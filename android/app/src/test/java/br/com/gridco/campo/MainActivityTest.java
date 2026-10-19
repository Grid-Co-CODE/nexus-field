package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.getcapacitor.CapConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * As regras da primeira abertura que não dependem da tela: o aviso depois do pedido das permissões, quando pedir a
 * notificação e o endereço que a WebView antiga abre; e o que o toque na notificação e o atalho de teste trazem no
 * Intent, que só vale pela porta interna. A tela (diálogos, o pedido de verdade) só se vê no aparelho.
 */
@RunWith(RobolectricTestRunner.class)
public class MainActivityTest {

    @Test
    public void avisoDepoisDoPedidoNasCombinacoesDePermissao() {
        // câmera, precisa, aproximada
        assertEquals(MainActivity.Aviso.NENHUM, MainActivity.avisoDepoisDoPedido(true, true, true));
        assertEquals(MainActivity.Aviso.NENHUM, MainActivity.avisoDepoisDoPedido(true, true, false));
        // Android 12+: o técnico escolheu "aproximada" no pedido.
        assertEquals(MainActivity.Aviso.APROXIMADA, MainActivity.avisoDepoisDoPedido(true, false, true));
        assertEquals(MainActivity.Aviso.NEGADAS, MainActivity.avisoDepoisDoPedido(true, false, false));
        // A câmera negada vence o caso da aproximada: o texto das negadas já manda liberar a localização também.
        assertEquals(MainActivity.Aviso.NEGADAS, MainActivity.avisoDepoisDoPedido(false, false, true));
        assertEquals(MainActivity.Aviso.NEGADAS, MainActivity.avisoDepoisDoPedido(false, true, true));
        assertEquals(MainActivity.Aviso.NEGADAS, MainActivity.avisoDepoisDoPedido(false, true, false));
        assertEquals(MainActivity.Aviso.NEGADAS, MainActivity.avisoDepoisDoPedido(false, false, false));
    }

    @Test
    public void aWebViewAntigaNaoAbreOEnderecoDoApp() {
        // Sem o script de início a página do App rodaria sem espelho, e a janela do Fracttal apagaria a tela. Sem
        // server.url, o Bridge carrega a página local (https://localhost), e o App nem é pedido ao servidor.
        CapConfig config = MainActivity.configSemOApp(RuntimeEnvironment.getApplication());
        assertNull(config.getServerUrl());
        assertNull(config.getStartPath());
        assertNull("sem página de erro: o diálogo é o aviso", config.getErrorPath());
        assertNull("nem o login da Microsoft abre", config.getAllowNavigation());
        assertEquals("localhost", config.getHostname());
    }

    @Test
    public void pedeSoNoAndroid13ComPermissaoNegadaEUmaVez() {
        assertTrue(MainActivity.devePedirNotificacao(33, false, false));
        assertFalse(MainActivity.devePedirNotificacao(32, false, false));
        assertFalse(MainActivity.devePedirNotificacao(33, true, false));
        assertFalse(MainActivity.devePedirNotificacao(33, false, true));
    }

    @Test
    public void aNotificacaoNegadaNaoEPedidaDeNovoNemNosAndroidsMaisNovos() {
        // Do 13 em diante a regra é a mesma: 34, 35 e 36 pedem uma vez; o Android 12 para trás nem tem a permissão.
        for (int sdk : new int[] {34, 35, 36}) {
            assertTrue(MainActivity.devePedirNotificacao(sdk, false, false));
            assertFalse(MainActivity.devePedirNotificacao(sdk, false, true));
        }
        assertFalse(MainActivity.devePedirNotificacao(26, false, false));
        // Negada uma vez, "já pediu" vale mesmo que a permissão volte a faltar: o técnico decide nos ajustes.
        assertFalse(MainActivity.devePedirNotificacao(36, false, true));
    }

