package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;

/**
 * A casca liga e desliga a coleta da equipe: o que o Nexus disse da chave do aparelho, o termo de uso, a localização
 * durante o uso, a janela das 6h às 22h, a tela à vista e o Sair. O serviço, a tela e o Nexus são falsos (as rotas do
 * Nexus ainda não estão no ar, e o Robolectric não tem o Google Play services); a chave, a fila e as permissões são as do
 * Android do Robolectric. O relógio é o de Brasília numa segunda-feira; as chaves são fictícias.
 */
@RunWith(RobolectricTestRunner.class)
public class EquipeNaCascaTest {

    private static final TimeZone BRASILIA = TimeZone.getTimeZone("America/Sao_Paulo");
    // Fictícias, no formato do contrato (32 bytes em base64url, 43 caracteres).
    private static final String CHAVE_A = "chaveFicticiaDaEquipe_AAAAAAAAAAAAAAAAAAAAA";
    private static final String CHAVE_B = "chaveFicticiaDaEquipe_BBBBBBBBBBBBBBBBBBBBB";
    // As contas de dois técnicos no mesmo aparelho, no formato que a página manda (o SHA-256 em hex minúsculo), fictícias.
    private static final String CONTA_A = "aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000";
    private static final String CONTA_B = "bbbb0000bbbb0000bbbb0000bbbb0000bbbb0000bbbb0000bbbb0000bbbb0000";
    private static final String PRECISA = Manifest.permission.ACCESS_FINE_LOCATION;
    private static final String APROXIMADA = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final String TEMPO_TODO = Manifest.permission.ACCESS_BACKGROUND_LOCATION;
    private static final long MINUTO = 60_000L;

    /** O ServicoDaEquipe de mentira: conta e guarda, na ordem, os pedidos, e está rodando quando o teste diz. */
    private static final class ServicoFalso implements EquipeNaCasca.Servico {
        int iniciados;
        int parados;
        boolean rodando;
        /** Os pedidos na ordem em que valeram: o último diz como a coleta ficou. */
        final List<String> chamadas = new ArrayList<>();
        /** Roda uma vez dentro do iniciar, antes de ele valer: o que outra thread faz no meio da decisão da casca. */
        Runnable noMeioDoIniciar;
        /** Roda a cada parar, quando ele vale. */
        Runnable aoParar;
        /** O parar que lança (a coleta que não parou agora): o resto do Sair não pode depender dele. */
        boolean lancarNoParar;
        /** A notificação fixa ficaria à vista (as notificações do Nexus Field e o canal da equipe ligados). */
        boolean notificacao = true;
        /** O último início do serviço pediu ao Android para religá-lo (o START_STICKY, só com o tempo todo, R15). */
        boolean religa;

        @Override
        public void iniciar() {
            Runnable noMeio = noMeioDoIniciar;
            noMeioDoIniciar = null;
            if (noMeio != null) noMeio.run();
            iniciados++;
            chamadas.add("iniciar");
        }

        @Override
        public void parar() {
            if (lancarNoParar) throw new IllegalStateException("o serviço não parou agora");
            parados++;
            rodando = false;
            chamadas.add("parar");
            if (aoParar != null) aoParar.run();
        }

        @Override
        public boolean rodando() {
            return rodando;
        }

        @Override
        public boolean notificacaoAVista() {
            return notificacao;
        }

        @Override
        public boolean religaSozinho() {
            return religa;
        }
    }

    /**
     * A tela de mentira: guarda o Li e aceito do termo, os pedidos ao Android e as janelas da bateria; com {@code saindo},
     * nada abre. Ela só tem o termo e as janelas do Android: nenhuma outra tela nossa existe para a casca abrir.
     */
    private static final class TelaFalsa implements EquipeNaCasca.Tela {
        int termos;
        int fechadas;
        /** A janela do próprio Android que pede a bateria sem restrição (R15). */
        int baterias;
        boolean saindo;
        /** O fechar que lança (o diálogo que a tela não tirou agora). */
        boolean lancarNoFechar;
        Runnable aceitarDoTermo;
        final List<List<String>> pedidos = new ArrayList<>();

        @Override
        public boolean pedirSemRestricaoDeBateria() {
            if (saindo) return false;
            baterias++;
            return true;
        }

        @Override
        public boolean mostrarTermo(Runnable aceitar) {
            if (saindo) return false;
            termos++;
            aceitarDoTermo = aceitar;
            return true;
        }

        @Override
        public boolean pedir(String[] permissoes) {
            if (saindo) return false;
            pedidos.add(Arrays.asList(permissoes));
            return true;
        }

        @Override
        public void fechar() {
            if (lancarNoFechar) throw new IllegalStateException("o diálogo não fechou agora");
            fechadas++;
        }
    }

