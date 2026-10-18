package br.com.gridco.campo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * O teto das recriações da tela quando o renderer da WebView morre: no máximo 3 em 60 s por processo. Passado o teto,
 * a tela recriada mostra a página de erro local em vez do App, para o renderer que morre sempre na carga (um vídeo, a
 * falta de memória) não virar uma tela que pisca para sempre. O relógio é falso.
 */
public class TetoDeRecriacaoTest {

    private final AtomicLong agora = new AtomicLong(1_000_000L);
    private final TetoDeRecriacao teto = new TetoDeRecriacao(agora::get);

    @Test
    public void tresEmSessentaSegundosEDepoisNao() {
        assertTrue(teto.cabe());
        agora.addAndGet(10_000);
        assertTrue(teto.cabe());
        agora.addAndGet(10_000);
        assertTrue(teto.cabe());
        agora.addAndGet(10_000);
        assertFalse("a 4ª em 30 s vai para a página de erro", teto.cabe());
        assertFalse(teto.cabe());
    }

    @Test
    public void aJanelaAndaEAsVelhasSaem() {
        assertTrue(teto.cabe()); // 0 s
        agora.addAndGet(20_000);
        assertTrue(teto.cabe()); // 20 s
        agora.addAndGet(20_000);
        assertTrue(teto.cabe()); // 40 s
        agora.addAndGet(19_999);
        assertFalse("aos 59,999 s a primeira ainda conta", teto.cabe());
        agora.addAndGet(1);
        assertTrue("aos 60 s a primeira saiu da janela", teto.cabe());
    }

    @Test
    public void oQueNaoCoubeNaoOcupaAJanela() {
        assertTrue(teto.cabe());
        assertTrue(teto.cabe());
        assertTrue(teto.cabe());
        agora.addAndGet(30_000);
        assertFalse(teto.cabe());
        agora.addAndGet(30_000);
        assertTrue("as três de 60 s atrás saíram; a recusada não contou", teto.cabe());
    }
}
