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

// O retrato completo, depois de um precisoRetrato. Com motivo 'fila' o nativo manda `ls` vazio. O `id` é o que o nativo
// gera para cada retrato completo (sem `id`, o retrato de antes da regra: aplicado toda vez).
function completo({ ls = {}, fila = null, filaHashes = [], id } = {}) {
  const r = { ...leve({ filaHashes }), modo: 'restaurar', ls, fila };
  if (id !== undefined) r.id = id;
  return r;
}

// Espia, por baixo do script, cada gravação que chega ao localStorage real (a ordem da restauração). O sessionStorage
// usa o mesmo prototype e fica de fora: lá o script só guarda a memória da sessão.
function espiarGravacoes(ordem) {
  return ({ win }) => {
    const original = win.Storage.prototype.setItem;
    const lsReal = win.localStorage;
    win.Storage.prototype.setItem = function (k, v) {
      if (this === lsReal) ordem.push(String(k));
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

// O que o localStorage de uma janela tem agora, para montar a carga seguinte (a WebView guarda entre cargas).
function lsDe({ win }) {
  const ls = win.localStorage;
  const copia = {};
  for (let i = 0; i < ls.length; i++) copia[ls.key(i)] = ls.getItem(ls.key(i));
  return copia;
}

// O sessionStorage de uma janela agora (a WebView o mantém entre as cargas da mesma sessão).
function sessaoDe({ win }) {
  const copia = {};
  try {
    const s = win.sessionStorage;
    for (let i = 0; i < s.length; i++) copia[s.key(i)] = s.getItem(s.key(i));
  } catch (_) { /* sessionStorage ausente ou quebrado: nada a levar */ }
  return copia;
}

// A carga seguinte na mesma WebView (loginRedirect, atualização do service worker, 'Tentar de novo'): o localStorage e
// o sessionStorage continuam os da carga anterior; o retrato é o que o nativo registrou (por padrão, o mesmo de antes).
function recarregar(anterior, opcoes = {}, antes) {
  return montar({ ls: lsDe(anterior), sessao: sessaoDe(anterior), retrato: anterior.retrato, ...opcoes }, antes);
}

// O despejo por tamanho do `lsS` do campo.html (v257, `_lsMedir`, `_lsProtegida` e o recuo da fila do `lsS`), com os
// mesmos literais: quando a fila não cabe, derruba da maior chave para a menor tudo que não é msal nem começa com um
// dos prefixos protegidos, tentando gravar a fila depois de cada remoção. A sentinela da casca não está protegida.
const LS_TRABALHO_DA_PAGINA = ['gc_campo_queue', 'gc_campo_status', 'gc_fech_draft', 'gc_apr_rasc_',
  'gc_v1_os_chamado', 'gc_ronda', 'gc_v1_os_voltou', 'gc_vivo', 'gc_campo_aprdia', 'gc_ext_rasc_'];
function gravarFilaComoAPagina(ls, valor) {
  try { ls.setItem(CHAVE_FILA, valor); return true; } catch (_) { /* cota: o recuo abaixo */ }
  const chaves = [];
  for (let i = 0; i < ls.length; i++) {
    const c = ls.key(i);
    chaves.push([c, c.length + (ls.getItem(c) || '').length]);
  }
  chaves.sort((a, b) => b[1] - a[1]);
  for (const [alvo] of chaves) {
    if (alvo.indexOf('msal') >= 0 || LS_TRABALHO_DA_PAGINA.some((p) => alvo.indexOf(p) === 0)) continue;
    try {
      ls.removeItem(alvo);
      ls.setItem(CHAVE_FILA, valor);
    } catch (_) { continue; }
    return true;
  }
  return false;
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

test('a faxina por tamanho do lsS leva a sentinela com o aparelho cheio: a carga seguinte não é perda', () => {
  // Contas (chave + valor): sentinela 19, msal.conta 10+40=50, gc_subs_1 9+60=69, fila V1 14+10=24 → 162; cota 170.
  // A fila nova (14+100=114) só cabe sem o gc_subs_1 E sem a sentinela: 50+114=164; com a sentinela, 183.
  const cota = 170;
  const filaNova = 'q'.repeat(100);
  const inicio = { [SENTINELA_LS]: '1', 'msal.conta': 'm'.repeat(40), gc_subs_1: 's'.repeat(60), [CHAVE_FILA]: V1 };
  const j = montar({ ls: inicio, cota, retrato: leve({ filaHashes: [fnv1a32(V1)] }) });
  assert.deepEqual(j.enviados, [], 'carga normal');

  assert.equal(gravarFilaComoAPagina(j.ls, filaNova), true);
  assert.equal(j.ls.getItem(SENTINELA_LS), null, 'a página levou a sentinela');
  assert.deepEqual(j.enviados, [{ t: 'lsDel', k: 'gc_subs_1' }, { t: 'ls', k: CHAVE_FILA, v: filaNova }],
    'a remoção da sentinela não é espelhada');

  // A carga seguinte (recarregar, loginRedirect ou a próxima partida): o nativo tem a fila nova na frente.
  const k = montar({ ls: lsDe(j), cota, retrato: leve({ filaHashes: [fnv1a32(filaNova), fnv1a32(V1)] }) });
  assert.equal(k.paradas.length, 0, 'não para a carga');
  assert.equal(k.enviados.length, 1, 'nem pede retrato');
  assert.equal(k.enviados[0].t, 'erro', 'só avisa que a sentinela não coube (o aparelho continua cheio)');
  assert.equal(k.enviados[0].onde, 'sentinela');
  assert.ok(!/gc_|msal/.test(k.enviados[0].msg), 'o aviso não leva nome de chave');
  assert.equal(k.ls.getItem(CHAVE_FILA), filaNova, 'a fila da WebView fica como está');

  // e o espelho está ligado para a página
  k.ls.setItem(CHAVE_FILA, V1);
  assert.deepEqual(k.enviados[1], { t: 'ls', k: CHAVE_FILA, v: V1 });
});

test('sem sentinela e com chaves da página: regrava a sentinela, não manda `sentinela` e confere a fila', () => {
  const semSentinela = { 'msal.conta': 'm', gc_vivo: '1' };

  // fila em dia: nada a fazer além de regravar a sentinela
  const emDia = montar({ ls: { ...semSentinela, [CHAVE_FILA]: V3 }, retrato: leve({ filaHashes: HASHES }) });
  assert.equal(emDia.paradas.length, 0);
  assert.deepEqual(emDia.enviados, []);
  assert.notEqual(emDia.ls.getItem(SENTINELA_LS), null, 'a sentinela voltou');
  emDia.ls.setItem('gc_fech_draft', 'x');
  emDia.avancar(400);
  assert.deepEqual(emDia.enviados, [{ t: 'ls', k: 'gc_fech_draft', v: 'x' }], 'espelho ligado');

  // fila atrasada: a conferência da fila roda como em qualquer carga
  const atrasada = montar({ ls: { ...semSentinela, [CHAVE_FILA]: V2 }, retrato: leve({ filaHashes: HASHES }) });
  assert.equal(atrasada.paradas.length, 1);
  assert.deepEqual(atrasada.enviados, [{ t: 'precisoRetrato', motivo: 'fila' }]);

  // fila à frente: espelhada
  const frente = montar({ ls: { ...semSentinela, [CHAVE_FILA]: V4 }, retrato: leve({ filaHashes: HASHES }) });
  assert.equal(frente.paradas.length, 0);
  assert.deepEqual(frente.enviados, [{ t: 'ls', k: CHAVE_FILA, v: V4 }]);

  // só o que a página protege no despejo (msal), sem fila nem versões: segue, com a sentinela de volta
  const soMsal = montar({ ls: { 'msal.conta': 'm' }, retrato: leve() });
  assert.equal(soMsal.paradas.length, 0);
  assert.deepEqual(soMsal.enviados, []);
  assert.notEqual(soMsal.ls.getItem(SENTINELA_LS), null);
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

test('restaurar: se a sentinela não couber, o que voltou fica e a carga seguinte não vê perda', () => {
  // cota 80: fila 34 + gc_fech_draft 18 = 52; gc_zl_serv (10+10) = 72 cabe; a sentinela (19) → 91 não cabe.
  const fila = 'q'.repeat(20);
  const j = montar({
    ls: VAZIO,
    cota: 80,
    retrato: completo({ ls: { gc_fech_draft: 'd'.repeat(5), gc_zl_serv: 'z'.repeat(10) }, fila }),
  });
  assert.equal(j.ls.getItem(SENTINELA_LS), null);
  assert.equal(j.ls.getItem('gc_zl_serv'), 'z'.repeat(10), 'o cache fica: é o que o técnico usa sem sinal');
  assert.equal(j.ls.getItem('gc_fech_draft'), 'ddddd');
  assert.equal(j.ls.getItem(CHAVE_FILA), fila);
  assert.equal(j.enviados.length, 2);
  assert.equal(j.enviados[0].t, 'erro');
  assert.equal(j.enviados[0].onde, 'sentinela');
  assert.deepEqual(j.enviados[1], { t: 'restaurado', chaves: 2, naoCouberam: [], fila: true });

  // A carga seguinte da mesma partida (um loginRedirect): sem sentinela, com o espelho, mas a WebView tem chaves.
  const k = montar({ ls: lsDe(j), cota: 80, retrato: leve({ filaHashes: [fnv1a32(fila)] }) });
  assert.equal(k.paradas.length, 0, 'não para a carga de novo');
  assert.deepEqual(k.enviados.map((m) => m.t), ['erro'], 'nem pede retrato');
  assert.equal(k.enviados[0].onde, 'sentinela');
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
  // filaHashes vazio é o nativo sem versão nenhuma (ex.: o banco dele se perdeu e a WebView não). O nativo sem
  // `temEspelho` está no teste da `sentinela` reenviada, mais abaixo.
  for (const retrato of [leve({ filaHashes: HASHES }), leve({ filaHashes: [] })]) {
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

test('com sentinela e sem temEspelho: reenvia `sentinela` e confere a fila', () => {
  // O nativo não marcou o espelho (a `sentinela` da instalação se perdeu, ou o banco dele foi recriado): sem o
  // reenvio, uma perda futura seria tomada por instalação nova e nada voltaria.
  for (const retrato of [leve({ temEspelho: false }), {}, { temEspelho: 'sim' }]) {
    const nome = JSON.stringify(retrato);
    const soSentinela = montar({ ls: { [SENTINELA_LS]: '1' }, retrato });
    assert.equal(soSentinela.paradas.length, 0, nome);
    assert.deepEqual(soSentinela.enviados, [{ t: 'sentinela' }], nome);
    assert.equal(soSentinela.ls.getItem(SENTINELA_LS), '1');

    const comFila = montar({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: V4 }, retrato });
    assert.equal(comFila.paradas.length, 0, nome);
    assert.deepEqual(comFila.enviados, [{ t: 'sentinela' }, { t: 'ls', k: CHAVE_FILA, v: V4 }], nome);
    comFila.ls.setItem('gc_fech_draft', 'x');
    comFila.avancar(400);
    assert.deepEqual(comFila.enviados[2], { t: 'ls', k: 'gc_fech_draft', v: 'x' }, 'espelho ligado');
  }

  // com o espelho marcado, nada a reenviar
  const marcado = montar({ ls: { [SENTINELA_LS]: '1' }, retrato: leve() });
  assert.deepEqual(marcado.enviados, []);
});

test('instalação nova com fila na WebView: grava a sentinela, avisa e espelha a fila', () => {
  for (const retrato of [leve({ temEspelho: false }), {}]) {
    const j = montar({ ls: { [CHAVE_FILA]: V1, gc_fech_draft: 'rascunho' }, retrato });
    assert.equal(j.paradas.length, 0);
    assert.notEqual(j.ls.getItem(SENTINELA_LS), null);
    assert.deepEqual(j.enviados, [{ t: 'sentinela' }, { t: 'ls', k: CHAVE_FILA, v: V1 }], JSON.stringify(retrato));
  }
});

test('recarga na mesma partida com o retrato velho não para a carga', () => {
  // O nativo registra o retrato leve uma vez na partida, e ele vale para toda carga dela. O técnico abre o app com uma
  // pendência (versões ['[x]', '[]']), a página a envia e grava '[]' (espelhada): uma recarga na mesma partida acha
  // '[]' igual à versão antiga do retrato velho, mas o nativo já tem '[]' como a mais nova.
  const velho = leve({ filaHashes: [fnv1a32('[x]'), fnv1a32('[]')] });
  const j = montar({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: '[x]' }, retrato: velho });
  assert.deepEqual(j.enviados, []);
  j.ls.setItem(CHAVE_FILA, '[]');
  assert.deepEqual(j.enviados, [{ t: 'ls', k: CHAVE_FILA, v: '[]' }]);
  assert.equal(j.win.sessionStorage.getItem('gc_casca_fila_sessao'), fnv1a32('[]'));

  const k = recarregar(j);
  assert.equal(k.paradas.length, 0, 'não para a carga');
  assert.deepEqual(k.enviados, [], 'nem pede retrato, nem reenvia a fila');
  k.ls.setItem(CHAVE_FILA, V1);
  assert.deepEqual(k.enviados, [{ t: 'ls', k: CHAVE_FILA, v: V1 }], 'espelho ligado');

  // Uma partida nova (sessionStorage vazio) com o mesmo retrato velho ainda confere pelas versões: a regra de antes.
  const nova = montar({ ls: lsDe(j), retrato: velho });
  assert.equal(nova.paradas.length, 1);
  assert.deepEqual(nova.enviados, [{ t: 'precisoRetrato', motivo: 'fila' }]);

  // A fila que a própria conferência espelhou (a WebView à frente) também está em dia na recarga.
  const frente = montar({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: V4 }, retrato: leve({ filaHashes: HASHES }) });
  assert.deepEqual(frente.enviados, [{ t: 'ls', k: CHAVE_FILA, v: V4 }]);
  const frente2 = recarregar(frente);
  assert.equal(frente2.paradas.length, 0);
  assert.deepEqual(frente2.enviados, [], 'o nativo já tem a V4: nada a reenviar');

  // E a fila que a restauração devolveu: o retrato leve registrado antes dela ainda tem a V2 como versão antiga.
  const restaurada = montar({ ls: VAZIO, retrato: completo({ fila: V2, filaHashes: [fnv1a32(V2)], id: 'r1' }) });
  assert.equal(restaurada.enviados[0].t, 'restaurado');
  const depois = recarregar(restaurada, { retrato: leve({ filaHashes: HASHES }) });
  assert.equal(depois.paradas.length, 0);
  assert.deepEqual(depois.enviados, []);

  // Só vale a fila que chegou ao canal: se ele recusou a mensagem, a recarga confere pelas versões.
  const semCanal = montar({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: '[x]' }, retrato: velho });
  const postMessage = semCanal.canal.postMessage;
  semCanal.canal.postMessage = () => { throw new Error('canal fora'); };
  semCanal.ls.setItem(CHAVE_FILA, '[]');
  semCanal.canal.postMessage = postMessage;
  const semCanal2 = recarregar(semCanal);
  assert.equal(semCanal2.paradas.length, 1);
  assert.deepEqual(semCanal2.enviados, [{ t: 'precisoRetrato', motivo: 'fila' }]);
});

test('não para a carga duas vezes na mesma sessão', () => {
  const MSG = 'conferência ignorada: já parou nesta sessão';

  // Fila atrasada: a 1ª carga para; a 2ª, na mesma sessão (o retrato não mudou), segue, espelha a fila da WebView
  // (ela vence) e avisa sem chave nem valor; a 3ª já acha a fila em dia.
  const j = montar({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: V2 }, retrato: leve({ filaHashes: HASHES }) });
  assert.equal(j.paradas.length, 1);
  assert.deepEqual(j.enviados, [{ t: 'precisoRetrato', motivo: 'fila' }]);
  assert.notEqual(j.win.sessionStorage.getItem('gc_casca_parou'), null);

  const k = recarregar(j);
  assert.equal(k.paradas.length, 0, 'não para de novo');
  assert.deepEqual(k.enviados, [{ t: 'ls', k: CHAVE_FILA, v: V2 }, { t: 'erro', onde: 'fila', msg: MSG }]);
  k.ls.setItem('gc_fech_draft', 'x');
  k.avancar(400);
  assert.deepEqual(k.enviados[2], { t: 'ls', k: 'gc_fech_draft', v: 'x' }, 'espelho ligado');

  const l = recarregar(k);
  assert.equal(l.paradas.length, 0);
  assert.deepEqual(l.enviados, [], 'sem aviso repetido');

  // Fila nula com versões no nativo: a 2ª vez não tem fila a espelhar (e nunca manda apagar a do nativo).
  const n = montar({ ls: { [SENTINELA_LS]: '1' }, retrato: leve({ filaHashes: HASHES }) });
  assert.equal(n.paradas.length, 1);
  const n2 = recarregar(n);
  assert.equal(n2.paradas.length, 0);
  assert.deepEqual(n2.enviados, [{ t: 'erro', onde: 'fila', msg: MSG }]);

  // Perda: a 2ª carga da sessão com a WebView ainda vazia segue, sem regravar a sentinela (a partida seguinte ainda
  // vê a perda), e com o espelho ligado (o que o técnico fizer agora vai ao nativo).
  const p = montar({ ls: VAZIO, retrato: leve({ filaHashes: HASHES }) });
  assert.equal(p.paradas.length, 1);
  const p2 = recarregar(p);
  assert.equal(p2.paradas.length, 0);
  assert.deepEqual(p2.enviados, [{ t: 'erro', onde: 'perda', msg: MSG }]);
  assert.equal(p2.ls.getItem(SENTINELA_LS), null);
  p2.ls.setItem(CHAVE_FILA, V4);
  assert.deepEqual(p2.enviados[1], { t: 'ls', k: CHAVE_FILA, v: V4 });
});

test('o mesmo retrato completo não é aplicado duas vezes', () => {
  // O nativo recarrega com o retrato completo; se a página recarregar antes de ele voltar ao leve (um loginRedirect),
  // a mesma restauração regravaria por cima, sem espelhar, o que a página fez depois dela.
  const retrato = completo({ ls: { gc_fech_draft: 'fech' }, fila: V3, filaHashes: HASHES, id: 'r1' });
  const j = montar({ ls: VAZIO, retrato });
  assert.deepEqual(j.enviados, [{ t: 'restaurado', chaves: 1, naoCouberam: [], fila: true }]);
  assert.equal(j.win.sessionStorage.getItem('gc_casca_retrato_aplicado'), 'r1');
  j.ls.setItem(CHAVE_FILA, V4);
  j.ls.setItem('gc_fech_draft', 'rascunho novo');
  j.avancar(400);
  assert.equal(j.enviados.length, 3);

  const k = recarregar(j);
  assert.equal(k.paradas.length, 0);
  assert.deepEqual(k.enviados, [], 'tratado como leve: a fila está em dia, nada a restaurar');
  assert.equal(k.ls.getItem(CHAVE_FILA), V4);
  assert.equal(k.ls.getItem('gc_fech_draft'), 'rascunho novo');
  k.ls.setItem('gc_fech_draft', 'y');
  k.avancar(400);
  assert.deepEqual(k.enviados, [{ t: 'ls', k: 'gc_fech_draft', v: 'y' }], 'espelho ligado');

  // Um retrato completo novo (outro id) é aplicado.
  const r2 = recarregar(k, { retrato: { ...retrato, id: 'r2' } });
  assert.deepEqual(r2.enviados, [{ t: 'restaurado', chaves: 1, naoCouberam: [], fila: true }]);
  assert.equal(r2.ls.getItem(CHAVE_FILA), V3);

  // Sem id (ou com id que não é texto, ou vazio) não há como saber: aplica toda vez, como antes.
  for (const id of [undefined, 7, '']) {
    const a = montar({ ls: VAZIO, retrato: completo({ ls: { gc_fech_draft: 'fech' }, fila: V3, id }) });
    a.ls.setItem(CHAVE_FILA, V4);
    const b = recarregar(a);
    assert.equal(b.enviados[0].t, 'restaurado', String(id));
    assert.equal(b.ls.getItem(CHAVE_FILA), V3);
  }
});

test('sem sessionStorage, segue como antes', () => {
  const quebras = {
    ausente: ({ win }) => { delete win.sessionStorage; },
    negado: ({ win }) => {
      Object.defineProperty(win, 'sessionStorage', { get() { throw new DOMException('negado', 'SecurityError'); } });
    },
    // um objeto em que os métodos originais do Storage lançam (Illegal invocation, no navegador)
    torto: ({ win }) => { win.sessionStorage = {}; },
  };
  for (const [nome, quebrar] of Object.entries(quebras)) {
    // para a carga toda vez (sem a trava)
    for (let i = 0; i < 2; i++) {
      const atrasada = { ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: V2 }, retrato: leve({ filaHashes: HASHES }) };
      const j = montar(atrasada, quebrar);
      assert.equal(j.paradas.length, 1, nome);
      assert.deepEqual(j.enviados, [{ t: 'precisoRetrato', motivo: 'fila' }], nome);
    }

    // a fila espelhada não vale como em dia: a recarga com o retrato velho para, como antes
    const velho = leve({ filaHashes: [fnv1a32('[x]'), fnv1a32('[]')] });
    const a = montar({ ls: { [SENTINELA_LS]: '1', [CHAVE_FILA]: '[x]' }, retrato: velho }, quebrar);
    a.ls.setItem(CHAVE_FILA, '[]');
    assert.deepEqual(a.enviados, [{ t: 'ls', k: CHAVE_FILA, v: '[]' }], nome);
    const b = recarregar(a, {}, quebrar);
    assert.equal(b.paradas.length, 1, nome);
    assert.deepEqual(b.enviados, [{ t: 'precisoRetrato', motivo: 'fila' }], nome);

    // e o mesmo retrato completo é aplicado de novo
    const c = montar({ ls: VAZIO, retrato: completo({ fila: V3, id: 'r1' }) }, quebrar);
    c.ls.setItem(CHAVE_FILA, V4);
    const d = recarregar(c, {}, quebrar);
    assert.deepEqual(d.enviados, [{ t: 'restaurado', chaves: 0, naoCouberam: [], fila: true }], nome);
    assert.equal(d.ls.getItem(CHAVE_FILA), V3, nome);
  }
});
