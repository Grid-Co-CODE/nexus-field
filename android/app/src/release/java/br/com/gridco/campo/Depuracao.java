package br.com.gridco.campo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * O release não tem o que o build de depuração tem (src/debug/.../Depuracao.java): nenhum extra de abertura faz nada
 * aqui, e o armazenamento da WebView nunca é apagado pela casca. Mesmos métodos, vazios, para o MainActivity ser um só.
 * O testes/depuracao.test.mjs confere que esta classe continua vazia.
 */
final class Depuracao {

    private Depuracao() {
    }

    static boolean pedeApagarWebView(Intent intent, Bundle salvo) {
        return false;
    }

    static void apagarWebView(Activity activity) {
    }
}
