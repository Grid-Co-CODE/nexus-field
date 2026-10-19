package br.com.gridco.campo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;

/**
 * O canal da página ao nativo: cada mensagem do www/casca/inicio.js vira uma chamada ao espelho ou uma ação do plugin,
 * e nada do que o técnico guarda (nome de chave, valor, e-mail) vai ao log. O espelho e as ações são falsos; o
 * relógio do disjuntor é injetado. Os e-mails abaixo são fictícios.
 */
@RunWith(RobolectricTestRunner.class)
public class MensageiroTest {

    // Nome de chave com e-mail, como as do App (gc_ronda_<email>...): nunca pode aparecer no log.
    private static final String EMAIL = "tecnico.ficticio@exemplo.com.br";
    private static final String CHAVE_COM_EMAIL = "gc_ronda_" + EMAIL + "_u1";
    // Um primeiro nome (fictício), só letras: o que a limpeza por aspas deixava passar.
    private static final String NOME = "Fulano";

    @Rule public TemporaryFolder pasta = new TemporaryFolder();

    private ArmazemFalso fake;
    private AcoesFalsas acoes;
    private long agora;
    private Mensageiro m;

    @Before
    public void preparar() {
        ShadowLog.clear();
        fake = new ArmazemFalso();
        acoes = new AcoesFalsas();
        agora = 1_000_000L;
        m = new Mensageiro(fake, acoes, new Mensageiro.Disjuntor(), () -> agora);
    }

    // --- o que o plano pede ------------------------------------------------------------------------------------------

    @Test
    public void lsVaiAoArmazem() {
        assertTrue(m.tratar("{\"t\":\"ls\",\"k\":\"gc_fech_draft\",\"v\":\"x\"}"));
        assertEquals("x", fake.ls.get("gc_fech_draft"));
    }

    @Test
    public void pedacoDecodificaBase64() {
        String b64 = Base64.encodeToString(new byte[] {1, 2, 3, (byte) 0xff}, Base64.NO_WRAP);
        assertTrue(m.tratar("{\"t\":\"idbPedaco\",\"k\":\"f_1\",\"id\":\"r1-a-1\",\"i\":0,\"n\":2,\"mime\":\"image/jpeg\","
            + "\"b64\":\"" + b64 + "\"}"));
        assertEquals(1, fake.pedacos.size());
        Pedaco p = fake.pedacos.get(0);
        assertEquals("f_1", p.k);
        assertEquals("r1-a-1", p.id);
        assertEquals(0, p.i);
        assertEquals(2, p.n);
        assertEquals("image/jpeg", p.mime);
        assertArrayEquals(new byte[] {1, 2, 3, (byte) 0xff}, p.dados);
    }

