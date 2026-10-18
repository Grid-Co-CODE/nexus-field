package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A faxina das fotos da reserva da câmera (o &lt;input capture&gt;): o BridgeWebChromeClient do Capacitor grava cada
 * foto em getExternalFilesDir(Pictures) (ou no cache, quando não há armazenamento externo) e nunca apaga. A página já
 * copiou a foto para o IndexedDB dela; o arquivo com mais de 48 h é sobra. O relógio é passado de fora.
 */
public class FaxinaDaCameraTest {

    private static final long HORA_MS = 3_600_000L;
    private static final long AGORA = 1_800_000_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File fotos;
    private File cache;

    @Before
    public void preparar() throws IOException {
        fotos = tmp.newFolder("Pictures");
        cache = tmp.newFolder("cache");
    }

    private File arquivo(File pasta, String nome, long idadeMs) throws IOException {
        File f = new File(pasta, nome);
        Files.write(f.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(f.setLastModified(AGORA - idadeMs));
        return f;
    }

    @Test
    public void naPastaDasFotosSaiTodoArquivoComMaisDe48h() throws IOException {
        File velha = arquivo(fotos, "JPEG_20261014_101010_123.jpg", 49 * HORA_MS);
        File vaziaVelha = new File(fotos, "JPEG_20261013_090000_7.jpg"); // a captura cancelada deixa o arquivo vazio
        assertTrue(vaziaVelha.createNewFile());
        assertTrue(vaziaVelha.setLastModified(AGORA - 72 * HORA_MS));
        File deOutroNome = arquivo(fotos, "outra.jpg", 50 * HORA_MS);
        File nova = arquivo(fotos, "JPEG_20261017_080000_9.jpg", 47 * HORA_MS);
        File agora = arquivo(fotos, "JPEG_20261017_120000_1.jpg", 0);

        assertEquals(3, FaxinaDaCamera.apagarVelhas(fotos, cache, AGORA));

        assertFalse(velha.exists());
        assertFalse(vaziaVelha.exists());
        assertFalse("a pasta é só das capturas: o nome não importa", deOutroNome.exists());
        assertTrue("a foto de ontem pode estar a caminho do IndexedDB", nova.exists());
        assertTrue(agora.exists());
    }

    @Test
    public void exatamente48hFica() throws IOException {
        File limite = arquivo(fotos, "JPEG_1.jpg", 48 * HORA_MS);
        File passou = arquivo(fotos, "JPEG_2.jpg", 48 * HORA_MS + 1000);
        assertEquals(1, FaxinaDaCamera.apagarVelhas(fotos, cache, AGORA));
        assertTrue(limite.exists());
        assertFalse(passou.exists());
    }

    @Test
    public void noCacheSaiSoACapturaDoCapacitor() throws IOException {
        // O cache do app tem também a WebView e o instalador da atualização: só o JPEG_*.jpg do createTempFile sai.
        File captura = arquivo(cache, "JPEG_20261010_101010_55.jpg", 49 * HORA_MS);
        File daWebView = arquivo(cache, "WebView-cache.bin", 100 * HORA_MS);
        File outroJpg = arquivo(cache, "miniatura.jpg", 100 * HORA_MS);
        File semExtensao = arquivo(cache, "JPEG_20261010_101010_56", 100 * HORA_MS);
        File atualizacao = new File(cache, "atualizacao");
        assertTrue(atualizacao.mkdir());
        File apk = arquivo(atualizacao, "app-campo.apk", 100 * HORA_MS);
        File capturaNova = arquivo(cache, "JPEG_20261017_101010_57.jpg", HORA_MS);

        assertEquals(1, FaxinaDaCamera.apagarVelhas(fotos, cache, AGORA));

        assertFalse(captura.exists());
        assertTrue(daWebView.exists());
        assertTrue(outroJpg.exists());
        assertTrue(semExtensao.exists());
        assertTrue("subpasta não é varrida", apk.exists());
        assertTrue(capturaNova.exists());
    }

    @Test
    public void pastaDentroDasFotosNaoESeguidaNemApagada() throws IOException {
        File sub = new File(fotos, "JPEG_pasta.jpg");
        assertTrue(sub.mkdir());
        File dentro = arquivo(sub, "JPEG_1.jpg", 100 * HORA_MS);
        assertTrue(sub.setLastModified(AGORA - 100 * HORA_MS));
        assertEquals(0, FaxinaDaCamera.apagarVelhas(fotos, cache, AGORA));
        assertTrue(sub.isDirectory());
        assertTrue(dentro.exists());
    }

    @Test
    public void arquivoDoFuturoFica() throws IOException {
        // A hora do aparelho voltou: a idade é negativa, e na dúvida o arquivo fica.
        File futuro = arquivo(fotos, "JPEG_1.jpg", -100 * HORA_MS);
        assertEquals(0, FaxinaDaCamera.apagarVelhas(fotos, cache, AGORA));
        assertTrue(futuro.exists());
    }

    @Test
    public void semPastaNaoLanca() {
        // getExternalFilesDir devolve null sem armazenamento externo montado.
        assertEquals(0, FaxinaDaCamera.apagarVelhas(null, null, AGORA));
        assertEquals(0, FaxinaDaCamera.apagarVelhas(new File(tmp.getRoot(), "nao-existe"), cache, AGORA));
    }
}
