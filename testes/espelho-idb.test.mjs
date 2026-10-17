// Prova o espelho e a restauração do IndexedDB das fotos do script de início (spec, seções 6.3 item 4 e 6.4). O que se
// protege aqui: a foto ou o vídeo que o técnico tirou nunca fica só na WebView, a casca nunca manda ao nativo o que a
// transação não gravou, e depois de uma perda a página só abre o banco das fotos com elas já de volta.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { IDBObjectStore } from 'fake-indexeddb';
import {
  criarJanela, criarAparelho, semear, bancos, lerFotos, lerCasca, bytesDe, ate, assentar, RETRATO_EM_USO, LS_EM_USO,
  BANCO_FOTOS, STORE_FOTOS,
} from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, fnv1a32, CHAVE_FILA, MARCA_PERDA, BANCO_SENTINELA, STORE_ESTADO } = require('../www/casca/inicio.js');

const PEDACO = 524288;
const TETO_MS = 15000;
const MSG_TRAVA = 'conferência ignorada: já parou nesta sessão';
const SESSAO_IDB = 'gc_casca_idb_reconciliado';

// Janela falsa + script instalado. `antes` roda ANTES do script.
function montar(opcoes = {}, antes = () => {}) {
  const j = criarJanela(opcoes);
  antes(j);
  const casca = instalar(j.win, j.canal, j.retrato);
  return { ...j, casca, ls: j.win.localStorage };
}

function copiar(s) {
  const c = {};
  try { for (let i = 0; i < s.length; i++) c[s.key(i)] = s.getItem(s.key(i)); } catch (_) { /* sem storage */ }
  return c;
}

// A carga seguinte na mesma WebView: o localStorage, o sessionStorage e o IndexedDB continuam os da anterior.
function recarregar(anterior, opcoes = {}) {
  return montar({
    idb: anterior.idb, ls: copiar(anterior.win.localStorage), sessao: copiar(anterior.win.sessionStorage),
    retrato: anterior.retrato, ...opcoes,
  });
}

// Um aparelho em uso: a casca já criou o banco sentinela dela. `fotos` é o que o banco das fotos já tem.
async function aparelhoEmUso(fotos = null) {
  const aparelho = criarAparelho();
  await semear(aparelho, { casca: true, fotos });
  return aparelho;
}

// O `idb()` do campo.html: abre o banco das fotos na versão 1 e cria a store no upgrade. `abriu()` diz se o onsuccess
// da página já rodou.
function abrirComoAPagina(win) {
  let abriu = false;
  const promessa = new Promise((res, rej) => {
    const r = win.indexedDB.open(BANCO_FOTOS, 1);
    r.onupgradeneeded = () => { const db = r.result; if (!db.objectStoreNames.contains(STORE_FOTOS)) db.createObjectStore(STORE_FOTOS); };
    r.onsuccess = () => { abriu = true; res(r.result); };
    r.onerror = () => rej(r.error);
  });
  return { promessa, abriu: () => abriu };
}

// O idbPut, o idbGet e o idbDel do campo.html (cada um na sua transação, com o oncomplete da página).
function idbPut(db, key, val) {
  return new Promise((res, rej) => {
    const tx = db.transaction(STORE_FOTOS, 'readwrite');
    tx.objectStore(STORE_FOTOS).put(val, key);
    tx.oncomplete = () => res(true); tx.onerror = () => rej(tx.error); tx.onabort = () => rej(tx.error);
  });
}
function idbGet(db, key) {
  return new Promise((res, rej) => {
    const rq = db.transaction(STORE_FOTOS, 'readonly').objectStore(STORE_FOTOS).get(key);
    rq.onsuccess = () => res(rq.result); rq.onerror = () => rej(rq.error);
  });
}
function idbDel(db, key) {
  return new Promise((res, rej) => {
    const tx = db.transaction(STORE_FOTOS, 'readwrite');
    tx.objectStore(STORE_FOTOS).delete(key);
    tx.oncomplete = () => res(true); tx.onerror = () => rej(tx.error);
  });
}

// Bytes que não se repetem a cada pedaço (um erro de ordem ou de corte aparece na comparação).
function bytesDeTeste(n) {
  const b = new Uint8Array(n);
  for (let i = 0; i < n; i++) b[i] = (i * 31 + (i >> 9) * 7 + 3) & 255;
  return b;
}
// Um Blob cujos pedaços demoram `ms` para ler (o espelho lê o objeto que a página passou ao put).
function blobLento(partes, opcoes, ms) {
  const blob = new Blob(partes, opcoes);
  const slice = blob.slice.bind(blob);
  blob.slice = (...args) => {
    const pedaco = slice(...args);
    const ler = pedaco.arrayBuffer.bind(pedaco);
    pedaco.arrayBuffer = () => new Promise((r) => setTimeout(() => r(ler()), ms));
    return pedaco;
  };
  return blob;
}
const b64 = (bytes) => Buffer.from(bytes).toString('base64');
const deB64 = (texto) => new Uint8Array(Buffer.from(texto, 'base64'));
const doIdb = (enviados) => enviados.filter((m) => /^idb/.test(m.t));

test('put de texto espelha só depois do complete', async () => {
  const j = montar({ idb: await aparelhoEmUso() });
  await j.casca.pronto;
  const db = await abrirComoAPagina(j.win).promessa;
  assert.deepEqual(j.enviados, []);

  const tx = db.transaction(STORE_FOTOS, 'readwrite');
  const store = tx.objectStore(STORE_FOTOS);
  let completou = false;
  const completa = new Promise((r) => tx.addEventListener('complete', () => { completou = true; r(); }));
  const rascunho = { _ts: 1, r: { data: '2026-10-17', itens: [1, 2] } };
  store.put('data:image/jpeg;base64,AAAA', 'f_1');
  store.put(rascunho, 'rdraft:u_2026-10-17');
  rascunho.r.data = 'mudou depois do put'; // o banco guardou a cópia do momento do put, e o espelho também
  const daPagina = () => {};
  tx.oncomplete = daPagina;
  // A transação fica viva (um pedido puxa o outro) enquanto o laço de eventos anda 200 voltas: o put já gravou, e o
  // espelho que mandasse antes do complete teria todo esse tempo para mandar.
  // O finally solta a transação mesmo com a asserção falhando: senão o laço de pedidos segura o processo do teste.
  let viva = true;
  const manter = () => { if (viva) store.get('nada').onsuccess = manter; };
  manter();
  try {
    await assentar();
    assert.equal(completou, false, 'a transação ainda está viva');
    assert.deepEqual(j.enviados, [], 'nada antes do complete');
  } finally {
    viva = false;
  }

  await completa;
  await ate(() => j.enviados.length === 2, 'os dois idbPut');
  assert.deepEqual(j.enviados, [
    { t: 'idbPut', k: 'f_1', tipo: 'texto', v: 'data:image/jpeg;base64,AAAA' },
    { t: 'idbPut', k: 'rdraft:u_2026-10-17', tipo: 'json', v: JSON.stringify({ _ts: 1, r: { data: '2026-10-17', itens: [1, 2] } }) },
  ]);
  assert.equal(tx.oncomplete, daPagina, 'o oncomplete da página fica como ela o pôs');

  // Na mesma transação a última operação de cada chave vence.
  j.enviados.length = 0;
  const tx2 = db.transaction(STORE_FOTOS, 'readwrite');
  tx2.objectStore(STORE_FOTOS).put('um', 'f_2');
  tx2.objectStore(STORE_FOTOS).put('dois', 'f_2');
  tx2.objectStore(STORE_FOTOS).put('x', 'f_3');
  tx2.objectStore(STORE_FOTOS).delete('f_3');
  await new Promise((r) => tx2.addEventListener('complete', r));
  await ate(() => j.enviados.length === 2, 'f_2 e f_3');
  assert.deepEqual(j.enviados, [{ t: 'idbPut', k: 'f_2', tipo: 'texto', v: 'dois' }, { t: 'idbDel', k: 'f_3' }]);
});

