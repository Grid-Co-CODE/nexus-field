package br.com.gridco.campo;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.Arrays;
import java.util.List;

/**
 * O atalho de teste dos avisos, só para administrador (decisão do dono, 18/10/2026). O dono quis testar as notificações
 * no celular por um botão que só quem é Admin no App vê; o botão é um atalho do ícone (o toque longo no ícone do Nexus
 * Field), e não um botão desenhado na página: a casca não põe tela no App de Campo, e o técnico nunca o vê.
 *
 * <ul>
 *   <li>Dois atalhos dinâmicos, de ids fixos: {@value #ID_AVISOS} (o canal alto, {@code emergencia}) e
 *       {@value #ID_FORA_DO_EXPEDIENTE} (o silencioso, {@code emergencia_silenciosa}). Quem decide é a página, a cada
 *       carga: o {@code {t:'pushAdmin', admin}} do www/casca/inicio.js (o GET /me do servidor, papel 'Admin') publica
 *       com true e tira com false (outro papel, erro, 401, o Sair). O 403 do teste também tira ({@link Mensageiro}).</li>
 *   <li>Publicar é pelo pushDynamicShortcut, um a um, e nunca pelo setDynamicShortcuts, que troca TODOS os dinâmicos do
 *       app pelos da lista (um atalho de outra origem sumiria a cada carga do Admin); o push também conta o uso, que o
 *       launcher usa para ordenar. Tirar é o removeDynamicShortcuts só dos dois.</li>
 *   <li>O atalho que o administrador FIXOU na tela inicial (arrastar do menu) não sai com o removeDynamicShortcuts: quem
 *       não é Admin o deixa DESLIGADO ({@code disableShortcuts}, com o texto {@code atalho_teste_desligado}, que o
 *       launcher mostra no toque sem abrir o app), e o Admin o religa ({@code enableShortcuts}) antes de publicar. Caso
 *       que criou a regra: o fixado seguia tocável por outra conta no mesmo aparelho, e só o 403 do servidor segurava.</li>
 *   <li>O toque abre a tela pela PORTA INTERNA ({@link MainActivity#PORTA_INTERNA}, a activity-alias não exportada; o
 *       launcher abre o atalho em nome do próprio app), com o canal no extra {@value #EXTRA_TESTE}, sem CLEAR_TASK: com o
 *       app aberto, a tela vem para a frente (singleTask, onNewIntent) e a página não recarrega. A MainActivity confere o
 *       canal ({@link #canalValido}) e o plugin roda o teste na página quando ela estiver pronta
 *       ({@link CascaPlugin.TesteDosAvisos}); a página chama o servidor com o Bearer dela.</li>
 *   <li>O resultado volta da página e vira um toast ({@link #avisar}), sem dado da conta: só a contagem de aparelhos.</li>
 * </ul>
 */
final class AtalhosDeTeste {

    /** O extra do atalho: o canal do teste, que a MainActivity leva ao plugin ({@link MainActivity#testeDoAtalho}). */
    static final String EXTRA_TESTE = "gc_teste";

    static final String ID_AVISOS = "teste_avisos";
    static final String ID_FORA_DO_EXPEDIENTE = "teste_fora_expediente";
    private static final List<String> IDS = Arrays.asList(ID_AVISOS, ID_FORA_DO_EXPEDIENTE);

    // A resposta do servidor diz quantos aparelhos da conta receberam o teste; uma contagem maior que esta é resposta
    // torta (a conta tem um ou dois aparelhos), e o toast não a repete. O www/casca/inicio.js usa o mesmo teto (o
    // testes/atalho-teste.test.mjs compara os dois).
    private static final int APARELHOS_NO_MAXIMO = 10_000;

    // O que a página manda no status quando nem chamou a rota por falta do Bearer (www/casca/inicio.js, seção 8).
    private static final String SEM_LOGIN_DA_PAGINA = "sem login";

