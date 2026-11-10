package br.com.gridco.campo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/**
 * A coleta da posição da equipe (spec 2026-11-08, seção 4.1): um serviço em primeiro plano do tipo localização, com a
 * notificação fixa no canal "Localização da equipe" (CanaisDeNotificacao.criarDaEquipe), das 6h às 22h na hora do
 * celular, também com o app fechado. Sem pausa (decisão do dono): ligado, só para às 22h, no PARAR do Nexus, no
 * {@link #parar} (o Sair), sem a permissão de localização ou sem a notificação fixa à vista.
 *
 * <ul>
 *   <li>Liga pelo {@link #iniciar}, só dentro da janela, com a localização precisa ({@link #temAPermissao}) e com a
 *       notificação fixa à vista ({@link #notificacaoAVista}), com a tela do Nexus Field à vista (quem chama é a
 *       {@link EquipeNaCasca}, que confere): o serviço do tipo localização iniciado com o app à vista mantém a localização
 *       durante o uso enquanto roda, também com o app fechado. É a abertura que começa o dia: ela grava o prazo (as
 *       22h).</li>
 *   <li><b>Com o app no fundo, só volta o dia já começado, e só com o "Permitir o tempo todo"</b> (R15, o pedido do dono
 *       em 10/11/2026, no lugar da R10: "precisamos só ser mais independente... sem esses erros de ficar parando coleta nem
 *       nada"; o caso: o HyperOS e a atualização fecharam o app no meio do dia, e a coleta parava até a próxima abertura).
 *       Com o tempo todo ({@link #temOTempoTodo}), o {@link #onStartCommand} devolve {@code START_STICKY}, e o processo que
 *       o Android mata religa o serviço (o Intent nulo); o celular reiniciado e o app atualizado chegam pelo
 *       {@link ReceptorDoInicio} ({@link #religarNoDiaComecado}). Nos três, a coleta só volta com o dia começado (o prazo
 *       da abertura de hoje no disco e a hora na janela), a chave guardada, o termo de agora aceito, a localização precisa,
 *       o tempo todo e a notificação à vista ({@link #oQueFaltaParaReligar}): sem abrir o app no dia, a folga e as férias
 *       seguem de fora. Sem o tempo todo, como na 1.2.0: {@code START_NOT_STICKY}, e nada volta com o app no fundo (o
 *       serviço iniciado assim não teria a localização do Android 11 em diante, e do 14 em diante o primeiro plano de
 *       localização lança). Do Android 12 em diante o primeiro plano pedido com o app no fundo só vale nas exceções do
 *       Android (o boot e a atualização são duas; o religamento do processo morto, com o app sem a otimização de bateria):
 *       a recusa vai ao log só com o nome da classe, e a coleta fica para a próxima abertura. O {@link #REPOR} que chega
 *       com a coleta parada não a liga.</li>
 *   <li>A permissão é conferida ao ligar, a cada posição e a cada envio, e não só pela casca na volta ao app. Caso que
 *       criou a regra: a localização tirada nos ajustes com a coleta ligada, no aparelho em que o processo não morre: a
 *       coleta seguia até a próxima volta ao app. Faltando, o prazo é esquecido e o serviço para ({@link #semPermissao}).
 *       O prazo do dia (as 22h do dia em que o app abriu) vai ao disco antes do pedido.</li>
 *   <li>Coleta no ritmo do {@link RitmoDaColeta}: 2 min com a alta precisão em deslocamento, 10 min com a precisão
 *       equilibrada parado. O ritmo e o "mov" de cada ponto usam a mesma distância, a que passa da precisão das duas
 *       leituras ({@link #metrosAlemDaPrecisao}): parado longe do Wi-Fi, a precisão equilibrada vem da antena, com erro de
 *       centenas de metros, e com a distância crua cada leitura dela religava o GPS (um ciclo de uns 18 min, com o GPS
 *       ligado perto de metade do tempo). A leitura com um número que não é finito (o NaN de um provedor com defeito) é
 *       descartada: a fila não a escreve. Cada posição vai à {@link FilaDePontos}, e o {@link EnvioDePontos} manda o lote
 *       mais antigo a cada {@link #ENVIO_MS}, numa thread só para o disco e a rede.</li>
 *   <li>Na partida a frio o envio vale com a conta conferida no disco por uma página de antes (R14,
 *       {@link ChaveDaEquipe#conferida}): depois da atualização, do Forçar parada ou do reinício, a coleta que liga com a
 *       tela apagada envia sem esperar a página. Sem conferência nenhuma (a chave da 1.2.0), a coleta segue igual e o lote
 *       espera na fila até a página conferir; enquanto isso o envio nem acorda o celular nem recomeça a contar
 *       ({@link EnvioDePontos#podeEnviar}), e o primeiro lote depois da conferência sai com a posição seguinte.</li>
 *   <li>O envio não depende só do Handler, que conta o tempo de CPU acordada: com a tela apagada (no bolso, no suporte do
 *       carro) o celular dorme entre uma posição e outra, e os 2 min do Handler levariam horas no relógio (o mapa diria
 *       "sem sinal" com o técnico trabalhando). Por isso a posição que chega com o envio vencido, contado no relógio que
 *       anda com o celular dormindo ({@link SystemClock#elapsedRealtime}), manda na hora, com o celular acordado até a
 *       resposta ({@link #ACORDADO_NO_ENVIO_MS}). O Handler fica para o app aberto.</li>
 *   <li>Sem a notificação fixa à vista não há coleta: o desenho chama de fracasso coletar sem ela. No Android 13+ com a
 *       notificação negada, ou com as do Nexus Field ou o canal da equipe desligados nos ajustes, o serviço segue sem
 *       aparecer na barra; por isso ela é conferida ao ligar, a cada posição e a cada envio, e a coleta não liga ou para.
 *       A arrastada para o lado (Android 14+) volta na hora, pelo deleteIntent ({@link #REPOR}).</li>
 *   <li>Para às 22h: o relógio é conferido no prazo, a cada envio e a cada posição, porque o Handler não conta o tempo em
 *       que o celular dorme. O que ficou na fila sai num último envio ({@link #fimDoDia}): antes, os pontos entre o último
 *       envio e as 22h esperavam a primeira abertura do dia seguinte. O {@link EnvioDePontos.Resultado#PARAR} (o 401 ou o
 *       coleta:false do Nexus) e a permissão tirada esquecem o prazo, e a próxima abertura do app decide de novo. O prazo
 *       esquecido (o Sair, a troca de conta) não faz o último envio: a fila é de quem saiu.</li>
 *   <li>O Android 8+ derruba o app cujo serviço pedido em primeiro plano para antes do startForeground. Por isso o
 *       {@link #parar} nunca para o serviço antes dele (esquece o prazo, e o onStartCommand que ainda vier entra em
 *       primeiro plano e sai logo), e o stopSelf leva o número do último início (o iniciar que chegou depois de um parar
 *       segue valendo).</li>
 *   <li>Nada de coordenada, chave ou id de aparelho no log, nem o texto das exceções: só o nome da classe.</li>
 * </ul>
 */
