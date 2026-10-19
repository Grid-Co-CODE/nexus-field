// Prova o registro do aparelho para as notificações, do lado do script de início (spec 2026-10-18, seção 4.2): o
// nativo dá o token do Firebase, e a PRÓPRIA página o registra no servidor com o Bearer dela (o nativo nunca vê token da
// Microsoft). O que se protege aqui: um registro por carga, com o Bearer da conta de agora e o corpo do contrato com o
// servidor (Ruling N3: sem atalho de "já registrado", que não sabia de quem era o registro nem voltava depois do Sair);
// sem login, sem rede ou com a rota ainda fora do ar, nada de laço nem de erro na tela; no "Sair", o servidor esquece o
// aparelho antes de a página limpar a sessão, e o sair segue mesmo se o esquecer falhar (o aparelho dividido entre
// técnicos); e nada de token nem de e-mail no aviso de erro ao nativo. O servidor é falso: as rotas são da sessão "App
// de campo".
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';
import { criarJanela, criarAparelho, lerFotos, ate, assentar, ENDERECO_APP } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, PUSH } = require('../www/casca/inicio.js');
const CODIGO = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'www', 'casca', 'inicio.js'), 'utf8');

// O endereço do servidor como o campo.html o declara (a `const MIDDLEWARE_URL` de topo), fictício.
const MW = 'https://servidor.exemplo.test/api';
const ESPERA_MS = 5000;
const DEZ_MIN_MS = 10 * 60 * 1000;
// Os testes do sair têm prazo próprio: com o relógio falso, um sair que nunca chama o original deixaria o await
// pendurado para sempre, em vez de falhar.
// Os dados que nunca podem ir ao aviso de erro (fictícios).
const TOKEN = 'tokenFcmFicticio:APA91b_abc123';
const EMAIL = 'tecnico.ficticio@exemplo.com.br';
const BEARER = 'bearerFicticio.eyJ.' + EMAIL;
// Duas contas do mesmo aparelho (o aparelho dividido, ou o técnico com duas contas), fictícias.
const CONTA_A = 'bearerFicticio.contaA';
const CONTA_B = 'bearerFicticio.contaB';

// O que o nativo responde ao pushPedirToken (o Mensageiro.respostaDoPush): o token e a versão, e nada sobre registro.
const doNativo = (token = TOKEN) => ({ t: 'pushToken', token, versao: '1.1.0' });
// O que a primeira versão desta tarefa respondia na carga seguinte ao registro (o mesmo token, registrado há menos de 7
// dias). As provas das cargas seguintes o mandam de propósito: o script não tem mais atalho, e o campo não muda nada.
const doNativoAntigo = () => ({ ...doNativo(), jaRegistrado: true });

const ok = () => Promise.resolve({ ok: true, status: 200 });
const status = (n) => () => Promise.resolve({ ok: n >= 200 && n < 300, status: n });
const semRede = () => Promise.reject(new TypeError('Failed to fetch'));
const nunca = () => new Promise(() => {});

// O GET /me do atalho de teste (Ruling N7), que a seção 8 faz junto do registro com o mesmo Bearer: aqui, uma conta que
// não é Admin. Ele tem a prova dele (testes/atalho-teste.test.mjs); nesta, fica fora dos `pedidos` e do `doPush`.
const naoAdmin = () => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ papel: 'Tecnico' }) });

