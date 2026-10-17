package br.com.gridco.campo;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A cifra dos testes: XOR com prefixo, só para o teste provar que o valor cru no banco NÃO é o texto e que o caminho
 * decifrar(cifrar(x)) == x passa por ela. O AES/GCM de verdade (Android Keystore) não roda no Robolectric; o formato dele
 * é provado no CifraKeystoreTest, com uma chave de software.
 */
final class CifraFalsa implements Cifra {
    static final byte[] PREFIXO = "FALSA:".getBytes(StandardCharsets.US_ASCII);
    private static final int MASCARA = 0x5A;

    /** Quantas vezes cada operação foi chamada: o teste confere que só a identidade passou pela cifra. */
    int cifradas = 0;
    int decifradas = 0;

    /** Quando ligado, decifrar falha como falharia uma chave do Keystore que o aparelho perdeu. */
    boolean falharAoDecifrar = false;

    @Override
    public byte[] cifrar(String texto) {
        cifradas++;
        byte[] puro = texto.getBytes(StandardCharsets.UTF_8);
        byte[] saida = new byte[PREFIXO.length + puro.length];
        System.arraycopy(PREFIXO, 0, saida, 0, PREFIXO.length);
        for (int i = 0; i < puro.length; i++) saida[PREFIXO.length + i] = (byte) (puro[i] ^ MASCARA);
        return saida;
    }

    @Override
    public String decifrar(byte[] dados) {
        decifradas++;
        if (falharAoDecifrar) throw new IllegalStateException("chave perdida");
        if (dados.length < PREFIXO.length || !Arrays.equals(Arrays.copyOf(dados, PREFIXO.length), PREFIXO)) {
            throw new IllegalStateException("não foi cifrado pela cifra falsa");
        }
        byte[] puro = new byte[dados.length - PREFIXO.length];
        for (int i = 0; i < puro.length; i++) puro[i] = (byte) (dados[PREFIXO.length + i] ^ MASCARA);
        return new String(puro, StandardCharsets.UTF_8);
    }
}