public class ServicoDaEquipe extends Service {

    /** O id do canal da notificação fixa no Android (o nome está no strings.xml). */
    static final String CANAL_EQUIPE = "equipe_localizacao";
    /** O id da notificação fixa: fora das faixas do Notificacoes (1, o resumo; de 1.000.000 a 2.999.999, as emergências). */
    static final int ID_DA_NOTIFICACAO = 3_000_000;
    /** O envio da fila ao Nexus: a cada 2 min, o ritmo de deslocamento (a posição nova não espera mais que um ciclo). */
    static final long ENVIO_MS = 120_000L;
    /**
     * A posição que chega até 15 s antes dos 2 min também manda. O envio sai logo depois de uma posição, e a seguinte chega
     * a 2 min DELA, um tanto antes dos 2 min contados do envio: sem a folga, ela esperaria a outra, e com a tela apagada o
     * envio iria a cada 4 min.
     */
    static final long FOLGA_DO_ENVIO_MS = 15_000L;
    /**
     * O celular acordado durante um envio: os 15 s da conexão e os 15 s da leitura do Http, mais o disco e a volta à thread
     * principal. O prazo solta sozinho se a resposta não voltar (a rede pendurada não segura a bateria).
     */
    static final long ACORDADO_NO_ENVIO_MS = 2L * EnvioDePontos.PRAZO_MS + 10_000L;
    /** O início que o deleteIntent da notificação manda: o técnico a arrastou para o lado (Android 14+), e ela volta. */
    static final String REPOR = "br.com.gridco.campo.equipe.REPOR";
    /**
     * A localização o tempo todo (R15), do Android 10 em diante. É só o nome da permissão: no Android 9 e antes ela não
     * existe à parte, nada a pede, e quem responde por ela é a localização precisa ({@link #temOTempoTodo}).
     */
    @SuppressLint("InlinedApi")
    static final String O_TEMPO_TODO = Manifest.permission.ACCESS_BACKGROUND_LOCATION;

    private static final String TAG = CascaPlugin.TAG;
    // O nome do celular acordado nos dados de bateria do Android, no formato "app:motivo" que o Android recomenda.
    private static final String NOME_DO_ACORDADO = "NexusField:envio-da-equipe";
    // As 22h, ou o prazo que o parar() esqueceu: o motivo vai ao log sem dizer qual.
    private static final String FORA_DO_PRAZO = "a coleta da equipe parou: fora do prazo do dia (as 22h ou desligada)";
    private static final String SEM_A_NOTIFICACAO = "a coleta da equipe parou: a notificação fixa não está à vista "
        + "(as notificações do Nexus Field ou o canal da equipe desligados)";
    // O prazo do dia (as 22h do dia em que o app abriu, em epoch ms). Fora do backup, como tudo (regras_dados.xml).
    private static final String PREFERENCIAS = "equipe_servico";
    private static final String CHAVE_PRAZO = "prazo";

    /** As peças que o teste troca (os dois relógios, a fila, o envio, as posições e a thread); null é o de produção. */
    static Pecas pecasDeTeste;
    private static Pecas pecasDoProcesso;
    // O serviço que está em primeiro plano agora: é por ele que o parar() desliga. Só a thread principal o muda.
    private static volatile ServicoDaEquipe emPrimeiroPlano;

