package br.com.gridco.campo;

/**
 * Cifra do que o espelho guarda em dado pessoal (a identidade do técnico, gc_campo_ident). Em produção é a
 * CifraKeystore (AES/GCM, chave no Android Keystore); nos testes, uma falsa.
 */
public interface Cifra {

    /** Cifra o texto. O que sai carrega tudo de que o decifrar precisa (o IV vai junto). */
    byte[] cifrar(String texto);

    /**
     * Decifra o que o {@link #cifrar} produziu. Lança IllegalStateException (sem o conteúdo na mensagem) quando os dados
     * estão adulterados, truncados ou foram cifrados por outra chave, por exemplo se o aparelho perdeu a do Keystore.
     */
    String decifrar(byte[] dados);
}
