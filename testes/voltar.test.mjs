// Prova o voltar do Android do lado do script de início: o nativo pergunta à página (window.__gcCascaVoltar) e só
// minimiza o app quando ela diz que não havia nada aberto. O que se protege aqui: o voltar fecha a camada de cima pelo
// mesmo botão ou função que o técnico usaria (a OS, o Ver, o Concluir com o rascunho salvo, a APR com o rascunho
// salvo, a câmera sem perder as fotos), nunca passa por cima de uma espera que trava a tela, e um nome que o site
// trocou só faz o voltar minimizar, sem quebrar a página.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';
import { criarJanela, ENDERECO_APP } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, VOLTAR } = require('../www/casca/inicio.js');
const CODIGO = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'www', 'casca', 'inicio.js'), 'utf8');

function montar(opcoes = {}) {
  const j = criarJanela(opcoes);
  const casca = instalar(j.win, j.canal, j.retrato);
  const doc = j.win.document;
  const chamadas = [];
  // Um elemento da página, já no body; `classes` é o class do HTML; o clique anota `rotulo` e roda `acao`.
  function elemento(tag, { id, classes, dentro, rotulo, acao } = {}) {
    const el = doc.createElement(tag);
    if (id) el.id = id;
    if (classes) el.className = classes;
    if (rotulo) el.addEventListener('click', () => { chamadas.push(rotulo); if (acao) acao(); });
    (dentro || doc.body).appendChild(el);
    return el;
  }
  // Uma função de topo da página (no navegador, propriedade da janela), que anota a chamada.
  function funcao(nome, acao) {
    j.win[nome] = function (...args) {
      chamadas.push([nome, ...args].join(':'));
      return acao ? acao.apply(this, args) : undefined;
    };
  }
  const voltar = () => j.win.__gcCascaVoltar();
  return { ...j, casca, doc, chamadas, elemento, funcao, voltar };
}

const aberta = (el) => el.classList.contains('open');

// A OS aberta como o abrirModal do campo.html a deixa: #modal.open com o #m-body e o botão Fechar dele.
function abrirOs(j) {
  const modal = j.elemento('div', { id: 'modal', classes: 'open' });
  const corpo = j.elemento('div', { id: 'm-body', dentro: modal });
  j.funcao('fecharModal', () => modal.classList.remove('open'));
  j.elemento('button', { classes: 'm-close-btn', dentro: corpo, rotulo: 'm-close-btn', acao: () => j.win.fecharModal() });
  return modal;
}

test('nada aberto: devolve false, e a função não é enumerável', () => {
  const j = montar();
  assert.equal(typeof j.win.__gcCascaVoltar, 'function');
  assert.equal(Object.keys(j.win).includes('__gcCascaVoltar'), false, 'não enumerável');
  assert.equal(j.voltar(), false);
  assert.equal(j.timersAtivos(), 0, 'nada agendado');
  assert.deepEqual(j.enviados, []);
});

test('a OS aberta: devolve true na hora e clica no Fechar dela no tempo seguinte', () => {
  const j = montar();
  const modal = abrirOs(j);
  assert.equal(j.voltar(), true);
  assert.deepEqual(j.chamadas, [], 'a resposta sai antes: um confirm() da página não segura o nativo');
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['m-close-btn', 'fecharModal']);
  assert.equal(aberta(modal), false);
  assert.equal(j.voltar(), false, 'fechada a OS, nada mais aberto');
});

test('a Pausa no lugar da OS: o botão do #m-body é o passo anterior', () => {
  const j = montar();
  const modal = j.elemento('div', { id: 'modal', classes: 'open' });
  const corpo = j.elemento('div', { id: 'm-body', dentro: modal });
  j.funcao('fecharModal');
  j.elemento('button', { classes: 'm-close-btn', dentro: corpo, rotulo: 'Cancelar: volta para a OS' });
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['Cancelar: volta para a OS'], 'não chama o fecharModal por cima');

  // Sem o botão, o fecharModal.
  corpo.innerHTML = '';
  j.chamadas.length = 0;
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['fecharModal']);
});

test('o Ver por cima da OS: o 1º voltar fecha o Ver, o 2º a OS, e o 3º devolve false', () => {
  const j = montar();
  abrirOs(j);
  const ver = j.elemento('div', { id: 'ver-modal', classes: 'open' });
  j.funcao('fecharVer', () => ver.classList.remove('open'));
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['fecharVer']);
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['fecharVer', 'm-close-btn', 'fecharModal']);
  assert.equal(j.voltar(), false);
});

