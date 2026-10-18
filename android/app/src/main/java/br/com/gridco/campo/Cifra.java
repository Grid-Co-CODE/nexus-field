package br.com.gridco.campo;

/**
 * Cifra do que o espelho guarda em dado pessoal (a identidade do técnico, gc_campo_ident). Em produção é a
 * CifraKeystore (AES/GCM, chave no Android Keystore); nos testes, uma falsa.
 */
public interface Cifra {

    /**
     * Cifra o texto. O que sai carrega tudo de que o decifrar precisa (o IV vai junto). Lança IllegalStateException (sem
     * o texto na mensagem) se não conseguir: quem grava nunca troca a cifra que falhou por uma gravação em claro.
     */
    byte[] cifrar(String texto);

    /**
     * Decifra o que o {@link #cifrar} produziu. Lança {@link Ilegivel} quando o dado nunca mais será lido com a chave de
     * agora (adulterado, truncado, ou cifrado por outra chave: a do Keystore que o aparelho perdeu e foi refeita), e
     * IllegalStateException comum quando a falha não diz nada do dado (o Keystore que não respondeu agora). Nenhuma das
     * duas leva o conteúdo na mensagem.
     */
    String decifrar(byte[] dados);

    /** O dado que a chave de agora nunca vai ler: quem guarda pode apagá-lo. */
    class Ilegivel extends IllegalStateException {
        Ilegivel(String mensagem) {
            super(mensagem);
        }

        Ilegivel(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
