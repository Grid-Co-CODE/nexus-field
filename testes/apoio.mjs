// Apoio dos testes do script de início (www/casca/inicio.js): uma janela falsa, só com o que o script toca.
// Fica sem ".test." no nome de propósito: o glob do `npm test` só pega *.test.mjs, então este arquivo não roda sozinho.
//
// Por que falsa e não jsdom: o jsdom não deixa configurar a cota do localStorage, e a cota estourada
// (QuotaExceededError) é a regra mais delicada do espelho. Aqui a cota é um número.
//
// `win.stop()` já é espionado desde a Task 2 (a Task 3 prova que a perda para a carga com ele). A Task 3 acrescentou as
// opções `ls` e `sessao` (o que a WebView já tinha antes da carga); a Task 4 estende `criarJanela` com indexedDB e
// document.open/write/close: acrescenta campos em `win` ou `document` dentro da função e, se preciso, devolve helpers a
// mais. Nada aqui assume a lista fechada.

import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { SENTINELA_LS } = require('../www/casca/inicio.js');

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

// `retrato` é o que o nativo devolveria na partida (window.__GC_RETRATO__); `cota` é o limite do localStorage em
// caracteres; `ls` é o que o localStorage já tinha antes da carga (posto direto, sem a cota e sem o setItem);
// `sessao`, o mesmo para o sessionStorage (uma recarga na mesma WebView o mantém; uma partida nova começa vazia).
// Devolve a janela, o canal falso e tudo que o canal recebeu, já em objeto (o script manda JSON em texto).
// `paradas` guarda uma entrada por chamada de `win.stop()` (o teste confere `paradas.length`); `stop()` não faz mais nada.
export function criarJanela({ retrato = RETRATO_EM_USO, cota = Infinity, ls = LS_EM_USO, sessao = {} } = {}) {
  const Storage = criarClasseStorage();
  const relogio = criarRelogio();
  const enviados = [];
  const paradas = [];
  const localStorage = new Storage(cota);
  for (const [k, v] of Object.entries(ls)) localStorage._itens.set(k, String(v));
  const sessionStorage = new Storage();
  for (const [k, v] of Object.entries(sessao)) sessionStorage._itens.set(k, String(v));

  const canal = {
    postMessage(texto) {
      if (typeof texto !== 'string') throw new TypeError('o canal só recebe texto (JSON.stringify)');
      enviados.push(JSON.parse(texto));
    },
  };

  const document = alvoDeEventos({ visibilityState: 'visible' });

  const win = alvoDeEventos({
    Storage,
    localStorage,
    sessionStorage,
    document,
    setTimeout: relogio.setTimeout,
    clearTimeout: relogio.clearTimeout,
    stop() { paradas.push(true); },
    __GC_RETRATO__: retrato,
  });

  return { win, canal, enviados, paradas, retrato, avancar: relogio.avancar, timersAtivos: relogio.ativos };
}
