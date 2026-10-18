package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Test;

/**
 * O voltar do Android: com a página do App aberta, o nativo pergunta a ela (window.__gcCascaVoltar) e só manda o app
 * para o fundo quando ela diz que não havia nada aberto; fora dela (a tela de erro, o login da Microsoft) vai direto ao
 * fundo. Nunca fecha a tela. A página, o relógio e o "ir para o fundo" são falsos; a WebView de verdade e o gesto do
 * Android só se veem no aparelho.
 */
public class VoltarTest {

    private static final String ORIGEM = "https://gridco-campo-mw.azurewebsites.net";
    private static final String APP = ORIGEM + "/api/app";

    private PaginaFalsa pagina;
    private AgendaFalsa agenda;
    private int minimizou;
    private Voltar voltar;

    @Before
    public void preparar() {
        pagina = new PaginaFalsa();
        agenda = new AgendaFalsa();
        minimizou = 0;
        voltar = new Voltar(ORIGEM, pagina, agenda, () -> minimizou++);
        pagina.url = APP;
    }

    // ---------------------------------------------------------------- regras puras

    @Test
    public void soATrueDaPaginaConsome() {
        assertTrue(Voltar.consumiu("true"));
        // O evaluateJavascript devolve o JSON do resultado: o resto (false, a página sem o script, uma string) minimiza.
        for (String outra : new String[] {"false", "null", null, "", "\"true\"", "1", "True", " true"}) {
            assertFalse("consumiu " + outra, Voltar.consumiu(outra));
        }
    }

    @Test
    public void soAPaginaDoAppNaOrigemEPerguntada() {
        assertTrue(Voltar.eDoApp(APP, ORIGEM));
        assertTrue(Voltar.eDoApp(APP + "?x=1", ORIGEM));
        assertTrue(Voltar.eDoApp(APP + "#code=abc", ORIGEM));
        assertTrue(Voltar.eDoApp(APP + "/", ORIGEM));
        assertTrue("a origem com barra no fim vale igual", Voltar.eDoApp(APP, ORIGEM + "/"));

        assertFalse("a tela de erro local", Voltar.eDoApp("https://localhost/erro.html", ORIGEM));
        assertFalse("outra página da origem", Voltar.eDoApp(ORIGEM + "/erro.html", ORIGEM));
        assertFalse("o login da Microsoft", Voltar.eDoApp("https://login.microsoftonline.com/common/oauth2", ORIGEM));
        assertFalse("caminho que só começa igual", Voltar.eDoApp(ORIGEM + "/api/appx", ORIGEM));
        assertFalse("host que só começa igual", Voltar.eDoApp(ORIGEM + ".exemplo.com.br/api/app", ORIGEM));
        assertFalse("sem TLS", Voltar.eDoApp("http://gridco-campo-mw.azurewebsites.net/api/app", ORIGEM));
        assertFalse("sem página", Voltar.eDoApp(null, ORIGEM));
        assertFalse("sem origem (a WebView antiga, sem server.url)", Voltar.eDoApp(APP, null));
        assertFalse(Voltar.eDoApp(APP, ""));
    }

    @Test
    public void aPerguntaChamaAFuncaoDaPaginaSemDeixarErroEscapar() {
        assertTrue(Voltar.PERGUNTA.contains("window.__gcCascaVoltar"));
        assertTrue("o erro da página vira false, não exceção no nativo", Voltar.PERGUNTA.contains("catch"));
        assertFalse("sem aspas: a pergunta vai como está no evaluateJavascript", Voltar.PERGUNTA.contains("\""));
        assertEquals(1_500L, Voltar.PRAZO_MS);
    }

    // ---------------------------------------------------------------- o fluxo

    @Test
    public void aPaginaQueFechouUmaCamadaSeguraOApp() {
        voltar.aoVoltar();
        assertEquals(1, pagina.perguntas.size());
        assertEquals(Voltar.PERGUNTA, pagina.perguntas.get(0));
        pagina.responder("true");
        assertEquals(0, minimizou);
        agenda.passar(Voltar.PRAZO_MS);
        assertEquals("o prazo da pergunta já respondida não minimiza", 0, minimizou);
    }

