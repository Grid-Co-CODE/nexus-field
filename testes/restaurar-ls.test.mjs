// Prova a sentinela do localStorage, as versões da fila e a restauração do script de início (spec, seção 6.4: "Quando
// restaurar" e "A fila tem versões"). O que se protege aqui: depois de a WebView perder o armazenamento, a página nunca
// roda vazia com o espelho ligado (ela gravaria o vazio por cima do que o nativo guardou), e a restauração devolve o
// que o nativo tem sem ecoar nada de volta.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { criarJanela } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, fnv1a32, CHAVE_FILA, SENTINELA_LS } = require('../www/casca/inicio.js');

// A WebView sem nada no localStorage: instalação nova, ou o despejo do Chromium levou tudo (a sentinela junto).
const VAZIO = {};

// Janela falsa + script instalado. `antes` roda ANTES do script (para espiar o setItem original, por exemplo).
function montar(opcoes = {}, antes = () => {}) {
  const j = criarJanela(opcoes);
  antes(j);
  const casca = instalar(j.win, j.canal, j.retrato);
  return { ...j, casca, ls: j.win.localStorage };
}

// O retrato leve que o nativo põe em toda partida.
function leve({ temEspelho = true, filaHashes = [], idbChaves = [] } = {}) {
  return { temEspelho, filaHashes, idbChaves };
}

// O retrato completo, depois de um precisoRetrato. Com motivo 'fila' o nativo manda `ls` vazio.
function completo({ ls = {}, fila = null, filaHashes = [] } = {}) {
  return { ...leve({ filaHashes }), modo: 'restaurar', ls, fila };
}

// Espia, por baixo do script, cada gravação que chega ao localStorage real (a ordem da restauração).
function espiarGravacoes(ordem) {
  return ({ win }) => {
    const original = win.Storage.prototype.setItem;
    win.Storage.prototype.setItem = function (k, v) {
      ordem.push(String(k));
      return original.apply(this, arguments);
    };
  };
}

// A página roda depois do script: grava e apaga de tudo, e o tempo passa. Quem prova "o espelho está desligado"
// confere que nada disso chega ao nativo.
function paginaMexe({ win, ls, casca, avancar }) {
  ls.setItem(CHAVE_FILA, '[]');
  ls.setItem('gc_fech_draft', 'rascunho novo');
  ls.setItem('gc_subs_1', 'cache novo');
  ls.removeItem('gc_zl_pend');
  avancar(5000);
  win.document.visibilityState = 'hidden';
  win.document.dispatchEvent({ type: 'visibilitychange' });
  win.dispatchEvent({ type: 'pagehide' });
  casca.descarregar();
}

const V1 = '[{"id":1}]';
const V2 = '[{"id":1},{"id":2}]';
const V3 = '[{"id":1},{"id":2},{"id":3}]';
const V4 = '[{"id":1},{"id":2},{"id":3},{"id":4}]';
const VERSOES = [V3, V2, V1]; // mais nova primeiro, como o nativo manda
const HASHES = VERSOES.map(fnv1a32);

test('instalação nova grava a sentinela e não para a carga', () => {
  // `{}` é o retrato sem campos (o nativo ainda não sabe de nada): também é instalação nova.
  for (const retrato of [leve({ temEspelho: false }), {}]) {
    const j = montar({ ls: VAZIO, retrato });
    assert.equal(j.paradas.length, 0, 'stop não pode ser chamado');
    assert.notEqual(j.ls.getItem(SENTINELA_LS), null, 'a sentinela ficou no localStorage');
    assert.deepEqual(j.enviados, [{ t: 'sentinela' }]);

    // e o espelho está ligado para a página
    j.ls.setItem(CHAVE_FILA, V1);
    assert.deepEqual(j.enviados[1], { t: 'ls', k: CHAVE_FILA, v: V1 });
  }
});

test('perda: para a carga, pede o retrato e não espelha nada', () => {
  const j = criarJanela({ ls: VAZIO, retrato: leve({ filaHashes: HASHES }) });
  const { setItem, removeItem } = j.win.Storage.prototype;
  const casca = instalar(j.win, j.canal, j.retrato);
  const ls = j.win.localStorage;

  assert.equal(j.paradas.length, 1, 'win.stop() chamado uma vez');
  assert.deepEqual(j.enviados, [{ t: 'precisoRetrato', motivo: 'perda' }]);
  assert.equal(ls.getItem(SENTINELA_LS), null, 'a sentinela só volta com a restauração');
  assert.equal(j.win.Storage.prototype.setItem, setItem, 'nada embrulhado nesta carga');
  assert.equal(j.win.Storage.prototype.removeItem, removeItem);

  // alguns scripts da página podem já ter começado: o vazio deles não pode chegar ao nativo
  paginaMexe({ win: j.win, ls, casca, avancar: j.avancar });
  assert.deepEqual(j.enviados, [{ t: 'precisoRetrato', motivo: 'perda' }]);
  assert.equal(ls.getItem(CHAVE_FILA), '[]', 'o real grava normalmente, só não é espelhado');
});

