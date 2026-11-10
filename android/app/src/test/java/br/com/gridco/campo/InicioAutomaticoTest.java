package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.lang.reflect.InvocationTargetException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowLog;

/**
 * O Início automático do HyperOS e do MIUI (Task 14, o pedido do dono em 10/11/2026, depois da prova da 1.2.2 no
 * celular dele: sem ele o Android não deixou o app partir pelo aviso de app atualizado, e a coleta não voltou). O modo
 * vem de um leitor injetado: o AppOpsManager do Robolectric não sabe da operação 10008, que é do MIUI e não do Android.
 */
@RunWith(RobolectricTestRunner.class)
public class InicioAutomaticoTest {

    private Context app;

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        ShadowLog.clear();
    }

    @After
    public void fechar() {
        InicioAutomatico.leitorDeTeste = null;
    }

    private static InicioAutomatico.LeitorDoModo modo(int m) {
        return contexto -> m;
    }

    @Test
    public void foraDoXiaomiNaoSeAplicaENemSeLe() {
        InicioAutomatico.LeitorDoModo naoLe = contexto -> {
            fail("fora do Xiaomi o modo nem é lido");
            return 0;
        };
        for (String fabricante : new String[] {"samsung", "motorola", "Google", "unknown", "", null}) {
            assertNull(fabricante, InicioAutomatico.estado(fabricante, app, naoLe));
        }
    }

    @Test
    public void noXiaomiRedmiEPocoOModoPermitidoELigadoEOutroModoEDesligado() {
        for (String fabricante : new String[] {"Xiaomi", "XIAOMI", "xiaomi", "Redmi", "REDMI", "POCO", "Poco"}) {
            assertEquals(fabricante, Boolean.TRUE,
                InicioAutomatico.estado(fabricante, app, modo(AppOpsManager.MODE_ALLOWED)));
            for (int outro : new int[] {AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED,
                    AppOpsManager.MODE_DEFAULT, 5}) {
                assertEquals(fabricante + " no modo " + outro, Boolean.FALSE,
                    InicioAutomatico.estado(fabricante, app, modo(outro)));
            }
        }
    }

    @Test
    public void aLeituraQueFalhaDaNullNaoLancaEOLogNaoTemDado() {
        // O método escondido (o Android que o barra), o sistema que recusa, o que o MIUI lançar lá dentro: nada sabe do
        // estado, e a casca não abre nada nem diz que falta. O texto da exceção pode citar o uid e o pacote: não vai ao log.
        Exception[] falhas = {
            new NoSuchMethodException("checkOpNoThrow uid 10234"),
            new SecurityException("uid 10234 br.com.gridco.nexusfield"),
            new IllegalStateException("uid 10234"),
            new InvocationTargetException(new RuntimeException("uid 10234")),
            new IllegalAccessException("uid 10234"),
        };
        for (Exception falha : falhas) {
            ShadowLog.clear();
            assertNull(falha.getClass().getSimpleName(),
                InicioAutomatico.estado("Xiaomi", app, contexto -> {
                    throw falha;
                }));
            boolean logou = false;
            for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
                assertFalse("nada da exceção no log: " + item.msg, item.msg != null && item.msg.contains("10234"));
                if (item.msg != null && item.msg.contains(falha.getClass().getSimpleName())) logou = true;
            }
            assertTrue("o log diz que não leu, pelo nome da classe", logou);
        }
        // Sem contexto (nunca no app) também não lança.
        assertNull(InicioAutomatico.estado("Xiaomi", null, modo(AppOpsManager.MODE_ALLOWED)));
    }

    @Test
    public void oEstadoDoAparelhoUsaOFabricanteDoBuildEOLeitor() {
        InicioAutomatico.leitorDeTeste = modo(AppOpsManager.MODE_IGNORED);
        ShadowBuild.setManufacturer("samsung");
        assertNull(InicioAutomatico.estado(app));
        ShadowBuild.setManufacturer("Xiaomi");
        assertEquals(Boolean.FALSE, InicioAutomatico.estado(app));
        InicioAutomatico.leitorDeTeste = modo(AppOpsManager.MODE_ALLOWED);
        assertEquals(Boolean.TRUE, InicioAutomatico.estado(app));
    }

    @Test
    public void aTelaDoHyperOSEOsDetalhesDoApp() {
        Intent hyperos = InicioAutomatico.aTelaDoHyperOS();
        assertEquals("com.miui.securitycenter", hyperos.getComponent().getPackageName());
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", hyperos.getComponent().getClassName());
        Intent detalhes = InicioAutomatico.osDetalhesDoApp(app);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, detalhes.getAction());
        assertEquals("package:" + app.getPackageName(), detalhes.getDataString());
    }
}
