package br.com.gridco.campo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Por onde o Android entrega o aviso das 6h ({@link AvisoDoInicio}, R12): o alarme das 6h, que mostra o aviso e agenda o do
 * dia seguinte, e os eventos que apagam ou deslocam o alarme, que só o reagendam: o celular que religou (o
 * BOOT_COMPLETED: o Android não guarda os alarmes de um boot para o outro), o app atualizado (o MY_PACKAGE_REPLACED) e o
 * fuso novo (o TIMEZONE_CHANGED: o alarme marcado às 6h de Brasília tocaria às 5h em Mato Grosso). Nenhum deles mostra o
 * aviso: só o alarme mostra.
 *
 * Não exportado (o manifesto): o alarme é um PendingIntent do próprio app, e os três eventos vêm do sistema, que alcança o
 * receptor não exportado (o "exported" só barra os outros apps). Assim, nenhum app de fora manda um "Bom dia" a qualquer
 * hora. A chave e o termo são lidos na hora, das peças do processo ({@link ServicoDaEquipe#pecas}), sem a cifra; a coleta
 * ligada é a do serviço deste processo. Nunca lança: o receptor que lança no boot derruba o app com o aviso de "parou" do
 * Android.
 */
public class ReceptorDoInicio extends BroadcastReceiver {

    private static final String TAG = CascaPlugin.TAG;

    @Override
    public void onReceive(Context contexto, Intent intent) {
        String acao = intent == null ? null : intent.getAction();
        try {
            Context app = contexto.getApplicationContext();
            if (AvisoDoInicio.ACAO_DO_ALARME.equals(acao)) {
                ServicoDaEquipe.Pecas pecas = ServicoDaEquipe.pecas(app);
                AvisoDoInicio.naHora(app, pecas.chave, ServicoDaEquipe.rodando(), pecas.relogio.getAsLong());
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(acao) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(acao)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(acao)) {
                ServicoDaEquipe.Pecas pecas = ServicoDaEquipe.pecas(app);
                AvisoDoInicio.agendarSeVale(app, pecas.chave, pecas.relogio.getAsLong());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "o aviso das 6h falhou (" + e.getClass().getSimpleName() + ")");
        }
    }
}