// A janela do App com o que a página define (o getTokenMW e o MIDDLEWARE_URL) e o fetch falso do servidor. `bearer` é o
// que o getTokenMW devolve (null: ninguém logado); `servidor(url, init)` responde cada chamada das rotas do registro.
function montar({ bearer = BEARER, servidor = ok, ...opcoes } = {}) {
  const j = criarJanela(opcoes);
  const pedidos = [];
  const perguntas = [];
  const estado = { bearer, servidor, bearers: 0 };
  j.win.MIDDLEWARE_URL = MW;
  j.win.getTokenMW = async () => { estado.bearers++; return estado.bearer; };
  j.win.fetch = (url, init) => {
    if (url === MW + '/me') { perguntas.push({ url, init }); return naoAdmin(); }
    pedidos.push({ url, init, corpo: JSON.parse(init.body) });
    return estado.servidor(url, init);
  };
  const casca = instalar(j.win, j.canal, j.retrato);
  const doTipo = (t) => j.enviados.filter((m) => m.t === t);
  const erros = () => j.enviados.filter((m) => m.t === 'erro');
  // O que a seção 8 mandou ao nativo para o registro (menos os avisos e o pushAdmin do atalho de teste): só o pedido do
  // token, uma vez por carga.
  const doPush = () => j.enviados.filter((m) => typeof m.t === 'string' && m.t.indexOf('push') === 0 && m.t !== 'pushAdmin');
  const registros = () => pedidos.filter((p) => p.url === MW + '/push/registrar');
  const esquecimentos = () => pedidos.filter((p) => p.url === MW + '/push/esquecer');
  return { ...j, casca, pedidos, perguntas, estado, doTipo, erros, doPush, registros, esquecimentos };
}

// A carga seguinte na mesma WebView: o localStorage e o sessionStorage de antes (o script não guarda nada do registro
// em nenhum dos dois, e esta prova o confirma).
function recarregar(j, opcoes = {}) {
  return montar({
    ls: Object.fromEntries(j.win.localStorage._itens),
    sessao: Object.fromEntries(j.win.sessionStorage._itens),
    ...opcoes,
  });
}

// O load da página, os 5 s e a resposta do nativo ao pushPedirToken.
async function carregar(j, resposta) {
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS);
  j.responder({ ...doNativo(), ...resposta });
  await assentar();
}

function mostrar(j) {
  j.win.document.visibilityState = 'visible';
  j.win.document.dispatchEvent({ type: 'visibilitychange' });
}

test('registra uma vez por carga com o Bearer da página e o corpo do contrato', async () => {
  const j = montar();
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS - 1);
  assert.deepEqual(j.doTipo('pushPedirToken'), [], 'espera 5 s depois do load');
  j.avancar(1);
  assert.deepEqual(j.doTipo('pushPedirToken'), [{ t: 'pushPedirToken' }]);

  j.responder(doNativo());
  await assentar();
  assert.equal(j.pedidos.length, 1);
  const { url, init, corpo } = j.pedidos[0];
  assert.equal(url, MW + '/push/registrar');
  assert.equal(init.method, 'POST');
  assert.equal(init.headers.Authorization, 'Bearer ' + BEARER);
  assert.equal(init.headers['Content-Type'], 'application/json');
  assert.deepEqual(corpo, { token: TOKEN, aparelho: 'android', app: 'NexusField/1.1.0' });
  // O registro é de fundo: o gcQuieto do campo.html (v284) o deixa fora da barra de carregamento.
  assert.equal(init.gcQuieto, true);

  // Registrado, nada mais chama a rota nesta carga: nem a rede que volta, nem a volta ao app, nem outra resposta do
  // nativo (o token é pedido uma vez por carga, e só a primeira resposta com token vale).
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  j.avancar(DEZ_MIN_MS);
  j.responder(doNativo('outroToken'));
  await assentar();
  assert.equal(j.pedidos.length, 1);
  // E o nativo não fica sabendo do registro: não há memória dele a consultar na carga seguinte.
  assert.deepEqual(j.doPush(), [{ t: 'pushPedirToken' }], 'o token é pedido uma vez por carga, e nada mais vai ao nativo');
  assert.deepEqual(j.erros(), []);
});

