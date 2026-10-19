package br.com.gridco.campo;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Arrays;

/**
 * Os três canais das notificações (spec 2026-10-18), que o técnico vê e pode ajustar nos ajustes do Android:
 * - "Resumo do dia": importância padrão, com o som padrão de notificação (toca, sem aparecer por cima da tela);
 * - "Emergências": importância alta, com o som de ALARME padrão do aparelho e a vibração própria (aparece por cima e
 *   toca diferente do resumo; sem arquivo de som nosso, e o técnico pode trocar o som nos ajustes do Android);
 * - "Emergências fora do expediente": importância baixa, sem som nem vibração. A emergência fora do dia útil e do
 *   expediente não é descartada: chega em silêncio (o plantão segue pelo telefone).
 * Quem escolhe o canal é o servidor, na mensagem; a casca só rebaixa ao silencioso a que chega fora do expediente
 * (Notificacoes.montar).
 *
 * NENHUM CANAL FURA O NÃO PERTURBE NEM O MODO SILENCIOSO (decisão do dono): nenhum pede o setBypassDnd, o som da
 * emergência toca como notificação de evento, no volume das notificações (não no do despertador), e nenhuma notificação
 * leva a categoria de alarme (Notificacoes.mostrar), que o filtro do Não perturbe deixaria passar. No modo vibrar, o
 * Android troca o som pela vibração.
 *
 * Criar de novo um canal que existe não muda o que o técnico ajustou (o Android só atualiza o nome): por isso
 * {@link #criar} pode ser chamado a cada abertura (o load() do CascaPlugin) e a cada mensagem (o MensagensDoFirebase,
 * porque a primeira mensagem pode chegar antes de o técnico abrir o app depois da atualização). No Android 7, que não tem
 * canais, não faz nada; a prioridade, o som e a vibração da notificação fazem o papel (Notificacoes.mostrar, com o
 * {@link #somDaEmergencia} e a {@link #vibracaoDaEmergencia} daqui).
 *
 * O SOM, A VIBRAÇÃO E A IMPORTÂNCIA DE UM CANAL QUE JÁ EXISTE NÃO MUDAM: o Android guarda o canal como foi criado na
 * primeira vez, e o {@link #criar} seguinte só renomeia. Mudar qualquer um deles depois de a versão chegar aos aparelhos
 * pede um id de canal NOVO no Android, e o id do canal no Android não precisa ser o texto do contrato: um mapa no
 * Notificacoes (o canal "emergencia" da mensagem do servidor indo ao canal "emergencia_2" do Android, com o
 * deleteNotificationChannel do velho) muda o som sem o servidor mudar nada. Na Release, a 1.1.0 é a primeira a criar estes
 * canais; um aparelho de prova que rodou um APK de depuração deste ramo de antes do som de alarme tem as "Emergências" com
 * o som padrão de notificação (só reinstalando, o que apaga o espelho).
 */
final class CanaisDeNotificacao {

    // A vibração própria da emergência: três pulsos, diferente do toque curto de uma notificação comum.
    private static final long[] VIBRACAO_DA_EMERGENCIA = {0, 500, 250, 500, 250, 500};

    // O som da emergência toca como notificação de evento (o volume de notificação do aparelho, e não o do despertador):
    // é o alarme padrão como TOQUE, e o modo silencioso e o Não perturbe do técnico continuam valendo.
    private static final AudioAttributes AUDIO_DA_EMERGENCIA = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build();

    private CanaisDeNotificacao() {
    }

    /** O som da emergência: o alarme padrão do aparelho (o do canal, e o da notificação no Android 7). */
    static Uri somDaEmergencia() {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    }

    /** A vibração da emergência (uma cópia: quem a recebe pode guardá-la). */
    static long[] vibracaoDaEmergencia() {
        return VIBRACAO_DA_EMERGENCIA.clone();
    }

    /** Cria (ou renomeia) os três canais. Nunca lança: roda na abertura do app e no serviço do Firebase. */
    static void criar(Context contexto) {
        try {
            NotificationChannelCompat resumo = new NotificationChannelCompat.Builder(
                    Notificacoes.CANAL_RESUMO, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(contexto.getString(R.string.notificacao_canal_resumo))
                .build();
            NotificationChannelCompat emergencia = new NotificationChannelCompat.Builder(
                    Notificacoes.CANAL_EMERGENCIA, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(contexto.getString(R.string.notificacao_canal_emergencia))
                .setSound(somDaEmergencia(), AUDIO_DA_EMERGENCIA)
                .setVibrationEnabled(true)
                .setVibrationPattern(vibracaoDaEmergencia())
                .build();
            NotificationChannelCompat silenciosa = new NotificationChannelCompat.Builder(
                    Notificacoes.CANAL_EMERGENCIA_SILENCIOSA, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(contexto.getString(R.string.notificacao_canal_emergencia_silenciosa))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .build();
            NotificationManagerCompat.from(contexto)
                .createNotificationChannelsCompat(Arrays.asList(resumo, emergencia, silenciosa));
        } catch (RuntimeException e) {
            Log.w(CascaPlugin.TAG, "os canais de notificação não foram criados: " + e.getClass().getSimpleName());
        }
    }
}
