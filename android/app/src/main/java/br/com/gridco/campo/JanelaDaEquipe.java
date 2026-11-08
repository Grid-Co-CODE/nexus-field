package br.com.gridco.campo;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * A janela em que a casca coleta a posição da equipe: das 06:00 (vale) às 22:00 (não vale), na hora do celular.
 * Pura, sem Android: o fuso entra por parâmetro para o teste provar Brasília e Cuiabá (um técnico em outro fuso
 * seria coletado na hora errada se a conta usasse o fuso do servidor).
 */
final class JanelaDaEquipe {

    static final int HORA_DE_INICIO = 6;
    static final int HORA_DE_FIM = 22;

    private JanelaDaEquipe() {}

    /** 06:00:00 vale; 22:00:00 não vale. */
    static boolean dentro(long instante, TimeZone fuso) {
        Calendar c = Calendar.getInstance(fuso);
        c.setTimeInMillis(instante);
        int hora = c.get(Calendar.HOUR_OF_DAY);
        return hora >= HORA_DE_INICIO && hora < HORA_DE_FIM;
    }

    /** As 22:00 do dia (no fuso dado) a que o instante pertence, em epoch ms. */
    static long fimDaJanela(long instante, TimeZone fuso) {
        Calendar c = Calendar.getInstance(fuso);
        c.setTimeInMillis(instante);
        c.set(Calendar.HOUR_OF_DAY, HORA_DE_FIM);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** O deslocamento do fuso em relação ao UTC, em minutos, naquele instante (Brasília: -180). */
    static int fusoEmMinutos(long instante, TimeZone fuso) {
        return fuso.getOffset(instante) / 60_000;
    }
}
