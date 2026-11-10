package br.com.gridco.campo;

import android.util.Log;

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
 *   <li>Nada sai antes de uma página conferir a conta da chave ({@link ChaveDaEquipe#conferida}): a desta partida, ou a
 *       de uma partida de antes, pela marca no disco (R14: depois da atualização, do Forçar parada ou do reinício, com a
 *       tela apagada, os pontos saem sem esperar a página). Sem ela, o lote e o aviso sem permissão devolvem
 *       {@link Resultado#NADA} sem pedido ao Nexus, e a fila e a chave ficam. A conferência que o cofre não deixou fazer
 *       (R9) é refeita antes de cada envio. A revogação do Sair não espera.</li>
 *   <li>Só o 401 e o {@code coleta:false} do Nexus param o serviço e apagam a chave. O 401 apaga só a chave: a conta
 *       fica, dona da fila que sobrou ({@link ChaveDaEquipe#apagarAChave}). O coleta:false apaga a chave, a conta e a
 *       fila. Todo 5xx (o 503, com a Microsoft fora ou o servidor sem configuração, em especial) e a rede que cai dão
 *       {@link Resultado#SEM_REDE}: fila e chave ficam, e o serviço tenta de novo no ritmo seguinte.</li>
 *   <li>O 200 só vale com o {@code coleta} booleano: o {@code true} tira o lote da fila, o {@code false} para. O 200 que
 *       não é JSON legível, sem o campo, com ele nulo ou de outro tipo (o chat do Nexus mudou um nome, ou um tratador de
 *       erro devolveu 200 {erro}) vale como sem rede: sem a confirmação do Nexus os pontos não saem da fila, e sem o
 *       coleta:false dele nada se apaga. Antes, a ausência valia como coleta:false, e o primeiro envio de cada aparelho
 *       apagava a chave e o dia guardado sem sinal.</li>
 *   <li>O lote que o Nexus recusa (o 4xx que não é o 401: o 400 de um ponto torto, o 413, o 422) sai da fila
 *       ({@link Resultado#DESCARTADO}), e a chave fica: mandado de novo, ele seria recusado de novo, e na frente da fila
 *       não deixava subir nenhum ponto novo. O 403, o 404 e o 405 (o acesso ou a rota, e não o lote: o caminho que o chat
 *       do Nexus mudou, a rota que caiu num deploy), o 408 e o 429 (o tempo e o excesso de pedidos) e o 3xx (a resposta
 *       que não é do Nexus) esperam como sem rede.</li>
 *   <li>O ponto da fila que não se lê ({@link FilaDePontos#legivel}) sai dela antes do lote, que segue com os outros.</li>
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

    /**
     * NADA: nada a mandar, ou a conta ainda não conferida (sem pedido ao Nexus). ENVIADO: o Nexus aceitou. PARAR: o 401 ou
     * o coleta:false. SEM_REDE: tenta de novo depois, com a fila e a chave intactas. DESCARTADO: o Nexus recusou o lote
     * (o 4xx que não é o 401), que saiu da fila.
     */
    enum Resultado { NADA, ENVIADO, PARAR, SEM_REDE, DESCARTADO }

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

    /**
     * Vale tentar o envio agora: a conta conferida (nesta partida ou numa de antes, R14), ou a conferência por refazer
     * (R9), que o {@link #enviar} refaz. Não passa pela cifra nem grava no disco: o serviço pergunta na thread principal,
     * antes de acordar o celular e de recomeçar a contar o envio (o primeiro lote depois da conferência não espera mais 2
     * min).
     */
    boolean podeEnviar() {
        return chave.conferida() || chave.precisaReconferir();
    }

    /** Manda o lote mais antigo da fila. */
    Resultado enviar() {
        String k = chaveConferida();
        if (k == null) return Resultado.NADA;
        List<Long> ids = fila.idsDoLote(MAXIMO_DE_PONTOS);
        if (ids.isEmpty()) return Resultado.NADA;
        FilaDePontos.Lote lote = fila.legivel(ids);
        if (!lote.ruins.isEmpty()) {
            // Só a contagem: o ponto é coordenada.
            fila.remover(lote.ruins);
            Log.w(CascaPlugin.TAG, "a fila da equipe tinha " + lote.ruins.size()
                + " ponto(s) que não se leem: saíram, e o lote segue com os outros");
        }
        ids = lote.ids;
        if (ids.isEmpty()) return Resultado.NADA;
        JSONArray pontos = lote.pontos;
        String corpo = corpo(pontos, null);
        // Corta o lote antes de mandar: o servidor recusa corpo acima do limite.
        while (ids.size() > 1 && bytes(corpo) > maximoDeBytes) {
            int n = Math.max(1, (int) Math.min(ids.size() - 1L, (long) ids.size() * maximoDeBytes / bytes(corpo)));
            ids = ids.subList(0, n);
            pontos = primeiros(pontos, n);
            corpo = corpo(pontos, null);
        }
        Resultado r = postarPosicoes(k, corpo);
        if (r == Resultado.ENVIADO || r == Resultado.DESCARTADO) fila.remover(ids);
        return r;
    }

    private static JSONArray primeiros(JSONArray pontos, int n) {
        JSONArray saida = new JSONArray();
        for (int i = 0; i < n && i < pontos.length(); i++) saida.put(pontos.opt(i));
        return saida;
    }

    /**
     * Sem a permissão de localização (a precisa, durante o uso) ou sem a notificação fixa à vista (que o Android 13+ trata
     * como permissão) não há ponto a mandar, mas o Nexus precisa saber (o mapa separa quem negou de quem nunca abriu o app): lote vazio com
     * {@code permissao: "sem"}, as mesmas respostas do envio normal.
     */
    Resultado avisarSemPermissao() {
        String k = chaveConferida();
        if (k == null) return Resultado.NADA;
        return postarPosicoes(k, corpo(new JSONArray(), "sem"));
    }

    // A chave para o envio: só a da conta que uma página conferiu (ChaveDaEquipe#conferida, a desta partida ou a do disco).
    // Antes disso o lote espera na fila, sem pedido ao Nexus: nada sai em nome de uma conta que não foi conferida. A
    // conferência que o cofre não deixou fazer (R9) é refeita aqui; com o cofre ainda fora, ou com a chave de outra conta,
    // nada sai.
    private String chaveConferida() {
        if (!chave.conferida() && chave.precisaReconferir()) {
            try {
                if (chave.reconferir()) Log.i(CascaPlugin.TAG, "a conta da chave da equipe conferida de novo: o envio sai");
            } catch (RuntimeException e) {
                return null;
            }
        }
        // O piloto lê no log que o envio saiu sem a página (a partida nova com a tela apagada), uma vez por partida.
        if (chave.retomarConferencia()) {
            Log.i(CascaPlugin.TAG, "a conta da chave da equipe conferida numa partida de antes: o envio sai sem esperar a "
                + "página");
        }
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
        if (loteRecusado(resp.status)) return Resultado.DESCARTADO;
        if (resp.status != 200) return Resultado.SEM_REDE;
        Object coleta;
        try {
            coleta = new JSONObject(resp.corpo).opt("coleta");
        } catch (JSONException e) {
            return Resultado.SEM_REDE;
        }
        if (Boolean.FALSE.equals(coleta)) {
            chave.apagar();
            fila.limpar();
            return Resultado.PARAR;
        }
        // Só o coleta:true confirma; sem o campo, nulo ou de outro tipo, é a resposta fora do contrato: nada sai nem se apaga.
        return Boolean.TRUE.equals(coleta) ? Resultado.ENVIADO : Resultado.SEM_REDE;
    }

    /**
     * O Nexus recusou o lote: o 4xx que não é o 401 (que para) nem um dos que não falam do lote: o 403, o 404 e o 405 (o
     * acesso ou a rota), o 408 e o 429 (o tempo e o excesso de pedidos).
     */
    static boolean loteRecusado(int status) {
        if (status < 400 || status >= 500) return false;
        switch (status) {
            case 401:
            case 403:
            case 404:
            case 405:
            case 408:
            case 429:
                return false;
            default:
                return true;
        }
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
