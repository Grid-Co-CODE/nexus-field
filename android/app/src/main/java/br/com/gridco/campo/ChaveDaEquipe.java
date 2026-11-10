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
 *   <li>A conta conferida ({@link #conferida}): só a página a confere, pelo pedido do aparelho da mesma conta
 *       ({@link #conferir}) ou pela chave nova que ela entrega ({@link #guardar}). Sem conferência a coleta segue e guarda
 *       na fila, e nada sai ao Nexus ({@link EnvioDePontos}). Caso que criou a regra (R8): na partida a frio, o onResume
 *       ligava a coleta com a chave guardada antes de a página mandar a conta, e o primeiro lote (uns 1 min 45 s depois)
 *       levava em nome de A as posições de B, que entrou sem o Sair de A e cujo login foi mais lento que isso.</li>
 *   <li>A conferência vai também ao disco, presa à conta (R14): a marca {@code conta_conferida} é a conta guardada
 *       cifrada, a mesma do {@code conta}, e só vale enquanto as duas forem iguais e a chave estiver guardada. A partida
 *       nova (a atualização, o Forçar parada, o reinício, o app morto pelo sistema) e a tela que nasce
 *       ({@link #aTelaAbriu}) a mantêm, e o envio sai sem a página; a página continua conferindo a cada carga. Caso que
 *       criou a regra (10/11/2026): a atualização fechou o app, a coleta religou e coletou a cada 2 min, e só enviou 27
 *       min depois, quando o dono abriu o app; com a tela apagada o JavaScript da página não roda, e só ela conferia. O
 *       Sair, a troca de conta, o coleta:false, o 401, a página de outra conta e o cofre que lê outra conta a apagam, como
 *       apagam a da memória. O custo aceito pelo dono: no celular dividido em que outra pessoa abre o app sem o Sair da
 *       anterior, os pontos entre a partida e a carga da página vão em nome da anterior (antes, esperavam); a página dela
 *       faz a troca de conta, e dali em diante nada sai em nome da anterior.</li>
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
 *   <li>O aviso das 6h ({@link AvisoDoInicio}, R12) e o aviso do que falta ({@link AvisoDoQueFalta}, Task 14) só existem
 *       com a chave: a chave que sai leva o alarme e os dois avisos da barra.</li>
 *   <li>A janela da bateria sem restrição (R15, {@link EquipeNaCasca}) fica por pedir a cada Li e aceito
 *       ({@link #bateriaPorPedir}) e sai quando a janela abriu ({@link #bateriaPedida}): uma vez por conta e por termo.
 *       Recusada, não volta até a próxima conta ou o próximo termo, que pedem o aceite de novo; o 401 (a mesma conta) não
 *       a traz de volta.</li>
 *   <li>O pedido do "Permitir o tempo todo" (R15, {@link EquipeNaCasca}), igual: por fazer a cada Li e aceito
 *       ({@link #tempoTodoPorPedir}), feito quando o técnico respondeu ao Android ou com o tempo todo já dado
 *       ({@link #tempoTodoPedido}). Recusado, a coleta segue só com a durante o uso, e o pedido não volta até a próxima
 *       conta ou o próximo termo.</li>
 *   <li>A tela do Início automático do HyperOS (Task 14, {@link EquipeNaCasca}), igual à da bateria: por abrir a cada Li e
 *       aceito ({@link #inicioAutomaticoPorPedir}), aberta quando a casca a mandou à tela, ou nada a abrir fora do Xiaomi
 *       ou com ele já ligado ({@link #inicioAutomaticoPedido}). Recusada, não volta até a próxima conta ou o próximo
 *       termo.</li>
 * </ul>
 */
final class ChaveDaEquipe {

    private static final String ARQUIVO = "equipe";
    private static final String K_APARELHO = "aparelho";
    private static final String K_CHAVE = "chave";
    private static final String K_CONTA = "conta";
    /**
     * A versão do texto do termo de uso e privacidade (o casca_equipe_termo_texto, que diz "Versão 5" na primeira linha: o
     * de 10/11/2026 que acrescenta aos ajustes do celular o Início automático dos celulares Xiaomi e pede que fique ligado,
     * a Task 14; a 4, do mesmo dia, diz que os ajustes vão ao Nexus; a 3 pede o Permitir o tempo todo e a bateria sem
     * restrição, R15; a 2 mostra o ponto exato e o percurso, R13; a 1, de 09/11, prometia o contrário). Mudar o texto é
     * mudar esta constante (e a primeira linha), e o aceite da versão de antes deixa de valer: o técnico vê o termo novo
     * antes de a coleta ligar de novo.
     */
    static final String TERMO_VERSAO = "5";

    // O aceite do termo (R10), e não o "aviso_aceito" de antes dele: quem aceitou o aviso num aparelho de prova não leu o
    // termo. Guarda a versão aceita (R11); a marca booleana que a primeira versão desta chave guardava, só nos aparelhos
    // de prova, não é uma versão e não vale.
    private static final String K_TERMO = "termo_aceito";
    // A conta conferida pela página, no disco (R14): os mesmos bytes cifrados do K_CONTA de quando a página conferiu.
    // Comparar o cifrado com o cifrado não passa pela cifra (o serviço pergunta na thread principal), e a conta guardada
    // que muda sem conferência (outra cifra, outra conta) deixa a marca sem valor. O nome não é o "conferida" de um APK
    // de desenvolvimento de 09/11 (um sim ou não, que não dizia de quem era): o que ele tenha deixado não vale.
    private static final String K_CONFERIDA = "conta_conferida";
    // A janela da bateria sem restrição por pedir (R15): posta pelo Li e aceito, tirada quando a casca a abre.
    private static final String K_BATERIA = "bateria_por_pedir";
    // O pedido do Permitir o tempo todo por fazer (R15): posto pelo Li e aceito, tirado com a resposta do técnico.
    private static final String K_TEMPO_TODO = "tempo_todo_por_pedir";
    // A tela do Início automático do HyperOS por abrir (Task 14): posta pelo Li e aceito, tirada quando a casca a abre.
    private static final String K_INICIO = "inicio_automatico_por_pedir";
    // O Sair que começou e ainda não terminou ({@link #saindo}): posto no toque, tirado com a chave (o apagar) ou com a
    // chave nova (o guardar).
    private static final String K_SAINDO = "sair_por_terminar";

    private final Context app;
    private final SharedPreferences prefs;
    private final Cifra cifra;
    // Na memória (uma ChaveDaEquipe por processo, a das ServicoDaEquipe.Pecas): a página desta partida conferiu. A do
    // disco é a K_CONFERIDA.
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
     * Outra conta que a guardada (ou sem conta guardada, ou com a que a cifra não lê) esquece o termo aceito, a janela da
     * bateria, o pedido do tempo todo e a tela do Início automático por fazer; a chave nova da mesma conta os mantém. Guardada (ou a mesma de antes),
     * a conta fica conferida, também no disco: é a chave que a página desta tela entregou, da conta que ela mandou. A cifra
     * que falha lança antes, e a conferência fica como estava.
     */
    synchronized boolean guardar(String chave, String conta) {
        String contaGuardada = conta();
        if (chave.equals(chave()) && conta.equals(contaGuardada)) {
            conferidaPelaPagina(conta);
            return false;
        }
        String cifrada = Base64.encodeToString(cifra.cifrar(chave), Base64.NO_WRAP);
        String contaCifrada = Base64.encodeToString(cifra.cifrar(conta), Base64.NO_WRAP);
        // A conta conferida vai no mesmo commit: a chave nova e a marca dela chegam juntas ao disco.
        SharedPreferences.Editor gravacao = prefs.edit().putString(K_CHAVE, cifrada).putString(K_CONTA, contaCifrada)
            .putString(K_CONFERIDA, contaCifrada);
        if (!conta.equals(contaGuardada)) {
            gravacao.remove(K_TERMO).remove(K_BATERIA).remove(K_TEMPO_TODO).remove(K_INICIO);
        }
        gravacao.remove(K_SAINDO).commit(); // a chave nova é da conta da página: o Sair de antes não vale para ela
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
     * conferida (também no disco) e o envio sai; sem chave, ou com a de outra conta, não, e a marca do disco sai. Lança com
     * o cofre que não respondeu agora, e nada fica conferido (o que estava deixa de estar, também no disco: a página falou,
     * e não se sabe se é a mesma conta): o cofre que não lê a conta não confere, e a conferência fica por refazer
     * ({@link #reconferir}, R9) até o cofre voltar.
     */
    synchronized boolean conferir(String conta) {
        desfazer();
        contaDaPagina = conta;
        porRefazer = true; // até o cofre responder: a conferência que lança fica por refazer (R9)
        boolean mesma = !saidaPendente() && chave() != null && conta.equals(conta());
        porRefazer = false;
        if (mesma) conferidaPelaPagina(conta);
        return mesma;
    }

    /**
     * A conferência que o cofre não deixou fazer (R9), de novo, com a conta que a página mandou: true se a chave guardada é
     * da mesma conta (e então conferida, também no disco). A de outra conta não confere, não deixa marca no disco e segue
     * por refazer: a troca de conta da R5 é da volta ao app ou do próximo pedido do aparelho, fora do envio do serviço.
     * Lança com o cofre que ainda não responde. Sem nada por refazer, devolve a conferência de agora.
     */
    synchronized boolean reconferir() {
        if (!precisaReconferir()) return conferida();
        if (chave() == null || !contaDaPagina.equals(conta())) {
            semAMarca();
            return false;
        }
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

    /**
     * A conta da chave guardada foi conferida pela página, nesta partida ou numa de antes (a marca no disco, R14): só então
     * o envio sai. Não passa pela cifra: o serviço pergunta na thread principal ({@link EnvioDePontos#podeEnviar}).
     */
    synchronized boolean conferida() {
        return !saidaPendente() && (conferida || conferidaNoDisco());
    }

    /**
     * A conferência de uma partida de antes, que esta ainda não tinha usado: passa a valer também na memória, e o true é
     * só desta primeira vez (o envio leva ao log que saiu sem esperar a página). Não passa pela cifra.
     */
    synchronized boolean retomarConferencia() {
        if (conferida || !conferidaNoDisco()) return false;
        conferida = true;
        return true;
    }

    /**
     * A tela que nasce (o load do plugin): a conta que a página da tela de antes mandou não é a desta, que manda a dela ao
     * carregar. A conferência fica, na memória e no disco (R14): com a tela apagada a página não roda, e a coleta que liga
     * com esta tela envia. A página desta tela confere quando carregar: a mesma conta mantém, outra faz a troca de conta
     * (EquipeNaCasca.pedirAparelho) antes de qualquer envio dela.
     */
    synchronized void aTelaAbriu() {
        contaDaPagina = null;
        porRefazer = false;
    }

    /**
     * O Sair, a troca de conta e o coleta:false: a chave, a conta, o termo aceito, a janela da bateria, o pedido do tempo
     * todo e a tela do Início automático por fazer e a conferência (a da memória e a do disco) saem juntos, e o aviso das
     * 6h e o do que falta com eles ({@link #semOsAvisos}).
     */
    synchronized void apagar() {
        prefs.edit().remove(K_CHAVE).remove(K_CONTA).remove(K_TERMO).remove(K_CONFERIDA).remove(K_BATERIA)
            .remove(K_TEMPO_TODO).remove(K_INICIO).remove(K_SAINDO).commit();
        conferida = false;
        contaDaPagina = null;
        porRefazer = false;
        semOsAvisos();
    }

    /**
     * O toque no Sair, antes do trabalho dele (que espera na thread do envio, atrás de um envio pendurado, e revoga pela
     * rede, até 30 s com o sinal fraco): a conferência sai na hora, da memória e do disco, a marca do Sair por terminar vai
     * ao disco e o aviso das 6h e o do que falta saem. Enquanto a marca existe, nada fica conferido (nem a página da mesma conta confere),
     * a coleta não religa sozinha ({@link ServicoDaEquipe#oQueFaltaParaReligar}) e o aviso das 6h não vale; a partida
     * seguinte termina o Sair (EquipeNaCasca). Caso que criou a regra (a revisão final do ramo, 10/11/2026): o processo
     * morto nesse minuto (tirado dos recentes, ou o HyperOS) deixava a chave e a conferência no disco (R14), e a partida
     * seguinte enviava em nome de quem saiu e, com o tempo todo (R15), religava a coleta dele.
     */
    synchronized void saindo() {
        conferida = false;
        porRefazer = false;
        contaDaPagina = null;
        prefs.edit().remove(K_CONFERIDA).putBoolean(K_SAINDO, true).commit();
        semOsAvisos();
    }

    /** O Sair começou e ainda não terminou, nesta partida ou numa de antes ({@link #saindo}). Não passa pela cifra. */
    synchronized boolean saidaPendente() {
        return prefs.getBoolean(K_SAINDO, false);
    }

    /**
     * O 401 do envio (a chave que o Nexus não reconhece mais): sai só a chave. A conta fica, dona da fila que sobrou: a
     * mesma conta a sobe com a chave nova, e outra conta a apaga antes ({@link EquipeNaCasca}). Caso que criou a regra: A
     * trabalhou o dia sem sinal, a chave venceu e o primeiro envio levou o 401; com a conta apagada junto, B, que abriu o
     * app no dia seguinte sem o Sair de A, mandava o dia de A ao Nexus em nome de B. O termo aceito fica com a conta; a
     * conferência sai (a da memória e a do disco), e a chave nova a refaz; o aviso das 6h e o do que falta saem
     * ({@link #semOsAvisos}), e a chave nova da mesma conta os traz de novo.
     */
    synchronized void apagarAChave() {
        prefs.edit().remove(K_CHAVE).remove(K_CONFERIDA).commit();
        conferida = false;
        porRefazer = false;
        semOsAvisos();
    }

    // O aviso das 6h (R12) e o do que falta (Task 14) existem enquanto a chave existe: todo caminho em que ela sai passa
    // por aqui (o Sair, a troca de conta e o coleta:false do registro ou do envio pelo apagar; o 401 pelo apagarAChave,
    // também o do último envio das 22h, cuja resposta ninguém lê), e o alarme e os avisos da barra saem junto. Sem isto,
    // cada um desses caminhos teria de lembrar de cancelar, e o esquecido acordaria à toa às 6h o celular de quem saiu (o
    // aviso não apareceria: a decisão é da hora do alarme) e deixaria na barra de quem saiu o "Falta liberar no celular".
    private void semOsAvisos() {
        AvisoDoInicio.cancelar(app);
        AvisoDoQueFalta.tirar(app);
    }

    // Conferida, na memória e no disco: a chave guardada é da conta que a página mandou. A marca é a conta guardada tal
    // como está cifrada no disco (a conta conferida é ela), e só se grava se mudou.
    private void conferidaPelaPagina(String conta) {
        conferida = true;
        contaDaPagina = conta;
        porRefazer = false;
        String guardada = prefs.getString(K_CONTA, null);
        if (guardada != null && !guardada.equals(prefs.getString(K_CONFERIDA, null))) {
            gravar(prefs.edit().putString(K_CONFERIDA, guardada));
        }
    }

    // Deixa de estar conferida, na memória e no disco (a conferência de outra conta, o cofre fora).
    private void desfazer() {
        conferida = false;
        porRefazer = false;
        semAMarca();
    }

    private void semAMarca() {
        if (prefs.contains(K_CONFERIDA)) gravar(prefs.edit().remove(K_CONFERIDA));
    }

    // A marca vale com a chave guardada e para a conta guardada hoje: o cifrado da conta conferida é o mesmo do da conta
    // guardada. Não passa pela cifra.
    private boolean conferidaNoDisco() {
        String marca = prefs.getString(K_CONFERIDA, null);
        return marca != null && prefs.contains(K_CHAVE) && marca.equals(prefs.getString(K_CONTA, null));
    }

    // O commit, e não o apply, como nos outros caminhos desta classe: a marca tem de chegar ao disco antes de o envio
    // seguir, na thread dele.
    private static void gravar(SharedPreferences.Editor gravacao) {
        gravacao.commit();
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

    /**
     * A versão do termo aceita neste aparelho, a de agora ou uma de antes, ou null (o Celular pronto do Nexus, nos
     * {@link AjustesDaColeta}). A marca booleana de antes da versão não é uma versão: null. Não passa pela cifra.
     */
    synchronized String versaoDoTermoAceito() {
        try {
            return prefs.getString(K_TERMO, null);
        } catch (ClassCastException e) {
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

    /**
     * O Li e aceito: guarda a versão do texto que o técnico leu até o fim, e deixa por pedir o Permitir o tempo todo, a
     * janela da bateria sem restrição (R15) e a tela do Início automático (Task 14): é o aceite da conta e do termo que os
     * traz, uma vez.
     */
    synchronized void aceitarTermo() {
        prefs.edit().putString(K_TERMO, TERMO_VERSAO).putBoolean(K_TEMPO_TODO, true).putBoolean(K_BATERIA, true)
            .putBoolean(K_INICIO, true).commit();
    }

    /** O pedido do Permitir o tempo todo ainda por fazer, desde o último Li e aceito desta conta (R15). */
    synchronized boolean tempoTodoPorPedir() {
        try {
            return prefs.getBoolean(K_TEMPO_TODO, false) && termoAceito();
        } catch (ClassCastException e) {
            return false;
        }
    }

    /**
     * O técnico respondeu ao pedido do tempo todo (dando ou não), ou ele já estava dado: até o próximo Li e aceito, a casca
     * não pede de novo.
     */
    synchronized void tempoTodoPedido() {
        if (prefs.contains(K_TEMPO_TODO)) gravar(prefs.edit().remove(K_TEMPO_TODO));
    }

    /** A janela da bateria sem restrição ainda por pedir, desde o último Li e aceito desta conta. */
    synchronized boolean bateriaPorPedir() {
        try {
            return prefs.getBoolean(K_BATERIA, false) && termoAceito();
        } catch (ClassCastException e) {
            return false;
        }
    }

    /** A janela da bateria aberta (ou nada a pedir, com o app já liberado): até o próximo Li e aceito, ela não volta. */
    synchronized void bateriaPedida() {
        if (prefs.contains(K_BATERIA)) gravar(prefs.edit().remove(K_BATERIA));
    }

    /** A tela do Início automático ainda por abrir, desde o último Li e aceito desta conta (Task 14). */
    synchronized boolean inicioAutomaticoPorPedir() {
        try {
            return prefs.getBoolean(K_INICIO, false) && termoAceito();
        } catch (ClassCastException e) {
            return false;
        }
    }

    /**
     * A tela do Início automático aberta (ou nada a abrir: fora do Xiaomi, sem saber o estado ou com ele já ligado): até o
     * próximo Li e aceito, ela não volta.
     */
    synchronized void inicioAutomaticoPedido() {
        if (prefs.contains(K_INICIO)) gravar(prefs.edit().remove(K_INICIO));
    }
}
