// Prova a chave da equipe do lado da página (spec 2026-11-08, seções 4.1 e 8, com os ajustes do contrato com o chat do
// Nexus): a seção 10 do www/casca/inicio.js pede ao Nexus a chave do aparelho com o login Microsoft da página e a passa
// ao nativo, que a usa com o app fechado. O que se protege aqui:
//   - uma vez por carga, com a tela do App na frente (#s-app.on) e 5 s depois do load, pelas peças da seção 8 (a espera
//     da tela do App, o Bearer e o Sair);
//   - só quando o nativo diz que o aparelho não tem a chave DESTA conta (temChave false): cada pedido gera chave nova e
//     revoga a anterior do par pessoa e aparelho;
//   - a conta é o SHA-256 de "nexus-field-equipe:" + o oid (ou, sem ele, o sub) do próprio token, e só ela vai ao nativo:
//     o token da Microsoft e o e-mail, nunca;
//   - a tentativa que não terminou tenta de novo no online e na volta ao app: na hora a que parou antes do nativo (sem
//     login), e no máximo uma vez a cada 10 minutos a que chegou ao nativo ou ao Nexus (401, 503, rede, CORS, resposta
//     torta, o nativo que não responde); a que terminou (a chave desta conta no aparelho, a chave nova ou o coleta:false
//     entregues ao nativo) não tenta mais nesta carga; nenhuma exceção escapa; depois do Sair, nada;
//   - o token que não diz de quem é e a página que não resume a conta avisam, uma vez por carga cada.
// O Nexus e o nativo são falsos: as rotas são do chat "Nexus (Usinas 360)" e ainda não estão no ar.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createHash, webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { criarJanela, ate, assentar } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar } = require('../www/casca/inicio.js');
const RAIZ = join(dirname(fileURLToPath(import.meta.url)), '..');
const ler = (caminho) => readFileSync(join(RAIZ, caminho), 'utf8');
const CODIGO = ler('www/casca/inicio.js');

// O endereço do registro do aparelho no Nexus (o contrato, seção 8 do desenho).
const NEXUS = 'https://app.gridco.com.br';
const ROTA = '/t/campo/equipe/aparelho';
// O servidor do App de Campo (o /me e o registro das notificações da seção 8), fictício.
const MW = 'https://servidor.exemplo.test/api';
const ESPERA_MS = 5000;
const DEZ_MIN_MS = 10 * 60 * 1000;

// Tudo fictício: a conta Microsoft, o e-mail, o id do aparelho e a chave.
const EMAIL = 'tecnico.ficticio@exemplo.com.br';
const OID = '00000000-0000-4000-8000-00000000000a';
const SUB = 'subFicticioDaConta_0123456789';
const APARELHO = '11111111-2222-4333-8444-555555555555';
const CHAVE = 'chaveFicticiaDaEquipe_0123456789-abcdefghij';

const b64url = (texto) => Buffer.from(texto, 'utf8').toString('base64url');
// Um token no formato do da Microsoft (o JWT: cabeçalho, corpo e assinatura em base64url), com o corpo dado. O nome com
// acento prova que o corpo em UTF-8 não atrapalha a leitura do oid.
const jwt = (corpo) => [b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' })), b64url(JSON.stringify(corpo)),
  'assinaturaFicticia'].join('.');
const TOKEN = jwt({ oid: OID, sub: SUB, preferred_username: EMAIL, name: 'Conta Fictícia', tid: 'tenantFicticio' });
// O que o nativo deve receber: o SHA-256, em hex minúsculo, do prefixo + o id da conta, calculado aqui pelo crypto do
// Node (não pelo script).
const contaDe = (id) => createHash('sha256').update('nexus-field-equipe:' + id, 'utf8').digest('hex');
const CONTA = contaDe(OID);

// As respostas do Nexus falso.
const responde = (status, corpo) => () => Promise.resolve({
  ok: status >= 200 && status < 300, status, json: () => Promise.resolve(corpo),
});
const comChave = responde(200, { coleta: true, chave: CHAVE });
const semRede = () => Promise.reject(new TypeError('Failed to fetch'));

// A regra de ouro do script: nada dele escapa para a página. Uma Promise rejeitada que ninguém tratou cai aqui.
const escapadas = [];
process.on('unhandledRejection', (e) => { escapadas.push(e); });