    @Test
    public void nadaAbertoNaPaginaMandaParaOFundo() {
        voltar.aoVoltar();
        pagina.responder("false");
        assertEquals(1, minimizou);

        voltar.aoVoltar();
        pagina.responder("null"); // a página sem o script de início
        assertEquals(2, minimizou);
        assertEquals("cada voltar é uma pergunta", 2, pagina.perguntas.size());
    }

    @Test
    public void foraDaPaginaDoAppVaiDiretoParaOFundoSemPerguntar() {
        for (String url : new String[] {"https://localhost/erro.html", "https://login.microsoftonline.com/x", null}) {
            pagina.url = url;
            voltar.aoVoltar();
        }
        assertEquals(3, minimizou);
        assertEquals(0, pagina.perguntas.size());
    }

    @Test
    public void umaPerguntaPorVez() {
        voltar.aoVoltar();
        voltar.aoVoltar(); // o segundo toque enquanto a página pensa
        voltar.aoVoltar();
        assertEquals(1, pagina.perguntas.size());
        assertEquals(0, minimizou);
        pagina.responder("true");
        voltar.aoVoltar(); // respondida, o próximo pergunta de novo
        assertEquals(2, pagina.perguntas.size());
    }

    @Test
    public void aPaginaQueNaoRespondeEmUmSegundoEMeioMinimiza() {
        voltar.aoVoltar();
        agenda.passar(Voltar.PRAZO_MS - 1);
        assertEquals(0, minimizou);
        agenda.passar(1);
        assertEquals(1, minimizou);
        // A resposta atrasada (o renderer destravou) não minimiza de novo nem conta para a pergunta seguinte.
        pagina.responder("false");
        assertEquals(1, minimizou);
        voltar.aoVoltar();
        assertEquals("o prazo solta a vez", 2, pagina.perguntas.size());
        pagina.responder("true");
        assertEquals(1, minimizou);
    }

    @Test
    public void aRespostaAtrasadaDeUmaPerguntaVelhaNaoDecideANova() {
        voltar.aoVoltar();
        agenda.passar(Voltar.PRAZO_MS); // a 1ª venceu: minimizou
        voltar.aoVoltar(); // a 2ª está no ar
        pagina.responderA(0, "false"); // chega a resposta da 1ª
        assertEquals(1, minimizou);
        pagina.responderA(1, "true");
        assertEquals(1, minimizou);
    }

    @Test
    public void aWebViewQueLancaAoPerguntarMinimizaESoltaAVez() {
        pagina.lancar = new IllegalStateException("WebView destruída");
        voltar.aoVoltar();
        assertEquals(1, minimizou);
        pagina.lancar = null;
        voltar.aoVoltar();
        assertEquals(1, pagina.perguntas.size());
    }

    // ---------------------------------------------------------------- apoio

    private static final class PaginaFalsa implements Voltar.Pagina {
        String url;
        RuntimeException lancar;
        final List<String> perguntas = new ArrayList<>();
        final List<Consumer<String>> respostas = new ArrayList<>();

        @Override
        public String url() {
            return url;
        }

        @Override
        public void perguntar(String js, Consumer<String> resposta) {
            if (lancar != null) throw lancar;
            perguntas.add(js);
            respostas.add(resposta);
        }

        /** Responde a última pergunta. */
        void responder(String r) {
            responderA(respostas.size() - 1, r);
        }

        void responderA(int i, String r) {
            respostas.get(i).accept(r);
        }
    }

    /** O postDelayed da thread de UI, com um relógio que só anda quando o teste manda. */
    private static final class AgendaFalsa implements Voltar.Agenda {
        private long agora = 0;
        private final List<long[]> quando = new ArrayList<>();
        private final List<Runnable> tarefas = new ArrayList<>();

        @Override
        public void depois(long ms, Runnable tarefa) {
            quando.add(new long[] {agora + ms});
            tarefas.add(tarefa);
        }

        void passar(long ms) {
            agora += ms;
            for (int i = 0; i < tarefas.size(); i++) {
                if (tarefas.get(i) != null && quando.get(i)[0] <= agora) {
                    Runnable t = tarefas.get(i);
                    tarefas.set(i, null);
                    t.run();
                }
            }
        }
    }
}
