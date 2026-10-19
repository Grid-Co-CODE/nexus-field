package br.com.gridco.campo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.function.Consumer;

/**
 * O lado nativo da conversa das notificações com a página (www/casca/inicio.js, seções 8 e 9; o protocolo está no
 * CLAUDE.md, "As notificações do Nexus Field"). Quem registra o aparelho no servidor é a página, com o Bearer dela; o
 * nativo dá o token do Firebase, diz se os avisos do app estão ligados, cala a exibição depois do Sair e tira da barra a
 * emergência cuja OS a página abriu. Tudo aqui roda na thread do espelho (a das mensagens da página), uma coisa por vez:
 * o plugin põe ali também o onNewToken do Firebase e a volta ao app.
 *
 * <ul>
 *   <li><b>O token com os avisos</b> ({@code {t:'pushToken', token, versao, avisos}}): a resposta ao pushPedirToken, que
 *       também vai SOZINHA à página que pediu: no token novo do Firebase com a página viva ({@link #tokenNovo}) e na volta
 *       ao app ({@link #voltouAoApp}), quando os avisos passaram a ligados ou quando o token guardado não é o que a página
 *       recebeu. {@code avisos} é false com as notificações do app desligadas (ou sem a permissão, no Android 13+) ou com o
 *       canal das emergências bloqueado: a página só registra com true, senão o servidor contaria um aparelho que não
 *       mostra nada (e o FCM rebaixa a prioridade do app que recebe mensagem de prioridade alta sem mostrar).</li>
 *   <li><b>O Sair</b> ({@link #saiu}): a marca semConta (o MensagensDoFirebase não mostra nada até o próximo registro), as
 *       notificações do app saem da barra, a OS do toque é esquecida e o token é apagado no Firebase. É o que segura o Sair
 *       sem rede, com o servidor fora ou com a sessão vencida, quando o esquecer da página não chega ao servidor. O
 *       apagamento que falhou (sem rede, o mais comum no Sair que não chega ao servidor) fica marcado no arquivo "push" e é
 *       repetido no próximo pedido do token, antes de devolvê-lo, enquanto houver a marca semConta: senão o token de quem
 *       saiu seguia valendo no servidor (o esquecer também não chegou) até alguém entrar no aparelho.</li>
 *   <li><b>O registro</b> ({@link #registrado}, o 2xx do servidor na página) solta a marca (e a do apagamento: o token de
 *       agora é da conta que entrou).</li>
 *   <li><b>A OS aberta</b> ({@link #osAberta}): a emergência dela sai da barra e a OS deixa de ir à página.</li>
 * </ul>
 */
final class PushNaCasca {

    /** O Firebase do app ({@link #FIREBASE_DO_APP}; um falso no teste). */
    interface Firebase {
        /** Pede o token (o de cache, ou um novo) e o entrega na thread do espelho; null quando não sai. */
        void pedirToken(Consumer<String> entregar);

        /**
         * Apaga o token deste aparelho no Firebase (o próximo envio do servidor a ele volta UNREGISTERED) e diz, na thread
         * do espelho, se apagou (true também sem o Firebase no build: não há o que apagar) ou se falhou (sem rede).
         */
        void apagarToken(Consumer<Boolean> terminou);
    }

    /** O caminho até a página: a ponte da mensagem que pediu o token. Devolve false quando a página já foi embora. */
    interface Ponte {
        boolean mandar(String json);
    }

    private final Context app;
    private final Firebase firebase;
    private final OsDoToque osDoToque;
    private final String versao;

    private Ponte ponte;              // a da última página que pediu o token (null: nenhuma ainda)
    private String tokenEntregue;     // o último token que foi a ela
    private Boolean avisosEntregues;  // o último "avisos" que foi a ela (null: nada foi)

    PushNaCasca(Context contexto, Firebase firebase, OsDoToque osDoToque, String versao) {
        this.app = contexto.getApplicationContext();
        this.firebase = firebase;
        this.osDoToque = osDoToque;
        this.versao = versao;
    }

    /**
     * A página pediu o token ({@code pushPedirToken}): a resposta vai pela ponte dela, e ela passa a ser a da página. Com o
     * aparelho sem conta e o apagamento do Sair que falhou, o token é apagado de novo antes (o pedido espera o apagamento).
     */
    void pediuToken(Ponte daPagina) {
        ponte = daPagina;
        if (MensagensDoFirebase.semConta(app) && MensagensDoFirebase.apagarTokenPendente(app)) {
            Log.i(CascaPlugin.TAG, "o token de quem saiu não foi apagado no Sair: apagado de novo antes do token");
            apagarToken();
        }
        firebase.pedirToken(token -> {
            if (token != null && !token.equals(MensagensDoFirebase.tokenGuardado(app))) {
                MensagensDoFirebase.guardarTokenNovo(app, token);
            }
            entregar(daPagina, token);
        });
    }

    /** O Firebase deu um token novo (o onNewToken, já guardado): vai à página que pediu, se houver uma. */
    void tokenNovo(String token) {
        Ponte p = ponte;
        if (p != null && token != null) entregar(p, token);
    }

    /**
     * A volta ao app: manda o token de novo quando os avisos passaram a ligados (o técnico os ligou nos ajustes) ou quando
     * o token guardado não é o que a página recebeu (o onNewToken que não chegou a ela). Sem token não manda nada: a
     * página com o token null tem a espera dela.
     */
    void voltouAoApp() {
        Ponte p = ponte;
        if (p == null) return;
        boolean ligaram = Boolean.FALSE.equals(avisosEntregues) && avisosLigados(app);
        String guardado = MensagensDoFirebase.tokenGuardado(app);
        boolean outroToken = guardado != null && !guardado.equals(tokenEntregue);
        if (!ligaram && !outroToken) return;
        firebase.pedirToken(token -> {
            if (token != null && ponte == p) entregar(p, token);
        });
    }

