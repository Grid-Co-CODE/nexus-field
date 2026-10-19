package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerWebSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowWebView;

/**
 * A ligação do toque na MainActivity de verdade (o Bridge do Capacitor nasce no Robolectric): o BridgeActivity.load()
 * do Capacitor 8.5.3 chama onNewIntent(getIntent()) dentro do próprio onCreate, com ou sem estado salvo, e o onNewIntent
 * daqui ignora essa chamada (a marca {@code criando}); o intent da partida é do onCreate, uma vez e só sem estado salvo.
 * Sem a marca, a tela recriada pelo renderer que morreu (ou o processo restaurado) leria o toque de novo.
 *
 * O que se vê aqui é o extra: quem lê o toque o consome ({@link MainActivity#extraDoToque}). No Robolectric a WebView
 * não tem o script de início antes da página, então a tela vai pelo caminho da WebView antiga (sem o App e sem ler o
 * toque no onCreate): o extra que sobra depois do onCreate prova que o onNewIntent de dentro dele não o leu.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = MainActivityNaTelaTest.SemServiceWorker.class)
public class MainActivityNaTelaTest {

    /**
     * O Robolectric não tem o provedor da WebView, e o Bridge do Capacitor pede o ServiceWorkerController ao nascer:
     * aqui ele recebe um que não faz nada (o service worker do App não roda no teste).
     */
    @Implements(ServiceWorkerController.class)
    public static class SemServiceWorker {
        @Implementation
        protected static ServiceWorkerController getInstance() {
            return new ServiceWorkerController() {
                @Override
                public ServiceWorkerWebSettings getServiceWorkerWebSettings() {
                    return null;
                }

                @Override
                public void setServiceWorkerClient(ServiceWorkerClient client) {
                }
            };
        }
    }

    @Before
    public void webViewDoAparelho() {
        // O Bridge confere a versão da WebView do aparelho (a mínima do Capacitor) antes de carregar.
        PackageInfo webView = new PackageInfo();
        webView.packageName = "com.google.android.webview";
        webView.versionName = "140.0.0.0";
        ShadowWebView.setCurrentWebViewPackage(webView);
    }

    private static Intent toque(String os) {
        Application app = RuntimeEnvironment.getApplication();
        return new Intent().setClassName(app, MainActivity.PORTA_INTERNA).putExtra(Notificacoes.EXTRA_OS, os);
    }

    @Test
    public void oOnNewIntentDeDentroDoOnCreateNaoLeOToque() {
        ActivityController<MainActivity> tela = Robolectric.buildActivity(MainActivity.class, toque("12345")).create();
        assertTrue("o Bridge nasceu", tela.get().getBridge() != null);
        assertTrue("o onNewIntent que o Capacitor chama no onCreate não consumiu o toque",
            tela.get().getIntent().hasExtra(Notificacoes.EXTRA_OS));
    }

    @Test
    public void oToqueComATelaAbertaChegaPeloOnNewIntentEELido() {
        ActivityController<MainActivity> tela = Robolectric.buildActivity(MainActivity.class, new Intent()).setup();
        Intent novo = toque("4521");
        tela.newIntent(novo);
        assertEquals("a tela passa a ter o intent novo", novo, tela.get().getIntent());
        assertFalse("e o toque foi lido (consumido)", novo.hasExtra(Notificacoes.EXTRA_OS));
    }

    @Test
    public void aTelaRecriadaNaoLeOToqueDeNovo() {
        ActivityController<MainActivity> tela = Robolectric.buildActivity(MainActivity.class, toque("12345")).setup();
        MainActivity antes = tela.get();
        tela.recreate();
        assertNotSame("recriada", antes, tela.get());
        assertTrue("com estado salvo, nem o onCreate nem o onNewIntent de dentro dele leem o toque",
            tela.get().getIntent().hasExtra(Notificacoes.EXTRA_OS));
    }
}
