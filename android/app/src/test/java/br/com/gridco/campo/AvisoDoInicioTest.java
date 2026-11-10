package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowPendingIntent;

/**
 * O aviso das 6h (R12): o alarme das 6h na hora do celular, o receptor que decide na hora com a chave e o termo de agora,
 * a notificação que abre o Nexus Field, o boot e a atualização que reagendam e a chave que sai e leva o alarme junto. O
 * relógio é o de Brasília em novembro de 2026 (o dia 9 é uma segunda); a chave e a conta são fictícias. O serviço da coleta
 * não roda aqui: a coleta ligada é provada no ServicoDaEquipeTest.
 */
@RunWith(RobolectricTestRunner.class)
public class AvisoDoInicioTest {

    private static final TimeZone BRASILIA = TimeZone.getTimeZone("America/Sao_Paulo");
    private static final TimeZone CUIABA = TimeZone.getTimeZone("America/Cuiaba");
    // Fictícias, no formato do contrato (a chave: 32 bytes em base64url; a conta: o SHA-256 em hex que a página manda).
    private static final String CHAVE = "chaveFicticiaDaEquipe_AAAAAAAAAAAAAAAAAAAAA";
    private static final String CONTA = "aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000";
    private static final long MINUTO = 60_000L;
    // Os eventos do sistema que reagendam o alarme: o celular que religou, o app atualizado e o fuso novo.
    private static final String[] DO_SISTEMA = {
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED,
    };

