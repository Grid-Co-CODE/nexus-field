package br.com.gridco.campo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.util.UUID;

/**
 * A chave que o Nexus deu a este aparelho para a coleta de equipe, e o id do aparelho.
 *
 * <ul>
 *   <li>A chave só existe em repouso cifrada (a {@link Cifra}, em produção o Keystore), em base64 no SharedPreferences
 *       "equipe". Nunca em claro, nunca em log.</li>
 *   <li>O aviso aceito pertence à chave em uso: chave diferente (outro técnico no mesmo aparelho, ou a revogada e
 *       refeita) zera o aceite, e o técnico vê o aviso de novo.</li>
 *   <li>Chave que a cifra de agora não lê ({@link Cifra.Ilegivel}) vale como sem chave: o serviço pede outra.</li>
 *   <li>O id do aparelho é um UUID criado uma vez e sobrevive ao {@link #apagar}: é a identidade do aparelho, não o
 *       segredo.</li>
 * </ul>
 */
final class ChaveDaEquipe {

    private static final String ARQUIVO = "equipe";
    private static final String K_APARELHO = "aparelho";
    private static final String K_CHAVE = "chave";
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

    /** Guarda a chave cifrada. True se é outra chave que a de antes (e então o aviso aceito volta a "não"). */
    synchronized boolean guardar(String chave) {
        if (chave.equals(chave())) return false;
        String cifrada = Base64.encodeToString(cifra.cifrar(chave), Base64.NO_WRAP);
        prefs.edit().putString(K_CHAVE, cifrada).putBoolean(K_AVISO, false).commit();
        return true;
    }

    /** A chave em claro, ou null se não há, ou se a cifra de agora não a lê. */
    synchronized String chave() {
        String guardada = prefs.getString(K_CHAVE, null);
        if (guardada == null) return null;
        try {
            return cifra.decifrar(Base64.decode(guardada, Base64.NO_WRAP));
        } catch (Cifra.Ilegivel | IllegalArgumentException e) {
            return null;
        }
    }

    synchronized void apagar() {
        prefs.edit().remove(K_CHAVE).remove(K_AVISO).commit();
    }

    synchronized boolean avisoAceito() {
        return prefs.getBoolean(K_AVISO, false);
    }

    synchronized void aceitarAviso() {
        prefs.edit().putBoolean(K_AVISO, true).commit();
    }
}