test('perda: se win.stop() lança, o espelho fica desligado do mesmo jeito e o retrato é pedido', () => {
  const j = criarJanela({ ls: VAZIO, retrato: leve() });
  j.win.stop = () => { throw new Error('stop quebrado'); };
  const casca = instalar(j.win, j.canal, j.retrato);
  assert.deepEqual(j.enviados.map((m) => m.t), ['erro', 'precisoRetrato']);
  assert.equal(j.enviados[1].motivo, 'perda');
  j.enviados.length = 0;
  paginaMexe({ win: j.win, ls: j.win.localStorage, casca, avancar: j.avancar });
  assert.deepEqual(j.enviados, []);
});

test('restaurar devolve fila, trabalho e cache e grava a sentinela', () => {
  const ordem = [];
  const ronda = 'gc_ronda_x@y_2026-10-16_U1';
  const j = montar({
    ls: VAZIO,
    retrato: completo({
      // o cache vem primeiro no objeto de propósito: a ordem é por classe, não pela do retrato
      ls: { gc_subs_1: 'subs', gc_fech_draft: 'fech', [ronda]: 'ronda', gc_ronda_ctx: 'ctx' },
      fila: V3,
      filaHashes: HASHES,
    }),
  }, espiarGravacoes(ordem));

  assert.equal(j.paradas.length, 0);
  assert.deepEqual(ordem, [CHAVE_FILA, 'gc_fech_draft', ronda, 'gc_subs_1', 'gc_ronda_ctx', SENTINELA_LS]);
  assert.equal(j.ls.getItem(CHAVE_FILA), V3);
  assert.equal(j.ls.getItem('gc_fech_draft'), 'fech');
  assert.equal(j.ls.getItem(ronda), 'ronda');
  assert.equal(j.ls.getItem('gc_subs_1'), 'subs');
  assert.equal(j.ls.getItem('gc_ronda_ctx'), 'ctx');
  assert.notEqual(j.ls.getItem(SENTINELA_LS), null);
  assert.deepEqual(j.enviados, [{ t: 'restaurado', chaves: 4, naoCouberam: [], fila: true }]);

  // depois da restauração a página roda com o espelho ligado
  j.ls.setItem(CHAVE_FILA, V4);
  assert.deepEqual(j.enviados[1], { t: 'ls', k: CHAVE_FILA, v: V4 });
});

test('restaurar com cota curta grava a fila primeiro e lista o que não coube', () => {
  // Contas da cota (caracteres de chave + valor): fila 14+20=34; gc_fech_draft 13+5=18 (52); a sentinela 18+1=19.
  // Cada chave do cache passa de 75 sozinha somada ao resto. Se a ordem fosse a do retrato (cache primeiro), o
  // gc_subs_1 (9+30=39) entraria e a fila ficaria de fora.
  const j = montar({
    ls: VAZIO,
    cota: 75,
    retrato: completo({
      ls: { gc_subs_1: 'x'.repeat(30), gc_v1_almox_2: 'y'.repeat(30), gc_fech_draft: 'd'.repeat(5) },
      fila: 'q'.repeat(20),
    }),
  });
  assert.equal(j.ls.getItem(CHAVE_FILA), 'q'.repeat(20));
  assert.equal(j.ls.getItem('gc_fech_draft'), 'ddddd');
  assert.equal(j.ls.getItem('gc_subs_1'), null);
  assert.equal(j.ls.getItem('gc_v1_almox_2'), null);
  assert.notEqual(j.ls.getItem(SENTINELA_LS), null);
  assert.deepEqual(j.enviados, [
    { t: 'restaurado', chaves: 1, naoCouberam: ['gc_subs_1', 'gc_v1_almox_2'], fila: true },
  ]);
});

