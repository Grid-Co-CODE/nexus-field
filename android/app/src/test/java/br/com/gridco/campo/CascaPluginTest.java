package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * As regras do plugin que não dependem da WebView: o endereço da navegação como vai ao log de depuração e a fila da OS
 * que o toque na emergência pede para abrir ({@link CascaPlugin.AberturaDaOS}, com a página e o relógio falsos). O resto
 * (script, canal, recarga, recriação, Custom Tab) só se vê no aparelho. Os e-mails abaixo são fictícios.
 */
@RunWith(RobolectricTestRunner.class)
public class CascaPluginTest {

    private static String log(String url) {
        return CascaPlugin.navegacaoParaOLog(Uri.parse(url));
    }

    @Test
    public void userAgentTerminaComNexusFieldEAVersao() {
        // Os logs do servidor separam o app do navegador por este fim (o nome do aplicativo é Nexus Field desde 17/10).
        assertEquals("Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile Safari/537.36 NexusField/1.2.3",
            CascaPlugin.userAgent("Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile Safari/537.36", "1.2.3"));
        assertEquals("x NexusField/0.0.0-dev", CascaPlugin.userAgent("x", "0.0.0-dev"));
    }

    @Test
    public void semQueryNemFragmentoOEnderecoVaiInteiro() {
        assertEquals("https://gridco-campo-mw.azurewebsites.net/api/app",
            log("https://gridco-campo-mw.azurewebsites.net/api/app"));
        assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/authorize/",
            log("https://login.microsoftonline.com/common/oauth2/v2.0/authorize/"));
    }

    @Test
    public void queryEFragmentoViramComParametros() {
        // O retorno do login traz código e estado; o Fracttal, o token.
        String query = log("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=x&state=y");
        assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/authorize (com parâmetros)", query);
        String fragmento = log("https://gridco-campo-mw.azurewebsites.net/api/app#code=abc&state=y");
        assertEquals("https://gridco-campo-mw.azurewebsites.net/api/app (com parâmetros)", fragmento);
        for (String s : new String[] {query, fragmento}) {
            assertFalse(s, s.contains("state"));
            assertFalse(s, s.contains("code"));
        }
    }

    @Test
    public void emailNoCaminhoNoUsuarioOuCodificadoNaoVaiAoLog() {
        assertEquals("https://exemplo.com.br/fotos/<e-mail>/1",
            log("https://exemplo.com.br/fotos/tecnico.ficticio@exemplo.com.br/1"));
        assertEquals("https://exemplo.com.br/fotos/<e-mail>",
            log("https://exemplo.com.br/fotos/tecnico.ficticio%40exemplo.com.br"));
        // user@host: o getHost() não traz o usuário.
        assertEquals("https://exemplo.com.br/a", log("https://tecnico.ficticio@exemplo.com.br/a"));
        // Nome de chave do App e pedaço com caractere fora do comum (aspas, espaço) também não.
        assertEquals("https://exemplo.com.br/<chave>/…", log("https://exemplo.com.br/gc_ronda_x/'Fulano%20Silva'"));
        // Endereço opaco (mailto:, data:, javascript:): o que vem depois do esquema é dado.
        assertEquals("mailto:…", log("mailto:tecnico.ficticio@exemplo.com.br"));
        assertEquals("(sem endereço)", CascaPlugin.navegacaoParaOLog(null));
    }

    // A tela recriada (o renderer que morreu) faz um plugin novo. Com uma thread do espelho por plugin, a velha ainda
    // esvaziava a fila dela enquanto a nova começava, e mensagens da página velha caíam depois das da nova no mesmo
    // espelho. A thread é uma por processo, como o espelho e os disjuntores, e não é desligada quando a tela sai.
    @Test
    public void aThreadDoEspelhoEUmaPorProcessoESegueDepoisDaTelaSair() throws Exception {
        java.util.concurrent.ExecutorService a = CascaPlugin.threadDoEspelho();
        java.util.concurrent.ExecutorService b = CascaPlugin.threadDoEspelho();
        assertSame(a, b);
        assertFalse(a.isShutdown());
        java.util.concurrent.Future<String> nome = a.submit(() -> Thread.currentThread().getName());
        assertEquals("GcCasca-espelho", nome.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A OS que o toque na emergência pede para abrir
    // ---------------------------------------------------------------------------------------------------------------

    /** A WebView falsa: guarda o que o plugin mandou rodar na página. */
    private static final class PaginaFalsa implements CascaPlugin.AberturaDaOS.Pagina {
        final List<String> scripts = new ArrayList<>();

        @Override
        public void executar(String js) {
            scripts.add(js);
        }
    }

    /** O postDelayed falso: guarda a tarefa e o atraso, e só roda quando o teste manda. */
    private static final class AgendaFalsa implements CascaPlugin.AberturaDaOS.Agenda {
        final List<Long> atrasos = new ArrayList<>();
        private final List<Runnable> tarefas = new ArrayList<>();

        @Override
        public void depois(long ms, Runnable tarefa) {
            atrasos.add(ms);
            tarefas.add(tarefa);
        }

        /** Roda o que está agendado (o que as tarefas agendarem de novo fica para a próxima rodada). */
        void rodar() {
            List<Runnable> agora = new ArrayList<>(tarefas);
            tarefas.clear();
            for (Runnable t : agora) t.run();
        }

        /** Roda só a tarefa mais antiga (a folga de uma carga que a seguinte já passou). */
        void rodarUma() {
            tarefas.remove(0).run();
        }

        int pendentes() {
            return tarefas.size();
        }
    }

    private final PaginaFalsa pagina = new PaginaFalsa();
    private final AgendaFalsa agenda = new AgendaFalsa();
    private final CascaPlugin.AberturaDaOS abertura = new CascaPlugin.AberturaDaOS(pagina, agenda);

    private static String roda(String os) {
        return "window.__gcCascaAbrirOS&&window.__gcCascaAbrirOS('" + os + "')";
    }

    @Test
    public void aOsInvalidaERecusadaENuncaChegaAPagina() {
        // O extra do Intent vem de fora (a MainActivity é exportada) e o texto vira JavaScript: só de 1 a 12 dígitos ASCII.
        String[] invalidas = {
            null, "", " ", "12a", "abc", "1'); fecharTudo('", " 12", "12 ", "12\n", "-1", "1.5",
            "1234567890123",          // 13 dígitos
            "１２３",     // dígitos de largura total
            "١٢٣",     // dígitos arábico-índicos
        };
        abertura.carregou();
        agenda.rodar(); // a folga passou: a página está pronta, e é aqui que uma OS recusada faria estrago
        int agendadasAntes = agenda.atrasos.size();
        for (String os : invalidas) {
            assertFalse(String.valueOf(os), abertura.pedir(os));
        }
        agenda.rodar();
        assertTrue("nada foi executado", pagina.scripts.isEmpty());
        assertEquals("nem agendado", agendadasAntes, agenda.atrasos.size());
        // As bordas válidas.
        assertTrue(abertura.pedir("1"));
        assertTrue(abertura.pedir("123456789012"));
    }

    @Test
    public void oPedidoFeitoAntesDaCargaEGuardadoEEntregueDepoisDaFolga() {
        assertTrue(abertura.pedir("12345"));
        agenda.rodar();
        assertTrue("a página ainda não carregou: nada roda", pagina.scripts.isEmpty());
        assertEquals("nem fica agendado", 0, agenda.atrasos.size());

        abertura.carregou();
        assertTrue("a folga de 1,5 s depois da carga: o App ainda está montando a tela", pagina.scripts.isEmpty());
        assertEquals(Collections.singletonList(1_500L), agenda.atrasos);

        agenda.rodar();
        assertEquals(Collections.singletonList(roda("12345")), pagina.scripts);
    }

    @Test
    public void soOUltimoPedidoAntesDaCargaVale() {
        assertTrue(abertura.pedir("111"));
        assertTrue(abertura.pedir("222"));
        abertura.carregou();
        agenda.rodar();
        assertEquals(Collections.singletonList(roda("222")), pagina.scripts);
    }

    @Test
    public void comAPaginaProntaOPedidoVaiNaHoraSemFolgaNova() {
        // O toque com o app aberto: a página carregou há tempo, e a folga dela já passou.
        abertura.carregou();
        agenda.rodar();
        assertTrue("sem pedido, a folga não entrega nada", pagina.scripts.isEmpty());
        assertTrue(abertura.pedir("4521"));
        assertEquals("a folga da carga, e depois a entrega na hora", Arrays.asList(1_500L, 0L), agenda.atrasos);
        agenda.rodar();
        assertEquals(Collections.singletonList(roda("4521")), pagina.scripts);
    }

    @Test
    public void comAPaginaNaFolgaOPedidoEsperaAFolgaTerminar() {
        // O toque logo depois de a página carregar: o App ainda está montando a tela, e o pedido não pula a folga.
        abertura.carregou();
        assertTrue(abertura.pedir("4521"));
        assertEquals("nada novo agendado: a entrega é a da folga", Collections.singletonList(1_500L), agenda.atrasos);
        assertTrue(pagina.scripts.isEmpty());
        agenda.rodar();
        assertEquals(Collections.singletonList(roda("4521")), pagina.scripts);
    }

    @Test
    public void umaOsPedidaNaFolgaNaoDeixaAMaisVelhaPassarPorCima() {
        // O toque em A antes da carga, a carga, e o toque em B antes de A ser entregue: B é o último e é o que aparece.
        assertTrue(abertura.pedir("111"));
        abertura.carregou();
        assertTrue(abertura.pedir("222"));
        agenda.rodar();
        assertEquals(Collections.singletonList(roda("222")), pagina.scripts);
        agenda.rodar();
        assertEquals("uma entrega só", 1, pagina.scripts.size());
    }

    @Test
    public void aPaginaQueRecomecaAntesDaEntregaGuardaOPedidoParaACargaSeguinte() {
        // O login da Microsoft logo depois da carga do App: a entrega que vence na página de login não acharia o campo.
        assertTrue(abertura.pedir("12345"));
        abertura.carregou();
        abertura.comecouACarregar();
        agenda.rodar();
        assertTrue("a página nova ainda não carregou", pagina.scripts.isEmpty());

        abertura.carregou();
        agenda.rodar();
        assertEquals(Collections.singletonList(roda("12345")), pagina.scripts);
    }

    @Test
    public void aFolgaDeUmaCargaQueAConseguinteJaPassouNaoLiberaAEntrega() {
        // O login silencioso: o App carrega, vai à Microsoft e volta em ~1 s. A folga da primeira carga vence quando a
        // segunda acabou de carregar, e entregar ali seria entregar a uma página que ainda não montou a tela.
        assertTrue(abertura.pedir("12345"));
        abertura.carregou();
        abertura.comecouACarregar();
        abertura.carregou();
        assertEquals(2, agenda.pendentes());
        agenda.rodarUma(); // a folga da primeira
        assertTrue("a folga da carga velha não vale", pagina.scripts.isEmpty());
        agenda.rodarUma(); // a folga da segunda
        assertEquals(Collections.singletonList(roda("12345")), pagina.scripts);
    }

    @Test
    public void umPedidoComAPaginaSendoTrocadaEsperaACargaSeguinte() {
        // O toque com o app aberto, justo quando a página recarrega (ou vai ao login): "pronta" era de uma página que saiu.
        abertura.carregou();
        agenda.rodar();
        abertura.comecouACarregar();
        assertTrue(abertura.pedir("77"));
        agenda.rodar();
        assertTrue("a página nova ainda não carregou: nada roda", pagina.scripts.isEmpty());

        abertura.carregou();
        agenda.rodar();
        assertEquals(Collections.singletonList(roda("77")), pagina.scripts);
    }

    @Test
    public void umaOsEntregueNaoVoltaNasCargasSeguintes() {
        assertTrue(abertura.pedir("12345"));
        abertura.carregou();
        agenda.rodar();
        assertEquals(1, pagina.scripts.size());

        abertura.comecouACarregar();
        abertura.carregou();
        agenda.rodar();
        assertEquals("a recarga da página não abre a OS de novo", 1, pagina.scripts.size());
        assertEquals(0, agenda.pendentes());
    }

    @Test
    public void oScriptSoLevaDigitos() {
        // Defesa em profundidade: o que vai ao evaluateJavascript é montado só de uma OS que passou pela validação.
        assertEquals(roda("987"), CascaPlugin.AberturaDaOS.script("987"));
        // E o script recusa, ele mesmo, o que não é número de OS (quem chamar sem passar pelo pedir não monta JavaScript).
        for (String ruim : new String[] {null, "", "1'); fecharTudo('", "12a", "1234567890123"}) {
            try {
                CascaPlugin.AberturaDaOS.script(ruim);
                fail("o script aceitou " + ruim);
            } catch (IllegalArgumentException esperado) {
                assertFalse("a mensagem não leva o texto recusado", String.valueOf(esperado.getMessage()).contains("fecharTudo"));
            }
        }
        assertTrue(abertura.pedir("987"));
        abertura.carregou();
        agenda.rodar();
        for (String js : pagina.scripts) {
            assertTrue(js, js.matches("window\\.__gcCascaAbrirOS&&window\\.__gcCascaAbrirOS\\('\\d{1,12}'\\)"));
        }
    }
}
