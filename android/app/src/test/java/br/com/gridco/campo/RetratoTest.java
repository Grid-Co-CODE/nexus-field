package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * O retrato é o que o nativo entrega ao inicio.js antes da página (window.__GC_RETRATO__). O formato é o contrato com o
 * script: o leve em toda partida, o completo quando ele pede (precisoRetrato, motivo 'perda' ou 'fila').
 */
@RunWith(RobolectricTestRunner.class)
public class RetratoTest {

    private static final String IDENT = "{\"email\":\"tecnico@exemplo.com.br\"}";

    private Context ctx;
    private EspelhoSqlite espelho;

    @Before
    public void abrir() {
        ctx = RuntimeEnvironment.getApplication();
        espelho = new EspelhoSqlite(ctx, new CifraFalsa());
    }

    @After
    public void fechar() {
        espelho.close();
    }

    private void encher() {
        espelho.marcarSentinela();
        espelho.gravarLs("gc_campo_queue", "[1]");
        espelho.gravarLs("gc_campo_queue", "[1,2]");
        espelho.gravarLs("gc_campo_ident", IDENT);
        espelho.gravarLs("gc_fech_draft", "{\"a\":\"ação 😀\"}");
        espelho.gravarLs("gc_subs_u1", "cache");
        espelho.gravarIdbTexto("rdraft:1", "texto", "rascunho");
        espelho.receberPedaco("f_1", "r1-a-1", 0, 1, "image/jpeg", new byte[] {1, 2, 3});
    }

