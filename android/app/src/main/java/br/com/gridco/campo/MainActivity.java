package br.com.gridco.campo;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

import java.util.Map;

/**
 * A tela única do App: a WebView do Capacitor com o plugin da casca e, na primeira abertura, o pedido da câmera e da
 * localização PRECISA (spec, seção 6.5). No Android 12+ o técnico pode escolher "aproximada", e aí a posição vem com
 * erro de quilômetros e o "esquentar" do GPS do App não termina: por isso o aviso próprio desse caso.
 */
public class MainActivity extends BridgeActivity {

    private static final String[] PERMISSOES = {
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    // Pelo registro da AndroidX, sem código de pedido próprio: o BridgeActivity repassa ao Capacitor e ao Cordova os
    // resultados com código, e um código nosso poderia cair num plugin.
    private final ActivityResultLauncher<String[]> pedido =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::aposOPedido);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Antes do super: é no super.onCreate que o Bridge nasce e carrega os plugins.
        registerPlugin(CascaPlugin.class);
        super.onCreate(savedInstanceState);
        // Com estado salvo é a mesma partida voltando (a tela recriada depois do renderer que morreu, ou o processo
        // restaurado): o técnico já viu o pedido nesta partida, e ele volta na próxima abertura se ainda faltar.
        if (savedInstanceState == null && !webViewAntiga()) primeiraAbertura();
    }

    private boolean webViewAntiga() {
        PluginHandle casca = bridge == null ? null : bridge.getPlugin("Casca");
        return casca != null && casca.getInstance() instanceof CascaPlugin
            && ((CascaPlugin) casca.getInstance()).webViewAntiga();
    }

    private boolean tem(String permissao) {
        return ContextCompat.checkSelfPermission(this, permissao) == PackageManager.PERMISSION_GRANTED;
    }

    private void primeiraAbertura() {
        if (tem(Manifest.permission.CAMERA) && tem(Manifest.permission.ACCESS_FINE_LOCATION)) return;
        new AlertDialog.Builder(this)
            .setTitle(R.string.casca_permissoes_titulo)
            .setMessage(R.string.casca_permissoes_texto)
            .setCancelable(false)
            .setPositiveButton(R.string.casca_permissoes_continuar, (d, qual) -> pedido.launch(PERMISSOES))
            .show();
    }

    // Confere o estado de agora, e não só o resultado: a câmera já dada antes não volta no mapa como pedida de novo.
    // Mapa vazio é o pedido interrompido (a tela saiu no meio): não há resposta do técnico a tratar.
    private void aposOPedido(Map<String, Boolean> resultado) {
        if (resultado == null || resultado.isEmpty()) return;
        boolean camera = tem(Manifest.permission.CAMERA);
        boolean precisa = tem(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean aproximada = tem(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!camera || (!precisa && !aproximada)) {
            // A câmera negada vence o caso da aproximada: o texto deste aviso já manda liberar a localização também.
            avisarComAjustes(R.string.casca_negadas_titulo, R.string.casca_negadas_texto);
        } else if (!precisa) {
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
