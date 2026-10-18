package br.com.gridco.campo;

import java.util.function.Consumer;

/**
 * O voltar do Android (o gesto ou o botão). O Capacitor 8.5.3 não trata o voltar, e o campo.html não põe nada no
 * histórico ao abrir uma camada: sem isto, o gesto tirava o técnico do app no meio da OS (prova no aparelho de
 * 17/10/2026). Com a página do App aberta, o nativo pergunta a ela (window.__gcCascaVoltar, do www/casca/inicio.js),
 * que fecha a camada de cima pelo mesmo botão ou função que o técnico usaria e responde true. Qualquer outra resposta
 * (nada aberto, a página sem o script de início, um erro) manda o app para o fundo. Fora da página do App (a tela de erro
 * local, o login da Microsoft) vai direto para o fundo. A tela nunca é fechada: voltar ao app pelo ícone ou pelos
 * recentes encontra a página onde estava, sem recarregar.
 *
 * Uma pergunta por vez, com prazo de 1,5 s: o renderer travado não pode segurar o voltar, e a resposta que chega depois
 * do prazo não decide nada. Só na thread de UI (a do OnBackPressedCallback, do evaluateJavascript e do postDelayed).
 */
final class Voltar {

    /** Quanto a página tem para responder. Passou disso, o app vai para o fundo. */
    static final long PRAZO_MS = 1_500L;

    /**
     * O que o nativo roda na página. Devolve true só se a função da casca existe e respondeu exatamente true; o erro da
     * página vira false (sem exceção no nativo). Sem aspas, para ir como está ao evaluateJavascript.
     */
    static final String PERGUNTA = "(function(){try{var f=window.__gcCascaVoltar;"
        + "return typeof f==='function'?f()===true:false}catch(e){return false}})()";

    private static final String CAMINHO_DO_APP = "/api/app";

    /** A página que está na WebView. */
    interface Pagina {
        /** O endereço aberto agora; null se não há página (a tela saiu, o renderer morreu). */
        String url();

        /** Roda o js e entrega a resposta como o evaluateJavascript (o JSON do resultado), na thread de UI. */
        void perguntar(String js, Consumer<String> resposta);
    }

    /** O postDelayed da thread de UI. */
    interface Agenda {
        void depois(long ms, Runnable tarefa);
    }

    private final String origem;
    private final Pagina pagina;
    private final Agenda agenda;
    private final Runnable minimizar;
    private int ultima = 0;     // a numeração das perguntas
    private int emCurso = 0;    // a pergunta que ainda vale (0: nenhuma)

    Voltar(String origem, Pagina pagina, Agenda agenda, Runnable minimizar) {
        this.origem = origem;
        this.pagina = pagina;
        this.agenda = agenda;
        this.minimizar = minimizar;
    }

    /** O voltar foi pedido. */
    void aoVoltar() {
        // O segundo toque enquanto a página pensa não vira outra pergunta: duas respostas fechariam duas camadas.
        if (emCurso != 0) return;
        if (!eDoApp(pagina.url(), origem)) {
            minimizar.run();
            return;
        }
        final int esta = ++ultima;
        emCurso = esta;
        agenda.depois(PRAZO_MS, () -> {
            if (emCurso != esta) return; // já respondida
            emCurso = 0;
            minimizar.run();
        });
        try {
            pagina.perguntar(PERGUNTA, resposta -> {
                if (emCurso != esta) return; // chegou depois do prazo: o app já foi para o fundo
                emCurso = 0;
                if (!consumiu(resposta)) minimizar.run();
            });
        } catch (RuntimeException e) {
            // A WebView não aceitou a pergunta (destruída no meio): o voltar não pode ficar sem efeito.
            emCurso = 0;
            minimizar.run();
        }
    }

    /** A página fechou uma camada (ou consumiu o voltar de propósito, numa espera que trava a tela)? */
    static boolean consumiu(String resposta) {
        return "true".equals(resposta);
    }

    /**
     * O endereço é a página do App, na origem do server.url? O caminho tem de ser /api/app inteiro (seguido de nada, de
     * barra, de '?' ou de '#'): /api/appx não é o App.
     */
    static boolean eDoApp(String url, String origem) {
        if (url == null || origem == null || origem.isEmpty()) return false;
        String base = (origem.endsWith("/") ? origem.substring(0, origem.length() - 1) : origem) + CAMINHO_DO_APP;
        if (!url.startsWith(base)) return false;
        if (url.length() == base.length()) return true;
        char depois = url.charAt(base.length());
        return depois == '/' || depois == '?' || depois == '#';
    }
}
