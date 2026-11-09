package br.com.gridco.campo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * O termo de uso e privacidade da localização na tela (o diálogo é o da {@link EquipeNaCasca.TelaDaActivity}): o aviso de
 * privacidade inteiro rolando dentro do diálogo, e o Li e aceito, sempre à vista embaixo, desligado até o fim do texto ter
 * aparecido na tela. Caso que criou a regra (R11): o pedido do dono, em 09/11/2026, "Quero que o termo de notificação
 * inclua tudo referente a LGPD e seja que nem as empresas grandes fazem, aparece a pessoa tem que rolar até o final para
 * aceitar".
 *
 * <ul>
 *   <li>Cada linha do texto é um parágrafo; a linha toda em negrito (o {@code <b>} do strings.xml) é o título da seção e
 *       vai marcada como título para o TalkBack, que pula de seção em seção. Um TextView por parágrafo, e não um só: o
 *       TalkBack que passa ao parágrafo seguinte rola o texto até ele.</li>
 *   <li>O fim conta quando a rolagem não desce mais, com uma folga de {@link #FOLGA_DP} dp: a rolagem que para a um ou dois
 *       pixels do fim (o arredondamento do arrasto e da densidade) não deixa o botão desligado para sempre. Vale qualquer
 *       caminho, porque todos passam pela mesma rolagem: o dedo, o TalkBack (a ação de rolar) e o teclado.</li>
 *   <li>O texto que cabe inteiro sem rolar (a tela grande) libera logo depois do layout. Antes do primeiro layout não se
 *       sabe a altura, e nada libera.</li>
 *   <li>Ligado, fica ligado: rolar de volta para cima não desliga. A girada da tela (a MainActivity não é recriada, pelo
 *       configChanges do manifesto) refaz o layout, que só libera com o fim à vista; a volta ao app não mexe em nada.</li>
 *   <li>O clique só aceita com o botão ligado: o desligado não recebe o toque, mas um serviço de acessibilidade pode
 *       clicar sem olhar o estado, e o aceite sem o fim do texto não vale.</li>
 * </ul>
 */
final class TermoDaEquipe {

    /** A folga do fim, em dp: o fim do texto a até esta distância do pé da rolagem conta como à vista. */
    static final int FOLGA_DP = 4;

    private TermoDaEquipe() {
    }

    /** O termo para o setView do diálogo: o texto do strings.xml em parágrafos, na rolagem, e a dica embaixo. */
    static View vista(Context ctx) {
        return vista(ctx, ctx.getText(R.string.casca_equipe_termo_texto));
    }

    /**
     * O mesmo, com outro texto (o teste do texto que cabe sem rolar). {@code ctx} é o do diálogo, com o tema dele: é o que
     * dá as cores do texto nos dois temas do celular.
     */
    // Sem o pai no inflate: quem põe a vista no pai é o próprio diálogo (o setView), com as medidas dele.
    @SuppressLint("InflateParams")
    static View vista(Context ctx, CharSequence texto) {
        View vista = LayoutInflater.from(ctx).inflate(R.layout.equipe_termo, null, false);
        ViewGroup secoes = vista.findViewById(R.id.equipe_termo_secoes);
        for (CharSequence linha : linhas(texto)) {
            boolean titulo = todaEmNegrito(linha);
            TextView paragrafo = new TextView(ctx, null, 0,
                titulo ? R.style.TermoDaEquipe_Titulo : R.style.TermoDaEquipe_Texto);
            paragrafo.setText(linha);
            ViewCompat.setAccessibilityHeading(paragrafo, titulo);
            secoes.addView(paragrafo);
        }
        return vista;
    }

    /**
     * Desliga o {@code aceitar} até o fim do texto da {@code vista} aparecer na tela, com a dica à vista enquanto isso, e
     * dá a ele o clique, que roda {@code aoAceitar} só com o botão ligado. Na thread de UI, logo depois de o diálogo
     * aparecer (o primeiro layout vem depois, e é ele que diz se o texto cabe).
     */
    static void travar(View vista, Button aceitar, Runnable aoAceitar) {
        ScrollView rolagem = vista.findViewById(R.id.equipe_termo_rolagem);
        View dica = vista.findViewById(R.id.equipe_termo_dica);
        int folga = Math.round(FOLGA_DP * vista.getResources().getDisplayMetrics().density);
        aceitar.setEnabled(false);
        dica.setVisibility(View.VISIBLE);
        Runnable conferir = () -> {
            if (aceitar.isEnabled() || !viuOFim(rolagem, folga)) return;
            aceitar.setEnabled(true);
            dica.setVisibility(View.GONE);
        };
        rolagem.setOnScrollChangeListener((v, x, y, xAntes, yAntes) -> conferir.run());
        rolagem.addOnLayoutChangeListener((v, esq, cima, dir, baixo, esqAntes, cimaAntes, dirAntes, baixoAntes) ->
            conferir.run());
        aceitar.setOnClickListener(v -> {
            if (v.isEnabled()) aoAceitar.run();
        });
        conferir.run(); // a vista que já tinha o layout feito
    }

    // O fim do texto à vista: a rolagem não desce mais, ou falta descer no máximo a folga. Sem o layout (as alturas em
    // zero), não. Pela altura, e não pelo isLaidOut: no primeiro layout o Android só marca a vista como pronta depois de
    // chamar os ouvintes do layout, e o texto que cabe não liberava.
    private static boolean viuOFim(ScrollView rolagem, int folga) {
        if (rolagem.getChildCount() == 0) return false;
        View texto = rolagem.getChildAt(0);
        int aVista = rolagem.getHeight() - rolagem.getPaddingTop() - rolagem.getPaddingBottom();
        if (aVista <= 0 || texto.getHeight() == 0) return false;
        int falta = Math.max(0, texto.getHeight() - aVista) - rolagem.getScrollY();
        return !rolagem.canScrollVertically(1) || falta <= folga;
    }

    // As linhas do texto (cada \n do strings.xml), sem os espaços das pontas e sem as vazias, cada uma com o seu negrito.
    private static List<CharSequence> linhas(CharSequence texto) {
        List<CharSequence> linhas = new ArrayList<>();
        int inicio = 0;
        for (int i = 0; i <= texto.length(); i++) {
            if (i < texto.length() && texto.charAt(i) != '\n') continue;
            int a = inicio;
            int b = i;
            while (a < b && Character.isWhitespace(texto.charAt(a))) a++;
            while (b > a && Character.isWhitespace(texto.charAt(b - 1))) b--;
            if (b > a) linhas.add(texto.subSequence(a, b));
            inicio = i + 1;
        }
        return linhas;
    }

    // A linha inteira num negrito só: o título da seção.
    private static boolean todaEmNegrito(CharSequence linha) {
        if (!(linha instanceof Spanned)) return false;
        Spanned marcada = (Spanned) linha;
        for (StyleSpan estilo : marcada.getSpans(0, marcada.length(), StyleSpan.class)) {
            if (estilo.getStyle() == Typeface.BOLD && marcada.getSpanStart(estilo) == 0
                    && marcada.getSpanEnd(estilo) == marcada.length()) {
                return true;
            }
        }
        return false;
    }
}