    private final Handler principal = new Handler(Looper.getMainLooper());
    private Pecas pecas;
    private PowerManager.WakeLock acordado;
    private int ultimoInicio;
    private volatile boolean coletando = false;
    // O último início pediu ao Android para religar o serviço no processo morto (o START_STICKY, só com o tempo todo).
    private volatile boolean religa = false;
    private RitmoDaColeta ritmo;
    private Location ultima;
    private long intervaloPedido;
    // Quando saiu o último envio, no relógio que anda com o celular dormindo (Pecas.desdeOBoot).
    private long ultimoEnvio;

    /**
     * O relógio de parede, o relógio desde o boot (o que anda com o celular dormindo), a chave do aparelho, a fila, o
     * envio, a fonte das posições e a thread do disco e da rede: uma por processo, para o serviço e para a casca
     * ({@link EquipeNaCasca}, que guarda e apaga a chave e manda o Sair por esta mesma thread).
     */
    static final class Pecas {
        final LongSupplier relogio;
        final LongSupplier desdeOBoot;
        final ChaveDaEquipe chave;
        final FilaDePontos fila;
        final EnvioDePontos envio;
        final FonteDePosicoes fonte;
        final Executor execucao;

        Pecas(LongSupplier relogio, LongSupplier desdeOBoot, ChaveDaEquipe chave, FilaDePontos fila,
                EnvioDePontos envio, FonteDePosicoes fonte, Executor execucao) {
            this.relogio = relogio;
            this.desdeOBoot = desdeOBoot;
            this.chave = chave;
            this.fila = fila;
            this.envio = envio;
            this.fonte = fonte;
            this.execucao = execucao;
        }

