// Apoio dos testes do script de início (www/casca/inicio.js): uma janela falsa, só com o que o script toca.
// Fica sem ".test." no nome de propósito: o glob do `npm test` só pega *.test.mjs, então este arquivo não roda sozinho.
//
// Por que a janela é falsa e não a do jsdom: o jsdom não deixa configurar a cota do localStorage, e a cota estourada
// (QuotaExceededError) é a regra mais delicada do espelho. Aqui a cota é um número. Do jsdom vem só o `document`, onde
// a camada do `window.open` (Task 4) é montada; os eventos dele (visibilitychange) continuam os da janela falsa, que o
// teste dispara com um objeto simples.
//
// `win.stop()` já é espionado desde a Task 2 (a Task 3 prova que a perda para a carga com ele). A Task 3 acrescentou as
// opções `ls` e `sessao` (o que a WebView já tinha antes da carga). A Task 4 acrescentou o IndexedDB (fake-indexeddb),
// que só existe quando o teste passa `idb` (o IndexedDB do aparelho, de `criarAparelho`), e a resposta do nativo pelo
// `canal.onmessage` (`responder`). Nada aqui assume a lista fechada.

import { createRequire } from 'node:module';
import { JSDOM } from 'jsdom';
import { IDBFactory, IDBObjectStore } from 'fake-indexeddb';

const require = createRequire(import.meta.url);
const { SENTINELA_LS, BANCO_SENTINELA } = require('../www/casca/inicio.js');

// O banco e a store das fotos do campo.html (`IDB_NAME`, `IDB_STORE`).
export const BANCO_FOTOS = 'gc_campo_fotos';
export const STORE_FOTOS = 'fotos';

// O put e o delete do fake-indexeddb antes de qualquer script. O prototype é um só no processo inteiro (no navegador,
// um por janela): cada `criarJanela` o devolve a estes originais antes de o script embrulhá-lo, senão o embrulho de
// cada janela já testada ficaria por baixo do da seguinte. O apoio grava e lê o banco por eles, nunca pelo embrulho.
const PUT_ORIGINAL = IDBObjectStore.prototype.put;
const DELETE_ORIGINAL = IDBObjectStore.prototype.delete;

// O localStorage de um aparelho EM USO: a sentinela da casca já está lá e não há fila, então o script de início não tem
// nada a restaurar nem a parar. É o padrão do `criarJanela` porque é o estado em que o espelho (Task 2) e o IndexedDB
// (Task 4) trabalham. Quem prova a partida (instalação nova, perda) passa `ls: {}`, a WebView sem nada.
export const LS_EM_USO = Object.freeze({ [SENTINELA_LS]: '1' });

// O retrato do mesmo aparelho em uso: o nativo já recebeu a `sentinela` (temEspelho) e não guarda versão da fila. Com
// `{}` o script reenviaria a `sentinela` em toda carga (o nativo ainda não a teria marcado).
export const RETRATO_EM_USO = Object.freeze({ temEspelho: true, filaHashes: [], idbChaves: [] });

// Tem a cara de um EventTarget mínimo: o script só usa addEventListener, e o teste dispara com dispatchEvent({type}).
export function alvoDeEventos(alvo = {}) {
  const ouvintes = new Map();
  alvo.addEventListener = (tipo, fn) => {
    if (!ouvintes.has(tipo)) ouvintes.set(tipo, []);
    ouvintes.get(tipo).push(fn);
  };
  alvo.removeEventListener = (tipo, fn) => {
    ouvintes.set(tipo, (ouvintes.get(tipo) || []).filter((f) => f !== fn));
  };
  alvo.dispatchEvent = (evento) => {
    for (const fn of [...(ouvintes.get(evento.type) || [])]) fn(evento);
    return true;
  };
  return alvo;
}

// Storage próprio (Map), com a cota contada em caracteres de chave + valor, como o Chromium faz. Os métodos ficam no
// prototype, que é onde o script os embrulha (Storage.prototype.setItem), e `length` é um getter, como no navegador.
export function criarClasseStorage() {
  class Storage {
    constructor(cota = Infinity) {
      this._itens = new Map();
      this._cota = cota;
    }
    get length() { return this._itens.size; }
    key(i) { return [...this._itens.keys()][i] ?? null; }
    getItem(k) { return this._itens.has(String(k)) ? this._itens.get(String(k)) : null; }
    setItem(k, v) {
      if (arguments.length < 2) throw new TypeError('setItem pede chave e valor');
      k = String(k); v = String(v);
      let total = k.length + v.length;
      for (const [chave, valor] of this._itens) if (chave !== k) total += chave.length + valor.length;
      if (total > this._cota) throw new DOMException('Cota do armazenamento estourada', 'QuotaExceededError');
      this._itens.set(k, v);
    }
    removeItem(k) { this._itens.delete(String(k)); }
    clear() { this._itens.clear(); }
  }
  return Storage;
}

