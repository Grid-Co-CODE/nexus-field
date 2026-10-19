package br.com.gridco.campo;

import static br.com.gridco.campo.ExpedienteTest.em;
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
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowNotificationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * O lado nativo da conversa das notificações com a página (www/casca/inicio.js, seções 8 e 9), com o Firebase e a página
 * falsos: o token com os avisos, o token que chega depois da carga (o onNewToken com a página viva, a volta ao app com os
 * avisos ligados de novo ou com outro token guardado), o Sair (a marca semConta, as notificações canceladas, a OS do
 * toque esquecida e o token apagado no Firebase), o registro que solta a marca e o osAberta que tira a emergência da barra.
 * Os tokens são fictícios e nunca vão ao log.
 */
@RunWith(RobolectricTestRunner.class)
public class PushNaCascaTest {

    private static final String TOKEN = "tokenFcmFicticio:APA91b_abc123";
    private static final String OUTRO = "tokenFcmFicticio:APA91b_novo456";

    /**
     * O Firebase falso: devolve o token que o teste mandar (null é o que não sai), conta os apagamentos (que dão certo ou
     * não, como o teste mandar: o sem rede falha) e anota a ordem das chamadas.
     */
    private static final class FirebaseFalso implements PushNaCasca.Firebase {
        String token = TOKEN;
        int pedidos;
        int apagados;
        boolean apagaComSucesso = true;
        final List<String> ordem = new ArrayList<>();
        Consumer<String> guardado; // com segurar, a resposta espera o teste

        boolean segurar;

        @Override
        public void pedirToken(Consumer<String> entregar) {
            pedidos++;
            ordem.add("pedir");
            if (segurar) guardado = entregar; else entregar.accept(token);
        }

        @Override
        public void apagarToken(Consumer<Boolean> terminou) {
            apagados++;
            ordem.add("apagar");
            terminou.accept(apagaComSucesso);
        }
    }

    /** A página falsa: guarda o que o nativo mandou; viva ou não. */
    private static final class PaginaFalsa implements PushNaCasca.Ponte {
        final List<JSONObject> recebidas = new ArrayList<>();
        boolean viva = true;

        @Override
        public boolean mandar(String json) {
            if (!viva) return false;
            try {
                recebidas.add(new JSONObject(json));
            } catch (JSONException e) {
                throw new AssertionError("o nativo mandou o que não é JSON", e);
            }
            return true;
        }
    }

