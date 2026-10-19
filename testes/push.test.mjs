// Prova o registro do aparelho para as notificações, do lado do script de início (spec 2026-10-18, seção 4.2): o
// nativo dá o token do Firebase, e a PRÓPRIA página o registra no servidor com o Bearer dela (o nativo nunca vê token da
// Microsoft). O que se protege aqui:
//   - um registro por token em cada carga, com o Bearer da conta de agora e o corpo do contrato com o servidor (sem atalho
//     de "já registrado": a primeira versão pulava o registro de menos de 7 dias e não sabia de QUEM ele era);
//   - "logado" é a tela do App na frente (#s-app.on, que o site liga depois de fixar a conta escolhida): antes dela, o
//     getTokenMW dá a conta ativa ANTERIOR (o aparelho dividido, na volta do loginRedirect), e nada sai;
//   - o token que chega depois da carga (a primeira abertura sem sinal, o token renovado) registra, e só com os avisos do
//     app ligados;
//   - sem login, sem rede ou com a rota ainda fora do ar, nada de laço nem de erro na tela;
//   - no "Sair", o nativo fica sabendo ({t:'pushSaiu'}, com token ou sem), o servidor esquece o aparelho por um pedido
//     que sobrevive à navegação (keepalive), o sair da página roda assim que o pedido sai, o segundo toque não repete nada
//     e nenhum registro começa depois; o registro que deu certo avisa o nativo ({t:'pushRegistrado'});
//   - nada de token nem de e-mail no aviso de erro ao nativo.
// O servidor é falso: as rotas são da sessão "App de campo".
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

// O GET /me do atalho de teste, que a seção 8 faz a cada carga depois do login: aqui, uma conta que não é Admin. Ele tem a
// prova dele (testes/atalho-teste.test.mjs); nesta, fica fora dos `pedidos` e do `doPush`.
const naoAdmin = () => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ papel: 'Tecnico' }) });

// A janela do App com o que a página define (o getTokenMW e o MIDDLEWARE_URL), as duas telas do campo.html (`#s-login` e
// `#s-app`, a da frente com o .on; `tela` diz qual) e o fetch falso do servidor. `bearer` é o que o getTokenMW devolve
// (null: sem sessão da Microsoft); `servidor(url, init)` responde cada chamada das rotas do registro.
function montar({ bearer = BEARER, servidor = ok, tela = 'app', mw = MW, ...opcoes } = {}) {
  const j = criarJanela(opcoes);
  const pedidos = [];
  const perguntas = [];
  const estado = { bearer, servidor, bearers: 0 };
  j.win.MIDDLEWARE_URL = mw;
  j.win.getTokenMW = async () => { estado.bearers++; return estado.bearer; };
  j.win.fetch = (url, init) => {
    if (url === MW + '/me') { perguntas.push({ url, init }); return naoAdmin(); }
    pedidos.push({ url, init, corpo: JSON.parse(init.body) });
    return estado.servidor(url, init);
  };
  const doc = j.win.document;
  const login = doc.createElement('div');
  login.id = 's-login';
  login.className = tela === 'login' ? 'scr on' : 'scr';
  const app = doc.createElement('div');
  app.id = 's-app';
  app.className = tela === 'app' ? 'scr on' : 'scr';
  doc.body.append(login, app);
  const casca = instalar(j.win, j.canal, j.retrato);
  const doTipo = (t) => j.enviados.filter((m) => m.t === t);
  const erros = () => j.enviados.filter((m) => m.t === 'erro');
  // O que a seção 8 mandou ao nativo sobre o registro (menos os avisos e o pushAdmin do atalho de teste).
  const doPush = () => j.enviados.filter((m) => typeof m.t === 'string' && m.t.indexOf('push') === 0 && m.t !== 'pushAdmin');
  const registros = () => pedidos.filter((p) => p.url === MW + '/push/registrar');
  const esquecimentos = () => pedidos.filter((p) => p.url === MW + '/push/esquecer');
  // O Entrar que deu certo (o entrarComIdentidade do site) e o sair do site (a primeira linha dele).
  const entrar = () => { login.classList.remove('on'); app.classList.add('on'); };
  const irAoLogin = () => { app.classList.remove('on'); login.classList.add('on'); };
  return { ...j, casca, pedidos, perguntas, estado, doTipo, erros, doPush, registros, esquecimentos, entrar, irAoLogin, app };
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

  // Registrado, nada mais chama a rota nesta carga com este token: nem a rede que volta, nem a volta ao app, nem o mesmo
  // token de novo (o nativo o reenvia na volta ao app).
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  j.avancar(DEZ_MIN_MS);
  j.responder(doNativo());
  await assentar();
  assert.equal(j.pedidos.length, 1);
  // O nativo fica sabendo do registro que deu certo (é o que solta a marca do Sair lá), e de nada mais.
  assert.deepEqual(j.doPush(), [{ t: 'pushPedirToken' }, { t: 'pushRegistrado' }]);
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
    assert.deepEqual(j.doPush(), [{ t: 'pushPedirToken' }, { t: 'pushRegistrado' }]);
    assert.deepEqual(j.erros(), []);
  }
});