// Relógio falso: setTimeout/clearTimeout guardam o que foi pedido, e `avancar(ms)` roda os vencidos na ordem do
// vencimento (um timer que agenda outro dentro da janela também roda).
export function criarRelogio() {
  let agora = 0;
  let proximoId = 1;
  const timers = new Map();
  return {
    setTimeout(fn, ms = 0) {
      const id = proximoId++;
      timers.set(id, { id, em: agora + Math.max(0, Number(ms) || 0), fn });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
    avancar(ms) {
      const limite = agora + ms;
      for (;;) {
        const vencidos = [...timers.values()].filter((t) => t.em <= limite).sort((a, b) => a.em - b.em || a.id - b.id);
        if (!vencidos.length) break;
        const t = vencidos[0];
        timers.delete(t.id);
        agora = Math.max(agora, t.em);
        t.fn();
      }
      agora = limite;
    },
    ativos() { return timers.size; },
  };
}

// O endereço do App: base das URLs relativas do `window.open`.
export const ENDERECO_APP = 'https://gridco-campo-mw.azurewebsites.net/api/app';

// `retrato` é o que o nativo devolveria na partida (window.__GC_RETRATO__); `cota` é o limite do localStorage em
// caracteres; `ls` é o que o localStorage já tinha antes da carga (posto direto, sem a cota e sem o setItem);
// `sessao`, o mesmo para o sessionStorage (uma recarga na mesma WebView o mantém; uma partida nova começa vazia);
// `idb`, o IndexedDB do aparelho (`criarAparelho`), que dura entre as cargas como o localStorage. Sem `idb` a janela não
// tem `indexedDB`, e o script não mexe em IndexedDB nenhum (as provas do localStorage não precisam dele).
// Devolve a janela, o canal falso e tudo que o canal recebeu, já em objeto (o script manda JSON em texto).
// `paradas` guarda uma entrada por chamada de `win.stop()` (o teste confere `paradas.length`); `stop()` não faz mais nada.
// `responder(msg)` é o nativo falando com o script: entrega `msg` em JSON ao `canal.onmessage`, como a WebView entrega
// o que o JavaScriptReplyProxy manda.
export function criarJanela({ retrato = RETRATO_EM_USO, cota = Infinity, ls = LS_EM_USO, sessao = {}, idb = null } = {}) {
  const Storage = criarClasseStorage();
  const relogio = criarRelogio();
  const enviados = [];
  const paradas = [];
  const localStorage = new Storage(cota);
  for (const [k, v] of Object.entries(ls)) localStorage._itens.set(k, String(v));
  const sessionStorage = new Storage();
  for (const [k, v] of Object.entries(sessao)) sessionStorage._itens.set(k, String(v));

  const canal = {
    onmessage: null,
    postMessage(texto) {
      if (typeof texto !== 'string') throw new TypeError('o canal só recebe texto (JSON.stringify)');
      enviados.push(JSON.parse(texto));
    },
  };
  const responder = (mensagem) => {
    if (typeof canal.onmessage === 'function') canal.onmessage({ data: JSON.stringify(mensagem) });
  };

  // O document do jsdom, com os eventos e o visibilityState da janela falsa (o teste os troca à mão).
  const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', { url: ENDERECO_APP });
  const document = alvoDeEventos(dom.window.document);
  Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true, configurable: true });

  IDBObjectStore.prototype.put = PUT_ORIGINAL;
  IDBObjectStore.prototype.delete = DELETE_ORIGINAL;

  const win = alvoDeEventos({
    Storage,
    localStorage,
    sessionStorage,
    document,
    location: { href: ENDERECO_APP },
    URL,
    Blob,
    btoa,
    atob,
    setTimeout: relogio.setTimeout,
    clearTimeout: relogio.clearTimeout,
    stop() { paradas.push(true); },
    __GC_RETRATO__: retrato,
  });
  if (idb) {
    // Um objeto por janela por cima do IndexedDB do aparelho: o script embrulha o `open` da janela dele (como faz com o
    // window.indexedDB da WebView), e a carga seguinte acha os mesmos bancos sem o embrulho da anterior.
    win.indexedDB = Object.create(idb);
    win.IDBObjectStore = IDBObjectStore;
  }

  return {
    win, canal, enviados, paradas, retrato, idb, responder,
    avancar: relogio.avancar, timersAtivos: relogio.ativos,
  };
}