    /**
     * O que o toast diz: o resultado do teste que a página devolveu, ou {@link #JA_PEDIDO}, o toque recusado porque outro
     * foi aceito há menos de 10 s (CascaPlugin.TesteDosAvisos).
     */
    enum Resultado { ENVIADO, ENVIADO_SEM_CONTAGEM, SO_ADMIN, SEM_LOGIN, FALHOU, JA_PEDIDO }

    private AtalhosDeTeste() {
    }

    /** Só os dois canais da emergência: o texto vai a um script na página. */
    static boolean canalValido(String canal) {
        return Notificacoes.CANAL_EMERGENCIA.equals(canal) || Notificacoes.CANAL_EMERGENCIA_SILENCIOSA.equals(canal);
    }

    /**
     * Publica os dois atalhos (a conta é Admin), religando antes o que estiver fixado e desligado. Cada carga do Admin
     * publica de novo, e o push atualiza o que existe (não duplica). Com o app na frente o Android não limita a
     * frequência; com ele no fundo (o /me que respondeu depois de o técnico ir a outro app) o sistema pode recusar, e a
     * próxima carga publica. Nunca lança: o sistema que recusa (o limite, o usuário do aparelho bloqueado) só deixa um
     * aviso no log.
     */
    static void publicar(Context contexto) {
        Context app = contexto.getApplicationContext();
        IconCompat icone = IconCompat.createWithResource(app, R.mipmap.ic_launcher);
        List<ShortcutInfoCompat> atalhos = Arrays.asList(
            new ShortcutInfoCompat.Builder(app, ID_AVISOS)
                .setShortLabel(app.getString(R.string.atalho_teste_avisos_curto))
                .setLongLabel(app.getString(R.string.atalho_teste_avisos_longo))
                .setIcon(icone)
                .setRank(0)
                .setIntent(abrirComOCanal(app, Notificacoes.CANAL_EMERGENCIA))
                .build(),
            new ShortcutInfoCompat.Builder(app, ID_FORA_DO_EXPEDIENTE)
                .setShortLabel(app.getString(R.string.atalho_teste_fora_curto))
                .setLongLabel(app.getString(R.string.atalho_teste_fora_longo))
                .setIcon(icone)
                .setRank(1)
                .setIntent(abrirComOCanal(app, Notificacoes.CANAL_EMERGENCIA_SILENCIOSA))
                .build());
        try {
            ShortcutManagerCompat.enableShortcuts(app, atalhos);
            boolean todos = true;
            for (ShortcutInfoCompat atalho : atalhos) todos &= ShortcutManagerCompat.pushDynamicShortcut(app, atalho);
            if (todos) {
                Log.i(CascaPlugin.TAG, "atalhos de teste dos avisos publicados");
            } else {
                Log.w(CascaPlugin.TAG, "os atalhos de teste não foram publicados agora (o Android limitou)");
            }
        } catch (RuntimeException e) {
            Log.w(CascaPlugin.TAG, "os atalhos de teste não foram publicados (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Tira os dois atalhos (a conta não é Admin, ou saiu) e deixa desligado o que estiver fixado na tela inicial. Sem nada
     * publicado nem fixado, não faz nada. Nunca lança.
     */
    static void tirar(Context contexto) {
        Context app = contexto.getApplicationContext();
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(app, IDS);
            ShortcutManagerCompat.disableShortcuts(app, IDS, app.getString(R.string.atalho_teste_desligado));
            Log.i(CascaPlugin.TAG, "atalhos de teste dos avisos tirados");
        } catch (RuntimeException e) {
            Log.w(CascaPlugin.TAG, "os atalhos de teste não foram tirados (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Conta o uso do atalho do canal que o toque pediu (o launcher ordena os atalhos pelo uso). O canal que não é de
     * atalho nenhum não conta. Nunca lança.
     */
    static void relatarUso(Context contexto, String canal) {
        String id = Notificacoes.CANAL_EMERGENCIA.equals(canal) ? ID_AVISOS
            : Notificacoes.CANAL_EMERGENCIA_SILENCIOSA.equals(canal) ? ID_FORA_DO_EXPEDIENTE : null;
        if (id == null) return;
        try {
            ShortcutManagerCompat.reportShortcutUsed(contexto.getApplicationContext(), id);
        } catch (RuntimeException e) {
            // O uso é só para a ordem do menu.
        }
    }

    // O Intent do atalho: a tela do App pela porta interna, com o canal. A ação VIEW (e não a MAIN do launcher) faz dele
    // outro Intent que o do ícone, e as flags são as do toque na notificação (Notificacoes.mostrar).
    private static Intent abrirComOCanal(Context app, String canal) {
        return new Intent(Intent.ACTION_VIEW)
            .setClassName(app, MainActivity.PORTA_INTERNA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TESTE, canal);
    }

    /**
     * O resultado do teste pelo que a página devolveu ({@code {t:'pushTeste', ok, status, aparelhos}}, com os valores
     * como o JSON os entrega): enviado com ok, com a contagem ou sem ela (o 200 com o corpo que não se lê, ou com um
     * número acima do teto, foi enviado assim mesmo, e o "falhou" convidaria a repetir: cada aparelho da conta ganharia
     * outro par de avisos); o 403 é a conta que não é Admin (a rota é só dele); o 401 ou a página sem o Bearer pedem o
     * login; o resto (sem rede, o prazo, o 5xx, a resposta fora do contrato) é falha.
     */
    static Resultado resultado(Object ok, Object status, Object aparelhos) {
        if (Boolean.TRUE.equals(ok)) return contagem(aparelhos) != null ? Resultado.ENVIADO : Resultado.ENVIADO_SEM_CONTAGEM;
        Integer http = inteiro(status);
        if (http != null && http == 403) return Resultado.SO_ADMIN;
        if ((http != null && http == 401) || SEM_LOGIN_DA_PAGINA.equals(status)) return Resultado.SEM_LOGIN;
        return Resultado.FALHOU;
    }

    /** A contagem de aparelhos, só como inteiro de 0 a {@value #APARELHOS_NO_MAXIMO}; null no resto. */
    static Integer contagem(Object aparelhos) {
        Integer n = inteiro(aparelhos);
        return n != null && n >= 0 && n <= APARELHOS_NO_MAXIMO ? n : null;
    }

    /** O número do pedido do teste que a página devolveu no resultado, só como inteiro positivo; null no resto. */
    static Integer pedido(Object pedido) {
        Integer n = inteiro(pedido);
        return n != null && n >= 1 ? n : null;
    }

    // O número inteiro do JSON (o org.json dá Integer, Long ou Double conforme o texto), ou null. O booleano não é número.
    private static Integer inteiro(Object valor) {
        if (!(valor instanceof Number)) return null;
        double d = ((Number) valor).doubleValue();
        if (Double.isNaN(d) || d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) return null;
        return (int) d;
    }

    /** O toast do resultado. Na thread de UI. */
    static void avisar(Context contexto, Resultado resultado, int aparelhos) {
        switch (resultado) {
            case ENVIADO:
                Toast.makeText(contexto, contexto.getString(R.string.casca_teste_enviado, aparelhos), Toast.LENGTH_LONG)
                    .show();
                return;
            case ENVIADO_SEM_CONTAGEM:
                Toast.makeText(contexto, R.string.casca_teste_enviado_sem_contagem, Toast.LENGTH_LONG).show();
                return;
            case SO_ADMIN:
                Toast.makeText(contexto, R.string.casca_teste_so_admin, Toast.LENGTH_LONG).show();
                return;
            case SEM_LOGIN:
                Toast.makeText(contexto, R.string.casca_teste_entrar, Toast.LENGTH_LONG).show();
                return;
            case JA_PEDIDO:
                Toast.makeText(contexto, R.string.casca_teste_ja_pedido, Toast.LENGTH_LONG).show();
                return;
            default:
                Toast.makeText(contexto, R.string.casca_teste_falhou, Toast.LENGTH_LONG).show();
        }
    }
}
