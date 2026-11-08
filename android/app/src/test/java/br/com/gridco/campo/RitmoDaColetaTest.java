package br.com.gridco.campo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** O ritmo da coleta: 2 min em deslocamento, 10 min parado (3 leituras paradas seguidas). */
public class RitmoDaColetaTest {

    @Test
    public void emMovimentoOIntervaloECurto() {
        RitmoDaColeta r = new RitmoDaColeta();
        assertEquals(120_000, r.proximoIntervaloMs(5.0, 800));
    }

    @Test
    public void tresLeiturasParadasSeguidasAlongam() {
        RitmoDaColeta r = new RitmoDaColeta();
        assertEquals(120_000, r.proximoIntervaloMs(0.2, 10));
        assertEquals(120_000, r.proximoIntervaloMs(0.2, 10));
        assertEquals(600_000, r.proximoIntervaloMs(0.2, 10));
        assertEquals(600_000, r.proximoIntervaloMs(0.2, 10));
    }

    @Test
    public void umaLeituraEmMovimentoVoltaAoCurto() {
        RitmoDaColeta r = new RitmoDaColeta();
        for (int i = 0; i < 3; i++) r.proximoIntervaloMs(0.0, 0);
        assertEquals(600_000, r.proximoIntervaloMs(0.0, 0));
        assertEquals(120_000, r.proximoIntervaloMs(3.0, 400));
        // a contagem recomeça: duas paradas depois disso ainda não alongam
        assertEquals(120_000, r.proximoIntervaloMs(0.0, 0));
        assertEquals(120_000, r.proximoIntervaloMs(0.0, 0));
    }

    @Test
    public void duasParadasSoNaoMudam() {
        RitmoDaColeta r = new RitmoDaColeta();
        assertEquals(120_000, r.proximoIntervaloMs(0.0, 0));
        assertEquals(120_000, r.proximoIntervaloMs(0.5, 100));
    }

    @Test
    public void lentoMasLongeDoUltimoPontoNaoEParado() {
        RitmoDaColeta r = new RitmoDaColeta();
        for (int i = 0; i < 5; i++) assertEquals(120_000, r.proximoIntervaloMs(0.5, 150));
        for (int i = 0; i < 5; i++) assertEquals(120_000, r.proximoIntervaloMs(1.0, 10));
    }
}
