package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;
import java.util.TimeZone;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowPendingIntent;

/**
 * O aviso do que falta liberar no celular (Task 14, o pedido do dono em 10/11/2026: "se algo ficar pendente tem que ter
 * notificação 'faltando tal coisa'"): quando aparece, o que diz, o toque que abre os ajustes do Nexus Field, quando sai e
 * os pontos que o conferem (a volta ao app é provada no EquipeNaCascaTest). O relógio é o de Brasília em novembro de 2026
 * (o dia 9 é uma segunda); a chave e a conta são fictícias. O Início automático vem de um leitor injetado.
 */
@RunWith(RobolectricTestRunner.class)
public class AvisoDoQueFaltaTest {

    private static final TimeZone BRASILIA = TimeZone.getTimeZone("America/Sao_Paulo");
    // Fictícias, no formato do contrato (a chave: 32 bytes em base64url; a conta: o SHA-256 em hex que a página manda).
    private static final String CHAVE = "chaveFicticiaDaEquipe_AAAAAAAAAAAAAAAAAAAAA";
    private static final String CONTA = "aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000";
    private static final String PRECISA = Manifest.permission.ACCESS_FINE_LOCATION;
    private static final String APROXIMADA = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final String TEMPO_TODO = Manifest.permission.ACCESS_BACKGROUND_LOCATION;
    private static final String FIM_DO_TEXTO = ". Toque para abrir os ajustes do Nexus Field.";

