package br.com.gridco.campo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebStorage;
import android.widget.Toast;

/**
 * SÓ NO BUILD DE DEPURAÇÃO. Este arquivo mora em src/debug e não entra no release: o src/release traz uma Depuracao
 * vazia, com os mesmos métodos, e o testes/depuracao.test.mjs confere que o caminho não existe fora daqui.
 *
 * A prova da restauração (spec 10.3: "apagar o armazenamento da WebView numa versão de teste e ver o espelho devolver a
 * fila e as fotos"). "Limpar armazenamento" nos ajustes do Android apaga o espelho junto, e sem isto a restauração ia
 * ao campo provada só em jsdom e Robolectric. Aberta com o extra booleano gc_apagar_webview (pelo atalho "Prova: apagar
 * a WebView", que o src/debug põe no ícone, ou pelo am start; o roteiro está no docs/prova-no-aparelho.md), a tela:
 *   1. não abre o App (o MainActivity troca o config pelo sem server.url, e a WebView fica na página local);
 *   2. apaga o armazenamento da WebView de todas as origens: o WebStorage.deleteAllData do Chromium leva o localStorage,
 *      o IndexedDB, o Web SQL e o File System (os cookies e o cache HTTP ficam). O espelho (o banco e a pasta do
 *      espelho nativo) não é tocado;
 *   3. avisa num toast (a prova sem o cabo não tem o logcat para dizer que o apagar rodou);
 *   4. espera 3 s (o deleteAllData não avisa quando termina) e recria a tela, que abre o App como sempre: o script de
 *      início vê o localStorage vazio sem a sentinela, e é a perda.
 */
final class Depuracao {

    static final String APAGAR_WEBVIEW = "gc_apagar_webview";
    /** Quanto a tela espera entre o apagar e abrir o App. */
    static final long ESPERA_MS = 3_000L;

    private static final String TAG = CascaPlugin.TAG;

    private Depuracao() {
    }

    /**
     * O pedido de apagar nesta abertura: o extra booleano verdadeiro, e só na abertura nova. A tela recriada (a do fim
     * deste fluxo, ou a do renderer que morreu) traz o estado salvo e o mesmo intent, e apagar de novo ali faria a
     * restauração nunca terminar.
     */
    static boolean pedeApagarWebView(Intent intent, Bundle salvo) {
        return salvo == null && intent != null && intent.getBooleanExtra(APAGAR_WEBVIEW, false);
    }

    /** Na thread de UI, depois do super.onCreate (a WebView do Bridge já existe). */
    static void apagarWebView(Activity activity) {
        apagarEDepois(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) activity.recreate();
        });
        Toast.makeText(activity.getApplicationContext(), R.string.depuracao_webview_apagada, Toast.LENGTH_LONG).show();
    }

    // Separado para o teste: o WebStorage do Robolectric não faz nada, e a recriação da tela é passada de fora.
    static void apagarEDepois(Runnable recriar) {
        WebStorage.getInstance().deleteAllData();
        Log.w(TAG, "depuração: armazenamento da WebView apagado; o espelho fica, e o App abre em " + ESPERA_MS + " ms");
        new Handler(Looper.getMainLooper()).postDelayed(recriar, ESPERA_MS);
    }
}
