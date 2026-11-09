package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
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
    }

    /**
     * A tela de mentira: guarda o Li e aceito do termo e os pedidos ao Android; com {@code saindo}, nada abre. Ela só tem o
     * termo e o pedido do Android: nenhuma outra tela nossa existe para a casca abrir.
     */
    private static final class TelaFalsa implements EquipeNaCasca.Tela {
        int termos;
        int fechadas;
        boolean saindo;
        /** O fechar que lança (o diálogo que a tela não tirou agora). */
        boolean lancarNoFechar;
        Runnable aceitarDoTermo;
        final List<List<String>> pedidos = new ArrayList<>();

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

    /** O aparelho que já tem a chave da conta A e cujo técnico já aceitou o termo. */
    private void comChaveETermo(String k) {
        chave.guardar(k, CONTA_A);
        chave.aceitarTermo();
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
    public void comALocalizacaoJaDadaOLiEAceitoLigaSemPedidoNenhum() {
        // O caso comum: a localização já dada no Antes de começar (o App de Campo precisa dela), e só o termo aparece.
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.aceitarDoTermo.run();
        assertTrue(tela.pedidos.isEmpty());
        assertEquals(1, servico.iniciados);
        // A localização tirada mais tarde, na mesma tela: o pedido era só o de logo depois do aceite, e agora só o Nexus
        // fica sabendo.
        servico.rodando = true;
        tirar(PRECISA, APROXIMADA);
        c.reavaliar();
        assertTrue("nenhum pedido fora do aceite", tela.pedidos.isEmpty());
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

    // --- nada sai em nome de uma conta que esta partida não conferiu --------------------------------------------------

    /**
     * O processo novo (o Android matou o app, ou o celular reiniciou): a chave, a conta e a fila no disco, e nada da
     * memória da partida anterior. A casca e o envio montados depois desta chamada usam a chave nova.
     */
    private void partidaNova() {
        chave = new ChaveDaEquipe(app, cifra);
    }

    @Test
    public void naPartidaAFrioAColetaLigaENadaSaiAntesDeAPaginaConferirAConta() {
        // O caso: o onResume liga a coleta com a chave guardada antes de a página mandar a conta (5 s depois do load, mais
        // o token e o resumo), e o primeiro lote saía uns 1 min 45 s depois, em nome da conta da chave.
        comChaveETermo(CHAVE_A);
        partidaNova();
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
        partidaNova();
        EquipeNaCasca c = casca();
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        List<String> respostas = new ArrayList<>();
        c.pedirAparelho(CONTA_A, respostas::add);
        assertTrue(new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertTrue(chave.conferida());
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(1, pedidosAoNexus.size());
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(3, new JSONObject(pedidosAoNexus.get(0)[2]).getJSONArray("pontos").length());
        assertEquals(0, fila.tamanho());
    }

    @Test
    public void naPartidaAFrioComOutraContaNadaSaiuEmNomeDaChaveEAFilaSai() throws Exception {
        // O celular de A na mão de B, que abriu o app sem o Sair de A: a coleta ligou com a chave de A e guardou as
        // posições de B, e nenhuma delas foi ao Nexus em nome de A.
        comChaveETermo(CHAVE_A);
        partidaNova();
        EquipeNaCasca c = casca();
        c.reavaliar();
        encher(3);
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        c.pedirAparelho(CONTA_B, json -> true);
        assertEquals("só o revogar da chave de A", 1, pedidosAoNexus.size());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(0, fila.tamanho());
        assertNull(chave.chave());
        assertFalse(chave.conferida());
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
    public void aTelaQueAbreDesfazAConferenciaAteAPaginaDelaConferir() throws Exception {
        // A abertura com tela (o load do plugin) é o caso da R8: outra conta pode estar entrando. Nada sai até a página desta
        // tela conferir.
        comChaveETermo(CHAVE_A);
        encher(1);
        EquipeNaCasca c = casca();
        assertTrue(chave.conferida());
        c.aTelaAbriu();
        assertFalse(chave.conferida());
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        c.pedirAparelho(CONTA_A, json -> true);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertTrue(pedidosAoNexus.size() == 1);
    }

    @Test
    public void aChaveQueAPaginaEntregaConfere() throws Exception {
        // A partida a frio de A depois do 401 de ontem: a chave nova da mesma conta entra, e o dia de A sobe com ela.
        comChaveETermo(CHAVE_A);
        encher(2);
        partidaNova();
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        casca().receberChave(true, CHAVE_B, CONTA_A);
        assertTrue(chave.conferida());
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(CHAVE_B, pedidosAoNexus.get(0)[1]);
        assertEquals(2, new JSONObject(pedidosAoNexus.get(0)[2]).getJSONArray("pontos").length());
    }

    @Test
    public void oSairEATrocaDeContaVoltamANaoConferida() {
        EquipeNaCasca c = casca();
        c.receberChave(true, CHAVE_A, CONTA_A);
        assertTrue(chave.conferida());
        c.sair();
        assertFalse("o Sair", chave.conferida());

        c.receberChave(true, CHAVE_A, CONTA_A);
        c.pedirAparelho(CONTA_B, json -> true);
        assertFalse("a troca de conta", chave.conferida());
    }

    @Test
    public void naPartidaAFrioOSemPermissaoTambemEsperaAContaConferida() throws Exception {
        tirar(PRECISA, APROXIMADA);
        chave.guardar(CHAVE_A, CONTA_A);
        partidaNova();
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

    private AppCompatActivity activity() {
        ActivityController<AppCompatActivity> controle = Robolectric.buildActivity(AppCompatActivity.class);
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

    private void conferirDialogo(AlertDialog d, int titulo, int texto, int continuar) {
        assertTrue(d.isShowing());
        assertFalse("não fecha tocando fora nem pelo Voltar", shadowOf(d).isCancelable());
        assertEquals(app.getString(titulo), texto(d, androidx.appcompat.R.id.alertTitle));
        assertEquals(app.getString(texto), texto(d, android.R.id.message));
        Button positivo = d.getButton(AlertDialog.BUTTON_POSITIVE);
        assertEquals(app.getString(continuar), positivo.getText().toString());
        Button negativo = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        assertTrue("sem botão de recusar", negativo == null || negativo.getVisibility() != View.VISIBLE);
    }

    @Test
    public void aTelaDeVerdadeMostraOTermoQueSoOLiEAceitoFechaEOPedidoVaiDiretoAoAndroid() {
        AppCompatActivity activity = activity();
        List<List<String>> pedidos = new ArrayList<>();
        EquipeNaCasca.TelaDaActivity t = new EquipeNaCasca.TelaDaActivity(activity, p -> pedidos.add(Arrays.asList(p)));
        boolean[] aceitou = {false};

        assertTrue(t.mostrarTermo(() -> aceitou[0] = true));
        AlertDialog termo = ultimoDialogo();
        conferirDialogo(termo, R.string.casca_equipe_termo_titulo, R.string.casca_equipe_termo_texto,
            R.string.casca_equipe_termo_aceitar);
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
    public void oTermoDizOQueQuandoPorQueQuemVePorQuantoTempoEOQueEscolher() {
        // O que a seção 4.1 do desenho manda o termo dizer (o texto ainda vai à aprovação do dono, do RH e do jurídico), e a
        // escolha na tela do Android que vem em seguida.
        assertEquals("Termo de uso da localização", app.getString(R.string.casca_equipe_termo_titulo));
        assertEquals("Li e aceito", app.getString(R.string.casca_equipe_termo_aceitar));
        String termo = app.getString(R.string.casca_equipe_termo_texto);
        for (String trecho : new String[] {"posição deste celular", "das 6h às 22h", "primeira abertura do dia",
                "aplicativo fechado", "onde a equipe está", "rotas até as usinas", "PCM", "COS", "Performance",
                "Supervisão Campo", "Responsáveis UFV", "administradores do Nexus", "7 dias", "1 ano", "notificação",
                "Sair", "Durante o uso do app"}) {
            assertTrue("o termo não diz \"" + trecho + "\"", termo.contains(trecho));
        }
        assertFalse("nada de o tempo todo", termo.contains("tempo todo"));
    }
}
