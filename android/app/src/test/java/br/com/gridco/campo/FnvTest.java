package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * O hash do Java tem de ser IGUAL ao fnv1a32 do inicio.js, texto a texto: é com ele que o script da página decide se a
 * fila da WebView está atrasada em relação ao espelho. Os valores abaixo saíram do próprio JavaScript
 * (require('./www/casca/inicio.js').fnv1a32), não de uma tabela de referência.
 */
public class FnvTest {

    @Test
    public void fnvIgualAoJs() {
        assertEquals("811c9dc5", Fnv.hash(""));
        assertEquals("e40c292c", Fnv.hash("a"));
        assertEquals("bf9cf968", Fnv.hash("foobar"));
        assertEquals("741638a5", Fnv.hash("[]"));
        assertEquals("8b9e4511", Fnv.hash("{\"a\":1}"));
    }

    // O Java e o JavaScript percorrem as mesmas unidades UTF-16 (o char do Java, o charCodeAt do JS). Um FNV sobre UTF-8
    // daria outro valor para "ção" e para o emoji (que são duas unidades, o par substituto), e a fila seria tomada por
    // "à frente" ou "atrasada" à toa. Esperado gerado por:
    //   node -e "console.log(require('./www/casca/inicio.js').fnv1a32('ação ç 😀 gc_campo_queue'))"
    @Test
    public void fnvIgualAoJsComTextoNaoAscii() {
        assertEquals("f0e230d6", Fnv.hash("ação ç 😀 gc_campo_queue"));
    }

    // Oito dígitos sempre: o hash que começa com zero não pode perder o zero (o JS completa com '00000000' antes de cortar).
    @Test
    public void fnvCompletaComZerosAEsquerda() {
        assertEquals("0d226273", Fnv.hash("f8"));
        assertEquals("0c2260e0", Fnv.hash("f9"));
    }
}