test('put abortado não espelha', async () => {
  const j = montar({ idb: await aparelhoEmUso() });
  await j.casca.pronto;
  const db = await abrirComoAPagina(j.win).promessa;

  const tx = db.transaction(STORE_FOTOS, 'readwrite');
  tx.objectStore(STORE_FOTOS).put('x', 'f_9');
  tx.objectStore(STORE_FOTOS).delete('f_8');
  const abortou = new Promise((r) => tx.addEventListener('abort', r));
  tx.abort();
  await abortou;
  await assentar();
  assert.deepEqual(j.enviados, []);
  assert.equal(await idbGet(db, 'f_9'), undefined);

  // A exceção do put original (aqui, sem chave numa store sem keyPath) sobe intacta, e nada é espelhado.
  const tx2 = db.transaction(STORE_FOTOS, 'readwrite');
  assert.throws(() => tx2.objectStore(STORE_FOTOS).put('sem chave'), (e) => e.name === 'DataError');
  await new Promise((r) => tx2.addEventListener('complete', r));
  await assentar();
  assert.deepEqual(j.enviados, []);
});

test('put de Blob de 1,2 MB vai em 3 pedaços com o mesmo id', async () => {
  const j = montar({ idb: await aparelhoEmUso() });
  await j.casca.pronto;
  const db = await abrirComoAPagina(j.win).promessa;

  const bytes = bytesDeTeste(Math.round(1.2 * 1024 * 1024));
  await idbPut(db, 'v_1', new Blob([bytes], { type: 'video/mp4' }));
  await ate(() => j.enviados.length === 3, '3 pedaços');
  const ps = [...j.enviados];
  assert.deepEqual(ps.map((p) => [p.t, p.k, p.i, p.n, p.mime]), [
    ['idbPedaco', 'v_1', 0, 3, 'video/mp4'], ['idbPedaco', 'v_1', 1, 3, 'video/mp4'], ['idbPedaco', 'v_1', 2, 3, 'video/mp4'],
  ]);
  assert.equal(new Set(ps.map((p) => p.id)).size, 1, 'o mesmo id nos 3');
  assert.match(ps[0].id, /^[a-z0-9-]+$/, 'o id vira nome de arquivo no nativo');
  assert.deepEqual(ps.map((p) => deB64(p.b64).length), [PEDACO, PEDACO, bytes.length - 2 * PEDACO]);
  assert.deepEqual(Buffer.concat(ps.map((p) => deB64(p.b64))), Buffer.from(bytes), 'os bytes chegam inteiros e em ordem');

  // Outra gravação, outro id; o File é um Blob; e o vazio vai num pedaço só (o nativo fecha a entrada no i === n-1).
  j.enviados.length = 0;
  await idbPut(db, 'f_2', new File([bytesDeTeste(10)], 'foto.jpg', { type: 'image/jpeg' }));
  await idbPut(db, 'f_3', new Blob([]));
  await ate(() => j.enviados.length === 2, 'f_2 e f_3');
  assert.deepEqual(j.enviados.map((p) => [p.k, p.i, p.n, p.mime, p.b64]), [
    ['f_2', 0, 1, 'image/jpeg', b64(bytesDeTeste(10))], ['f_3', 0, 1, '', ''],
  ]);
  assert.notEqual(j.enviados[0].id, ps[0].id);
  assert.notEqual(j.enviados[0].id, j.enviados[1].id);
});