// A janela do App com o que a página define (o getTokenMW e o MIDDLEWARE_URL), as duas telas do campo.html (`tela` diz
// qual está na frente), o que a WebView dá à página (o crypto.subtle e o TextEncoder; `cripto` e `codificador` false
// tiram cada um) e o fetch falso: o Nexus responde pelo `estado.nexus(url, init)`; o servidor do App de Campo (o /me e o
// registro das notificações, da seção 8) responde à parte e fica fora dos `pedidos`.
function montar({ token = TOKEN, nexus = comChave, tela = 'app', cripto = true, codificador = true, ...opcoes } = {}) {
  const j = criarJanela(opcoes);
  const estado = { token, nexus, bearers: 0, resumindo: 0 };
  const pedidos = [];
  j.win.MIDDLEWARE_URL = MW;
  j.win.getTokenMW = async () => { estado.bearers++; return estado.token; };
  // O crypto do Node, com a conta dos resumos em curso: o digest roda fora do laço de eventos, e com a máquina ocupada (o
  // npm test roda os arquivos em paralelo) passa das voltas do assentar; o carregar espera por ele.
  if (cripto) {
    j.win.crypto = {
      subtle: {
        digest: (...args) => {
          estado.resumindo++;
          return webcrypto.subtle.digest(...args).finally(() => { estado.resumindo--; });
        },
      },
    };
  }
  if (codificador) j.win.TextEncoder = TextEncoder;
  j.win.fetch = (url, init) => {
    if (String(url).indexOf(MW) === 0) {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ papel: 'Tecnico' }) });
    }
    pedidos.push({ url, init, corpo: JSON.parse(init.body) });
    return estado.nexus(url, init);
  };
  const doc = j.win.document;
  const login = doc.createElement('div');
  login.id = 's-login';
  login.className = tela === 'login' ? 'scr on' : 'scr';
  const app = doc.createElement('div');
  app.id = 's-app';
  app.className = tela === 'app' ? 'scr on' : 'scr';
  doc.body.append(login, app);
  instalar(j.win, j.canal, j.retrato);
  const doTipo = (t) => j.enviados.filter((m) => m.t === t);
  // O que a seção 10 mandou ao nativo (o equipePedirAparelho e o equipeChave).
  const daEquipe = () => j.enviados.filter((m) => typeof m.t === 'string' && m.t.indexOf('equipe') === 0);
  const erros = () => j.enviados.filter((m) => m.t === 'erro');
  const entrar = () => { login.classList.remove('on'); app.classList.add('on'); };
  // O nativo responde ao equipePedirAparelho (o Mensageiro.respostaDaEquipe): o id do aparelho, a versão e se ele já tem a
  // chave desta conta.
  const nativo = (resposta = {}) =>
    j.responder({ t: 'equipeAparelho', aparelho: APARELHO, versao: '1.2.0', temChave: false, ...resposta });
  return { ...j, estado, pedidos, doTipo, daEquipe, erros, entrar, nativo };
}

// A carga seguinte na mesma WebView: o localStorage e o sessionStorage de antes.
function recarregar(j, opcoes = {}) {
  return montar({
    ls: Object.fromEntries(j.win.localStorage._itens),
    sessao: Object.fromEntries(j.win.sessionStorage._itens),
    ...opcoes,
  });
}

// O resumo da conta em curso (o da janela com o crypto do Node) terminou, e o que vem depois dele também.
async function resumido(j) {
  await assentar();
  await ate(() => !j.estado.resumindo, 'o resumo da conta');
  await assentar();
}

// O load da página e os 5 s.
async function carregar(j) {
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS);
  await resumido(j);
}

function mostrar(j) {
  j.win.document.visibilityState = 'visible';
  j.win.document.dispatchEvent({ type: 'visibilitychange' });
}

// A rede que volta, a volta ao app e a espera: o que faria uma tentativa de novo (e nada disso pede de novo depois da
// tentativa que terminou).
async function tudoQueTentariaDeNovo(j) {
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  j.avancar(DEZ_MIN_MS);
  await resumido(j);
}

const online = (j) => j.win.dispatchEvent({ type: 'online' });
// Quantas tentativas chegaram ao nativo (o equipePedirAparelho de cada uma).
const pedidosAoNativo = (j) => j.doTipo('equipePedirAparelho').length;
// Os avisos desta seção (os da seção 8, com o rótulo push, ficam de fora).
const avisosDaEquipe = (j) => j.erros().filter((e) => e.onde === 'equipe');

test('logado: o aparelho pedido ao nativo com a conta, um pedido ao Nexus com o Bearer e o corpo do contrato, e a chave ao nativo', async () => {
  const j = montar();
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS - 1);
  await assentar();
  assert.deepEqual(j.daEquipe(), [], 'espera os 5 s depois do load, como o registro das notificações');
  j.avancar(1);
  await ate(() => j.doTipo('equipePedirAparelho').length > 0, 'o equipePedirAparelho');
  assert.deepEqual(j.daEquipe(), [{ t: 'equipePedirAparelho', conta: CONTA }]);
  assert.deepEqual(j.pedidos, [], 'o Nexus só depois do que o nativo responder');

  j.nativo({ temChave: false });
  await ate(() => j.doTipo('equipeChave').length > 0, 'o equipeChave');
  assert.equal(j.pedidos.length, 1);
  const { url, init, corpo } = j.pedidos[0];
  assert.equal(url, NEXUS + ROTA);
  assert.equal(init.method, 'POST');
  assert.equal(init.headers.Authorization, 'Bearer ' + TOKEN);
  assert.equal(init.headers['Content-Type'], 'application/json');
  assert.deepEqual(corpo, { aparelho: APARELHO, app: 'NexusField/1.2.0' });
  // De fundo (o gcQuieto do site: fora da barra de carregamento), sem cookie (o Nexus confere só o Bearer) e sem seguir
  // redirecionamento (o Bearer não vai a outro endereço).
  assert.equal(init.gcQuieto, true);
  assert.equal(init.credentials, 'omit');
  assert.equal(init.redirect, 'error');
  assert.deepEqual(j.doTipo('equipeChave'), [{ t: 'equipeChave', coleta: true, chave: CHAVE, conta: CONTA }]);

  // A tentativa terminou (a chave nova entregue ao nativo): nem a rede que volta, nem a volta ao app, nem a espera pedem
  // de novo nesta carga, porque cada pedido troca a chave.
  await tudoQueTentariaDeNovo(j);
  await tudoQueTentariaDeNovo(j);
  assert.equal(j.pedidos.length, 1);
  assert.equal(j.doTipo('equipePedirAparelho').length, 1);
  assert.equal(j.doTipo('equipeChave').length, 1);
  assert.deepEqual(j.erros(), []);
  assert.deepEqual(escapadas, []);
});

