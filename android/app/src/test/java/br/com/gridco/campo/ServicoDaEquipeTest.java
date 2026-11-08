package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Looper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowService;

/**
 * O serviço da coleta da equipe, com o relógio, as posições e o Nexus falsos: o Robolectric não tem o Google Play
 * services, e as rotas do Nexus ainda não estão no ar. O relógio é o de Brasília numa segunda-feira; as posições são
 * fictícias (um ponto qualquer, sem usina nem casa de ninguém).
 */
@RunWith(RobolectricTestRunner.class)
public class ServicoDaEquipeTest {

    private static final TimeZone BRASILIA = TimeZone.getTimeZone("America/Sao_Paulo");
    private static final long UM_DIA_MS = 24L * 60L * 60L * 1000L;
    private static final double LAT = -15.60;
    private static final double LON = -56.10;

    /** As posições falsas: guardam o último pedido (o ritmo e a precisão) e entregam o que o teste mandar. */
    private static final class FonteFalsa implements ServicoDaEquipe.FonteDePosicoes {
        long intervalo;
        boolean emDeslocamento;
        int pedidos;
        boolean parada;
        RuntimeException lancar;
        ServicoDaEquipe.FonteDePosicoes.Receptor receptor;

        @Override
        public void pedir(long intervaloMs, boolean emDeslocamento, ServicoDaEquipe.FonteDePosicoes.Receptor receptor) {
            if (lancar != null) throw lancar;
            pedidos++;
            this.intervalo = intervaloMs;
            this.emDeslocamento = emDeslocamento;
            this.receptor = receptor;
            parada = false;
        }

        @Override
        public void parar() {
            parada = true;
        }
    }

