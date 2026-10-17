// Apoio dos testes do script de início (www/casca/inicio.js): uma janela falsa, só com o que o script toca.
// Fica sem ".test." no nome de propósito: o glob do `npm test` só pega *.test.mjs, então este arquivo não roda sozinho.
//
// Por que falsa e não jsdom: o jsdom não deixa configurar a cota do localStorage, e a cota estourada
// (QuotaExceededError) é a regra mais delicada do espelho. Aqui a cota é um número.
//
// As Tasks 3 e 4 estendem `criarJanela` (stop espionado, indexedDB, document.open/write/close): acrescentam campos
// em `win` ou `document` dentro da função e, se preciso, devolvem helpers a mais. Nada aqui assume a lista fechada.

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
// caracteres. Devolve a janela, o canal falso e tudo que o canal recebeu, já em objeto (o script manda JSON em texto).
export function criarJanela({ retrato = {}, cota = Infinity } = {}) {
  const Storage = criarClasseStorage();
  const relogio = criarRelogio();
  const enviados = [];

  const canal = {
    postMessage(texto) {
      if (typeof texto !== 'string') throw new TypeError('o canal só recebe texto (JSON.stringify)');
      enviados.push(JSON.parse(texto));
    },
  };

  const document = alvoDeEventos({ visibilityState: 'visible' });

  const win = alvoDeEventos({
    Storage,
    localStorage: new Storage(cota),
    sessionStorage: new Storage(),
    document,
    setTimeout: relogio.setTimeout,
    clearTimeout: relogio.clearTimeout,
    __GC_RETRATO__: retrato,
  });

  return { win, canal, enviados, retrato, avancar: relogio.avancar, timersAtivos: relogio.ativos };
}