test('a conta: o SHA-256, em hex minúsculo, de "nexus-field-equipe:" + o oid do token; sem o oid, o sub', async () => {
  for (const [nome, corpo, id] of [
    ['com o oid', { oid: OID, sub: SUB, preferred_username: EMAIL }, OID],
    ['sem o oid', { sub: SUB, preferred_username: EMAIL }, SUB],
    ['com o oid vazio', { oid: '', sub: SUB }, SUB],
    ['com o oid que não é texto', { oid: 12345, sub: SUB }, SUB],
  ]) {
    const j = montar({ token: jwt(corpo) });
    await carregar(j);
    assert.match(contaDe(id), /^[0-9a-f]{64}$/);
    assert.deepEqual(j.doTipo('equipePedirAparelho'), [{ t: 'equipePedirAparelho', conta: contaDe(id) }], nome);
  }
  // Outra conta no mesmo aparelho, outra conta no nativo.
  const outra = montar({ token: jwt({ oid: '00000000-0000-4000-8000-00000000000b', sub: SUB }) });
  await carregar(outra);
  assert.notEqual(outra.doTipo('equipePedirAparelho')[0].conta, CONTA);
});

test('o token que não diz de quem é e a página que não resume a conta: nada vai ao nativo, e um aviso da equipe por carga', async () => {
  // São as falhas que só aparecem no aparelho (o token que o getTokenMW dá, a WebView do celular): caladas, o piloto não
  // teria como saber por que o aparelho não entra na coleta.
  const resumoCurto = { subtle: { digest: () => Promise.resolve(new ArrayBuffer(16)) } };
  for (const [nome, opcoes, assunto, cripto] of [
    ['JWT sem oid nem sub', { token: jwt({ preferred_username: EMAIL, name: 'Conta Fictícia' }) }, 'token'],
    ['JWT com o oid e o sub vazios', { token: jwt({ oid: '', sub: '' }) }, 'token'],
    ['JWT com o corpo que não é JSON', { token: [b64url('{}'), b64url('não é JSON'), 'x'].join('.') }, 'token'],
    ['JWT com o corpo que não é objeto', { token: [b64url('{}'), b64url('42'), 'x'].join('.') }, 'token'],
    ['JWT com o corpo que não é base64', { token: 'a.%%%.c' }, 'token'],
    ['token opaco', { token: 'tokenOpacoFicticio' }, 'token'],
    ['token com quatro partes', { token: TOKEN + '.extra' }, 'token'],
    ['sem o crypto.subtle', { cripto: false }, 'resum'],
    ['sem o TextEncoder', { codificador: false }, 'resum'],
    ['o resumo fora do formato', { cripto: false }, 'resum', resumoCurto],
  ]) {
    const j = montar(opcoes);
    if (cripto) j.win.crypto = cripto;
    await carregar(j);
    // Um equipeAparelho que chegasse assim mesmo não vale: não houve pedido nesta carga.
    j.nativo({ temChave: false });
    await assentar();
    // As novas tentativas (o online, a volta ao app, a espera) falham igual, e o aviso não se repete nesta carga.
    await tudoQueTentariaDeNovo(j);
    await tudoQueTentariaDeNovo(j);
    assert.deepEqual(j.daEquipe(), [], nome);
    assert.deepEqual(j.pedidos, [], nome);
    const avisos = avisosDaEquipe(j);
    assert.equal(avisos.length, 1, nome + ': um aviso por carga');
    assert.ok(avisos[0].msg.includes(assunto), nome + ': ' + avisos[0].msg);
    // A carga seguinte avisa de novo (o aviso é por carga).
    const k = recarregar(j, opcoes);
    if (cripto) k.win.crypto = cripto;
    await carregar(k);
    assert.equal(avisosDaEquipe(k).length, 1, nome + ': na carga seguinte');
  }
  assert.deepEqual(escapadas, []);
});

test('sem login (o Bearer null): nada vai ao nativo, nada avisa, e o online ou a volta ao app tentam na hora', async () => {
  for (const [nome, gatilho] of [['a volta ao app', mostrar], ['o online', online]]) {
    const j = montar({ token: null });
    await carregar(j);
    gatilho(j); // ainda sem login: nada
    await assentar();
    assert.deepEqual(j.daEquipe(), [], nome);
    // A sessão volta (a rede, o MSAL que renovou o token): a tentativa não espera os 10 minutos, porque nada foi pedido
    // a ninguém.
    j.estado.token = TOKEN;
    gatilho(j);
    await ate(() => pedidosAoNativo(j) === 1, nome + ': o pedido ao nativo');
    assert.deepEqual(j.doTipo('equipePedirAparelho'), [{ t: 'equipePedirAparelho', conta: CONTA }], nome);
    j.nativo();
    await ate(() => j.doTipo('equipeChave').length === 1, nome + ': o equipeChave');
    // O estado comum de quem ainda não entrou não é erro: nada de aviso a cada abertura.
    assert.deepEqual(avisosDaEquipe(j), [], nome);
  }
  assert.deepEqual(escapadas, []);
});