test('delete espelha idbDel', async () => {
  // o nativo já tem as duas (senão a reconciliação as reenviaria)
  const j = montar({ idb: await aparelhoEmUso({ f_1: 'x', v_1: 'y' }), retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1', 'v_1'] } });
  await j.casca.pronto;
  await assentar();
  assert.deepEqual(j.enviados, []);
  const db = await abrirComoAPagina(j.win).promessa;
  await idbDel(db, 'f_1');
  await ate(() => j.enviados.length === 1, 'idbDel');
  assert.deepEqual(j.enviados, [{ t: 'idbDel', k: 'f_1' }]);
  assert.equal(await idbGet(db, 'f_1'), undefined);

  // Um vídeo gravado e logo apagado: o idbDel só sai depois do último pedaço (senão o nativo o ressuscitaria). No
  // aparelho, ler 512 KB do Blob demora mais que a transação do delete; aqui cada pedaço demora 30 ms para mostrar isso.
  j.enviados.length = 0;
  await idbPut(db, 'v_2', blobLento([bytesDeTeste(PEDACO + 10)], { type: 'video/mp4' }, 30));
  await idbDel(db, 'v_2');
  await ate(() => j.enviados.length === 3, 'pedaços e idbDel');
  assert.deepEqual(j.enviados.map((m) => m.t + ':' + m.k + (m.t === 'idbPedaco' ? ':' + m.i : '')),
    ['idbPedaco:v_2:0', 'idbPedaco:v_2:1', 'idbDel:v_2']);
});

test('outro banco não é espelhado', async () => {
  const j = montar({ idb: await aparelhoEmUso() });
  await j.casca.pronto;

  // outro banco com uma store chamada 'fotos'
  const outro = await new Promise((res, rej) => {
    const r = j.win.indexedDB.open('outro_banco', 1);
    r.onupgradeneeded = () => r.result.createObjectStore(STORE_FOTOS);
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  });
  await idbPut(outro, 'f_1', 'x');
  await idbDel(outro, 'f_1');

  // o banco das fotos com outra store além da 'fotos'
  const db = await new Promise((res, rej) => {
    const r = j.win.indexedDB.open(BANCO_FOTOS, 1);
    r.onupgradeneeded = () => { r.result.createObjectStore(STORE_FOTOS); r.result.createObjectStore('outra'); };
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  });
  await new Promise((res) => {
    const tx = db.transaction('outra', 'readwrite');
    tx.objectStore('outra').put('x', 'f_1');
    tx.objectStore('outra').delete('f_2');
    tx.oncomplete = res;
  });
  await assentar();
  assert.deepEqual(j.enviados, []);

  // e a 'fotos' do mesmo banco é espelhada
  await idbPut(db, 'f_1', 'y');
  await ate(() => j.enviados.length === 1, 'idbPut');
  assert.deepEqual(j.enviados, [{ t: 'idbPut', k: 'f_1', tipo: 'texto', v: 'y' }]);
});

test('get, getAll e getAllKeys ficam intocados; só put e delete são embrulhados', async () => {
  const proto = IDBObjectStore.prototype;
  const j = criarJanela({ idb: await aparelhoEmUso() });
  const antes = { get: proto.get, getAll: proto.getAll, getAllKeys: proto.getAllKeys, put: proto.put, del: proto.delete };
  const open = j.win.indexedDB.open;
  const casca = instalar(j.win, j.canal, j.retrato);
  assert.equal(proto.get, antes.get);
  assert.equal(proto.getAll, antes.getAll);
  assert.equal(proto.getAllKeys, antes.getAllKeys);
  assert.notEqual(proto.put, antes.put);
  assert.notEqual(proto.delete, antes.del);
  assert.notEqual(j.win.indexedDB.open, open, 'o open da janela segura o banco das fotos até o pronto');
  await casca.pronto;
});

test('chave que não é texto não vai ao nativo; o aviso não leva chave nem valor', async () => {
  const j = montar({ idb: await aparelhoEmUso() });
  await j.casca.pronto;
  const db = await abrirComoAPagina(j.win).promessa;
  await idbPut(db, 7, 'valor-do-tecnico');
  await ate(() => j.enviados.length === 1, 'aviso');
  assert.equal(j.enviados[0].t, 'erro');
  assert.ok(!JSON.stringify(j.enviados[0]).includes('valor-do-tecnico'));
  assert.equal(await idbGet(db, 7), 'valor-do-tecnico', 'o banco gravou do mesmo jeito');
});

test('o espelho nunca quebra a página: canal que lança não derruba o put nem o oncomplete', async () => {
  const j = montar({ idb: await aparelhoEmUso() });
  await j.casca.pronto;
  const db = await abrirComoAPagina(j.win).promessa;
  j.canal.postMessage = () => { throw new Error('canal caiu'); };
  assert.equal(await idbPut(db, 'f_1', 'x'), true);
  assert.equal(await idbPut(db, 'v_1', new Blob([bytesDeTeste(PEDACO * 2 + 1)])), true);
  assert.equal(await idbDel(db, 'f_1'), true);
  await assentar();
  assert.equal(await idbGet(db, 'f_1'), undefined);
});

test('perda do IndexedDB: open da página espera a restauração e acha as fotos', async () => {
  const aparelho = criarAparelho(); // a WebView perdeu tudo: nem o banco sentinela da casca
  const j = montar({ idb: aparelho });
  const pagina = abrirComoAPagina(j.win);
  await ate(() => j.enviados.length > 0, 'idbPedir');
  assert.deepEqual(j.enviados, [{ t: 'idbPedir' }]);
  await assentar();
  assert.equal(pagina.abriu(), false, 'a página espera');

  // outro banco passa direto, mesmo durante a espera
  const outro = await new Promise((res, rej) => {
    const r = j.win.indexedDB.open('outro_banco');
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  });
  outro.close();

  const video = bytesDeTeste(PEDACO + 1000);
  const rascunho = { _ts: 5, r: { data: '2026-10-17' } };
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'data:image/jpeg;base64,AAAA' });
  j.responder({ t: 'idbItem', k: 'rdraft:u_2026-10-17', tipo: 'json', v: JSON.stringify(rascunho) });
  j.responder({ t: 'idbPedaco', k: 'v_1', i: 0, n: 2, mime: 'video/mp4', b64: b64(video.subarray(0, PEDACO)) });
  j.responder({ t: 'idbPedaco', k: 'v_1', i: 1, n: 2, mime: 'video/mp4', b64: b64(video.subarray(PEDACO)) });
  await assentar();
  assert.equal(pagina.abriu(), false, 'ainda espera o idbFim');
  j.responder({ t: 'idbFim', n: 3 });

  const db = await pagina.promessa;
  assert.equal(await idbGet(db, 'f_1'), 'data:image/jpeg;base64,AAAA');
  assert.deepEqual(await idbGet(db, 'rdraft:u_2026-10-17'), rascunho);
  const v = await idbGet(db, 'v_1');
  assert.ok(v instanceof Blob);
  assert.equal(v.type, 'video/mp4');
  assert.deepEqual(await bytesDe(v), video);
  await j.casca.pronto;
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS, BANCO_SENTINELA, 'outro_banco'].sort(),
    'o banco sentinela volta no fim');
  assert.equal(j.timersAtivos(), 0, 'a espera de 15 s não fica pendurada');
});

