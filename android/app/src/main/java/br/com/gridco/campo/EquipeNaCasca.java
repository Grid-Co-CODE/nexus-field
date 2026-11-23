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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
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
 *   <li><b>Antes de a coleta ligar, uma tela nossa só: o termo de uso da localização</b> (R10, o pedido do dono: "essa
 *       quantidade de telas e avisos atrapalham muito a experiência"), uma vez por conta ({@link ChaveDaEquipe#termoAceito}:
 *       a chave nova da mesma conta não o pede de novo, outra conta no aparelho pede). Um botão só, o Li e aceito: não fecha
 *       tocando fora nem pelo Voltar, e sem o aceite nada coleta (a próxima abertura mostra o termo de novo). A outra tela
 *       nossa é o lembrete diário, abaixo, que só vem com a coleta já ligada.</li>
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
 *       em primeiro plano com o app no fundo (é uma das exceções da regra), e o HyperOS fecha menos o app.</li>
 *   <li><b>O Início automático do Xiaomi</b> (Task 14, o pedido do dono em 10/11/2026: "claro, quando o cara instala já
 *       tem que ter tudo pronto"): depois da bateria (a janela dela já voltou, ou não havia o que pedir), num Xiaomi, Redmi
 *       ou POCO com ele desligado ({@link InicioAutomatico#estado}), a tela do HyperOS, ou os detalhes do app quando ela
 *       não abre ({@link Tela#pedirInicioAutomatico}), sem tela nossa antes: o termo e o aviso do que falta dizem o que
 *       fazer. Uma vez por conta e por termo ({@link ChaveDaEquipe#inicioAutomaticoPorPedir}), como a bateria. Caso: na
 *       prova da 1.2.2, sem ele o HyperOS não deixou o app partir pelo aviso de app atualizado ("process is not permitted
 *       to auto start"), e a coleta não voltou; ligado, ela voltou em 9 s.</li>
 *   <li><b>O aviso do que falta</b> ({@link AvisoDoQueFalta}, o mesmo pedido: "se algo ficar pendente tem que ter
 *       notificação 'faltando tal coisa'"): conferido no fim de cada decisão, mas mostrado só sem um pedido do Android
 *       na frente do técnico (no meio dos pedidos ele está liberando, e o aviso listaria o que a casca ainda vai pedir).</li>
 *   <li><b>O lembrete diário do que falta liberar</b> (Task 15, 1.2.5, o pedido do dono em 16/11/2026: "Eu estou tendo
 *       pouca adesão na parte dos técnicos. Ou eles não instalam, ou quando eles instalam, eles não marcam lá o permit o
 *       tempo todo"; recusados na instalação, o tempo todo, a bateria e o Início automático não eram pedidos de novo, e o
 *       aviso da barra é fácil de ignorar): uma vez por dia do celular, na volta ao app com a coleta ligando ou ligada e a
 *       tela à vista, se falta um dos três, a tela nossa <b>Falta liberar no celular</b>, com o Agora não e o Liberar agora
 *       ({@link #lembrarSeFalta}). Não aparece com algo na frente do técnico (um pedido, uma janela, outro diálogo da casca,
 *       o Atualizador no ar: {@link TelaDaActivity#mostrarLembrete} confere o foco da janela e o {@link Atualizador#noAr}),
 *       com a instalação ainda pedindo ({@link ChaveDaEquipe#ajustesPorPedir}), no dia em que ela já pediu
 *       ({@link ChaveDaEquipe#lembrado}) nem com a trava (abaixo) no ar, que cobre o caso. O Liberar agora é o mesmo da
 *       trava ({@link #liberarAgora}).</li>
 *   <li><b>A trava do App de Campo até o celular estar pronto</b> (Task 16, 1.2.7, o pedido do dono em 23/11/2026: "a gente
 *       precisa obrigar eles a aceitarem isso [...] A gente não pode ficar só dependendo da boa vontade deles"; os celulares
 *       são da empresa, e o lembrete diário e o aviso da barra não bastaram): com a chave, o termo de agora e nenhum Sair
 *       por terminar ({@link AvisoDoInicio#vale}), a qualquer hora do dia, se falta a localização precisa, o Permitir o
 *       tempo todo (do Android 10 em diante), a notificação à vista, a bateria sem restrição ou o Início automático (só o
 *       desligado), a tela nossa de tela cheia por cima da WebView, que não fecha e cujo Voltar só manda o app para o fundo
 *       ({@link TelaDaActivity#mostrarTrava}), com um botão só, o Liberar agora ({@link #conferirATrava}). Ela não aparece
 *       no meio do termo e do fluxo da instalação, pela regra do lembrete, e sai sozinha com tudo liberado. O
 *       <b>Liberar agora</b> reaproveita os pedidos que já existiam, sem uma segunda cópia: as três marcas da instalação
 *       por pedir de novo, mais a localização e a notificação, e a decisão pede só o que falta, um por vez, na ordem da
 *       lista ({@link #seguirOLiberar}). Com o Android que não pergunta mais ({@link Tela#aindaPergunta}), o recuo do
 *       pedido abre no lugar dele (os detalhes do app; os ajustes de notificação, para a notificação), e a recusa que vem
 *       na hora ({@link #RECUSA_NA_HORA_MS}) também cai nele.</li>
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
     * O reforço do recuo no Liberar agora (Task 15; desde a Task 16, também na localização e na notificação). Quem decide
     * primeiro é o {@link Tela#aindaPergunta}: com o pedido já feito antes (na instalação, ou pelo Antes de começar), o
     * false é o "não perguntar de novo" (do Android 11 em diante, a partir da segunda recusa), e o recuo abre sem pedir.
     * Este tempo pega o que escapar dele: a recusa que chega antes disto não passou pela mão do técnico. O relógio sozinho
     * não bastava (a revisão): num celular simples o PermissionController parte a frio, e a recusa automática pode passar
     * de 1,5 s. A pessoa que lê a tela do Android e escolhe leva mais que isso, e a que escolher rápido demais vê o recuo,
     * onde também se libera.
     */
    static final long RECUSA_NA_HORA_MS = 1_500L;
    // Sem pedido a medir (o pedidoEm).
    private static final long SEM_PEDIDO = Long.MIN_VALUE;

    /**
     * O pedido do Android logo depois do aceite: a localização durante o uso, a precisa e a aproximada juntas (do Android
     * 12 em diante a precisa pedida sozinha é ignorada, e a tela do Android oferece as duas).
     */
    static final String[] A_LOCALIZACAO = {
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    };
    private static final String PRECISA = Manifest.permission.ACCESS_FINE_LOCATION;
    // A permissão é do Android 13 e o minSdk é 24: só é pedida do 13 em diante (pedirANotificacao), como na MainActivity.
    @SuppressLint("InlinedApi")
    private static final String A_NOTIFICACAO = Manifest.permission.POST_NOTIFICATIONS;

    /**
     * Os pedidos de permissão que a casca faz ao Android pelo launcher do plugin, com a resposta pelo {@link #aposOPedido}:
     * a localização (logo depois do termo, ou no Liberar agora), o Permitir o tempo todo (R15) e a notificação (Task 16, só
     * do Android 13 em diante, e só no Liberar agora: na instalação quem a pede é o Antes de começar da MainActivity).
     */
    enum Pedido {
        LOCALIZACAO(A_LOCALIZACAO),
        TEMPO_TODO(ServicoDaEquipe.O_TEMPO_TODO),
        NOTIFICACAO(A_NOTIFICACAO);

        final String[] permissoes;

        Pedido(String... permissoes) {
            this.permissoes = permissoes;
        }

        // A resposta que chega a uma tela que não sabe do pedido (do Android 11 em diante o do tempo todo leva aos ajustes
        // do app, e o processo pode morrer lá) é reconhecida pelo nome da permissão; a vazia, sem nome, é a da localização,
        // como era.
        static Pedido daResposta(Map<String, Boolean> resultado) {
            if (resultado != null && resultado.containsKey(ServicoDaEquipe.O_TEMPO_TODO)) return TEMPO_TODO;
            if (resultado != null && resultado.containsKey(A_NOTIFICACAO)) return NOTIFICACAO;
            return LOCALIZACAO;
        }
    }

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
     * O que o técnico vê e responde: o termo, os pedidos da localização, do tempo todo e da notificação ao Android, a janela
     * da bateria do Android, a tela do Início automático do HyperOS, o lembrete diário, a trava, os detalhes do app e os
     * ajustes de notificação ({@link TelaDaActivity}). Tudo na thread de UI. O que devolve false não apareceu (a tela
     * saindo): a casca não fica esperando uma resposta que não vem.
     */
    interface Tela {
        /**
         * A trava do App de Campo até o celular estar pronto (Task 16): a tela nossa de tela cheia por cima da WebView, com
         * o título, o {@code texto} com a lista do que falta, o texto menor e o Liberar agora, que roda {@code liberar}. Não
         * fecha tocando nela, e o Voltar só manda o app para o fundo. A que já está no ar só troca o texto e o Liberar
         * agora; a que vai aparecer espera ter nada na frente do técnico, pela regra do lembrete, mas sem desistir: a
         * decisão seguinte troca o texto da que espera, e a tela que sai da vista larga a espera.
         */
        void mostrarTrava(String texto, Runnable liberar);

        /** Tira a trava do ar (tudo liberado, a conta fora da coleta), e a que esperava não aparece mais. */
        void tirarTrava();

        /**
         * Os ajustes de notificação do Nexus Field (Task 16), o recuo do pedido da notificação, ou os detalhes do app quando
         * eles não abrem. False se nenhum abriu (a tela saindo, o aparelho sem os dois).
         */
        boolean abrirOsAjustesDeNotificacao();

        /**
         * O lembrete diário do que falta liberar (Task 15): o título, o {@code texto} com a lista, o Agora não (só fecha) e
         * o Liberar agora, que roda {@code liberar}. Só com nada na frente do técnico. {@code depois} recebe true quando
         * ele apareceu e false quando não vai aparecer agora (outro diálogo ou pedido na frente, a tela saindo), sempre uma
         * vez.
         */
        void mostrarLembrete(String texto, Consumer<Boolean> depois, Runnable liberar);

        /**
         * Os detalhes do Nexus Field nos ajustes do Android (Task 15): o recuo do pedido do tempo todo (e, desde a Task 16,
         * do da localização) que o Android recusou sem mostrar nada. False se não abriram (a tela saindo, o aparelho sem a
         * tela).
         */
        boolean abrirOsDetalhesDoApp();

        /**
         * O shouldShowRequestPermissionRationale do Android (a revisão da Task 15): true depois de uma recusa, enquanto o
         * Android ainda mostra o pedido. Com o pedido já feito antes, o false é o "não perguntar de novo"; sem ele, é o
         * pedido que nunca foi feito.
         */
        boolean aindaPergunta(String permissao);

        /** O termo de uso da localização, que não fecha tocando fora nem pelo Voltar; o Li e aceito roda {@code aceitar}. */
        boolean mostrarTermo(Runnable aceitar);

        /** Pede as permissões ao Android; a resposta volta pelo {@link EquipeNaCasca#aposOPedido}. */
        boolean pedir(String[] permissoes);

        /**
         * Tira da tela o termo ou o lembrete que estiver aberto, e a trava (o Sair, a coleta que deixou de valer, a tela
         * saindo).
         */
        void fechar();

        /**
         * A janela do próprio Android que pede a bateria sem restrição para o Nexus Field (R15). A resposta não volta à
         * casca: recusada ou aceita, a coleta segue igual. False só com a tela saindo (a próxima volta ao app tenta).
         */
        boolean pedirSemRestricaoDeBateria();

        /**
         * A tela do Início automático do HyperOS, ou os detalhes do app quando ela não abre (Task 14). A resposta não volta
         * à casca: ligado ou não, a coleta segue igual, e o aviso do que falta diz. False só com a tela saindo.
         */
        boolean pedirInicioAutomatico();
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
    // O relógio que não volta nem pula com a hora do celular (o elapsedRealtime): quanto o Android levou para responder.
    private final LongSupplier monotonico;
    private final String versao;

    // Só na thread do trabalho. O termo ou o pedido do Android estão na frente do técnico: a resposta dele decide, e a
    // volta ao app não abre outro por cima.
    private boolean esperandoOTecnico = false;
    // O pedido da localização vem agora, uma vez: o termo acabou de ser aceito nesta tela, ou o Liberar agora foi tocado,
    // com a localização faltando. Fora disso a casca não pede (o Antes de começar da MainActivity pede a cada abertura).
    private boolean localizacaoPorPedir = false;
    // Só na thread do trabalho. O pedido da notificação vem no Liberar agora (Task 16), uma vez: posto no toque, com a
    // notificação faltando, e tirado quando a resposta chega ou os ajustes de notificação abrem.
    private boolean notificacaoPorPedir = false;
    // Só na thread do trabalho. O pedido na frente do técnico (ou null): a resposta dele, também a vazia do pedido
    // interrompido, é desse pedido.
    private Pedido pedindo;
    // Só na thread do trabalho. O pedido na frente veio do Liberar agora: a recusa na hora cai no recuo dele. No fluxo da
    // instalação, como antes, não.
    private boolean pedidoDoLiberar = false;
    // Só na thread do trabalho. A janela da bateria a caminho da tela: a decisão seguinte (a volta ao app, a chave que
    // chega) não abre outra por cima antes de a marca sair.
    private boolean bateriaACaminho = false;
    // Só na thread do trabalho. A tela do Início automático a caminho da tela, como a da bateria.
    private boolean inicioACaminho = false;
    // Só na thread do trabalho. O lembrete diário a caminho da tela (Task 15), até ela dizer se ele apareceu: a decisão
    // seguinte (a chave que a página entrega logo depois do onResume, na partida a frio) não manda outro.
    private boolean lembreteACaminho = false;
    // Só na thread do trabalho. Os ajustes do Android abertos no lugar de um pedido (os detalhes do app, ou os ajustes de
    // notificação: o recuo da Task 15 e da Task 16), até a tela sair da vista (a volta deles é a decisão seguinte): a
    // decisão do onResume que vem logo depois da recusa não abre outra tela por cima. Volatile: o aTelaSaiuDaVista o lê na
    // thread de UI.
    private volatile boolean ajustesACaminho = false;
    // Só na thread do trabalho. O Liberar agora (o da trava, Task 16, e o do lembrete, Task 15) em curso: a decisão pede o
    // que falta, um por vez, até não haver mais nada a pedir (seguirOLiberar).
    private boolean liberando = false;
    // Quando a tela mandou o pedido de agora ao Android (o monotonico, na thread de UI), ou SEM_PEDIDO.
    private volatile long pedidoEm = SEM_PEDIDO;
    // Só na thread do trabalho. Esta decisão mandou algo à frente do técnico (o termo, um pedido do Android, a janela da
    // bateria, a tela do Início automático, o lembrete diário, os ajustes): o aviso do que falta e a trava esperam a volta
    // seguinte.
    private boolean pediuAgora = false;
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
     * @param relogio    o relógio de parede (a janela das 6h às 22h, a marca dos 30 minutos e o dia do lembrete)
     * @param monotonico o relógio que não volta (o elapsedRealtime): mede a recusa do tempo todo que veio na hora
     * @param versao     o versionName, que a página manda ao Nexus como "NexusField/&lt;versao&gt;"
     */
    EquipeNaCasca(Context contexto, ChaveDaEquipe chave, FilaDePontos fila, EnvioDePontos envio, Servico servico,
            Tela tela, Executor trabalho, Executor ui, LongSupplier relogio, LongSupplier monotonico, String versao) {
        this.app = contexto.getApplicationContext();
        this.chave = chave;
        this.fila = fila;
        this.envio = envio;
        this.servico = servico;
        this.tela = tela;
        this.trabalho = trabalho;
        this.ui = ui;
        this.relogio = relogio;
        this.monotonico = monotonico;
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
            new TelaDaActivity(activity, pedido), pecas.execucao, ui, pecas.relogio, SystemClock::elapsedRealtime, versao);
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
     * A resposta do Android ao pedido da localização, ao do tempo todo ou ao da notificação (o launcher do plugin, na
     * thread de UI). A do tempo todo e a da notificação trazem o nome da permissão: assim vale também a que chega a uma
     * tela nova (do Android 11 em diante o pedido do tempo todo leva aos ajustes do app, e o processo pode morrer lá), que
     * não sabe do pedido.
     */
    void aposOPedido(Map<String, Boolean> resultado) {
        // A hora da resposta, aqui na thread de UI, contra a do pedido (pedir): a recusa que chegou antes de
        // RECUSA_NA_HORA_MS não passou pela mão do técnico.
        long respondidoEm = monotonico.getAsLong();
        trabalhar(() -> {
            Pedido qual = pedindo != null ? pedindo : Pedido.daResposta(resultado);
            boolean doLiberar = pedidoDoLiberar;
            long pedidoAntes = pedidoEm;
            pedidoEm = SEM_PEDIDO;
            esperandoOTecnico = false;
            pedindo = null;
            pedidoDoLiberar = false;
            if (resultado == null || resultado.isEmpty()) {
                // O pedido interrompido (a tela saiu no meio): o técnico não respondeu, e a próxima volta ao app pergunta
                // (o do tempo todo segue por fazer, e a janela da bateria espera por ele).
                naoRespondido(qual);
                return;
            }
            // Respondido, dado ou não: não se pede de novo até a próxima conta, o próximo termo ou o Liberar agora.
            respondido(qual);
            boolean dado = dado(qual);
            Log.i(TAG, nomeNoLog(qual) + (dado ? " dada" : " não dada"));
            if (doLiberar && !dado && pedidoAntes != SEM_PEDIDO && respondidoEm - pedidoAntes < RECUSA_NA_HORA_MS) {
                abrirORecuoNoLugar(qual);
                return;
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
        // Os ajustes do recuo (os detalhes do app, os de notificação) tiram a tela da vista: a volta deles é a decisão
        // seguinte, que segue para o próximo pedido. Pela thread do trabalho, atrás da decisão do onResume que veio logo
        // depois da recusa (ela entrou na fila antes de os ajustes abrirem, e não pode abrir outra tela por cima deles).
        if (ajustesACaminho) trabalhar(() -> ajustesACaminho = false);
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
        pediuAgora = false;
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
        // A coleta ligando, ou já ligada, com tudo em dia: é quando os pedidos de depois da localização valem, e o lembrete
        // diário também (Task 15).
        boolean comAColeta = false;
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
                // A ordem depois da localização (R15, Task 14): o tempo todo, depois da resposta dele a bateria e, depois
                // da volta dela, o Início automático.
                pedirOsAjustes();
                comAColeta = true;
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
                // Quem pede a notificação é o Antes de começar da MainActivity, uma vez, e o Liberar agora da trava (Task
                // 16, seguirOLiberar): aqui só o Nexus fica sabendo.
                if (rodando) servico.parar();
                avisarSemPermissao("sem a notificação fixa à vista");
                break;
            default:
                // O NADA dentro da janela é a coleta já ligada com tudo em dia: o serviço ligado antes do tempo todo passa
                // a pedir o religamento, e o pedido do tempo todo, a janela da bateria e a tela do Início automático que
                // não abriram (a tela saindo, ou a vez delas que ainda não tinha chegado) abrem agora.
                if (rodando) {
                    religarSozinhoDaquiEmDiante();
                    pedirOsAjustes();
                    comAColeta = true;
                }
                break;
        }
        seguirOLiberar();
        boolean travado = conferirATrava();
        if (comAColeta && !travado) lembrarSeFalta();
        // O aviso do que falta (Task 14), depois da decisão: com algo na frente do técnico, ele só sai (o que deixou de
        // valer), e aparece na volta seguinte. A trava não conta: com ela no ar, ele continua como estava (Task 16).
        AvisoDoQueFalta.conferir(app, chave, relogio.getAsLong(), !algoNaFrente());
    }

    // Algo desta casca na frente do técnico, ou a caminho dela: o termo ou um pedido do Android esperando a resposta, a
    // janela da bateria, a tela do Início automático, o lembrete diário ou os ajustes do recuo; ou mandado nesta decisão.
    private boolean algoNaFrente() {
        return pediuAgora || esperandoOTecnico || bateriaACaminho || inicioACaminho || lembreteACaminho
            || ajustesACaminho;
    }

    // Os ajustes que vêm depois da localização dada, nesta ordem e um por vez: o tempo todo, a bateria e o Início
    // automático. A janela da bateria mandada agora segura o Início automático até a volta dela: as duas telas abertas
    // juntas ficariam uma por cima da outra, e a de baixo só apareceria depois. Com os ajustes abertos pelo recuo de um
    // pedido (Task 15 e Task 16), nada até a volta deles.
    private void pedirOsAjustes() {
        if (ajustesACaminho) return;
        pedirOTempoTodo();
        if (!pedirABateria()) pedirOInicioAutomatico();
    }

    /**
     * O texto da trava (Task 16), com a lista do que falta juntada como no aviso do que falta: a vírgula entre os nomes e o
     * "e" antes do último.
     */
    static String textoDaTrava(Context contexto, List<String> faltas) {
        return contexto.getString(R.string.casca_equipe_trava_texto, AvisoDoQueFalta.emLista(faltas));
    }

    // Task 16, o pedido do dono em 23/11/2026 ("a gente precisa obrigar eles a aceitarem isso [...] A gente não pode ficar
    // só dependendo da boa vontade deles"; "Faz a trava"): os celulares são da empresa, e o lembrete diário e o aviso da
    // barra não bastaram. Com a conta na coleta (a chave, o termo de agora, nenhum Sair por terminar), a qualquer hora (o
    // que falta vale para a coleta de amanhã) e faltando algo da lista, a trava cobre o App de Campo; com tudo liberado, ou
    // a conta fora da coleta, ela sai sozinha. Supervisor, administrador e quem está fora da coleta não têm a chave, e
    // nunca a veem. Ela não aparece no meio do termo e do fluxo da instalação (os pedidos logo depois do Li e aceito): com
    // algo da casca na frente, espera a decisão seguinte, e a tela de verdade espera também o que a casca não conhece (a
    // regra do lembrete). Na tela, ela só aparece com a tela à vista; a que está no ar fica no ar com a tela no fundo. True
    // se a trava vale agora: o lembrete diário não aparece com ela, que cobre o caso dele.
    private boolean conferirATrava() {
        if (!AvisoDoInicio.vale(chave)) {
            naTela(tela::tirarTrava);
            return false;
        }
        List<String> faltas;
        try {
            faltas = oQueTrava();
        } catch (RuntimeException e) {
            // O sistema que não respondeu agora: a trava fica como está, e a decisão seguinte confere de novo.
            Log.w(TAG, "a trava do que falta não foi conferida agora (" + e.getClass().getSimpleName() + ")");
            return true;
        }
        if (faltas.isEmpty()) {
            naTela(tela::tirarTrava);
            return false;
        }
        if (!aVista || algoNaFrente()) return true;
        String texto = textoDaTrava(app, faltas);
        naTela(() -> tela.mostrarTrava(texto, () -> trabalhar(this::liberarAgora)));
        return true;
    }

    // Os mesmos ajustes que vão ao Nexus e ao aviso do que falta (o técnico, o Piloto e a trava dizem o mesmo), com a
    // notificação pelo serviço da casca: o mesmo notificacaoAVista do ServicoDaEquipe, que o decidir também confere.
    private List<String> oQueTrava() {
        JSONObject ajustes = AjustesDaColeta.ler(app, chave);
        try {
            ajustes.put("notificacao", servico.notificacaoAVista());
        } catch (JSONException e) {
            throw new IllegalStateException("ajustes inválidos");
        }
        return AvisoDoQueFalta.oQueTrava(app, ajustes);
    }

    // O Liberar agora (o da trava, Task 16, e o do lembrete, Task 15): os mesmos pedidos de depois do termo, nada de uma
    // segunda cópia, mais o da localização e o da notificação, que a trava também lista. As três marcas da instalação ficam
    // por pedir de novo, e as da localização e da notificação, se faltam; a decisão de agora pede pela ordem da trava (a
    // localização, o tempo todo, a notificação, a bateria e o Início automático), um por vez e só o que falta: cada pedido
    // sai sem abrir o que já está liberado. O toque com um pedido já na frente (o botão da trava não fecha com o toque,
    // como o de um diálogo) não pede de novo.
    private void liberarAgora() {
        if (algoNaFrente()) {
            Log.i(TAG, "o Liberar agora com um pedido já na frente: nada a pedir de novo");
            return;
        }
        chave.liberarDeNovo();
        liberando = true;
        localizacaoPorPedir = !tem(PRECISA);
        notificacaoPorPedir = !servico.notificacaoAVista();
        Log.i(TAG, "o Liberar agora: a casca pede o que falta, na ordem da trava");
        reavaliarAgora();
    }

    // O Liberar agora em curso, depois dos pedidos da decisão (o switch do reavaliarAgora): o primeiro que falta, na ordem
    // da trava. A localização (o mesmo pedido de logo depois do termo; dentro da janela, o PEDIR_PERMISSAO já o fez), o
    // tempo todo (só com a localização dada: sem a durante o uso o Android nem o mostra), a notificação, a bateria e o
    // Início automático: os da instalação, com a coleta ligada ou não, e o recusado não segura o seguinte. Nada mais a
    // pedir, com a tela à vista e nada na frente: o Liberar agora acabou, e a trava fica com o que ainda falta.
    private void seguirOLiberar() {
        if (!liberando) return;
        if (!AvisoDoInicio.vale(chave)) {
            terminarOLiberar();
            return;
        }
        if (!aVista || algoNaFrente()) return;
        if (localizacaoPorPedir && !tem(PRECISA)) {
            pedirAPrecisa();
            return;
        }
        localizacaoPorPedir = false;
        pedirOTempoTodo();
        if (pediuAgora || pedirANotificacao() || pedirABateria()) return;
        pedirOInicioAutomatico();
        if (pediuAgora) return;
        terminarOLiberar();
        Log.i(TAG, "o Liberar agora terminou: nada mais a pedir");
    }

    private void terminarOLiberar() {
        liberando = false;
        localizacaoPorPedir = false;
        notificacaoPorPedir = false;
    }

    // Task 16: a notificação no Liberar agora, uma vez. Do Android 13 em diante, sem a permissão, o pedido do Android (com
    // o recuo para os ajustes de notificação, o Android que não pergunta mais); com a permissão dada (ou antes do 13, em
    // que ela não existe), as notificações do Nexus Field ou o canal da equipe desligados: não há pedido que os ligue, e
    // os ajustes de notificação do app abrem direto. True se algo foi mandado à tela agora.
    private boolean pedirANotificacao() {
        if (!notificacaoPorPedir || !aVista || algoNaFrente()) return false;
        if (servico.notificacaoAVista()) {
            notificacaoPorPedir = false;
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !tem(A_NOTIFICACAO)) {
            Log.i(TAG, "a casca pede ao Android a notificação, pelo Liberar agora");
            pedir(Pedido.NOTIFICACAO);
            return true;
        }
        notificacaoPorPedir = false;
        Log.i(TAG, "a notificação dada com as notificações ou o canal da equipe desligados: os ajustes de notificação");
        abrirORecuoNoLugar(Pedido.NOTIFICACAO);
        return true;
    }

    // Um pedido de permissão ao Android pelo launcher do plugin, com a resposta pelo aposOPedido. No Liberar agora, o
    // Android que não pergunta mais (o "não perguntar de novo", do Android 11 em diante depois da segunda recusa)
    // recusaria sem mostrar nada, e o técnico que tocou ficaria sem tela nenhuma: quem decide é o
    // shouldShowRequestPermissionRationale (Tela.aindaPergunta, na thread de UI). A trava e o lembrete só vêm com o termo
    // aceito, e o pedido já foi feito (o Antes de começar, o de logo depois do termo, os da instalação): o false é o "não
    // perguntar de novo", e o recuo abre no lugar do pedido, que conta como feito. Na instalação, o mesmo false é o pedido
    // que nunca foi feito, e se pede.
    private void pedir(Pedido qual) {
        esperandoOTecnico = true;
        pediuAgora = true;
        pedindo = qual;
        pedidoDoLiberar = liberando;
        boolean doLiberar = liberando;
        naTela(() -> {
            if (doLiberar && !tela.aindaPergunta(qual.permissoes[0])) {
                // Aqui, na thread de UI, antes de abrir: o onStop dos ajustes já o vê, e solta o pedido seguinte na volta
                // deles.
                ajustesACaminho = true;
                boolean abriu = abrirORecuo(qual);
                trabalhar(() -> oAndroidNaoPerguntaMais(qual, abriu));
                return;
            }
            // A hora do pedido, para a recusa que vem na hora (aposOPedido, RECUSA_NA_HORA_MS).
            pedidoEm = monotonico.getAsLong();
            if (!tela.pedir(qual.permissoes)) trabalhar(() -> pedidoNaoApareceu(qual));
        });
    }

    // O recuo de cada pedido, na thread de UI: os detalhes do app (a localização e o tempo todo se liberam em Permissões,
    // Localização) ou os ajustes de notificação do app (com o recuo para os detalhes, na tela).
    private boolean abrirORecuo(Pedido qual) {
        return qual == Pedido.NOTIFICACAO ? tela.abrirOsAjustesDeNotificacao() : tela.abrirOsDetalhesDoApp();
    }

    // O pedido do Liberar agora que o Android recusou sem mostrar nada: o técnico tocou em Liberar agora e ficaria sem tela
    // nenhuma. O recuo abre, e o pedido seguinte espera a volta dele (ajustesACaminho), para não abrir uma tela por cima da
    // outra.
    private void abrirORecuoNoLugar(Pedido qual) {
        ajustesACaminho = true;
        pediuAgora = true;
        Log.i(TAG, nomeNoLog(qual) + ": a casca abre o recuo, os ajustes do app");
        naTela(() -> {
            if (!abrirORecuo(qual)) trabalhar(this::recuoNaoAbriu);
        });
    }

    // O recuo que não abriu (o aparelho sem a tela, a tela saindo): o fluxo segue agora, sem esperar uma volta que não vem.
    private void recuoNaoAbriu() {
        ajustesACaminho = false;
        reavaliarAgora();
    }

    // O Liberar agora com o Android que não pergunta mais (a revisão da Task 15): no lugar do pedido, o recuo, e o pedido
    // conta como feito. Caso: pelo relógio só, num celular simples o PermissionController parte a frio, a recusa automática
    // passa de RECUSA_NA_HORA_MS, e o técnico que negou duas vezes, o do pedido de 16/11/2026, nunca veria a tela do
    // Permitir o tempo todo. O pedido seguinte vem na volta do recuo; se ele não abriu, agora.
    private void oAndroidNaoPerguntaMais(Pedido qual, boolean abriu) {
        esperandoOTecnico = false;
        pedindo = null;
        pedidoDoLiberar = false;
        respondido(qual);
        if (abriu) {
            Log.i(TAG, "o Android não pergunta mais " + nomeNoLog(qual) + ": a casca abriu os ajustes do app");
            return;
        }
        recuoNaoAbriu();
    }

    // O pedido que não abriu (a tela saindo): a próxima volta ao app pede de novo.
    private void pedidoNaoApareceu(Pedido qual) {
        esperandoOTecnico = false;
        pedindo = null;
        pedidoDoLiberar = false;
        naoRespondido(qual);
    }

    // O pedido que o técnico não respondeu (não abriu, ou foi interrompido): o da localização volta a ficar por pedir; o do
    // tempo todo e o da notificação seguem por fazer, nas marcas deles.
    private void naoRespondido(Pedido qual) {
        if (qual == Pedido.LOCALIZACAO) localizacaoPorPedir = true;
    }

    // O pedido respondido, dado ou não (ou trocado pelo recuo): o do tempo todo sai da marca dele, até a próxima conta, o
    // próximo termo ou o Liberar agora; o da notificação, do Liberar agora, e o Antes de começar da MainActivity não a pede
    // de novo. O da localização já saiu quando foi pedido.
    private void respondido(Pedido qual) {
        if (qual == Pedido.TEMPO_TODO) {
            chave.tempoTodoPedido();
        } else if (qual == Pedido.NOTIFICACAO) {
            notificacaoPorPedir = false;
            MainActivity.marcarNotificacaoPedida(app);
        }
    }

    private boolean dado(Pedido qual) {
        switch (qual) {
            case TEMPO_TODO:
                return temOTempoTodo();
            case NOTIFICACAO:
                return tem(A_NOTIFICACAO);
            default:
                return tem(PRECISA);
        }
    }

    private static String nomeNoLog(Pedido qual) {
        switch (qual) {
            case TEMPO_TODO:
                return "a localização o tempo todo";
            case NOTIFICACAO:
                return "a notificação";
            default:
                return "a localização precisa";
        }
    }

    /**
     * O texto do lembrete diário (Task 15), com a lista do que falta juntada como no aviso do que falta: a vírgula entre os
     * nomes e o "e" antes do último.
     */
    static String textoDoLembrete(Context contexto, List<String> faltas) {
        return contexto.getString(R.string.casca_equipe_lembrete_texto, AvisoDoQueFalta.emLista(faltas));
    }

    // Task 15, o pedido do dono em 16/11/2026 ("Faz os dois"): uma vez por dia do celular, na volta ao app com a coleta
    // ligando ou ligada e a tela à vista, se falta o Permitir o tempo todo, a bateria sem restrição ou o Início automático
    // (só o desligado), o lembrete. Antes, recusado na instalação, nada pedia de novo até a próxima conta ou o próximo
    // termo, e só o aviso da barra lembrava. Não aparece com algo na frente do técnico, com a instalação ainda pedindo (as
    // marcas por pedir) nem no dia em que ela já pediu (o Li e aceito e cada pedido dela marcam o dia): o técnico que
    // acabou de recusar não o vê por cima. A localização precisa e a notificação não entram: sem elas a coleta nem liga, e
    // têm os pedidos e os diálogos delas. A marca do dia vai ao disco quando ele aparece, e não na escolha: o app fechado
    // com ele aberto não o traz de volta no mesmo dia.
    private void lembrarSeFalta() {
        if (!aVista || algoNaFrente() || chave.ajustesPorPedir() || !AvisoDoInicio.vale(chave)) return;
        long agora = relogio.getAsLong();
        TimeZone fuso = TimeZone.getDefault();
        if (!JanelaDaEquipe.dentro(agora, fuso)) return;
        String hoje = JanelaDaEquipe.dia(agora, fuso);
        if (hoje.equals(chave.diaDoLembrete())) return;
        List<String> faltas;
        try {
            // Os mesmos ajustes que vão ao Nexus e ao aviso do que falta: o técnico, o Piloto e o lembrete dizem o mesmo.
            faltas = AvisoDoQueFalta.oQueFaltaLiberar(app, AjustesDaColeta.ler(app, chave));
        } catch (RuntimeException e) {
            Log.w(TAG, "o lembrete do que falta não foi conferido agora (" + e.getClass().getSimpleName() + ")");
            return;
        }
        if (faltas.isEmpty()) return;
        String texto = textoDoLembrete(app, faltas);
        lembreteACaminho = true;
        pediuAgora = true;
        Log.i(TAG, "o lembrete do dia: falta liberar " + faltas.size() + " ajuste(s) no celular");
        naTela(() -> tela.mostrarLembrete(texto,
            apareceu -> trabalhar(() -> {
                lembreteACaminho = false;
                if (apareceu) chave.lembrado(hoje);
            }),
            () -> trabalhar(this::liberarAgora)));
    }

    // O dia de hoje, no relógio do celular, como já lembrado: o Li e aceito e os pedidos da instalação pedem tudo hoje, e o
    // lembrete diário fica para o dia seguinte (Task 15).
    private void lembradoHoje() {
        chave.lembrado(JanelaDaEquipe.dia(relogio.getAsLong(), TimeZone.getDefault()));
    }

    // R15: logo depois da localização dada e da coleta ligada, o pedido do Android da localização o tempo todo, sozinho
    // (o Android 10 mostra a tela dele; do 11 em diante ele leva aos ajustes do app), uma vez por conta e por termo. A
    // marca sai com a resposta do técnico (aposOPedido); a que não apareceu ou foi interrompida pede na próxima volta. Com
    // o tempo todo já dado (do Android 9 para trás, a localização dada), nada a pedir, e a marca sai também. Sem a
    // localização dada (o Liberar agora com ela recusada), nada: sem a durante o uso o Android nem mostra o pedido, e a
    // marca fica para quando ela vier. No Liberar agora, o recuo para os detalhes do app (pedir).
    private void pedirOTempoTodo() {
        if (!aVista || esperandoOTecnico || !chave.tempoTodoPorPedir() || !tem(PRECISA)) return;
        if (temOTempoTodo()) {
            chave.tempoTodoPedido();
            return;
        }
        lembradoHoje();
        Log.i(TAG, "a casca pede ao Android a localização o tempo todo, logo depois da localização durante o uso");
        pedir(Pedido.TEMPO_TODO);
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
    // janela fora do fluxo do termo, só o Liberar agora). True se a janela foi mandada à tela agora. Com os ajustes do
    // recuo a caminho (o Liberar agora), espera a volta deles.
    private boolean pedirABateria() {
        if (!aVista || esperandoOTecnico || bateriaACaminho || ajustesACaminho || !chave.bateriaPorPedir()) return false;
        if (liberadoDaBateria()) {
            chave.bateriaPedida();
            return false;
        }
        bateriaACaminho = true;
        pediuAgora = true;
        lembradoHoje();
        Log.i(TAG, "a casca pede ao Android a bateria sem restrição, logo depois do termo e da localização");
        naTela(() -> {
            boolean abriu = tela.pedirSemRestricaoDeBateria();
            trabalhar(() -> {
                bateriaACaminho = false;
                if (abriu) chave.bateriaPedida();
            });
        });
        return true;
    }

    private boolean liberadoDaBateria() {
        return AjustesDaColeta.bateriaSemRestricao(app);
    }

    // Task 14: depois da bateria (a janela dela já foi e voltou, ou não havia o que pedir), num Xiaomi com o Início
    // automático desligado, a tela do HyperOS, uma vez por conta e por termo, com a mesma regra da marca da bateria: sai
    // quando a tela abre (recusada, não volta), e a que não abriu (a tela saindo) abre na próxima volta. Fora do Xiaomi, sem
    // saber o estado ou com ele já ligado, nada a abrir, e a marca sai também (desligado depois nos ajustes, quem diz é o
    // aviso do que falta).
    private void pedirOInicioAutomatico() {
        if (!aVista || esperandoOTecnico || bateriaACaminho || inicioACaminho || ajustesACaminho
                || chave.bateriaPorPedir() || !chave.inicioAutomaticoPorPedir()) {
            return;
        }
        if (!Boolean.FALSE.equals(InicioAutomatico.estado(app))) {
            chave.inicioAutomaticoPedido();
            return;
        }
        inicioACaminho = true;
        pediuAgora = true;
        lembradoHoje();
        Log.i(TAG, "a casca abre a tela do Início automático do HyperOS, logo depois da bateria");
        naTela(() -> {
            boolean abriu = tela.pedirInicioAutomatico();
            trabalhar(() -> {
                inicioACaminho = false;
                if (abriu) chave.inicioAutomaticoPedido();
            });
        });
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
        pediuAgora = true;
        Log.i(TAG, "o termo de uso da localização, antes de ligar a coleta");
        naTela(() -> {
            if (!tela.mostrarTermo(() -> trabalhar(this::termoAceito))) trabalhar(this::termoNaoApareceu);
        });
    }

    // O Li e aceito: aceito pela conta da chave de agora, e o pedido do Android logo em seguida, se faltar a localização
    // (também para quem a negou antes nesta tela: a chave nova é de outra conta). O aceite deixa por pedir o tempo todo, a
    // janela da bateria e a tela do Início automático (ChaveDaEquipe.aceitarTermo), que vêm quando a coleta liga, com a
    // localização dada; o fluxo deles pede tudo hoje, e o lembrete diário fica para amanhã (Task 15).
    private void termoAceito() {
        esperandoOTecnico = false;
        chave.aceitarTermo();
        lembradoHoje();
        terminarOLiberar();
        localizacaoPorPedir = true;
        reavaliarAgora();
        // Com a localização já dada nada foi pedido: o pedido não fica guardado para quando ela for tirada mais tarde
        // nesta tela (aí só o Nexus fica sabendo, a trava diz, e o Antes de começar pede na próxima abertura).
        if (tem(PRECISA)) localizacaoPorPedir = false;
    }

    // O termo que não apareceu (a tela saindo): ninguém vai responder, e a próxima volta ao app mostra de novo.
    private void termoNaoApareceu() {
        esperandoOTecnico = false;
    }

    // Logo depois do aceite (ou no Liberar agora da trava), o pedido do Android, sem tela nossa antes; fora deles, só o
    // Nexus fica sabendo (quem pede a cada abertura é o Antes de começar da MainActivity, e o Android que não mostra mais o
    // pedido também cai aqui; no Liberar agora, os detalhes do app abrem no lugar dele).
    private void pedirALocalizacao() {
        if (esperandoOTecnico) return; // a resposta dele decide
        if (!localizacaoPorPedir) {
            avisarSemPermissao("sem a permissão de localização");
            return;
        }
        pedirAPrecisa();
    }

    private void pedirAPrecisa() {
        localizacaoPorPedir = false;
        Log.i(TAG, "a casca pede a localização durante o uso, "
            + (liberando ? "pelo Liberar agora" : "logo depois do termo"));
        pedir(Pedido.LOCALIZACAO);
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
        // O Liberar agora e os ajustes do recuo eram de quem saiu (o lembrete que esperava e a trava saíram com o fechar
        // acima).
        terminarOLiberar();
        pedindo = null;
        pedidoDoLiberar = false;
        ajustesACaminho = false;
        lembreteACaminho = false;
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
     * O termo nesta activity, os pedidos ao Android (a localização, o tempo todo e a notificação) pelo launcher do plugin,
     * a janela da bateria, a tela do Início automático, o lembrete diário, a trava, os detalhes do app e os ajustes de
     * notificação. Só na thread de UI. Um diálogo por vez: o novo tira o anterior. A trava não é um diálogo: é uma vista
     * no conteúdo da activity, por cima da WebView, e os diálogos ficam por cima dela.
     */
    static final class TelaDaActivity implements Tela {
        /**
         * Quanto o lembrete diário espera ter nada na frente do técnico (Task 15). Na partida a frio a decisão chega antes
         * do foco da janela (ela vem da thread do envio logo depois do onResume, e a janela só ganha o foco depois de
         * desenhada), e a conferência da versão nova do Atualizador pode estar na rede; com outro diálogo ou um pedido na
         * frente, o foco não vem. Passado isto, a decisão pode estar velha: o lembrete não aparece agora, e a próxima
         * volta ao app decide de novo.
         */
        static final long ESPERA_DO_LEMBRETE_MS = 3_000L;
        /** De quanto em quanto a espera do lembrete confere de novo se nada está na frente. */
        static final long PASSO_DA_ESPERA_MS = 100L;

        private final Activity activity;
        private final Consumer<String[]> pedido;
        private final Handler principal = new Handler(Looper.getMainLooper());
        private AlertDialog aberto;
        // O lembrete que está na tela (ou null): o de ontem que ficou aberto sai antes do de hoje.
        private AlertDialog lembreteAberto;
        // O lembrete que espera ter nada na frente: a conferência seguinte e quem espera a resposta (ou nulos).
        private Runnable conferirDeNovo;
        private Consumer<Boolean> depoisDaEspera;
        // A trava no ar (Task 16), ou null, e o Liberar agora dela (o da decisão mais nova).
        private View trava;
        private Runnable liberarDaTrava;
        // A trava que espera ter nada na frente: o texto e o Liberar agora dela (ou nulos), e a conferência seguinte.
        private String travaEsperando;
        private Runnable liberarEsperando;
        private Runnable conferirATravaDeNovo;
        // O Voltar com a trava no ar, que só manda o app para o fundo, ou null.
        private OnBackPressedCallback voltarComATrava;
        // As vistas que a trava cobre e o que elas eram para o TalkBack antes dela, para devolver quando ela sai.
        private final Map<View, Integer> acessibilidadeDeAntes = new HashMap<>();

        TelaDaActivity(Activity activity, Consumer<String[]> pedido) {
            this.activity = activity;
            this.pedido = pedido;
        }

        // Task 16: a trava aparece com nada na frente do técnico (nadaNaFrente, a regra do lembrete) e, ao contrário do
        // lembrete, não desiste: ela vale a qualquer hora, e o que falta não muda por esperar. A decisão seguinte troca o
        // texto da que espera. A tela que sai da vista (o técnico foi para o Início com um diálogo na frente) larga a
        // espera, para não conferir a cada 100 ms com o app no fundo; a volta ao app decide de novo. Caso: o Antes de
        // começar fica na frente até o técnico tocar em Continuar, e a trava aparece logo depois dos pedidos dele.
        @Override
        public void mostrarTrava(String texto, Runnable liberar) {
            if (!ativa()) return;
            if (trava != null) {
                TextView lista = trava.findViewById(R.id.equipe_trava_texto);
                lista.setText(texto);
                liberarDaTrava = liberar;
                return;
            }
            travaEsperando = texto;
            liberarEsperando = liberar;
            if (conferirATravaDeNovo != null) return; // a espera já roda, e mostra o texto de agora
            conferirATravaDeNovo = new Runnable() {
                @Override
                public void run() {
                    if (travaEsperando == null || !ativa() || !aTelaAVista()) {
                        pararDeEsperarATrava();
                    } else if (nadaNaFrente()) {
                        String t = travaEsperando;
                        Runnable l = liberarEsperando;
                        pararDeEsperarATrava();
                        abrirATrava(t, l);
                    } else {
                        principal.postDelayed(this, PASSO_DA_ESPERA_MS);
                    }
                }
            };
            conferirATravaDeNovo.run();
        }

        @Override
        public void tirarTrava() {
            pararDeEsperarATrava();
            View vista = trava;
            trava = null;
            liberarDaTrava = null;
            if (voltarComATrava != null) {
                voltarComATrava.remove();
                voltarComATrava = null;
            }
            for (Map.Entry<View, Integer> antes : acessibilidadeDeAntes.entrySet()) {
                antes.getKey().setImportantForAccessibility(antes.getValue());
            }
            acessibilidadeDeAntes.clear();
            if (vista == null) return;
            ViewGroup pai = (ViewGroup) vista.getParent();
            if (pai != null) pai.removeView(vista);
        }

        private void pararDeEsperarATrava() {
            if (conferirATravaDeNovo != null) principal.removeCallbacks(conferirATravaDeNovo);
            conferirATravaDeNovo = null;
            travaEsperando = null;
            liberarEsperando = null;
        }

        // A tela à vista (do onStart ao onStop): a activity que não diz o ciclo dela conta como à vista.
        private boolean aTelaAVista() {
            return !(activity instanceof LifecycleOwner) || ((LifecycleOwner) activity).getLifecycle().getCurrentState()
                .isAtLeast(Lifecycle.State.STARTED);
        }

        // A trava por cima de tudo o que está no conteúdo da activity (a WebView do App de Campo), na tela inteira. Os
        // diálogos (o termo, a Nova versão, os do Android) são janelas à parte e ficam por cima dela: a atualização segue
        // possível com a trava no ar. O que fica embaixo sai do TalkBack, o teclado do App de Campo fecha, e o Voltar só
        // manda o app para o fundo: o da casca perguntaria à página, que fecharia uma camada que o técnico nem vê.
        private void abrirATrava(String texto, Runnable liberar) {
            ViewGroup conteudo = activity.findViewById(android.R.id.content);
            if (conteudo == null) return;
            View vista;
            try {
                vista = LayoutInflater.from(activity).inflate(R.layout.equipe_trava, conteudo, false);
            } catch (RuntimeException e) {
                Log.w(TAG, "a trava não abriu (" + e.getClass().getSimpleName() + ")");
                return;
            }
            TextView lista = vista.findViewById(R.id.equipe_trava_texto);
            lista.setText(texto);
            liberarDaTrava = liberar;
            vista.findViewById(R.id.equipe_trava_liberar).setOnClickListener(v -> {
                Runnable agora = liberarDaTrava;
                if (agora != null) agora.run();
            });
            ViewCompat.setAccessibilityHeading(vista.findViewById(R.id.equipe_trava_titulo), true);
            ViewCompat.setAccessibilityPaneTitle(vista, activity.getString(R.string.casca_equipe_trava_titulo));
            // A tela inteira, também com o Android 15 em diante, que desenha o app atrás das barras do sistema: o texto e o
            // botão ficam fora delas.
            int esquerda = vista.getPaddingLeft();
            int cima = vista.getPaddingTop();
            int direita = vista.getPaddingRight();
            int baixo = vista.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(vista, (v, bordas) -> {
                Insets b = bordas.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(esquerda + b.left, cima + b.top, direita + b.right, baixo + b.bottom);
                return bordas;
            });
            for (int i = 0; i < conteudo.getChildCount(); i++) {
                View embaixo = conteudo.getChildAt(i);
                acessibilidadeDeAntes.put(embaixo, embaixo.getImportantForAccessibility());
                embaixo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            conteudo.addView(vista, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
            trava = vista;
            ViewCompat.requestApplyInsets(vista);
            InputMethodManager teclado = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (teclado != null) teclado.hideSoftInputFromWindow(conteudo.getWindowToken(), 0);
            vista.requestFocus();
            if (activity instanceof ComponentActivity) {
                ComponentActivity dona = (ComponentActivity) activity;
                voltarComATrava = new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        activity.moveTaskToBack(true);
                    }
                };
                dona.getOnBackPressedDispatcher().addCallback(dona, voltarComATrava);
            }
            Log.i(TAG, "a trava do App de Campo no ar: falta liberar algo no celular");
        }

        // Os ajustes de notificação do Nexus Field (o recuo do pedido da notificação, Task 16), do Android 8 em diante (é
        // dele a tela); antes dele, ou sem ela (há fabricante que a tira), os detalhes do app, que também levam às
        // notificações.
        @Override
        public boolean abrirOsAjustesDeNotificacao() {
            if (!ativa()) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent ajustes = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
                try {
                    activity.startActivity(ajustes);
                    return true;
                } catch (ActivityNotFoundException | SecurityException e) {
                    Log.w(TAG, "os ajustes de notificação não abriram (" + e.getClass().getSimpleName()
                        + "): os detalhes do app");
                }
            }
            return abrirOsDetalhesDoApp();
        }

        // Task 15: o lembrete só aparece com nada na frente do técnico (nadaNaFrente). Sem isso agora, ele confere de novo a
        // cada PASSO_DA_ESPERA_MS até ESPERA_DO_LEMBRETE_MS e desiste. Tocar fora e o Voltar contam como o Agora não.
        @Override
        public void mostrarLembrete(String texto, Consumer<Boolean> depois, Runnable liberar) {
            if (!ativa() || depoisDaEspera != null) {
                depois.accept(false);
                return;
            }
            // O lembrete de ontem ainda aberto (o técnico saiu pelo Início sem responder, e o processo passou a noite com
            // a activity de pé; a revisão da Task 15): com o foco nele, o de hoje esperaria à toa e desistiria, ou abriria
            // logo depois de um Agora não, a mesma tela duas vezes. Sai antes; o foco volta à janela, e a espera o vê.
            fecharOLembreteAberto();
            depoisDaEspera = depois;
            long prazo = SystemClock.uptimeMillis() + ESPERA_DO_LEMBRETE_MS;
            conferirDeNovo = new Runnable() {
                @Override
                public void run() {
                    if (depoisDaEspera == null) return;
                    if (!ativa() || nadaNaFrente()) {
                        pararDeEsperar().accept(ativa() && abrirOLembrete(texto, liberar));
                    } else if (SystemClock.uptimeMillis() >= prazo) {
                        pararDeEsperar().accept(false);
                    } else {
                        principal.postDelayed(this, PASSO_DA_ESPERA_MS);
                    }
                }
            };
            conferirDeNovo.run();
        }

        // Nada na frente do técnico. A janela do app com o foco: outro diálogo da casca (a Nova versão, o Antes de começar,
        // os avisos de depois do pedido das permissões), um pedido do Android ou outra tela o tiram, e esta classe não sabe
        // de todos eles. E nenhum fluxo do Atualizador no ar (a revisão da Task 15): a conferência do versao.json vai à
        // rede no onResume, antes da decisão da equipe, e a Nova versão abria segundos depois, por cima do lembrete, em
        // todo dia de Release; quem tocava em Atualizar agora reiniciava o app com o dia já marcado.
        private boolean nadaNaFrente() {
            return activity.hasWindowFocus() && !Atualizador.noAr();
        }

        // Tira a espera do lembrete, e devolve quem esperava a resposta (null se nada esperava).
        private Consumer<Boolean> pararDeEsperar() {
            Consumer<Boolean> quem = depoisDaEspera;
            if (conferirDeNovo != null) principal.removeCallbacks(conferirDeNovo);
            conferirDeNovo = null;
            depoisDaEspera = null;
            return quem;
        }

        private void fecharOLembreteAberto() {
            if (lembreteAberto != null && lembreteAberto == aberto) fechar();
            lembreteAberto = null;
        }

        private boolean abrirOLembrete(String texto, Runnable liberar) {
            AlertDialog dialogo = new AlertDialog.Builder(activity)
                .setTitle(R.string.casca_equipe_lembrete_titulo)
                .setMessage(texto)
                .setNegativeButton(R.string.casca_agora_nao, null)
                .setPositiveButton(R.string.casca_equipe_lembrete_liberar, (d, qual) -> liberar.run())
                .create();
            if (!mostrar(dialogo)) return false;
            lembreteAberto = dialogo;
            return true;
        }

        // O Liberar agora com o Android que não pergunta mais o tempo todo (a revisão da Task 15): a tela saindo responde
        // que pergunta, e o pedido, que também não abre, fica para a próxima volta.
        @Override
        public boolean aindaPergunta(String permissao) {
            if (!ativa()) return true;
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, permissao);
        }

        // O recuo do tempo todo no Liberar agora (Task 15): os detalhes do app, onde ficam as permissões.
        @Override
        public boolean abrirOsDetalhesDoApp() {
            if (!ativa()) return false;
            try {
                activity.startActivity(InicioAutomatico.osDetalhesDoApp(activity));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "os detalhes do app não abriram (" + e.getClass().getSimpleName() + ")");
                return false;
            }
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

        // A tela do HyperOS; a que não abre (outra versão do MIUI ou do HyperOS sem ela, ou com ela fechada a outros apps)
        // cai nos detalhes do app, onde o HyperOS também mostra o Início automático. Sem nenhuma das duas, nada a tentar de
        // novo: o aviso do que falta segue dizendo.
        @Override
        public boolean pedirInicioAutomatico() {
            if (!ativa()) return false;
            try {
                activity.startActivity(InicioAutomatico.aTelaDoHyperOS());
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "a tela do Início automático do HyperOS não abriu (" + e.getClass().getSimpleName()
                    + "): os detalhes do app");
            }
            try {
                activity.startActivity(InicioAutomatico.osDetalhesDoApp(activity));
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "os detalhes do app não abriram (" + e.getClass().getSimpleName() + ")");
            }
            return true;
        }

        @Override
        public void fechar() {
            fecharODialogo();
            // A trava também sai (o Sair, o coleta:false, a tela saindo): a conta deixou a coleta, ou a tela vai embora.
            tirarTrava();
        }

        private void fecharODialogo() {
            // O lembrete que esperava não aparece depois (o Sair, o coleta:false, a tela saindo).
            Consumer<Boolean> esperava = pararDeEsperar();
            if (esperava != null) esperava.accept(false);
            AlertDialog dialogo = aberto;
            aberto = null;
            if (dialogo == null) return;
            try {
                dialogo.dismiss();
            } catch (RuntimeException e) {
                // a janela já foi embora com a activity
            }
        }

        // Um diálogo por vez: o novo tira o anterior (a trava não, que fica embaixo dele).
        private boolean mostrar(AlertDialog dialogo) {
            fecharODialogo();
            try {
                dialogo.show();
            } catch (RuntimeException e) {
                Log.w(TAG, "o diálogo da equipe não abriu (" + e.getClass().getSimpleName() + ")");
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
