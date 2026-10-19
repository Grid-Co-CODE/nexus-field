package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * O expediente e o dia na hora de Brasília (America/Sao_Paulo), que a casca usa para três coisas: a emergência que chega
 * fora do dia útil das 07:00 às 17:00 vai ao canal silencioso, o resumo sai da barra no fim do dia e a OS do toque vale
 * até o fim do dia. Feriado conta como dia útil (como no servidor, spec 2026-10-18, seção 6). Os instantes são absolutos:
 * o fuso do aparelho não muda nada.
 */
public class ExpedienteTest {

    private static final TimeZone SAO_PAULO = TimeZone.getTimeZone("America/Sao_Paulo");
    private final TimeZone fusoDoAparelho = TimeZone.getDefault();

    @After
    public void devolverOFuso() {
        TimeZone.setDefault(fusoDoAparelho);
    }

    /** O instante de uma data e hora na hora de Brasília. */
    static long em(int ano, int mes, int dia, int hora, int minuto) {
        GregorianCalendar c = new GregorianCalendar(SAO_PAULO, Locale.ROOT);
        c.clear();
        c.set(ano, mes - 1, dia, hora, minuto, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void diaUtilDasSeteAsDezessete() {
        // 19/10/2026 é uma segunda-feira; 16/10, uma sexta; 17 e 18, o fim de semana.
        assertFalse("segunda 06:59", Expediente.dentro(em(2026, 10, 19, 6, 59)));
        assertTrue("segunda 07:00", Expediente.dentro(em(2026, 10, 19, 7, 0)));
        assertTrue("terça 10:00", Expediente.dentro(em(2026, 10, 20, 10, 0)));
        assertTrue("sexta 16:59", Expediente.dentro(em(2026, 10, 16, 16, 59)));
        assertFalse("sexta 17:00 já é fora", Expediente.dentro(em(2026, 10, 16, 17, 0)));
        assertFalse("sexta 19:40", Expediente.dentro(em(2026, 10, 16, 19, 40)));
        assertFalse("sábado 10:00", Expediente.dentro(em(2026, 10, 17, 10, 0)));
        assertFalse("domingo 10:00", Expediente.dentro(em(2026, 10, 18, 10, 0)));
        assertFalse("segunda 00:30", Expediente.dentro(em(2026, 10, 19, 0, 30)));
    }

    @Test
    public void feriadoContaComoDiaUtil() {
        // 02/11/2026, Finados, é uma segunda-feira: a casca não tem a tabela de feriados (nem o servidor).
        assertTrue(Expediente.dentro(em(2026, 11, 2, 10, 0)));
    }

    @Test
    public void oFusoDoAparelhoNaoMudaOExpediente() {
        // O mesmo instante, com o aparelho em UTC ou em Tóquio: sexta 16:50 em Brasília é 19:50 em UTC.
        long sexta1650 = em(2026, 10, 16, 16, 50);
        for (String fuso : new String[] {"UTC", "Asia/Tokyo", "America/Manaus"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(fuso));
            assertTrue(fuso, Expediente.dentro(sexta1650));
            assertFalse(fuso, Expediente.dentro(em(2026, 10, 16, 19, 40)));
            assertEquals(fuso, 20261016, Expediente.dia(sexta1650));
        }
    }

    @Test
    public void oFimDoDiaEAMeiaNoiteSeguinteEmBrasilia() {
        assertEquals(em(2026, 10, 17, 0, 0), Expediente.fimDoDia(em(2026, 10, 16, 16, 50)));
        assertEquals(em(2026, 10, 17, 0, 0), Expediente.fimDoDia(em(2026, 10, 16, 0, 0)));
        assertEquals("23:59 ainda é do dia", em(2026, 10, 17, 0, 0), Expediente.fimDoDia(em(2026, 10, 16, 23, 59)));
        // A virada do mês e do ano.
        assertEquals(em(2026, 11, 1, 0, 0), Expediente.fimDoDia(em(2026, 10, 31, 13, 0)));
        assertEquals(em(2027, 1, 1, 0, 0), Expediente.fimDoDia(em(2026, 12, 31, 7, 0)));
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        assertEquals("no aparelho em UTC, o mesmo", em(2026, 10, 17, 0, 0), Expediente.fimDoDia(em(2026, 10, 16, 22, 0)));
    }

    @Test
    public void oDiaEmBrasiliaVemComoAAAAMMDD() {
        assertEquals(20261016, Expediente.dia(em(2026, 10, 16, 0, 0)));
        assertEquals(20261016, Expediente.dia(em(2026, 10, 16, 23, 59)));
        assertEquals(20261017, Expediente.dia(em(2026, 10, 17, 0, 0)));
        // 22:30 em Brasília já é o dia seguinte em UTC: vale o de Brasília.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Calendar utc = Calendar.getInstance();
        utc.setTimeInMillis(em(2026, 10, 16, 22, 30));
        assertEquals(17, utc.get(Calendar.DAY_OF_MONTH));
        assertEquals(20261016, Expediente.dia(em(2026, 10, 16, 22, 30)));
    }
}