    private Application app;
    private TimeZone fusoDeAntes;
    private long agora;
    private ChaveDaEquipe chave;
    private FilaDePontos fila;
    private FonteFalsa fonte;
    private final List<String> pedidosAoNexus = new ArrayList<>();
    private EnvioDePontos.Resposta resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":1,\"coleta\":true}");
    private final List<ServiceController<ServicoDaEquipe>> servicos = new ArrayList<>();

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        fusoDeAntes = TimeZone.getDefault();
        TimeZone.setDefault(BRASILIA);
        app.deleteDatabase(FilaDePontos.NOME_DO_BANCO);
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS);
        chave = new ChaveDaEquipe(app, new CifraFalsa());
        fila = new FilaDePontos(app);
        fonte = new FonteFalsa();
        EnvioDePontos envio = new EnvioDePontos(chave, fila, (url, k, corpo) -> {
            pedidosAoNexus.add(url);
            return resposta;
        }, () -> agora);
        // O disco e a rede na thread do teste: a fila e o envio rodam na hora, sem outra thread para esperar.
        ServicoDaEquipe.pecasDeTeste = new ServicoDaEquipe.Pecas(() -> agora, fila, envio, fonte, Runnable::run);
        ShadowLog.clear();
    }

    @After
    public void fechar() {
        morrerOProcesso();
        ServicoDaEquipe.pecasDeTeste = null;
        TimeZone.setDefault(fusoDeAntes);
        app.deleteDatabase(FilaDePontos.NOME_DO_BANCO);
    }

    /** Segunda, 09/11/2026, na hora de Brasília. */
    private static long em(int hora, int minuto) {
        Calendar c = Calendar.getInstance(BRASILIA);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 9, hora, minuto, 0);
        return c.getTimeInMillis();
    }

    /** O iniciar e o que o Android faz com o pedido: cria o serviço e entrega o Intent ao onStartCommand. */
    private ServicoDaEquipe ligar() {
        ServicoDaEquipe.iniciar(app);
        Intent pedido = shadowOf(app).getNextStartedService();
        assertNotNull("o iniciar pediu o serviço ao Android", pedido);
        assertEquals(ServicoDaEquipe.class.getName(), pedido.getComponent().getClassName());
        return entregar(pedido);
    }

    /**
     * O religamento do sistema (START_STICKY): o Android cria o serviço de novo e entrega o Intent nulo. O
     * ServiceController troca o nulo por um Intent com o componente, então o onStartCommand é chamado direto.
     */
    private ServicoDaEquipe religarPeloSistema() {
        return religarPeloSistema(null);
    }

    private ServicoDaEquipe religarPeloSistema(Exception recusaDoAndroid) {
        ServiceController<ServicoDaEquipe> c = Robolectric.buildService(ServicoDaEquipe.class).create();
        servicos.add(c);
        if (recusaDoAndroid != null) shadowOf(c.get()).setThrowInStartForeground(recusaDoAndroid);
        c.get().onStartCommand(null, 0, 1);
        return c.get();
    }

    private ServicoDaEquipe entregar(Intent pedido) {
        ServiceController<ServicoDaEquipe> c = Robolectric.buildService(ServicoDaEquipe.class, pedido).create();
        servicos.add(c);
        c.startCommand(0, 1);
        return c.get();
    }

    /** O processo que o Android matou: o que é do processo some (no teste, pelo onDestroy). */
    private void morrerOProcesso() {
        for (ServiceController<ServicoDaEquipe> s : servicos) s.destroy();
        servicos.clear();
    }

    private static Location posicao(long t, double lat, double lon, Float velocidade) {
        Location l = new Location("teste");
        l.setTime(t);
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAccuracy(8f);
        if (velocidade != null) l.setSpeed(velocidade);
        return l;
    }

    /** Algum log (de qualquer tag), na mensagem ou na exceção, tem o trecho. */
    private static boolean logou(String trecho) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.msg != null && item.msg.contains(trecho)) return true;
            if (item.throwable != null && String.valueOf(item.throwable.getMessage()).contains(trecho)) return true;
        }
        return false;
    }

    @Test
    public void oManifestoDeclaraOServicoDeLocalizacaoEAsTresPermissoesNovas() throws Exception {
        PackageManager pm = app.getPackageManager();
        ServiceInfo servico = pm.getServiceInfo(new ComponentName(app, ServicoDaEquipe.class), 0);
        assertFalse("não exportado: só a casca o liga", servico.exported);
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, servico.getForegroundServiceType());
        List<String> pedidas = Arrays.asList(
            pm.getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions);
        assertTrue(pedidas.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        assertTrue(pedidas.contains(Manifest.permission.FOREGROUND_SERVICE));
        assertTrue(pedidas.contains(Manifest.permission.FOREGROUND_SERVICE_LOCATION));
    }

    @Test
    public void iniciarDentroDaJanelaPoeOServicoEmPrimeiroPlanoComANotificacaoNoCanalDaEquipe() {
        agora = em(7, 30);
        ServicoDaEquipe s = ligar();

        ShadowService sombra = shadowOf(s);
        Notification n = sombra.getLastForegroundNotification();
        assertNotNull("em primeiro plano, com a notificação fixa", n);
        assertEquals(ServicoDaEquipe.ID_DA_NOTIFICACAO, sombra.getLastForegroundNotificationId());
        assertEquals("equipe_localizacao", n.getChannelId());
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, s.getForegroundServiceType());
        assertTrue("fixa (até o Android 13 o técnico não a dispensa)", (n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertEquals(app.getString(R.string.notificacao_equipe_titulo),
            n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals(app.getString(R.string.notificacao_equipe_texto),
            n.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertTrue(ServicoDaEquipe.rodando());
        assertEquals("as posições no ritmo de deslocamento", RitmoDaColeta.EM_DESLOCAMENTO_MS, fonte.intervalo);
        assertTrue("com a alta precisão", fonte.emDeslocamento);

        // O canal: importância baixa, sem som, sem vibração, sem o ponto no ícone e sem furar o Não perturbe.
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel canal = nm.getNotificationChannel("equipe_localizacao");
        assertNotNull("o serviço cria o canal antes do primeiro plano", canal);
        assertEquals(app.getString(R.string.notificacao_canal_equipe_localizacao), canal.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_LOW, canal.getImportance());
        assertNull(canal.getSound());
        assertFalse(canal.shouldVibrate());
        assertFalse(canal.canShowBadge());
        assertFalse(canal.canBypassDnd());
    }

    @Test
    public void iniciarForaDaJanelaNaoPedeOServico() {
        agora = em(5, 59);
        ServicoDaEquipe.iniciar(app);
        agora = em(22, 0);
        ServicoDaEquipe.iniciar(app);
        assertNull("nem às 5h59 nem às 22h", shadowOf(app).getNextStartedService());
        assertFalse(ServicoDaEquipe.rodando());
    }

    @Test
    public void iniciadoAs21h59ParaSozinhoAoPassarDas22h() {
        agora = em(21, 59);
        ServicoDaEquipe s = ligar();
        assertTrue(ServicoDaEquipe.rodando());

        agora = em(22, 0) + 1_000;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));

        ShadowService sombra = shadowOf(s);
        assertTrue("saiu do primeiro plano", sombra.isForegroundStopped());
        assertTrue("e a notificação saiu da barra", sombra.getNotificationShouldRemoved());
        assertTrue(sombra.isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        assertTrue("o GPS foi solto", fonte.parada);
    }

    @Test
    public void oEnvioQueDevolvePararDesligaOServicoETiraANotificacao() {
        agora = em(10, 0);
        chave.guardar("chave-ficticia");
        fila.guardar(new PontoDaEquipe(System.currentTimeMillis(), LAT, LON, 8f, 0f, false));
        resposta = new EnvioDePontos.Resposta(401, "");
        ServicoDaEquipe s = ligar();

        // O envio sai a cada 2 min: antes disso, nada vai ao Nexus.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS - 1_000));
        assertTrue(pedidosAoNexus.isEmpty());
        assertTrue(ServicoDaEquipe.rodando());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(Arrays.asList(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_POSICOES), pedidosAoNexus);
        ShadowService sombra = shadowOf(s);
        assertTrue(sombra.isForegroundStopped());
        assertTrue("a notificação saiu da barra", sombra.getNotificationShouldRemoved());
        assertTrue(sombra.isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        assertNull("o 401 apagou a chave", chave.chave());

        // E o religamento do sistema não volta a coletar: só a próxima abertura do app decide de novo.
        morrerOProcesso();
        ServicoDaEquipe depois = religarPeloSistema();
        assertEquals("nem entra em primeiro plano", 0, shadowOf(depois).getLastForegroundNotificationId());
        assertFalse(ServicoDaEquipe.rodando());
    }

    @Test
    public void cadaPosicaoVaiAFilaEOPedidoAoGpsSegueORitmo() throws Exception {
        agora = em(10, 0);
        ligar();
        // A hora das posições é a do relógio de verdade: a fila poda pelo relógio do aparelho o que passou de 24 h.
        long t0 = System.currentTimeMillis() - 60_000;

        // Parado: abaixo de 1 m/s e no mesmo lugar. A primeira leitura não tem com quem comparar; as três paradas seguidas
        // depois dela levam ao ritmo de parado, com a precisão equilibrada.
        for (int i = 0; i < 4; i++) fonte.receptor.posicao(posicao(t0 + i * 1_000L, LAT, LON, 0.2f));
        assertEquals(RitmoDaColeta.PARADO_MS, fonte.intervalo);
        assertFalse(fonte.emDeslocamento);
        assertEquals(4, fila.tamanho());

        JSONArray pontos = fila.lote(fila.idsDoLote(10));
        JSONObject primeiro = pontos.getJSONObject(0);
        assertEquals(t0, primeiro.getLong("t"));
        assertEquals(LAT, primeiro.getDouble("lat"), 1e-9);
        assertEquals(LON, primeiro.getDouble("lon"), 1e-9);
        assertEquals(8.0, primeiro.getDouble("prec"), 1e-6);
        assertEquals(0.2, primeiro.getDouble("vel"), 1e-6);
        assertTrue("em deslocamento até a terceira parada seguida", primeiro.getBoolean("mov"));
        assertFalse("parado", pontos.getJSONObject(3).getBoolean("mov"));

        // Uma leitura longe e a 12 m/s volta ao ritmo curto, com a alta precisão; a que vem sem velocidade vai sem o "vel".
        fonte.receptor.posicao(posicao(t0 + 10_000, LAT + 0.05, LON, 12f));
        assertEquals(RitmoDaColeta.EM_DESLOCAMENTO_MS, fonte.intervalo);
        assertTrue(fonte.emDeslocamento);
        fonte.receptor.posicao(posicao(t0 + 20_000, LAT + 0.10, LON, null));
        JSONObject ultimo = fila.lote(fila.idsDoLote(10)).getJSONObject(5);
        assertFalse(ultimo.has("vel"));
        assertTrue(ultimo.getBoolean("mov"));

        assertFalse("nada de coordenada no log", logou("-15.6"));
        assertFalse(logou("-56.1"));
    }

    @Test
    public void aPermissaoTiradaParaOServicoComUmLogSemConteudo() {
        agora = em(10, 0);
        fonte.lancar = new SecurityException("Client must have ACCESS_FINE_LOCATION permission to request locations.");
        ServicoDaEquipe s = ligar();

        ShadowService sombra = shadowOf(s);
        assertTrue(sombra.isForegroundStopped());
        assertTrue(sombra.isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        assertTrue(logou("sem a permissão de localização"));
        assertFalse("o log não leva o texto da exceção", logou("Client must have"));
    }

    @Test
    public void aFalhaDasPosicoesDepoisDeLigadoTambemParaOServico() {
        // O pedido ao Google Play services falha depois (a tarefa dele): a permissão tirada ou o serviço dele fora.
        agora = em(10, 0);
        ServicoDaEquipe s = ligar();
        fonte.receptor.falhou(new SecurityException("Client must have ACCESS_FINE_LOCATION permission"));

        assertTrue(shadowOf(s).isStoppedBySelf());
        assertTrue(shadowOf(s).getNotificationShouldRemoved());
        assertFalse(ServicoDaEquipe.rodando());
        assertFalse("o log não leva o texto da exceção", logou("Client must have"));
    }

    @Test
    public void oReligamentoDoSistemaSoVoltaNoDiaEmQueOAppAbriu() {
        agora = em(10, 0);
        ligar();
        morrerOProcesso();

        // No mesmo dia, antes das 22h: volta, em primeiro plano.
        agora = em(15, 0);
        ServicoDaEquipe mesmoDia = religarPeloSistema();
        assertNotNull(shadowOf(mesmoDia).getLastForegroundNotification());
        assertTrue(ServicoDaEquipe.rodando());
        morrerOProcesso();

        // No dia seguinte, sem o técnico abrir o app (a folga, as férias): não volta, e a notificação nem aparece.
        agora = em(10, 0) + UM_DIA_MS;
        ServicoDaEquipe outroDia = religarPeloSistema();
        assertEquals("nem entra em primeiro plano", 0, shadowOf(outroDia).getLastForegroundNotificationId());
        assertTrue(shadowOf(outroDia).isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        assertEquals("nada pedido ao GPS no outro dia", 2, fonte.pedidos);
    }

    @Test
    public void oReligamentoQueOAndroidRecusaEmPrimeiroPlanoSoRegistraEPara() {
        agora = em(10, 0);
        ligar();
        morrerOProcesso();
        int pedidosAntes = fonte.pedidos;

        agora = em(11, 0);
        ServicoDaEquipe s = religarPeloSistema(new ForegroundServiceStartNotAllowedException("no fundo"));

        assertTrue(shadowOf(s).isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        assertEquals("nada pedido ao GPS", pedidosAntes, fonte.pedidos);
        assertTrue(logou("o Android recusou"));
    }

    @Test
    public void pararDesligaOServicoEOReligamentoNaoVolta() {
        agora = em(10, 0);
        ServicoDaEquipe s = ligar();
        ServicoDaEquipe.parar(app);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(shadowOf(s).isForegroundStopped());
        assertTrue(shadowOf(s).getNotificationShouldRemoved());
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        morrerOProcesso();
        assertEquals("o religamento não volta", 0, shadowOf(religarPeloSistema()).getLastForegroundNotificationId());
    }

    @Test
    public void pararAntesDeOPedidoChegarAoServicoEntraEmPrimeiroPlanoESaiLogo() {
        // O Android derruba o app cujo serviço pedido em primeiro plano para antes do startForeground: o parar que chega
        // antes do onStartCommand não pode parar o serviço ali; o onStartCommand entra em primeiro plano e sai em seguida.
        agora = em(10, 0);
        ServicoDaEquipe.iniciar(app);
        Intent pedido = shadowOf(app).getNextStartedService();
        ServicoDaEquipe.parar(app);
        shadowOf(Looper.getMainLooper()).idle();

        ServicoDaEquipe s = entregar(pedido);
        ShadowService sombra = shadowOf(s);
        // O id só nasce no startForeground (a notificação em si o stopForeground já tirou).
        assertEquals("entrou em primeiro plano", ServicoDaEquipe.ID_DA_NOTIFICACAO, sombra.getLastForegroundNotificationId());
        assertTrue("e saiu", sombra.isForegroundStopped());
        assertTrue(sombra.getNotificationShouldRemoved());
        assertTrue(sombra.isStoppedBySelf());
        assertEquals("o stopSelf leva o número do último início", 1, sombra.getStopSelfId());
        assertFalse(ServicoDaEquipe.rodando());
        assertEquals("nada pedido ao GPS", 0, fonte.pedidos);
    }
}