    @Test
    public void precisoRetratoChamaAcao() throws JSONException {
        fake.lsTudo.put("gc_fech_draft", "rascunho");
        fake.fila = "[1,2]";
        fake.hashes = Arrays.asList("aaaaaaaa", "bbbbbbbb");

        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));

        assertEquals(1, acoes.retratos.size());
        JSONObject r = acoes.retratos.get(0);
        assertEquals("restaurar", r.getString("modo"));
        assertEquals("perda", r.getString("motivo"));
        assertFalse(r.getString("id").isEmpty());
        assertEquals("rascunho", r.getJSONObject("ls").getString("gc_fech_draft"));
        assertEquals("[1,2]", r.getString("fila"));
        assertEquals(2, r.getJSONArray("filaHashes").length());
        assertFalse(r.has("semParar"));
    }

    @Test
    public void foraChamaAbrirFora() {
        String url = "https://app.fracttal.com/oauth?client=x&state=y";
        assertTrue(m.tratar("{\"t\":\"fora\",\"url\":\"" + url + "\"}"));
        assertEquals(Arrays.asList(url), acoes.fora);
    }

    @Test
    public void jsonInvalidoOuTipoDesconhecidoDevolveFalse() {
        assertFalse(m.tratar("{"));
        assertFalse(m.tratar("{\"t\":\"xyz\"}"));
        assertFalse(m.tratar("[]"));
        assertFalse(m.tratar(""));
        assertFalse(m.tratar(null));
        assertFalse(m.tratar("{\"t\":3}"));
    }

    @Test
    public void sentinelaMarcaEspelho() {
        assertFalse(fake.temEspelho());
        assertTrue(m.tratar("{\"t\":\"sentinela\"}"));
        assertTrue(fake.temEspelho());
        // O script a reenvia em toda carga até o retrato dizer temEspelho: repetir não muda nada nem falha.
        assertTrue(m.tratar("{\"t\":\"sentinela\"}"));
        assertTrue(fake.temEspelho());
        assertEquals(2, fake.sentinelas);
    }

    // --- as outras mensagens da página ---------------------------------------------------------------------------------

    @Test
    public void exclusoesEEntradasDeTextoVaoAoArmazem() {
        fake.ls.put("gc_zl_pend", "[1]");
        assertTrue(m.tratar("{\"t\":\"lsDel\",\"k\":\"gc_zl_pend\"}"));
        assertFalse(fake.ls.containsKey("gc_zl_pend"));

        assertTrue(m.tratar("{\"t\":\"idbPut\",\"k\":\"rdraft:1\",\"tipo\":\"json\",\"v\":\"{\\\"a\\\":1}\"}"));
        assertEquals("json|{\"a\":1}", fake.idbTexto.get("rdraft:1"));

        assertTrue(m.tratar("{\"t\":\"idbDel\",\"k\":\"rdraft:1\"}"));
        assertFalse(fake.idbTexto.containsKey("rdraft:1"));
    }

    @Test
    public void mensagemComCampoQueFaltaOuTortoDevolveFalse() {
        assertFalse(m.tratar("{\"t\":\"ls\",\"k\":\"gc_fech_draft\"}"));
        assertFalse(m.tratar("{\"t\":\"ls\",\"v\":\"x\"}"));
        assertFalse(m.tratar("{\"t\":\"ls\",\"k\":1,\"v\":\"x\"}"));
        assertFalse(m.tratar("{\"t\":\"idbPedaco\",\"k\":\"f_1\",\"id\":\"a\",\"i\":0.5,\"n\":1,\"b64\":\"\"}"));
        assertFalse(m.tratar("{\"t\":\"idbPedaco\",\"k\":\"f_1\",\"id\":\"a\",\"i\":0,\"n\":1,\"b64\":\"***\"}"));
        assertTrue(fake.ls.isEmpty());
        assertTrue(fake.pedacos.isEmpty());
    }

    @Test
    public void base64SoOPadraoDoBtoa() {
        assertTrue(Mensageiro.base64Valido(""));
        assertTrue(Mensageiro.base64Valido("AQID"));
        assertTrue(Mensageiro.base64Valido("AQI="));
        assertTrue(Mensageiro.base64Valido("AQ=="));
        assertTrue(Mensageiro.base64Valido("+/9a"));
        assertFalse(Mensageiro.base64Valido("AQ"));
        assertFalse(Mensageiro.base64Valido("AQ=A"));
        assertFalse(Mensageiro.base64Valido("A==="));
        assertFalse(Mensageiro.base64Valido("AQ==AQID"));
        assertFalse(Mensageiro.base64Valido("AQ\nI"));
        assertFalse(Mensageiro.base64Valido("AQ-_"));
    }

    @Test
    public void foraSoAbreHttpOuHttps() {
        assertFalse(m.tratar("{\"t\":\"fora\",\"url\":\"javascript:alert(1)\"}"));
        assertFalse(m.tratar("{\"t\":\"fora\",\"url\":\"intent://x#Intent;end\"}"));
        assertFalse(m.tratar("{\"t\":\"fora\",\"url\":\"file:///sdcard/a\"}"));
        assertFalse(m.tratar("{\"t\":\"fora\"}"));
        assertTrue(acoes.fora.isEmpty());
        assertTrue(m.tratar("{\"t\":\"fora\",\"url\":\"HTTP://exemplo.com.br/a\"}"));
        assertEquals(1, acoes.fora.size());
    }

    @Test
    public void escritaQueLancaNaoDerrubaEAvisaSoAContagem() {
        fake.lancarAoEscrever = new IllegalArgumentException("índice ou total de pedaços impossível");
        String b64 = Base64.encodeToString(new byte[] {9}, Base64.NO_WRAP);

        assertFalse(m.tratar("{\"t\":\"idbPedaco\",\"k\":\"" + CHAVE_COM_EMAIL + "\",\"id\":\"a\",\"i\":0,\"n\":1,"
            + "\"b64\":\"" + b64 + "\"}"));
        fake.lancarAoEscrever = new RuntimeException("disco cheio");
        assertFalse(m.tratar("{\"t\":\"ls\",\"k\":\"" + CHAVE_COM_EMAIL + "\",\"v\":\"" + EMAIL + "\"}"));

        assertEquals(2, acoes.erros.size());
        assertTrue(acoes.erros.get(1), acoes.erros.get(1).contains("2"));
        semDadosDoTecnico();
    }

    // --- o retrato: todo pedido atendido, com disjuntor ----------------------------------------------------------------

    @Test
    public void todoPedidoGanhaRetratoComIdNovo() throws JSONException {
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"fila\"}"));
        agora += 61_000L;
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"fila\"}"));
        agora += 61_000L;
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));

        assertEquals(3, acoes.retratos.size());
        for (JSONObject r : acoes.retratos) assertEquals("restaurar", r.getString("modo"));
        assertEquals("fila", acoes.retratos.get(0).getString("motivo"));
        assertEquals(0, acoes.retratos.get(0).getJSONObject("ls").length());
        assertEquals("perda", acoes.retratos.get(2).getString("motivo"));
        assertNotEquals(acoes.retratos.get(0).getString("id"), acoes.retratos.get(1).getString("id"));
        assertNotEquals(acoes.retratos.get(1).getString("id"), acoes.retratos.get(2).getString("id"));
    }

    @Test
    public void disjuntorDaODepoisDeDoisEm60sOLeveSemHashes() throws JSONException {
        fake.hashes = Arrays.asList("aaaaaaaa", "bbbbbbbb");
        fake.idbChaves = Arrays.asList("f_1");
        fake.sentinela = true;

        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"fila\"}"));
        agora += 10_000L;
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"fila\"}"));
        agora += 10_000L;
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));

        // O terceiro também recarrega (a página parou e espera), mas com o leve e sem as versões da fila.
        assertEquals(3, acoes.retratos.size());
        assertEquals("restaurar", acoes.retratos.get(0).getString("modo"));
        assertEquals("restaurar", acoes.retratos.get(1).getString("modo"));
        JSONObject terceiro = acoes.retratos.get(2);
        assertFalse(terceiro.has("modo"));
        assertFalse(terceiro.has("id"));
        assertFalse(terceiro.has("ls"));
        assertEquals(0, terceiro.getJSONArray("filaHashes").length());
        assertTrue("sem conferência, a página não para a carga", terceiro.getBoolean("semParar"));
        assertTrue(terceiro.getBoolean("temEspelho"));
        assertEquals(1, terceiro.getJSONArray("idbChaves").length());

        // O aviso do disjuntor leva só a contagem.
        assertEquals(1, acoes.erros.size());
        assertTrue(acoes.erros.get(0), acoes.erros.get(0).startsWith("disjuntor:"));
        assertTrue(acoes.erros.get(0), acoes.erros.get(0).contains("3"));

        // Passados 60 s do primeiro, a janela anda e um completo volta a sair.
        agora += 40_000L;
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"fila\"}"));
        assertEquals("restaurar", acoes.retratos.get(3).getString("modo"));
    }

    @Test
    public void disjuntorEDoProcessoOMensageiroDaTelaRecriadaContinuaAContagem() throws JSONException {
        // O renderer morre na recarga com o retrato completo: a tela é recriada, com plugin, Mensageiro e sessionStorage
        // novos, no MESMO processo. Se a contagem fosse do Mensageiro, cada recriação ganharia outro completo, e nada
        // pararia o laço.
        Mensageiro.Disjuntor doProcesso = new Mensageiro.Disjuntor();
        Mensageiro antes = new Mensageiro(fake, acoes, doProcesso, () -> agora);
        assertTrue(antes.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
        agora += 5_000L;
        Mensageiro recriado = new Mensageiro(fake, acoes, doProcesso, () -> agora);
        assertTrue(recriado.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
        agora += 5_000L;
        Mensageiro recriadoDeNovo = new Mensageiro(fake, acoes, doProcesso, () -> agora);
        assertTrue(recriadoDeNovo.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));

        assertEquals(3, acoes.retratos.size());
        assertEquals("restaurar", acoes.retratos.get(0).getString("modo"));
        assertEquals("restaurar", acoes.retratos.get(1).getString("modo"));
        JSONObject terceiro = acoes.retratos.get(2);
        assertFalse("o terceiro em 60 s, já na terceira tela, sai sem o completo", terceiro.has("modo"));
        assertEquals(0, terceiro.getJSONArray("filaHashes").length());
        assertTrue("sem conferência, a página não para a carga", terceiro.getBoolean("semParar"));
        assertTrue(acoes.erros.toString(), acoes.erros.get(0).startsWith("disjuntor:"));

        // Outro processo (o app aberto de novo) começa do zero.
        Mensageiro outroProcesso = new Mensageiro(fake, acoes, new Mensageiro.Disjuntor(), () -> agora);
        assertTrue(outroProcesso.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
        assertEquals("restaurar", acoes.retratos.get(3).getString("modo"));
    }

    // --- o disjuntor do dia: 3 restaurações de perda e 3 só do IndexedDB por 24 h no aparelho, em contas separadas ---

    @Test
    public void disjuntorDaPerdaDeixaTresEm24hPeloRelogioDeParede() {
        Mensageiro.DisjuntorDaPerda.Memoria memoria = Mensageiro.DisjuntorDaPerda.naMemoria();
        long[] parede = {5_000_000_000L};
        Mensageiro.DisjuntorDaPerda d = new Mensageiro.DisjuntorDaPerda(memoria, () -> parede[0]);
        assertTrue(d.liberaPerda());
        assertTrue("dois no mesmo instante contam dois", d.liberaPerda());
        parede[0] += 1_000L;
        assertTrue(d.liberaPerda());
        assertFalse("a quarta em 24 h não sai", d.liberaPerda());
        parede[0] += Mensageiro.JANELA_DA_PERDA_MS - 1_001L;
        assertFalse("um milissegundo antes de as duas primeiras saírem da janela", d.liberaPerda());
        parede[0] += 1L;
        assertTrue("as duas primeiras saíram da janela", d.liberaPerda());
        assertTrue(d.liberaPerda());
        assertFalse(d.liberaPerda());
    }

    @Test
    public void asRestauracoesDaPerdaESoDoIndexedDbTemContasSeparadas() {
        // Caso que criou a regra: com uma conta só, as restaurações só do IndexedDB (o banco sentinela que falta a cada
        // carga com o disco cheio) gastavam as vagas do dia, e a perda de verdade que viesse depois recarregava sem o
        // retrato completo: a página seguia sem espelho por um dia, com a cópia boa parada no nativo.
        Mensageiro.DisjuntorDaPerda.Memoria memoria = Mensageiro.DisjuntorDaPerda.naMemoria();
        long[] parede = {6_000_000_000L};
        Mensageiro.DisjuntorDaPerda d = new Mensageiro.DisjuntorDaPerda(memoria, () -> parede[0]);
        for (int i = 0; i < Mensageiro.RESTAURACOES_POR_DIA; i++) assertTrue("idb " + i, d.liberaIdb());
        assertFalse("a quarta só do IndexedDB em 24 h não sai", d.liberaIdb());
        for (int i = 0; i < Mensageiro.RESTAURACOES_POR_DIA; i++) {
            assertTrue("a perda tem as vagas dela: " + i, d.liberaPerda());
        }
        assertFalse(d.liberaPerda());
        // E ao contrário: a perda estourada não fecha a do IndexedDB (outro aparelho, outra janela).
        Mensageiro.DisjuntorDaPerda e =
            new Mensageiro.DisjuntorDaPerda(Mensageiro.DisjuntorDaPerda.naMemoria(), () -> parede[0]);
        for (int i = 0; i < Mensageiro.RESTAURACOES_POR_DIA; i++) assertTrue(e.liberaPerda());
        assertFalse(e.liberaPerda());
        assertTrue("a do IndexedDB ainda sai", e.liberaIdb());
        // Cada conta mora na sua chave da memória.
        assertEquals(3, memoria.ler(Mensageiro.DisjuntorDaPerda.CHAVE).split(",").length);
        assertEquals(3, memoria.ler(Mensageiro.DisjuntorDaPerda.CHAVE_IDB).split(",").length);
        assertNotEquals(Mensageiro.DisjuntorDaPerda.CHAVE, Mensageiro.DisjuntorDaPerda.CHAVE_IDB);
    }

    @Test
    public void disjuntorDaPerdaAtravessaOProcessoPelaMemoria() {
        // O processo morre (o renderer que morre na restauração, o Android que mata o app em segundo plano): a contagem
        // é do aparelho, na memória que fica (o SharedPreferences), não do processo. Vale para as duas contas.
        Mensageiro.DisjuntorDaPerda.Memoria memoria = Mensageiro.DisjuntorDaPerda.naMemoria();
        long[] parede = {7_000_000_000L};
        for (int i = 0; i < 3; i++) {
            assertTrue(new Mensageiro.DisjuntorDaPerda(memoria, () -> parede[0]).liberaPerda());
            assertTrue(new Mensageiro.DisjuntorDaPerda(memoria, () -> parede[0]).liberaIdb());
        }
        assertFalse(new Mensageiro.DisjuntorDaPerda(memoria, () -> parede[0]).liberaPerda());
        assertFalse(new Mensageiro.DisjuntorDaPerda(memoria, () -> parede[0]).liberaIdb());
    }

    @Test
    public void disjuntorDaPerdaSemGravarNaMemoriaAindaContaNoProcesso() {
        // O disco cheio, que é o caso do laço, também pode recusar o SharedPreferences.
        Mensageiro.DisjuntorDaPerda.Memoria quebrada = new Mensageiro.DisjuntorDaPerda.Memoria() {
            @Override public String ler(String chave) { return ""; }
            @Override public void gravar(String chave, String valor) { throw new IllegalStateException("disco cheio"); }
        };
        Mensageiro.DisjuntorDaPerda d = new Mensageiro.DisjuntorDaPerda(quebrada, () -> 9_000_000_000L);
        for (int i = 0; i < 3; i++) {
            assertTrue(d.liberaPerda());
            assertTrue(d.liberaIdb());
        }
        assertFalse(d.liberaPerda());
        assertFalse(d.liberaIdb());
    }

    @Test
    public void disjuntorDaPerdaComORelogioQueVoltouOuAMemoriaTortaNaoTravaParaSempre() {
        Mensageiro.DisjuntorDaPerda.Memoria memoria = Mensageiro.DisjuntorDaPerda.naMemoria();
        long[] parede = {10_000_000_000L};
        Mensageiro.DisjuntorDaPerda d = new Mensageiro.DisjuntorDaPerda(memoria, () -> parede[0]);
        assertTrue(d.liberaPerda());
        assertTrue(d.liberaPerda());
        assertTrue(d.liberaPerda());
        assertFalse(d.liberaPerda());
        // A hora do aparelho voltou um ano: as marcas "do futuro" não valem (senão travariam por um ano).
        parede[0] -= 365L * Mensageiro.JANELA_DA_PERDA_MS;
        assertTrue(new Mensageiro.DisjuntorDaPerda(memoria, () -> parede[0]).liberaPerda());

        Mensageiro.DisjuntorDaPerda.Memoria torta = Mensageiro.DisjuntorDaPerda.naMemoria();
        torta.gravar(Mensageiro.DisjuntorDaPerda.CHAVE, "x,,-3,abc," + Long.MAX_VALUE);
        torta.gravar(Mensageiro.DisjuntorDaPerda.CHAVE_IDB, "x,,-3,abc," + Long.MAX_VALUE);
        assertTrue(new Mensageiro.DisjuntorDaPerda(torta, () -> parede[0]).liberaPerda());
        assertTrue(new Mensageiro.DisjuntorDaPerda(torta, () -> parede[0]).liberaIdb());
    }

    @Test
    public void perdaComODisjuntorDoDiaEstouradoRecarregaSemConferenciaEAFilaContinua() throws JSONException {
        fake.sentinela = true;
        fake.hashes = Arrays.asList("aaaaaaaa");
        for (int i = 0; i < 3; i++) {
            assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
            agora += 61_000L; // fora da janela de 60 s: só o disjuntor do dia conta
        }
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
        assertEquals(4, acoes.retratos.size());
        for (int i = 0; i < 3; i++) assertEquals("restaurar", acoes.retratos.get(i).getString("modo"));
        JSONObject quarto = acoes.retratos.get(3);
        assertFalse("a quarta perda em 24 h recarrega sem o completo", quarto.has("modo"));
        assertTrue("e a página não para a carga: segue sem espelho até a janela andar", quarto.getBoolean("semParar"));
        assertEquals(0, quarto.getJSONArray("filaHashes").length());
        assertEquals(1, acoes.erros.size());
        assertTrue(acoes.erros.get(0), acoes.erros.get(0).startsWith("disjuntor:"));
        assertTrue(acoes.erros.get(0), acoes.erros.get(0).contains("24 h"));

        // O retrato da fila não é restauração de perda: continua saindo.
        agora += 61_000L;
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"fila\"}"));
        assertEquals("restaurar", acoes.retratos.get(4).getString("modo"));
        semDadosDoTecnico();
    }

    @Test
    public void disjuntorDe60sNaoGastaAsRestauracoesDoDia() throws JSONException {
        // A recarga em laço (o renderer que morre na recarga com o completo) para no de 60 s, sem gastar o dia.
        for (int i = 0; i < 5; i++) assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
        agora += 61_000L;
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
        assertEquals("a terceira restauração do dia ainda sai", "restaurar", acoes.retratos.get(5).getString("modo"));
    }

    @Test
    public void idbPedirSoDoIndexedDbContaNoDisjuntorEODaPerdaNao() throws JSONException {
        // A restauração da perda já passou pelo disjuntor no precisoRetrato.
        for (int i = 0; i < 5; i++) assertTrue(m.tratar("{\"t\":\"idbPedir\",\"motivo\":\"perda\"}"));
        assertEquals(5, acoes.idbPedidos);
        // A só do IndexedDB (sem o banco sentinela da casca, a restauração que não terminou): 3 por 24 h, na conta dela.
        // Sem motivo conta como ela.
        assertTrue(m.tratar("{\"t\":\"idbPedir\",\"motivo\":\"idb\"}"));
        assertTrue(m.tratar("{\"t\":\"idbPedir\"}"));
        assertTrue(m.tratar("{\"t\":\"idbPedir\",\"motivo\":\"idb\"}"));
        assertEquals(8, acoes.idbPedidos);
        assertEquals(0, acoes.idbRecusas);
        assertTrue(m.tratar("{\"t\":\"idbPedir\",\"motivo\":\"idb\"}"));
        assertEquals("a quarta é recusada", 8, acoes.idbPedidos);
        assertEquals(1, acoes.idbRecusas);
        assertTrue(acoes.erros.toString(), acoes.erros.get(0).startsWith("disjuntor:"));
    }

    @Test
    public void tresRestauracoesSoDoIndexedDbNoDiaNaoTiramOCompletoDaPerdaSeguinte() throws JSONException {
        // O disco cheio faz cada carga restaurar só o IndexedDB (o banco sentinela nunca volta) e gastar o dia dela. A
        // perda de verdade que vem depois ainda ganha o retrato completo: a 'idb' nunca gasta a vaga da 'perda'.
        fake.sentinela = true;
        fake.hashes = Arrays.asList("aaaaaaaa");
        for (int i = 0; i < Mensageiro.RESTAURACOES_POR_DIA; i++) {
            assertTrue(m.tratar("{\"t\":\"idbPedir\",\"motivo\":\"idb\"}"));
        }
        assertTrue(m.tratar("{\"t\":\"idbPedir\",\"motivo\":\"idb\"}"));
        assertEquals("a só do IndexedDB estourou a conta dela", 1, acoes.idbRecusas);

        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
        assertEquals(1, acoes.retratos.size());
        JSONObject retrato = acoes.retratos.get(0);
        assertEquals("a perda ainda é atendida com o retrato completo", "restaurar", retrato.getString("modo"));
        assertEquals("perda", retrato.getString("motivo"));
        assertFalse(retrato.optBoolean("semParar", false));
        assertEquals(1, retrato.getJSONArray("filaHashes").length());

        // E a perda que esgota a conta dela não fecha a do IndexedDB de um aparelho que ainda tem vaga.
        Mensageiro outro = new Mensageiro(new ArmazemFalso(), acoes, new Mensageiro.Disjuntor(), () -> agora);
        for (int i = 0; i < Mensageiro.RESTAURACOES_POR_DIA + 1; i++) {
            agora += 61_000L; // fora da janela de 60 s: só o disjuntor do dia conta
            assertTrue(outro.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
        }
        int pedidos = acoes.idbPedidos;
        assertTrue(outro.tratar("{\"t\":\"idbPedir\",\"motivo\":\"idb\"}"));
        assertEquals("a do IndexedDB sai", pedidos + 1, acoes.idbPedidos);
        semDadosDoTecnico();
    }

    @Test
    public void recusarIdbMandaOIdbRecusadoPelaPonte() throws JSONException {
        List<String> saiu = new ArrayList<>();
        assertTrue(m.recusarIdb(msg -> { saiu.add(msg); return true; }));
        assertEquals(1, saiu.size());
        assertEquals("idbRecusado", new JSONObject(saiu.get(0)).getString("t"));
        assertFalse(m.recusarIdb(msg -> { throw new IllegalStateException("a ponte caiu"); }));
    }

    @Test
    public void aMemoriaDoDisjuntorNoSharedPreferencesGuardaEntreAsInstancias() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("casca_espelho_teste", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        long[] parede = {11_000_000_000L};
        for (int i = 0; i < 3; i++) {
            assertTrue(new Mensageiro.DisjuntorDaPerda(Mensageiro.DisjuntorDaPerda.naPreferencia(prefs), () -> parede[0])
                .liberaPerda());
        }
        assertFalse(new Mensageiro.DisjuntorDaPerda(Mensageiro.DisjuntorDaPerda.naPreferencia(prefs), () -> parede[0])
            .liberaPerda());
        // A conta só do IndexedDB mora noutra chave: as perdas do dia não a gastam.
        assertTrue(new Mensageiro.DisjuntorDaPerda(Mensageiro.DisjuntorDaPerda.naPreferencia(prefs), () -> parede[0])
            .liberaIdb());
        assertEquals(String.valueOf(parede[0]), prefs.getString(Mensageiro.DisjuntorDaPerda.CHAVE_IDB, ""));
        // Um valor de outro tipo na chave (versão antiga, arquivo mexido) conta como vazio, sem lançar.
        prefs.edit().clear().putInt(Mensageiro.DisjuntorDaPerda.CHAVE, 7).putInt(Mensageiro.DisjuntorDaPerda.CHAVE_IDB, 7)
            .commit();
        assertTrue(new Mensageiro.DisjuntorDaPerda(Mensageiro.DisjuntorDaPerda.naPreferencia(prefs), () -> parede[0])
            .liberaPerda());
        assertTrue(new Mensageiro.DisjuntorDaPerda(Mensageiro.DisjuntorDaPerda.naPreferencia(prefs), () -> parede[0])
            .liberaIdb());
    }

    @Test
    public void motivoInvalidoRecarregaSemConferirAFilaEAvisaSemOValor() throws JSONException {
        // A página que mandou o pedido já chamou win.stop(): sem a recarga, fica em branco até matar o app. O motivo
        // torto não monta o completo (o Retrato.completo lança), mas a recarga sai com o leve sem conferência.
        fake.hashes = Arrays.asList("aaaaaaaa");
        fake.sentinela = true;
        assertFalse(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"" + EMAIL + "\"}"));
        assertFalse(m.tratar("{\"t\":\"precisoRetrato\"}"));
        assertFalse(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":null}"));

        assertEquals(3, acoes.retratos.size());
        for (JSONObject r : acoes.retratos) {
            assertFalse(r.has("modo"));
            assertFalse(r.has("ls"));
            assertEquals(0, r.getJSONArray("filaHashes").length());
            assertTrue("sem conferência, a página não para a carga", r.getBoolean("semParar"));
            assertTrue(r.getBoolean("temEspelho"));
        }
        assertEquals(3, acoes.erros.size());
        for (String e : acoes.erros) assertTrue(e, e.startsWith("precisoRetrato:"));
        semDadosDoTecnico();
    }

    @Test
    public void retratoQueFalhaAindaRecarregaComOLeveSemHashes() throws JSONException {
        fake.lancarAoLer = new IllegalStateException("leitura do espelho incompleta");
        assertTrue(m.tratar("{\"t\":\"precisoRetrato\",\"motivo\":\"perda\"}"));
        assertEquals(1, acoes.retratos.size());
        JSONObject r = acoes.retratos.get(0);
        assertFalse(r.has("modo"));
        assertEquals(0, r.getJSONArray("filaHashes").length());
        assertTrue("sem conferência, a página não para a carga", r.getBoolean("semParar"));
        // Sem ler o espelho, o retrato não pode dizer que não há espelho: seria tomado por instalação nova.
        assertTrue(r.getBoolean("temEspelho"));
        assertEquals(1, acoes.erros.size());
    }

    @Test
    public void restauradoVoltaAoLeveFrescoESoContaOQueNaoCoube() throws JSONException {
        fake.hashes = Arrays.asList("cccccccc");
        fake.idbChaves = Arrays.asList("f_1", "f_2");
        fake.sentinela = true;

        assertTrue(m.tratar("{\"t\":\"restaurado\",\"chaves\":3,\"naoCouberam\":[\"" + CHAVE_COM_EMAIL
            + "\",\"gc_campo_queue\"],\"fila\":false}"));

        assertEquals(1, acoes.leves.size());
        JSONObject leve = acoes.leves.get(0);
        assertFalse(leve.has("modo"));
        assertEquals("cccccccc", leve.getJSONArray("filaHashes").getString(0));
        assertFalse("o leve fresco deixa a página parar de novo", leve.has("semParar"));
        assertEquals(2, leve.getJSONArray("idbChaves").length());
        assertTrue(acoes.retratos.isEmpty()); // não recarrega

        assertEquals(1, acoes.erros.size());
        assertTrue(acoes.erros.get(0), acoes.erros.get(0).contains("2"));
        assertFalse(acoes.erros.get(0), acoes.erros.get(0).contains("gc_campo_queue"));
        semDadosDoTecnico();
    }

    @Test
    public void restauradoQueFalhaAoLerAindaTrocaOScriptPeloLeveSemHashes() throws JSONException {
        // O completo saiu e a página o aplicou; o espelho falha agora. Se o script não for trocado, o completo (com o
        // `ls` e a fila do pedido) fica de pé pela partida, e a carga que vier depois de um "limpar só o login"
        // (sessionStorage limpo, sem o id do retrato aplicado) grava o `ls` velho e a fila velha por cima dos novos.
        fake.lancarAoLer = new IllegalStateException("leitura do espelho incompleta");

        assertTrue(m.tratar("{\"t\":\"restaurado\",\"chaves\":3,\"naoCouberam\":[\"" + CHAVE_COM_EMAIL
            + "\"],\"fila\":true}"));

        assertEquals(1, acoes.leves.size());
        JSONObject leve = acoes.leves.get(0);
        assertFalse(leve.has("modo"));
        assertFalse(leve.has("id"));
        assertFalse(leve.has("ls"));
        assertFalse(leve.has("fila"));
        assertEquals(0, leve.getJSONArray("filaHashes").length());
        assertTrue("sem conferência, a página não para a carga", leve.getBoolean("semParar"));
        // Sem ler o espelho, o retrato não pode dizer que não há espelho: seria tomado por instalação nova.
        assertTrue(leve.getBoolean("temEspelho"));
        assertTrue(acoes.retratos.isEmpty()); // não recarrega

        // O aviso da falha leva só a contagem.
        assertTrue(acoes.erros.toString(), acoes.erros.stream().anyMatch(e -> e.startsWith("restaurado:")
            && e.contains("falha(s) nesta partida")));
        semDadosDoTecnico();
    }

    // --- o log -----------------------------------------------------------------------------------------------------

    @Test
    public void nenhumLogRecebeNomeDeChaveNemEmail() {
        // O erro que vem da página: o QuotaExceededError do Chromium cita a chave entre aspas.
        assertTrue(m.tratar("{\"t\":\"erro\",\"onde\":\"lote\",\"msg\":\"Failed to execute 'setItem' on 'Storage': "
            + "Setting the value of '" + CHAVE_COM_EMAIL + "' exceeded the quota.\"}"));
        assertTrue(m.tratar("{\"t\":\"erro\",\"onde\":\"" + EMAIL + "\",\"msg\":\"falhou para " + EMAIL + "\"}"));
        assertTrue(m.tratar("{\"t\":\"erro\",\"onde\":\"idb\",\"msg\":\"chave \\\"" + CHAVE_COM_EMAIL + "\\\"\"}"));
        assertFalse(m.tratar("{\"t\":\"" + CHAVE_COM_EMAIL + "\"}"));
        assertFalse(m.tratar("{\"t\":\"ls\",\"k\":\"" + CHAVE_COM_EMAIL + "\"}"));
        assertFalse(m.tratar("{\"t\":\"ls\",\"k\":\"" + CHAVE_COM_EMAIL + "\",\"v\":"));
        assertFalse(m.tratar("{\"t\":\"fora\",\"url\":\"javascript:" + EMAIL + "\"}"));
        // O erro do JSON.parse cita o texto entre aspas: um primeiro nome, só letras, num rascunho que não é JSON.
        assertTrue(m.tratar("{\"t\":\"erro\",\"onde\":\"canal\",\"msg\":\"Unexpected token 'F', \\\"" + NOME
            + "\\\" is not valid JSON\"}"));
        // O `onde` só vai ao log se for um dos rótulos do inicio.js; um nome no lugar dele vira 'outro'.
        assertTrue(m.tratar("{\"t\":\"erro\",\"onde\":\"" + NOME + "\",\"msg\":\"x\"}"));

        assertEquals(9, acoes.erros.size());
        assertTrue(acoes.erros.get(0), acoes.erros.get(0).startsWith("lote:"));
        assertTrue(acoes.erros.get(1), acoes.erros.get(1).startsWith("outro:"));
        assertTrue(acoes.erros.get(7), acoes.erros.get(7).startsWith("canal:"));
        assertTrue(acoes.erros.get(8), acoes.erros.get(8).startsWith("outro:"));
        // Do erro da página, o log leva só o rótulo e a contagem: nunca o texto, nem o 'setItem' do Chromium.
        assertFalse(acoes.erros.get(0), acoes.erros.get(0).contains("setItem"));
        assertTrue(acoes.erros.get(7), acoes.erros.get(7).contains("4"));
        semDadosDoTecnico();
    }

    @Test
    public void erroDaPaginaComRotuloDoScriptVaiAoLogComORotulo() {
        for (String onde : new String[] {"idbRestaurar", "espelho-ls", "document.write", "location.href", "perda",
                "voltar", "reenvio", "push"}) {
            assertTrue(onde, m.tratar("{\"t\":\"erro\",\"onde\":\"" + onde + "\",\"msg\":\"qualquer\"}"));
        }
        assertEquals(Arrays.asList("idbRestaurar: erro da página (1 nesta partida)",
            "espelho-ls: erro da página (2 nesta partida)", "document.write: erro da página (3 nesta partida)",
            "location.href: erro da página (4 nesta partida)", "perda: erro da página (5 nesta partida)",
            "voltar: erro da página (6 nesta partida)", "reenvio: erro da página (7 nesta partida)",
            "push: erro da página (8 nesta partida)"), acoes.erros);
    }

    // --- a resposta do idbPedir -----------------------------------------------------------------------------------------

    @Test
    public void idbPedirChamaAAcao() {
        assertTrue(m.tratar("{\"t\":\"idbPedir\"}"));
        assertEquals(1, acoes.idbPedidos);
    }

    @Test
    public void enviarIdbMandaUmaEntradaPorVezComPedacosDe512KbEOFim() throws Exception {
        byte[] video = new byte[2 * Mensageiro.TAMANHO_PEDACO + 10];
        for (int i = 0; i < video.length; i++) video[i] = (byte) (i * 31);
        fake.idbOrdem.add(new EntradaIdb("__programacao", "json", null, "{\"a\":1}", null));
        fake.idbOrdem.add(new EntradaIdb("f_1", "blob", "video/mp4", null, arquivo("f1.bin", video)));
        fake.idbOrdem.add(new EntradaIdb("f_vazio", "blob", "image/jpeg", null, arquivo("vazio.bin", new byte[0])));
        fake.idbOrdem.add(new EntradaIdb("rdraft:1", "texto", null, "rascunho", null));

        List<JSONObject> saiu = new ArrayList<>();
        assertTrue(m.enviarIdb(msg -> { saiu.add(objeto(msg)); return true; }));

        assertEquals(7, saiu.size());
        JSONObject json = saiu.get(0);
        assertEquals("idbItem", json.getString("t"));
        assertEquals("__programacao", json.getString("k"));
        assertEquals("json", json.getString("tipo"));
        assertEquals("{\"a\":1}", json.getString("v"));

        ByteArrayOutputStream junto = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) {
            JSONObject p = saiu.get(1 + i);
            assertEquals("idbPedaco", p.getString("t"));
            assertEquals("f_1", p.getString("k"));
            assertEquals(i, p.getInt("i"));
            assertEquals(3, p.getInt("n"));
            assertEquals("video/mp4", p.getString("mime"));
            assertFalse("a resposta não leva id: o script junta pela chave", p.has("id"));
            byte[] parte = Base64.decode(p.getString("b64"), Base64.DEFAULT);
            assertEquals(i < 2 ? Mensageiro.TAMANHO_PEDACO : 10, parte.length);
            junto.write(parte);
        }
        assertArrayEquals(video, junto.toByteArray());

        JSONObject vazio = saiu.get(4);
        assertEquals("f_vazio", vazio.getString("k"));
        assertEquals(0, vazio.getInt("i"));
        assertEquals(1, vazio.getInt("n"));
        assertEquals("", vazio.getString("b64"));

        assertEquals("idbItem", saiu.get(5).getString("t"));
        assertEquals("texto", saiu.get(5).getString("tipo"));

        JSONObject fim = saiu.get(6);
        assertEquals("idbFim", fim.getString("t"));
        assertEquals(4, fim.getInt("n"));
    }

    @Test
    public void enviarIdbParaQuandoAPonteCaiSemOFim() throws Exception {
        fake.idbOrdem.add(new EntradaIdb("a_1", "texto", null, "1", null));
        fake.idbOrdem.add(new EntradaIdb("a_2", "texto", null, "2", null));
        fake.idbOrdem.add(new EntradaIdb("a_3", "texto", null, "3", null));
        List<String> saiu = new ArrayList<>();
        assertFalse(m.enviarIdb(msg -> { saiu.add(msg); return saiu.size() < 2; }));
        assertEquals(2, saiu.size());
        for (String s : saiu) assertNotEquals("idbFim", new JSONObject(s).getString("t"));
        semDadosDoTecnico();
    }

    @Test
    public void enviarIdbPulaOBlobIlegivelEContaOQueSaiu() throws Exception {
        fake.idbOrdem.add(new EntradaIdb(CHAVE_COM_EMAIL, "blob", "image/jpeg", null, new File(pasta.getRoot(), "nao-existe")));
        fake.idbOrdem.add(new EntradaIdb("a_1", "texto", null, "1", null));
        List<JSONObject> saiu = new ArrayList<>();
        assertTrue(m.enviarIdb(msg -> { saiu.add(objeto(msg)); return true; }));
        assertEquals(2, saiu.size());
        assertEquals("a_1", saiu.get(0).getString("k"));
        assertEquals("idbFim", saiu.get(1).getString("t"));
        assertEquals(1, saiu.get(1).getInt("n"));
        assertEquals(1, acoes.erros.size());
        semDadosDoTecnico();
    }

    // --- o registro do aparelho para as notificações --------------------------------------------------------------

    // Um token do Firebase fictício: como o e-mail, nunca vai ao log.
    private static final String TOKEN = "tokenFcmFicticio:APA91b_abc123";

    @Test
    public void pushPedirTokenChegaAoPlugin() {
        assertTrue(m.tratar("{\"t\":\"pushPedirToken\"}"));
        assertEquals(1, acoes.pushPedidos);
        assertTrue(acoes.erros.isEmpty());
        semDadosDoTecnico();
        semOToken();
    }

    @Test
    public void oSairEORegistroDaPaginaChegamAoPlugin() {
        // O Sair cala as notificações até o registro seguinte; o registro 2xx as solta. Só o tipo: o que vier junto (um
        // token, a conta) não é lido nem vai ao log.
        assertTrue(m.tratar("{\"t\":\"pushSaiu\"}"));
        assertTrue(m.tratar("{\"t\":\"pushRegistrado\",\"token\":\"" + TOKEN + "\",\"email\":\"" + EMAIL + "\"}"));
        assertTrue(m.tratar("{\"t\":\"pushSaiu\",\"email\":\"" + EMAIL + "\"}"));
        assertEquals(Arrays.asList("saiu", "registrado", "saiu"), acoes.push);
        assertTrue(acoes.erros.isEmpty());
        semDadosDoTecnico();
        semOToken();
    }

    @Test
    public void aOsAbertaChegaAoPluginSoComONumeroValido() {
        assertTrue(m.tratar("{\"t\":\"osAberta\",\"os\":\"4521\"}"));
        assertTrue(m.tratar("{\"t\":\"osAberta\",\"os\":\"999999999\"}"));
        assertEquals(Arrays.asList("aberta:4521", "aberta:999999999"), acoes.push);
        // O número vai a um cancelamento na barra: só de 1 a 12 dígitos ASCII. O resto é recusado sem citar o que veio.
        for (String torto : new String[] {"{\"t\":\"osAberta\"}", "{\"t\":\"osAberta\",\"os\":4521}",
                "{\"t\":\"osAberta\",\"os\":\"12a\"}", "{\"t\":\"osAberta\",\"os\":\"1234567890123\"}",
                "{\"t\":\"osAberta\",\"os\":\"" + EMAIL + "\"}", "{\"t\":\"osAberta\",\"os\":null}"}) {
            assertFalse(torto, m.tratar(torto));
        }
        assertEquals("nenhuma torta chegou ao plugin", 2, acoes.push.size());
        assertEquals(6, acoes.erros.size());
        for (String erro : acoes.erros) assertTrue(erro, erro.startsWith("osAberta:"));
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) assertFalse(item.msg, item.msg.contains("4521"));
        semDadosDoTecnico();
    }

    @Test
    public void aRespostaDoPushLevaOTokenAVersaoEOsAvisos() throws JSONException {
        // A resposta não diz nada sobre registro e não depende do que veio antes: a página registra uma vez por token em
        // cada carga, com a conta de agora, e só com os avisos ligados.
        for (boolean avisos : new boolean[] {true, false}) {
            JSONObject r = objeto(Mensageiro.respostaDoPush(TOKEN, "1.1.0", avisos));
            assertEquals("pushToken", r.getString("t"));
            assertEquals(TOKEN, r.getString("token"));
            assertEquals("1.1.0", r.getString("versao"));
            assertEquals(avisos, r.getBoolean("avisos"));
            assertEquals("só o tipo, o token, a versão e os avisos", 4, r.length());
        }
        // Sem token (o Firebase não configurado, ou o token ainda não saiu): o token vai como null do JSON.
        for (String semToken : new String[] {null, ""}) {
            JSONObject r = objeto(Mensageiro.respostaDoPush(semToken, "0.0.0-dev", true));
            assertTrue("o token vai como null do JSON", r.has("token") && r.isNull("token"));
            assertEquals("0.0.0-dev", r.getString("versao"));
            assertEquals(4, r.length());
        }
        // Sem a versão, texto vazio: a página monta "NexusField/" e não "NexusField/null".
        assertEquals("", objeto(Mensageiro.respostaDoPush(TOKEN, null, true)).getString("versao"));
    }

    // --- o atalho de teste dos avisos (só para administrador) ------------------------------------------------------

    @Test
    public void pushAdminPublicaOuTiraOsAtalhos() {
        assertTrue(m.tratar("{\"t\":\"pushAdmin\",\"admin\":true}"));
        assertTrue(m.tratar("{\"t\":\"pushAdmin\",\"admin\":false}"));
        assertEquals(Arrays.asList(true, false), acoes.admins);
        assertTrue(acoes.erros.isEmpty());
    }

    @Test
    public void pushAdminTortoTiraOsAtalhos() {
        // Nunca fica com o atalho quem não foi dito Admin: o campo que falta ou não é booleano conta como "não".
        for (String torto : new String[] {"{\"t\":\"pushAdmin\"}", "{\"t\":\"pushAdmin\",\"admin\":\"true\"}",
                "{\"t\":\"pushAdmin\",\"admin\":1}", "{\"t\":\"pushAdmin\",\"admin\":null}"}) {
            assertFalse(torto, m.tratar(torto));
        }
        assertEquals(Arrays.asList(false, false, false, false), acoes.admins);
        assertEquals(4, acoes.erros.size());
    }

    @Test
    public void pushTesteDaOToastCertoParaCadaResposta() {
        assertTrue(m.tratar("{\"t\":\"pushTeste\",\"ok\":true,\"status\":200,\"aparelhos\":2}"));
        assertTrue(m.tratar("{\"t\":\"pushTeste\",\"ok\":false,\"status\":\"sem login\",\"aparelhos\":null}"));
        assertTrue(m.tratar("{\"t\":\"pushTeste\",\"ok\":false,\"status\":401,\"aparelhos\":null}"));
        assertTrue(m.tratar("{\"t\":\"pushTeste\",\"ok\":false,\"status\":\"erro\",\"aparelhos\":null}"));
        assertTrue(m.tratar("{\"t\":\"pushTeste\",\"ok\":false,\"status\":500,\"aparelhos\":null}"));
        // O 200 sem a contagem que se leia (o corpo torto, o número acima do teto que o script também corta) foi enviado:
        // o toast diz que saiu, sem número, e não convida a repetir (um teste a mais manda outro par de avisos a todos).
        assertTrue(m.tratar("{\"t\":\"pushTeste\",\"ok\":true,\"status\":200,\"aparelhos\":null}"));
        assertTrue(m.tratar("{\"t\":\"pushTeste\"}"));
        assertEquals(Arrays.asList("ENVIADO:2", "SEM_LOGIN:0", "SEM_LOGIN:0", "FALHOU:0", "FALHOU:0",
            "ENVIADO_SEM_CONTAGEM:0", "FALHOU:0"), acoes.testes);
        assertTrue("nenhum desses mexe nos atalhos", acoes.admins.isEmpty());
    }

    @Test
    public void oTesteRecusadoPorNaoSerAdminTiraOsAtalhos() {
        // O 403 é o servidor dizendo que a conta não é Admin (o papel mudou, ou um atalho que ficou de antes): o toast
        // diz "só administrador" e os atalhos saem na hora, sem esperar a próxima carga.
        assertTrue(m.tratar("{\"t\":\"pushTeste\",\"ok\":false,\"status\":403,\"aparelhos\":null}"));
        assertEquals(Arrays.asList("SO_ADMIN:0"), acoes.testes);
        assertEquals(Arrays.asList(false), acoes.admins);
    }

    @Test
    public void oTesteNaoLevaNadaDaContaAoLog() {
        assertTrue(m.tratar("{\"t\":\"pushTeste\",\"ok\":true,\"status\":200,\"aparelhos\":1,\"email\":\"" + EMAIL
            + "\",\"nome\":\"" + NOME + "\"}"));
        assertTrue(m.tratar("{\"t\":\"pushAdmin\",\"admin\":true,\"email\":\"" + EMAIL + "\"}"));
        assertFalse(m.tratar("{\"t\":\"pushAdmin\",\"admin\":\"" + EMAIL + "\"}"));
        semDadosDoTecnico();
    }

    // --- ajudantes ---------------------------------------------------------------------------------------------------

    /** O token do Firebase também não vai ao log (nem pelas ações, nem pelo android.util.Log). */
    private void semOToken() {
        List<String> tudo = new ArrayList<>(acoes.erros);
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) tudo.add(item.tag + " " + item.msg);
        for (String linha : tudo) {
            if (linha.contains(TOKEN) || linha.contains("APA91b")) fail("o log levou o token: " + linha);
        }
    }

    private static JSONObject objeto(String json) {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new AssertionError("a resposta não é JSON", e);
        }
    }

    private File arquivo(String nome, byte[] bytes) throws IOException {
        File f = pasta.newFile(nome);
        Files.write(f.toPath(), bytes);
        return f;
    }

    /** Nada do que o técnico guarda chega ao log: nem pelo aviso das ações, nem pelo android.util.Log. */
    private void semDadosDoTecnico() {
        List<String> tudo = new ArrayList<>(acoes.erros);
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            tudo.add(item.tag + " " + item.msg + (item.throwable == null ? "" : " " + item.throwable));
        }
        for (String linha : tudo) {
            for (String proibido : new String[] {EMAIL, "@exemplo", "tecnico.ficticio", "gc_ronda_", "gc_campo_queue",
                    "fracttal", "state=y", NOME}) {
                if (linha.contains(proibido)) fail("o log levou \"" + proibido + "\": " + linha);
            }
        }
    }

    private static final class Pedaco {
        String k;
        String id;
        int i;
        int n;
        String mime;
        byte[] dados;
    }

    private static final class EntradaIdb {
        final String k;
        final String tipo;
        final String mime;
        final String texto;
        final File arquivo;

        EntradaIdb(String k, String tipo, String mime, String texto, File arquivo) {
            this.k = k;
            this.tipo = tipo;
            this.mime = mime;
            this.texto = texto;
            this.arquivo = arquivo;
        }
    }

    /** O espelho de mentira: guarda em mapas e, quando pedido, lança como o EspelhoSqlite lançaria. */
    private static final class ArmazemFalso implements ArmazemEspelho {
        final Map<String, String> ls = new HashMap<>();
        final Map<String, String> lsTudo = new LinkedHashMap<>();
        final Map<String, String> idbTexto = new HashMap<>();
        final List<Pedaco> pedacos = new ArrayList<>();
        final List<EntradaIdb> idbOrdem = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        List<String> idbChaves = new ArrayList<>();
        String fila;
        boolean sentinela;
        int sentinelas;
        RuntimeException lancarAoEscrever;
        RuntimeException lancarAoLer;

        private void talvezLancar() {
            if (lancarAoEscrever != null) throw lancarAoEscrever;
        }

        private void talvezLancarAoLer() {
            if (lancarAoLer != null) throw lancarAoLer;
        }

        @Override public void gravarLs(String k, String v) { talvezLancar(); ls.put(k, v); }
        @Override public void apagarLs(String k) { talvezLancar(); ls.remove(k); }
        @Override public void gravarIdbTexto(String k, String tipo, String v) { talvezLancar(); idbTexto.put(k, tipo + "|" + v); }

        @Override
        public void receberPedaco(String k, String id, int i, int n, String mime, byte[] dados) {
            talvezLancar();
            Pedaco p = new Pedaco();
            p.k = k;
            p.id = id;
            p.i = i;
            p.n = n;
            p.mime = mime;
            p.dados = dados;
            pedacos.add(p);
        }

        @Override public void apagarIdb(String k) { talvezLancar(); idbTexto.remove(k); }
        @Override public void marcarSentinela() { talvezLancar(); sentinela = true; sentinelas++; }
        @Override public boolean temEspelho() { talvezLancarAoLer(); return sentinela; }
        @Override public List<String> filaHashes() { talvezLancarAoLer(); return hashes; }
        @Override public String filaMaisNova() { talvezLancarAoLer(); return fila; }
        @Override public Map<String, String> lsTudo() { talvezLancarAoLer(); return lsTudo; }
        @Override public List<String> idbChaves() { talvezLancarAoLer(); return idbChaves; }

        @Override
        public void paraCadaIdb(ItemIdb consumidor) {
            talvezLancarAoLer();
            for (EntradaIdb e : idbOrdem) consumidor.aceitar(e.k, e.tipo, e.mime, e.texto, e.arquivo);
        }
    }

    /** As ações do plugin, só anotadas. */
    private static final class AcoesFalsas implements Mensageiro.Acoes {
        final List<JSONObject> retratos = new ArrayList<>();
        final List<JSONObject> leves = new ArrayList<>();
        final List<String> fora = new ArrayList<>();
        final List<String> erros = new ArrayList<>();
        int idbPedidos;
        int idbRecusas;
        int pushPedidos;
        final List<Boolean> admins = new ArrayList<>();
        final List<String> testes = new ArrayList<>();
        final List<String> push = new ArrayList<>();

        @Override public void pushSaiu() { push.add("saiu"); }
        @Override public void pushRegistrado() { push.add("registrado"); }
        @Override public void osAberta(String os) { push.add("aberta:" + os); }
        @Override public void pushAdmin(boolean admin) { admins.add(admin); }
        @Override public void pushTeste(AtalhosDeTeste.Resultado resultado, int aparelhos) { testes.add(resultado + ":" + aparelhos); }
        @Override public void precisoRetrato(JSONObject retrato) { retratos.add(retrato); }
        @Override public void restaurado(JSONObject retratoLeve) { leves.add(retratoLeve); }
        @Override public void idbPedir() { idbPedidos++; }
        @Override public void idbRecusado() { idbRecusas++; }
        @Override public void pushPedirToken() { pushPedidos++; }
        @Override public void abrirFora(String url) { fora.add(url); }
        @Override public void erro(String onde, String msg) { erros.add(onde + ": " + msg); }
    }

}