test('o token que não diz de quem é, e depois o token com o oid: a volta ao app tenta, sem outro aviso', async () => {
  const j = montar({ token: 'tokenOpacoFicticio' });
  await carregar(j);
  assert.equal(avisosDaEquipe(j).length, 1);
  j.estado.token = TOKEN;
  mostrar(j);
  await ate(() => pedidosAoNativo(j) === 1, 'o pedido ao nativo');
  j.nativo();
  await ate(() => j.doTipo('equipeChave').length === 1, 'o equipeChave');
  assert.equal(avisosDaEquipe(j).length, 1);
});

test('na tela de login: nada até o #s-app ganhar o .on, e aí um pedido só', async () => {
  // A volta do login, com a conta ANTERIOR ainda ativa no MSAL (a seção 8): o getTokenMW nem é chamado antes da tela do
  // App, e a conta que vai ao nativo é a de quem entrou.
  const j = montar({ tela: 'login', token: jwt({ oid: '00000000-0000-4000-8000-0000000000ff' }) });
  await carregar(j);
  j.avancar(30000);
  await assentar();
  assert.deepEqual(j.daEquipe(), []);
  assert.equal(j.estado.bearers, 0, 'nem o getTokenMW é chamado');
  j.estado.token = TOKEN;
  j.entrar();
  j.avancar(1000); // a consulta de 1 s da seção 8 (a janela falsa não tem MutationObserver)
  await ate(() => j.doTipo('equipePedirAparelho').length > 0, 'o equipePedirAparelho');
  assert.deepEqual(j.doTipo('equipePedirAparelho'), [{ t: 'equipePedirAparelho', conta: CONTA }]);
  j.nativo();
  await ate(() => j.doTipo('equipeChave').length > 0, 'o equipeChave');
  await tudoQueTentariaDeNovo(j);
  assert.equal(j.pedidos.length, 1);
  assert.equal(j.doTipo('equipePedirAparelho').length, 1);
});

test('com a chave desta conta no aparelho (temChave true), nada vai ao Nexus; sem ela, um pedido', async () => {
  const j = montar();
  await carregar(j);
  j.nativo({ temChave: true });
  await assentar();
  assert.deepEqual(j.pedidos, []);
  assert.deepEqual(j.doTipo('equipeChave'), []);
  // A tentativa terminou: nada de nova tentativa nesta carga (pedir de novo trocaria a chave à toa).
  await tudoQueTentariaDeNovo(j);
  await tudoQueTentariaDeNovo(j);
  assert.equal(pedidosAoNativo(j), 1);
  assert.deepEqual(j.pedidos, []);
  // O nativo que não diz (sem o campo, ou com outro valor): também nada; pedir sem saber trocaria a chave à toa. A
  // tentativa não terminou: passados os 10 minutos, o online pergunta ao nativo de novo.
  for (const temChave of [undefined, null, 'false', 0]) {
    const k = montar();
    await carregar(k);
    k.nativo({ temChave });
    await assentar();
    assert.deepEqual(k.pedidos, [], String(temChave));
    k.avancar(DEZ_MIN_MS);
    online(k);
    await ate(() => pedidosAoNativo(k) === 2, String(temChave) + ': a nova tentativa');
  }
  const s = montar();
  await carregar(s);
  s.nativo({ temChave: false });
  await assentar();
  assert.equal(s.pedidos.length, 1);
});

test('duas cargas da mesma página não fazem dois pedidos; a carga seguinte só pede se o nativo disser que não tem', async () => {
  const j = montar();
  j.nativo(); // a resposta sem o pedido desta carga (atrasada, ou de outro): não vale
  await carregar(j);
  j.win.dispatchEvent({ type: 'load' }); // o segundo load (o navegador não o dispara; o ouvinte é de uma vez só)
  j.avancar(ESPERA_MS);
  await assentar();
  assert.equal(j.doTipo('equipePedirAparelho').length, 1);
  assert.deepEqual(j.pedidos, []);
  j.nativo();
  j.nativo(); // a resposta repetida não faz outro pedido
  await assentar();
  assert.equal(j.pedidos.length, 1);
  assert.equal(j.doTipo('equipeChave').length, 1);

  // A carga seguinte, na mesma WebView: o nativo já tem a chave desta conta, e nada vai ao Nexus.
  const k = recarregar(j);
  await carregar(k);
  assert.deepEqual(k.doTipo('equipePedirAparelho'), [{ t: 'equipePedirAparelho', conta: CONTA }]);
  k.nativo({ temChave: true });
  await assentar();
  assert.deepEqual(k.pedidos, []);
  // E a de depois de um 401 do envio (o nativo apagou a chave): pede de novo.
  const p = recarregar(k);
  await carregar(p);
  p.nativo({ temChave: false });
  await assentar();
  assert.equal(p.pedidos.length, 1);
});

