package br.com.gridco.campo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

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
 * {@link #decidir}, pura; o resto só a executa.
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
 *       a aproximada juntas (do Android 12 em diante, a precisa pedida sozinha é ignorada), sem tela nossa antes. A o tempo
 *       todo não é pedida: o serviço do tipo localização, iniciado com o app à vista, mantém a durante o uso enquanto roda,
 *       também com o app fechado. Fora do aceite quem pede a localização é o "Antes de começar" da MainActivity, a cada
 *       abertura (dois pedidos de uma vez empilhariam as telas); negada, ou negada de vez (o Android não mostra mais o
 *       pedido), nenhuma tela nossa a mais: o Nexus sabe, e o guia diz como liberar nos ajustes. Vale a precisa: a
 *       aproximada erra por quilômetros, e o ponto no mapa e o Abrir rota precisam dele.</li>
 *   <li><b>O serviço só liga com a tela à vista</b> ({@link #aTelaFicouAVista}, do onResume ao onStop): a decisão que roda
 *       na thread do envio depois de o técnico sair do app (a chave que o Nexus deu com o app já no fundo, a volta ao app
 *       atrás de um envio pendurado) não o liga. Iniciado com o app no fundo, ele não teria a localização durante o uso do
 *       Android 11 em diante, e do 12 em diante o Android recusa o primeiro plano: a notificação prometeria uma coleta que
 *       não acontece. A próxima volta ao app liga. A coleta ligada segue com a tela fora da vista.</li>
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
 *   <li><b>Nada sai em nome de uma conta que a página não conferiu</b> ({@link ChaveDaEquipe#conferida}): a tela que
 *       nasce ({@link #aTelaAbriu}) volta a "não conferida", e na partida a frio a coleta liga com a chave guardada e
 *       guarda na fila (a abertura sem sinal também coleta); o envio, o aviso sem permissão inclusive, espera até o pedido
 *       do aparelho achar a chave da mesma conta ou a chave nova entrar. O cofre que não lê a conta não confere: a
 *       resposta diz que há a chave, e o envio e a volta ao app conferem de novo até o cofre voltar (R9); a conta guardada
 *       que então se mostra de outra conta faz a troca de conta na volta ao app. O Sair e a troca de conta voltam a "não
 *       conferida"; a revogação do Sair não espera.</li>
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
    }

    /**
     * O que o técnico vê e responde: o termo e o pedido da localização ao Android ({@link TelaDaActivity}). Tudo na thread
     * de UI. O que devolve false não apareceu (a tela saindo): a casca não fica esperando uma resposta que não vem.
     */
    interface Tela {
        /** O termo de uso da localização, que não fecha tocando fora nem pelo Voltar; o Li e aceito roda {@code aceitar}. */
        boolean mostrarTermo(Runnable aceitar);

        /** Pede as permissões ao Android; a resposta volta pelo {@link EquipeNaCasca#aposOPedido}. */
        boolean pedir(String[] permissoes);

        /** Tira da tela o termo que estiver aberto (o Sair, a coleta que deixou de valer, a tela saindo). */
        void fechar();
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

    /** A resposta do Android ao pedido da localização (o launcher do plugin, na thread de UI). */
    void aposOPedido(Map<String, Boolean> resultado) {
        trabalhar(() -> {
            esperandoOTecnico = false;
            if (resultado == null || resultado.isEmpty()) {
                // O pedido interrompido (a tela saiu no meio): o técnico não respondeu, e a próxima volta ao app pergunta.
                pedirDepoisDoTermo = true;
                return;
            }
            Log.i(TAG, "a localização precisa " + (tem(PRECISA) ? "dada" : "não dada"));
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
     * A tela que nasce (o load do plugin): a conta conferida antes dela não vale até a página desta tela conferir (R8),
     * também no processo que sobreviveu à noite.
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
        if (chave.precisaReconferir()) reconferirNaVolta();
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
                break;
        }
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
    // (também para quem a negou antes nesta tela: a chave nova é de outra conta).
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
    }

    /**
     * O termo nesta activity e o pedido ao Android pelo launcher do plugin. Só na thread de UI. Um diálogo por vez: o novo
     * tira o anterior.
     */
    static final class TelaDaActivity implements Tela {
        private final Activity activity;
        private final Consumer<String[]> pedido;
        private AlertDialog aberto;

        TelaDaActivity(Activity activity, Consumer<String[]> pedido) {
            this.activity = activity;
            this.pedido = pedido;
        }

        @Override
        public boolean mostrarTermo(Runnable aceitar) {
            if (!ativa()) return false;
            return mostrar(new AlertDialog.Builder(activity)
                .setTitle(R.string.casca_equipe_termo_titulo)
                .setMessage(R.string.casca_equipe_termo_texto)
                .setCancelable(false)
                .setPositiveButton(R.string.casca_equipe_termo_aceitar, (d, qual) -> aceitar.run())
                .create());
        }

        @Override
        public boolean pedir(String[] permissoes) {
            if (!ativa()) return false;
            try {
                pedido.accept(permissoes);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "o pedido da localização não abriu (" + e.getClass().getSimpleName() + ")");
                return false;
            }
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
