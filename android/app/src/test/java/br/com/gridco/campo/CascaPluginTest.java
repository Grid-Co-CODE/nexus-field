package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * A única regra do plugin que não depende da WebView: o endereço da navegação como vai ao log de depuração. O resto
 * (script, canal, recarga, recriação, Custom Tab) só se vê no aparelho. Os e-mails abaixo são fictícios.
 */
@RunWith(RobolectricTestRunner.class)
public class CascaPluginTest {

    private static String log(String url) {
        return CascaPlugin.navegacaoParaOLog(Uri.parse(url));
    }

    @Test
    public void userAgentTerminaComNexusFieldEAVersao() {
        // Os logs do servidor separam o app do navegador por este fim (o nome do aplicativo é Nexus Field desde 17/10).
        assertEquals("Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile Safari/537.36 NexusField/1.2.3",
            CascaPlugin.userAgent("Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile Safari/537.36", "1.2.3"));
        assertEquals("x NexusField/0.0.0-dev", CascaPlugin.userAgent("x", "0.0.0-dev"));
    }

    @Test
    public void semQueryNemFragmentoOEnderecoVaiInteiro() {
        assertEquals("https://gridco-campo-mw.azurewebsites.net/api/app",
            log("https://gridco-campo-mw.azurewebsites.net/api/app"));
        assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/authorize/",
            log("https://login.microsoftonline.com/common/oauth2/v2.0/authorize/"));
    }

    @Test
    public void queryEFragmentoViramComParametros() {
        // O retorno do login traz código e estado; o Fracttal, o token.
        String query = log("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=x&state=y");
        assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/authorize (com parâmetros)", query);
        String fragmento = log("https://gridco-campo-mw.azurewebsites.net/api/app#code=abc&state=y");
        assertEquals("https://gridco-campo-mw.azurewebsites.net/api/app (com parâmetros)", fragmento);
        for (String s : new String[] {query, fragmento}) {
            assertFalse(s, s.contains("state"));
            assertFalse(s, s.contains("code"));
        }
    }

    @Test
    public void emailNoCaminhoNoUsuarioOuCodificadoNaoVaiAoLog() {
        assertEquals("https://exemplo.com.br/fotos/<e-mail>/1",
            log("https://exemplo.com.br/fotos/tecnico.ficticio@exemplo.com.br/1"));
        assertEquals("https://exemplo.com.br/fotos/<e-mail>",
            log("https://exemplo.com.br/fotos/tecnico.ficticio%40exemplo.com.br"));
        // user@host: o getHost() não traz o usuário.
        assertEquals("https://exemplo.com.br/a", log("https://tecnico.ficticio@exemplo.com.br/a"));
        // Nome de chave do App e pedaço com caractere fora do comum (aspas, espaço) também não.
        assertEquals("https://exemplo.com.br/<chave>/…", log("https://exemplo.com.br/gc_ronda_x/'Fulano%20Silva'"));
        // Endereço opaco (mailto:, data:, javascript:): o que vem depois do esquema é dado.
        assertEquals("mailto:…", log("mailto:tecnico.ficticio@exemplo.com.br"));
        assertEquals("(sem endereço)", CascaPlugin.navegacaoParaOLog(null));
    }

    // A tela recriada (o renderer que morreu) faz um plugin novo. Com uma thread do espelho por plugin, a velha ainda
    // esvaziava a fila dela enquanto a nova começava, e mensagens da página velha caíam depois das da nova no mesmo
    // espelho. A thread é uma por processo, como o espelho e os disjuntores, e não é desligada quando a tela sai.
    @Test
    public void aThreadDoEspelhoEUmaPorProcessoESegueDepoisDaTelaSair() throws Exception {
        java.util.concurrent.ExecutorService a = CascaPlugin.threadDoEspelho();
        java.util.concurrent.ExecutorService b = CascaPlugin.threadDoEspelho();
        assertSame(a, b);
        assertFalse(a.isShutdown());
        java.util.concurrent.Future<String> nome = a.submit(() -> Thread.currentThread().getName());
        assertEquals("GcCasca-espelho", nome.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
}