test('o Bearer do Nexus é o mesmo token de que saiu a conta', async () => {
  // A conta que vai com a chave é a do token que o Nexus conferiu: o token não é pedido de novo entre os dois.
  const j = montar();
  await carregar(j);
  assert.equal(j.doTipo('equipePedirAparelho')[0].conta, CONTA);
  j.estado.token = jwt({ oid: '00000000-0000-4000-8000-00000000000c' });
  j.nativo();
  await ate(() => j.doTipo('equipeChave').length > 0, 'o equipeChave');
  assert.equal(j.pedidos[0].init.headers.Authorization, 'Bearer ' + TOKEN);
  assert.equal(j.doTipo('equipeChave')[0].conta, CONTA);
});

test('o token da Microsoft, o oid e o e-mail não vão ao nativo: só o hash da conta, nas duas mensagens', async () => {
  const j = montar();
  await carregar(j);
  j.nativo();
  await ate(() => j.doTipo('equipeChave').length > 0, 'o equipeChave');
  const [cabecalho, corpo, assinatura] = TOKEN.split('.');
  const tudo = JSON.stringify(j.enviados);
  for (const proibido of [TOKEN, cabecalho, corpo, assinatura, OID, SUB, EMAIL, 'tecnico.ficticio', 'tenantFicticio']) {
    assert.equal(tudo.includes(proibido), false, `foi ao nativo: ${proibido}`);
  }
  assert.equal(j.doTipo('equipePedirAparelho')[0].conta, CONTA);
  assert.equal(j.doTipo('equipeChave')[0].conta, CONTA);
});

test('o coleta:false do Nexus vai ao nativo com a conta e sem chave, e não tenta de novo', async () => {
  const j = montar({ nexus: responde(200, { coleta: false, chave: CHAVE }) });
  await carregar(j);
  j.nativo();
  await ate(() => j.doTipo('equipeChave').length > 0, 'o equipeChave');
  assert.deepEqual(j.doTipo('equipeChave'), [{ t: 'equipeChave', coleta: false, conta: CONTA }]);
  // A resposta do Nexus terminou a tentativa: o aparelho não entra na coleta, e pedir de novo não muda isso.
  await tudoQueTentariaDeNovo(j);
  await tudoQueTentariaDeNovo(j);
  assert.equal(pedidosAoNativo(j), 1);
  assert.equal(j.pedidos.length, 1);
});

test('503 e depois o online: tenta de novo, no máximo uma vez a cada 10 minutos (o online da espera tenta quando ela acaba)', async () => {
  const j = montar({ nexus: responde(503, {}) });
  await carregar(j);
  j.nativo();
  await assentar();
  assert.equal(j.pedidos.length, 1, 'a primeira tentativa, 5 s depois do load');
  // A rede volta logo depois: dentro da espera, nada agora.
  online(j);
  await assentar();
  assert.equal(pedidosAoNativo(j), 1, 'o online antes dos 10 minutos não tenta na hora');
  j.avancar(DEZ_MIN_MS / 2);
  online(j);
  mostrar(j);
  await assentar();
  assert.equal(pedidosAoNativo(j), 1, 'de novo antes dos 10 minutos: nada');
  // Os 10 minutos do pedido ao nativo: o online que esperava tenta, uma vez.
  j.avancar(DEZ_MIN_MS / 2);
  await ate(() => pedidosAoNativo(j) === 2, 'a segunda tentativa');
  j.nativo();
  await assentar();
  assert.equal(j.pedidos.length, 2);
  // Sem online nem volta ao app, nada: sem laço.
  j.avancar(DEZ_MIN_MS * 3);
  await assentar();
  assert.equal(pedidosAoNativo(j), 2, 'sem laço');
  // Passados os 10 minutos, o online tenta na hora.
  online(j);
  await ate(() => pedidosAoNativo(j) === 3, 'a terceira tentativa');
  // O Nexus de volta: a chave vai ao nativo, e a tentativa terminou.
  j.estado.nexus = comChave;
  j.nativo();
  await ate(() => j.doTipo('equipeChave').length === 1, 'o equipeChave');
  await tudoQueTentariaDeNovo(j);
  await tudoQueTentariaDeNovo(j);
  assert.equal(pedidosAoNativo(j), 3, 'depois do sucesso, nem o online nem a volta ao app tentam');
  assert.equal(j.pedidos.length, 3);
  assert.deepEqual(avisosDaEquipe(j).map((e) => e.msg.includes('503')), [true, true], 'um aviso por falha');
  assert.deepEqual(escapadas, []);
});

test('a volta ao app também tenta de novo depois da falha que chegou ao Nexus, passados os 10 minutos', async () => {
  const j = montar({ nexus: semRede });
  await carregar(j);
  j.nativo();
  await assentar();
  assert.equal(j.pedidos.length, 1);
  mostrar(j);
  await assentar();
  assert.equal(pedidosAoNativo(j), 1, 'na espera, nada');
  j.avancar(DEZ_MIN_MS);
  await ate(() => pedidosAoNativo(j) === 2, 'a volta ao app que esperava tenta quando a espera acaba');
  j.estado.nexus = comChave;
  j.nativo();
  await ate(() => j.doTipo('equipeChave').length === 1, 'o equipeChave');
});