test('restauração não ecoa', async () => {
  const aparelho = criarAparelho();
  const j = montar({ idb: aparelho });
  const pagina = abrirComoAPagina(j.win);
  await ate(() => j.enviados.length > 0, 'idbPedir');
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbPedaco', k: 'v_1', i: 0, n: 1, mime: 'video/mp4', b64: b64(bytesDeTeste(100)) });
  j.responder({ t: 'idbFim', n: 2 });
  const db = await pagina.promessa;
  await j.casca.pronto;
  j.casca.descarregar();
  await assentar();
  assert.deepEqual(j.enviados, [{ t: 'idbPedir' }], 'nada do que voltou vai de volta ao nativo');

  // o que a página grava depois é espelhado
  await idbPut(db, 'f_2', 'b');
  await ate(() => j.enviados.length === 2, 'idbPut da página');
  assert.deepEqual(j.enviados[1], { t: 'idbPut', k: 'f_2', tipo: 'texto', v: 'b' });

  // e a carga seguinte da mesma sessão, com o banco sentinela de volta, não pede de novo
  const k = recarregar(j, { retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1', 'v_1', 'f_2'] } });
  await k.casca.pronto;
  await assentar();
  assert.deepEqual(k.enviados, []);
});

test('restauração incompleta cria o banco sentinela com as pendentes, e a carga seguinte não pede de novo nem as apaga no nativo', async () => {
  // O que não volta: um vídeo sem o último pedaço, um JSON quebrado, uma gravação que o disco cheio recusa e uma chave
  // que o nativo anunciou no retrato e não mandou.
  const aparelho = criarAparelho();
  const retrato = { ...RETRATO_EM_USO, idbChaves: ['f_1', 'v_1', 'f_2', 'v_cheio', 'f_4'] };
  const j = montar({ idb: aparelho, retrato }, () => {
    const put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function (valor, chave) {
      if (chave === 'v_cheio' && this.name === STORE_FOTOS) throw new DOMException('disco cheio', 'QuotaExceededError');
      return put.apply(this, arguments);
    };
  });
  const pagina = abrirComoAPagina(j.win);
  await ate(() => j.enviados.length > 0, 'idbPedir');
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbPedaco', k: 'v_1', i: 0, n: 2, mime: 'video/mp4', b64: b64(bytesDeTeste(10)) }); // falta o i=1
  j.responder({ t: 'idbItem', k: 'f_2', tipo: 'json', v: '{quebrado' });
  j.responder({ t: 'idbPedaco', k: 'v_cheio', i: 0, n: 1, mime: 'video/mp4', b64: b64(bytesDeTeste(10)) });
  j.responder({ t: 'idbFim', n: 4 }); // as 4 chaves que ele mandou: nenhuma sumiu no caminho
  await pagina.promessa;
  await j.casca.pronto;
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS, BANCO_SENTINELA], 'o banco sentinela nasce mesmo assim');
  assert.deepEqual(await lerCasca(aparelho), { pendentes: ['f_2', 'f_4', 'v_1', 'v_cheio'], incompleta: false });
  const aviso = j.enviados.find((m) => m.t === 'erro');
  assert.ok(aviso, 'avisa');
  assert.ok(!/f_1|f_2|f_4|v_1|v_cheio/.test(aviso.msg), 'sem nome de chave');
  assert.deepEqual(await lerFotos(aparelho), { f_1: 'a' }, 'o que gravou fica');
  assert.equal(j.timersAtivos(), 0, 'o teto de 15 s não fica pendurado');

  // Partida nova (o idbChaves é o de agora): sem pedir tudo de novo, a reconciliação roda, e as pendentes não viram
  // idbDel no nativo, que ainda as tem. A f_8, que o nativo tem e não é pendente, a WebView apagou: essa vai.
  const k = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: [...retrato.idbChaves, 'f_8'] } });
  await k.casca.pronto;
  await assentar();
  assert.deepEqual(k.enviados, [{ t: 'idbDel', k: 'f_8' }]);
});

test('restauração com o n que não bate marca o banco sentinela como incompleto, e a reconciliação não apaga nada no nativo', async () => {
  // Uma entrada sumiu no caminho (o nativo diz 3, chegaram 2, uma delas sem chave): não há como saber qual chave falta.
  const aparelho = criarAparelho();
  const j = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1'] } });
  const pagina = abrirComoAPagina(j.win);
  await ate(() => j.enviados.length > 0, 'idbPedir');
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbItem', tipo: 'texto', v: 'sem chave' });
  j.responder({ t: 'idbFim', n: 3 });
  await pagina.promessa;
  await j.casca.pronto;
  assert.deepEqual(await lerCasca(aparelho), { pendentes: [], incompleta: true }, 'grava o que deu e marca');
  assert.equal((await lerFotos(aparelho)).f_1, 'a');

  // Na partida seguinte a WebView tem a f_1 e o nativo diz ter a f_7 (a que não chegou, talvez): reenvia o que falta
  // lá, mas não manda apagar nada.
  const k = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_7'] } });
  await k.casca.pronto;
  await ate(() => k.enviados.length === 1, 'reenvio');
  await assentar();
  assert.deepEqual(k.enviados, [{ t: 'idbPut', k: 'f_1', tipo: 'texto', v: 'a' }]);
});

// Espia a marca 'incompleta' do banco sentinela (instalado no `antes`, por baixo do script): `marcada()` diz se a
// transação que a gravou já terminou. Na criação do banco ela vem na transação do upgrade, que também termina.
function espiarMarca() {
  let marcada = false;
  const put = IDBObjectStore.prototype.put;
  IDBObjectStore.prototype.put = function (valor) {
    const r = put.apply(this, arguments);
    if (this.name === STORE_ESTADO && valor === 'incompleta') {
      this.transaction.addEventListener('complete', () => { marcada = true; });
    }
    return r;
  };
  return () => marcada;
}

test('restauração interrompida antes do idbFim não deixa a partida seguinte mandar idbDel', async () => {
  // A 1ª carga pede ao nativo, grava uma parte e morre antes do idbFim e do teto (o técnico fechou o app na espera, o
  // renderer morreu num vídeo grande), ou nem consegue pedir (o canal caiu). A 2ª partida, leve, reconcilia: o nativo
  // ainda diz ter a f_2 (e a f_1, no pedido que nem saiu), que a WebView não tem porque não voltou. Com o banco
  // sentinela no estado velho, completo, a reconciliação mandaria idbDel delas, e o nativo perderia a única cópia.
  const retratoLeve = { ...RETRATO_EM_USO, idbChaves: ['f_1', 'f_2'] };
  const retratoDaPerda = { ...retratoLeve, modo: 'restaurar', motivo: 'perda', id: 'r1', ls: {}, fila: '[]' };
  const daPerda = { ls: { [MARCA_PERDA]: '1' }, retrato: retratoDaPerda };
  const reenvioF5 = [{ t: 'idbPut', k: 'f_5', tipo: 'texto', v: 'só na WebView' }];
  const casos = {
    'restauração da perda com o banco sentinela de pé': {
      semente: { casca: true, fotos: { f_5: 'só na WebView' } }, opcoes: daPerda, segunda: reenvioF5,
    },
    'store das fotos vazia com o banco sentinela de pé': {
      semente: { casca: true, fotos: {} }, opcoes: { retrato: retratoLeve }, segunda: [],
    },
    'sem o banco sentinela (ele nasce já marcado)': {
      semente: { fotos: { f_5: 'só na WebView' } }, opcoes: { retrato: retratoLeve }, segunda: reenvioF5,
    },
    'o idbPedir que nem sai': {
      semente: { casca: true, fotos: { f_5: 'só na WebView' } }, opcoes: daPerda, segunda: reenvioF5, canalCai: true,
    },
  };
  for (const [nome, caso] of Object.entries(casos)) {
    const aparelho = criarAparelho();
    await semear(aparelho, caso.semente);
    const noPedido = []; // a marca já estava gravada quando o idbPedir saiu?
    const j = montar({ idb: aparelho, ...caso.opcoes }, ({ canal }) => {
      const marcada = espiarMarca();
      const post = canal.postMessage;
      canal.postMessage = function (texto) {
        if (JSON.parse(texto).t === 'idbPedir') {
          noPedido.push(marcada());
          if (caso.canalCai) throw new Error('canal caiu');
        }
        return post.call(this, texto);
      };
    });
    if (caso.canalCai) {
      await j.casca.pronto;
      assert.ok(j.enviados.some((m) => m.t === 'erro' && m.onde === 'idbPedir'), 'o pedido não saiu: ' + nome);
    } else {
      await ate(() => j.enviados.some((m) => m.t === 'idbPedir'), 'idbPedir: ' + nome);
      j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
      await ate(async () => ((await lerFotos(aparelho)) || {}).f_1 === 'a', 'f_1 gravada: ' + nome);
    }
    assert.deepEqual(noPedido, [true], 'a marca entra antes do idbPedir: ' + nome);
    assert.deepEqual(await lerCasca(aparelho), { pendentes: [], incompleta: true }, nome);
    // A carga morre aqui: nem idbFim, nem o teto (o relógio dela não anda mais).

    const k = montar({ idb: aparelho, retrato: retratoLeve });
    await ate(() => k.win.sessionStorage.getItem(SESSAO_IDB) !== null ||
      k.enviados.some((m) => m.t === 'idbDel' || m.t === 'idbPedir'), 'reconciliação: ' + nome);
    await assentar();
    assert.deepEqual(k.enviados, caso.segunda, 'nenhum idbDel nem novo pedido: ' + nome);
    await k.casca.pronto;
  }
});

