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
 *       posições de B, que entrou sem o Sair de A e cujo login foi mais lento que isso. Ela fica só na memória: o serviço
 *       não volta sem a tela (o Android que mata o app para a coleta até a próxima abertura, {@link ServicoDaEquipe}), e
 *       nenhum processo sem página precisa dela.</li>
 *   <li>O cofre que não respondeu na conferência (R9): a conta da página fica guardada na memória
 *       ({@link #contaDaPagina}), e o envio e a volta ao app conferem de novo ({@link #reconferir}) até o cofre voltar.
 *       Caso que criou a regra: com a R8 e a R7 juntas, um soluço do Keystore na carga deixava o envio parado até a
 *       próxima carga, que pode ser no dia seguinte (a fila perde o que passa de 24 h).</li>
 *   <li>O termo de uso e privacidade da localização aceito é da conta (R10): a chave nova da mesma conta (a revogada e
 *       refeita depois do 401) não pede o termo de novo; outra conta no mesmo aparelho, a chave de antes da conta e a
 *       conta que a cifra não lê pedem. Caso que criou a regra: o dono pediu menos telas ("essa quantidade de telas e
 *       avisos atrapalham muito a experiência"), e o Nexus que revoga as chaves mostraria o termo de novo a quem já o
 *       aceitou.</li>
 *   <li>E é da versão do texto ({@link #TERMO_VERSAO}, R11): o aceite guarda a versão, e só vale o da versão de agora.
 *       Caso que criou a regra: o termo curto da R10 virou o aviso de privacidade inteiro (o pedido do dono, 09/11/2026),
 *       e quem aceitou o curto num aparelho de prova não leu o novo; e o texto que mudar no futuro pede o aceite de novo,
 *       como a seção 12 dele promete.</li>
 *   <li>Chave que a cifra de agora não lê ({@link Cifra.Ilegivel}) vale como sem chave: o serviço pede outra. A conta
 *       que ela não lê vale como sem conta.</li>
 *   <li>O id do aparelho é um UUID criado uma vez e sobrevive ao {@link #apagar}: é a identidade do aparelho, não o
 *       segredo.</li>
 *   <li>O aviso das 6h ({@link AvisoDoInicio}, R12) só existe com a chave: a chave que sai leva o alarme e o aviso da
 *       barra.</li>
 * </ul>
 */
final class ChaveDaEquipe {

    private static final String ARQUIVO = "equipe";
    private static final String K_APARELHO = "aparelho";
    private static final String K_CHAVE = "chave";
    private static final String K_CONTA = "conta";
    /**
     * A versão do texto do termo de uso e privacidade (o casca_equipe_termo_texto, que diz "Versão 2" na primeira linha: o
     * de 10/11/2026, que mostra o ponto exato e o percurso, R13; a 1, de 09/11, prometia o contrário).
     * Mudar o texto é mudar esta constante (e a primeira linha), e o aceite da versão de antes deixa de valer: o técnico
     * vê o termo novo antes de a coleta ligar de novo.
     */
    static final String TERMO_VERSAO = "2";

    // O aceite do termo (R10), e não o "aviso_aceito" de antes dele: quem aceitou o aviso num aparelho de prova não leu o
    // termo. Guarda a versão aceita (R11); a marca booleana que a primeira versão desta chave guardava, só nos aparelhos
    // de prova, não é uma versão e não vale.
    private static final String K_TERMO = "termo_aceito";

    private final Context app;
    private final SharedPreferences prefs;
    private final Cifra cifra;
    // Na memória: some com o processo e com a tela que nasce (uma ChaveDaEquipe por processo, a das
    // ServicoDaEquipe.Pecas).
    private boolean conferida = false;
    // A conta que a página desta tela mandou no último pedido do aparelho, e se a conferência dela ficou por refazer
    // porque o cofre não respondeu (R9).
    private String contaDaPagina;
    private boolean porRefazer = false;

    ChaveDaEquipe(Context ctx, Cifra cifra) {
        this.app = ctx.getApplicationContext();
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
     * Guarda a chave e a conta dela, as duas cifradas, de uma vez. True se é outra chave ou outra conta que as de antes.
     * Outra conta que a guardada (ou sem conta guardada, ou com a que a cifra não lê) esquece o termo aceito; a chave nova
     * da mesma conta o mantém. Guardada (ou a mesma de antes), a conta fica conferida: é a chave que a página
     * desta tela entregou, da conta que ela mandou. A cifra que falha lança antes, e a conferência fica como estava.
     */
    synchronized boolean guardar(String chave, String conta) {
        String contaGuardada = conta();
        if (chave.equals(chave()) && conta.equals(contaGuardada)) {
            conferidaPelaPagina(conta);
            return false;
        }
        String cifrada = Base64.encodeToString(cifra.cifrar(chave), Base64.NO_WRAP);
        String contaCifrada = Base64.encodeToString(cifra.cifrar(conta), Base64.NO_WRAP);
        SharedPreferences.Editor gravacao = prefs.edit().putString(K_CHAVE, cifrada).putString(K_CONTA, contaCifrada);
        if (!conta.equals(contaGuardada)) gravacao.remove(K_TERMO);
        gravacao.commit();
        conferidaPelaPagina(conta);
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
     * conferida e o envio sai; sem chave, ou com a de outra conta, não. Lança com o cofre que não
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
     * da mesma conta (e então conferida). A de outra conta não confere e segue por refazer: a troca de
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
     * A tela que nasce (o load do plugin): a conferência de antes não vale até a página desta tela conferir (R8: outra conta
     * pode estar entrando, também no processo que sobreviveu à noite).
     */
    synchronized void aTelaAbriu() {
        desfazer();
        contaDaPagina = null;
    }

    /**
     * O Sair, a troca de conta e o coleta:false: a chave, a conta, o termo aceito e a conferência saem juntos, e o aviso
     * das 6h com eles ({@link #semOAvisoDas6h}).
     */
    synchronized void apagar() {
        prefs.edit().remove(K_CHAVE).remove(K_CONTA).remove(K_TERMO).commit();
        conferida = false;
        contaDaPagina = null;
        porRefazer = false;
        semOAvisoDas6h();
    }

    /**
     * O 401 do envio (a chave que o Nexus não reconhece mais): sai só a chave. A conta fica, dona da fila que sobrou: a
     * mesma conta a sobe com a chave nova, e outra conta a apaga antes ({@link EquipeNaCasca}). Caso que criou a regra: A
     * trabalhou o dia sem sinal, a chave venceu e o primeiro envio levou o 401; com a conta apagada junto, B, que abriu o
     * app no dia seguinte sem o Sair de A, mandava o dia de A ao Nexus em nome de B. O termo aceito fica com a conta; o
     * aviso das 6h sai ({@link #semOAvisoDas6h}), e a chave nova da mesma conta o agenda de novo.
     */
    synchronized void apagarAChave() {
        prefs.edit().remove(K_CHAVE).commit();
        conferida = false;
        porRefazer = false;
        semOAvisoDas6h();
    }

    // O aviso das 6h (R12) existe enquanto a chave existe: todo caminho em que ela sai passa por aqui (o Sair, a troca de
    // conta e o coleta:false do registro ou do envio pelo apagar; o 401 pelo apagarAChave, também o do último envio das
    // 22h, cuja resposta ninguém lê), e o alarme e o aviso da barra saem junto. Sem isto, cada um desses caminhos teria de
    // lembrar de cancelar, e o esquecido acordaria à toa às 6h o celular de quem saiu (o aviso não apareceria: a decisão é
    // da hora do alarme).
    private void semOAvisoDas6h() {
        AvisoDoInicio.cancelar(app);
    }

    // Conferida: a chave guardada é da conta que a página mandou.
    private void conferidaPelaPagina(String conta) {
        conferida = true;
        contaDaPagina = conta;
        porRefazer = false;
    }

    // Deixa de estar conferida (a conferência de outra conta, o cofre fora, a tela que nasce).
    private void desfazer() {
        conferida = false;
        porRefazer = false;
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

    /** O Li e aceito do termo de uso e privacidade, da conta da chave guardada e da versão de agora do texto. */
    synchronized boolean termoAceito() {
        try {
            return TERMO_VERSAO.equals(prefs.getString(K_TERMO, null));
        } catch (ClassCastException e) {
            // A marca booleana de antes da versão (aparelhos de prova): quem a tem leu o termo curto, não este.
            return false;
        }
    }

    /** O Li e aceito: guarda a versão do texto que o técnico leu até o fim. */
    synchronized void aceitarTermo() {
        prefs.edit().putString(K_TERMO, TERMO_VERSAO).commit();
    }
}