test('depois do Sair a carga seguinte registra', { timeout: 10000 }, async () => {
  // O aparelho dividido entre técnicos: o Sair tira o aparelho do servidor antes do logout, e a carga que vem depois
  // (o logout da Microsoft volta ao App) o registra de novo assim que alguém entra, com a conta de quem entrou. Com o
  // atalho dos 7 dias, o nativo seguia achando que estava registrado, e o aparelho ficava sem aviso por até 7 dias.
  const antes = montar({ bearer: CONTA_A });
  antes.win.sair = async function () { antes.irAoLogin(); return 'saiu'; };
  await carregar(antes, doNativo());
  assert.equal(await antes.win.sair(), 'saiu');
  assert.deepEqual(antes.pedidos.map((p) => [p.url, p.init.headers.Authorization, p.corpo.token]), [
    [MW + '/push/registrar', 'Bearer ' + CONTA_A, TOKEN],
    [MW + '/push/esquecer', 'Bearer ' + CONTA_A, TOKEN],
  ]);

  // A carga seguinte abre na tela de login (online não há entrada automática): nada a registrar ainda, e o getTokenMW nem
  // é chamado (ele daria a conta que estava salva, não a de quem está diante do aparelho).
  const depois = recarregar(antes, { bearer: CONTA_A, tela: 'login' });
  await carregar(depois, doNativoAntigo());
  assert.equal(depois.pedidos.length, 0);
  assert.equal(depois.estado.bearers, 0);
  // O outro técnico entra: a tela do App vem para a frente e o registro sai com a conta dele.
  depois.estado.bearer = CONTA_B;
  depois.entrar();
  depois.avancar(1000);
  await assentar();
  assert.deepEqual(depois.pedidos.map((p) => [p.url, p.init.headers.Authorization, p.corpo.token]), [
    [MW + '/push/registrar', 'Bearer ' + CONTA_B, TOKEN],
  ]);
  assert.deepEqual(depois.doPush(), [{ t: 'pushPedirToken' }, { t: 'pushRegistrado' }]);
  assert.deepEqual([...antes.erros(), ...depois.erros()], []);
});

test('na volta do login, antes da tela do App, o getTokenMW dá a conta anterior: nada sai com ela', async () => {
  // O aparelho dividido: A não saiu; B toca em Entrar, escolhe a conta e volta. Até o site fixar a conta de B
  // (setActiveAccount, logo antes de ligar a tela do App), o MSAL ainda tem A como ativa, e com 4G fraco isso passa dos
  // 5 s. O registro e o /me saíam com A: as emergências de A tocavam na mão de B, e os atalhos de Admin de A ficavam
  // para B.
  const j = montar({ bearer: CONTA_A, tela: 'login' });
  await carregar(j, doNativo());
  j.avancar(30000);
  await assentar();
  assert.deepEqual(j.pedidos, [], 'nenhum registro');
  assert.deepEqual(j.perguntas, [], 'nenhum /me');
  assert.equal(j.estado.bearers, 0, 'nem o getTokenMW é chamado');
  // O site fixa a conta de B e liga a tela do App.
  j.estado.bearer = CONTA_B;
  j.entrar();
  j.avancar(1000);
  await assentar();
  assert.deepEqual(j.registros().map((p) => p.init.headers.Authorization), ['Bearer ' + CONTA_B]);
  const me = j.perguntas.map((p) => p.init.headers.Authorization);
  assert.ok(me.length >= 1 && me.every((b) => b === 'Bearer ' + CONTA_B), 'o /me só com B: ' + me.join(', '));
});

