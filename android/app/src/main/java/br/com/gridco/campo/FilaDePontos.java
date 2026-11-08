package br.com.gridco.campo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A fila local dos pontos da coleta de equipe que ainda não subiram. Banco próprio (equipe.db), fora do espelho, para
 * nunca misturar posição com os dados do técnico.
 *
 * <p>Teto: no máximo 1000 pontos e nada com mais de 24 h (o celular sem sinal o dia todo não pode encher o
 * armazenamento). A poda roda a cada gravação, e quando passa, sai o mais velho (o menor id). Nada de coordenada em
 * log nem em mensagem de exceção.</p>
 */
final class FilaDePontos {

    static final String NOME_DO_BANCO = "equipe.db";
    static final int MAXIMO = 1000;
    static final long IDADE_MAXIMA_MS = 24L * 60L * 60L * 1000L;

    private final Helper helper;

    FilaDePontos(Context ctx) {
        helper = new Helper(ctx.getApplicationContext());
    }

    synchronized void guardar(PontoDaEquipe p) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("t", p.t);
        v.put("corpo", p.json().toString());
        db.beginTransaction();
        try {
            db.insertOrThrow("pontos", null, v);
            db.delete("pontos", "t < ?", new String[] {String.valueOf(System.currentTimeMillis() - IDADE_MAXIMA_MS)});
            db.execSQL("DELETE FROM pontos WHERE id IN (SELECT id FROM pontos ORDER BY id DESC LIMIT -1 OFFSET "
                    + MAXIMO + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Os ids dos pontos mais antigos, até o máximo do lote, na ordem em que foram gravados. */
    synchronized List<Long> idsDoLote(int max) {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT id FROM pontos ORDER BY id LIMIT " + Math.max(0, max), null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        return ids;
    }

    /** O corpo (JSON) dos pontos desses ids, na ordem de gravação. */
    synchronized JSONArray lote(List<Long> ids) {
        JSONArray saida = new JSONArray();
        if (ids.isEmpty()) return saida;
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT corpo FROM pontos WHERE id IN (" + lista(ids) + ") ORDER BY id", null)) {
            while (c.moveToNext()) saida.put(new JSONObject(c.getString(0)));
        } catch (JSONException e) {
            throw new IllegalStateException("ponto ilegível na fila");
        }
        return saida;
    }

    synchronized void remover(List<Long> ids) {
        if (ids.isEmpty()) return;
        helper.getWritableDatabase().execSQL("DELETE FROM pontos WHERE id IN (" + lista(ids) + ")");
    }

    synchronized int tamanho() {
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pontos", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized void limpar() {
        helper.getWritableDatabase().delete("pontos", null, null);
    }

    /** Só números (long): montar o IN à mão não abre porta para injeção. */
    private static String lista(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id.longValue());
        }
        return sb.toString();
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context ctx) {
            super(ctx, NOME_DO_BANCO, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE pontos(id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, corpo TEXT NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int de, int para) {
            // versão 1: nada a migrar
        }
    }
}
