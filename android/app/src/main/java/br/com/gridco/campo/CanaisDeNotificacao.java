package br.com.gridco.campo;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.util.Log;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Arrays;

/**
 * Os três canais das notificações (spec 2026-10-18), que o técnico vê e pode ajustar nos ajustes do Android:
 * - "Resumo do dia": importância padrão, com o som padrão de notificação (toca, sem aparecer por cima da tela);
 * - "Emergências": importância alta, com o som de ALARME padrão do aparelho e a vibração própria (aparece por cima e
 *   toca diferente do resumo: Ruling N5, 18/10/2026; sem arquivo de som nosso, e o técnico pode trocar o som nos
 *   ajustes do Android);
 * - "Emergências fora do expediente": importância baixa, sem som nem vibração. A emergência fora do dia útil e do
 *   expediente não é descartada: chega em silêncio (o plantão segue pelo telefone).
 * Quem escolhe o canal é o servidor, na mensagem.
 *
 * Criar de novo um canal que existe não muda o que o técnico ajustou (o Android só atualiza o nome): por isso
 * {@link #criar} pode ser chamado a cada abertura (o load() do CascaPlugin) e a cada mensagem (o MensagensDoFirebase,
 * porque a primeira mensagem pode chegar antes de o técnico abrir o app depois da atualização). No Android 7, que não tem
 * canais, não faz nada; a prioridade da notificação faz o papel (Notificacoes.mostrar).
 *
 * O SOM E A VIBRAÇÃO DE UM CANAL QUE JÁ EXISTE NÃO MUDAM: o Android guarda o canal como foi criado na primeira vez, e o
 * {@link #criar} seguinte só renomeia. Mudar o som da emergência depois de a versão chegar aos aparelhos exige um id de
 * canal novo (e apagar o velho), com o contrato do servidor mudando junto. Até a 1.1.0 nenhum aparelho criou estes
 * canais, então o som de alarme entra sem migração.
 */
final class CanaisDeNotificacao {

    // A vibração própria da emergência: três pulsos, diferente do toque curto de uma notificação comum.
    private static final long[] VIBRACAO_DA_EMERGENCIA = {0, 500, 250, 500, 250, 500};

    // O som da emergência toca como notificação de evento (o volume de notificação do aparelho, e não o do
    // despertador): é o alarme padrão como TOQUE, que o "Não perturbe" e o modo silencioso do técnico continuam valendo.
    private static final AudioAttributes AUDIO_DA_EMERGENCIA = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build();

    private CanaisDeNotificacao() {
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
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AUDIO_DA_EMERGENCIA)
                .setVibrationEnabled(true)
                .setVibrationPattern(VIBRACAO_DA_EMERGENCIA)
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