test('cada carga registra de novo com a conta atual', async () => {
  // O técnico que troca de conta sem passar pelo Sair (o "limpar só o login", a outra conta do "Há duas contas"): a
  // carga seguinte registra o mesmo token com o Bearer da conta nova, e o servidor (upsert pelo token) passa o aparelho
  // a ela. Com o atalho dos 7 dias, a emergência seguia indo ao e-mail anterior.
  const primeira = montar({ bearer: CONTA_A });
  await carregar(primeira, doNativo());
  const segunda = recarregar(primeira, { bearer: CONTA_B });
  await carregar(segunda, doNativoAntigo());

  const registros = [...primeira.registros(), ...segunda.registros()];
  assert.deepEqual(registros.map((p) => p.init.headers.Authorization), ['Bearer ' + CONTA_A, 'Bearer ' + CONTA_B]);
  for (const p of registros) assert.deepEqual(p.corpo, { token: TOKEN, aparelho: 'android', app: 'NexusField/1.1.0' });
  for (const j of [primeira, segunda]) {
    assert.equal(j.pedidos.length, 1, 'um registro por carga');
    assert.deepEqual(j.doPush(), [{ t: 'pushPedirToken' }]);
    assert.deepEqual(j.erros(), []);
  }
});

test('depois do Sair a carga seguinte registra', { timeout: 10000 }, async () => {
  // O aparelho dividido entre técnicos: o Sair tira o aparelho do servidor antes do logout, e a carga que vem depois
  // (o logout da Microsoft volta ao App) o registra de novo assim que alguém entra, com a conta de quem entrou. Com o
  // atalho dos 7 dias, o nativo seguia achando que estava registrado, e o aparelho ficava sem aviso por até 7 dias.
  const antes = montar({ bearer: CONTA_A });
  antes.win.sair = async function () { return 'saiu'; };
  await carregar(antes, doNativo());
  assert.equal(await antes.win.sair(), 'saiu');
  assert.deepEqual(antes.pedidos.map((p) => [p.url, p.init.headers.Authorization, p.corpo.token]), [
    [MW + '/push/registrar', 'Bearer ' + CONTA_A, TOKEN],
    [MW + '/push/esquecer', 'Bearer ' + CONTA_A, TOKEN],
  ]);

  // A carga seguinte abre na tela de login (ninguém logado): nada a registrar ainda.
  const depois = recarregar(antes, { bearer: null });
  await carregar(depois, doNativoAntigo());
  assert.equal(depois.pedidos.length, 0);
  // O outro técnico entra, e a volta ao app registra com a conta dele.
  depois.estado.bearer = CONTA_B;
  mostrar(depois);
  await assentar();
  assert.deepEqual(depois.pedidos.map((p) => [p.url, p.init.headers.Authorization, p.corpo.token]), [
    [MW + '/push/registrar', 'Bearer ' + CONTA_B, TOKEN],
  ]);
  assert.deepEqual(depois.doPush(), [{ t: 'pushPedirToken' }]);
  assert.deepEqual([...antes.erros(), ...depois.erros()], []);
});

test('não registra sem login e tenta no próximo online', async () => {
  const j = montar({ bearer: null });
  await carregar(j, { token: TOKEN });
  assert.equal(j.estado.bearers, 1, 'perguntou à página');
  assert.equal(j.pedidos.length, 0, 'sem Bearer não chama a rota');
  assert.deepEqual(j.erros(), [], 'ninguém logado não é erro');

  // Ainda sem login: a volta da rede pergunta de novo, sem chamar a rota.
  j.win.dispatchEvent({ type: 'online' });
  await assentar();
  assert.equal(j.estado.bearers, 2);
  assert.equal(j.pedidos.length, 0);

  // O técnico entrou: o próximo online registra.
  j.estado.bearer = BEARER;
  j.win.dispatchEvent({ type: 'online' });
  await assentar();
  assert.equal(j.pedidos.length, 1);
  assert.equal(j.pedidos[0].init.headers.Authorization, 'Bearer ' + BEARER);
  assert.deepEqual(j.pedidos[0].corpo, { token: TOKEN, aparelho: 'android', app: 'NexusField/1.1.0' });

  // Registrado, a espera do login acabou: nem a rede nem a volta ao app chamam a rota de novo.
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  await assentar();
  assert.equal(j.pedidos.length, 1);
  assert.equal(j.estado.bearers, 3);
});

