package br.com.gridco.campo;

import java.util.function.LongSupplier;

/**
 * A OS que o toque na emergência pediu para abrir, guardada no PROCESSO (o plugin tem uma só, estática, como o
 * espelho): ela vai à página a cada carga do App ({@link CascaPlugin.AberturaDaOS}) até a página dizer que a abriu de
 * fato (o {@code {t:'osAberta', os}}), e vale só até o fim do dia, em Brasília. A notificação também fica na barra até
 * lá (Notificacoes.mostrar).
 *
 * Caso que criou a regra: o técnico preenche uma OS com fotos num aparelho de 4 GB, toca na emergência, e a página a deixa
 * pendente (ele estava ocupado); o renderer morre, a tela é recriada com plugin e sessionStorage novos, e o número da OS
 * sumia: o pedido morava na instância do plugin da tela velha e a pendência, no sessionStorage dela. Com o app fechado, a
 * notificação que ficou na barra refaz tudo num toque novo.
 *
 * Sincronizada: quem guarda e entrega é a thread de UI (o toque, a carga), e quem esquece é a do espelho (o osAberta, o
 * Sair).
 */
final class OsDoToque {

    private final LongSupplier relogioDeParede;
    private String os;
    private int dia;

    OsDoToque(LongSupplier relogioDeParede) {
        this.relogioDeParede = relogioDeParede;
    }

    /** O toque numa emergência: esta OS passa a ser a guardada (a última tocada vence). Já validada. */
    synchronized void guardar(String os) {
        this.os = os;
        this.dia = Expediente.dia(relogioDeParede.getAsLong());
    }

    /** A OS a entregar à página nesta carga, ou null. A de outro dia sai aqui. Entregar não esquece. */
    synchronized String aEntregar() {
        if (os != null && dia != Expediente.dia(relogioDeParede.getAsLong())) os = null;
        return os;
    }

    /** A página abriu esta OS: ela não volta mais (outra, tocada depois, fica). */
    synchronized void esquecerSe(String aberta) {
        if (aberta != null && aberta.equals(os)) os = null;
    }

    /** O Sair: a OS de quem saiu não abre para quem entrar. */
    synchronized void esquecer() {
        os = null;
    }
}
