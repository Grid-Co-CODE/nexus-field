package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.CapConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * As regras da primeira abertura que não dependem da tela: o aviso depois do pedido das permissões, quando pedir a
 * notificação e o endereço que a WebView antiga abre. A tela (diálogos, o pedido de verdade) só se vê no aparelho.
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
}