test('tudo falhou na restauração: a carga seguinte não pede de novo', async () => {
  // O disco cheio recusa todas as gravações: o banco sentinela nasce com todas as chaves pendentes e a store das fotos
  // fica vazia. Tomar isso por perda pediria tudo de novo a cada carga, seguraria a página até 15 s e traria o que o
  // disco não aceita: o laço que as pendentes existem para evitar.
  const aparelho = criarAparelho();
  const retrato = { ...RETRATO_EM_USO, idbChaves: ['f_1', 'v_1'] };
  const j = montar({ idb: aparelho, retrato }, () => {
    const put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function () {
      if (this.name === STORE_FOTOS) throw new DOMException('disco cheio', 'QuotaExceededError');
      return put.apply(this, arguments);
    };
  });
  await ate(() => j.enviados.some((m) => m.t === 'idbPedir'), 'idbPedir');
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbPedaco', k: 'v_1', i: 0, n: 1, mime: 'video/mp4', b64: b64(bytesDeTeste(10)) });
  j.responder({ t: 'idbFim', n: 2 });
  await j.casca.pronto;
  assert.deepEqual(await lerCasca(aparelho), { pendentes: ['f_1', 'v_1'], incompleta: false });
  assert.deepEqual(await lerFotos(aparelho), {}, 'nada entrou');

  // A partida seguinte: nem idbPedir, nem idbDel; um aviso sem nome de chave, e a página abre sem esperar.
  const k = montar({ idb: aparelho, retrato });
  const pagina = abrirComoAPagina(k.win);
  await ate(() => k.enviados.length > 0, 'a decisão');
  await assentar();
  assert.deepEqual(k.enviados.map((m) => m.t + ':' + (m.onde || '')), ['erro:idb']);
  assert.ok(!/f_1|v_1/.test(k.enviados[0].msg), 'sem nome de chave');
  await k.casca.pronto;
  await pagina.promessa;
  assert.equal(k.timersAtivos(), 0, 'o teto de 15 s não fica pendurado');

  // Uma recarga da mesma sessão não avisa de novo.
  const r = recarregar(k);
  await r.casca.pronto;
  await assentar();
  assert.deepEqual(r.enviados, []);

  // Uma chave do nativo que não é pendente (gravada nele depois) muda o caso: perda só do IndexedDB, restaura.
  const p = montar({ idb: aparelho, retrato: { ...retrato, idbChaves: ['f_1', 'v_1', 'f_9'] } });
  await ate(() => p.enviados.some((m) => m.t === 'idbPedir'), 'idbPedir com a f_9');
  p.responder({ t: 'idbFim', n: 0 });
  await p.casca.pronto;
});

test('reconciliação reenvia chave que falta e apaga a que sobrou', async () => {
  const video = bytesDeTeste(300);
  const aparelho = await aparelhoEmUso({ f_1: 'a', v_2: new Blob([video], { type: 'video/mp4' }), f_3: { x: 1 } });
  const retrato = { ...RETRATO_EM_USO, idbChaves: ['f_1', 'f_9'] };
  const j = montar({ idb: aparelho, retrato });
  const pagina = abrirComoAPagina(j.win);
  await j.casca.pronto;
  await ate(() => j.enviados.length === 3, 'reconciliação');
  const porChave = Object.fromEntries(j.enviados.map((m) => [m.k, m]));
  assert.deepEqual(porChave.f_9, { t: 'idbDel', k: 'f_9' });
  assert.deepEqual(porChave.f_3, { t: 'idbPut', k: 'f_3', tipo: 'json', v: '{"x":1}' });
  assert.equal(porChave.v_2.t, 'idbPedaco');
  assert.deepEqual([porChave.v_2.i, porChave.v_2.n, porChave.v_2.mime], [0, 1, 'video/mp4']);
  assert.deepEqual(deB64(porChave.v_2.b64), video);
  assert.ok(!('f_1' in porChave), 'o que o nativo já tem não vai de novo');
  await pagina.promessa;
  const tudo = await lerFotos(aparelho);
  assert.deepEqual(Object.keys(tudo).sort(), ['f_1', 'f_3', 'v_2'], 'a reconciliação não escreve na página');
  assert.deepEqual(await bytesDe(tudo.v_2), video);

  // Uma vez por sessão: o idbChaves do retrato leve é da partida, e numa recarga ele já envelheceu. A marca entra
  // depois do último reenvio (uma recarga no meio deles reconcilia de novo).
  await ate(() => j.win.sessionStorage.getItem(SESSAO_IDB) !== null, 'marca da sessão');
  const k = recarregar(j);
  await k.casca.pronto;
  await assentar();
  assert.deepEqual(k.enviados, []);

  // Numa partida nova (sessionStorage vazio), de novo.
  const p = montar({ idb: aparelho, retrato });
  await p.casca.pronto;
  await ate(() => p.enviados.length === 3, 'reconciliação na partida nova');

  // Sem sessionStorage, em toda carga.
  const s1 = montar({ idb: aparelho, retrato }, ({ win }) => { delete win.sessionStorage; });
  await s1.casca.pronto;
  await ate(() => s1.enviados.length === 3, 'sem sessionStorage 1');
  const s2 = montar({ idb: aparelho, retrato }, ({ win }) => { delete win.sessionStorage; });
  await s2.casca.pronto;
  await ate(() => s2.enviados.length === 3, 'sem sessionStorage 2');
});

