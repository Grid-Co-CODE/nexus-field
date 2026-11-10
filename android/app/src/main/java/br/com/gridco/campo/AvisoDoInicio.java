package br.com.gridco.campo;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * O aviso das 6h (R12): todo dia às 6h na hora do celular, uma notificação que abre o Nexus Field, e a abertura liga a
 * coleta pelo caminho que já existe (a volta ao app da {@link EquipeNaCasca}). Caso que criou a regra: no piloto, no
 * celular do dono, a coleta só ligou às 07:46, quando ele abriu o app ("Não pode ficar esperando o usuário entrar no
 * aplicativo, deu 6 horas começou a coletar"). Entre ligar sozinho e avisar, ele escolheu avisar: "Só avisa, mas tem que
 * coletar cara!". Ligar sozinho às 6h coletaria em folga, sem o técnico abrir o app (o anti-objetivo): a localização "o
 * tempo todo", que voltou com a R15, só religa a coleta do dia já começado pela abertura.
 *
 * <ul>
 *   <li><b>Só para quem a coleta vale</b> ({@link #vale}): a chave da equipe guardada e o termo da versão de agora aceito.
 *       Sem a chave (fora do piloto, o Sair, o coleta:false, o 401) não há alarme, e o aparelho não acorda às 6h à toa:
 *       a 1.2.0 vai a todos os aparelhos.</li>
 *   <li><b>A decisão é da hora do alarme</b> ({@link #naHora}), com a chave e o termo lidos ali: o alarme que sobrou não
 *       mostra nada a quem saiu. Nada aparece com a coleta já ligada (o técnico abriu o app entre as 6h e o alarme
 *       atrasado) nem fora da janela das 6h às 22h (o alarme marcado em Brasília que toca às 5h em Mato Grosso: o toque
 *       não ligaria a coleta, e o próximo fica para as 6h de lá).</li>
 *   <li><b>Sem alarme exato</b> (nada de SCHEDULE_EXACT_ALARM nem USE_EXACT_ALARM, que o Android 12+ pede ao técnico ou à
 *       Play): o {@code setAndAllowWhileIdle} do RTC_WAKEUP, que acorda o celular também em repouso (o Doze), e que o
 *       Android pode atrasar uns minutos. O {@code setWindow} não vale aqui: no Doze ele espera a janela de manutenção, que
 *       com o celular parado a noite inteira pode demorar horas.</li>
 *   <li><b>Reagendado</b> no alarme (o do dia seguinte), a cada volta ao app com a chave e o termo de pé (o Forçar parada
 *       apaga os alarmes do app, e a primeira abertura os refaz), no boot, na atualização do app e no fuso novo
 *       ({@link ReceptorDoInicio}). <b>Cancelado</b> com o aviso da barra em todo caminho em que a chave sai
 *       ({@link ChaveDaEquipe#apagar}, {@link ChaveDaEquipe#apagarAChave}) e quando o termo deixa de valer. A coleta que
 *       liga tira o aviso da barra ({@link ServicoDaEquipe}).</li>
 *   <li>O aviso não coleta nada: quem está de folga o ignora, e sem abrir o app nada é coletado. Num canal próprio, "Início
 *       do dia", com o som e a importância padrão (para ser visto, sem aparecer por cima da tela) e sem categoria: o Não
 *       perturbe e o modo silencioso do técnico valem, como nos outros avisos. A notificação negada (Android 13+) ou o
 *       canal desligado: nada aparece, e a coleta também não ligaria (sem a notificação fixa à vista).</li>
 *   <li>Nada de chave, conta ou id de aparelho no log, nem o texto das exceções: só o nome da classe.</li>
 * </ul>
 */
final class AvisoDoInicio {

    /** O id do canal no Android (o nome está no strings.xml). */
    static final String CANAL_INICIO = "inicio_do_dia";
    /**
     * O id da notificação: depois do da notificação fixa da equipe (3.000.000, o ServicoDaEquipe), fora das faixas do
     * Notificacoes (1, o resumo; de 1.000.000 a 2.999.999, as emergências).
     */
    static final int ID_DA_NOTIFICACAO = 3_000_001;
    /** A ação do alarme das 6h: só o próprio app a manda (o PendingIntent explícito; o receptor não é exportado). */
    static final String ACAO_DO_ALARME = "br.com.gridco.campo.equipe.INICIO_DO_DIA";

    private static final String TAG = CascaPlugin.TAG;
    // O código do PendingIntent do alarme (um broadcast) e o do toque (uma activity): o tipo os separa no Android.
    private static final int CODIGO = ID_DA_NOTIFICACAO;

    private AvisoDoInicio() {
    }

    /**
     * As primeiras 06:00 depois de {@code agora}, no fuso dado (o do celular). As 6h em ponto já são as de amanhã: é o
     * alarme que tocou na hora e agenda o seguinte.
     */
    static long proximoInicio(long agora, TimeZone fuso) {
        Calendar c = Calendar.getInstance(fuso);
        c.setTimeInMillis(agora);
        c.set(Calendar.HOUR_OF_DAY, JanelaDaEquipe.HORA_DE_INICIO);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= agora) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** O aviso vale para este aparelho: a chave da equipe guardada e o termo da versão de agora aceito. Sem a cifra. */
    static boolean vale(ChaveDaEquipe chave) {
        return chave.temChaveGuardada() && chave.termoAceito();
    }

    /**
     * A regra da hora do alarme: o aviso aparece com a chave e o termo de pé, a coleta parada e a hora do celular dentro da
     * janela das 6h às 22h.
     */
    static boolean deveMostrar(boolean temChave, boolean termoAceito, boolean rodando, boolean dentroDaJanela) {
        return temChave && termoAceito && !rodando && dentroDaJanela;
    }

    /** Agenda o alarme das próximas 6h se o aviso vale; senão, o cancela (com o aviso da barra). Nunca lança. */
    static void agendarSeVale(Context contexto, ChaveDaEquipe chave, long agora) {
        if (vale(chave)) {
            agendar(contexto, agora);
        } else {
            cancelar(contexto);
        }
    }

    /**
     * Na hora do alarme: sem a chave ou sem o termo, nada aparece e o próximo não é agendado (o aparelho deixa de acordar
     * às 6h); com eles, o do dia seguinte é agendado e o aviso aparece se a coleta está parada e a hora está na janela.
     * True se o aviso apareceu.
     */
    static boolean naHora(Context contexto, ChaveDaEquipe chave, boolean rodando, long agora) {
        if (!vale(chave)) {
            Log.i(TAG, "o aviso das 6h: sem a chave da equipe ou sem o termo aceito, nada aparece");
            return false;
        }
        agendar(contexto, agora);
        if (!deveMostrar(true, true, rodando, JanelaDaEquipe.dentro(agora, TimeZone.getDefault()))) {
            Log.i(TAG, rodando ? "o aviso das 6h não aparece: a coleta da equipe já está ligada"
                : "o aviso das 6h não aparece: fora da janela das 6h às 22h na hora do celular");
            return false;
        }
        return mostrar(contexto, agora);
    }

    /** O alarme das próximas 6h, que toma o lugar do que já estiver agendado. Nunca lança. */
    static void agendar(Context contexto, long agora) {
        Context app = contexto.getApplicationContext();
        // O canal nasce com o aviso valendo: o técnico o vê nos ajustes (e pode desligá-lo) antes do primeiro aviso.
        CanaisDeNotificacao.criarDoInicio(app);
        try {
            AlarmManager alarmes = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (alarmes == null) return;
            alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, proximoInicio(agora, TimeZone.getDefault()),
                doAlarme(app, PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (RuntimeException e) {
            // O Android que recusou o alarme (o limite de alarmes de alguns aparelhos): a próxima volta ao app tenta.
            Log.w(TAG, "o aviso das 6h não foi agendado (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Cancela o alarme e tira o aviso da barra (a chave que saiu, o termo que deixou de valer). Nunca lança. */
    static void cancelar(Context contexto) {
        Context app = contexto.getApplicationContext();
        try {
            PendingIntent alarme = doAlarme(app, PendingIntent.FLAG_NO_CREATE);
            AlarmManager alarmes = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (alarme != null) {
                if (alarmes != null) alarmes.cancel(alarme);
                alarme.cancel();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "o alarme do aviso das 6h não foi cancelado (" + e.getClass().getSimpleName() + ")");
        }
        tirarDaBarra(app);
    }

    /** Tira o aviso da barra (a coleta que ligou, a chave que saiu). Nunca lança. */
    static void tirarDaBarra(Context contexto) {
        try {
            NotificationManagerCompat.from(contexto).cancel(ID_DA_NOTIFICACAO);
        } catch (RuntimeException e) {
            // o sistema que não respondeu agora: o aviso sai sozinho às 22h ou no toque
        }
    }

    /**
     * Mostra o aviso. False (e não mostra) sem a permissão de notificação (Android 13+), com as notificações do Nexus Field
     * desligadas, com o canal Início do dia desligado, ou se o sistema recusar. Nunca lança: roda no receptor do alarme.
     * O toque abre o Nexus Field pela porta interna, sem extra, como o resumo e a notificação fixa: a MainActivity que
     * volta à vista (o onResume do plugin) liga a coleta. Sai da barra no toque e, sem toque, às 22h, quando o toque já
     * não ligaria a coleta.
     */
    static boolean mostrar(Context contexto, long agora) {
        Context app = contexto.getApplicationContext();
        // A permissão aqui mesmo, antes do notify (o lint MissingPermission só a enxerga no mesmo método).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "o aviso das 6h não aparece: sem a permissão de notificação");
            return false;
        }
        if (!ligado(app)) {
            Log.i(TAG, "o aviso das 6h não aparece: as notificações do Nexus Field ou o canal Início do dia desligados");
            return false;
        }
        CanaisDeNotificacao.criarDoInicio(app);
        Intent abrir = new Intent()
            .setClassName(app, MainActivity.PORTA_INTERNA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent toque = PendingIntent.getActivity(app, CODIGO, abrir,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String texto = app.getString(R.string.notificacao_inicio_texto);
        long fim = JanelaDaEquipe.fimDaJanela(agora, TimeZone.getDefault());
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CANAL_INICIO)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle(app.getString(R.string.notificacao_inicio_titulo))
            .setContentText(texto)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(toque)
            .setAutoCancel(true)
            .setWhen(agora)
            .setShowWhen(true)
            // Android 7, sem canais: a prioridade e o som da própria notificação fazem o papel do canal, como no resumo.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_SOUND);
        if (fim > agora) b.setTimeoutAfter(fim - agora);
        try {
            NotificationManagerCompat.from(app).notify(ID_DA_NOTIFICACAO, b.build());
            Log.i(TAG, "o aviso das 6h apareceu");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "o aviso das 6h foi recusado pelo sistema (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    // As notificações do Nexus Field ligadas nos ajustes e o canal Início do dia não desligado (antes do primeiro
    // agendamento ele nem existe). Sem como perguntar ao sistema, tenta: o notify diz se ele recusou.
    private static boolean ligado(Context app) {
        try {
            NotificationManagerCompat notificacoes = NotificationManagerCompat.from(app);
            if (!notificacoes.areNotificationsEnabled()) return false;
            NotificationChannelCompat canal = notificacoes.getNotificationChannelCompat(CANAL_INICIO);
            return canal == null || canal.getImportance() != NotificationManagerCompat.IMPORTANCE_NONE;
        } catch (RuntimeException e) {
            return true;
        }
    }

    // O alarme: um broadcast explícito ao ReceptorDoInicio, com a ação do aviso. Imutável; o mesmo a cada agendamento (o
    // Android troca o alarme marcado com o mesmo PendingIntent, sem empilhar).
    private static PendingIntent doAlarme(Context app, int flag) {
        Intent intent = new Intent(app, ReceptorDoInicio.class).setAction(ACAO_DO_ALARME);
        return PendingIntent.getBroadcast(app, CODIGO, intent, PendingIntent.FLAG_IMMUTABLE | flag);
    }
}
