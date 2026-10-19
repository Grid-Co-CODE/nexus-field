package br.com.gridco.campo;

import static br.com.gridco.campo.ExpedienteTest.em;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import br.com.gridco.campo.Notificacoes.Montada;

import com.google.firebase.messaging.RemoteMessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowPendingIntent;

import java.util.HashMap;
import java.util.Map;

/**
 * As notificações que o servidor do App de Campo manda pelo Firebase (spec 2026-10-18): a montagem pura (o que mostrar,
 * em que canal, com que id, com a hora do envio e a da chegada) e, pelo Robolectric, os três canais, a notificação postada
 * com o toque para a porta interna da MainActivity e o serviço do Firebase. O servidor aqui é falso: a mensagem é o Map de
 * dados que ele mandaria. Os textos de exemplo só têm contagens e o número da OS, como o contrato manda (nada de usina nem
 * de pessoa).
 */
@RunWith(RobolectricTestRunner.class)
public class NotificacoesTest {

    // Os instantes do teste, na hora de Brasília: terça 10:00 (no expediente) é a hora padrão da montagem.
    private static final long TERCA_10H = em(2026, 10, 20, 10, 0);
    private static final long TERCA_10H02 = em(2026, 10, 20, 10, 2);
    private static final long SEXTA_16H50 = em(2026, 10, 16, 16, 50);
    private static final long SEXTA_19H40 = em(2026, 10, 16, 19, 40);

