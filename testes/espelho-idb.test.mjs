// Prova o espelho e a restauração do IndexedDB das fotos do script de início (spec, seções 6.3 item 4 e 6.4). O que se
// protege aqui: a foto ou o vídeo que o técnico tirou nunca fica só na WebView, a casca nunca manda ao nativo o que a
// transação não gravou, e depois de uma perda a página só abre o banco das fotos com elas já de volta.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { IDBObjectStore } from 'fake-indexeddb';
import {
  criarJanela, criarAparelho, semear, bancos, lerFotos, bytesDe, ate, assentar, RETRATO_EM_USO, LS_EM_USO,
  BANCO_FOTOS, STORE_FOTOS,
} from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, fnv1a32, CHAVE_FILA, BANCO_SENTINELA } = require('../www/casca/inicio.js');

const PEDACO = 524288;
const MSG_TRAVA = 'conferência ignorada: já parou nesta sessão';

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
  const rascunho = { _ts: 1, r: { data: '2026-10-17', itens: [1, 2] } };
  store.put('data:image/jpeg;base64,AAAA', 'f_1');
  store.put(rascunho, 'rdraft:u_2026-10-17');
  rascunho.r.data = 'mudou depois do put'; // o banco guardou a cópia do momento do put, e o espelho também
  const daPagina = () => {};
  tx.oncomplete = daPagina;
  await assentar(0);
  assert.deepEqual(j.enviados, [], 'nada antes do complete');

  await new Promise((r) => tx.addEventListener('complete', r));
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

