// Prova a abertura da OS pelo toque na notificação, do lado do script de início (spec 2026-10-18, seção 4.2, "Ao tocar"):
// o nativo manda `window.__gcCascaAbrirOS('<os>')` e a página faz o que o técnico faria, ir à aba Tarefas, digitar o
// número no campo de busca (`#busca-os`) e deixar o `setBusca` procurar, local e depois ao vivo no Fracttal. A página não
// abre OS pelo endereço, então este é o caminho que ela já tem. O que se protege aqui:
//   - a regra do dono (18/10/2026, "Continua no preenchimento de OS ou ronda, a da notificação fica pendente"): com
//     qualquer camada da pilha do voltar aberta, com a ronda começada, com um serviço da Zeladoria aberto ou na tela de
//     login, nada é fechado e nenhuma aba é trocada; a OS fica pendente no sessionStorage (só no dia; sem ele, numa
//     variável da página) e abre sozinha quando ele ficar livre, por uma conferência de 2 s que só roda enquanto houver
//     pendência; a última emergência vence;
//   - a tela de login conta como ocupada: toda partida a frio cai nela, e o Entrar recarrega a página pelo
//     loginRedirect (a busca posta ali se perdia); a pendência atravessa a recarga e abre com a tela do App na frente;
//   - livre, troca para Tarefas (só se não estiver nela) e preenche o campo ANTES do setBusca, e avisa o nativo
//     ({t:'osAberta', os}), que repete a OS a cada carga até ouvir isso; a mesma OS entregue de novo na mesma carga não
//     reabre;
//   - só entra número de OS (1 a 12 dígitos ASCII), porque o nativo valida mas o extra do Intent vem de fora e o texto
//     vai parar numa busca; a página sem o campo ou sem o setBusca devolve false sem lançar e sem tocar em nada; e o
//     setBusca que quebra vira só o rótulo `abrirOS` ao nativo.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';
import { criarJanela, ENDERECO_APP } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, ABRIR_OS, VOLTAR, PUSH } = require('../www/casca/inicio.js');
const CODIGO = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'www', 'casca', 'inicio.js'), 'utf8');

// A chave da pendência no sessionStorage (dura a partida, atravessa as recargas da mesma WebView).
const CHAVE = 'gc_casca_os_pendente';

// O relógio da janela (o script lê o win.Date, como lê o win.setTimeout), parado num dia: o teste não depende da hora em
// que roda, e a virada do dia é trocar a classe.
function diaFixo(ano, mes, dia) {
  const agora = new Date(ano, mes - 1, dia, 10, 0, 0).getTime();
  return class extends Date {
    constructor(...a) {
      if (a.length) super(...a);
      else super(agora);
    }
    static now() { return agora; }
  };
}
const HOJE = '2026-10-18';

// A janela do App com as duas telas do campo.html (`#s-login` e `#s-app`, a da frente com o .on; `tela` diz qual), o
// campo de busca (`<input id="busca-os">`, dentro da tela do App, como no site) e o `setBusca` de topo da página, que
// anota o que recebeu e o que o campo já tinha naquela hora. Cada peça pode faltar. `chamadas` junta, na ordem, o
// setBusca, as funções de topo postas por `funcao` e os cliques dos elementos com `rotulo`.
function montar({ semCampo = false, semBusca = false, busca = null, tela = 'app', semSessao = false, Data = diaFixo(2026, 10, 18), ...opcoes } = {}) {
  const j = criarJanela(opcoes);
  j.win.Date = Data;
  // O sessionStorage negado (SecurityError ao ler a propriedade), já antes do script de início.
  if (semSessao) Object.defineProperty(j.win, 'sessionStorage', { get() { throw new DOMException('negado', 'SecurityError'); } });
  const casca = instalar(j.win, j.canal, j.retrato);
  const doc = j.win.document;
  const chamadas = [];
  const buscas = [];
  const login = doc.createElement('div');
  login.id = 's-login';
  login.className = tela === 'login' ? 'scr on' : 'scr';
  doc.body.appendChild(login);
  const app = doc.createElement('div');
  app.id = 's-app';
  app.className = tela === 'app' ? 'scr on' : 'scr';
  doc.body.appendChild(app);
  let campo = null;
  if (!semCampo) {
    campo = doc.createElement('input');
    campo.id = 'busca-os';
    app.appendChild(campo);
  }
  if (!semBusca) {
    j.win.setBusca = busca || function (v) {
      chamadas.push('setBusca:' + v);
      buscas.push({ v, noCampo: campo ? campo.value : null });
    };
  }
  function elemento(tag, { id, classes, dentro, rotulo, acao } = {}) {
    const el = doc.createElement(tag);
    if (id) el.id = id;
    if (classes) el.className = classes;
    if (rotulo) el.addEventListener('click', () => { chamadas.push(rotulo); if (acao) acao(); });
    (dentro || doc.body).appendChild(el);
    return el;
  }
  function funcao(nome, acao) {
    j.win[nome] = function (...args) {
      chamadas.push([nome, ...args].join(':'));
      return acao ? acao.apply(this, args) : undefined;
    };
  }
  const abrir = (os) => j.win.__gcCascaAbrirOS(os);
  const erros = () => j.enviados.filter((m) => m.t === 'erro');
  // O que foi aberto de fato, pelo aviso ao nativo.
  const abertas = () => j.enviados.filter((m) => m.t === 'osAberta').map((m) => m.os);
  const pendente = () => {
    const texto = j.win.sessionStorage.getItem(CHAVE);
    return texto === null ? null : JSON.parse(texto);
  };
  // O Entrar que deu certo (o entrarComIdentidade do site): a tela de login sai e a do App entra.
  const entrar = () => { login.classList.remove('on'); app.classList.add('on'); };
  return { ...j, casca, doc, campo, buscas, chamadas, elemento, funcao, abrir, erros, abertas, pendente, entrar, app, login };
}

// A carga seguinte na mesma partida (o loginRedirect, o "Tentar de novo"): o localStorage e o sessionStorage de antes.
function recarregar(j, opcoes = {}) {
  return montar({
    ls: Object.fromEntries(j.win.localStorage._itens),
    sessao: Object.fromEntries(j.win.sessionStorage._itens),
    ...opcoes,
  });
}

