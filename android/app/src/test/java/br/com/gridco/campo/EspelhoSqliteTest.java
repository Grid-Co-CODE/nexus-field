package br.com.gridco.campo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;

/**
 * O espelho nativo em SQLite e arquivos. Roda no Robolectric (SQLite e sistema de arquivos de verdade), com a cifra
 * falsa: o AES/GCM do Keystore é provado à parte (CifraKeystoreTest). Os e-mails abaixo são fictícios.
 */
@RunWith(RobolectricTestRunner.class)
public class EspelhoSqliteTest {

    private static final String IDENT = "{\"email\":\"tecnico@exemplo.com.br\",\"nome\":\"Tecnico Ficticio\"}";
    private static final long UMA_HORA_MS = 60L * 60L * 1000L;

    private Context ctx;
    private CifraFalsa cifra;
    private EspelhoSqlite espelho;

    @Before
    public void abrir() {
        ShadowLog.clear();
        ctx = RuntimeEnvironment.getApplication();
        cifra = new CifraFalsa();
        espelho = new EspelhoSqlite(ctx, cifra);
    }

    @After
    public void fechar() {
        espelho.close();
    }

    // --- ajudantes -----------------------------------------------------------------------------------------------

    private File pasta(String relativo) {
        return new File(ctx.getFilesDir(), relativo);
    }

    private SQLiteDatabase bancoCru() {
        return SQLiteDatabase.openDatabase(ctx.getDatabasePath("espelho.db").getPath(), null,
            SQLiteDatabase.OPEN_READONLY);
    }

    private static final class Item {
        String tipo;
        String mime;
        String texto;
        File arquivo;
    }

    private static Map<String, Item> idbTudo(ArmazemEspelho armazem) {
        Map<String, Item> mapa = new TreeMap<>();
        armazem.paraCadaIdb((k, tipo, mime, texto, arquivo) -> {
            Item item = new Item();
            item.tipo = tipo;
            item.mime = mime;
            item.texto = texto;
            item.arquivo = arquivo;
            mapa.put(k, item);
        });
        return mapa;
    }

    private static byte[] bytes(int... valores) {
        byte[] saida = new byte[valores.length];
        for (int i = 0; i < valores.length; i++) saida[i] = (byte) valores[i];
        return saida;
    }