        // A mesma fila para quem grava e para quem manda: duas conexões no mesmo equipe.db disputariam a escrita. E a mesma
        // chave para o envio e para a casca: cada ChaveDaEquipe traz a sua CifraKeystore, que refaz uma vez por processo a
        // chave que o Keystore passou a recusar supondo ser a única (uma segunda apagaria a que a primeira acabou de refazer).
        static Pecas deProducao(Context app) {
            LongSupplier relogio = System::currentTimeMillis;
            ChaveDaEquipe chave = new ChaveDaEquipe(app, new CifraKeystore());
            FilaDePontos fila = new FilaDePontos(app);
            EnvioDePontos envio = new EnvioDePontos(chave, fila, new EnvioDePontos.Http(), relogio,
                () -> AjustesDaColeta.ler(app, chave));
            Executor thread = Executors.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "GcCasca-equipe"));
            return new Pecas(relogio, SystemClock::elapsedRealtime, chave, fila, envio, new Fused(app), thread);
        }
    }

    /** De onde vêm as posições: o Google Play services ({@link Fused}); no teste, uma falsa. Tudo na thread principal. */
    interface FonteDePosicoes {
        /**
         * Pede posições a cada {@code intervaloMs}, com a alta precisão em deslocamento e a equilibrada parado; o pedido
         * novo toma o lugar do anterior. Lança SecurityException sem a permissão de localização.
         */
        void pedir(long intervaloMs, boolean emDeslocamento, Receptor receptor);

        /** Para de pedir (sem pedido, não faz nada). */
        void parar();

        interface Receptor {
            void posicao(Location posicao);

            /** O pedido que falhou depois de feito: a permissão tirada, o Google Play services fora. */
            void falhou(Exception erro);
        }
    }

    static synchronized Pecas pecas(Context contexto) {
        if (pecasDeTeste != null) return pecasDeTeste;
        if (pecasDoProcesso == null) pecasDoProcesso = Pecas.deProducao(contexto.getApplicationContext());
        return pecasDoProcesso;
    }

    /**
     * Liga a coleta, se a hora do celular está na janela, a localização precisa foi dada ({@link #temAPermissao}) e a
     * notificação fixa vai ficar à vista ({@link #notificacaoAVista}). Quem decide se a coleta vale para o técnico é a
     * casca, antes (o Nexus, pela chave do aparelho), e só com a tela à vista. Chamar de novo com ela ligada não faz outra.
     */
    static void iniciar(Context contexto) {
        Context app = contexto.getApplicationContext();
        long agora = pecas(app).relogio.getAsLong();
        TimeZone fuso = TimeZone.getDefault();
        if (!JanelaDaEquipe.dentro(agora, fuso)) {
            Log.i(TAG, "coleta da equipe não ligada: fora da janela das 6h às 22h");
            return;
        }
        if (!temAPermissao(app)) {
            // Do Android 14 em diante o primeiro plano de localização sem a permissão lança, e o serviço pedido em
            // primeiro plano que não chega a ele derruba o app; com só a aproximada, o pedido de alta precisão é recusado.
            Log.w(TAG, "coleta da equipe não ligada: sem a permissão de localização");
            return;
        }
        if (!notificacaoAVista(app)) {
            Log.w(TAG, "coleta da equipe não ligada: a notificação fixa não ficaria à vista "
                + "(as notificações do Nexus Field ou o canal da equipe desligados)");
            return;
        }
        preferencias(app).edit().putLong(CHAVE_PRAZO, JanelaDaEquipe.fimDaJanela(agora, fuso)).apply();
        pedirOServico(app);
    }

    /**
     * O celular reiniciado e o app atualizado ({@link ReceptorDoInicio}, R15): a coleta do dia começado volta sozinha, sem
     * abrir o app, se nada falta ({@link #oQueFaltaParaReligar}). O BOOT_COMPLETED e o MY_PACKAGE_REPLACED estão entre as
     * exceções em que o Android 12+ deixa pedir o primeiro plano com o app no fundo, e o 14+ exige para o de localização o
     * tempo todo, conferido antes. O prazo do dia não muda: é o da abertura. {@code evento} vai ao log. True se o serviço
     * foi pedido.
     */
    static boolean religarNoDiaComecado(Context contexto, String evento) {
        Context app = contexto.getApplicationContext();
        if (rodando()) return false;
        String falta = oQueFaltaParaReligar(app, pecas(app).relogio.getAsLong());
        if (falta != null) {
            Log.i(TAG, evento + ": a coleta da equipe não volta sozinha (" + falta + ")");
            return false;
        }
        Log.i(TAG, evento + ": a coleta da equipe volta sozinha (o dia já começou)");
        return pedirOServico(app);
    }

    /**
     * O que falta para a coleta voltar sozinha com o app no fundo (R15), ou null se nada falta: o dia começado, a chave
     * guardada e o termo de agora aceito (lidos sem a cifra), a localização precisa, o tempo todo e a notificação fixa à
     * vista. O texto vai ao log, e não diz nada do aparelho nem da conta.
     */
    static String oQueFaltaParaReligar(Context contexto, long agora) {
        Context app = contexto.getApplicationContext();
        if (!diaComecado(app, agora)) return "o dia não começou: sem a abertura de hoje, ou fora das 6h às 22h";
        ChaveDaEquipe chave = pecas(app).chave;
        if (!chave.temChaveGuardada() || !chave.termoAceito()) return "sem a chave da equipe ou sem o termo de agora";
        if (chave.saidaPendente()) return "o Sair por terminar: a próxima abertura o termina";
        if (!temAPermissao(app)) return "sem a localização precisa";
        if (!temOTempoTodo(app)) return "sem a localização o tempo todo";
        if (!notificacaoAVista(app)) return "sem a notificação fixa à vista";
        return null;
    }

    /**
     * O dia já começou (R15): a abertura de hoje gravou o prazo do dia (as 22h), e a hora do celular está na janela. É o
     * que separa o religamento sozinho da coleta em folga: sem abrir o app no dia, o prazo é o de outro dia (ou nenhum).
     */
    private static boolean diaComecado(Context contexto, long agora) {
        return JanelaDaEquipe.dentro(agora, TimeZone.getDefault()) && agora < prazo(contexto);
    }

    // O pedido do serviço em primeiro plano. Do Android 12 em diante, com o app no fundo fora das exceções, o Android o
    // recusa (ForegroundServiceStartNotAllowedException): só o nome da classe vai ao log, e a próxima abertura religa.
    private static boolean pedirOServico(Context app) {
        try {
            ContextCompat.startForegroundService(app, new Intent(app, ServicoDaEquipe.class));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "o Android não deixou ligar a coleta da equipe agora: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Desliga a coleta (o Sair, a coleta que deixou de valer). O serviço que ainda não entrou em primeiro plano entra e sai
     * logo.
     */
    static void parar(Context contexto) {
        esquecerOPrazo(contexto);
        new Handler(Looper.getMainLooper()).post(() -> {
            ServicoDaEquipe s = emPrimeiroPlano;
            if (s != null) s.encerrar("coleta da equipe desligada");
        });
    }

    /** A coleta está ligada: em primeiro plano, pedindo posições. */
    static boolean rodando() {
        ServicoDaEquipe s = emPrimeiroPlano;
        return s != null && s.coletando;
    }

    /**
     * A coleta ligada volta sozinha se o Android matar o processo: o último início dela pediu o religamento (o
     * START_STICKY, com o tempo todo). O serviço iniciado antes do tempo todo dado não pediu, e a casca o inicia de novo.
     */
    static boolean religaSozinho() {
        ServicoDaEquipe s = emPrimeiroPlano;
        return s != null && s.coletando && s.religa;
    }

    /**
     * A localização o tempo todo (R15): do Android 10 em diante, a {@link #O_TEMPO_TODO}; do 9 para trás ela não existe à
     * parte, e a localização precisa dada já vale com o app no fundo.
     */
    static boolean temOTempoTodo(Context contexto) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return temAPermissao(contexto);
        return ContextCompat.checkSelfPermission(contexto, O_TEMPO_TODO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * A notificação fixa fica à vista na barra: as notificações do Nexus Field ligadas (no Android 13+, a permissão dada)
     * e o canal da equipe não desligado (antes da primeira coleta ele nem existe, e nasce na importância baixa). Sem isso o
     * serviço em primeiro plano roda e só o gerenciador de tarefas do Android o mostra, e a coleta não liga: a casca pede
     * ao técnico para ligá-las, como faz com a localização.
     */
    static boolean notificacaoAVista(Context contexto) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManagerCompat notificacoes = NotificationManagerCompat.from(contexto);
        if (!notificacoes.areNotificationsEnabled()) return false;
        NotificationChannelCompat canal = notificacoes.getNotificationChannelCompat(CANAL_EQUIPE);
        return canal == null || canal.getImportance() != NotificationManagerCompat.IMPORTANCE_NONE;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        pecas = pecas(this);
        PowerManager energia = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (energia != null) {
            acordado = energia.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, NOME_DO_ACORDADO);
            // Sem contagem: um release solta de vez, e o que vem depois de o prazo já ter soltado não lança.
            acordado.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        ultimoInicio = startId;
        long agora = pecas.relogio.getAsLong();
        if (intent == null) {
            // O religamento do sistema: o processo que o Android matou, com o START_STICKY do início de antes (só com o
            // tempo todo). Volta só no dia começado e com o que a coleta pede (R15, ver o cabeçalho); senão sai antes do
            // primeiro plano: o Android só exige o startForeground de quem foi pedido pelo startForegroundService.
            String falta = oQueFaltaParaReligar(this, agora);
            if (falta != null) {
                Log.i(TAG, "religamento da coleta da equipe pelo sistema: não volta (" + falta + "); a coleta liga na "
                    + "próxima abertura do app");
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            // O primeiro plano com o app no fundo: do Android 12 em diante só nas exceções (o app sem a otimização de
            // bateria), e a recusa só vai ao log (entrarEmPrimeiroPlano).
            if (!entrarEmPrimeiroPlano()) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            Log.i(TAG, "religamento da coleta da equipe pelo sistema: a coleta volta (o dia já começou)");
            if (!coletando) ligarColeta(agora);
            return religamento();
        }
        boolean repor = REPOR.equals(intent.getAction());
        if (repor && !coletando) {
            // O arrastar que chega com a coleta parada (o processo que morreu com a notificação na barra): é um início com
            // o app no fundo, e não liga nada. Veio pelo startService, que não exige o primeiro plano.
            Log.i(TAG, "a notificação da coleta da equipe arrastada com a coleta parada: nada liga");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        // Também no REPOR (a notificação arrastada para o lado): o startForeground de novo a põe de volta na barra.
        if (!entrarEmPrimeiroPlano()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!valeColetar(agora)) {
            // O parar que chegou antes deste início, ou as 22h que passaram entre o iniciar e aqui.
            encerrar("coleta da equipe fora do prazo: não ligou");
            return START_NOT_STICKY;
        }
        if (!notificacaoAVista(this)) {
            // Conferida depois do startForeground, e não antes: o serviço pedido pelo startForegroundService que para
            // antes dele derruba o app. O iniciar já conferiu; aqui é a notificação desligada entre o pedido e este
            // início.
            encerrar(SEM_A_NOTIFICACAO);
            return START_NOT_STICKY;
        }
        if (!coletando) {
            ligarColeta(agora);
        } else if (repor) {
            Log.i(TAG, "a notificação fixa da coleta da equipe foi arrastada para o lado e voltou");
        }
        return religamento();
    }

    // O que o início pede ao Android para o processo morto (R15): religar o serviço só com a coleta ligada e o tempo todo;
    // sem ele, como na 1.2.0, nada volta com o app no fundo. É o último início que vale para o Android.
    private int religamento() {
        religa = coletando && temOTempoTodo(this);
        return religa ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        desligar();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean entrarEmPrimeiroPlano() {
        if (!temAPermissao(this)) {
            Log.w(TAG, "sem a permissão de localização: a coleta da equipe não ligou");
            return false;
        }
        CanaisDeNotificacao.criarDaEquipe(this);
        try {
            ServiceCompat.startForeground(this, ID_DA_NOTIFICACAO, notificacao(), tipoDeLocalizacao());
        } catch (RuntimeException e) {
            // O Android 12+ recusa o primeiro plano pedido com o app no fundo (ForegroundServiceStartNotAllowedException),
            // e o 14+ o de localização sem a permissão (SecurityException): só registra, e a próxima abertura religa.
            Log.w(TAG, "o Android recusou a coleta da equipe em primeiro plano: " + e.getClass().getSimpleName());
            return false;
        }
        emPrimeiroPlano = this;
        return true;
    }

    private static int tipoDeLocalizacao() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
    }

    private Notification notificacao() {
        // O toque abre o Nexus Field onde ele estava: a porta interna sem extra, como o toque no resumo.
        Intent abrir = new Intent()
            .setClassName(this, MainActivity.PORTA_INTERNA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent toque = PendingIntent.getActivity(this, ID_DA_NOTIFICACAO, abrir,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // Do Android 14 em diante o técnico pode arrastar a notificação de primeiro plano para o lado, e a coleta seguiria
        // sem ela à vista: o arrastar chega a este serviço como um início (o REPOR), e o startForeground a põe de volta. É
        // o startService, e não o startForegroundService: a notificação arrastada só existe com o serviço em primeiro
        // plano, e o Android deixa o app que tem um serviço em primeiro plano iniciar serviço.
        PendingIntent repor = PendingIntent.getService(this, ID_DA_NOTIFICACAO,
            new Intent(this, ServicoDaEquipe.class).setAction(REPOR),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String texto = getString(R.string.notificacao_equipe_texto);
        return new NotificationCompat.Builder(this, CANAL_EQUIPE)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle(getString(R.string.notificacao_equipe_titulo))
            .setContentText(texto)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(toque)
            .setDeleteIntent(repor)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Android 7, sem canais: a prioridade baixa faz o papel do canal (sem som).
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Do Android 12 em diante o sistema pode segurar a notificação de primeiro plano por até 10 s, e coletar sem
            // ela à vista é o que o desenho chama de fracasso.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build();
    }

    private void ligarColeta(long agora) {
        coletando = true;
        ritmo = new RitmoDaColeta();
        ultima = null;
        intervaloPedido = RitmoDaColeta.EM_DESLOCAMENTO_MS;
        // O primeiro envio sai 2 min depois de ligar, pelo Handler ou pela posição que chegar com ele vencido.
        ultimoEnvio = pecas.desdeOBoot.getAsLong();
        if (!pedirPosicoes()) return;
        principal.postDelayed(envioPeriodico, ENVIO_MS);
        agendarOFim(agora);
        // O aviso das 6h (AvisoDoInicio) cumpriu o papel: o técnico que abriu o app pelo ícone não fica com ele na barra
        // até as 22h, pedindo um toque que já não muda nada.
        AvisoDoInicio.tirarDaBarra(this);
        Log.i(TAG, "coleta da equipe ligada até as 22h");
    }

    /** Pede as posições no ritmo de agora; sem a permissão, para. */
    private boolean pedirPosicoes() {
        try {
            pecas.fonte.pedir(intervaloPedido, intervaloPedido == RitmoDaColeta.EM_DESLOCAMENTO_MS, receptor);
            return true;
        } catch (SecurityException e) {
            semPermissao();
            return false;
        }
    }

    private void semPermissao() {
        // A permissão tirada: o log diz só o motivo (o texto da exceção fica de fora), e o prazo do dia é esquecido.
        esquecerOPrazo(this);
        encerrar("sem a permissão de localização: a coleta da equipe parou");
    }

    private final FonteDePosicoes.Receptor receptor = new FonteDePosicoes.Receptor() {
        @Override
        public void posicao(Location posicao) {
            aoReceber(posicao);
        }

        @Override
        public void falhou(Exception erro) {
            if (!coletando) return;
            if (erro instanceof SecurityException) {
                semPermissao();
            } else {
                // O Google Play services fora ou desatualizado: sem posição, a notificação não fica prometendo uma
                // coleta que não acontece. A próxima abertura tenta de novo.
                encerrar("as posições não vieram (" + erro.getClass().getSimpleName() + "): a coleta da equipe parou");
            }
        }
    };

    private void aoReceber(Location l) {
        if (!coletando) return;
        if (!valeColetar(pecas.relogio.getAsLong())) {
            fimDoDia();
            return;
        }
        if (!temAPermissao(this)) {
            // A permissão tirada com a coleta ligada, no aparelho em que o processo não morreu: esta posição já não entra.
            semPermissao();
            return;
        }
        if (!notificacaoAVista(this)) {
            // O técnico desligou a notificação ou o canal com a coleta ligada: esta posição já não entra.
            encerrar(SEM_A_NOTIFICACAO);
            return;
        }
        if (!leituraValida(l)) {
            // Sem o valor no log: é coordenada.
            Log.w(TAG, "uma leitura de posição sem número válido foi descartada");
            return;
        }
        // O ritmo: sem a velocidade do aparelho, a distância decide sozinha (ele pede as duas: abaixo de 1 m/s e a menos de
        // 150 m), e só a que passa da precisão das duas leituras conta, como no mov; a primeira leitura não tem com quem
        // comparar e conta como deslocamento (o ritmo começa no curto).
        Float velocidade = l.hasSpeed() ? l.getSpeed() : null;
        double metros = ultima == null ? Double.POSITIVE_INFINITY : metrosAlemDaPrecisao(l, ultima);
        boolean mov = movDaLeitura(l, ultima);
        ultima = new Location(l);
        long intervalo = ritmo.proximoIntervaloMs(velocidade == null ? 0 : velocidade, metros);
        PontoDaEquipe ponto = new PontoDaEquipe(l.getTime(), l.getLatitude(), l.getLongitude(), l.getAccuracy(),
            velocidade, mov);
        executar(() -> pecas.fila.guardar(ponto));
        if (intervalo != intervaloPedido) {
            intervaloPedido = intervalo;
            if (!pedirPosicoes()) return;
        }
        // Com a tela apagada o Handler não anda, e é a posição que acorda o celular: com o envio vencido, manda agora
        // (o ponto de agora vai junto, porque a fila grava antes, na mesma thread). À espera da conta conferida, nada:
        // nem o celular acordado à toa nem a conta do envio recomeçada.
        if (pecas.desdeOBoot.getAsLong() - ultimoEnvio >= ENVIO_MS - FOLGA_DO_ENVIO_MS && pecas.envio.podeEnviar()) {
            mandarOLote();
        }
    }

    /** A leitura que a fila pode guardar: a posição, a precisão e a velocidade (quando vêm) são números finitos. */
    static boolean leituraValida(Location l) {
        double lat = l.getLatitude();
        double lon = l.getLongitude();
        if (!finito(lat) || !finito(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return false;
        if (l.hasAccuracy() && !finito(l.getAccuracy())) return false;
        return !l.hasSpeed() || finito(l.getSpeed());
    }

    private static boolean finito(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    /**
     * O "mov" do ponto (seção 8 do desenho): a própria leitura em deslocamento, com a régua do ritmo (1 m/s ou mais, ou
     * 150 m ou mais da leitura anterior: {@link RitmoDaColeta#leituraParada}), e não o estado do ritmo. O ritmo sai do
     * deslocamento só na terceira parada seguida e começa nele, e com o estado dele as três primeiras leituras do dia em
     * casa (o app aberto antes de sair) e as da chegada em casa ou no hotel iam com mov true: o mapa mostra o ponto exato e
     * o Abrir rota de quem está em deslocamento, e o desenho esconde os dois de quem está parado fora de usina. Pelo mesmo
     * motivo, sem a leitura anterior só a velocidade conta (a primeira leitura sem velocidade vai parada), e a distância
     * que cabe na precisão das duas leituras não conta: é o salto da posição de rede (do Wi-Fi para a antena e de volta)
     * com o celular parado em cima da mesa.
     */
    static boolean movDaLeitura(Location leitura, @Nullable Location anterior) {
        double velocidade = leitura.hasSpeed() ? leitura.getSpeed() : 0;
        return !RitmoDaColeta.leituraParada(velocidade, metrosAlemDaPrecisao(leitura, anterior));
    }

    /**
     * A distância da leitura anterior que conta para o mov e para o ritmo: a que não cabe na precisão das duas leituras
     * (dentro dela, é o salto da posição de rede com o celular parado), ou zero; sem a anterior, zero.
     */
    static double metrosAlemDaPrecisao(Location leitura, @Nullable Location anterior) {
        if (anterior == null) return 0;
        double distancia = anterior.distanceTo(leitura);
        return distancia > precisao(anterior) + precisao(leitura) ? distancia : 0;
    }

    private static double precisao(Location l) {
        return l.hasAccuracy() ? l.getAccuracy() : 0;
    }

    private final Runnable envioPeriodico = new Runnable() {
        @Override
        public void run() {
            if (!coletando) return;
            if (!valeColetar(pecas.relogio.getAsLong())) {
                fimDoDia();
                return;
            }
            if (!temAPermissao(ServicoDaEquipe.this)) {
                semPermissao();
                return;
            }
            if (!notificacaoAVista(ServicoDaEquipe.this)) {
                encerrar(SEM_A_NOTIFICACAO);
                return;
            }
            if (!pecas.envio.podeEnviar()) {
                // À espera da conta conferida: o Handler segue de pé, sem acordar o celular e sem recomeçar a contar o
                // envio, e a posição que chegar depois da conferência manda na hora.
                principal.postDelayed(envioPeriodico, ENVIO_MS);
                return;
            }
            mandarOLote();
        }
    };

    /**
     * As 22h (ou o prazo do dia em que o app abriu): a coleta para, e o que ficou na fila sai num último envio, com o
     * celular acordado até a resposta (o serviço já saiu; o prazo do acordado solta sozinho). O prazo esquecido pelo
     * {@link #parar} (o Sair, a troca de conta) não manda: a fila é de quem saiu. A conta ainda não conferida também não.
     */
    private void fimDoDia() {
        boolean ultimo = prazo(this) > 0 && pecas.envio.podeEnviar();
        encerrar(FORA_DO_PRAZO);
        if (!ultimo) return;
        PowerManager energia = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock noFim = energia == null ? null
            : energia.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, NOME_DO_ACORDADO);
        if (noFim != null) {
            noFim.setReferenceCounted(false);
            noFim.acquire(ACORDADO_NO_ENVIO_MS);
        }
        Log.i(TAG, "o fim da coleta da equipe: o que ficou na fila sai num último envio");
        executar(() -> {
            try {
                pecas.envio.enviar();
            } finally {
                if (noFim != null && noFim.isHeld()) noFim.release();
            }
        });
    }

    /**
     * Manda o lote mais antigo na thread do disco e da rede, com o celular acordado até a resposta. O Handler recomeça a
     * contar daqui: a posição que mandou não leva a outro envio do Handler logo depois.
     */
    private void mandarOLote() {
        ultimoEnvio = pecas.desdeOBoot.getAsLong();
        principal.removeCallbacks(envioPeriodico);
        principal.postDelayed(envioPeriodico, ENVIO_MS);
        if (acordado != null) acordado.acquire(ACORDADO_NO_ENVIO_MS);
        executar(() -> {
            EnvioDePontos.Resultado r = EnvioDePontos.Resultado.SEM_REDE;
            try {
                r = pecas.envio.enviar();
            } finally {
                // Também com a fila que lançou: o celular volta a dormir na hora, e não só no fim do prazo.
                EnvioDePontos.Resultado fim = r;
                principal.post(() -> aposOEnvio(fim));
            }
        });
    }

    private void aposOEnvio(EnvioDePontos.Resultado r) {
        soltarOAcordado();
        if (r != EnvioDePontos.Resultado.PARAR || !coletando) return;
        // O 401 (a chave que o Nexus não reconhece) ou o coleta:false: a chave já saiu (EnvioDePontos), e o prazo do dia
        // é esquecido. A próxima abertura do app decide de novo, com o Nexus.
        esquecerOPrazo(this);
        encerrar("o Nexus desligou a coleta da equipe neste aparelho");
    }

    private final Runnable fimDoPrazo = new Runnable() {
        @Override
        public void run() {
            if (!coletando) return;
            long agora = pecas.relogio.getAsLong();
            if (valeColetar(agora)) {
                agendarOFim(agora); // o Handler acordou antes: o fuso do celular mudou
            } else {
                fimDoDia();
            }
        }
    };

    /** O próximo "acabou?": as 22h de hoje na hora do celular, ou o prazo do dia em que o app abriu, o que vier antes. */
    private void agendarOFim(long agora) {
        principal.removeCallbacks(fimDoPrazo);
        long fim = Math.min(JanelaDaEquipe.fimDaJanela(agora, TimeZone.getDefault()), prazo(this));
        principal.postDelayed(fimDoPrazo, Math.max(1_000L, fim - agora));
    }

    /** Dentro da janela das 6h às 22h e antes do prazo do dia em que o app abriu. */
    private boolean valeColetar(long agora) {
        return diaComecado(this, agora);
    }

    /** Na thread do disco e da rede; o erro vai ao log só com o nome da classe (nada de coordenada nem de chave). */
    private void executar(Runnable tarefa) {
        try {
            pecas.execucao.execute(() -> {
                try {
                    tarefa.run();
                } catch (RuntimeException e) {
                    Log.w(TAG, "a fila da equipe falhou (" + e.getClass().getSimpleName() + ")");
                }
            });
        } catch (RejectedExecutionException e) {
            // nunca: a thread do processo não é desligada
        }
    }

    /** Sai do primeiro plano, tira a notificação e para o serviço, a não ser que um iniciar mais novo esteja a caminho. */
    private void encerrar(String motivo) {
        Log.i(TAG, motivo);
        desligar();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf(ultimoInicio);
    }

    /** Solta o GPS, os relógios e o celular acordado (também no onDestroy). */
    private void desligar() {
        coletando = false;
        principal.removeCallbacks(envioPeriodico);
        principal.removeCallbacks(fimDoPrazo);
        soltarOAcordado();
        try {
            pecas.fonte.parar();
        } catch (RuntimeException e) {
            // o Google Play services fora: não há pedido a soltar
        }
        if (emPrimeiroPlano == this) emPrimeiroPlano = null;
    }

    private void soltarOAcordado() {
        if (acordado != null && acordado.isHeld()) acordado.release();
    }

    /**
     * A permissão da coleta: a localização precisa, durante o uso (a que liga a coleta; o tempo todo, à parte, só a faz
     * voltar sozinha, R15). Sem ela o pedido de alta precisão é recusado, e a aproximada erra por quilômetros (o ponto no
     * mapa e o Abrir rota precisam dele).
     */
    static boolean temAPermissao(Context contexto) {
        return ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences preferencias(Context contexto) {
        return contexto.getApplicationContext().getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE);
    }

    private static long prazo(Context contexto) {
        try {
            return preferencias(contexto).getLong(CHAVE_PRAZO, 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    private static void esquecerOPrazo(Context contexto) {
        preferencias(contexto).edit().remove(CHAVE_PRAZO).apply();
    }

    /** As posições do FusedLocationProviderClient, entregues na thread principal. */
    static final class Fused implements FonteDePosicoes {
        private final Context app;
        private final FusedLocationProviderClient cliente;
        private Receptor receptor;
        private final LocationCallback callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult resultado) {
                Receptor r = receptor;
                if (r == null) return;
                for (Location l : resultado.getLocations()) r.posicao(l);
            }
        };

        Fused(Context app) {
            this.app = app;
            this.cliente = LocationServices.getFusedLocationProviderClient(app);
        }

        @Override
        public void pedir(long intervaloMs, boolean emDeslocamento, Receptor receptor) {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("sem a permissão de localização precisa");
            }
            this.receptor = receptor;
            LocationRequest pedido = new LocationRequest.Builder(emDeslocamento
                    ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervaloMs)
                // Nem mais depressa que o ritmo: outro app que pede o GPS a cada segundo não enche a fila de pontos.
                .setMinUpdateIntervalMillis(intervaloMs)
                .build();
            // O mesmo callback: o pedido novo (o ritmo que mudou) toma o lugar do anterior.
            cliente.requestLocationUpdates(pedido, callback, Looper.getMainLooper())
                .addOnFailureListener(receptor::falhou);
        }

        @Override
        public void parar() {
            receptor = null;
            cliente.removeLocationUpdates(callback);
        }
    }
}