// A OS aberta como o abrirModal do campo.html a deixa (o mesmo do testes/voltar.test.mjs): #modal.open com o #m-body e o
// botão Fechar dele.
function abrirOs(j) {
  const modal = j.elemento('div', { id: 'modal', classes: 'open' });
  const corpo = j.elemento('div', { id: 'm-body', dentro: modal });
  j.funcao('fecharModal', () => modal.classList.remove('open'));
  j.elemento('button', { classes: 'm-close-btn', dentro: corpo, rotulo: 'm-close-btn', acao: () => j.win.fecharModal() });
  return modal;
}

// A tela de uma aba (a de Tarefas não tem tela com .on), ativa ou não, e o setTab da página, que tira o .on das telas das
// abas (as telas de login e do App não são abas).
const tela = (j, id, ativa = true) => j.elemento('div', { id, classes: ativa ? 'on' : '', dentro: j.app });
function comSetTab(j) {
  j.funcao('setTab', () => { for (const el of [...j.doc.querySelectorAll('[id^="tela-"].on')]) el.classList.remove('on'); });
}

// A aba Ronda numa etapa, como o renderRonda do campo.html (v284) a desenha: o .rd-voltar no cabeçalho de TODAS as etapas,
// inclusive a Chegada antes de começar. `ronda` é o `let RONDA` do site (o rascunho da ronda), lido pelo nome; sem ele, a
// casca não sabe se a ronda começou.
function rondaNaTela(j, { ronda, ativa = true } = {}) {
  const el = tela(j, 'tela-ronda', ativa);
  const voltar = j.elemento('button', { classes: 'rd-voltar', dentro: el, rotulo: 'rd-voltar' });
  if (ronda !== undefined) j.win.RONDA = ronda;
  return { el, voltar };
}
// O rondaVazia() do site (o rascunho da ronda que ainda não começou), só com os campos que o critério lê.
const rondaVazia = () => ({ usina: '', respostas: {}, dispersos: {}, etapa: 0, finalizadaNoServidor: undefined });

// ---------------------------------------------------------------------------------------------------------------
// A regra do dono: o toque nunca interrompe o técnico
// ---------------------------------------------------------------------------------------------------------------

test('livre: abre na hora, troca para Tarefas e busca', () => {
  // Noutra aba (a Sincronização), sem nada aberto: troca para Tarefas pelo setTab da página, e só depois preenche e busca.
  const j = montar();
  comSetTab(j);
  const sync = tela(j, 'tela-sync');
  tela(j, 'tela-ronda', false);
  assert.equal(j.abrir('4521'), 'aberta');
  assert.deepEqual(j.chamadas, ['setTab:tarefas', 'setBusca:4521']);
  assert.deepEqual(j.buscas, [{ v: '4521', noCampo: '4521' }], 'o campo já estava preenchido quando o setBusca rodou');
  assert.equal(sync.classList.contains('on'), false);
  assert.equal(j.pendente(), null, 'aberta na hora: nada pendente');
  assert.equal(j.timersAtivos(), 0, 'e nada agendado');
  assert.deepEqual(j.enviados, [{ t: 'osAberta', os: '4521' }], 'o nativo fica sabendo que abriu (e para de repetir)');

  // Já em Tarefas (nenhuma tela de aba com .on): sem setTab, que só troca quando não é a aba atual.
  const t = montar();
  comSetTab(t);
  tela(t, 'tela-sync', false);
  assert.equal(t.abrir('4521'), 'aberta');
  assert.deepEqual(t.chamadas, ['setBusca:4521']);

  // A Ronda sem etapa na tela (sem o checklist baixado, ou a de hoje já finalizada: sem o .rd-voltar), a Zeladoria na
  // lista dos serviços e as outras abas não seguram.
  for (const id of ['tela-ronda', 'tela-zl', 'tela-conquistas', 'tela-acomp']) {
    const k = montar();
    comSetTab(k);
    tela(k, id);
    assert.equal(k.abrir('77'), 'aberta', id);
    assert.deepEqual(k.chamadas, ['setTab:tarefas', 'setBusca:77'], id);
  }

  // Sem o setTab na página (o site trocou o nome), noutra aba: a busca vai assim mesmo (o contrato diário avisa o dono).
  const s = montar();
  tela(s, 'tela-sync');
  assert.equal(s.abrir('4521'), 'aberta');
  assert.deepEqual(s.chamadas, ['setBusca:4521']);
});

test('com a OS aberta: fica pendente, nada é fechado', () => {
  const j = montar();
  comSetTab(j);
  const sync = tela(j, 'tela-sync'); // a OS aberta por cima de outra aba
  const modal = abrirOs(j);
  const pagina = j.doc.documentElement.outerHTML;
  assert.equal(j.abrir('4521'), 'pendente');
  assert.equal(j.doc.documentElement.outerHTML, pagina, 'nada muda na página: nem camada fechada, nem tela nova');
  assert.deepEqual(j.chamadas, [], 'nem o Fechar da OS, nem o setTab, nem a busca');
  assert.ok(modal.classList.contains('open'), 'a OS continua aberta');
  assert.ok(sync.classList.contains('on'), 'a aba não foi trocada');
  assert.equal(j.campo.value, '', 'o campo de busca não foi tocado');
  assert.deepEqual(j.pendente(), { os: '4521', dia: HOJE });
  assert.equal(j.timersAtivos(), 1, 'a conferência de 2 s');
  j.avancar(10000);
  assert.deepEqual(j.chamadas, [], 'com a OS aberta, a conferência não faz nada');
  assert.equal(j.doc.documentElement.outerHTML, pagina, 'nem nas conferências');
  assert.deepEqual(j.enviados, [], 'pendente não é erro, nem aberta: o nativo segue com a OS');
});