    private static boolean contem(byte[] palheiro, byte[] agulha) {
        for (int i = 0; i + agulha.length <= palheiro.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(palheiro, i, i + agulha.length), agulha)) return true;
        }
        return false;
    }

    private static List<String> hashes(String... valores) {
        List<String> lista = new ArrayList<>();
        for (String v : valores) lista.add(Fnv.hash(v));
        return lista;
    }

    // --- localStorage: a fila --------------------------------------------------------------------------------------

    @Test
    public void semFilaNaoHaVersaoNenhuma() {
        assertTrue(espelho.filaHashes().isEmpty());
        assertNull(espelho.filaMaisNova());
    }

    @Test
    public void filaGuardaTresVersoesMaisNovaPrimeiro() {
        espelho.gravarLs("gc_campo_queue", "v1");
        espelho.gravarLs("gc_campo_queue", "v2");
        espelho.gravarLs("gc_campo_queue", "v3");
        espelho.gravarLs("gc_campo_queue", "v4");

        assertEquals(hashes("v4", "v3", "v2"), espelho.filaHashes());
        assertEquals("v4", espelho.filaMaisNova());
    }

    // Duas gravações iguais seguidas não são duas versões: a página regrava a fila com o mesmo conteúdo a toda hora, e
    // cada regravação empurraria uma versão de verdade para fora das três. A → B → A (diferentes em seguida) continuam
    // três versões, porque é isso que a regra da WebView em dia (versoes[0]) espera.
    @Test
    public void filaIgualASuaMaisNovaNaoGastaVersao() {
        espelho.gravarLs("gc_campo_queue", "A");
        espelho.gravarLs("gc_campo_queue", "B");
        espelho.gravarLs("gc_campo_queue", "B");
        espelho.gravarLs("gc_campo_queue", "B");
        assertEquals(hashes("B", "A"), espelho.filaHashes());

        espelho.gravarLs("gc_campo_queue", "A");
        assertEquals(hashes("A", "B", "A"), espelho.filaHashes());
        assertEquals("A", espelho.filaMaisNova());
    }

    @Test
    public void filaGuardaOTextoInteiroComAcentoEEmoji() {
        String fila = "[{\"obs\":\"ação ç 😀\",\"n\":1}]";
        espelho.gravarLs("gc_campo_queue", fila);
        assertEquals(fila, espelho.filaMaisNova());
        assertEquals(hashes(fila), espelho.filaHashes());
    }

    @Test
    public void apagarLsDaFilaApagaTodasAsVersoes() {
        espelho.gravarLs("gc_campo_queue", "v1");
        espelho.gravarLs("gc_campo_queue", "v2");
        espelho.gravarLs("gc_campo_queue", "v3");

        espelho.apagarLs("gc_campo_queue");

        assertTrue(espelho.filaHashes().isEmpty());
        assertNull(espelho.filaMaisNova());
    }

    // A fila tem tabela própria (as versões); o retrato a leva no campo `fila`, e a restauração do script ignora a
    // gc_campo_queue que vier dentro de `ls`. No lsTudo ela não aparece.
    @Test
    public void filaNaoApareceNoLsTudo() {
        espelho.gravarLs("gc_campo_queue", "[1]");
        espelho.gravarLs("gc_fech_draft", "{}");
        assertEquals(1, espelho.lsTudo().size());
        assertFalse(espelho.lsTudo().containsKey("gc_campo_queue"));
    }

    // --- localStorage: o resto --------------------------------------------------------------------------------------

    @Test
    public void gravarLerSobrescreverEApagar() {
        espelho.gravarLs("gc_fech_draft", "um");
        espelho.gravarLs("gc_zl_pend", "dois");
        espelho.gravarLs("gc_fech_draft", "tres");

        Map<String, String> tudo = espelho.lsTudo();
        assertEquals(2, tudo.size());
        assertEquals("tres", tudo.get("gc_fech_draft"));
        assertEquals("dois", tudo.get("gc_zl_pend"));

        espelho.apagarLs("gc_fech_draft");
        assertEquals(1, espelho.lsTudo().size());
        assertFalse(espelho.lsTudo().containsKey("gc_fech_draft"));

        espelho.apagarLs("chave_que_nunca_existiu"); // apagar o que não há não é erro
        assertEquals(1, espelho.lsTudo().size());
    }

    @Test
    public void lsGuardaValorVazioEUnicode() {
        espelho.gravarLs("gc_fb_draft", "");
        espelho.gravarLs("gc_zl_draft", "ação ç 😀 fim");
        assertEquals("", espelho.lsTudo().get("gc_fb_draft"));
        assertEquals("ação ç 😀 fim", espelho.lsTudo().get("gc_zl_draft"));
    }

    @Test
    public void identFicaCifradoEmRepouso() throws IOException {
        espelho.gravarLs("gc_campo_ident", IDENT);
        espelho.gravarLs("gc_fech_draft", "rascunho");

        try (SQLiteDatabase db = bancoCru();
             Cursor c = db.rawQuery("SELECT valor, cifrado FROM ls WHERE chave = ?", new String[] {"gc_campo_ident"})) {
            assertTrue(c.moveToFirst());
            byte[] cru = c.getBlob(0);
            assertEquals(1, c.getInt(1));
            assertFalse("o valor cru não pode ser o texto", Arrays.equals(IDENT.getBytes(StandardCharsets.UTF_8), cru));
            assertFalse(contem(cru, "tecnico@exemplo".getBytes(StandardCharsets.UTF_8)));
            assertTrue("passou pela cifra", contem(cru, CifraFalsa.PREFIXO));
        }
        // Só a identidade é cifrada; o resto fica em claro e a cifra não é chamada para ele.
        try (SQLiteDatabase db = bancoCru();
             Cursor c = db.rawQuery("SELECT cifrado FROM ls WHERE chave = ?", new String[] {"gc_fech_draft"})) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        }
        assertEquals(1, cifra.cifradas);

        // E o arquivo do banco inteiro não tem o e-mail em lugar nenhum (nem em página livre nem em diário).
        byte[] arquivoDoBanco = Files.readAllBytes(ctx.getDatabasePath("espelho.db").toPath());
        assertFalse(contem(arquivoDoBanco, "tecnico@exemplo".getBytes(StandardCharsets.UTF_8)));

        assertEquals(IDENT, espelho.lsTudo().get("gc_campo_ident"));
    }

    // A chave do Keystore pode sumir (restauração em outro aparelho, troca de tela de bloqueio): a identidade fica
    // ilegível, mas o resto do retrato tem de sair, e nada vai ao log com o nome da chave nem o valor.
    @Test
    public void identIlegivelNaoDerrubaORestoDoLsTudo() {
        espelho.gravarLs("gc_campo_ident", IDENT);
        espelho.gravarLs("gc_ronda_tecnico@exemplo.com.br_2026-10-16_u1", "ronda");
        cifra.falharAoDecifrar = true;

        Map<String, String> tudo = espelho.lsTudo();

        assertFalse(tudo.containsKey("gc_campo_ident"));
        assertEquals("ronda", tudo.get("gc_ronda_tecnico@exemplo.com.br_2026-10-16_u1"));
        assertNenhumLogComSegredo();
    }

    // A cifra que falha ao gravar (o Keystore fora do ar, a chave recusada) nunca leva a identidade em claro para o banco,
    // e a versão anterior sai: uma restauração devolveria a identidade velha com a fila nova (no aparelho dividido entre
    // técnicos, isso troca a autoria). A página regrava a identidade no próximo login.
    @Test
    public void cifraQueFalhaAoGravarAIdentidadeNaoGravaEmClaroEApagaAVelha() throws IOException {
        espelho.gravarLs("gc_campo_ident", IDENT);
        espelho.gravarLs("gc_fech_draft", "rascunho");
        String nova = "{\"email\":\"outro.tecnico@exemplo.com.br\",\"nome\":\"Outro Ficticio\"}";
        cifra.falharAoCifrar = true;
        try {
            espelho.gravarLs("gc_campo_ident", nova);
            fail("a cifra que falha devia subir, não virar gravação em claro");
        } catch (IllegalStateException esperado) {
            assertFalse(String.valueOf(esperado.getMessage()).contains("exemplo"));
        }
        cifra.falharAoCifrar = false;

        Map<String, String> tudo = espelho.lsTudo();
        assertFalse("a identidade velha não volta numa restauração", tudo.containsKey("gc_campo_ident"));
        assertEquals("o resto fica", "rascunho", tudo.get("gc_fech_draft"));
        try (SQLiteDatabase db = bancoCru();
             Cursor c = db.rawQuery("SELECT chave, valor FROM ls", null)) {
            while (c.moveToNext()) {
                assertFalse("linha da identidade no banco", "gc_campo_ident".equals(c.getString(0)));
                byte[] valor = c.getBlob(1);
                assertFalse(contem(valor, "outro.tecnico".getBytes(StandardCharsets.UTF_8)));
            }
        }
        byte[] arquivoDoBanco = Files.readAllBytes(ctx.getDatabasePath("espelho.db").toPath());
        assertFalse("nem em página livre do arquivo", contem(arquivoDoBanco, "outro.tecnico".getBytes(StandardCharsets.UTF_8)));
        assertNenhumLogComSegredo();
    }

    // A identidade que a chave de agora não lê (a chave refeita depois que o Keystore passou a recusar a velha, ou o dado
    // corrompido) nunca mais será lida: sai do banco na primeira leitura, e a página a regrava no próximo login.
    @Test
    public void identidadeQueAChaveDeAgoraNaoLeEApagada() {
        espelho.gravarLs("gc_campo_ident", IDENT);
        espelho.gravarLs("gc_fech_draft", "rascunho");
        cifra.falharAoDecifrar = true;
        assertFalse(espelho.lsTudo().containsKey("gc_campo_ident"));
        cifra.falharAoDecifrar = false;

        assertFalse("apagada, não só pulada", espelho.lsTudo().containsKey("gc_campo_ident"));
        try (SQLiteDatabase db = bancoCru();
             Cursor c = db.rawQuery("SELECT COUNT(*) FROM ls WHERE chave = ?", new String[] {"gc_campo_ident"})) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        }
        assertEquals("rascunho", espelho.lsTudo().get("gc_fech_draft"));
        assertNenhumLogComSegredo();
    }

    // O Keystore que não respondeu agora (o cofre ainda trancado logo depois de ligar o aparelho) não diz nada sobre o
    // dado: a identidade fica fora deste retrato e continua guardada para o próximo.
    @Test
    public void keystoreForaDoArNaoApagaAIdentidade() {
        espelho.gravarLs("gc_campo_ident", IDENT);
        cifra.keystoreFora = true;
        assertFalse(espelho.lsTudo().containsKey("gc_campo_ident"));
        cifra.keystoreFora = false;
        assertEquals(IDENT, espelho.lsTudo().get("gc_campo_ident"));
    }

    @Test
    public void msalNuncaEntra() {
        espelho.gravarLs("msal.x", "y");
        espelho.gravarLs("msal.token.keys.abc-123", "{\"idToken\":[]}");
        espelho.gravarLs("gc_casca_sentinela", "1");
        espelho.gravarLs("gc_casca_perda_pendente", "1");
        espelho.gravarLs("gc_casca_fila_sessao", "abcd1234");

        assertTrue(espelho.lsTudo().isEmpty());
        assertTrue("a sentinela da casca não é a sentinela do espelho", !espelho.temEspelho());
        try (SQLiteDatabase db = bancoCru();
             Cursor c = db.rawQuery("SELECT COUNT(*) FROM ls", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        }
    }

    // Apagar uma chave recusada também é no-op: apagarLs('gc_casca_sentinela') não pode derrubar a sentinela do espelho.
    @Test
    public void apagarChaveRecusadaNaoMexeNaSentinelaDoEspelho() {
        espelho.marcarSentinela();
        espelho.apagarLs("gc_casca_sentinela");
        espelho.apagarLs("msal.x");
        assertTrue(espelho.temEspelho());
    }

    @Test
    public void chaveOuValorNulosSaoIgnorados() {
        espelho.gravarLs(null, "x");
        espelho.gravarLs("", "x");
        espelho.gravarLs("gc_fech_draft", null);
        espelho.apagarLs(null);
        assertTrue(espelho.lsTudo().isEmpty());
    }

    // O CursorWindow do Android recusa uma linha de mais de ~2 MB, e o localStorage vai até 5 MB. O espelho lê em
    // pedaços; sem isso um valor grande derrubaria a restauração inteira (SQLiteBlobTooBigException).
    @Test
    public void valorMaiorQueAJanelaDoCursorVoltaInteiro() {
        StringBuilder montagem = new StringBuilder();
        while (montagem.length() < 1_900_000) montagem.append("ação ç 😀 linha de teste|");
        String grande = montagem.toString(); // ~1,9 M de caracteres, mais de 2 MB em UTF-8
        assertTrue(grande.getBytes(StandardCharsets.UTF_8).length > 2_100_000);

        espelho.gravarLs("gc_zl_modelos", grande);
        espelho.gravarLs("gc_campo_queue", grande);
        espelho.gravarIdbTexto("rdraft:grande", "json", grande);

        assertEquals(grande, espelho.lsTudo().get("gc_zl_modelos"));
        assertEquals(grande, espelho.filaMaisNova());
        assertEquals(hashes(grande), espelho.filaHashes());
        assertEquals(grande, idbTudo(espelho).get("rdraft:grande").texto);

        // E a fila igual à anterior, também grande, continua sem gastar versão.
        espelho.gravarLs("gc_campo_queue", grande);
        assertEquals(1, espelho.filaHashes().size());
    }

    // --- sentinela ---------------------------------------------------------------------------------------------------

    @Test
    public void temEspelhoSoDepoisDaSentinela() {
        assertFalse(espelho.temEspelho());

        espelho.gravarLs("gc_campo_queue", "[1]");
        espelho.gravarLs("gc_fech_draft", "{}");
        assertFalse("gravar chaves não marca o espelho: só a mensagem 'sentinela' da instalação", espelho.temEspelho());

        espelho.marcarSentinela();
        assertTrue(espelho.temEspelho());

        espelho.marcarSentinela(); // a mensagem repetida é igual à primeira
        assertTrue(espelho.temEspelho());
    }

    // --- IndexedDB: texto e JSON --------------------------------------------------------------------------------------

    @Test
    public void idbTextoEJsonVoltamPeloParaCadaIdb() {
        espelho.gravarIdbTexto("rdraft:abc", "texto", "olá");
        espelho.gravarIdbTexto("a_9", "json", "{\"x\":[1,2]}");

        Map<String, Item> tudo = idbTudo(espelho);
        assertEquals(Arrays.asList("a_9", "rdraft:abc"), new ArrayList<>(tudo.keySet()));
        assertEquals("texto", tudo.get("rdraft:abc").tipo);
        assertEquals("olá", tudo.get("rdraft:abc").texto);
        assertNull(tudo.get("rdraft:abc").arquivo);
        assertNull(tudo.get("rdraft:abc").mime);
        assertEquals("json", tudo.get("a_9").tipo);
        assertEquals("{\"x\":[1,2]}", tudo.get("a_9").texto);
        assertEquals(Arrays.asList("a_9", "rdraft:abc"), espelho.idbChaves());
    }

    @Test
    public void idbTextoSobrescreveEApaga() {
        espelho.gravarIdbTexto("a_9", "texto", "um");
        espelho.gravarIdbTexto("a_9", "texto", "dois");
        assertEquals("dois", idbTudo(espelho).get("a_9").texto);
        assertEquals(1, espelho.idbChaves().size());

        espelho.apagarIdb("a_9");
        assertTrue(espelho.idbChaves().isEmpty());
        espelho.apagarIdb("a_9"); // de novo: sem erro
        espelho.apagarIdb("nunca_existiu");
    }

    @Test
    public void idbTextoComTipoDesconhecidoELancado() {
        try {
            espelho.gravarIdbTexto("a_9", "xml", "<a/>");
            fail("tipo desconhecido tem de falhar");
        } catch (IllegalArgumentException esperado) {
            // ok
        }
        assertTrue(espelho.idbChaves().isEmpty());
    }

    // --- IndexedDB: pedaços ---------------------------------------------------------------------------------------------

    @Test
    public void pedacosSoViramEntradaNoFim() throws IOException {
        espelho.receberPedaco("f_123", "p1-abc-1", 0, 3, "image/jpeg", bytes(1, 2));
        assertTrue(espelho.idbChaves().isEmpty());

        espelho.receberPedaco("f_123", "p1-abc-1", 1, 3, "image/jpeg", bytes(3, 4));
        assertTrue("depois do 2º de 3 ainda não há entrada", espelho.idbChaves().isEmpty());
        assertTrue(idbTudo(espelho).isEmpty());
        File parte = pasta("espelho/tmp/p1-abc-1.parte");
        assertTrue(parte.isFile());
        assertArrayEquals(bytes(1, 2, 3, 4), Files.readAllBytes(parte.toPath()));

        espelho.receberPedaco("f_123", "p1-abc-1", 2, 3, "image/jpeg", bytes(5));

        assertEquals(Arrays.asList("f_123"), espelho.idbChaves());
        File arquivo = pasta("espelho/idb/df227124c824cab638e40035658b57381ab2a7fb.bin"); // sha1 de "f_123"
        assertTrue(arquivo.isFile());
        assertArrayEquals("os bytes concatenados na ordem", bytes(1, 2, 3, 4, 5), Files.readAllBytes(arquivo.toPath()));
        assertFalse("a parte sai de tmp quando vira entrada", parte.exists());

        Item item = idbTudo(espelho).get("f_123");
        assertNotNull(item);
        assertEquals("blob", item.tipo);
        assertEquals("image/jpeg", item.mime);
        assertNull(item.texto);
        assertEquals(arquivo.getAbsoluteFile(), item.arquivo.getAbsoluteFile());
    }

    @Test
    public void blobDeUmPedacoSoEVazioTambemViraEntrada() throws IOException {
        espelho.receberPedaco("v_1", "p2-x-1", 0, 1, "video/mp4", bytes(9, 9, 9));
        espelho.receberPedaco("v_vazio", "p2-x-2", 0, 1, "", new byte[0]);

        assertEquals(Arrays.asList("v_1", "v_vazio"), espelho.idbChaves());
        Item vazio = idbTudo(espelho).get("v_vazio");
        assertEquals(0L, vazio.arquivo.length());
        assertEquals("", vazio.mime);
        assertArrayEquals(bytes(9, 9, 9), Files.readAllBytes(idbTudo(espelho).get("v_1").arquivo.toPath()));
    }

    // O último pedaço sozinho (ou depois de um buraco) não prova que o resto chegou: o total do id não confere.
    @Test
    public void ultimoPedacoSemOsAnterioresNaoCriaEntrada() {
        espelho.receberPedaco("f_1", "p3-a-1", 2, 3, "image/png", bytes(7));
        assertTrue(espelho.idbChaves().isEmpty());

        espelho.receberPedaco("f_2", "p3-a-2", 0, 3, "image/png", bytes(1));
        espelho.receberPedaco("f_2", "p3-a-2", 2, 3, "image/png", bytes(3)); // pulou o 1
        assertTrue(espelho.idbChaves().isEmpty());
        assertFalse("a sequência quebrada não deixa a parte para trás", pasta("espelho/tmp/p3-a-2.parte").exists());

        // E o que vier depois da quebra, do mesmo id, também não cria nada.
        espelho.receberPedaco("f_2", "p3-a-2", 1, 3, "image/png", bytes(2));
        espelho.receberPedaco("f_2", "p3-a-2", 2, 3, "image/png", bytes(3));
        assertTrue(espelho.idbChaves().isEmpty());
    }

    @Test
    public void pedacoComTotalOuChaveDiferentesDescartaASequencia() {
        espelho.receberPedaco("f_1", "p4-a-1", 0, 3, "image/png", bytes(1));
        espelho.receberPedaco("f_1", "p4-a-1", 1, 4, "image/png", bytes(2)); // o n mudou
        espelho.receberPedaco("f_1", "p4-a-1", 2, 4, "image/png", bytes(3));
        espelho.receberPedaco("f_1", "p4-a-1", 3, 4, "image/png", bytes(4));
        assertTrue(espelho.idbChaves().isEmpty());

        espelho.receberPedaco("f_1", "p4-a-2", 0, 2, "image/png", bytes(1));
        espelho.receberPedaco("f_OUTRA", "p4-a-2", 1, 2, "image/png", bytes(2)); // a chave mudou
        assertTrue(espelho.idbChaves().isEmpty());
    }

    @Test
    public void pedacoDeNovaGravacaoDaMesmaChaveSubstituiOBlobEMantemUmArquivoSo() throws IOException {
        espelho.receberPedaco("f_123", "p5-a-1", 0, 1, "image/jpeg", bytes(1, 1, 1, 1));
        espelho.receberPedaco("f_123", "p5-a-2", 0, 1, "image/png", bytes(2, 2));

        assertEquals(Arrays.asList("f_123"), espelho.idbChaves());
        Item item = idbTudo(espelho).get("f_123");
        assertEquals("image/png", item.mime);
        assertArrayEquals(bytes(2, 2), Files.readAllBytes(item.arquivo.toPath()));
        assertEquals(1, pasta("espelho/idb").listFiles().length);
    }

    // No Android o rename troca o arquivo de uma vez (rename(2)): se o processo morre no meio, fica o blob antigo ou o
    // novo, nunca nenhum. Apagar o antigo antes do rename abria a janela em que nenhum dos dois existe.
    @Test
    public void aTrocaDoArquivoDoBlobTentaORenameAntesDeApagarOAntigo() throws IOException {
        File pastaTroca = new File(ctx.getCacheDir(), "troca");
        assertTrue(pastaTroca.mkdirs() || pastaTroca.isDirectory());
        File parte = new File(pastaTroca, "p.parte");
        File destino = new File(pastaTroca, "d.bin");
        Files.write(parte.toPath(), bytes(2, 2));
        Files.write(destino.toPath(), bytes(1, 1, 1));
        List<Boolean> oAntigoExistiaAoMover = new ArrayList<>();

        EspelhoSqlite.trocarArquivo(parte, destino, (de, para) -> {
            oAntigoExistiaAoMover.add(para.exists());
            try {
                // O rename(2) do Android: substitui o destino.
                Files.move(de.toPath(), para.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e) {
                return false;
            }
        });

        assertEquals("um rename só, com o antigo ainda no lugar", Arrays.asList(true), oAntigoExistiaAoMover);
        assertArrayEquals(bytes(2, 2), Files.readAllBytes(destino.toPath()));
        assertFalse(parte.exists());
    }

    @Test
    public void oRenameQueNaoSubstituiApagaOAntigoERepete() throws IOException {
        // O JVM do Windows (o deste teste) não troca um arquivo que já existe: só aí o antigo é apagado.
        File pastaTroca = new File(ctx.getCacheDir(), "troca2");
        assertTrue(pastaTroca.mkdirs() || pastaTroca.isDirectory());
        File parte = new File(pastaTroca, "p.parte");
        File destino = new File(pastaTroca, "d.bin");
        Files.write(parte.toPath(), bytes(2, 2));
        Files.write(destino.toPath(), bytes(1, 1, 1));
        int[] tentativas = {0};

        EspelhoSqlite.trocarArquivo(parte, destino, (de, para) -> {
            tentativas[0]++;
            return !para.exists() && de.renameTo(para);
        });

        assertEquals(2, tentativas[0]);
        assertArrayEquals(bytes(2, 2), Files.readAllBytes(destino.toPath()));
    }

    @Test
    public void aTrocaQueNaoAconteceLanca() throws IOException {
        File pastaTroca = new File(ctx.getCacheDir(), "troca3");
        assertTrue(pastaTroca.mkdirs() || pastaTroca.isDirectory());
        File parte = new File(pastaTroca, "p.parte");
        Files.write(parte.toPath(), bytes(2));
        try {
            EspelhoSqlite.trocarArquivo(parte, new File(pastaTroca, "d.bin"), (de, para) -> false);
            fail("a troca que não aconteceu devia lançar");
        } catch (IllegalStateException esperado) {
            // ok: a transação da linha desfaz
        }
    }

    @Test
    public void textoNoLugarDeBlobApagaOArquivoEApagarIdbTambem() {
        espelho.receberPedaco("f_123", "p6-a-1", 0, 1, "image/jpeg", bytes(1));
        File arquivo = idbTudo(espelho).get("f_123").arquivo;
        assertTrue(arquivo.isFile());

        espelho.gravarIdbTexto("f_123", "texto", "agora é texto");
        assertFalse(arquivo.exists());
        assertEquals("texto", idbTudo(espelho).get("f_123").tipo);

        espelho.receberPedaco("f_123", "p6-a-2", 0, 1, "image/jpeg", bytes(2));
        File outro = idbTudo(espelho).get("f_123").arquivo;
        assertTrue(outro.isFile());
        espelho.apagarIdb("f_123");
        assertFalse(outro.exists());
        assertTrue(espelho.idbChaves().isEmpty());
        assertEquals(0, pasta("espelho/idb").listFiles().length);
    }

    // O arquivo some do disco (limpeza de armazenamento parcial, falha de escrita): a entrada incompleta não vai ao
    // retrato. Anunciar uma chave que a restauração não consegue mandar deixaria o `n` do idbFim sem bater.
    @Test
    public void blobSemOArquivoNaoApareceNoRetrato() {
        espelho.receberPedaco("f_1", "p7-a-1", 0, 1, "image/jpeg", bytes(1));
        espelho.receberPedaco("f_2", "p7-a-2", 0, 1, "image/jpeg", bytes(2));
        assertTrue(idbTudo(espelho).get("f_1").arquivo.delete());

        assertEquals(Arrays.asList("f_2"), espelho.idbChaves());
        assertEquals(Arrays.asList("f_2"), new ArrayList<>(idbTudo(espelho).keySet()));
    }

    // O id vira nome de arquivo: nada de barra, ponto ou vazio. A exceção não leva a chave (tem e-mail).
    @Test
    public void idDePedacoPerigosoERecusado() {
        String chave = "rdraft:tecnico@exemplo.com.br";
        String[] ruins = {"../fora", "a/b", "a\\b", "a.b", "", "x y", "..", "a\u0000b"};
        for (String id : ruins) {
            try {
                espelho.receberPedaco(chave, id, 0, 1, "image/jpeg", bytes(1));
                fail("id perigoso aceito: " + id.replace("\u0000", "NUL"));
            } catch (IllegalArgumentException esperado) {
                assertFalse(String.valueOf(esperado.getMessage()).contains("exemplo"));
            }
        }
        try {
            espelho.receberPedaco(chave, null, 0, 1, "image/jpeg", bytes(1));
            fail("id nulo aceito");
        } catch (IllegalArgumentException esperado) {
            // ok
        }
        assertTrue(espelho.idbChaves().isEmpty());
        assertFalse(new File(ctx.getFilesDir(), "fora.parte").exists());
        assertFalse(pasta("espelho/fora.parte").exists());
    }

    @Test
    public void pedacoComIndiceOuTotalImpossiveisELancado() {
        int[][] ruins = {{0, 0}, {-1, 3}, {3, 3}, {5, 3}, {0, -2}};
        for (int[] in : ruins) {
            try {
                espelho.receberPedaco("f_1", "p8-a-1", in[0], in[1], "image/jpeg", bytes(1));
                fail("i=" + in[0] + " n=" + in[1] + " aceito");
            } catch (IllegalArgumentException esperado) {
                // ok
            }
        }
        assertTrue(espelho.idbChaves().isEmpty());
    }

    @Test
    public void pedacoDeChaveNulaOuVaziaESemDadosEIgnorado() {
        espelho.receberPedaco(null, "p9-a-1", 0, 1, "image/jpeg", bytes(1));
        espelho.receberPedaco("", "p9-a-2", 0, 1, "image/jpeg", bytes(1));
        espelho.receberPedaco("f_1", "p9-a-3", 0, 1, "image/jpeg", null);
        assertTrue(espelho.idbChaves().isEmpty());
    }

    // --- IndexedDB: a faxina da abertura ---------------------------------------------------------------------------------

    @Test
    public void parteOrfaMaisVelhaQue24hEApagada() throws IOException {
        File tmp = pasta("espelho/tmp");
        assertTrue(tmp.isDirectory() || tmp.mkdirs());
        File velha = new File(tmp, "velha-1.parte");
        File quaseVelha = new File(tmp, "quase-2.parte");
        File recente = new File(tmp, "recente-3.parte");
        for (File f : new File[] {velha, quaseVelha, recente}) Files.write(f.toPath(), bytes(1, 2, 3));
        long agora = System.currentTimeMillis();
        assertTrue(velha.setLastModified(agora - 25 * UMA_HORA_MS));
        assertTrue(quaseVelha.setLastModified(agora - 23 * UMA_HORA_MS));
        assertTrue(recente.setLastModified(agora - 5 * 60 * 1000L));

        EspelhoSqlite novo = new EspelhoSqlite(ctx, cifra); // a abertura faz a faxina
        try {
            assertFalse("passou de 24 h", velha.exists());
            assertTrue("ainda dentro das 24 h", quaseVelha.exists());
            assertTrue(recente.exists());
        } finally {
            novo.close();
        }
    }

    @Test
    public void aAberturaCriaAsPastasENaoApagaEntradaCompleta() {
        assertTrue(pasta("espelho/idb").isDirectory());
        assertTrue(pasta("espelho/tmp").isDirectory());

        espelho.receberPedaco("f_123", "p10-a-1", 0, 1, "image/jpeg", bytes(1, 2));
        File arquivo = idbTudo(espelho).get("f_123").arquivo;
        assertTrue(arquivo.setLastModified(System.currentTimeMillis() - 48 * UMA_HORA_MS));

        EspelhoSqlite novo = new EspelhoSqlite(ctx, cifra);
        try {
            assertTrue("a faxina é só da pasta tmp", arquivo.exists());
            assertEquals(Arrays.asList("f_123"), novo.idbChaves());
        } finally {
            novo.close();
        }
    }

    // --- persistência -----------------------------------------------------------------------------------------------------

    @Test
    public void tudoSobreviveAReabrirOEspelho() throws IOException {
        espelho.marcarSentinela();
        espelho.gravarLs("gc_campo_queue", "v1");
        espelho.gravarLs("gc_campo_queue", "v2");
        espelho.gravarLs("gc_campo_ident", IDENT);
        espelho.gravarLs("gc_ronda_ctx", "contexto");
        espelho.gravarIdbTexto("rdraft:1", "texto", "rascunho");
        espelho.receberPedaco("f_123", "p11-a-1", 0, 1, "image/jpeg", bytes(4, 5, 6));
        espelho.close();

        EspelhoSqlite outro = new EspelhoSqlite(ctx, cifra);
        try {
            assertTrue(outro.temEspelho());
            assertEquals(hashes("v2", "v1"), outro.filaHashes());
            assertEquals("v2", outro.filaMaisNova());
            assertEquals(IDENT, outro.lsTudo().get("gc_campo_ident"));
            assertEquals("contexto", outro.lsTudo().get("gc_ronda_ctx"));
            assertEquals(Arrays.asList("f_123", "rdraft:1"), outro.idbChaves());
            assertArrayEquals(bytes(4, 5, 6), Files.readAllBytes(idbTudo(outro).get("f_123").arquivo.toPath()));
        } finally {
            outro.close();
        }
        // O tearDown fecha o `espelho` de novo: fechar duas vezes não pode lançar.
    }

    // --- o que nunca vai ao log --------------------------------------------------------------------------------------------

    // Os nomes das chaves têm e-mail. Sequência quebrada e chave ilegível passam por caminhos que registram no log, e o
    // registro só pode ter contagem.
    @Test
    public void nomesDeChaveNuncaVaoAoLog() {
        String chave = "rdraft:tecnico@exemplo.com.br";
        espelho.receberPedaco(chave, "p12-a-1", 0, 3, "image/jpeg", bytes(1));
        espelho.receberPedaco(chave, "p12-a-1", 2, 3, "image/jpeg", bytes(3)); // quebra a sequência
        espelho.receberPedaco(chave, "p12-a-9", 1, 3, "image/jpeg", bytes(3)); // pedaço sem começo
        espelho.gravarLs("gc_campo_ident", IDENT);
        cifra.falharAoDecifrar = true;
        espelho.lsTudo();

        assertNenhumLogComSegredo();
    }

    private static void assertNenhumLogComSegredo() {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            String texto = String.valueOf(item.msg) + (item.throwable == null ? "" : item.throwable.toString());
            assertFalse("log com nome de chave ou valor: " + texto, texto.contains("exemplo"));
            assertFalse("log com nome de chave: " + texto, texto.contains("rdraft"));
            assertFalse("log com nome de chave: " + texto, texto.contains("gc_ronda"));
            assertFalse("log com valor da identidade: " + texto, texto.contains("Ficticio"));
        }
    }

    @Test
    public void naoHaChaveEmClaroNoNomeDoDiretorioDeTrabalho() {
        // O nome do arquivo do blob é o sha1 da chave, nunca a chave (que tem e-mail).
        espelho.receberPedaco("rdraft:tecnico@exemplo.com.br", "p13-a-1", 0, 1, "image/jpeg", bytes(1));
        File[] arquivos = pasta("espelho/idb").listFiles();
        assertNotNull(arquivos);
        assertEquals(1, arquivos.length);
        assertTrue(arquivos[0].getName().matches("[0-9a-f]{40}\\.bin"));
    }
}
