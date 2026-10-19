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
 * servidor escolheu (ou no silencioso, se ela chegou fora do expediente). Não exportado (AndroidManifest): só o Firebase,
 * por dentro do app, chega aqui. Nada de token, título, texto ou OS no log.
 *
 * O SharedPreferences "push" (fora do backup, como tudo: regras_dados.xml; ninguém dá clear() nele, porque a marca
 * jaPediuNotificacao da MainActivity mora ali) guarda:
 * <ul>
 *   <li>{@value #CHAVE_TOKEN}: o último token que o Firebase deu (o onNewToken e o getToken do pedido da página). Quem
 *       registra o aparelho no servidor é a página, com o Bearer dela (o nativo nunca vê token da Microsoft); a chave
 *       serve para COMPARAR: na volta ao app, o token guardado que não é o que a página recebeu nesta carga vai a ela de
 *       novo ({@link PushNaCasca#voltouAoApp}), e ela registra o novo;</li>
 *   <li>{@value #CHAVE_SEM_CONTA}: a marca do Sair. O esquecer do Sair é um pedido só, e sem rede, com o servidor fora ou
 *       com a sessão vencida ele não chega: o servidor seguia mandando os avisos de quem saiu ao aparelho, que muitas
 *       vezes passa à mão de outro técnico. Com a marca, nada é mostrado até o registro seguinte (o pushRegistrado da
 *       página a solta). Ela não pula registro nenhum: só cala a exibição entre o Sair e a próxima conta;</li>
 *   <li>{@value #CHAVE_APAGAR_TOKEN}: o apagamento do token no Firebase, do Sair, que ainda não deu certo (sem rede). O
 *       próximo pedido do token da página o repete antes de devolver o token, enquanto a marca do Sair estiver de pé
 *       (PushNaCasca); sai quando o apagamento dá certo ou no registro de uma conta.</li>
 * </ul>
 */
public class MensagensDoFirebase extends FirebaseMessagingService {

    static final String PREFERENCIAS = "push";
    static final String CHAVE_TOKEN = "token";
    static final String CHAVE_SEM_CONTA = "semConta";
    static final String CHAVE_APAGAR_TOKEN = "apagarToken";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage mensagem) {
        if (semConta(this)) {
            Log.i(CascaPlugin.TAG, "mensagem do Firebase depois do Sair, antes de uma conta entrar: não mostrada");
            return;
        }
        // Os canais aqui também: a primeira mensagem pode chegar antes de o técnico abrir o app depois da atualização,
        // e a notificação num canal que não existe não aparece no Android 8+.
        CanaisDeNotificacao.criar(this);
        long chegouEm = agora();
        Notificacoes.Montada m = Notificacoes.montar(mensagem.getData(), mensagem.getSentTime(), chegouEm);
        if (m == null) {
            Log.w(CascaPlugin.TAG, "mensagem do Firebase com o tipo fora do contrato: não mostrada");
            return;
        }
        // Só o motivo, um texto nosso: nada do título, do texto nem da OS.
        if (m.aviso != null) Log.w(CascaPlugin.TAG, "mensagem do Firebase: " + m.aviso);
        Notificacoes.mostrar(this, m, chegouEm);
    }

    /** O relógio de parede da chegada (o teste o para num instante). */
    long agora() {
        return System.currentTimeMillis();
    }

    /**
     * O Firebase deu um token novo (a primeira abertura, o app reinstalado, o token renovado, o que vem depois do
     * deleteToken do Sair). O novo é guardado e vai à página do App que estiver viva ({@link CascaPlugin#tokenNovo}), que
     * o registra uma vez; sem página viva, a próxima carga pede o token e registra. Caso: a primeira abertura sem sinal dá
     * token null à página, e o SDK do Firebase só obtém o token quando a rede volta, por aqui; o aparelho ficava sem aviso
     * até a próxima carga, que pode ser dias depois num app que o Android mantém vivo no fundo.
     *
     * No firebase-messaging 25.1 (BOM 34.19.0) este método está marcado como obsoleto em favor do onRegistered, que é de
     * outro fluxo de registro, ligado só pelo meta-data firebase_messaging_installation_id_enabled. Sem ele (o caso
     * daqui), o SDK segue chamando o onNewToken (conferido no FirebaseMessaging.invokeOnRegistrationChanged da 25.1.3).
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onNewToken(@NonNull String token) {
        guardarTokenNovo(this, token);
        CascaPlugin.tokenNovo(token);
    }

    private static SharedPreferences push(Context contexto) {
        return contexto.getApplicationContext().getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE);
    }

    // As gravações abaixo vão por commit, e não apply: o serviço do Firebase pode ser encerrado logo depois, e a marca do
    // Sair tem de estar no disco antes de a próxima mensagem chegar. Rodam fora da thread de UI (a do Firebase e a do
    // espelho). Cada uma mexe só na sua chave: o resto do arquivo fica.

    static void guardarTokenNovo(Context contexto, String token) {
        boolean gravou = push(contexto).edit().putString(CHAVE_TOKEN, token).commit();
        if (!gravou) Log.w(CascaPlugin.TAG, "o token novo do Firebase não foi gravado");
    }

    /** O último token que o Firebase deu, ou null. */
    static String tokenGuardado(Context contexto) {
        try {
            return push(contexto).getString(CHAVE_TOKEN, null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** O aparelho está sem conta: o Sair veio e o registro seguinte ainda não. */
    static boolean semConta(Context contexto) {
        try {
            return push(contexto).getBoolean(CHAVE_SEM_CONTA, false);
        } catch (ClassCastException e) {
            return false;
        }
    }

    /** O Sair: nada é mostrado até o próximo registro. Devolve false se não gravou. */
    static boolean marcarSemConta(Context contexto) {
        boolean gravou = push(contexto).edit().putBoolean(CHAVE_SEM_CONTA, true).commit();
        if (!gravou) Log.w(CascaPlugin.TAG, "a marca do Sair não foi gravada");
        return gravou;
    }

    /** O registro de uma conta deu certo: as notificações voltam. Devolve false se não gravou. */
    static boolean soltarSemConta(Context contexto) {
        boolean gravou = push(contexto).edit().remove(CHAVE_SEM_CONTA).commit();
        if (!gravou) Log.w(CascaPlugin.TAG, "a marca do Sair não foi solta");
        return gravou;
    }

    /** O apagamento do token do Sair ainda não deu certo. */
    static boolean apagarTokenPendente(Context contexto) {
        try {
            return push(contexto).getBoolean(CHAVE_APAGAR_TOKEN, false);
        } catch (ClassCastException e) {
            return false;
        }
    }

    /** O apagamento do token vai começar: fica marcado até dar certo. Devolve false se não gravou. */
    static boolean marcarApagarToken(Context contexto) {
        boolean gravou = push(contexto).edit().putBoolean(CHAVE_APAGAR_TOKEN, true).commit();
        if (!gravou) Log.w(CascaPlugin.TAG, "a marca do apagamento do token não foi gravada");
        return gravou;
    }

    /** O apagamento deu certo, ou uma conta registrou o token de agora. Devolve false se não gravou. */
    static boolean soltarApagarToken(Context contexto) {
        boolean gravou = push(contexto).edit().remove(CHAVE_APAGAR_TOKEN).commit();
        if (!gravou) Log.w(CascaPlugin.TAG, "a marca do apagamento do token não foi solta");
        return gravou;
    }
}
