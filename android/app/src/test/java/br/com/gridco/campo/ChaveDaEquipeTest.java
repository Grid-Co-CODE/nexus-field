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