test('o Concluir (#sheet): fecha pelo fecharSheet, que salva o rascunho, e reabre a OS que ele tinha substituído', () => {
  const j = montar();
  const sheet = j.elemento('div', { id: 'sheet', classes: 'open' });
  j.win.sheetTask = { osRef: '4521', tid: 7 };
  j.funcao('fecharSheet', () => { sheet.classList.remove('open'); j.win.sheetTask = null; });
  j.funcao('abrirModal');
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['fecharSheet', 'abrirModal:4521:7'], 'a tarefa é lida antes de o fecharSheet soltá-la');

  // Sem a tarefa (já solta), só fecha.
  sheet.classList.add('open');
  j.chamadas.length = 0;
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['fecharSheet']);
});

test('a APR: o rascunho é salvo antes do aprFechar, e a APR fechada no meio não é fechada de novo', async () => {
  const j = montar();
  const apr = j.elemento('div', { id: 'apr-modal', classes: 'open' });
  let terminarSalvar;
  j.funcao('_aprRascSalvar', () => new Promise((r) => { terminarSalvar = r; }));
  j.funcao('aprFechar', () => apr.classList.remove('open'));
  assert.equal(j.voltar(), true);
  j.avancar(0);
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(j.chamadas, ['_aprRascSalvar'], 'espera o rascunho');
  terminarSalvar();
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(j.chamadas, ['_aprRascSalvar', 'aprFechar']);
  assert.equal(aberta(apr), false);

  // O rascunho que falha: avisa sem o texto e fecha do mesmo jeito (é o que o botão de fechar da página faria).
  apr.classList.add('open');
  j.chamadas.length = 0;
  j.funcao('_aprRascSalvar', () => Promise.reject(new Error('cota estourada para tecnico@exemplo.com.br')));
  assert.equal(j.voltar(), true);
  j.avancar(0);
  await new Promise((r) => setImmediate(r));
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(j.chamadas, ['_aprRascSalvar', 'aprFechar']);
  assert.ok(j.enviados.some((m) => m.t === 'erro' && m.onde === 'voltar'));

  // O técnico fechou a APR enquanto o rascunho era salvo: nada a fechar.
  apr.classList.add('open');
  j.chamadas.length = 0;
  j.funcao('_aprRascSalvar', () => new Promise((r) => { terminarSalvar = r; }));
  assert.equal(j.voltar(), true);
  j.avancar(0);
  apr.classList.remove('open');
  terminarSalvar();
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(j.chamadas, ['_aprRascSalvar']);
});

test('as esperas que travam a tela (chamado, GPS exigido, sonda da câmera): o voltar é consumido sem fazer nada', () => {
  for (const [id, classes] of [['cham-wait', 'cham-ovl'], ['geo-exige', 'cham-ovl'], ['sonda', 'open']]) {
    const j = montar();
    abrirOs(j);
    const el = j.elemento('div', { id, classes });
    assert.equal(j.voltar(), true, id);
    assert.equal(j.timersAtivos(), 0, 'nada agendado: ' + id);
    j.avancar(0);
    assert.deepEqual(j.chamadas, [], 'nem a OS por baixo: ' + id);
    el.remove();
  }
  // A sonda fechada (sem .open) não conta.
  const j = montar();
  j.elemento('div', { id: 'sonda' });
  assert.equal(j.voltar(), false);
});

test('a camada da casca (o aviso do Fracttal) sai antes de tudo', () => {
  const j = montar();
  abrirOs(j);
  const aba = j.win.open('', '_blank');
  aba.document.open();
  aba.document.write('<h2>Sua sessão da Microsoft expirou</h2>');
  aba.document.close();
  j.avancar(1000);
  assert.ok(j.doc.getElementById('gc-casca-aviso'), 'a camada está de pé');
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.equal(j.doc.getElementById('gc-casca-aviso'), null, 'saiu pelo Fechar dela');
  assert.deepEqual(j.chamadas, [], 'a OS por baixo fica');
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['m-close-btn', 'fecharModal']);
});

test('a foto ampliada (.rd-lb): fecha a última aberta pelo clique dela', () => {
  const j = montar();
  abrirOs(j);
  j.elemento('div', { classes: 'rd-lb', rotulo: 'primeira' });
  const segunda = j.elemento('div', { classes: 'rd-lb', rotulo: 'segunda', acao: () => segunda.remove() });
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['segunda']);
});

test('a câmera do App: o Voltar dela (as fotos da fila continuam); sem o botão, o camFechar', () => {
  const j = montar();
  const cam = j.elemento('div', { id: 'cam' });
  j.funcao('camFechar', () => cam.remove());
  j.elemento('button', { classes: 'cam-volta', dentro: cam, rotulo: 'cam-volta', acao: () => j.win.camFechar() });
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['cam-volta', 'camFechar']);

  const k = montar();
  k.elemento('div', { id: 'cam' });
  k.funcao('camFechar');
  assert.equal(k.voltar(), true);
  k.avancar(0);
  assert.deepEqual(k.chamadas, ['camFechar']);
});

