package br.com.gridco.campo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;
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
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;

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
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * O plugin da casca (spec, seção 6.2): liga o www/casca/inicio.js ao espelho nativo. Injeta o script de início antes
 * de qualquer script da página, abre o canal gcCasca só para a origem do App, liga os cookies de terceiros e o user
 * agent, recria a tela quando o renderer morre, limpa o histórico depois do login e abre os endereços de fora numa
 * Custom Tab. Também dispara a checagem de atualização da casca ({@link Atualizador}) ao abrir e a cada volta ao app,
 * trata o voltar do Android ({@link Voltar}), dá à página o token do Firebase para ela registrar o aparelho
 * ({@link Mensageiro#respostaDoPush}), leva à página a OS do toque na emergência ({@link #abrirOS}) e o teste dos avisos
 * do atalho do ícone, só para administrador ({@link #testarAvisos}, {@link AtalhosDeTeste}) e, uma vez por processo,
 * apaga as fotos velhas da reserva da câmera ({@link FaxinaDaCamera}).
 *
 * Ordem no Capacitor 8.5.3 (conferida no código dele): o construtor do Bridge roda initWebView, registerAllPlugins (o
 * PluginHandle chama o load() de cada plugin) e só então loadWebView, que faz o primeiro webView.loadUrl(appUrl). Por
 * isso o script registrado no load() já vale para a primeira carga.
 *
 * Threads: o canal entrega na thread de UI; o que mexe no espelho (gravar, montar retrato, ler o IndexedDB guardado)
 * vai para UMA thread só, a do espelho, na ordem em que a página mandou, e o que mexe na WebView volta para a de UI.
 */
@CapacitorPlugin(name = CascaPlugin.NOME)
public class CascaPlugin extends Plugin {

    /** O nome do plugin no Capacitor: a MainActivity acha a instância por ele (bridge.getPlugin) para entregar a OS do toque. */
    static final String NOME = "Casca";
    static final String TAG = "GcCasca";
    private static final String CANAL = "gcCasca";
    private static final String SCRIPT_DE_INICIO = "public/casca/inicio.js";
    private static final String CAMINHO_DO_APP = "/api/app";
    private static final String LOJA_DA_WEBVIEW = "market://details?id=com.google.android.webview";
    private static final String LOJA_DA_WEBVIEW_NA_WEB =
        "https://play.google.com/store/apps/details?id=com.google.android.webview";
    // Quanto a thread do espelho espera a de UI mandar cada pedaço do idbPedir. Passou disso, a de UI está travada, e
    // o envio para (a página tem o prazo dela: 20 s sem nada, 180 s no máximo).
    private static final long ESPERA_POR_MENSAGEM_S = 10;
    // O pedaço do caminho que vai ao log como está (ver navegacaoParaOLog).
    private static final Pattern PEDACO_COMUM = Pattern.compile("[A-Za-z0-9._~-]{1,64}");

    // Um espelho por processo: a recriação da tela (renderer que morreu) faz um plugin novo, e dois EspelhoSqlite no
    // mesmo banco perderiam o blob que chegava pela metade (a remessa em andamento é da instância).
    private static ArmazemEspelho espelhoDoProcesso;
    // E uma thread do espelho por processo, que não é desligada quando a tela sai: com uma por plugin, a velha ainda
    // esvaziava a fila dela enquanto a nova começava, e mensagens da página velha caíam depois das da nova no mesmo
    // espelho. O que a tela que saiu ainda tinha na fila é gravado (é o que a página mandou), na ordem.
    private static ExecutorService threadDoEspelhoDoProcesso;
    // As recriações por renderer morto: 3 em 60 s. Passado o teto, a tela recriada carrega a página de erro local em vez
    // do App (ERRO_NA_PROXIMA_CARGA), para o renderer que morre sempre na carga não virar uma tela que pisca para sempre.
    private static final TetoDeRecriacao RECRIACOES_DO_PROCESSO = new TetoDeRecriacao(SystemClock::elapsedRealtime);
    private static final AtomicBoolean ERRO_NA_PROXIMA_CARGA = new AtomicBoolean(false);
    // A faxina das fotos da reserva da câmera roda uma vez por processo (a tela recriada não repete).
    private static final AtomicBoolean FAXINA_FEITA = new AtomicBoolean(false);
    // E um disjuntor por processo: o renderer que morre na recarga com o retrato completo recria a tela (plugin e
    // Mensageiro novos), e a contagem dos completos tem de atravessar a recriação, senão o laço não para.
    private static final Mensageiro.Disjuntor DISJUNTOR_DO_PROCESSO = new Mensageiro.Disjuntor();
    // O das restaurações por 24 h, que mora no SharedPreferences (atravessa o processo) e também é um por processo (a
    // contagem dele vale quando o SharedPreferences não grava). Fora do backup, como tudo (regras_dados.xml).
    private static final String PREFERENCIAS_DO_ESPELHO = "casca_espelho";
    private static Mensageiro.DisjuntorDaPerda perdasDoProcesso;

    private String origem;
    // Ligado quando a tela sai (onDestroy) ou o renderer morre: nada mais toca nesta WebView.
    private volatile boolean encerrado = false;
    private Handler ui;
    private Mensageiro mensageiro;
    private String inicioJs;
    private Atualizador atualizador;              // null com a WebView antiga (o load() nem chega a criar)
    private AberturaDaOS aberturaDaOS;            // idem; só na thread de UI
    private TesteDosAvisos testeDosAvisos;        // idem
    private ScriptHandler script;                 // só na thread de UI
    private JavaScriptReplyProxy ponteDaMensagem; // só na thread do espelho, durante a mensagem que a trouxe

    @Override
    public void load() {
        WebView webView = bridge.getWebView();
        ui = new Handler(Looper.getMainLooper());

        // 1. A origem do App é o server.url do capacitor.config.json (só a origem, sem caminho nem barra no fim).
        origem = bridge.getServerUrl();

        // O voltar do Android, antes de tudo: vale também com a WebView antiga e sem o espelho. O OnBackPressedCallback
        // do androidx.activity cobre o voltar preditivo do targetSdk 36 (sobrescrever o onBackPressed não cobriria), e
        // sai junto com a tela (o dono do ciclo de vida é ela).
        ligarVoltar();
        faxinaDaCamera();
        // Os canais das notificações (resumo, emergência e a silenciosa) a cada abertura, também com a WebView antiga:
        // são do sistema, não da página, e criar de novo não desfaz o que o técnico ajustou (CanaisDeNotificacao).
        CanaisDeNotificacao.criar(getContext());

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
        ajustes.setUserAgentString(userAgent(ajustes.getUserAgentString(), BuildConfig.VERSION_NAME));

        // A OS do toque na emergência (MainActivity.osDoToque): só com a página do App; com a WebView antiga, que retorna
        // acima, ela nem abre. O que a página respondeu vai ao log só como "aberta", "pendente" (o técnico estava numa OS
        // ou numa ronda, e a página a abre quando ele terminar) ou "não aberta": nunca o número.
        aberturaDaOS = new AberturaDaOS(
            js -> {
                if (encerrado) return;
                bridge.getWebView().evaluateJavascript(js, resposta ->
                    Log.i(TAG, "a OS do toque na página: " + AberturaDaOS.resultado(resposta)));
            },
            (ms, tarefa) -> ui.postDelayed(tarefa, ms));
        // O teste dos avisos do atalho do ícone (só para administrador): a mesma espera da OS do toque. O resultado vem
        // depois, pelo canal (o pushTeste), e vira o toast; a página que nem aceitou o pedido (sem o script, false) dá o
        // toast de falha aqui, para o toque nunca ficar sem resposta.
        testeDosAvisos = new TesteDosAvisos(
            js -> {
                if (encerrado) return;
                bridge.getWebView().evaluateJavascript(js, resposta -> {
                    boolean aceito = TesteDosAvisos.aceito(resposta);
                    Log.i(TAG, "o teste dos avisos na página: " + (aceito ? "pedido" : "não pedido"));
                    if (!aceito && !encerrado) {
                        AtalhosDeTeste.avisar(getContext(), AtalhosDeTeste.Resultado.FALHOU, 0);
                    }
                });
            },
            (ms, tarefa) -> ui.postDelayed(tarefa, ms),
            SystemClock::elapsedRealtime);

        if (origem == null || origem.isEmpty()) {
            Log.e(TAG, "sem server.url: a casca fica sem espelho nesta partida");
        } else {
            ligarEspelho(webView); // 5 e 6
        }

        // A tela recriada depois do teto das recriações: o Bridge carrega o App logo depois deste load(), e esta carga,
        // postada, vem antes de qualquer resposta da rede e a substitui pela página de erro local. O "Tentar de novo"
        // dela abre o App outra vez, pela mão do técnico.
        if (ERRO_NA_PROXIMA_CARGA.getAndSet(false)) {
            ui.post(() -> {
                String erro = bridge.getErrorUrl();
                if (!encerrado && erro != null) bridge.getWebView().loadUrl(erro);
            });
        }

        // 7. O ouvinte NÃO entra direto no load(): o Bridge.Builder.create() chama bridge.setWebViewListeners(...)
        // logo depois do construtor (que é onde o load() roda) e troca a lista inteira, levando junto o que um plugin
        // tivesse posto nela. Por isso o SystemBars do próprio Capacitor põe o dele no handleOnStart. Postado na thread
        // de UI, roda quando o onCreate termina, antes de qualquer retorno da WebView (que também chega por ela).
        ui.post(() -> {
            if (!encerrado) bridge.addWebViewListener(new OuvinteDaWebView());
        });

        // 8. A atualização da casca (spec, seção 7): lê o versao.json da última Release, no máximo a cada 6 h, e oferece
        // a versão nova. Independe da página e do espelho; fica depois do aviso da WebView antiga (que retorna acima),
        // porque com ela o App nem abre. O resume seguinte (o Android chama o handleOnResume logo depois do onCreate)
        // cai no "um fluxo por vez" ou nas 6 h, e não repete.
        atualizador = new Atualizador(getActivity());
        atualizador.verificar();
    }

    /**
     * O user agent da WebView com o fim que identifica o aplicativo: " NexusField/<versionName>". Mudou com o nome do
     * aplicativo em 17/10/2026, antes da primeira Release; o site não depende dele.
     */
    static String userAgent(String doSistema, String versao) {
        return doSistema + " NexusField/" + versao;
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
        mensageiro = new Mensageiro(armazem, new AcoesDaCasca(), DISJUNTOR_DO_PROCESSO, perdas(getContext()));

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

    /** A thread do espelho, uma por processo (ver threadDoEspelhoDoProcesso). */
    static synchronized ExecutorService threadDoEspelho() {
        if (threadDoEspelhoDoProcesso == null) {
            threadDoEspelhoDoProcesso =
                Executors.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "GcCasca-espelho"));
        }
        return threadDoEspelhoDoProcesso;
    }

    private void ligarVoltar() {
        Voltar voltar = new Voltar(origem, new Voltar.Pagina() {
            @Override
            public String url() {
                return encerrado ? null : bridge.getWebView().getUrl();
            }

            @Override
            public void perguntar(String js, java.util.function.Consumer<String> resposta) {
                bridge.getWebView().evaluateJavascript(js, resposta::accept);
            }
        }, (ms, tarefa) -> ui.postDelayed(tarefa, ms), () -> getActivity().moveTaskToBack(true));
        getActivity().getOnBackPressedDispatcher().addCallback(getActivity(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                voltar.aoVoltar();
            }
        });
    }

    // Na thread do espelho (o getExternalFilesDir cria a pasta, e listar é disco): nada disso pode ir à de UI.
    private void faxinaDaCamera() {
        if (!FAXINA_FEITA.compareAndSet(false, true)) return;
        Context app = getContext().getApplicationContext();
        try {
            threadDoEspelho().execute(() -> {
                try {
                    int apagadas = FaxinaDaCamera.apagarVelhas(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        app.getCacheDir(), System.currentTimeMillis());
                    if (apagadas > 0) Log.i(TAG, apagadas + " foto(s) da reserva da câmera com mais de 48 h apagada(s)");
                } catch (RuntimeException e) {
                    Log.w(TAG, "a faxina das fotos da câmera falhou (" + e.getClass().getSimpleName() + ")");
                }
            });
        } catch (RejectedExecutionException e) {
            // nunca: a thread do processo não é desligada
        }
    }

    private static synchronized ArmazemEspelho espelho(Context contexto) {
        if (espelhoDoProcesso == null) espelhoDoProcesso = new EspelhoSqlite(contexto, new CifraKeystore());
        return espelhoDoProcesso;
    }

    private static synchronized Mensageiro.DisjuntorDaPerda perdas(Context contexto) {
        if (perdasDoProcesso == null) {
            SharedPreferences prefs = contexto.getApplicationContext()
                .getSharedPreferences(PREFERENCIAS_DO_ESPELHO, Context.MODE_PRIVATE);
            perdasDoProcesso = new Mensageiro.DisjuntorDaPerda(Mensageiro.DisjuntorDaPerda.naPreferencia(prefs),
                System::currentTimeMillis);
        }
        return perdasDoProcesso;
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
            threadDoEspelho().execute(() -> {
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

    /**
     * O token do aparelho no Firebase (o de cache, ou um novo), entregue na thread do espelho; null quando não sai. O
     * build sem o google-services.json (o de PR, e todos até o arquivo entrar no repositório) não inicia o Firebase (o
     * FirebaseInitProvider só avisa no log), e o getInstance lança; sem rede, o pedido de um token novo falha. Nos dois
     * casos a página recebe token null e não registra nada, e a próxima carga pede de novo.
     */
    private static void tokenDoFirebase(java.util.function.Consumer<String> entregar) {
        Task<String> pedido;
        try {
            pedido = FirebaseMessaging.getInstance().getToken();
        } catch (RuntimeException e) {
            entregar.accept(null); // já na thread do espelho (o pedido vem do Mensageiro)
            return;
        }
        pedido.addOnCompleteListener(threadDoEspelho(),
            tarefa -> entregar.accept(tarefa.isSuccessful() ? tarefa.getResult() : null));
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
     * O toque na emergência pediu esta OS (a MainActivity, na thread de UI, ao nascer ou ao receber o Intent). Devolve
     * false se o número não é de OS ou se a tela não abre o App (a WebView antiga); true se o pedido foi guardado ou
     * agendado, e a página o recebe quando estiver pronta ({@link AberturaDaOS}).
     */
    boolean abrirOS(String os) {
        AberturaDaOS abertura = aberturaDaOS;
        return abertura != null && abertura.pedir(os);
    }

    /**
     * O teste dos avisos que o atalho do ícone pediu (só para administrador, {@link AtalhosDeTeste}; a MainActivity, na
     * thread de UI, ao nascer ou ao receber o Intent). Devolve false se o canal não é um dos dois, se outro toque foi
     * aceito há menos de 10 s ou se a tela não abre o App (a WebView antiga); true se o pedido foi guardado ou agendado
     * ({@link TesteDosAvisos}).
     */
    boolean testarAvisos(String canal) {
        TesteDosAvisos teste = testeDosAvisos;
        return teste != null && teste.pedir(canal);
    }

    /**
     * Leva um script à página do App quando ela estiver pronta: a OS do toque na emergência ({@link AberturaDaOS}) e o
     * teste dos avisos do atalho do ícone ({@link TesteDosAvisos}), cada um com a sua instância (os dois na mesma
     * partida não se atropelam). O pedido chega da MainActivity assim que a tela nasce ou recebe o Intent, com a página
     * do App ainda carregando (o toque com o app fechado) ou já pronta (o toque com ele aberto). Aqui só se decide QUANDO
     * rodar o script:
     * <ul>
     *   <li>a cada página do App que termina de carregar começa uma folga de 1,5 s, porque o onPageLoaded é o fim do
     *       documento e o App monta a tela (login silencioso, tarefas) depois; só passada a folga a página está pronta;</li>
     *   <li>pedido com a página pronta vai logo (no ciclo seguinte da thread de UI); sem ela, o ÚLTIMO pedido fica
     *       guardado e vai quando a folga terminar (dois toques seguidos: vale o segundo);</li>
     *   <li>a página que recomeça (o login da Microsoft logo depois da carga) tira o "pronta", e a folga da carga velha
     *       deixa de valer: o pedido espera a carga seguinte do App. A entrega é uma só: entregue, não volta numa
     *       recarga.</li>
     * </ul>
     * Quem chama já validou o que vai no script (o texto vai direto ao evaluateJavascript). Só na thread de UI (a do
     * onPageStarted, do onPageLoaded, do postDelayed e do evaluateJavascript).
     */
    static class EntregaNaPagina {

        /** Quanto esperar, depois do fim do carregamento do App, para ele montar a tela antes do script. */
        static final long FOLGA_MS = 1_500L;

        /** A página do App, onde o script roda. */
        interface Pagina {
            void executar(String js);
        }

        /** O postDelayed da thread de UI. */
        interface Agenda {
            void depois(long ms, Runnable tarefa);
        }

        private final Pagina pagina;
        private final Agenda agenda;
        private boolean pronta = false;  // o App carregou e a folga passou
        private int carga = 0;           // conta as cargas: a folga de uma carga que já passou não vale
        private String pendente = null;  // o último pedido ainda não entregue

        EntregaNaPagina(Pagina pagina, Agenda agenda) {
            this.pagina = pagina;
            this.agenda = agenda;
        }

        /** Guarda o script como o último pedido e o entrega logo, se a página está pronta. */
        final void entregarQuandoPronta(String js) {
            pendente = js;
            if (pronta) agenda.depois(0, this::entregar);
        }

        /** Uma página começou a carregar (qualquer uma: o App, o login da Microsoft, a tela de erro). */
        final void comecouACarregar() {
            pronta = false;
            carga++;
        }

        /** A página do App terminou de carregar (as outras não chegam aqui): começa a folga. */
        final void carregou() {
            pronta = false;
            final int desta = ++carga;
            agenda.depois(FOLGA_MS, () -> {
                if (carga != desta) return; // outra página começou a carregar nesse meio-tempo
                pronta = true;
                entregar();
            });
        }

        private void entregar() {
            if (!pronta || pendente == null) return;
            String js = pendente;
            pendente = null;
            pagina.executar(js);
        }
    }

    /**
     * A OS que o toque na emergência pediu para abrir (spec das notificações, 4.2, "Ao tocar"), entregue à página do App
     * como diz a {@link EntregaNaPagina}: {@code window.__gcCascaAbrirOS('<os>')} (www/casca/inicio.js, seção 9) põe o
     * número no campo de busca da página, ou o deixa pendente até o técnico sair da OS ou da ronda em que está (a página
     * decide e espera; o nativo só entrega). Só OS que passa pela {@link Notificacoes#osValida} (1 a 12 dígitos ASCII)
     * entra, porque o texto vai direto a um script, e o script a confere de novo (o extra do Intent pode vir de qualquer
     * app). O número nunca vai ao log.
     */
    static final class AberturaDaOS extends EntregaNaPagina {

        AberturaDaOS(Pagina pagina, Agenda agenda) {
            super(pagina, agenda);
        }

        /** O script que a página roda. A OS tem de ter passado pela {@link Notificacoes#osValida}: só dígitos. */
        static String script(String os) {
            if (!Notificacoes.osValida(os)) throw new IllegalArgumentException("OS inválida");
            return "window.__gcCascaAbrirOS&&window.__gcCascaAbrirOS('" + os + "')";
        }

        /**
         * O que a página respondeu ao script, como vai ao log: "aberta", "pendente" ou "não aberta". O
         * {@code __gcCascaAbrirOS} devolve 'aberta' (a busca foi posta), 'pendente' (o técnico estava numa camada ou numa
         * ronda: nada foi fechado, e a página abre a OS sozinha quando ele ficar livre) ou false; o evaluateJavascript
         * entrega o JSON disso, com o texto entre aspas. O nativo não decide nada com a resposta: quem espera o técnico é
         * a página. Qualquer outra coisa (false, null da página sem o script) é "não aberta", e nada dela vai ao log.
         */
        static String resultado(String resposta) {
            if ("\"aberta\"".equals(resposta)) return "aberta";
            if ("\"pendente\"".equals(resposta)) return "pendente";
            return "não aberta";
        }

        /** Pede a OS. False (e nada acontece) se o número não é de OS. */
        boolean pedir(String os) {
            if (!Notificacoes.osValida(os)) return false;
            entregarQuandoPronta(script(os));
            return true;
        }
    }

    /**
     * O teste dos avisos que o atalho do ícone pede (só para administrador, Ruling N7, 18/10/2026; {@link AtalhosDeTeste}),
     * entregue à página do App como a OS do toque ({@link EntregaNaPagina}): {@code window.__gcCascaTestarAvisos('<canal>')}
     * (www/casca/inicio.js, seção 8) chama o servidor com o Bearer da página e devolve o resultado pelo canal
     * ({@code {t:'pushTeste'}}), que vira o toast. Só os dois canais da emergência entram ({@link AtalhosDeTeste#canalValido}:
     * o texto vai a um script, e o extra pode vir de qualquer app), e o script os confere de novo.
     *
     * UM TESTE POR TOQUE: o toque aceito há menos de {@value #INTERVALO_MS} ms segura o seguinte, seja qual for o canal.
     * Dois toques seguidos (o atalho que parece não ter respondido enquanto o app abre) mandariam dois pares de avisos a
     * cada aparelho da conta. O relógio é o que não volta com a troca de hora (elapsedRealtime, no plugin).
     */
    static final class TesteDosAvisos extends EntregaNaPagina {

        /** O intervalo mínimo entre dois toques aceitos. */
        static final long INTERVALO_MS = 10_000L;

        private final LongSupplier relogio;
        private boolean houveToque = false;
        private long ultimoToque;

        TesteDosAvisos(Pagina pagina, Agenda agenda, LongSupplier relogio) {
            super(pagina, agenda);
            this.relogio = relogio;
        }

        /** O script que a página roda. O canal tem de ser um dos dois: o texto vai direto ao evaluateJavascript. */
        static String script(String canal) {
            if (!AtalhosDeTeste.canalValido(canal)) throw new IllegalArgumentException("canal de teste inválido");
            return "window.__gcCascaTestarAvisos&&window.__gcCascaTestarAvisos('" + canal + "')";
        }

        /**
         * Se a página aceitou o pedido: o {@code __gcCascaTestarAvisos} devolve true quando a chamada saiu (o resultado
         * vem depois, pelo canal). O resto (o false, a página sem o script, que dá null) é o teste que não saiu.
         */
        static boolean aceito(String resposta) {
            return "true".equals(resposta);
        }

        /** Pede o teste. False (e nada acontece) com o canal fora dos dois ou com outro toque aceito há menos de 10 s. */
        boolean pedir(String canal) {
            if (!AtalhosDeTeste.canalValido(canal)) return false;
            long agora = relogio.getAsLong();
            if (houveToque && agora - ultimoToque < INTERVALO_MS) return false;
            houveToque = true;
            ultimoToque = agora;
            entregarQuandoPronta(script(canal));
            return true;
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

    // A volta ao app (a câmera, os ajustes, a Custom Tab do Fracttal): a checagem das 6 h e, depois de o técnico liberar
    // "Instalar apps desconhecidos", a retomada da instalação.
    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (atualizador != null) atualizador.verificar();
    }

    @Override
    protected void handleOnDestroy() {
        encerrado = true;
        if (atualizador != null) atualizador.encerrar(); // fecha o diálogo aberto; o download em curso termina sozinho
        // A thread do espelho é do processo e fica: o que já está na fila dela ainda é gravado (é o que a página
        // mandou), e o encerrado impede que algo novo desta tela entre (aoReceber) ou volte à WebView (naUi).
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
        public void idbRecusado() {
            JavaScriptReplyProxy ponte = ponteDaMensagem;
            if (ponte != null) mensageiro.recusarIdb(mensagem -> responder(ponte, mensagem));
        }

        @Override
        public void abrirFora(String url) {
            naUi(() -> abrirNaCustomTab(url));
        }

        // A resposta vai pela ponte da mensagem que pediu, quando o Firebase responder (na thread do espelho). A página
        // que já foi embora não recebe nada (o responder engole), e a próxima carga pede de novo.
        @Override
        public void pushPedirToken() {
            JavaScriptReplyProxy ponte = ponteDaMensagem;
            if (ponte == null) return;
            tokenDoFirebase(token -> responder(ponte, Mensageiro.respostaDoPush(token, BuildConfig.VERSION_NAME)));
        }

        // Na thread do espelho mesmo: o ShortcutManager é um serviço do sistema e não pede a de UI. Vale também com a
        // tela saindo (os atalhos são do aplicativo, não da tela).
        @Override
        public void pushAdmin(boolean admin) {
            Context app = getContext().getApplicationContext();
            if (admin) AtalhosDeTeste.publicar(app); else AtalhosDeTeste.tirar(app);
        }

        // O toast precisa da thread de UI. Ao log vai só o resultado e a contagem.
        @Override
        public void pushTeste(AtalhosDeTeste.Resultado resultado, int aparelhos) {
            Log.i(TAG, "o teste dos avisos: " + resultado
                + (resultado == AtalhosDeTeste.Resultado.ENVIADO ? " (" + aparelhos + " aparelho(s))" : ""));
            naUi(() -> AtalhosDeTeste.avisar(getContext(), resultado, aparelhos));
        }

        @Override
        public void erro(String onde, String msg) {
            Log.w(TAG, onde + ": " + msg);
        }
    }

    private final class OuvinteDaWebView extends WebViewListener {

        // Depois do login o "voltar" não pode cair nas páginas da Microsoft. E a OS do toque na emergência e o teste do
        // atalho esperam o App terminar de carregar: a folga deles começa aqui, só para a página do App (o login e a tela
        // de erro não contam).
        @Override
        public void onPageLoaded(WebView webView) {
            String url = webView.getUrl();
            if (url != null && url.startsWith(origem + CAMINHO_DO_APP)) webView.clearHistory();
            if (aberturaDaOS != null && !encerrado && Voltar.eDoApp(url, origem)) aberturaDaOS.carregou();
            if (testeDosAvisos != null && !encerrado && Voltar.eDoApp(url, origem)) testeDosAvisos.carregou();
        }

        // Qualquer página que começa a carregar tira o "pronta" da OS do toque e do teste: o App que estava pronto está
        // sendo trocado.
        @Override
        public void onPageStarted(WebView webView) {
            if (aberturaDaOS != null && !encerrado) aberturaDaOS.comecouACarregar();
            if (testeDosAvisos != null && !encerrado) testeDosAvisos.comecouACarregar();
        }

        // A falta de memória num aparelho de 4 GB mata o renderer; sem tratar, o Android derruba o app inteiro. Esta
        // WebView não serve mais: a activity é recriada, e o onCreate novo infla outra WebView, monta outro Bridge
        // (este plugin carrega de novo, com script e canal na WebView nova) e carrega o App. Até 3 vezes em 60 s; daí
        // em diante a tela recriada mostra a página de erro local (ver ERRO_NA_PROXIMA_CARGA).
        @Override
        public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail detalhe) {
            encerrado = true;
            if (RECRIACOES_DO_PROCESSO.cabe()) {
                Log.w(TAG, "o renderer da WebView morreu; recriando a tela");
            } else {
                ERRO_NA_PROXIMA_CARGA.set(true);
                Log.w(TAG, "o renderer da WebView morreu de novo (teto de recriações); a tela recriada mostra o erro");
            }
            getActivity().recreate();
            return true;
        }
    }
}