    private Application app;
    private TimeZone fusoDeAntes;
    private long agora;
    private ChaveDaEquipe chave;
    private EnvioDePontos.Resposta resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":0,\"coleta\":true}");

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        fusoDeAntes = TimeZone.getDefault();
        TimeZone.setDefault(BRASILIA);
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        chave = new ChaveDaEquipe(app, new CifraFalsa());
        // As peças do processo, como o receptor as lê (o relógio e a chave); a fila nem abre, e o Nexus é falso.
        FilaDePontos fila = new FilaDePontos(app);
        EnvioDePontos envio = new EnvioDePontos(chave, fila, (url, k, corpo) -> resposta, () -> agora);
        ServicoDaEquipe.pecasDeTeste = new ServicoDaEquipe.Pecas(() -> agora, () -> 0L, chave, fila, envio,
            new FonteParada(), Runnable::run);
        agora = em(9, 9, 0);
        ShadowLog.clear();
    }

    @After
    public void fechar() {
        ServicoDaEquipe.pecasDeTeste = null;
        TimeZone.setDefault(fusoDeAntes);
    }

    /** As posições que nunca chegam: o receptor não liga a coleta. */
    private static final class FonteParada implements ServicoDaEquipe.FonteDePosicoes {
        @Override
        public void pedir(long intervaloMs, boolean emDeslocamento, Receptor receptor) {
        }

        @Override
        public void parar() {
        }
    }

    /** Novembro de 2026, na hora de Brasília. */
    private static long em(int dia, int hora, int minuto) {
        return em(BRASILIA, dia, hora, minuto);
    }

    private static long em(TimeZone fuso, int dia, int hora, int minuto) {
        Calendar c = Calendar.getInstance(fuso);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, dia, hora, minuto, 0);
        return c.getTimeInMillis();
    }

    /** O aparelho com a chave da equipe guardada e o termo da versão de agora aceito. */
    private void comChaveETermo() {
        chave.guardar(CHAVE, CONTA);
        chave.aceitarTermo();
    }

    private AlarmManager alarmes() {
        return (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
    }

    private NotificationManager notificacoes() {
        return (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** Os alarmes do aviso das 6h agendados agora (os que mandam a ação dele ao receptor). */
    private List<ShadowAlarmManager.ScheduledAlarm> alarmesDoInicio() {
        List<ShadowAlarmManager.ScheduledAlarm> deles = new ArrayList<>();
        for (ShadowAlarmManager.ScheduledAlarm a : shadowOf(alarmes()).getScheduledAlarms()) {
            if (a.operation == null) continue;
            Intent alvo = shadowOf(a.operation).getSavedIntent();
            if (alvo != null && AvisoDoInicio.ACAO_DO_ALARME.equals(alvo.getAction())) deles.add(a);
        }
        return deles;
    }

    /** O único alarme do aviso das 6h. */
    private ShadowAlarmManager.ScheduledAlarm oAlarme() {
        List<ShadowAlarmManager.ScheduledAlarm> deles = alarmesDoInicio();
        assertEquals("um alarme do aviso das 6h, e só um", 1, deles.size());
        return deles.get(0);
    }

    /** O aviso das 6h na barra, ou null. */
    private Notification oAviso() {
        return shadowOf(notificacoes()).getNotification(AvisoDoInicio.ID_DA_NOTIFICACAO);
    }

    /** O alarme toca: o Android entrega ao receptor o Intent do PendingIntent agendado. */
    private void tocarOAlarme() {
        Intent doAlarme = shadowOf(oAlarme().operation).getSavedIntent();
        new ReceptorDoInicio().onReceive(app, new Intent(doAlarme));
    }

    /** O Android entrega ao receptor um evento (o boot, a atualização, o fuso, ou o alarme que sobrou de antes). */
    private void receber(String acao) {
        new ReceptorDoInicio().onReceive(app, new Intent(acao).setClass(app, ReceptorDoInicio.class));
    }

    private static String texto(Notification n, String extra) {
        CharSequence s = n.extras.getCharSequence(extra);
        return s == null ? null : s.toString();
    }

    // --- o agendamento -----------------------------------------------------------------------------------------------

    @Test
    public void comAChaveEOTermoOAlarmeFicaParaAs6hDoDiaSeguinteSemAlarmeExato() {
        comChaveETermo();
        AvisoDoInicio.agendarSeVale(app, chave, agora);

        ShadowAlarmManager.ScheduledAlarm a = oAlarme();
        assertEquals("as 6h da terça, na hora do celular", em(10, 6, 0), a.getTriggerAtMs());
        assertEquals("acorda o celular", AlarmManager.RTC_WAKEUP, a.getType());
        assertTrue("vale com o celular em repouso (o Doze)", a.isAllowWhileIdle());
        assertNotEquals("sem alarme exato (nada de SCHEDULE_EXACT_ALARM)", ShadowAlarmManager.WINDOW_EXACT,
            a.getWindowLengthMs());
        ShadowPendingIntent pi = shadowOf(a.operation);
        assertTrue("o alarme vai ao receptor", pi.isBroadcast());
        assertTrue("PendingIntent imutável", (pi.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        Intent alvo = pi.getSavedIntent();
        assertEquals(ReceptorDoInicio.class.getName(), alvo.getComponent().getClassName());
        assertEquals(app.getPackageName(), alvo.getComponent().getPackageName());

        // De novo (a volta ao app seguinte): o mesmo alarme, que não empilha.
        AvisoDoInicio.agendarSeVale(app, chave, agora + 5 * MINUTO);
        assertEquals(em(10, 6, 0), oAlarme().getTriggerAtMs());
    }

    @Test
    public void as6hSaoAsProximasDepoisDeAgoraNaHoraDoCelular() {
        assertEquals("antes das 6h, as de hoje", em(9, 6, 0),
            AvisoDoInicio.proximoInicio(em(9, 5, 59) + 59_999L, BRASILIA));
        assertEquals("às 6h em ponto (o alarme que tocou na hora), as de amanhã", em(10, 6, 0),
            AvisoDoInicio.proximoInicio(em(9, 6, 0), BRASILIA));
        assertEquals("de manhã, as de amanhã", em(10, 6, 0), AvisoDoInicio.proximoInicio(em(9, 6, 1), BRASILIA));
        assertEquals("de noite, as de amanhã", em(10, 6, 0), AvisoDoInicio.proximoInicio(em(9, 23, 30), BRASILIA));
        Calendar primeiroDeDezembro = Calendar.getInstance(BRASILIA);
        primeiroDeDezembro.clear();
        primeiroDeDezembro.set(2026, Calendar.DECEMBER, 1, 6, 0, 0);
        assertEquals("no último dia do mês, as do dia 1º", primeiroDeDezembro.getTimeInMillis(),
            AvisoDoInicio.proximoInicio(em(30, 22, 0), BRASILIA));
        // O técnico em Mato Grosso: as 6h de lá, uma hora depois das de Brasília.
        assertEquals(em(CUIABA, 10, 6, 0), AvisoDoInicio.proximoInicio(em(CUIABA, 9, 8, 0), CUIABA));
        assertEquals(em(BRASILIA, 10, 7, 0), AvisoDoInicio.proximoInicio(em(CUIABA, 9, 8, 0), CUIABA));
    }

    @Test
    public void semAChaveOuSemOTermoNadaFicaAgendado() {
        // Fora do piloto (sem chave) nenhum aparelho acorda às 6h à toa: a 1.2.0 vai a todos.
        AvisoDoInicio.agendarSeVale(app, chave, agora);
        assertTrue("sem nada", alarmesDoInicio().isEmpty());
        chave.aceitarTermo();
        AvisoDoInicio.agendarSeVale(app, chave, agora);
        assertTrue("só com o termo (a chave saiu depois do 401)", alarmesDoInicio().isEmpty());
        chave.apagar();
        chave.guardar(CHAVE, CONTA);
        AvisoDoInicio.agendarSeVale(app, chave, agora);
        assertTrue("só com a chave (o termo ainda não aceito)", alarmesDoInicio().isEmpty());
        chave.aceitarTermo();
        AvisoDoInicio.agendarSeVale(app, chave, agora);
        assertEquals(1, alarmesDoInicio().size());
        // O termo da versão de antes deixa de valer (a versão nova do texto): o alarme sai na próxima conferência.
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().putString("termo_aceito", "0").commit();
        AvisoDoInicio.agendarSeVale(app, chave, agora);
        assertTrue("o termo de outra versão", alarmesDoInicio().isEmpty());
    }

    // --- a hora do alarme --------------------------------------------------------------------------------------------

    @Test
    public void naHoraOAvisoApareceNoCanalDoInicioDoDiaEOProximoFicaParaOutroDia() {
        comChaveETermo();
        AvisoDoInicio.agendarSeVale(app, chave, em(9, 21, 0));
        agora = em(10, 6, 3); // o Doze segurou o alarme uns minutos
        tocarOAlarme();

        Notification n = oAviso();
        assertNotNull("o aviso na barra", n);
        assertEquals(AvisoDoInicio.CANAL_INICIO, n.getChannelId());
        assertEquals("inicio_do_dia", n.getChannelId());
        assertEquals("Bom dia", texto(n, Notification.EXTRA_TITLE));
        assertEquals("Toque para abrir o Nexus Field e começar o dia.", texto(n, Notification.EXTRA_TEXT));
        assertEquals(R.drawable.ic_notificacao, n.getSmallIcon().getResId());
        assertTrue("sai da barra no toque", (n.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertEquals("às 22h ele sai sozinho: depois disso o toque não liga a coleta", em(10, 22, 0) - agora,
            n.getTimeoutAfter());
        assertEquals("nenhuma categoria: o Não perturbe do técnico vale", null, n.category);

        NotificationChannel canal = notificacoes().getNotificationChannel(AvisoDoInicio.CANAL_INICIO);
        assertNotNull("o canal próprio", canal);
        assertEquals("Início do dia", canal.getName().toString());
        assertEquals("com som e importância padrão, para ser visto", NotificationManager.IMPORTANCE_DEFAULT,
            canal.getImportance());
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, canal.getSound());
        assertFalse("não fura o Não perturbe", canal.canBypassDnd());

        assertEquals("o do dia seguinte", em(11, 6, 0), oAlarme().getTriggerAtMs());
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse("nada da chave nem da conta no log", item.msg != null
                && (item.msg.contains(CHAVE) || item.msg.contains(CONTA)));
        }
    }

    @Test
    public void oToqueAbreONexusFieldPelaPortaInternaDaMainActivity() {
        comChaveETermo();
        agora = em(10, 6, 0);
        assertTrue(AvisoDoInicio.mostrar(app, agora));
        ShadowPendingIntent pi = shadowOf(oAviso().contentIntent);
        assertTrue("o toque abre uma activity", pi.isActivity());
        assertTrue("PendingIntent imutável", (pi.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        Intent toque = pi.getSavedIntent();
        assertEquals(MainActivity.PORTA_INTERNA, toque.getComponent().getClassName());
        assertEquals(app.getPackageName(), toque.getComponent().getPackageName());
        assertTrue((toque.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("a tela aberta recebe o toque, sem outra por cima", (toque.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
        assertNull("sem OS: só abre o Nexus Field", MainActivity.osDoToque(new Intent(toque)));
        assertNull("sem teste", MainActivity.testeDoAtalho(new Intent(toque)));
        // A porta interna é a MainActivity: é a volta ao app dela (o onResume) que liga a coleta.
        ActivityInfo porta;
        try {
            porta = app.getPackageManager().getActivityInfo(new ComponentName(app, MainActivity.PORTA_INTERNA),
                PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (PackageManager.NameNotFoundException e) {
            throw new AssertionError("a porta interna não está no manifesto", e);
        }
        assertEquals(MainActivity.class.getName(), porta.targetActivity);
    }

    @Test
    public void semAChaveNadaApareceENenhumOutroAlarmeFica() {
        // O alarme que sobrou de antes (o Sair já o cancela; este é o caso de ele ter tocado mesmo assim): a decisão é da
        // hora do alarme, com a chave de agora.
        chave.aceitarTermo();
        agora = em(10, 6, 0);
        receber(AvisoDoInicio.ACAO_DO_ALARME);
        assertNull("nada aparece a quem saiu, a quem está fora do piloto ou a quem levou o coleta:false", oAviso());
        assertTrue("e o aparelho não acorda mais às 6h", alarmesDoInicio().isEmpty());
    }

    @Test
    public void semOTermoDaVersaoDeAgoraNadaAparece() {
        chave.guardar(CHAVE, CONTA);
        agora = em(10, 6, 0);
        receber(AvisoDoInicio.ACAO_DO_ALARME);
        assertNull(oAviso());
        assertTrue(alarmesDoInicio().isEmpty());
    }

    @Test
    public void comAColetaJaLigadaNadaApareceMasOProximoFicaAgendado() {
        // O técnico abriu o app entre as 6h e a hora do alarme (o Doze o segurou): a coleta já está ligada.
        comChaveETermo();
        agora = em(10, 6, 20);
        assertFalse(AvisoDoInicio.naHora(app, chave, true, agora));
        assertNull(oAviso());
        assertEquals(em(11, 6, 0), oAlarme().getTriggerAtMs());
        // E com ela parada, aparece.
        assertTrue(AvisoDoInicio.naHora(app, chave, false, agora));
        assertNotNull(oAviso());
    }

    @Test
    public void foraDaJanelaNadaApareceEOAlarmeVaiAs6hDoFusoNovo() {
        // O técnico agendado em Brasília que viaja para Mato Grosso: o alarme das 6h de Brasília toca às 5h de lá, antes
        // da janela (o toque não ligaria a coleta). Nada aparece, e o próximo fica para as 6h de lá, uma hora depois.
        comChaveETermo();
        AvisoDoInicio.agendarSeVale(app, chave, em(9, 21, 0));
        assertEquals(em(BRASILIA, 10, 6, 0), oAlarme().getTriggerAtMs());
        TimeZone.setDefault(CUIABA);
        agora = em(BRASILIA, 10, 6, 0);
        tocarOAlarme();
        assertNull("às 5h na hora do celular, nada", oAviso());
        assertEquals(em(CUIABA, 10, 6, 0), oAlarme().getTriggerAtMs());
        agora = em(CUIABA, 10, 6, 0);
        tocarOAlarme();
        assertNotNull("às 6h de lá, o aviso", oAviso());
        assertEquals(em(CUIABA, 11, 6, 0), oAlarme().getTriggerAtMs());
    }

    @Test
    public void comANotificacaoNegadaOuOCanalDesligadoNadaAparece() {
        comChaveETermo();
        agora = em(10, 6, 0);

        // Android 13+ com a notificação negada: nada aparece (e a coleta também não ligaria).
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertFalse(AvisoDoInicio.mostrar(app, agora));
        assertNull(oAviso());
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        // As notificações do Nexus Field desligadas nos ajustes.
        shadowOf(notificacoes()).setNotificationsEnabled(false);
        assertFalse(AvisoDoInicio.mostrar(app, agora));
        assertNull(oAviso());
        shadowOf(notificacoes()).setNotificationsEnabled(true);

        // O canal Início do dia desligado nos ajustes (ele nasce com o agendamento).
        AvisoDoInicio.agendarSeVale(app, chave, agora);
        notificacoes().getNotificationChannel(AvisoDoInicio.CANAL_INICIO).setImportance(NotificationManager.IMPORTANCE_NONE);
        receber(AvisoDoInicio.ACAO_DO_ALARME);
        assertNull("o canal desligado", oAviso());
        assertEquals("o próximo fica: o técnico pode religar o canal", em(11, 6, 0), oAlarme().getTriggerAtMs());

        // Religado: aparece.
        notificacoes().getNotificationChannel(AvisoDoInicio.CANAL_INICIO).setImportance(NotificationManager.IMPORTANCE_DEFAULT);
        assertTrue(AvisoDoInicio.mostrar(app, agora));
        assertNotNull(oAviso());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.N)
    public void noAndroid7OAvisoTocaOSomPadraoComoOResumo() {
        // Sem canais: a prioridade e o som da própria notificação fazem o papel do canal.
        comChaveETermo();
        agora = em(10, 6, 0);
        assertTrue(AvisoDoInicio.mostrar(app, agora));
        Notification n = oAviso();
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, n.priority);
        assertTrue((n.defaults & Notification.DEFAULT_SOUND) != 0);
    }

    // --- o boot, a atualização e o fuso ------------------------------------------------------------------------------

    @Test
    public void oBootAAtualizacaoEOFusoNovoReagendamSemMostrarNada() {
        comChaveETermo();
        for (String acao : DO_SISTEMA) {
            AvisoDoInicio.cancelar(app); // o reinício e a atualização do app apagam os alarmes
            assertTrue(alarmesDoInicio().isEmpty());
            agora = em(9, 7, 30);
            receber(acao);
            assertEquals(acao + " reagenda para as próximas 6h", em(10, 6, 0), oAlarme().getTriggerAtMs());
            assertNull(acao + " não mostra o aviso: só o alarme das 6h mostra", oAviso());
        }
        // O celular que religa às 5h: as 6h de hoje.
        AvisoDoInicio.cancelar(app);
        agora = em(9, 5, 0);
        receber(Intent.ACTION_BOOT_COMPLETED);
        assertEquals(em(9, 6, 0), oAlarme().getTriggerAtMs());
    }

    @Test
    public void oFusoNovoTrocaOAlarmeMarcadoPelasDoFusoNovo() {
        comChaveETermo();
        AvisoDoInicio.agendarSeVale(app, chave, em(9, 9, 0));
        assertEquals(em(BRASILIA, 10, 6, 0), oAlarme().getTriggerAtMs());
        TimeZone.setDefault(CUIABA);
        agora = em(BRASILIA, 9, 15, 0);
        receber(Intent.ACTION_TIMEZONE_CHANGED);
        assertEquals("as 6h de Mato Grosso, no lugar das de Brasília", em(CUIABA, 10, 6, 0), oAlarme().getTriggerAtMs());
    }

    @Test
    public void semAChaveOBootNaoAgendaNada() {
        chave.aceitarTermo();
        for (String acao : DO_SISTEMA) {
            receber(acao);
            assertTrue(acao, alarmesDoInicio().isEmpty());
        }
        assertNull(oAviso());
    }

    @Test
    public void outroEventoNaoFazNadaEOReceptorNuncaLanca() {
        comChaveETermo();
        agora = em(10, 6, 0);
        receber("br.com.outro.app.QUALQUER");
        new ReceptorDoInicio().onReceive(app, new Intent());
        assertTrue(alarmesDoInicio().isEmpty());
        assertNull(oAviso());
        // O que falhar dentro dele (aqui, o relógio) vai ao log só com o nome da classe: o receptor que lança no boot
        // derrubaria o app com o aviso de "parou" do Android.
        ServicoDaEquipe.pecasDeTeste = new ServicoDaEquipe.Pecas(() -> {
            throw new IllegalStateException("o relógio falhou: detalhe que não vai ao log");
        }, () -> 0L, chave, new FilaDePontos(app), null, new FonteParada(), Runnable::run);
        receber(Intent.ACTION_BOOT_COMPLETED);
        receber(AvisoDoInicio.ACAO_DO_ALARME);
        boolean logou = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse(item.msg != null && item.msg.contains("detalhe que não vai ao log"));
            if (item.msg != null && item.msg.contains("IllegalStateException")) logou = true;
        }
        assertTrue("a falha no log, pelo nome da classe", logou);
    }

    // --- a chave que sai leva o alarme e o aviso ---------------------------------------------------------------------

    @Test
    public void aChaveQueSaiLevaOAlarmeEOAvisoDaBarra() {
        // O Sair, a troca de conta e o coleta:false do registro passam pelo apagar; o 401 do envio, pelo apagarAChave.
        comChaveETermo();
        agora = em(10, 6, 0);
        AvisoDoInicio.agendarSeVale(app, chave, agora);
        assertTrue(AvisoDoInicio.mostrar(app, agora));
        chave.apagar();
        assertTrue("o alarme saiu", alarmesDoInicio().isEmpty());
        assertNull("e o aviso da barra", oAviso());

        comChaveETermo();
        AvisoDoInicio.agendarSeVale(app, chave, agora);
        assertTrue(AvisoDoInicio.mostrar(app, agora));
        chave.apagarAChave();
        assertTrue("o 401: o alarme saiu", alarmesDoInicio().isEmpty());
        assertNull(oAviso());
    }

    @Test
    public void o401EOColetaFalseDoEnvioCancelamOAviso() {
        // Também o do último envio das 22h, cuja resposta ninguém lê: é o EnvioDePontos que apaga a chave.
        EnvioDePontos envio = ServicoDaEquipe.pecasDeTeste.envio;
        for (EnvioDePontos.Resposta r : Arrays.asList(new EnvioDePontos.Resposta(401, ""),
                new EnvioDePontos.Resposta(200, "{\"aceitos\":0,\"coleta\":false}"))) {
            comChaveETermo(); // guardada, a chave fica conferida, e o aviso sem permissão sai ao Nexus
            AvisoDoInicio.agendarSeVale(app, chave, agora);
            assertEquals(1, alarmesDoInicio().size());
            resposta = r;
            assertEquals(EnvioDePontos.Resultado.PARAR, envio.avisarSemPermissao());
            assertTrue("o " + r.status + ": o alarme saiu", alarmesDoInicio().isEmpty());
        }
    }

    @Test
    public void aColetaQueLigaTiraOAvisoDaBarra() {
        comChaveETermo();
        agora = em(10, 6, 0);
        assertTrue(AvisoDoInicio.mostrar(app, agora));
        AvisoDoInicio.tirarDaBarra(app);
        assertNull(oAviso());
    }

    // --- o manifesto -------------------------------------------------------------------------------------------------

    @Test
    public void oManifestoDeclaraOReceptorNaoExportadoComOBootEAAtualizacaoESemAlarmeExato() throws Exception {
        PackageManager pm = app.getPackageManager();
        ActivityInfo receptor = pm.getReceiverInfo(new ComponentName(app, ReceptorDoInicio.class), 0);
        assertFalse("não exportado: o alarme é do próprio app, e o sistema alcança o receptor assim mesmo",
            receptor.exported);
        for (String acao : DO_SISTEMA) {
            boolean achou = false;
            for (ResolveInfo r : pm.queryBroadcastReceivers(new Intent(acao).setPackage(app.getPackageName()), 0)) {
                if (ReceptorDoInicio.class.getName().equals(r.activityInfo.name)) achou = true;
            }
            assertTrue("o receptor ouve " + acao, achou);
        }
        boolean outroOuveOAlarme = false;
        for (ResolveInfo r : pm.queryBroadcastReceivers(
                new Intent(AvisoDoInicio.ACAO_DO_ALARME).setPackage(app.getPackageName()), 0)) {
            outroOuveOAlarme = true;
        }
        assertFalse("a ação do alarme não está em filtro nenhum: só o PendingIntent explícito a manda", outroOuveOAlarme);
        List<String> pedidas = Arrays.asList(
            pm.getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions);
        assertTrue("o boot reagenda", pedidas.contains(Manifest.permission.RECEIVE_BOOT_COMPLETED));
        assertFalse("sem alarme exato", pedidas.contains(Manifest.permission.SCHEDULE_EXACT_ALARM));
        assertFalse("sem alarme exato", pedidas.contains(Manifest.permission.USE_EXACT_ALARM));
    }
}
