package br.com.gridco.campo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Por onde o Android entrega o aviso das 6h ({@link AvisoDoInicio}, R12): o alarme das 6h, que mostra o aviso e agenda o do
 * dia seguinte, e os eventos que apagam ou deslocam o alarme, que só o reagendam: o celular que religou (o
 * BOOT_COMPLETED: o Android não guarda os alarmes de um boot para o outro), o app atualizado (o MY_PACKAGE_REPLACED), o
 * fuso novo (o TIMEZONE_CHANGED: o alarme marcado às 6h de Brasília tocaria às 5h em Mato Grosso) e o relógio mudado, à
 * mão ou pela correção automática da rede (o TIME_SET, que o Android chama de ACTION_TIME_CHANGED: o alarme marcado pelo relógio de antes tocaria na hora errada).
 * Nenhum deles mostra o aviso: só o alarme mostra.
 *
 * O boot e a atualização também religam a coleta da equipe do dia já começado (R15,
 * {@link ServicoDaEquipe#religarNoDiaComecado}): o caso de 10/11/2026, em que a atualização fechou o app e a coleta só
 * voltou quando o dono o abriu. Só com o "Permitir o tempo todo" e a abertura de hoje: sem abrir o app no dia, nada volta.
 * Os dois são exceções do Android 12+ para ligar o primeiro plano com o app no fundo. O fuso e o relógio não religam.
 *
 * Não exportado (o manifesto): o alarme é um PendingIntent do próprio app, e os quatro eventos vêm do sistema, que alcança o
 * receptor não exportado (o "exported" só barra os outros apps). Assim, nenhum app de fora manda um "Bom dia" a qualquer
 * hora. A chave e o termo são lidos na hora, das peças do processo ({@link ServicoDaEquipe#pecas}), sem a cifra; a coleta
 * ligada é a do serviço deste processo. Nunca lança: o receptor que lança no boot derruba o app com o aviso de "parou" do
 * Android. O aviso e a coleta falham cada um por si: o que falhar num não leva o outro.
 */
public class ReceptorDoInicio extends BroadcastReceiver {

    private static final String TAG = CascaPlugin.TAG;

    @Override
    public void onReceive(Context contexto, Intent intent) {
        String acao = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(acao);
        boolean atualizado = Intent.ACTION_MY_PACKAGE_REPLACED.equals(acao);
        try {
            Context app = contexto.getApplicationContext();
            if (AvisoDoInicio.ACAO_DO_ALARME.equals(acao)) {
                ServicoDaEquipe.Pecas pecas = ServicoDaEquipe.pecas(app);
                AvisoDoInicio.naHora(app, pecas.chave, ServicoDaEquipe.rodando(), pecas.relogio.getAsLong());
            } else if (boot || atualizado || Intent.ACTION_TIMEZONE_CHANGED.equals(acao)
                    || Intent.ACTION_TIME_CHANGED.equals(acao)) {
                ServicoDaEquipe.Pecas pecas = ServicoDaEquipe.pecas(app);
                AvisoDoInicio.agendarSeVale(app, pecas.chave, pecas.relogio.getAsLong());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "o aviso das 6h falhou (" + e.getClass().getSimpleName() + ")");
        }
        if (!boot && !atualizado) return;
        try {
            ServicoDaEquipe.religarNoDiaComecado(contexto.getApplicationContext(),
                boot ? "o celular religou" : "o Nexus Field foi atualizado");
        } catch (RuntimeException e) {
            Log.w(TAG, "a coleta da equipe não voltou sozinha (" + e.getClass().getSimpleName() + ")");
        }
    }
}
