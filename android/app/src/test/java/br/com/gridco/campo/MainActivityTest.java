package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.getcapacitor.CapConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * As regras da primeira abertura que não dependem da tela: o aviso depois do pedido das permissões e o endereço que a
 * WebView antiga abre. A tela (diálogos, o pedido de verdade) só se vê no aparelho.
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
}
