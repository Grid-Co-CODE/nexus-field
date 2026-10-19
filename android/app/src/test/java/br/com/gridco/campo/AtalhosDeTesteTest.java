package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowShortcutManager;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O atalho de teste dos avisos, só para administrador: os dois atalhos dinâmicos do ícone (publicados quando a página
 * diz que a conta é Admin, tirados no resto, e o que o administrador fixou na tela inicial desligado), o canal que cada
 * um pede pela porta interna, o resultado que a página devolve e o toast de cada resultado. O launcher de verdade (o
 * toque longo) só se vê no aparelho.
 */
@RunWith(RobolectricTestRunner.class)
public class AtalhosDeTesteTest {

    private Application app;
    private ShortcutManager sistema;

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        sistema = app.getSystemService(ShortcutManager.class);
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

    // Os fixados que o sistema guarda desligados (o ShadowShortcutManager 4.17 não marca o ShortcutInfo, só o põe à parte).
    private static Map<String, ShortcutInfo> fixadosDesligados() {
        return ReflectionHelpers.getStaticField(ShadowShortcutManager.class, "disabledPinnedShortcuts");
    }

    private static Map<String, ShortcutInfo> fixadosLigados() {
        return ReflectionHelpers.getStaticField(ShadowShortcutManager.class, "activePinnedShortcuts");
    }

    // O administrador arrasta o atalho do menu para a tela inicial.
    private void fixar(String id) {
        ShortcutInfo atalho = null;
        for (ShortcutInfo s : sistema.getDynamicShortcuts()) if (s.getId().equals(id)) atalho = s;
        assertNotNull("o atalho " + id + " está publicado", atalho);
        assertTrue(sistema.requestPinShortcut(atalho, null));
    }

