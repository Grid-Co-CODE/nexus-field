package br.com.gridco.campo;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * A notificação que o servidor do App de Campo manda pelo Firebase (spec 2026-10-18): os resumos das 07:00 e das 13:00
 * e a emergência. A mensagem é só de dados, {tipo, canal, titulo, texto, os?}; quem escolhe o canal é o servidor (a
 * emergência no expediente toca alto, fora dele vai ao canal silencioso). Aqui só se confere e se mostra.
 *
 * A montagem é pura ({@link #montar}) para o teste de JVM provar as regras que evitam o barulho que a spec chama de
 * fracasso: o resumo da tarde SUBSTITUI o da manhã (id fixo), a mesma emergência chegando duas vezes substitui a
 * primeira (id estável pela OS), o tipo fora do contrato não aparece e o canal que não é do tipo é trocado pelo do tipo
 * (a emergência nunca se perde). O texto é o do servidor, que só leva contagens e o número da OS (nada de usina nem de
 * pessoa: o Firebase passa pelo Google); a casca não acrescenta nada, e o log diz só o canal e o motivo.
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
        // O motivo de o canal ter sido trocado, para o log (um texto nosso, sem nada da mensagem); null sem troca.
        String aviso;
    }

    private Notificacoes() {
    }

    /**
     * Monta a notificação a partir dos dados da mensagem do Firebase, ou devolve null se ela não é para mostrar: o tipo
     * fora do contrato. O canal que não é do tipo é trocado, com o motivo em {@link Montada#aviso} (Ruling N4,
     * 18/10/2026): a emergência num canal que não é um dos dois dela vai ao silencioso, porque descartá-la perderia o
     * aviso e o canal alto tocaria por um canal que ninguém escolheu; o resumo em outro canal vai ao dele, porque no da
     * emergência tocaria alto às 7h. Título vazio vira o nome do aplicativo; texto longo é cortado.
     */
    static Montada montar(Map<String, String> dados) {
        if (dados == null) return null;
        String tipo = dados.get("tipo");
        String canal = dados.get("canal");
        boolean resumo = TIPO_RESUMO.equals(tipo);
        if (!resumo && !TIPO_EMERGENCIA.equals(tipo)) return null;

        Montada m = new Montada();
        if (resumo) {
            m.canal = CANAL_RESUMO;
            if (!CANAL_RESUMO.equals(canal)) m.aviso = "resumo com o canal fora do contrato: vai ao canal do resumo";
        } else if (CANAL_EMERGENCIA.equals(canal) || CANAL_EMERGENCIA_SILENCIOSA.equals(canal)) {
            m.canal = canal;
        } else {
            m.canal = CANAL_EMERGENCIA_SILENCIOSA;
            m.aviso = "emergência com o canal fora do contrato: vai ao canal silencioso";
        }
        String titulo = aparado(dados.get("titulo"));
        m.titulo = titulo.isEmpty() ? TITULO_PADRAO : titulo;
        m.texto = cortado(aparado(dados.get("texto")));
        if (resumo) {
            m.id = ID_RESUMO;
        } else {
            String os = dados.get("os");
            if (osValida(os)) {
                m.os = os;
                m.id = ID_EMERGENCIA_BASE + (os.hashCode() & 0x7fffffff) % FAIXA;
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
     * ainda não pedida) ou se o sistema recusar; nunca lança, porque roda no serviço do Firebase.
     *
     * O toque abre a MainActivity (singleTask: a tela que já está aberta recebe o Intent, sem recarregar a página); na
     * emergência com OS, o Intent leva o número no extra gc_os. O código do pedido do PendingIntent é o id da
     * notificação: o sistema acha "igual" o PendingIntent que só muda nos extras, e com o mesmo código a segunda
     * emergência na barra abriria a OS da primeira.
     */
    static boolean mostrar(Context contexto, Montada m) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(CascaPlugin.TAG, "notificação não mostrada: sem a permissão (" + m.canal + ")");
            return false;
        }
        boolean emergencia = !CANAL_RESUMO.equals(m.canal);

        Intent abrir = new Intent(contexto, MainActivity.class)
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
            .setAutoCancel(true)
            .setCategory(emergencia ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_REMINDER)
            // A mesma OS de novo, com a primeira ainda na barra, substitui sem tocar outra vez. Só na emergência: o
            // resumo das 13:00 tem de tocar mesmo com o das 07:00 ainda lá.
            .setOnlyAlertOnce(emergencia);
        // Android 7 (sem canais): a prioridade e o som fazem o papel do canal.
        if (CANAL_EMERGENCIA.equals(m.canal)) {
            b.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);
        } else if (CANAL_EMERGENCIA_SILENCIOSA.equals(m.canal)) {
            b.setPriority(NotificationCompat.PRIORITY_LOW);
        } else {
            b.setPriority(NotificationCompat.PRIORITY_DEFAULT).setDefaults(NotificationCompat.DEFAULT_SOUND);
        }

        try {
            NotificationManagerCompat.from(contexto).notify(m.id, b.build());
            Log.i(CascaPlugin.TAG, "notificação mostrada (" + m.canal + ")");
            return true;
        } catch (RuntimeException e) {
            Log.w(CascaPlugin.TAG, "notificação recusada pelo sistema (" + m.canal + "): " + e.getClass().getSimpleName());
            return false;
        }
    }
}
