package br.com.gridco.campo;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * O expediente e o dia na hora de Brasília (America/Sao_Paulo), não no fuso do aparelho: os horários das notificações
 * são os da operação (spec 2026-10-18), e o técnico pode estar com o aparelho em outro fuso. O expediente é o do servidor:
 * dia útil (segunda a sexta) das 07:00 às 17:00, e feriado conta como dia útil (a casca não tem a tabela, nem o
 * servidor; spec, seção 6). Usado em três lugares: a emergência que CHEGA fora dele vai ao canal silencioso
 * ({@link Notificacoes#montar}), o resumo sai da barra no fim do dia e a OS do toque vale até o fim do dia.
 *
 * Calendar, e não java.time: o minSdk é 24, e o java.time só existe do Android 8 em diante.
 */
final class Expediente {

    static final TimeZone FUSO = TimeZone.getTimeZone("America/Sao_Paulo");

    /** A primeira hora do expediente (07:00) e a primeira que já é fora dele (17:00). */
    static final int ABRE = 7;
    static final int FECHA = 17;

    private Expediente() {
    }

    // Gregoriano sempre: o calendário do Locale do aparelho poderia ser outro (o budista do th_TH, por exemplo).
    private static Calendar em(long instante) {
        Calendar c = new GregorianCalendar(FUSO, Locale.ROOT);
        c.setTimeInMillis(instante);
        return c;
    }

    /** Se o instante cai num dia útil, das 07:00 às 16:59, em Brasília. */
    static boolean dentro(long instante) {
        Calendar c = em(instante);
        int diaDaSemana = c.get(Calendar.DAY_OF_WEEK);
        if (diaDaSemana == Calendar.SATURDAY || diaDaSemana == Calendar.SUNDAY) return false;
        int hora = c.get(Calendar.HOUR_OF_DAY);
        return hora >= ABRE && hora < FECHA;
    }

    /** A meia-noite seguinte ao instante, em Brasília: o fim do dia dele. */
    static long fimDoDia(long instante) {
        Calendar c = em(instante);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** O dia do instante em Brasília, como AAAAMMDD (16/10/2026 é 20261016). */
    static int dia(long instante) {
        Calendar c = em(instante);
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }
}
