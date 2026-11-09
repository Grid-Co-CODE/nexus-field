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
 *   <li>A conta conferida ({@link #conferida}) começa "não conferida" em cada processo e em cada tela que nasce
 *       ({@link #aTelaAbriu}), e só a página a confere, pelo pedido do aparelho da mesma conta ({@link #conferir}) ou pela
 *       chave nova que ela entrega ({@link #guardar}). Até lá a coleta segue e guarda na fila, e nada sai ao Nexus
 *       ({@link EnvioDePontos}). Caso que criou a regra (R8): na partida a frio, o onResume ligava a coleta com a chave
 *       guardada antes de a página mandar a conta, e o primeiro lote (uns 1 min 45 s depois) levava em nome de A as
 *       posições de B, que entrou sem o Sair de A e cujo login foi mais lento que isso.</li>
 *   <li>A conferência também vai ao disco, como uma marca de sim ou não (a conta já está cifrada ao lado; a marca não diz
 *       de quem é), para o processo que o Android religa sem tela ({@link #retomarConferencia}, o START_STICKY do
 *       ServicoDaEquipe): ele não tem página para conferir, e sem a marca coletava a tarde inteira sem mandar nada até o
 *       técnico abrir o app de novo (o processo morto pela falta de memória ou pelo "tirar dos recentes" de quem mata o
 *       app). Tudo que desfaz a conferência tira a marca junto, e a tela que nasce também: ela é o caso que a R8 protege
 *       (outra conta pode estar entrando), e o religamento depois dela espera a página dela conferir. O tempo da marca é o
 *       do prazo do dia do serviço: o religamento só coleta até as 22h do dia em que o app abriu.</li>
 *   <li>O cofre que não respondeu na conferência (R9): a conta da página fica guardada na memória
 *       ({@link #contaDaPagina}), e o envio e a volta ao app conferem de novo ({@link #reconferir}) até o cofre voltar.
 *       Caso que criou a regra: com a R8 e a R7 juntas, um soluço do Keystore na carga deixava o envio parado até a
 *       próxima carga, que pode ser no dia seguinte (a fila perde o que passa de 24 h).</li>
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
    // A conferência da página, para o religamento sem tela: um sim ou não (a conta cifrada já está em K_CONTA).
    private static final String K_CONFERIDA = "conferida";

    private final SharedPreferences prefs;
    private final Cifra cifra;
    // Na memória: some com o processo e com a tela que nasce (uma ChaveDaEquipe por processo, a das
    // ServicoDaEquipe.Pecas). A do disco (K_CONFERIDA) só o religamento sem tela lê.
    private boolean conferida = false;
    // A conta que a página desta tela mandou no último pedido do aparelho, e se a conferência dela ficou por refazer
    // porque o cofre não respondeu (R9).
    private String contaDaPagina;
    private boolean porRefazer = false;

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
     * então o aviso aceito volta a "não"). Guardada (ou a mesma de antes), a conta fica conferida, também no disco: é a
     * chave que a página desta tela entregou, da conta que ela mandou. A cifra que falha lança antes, e a conferência fica
     * como estava.
     */
    synchronized boolean guardar(String chave, String conta) {
        if (chave.equals(chave()) && conta.equals(conta())) {
            conferidaPelaPagina(conta);
            return false;
        }
        String cifrada = Base64.encodeToString(cifra.cifrar(chave), Base64.NO_WRAP);
        String contaCifrada = Base64.encodeToString(cifra.cifrar(conta), Base64.NO_WRAP);
        prefs.edit().putString(K_CHAVE, cifrada).putString(K_CONTA, contaCifrada).putBoolean(K_AVISO, false)
            .putBoolean(K_CONFERIDA, true).commit();
        conferida = true;
        contaDaPagina = conta;
        porRefazer = false;
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
     * conferida (também no disco) e o envio sai; sem chave, ou com a de outra conta, não. Lança com o cofre que não
     * respondeu agora, e nada fica conferido (o que estava deixa de estar): o cofre que não lê a conta não confere, e a
     * conferência fica por refazer ({@link #reconferir}, R9) até o cofre voltar.
     */
    synchronized boolean conferir(String conta) {
        desfazer();
        contaDaPagina = conta;
        porRefazer = true; // até o cofre responder: a conferência que lança fica por refazer (R9)
        boolean mesma = chave() != null && conta.equals(conta());
        porRefazer = false;
        if (mesma) conferidaPelaPagina(conta);
        return mesma;
    }

    /**
     * A conferência que o cofre não deixou fazer (R9), de novo, com a conta que a página mandou: true se a chave guardada é
     * da mesma conta (e então conferida, também no disco). A de outra conta não confere e segue por refazer: a troca de
     * conta da R5 é da volta ao app ou do próximo pedido do aparelho, fora do envio do serviço. Lança com o cofre que
     * ainda não responde. Sem nada por refazer, devolve a conferência de agora.
     */
    synchronized boolean reconferir() {
        if (!precisaReconferir()) return conferida;
        if (chave() == null || !contaDaPagina.equals(conta())) return false;
        conferidaPelaPagina(contaDaPagina);
        return true;
    }

    /** A conferência da página ficou por refazer, porque o cofre não respondeu (R9). Não passa pela cifra. */
    synchronized boolean precisaReconferir() {
        return porRefazer && contaDaPagina != null;
    }

    /** A conta que a página desta tela mandou no último pedido do aparelho, ou null. */
    synchronized String contaDaPagina() {
        return contaDaPagina;
    }

    /** A conta da chave guardada foi conferida pela página: só então o envio sai. */
    synchronized boolean conferida() {
        return conferida;
    }

    /**
     * A tela que nasce (o load do plugin): a conferência de antes não vale, na memória nem no disco, até a página desta
     * tela conferir (R8: outra conta pode estar entrando, também no processo que sobreviveu à noite).
     */
    synchronized void aTelaAbriu() {
        desfazer();
        contaDaPagina = null;
    }

    /**
     * O processo que o Android religou sem tela (o START_STICKY do ServicoDaEquipe): vale a conferência que a página fez
     * antes de o processo morrer, se a chave e a conta dela continuam guardadas. Não passa pela cifra.
     */
    synchronized boolean retomarConferencia() {
        boolean marca;
        try {
            marca = prefs.getBoolean(K_CONFERIDA, false);
        } catch (ClassCastException e) {
            marca = false;
        }
        conferida = marca && prefs.contains(K_CHAVE) && prefs.contains(K_CONTA);
        return conferida;
    }

    /** O Sair, a troca de conta e o coleta:false: a chave, a conta, o aviso aceito e a conferência saem juntos. */
    synchronized void apagar() {
        prefs.edit().remove(K_CHAVE).remove(K_CONTA).remove(K_AVISO).remove(K_CONFERIDA).commit();
        conferida = false;
        contaDaPagina = null;
        porRefazer = false;
    }

    /**
     * O 401 do envio (a chave que o Nexus não reconhece mais): sai só a chave. A conta fica, dona da fila que sobrou: a
     * mesma conta a sobe com a chave nova, e outra conta a apaga antes ({@link EquipeNaCasca}). Caso que criou a regra: A
     * trabalhou o dia sem sinal, a chave venceu e o primeiro envio levou o 401; com a conta apagada junto, B, que abriu o
     * app no dia seguinte sem o Sair de A, mandava o dia de A ao Nexus em nome de B.
     */
    synchronized void apagarAChave() {
        prefs.edit().remove(K_CHAVE).remove(K_CONFERIDA).commit();
        conferida = false;
        porRefazer = false;
    }

    // Conferida, na memória e no disco: a chave guardada é da conta que a página mandou.
    private void conferidaPelaPagina(String conta) {
        conferida = true;
        contaDaPagina = conta;
        porRefazer = false;
        prefs.edit().putBoolean(K_CONFERIDA, true).commit();
    }

    // Deixa de estar conferida, na memória e no disco (a conferência de outra conta, o cofre fora, a tela que nasce).
    private void desfazer() {
        conferida = false;
        porRefazer = false;
        prefs.edit().remove(K_CONFERIDA).commit();
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
