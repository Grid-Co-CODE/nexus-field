package br.com.gridco.campo;

import static br.com.gridco.campo.ExpedienteTest.em;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.media.RingtoneManager;
import android.os.Build;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

import java.util.HashMap;
import java.util.Map;

/**
 * As notificações no Android 7 (o minSdk, 24), que não tem canais: o som, a vibração e a prioridade são os da própria
 * notificação, e do Android 8 em diante o NotificationCompat os apaga (quem decide é o canal). Por isso este teste roda o
 * Robolectric no SDK 24: no 36 o som e a vibração da notificação saem vazios de propósito.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.N)
public class NotificacoesNoAndroid7Test {

    private static final long TERCA_10H = em(2026, 10, 20, 10, 0);

    private Application app;
    private ShadowNotificationManager sombra;

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        sombra = shadowOf((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE));
    }

    private Notification mostrada(String tipo, String canal, String os) {
        Map<String, String> d = new HashMap<>();
        d.put("tipo", tipo);
        d.put("canal", canal);
        d.put("titulo", "Emergência");
        d.put("texto", "Corretiva emergencial: OS " + os);
        if (os != null) d.put("os", os);
        Notificacoes.Montada m = Notificacoes.montar(d, TERCA_10H, TERCA_10H);
        assertTrue(Notificacoes.mostrar(app, m, TERCA_10H));
        Notification n = sombra.getNotification(m.id);
        assertNotNull(n);
        return n;
    }

    @Test
    public void aEmergenciaTocaOAlarmeEVibraComoOCanalDela() {
        assertEquals(24, Build.VERSION.SDK_INT);
        Notification n = mostrada("emergencia", "emergencia", "12345");
        assertEquals("o alarme padrão do aparelho, como no canal", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            n.sound);
        assertArrayEquals("os três pulsos do canal", new long[] {0, 500, 250, 500, 250, 500}, n.vibrate);
        assertEquals("sem o som padrão de notificação por cima do alarme", 0, n.defaults & Notification.DEFAULT_SOUND);
        assertEquals(0, n.defaults & Notification.DEFAULT_VIBRATE);
        assertEquals("aparece por cima, como a importância alta do canal", Notification.PRIORITY_HIGH, n.priority);
        assertNull("nenhuma categoria que fure o Não perturbe", n.category);
    }

    @Test
    public void aSilenciosaNaoTocaNemVibra() {
        Notification n = mostrada("emergencia", "emergencia_silenciosa", "12345");
        assertNull(n.sound);
        assertNull(n.vibrate);
        assertEquals(0, n.defaults & (Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE));
        assertEquals(Notification.PRIORITY_LOW, n.priority);
    }

    @Test
    public void oResumoTocaOSomPadrao() {
        Notification n = mostrada("resumo", "resumo", null);
        assertTrue((n.defaults & Notification.DEFAULT_SOUND) != 0);
        assertNull("o som padrão vem dos padrões, sem arquivo", n.sound);
        assertEquals(Notification.PRIORITY_DEFAULT, n.priority);
    }
}