test('restaurar: chave por chave, a pequena que vem depois da grande ainda entra', () => {
  // cota 80: fila 34 + gc_subs_1 grande (9+60) não cabe; gc_zl_serv (10+3=13) cabe depois dela; sentinela 19 → 66.
  const j = montar({
    ls: VAZIO,
    cota: 80,
    retrato: completo({ ls: { gc_subs_1: 'g'.repeat(60), gc_zl_serv: 'abc' }, fila: 'q'.repeat(20) }),
  });
  assert.equal(j.ls.getItem('gc_zl_serv'), 'abc');
  assert.deepEqual(j.enviados, [{ t: 'restaurado', chaves: 1, naoCouberam: ['gc_subs_1'], fila: true }]);
});

test('restaurar: a sentinela vence o cache (se não couber, o cache restaurado devolve o espaço)', () => {
  // Sem a sentinela, a próxima carga desta mesma partida (um loginRedirect, um recarregar) veria "perda" de novo.
  // cota 80: fila 34 + gc_fech_draft 18 = 52; gc_zl_serv (10+10) = 72 cabe; a sentinela (19) → 91 não cabe.
  const j = montar({
    ls: VAZIO,
    cota: 80,
    retrato: completo({ ls: { gc_fech_draft: 'd'.repeat(5), gc_zl_serv: 'z'.repeat(10) }, fila: 'q'.repeat(20) }),
  });
  assert.notEqual(j.ls.getItem(SENTINELA_LS), null, 'a sentinela entrou');
  assert.equal(j.ls.getItem('gc_zl_serv'), null, 'o cache deu o lugar');
  assert.equal(j.ls.getItem('gc_fech_draft'), 'ddddd', 'o trabalho fica');
  assert.equal(j.ls.getItem(CHAVE_FILA), 'q'.repeat(20), 'a fila fica');
  assert.deepEqual(j.enviados, [{ t: 'restaurado', chaves: 1, naoCouberam: ['gc_zl_serv'], fila: true }]);
});

test('restaurar com a fila que não cabe: fila false, e a fila entra no naoCouberam', () => {
  const j = montar({ ls: VAZIO, cota: 30, retrato: completo({ ls: { gc_zl_serv: 'a' }, fila: 'q'.repeat(40) }) });
  assert.equal(j.ls.getItem(CHAVE_FILA), null);
  assert.equal(j.ls.getItem('gc_zl_serv'), 'a');
  assert.deepEqual(j.enviados, [{ t: 'restaurado', chaves: 1, naoCouberam: [CHAVE_FILA], fila: false }]);
});

test('restaurar com motivo fila (ls vazio) só regrava a fila e não toca no resto', () => {
  const j = montar({
    ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: V2, gc_fech_draft: 'rascunho da página', gc_subs_1: 'cache da página' },
    retrato: completo({ ls: {}, fila: V3, filaHashes: HASHES }),
  });
  assert.equal(j.paradas.length, 0);
  assert.equal(j.ls.getItem(CHAVE_FILA), V3);
  assert.equal(j.ls.getItem('gc_fech_draft'), 'rascunho da página');
  assert.equal(j.ls.getItem('gc_subs_1'), 'cache da página');
  assert.deepEqual(j.enviados, [{ t: 'restaurado', chaves: 0, naoCouberam: [], fila: true }]);
});

test('restaurar ignora no ls o que não é trabalho nem cache (msal, sentinela, a fila, preferências)', () => {
  const j = montar({
    ls: VAZIO,
    retrato: completo({
      ls: {
        'msal.abc.idtoken': 'segredo', [SENTINELA_LS]: 'outra', [CHAVE_FILA]: V1, gc_vivo: '1', gc_fech_draft: 'd',
      },
      fila: V3,
    }),
  });
  assert.equal(j.ls.getItem('msal.abc.idtoken'), null);
  assert.equal(j.ls.getItem('gc_vivo'), null);
  assert.equal(j.ls.getItem(CHAVE_FILA), V3, 'a fila vem do campo `fila`, nunca do `ls`');
  assert.equal(j.ls.getItem('gc_fech_draft'), 'd');
  assert.equal(j.ls.getItem(SENTINELA_LS), '1');
  assert.deepEqual(j.enviados, [{ t: 'restaurado', chaves: 1, naoCouberam: [], fila: true }]);
});

test('restaurar não ecoa as próprias gravações ao nativo', () => {
  const j = montar({
    ls: VAZIO,
    retrato: completo({ ls: { gc_fech_draft: 'fech', gc_zl_pend: 'pend', gc_subs_1: 'subs' }, fila: V3 }),
  });
  j.avancar(5000);
  j.win.document.visibilityState = 'hidden';
  j.win.document.dispatchEvent({ type: 'visibilitychange' });
  j.win.dispatchEvent({ type: 'pagehide' });
  j.casca.descarregar();
  assert.deepEqual(j.enviados, [{ t: 'restaurado', chaves: 3, naoCouberam: [], fila: true }]);
});

