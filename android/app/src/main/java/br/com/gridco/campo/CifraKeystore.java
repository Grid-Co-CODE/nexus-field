package br.com.gridco.campo;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.function.Supplier;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES/GCM/NoPadding com a chave `gc_casca_ident` no AndroidKeyStore. A chave nasce no primeiro uso, não sai do cofre
 * do aparelho e não vai a backup nenhum.
 *
 * Formato do que sai de {@link #cifrar}: [1 byte com o tamanho do IV][IV][texto cifrado + etiqueta de 16 bytes]. O IV é
 * gerado pelo provedor a cada cifra (o Keystore recusa IV escolhido por nós, e GCM com IV repetido quebra a chave) e
 * vai junto, porque sem ele não se decifra.
 *
 * Nenhuma mensagem de erro leva o texto nem os dados: o conteúdo é a identidade do técnico.
 */
public final class CifraKeystore implements Cifra {

    static final String ALIAS = "gc_casca_ident";
    private static final String PROVEDOR = "AndroidKeyStore";
    private static final String TRANSFORMACAO = "AES/GCM/NoPadding";
    private static final int BITS_DA_ETIQUETA = 128;
    private static final int BYTES_DA_ETIQUETA = BITS_DA_ETIQUETA / 8;
    private static final int TAMANHO_MAXIMO_DO_IV = 32;

    private final Supplier<SecretKey> fonteDaChave;
    private SecretKey chave; // lida do Keystore uma vez e guardada: a chave é a mesma pelo resto da vida do processo

    /** A de produção: a chave do AndroidKeyStore. */
    public CifraKeystore() {
        this(CifraKeystore::chaveDoKeystore);
    }

    /**
     * Para o teste de JVM, onde não há Keystore: a mesma cifra com uma chave de software. Fora do pacote ninguém a usa.
     */
    CifraKeystore(Supplier<SecretKey> fonteDaChave) {
        this.fonteDaChave = fonteDaChave;
    }

    @Override
    public byte[] cifrar(String texto) {
        try {
            Cipher cifra = Cipher.getInstance(TRANSFORMACAO);
            cifra.init(Cipher.ENCRYPT_MODE, chave());
            byte[] iv = cifra.getIV();
            byte[] cifrado = cifra.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            byte[] saida = new byte[1 + iv.length + cifrado.length];
            saida[0] = (byte) iv.length;
            System.arraycopy(iv, 0, saida, 1, iv.length);
            System.arraycopy(cifrado, 0, saida, 1 + iv.length, cifrado.length);
            return saida;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha ao cifrar", e);
        }
    }

    @Override
    public String decifrar(byte[] dados) {
        if (dados == null || dados.length < 1) throw new IllegalStateException("dados cifrados vazios");
        int tamanhoDoIv = dados[0] & 0xff;
        // O IV tem de caber, e depois dele tem de haver ao menos a etiqueta do GCM.
        if (tamanhoDoIv < 1 || tamanhoDoIv > TAMANHO_MAXIMO_DO_IV || dados.length < 1 + tamanhoDoIv + BYTES_DA_ETIQUETA) {
            throw new IllegalStateException("dados cifrados em formato inválido");
        }
        try {
            Cipher cifra = Cipher.getInstance(TRANSFORMACAO);
            cifra.init(Cipher.DECRYPT_MODE, chave(), new GCMParameterSpec(BITS_DA_ETIQUETA, dados, 1, tamanhoDoIv));
            byte[] puro = cifra.doFinal(dados, 1 + tamanhoDoIv, dados.length - 1 - tamanhoDoIv);
            return new String(puro, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Etiqueta que não confere (adulterado ou outra chave), chave inválida, etc.
            throw new IllegalStateException("falha ao decifrar", e);
        }
    }

    private synchronized SecretKey chave() {
        if (chave == null) chave = fonteDaChave.get();
        return chave;
    }

    /** A chave `gc_casca_ident` do Keystore; cria se ainda não existe (primeiro uso, ou o aparelho a perdeu). */
    private static SecretKey chaveDoKeystore() {
        try {
            KeyStore cofre = KeyStore.getInstance(PROVEDOR);
            cofre.load(null);
            Key existente = null;
            try {
                existente = cofre.getKey(ALIAS, null);
            } catch (UnrecoverableKeyException e) {
                // A chave existe e o cofre não a devolve mais (invalidada). O que ela cifrou já era: sem apagá-la, a
                // identidade nunca mais seria espelhada. Uma chave nova abaixo; o que estava cifrado fica ilegível e o
                // EspelhoSqlite o pula (a página regrava a identidade na próxima mudança).
                cofre.deleteEntry(ALIAS);
            }
            if (existente instanceof SecretKey) return (SecretKey) existente;

            KeyGenerator gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEDOR);
            gerador.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
            return gerador.generateKey();
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Keystore indisponível", e);
        }
    }
}
