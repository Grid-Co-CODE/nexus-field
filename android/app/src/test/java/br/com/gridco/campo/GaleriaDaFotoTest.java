package br.com.gridco.campo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Executor;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

/**
 * A cópia de cada foto do App de Campo na galeria do celular (1.2.8): o que é foto, a decodificação, a deduplicação pelo
 * conteúdo (que atravessa o app fechado e tem teto), o Android 10 em diante, o trabalho fora da thread de quem chama e a
 * gravação no MediaStore. O gravador, a thread e o relógio são de mentira, menos no teste do MediaStore, que passa pelo
 * ContentResolver do Robolectric com um provedor "media" de mentira. Os e-mails abaixo são fictícios.
 */
@RunWith(RobolectricTestRunner.class)
public class GaleriaDaFotoTest {

    private static final String EMAIL = "tecnico.ficticio@exemplo.com.br";
    private static final long AGORA = Instant.parse("2026-11-24T12:30:05Z").toEpochMilli();
    // A Uri que o provedor de mentira dá à primeira inserção (cada teste tem o seu).
    private static final Uri PRIMEIRA = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1);

    // Os começos de verdade dos três formatos (a assinatura que o formatoPelosBytes confere) e um resto qualquer.
    private static final byte[] JPEG = bytes(new int[] {0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0, 1, 2, 3});
    private static final byte[] OUTRO_JPEG = bytes(new int[] {0xff, 0xd8, 0xff, 0xe1, 0x00, 0x10, 'E', 'x', 'i', 'f', 9, 8});
    private static final byte[] PNG = bytes(new int[] {0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d});
    private static final byte[] WEBP = bytes(new int[] {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P'});

    @Rule public TemporaryFolder pasta = new TemporaryFolder();

    private GravadorFalso gravador;
    private List<String> avisos;
    private File arquivoDoRegistro;

    @Before
    public void preparar() throws IOException {
        ShadowLog.clear();
        gravador = new GravadorFalso();
        avisos = new ArrayList<>();
        arquivoDoRegistro = new File(pasta.newFolder("files"), GaleriaDaFoto.ARQUIVO_DO_REGISTRO);
    }

    @After
    public void semDadosDoTecnicoNoLog() {
        List<String> tudo = new ArrayList<>(avisos);
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) tudo.add(item.tag + " " + item.msg);
        for (String linha : tudo) {
            for (String proibido : new String[] {EMAIL, "@exemplo", "gc_ronda_", "/9j/", "data:image"}) {
                if (linha.contains(proibido)) fail("o log levou \"" + proibido + "\": " + linha);
            }
        }
    }

    // --- o que é foto ---------------------------------------------------------------------------------------------------

    @Test
    public void oTextoQueEFotoEODataUrlDeJpegPngOuWebp() {
        assertTrue(GaleriaDaFoto.ehFotoEmTexto(dataUrl("image/jpeg", JPEG)));
        assertTrue(GaleriaDaFoto.ehFotoEmTexto(dataUrl("image/png", PNG)));
        assertTrue(GaleriaDaFoto.ehFotoEmTexto(dataUrl("image/webp", WEBP)));
        assertTrue("o cabeçalho em maiúsculas é o mesmo", GaleriaDaFoto.ehFotoEmTexto("DATA:IMAGE/JPEG;BASE64,/9j/"));
        assertEquals("image/jpeg", GaleriaDaFoto.mimeDoTexto("DATA:IMAGE/JPEG;BASE64,/9j/"));
        assertTrue("um parâmetro antes do base64", GaleriaDaFoto.ehFotoEmTexto("data:image/jpeg;name=x;base64,/9j/"));

        for (String naoEFoto : new String[] {
            null, "", "x", "gc_ronda_" + EMAIL,
            "data:image/gif;base64,R0lGODlh", // a galeria mostraria, mas o site não faz GIF: fica fora
            "data:image/svg+xml;base64,PHN2Zz4=", // texto, e pode ter script
            "data:text/plain;base64,eA==",
            "data:video/mp4;base64,AAAAIGZ0eXA=",
            "data:audio/webm;base64,GkXfow==",
            "data:image/jpeg,naoebase64", // sem o ;base64
            "data:image/jpeg;base64", // sem a vírgula
            "data:image/jpeg" + ";x".repeat(80) + ";base64,/9j/", // o cabeçalho que não acaba nos 128 primeiros
            "{\"url\":\"data:image/jpeg;base64,/9j/\"}", // o JSON com a foto dentro não é foto em texto
            " data:image/jpeg;base64,/9j/",
        }) {
            assertFalse(String.valueOf(naoEFoto), GaleriaDaFoto.ehFotoEmTexto(naoEFoto));
        }
    }

    @Test
    public void oBlobQueEFotoEODeMimeJpegPngOuWebp() {
        for (String mime : new String[] {"image/jpeg", "IMAGE/PNG", " image/webp "}) {
            assertTrue(mime, GaleriaDaFoto.ehMimeDeFoto(mime));
        }
        for (String mime : new String[] {null, "", "video/mp4", "video/webm", "video/quicktime", "audio/webm",
            "audio/mpeg", "image/gif", "image/svg+xml", "application/octet-stream", "image/jpeg;x"}) {
            assertFalse(String.valueOf(mime), GaleriaDaFoto.ehMimeDeFoto(mime));
        }
    }

    @Test
    public void aDecodificacaoDevolveOsBytesDaFotoEORecusaOBase64Torto() {
        assertArrayEquals(JPEG, GaleriaDaFoto.decodificar(dataUrl("image/jpeg", JPEG)));
        assertArrayEquals(PNG, GaleriaDaFoto.decodificar(dataUrl("image/png", PNG)));
        assertNull("o android.util.Base64 pularia o @ em silêncio", GaleriaDaFoto.decodificar("data:image/jpeg;base64,/9j@"));
        assertNull("o tamanho que não é múltiplo de 4", GaleriaDaFoto.decodificar("data:image/jpeg;base64,/9j/4A"));
        assertNull("vazio", GaleriaDaFoto.decodificar("data:image/jpeg;base64,"));
        assertNull("não é foto", GaleriaDaFoto.decodificar("data:text/plain;base64,eA=="));
        assertNull(GaleriaDaFoto.decodificar(null));
    }

    @Test
    public void oFormatoVemDosBytesENaoDoQueAPaginaDeclarou() {
        assertEquals("image/jpeg", GaleriaDaFoto.formatoPelosBytes(JPEG));
        assertEquals("image/png", GaleriaDaFoto.formatoPelosBytes(PNG));
        assertEquals("image/webp", GaleriaDaFoto.formatoPelosBytes(WEBP));
        assertNull(GaleriaDaFoto.formatoPelosBytes(new byte[0]));
        assertNull(GaleriaDaFoto.formatoPelosBytes(null));
        assertNull(GaleriaDaFoto.formatoPelosBytes("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertNull("o RIFF de áudio não é WebP",
            GaleriaDaFoto.formatoPelosBytes(bytes(new int[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'})));
        assertNull("o JPEG cortado no começo", GaleriaDaFoto.formatoPelosBytes(bytes(new int[] {0xff, 0xd8})));
    }

    @Test
    public void oNomeNaGaleriaEGridCoComADataEAHoraLocaisDoAparelho() {
        TimeZone brasilia = TimeZone.getTimeZone("America/Sao_Paulo");
        assertEquals("GridCo_20261124_093005.jpg", GaleriaDaFoto.nomeDoArquivo(AGORA, brasilia, "image/jpeg"));
        assertEquals("GridCo_20261124_093005.png", GaleriaDaFoto.nomeDoArquivo(AGORA, brasilia, "image/png"));
        assertEquals("GridCo_20261124_093005.webp", GaleriaDaFoto.nomeDoArquivo(AGORA, brasilia, "image/webp"));
        assertEquals("o fuso de Mato Grosso", "GridCo_20261124_083005.jpg",
            GaleriaDaFoto.nomeDoArquivo(AGORA, TimeZone.getTimeZone("America/Cuiaba"), "image/jpeg"));
        assertEquals("GridCo_20261124_123005.jpg",
            GaleriaDaFoto.nomeDoArquivo(AGORA, TimeZone.getTimeZone("UTC"), "image/jpeg"));
    }

    // --- a gravação e a deduplicação ----------------------------------------------------------------------------------

    @Test
    public void aFotoEmTextoVaiAGaleriaComOFormatoAHoraEONome() {
        GaleriaDaFoto galeria = galeria(Runnable::run);
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));

        assertEquals(1, gravador.gravadas.size());
        Gravada g = gravador.gravadas.get(0);
        assertArrayEquals(JPEG, g.bytes);
        assertEquals("image/jpeg", g.mime);
        assertEquals(AGORA, g.tiradaEm);
        assertTrue(g.nome, g.nome.matches("GridCo_\\d{8}_\\d{6}\\.jpg"));
        assertTrue(avisos.isEmpty());
    }

    @Test
    public void oBlobQueDizJpegESeLeComoPngVaiComoPng() throws IOException {
        GaleriaDaFoto galeria = galeria(Runnable::run);
        galeria.fotoEmArquivo(arquivo("blob.bin", PNG), "image/jpeg");
        assertEquals(1, gravador.gravadas.size());
        assertEquals("image/png", gravador.gravadas.get(0).mime);
        assertTrue(gravador.gravadas.get(0).nome.endsWith(".png"));
    }

    @Test
    public void oConteudoQueNaoEFotoNaoVaiEAvisaSemOConteudo() throws IOException {
        GaleriaDaFoto galeria = galeria(Runnable::run);
        galeria.fotoEmArquivo(arquivo("blob.bin", ("gc_ronda_" + EMAIL).getBytes(StandardCharsets.UTF_8)), "image/jpeg");
        galeria.fotoEmTexto("data:image/jpeg;base64," + Base64.encodeToString(("{\"e\":\"" + EMAIL + "\"}")
            .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        galeria.fotoEmTexto("data:image/jpeg;base64,/9j@");
        assertTrue(gravador.gravadas.isEmpty());
        assertEquals(3, avisos.size());
    }

    @Test
    public void aMesmaFotoPorDuasChavesEDoisJeitosGravaUmaVezSo() throws IOException {
        // O caso do site: o rascunho d_ (o dataURL, na captura) e a fila f_ (a mesma foto, no Concluir da OS), e a Ronda
        // que guarda a mesma foto em Blob. Três cópias, uma foto na galeria.
        GaleriaDaFoto galeria = galeria(Runnable::run);
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        galeria.fotoEmArquivo(arquivo("f.bin", JPEG), "image/jpeg");
        assertEquals(1, gravador.gravadas.size());

        galeria.fotoEmTexto(dataUrl("image/jpeg", OUTRO_JPEG));
        assertEquals("outra foto vai", 2, gravador.gravadas.size());
        assertTrue(avisos.isEmpty());
    }

    @Test
    public void oRegistroAtravessaOAppFechado() {
        galeria(Runnable::run).fotoEmTexto(dataUrl("image/jpeg", JPEG));
        assertEquals(1, gravador.gravadas.size());
        assertTrue(arquivoDoRegistro.isFile());

        // O processo novo: outra galeria, outro registro na memória, o mesmo arquivo.
        GravadorFalso doProcessoNovo = new GravadorFalso();
        GaleriaDaFoto nova = new GaleriaDaFoto(Build.VERSION_CODES.Q, doProcessoNovo,
            new GaleriaDaFoto.Registro(arquivoDoRegistro, GaleriaDaFoto.Registro.TETO), Runnable::run, () -> AGORA,
            avisos::add);
        nova.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        assertTrue("a foto que já está na galeria não vai de novo depois de o app fechar", doProcessoNovo.gravadas.isEmpty());
        nova.fotoEmTexto(dataUrl("image/jpeg", OUTRO_JPEG));
        assertEquals(1, doProcessoNovo.gravadas.size());
    }

    @Test
    public void oRegistroLembraOsMaisNovosAteOTetoEOArquivoNaoCresceSemFim() throws IOException {
        GaleriaDaFoto.Registro registro = new GaleriaDaFoto.Registro(arquivoDoRegistro, 10);
        List<String> resumos = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String r = GaleriaDaFoto.resumo(("foto " + i).getBytes(StandardCharsets.UTF_8));
            resumos.add(r);
            assertTrue(registro.anotar(r));
            assertTrue("o arquivo passa no máximo um quinto do teto além dele", linhas(arquivoDoRegistro) <= 12);
        }
        for (int i = 0; i < 20; i++) assertFalse("a " + i + " saiu pelo teto", registro.tem(resumos.get(i)));
        for (int i = 20; i < 30; i++) assertTrue("a " + i + " ficou", registro.tem(resumos.get(i)));

        GaleriaDaFoto.Registro lido = new GaleriaDaFoto.Registro(arquivoDoRegistro, 10);
        for (int i = 0; i < 20; i++) assertFalse(lido.tem(resumos.get(i)));
        for (int i = 20; i < 30; i++) assertTrue(lido.tem(resumos.get(i)));
        assertEquals("o teto de produção", 5000, GaleriaDaFoto.Registro.TETO);
    }

    @Test
    public void aLinhaTortaDoRegistroNaoContaEAMesmaFotoNaoSeRepeteNoArquivo() throws IOException {
        String valido = GaleriaDaFoto.resumo(JPEG);
        Files.write(arquivoDoRegistro.toPath(), ("lixo\n" + valido.substring(0, 10) + "\n" + valido + "\n")
            .getBytes(StandardCharsets.US_ASCII));
        GaleriaDaFoto.Registro registro = new GaleriaDaFoto.Registro(arquivoDoRegistro, 10);
        assertTrue(registro.tem(valido));
        assertFalse(registro.tem(valido.substring(0, 10)));
        assertTrue(registro.anotar(valido));
        assertEquals("anotar a que já está não escreve de novo", 3, linhas(arquivoDoRegistro));
        assertEquals(32, valido.length());
    }

    @Test
    public void oRegistroIlegivelValeComoVazio() throws IOException {
        assertTrue(arquivoDoRegistro.mkdirs()); // uma pasta no lugar do arquivo: nem lê, nem grava
        GaleriaDaFoto galeria = galeria(Runnable::run);
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        assertEquals("a foto vai mesmo sem o registro no disco", 1, gravador.gravadas.size());
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        assertEquals("e a memória ainda deduplica até o app fechar", 1, gravador.gravadas.size());
        assertEquals(1, avisos.size());
        assertTrue(avisos.get(0), avisos.get(0).contains("registro"));
    }

    @Test
    public void aFotoQueFalhouVaiDeNovoNaProximaCopia() throws IOException {
        GaleriaDaFoto galeria = galeria(Runnable::run);
        gravador.responder = false;
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        assertEquals(1, gravador.tentativas);
        assertEquals(Arrays.asList("uma foto não foi para a galeria (1 falha(s) nesta partida)"), avisos);

        gravador.responder = true;
        galeria.fotoEmArquivo(arquivo("f.bin", JPEG), "image/jpeg");
        assertEquals("a falha não entrou no registro", 2, gravador.tentativas);
        assertEquals(1, gravador.gravadas.size());
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        assertEquals("a que gravou entrou", 2, gravador.tentativas);
    }

    @Test
    public void oGravadorQueLancaNaoDerrubaNadaENaoEntraNoRegistro() {
        GaleriaDaFoto galeria = galeria(Runnable::run);
        gravador.lancar = new IllegalStateException("gc_ronda_" + EMAIL);
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        assertEquals(1, avisos.size());
        gravador.lancar = null;
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        assertEquals(1, gravador.gravadas.size());
    }

    @Test
    public void oTrabalhoRodaNaThreadDaGaleriaENaoNaDeQuemChama() throws IOException {
        FilaDeTarefas fila = new FilaDeTarefas();
        GaleriaDaFoto galeria = galeria(fila);
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        galeria.fotoEmArquivo(arquivo("f.bin", OUTRO_JPEG), "image/jpeg");
        assertEquals("quem chama (a thread do espelho) só põe na fila", 0, gravador.tentativas);
        assertFalse("nem o registro é lido na thread de quem chama", arquivoDoRegistro.exists());
        assertEquals(2, fila.tarefas.size());

        fila.rodar();
        assertEquals(2, gravador.gravadas.size());
    }

    @Test
    public void aFilaCheiaDeixaAFotoDeForaFechaOArquivoEAvisa() throws IOException {
        FilaDeTarefas fila = new FilaDeTarefas();
        GaleriaDaFoto galeria = new GaleriaDaFoto(Build.VERSION_CODES.Q, gravador, registro(), fila, () -> AGORA,
            avisos::add, 3L * JPEG.length);
        File primeiro = arquivo("a.bin", JPEG);
        File segundo = arquivo("b.bin", OUTRO_JPEG);
        galeria.fotoEmArquivo(primeiro, "image/jpeg");
        galeria.fotoEmArquivo(segundo, "image/jpeg"); // dois arquivos cabem no teto de três
        galeria.fotoEmTexto(dataUrl("image/jpeg", PNG)); // o texto conta 2 bytes por caractere: não cabe

        assertEquals(2, fila.tarefas.size());
        assertEquals(Arrays.asList("a fila da galeria está cheia; uma foto ficou fora dela (1 nesta partida)"), avisos);

        fila.rodar();
        assertEquals(2, gravador.gravadas.size());
        // Com a fila vazia, cabe de novo.
        galeria.fotoEmArquivo(arquivo("c.bin", PNG), "image/png");
        assertEquals(1, fila.tarefas.size());
        fila.rodar();
        assertEquals(3, gravador.gravadas.size());
        // O arquivo lido foi fechado (no Windows, o aberto não se apaga).
        assertTrue(primeiro.delete());
        assertTrue(segundo.delete());
    }

    @Test
    public void aThreadQueRecusaNaoDerrubaQuemChama() throws IOException {
        File f = arquivo("f.bin", JPEG);
        GaleriaDaFoto galeria = galeria(tarefa -> {
            throw new java.util.concurrent.RejectedExecutionException("fora");
        });
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        galeria.fotoEmArquivo(f, "image/jpeg");
        assertEquals(2, avisos.size());
        assertTrue("o arquivo da tarefa recusada foi fechado", f.delete());
    }

    @Test
    public void oBlobVazioOuSemArquivoNaoVai() throws IOException {
        GaleriaDaFoto galeria = galeria(Runnable::run);
        galeria.fotoEmArquivo(arquivo("vazio.bin", new byte[0]), "image/jpeg");
        galeria.fotoEmArquivo(new File(pasta.getRoot(), "nao-existe.bin"), "image/jpeg");
        galeria.fotoEmArquivo(null, "image/jpeg");
        galeria.fotoEmTexto(null);
        assertTrue(gravador.gravadas.isEmpty());
        assertEquals(2, avisos.size());
    }

    // --- o Android 10 em diante ------------------------------------------------------------------------------------------

    @Test
    public void antesDoAndroid10NadaVaiAGaleria() throws IOException {
        FilaDeTarefas fila = new FilaDeTarefas();
        GaleriaDaFoto galeria = new GaleriaDaFoto(Build.VERSION_CODES.P, gravador, registro(), fila, () -> AGORA,
            avisos::add);
        assertFalse(galeria.ativa());
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        galeria.fotoEmArquivo(arquivo("f.bin", JPEG), "image/jpeg");
        assertTrue("nem vai à fila", fila.tarefas.isEmpty());
        assertEquals(0, gravador.tentativas);
        assertTrue(avisos.isEmpty());
        assertTrue(new GaleriaDaFoto(Build.VERSION_CODES.Q, gravador, registro(), fila, () -> AGORA, avisos::add).ativa());
        assertFalse("sem gravador", new GaleriaDaFoto(Build.VERSION_CODES.Q, null, registro(), fila, () -> AGORA,
            avisos::add).ativa());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.P)
    public void aGaleriaDoAppNoAndroid9NaoGrava() {
        Context ctx = RuntimeEnvironment.getApplication();
        GaleriaDaFoto galeria = GaleriaDaFoto.doApp(ctx);
        assertFalse(galeria.ativa());
        galeria.fotoEmTexto(dataUrl("image/jpeg", JPEG));
        assertTrue("nada foi ao MediaStore", shadowOf(ctx.getContentResolver()).getInsertStatements().isEmpty());
        assertFalse(new File(ctx.getFilesDir(), GaleriaDaFoto.ARQUIVO_DO_REGISTRO).exists());
    }

    @Test
    public void aGaleriaDoAppNoAndroid16EstaLigada() {
        assertTrue(GaleriaDaFoto.doApp(RuntimeEnvironment.getApplication()).ativa());
    }

    // --- o MediaStore de verdade (o ContentResolver) -----------------------------------------------------------------

    @Test
    public void oMediaStoreRecebeALinhaPendenteNaPastaGridCoOsBytesEDepoisAFotoAVista() {
        MediaFalso media = Robolectric.buildContentProvider(MediaFalso.class).create("media").get();
        ContentResolver resolver = RuntimeEnvironment.getApplication().getContentResolver();
        ByteArrayOutputStream escrito = new ByteArrayOutputStream();
        shadowOf(resolver).registerOutputStream(PRIMEIRA, escrito);

        GaleriaDaFoto.Gravador noMediaStore = new GaleriaDaFoto.NoMediaStore(new GaleriaDaFoto.DestinoDoSistema(resolver));
        assertTrue(noMediaStore.gravar(JPEG, "image/jpeg", "GridCo_20261124_093005.jpg", AGORA));

        assertEquals(1, media.inseridas.size());
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, media.onde.get(0));
        ContentValues linha = media.inseridas.get(0);
        assertEquals("Pictures/Grid Co", linha.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals("GridCo_20261124_093005.jpg", linha.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/jpeg", linha.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals(Long.valueOf(AGORA), linha.getAsLong(MediaStore.MediaColumns.DATE_TAKEN));
        assertEquals("pendente durante a escrita", Integer.valueOf(1), linha.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertArrayEquals(JPEG, escrito.toByteArray());
        assertEquals(1, media.atualizadas.size());
        assertEquals("à vista no fim", Integer.valueOf(0),
            media.atualizadas.get(0).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals(Arrays.asList(PRIMEIRA), media.atualizadasOnde);
        assertTrue("nada apagado", media.apagadas.isEmpty());
    }

    @Test
    public void aEscritaQueFalhaApagaALinhaPendente() {
        MediaFalso media = Robolectric.buildContentProvider(MediaFalso.class).create("media").get();
        ContentResolver resolver = RuntimeEnvironment.getApplication().getContentResolver();
        shadowOf(resolver).registerOutputStream(PRIMEIRA, new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disco cheio");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("disco cheio");
            }
        });
        GaleriaDaFoto.Gravador noMediaStore = new GaleriaDaFoto.NoMediaStore(new GaleriaDaFoto.DestinoDoSistema(resolver));
        assertFalse(noMediaStore.gravar(JPEG, "image/jpeg", "GridCo_20261124_093005.jpg", AGORA));
        assertEquals(Arrays.asList(PRIMEIRA), media.apagadas);
        assertTrue("nunca ficou à vista", media.atualizadas.isEmpty());
    }

    @Test
    public void aFotoQueNaoFicaAVistaSaiENaoContaComoGravada() {
        MediaFalso media = Robolectric.buildContentProvider(MediaFalso.class).create("media").get();
        ContentResolver resolver = RuntimeEnvironment.getApplication().getContentResolver();
        shadowOf(resolver).registerOutputStream(PRIMEIRA, new ByteArrayOutputStream());
        media.atualizaQuantas = 0;
        GaleriaDaFoto.Gravador noMediaStore = new GaleriaDaFoto.NoMediaStore(new GaleriaDaFoto.DestinoDoSistema(resolver));
        assertFalse(noMediaStore.gravar(JPEG, "image/jpeg", "x.jpg", AGORA));
        assertEquals(Arrays.asList(PRIMEIRA), media.apagadas);
    }

    @Test
    public void oSistemaQueNaoCriaALinhaNaoGravaNemApaga() {
        MediaFalso media = Robolectric.buildContentProvider(MediaFalso.class).create("media").get();
        media.recusarInsercao = true;
        ContentResolver resolver = RuntimeEnvironment.getApplication().getContentResolver();
        GaleriaDaFoto.Gravador noMediaStore = new GaleriaDaFoto.NoMediaStore(new GaleriaDaFoto.DestinoDoSistema(resolver));
        assertFalse(noMediaStore.gravar(JPEG, "image/jpeg", "x.jpg", AGORA));
        assertTrue(media.apagadas.isEmpty());
    }

    // --- pelo canal: o Mensageiro com o espelho e a galeria de verdade ---------------------------------------------------

    @Test
    public void peloCanalAMesmaFotoNoRascunhoNaFilaENoBlobVaiUmaVezEOResto() {
        Context ctx = RuntimeEnvironment.getApplication();
        EspelhoSqlite espelho = new EspelhoSqlite(ctx, new CifraFalsa());
        try {
            List<String> erros = new ArrayList<>();
            Mensageiro m = new Mensageiro(espelho, new SoErros(erros), new Mensageiro.Disjuntor(), () -> 1L,
                galeria(Runnable::run));
            String foto = dataUrl("image/jpeg", JPEG);
            // O mesmo JPEG em dois pedaços de Blob (o base64 de cada pedaço é dos bytes dele).
            String b64a = Base64.encodeToString(Arrays.copyOfRange(JPEG, 0, 6), Base64.NO_WRAP);
            String b64b = Base64.encodeToString(Arrays.copyOfRange(JPEG, 6, JPEG.length), Base64.NO_WRAP);

            assertTrue(m.tratar(idbPut("d_tk1_1_0", "texto", foto)));
            assertTrue(m.tratar(idbPut("f_tk1_2_0", "texto", foto)));
            assertTrue(m.tratar(pedaco("idb:2026-11-24_1_0", "r1-a-1", 0, 2, "image/jpeg", b64a)));
            assertEquals("o pedaço do meio não vai", 1, gravador.tentativas);
            assertTrue(m.tratar(pedaco("idb:2026-11-24_1_0", "r1-a-1", 1, 2, "image/jpeg", b64b)));
            assertEquals("a mesma foto em três chaves, uma na galeria", 1, gravador.gravadas.size());

            // O JSON com a foto dentro, o texto que não é foto e o vídeo não vão.
            assertTrue(m.tratar(idbPut("rdraft:u1", "json", "{\"f\":\"" + dataUrl("image/jpeg", OUTRO_JPEG) + "\"}")));
            assertTrue(m.tratar(idbPut("prog", "texto", "{\"x\":1}")));
            assertTrue(m.tratar(pedaco("v_tk1_3", "r1-a-2", 0, 1, "video/mp4",
                Base64.encodeToString(OUTRO_JPEG, Base64.NO_WRAP))));
            assertEquals(1, gravador.tentativas);

            // Outra foto, em Blob de um pedaço só: vai.
            assertTrue(m.tratar(pedaco("idb:zl_1", "r1-a-3", 0, 1, "image/jpeg",
                Base64.encodeToString(OUTRO_JPEG, Base64.NO_WRAP))));
            assertEquals(2, gravador.gravadas.size());
            assertArrayEquals(OUTRO_JPEG, gravador.gravadas.get(1).bytes);
            assertEquals("o espelho guardou tudo como antes", 7, espelho.idbChaves().size());
            assertTrue(erros.toString(), erros.isEmpty());
        } finally {
            espelho.close();
        }
    }

    // --- ajudantes -----------------------------------------------------------------------------------------------------

    private GaleriaDaFoto galeria(Executor thread) {
        return new GaleriaDaFoto(Build.VERSION_CODES.Q, gravador, registro(), thread, () -> AGORA, avisos::add);
    }

    private GaleriaDaFoto.Registro registro() {
        return new GaleriaDaFoto.Registro(arquivoDoRegistro, GaleriaDaFoto.Registro.TETO);
    }

    private File arquivo(String nome, byte[] conteudo) throws IOException {
        File f = pasta.newFile(nome);
        Files.write(f.toPath(), conteudo);
        return f;
    }

    private static int linhas(File f) throws IOException {
        return Files.readAllLines(f.toPath(), StandardCharsets.US_ASCII).size();
    }

    static String dataUrl(String mime, byte[] conteudo) {
        return "data:" + mime + ";base64," + Base64.encodeToString(conteudo, Base64.NO_WRAP);
    }

    private static String idbPut(String k, String tipo, String v) {
        return "{\"t\":\"idbPut\",\"k\":\"" + k + "\",\"tipo\":\"" + tipo + "\",\"v\":" + org.json.JSONObject.quote(v) + "}";
    }

    private static String pedaco(String k, String id, int i, int n, String mime, String b64) {
        return "{\"t\":\"idbPedaco\",\"k\":\"" + k + "\",\"id\":\"" + id + "\",\"i\":" + i + ",\"n\":" + n
            + ",\"mime\":\"" + mime + "\",\"b64\":\"" + b64 + "\"}";
    }

    private static byte[] bytes(int[] valores) {
        byte[] b = new byte[valores.length];
        for (int i = 0; i < valores.length; i++) b[i] = (byte) valores[i];
        return b;
    }

    private static final class Gravada {
        final byte[] bytes;
        final String mime;
        final String nome;
        final long tiradaEm;

        Gravada(byte[] bytes, String mime, String nome, long tiradaEm) {
            this.bytes = bytes;
            this.mime = mime;
            this.nome = nome;
            this.tiradaEm = tiradaEm;
        }
    }

    /** O gravador de mentira: anota o que gravou, e responde false ou lança quando pedido. */
    private static final class GravadorFalso implements GaleriaDaFoto.Gravador {
        final List<Gravada> gravadas = new ArrayList<>();
        int tentativas;
        boolean responder = true;
        RuntimeException lancar;

        @Override
        public boolean gravar(byte[] bytes, String mime, String nome, long tiradaEm) {
            tentativas++;
            if (lancar != null) throw lancar;
            if (responder) gravadas.add(new Gravada(bytes, mime, nome, tiradaEm));
            return responder;
        }
    }

    /** A thread da galeria de mentira: guarda as tarefas, e o teste as roda quando quer. */
    private static final class FilaDeTarefas implements Executor {
        final List<Runnable> tarefas = new ArrayList<>();

        @Override
        public void execute(Runnable tarefa) {
            tarefas.add(tarefa);
        }

        void rodar() {
            List<Runnable> agora = new ArrayList<>(tarefas);
            tarefas.clear();
            for (Runnable t : agora) t.run();
        }
    }

    /** As ações do plugin que este teste não usa: só os avisos. */
    private static final class SoErros implements Mensageiro.Acoes {
        final List<String> erros;

        SoErros(List<String> erros) {
            this.erros = erros;
        }

        @Override public void precisoRetrato(org.json.JSONObject retrato) { }
        @Override public void restaurado(org.json.JSONObject retratoLeve) { }
        @Override public void idbPedir() { }
        @Override public void idbRecusado() { }
        @Override public void abrirFora(String url) { }
        @Override public void pushPedirToken() { }
        @Override public void pushSaiu() { }
        @Override public void pushRegistrado() { }
        @Override public void osAberta(String os) { }
        @Override public void pushAdmin(boolean admin) { }
        @Override public void pushTeste(AtalhosDeTeste.Resultado resultado, int aparelhos, int pedido) { }
        @Override public void equipePedirAparelho(String conta) { }
        @Override public void equipeChave(boolean coleta, String chave, String conta) { }
        @Override public void erro(String onde, String msg) { erros.add(onde + ": " + msg); }
    }

    /** O provedor "media" de mentira: a coleção das imagens do MediaStore, só anotada. */
    public static final class MediaFalso extends ContentProvider {
        final List<Uri> onde = new ArrayList<>();
        final List<ContentValues> inseridas = new ArrayList<>();
        final List<ContentValues> atualizadas = new ArrayList<>();
        final List<Uri> atualizadasOnde = new ArrayList<>();
        final List<Uri> apagadas = new ArrayList<>();
        boolean recusarInsercao;
        int atualizaQuantas = 1;
        private long id = 0;

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            onde.add(uri);
            if (recusarInsercao) return null;
            inseridas.add(new ContentValues(values));
            return ContentUris.withAppendedId(uri, ++id);
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            atualizadas.add(new ContentValues(values));
            atualizadasOnde.add(uri);
            return atualizaQuantas;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            apagadas.add(uri);
            return 1;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }
    }
}
