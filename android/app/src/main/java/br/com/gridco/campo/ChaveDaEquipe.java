package br.com.gridco.campo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.util.UUID;

/**
 * A chave que o Nexus deu a este aparelho para a coleta de equipe, a conta de quem a pediu e o id do aparelho.
 *
 * <ul>
 *   <li>A chave só existe em repouso cifrada (a {@link Cifra}, em produção o Keystore), em base64 no SharedPreferences
 *       "equipe". Nunca em claro, nunca em log.</li>
 *   <li>A conta é o hash que a página calcula do token Microsoft dela (o SHA-256 de "nexus-field-equipe:" + o oid, ou o
 *       sub sem ele): a chave é de uma conta, e a casca só a usa para a mesma ({@link EquipeNaCasca}). Caso que criou a
 *       regra: no aparelho dividido, B que entrava sem o Sair de A ficava com a chave, o aviso aceito e a fila de A, e as
 *       posições de B iam ao Nexus em nome de A. Ela aponta para a pessoa: guardada cifrada, como a chave, e entra e sai
 *       junto com ela, menos no 401 do envio ({@link #apagarAChave}): aí a conta fica, dona da fila que sobrou.</li>
 *   <li>A conta conferida ({@link #conferida}) é só da memória desta partida do processo: começa "não conferida" e só a
 *       página a confere, pelo pedido do aparelho da mesma conta ({@link #conferir}) ou pela chave nova que ela entrega
 *       ({@link #guardar}). Até lá a coleta segue e guarda na fila, e nada sai ao Nexus ({@link EnvioDePontos}). Caso que
 *       criou a regra: na partida a frio, o onResume ligava a coleta com a chave guardada antes de a página mandar a conta,
 *       e o primeiro lote (uns 1 min 45 s depois) levava em nome de A as posições de B, que entrou sem o Sair de A e cujo
 *       login foi mais lento que isso.</li>
 *   <li>O aviso aceito pertence à chave em uso: chave diferente (outro técnico no mesmo aparelho, ou a revogada e
 *       refeita) zera o aceite, e o técnico vê o aviso de novo.</li>
 *   <li>Chave que a cifra de agora não lê ({@link Cifra.Ilegivel}) vale como sem chave: o serviço pede outra. A conta
 *       que ela não lê vale como sem conta.</li>
 *   <li>O id do aparelho é um UUID criado uma vez e sobrevive ao {@link #apagar}: é a identidade do aparelho, não o
 *       segredo.</li>
 * </ul>
 */
final class ChaveDaEquipe {

    private static final String ARQUIVO = "equipe";
    private static final String K_APARELHO = "aparelho";
    private static final String K_CHAVE = "chave";
    private static final String K_CONTA = "conta";
    private static final String K_AVISO = "aviso_aceito";

    private final SharedPreferences prefs;
    private final Cifra cifra;
    // Só na memória: some com o processo, e a partida seguinte começa sem conferir (uma ChaveDaEquipe por processo, a das
    // ServicoDaEquipe.Pecas).
    private boolean conferida = false;

    ChaveDaEquipe(Context ctx, Cifra cifra) {
        this.prefs = ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE);
        this.cifra = cifra;
    }

    synchronized String aparelho() {
        String id = prefs.getString(K_APARELHO, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(K_APARELHO, id).commit();
        }
        return id;
    }

    /**
     * Guarda a chave e a conta dela, as duas cifradas, de uma vez. True se é outra chave ou outra conta que as de antes (e
     * então o aviso aceito volta a "não"). Guardada (ou a mesma de antes), a conta fica conferida nesta partida: é a chave
     * que a página desta partida entregou, da conta que ela mandou. A cifra que falha lança antes, e a conferência fica
     * como estava.
     */
    synchronized boolean guardar(String chave, String conta) {
        if (chave.equals(chave()) && conta.equals(conta())) {
            conferida = true;
            return false;
        }
        String cifrada = Base64.encodeToString(cifra.cifrar(chave), Base64.NO_WRAP);
        String contaCifrada = Base64.encodeToString(cifra.cifrar(conta), Base64.NO_WRAP);
        prefs.edit().putString(K_CHAVE, cifrada).putString(K_CONTA, contaCifrada).putBoolean(K_AVISO, false).commit();
        conferida = true;
        return true;
    }

    /** A chave em claro, ou null se não há, ou se a cifra de agora não a lê. */
    synchronized String chave() {
        return decifrada(K_CHAVE);
    }

    /**
     * A conta da chave guardada, ou null sem chave, com a chave de antes da conta, ou com a que a cifra de agora não lê.
     * Lança, como o {@link #chave}, com o cofre que não respondeu agora: a conta continua guardada.
     */
    synchronized String conta() {
        return decifrada(K_CONTA);
    }

    /** Há uma chave guardada, legível ou não. Não passa pela cifra: vale também com o cofre que não responde agora. */
    synchronized boolean temChaveGuardada() {
        return prefs.contains(K_CHAVE);
    }

    /**
     * A página desta partida mandou a conta dela (o pedido do aparelho): com a chave guardada da MESMA conta, a conta fica
     * conferida e o envio sai; sem chave, ou com a de outra conta, não. Lança com o cofre que não respondeu agora, e nada
     * fica conferido (o que estava deixa de estar): o cofre que não lê a conta não confere, e nada sai até a próxima
     * conferência.
     */
    synchronized boolean conferir(String conta) {
        conferida = false;
        conferida = chave() != null && conta.equals(conta());
        return conferida;
    }

    /** A conta da chave guardada foi conferida pela página nesta partida do processo: só então o envio sai. */
    synchronized boolean conferida() {
        return conferida;
    }

    /** O Sair, a troca de conta e o coleta:false: a chave, a conta e o aviso aceito saem juntos. */
    synchronized void apagar() {
        prefs.edit().remove(K_CHAVE).remove(K_CONTA).remove(K_AVISO).commit();
        conferida = false;
    }

    /**
     * O 401 do envio (a chave que o Nexus não reconhece mais): sai só a chave. A conta fica, dona da fila que sobrou: a
     * mesma conta a sobe com a chave nova, e outra conta a apaga antes ({@link EquipeNaCasca}). Caso que criou a regra: A
     * trabalhou o dia sem sinal, a chave venceu e o primeiro envio levou o 401; com a conta apagada junto, B, que abriu o
     * app no dia seguinte sem o Sair de A, mandava o dia de A ao Nexus em nome de B.
     */
    synchronized void apagarAChave() {
        prefs.edit().remove(K_CHAVE).commit();
        conferida = false;
    }

    private String decifrada(String nome) {
        String guardada = prefs.getString(nome, null);
        if (guardada == null) return null;
        try {
            return cifra.decifrar(Base64.decode(guardada, Base64.NO_WRAP));
        } catch (Cifra.Ilegivel | IllegalArgumentException e) {
            return null;
        }
    }

    synchronized boolean avisoAceito() {
        return prefs.getBoolean(K_AVISO, false);
    }

    synchronized void aceitarAviso() {
        prefs.edit().putBoolean(K_AVISO, true).commit();
    }
}