test('os Extintores: o voltar da subtela; sem ele, o extFechar', () => {
  const j = montar();
  const ext = j.elemento('div', { id: 'ext-tela', classes: 'open' });
  j.funcao('extFechar');
  j.elemento('button', { classes: 'ext-volta', dentro: ext, rotulo: 'ext-volta da subtela' });
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['ext-volta da subtela']);
  ext.innerHTML = '';
  j.chamadas.length = 0;
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['extFechar']);
  // Fechados (sem .open), não contam.
  ext.classList.remove('open');
  assert.equal(j.voltar(), false);
});

test('as folhas de baixo (fora da programação, feedback) e o menu do usuário', () => {
  const casos = [['fora-modal', 'foraFechar'], ['fb-sheet', 'fecharFeedback'], ['user-menu', 'fecharUserMenu']];
  for (const [id, fn] of casos) {
    const j = montar();
    const el = j.elemento('div', { id, classes: 'open' });
    j.funcao(fn, () => el.classList.remove('open'));
    assert.equal(j.voltar(), true, id);
    j.avancar(0);
    assert.deepEqual(j.chamadas, [fn], id);
    assert.equal(j.voltar(), false, id);
  }
  // O fora-modal por cima da OS sai primeiro.
  const j = montar();
  abrirOs(j);
  j.elemento('div', { id: 'fora-modal', classes: 'open' });
  j.funcao('foraFechar');
  j.voltar();
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['foraFechar']);
});

test('as abas: a Ronda pelo Voltar dela, a Zeladoria pelo dela, as outras para Tarefas; em Tarefas, false', () => {
  // Ronda com a seção aberta: o .rd-voltar (o rdVoltar da página pede confirmação com pendência).
  const j = montar();
  j.funcao('setTab');
  const ronda = j.elemento('div', { id: 'tela-ronda', classes: 'on' });
  j.elemento('button', { classes: 'rd-voltar', dentro: ronda, rotulo: 'rd-voltar' });
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['rd-voltar']);
  // Ronda sem seção aberta: Tarefas.
  ronda.innerHTML = '';
  j.chamadas.length = 0;
  assert.equal(j.voltar(), true);
  j.avancar(0);
  assert.deepEqual(j.chamadas, ['setTab:tarefas']);

  // Zeladoria dentro de um serviço: o .zl-volta; na lista, Tarefas.
  const z = montar();
  z.funcao('setTab');
  const zl = z.elemento('div', { id: 'tela-zl', classes: 'on' });
  z.elemento('button', { classes: 'zl-volta', dentro: zl, rotulo: 'zl-volta' });
  z.voltar();
  z.avancar(0);
  assert.deepEqual(z.chamadas, ['zl-volta']);

  for (const id of ['tela-sync', 'tela-conquistas', 'tela-acomp']) {
    const s = montar();
    s.funcao('setTab');
    s.elemento('div', { id, classes: 'on' });
    s.elemento('div', { id: 'tela-ronda' }); // as telas das outras abas existem, sem o .on
    assert.equal(s.voltar(), true, id);
    s.avancar(0);
    assert.deepEqual(s.chamadas, ['setTab:tarefas'], id);
  }

  // Em Tarefas (nenhuma tela de aba com .on), sem nada aberto: false, e o app vai para o fundo.
  const t = montar();
  t.funcao('setTab');
  t.elemento('div', { id: 'tela-ronda' });
  t.elemento('div', { id: 'tela-sync' });
  assert.equal(t.voltar(), false);
});

test('o site trocou um nome: sem a função da camada aberta, devolve false e não lança nem fecha a de baixo', () => {
  const j = montar();
  abrirOs(j);
  j.elemento('div', { id: 'ver-modal', classes: 'open' }); // sem o fecharVer
  assert.equal(j.voltar(), false);
  j.avancar(0);
  assert.deepEqual(j.chamadas, [], 'a OS por baixo não fecha no lugar do Ver');

  const k = montar();
  k.elemento('div', { id: 'tela-ronda', classes: 'on' }); // sem .rd-voltar e sem setTab
  assert.equal(k.voltar(), false);

  const a = montar();
  a.elemento('div', { id: 'apr-modal', classes: 'open' });
  a.funcao('_aprRascSalvar');
  assert.equal(a.voltar(), false, 'sem o aprFechar');
});

