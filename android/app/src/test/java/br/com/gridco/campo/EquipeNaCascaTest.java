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
import android.os.Build;
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
import java.util.List;
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
 * A casca liga e desliga a coleta da equipe: o que o Nexus disse da chave do aparelho, o aviso, a permissão "o tempo
 * todo", a janela das 6h às 22h e o Sair. O serviço, a tela e o Nexus são falsos (as rotas do Nexus ainda não estão no
 * ar, e o Robolectric não tem o Google Play services); a chave, a fila e as permissões são as do Android do Robolectric.
 * O relógio é o de Brasília numa segunda-feira; as chaves são fictícias.
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
    private static final String USO = Manifest.permission.ACCESS_FINE_LOCATION;
    private static final String APROXIMADA = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final String O_TEMPO_TODO = Manifest.permission.ACCESS_BACKGROUND_LOCATION;
    private static final int ANDROID_9 = Build.VERSION_CODES.P;
    private static final int ANDROID_10 = Build.VERSION_CODES.Q;
    private static final int ANDROID_11 = Build.VERSION_CODES.R;
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
            parados++;
            rodando = false;
            chamadas.add("parar");
            if (aoParar != null) aoParar.run();
        }

        @Override
        public boolean rodando() {
            return rodando;
        }
    }

    /** A tela de mentira: guarda o Continuar de cada diálogo e os pedidos ao Android; com {@code saindo}, nada abre. */
    private static final class TelaFalsa implements EquipeNaCasca.Tela {
        int avisos;
        int explicacoes;
        int fechadas;
        boolean saindo;
        Runnable continuarDoAviso;
        Runnable continuarDaExplicacao;
        final List<List<String>> pedidos = new ArrayList<>();

        @Override
        public boolean mostrarAviso(Runnable continuar) {
            if (saindo) return false;
            avisos++;
            continuarDoAviso = continuar;
            return true;
        }

        @Override
        public boolean explicarOTempoTodo(Runnable continuar) {
            if (saindo) return false;
            explicacoes++;
            continuarDaExplicacao = continuar;
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
        dar(USO, APROXIMADA, O_TEMPO_TODO);
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

    /** A casca com a thread do envio e a de UI rodando na hora, na thread do teste. */
    private EquipeNaCasca casca(int sdk) {
        return new EquipeNaCasca(app, chave, fila, envio(), servico, tela, Runnable::run, Runnable::run, () -> agora, sdk,
            "1.2.0");
    }

    /** O aparelho que já tem a chave da conta A e cujo técnico já leu o aviso. */
    private void comChaveEAviso(String k) {
        chave.guardar(k, CONTA_A);
        chave.aceitarAviso();
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
        // (coleta, temChave, avisoAceito, permissaoDeUso, permissaoOTempoTodo, dentroDaJanela, rodando)
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
            assertEquals("sem o aviso aceito", EquipeNaCasca.Acao.MOSTRAR_AVISO,
                EquipeNaCasca.decidir(true, true, false, true, true, true, rodando));
            assertEquals("sem a permissão o tempo todo", EquipeNaCasca.Acao.PEDIR_PERMISSAO,
                EquipeNaCasca.decidir(true, true, true, true, false, true, rodando));
            assertEquals("sem a de uso também", EquipeNaCasca.Acao.PEDIR_PERMISSAO,
                EquipeNaCasca.decidir(true, true, true, false, false, true, rodando));
        }
        assertEquals("fora da janela e parado", EquipeNaCasca.Acao.NADA,
            EquipeNaCasca.decidir(true, true, true, true, true, false, false));
        assertEquals("fora da janela e rodando", EquipeNaCasca.Acao.PARAR,
            EquipeNaCasca.decidir(true, true, true, true, true, false, true));
        // Fora da janela nada aparece ao técnico: nem o aviso nem o pedido da permissão (às 23h não há o que ligar).
        assertEquals(EquipeNaCasca.Acao.NADA, EquipeNaCasca.decidir(true, true, false, true, true, false, false));
        assertEquals(EquipeNaCasca.Acao.NADA, EquipeNaCasca.decidir(true, true, true, true, false, false, false));
        assertEquals(EquipeNaCasca.Acao.PARAR, EquipeNaCasca.decidir(true, true, false, false, false, false, true));
    }

    @Test
    public void tudoEmDiaNaVoltaAoAppLigaAColetaUmaVez() {
        comChaveEAviso(CHAVE_A);
        EquipeNaCasca c = casca(ANDROID_11);
        c.reavaliar();
        assertEquals(1, servico.iniciados);
        servico.rodando = true;
        c.reavaliar();
        assertEquals("rodando, não liga outra", 1, servico.iniciados);
        assertEquals(0, servico.parados);
        assertEquals(0, tela.avisos + tela.explicacoes + tela.pedidos.size());
        assertTrue(pedidosAoNexus.isEmpty());
    }

    @Test
    public void as6hValemEAs22hNao() {
        comChaveEAviso(CHAVE_A);
        EquipeNaCasca c = casca(ANDROID_11);
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
        comChaveEAviso(CHAVE_A);
        encher(3);
        servico.rodando = true;
        int[] filaNoParar = {-1};
        servico.aoParar = () -> filaNoParar[0] = fila.tamanho();
        casca(ANDROID_11).receberChave(false, null, CONTA_A);
        assertEquals("a coleta para primeiro, e só então a fila sai: o que falhar depois não a deixa ligada", 3,
            filaNoParar[0]);
        assertNull(chave.chave());
        assertFalse(chave.avisoAceito());
        assertEquals(1, servico.parados);
        assertEquals("os pontos sem dono não sobem com a chave de outra conta", 0, fila.tamanho());
        assertEquals(0, servico.iniciados);
        assertTrue(pedidosAoNexus.isEmpty());
        assertEquals("um diálogo da equipe aberto sai", 1, tela.fechadas);
    }

    @Test
    public void aChaveNovaDaMesmaContaZeraOAvisoEParaAColetaAteOContinuar() {
        // A chave refeita da mesma conta (o 401 do envio apagou a de antes, e a página pediu outra): o aviso de novo.
        comChaveEAviso(CHAVE_A);
        servico.rodando = true;
        EquipeNaCasca c = casca(ANDROID_11);
        c.receberChave(true, CHAVE_B, CONTA_A);
        assertEquals(CHAVE_B, chave.chave());
        assertFalse(chave.avisoAceito());
        assertEquals("a coleta de quem ainda não leu o aviso não segue", 1, servico.parados);
        assertEquals(1, tela.avisos);
        assertEquals(0, servico.iniciados);
        tela.continuarDoAviso.run();
        assertTrue(chave.avisoAceito());
        assertEquals("com o aviso aceito e a permissão dada, liga", 1, servico.iniciados);
    }

    @Test
    public void receberAMesmaChaveNaoMostraOAvisoDeNovo() {
        comChaveEAviso(CHAVE_A);
        casca(ANDROID_11).receberChave(true, CHAVE_A, CONTA_A);
        assertTrue(chave.avisoAceito());
        assertEquals(0, tela.avisos);
        assertEquals(1, servico.iniciados);
    }

    @Test
    public void aChaveQueACifraNaoGuardaNaoLigaNada() {
        cifra.falharAoCifrar = true;
        casca(ANDROID_11).receberChave(true, CHAVE_A, CONTA_A);
        assertNull(chave.chave());
        assertEquals(0, servico.iniciados);
        assertEquals(0, tela.avisos);
        assertEquals("sem chave, para", 1, servico.parados);
    }

    @Test
    public void aChaveQueOCofreNaoLeAgoraNaoMudaNada() {
        comChaveEAviso(CHAVE_A);
        servico.rodando = true;
        cifra.keystoreFora = true;
        casca(ANDROID_11).reavaliar();
        assertEquals(0, servico.iniciados);
        assertEquals("a chave continua guardada: a coleta ligada não para por isso", 0, servico.parados);
        assertEquals(0, tela.avisos + tela.explicacoes + tela.pedidos.size());
        cifra.keystoreFora = false;
        assertEquals(CHAVE_A, chave.chave());
    }

    // --- o aviso e a permissão ----------------------------------------------------------------------------------------

    @Test
    public void oAvisoApareceUmaVezEOContinuarSegueParaAPermissaoNoAndroid11() {
        tirar(O_TEMPO_TODO);
        EquipeNaCasca c = casca(ANDROID_11);
        c.receberChave(true, CHAVE_A, CONTA_A);
        assertEquals(1, tela.avisos);
        c.reavaliar(); // a volta ao app com o aviso na frente: não abre outro por cima
        assertEquals(1, tela.avisos);
        assertEquals(0, servico.iniciados);

        tela.continuarDoAviso.run();
        assertTrue(chave.avisoAceito());
        assertEquals("no Android 11 ou mais novo, a explicação antes dos ajustes", 1, tela.explicacoes);
        assertTrue("o Android só é chamado depois da explicação", tela.pedidos.isEmpty());
        c.reavaliar();
        assertEquals("a explicação na frente: nem outra, nem o aviso ao Nexus", 1, tela.explicacoes);
        assertTrue(pedidosAoNexus.isEmpty());

        tela.continuarDaExplicacao.run();
        assertEquals("só a o tempo todo: a de uso já foi dada", Collections.singletonList(Collections.singletonList(O_TEMPO_TODO)),
            tela.pedidos);
        // O técnico escolheu Permitir o tempo todo nos ajustes.
        dar(O_TEMPO_TODO);
        c.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, true));
        assertEquals(1, servico.iniciados);
        assertTrue(pedidosAoNexus.isEmpty());
    }

    @Test
    public void noAndroid10AOTempoTodoVaiJuntoComADeUsoSemExplicacao() {
        tirar(O_TEMPO_TODO);
        comChaveEAviso(CHAVE_A);
        EquipeNaCasca c = casca(ANDROID_10);
        c.reavaliar();
        assertEquals(0, tela.explicacoes);
        assertEquals(Collections.singletonList(Arrays.asList(USO, APROXIMADA, O_TEMPO_TODO)), tela.pedidos);
        dar(O_TEMPO_TODO);
        c.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, true));
        assertEquals(1, servico.iniciados);
    }

    @Test
    public void antesDoAndroid10ADeUsoJaValeOTempoTodo() {
        tirar(O_TEMPO_TODO); // não existe no Android 9: a de uso basta
        comChaveEAviso(CHAVE_A);
        casca(ANDROID_9).reavaliar();
        assertEquals(1, servico.iniciados);
        assertTrue(tela.pedidos.isEmpty());
    }

    @Test
    public void semAPermissaoOTempoTodoUmAvisoSemPermissaoSaiENaoSeRepeteAntesDe30Minutos() throws Exception {
        tirar(O_TEMPO_TODO);
        comChaveEAviso(CHAVE_A);
        EquipeNaCasca c = casca(ANDROID_11);
        c.reavaliar();
        tela.continuarDaExplicacao.run();
        // O técnico voltou dos ajustes sem escolher o tempo todo.
        c.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, false));
        assertEquals(0, servico.iniciados);
        assertEquals(1, pedidosAoNexus.size());
        String[] pedido = pedidosAoNexus.get(0);
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_POSICOES, pedido[0]);
        assertEquals(CHAVE_A, pedido[1]);
        JSONObject corpo = new JSONObject(pedido[2]);
        assertEquals("sem", corpo.getString("permissao"));
        assertEquals(0, corpo.getJSONArray("pontos").length());
        assertEquals(chave.aparelho(), corpo.getString("aparelho"));

        // As voltas ao app em seguida: a explicação não volta nesta tela, e o aviso ao Nexus espera os 30 minutos.
        agora += 10 * MINUTO;
        c.reavaliar();
        agora += 19 * MINUTO;
        c.reavaliar();
        assertEquals(1, tela.explicacoes);
        assertEquals(1, pedidosAoNexus.size());
        // A tela recriada (o renderer que morreu) pergunta de novo, mas o aviso ao Nexus não sai antes da hora.
        EquipeNaCasca recriada = casca(ANDROID_11);
        recriada.reavaliar();
        assertEquals(2, tela.explicacoes);
        tela.continuarDaExplicacao.run();
        recriada.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, false));
        assertEquals(1, pedidosAoNexus.size());

        agora += 2 * MINUTO; // 31 minutos depois do primeiro
        c.reavaliar();
        assertEquals(2, pedidosAoNexus.size());
        assertEquals("sem", new JSONObject(pedidosAoNexus.get(1)[2]).getString("permissao"));
        assertEquals(0, servico.iniciados);
    }

    @Test
    public void oAvisoSemPermissaoQueNaoChegouAoNexusTentaNaProximaVolta() {
        tirar(O_TEMPO_TODO);
        comChaveEAviso(CHAVE_A);
        EquipeNaCasca c = casca(ANDROID_11);
        c.reavaliar();
        tela.continuarDaExplicacao.run();
        semRede = new IOException("sem rede");
        c.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, false));
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
    public void oAvisoDaChaveDeOutraContaPerguntaAPermissaoDeNovoNaMesmaTela() {
        tirar(O_TEMPO_TODO);
        comChaveEAviso(CHAVE_A);
        EquipeNaCasca c = casca(ANDROID_11);
        c.reavaliar();
        tela.continuarDaExplicacao.run();
        c.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, false)); // a conta de antes negou
        c.receberChave(true, CHAVE_B, CONTA_B); // outra conta entrou no mesmo aparelho
        assertEquals(1, tela.avisos);
        tela.continuarDoAviso.run();
        assertEquals("o Continuar do aviso segue para a permissão, também depois do não da outra conta", 2,
            tela.explicacoes);
    }

    @Test
    public void semAPermissaoDeUsoACascaNaoPedeNadaEAvisaONexus() {
        // Quem pede a de uso é o Antes de começar da MainActivity, a cada abertura: dois pedidos empilhariam as telas.
        tirar(USO, O_TEMPO_TODO);
        comChaveEAviso(CHAVE_A);
        casca(ANDROID_11).reavaliar();
        assertTrue(tela.pedidos.isEmpty());
        assertEquals(0, tela.explicacoes);
        assertEquals(0, servico.iniciados);
        assertEquals(1, pedidosAoNexus.size());
    }

    @Test
    public void aPermissaoQueSumiuComAColetaLigadaParaEPedeDeNovo() {
        comChaveEAviso(CHAVE_A);
        servico.rodando = true;
        tirar(O_TEMPO_TODO);
        casca(ANDROID_11).reavaliar();
        assertEquals(1, servico.parados);
        assertEquals(1, tela.explicacoes);
    }

    @Test
    public void oPedidoInterrompidoPerguntaDeNovoNaVoltaAoApp() {
        tirar(O_TEMPO_TODO);
        comChaveEAviso(CHAVE_A);
        EquipeNaCasca c = casca(ANDROID_11);
        c.reavaliar();
        tela.continuarDaExplicacao.run();
        c.aposOPedido(Collections.emptyMap()); // a tela saiu no meio: o técnico não respondeu
        assertTrue("sem resposta não é um não", pedidosAoNexus.isEmpty());
        c.reavaliar();
        assertEquals(2, tela.explicacoes);
    }

    @Test
    public void oQueNaoApareceuNaTelaNaoDeixaACascaEsperandoParaSempre() {
        // A tela saindo (finishing) não mostra o diálogo nem pede ao Android: ninguém vai responder.
        tirar(O_TEMPO_TODO);
        chave.guardar(CHAVE_A, CONTA_A);
        EquipeNaCasca c = casca(ANDROID_11);
        tela.saindo = true;
        c.reavaliar();
        tela.saindo = false;
        c.reavaliar();
        assertEquals("a volta seguinte mostra o aviso", 1, tela.avisos);

        tela.saindo = true;
        tela.continuarDoAviso.run(); // a explicação que vem em seguida não abre
        assertTrue(chave.avisoAceito());
        tela.saindo = false;
        c.reavaliar();
        assertEquals(1, tela.explicacoes);
        assertTrue("nem o aviso ao Nexus: a explicação não tinha sido vista", pedidosAoNexus.isEmpty());

        tela.saindo = true;
        tela.continuarDaExplicacao.run(); // o pedido ao Android não abre
        tela.saindo = false;
        c.reavaliar();
        assertEquals(2, tela.explicacoes);
        assertTrue(pedidosAoNexus.isEmpty());
    }

    @Test
    public void foraDaJanelaNadaApareceEAColetaQueSobrouPara() {
        tirar(O_TEMPO_TODO);
        chave.guardar(CHAVE_A, CONTA_A); // sem o aviso aceito
        agora = em(9, 23, 0);
        EquipeNaCasca c = casca(ANDROID_11);
        c.reavaliar();
        assertEquals(0, tela.avisos + tela.explicacoes + tela.pedidos.size());
        assertTrue(pedidosAoNexus.isEmpty());
        servico.rodando = true;
        c.reavaliar();
        assertEquals(1, servico.parados);
        // A primeira abertura do dia seguinte, depois das 6h, mostra o aviso.
        agora = em(10, 6, 30);
        c.reavaliar();
        assertEquals(1, tela.avisos);
    }

    // --- o Sair -------------------------------------------------------------------------------------------------------

    @Test
    public void sairRevogaApagaEParaMesmoSemRede() throws Exception {
        comChaveEAviso(CHAVE_A);
        encher(2);
        servico.rodando = true;
        String aparelho = chave.aparelho();
        semRede = new IOException("sem rede");
        casca(ANDROID_11).sair();
        assertEquals("na hora e de novo na thread do envio, sem nunca ligar", Arrays.asList("parar", "parar"),
            servico.chamadas);
        assertNull(chave.chave());
        assertFalse(chave.avisoAceito());
        assertEquals("os pontos de quem saiu não sobem com a chave de quem entrar", 0, fila.tamanho());
        assertEquals(1, pedidosAoNexus.size());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(aparelho, new JSONObject(pedidosAoNexus.get(0)[2]).getString("aparelho"));
        assertEquals("o aparelho fica: é a identidade dele, não o segredo", aparelho, chave.aparelho());
        assertEquals(1, tela.fechadas);
    }

    @Test
    public void sairApagaAChaveMesmoComOCofreQueNaoALeAgora() {
        comChaveEAviso(CHAVE_A);
        cifra.keystoreFora = true; // o revogar não consegue ler a chave para mandá-la ao Nexus
        casca(ANDROID_11).sair();
        assertEquals(Arrays.asList("parar", "parar"), servico.chamadas);
        cifra.keystoreFora = false;
        assertNull("a chave de quem saiu não fica no aparelho", chave.chave());
        assertTrue(pedidosAoNexus.isEmpty());
    }

    /** A casca com a thread do envio em fila: o que entra nela só roda no {@link #soltar}. */
    private EquipeNaCasca cascaComAThreadEmFila(Deque<Runnable> threadDoEnvio) {
        return new EquipeNaCasca(app, chave, fila, envio(), servico, tela, threadDoEnvio::add, Runnable::run,
            () -> agora, ANDROID_11, "1.2.0");
    }

    /** A thread do envio solta (o envio pendurado respondeu): roda o que estava nela e o que entrar no caminho. */
    private static void soltar(Deque<Runnable> threadDoEnvio) {
        while (!threadDoEnvio.isEmpty()) threadDoEnvio.poll().run();
    }

    @Test
    public void aVoltaAoAppQueEsperavaNaThreadNaoReligaAColetaDepoisDoSair() {
        // O caso: a coleta ligada e um envio pendurado na rede fraca segurando a thread do envio (15 s de conexão e
        // 15 s de leitura); o técnico volta ao app (o reavaliar entra na fila, atrás do envio) e toca em Sair.
        comChaveEAviso(CHAVE_A);
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
        assertEquals("nem mostra o aviso ou a permissão", 0, tela.avisos + tela.explicacoes + tela.pedidos.size());
        assertNull(chave.chave());
        assertEquals("os pontos de quem saiu não ficam para a chave seguinte", 0, fila.tamanho());

        // A conta seguinte no aparelho: a chave nova, o aviso e o Continuar ligam a coleta dela (a marca do Sair saiu
        // com a chave de quem saiu).
        c.receberChave(true, CHAVE_B, CONTA_B);
        soltar(threadDoEnvio);
        assertEquals(1, tela.avisos);
        tela.continuarDoAviso.run();
        soltar(threadDoEnvio);
        assertEquals(1, servico.iniciados);
    }

    @Test
    public void oSairNoMeioDaDecisaoDaThreadTerminaComAColetaParadaAntesDaRede() {
        // O Sair (na thread do espelho) que chega quando a decisão da thread do envio já passou pela marca do Sair: o
        // iniciar dela vale depois do parar do Sair, e é a tarefa do Sair, atrás dela na thread, que para de novo.
        comChaveEAviso(CHAVE_A);
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
        comChaveEAviso(CHAVE_A);
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
        comChaveEAviso(CHAVE_A);
        encher(2);
        servico.rodando = true;
        marcarSemPermissao(agora);
        List<String> respostas = new ArrayList<>();
        casca(ANDROID_11).pedirAparelho(CONTA_A, json -> respostas.add(json));
        JSONObject r = new JSONObject(respostas.get(0));
        assertTrue("a chave desta conta: a página não pede outra", r.getBoolean("temChave"));
        assertFalse("a chave nunca vai à página", respostas.get(0).contains(CHAVE_A));
        assertFalse("nem a conta volta a ela", respostas.get(0).contains(CONTA_A));
        assertTrue("nada parado", servico.chamadas.isEmpty());
        assertEquals(2, fila.tamanho());
        assertTrue(pedidosAoNexus.isEmpty());
        assertEquals(CHAVE_A, chave.chave());
        assertTrue(chave.avisoAceito());
        assertTrue(daCasca().contains("sem_permissao_em"));
        assertEquals(0, tela.fechadas);
    }

    @Test
    public void outraContaNoAparelhoFazOSairDaAnteriorAntesDaResposta() throws Exception {
        // O caso: A não tocou em Sair, e B entra no mesmo aparelho. Sem isto, B ficava com a chave, o aviso aceito e a
        // fila de A, e as posições de B iam ao Nexus em nome de A.
        comChaveEAviso(CHAVE_A);
        encher(3);
        servico.rodando = true;
        marcarSemPermissao(agora);
        String aparelho = chave.aparelho();
        List<String> naHoraDaResposta = new ArrayList<>();
        List<String> respostas = new ArrayList<>();
        casca(ANDROID_11).pedirAparelho(CONTA_B, json -> {
            naHoraDaResposta.add("serviço " + servico.chamadas + ", fila " + fila.tamanho() + ", Nexus "
                + pedidosAoNexus.size() + ", chave " + chave.chave() + ", aviso " + chave.avisoAceito() + ", marca "
                + daCasca().contains("sem_permissao_em"));
            return respostas.add(json);
        });
        assertEquals("tudo de A já saiu quando a resposta vai",
            Collections.singletonList("serviço [parar], fila 0, Nexus 1, chave null, aviso false, marca false"),
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
        comChaveEAviso(CHAVE_A);
        encher(1);
        app.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().remove("conta").commit();
        List<String> respostas = new ArrayList<>();
        casca(ANDROID_11).pedirAparelho(CONTA_A, json -> respostas.add(json));
        assertFalse(new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertNull(chave.chave());
        assertEquals(0, fila.tamanho());
        assertEquals(Collections.singletonList("parar"), servico.chamadas);
    }

    @Test
    public void oCofreQueNaoLeAContaAgoraNaoMudaNada() throws Exception {
        comChaveEAviso(CHAVE_A);
        encher(2);
        servico.rodando = true;
        cifra.keystoreFora = true;
        List<String> respostas = new ArrayList<>();
        casca(ANDROID_11).pedirAparelho(CONTA_B, json -> respostas.add(json));
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
        comChaveEAviso(CHAVE_A);
        encher(2);
        EquipeNaCasca c = casca(ANDROID_11);
        c.receberChave(true, CHAVE_B, CONTA_B);
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertEquals(0, fila.tamanho());
        assertEquals(CHAVE_B, chave.chave());
        assertEquals(CONTA_B, chave.conta());
        assertFalse(chave.avisoAceito());
        assertEquals("B lê o aviso dela", 1, tela.avisos);
        assertEquals(0, servico.iniciados);
        // A mesma conta com a chave dela: nada mais é revogado.
        c.receberChave(true, CHAVE_B, CONTA_B);
        assertEquals(1, pedidosAoNexus.size());
    }

    @Test
    public void aContaNovaAvisaSemPermissaoSemEsperarOsTrintaMinutosDaAnterior() throws Exception {
        tirar(O_TEMPO_TODO);
        comChaveEAviso(CHAVE_A);
        EquipeNaCasca c = casca(ANDROID_11);
        c.reavaliar();
        tela.continuarDaExplicacao.run();
        c.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, false));
        assertEquals("o sem permissão de A", 1, pedidosAoNexus.size());
        c.pedirAparelho(CONTA_B, json -> true); // B entrou sem o Sair de A
        assertEquals("o revogar da chave de A", 2, pedidosAoNexus.size());
        agora += MINUTO;
        c.receberChave(true, CHAVE_B, CONTA_B);
        tela.continuarDoAviso.run();
        tela.continuarDaExplicacao.run();
        c.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, false));
        assertEquals("o mapa sabe de B um minuto depois, não 30", 3, pedidosAoNexus.size());
        assertEquals(CHAVE_B, pedidosAoNexus.get(2)[1]);
        assertEquals("sem", new JSONObject(pedidosAoNexus.get(2)[2]).getString("permissao"));
    }

    // --- a fila que o 401 deixa (a conta guardada é a dona da fila) ------------------------------------------------------

    /** A chave de A vence com a fila cheia (A trabalhou o dia sem sinal): o primeiro envio leva o 401. */
    private void quatrocentoEUmComAFilaDeA(int pontos) {
        comChaveEAviso(CHAVE_A);
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
        casca(ANDROID_11).pedirAparelho(CONTA_B, json -> {
            naHoraDaResposta.add("serviço " + servico.chamadas + ", fila " + fila.tamanho() + ", aviso "
                + chave.avisoAceito() + ", marca " + daCasca().contains("sem_permissao_em") + ", conta " + chave.conta());
            return respostas.add(json);
        });
        assertEquals("o que é de A saiu antes da resposta",
            Collections.singletonList("serviço [parar], fila 0, aviso false, marca false, conta null"), naHoraDaResposta);
        assertFalse("a página de B pede a chave dela", new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertTrue("sem chave, nada a revogar", pedidosAoNexus.isEmpty());
        assertEquals("um diálogo da equipe de A aberto sai", 1, tela.fechadas);
    }

    @Test
    public void depoisDoQuatrocentoEUmAMesmaContaFicaComAFilaEASobeComAChaveNova() throws Exception {
        quatrocentoEUmComAFilaDeA(3);
        EquipeNaCasca c = casca(ANDROID_11);
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
        EquipeNaCasca c = casca(ANDROID_11);
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
        casca(ANDROID_11).pedirAparelho(CONTA_A, respostas::add);
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
        comChaveEAviso(CHAVE_A);
        partidaNova();
        EquipeNaCasca c = casca(ANDROID_11);
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
        comChaveEAviso(CHAVE_A);
        encher(3);
        partidaNova();
        EquipeNaCasca c = casca(ANDROID_11);
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
        comChaveEAviso(CHAVE_A);
        partidaNova();
        EquipeNaCasca c = casca(ANDROID_11);
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
    public void oCofreQueNaoLeAContaDizQueTemAChaveMasNadaSai() throws Exception {
        comChaveEAviso(CHAVE_A);
        encher(2);
        partidaNova();
        cifra.keystoreFora = true;
        List<String> respostas = new ArrayList<>();
        casca(ANDROID_11).pedirAparelho(CONTA_A, respostas::add);
        assertTrue("pedir outra chave revogaria à toa a que o cofre guarda", new JSONObject(respostas.get(0)).getBoolean("temChave"));
        cifra.keystoreFora = false; // o cofre voltou, e a chave se lê de novo
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        assertTrue("nada sai sem a conta conferida", pedidosAoNexus.isEmpty());
        assertEquals(2, fila.tamanho());
        // A conferência seguinte (a próxima carga), com o cofre de pé, solta o envio.
        casca(ANDROID_11).pedirAparelho(CONTA_A, json -> true);
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(1, pedidosAoNexus.size());
    }

    @Test
    public void aChaveQueAPaginaEntregaConfere() throws Exception {
        // A partida a frio de A depois do 401 de ontem: a chave nova da mesma conta entra, e o dia de A sobe com ela.
        comChaveEAviso(CHAVE_A);
        encher(2);
        partidaNova();
        assertEquals(EnvioDePontos.Resultado.NADA, envio().enviar());
        casca(ANDROID_11).receberChave(true, CHAVE_B, CONTA_A);
        assertTrue(chave.conferida());
        assertEquals(EnvioDePontos.Resultado.ENVIADO, envio().enviar());
        assertEquals(CHAVE_B, pedidosAoNexus.get(0)[1]);
        assertEquals(2, new JSONObject(pedidosAoNexus.get(0)[2]).getJSONArray("pontos").length());
    }

    @Test
    public void oSairEATrocaDeContaVoltamANaoConferida() {
        EquipeNaCasca c = casca(ANDROID_11);
        c.receberChave(true, CHAVE_A, CONTA_A);
        assertTrue(chave.conferida());
        c.sair();
        assertFalse("o Sair", chave.conferida());

        c.receberChave(true, CHAVE_A, CONTA_A);
        c.pedirAparelho(CONTA_B, json -> true);
        assertFalse("a troca de conta", chave.conferida());
    }

    @Test
    public void naPartidaAFrioOAvisoSemPermissaoTambemEsperaAContaConferida() throws Exception {
        tirar(O_TEMPO_TODO);
        comChaveEAviso(CHAVE_A);
        partidaNova();
        EquipeNaCasca c = casca(ANDROID_11);
        c.reavaliar();
        tela.continuarDaExplicacao.run();
        c.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, false));
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
        comChaveEAviso(CHAVE_A);
        partidaNova();
        casca(ANDROID_11).sair();
        assertEquals("a revogação vai com a chave velha", 1, pedidosAoNexus.size());
        assertEquals(EnvioDePontos.NEXUS + EnvioDePontos.ROTA_REVOGAR, pedidosAoNexus.get(0)[0]);
        assertEquals(CHAVE_A, pedidosAoNexus.get(0)[1]);
        assertNull(chave.chave());
    }

    @Test
    public void semChaveNemFilaOPedidoDoAparelhoNaoMexeEmNada() throws Exception {
        // A instalação nova: nada a tirar, e nada parado.
        List<String> respostas = new ArrayList<>();
        casca(ANDROID_11).pedirAparelho(CONTA_A, respostas::add);
        assertFalse(new JSONObject(respostas.get(0)).getBoolean("temChave"));
        assertTrue(servico.chamadas.isEmpty());
        assertEquals(0, tela.fechadas);
    }

    @Test
    public void oSairTambemApagaAMarcaDosTrintaMinutos() {
        comChaveEAviso(CHAVE_A);
        marcarSemPermissao(agora);
        casca(ANDROID_11).sair();
        assertFalse("a próxima conta não espera os 30 minutos de quem saiu", daCasca().contains("sem_permissao_em"));
        assertNull(chave.chave());
        assertNull(chave.conta());
    }

    // --- o log --------------------------------------------------------------------------------------------------------

    @Test
    public void nadaDaChaveDaContaNemDoAparelhoVaiAoLog() {
        tirar(O_TEMPO_TODO);
        EquipeNaCasca c = casca(ANDROID_11);
        c.receberChave(true, CHAVE_A, CONTA_A);
        tela.continuarDoAviso.run();
        tela.continuarDaExplicacao.run();
        c.aposOPedido(Collections.singletonMap(O_TEMPO_TODO, false));
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
    public void aTelaDeVerdadeMostraOAvisoEAExplicacaoQueSoOContinuarFecha() {
        AppCompatActivity activity = activity();
        List<List<String>> pedidos = new ArrayList<>();
        EquipeNaCasca.TelaDaActivity t = new EquipeNaCasca.TelaDaActivity(activity, p -> pedidos.add(Arrays.asList(p)));
        boolean[] seguiu = {false};

        t.mostrarAviso(() -> seguiu[0] = true);
        AlertDialog aviso = ultimoDialogo();
        conferirDialogo(aviso, R.string.casca_equipe_aviso_titulo, R.string.casca_equipe_aviso_texto,
            R.string.casca_equipe_aviso_continuar);
        aviso.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(seguiu[0]);
        assertFalse(aviso.isShowing());

        t.explicarOTempoTodo(() -> t.pedir(new String[] {O_TEMPO_TODO}));
        AlertDialog explicacao = ultimoDialogo();
        assertNotEquals(aviso, explicacao);
        conferirDialogo(explicacao, R.string.casca_equipe_permissao_titulo, R.string.casca_equipe_permissao_texto,
            R.string.casca_equipe_permissao_continuar);
        explicacao.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList(Collections.singletonList(O_TEMPO_TODO)), pedidos);

        // O Sair (ou o coleta:false) com o aviso na frente: ele sai da tela.
        t.mostrarAviso(() -> fail("o aviso fechado pelo Sair não segue para a permissão"));
        AlertDialog deNovo = ultimoDialogo();
        assertTrue(deNovo.isShowing());
        t.fechar();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(deNovo.isShowing());
    }

    @Test
    public void osTextosDoAvisoDizemOQueQuandoPorQueQuemVeEPorQuantoTempo() {
        // O que a seção 4.1 do desenho manda o aviso dizer (o texto ainda vai à aprovação do dono, do RH e do jurídico).
        String aviso = app.getString(R.string.casca_equipe_aviso_texto);
        for (String trecho : new String[] {"posição deste celular", "6h", "22h", "sem pausa", "aplicativo fechado",
                "onde a equipe está", "rotas até as usinas", "PCM", "COS", "Performance", "Supervisão Campo",
                "Responsáveis UFV", "administradores do Nexus", "7 dias", "1 ano", "notificação"}) {
            assertTrue("o aviso não diz \"" + trecho + "\"", aviso.contains(trecho));
        }
        assertTrue(app.getString(R.string.casca_equipe_permissao_texto).contains("Permitir o tempo todo"));
    }
}
