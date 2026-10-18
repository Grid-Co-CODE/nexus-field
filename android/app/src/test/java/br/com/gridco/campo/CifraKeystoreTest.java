package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

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

    // O que não se lê nunca mais (adulterado, outra chave) é Cifra.Ilegivel: o espelho apaga a linha. O Keystore que não
    // respondeu agora é erro comum: a linha fica para o próximo retrato.
    @Test
    public void adulteradoEOutraChaveSaoIlegiveis() {
        byte[] dados = cifra.cifrar(TEXTO);
        dados[dados.length - 1] ^= 0x01;
        try {
            cifra.decifrar(dados);
            fail("adulterado aceito");
        } catch (Cifra.Ilegivel esperado) {
            // ok
        }
        try {
            new CifraKeystore(CifraKeystoreTest::novaChave).decifrar(cifra.cifrar(TEXTO));
            fail("outra chave decifrou");
        } catch (Cifra.Ilegivel esperado) {
            // ok
        }
        try {
            cifra.decifrar(new byte[] {0, 1, 2});
            fail("formato inválido aceito");
        } catch (Cifra.Ilegivel esperado) {
            // ok
        }
    }

    @Test
    public void keystoreForaDoArNaoEIlegivel() {
        CifraKeystore fora = new CifraKeystore(() -> {
            throw new IllegalStateException("Keystore indisponível");
        }, () -> fail("o Keystore fora do ar não é motivo para apagar a chave"));
        byte[] dados = cifra.cifrar(TEXTO);
        try {
            fora.decifrar(dados);
            fail("decifrou sem chave");
        } catch (Cifra.Ilegivel errado) {
            fail("o Keystore fora do ar não diz que o dado é ilegível");
        } catch (IllegalStateException esperado) {
            // ok
        }
    }

    // A chave que o Keystore passa a recusar (o defeito pós-atualização de sistema de alguns fabricantes: InvalidKey ou
    // ProviderException no init, não a etiqueta) nunca mais funcionaria: sem refazer, a identidade deixaria de ser
    // espelhada naquele aparelho para sempre. É apagada e refeita UMA vez; o que a velha cifrou fica ilegível.
    @Test
    public void chaveQueOKeystorePassaARecusarERefeitaUmaVez() {
        SecretKey boa = novaChave();
        Deque<SecretKey> chaves = new ArrayDeque<>(Arrays.asList(CHAVE_RECUSADA, boa));
        int[] apagadas = {0};
        CifraKeystore c = new CifraKeystore(chaves::pop, () -> apagadas[0]++);

        byte[] dados = c.cifrar(TEXTO);

        assertEquals(1, apagadas[0]);
        assertEquals(TEXTO, c.decifrar(dados));
        assertEquals(TEXTO, new CifraKeystore(() -> boa).decifrar(dados));
        c.cifrar(TEXTO);
        assertEquals("a chave nova vale daí em diante", 1, apagadas[0]);
    }

    @Test
    public void aChaveRecusadaAoDecifrarERefeitaEOVelhoFicaIlegivel() {
        byte[] daVelha = cifra.cifrar(TEXTO);
        Deque<SecretKey> chaves = new ArrayDeque<>(Arrays.asList(CHAVE_RECUSADA, novaChave()));
        int[] apagadas = {0};
        CifraKeystore c = new CifraKeystore(chaves::pop, () -> apagadas[0]++);
        try {
            c.decifrar(daVelha);
            fail("a chave nova não lê o que a velha cifrou");
        } catch (Cifra.Ilegivel esperado) {
            // ok: o espelho apaga a linha, e a página regrava a identidade no próximo login
        }
        assertEquals(1, apagadas[0]);
        assertEquals(TEXTO, c.decifrar(c.cifrar(TEXTO)));
    }

    @Test
    public void aProviderExceptionNoInitTambemRefazAChave() {
        SecretKey quebrada = new SecretKey() {
            @Override
            public String getAlgorithm() {
                return "AES";
            }

            @Override
            public String getFormat() {
                return "RAW";
            }

            @Override
            public byte[] getEncoded() {
                throw new java.security.ProviderException("defeito do fabricante");
            }
        };
        Deque<SecretKey> chaves = new ArrayDeque<>(Arrays.asList(quebrada, novaChave()));
        int[] apagadas = {0};
        CifraKeystore c = new CifraKeystore(chaves::pop, () -> apagadas[0]++);
        assertEquals(TEXTO, c.decifrar(c.cifrar(TEXTO)));
        assertEquals(1, apagadas[0]);
    }

    @Test
    public void soUmaVezPorProcesso() {
        Deque<SecretKey> chaves = new ArrayDeque<>(Arrays.asList(CHAVE_RECUSADA, CHAVE_RECUSADA, CHAVE_RECUSADA));
        int[] apagadas = {0};
        CifraKeystore c = new CifraKeystore(chaves::pop, () -> apagadas[0]++);
        for (int i = 0; i < 2; i++) {
            try {
                c.cifrar(TEXTO);
                fail("a chave refeita também recusada");
            } catch (IllegalStateException esperado) {
                assertFalse(String.valueOf(esperado.getMessage()).contains("exemplo"));
            }
        }
        assertEquals("sem laço de chaves novas", 1, apagadas[0]);
    }

    @Test
    public void aEtiquetaQueNaoConfereNaoRefazAChave() {
        int[] apagadas = {0};
        SecretKey chave = novaChave();
        CifraKeystore c = new CifraKeystore(() -> chave, () -> apagadas[0]++);
        byte[] dados = c.cifrar(TEXTO);
        dados[5] ^= 0x01;
        try {
            c.decifrar(dados);
            fail("adulterado aceito");
        } catch (Cifra.Ilegivel esperado) {
            // ok
        }
        assertEquals("o dado errado não é a chave errada", 0, apagadas[0]);
        assertEquals(TEXTO, c.decifrar(c.cifrar(TEXTO)));
    }

    // Uma chave AES de 5 bytes: o init do GCM a recusa com InvalidKeyException, como o Keystore recusa a chave invalidada.
    private static final SecretKey CHAVE_RECUSADA = new SecretKeySpec(new byte[5], "AES");

    private static SecretKey novaChave() {
        try {
            KeyGenerator gerador = KeyGenerator.getInstance("AES");
            gerador.init(256);
            return gerador.generateKey();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean contem(byte[] palheiro, byte[] agulha) {
        for (int i = 0; i + agulha.length <= palheiro.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(palheiro, i, i + agulha.length), agulha)) return true;
        }
        return false;
    }
}