test('a página sem o getTokenMW ou sem o MIDDLEWARE_URL: um aviso, nenhuma chamada e nada de tentar de novo', async () => {
  for (const falta of ['getTokenMW', 'MIDDLEWARE_URL']) {
    const j = montar();
    delete j.win[falta];
    await carregar(j, { token: TOKEN });
    for (let i = 0; i < 2; i++) {
      j.avancar(DEZ_MIN_MS);
      j.win.dispatchEvent({ type: 'online' });
      mostrar(j);
      await assentar();
    }
    assert.equal(j.pedidos.length, 0, falta);
    assert.deepEqual(j.erros().map((e) => e.onde), ['push'], falta);
  }
});

test('sem login, a volta ao app (visibilitychange) também tenta de novo; o fundo não', async () => {
  const j = montar({ bearer: null });
  await carregar(j, { token: TOKEN });
  j.estado.bearer = BEARER;
  j.win.document.visibilityState = 'hidden';
  j.win.document.dispatchEvent({ type: 'visibilitychange' });
  await assentar();
  assert.equal(j.pedidos.length, 0, 'indo para o fundo não tenta');
  mostrar(j);
  await assentar();
  assert.equal(j.pedidos.length, 1);
  assert.equal(j.registros().length, 1);
});

test('404: não tenta mais nesta sessão', async () => {
  const j = montar({ servidor: status(404) });
  await carregar(j, { token: TOKEN });
  assert.equal(j.pedidos.length, 1);
  assert.deepEqual(j.erros().map((e) => e.onde), ['push'], 'um aviso só');

  for (let i = 0; i < 3; i++) {
    j.avancar(DEZ_MIN_MS);
    j.win.dispatchEvent({ type: 'online' });
    mostrar(j);
    await assentar();
  }
  assert.equal(j.pedidos.length, 1, 'a rota ainda não está no ar: nada de laço');
  assert.equal(j.erros().length, 1);
});

test('outro erro ou sem rede: tenta no próximo online, no máximo 1 vez a cada 10 minutos', async () => {
  const j = montar({ servidor: semRede });
  await carregar(j, { token: TOKEN });
  assert.equal(j.pedidos.length, 1);
  assert.deepEqual(j.erros().map((e) => e.onde), ['push']);

  // A volta ao app não tenta (só o online), e o online logo depois da falha espera os 10 minutos.
  mostrar(j);
  j.win.dispatchEvent({ type: 'online' });
  await assentar();
  assert.equal(j.pedidos.length, 1, 'menos de 10 minutos desde a tentativa');
  j.avancar(DEZ_MIN_MS - 1);
  await assentar();
  assert.equal(j.pedidos.length, 1);
  j.avancar(1);
  await assentar();
  assert.equal(j.pedidos.length, 2, 'o online que chegou na espera tenta quando ela acaba');

  // O servidor responde 500: outra espera; passados os 10 minutos sem online, nada; o online seguinte tenta.
  j.estado.servidor = status(500);
  j.win.dispatchEvent({ type: 'online' });
  j.avancar(DEZ_MIN_MS);
  await assentar();
  assert.equal(j.pedidos.length, 3);
  j.avancar(DEZ_MIN_MS);
  await assentar();
  assert.equal(j.pedidos.length, 3, 'sem online, não tenta sozinho');
  j.estado.servidor = ok;
  j.win.dispatchEvent({ type: 'online' });
  await assentar();
  assert.equal(j.pedidos.length, 4);
  assert.equal(j.registros().length, 4, 'todas as tentativas são o registro');
  j.win.dispatchEvent({ type: 'online' });
  j.avancar(DEZ_MIN_MS);
  await assentar();
  assert.equal(j.pedidos.length, 4, 'registrado, para');
});

