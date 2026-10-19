package br.com.gridco.campo;

import static br.com.gridco.campo.ExpedienteTest.em;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * A OS do toque na emergência guardada no processo: ela vai à página a cada carga do App até a página dizer que a abriu
 * (o osAberta), e vale só até o fim do dia, em Brasília. É o que segura a emergência quando o renderer morre (a tela
 * recriada começa sem o pedido da tela velha e sem o sessionStorage da página) ou quando a OS ficou pendente na página.
 */
public class OsDoToqueTest {

    private long agora = em(2026, 10, 16, 16, 50);
    private final OsDoToque os = new OsDoToque(() -> agora);

    @Test
    public void guardadaEEntregueAteSerEsquecida() {
        assertNull("nada guardado", os.aEntregar());
        os.guardar("12345");
        assertEquals("12345", os.aEntregar());
        assertEquals("entregar não esquece: a próxima carga entrega de novo", "12345", os.aEntregar());
        os.esquecer();
        assertNull(os.aEntregar());
    }

    @Test
    public void aUltimaEmergenciaTocadaVence() {
        os.guardar("111");
        os.guardar("222");
        assertEquals("222", os.aEntregar());
    }

    @Test
    public void aAbertaSoEsqueceElaMesma() {
        // O osAberta da OS velha (a página abriu a pendente) não apaga a nova, tocada depois.
        os.guardar("222");
        os.esquecerSe("111");
        assertEquals("222", os.aEntregar());
        os.esquecerSe("222");
        assertNull(os.aEntregar());
        os.esquecerSe("222"); // de novo (a página repete o osAberta): nada muda
        assertNull(os.aEntregar());
    }

    @Test
    public void valeSoAteOFimDoDiaEmBrasilia() {
        os.guardar("12345");
        agora = em(2026, 10, 16, 23, 59);
        assertEquals("ainda é o dia do toque", "12345", os.aEntregar());
        agora = em(2026, 10, 17, 0, 0);
        assertNull("a emergência de ontem não é o que ele precisa ver hoje", os.aEntregar());
        agora = em(2026, 10, 16, 23, 0);
        assertNull("e saiu de vez (a hora que volta não a traz)", os.aEntregar());
    }
}
