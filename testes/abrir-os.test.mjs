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
//   - a entrada do site também: a tela do App vem antes da fila, e a fila que chega restaura a tela de antes uma vez (o
//     _telaRestaurar do campo.html volta à aba anterior ou reabre a folha), por cima da busca. A OS espera a restauração
//     rodar, ou a fila carregar sem nada a restaurar, ou o teto de 20 s; e até o teto a Ronda reaberta que ainda abre
//     (sem etapa) também segura;
//   - livre, troca para Tarefas (só se não estiver nela) e preenche o campo ANTES do setBusca; o nativo só ouve o
//     osAberta ({t:'osAberta', os}, que tira a emergência da barra e para de repetir a OS) depois de a conferência de
//     1,5 s ver a OS à vista (a aba Tarefas sem nada por cima e o número no campo); fora da vista, ela volta a pendente. A
//     mesma OS entregue de novo na mesma carga é um pedido novo (livre, volta à busca; ocupado, fica pendente);
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
import { criarJanela, criarRelogio, assentar, ENDERECO_APP } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, ABRIR_OS, VOLTAR, PUSH } = require('../www/casca/inicio.js');
const CODIGO = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'www', 'casca', 'inicio.js'), 'utf8');

// A chave da pendência no sessionStorage (dura a partida, atravessa as recargas da mesma WebView).
const CHAVE = 'gc_casca_os_pendente';
// A conferência de que a OS posta na busca ficou à vista, e o teto da espera da entrada do site.
const CONFERE_MS = 1500;
const TETO_MS = 20000;
// A OS da emergência de teste do servidor (o /push/teste da v287 manda sempre esta).
const OS_DO_TESTE = '999999999';

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
// `entrada` é a entrada do site (os `let` de topo _telaRestaurada e pollTimer do campo.html, que na janela falsa são
// propriedades): 'terminada' é o App em uso (a restauração da tela já rodou nesta carga), 'em curso' a fila ainda a
// caminho (nenhum dos dois ligado) e 'ilegivel' o site sem os dois nomes.
function montar({ semCampo = false, semBusca = false, busca = null, tela = 'app', semSessao = false, entrada = 'terminada',
  Data = diaFixo(2026, 10, 18), ...opcoes } = {}) {
  const j = criarJanela(opcoes);
  j.win.Date = Data;
  if (entrada === 'terminada') j.win._telaRestaurada = true;
  if (entrada === 'em curso') { j.win._telaRestaurada = false; j.win.pollTimer = null; }
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
  // A conferência de 1,5 s depois da busca posta.
  const conferir = () => j.avancar(CONFERE_MS);
  return { ...j, casca, doc, campo, buscas, chamadas, elemento, funcao, abrir, erros, abertas, pendente, entrar, conferir, app, login };
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
// abas (as telas de login e do App não são abas) e põe na da aba pedida, quando ela tem tela.
const tela = (j, id, ativa = true) => j.elemento('div', { id, classes: ativa ? 'on' : '', dentro: j.app });
function comSetTab(j) {
  j.funcao('setTab', (aba) => {
    for (const el of [...j.doc.querySelectorAll('[id^="tela-"].on')]) el.classList.remove('on');
    const destino = j.doc.getElementById('tela-' + aba);
    if (destino) destino.classList.add('on');
  });
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

test('livre: abre na hora, troca para Tarefas e busca; o nativo ouve o osAberta depois da conferência', () => {
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
  // O osAberta (o nativo tira a emergência da barra) só sai com a OS à vista, conferida 1,5 s depois.
  assert.deepEqual(j.enviados, [], 'antes da conferência, nada ao nativo');
  assert.equal(j.timersAtivos(), 1, 'só a conferência de 1,5 s');
  j.avancar(CONFERE_MS - 1);
  assert.deepEqual(j.enviados, []);
  j.avancar(1);
  assert.deepEqual(j.enviados, [{ t: 'osAberta', os: '4521' }], 'o nativo fica sabendo que abriu (e para de repetir)');
  assert.equal(j.timersAtivos(), 0, 'e nada mais agendado');

  // Já em Tarefas (nenhuma tela de aba com .on): sem setTab, que só troca quando não é a aba atual.
  const t = montar();
  comSetTab(t);
  tela(t, 'tela-sync', false);
  assert.equal(t.abrir('4521'), 'aberta');
  assert.deepEqual(t.chamadas, ['setBusca:4521']);

  // A Ronda sem etapa na tela (sem o checklist baixado, ou a de hoje já finalizada: sem o .rd-voltar), a Zeladoria na
  // lista dos serviços e as outras abas não seguram, com o App em uso.
  for (const id of ['tela-ronda', 'tela-zl', 'tela-conquistas', 'tela-acomp']) {
    const k = montar();
    comSetTab(k);
    tela(k, id);
    assert.equal(k.abrir('77'), 'aberta', id);
    assert.deepEqual(k.chamadas, ['setTab:tarefas', 'setBusca:77'], id);
    k.conferir();
    assert.deepEqual(k.abertas(), ['77'], id);
  }

  // Sem o setTab na página (o site trocou o nome), noutra aba: a busca vai assim mesmo (o contrato diário avisa o dono),
  // mas a OS não fica à vista (a outra aba continua na frente), e a conferência a devolve à pendência.
  const s = montar();
  tela(s, 'tela-sync');
  assert.equal(s.abrir('4521'), 'aberta');
  assert.deepEqual(s.chamadas, ['setBusca:4521']);
  s.conferir();
  assert.deepEqual(s.abertas(), [], 'fora da vista, sem osAberta');
  assert.deepEqual(s.pendente(), { os: '4521', dia: HOJE });
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
  // O entrarComIdentidade liga a tela do App, e a fila já está lá (a restauração da tela já rodou): a conferência
  // seguinte abre, uma vez.
  volta.entrar();
  volta.avancar(2000);
  assert.deepEqual(volta.chamadas, ['setBusca:4521']);
  assert.equal(volta.campo.value, '4521');
  assert.equal(volta.pendente(), null);
  volta.conferir();
  assert.deepEqual(volta.abertas(), ['4521']);
  assert.equal(volta.timersAtivos(), 0, 'à vista: a conferência e o teto da entrada acabam');
  volta.avancar(30000);
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

test('a mesma OS entregue de novo na mesma carga é um pedido novo: livre, volta à busca; ocupado, fica pendente', () => {
  // O técnico toca de novo numa emergência de uma OS que já foi mostrada nesta carga, ou o responsável pela prova usa o
  // Testar avisos, que manda sempre a mesma OS, item depois de item, sem recarregar. Caso que criou a regra: a página
  // guardava as OS abertas na carga e devolvia 'aberta' sem fazer nada (sem a aba, sem a busca, sem pendência), e o
  // osAberta tirava da barra uma emergência que ninguém viu.
  const j = montar();
  comSetTab(j);
  assert.equal(j.abrir(OS_DO_TESTE), 'aberta');
  j.conferir();
  // Ele limpou a busca e foi à lista da Zeladoria: a mesma OS volta à aba Tarefas, com o número no campo.
  j.campo.value = '';
  const zl = tela(j, 'tela-zl');
  assert.equal(j.abrir(OS_DO_TESTE), 'aberta');
  assert.deepEqual(j.chamadas, ['setBusca:' + OS_DO_TESTE, 'setTab:tarefas', 'setBusca:' + OS_DO_TESTE]);
  assert.deepEqual(j.buscas[1], { v: OS_DO_TESTE, noCampo: OS_DO_TESTE }, 'o campo preenchido antes do setBusca');
  assert.equal(zl.classList.contains('on'), false);
  j.conferir();
  // Em Tarefas, com a busca ainda nela: a busca de novo, sem trocar de aba (o toque é o pedido de ver a OS).
  assert.equal(j.abrir(OS_DO_TESTE), 'aberta');
  assert.deepEqual(j.chamadas.slice(3), ['setBusca:' + OS_DO_TESTE]);
  j.conferir();
  assert.deepEqual(j.abertas(), [OS_DO_TESTE, OS_DO_TESTE, OS_DO_TESTE], 'cada vez que a OS é mostrada, o nativo fica sabendo');

  // Ele abriu a OS que a busca trouxe (ou outra), e a mesma OS chega de novo: pendente, nada vai por baixo da OS dele, e
  // o nativo não ouve o osAberta (a emergência fica na barra) até ela ser mostrada.
  const modal = abrirOs(j);
  j.campo.value = '';
  assert.equal(j.abrir(OS_DO_TESTE), 'pendente');
  assert.deepEqual(j.pendente(), { os: OS_DO_TESTE, dia: HOJE });
  assert.equal(j.campo.value, '');
  assert.deepEqual(j.abertas(), [OS_DO_TESTE, OS_DO_TESTE, OS_DO_TESTE], 'pendente não é aberta');
  j.avancar(4000);
  assert.deepEqual(j.chamadas.slice(4), [], 'com a OS aberta, nada');
  modal.classList.remove('open');
  j.avancar(2000);
  assert.deepEqual(j.chamadas.slice(4), ['setBusca:' + OS_DO_TESTE]);
  assert.equal(j.campo.value, OS_DO_TESTE);
  j.conferir();
  assert.deepEqual(j.abertas(), [OS_DO_TESTE, OS_DO_TESTE, OS_DO_TESTE, OS_DO_TESTE], 'mostrada pela pendência, o osAberta');
  assert.equal(j.pendente(), null);
  assert.equal(j.timersAtivos(), 0);

  // O nativo entrega de novo na carga seguinte a OS cujo osAberta não chegou: abre como sempre.
  const k = recarregar(j);
  assert.equal(k.abrir(OS_DO_TESTE), 'aberta');
  assert.deepEqual(k.chamadas, ['setBusca:' + OS_DO_TESTE]);
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
  j.conferir();
  assert.deepEqual(j.abertas(), ['4521']);
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
  assert.equal(j.timersAtivos(), 1, 'a conferência de 2 s para; a de 1,5 s espera a OS ficar à vista');
  j.conferir();
  assert.equal(j.timersAtivos(), 0);
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
  j.conferir();
  assert.deepEqual(j.abertas(), ['4521']);
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
  k.conferir();
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
  assert.equal(j.timersAtivos(), 1, 'aberta na hora: só a conferência de 1,5 s');
  j.conferir();
  assert.equal(j.timersAtivos(), 0);

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
  assert.equal(j.timersAtivos(), 1, 'aberta, a conferência de 2 s para (fica a de 1,5 s)');
  j.conferir();
  assert.equal(j.timersAtivos(), 0);
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
  assert.deepEqual(j.abertas(), ['222']);

  // A pendente esperando e uma emergência nova com ele já livre: a nova abre na hora, e a velha não vem depois.
  const k = montar();
  const m = abrirOs(k);
  assert.equal(k.abrir('111'), 'pendente');
  m.classList.remove('open');
  assert.equal(k.abrir('333'), 'aberta');
  assert.equal(k.pendente(), null);
  k.avancar(10000);
  assert.deepEqual(k.chamadas, ['setBusca:333']);
  assert.deepEqual(k.abertas(), ['333']);
  assert.equal(k.timersAtivos(), 0, 'sem pendência, a conferência já parou');

  // Uma OS nova chega durante a conferência de 1,5 s da anterior: só a nova é conferida, e a velha não volta a pendente.
  const n = montar();
  assert.equal(n.abrir('444'), 'aberta');
  n.avancar(500);
  assert.equal(n.abrir('555'), 'aberta');
  n.avancar(10000);
  assert.deepEqual(n.abertas(), ['555']);
  assert.equal(n.pendente(), null);
  // E a que fica pendente (ele abriu uma OS) durante a conferência da anterior, que não ficou à vista: a pendente é a
  // nova, e a anterior não toma o lugar dela.
  const p = montar();
  assert.equal(p.abrir('666'), 'aberta');
  const om = abrirOs(p);
  assert.equal(p.abrir('777'), 'pendente');
  p.conferir();
  assert.deepEqual(p.abertas(), [], 'a 666 não ficou à vista (a OS por cima)');
  assert.deepEqual(p.pendente(), { os: '777', dia: HOJE }, 'a última vence');
  om.classList.remove('open');
  p.avancar(2000);
  p.conferir();
  assert.deepEqual(p.abertas(), ['777']);
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
  j.conferir();
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

  // A OS que não ficou à vista volta à pendência na variável, também sem sessionStorage.
  const v = montar({ semSessao: true });
  assert.equal(v.abrir('10'), 'aberta');
  const ov = abrirOs(v);
  v.conferir();
  assert.deepEqual(v.abertas(), []);
  ov.classList.remove('open');
  v.avancar(2000);
  v.conferir();
  assert.deepEqual(v.abertas(), ['10']);
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

  // A OS posta na busca, com a conferência de 1,5 s em curso: o Sair a tira dali (nem o osAberta, nem a volta a
  // pendente para a conta seguinte).
  const s = montar();
  s.win.sair = async function () { return 'saiu'; };
  s.win.dispatchEvent({ type: 'load' });
  assert.equal(s.abrir('88'), 'aberta');
  await s.win.sair();
  abrirOs(s);
  s.avancar(10000);
  assert.deepEqual(s.abertas(), []);
  assert.equal(s.pendente(), null);
});

// ---------------------------------------------------------------------------------------------------------------
// A entrada do site: a fila que chega depois da tela do App e a restauração da tela de antes
// ---------------------------------------------------------------------------------------------------------------

// A entrada do campo.html (v287), na janela falsa: o entrarComIdentidade liga a tela do App e espera a fila; a fila que
// chega monta a lista e o renderTudo chama o _telaRestaurar UMA vez, que volta à aba anterior (setTab) ou reabre a folha
// (o abrirFechamentoTask: o #sheet.open). `marca` é o que o site guardou da tela de antes (o gc_tela).
function comEntrada(j) {
  comSetTab(j);
  const folha = j.elemento('div', { id: 'sheet' });
  const sync = tela(j, 'tela-sync', false);
  const ronda = tela(j, 'tela-ronda', false);
  // A fila chegou (o fim do carregarDados): com tarefas, a restauração roda e marca o _telaRestaurada; o entrarComIdentidade
  // liga o pollTimer logo depois, nos dois casos.
  const filaChegou = ({ tarefas = true, marca = null } = {}) => {
    if (tarefas) {
      j.win._telaRestaurada = true;
      if (marca && marca.tab) j.win.setTab(marca.tab);
      if (marca && marca.folha) { j.win.setTab('tarefas'); folha.classList.add('open'); }
    }
    j.win.pollTimer = 3;
  };
  return { folha, sync, ronda, filaChegou };
}

test('na entrada: a OS espera a fila, e a folha que a restauração reabre por cima da busca a segura até ele fechar', () => {
  // O toque com o app fechado: a tela de login, o Entrar, a tela do App na frente ANTES da fila (o entrarComIdentidade
  // espera o carregarMinhasOS e o carregarDados depois de ligá-la). Quando a fila chega, o site reabre a folha em que o
  // técnico estava (o "Você estava aqui"). Caso que criou a regra: a busca posta logo depois do Entrar ficava por baixo
  // da folha reaberta, e o osAberta já tinha tirado a emergência da barra.
  const j = montar({ tela: 'login', entrada: 'em curso' });
  const e = comEntrada(j);
  assert.equal(j.abrir(OS_DO_TESTE), 'pendente');
  j.entrar();
  j.avancar(2000);
  assert.deepEqual(j.chamadas, [], 'a tela do App na frente, mas a fila ainda a caminho: a OS espera');
  j.avancar(4000);
  assert.deepEqual(j.chamadas, []);
  assert.deepEqual(j.pendente(), { os: OS_DO_TESTE, dia: HOJE });
  // A fila chega e a restauração reabre a folha (o setTab('tarefas') e o #sheet.open).
  e.filaChegou({ marca: { folha: true } });
  j.avancar(2000);
  assert.deepEqual(j.chamadas, ['setTab:tarefas'], 'só o setTab da restauração: a folha reaberta segura a OS');
  assert.equal(j.campo.value, '');
  j.avancar(10000);
  assert.deepEqual(j.chamadas, ['setTab:tarefas']);
  assert.deepEqual(j.abertas(), []);
  // Ele fecha a folha: a OS vai à busca e, à vista, o nativo fica sabendo.
  e.folha.classList.remove('open');
  j.avancar(2000);
  assert.deepEqual(j.chamadas, ['setTab:tarefas', 'setBusca:' + OS_DO_TESTE]);
  j.conferir();
  assert.deepEqual(j.abertas(), [OS_DO_TESTE]);
  assert.equal(j.timersAtivos(), 0, 'nada mais agendado: nem a conferência, nem o teto da entrada');
});

test('na entrada: a restauração que troca de aba; a Ronda reaberta segura a OS enquanto abre, e a começada segura depois', () => {
  // A restauração voltou à Sincronização (outra aba sem nada aberto): a OS vai à aba Tarefas, como sempre.
  const j = montar({ tela: 'login', entrada: 'em curso' });
  const e = comEntrada(j);
  assert.equal(j.abrir('4521'), 'pendente');
  j.entrar();
  j.avancar(2000);
  e.filaChegou({ marca: { tab: 'sync' } });
  assert.ok(e.sync.classList.contains('on'), 'a restauração voltou à Sincronização');
  j.avancar(2000);
  assert.deepEqual(j.chamadas, ['setTab:sync', 'setTab:tarefas', 'setBusca:4521']);
  j.conferir();
  assert.deepEqual(j.abertas(), ['4521']);

  // A restauração voltou à Ronda: a aba abre pela rede (a conta do Fracttal e o checklist), sem etapa até lá, e o RONDA
  // ainda nulo. Nesse meio-tempo a OS não tira o técnico da ronda em que ele estava.
  const k = montar({ tela: 'login', entrada: 'em curso' });
  const r = comEntrada(k);
  assert.equal(k.abrir('4521'), 'pendente');
  k.entrar();
  k.avancar(2000);
  k.win.RONDA = null;
  r.filaChegou({ marca: { tab: 'ronda' } });
  k.avancar(6000);
  assert.deepEqual(k.chamadas, ['setTab:ronda'], 'a Ronda abrindo: a OS espera');
  // A Ronda abriu na etapa em que ele estava (o rascunho com a usina): a ronda começada segura.
  k.win.RONDA = { ...rondaVazia(), usina: 'Usina Ficticia' };
  k.elemento('button', { classes: 'rd-voltar', dentro: r.ronda });
  k.avancar(30000);
  assert.deepEqual(k.chamadas, ['setTab:ronda'], 'passado o teto, a ronda começada segura como sempre');
  // Ele sai da Ronda: a OS vai à busca.
  r.ronda.classList.remove('on');
  k.avancar(2000);
  assert.deepEqual(k.chamadas, ['setTab:ronda', 'setBusca:4521']);
  k.conferir();
  assert.deepEqual(k.abertas(), ['4521']);

  // A entrada rápida (a fila já tinha chegado quando a casca viu a tela do App, depois de ver a tela de login): é a
  // entrada desta carga do mesmo jeito, e a Ronda reaberta que ainda abre segura a OS.
  const q = montar({ tela: 'login', entrada: 'em curso' });
  const rq = comEntrada(q);
  assert.equal(q.abrir('4521'), 'pendente');
  q.entrar();
  rq.filaChegou({ marca: { tab: 'ronda' } });
  q.avancar(6000);
  assert.deepEqual(q.chamadas, ['setTab:ronda'], 'a Ronda abrindo segura, mesmo com a entrada já terminada');

  // A Ronda reaberta que não abre (a conta do Fracttal pede conexão: sem etapa, o RONDA nulo) segura só até o teto.
  const t = montar({ tela: 'login', entrada: 'em curso' });
  const rt = comEntrada(t);
  assert.equal(t.abrir('4521'), 'pendente');
  t.entrar();
  t.avancar(1000);
  rt.filaChegou({ marca: { tab: 'ronda' } });
  t.avancar(TETO_MS - 2000);
  assert.deepEqual(t.chamadas, ['setTab:ronda'], 'até o teto, a Ronda sem etapa segura');
  t.avancar(4000);
  assert.deepEqual(t.chamadas, ['setTab:ronda', 'setTab:tarefas', 'setBusca:4521'], 'passado o teto, é outra aba');
});

test('na entrada: a fila vazia (nada a restaurar) libera pelo fim da carga; sem sinal nenhum, o teto de 20 s', () => {
  // Com a fila vazia o site não restaura nada (o _telaRestaurar só roda com a fila montada) e o _telaRestaurada fica
  // false: o fim da entrada é o pollTimer, que o entrarComIdentidade só liga depois do await carregarDados().
  const j = montar({ tela: 'login', entrada: 'em curso' });
  const e = comEntrada(j);
  assert.equal(j.abrir('4521'), 'pendente');
  j.entrar();
  j.avancar(4000);
  assert.deepEqual(j.chamadas, []);
  e.filaChegou({ tarefas: false });
  assert.equal(j.win._telaRestaurada, false, 'nada a restaurar');
  j.avancar(2000);
  assert.deepEqual(j.chamadas, ['setBusca:4521']);
  j.conferir();
  assert.deepEqual(j.abertas(), ['4521']);

  // A fila que não chega (o carregarMinhasOS esperando o Fracttal): o teto, contado desde que a casca viu a tela do App.
  const k = montar({ tela: 'login', entrada: 'em curso' });
  comEntrada(k);
  assert.equal(k.abrir('4521'), 'pendente');
  k.avancar(2000);
  k.entrar();
  k.avancar(2000); // a casca vê a tela do App aqui
  k.avancar(TETO_MS - 2000);
  assert.deepEqual(k.chamadas, [], 'antes do teto');
  k.avancar(2000);
  assert.deepEqual(k.chamadas, ['setBusca:4521'], 'no teto');
  k.conferir();
  assert.deepEqual(k.abertas(), ['4521']);

  // O site sem os dois nomes (trocou o _telaRestaurada e o pollTimer): a OS que chega com o App na frente espera o teto.
  const s = montar({ entrada: 'ilegivel' });
  assert.equal(s.abrir('4521'), 'pendente');
  s.avancar(TETO_MS);
  assert.deepEqual(s.chamadas, ['setBusca:4521']);
});

test('o App já em uso quando a OS chega não espera a entrada: ela terminou antes, nesta carga', () => {
  // O toque com o app aberto (a fila já carregada, a restauração já rodou ou não tinha o que restaurar): a OS vai à busca
  // na hora, e nada de teto.
  for (const sinal of ['_telaRestaurada', 'pollTimer']) {
    const j = montar({ entrada: 'em curso' });
    j.win[sinal] = sinal === 'pollTimer' ? 5 : true;
    assert.equal(j.abrir('4521'), 'aberta', sinal);
    j.conferir();
    assert.deepEqual(j.abertas(), ['4521'], sinal);
    assert.equal(j.timersAtivos(), 0, sinal);
  }
  // Com a Ronda sem etapa na frente (ele está na tela da ronda finalizada, ou sem o checklist): é outra aba, como sempre.
  const k = montar();
  comSetTab(k);
  tela(k, 'tela-ronda');
  assert.equal(k.abrir('4521'), 'aberta');
  assert.deepEqual(k.chamadas, ['setTab:tarefas', 'setBusca:4521']);
});

test('a conferência de 1,5 s: o osAberta só sai com a OS à vista; fora dela, a OS volta a pendente', () => {
  // A restauração da tela que vem depois da busca posta (a OS aberta pelo teto, com a fila chegando logo em seguida): a
  // folha reaberta por cima da busca. Sem o osAberta, a emergência fica na barra, e a OS volta à busca quando ele fechar.
  const j = montar({ tela: 'login', entrada: 'em curso' });
  const e = comEntrada(j);
  assert.equal(j.abrir('4521'), 'pendente');
  j.entrar();
  j.avancar(2000);
  j.avancar(TETO_MS);
  assert.deepEqual(j.chamadas, ['setBusca:4521'], 'o teto abriu');
  e.filaChegou({ marca: { folha: true } });
  j.conferir();
  assert.deepEqual(j.abertas(), [], 'a folha por cima da busca: sem osAberta');
  assert.deepEqual(j.pendente(), { os: '4521', dia: HOJE }, 'a OS volta a pendente');
  j.avancar(6000);
  assert.deepEqual(j.chamadas, ['setBusca:4521', 'setTab:tarefas'], 'com a folha aberta, nada');
  e.folha.classList.remove('open');
  j.avancar(2000);
  assert.deepEqual(j.chamadas, ['setBusca:4521', 'setTab:tarefas', 'setBusca:4521']);
  j.conferir();
  assert.deepEqual(j.abertas(), ['4521']);

  // A restauração que volta a outra aba depois da busca: a OS some atrás dela, e volta à aba Tarefas na conferência.
  const k = montar({ entrada: 'em curso' });
  const ek = comEntrada(k);
  k.win.pollTimer = 1; // a fila já carregou, sem nada a restaurar ainda
  assert.equal(k.abrir('4521'), 'aberta');
  k.win.setTab('sync');
  k.conferir();
  assert.deepEqual(k.abertas(), [], 'outra aba na frente');
  k.avancar(2000);
  assert.ok(!ek.sync.classList.contains('on'), 'de volta a Tarefas');
  k.conferir();
  assert.deepEqual(k.abertas(), ['4521']);

  // O número que saiu do campo (a página redesenhou a busca, ou ele a limpou) e a tela do App que saiu: fora da vista.
  const c = montar();
  assert.equal(c.abrir('4521'), 'aberta');
  c.campo.value = '';
  c.conferir();
  assert.deepEqual(c.abertas(), []);
  assert.deepEqual(c.pendente(), { os: '4521', dia: HOJE });
  const t = montar();
  assert.equal(t.abrir('4521'), 'aberta');
  t.app.classList.remove('on');
  t.conferir();
  assert.deepEqual(t.abertas(), []);
  assert.deepEqual(t.pendente(), { os: '4521', dia: HOJE });
});

// ---------------------------------------------------------------------------------------------------------------
// A busca e o número
// ---------------------------------------------------------------------------------------------------------------

test('preenche o campo de busca e chama o setBusca com o número, nessa ordem', () => {
  const j = montar();
  assert.equal(j.abrir(OS_DO_TESTE), 'aberta');
  assert.equal(j.campo.value, OS_DO_TESTE);
  assert.deepEqual(j.buscas, [{ v: OS_DO_TESTE, noCampo: OS_DO_TESTE }], 'o campo já estava preenchido quando o setBusca rodou');
  assert.deepEqual(j.erros(), []);

  // Outra OS no lugar da primeira: o campo troca, sem acumular.
  assert.equal(j.abrir('7'), 'aberta');
  assert.equal(j.campo.value, '7');
  assert.deepEqual(j.buscas.map((b) => b.v), [OS_DO_TESTE, '7']);
});

test('a OS de 9 dígitos do teste do servidor passa, inteira', () => {
  // O /push/teste da v287 manda a OS 999999999 (fora da faixa das OS reais): a casca a aceita do mesmo jeito.
  const j = montar();
  assert.equal(j.abrir(OS_DO_TESTE), 'aberta');
  assert.equal(j.campo.value, OS_DO_TESTE);
  j.conferir();
  assert.deepEqual(j.abertas(), [OS_DO_TESTE]);
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
  j.avancar(10000);
  assert.deepEqual(j.abertas(), [], 'a busca que quebrou não vira osAberta');

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

  // A conferência de 1,5 s que quebra (a página que lança ao procurar a camada): um aviso, sem osAberta.
  const c = montar();
  assert.equal(c.abrir('4521'), 'aberta');
  c.doc.getElementById = () => { throw new Error('quebrou com tecnico.ficticio@exemplo.com.br'); };
  c.conferir();
  assert.deepEqual(c.erros().map((m) => m.onde), ['abrirOS']);
  assert.deepEqual(c.abertas(), []);
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
      let _houveQueda = false, _telaRestaurada = true;
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

test('no navegador: a entrada do site de verdade, com o _telaRestaurada e o pollTimer de topo lidos pelo nome', async () => {
  // O jsdom roda o inicio.js e uma página com a entrada do campo.html (v287) como scripts clássicos de verdade: o
  // entrarComIdentidade liga a tela do App, espera a fila e só então liga o pollTimer; o renderTudo chama o _telaRestaurar,
  // que reabre a folha com a fila montada. Os `let` de topo não são propriedades da janela: a casca os lê pelo nome. O
  // relógio da casca é falso (posto na janela antes dela), e a rede da página são promessas que o teste solta.
  const relogio = criarRelogio();
  const enviados = [];
  const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {
    url: ENDERECO_APP,
    runScripts: 'dangerously',
    beforeParse(window) {
      window.gcCasca = { postMessage(texto) { enviados.push(JSON.parse(texto)); } };
      window.__GC_RETRATO__ = { temEspelho: true, filaHashes: [], idbChaves: [] };
      window.localStorage.setItem('gc_casca_sentinela', '1');
      window.setTimeout = relogio.setTimeout;
      window.clearTimeout = relogio.clearTimeout;
    },
  });
  const { window } = dom;
  try {
    const rodar = (codigo) => {
      const s = window.document.createElement('script');
      s.textContent = codigo;
      window.document.head.appendChild(s);
    };
    rodar(CODIGO);
    window.document.body.innerHTML =
      '<div id="s-login" class="scr on"></div><div id="s-app" class="scr">' +
      '<input id="busca-os" type="search"><div id="tela-sync"></div></div><div id="sheet"></div>';
    // A rede da página: o teste solta a lista "minhas OS" e a fila quando quer.
    let soltarMinhas;
    let soltarFila;
    window.__minhas = new window.Promise((r) => { soltarMinhas = r; });
    window.__fila = new window.Promise((r) => { soltarFila = r; });
    rodar(`
      let BUSCA = '';
      let TASKI = {};
      let primeiroLoad = true, snapFila = null, pollTimer = null, semanaAtiva = '';
      let _houveQueda = false, _quedaAto = '', _telaRestaurada = false, _wakeLock = null;
      function setBusca(v) { BUSCA = String(v || '').trim(); window.buscouPor = BUSCA; }
      function setTab(t) {
        document.getElementById('tela-sync').classList.toggle('on', t === 'sync');
      }
      function abrirFechamentoTask(tid) { document.getElementById('sheet').classList.add('open'); }
      function _telaRestaurar() {
        if (_telaRestaurada) return false;
        if (!Object.keys(TASKI).length) return false;
        _telaRestaurada = true;
        setTab('tarefas');
        abrirFechamentoTask(1);
        return true;
      }
      function renderTudo() {
        try { _telaRestaurar(); } catch (e) {}
      }
      async function carregarMinhasOS() { await window.__minhas; }
      async function carregarDados() { TASKI = await window.__fila; renderTudo(); }
      async function entrarComIdentidade() {
        document.getElementById('s-login').classList.remove('on');
        document.getElementById('s-app').classList.add('on');
        await carregarMinhasOS();
        await carregarDados();
        if (!pollTimer) pollTimer = 1;
        renderTudo();
      }
    `);
    assert.equal(window._telaRestaurada, undefined, 'o let não é propriedade da janela');
    assert.equal(window.pollTimer, undefined);
    const abertas = () => enviados.filter((m) => m.t === 'osAberta').map((m) => m.os);
    const campo = () => window.document.getElementById('busca-os').value;

    // O toque com o app fechado: a tela de login.
    assert.equal(window.__gcCascaAbrirOS(OS_DO_TESTE), 'pendente');
    // O Entrar: a tela do App na frente, a fila ainda a caminho.
    const entrada = window.entrarComIdentidade();
    relogio.avancar(2000);
    relogio.avancar(4000);
    assert.equal(campo(), '', 'a fila a caminho: a OS espera');
    soltarMinhas();
    await assentar();
    relogio.avancar(2000);
    assert.equal(campo(), '', 'ainda sem a fila');
    // A fila chega com tarefas: a restauração reabre a folha, e a OS espera ele fechar.
    soltarFila({ 1: { key: 'k1' } });
    await entrada;
    assert.ok(window.document.getElementById('sheet').classList.contains('open'), 'a folha reaberta pelo site');
    relogio.avancar(6000);
    assert.equal(campo(), '', 'a folha reaberta segura a OS');
    assert.deepEqual(abertas(), []);
    window.document.getElementById('sheet').classList.remove('open');
    relogio.avancar(2000);
    assert.equal(campo(), OS_DO_TESTE);
    assert.equal(window.buscouPor, OS_DO_TESTE);
    relogio.avancar(CONFERE_MS);
    assert.deepEqual(abertas(), [OS_DO_TESTE], 'à vista: o osAberta');
  } finally {
    window.close();
  }
});

test('o ABRIR_OS do inicio.js tem os nomes que o contrato com o site exige, e a seção 9 só os usa por ele', () => {
  assert.deepEqual(ABRIR_OS, {
    funcao: { buscar: 'setBusca', aba: 'setTab' },
    id: { busca: 'busca-os', ronda: 'tela-ronda', app: 's-app' },
    classe: { abaAtiva: 'on', rondaNumaEtapa: 'rd-voltar' },
    ronda: {
      global: 'RONDA', criterio: 'rdVoltar', comecou: 'comecou', usina: 'usina', respostas: 'respostas',
      dispersos: 'dispersos', finalizada: 'finalizadaNoServidor',
    },
    entrada: {
      restaurada: '_telaRestaurada', restaurar: '_telaRestaurar', render: 'renderTudo',
      filaCarregada: 'pollTimer', entrar: 'entrarComIdentidade', carregarFila: 'carregarDados',
    },
    aba: 'tarefas',
  });
  // O que a regra de não interromper lê da página é o que o voltar lê: os mesmos nomes, de um lugar só. A tela do App é a
  // mesma que o registro do aparelho espera (o PUSH).
  assert.equal(ABRIR_OS.funcao.aba, VOLTAR.funcao.aba);
  assert.equal(ABRIR_OS.aba, VOLTAR.abaInicial);
  assert.equal(ABRIR_OS.id.ronda, VOLTAR.id.ronda);
  assert.equal(ABRIR_OS.classe.abaAtiva, VOLTAR.classe.abaAtiva);
  assert.equal(ABRIR_OS.classe.rondaNumaEtapa, VOLTAR.classe.rondaVolta);
  assert.equal(ABRIR_OS.id.app, PUSH.id.app);
  // E vêm do VOLTAR e do PUSH no código, não repetidos: o nome trocado num lugar só não pode deixar o outro para trás.
  const objeto = CODIGO.slice(CODIGO.indexOf('  var ABRIR_OS = {'), CODIGO.indexOf('\n  };', CODIGO.indexOf('  var ABRIR_OS = {')));
  for (const ref of ['aba: VOLTAR.funcao.aba', 'ronda: VOLTAR.id.ronda', 'abaAtiva: VOLTAR.classe.abaAtiva',
    'rondaNumaEtapa: VOLTAR.classe.rondaVolta', 'aba: VOLTAR.abaInicial', 'app: PUSH.id.app']) {
    assert.ok(objeto.includes(ref), ref);
  }

  const secao = CODIGO.slice(CODIGO.indexOf('// 9. Abrir a OS pelo toque'), CODIGO.indexOf('// Ponto único de entrada'));
  assert.ok(secao.includes('function instalarAbrirOS('), 'o corte pega a seção 9');
  const codigo = secao.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
  for (const usado of ['ABRIR_OS.funcao.buscar', 'ABRIR_OS.id.busca', 'ABRIR_OS.funcao.aba', 'ABRIR_OS.aba',
    'ABRIR_OS.ronda.global', 'ABRIR_OS.ronda.usina', 'ABRIR_OS.ronda.respostas', 'ABRIR_OS.ronda.dispersos',
    'ABRIR_OS.ronda.finalizada', 'ABRIR_OS.entrada']) {
    assert.ok(codigo.includes(usado), 'usado: ' + usado);
  }
  // O "ocupado" é a camada de cima da seção 7, a mesma do voltar, e a tela do App da seção 8: a seção 9 não refaz a lista.
  assert.ok(codigo.includes('camadaDeCima(ctx)'), 'a seção 9 pergunta à seção 7');
  assert.ok(codigo.includes('appNaTela(win)'), 'e à seção 8');
  assert.equal(codigo.match(/VOLTAR\.|getElementById\(VOLTAR|querySelector/g), null, 'nenhuma camada procurada aqui');
  // Nenhum nome do site escrito por fora do objeto: um nome trocado lá ficaria sem o contrato ver. Os únicos sem aspas são
  // os `let` lidos pelo nome (o da ronda e os dois da entrada), cada um numa linha só, que tem de ser a do objeto.
  const peloNome = { RONDA: ABRIR_OS.ronda.global, _telaRestaurada: ABRIR_OS.entrada.restaurada, pollTimer: ABRIR_OS.entrada.filaCarregada };
  const semOsPeloNome = codigo.split('\n').filter((l) => !Object.keys(peloNome).some((n) => new RegExp(`typeof ${n} `).test(l))).join('\n');
  assert.equal(semOsPeloNome.match(/setBusca|busca-os|setTab|tarefas|tela-|rd-voltar|s-app|'modal'|'open'|'on'|(?<!ABRIR_OS\.ronda\.)\b(?:usina|respostas|dispersos)\b|finalizadaNoServidor|rdVoltar|\bcomecou\b|_telaRestaura|\brenderTudo\b|\bpollTimer\b|entrarComIdentidade|carregarDados|\bRONDA\b/g), null);
  for (const [nome, doObjeto] of Object.entries(peloNome)) {
    assert.equal(nome, doObjeto, `o ${nome} lido pelo nome é o do objeto`);
    const linhas = codigo.split('\n').filter((l) => new RegExp(`\\b${nome}\\b`).test(l));
    assert.equal(linhas.length, 1, linhas.join('\n'));
    assert.ok(linhas[0].includes('typeof ' + nome + ' '), linhas[0]);
  }
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
