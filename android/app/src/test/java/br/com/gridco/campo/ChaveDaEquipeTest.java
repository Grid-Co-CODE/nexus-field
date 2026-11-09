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
 * A chave do aparelho da coleta de equipe: cifrada em repouso com a conta dela, e o termo de uso aceito vale para a conta
 * (a chave nova da mesma conta o mantém; outra conta o pede de novo).
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
    public void guardarAMesmaChaveDaFalseEMantemOTermo() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        assertTrue(c.guardar("k1", CONTA_A));
        assertFalse("a conta nova ainda não aceitou", c.termoAceito());
        c.aceitarTermo();
        assertTrue(c.termoAceito());
        assertFalse(c.guardar("k1", CONTA_A));
        assertTrue(c.termoAceito());
    }

    @Test
    public void aChaveNovaDaMesmaContaDaTrueEMantemOTermo() {
        // O termo é da conta (R10): a chave revogada e refeita, ou a do 401, não mostra o termo de novo a quem já o aceitou.
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        c.aceitarTermo();
        assertTrue(c.guardar("k2", CONTA_A));
        assertTrue("a mesma conta", c.termoAceito());
        assertEquals("k2", c.chave());
        c.apagarAChave(); // o 401 do envio
        assertTrue("o 401 apaga só a chave: o termo fica com a conta", c.termoAceito());
        assertTrue(c.guardar("k3", CONTA_A));
        assertTrue(c.termoAceito());
    }

    @Test
    public void oTermoAceitoEDaContaAOutraContaPedeDeNovo() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        c.aceitarTermo();
        c.apagarAChave();
        assertTrue("a chave de outra conta, depois do 401 de A", c.guardar("k2", CONTA_B));
        assertFalse(c.termoAceito());
        c.aceitarTermo();
        c.apagar(); // o Sair, a troca de conta e o coleta:false
        assertFalse(c.termoAceito());
        // O aceite que chegasse sem chave guardada (o Li e aceito no meio do Sair) não vale para a conta seguinte.
        c.aceitarTermo();
        c.guardar("k3", CONTA_A);
        assertFalse(c.termoAceito());
        // Nem a conta que a cifra não lê: não se sabe se é a mesma.
        c.aceitarTermo();
        cifra.falharAoDecifrar = true;
        c.guardar("k4", CONTA_A);
        cifra.falharAoDecifrar = false;
        assertFalse(c.termoAceito());
    }

    @Test
    public void oAvisoAceitoDeAntesDoTermoNaoValeComoOTermo() {
        // O aparelho de prova que aceitou o aviso de antes do termo (a marca "aviso_aceito"): ele não leu o termo.
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().putBoolean("aviso_aceito", true).commit();
        assertFalse(c.termoAceito());
    }

    @Test
    public void oAceiteGuardaAVersaoDoTermo() {
        // R11: o aceite é da versão do texto (a de 09/11/2026): mudar o texto é mudar a constante, e quem aceitou a versão
        // de antes vê o termo de novo.
        assertEquals("1", ChaveDaEquipe.TERMO_VERSAO);
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        c.aceitarTermo();
        assertEquals(ChaveDaEquipe.TERMO_VERSAO,
            ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).getString("termo_aceito", null));
        assertTrue(c.termoAceito());
    }

    @Test
    public void oAceiteDeOutraVersaoDoTermoNaoVale() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().putString("termo_aceito", "0").commit();
        assertFalse(c.termoAceito());
        c.aceitarTermo();
        assertTrue("o aceite de agora vale", c.termoAceito());
    }

    @Test
    public void aMarcaDoTermoDeAntesDaVersaoNaoVale() {
        // A marca booleana "termo_aceito" da Task 8, que só existiu nos aparelhos de prova: quem aceitou ali leu o termo
        // curto, e não o aviso de privacidade inteiro.
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().putBoolean("termo_aceito", true).commit();
        assertFalse(c.termoAceito());
        c.aceitarTermo(); // a versão de agora toma o lugar da marca velha
        assertTrue(c.termoAceito());
        assertTrue(c.guardar("k2", CONTA_A));
        assertTrue("a chave nova da mesma conta mantém o aceite da versão", c.termoAceito());
        // Outra conta, com a marca velha: também não vale, e a troca de conta a apaga.
        ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).edit().putBoolean("termo_aceito", true).commit();
        assertTrue(c.guardar("k3", CONTA_B));
        assertFalse(c.termoAceito());
        assertFalse(ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).contains("termo_aceito"));
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
    public void aMesmaChaveDeOutraContaDaTrueEZeraOTermo() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        c.aceitarTermo();
        assertTrue(c.guardar("k1", CONTA_B));
        assertFalse(c.termoAceito());
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

    // --- a conferência é só da memória -------------------------------------------------------------------------------

    @Test
    public void aConferenciaNaoVaiAoDiscoEOProcessoNovoComecaSemEla() {
        // O serviço não volta sem a tela (R10): nenhum processo sem página retoma a conferência de antes, e o disco não a
        // guarda. Antes, a marca "conferida" ia ao disco para o religamento do sistema.
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1", CONTA_A);
        assertTrue(c.conferir(CONTA_A));
        assertFalse(ctx.getSharedPreferences("equipe", Context.MODE_PRIVATE).contains("conferida"));
        assertFalse("o processo novo", new ChaveDaEquipe(ctx, cifra).conferida());
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
