package br.com.gridco.campo;

/**
 * FNV-1a de 32 bits, IGUAL ao fnv1a32 do www/casca/inicio.js: percorre as unidades UTF-16 do texto (o char do Java, o
 * charCodeAt do JavaScript, que são a mesma coisa) e devolve 8 dígitos hexadecimais minúsculos.
 *
 * Não é segurança. Serve à regra das versões da fila: o script compara o hash da fila da WebView com os que o retrato
 * traz, sem trafegar o texto inteiro. Por isso os dois lados têm de concordar até em texto com acento e emoji, e é
 * UTF-16, e não UTF-8, justamente porque é o que o JavaScript dá de graça.
 */
public final class Fnv {

    private static final int BASE = 0x811c9dc5;
    private static final int PRIMO = 0x01000193;

    private Fnv() {}

    public static String hash(String texto) {
        int h = BASE;
        for (int i = 0; i < texto.length(); i++) {
            h ^= texto.charAt(i);
            h *= PRIMO; // o int do Java dá a volta em 32 bits, como o Math.imul(...) >>> 0 do JavaScript
        }
        return String.format(java.util.Locale.ROOT, "%08x", h);
    }
}
