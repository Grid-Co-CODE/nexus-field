package br.com.gridco.campo;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Um ponto da coleta de equipe, no formato do corpo do lote que o Nexus recebe (seção 8 do desenho). Sem velocidade
 * (o aparelho não deu leitura), o campo "vel" não vai: o Nexus distingue "parado a 0" de "não sei".
 */
final class PontoDaEquipe {
    final long t;
    final double lat;
    final double lon;
    final float prec;
    final Float vel;
    final boolean mov;

    PontoDaEquipe(long t, double lat, double lon, float prec, Float vel, boolean mov) {
        this.t = t;
        this.lat = lat;
        this.lon = lon;
        this.prec = prec;
        this.vel = vel;
        this.mov = mov;
    }

    JSONObject json() {
        try {
            JSONObject o = new JSONObject();
            o.put("t", t);
            o.put("lat", lat);
            o.put("lon", lon);
            o.put("prec", (double) prec);
            if (vel != null) o.put("vel", (double) vel);
            o.put("mov", mov);
            return o;
        } catch (JSONException e) {
            // só acontece com NaN ou infinito; sem o valor na mensagem (é coordenada)
            throw new IllegalStateException("ponto inválido");
        }
    }
}
