package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.Looper;
import android.util.Xml;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.xmlpull.v1.XmlPullParser;

/**
 * O que só existe no build de depuração (src/debug): o extra de abertura que apaga o armazenamento da WebView, para a
 * prova da restauração no aparelho (spec 10.3). Este teste mora em src/testDebug porque o release nem tem a classe de
 * verdade (o src/release traz uma vazia, e o testes/depuracao.test.mjs confere que o caminho não existe lá).
 */
@RunWith(RobolectricTestRunner.class)
public class DepuracaoTest {

    private static Intent comExtra(boolean valor) {
        return new Intent().putExtra(Depuracao.APAGAR_WEBVIEW, valor);
    }

    @Test
    public void oExtraDaAberturaPedeOApagar() {
        assertEquals("gc_apagar_webview", Depuracao.APAGAR_WEBVIEW);
        assertTrue(Depuracao.pedeApagarWebView(comExtra(true), null));
    }

    @Test
    public void semOExtraOuComEleFalsoNaoApaga() {
        assertFalse(Depuracao.pedeApagarWebView(new Intent(), null));
        assertFalse(Depuracao.pedeApagarWebView(comExtra(false), null));
        assertFalse(Depuracao.pedeApagarWebView(new Intent().putExtra(Depuracao.APAGAR_WEBVIEW, "true"), null));
        assertFalse(Depuracao.pedeApagarWebView(null, null));
    }

    @Test
    public void aTelaRecriadaNaoApagaDeNovo() {
        // A tela recriada depois do apagar (e a do renderer que morreu) traz o estado salvo e o mesmo intent: apagar de
        // novo ali faria a restauração nunca terminar.
        assertFalse(Depuracao.pedeApagarWebView(comExtra(true), new Bundle()));
    }

    @Test
    public void apagaERecriaATelaDepoisDaEsperaSemTocarNoEspelho() {
        Context ctx = RuntimeEnvironment.getApplication();
        EspelhoSqlite espelho = new EspelhoSqlite(ctx, new CifraFalsa());
        espelho.gravarLs("gc_fech_draft", "rascunho");
        espelho.gravarLs("gc_campo_queue", "[{\"id\":1}]");
        espelho.receberPedaco("f_1", "p1-a-1", 0, 1, "image/jpeg", new byte[] {1, 2, 3});
        espelho.marcarSentinela();
        espelho.close();
        File banco = ctx.getDatabasePath(EspelhoSqlite.NOME_DO_BANCO);
        File pastaDoEspelho = new File(ctx.getFilesDir(), "espelho/idb");
        long tamanho = banco.length();

        AtomicInteger recriou = new AtomicInteger();
        Depuracao.apagarEDepois(recriou::incrementAndGet);

        ShadowLooper looper = org.robolectric.Shadows.shadowOf(Looper.getMainLooper());
        looper.idleFor(Duration.ofMillis(Depuracao.ESPERA_MS - 1));
        assertEquals("a tela só é recriada depois da espera", 0, recriou.get());
        looper.idleFor(Duration.ofMillis(1));
        assertEquals(1, recriou.get());

        assertTrue(banco.isFile());
        assertEquals(tamanho, banco.length());
        assertEquals(1, pastaDoEspelho.listFiles().length);
        EspelhoSqlite reaberto = new EspelhoSqlite(ctx, new CifraFalsa());
        assertTrue(reaberto.temEspelho());
        assertEquals("rascunho", reaberto.lsTudo().get("gc_fech_draft"));
        assertEquals("[{\"id\":1}]", reaberto.filaMaisNova());
        assertEquals(1, reaberto.idbChaves().size());
        reaberto.close();
    }

    // A prova sem o cabo (o celular pessoal não deixa usar o shell do adb): o atalho do ícone abre a tela com o extra. O
    // XML é lido como o Android o lê (o Intent.parseIntent é o que o ShortcutParser do sistema usa para o <intent>):
    // se o android:value="true" fosse compilado como texto, o getBooleanExtra daria falso e o atalho só abriria o App.
    @Test
    public void oAtalhoDoIconeAbreATelaComOPedidoDeApagar() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        Intent doAtalho = null;
        try (XmlResourceParser xml = ctx.getResources().getXml(R.xml.atalhos_depuracao)) {
            for (int evento = xml.next(); evento != XmlPullParser.END_DOCUMENT; evento = xml.next()) {
                if (evento == XmlPullParser.START_TAG && "intent".equals(xml.getName())) {
                    doAtalho = Intent.parseIntent(ctx.getResources(), xml, Xml.asAttributeSet(xml));
                    break;
                }
            }
        }
        assertTrue("o atalho tem o <intent>", doAtalho != null);
        assertEquals(Intent.ACTION_MAIN, doAtalho.getAction());
        assertEquals(new ComponentName(ctx.getPackageName(), MainActivity.class.getName()), doAtalho.getComponent());
        assertTrue("o extra chega como booleano verdadeiro", Depuracao.pedeApagarWebView(doAtalho, null));
    }

    @Test
    public void oApagarAvisaNumToast() {
        // Sem o cabo não há logcat: o toast é o que diz a quem faz a prova que o apagar rodou.
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        Depuracao.apagarWebView(activity);
        assertEquals(ctxTexto(R.string.depuracao_webview_apagada), ShadowToast.getTextOfLatestToast());
        // A tela que saiu antes da espera não é recriada.
        activity.finish();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Depuracao.ESPERA_MS));
        assertTrue(activity.isFinishing());
    }

    private static String ctxTexto(int id) {
        return RuntimeEnvironment.getApplication().getString(id);
    }
}
