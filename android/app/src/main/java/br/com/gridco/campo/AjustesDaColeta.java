package br.com.gridco.campo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;

import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Os ajustes deste celular que a coleta da equipe precisa, no campo {@code ajustes} de cada lote de posições e do aviso
 * sem permissão: a versão do termo aceita (ou nulo), a localização (precisa, aproximada ou sem), o Permitir o tempo todo,
 * a bateria sem restrição, a notificação fixa à vista e, desde a 1.2.3, o Início automático do Xiaomi (ligado, desligado
 * ou nulo fora do Xiaomi e sem saber, {@link InicioAutomatico}). É o que o Piloto do Nexus mostra como Celular pronto, ou o
 * que falta, e o que o aviso do que falta diz ao técnico ({@link AvisoDoQueFalta}). Caso que criou: o dono, em 10/11/2026,
 * depois de a 1.2.1 pedir o tempo todo e a bateria: "tem como sabermos qual o técnico não aceitou tudo?". Só sim ou não e
 * a versão: nada da posição, e nada do aparelho que o Nexus já não saiba. O termo versão 5 diz isso na seção 2.
 */
final class AjustesDaColeta {
    private AjustesDaColeta() {
    }

    /** Os seis ajustes de agora. Não passa pela cifra; lança só se o sistema não responder (o envio segue sem eles). */
    static JSONObject ler(Context contexto, ChaveDaEquipe chave) {
        Context app = contexto.getApplicationContext();
        try {
            String termo = chave.versaoDoTermoAceito();
            Boolean inicioAutomatico = InicioAutomatico.estado(app);
            return new JSONObject()
                .put("termo", termo == null ? JSONObject.NULL : termo)
                .put("localizacao", localizacao(app))
                .put("tempoTodo", ServicoDaEquipe.temOTempoTodo(app))
                .put("bateriaSemRestricao", bateriaSemRestricao(app))
                .put("notificacao", ServicoDaEquipe.notificacaoAVista(app))
                .put("inicioAutomatico", inicioAutomatico == null ? JSONObject.NULL : inicioAutomatico);
        } catch (JSONException e) {
            throw new IllegalStateException("ajustes inválidos");
        }
    }

    /**
     * Os ajustes de um lote (o EnvioDePontos os lê pelo ServicoDaEquipe.Pecas a cada lote e a cada aviso sem permissão), e
     * a conferência do aviso do que falta com eles: cada envio do serviço é um dos pontos que a conferem, sem laço próprio,
     * e o aviso diz o mesmo que o Nexus vê.
     */
    static JSONObject doLote(Context contexto, ChaveDaEquipe chave, long agora) {
        JSONObject ajustes = ler(contexto, chave);
        AvisoDoQueFalta.conferirComOsAjustes(contexto, chave, ajustes, agora, true);
        return ajustes;
    }

    // A precisa vale para a coleta; a aproximada só dá a cidade, e o Nexus a mostra como o que falta.
    private static String localizacao(Context app) {
        if (dada(app, Manifest.permission.ACCESS_FINE_LOCATION)) return "precisa";
        if (dada(app, Manifest.permission.ACCESS_COARSE_LOCATION)) return "aproximada";
        return "sem";
    }

    private static boolean dada(Context app, String permissao) {
        return ContextCompat.checkSelfPermission(app, permissao) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * O app liberado da otimização de bateria (R15): o Android 12+ deixa religar o primeiro plano com o app no fundo, e o
     * HyperOS fecha menos o app. O PowerManager que não responde conta como liberado: sem saber, a casca não abre janela.
     */
    static boolean bateriaSemRestricao(Context contexto) {
        Context app = contexto.getApplicationContext();
        PowerManager energia = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return energia == null || energia.isIgnoringBatteryOptimizations(app.getPackageName());
    }
}
