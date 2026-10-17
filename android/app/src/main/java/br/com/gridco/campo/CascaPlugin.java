package br.com.gridco.campo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AlertDialog;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.Plugin;
import com.getcapacitor.WebViewListener;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * O plugin da casca (spec, seção 6.2): liga o www/casca/inicio.js ao espelho nativo. Injeta o script de início antes
 * de qualquer script da página, abre o canal gcCasca só para a origem do App, liga os cookies de terceiros e o user
 * agent, recria a tela quando o renderer morre, limpa o histórico depois do login e abre os endereços de fora numa
 * Custom Tab.
 *
 * Ordem no Capacitor 8.5.3 (conferida no código dele): o construtor do Bridge roda initWebView, registerAllPlugins (o
 * PluginHandle chama o load() de cada plugin) e só então loadWebView, que faz o primeiro webView.loadUrl(appUrl). Por
 * isso o script registrado no load() já vale para a primeira carga.
 *
 * Threads: o canal entrega na thread de UI; o que mexe no espelho (gravar, montar retrato, ler o IndexedDB guardado)
 * vai para UMA thread só, a do espelho, na ordem em que a página mandou, e o que mexe na WebView volta para a de UI.
 */
@CapacitorPlugin(name = "Casca")
public class CascaPlugin extends Plugin {

    static final String TAG = "GcCasca";
    private static final String CANAL = "gcCasca";
    private static final String SCRIPT_DE_INICIO = "public/casca/inicio.js";
    private static final String CAMINHO_DO_APP = "/api/app";
    private static final String LOJA_DA_WEBVIEW = "market://details?id=com.google.android.webview";
    private static final String LOJA_DA_WEBVIEW_NA_WEB =
        "https://play.google.com/store/apps/details?id=com.google.android.webview";
    // Quanto a thread do espelho espera a de UI mandar cada pedaço do idbPedir. Passou disso, a de UI está travada, e
    // o envio para (a página tem o teto dela de 15 s).
    private static final long ESPERA_POR_MENSAGEM_S = 10;
    // O pedaço do caminho que vai ao log como está (ver navegacaoParaOLog).
    private static final Pattern PEDACO_COMUM = Pattern.compile("[A-Za-z0-9._~-]{1,64}");

    // Um espelho por processo: a recriação da tela (renderer que morreu) faz um plugin novo, e dois EspelhoSqlite no
    // mesmo banco perderiam o blob que chegava pela metade (a remessa em andamento é da instância).
    private static ArmazemEspelho espelhoDoProcesso;
    // E um disjuntor por processo: o renderer que morre na recarga com o retrato completo recria a tela (plugin e
    // Mensageiro novos), e a contagem dos completos tem de atravessar a recriação, senão o laço não para.
    private static final Mensageiro.Disjuntor DISJUNTOR_DO_PROCESSO = new Mensageiro.Disjuntor();

    private String origem;
    // Ligado quando a tela sai (onDestroy) ou o renderer morre: nada mais toca nesta WebView.
    private volatile boolean encerrado = false;
    private Handler ui;
    private ExecutorService threadDoEspelho;
    private Mensageiro mensageiro;
    private String inicioJs;
    private ScriptHandler script;                 // só na thread de UI
    private JavaScriptReplyProxy ponteDaMensagem; // só na thread do espelho, durante a mensagem que a trouxe