    private static List<String> lista(JSONArray array) throws JSONException {
        List<String> saida = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) saida.add(array.getString(i));
        return saida;
    }

    // --- leve ---------------------------------------------------------------------------------------------------------

    @Test
    public void leveDoEspelhoVazio() throws JSONException {
        JSONObject leve = Retrato.leve(espelho);

        assertEquals(false, leve.getBoolean("temEspelho"));
        assertEquals(0, leve.getJSONArray("filaHashes").length());
        assertEquals(0, leve.getJSONArray("idbChaves").length());
    }

    @Test
    public void leveNaoTemLsNemFilaNemModo() throws JSONException {
        encher();

        JSONObject leve = Retrato.leve(espelho);

        assertFalse("o leve não leva o localStorage", leve.has("ls"));
        assertFalse(leve.has("fila"));
        assertFalse("sem modo, o script não restaura nada", leve.has("modo"));
        assertFalse(leve.has("motivo"));
        assertFalse(leve.has("id"));
        assertEquals(true, leve.getBoolean("temEspelho"));
        assertEquals(Arrays.asList(Fnv.hash("[1,2]"), Fnv.hash("[1]")), lista(leve.getJSONArray("filaHashes")));
        assertEquals(Arrays.asList("f_1", "rdraft:1"), lista(leve.getJSONArray("idbChaves")));
    }

    // idbChaves só leva entrada completa: o `n` do idbFim tem de bater com o que a restauração manda.
    @Test
    public void leveNaoAnunciaBlobIncompleto() throws JSONException {
        espelho.receberPedaco("f_1", "r2-a-1", 0, 2, "image/jpeg", new byte[] {1});

        assertEquals(0, Retrato.leve(espelho).getJSONArray("idbChaves").length());
    }

    // --- completo -----------------------------------------------------------------------------------------------------

    @Test
    public void completoDaPerdaTrazLsEFilaEMotivo() throws JSONException {
        encher();

        JSONObject completo = Retrato.completo(espelho, "perda");

        assertEquals("restaurar", completo.getString("modo"));
        assertEquals("perda", completo.getString("motivo"));
        assertFalse(completo.getString("id").isEmpty());
        JSONObject ls = completo.getJSONObject("ls");
        assertEquals(IDENT, ls.getString("gc_campo_ident"));
        assertEquals("{\"a\":\"ação 😀\"}", ls.getString("gc_fech_draft"));
        assertEquals("cache", ls.getString("gc_subs_u1"));
        assertFalse("a fila vai no campo próprio", ls.has("gc_campo_queue"));
        assertEquals("[1,2]", completo.getString("fila"));
        // O completo é o leve mais os campos da restauração.
        assertEquals(true, completo.getBoolean("temEspelho"));
        assertEquals(Arrays.asList(Fnv.hash("[1,2]"), Fnv.hash("[1]")), lista(completo.getJSONArray("filaHashes")));
        assertEquals(Arrays.asList("f_1", "rdraft:1"), lista(completo.getJSONArray("idbChaves")));
    }

    @Test
    public void completoDaFilaLevaSoAFilaEComLsVazio() throws JSONException {
        encher();

        JSONObject completo = Retrato.completo(espelho, "fila");

        assertEquals("restaurar", completo.getString("modo"));
        assertEquals("fila", completo.getString("motivo"));
        assertFalse(completo.getString("id").isEmpty());
        assertEquals("[1,2]", completo.getString("fila"));
        assertTrue("ls presente e vazio", completo.has("ls"));
        assertEquals(0, completo.getJSONObject("ls").length());
    }

    @Test
    public void completoSemFilaTrazFilaNula() throws JSONException {
        espelho.marcarSentinela();
        espelho.gravarLs("gc_fech_draft", "{}");

        for (String motivo : new String[] {"perda", "fila"}) {
            JSONObject completo = Retrato.completo(espelho, motivo);
            assertTrue("a chave existe, com null (o script só aceita string)", completo.has("fila"));
            assertTrue(completo.isNull("fila"));
        }
    }

    // O script não aplica duas vezes o mesmo retrato completo (SESSAO_RETRATO), e para isso o id tem de mudar a cada
    // retrato: dois pedidos na mesma sessão com o mesmo id seriam tomados pelo mesmo retrato.
    @Test
    public void idMudaAEntreDuasChamadas() throws JSONException {
        encher();

        String primeiro = Retrato.completo(espelho, "perda").getString("id");
        String segundo = Retrato.completo(espelho, "perda").getString("id");
        String terceiro = Retrato.completo(espelho, "fila").getString("id");

        assertFalse(primeiro.isEmpty());
        assertNotEquals(primeiro, segundo);
        assertNotEquals(segundo, terceiro);
        assertNotEquals(primeiro, terceiro);
    }

    @Test
    public void motivoDesconhecidoELancado() {
        for (String motivo : new String[] {"outro", "", "PERDA", null}) {
            try {
                Retrato.completo(espelho, motivo);
                fail("motivo " + motivo + " aceito");
            } catch (IllegalArgumentException esperado) {
                // ok
            }
        }
    }

    // --- script -------------------------------------------------------------------------------------------------------

    @Test
    public void scriptEORetratoMaisOInicioJs() throws JSONException {
        encher();
        JSONObject retrato = Retrato.completo(espelho, "perda");
        String inicio = "(function () { 'use strict'; var x = '$1 \\\\ \\n'; })();\n";

        String script = Retrato.script(retrato, inicio);

        String prefixo = "window.__GC_RETRATO__=";
        assertTrue(script.startsWith(prefixo));
        assertTrue(script.endsWith(";\n" + inicio));
        String json = script.substring(prefixo.length(), script.length() - (";\n" + inicio).length());
        assertEquals(retrato.toString(), json);
        // E o trecho entre o prefixo e o ponto e vírgula é um JSON que volta a ser o mesmo retrato.
        assertEquals("perda", new JSONObject(json).getString("motivo"));
        assertEquals("[1,2]", new JSONObject(json).getString("fila"));
    }

    @Test
    public void scriptNaoQuebraComTextoDificil() throws JSONException {
        // Aspas, barra, quebra de linha e o separador de linha do Unicode dentro de um valor: o JSON tem de continuar
        // sendo UMA expressão de JavaScript, sem fechar o literal antes da hora.
        espelho.gravarLs("gc_fech_draft", "linha1\nlinha2 \"aspas\" \\ barra   </script> fim");
        JSONObject completo = Retrato.completo(espelho, "perda");

        String script = Retrato.script(completo, "// js\n");

        String json = script.substring("window.__GC_RETRATO__=".length(), script.length() - ";\n// js\n".length());
        assertEquals("linha1\nlinha2 \"aspas\" \\ barra   </script> fim",
            new JSONObject(json).getJSONObject("ls").getString("gc_fech_draft"));
        assertFalse("sem quebra de linha crua dentro do literal", json.contains("\n"));
    }
}
