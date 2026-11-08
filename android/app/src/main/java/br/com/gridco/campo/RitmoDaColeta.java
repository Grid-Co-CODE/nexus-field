package br.com.gridco.campo;

/**
 * O intervalo entre uma leitura e a próxima: 2 min em deslocamento, 10 min parado.
 * Parado = velocidade abaixo de 1 m/s e menos de 150 m do último ponto, em 3 leituras seguidas; a primeira leitura
 * acima disso volta ao ritmo curto. Guarda a contagem (por isso é instância, uma por serviço): um pico de GPS
 * parado não pode, sozinho, deixar o técnico 10 min sem ponto no mapa.
 */
final class RitmoDaColeta {

    static final long EM_DESLOCAMENTO_MS = 120_000L;
    static final long PARADO_MS = 600_000L;

    private static final double VELOCIDADE_DE_PARADO_MS = 1.0;
    private static final double DISTANCIA_DE_PARADO_M = 150.0;
    private static final int LEITURAS_PARA_PARAR = 3;

    private int paradasSeguidas = 0;

    /**
     * Uma leitura parada: abaixo de 1 m/s e a menos de 150 m da anterior. É a régua do ritmo (três seguidas alongam o
     * intervalo) e a do "mov" de cada ponto (ServicoDaEquipe.movDaLeitura), que olha só a leitura, sem a contagem.
     */
    static boolean leituraParada(double velocidadeMs, double metrosDesdeOUltimo) {
        return velocidadeMs < VELOCIDADE_DE_PARADO_MS && metrosDesdeOUltimo < DISTANCIA_DE_PARADO_M;
    }

    long proximoIntervaloMs(double velocidadeMs, double metrosDesdeOUltimo) {
        boolean parada = leituraParada(velocidadeMs, metrosDesdeOUltimo);
        paradasSeguidas = parada ? Math.min(paradasSeguidas + 1, LEITURAS_PARA_PARAR) : 0;
        return paradasSeguidas >= LEITURAS_PARA_PARAR ? PARADO_MS : EM_DESLOCAMENTO_MS;
    }
}
