package br.com.gridco.campo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TimeZone;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * O envio dos pontos da fila ao Nexus (seção 8 do desenho): lote de até 200 pontos e 64 KB, com o aparelho, o fuso e a
 * chave do aparelho no Authorization.
 *
 * <ul>
 *   <li>Nada sai antes de a página desta partida conferir a conta da chave ({@link ChaveDaEquipe#conferida}): o lote e o
 *       aviso sem permissão devolvem {@link Resultado#NADA} sem pedido ao Nexus, e a fila e a chave ficam. A revogação do
 *       Sair não espera.</li>
 *   <li>Só o 401 e o {@code coleta:false} do Nexus param o serviço e apagam a chave. O 401 apaga só a chave: a conta
 *       fica, dona da fila que sobrou ({@link ChaveDaEquipe#apagarAChave}). O coleta:false apaga a chave, a conta e a
 *       fila. Todo 5xx (o 503, com a Microsoft fora ou o servidor sem configuração, em especial) e a rede que cai dão
 *       {@link Resultado#SEM_REDE}: fila e chave ficam, e o serviço tenta de novo no ritmo seguinte.</li>
 *   <li>Resposta 200 que não é JSON legível também vale como sem rede: sem a confirmação do Nexus, os pontos não saem
 *       da fila.</li>
 *   <li>Nada de coordenada, chave ou id de aparelho no log: nem a mensagem das exceções leva o corpo.</li>
 * </ul>
 */
final class EnvioDePontos {

    static final String NEXUS = "https://app.gridco.com.br";
    static final String ROTA_POSICOES = "/t/campo/equipe/posicoes";
    static final String ROTA_REVOGAR = "/t/campo/equipe/aparelho/revogar";
    static final int MAXIMO_DE_PONTOS = 200;
    static final int MAXIMO_DE_BYTES = 64 * 1024;
    static final int PRAZO_MS = 15_000;

    enum Resultado { NADA, ENVIADO, PARAR, SEM_REDE }

    static final class Resposta {
        final int status;
        final String corpo;

        Resposta(int status, String corpo) {
            this.status = status;
            this.corpo = corpo == null ? "" : corpo;
        }
    }

    interface Transporte {
        Resposta postar(String url, String chave, String corpoJson) throws IOException;
    }

    private final ChaveDaEquipe chave;
    private final FilaDePontos fila;
    private final Transporte transporte;
    private final LongSupplier relogio;
    private final int maximoDeBytes;

    EnvioDePontos(ChaveDaEquipe chave, FilaDePontos fila, Transporte transporte, LongSupplier relogio) {
        this(chave, fila, transporte, relogio, MAXIMO_DE_BYTES);
    }

    /** O limite de bytes entra à parte só para o teste: um ponto tem uns 100 bytes, e 200 deles nunca chegam a 64 KB. */
    EnvioDePontos(ChaveDaEquipe chave, FilaDePontos fila, Transporte transporte, LongSupplier relogio,
            int maximoDeBytes) {
        this.chave = chave;
        this.fila = fila;
        this.transporte = transporte;
        this.relogio = relogio;
        this.maximoDeBytes = maximoDeBytes;
    }

    /** Manda o lote mais antigo da fila. */
    Resultado enviar() {
        String k = chaveConferida();
        if (k == null) return Resultado.NADA;
        List<Long> ids = fila.idsDoLote(MAXIMO_DE_PONTOS);
        if (ids.isEmpty()) return Resultado.NADA;
        JSONArray pontos = fila.lote(ids);
        String corpo = corpo(pontos, null);
        // Corta o lote antes de mandar: o servidor recusa corpo acima do limite.
        while (ids.size() > 1 && bytes(corpo) > maximoDeBytes) {
            int n = (int) Math.min(ids.size() - 1L, (long) ids.size() * maximoDeBytes / bytes(corpo));
            ids = ids.subList(0, Math.max(1, n));
            pontos = fila.lote(ids);
            corpo = corpo(pontos, null);
        }
        Resultado r = postarPosicoes(k, corpo);
        if (r == Resultado.ENVIADO) fila.remover(ids);
        return r;
    }

    /**
     * Sem a permissão "o tempo todo" não há ponto a mandar, mas o Nexus precisa saber (o mapa separa quem negou de
     * quem nunca abriu o app): lote vazio com {@code permissao: "sem"}, as mesmas respostas do envio normal.
     */
    Resultado avisarSemPermissao() {
        String k = chaveConferida();
        if (k == null) return Resultado.NADA;
        return postarPosicoes(k, corpo(new JSONArray(), "sem"));
    }

    // A chave para o envio: só a da conta que a página desta partida conferiu (ChaveDaEquipe#conferida). Antes disso o
    // lote espera na fila, sem pedido ao Nexus: nada sai em nome de uma conta que esta partida não conferiu.
    private String chaveConferida() {
        return chave.conferida() ? chave.chave() : null;
    }

    /**
     * Pede ao Nexus para revogar a chave e a apaga daqui de qualquer jeito (o técnico desligou, não pode ficar presa). Não
     * espera a conta conferida: é a chave velha que sai, e o que ela leva é o pedido de revogá-la.
     */
    void revogar() {
        String k = chave.chave();
        try {
            if (k != null) {
                JSONObject o = new JSONObject();
                o.put("aparelho", chave.aparelho());
                transporte.postar(NEXUS + ROTA_REVOGAR, k, o.toString());
            }
        } catch (IOException | JSONException | RuntimeException e) {
            // sem rede ou resposta ruim: a chave local cai mesmo assim; a do Nexus vence por troca ou expiração
        } finally {
            chave.apagar();
        }
    }

    private Resultado postarPosicoes(String k, String corpo) {
        Resposta resp;
        try {
            resp = transporte.postar(NEXUS + ROTA_POSICOES, k, corpo);
        } catch (IOException | RuntimeException e) {
            return Resultado.SEM_REDE;
        }
        if (resp.status == 401) {
            // Só a chave: a conta fica, dona da fila que sobrou (ChaveDaEquipe#apagarAChave).
            chave.apagarAChave();
            return Resultado.PARAR;
        }
        if (resp.status != 200) return Resultado.SEM_REDE;
        boolean coleta;
        try {
            coleta = new JSONObject(resp.corpo).optBoolean("coleta", false);
        } catch (JSONException e) {
            return Resultado.SEM_REDE;
        }
        if (!coleta) {
            chave.apagar();
            fila.limpar();
            return Resultado.PARAR;
        }
        return Resultado.ENVIADO;
    }

    private String corpo(JSONArray pontos, String permissao) {
        try {
            JSONObject o = new JSONObject();
            o.put("aparelho", chave.aparelho());
            o.put("fuso", JanelaDaEquipe.fusoEmMinutos(relogio.getAsLong(), TimeZone.getDefault()));
            if (permissao != null) o.put("permissao", permissao);
            o.put("pontos", pontos);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("corpo inválido");
        }
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** O transporte de verdade: POST em JSON com 15 s de prazo. A chave vai só no cabeçalho. */
    static final class Http implements Transporte {
        @Override
        public Resposta postar(String url, String chave, String corpoJson) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(PRAZO_MS);
                c.setReadTimeout(PRAZO_MS);
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("Authorization", "Bearer " + chave);
                byte[] dados = corpoJson.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(dados.length);
                try (OutputStream saida = c.getOutputStream()) {
                    saida.write(dados);
                }
                int status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                return new Resposta(status, in == null ? "" : ler(in));
            } finally {
                c.disconnect();
            }
        }

        /** Resposta do Nexus é pequena; o teto de 64 KB evita ler um corpo sem fim. */
        private static String ler(InputStream entrada) throws IOException {
            try (InputStream in = entrada) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) != -1 && buf.size() < 64 * 1024) buf.write(b, 0, n);
                return buf.toString("UTF-8");
            }
        }
    }
}