test('sem token do Firebase (null): não faz nada', async () => {
  const j = montar();
  const chamadas = [];
  j.win.sair = async function () { chamadas.push('sair'); return 'saiu'; };
  await carregar(j, { token: null });
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  j.avancar(DEZ_MIN_MS);
  await assentar();
  assert.equal(j.pedidos.length, 0);
  assert.equal(j.estado.bearers, 0);
  assert.deepEqual(j.erros(), []);
  // O sair, sem token a esquecer, é o da página na hora.
  assert.equal(await j.win.sair(), 'saiu');
  assert.deepEqual(chamadas, ['sair']);
  assert.equal(j.pedidos.length, 0);

  // Token vazio ou de outro tipo vale o mesmo.
  for (const token of ['', 123, undefined]) {
    const k = montar();
    await carregar(k, { token });
    assert.equal(k.pedidos.length, 0, String(token));
  }
});

test('sair: esquece o token antes e chama o original mesmo se o esquecer falhar', { timeout: 10000 }, async () => {
  // A página define o sair enquanto carrega, antes do load (o script de início roda antes dela).
  const j = montar();
  const ordem = [];
  j.win.sair = async function (...args) { ordem.push(['sair', this === j.win, ...args].join(':')); return 'saiu'; };
  const original = j.win.sair;
  await carregar(j, { token: TOKEN });
  assert.notEqual(j.win.sair, original, 'embrulhado no load');
  assert.deepEqual(j.pedidos.map((p) => p.url), [MW + '/push/registrar'], 'a carga registrou');

  // A rede caiu: o esquecer falha, e o sair segue.
  j.estado.servidor = semRede;
  const r = j.win.sair('a');
  assert.deepEqual(ordem, [], 'o esquecer vem antes');
  assert.equal(await r, 'saiu', 'o que o original devolve');
  assert.equal(j.esquecimentos().length, 1);
  const esquecer = j.esquecimentos()[0];
  assert.equal(esquecer.init.method, 'POST');
  assert.equal(esquecer.init.headers.Authorization, 'Bearer ' + BEARER);
  assert.equal(esquecer.init.headers['Content-Type'], 'application/json');
  assert.deepEqual(esquecer.corpo, { token: TOKEN });
  assert.deepEqual(ordem, ['sair:true:a'], 'chamado com o this e os argumentos de antes');
  assert.deepEqual(j.erros().map((e) => e.onde), ['push']);

  // O servidor que responde erro: o sair segue.
  j.estado.servidor = status(500);
  assert.equal(await j.win.sair(), 'saiu');
  assert.equal(ordem.length, 2);

  // O servidor que não responde: o sair espera 3 s, no máximo.
  j.estado.servidor = nunca;
  const preso = j.win.sair();
  await assentar();
  j.avancar(2999);
  await assentar();
  assert.equal(ordem.length, 2, 'ainda no prazo');
  j.avancar(1);
  assert.equal(await preso, 'saiu');
  assert.equal(ordem.length, 3);

  // Ninguém logado (o Bearer null): sem esquecer, o sair segue.
  j.estado.bearer = null;
  assert.equal(await j.win.sair(), 'saiu');
  assert.equal(ordem.length, 4);
  assert.equal(j.esquecimentos().length, 3);
  assert.equal(j.registros().length, 1, 'o sair não registra de novo');

  // Um segundo load (o navegador não o dispara; o ouvinte é de uma vez só) não embrulha de novo nem pede outro token.
  const embrulho = j.win.sair;
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS);
  assert.equal(j.win.sair, embrulho);
  assert.equal(j.doTipo('pushPedirToken').length, 1);
});

test('sair: o esquecer que dá certo não avisa nada, e o erro do original sobe como subiria', { timeout: 10000 }, async () => {
  const j = montar();
  j.win.sair = async function () { throw new Error('o logout falhou'); };
  await carregar(j, { token: TOKEN });
  await assert.rejects(j.win.sair(), /o logout falhou/);
  assert.deepEqual(j.pedidos.map((p) => p.url), [MW + '/push/registrar', MW + '/push/esquecer']);
  assert.deepEqual(j.erros(), []);
});

