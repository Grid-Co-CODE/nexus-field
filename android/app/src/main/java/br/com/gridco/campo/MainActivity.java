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

import java.util.Map;

/**
 * A tela única do App: a WebView do Capacitor com o plugin da casca e, na primeira abertura, o pedido da câmera e da
 * localização PRECISA (spec, seção 6.5). No Android 12+ o técnico pode escolher "aproximada", e aí a posição vem com
 * erro de quilômetros e o "esquentar" do GPS do App não termina: por isso o aviso próprio desse caso. No Android 13+ o
 * pedido leva também a notificação (spec das notificações, 4.2): na instalação nova junto com os outros dois, e, para
 * quem já tinha o app e só ganhou a notificação na atualização, depois de um diálogo próprio que explica o motivo. Com
 * a WebView antiga, nem o App nem o pedido: só o aviso do plugin.
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
        super.onCreate(savedInstanceState);
        if (apagarWebView) {
            Depuracao.apagarWebView(this);
            return;
        }
        // Com estado salvo é a mesma partida voltando (a tela recriada depois do renderer que morreu, ou o processo
        // restaurado): o técnico já viu o pedido nesta partida, e ele volta na próxima abertura se ainda faltar.
        if (savedInstanceState == null && !webViewAntiga) primeiraAbertura();
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
