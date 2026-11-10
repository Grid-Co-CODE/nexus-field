package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** O envio dos pontos ao Nexus, com um servidor falso que grava cada pedido. */
@RunWith(RobolectricTestRunner.class)
public class EnvioDePontosTest {

    private static final long AGORA = 1_762_600_000_000L;
    // A conta da chave (o SHA-256 que a página calcula), fictícia: o envio não a usa.
    private static final String CONTA = "c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00";

    private Context ctx;
    private ChaveDaEquipe chave;
    private FilaDePontos fila;
    private final List<String[]> pedidos = new ArrayList<>(); // {url, chave, corpo}
    private EnvioDePontos.Resposta resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":200,\"coleta\":true}");
    private IOException falha = null;

    private final EnvioDePontos.Transporte falso = (url, k, corpo) -> {
        pedidos.add(new String[] {url, k, corpo});
        if (falha != null) throw falha;
        return resposta;
    };

    @Before
    public void abrir() {
        ctx = RuntimeEnvironment.getApplication();
        ctx.deleteDatabase("equipe.db");
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().clear().commit();
        chave = new ChaveDaEquipe(ctx, new CifraFalsa());
        fila = new FilaDePontos(ctx);
    }

    @After
    public void fechar() {
        ctx.deleteDatabase("equipe.db");
    }

    private EnvioDePontos envio() {
        return new EnvioDePontos(chave, fila, falso, () -> AGORA);
    }

    private void encher(int n) {
        long t = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            fila.guardar(new PontoDaEquipe(t - (n - i), -23.5, -46.6, 8.5f, 1.5f, true));
        }
    }

    @Test
    public void semChaveOuFilaVaziaNaoPedeNada() {
        encher(3);
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        chave.guardar("k1", CONTA);
        fila.limpar();
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        assertTrue(pedidos.isEmpty());
    }

    @Test
    public void duzentosPorLoteComAparelhoFusoEChave() throws Exception {
        chave.guardar("k1", CONTA);
        encher(250);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(1, pedidos.size());
        String[] p = pedidos.get(0);
        assertEquals("https://app.gridco.com.br/t/campo/equipe/posicoes", p[0]);
        assertEquals("k1", p[1]);
        JSONObject corpo = new JSONObject(p[2]);
        assertEquals(chave.aparelho(), corpo.getString("aparelho"));
        assertEquals(JanelaDaEquipe.fusoEmMinutos(AGORA, TimeZone.getDefault()), corpo.getInt("fuso"));
        assertEquals(200, corpo.getJSONArray("pontos").length());
        assertFalse(corpo.has("permissao"));
        assertEquals(50, fila.tamanho());
    }

    @Test
    public void cadaLoteEOAvisoSemPermissaoLevamOsAjustesDaColeta() throws Exception {
        // O "Celular pronto" do Piloto no Nexus (10/11/2026): o que falta no celular vai junto com o que ele já manda.
        chave.guardar("k1", CONTA);
        encher(2);
        JSONObject ajustes = new JSONObject().put("termo", "4").put("tempoTodo", false);
        EnvioDePontos comAjustes = new EnvioDePontos(chave, fila, falso, () -> AGORA, () -> ajustes);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, comAjustes.enviar());
        assertEquals(ajustes.toString(), new JSONObject(pedidos.get(0)[2]).getJSONObject("ajustes").toString());
        assertEquals(EnvioDePontos.Resultado.ENVIADO, comAjustes.avisarSemPermissao());
        assertEquals("4", new JSONObject(pedidos.get(1)[2]).getJSONObject("ajustes").getString("termo"));
    }

    @Test
    public void osAjustesQueNaoSeLeemNaoSeguramOsPontos() throws Exception {
        chave.guardar("k1", CONTA);
        encher(2);
        EnvioDePontos quebrado = new EnvioDePontos(chave, fila, falso, () -> AGORA, () -> {
            throw new IllegalStateException("o sistema não respondeu");
        });
        assertEquals(EnvioDePontos.Resultado.ENVIADO, quebrado.enviar());
        JSONObject corpo = new JSONObject(pedidos.get(0)[2]);
        assertFalse(corpo.has("ajustes"));
        assertEquals(2, corpo.getJSONArray("pontos").length());
    }

    @Test
    public void coletaFalsaParaApagaAChaveELimpaAFila() {
        chave.guardar("k1", CONTA);
        encher(5);
        resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":0,\"coleta\":false}");
        assertEquals(EnvioDePontos.Resultado.PARAR, envio().enviar());
        assertNull(chave.chave());
        assertEquals(0, fila.tamanho());
        assertFalse("a conta conferida no disco sai junto (R14)", temAMarca());
    }

    @Test
    public void quatrocentoEUmApagaAChaveEMantemAFila() {
        chave.guardar("k1", CONTA);
        encher(5);
        resposta = new EnvioDePontos.Resposta(401, "");
        assertEquals(EnvioDePontos.Resultado.PARAR, envio().enviar());
        assertNull(chave.chave());
        assertEquals(5, fila.tamanho());
        // A conta fica: é a dona da fila que sobrou (a mesma conta a sobe com a chave nova; outra conta a apaga antes).
        assertEquals(CONTA, chave.conta());
        assertFalse("a conta conferida no disco sai junto (R14)", temAMarca());
    }

    // A marca da conta conferida no disco (R14).
    private boolean temAMarca() {
        return ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).contains("conta_conferida");
    }

    @Test
    public void aContaConferidaSobreviveAPartidaNovaEOEnvioSaiSemAPagina() throws Exception {
        // R14, o caso de 10/11/2026: a atualização fechou o app, a coleta religou com a tela apagada e os pontos só subiram
        // quando o dono abriu o app (a página, que não roda com a tela apagada, conferia a conta).
        chave.guardar("k1", CONTA);
        encher(3);
        chave = new ChaveDaEquipe(ctx, new CifraFalsa()); // a partida nova, sem a página
        assertTrue(envio().podeEnviar());
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(1, pedidos.size());
        assertEquals("k1", pedidos.get(0)[1]);
        assertEquals(0, fila.tamanho());
        assertEquals("o aviso sem permissão também", EnvioDePontos.Resultado.ENVIADO, envio().avisarSemPermissao());
    }

    @Test
    public void semAContaConferidaNadaSaiMasORevogarNaoEspera() throws Exception {
        chave.guardar("k1", CONTA);
        encher(3);
        // A partida a frio com a chave que a 1.2.0 guardou, de antes da marca: a página ainda não mandou a conta.
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().remove("conta_conferida").commit();
        chave = new ChaveDaEquipe(ctx, new CifraFalsa());
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        assertEquals("nem o aviso sem permissão", EnvioDePontos.Resultado.NADA, envio().avisarSemPermissao());
        assertTrue("nada pedido ao Nexus", pedidos.isEmpty());
        assertEquals("a fila fica", 3, fila.tamanho());
        assertEquals("e a chave", "k1", chave.chave());

        assertTrue(chave.conferir(CONTA));
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(1, pedidos.size());
        assertEquals(0, fila.tamanho());

        // O Sair de outra partida, de novo sem a marca: a revogação vai com a chave velha, sem esperar conferência nenhuma.
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().remove("conta_conferida").commit();
        chave = new ChaveDaEquipe(ctx, new CifraFalsa());
        assertFalse(chave.conferida());
        envio().revogar();
        assertEquals(2, pedidos.size());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidos.get(1)[0]);
        assertEquals("k1", pedidos.get(1)[1]);
        assertNull(chave.chave());
    }

    @Test
    public void oDuzentosSemOColetaOuComOColetaQueNaoEBooleanoNaoApagaNada() {
        // O 200 fora do contrato (o chat do Nexus mudou um nome, ou um tratador de erro do servidor devolveu 200 {erro}):
        // só o coleta:false para e apaga, e só o coleta:true tira os pontos da fila. Sem isso, o primeiro envio de cada
        // aparelho apagava a chave e o dia guardado sem sinal.
        chave.guardar("k1", CONTA);
        encher(3);
        for (String corpo : new String[] {"{\"aceitos\":3}", "{\"coleta\":null}", "{\"coleta\":\"false\"}",
                "{\"coleta\":0}", "{\"erro\":\"interno\"}", "{\"coletar\":false}", "{}"}) {
            resposta = new EnvioDePontos.Resposta(200, corpo);
            assertEquals(corpo, EnvioDePontos.Resultado.SEM_REDE, envio().enviar());
            assertEquals(corpo, 3, fila.tamanho());
            assertEquals(corpo, "k1", chave.chave());
            assertEquals(corpo, CONTA, chave.conta());
            assertTrue(corpo, chave.conferida());
        }
        resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":0}");
        assertEquals("o aviso sem permissão, igual", EnvioDePontos.Resultado.SEM_REDE, envio().avisarSemPermissao());
        assertEquals("k1", chave.chave());
    }

    @Test
    public void oQuatrocentosQueNaoEOQuatrocentoEUmDescartaOLoteRecusadoEOEnvioSegue() {
        // O lote que o Nexus recusa (o 400 de um ponto torto, o 413, o 422) não passa se mandado de novo: sem o descarte ele
        // ficava na frente da fila, e nenhum ponto novo subia até as 24 h da fila.
        chave.guardar("k1", CONTA);
        encher(250);
        resposta = new EnvioDePontos.Resposta(400, "{\"erro\":\"lote\"}");
        assertEquals(EnvioDePontos.Resultado.DESCARTADO, envio().enviar());
        assertEquals("os 200 recusados saem da fila", 50, fila.tamanho());
        assertEquals("e a chave fica", "k1", chave.chave());
        resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":50,\"coleta\":true}");
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(0, fila.tamanho());
        for (int status : new int[] {402, 409, 410, 413, 422, 499}) {
            encher(2);
            resposta = new EnvioDePontos.Resposta(status, "");
            assertEquals(String.valueOf(status), EnvioDePontos.Resultado.DESCARTADO, envio().enviar());
            assertEquals(String.valueOf(status), 0, fila.tamanho());
        }
        // O 403, o 404 e o 405 falam do acesso ou da rota, e não do lote (o caminho que o chat do Nexus mudou, a rota que
        // caiu num deploy): descartar ali levaria o dia inteiro. O 408 e o 429 (o tempo esgotado e o excesso de pedidos)
        // também são de espera, como sem rede; o 3xx também (a resposta que não é do Nexus): o lote fica.
        for (int status : new int[] {403, 404, 405, 408, 429, 302}) {
            encher(2);
            resposta = new EnvioDePontos.Resposta(status, "");
            assertEquals(String.valueOf(status), EnvioDePontos.Resultado.SEM_REDE, envio().enviar());
            assertEquals(String.valueOf(status), 2, fila.tamanho());
            fila.limpar();
        }
        assertEquals("k1", chave.chave());
        resposta = new EnvioDePontos.Resposta(400, "");
        assertEquals("o aviso sem permissão recusado", EnvioDePontos.Resultado.DESCARTADO, envio().avisarSemPermissao());
        assertEquals("k1", chave.chave());
    }

    /** Uma linha gravada direto no banco da fila: a que o disco corrompeu, ou com um número que o JSON não escreve. */
    private void gravarLinhaCrua(String corpo) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(ctx.getDatabasePath(FilaDePontos.NOME_DO_BANCO).getPath(), null,
            SQLiteDatabase.OPEN_READWRITE);
        try {
            db.execSQL("INSERT INTO pontos(t, corpo) VALUES (?, ?)", new Object[] {System.currentTimeMillis(), corpo});
        } finally {
            db.close();
        }
    }

    @Test
    public void oPontoQueNaoSeLeNaFilaSaiEOLoteSegueComOsOutros() throws Exception {
        // O NaN que o org.json do Android lê de volta como número (e que o toString não escreve: o corpo saía null) e a linha
        // que não é JSON: sem o descarte, todo envio quebrava nelas (o lote mais antigo é sempre o mesmo) e nada mais subia.
        chave.guardar("k1", CONTA);
        encher(2);
        gravarLinhaCrua("{\"t\":1,\"lat\":NaN,\"lon\":-46.6,\"prec\":8,\"mov\":false}");
        gravarLinhaCrua("isto não é JSON");
        gravarLinhaCrua("{\"t\":1,\"lat\":-23.5,\"lon\":Infinity,\"prec\":8,\"mov\":false}");
        encher(1);
        assertEquals(6, fila.tamanho());
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals("os três pontos bons subiram", 3,
            new JSONObject(pedidos.get(0)[2]).getJSONArray("pontos").length());
        assertEquals("e os ruins saíram da fila", 0, fila.tamanho());

        // O lote só com pontos ruins (a linha cortada no meio, a que não tem a posição): eles saem, e nada vai ao Nexus.
        gravarLinhaCrua("{\"t\":1,\"lat\":");
        gravarLinhaCrua("{\"t\":1,\"prec\":8,\"mov\":false}");
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        assertEquals(1, pedidos.size());
        assertEquals(0, fila.tamanho());
    }

    @Test
    public void podeEnviarSoComAContaConferidaOuPorConferirDeNovo() {
        // O serviço pergunta antes de acordar o celular (a thread principal, sem o cofre).
        assertFalse(envio().podeEnviar());
        chave.guardar("k1", CONTA);
        assertTrue(envio().podeEnviar());
        chave = new ChaveDaEquipe(ctx, new CifraFalsa());
        assertTrue("a partida a frio, antes da página: a conferência do disco (R14)", envio().podeEnviar());
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().remove("conta_conferida").commit();
        chave = new ChaveDaEquipe(ctx, new CifraFalsa());
        assertFalse("a chave de antes da marca, antes da página", envio().podeEnviar());
        CifraFalsa fora = new CifraFalsa();
        fora.keystoreFora = true;
        chave = new ChaveDaEquipe(ctx, fora);
        try {
            chave.conferir(CONTA);
        } catch (IllegalStateException esperado) {
            // o cofre fora
        }
        assertTrue("a conferência por refazer (R9): o envio tenta", envio().podeEnviar());
    }

    @Test
    public void oEnvioConfereDeNovoAContaQueOCofreNaoDeixouConferir() throws Exception {
        // R9: o soluço do Keystore justo no pedido do aparelho; o cofre volta, e o próximo envio confere e sobe a fila.
        chave.guardar("k1", CONTA);
        encher(2);
        CifraFalsa cofre = new CifraFalsa();
        chave = new ChaveDaEquipe(ctx, cofre);
        cofre.keystoreFora = true;
        try {
            chave.conferir(CONTA);
        } catch (IllegalStateException esperado) {
            // o cofre fora
        }
        assertEquals("o cofre ainda fora: nada sai", EnvioDePontos.Resultado.NADA, envio().enviar());
        assertTrue(pedidos.isEmpty());
        cofre.keystoreFora = false;
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(2, new JSONObject(pedidos.get(0)[2]).getJSONArray("pontos").length());
        assertTrue(chave.conferida());
    }

    @Test
    public void semRedeMantemFilaEChave() {
        chave.guardar("k1", CONTA);
        encher(5);
        falha = new IOException("sem rede");
        assertEquals(EnvioDePontos.Resultado.SEM_REDE, envio().enviar());
        assertEquals(5, fila.tamanho());
        assertEquals("k1", chave.chave());
    }

    @Test
    public void todo5xxDaSemRedeEMantemAChave() {
        chave.guardar("k1", CONTA);
        encher(5);
        resposta = new EnvioDePontos.Resposta(503, "{}");
        assertEquals(EnvioDePontos.Resultado.SEM_REDE, envio().enviar());
        resposta = new EnvioDePontos.Resposta(500, "");
        assertEquals(EnvioDePontos.Resultado.SEM_REDE, envio().enviar());
        assertEquals(5, fila.tamanho());
        assertEquals("k1", chave.chave());
    }

    @Test
    public void respostaIlegivelNaoTiraDaFila() {
        chave.guardar("k1", CONTA);
        encher(5);
        resposta = new EnvioDePontos.Resposta(200, "<html>");
        assertEquals(EnvioDePontos.Resultado.SEM_REDE, envio().enviar());
        assertEquals(5, fila.tamanho());
        assertEquals("k1", chave.chave());
    }

    @Test
    public void loteQuePassariaDoLimiteDeBytesSaiMenor() throws Exception {
        chave.guardar("k1", CONTA);
        encher(200);
        EnvioDePontos pequeno = new EnvioDePontos(chave, fila, falso, () -> AGORA, 4000);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, pequeno.enviar());
        String corpo = pedidos.get(0)[2];
        assertTrue(corpo.getBytes(StandardCharsets.UTF_8).length <= 4000);
        int enviados = new JSONObject(corpo).getJSONArray("pontos").length();
        assertTrue(enviados > 0 && enviados < 200);
        assertEquals(200 - enviados, fila.tamanho());
    }

    @Test
    public void oCorteDoLoteContaTambemOsAjustes() throws Exception {
        // A revisão do Celular pronto: o limite vale para o corpo inteiro, com os ajustes dentro.
        chave.guardar("k1", CONTA);
        encher(200);
        StringBuilder grande = new StringBuilder();
        for (int i = 0; i < 1500; i++) grande.append('x');
        JSONObject ajustes = new JSONObject().put("termo", grande.toString());
        EnvioDePontos pequeno = new EnvioDePontos(chave, fila, falso, () -> AGORA, 4000, () -> ajustes);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, pequeno.enviar());
        String corpo = pedidos.get(0)[2];
        assertTrue(corpo.getBytes(StandardCharsets.UTF_8).length <= 4000);
        assertTrue(new JSONObject(corpo).has("ajustes"));
        int enviados = new JSONObject(corpo).getJSONArray("pontos").length();
        assertTrue(enviados > 0 && enviados < 200);
    }

    @Test
    public void limitePadraoE64Kb() {
        assertEquals(64 * 1024, EnvioDePontos.MAXIMO_DE_BYTES);
    }

    @Test
    public void revogarPedeComAChaveEApagaMesmoSeFalhar() throws Exception {
        chave.guardar("k1", CONTA);
        envio().revogar();
        assertEquals(1, pedidos.size());
        assertEquals("https://app.gridco.com.br/t/campo/equipe/aparelho/revogar", pedidos.get(0)[0]);
        assertEquals("k1", pedidos.get(0)[1]);
        assertEquals(chave.aparelho(), new JSONObject(pedidos.get(0)[2]).getString("aparelho"));
        assertNull(chave.chave());

        chave.guardar("k2", CONTA);
        falha = new IOException("sem rede");
        envio().revogar();
        assertNull(chave.chave());
    }

    @Test
    public void avisarSemPermissaoManda() throws Exception {
        chave.guardar("k1", CONTA);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().avisarSemPermissao());
        String[] p = pedidos.get(0);
        assertEquals("https://app.gridco.com.br/t/campo/equipe/posicoes", p[0]);
        assertEquals("k1", p[1]);
        JSONObject corpo = new JSONObject(p[2]);
        assertEquals(chave.aparelho(), corpo.getString("aparelho"));
        assertEquals("sem", corpo.getString("permissao"));
        assertEquals(0, corpo.getJSONArray("pontos").length());
        assertTrue(corpo.has("fuso"));
    }

    @Test
    public void avisarSemPermissaoTemAsMesmasRespostas() {
        assertEquals(EnvioDePontos.Resultado.NADA, envio().avisarSemPermissao());
        chave.guardar("k1", CONTA);
        resposta = new EnvioDePontos.Resposta(200, "{\"coleta\":false}");
        assertEquals(EnvioDePontos.Resultado.PARAR, envio().avisarSemPermissao());
        assertNull(chave.chave());
        chave.guardar("k1", CONTA);
        resposta = new EnvioDePontos.Resposta(401, "");
        assertEquals(EnvioDePontos.Resultado.PARAR, envio().avisarSemPermissao());
        chave.guardar("k1", CONTA);
        resposta = new EnvioDePontos.Resposta(503, "");
        assertEquals(EnvioDePontos.Resultado.SEM_REDE, envio().avisarSemPermissao());
        assertNotNull(chave.chave());
    }
}