    private Application app;
    private NotificationManager nm;
    private ShadowNotificationManager sombra;

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        sombra = shadowOf(nm);
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }

    /** A montagem de uma mensagem enviada e chegada numa terça às 10:00 (o expediente). */
    private static Montada montar(Map<String, String> dados) {
        return Notificacoes.montar(dados, TERCA_10H, TERCA_10H);
    }

    private static Map<String, String> dados(String tipo, String canal, String titulo, String texto, String os) {
        Map<String, String> d = new HashMap<>();
        if (tipo != null) d.put("tipo", tipo);
        if (canal != null) d.put("canal", canal);
        if (titulo != null) d.put("titulo", titulo);
        if (texto != null) d.put("texto", texto);
        if (os != null) d.put("os", os);
        return d;
    }

    private static Map<String, String> dadosEmerg(String os) {
        return dados("emergencia", "emergencia", "Emergência", "Corretiva emergencial: OS " + os, os);
    }

    private static Map<String, String> dadosResumo(String titulo, String texto) {
        return dados("resumo", "resumo", titulo, texto, null);
    }

    private static String repetir(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A montagem
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    public void resumoVaiAoCanalResumoComIdFixo() {
        Montada m = montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas."));
        assertEquals("resumo", m.canal);
        assertEquals(Notificacoes.ID_RESUMO, m.id);
        assertNull(m.os);
        assertEquals("Bom dia!", m.titulo);
        assertEquals("Hoje: 4 tarefas.", m.texto);
        // O da tarde substitui o da manhã: o mesmo id, e o número que viesse num resumo não vira OS.
        Montada tarde = montar(dados("resumo", "resumo", "Boa tarde!", "Faltam 2 tarefas de hoje.", "12345"));
        assertEquals(m.id, tarde.id);
        assertNull("só a emergência leva a OS ao toque", tarde.os);
    }

    @Test
    public void emergenciaTemIdEstavelPorOs() {
        assertEquals(montar(dadosEmerg("12345")).id, montar(dadosEmerg("12345")).id);
        assertNotEquals(montar(dadosEmerg("12345")).id, montar(dadosEmerg("12346")).id);
        Montada m = montar(dadosEmerg("12345"));
        assertEquals("12345", m.os);
        assertEquals(Notificacoes.ID_EMERGENCIA_BASE + ("12345".hashCode() & 0x7fffffff) % 1_000_000, m.id);
        assertEquals("o id que o osAberta cancela é o da montagem", m.id, Notificacoes.idDaEmergencia("12345"));
        assertNotEquals("a emergência não substitui o resumo", Notificacoes.ID_RESUMO, m.id);
        // A OS de teste do servidor (nove dígitos) também tem id na faixa das emergências com OS.
        int teste = Notificacoes.idDaEmergencia("999999999");
        assertTrue(teste >= Notificacoes.ID_EMERGENCIA_BASE && teste < Notificacoes.ID_EMERGENCIA_BASE + 1_000_000);
    }

    @Test
    public void aOsDoTesteDoServidorTemNoveDigitosEVaiInteiraAoToque() {
        // O /push/teste da v287 manda a emergência com a OS 999999999 (fora da faixa das OS reais): ela é uma OS válida, tem
        // id na faixa das emergências com OS e chega inteira à página pelo toque.
        String doTeste = "999999999";
        assertTrue(Notificacoes.osValida(doTeste));
        Montada m = montar(dados("emergencia", "emergencia", "Emergência", "Corretiva emergencial: OS " + doTeste, doTeste));
        assertEquals(doTeste, m.os);
        assertEquals(Notificacoes.idDaEmergencia(doTeste), m.id);
        assertTrue(m.id >= Notificacoes.ID_EMERGENCIA_BASE && m.id < Notificacoes.ID_EMERGENCIA_BASE + 1_000_000);
        Notification n = mostrada(m);
        assertEquals(doTeste, MainActivity.osDoToque(new Intent(intentDoToque(n))));
        assertEquals(0, n.flags & Notification.FLAG_AUTO_CANCEL);
    }

    @Test
    public void canalSilenciosoRespeitado() {
        assertEquals("emergencia_silenciosa", montar(dados("emergencia", "emergencia_silenciosa", "t", "x", "1")).canal);
        assertEquals("emergencia", montar(dadosEmerg("1")).canal);
    }

    @Test
    public void tipoDesconhecidoNaoMostra() {
        // O tipo decide o que a mensagem é: fora do contrato, não se sabe nem em que canal mostrar.
        assertNull(montar(dados("promo", "resumo", "t", "x", null)));
        assertNull(montar(dados("promo", "emergencia", "t", "x", "12345")));
        assertNull("sem tipo", montar(dados(null, "resumo", "t", "x", null)));
        assertNull("caixa diferente não é o contrato", montar(dados("Resumo", "resumo", "t", "x", null)));
        assertNull(montar(dados("Emergencia", "emergencia", "t", "x", "12345")));
        assertNull(montar(new HashMap<>()));
        assertNull(montar(null));
    }

    @Test
    public void aEmergenciaComCanalQueNaoEDelaVaiAoSilenciosoENaoSePerde() {
        // Um canal errado do servidor não descarta a emergência. Ela vai ao canal silencioso: aparece na barra, com a OS
        // no toque, sem tocar alto por um canal que ninguém escolheu (o do resumo, ou um que não existe).
        for (String canal : new String[] {"resumo", "outro", "", null, "Emergencia"}) {
            Montada m = montar(dados("emergencia", canal, "Emergência", "Corretiva emergencial: OS 12345", "12345"));
            assertNotNull("canal " + canal, m);
            assertEquals("canal " + canal, "emergencia_silenciosa", m.canal);
            assertEquals("a OS segue no toque", "12345", m.os);
            assertEquals("o id é o da mesma emergência no canal certo", montar(dadosEmerg("12345")).id, m.id);
            assertNotNull("o motivo vai ao log", m.aviso);
        }
        // No canal dela, nada a avisar.
        assertNull(montar(dadosEmerg("12345")).aviso);
        assertNull(montar(dados("emergencia", "emergencia_silenciosa", "t", "x", "1")).aviso);
    }

    @Test
    public void oResumoComCanalQueNaoEDeleVaiAoCanalDoResumo() {
        // O resumo no canal da emergência tocaria alto às 7h (o barulho que a spec chama de fracasso): vai ao dele.
        for (String canal : new String[] {"emergencia", "emergencia_silenciosa", "outro", "", null}) {
            Montada m = montar(dados("resumo", canal, "Bom dia!", "Hoje: 4 tarefas.", null));
            assertNotNull("canal " + canal, m);
            assertEquals("canal " + canal, "resumo", m.canal);
            assertEquals(Notificacoes.ID_RESUMO, m.id);
            assertNull(m.os);
            assertNotNull("o motivo vai ao log", m.aviso);
        }
        assertNull(montar(dados("resumo", "resumo", "t", "x", null)).aviso);
    }

    @Test
    public void aEmergenciaQueChegaForaDoExpedienteVaiAoSilencioso() {
        // A emergência sai às 16:50 de uma sexta no canal alto (o servidor escolhe pela hora do ENVIO) para um técnico
        // sem sinal; o celular pega sinal às 19:40, em casa. O servidor não troca o canal depois de enviar, e o FCM segura
        // a mensagem por até 12 h: quem sabe a hora da chegada é a casca.
        Montada atrasada = Notificacoes.montar(dadosEmerg("12345"), SEXTA_16H50, SEXTA_19H40);
        assertEquals("emergencia_silenciosa", atrasada.canal);
        assertEquals("a OS segue no toque", "12345", atrasada.os);
        assertEquals("o mesmo id da emergência", montar(dadosEmerg("12345")).id, atrasada.id);
        assertNotNull("o motivo vai ao log", atrasada.aviso);
        assertTrue(atrasada.aviso, atrasada.aviso.contains("fora do expediente"));
        assertEquals("a barra diz a hora em que a OS foi mandada, não a da chegada", SEXTA_16H50, atrasada.quando);

        // Terça 10:00, entregue às 10:02: segue alta.
        Montada naHora = Notificacoes.montar(dadosEmerg("12345"), TERCA_10H, TERCA_10H02);
        assertEquals("emergencia", naHora.canal);
        assertNull(naHora.aviso);
        assertEquals(TERCA_10H, naHora.quando);

        // Fim de semana e madrugada também; a que já vem no silencioso fica nele, sem aviso.
        assertEquals("emergencia_silenciosa", Notificacoes.montar(dadosEmerg("1"), TERCA_10H, em(2026, 10, 17, 10, 0)).canal);
        assertEquals("emergencia_silenciosa", Notificacoes.montar(dadosEmerg("1"), TERCA_10H, em(2026, 10, 20, 6, 59)).canal);
        Montada jaSilenciosa = Notificacoes.montar(dados("emergencia", "emergencia_silenciosa", "t", "x", "1"),
            SEXTA_19H40, SEXTA_19H40);
        assertEquals("emergencia_silenciosa", jaSilenciosa.canal);
        assertNull(jaSilenciosa.aviso);
    }

    @Test
    public void oResumoNaoMudaDeCanalPelaHoraDaChegada() {
        // Os resumos saem às 07:00 e às 13:00; o que chega atrasado fora do expediente continua no canal dele.
        Montada m = Notificacoes.montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas."), em(2026, 10, 17, 7, 0),
            em(2026, 10, 17, 21, 0));
        assertEquals("resumo", m.canal);
        assertNull(m.aviso);
    }

    @Test
    public void semAHoraDoEnvioABarraDizAHoraDaChegada() {
        // O getSentTime() do Firebase vem 0 quando a mensagem não traz a hora.
        assertEquals(TERCA_10H02, Notificacoes.montar(dadosEmerg("1"), 0, TERCA_10H02).quando);
        assertEquals(TERCA_10H02, Notificacoes.montar(dadosEmerg("1"), -5, TERCA_10H02).quando);
    }

    @Test
    public void tituloVazioViraPadraoETextoLongoECortado() {
        assertEquals("Nexus Field", montar(dados("resumo", "resumo", "", "x", null)).titulo);
        assertEquals("Nexus Field", montar(dados("resumo", "resumo", "   ", "x", null)).titulo);
        assertEquals("Nexus Field", montar(dados("resumo", "resumo", null, "x", null)).titulo);
        assertEquals("o padrão é o nome do aplicativo", app.getString(R.string.app_name), Notificacoes.TITULO_PADRAO);

        String longo = repetir('a', 300);
        String cortado = montar(dados("resumo", "resumo", "t", longo, null)).texto;
        assertEquals(240, cortado.length());
        assertTrue(cortado.endsWith("…"));
        assertEquals(repetir('a', 239) + "…", cortado);

        String noLimite = repetir('b', 240);
        assertEquals("240 cabem inteiros", noLimite, montar(dados("resumo", "resumo", "t", noLimite, null)).texto);
        assertEquals("sem texto, a notificação fica só com o título", "",
            montar(dados("resumo", "resumo", "t", null, null)).texto);

        // O corte não parte um caractere de dois pedaços (UTF-16) ao meio. O par vai escrito por escape (um rosto
        // sorrindo, U+1F600): o escape independe da codificação do fonte.
        String par = "😀";
        // Com 238 letras antes, a metade alta cai no índice 238 e a baixa no 239: o corte em 239 (240 com o "…") levaria
        // só a metade alta. O corte recua uma posição e o par sai inteiro do texto.
        String parNoCorte = montar(dados("resumo", "resumo", "t", repetir('c', 238) + par + repetir('d', 10), null)).texto;
        assertEquals("o par no limite sai inteiro, sem a metade alta sozinha", repetir('c', 238) + "…", parNoCorte);
        assertFalse(Character.isHighSurrogate(parNoCorte.charAt(parNoCorte.length() - 2)));
        // Com 237 letras antes, o par ocupa os índices 237 e 238 e cabe inteiro antes do corte: nada recua.
        String parCabe = montar(dados("resumo", "resumo", "t", repetir('c', 237) + par + repetir('d', 10), null)).texto;
        assertEquals("o par que cabe fica, e o texto tem os 240", repetir('c', 237) + par + "…", parCabe);
    }

    @Test
    public void osComLetrasEIgnorada() {
        assertFalse(Notificacoes.osValida("12a"));
        assertNull(montar(dadosEmerg("12a")).os);
        assertTrue(Notificacoes.osValida("1"));
        assertTrue(Notificacoes.osValida("123456789012"));
        for (String ruim : new String[] {null, "", "1234567890123", " 12", "12 ", "-1", "1.5", "12'3", "１２", "١"}) {
            assertFalse("OS fora de ^\\d{1,12}$: " + ruim, Notificacoes.osValida(ruim));
        }
    }

    @Test
    public void emergenciaSemOsValidaAindaApareceSemSubstituirOutra() {
        // Mostra (a emergência não se perde) e não leva OS ao toque. O id vem do texto: a mesma mensagem repetida
        // substitui, e outra não apaga a primeira nem uma emergência com OS.
        Montada a = montar(dados("emergencia", "emergencia", "t", "Corretiva emergencial", "12a"));
        Montada b = montar(dados("emergencia", "emergencia", "t", "Corretiva emergencial", null));
        Montada c = montar(dados("emergencia", "emergencia", "t", "Religamento", null));
        assertNotNull(a);
        assertNull(a.os);
        assertEquals(a.id, b.id);
        assertNotEquals(a.id, c.id);
        assertNotEquals(Notificacoes.ID_RESUMO, a.id);
        assertTrue("fora da faixa das emergências com OS", a.id >= Notificacoes.ID_EMERGENCIA_BASE + 1_000_000);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Os canais
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    public void osTresCanaisSaoCriadosUmaVezSo() {
        CanaisDeNotificacao.criar(app);
        CanaisDeNotificacao.criar(app);
        assertEquals(3, nm.getNotificationChannels().size());

        NotificationChannel resumo = nm.getNotificationChannel("resumo");
        assertEquals(app.getString(R.string.notificacao_canal_resumo), resumo.getName().toString());
        assertEquals("Resumo do dia", resumo.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, resumo.getImportance());

        NotificationChannel emergencia = nm.getNotificationChannel("emergencia");
        assertEquals("Emergências", emergencia.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_HIGH, emergencia.getImportance());
        assertNotNull("toca", emergencia.getSound());
        assertTrue("vibra", emergencia.shouldVibrate());

        NotificationChannel silenciosa = nm.getNotificationChannel("emergencia_silenciosa");
        assertEquals("Emergências fora do expediente", silenciosa.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_LOW, silenciosa.getImportance());
        assertNull("não toca", silenciosa.getSound());
        assertFalse("não vibra", silenciosa.shouldVibrate());

        // Nenhum canal fura o Não perturbe: quem decide isso é o técnico, nos ajustes do Android.
        for (NotificationChannel canal : nm.getNotificationChannels()) {
            assertFalse(canal.getId(), canal.canBypassDnd());
        }
    }

    @Test
    public void aEmergenciaTocaOAlarmeDoAparelhoEOResumoOSomDeNotificacao() {
        // A emergência não pode soar como o resumo. O canal dela toca o som de alarme padrão do aparelho (como
        // notificação de evento, no volume das notificações, sem arquivo de som nosso) e vibra no padrão próprio; o
        // resumo fica com o som padrão de notificação.
        CanaisDeNotificacao.criar(app);
        NotificationChannel emergencia = nm.getNotificationChannel("emergencia");
        NotificationChannel resumo = nm.getNotificationChannel("resumo");

        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), emergencia.getSound());
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_EVENT, emergencia.getAudioAttributes().getUsage());
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, emergencia.getAudioAttributes().getContentType());
        assertArrayEquals(new long[] {0, 500, 250, 500, 250, 500}, emergencia.getVibrationPattern());

        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, resumo.getSound());
        assertNotEquals("os dois soam diferente", resumo.getSound(), emergencia.getSound());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A notificação postada e o toque
    // ---------------------------------------------------------------------------------------------------------------

    private Notification mostrada(Montada m) {
        assertTrue(Notificacoes.mostrar(app, m, TERCA_10H));
        Notification n = sombra.getNotification(m.id);
        assertNotNull("postada com o id da montagem", n);
        return n;
    }

    private static Intent intentDoToque(Notification n) {
        ShadowPendingIntent pi = shadowOf(n.contentIntent);
        assertTrue("o toque abre uma activity", pi.isActivity());
        assertTrue("PendingIntent imutável", (pi.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        return pi.getSavedIntent();
    }

    @Test
    public void aEmergenciaLevaAOsAoTocarPelaPortaInternaEVaiAoCanalDela() {
        Notification n = mostrada(montar(dadosEmerg("12345")));
        assertEquals("emergencia", n.getChannelId());
        assertEquals(R.drawable.ic_notificacao, n.getSmallIcon().getResId());
        assertEquals("Emergência", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Corretiva emergencial: OS 12345", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertTrue("a mesma OS de novo não toca outra vez", (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);

        // O toque vai à porta interna (a activity-alias não exportada): só ela leva a OS à página, e um app de fora não
        // a alcança.
        Intent toque = intentDoToque(n);
        assertEquals(MainActivity.PORTA_INTERNA, toque.getComponent().getClassName());
        assertEquals(app.getPackageName(), toque.getComponent().getPackageName());
        assertEquals("12345", toque.getStringExtra(Notificacoes.EXTRA_OS));
        assertEquals("gc_os", Notificacoes.EXTRA_OS);
        assertTrue((toque.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
        assertEquals("a porta interna, lida como a MainActivity lê", "12345", MainActivity.osDoToque(new Intent(toque)));
    }

    @Test
    public void aEmergenciaFicaNaBarraDepoisDoToque() {
        // O toque não a apaga: ela só sai quando a página diz que abriu a OS (osAberta). Com o técnico ocupado a OS fica
        // pendente na página, e o renderer que morre ou o app fechado levavam a pendência junto com a notificação.
        for (Map<String, String> d : new Map[] {dadosEmerg("12345"),
                dados("emergencia", "emergencia_silenciosa", "Emergência", "Religamento: OS 777", "777")}) {
            Notification n = mostrada(montar(d));
            assertEquals(0, n.flags & Notification.FLAG_AUTO_CANCEL);
        }
        // O resumo sai no toque, como antes.
        Notification resumo = mostrada(montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas.")));
        assertTrue((resumo.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        // A emergência sem OS válida também sai no toque: não há OS para a página abrir, e nada mais (o osAberta) a tiraria
        // da barra; ficava presa até o Sair.
        for (Map<String, String> d : new Map[] {dados("emergencia", "emergencia", "Emergência", "Corretiva emergencial", null),
                dados("emergencia", "emergencia_silenciosa", "Emergência", "Religamento", "12a")}) {
            Montada m = montar(d);
            assertNull(m.os);
            Notification n = mostrada(m);
            assertTrue("sem OS, sai no toque", (n.flags & Notification.FLAG_AUTO_CANCEL) != 0);
            assertFalse(intentDoToque(n).hasExtra(Notificacoes.EXTRA_OS));
        }
    }

    @Test
    public void nenhumaNotificacaoTemCategoriaQueFuraONaoPerturbe() {
        // O filtro do Não perturbe trata como alarme toda notificação da categoria "alarm" (e como lembrete a da
        // "reminder"), e as deixa passar no modo prioridade, que libera os alarmes por padrão. A decisão do dono é
        // respeitar o Não perturbe: nenhuma das três leva categoria.
        assertNull(mostrada(montar(dadosEmerg("12345"))).category);
        assertNull(mostrada(montar(dados("emergencia", "emergencia_silenciosa", "t", "x", "9"))).category);
        assertNull(mostrada(montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas."))).category);
    }

    @Test
    public void aBarraMostraAHoraDoEnvio() {
        Montada atrasada = Notificacoes.montar(dadosEmerg("12345"), SEXTA_16H50, SEXTA_19H40);
        assertTrue(Notificacoes.mostrar(app, atrasada, SEXTA_19H40));
        Notification n = sombra.getNotification(atrasada.id);
        assertEquals(SEXTA_16H50, n.when);
        assertTrue("a hora aparece na barra", n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN));
        assertEquals("e foi ao silencioso", "emergencia_silenciosa", n.getChannelId());
    }

    @Test
    public void oResumoSaiDaBarraNoFimDoDiaEAEmergenciaNao() {
        // O "Boa tarde! Faltam 2 tarefas de hoje" de sexta não pode estar na barra na segunda: some à meia-noite do dia em
        // que foi enviado.
        long sexta13h = em(2026, 10, 16, 13, 0);
        Montada resumo = Notificacoes.montar(dadosResumo("Boa tarde!", "Faltam 2 tarefas de hoje."), sexta13h, sexta13h);
        assertTrue(Notificacoes.mostrar(app, resumo, sexta13h));
        Notification n = sombra.getNotification(resumo.id);
        assertEquals(em(2026, 10, 17, 0, 0) - sexta13h, n.getTimeoutAfter());

        Notification emergencia = mostrada(montar(dadosEmerg("12345")));
        assertEquals("a emergência fica até a OS abrir", 0, emergencia.getTimeoutAfter());
    }

    @Test
    public void oResumoQueChegaDepoisDoDiaDoEnvioNaoApareceEOPrazoEODoDiaDoEnvio() {
        // O celular sem sinal a noite inteira: o FCM entrega o "Faltam 2 tarefas de hoje" de ontem de manhã, e as tarefas
        // "de hoje" dele são as de ontem. Não aparece, e o log diz só o motivo.
        long ontem13h = em(2026, 10, 15, 13, 0);
        long hoje7h = em(2026, 10, 16, 7, 0);
        Montada atrasado = Notificacoes.montar(dadosResumo("Boa tarde!", "Faltam 2 tarefas de hoje."), ontem13h, hoje7h);
        ShadowLog.clear();
        assertFalse(Notificacoes.mostrar(app, atrasado, hoje7h));
        assertEquals(0, sombra.size());
        assertTrue(logou("resumo não mostrado"));
        assertFalse("nada do texto no log", logou("tarefas"));
        assertFalse(logou("mostrada ("));
        // Enviado às 23:50 e chegado às 00:05: o mesmo.
        assertFalse(Notificacoes.mostrar(app, Notificacoes.montar(dadosResumo("Boa tarde!", "Faltam 2."),
            em(2026, 10, 15, 23, 50), em(2026, 10, 16, 0, 5)), em(2026, 10, 16, 0, 5)));
        // Enviado às 13:00 e chegado às 17:30 do mesmo dia: aparece, e sai à meia-noite do dia do envio (não 24 h depois).
        long envio = em(2026, 10, 16, 13, 0);
        long chegada = em(2026, 10, 16, 17, 30);
        Montada tarde = Notificacoes.montar(dadosResumo("Boa tarde!", "Faltam 2."), envio, chegada);
        assertTrue(Notificacoes.mostrar(app, tarde, chegada));
        assertEquals(em(2026, 10, 17, 0, 0) - chegada, sombra.getNotification(tarde.id).getTimeoutAfter());
        // A emergência atrasada aparece (no silencioso, se chegou fora do expediente): só o resumo vence com o dia.
        assertTrue(Notificacoes.mostrar(app, Notificacoes.montar(dadosEmerg("12345"), ontem13h, hoje7h), hoje7h));
        // O relógio do aparelho atrás do do servidor, na virada do dia: o prazo é o do dia do ENVIO (o de amanhã, no
        // relógio do aparelho), e não o do dia que o aparelho acha que é.
        long envioDepoisDaMeiaNoite = em(2026, 10, 17, 0, 5);
        long aparelhoAtrasado = em(2026, 10, 16, 23, 55);
        Montada adiantado = Notificacoes.montar(dadosResumo("Bom dia!", "Hoje: 3."), envioDepoisDaMeiaNoite, aparelhoAtrasado);
        assertTrue(Notificacoes.mostrar(app, adiantado, aparelhoAtrasado));
        assertEquals(em(2026, 10, 18, 0, 0) - aparelhoAtrasado, sombra.getNotification(adiantado.id).getTimeoutAfter());
    }

    @Test
    public void oMesmoResumoDeNovoNaoTocaOutraVezMasOutroTexto() {
        // A entrega repetida do FCM (ou o envio repetido do servidor) não faz o mesmo resumo tocar duas vezes; o das 13h,
        // com outro texto, toca mesmo com o das 7h ainda na barra.
        Notification primeiro = mostrada(montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas.")));
        assertEquals(0, primeiro.flags & Notification.FLAG_ONLY_ALERT_ONCE);
        Notification repetido = mostrada(montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas.")));
        assertTrue("o mesmo título e texto na barra: não toca", (repetido.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        // Cada um conta: o mesmo título com outro texto toca, e o mesmo texto com outro título também.
        Notification outroTexto = mostrada(montar(dadosResumo("Bom dia!", "Hoje: 5 tarefas.")));
        assertEquals("o mesmo título, outro texto: toca", 0, outroTexto.flags & Notification.FLAG_ONLY_ALERT_ONCE);
        Notification outroTitulo = mostrada(montar(dadosResumo("Boa tarde!", "Hoje: 5 tarefas.")));
        assertEquals("o mesmo texto, outro título: toca", 0, outroTitulo.flags & Notification.FLAG_ONLY_ALERT_ONCE);
        Notification tarde = mostrada(montar(dadosResumo("Boa tarde!", "Faltam 2 tarefas de hoje.")));
        assertEquals("o das 13h toca", 0, tarde.flags & Notification.FLAG_ONLY_ALERT_ONCE);
        assertEquals(1, sombra.size());
    }

    @Test
    public void oResumoRepetidoDepoisDeSairDaBarraTocaDeNovo() {
        mostrada(montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas.")));
        nm.cancel(Notificacoes.ID_RESUMO);
        Notification denovo = mostrada(montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas.")));
        assertEquals(0, denovo.flags & Notification.FLAG_ONLY_ALERT_ONCE);
    }

    @Test
    public void oResumoAbreOAppSemOs() {
        Notification n = mostrada(montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas.")));
        assertEquals("resumo", n.getChannelId());
        assertFalse("o das 13h toca mesmo com o das 7h ainda na barra", (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        Intent toque = intentDoToque(n);
        assertEquals(MainActivity.PORTA_INTERNA, toque.getComponent().getClassName());
        assertFalse(toque.hasExtra(Notificacoes.EXTRA_OS));
    }

    @Test
    public void duasEmergenciasNaBarraCadaUmaAbreASuaOs() {
        // O PendingIntent é igual para o sistema quando só os extras mudam: com o mesmo código de pedido, a segunda
        // notificação abriria a OS da primeira.
        Notification a = mostrada(montar(dadosEmerg("111")));
        Notification b = mostrada(montar(dadosEmerg("222")));
        assertEquals(2, sombra.size());
        assertEquals("111", intentDoToque(a).getStringExtra(Notificacoes.EXTRA_OS));
        assertEquals("222", intentDoToque(b).getStringExtra(Notificacoes.EXTRA_OS));
    }

    @Test
    public void aMesmaEmergenciaDuasVezesSubstituiNaoEmpilha() {
        mostrada(montar(dadosEmerg("12345")));
        mostrada(montar(dadosEmerg("12345")));
        assertEquals(1, sombra.size());
    }

    @Test
    public void semAPermissaoNadaEMostradoENadaLanca() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertFalse(Notificacoes.mostrar(app, montar(dadosEmerg("12345")), TERCA_10H));
        assertEquals(0, sombra.size());
    }

    private boolean logou(String trecho) {
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag(CascaPlugin.TAG)) {
            if (item.msg.contains(trecho)) return true;
        }
        return false;
    }

    @Test
    public void comAsNotificacoesDoAppDesligadasOLogNaoDizMostrada() {
        // Do Android 12 para trás, sem a permissão, quem desliga é o técnico nos ajustes; o sistema descarta em silêncio, e
        // o log dizia "mostrada".
        sombra.setNotificationsEnabled(false);
        ShadowLog.clear();
        assertFalse(Notificacoes.mostrar(app, montar(dadosEmerg("12345")), TERCA_10H));
        assertEquals(0, sombra.size());
        assertFalse(logou("mostrada ("));
        assertTrue(logou("desligadas"));
    }

    @Test
    public void comOCanalDasEmergenciasDesligadoAEmergenciaNaoEMostradaEOResumoE() {
        CanaisDeNotificacao.criar(app);
        nm.createNotificationChannel(new NotificationChannel("emergencia", "Emergências", NotificationManager.IMPORTANCE_NONE));
        assertEquals(NotificationManager.IMPORTANCE_NONE, nm.getNotificationChannel("emergencia").getImportance());
        ShadowLog.clear();
        assertFalse(Notificacoes.mostrar(app, montar(dadosEmerg("12345")), TERCA_10H));
        assertFalse(logou("mostrada ("));
        assertTrue(logou("desligado"));
        assertTrue(Notificacoes.mostrar(app, montar(dadosResumo("Bom dia!", "Hoje: 4 tarefas.")), TERCA_10H));
        assertEquals(1, sombra.size());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // O serviço do Firebase
    // ---------------------------------------------------------------------------------------------------------------

    /** O serviço com o relógio parado num instante: a hora da chegada da mensagem. */
    public static final class ServicoComRelogio extends MensagensDoFirebase {
        static long agora = TERCA_10H02;

        @Override
        long agora() {
            return agora;
        }
    }

    private static RemoteMessage mensagem(Map<String, String> dados) {
        return mensagem(dados, 0);
    }

    // A hora do envio vai na chave do próprio Firebase (google.sent_time), como chega do servidor.
    private static RemoteMessage mensagem(Map<String, String> dados, long enviadaEm) {
        Bundle b = new Bundle();
        for (Map.Entry<String, String> e : dados.entrySet()) b.putString(e.getKey(), e.getValue());
        if (enviadaEm > 0) b.putLong("google.sent_time", enviadaEm);
        return new RemoteMessage(b);
    }

    private MensagensDoFirebase servico() {
        ServicoComRelogio.agora = TERCA_10H02;
        return Robolectric.setupService(ServicoComRelogio.class);
    }

    @Test
    public void oServicoCriaOsCanaisEMostraAMensagemDoContrato() {
        servico().onMessageReceived(mensagem(dadosEmerg("12345"), TERCA_10H));
        assertEquals("os canais nascem também aqui (o app pode nunca ter sido aberto depois da atualização)",
            3, nm.getNotificationChannels().size());
        assertEquals(1, sombra.size());
        Notification n = sombra.getNotification(montar(dadosEmerg("12345")).id);
        assertNotNull(n);
        assertEquals("emergencia", n.getChannelId());
        assertEquals("a hora do envio, do Firebase", TERCA_10H, n.when);
    }

    @Test
    public void oServicoLevaAoSilenciosoAEmergenciaQueChegouForaDoExpediente() {
        MensagensDoFirebase servico = servico();
        ServicoComRelogio.agora = SEXTA_19H40;
        ShadowLog.clear();
        servico.onMessageReceived(mensagem(dadosEmerg("12345"), SEXTA_16H50));
        Notification n = sombra.getNotification(montar(dadosEmerg("12345")).id);
        assertNotNull(n);
        assertEquals("emergencia_silenciosa", n.getChannelId());
        assertEquals(SEXTA_16H50, n.when);
        assertTrue(logou("fora do expediente"));
        assertFalse("o log não leva a OS", logou("12345"));
    }

    @Test
    public void oServicoIgnoraAMensagemForaDoContrato() {
        servico().onMessageReceived(mensagem(dados("promo", "resumo", "t", "x", null)));
        assertEquals(0, sombra.size());
    }

    @Test
    public void oServicoMostraAEmergenciaDeCanalErradoNoSilenciosoEOLogDizSoOMotivo() {
        // O título, o texto e a OS são distintos de propósito: nenhum deles pode aparecer no log.
        Map<String, String> d = dados("emergencia", "resumo", "TituloDeProvaX1", "TextoDeProvaY2", "987654");
        ShadowLog.clear();
        servico().onMessageReceived(mensagem(d));

        assertEquals("a emergência não se perde", 1, sombra.size());
        Notification n = sombra.getNotification(montar(d).id);
        assertNotNull(n);
        assertEquals("emergencia_silenciosa", n.getChannelId());
        assertEquals("987654", intentDoToque(n).getStringExtra(Notificacoes.EXTRA_OS));

        boolean avisou = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag(CascaPlugin.TAG)) {
            if (item.type == Log.WARN && item.msg.contains("canal fora do contrato")) avisou = true;
            for (String proibido : new String[] {"TituloDeProvaX1", "TextoDeProvaY2", "987654"}) {
                assertFalse("o log levou o conteúdo: " + item.msg, item.msg.contains(proibido));
            }
        }
        assertTrue("o log avisou o canal trocado", avisou);
    }

    @Test
    public void depoisDoSairOServicoNaoMostraNadaAteONovoRegistro() {
        // O Sair sem rede (ou com o servidor fora, ou com a sessão vencida) não chega ao servidor, e o aparelho seguia com
        // os avisos de quem saiu: a marca semConta cala a exibição até o registro seguinte (o pushRegistrado).
        assertTrue(MensagensDoFirebase.marcarSemConta(app));
        ShadowLog.clear();
        Map<String, String> d = dados("emergencia", "emergencia", "TituloDeProvaX1", "TextoDeProvaY2", "987654");
        servico().onMessageReceived(mensagem(d));
        servico().onMessageReceived(mensagem(dadosResumo("Bom dia!", "Hoje: 4 tarefas.")));
        assertEquals("nada aparece", 0, sombra.size());
        assertTrue("o log diz por quê", logou("Sair"));
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag(CascaPlugin.TAG)) {
            for (String proibido : new String[] {"TituloDeProvaX1", "TextoDeProvaY2", "987654", "Bom dia"}) {
                assertFalse("o log levou o conteúdo: " + item.msg, item.msg.contains(proibido));
            }
        }

        assertTrue(MensagensDoFirebase.soltarSemConta(app));
        servico().onMessageReceived(mensagem(d));
        assertEquals("registrado de novo, volta a mostrar", 1, sombra.size());
    }

    @Test
    public void tokenNovoEGuardadoSemMexerNoRestoDoArquivo() {
        // O token novo é guardado (a volta ao app o compara com o que a página recebeu) e vai à página viva, que o
        // registra; sem a página, a próxima carga pede o token e registra. O resto do arquivo (a marca da permissão de
        // notificação, a do Sair) fica.
        SharedPreferences push = app.getSharedPreferences("push", Context.MODE_PRIVATE);
        assertTrue(push.edit().putString("token", "velho").putBoolean("jaPediuNotificacao", true)
            .putBoolean("semConta", true).commit());

        servico().onNewToken("novo");

        assertEquals("novo", push.getString("token", null));
        assertEquals("novo", MensagensDoFirebase.tokenGuardado(app));
        assertTrue(push.getBoolean("jaPediuNotificacao", false));
        assertTrue("a marca do Sair fica: só o registro a solta", push.getBoolean("semConta", false));
        assertEquals("só o token e as marcas", 3, push.getAll().size());
    }
}