    @Test
    public void aMarcaDeJaPediuMoraNoPushESobreviveAoTokenNovo() {
        Application app = RuntimeEnvironment.getApplication();
        assertFalse("instalação nova: ainda não pediu", MainActivity.jaPediuNotificacao(app));

        MainActivity.marcarNotificacaoPedida(app);
        assertTrue(MainActivity.jaPediuNotificacao(app));
        SharedPreferences push = app.getSharedPreferences("push", Context.MODE_PRIVATE);
        assertTrue("a marca é jaPediuNotificacao no SharedPreferences \"push\"", push.getBoolean("jaPediuNotificacao", false));

        // O token novo do Firebase mexe no mesmo arquivo (a chave do token) e não pode devolver o pedido ao técnico que já
        // o respondeu.
        MensagensDoFirebase.guardarTokenNovo(app, "token-de-teste");
        assertTrue(MainActivity.jaPediuNotificacao(app));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A porta interna: o toque na notificação e o atalho de teste chegam por ela
    // ---------------------------------------------------------------------------------------------------------------

    /** O Intent que a notificação e o atalho mandam: a porta interna (a activity-alias não exportada) do próprio app. */
    private static Intent pelaPortaInterna() {
        return new Intent().setClassName(RuntimeEnvironment.getApplication(), MainActivity.PORTA_INTERNA);
    }

    /** O Intent que qualquer app pode mandar: a MainActivity exportada (o ícone também chega por ela). */
    private static Intent pelaMainActivity() {
        return new Intent().setClassName(RuntimeEnvironment.getApplication(), MainActivity.class.getName());
    }

    @Test
    public void aPortaInternaEUmaAliasDaMainActivityQueNenhumAppDeForaAlcanca() throws Exception {
        // O manifesto (o mesclado, que o Robolectric lê): a alias aponta para a MainActivity, não é exportada e não tem
        // filtro (sem filtro, nem o sistema a oferece a outro app). A notificação e o launcher a abrem em nome do app.
        Application app = RuntimeEnvironment.getApplication();
        ActivityInfo porta = app.getPackageManager()
            .getActivityInfo(new ComponentName(app, MainActivity.PORTA_INTERNA), PackageManager.MATCH_DISABLED_COMPONENTS);
        assertEquals(MainActivity.class.getName(), porta.targetActivity);
        assertFalse("não exportada", porta.exported);
        Intent qualquer = new Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_DEFAULT).setPackage(app.getPackageName());
        for (ResolveInfo r : app.getPackageManager().queryIntentActivities(qualquer, 0)) {
            assertFalse("a porta interna não responde a filtro nenhum", MainActivity.PORTA_INTERNA.equals(r.activityInfo.name));
        }
        ActivityInfo principal = app.getPackageManager()
            .getActivityInfo(new ComponentName(app, MainActivity.class.getName()), 0);
        assertTrue("a MainActivity segue exportada (o ícone)", principal.exported);
    }

    @Test
    public void aOsDoToqueSaiDoExtraEUmaVezSo() {
        Intent toque = pelaPortaInterna().putExtra(Notificacoes.EXTRA_OS, "12345");
        assertEquals("12345", MainActivity.osDoToque(toque));
        // O extra é consumido: o mesmo Intent relido (a tela recriada, o setIntent) não abre a OS de novo.
        assertNull(MainActivity.osDoToque(toque));
        assertFalse(toque.hasExtra(Notificacoes.EXTRA_OS));
    }

    @Test
    public void semExtraOuComIntentNuloNaoHaOs() {
        assertNull(MainActivity.osDoToque(null));
        assertNull("o toque no resumo não leva OS", MainActivity.osDoToque(pelaPortaInterna()));
    }

    @Test
    public void umExtraQueNaoEOsValidaEDescartadoEConsumido() {
        // Só passa o número de OS, mesmo pela porta interna (defesa em profundidade: o texto vai a um script na página).
        for (String os : new String[] {"", "12a", "1'); x('", " 12", "1234567890123", "１２"}) {
            Intent torto = pelaPortaInterna().putExtra(Notificacoes.EXTRA_OS, os);
            assertNull(os, MainActivity.osDoToque(torto));
            assertFalse("consumido mesmo recusado: " + os, torto.hasExtra(Notificacoes.EXTRA_OS));
        }
        // E o extra que nem é texto.
        assertNull(MainActivity.osDoToque(pelaPortaInterna().putExtra(Notificacoes.EXTRA_OS, 12345)));
    }

