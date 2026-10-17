package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.Before;
import org.junit.Test;

/**
 * O Android Keystore não existe no JVM do teste, mas o que importa provar aqui é o nosso código em volta dele: o formato
 * (tamanho do IV + IV + texto cifrado com a etiqueta do GCM), o IV novo a cada cifra e a recusa do que foi adulterado.
 * A chave de software faz o mesmo papel da do Keystore: AES de 256 bits, GCM. Gerar a chave no Keystore de verdade
 * (KeyGenParameterSpec) só se vê no aparelho.
 */
public class CifraKeystoreTest {

    private static final String TEXTO = "{\"email\":\"tecnico@exemplo.com.br\",\"nome\":\"Ação Ç 😀\"}";

    private CifraKeystore cifra;

    @Before
    public void preparar() throws Exception {
        KeyGenerator gerador = KeyGenerator.getInstance("AES");
        gerador.init(256);
        final SecretKey chave = gerador.generateKey();
        cifra = new CifraKeystore(() -> chave);
    }

    @Test
    public void cifrarEDecifrarVoltaAoTexto() {
        assertEquals(TEXTO, cifra.decifrar(cifra.cifrar(TEXTO)));
        assertEquals("", cifra.decifrar(cifra.cifrar("")));
    }

    @Test
    public void oCifradoNaoTemOTextoNemOEmail() {
        byte[] dados = cifra.cifrar(TEXTO);

        assertFalse(contem(dados, "tecnico@exemplo".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Arrays.equals(TEXTO.getBytes(StandardCharsets.UTF_8), dados));
    }

    // GCM com o mesmo IV duas vezes quebra a segurança da chave. O IV é do provedor (aleatório), nunca nosso.
    @Test
    public void cadaCifraTemIvNovo() {
        byte[] a = cifra.cifrar(TEXTO);
        byte[] b = cifra.cifrar(TEXTO);

        assertNotEquals(Arrays.toString(a), Arrays.toString(b));
        assertEquals(TEXTO, cifra.decifrar(a));
        assertEquals(TEXTO, cifra.decifrar(b));
    }

    // Formato: 1 byte com o tamanho do IV, o IV (12 bytes no GCM), depois o texto cifrado com a etiqueta de 16 bytes.
    @Test
    public void formatoTemOIvJuntoDoTextoCifrado() {
        byte[] dados = cifra.cifrar(TEXTO);
        int tamanhoDoTexto = TEXTO.getBytes(StandardCharsets.UTF_8).length;

        assertEquals(12, dados[0]);
        assertEquals(1 + 12 + tamanhoDoTexto + 16, dados.length);
    }

    @Test
    public void adulteracaoELancada() {
        byte[] dados = cifra.cifrar(TEXTO);
        for (int posicao : new int[] {1, 13, dados.length - 1}) { // no IV, no miolo e na etiqueta
            byte[] adulterado = dados.clone();
            adulterado[posicao] ^= 0x01;
            try {
                cifra.decifrar(adulterado);
                fail("byte " + posicao + " adulterado e aceito");
            } catch (IllegalStateException esperado) {
                assertFalse(String.valueOf(esperado.getMessage()).contains("exemplo"));
            }
        }
    }

    @Test
    public void dadosTruncadosOuInvalidosSaoLancados() {
        byte[] dados = cifra.cifrar(TEXTO);
        byte[][] ruins = {
            new byte[0],
            new byte[] {12},
            Arrays.copyOf(dados, 5),
            Arrays.copyOf(dados, dados.length - 1),
            new byte[] {0, 1, 2, 3}, // tamanho de IV zero
            new byte[] {(byte) 200, 1, 2, 3}, // tamanho de IV maior que os dados
        };
        for (byte[] ruim : ruins) {
            try {
                cifra.decifrar(ruim);
                fail("aceitou " + Arrays.toString(ruim));
            } catch (IllegalStateException esperado) {
                // ok
            }
        }
        try {
            cifra.decifrar(null);
            fail("aceitou null");
        } catch (IllegalStateException esperado) {
            // ok
        }
    }

    @Test
    public void chaveDiferenteNaoDecifra() throws Exception {
        KeyGenerator gerador = KeyGenerator.getInstance("AES");
        gerador.init(256);
        final SecretKey outra = gerador.generateKey();
        CifraKeystore outraCifra = new CifraKeystore(() -> outra);

        try {
            outraCifra.decifrar(cifra.cifrar(TEXTO));
            fail("outra chave decifrou");
        } catch (IllegalStateException esperado) {
            // ok
        }
    }

    @Test
    public void aMesmaChaveEmOutraInstanciaDecifra() throws Exception {
        KeyGenerator gerador = KeyGenerator.getInstance("AES");
        gerador.init(256);
        final SecretKey chave = gerador.generateKey();
        byte[] dados = new CifraKeystore(() -> chave).cifrar(TEXTO);

        assertEquals(TEXTO, new CifraKeystore(() -> chave).decifrar(dados));
    }

    private static boolean contem(byte[] palheiro, byte[] agulha) {
        for (int i = 0; i + agulha.length <= palheiro.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(palheiro, i, i + agulha.length), agulha)) return true;
        }
        return false;
    }
}
