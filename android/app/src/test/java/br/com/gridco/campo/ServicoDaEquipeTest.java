package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;

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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.shadows.ShadowPowerManager;
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
    // A conta da chave (o SHA-256 que a página calcula), fictícia: o serviço não a usa.
    private static final String CONTA = "c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00";

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
    // O relógio desde o boot, o que anda com o celular dormindo: o teste o avança sem rodar o laço principal (o Handler),
    // como o celular com a tela apagada.
    private long desdeOBoot = 50_000_000L;
    private ChaveDaEquipe chave;
    private FilaDePontos fila;
    private FonteFalsa fonte;
    private EnvioDePontos envio;
    private final List<String> pedidosAoNexus = new ArrayList<>();
    private String ultimoCorpo;
    private EnvioDePontos.Resposta resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":1,\"coleta\":true}");
    private final List<ServiceController<ServicoDaEquipe>> servicos = new ArrayList<>();
    // O que o último religamento pelo sistema devolveu ao Android (o START_STICKY ou o START_NOT_STICKY).
    private int retornoDoReligamento;

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        fusoDeAntes = TimeZone.getDefault();
        TimeZone.setDefault(BRASILIA);
        app.deleteDatabase(FilaDePontos.NOME_DO_BANCO);
        // A localização durante o uso (a precisa e a aproximada) e a notificação: a o tempo todo não é pedida (R10).
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS);
        chave = new ChaveDaEquipe(app, new CifraFalsa());
        fila = new FilaDePontos(app);
        fonte = new FonteFalsa();
        montarAsPecas();
        ShadowLog.clear();
    }

    /** O envio com o Nexus falso e as peças do processo com a chave de agora. */
    private void montarAsPecas() {
        envio = new EnvioDePontos(chave, fila, (url, k, corpo) -> {
            pedidosAoNexus.add(url);
            ultimoCorpo = corpo;
            return resposta;
        }, () -> agora);
        // O disco e a rede na thread do teste: a fila e o envio rodam na hora, sem outra thread para esperar.
        ServicoDaEquipe.pecasDeTeste = new ServicoDaEquipe.Pecas(() -> agora, () -> desdeOBoot, chave, fila, envio,
            fonte, Runnable::run);
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
        retornoDoReligamento = c.get().onStartCommand(null, 0, 1);
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
        return posicao(t, lat, lon, velocidade, 8f);
    }

    private static Location posicao(long t, double lat, double lon, Float velocidade, float precisao) {
        Location l = new Location("teste");
        l.setTime(t);
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAccuracy(precisao);
        if (velocidade != null) l.setSpeed(velocidade);
        return l;
    }

    private NotificationManager notificacoes() {
        return (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** O técnico liga ou desliga o canal da equipe nos ajustes do Android. */
    private void canalDaEquipe(int importancia) {
        notificacoes().getNotificationChannel(ServicoDaEquipe.CANAL_EQUIPE).setImportance(importancia);
    }

    private void rodarOLacoPrincipal() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** O mov de cada ponto da fila, na ordem em que entraram. */
    private List<Boolean> movsDaFila() throws Exception {
        JSONArray pontos = fila.lote(fila.idsDoLote(FilaDePontos.MAXIMO));
        List<Boolean> movs = new ArrayList<>();
        for (int i = 0; i < pontos.length(); i++) movs.add(pontos.getJSONObject(i).getBoolean("mov"));
        return movs;
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
    public void oManifestoDeclaraOServicoDeLocalizacaoEAsPermissoesSemAOTempoTodo() throws Exception {
        PackageManager pm = app.getPackageManager();
        ServiceInfo servico = pm.getServiceInfo(new ComponentName(app, ServicoDaEquipe.class), 0);
        assertFalse("não exportado: só a casca o liga", servico.exported);
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, servico.getForegroundServiceType());
        List<String> pedidas = Arrays.asList(
            pm.getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions);
        // O manifesto mesclado (o do app e o das bibliotecas): a localização o tempo todo saiu (R10), e a durante o uso fica.
        assertFalse("sem a localização o tempo todo", pedidas.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        assertTrue(pedidas.contains(Manifest.permission.ACCESS_FINE_LOCATION));
        assertTrue(pedidas.contains(Manifest.permission.ACCESS_COARSE_LOCATION));
        assertTrue(pedidas.contains(Manifest.permission.FOREGROUND_SERVICE));
        assertTrue(pedidas.contains(Manifest.permission.FOREGROUND_SERVICE_LOCATION));
        assertTrue("o celular acordado durante o envio", pedidas.contains(Manifest.permission.WAKE_LOCK));
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
        assertTrue("fixa (até o Android 13 o técnico não a dispensa; do 14 em diante, arrastada, ela volta)",
            (n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
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
        chave.guardar("chave-ficticia", CONTA);
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
        // O mov é da própria leitura: as quatro vão paradas, também as três primeiras, que o ritmo ainda conta como
        // deslocamento (o técnico em casa de manhã, com o app aberto antes de sair).
        assertEquals(Arrays.asList(false, false, false, false), movsDaFila());

        // Uma leitura longe e a 12 m/s volta ao ritmo curto, com a alta precisão; a que vem sem velocidade vai sem o "vel".
        fonte.receptor.posicao(posicao(t0 + 10_000, LAT + 0.05, LON, 12f));
        assertEquals(RitmoDaColeta.EM_DESLOCAMENTO_MS, fonte.intervalo);
        assertTrue(fonte.emDeslocamento);
        fonte.receptor.posicao(posicao(t0 + 20_000, LAT + 0.10, LON, null));
        JSONObject ultimo = fila.lote(fila.idsDoLote(10)).getJSONObject(5);
        assertFalse(ultimo.has("vel"));
        assertEquals("em deslocamento pela velocidade e, sem ela, pela distância",
            Arrays.asList(false, false, false, false, true, true), movsDaFila());

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

    // ---- A localização durante o uso basta, e a precisa tirada para a coleta ---------------------------------------------

    @Test
    public void aLocalizacaoDuranteOUsoBastaParaAColetaSemOTempoTodo() {
        // R10: o serviço do tipo localização iniciado com o app à vista mantém a localização "durante o uso" enquanto roda,
        // também com o app fechado. Antes, sem a o tempo todo (Android 10+) a coleta nem ligava.
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        ligar();
        assertTrue(ServicoDaEquipe.rodando());
        fonte.receptor.posicao(posicao(System.currentTimeMillis(), LAT, LON, 0.2f));
        assertEquals(1, fila.tamanho());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS));
        assertEquals(Arrays.asList(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_POSICOES), pedidosAoNexus);
        assertTrue(ServicoDaEquipe.rodando());
    }

    @Test
    public void aPosicaoQueChegaSemALocalizacaoPrecisaNaoEntraNaFilaEOServicoPara() {
        // O técnico troca a precisa pela aproximada (ou tira a localização) com a coleta ligada, no aparelho em que o
        // processo não morre: a coleta seguia até a próxima volta ao app.
        agora = em(10, 0);
        ServicoDaEquipe s = ligar();
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        fonte.receptor.posicao(posicao(System.currentTimeMillis(), LAT, LON, 0.2f));

        assertEquals("a posição não entra", 0, fila.tamanho());
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertTrue(shadowOf(s).getNotificationShouldRemoved());
        assertFalse(ServicoDaEquipe.rodando());
        assertTrue("o GPS foi solto", fonte.parada);
        assertTrue(logou("sem a permissão de localização"));
    }

    @Test
    public void oEnvioSemALocalizacaoPrecisaNaoMandaEOServicoPara() {
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        fila.guardar(new PontoDaEquipe(System.currentTimeMillis(), LAT, LON, 8f, 0f, false));
        ServicoDaEquipe s = ligar();
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS));
        assertTrue("nada foi ao Nexus", pedidosAoNexus.isEmpty());
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
    }

    @Test
    public void soComAAproximadaOIniciarNaoPedeOServicoEOInicioQueJaVinhaNaoLiga() {
        // A aproximada erra por quilômetros (o mapa e o Abrir rota precisam do ponto) e o pedido de alta precisão é
        // recusado sem a precisa.
        agora = em(10, 0);
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        ServicoDaEquipe.iniciar(app);
        assertNull("o iniciar não pede o serviço", shadowOf(app).getNextStartedService());

        // A precisa tirada entre o iniciar e o onStartCommand: o serviço não liga nem pede o GPS.
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        ServicoDaEquipe.iniciar(app);
        Intent pedido = shadowOf(app).getNextStartedService();
        assertNotNull(pedido);
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        ServicoDaEquipe s = entregar(pedido);
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        assertEquals("nada pedido ao GPS", 0, fonte.pedidos);
    }

    // ---- O serviço só liga com o app à vista: o religamento do sistema não volta (R10) ----------------------------------

    @Test
    public void aColetaLigadaNaoPedeAoSistemaParaReligarOServico() {
        // Só com a durante o uso, o serviço que o sistema religasse com o app no fundo não teria a localização (do Android 11
        // em diante; do 14 em diante o primeiro plano dele lança), e do 12 em diante o primeiro plano no fundo é recusado.
        agora = em(10, 0);
        ServicoDaEquipe.iniciar(app);
        Intent pedido = shadowOf(app).getNextStartedService();
        ServiceController<ServicoDaEquipe> c = Robolectric.buildService(ServicoDaEquipe.class, pedido).create();
        servicos.add(c);
        assertEquals("o processo morto não religa o serviço", Service.START_NOT_STICKY, c.get().onStartCommand(pedido, 0, 1));
        assertTrue(ServicoDaEquipe.rodando());
    }

    @Test
    public void oReligamentoDoSistemaNaoVoltaNemNoMesmoDia() {
        // O Android que mata o app (a falta de memória, o "tirar dos recentes" de quem mata o app) para a coleta até a
        // próxima abertura: o religamento, se vier, sai sem o primeiro plano e sem pedir o GPS.
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        ligar();
        morrerOProcesso();
        int pedidosAntes = fonte.pedidos;

        agora = em(10, 5);
        ServicoDaEquipe s = religarPeloSistema();
        assertEquals("nem entra em primeiro plano", 0, shadowOf(s).getLastForegroundNotificationId());
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertEquals(Service.START_NOT_STICKY, retornoDoReligamento);
        assertFalse(ServicoDaEquipe.rodando());
        assertEquals("nada pedido ao GPS", pedidosAntes, fonte.pedidos);
        desdeOBoot += ServicoDaEquipe.ENVIO_MS;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS));
        assertTrue("nem envio", pedidosAoNexus.isEmpty());
        assertTrue(logou("não volta"));

        // A próxima abertura do app liga de novo.
        ligar();
        assertTrue(ServicoDaEquipe.rodando());
    }

    @Test
    public void aNotificacaoArrastadaQueChegaComOServicoParadoNaoLigaAColeta() {
        // O deleteIntent que chegasse com o processo já morto (o prazo do dia ainda no disco): um início com o app no
        // fundo, que antes ligava a coleta sem a tela.
        agora = em(10, 0);
        ligar();
        PendingIntent aoArrastar = shadowOf(notificacoes()).getNotification(ServicoDaEquipe.ID_DA_NOTIFICACAO).deleteIntent;
        morrerOProcesso();
        int pedidosAntes = fonte.pedidos;
        try {
            aoArrastar.send();
        } catch (PendingIntent.CanceledException e) {
            throw new AssertionError(e);
        }
        Intent repor = shadowOf(app).getNextStartedService();
        assertNotNull(repor);
        assertEquals(ServicoDaEquipe.REPOR, repor.getAction());
        ServicoDaEquipe s = entregar(repor);
        assertEquals("nem entra em primeiro plano", 0, shadowOf(s).getLastForegroundNotificationId());
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        assertEquals("nada pedido ao GPS", pedidosAntes, fonte.pedidos);
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.N)
    public void noAndroid7APrecisaBastaEOReligamentoTambemNaoVolta() {
        agora = em(10, 0);
        ligar();
        assertTrue(ServicoDaEquipe.rodando());
        fonte.receptor.posicao(posicao(System.currentTimeMillis(), LAT, LON, 0.2f));
        assertEquals(1, fila.tamanho());
        morrerOProcesso();
        assertEquals("o religamento não volta", 0, shadowOf(religarPeloSistema()).getLastForegroundNotificationId());
        assertFalse(ServicoDaEquipe.rodando());
    }

    // ---- O envio espera a conta conferida -------------------------------------------------------------------------------

    @Test
    public void oEnvioQueEsperaAContaNaoAcordaOCelularNemRecomecaAContaDoEnvio() {
        // Na partida a frio com a tela, à espera da página: nem o Handler nem a posição com o envio vencido acordam o
        // celular, e a conta do envio não recomeça, para o primeiro lote depois da conferência não esperar mais 2 min.
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        chave = new ChaveDaEquipe(app, new CifraFalsa());
        montarAsPecas();
        ligar();
        long t0 = System.currentTimeMillis() - 300_000;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS));
        desdeOBoot += ServicoDaEquipe.ENVIO_MS;
        fonte.receptor.posicao(posicao(t0, LAT, LON, 0.2f));
        assertTrue(pedidosAoNexus.isEmpty());
        assertEquals("o celular nem foi acordado", 0, shadowOf(ShadowPowerManager.getLatestWakeLock()).getTimesHeld());

        // A página confere 30 s depois: a posição seguinte manda na hora.
        assertTrue(chave.conferir(CONTA));
        desdeOBoot += 30_000;
        fonte.receptor.posicao(posicao(t0 + 30_000, LAT, LON, 0.2f));
        assertEquals(Arrays.asList(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_POSICOES), pedidosAoNexus);
        rodarOLacoPrincipal();
        assertEquals(0, fila.tamanho());

        // E o Handler seguiu de pé durante a espera: com o app aberto, ele manda de novo 2 min depois do envio.
        fila.guardar(new PontoDaEquipe(System.currentTimeMillis(), LAT, LON, 8f, 0f, false));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS));
        assertEquals(2, pedidosAoNexus.size());
    }

    @Test
    public void comOAppAbertoOHandlerMandaACada2Minutos() {
        // O reagendamento: cada envio do Handler marca o seguinte, sem precisar de posição.
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        ligar();
        for (int i = 1; i <= 3; i++) {
            fila.guardar(new PontoDaEquipe(System.currentTimeMillis(), LAT, LON, 8f, 0f, false));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS - 1_000));
            assertEquals("antes dos 2 min do " + i + "º", i - 1, pedidosAoNexus.size());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals("o " + i + "º envio, aos " + (2 * i) + " min", i, pedidosAoNexus.size());
            assertEquals(0, fila.tamanho());
        }
        assertTrue(ServicoDaEquipe.rodando());
    }

    // ---- As 22h: o último envio ---------------------------------------------------------------------------------------

    @Test
    public void as22hOQueFicouNaFilaSaiNumUltimoEnvio() {
        // Os pontos entre o último envio e as 22h esperavam a primeira abertura do dia seguinte.
        agora = em(21, 59);
        chave.guardar("chave-ficticia", CONTA);
        ServicoDaEquipe s = ligar();
        long t0 = System.currentTimeMillis() - 60_000;
        fonte.receptor.posicao(posicao(t0, LAT, LON, 0.2f));
        fonte.receptor.posicao(posicao(t0 + 30_000, LAT, LON, 0.2f));
        assertTrue(pedidosAoNexus.isEmpty());
        assertEquals(2, fila.tamanho());

        agora = em(22, 0) + 1_000;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
        assertEquals("um envio depois das 22h", Arrays.asList(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_POSICOES),
            pedidosAoNexus);
        assertEquals("o que ficou subiu", 0, fila.tamanho());
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertTrue(shadowOf(s).getNotificationShouldRemoved());
        assertFalse(ServicoDaEquipe.rodando());
        assertFalse("e o celular volta a dormir", ShadowPowerManager.getLatestWakeLock().isHeld());

        // A posição que chega depois das 22h não entra (só o que era do dia sobe).
        assertTrue(fonte.parada);
    }

    @Test
    public void aPosicaoDepoisDas22hNaoEntraEOUltimoEnvioLevaSoAsDeAntes() throws Exception {
        agora = em(21, 59);
        chave.guardar("chave-ficticia", CONTA);
        ligar();
        long t0 = System.currentTimeMillis() - 60_000;
        fonte.receptor.posicao(posicao(t0, LAT, LON, 0.2f));
        agora = em(22, 0) + 1_000;
        fonte.receptor.posicao(posicao(t0 + 60_000, LAT, LON, 0.2f));
        assertEquals(1, pedidosAoNexus.size());
        assertEquals("só o ponto de antes das 22h", 1,
            new JSONObject(ultimoCorpo).getJSONArray("pontos").length());
        assertEquals(0, fila.tamanho());
        assertFalse(ServicoDaEquipe.rodando());
    }

    @Test
    public void oPararQueEsqueceOPrazoNaoFazOUltimoEnvio() {
        // O Sair e a troca de conta param pelo parar(), que esquece o prazo: a fila é de quem saiu, e não sobe.
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        ligar();
        fonte.receptor.posicao(posicao(System.currentTimeMillis() - 60_000, LAT, LON, 0.2f));
        ServicoDaEquipe.parar(app);
        // A posição que chega antes do encerrar postado: fora do prazo, e nada sai.
        fonte.receptor.posicao(posicao(System.currentTimeMillis(), LAT, LON, 0.2f));
        rodarOLacoPrincipal();
        assertTrue(pedidosAoNexus.isEmpty());
        assertFalse(ServicoDaEquipe.rodando());
    }

    // ---- A leitura sem número válido -----------------------------------------------------------------------------------

    @Test
    public void aLeituraSemNumeroValidoEDescartadaEAColetaSegue() {
        // O NaN ou o infinito de uma leitura (o provedor de posição com defeito): o ponto não vai à fila, que não escreve
        // esses números, e a coleta segue com a leitura seguinte.
        agora = em(10, 0);
        ligar();
        long t0 = System.currentTimeMillis() - 60_000;
        fonte.receptor.posicao(posicao(t0, Double.NaN, LON, 0.2f));
        fonte.receptor.posicao(posicao(t0 + 1_000, LAT, Double.POSITIVE_INFINITY, 0.2f));
        fonte.receptor.posicao(posicao(t0 + 2_000, LAT, LON, 0.2f, Float.NaN));
        fonte.receptor.posicao(posicao(t0 + 3_000, LAT, LON, Float.NaN));
        assertEquals(0, fila.tamanho());
        assertTrue(ServicoDaEquipe.rodando());
        assertTrue(logou("descartada"));
        fonte.receptor.posicao(posicao(t0 + 4_000, LAT, LON, 0.2f));
        assertEquals(1, fila.tamanho());
        assertFalse("nada de coordenada no log", logou("-56.1"));
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

    // ---- O envio com a tela apagada ------------------------------------------------------------------------------------

    @Test
    public void comATelaApagadaAPosicaoQueChegaComOEnvioVencidoMandaNaHoraComOCelularAcordado() {
        // O celular no bolso ou no suporte do carro: a CPU dorme entre uma posição e outra, e o Handler, que conta o tempo
        // de CPU acordada, não anda. Aqui o laço principal não roda: só o relógio desde o boot avança, e quem acorda o
        // serviço é a posição.
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        ligar();
        long t0 = System.currentTimeMillis() - 300_000;

        desdeOBoot += 60_000;
        fonte.receptor.posicao(posicao(t0, LAT, LON, 0.2f));
        assertTrue("1 min depois de ligar, a posição só vai à fila", pedidosAoNexus.isEmpty());

        desdeOBoot += 70_000;
        fonte.receptor.posicao(posicao(t0 + 70_000, LAT, LON, 0.2f));
        assertEquals("130 s depois de ligar, sem o Handler ter rodado, o pedido sai",
            Arrays.asList(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_POSICOES), pedidosAoNexus);
        PowerManager.WakeLock acordado = ShadowPowerManager.getLatestWakeLock();
        assertNotNull(acordado);
        assertTrue("o celular fica acordado até a resposta", acordado.isHeld());
        rodarOLacoPrincipal();
        assertFalse("e volta a dormir com ela", acordado.isHeld());
        assertEquals("os dois pontos subiram, o de agora junto", 0, fila.tamanho());

        // A posição seguinte chega 2 min depois DA ANTERIOR, um tanto antes dos 2 min contados do envio, que saiu logo
        // depois dela: manda também (sem a folga, esperaria a outra, e o envio iria a cada 4 min).
        desdeOBoot += ServicoDaEquipe.ENVIO_MS - 1_000;
        fonte.receptor.posicao(posicao(t0 + 189_000, LAT, LON, 0.2f));
        assertEquals(2, pedidosAoNexus.size());
        rodarOLacoPrincipal();
        assertFalse(acordado.isHeld());
        assertTrue(ServicoDaEquipe.rodando());
    }

    @Test
    public void naPartidaAFrioAColetaGuardaEOEnvioEsperaAPaginaConferirAConta() {
        // A chave e a conta no disco, de antes, e o processo novo (o Android matou o app): a coleta liga e guarda como
        // sempre, e nada vai ao Nexus até a página desta partida mandar a conta (o pedido do aparelho, na EquipeNaCasca).
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        chave = new ChaveDaEquipe(app, new CifraFalsa());
        montarAsPecas();
        ligar();
        long t0 = System.currentTimeMillis() - 300_000;
        desdeOBoot += ServicoDaEquipe.ENVIO_MS;
        fonte.receptor.posicao(posicao(t0, LAT, LON, 0.2f));
        rodarOLacoPrincipal();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS));
        assertTrue("nem pela posição nem pelo Handler", pedidosAoNexus.isEmpty());
        assertEquals("a posição fica na fila", 1, fila.tamanho());
        assertTrue("e a coleta segue", ServicoDaEquipe.rodando());
        assertFalse("o celular não fica acordado à toa", ShadowPowerManager.getLatestWakeLock().isHeld());

        // A página confere a conta: o envio seguinte sobe a fila.
        assertTrue(chave.conferir(CONTA));
        desdeOBoot += ServicoDaEquipe.ENVIO_MS;
        fonte.receptor.posicao(posicao(t0 + 120_000, LAT, LON, 0.2f));
        assertEquals(Arrays.asList(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_POSICOES), pedidosAoNexus);
        rodarOLacoPrincipal();
        assertEquals(0, fila.tamanho());
    }

    @Test
    public void oEnvioPelaPosicaoRecomecaAContaDoHandler() {
        // Com o app aberto o Handler também manda: o envio que a posição fez o leva a contar de novo, para não sair outro
        // logo depois.
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        ligar();
        long t0 = System.currentTimeMillis() - 300_000;
        // 30 s de tela acesa, e ela apaga.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        desdeOBoot += ServicoDaEquipe.ENVIO_MS + 10_000;
        fonte.receptor.posicao(posicao(t0, LAT, LON, 0.2f));
        assertEquals(1, pedidosAoNexus.size());
        rodarOLacoPrincipal();

        // A tela acende: o Handler, que mandaria aos 2 min de ligar, agora manda aos 2 min do envio pela posição.
        fonte.receptor.posicao(posicao(t0 + 30_000, LAT, LON, 0.2f));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS - 1_000));
        assertEquals("nada aos 2 min de ligar", 1, pedidosAoNexus.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals("aos 2 min do último envio", 2, pedidosAoNexus.size());
    }

    @Test
    public void oCelularAcordadoSoltaSozinhoSeARespostaNaoVolta() {
        // A rede pendurada: o envio não volta (a thread não roda aqui), e o prazo solta o celular acordado sozinho.
        List<Runnable> naThread = new ArrayList<>();
        ServicoDaEquipe.pecasDeTeste = new ServicoDaEquipe.Pecas(() -> agora, () -> desdeOBoot, chave, fila, envio,
            fonte, naThread::add);
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        ligar();
        desdeOBoot += ServicoDaEquipe.ENVIO_MS;
        fonte.receptor.posicao(posicao(System.currentTimeMillis(), LAT, LON, 0.2f));
        PowerManager.WakeLock acordado = ShadowPowerManager.getLatestWakeLock();
        assertTrue("acordado para o envio", acordado.isHeld());
        assertEquals("o ponto e o envio esperando a thread", 2, naThread.size());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ACORDADO_NO_ENVIO_MS - 1_000));
        assertTrue("ainda acordado: a resposta pode estar a caminho", acordado.isHeld());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertFalse("o prazo soltou sozinho", acordado.isHeld());
    }

    // ---- A notificação fixa à vista ------------------------------------------------------------------------------------

    @Test
    public void semANotificacaoAVistaOIniciarNaoPedeOServico() {
        agora = em(10, 0);

        // Android 13+ com a notificação negada: o serviço rodaria e só o gerenciador de tarefas do Android o mostraria.
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        ServicoDaEquipe.iniciar(app);
        assertNull("sem a permissão da notificação", shadowOf(app).getNextStartedService());
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        // As notificações do Nexus Field desligadas nos ajustes.
        shadowOf(notificacoes()).setNotificationsEnabled(false);
        ServicoDaEquipe.iniciar(app);
        assertNull("com as notificações do Nexus Field desligadas", shadowOf(app).getNextStartedService());
        shadowOf(notificacoes()).setNotificationsEnabled(true);

        // O canal da equipe desligado nos ajustes (ele existe desde a primeira coleta, num dia anterior).
        CanaisDeNotificacao.criarDaEquipe(app);
        canalDaEquipe(NotificationManager.IMPORTANCE_NONE);
        ServicoDaEquipe.iniciar(app);
        assertNull("com o canal da equipe desligado", shadowOf(app).getNextStartedService());
        assertFalse(ServicoDaEquipe.notificacaoAVista(app));
        assertTrue(logou("a notificação fixa não ficaria à vista"));

        // O técnico liga o canal de novo: a coleta liga.
        canalDaEquipe(NotificationManager.IMPORTANCE_LOW);
        assertTrue(ServicoDaEquipe.notificacaoAVista(app));
        ligar();
        assertTrue(ServicoDaEquipe.rodando());
    }

    @Test
    public void aNotificacaoDesligadaComAColetaLigadaParaOServico() {
        agora = em(10, 0);
        chave.guardar("chave-ficticia", CONTA);
        ServicoDaEquipe s = ligar();

        // O técnico desliga o canal da equipe com a coleta ligada: a posição seguinte já não entra, e o serviço para.
        canalDaEquipe(NotificationManager.IMPORTANCE_NONE);
        fonte.receptor.posicao(posicao(System.currentTimeMillis(), LAT, LON, 0.2f));
        assertEquals("a posição sem a notificação à vista não vai à fila", 0, fila.tamanho());
        assertTrue(shadowOf(s).isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        assertTrue("o GPS foi solto", fonte.parada);
        assertTrue(logou("a notificação fixa não está à vista"));
        morrerOProcesso();

        // E as notificações do Nexus Field desligadas com a coleta ligada: para no envio seguinte, sem mandar.
        canalDaEquipe(NotificationManager.IMPORTANCE_LOW);
        ServicoDaEquipe outro = ligar();
        fonte.receptor.posicao(posicao(System.currentTimeMillis(), LAT, LON, 0.2f));
        shadowOf(notificacoes()).setNotificationsEnabled(false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ServicoDaEquipe.ENVIO_MS));
        assertTrue("nada foi ao Nexus", pedidosAoNexus.isEmpty());
        assertTrue(shadowOf(outro).isStoppedBySelf());
        assertTrue(shadowOf(outro).getNotificationShouldRemoved());
        assertFalse(ServicoDaEquipe.rodando());
    }

    @Test
    public void oInicioQueChegaComANotificacaoJaDesligadaEntraEmPrimeiroPlanoESaiLogo() {
        // A notificação desligada entre o iniciar e o onStartCommand: o serviço pedido em primeiro plano que para antes do
        // startForeground derruba o app, então ele entra e sai.
        agora = em(10, 0);
        ServicoDaEquipe.iniciar(app);
        Intent pedido = shadowOf(app).getNextStartedService();
        assertNotNull(pedido);
        shadowOf(notificacoes()).setNotificationsEnabled(false);

        ServicoDaEquipe s = entregar(pedido);
        ShadowService sombra = shadowOf(s);
        assertEquals("entrou em primeiro plano", ServicoDaEquipe.ID_DA_NOTIFICACAO, sombra.getLastForegroundNotificationId());
        assertTrue("e saiu", sombra.isForegroundStopped());
        assertTrue(sombra.isStoppedBySelf());
        assertFalse(ServicoDaEquipe.rodando());
        assertEquals("nada pedido ao GPS", 0, fonte.pedidos);
    }

    @Test
    public void aNotificacaoArrastadaParaOLadoVoltaNaHoraEAColetaSegue() throws Exception {
        agora = em(10, 0);
        ligar();
        ServiceController<ServicoDaEquipe> controle = servicos.get(0);
        Notification n = shadowOf(notificacoes()).getNotification(ServicoDaEquipe.ID_DA_NOTIFICACAO);
        assertNotNull("a notificação fixa na barra", n);
        assertNotNull("o arrastar avisa o serviço", n.deleteIntent);
        ShadowPendingIntent aoArrastar = shadowOf(n.deleteIntent);
        assertTrue("um início do serviço, e não do primeiro plano", aoArrastar.isService());
        assertEquals(ServicoDaEquipe.class.getName(), aoArrastar.getSavedIntent().getComponent().getClassName());
        assertEquals(ServicoDaEquipe.REPOR, aoArrastar.getSavedIntent().getAction());

        // Android 14+: o técnico arrasta a notificação para o lado, e o Android a tira da barra e manda o deleteIntent.
        notificacoes().cancel(ServicoDaEquipe.ID_DA_NOTIFICACAO);
        n.deleteIntent.send();
        Intent repor = shadowOf(app).getNextStartedService();
        assertNotNull(repor);
        controle.withIntent(repor).startCommand(0, 2);

        assertNotNull("a notificação voltou à barra",
            shadowOf(notificacoes()).getNotification(ServicoDaEquipe.ID_DA_NOTIFICACAO));
        assertTrue(ServicoDaEquipe.rodando());
        assertEquals("a coleta seguiu, sem pedir o GPS de novo", 1, fonte.pedidos);
        assertTrue(logou("arrastada para o lado e voltou"));
    }

    // ---- O mov de cada ponto -------------------------------------------------------------------------------------------

    @Test
    public void oMovEDaPropriaLeituraENaoDoRitmo() throws Exception {
        // O mapa mostra o ponto exato e o Abrir rota só de quem está em deslocamento: com o técnico parado em casa, o mov
        // tem de ir false desde a primeira leitura.
        agora = em(7, 0);
        ligar();
        long t0 = System.currentTimeMillis() - 60_000;
        double norte200m = 0.0018; // uns 200 m de latitude
        double norte600m = 0.0054;

        // A primeira leitura do dia, sem velocidade: parada (o ritmo começa no curto, mas não decide o mov).
        fonte.receptor.posicao(posicao(t0, LAT, LON, null));
        // A velocidade decide: abaixo de 1 m/s, parada; 1 m/s, em deslocamento.
        fonte.receptor.posicao(posicao(t0 + 1_000, LAT, LON, 0.99f));
        fonte.receptor.posicao(posicao(t0 + 2_000, LAT, LON, 1.0f));
        // Sem velocidade, a distância: 200 m da anterior, com 8 m de precisão nas duas, é deslocamento.
        fonte.receptor.posicao(posicao(t0 + 3_000, LAT + norte200m, LON, null));
        // O salto da posição de rede, com o celular parado: 600 m para uma leitura da antena (1,5 km de precisão) e de volta
        // ao Wi-Fi (30 m). Cabe na precisão das duas: não é deslocamento.
        fonte.receptor.posicao(posicao(t0 + 4_000, LAT + norte200m + norte600m, LON, null, 1_500f));
        fonte.receptor.posicao(posicao(t0 + 5_000, LAT + norte200m, LON, null, 30f));
        // Menos de 150 m, além da precisão: parada.
        fonte.receptor.posicao(posicao(t0 + 6_000, LAT + norte200m + 0.0012, LON, null));

        assertEquals(Arrays.asList(false, false, true, true, false, false, false), movsDaFila());
    }

    @Test
    public void oSaltoDaAntenaComOCelularParadoNaoReligaOGps() {
        // O ritmo usa a mesma régua do mov: a distância que cabe na precisão das duas leituras não é deslocamento. Caso que
        // criou a regra: parado o dia todo numa usina longe do Wi-Fi, a precisão equilibrada vem da antena (erro de
        // centenas de metros), a leitura dela passava dos 150 m da última do GPS, o ritmo voltava à alta precisão, e a volta
        // do GPS à posição certa passava de novo: um ciclo de uns 18 min com o GPS ligado perto de metade do tempo.
        agora = em(10, 0);
        ligar();
        long t0 = System.currentTimeMillis() - 60_000;
        for (int i = 0; i < 4; i++) fonte.receptor.posicao(posicao(t0 + i * 1_000L, LAT, LON, 0.2f));
        assertEquals(RitmoDaColeta.PARADO_MS, fonte.intervalo);
        assertFalse(fonte.emDeslocamento);
        int pedidos = fonte.pedidos;

        // A leitura da antena a uns 800 m, com 1,5 km de precisão, e a volta ao Wi-Fi (30 m) na posição certa.
        fonte.receptor.posicao(posicao(t0 + 4_000, LAT + 0.0072, LON, null, 1_500f));
        fonte.receptor.posicao(posicao(t0 + 5_000, LAT, LON, null, 30f));
        assertEquals("o pedido segue na precisão equilibrada de 10 min", RitmoDaColeta.PARADO_MS, fonte.intervalo);
        assertFalse(fonte.emDeslocamento);
        assertEquals("sem pedir o GPS de novo", pedidos, fonte.pedidos);

        // O deslocamento de verdade (200 m com o GPS, além da precisão) volta ao ritmo curto.
        fonte.receptor.posicao(posicao(t0 + 6_000, LAT + 0.0018, LON, null));
        assertEquals(RitmoDaColeta.EM_DESLOCAMENTO_MS, fonte.intervalo);
        assertTrue(fonte.emDeslocamento);
    }

    // ---- O aviso das 6h (R12) ------------------------------------------------------------------------------------------

    private Notification avisoDas6h() {
        return shadowOf(notificacoes()).getNotification(AvisoDoInicio.ID_DA_NOTIFICACAO);
    }

    private void tocarOAlarmeDas6h() {
        new ReceptorDoInicio().onReceive(app,
            new Intent(AvisoDoInicio.ACAO_DO_ALARME).setClass(app, ReceptorDoInicio.class));
    }

    @Test
    public void aColetaQueLigaTiraOAvisoDas6hDaBarra() {
        // O técnico abriu o app pelo ícone, sem tocar no aviso: ele sai quando a coleta liga, e não fica até as 22h
        // pedindo um toque que já não muda nada.
        chave.guardar("chave-ficticia", CONTA);
        chave.aceitarTermo();
        agora = em(6, 5);
        assertTrue(AvisoDoInicio.mostrar(app, agora));
        assertNotNull(avisoDas6h());
        ServicoDaEquipe s = ligar();
        assertTrue(ServicoDaEquipe.rodando());
        assertNull("a coleta ligou: o aviso saiu", avisoDas6h());
        assertNotNull("a notificação fixa ficou", shadowOf(s).getLastForegroundNotification());
    }

    @Test
    public void comAColetaLigadaOAlarmeDas6hNaoMostraOAviso() {
        // O Doze segurou o alarme até as 6h20, e o técnico abriu o app às 6h10: a coleta já está ligada.
        chave.guardar("chave-ficticia", CONTA);
        chave.aceitarTermo();
        agora = em(6, 10);
        ligar();
        agora = em(6, 20);
        tocarOAlarmeDas6h();
        assertNull("a coleta já ligada: nada aparece", avisoDas6h());
        // Parada (o Android que matou o app), o alarme que vier mostra.
        morrerOProcesso();
        assertFalse(ServicoDaEquipe.rodando());
        agora = em(6, 30);
        tocarOAlarmeDas6h();
        assertNotNull(avisoDas6h());
    }
}