test('na tela de login: fica pendente, atravessa o loginRedirect e abre com a tela do App na frente', () => {
  // A partida a frio (o app tirado dos recentes, ou morto pelo Android, que é o caso de quem recebe a notificação) cai na
  // tela de login: online não há entrada automática, e o Entrar vai ao loginRedirect, que recarrega a página. A busca
  // posta ali, por trás do login, se perdia na recarga.
  const j = montar({ tela: 'login' });
  comSetTab(j);
  assert.equal(j.abrir('4521'), 'pendente');
  assert.deepEqual(j.chamadas, [], 'nem o setTab nem a busca por trás do login');
  assert.equal(j.campo.value, '');
  assert.deepEqual(j.pendente(), { os: '4521', dia: HOJE });
  j.avancar(6000);
  assert.deepEqual(j.chamadas, [], 'na tela de login, a conferência espera');
  assert.deepEqual(j.abertas(), []);

  // O Entrar: o loginRedirect sai da página e a volta do login é uma carga nova, ainda na tela de login (a troca do código
  // pela rede e o /me do site levam uns segundos). O sessionStorage atravessa a recarga na mesma aba.
  const volta = recarregar(j, { tela: 'login' });
  comSetTab(volta);
  assert.equal(volta.timersAtivos(), 1, 'a carga nova retoma a conferência');
  volta.avancar(4000);
  assert.deepEqual(volta.chamadas, []);
  assert.deepEqual(volta.pendente(), { os: '4521', dia: HOJE });
  // O nativo entrega a mesma OS de novo nesta carga (ele repete até ouvir o osAberta): continua pendente, uma só.
  assert.equal(volta.abrir('4521'), 'pendente');
  assert.equal(volta.timersAtivos(), 1);
  // O entrarComIdentidade liga a tela do App: a conferência seguinte abre, uma vez.
  volta.entrar();
  volta.avancar(2000);
  assert.deepEqual(volta.chamadas, ['setBusca:4521']);
  assert.equal(volta.campo.value, '4521');
  assert.deepEqual(volta.abertas(), ['4521']);
  assert.equal(volta.pendente(), null);
  assert.equal(volta.timersAtivos(), 0);
  volta.avancar(10000);
  assert.deepEqual(volta.chamadas, ['setBusca:4521'], 'uma vez só');

  // Logado, mas com uma camada aberta por cima do App: segura como sempre.
  const k = montar({ tela: 'login' });
  const modal = abrirOs(k);
  assert.equal(k.abrir('9'), 'pendente');
  k.entrar();
  k.avancar(4000);
  assert.deepEqual(k.chamadas, [], 'o App na frente, mas a OS aberta');
  modal.classList.remove('open');
  k.avancar(2000);
  assert.deepEqual(k.chamadas, ['setBusca:9']);

  // A página sem a tela do App (o site trocou o id): a OS nunca abre por trás de uma tela que a casca não vê, e o
  // contrato diário avisa.
  const s = montar();
  s.app.id = 'outra-tela';
  assert.equal(s.abrir('9'), 'pendente');
  s.avancar(10000);
  assert.deepEqual(s.chamadas, []);
});

test('a mesma OS entregue de novo na mesma carga não reabre; o nativo ouve o osAberta outra vez', () => {
  // O nativo repete a OS a cada carga até ouvir o osAberta, e o técnico pode tocar duas vezes na mesma notificação antes de
  // ela sair da barra. Reabrir puxaria a busca de novo por baixo do que ele abriu depois.
  const j = montar();
  assert.equal(j.abrir('4521'), 'aberta');
  const modal = abrirOs(j); // ele abriu a OS que a busca trouxe
  assert.equal(j.abrir('4521'), 'aberta', 'já aberta nesta carga');
  assert.deepEqual(j.chamadas, ['setBusca:4521'], 'uma busca só');
  assert.equal(j.pendente(), null, 'e não vira pendência');
  assert.equal(j.timersAtivos(), 0);
  assert.deepEqual(j.abertas(), ['4521', '4521']);
  assert.ok(modal.classList.contains('open'));
  // Outra OS, com ele na OS: pendente, como sempre.
  assert.equal(j.abrir('77'), 'pendente');

  // A carga seguinte não sabe da anterior: a OS que o nativo ainda repete abre de novo (melhor duas vezes que nenhuma).
  const k = recarregar(j);
  assert.equal(k.abrir('4521'), 'aberta');
});

test('com um serviço da Zeladoria aberto: fica pendente; na lista dos serviços, não', () => {
  // O serviço aberto (o .zl-volta, que o site só desenha dentro de um serviço) é preenchimento, como a OS e a ronda.
  const j = montar();
  comSetTab(j);
  const zl = tela(j, 'tela-zl');
  const volta = j.elemento('button', { classes: 'zl-volta', dentro: zl, rotulo: 'zl-volta' });
  assert.equal(j.abrir('4521'), 'pendente');
  assert.deepEqual(j.chamadas, [], 'nem o voltar dela, nem o setTab, nem a busca');
  assert.ok(zl.classList.contains('on'));
  j.avancar(4000);
  assert.deepEqual(j.chamadas, []);
  volta.remove(); // ele voltou à lista dos serviços
  j.avancar(2000);
  assert.deepEqual(j.chamadas, ['setTab:tarefas', 'setBusca:4521']);
});

test('toda camada da pilha do voltar segura a OS do toque, mesmo a que o voltar não saberia fechar', () => {
  // A mesma lista da seção 7 (o voltar), sem fechar nada. As camadas vão aqui sem a função de fechar: o voltar não as
  // fecharia (devolveria false), mas elas estão abertas, e o técnico está nelas.
  const camadas = [
    ['a camada da casca', (j) => j.elemento('div', { id: 'gc-casca-aviso' })],
    ['a foto ampliada', (j) => j.elemento('div', { classes: 'rd-lb' })],
    ['a câmera', (j) => j.elemento('div', { id: 'cam' })],
    ['a espera do chamado', (j) => j.elemento('div', { id: 'cham-wait' })],
    ['o GPS exigido', (j) => j.elemento('div', { id: 'geo-exige' })],
    ['a sonda da câmera', (j) => j.elemento('div', { id: 'sonda', classes: 'open' })],
    ['a APR', (j) => j.elemento('div', { id: 'apr-modal', classes: 'open' })],
    ['os Extintores', (j) => j.elemento('div', { id: 'ext-tela', classes: 'open' })],
    ['a fora da programação', (j) => j.elemento('div', { id: 'fora-modal', classes: 'open' })],
    ['o Ver', (j) => j.elemento('div', { id: 'ver-modal', classes: 'open' })],
    ['o Concluir', (j) => j.elemento('div', { id: 'sheet', classes: 'open' })],
    ['o feedback', (j) => j.elemento('div', { id: 'fb-sheet', classes: 'open' })],
    ['a OS', (j) => j.elemento('div', { id: 'modal', classes: 'open' })],
    ['o menu do usuário', (j) => j.elemento('div', { id: 'user-menu', classes: 'open' })],
  ];
  for (const [nome, abrirCamada] of camadas) {
    const j = montar();
    comSetTab(j);
    abrirCamada(j);
    assert.equal(j.abrir('4521'), 'pendente', nome);
    assert.deepEqual(j.chamadas, [], nome);
    assert.equal(j.campo.value, '', nome);
  }
});

