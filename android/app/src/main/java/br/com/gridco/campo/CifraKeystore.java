package br.com.gridco.campo;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyStore;
import java.security.ProviderException;
import java.security.UnrecoverableKeyException;
import java.security.spec.AlgorithmParameterSpec;
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
 * A chave que o Keystore passa a recusar no init (InvalidKeyException, que inclui a KeyPermanentlyInvalidatedException, ou
 * a ProviderException do defeito que alguns fabricantes trazem depois de uma atualização de sistema) é apagada e refeita
 * UMA vez por processo: sem isso, a identidade deixaria de ser espelhada naquele aparelho para sempre, avisando só pela
 * contagem. O que a chave velha cifrou fica ilegível ({@link Cifra.Ilegivel}), o espelho apaga, e a página regrava a
 * identidade no próximo login. A etiqueta que não confere é o dado errado, não a chave: não refaz nada.
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
    private final Runnable apagarChave;
    private SecretKey chave; // lida do Keystore uma vez e guardada: a chave é a mesma pelo resto da vida do processo
    private boolean refeita = false; // a chave recusada já foi refeita neste processo

    /** A de produção: a chave do AndroidKeyStore. */
    public CifraKeystore() {
        this(CifraKeystore::chaveDoKeystore, CifraKeystore::apagarDoKeystore);
    }

    /**
     * Para o teste de JVM, onde não há Keystore: a mesma cifra com uma chave de software. Fora do pacote ninguém a usa.
     */
    CifraKeystore(Supplier<SecretKey> fonteDaChave) {
        this(fonteDaChave, () -> { });
    }

    /** Idem, com o "apagar a chave do cofre" que a chave recusada chama antes de pedir outra à fonte. */
    CifraKeystore(Supplier<SecretKey> fonteDaChave, Runnable apagarChave) {
        this.fonteDaChave = fonteDaChave;
        this.apagarChave = apagarChave;
    }

    @Override
    public byte[] cifrar(String texto) {
        try {
            Cipher cifra = iniciar(Cipher.ENCRYPT_MODE, null);
            byte[] iv = cifra.getIV();
            byte[] cifrado = cifra.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            byte[] saida = new byte[1 + iv.length + cifrado.length];
            saida[0] = (byte) iv.length;
            System.arraycopy(iv, 0, saida, 1, iv.length);
            System.arraycopy(cifrado, 0, saida, 1 + iv.length, cifrado.length);
            return saida;
        } catch (GeneralSecurityException | ProviderException e) {
            throw new IllegalStateException("falha ao cifrar", e);
        }
    }

    @Override
    public String decifrar(byte[] dados) {
        if (dados == null || dados.length < 1) throw new Cifra.Ilegivel("dados cifrados vazios");
        int tamanhoDoIv = dados[0] & 0xff;
        // O IV tem de caber, e depois dele tem de haver ao menos a etiqueta do GCM.
        if (tamanhoDoIv < 1 || tamanhoDoIv > TAMANHO_MAXIMO_DO_IV || dados.length < 1 + tamanhoDoIv + BYTES_DA_ETIQUETA) {
            throw new Cifra.Ilegivel("dados cifrados em formato inválido");
        }
        Cipher cifra;
        try {
            cifra = iniciar(Cipher.DECRYPT_MODE, new GCMParameterSpec(BITS_DA_ETIQUETA, dados, 1, tamanhoDoIv));
        } catch (InvalidAlgorithmParameterException e) {
            throw new Cifra.Ilegivel("dados cifrados em formato inválido", e); // o IV veio do dado
        } catch (GeneralSecurityException | ProviderException e) {
            // A chave (também a refeita) recusada, ou o algoritmo indisponível: não diz nada do dado.
            throw new IllegalStateException("falha ao decifrar", e);
        }
        try {
            byte[] puro = cifra.doFinal(dados, 1 + tamanhoDoIv, dados.length - 1 - tamanhoDoIv);
            return new String(puro, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // A etiqueta que não confere: adulterado, ou cifrado por outra chave (a velha, antes de ser refeita).
            throw new Cifra.Ilegivel("falha ao decifrar", e);
        }
    }

    // O init com a chave guardada. Se ela é recusada, apaga, pede outra à fonte e tenta de novo, uma vez por processo:
    // uma chave nova que também é recusada não vira um laço de chaves novas a cada gravação.
    private synchronized Cipher iniciar(int modo, AlgorithmParameterSpec parametros) throws GeneralSecurityException {
        try {
            return iniciarCom(modo, chave(), parametros);
        } catch (InvalidKeyException | ProviderException recusada) {
            if (refeita) throw recusada;
            refeita = true;
            chave = null;
            apagarChave.run();
            return iniciarCom(modo, chave(), parametros);
        }
    }

    private static Cipher iniciarCom(int modo, SecretKey chave, AlgorithmParameterSpec parametros)
            throws GeneralSecurityException {
        Cipher cifra = Cipher.getInstance(TRANSFORMACAO);
        if (parametros == null) cifra.init(modo, chave);
        else cifra.init(modo, chave, parametros);
        return cifra;
    }

    private synchronized SecretKey chave() {
        if (chave == null) chave = fonteDaChave.get();
        return chave;
    }

    /** Tira a chave `gc_casca_ident` do cofre; a próxima {@link #chaveDoKeystore} gera outra. */
    private static void apagarDoKeystore() {
        try {
            KeyStore cofre = KeyStore.getInstance(PROVEDOR);
            cofre.load(null);
            cofre.deleteEntry(ALIAS);
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Keystore indisponível", e);
        }
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