    private Application app;
    private TimeZone fusoDeAntes;
    private long agora;
    private ChaveDaEquipe chave;

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        fusoDeAntes = TimeZone.getDefault();
        TimeZone.setDefault(BRASILIA);
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("equipe_falta", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, PRECISA, APROXIMADA, TEMPO_TODO);
        liberarABateria(true);
        chave = new ChaveDaEquipe(app, new CifraFalsa());
        // As peças do processo, como o receptor as lê (o relógio e a chave); a fila nem abre, e o Nexus é falso.
        FilaDePontos fila = new FilaDePontos(app);
        EnvioDePontos envio = new EnvioDePontos(chave, fila, (url, k, corpo) -> new EnvioDePontos.Resposta(200,
            "{\"aceitos\":0,\"coleta\":true}"), () -> agora);
        ServicoDaEquipe.pecasDeTeste = new ServicoDaEquipe.Pecas(() -> agora, () -> 0L, chave, fila, envio,
            new FonteParada(), Runnable::run);
        agora = em(9, 9, 0);
        ShadowLog.clear();
    }

    @After
    public void fechar() {
        ServicoDaEquipe.pecasDeTeste = null;
        InicioAutomatico.leitorDeTeste = null;
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
        Calendar c = Calendar.getInstance(BRASILIA);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, dia, hora, minuto, 0);
        return c.getTimeInMillis();
    }

    /** O aparelho no piloto: a chave da equipe guardada e o termo da versão de agora aceito. */
    private void comChaveETermo() {
        chave.guardar(CHAVE, CONTA);
        chave.aceitarTermo();
    }

    private void liberarABateria(boolean liberada) {
        PowerManager energia = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        shadowOf(energia).setIgnoringBatteryOptimizations(app.getPackageName(), liberada);
    }

    /** Um Xiaomi com o Início automático no modo dado (o MODE_ALLOWED é o ligado). */
    private static void xiaomiNoModo(int modo) {
        ShadowBuild.setManufacturer("Xiaomi");
        InicioAutomatico.leitorDeTeste = contexto -> modo;
    }

    private NotificationManager notificacoes() {
        return (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** O aviso do que falta na barra, ou null. */
    private Notification oAviso() {
        return shadowOf(notificacoes()).getNotification(AvisoDoQueFalta.ID_DA_NOTIFICACAO);
    }

    private String textoDoAviso() {
        Notification n = oAviso();
        assertNotNull("o aviso do que falta na barra", n);
        return texto(n, Notification.EXTRA_TEXT);
    }

    private static String texto(Notification n, String extra) {
        CharSequence s = n.extras.getCharSequence(extra);
        return s == null ? null : s.toString();
    }

    private void conferir() {
        AvisoDoQueFalta.conferir(app, chave, agora);
    }

    // --- o que aparece -----------------------------------------------------------------------------------------------

    @Test
    public void aListaCertaComOTextoExato() {
        comChaveETermo();
        shadowOf(app).denyPermissions(TEMPO_TODO);
        xiaomiNoModo(AppOpsManager.MODE_IGNORED);
        conferir();
        Notification n = oAviso();
        assertNotNull(n);
        assertEquals("Falta liberar no celular", texto(n, Notification.EXTRA_TITLE));
        assertEquals("o exemplo do pedido", "Falta: Permitir o tempo todo e Início automático. Toque para abrir os ajustes"
            + " do Nexus Field.", texto(n, Notification.EXTRA_TEXT));

        // Tudo faltando: a ordem do texto, com a vírgula e o "e" no último. A notificação não entra: sem ela, o aviso nem
        // aparece.
        shadowOf(app).denyPermissions(PRECISA, APROXIMADA);
        liberarABateria(false);
        conferir();
        assertEquals("Falta: localização precisa, Permitir o tempo todo, bateria sem restrição e Início automático"
            + FIM_DO_TEXTO, textoDoAviso());

        // Uma coisa só.
        shadowOf(app).grantPermissions(PRECISA, APROXIMADA, TEMPO_TODO);
        xiaomiNoModo(AppOpsManager.MODE_ALLOWED);
        conferir();
        assertEquals("Falta: bateria sem restrição" + FIM_DO_TEXTO, textoDoAviso());

        // A aproximada não basta: falta a precisa.
        liberarABateria(true);
        shadowOf(app).denyPermissions(PRECISA);
        conferir();
        assertEquals("Falta: localização precisa" + FIM_DO_TEXTO, textoDoAviso());
    }

    @Test
    public void noCanalProprioSemSerFixaEOToqueAbreOsAjustesDoNexusField() {
        comChaveETermo();
        assertNull("o canal só nasce com o primeiro aviso",
            notificacoes().getNotificationChannel(AvisoDoQueFalta.CANAL_AJUSTES));
        liberarABateria(false);
        conferir();

        Notification n = oAviso();
        assertNotNull(n);
        assertEquals("ajustes_da_localizacao", n.getChannelId());
        NotificationChannel canal = notificacoes().getNotificationChannel(AvisoDoQueFalta.CANAL_AJUSTES);
        assertNotNull("o canal próprio", canal);
        assertEquals("Ajustes da localização", canal.getName().toString());
        assertEquals("importância padrão", NotificationManager.IMPORTANCE_DEFAULT, canal.getImportance());
        assertFalse("não fura o Não perturbe", canal.canBypassDnd());
        assertEquals(R.drawable.ic_notificacao, n.getSmallIcon().getResId());
        assertTrue("toca uma vez: a conferência seguinte que atualiza não toca de novo",
            (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertEquals("sem ser fixa: o técnico pode limpar", 0, n.flags & Notification.FLAG_ONGOING_EVENT);
        assertEquals(0, n.flags & Notification.FLAG_NO_CLEAR);
        assertEquals("nenhuma categoria: o Não perturbe do técnico vale", null, n.category);
        assertEquals("sai sozinha às 22h, quando nada mais aparece ao técnico", em(9, 22, 0) - agora, n.getTimeoutAfter());

        ShadowPendingIntent pi = shadowOf(n.contentIntent);
        assertTrue("o toque abre uma activity", pi.isActivity());
        assertTrue("PendingIntent imutável", (pi.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        Intent toque = pi.getSavedIntent();
        assertEquals("os ajustes do Nexus Field: no HyperOS, as permissões, a bateria e o Início automático",
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, toque.getAction());
        assertEquals("package:" + app.getPackageName(), toque.getDataString());
        assertTrue((toque.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);

        // A conferência seguinte com a mesma falta: o mesmo id, uma notificação só.
        conferir();
        assertEquals(1, shadowOf(notificacoes()).getAllNotifications().size());
    }

    @Test
    public void oInicioAutomaticoSoContaDesligado() {
        comChaveETermo();
        conferir();
        assertNull("fora do Xiaomi não se aplica, e o resto está liberado", oAviso());
        ShadowBuild.setManufacturer("Xiaomi");
        InicioAutomatico.leitorDeTeste = contexto -> {
            throw new NoSuchMethodException("checkOpNoThrow");
        };
        conferir();
        assertNull("sem saber o estado, não diz que falta", oAviso());
        xiaomiNoModo(AppOpsManager.MODE_ALLOWED);
        conferir();
        assertNull("ligado", oAviso());
        xiaomiNoModo(AppOpsManager.MODE_IGNORED);
        conferir();
        assertEquals("Falta: Início automático" + FIM_DO_TEXTO, textoDoAviso());
    }

    // --- o som: uma vez por dia para cada lista ---------------------------------------------------------------------

    /** O aviso de agora tocou: não foi postado em silêncio (o setSilent do NotificationCompat o põe no grupo silencioso). */
    private static boolean tocou(Notification n) {
        assertNotNull("o aviso do que falta na barra", n);
        return !NotificationCompat.GROUP_KEY_SILENT.equals(n.getGroup());
    }

    /** O técnico limpa o aviso da barra. */
    private void limpar() {
        notificacoes().cancel(AvisoDoQueFalta.ID_DA_NOTIFICACAO);
        assertNull(oAviso());
    }

    @Test
    public void oSomValeUmaVezPorDiaParaCadaListaMesmoDepoisDeLimpar() {
        // A revisão da Task 14 (10/11/2026): sem a marca, o aviso limpo com algo ainda faltando voltava tocando a cada
        // envio (de 2 a 10 min com a coleta ligada), porque o setOnlyAlertOnce só cala o aviso que ainda está na barra.
        comChaveETermo();
        shadowOf(app).denyPermissions(TEMPO_TODO);
        conferir();
        assertTrue("o primeiro aviso do dia toca", tocou(oAviso()));

        limpar();
        agora = em(9, 9, 10);
        conferir();
        assertFalse("o mesmo dia e a mesma lista, depois de limpar: volta em silêncio", tocou(oAviso()));
        assertEquals("Falta: Permitir o tempo todo" + FIM_DO_TEXTO, textoDoAviso());

        limpar();
        liberarABateria(false);
        conferir();
        assertTrue("outra lista toca", tocou(oAviso()));

        limpar();
        agora = em(9, 21, 50);
        conferir();
        assertFalse("a mesma lista no fim do dia: silêncio", tocou(oAviso()));

        limpar();
        agora = em(10, 6, 5);
        conferir();
        assertTrue("o dia seguinte, na hora do celular, toca de novo", tocou(oAviso()));

        // O apagar (o Sair, a troca de conta, o coleta:false) tira a marca: a próxima conta ouve o aviso dela.
        chave.apagar();
        comChaveETermo();
        conferir();
        assertTrue("depois do apagar, toca de novo", tocou(oAviso()));
    }

    // --- quando não aparece ------------------------------------------------------------------------------------------

    @Test
    public void naoApareceSemChaveSemTermoComOSairPorTerminarOuForaDaJanela() {
        shadowOf(app).denyPermissions(TEMPO_TODO);
        conferir();
        assertNull("sem a chave (fora do piloto)", oAviso());
        chave.guardar(CHAVE, CONTA);
        conferir();
        assertNull("sem o termo aceito", oAviso());
        chave.aceitarTermo();
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().putString("termo_aceito", "4").commit();
        conferir();
        assertNull("com o termo de outra versão", oAviso());
        chave.aceitarTermo();

        agora = em(9, 5, 59);
        conferir();
        assertNull("antes das 6h", oAviso());
        agora = em(9, 22, 0);
        conferir();
        assertNull("às 22h em ponto já não", oAviso());
        agora = em(9, 6, 0);
        conferir();
        assertNotNull("às 6h em ponto, sim", oAviso());

        // A que estava na barra sai na conferência fora da janela.
        agora = em(9, 22, 30);
        conferir();
        assertNull(oAviso());

        agora = em(10, 8, 0);
        conferir();
        assertNotNull(oAviso());
        chave.saindo();
        assertNull("o toque no Sair a tira", oAviso());
        conferir();
        assertNull("com o Sair por terminar, nada", oAviso());
    }

    @Test
    public void semAPermissaoDeNotificacaoNadaAparece() {
        comChaveETermo();
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS, TEMPO_TODO);
        conferir();
        assertNull(oAviso());
    }

    // --- quando sai --------------------------------------------------------------------------------------------------

    @Test
    public void someSozinhaQuandoNadaFalta() {
        comChaveETermo();
        shadowOf(app).denyPermissions(TEMPO_TODO);
        conferir();
        assertNotNull(oAviso());
        shadowOf(app).grantPermissions(TEMPO_TODO);
        conferir();
        assertNull("liberado, o aviso sai", oAviso());
    }

    @Test
    public void saiComAChaveQueSaiComoOAvisoDas6h() {
        // O Sair, a troca de conta e o coleta:false passam pelo apagar; o 401, pelo apagarAChave.
        shadowOf(app).denyPermissions(TEMPO_TODO);
        comChaveETermo();
        conferir();
        assertNotNull(oAviso());
        chave.apagar();
        assertNull("o apagar o tira", oAviso());

        comChaveETermo();
        conferir();
        assertNotNull(oAviso());
        chave.apagarAChave();
        assertNull("o 401 também", oAviso());
    }

    @Test
    public void naConferenciaQueNaoPodeMostrarSoSai() {
        // A volta ao app com um pedido do Android na frente do técnico (o tempo todo, a bateria, o Início automático):
        // o aviso não aparece no meio do pedido, mas o que já não vale sai.
        comChaveETermo();
        shadowOf(app).denyPermissions(TEMPO_TODO);
        AvisoDoQueFalta.conferir(app, chave, agora, false);
        assertNull("no meio do pedido, nada aparece", oAviso());
        conferir();
        assertNotNull(oAviso());
        liberarABateria(false);
        AvisoDoQueFalta.conferir(app, chave, agora, false);
        assertEquals("nem muda: o que está na barra fica como estava", "Falta: Permitir o tempo todo" + FIM_DO_TEXTO,
            textoDoAviso());
        shadowOf(app).grantPermissions(TEMPO_TODO);
        liberarABateria(true);
        AvisoDoQueFalta.conferir(app, chave, agora, false);
        assertNull("nada falta: sai", oAviso());
    }

    // --- os pontos que conferem --------------------------------------------------------------------------------------

    @Test
    public void oReceptorConfereNoBootNaAtualizacaoENoAlarmeDas6h() {
        comChaveETermo();
        shadowOf(app).denyPermissions(TEMPO_TODO);
        for (String acao : new String[] {Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AvisoDoInicio.ACAO_DO_ALARME}) {
            AvisoDoQueFalta.tirar(app);
            assertNull(oAviso());
            agora = em(10, 6, 0);
            new ReceptorDoInicio().onReceive(app, new Intent(acao).setClass(app, ReceptorDoInicio.class));
            assertEquals(acao, "Falta: Permitir o tempo todo" + FIM_DO_TEXTO, textoDoAviso());
        }
    }

    @Test
    public void cadaEnvioConfereComOsAjustesDoLote() throws Exception {
        // O EnvioDePontos lê os ajustes de cada lote pelo AjustesDaColeta.doLote: a conferência usa os mesmos, e o aviso
        // diz o mesmo que o Nexus vê.
        comChaveETermo();
        liberarABateria(false);
        JSONObject ajustes = AjustesDaColeta.doLote(app, chave, agora);
        assertEquals(6, ajustes.length());
        assertFalse(ajustes.getBoolean("bateriaSemRestricao"));
        assertEquals("Falta: bateria sem restrição" + FIM_DO_TEXTO, textoDoAviso());
        liberarABateria(true);
        AjustesDaColeta.doLote(app, chave, agora);
        assertNull(oAviso());
    }

    @Test
    public void nadaDaChaveNemDaContaNoLog() {
        comChaveETermo();
        shadowOf(app).denyPermissions(TEMPO_TODO);
        conferir();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse(item.msg != null && (item.msg.contains(CHAVE) || item.msg.contains(CONTA)));
        }
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.N)
    public void noAndroid7OAvisoTocaOSomPadrao() {
        // Sem canais: a prioridade e o som da própria notificação fazem o papel do canal.
        comChaveETermo();
        liberarABateria(false);
        conferir();
        Notification n = oAviso();
        assertNotNull(n);
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, n.priority);
        assertTrue((n.defaults & Notification.DEFAULT_SOUND) != 0);
    }
}