test('com a ronda começada (o critério do voltar da Ronda no site): fica pendente', () => {
  // A ronda começada é a do rdVoltar do campo.html: a usina escolhida, uma resposta ou um disperso, e não finalizada no
  // servidor. Nem o Voltar dela, nem o setTab, nem a busca.
  const comecadas = {
    'a usina escolhida': { ...rondaVazia(), usina: 'Usina Ficticia' },
    'uma resposta': { ...rondaVazia(), respostas: { item1: 'ok' } },
    'um disperso': { ...rondaVazia(), dispersos: { d1: { x: 1 } } },
  };
  for (const [nome, ronda] of Object.entries(comecadas)) {
    const j = montar();
    comSetTab(j);
    const { el } = rondaNaTela(j, { ronda });
    assert.equal(j.abrir('4521'), 'pendente', nome);
    assert.deepEqual(j.chamadas, [], nome);
    assert.ok(el.classList.contains('on'), 'continua na Ronda: ' + nome);
    assert.deepEqual(j.pendente(), { os: '4521', dia: HOJE }, nome);
    j.avancar(10000);
    assert.deepEqual(j.chamadas, [], nome);
  }

  // A aba aberta na Chegada e ele escolhe a usina: a ronda começou, e a OS que chega depois espera.
  const c = montar();
  comSetTab(c);
  const { el } = rondaNaTela(c, { ronda: rondaVazia() });
  c.win.RONDA.usina = 'Usina Ficticia';
  assert.equal(c.abrir('5'), 'pendente');
  c.avancar(4000);
  assert.deepEqual(c.chamadas, []);
  // Ele termina e volta a Tarefas (o .on sai da aba): abre.
  el.classList.remove('on');
  c.avancar(2000);
  assert.deepEqual(c.chamadas, ['setBusca:5']);

  // A seção da ronda deixada aberta numa aba que não é a atual (ele já está em Tarefas): ela não está no caminho, e a OS
  // abre ali, sem trocar de aba nem tocar na ronda.
  const k = montar();
  comSetTab(k);
  rondaNaTela(k, { ronda: { ...rondaVazia(), usina: 'Usina Ficticia' }, ativa: false });
  assert.equal(k.abrir('4521'), 'aberta');
  assert.deepEqual(k.chamadas, ['setBusca:4521']);
});

test('a aba Ronda aberta na Chegada, sem a ronda começar, não segura; a finalizada também não', () => {
  // O site desenha o .rd-voltar em toda etapa, até na Chegada antes de começar: só ele não diz que a ronda começou.
  for (const [nome, ronda] of [
    ['a Chegada (o rascunho vazio)', rondaVazia()],
    ['a ronda ainda não aberta nesta carga (o let RONDA nulo)', null],
    ['a finalizada no servidor, com a usina', { ...rondaVazia(), usina: 'Usina Ficticia', respostas: { a: 1 }, finalizadaNoServidor: true }],
  ]) {
    const j = montar();
    comSetTab(j);
    rondaNaTela(j, { ronda });
    assert.equal(j.abrir('77'), 'aberta', nome);
    assert.deepEqual(j.chamadas, ['setTab:tarefas', 'setBusca:77'], nome);
  }
  // Sem como ler a ronda do site (o nome trocado): a aba numa etapa segura, pelo lado de não interromper.
  const s = montar();
  comSetTab(s);
  rondaNaTela(s);
  assert.equal(s.abrir('77'), 'pendente');
  assert.deepEqual(s.chamadas, []);
});

test('pendente abre sozinha quando a camada fecha', () => {
  const j = montar();
  comSetTab(j);
  const sync = tela(j, 'tela-sync');
  const modal = abrirOs(j);
  assert.equal(j.abrir('4521'), 'pendente');
  j.avancar(2000);
  assert.deepEqual(j.chamadas, [], 'ainda na OS');
  modal.classList.remove('open'); // ele terminou e fechou a OS
  j.avancar(1999);
  assert.deepEqual(j.chamadas, [], 'a conferência é a cada 2 s');
  j.avancar(1);
  assert.deepEqual(j.chamadas, ['setTab:tarefas', 'setBusca:4521']);
  assert.equal(j.campo.value, '4521');
  assert.equal(sync.classList.contains('on'), false, 'foi para Tarefas');
  assert.equal(j.pendente(), null, 'aberta, a pendência sai');
  assert.equal(j.timersAtivos(), 0, 'e a conferência para');
  assert.deepEqual(j.abertas(), ['4521'], 'aberta pela pendência: o nativo fica sabendo');
  j.avancar(10000);
  assert.deepEqual(j.chamadas, ['setTab:tarefas', 'setBusca:4521'], 'uma vez só');
  assert.deepEqual(j.abertas(), ['4521']);

  // A ronda que termina (o .rd-voltar sai da tela, com a de hoje finalizada): abre, em Tarefas.
  const k = montar();
  comSetTab(k);
  const { voltar: rd } = rondaNaTela(k, { ronda: { ...rondaVazia(), usina: 'Usina Ficticia' } });
  assert.equal(k.abrir('88'), 'pendente');
  rd.remove();
  k.avancar(2000);
  assert.deepEqual(k.chamadas, ['setTab:tarefas', 'setBusca:88']);

  // Uma camada por cima da outra: o Ver fecha, mas a OS embaixo ainda segura; só com as duas fechadas a OS do toque abre.
  const v = montar();
  const os = abrirOs(v);
  const ver = v.elemento('div', { id: 'ver-modal', classes: 'open' });
  assert.equal(v.abrir('5'), 'pendente');
  ver.classList.remove('open');
  v.avancar(2000);
  assert.deepEqual(v.chamadas, []);
  os.classList.remove('open');
  v.avancar(2000);
  assert.deepEqual(v.chamadas, ['setBusca:5']);
});