test('reconciliação com a store das fotos vazia e chaves no nativo: perda só do IndexedDB, restaura em vez de apagar no nativo', async () => {
  // Nenhuma faxina da página esvazia o banco das fotos inteiro: a WebView sem nada com o nativo cheio é perda, e
  // reconciliar mandaria idbDel de todas as fotos que o nativo guarda.
  const casos = { 'sem o banco das fotos': await aparelhoEmUso(), 'com a store vazia': await aparelhoEmUso({}) };
  for (const [nome, aparelho] of Object.entries(casos)) {
    const j = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1'] } });
    const pagina = abrirComoAPagina(j.win);
    await ate(() => j.enviados.length > 0, 'idbPedir: ' + nome);
    assert.deepEqual(j.enviados, [{ t: 'idbPedir' }], nome);
    j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
    j.responder({ t: 'idbFim', n: 1 });
    assert.equal(await idbGet(await pagina.promessa, 'f_1'), 'a', nome);
    await j.casca.pronto;
    await assentar();
    assert.deepEqual(j.enviados, [{ t: 'idbPedir' }], 'nenhum idbDel: ' + nome);
    assert.deepEqual(await lerCasca(aparelho), { pendentes: [], incompleta: false }, nome);
  }

  // Com a store vazia e o nativo vazio também, não há o que pedir.
  const vazio = await aparelhoEmUso({});
  const j = montar({ idb: vazio });
  await j.casca.pronto;
  await assentar();
  assert.deepEqual(j.enviados, []);
});

test('sem resposta do nativo, pronto resolve em 15 s, o open segue e o banco sentinela nasce com as pendentes', async () => {
  const aparelho = criarAparelho();
  const j = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1', 'f_2'] } });
  const pagina = abrirComoAPagina(j.win);
  let resolveu = false;
  j.casca.pronto.then(() => { resolveu = true; });
  await ate(() => j.enviados.length > 0, 'idbPedir');
  j.avancar(TETO_MS - 1);
  await assentar();
  assert.equal(resolveu, false);
  assert.equal(pagina.abriu(), false);

  j.avancar(1);
  await ate(() => resolveu, 'pronto');
  const db = await pagina.promessa;
  const aviso = j.enviados.find((m) => m.t === 'erro');
  assert.ok(aviso && aviso.onde, 'avisa o nativo');
  // Sem nova tentativa automática: o banco sentinela nasce agora, com o que não voltou e a marca (o n não chegou). Ele
  // já nasce marcado, antes das pendentes entrarem: em nenhum momento ele diz "completo" sem elas.
  await ate(async () => ((await lerCasca(aparelho)) || {}).pendentes?.length === 2, 'banco sentinela com as pendentes');
  assert.deepEqual(await lerCasca(aparelho), { pendentes: ['f_1', 'f_2'], incompleta: true });

  // O nativo lento termina depois: os itens ainda entram e o estado do banco sentinela é refeito.
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbItem', k: 'f_2', tipo: 'texto', v: 'b' });
  j.responder({ t: 'idbFim', n: 2 });
  await ate(async () => !(await lerCasca(aparelho)).incompleta, 'estado refeito');
  assert.deepEqual(await lerCasca(aparelho), { pendentes: [], incompleta: false });
  assert.equal(await idbGet(db, 'f_1'), 'a');
  assert.ok(!j.enviados.some((m) => m.t === 'idbPut'), 'sem eco');
  assert.equal(j.enviados.filter((m) => m.t === 'idbPedir').length, 1, 'um pedido só');
});

test('depois do prazo, a restauração atrasada pula o que a página gravou ou apagou', async () => {
  const aparelho = criarAparelho();
  const j = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1', 'f_2', 'f_3'] } });
  const pagina = abrirComoAPagina(j.win);
  await ate(() => j.enviados.length > 0, 'idbPedir');
  j.avancar(TETO_MS);
  const db = await pagina.promessa;

  // A página já abriu o banco: grava uma foto e apaga outra (o espelho manda as duas ao nativo).
  await idbPut(db, 'f_1', 'da página');
  await idbPut(db, 'f_2', 'da página');
  await idbDel(db, 'f_2');
  await ate(() => j.enviados.some((m) => m.t === 'idbDel'), 'espelho da página');

  // O que chega atrasado do nativo é a cópia de antes: não passa por cima do que a página fez.
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'do nativo' });
  j.responder({ t: 'idbItem', k: 'f_2', tipo: 'texto', v: 'do nativo' });
  j.responder({ t: 'idbItem', k: 'f_3', tipo: 'texto', v: 'do nativo' });
  j.responder({ t: 'idbFim', n: 3 });
  await ate(async () => (await lerFotos(aparelho)).f_3 === 'do nativo', 'f_3 restaurada');
  await assentar();
  assert.deepEqual(await lerFotos(aparelho), { f_1: 'da página', f_3: 'do nativo' });
  assert.deepEqual(doIdb(j.enviados).map((m) => m.t + ':' + (m.k || '')),
    ['idbPedir:', 'idbPut:f_1', 'idbPut:f_2', 'idbDel:f_2'], 'sem eco do que voltou');
});

test('teto de 15 s: databases() que não responde não segura a página, e a resposta atrasada não decide nada', async () => {
  const aparelho = criarAparelho();
  let responder;
  const j = montar({ idb: aparelho }, ({ win }) => {
    win.indexedDB.databases = () => new Promise((r) => { responder = r; });
  });
  const pagina = abrirComoAPagina(j.win);
  j.avancar(TETO_MS - 1);
  await assentar();
  assert.equal(pagina.abriu(), false);
  j.avancar(1);
  await pagina.promessa;
  await j.casca.pronto;
  assert.deepEqual(j.enviados.map((m) => m.t), ['erro']);

  // Sem o banco sentinela: antes do prazo seria idbPedir. Agora a página já abriu o banco, e nada se decide às cegas.
  responder([]);
  await assentar();
  assert.deepEqual(j.enviados.map((m) => m.t), ['erro']);
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS]);
});