    private void entregar(Ponte p, String token) {
        boolean avisos = avisosLigados(app);
        if (p.mandar(Mensageiro.respostaDoPush(token, versao, avisos))) {
            tokenEntregue = token;
            avisosEntregues = avisos;
        }
    }

    /** O Sair da página ({@code pushSaiu}), com token ou sem. Nunca lança. */
    void saiu() {
        MensagensDoFirebase.marcarSemConta(app);
        try {
            NotificationManagerCompat.from(app).cancelAll();
        } catch (RuntimeException e) {
            Log.w(CascaPlugin.TAG, "as notificações do Sair não saíram da barra (" + e.getClass().getSimpleName() + ")");
        }
        osDoToque.esquecer();
        apagarToken();
        Log.i(CascaPlugin.TAG, "o Sair: as notificações ficam caladas até o próximo registro");
    }

    // Apaga o token no Firebase. A marca do apagamento vai antes (o processo que morre no meio a guarda) e sai só quando ele
    // deu certo.
    private void apagarToken() {
        MensagensDoFirebase.marcarApagarToken(app);
        firebase.apagarToken(apagou -> {
            if (Boolean.TRUE.equals(apagou)) MensagensDoFirebase.soltarApagarToken(app);
        });
    }

    /** O servidor aceitou o registro de uma conta ({@code pushRegistrado}): as notificações voltam. */
    void registrado() {
        if (MensagensDoFirebase.semConta(app)) {
            MensagensDoFirebase.soltarSemConta(app);
            Log.i(CascaPlugin.TAG, "o aparelho registrado de novo: as notificações voltam");
        }
        // O token registrado é o da conta de agora: nada de quem saiu a apagar.
        if (MensagensDoFirebase.apagarTokenPendente(app)) MensagensDoFirebase.soltarApagarToken(app);
    }

    /** A página abriu de fato a OS do toque ({@code osAberta}, já validada). Pode chegar repetido. */
    void osAberta(String os) {
        if (!Notificacoes.osValida(os)) return;
        try {
            NotificationManagerCompat.from(app).cancel(Notificacoes.idDaEmergencia(os));
        } catch (RuntimeException e) {
            Log.w(CascaPlugin.TAG, "a emergência aberta não saiu da barra (" + e.getClass().getSimpleName() + ")");
        }
        osDoToque.esquecerSe(os);
    }

    /**
     * Os avisos do app estão ligados: as notificações do app (no Android 13+, a permissão) e o canal das emergências não
     * bloqueado. O do resumo não conta: quem o calou ainda quer o aviso urgente. Sem como perguntar ao sistema, vale
     * ligado (como o nativo antigo, que nem mandava o campo).
     */
    static boolean avisosLigados(Context contexto) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManagerCompat gerente = NotificationManagerCompat.from(contexto);
            if (!gerente.areNotificationsEnabled()) return false;
            NotificationChannelCompat emergencia = gerente.getNotificationChannelCompat(Notificacoes.CANAL_EMERGENCIA);
            return emergencia == null || emergencia.getImportance() != NotificationManagerCompat.IMPORTANCE_NONE;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * O Firebase de verdade. O build sem o google-services.json (o de PR, e todos até o arquivo entrar no repositório)
     * não inicia o Firebase (o FirebaseInitProvider só avisa no log), e o getInstance lança: o token vem null e o Sair fica
     * só com a marca. Sem rede, o pedido de um token novo falha (null) e o apagamento fica para depois: a marca segura.
     *
     * O pedido do token que chega com um apagamento em curso (o Sair seguido da carga do login) espera ele terminar: o
     * token velho registrado para a conta nova morreria logo em seguida, e o aparelho ficaria sem aviso até outra carga.
     */
    static final Firebase FIREBASE_DO_APP = new Firebase() {
        private volatile Task<Void> apagando;

        @Override
        public void pedirToken(Consumer<String> entregar) {
            Task<Void> emCurso = apagando;
            if (emCurso != null && !emCurso.isComplete()) {
                emCurso.addOnCompleteListener(CascaPlugin.threadDoEspelho(), t -> pedirToken(entregar));
                return;
            }
            Task<String> pedido;
            try {
                pedido = FirebaseMessaging.getInstance().getToken();
            } catch (RuntimeException e) {
                entregar.accept(null); // já na thread do espelho (o pedido vem da página)
                return;
            }
            pedido.addOnCompleteListener(CascaPlugin.threadDoEspelho(),
                tarefa -> entregar.accept(tarefa.isSuccessful() ? tarefa.getResult() : null));
        }

        @Override
        public void apagarToken(Consumer<Boolean> terminou) {
            Task<Void> pedido;
            try {
                pedido = FirebaseMessaging.getInstance().deleteToken();
            } catch (RuntimeException e) {
                Log.i(CascaPlugin.TAG, "sem o Firebase neste build: o Sair fica só com a marca");
                terminou.accept(true); // nada a apagar (já na thread do espelho: o Sair e o pedido vêm da página)
                return;
            }
            apagando = pedido;
            pedido.addOnCompleteListener(CascaPlugin.threadDoEspelho(), tarefa -> {
                Log.i(CascaPlugin.TAG, tarefa.isSuccessful() ? "o token do Firebase foi apagado (o Sair)"
                    : "o token do Firebase não foi apagado agora (sem rede?): a marca do Sair segura, e o próximo pedido "
                        + "do token apaga de novo");
                terminou.accept(tarefa.isSuccessful());
            });
        }
    };
}
