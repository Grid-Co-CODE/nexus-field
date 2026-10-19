package br.com.gridco.campo;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * A notificação que o servidor do App de Campo manda pelo Firebase (spec 2026-10-18): os resumos das 07:00 e das 13:00
 * e a emergência. A mensagem é só de dados, {tipo, canal, titulo, texto, os?}; quem escolhe o canal é o servidor, pela
 * hora do ENVIO (a emergência no expediente toca alto, fora dele vai ao canal silencioso). Aqui se confere, se corrige o
 * que a hora da CHEGADA mudou e se mostra.
 *
 * A montagem é pura ({@link #montar}) para o teste de JVM provar as regras que evitam o barulho que a spec chama de
 * fracasso: o resumo da tarde SUBSTITUI o da manhã (id fixo), a mesma emergência chegando duas vezes substitui a
 * primeira (id estável pela OS), o tipo fora do contrato não aparece, o canal que não é do tipo é trocado pelo do tipo
 * (a emergência nunca se perde) e a emergência que chega fora do expediente não toca. O texto é o do servidor, que só leva
 * contagens e o número da OS (nada de usina nem de pessoa: o Firebase passa pelo Google); a casca não acrescenta nada, e o
 * log diz só o canal e o motivo.
 */
final class Notificacoes {

    // O contrato com o servidor: os tipos e os ids dos canais (os nomes dos canais estão no strings.xml).
    static final String TIPO_RESUMO = "resumo";
    static final String TIPO_EMERGENCIA = "emergencia";
    static final String CANAL_RESUMO = "resumo";
    static final String CANAL_EMERGENCIA = "emergencia";
    static final String CANAL_EMERGENCIA_SILENCIOSA = "emergencia_silenciosa";

    /** O extra do toque na emergência: o número da OS, que a MainActivity leva à página. */
    static final String EXTRA_OS = "gc_os";

    /** O título da mensagem que veio sem título: o nome do aplicativo (o app_name do strings.xml). */
    static final String TITULO_PADRAO = "Nexus Field";

    /** O texto mais longo que vai à barra; o resto vira "…". O servidor manda uma linha curta: isto é só a guarda. */
    static final int TEXTO_MAXIMO = 240;

    /** Os dois resumos do dia têm o mesmo id: o das 13:00 toma o lugar do das 07:00, não empilha. */
    static final int ID_RESUMO = 1;
    /** A emergência com OS: um id por OS, de 1.000.000 a 1.999.999. */
    static final int ID_EMERGENCIA_BASE = 1_000_000;
    // A emergência sem OS válida (não devia vir, mas não se perde): o id sai do texto, de 2.000.000 a 2.999.999, para a
    // mesma mensagem repetida substituir e outra não apagar a primeira nem uma emergência com OS.
    private static final int ID_EMERGENCIA_SEM_OS_BASE = 2_000_000;
    private static final int FAIXA = 1_000_000;

    // O número da OS do Fracttal, só com os dígitos ASCII (o \d do Java sem UNICODE_CHARACTER_CLASS): ele vai a um
    // extra do Intent e depois a um script na página, e nada além de dígitos passa.
    private static final Pattern OS = Pattern.compile("\\d{1,12}");

    /** A notificação montada, pronta para mostrar. */
    static final class Montada {
        String canal;
        String titulo;
        String texto;
        String os;   // só na emergência, e só com o número válido; null no resto
        int id;
        long quando; // a hora que a barra mostra: a do envio (a da chegada, se a mensagem não trouxe)
        // O motivo de o canal ter sido trocado, para o log (um texto nosso, sem nada da mensagem); null sem troca.
        String aviso;
    }

    private Notificacoes() {
    }

    /**
     * Monta a notificação a partir dos dados da mensagem do Firebase, ou devolve null se ela não é para mostrar: o tipo
     * fora do contrato. Título vazio vira o nome do aplicativo; texto longo é cortado. O canal pode ser trocado, com o
     * motivo em {@link Montada#aviso}:
     * <ul>
     *   <li>o canal que não é do tipo: a emergência num canal que não é um dos dois dela vai ao silencioso, porque
     *       descartá-la perderia o aviso e o canal alto tocaria por um canal que ninguém escolheu; o resumo em outro canal
     *       vai ao dele, porque no da emergência tocaria alto às 7h;</li>
     *   <li>a emergência do canal alto que CHEGA fora do expediente ({@link Expediente#dentro}) vai ao silencioso. Caso
     *       que criou a regra: a OS sai às 16:50 de uma sexta no canal alto para um técnico sem sinal (ou com o celular
     *       descarregado), o FCM a segura por até 12 h, e o celular a recebe às 19:40 em casa, ou às 22:00 no modo dormir.
     *       O servidor escolhe pela hora do envio e não troca o canal depois; quem sabe a hora da chegada é a casca.</li>
     * </ul>
     *
     * @param enviadaEm a hora do envio (o getSentTime do Firebase), ou 0 se a mensagem não a trouxe
     * @param chegouEm  a hora da chegada, no relógio de parede do aparelho
     */
    static Montada montar(Map<String, String> dados, long enviadaEm, long chegouEm) {
        if (dados == null) return null;
        String tipo = dados.get("tipo");
        String canal = dados.get("canal");
        boolean resumo = TIPO_RESUMO.equals(tipo);
        if (!resumo && !TIPO_EMERGENCIA.equals(tipo)) return null;

        Montada m = new Montada();
        if (resumo) {
            m.canal = CANAL_RESUMO;
            if (!CANAL_RESUMO.equals(canal)) m.aviso = "resumo com o canal fora do contrato: vai ao canal do resumo";
        } else if (CANAL_EMERGENCIA_SILENCIOSA.equals(canal)) {
            m.canal = canal;
        } else if (CANAL_EMERGENCIA.equals(canal)) {
            m.canal = canal;
            if (!Expediente.dentro(chegouEm)) {
                m.canal = CANAL_EMERGENCIA_SILENCIOSA;
                m.aviso = "emergência que chegou fora do expediente: vai ao canal silencioso";
            }
        } else {
            m.canal = CANAL_EMERGENCIA_SILENCIOSA;
            m.aviso = "emergência com o canal fora do contrato: vai ao canal silencioso";
        }
        String titulo = aparado(dados.get("titulo"));
        m.titulo = titulo.isEmpty() ? TITULO_PADRAO : titulo;
        m.texto = cortado(aparado(dados.get("texto")));
        m.quando = enviadaEm > 0 ? enviadaEm : chegouEm;
        if (resumo) {
            m.id = ID_RESUMO;
        } else {
            String os = dados.get("os");
            if (osValida(os)) {
                m.os = os;
                m.id = idDaEmergencia(os);
            } else {
                m.id = ID_EMERGENCIA_SEM_OS_BASE + ((m.titulo + "\n" + m.texto).hashCode() & 0x7fffffff) % FAIXA;
            }
        }
        return m;
    }

    /** O número da OS: de 1 a 12 dígitos, nada mais (nem espaço, sinal, ponto ou dígito de outro alfabeto). */
    static boolean osValida(String os) {
        return os != null && OS.matcher(os).matches();
    }

    /**
     * O id da notificação da emergência desta OS (válida): o mesmo que a montagem dá, e que o osAberta da página cancela
     * quando a OS abre de fato.
     */
    static int idDaEmergencia(String os) {
        return ID_EMERGENCIA_BASE + (os.hashCode() & 0x7fffffff) % FAIXA;
    }

    private static String aparado(String s) {
        return s == null ? "" : s.trim();
    }

    // Corta em TEXTO_MAXIMO contando o "…", sem partir ao meio um caractere de dois pedaços do UTF-16.
    private static String cortado(String texto) {
        if (texto.length() <= TEXTO_MAXIMO) return texto;
        int fim = TEXTO_MAXIMO - 1;
        if (Character.isHighSurrogate(texto.charAt(fim - 1))) fim--;
        return texto.substring(0, fim) + "…";
    }

    /**
     * Mostra a notificação montada. Devolve false (e não mostra) sem a permissão de notificação (Android 13+, negada ou
     * ainda não pedida), com as notificações do app desligadas nos ajustes (o Android 12 para trás descarta em silêncio, e
     * o log dizia "mostrada"), com o canal dela desligado, ou se o sistema recusar; nunca lança, porque roda no serviço
     * do Firebase.
     *
     * O toque abre a tela pela PORTA INTERNA ({@link MainActivity#PORTA_INTERNA}, a activity-alias não exportada): só o
     * Intent que chega por ela leva a OS à página, e um app de fora alcança só a MainActivity, que ignora o extra. A tela
     * é singleTask: a que já está aberta recebe o Intent, sem recarregar a página. O código do pedido do PendingIntent é o
     * id da notificação: o sistema acha "igual" o PendingIntent que só muda nos extras, e com o mesmo código a segunda
     * emergência na barra abriria a OS da primeira.
     *
     * Nenhuma notificação leva categoria: o filtro do Não perturbe deixa passar a da categoria "alarm" (os alarmes vêm
     * liberados no modo prioridade) e a "reminder" onde os lembretes estiverem liberados. Decisão do dono: respeitar o
     * Não perturbe e o modo silencioso do técnico.
     *
     * A emergência não sai da barra no toque: ela fica até a página dizer que a OS abriu (o osAberta, que cancela pelo
     * {@link #idDaEmergencia}). Com o técnico ocupado a OS fica pendente na página, e o renderer que morre ou o app
     * fechado levariam junto a pendência; com a notificação na barra, um toque novo refaz tudo. O resumo sai no toque e,
     * sem toque, à meia-noite (em Brasília): o "Faltam 2 tarefas de hoje" de sexta não pode estar na barra na segunda.
     *
     * @param agora o relógio de parede (o fim do dia do resumo sai dele)
     */
    static boolean mostrar(Context contexto, Montada m, long agora) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(CascaPlugin.TAG, "notificação não mostrada: sem a permissão (" + m.canal + ")");
            return false;
        }
        NotificationManagerCompat gerente = NotificationManagerCompat.from(contexto);
        try {
            if (!gerente.areNotificationsEnabled()) {
                Log.i(CascaPlugin.TAG, "notificação não mostrada: as notificações do app estão desligadas (" + m.canal + ")");
                return false;
            }
            NotificationChannelCompat canal = gerente.getNotificationChannelCompat(m.canal);
            if (canal != null && canal.getImportance() == NotificationManagerCompat.IMPORTANCE_NONE) {
                Log.i(CascaPlugin.TAG, "notificação não mostrada: o canal está desligado nos ajustes (" + m.canal + ")");
                return false;
            }
        } catch (RuntimeException e) {
            // Sem como perguntar ao sistema, tenta mostrar: o notify abaixo diz se ele recusou.
        }
        boolean emergencia = !CANAL_RESUMO.equals(m.canal);

        Intent abrir = new Intent()
            .setClassName(contexto, MainActivity.PORTA_INTERNA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (m.os != null) abrir.putExtra(EXTRA_OS, m.os);
        PendingIntent toque = PendingIntent.getActivity(contexto, m.id, abrir,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(contexto, m.canal)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle(m.titulo)
            .setContentText(m.texto)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(m.texto))
            .setContentIntent(toque)
            .setWhen(m.quando)
            .setShowWhen(true)
            .setAutoCancel(!emergencia)
            // A mesma OS de novo, com a primeira ainda na barra, substitui sem tocar outra vez. No resumo, só quando o
            // que está na barra é o mesmo resumo (a entrega repetida): o das 13:00 toca mesmo com o das 07:00 lá.
            .setOnlyAlertOnce(emergencia || oMesmoNaBarra(contexto, m));
        if (!emergencia) b.setTimeoutAfter(Expediente.fimDoDia(agora) - agora);
        // Android 7 (sem canais): a prioridade, o som e a vibração da própria notificação fazem o papel do canal, com o
        // mesmo alarme e a mesma vibração do canal das emergências. Do Android 8 em diante o canal decide.
        if (CANAL_EMERGENCIA.equals(m.canal)) {
            b.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(CanaisDeNotificacao.somDaEmergencia())
                .setVibrate(CanaisDeNotificacao.vibracaoDaEmergencia());
        } else if (CANAL_EMERGENCIA_SILENCIOSA.equals(m.canal)) {
            b.setPriority(NotificationCompat.PRIORITY_LOW);
        } else {
            b.setPriority(NotificationCompat.PRIORITY_DEFAULT).setDefaults(NotificationCompat.DEFAULT_SOUND);
        }

        try {
            gerente.notify(m.id, b.build());
            Log.i(CascaPlugin.TAG, "notificação mostrada (" + m.canal + ")");
            return true;
        } catch (RuntimeException e) {
            Log.w(CascaPlugin.TAG, "notificação recusada pelo sistema (" + m.canal + "): " + e.getClass().getSimpleName());
            return false;
        }
    }

    // A notificação de mesmo id que já está na barra tem o mesmo título e o mesmo texto? Sem como saber, não.
    private static boolean oMesmoNaBarra(Context contexto, Montada m) {
        try {
            NotificationManager gerente = (NotificationManager) contexto.getSystemService(Context.NOTIFICATION_SERVICE);
            if (gerente == null) return false;
            for (StatusBarNotification ativa : gerente.getActiveNotifications()) {
                if (ativa.getId() != m.id || ativa.getTag() != null) continue;
                Notification n = ativa.getNotification();
                if (n == null || n.extras == null) return false;
                return m.titulo.equals(texto(n.extras.getCharSequence(Notification.EXTRA_TITLE)))
                    && m.texto.equals(texto(n.extras.getCharSequence(Notification.EXTRA_TEXT)));
            }
        } catch (RuntimeException e) {
            // O sistema que não responde: toca, como antes.
        }
        return false;
    }

    private static String texto(CharSequence s) {
        return s == null ? "" : s.toString();
    }
}