test('a pendência atravessa a recarga da página na mesma sessão', () => {
  // A página recarregou com a OS pendente (a volta do loginRedirect, o "Tentar de novo"): o sessionStorage é o mesmo, e a
  // carga nova retoma a conferência sozinha, sem esperar o nativo entregar de novo.
  const j = montar({ sessao: { [CHAVE]: JSON.stringify({ os: '4521', dia: HOJE }) } });
  assert.equal(j.timersAtivos(), 1, 'a carga retoma a conferência');
  j.avancar(2000);
  assert.deepEqual(j.chamadas, ['setBusca:4521']);
  assert.equal(j.pendente(), null);
  assert.equal(j.timersAtivos(), 0);

  // A página ainda montando (sem o campo e sem o setBusca): a conferência espera, sem perder a pendência.
  const k = montar({ semCampo: true, semBusca: true, sessao: { [CHAVE]: JSON.stringify({ os: '4521', dia: HOJE }) } });
  k.avancar(6000);
  assert.deepEqual(k.pendente(), { os: '4521', dia: HOJE });
  assert.equal(k.timersAtivos(), 1);
  const campo = k.elemento('input', { id: 'busca-os' });
  k.funcao('setBusca');
  k.avancar(2000);
  assert.deepEqual(k.chamadas, ['setBusca:4521']);
  assert.equal(campo.value, '4521');
  assert.equal(k.timersAtivos(), 0);
});

test('pendência de outro dia é descartada', () => {
  // De ontem, numa carga de hoje: sai do sessionStorage já na carga, sem conferência e sem abrir nada.
  const j = montar({ sessao: { [CHAVE]: JSON.stringify({ os: '4521', dia: '2026-10-17' }) } });
  assert.equal(j.win.sessionStorage.getItem(CHAVE), null);
  assert.equal(j.timersAtivos(), 0);
  j.avancar(10000);
  assert.deepEqual(j.chamadas, []);

  // O que não é uma pendência nossa (texto torto, sem o dia, OS que não é número): sai também.
  const lixos = [
    '{', 'null', '"4521"', JSON.stringify({ os: '4521' }), JSON.stringify({ os: 4521, dia: HOJE }),
    JSON.stringify({ os: "1'); fecharTudo('", dia: HOJE }),
  ];
  for (const lixo of lixos) {
    const k = montar({ sessao: { [CHAVE]: lixo } });
    assert.equal(k.win.sessionStorage.getItem(CHAVE), null, lixo);
    assert.equal(k.timersAtivos(), 0, lixo);
    k.avancar(10000);
    assert.deepEqual(k.chamadas, [], lixo);
  }

  // A meia-noite passou com a OS pendente (ele ficou na OS): na primeira conferência do dia seguinte ela sai, sem abrir,
  // e a conferência para.
  const m = montar();
  comSetTab(m);
  const modal = abrirOs(m);
  assert.equal(m.abrir('4521'), 'pendente');
  m.win.Date = diaFixo(2026, 10, 19);
  modal.classList.remove('open');
  m.avancar(2000);
  assert.deepEqual(m.chamadas, []);
  assert.equal(m.pendente(), null);
  assert.equal(m.timersAtivos(), 0);
});

test('a conferência de 2 s só roda com pendência', () => {
  const j = montar();
  comSetTab(j);
  assert.equal(j.timersAtivos(), 0, 'sem pendência, nada agendado na carga');
  assert.equal(j.abrir('1'), 'aberta');
  assert.equal(j.timersAtivos(), 0, 'aberta na hora: nada agendado');

  const modal = abrirOs(j);
  assert.equal(j.abrir('2'), 'pendente');
  assert.equal(j.timersAtivos(), 1);
  assert.equal(j.abrir('3'), 'pendente');
  assert.equal(j.timersAtivos(), 1, 'um pedido novo não abre outra conferência');
  for (let i = 0; i < 5; i++) {
    j.avancar(2000);
    assert.equal(j.timersAtivos(), 1, 'uma conferência de cada vez: ' + i);
  }
  modal.classList.remove('open');
  j.avancar(2000);
  assert.equal(j.timersAtivos(), 0, 'aberta, a conferência para');
  assert.deepEqual(j.chamadas, ['setBusca:1', 'setBusca:3']);

  // A pendência que some por fora (a página limpou o sessionStorage no "limpar só o login"): a conferência para sem abrir.
  const k = montar();
  const m = abrirOs(k);
  assert.equal(k.abrir('4521'), 'pendente');
  k.win.sessionStorage.removeItem(CHAVE);
  m.classList.remove('open');
  k.avancar(2000);
  assert.deepEqual(k.chamadas, []);
  assert.equal(k.timersAtivos(), 0);
});

test('a última emergência vence', () => {
  // Duas emergências com ele na OS: a segunda substitui a primeira, e só ela abre, uma vez.
  const j = montar();
  const modal = abrirOs(j);
  assert.equal(j.abrir('111'), 'pendente');
  assert.equal(j.abrir('222'), 'pendente');
  assert.deepEqual(j.pendente(), { os: '222', dia: HOJE });
  modal.classList.remove('open');
  j.avancar(12000);
  assert.deepEqual(j.chamadas, ['setBusca:222']);

  // A pendente esperando e uma emergência nova com ele já livre: a nova abre na hora, e a velha não vem depois.
  const k = montar();
  const m = abrirOs(k);
  assert.equal(k.abrir('111'), 'pendente');
  m.classList.remove('open');
  assert.equal(k.abrir('333'), 'aberta');
  assert.equal(k.pendente(), null);
  assert.equal(k.timersAtivos(), 0, 'sem pendência, a conferência já parou');
  k.avancar(10000);
  assert.deepEqual(k.chamadas, ['setBusca:333']);
});

test('sem onde guardar a pendência (o sessionStorage cheio ou negado), ela fica na página até a recarga', () => {
  // A emergência não some porque o sessionStorage recusou: a pendência fica numa variável da página, e a conferência a
  // abre quando ele ficar livre, nesta carga.
  const j = montar();
  const modal = abrirOs(j);
  j.win.sessionStorage._cota = 0; // toda gravação estoura a cota
  assert.equal(j.abrir('4521'), 'pendente');
  assert.equal(j.pendente(), null, 'o sessionStorage não guardou');
  assert.equal(j.timersAtivos(), 1, 'a conferência');
  j.avancar(4000);
  assert.deepEqual(j.chamadas, []);
  modal.classList.remove('open');
  j.avancar(2000);
  assert.deepEqual(j.chamadas, ['setBusca:4521']);
  assert.deepEqual(j.abertas(), ['4521']);
  assert.equal(j.timersAtivos(), 0);

  // A pendência velha de uma carga anterior e a gravação nova que falha: a velha sai antes, e a última vence.
  const k = montar({ sessao: { [CHAVE]: JSON.stringify({ os: '111', dia: HOJE }) } });
  const m = abrirOs(k);
  k.win.sessionStorage._cota = 0;
  assert.equal(k.abrir('222'), 'pendente');
  assert.equal(k.pendente(), null, 'a velha não fica no sessionStorage para vir depois');
  m.classList.remove('open');
  k.avancar(10000);
  assert.deepEqual(k.chamadas, ['setBusca:222'], 'só a nova, uma vez');

  // Sem sessionStorage nenhum (negado: ler a propriedade lança), a variável também segura.
  const s = montar({ semSessao: true });
  const om = abrirOs(s);
  assert.equal(s.abrir('9'), 'pendente');
  om.classList.remove('open');
  s.avancar(2000);
  assert.deepEqual(s.chamadas, ['setBusca:9']);
});

