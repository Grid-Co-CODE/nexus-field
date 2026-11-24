package br.com.gridco.campo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cópia de cada foto do App de Campo na galeria do celular (1.2.8, o pedido do dono em 24/11/2026: "assim que tira a
 * foto já vai para a galeria"). O técnico não faz nada: a foto que o site grava no IndexedDB dele já sai carimbada (data,
 * hora e local) em JPEG de 1280 px, e é essa versão que vai à pasta Pictures/Grid Co (o álbum "Grid Co" da galeria).
 *
 * <p>Por que na casca: o site não consegue gravar na galeria de dentro da WebView do Capacitor (não há DownloadListener
 * nem plugin de mídia), mas a casca já recebe cada foto, pelo espelho do IndexedDB das fotos (o {@link Mensageiro}): o
 * idbPut de texto (a foto em dataURL) e o Blob inteiro, no último pedaço. O {@link Mensageiro} chama esta classe logo
 * depois de gravar no espelho, só com o que é foto ({@link #ehFotoEmTexto}, {@link #ehMimeDeFoto}); o JSON (o rascunho
 * da ronda, a programação), o texto que não é imagem, o vídeo e o áudio nunca chegam aqui.
 *
 * <p>Regras que o código não diz sozinho:
 * <ul>
 *   <li><b>Só do Android 10 (API 29) em diante</b>, pelo MediaStore com o RELATIVE_PATH e o IS_PENDING: aí a foto que o
 *       próprio app cria na galeria não pede permissão nenhuma. Abaixo dele gravar em Pictures pede o
 *       WRITE_EXTERNAL_STORAGE, que a casca não pede (mais uma tela ao técnico, e a permissão abre o armazenamento
 *       inteiro); lá esta classe não faz nada ({@link #ativa}).</li>
 *   <li><b>A mesma foto vai uma vez só</b>, pelo CONTEÚDO, num registro guardado no armazenamento privado do app
 *       ({@link Registro}, os últimos {@value Registro#TETO}), que sobrevive ao app fechado. O site grava a mesma foto
 *       mais de uma vez com chaves diferentes (o rascunho {@code d_} na captura e a fila {@code f_} no Concluir da OS; o
 *       rascunho e o envio da APR), e a restauração do espelho devolve fotos à página que podem voltar pelo canal. O
 *       resumo é o SHA-256 dos bytes da imagem (os 16 primeiros bytes), e não o {@link Fnv}: o Fnv tem 32 bits e é do
 *       texto em UTF-16, igual ao do inicio.js, e com 5000 fotos no registro uma foto nova tomada por "já está na
 *       galeria" (e que nunca chegaria lá) teria uma chance em ~860 mil a cada foto, somando ao longo da vida do
 *       aparelho; com 128 bits, nunca. A foto só entra no registro depois de gravada: a que falhou tenta de novo na
 *       próxima cópia que a página mandar.</li>
 *   <li><b>Nunca atrasa nem derruba o espelho.</b> Tudo o que pesa (decodificar o base64, ler o arquivo, o resumo, o
 *       MediaStore, o registro em disco) roda numa thread só, a {@code GcCasca-galeria}, de prioridade de fundo, fora da
 *       thread do espelho e da de UI. A thread do espelho só confere o começo do texto ou o mime e, no Blob, abre o
 *       arquivo: aberto ali, o arquivo segue legível mesmo que a página regrave ou apague a chave antes de a galeria
 *       chegar nele (no Linux o arquivo aberto vive até ser fechado). O que espera a galeria tem um teto de memória
 *       ({@value #PENDENTE_NO_MAXIMO} bytes): passou, a foto fica fora da galeria, com aviso. Caso que pesa: a
 *       reconciliação do espelho recriado manda todas as fotos de uma vez.</li>
 *   <li><b>Nunca lança</b> (nem o erro de memória da thread dela, que no Android derrubaria o processo inteiro). A
 *       falha só conta e vai ao log com o rótulo {@code galeria} e a contagem, como o resto da casca: nunca o nome da
 *       chave (tem o e-mail do técnico) nem o conteúdo.</li>
 * </ul>
 */
final class GaleriaDaFoto implements Mensageiro.Galeria {

    private static final String TAG = CascaPlugin.TAG;

    /** A pasta da galeria (o RELATIVE_PATH do MediaStore): o álbum "Grid Co". */
    static final String PASTA = Environment.DIRECTORY_PICTURES + "/Grid Co";

    /** O registro do que já foi para a galeria, no armazenamento privado do app (fora do backup, como tudo). */
    static final String ARQUIVO_DO_REGISTRO = "fotos_na_galeria.txt";

    /** O teto do que espera a thread da galeria, em bytes (o texto conta 2 por caractere, como o Java o guarda). */
    static final long PENDENTE_NO_MAXIMO = 32L * 1024 * 1024;

    /**
     * A maior foto que vai à galeria. A do site tem uns 300 KB (o JPEG de 1280 px); o teto só barra o Blob de imagem
     * fora do comum, que leria tudo para a memória de um aparelho de 4 GB.
     */
    static final long FOTO_NO_MAXIMO = 16L * 1024 * 1024;

    // O cabeçalho do dataURL que é foto: o que o canvas do site dá (toDataURL('image/jpeg', ...)), e o PNG e o WebP, que
    // a galeria também mostra. Outro formato (o SVG, que é texto e pode ter script; o GIF) fica fora.
    private static final Pattern CABECALHO_DE_FOTO =
        Pattern.compile("data:(image/(?:jpeg|png|webp))(?:;[^;,]*)*;base64", Pattern.CASE_INSENSITIVE);

    // Até onde se procura a vírgula do cabeçalho: o resto é o base64 da foto, de centenas de milhares de caracteres.
    private static final int CABECALHO_NO_MAXIMO = 128;

    /** Quem grava a cópia na galeria. O de produção é o {@link NoMediaStore}; o teste passa outro. */
    interface Gravador {
        /**
         * Grava os bytes como uma foto nova da galeria e devolve true só com a foto inteira e à vista. Pode lançar: quem
         * chama trata como falha.
         */
        boolean gravar(byte[] bytes, String mime, String nome, long tiradaEm);
    }

    /** Para onde vai o aviso da falha (o log, no app). Só texto nosso, com contagem. */
    interface Aviso {
        void avisar(String msg);
    }

    private final boolean ativa;
    private final Gravador gravador;
    private final Registro registro;
    private final Executor thread;
    private final LongSupplier relogio;
    private final Aviso aviso;
    private final long pendenteNoMaximo;
    private final AtomicLong pendente = new AtomicLong();

    // Só na thread da galeria (as do aviso de fila cheia são da thread do espelho, por isso atômicas).
    private int gravadas = 0;
    private int falhas = 0;
    private int foraDoFormato = 0;
    private final AtomicLong deixadas = new AtomicLong();

    /**
     * @param sdk      o Build.VERSION.SDK_INT do aparelho: abaixo do 29 nada vai à galeria
     * @param gravador quem grava (null abaixo do 29)
     * @param thread   a thread da galeria (no teste, a de quem chama, ou uma fila que o teste esvazia)
     * @param relogio  a hora da foto, no relógio de parede (o DATE_TAKEN e o nome do arquivo)
     */
    GaleriaDaFoto(int sdk, Gravador gravador, Registro registro, Executor thread, LongSupplier relogio, Aviso aviso) {
        this(sdk, gravador, registro, thread, relogio, aviso, PENDENTE_NO_MAXIMO);
    }

    /** Para o teste da fila cheia: o teto do que espera a thread, em bytes. */
    GaleriaDaFoto(int sdk, Gravador gravador, Registro registro, Executor thread, LongSupplier relogio, Aviso aviso,
                  long pendenteNoMaximo) {
        this.ativa = sdk >= Build.VERSION_CODES.Q && gravador != null;
        this.gravador = gravador;
        this.registro = registro;
        this.thread = thread;
        this.relogio = relogio;
        this.aviso = aviso;
        this.pendenteNoMaximo = pendenteNoMaximo;
    }

    /**
     * A galeria do app, uma por processo (o CascaPlugin a guarda, como o espelho): o MediaStore do aparelho, o registro em
     * files/{@value #ARQUIVO_DO_REGISTRO} e a thread da galeria. Roda no load() do plugin, na thread de UI, então nada
     * de ler o registro aqui: ele só é lido na primeira foto, na thread da galeria.
     */
    static GaleriaDaFoto doApp(Context contexto) {
        Context app = contexto.getApplicationContext() != null ? contexto.getApplicationContext() : contexto;
        Gravador gravador = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            gravador = new NoMediaStore(new DestinoDoSistema(app.getContentResolver()));
        }
        return new GaleriaDaFoto(Build.VERSION.SDK_INT, gravador,
            new Registro(new File(app.getFilesDir(), ARQUIVO_DO_REGISTRO), Registro.TETO), threadDaGaleria(),
            System::currentTimeMillis, msg -> Log.w(TAG, "galeria: " + msg));
    }

    // Uma thread só, de prioridade de fundo: as fotos saem na ordem, uma por vez, sem disputar com a tela.
    private static Executor threadDaGaleria() {
        return Executors.newSingleThreadExecutor(tarefa -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            tarefa.run();
        }, "GcCasca-galeria"));
    }

    /** Se a cópia na galeria vale neste aparelho (Android 10 em diante). */
    boolean ativa() {
        return ativa;
    }

    // --- o que é foto (a thread do espelho pergunta antes de chamar) -------------------------------------------------

    /**
     * O texto é uma foto em dataURL ({@code data:image/jpeg;base64,...}, ou PNG, ou WebP)? Só olha o cabeçalho (até
     * {@value #CABECALHO_NO_MAXIMO} caracteres): roda na thread do espelho, a cada idbPut de texto.
     */
    static boolean ehFotoEmTexto(String texto) {
        return mimeDoTexto(texto) != null;
    }

    /** O mime da foto em dataURL, em minúsculas, ou null se o texto não é uma. */
    static String mimeDoTexto(String texto) {
        if (texto == null || !texto.regionMatches(true, 0, "data:image/", 0, 11)) return null;
        int fim = Math.min(texto.length(), CABECALHO_NO_MAXIMO);
        int virgula = -1;
        for (int i = 11; i < fim; i++) {
            if (texto.charAt(i) == ',') {
                virgula = i;
                break;
            }
        }
        if (virgula < 0) return null;
        Matcher m = CABECALHO_DE_FOTO.matcher(texto.substring(0, virgula));
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** O mime do Blob é de foto (JPEG, PNG ou WebP)? O vídeo, o áudio e o resto ficam fora. */
    static boolean ehMimeDeFoto(String mime) {
        if (mime == null) return false;
        String m = mime.trim().toLowerCase(Locale.ROOT);
        return m.equals("image/jpeg") || m.equals("image/png") || m.equals("image/webp");
    }

    /**
     * Os bytes da foto em dataURL, ou null se o texto não é uma foto ou o base64 está torto. O {@code android.util.Base64}
     * pula em silêncio o que não é do alfabeto, então o base64 é conferido antes ({@link Mensageiro#base64Valido}): sem
     * isso, um texto torto viraria uma foto quebrada na galeria.
     */
    static byte[] decodificar(String dataUrl) {
        if (mimeDoTexto(dataUrl) == null) return null;
        String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
        if (b64.isEmpty() || !Mensageiro.base64Valido(b64)) return null;
        try {
            return Base64.decode(b64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * O formato pelos primeiros bytes (a assinatura do JPEG, do PNG e do WebP), ou null. É ele que decide o MIME_TYPE da
     * galeria, e não o que a página declarou: um Blob que diz image/jpeg e não é foto (o vazio, o cortado) não vira uma
     * foto quebrada na galeria.
     */
    static String formatoPelosBytes(byte[] b) {
        if (b == null) return null;
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0d
            && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
            && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** O nome na galeria: GridCo_AAAAMMDD_HHMMSS na hora local do aparelho, com a extensão do formato. */
    static String nomeDoArquivo(long quando, TimeZone fuso, String mime) {
        SimpleDateFormat formato = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT);
        formato.setTimeZone(fuso);
        String extensao = "image/png".equals(mime) ? ".png" : "image/webp".equals(mime) ? ".webp" : ".jpg";
        return "GridCo_" + formato.format(new Date(quando)) + extensao;
    }

    /** O resumo do conteúdo para a deduplicação: os 16 primeiros bytes do SHA-256, em hex minúsculo. */
    static String resumo(byte[] bytes) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((sha[i] >> 4) & 0xf, 16)).append(Character.forDigit(sha[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e); // todo Android tem
        }
    }

    // --- o que o Mensageiro chama (thread do espelho) -----------------------------------------------------------------

    @Override
    public void fotoEmTexto(String dataUrl) {
        if (!ativa || dataUrl == null) return;
        agendar(2L * dataUrl.length(), null, () -> {
            byte[] bytes = decodificar(dataUrl);
            if (bytes == null) {
                foraDoFormato++;
                avisar("uma foto em texto com o base64 torto ficou fora da galeria (" + foraDoFormato
                    + " nesta partida)");
                return;
            }
            levar(bytes);
        });
    }

    @Override
    public void fotoEmArquivo(File arquivo, String mime) {
        if (!ativa || arquivo == null) return;
        long tamanho = arquivo.length();
        if (tamanho <= 0 || tamanho > FOTO_NO_MAXIMO) {
            avisar("um Blob de foto vazio ou maior que " + (FOTO_NO_MAXIMO / (1024 * 1024)) + " MB ficou fora da galeria");
            return;
        }
        InputStream entrada;
        try {
            entrada = new FileInputStream(arquivo);
        } catch (IOException | RuntimeException e) {
            avisar("o arquivo de uma foto não abriu; ela ficou fora da galeria");
            return;
        }
        agendar(tamanho, entrada, () -> {
            byte[] bytes;
            try (InputStream in = entrada) {
                bytes = lerTudo(in, (int) tamanho);
            } catch (IOException e) {
                falhas++;
                avisar("o arquivo de uma foto não foi lido; ela ficou fora da galeria (" + falhas
                    + " falha(s) nesta partida)");
                return;
            }
            levar(bytes);
        });
    }

    // Põe a tarefa na thread da galeria, dentro do teto de memória. A recusada (o teto, a thread que não aceita) fecha o
    // arquivo aberto e só avisa. A tarefa nunca deixa escapar nada: o erro que sai de uma thread do app derruba o
    // processo (o tratador padrão do Android), e com ele o espelho e o que o técnico estava fazendo.
    private void agendar(long custo, InputStream paraFecharSeRecusada, Runnable tarefa) {
        if (pendente.addAndGet(custo) > pendenteNoMaximo) {
            pendente.addAndGet(-custo);
            recusada(paraFecharSeRecusada, "a fila da galeria está cheia");
            return;
        }
        try {
            thread.execute(() -> {
                try {
                    tarefa.run();
                } catch (RuntimeException | OutOfMemoryError e) {
                    falhas++;
                    avisar("a cópia de uma foto falhou (" + e.getClass().getSimpleName() + "; " + falhas
                        + " falha(s) nesta partida)");
                } finally {
                    pendente.addAndGet(-custo);
                }
            });
        } catch (RejectedExecutionException e) {
            pendente.addAndGet(-custo);
            recusada(paraFecharSeRecusada, "a thread da galeria não aceitou a foto");
        }
    }

    private void recusada(InputStream paraFechar, String motivo) {
        if (paraFechar != null) {
            try {
                paraFechar.close();
            } catch (IOException e) {
                // nada a fazer
            }
        }
        avisar(motivo + "; uma foto ficou fora dela (" + deixadas.incrementAndGet() + " nesta partida)");
    }

    // --- na thread da galeria ---------------------------------------------------------------------------------------

    // A foto já em bytes: confere o formato, pula a que já está na galeria e grava a nova. Só entra no registro a que
    // gravou: a que falhou vai de novo na próxima cópia que a página mandar (a fila do Concluir, a reconciliação).
    private void levar(byte[] bytes) {
        String mime = formatoPelosBytes(bytes);
        if (mime == null) {
            foraDoFormato++;
            avisar("um conteúdo que não é JPEG, PNG nem WebP ficou fora da galeria (" + foraDoFormato + " nesta partida)");
            return;
        }
        String resumo = resumo(bytes);
        if (registro.tem(resumo)) return; // a mesma foto por outra chave, ou de volta pela restauração: já está lá
        long agora = relogio.getAsLong();
        boolean gravou;
        try {
            gravou = gravador.gravar(bytes, mime, nomeDoArquivo(agora, TimeZone.getDefault(), mime), agora);
        } catch (RuntimeException e) {
            gravou = false;
        }
        if (!gravou) {
            falhas++;
            avisar("uma foto não foi para a galeria (" + falhas + " falha(s) nesta partida)");
            return;
        }
        gravadas++;
        Log.i(TAG, "galeria: uma foto foi para a galeria (" + gravadas + " nesta partida)");
        if (!registro.anotar(resumo)) {
            avisar("o registro das fotos na galeria não gravou no disco; vale só até o app fechar");
        }
    }

    private void avisar(String msg) {
        try {
            aviso.avisar(msg);
        } catch (RuntimeException e) {
            // O aviso nunca derruba a galeria.
        }
    }

    private static byte[] lerTudo(InputStream in, int tamanho) throws IOException {
        byte[] bytes = new byte[tamanho];
        int lidos = 0;
        while (lidos < tamanho) {
            int r = in.read(bytes, lidos, tamanho - lidos);
            if (r < 0) throw new EOFException("o arquivo acabou antes do tamanho");
            lidos += r;
        }
        return bytes;
    }

    // --- a deduplicação -----------------------------------------------------------------------------------------------

    /**
     * Os resumos das fotos que já foram para a galeria, os {@value #TETO} mais novos, num arquivo de texto do
     * armazenamento privado do app (um por linha): atravessa o app fechado, a atualização e o aparelho reiniciado. Sai
     * com o "Limpar armazenamento" (que o suporte nunca manda fazer, porque apaga o espelho), e aí uma foto que o espelho
     * recriado mandar de novo à galeria pode aparecer duas vezes.
     *
     * Lido na primeira consulta (na thread da galeria, nunca na de UI). A gravação é por linha acrescentada; quando o
     * arquivo passa um quinto do teto além dele, é reescrito só com os que valem (numa cópia que troca o arquivo de uma
     * vez, como o blob do espelho). O arquivo ilegível vale como vazio: o custo é uma foto repetida, nunca uma perdida.
     */
    static final class Registro {

        /** Quantas fotos o registro lembra. Uma OS tem dezenas; 5000 cobrem meses de trabalho de um técnico. */
        static final int TETO = 5000;

        private static final Pattern RESUMO = Pattern.compile("[0-9a-f]{32}");

        private final File arquivo;
        private final int teto;
        private LinkedHashSet<String> resumos; // null até a primeira consulta
        private int linhasNoArquivo;

        Registro(File arquivo, int teto) {
            this.arquivo = arquivo;
            this.teto = teto;
        }

        synchronized boolean tem(String resumo) {
            return carregados().contains(resumo);
        }

        /** Anota uma foto que foi para a galeria. Devolve false se o disco não guardou (a memória guarda até o app fechar). */
        synchronized boolean anotar(String resumo) {
            LinkedHashSet<String> todos = carregados();
            if (!todos.add(resumo)) return true;
            esquecerOsMaisVelhos(todos);
            if (linhasNoArquivo + 1 > teto + teto / 5) return reescrever(todos);
            try (Writer saida = new OutputStreamWriter(new FileOutputStream(arquivo, true), StandardCharsets.US_ASCII)) {
                saida.write(resumo);
                saida.write('\n');
                linhasNoArquivo++;
                return true;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        private LinkedHashSet<String> carregados() {
            if (resumos != null) return resumos;
            resumos = new LinkedHashSet<>();
            linhasNoArquivo = 0;
            if (arquivo.isFile()) {
                try (BufferedReader leitor = new BufferedReader(
                    new InputStreamReader(new FileInputStream(arquivo), StandardCharsets.US_ASCII))) {
                    String linha;
                    while ((linha = leitor.readLine()) != null) {
                        linhasNoArquivo++;
                        String r = linha.trim();
                        if (!RESUMO.matcher(r).matches()) continue; // a linha torta (o processo que morreu no meio) não conta
                        resumos.remove(r); // a repetida vale pela posição mais nova
                        resumos.add(r);
                    }
                } catch (IOException | RuntimeException e) {
                    // Fica o que deu para ler.
                }
            }
            esquecerOsMaisVelhos(resumos);
            return resumos;
        }

        private void esquecerOsMaisVelhos(LinkedHashSet<String> todos) {
            Iterator<String> mais = todos.iterator();
            while (todos.size() > teto && mais.hasNext()) {
                mais.next();
                mais.remove();
            }
        }

        private boolean reescrever(LinkedHashSet<String> todos) {
            File copia = new File(arquivo.getPath() + ".novo");
            try (Writer saida = new OutputStreamWriter(new FileOutputStream(copia, false), StandardCharsets.US_ASCII)) {
                for (String r : todos) {
                    saida.write(r);
                    saida.write('\n');
                }
            } catch (IOException | RuntimeException e) {
                copia.delete();
                return false;
            }
            try {
                EspelhoSqlite.trocarArquivo(copia, arquivo, File::renameTo);
            } catch (RuntimeException e) {
                copia.delete();
                return false;
            }
            linhasNoArquivo = todos.size();
            return true;
        }
    }

    // --- o MediaStore ------------------------------------------------------------------------------------------------

    /** As quatro operações do MediaStore que a gravação usa. O de produção é o {@link DestinoDoSistema}. */
    interface Destino {
        /** A linha nova; null se o sistema não a criou. */
        Uri inserir(ContentValues valores);

        OutputStream abrir(Uri uri) throws IOException;

        /** Quantas linhas mudaram. */
        int atualizar(Uri uri, ContentValues valores);

        void apagar(Uri uri);
    }

    /** O MediaStore do aparelho, pelo ContentResolver: a coleção das imagens do armazenamento externo. */
    static final class DestinoDoSistema implements Destino {
        private final ContentResolver resolver;

        DestinoDoSistema(ContentResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public Uri inserir(ContentValues valores) {
            return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores);
        }

        @Override
        public OutputStream abrir(Uri uri) throws IOException {
            return resolver.openOutputStream(uri);
        }

        @Override
        public int atualizar(Uri uri, ContentValues valores) {
            return resolver.update(uri, valores, null, null);
        }

        @Override
        public void apagar(Uri uri) {
            resolver.delete(uri, null, null);
        }
    }

    /**
     * A gravação no MediaStore do Android 10 em diante: a linha nasce pendente (IS_PENDING 1: a galeria não mostra, e
     * nenhum app lê a foto pela metade), recebe os bytes e só então fica à vista (IS_PENDING 0). Se a escrita ou a
     * troca falhar, a linha sai: senão sobraria uma foto pendente, invisível, que o Android só apaga dias depois.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    static final class NoMediaStore implements Gravador {
        private final Destino destino;

        NoMediaStore(Destino destino) {
            this.destino = destino;
        }

        @Override
        public boolean gravar(byte[] bytes, String mime, String nome, long tiradaEm) {
            ContentValues valores = new ContentValues();
            valores.put(MediaStore.MediaColumns.DISPLAY_NAME, nome); // o nome repetido o MediaStore resolve (" (1)")
            valores.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            valores.put(MediaStore.MediaColumns.RELATIVE_PATH, PASTA);
            valores.put(MediaStore.MediaColumns.DATE_TAKEN, tiradaEm);
            valores.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = destino.inserir(valores);
            if (uri == null) return false;
            boolean pronta = false;
            try {
                try (OutputStream saida = destino.abrir(uri)) {
                    if (saida == null) throw new IOException("o MediaStore não deu onde escrever");
                    saida.write(bytes);
                }
                ContentValues aVista = new ContentValues();
                aVista.put(MediaStore.MediaColumns.IS_PENDING, 0);
                pronta = destino.atualizar(uri, aVista) > 0;
                return pronta;
            } catch (IOException | RuntimeException e) {
                return false;
            } finally {
                if (!pronta) {
                    try {
                        destino.apagar(uri);
                    } catch (RuntimeException e) {
                        // A pendente que sobrar o Android apaga sozinho; o aviso é de quem chama.
                    }
                }
            }
        }
    }
}
