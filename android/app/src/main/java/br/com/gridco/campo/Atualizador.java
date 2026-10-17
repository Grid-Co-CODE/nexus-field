package br.com.gridco.campo;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A atualização da casca (spec, seção 7): lê o versao.json da última Release pública do GitHub e, se a versão for
 * maior, oferece baixar o APK, confere o SHA-256 e abre o instalador do sistema. A parte web do App não passa por
 * aqui: chega na hora, pelo servidor.
 *
 * O fluxo, numa thread própria (a UI só mostra os diálogos):
 *   1. {@link #verificar()} (ao abrir e a cada volta ao app): se a última checagem foi há 6 h ou mais, grava a hora e lê
 *      o URL_VERSAO. A hora é gravada ANTES da rede, porque falhar também conta: sem sinal, o campo não pode gerar uma
 *      espera de 15 s a cada volta ao app. Falha de rede é silêncio, e a próxima tentativa é daí a 6 h.
 *   2. Com versionCode maior que o instalado, oferece "Atualizar agora / Depois".
 *   3. Baixa para cache/atualizacao/app-campo.apk, confere o SHA-256 (se não bate, apaga e avisa num toast).
 *   4. Sem a permissão "Instalar apps desconhecidos", o diálogo manda para os ajustes, e a volta ao app (o resume)
 *      segue dali, sem baixar de novo. O Android pode matar o processo ao liberar a permissão, então o que falta
 *      (versão e SHA do APK que ficou no cache) vai para o SharedPreferences, e a retomada confere o arquivo outra vez.
 *   5. Abre o instalador: ACTION_VIEW com o URI do FileProvider br.com.gridco.campo.arquivos.
 *
 * Um fluxo por vez em todo o processo (a tela recriada depois do renderer que morreu faz outra instância, e dois
 * downloads para o mesmo arquivo o corromperiam). O log não leva endereço, nome de arquivo nem e-mail: só o que
 * aconteceu e contagens.
 */
public final class Atualizador {

    static final String URL_VERSAO =
        "https://github.com/Grid-Co-CODE/app-campo-android/releases/latest/download/versao.json";
    /** Entre uma checagem e a seguinte: 6 h. */
    static final long INTERVALO_MS = 21_600_000L;
    static final String AUTORIDADE_DOS_ARQUIVOS = "br.com.gridco.campo.arquivos";
    static final String TIPO_DO_APK = "application/vnd.android.package-archive";

    private static final String TAG = CascaPlugin.TAG;
    private static final String PREFERENCIAS = "casca_atualizacao";
    private static final String ULTIMA = "ultima_checagem_ms";
    private static final String PENDENTE_CODIGO = "pendente_codigo";
    private static final String PENDENTE_SHA = "pendente_sha256";
    private static final String PASTA_NO_CACHE = "atualizacao"; // a única que o ArquivosProvider expõe
    private static final String NOME_DO_APK = "app-campo.apk";

    // Um fluxo por processo, como o espelho e o disjuntor do plugin.
    private static final AtomicReference<Object> DONO_DO_PROCESSO = new AtomicReference<>();

    /** A rede. Os testes põem uma falsa; a de verdade é a {@link RedeHttp}. */
    interface Rede {
        /** O texto do versao.json. */
        String lerVersao() throws IOException;

        /** Grava o arquivo do endereço em destino (a pasta já existe). */
        void baixar(String url, File destino) throws IOException;
    }

    /**
     * O que a pessoa vê e o sistema faz. Os métodos podem ser chamados de qualquer thread; a implementação de verdade
     * leva o que mexe na tela para a de UI.
     */
    interface Telas {
        boolean podeInstalar();

        /** Mostra a versão nova. Chama exatamente um dos dois: aceitou ("Atualizar agora") ou adiou (o resto). */
        void oferecer(VersaoRemota v, Runnable aceitou, Runnable adiou);

        /** O toast do arquivo que não bateu com o SHA-256. */
        void avisarSha();

        /** O diálogo da permissão; aoTocar roda (na UI) quando a pessoa toca em "Abrir ajustes". */
        void pedirPermissao(Runnable aoTocar);

        /** Abre os ajustes de "Instalar apps desconhecidos" deste app. Falso se não abriram. */
        boolean abrirAjustes();

        void instalar(File apk);

        /** A tela está saindo: fecha o diálogo aberto, o que conta como "Depois". */
        void encerrar();
    }

    private final SharedPreferences prefs;
    private final File pasta;
    private final int versaoAtual;
    private final LongSupplier relogio;
    private final Rede rede;
    private final Telas telas;
    private final Executor executor;
    private final AtomicReference<Object> dono;
    private volatile boolean encerrado = false;

    /** A de verdade, para a tela do App. */
    public Atualizador(Activity activity) {
        this(activity.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE),
            new File(activity.getCacheDir(), PASTA_NO_CACHE), BuildConfig.VERSION_CODE, System::currentTimeMillis,
            new RedeHttp(), new TelasDaActivity(activity), novoExecutor(), DONO_DO_PROCESSO);
    }

    Atualizador(SharedPreferences prefs, File pasta, int versaoAtual, LongSupplier relogio, Rede rede, Telas telas,
            Executor executor, AtomicReference<Object> dono) {
        this.prefs = prefs;
        this.pasta = pasta;
        this.versaoAtual = versaoAtual;
        this.relogio = relogio;
        this.rede = rede;
        this.telas = telas;
        this.executor = executor;
        this.dono = dono;
    }

    // Uma thread que morre sozinha depois de 10 s parada: nada a desligar quando a tela sai.
    private static Executor novoExecutor() {
        ThreadPoolExecutor e = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), tarefa -> {
            Thread t = new Thread(tarefa, "GcCasca-atualizacao");
            t.setDaemon(true);
            return t;
        });
        e.allowCoreThreadTimeOut(true);
        return e;
    }

    // ------------------------------------------------------------------ regras puras

    /** Verdadeiro se a última checagem foi há 6 h (21 600 000 ms) ou mais. */
    static boolean devoChecar(long agoraMs, long ultimaMs) {
        // O relógio que voltou (a data do aparelho acertada para trás) deixaria "agora - última" negativo, e a checagem
        // ficaria parada até o relógio alcançar a hora gravada.
        return agoraMs < ultimaMs || agoraMs - ultimaMs >= INTERVALO_MS;
    }

    /**
     * O SHA-256 do arquivo é o esperado? Lê em pedaços (o APK tem dezenas de MB), e o hexadecimal pode vir em maiúsculas.
     * Arquivo que não abre ou SHA que não é texto dá falso.
     */
    static boolean confere(File apk, String sha256) {
        if (apk == null || sha256 == null) return false;
        try (InputStream in = new FileInputStream(apk)) {
            MessageDigest resumo = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int lidos;
            while ((lidos = in.read(buffer)) != -1) resumo.update(buffer, 0, lidos);
            StringBuilder hex = new StringBuilder();
            for (byte b : resumo.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString().equalsIgnoreCase(sha256);
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }

    /** O instalador do sistema para o APK, pelo FileProvider (que só expõe cache/atualizacao/). */
    static Intent intentDoInstalador(Context contexto, File apk) {
        return intentParaInstalar(FileProvider.getUriForFile(contexto, AUTORIDADE_DOS_ARQUIVOS, apk));
    }

    // Separado do FileProvider para o teste de JVM do Windows provar o intent sem ele.
    static Intent intentParaInstalar(Uri uriDoApk) {
        return new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriDoApk, TIPO_DO_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** O texto do diálogo da versão nova: o versionName e uma linha "- item" por novidade. */
    static String textoDoDialogo(Context contexto, VersaoRemota v) {
        if (v.novidades.isEmpty()) return contexto.getString(R.string.casca_atualizacao_texto_simples, v.versionName);
        StringBuilder linhas = new StringBuilder();
        for (String novidade : v.novidades) {
            if (linhas.length() > 0) linhas.append('\n');
            linhas.append("- ").append(novidade);
        }
        return contexto.getString(R.string.casca_atualizacao_texto, v.versionName, linhas.toString());
    }

    // Tira da pasta tudo o que não é o arquivo a manter (null = nada fica): APK de versão antiga, pedaço de download
    // que parou no meio, o APK que já foi instalado.
    private static void apagarVelhos(File pasta, File manter) {
        File[] arquivos = pasta.listFiles();
        if (arquivos == null) return;
        for (File arquivo : arquivos) {
            if (manter == null || !arquivo.equals(manter)) arquivo.delete();
        }
    }

    // ------------------------------------------------------------------ o fluxo

    /**
     * Chamado ao abrir o app e a cada volta a ele (a thread de UI). Volta na hora: o trabalho é de outra thread, e se
     * já há um fluxo no ar (o diálogo aberto, o download correndo) esta chamada não faz nada.
     */
    public void verificar() {
        if (encerrado || !dono.compareAndSet(null, this)) return;
        try {
            executor.execute(this::trabalhar);
        } catch (RejectedExecutionException e) {
            liberar();
        }
    }

    /** A tela está saindo: nada novo começa, e o diálogo aberto fecha (e solta o fluxo, como um "Depois"). */
    public void encerrar() {
        encerrado = true;
        telas.encerrar();
    }

    private void liberar() {
        dono.compareAndSet(this, null);
    }

    // Na thread de trabalho. Um fluxo só termina de duas maneiras: aqui (o finally solta o fluxo) ou num diálogo, que é
    // quem solta depois (a resposta da pessoa, "Depois" ou o fechar da tela).
    private void trabalhar() {
        boolean seguePeloDialogo = false;
        try {
            if (!retomarInstalacaoPendente()) seguePeloDialogo = conferirVersaoNova();
        } catch (RuntimeException e) {
            Log.w(TAG, "atualização: falhou (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (!seguePeloDialogo) liberar();
        }
    }

    // A pessoa tocou em "Abrir ajustes" e voltou: o APK está no cache, falta abrir o instalador. É UMA tentativa só: o
    // próximo resume (a câmera, o GPS) não pode reabrir o instalador nem repetir o diálogo.
    private boolean retomarInstalacaoPendente() {
        int codigo = prefs.getInt(PENDENTE_CODIGO, 0);
        String sha = prefs.getString(PENDENTE_SHA, null);
        if (codigo == 0 && sha == null) return false;
        limparPendencia();

        File apk = new File(pasta, NOME_DO_APK);
        if (codigo <= versaoAtual) {
            apagarVelhos(pasta, null);
            Log.i(TAG, "atualização: a versão pendente já está instalada");
        } else if (!confere(apk, sha)) {
            // Sumiu (o Android limpa o cache quando falta espaço) ou mudou: nunca vai ao instalador sem conferir.
            apagarVelhos(pasta, null);
            Log.i(TAG, "atualização: o arquivo pendente sumiu ou mudou");
        } else if (!telas.podeInstalar()) {
            Log.i(TAG, "atualização: voltou dos ajustes sem liberar a instalação");
        } else {
            abrirInstalador(apk);
        }
        return true;
    }

    // Devolve verdadeiro se ofereceu a versão (o fluxo segue pelo diálogo).
    private boolean conferirVersaoNova() {
        long agora = relogio.getAsLong();
        if (!devoChecar(agora, prefs.getLong(ULTIMA, 0L))) return false;
        prefs.edit().putLong(ULTIMA, agora).apply();
        apagarVelhos(pasta, null); // o que sobrou da atualização passada ou de um diálogo fechado

        String texto;
        try {
            texto = rede.lerVersao();
        } catch (IOException e) {
            Log.i(TAG, "atualização: o versao.json não foi lido (" + e.getClass().getSimpleName() + ")");
            return false;
        }
        VersaoRemota v;
        try {
            v = VersaoRemota.ler(texto);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "atualização: o versao.json veio inválido");
            return false;
        }
        if (v.versionCode <= versaoAtual) return false;

        Log.i(TAG, "atualização: versão nova no ar (código " + v.versionCode + ")");
        telas.oferecer(v, () -> executar(() -> baixarEInstalar(v)), this::liberar);
        return true;
    }

    private void executar(Runnable tarefa) {
        try {
            executor.execute(tarefa);
        } catch (RejectedExecutionException e) {
            liberar();
        }
    }

    // "Atualizar agora": na thread de trabalho. Termina sempre soltando o fluxo (a permissão pendente se retoma pelo
    // resume seguinte, que é outro fluxo).
    private void baixarEInstalar(VersaoRemota v) {
        File apk = new File(pasta, NOME_DO_APK);
        try {
            if (!pasta.isDirectory() && !pasta.mkdirs()) {
                Log.w(TAG, "atualização: a pasta do download não foi criada");
                return;
            }
            try {
                rede.baixar(v.apk, apk);
            } catch (IOException e) {
                apk.delete();
                Log.i(TAG, "atualização: o download não terminou (" + e.getClass().getSimpleName() + ")");
                return;
            }
            if (!confere(apk, v.sha256)) {
                long bytes = apk.length();
                apk.delete();
                Log.w(TAG, "atualização: o arquivo baixado não bate com o SHA-256 (" + bytes + " bytes)");
                telas.avisarSha();
                return;
            }
            if (!telas.podeInstalar()) {
                telas.pedirPermissao(() -> aoAbrirOsAjustes(v));
                return;
            }
            abrirInstalador(apk);
        } catch (RuntimeException e) {
            // O que ficou no cache não vai ao instalador sem o SHA-256 conferido, e a próxima checagem apaga: não se
            // apaga aqui porque o erro pode ter vindo depois de o instalador já ter sido pedido.
            Log.w(TAG, "atualização: falhou (" + e.getClass().getSimpleName() + ")");
        } finally {
            liberar();
        }
    }

    // Na UI, no toque em "Abrir ajustes". A pendência só nasce aqui: o diálogo fechado sem o toque não deixa nada.
    private void aoAbrirOsAjustes(VersaoRemota v) {
        prefs.edit().putInt(PENDENTE_CODIGO, v.versionCode).putString(PENDENTE_SHA, v.sha256).apply();
        if (!telas.abrirAjustes()) limparPendencia();
    }

    private void limparPendencia() {
        prefs.edit().remove(PENDENTE_CODIGO).remove(PENDENTE_SHA).apply();
    }

    private void abrirInstalador(File apk) {
        apagarVelhos(pasta, apk);
        telas.instalar(apk);
    }

    // ------------------------------------------------------------------ a rede de verdade

    /**
     * HttpURLConnection com tempo-limite curto e sem nova tentativa: falhou, o fluxo espera as 6 h. Segue redirecionamento
     * (o GitHub manda o /releases/latest/download/ para a Release e o arquivo para o armazenamento dele), só https.
     */
    static final class RedeHttp implements Rede {
        private static final int TEMPO_DE_CONEXAO_MS = 15_000;
        private static final int TEMPO_DE_LEITURA_DO_JSON_MS = 15_000;
        private static final int TEMPO_DE_LEITURA_DO_APK_MS = 120_000;
        private static final long LIMITE_DO_JSON = 64L * 1024;
        private static final long LIMITE_DO_APK = 200L * 1024 * 1024;

        @Override
        public String lerVersao() throws IOException {
            HttpURLConnection conexao = abrir(URL_VERSAO, TEMPO_DE_LEITURA_DO_JSON_MS);
            try {
                ByteArrayOutputStream saida = new ByteArrayOutputStream();
                copiar(conexao.getInputStream(), saida, LIMITE_DO_JSON);
                return new String(saida.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                conexao.disconnect();
            }
        }

        @Override
        public void baixar(String url, File destino) throws IOException {
            HttpURLConnection conexao = abrir(url, TEMPO_DE_LEITURA_DO_APK_MS);
            try (OutputStream saida = new FileOutputStream(destino)) {
                copiar(conexao.getInputStream(), saida, LIMITE_DO_APK);
            } finally {
                conexao.disconnect();
            }
        }

        private static HttpURLConnection abrir(String endereco, int leituraMs) throws IOException {
            HttpURLConnection conexao = (HttpURLConnection) new URL(endereco).openConnection();
            try {
                conexao.setConnectTimeout(TEMPO_DE_CONEXAO_MS);
                conexao.setReadTimeout(leituraMs);
                conexao.setInstanceFollowRedirects(true);
                int codigo = conexao.getResponseCode();
                // A mensagem leva só o código; o Atualizador registra apenas a classe da exceção, nunca o texto.
                if (codigo != HttpURLConnection.HTTP_OK) throw new IOException("resposta " + codigo);
            } catch (IOException | RuntimeException e) {
                conexao.disconnect();
                throw e;
            }
            return conexao;
        }

        private static void copiar(InputStream entrada, OutputStream saida, long limite) throws IOException {
            try (InputStream in = entrada) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int lidos;
                while ((lidos = in.read(buffer)) != -1) {
                    total += lidos;
                    if (total > limite) throw new IOException("resposta grande demais");
                    saida.write(buffer, 0, lidos);
                }
            }
        }
    }

    // ------------------------------------------------------------------ as telas de verdade

    /** Os diálogos, o toast e o instalador, na tela do App. O que mexe na tela vai para a thread de UI. */
    static final class TelasDaActivity implements Telas {
        private final Activity activity;
        private AlertDialog aberto; // só na thread de UI
        private Runnable desistirDoAberto; // idem: o "Depois" do diálogo da versão aberto (null no da permissão)

        TelasDaActivity(Activity activity) {
            this.activity = activity;
        }

        private boolean ativa() {
            return !activity.isFinishing() && !activity.isDestroyed();
        }

        @Override
        public boolean podeInstalar() {
            // Antes do Android 8 não há permissão por app: quem pergunta é o instalador do sistema.
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls();
        }

        @Override
        public void oferecer(VersaoRemota v, Runnable aceitou, Runnable adiou) {
            activity.runOnUiThread(() -> {
                // Responde uma vez só: o clique em "Atualizar agora" e o dismiss que vem logo depois dele não somam.
                AtomicBoolean respondeu = new AtomicBoolean(false);
                Runnable desistir = () -> {
                    if (respondeu.compareAndSet(false, true)) adiou.run();
                };
                if (!ativa()) {
                    desistir.run();
                    return;
                }
                AlertDialog dialogo = new AlertDialog.Builder(activity)
                    .setTitle(R.string.casca_atualizacao_titulo)
                    .setMessage(textoDoDialogo(activity, v))
                    .setPositiveButton(R.string.casca_atualizacao_atualizar, (d, qual) -> {
                        if (respondeu.compareAndSet(false, true)) aceitou.run();
                    })
                    .setNegativeButton(R.string.casca_atualizacao_depois, null)
                    .create();
                // Voltar, tocar fora, "Depois" e a tela saindo: tudo isso é desistir.
                dialogo.setOnDismissListener(d -> {
                    soltar(dialogo);
                    desistir.run();
                });
                mostrar(dialogo, desistir);
            });
        }

        @Override
        public void pedirPermissao(Runnable aoTocar) {
            activity.runOnUiThread(() -> {
                if (!ativa()) return;
                AlertDialog dialogo = new AlertDialog.Builder(activity)
                    .setTitle(R.string.casca_instalar_titulo)
                    .setMessage(R.string.casca_instalar_texto)
                    .setPositiveButton(R.string.casca_abrir_ajustes, (d, qual) -> aoTocar.run())
                    .create();
                dialogo.setOnDismissListener(d -> soltar(dialogo));
                mostrar(dialogo, null);
            });
        }

        // Na UI. Janela que não abre (a activity morreu entre o ativa() e aqui) conta como desistir.
        private void mostrar(AlertDialog dialogo, Runnable desistir) {
            aberto = dialogo;
            desistirDoAberto = desistir;
            try {
                dialogo.show();
            } catch (RuntimeException e) {
                soltar(dialogo);
                Log.w(TAG, "atualização: o diálogo não abriu (" + e.getClass().getSimpleName() + ")");
                if (desistir != null) desistir.run();
            }
        }

        // Na UI. Só esquece o diálogo se ainda é o dele (o da permissão pode já ter tomado o lugar).
        private void soltar(AlertDialog dialogo) {
            if (aberto != dialogo) return;
            aberto = null;
            desistirDoAberto = null;
        }

        @Override
        public void avisarSha() {
            activity.runOnUiThread(() ->
                Toast.makeText(activity.getApplicationContext(), R.string.casca_atualizacao_sha_toast, Toast.LENGTH_LONG)
                    .show());
        }

        @Override
        public boolean abrirAjustes() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false; // sem permissão por app, nunca se pede
            Intent ajustes = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(ajustes);
                return true;
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "atualização: os ajustes de instalar apps não abriram");
                return false;
            }
        }

        @Override
        public void instalar(File apk) {
            activity.runOnUiThread(() -> {
                if (!ativa()) return; // o APK fica no cache até a próxima checagem apagar
                try {
                    activity.startActivity(intentDoInstalador(activity, apk));
                } catch (ActivityNotFoundException | IllegalArgumentException | SecurityException e) {
                    Log.w(TAG, "atualização: o instalador não abriu (" + e.getClass().getSimpleName() + ")");
                }
            });
        }

        @Override
        public void encerrar() {
            activity.runOnUiThread(() -> {
                AlertDialog dialogo = aberto;
                Runnable desistir = desistirDoAberto;
                aberto = null;
                desistirDoAberto = null;
                try {
                    if (dialogo != null) dialogo.dismiss(); // dispara o OnDismissListener
                } catch (RuntimeException e) {
                    // A janela já foi embora com a activity: o dismiss não avisa, e o fluxo precisa soltar do mesmo jeito.
                }
                if (desistir != null) desistir.run(); // uma vez só: o dismiss que já tenha vindo não repete
            });
        }
    }
}
