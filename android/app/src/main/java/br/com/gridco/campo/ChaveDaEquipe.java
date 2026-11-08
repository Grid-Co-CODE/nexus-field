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
 *       junto com ela.</li>
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
     * então o aviso aceito volta a "não").
     */
    synchronized boolean guardar(String chave, String conta) {
        if (chave.equals(chave()) && conta.equals(conta())) return false;
        String cifrada = Base64.encodeToString(cifra.cifrar(chave), Base64.NO_WRAP);
        String contaCifrada = Base64.encodeToString(cifra.cifrar(conta), Base64.NO_WRAP);
        prefs.edit().putString(K_CHAVE, cifrada).putString(K_CONTA, contaCifrada).putBoolean(K_AVISO, false).commit();
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

    synchronized void apagar() {
        prefs.edit().remove(K_CHAVE).remove(K_CONTA).remove(K_AVISO).commit();
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