    private Application app;
    private NotificationManager nm;
    private ShadowNotificationManager sombra;
    private final FirebaseFalso firebase = new FirebaseFalso();
    private final PaginaFalsa pagina = new PaginaFalsa();
    private final long hoje = em(2026, 10, 20, 10, 0);
    private final OsDoToque osDoToque = new OsDoToque(() -> hoje);
    private PushNaCasca push;

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        sombra = shadowOf(nm);
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        CanaisDeNotificacao.criar(app);
        push = new PushNaCasca(app, firebase, osDoToque, "1.1.0");
    }

    private SharedPreferences arquivoPush() {
        return app.getSharedPreferences("push", Context.MODE_PRIVATE);
    }

    private static JSONObject token(String token, boolean avisos) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "pushToken");
            o.put("token", token == null ? JSONObject.NULL : token);
            o.put("versao", "1.1.0");
            o.put("avisos", avisos);
            return o;
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private void recebeu(JSONObject... esperadas) {
        assertEquals("o que a página recebeu", esperadas.length, pagina.recebidas.size());
        for (int i = 0; i < esperadas.length; i++) {
            assertEquals("mensagem " + i, esperadas[i].toString(), pagina.recebidas.get(i).toString());
        }
    }

    private void bloquearAsEmergencias() {
        nm.createNotificationChannel(new NotificationChannel("emergencia", "Emergências", NotificationManager.IMPORTANCE_NONE));
    }

    private void semOTokenNoLog() {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse("o log levou o token: " + item.msg, item.msg.contains("APA91b"));
        }
    }

    // --- o token e os avisos -------------------------------------------------------------------------------------------

    @Test
    public void oPedidoDaPaginaRecebeOTokenAVersaoEOsAvisos() {
        push.pediuToken(pagina);
        recebeu(token(TOKEN, true));
        assertEquals("o token que o Firebase deu fica guardado, para comparar na volta ao app", TOKEN,
            MensagensDoFirebase.tokenGuardado(app));
        semOTokenNoLog();
    }

    @Test
    public void semOTokenDoFirebaseAPaginaRecebeNullENadaEGuardado() {
        firebase.token = null;
        push.pediuToken(pagina);
        recebeu(token(null, true));
        assertNull(MensagensDoFirebase.tokenGuardado(app));
    }

    @Test
    public void osAvisosDesligadosVaoComoFalse() {
        // A página só registra com os avisos ligados: senão o servidor contaria um aparelho que não mostra nada.
        assertTrue(PushNaCasca.avisosLigados(app));
        sombra.setNotificationsEnabled(false);
        assertFalse("as notificações do app desligadas nos ajustes", PushNaCasca.avisosLigados(app));
        push.pediuToken(pagina);
        recebeu(token(TOKEN, false));

        sombra.setNotificationsEnabled(true);
        bloquearAsEmergencias();
        assertFalse("o canal das emergências bloqueado", PushNaCasca.avisosLigados(app));
    }

    @Test
    public void semAPermissaoDeNotificacaoOsAvisosEstaoDesligados() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertFalse(PushNaCasca.avisosLigados(app));
    }

    @Test
    public void oCanalDoResumoBloqueadoNaoDesligaOsAvisos() {
        // O que conta é a emergência: o técnico que calou o resumo ainda quer o aviso urgente.
        nm.createNotificationChannel(new NotificationChannel("resumo", "Resumo do dia", NotificationManager.IMPORTANCE_NONE));
        assertTrue(PushNaCasca.avisosLigados(app));
    }

    @Test
    public void oTokenNovoVaiAPaginaVivaQueJaPediu() {
        // O primeiro getToken sem rede deu null; quando a rede volta, o Firebase dá o token pelo onNewToken.
        firebase.token = null;
        push.pediuToken(pagina);
        push.tokenNovo(OUTRO);
        recebeu(token(null, true), token(OUTRO, true));
    }

    @Test
    public void oTokenNovoSemPaginaQuePediuNaoVaiANadaENaoLanca() {
        push.tokenNovo(OUTRO);
        assertTrue(pagina.recebidas.isEmpty());
        push.tokenNovo(null);
    }

    @Test
    public void aVoltaAoAppComOsAvisosLigadosDeNovoMandaOTokenOutraVez() {
        // O técnico desligou as notificações, a página não registrou; ele liga nos ajustes e volta ao app.
        sombra.setNotificationsEnabled(false);
        push.pediuToken(pagina);
        sombra.setNotificationsEnabled(true);
        push.voltouAoApp();
        recebeu(token(TOKEN, false), token(TOKEN, true));
        // Outra volta, sem nada mudado: nada de novo.
        push.voltouAoApp();
        assertEquals(2, pagina.recebidas.size());
        assertEquals("o Firebase só foi chamado quando precisava", 2, firebase.pedidos);
    }

    @Test
    public void aVoltaAoAppSemNadaMudadoNaoMandaNada() {
        push.pediuToken(pagina);
        push.voltouAoApp();
        push.voltouAoApp();
        recebeu(token(TOKEN, true));
        assertEquals(1, firebase.pedidos);
    }

    @Test
    public void aVoltaAoAppComOsAvisosAindaDesligadosNaoMandaNada() {
        sombra.setNotificationsEnabled(false);
        push.pediuToken(pagina);
        push.voltouAoApp();
        recebeu(token(TOKEN, false));
    }

    @Test
    public void aVoltaAoAppComOutroTokenGuardadoMandaOToken() {
        // O onNewToken chegou com a página trocando de carga (a ponte velha não entregou): o token guardado não é o que a
        // página recebeu, e a volta ao app o manda.
        push.pediuToken(pagina);
        MensagensDoFirebase.guardarTokenNovo(app, OUTRO);
        firebase.token = OUTRO;
        push.voltouAoApp();
        recebeu(token(TOKEN, true), token(OUTRO, true));
        push.voltouAoApp();
        assertEquals("agora em dia: nada mais", 2, pagina.recebidas.size());
    }

    @Test
    public void aVoltaAoAppQueNaoConsegueOTokenNaoMandaNull() {
        sombra.setNotificationsEnabled(false);
        push.pediuToken(pagina);
        sombra.setNotificationsEnabled(true);
        firebase.token = null;
        push.voltouAoApp();
        recebeu(token(TOKEN, false));
    }

    @Test
    public void aVoltaAoAppSemPaginaQuePediuNaoFazNada() {
        MensagensDoFirebase.guardarTokenNovo(app, OUTRO);
        push.voltouAoApp();
        assertEquals(0, firebase.pedidos);
        assertTrue(pagina.recebidas.isEmpty());
    }

    @Test
    public void aRespostaQueChegaDepoisDeOutraPaginaPedirVaiAPaginaQuePediu() {
        // A volta ao app pediu o token, e outra carga pediu antes da resposta: cada resposta vai a quem pediu.
        sombra.setNotificationsEnabled(false);
        push.pediuToken(pagina);
        sombra.setNotificationsEnabled(true);
        firebase.segurar = true;
        push.voltouAoApp();
        Consumer<String> daVolta = firebase.guardado;
        PaginaFalsa nova = new PaginaFalsa();
        push.pediuToken(nova);
        firebase.guardado.accept(TOKEN);
        daVolta.accept(TOKEN);
        assertEquals("a página velha não recebe a resposta da volta: a nova já pediu", 1, pagina.recebidas.size());
        assertEquals(1, nova.recebidas.size());
    }

    // --- o Sair, o registro e o osAberta ------------------------------------------------------------------------------

    @Test
    public void oSairMarcaSemContaCancelaAsNotificacoesEsqueceAOsEApagaOToken() {
        SharedPreferences arquivo = arquivoPush();
        assertTrue(arquivo.edit().putBoolean("jaPediuNotificacao", true).putString("token", TOKEN).commit());
        assertTrue(Notificacoes.mostrar(app, Notificacoes.montar(emergencia("4521"), hoje, hoje), hoje));
        Map<String, String> resumo = new HashMap<>();
        resumo.put("tipo", "resumo");
        resumo.put("canal", "resumo");
        resumo.put("titulo", "Bom dia!");
        resumo.put("texto", "Hoje: 4 tarefas.");
        assertTrue(Notificacoes.mostrar(app, Notificacoes.montar(resumo, hoje, hoje), hoje));
        assertEquals(2, sombra.size());
        osDoToque.guardar("4521");

        push.saiu();

        assertTrue("a marca do Sair", MensagensDoFirebase.semConta(app));
        assertEquals("os avisos de quem saiu saem da barra", 0, sombra.size());
        assertNull("a OS de quem saiu não abre para quem entrar", osDoToque.aEntregar());
        assertEquals("o token do Firebase é apagado (o servidor o tira no próximo envio)", 1, firebase.apagados);
        assertTrue("sem clear: a marca da permissão fica", arquivo.getBoolean("jaPediuNotificacao", false));
    }

    @Test
    public void oApagamentoDoSairQueFalhouERepetidoNoProximoPedidoDoTokenAntesDeDevolveLo() {
        // O Sair sem rede: o esquecer da página não chega ao servidor e o deleteToken falha. Sem repetir o apagamento, o
        // token de quem saiu seguia valendo no servidor até alguém entrar. O próximo pedido do token (a página do login,
        // quando a rede volta) apaga de novo ANTES de pedir o token.
        firebase.apagaComSucesso = false;
        push.saiu();
        assertEquals(1, firebase.apagados);
        assertTrue("o apagamento que falhou fica marcado, no arquivo", MensagensDoFirebase.apagarTokenPendente(app));

        // A carga seguinte, num processo novo (a marca está no arquivo, não na memória), e a rede de volta.
        PushNaCasca depois = new PushNaCasca(app, firebase, osDoToque, "1.1.0");
        firebase.apagaComSucesso = true;
        firebase.ordem.clear();
        depois.pediuToken(pagina);
        assertEquals("apaga de novo, e só então pede o token", java.util.Arrays.asList("apagar", "pedir"), firebase.ordem);
        assertFalse("apagado, a marca sai", MensagensDoFirebase.apagarTokenPendente(app));
        recebeu(token(TOKEN, true));

        // Apagado, o pedido seguinte (ainda sem conta) não apaga mais.
        firebase.ordem.clear();
        depois.pediuToken(pagina);
        assertEquals(java.util.Arrays.asList("pedir"), firebase.ordem);
        assertEquals(2, firebase.apagados);
    }

    @Test
    public void oApagamentoQueFalhaDeNovoSegueMarcadoAteDarCerto() {
        firebase.apagaComSucesso = false;
        push.saiu();
        push.pediuToken(pagina);
        push.pediuToken(pagina);
        assertEquals("cada pedido sem conta tenta de novo", 3, firebase.apagados);
        assertTrue(MensagensDoFirebase.apagarTokenPendente(app));
        firebase.apagaComSucesso = true;
        push.pediuToken(pagina);
        assertFalse(MensagensDoFirebase.apagarTokenPendente(app));
        assertEquals(4, firebase.apagados);
    }

    @Test
    public void comUmaContaRegistradaOTokenNaoEApagado() {
        // O registro (o pushRegistrado) é o token de agora para a conta que entrou: apagá-lo tiraria os avisos dela.
        firebase.apagaComSucesso = false;
        push.saiu();
        push.registrado();
        assertFalse(MensagensDoFirebase.semConta(app));
        assertFalse("o registro tira a marca do apagamento", MensagensDoFirebase.apagarTokenPendente(app));
        push.pediuToken(pagina);
        assertEquals(1, firebase.apagados);
        // E sem a marca do Sair, mesmo com a do apagamento (um arquivo de antes), nada é apagado.
        assertTrue(MensagensDoFirebase.marcarApagarToken(app));
        push.pediuToken(pagina);
        assertEquals(1, firebase.apagados);
    }

    @Test
    public void oApagamentoQueDeuCertoNoSairNaoSeRepete() {
        push.saiu();
        assertFalse(MensagensDoFirebase.apagarTokenPendente(app));
        firebase.ordem.clear();
        push.pediuToken(pagina);
        assertEquals(java.util.Arrays.asList("pedir"), firebase.ordem);
        assertEquals(1, firebase.apagados);
    }

    @Test
    public void oRegistroSoltaAMarcaDoSair() {
        push.saiu();
        assertTrue(MensagensDoFirebase.semConta(app));
        push.registrado();
        assertFalse(MensagensDoFirebase.semConta(app));
        push.registrado(); // sem marca: nada muda
        assertFalse(MensagensDoFirebase.semConta(app));
    }

    private static Map<String, String> emergencia(String os) {
        Map<String, String> d = new HashMap<>();
        d.put("tipo", "emergencia");
        d.put("canal", "emergencia");
        d.put("titulo", "Emergência");
        d.put("texto", "Corretiva emergencial: OS " + os);
        d.put("os", os);
        return d;
    }

    @Test
    public void aOsAbertaSaiDaBarraEDeixaDeSerEntregue() {
        assertTrue(Notificacoes.mostrar(app, Notificacoes.montar(emergencia("4521"), hoje, hoje), hoje));
        assertTrue(Notificacoes.mostrar(app, Notificacoes.montar(emergencia("777"), hoje, hoje), hoje));
        osDoToque.guardar("4521");

        push.osAberta("4521");

        assertNull("a aberta sai da barra", sombra.getNotification(Notificacoes.idDaEmergencia("4521")));
        Notification outra = sombra.getNotification(Notificacoes.idDaEmergencia("777"));
        assertNotNull("a outra emergência fica", outra);
        assertNull("e não volta na próxima carga", osDoToque.aEntregar());

        // A página repete o osAberta (a mesma OS mostrada de novo na mesma carga, num toque repetido): nada quebra.
        push.osAberta("4521");
        assertEquals(1, sombra.size());
    }

    @Test
    public void aOsAbertaDeOutraNaoEsqueceAGuardada() {
        osDoToque.guardar("222");
        push.osAberta("111");
        assertEquals("222", osDoToque.aEntregar());
    }

    @Test
    public void oFirebaseDoAppSemOGoogleServicesNaoLanca() {
        // O build sem o google-services.json (o de PR, e o teste): o getInstance lança. O pedido do token dá null e o
        // Sair fica só com a marca.
        List<String> entregues = new ArrayList<>();
        PushNaCasca.FIREBASE_DO_APP.pedirToken(entregues::add);
        assertEquals(1, entregues.size());
        assertNull(entregues.get(0));
        List<Boolean> apagou = new ArrayList<>();
        PushNaCasca.FIREBASE_DO_APP.apagarToken(apagou::add);
        assertEquals("sem o Firebase não há o que apagar: conta como apagado", java.util.Collections.singletonList(true), apagou);
        PushNaCasca sem = new PushNaCasca(app, PushNaCasca.FIREBASE_DO_APP, osDoToque, "1.1.0");
        sem.saiu();
        assertTrue(MensagensDoFirebase.semConta(app));
        assertFalse("e nada a repetir no próximo pedido", MensagensDoFirebase.apagarTokenPendente(app));
    }
}
