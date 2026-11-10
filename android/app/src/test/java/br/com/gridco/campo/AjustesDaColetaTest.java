package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.Context;
import android.os.PowerManager;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Os ajustes da coleta que vão em cada lote (o "Celular pronto" do Piloto no Nexus, pedido do dono em 10/11/2026: "tem
 * como sabermos qual o técnico não aceitou tudo?").
 */
@RunWith(RobolectricTestRunner.class)
public class AjustesDaColetaTest {
    private static final String CONTA = "c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00";

    private Context app;
    private ChaveDaEquipe chave;

    @Before
    public void abrir() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().clear().commit();
        chave = new ChaveDaEquipe(app, new CifraFalsa());
        chave.guardar("chave-ficticia", CONTA);
    }

    private void liberarABateria(boolean liberada) {
        PowerManager energia = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        shadowOf(energia).setIgnoringBatteryOptimizations(app.getPackageName(), liberada);
    }

    @Test
    public void tudoLiberadoDizTudoSimEAVersaoDoTermo() throws Exception {
        chave.aceitarTermo();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, ServicoDaEquipe.O_TEMPO_TODO,
            Manifest.permission.POST_NOTIFICATIONS);
        liberarABateria(true);
        JSONObject a = AjustesDaColeta.ler(app, chave);
        assertEquals(ChaveDaEquipe.TERMO_VERSAO, a.getString("termo"));
        assertEquals("precisa", a.getString("localizacao"));
        assertTrue(a.getBoolean("tempoTodo"));
        assertTrue(a.getBoolean("bateriaSemRestricao"));
        assertTrue(a.getBoolean("notificacao"));
        assertEquals("só os cinco ajustes: nada da posição nem do aparelho", 5, a.length());
    }

    @Test
    public void oQueFaltaApareceComoNao() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            ServicoDaEquipe.O_TEMPO_TODO, Manifest.permission.POST_NOTIFICATIONS);
        liberarABateria(false);
        JSONObject a = AjustesDaColeta.ler(app, chave);
        assertTrue("sem o termo aceito, o termo vai nulo", a.isNull("termo"));
        assertEquals("aproximada", a.getString("localizacao"));
        assertFalse(a.getBoolean("tempoTodo"));
        assertFalse(a.getBoolean("bateriaSemRestricao"));
        assertFalse(a.getBoolean("notificacao"));

        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        assertEquals("sem", AjustesDaColeta.ler(app, chave).getString("localizacao"));
    }

    @Test
    public void oTermoDeUmaVersaoDeAntesVaiComAVersaoDele() throws Exception {
        // Quem aceitou a versão de antes e ainda não abriu o app depois da atualização: o Nexus mostra que falta o termo
        // novo, e não que nunca aceitou.
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().putString("termo_aceito", "3").commit();
        assertEquals("3", AjustesDaColeta.ler(app, chave).getString("termo"));
    }
}
