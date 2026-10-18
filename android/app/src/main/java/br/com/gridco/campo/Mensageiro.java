package br.com.gridco.campo;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Traduz as mensagens do canal gcCasca (as que o www/casca/inicio.js manda da página) em chamadas ao
 * {@link ArmazemEspelho} e em {@link Acoes} do plugin, e monta a resposta do idbPedir. O protocolo é o do script:
 *
 * <pre>
 *   ls {k, v} · lsDel {k} · idbPut {k, tipo, v} · idbDel {k} · idbPedaco {k, id, i, n, mime, b64} · sentinela
 *   precisoRetrato {motivo: 'perda' | 'fila'} · restaurado {chaves, naoCouberam, fila}
 *   idbPedir {motivo: 'perda' | 'idb'} · fora {url} · erro {onde, msg}
 * </pre>
 *
 * E a resposta do idbPedir, pela ponte da mesma mensagem: idbItem, idbPedaco e idbFim ({@link #enviarIdb}), ou
 * idbRecusado ({@link #recusarIdb}) quando o disjuntor do dia não deixa.
 *
 * Regras que o código não mostra sozinho:
 * <ul>
 *   <li><b>Nada do técnico vai ao log.</b> Os nomes das chaves têm e-mail (gc_ronda_&lt;email&gt;...), e o
 *       {@code naoCouberam} do restaurado é uma lista deles. Todo aviso sai por {@link Acoes#erro} com um rótulo nosso e
 *       contagens. Do erro que vem da página, só o {@code onde} (da lista {@link #ONDES_DA_PAGINA}) e a contagem; o
 *       texto nunca, porque o QuotaExceededError do Chromium cita a chave entre aspas ("Setting the value of '...'
 *       exceeded the quota") e o do JSON.parse, o próprio texto.</li>
 *   <li><b>Nunca lança.</b> As escritas do armazém lançam RuntimeException (disco cheio, pedaço com índice impossível):
 *       o canal engole, conta e devolve false. Uma exceção aqui derrubaria o app inteiro, e a página não tem o que fazer
 *       com ela.</li>
 *   <li><b>Todo precisoRetrato é atendido</b> com um retrato completo novo (id novo), porque a página parou a carga
 *       (win.stop) e espera a recarga: sem ela, fica em branco até matar o app. O disjuntor segura o laço: no máximo
 *       {@value #COMPLETOS_POR_JANELA} completos em 60 s, contados por processo ({@link Disjuntor}); daí em diante, até a
 *       janela andar, a recarga vai com o retrato sem conferência ({@link #semConferencia}), com que a página não para
 *       a carga.</li>
 *   <li><b>No máximo {@value #RESTAURACOES_POR_DIA} restaurações por 24 h no aparelho, em duas contas</b>
 *       ({@link DisjuntorDaPerda}): uma para as de perda (o precisoRetrato 'perda') e outra para as só do IndexedDB (o
 *       idbPedir que não é da perda); uma nunca gasta a vaga da outra. A página refaz a restauração que não terminou na
 *       carga seguinte, e o disco cheio que recusa sempre faria disso um laço que segura a página a cada abertura.
 *       Estourada a da perda, ela recarrega sem conferência (a página segue sem espelho até a janela andar); estourada a
 *       do IndexedDB, o idbPedir ganha um idbRecusado (a página abre na hora).</li>
 * </ul>
 *
 * Não é seguro entre threads: o plugin chama {@link #tratar} e {@link #enviarIdb} sempre da mesma thread (a do
 * espelho), uma mensagem depois da outra, na ordem em que a página as mandou.
 */
public final class Mensageiro {

    /** O pedaço do Blob na resposta do idbPedir: o mesmo que o script usa na ida (TAMANHO_PEDACO do inicio.js). */
    static final int TAMANHO_PEDACO = 524288;

    static final long JANELA_DO_DISJUNTOR_MS = 60_000L;
    static final int COMPLETOS_POR_JANELA = 2;

    static final long JANELA_DA_PERDA_MS = 24L * 60 * 60 * 1000;
    static final int RESTAURACOES_POR_DIA = 3;

    /**
     * Os rótulos ({@code onde}) que o www/casca/inicio.js manda no {@code {t:'erro'}}. Lista fechada: o campo vem da
     * página e pode trazer qualquer coisa, então o que não estiver aqui vai ao log como 'outro'. O
     * testes/canal.test.mjs confere que ela é a mesma do script (rótulo novo lá entra aqui no mesmo trabalho).
     */
    static final Set<String> ONDES_DA_PAGINA = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "canal", "espelho-ls", "lote", "setItem", "removeItem", "descarregar",
        "sentinela", "marca", "perda", "fila", "stop", "precisoRetrato", "restaurado",
        "reenvio",
        "idb", "idbOpen", "idbPut", "idbDel", "idbPedaco", "idbEnvio", "idbPedir", "idbReconciliar", "idbRestaurar",
        "idbSentinela",
        "janela", "open", "fora", "camada", "close", "location", "location.assign", "location.replace",
        "location.href", "document.open", "document.write", "document.writeln", "document.close",
        "voltar")));

    /** O que o canal pede ao plugin. Todas chegam na thread do espelho; quem mexe na WebView passa para a de UI. */
    public interface Acoes {
        /**
         * A página parou a carga e pediu o retrato: troque o script de início por este (já decidido: o completo, ou o
         * leve sem as versões da fila quando o disjuntor desarmou) e recarregue a página.
         */
        void precisoRetrato(JSONObject retrato);

        /** A página aplicou o retrato completo: troque o script de início por este, o leve fresco, sem recarregar. */
        void restaurado(JSONObject retratoLeve);

        /** A página pediu o IndexedDB guardado: responda pela ponte da própria mensagem com {@link #enviarIdb}. */
        void idbPedir();

        /** O pedido do IndexedDB esbarrou no disjuntor do dia: responda pela ponte da mensagem com {@link #recusarIdb}. */
        void idbRecusado();

        /** Um endereço http ou https de fora do App (o "Conectar Fracttal"), para a Custom Tab. */
        void abrirFora(String url);

        /** Um aviso para o log. Nunca traz nome de chave, valor, URL com parâmetros nem e-mail. */
        void erro(String onde, String msg);
    }

    /** Por onde sai cada mensagem da resposta do idbPedir. Devolve false quando a ponte caiu: o envio para ali. */
    public interface Saida {
        boolean enviar(String mensagem);
    }

    /**
     * A contagem do disjuntor, UMA por processo (o plugin a guarda num campo estático, como o espelho). Caso que a
     * criou: o renderer que morre na recarga com o retrato completo faz a tela ser recriada, com plugin, Mensageiro e
     * sessionStorage novos (a trava da página some junto); com a contagem no Mensageiro, cada recriação ganharia outro
     * completo e nada pararia o laço. Sincronizada: na recriação, a thread do espelho da tela velha ainda esvazia a fila
     * dela enquanto a da nova começa.
     */
    static final class Disjuntor {
        private final Deque<Long> completos = new ArrayDeque<>();
        private final Deque<Long> pedidos = new ArrayDeque<>();

        /** Anota um pedido em {@code agora} e diz se um retrato completo pode sair (e, se pode, já o conta). */
        synchronized boolean liberaCompleto(long agora) {
            esquecerAntesDe(completos, agora);
            esquecerAntesDe(pedidos, agora);
            pedidos.addLast(agora);
            if (completos.size() >= COMPLETOS_POR_JANELA) return false;
            completos.addLast(agora);
            return true;
        }

        /** Os pedidos da janela, para o aviso. */
        synchronized int pedidosNaJanela() {
            return pedidos.size();
        }

        private static void esquecerAntesDe(Deque<Long> momentos, long agora) {
            while (!momentos.isEmpty() && agora - momentos.peekFirst() >= JANELA_DO_DISJUNTOR_MS) momentos.pollFirst();
        }
    }

    /**
     * As restaurações dos últimos 24 h NO APARELHO, em duas contas separadas, cada uma com no máximo
     * {@link Mensageiro#RESTAURACOES_POR_DIA}: a da perda ({@link #liberaPerda}, o precisoRetrato 'perda') e a só do
     * IndexedDB ({@link #liberaIdb}, o idbPedir que não é da perda). Caso que o criou: a página refaz na carga seguinte a
     * restauração que não terminou (a marca da perda e o banco sentinela da casca só saem com ela inteira), e o disco
     * cheio, que recusa toda gravação, faria cada abertura do app pedir tudo de novo e segurar a página.
     *
     * Separadas porque, com uma conta só, as restaurações só do IndexedDB (o banco sentinela que falta a cada carga com o
     * disco cheio) gastavam as vagas do dia, e a perda de verdade que viesse depois recarregava sem o retrato completo: a
     * página seguia um dia sem espelho, com a cópia boa parada no nativo. A 'idb' nunca gasta a vaga da 'perda', nem o
     * contrário.
     *
     * A contagem fica fora da cota do IndexedDB e atravessa o processo (o renderer que morre recria a tela; o Android
     * mata o app em segundo plano): na {@link Memoria}, que no app é o SharedPreferences, uma chave por conta. Se ela não
     * gravar (o disco cheio pega o SharedPreferences também), vale a contagem deste objeto, que o plugin guarda um por
     * processo.
     *
     * O relógio é o de parede (a janela atravessa a reinicialização do aparelho, que zera o elapsedRealtime). Uma marca
     * "do futuro" (a hora do aparelho voltou) não conta: travaria o disjuntor pelo tempo que a hora voltou. Custo: trocar
     * a hora rende mais tentativas.
     */
    static final class DisjuntorDaPerda {

        /** A chave da conta da perda no SharedPreferences: os instantes, em milissegundos, separados por vírgula. */
        static final String CHAVE = "restauracoes";

        /** A chave da conta só do IndexedDB, no mesmo formato. */
        static final String CHAVE_IDB = "restauracoes_idb";

        /** Onde as contagens ficam entre os processos, cada uma na sua chave. */
        interface Memoria {
            String ler(String chave);

            void gravar(String chave, String valor);
        }

        private final Conta daPerda;
        private final Conta soDoIdb;

        DisjuntorDaPerda(Memoria memoria, LongSupplier relogioDeParede) {
            this.daPerda = new Conta(memoria, CHAVE, relogioDeParede);
            this.soDoIdb = new Conta(memoria, CHAVE_IDB, relogioDeParede);
        }

        /** Conta uma restauração da perda agora, se cabe na janela dela, e diz se ela pode sair. */
        synchronized boolean liberaPerda() {
            return daPerda.libera();
        }

        /** O mesmo para uma restauração só do IndexedDB, na conta dela. */
        synchronized boolean liberaIdb() {
            return soDoIdb.libera();
        }

        /** Uma das contas: os instantes da janela, na memória e no processo. */
        private static final class Conta {
            private final Memoria memoria;
            private final String chave;
            private final LongSupplier relogio;
            private List<Long> doProcesso = new ArrayList<>();

            Conta(Memoria memoria, String chave, LongSupplier relogio) {
                this.memoria = memoria;
                this.chave = chave;
                this.relogio = relogio;
            }

            boolean libera() {
                long agora = relogio.getAsLong();
                List<Long> naJanela = naJanela(ler(), agora);
                List<Long> doProcessoNaJanela = naJanela(doProcesso, agora);
                if (doProcessoNaJanela.size() > naJanela.size()) naJanela = doProcessoNaJanela;
                boolean libera = naJanela.size() < RESTAURACOES_POR_DIA;
                if (libera) naJanela.add(agora);
                doProcesso = naJanela;
                StringBuilder texto = new StringBuilder();
                for (Long t : naJanela) texto.append(texto.length() == 0 ? "" : ",").append(t);
                try {
                    memoria.gravar(chave, texto.toString());
                } catch (RuntimeException e) {
                    // Fica a contagem do processo.
                }
                return libera;
            }

            private List<Long> ler() {
                List<Long> lidos = new ArrayList<>();
                String texto;
                try {
                    texto = memoria.ler(chave);
                } catch (RuntimeException e) {
                    return lidos;
                }
                if (texto == null) return lidos;
                for (String pedaco : texto.split(",")) {
                    try {
                        lidos.add(Long.parseLong(pedaco.trim()));
                    } catch (NumberFormatException e) {
                        // Um pedaço torto não conta.
                    }
                }
                return lidos;
            }

            private static List<Long> naJanela(List<Long> momentos, long agora) {
                List<Long> dentro = new ArrayList<>();
                for (Long t : momentos) if (t <= agora && agora - t < JANELA_DA_PERDA_MS) dentro.add(t);
                return dentro;
            }
        }

        /** A memória no SharedPreferences do app. Um valor de outro tipo na chave conta como vazio. */
        static Memoria naPreferencia(SharedPreferences prefs) {
            return new Memoria() {
                @Override
                public String ler(String chave) {
                    try {
                        return prefs.getString(chave, "");
                    } catch (ClassCastException e) {
                        return "";
                    }
                }

                // O commit (e não o apply) de propósito: roda na thread do espelho, e a contagem tem de estar no disco
                // antes da recarga que vem logo depois, porque o caso é justo o do processo que morre (o renderer que
                // morre na restauração).
                @SuppressLint("ApplySharedPref")
                @Override
                public void gravar(String chave, String valor) {
                    prefs.edit().putString(chave, valor).commit();
                }
            };
        }

        /** A memória só deste objeto (o teste, e o Mensageiro do teste). */
        static Memoria naMemoria() {
            return new Memoria() {
                private final Map<String, String> valores = new HashMap<>();

                @Override
                public String ler(String chave) {
                    String valor = valores.get(chave);
                    return valor == null ? "" : valor;
                }

                @Override
                public void gravar(String chave, String novo) {
                    valores.put(chave, novo);
                }
            };
        }
    }

    private final ArmazemEspelho armazem;
    private final Acoes acoes;
    private final Disjuntor disjuntor;
    private final DisjuntorDaPerda perdas;
    private final LongSupplier relogio;
    private int falhas = 0;
    private int errosDaPagina = 0;

    /**
     * @param disjuntor o do processo: a tela recriada passa o mesmo (ver {@link Disjuntor})
     * @param perdas    o do aparelho, com as duas contas do dia (ver {@link DisjuntorDaPerda}), também um por processo
     */
    public Mensageiro(ArmazemEspelho armazem, Acoes acoes, Disjuntor disjuntor, DisjuntorDaPerda perdas) {
        // O relógio que não anda para trás com a troca de hora do aparelho.
        this(armazem, acoes, disjuntor, perdas, SystemClock::elapsedRealtime);
    }

    /** Para o teste: o relógio dos dois disjuntores, em milissegundos, e o do dia só na memória. */
    Mensageiro(ArmazemEspelho armazem, Acoes acoes, Disjuntor disjuntor, LongSupplier relogio) {
        this(armazem, acoes, disjuntor, new DisjuntorDaPerda(DisjuntorDaPerda.naMemoria(), relogio), relogio);
    }

    Mensageiro(ArmazemEspelho armazem, Acoes acoes, Disjuntor disjuntor, DisjuntorDaPerda perdas, LongSupplier relogio) {
        this.armazem = armazem;
        this.acoes = acoes;
        this.disjuntor = disjuntor;
        this.perdas = perdas;
        this.relogio = relogio;
    }

    /**
     * Trata uma mensagem da página. Devolve true quando ela foi entregue ao armazém ou ao plugin, e false quando foi
     * recusada (não é JSON, tipo desconhecido, campo que falta ou torto) ou quando a escrita falhou. Nunca lança.
     * O precisoRetrato com motivo torto é recusado (false) e recarrega do mesmo jeito: a página parou e espera.
     */
    public boolean tratar(String json) {
        JSONObject m;
        try {
            m = new JSONObject(json);
        } catch (JSONException | RuntimeException e) {
            // A mensagem do JSONException cita o texto, que pode ter chave e valor: não vai ao log.
            avisar("canal", "mensagem que não é JSON");
            return false;
        }
        String t = texto(m, "t");
        if (t == null) {
            avisar("canal", "mensagem sem tipo");
            return false;
        }
        try {
            switch (t) {
                case "ls": {
                    String k = texto(m, "k");
                    String v = texto(m, "v");
                    if (k == null || v == null) return recusar(t);
                    armazem.gravarLs(k, v);
                    return true;
                }
                case "lsDel": {
                    String k = texto(m, "k");
                    if (k == null) return recusar(t);
                    armazem.apagarLs(k);
                    return true;
                }
                case "idbPut": {
                    String k = texto(m, "k");
                    String tipo = texto(m, "tipo");
                    String v = texto(m, "v");
                    if (k == null || tipo == null || v == null) return recusar(t);
                    armazem.gravarIdbTexto(k, tipo, v);
                    return true;
                }
                case "idbDel": {
                    String k = texto(m, "k");
                    if (k == null) return recusar(t);
                    armazem.apagarIdb(k);
                    return true;
                }
                case "idbPedaco":
                    return pedaco(m);
                case "sentinela":
                    // Chega em toda carga até o retrato dizer temEspelho; marcar de novo não muda nada.
                    armazem.marcarSentinela();
                    return true;
                case "precisoRetrato":
                    return atenderRetrato(texto(m, "motivo"));
                case "restaurado":
                    return restaurado(m);
                case "idbPedir":
                    // A restauração da perda já passou pelo disjuntor do dia no precisoRetrato; a só do IndexedDB
                    // ('idb', ou sem motivo) passa aqui, a cada pedido, na conta dela (nunca gasta a vaga da perda).
                    if (!"perda".equals(texto(m, "motivo")) && !perdas.liberaIdb()) {
                        avisar("disjuntor", RESTAURACOES_POR_DIA + " restaurações só do IndexedDB em 24 h no aparelho; "
                            + "a próxima fica para a próxima janela");
                        acoes.idbRecusado();
                        return true;
                    }
                    acoes.idbPedir();
                    return true;
                case "fora": {
                    String url = texto(m, "url");
                    if (url == null || !httpOuHttps(url)) {
                        avisar(t, "endereço recusado: só http ou https abre fora");
                        return false;
                    }
                    acoes.abrirFora(url);
                    return true;
                }
                case "erro": {
                    // Só o rótulo e a contagem. O texto do erro nunca vai ao log: o QuotaExceededError do Chromium cita
                    // a chave entre aspas, e o do JSON.parse, o próprio texto (um nome, num rascunho que não é JSON).
                    // Nenhuma limpeza alcança tudo isso.
                    errosDaPagina++;
                    String onde = texto(m, "onde");
                    avisar(onde != null && ONDES_DA_PAGINA.contains(onde) ? onde : "outro",
                        "erro da página (" + errosDaPagina + " nesta partida)");
                    return true;
                }
                default:
                    // O tipo não vai ao log: uma mensagem torta pode ter qualquer coisa nele.
                    avisar("canal", "tipo de mensagem desconhecido");
                    return false;
            }
        } catch (RuntimeException e) {
            // Só a contagem: a mensagem da exceção pode citar o que estava sendo gravado.
            falhas++;
            avisar(t, "falhou no espelho (" + falhas + " falha(s) nesta partida)");
            return false;
        }
    }

    private boolean pedaco(JSONObject m) {
        String k = texto(m, "k");
        String id = texto(m, "id");
        Integer i = inteiro(m, "i");
        Integer n = inteiro(m, "n");
        String b64 = texto(m, "b64");
        if (k == null || id == null || i == null || n == null || b64 == null) return recusar("idbPedaco");
        // O decodificador do Android pula em silêncio o que não é do alfabeto: sem esta conferência, um pedaço torto
        // viraria bytes errados dentro da foto, sem aviso.
        if (!base64Valido(b64)) return recusar("idbPedaco");
        byte[] dados;
        try {
            dados = Base64.decode(b64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return recusar("idbPedaco");
        }
        armazem.receberPedaco(k, id, i, n, texto(m, "mime"), dados);
        return true;
    }

    // Todo pedido recarrega a página (ela parou e espera). O completo sai enquanto o disjuntor deixa; se ele falhar ao
    // ler o espelho, a recarga vai do mesmo jeito, sem a conferência da fila.
    private boolean atenderRetrato(String motivo) {
        if (!"perda".equals(motivo) && !"fila".equals(motivo)) {
            // O pedido é recusado (o Retrato.completo lança com outro motivo, e o valor recebido não vai ao log), mas a
            // página que o mandou já chamou win.stop(): sem a recarga, ficaria em branco até matar o app.
            avisar("precisoRetrato", "motivo de retrato desconhecido; recarrega sem conferir a fila");
            acoes.precisoRetrato(semConferencia(armazem));
            return false;
        }
        JSONObject retrato;
        if (!disjuntor.liberaCompleto(relogio.getAsLong())) {
            // Caso que o disjuntor segura: o sessionStorage que não guarda a trava da página (o "limpar só o login", que
            // a apaga, ou a tela recriada depois do renderer que morreu, que começa sem ela) faz cada carga parar e pedir
            // de novo. O sem conferência leva o semParar, e a carga seguinte não para. Vem antes do disjuntor do dia: a
            // recarga em laço não gasta as restaurações dele.
            avisar("disjuntor", disjuntor.pedidosNaJanela() + " pedidos de retrato em 60 s; recarrega sem conferir a fila");
            retrato = semConferencia(armazem);
        } else if ("perda".equals(motivo) && !perdas.liberaPerda()) {
            // A restauração da perda que não termina (o disco cheio) seria refeita a cada abertura do app. Com o
            // semParar, a página segue sem espelho e com a marca da perda de pé; a próxima janela restaura de novo.
            avisar("disjuntor", RESTAURACOES_POR_DIA + " restaurações da perda em 24 h no aparelho; recarrega sem "
                + "conferir, e a página segue sem espelho até a janela andar");
            retrato = semConferencia(armazem);
        } else {
            try {
                retrato = Retrato.completo(armazem, motivo);
            } catch (RuntimeException e) {
                falhas++;
                avisar("precisoRetrato", "o retrato completo falhou; recarrega sem conferir a fila (" + falhas
                    + " falha(s) nesta partida)");
                retrato = semConferencia(armazem);
            }
        }
        acoes.precisoRetrato(retrato);
        return true;
    }

    // A página devolveu o retrato completo: o script volta ao leve, com as versões da fila e as chaves do IndexedDB
    // de agora (as do começo da partida já estão velhas). Do que não coube, só a contagem (são nomes de chave, com e-mail).
    // A troca sai mesmo que o espelho falhe ao ler: o completo de pé pela partida volta a ser aplicado na carga em que
    // o sessionStorage não tiver o id dele (o "limpar só o login" o apaga), e grava o `ls` e a fila do momento do pedido
    // por cima do trabalho novo. No lugar, o leve sem as versões da fila, como no precisoRetrato.
    private boolean restaurado(JSONObject m) {
        JSONArray naoCouberam = m.optJSONArray("naoCouberam");
        int quantas = naoCouberam == null ? 0 : naoCouberam.length();
        if (quantas > 0) {
            avisar("restaurado", m.optInt("chaves", 0) + " chave(s) voltaram; " + quantas
                + " não couberam no localStorage");
        }
        JSONObject leve;
        try {
            leve = Retrato.leve(armazem);
        } catch (RuntimeException e) {
            falhas++;
            avisar("restaurado", "o retrato leve falhou; o script fica sem conferir a fila (" + falhas
                + " falha(s) nesta partida)");
            leve = semConferencia(armazem);
        }
        acoes.restaurado(leve);
        return true;
    }

    /**
     * O leve sem as versões da fila e com {@code semParar: true}: a página não confere a fila nesta carga (espelha a
     * dela) e não chama win.stop() por motivo nenhum. Sem o semParar, a perda (a marca no localStorage, ou ele vazio)
     * ainda parava a carga e pedia de novo: com o sessionStorage que não guarda a trava, toda recarga pedia, e o
     * disjuntor só trocava um laço por outro. Com ele, a perda segue com o espelho desligado, como na trava da sessão.
     * Se nem o leve puder ser lido, um retrato que diz que HÁ espelho: dizer o contrário faria uma perda ser tomada por
     * instalação nova, e o que a página gravasse depois iria por cima da cópia boa do nativo.
     */
    static JSONObject semConferencia(ArmazemEspelho armazem) {
        try {
            JSONObject retrato;
            try {
                retrato = Retrato.leve(armazem);
            } catch (RuntimeException e) {
                retrato = new JSONObject();
                retrato.put("temEspelho", true);
                retrato.put("idbChaves", new JSONArray());
            }
            retrato.put("filaHashes", new JSONArray());
            retrato.put("semParar", true);
            return retrato;
        } catch (JSONException e) {
            throw new IllegalStateException("não foi possível montar o retrato sem conferência", e); // não ocorre
        }
    }

    // --- a resposta do idbPedir -----------------------------------------------------------------------------------

    /**
     * Manda à página o IndexedDB guardado, uma entrada por vez e na ordem: texto e JSON como
     * {@code {t:'idbItem', k, tipo, v}}; Blob em {@code {t:'idbPedaco', k, i, n, mime, b64}} de {@value #TAMANHO_PEDACO}
     * bytes (sem id: o script junta pela chave), lido do arquivo pedaço a pedaço, porque o vídeo chega a 15 MB e não pode
     * virar um base64 inteiro na memória. No fim, {@code {t:'idbFim', n}}, com n = chaves distintas mandadas.
     *
     * Premissa (Task 5): durante a restauração a página não escreve no espelho (na perda o espelho está desligado; na
     * perda só do IndexedDB o open da página está segurado), então o arquivo que o paraCadaIdb entrega não muda no meio.
     *
     * @return true se tudo saiu, inclusive o idbFim; false se a ponte caiu (sem o idbFim: o prazo da página resolve)
     */
    public boolean enviarIdb(Saida saida) {
        Envio envio = new Envio(saida);
        armazem.paraCadaIdb(envio);
        if (envio.caiu) {
            avisar("idbPedir", "a resposta parou no meio: " + envio.mandadas.size() + " entrada(s) mandada(s)");
            return false;
        }
        if (envio.ilegiveis > 0) {
            avisar("idbPedir", envio.ilegiveis + " blob(s) ilegível(is) no espelho; a página os tem por pendentes");
        }
        return envio.mandar(json("t", "idbFim", "n", envio.mandadas.size()));
    }

    /**
     * Diz à página que a restauração do IndexedDB não sai agora ({@code {t:'idbRecusado'}}): ela solta o banco das
     * fotos na hora, sem esperar o prazo dela, e a carga de uma janela seguinte pede de novo.
     *
     * @return false se a ponte caiu (a página tem o prazo dela)
     */
    public boolean recusarIdb(Saida saida) {
        try {
            return saida.enviar(json("t", "idbRecusado"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private final class Envio implements ArmazemEspelho.ItemIdb {
        final Saida saida;
        final Set<String> mandadas = new HashSet<>();
        boolean caiu = false;
        int ilegiveis = 0;
        private byte[] buffer;

        Envio(Saida saida) {
            this.saida = saida;
        }

        @Override
        public void aceitar(String k, String tipo, String mime, String texto, File arquivo) {
            if (caiu || k == null) return;
            if ("blob".equals(tipo)) {
                blob(k, mime == null ? "" : mime, arquivo);
            } else if (mandar(json("t", "idbItem", "k", k, "tipo", tipo, "v", texto == null ? "" : texto))) {
                mandadas.add(k);
            } else {
                caiu = true;
            }
        }

        // Um blob ilegível antes do primeiro pedaço não é mandado (a página o tem por pendente, porque o idbChaves do
        // retrato o anunciou); no meio, já foi mandado em parte, e a página conta os pedaços que faltam como falha.
        private void blob(String k, String mime, File arquivo) {
            if (arquivo == null) {
                ilegiveis++;
                return;
            }
            long tamanho = arquivo.length();
            int n = (int) Math.max(1, (tamanho + TAMANHO_PEDACO - 1) / TAMANHO_PEDACO); // o vazio vai num pedaço, vazio
            if (buffer == null) buffer = new byte[TAMANHO_PEDACO];
            try (InputStream in = new FileInputStream(arquivo)) {
                for (int i = 0; i < n; i++) {
                    int tam = (int) Math.min(TAMANHO_PEDACO, tamanho - (long) i * TAMANHO_PEDACO);
                    lerTudo(in, buffer, tam);
                    String b64 = Base64.encodeToString(buffer, 0, tam, Base64.NO_WRAP);
                    if (!mandar(json("t", "idbPedaco", "k", k, "i", i, "n", n, "mime", mime, "b64", b64))) {
                        caiu = true;
                        return;
                    }
                    mandadas.add(k);
                }
            } catch (IOException e) {
                ilegiveis++;
            }
        }

        boolean mandar(String mensagem) {
            try {
                return saida.enviar(mensagem);
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    private static void lerTudo(InputStream in, byte[] destino, int tamanho) throws IOException {
        int lidos = 0;
        while (lidos < tamanho) {
            int r = in.read(destino, lidos, tamanho - lidos);
            if (r < 0) throw new EOFException("o arquivo acabou antes do tamanho"); // encolheu no meio
            lidos += r;
        }
    }

    // --- apoio ------------------------------------------------------------------------------------------------------

    // O base64 do btoa: múltiplo de 4, só o alfabeto padrão e o '=' só nas duas últimas posições. Conferido à mão, sem
    // regex: um pedaço tem ~700 mil caracteres, e a repetição de grupo do java.util.regex estoura a pilha nesse tamanho.
    static boolean base64Valido(String b64) {
        int tamanho = b64.length();
        if (tamanho % 4 != 0) return false;
        for (int i = 0; i < tamanho; i++) {
            char c = b64.charAt(i);
            boolean alfabeto = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+'
                || c == '/';
            if (alfabeto) continue;
            if (c != '=' || i < tamanho - 2) return false;
            if (i == tamanho - 2 && b64.charAt(tamanho - 1) != '=') return false; // "ab=c": o '=' no penúltimo pede outro no último
        }
        return true;
    }

    private static boolean httpOuHttps(String url) {
        Uri uri = Uri.parse(url);
        String esquema = uri.getScheme();
        if (esquema == null) return false;
        esquema = esquema.toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        return ("https".equals(esquema) || "http".equals(esquema)) && host != null && !host.isEmpty();
    }

    private boolean recusar(String t) {
        avisar(t, "mensagem com campo que falta ou torto");
        return false;
    }

    private void avisar(String onde, String msg) {
        try {
            acoes.erro(onde, msg);
        } catch (RuntimeException e) {
            // O aviso nunca derruba o canal.
        }
    }

    private static String texto(JSONObject m, String nome) {
        Object valor = m.opt(nome);
        return valor instanceof String ? (String) valor : null; // o null do JSON e o número não são texto
    }

    private static Integer inteiro(JSONObject m, String nome) {
        Object valor = m.opt(nome);
        if (!(valor instanceof Number)) return null;
        double d = ((Number) valor).doubleValue();
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) return null;
        return (int) d;
    }

    private static String json(Object... pares) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i < pares.length; i += 2) o.put((String) pares[i], pares[i + 1]);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("não foi possível montar a resposta", e); // nome nulo: não ocorre
        }
    }
}
