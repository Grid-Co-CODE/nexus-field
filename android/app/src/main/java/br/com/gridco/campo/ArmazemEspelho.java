package br.com.gridco.campo;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Onde o nativo guarda o espelho do que o inicio.js manda da página (spec 6.4): o localStorage de trabalho e de cache,
 * as versões da fila e as entradas do IndexedDB das fotos. A implementação de produção é a {@link EspelhoSqlite}.
 *
 * O espelho nunca guarda msal.*, o sessionStorage nem token, e a casca nunca envia nada daqui ao servidor: o que sai
 * daqui vai de volta à própria WebView, no retrato.
 *
 * As chaves têm e-mail de técnico: nenhuma implementação põe nome de chave (nem valor) em log ou em mensagem de exceção.
 * As escritas podem lançar RuntimeException (disco cheio, pedaço mal formado); quem chama (o canal da Task 6) as engole
 * e avisa só com contagem.
 */
public interface ArmazemEspelho {

    /** Uma entrada do IndexedDB, entregue por {@link #paraCadaIdb}. */
    interface ItemIdb {
        /**
         * @param k       a chave da entrada no IndexedDB
         * @param tipo    'texto', 'json' ou 'blob'
         * @param mime    o tipo do Blob; null para texto e JSON
         * @param texto   o valor, para texto e JSON; null para blob
         * @param arquivo o arquivo com os bytes, para blob; null para texto e JSON
         */
        void aceitar(String k, String tipo, String mime, String texto, File arquivo);
    }

    // --- localStorage ---------------------------------------------------------------------------------------------

    /**
     * Grava uma chave do localStorage. A gc_campo_queue entra como uma versão nova da fila (as 3 mais novas ficam); a
     * gc_campo_ident vai cifrada. Chave msal.*, gc_casca_* (a sentinela e a marca da perda da casca) e chave ou valor
     * nulos são recusados em silêncio.
     */
    void gravarLs(String k, String v);

    /** Apaga uma chave do localStorage; da fila, apaga todas as versões. */
    void apagarLs(String k);

    // --- IndexedDB das fotos ----------------------------------------------------------------------------------------

    /** Grava uma entrada de texto ou JSON ('texto' | 'json'); no lugar de um blob da mesma chave, apaga o arquivo dele. */
    void gravarIdbTexto(String k, String tipo, String v);

    /**
     * Recebe um pedaço de um Blob. O {@code id} é da gravação (o script gera um por Blob) e vira nome de arquivo
     * (files/espelho/tmp/&lt;id&gt;.parte). A entrada só passa a existir quando chega o pedaço {@code i == n-1} e todos
     * os {@code n} chegaram, na ordem; uma sequência quebrada é descartada.
     */
    void receberPedaco(String k, String id, int i, int n, String mime, byte[] dados);

    /** Apaga a entrada (e o arquivo, se for blob). */
    void apagarIdb(String k);

    // --- sentinela e retrato -----------------------------------------------------------------------------------------

    /** O script da página viu a instalação nova e gravou a sentinela dele: daqui em diante há espelho. */
    void marcarSentinela();

    boolean temEspelho();

    /** Os hashes (Fnv) das versões da fila, a mais nova primeiro, até 3. */
    List<String> filaHashes();

    /** O texto da versão mais nova da fila, ou null. */
    String filaMaisNova();

    /** O localStorage espelhado, SEM a fila, já decifrado. A chave que não puder ser decifrada fica de fora. */
    Map<String, String> lsTudo();

    /** As chaves das entradas completas do IndexedDB (um blob incompleto, ou sem o arquivo, não conta). */
    List<String> idbChaves();

    /** Entrega cada entrada completa do IndexedDB. */
    void paraCadaIdb(ItemIdb consumidor);
}