test('no Sair, a OS pendente sai: a conta seguinte não recebe a emergência da anterior', { timeout: 10000 }, async () => {
  // O aparelho dividido: o técnico A, numa OS, toca a emergência (fica pendente) e sai pelo menu; o B entra no mesmo
  // aparelho no mesmo dia. O sair do site não limpa o sessionStorage, e a conferência abriria a OS de A para B.
  const j = montar();
  j.win.sair = async function () { return 'saiu'; };
  j.win.dispatchEvent({ type: 'load' }); // o sair da página é embrulhado no load
  const modal = abrirOs(j);
  assert.equal(j.abrir('4521'), 'pendente');
  assert.equal(await j.win.sair(), 'saiu');
  assert.equal(j.pendente(), null, 'a pendência saiu do sessionStorage');
  modal.classList.remove('open');
  j.avancar(10000);
  assert.deepEqual(j.chamadas, [], 'e a conferência não abre mais nada');
  const k = recarregar(j);
  k.avancar(10000);
  assert.deepEqual(k.chamadas, [], 'nem na carga seguinte');

  // A pendência só na variável (o sessionStorage cheio) também sai.
  const v = montar();
  v.win.sair = async function () { return 'saiu'; };
  v.win.dispatchEvent({ type: 'load' });
  const om = abrirOs(v);
  v.win.sessionStorage._cota = 0;
  assert.equal(v.abrir('77'), 'pendente');
  await v.win.sair();
  om.classList.remove('open');
  v.avancar(10000);
  assert.deepEqual(v.chamadas, []);
});

// ---------------------------------------------------------------------------------------------------------------
// A busca e o número
// ---------------------------------------------------------------------------------------------------------------

test('preenche o campo de busca e chama o setBusca com o número, nessa ordem', () => {
  const j = montar();
  assert.equal(j.abrir('12345'), 'aberta');
  assert.equal(j.campo.value, '12345');
  assert.deepEqual(j.buscas, [{ v: '12345', noCampo: '12345' }], 'o campo já estava preenchido quando o setBusca rodou');
  assert.deepEqual(j.erros(), []);

  // Outra OS no lugar da primeira: o campo troca, sem acumular.
  assert.equal(j.abrir('7'), 'aberta');
  assert.equal(j.campo.value, '7');
  assert.deepEqual(j.buscas.map((b) => b.v), ['12345', '7']);
});

test('a função não é enumerável, existe em toda carga e não manda nada ao nativo', () => {
  const j = montar();
  assert.equal(typeof j.win.__gcCascaAbrirOS, 'function');
  assert.equal(Object.keys(j.win).includes('__gcCascaAbrirOS'), false, 'o que da página varre a janela não a vê');
  assert.deepEqual(j.enviados, []);
  assert.equal(j.timersAtivos(), 0, 'nada agendado sem pendência: quem espera a página carregar é o nativo');
});

test('sem o setBusca ou sem o campo devolve false, sem lançar e sem tocar em nada', () => {
  // A página ainda montando (o campo existe, mas o script dela ainda não definiu o setBusca), ou uma versão do site que
  // trocou os nomes: o app abre normalmente, sem busca, e o contrato diário avisa o dono. Não é erro da casca: nada vai ao
  // nativo, e o nativo entrega a OS de novo na carga seguinte (ele repete até ouvir o osAberta).
  const semBusca = montar({ semBusca: true });
  assert.equal(semBusca.abrir('12345'), false);
  assert.equal(semBusca.campo.value, '', 'sem poder buscar, o campo fica como estava');

  const semCampo = montar({ semCampo: true });
  assert.equal(semCampo.abrir('12345'), false);
  assert.deepEqual(semCampo.buscas, [], 'sem o campo, nem o setBusca roda');

  const nenhum = montar({ semBusca: true, semCampo: true });
  assert.equal(nenhum.abrir('12345'), false);

  // O setBusca que existe mas não é função (o nome virou outra coisa na página).
  const naoEFuncao = montar({ semBusca: true });
  naoEFuncao.win.setBusca = 'texto';
  assert.equal(naoEFuncao.abrir('12345'), false);
  assert.equal(naoEFuncao.campo.value, '');

  // Ocupado e sem o setBusca: também false, sem pendência (a busca não teria como sair depois).
  const ocupado = montar({ semBusca: true });
  abrirOs(ocupado);
  assert.equal(ocupado.abrir('12345'), false);
  assert.equal(ocupado.pendente(), null);

  for (const j of [semBusca, semCampo, nenhum, naoEFuncao, ocupado]) {
    assert.deepEqual(j.enviados, []);
    assert.equal(j.timersAtivos(), 0);
  }
});

test('a janela sem document devolve false sem lançar', () => {
  const j = montar();
  delete j.win.document;
  assert.equal(j.abrir('12345'), false);
  assert.deepEqual(j.buscas, []);
});

test('aspas e letras não passam: o script ainda confere os dígitos, mesmo com a OS já validada pelo nativo', () => {
  const j = montar();
  const recusadas = [
    "1'); fecharTudo('", '12a', 'abc', '', ' 12', '12 ', '12\n', '\n12', '1.5', '-1', '+1', '0x1F', '1e3',
    '1'.repeat(13),
    '１２３',        // dígitos de largura total
    '١٢٣',           // dígitos arábico-índicos
    '12\u0000',
  ];
  for (const os of recusadas) {
    assert.equal(j.abrir(os), false, JSON.stringify(os));
  }
  // Só texto: o número, o nulo e o objeto que vira texto não entram (o nativo manda sempre um texto entre aspas).
  for (const os of [12345, null, undefined, true, ['12'], { toString: () => '12' }]) {
    assert.equal(j.abrir(os), false, String(typeof os));
  }
  assert.deepEqual(j.buscas, [], 'nenhuma busca foi feita');
  assert.equal(j.campo.value, '', 'o campo não foi tocado');
  assert.deepEqual(j.enviados, [], 'a OS recusada não é erro da página');

  // Ocupado, a OS recusada também não vira pendência.
  const modal = abrirOs(j);
  assert.equal(j.abrir("1'); fecharTudo('"), false);
  assert.equal(j.pendente(), null);
  modal.classList.remove('open');

  // E as bordas que valem: de 1 a 12 dígitos.
  assert.equal(j.abrir('1'), 'aberta');
  assert.equal(j.abrir('1'.repeat(12)), 'aberta');
  assert.deepEqual(j.buscas.map((b) => b.v), ['1', '1'.repeat(12)]);
});