    @Override
    public void load() {
        WebView webView = bridge.getWebView();
        ui = new Handler(Looper.getMainLooper());

        // 1. A origem do App é o server.url do capacitor.config.json (só a origem, sem caminho nem barra no fim).
        origem = bridge.getServerUrl();

        // 2. Sem o script de início antes da página não há espelho, e sem o canal por regra de origem a ponte cairia no
        // addJavascriptInterface, exposto a todo frame. A WebView antiga para aqui, com o aviso, e a página do App não
        // roda: o MainActivity já tirou o server.url (o Bridge carrega a página local, e o App nem é pedido), e o
        // JavaScript sai antes do primeiro loadUrl (o initWebView do Bridge o liga ANTES dos plugins, e o loadWebView,
        // depois deles, não mexe nele). Se a troca do endereço faltar, nenhuma página roda script mesmo assim.
        if (!webViewTemOQueACascaPrecisa()) {
            webView.getSettings().setJavaScriptEnabled(false);
            avisarWebViewAntiga();
            return;
        }

        // 3. Sem isso, depois das 24 h do refresh token de SPA a renovação silenciosa do MSAL falha, e o técnico vê
        // "sessão expirou" todo dia.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // 4. Os logs do servidor separam o app do navegador por aqui, sem mudar o site.
        WebSettings ajustes = webView.getSettings();
        ajustes.setUserAgentString(ajustes.getUserAgentString() + " GridcoCampoApp/" + BuildConfig.VERSION_NAME);

        if (origem == null || origem.isEmpty()) {
            Log.e(TAG, "sem server.url: a casca fica sem espelho nesta partida");
        } else {
            ligarEspelho(webView); // 5 e 6
        }

        // 7. O ouvinte NÃO entra direto no load(): o Bridge.Builder.create() chama bridge.setWebViewListeners(...)
        // logo depois do construtor (que é onde o load() roda) e troca a lista inteira, levando junto o que um plugin
        // tivesse posto nela. Por isso o SystemBars do próprio Capacitor põe o dele no handleOnStart. Postado na thread
        // de UI, roda quando o onCreate termina, antes de qualquer retorno da WebView (que também chega por ela).
        ui.post(() -> {
            if (!encerrado) bridge.addWebViewListener(new OuvinteDaWebView());
        });
    }