// ---------------------------------------------------------------------------------------------------------------------
// IndexedDB do aparelho (fake-indexeddb)
// ---------------------------------------------------------------------------------------------------------------------

// Um IndexedDB novo, vazio: a WebView de uma instalação nova, ou a que perdeu o armazenamento.
export function criarAparelho() {
  return new IDBFactory();
}

// Deixa o laço de eventos andar até `condicao()` valer (ela pode ser assíncrona). O fake-indexeddb trabalha por
// setImmediate; o relógio falso da janela não o move.
export async function ate(condicao, rotulo = 'condição') {
  const limite = Date.now() + 5000;
  while (Date.now() < limite) {
    if (await condicao()) return;
    await new Promise((r) => setImmediate(r));
  }
  throw new Error('esperou demais: ' + rotulo);
}

// Deixa o laço de eventos andar um pouco, para provar que algo NÃO acontece.
export async function assentar(voltas = 200) {
  for (let i = 0; i < voltas; i++) await new Promise((r) => setImmediate(r));
}

function pedir(req) {
  return new Promise((ok, falha) => {
    req.onsuccess = () => ok(req.result);
    req.onerror = () => falha(req.error);
  });
}

function concluir(tx) {
  return new Promise((ok, falha) => {
    tx.oncomplete = () => ok();
    tx.onerror = () => falha(tx.error);
    tx.onabort = () => falha(tx.error || new Error('transação abortada'));
  });
}

// Abre o banco das fotos como o `idb()` do campo.html (versão 1, cria a store se faltar), direto no aparelho.
function abrirFotos(aparelho) {
  const req = aparelho.open(BANCO_FOTOS, 1);
  req.onupgradeneeded = () => {
    if (!req.result.objectStoreNames.contains(STORE_FOTOS)) req.result.createObjectStore(STORE_FOTOS);
  };
  return pedir(req);
}

// Põe no aparelho, antes da carga, o que uma WebView em uso já teria: o banco sentinela da casca (`casca`: o IndexedDB
// desta WebView já foi conferido com o nativo; só a existência dele conta, como o script o cria) e as fotos (`fotos`,
// chave -> valor; `{}` cria o banco com a store vazia). Grava pelos originais, sem passar por embrulho nenhum.
export async function semear(aparelho, { casca = false, fotos = null } = {}) {
  if (casca) (await pedir(aparelho.open(BANCO_SENTINELA, 1))).close();
  if (fotos) {
    const db = await abrirFotos(aparelho);
    const tx = db.transaction(STORE_FOTOS, 'readwrite');
    const store = tx.objectStore(STORE_FOTOS);
    for (const [k, v] of Object.entries(fotos)) PUT_ORIGINAL.call(store, v, k);
    await concluir(tx);
    db.close();
  }
}

// Os nomes dos bancos do aparelho agora.
export async function bancos(aparelho) {
  return (await aparelho.databases()).map((d) => d.name).sort();
}

// Tudo que a store das fotos tem agora (chave -> valor), ou null se o banco não existe. Não cria o banco.
export async function lerFotos(aparelho) {
  if (!(await bancos(aparelho)).includes(BANCO_FOTOS)) return null;
  const db = await pedir(aparelho.open(BANCO_FOTOS));
  const tx = db.transaction(STORE_FOTOS, 'readonly');
  const store = tx.objectStore(STORE_FOTOS);
  // Os dois pedidos saem juntos: esperar um antes de fazer o outro deixaria a transação terminar no meio.
  const fim = concluir(tx);
  const [chaves, valores] = await Promise.all([pedir(store.getAllKeys()), pedir(store.getAll())]);
  await fim;
  db.close();
  const tudo = {};
  chaves.forEach((k, i) => { tudo[k] = valores[i]; });
  return tudo;
}

// O banco sentinela da casca existe agora? (Ele quer dizer: a última restauração do IndexedDB terminou.)
export async function temCasca(aparelho) {
  return (await bancos(aparelho)).includes(BANCO_SENTINELA);
}

// Os bytes de um Blob, para comparar.
export async function bytesDe(blob) {
  return new Uint8Array(await blob.arrayBuffer());
}