test('o setBusca que lança: devolve false e avisa o nativo só com o rótulo abrirOS', () => {
  const quebra = () => { throw new Error('quebrou na OS 12345 de tecnico.ficticio@exemplo.com.br'); };
  const j = montar({ busca: quebra });
  assert.equal(j.abrir('12345'), false);
  const erros = j.erros();
  assert.equal(erros.length, 1);
  assert.equal(erros[0].onde, 'abrirOS');

  // Na conferência: a pendente cujo setBusca lança sai da pendência (não se repete a cada 2 s) e avisa uma vez.
  const k = montar({ busca: quebra });
  const modal = abrirOs(k);
  assert.equal(k.abrir('4521'), 'pendente');
  modal.classList.remove('open');
  k.avancar(10000);
  assert.deepEqual(k.erros().map((m) => m.onde), ['abrirOS']);
  assert.equal(k.pendente(), null);
  assert.equal(k.timersAtivos(), 0);

  // A conferência que quebra antes da busca (a página que lança ao procurar a camada): também sai da pendência, e o
  // aviso vai uma vez só.
  const q = montar();
  abrirOs(q);
  assert.equal(q.abrir('4521'), 'pendente');
  q.doc.getElementById = () => { throw new Error('quebrou com tecnico.ficticio@exemplo.com.br'); };
  q.avancar(10000);
  assert.deepEqual(q.erros().map((m) => m.onde), ['abrirOS']);
  assert.equal(q.pendente(), null);
  assert.equal(q.timersAtivos(), 0);
});

test('no navegador: o function setBusca de topo da página é achado pelo nome na janela', () => {
  // O campo.html declara `function setBusca(v)` num script clássico, e isso vira propriedade da janela. Aqui o jsdom roda
  // o inicio.js e a página como scripts de verdade, na mesma janela, com o campo escrito como o site o escreve.
  const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {
    url: ENDERECO_APP,
    runScripts: 'dangerously',
    beforeParse(window) {
      window.gcCasca = { postMessage() {} };
      window.__GC_RETRATO__ = { temEspelho: true, filaHashes: [], idbChaves: [] };
      window.localStorage.setItem('gc_casca_sentinela', '1');
    },
  });
  const { window } = dom;
  // A janela fecha mesmo com o teste falhando: com uma OS pendente, a conferência de 2 s é um timer de verdade aqui, e a
  // janela aberta seguraria o processo do teste para sempre.
  try {
    const rodar = (codigo) => {
      const s = window.document.createElement('script');
      s.textContent = codigo;
      window.document.head.appendChild(s);
    };
    rodar(CODIGO);
    window.document.body.innerHTML =
      '<div id="s-login" class="scr"></div><div id="s-app" class="scr on">' +
      '<input id="busca-os" type="search" oninput="setBusca(this.value)"><button id="busca-x" style="display:none"></button>' +
      '<div id="tela-ronda"><button class="rd-voltar">‹ Voltar</button></div></div>' +
      '<div id="modal"></div>';
    assert.equal(window.__gcCascaAbrirOS('4521'), false, 'antes de a página definir o setBusca');
    rodar(`
      let BUSCA = '';
      let RONDA = null;
      function setBusca(v) { BUSCA = String(v || '').trim(); window.buscouPor = BUSCA; document.getElementById('busca-x').style.display = BUSCA ? '' : 'none'; }
    `);
    assert.equal(window.setBusca === undefined, false, 'a função de topo é propriedade da janela');
    assert.equal(window.__gcCascaAbrirOS('4521'), 'aberta');
    assert.equal(window.document.getElementById('busca-os').value, '4521');
    assert.equal(window.buscouPor, '4521');
    assert.equal(window.document.getElementById('busca-x').style.display, '', 'o setBusca da página rodou inteiro');
    assert.equal(Object.keys(window).includes('__gcCascaAbrirOS'), false);

    // A ronda: o `let RONDA` de topo não é propriedade da janela, e o script o lê pelo nome. Na Chegada (o rascunho vazio)
    // não segura; com a usina escolhida, segura.
    window.document.getElementById('tela-ronda').className = 'on';
    rodar("RONDA = { usina: '', respostas: {}, dispersos: {} };");
    assert.equal(window.RONDA, undefined, 'o let não é propriedade da janela');
    assert.equal(window.__gcCascaAbrirOS('31'), 'aberta', 'na Chegada');
    rodar("RONDA.usina = 'Usina Ficticia';");
    assert.equal(window.__gcCascaAbrirOS('32'), 'pendente', 'com a usina escolhida');
    window.document.getElementById('tela-ronda').className = '';
    window.sessionStorage.removeItem(CHAVE);

    // Com a OS aberta, a pendência vai ao sessionStorage de verdade, com o dia de hoje do relógio da janela.
    window.document.getElementById('modal').className = 'open';
    assert.equal(window.__gcCascaAbrirOS('77'), 'pendente');
    const guardada = JSON.parse(window.sessionStorage.getItem(CHAVE));
    const d = new window.Date();
    const hoje = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    assert.deepEqual(guardada, { os: '77', dia: hoje });
  } finally {
    window.close();
  }
});