test('restaurar com retrato torto (sem ls, fila que não é texto) não quebra e grava a sentinela', () => {
  for (const extra of [{ ls: null, fila: undefined }, { ls: 'nada', fila: 7 }, { ls: { gc_fech_draft: 9 } }]) {
    const j = montar({ ls: VAZIO, retrato: { ...completo(), ...extra } });
    assert.equal(j.paradas.length, 0);
    assert.notEqual(j.ls.getItem(SENTINELA_LS), null);
    assert.equal(j.ls.getItem('gc_fech_draft'), null);
    assert.deepEqual(j.enviados, [{ t: 'restaurado', chaves: 0, naoCouberam: [], fila: false }], JSON.stringify(extra));
  }
});

test('fila atrasada: hash bate com versão antiga → pede retrato motivo fila', () => {
  for (const antiga of [V2, V1]) { // filaHashes[1] e [2]
    const j = criarJanela({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: antiga }, retrato: leve({ filaHashes: HASHES }) });
    const { setItem } = j.win.Storage.prototype;
    const casca = instalar(j.win, j.canal, j.retrato);
    assert.equal(j.paradas.length, 1, antiga);
    assert.deepEqual(j.enviados, [{ t: 'precisoRetrato', motivo: 'fila' }]);
    assert.equal(j.win.Storage.prototype.setItem, setItem, 'espelho desligado');
    assert.equal(j.win.localStorage.getItem(CHAVE_FILA), antiga, 'o script não mexe na fila: quem devolve é o retrato');
    paginaMexe({ win: j.win, ls: j.win.localStorage, casca, avancar: j.avancar });
    assert.equal(j.enviados.length, 1, 'nada da página chega ao nativo');
  }
});

test('fila nula com sentinela e espelho com versões → pede retrato motivo fila', () => {
  const j = montar({ ls: { [SENTINELA_LS]: '1' }, retrato: leve({ filaHashes: HASHES }) });
  assert.equal(j.paradas.length, 1);
  assert.deepEqual(j.enviados, [{ t: 'precisoRetrato', motivo: 'fila' }]);
  paginaMexe(j);
  assert.equal(j.enviados.length, 1, 'espelho desligado');
});

test('fila à frente do espelho: espelha a atual', () => {
  // filaHashes vazio é o nativo sem versão nenhuma (ex.: o banco dele se perdeu e a WebView não)
  for (const retrato of [leve({ filaHashes: HASHES }), leve({ temEspelho: false, filaHashes: [] }), {}]) {
    const j = montar({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: V4 }, retrato });
    assert.equal(j.paradas.length, 0);
    assert.deepEqual(j.enviados, [{ t: 'ls', k: CHAVE_FILA, v: V4 }], JSON.stringify(retrato));
    assert.equal(j.ls.getItem(CHAVE_FILA), V4);
    // e o espelho segue ligado
    j.ls.setItem('gc_fech_draft', 'x');
    j.avancar(400);
    assert.deepEqual(j.enviados[1], { t: 'ls', k: 'gc_fech_draft', v: 'x' });
  }
});

test('fila igual à versão mais nova: nada a fazer, mesmo que o mesmo valor apareça numa versão antiga', () => {
  // A página pode gravar A, B e A de novo: as versões ficam [A, B, A], e a WebView com A está em dia.
  for (const filaHashes of [[fnv1a32(V1)], [fnv1a32(V1), fnv1a32(V2), fnv1a32(V1)]]) {
    const j = montar({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: V1 }, retrato: leve({ filaHashes }) });
    assert.equal(j.paradas.length, 0);
    assert.deepEqual(j.enviados, []);
  }
});

test('com sentinela, sem fila e sem versões: nada a fazer', () => {
  const j = montar({ ls: { [SENTINELA_LS]: '1' }, retrato: leve() });
  assert.equal(j.paradas.length, 0);
  assert.deepEqual(j.enviados, []);
  j.ls.setItem(CHAVE_FILA, V1);
  assert.deepEqual(j.enviados, [{ t: 'ls', k: CHAVE_FILA, v: V1 }]);
});

test('filaHashes que não é lista conta como nenhuma versão', () => {
  const j = montar({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: V1 }, retrato: { temEspelho: true, filaHashes: 'abc' } });
  assert.equal(j.paradas.length, 0);
  assert.deepEqual(j.enviados, [{ t: 'ls', k: CHAVE_FILA, v: V1 }]);
});