test('restauração incompleta não cria o banco sentinela, e a carga seguinte pede de novo', async () => {
  const aparelho = criarAparelho();
  const j = montar({ idb: aparelho });
  const pagina = abrirComoAPagina(j.win);
  await ate(() => j.enviados.length > 0, 'idbPedir');
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbPedaco', k: 'v_1', i: 0, n: 2, mime: 'video/mp4', b64: b64(bytesDeTeste(10)) }); // falta o i=1
  j.responder({ t: 'idbItem', k: 'f_2', tipo: 'json', v: '{quebrado' });
  j.responder({ t: 'idbFim', n: 3 });
  await pagina.promessa;
  await j.casca.pronto;
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS], 'sem o banco sentinela');
  const aviso = j.enviados.find((m) => m.t === 'erro');
  assert.ok(aviso, 'avisa');
  assert.ok(!/f_1|f_2|v_1/.test(aviso.msg), 'sem nome de chave');
  assert.equal((await lerFotos(aparelho)).f_1, 'a', 'o que gravou fica');

  // Sem o banco sentinela a reconciliação não roda (ela apagaria no nativo o que não voltou): a carga seguinte restaura.
  const k = recarregar(j, { retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1', 'f_2', 'v_1'] } });
  await ate(() => k.enviados.length > 0, 'idbPedir de novo');
  assert.deepEqual(k.enviados, [{ t: 'idbPedir' }]);
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

  // Uma vez por sessão: o idbChaves do retrato leve é da partida, e numa recarga ele já envelheceu.
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

test('o banco sentinela sem o banco das fotos, com chaves no nativo: restaura em vez de apagar no nativo', async () => {
  // Nenhuma faxina da página apaga o banco das fotos inteiro: ele sumir é perda, e a reconciliação mandaria idbDel de
  // todas as fotos que o nativo guarda.
  const aparelho = await aparelhoEmUso();
  const j = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1'] } });
  const pagina = abrirComoAPagina(j.win);
  await ate(() => j.enviados.length > 0, 'idbPedir');
  assert.deepEqual(j.enviados, [{ t: 'idbPedir' }]);
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbFim', n: 1 });
  assert.equal(await idbGet(await pagina.promessa, 'f_1'), 'a');
  assert.deepEqual(j.enviados, [{ t: 'idbPedir' }], 'nenhum idbDel');
});

test('sem resposta do nativo, pronto resolve em 15 s e o open segue', async () => {
  const aparelho = criarAparelho();
  const j = montar({ idb: aparelho });
  const pagina = abrirComoAPagina(j.win);
  let resolveu = false;
  j.casca.pronto.then(() => { resolveu = true; });
  await ate(() => j.enviados.length > 0, 'idbPedir');
  j.avancar(14999);
  await assentar();
  assert.equal(resolveu, false);
  assert.equal(pagina.abriu(), false);

  j.avancar(1);
  await ate(() => resolveu, 'pronto');
  const db = await pagina.promessa;
  const aviso = j.enviados.find((m) => m.t === 'erro');
  assert.ok(aviso && aviso.onde, 'avisa o nativo');
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS], 'sem o banco sentinela: a restauração não terminou');

  // O nativo lento termina depois: o item ainda entra e o banco sentinela é criado.
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbFim', n: 1 });
  await ate(async () => (await bancos(aparelho)).includes(BANCO_SENTINELA), 'banco sentinela');
  assert.equal(await idbGet(db, 'f_1'), 'a');
  assert.ok(!j.enviados.some((m) => m.t === 'idbPut'), 'sem eco');
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

test('espelho desligado: nada do IndexedDB vai ao nativo e a reconciliação não roda', async () => {
  // A segunda perda da sessão (a trava não deixa parar de novo): a carga segue sem espelho nenhum.
  const proto = IDBObjectStore.prototype;
  const aparelho = await aparelhoEmUso({ f_1: 'a', f_2: 'b' });
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

  // A página roda com o localStorage vazio: a faxina de órfãos apaga as fotos, e ela grava outras.
  const db = await abrirComoAPagina(j.win).promessa;
  await idbDel(db, 'f_1');
  await idbPut(db, 'f_3', 'c');
  await assentar();
  assert.deepEqual(j.enviados, [{ t: 'erro', onde: 'perda', msg: MSG_TRAVA }], 'nem reconciliação, nem put, nem delete');

  // O que a página fez sem espelho não pode virar a verdade: a reconciliação mandaria apagar no nativo a f_1. O banco
  // sentinela sai, e a próxima carga com espelho restaura do nativo.
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS]);
  const depois = montar({ idb: aparelho, retrato: { ...RETRATO_EM_USO, idbChaves: ['f_1', 'f_2'] } });
  const pagina = abrirComoAPagina(depois.win);
  await ate(() => depois.enviados.length > 0, 'idbPedir');
  assert.deepEqual(depois.enviados, [{ t: 'idbPedir' }]);
  depois.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  depois.responder({ t: 'idbItem', k: 'f_2', tipo: 'texto', v: 'b' });
  depois.responder({ t: 'idbFim', n: 2 });
  const db2 = await pagina.promessa;
  assert.equal(await idbGet(db2, 'f_1'), 'a', 'a foto que a página apagou sem espelho voltou');
  assert.equal(await idbGet(db2, 'f_3'), 'c', 'e a que ela gravou ficou');
  assert.deepEqual(depois.enviados, [{ t: 'idbPedir' }]);
});

test('espelho desligado e sem o banco sentinela: restaura (ler não escreve no nativo), mas não cria o banco sentinela', async () => {
  const aparelho = criarAparelho();
  const j = montar({ idb: aparelho, ls: {}, sessao: { gc_casca_parou: 'perda' } });
  const pagina = abrirComoAPagina(j.win);
  await ate(() => j.enviados.length > 1, 'idbPedir');
  assert.deepEqual(j.enviados, [{ t: 'erro', onde: 'perda', msg: MSG_TRAVA }, { t: 'idbPedir' }]);
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'a' });
  j.responder({ t: 'idbFim', n: 1 });
  const db = await pagina.promessa;
  assert.equal(await idbGet(db, 'f_1'), 'a');
  await idbPut(db, 'f_2', 'b');
  await assentar();
  assert.equal(j.enviados.length, 2, 'sem eco e sem espelho');
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS]);
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

  // Se o stop lançou, a página roda até o nativo recarregar, sem espelho: é uma carga sem espelho como as outras, e o
  // banco sentinela sai (a faxina de órfãos dela não pode virar idbDel na próxima reconciliação).
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
  assert.deepEqual(await bancos(aparelho), [BANCO_FOTOS]);
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