test('o ABRIR_OS do inicio.js tem os nomes que o contrato com o site exige, e a seção 9 só os usa por ele', () => {
  assert.deepEqual(ABRIR_OS, {
    funcao: { buscar: 'setBusca', aba: 'setTab' },
    id: { busca: 'busca-os', ronda: 'tela-ronda', app: 's-app' },
    classe: { abaAtiva: 'on', rondaEmAndamento: 'rd-voltar' },
    ronda: {
      global: 'RONDA', criterio: 'rdVoltar', usina: 'usina', respostas: 'respostas', dispersos: 'dispersos',
      finalizada: 'finalizadaNoServidor',
    },
    aba: 'tarefas',
  });
  // O que a regra de não interromper lê da página é o que o voltar lê: os mesmos nomes, de um lugar só. A tela do App é a
  // mesma que o registro do aparelho espera (o PUSH).
  assert.equal(ABRIR_OS.funcao.aba, VOLTAR.funcao.aba);
  assert.equal(ABRIR_OS.aba, VOLTAR.abaInicial);
  assert.equal(ABRIR_OS.id.ronda, VOLTAR.id.ronda);
  assert.equal(ABRIR_OS.classe.abaAtiva, VOLTAR.classe.abaAtiva);
  assert.equal(ABRIR_OS.classe.rondaEmAndamento, VOLTAR.classe.rondaVolta);
  assert.equal(ABRIR_OS.id.app, PUSH.id.app);
  // E vêm do VOLTAR e do PUSH no código, não repetidos: o nome trocado num lugar só não pode deixar o outro para trás.
  const objeto = CODIGO.slice(CODIGO.indexOf('  var ABRIR_OS = {'), CODIGO.indexOf('\n  };', CODIGO.indexOf('  var ABRIR_OS = {')));
  for (const ref of ['aba: VOLTAR.funcao.aba', 'ronda: VOLTAR.id.ronda', 'abaAtiva: VOLTAR.classe.abaAtiva',
    'rondaEmAndamento: VOLTAR.classe.rondaVolta', 'aba: VOLTAR.abaInicial', 'app: PUSH.id.app']) {
    assert.ok(objeto.includes(ref), ref);
  }

  const secao = CODIGO.slice(CODIGO.indexOf('// 9. Abrir a OS pelo toque'), CODIGO.indexOf('// Ponto único de entrada'));
  assert.ok(secao.includes('function instalarAbrirOS('), 'o corte pega a seção 9');
  const codigo = secao.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
  for (const usado of ['ABRIR_OS.funcao.buscar', 'ABRIR_OS.id.busca', 'ABRIR_OS.funcao.aba', 'ABRIR_OS.aba',
    'ABRIR_OS.ronda.global', 'ABRIR_OS.ronda.usina', 'ABRIR_OS.ronda.respostas', 'ABRIR_OS.ronda.dispersos',
    'ABRIR_OS.ronda.finalizada']) {
    assert.ok(codigo.includes(usado), 'usado: ' + usado);
  }
  // O "ocupado" é a camada de cima da seção 7, a mesma do voltar, e a tela do App da seção 8: a seção 9 não refaz a lista.
  assert.ok(codigo.includes('camadaDeCima(ctx)'), 'a seção 9 pergunta à seção 7');
  assert.ok(codigo.includes('appNaTela(win)'), 'e à seção 8');
  assert.equal(codigo.match(/VOLTAR\.|getElementById\(VOLTAR|querySelector/g), null, 'nenhuma camada procurada aqui');
  // Nenhum nome do site escrito por fora do objeto: um nome trocado lá ficaria sem o contrato ver. O único sem aspas é o
  // `let` da ronda lido pelo nome, numa linha só, que tem de ser o do objeto.
  assert.equal(codigo.match(/setBusca|busca-os|setTab|tarefas|tela-|rd-voltar|s-app|'modal'|'open'|'on'|(?<!ABRIR_OS\.ronda\.)\b(?:usina|respostas|dispersos)\b|finalizadaNoServidor|rdVoltar/g), null);
  const peloNome = codigo.split('\n').filter((l) => /\bRONDA\b/.test(l));
  assert.equal(peloNome.length, 1, peloNome.join('\n'));
  assert.ok(peloNome[0].includes('typeof ' + ABRIR_OS.ronda.global + ' '), peloNome[0]);
});

// O nativo não roda no JVM com WebView, então a ligação das peças (que a regra pura não vê) se confere no texto do Java:
// o intent da partida tratado uma vez e só sem estado salvo, e a folga contada a partir do fim do carregamento do App.
const ler = (caminho) => readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', caminho), 'utf8');
const semComentarios = (java) => java.replace(/\/\*[\s\S]*?\*\//g, '').split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');

test('a ligação do nativo: a partida trata o intent uma vez e só sem estado salvo, e a folga vem do fim da carga do App', () => {
  const main = semComentarios(ler('android/app/src/main/java/br/com/gridco/campo/MainActivity.java'));
  const plugin = semComentarios(ler('android/app/src/main/java/br/com/gridco/campo/CascaPlugin.java'));
  // O BridgeActivity.load() (Capacitor 8.5.3) chama onNewIntent(getIntent()) dentro do onCreate dele: o onNewIntent daqui
  // ignora essa chamada (a marca `criando`), e quem trata o intent da partida é o onCreate, sem estado salvo. Sem isso, a
  // tela recriada pelo renderer que morreu abriria a OS do toque de novo.
  assert.match(main, /criando = true;\s*try \{\s*super\.onCreate\(savedInstanceState\);\s*\} finally \{\s*criando = false;/);
  assert.match(main, /protected void onNewIntent\(Intent intent\) \{\s*super\.onNewIntent\(intent\);\s*if \(criando\) return;[^}]*abrirOsDoToque\(intent\);/);
  assert.match(main, /if \(savedInstanceState == null && !webViewAntiga\) \{\s*primeiraAbertura\(\);\s*abrirOsDoToque\(getIntent\(\)\);/);
  // A OS vai ao plugin pelo nome dele, e o plugin só a leva à página do App: a folga começa no fim da carga do App e
  // qualquer página que começa a carregar tira o "pronta".
  assert.match(main, /bridge\.getPlugin\(CascaPlugin\.NOME\)/);
  assert.match(plugin, /public void onPageLoaded\(WebView webView\) \{[\s\S]*?Voltar\.eDoApp\(url, origem\)\) aberturaDaOS\.carregou\(\);/);
  assert.match(plugin, /public void onPageStarted\(WebView webView\) \{[^}]*aberturaDaOS\.comecouACarregar\(\);/);
  // O que a página respondeu ('aberta', 'pendente' ou false) só vai ao log, pelo AberturaDaOS.resultado: o nativo não
  // decide nada com isso (quem espera o técnico ficar livre é a página).
  assert.match(plugin, /evaluateJavascript\(js, resposta ->\s*Log\.i\(TAG, "a OS do toque na página: " \+ AberturaDaOS\.resultado\(resposta\)\)\)/);
});