test('o nativo que não responde: a nova tentativa vem com o online, passados os 10 minutos do pedido', async () => {
  const j = montar();
  await carregar(j);
  assert.equal(pedidosAoNativo(j), 1);
  online(j);
  j.avancar(DEZ_MIN_MS - 1);
  await assentar();
  assert.equal(pedidosAoNativo(j), 1, 'esperando o nativo, nada antes dos 10 minutos');
  j.avancar(1);
  // A resposta atrasada da primeira chega antes do pedido da segunda: não vale (levaria ao Nexus um segundo pedido).
  j.nativo();
  await ate(() => pedidosAoNativo(j) === 2, 'a segunda tentativa');
  await assentar();
  assert.deepEqual(j.pedidos, []);
  // A resposta à segunda, e uma repetida: um pedido só ao Nexus.
  j.nativo();
  j.nativo();
  await ate(() => j.doTipo('equipeChave').length === 1, 'o equipeChave');
  await assentar();
  assert.equal(j.pedidos.length, 1);
});

test('a chave que não chegou ao nativo (o canal que falhou) tenta de novo: o nativo não ficou sabendo', async () => {
  const j = montar();
  const postar = j.canal.postMessage;
  let canalFora = true;
  j.canal.postMessage = function (texto) {
    if (canalFora && texto.includes('"equipeChave"')) throw new Error('o canal caiu');
    return postar.call(this, texto);
  };
  await carregar(j);
  j.nativo();
  await ate(() => avisosDaEquipe(j).length === 1, 'o aviso do canal');
  assert.equal(j.pedidos.length, 1);
  canalFora = false;
  j.avancar(DEZ_MIN_MS);
  online(j);
  await ate(() => pedidosAoNativo(j) === 2, 'a nova tentativa');
  j.nativo();
  await ate(() => j.doTipo('equipeChave').length === 1, 'o equipeChave');
});

test('com o pedido ao Nexus no ar, nada começa outro: dois pedidos trariam duas chaves', async () => {
  let soltar;
  const j = montar({ nexus: () => new Promise((r) => { soltar = r; }) });
  await carregar(j);
  j.nativo();
  await ate(() => j.pedidos.length === 1, 'o pedido ao Nexus');
  online(j);
  mostrar(j);
  j.avancar(DEZ_MIN_MS * 2);
  online(j);
  mostrar(j);
  await assentar();
  assert.equal(pedidosAoNativo(j), 1, 'nem o online, nem a volta ao app, nem o fim da espera');
  // O Nexus responde 503: a tentativa não terminou, e o próximo online tenta (os 10 minutos já passaram).
  soltar({ ok: false, status: 503, json: () => Promise.resolve({}) });
  await assentar();
  assert.equal(pedidosAoNativo(j), 1);
  online(j);
  await ate(() => pedidosAoNativo(j) === 2, 'a nova tentativa');
});

test('401, 503, outro status, a rede, o CORS e a resposta torta: um aviso, nenhuma exceção que escape, e a nova tentativa no online ou na volta ao app', async () => {
  const json200 = (json) => () => Promise.resolve({ ok: true, status: 200, json });
  for (const [nome, nexus] of [
    ['401 (o token vencido)', responde(401, { erro: 'token' })],
    ['503 (a Microsoft fora, o Nexus sem configuração)', responde(503, {})],
    ['500', responde(500, {})],
    ['404 (a rota ainda fora do ar)', responde(404, {})],
    ['204', responde(204, null)],
    ['201 com a chave (o contrato é o 200)', responde(201, { coleta: true, chave: CHAVE })],
    ['sem rede ou o CORS (o TypeError do fetch)', semRede],
    ['o fetch que lança', () => { throw new TypeError('fetch quebrado'); }],
    ['a resposta nula', () => Promise.resolve(null)],
    ['200 que não é JSON', json200(() => Promise.reject(new SyntaxError('Unexpected token <')))],
    ['200 com o json que lança', json200(() => { throw new TypeError('sem corpo'); })],
    ['200 sem a chave', responde(200, { coleta: true })],
    ['200 com a chave vazia', responde(200, { coleta: true, chave: '' })],
    ['200 com a chave que não é texto', responde(200, { coleta: true, chave: 123 })],
    ['200 sem o coleta', responde(200, { chave: CHAVE })],
    ['200 com o coleta que não é booleano', responde(200, { coleta: 'true', chave: CHAVE })],
    ['200 nulo', responde(200, null)],
  ]) {
    const j = montar({ nexus });
    await carregar(j);
    j.nativo();
    await assentar();
    assert.equal(j.pedidos.length, 1, nome + ': o pedido saiu');
    assert.deepEqual(j.doTipo('equipeChave'), [], nome);
    const avisos = j.erros();
    assert.ok(avisos.length === 1 && avisos[0].onde === 'equipe', nome + ': um aviso, com o rótulo da equipe');
    // Sem laço: sem o online nem a volta ao app, nada mais.
    j.avancar(DEZ_MIN_MS * 2);
    await assentar();
    assert.equal(pedidosAoNativo(j), 1, nome + ': sem laço');
    // A volta ao app, passados os 10 minutos, tenta de novo (o pedido ao nativo, que aqui não responde).
    mostrar(j);
    await ate(() => pedidosAoNativo(j) === 2, nome + ': a nova tentativa');
    assert.equal(j.pedidos.length, 1, nome);
  }
  // O nativo que responde sem o id do aparelho: nada vai ao Nexus, avisa, e o próximo online tenta de novo.
  const k = montar();
  await carregar(k);
  k.nativo({ aparelho: '' });
  await assentar();
  assert.deepEqual(k.pedidos, []);
  assert.deepEqual(k.erros().map((e) => e.onde), ['equipe']);
  k.avancar(DEZ_MIN_MS);
  online(k);
  await ate(() => pedidosAoNativo(k) === 2, 'a nova tentativa');
  assert.deepEqual(escapadas, []);
});