test('a espera do login sem MutationObserver é uma consulta curta com limite; o online e a volta ao app conferem de novo', async () => {
  const j = montar({ tela: 'login' });
  await carregar(j, doNativo());
  // A consulta de 1 s por 3 minutos, e depois nada agendado por ela (nada mais espera um timer nesta carga).
  assert.equal(j.timersAtivos(), 1, 'a consulta');
  j.avancar(3 * 60 * 1000);
  await assentar();
  assert.equal(j.pedidos.length, 0);
  assert.equal(j.timersAtivos(), 0, 'a consulta parou');
  // O login que veio depois do limite: a volta ao app o vê.
  j.entrar();
  mostrar(j);
  await assentar();
  assert.equal(j.registros().length, 1);

  // E o online também.
  const k = montar({ tela: 'login' });
  await carregar(k, doNativo());
  k.avancar(5 * 60 * 1000);
  k.entrar();
  k.win.dispatchEvent({ type: 'online' });
  await assentar();
  assert.equal(k.registros().length, 1);
});

test('com MutationObserver, a troca da classe da tela do App já registra, sem consulta', async () => {
  const j = montar({ tela: 'login' });
  j.win.MutationObserver = j.win.document.defaultView.MutationObserver;
  await carregar(j, doNativo());
  const agendados = j.timersAtivos();
  j.entrar();
  await assentar();
  assert.equal(j.registros().length, 1, 'sem avançar o relógio');
  assert.ok(j.timersAtivos() <= agendados, 'nenhuma consulta agendada');
});

test('o MutationObserver que não observa cai na consulta curta', async () => {
  const j = montar({ tela: 'login' });
  j.win.MutationObserver = class { observe() { throw new TypeError('não observa'); } disconnect() {} };
  await carregar(j, doNativo());
  j.entrar();
  j.avancar(1000);
  await assentar();
  assert.equal(j.registros().length, 1);
});

