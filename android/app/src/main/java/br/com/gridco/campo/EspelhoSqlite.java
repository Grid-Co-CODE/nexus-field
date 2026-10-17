package br.com.gridco.campo;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * O espelho nativo (spec 6.4) em SQLite e arquivos, só no armazenamento privado do app.
 *
 * <pre>
 * banco espelho.db
 *   ls(chave PK, valor BLOB, cifrado INT)        localStorage de trabalho e de cache, SEM a fila
 *   fila(id AUTOINCREMENT, valor TEXT, hash TEXT) as 3 versões mais novas da gc_campo_queue
 *   idb(chave PK, tipo, texto, arquivo, mime)    IndexedDB das fotos: texto, json ou blob
 *   meta(k PK, v)                                a sentinela do espelho
 * files/espelho/idb/&lt;sha1 da chave&gt;.bin    os bytes de cada blob (o nome nunca é a chave: ela tem e-mail)
 * files/espelho/tmp/&lt;id&gt;.parte             o blob que ainda está chegando, pedaço a pedaço
 * </pre>
 *
 * Regras que o código não diz sozinho:
 * <ul>
 *   <li>Nada de nome de chave nem de valor em log ou em mensagem de exceção: as chaves do localStorage têm e-mail do
 *       técnico. O log só tem contagem.</li>
 *   <li>Só a identidade (gc_campo_ident) é cifrada; o resto do localStorage é rascunho e cache. Se a cifra falhar ao
 *       gravar, a exceção sobe e a identidade NÃO é gravada em claro como plano B.</li>
 *   <li>Valor lido em pedaços de 512 KB: o CursorWindow do Android recusa uma linha de mais de ~2 MB, e uma chave do
 *       localStorage (o limite é 5 MB) pode passar disso. Sem isso, um valor grande derrubaria a restauração inteira.</li>
 *   <li>Os métodos são sincronizados: o canal do WebView entrega as mensagens fora da thread principal.</li>
 * </ul>
 */
public class EspelhoSqlite implements ArmazemEspelho {

    private static final String TAG = "EspelhoSqlite";
    static final String NOME_DO_BANCO = "espelho.db";

    private static final String CHAVE_FILA = "gc_campo_queue";
    private static final String CHAVE_IDENT = "gc_campo_ident";
    private static final String META_SENTINELA = "sentinela";
    private static final int VERSOES_DA_FILA = 3;
    private static final long IDADE_MAXIMA_DA_PARTE_MS = 24L * 60L * 60L * 1000L;
    private static final int PEDACO_DE_LEITURA = 512 * 1024;

    // O id vira nome de arquivo (files/espelho/tmp/<id>.parte). O script só gera [a-z0-9-], mas quem decide é este
    // lado: nada de barra, ponto, espaço, NUL nem vazio.
    private static final Pattern ID_DA_PARTE = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    /** O blob em chegada: de que chave é, quantos pedaços terá e quantos já vieram (na ordem). */
    private static final class Remessa {
        final String chave;
        final int total;
        int recebidos = 0;
        String mime;

        Remessa(String chave, int total, String mime) {
            this.chave = chave;
            this.total = total;
            this.mime = mime == null ? "" : mime;
        }
    }

    /** Uma linha da tabela idb, já conferida (o arquivo do blob existe). */
    private static final class Entrada {
        final String chave;
        final String tipo;
        final String mime;
        final String texto;
        final File arquivo;

        Entrada(String chave, String tipo, String mime, String texto, File arquivo) {
            this.chave = chave;
            this.tipo = tipo;
            this.mime = mime;
            this.texto = texto;
            this.arquivo = arquivo;
        }
    }

    private final Cifra cifra;
    private final Banco ajudante;
    private final File pastaIdb;
    private final File pastaTmp;
    private final Map<String, Remessa> emAndamento = new HashMap<>();
    private SQLiteDatabase banco; // aberto na primeira necessidade