test('depois do Sair, nada: nem o pedido ao nativo, nem o Nexus, nem a chave que chegar depois', { timeout: 10000 }, async () => {
  const sairDaPagina = async function () { return 'saiu'; };
  // O Sair antes dos 5 s.
  const a = montar();
  a.win.sair = sairDaPagina;
  a.win.dispatchEvent({ type: 'load' }); // o sair da página é embrulhado no load (seção 8)
  await a.win.sair();
  a.avancar(ESPERA_MS);
  await assentar();
  assert.deepEqual(a.daEquipe(), []);
  assert.equal(a.estado.bearers, 0);

  // O Sair com o Bearer no ar (o getTokenMW que demora): o pedido ao nativo não sai.
  const b = montar();
  b.win.sair = sairDaPagina;
  let soltarBearer;
  b.win.dispatchEvent({ type: 'load' });
  b.win.getTokenMW = () => new Promise((r) => { soltarBearer = () => r(TOKEN); });
  b.avancar(ESPERA_MS);
  await assentar();
  await b.win.sair();
  soltarBearer();
  await assentar();
  assert.deepEqual(b.daEquipe(), []);

  // O Sair com o pedido ao nativo no ar: o Nexus não é chamado.
  const c = montar();
  c.win.sair = sairDaPagina;
  await carregar(c);
  assert.equal(c.doTipo('equipePedirAparelho').length, 1);
  await c.win.sair();
  c.nativo();
  await assentar();
  assert.deepEqual(c.pedidos, []);

  // O Sair com o pedido ao Nexus no ar: a chave que chegar é de quem saiu, e não vai ao nativo.
  let soltar;
  const d = montar({ nexus: () => new Promise((r) => { soltar = r; }) });
  d.win.sair = sairDaPagina;
  await carregar(d);
  d.nativo();
  await ate(() => d.pedidos.length === 1, 'o pedido ao Nexus');
  await d.win.sair();
  soltar({ ok: true, status: 200, json: () => Promise.resolve({ coleta: true, chave: CHAVE }) });
  await assentar();
  assert.deepEqual(d.doTipo('equipeChave'), []);

  // O Sair depois de uma falha: nem o online que esperava, nem a volta ao app, nem o fim da espera tentam de novo.
  const e = montar({ nexus: responde(503, {}) });
  e.win.sair = sairDaPagina;
  await carregar(e);
  e.nativo();
  await assentar();
  online(e); // na espera: tentaria quando ela acabasse
  await e.win.sair();
  await tudoQueTentariaDeNovo(e);
  await tudoQueTentariaDeNovo(e);
  assert.equal(pedidosAoNativo(e), 1);

  // E o sem login, que tentaria na hora.
  const f = montar({ token: null });
  f.win.sair = sairDaPagina;
  await carregar(f);
  await f.win.sair();
  f.estado.token = TOKEN;
  await tudoQueTentariaDeNovo(f);
  assert.deepEqual(f.daEquipe(), []);
  assert.deepEqual(escapadas, []);
});

test('a página sem o getTokenMW ou com o getTokenMW que lança: nada da equipe, e nada escapa', async () => {
  for (const falta of ['getTokenMW', 'lança']) {
    const j = montar();
    if (falta === 'lança') j.win.getTokenMW = async () => { throw new Error('sem conta para ' + EMAIL); };
    else delete j.win.getTokenMW;
    await carregar(j);
    j.nativo();
    await assentar();
    assert.deepEqual(j.daEquipe(), [], falta);
    assert.deepEqual(j.pedidos, [], falta);
    // O aviso do login que falta é o da seção 8 (o rótulo push), não um da equipe a cada abertura.
    assert.deepEqual(avisosDaEquipe(j), [], falta);
    // A página que passa a dar o token: a volta ao app tenta na hora (nada foi pedido a ninguém).
    j.win.getTokenMW = async () => TOKEN;
    mostrar(j);
    await ate(() => pedidosAoNativo(j) === 1, falta + ': a nova tentativa');
  }
  assert.deepEqual(escapadas, []);
});

