package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.util.Base64;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A chave do aparelho da coleta de equipe: cifrada em repouso com a conta dela, e o aviso aceito vale só para a chave em
 * uso.
 */
@RunWith(RobolectricTestRunner.class)
public class ChaveDaEquipeTest {

    // Fictícias, no formato da página (o SHA-256 em hex minúsculo da conta Microsoft).
    private static final String CONTA_A = conta('a');
    private static final String CONTA_B = conta('b');

    private Context ctx;
    private CifraFalsa cifra;

    private static String conta(char c) {
        return new String(new char[64]).replace('\0', c);
    }

    @Before
    public void abrir() {
        ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().clear().commit();
        cifra = new CifraFalsa();
    }

    @Test
    public void oAparelhoEOMesmoEmDuasInstanciasENaoVazio() {
        String a = new ChaveDaEquipe(ctx, cifra).aparelho();
        String b = new ChaveDaEquipe(ctx, cifra).aparelho();
        assertNotNull(a);
        assertFalse(a.isEmpty());
        assertEquals(a, b);
    }

    @Test
    public void aChaveGuardadaNaoApareceEmClaroNoSharedPreferences() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("chave-secreta-123", CONTA_A);
        assertEquals("chave-secreta-123", c.chave());
        boolean comPrefixoDaCifra = false;
        for (Object v : ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).getAll().values()) {
            String s = String.valueOf(v);
            assertFalse(s.contains("chave-secreta-123"));
            if (!(v instanceof String)) continue;
            try {
                byte[] cru = Base64.decode(s, Base64.NO_WRAP);
                if (cru.length >= CifraFalsa.PREFIXO.length
                        && Arrays.equals(Arrays.copyOf(cru, CifraFalsa.PREFIXO.length), CifraFalsa.PREFIXO)) {
                    comPrefixoDaCifra = true;
                }
            } catch (IllegalArgumentException ignora) {
                // o id do aparelho não é base64 válido: não é o valor procurado
            }
        }
        assertTrue("o valor guardado deve ser o que a cifra produziu", comPrefixoDaCifra);
    }

    @Test
    public void guardarAMesmaChaveDaFalseEMantemOAviso() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        assertTrue(c.guardar("k1", CONTA_A));
        c.aceitarAviso();
        assertTrue(c.avisoAceito());
        assertFalse(c.guardar("k1", CONTA_A));
        assertTrue(c.avisoAceito());
    }

    @Test
    public void guardarOutraChaveDaTrueEZeraOAviso() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        c.aceitarAviso();
        assertTrue(c.guardar("k2", CONTA_A));
        assertFalse(c.avisoAceito());
        assertEquals("k2", c.chave());
    }

    @Test
    public void apagarDeixaAChaveNulaEMantemOAparelho() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        String aparelho = c.aparelho();
        c.guardar("k1", CONTA_A);
        c.apagar();
        assertNull(c.chave());
        assertEquals(aparelho, c.aparelho());
        assertNotEquals("", aparelho);
    }

    @Test
    public void semChaveGuardadaAChaveENula() {
        assertNull(new ChaveDaEquipe(ctx, cifra).chave());
    }

    @Test
    public void chaveIlegivelContaComoSemChaveEGuardarDaTrue() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        cifra.falharAoDecifrar = true;
        assertNull(c.chave());
        assertTrue(c.guardar("k1", CONTA_A));
    }

    // --- a conta da chave (o aparelho dividido) -----------------------------------------------------------------------

    @Test
    public void aContaFicaCifradaJuntoDaChaveEOApagarLevaAsDuas() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        assertNull("sem chave, sem conta", c.conta());
        assertFalse(c.temChaveGuardada());
        c.guardar("chave-secreta-123", CONTA_A);
        assertEquals(CONTA_A, c.conta());
        assertTrue(c.temChaveGuardada());
        // A conta aponta para a pessoa (é o resumo do oid dela): em repouso, cifrada como a chave.
        for (Object v : ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).getAll().values()) {
            assertFalse(String.valueOf(v).contains(CONTA_A));
        }
        c.apagar();
        assertNull(c.chave());
        assertNull(c.conta());
        assertFalse(c.temChaveGuardada());
    }

    @Test
    public void aMesmaChaveDeOutraContaDaTrueEZeraOAviso() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        c.aceitarAviso();
        assertTrue(c.guardar("k1", CONTA_B));
        assertFalse(c.avisoAceito());
        assertEquals(CONTA_B, c.conta());
        assertEquals("k1", c.chave());
    }

    @Test
    public void aChaveDeAntesDaContaTemAContaNula() {
        // A chave guardada antes de a conta existir (uma 1.2.0 de desenvolvimento): a conta nula, e a casca a trata como
        // a de outra conta.
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().remove("conta").commit();
        assertEquals("k1", c.chave());
        assertNull(c.conta());
        assertTrue(c.temChaveGuardada());
    }

    @Test
    public void oQuatrocentoEUmApagaSoAChaveEAContaFica() {
        // A conta é a dona da fila que o 401 deixa: a mesma conta a sobe com a chave nova, e outra conta a apaga antes.
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        String aparelho = c.aparelho();
        c.guardar("k1", CONTA_A);
        c.apagarAChave();
        assertNull(c.chave());
        assertFalse(c.temChaveGuardada());
        assertEquals(CONTA_A, c.conta());
        assertEquals(aparelho, c.aparelho());
        assertTrue("a chave nova da mesma conta é outra chave", c.guardar("k2", CONTA_A));
        c.apagar();
        assertNull("o Sair e a troca de conta levam a conta junto", c.conta());
    }

    // --- a conta conferida nesta partida do processo ------------------------------------------------------------------

    @Test
    public void aContaSoFicaConferidaPelaPaginaDestaPartida() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        assertFalse("a partida começa sem conferir", c.conferida());
        c.guardar("k1", CONTA_A);
        assertTrue("a chave que a página entrega é da conta que a trouxe", c.conferida());

        // O processo novo (o Android matou o app): a chave e a conta no disco, e nada conferido na memória.
        ChaveDaEquipe outraPartida = new ChaveDaEquipe(ctx, cifra);
        assertFalse(outraPartida.conferida());
        assertEquals("k1", outraPartida.chave());
        assertFalse("outra conta não confere", outraPartida.conferir(CONTA_B));
        assertFalse(outraPartida.conferida());
        assertTrue(outraPartida.conferir(CONTA_A));
        assertTrue(outraPartida.conferida());
        assertFalse("a conferência seguinte, de outra conta, desfaz", outraPartida.conferir(CONTA_B));
        assertFalse(outraPartida.conferida());
    }

    @Test
    public void guardarAMesmaChaveTambemConfereEACifraQueFalhaNao() {
        new ChaveDaEquipe(ctx, cifra).guardar("k1", CONTA_A);
        ChaveDaEquipe outraPartida = new ChaveDaEquipe(ctx, cifra);
        assertFalse(outraPartida.guardar("k1", CONTA_A));
        assertTrue("a página entregou a chave desta conta", outraPartida.conferida());

        ChaveDaEquipe terceira = new ChaveDaEquipe(ctx, cifra);
        cifra.falharAoCifrar = true;
        try {
            terceira.guardar("k2", CONTA_A);
            fail("a cifra que falha não guarda");
        } catch (IllegalStateException esperado) {
            // a chave nova não vai em claro para o disco
        }
        assertFalse(terceira.conferida());
    }

    @Test
    public void oQueApagaAChaveDesfazAConferencia() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        c.apagarAChave();
        assertFalse(c.conferida());
        assertFalse("sem chave, nada a conferir", c.conferir(CONTA_A));
        c.guardar("k2", CONTA_A);
        c.apagar();
        assertFalse(c.conferida());
    }

    @Test
    public void oCofreQueNaoLeAContaNaoConfere() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        cifra.keystoreFora = true;
        try {
            c.conferir(CONTA_A);
            fail("o cofre que não respondeu agora não diz de quem é a chave");
        } catch (IllegalStateException esperado) {
            // a chave e a conta continuam guardadas
        }
        assertFalse("o que estava conferido deixa de estar: nada sai até a próxima conferência", c.conferida());
        cifra.keystoreFora = false;
        assertTrue(c.conferir(CONTA_A));
    }

    // --- a conferência no disco, para o religamento sem tela ------------------------------------------------------------

    /** O processo que o Android religa sem tela (o serviço da coleta): uma ChaveDaEquipe nova que retoma a do disco. */
    private ChaveDaEquipe religadaPeloSistema() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.retomarConferencia();
        return c;
    }

    @Test
    public void oReligamentoSemTelaRetomaAConferenciaDaPaginaEATelaQueAbreADesfaz() {
        // O caso: a coleta ligada, o processo morto no meio do dia (a falta de memória, o "tirar dos recentes" de quem mata
        // o app) e o serviço religado pelo sistema, sem tela e sem página: nada subia até o técnico abrir o app de novo.
        new ChaveDaEquipe(ctx, cifra).guardar("k1", CONTA_A);
        ChaveDaEquipe religada = new ChaveDaEquipe(ctx, cifra);
        assertFalse("o processo novo começa sem conferir", religada.conferida());
        religada.retomarConferencia();
        assertTrue("o religamento sem tela retoma a conferência que a página fez", religada.conferida());

        // A tela que nasce (o load do plugin) desfaz, na memória e no disco, até a página dela conferir.
        religada.aTelaAbriu();
        assertFalse(religada.conferida());
        assertFalse("o religamento seguinte não acha a conferência de antes da tela", religadaPeloSistema().conferida());

        // A página da tela confere de novo: volta ao disco.
        ChaveDaEquipe comTela = new ChaveDaEquipe(ctx, cifra);
        comTela.aTelaAbriu();
        assertTrue(comTela.conferir(CONTA_A));
        assertTrue(religadaPeloSistema().conferida());

        // A marca não diz de quem é: nem a conta nem a chave em claro no disco.
        for (Object v : ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).getAll().values()) {
            assertFalse(String.valueOf(v).contains(CONTA_A));
            assertFalse(String.valueOf(v).contains("k1"));
        }
    }

    @Test
    public void tudoQueDesfazAConferenciaTiraTambemADoDisco() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        assertFalse("a conferência de outra conta", c.conferir(CONTA_B));
        assertFalse(religadaPeloSistema().conferida());

        assertTrue(c.conferir(CONTA_A));
        cifra.keystoreFora = true;
        try {
            c.conferir(CONTA_A);
            fail("o cofre que não respondeu agora não confere");
        } catch (IllegalStateException esperado) {
            // a chave e a conta continuam guardadas
        }
        cifra.keystoreFora = false;
        assertFalse("o cofre fora", religadaPeloSistema().conferida());

        assertTrue(c.conferir(CONTA_A));
        c.apagarAChave();
        assertFalse("o 401", religadaPeloSistema().conferida());

        c.guardar("k2", CONTA_A);
        c.apagar();
        assertFalse("o Sair, a troca de conta e o coleta:false", religadaPeloSistema().conferida());

        // Sem a chave no disco, a marca que sobrasse não confere nada.
        c.guardar("k3", CONTA_A);
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().remove("chave").commit();
        assertFalse(religadaPeloSistema().conferida());
    }

    // --- R9: a conferência que o cofre não deixou fazer ---------------------------------------------------------------

    @Test
    public void oCofreQueFalhaNaConferenciaDeixaAContaDaPaginaParaConferirDeNovo() {
        new ChaveDaEquipe(ctx, cifra).guardar("k1", CONTA_A);
        ChaveDaEquipe partida = new ChaveDaEquipe(ctx, cifra);
        assertFalse("nada a refazer sem a página", partida.precisaReconferir());
        cifra.keystoreFora = true;
        try {
            partida.conferir(CONTA_A);
            fail("o cofre que não respondeu agora não confere");
        } catch (IllegalStateException esperado) {
            // a conta fica por conferir
        }
        assertFalse(partida.conferida());
        assertTrue("o envio e a volta ao app conferem de novo", partida.precisaReconferir());
        assertEquals(CONTA_A, partida.contaDaPagina());
        try {
            partida.reconferir();
            fail("o cofre ainda fora");
        } catch (IllegalStateException esperado) {
            // segue por refazer
        }
        assertTrue(partida.precisaReconferir());

        cifra.keystoreFora = false;
        assertTrue("o cofre voltou: a mesma conta confere", partida.reconferir());
        assertTrue(partida.conferida());
        assertFalse(partida.precisaReconferir());
        assertTrue("e vai ao disco, como a conferência da página", religadaPeloSistema().conferida());
    }

    @Test
    public void aReconferenciaComOutraContaGuardadaNaoConfereESegueAteATroca() {
        // B usou o aparelho sem o Sair; o cofre não respondeu no pedido do aparelho de A, e voltou com a conta de B guardada.
        new ChaveDaEquipe(ctx, cifra).guardar("k1", CONTA_B);
        ChaveDaEquipe partida = new ChaveDaEquipe(ctx, cifra);
        cifra.keystoreFora = true;
        try {
            partida.conferir(CONTA_A);
            fail("o cofre que não respondeu agora não confere");
        } catch (IllegalStateException esperado) {
            // por refazer
        }
        cifra.keystoreFora = false;
        assertFalse("outra conta guardada: nada sai", partida.reconferir());
        assertFalse(partida.conferida());
        assertTrue("segue por refazer até a troca de conta da volta ao app", partida.precisaReconferir());
        assertFalse(religadaPeloSistema().conferida());
        partida.apagar(); // a troca de conta
        assertFalse(partida.precisaReconferir());
        assertNull(partida.contaDaPagina());
    }

    @Test
    public void aConferenciaDefinitivaOuAChaveNovaTiramOQueEstavaPorRefazer() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        cifra.keystoreFora = true;
        try {
            c.conferir(CONTA_A);
            fail("o cofre fora");
        } catch (IllegalStateException esperado) {
            // por refazer
        }
        cifra.keystoreFora = false;
        assertTrue(c.precisaReconferir());
        c.guardar("k2", CONTA_A); // a chave nova que a página entregou
        assertFalse(c.precisaReconferir());
        assertTrue(c.conferida());

        cifra.keystoreFora = true;
        try {
            c.conferir(CONTA_A);
            fail("o cofre fora");
        } catch (IllegalStateException esperado) {
            // por refazer
        }
        cifra.keystoreFora = false;
        assertFalse("a conferência seguinte, de outra conta, é definitiva", c.conferir(CONTA_B));
        assertFalse(c.precisaReconferir());

        c.aTelaAbriu();
        assertNull("a tela nova traz a conta dela", c.contaDaPagina());
    }

    @Test
    public void aContaIlegivelContaComoSemContaEOCofreForaNaoEUmaContaQueFalta() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        cifra.falharAoDecifrar = true;
        assertNull(c.conta());
        cifra.falharAoDecifrar = false;
        cifra.keystoreFora = true;
        try {
            c.conta();
            fail("o cofre que não respondeu agora não diz que a conta falta");
        } catch (IllegalStateException esperado) {
            // a conta continua guardada
        }
        assertTrue("sem decifrar nada", c.temChaveGuardada());
        cifra.keystoreFora = false;
        assertEquals(CONTA_A, c.conta());
    }
}