    public EspelhoSqlite(Context ctx, Cifra cifra) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.cifra = cifra;
        File raiz = new File(app.getFilesDir(), "espelho");
        this.pastaIdb = new File(raiz, "idb");
        this.pastaTmp = new File(raiz, "tmp");
        garantirPasta(pastaIdb);
        garantirPasta(pastaTmp);
        apagarPartesVelhas();
        this.ajudante = new Banco(app);
    }

    /** Fecha o banco. Chamar de novo não faz mal; um uso depois disso o reabre. */
    public synchronized void close() {
        if (banco != null) {
            banco.close();
            banco = null;
        }
        ajudante.close();
    }

    // --- localStorage -----------------------------------------------------------------------------------------------

    @Override
    public synchronized void gravarLs(String k, String v) {
        if (chaveRecusada(k) || v == null) return;
        SQLiteDatabase db = bd();
        if (CHAVE_FILA.equals(k)) {
            gravarFila(db, v);
            return;
        }
        byte[] valor;
        int cifrado = 0;
        if (CHAVE_IDENT.equals(k)) {
            valor = cifra.cifrar(v);
            cifrado = 1;
        } else {
            valor = v.getBytes(StandardCharsets.UTF_8);
        }
        // execSQL (e não insert): o insert() do Android, se falhar, registra no log o ContentValues inteiro, e nele
        // estariam o nome da chave e o valor.
        db.execSQL("INSERT OR REPLACE INTO ls (chave, valor, cifrado) VALUES (?, ?, ?)",
            new Object[] {k, valor, cifrado});
    }

    @Override
    public synchronized void apagarLs(String k) {
        if (chaveRecusada(k)) return;
        SQLiteDatabase db = bd();
        if (CHAVE_FILA.equals(k)) {
            db.execSQL("DELETE FROM fila");
        } else {
            db.execSQL("DELETE FROM ls WHERE chave = ?", new Object[] {k});
        }
    }

    // O que nunca entra, qualquer que seja a mensagem que peça: o MSAL (a sessão da Microsoft; se perder, o técnico entra
    // de novo) e as chaves da casca (a sentinela do localStorage e a marca da perda, que são da WebView e voltam por
    // outro caminho). A classificação do inicio.js já as deixa de fora; aqui é a segunda trava.
    private static boolean chaveRecusada(String k) {
        return k == null || k.isEmpty() || k.startsWith("msal.") || k.startsWith("gc_casca_");
    }

    // A fila entra como versão nova, a menos que seja igual à mais nova: a página regrava a fila com o mesmo conteúdo a
    // toda hora, e cada cópia empurraria uma versão de verdade para fora das três (A → B → B → B deixaria só B). Escolha
    // da Task 5: o brief diz "insere uma versão nova", e um valor igual ao anterior não é versão nova.
    private void gravarFila(SQLiteDatabase db, String valor) {
        String hash = Fnv.hash(valor);
        db.beginTransaction();
        try {
            if (!igualAMaisNova(db, hash, valor)) {
                db.execSQL("INSERT INTO fila (valor, hash) VALUES (?, ?)", new Object[] {valor, hash});
                db.execSQL("DELETE FROM fila WHERE id NOT IN (SELECT id FROM fila ORDER BY id DESC LIMIT "
                    + VERSOES_DA_FILA + ")");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // O hash (32 bits) decide rápido; o texto inteiro decide de verdade, porque uma colisão tomada por "igual" perderia
    // uma versão da fila.
    private boolean igualAMaisNova(SQLiteDatabase db, String hash, String valor) {
        String idDaNova = primeiroTexto(db, "SELECT id FROM fila ORDER BY id DESC LIMIT 1");
        if (idDaNova == null) return false;
        if (!hash.equals(primeiroTexto(db, "SELECT hash FROM fila WHERE id = ?", idDaNova))) return false;
        byte[] gravado = lerEmPedacos(db, "fila", "id", idDaNova, "valor");
        return gravado != null && valor.equals(new String(gravado, StandardCharsets.UTF_8));
    }

    @Override
    public synchronized List<String> filaHashes() {
        List<String> hashes = new ArrayList<>();
        try (Cursor c = bd().rawQuery("SELECT hash FROM fila ORDER BY id DESC LIMIT " + VERSOES_DA_FILA, null)) {
            while (c.moveToNext()) hashes.add(c.getString(0));
        }
        return hashes;
    }

    @Override
    public synchronized String filaMaisNova() {
        SQLiteDatabase db = bd();
        String id = primeiroTexto(db, "SELECT id FROM fila ORDER BY id DESC LIMIT 1");
        if (id == null) return null;
        byte[] valor = lerEmPedacos(db, "fila", "id", id, "valor");
        return valor == null ? null : new String(valor, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized Map<String, String> lsTudo() {
        SQLiteDatabase db = bd();
        Map<String, Boolean> linhas = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery("SELECT chave, cifrado FROM ls ORDER BY chave", null)) {
            while (c.moveToNext()) linhas.put(c.getString(0), c.getInt(1) != 0);
        }
        Map<String, String> saida = new LinkedHashMap<>();
        int ilegiveis = 0;
        for (Map.Entry<String, Boolean> linha : linhas.entrySet()) {
            byte[] valor = lerEmPedacos(db, "ls", "chave", linha.getKey(), "valor");
            if (valor == null) valor = new byte[0]; // a linha existe: valor NULL é o texto vazio
            if (linha.getValue()) {
                try {
                    saida.put(linha.getKey(), cifra.decifrar(valor));
                } catch (RuntimeException e) {
                    ilegiveis++; // a chave do Keystore se perdeu ou o dado está corrompido: fica de fora, o resto sai
                }
            } else {
                saida.put(linha.getKey(), new String(valor, StandardCharsets.UTF_8));
            }
        }
        if (ilegiveis > 0) Log.w(TAG, ilegiveis + " valor(es) cifrado(s) ilegível(is) ficou(aram) de fora do retrato");
        return saida;
    }

    // --- IndexedDB --------------------------------------------------------------------------------------------------

    @Override
    public synchronized void gravarIdbTexto(String k, String tipo, String v) {
        if (k == null || k.isEmpty() || v == null) return;
        if (!"texto".equals(tipo) && !"json".equals(tipo)) {
            throw new IllegalArgumentException("tipo de entrada do IndexedDB desconhecido");
        }
        SQLiteDatabase db = bd();
        String arquivoAntigo = arquivoDaEntrada(db, k);
        db.execSQL("INSERT OR REPLACE INTO idb (chave, tipo, texto, arquivo, mime) VALUES (?, ?, ?, NULL, NULL)",
            new Object[] {k, tipo, v});
        apagarArquivoDoBlob(arquivoAntigo); // era um blob e agora é texto
    }

    @Override
    public synchronized void apagarIdb(String k) {
        if (k == null || k.isEmpty()) return;
        SQLiteDatabase db = bd();
        String arquivoAntigo = arquivoDaEntrada(db, k);
        db.execSQL("DELETE FROM idb WHERE chave = ?", new Object[] {k});
        apagarArquivoDoBlob(arquivoAntigo);
    }

    @Override
    public synchronized void receberPedaco(String k, String id, int i, int n, String mime, byte[] dados) {
        // Erro de quem manda (bug do script ou do canal): sobe. A exceção não leva a chave.
        if (id == null || !ID_DA_PARTE.matcher(id).matches()) {
            throw new IllegalArgumentException("id de pedaço inválido");
        }
        if (n < 1 || i < 0 || i >= n) throw new IllegalArgumentException("índice ou total de pedaços impossível");
        if (k == null || k.isEmpty() || dados == null) return;

        File parte = new File(pastaTmp, id + ".parte");
        Remessa remessa = emAndamento.get(id);
        if (i == 0) {
            descartarRemessa(id); // um id repetido recomeça do zero
            remessa = new Remessa(k, n, mime);
            emAndamento.put(id, remessa);
        } else if (remessa == null || remessa.total != n || !remessa.chave.equals(k) || remessa.recebidos != i) {
            // Pedaço sem começo, repetido, fora de ordem ou de outra gravação: o blob não pode ser conferido, e uma
            // entrada pela metade seria pior que nenhuma. Fica a cópia anterior; a reconciliação da próxima partida
            // manda de novo. Só contagem no log.
            descartarRemessa(id);
            Log.w(TAG, "pedaço fora da sequência descartado (" + i + " de " + n + ")");
            return;
        }

        boolean ultimo = i == n - 1;
        try (FileOutputStream saida = new FileOutputStream(parte, i > 0)) {
            saida.write(dados);
            if (ultimo) saida.getFD().sync(); // o blob inteiro está no disco antes de virar entrada
        } catch (IOException e) {
            descartarRemessa(id);
            throw new IllegalStateException("não foi possível gravar o pedaço de blob", e);
        }
        remessa.recebidos++;
        if (mime != null && !mime.isEmpty()) remessa.mime = mime;
        if (ultimo) concluirRemessa(id, parte, remessa);
    }

    // O último pedaço chegou e todos vieram na ordem: a parte vira o arquivo da entrada e a linha é gravada, juntas
    // (se uma falhar, a outra desfaz). O nome é o sha1 da chave, então regravar a mesma chave troca o arquivo.
    private void concluirRemessa(String id, File parte, Remessa remessa) {
        String nome = sha1(remessa.chave) + ".bin";
        File destino = new File(pastaIdb, nome);
        SQLiteDatabase db = bd();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR REPLACE INTO idb (chave, tipo, texto, arquivo, mime) VALUES (?, 'blob', NULL, ?, ?)",
                new Object[] {remessa.chave, nome, remessa.mime});
            // Fora do Android (o JVM do teste no Windows) o renameTo não troca um arquivo que já existe.
            if (destino.exists() && !destino.delete()) {
                throw new IllegalStateException("não foi possível trocar o arquivo do blob");
            }
            if (!parte.renameTo(destino)) throw new IllegalStateException("não foi possível mover o blob para o espelho");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            emAndamento.remove(id);
            parte.delete(); // sobra só se algo falhou; depois do rename não há mais o que apagar
        }
    }

    private void descartarRemessa(String id) {
        emAndamento.remove(id);
        new File(pastaTmp, id + ".parte").delete();
    }

    @Override
    public synchronized List<String> idbChaves() {
        List<String> chaves = new ArrayList<>();
        for (Entrada e : entradasIdb(false)) chaves.add(e.chave);
        return chaves;
    }

    @Override
    public void paraCadaIdb(ItemIdb consumidor) {
        // Colhe com o espelho travado e entrega solto: o consumidor (a restauração do canal) manda cada entrada à página e
        // não pode segurar as gravações que chegam enquanto isso.
        List<Entrada> entradas;
        synchronized (this) {
            entradas = entradasIdb(true);
        }
        for (Entrada e : entradas) consumidor.aceitar(e.chave, e.tipo, e.mime, e.texto, e.arquivo);
    }

    // As entradas COMPLETAS do IndexedDB: um blob cujo arquivo sumiu do disco não conta, e idbChaves() e paraCadaIdb()
    // concordam nisso. Anunciar uma chave que a restauração não consegue mandar deixaria o `n` do idbFim sem bater.
    private List<Entrada> entradasIdb(boolean comTexto) {
        SQLiteDatabase db = bd();
        List<String[]> linhas = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT chave, tipo, arquivo, mime FROM idb ORDER BY chave", null)) {
            while (c.moveToNext()) linhas.add(new String[] {c.getString(0), c.getString(1), c.getString(2), c.getString(3)});
        }
        List<Entrada> entradas = new ArrayList<>();
        int semArquivo = 0;
        for (String[] linha : linhas) {
            if ("blob".equals(linha[1])) {
                File arquivo = linha[2] == null ? null : new File(pastaIdb, linha[2]);
                if (arquivo == null || !arquivo.isFile()) {
                    semArquivo++;
                    continue;
                }
                entradas.add(new Entrada(linha[0], "blob", linha[3], null, arquivo));
            } else {
                String texto = null;
                if (comTexto) {
                    byte[] bytes = lerEmPedacos(db, "idb", "chave", linha[0], "texto");
                    texto = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
                }
                entradas.add(new Entrada(linha[0], linha[1], null, texto, null));
            }
        }
        if (semArquivo > 0) Log.w(TAG, semArquivo + " blob(s) sem o arquivo ficou(aram) fora do retrato");
        return entradas;
    }

    private String arquivoDaEntrada(SQLiteDatabase db, String k) {
        return primeiroTexto(db, "SELECT arquivo FROM idb WHERE chave = ?", k);
    }

    private void apagarArquivoDoBlob(String nome) {
        if (nome != null) new File(pastaIdb, nome).delete();
    }

    // --- sentinela --------------------------------------------------------------------------------------------------

    @Override
    public synchronized void marcarSentinela() {
        bd().execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES (?, '1')", new Object[] {META_SENTINELA});
    }

    @Override
    public synchronized boolean temEspelho() {
        return primeiroTexto(bd(), "SELECT v FROM meta WHERE k = ?", META_SENTINELA) != null;
    }

    // --- apoio ------------------------------------------------------------------------------------------------------

    private SQLiteDatabase bd() {
        if (banco == null || !banco.isOpen()) banco = ajudante.getWritableDatabase();
        return banco;
    }

    /** A 1ª coluna da 1ª linha como texto, ou null se não há linha (ou é NULL). */
    private static String primeiroTexto(SQLiteDatabase db, String sql, String... argumentos) {
        try (Cursor c = db.rawQuery(sql, argumentos.length == 0 ? null : argumentos)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
        }
    }

    /**
     * Lê uma coluna (BLOB ou TEXT) em pedaços de 512 KB, como bytes. null se a linha não existe ou o valor é NULL. A
     * tabela e as colunas são constantes deste arquivo; só a chave é argumento. O CAST AS BLOB faz o tamanho e o corte
     * serem em bytes (UTF-8 para o texto) e a leitura não parar num NUL dentro do texto.
     */
    private static byte[] lerEmPedacos(SQLiteDatabase db, String tabela, String colunaChave, String chave, String coluna) {
        String bytes = "CAST(" + coluna + " AS BLOB)";
        byte[] saida;
        int copiados;
        try (Cursor c = db.rawQuery("SELECT length(" + bytes + "), substr(" + bytes + ", 1, " + PEDACO_DE_LEITURA
            + ") FROM " + tabela + " WHERE " + colunaChave + " = ?", new String[] {chave})) {
            if (!c.moveToFirst() || c.isNull(0)) return null;
            int total = c.getInt(0);
            if (total == 0) return new byte[0]; // o substr de um valor vazio devolve NULL
            byte[] primeiro = c.getBlob(1);
            if (primeiro == null) throw new IllegalStateException("leitura do espelho incompleta");
            if (primeiro.length >= total) return primeiro;
            saida = new byte[total];
            System.arraycopy(primeiro, 0, saida, 0, primeiro.length);
            copiados = primeiro.length;
        }
        while (copiados < saida.length) {
            int tamanho = Math.min(PEDACO_DE_LEITURA, saida.length - copiados);
            try (Cursor c = db.rawQuery("SELECT substr(" + bytes + ", ?, ?) FROM " + tabela + " WHERE " + colunaChave
                + " = ?", new String[] {String.valueOf(copiados + 1), String.valueOf(tamanho), chave})) {
                byte[] pedaco = c.moveToFirst() ? c.getBlob(0) : null;
                if (pedaco == null || pedaco.length == 0) throw new IllegalStateException("leitura do espelho incompleta");
                System.arraycopy(pedaco, 0, saida, copiados, Math.min(pedaco.length, saida.length - copiados));
                copiados += pedaco.length;
            }
        }
        return saida;
    }

    private static String sha1(String texto) {
        try {
            byte[] resumo = MessageDigest.getInstance("SHA-1").digest(texto.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(resumo.length * 2);
            for (byte b : resumo) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 indisponível", e); // todo Android tem
        }
    }

    private static void garantirPasta(File pasta) {
        if (!pasta.isDirectory() && !pasta.mkdirs() && !pasta.isDirectory()) {
            throw new IllegalStateException("não foi possível criar a pasta do espelho");
        }
    }

    // Na abertura: parte de blob com mais de 24 h é de uma carga que morreu no meio (o último pedaço nunca veio) e não
    // volta mais. As mais novas ficam, porque o app pode ter sido fechado no meio de um vídeo e o resto ainda chegar.
    private void apagarPartesVelhas() {
        File[] partes = pastaTmp.listFiles();
        if (partes == null) return;
        long limite = System.currentTimeMillis() - IDADE_MAXIMA_DA_PARTE_MS;
        int apagadas = 0;
        for (File parte : partes) {
            if (parte.isFile() && parte.lastModified() < limite && parte.delete()) apagadas++;
        }
        if (apagadas > 0) Log.i(TAG, apagadas + " parte(s) órfã(s) com mais de 24 h apagada(s)");
    }

    /** O banco: quatro tabelas, versão 1. */
    private static final class Banco extends SQLiteOpenHelper {
        Banco(Context ctx) {
            super(ctx, NOME_DO_BANCO, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE ls (chave TEXT PRIMARY KEY, valor BLOB, cifrado INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE fila (id INTEGER PRIMARY KEY AUTOINCREMENT, valor TEXT NOT NULL, hash TEXT NOT NULL)");
            db.execSQL("CREATE TABLE idb (chave TEXT PRIMARY KEY, tipo TEXT NOT NULL, texto TEXT, arquivo TEXT, mime TEXT)");
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int antiga, int nova) {
            // Só há a versão 1. Uma mudança de tabela futura entra aqui, sem apagar o que o espelho já guarda.
        }
    }
}