test('nada de token nem e-mail nas mensagens de erro', { timeout: 10000 }, async () => {
  // Cada erro cita o token, o e-mail e o Bearer no texto, como uma mensagem de rede ou do MSAL poderia citar.
  const citando = () => Promise.reject(new Error(`falhou ${MW}/push?token=${TOKEN} para ${EMAIL} com ${BEARER}`));
  const j = montar({ servidor: citando });
  j.win.sair = async function () { return 'saiu'; };
  await carregar(j, { token: TOKEN });
  j.avancar(DEZ_MIN_MS);
  j.estado.servidor = status(500);
  j.win.dispatchEvent({ type: 'online' });
  await assentar();
  await j.win.sair();
  j.estado.servidor = citando;
  await j.win.sair();
  j.estado.servidor = nunca;
  const preso = j.win.sair();
  await assentar();
  j.avancar(3000);
  await preso;
  // O getTokenMW que lança (a página de v284 não lança, mas outra pode).
  j.win.getTokenMW = async () => { throw new Error('sem conta para ' + EMAIL); };
  await j.win.sair();

  // E a rota que ainda não está no ar, noutra carga.
  const k = montar({ servidor: status(404) });
  await carregar(k, { token: TOKEN });

  const erros = [...j.erros(), ...k.erros()];
  assert.ok(erros.length >= 6, 'cada falha avisou: ' + erros.length);
  for (const e of erros) {
    assert.equal(e.onde, 'push');
    for (const proibido of [TOKEN, 'APA91b', EMAIL, 'tecnico.ficticio', '@exemplo', BEARER, 'bearerFicticio', 'eyJ']) {
      assert.equal(e.msg.includes(proibido), false, `o aviso levou "${proibido}": ${e.msg}`);
    }
  }
});

test('com a restauração das fotos em curso, o pushToken e as respostas do IndexedDB chegam cada um ao seu', async () => {
  // O nativo responde às duas pelo mesmo canal.onmessage: a seção que se registra depois não pode tirar a outra.
  const aparelho = criarAparelho(); // sem o banco sentinela da casca: esta carga restaura o IndexedDB
  const j = montar({ idb: aparelho });
  await ate(() => j.doTipo('idbPedir').length > 0, 'idbPedir');
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS);
  j.responder({ t: 'idbItem', k: 'f_1', tipo: 'texto', v: 'foto' });
  j.responder(doNativo());
  j.responder({ t: 'idbFim', n: 1 });
  await j.casca.pronto;
  await assentar();
  assert.deepEqual(await lerFotos(aparelho), { f_1: 'foto' });
  assert.equal(j.registros().length, 1);
  assert.deepEqual(j.erros(), []);
});

test('a carga parada para o retrato não pede o token', async () => {
  // A perda (a WebView sem nada e o nativo com espelho) para a carga: a página nem roda, e o nativo recarrega.
  const j = montar({ ls: {}, retrato: { temEspelho: true, filaHashes: [], idbChaves: [] } });
  assert.equal(j.paradas.length, 1, 'a carga parou');
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS);
  await assentar();
  assert.deepEqual(j.doTipo('pushPedirToken'), []);
});

