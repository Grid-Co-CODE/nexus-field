package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** A fila local de pontos da equipe: no máximo 1000 pontos e nada com mais de 24 h; passou, sai o mais velho. */
@RunWith(RobolectricTestRunner.class)
public class FilaDePontosTest {

    private Context ctx;
    private FilaDePontos fila;

    @Before
    public void abrir() {
        ctx = RuntimeEnvironment.getApplication();
        ctx.deleteDatabase("equipe.db");
        fila = new FilaDePontos(ctx);
    }

    @After
    public void fechar() {
        ctx.deleteDatabase("equipe.db");
    }

    private static PontoDaEquipe ponto(long t) {
        return new PontoDaEquipe(t, -23.5, -46.6, 8.5f, 1.5f, true);
    }

    @Test
    public void guardaTresELeOLoteNaOrdem() throws Exception {
        long agora = System.currentTimeMillis();
        fila.guardar(ponto(agora - 3000));
        fila.guardar(ponto(agora - 2000));
        fila.guardar(ponto(agora - 1000));
        assertEquals(3, fila.tamanho());
        List<Long> ids = fila.idsDoLote(200);
        assertEquals(3, ids.size());
        JSONArray lote = fila.lote(ids);
        assertEquals(3, lote.length());
        assertEquals(agora - 3000, lote.getJSONObject(0).getLong("t"));
        assertEquals(agora - 2000, lote.getJSONObject(1).getLong("t"));
        assertEquals(agora - 1000, lote.getJSONObject(2).getLong("t"));
        assertEquals(-23.5, lote.getJSONObject(0).getDouble("lat"), 0.0);
        assertTrue(lote.getJSONObject(0).getBoolean("mov"));
    }

    @Test
    public void oLoteRespeitaOMaximo() {
        long agora = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) fila.guardar(ponto(agora - 5000 + i));
        assertEquals(2, fila.idsDoLote(2).size());
    }

    @Test
    public void removerTiraSoOsIdsDados() throws Exception {
        long agora = System.currentTimeMillis();
        fila.guardar(ponto(agora - 3000));
        fila.guardar(ponto(agora - 2000));
        fila.guardar(ponto(agora - 1000));
        List<Long> ids = fila.idsDoLote(10);
        fila.remover(Arrays.asList(ids.get(0), ids.get(2)));
        assertEquals(1, fila.tamanho());
        assertEquals(agora - 2000, fila.lote(fila.idsDoLote(10)).getJSONObject(0).getLong("t"));
    }

    @Test
    public void comMilECincoPontosFicamMilEOsCincoMaisVelhosSaem() throws Exception {
        long base = System.currentTimeMillis() - 100_000L;
        for (int i = 0; i < 1005; i++) fila.guardar(ponto(base + i));
        assertEquals(1000, fila.tamanho());
        JSONArray lote = fila.lote(fila.idsDoLote(1));
        assertEquals(base + 5, lote.getJSONObject(0).getLong("t"));
    }

    @Test
    public void pontoComMaisDe24HSaiNaProximaGravacao() {
        long agora = System.currentTimeMillis();
        fila.guardar(ponto(agora - 25L * 3600_000L));
        fila.guardar(ponto(agora - 1000));
        assertEquals(1, fila.tamanho());
    }

    @Test
    public void limparZera() {
        fila.guardar(ponto(System.currentTimeMillis()));
        fila.limpar();
        assertEquals(0, fila.tamanho());
        assertTrue(fila.idsDoLote(10).isEmpty());
    }

    @Test
    public void semVelocidadeOCampoVelNaoVai() throws Exception {
        PontoDaEquipe p = new PontoDaEquipe(1L, 1.0, 2.0, 3f, null, false);
        assertFalse(p.json().has("vel"));
    }
}
