package br.com.gridco.campo;

import android.Manifest;
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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
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
 * {@link #parar} (o Sair) ou sem a permissão de localização.
 *
 * <ul>
 *   <li>Liga pelo {@link #iniciar}, só dentro da janela e com a localização precisa. O prazo do dia (as 22h do dia em que
 *       o app abriu) vai ao disco antes do pedido, e o religamento do sistema ({@code START_STICKY}, o processo que o
 *       Android matou) só volta a coletar até ele: sem o prazo, um religamento no dia seguinte coletaria sem o técnico ter
 *       aberto o app, e o desenho chama isso de fracasso (a folga, as férias).</li>
 *   <li>Coleta no ritmo do {@link RitmoDaColeta}: 2 min com a alta precisão em deslocamento, 10 min com a precisão
 *       equilibrada parado. Cada posição vai à {@link FilaDePontos}, e a cada {@link #ENVIO_MS} o {@link EnvioDePontos}
 *       manda o lote mais antigo, numa thread só para o disco e a rede.</li>
 *   <li>Para às 22h: o relógio é conferido no prazo, a cada envio e a cada posição, porque o Handler não conta o tempo em
 *       que o celular dorme. O {@link EnvioDePontos.Resultado#PARAR} (o 401 ou o coleta:false do Nexus) e a permissão
 *       tirada esquecem o prazo: o religamento não volta, e a próxima abertura do app decide de novo.</li>
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

    private static final String TAG = CascaPlugin.TAG;
    // As 22h, ou o prazo que o parar() esqueceu: o motivo vai ao log sem dizer qual.
    private static final String FORA_DO_PRAZO = "a coleta da equipe parou: fora do prazo do dia (as 22h ou desligada)";
    // O prazo do dia (as 22h do dia em que o app abriu, em epoch ms). Fora do backup, como tudo (regras_dados.xml).
    private static final String PREFERENCIAS = "equipe_servico";
    private static final String CHAVE_PRAZO = "prazo";

    /** As peças que o teste troca (o relógio, a fila, o envio, as posições e a thread); null é o de produção. */
    static Pecas pecasDeTeste;
    private static Pecas pecasDoProcesso;
    // O serviço que está em primeiro plano agora: é por ele que o parar() desliga. Só a thread principal o muda.
    private static volatile ServicoDaEquipe emPrimeiroPlano;

    private final Handler principal = new Handler(Looper.getMainLooper());
    private Pecas pecas;
    private int ultimoInicio;
    private volatile boolean coletando = false;
    private RitmoDaColeta ritmo;
    private Location ultima;
    private long intervaloPedido;

    /** O relógio de parede, a fila, o envio, a fonte das posições e a thread do disco e da rede: uma por processo. */
    static final class Pecas {
        final LongSupplier relogio;
        final FilaDePontos fila;
        final EnvioDePontos envio;
        final FonteDePosicoes fonte;
        final Executor execucao;

        Pecas(LongSupplier relogio, FilaDePontos fila, EnvioDePontos envio, FonteDePosicoes fonte, Executor execucao) {
            this.relogio = relogio;
            this.fila = fila;
            this.envio = envio;
            this.fonte = fonte;
            this.execucao = execucao;
        }

        // A mesma fila para quem grava e para quem manda: duas conexões no mesmo equipe.db disputariam a escrita.
        static Pecas deProducao(Context app) {
            LongSupplier relogio = System::currentTimeMillis;
            FilaDePontos fila = new FilaDePontos(app);
            EnvioDePontos envio = new EnvioDePontos(new ChaveDaEquipe(app, new CifraKeystore()), fila,
                new EnvioDePontos.Http(), relogio);
            Executor thread = Executors.newSingleThreadExecutor(tarefa -> new Thread(tarefa, "GcCasca-equipe"));
            return new Pecas(relogio, fila, envio, new Fused(app), thread);
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
     * Liga a coleta, se a hora do celular está na janela e a localização precisa foi dada. Quem decide se a coleta vale
     * para o técnico é a casca, antes (o Nexus, pela chave do aparelho). Chamar de novo com ela ligada não faz outra.
     */
    static void iniciar(Context contexto) {
        Context app = contexto.getApplicationContext();
        long agora = pecas(app).relogio.getAsLong();
        TimeZone fuso = TimeZone.getDefault();
        if (!JanelaDaEquipe.dentro(agora, fuso)) {
            Log.i(TAG, "coleta da equipe não ligada: fora da janela das 6h às 22h");
            return;
        }
        if (!temLocalizacaoPrecisa(app)) {
            // Do Android 14 em diante o primeiro plano de localização sem ela lança, e o serviço pedido em primeiro plano
            // que não chega a ele derruba o app.
            Log.w(TAG, "coleta da equipe não ligada: sem a permissão de localização");
            return;
        }
        preferencias(app).edit().putLong(CHAVE_PRAZO, JanelaDaEquipe.fimDaJanela(agora, fuso)).apply();
        try {
            ContextCompat.startForegroundService(app, new Intent(app, ServicoDaEquipe.class));
        } catch (RuntimeException e) {
            // O Android 12+ recusa o primeiro plano pedido com o app no fundo: a próxima abertura religa.
            Log.w(TAG, "o Android não deixou ligar a coleta da equipe agora: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Desliga a coleta (o Sair, a coleta que deixou de valer). O religamento do sistema não volta, e o serviço que ainda
     * não entrou em primeiro plano entra e sai logo.
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

    @Override
    public void onCreate() {
        super.onCreate();
        pecas = pecas(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        ultimoInicio = startId;
        long agora = pecas.relogio.getAsLong();
        // O religamento do sistema chega sem o Intent. Fora do prazo do dia em que o app abriu ele não volta, e sai antes do
        // primeiro plano (nada de notificação às 23h nem no dia seguinte): o Android só exige o startForeground de quem
        // foi pedido pelo startForegroundService, o do iniciar.
        if (intent == null && !valeColetar(agora)) {
            Log.i(TAG, "religamento da coleta da equipe fora do dia em que o app abriu: não volta");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!entrarEmPrimeiroPlano()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!valeColetar(agora)) {
            // O parar que chegou antes deste início, ou as 22h que passaram entre o iniciar e aqui.
            encerrar("coleta da equipe fora do prazo: não ligou");
            return START_NOT_STICKY;
        }
        if (!coletando) ligarColeta(agora);
        return coletando ? START_STICKY : START_NOT_STICKY;
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
        if (!temLocalizacaoPrecisa(this)) {
            Log.w(TAG, "sem a permissão de localização: a coleta da equipe não ligou");
            return false;
        }
        CanaisDeNotificacao.criarDaEquipe(this);
        try {
            ServiceCompat.startForeground(this, ID_DA_NOTIFICACAO, notificacao(), tipoDeLocalizacao());
        } catch (RuntimeException e) {
            // O Android 12+ recusa o primeiro plano do religamento com o app no fundo
            // (ForegroundServiceStartNotAllowedException): só registra, e a próxima abertura religa.
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
        String texto = getString(R.string.notificacao_equipe_texto);
        return new NotificationCompat.Builder(this, CANAL_EQUIPE)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle(getString(R.string.notificacao_equipe_titulo))
            .setContentText(texto)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(toque)
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
        if (!pedirPosicoes()) return;
        principal.postDelayed(envioPeriodico, ENVIO_MS);
        agendarOFim(agora);
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
        // A permissão tirada: o log diz só o motivo (o texto da exceção fica de fora), e o religamento não volta.
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
            encerrar(FORA_DO_PRAZO);
            return;
        }
        // Sem a velocidade do aparelho, a distância decide sozinha (o ritmo pede as duas: abaixo de 1 m/s e a menos de
        // 150 m); a primeira leitura não tem com quem comparar e conta como deslocamento.
        Float velocidade = l.hasSpeed() ? l.getSpeed() : null;
        double metros = ultima == null ? Double.POSITIVE_INFINITY : ultima.distanceTo(l);
        ultima = new Location(l);
        long intervalo = ritmo.proximoIntervaloMs(velocidade == null ? 0 : velocidade, metros);
        boolean emDeslocamento = intervalo == RitmoDaColeta.EM_DESLOCAMENTO_MS;
        // O "mov" é o estado do ritmo (parado só depois de três leituras paradas seguidas), o mesmo que decide quando vem
        // o próximo ponto: o mapa vê deslocamento enquanto o celular manda a cada 2 min.
        PontoDaEquipe ponto = new PontoDaEquipe(l.getTime(), l.getLatitude(), l.getLongitude(), l.getAccuracy(),
            velocidade, emDeslocamento);
        executar(() -> pecas.fila.guardar(ponto));
        if (intervalo != intervaloPedido) {
            intervaloPedido = intervalo;
            pedirPosicoes();
        }
    }

    private final Runnable envioPeriodico = new Runnable() {
        @Override
        public void run() {
            if (!coletando) return;
            if (!valeColetar(pecas.relogio.getAsLong())) {
                encerrar(FORA_DO_PRAZO);
                return;
            }
            principal.postDelayed(this, ENVIO_MS);
            executar(() -> {
                EnvioDePontos.Resultado r = pecas.envio.enviar();
                principal.post(() -> aposOEnvio(r));
            });
        }
    };

    private void aposOEnvio(EnvioDePontos.Resultado r) {
        if (r != EnvioDePontos.Resultado.PARAR || !coletando) return;
        // O 401 (a chave que o Nexus não reconhece) ou o coleta:false: a chave já saiu (EnvioDePontos), e o religamento não
        // volta. A próxima abertura do app decide de novo, com o Nexus.
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
                encerrar(FORA_DO_PRAZO);
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
        return JanelaDaEquipe.dentro(agora, TimeZone.getDefault()) && agora < prazo(this);
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

    /** Solta o GPS e os relógios (também no onDestroy). */
    private void desligar() {
        coletando = false;
        principal.removeCallbacks(envioPeriodico);
        principal.removeCallbacks(fimDoPrazo);
        try {
            pecas.fonte.parar();
        } catch (RuntimeException e) {
            // o Google Play services fora: não há pedido a soltar
        }
        if (emPrimeiroPlano == this) emPrimeiroPlano = null;
    }

    /** A localização precisa: sem ela o pedido de alta precisão é recusado, e a aproximada erra por quilômetros. */
    private static boolean temLocalizacaoPrecisa(Context contexto) {
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
