package br.com.gridco.campo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Quantas vezes a tela pode ser recriada porque o renderer da WebView morreu: 3 em 60 s, por processo. O
 * onRenderProcessGone recria a tela (sem isso o Android derruba o app inteiro), mas o renderer que morre sempre na carga
 * (um vídeo grande, a falta de memória num aparelho de 4 GB) fazia a tela piscar para sempre, sem mensagem nenhuma.
 * Passado o teto, a tela recriada mostra a página de erro local (o www/erro.html), cujo "Tentar de novo" abre o App de
 * novo pela mão do técnico. A recusada não ocupa a janela.
 *
 * O relógio é o do processo (o elapsedRealtime, que não volta quando a hora do aparelho muda).
 */
final class TetoDeRecriacao {

    static final int RECRIACOES_POR_JANELA = 3;
    static final long JANELA_MS = 60_000L;

    private final LongSupplier relogio;
    private final Deque<Long> recriacoes = new ArrayDeque<>();

    TetoDeRecriacao(LongSupplier relogio) {
        this.relogio = relogio;
    }

    /** Conta uma recriação agora, se cabe na janela, e diz se ela pode abrir o App (senão, a página de erro). */
    synchronized boolean cabe() {
        long agora = relogio.getAsLong();
        while (!recriacoes.isEmpty() && agora - recriacoes.peekFirst() >= JANELA_MS) recriacoes.pollFirst();
        if (recriacoes.size() >= RECRIACOES_POR_JANELA) return false;
        recriacoes.addLast(agora);
        return true;
    }
}
