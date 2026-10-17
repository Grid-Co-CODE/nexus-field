package br.com.gridco.campo;

import java.util.Map;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * O retrato do espelho que o nativo entrega ao www/casca/inicio.js, antes de qualquer script da página, como
 * window.__GC_RETRATO__. O formato é o contrato com o script (seções 4 e 5 dele):
 *
 * <pre>
 * leve (toda partida):
 *   { temEspelho: boolean,
 *     filaHashes: [fnv1a32 da fila, a mais nova primeiro, até 3],
 *     idbChaves:  [chaves das entradas COMPLETAS do IndexedDB] }
 *
 * completo (quando o script pede, com precisoRetrato): o leve mais
 *   { modo: 'restaurar',
 *     motivo: 'perda' | 'fila',
 *     id: texto novo a cada retrato,
 *     ls: { chave: valor, ... },     // vazio no motivo 'fila'
 *     fila: texto da fila mais nova, ou null }
 * </pre>
 *
 * O {@code id} muda a cada chamada de propósito: o script grava o do último retrato que aplicou e não aplica o mesmo
 * duas vezes na sessão (regravaria, sem espelhar, por cima do que a página fez depois). O motivo 'fila' leva só a
 * fila, com {@code ls} vazio: o script regrava só a fila e não mexe na marca da perda.
 */
public final class Retrato {

    private Retrato() {}

    public static JSONObject leve(ArmazemEspelho armazem) {
        try {
            JSONObject retrato = new JSONObject();
            retrato.put("temEspelho", armazem.temEspelho());
            retrato.put("filaHashes", new JSONArray(armazem.filaHashes()));
            retrato.put("idbChaves", new JSONArray(armazem.idbChaves()));
            return retrato;
        } catch (JSONException e) {
            throw new IllegalStateException("não foi possível montar o retrato leve", e); // nome nulo ou número inválido: não ocorre
        }
    }

    /** @param motivo 'perda' ou 'fila'; qualquer outro lança IllegalArgumentException (quem chama valida o pedido). */
    public static JSONObject completo(ArmazemEspelho armazem, String motivo) {
        if (!"perda".equals(motivo) && !"fila".equals(motivo)) {
            throw new IllegalArgumentException("motivo de retrato desconhecido");
        }
        try {
            JSONObject retrato = leve(armazem);
            retrato.put("modo", "restaurar");
            retrato.put("motivo", motivo);
            retrato.put("id", UUID.randomUUID().toString());

            JSONObject ls = new JSONObject();
            if ("perda".equals(motivo)) {
                for (Map.Entry<String, String> e : armazem.lsTudo().entrySet()) ls.put(e.getKey(), e.getValue());
            }
            retrato.put("ls", ls);

            // put(nome, null) APAGA a chave no org.json; o null do JSON é o JSONObject.NULL.
            String fila = armazem.filaMaisNova();
            retrato.put("fila", fila == null ? JSONObject.NULL : fila);
            return retrato;
        } catch (JSONException e) {
            throw new IllegalStateException("não foi possível montar o retrato completo", e);
        }
    }

    /** O que o plugin injeta antes da página: o retrato como variável global e, logo em seguida, o inicio.js. */
    public static String script(JSONObject retrato, String inicioJs) {
        return "window.__GC_RETRATO__=" + retrato.toString() + ";\n" + inicioJs;
    }
}
