package br.com.gridco.campo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A casca liga e desliga a coleta da posição da equipe (spec 2026-11-08, seção 4.1): pelo que o Nexus disse da chave do
 * aparelho (o equipeChave que a página repassa), pelo termo de uso aceito, pela permissão de localização durante o uso e
 * pela janela das 6h às 22h na hora do celular, na volta ao app ({@link #reavaliar}) e no Sair ({@link #sair}). A regra é a
 * {@link #decidir}, pura; o resto só a executa. O religamento sozinho do dia começado (R15) não passa por aqui: é do
 * serviço e do {@link ReceptorDoInicio}.
 *
 * <ul>
 *   <li><b>Fora da janela nada aparece ao técnico</b>: nem o termo nem o pedido da localização (às 23h não há coleta a
 *       ligar, e um pedido da localização à noite assusta); a coleta que sobrou rodando para. A primeira abertura depois
 *       das 6h mostra o que faltar.</li>
 *   <li><b>Uma tela nossa só: o termo de uso da localização</b> (R10, o pedido do dono: "essa quantidade de telas e avisos
 *       atrapalham muito a experiência"), uma vez por conta ({@link ChaveDaEquipe#termoAceito}: a chave nova da mesma conta
 *       não o pede de novo, outra conta no aparelho pede). Um botão só, o Li e aceito: não fecha tocando fora nem pelo
 *       Voltar, e sem o aceite nada coleta (a próxima abertura mostra o termo de novo).</li>
 *   <li><b>A localização durante o uso</b>, a precisa: logo depois do aceite, o pedido do próprio Android, com a precisa e
 *       a aproximada juntas (do Android 12 em diante, a precisa pedida sozinha é ignorada), sem tela nossa antes. É ela que
 *       liga a coleta: o serviço do tipo localização, iniciado com o app à vista, mantém a durante o uso enquanto roda,
 *       também com o app fechado. Fora do aceite quem pede a localização é o "Antes de começar" da MainActivity, a cada
 *       abertura (dois pedidos de uma vez empilhariam as telas); negada, ou negada de vez (o Android não mostra mais o
 *       pedido), nenhuma tela nossa a mais: o Nexus sabe, e o guia diz como liberar nos ajustes. Vale a precisa: a
 *       aproximada erra por quilômetros, e o ponto no mapa e o Abrir rota precisam dele.</li>
 *   <li><b>O "Permitir o tempo todo"</b> (R15, o pedido do dono em 10/11/2026: "precisamos só ser mais independente...
 *       sem esses erros de ficar parando coleta nem nada"; no lugar da R10, que o tinha tirado para ter menos telas): logo
 *       depois da localização dada, com a coleta ligando (ou já ligada) e a tela à vista, o pedido do Android da
 *       localização o tempo todo, sozinho ({@link #pedirOTempoTodo}), sem tela nossa antes: no Android 10, a tela do
 *       próprio Android; do 11 em diante, o Android leva aos ajustes do app. Uma vez por conta e por termo
 *       ({@link ChaveDaEquipe#tempoTodoPorPedir}): recusado, a coleta segue só com a durante o uso, como na 1.2.0, e o pedido
 *       não volta até a próxima conta ou o próximo termo. Com ele, o serviço volta sozinho no dia começado; o serviço que
 *       ligou antes dele é iniciado de novo, com a tela à vista, para passar a pedir o religamento ao Android
 *       ({@link Servico#religaSozinho}). O mapa não sabe dele: o "sem" ao Nexus continua só para a falta da localização
 *       precisa ou da notificação.</li>
 *   <li><b>A bateria sem restrição</b> (R15, o mesmo pedido): logo depois do termo aceito, da localização dada e da
 *       resposta ao pedido do tempo todo (a ordem: a localização, o tempo todo, a bateria), com a coleta ligando (ou já
 *       ligada) e a tela à vista, se o Android ainda aplica a otimização de bateria ao Nexus Field, a janela
 *       do próprio Android que pede a liberação ({@link Tela#pedirSemRestricaoDeBateria}), sem tela nossa antes. Uma vez por
 *       conta e por termo ({@link ChaveDaEquipe#bateriaPorPedir}): recusada, não volta até a próxima conta ou o próximo
 *       termo, e a coleta segue igual. Por que importa: liberado da otimização, o Android 12+ deixa o app ligar o serviço
 *       em primeiro plano com o app no fundo (é uma das exceções da regra), e o HyperOS fecha menos o app. O Início
 *       automático do Xiaomi não tem janela do Android: é o guia de instalação que o pede.</li>
 *   <li><b>A casca só liga o serviço com a tela à vista</b> ({@link #aTelaFicouAVista}, do onResume ao onStop): a decisão
 *       que roda na thread do envio depois de o técnico sair do app (a chave que o Nexus deu com o app já no fundo, a volta
 *       ao app atrás de um envio pendurado) não o liga. Iniciado com o app no fundo, sem o tempo todo ele não teria a
 *       localização do Android 11 em diante, e do 12 em diante o Android recusa o primeiro plano: a notificação prometeria
 *       uma coleta que não acontece. A próxima volta ao app liga. A coleta ligada segue com a tela fora da vista.</li>
 *   <li><b>Sem a notificação fixa à vista não há coleta</b> ({@link ServicoDaEquipe#notificacaoAVista}): a regra também a
 *       confere, e falta o mesmo que falta a permissão (a coleta ligada para, e o Nexus sabe). Antes, a casca mandava
 *       ligar, o serviço não ligava, e o mapa mostrava o aparelho só como "sem sinal".</li>
 *   <li><b>Sem elas, o Nexus sabe</b>: o lote vazio com {@code permissao: "sem"} ({@link EnvioDePontos#avisarSemPermissao}),
 *       no máximo um a cada 30 minutos no aparelho (a marca atravessa a tela recriada), para o mapa separar quem negou de
 *       quem não abriu o app. O aviso que não chegou ao Nexus (sem rede) não conta: a próxima volta ao app tenta; o que o
 *       Nexus recusou (o 4xx) conta, porque mandar de novo não muda a resposta.</li>
 *   <li><b>O Sair</b> para a coleta na hora, apaga a fila (os pontos de quem saiu não podem subir com a chave de quem
 *       entrar depois) e revoga e apaga a chave, mesmo sem rede, com o termo aceito e a marca dos 30 minutos (a conta
 *       seguinte não espera a de quem saiu para o mapa saber que falta a permissão). Até a chave sair, a decisão que já
 *       esperava na thread (a volta ao app atrás de um envio pendurado na rede fraca) não liga a coleta nem mostra nada,
 *       e a tarefa do Sair para de novo antes da rede: senão a coleta de quem saiu seguiria até as 22h.</li>
 *   <li><b>A chave e a fila são de uma conta</b> (a que a página calcula do token dela, {@link ChaveDaEquipe#conta}), e a
 *       conta guardada sobrevive ao 401 do envio, que apaga só a chave. A outra conta no aparelho (B que entra sem o Sair
 *       de A, com a chave de A ou com a fila que o 401 deixou), a chave de antes da conta ou a fila sem conta guardada
 *       recebem o mesmo trabalho do Sair da anterior (sem a revogação, sem chave a revogar), na thread do envio, antes de
 *       a página saber se há chave ({@link #pedirAparelho}) ou de a chave nova entrar ({@link #receberChave}): senão B
 *       ficava com a chave, o termo aceito e a fila de A, e as posições de B iam ao Nexus em nome de A (ou o dia de A em
 *       nome de B). A mesma conta, depois do 401, fica com a fila, que sobe com a chave nova. O cofre que não respondeu
 *       agora não muda nada: a próxima carga confere de novo.</li>
 *   <li><b>Nada sai em nome de uma conta que nenhuma página conferiu</b> ({@link ChaveDaEquipe#conferida}): a conferência
 *       vai também ao disco, presa à conta guardada (R14), e a partida nova e a tela que nasce ({@link #aTelaAbriu}) a
 *       mantêm: depois da atualização, do Forçar parada ou do reinício, a coleta que liga com a tela apagada (a página não
 *       roda) envia. A página confere a cada carga: a mesma conta mantém; outra conta faz a troca de conta da R5 no pedido
 *       do aparelho ({@link #pedirAparelho}), antes de qualquer envio dela, e dali em diante nada sai em nome da anterior.
 *       O custo aceito pelo dono: no celular dividido, os pontos entre a partida e a carga da página vão em nome da conta
 *       anterior. Sem conferência nenhuma (a chave da 1.2.0, de antes da marca), a coleta guarda na fila, e o envio, o
 *       aviso sem permissão inclusive, espera o pedido do aparelho achar a chave da mesma conta ou a chave nova entrar. O
 *       cofre que não lê a conta não confere: a resposta diz que há a chave, e o envio e a volta ao app conferem de novo
 *       até o cofre voltar (R9); a conta guardada que então se mostra de outra conta faz a troca de conta na volta ao app.
 *       O Sair, a troca de conta, o coleta:false e o 401 desfazem a conferência, também a do disco; a revogação do Sair não
 *       espera.</li>
 *   <li><b>O aviso das 6h</b> ({@link AvisoDoInicio}, R12): a volta ao app agenda o do dia seguinte enquanto a chave e o
 *       termo estão de pé (a chave que entra e o Li e aceito passam por ela), e o tira sem um dos dois. O toque nele só
 *       abre o app: quem liga a coleta é esta volta ao app, como em qualquer abertura.</li>
 *   <li><b>Uma thread só</b> para o disco, a cifra e a rede: a do serviço ({@link ServicoDaEquipe.Pecas#execucao}), na
 *       ordem em que tudo chegou. O pedido do aparelho que vem depois de um Sair só é respondido depois de a chave sair (e
 *       a página pede outra), e a chave que chega enquanto um envio leva o 401 não é apagada por ele. O que mexe na tela
 *       vai à de UI.</li>
 *   <li>Nada de chave, id de aparelho ou coordenada no log, nem o texto das exceções: só o nome da classe.</li>
 * </ul>
 */
final class EquipeNaCasca {

    /** O que a casca faz agora com a coleta. */
    enum Acao {
        /** Liga o serviço ({@link ServicoDaEquipe#iniciar}, que ainda confere a janela, a localização e a notificação). */
        INICIAR,
        /** Desliga o serviço e esquece o prazo do dia, também com ele parado. */
        PARAR,
        /** Mostra o termo de uso da localização; a coleta ligada para antes. */
        MOSTRAR_TERMO,
        /**
         * Falta a localização: logo depois do aceite do termo, o pedido do Android; fora dele, só o Nexus fica sabendo. A
         * coleta ligada para antes.
         */
        PEDIR_PERMISSAO,
        /** A notificação fixa não ficaria à vista: a coleta ligada para, e o Nexus sabe que falta, como a permissão. */
        SEM_A_NOTIFICACAO,
        /** Nada muda. */
        NADA
    }

    /** No máximo um aviso de "sem permissão" ao Nexus por este tempo (o contrato, seção 8 do desenho). */
    static final long INTERVALO_SEM_PERMISSAO_MS = 30L * 60L * 1000L;

    /**
     * O pedido do Android logo depois do aceite: a localização durante o uso, a precisa e a aproximada juntas (do Android
     * 12 em diante a precisa pedida sozinha é ignorada, e a tela do Android oferece as duas).
     */
    static final String[] A_LOCALIZACAO = {
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    };
    private static final String PRECISA = Manifest.permission.ACCESS_FINE_LOCATION;

    private static final String TAG = CascaPlugin.TAG;
    // Quando o Nexus soube, pela última vez, que falta a permissão (epoch ms). Fora do backup, como tudo (regras_dados.xml).
    private static final String PREFERENCIAS = "equipe_casca";
    private static final String CHAVE_SEM_PERMISSAO = "sem_permissao_em";

    /** O serviço da coleta: o {@link ServicoDaEquipe} ({@link ServicoDoAndroid}); no teste, um falso. */
    interface Servico {
        void iniciar();

        void parar();

        boolean rodando();

        /** A notificação fixa ficaria à vista ({@link ServicoDaEquipe#notificacaoAVista}). */
        boolean notificacaoAVista();

        /**
         * A coleta ligada volta sozinha se o Android matar o processo: o último início pediu o religamento, o que só se faz
         * com o tempo todo ({@link ServicoDaEquipe#religaSozinho}).
         */
        boolean religaSozinho();
    }

    /**
     * O que o técnico vê e responde: o termo, os pedidos da localização e do tempo todo ao Android e a janela da bateria do
     * Android ({@link TelaDaActivity}). Tudo na thread de UI. O que devolve false não apareceu (a tela saindo): a casca não
     * fica esperando uma resposta que não vem.
     */
    interface Tela {
        /** O termo de uso da localização, que não fecha tocando fora nem pelo Voltar; o Li e aceito roda {@code aceitar}. */
        boolean mostrarTermo(Runnable aceitar);

        /** Pede as permissões ao Android; a resposta volta pelo {@link EquipeNaCasca#aposOPedido}. */
        boolean pedir(String[] permissoes);

        /** Tira da tela o termo que estiver aberto (o Sair, a coleta que deixou de valer, a tela saindo). */
        void fechar();

        /**
         * A janela do próprio Android que pede a bateria sem restrição para o Nexus Field (R15). A resposta não volta à
         * casca: recusada ou aceita, a coleta segue igual. False só com a tela saindo (a próxima volta ao app tenta).
         */
        boolean pedirSemRestricaoDeBateria();
    }

    private final Context app;
    private final ChaveDaEquipe chave;
    private final FilaDePontos fila;
    private final EnvioDePontos envio;
    private final Servico servico;
    private final Tela tela;
    private final Executor trabalho;
    private final Executor ui;
    private final LongSupplier relogio;
    private final String versao;

    // Só na thread do trabalho. O termo ou o pedido do Android estão na frente do técnico: a resposta dele decide, e a
    // volta ao app não abre outro por cima.
    private boolean esperandoOTecnico = false;
    // O termo acabou de ser aceito nesta tela e a localização falta: o pedido do Android vem agora, uma vez. Fora disso a
    // casca não pede (o Antes de começar da MainActivity pede a cada abertura).
    private boolean pedirDepoisDoTermo = false;
    // Só na thread do trabalho. O pedido na frente do técnico é o do tempo todo, e não o da localização: a resposta dele
    // (também a vazia, do pedido interrompido) é desse pedido.
    private boolean pedindoOTempoTodo = false;
    // Só na thread do trabalho. A janela da bateria a caminho da tela: a decisão seguinte (a volta ao app, a chave que
    // chega) não abre outra por cima antes de a marca sair.
    private boolean bateriaACaminho = false;
    // A tela à vista (do onResume ao onStop, na thread de UI): só com ela o serviço liga.
    private volatile boolean aVista = false;
    // Os Sair pedidos cuja chave ainda não saiu do aparelho. A decisão que já esperava na thread antes do Sair (a volta
    // ao app atrás de um envio pendurado na rede fraca, com 15 s de conexão e 15 s de leitura) rodaria depois do parar
    // do Sair, ainda com a chave de quem saiu, e ligaria a coleta dele até as 22h. Se a tarefa do Sair nem entrar na
    // thread (nunca: ela não é desligada), a marca fica, e a coleta não liga com a chave que não saiu.
    private final AtomicInteger saidasPendentes = new AtomicInteger();

    /**
     * @param trabalho a thread do disco, da cifra e da rede (a do serviço, {@link ServicoDaEquipe.Pecas#execucao})
     * @param ui       a thread de UI da tela (o plugin não roda nada nela com a tela saindo)
     * @param relogio  o relógio de parede (a janela das 6h às 22h e a marca dos 30 minutos)
     * @param versao   o versionName, que a página manda ao Nexus como "NexusField/&lt;versao&gt;"
     */
    EquipeNaCasca(Context contexto, ChaveDaEquipe chave, FilaDePontos fila, EnvioDePontos envio, Servico servico,
            Tela tela, Executor trabalho, Executor ui, LongSupplier relogio, String versao) {
        this.app = contexto.getApplicationContext();
        this.chave = chave;
        this.fila = fila;
        this.envio = envio;
        this.servico = servico;
        this.tela = tela;
        this.trabalho = trabalho;
        this.ui = ui;
        this.relogio = relogio;
        this.versao = versao;
    }

    /**
     * A da tela do plugin: as peças do processo (a chave, a fila, o envio e a thread do serviço), o ServicoDaEquipe e o
     * termo nesta activity. {@code pedido} é o launcher do pedido de permissões que o plugin registrou no load().
     */
    static EquipeNaCasca daTela(Activity activity, Executor ui, Consumer<String[]> pedido, String versao) {
        Context app = activity.getApplicationContext();
        ServicoDaEquipe.Pecas pecas = ServicoDaEquipe.pecas(app);
        return new EquipeNaCasca(app, pecas.chave, pecas.fila, pecas.envio, new ServicoDoAndroid(app),
            new TelaDaActivity(activity, pedido), pecas.execucao, ui, pecas.relogio, versao);
    }

    /**
     * A regra, nesta ordem: sem a coleta do Nexus (o coleta:false) ou sem a chave, para; fora da janela das 6h às 22h,
     * nada (e para o que sobrou rodando), sem mostrar nada ao técnico; sem o termo aceito, o termo; sem a permissão de
     * localização (a precisa, durante o uso), a permissão; sem a notificação fixa à vista, o mesmo que sem a permissão, sem
     * pedido; tudo em dia, liga, ou nada se já está rodando.
     */
    static Acao decidir(boolean coleta, boolean temChave, boolean termoAceito, boolean localizacao,
            boolean notificacaoAVista, boolean dentroDaJanela, boolean rodando) {
        if (!coleta || !temChave) return Acao.PARAR;
        if (!dentroDaJanela) return rodando ? Acao.PARAR : Acao.NADA;
        if (!termoAceito) return Acao.MOSTRAR_TERMO;
        if (!localizacao) return Acao.PEDIR_PERMISSAO;
        if (!notificacaoAVista) return Acao.SEM_A_NOTIFICACAO;
        return rodando ? Acao.NADA : Acao.INICIAR;
    }

    /**
     * O que o Nexus respondeu ao registro do aparelho, que a página repassa com a conta dela (o equipeChave, já conferido
     * no Mensageiro).
     */
    void receberChave(boolean coleta, String nova, String conta) {
        trabalhar(() -> {
            if (!coleta || nova == null) {
                // A coleta não vale para esta conta: primeiro ela para (o que falhar depois não a deixa ligada), e
                // então sem a chave e sem a fila (os pontos que sobraram não têm um dono que se saiba); o diálogo da
                // equipe aberto sai da frente.
                servico.parar();
                chave.apagar();
                limparAFila();
                pararDeEsperar();
                Log.i(TAG, "o Nexus: a coleta da equipe não vale para esta conta");
                return;
            }
            // A chave ou a fila de outra conta ainda guardadas (o pedido do aparelho desta conta não passou antes, ou a fila
            // que o 401 deixou): saem como no Sair dela, antes de a nova entrar, ou a fila dela subiria com a chave desta.
            if (deOutraConta(conta)) esquecerAContaAnterior();
            try {
                // Guardada, a chave já vem conferida nesta partida: é a da conta que a página mandou, e o envio sai.
                if (chave.guardar(nova, conta)) Log.i(TAG, "o Nexus deu a chave da equipe a este aparelho");
            } catch (RuntimeException e) {
                // A cifra que falhou: a chave não vai em claro para o disco, e a próxima carga da página pede outra.
                Log.w(TAG, "a chave da equipe não foi guardada (" + e.getClass().getSimpleName() + ")");
            }
            reavaliarAgora();
        });
    }

    /** A volta ao app (e a primeira abertura, que também passa pelo onResume): o que a regra mandar agora. */
    void reavaliar() {
        trabalhar(this::reavaliarAgora);
    }

    /**
     * O Sair da página: a coleta para já, e a chave (com a fila) é revogada e apagada na thread do envio, mesmo sem
     * rede. Até a chave sair, nada se decide com ela ({@link #reavaliarAgora}), e a tarefa do Sair para a coleta de
     * novo, antes da fila e da rede.
     */
    void sair() {
        saidasPendentes.incrementAndGet(); // antes do parar: a decisão que vier depois dele já vê a marca
        // No disco, antes do trabalho do Sair na thread: se o processo morrer antes dele, a conta de quem saiu não fica
        // conferida e a partida seguinte termina o Sair ({@link #terminarOSairDeAntes}).
        chave.saindo();
        // O parar que lança não segura o resto: a chave e a fila de quem saiu têm de sair, e a marca do Sair com elas
        // (senão a conta seguinte nunca liga a coleta). A tarefa abaixo para de novo.
        pararAColeta();
        trabalhar(() -> {
            esquecerAConta();
            // A marca do Sair sai com a chave (que sai também quando o revogar falha): se a chave não saiu do disco, a
            // marca fica, e nada liga com ela.
            saidasPendentes.decrementAndGet();
            Log.i(TAG, "o Sair: a coleta da equipe parou, e a chave do aparelho foi revogada e apagada");
        });
    }

    /**
     * A página pediu o id do aparelho (o equipePedirAparelho, com a conta dela): a resposta
     * ({@link Mensageiro#respostaDaEquipe}) vai pela ponte da mensagem, na thread do envio, depois do que já estava nela
     * (o Sair que ainda revogava a chave). A chave ou a fila guardadas de outra conta (ou sem dona) saem antes da resposta,
     * como no Sair dela, e a resposta então diz que não há chave: a página registra a conta de agora. A chave da mesma
     * conta fica conferida nesta partida, e só então o envio sai.
     */
    void pedirAparelho(String conta, Mensageiro.Saida ponte) {
        trabalhar(() -> {
            terminarOSairDeAntes();
            if (deOutraConta(conta)) esquecerAContaAnterior();
            conferir(conta);
            if (!ponte.enviar(Mensageiro.respostaDaEquipe(chave, versao, conta))) {
                Log.i(TAG, "a página foi embora antes do id do aparelho: a próxima carga pede de novo");
            }
        });
    }

    // A conta que a página mandou, contra a da chave guardada: a mesma solta o envio. O cofre que não respondeu agora não
    // confere: a resposta diz que há a chave (pedir outra a revogaria à toa), mas nada sai até a próxima conferência, e a
    // de outra conta, se for, faz a troca.
    private void conferir(String conta) {
        try {
            if (chave.conferir(conta)) Log.i(TAG, "a conta da chave da equipe conferida nesta partida: o envio sai");
        } catch (RuntimeException e) {
            Log.w(TAG, "a conta da chave da equipe não pôde ser conferida agora (" + e.getClass().getSimpleName()
                + "): o envio espera a próxima conferência");
        }
    }

    /**
     * A resposta do Android ao pedido da localização ou ao do tempo todo (o launcher do plugin, na thread de UI). A do tempo
     * todo traz o nome dele: assim vale também a que chega a uma tela nova (do Android 11 em diante o pedido leva aos
     * ajustes do app, e o processo pode morrer lá), que não sabe do pedido.
     */
    void aposOPedido(Map<String, Boolean> resultado) {
        trabalhar(() -> {
            boolean doTempoTodo = pedindoOTempoTodo
                || (resultado != null && resultado.containsKey(ServicoDaEquipe.O_TEMPO_TODO));
            esperandoOTecnico = false;
            pedindoOTempoTodo = false;
            if (resultado == null || resultado.isEmpty()) {
                // O pedido interrompido (a tela saiu no meio): o técnico não respondeu, e a próxima volta ao app pergunta
                // (o do tempo todo segue por fazer, e a janela da bateria espera por ele).
                if (!doTempoTodo) pedirDepoisDoTermo = true;
                return;
            }
            if (doTempoTodo) {
                // Respondido, dado ou não: não se pede de novo até a próxima conta ou o próximo termo.
                chave.tempoTodoPedido();
                Log.i(TAG, "a localização o tempo todo " + (temOTempoTodo() ? "dada" : "não dada: a coleta segue só "
                    + "com a durante o uso"));
            } else {
                Log.i(TAG, "a localização precisa " + (tem(PRECISA) ? "dada" : "não dada"));
            }
            reavaliarAgora();
        });
    }

    /** A tela à vista (o handleOnResume do plugin, antes do reavaliar): o serviço pode ligar. */
    void aTelaFicouAVista() {
        aVista = true;
    }

    /** A tela fora da vista (o handleOnStop do plugin): a decisão que vier depois não liga o serviço. */
    void aTelaSaiuDaVista() {
        aVista = false;
    }

    /**
     * A tela que nasce (o load do plugin): a conta que a página da tela de antes mandou não é a desta. A conferência fica
     * (R14): com a tela apagada a página não roda, e a coleta que liga com esta tela envia; a página desta tela confere
     * quando carregar.
     */
    void aTelaAbriu() {
        trabalhar(chave::aTelaAbriu);
    }

    /** A tela saindo (o handleOnDestroy do plugin, na thread de UI): o diálogo aberto vai junto. */
    void encerrar() {
        tela.fechar();
    }

    private void reavaliarAgora() {
        if (saidasPendentes.get() > 0) {
            // O Sair a caminho: esta decisão esperava na thread desde antes dele e, com a chave de quem saiu ainda
            // guardada, ligaria a coleta dele, mostraria o termo ou avisaria o Nexus com a chave que vai ser revogada.
            Log.i(TAG, "o Sair a caminho: a casca não decide nada com a chave de quem saiu");
            return;
        }
        if (terminarOSairDeAntes()) return;
        if (chave.precisaReconferir()) reconferirNaVolta();
        // O aviso das 6h (R12) acompanha a chave e o termo: a chave que entra com o termo aceito, o Li e aceito e cada
        // volta ao app agendam o do dia seguinte (o Forçar parada apaga os alarmes do app, e a abertura os refaz); sem um
        // dos dois, ele sai. Depois da troca de conta acima, para não agendar o da conta que saiu.
        AvisoDoInicio.agendarSeVale(app, chave, relogio.getAsLong());
        boolean temChave;
        try {
            temChave = chave.chave() != null;
        } catch (RuntimeException e) {
            // O cofre do aparelho não respondeu agora (a chave guardada continua lá): nada muda nesta volta.
            Log.w(TAG, "a chave da equipe não pôde ser lida agora (" + e.getClass().getSimpleName() + ")");
            return;
        }
        boolean rodando = servico.rodando();
        // Sem a resposta do Nexus na mão, a coleta vale enquanto houver a chave: ele só a dá com o coleta:true, e o
        // coleta:false (no registro ou num envio), o 401 e o Sair a apagam.
        Acao acao = decidir(temChave, temChave, chave.termoAceito(), tem(PRECISA), servico.notificacaoAVista(),
            JanelaDaEquipe.dentro(relogio.getAsLong(), TimeZone.getDefault()), rodando);
        switch (acao) {
            case INICIAR:
                if (!aVista) {
                    // A tela saiu da vista antes desta decisão: o serviço iniciado com o app no fundo não teria a
                    // localização durante o uso (Android 11+) nem o primeiro plano (12+). A próxima volta ao app liga.
                    Log.i(TAG, "a tela não está à vista: a coleta da equipe liga na próxima volta ao app");
                    break;
                }
                Log.i(TAG, "a casca liga a coleta da equipe");
                servico.iniciar();
                // A ordem depois da localização (R15): o tempo todo e, depois da resposta dele, a bateria.
                pedirOTempoTodo();
                pedirABateria();
                break;
            case PARAR:
                if (rodando) Log.i(TAG, "a casca desliga a coleta da equipe");
                servico.parar();
                break;
            case MOSTRAR_TERMO:
                if (rodando) servico.parar();
                mostrarOTermo();
                break;
            case PEDIR_PERMISSAO:
                if (rodando) servico.parar();
                pedirALocalizacao();
                break;
            case SEM_A_NOTIFICACAO:
                // Quem pede a notificação é o Antes de começar da MainActivity, uma vez: aqui só o Nexus fica sabendo.
                if (rodando) servico.parar();
                avisarSemPermissao("sem a notificação fixa à vista");
                break;
            default:
                // O NADA dentro da janela é a coleta já ligada com tudo em dia: o serviço ligado antes do tempo todo passa
                // a pedir o religamento, e o pedido do tempo todo e a janela da bateria que não abriram (a tela saindo)
                // abrem agora.
                if (rodando) {
                    religarSozinhoDaquiEmDiante();
                    pedirOTempoTodo();
                    pedirABateria();
                }
                break;
        }
    }

    // R15: logo depois da localização dada e da coleta ligada, o pedido do Android da localização o tempo todo, sozinho
    // (o Android 10 mostra a tela dele; do 11 em diante ele leva aos ajustes do app), uma vez por conta e por termo. A
    // marca sai com a resposta do técnico (aposOPedido); a que não apareceu ou foi interrompida pede na próxima volta. Com
    // o tempo todo já dado (do Android 9 para trás, a localização dada), nada a pedir, e a marca sai também.
    private void pedirOTempoTodo() {
        if (!aVista || esperandoOTecnico || !chave.tempoTodoPorPedir()) return;
        if (temOTempoTodo()) {
            chave.tempoTodoPedido();
            return;
        }
        esperandoOTecnico = true;
        pedindoOTempoTodo = true;
        Log.i(TAG, "a casca pede ao Android a localização o tempo todo, logo depois da localização durante o uso");
        naTela(() -> {
            if (!tela.pedir(new String[] {ServicoDaEquipe.O_TEMPO_TODO})) trabalhar(this::tempoTodoNaoApareceu);
        });
    }

    // O pedido do tempo todo que não abriu (a tela saindo): a marca fica, e a próxima volta ao app pede.
    private void tempoTodoNaoApareceu() {
        esperandoOTecnico = false;
        pedindoOTempoTodo = false;
    }

    // O serviço que ligou sem o tempo todo pediu ao Android para não ser religado, e é o último início que vale: com o tempo
    // todo dado depois (o pedido logo acima, ou os ajustes), um início novo, com a tela à vista, passa a pedir o
    // religamento. Sem isso, a coleta do dia em que o técnico deu o tempo todo não voltaria sozinha.
    private void religarSozinhoDaquiEmDiante() {
        if (!aVista || servico.religaSozinho() || !temOTempoTodo()) return;
        Log.i(TAG, "o tempo todo dado com a coleta ligada: a casca a inicia de novo, para ela voltar sozinha no dia");
        servico.iniciar();
    }

    private boolean temOTempoTodo() {
        return ServicoDaEquipe.temOTempoTodo(app);
    }

    // R15: logo depois do termo aceito, da localização dada e da resposta ao pedido do tempo todo (a coleta que liga, ou
    // que já está ligada), a janela do próprio Android que pede a bateria sem restrição, uma vez por conta e por termo. A
    // marca sai quando a janela abriu: recusada (ou só fechada), ela não volta; a que não abriu (a tela saindo) fica por
    // pedir. Com o app já liberado, nada a pedir, e a marca sai também (a liberação tirada depois nos ajustes não traz a
    // janela fora do fluxo do termo).
    private void pedirABateria() {
        if (!aVista || esperandoOTecnico || bateriaACaminho || !chave.bateriaPorPedir()) return;
        if (liberadoDaBateria()) {
            chave.bateriaPedida();
            return;
        }
        bateriaACaminho = true;
        Log.i(TAG, "a casca pede ao Android a bateria sem restrição, logo depois do termo e da localização");
        naTela(() -> {
            boolean abriu = tela.pedirSemRestricaoDeBateria();
            trabalhar(() -> {
                bateriaACaminho = false;
                if (abriu) chave.bateriaPedida();
            });
        });
    }

    private boolean liberadoDaBateria() {
        return AjustesDaColeta.bateriaSemRestricao(app);
    }

    // R9: a conferência que o cofre não deixou fazer, de novo na volta ao app. A conta guardada que se mostra de outra
    // conta faz aqui a troca de conta da R5, fora do envio do serviço; com o cofre ainda fora, nada muda.
    private void reconferirNaVolta() {
        String conta = chave.contaDaPagina();
        if (conta == null) return;
        if (deOutraConta(conta)) {
            esquecerAContaAnterior();
            return;
        }
        try {
            if (chave.reconferir()) Log.i(TAG, "a conta da chave da equipe conferida de novo na volta ao app: o envio sai");
        } catch (RuntimeException e) {
            Log.w(TAG, "a conta da chave da equipe ainda não pôde ser conferida (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void mostrarOTermo() {
        if (esperandoOTecnico) return;
        esperandoOTecnico = true;
        Log.i(TAG, "o termo de uso da localização, antes de ligar a coleta");
        naTela(() -> {
            if (!tela.mostrarTermo(() -> trabalhar(this::termoAceito))) trabalhar(this::termoNaoApareceu);
        });
    }

    // O Li e aceito: aceito pela conta da chave de agora, e o pedido do Android logo em seguida, se faltar a localização
    // (também para quem a negou antes nesta tela: a chave nova é de outra conta). O aceite deixa por pedir o tempo todo e
    // a janela da bateria (ChaveDaEquipe.aceitarTermo), que vêm quando a coleta liga, com a localização dada.
    private void termoAceito() {
        esperandoOTecnico = false;
        chave.aceitarTermo();
        pedirDepoisDoTermo = true;
        reavaliarAgora();
        // Com a localização já dada nada foi pedido: o pedido não fica guardado para quando ela for tirada mais tarde
        // nesta tela (aí só o Nexus fica sabendo, e o Antes de começar pede na próxima abertura).
        if (tem(PRECISA)) pedirDepoisDoTermo = false;
    }

    // O termo que não apareceu (a tela saindo): ninguém vai responder, e a próxima volta ao app mostra de novo.
    private void termoNaoApareceu() {
        esperandoOTecnico = false;
    }

    // Logo depois do aceite, o pedido do Android, sem tela nossa antes; fora dele, só o Nexus fica sabendo (quem pede a
    // cada abertura é o Antes de começar da MainActivity, e o Android que não mostra mais o pedido também cai aqui).
    private void pedirALocalizacao() {
        if (esperandoOTecnico) return; // a resposta dele decide
        if (!pedirDepoisDoTermo) {
            avisarSemPermissao("sem a permissão de localização");
            return;
        }
        pedirDepoisDoTermo = false;
        esperandoOTecnico = true;
        Log.i(TAG, "a casca pede a localização durante o uso, logo depois do termo");
        naTela(() -> {
            if (!tela.pedir(A_LOCALIZACAO)) trabalhar(this::pedidoNaoApareceu);
        });
    }

    // O pedido do Android que não abriu (a tela saindo): a próxima volta ao app pede de novo.
    private void pedidoNaoApareceu() {
        esperandoOTecnico = false;
        pedirDepoisDoTermo = true;
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE);
    }

    /** O "sem" ao Nexus, no máximo a cada 30 minutos; {@code falta} é o que o log diz que falta. */
    private void avisarSemPermissao(String falta) {
        long agora = relogio.getAsLong();
        SharedPreferences prefs = prefs();
        long ultimo;
        try {
            ultimo = prefs.getLong(CHAVE_SEM_PERMISSAO, -1L);
        } catch (ClassCastException e) {
            ultimo = -1L;
        }
        // A marca "do futuro" (a hora do aparelho voltou) não segura: seguraria pelo tempo que a hora voltou.
        if (ultimo >= 0 && ultimo <= agora && agora - ultimo < INTERVALO_SEM_PERMISSAO_MS) return;
        EnvioDePontos.Resultado r = envio.avisarSemPermissao();
        // O que o Nexus respondeu conta (também a recusa: mandar de novo não muda a resposta); a rede que caiu, não.
        if (r == EnvioDePontos.Resultado.ENVIADO || r == EnvioDePontos.Resultado.PARAR
                || r == EnvioDePontos.Resultado.DESCARTADO) {
            prefs.edit().putLong(CHAVE_SEM_PERMISSAO, agora).apply();
        }
        switch (r) {
            case ENVIADO:
                Log.i(TAG, falta + ": o Nexus sabe");
                break;
            case PARAR:
                // O 401 ou o coleta:false: o EnvioDePontos já apagou a chave.
                servico.parar();
                Log.i(TAG, falta + ": o Nexus desligou a coleta neste aparelho");
                break;
            case DESCARTADO:
                Log.i(TAG, falta + ": o Nexus recusou o aviso");
                break;
            case SEM_REDE:
                Log.i(TAG, falta + ": o aviso ao Nexus fica para a próxima volta ao app");
                break;
            default:
                // O NADA: a página ainda não conferiu a conta da chave, e o aviso espera como o lote.
                Log.i(TAG, falta + ": o aviso ao Nexus espera a página conferir a conta");
                break;
        }
    }

    // A thread do envio. O erro vai ao log só com o nome da classe: a mensagem de uma exceção do disco ou da cifra pode
    // citar o que estava sendo gravado.
    private void trabalhar(Runnable tarefa) {
        try {
            trabalho.execute(() -> {
                try {
                    tarefa.run();
                } catch (RuntimeException e) {
                    Log.w(TAG, "a casca da equipe falhou (" + e.getClass().getSimpleName() + ")");
                }
            });
        } catch (RejectedExecutionException e) {
            // nunca: a thread do processo não é desligada
        }
    }

    private void naTela(Runnable acao) {
        try {
            ui.execute(acao);
        } catch (RejectedExecutionException e) {
            // a tela saindo: o que não apareceu agora a próxima abertura decide
        }
    }

    private void pararDeEsperar() {
        esperandoOTecnico = false;
        naTela(tela::fechar);
    }

    private void limparAFila() {
        try {
            fila.limpar();
        } catch (RuntimeException e) {
            Log.w(TAG, "a fila da equipe não foi apagada (" + e.getClass().getSimpleName() + ")");
        }
    }

    // O que está guardado é de outra conta que a da página. A conta guardada é a dona da chave e da fila, e sobrevive ao
    // 401 (que apaga só a chave): outra conta guardada, com a chave ou sem ela, é a troca de conta. Sem conta guardada, a
    // chave ou a fila que sobraram não têm dona que se saiba (a chave de antes da conta, o ponto que chegou no meio do
    // Sair) e saem também; sem nada guardado, nada a tirar. Com o cofre que não respondeu agora, nada: a chave e a conta
    // continuam guardadas, e tirá-las revogaria à toa a chave de quem pode ser a mesma conta; a próxima carga confere de
    // novo.
    private boolean deOutraConta(String conta) {
        String dona;
        try {
            dona = chave.conta();
        } catch (RuntimeException e) {
            Log.w(TAG, "a conta da chave da equipe não pôde ser lida agora (" + e.getClass().getSimpleName() + ")");
            return false;
        }
        if (dona != null) return !conta.equals(dona);
        return chave.temChaveGuardada() || filaComPontos();
    }

    // A fila que não se lê agora conta como com pontos: sem conta guardada, o que estiver nela não tem dona, e tirá-la (se
    // der) não perde nada de quem se saiba.
    private boolean filaComPontos() {
        try {
            return fila.tamanho() > 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "a fila da equipe não pôde ser lida agora (" + e.getClass().getSimpleName() + ")");
            return true;
        }
    }

    // A troca de conta no aparelho: o trabalho do Sair da anterior (sem chave guardada, como depois do 401, não há o que
    // revogar: o revogar só apaga). O que falhar nele não segura o que vem depois (a resposta à página, a chave nova).
    private void esquecerAContaAnterior() {
        Log.i(TAG, "outra conta no aparelho: a coleta, a fila e a chave da anterior saem, como no Sair dela");
        try {
            esquecerAConta();
        } catch (RuntimeException e) {
            Log.w(TAG, "a chave da conta anterior não saiu (" + e.getClass().getSimpleName() + ")");
        }
    }

    // O trabalho do Sair, na thread do envio. A coleta para (no Sair, de novo: a decisão que corria na thread quando ele
    // chegou já tinha passado pela marca, e o iniciar dela pode ter valido depois do parar de lá) antes da fila e da rede
    // do revogar (até 30 s com o sinal fraco); o termo aberto sai; a fila sai (os pontos desta conta não sobem com a
    // chave da próxima); a chave é revogada no Nexus e apagada daqui, com a conta e o termo aceito, e a marca dos 30
    // minutos vai junto. Só lança se a chave não sair do disco.
    private void esquecerAConta() {
        // O parar e o fechar do termo que lançam não seguram a fila nem a chave (que é o que o Sair garante).
        pararAColeta();
        try {
            pararDeEsperar();
        } catch (RuntimeException e) {
            esperandoOTecnico = false;
            Log.w(TAG, "o diálogo da equipe não fechou agora (" + e.getClass().getSimpleName() + ")");
        }
        pedirDepoisDoTermo = false;
        limparAFila();
        try {
            envio.revogar(); // pede ao Nexus para revogar e apaga a chave daqui de qualquer jeito
        } catch (RuntimeException e) {
            // O cofre do aparelho que não leu a chave agora: o revogar lança antes de mandá-la e antes de apagá-la. A do
            // Nexus vence pela troca ou pela validade; a daqui sai abaixo assim mesmo.
            Log.w(TAG, "a chave da equipe não foi revogada no Nexus (" + e.getClass().getSimpleName() + ")");
        }
        chave.apagar();
        esquecerAMarcaSemPermissao();
    }

    // O Sair de uma partida de antes que o processo não deixou terminar (a marca do ChaveDaEquipe#saindo): termina agora,
    // antes de qualquer decisão ou resposta com a chave de quem saiu, como o trabalho dele teria feito. O Sair desta
    // partida, ainda na thread, não conta: o trabalho dele vem atrás. True se terminou um.
    private boolean terminarOSairDeAntes() {
        if (saidasPendentes.get() > 0 || !chave.saidaPendente()) return false;
        Log.i(TAG, "o Sair da partida de antes não terminou: a chave de quem saiu é revogada e apagada agora");
        try {
            esquecerAConta();
        } catch (RuntimeException e) {
            Log.w(TAG, "a chave de quem saiu não saiu agora (" + e.getClass().getSimpleName() + ")");
        }
        return true;
    }

    // A marca dos 30 minutos é do aviso da conta que saiu: a seguinte avisa o Nexus que falta a permissão sem esperar por
    // ela.
    private void esquecerAMarcaSemPermissao() {
        prefs().edit().remove(CHAVE_SEM_PERMISSAO).apply();
    }

    // O parar do serviço que lança (nunca no de verdade, que só posta à thread principal) fica no log: o que vem depois
    // dele (a fila, a chave, a marca do Sair) não depende dele.
    private void pararAColeta() {
        try {
            servico.parar();
        } catch (RuntimeException e) {
            Log.w(TAG, "a coleta da equipe não parou agora (" + e.getClass().getSimpleName() + ")");
        }
    }

    private boolean tem(String permissao) {
        return ContextCompat.checkSelfPermission(app, permissao) == PackageManager.PERMISSION_GRANTED;
    }

    /** O {@link ServicoDaEquipe} de verdade. */
    static final class ServicoDoAndroid implements Servico {
        private final Context app;

        ServicoDoAndroid(Context app) {
            this.app = app;
        }

        @Override
        public void iniciar() {
            ServicoDaEquipe.iniciar(app);
        }

        @Override
        public void parar() {
            ServicoDaEquipe.parar(app);
        }

        @Override
        public boolean rodando() {
            return ServicoDaEquipe.rodando();
        }

        @Override
        public boolean notificacaoAVista() {
            return ServicoDaEquipe.notificacaoAVista(app);
        }

        @Override
        public boolean religaSozinho() {
            return ServicoDaEquipe.religaSozinho();
        }
    }

    /**
     * O termo nesta activity e os pedidos ao Android (a localização e o tempo todo) pelo launcher do plugin. Só na thread
     * de UI. Um diálogo por vez: o novo tira o anterior.
     */
    static final class TelaDaActivity implements Tela {
        private final Activity activity;
        private final Consumer<String[]> pedido;
        private AlertDialog aberto;

        TelaDaActivity(Activity activity, Consumer<String[]> pedido) {
            this.activity = activity;
            this.pedido = pedido;
        }

        // O texto vai no setView, e não no setMessage: é o TermoDaEquipe que o rola com o botão à vista e que só liga o Li
        // e aceito depois do fim (R11). O botão nasce sem clique (o do AlertDialog fecharia o termo mesmo desligado): o
        // clique é o do travar, que fecha e aceita só com ele ligado.
        @Override
        public boolean mostrarTermo(Runnable aceitar) {
            if (!ativa()) return false;
            AlertDialog.Builder montar = new AlertDialog.Builder(activity);
            View termo = TermoDaEquipe.vista(montar.getContext());
            AlertDialog dialogo = montar
                .setTitle(R.string.casca_equipe_termo_titulo)
                .setView(termo)
                .setCancelable(false)
                .setPositiveButton(R.string.casca_equipe_termo_aceitar, null)
                .create();
            if (!mostrar(dialogo)) return false;
            TermoDaEquipe.travar(termo, dialogo.getButton(AlertDialog.BUTTON_POSITIVE), () -> {
                fechar();
                aceitar.run();
            });
            return true;
        }

        @Override
        public boolean pedir(String[] permissoes) {
            if (!ativa()) return false;
            try {
                pedido.accept(permissoes);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "o pedido ao Android não abriu (" + e.getClass().getSimpleName() + ")");
                return false;
            }
        }

        // O BatteryLife do lint avisa que a loja do Google só aceita esta janela em poucos casos: o Nexus Field não é da
        // loja (sai pela Release do GitHub), e a janela é decisão do dono (R15).
        @SuppressLint("BatteryLife")
        @Override
        public boolean pedirSemRestricaoDeBateria() {
            if (!ativa()) return false;
            Intent janela = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(janela);
            } catch (ActivityNotFoundException | SecurityException e) {
                // O Android sem esta janela (há fabricante que a tira): nada a tentar de novo, e o guia de instalação
                // diz o caminho nos ajustes.
                Log.w(TAG, "a janela da bateria sem restrição não abriu (" + e.getClass().getSimpleName() + ")");
            }
            return true;
        }

        @Override
        public void fechar() {
            AlertDialog dialogo = aberto;
            aberto = null;
            if (dialogo == null) return;
            try {
                dialogo.dismiss();
            } catch (RuntimeException e) {
                // a janela já foi embora com a activity
            }
        }

        private boolean mostrar(AlertDialog dialogo) {
            fechar();
            try {
                dialogo.show();
            } catch (RuntimeException e) {
                Log.w(TAG, "o termo da equipe não abriu (" + e.getClass().getSimpleName() + ")");
                return false;
            }
            aberto = dialogo;
            return true;
        }

        private boolean ativa() {
            return !activity.isFinishing() && !activity.isDestroyed();
        }
    }
}