    @Test
    public void oAdminPublicaOsDoisAtalhosComOCanalDeCadaUmPelaPortaInterna() {
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
        // A ordem do menu é a do rank: o canal alto primeiro.
        assertTrue(avisos.getRank() < fora.getRank());

        // Cada atalho abre a tela pela porta interna (a activity-alias não exportada) com o canal que ele testa: o alto e
        // o silencioso. O launcher abre o atalho em nome do próprio app, e só o Intent da porta interna vale.
        Intent doAvisos = avisos.getIntent();
        assertNotNull(doAvisos.getAction());
        assertEquals(MainActivity.PORTA_INTERNA, doAvisos.getComponent().getClassName());
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
    public void publicarNaoTiraOsAtalhosDeOutraOrigem() {
        // O setDynamicShortcuts trocaria TODOS os dinâmicos do app pelos dois: um atalho dinâmico de outra parte do app
        // (ou de uma versão futura) sumiria a cada carga do Admin.
        assertTrue(ShortcutManagerCompat.addDynamicShortcuts(app, Arrays.asList(new ShortcutInfoCompat.Builder(app, "outro")
            .setShortLabel("Outro").setIntent(new Intent(Intent.ACTION_VIEW).setClassName(app, MainActivity.class.getName()))
            .build())));
        AtalhosDeTeste.publicar(app);
        assertEquals(3, dinamicos().size());
        AtalhosDeTeste.tirar(app);
        assertEquals("tirar também só mexe nos dois", Arrays.asList("outro"), new ArrayList<>(porId().keySet()));
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
    public void oAtalhoFixadoFicaDesligadoParaQuemNaoEAdminEOAdminORelga() {
        // O atalho que o administrador fixou na tela inicial não sai com o removeDynamicShortcuts: tocado por outra conta,
        // mandava o pedido ao servidor. Desligado, o launcher mostra o "Só para administrador" e nem abre o app.
        AtalhosDeTeste.publicar(app);
        fixar("teste_avisos");
        AtalhosDeTeste.tirar(app);
        assertTrue("o fixado fica desligado", fixadosDesligados().containsKey("teste_avisos"));
        assertFalse(fixadosLigados().containsKey("teste_avisos"));
        assertTrue(dinamicos().isEmpty());

        // O Admin entra de novo: o fixado é religado antes de publicar, e os dois voltam ao menu (o fixado o Robolectric
        // guarda só como fixado; no aparelho ele é fixado e dinâmico ao mesmo tempo).
        AtalhosDeTeste.publicar(app);
        assertTrue("religado", fixadosLigados().containsKey("teste_avisos"));
        assertFalse(fixadosDesligados().containsKey("teste_avisos"));
        assertTrue(porId().containsKey("teste_fora_expediente"));
    }

    @Test
    public void oTextoDoAtalhoDesligadoESoParaAdministrador() {
        assertEquals("Só para administrador", app.getString(R.string.atalho_teste_desligado));
    }

    @Test
    public void osRotulosCurtosCabemNoAtalhoFixado() {
        // O launcher corta o rótulo curto a uns 10 caracteres (é o que aparece embaixo do atalho fixado). E nenhum promete
        // "sem som": o teste manda também um resumo, que toca no canal dele.
        for (int id : new int[] {R.string.atalho_teste_avisos_curto, R.string.atalho_teste_fora_curto}) {
            String curto = app.getString(id);
            assertTrue(curto + " tem " + curto.length() + " caracteres", curto.length() <= 10);
        }
        for (int id : new int[] {R.string.atalho_teste_avisos_curto, R.string.atalho_teste_avisos_longo,
                R.string.atalho_teste_fora_curto, R.string.atalho_teste_fora_longo}) {
            assertFalse(app.getString(id), app.getString(id).toLowerCase(java.util.Locale.ROOT).contains("sem som"));
        }
    }

    @Test
    public void oUsoDoAtalhoTocadoEContado() {
        // O launcher ordena os atalhos pelo uso: o toque conta para o do canal que ele pediu.
        AtalhosDeTeste.relatarUso(app, "emergencia_silenciosa");
        AtalhosDeTeste.relatarUso(app, "emergencia");
        AtalhosDeTeste.relatarUso(app, "resumo"); // não é de atalho nenhum: nada
        AtalhosDeTeste.relatarUso(app, null);
        ShadowShortcutManager sombra = shadowOf(sistema);
        assertEquals(Arrays.asList("teste_fora_expediente", "teste_avisos"), sombra.getReportedShortcutsUsed());
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
        // O 200 sem a contagem que se leia: enviado, sem o número (a resposta fora do contrato, o número acima do teto).
        for (Object semContagem : new Object[] {null, -1, 1.5, "2", 10_001, true}) {
            assertEquals(String.valueOf(semContagem), AtalhosDeTeste.Resultado.ENVIADO_SEM_CONTAGEM,
                AtalhosDeTeste.resultado(true, 200, semContagem));
        }
        // O 403 é a conta que não é Admin (a rota é só dele).
        assertEquals(AtalhosDeTeste.Resultado.SO_ADMIN, AtalhosDeTeste.resultado(false, 403, null));
        // Sem o Bearer na página, ou a sessão que o servidor não aceitou.
        assertEquals(AtalhosDeTeste.Resultado.SEM_LOGIN, AtalhosDeTeste.resultado(false, "sem login", null));
        assertEquals(AtalhosDeTeste.Resultado.SEM_LOGIN, AtalhosDeTeste.resultado(false, 401, null));
        // O resto é falha: sem rede, o prazo, o 5xx, o 404 e o ok que não é o true do JSON.
        Object[][] falhas = {
            {false, "erro", null}, {false, 500, null}, {false, 404, null}, {"true", 200, 2}, {null, null, null},
            {false, 403.5, null}, {false, "403", null}, {false, "Sem login", null}, {1, 200, 2},
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
        assertEquals(Integer.valueOf(10_000), AtalhosDeTeste.contagem(10_000)); // o teto, o mesmo do script
        for (Object torto : new Object[] {null, -1, 1.5, "2", Double.NaN, 1e12, true, 10_001}) {
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
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.ENVIADO_SEM_CONTAGEM, 0);
        assertEquals("Aviso de teste enviado", ShadowToast.getTextOfLatestToast());
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.SO_ADMIN, 0);
        assertEquals("Só administrador pode testar", ShadowToast.getTextOfLatestToast());
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.SEM_LOGIN, 0);
        assertEquals("Entre no Nexus Field para testar", ShadowToast.getTextOfLatestToast());
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.FALHOU, 0);
        assertEquals("Não consegui enviar o teste agora", ShadowToast.getTextOfLatestToast());
        // O segundo toque em menos de 10 s: o primeiro teste já foi pedido (e pode ter saído), então não é "falhou".
        AtalhosDeTeste.avisar(app, AtalhosDeTeste.Resultado.JA_PEDIDO, 0);
        assertEquals("Teste já pedido. Espere alguns segundos para pedir outro", ShadowToast.getTextOfLatestToast());
        assertEquals(7, ShadowToast.shownToastCount());
    }
}