    private Application app;
    private TimeZone fusoDeAntes;
    private long agora;
    private CifraFalsa cifra;
    private ChaveDaEquipe chave;
    private FilaDePontos fila;
    private ServicoFalso servico;
    private TelaFalsa tela;
    private final List<String[]> pedidosAoNexus = new ArrayList<>(); // {url, chave, corpo}
    private EnvioDePontos.Resposta resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":0,\"coleta\":true}");
    private IOException semRede;

    @Before
    public void preparar() {
        app = RuntimeEnvironment.getApplication();
        fusoDeAntes = TimeZone.getDefault();
        TimeZone.setDefault(BRASILIA);
        app.deleteDatabase(FilaDePontos.NOME_DO_BANCO);
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("equipe_casca", Context.MODE_PRIVATE).edit().clear().commit();
        cifra = new CifraFalsa();
        chave = new ChaveDaEquipe(app, cifra);
        fila = new FilaDePontos(app);
        servico = new ServicoFalso();
        tela = new TelaFalsa();
        agora = em(9, 9, 0);
        dar(PRECISA, APROXIMADA);
        ShadowLog.clear();
    }

    @After
    public void fechar() {
        TimeZone.setDefault(fusoDeAntes);
        app.deleteDatabase(FilaDePontos.NOME_DO_BANCO);
    }

    /** Novembro de 2026, na hora de Brasília (o dia 9 é uma segunda). */
    private static long em(int dia, int hora, int minuto) {
        Calendar c = Calendar.getInstance(BRASILIA);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, dia, hora, minuto, 0);
        return c.getTimeInMillis();
    }

    private void dar(String... permissoes) {
        shadowOf(app).grantPermissions(permissoes);
    }

    private void tirar(String... permissoes) {
        shadowOf(app).denyPermissions(permissoes);
    }

    private EnvioDePontos envio() {
        return new EnvioDePontos(chave, fila, (url, k, corpo) -> {
            pedidosAoNexus.add(new String[] {url, k, corpo});
            if (semRede != null) throw semRede;
            return resposta;
        }, () -> agora);
    }

    /** A casca com a thread do envio e a de UI rodando na hora, na thread do teste, e a tela à vista (o onResume). */
    private EquipeNaCasca casca() {
        EquipeNaCasca c = new EquipeNaCasca(app, chave, fila, envio(), servico, tela, Runnable::run, Runnable::run,
            () -> agora, "1.2.0");
        c.aTelaFicouAVista();
        return c;
    }

    /**
     * O aparelho que já tem a chave da conta A e cujo técnico já aceitou o termo e já respondeu ao pedido do tempo todo,
     * sem dá-lo (só a durante o uso, como na 1.2.0): o pedido não volta.
     */
    private void comChaveETermo(String k) {
        chave.guardar(k, CONTA_A);
        chave.aceitarTermo();
        chave.tempoTodoPedido();
    }

    /**
     * O que o técnico escolheu no pedido do Android (Durante o uso do app com a precisa, só com a aproximada, Não permitir;
     * ou nada, o Android que não mostra mais o pedido): as permissões do aparelho ficam assim, e a resposta volta à casca.
     */
    private void responder(EquipeNaCasca c, boolean precisa, boolean aproximada) {
        if (precisa) dar(PRECISA); else tirar(PRECISA);
        if (aproximada) dar(APROXIMADA); else tirar(APROXIMADA);
        Map<String, Boolean> r = new HashMap<>();
        r.put(PRECISA, precisa);
        r.put(APROXIMADA, aproximada);
        c.aposOPedido(r);
    }

    /** Logo depois do aceite: o pedido do Android, a precisa e a aproximada juntas. */
    private static List<String> aLocalizacao() {
        return Arrays.asList(PRECISA, APROXIMADA);
    }

    /** Logo depois da localização dada e da coleta ligada: o pedido do tempo todo, sozinho (R15). */
    private static List<String> oTempoTodo() {
        return Collections.singletonList(TEMPO_TODO);
    }

    /**
     * O que o técnico escolheu no pedido do tempo todo (Permitir o tempo todo, ou ficar com a durante o uso): a permissão
     * do aparelho fica assim, e a resposta volta à casca, como o launcher do plugin a entrega.
     */
    private void responderOTempoTodo(EquipeNaCasca c, boolean dado) {
        if (dado) dar(TEMPO_TODO); else tirar(TEMPO_TODO);
        c.aposOPedido(Collections.singletonMap(TEMPO_TODO, dado));
    }

    private SharedPreferences daCasca() {
        return app.getSharedPreferences("equipe_casca", Context.MODE_PRIVATE);
    }

    /** O Nexus já soube, em {@code quando}, que falta a permissão (a marca dos 30 minutos). */
    private void marcarSemPermissao(long quando) {
        daCasca().edit().putLong("sem_permissao_em", quando).commit();
    }

    private void encher(int n) {
        long t = System.currentTimeMillis();
        for (int i = 0; i < n; i++) fila.guardar(new PontoDaEquipe(t - (n - i), -15.6, -56.1, 8f, 0f, false));
    }

    // --- a regra ------------------------------------------------------------------------------------------------------

    @Test
    public void decidirSegueARegraDaColeta() {
        // (coleta, temChave, termoAceito, localizacao, notificacaoAVista, dentroDaJanela, rodando). A localização é a
        // permissão de localização precisa, durante o uso (R10): a o tempo todo saiu da regra.
        assertEquals("tudo em dia e parado", EquipeNaCasca.Acao.INICIAR,
            EquipeNaCasca.decidir(true, true, true, true, true, true, false));
        assertEquals("tudo em dia e rodando", EquipeNaCasca.Acao.NADA,
            EquipeNaCasca.decidir(true, true, true, true, true, true, true));
        for (boolean rodando : new boolean[] {false, true}) {
            assertEquals("o coleta:false do Nexus", EquipeNaCasca.Acao.PARAR,
                EquipeNaCasca.decidir(false, true, true, true, true, true, rodando));
            assertEquals("o coleta:false fora da janela", EquipeNaCasca.Acao.PARAR,
                EquipeNaCasca.decidir(false, false, true, true, true, false, rodando));
            assertEquals("sem a chave não há para onde mandar", EquipeNaCasca.Acao.PARAR,
                EquipeNaCasca.decidir(true, false, true, true, true, true, rodando));
            assertEquals("sem o termo aceito", EquipeNaCasca.Acao.MOSTRAR_TERMO,
                EquipeNaCasca.decidir(true, true, false, true, true, true, rodando));
            assertEquals("sem a permissão de localização", EquipeNaCasca.Acao.PEDIR_PERMISSAO,
                EquipeNaCasca.decidir(true, true, true, false, true, true, rodando));
            assertEquals("sem a notificação fixa à vista", EquipeNaCasca.Acao.SEM_A_NOTIFICACAO,
                EquipeNaCasca.decidir(true, true, true, true, false, true, rodando));
            assertEquals("a localização vem antes da notificação", EquipeNaCasca.Acao.PEDIR_PERMISSAO,
                EquipeNaCasca.decidir(true, true, true, false, false, true, rodando));
            assertEquals("e o termo antes das duas", EquipeNaCasca.Acao.MOSTRAR_TERMO,
                EquipeNaCasca.decidir(true, true, false, false, false, true, rodando));
        }
        assertEquals("fora da janela e parado", EquipeNaCasca.Acao.NADA,
            EquipeNaCasca.decidir(true, true, true, true, true, false, false));
        assertEquals("fora da janela e rodando", EquipeNaCasca.Acao.PARAR,
            EquipeNaCasca.decidir(true, true, true, true, true, false, true));
        // Fora da janela nada aparece ao técnico: nem o termo nem o pedido da localização (às 23h não há o que ligar).
        assertEquals(EquipeNaCasca.Acao.NADA, EquipeNaCasca.decidir(true, true, false, true, true, false, false));
        assertEquals(EquipeNaCasca.Acao.NADA, EquipeNaCasca.decidir(true, true, true, false, true, false, false));
        assertEquals(EquipeNaCasca.Acao.NADA, EquipeNaCasca.decidir(true, true, true, true, false, false, false));
        assertEquals(EquipeNaCasca.Acao.PARAR, EquipeNaCasca.decidir(true, true, false, false, true, false, true));
    }

    @Test
    public void semANotificacaoAVistaAColetaParaEONexusSabeComoSemAPermissao() throws Exception {
        // O caso: a notificação do Nexus Field negada no Android 13+ (ou o canal desligado). A casca mandava ligar, o serviço
        // não ligava, e o mapa mostrava o aparelho só como "sem sinal", sem dizer que falta algo do técnico.
        comChaveETermo(CHAVE_A);
        servico.notificacao = false;
        servico.rodando = true;
        EquipeNaCasca c = casca();
        c.reavaliar();
        assertEquals("a coleta ligada para", 1, servico.parados);
        assertEquals(0, servico.iniciados);
        assertEquals("nada aparece ao técnico", 0, tela.termos + tela.pedidos.size());
        assertEquals(1, pedidosAoNexus.size());
        assertEquals("sem", new JSONObject(pedidosAoNexus.get(0)[2]).optString("permissao"));
        // A marca dos 30 minutos é a mesma do "sem" da permissão.
        agora += MINUTO;
        c.reavaliar();
        assertEquals(1, pedidosAoNexus.size());
        // A notificação de volta: a coleta liga.
        servico.notificacao = true;
        c.reavaliar();
        assertEquals(1, servico.iniciados);
    }

    @Test
    public void tudoEmDiaNaVoltaAoAppLigaAColetaUmaVez() {
        comChaveETermo(CHAVE_A);
        EquipeNaCasca c = casca();
        c.reavaliar();
        assertEquals(1, servico.iniciados);
        servico.rodando = true;
        c.reavaliar();
        assertEquals("rodando, não liga outra", 1, servico.iniciados);
        assertEquals(0, servico.parados);
        assertEquals(0, tela.termos + tela.pedidos.size());
        assertTrue(pedidosAoNexus.isEmpty());
    }

    @Test
    public void comATelaForaDaVistaACascaNaoLigaAColeta() {
        // A decisão que roda na thread do envio depois de a tela sair da vista (a chave que o Nexus deu com o app já no
        // fundo, a volta ao app atrás de um envio pendurado): o serviço iniciado com o app no fundo não teria a localização
        // durante o uso (Android 11+), e do 12 em diante o Android recusa o primeiro plano. A próxima volta ao app liga.
        comChaveETermo(CHAVE_A);
        EquipeNaCasca c = casca();
        c.aTelaSaiuDaVista();
        c.reavaliar();
        c.receberChave(true, CHAVE_A, CONTA_A);
        assertEquals(0, servico.iniciados);
        assertEquals("nada mais muda: nem parar, nem o sem ao Nexus", 0, servico.parados);
        assertTrue(pedidosAoNexus.isEmpty());
        c.aTelaFicouAVista();
        c.reavaliar();
        assertEquals(1, servico.iniciados);
        // A coleta ligada segue com a tela fora da vista: é para isso que ela existe.
        servico.rodando = true;
        c.aTelaSaiuDaVista();
        c.reavaliar();
        assertEquals(0, servico.parados);
    }

    @Test
    public void as6hValemEAs22hNao() {
        comChaveETermo(CHAVE_A);
        EquipeNaCasca c = casca();
        agora = em(9, 5, 59);
        c.reavaliar();
        assertEquals(0, servico.iniciados);
        agora = em(9, 6, 0);
        c.reavaliar();
        assertEquals(1, servico.iniciados);
        servico.rodando = true;
        agora = em(9, 21, 59);
        c.reavaliar();
        assertEquals(0, servico.parados);
        agora = em(9, 22, 0);
        c.reavaliar();
        assertEquals(1, servico.parados);
    }

    // --- o que o Nexus disse ------------------------------------------------------------------------------------------

    @Test
    public void receberChaveFalseApagaAChaveAFilaEPara() {
        comChaveETermo(CHAVE_A);
        encher(3);
        servico.rodando = true;
        int[] filaNoParar = {-1};
        servico.aoParar = () -> filaNoParar[0] = fila.tamanho();
        casca().receberChave(false, null, CONTA_A);
        assertEquals("a coleta para primeiro, e só então a fila sai: o que falhar depois não a deixa ligada", 3,
            filaNoParar[0]);
        assertNull(chave.chave());
        assertFalse(chave.termoAceito());
        assertEquals(1, servico.parados);
        assertEquals("os pontos sem dono não sobem com a chave de outra conta", 0, fila.tamanho());
        assertEquals(0, servico.iniciados);
        assertTrue(pedidosAoNexus.isEmpty());
        assertEquals("um diálogo da equipe aberto sai", 1, tela.fechadas);
    }

    @Test
    public void aChaveNovaDaMesmaContaNaoPedeOTermoDeNovo() {
        // A chave refeita da mesma conta (o 401 do envio apagou a de antes, e a página pediu outra): o termo é da conta, e a
        // coleta segue sem tela nenhuma (R10).
        comChaveETermo(CHAVE_A);
        servico.rodando = true;
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_B, CONTA_A);
        assertEquals(CHAVE_B, chave.chave());
        assertTrue(chave.termoAceito());
        assertEquals(0, tela.termos + tela.pedidos.size());
        assertEquals("a coleta segue", 0, servico.parados);
        servico.rodando = false;
        c.reavaliar();
        assertEquals(1, servico.iniciados);
    }

    @Test
    public void receberAMesmaChaveNaoMostraOTermoDeNovo() {
        comChaveETermo(CHAVE_A);
        casca().receberChave(true, CHAVE_A, CONTA_A);
        assertTrue(chave.termoAceito());
        assertEquals(0, tela.termos);
        assertEquals(1, servico.iniciados);
    }

    @Test
    public void aChaveQueACifraNaoGuardaNaoLigaNada() {
        cifra.falharAoCifrar = true;
        casca().receberChave(true, CHAVE_A, CONTA_A);
        assertNull(chave.chave());
        assertEquals(0, servico.iniciados);
        assertEquals(0, tela.termos);
        assertEquals("sem chave, para", 1, servico.parados);
    }

    @Test
    public void aChaveQueOCofreNaoLeAgoraNaoMudaNada() {
        comChaveETermo(CHAVE_A);
        servico.rodando = true;
        cifra.keystoreFora = true;
        casca().reavaliar();
        assertEquals(0, servico.iniciados);
        assertEquals("a chave continua guardada: a coleta ligada não para por isso", 0, servico.parados);
        assertEquals(0, tela.termos + tela.pedidos.size());
        cifra.keystoreFora = false;
        assertEquals(CHAVE_A, chave.chave());
    }

    // --- o termo e a localização --------------------------------------------------------------------------------------

    @Test
    public void oTermoApareceUmaVezEOLiEAceitoPedeALocalizacaoAoAndroidSemOutraTela() {
        tirar(PRECISA, APROXIMADA);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        assertEquals(1, tela.termos);
        c.reavaliar(); // a volta ao app com o termo na frente: não abre outro por cima
        assertEquals(1, tela.termos);
        assertEquals(0, servico.iniciados);
        assertTrue("nada pedido antes do aceite", tela.pedidos.isEmpty());

        tela.aceitarDoTermo.run();
        assertTrue(chave.termoAceito());
        assertEquals("logo depois do aceite, o pedido do próprio Android, sem tela nossa antes",
            Collections.singletonList(aLocalizacao()), tela.pedidos);
        c.reavaliar();
        assertEquals("o pedido na frente: nem outro, nem o sem ao Nexus", 1, tela.pedidos.size());
        assertTrue(pedidosAoNexus.isEmpty());

        // O técnico escolheu Durante o uso do app, com a localização precisa.
        responder(c, true, true);
        assertEquals(1, servico.iniciados);
        assertEquals("nenhuma tela nossa além do termo", 1, tela.termos);
        assertTrue(pedidosAoNexus.isEmpty());
    }

    @Test
    public void comALocalizacaoJaDadaOLiEAceitoLigaSemPedidoDaLocalizacao() {
        // O caso comum: a localização já dada no Antes de começar (o App de Campo precisa dela), e só o termo aparece, e
        // depois dele só o pedido do tempo todo (R15).
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        assertEquals(Collections.singletonList(oTempoTodo()), tela.pedidos);
        assertEquals(1, servico.iniciados);
        servico.rodando = true;
        responderOTempoTodo(c, false);
        // A localização tirada mais tarde, na mesma tela: o pedido era só o de logo depois do aceite, e agora só o Nexus
        // fica sabendo.
        tirar(PRECISA, APROXIMADA);
        c.reavaliar();
        assertEquals("nenhum pedido fora do aceite", 1, tela.pedidos.size());
        assertEquals(1, servico.parados);
        assertEquals(1, pedidosAoNexus.size());
    }

    @Test
    public void semALocalizacaoUmSemSaiENaoSeRepeteAntesDe30Minutos() throws Exception {
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A);
        EquipeNaCasca c = casca();
        c.reavaliar();
        tela.aceitarDoTermo.run();
        // O técnico tocou em Não permitir no pedido do Android.
        responder(c, false, false);
        assertEquals(0, servico.iniciados);
        assertEquals(1, pedidosAoNexus.size());
        String[] pedido = pedidosAoNexus.get(0);
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_POSICOES, pedido[0]);
        assertEquals(CHAVE_A, pedido[1]);
        JSONObject corpo = new JSONObject(pedido[2]);
        assertEquals("sem", corpo.getString("permissao"));
        assertEquals(0, corpo.getJSONArray("pontos").length());
        assertEquals(chave.aparelho(), corpo.getString("aparelho"));

        // As voltas ao app em seguida: nenhum pedido nosso de novo, e o sem ao Nexus espera os 30 minutos.
        agora += 10 * MINUTO;
        c.reavaliar();
        agora += 19 * MINUTO;
        c.reavaliar();
        assertEquals(1, tela.pedidos.size());
        assertEquals(1, pedidosAoNexus.size());
        // A tela recriada (o renderer que morreu): o termo aceito não volta, e quem pede a localização a cada abertura é o
        // Antes de começar da MainActivity (dois pedidos empilhariam as telas); o sem não sai antes da hora.
        EquipeNaCasca recriada = casca();
        recriada.reavaliar();
        assertEquals(1, tela.termos);
        assertEquals(1, tela.pedidos.size());
        assertEquals(1, pedidosAoNexus.size());

        agora += 2 * MINUTO; // 31 minutos depois do primeiro
        c.reavaliar();
        assertEquals(2, pedidosAoNexus.size());
        assertEquals("sem", new JSONObject(pedidosAoNexus.get(1)[2]).getString("permissao"));
        assertEquals(0, servico.iniciados);
    }

    @Test
    public void negadaDeVezNenhumaTelaNossaAMaisEOSemVaiAoMapa() throws Exception {
        // O Android que não mostra mais o pedido (duas recusas): ele responde "não" na hora, sem tela. A casca não explica
        // nem leva aos ajustes: o sem vai ao mapa, e o guia diz como liberar nos ajustes.
        tirar(PRECISA, APROXIMADA);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        responder(c, false, false);
        assertEquals("só o termo e o pedido do Android", 1, tela.termos);
        assertEquals(1, tela.pedidos.size());
        assertEquals(1, pedidosAoNexus.size());
        assertEquals("sem", new JSONObject(pedidosAoNexus.get(0)[2]).getString("permissao"));
        assertEquals(0, servico.iniciados);
    }

    @Test
    public void soComAAproximadaAColetaNaoLigaEONexusSabe() throws Exception {
        // Do Android 12 em diante o técnico pode escolher a localização aproximada: ela erra por quilômetros, e a coleta não
        // liga com ela (vale a precisa).
        tirar(PRECISA, APROXIMADA);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        responder(c, false, true);
        assertEquals(0, servico.iniciados);
        assertEquals(1, pedidosAoNexus.size());
        assertEquals("sem", new JSONObject(pedidosAoNexus.get(0)[2]).getString("permissao"));
    }

    @Test
    public void oSemQueONexusRecusaContaComoRespondidoENaoParaNada() {
        // O 4xx (o Nexus recusou o lote vazio): mandar de novo a cada volta ao app não muda a resposta.
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A);
        resposta = new EnvioDePontos.Resposta(400, "");
        EquipeNaCasca c = casca();
        c.reavaliar();
        tela.aceitarDoTermo.run();
        responder(c, false, false);
        assertEquals(1, pedidosAoNexus.size());
        assertTrue("a marca dos 30 minutos", daCasca().contains("sem_permissao_em"));
        assertEquals("a chave fica", CHAVE_A, chave.chave());
        assertEquals(0, servico.parados);
        agora += MINUTO;
        c.reavaliar();
        assertEquals(1, pedidosAoNexus.size());
    }

    @Test
    public void oSemQueNaoChegouAoNexusTentaNaProximaVolta() {
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A);
        EquipeNaCasca c = casca();
        c.reavaliar();
        tela.aceitarDoTermo.run();
        semRede = new IOException("sem rede");
        responder(c, false, false);
        assertEquals(1, pedidosAoNexus.size());
        semRede = null;
        agora += MINUTO;
        c.reavaliar();
        assertEquals("sem rede não conta como avisado", 2, pedidosAoNexus.size());
        agora += MINUTO;
        c.reavaliar();
        assertEquals(2, pedidosAoNexus.size());
    }

    @Test
    public void oTermoDeOutraContaPedeALocalizacaoDeNovoNaMesmaTela() {
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A);
        EquipeNaCasca c = casca();
        c.reavaliar();
        tela.aceitarDoTermo.run();
        responder(c, false, false); // a conta de antes negou
        c.receberChave(true, CHAVE_B, CONTA_B); // outra conta entrou no mesmo aparelho
        assertEquals(2, tela.termos);
        tela.aceitarDoTermo.run();
        assertEquals("o Li e aceito de B pede a localização, também depois do não de A", 2, tela.pedidos.size());
    }

    @Test
    public void oAceiteDeOutraVersaoDoTermoMostraOTermoDeNovo() {
        // R11: só vale o aceite da versão de agora do texto (ChaveDaEquipe.TERMO_VERSAO).
        chave.guardar(CHAVE_A, CONTA_A);
        daEquipe().edit().putString("termo_aceito", "0").commit();
        oTermoVoltaEOAceiteGuardaAVersaoDeAgora();
    }

    @Test
    public void quemAceitouAVersao1VeOTermoDeAgoraDeNovo() {
        // R13 (10/11/2026): o texto passou a mostrar o ponto exato e o percurso, e quem aceitou o de antes não leu isso.
        chave.guardar(CHAVE_A, CONTA_A);
        daEquipe().edit().putString("termo_aceito", "1").commit();
        assertFalse("o aceite da versão 1 não vale mais", chave.termoAceito());
        oTermoVoltaEOAceiteGuardaAVersaoDeAgora();
        assertEquals("3", daEquipe().getString("termo_aceito", null));
        assertTrue(chave.termoAceito());
    }

    @Test
    public void quemAceitouAVersao2VeOTermoDaVersao3DeNovoEOAceiteDa3Vale() {
        // R15 (10/11/2026): o texto passou a pedir o Permitir o tempo todo e a bateria sem restrição, e a dizer que a coleta
        // volta sozinha no mesmo dia depois de o celular reiniciar ou o aplicativo ser atualizado; quem aceitou a versão 2
        // não leu isso.
        chave.guardar(CHAVE_A, CONTA_A);
        daEquipe().edit().putString("termo_aceito", "2").commit();
        assertFalse("o aceite da versão 2 não vale mais", chave.termoAceito());
        oTermoVoltaEOAceiteGuardaAVersaoDeAgora();
        assertEquals("3", daEquipe().getString("termo_aceito", null));
        assertTrue("o aceite da versão 3 vale", chave.termoAceito());
    }

    @Test
    public void aMarcaDoTermoDeAntesDaVersaoMostraOTermoDeNovo() {
        // A marca booleana da Task 8, só dos aparelhos de prova: quem a tem leu o termo curto, não o aviso inteiro.
        chave.guardar(CHAVE_A, CONTA_A);
        daEquipe().edit().putBoolean("termo_aceito", true).commit();
        oTermoVoltaEOAceiteGuardaAVersaoDeAgora();
    }

    private SharedPreferences daEquipe() {
        return app.getSharedPreferences("equipe", Context.MODE_PRIVATE);
    }

    // A coleta que estava ligada com o aceite que não vale mais para, o termo aparece, e o Li e aceito guarda a versão de
    // agora e liga a coleta; aceito, ele não aparece de novo.
    private void oTermoVoltaEOAceiteGuardaAVersaoDeAgora() {
        servico.rodando = true;
        EquipeNaCasca c = casca();
        c.reavaliar();
        assertEquals("a coleta para até o aceite da versão de agora", 1, servico.parados);
        assertEquals(1, tela.termos);
        assertEquals(0, servico.iniciados);
        tela.aceitarDoTermo.run();
        assertEquals(ChaveDaEquipe.TERMO_VERSAO, daEquipe().getString("termo_aceito", null));
        assertEquals(1, servico.iniciados);
        servico.rodando = true;
        c.reavaliar();
        assertEquals("aceito, o termo não aparece de novo", 1, tela.termos);
    }

    @Test
    public void comOTermoJaAceitoACascaNaoPedeALocalizacaoEAvisaONexus() {
        // Fora do aceite do termo quem pede a localização é o Antes de começar da MainActivity, a cada abertura: dois
        // pedidos de uma vez empilhariam as telas.
        tirar(PRECISA, APROXIMADA);
        comChaveETermo(CHAVE_A);
        casca().reavaliar();
        assertTrue(tela.pedidos.isEmpty());
        assertEquals(0, tela.termos);
        assertEquals(0, servico.iniciados);
        assertEquals(1, pedidosAoNexus.size());
    }

    @Test
    public void aLocalizacaoQueSumiuComAColetaLigadaParaEAvisaONexus() {
        comChaveETermo(CHAVE_A);
        servico.rodando = true;
        tirar(PRECISA);
        casca().reavaliar();
        assertEquals(1, servico.parados);
        assertEquals(0, tela.termos + tela.pedidos.size());
        assertEquals(1, pedidosAoNexus.size());
    }

    @Test
    public void oPedidoInterrompidoPerguntaDeNovoNaVoltaAoApp() {
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A);
        EquipeNaCasca c = casca();
        c.reavaliar();
        tela.aceitarDoTermo.run();
        c.aposOPedido(Collections.emptyMap()); // a tela saiu no meio: o técnico não respondeu
        assertTrue("sem resposta não é um não", pedidosAoNexus.isEmpty());
        c.reavaliar();
        assertEquals(2, tela.pedidos.size());
        assertEquals(1, tela.termos);
    }

    @Test
    public void oQueNaoApareceuNaTelaNaoDeixaACascaEsperandoParaSempre() {
        // A tela saindo (finishing) não mostra o termo nem pede ao Android: ninguém vai responder.
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A);
        EquipeNaCasca c = casca();
        tela.saindo = true;
        c.reavaliar();
        tela.saindo = false;
        c.reavaliar();
        assertEquals("a volta seguinte mostra o termo", 1, tela.termos);

        tela.saindo = true;
        tela.aceitarDoTermo.run(); // o pedido do Android que vem em seguida não abre
        assertTrue(chave.termoAceito());
        tela.saindo = false;
        c.reavaliar();
        assertEquals("a volta seguinte pede", 1, tela.pedidos.size());
        assertTrue("nem o sem ao Nexus: o pedido não tinha sido visto", pedidosAoNexus.isEmpty());
    }

    @Test
    public void foraDaJanelaNadaApareceEAColetaQueSobrouPara() {
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A); // sem o termo aceito
        agora = em(9, 23, 0);
        EquipeNaCasca c = casca();
        c.reavaliar();
        assertEquals(0, tela.termos + tela.pedidos.size());
        assertTrue(pedidosAoNexus.isEmpty());
        servico.rodando = true;
        c.reavaliar();
        assertEquals(1, servico.parados);
        // A primeira abertura do dia seguinte, depois das 6h, mostra o termo.
        agora = em(10, 6, 30);
        c.reavaliar();
        assertEquals(1, tela.termos);
    }

    // --- o Sair -------------------------------------------------------------------------------------------------------

    @Test
    public void sairRevogaApagaEParaMesmoSemRede() throws Exception {
        comChaveETermo(CHAVE_A);
        encher(2);
        servico.rodando = true;
        String aparelho = chave.aparelho();
        semRede = new IOException("sem rede");
        casca().sair();
        assertEquals("na hora e de novo na thread do envio, sem nunca ligar", Arrays.asList("parar", "parar"),
            servico.chamadas);
        assertNull(chave.chave());
        assertFalse(chave.termoAceito());
        assertEquals("os pontos de quem saiu não sobem com a chave de quem entrar", 0, fila.tamanho());
        assertEquals(1, pedidosAoNexus.size());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(aparelho, new JSONObject(pedidosAoNexus.get(0)[2]).getString("aparelho"));
        assertEquals("o aparelho fica: é a identidade dele, não o segredo", aparelho, chave.aparelho());
        assertEquals(1, tela.fechadas);
    }

    @Test
    public void oSairApagaAChaveEAFilaMesmoComAColetaEODialogoQueNaoParamAgora() {
        // O parar e o fechar do diálogo ficavam fora do que garante o Sair: um deles lançando, a chave de quem saiu e a fila
        // ficavam no aparelho, e a marca do Sair nunca saía (a conta seguinte não ligava a coleta).
        comChaveETermo(CHAVE_A);
        encher(2);
        servico.lancarNoParar = true;
        tela.lancarNoFechar = true;
        EquipeNaCasca c = casca();
        c.sair();
        assertNull("a chave de quem saiu não fica", chave.chave());
        assertEquals("nem a fila", 0, fila.tamanho());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);

        servico.lancarNoParar = false;
        tela.lancarNoFechar = false;
        c.receberChave(true, CHAVE_B, CONTA_B);
        tela.aceitarDoTermo.run();
        assertEquals("a marca do Sair saiu com a chave: a conta seguinte liga", 1, servico.iniciados);
    }

    @Test
    public void sairApagaAChaveMesmoComOCofreQueNaoALeAgora() {
        comChaveETermo(CHAVE_A);
        cifra.keystoreFora = true; // o revogar não consegue ler a chave para mandá-la ao Nexus
        casca().sair();
        assertEquals(Arrays.asList("parar", "parar"), servico.chamadas);
        cifra.keystoreFora = false;
        assertNull("a chave de quem saiu não fica no aparelho", chave.chave());
        assertTrue(pedidosAoNexus.isEmpty());
    }

    /** A casca com a thread do envio em fila: o que entra nela só roda no {@link #soltar}. */
    private EquipeNaCasca cascaComAThreadEmFila(Deque<Runnable> threadDoEnvio) {
        EquipeNaCasca c = new EquipeNaCasca(app, chave, fila, envio(), servico, tela, threadDoEnvio::add, Runnable::run,
            () -> agora, "1.2.0");
        c.aTelaFicouAVista();
        return c;
    }

    /** A thread do envio solta (o envio pendurado respondeu): roda o que estava nela e o que entrar no caminho. */
    private static void soltar(Deque<Runnable> threadDoEnvio) {
        while (!threadDoEnvio.isEmpty()) threadDoEnvio.poll().run();
    }

    @Test
    public void aVoltaAoAppQueEsperavaNaThreadNaoReligaAColetaDepoisDoSair() {
        // O caso: a coleta ligada e um envio pendurado na rede fraca segurando a thread do envio (15 s de conexão e
        // 15 s de leitura); o técnico volta ao app (o reavaliar entra na fila, atrás do envio) e toca em Sair.
        comChaveETermo(CHAVE_A);
        encher(3);
        servico.rodando = true;
        Deque<Runnable> threadDoEnvio = new ArrayDeque<>();
        EquipeNaCasca c = cascaComAThreadEmFila(threadDoEnvio);
        c.reavaliar();
        c.sair();
        assertEquals("a coleta para na hora", Collections.singletonList("parar"), servico.chamadas);

        soltar(threadDoEnvio);
        assertEquals("a volta ao app que esperava não liga a coleta de quem saiu", 0, servico.iniciados);
        assertEquals("parar", servico.chamadas.get(servico.chamadas.size() - 1));
        assertEquals("nem mostra o termo ou o pedido da localização", 0, tela.termos + tela.pedidos.size());
        assertNull(chave.chave());
        assertEquals("os pontos de quem saiu não ficam para a chave seguinte", 0, fila.tamanho());

        // A conta seguinte no aparelho: a chave nova, o termo e o Li e aceito ligam a coleta dela (a marca do Sair saiu
        // com a chave de quem saiu).
        c.receberChave(true, CHAVE_B, CONTA_B);
        soltar(threadDoEnvio);
        assertEquals(1, tela.termos);
        tela.aceitarDoTermo.run();
        soltar(threadDoEnvio);
        assertEquals(1, servico.iniciados);
    }

    @Test
    public void oSairNoMeioDaDecisaoDaThreadTerminaComAColetaParadaAntesDaRede() {
        // O Sair (na thread do espelho) que chega quando a decisão da thread do envio já passou pela marca do Sair: o
        // iniciar dela vale depois do parar do Sair, e é a tarefa do Sair, atrás dela na thread, que para de novo.
        comChaveETermo(CHAVE_A);
        encher(2);
        Deque<Runnable> threadDoEnvio = new ArrayDeque<>();
        EquipeNaCasca c = cascaComAThreadEmFila(threadDoEnvio);
        List<String> noParar = new ArrayList<>();
        servico.aoParar = () -> noParar.add("fila " + fila.tamanho() + ", Nexus " + pedidosAoNexus.size());
        servico.noMeioDoIniciar = c::sair;
        c.reavaliar();
        soltar(threadDoEnvio);
        assertEquals(Arrays.asList("parar", "iniciar", "parar"), servico.chamadas);
        assertEquals("a tarefa do Sair para antes de apagar a fila e antes da rede do revogar (30 s no sinal fraco)",
            Arrays.asList("fila 2, Nexus 0", "fila 2, Nexus 0"), noParar);
        assertNull(chave.chave());
        assertEquals(0, fila.tamanho());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
    }

    @Test
    public void oAparelhoPedidoDepoisDoSairJaVemSemAChave() throws Exception {
        comChaveETermo(CHAVE_A);
        Deque<Runnable> threadDoEnvio = new ArrayDeque<>();
        EquipeNaCasca c = cascaComAThreadEmFila(threadDoEnvio);
        c.sair();
        assertEquals("a coleta para na hora, sem esperar a rede", 1, servico.parados);
        List<String> respostas = new ArrayList<>();
        c.pedirAparelho(CONTA_A, json -> respostas.add(json));
        assertTrue("o disco e a rede só na thread do envio", respostas.isEmpty() && pedidosAoNexus.isEmpty());
        soltar(threadDoEnvio);
        assertEquals(1, respostas.size());
        JSONObject r = new JSONObject(respostas.get(0));
        assertEquals("equipeAparelho", r.getString("t"));
        assertEquals(chave.aparelho(), r.getString("aparelho"));
        assertEquals("1.2.0", r.getString("versao"));
        assertFalse("o Sair veio antes: a página pede outra chave", r.getBoolean("temChave"));
    }

    // --- a conta da chave (o aparelho dividido) -----------------------------------------------------------------------

    @Test
    public void aMesmaContaComAChaveDizQueTemENadaPara() throws Exception {
        comChaveETermo(CHAVE_A);
        encher(2);
        servico.rodando = true;
        marcarSemPermissao(agora);
        List<String> respostas = new ArrayList<>();
        casca().pedirAparelho(CONTA_A, json -> respostas.add(json));
        JSONObject r = new JSONObject(respostas.get(0));
        assertTrue("a chave desta conta: a página não pede outra", r.getBoolean("temChave"));
        assertFalse("a chave nunca vai à página", respostas.get(0).contains(CHAVE_A));
        assertFalse("nem a conta volta a ela", respostas.get(0).contains(CONTA_A));
        assertTrue("nada parado", servico.chamadas.isEmpty());
        assertEquals(2, fila.tamanho());
        assertTrue(pedidosAoNexus.isEmpty());
        assertEquals(CHAVE_A, chave.chave());
        assertTrue(chave.termoAceito());
        assertTrue(daCasca().contains("sem_permissao_em"));
        assertEquals(0, tela.fechadas);
    }

    @Test
    public void outraContaNoAparelhoFazOSairDaAnteriorAntesDaResposta() throws Exception {
        // O caso: A não tocou em Sair, e B entra no mesmo aparelho. Sem isto, B ficava com a chave, o aviso aceito e a
        // fila de A, e as posições de B iam ao Nexus em nome de A.
        comChaveETermo(CHAVE_A);
        encher(3);
        servico.rodando = true;
        marcarSemPermissao(agora);
        String aparelho = chave.aparelho();
        List<String> naHoraDaResposta = new ArrayList<>();
        List<String> respostas = new ArrayList<>();
        casca().pedirAparelho(CONTA_B, json -> {
            naHoraDaResposta.add("serviço " + servico.chamadas + ", fila " + fila.tamanho() + ", Nexus "
                + pedidosAoNexus.size() + ", chave " + chave.chave() + ", termo " + chave.termoAceito() + ", marca "
                + daCasca().contains("sem_permissao_em"));
            return respostas.add(json);
        });
        assertEquals("tudo de A já saiu quando a resposta vai",
            Collections.singletonList("serviço [parar], fila 0, Nexus 1, chave null, termo false, marca false"),
            naHoraDaResposta);
        assertFalse("a página de B pede a chave dela", new JSONObject(respostas.get(0)).getBoolean("temChave"));
        String[] revogar = pedidosAoNexus.get(0);
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, revogar[0]);
        assertEquals("o revogar vai com a chave velha", CHAVE_A, revogar[1]);
        assertEquals(aparelho, new JSONObject(revogar[2]).getString("aparelho"));
        assertNull(chave.conta());
        assertEquals("o aparelho fica: é a identidade dele, não da conta", aparelho, chave.aparelho());
        assertEquals(0, servico.iniciados);
        assertEquals("um diálogo da equipe de A aberto sai", 1, tela.fechadas);
    }

    @Test
    public void aChaveSemContaContaComoDeOutraConta() throws Exception {
        // A chave guardada antes de a conta existir (uma 1.2.0 de desenvolvimento): não se sabe de quem é.
        comChaveETermo(CHAVE_A);
        encher(1);
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().remove("conta").commit();
        List<String> respostas = new ArrayList<>();
        casca().pedirAparelho(CONTA_A, json -> respostas.add(json));
        assertFalse(new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertNull(chave.chave());
        assertEquals(0, fila.tamanho());
        assertEquals(Collections.singletonList("parar"), servico.chamadas);
    }

    @Test
    public void oCofreQueNaoLeAContaAgoraNaoMudaNada() throws Exception {
        comChaveETermo(CHAVE_A);
        encher(2);
        servico.rodando = true;
        cifra.keystoreFora = true;
        List<String> respostas = new ArrayList<>();
        casca().pedirAparelho(CONTA_B, json -> respostas.add(json));
        assertTrue("a chave continua lá, e pedir outra a revogaria à toa", new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertTrue(servico.chamadas.isEmpty());
        assertTrue(pedidosAoNexus.isEmpty());
        assertEquals(2, fila.tamanho());
        cifra.keystoreFora = false;
        assertEquals(CHAVE_A, chave.chave());
        assertEquals(CONTA_A, chave.conta());
    }

    @Test
    public void aChaveDeOutraContaQueAindaEstivesseGuardadaSaiAntesDaNova() throws Exception {
        // O equipeChave de B que chegasse com a chave de A ainda guardada (sem o pedido do aparelho antes): a fila de A
        // não sobe com a chave de B, e a de A é revogada.
        comChaveETermo(CHAVE_A);
        encher(2);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_B, CONTA_B);
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(0, fila.tamanho());
        assertEquals(CHAVE_B, chave.chave());
        assertEquals(CONTA_B, chave.conta());
        assertFalse(chave.termoAceito());
        assertEquals("B lê o termo dela", 1, tela.termos);
        assertEquals(0, servico.iniciados);
        // A mesma conta com a chave dela: nada mais é revogado.
        c.receberChave(true, CHAVE_B, CONTA_B);
        assertEquals(1, pedidosAoNexus.size());
    }

    @Test
    public void aContaNovaAvisaSemPermissaoSemEsperarOsTrintaMinutosDaAnterior() throws Exception {
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A);
        EquipeNaCasca c = casca();
        c.reavaliar();
        tela.aceitarDoTermo.run();
        responder(c, false, false);
        assertEquals("o sem permissão de A", 1, pedidosAoNexus.size());
        c.pedirAparelho(CONTA_B, json -> true); // B entrou sem o Sair de A
        assertEquals("o revogar da chave de A", 2, pedidosAoNexus.size());
        agora += MINUTO;
        c.receberChave(true, CHAVE_B, CONTA_B);
        tela.aceitarDoTermo.run();
        responder(c, false, false);
        assertEquals("o mapa sabe de B um minuto depois, não 30", 3, pedidosAoNexus.size());
        assertEquals(CHAVE_B, pedidosAoNexus.get(2)[1]);
        assertEquals("sem", new JSONObject(pedidosAoNexus.get(2)[2]).getString("permissao"));
    }

    // --- a fila que o 401 deixa (a conta guardada é a dona da fila) ------------------------------------------------------

    /** A chave de A vence com a fila cheia (A trabalhou o dia sem sinal): o primeiro envio leva o 401. */
    private void quatrocentoEUmComAFilaDeA(int pontos) {
        comChaveETermo(CHAVE_A);
        encher(pontos);
        resposta = new EnvioDePontos.Resposta(401, "");
        assertEquals(EnvioDePontos.Resultado.PARAR, envio().enviar());
        resposta = new EnvioDePontos.Resposta(200, "{\"aceitos\":0,\"coleta\":true}");
        pedidosAoNexus.clear();
        assertNull("o 401 apaga a chave", chave.chave());
        assertEquals("e a conta fica, dona da fila", CONTA_A, chave.conta());
        assertEquals(pontos, fila.tamanho());
    }

    @Test
    public void depoisDoQuatrocentoEUmOutraContaNaoHerdaAFilaDeA() throws Exception {
        // O caso: A trabalha o dia sem sinal, a chave vence e o primeiro envio leva 401; B abre o app no dia seguinte sem
        // o Sair de A. Sem isto, sem chave guardada não havia conta a comparar, e o dia de A ia ao Nexus em nome de B.
        quatrocentoEUmComAFilaDeA(3);
        marcarSemPermissao(agora);
        List<String> naHoraDaResposta = new ArrayList<>();
        List<String> respostas = new ArrayList<>();
        casca().pedirAparelho(CONTA_B, json -> {
            naHoraDaResposta.add("serviço " + servico.chamadas + ", fila " + fila.tamanho() + ", termo "
                + chave.termoAceito() + ", marca " + daCasca().contains("sem_permissao_em") + ", conta " + chave.conta());
            return respostas.add(json);
        });
        assertEquals("o que é de A saiu antes da resposta",
            Collections.singletonList("serviço [parar], fila 0, termo false, marca false, conta null"), naHoraDaResposta);
        assertFalse("a página de B pede a chave dela", new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertTrue("sem chave, nada a revogar", pedidosAoNexus.isEmpty());
        assertEquals("um diálogo da equipe de A aberto sai", 1, tela.fechadas);
    }

    @Test
    public void depoisDoQuatrocentoEUmAMesmaContaFicaComAFilaEASobeComAChaveNova() throws Exception {
        quatrocentoEUmComAFilaDeA(3);
        EquipeNaCasca c = casca();
        List<String> respostas = new ArrayList<>();
        c.pedirAparelho(CONTA_A, respostas::add);
        assertFalse("sem a chave, a página pede outra", new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertEquals("o dia de A fica com A", 3, fila.tamanho());
        assertTrue(servico.chamadas.isEmpty());
        assertTrue(pedidosAoNexus.isEmpty());

        c.receberChave(true, CHAVE_B, CONTA_A);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        String[] lote = pedidosAoNexus.get(0);
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_POSICOES, lote[0]);
        assertEquals("com a chave nova", CHAVE_B, lote[1]);
        assertEquals(3, new JSONObject(lote[2]).getJSONArray("pontos").length());
        assertEquals(0, fila.tamanho());
    }

    @Test
    public void depoisDoQuatrocentoEUmAChaveDeOutraContaNaoLevaAFilaDeA() throws Exception {
        // O equipeChave de B que chegasse sem o pedido do aparelho antes: a fila de A sai antes de a chave de B entrar.
        quatrocentoEUmComAFilaDeA(3);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_B, CONTA_B);
        assertEquals(0, fila.tamanho());
        assertTrue("sem chave de A, nada a revogar", pedidosAoNexus.isEmpty());
        assertEquals(CHAVE_B, chave.chave());
        assertEquals(CONTA_B, chave.conta());
        encher(1);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals("só o ponto de B", 1, new JSONObject(pedidosAoNexus.get(0)[2]).getJSONArray("pontos").length());
    }

    @Test
    public void aFilaSemDonaSaiNoPedidoDoAparelho() throws Exception {
        // Os pontos sem conta guardada (o ponto que chegou no meio do Sair, a fila de antes da conta): não se sabe de quem
        // são, e não sobem com a chave de quem pedir.
        encher(2);
        assertNull(chave.conta());
        List<String> respostas = new ArrayList<>();
        casca().pedirAparelho(CONTA_A, respostas::add);
        assertEquals(0, fila.tamanho());
        assertFalse(new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertTrue(pedidosAoNexus.isEmpty());
    }

    // --- a janela da bateria sem restrição (R15) ----------------------------------------------------------------------

    private void liberarDaBateria(boolean liberado) {
        PowerManager energia = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        shadowOf(energia).setIgnoringBatteryOptimizations(app.getPackageName(), liberado);
    }

    @Test
    public void aJanelaDaBateriaAbreUmaVezLogoDepoisDoTermoDaLocalizacaoEDoTempoTodo() {
        // O pedido do dono em 10/11/2026 ("precisamos só ser mais independente"): sem a otimização de bateria, o HyperOS
        // fecha menos o app e o Android 12+ deixa religar a coleta com o app no fundo.
        tirar(PRECISA, APROXIMADA);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        assertEquals("com o termo na frente, nada", 0, tela.baterias);
        tela.aceitarDoTermo.run();
        assertEquals("com o pedido da localização na frente, nada", 0, tela.baterias);
        responder(c, true, true);
        assertEquals(1, servico.iniciados);
        assertEquals("com o pedido do tempo todo na frente, nada", 0, tela.baterias);
        servico.rodando = true;
        responderOTempoTodo(c, false);
        assertEquals("logo depois da resposta do tempo todo, a janela do próprio Android", 1, tela.baterias);
        assertEquals("nenhuma tela nossa antes", 1, tela.termos);

        // A volta da janela, com a recusa: a coleta segue igual, e a janela não volta.
        servico.rodando = true;
        c.reavaliar();
        assertEquals(1, tela.baterias);
        partidaNova();
        casca().reavaliar();
        assertEquals("nem na partida nova", 1, tela.baterias);
        assertEquals(0, servico.parados);
        assertEquals(1, servico.iniciados);
    }

    @Test
    public void comOAppJaLiberadoAJanelaDaBateriaNaoAbre() {
        liberarDaBateria(true);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        assertEquals(1, servico.iniciados);
        servico.rodando = true;
        responderOTempoTodo(c, false);
        assertEquals(0, tela.baterias);
        // A liberação tirada depois, nos ajustes: a janela não aparece fora do fluxo do termo.
        liberarDaBateria(false);
        servico.rodando = true;
        c.reavaliar();
        assertEquals(0, tela.baterias);
    }

    @Test
    public void recusadaAJanelaSoVoltaComAProximaContaOuOProximoTermo() {
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        servico.rodando = true;
        responderOTempoTodo(c, false);
        assertEquals(1, tela.baterias);
        c.reavaliar();
        c.receberChave(true, CHAVE_B, CONTA_A); // a chave nova da mesma conta, a do 401
        assertEquals("a mesma conta e o mesmo termo", 1, tela.baterias);

        // A próxima conta: B entra sem o Sair de A, e a janela vem depois do aceite do termo de B (e do tempo todo).
        c.pedirAparelho(CONTA_B, json -> true);
        c.receberChave(true, CHAVE_B, CONTA_B);
        assertEquals(1, tela.baterias);
        tela.aceitarDoTermo.run();
        servico.rodando = true;
        responderOTempoTodo(c, false);
        assertEquals(2, tela.baterias);

        // O próximo termo: o texto que muda pede o aceite de novo, e a janela vem depois dele.
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().putString("termo_aceito", "1").commit();
        c.reavaliar();
        assertEquals(2, tela.baterias);
        tela.aceitarDoTermo.run();
        servico.rodando = true;
        responderOTempoTodo(c, false);
        assertEquals(3, tela.baterias);
    }

    @Test
    public void semALocalizacaoAJanelaEsperaALocalizacaoDadaEATelaAVista() {
        tirar(PRECISA, APROXIMADA);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        responder(c, false, false);
        assertEquals("negada, nada", 0, tela.baterias);
        c.reavaliar();
        assertEquals(0, tela.baterias);

        // A localização dada mais tarde (o Antes de começar da próxima abertura): a janela vem logo depois, com a tela à
        // vista.
        dar(PRECISA, APROXIMADA);
        c.aTelaSaiuDaVista();
        c.reavaliar();
        assertEquals("a tela fora da vista: nada", 0, tela.baterias);
        c.aTelaFicouAVista();
        c.reavaliar();
        assertEquals(1, servico.iniciados);
        assertEquals("antes, o pedido do tempo todo", Collections.singletonList(oTempoTodo()), tela.pedidos.subList(1, 2));
        servico.rodando = true;
        responderOTempoTodo(c, false);
        assertEquals(1, tela.baterias);
    }

    @Test
    public void aJanelaQueNaoAbriuComATelaSaindoAbreNaProximaVolta() {
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.saindo = true;
        tela.aceitarDoTermo.run();
        assertEquals(0, tela.baterias);
        tela.saindo = false;
        servico.rodando = true;
        // A decisão que roda com o app no fundo (a chave que chega depois de o técnico sair do app): nada abre, e a janela
        // segue por pedir (o Android 10+ nem deixa uma activity abrir do fundo).
        c.aTelaSaiuDaVista();
        c.reavaliar();
        assertEquals(0, tela.baterias);
        c.aTelaFicouAVista();
        c.reavaliar();
        assertEquals("o pedido do tempo todo, que também não abriu, vem antes", Collections.singletonList(oTempoTodo()),
            tela.pedidos);
        responderOTempoTodo(c, false);
        assertEquals(1, tela.baterias);
        c.reavaliar();
        assertEquals(1, tela.baterias);
    }

    @Test
    public void aTelaDeVerdadeAbreAJanelaDaBateriaDoProprioAndroid() {
        AppCompatActivity activity = activity();
        EquipeNaCasca.TelaDaActivity t = new EquipeNaCasca.TelaDaActivity(activity, p -> fail("nada de permissão aqui"));
        assertTrue(t.pedirSemRestricaoDeBateria());
        Intent aberta = shadowOf(activity).getNextStartedActivity();
        assertNotNull(aberta);
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, aberta.getAction());
        assertEquals("package:" + app.getPackageName(), aberta.getDataString());
        assertNull("nenhum diálogo nosso antes", ShadowDialog.getLatestDialog());

        // O Android sem a janela (o fabricante que a tirou): nada quebra, e nada fica para tentar de novo.
        shadowOf(app).checkActivities(true);
        assertTrue(t.pedirSemRestricaoDeBateria());
        controle.pause().stop().destroy();
        assertFalse("a tela saindo: a próxima volta ao app tenta", t.pedirSemRestricaoDeBateria());
    }

    @Test
    public void oManifestoPedeAPermissaoDaJanelaDaBateria() throws Exception {
        List<String> pedidas = Arrays.asList(app.getPackageManager()
            .getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions);
        assertTrue(pedidas.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS));
    }

    // --- o tempo todo (R15) -------------------------------------------------------------------------------------------

    @Test
    public void oTempoTodoVemLogoDepoisDaLocalizacaoEAJanelaDaBateriaDepoisDele() {
        // R15, o pedido do dono em 10/11/2026 ("precisamos só ser mais independente... sem esses erros de ficar parando
        // coleta nem nada"): com o tempo todo, a coleta do dia começado volta sozinha. A ordem é a localização, o tempo
        // todo e a bateria, e nenhuma tela nossa além do termo.
        tirar(PRECISA, APROXIMADA);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        assertEquals(Collections.singletonList(aLocalizacao()), tela.pedidos);
        responder(c, true, true);
        assertEquals("a coleta liga com a durante o uso, antes do tempo todo", 1, servico.iniciados);
        assertEquals("logo depois, o pedido do tempo todo, sozinho", Arrays.asList(aLocalizacao(), oTempoTodo()),
            tela.pedidos);
        assertEquals("a janela da bateria espera a resposta do tempo todo", 0, tela.baterias);
        servico.rodando = true;
        c.reavaliar(); // a volta ao app com o pedido na frente (do Android 11 em diante, os ajustes): nada por cima
        assertEquals(2, tela.pedidos.size());
        assertEquals(0, tela.baterias);

        responderOTempoTodo(c, true);
        assertEquals("depois do tempo todo, a janela da bateria", 1, tela.baterias);
        assertEquals("nenhuma tela nossa além do termo", 1, tela.termos);
        assertEquals(2, tela.pedidos.size());
        assertTrue("o mapa não sabe do tempo todo", pedidosAoNexus.isEmpty());
    }

    @Test
    public void comALocalizacaoJaDadaOTempoTodoVemLogoDepoisDoLiEAceito() {
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        assertEquals(1, servico.iniciados);
        assertEquals(Collections.singletonList(oTempoTodo()), tela.pedidos);
        assertEquals(0, tela.baterias);
        servico.rodando = true;
        responderOTempoTodo(c, false);
        assertEquals("recusado, a janela da bateria vem igual", 1, tela.baterias);
        assertEquals("e a coleta segue só com a durante o uso, como na 1.2.0", 0, servico.parados);
        assertEquals(1, servico.iniciados);
        assertTrue("o mapa não sabe do tempo todo: nada de sem ao Nexus", pedidosAoNexus.isEmpty());
    }

    @Test
    public void recusadoOTempoTodoSoVoltaComAProximaContaOuOProximoTermo() {
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        servico.rodando = true;
        responderOTempoTodo(c, false);
        c.reavaliar();
        c.receberChave(true, CHAVE_B, CONTA_A); // a chave nova da mesma conta, a do 401
        partidaNova();
        EquipeNaCasca depois = casca();
        depois.aTelaAbriu();
        depois.reavaliar();
        assertEquals("nem a mesma conta, nem a partida nova pedem de novo", 1, tela.pedidos.size());
        assertEquals(0, servico.parados);
        assertTrue(pedidosAoNexus.isEmpty());

        // A próxima conta: B entra sem o Sair de A, e o pedido vem depois do aceite do termo de B.
        depois.pedirAparelho(CONTA_B, json -> true);
        depois.receberChave(true, CHAVE_B, CONTA_B);
        assertEquals(1, tela.pedidos.size());
        tela.aceitarDoTermo.run();
        assertEquals(Arrays.asList(oTempoTodo(), oTempoTodo()), tela.pedidos);
        servico.rodando = true;
        responderOTempoTodo(depois, false);

        // O próximo termo: o texto que muda pede o aceite de novo, e o pedido vem depois dele.
        daEquipe().edit().putString("termo_aceito", "1").commit();
        depois.reavaliar();
        assertEquals(2, tela.pedidos.size());
        tela.aceitarDoTermo.run();
        assertEquals(3, tela.pedidos.size());
        assertEquals(oTempoTodo(), tela.pedidos.get(2));
    }

    @Test
    public void comOTempoTodoJaDadoNadaEPedidoEAJanelaDaBateriaVemLogo() {
        dar(TEMPO_TODO);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        assertTrue(tela.pedidos.isEmpty());
        assertEquals(1, tela.baterias);
        // Tirado depois nos ajustes: o pedido não volta fora do fluxo do termo, e a coleta segue com a durante o uso.
        tirar(TEMPO_TODO);
        servico.rodando = true;
        c.reavaliar();
        assertTrue(tela.pedidos.isEmpty());
        assertEquals(0, servico.parados);
    }

    @Test
    public void oPedidoDoTempoTodoQueNaoApareceuOuFoiInterrompidoPedeNaProximaVolta() {
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.saindo = true;
        tela.aceitarDoTermo.run(); // a coleta liga, e o pedido do tempo todo não abre (a tela saindo)
        assertEquals(1, servico.iniciados);
        assertTrue(tela.pedidos.isEmpty());
        assertEquals(0, tela.baterias);
        tela.saindo = false;
        servico.rodando = true;
        c.aTelaSaiuDaVista();
        c.reavaliar();
        assertTrue("com o app no fundo, nada", tela.pedidos.isEmpty());
        c.aTelaFicouAVista();
        c.reavaliar();
        assertEquals(Collections.singletonList(oTempoTodo()), tela.pedidos);

        c.aposOPedido(Collections.emptyMap()); // a tela saiu no meio: o técnico não respondeu
        assertEquals("sem resposta não é um não, e a janela da bateria não passa na frente", 0, tela.baterias);
        c.reavaliar();
        assertEquals(Arrays.asList(oTempoTodo(), oTempoTodo()), tela.pedidos);
        responderOTempoTodo(c, false);
        assertEquals(1, tela.baterias);
        c.reavaliar();
        assertEquals("respondido, não volta", 2, tela.pedidos.size());
        assertTrue("nem o pedido da localização vem por engano", tela.pedidos.stream().noneMatch(aLocalizacao()::equals));
    }

    @Test
    public void aRespostaDoTempoTodoQueChegaNumaTelaNovaVale() {
        // Do Android 11 em diante o pedido leva aos ajustes do app, e o processo pode morrer lá: a resposta chega à tela
        // nova (o launcher do plugin registrado de novo), que não sabe do pedido, e vale igual.
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        assertEquals(1, tela.pedidos.size());
        partidaNova();
        EquipeNaCasca nova = casca();
        responderOTempoTodo(nova, false);
        servico.rodando = true;
        nova.reavaliar();
        assertEquals("a resposta conta: nada é pedido de novo", 1, tela.pedidos.size());
        assertEquals("e a janela da bateria vem", 1, tela.baterias);
    }

    @Test
    public void oTempoTodoDadoComAColetaLigadaIniciaOServicoDeNovoParaEleVoltarSozinho() {
        // O serviço ligado antes do tempo todo pediu ao Android para não ser religado (o START_NOT_STICKY do início dele):
        // dado o tempo todo, a casca o inicia de novo, com a tela à vista, e o início novo pede o religamento. Sem isso, a
        // coleta do primeiro dia não voltaria sozinha.
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        assertEquals(1, servico.iniciados);
        servico.rodando = true;
        c.aTelaSaiuDaVista(); // os ajustes do app por cima (Android 11+)
        responderOTempoTodo(c, true);
        assertEquals("com o app no fundo, não", 1, servico.iniciados);
        c.aTelaFicouAVista();
        c.reavaliar();
        assertEquals("a volta ao app inicia o serviço de novo", 2, servico.iniciados);
        servico.religa = true; // o início novo pediu o religamento
        c.reavaliar();
        assertEquals("e uma vez só", 2, servico.iniciados);
        assertEquals(0, servico.parados);

        // O tempo todo tirado nos ajustes com o processo vivo: a casca não insiste nem para a coleta.
        servico.religa = false;
        tirar(TEMPO_TODO);
        c.reavaliar();
        assertEquals(2, servico.iniciados);
        assertEquals(0, servico.parados);
        assertEquals(1, tela.pedidos.size());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.N)
    public void noAndroid7ALocalizacaoDadaJaValeComOAppNoFundoENadaEPedido() {
        // Do Android 9 para trás não existe o tempo todo à parte: a localização dada vale também com o app no fundo.
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        assertEquals(1, servico.iniciados);
        assertTrue(tela.pedidos.isEmpty());
        assertEquals(1, tela.baterias);
    }

    // --- nada sai em nome de uma conta que nenhuma página conferiu ----------------------------------------------------

    /**
     * O processo novo (a atualização, o Forçar parada, o celular reiniciado, o Android que matou o app): a chave, a conta,
     * a conta conferida (R14) e a fila no disco, e nada da memória da partida anterior. A casca e o envio montados depois
     * desta chamada usam a chave nova.
     */
    private void partidaNova() {
        chave = new ChaveDaEquipe(app, cifra);
    }

    /** O processo novo com a chave que a 1.2.0 guardou, de antes da conta conferida no disco. */
    private void partidaNovaSemAMarca() {
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().remove("conta_conferida").commit();
        partidaNova();
    }

    private boolean temAMarca() {
        return app.getSharedPreferences("equipe", Context.MODE_PRIVATE).contains("conta_conferida");
    }

    @Test
    public void naPartidaAFrioAColetaLigaEOEnvioSaiSemEsperarAPagina() throws Exception {
        // R14, o caso de 10/11/2026: a atualização das 10:23 fechou o app, a coleta religou e coletou a cada 2 min, e só
        // enviou às 10:50, quando o dono abriu o app (com a tela apagada a página não roda, e só ela conferia a conta).
        comChaveETermo(CHAVE_A);
        partidaNova();
        EquipeNaCasca c = casca();
        c.aTelaAbriu(); // a tela que nasce, sem a página carregar (a tela apagada)
        c.reavaliar();
        assertEquals("a coleta liga com a chave guardada (a abertura sem sinal também coleta)", 1, servico.iniciados);
        encher(3);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(1, pedidosAoNexus.size());
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(3, new JSONObject(pedidosAoNexus.get(0)[2]).getJSONArray("pontos").length());
        assertEquals(0, fila.tamanho());
    }

    @Test
    public void naPartidaAFrioSemAMarcaNadaSaiAntesDeAPaginaConferirAConta() {
        // A chave da 1.2.0, de antes da marca: o onResume liga a coleta, e o envio espera a página conferir, como antes.
        comChaveETermo(CHAVE_A);
        partidaNovaSemAMarca();
        EquipeNaCasca c = casca();
        c.reavaliar();
        assertEquals("a coleta liga com a chave guardada (a abertura sem sinal também coleta)", 1, servico.iniciados);
        EnvioDePontos envio = envio();
        encher(3);
        assertEquals(EnvioDePontos.Resultado.NADA, envio.enviar());
        encher(2);
        assertEquals(EnvioDePontos.Resultado.NADA, envio.enviar());
        assertTrue("nada foi ao Nexus", pedidosAoNexus.isEmpty());
        assertEquals("a fila cresce", 5, fila.tamanho());
        assertEquals("a chave fica", CHAVE_A, chave.chave());
    }

    @Test
    public void oPedidoDoAparelhoDaMesmaContaConfereEOEnvioSobeAFila() throws Exception {
        comChaveETermo(CHAVE_A);
        encher(3);
        partidaNovaSemAMarca();
        EquipeNaCasca c = casca();
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        List<String> respostas = new ArrayList<>();
        c.pedirAparelho(CONTA_A, respostas::add);
        assertTrue(new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertTrue(chave.conferida());
        assertTrue("a conferência vai ao disco, para a próxima partida (R14)", temAMarca());
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(1, pedidosAoNexus.size());
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(3, new JSONObject(pedidosAoNexus.get(0)[2]).getJSONArray("pontos").length());
        assertEquals(0, fila.tamanho());
    }

    @Test
    public void naPartidaAFrioOutraContaNaPaginaFazATrocaENadaMaisSaiEmNomeDaAnterior() throws Exception {
        // O celular de A na mão de B, que abriu o app sem o Sair de A. O custo aceito (R14): entre a partida e a carga da
        // página, os pontos vão em nome de A. A página de B faz a troca da R5, e dali em diante nada sai em nome de A.
        comChaveETermo(CHAVE_A);
        partidaNova();
        EquipeNaCasca c = casca();
        c.aTelaAbriu();
        c.reavaliar();
        encher(2);
        assertEquals("antes da página, em nome de A (o custo aceito)", EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        encher(3);
        List<String> respostas = new ArrayList<>();
        c.pedirAparelho(CONTA_B, respostas::add);
        assertFalse("a página de B registra a conta dela", new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertEquals("o lote e o revogar da chave de A", 2, pedidosAoNexus.size());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(1)[0]);
        assertEquals(CHAVE_A, pedidosAoNexus.get(1)[1]);
        assertEquals("os pontos de depois da partida saíram com a fila de A", 0, fila.tamanho());
        assertNull(chave.chave());
        assertFalse(chave.conferida());
        assertFalse(temAMarca());
        assertEquals("parar", servico.chamadas.get(servico.chamadas.size() - 1));

        // Nada mais sai em nome de A: nem nesta partida, nem na próxima, e o que B coletar sobe só com a chave de B.
        encher(1);
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        assertEquals(EnvioDePontos.Resultado.NADA, envio().avisarSemPermissao());
        partidaNova();
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        assertEquals(2, pedidosAoNexus.size());
        c = casca();
        c.receberChave(true, CHAVE_B, CONTA_B); // o ponto sem dona sai antes da chave de B entrar
        assertEquals(0, fila.tamanho());
        encher(1);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(CHAVE_B, pedidosAoNexus.get(2)[1]);
        for (String[] p : pedidosAoNexus.subList(2, pedidosAoNexus.size())) assertNotEquals(CHAVE_A, p[1]);
    }

    @Test
    public void oCofreQueNaoLeAContaDizQueTemAChaveENadaSaiAteOEnvioConferirDeNovo() throws Exception {
        // R9: o soluço do Keystore no pedido do aparelho. A resposta diz que há a chave (pedir outra revogaria à toa a que o
        // cofre guarda), nada sai com o cofre fora, e o cofre que volta deixa o próximo envio conferir e subir a fila. Antes,
        // só a próxima carga da página conferia, e ela pode ser no dia seguinte (a fila perde o que passa de 24 h).
        comChaveETermo(CHAVE_A);
        encher(2);
        partidaNova();
        cifra.keystoreFora = true;
        List<String> respostas = new ArrayList<>();
        casca().pedirAparelho(CONTA_A, respostas::add);
        assertTrue("pedir outra chave revogaria à toa a que o cofre guarda", new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertEquals("o cofre ainda fora: nada sai", EnvioDePontos.Resultado.NADA, envio().enviar());
        assertTrue(pedidosAoNexus.isEmpty());
        assertEquals(2, fila.tamanho());
        cifra.keystoreFora = false;
        assertEquals("o cofre voltou: o envio confere e sobe a fila", EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(2, new JSONObject(pedidosAoNexus.get(0)[2]).getJSONArray("pontos").length());
        assertTrue(chave.conferida());
    }

    @Test
    public void aVoltaAoAppConfereDeNovoAContaQueOCofreNaoDeixouConferir() {
        comChaveETermo(CHAVE_A);
        partidaNova();
        EquipeNaCasca c = casca();
        cifra.keystoreFora = true;
        c.pedirAparelho(CONTA_A, json -> true);
        assertFalse(chave.conferida());
        c.reavaliar();
        assertFalse("o cofre ainda fora", chave.conferida());
        cifra.keystoreFora = false;
        c.reavaliar();
        assertTrue("a volta ao app conferiu", chave.conferida());
        assertTrue("nada revogado: é a mesma conta", pedidosAoNexus.isEmpty());
        assertEquals(CHAVE_A, chave.chave());
    }

    @Test
    public void oCofreQueVoltaComOutraContaGuardadaNaoMandaEAVoltaAoAppFazATroca() throws Exception {
        // B usou o aparelho sem o Sair, e o cofre não respondeu no pedido do aparelho de A: a resposta disse que há a chave,
        // e a página de A não pediu outra. O cofre volta: o envio confere, acha a conta de B e nada sai, e a volta ao app faz
        // a troca de conta da R5 (fora do envio do serviço).
        chave.guardar(CHAVE_B, CONTA_B);
        chave.aceitarTermo();
        encher(2);
        partidaNova();
        servico.rodando = true;
        EquipeNaCasca c = casca();
        cifra.keystoreFora = true;
        List<String> respostas = new ArrayList<>();
        c.pedirAparelho(CONTA_A, respostas::add);
        assertTrue(new JSONObject(respostas.get(0)).getBoolean("temChave"));
        cifra.keystoreFora = false;
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        assertTrue("nada em nome de B", pedidosAoNexus.isEmpty());
        assertEquals(2, fila.tamanho());

        c.reavaliar();
        assertEquals("só o revogar da chave de B", 1, pedidosAoNexus.size());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertEquals(CHAVE_B, pedidosAoNexus.get(0)[1]);
        assertNull(chave.chave());
        assertNull(chave.conta());
        assertEquals("a fila de B saiu", 0, fila.tamanho());
        assertEquals("parar", servico.chamadas.get(servico.chamadas.size() - 1));
        assertEquals(0, servico.iniciados);
    }

    @Test
    public void aTelaQueNasceNaoDesfazAConferenciaDoDisco() throws Exception {
        // R14: a tela que nasce (o load do plugin) com a tela apagada não tem página que rode, e a coleta que liga com ela
        // envia; a página dela confere quando carregar (a mesma conta mantém; outra faz a troca).
        comChaveETermo(CHAVE_A);
        encher(1);
        EquipeNaCasca c = casca();
        assertTrue(chave.conferida());
        c.aTelaAbriu();
        assertTrue(chave.conferida());
        assertTrue(temAMarca());
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        c.pedirAparelho(CONTA_A, json -> true);
        assertTrue("a mesma conta mantém", chave.conferida());
        assertTrue(temAMarca());
        encher(1);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(2, pedidosAoNexus.size());
    }

    @Test
    public void aChaveQueAPaginaEntregaConfere() throws Exception {
        // A partida a frio de A depois do 401 de ontem (que apagou a chave e a conferência): a chave nova da mesma conta
        // entra, e o dia de A sobe com ela.
        comChaveETermo(CHAVE_A);
        encher(2);
        chave.apagarAChave();
        partidaNova();
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        casca().receberChave(true, CHAVE_B, CONTA_A);
        assertTrue(chave.conferida());
        assertTrue(temAMarca());
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(CHAVE_B, pedidosAoNexus.get(0)[1]);
        assertEquals(2, new JSONObject(pedidosAoNexus.get(0)[2]).getJSONArray("pontos").length());
    }

    @Test
    public void oSairATrocaDeContaOColetaFalseEO401VoltamANaoConferidaTambemNoDisco() {
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        assertTrue(chave.conferida());
        c.sair();
        assertFalse("o Sair", chave.conferida());
        assertFalse(temAMarca());
        partidaNova();
        assertFalse("nem na partida nova", chave.conferida());

        c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        c.pedirAparelho(CONTA_B, json -> true);
        assertFalse("a troca de conta", chave.conferida());
        assertFalse(temAMarca());

        c.receberChave(true, CHAVE_A, CONTA_A);
        c.receberChave(false, null, CONTA_A);
        assertFalse("o coleta:false do registro", chave.conferida());
        assertFalse(temAMarca());

        c.receberChave(true, CHAVE_A, CONTA_A);
        assertTrue(temAMarca());
        resposta = new EnvioDePontos.Resposta(401, "");
        encher(1);
        assertEquals(EnvioDePontos.Resultado.PARAR, envio().enviar());
        assertFalse("o 401 do envio", temAMarca());
        partidaNova();
        assertFalse(chave.conferida());
    }

    @Test
    public void naPartidaAFrioSemAMarcaOSemPermissaoTambemEsperaAContaConferida() throws Exception {
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A);
        partidaNovaSemAMarca();
        EquipeNaCasca c = casca();
        c.reavaliar();
        tela.aceitarDoTermo.run();
        responder(c, false, false);
        assertTrue("o lote sem permissão espera como o de pontos", pedidosAoNexus.isEmpty());
        assertFalse("e não conta como avisado", daCasca().contains("sem_permissao_em"));
        c.pedirAparelho(CONTA_A, json -> true);
        agora += MINUTO;
        c.reavaliar();
        assertEquals(1, pedidosAoNexus.size());
        assertEquals("sem", new JSONObject(pedidosAoNexus.get(0)[2]).getString("permissao"));
    }

    @Test
    public void naPartidaAFrioOSairRevogaSemEsperarAConferencia() {
        comChaveETermo(CHAVE_A);
        partidaNova();
        casca().sair();
        assertEquals("a revogação vai com a chave velha", 1, pedidosAoNexus.size());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertNull(chave.chave());
    }

    @Test
    public void semChaveNemFilaOPedidoDoAparelhoNaoMexeEmNada() throws Exception {
        // A instalação nova: nada a tirar, e nada parado.
        List<String> respostas = new ArrayList<>();
        casca().pedirAparelho(CONTA_A, respostas::add);
        assertFalse(new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertTrue(servico.chamadas.isEmpty());
        assertEquals(0, tela.fechadas);
    }

    @Test
    public void oSairTambemApagaAMarcaDosTrintaMinutos() {
        comChaveETermo(CHAVE_A);
        marcarSemPermissao(agora);
        casca().sair();
        assertFalse("a próxima conta não espera os 30 minutos de quem saiu", daCasca().contains("sem_permissao_em"));
        assertNull(chave.chave());
        assertNull(chave.conta());
    }

    // --- o aviso das 6h (R12) -------------------------------------------------------------------------------------------

    /** Os alarmes do aviso das 6h agendados agora (os que mandam a ação dele ao receptor), pela hora em que tocam. */
    private List<Long> alarmesDas6h() {
        AlarmManager alarmes = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        List<Long> deles = new ArrayList<>();
        for (ShadowAlarmManager.ScheduledAlarm a : shadowOf(alarmes).getScheduledAlarms()) {
            if (a.operation == null) continue;
            Intent alvo = shadowOf(a.operation).getSavedIntent();
            if (alvo != null && AvisoDoInicio.ACAO_DO_ALARME.equals(alvo.getAction())) deles.add(a.getTriggerAtMs());
        }
        return deles;
    }

    /** O aviso das 6h está na barra. */
    private boolean avisoDas6hNaBarra() {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        return shadowOf(nm).getNotification(AvisoDoInicio.ID_DA_NOTIFICACAO) != null;
    }

    @Test
    public void aVoltaAoAppComAChaveEOTermoAgendaOAvisoDas6hDoDiaSeguinte() {
        // A cada volta ao app, também depois do Forçar parada (que apaga os alarmes do app) ou do boot que o Android não
        // entregou: a primeira abertura refaz o alarme do dia seguinte.
        comChaveETermo(CHAVE_A);
        casca().reavaliar();
        assertEquals(Collections.singletonList(em(10, 6, 0)), alarmesDas6h());
        casca().reavaliar();
        assertEquals("não empilha", 1, alarmesDas6h().size());
    }

    @Test
    public void oTermoAceitoEAChaveQueEntraAgendamOAvisoDas6h() {
        // A chave sem o termo: nada agendado; o Li e aceito agenda.
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        assertEquals(1, tela.termos);
        assertTrue("sem o termo, nenhum aviso", alarmesDas6h().isEmpty());
        tela.aceitarDoTermo.run();
        assertEquals(Collections.singletonList(em(10, 6, 0)), alarmesDas6h());

        // O 401 apaga a chave e leva o alarme; a chave nova da mesma conta (o termo já aceito) o agenda de novo.
        chave.apagarAChave();
        assertTrue("o 401 leva o alarme", alarmesDas6h().isEmpty());
        c.receberChave(true, CHAVE_B, CONTA_A);
        assertEquals(Collections.singletonList(em(10, 6, 0)), alarmesDas6h());
    }

    @Test
    public void oSairOColetaFalseEATrocaDeContaCancelamOAvisoDas6h() {
        dar(Manifest.permission.POST_NOTIFICATIONS);
        comChaveETermo(CHAVE_A);
        casca().reavaliar();
        assertTrue(AvisoDoInicio.mostrar(app, em(9, 6, 5)));
        semRede = new IOException("sem rede");
        casca().sair();
        assertTrue("o Sair leva o alarme, mesmo sem rede", alarmesDas6h().isEmpty());
        assertFalse("e o aviso da barra", avisoDas6hNaBarra());

        semRede = null;
        comChaveETermo(CHAVE_A);
        casca().reavaliar();
        assertEquals(1, alarmesDas6h().size());
        casca().receberChave(false, null, CONTA_A);
        assertTrue("o coleta:false leva o alarme", alarmesDas6h().isEmpty());

        comChaveETermo(CHAVE_A);
        casca().reavaliar();
        assertEquals(1, alarmesDas6h().size());
        casca().pedirAparelho(CONTA_B, json -> true);
        assertTrue("a troca de conta leva o alarme: B ainda não aceitou o termo", alarmesDas6h().isEmpty());
    }

    // --- o log --------------------------------------------------------------------------------------------------------

    @Test
    public void nadaDaChaveDaContaNemDoAparelhoVaiAoLog() {
        tirar(PRECISA, APROXIMADA);
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        responder(c, false, false);
        c.pedirAparelho(CONTA_A, json -> true);
        c.pedirAparelho(CONTA_B, json -> true); // a troca de conta
        c.receberChave(true, CHAVE_B, CONTA_B);
        c.receberChave(true, CHAVE_A, CONTA_A); // e a volta, pelo equipeChave
        semRede = new IOException("sem rede " + CHAVE_B + " " + CONTA_B);
        c.sair();
        c.receberChave(false, null, CONTA_B);
        String aparelho = chave.aparelho();
        assertFalse(ShadowLog.getLogs().isEmpty());
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            String linha = item.tag + " " + item.msg + (item.throwable == null ? "" : " " + item.throwable);
            for (String proibido : new String[] {CHAVE_A, CHAVE_B, "chaveFicticia", CONTA_A, CONTA_B, "aaaa0000",
                    "bbbb0000", aparelho}) {
                if (linha.contains(proibido)) fail("o log levou \"" + proibido + "\": " + linha);
            }
        }
    }

    // --- a tela de verdade --------------------------------------------------------------------------------------------

    private ActivityController<AppCompatActivity> controle;

    private AppCompatActivity activity() {
        controle = Robolectric.buildActivity(AppCompatActivity.class);
        controle.get().setTheme(R.style.AppTheme);
        return controle.setup().get();
    }

    private static AlertDialog ultimoDialogo() {
        shadowOf(Looper.getMainLooper()).idle();
        Dialog d = ShadowDialog.getLatestDialog();
        assertTrue("um diálogo do AppCompat", d instanceof AlertDialog);
        return (AlertDialog) d;
    }

    private static String texto(Dialog d, int id) {
        TextView v = d.findViewById(id);
        return v == null ? null : v.getText().toString();
    }

    private void conferirDialogo(AlertDialog d, int titulo, int continuar) {
        assertTrue(d.isShowing());
        assertFalse("não fecha tocando fora nem pelo Voltar", shadowOf(d).isCancelable());
        assertEquals(app.getString(titulo), texto(d, androidx.appcompat.R.id.alertTitle));
        Button positivo = d.getButton(AlertDialog.BUTTON_POSITIVE);
        assertEquals(app.getString(continuar), positivo.getText().toString());
        Button negativo = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        assertTrue("sem botão de recusar", negativo == null || negativo.getVisibility() != View.VISIBLE);
    }

    /** O termo aberto na tela de verdade, com o layout feito. */
    private AlertDialog abrirOTermo(Runnable aceitar) {
        assertTrue(new EquipeNaCasca.TelaDaActivity(activity(), p -> { }).mostrarTermo(aceitar));
        return ultimoDialogo();
    }

    /** A rolagem do texto do termo, dentro do diálogo. */
    private static ScrollView rolagem(Dialog d) {
        ScrollView r = d.findViewById(R.id.equipe_termo_rolagem);
        assertNotNull("o texto do termo rola dentro do diálogo", r);
        return r;
    }

    /** Até onde a rolagem desce: a altura do texto menos a parte à vista. */
    private static int fimDaRolagem(ScrollView r) {
        return Math.max(0, r.getChildAt(0).getHeight() - (r.getHeight() - r.getPaddingTop() - r.getPaddingBottom()));
    }

    private static void rolarAte(ScrollView r, int y) {
        r.scrollTo(0, y);
        shadowOf(Looper.getMainLooper()).idle();
    }

    // A rolagem animada (a do TalkBack e a do teclado) anda a cada quadro desenhado, pelo computeScroll. O Robolectric não
    // desenha a tela: o teste passa o tempo da animação e chama o computeScroll que o quadro seguinte chamaria.
    private static void deixarARolagemAndar(ScrollView r) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        r.computeScroll();
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** A folga do fim em pixels, na densidade da tela do teste. */
    private int folga() {
        return Math.round(TermoDaEquipe.FOLGA_DP * app.getResources().getDisplayMetrics().density);
    }

    private static boolean emNegrito(CharSequence texto) {
        if (!(texto instanceof Spanned)) return false;
        Spanned s = (Spanned) texto;
        for (StyleSpan estilo : s.getSpans(0, s.length(), StyleSpan.class)) {
            if (estilo.getStyle() == Typeface.BOLD && s.getSpanStart(estilo) == 0 && s.getSpanEnd(estilo) == s.length()) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void aTelaDeVerdadeMostraOTermoQueSoOLiEAceitoFechaEOPedidoVaiDiretoAoAndroid() {
        AppCompatActivity activity = activity();
        List<List<String>> pedidos = new ArrayList<>();
        EquipeNaCasca.TelaDaActivity t = new EquipeNaCasca.TelaDaActivity(activity, p -> pedidos.add(Arrays.asList(p)));
        boolean[] aceitou = {false};

        assertTrue(t.mostrarTermo(() -> aceitou[0] = true));
        AlertDialog termo = ultimoDialogo();
        conferirDialogo(termo, R.string.casca_equipe_termo_titulo, R.string.casca_equipe_termo_aceitar);
        rolarAte(rolagem(termo), fimDaRolagem(rolagem(termo)));
        termo.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(aceitou[0]);
        assertFalse(termo.isShowing());

        // O pedido vai direto ao launcher do Android, sem diálogo nosso antes.
        assertTrue(t.pedir(aLocalizacao().toArray(new String[0])));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList(aLocalizacao()), pedidos);
        assertEquals("nenhum diálogo depois do termo", termo, ShadowDialog.getLatestDialog());

        // O Sair (ou o coleta:false) com o termo na frente: ele sai da tela.
        t.mostrarTermo(() -> fail("o termo fechado pelo Sair não é um aceite"));
        AlertDialog deNovo = ultimoDialogo();
        assertTrue(deNovo.isShowing());
        t.fechar();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(deNovo.isShowing());
    }

    @Test
    public void oTermoAbreComOTextoInteiroEmSecoesEOLiEAceitoDesligadoComADica() {
        boolean[] aceitou = {false};
        AlertDialog termo = abrirOTermo(() -> aceitou[0] = true);

        // O texto inteiro, linha a linha, como o strings.xml o traz: o título de cada seção em negrito e marcado como
        // título para o TalkBack (que pula de seção em seção), o resto em texto normal.
        List<String> esperadas = new ArrayList<>();
        for (String linha : app.getString(R.string.casca_equipe_termo_texto).split("\n")) {
            if (!linha.trim().isEmpty()) esperadas.add(linha.trim());
        }
        ViewGroup secoes = termo.findViewById(R.id.equipe_termo_secoes);
        List<String> mostradas = new ArrayList<>();
        int titulos = 0;
        for (int i = 0; i < secoes.getChildCount(); i++) {
            TextView v = (TextView) secoes.getChildAt(i);
            String linha = v.getText().toString();
            mostradas.add(linha);
            boolean titulo = linha.matches("\\d+\\. .*");
            assertEquals("negrito só no título da seção: " + linha, titulo, emNegrito(v.getText()));
            assertEquals("título para o TalkBack só no da seção: " + linha, titulo, ViewCompat.isAccessibilityHeading(v));
            if (titulo) titulos++;
        }
        assertEquals(esperadas, mostradas);
        assertEquals("as 12 seções", 12, titulos);
        View mensagem = termo.findViewById(android.R.id.message);
        assertTrue("o texto não vai na mensagem do diálogo, que não rola com o botão à vista",
            mensagem == null || !mensagem.isShown());

        // Desligado ao abrir, com a dica logo acima dele, e o texto maior que a parte à vista.
        Button aceitar = termo.getButton(AlertDialog.BUTTON_POSITIVE);
        ScrollView r = rolagem(termo);
        assertTrue("o texto rola: " + r.getChildAt(0).getHeight() + " px em " + r.getHeight() + " px",
            fimDaRolagem(r) > folga());
        assertFalse(aceitar.isEnabled());
        TextView dica = termo.findViewById(R.id.equipe_termo_dica);
        assertEquals(View.VISIBLE, dica.getVisibility());
        assertEquals(app.getString(R.string.casca_equipe_termo_rolar), dica.getText().toString());

        // Um clique que chegasse com o botão desligado (um serviço que clica sem olhar o estado) não aceita nem fecha.
        aceitar.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(aceitou[0]);
        assertTrue(termo.isShowing());
    }

    @Test
    public void soORolarAteOFimLigaOLiEAceitoEOMeioNao() {
        boolean[] aceitou = {false};
        AlertDialog termo = abrirOTermo(() -> aceitou[0] = true);
        Button aceitar = termo.getButton(AlertDialog.BUTTON_POSITIVE);
        View dica = termo.findViewById(R.id.equipe_termo_dica);
        ScrollView r = rolagem(termo);
        int fim = fimDaRolagem(r);

        rolarAte(r, fim / 2);
        assertFalse("o meio do texto não liga", aceitar.isEnabled());
        rolarAte(r, fim - folga() - 1);
        assertFalse("um pixel antes da folga do fim, também não", aceitar.isEnabled());
        assertEquals(View.VISIBLE, dica.getVisibility());

        rolarAte(r, fim - folga());
        assertTrue("o fim do texto à vista, com a folga de poucos pixels, liga", aceitar.isEnabled());
        assertEquals("a dica sai", View.GONE, dica.getVisibility());

        rolarAte(r, 0);
        assertTrue("ligado, fica ligado ao subir de novo", aceitar.isEnabled());
        assertTrue("e o termo continua aberto até o toque", termo.isShowing());
        assertFalse(aceitou[0]);
        aceitar.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(aceitou[0]);
        assertFalse(termo.isShowing());
    }

    @Test
    public void oTextoQueCabeInteiroLigaOLiEAceitoLogoDepoisDoLayout() {
        // A tela grande em que o termo inteiro aparece sem rolar: o fim do texto já está à vista, e não há o que rolar.
        AppCompatActivity activity = activity();
        View vista = TermoDaEquipe.vista(activity, "Versão curta, que cabe inteira na tela.");
        Button aceitar = new Button(activity);
        LinearLayout tela = new LinearLayout(activity);
        tela.setOrientation(LinearLayout.VERTICAL);
        tela.addView(vista, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tela.addView(aceitar);
        activity.setContentView(tela);
        boolean[] aceitou = {false};
        TermoDaEquipe.travar(vista, aceitar, () -> aceitou[0] = true);
        assertFalse("antes do layout não se sabe se cabe", aceitar.isEnabled());
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(aceitar.isEnabled());
        assertEquals(View.GONE, vista.findViewById(R.id.equipe_termo_dica).getVisibility());
        aceitar.performClick();
        assertTrue(aceitou[0]);
    }

    @Test
    public void aVoltaAoAppEONovoLayoutDaGiradaNaoLigamOLiEAceitoSemARolagem() {
        // A girada da tela refaz o layout (a MainActivity não é recriada: o configChanges do manifesto) e a volta ao app
        // passa pelo onPause e pelo onResume: nenhum dos dois é o fim do texto à vista.
        AlertDialog termo = abrirOTermo(() -> fail("não houve aceite"));
        Button aceitar = termo.getButton(AlertDialog.BUTTON_POSITIVE);
        ScrollView r = rolagem(termo);
        rolarAte(r, fimDaRolagem(r) / 2);
        RuntimeEnvironment.setQualifiers("+land");
        termo.getWindow().getDecorView().requestLayout();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("o novo layout não é a rolagem até o fim", aceitar.isEnabled());
        controle.pause().stop().restart().start().resume();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(termo.isShowing());
        assertFalse("a volta ao app não é a rolagem até o fim", aceitar.isEnabled());
        rolarAte(r, fimDaRolagem(r));
        assertTrue(aceitar.isEnabled());
    }

    @Test
    public void rolarPeloTalkBackContaIgual() {
        // O TalkBack rola com a ação de rolar para a frente (o gesto de dois dedos, ou o "rolar" do menu dele): passa pela
        // mesma rolagem que o dedo.
        AlertDialog termo = abrirOTermo(() -> { });
        Button aceitar = termo.getButton(AlertDialog.BUTTON_POSITIVE);
        ScrollView r = rolagem(termo);
        for (int vez = 0; vez < 200 && !aceitar.isEnabled(); vez++) {
            assertTrue("o TalkBack ainda rola", r.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
            deixarARolagemAndar(r);
            if (vez == 0) assertFalse("uma tela de rolagem não é o fim", aceitar.isEnabled());
        }
        assertTrue(aceitar.isEnabled());
        assertEquals("ligou no fim, e não antes", fimDaRolagem(r), r.getScrollY());
    }

    @Test
    public void rolarPeloTecladoContaIgual() {
        // O teclado (Alt e a seta para baixo vão ao fim) passa pela mesma rolagem que o dedo.
        AlertDialog termo = abrirOTermo(() -> { });
        Button aceitar = termo.getButton(AlertDialog.BUTTON_POSITIVE);
        ScrollView r = rolagem(termo);
        assertTrue(r.requestFocus());
        // Longe da última rolagem (mais de 250 ms), o ScrollView anima a do teclado, como no aparelho.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue(r.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0,
            KeyEvent.META_ALT_ON)));
        assertFalse("antes de a rolagem andar, nada liga", aceitar.isEnabled());
        deixarARolagemAndar(r);
        assertTrue(aceitar.isEnabled());
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp", fontScale = 2.0f)
    public void comAFonteGrandeNaTelaPequenaOTextoRolaAteOFimEOBotaoFicaAVista() {
        // A fonte do sistema no dobro, numa tela pequena: o título, a dica e o botão cabem, o texto ainda tem onde rolar, e
        // o Li e aceito liga no fim.
        AlertDialog termo = abrirOTermo(() -> { });
        Button aceitar = termo.getButton(AlertDialog.BUTTON_POSITIVE);
        ScrollView r = rolagem(termo);
        assertTrue("sobra altura para o texto: " + r.getHeight() + " px", r.getHeight() > 0);
        assertTrue(aceitar.getHeight() > 0);
        View janela = termo.getWindow().getDecorView();
        int[] botao = new int[2];
        aceitar.getLocationInWindow(botao);
        assertTrue("o botão fica dentro da janela do diálogo", botao[1] + aceitar.getHeight() <= janela.getHeight());
        assertFalse(aceitar.isEnabled());
        rolarAte(r, fimDaRolagem(r));
        assertTrue(aceitar.isEnabled());
    }

    // O texto de cada versão do termo, pelo resumo (o SHA-256 do texto que o técnico lê). O aceite guarda a versão
    // (ChaveDaEquipe.TERMO_VERSAO): o texto que muda sem a versão nova valeria para quem aceitou o de antes. Mudou o
    // texto? Mude o TERMO_VERSAO e a primeira linha do texto, e acrescente aqui a versão nova com o resumo dela.
    // A 1 (09/11/2026) prometia só a cidade fora de usina; a 2 (10/11/2026, R13) mostra o ponto exato e o percurso; a 3
    // (10/11/2026, R15) pede o Permitir o tempo todo e a bateria sem restrição, e diz que a coleta volta sozinha no mesmo
    // dia depois de reiniciar ou atualizar.
    private static final Map<String, String> TEXTO_DE_CADA_VERSAO = new HashMap<>();
    static {
        TEXTO_DE_CADA_VERSAO.put("1", "7bf3bf6f12d6aeb38ecb3229e780fbcce48cd8af8c3c29301070ccf2a9c5f7b1");
        TEXTO_DE_CADA_VERSAO.put("2", "3e9742434fb1d7f4b48b4fc72308463cb370125f7eb198b039e1ba3d0e945840");
        TEXTO_DE_CADA_VERSAO.put("3", "292030c089bc624bf37dd9a22eb177a5916f7b66a3177933c79ac1c727726a5b");
    }

    private static String sha256(String texto) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    public void oTermoEOAvisoDePrivacidadeInteiroDaVersaoDeAgora() throws Exception {
        // R11, o pedido do dono (09/11/2026): o termo traz tudo o que a LGPD pede, em seções. O texto ainda vai à aprovação
        // do dono, do RH e do jurídico.
        assertEquals("Termo de uso e privacidade da localização", app.getString(R.string.casca_equipe_termo_titulo));
        assertEquals("Li e aceito", app.getString(R.string.casca_equipe_termo_aceitar));
        assertEquals("Role até o fim para liberar o Li e aceito", app.getString(R.string.casca_equipe_termo_rolar));
        String termo = app.getString(R.string.casca_equipe_termo_texto);
        assertTrue("a primeira linha diz a versão que o aceite guarda",
            termo.startsWith("Versão " + ChaveDaEquipe.TERMO_VERSAO + ", de 10 de novembro de 2026."));
        List<String> titulos = new ArrayList<>();
        for (String linha : termo.split("\n")) if (linha.trim().matches("\\d+\\. .*")) titulos.add(linha.trim());
        assertEquals(Arrays.asList("1. Quem é o responsável", "2. Quais dados", "3. Quando", "4. Para quê", "5. Base legal",
            "6. Quem vê", "7. Com quem é compartilhado", "8. Por quanto tempo", "9. Segurança", "10. Seus direitos",
            "11. Como parar", "12. Mudanças neste termo"), titulos);
        for (String trecho : new String[] {"Lei nº 13.709/2018", "posição deste celular", "Das 6h às 22h",
                "também com o aplicativo fechado", app.getString(R.string.notificacao_equipe_titulo), "art. 7º",
                "PCM, COS, Performance, Supervisão Campo e os Responsáveis UFV", "administradores do Nexus", "7 dias",
                "1 ano", "24 horas", "Google Maps", "art. 18", "ANPD", "RH da Grid Co.", "Sair do aplicativo",
                "a coleta volta sozinha se o celular reiniciar ou o aplicativo for atualizado no mesmo dia",
                "escolha Permitir o tempo todo e deixe o Nexus Field sem restrição de bateria"}) {
            assertTrue("o termo não diz \"" + trecho + "\"", termo.contains(trecho));
        }
        assertFalse("a versão 3 não manda mais escolher a durante o uso", termo.contains("Durante o uso do app"));
        assertEquals("o texto da versão " + ChaveDaEquipe.TERMO_VERSAO + " mudou: mude o ChaveDaEquipe.TERMO_VERSAO e a"
            + " primeira linha do texto, e ponha a versão nova no TEXTO_DE_CADA_VERSAO",
            TEXTO_DE_CADA_VERSAO.get(ChaveDaEquipe.TERMO_VERSAO), sha256(termo));
    }
}