    /**
     * A WebView tem o script de início antes da página e o canal por regra de origem? Sem os dois a casca não funciona.
     * O MainActivity pergunta antes de o Bridge nascer (para não abrir o App), e o load() de novo. A WebView que nem
     * carrega (o provedor desligado ou ausente lança aqui) conta como antiga; aí o BridgeActivity mostra a tela dele.
     */
    static boolean webViewTemOQueACascaPrecisa() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // 5. O canal gcCasca, só para a origem do App, e 6. o script de início com o retrato leve.
    // Os recursos da WebView que o lint pede (RequiresFeature) são conferidos no passo 2 do load(), e este método, o
    // trocarScript e o responder só rodam depois dele.
    @SuppressLint("RequiresFeature")
    private void ligarEspelho(WebView webView) {
        ArmazemEspelho armazem;
        try {
            armazem = espelho(getContext());
            inicioJs = lerScriptDeInicio();
        } catch (IOException | RuntimeException e) {
            // Sem canal nem script a página abre como no navegador: o inicio.js não roda e nada é embrulhado.
            Log.e(TAG, "a casca ficou sem espelho nesta partida (" + e.getClass().getSimpleName() + ")");
            return;
        }
        threadDoEspelho = Executors.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "GcCasca-espelho"));
        mensageiro = new Mensageiro(armazem, new AcoesDaCasca(), DISJUNTOR_DO_PROCESSO);

        try {
            WebViewCompat.addWebMessageListener(webView, CANAL, Collections.singleton(origem), this::aoReceber);
        } catch (RuntimeException e) {
            Log.e(TAG, "o canal da casca não abriu (" + e.getClass().getSimpleName() + ")");
            return;
        }

        JSONObject leve;
        try {
            leve = Retrato.leve(armazem);
        } catch (RuntimeException e) {
            Log.w(TAG, "o espelho não pôde ser lido; a primeira carga vai sem conferir a fila");
            leve = Mensageiro.semConferencia(armazem);
        }
        trocarScript(Retrato.script(leve, inicioJs));
    }

    private static synchronized ArmazemEspelho espelho(Context contexto) {
        if (espelhoDoProcesso == null) espelhoDoProcesso = new EspelhoSqlite(contexto, new CifraKeystore());
        return espelhoDoProcesso;
    }

    private String lerScriptDeInicio() throws IOException {
        try (InputStream in = getContext().getAssets().open(SCRIPT_DE_INICIO)) {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int lidos;
            while ((lidos = in.read(buffer)) != -1) saida.write(buffer, 0, lidos);
            return new String(saida.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // Só o frame principal da origem do App fala com o espelho: um iframe, ou a página de login da Microsoft, não.
    // O resto é ignorado em silêncio.
    private void aoReceber(WebView view, WebMessageCompat mensagem, Uri origemDaMensagem, boolean quadroPrincipal,
            JavaScriptReplyProxy ponte) {
        if (!quadroPrincipal || origemDaMensagem == null || !mesmaOrigem(origemDaMensagem.toString())) return;
        String dado;
        try {
            dado = mensagem.getData(); // o script só manda texto; o ArrayBuffer lança aqui
        } catch (RuntimeException e) {
            return;
        }
        if (dado == null || encerrado) return;
        try {
            threadDoEspelho.execute(() -> {
                ponteDaMensagem = ponte;
                try {
                    mensageiro.tratar(dado);
                } finally {
                    ponteDaMensagem = null;
                }
            });
        } catch (RejectedExecutionException e) {
            // A tela está saindo: o que a página mandou agora a próxima carga confere de novo.
        }
    }

    private boolean mesmaOrigem(String outra) {
        return semBarraNoFim(origem).equals(semBarraNoFim(outra));
    }

    private static String semBarraNoFim(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // Só na thread de UI. Tira o script atual antes de pôr o novo: dois inicio.js na mesma carga embrulhariam o
    // localStorage duas vezes. Vale da próxima carga em diante.
    @SuppressLint("RequiresFeature")
    private void trocarScript(String js) {
        WebView webView = bridge.getWebView();
        if (script != null) {
            script.remove();
            script = null;
        }
        try {
            script = WebViewCompat.addDocumentStartJavaScript(webView, js, Collections.singleton(origem));
        } catch (RuntimeException e) {
            Log.e(TAG, "o script de início não foi registrado (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void naUi(Runnable acao) {
        ui.post(() -> {
            if (!encerrado) acao.run();
        });
    }

    // Na thread do espelho: põe a mensagem na thread de UI, que é onde a ponte manda, e espera ela sair antes de ler o
    // pedaço seguinte. Assim só um pedaço (~1 MB entre bytes e base64) fica na memória por vez.
    @SuppressLint("RequiresFeature")
    private boolean responder(JavaScriptReplyProxy ponte, String mensagem) {
        if (encerrado) return false;
        CountDownLatch saiu = new CountDownLatch(1);
        AtomicBoolean mandou = new AtomicBoolean(false);
        ui.post(() -> {
            try {
                if (!encerrado) {
                    ponte.postMessage(mensagem);
                    mandou.set(true);
                }
            } catch (RuntimeException e) {
                // A página foi embora (navegou, recarregou ou o renderer morreu).
            } finally {
                saiu.countDown();
            }
        });
        try {
            return saiu.await(ESPERA_POR_MENSAGEM_S, TimeUnit.SECONDS) && mandou.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void abrirNaCustomTab(String url) {
        try {
            new CustomTabsIntent.Builder().build().launchUrl(getActivity(), Uri.parse(url));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "fora: nenhum navegador no aparelho"); // o endereço não vai ao log: tem parâmetros
        }
    }

    private void avisarWebViewAntiga() {
        Activity activity = getActivity();
        AlertDialog dialogo = new AlertDialog.Builder(activity)
            .setTitle(R.string.casca_webview_titulo)
            .setMessage(R.string.casca_webview_texto)
            .setCancelable(false)
            .setPositiveButton(R.string.casca_webview_abrir_loja, null)
            .create();
        // O botão abre a loja e o aviso fica: sem a WebView nova o App não funciona, e o texto pede para abrir de novo.
        dialogo.setOnShowListener(d -> dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> abrirLojaDaWebView(activity)));
        dialogo.show();
    }

    private static void abrirLojaDaWebView(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LOJA_DA_WEBVIEW)));
        } catch (ActivityNotFoundException semPlayStore) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LOJA_DA_WEBVIEW_NA_WEB)));
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "nem a Play Store nem um navegador para atualizar a WebView");
            }
        }
    }

    /**
     * Em debug, registra as navegações que passam pelo Capacitor (a lista do allowNavigation sai da prova no aparelho),
     * sem os parâmetros (ver {@link #navegacaoParaOLog}). Devolve null: a regra do Capacitor continua valendo.
     */
    @Override
    public Boolean shouldOverrideLoad(Uri url) {
        if (BuildConfig.DEBUG) Log.d(TAG, "navegação: " + navegacaoParaOLog(url));
        return null;
    }

    /**
     * O endereço como vai ao log: esquema, host e caminho, e " (com parâmetros)" no lugar da query e do fragmento (o
     * retorno do login traz código e estado; o do Fracttal, o token). No caminho, o pedaço com '@' vira "&lt;e-mail&gt;",
     * o que começa por gc_ ou msal. vira "&lt;chave&gt;", e o que tem caractere fora de [A-Za-z0-9._~-] vira "…". O
     * usuário de "usuario@host" não entra (o getHost() não o traz), e do endereço opaco (mailto:, data:, javascript:)
     * vai só o esquema.
     */
    static String navegacaoParaOLog(Uri url) {
        if (url == null) return "(sem endereço)";
        String esquema = url.getScheme() == null ? "?" : url.getScheme();
        if (url.isOpaque()) return esquema + ":…";
        StringBuilder s = new StringBuilder(esquema).append("://").append(url.getHost() == null ? "" : url.getHost());
        if (url.getPort() != -1) s.append(':').append(url.getPort());
        for (String pedaco : url.getPathSegments()) s.append('/').append(pedacoParaOLog(pedaco));
        String caminho = url.getPath();
        if (caminho != null && caminho.length() > 1 && caminho.endsWith("/")) s.append('/');
        if (url.getQuery() != null || url.getFragment() != null) s.append(" (com parâmetros)");
        return s.toString();
    }

    private static String pedacoParaOLog(String pedaco) {
        if (pedaco.indexOf('@') >= 0) return "<e-mail>";
        if (pedaco.startsWith("gc_") || pedaco.startsWith("msal.")) return "<chave>";
        return PEDACO_COMUM.matcher(pedaco).matches() ? pedaco : "…";
    }

    @Override
    protected void handleOnDestroy() {
        encerrado = true;
        // O que já está na fila ainda é gravado (é o que a página mandou); nada novo entra.
        if (threadDoEspelho != null) threadDoEspelho.shutdown();
        super.handleOnDestroy();
    }

    /** As ações do canal. Chegam na thread do espelho. */
    private final class AcoesDaCasca implements Mensageiro.Acoes {

        @Override
        public void precisoRetrato(JSONObject retrato) {
            String js = Retrato.script(retrato, inicioJs); // o retrato completo pode ter MB: montado fora da UI
            naUi(() -> {
                trocarScript(js);
                bridge.getWebView().reload();
            });
        }

        @Override
        public void restaurado(JSONObject retratoLeve) {
            String js = Retrato.script(retratoLeve, inicioJs);
            naUi(() -> trocarScript(js));
        }

        @Override
        public void idbPedir() {
            JavaScriptReplyProxy ponte = ponteDaMensagem;
            if (ponte != null) mensageiro.enviarIdb(mensagem -> responder(ponte, mensagem));
        }

        @Override
        public void abrirFora(String url) {
            naUi(() -> abrirNaCustomTab(url));
        }

        @Override
        public void erro(String onde, String msg) {
            Log.w(TAG, onde + ": " + msg);
        }
    }

    private final class OuvinteDaWebView extends WebViewListener {

        // Depois do login o "voltar" não pode cair nas páginas da Microsoft.
        @Override
        public void onPageLoaded(WebView webView) {
            String url = webView.getUrl();
            if (url != null && url.startsWith(origem + CAMINHO_DO_APP)) webView.clearHistory();
        }

        // A falta de memória num aparelho de 4 GB mata o renderer; sem tratar, o Android derruba o app inteiro. Esta
        // WebView não serve mais: a activity é recriada, e o onCreate novo infla outra WebView, monta outro Bridge
        // (este plugin carrega de novo, com script e canal na WebView nova) e carrega o App.
        @Override
        public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail detalhe) {
            encerrado = true;
            Log.w(TAG, "o renderer da WebView morreu; recriando a tela");
            getActivity().recreate();
            return true;
        }
    }
}
