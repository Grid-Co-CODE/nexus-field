package br.com.gridco.campo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.CapConfig;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginHandle;

import java.util.Map;

/**
 * A tela única do App: a WebView do Capacitor com o plugin da casca e, na primeira abertura, o pedido da câmera e da
 * localização PRECISA (spec, seção 6.5). No Android 12+ o técnico pode escolher "aproximada", e aí a posição vem com
 * erro de quilômetros e o "esquentar" do GPS do App não termina: por isso o aviso próprio desse caso. No Android 13+ o
 * pedido leva também a notificação (spec das notificações, 4.2): na instalação nova junto com os outros dois, e, para
 * quem já tinha o app e só ganhou a notificação na atualização, depois de um diálogo próprio que explica o motivo. Com
 * a WebView antiga, nem o App nem o pedido: só o aviso do plugin. O toque numa emergência traz a OS no extra gc_os, e
 * a tela a leva ao plugin ({@link #osDoToque}, {@link CascaPlugin#abrirOS}): na partida (onCreate) ou com a tela já
 * aberta (onNewIntent). Do mesmo jeito, o atalho de teste dos avisos do ícone (só na conta Admin) traz o canal no extra
 * gc_teste ({@link #testeDoAtalho}, {@link CascaPlugin#testarAvisos}).
 */
public class MainActivity extends BridgeActivity {

    private static final String[] PERMISSOES = {
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    // A constante da permissão é do Android 13 e o minSdk é 24, então o lint avisa InlinedApi em cada uso. O aviso não
    // procede: o compilador copia o texto da constante para o nosso código, e ela só é PEDIDA no 13+ (devePedirNotificacao).
    // Num lugar só, para a supressão ficar à vista.
    @SuppressLint("InlinedApi")
    private static final String NOTIFICACAO = Manifest.permission.POST_NOTIFICATIONS;

    // Só pedida no Android 13+ (antes dele a notificação não é permissão). Vai por último: o sistema mostra uma tela
    // por grupo, e a da notificação é a que o técnico menos precisa para trabalhar.
    private static final String[] PERMISSOES_COM_NOTIFICACAO = {
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        NOTIFICACAO,
    };

    private static final String[] SO_NOTIFICACAO = { NOTIFICACAO };

    // No arquivo "push" do SharedPreferences, o mesmo do token do Firebase (fora do backup, como tudo). Quem negou a
    // notificação não é perguntado de novo; ligar depois é nos ajustes do Android.
    static final String CHAVE_JA_PEDIU_NOTIFICACAO = "jaPediuNotificacao";

    // Pelo registro da AndroidX, sem código de pedido próprio: o BridgeActivity repassa ao Capacitor e ao Cordova os
    // resultados com código, e um código nosso poderia cair num plugin.
    private final ActivityResultLauncher<String[]> pedido =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::aposOPedido);

    /** O aviso depois do pedido das permissões. */
    enum Aviso { NENHUM, NEGADAS, APROXIMADA }

