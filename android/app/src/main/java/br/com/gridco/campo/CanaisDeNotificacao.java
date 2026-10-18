package br.com.gridco.campo;

import android.content.Context;
import android.util.Log;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Arrays;

/**
 * Os três canais das notificações (spec 2026-10-18), que o técnico vê e pode ajustar nos ajustes do Android:
 * - "Resumo do dia": importância padrão (toca, sem aparecer por cima da tela);
 * - "Emergências": importância alta, com som e vibração (aparece por cima e toca);
 * - "Emergências fora do expediente": importância baixa, sem som nem vibração. A emergência fora do dia útil e do
 *   expediente não é descartada: chega em silêncio (o plantão segue pelo telefone).
 * Quem escolhe o canal é o servidor, na mensagem.
 *
 * Criar de novo um canal que existe não muda o que o técnico ajustou (o Android só atualiza o nome): por isso
 * {@link #criar} pode ser chamado a cada abertura (o load() do CascaPlugin) e a cada mensagem (o MensagensDoFirebase,
 * porque a primeira mensagem pode chegar antes de o técnico abrir o app depois da atualização). No Android 7, que não tem
 * canais, não faz nada; a prioridade da notificação faz o papel (Notificacoes.mostrar).
 */
final class CanaisDeNotificacao {

    // A vibração própria da emergência: três pulsos, diferente do toque curto de uma notificação comum.
    private static final long[] VIBRACAO_DA_EMERGENCIA = {0, 500, 250, 500, 250, 500};

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
