package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O atalho de teste dos avisos, só para administrador (Ruling N7, 18/10/2026): os dois atalhos dinâmicos do ícone
 * (publicados quando a página diz que a conta é Admin, tirados no resto), o canal que cada um pede, o resultado que a
 * página devolve e o toast de cada resultado. O launcher de verdade (o toque longo) só se vê no aparelho.
 */
@RunWith(RobolectricTestRunner.class)
public class AtalhosDeTesteTest {

    private Application app;

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        ShortcutManagerCompat.removeAllDynamicShortcuts(app);
    }

    private List<ShortcutInfoCompat> dinamicos() {
        return ShortcutManagerCompat.getDynamicShortcuts(app);
    }

    private Map<String, ShortcutInfoCompat> porId() {
        Map<String, ShortcutInfoCompat> mapa = new HashMap<>();
        for (ShortcutInfoCompat s : dinamicos()) mapa.put(s.getId(), s);
        return mapa;
    }

    @Test
    public void oAdminPublicaOsDoisAtalhosComOCanalDeCadaUm() {
        assertTrue(dinamicos().isEmpty());
        AtalhosDeTeste.publicar(app);

        Map<String, ShortcutInfoCompat> atalhos = porId();
        assertEquals(Arrays.asList("teste_avisos", "teste_fora_expediente"),
            new ArrayList<>(new java.util.TreeSet<>(atalhos.keySet())));

        ShortcutInfoCompat avisos = atalhos.get("teste_avisos");
        assertEquals(app.getString(R.string.atalho_teste_avisos_curto), avisos.getShortLabel().toString());
        assertEquals(app.getString(R.string.atalho_teste_avisos_longo), avisos.getLongLabel().toString());
        ShortcutInfoCompat fora = atalhos.get("teste_fora_expediente");
        assertEquals(app.getString(R.string.atalho_teste_fora_curto), fora.getShortLabel().toString());
        assertEquals(app.getString(R.string.atalho_teste_fora_longo), fora.getLongLabel().toString());

        // Cada atalho abre a tela do App com o canal que ele testa: o alto ("Testar avisos") e o silencioso.
        Intent doAvisos = avisos.getIntent();
        assertNotNull(doAvisos.getAction());
        assertEquals(MainActivity.class.getName(), doAvisos.getComponent().getClassName());
        assertEquals(app.getPackageName(), doAvisos.getComponent().getPackageName());
        assertEquals("emergencia", MainActivity.testeDoAtalho(new Intent(doAvisos)));
        assertEquals("emergencia_silenciosa", MainActivity.testeDoAtalho(new Intent(fora.getIntent())));
        // Sem CLEAR_TASK: com o app aberto, o toque traz a tela para a frente (singleTask, onNewIntent) sem recarregar a
        // página em que o técnico está.
        assertEquals(0, doAvisos.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK);

        // Publicar de novo (cada carga do Admin) não duplica.
        AtalhosDeTeste.publicar(app);
        assertEquals(2, dinamicos().size());
    }

    @Test
    public void oNaoAdminTiraOsAtalhos() {
        AtalhosDeTeste.publicar(app);
        assertEquals(2, dinamicos().size());
        AtalhosDeTeste.tirar(app);
        assertTrue(dinamicos().isEmpty());
        // Tirar sem nada publicado não falha (a carga de um técnico em aparelho que nunca teve Admin).
        AtalhosDeTeste.tirar(app);
        assertTrue(dinamicos().isEmpty());
    }

    @Test
    public void soOsDoisCanaisDaEmergenciaValem() {
        assertTrue(AtalhosDeTeste.canalValido("emergencia"));
        assertTrue(AtalhosDeTeste.canalValido("emergencia_silenciosa"));
        for (String outro : new String[] {null, "", "resumo", "EMERGENCIA", "emergencia ", " emergencia",
                "emergencia_silenciosa\n", "emergencia'); x('", "emergência"}) {
            assertFalse(String.valueOf(outro), AtalhosDeTeste.canalValido(outro));
        }
    }

    // --- o resultado que a página devolve -------------------------------------------------------------------------------

    @Test
    public void oResultadoDoTestePelaRespostaDaPagina() {
        assertEquals(AtalhosDeTeste.Resultado.ENVIADO, AtalhosDeTeste.resultado(true, 200, 2));
        assertEquals(AtalhosDeTeste.Resultado.ENVIADO, AtalhosDeTeste.resultado(true, 200, 0));
        // O 403 é a conta que não é Admin (a rota é só dele).
        assertEquals(AtalhosDeTeste.Resultado.SO_ADMIN, AtalhosDeTeste.resultado(false, 403, null));
        // Sem o Bearer na página, ou a sessão que o servidor não aceitou.
        assertEquals(AtalhosDeTeste.Resultado.SEM_LOGIN, AtalhosDeTeste.resultado(false, "sem login", null));
        assertEquals(AtalhosDeTeste.Resultado.SEM_LOGIN, AtalhosDeTeste.resultado(false, 401, null));
        // O resto é falha: sem rede, o prazo, o 5xx, o 404, e o 200 sem a contagem (a resposta fora do contrato).
        Object[][] falhas = {
            {false, "erro", null}, {false, 500, null}, {false, 404, null}, {true, 200, null}, {true, 200, -1},
            {true, 200, 1.5}, {true, 200, "2"}, {"true", 200, 2}, {null, null, null}, {false, 403.5, null},
            {false, "403", null}, {false, "Sem login", null},
        };
        for (Object[] f : falhas) {
            assertEquals(Arrays.toString(f), AtalhosDeTeste.Resultado.FALHOU, AtalhosDeTeste.resultado(f[0], f[1], f[2]));
        }
    }

    @Test
    public void aContagemDeAparelhosSoComoInteiro() {
        assertEquals(Integer.valueOf(2), AtalhosDeTeste.contagem(2));
        assertEquals(Integer.valueOf(0), AtalhosDeTeste.contagem(0));
        assertEquals(Integer.valueOf(3), AtalhosDeTeste.contagem(3.0)); // o número do JSON pode chegar como double
        for (Object torto : new Object[] {null, -1, 1.5, "2", Double.NaN, 1e12, true}) {
            assertNull(String.valueOf(torto), AtalhosDeTeste.contagem(torto));
        }
    }

    // --- o toast de cada resultado --------------------------------------------------------------------------------------

    @Test
    public void oToastCertoParaCadaResultado() {
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.ENVIADO, 2);
        assertEquals("Aviso de teste enviado para 2 aparelho(s)", ShadowToast.getTextOfLatestToast());
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.ENVIADO, 0);
        assertEquals("Aviso de teste enviado para 0 aparelho(s)", ShadowToast.getTextOfLatestToast());
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.SO_ADMIN, 0);
        assertEquals("Só administrador pode testar", ShadowToast.getTextOfLatestToast());
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.SEM_LOGIN, 0);
        assertEquals("Entre no Nexus Field para testar", ShadowToast.getTextOfLatestToast());
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.FALHOU, 0);
        assertEquals("Não consegui enviar o teste agora", ShadowToast.getTextOfLatestToast());
        assertEquals(5, ShadowToast.shownToastCount());
    }
}