test('teto de 15 s: o banco sentinela que não abre não segura a página, e sem as pendentes nada é apagado no nativo', async () => {
  const aparelho = await aparelhoEmUso({ f_1: 'a' });
  const j = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_9'] } }, ({ win }) => {
    const abrir = win.indexedDB.open;
    win.indexedDB.open = function (nome) { return nome === BANCO_SENTINELA ? {} : abrir.apply(this, arguments); };
  });
  const pagina = abrirComoAPagina(j.win);
  let resolveu = false;
  j.casca.pronto.then(() => { resolveu = true; });
  await assentar();
  assert.equal(resolveu, false);
  assert.equal(pagina.abriu(), false);
  j.avancar(TETO_MS);
  await ate(() => resolveu, 'pronto');
  await pagina.promessa;
  await assentar();
  assert.deepEqual(j.enviados.map((m) => m.t), ['erro'], 'nem idbDel, nem reenvio');
});

test('reconciliação lê e manda uma chave por vez, sem juntar os valores na memória', async () => {
  const proto = IDBObjectStore.prototype;
  const get = proto.get;
  const getAll = proto.getAll;
  const leituras = [];
  let todas = 0;
  try {
    const aparelho = await aparelhoEmUso({
      v_1: new Blob([bytesDeTeste(PEDACO + 1)]), v_2: new Blob([bytesDeTeste(PEDACO + 2)]), v_3: new Blob([bytesDeTeste(PEDACO + 3)]),
    });
    let j;
    j = montar({ idb: aparelho }, () => {
      proto.get = function (k) {
        if (this.name === STORE_FOTOS) leituras.push([k, j.enviados.length]);
        return get.apply(this, arguments);
      };
      proto.getAll = function () {
        if (this.name === STORE_FOTOS) todas++;
        return getAll.apply(this, arguments);
      };
    });
    await j.casca.pronto;
    await ate(() => j.enviados.length === 6, 'os 3 vídeos em 2 pedaços');
    // Cada valor só é lido depois de os pedaços do anterior saírem.
    assert.deepEqual(leituras, [['v_1', 0], ['v_2', 2], ['v_3', 4]]);
    assert.equal(todas, 0, 'nada de getAll na store das fotos');
    await ate(() => j.win.sessionStorage.getItem(SESSAO_IDB) !== null, 'reconciliação marcada na sessão');
  } finally {
    proto.get = get;
    proto.getAll = getAll;
  }
});

test('instalação nova: sem espelho no nativo só cria o banco sentinela', async () => {
  const aparelho = criarAparelho();
  const j = montar({ idb: aparelho, ls: {}, retrato: { temEspelho: false, filaHashes: [], idbChaves: [] } });
  await j.casca.pronto;
  assert.deepEqual(j.enviados, [{ t: 'sentinela' }], 'nada de idbPedir');
  assert.deepEqual(await bancos(aparelho), [BANCO_SENTINELA]);
  // e o espelho está ligado
  const db = await abrirComoAPagina(j.win).promessa;
  await idbPut(db, 'f_1', 'x');
  await ate(() => j.enviados.length === 2, 'idbPut');
  assert.deepEqual(j.enviados[1], { t: 'idbPut', k: 'f_1', tipo: 'texto', v: 'x' });
});

test('espelho desligado não toca no IndexedDB, e a restauração da perda que vem depois devolve tudo', async () => {
  // A segunda perda da sessão (a trava não deixa parar de novo): a carga segue sem espelho nenhum.
  const proto = IDBObjectStore.prototype;
  const aparelho = await aparelhoEmUso({ f_1: 'a', f_2: 'b' });
  await semear(aparelho, { casca: true, pendentes: ['f_7'] });
  const retrato = { ...RETRATO_EM_USO, idbChaves: ['f_1', 'f_2', 'f_9'] };
  const originais = {};
  const j = montar({ idb: aparelho, ls: {}, sessao: { gc_casca_parou: 'perda' }, retrato }, () => {
    originais.put = proto.put; originais.del = proto.delete;
  });
  assert.equal(j.paradas.length, 0);
  assert.deepEqual(j.enviados, [{ t: 'erro', onde: 'perda', msg: MSG_TRAVA }]);
  assert.equal(proto.put, originais.put, 'nada embrulhado nesta carga');
  assert.equal(proto.delete, originais.del);
  await j.casca.pronto;
  assert.equal(j.timersAtivos(), 0, 'nada a esperar: nem o teto de 15 s');

  // A página roda com o localStorage vazio: a faxina de órfãos apaga as fotos, e ela grava outras.
  const db = await abrirComoAPagina(j.win).promessa;
  await idbDel(db, 'f_1');
  await idbPut(db, 'f_3', 'c');
  await assentar();
  assert.deepEqual(j.enviados, [{ t: 'erro', onde: 'perda', msg: MSG_TRAVA }],
    'nem restauração, nem reconciliação, nem put, nem delete');
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS, BANCO_SENTINELA], 'o banco sentinela fica');
  assert.deepEqual(await lerCasca(aparelho), { pendentes: ['f_7'], incompleta: false }, 'e fica como estava');

  // A partida seguinte acha a marca da perda e para a carga, também sem tocar no IndexedDB.
  const p = montar({ idb: aparelho, ls: copiar(j.win.localStorage), retrato });
  assert.equal(p.paradas.length, 1);
  await p.casca.pronto;
  assert.deepEqual(p.enviados, [{ t: 'precisoRetrato', motivo: 'perda' }]);

  // O nativo recarrega com o retrato completo da perda: o localStorage e o IndexedDB voltam da mesma geração, e a foto
  // que a página apagou sem espelho volta (o banco sentinela de pé não impede).
  const r = recarregar(p, {
    retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1', 'f_2'], modo: 'restaurar', motivo: 'perda', id: 'r1', ls: {}, fila: '[]' },
  });
  const pagina = abrirComoAPagina(r.win);
  await ate(() => r.enviados.some((m) => m.t === 'idbPedir'), 'idbPedir');
  r.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  r.responder({ t: 'idbItem', k: 'f_2', tipo: 'texto', v: 'b' });
  r.responder({ t: 'idbFim', n: 2 });
  const db2 = await pagina.promessa;
  assert.equal(await idbGet(db2, 'f_1'), 'a', 'a foto que a página apagou sem espelho voltou');
  assert.equal(await idbGet(db2, 'f_3'), 'c', 'e a que ela gravou ficou');
  await r.casca.pronto;
  assert.deepEqual(r.enviados.map((m) => m.t), ['restaurado', 'idbPedir'], 'sem eco e sem idbDel');
  assert.deepEqual(await lerCasca(aparelho), { pendentes: [], incompleta: false }, 'o estado é o desta restauração');
});

test('espelho desligado sem o banco sentinela: não restaura nem cria o banco sentinela', async () => {
  const aparelho = criarAparelho();
  const j = montar({ idb: aparelho, ls: {}, sessao: { gc_casca_parou: 'perda' }, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1'] } });
  const pagina = abrirComoAPagina(j.win);
  await j.casca.pronto;
  const db = await pagina.promessa; // abre sem esperar o nativo
  await idbPut(db, 'f_2', 'b');
  await assentar();
  assert.deepEqual(j.enviados, [{ t: 'erro', onde: 'perda', msg: MSG_TRAVA }], 'nem idbPedir, nem espelho');
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS]);
});

