package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Base64;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** A chave do aparelho da coleta de equipe: cifrada em repouso, e o aviso aceito vale só para a chave em uso. */
@RunWith(RobolectricTestRunner.class)
public class ChaveDaEquipeTest {

    private Context ctx;
    private CifraFalsa cifra;

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
        c.guardar("chave-secreta-123");
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
        assertTrue(c.guardar("k1"));
        c.aceitarAviso();
        assertTrue(c.avisoAceito());
        assertFalse(c.guardar("k1"));
        assertTrue(c.avisoAceito());
    }

    @Test
    public void guardarOutraChaveDaTrueEZeraOAviso() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        c.guardar("k1");
        c.aceitarAviso();
        assertTrue(c.guardar("k2"));
        assertFalse(c.avisoAceito());
        assertEquals("k2", c.chave());
    }

    @Test
    public void apagarDeixaAChaveNulaEMantemOAparelho() {
        ChaveDaEquipe c = new ChaveDaEquipe(ctx, cifra);
        String aparelho = c.aparelho();
        c.guardar("k1");
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
        c.guardar("k1");
        cifra.falharAoDecifrar = true;
        assertNull(c.chave());
        assertTrue(c.guardar("k1"));
    }
}