test('não registra sem login e tenta no próximo online', async () => {
  // A tela do App na frente sem a sessão da Microsoft (a entrada sem sinal com a identidade guardada, ou a sessão
  // vencida): o getTokenMW dá null.
  const j = montar({ bearer: null });
  await carregar(j, { token: TOKEN });
  assert.ok(j.estado.bearers >= 1, 'perguntou à página');
  assert.equal(j.pedidos.length, 0, 'sem Bearer não chama a rota');
  assert.deepEqual(j.erros(), [], 'ninguém logado não é erro');

  // Ainda sem login: a volta da rede pergunta de novo, sem chamar a rota.
  const antes = j.estado.bearers;
  j.win.dispatchEvent({ type: 'online' });
  await assentar();
  assert.ok(j.estado.bearers > antes);
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

test('sem rede: tenta no próximo online, no máximo 1 vez a cada 10 minutos (a volta ao app não diz que a rede voltou)', async () => {
  const j = montar({ servidor: semRede });
  j.win.navigator = { onLine: false };
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
  // Sem rede de novo: passados os 10 minutos, a volta ao app não tenta; o online, sim.
  j.avancar(DEZ_MIN_MS);
  mostrar(j);
  await assentar();
  assert.equal(j.pedidos.length, 2, 'a volta ao app não tenta o que falhou sem rede');
  j.estado.servidor = ok;
  j.win.dispatchEvent({ type: 'online' });
  await assentar();
  assert.equal(j.pedidos.length, 3);
  j.win.dispatchEvent({ type: 'online' });
  j.avancar(DEZ_MIN_MS);
  await assentar();
  assert.equal(j.pedidos.length, 3, 'registrado, para');

  // O aparelho que não diz se está no ar (sem o navigator.onLine): conta como sem rede.
  const k = montar({ servidor: semRede });
  await carregar(k, { token: TOKEN });
  k.avancar(DEZ_MIN_MS);
  mostrar(k);
  await assentar();
  assert.equal(k.pedidos.length, 1);
});

test('com a rede de pé (5xx, ou o pedido que falhou com o aparelho no ar): tenta também na volta ao app, com a espera', async () => {
  // O online não vem para quem nunca perdeu a rede: o registro que deu 503, ou que esgotou o tempo com o aparelho no ar,
  // ficava sem nova tentativa até a carga seguinte, que no app vivo no fundo pode ser o dia inteiro.
  for (const [nome, servidor] of [['503', status(503)], ['500', status(500)], ['tempo esgotado', semRede]]) {
    const j = montar({ servidor });
    j.win.navigator = { onLine: true };
    await carregar(j, { token: TOKEN });
    assert.equal(j.registros().length, 1, nome);
    // A volta ao app logo depois espera os 10 minutos, e tenta quando eles acabam (uma vez).
    mostrar(j);
    await assentar();
    assert.equal(j.registros().length, 1, nome + ': menos de 10 minutos desde a tentativa');
    j.avancar(DEZ_MIN_MS);
    await assentar();
    assert.equal(j.registros().length, 2, nome + ': a volta ao app que chegou na espera tenta quando ela acaba');
    // Passada a espera, a volta ao app tenta na hora; depois do registro, nada mais.
    j.avancar(DEZ_MIN_MS);
    j.estado.servidor = ok;
    mostrar(j);
    await assentar();
    assert.equal(j.registros().length, 3, nome);
    j.avancar(DEZ_MIN_MS);
    mostrar(j);
    j.win.dispatchEvent({ type: 'online' });
    await assentar();
    assert.equal(j.registros().length, 3, nome + ': registrado, para');
    // O online também tenta, como sempre (o caso de cima, sem rede, prova o online).
  }
  // O 5xx com o online: a espera vale do mesmo jeito.
  const k = montar({ servidor: status(500) });
  await carregar(k, { token: TOKEN });
  k.win.dispatchEvent({ type: 'online' });
  k.avancar(DEZ_MIN_MS);
  await assentar();
  assert.equal(k.registros().length, 2, 'o online que chegou na espera tenta quando ela acaba');
  k.avancar(DEZ_MIN_MS);
  await assentar();
  assert.equal(k.registros().length, 2, 'sem online nem volta ao app, não tenta sozinho');
});

test('sem token do Firebase (null): nada a registrar, e o online e a volta ao app pedem de novo, no máximo 1 vez a cada 10 minutos', async () => {
  // A primeira abertura já na usina, sem sinal (o App abre do cache do service worker): o getToken do Firebase falha, e o
  // SDK só obtém o token quando o sinal volta. Antes, o null encerrava a seção nesta carga, e o aparelho ficava sem o
  // resumo e sem a emergência até a próxima carga, que podia ser o dia inteiro com o app vivo no fundo.
  const j = montar();
  const chamadas = [];
  j.win.sair = async function () { chamadas.push('sair'); return 'saiu'; };
  await carregar(j, { token: null });
  assert.equal(j.pedidos.length, 0);
  assert.deepEqual(j.erros(), []);
  const pedidosDoToken = () => j.doTipo('pushPedirToken').length;
  assert.equal(pedidosDoToken(), 1);

  // O online e a volta ao app logo depois esperam os 10 minutos, e pedem quando eles acabam (uma vez).
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  await assentar();
  assert.equal(pedidosDoToken(), 1, 'menos de 10 minutos desde o pedido');
  j.avancar(DEZ_MIN_MS);
  assert.equal(pedidosDoToken(), 2, 'o que chegou na espera pede quando ela acaba');
  // Sem nada que peça, o tempo não pede sozinho.
  j.responder({ token: null, t: 'pushToken' });
  j.avancar(DEZ_MIN_MS * 3);
  assert.equal(pedidosDoToken(), 2, 'sem online nem volta ao app, não pede');
  // Passada a espera, a volta ao app pede na hora; o pedido de novo também começa a espera, mesmo sem resposta do nativo.
  mostrar(j);
  assert.equal(pedidosDoToken(), 3);
  mostrar(j);
  j.win.dispatchEvent({ type: 'online' });
  assert.equal(pedidosDoToken(), 3, 'o pedido de novo também espera os 10 minutos');
  // O token chega e registra.
  j.responder(doNativo());
  await assentar();
  assert.equal(j.registros().length, 1);
  // Com o token, nada mais é pedido.
  j.avancar(DEZ_MIN_MS);
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  assert.equal(pedidosDoToken(), 3);

  // O sair, sem token a esquecer, é o da página na hora.
  const k = montar();
  k.win.sair = async function () { chamadas.push('sair'); return 'saiu'; };
  await carregar(k, { token: null });
  assert.equal(await k.win.sair(), 'saiu');
  assert.deepEqual(chamadas, ['sair']);
  assert.equal(k.pedidos.length, 0);

  // Token vazio ou de outro tipo vale o mesmo.
  for (const token of ['', 123, undefined]) {
    const s = montar();
    await carregar(s, { token });
    assert.equal(s.pedidos.length, 0, String(token));
  }
});

test('o token que chega depois da carga, ou um token diferente, registra uma vez por token', async () => {
  // O nativo manda o pushToken a qualquer hora (o onNewToken com a página viva): o token renovado, com o servidor apagando
  // o velho, deixava o aparelho fora até recarregar.
  const j = montar();
  j.win.sair = async function () { return 'saiu'; };
  await carregar(j, doNativo());
  assert.deepEqual(j.registros().map((p) => p.corpo.token), [TOKEN]);
  j.responder(doNativo('tokenNovoFicticio'));
  await assentar();
  assert.deepEqual(j.registros().map((p) => p.corpo.token), [TOKEN, 'tokenNovoFicticio']);
  // O mesmo de novo (o token de agora, já registrado nesta carga): nada.
  j.responder(doNativo('tokenNovoFicticio'));
  await assentar();
  assert.equal(j.registros().length, 2);
  assert.deepEqual(j.doTipo('pushRegistrado').length, 2, 'um aviso ao nativo por registro');

  // O token antigo que volta (a resposta atrasada de um pedido de antes, depois do token novo): é DIFERENTE do de agora,
  // vira o de agora e registra uma vez; o mesmo de novo, não. O registrado é sempre o último token que o nativo mandou, e
  // é ele que o Sair manda o servidor esquecer.
  j.responder(doNativo(TOKEN));
  await assentar();
  assert.deepEqual(j.registros().map((p) => p.corpo.token), [TOKEN, 'tokenNovoFicticio', TOKEN]);
  j.responder(doNativo(TOKEN));
  await assentar();
  assert.equal(j.registros().length, 3);
  await j.win.sair();
  assert.deepEqual(j.esquecimentos().map((p) => p.corpo.token), [TOKEN], 'o Sair esquece o token de agora');

  // O token novo chega com o registro do anterior ainda no ar: registra o novo quando o primeiro termina.
  let soltar;
  const k = montar({ servidor: () => new Promise((r) => { soltar = () => r({ ok: true, status: 200 }); }) });
  await carregar(k, doNativo());
  assert.equal(k.registros().length, 1);
  k.responder(doNativo('tokenNovoFicticio'));
  await assentar();
  assert.equal(k.registros().length, 1, 'um pedido de cada vez');
  k.estado.servidor = ok;
  soltar();
  await assentar();
  assert.deepEqual(k.registros().map((p) => p.corpo.token), [TOKEN, 'tokenNovoFicticio']);
});

test('só registra com os avisos do app ligados; o nativo velho, sem o campo, vale como ligado', async () => {
  // Com as notificações do app desligadas, ou o canal das emergências bloqueado, o servidor contaria um aparelho que não
  // mostra nada ("1 aparelho(s)" no teste, e nada aparece).
  const j = montar();
  await carregar(j, { ...doNativo(), avisos: false });
  assert.equal(j.registros().length, 0);
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  j.avancar(DEZ_MIN_MS);
  await assentar();
  assert.equal(j.registros().length, 0, 'nada tenta sozinho');
  // O técnico liga nos ajustes do Android e volta ao app: o nativo manda o mesmo token com os avisos ligados.
  j.responder({ ...doNativo(), avisos: true });
  await assentar();
  assert.equal(j.registros().length, 1);

  // O que não é true nem ausente não liga.
  for (const avisos of [null, 0, 'true', 'sim']) {
    const k = montar();
    await carregar(k, { ...doNativo(), avisos });
    assert.equal(k.registros().length, 0, String(avisos));
  }
});

test('o endereço do servidor sem a barra do fim, como a página faz', async () => {
  // O campo.html usa MIDDLEWARE_URL.replace(/\/+$/,'') em toda chamada; com a barra no fim, a casca pedia '…/api//push/…',
  // o 404 caía em "rota fora do ar" e o aparelho deixava de ser registrado sem ninguém ver.
  for (const mw of [MW + '/', MW + '//']) {
    const j = montar({ mw });
    j.win.sair = async function () { return 'saiu'; };
    await carregar(j, doNativo());
    await j.win.sair();
    assert.deepEqual(j.pedidos.map((p) => p.url), [MW + '/push/registrar', MW + '/push/esquecer'], mw);
    assert.equal(j.perguntas.length, 1, mw);
  }
  // Só barras não é endereço: nada sai.
  const k = montar({ mw: '/' });
  await carregar(k, doNativo());
  assert.equal(k.pedidos.length, 0);
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

  // O servidor que não responde: o pedido saiu (com keepalive, ele sobrevive à navegação do logout), e o sair da página
  // roda na hora, sem esperar a resposta.
  j.estado.servidor = nunca;
  const preso = j.win.sair();
  await assentar();
  assert.equal(ordem.length, 3, 'o sair não espera a resposta');
  assert.equal(await preso, 'saiu');
  assert.equal(j.esquecimentos().at(-1).init.keepalive, true);

  // O getTokenMW que não responde: o sair espera 3 s, no máximo.
  const getTokenMW = j.win.getTokenMW;
  j.win.getTokenMW = () => new Promise(() => {});
  const semBearer = j.win.sair();
  await assentar();
  j.avancar(2999);
  await assentar();
  assert.equal(ordem.length, 3, 'ainda no prazo');
  j.avancar(1);
  assert.equal(await semBearer, 'saiu');
  assert.equal(ordem.length, 4);
  j.win.getTokenMW = getTokenMW;

  // Ninguém logado (o Bearer null): sem esquecer, o sair segue.
  j.estado.bearer = null;
  assert.equal(await j.win.sair(), 'saiu');
  assert.equal(ordem.length, 5);
  assert.equal(j.esquecimentos().length, 3);
  assert.equal(j.registros().length, 1, 'o sair não registra de novo');
  for (const e of j.esquecimentos()) assert.equal(e.init.keepalive, true, 'todo esquecer vai com keepalive');

  // Um segundo load (o navegador não o dispara; o ouvinte é de uma vez só) não embrulha de novo nem pede outro token.
  const embrulho = j.win.sair;
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS);
  assert.equal(j.win.sair, embrulho);
  assert.equal(j.doTipo('pushPedirToken').length, 1);
});

test('sair: o nativo ouve o pushSaiu antes de tudo, com token ou sem, e sem rede', { timeout: 10000 }, async () => {
  // Sem rede, com o servidor fora ou com a sessão vencida, o esquecer não chega ao servidor; o nativo cala as notificações
  // até o próximo registro e apaga o token do Firebase. O aviso a ele não pode depender do esquecer.
  for (const [nome, opcoes, resposta] of [
    ['com token', {}, doNativo()],
    ['sem rede', { servidor: semRede }, doNativo()],
    ['sem sessão', { bearer: null }, doNativo()],
    ['sem token', {}, { token: null }],
  ]) {
    const j = montar(opcoes);
    const ordem = [];
    j.win.sair = async function () { ordem.push(j.doTipo('pushSaiu').length + ':' + j.esquecimentos().length); return 'saiu'; };
    await carregar(j, resposta);
    j.estado.servidor = opcoes.servidor || ok;
    const antes = j.esquecimentos().length;
    const r = j.win.sair();
    assert.deepEqual(j.doTipo('pushSaiu'), [{ t: 'pushSaiu' }], nome + ': na hora do toque');
    assert.equal(j.esquecimentos().length, antes, nome + ': antes do esquecer');
    await r;
    assert.equal(ordem.length, 1, nome);
    assert.equal(ordem[0].split(':')[0], '1', nome + ': antes do sair da página');
  }
});

test('sair: o segundo toque devolve o mesmo sair em curso, sem outro esquecer nem outro logout', { timeout: 10000 }, async () => {
  // O sair do site troca de tela na primeira linha e o embrulho o segura até o pedido sair; o segundo toque repetia o
  // esquecer e o logout, e o segundo logoutRedirect lança interaction_in_progress (a reserva da página apaga as contas
  // enquanto o primeiro navega).
  const j = montar();
  let originais = 0;
  j.win.sair = async function () { originais++; return 'saiu'; };
  await carregar(j, doNativo());
  const getTokenMW = j.win.getTokenMW;
  let soltarBearer;
  j.win.getTokenMW = () => new Promise((r) => { soltarBearer = () => r(BEARER); });
  const primeira = j.win.sair();
  const segunda = j.win.sair();
  assert.equal(segunda, primeira, 'a mesma Promise');
  soltarBearer();
  assert.equal(await primeira, 'saiu');
  assert.equal(originais, 1);
  assert.equal(j.esquecimentos().length, 1);
  assert.equal(j.doTipo('pushSaiu').length, 1);
  // Terminado o sair, um novo Sair (a entrada sem sinal na mesma carga, e o Sair de novo) roda outra vez.
  j.win.getTokenMW = getTokenMW;
  await j.win.sair();
  assert.equal(originais, 2);

  // Sem token, o segundo toque com o original ainda no ar também é o mesmo.
  const k = montar();
  let soltar;
  k.win.sair = function () { originais++; return new Promise((r) => { soltar = () => r('saiu'); }); };
  await carregar(k, { token: null });
  const a = k.win.sair();
  const b = k.win.sair();
  assert.equal(b, a);
  soltar();
  assert.equal(await a, 'saiu');
  assert.equal(originais, 3);
});

test('sair: nenhum registro começa depois do Sair', { timeout: 10000 }, async () => {
  // Durante o esquecer, um online com o registro esperando a rede, o fim da espera de 10 minutos ou um token novo do nativo
  // mandavam um /push/registrar com o Bearer de quem está saindo, ao lado do /push/esquecer. Se o servidor tratasse o
  // registro por último, a conta que saiu continuava dona do aparelho.
  const j = montar({ servidor: semRede });
  j.win.sair = async function () { j.irAoLogin(); return 'saiu'; };
  await carregar(j, doNativo());
  assert.equal(j.registros().length, 1, 'o registro falhou e espera a rede');
  j.win.dispatchEvent({ type: 'online' }); // chega na espera: tentaria quando ela acabasse
  await j.win.sair();
  const bearersNoSair = j.estado.bearers;
  j.estado.servidor = ok;
  j.avancar(DEZ_MIN_MS);
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  j.responder(doNativo('tokenNovoFicticio'));
  // A entrada sem sinal na mesma carga (a identidade guardada) também não registra: a próxima carga registra.
  j.entrar();
  j.avancar(5000);
  await assentar();
  assert.equal(j.registros().length, 1, 'nada depois do Sair');
  assert.equal(j.estado.bearers, bearersNoSair, 'nem o Bearer é pedido de novo');
  assert.deepEqual(j.doTipo('pushRegistrado'), []);

  // O registro que já estava no ar e responde 2xx depois do Sair não avisa o nativo (a marca do Sair fica lá).
  let soltar;
  const k = montar({ servidor: () => new Promise((r) => { soltar = () => r({ ok: true, status: 200 }); }) });
  k.win.sair = async function () { return 'saiu'; };
  await carregar(k, doNativo());
  k.estado.servidor = ok;
  await k.win.sair();
  soltar();
  await assentar();
  assert.deepEqual(k.doTipo('pushRegistrado'), []);
});

test('o registro que dá certo avisa o nativo; o que falha, não', async () => {
  for (const [nome, servidor, avisa] of [['200', ok, 1], ['204', status(204), 1], ['404', status(404), 0], ['500', status(500), 0], ['sem rede', semRede, 0]]) {
    const j = montar({ servidor });
    await carregar(j, doNativo());
    assert.equal(j.doTipo('pushRegistrado').length, avisa, nome);
    for (const m of j.doTipo('pushRegistrado')) assert.deepEqual(m, { t: 'pushRegistrado' }, 'só o tipo: ' + nome);
  }
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
  await j.win.sair();
  // O getTokenMW que não responde: o prazo de 3 s.
  const getTokenMW = j.win.getTokenMW;
  j.win.getTokenMW = () => new Promise(() => {});
  const preso = j.win.sair();
  await assentar();
  j.avancar(3000);
  await preso;
  j.win.getTokenMW = getTokenMW;
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
  assert.deepEqual(PUSH, {
    funcao: { bearer: 'getTokenMW', sair: 'sair' },
    global: { middleware: 'MIDDLEWARE_URL' },
    id: { app: 's-app' },
    classe: { naTela: 'on' },
  });
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
  assert.equal(codigo.match(/s-app|'on'/g), null);
  const peloNome = codigo.split('\n').filter((l) => l.includes('MIDDLEWARE_URL'));
  assert.equal(peloNome.length, 1);
  assert.ok(peloNome[0].includes('typeof ' + PUSH.global.middleware + ' '), peloNome[0]);
});

test('na página de verdade: a const MIDDLEWARE_URL lida pelo nome e o async function sair embrulhado', async () => {
  // A janela falsa põe tudo na janela; aqui o script e a página rodam como scripts de verdade (jsdom), e a `const` de
  // topo do campo.html não vira propriedade da janela: o script a acha pelo nome, como acha o sheetTask do voltar.
  const enviados = [];
  const canal = { onmessage: null, postMessage(texto) { enviados.push(JSON.parse(texto)); } };
  const dom = new JSDOM('<!doctype html><html><head></head><body><div id="s-app" class="scr on"></div></body></html>', {
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
  // O papel da conta, com o Bearer da página, e o nativo sabendo só que ela não é Admin.
  assert.deepEqual([...window.perguntas], ['Bearer B']);
  assert.deepEqual(enviados.filter((m) => m.t.indexOf('push') === 0),
    [{ t: 'pushPedirToken' }, { t: 'pushAdmin', admin: false }, { t: 'pushRegistrado' }]);

  // O sair chamado pela própria página (o onclick do menu) passa pelo embrulho: a declaração de topo é a da janela.
  rodar('window.saiu = sair();');
  await window.saiu;
  assert.deepEqual(window.pedidos.map((p) => p.url), [MW + '/push/registrar', MW + '/push/esquecer']);
  assert.deepEqual([...window.ordem], ['sair']);
  assert.deepEqual(enviados.filter((m) => m.t === 'pushAdmin').at(-1), { t: 'pushAdmin', admin: false }, 'o Sair tira os atalhos');
  assert.deepEqual(enviados.filter((m) => m.t === 'pushSaiu'), [{ t: 'pushSaiu' }]);
  window.close();
});

test('na página de verdade: a volta do login espera o site ligar a tela do App (MutationObserver), e sai com a conta escolhida', async () => {
  // A volta do loginRedirect como o campo.html a faz: a tela de login na frente, o MSAL ainda com a conta ANTERIOR ativa;
  // o concluirLoginMS fixa a escolhida (setActiveAccount) e o entrarComIdentidade troca as telas.
  const enviados = [];
  const canal = { onmessage: null, postMessage(texto) { enviados.push(JSON.parse(texto)); } };
  const dom = new JSDOM('<!doctype html><html><head></head><body><div id="s-login" class="scr on"></div><div id="s-app" class="scr"></div></body></html>', {
    url: ENDERECO_APP,
    runScripts: 'dangerously',
    beforeParse(window) {
      window.gcCasca = canal;
      window.__GC_RETRATO__ = { temEspelho: true, filaHashes: [], idbChaves: [] };
      window.localStorage.setItem('gc_casca_sentinela', '1');
      const agendar = window.setTimeout.bind(window);
      window.setTimeout = (fn, ms) => agendar(fn, ms === ESPERA_MS ? 0 : ms);
      window.autorizacoes = [];
      window.fetch = (url, init) => {
        window.autorizacoes.push(url.slice(MW.length) + ' ' + init.headers.Authorization);
        if (url === MW + '/me') return naoAdmin();
        return Promise.resolve({ ok: true, status: 200 });
      };
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
    rodar(`
      const MIDDLEWARE_URL = '${MW}';
      let ativa = 'A';
      async function getTokenMW() { return ativa; }
      function concluirLogin() {
        ativa = 'B';
        document.getElementById('s-login').classList.remove('on');
        document.getElementById('s-app').classList.add('on');
      }
    `);
    window.dispatchEvent(new window.Event('load'));
    await new Promise((r) => setTimeout(r, 20));
    canal.onmessage({ data: JSON.stringify({ t: 'pushToken', token: 'T', versao: '9.9.9' }) });
    await new Promise((r) => setTimeout(r, 20));
    assert.deepEqual([...window.autorizacoes], [], 'nada com a conta anterior');
    rodar('concluirLogin();');
    await new Promise((r) => setTimeout(r, 20));
    assert.deepEqual([...window.autorizacoes].sort(), ['/me Bearer B', '/push/registrar Bearer B']);
  } finally {
    window.close();
  }
});