test('o PUSH do inicio.js tem os nomes que o contrato com o site exige, e a seção 8 só os usa por ele', () => {
  assert.deepEqual(PUSH, { funcao: { bearer: 'getTokenMW', sair: 'sair' }, global: { middleware: 'MIDDLEWARE_URL' } });
  // A seção 8 vai até a 9 (a abertura da OS pelo toque, que tem os nomes dela no ABRIR_OS, conferidos no testes/abrir-os.test.mjs).
  const secao = CODIGO.slice(CODIGO.indexOf('// 8. O registro do aparelho'), CODIGO.indexOf('// 9. Abrir a OS pelo toque'));
  assert.ok(secao.includes('function instalarPush('), 'o corte pega a seção 8');
  const codigo = secao.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
  for (const [grupo, papeis] of Object.entries(PUSH)) {
    for (const papel of Object.keys(papeis)) assert.ok(codigo.includes(`PUSH.${grupo}.${papel}`), `usado: PUSH.${grupo}.${papel}`);
  }
  // Nenhum nome do site escrito por fora do objeto (um nome trocado lá ficaria sem o contrato ver), menos a const lida
  // pelo nome, numa linha só, que tem de ser a do objeto.
  assert.equal(codigo.match(/getTokenMW/g), null);
  assert.equal(codigo.match(/['"]sair['"]|(?<!PUSH\.funcao)\.sair\b|function sair\b/g), null);
  const peloNome = codigo.split('\n').filter((l) => l.includes('MIDDLEWARE_URL'));
  assert.equal(peloNome.length, 1);
  assert.ok(peloNome[0].includes('typeof ' + PUSH.global.middleware + ' '), peloNome[0]);
});

test('na página de verdade: a const MIDDLEWARE_URL lida pelo nome e o async function sair embrulhado', async () => {
  // A janela falsa põe tudo na janela; aqui o script e a página rodam como scripts de verdade (jsdom), e a `const` de
  // topo do campo.html não vira propriedade da janela: o script a acha pelo nome, como acha o sheetTask do voltar.
  const enviados = [];
  const canal = { onmessage: null, postMessage(texto) { enviados.push(JSON.parse(texto)); } };
  const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {
    url: ENDERECO_APP,
    runScripts: 'dangerously',
    beforeParse(window) {
      window.gcCasca = canal;
      window.__GC_RETRATO__ = { temEspelho: true, filaHashes: [], idbChaves: [] };
      // O aparelho em uso (a sentinela de pé): a carga não para para restaurar.
      window.localStorage.setItem('gc_casca_sentinela', '1');
      // Os 5 s depois do load, encurtados (o resto do script não espera nada nesta prova).
      const agendar = window.setTimeout.bind(window);
      window.setTimeout = (fn, ms) => agendar(fn, ms === ESPERA_MS ? 0 : ms);
      window.pedidos = [];
      window.perguntas = [];
      window.fetch = (url, init) => {
        // O GET /me do atalho de teste (sem corpo), à parte: o papel que não é Admin.
        if (url === MW + '/me') { window.perguntas.push(init.headers.Authorization); return naoAdmin(); }
        window.pedidos.push({ url, corpo: JSON.parse(init.body) });
        return Promise.resolve({ ok: true, status: 200 });
      };
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
    const MIDDLEWARE_URL = '${MW}';
    window.ordem = [];
    async function getTokenMW() { return 'B'; }
    async function sair() { ordem.push('sair'); }
  `);
  assert.equal(window.MIDDLEWARE_URL, undefined, 'a const não é propriedade da janela');
  window.dispatchEvent(new window.Event('load'));
  await new Promise((r) => setTimeout(r, 20));
  assert.deepEqual(enviados.filter((m) => m.t === 'pushPedirToken'), [{ t: 'pushPedirToken' }]);
  canal.onmessage({ data: JSON.stringify({ t: 'pushToken', token: 'T', versao: '9.9.9' }) });
  await new Promise((r) => setTimeout(r, 20));
  assert.deepEqual(window.pedidos.map((p) => p.url), [MW + '/push/registrar']);
  assert.equal(window.pedidos[0].corpo.app, 'NexusField/9.9.9');
  // O papel da conta, com o mesmo Bearer do registro, e o nativo sabendo só que ela não é Admin.
  assert.deepEqual([...window.perguntas], ['Bearer B']);
  assert.deepEqual(enviados.filter((m) => m.t.indexOf('push') === 0),
    [{ t: 'pushPedirToken' }, { t: 'pushAdmin', admin: false }]);

  // O sair chamado pela própria página (o onclick do menu) passa pelo embrulho: a declaração de topo é a da janela.
  rodar('window.saiu = sair();');
  await window.saiu;
  assert.deepEqual(window.pedidos.map((p) => p.url), [MW + '/push/registrar', MW + '/push/esquecer']);
  assert.deepEqual([...window.ordem], ['sair']);
  assert.deepEqual(enviados.filter((m) => m.t === 'pushAdmin').at(-1), { t: 'pushAdmin', admin: false }, 'o Sair tira os atalhos');
  window.close();
});
