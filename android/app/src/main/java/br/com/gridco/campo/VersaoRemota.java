package br.com.gridco.campo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * O versao.json da última Release (spec, seção 7): o número da versão, o link do APK, o SHA-256 dele e as novidades.
 * Quem gera é o ferramentas/versao-json.mjs, na Release; aqui só se lê, e se lê desconfiando: o texto veio da rede.
 *
 * O link do APK só vale dentro das Releases deste repositório. Sem isso, um versao.json adulterado mandaria a casca
 * baixar de qualquer lugar, e o SHA-256 que ele mesmo traz não protegeria de nada.
 */
public final class VersaoRemota {

    /** Tudo o que o apk tem de ter na frente. */
    static final String PREFIXO_DO_APK = "https://github.com/Grid-Co-CODE/app-campo-android/releases/download/";

    // O resto do link: pedaços de caminho só com o que um nome de tag e de arquivo usam, separados por uma barra. Fora
    // daqui ficam a query, o fragmento, o espaço, a barra invertida, a quebra de linha e o "%" (que esconderia "..").
    private static final Pattern RESTO_DO_APK = Pattern.compile("[A-Za-z0-9._~+-]+(?:/[A-Za-z0-9._~+-]+)*");
    private static final Pattern SHA256 = Pattern.compile("[0-9A-Fa-f]{64}");

    public final int versionCode;
    public final String versionName;
    public final String apk;
    /** Em minúsculas. */
    public final String sha256;
    public final List<String> novidades;

    private VersaoRemota(int versionCode, String versionName, String apk, String sha256, List<String> novidades) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apk = apk;
        this.sha256 = sha256;
        this.novidades = novidades;
    }

    /**
     * Lê o versao.json. Lança IllegalArgumentException se o texto não for um JSON, se faltar campo ou o campo tiver o
     * tipo errado, se o SHA-256 não tiver 64 dígitos hexadecimais (nunca bateria) ou se o apk não estiver nas Releases
     * deste repositório. A mensagem diz só o campo, nunca o valor: ela pode ir parar num log.
     */
    public static VersaoRemota ler(String json) {
        if (json == null) throw new IllegalArgumentException("versao.json vazio");
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("versao.json ilegível");
        }
        int codigo = inteiroPositivo(o, "versionCode");
        String nome = texto(o, "versionName");
        String apk = texto(o, "apk");
        String sha = texto(o, "sha256");
        List<String> novidades = lista(o, "novidades");

        if (!apkValido(apk)) throw new IllegalArgumentException("apk fora das Releases do repositório");
        if (!SHA256.matcher(sha).matches()) throw new IllegalArgumentException("sha256 inválido");
        return new VersaoRemota(codigo, nome, apk, sha.toLowerCase(Locale.ROOT), novidades);
    }

    private static boolean apkValido(String apk) {
        if (!apk.startsWith(PREFIXO_DO_APK)) return false;
        String resto = apk.substring(PREFIXO_DO_APK.length());
        if (!RESTO_DO_APK.matcher(resto).matches()) return false;
        for (String pedaco : resto.split("/")) {
            if (pedaco.equals(".") || pedaco.equals("..")) return false;
        }
        return true;
    }

    // O JSONObject.getInt() aceita "12" e 12.7; aqui o campo tem de ser um número inteiro de verdade.
    private static int inteiroPositivo(JSONObject o, String campo) {
        Object v = o.opt(campo);
        if (!(v instanceof Number)) throw new IllegalArgumentException("falta " + campo);
        double d = ((Number) v).doubleValue();
        if (d != Math.rint(d) || d < 1 || d > Integer.MAX_VALUE) throw new IllegalArgumentException(campo + " inválido");
        return (int) d;
    }

    // O JSONObject.getString() devolve "12" para o número 12; aqui só vale texto.
    private static String texto(JSONObject o, String campo) {
        Object v = o.opt(campo);
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) throw new IllegalArgumentException("falta " + campo);
        return (String) v;
    }

    private static List<String> lista(JSONObject o, String campo) {
        Object v = o.opt(campo);
        if (!(v instanceof JSONArray)) throw new IllegalArgumentException("falta " + campo);
        JSONArray itens = (JSONArray) v;
        List<String> saida = new ArrayList<>();
        for (int i = 0; i < itens.length(); i++) {
            Object item = itens.opt(i);
            if (!(item instanceof String)) throw new IllegalArgumentException(campo + " com item que não é texto");
            String linha = ((String) item).trim();
            if (!linha.isEmpty()) saida.add(linha);
        }
        return Collections.unmodifiableList(saida);
    }
}