test('restauração da perda devolve também o IndexedDB inteiro, mesmo com o banco sentinela de pé', async () => {
  for (const motivo of ['perda', undefined]) {
    const rotulo = 'motivo ' + motivo;
    const aparelho = await aparelhoEmUso({ f_1: 'velha', f_5: 'só na WebView' });
    await semear(aparelho, { casca: true, pendentes: ['f_x'], incompleta: true });
    const retrato = {
      ...RETRATO_EM_USO, idbChaves: ['f_1', 'f_2'], modo: 'restaurar', id: 'r1', ls: { gc_fech_draft: 'x' }, fila: '[]',
    };
    if (motivo) retrato.motivo = motivo;
    const j = montar({ idb: aparelho, ls: { [MARCA_PERDA]: '1' }, retrato });
    const pagina = abrirComoAPagina(j.win);
    await ate(() => j.enviados.length === 2, 'idbPedir: ' + rotulo);
    assert.deepEqual(j.enviados.map((m) => m.t), ['restaurado', 'idbPedir'], rotulo);
    await assentar();
    assert.equal(pagina.abriu(), false, 'a página espera as fotos: ' + rotulo);
    j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'nova' });
    j.responder({ t: 'idbItem', k: 'f_2', tipo: 'texto', v: 'dois' });
    j.responder({ t: 'idbFim', n: 2 });
    const db = await pagina.promessa;
    await j.casca.pronto;
    assert.deepEqual(await lerFotos(aparelho), { f_1: 'nova', f_2: 'dois', f_5: 'só na WebView' }, 'nada é apagado: ' + rotulo);
    assert.deepEqual(await lerCasca(aparelho), { pendentes: [], incompleta: false }, 'o estado de antes é trocado: ' + rotulo);
    await assentar();
    assert.deepEqual(j.enviados.map((m) => m.t), ['restaurado', 'idbPedir'], 'sem eco e sem idbDel: ' + rotulo);
    db.close();
  }

  // A restauração só da fila não mexe no IndexedDB: com o banco sentinela de pé, é uma carga como as outras.
  const aparelho = await aparelhoEmUso({ f_1: 'a' });
  const j = montar({
    idb: aparelho,
    retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1'], modo: 'restaurar', motivo: 'fila', id: 'q1', ls: {}, fila: '[]' },
  });
  await j.casca.pronto;
  await assentar();
  assert.deepEqual(j.enviados.map((m) => m.t), ['restaurado']);
});

test('banco sentinela ausente numa carga normal: perda só do IndexedDB, restaura sem apagar o que a WebView tem', async () => {
  const aparelho = criarAparelho();
  await semear(aparelho, { fotos: { f_5: 'só na WebView' } }); // o banco das fotos ficou, o sentinela não
  const j = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1'] } }); // localStorage com a sentinela
  const pagina = abrirComoAPagina(j.win);
  await ate(() => j.enviados.length > 0, 'idbPedir');
  assert.deepEqual(j.enviados, [{ t: 'idbPedir' }]);
  await assentar();
  assert.equal(pagina.abriu(), false);
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbFim', n: 1 });
  await pagina.promessa;
  await j.casca.pronto;
  assert.deepEqual(await lerFotos(aparelho), { f_1: 'a', f_5: 'só na WebView' });
  assert.deepEqual(await lerCasca(aparelho), { pendentes: [], incompleta: false });
  assert.deepEqual(j.enviados, [{ t: 'idbPedir' }]);
});

test('carga parada não confere o IndexedDB e o pronto resolve na hora', async () => {
  const casos = {
    perda: { ls: {} },
    'fila atrasada': { ls: { ...LS_EM_USO, [CHAVE_FILA]: '[1]' }, retrato: { ...RETRATO_EM_USO, filaHashes: [fnv1a32('[2]'), fnv1a32('[1]')] } },
  };
  for (const [nome, opcoes] of Object.entries(casos)) {
    const aparelho = criarAparelho();
    const j = montar({ idb: aparelho, ...opcoes });
    assert.equal(j.paradas.length, 1, nome);
    let resolveu = false;
    j.casca.pronto.then(() => { resolveu = true; });
    await ate(() => resolveu, 'pronto: ' + nome);
    await assentar();
    assert.deepEqual(j.enviados.map((m) => m.t), ['precisoRetrato'], nome);
    assert.deepEqual(await bancos(aparelho), [], 'nada criado: ' + nome);
  }

  // Se o stop lançou, a página roda até o nativo recarregar, sem espelho: é uma carga sem espelho como as outras, e não
  // toca no IndexedDB (a marca da perda faz a carga seguinte restaurar tudo, e a reconciliação não roda com ela).
  const aparelho = await aparelhoEmUso({ f_1: 'a' });
  const j = montar({ idb: aparelho, ls: {}, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1'] } }, ({ win }) => {
    win.stop = () => { throw new Error('stop quebrado'); };
  });
  assert.deepEqual(j.enviados.map((m) => m.t), ['erro', 'precisoRetrato']);
  await j.casca.pronto;
  const db = await abrirComoAPagina(j.win).promessa;
  await idbDel(db, 'f_1');
  await assentar();
  assert.equal(j.enviados.length, 2, 'nada do IndexedDB vai ao nativo');
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS, BANCO_SENTINELA], 'o banco sentinela fica');
});

test('sem indexedDB.databases(): segue sem restaurar, avisa sem chave e não lança', async () => {
  const quebras = {
    ausente: (fabrica) => { fabrica.databases = undefined; },
    lanca: (fabrica) => { fabrica.databases = () => { throw new Error('não implementado'); }; },
    recusa: (fabrica) => { fabrica.databases = () => Promise.reject(new Error('negado')); },
  };
  for (const [nome, quebrar] of Object.entries(quebras)) {
    const aparelho = criarAparelho();
    let j;
    assert.doesNotThrow(() => { j = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1'] } }, ({ win }) => quebrar(win.indexedDB)); }, nome);
    await j.casca.pronto;
    const db = await abrirComoAPagina(j.win).promessa;
    assert.ok(db, nome);
    await assentar();
    assert.deepEqual(j.enviados.map((m) => m.t), ['erro'], nome);
    assert.ok(!/f_1/.test(j.enviados[0].msg), nome);
    // e o espelho segue ligado
    await idbPut(db, 'f_2', 'x');
    await ate(() => j.enviados.length === 2, 'idbPut: ' + nome);
  }
});
