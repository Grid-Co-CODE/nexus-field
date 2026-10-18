package br.com.gridco.campo;

import static br.com.gridco.campo.Notificacoes.montar;
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
 * em que canal, com que id) e, pelo Robolectric, os três canais, a notificação postada com o toque para a MainActivity e
 * o serviço do Firebase. O servidor aqui é falso: a mensagem é o Map de dados que ele mandaria. Os textos de exemplo só
 * têm contagens e o número da OS, como o contrato manda (nada de usina nem de pessoa).
 */
@RunWith(RobolectricTestRunner.class)
public class NotificacoesTest {

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
        Montada m = montar(dados("resumo", "resumo", "Bom dia!", "Hoje: 4 tarefas.", null));
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
        assertNotEquals("a emergência não substitui o resumo", Notificacoes.ID_RESUMO, m.id);
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
        // Ruling N4: um canal errado do servidor não descarta a emergência. Ela vai ao canal silencioso: aparece na
        // barra, com a OS no toque, sem tocar alto por um canal que ninguém escolheu (o do resumo, ou um que não existe).
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
        String par = "\uD83D\uDE00";
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
    }

    @Test
    public void aEmergenciaTocaOAlarmeDoAparelhoEOResumoOSomDeNotificacao() {
        // Ruling N5: a emergência não pode soar como o resumo. O canal dela toca o som de alarme padrão do aparelho
        // (como notificação de evento, sem arquivo de som nosso) e vibra no padrão próprio; o resumo fica com o som
        // padrão de notificação.
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
        assertTrue(Notificacoes.mostrar(app, m));
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
    public void aEmergenciaLevaAOsAoTocarEVaiAoCanalDela() {
        Notification n = mostrada(montar(dadosEmerg("12345")));
        assertEquals("emergencia", n.getChannelId());
        assertEquals(R.drawable.ic_notificacao, n.getSmallIcon().getResId());
        assertEquals("Emergência", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Corretiva emergencial: OS 12345", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(Notification.CATEGORY_ALARM, n.category);
        assertTrue("some ao tocar", (n.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertTrue("a mesma OS de novo não toca outra vez", (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);

        Intent toque = intentDoToque(n);
        assertEquals(MainActivity.class.getName(), toque.getComponent().getClassName());
        assertEquals("12345", toque.getStringExtra(Notificacoes.EXTRA_OS));
        assertEquals("gc_os", Notificacoes.EXTRA_OS);
        assertTrue((toque.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
    }

    @Test
    public void oResumoAbreOAppSemOs() {
        Notification n = mostrada(montar(dados("resumo", "resumo", "Bom dia!", "Hoje: 4 tarefas.", null)));
        assertEquals("resumo", n.getChannelId());
        assertEquals(Notification.CATEGORY_REMINDER, n.category);
        assertTrue((n.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertFalse("o das 13h toca mesmo com o das 7h ainda na barra", (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        Intent toque = intentDoToque(n);
        assertEquals(MainActivity.class.getName(), toque.getComponent().getClassName());
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
        assertFalse(Notificacoes.mostrar(app, montar(dadosEmerg("12345"))));
        assertEquals(0, sombra.size());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // O serviço do Firebase
    // ---------------------------------------------------------------------------------------------------------------

    private static RemoteMessage mensagem(Map<String, String> dados) {
        RemoteMessage.Builder b = new RemoteMessage.Builder("servidor-de-teste");
        for (Map.Entry<String, String> e : dados.entrySet()) b.addData(e.getKey(), e.getValue());
        return b.build();
    }

    @Test
    public void oServicoCriaOsCanaisEMostraAMensagemDoContrato() {
        MensagensDoFirebase servico = Robolectric.setupService(MensagensDoFirebase.class);
        servico.onMessageReceived(mensagem(dadosEmerg("12345")));
        assertEquals("os canais nascem também aqui (o app pode nunca ter sido aberto depois da atualização)",
            3, nm.getNotificationChannels().size());
        assertEquals(1, sombra.size());
        assertNotNull(sombra.getNotification(montar(dadosEmerg("12345")).id));
    }

    @Test
    public void oServicoIgnoraAMensagemForaDoContrato() {
        MensagensDoFirebase servico = Robolectric.setupService(MensagensDoFirebase.class);
        servico.onMessageReceived(mensagem(dados("promo", "resumo", "t", "x", null)));
        assertEquals(0, sombra.size());
    }

    @Test
    public void oServicoMostraAEmergenciaDeCanalErradoNoSilenciosoEOLogDizSoOMotivo() {
        // O título, o texto e a OS são distintos de propósito: nenhum deles pode aparecer no log.
        Map<String, String> d = dados("emergencia", "resumo", "TituloDeProvaX1", "TextoDeProvaY2", "987654");
        ShadowLog.clear();
        MensagensDoFirebase servico = Robolectric.setupService(MensagensDoFirebase.class);
        servico.onMessageReceived(mensagem(d));

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
    public void tokenNovoEGuardadoSemMexerNoRestoDoArquivo() {
        // O token novo é guardado, e a próxima carga da página com alguém logado o registra (Ruling N3: toda carga
        // registra o token de agora, e o nativo não guarda nada do registro). A marca da permissão de notificação, do
        // mesmo arquivo, fica.
        SharedPreferences push = app.getSharedPreferences("push", Context.MODE_PRIVATE);
        assertTrue(push.edit().putString("token", "velho").putBoolean("jaPediuNotificacao", true).commit());

        MensagensDoFirebase servico = Robolectric.setupService(MensagensDoFirebase.class);
        servico.onNewToken("novo");

        assertEquals("novo", push.getString("token", null));
        assertTrue(push.getBoolean("jaPediuNotificacao", false));
        assertEquals("só o token e a marca", 2, push.getAll().size());
    }
}
