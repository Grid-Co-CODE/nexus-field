package br.com.gridco.campo;

import java.io.File;
import java.util.regex.Pattern;

/**
 * A faxina das fotos da reserva da câmera (o &lt;input capture&gt; do App, quando a câmera interna dele não serve). O
 * BridgeWebChromeClient do Capacitor 8.5.3 grava cada foto num JPEG_&lt;data&gt;_*.jpg em
 * getExternalFilesDir(Pictures), ou no cache do app quando não há armazenamento externo (o createTempFile sem pasta), e
 * nunca apaga: no Chrome o arquivo é temporário, na casca ficava para sempre. Cada foto tem MB na resolução cheia da
 * câmera, o disco cheio é a causa principal do despejo do armazenamento da WebView, e no Android 7 a 10 a pasta externa
 * é legível por qualquer app com a permissão de armazenamento.
 *
 * A página copia a foto para o IndexedDB dela quando a recebe (o put copia o blob), então o arquivo de mais de 48 h é
 * sobra. Na pasta das fotos sai todo arquivo velho (a pasta é só das capturas; a captura cancelada deixa um arquivo
 * vazio). No cache, que tem também a WebView e o instalador da atualização, sai só o JPEG_*.jpg do Capacitor, e nenhuma
 * subpasta é varrida. O arquivo "do futuro" (a hora do aparelho voltou) fica.
 */
final class FaxinaDaCamera {

    /** A idade a partir da qual a foto da reserva é sobra. */
    static final long IDADE_MAXIMA_MS = 48L * 60L * 60L * 1000L;

    // O nome que o createImageFile do BridgeWebChromeClient dá: "JPEG_" + data + "_" + sufixo do createTempFile + ".jpg".
    private static final Pattern CAPTURA_DO_CAPACITOR = Pattern.compile("JPEG_.*\\.jpg");

    private FaxinaDaCamera() {
    }

    /**
     * Apaga as sobras com mais de 48 h e devolve quantas saíram. As pastas podem ser null (getExternalFilesDir sem
     * armazenamento externo montado) ou não existir.
     */
    static int apagarVelhas(File fotos, File cache, long agoraMs) {
        return apagar(fotos, false, agoraMs) + apagar(cache, true, agoraMs);
    }

    private static int apagar(File pasta, boolean soCapturas, long agoraMs) {
        File[] arquivos = pasta == null ? null : pasta.listFiles();
        if (arquivos == null) return 0;
        int apagados = 0;
        for (File arquivo : arquivos) {
            if (!arquivo.isFile()) continue;
            if (soCapturas && !CAPTURA_DO_CAPACITOR.matcher(arquivo.getName()).matches()) continue;
            if (agoraMs - arquivo.lastModified() > IDADE_MAXIMA_MS && arquivo.delete()) apagados++;
        }
        return apagados;
    }
}