    // Ligado enquanto o super.onCreate roda. O BridgeActivity.load() (Capacitor 8.5.3) chama onNewIntent(getIntent()) no
    // fim do onCreate dele, com ou sem estado salvo, e o intent da partida é tratado no onCreate daqui, uma vez só e só
    // sem estado salvo: sem esta marca, a tela recriada (o renderer que morreu) abriria a OS do toque de novo.
    private boolean criando = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Antes do super: é no super.onCreate que o Bridge nasce e carrega os plugins.
        registerPlugin(CascaPlugin.class);
        // A WebView antiga (sem o script de início ou sem o canal) não pode abrir o App: a página rodaria sem o
        // espelho, e a janela do Fracttal apagaria a tela sem nada guardado. O Bridge nasce sem server.url e carrega a
        // página local; o plugin mostra o aviso, que não fecha.
        boolean webViewAntiga = !CascaPlugin.webViewTemOQueACascaPrecisa();
        // Só no build de depuração (no release a Depuracao é vazia e devolve false): a prova da restauração abre sem o
        // App, apaga o armazenamento da WebView e recria a tela (docs/prova-no-aparelho.md).
        boolean apagarWebView = !webViewAntiga && Depuracao.pedeApagarWebView(getIntent(), savedInstanceState);
        if (webViewAntiga || apagarWebView) config = configSemOApp(this);
        criando = true;
        try {
            super.onCreate(savedInstanceState);
        } finally {
            criando = false;
        }
        if (apagarWebView) {
            Depuracao.apagarWebView(this);
            return;
        }
        // Com estado salvo é a mesma partida voltando (a tela recriada depois do renderer que morreu, ou o processo
        // restaurado): o técnico já viu o pedido nesta partida, e ele volta na próxima abertura se ainda faltar. O mesmo
        // vale para a OS do toque na emergência: o intent da tela recriada é o que a abriu, e a OS já foi aberta.
        if (savedInstanceState == null && !webViewAntiga) {
            primeiraAbertura();
            abrirOsDoToque(getIntent());
            pedirTesteDoAtalho(getIntent());
        }
    }

    /**
     * O toque numa notificação com a tela já aberta (singleTask entrega o Intent aqui, sem recriar a tela). O Capacitor
     * repassa aos plugins pelo super; a OS da emergência vai à página.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (criando) return; // o intent da partida é do onCreate
        setIntent(intent);
        abrirOsDoToque(intent);
        pedirTesteDoAtalho(intent);
    }

    /**
     * A OS que o toque na emergência pede para abrir (o extra gc_os que {@link Notificacoes#mostrar} põe no Intent), ou
     * null: sem o extra (o toque no resumo, o ícone do launcher), com o extra que não é um número de OS (a MainActivity
     * é exportada, e qualquer app pode mandar um Intent com ele) ou no Intent que o Android entrega de novo ao voltar
     * pelos recentes (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY: a OS do toque de ontem não é a de agora). O extra é consumido
     * na leitura, valendo ou não, para o mesmo Intent não abrir a OS duas vezes (o setIntent, a tela recriada).
     */
    static String osDoToque(Intent intent) {
        if (intent == null) return null;
        String os;
        try {
            os = intent.getStringExtra(Notificacoes.EXTRA_OS);
            intent.removeExtra(Notificacoes.EXTRA_OS);
        } catch (RuntimeException e) {
            return null; // o Bundle de um app de fora que não se lê
        }
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null;
        return Notificacoes.osValida(os) ? os : null;
    }

    /**
     * O canal do teste dos avisos que o atalho do ícone pede (o extra gc_teste que o {@link AtalhosDeTeste} põe no
     * Intent, só na conta Admin), ou null, com as mesmas regras da OS do toque ({@link #osDoToque}): sem o extra, com um
     * canal que não é um dos dois da emergência (a MainActivity é exportada), ou no Intent reentregue pelos recentes; o
     * extra é consumido na leitura, valendo ou não.
     */
    static String testeDoAtalho(Intent intent) {
        if (intent == null) return null;
        String canal;
        try {
            canal = intent.getStringExtra(AtalhosDeTeste.EXTRA_TESTE);
            intent.removeExtra(AtalhosDeTeste.EXTRA_TESTE);
        } catch (RuntimeException e) {
            return null; // o Bundle de um app de fora que não se lê
        }
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null;
        return AtalhosDeTeste.canalValido(canal) ? canal : null;
    }

    // Entrega a OS ao plugin da casca, que a leva à página quando o App estiver pronto. Sem plugin (o Bridge não nasceu)
    // ou com a WebView antiga, não há o que abrir.
    private void abrirOsDoToque(Intent intent) {
        String os = osDoToque(intent);
        CascaPlugin casca = os == null ? null : casca();
        if (casca != null) casca.abrirOS(os);
    }

    // O mesmo para o teste do atalho: o plugin o leva à página, e a página chama o servidor com o Bearer dela.
    private void pedirTesteDoAtalho(Intent intent) {
        String canal = testeDoAtalho(intent);
        CascaPlugin casca = canal == null ? null : casca();
        if (casca != null) casca.testarAvisos(canal);
    }

    // O plugin da casca desta tela, pelo nome, ou null (o Bridge ainda não nasceu).
    private CascaPlugin casca() {
        if (bridge == null) return null;
        PluginHandle handle = bridge.getPlugin(CascaPlugin.NOME);
        Plugin plugin = handle == null ? null : handle.getInstance();
        return plugin instanceof CascaPlugin ? (CascaPlugin) plugin : null;
    }

    /**
     * O config da partida com a WebView antiga, no lugar do capacitor.config.json: sem server.url o Bridge carrega
     * https://localhost (o index.html do www, sem script), e o endereço do App nunca é pedido. Sem página de erro (o
     * aviso é o diálogo) e sem allowNavigation (nem o login da Microsoft abre).
     */
    static CapConfig configSemOApp(Context contexto) {
        return new CapConfig.Builder(contexto).create();
    }

    /**
     * O aviso depois do pedido, pelo estado de agora (e não só pelo resultado: a câmera dada antes não volta no mapa).
     * A câmera negada vence o caso da aproximada: o texto das negadas já manda liberar a localização também.
     */
    static Aviso avisoDepoisDoPedido(boolean camera, boolean precisa, boolean aproximada) {
        if (!camera || (!precisa && !aproximada)) return Aviso.NEGADAS;
        return precisa ? Aviso.NENHUM : Aviso.APROXIMADA;
    }

    /**
     * Se a notificação deve ser pedida agora: só no Android 13+ (antes dele ela não é permissão), só enquanto falta e
     * uma vez só. Negada, não volta a perguntar: o aviso das tarefas do dia não vale um pedido a cada abertura.
     */
    static boolean devePedirNotificacao(int sdk, boolean concedida, boolean jaPediu) {
        return sdk >= Build.VERSION_CODES.TIRAMISU && !concedida && !jaPediu;
    }

    static boolean jaPediuNotificacao(Context contexto) {
        return contexto.getSharedPreferences(MensagensDoFirebase.PREFERENCIAS, Context.MODE_PRIVATE)
            .getBoolean(CHAVE_JA_PEDIU_NOTIFICACAO, false);
    }

    static void marcarNotificacaoPedida(Context contexto) {
        SharedPreferences push = contexto.getSharedPreferences(MensagensDoFirebase.PREFERENCIAS, Context.MODE_PRIVATE);
        push.edit().putBoolean(CHAVE_JA_PEDIU_NOTIFICACAO, true).apply();
    }

    private boolean tem(String permissao) {
        return ContextCompat.checkSelfPermission(this, permissao) == PackageManager.PERMISSION_GRANTED;
    }

    private void primeiraAbertura() {
        boolean pedeNotificacao = devePedirNotificacao(Build.VERSION.SDK_INT,
            tem(NOTIFICACAO), jaPediuNotificacao(this));
        if (tem(Manifest.permission.CAMERA) && tem(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Quem já tinha o app (atualização para a 1.1.0) já deu a câmera e a localização e não veria pedido nenhum:
            // o Android abriria a tela da notificação do nada. O diálogo de antes diz do que se trata.
            if (pedeNotificacao) avisarDasNotificacoes();
            return;
        }
        String[] pedidas = pedeNotificacao ? PERMISSOES_COM_NOTIFICACAO : PERMISSOES;
        new AlertDialog.Builder(this)
            .setTitle(R.string.casca_permissoes_titulo)
            .setMessage(R.string.casca_permissoes_texto)
            .setCancelable(false)
            .setPositiveButton(R.string.casca_permissoes_continuar, (d, qual) -> pedido.launch(pedidas))
            .show();
    }

    private void avisarDasNotificacoes() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.casca_avisos_titulo)
            .setMessage(R.string.casca_avisos_texto)
            .setCancelable(false)
            .setPositiveButton(R.string.casca_avisos_continuar, (d, qual) -> pedido.launch(SO_NOTIFICACAO))
            .show();
    }

    // Mapa vazio é o pedido interrompido (a tela saiu no meio): não há resposta do técnico a tratar.
    private void aposOPedido(Map<String, Boolean> resultado) {
        if (resultado == null || resultado.isEmpty()) return;
        // Respondido (dado ou negado) é pedido feito. A marca vai aqui, e não ao abrir o pedido: se o app morrer com a
        // tela do sistema aberta, o técnico ainda não respondeu e a próxima abertura pergunta de novo.
        if (resultado.containsKey(NOTIFICACAO)) marcarNotificacaoPedida(this);
        Aviso aviso = avisoDepoisDoPedido(tem(Manifest.permission.CAMERA),
            tem(Manifest.permission.ACCESS_FINE_LOCATION), tem(Manifest.permission.ACCESS_COARSE_LOCATION));
        if (aviso == Aviso.NEGADAS) {
            avisarComAjustes(R.string.casca_negadas_titulo, R.string.casca_negadas_texto);
        } else if (aviso == Aviso.APROXIMADA) {
            avisarComAjustes(R.string.casca_aproximada_titulo, R.string.casca_aproximada_texto);
        }
    }

    private void avisarComAjustes(int titulo, int texto) {
        new AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(texto)
            .setPositiveButton(R.string.casca_abrir_ajustes, (d, qual) -> abrirAjustesDoApp())
            .setNegativeButton(R.string.casca_agora_nao, null)
            .show();
    }

    private void abrirAjustesDoApp() {
        Intent ajustes = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(ajustes);
        } catch (ActivityNotFoundException e) {
            Log.w(CascaPlugin.TAG, "os ajustes do app não abriram");
        }
    }
}
