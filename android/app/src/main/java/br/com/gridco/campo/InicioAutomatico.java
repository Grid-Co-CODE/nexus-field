package br.com.gridco.campo;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * O Início automático do MIUI e do HyperOS (Xiaomi, Redmi e POCO): sem ele, o sistema não deixa o Nexus Field partir por
 * um aviso do sistema (o celular que religou, o app atualizado, o alarme), e a coleta do dia começado não volta sozinha
 * (R15). Caso que criou a peça (a prova da 1.2.2 no celular do dono, 10/11/2026): a atualização pelo cabo deu "Unable to
 * launch app ... MY_PACKAGE_REPLACED: process is not permitted to auto start", e a coleta só voltou na abertura; com o
 * Início automático ligado, ela voltou 9 s depois da instalação. O dono pediu: "claro, quando o cara instala já tem que
 * ter tudo pronto".
 *
 * <ul>
 *   <li>O estado ({@link #estado}) vai nos ajustes de cada lote ({@link AjustesDaColeta}), decide se a casca abre a tela
 *       do HyperOS depois da bateria ({@link EquipeNaCasca}) e entra no aviso do que falta ({@link AvisoDoQueFalta}).</li>
 *   <li>Não é do Android: o MIUI o guarda na operação {@link #OP_INICIO_AUTOMATICO} do AppOpsManager, lida pelo
 *       {@code checkOpNoThrow(int, int, String)}, que é escondido (por reflexão). O {@code adb shell appops get
 *       br.com.gridco.nexusfield 10008} mostra o mesmo ({@code allow} ligado, {@code ignore} desligado).</li>
 *   <li>Sem saber, nada: fora do Xiaomi, ou com a leitura que falhou, o estado é nulo, a casca não abre tela e o aviso
 *       não diz que falta (como o PowerManager que não responde conta como bateria liberada).</li>
 * </ul>
 */
final class InicioAutomatico {

    /** A operação do AppOpsManager em que o MIUI e o HyperOS guardam o Início automático (do MIUI, não do Android). */
    static final int OP_INICIO_AUTOMATICO = 10008;

    private static final String TAG = CascaPlugin.TAG;
    // Os fabricantes que mostram o Início automático. O Build.MANUFACTURER do Redmi e do POCO costuma ser "Xiaomi", mas há
    // aparelho que diz a submarca.
    private static final String[] DO_XIAOMI = {"xiaomi", "redmi", "poco"};

    /** De onde vem o modo da operação: o AppOpsManager por reflexão ({@link #peloAppOps}); no teste, um de mentira. */
    interface LeitorDoModo {
        int modo(Context app) throws Exception;
    }

    /**
     * No teste, o leitor do modo no lugar do AppOpsManager (null em produção): o do Robolectric não sabe da operação do
     * MIUI.
     */
    static LeitorDoModo leitorDeTeste;

    private InicioAutomatico() {
    }

    /** O Início automático deste celular: true ligado, false desligado, null fora do Xiaomi ou sem saber. Nunca lança. */
    static Boolean estado(Context contexto) {
        LeitorDoModo leitor = leitorDeTeste;
        return estado(Build.MANUFACTURER, contexto, leitor != null ? leitor : InicioAutomatico::peloAppOps);
    }

    /**
     * A regra: fora do Xiaomi, Redmi e POCO (sem diferenciar maiúscula), null sem ler nada; neles, o modo permitido é
     * ligado e qualquer outro é desligado. A leitura que falha (o método escondido que o Android barrou, o sistema que
     * recusa, o que o MIUI lançar) dá null e um log com o nome da classe, sem o texto (pode citar o uid e o pacote).
     */
    static Boolean estado(String fabricante, Context contexto, LeitorDoModo leitor) {
        if (!doXiaomi(fabricante)) return null;
        try {
            return leitor.modo(contexto.getApplicationContext()) == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable e) {
            // O Error (a memória que acabou) não é falha da leitura: segue.
            if (e instanceof Error) throw (Error) e;
            Log.w(TAG, "o Início automático não pôde ser lido (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    static boolean doXiaomi(String fabricante) {
        if (fabricante == null) return false;
        String f = fabricante.trim().toLowerCase(Locale.ROOT);
        for (String deles : DO_XIAOMI) if (deles.equals(f)) return true;
        return false;
    }

    // O checkOpNoThrow(int, int, String) é escondido (o público recebe o nome da operação, e a do MIUI não tem nome no
    // Android): por reflexão, para o próprio uid e pacote.
    private static int peloAppOps(Context app) throws Exception {
        AppOpsManager operacoes = (AppOpsManager) app.getSystemService(Context.APP_OPS_SERVICE);
        Method checar = AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class);
        return (Integer) checar.invoke(operacoes, OP_INICIO_AUTOMATICO, Process.myUid(), app.getPackageName());
    }

    /** A tela do Início automático do HyperOS, na Segurança do MIUI. */
    static Intent aTelaDoHyperOS() {
        return new Intent().setComponent(new ComponentName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"));
    }

    /** Os detalhes do Nexus Field nos ajustes: no HyperOS, as permissões, a economia de bateria e o Início automático. */
    static Intent osDetalhesDoApp(Context contexto) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + contexto.getPackageName()));
    }
}
