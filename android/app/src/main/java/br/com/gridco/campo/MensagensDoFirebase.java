package br.com.gridco.campo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Onde o Firebase Cloud Messaging entrega ao app (spec 2026-10-18), com o app aberto, no fundo ou fechado. O servidor
 * do App de Campo manda só dados; este serviço confere a mensagem e mostra a notificação (Notificacoes), no canal que o
 * servidor escolheu. Não exportado (AndroidManifest): só o Firebase, por dentro do app, chega aqui.
 *
 * O token do aparelho no Firebase mora no SharedPreferences "push" (fora do backup, como tudo: regras_dados.xml), e
 * quem o registra no servidor é o script de início, com o Bearer da página (o nativo nunca vê token da Microsoft). Nada
 * de token, título ou texto no log.
 */
public class MensagensDoFirebase extends FirebaseMessagingService {

    static final String PREFERENCIAS = "push";
    static final String CHAVE_TOKEN = "token";
    // A hora em que o token foi registrado no servidor (o script de início a grava pelo canal). Token novo sem ela é
    // token a registrar.
    static final String CHAVE_REGISTRADO_EM = "registradoEm";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage mensagem) {
        // Os canais aqui também: a primeira mensagem pode chegar antes de o técnico abrir o app depois da atualização,
        // e a notificação num canal que não existe não aparece no Android 8+.
        CanaisDeNotificacao.criar(this);
        Notificacoes.Montada m = Notificacoes.montar(mensagem.getData());
        if (m == null) {
            Log.w(CascaPlugin.TAG, "mensagem do Firebase fora do contrato (tipo ou canal): não mostrada");
            return;
        }
        Notificacoes.mostrar(this, m);
    }

    /**
     * O Firebase deu um token novo (a primeira abertura, o app reinstalado, o token renovado). O servidor só conhece o
     * velho: o novo é guardado e o registro anterior é esquecido, e o script de início registra de novo na próxima carga
     * da página com alguém logado.
     *
     * No firebase-messaging 25.1 (BOM 34.19.0) este método está marcado como obsoleto em favor do onRegistered, que é de
     * outro fluxo de registro, ligado só pelo meta-data firebase_messaging_installation_id_enabled. Sem ele (o caso
     * daqui), o SDK segue chamando o onNewToken (conferido no FirebaseMessaging.invokeOnRegistrationChanged da 25.1.3).
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onNewToken(@NonNull String token) {
        guardarTokenNovo(this, token);
    }

    static void guardarTokenNovo(Context contexto, String token) {
        SharedPreferences push = contexto.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE);
        // commit, e não apply: o serviço do Firebase pode ser encerrado logo depois, e o pedido de registro não pode se
        // perder. Roda na thread do Firebase, não na de UI.
        boolean gravou = push.edit().putString(CHAVE_TOKEN, token).remove(CHAVE_REGISTRADO_EM).commit();
        if (!gravou) Log.w(CascaPlugin.TAG, "o token novo do Firebase não foi gravado");
    }
}