test('erro na decisão vira aviso voltar (sem texto da página) e false; erro no fechamento também avisa', () => {
  const j = montar();
  j.doc.getElementById = () => { throw new Error('quebrou com tecnico@exemplo.com.br'); };
  assert.equal(j.voltar(), false);
  assert.deepEqual(j.enviados.map((m) => m.t + ':' + m.onde), ['erro:voltar']);

  const k = montar();
  const ver = k.elemento('div', { id: 'ver-modal', classes: 'open' });
  k.funcao('fecharVer', () => { throw new Error('falhou'); });
  assert.equal(k.voltar(), true);
  assert.doesNotThrow(() => k.avancar(0));
  assert.deepEqual(k.enviados.map((m) => m.t + ':' + m.onde), ['erro:voltar']);
  assert.ok(aberta(ver));
});

test('VOLTAR traz os nomes do campo.html que o voltar usa, num lugar só (o contrato com o site os confere)', () => {
  assert.ok(VOLTAR && typeof VOLTAR === 'object');
  assert.deepEqual(Object.keys(VOLTAR).sort(), ['abaInicial', 'classe', 'funcao', 'global', 'id']);
  for (const grupo of ['id', 'classe', 'funcao', 'global']) {
    for (const [papel, nome] of Object.entries(VOLTAR[grupo])) {
      assert.match(nome, /^[A-Za-z_][\w-]*$/, grupo + '.' + papel);
    }
  }
  assert.equal(VOLTAR.id.os, 'modal');
  assert.equal(VOLTAR.funcao.abrirOs, 'abrirModal');
  assert.equal(VOLTAR.global.tarefaDoFechamento, 'sheetTask');
  assert.equal(VOLTAR.abaInicial, 'tarefas');
  // Todo nome do objeto aparece no código do voltar por papel (nenhum nome solto que o contrato não veria).
  // A seção 7 vai até a 8 (o registro do aparelho, que tem os nomes dela no PUSH, conferidos no testes/push.test.mjs).
  const secao = CODIGO.slice(CODIGO.indexOf('// 7. O voltar do Android'), CODIGO.indexOf('// 8. O registro do aparelho'));
  assert.ok(secao.includes('function instalarVoltar('), 'o corte pega a seção 7 inteira');
  for (const grupo of ['id', 'classe', 'funcao', 'global']) {
    for (const papel of Object.keys(VOLTAR[grupo])) {
      assert.ok(secao.includes('VOLTAR.' + grupo + '.' + papel), 'usado: VOLTAR.' + grupo + '.' + papel);
    }
  }
  // E o contrário: nenhum nome do site escrito por fora do objeto. Os textos entre aspas da seção são só os nossos, e
  // o único nome do site sem aspas é o `let` lido pelo nome, que é o do objeto.
  const codigo = secao.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
  const textos = [...new Set([...codigo.matchAll(/'([^']*)'/g)].map((m) => m[1]))].sort();
  assert.deepEqual(textos, ['.', '__gcCascaVoltar', 'button', 'function', 'undefined', 'voltar']);
  assert.ok(codigo.includes('typeof ' + VOLTAR.global.tarefaDoFechamento + ' '), 'o let lido pelo nome é o do objeto');
});

test('no navegador: a função existe com o arquivo como script clássico, e o let de topo da página é lido pelo nome', async () => {
  // O sheetTask é um `let` de topo do campo.html: não vira propriedade da janela, mas todo script clássico da página o
  // enxerga pelo nome (o ambiente léxico global é um só). Aqui o jsdom roda o inicio.js e a página como scripts de
  // verdade, na mesma janela.
  const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {
    url: ENDERECO_APP,
    runScripts: 'dangerously',
    beforeParse(window) {
      window.gcCasca = { postMessage() {} };
      window.__GC_RETRATO__ = { temEspelho: true, filaHashes: [], idbChaves: [] };
    },
  });
  const { window } = dom;
  const rodar = (codigo) => {
    const s = window.document.createElement('script');
    s.textContent = codigo;
    window.document.head.appendChild(s);
  };
  rodar(CODIGO);
  rodar(`
    let sheetTask = { osRef: '88', tid: 3 };
    window.chamadas = [];
    function fecharSheet() { chamadas.push('fecharSheet'); document.getElementById('sheet').classList.remove('open'); sheetTask = null; }
    function abrirModal(os, tid) { chamadas.push('abrirModal:' + os + ':' + tid); }
  `);
  window.document.body.innerHTML = '<div id="sheet" class="open"></div>';
  assert.equal(window.sheetTask, undefined, 'o let não é propriedade da janela');
  assert.equal(window.__gcCascaVoltar(), true);
  await new Promise((r) => window.setTimeout(r, 5));
  assert.deepEqual([...window.chamadas], ['fecharSheet', 'abrirModal:88:3']);
  assert.equal(window.__gcCascaVoltar(), false);
  window.close();
});