    @Test
    public void aOsQueChegaPelaMainActivityExportadaEIgnoradaEConsumida() {
        // Um app de fora manda à MainActivity exportada um Intent com uma OS válida: ela trocaria a pendente da emergência
        // de verdade (a última vence) e traria o app para a frente. Só a porta interna leva a OS à página.
        for (Intent deFora : new Intent[] {pelaMainActivity(), new Intent(), new Intent(Intent.ACTION_VIEW)}) {
            deFora.putExtra(Notificacoes.EXTRA_OS, "99999");
            assertNull(String.valueOf(deFora.getComponent()), MainActivity.osDoToque(deFora));
            assertFalse("consumido: não sobra para depois", deFora.hasExtra(Notificacoes.EXTRA_OS));
        }
        // A classe certa em outro pacote também não vale.
        Intent outroPacote = new Intent().setClassName("br.com.outro.app", MainActivity.PORTA_INTERNA)
            .putExtra(Notificacoes.EXTRA_OS, "99999");
        assertNull(MainActivity.osDoToque(outroPacote));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // O atalho de teste dos avisos: o canal que vem no Intent
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    public void oCanalDoAtalhoSaiDoExtraEUmaVezSo() {
        for (String canal : new String[] {"emergencia", "emergencia_silenciosa"}) {
            Intent toque = pelaPortaInterna().putExtra(AtalhosDeTeste.EXTRA_TESTE, canal);
            assertEquals(canal, MainActivity.testeDoAtalho(toque));
            assertNull("consumido: o mesmo Intent não testa duas vezes", MainActivity.testeDoAtalho(toque));
            assertFalse(toque.hasExtra(AtalhosDeTeste.EXTRA_TESTE));
        }
        assertNull(MainActivity.testeDoAtalho(null));
        assertNull("o ícone e a notificação não levam teste", MainActivity.testeDoAtalho(pelaPortaInterna()));
    }

    @Test
    public void umExtraDeTesteQueNaoEUmDosDoisCanaisEDescartadoEConsumido() {
        for (String canal : new String[] {"", "resumo", "EMERGENCIA", "emergencia ", "emergencia'); x('"}) {
            Intent torto = pelaPortaInterna().putExtra(AtalhosDeTeste.EXTRA_TESTE, canal);
            assertNull(canal, MainActivity.testeDoAtalho(torto));
            assertFalse("consumido mesmo recusado: " + canal, torto.hasExtra(AtalhosDeTeste.EXTRA_TESTE));
        }
        assertNull(MainActivity.testeDoAtalho(pelaPortaInterna().putExtra(AtalhosDeTeste.EXTRA_TESTE, 1)));
    }

    @Test
    public void oTesteQueChegaPelaMainActivityExportadaEIgnoradoEConsumido() {
        // Um app de fora mandaria dois pares de avisos a cada aparelho da conta por toque falso: só a porta interna vale.
        for (Intent deFora : new Intent[] {pelaMainActivity(), new Intent()}) {
            deFora.putExtra(AtalhosDeTeste.EXTRA_TESTE, "emergencia");
            assertNull(MainActivity.testeDoAtalho(deFora));
            assertFalse(deFora.hasExtra(AtalhosDeTeste.EXTRA_TESTE));
        }
    }

    @Test
    public void oAtalhoReentregueDosRecentesNaoTestaDeNovo() {
        Intent dosRecentes = pelaPortaInterna().putExtra(AtalhosDeTeste.EXTRA_TESTE, "emergencia")
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        assertNull(MainActivity.testeDoAtalho(dosRecentes));
        assertFalse(dosRecentes.hasExtra(AtalhosDeTeste.EXTRA_TESTE));
    }

    @Test
    public void oIntentComOsETesteDaUmAoOutroSemSeMisturar() {
        // Os dois extras são lidos cada um pelo seu: um não consome o outro.
        Intent ambos = pelaPortaInterna().putExtra(Notificacoes.EXTRA_OS, "12345")
            .putExtra(AtalhosDeTeste.EXTRA_TESTE, "emergencia");
        assertEquals("emergencia", MainActivity.testeDoAtalho(ambos));
        assertEquals("12345", MainActivity.osDoToque(ambos));
    }

    @Test
    public void oIntentReentregueDosRecentesNaoAbreAOs() {
        // Voltar ao app pelos recentes, depois de a tela ter sido destruída, o Android entrega de novo o Intent que a abriu.
        Intent dosRecentes = pelaPortaInterna().putExtra(Notificacoes.EXTRA_OS, "12345")
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        assertNull(MainActivity.osDoToque(dosRecentes));
        assertFalse(dosRecentes.hasExtra(Notificacoes.EXTRA_OS));
    }
}
