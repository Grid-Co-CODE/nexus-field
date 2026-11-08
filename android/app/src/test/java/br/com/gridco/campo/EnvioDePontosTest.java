package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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
    public void coletaFalsaParaApagaAChaveELimpaAFila() {
        chave.guardar("k1", CONTA);
        encher(5);
        resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":0,\"coleta\":false}");
        assertEquals(EnvioDePontos.Resultado.PARAR, envio().enviar());
        assertNull(chave.chave());
        assertEquals(0, fila.tamanho());
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
    }

    @Test
    public void semAContaConferidaNestaPartidaNadaSaiMasORevogarNaoEspera() throws Exception {
        chave.guardar("k1", CONTA);
        encher(3);
        // A partida a frio: a chave e a conta no disco, e a página desta partida ainda não mandou a conta.
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

        // O Sair de outra partida: a revogação vai com a chave velha, sem esperar conferência nenhuma.
        chave = new ChaveDaEquipe(ctx, new CifraFalsa());
        envio().revogar();
        assertEquals(2, pedidos.size());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidos.get(1)[0]);
        assertEquals("k1", pedidos.get(1)[1]);
        assertNull(chave.chave());
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
