package br.com.gridco.campo;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * O aviso do que falta liberar no celular (Task 14, o pedido do dono em 10/11/2026: "se algo ficar pendente tem que ter
 * notificação 'faltando tal coisa'"): a notificação <b>Falta liberar no celular</b>, com a lista do que a coleta ainda
 * precisa, e o toque que abre os ajustes do Nexus Field (no HyperOS, a mesma tela tem as permissões, a economia de bateria
 * e o Início automático).
 *
 * <ul>
 *   <li><b>Quando aparece</b>: com a chave guardada, o termo da versão de agora aceito e nenhum Sair por terminar (a regra
 *       do aviso das 6h, {@link AvisoDoInicio#vale}), a hora do celular nas 6h às 22h e faltando a localização precisa, o
 *       Permitir o tempo todo, a bateria sem restrição ou o Início automático (só desligado: o nulo, fora do Xiaomi ou sem
 *       saber, não conta). A falta da notificação não entra: sem ela, este aviso nem aparece.</li>
 *   <li><b>O que diz</b>: o que falta sai dos mesmos ajustes que vão ao Nexus em cada lote ({@link AjustesDaColeta}): o
 *       técnico e o Piloto veem a mesma lista.</li>
 *   <li><b>Onde se confere</b>, sem laço próprio: a volta ao app ({@link EquipeNaCasca}), cada envio do serviço
 *       ({@link AjustesDaColeta#doLote}) e o {@link ReceptorDoInicio} (o boot, a atualização e o alarme das 6h). Com um
 *       pedido do Android na frente do técnico (o termo, a localização, o tempo todo, a bateria, o Início automático), a
 *       volta ao app não o mostra: ele está liberando, e o aviso que tocasse no meio listaria o que a casca ainda vai
 *       pedir.</li>
 *   <li><b>Quando sai</b>: sozinho, quando nada falta; fora do piloto (a chave que sai, pelo mesmo caminho que cancela o
 *       aviso das 6h, {@link ChaveDaEquipe}), com o termo que deixou de valer e fora da janela (às 22h ele sai sozinho, pelo
 *       {@code setTimeoutAfter}). Não é fixo: o técnico pode limpá-lo, e ele volta na próxima conferência se ainda faltar.
 *       O toque não o tira: na volta dos ajustes, a conferência o atualiza em silêncio ou o tira.</li>
 *   <li><b>O som, uma vez por dia para cada lista</b>: o aviso que volta no mesmo dia (na hora do celular) com a mesma lista
 *       vai em silêncio ({@code setSilent}), também depois de o técnico limpá-lo; outra lista, ou outro dia, toca de novo.
 *       A marca é o dia e a lista do último aviso que tocou, e o {@link ChaveDaEquipe#apagar} a tira ({@link #esquecer}).
 *       Caso que criou a regra (a revisão da Task 14, 10/11/2026): o {@code setOnlyAlertOnce} só cala o aviso que ainda
 *       está na barra, e o limpo com algo faltando voltava tocando a cada envio, de 2 a 10 min com a coleta ligada.</li>
 *   <li>Um canal próprio, "Ajustes da localização" ({@link CanaisDeNotificacao#criarDosAjustes}), com a importância e o som
 *       padrão, que nasce com o primeiro aviso; o mesmo id, que não toca de novo enquanto está na barra
 *       ({@code setOnlyAlertOnce}).</li>
 *   <li>Nada de chave, conta ou id de aparelho no log, nem o texto das exceções: só o nome da classe.</li>
 * </ul>
 */
final class AvisoDoQueFalta {

    /** O id do canal no Android (o nome está no strings.xml). */
    static final String CANAL_AJUSTES = "ajustes_da_localizacao";
    /**
     * O id da notificação: depois do da notificação fixa da equipe (3.000.000) e do aviso das 6h (3.000.001), fora das
     * faixas do Notificacoes (1, o resumo; de 1.000.000 a 2.999.999, as emergências).
     */
    static final int ID_DA_NOTIFICACAO = 3_000_002;

    private static final String TAG = CascaPlugin.TAG;
    // O código do PendingIntent do toque (uma activity).
    private static final int CODIGO = ID_DA_NOTIFICACAO;
    // O dia (na hora do celular) e a lista do último aviso que tocou: o mesmo dia com a mesma lista vai em silêncio. Fora
    // do backup, como tudo (regras_dados.xml).
    private static final String PREFERENCIAS = "equipe_falta";
    private static final String CHAVE_TOCOU = "tocou";

    private AvisoDoQueFalta() {
    }

    /** O boot, a atualização e o alarme das 6h: confere com os ajustes de agora. Nunca lança. */
    static void conferir(Context contexto, ChaveDaEquipe chave, long agora) {
        conferir(contexto, chave, agora, true);
    }

    /**
     * Confere com os ajustes de agora: mostra (ou atualiza) o aviso se ele vale e algo falta, e o tira se não vale ou se
     * nada falta. Com {@code podeMostrar} falso (a volta ao app com um pedido na frente do técnico), só tira. Nunca lança.
     */
    static void conferir(Context contexto, ChaveDaEquipe chave, long agora, boolean podeMostrar) {
        Context app = contexto.getApplicationContext();
        if (!vale(chave, agora)) {
            tirar(app);
            return;
        }
        JSONObject ajustes;
        try {
            ajustes = AjustesDaColeta.ler(app, chave);
        } catch (RuntimeException e) {
            Log.w(TAG, "o aviso do que falta não foi conferido agora (" + e.getClass().getSimpleName() + ")");
            return;
        }
        conferirComOsAjustes(app, chave, ajustes, agora, podeMostrar);
    }

    /** O mesmo, com os ajustes já lidos (os do lote, {@link AjustesDaColeta#doLote}). Nunca lança. */
    static void conferirComOsAjustes(Context contexto, ChaveDaEquipe chave, JSONObject ajustes, long agora,
            boolean podeMostrar) {
        Context app = contexto.getApplicationContext();
        try {
            if (!vale(chave, agora)) {
                tirar(app);
                return;
            }
            List<String> faltas = oQueFalta(app, ajustes);
            if (faltas.isEmpty()) {
                tirar(app);
            } else if (podeMostrar) {
                mostrar(app, emLista(faltas), Boolean.FALSE.equals(ajustes.opt("inicioAutomatico")), agora);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "o aviso do que falta não foi conferido agora (" + e.getClass().getSimpleName() + ")");
        }
    }

    // O aviso vale para este aparelho agora: a regra do aviso das 6h (a chave, o termo de agora e nenhum Sair por
    // terminar, sem a cifra) e a hora do celular na janela.
    private static boolean vale(ChaveDaEquipe chave, long agora) {
        return AvisoDoInicio.vale(chave) && JanelaDaEquipe.dentro(agora, TimeZone.getDefault());
    }

    /**
     * O que falta, pelos ajustes de um lote, na ordem do texto: a localização precisa, o Permitir o tempo todo, a bateria
     * sem restrição e o Início automático (só o desligado; o nulo não conta). A notificação não entra.
     */
    static List<String> oQueFalta(Context contexto, JSONObject ajustes) {
        List<String> faltas = new ArrayList<>();
        if (!"precisa".equals(ajustes.optString("localizacao"))) {
            faltas.add(contexto.getString(R.string.notificacao_falta_localizacao));
        }
        if (!ajustes.optBoolean("tempoTodo", false)) faltas.add(contexto.getString(R.string.notificacao_falta_tempo_todo));
        if (!ajustes.optBoolean("bateriaSemRestricao", false)) {
            faltas.add(contexto.getString(R.string.notificacao_falta_bateria));
        }
        if (Boolean.FALSE.equals(ajustes.opt("inicioAutomatico"))) {
            faltas.add(contexto.getString(R.string.notificacao_falta_inicio_automatico));
        }
        return faltas;
    }


    // A casca só fala português (o strings.xml é tools:locale="pt"): a vírgula entre os itens e o "e" antes do último
    // ficam aqui, e os nomes no strings.xml.
    static String emLista(List<String> itens) {
        int n = itens.size();
        if (n == 1) return itens.get(0);
        return String.join(", ", itens.subList(0, n - 1)) + " e " + itens.get(n - 1);
    }

    /** A chave que sai ({@link ChaveDaEquipe#apagar}): esquece o dia e a lista do último aviso que tocou. Nunca lança. */
    static void esquecer(Context contexto) {
        try {
            preferencias(contexto).edit().remove(CHAVE_TOCOU).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "a marca do aviso do que falta não foi apagada (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Tira o aviso da barra (nada falta, a chave que saiu, fora da janela). Nunca lança. */
    static void tirar(Context contexto) {
        try {
            NotificationManagerCompat.from(contexto).cancel(ID_DA_NOTIFICACAO);
        } catch (RuntimeException e) {
            // o sistema que não respondeu agora: a próxima conferência tira
        }
    }

    // Mostra, ou atualiza em silêncio (o mesmo id e o setOnlyAlertOnce), o aviso, com o som só uma vez por dia para cada
    // lista (a marca). Sem a permissão de notificação (Android 13+) nada aparece: é a falta que este aviso não diz.
    private static void mostrar(Context app, String lista, boolean faltaOInicio, long agora) {
        // A permissão aqui mesmo, antes do notify (o lint MissingPermission só a enxerga no mesmo método).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        CanaisDeNotificacao.criarDosAjustes(app);
        Intent ajustes = destinoDoToque(app, faltaOInicio).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent toque = PendingIntent.getActivity(app, CODIGO, ajustes,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long fim = JanelaDaEquipe.fimDaJanela(agora, TimeZone.getDefault());
        String texto = app.getString(R.string.notificacao_falta_texto, lista);
        String marca = dia(agora) + "\n" + lista;
        SharedPreferences prefs = preferencias(app);
        boolean jaTocou = marca.equals(prefs.getString(CHAVE_TOCOU, null));
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CANAL_AJUSTES)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle(app.getString(R.string.notificacao_falta_titulo))
            .setContentText(texto)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(toque)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Android 7, sem canais: a prioridade e o som da própria notificação fazem o papel do canal, como no aviso
            // das 6h.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setSilent(jaTocou);
        if (fim > agora) b.setTimeoutAfter(fim - agora);
        try {
            NotificationManagerCompat.from(app).notify(ID_DA_NOTIFICACAO, b.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "o aviso do que falta foi recusado pelo sistema (" + e.getClass().getSimpleName() + ")");
            return;
        }
        if (!jaTocou) prefs.edit().putString(CHAVE_TOCOU, marca).apply();
    }

    // O dia do celular (o fuso dele), para a marca do som.
    // O toque leva aonde se resolve o que falta. Caso de 10/11/2026 (o celular do dono, HyperOS 2): os detalhes do app
    // não têm a chave do Início automático, nem em Outras permissões, e o técnico que tocava no aviso não a achava; ela só
    // está na tela do HyperOS. Com o Início automático na lista, o toque abre essa tela, se o aparelho a tiver (o
    // <queries> do manifesto deixa enxergá-la); senão, ou depois de ligado, os detalhes do app, onde ficam as
    // permissões e a bateria.
    static Intent destinoDoToque(Context app, boolean faltaOInicio) {
        if (faltaOInicio) {
            Intent hyperos = InicioAutomatico.aTelaDoHyperOS();
            try {
                app.getPackageManager().getActivityInfo(hyperos.getComponent(), 0);
                return hyperos;
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                // O aparelho sem a tela (outro HyperOS, ou a Segurança escondida): os detalhes do app.
            }
        }
        return InicioAutomatico.osDetalhesDoApp(app);
    }

    private static String dia(long agora) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.setTimeInMillis(agora);
        return String.format(Locale.ROOT, "%04d-%03d", c.get(Calendar.YEAR), c.get(Calendar.DAY_OF_YEAR));
    }

    private static SharedPreferences preferencias(Context contexto) {
        return contexto.getApplicationContext().getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE);
    }
}