test('os avisos ao nativo não levam o token, o e-mail, o oid, a conta, o aparelho nem a chave', async () => {
  const citando = `${NEXUS}${ROTA}?t=${TOKEN} ${EMAIL} ${OID} ${CONTA} ${APARELHO} ${CHAVE}`;
  const avisos = [];
  for (const nexus of [
    () => Promise.reject(new TypeError('falhou ' + citando)),
    responde(401, { erro: citando }),
    responde(200, { coleta: true, chave: 42, eco: citando }),
    () => Promise.resolve({ ok: true, status: 200, json: () => Promise.reject(new SyntaxError(citando)) }),
  ]) {
    const j = montar({ nexus });
    await carregar(j);
    j.nativo();
    await assentar();
    avisos.push(...j.erros());
  }
  // O resumo da conta que falha (o crypto.subtle que lança), citando a conta.
  const r = montar({ cripto: false });
  r.win.crypto = { subtle: { digest: () => Promise.reject(new Error('falhou ' + citando)) } };
  await carregar(r);
  avisos.push(...r.erros());
  assert.deepEqual(r.daEquipe(), []);
  // O token que não diz de quem é, com o e-mail, o nome e o resto no corpo; e a página sem o crypto, com o token bom.
  const s = montar({ token: jwt({ preferred_username: EMAIL, name: 'Conta Fictícia', eco: citando }) });
  await carregar(s);
  avisos.push(...s.erros());
  const t = montar({ cripto: false });
  await carregar(t);
  avisos.push(...t.erros());

  assert.equal(avisos.length, 7, 'cada falha avisou: ' + avisos.length);
  for (const e of avisos) {
    assert.equal(e.onde, 'equipe');
    for (const proibido of [TOKEN, 'eyJ', EMAIL, 'tecnico.ficticio', '@exemplo', OID, CONTA, APARELHO, CHAVE, 'chaveFicticia']) {
      assert.equal(e.msg.includes(proibido), false, `o aviso levou "${proibido}": ${e.msg}`);
    }
  }
  assert.deepEqual(escapadas, []);
});

test('a carga parada para o retrato não pede nada', async () => {
  // A perda (a WebView sem nada e o nativo com espelho) para a carga: a página nem roda, e o nativo recarrega.
  const j = montar({ ls: {}, retrato: { temEspelho: true, filaHashes: [], idbChaves: [] } });
  assert.equal(j.paradas.length, 1, 'a carga parou');
  await carregar(j);
  j.nativo();
  await assentar();
  assert.deepEqual(j.daEquipe(), []);
  assert.deepEqual(j.pedidos, []);
});

test('o endereço do Nexus é o do nativo, e a seção 10 só usa as peças da seção 8, que a liga no fim', () => {
  // O mesmo Nexus dos dois lados: a página registra o aparelho onde o nativo manda as posições e revoga a chave.
  const envio = ler('android/app/src/main/java/br/com/gridco/campo/EnvioDePontos.java');
  const doNativo = envio.match(/static final String NEXUS = "([^"]+)";/);
  assert.ok(doNativo, 'o EnvioDePontos.NEXUS');
  assert.equal(doNativo[1], NEXUS);

  const secao = CODIGO.slice(CODIGO.indexOf('// 10. A chave da equipe'), CODIGO.indexOf('// Ponto único de entrada'));
  assert.ok(secao.includes('function instalarEquipe('), 'o corte pega a seção 10');
  // As constantes lidas do texto como ele é (o corte dos comentários, abaixo, cortaria o "//" do endereço).
  const constante = (nome) => (secao.match(new RegExp(`\\n  var ${nome} = '([^']+)';\\n`)) || [])[1];
  assert.equal(constante('NEXUS_DA_EQUIPE'), NEXUS, 'o endereço do Nexus');
  assert.equal(constante('ROTA_DO_APARELHO'), ROTA, 'a rota do contrato');
  assert.equal(constante('PREFIXO_DA_CONTA'), 'nexus-field-equipe:', 'o prefixo da conta');
  const codigo = secao.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
  assert.ok(codigo.includes('NEXUS_DA_EQUIPE + ROTA_DO_APARELHO'), 'o pedido vai a esse endereço');
  // Nenhum nome do site: a tela do App, o Bearer e o Sair vêm da seção 8 (que tem os nomes no PUSH, conferidos pelo
  // contrato diário), sem cópia.
  assert.equal(codigo.match(/getTokenMW|MIDDLEWARE_URL|s-app|'on'|\bsair\b|PUSH\.|appNaTela|getElementById/g), null);
  for (const peca of ['pecas.quandoLogado(', 'pecas.pedirBearer(', 'pecas.saiu(']) assert.ok(codigo.includes(peca), peca);
  // A última coisa do instalarPush, depois de tudo dele estar de pé, e dentro do `tentar`: a seção 10 que falhar ao
  // instalar não leva junto o registro das notificações.
  const push = CODIGO.slice(CODIGO.indexOf('function instalarPush('), CODIGO.indexOf('// 9. Abrir a OS pelo toque'));
  assert.match(push, /tentar\(ctx, 'equipe', function \(\) \{\s*instalarEquipe\(ctx, \{ quandoLogado: quandoLogado, pedirBearer: pedirBearer, saiu: function \(\) \{ return saiu; \} \}\);\s*\}\);\s*\}\s*\/\/ -{20,}/);
});
