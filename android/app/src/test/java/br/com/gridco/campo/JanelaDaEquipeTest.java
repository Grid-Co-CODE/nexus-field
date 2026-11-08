package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** A janela da coleta da equipe: das 06:00 (vale) às 22:00 (não vale), na hora do celular. */
public class JanelaDaEquipeTest {

    private static final TimeZone BRASILIA = TimeZone.getTimeZone("America/Sao_Paulo");
    private static final TimeZone CUIABA = TimeZone.getTimeZone("America/Cuiaba");

    private static long em(TimeZone fuso, int h, int m, int s) {
        Calendar c = Calendar.getInstance(fuso);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 9, h, m, s);
        return c.getTimeInMillis();
    }

    @Test
    public void bordasDaJanelaEmBrasilia() {
        assertFalse(JanelaDaEquipe.dentro(em(BRASILIA, 5, 59, 59), BRASILIA));
        assertTrue(JanelaDaEquipe.dentro(em(BRASILIA, 6, 0, 0), BRASILIA));
        assertTrue(JanelaDaEquipe.dentro(em(BRASILIA, 21, 59, 59), BRASILIA));
        assertFalse(JanelaDaEquipe.dentro(em(BRASILIA, 22, 0, 0), BRASILIA));
    }

    @Test
    public void fimDaJanelaEAsVinteEDuasDoMesmoDia() {
        assertEquals(em(BRASILIA, 22, 0, 0), JanelaDaEquipe.fimDaJanela(em(BRASILIA, 21, 0, 0), BRASILIA));
    }

    @Test
    public void valeAHoraDoFusoDoCelular() {
        // 06:30 em Brasília é 05:30 em Cuiabá (uma hora atrás): lá ainda está fora.
        long instante = em(BRASILIA, 6, 30, 0);
        assertTrue(JanelaDaEquipe.dentro(instante, BRASILIA));
        assertFalse(JanelaDaEquipe.dentro(instante, CUIABA));
    }

    @Test
    public void fusoEmMinutos() {
        assertEquals(-180, JanelaDaEquipe.fusoEmMinutos(em(BRASILIA, 12, 0, 0), BRASILIA));
        assertEquals(-240, JanelaDaEquipe.fusoEmMinutos(em(CUIABA, 12, 0, 0), CUIABA));
    }
}
