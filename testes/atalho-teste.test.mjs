// Prova o atalho de teste dos avisos, do lado do script de início (18/10/2026): o dono quis testar as notificações no
// celular por um botão que só o administrador vê, e o botão é um atalho do ícone (toque longo), para a casca não
// desenhar nada na página. O script faz duas coisas, com o Bearer da própria página (o nativo nunca vê token da
// Microsoft), e as duas só com a tela do App na frente (#s-app.on: antes dela, na volta do login, o getTokenMW dá a
// conta ativa anterior):
//   - uma vez por carga, depois do login e sem depender do token do Firebase, pergunta ao GET {MIDDLEWARE_URL}/me se a
//     conta é 'Admin' e manda ao nativo só {t:'pushAdmin', admin}: true publica os atalhos, false os tira (outro papel,
//     erro, 401, a página sem o que a seção usa e o Sair). Do /me não vai mais nada (o e-mail e o nome ficam na página);
//   - window.__gcCascaTestarAvisos(canal, pedido), que o nativo chama no toque do atalho: o POST
//     {MIDDLEWARE_URL}/push/teste {canal} e o resultado de volta, {t:'pushTeste', ok, status, aparelhos, pedido}, para o
//     toast. O pedido é o número que o nativo deu ao toque, devolvido igual na aceitação e no resultado (é por ele que o
//     nativo liga a resposta ao toque); o recusado não tem resultado. Só os dois canais da emergência passam, e a
//     contagem tem o mesmo teto que o nativo aceita.
// O servidor é falso (as rotas são da sessão "App de campo", no ar desde a v286); o e-mail, o nome e os tokens são
// fictícios.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { criarJanela, assentar } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, APARELHOS_NO_MAXIMO } = require('../www/casca/inicio.js');

const MW = 'https://servidor.exemplo.test/api';
const ESPERA_MS = 5000;
const DEZ_MIN_MS = 10 * 60 * 1000;
const PRAZO_DO_TESTE_MS = 20000;
const TOKEN = 'tokenFcmFicticio:APA91b_abc123';
const BEARER = 'bearerFicticio.contaAdmin';
// O que o /me devolve de verdade traz o e-mail e o nome da conta: nada disso pode chegar ao nativo.
const EMAIL = 'admin.ficticio@exemplo.com.br';
const NOME = 'Fulano Ficticio';
const doMe = (papel) => ({ email: EMAIL, nome: NOME, papel, clusters: ['cluster-ficticio'] });

const responde = (status, corpo) => () => Promise.resolve({
  ok: status >= 200 && status < 300,
  status,
  json: () => Promise.resolve(corpo),
});
const semRede = () => Promise.reject(new TypeError('Failed to fetch ' + EMAIL));
const nunca = () => new Promise(() => {});
// A resposta do /push/teste no ar (v286): a contagem e o que saiu para cada aparelho.
const ENVIADO = { aparelhos: 2, resumo: [{ ok: true }, { ok: true }], emergencia: [{ ok: true }, { ok: true }] };

// A janela do App com o que a página define (o getTokenMW e o MIDDLEWARE_URL) e o servidor falso, com uma resposta por
// rota: `me` (o GET /me), `teste` (o POST /push/teste) e `registro` (o /push/registrar e o /push/esquecer).
// A tela do App (`#s-app`) vem com o .on, menos com `tela: 'login'`; `entrar()` a liga, como o entrarComIdentidade do site.
function montar({ bearer = BEARER, papel = 'Admin', me, teste, registro, tela = 'app', ...opcoes } = {}) {
  const j = criarJanela(opcoes);
  const estado = {
    bearer,
    me: me || responde(200, doMe(papel)),
    teste: teste || responde(200, ENVIADO),
    registro: registro || responde(200, {}),
  };
  const pedidos = [];
  j.win.MIDDLEWARE_URL = MW;
  j.win.getTokenMW = async () => estado.bearer;
  j.win.fetch = (url, init = {}) => {
    pedidos.push({ url, init, corpo: init.body === undefined ? undefined : JSON.parse(init.body) });
    if (url === MW + '/me') return estado.me(url, init);
    if (url === MW + '/push/teste') return estado.teste(url, init);
    return estado.registro(url, init);
  };
  const app = j.win.document.createElement('div');
  app.id = 's-app';
  app.className = tela === 'app' ? 'scr on' : 'scr';
  j.win.document.body.appendChild(app);
  instalar(j.win, j.canal, j.retrato);
  const doTipo = (t) => j.enviados.filter((m) => m.t === t);
  const rota = (r) => pedidos.filter((p) => p.url === MW + r);
  return {
    ...j, estado, pedidos, doTipo,
    admins: () => doTipo('pushAdmin'),
    resultados: () => doTipo('pushTeste'),
    perguntas: () => rota('/me'),
    testes: () => rota('/push/teste'),
    erros: () => doTipo('erro'),
    entrar: () => app.classList.add('on'),
  };
}

// O load da página, os 5 s e a resposta do nativo ao pushPedirToken (a vez do registro do aparelho).
async function carregar(j, token = TOKEN) {
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS);
  j.responder({ t: 'pushToken', token, versao: '1.1.0' });
  await assentar();
}

function mostrar(j) {
  j.win.document.visibilityState = 'visible';
  j.win.document.dispatchEvent({ type: 'visibilitychange' });
}

// Uma promessa que o teste resolve quando quiser (o servidor que ainda não respondeu).
function depois() {
  let resolver;
  const promessa = new Promise((r) => { resolver = r; });
  return { promessa, resolver };
}

// ---------------------------------------------------------------------------------------------------------------------
// O papel da conta (GET /me)
// ---------------------------------------------------------------------------------------------------------------------

test('o /me do Admin manda admin:true, uma vez por carga, depois do login e com o Bearer da página', async () => {
  const j = montar();
  j.win.dispatchEvent({ type: 'load' });
  j.avancar(ESPERA_MS - 1);
  await assentar();
  assert.deepEqual(j.perguntas(), [], 'espera os 5 s depois do load, como o pedido do token');
  j.avancar(1);
  await assentar();
  // Sem esperar o token do Firebase: o atalho de teste manda aos aparelhos da conta, e um aparelho sem o Firebase
  // (ou com o token ainda a caminho) não pode deixar o Admin sem o atalho.
  assert.equal(j.perguntas().length, 1, 'antes do token do Firebase');
  j.responder({ t: 'pushToken', token: TOKEN, versao: '1.1.0' });
  await assentar();
  assert.equal(j.perguntas().length, 1);
  const { init } = j.perguntas()[0];
  assert.equal(init.method, 'GET');
  assert.equal(init.headers.Authorization, 'Bearer ' + BEARER);
  assert.equal(init.body, undefined, 'o GET não leva corpo');
  // De fundo, como o registro: fora da barra de carregamento do campo.html (v284).
  assert.equal(init.gcQuieto, true);
  assert.deepEqual(j.admins(), [{ t: 'pushAdmin', admin: true }]);

  // Uma vez por carga: nem a rede que volta, nem a volta ao app, nem o tempo, nem outra resposta do nativo perguntam
  // de novo.
  j.win.dispatchEvent({ type: 'online' });
  mostrar(j);
  j.avancar(DEZ_MIN_MS);
  j.responder({ t: 'pushToken', token: 'outroToken', versao: '1.1.0' });
  await assentar();
  assert.equal(j.perguntas().length, 1);
  assert.deepEqual(j.admins(), [{ t: 'pushAdmin', admin: true }]);
  assert.deepEqual(j.erros(), []);
});

test('outro papel, 401, erro do servidor, sem rede ou resposta torta mandam admin:false', async () => {
  const casos = {
    'papel Tecnico': { papel: 'Tecnico' },
    'papel Supervisor': { papel: 'Supervisor' },
    'admin minúsculo': { papel: 'admin' },
    'Admin com espaço': { papel: 'Admin ' },
    'sem papel': { me: responde(200, { email: EMAIL, nome: NOME }) },
    '401': { me: responde(401, { erro: 'sem sessão' }) },
    '403': { me: responde(403, doMe('Admin')) },
    '500': { me: responde(500, doMe('Admin')) },
    'sem rede': { me: semRede },
    'corpo que não é JSON': { me: () => Promise.resolve({ ok: true, status: 200, json: () => Promise.reject(new SyntaxError(NOME)) }) },
    'corpo nulo': { me: responde(200, null) },
    'corpo lista': { me: responde(200, [doMe('Admin')]) },
    'papel numa lista': { me: responde(200, { ...doMe('x'), papel: ['Admin'] }) },
    'fetch que lança': { me: () => { throw new TypeError('fetch quebrado'); } },
  };
  for (const [nome, opcoes] of Object.entries(casos)) {
    const j = montar(opcoes);
    await carregar(j);
    assert.deepEqual(j.admins(), [{ t: 'pushAdmin', admin: false }], nome);
    assert.equal(j.perguntas().length, 1, nome);
  }
});

test('sem login não pergunta; o login seguinte (online ou volta ao app) pergunta, uma vez', async () => {
  const j = montar({ bearer: null });
  await carregar(j);
  assert.deepEqual(j.perguntas(), []);
  assert.deepEqual(j.admins(), [], 'ninguém logado: nada a decidir ainda');

  j.estado.bearer = BEARER;
  mostrar(j);
  await assentar();
  assert.equal(j.perguntas().length, 1);
  assert.deepEqual(j.admins(), [{ t: 'pushAdmin', admin: true }]);
  j.win.dispatchEvent({ type: 'online' });
  await assentar();
  assert.equal(j.perguntas().length, 1);
});

test('na tela de login não pergunta, nem com a conta salva: pergunta quando a tela do App vem para a frente', async () => {
  // Na tela de login com uma conta em cache, o getTokenMW daria o Bearer de quem estava salvo, não o de quem está diante
  // do aparelho: os atalhos de Admin ficariam para outra pessoa.
  const j = montar({ tela: 'login' });
  await carregar(j, null);
  j.avancar(20000);
  await assentar();
  assert.deepEqual(j.perguntas(), []);
  assert.deepEqual(j.admins(), []);
  j.entrar();
  j.avancar(1000);
  await assentar();
  assert.equal(j.perguntas().length, 1);
  assert.deepEqual(j.admins(), [{ t: 'pushAdmin', admin: true }]);
});

test('a página sem o getTokenMW, sem o MIDDLEWARE_URL ou com o getTokenMW que lança: os atalhos saem, sem chamar o /me', async () => {
  // Nada de atalho para quem não se sabe se é Admin: todo erro antes do /me manda false (um atalho velho, publicado numa
  // carga anterior por outra conta, sai).
  for (const falta of ['getTokenMW', 'MIDDLEWARE_URL', 'lança']) {
    const j = montar();
    if (falta === 'lança') j.win.getTokenMW = async () => { throw new Error('sem conta para ' + EMAIL); };
    else delete j.win[falta];
    await carregar(j);
    assert.deepEqual(j.perguntas(), [], falta);
    assert.deepEqual(j.admins(), [{ t: 'pushAdmin', admin: false }], falta);
    assert.equal(JSON.stringify(j.enviados).includes(EMAIL), false, falta);
  }
});

test('o /me não depende do registro: com a rota de registro fora do ar (404) ou sem rede, pergunta do mesmo jeito', async () => {
  for (const registro of [responde(404, {}), semRede]) {
    const j = montar({ registro });
    await carregar(j);
    assert.equal(j.perguntas().length, 1);
    assert.deepEqual(j.admins(), [{ t: 'pushAdmin', admin: true }]);
    // A volta da rede tenta o registro de novo (a regra dele), sem perguntar o papel outra vez.
    j.avancar(DEZ_MIN_MS);
    j.win.dispatchEvent({ type: 'online' });
    await assentar();
    assert.equal(j.perguntas().length, 1);
  }
});

test('do /me só o admin vai ao nativo: nada de e-mail, nome, papel ou cluster', async () => {
  for (const papel of ['Admin', 'Tecnico']) {
    const j = montar({ papel, me: () => Promise.reject(new Error(`sem acesso para ${EMAIL} (${NOME})`)) });
    await carregar(j);
    const k = montar({ papel });
    await carregar(k);
    for (const m of [...j.admins(), ...k.admins()]) assert.deepEqual(Object.keys(m).sort(), ['admin', 't']);
    const tudo = JSON.stringify([...j.enviados, ...k.enviados]);
    for (const proibido of [EMAIL, 'admin.ficticio', '@exemplo', NOME, 'Fulano', 'cluster', BEARER, 'bearerFicticio']) {
      assert.equal(tudo.includes(proibido), false, `o nativo recebeu "${proibido}"`);
    }
  }
});

// ---------------------------------------------------------------------------------------------------------------------
// O teste pedido pelo atalho (window.__gcCascaTestarAvisos)
// ---------------------------------------------------------------------------------------------------------------------

test('o teste chama a rota com o Bearer e o canal do atalho e devolve o resultado ao nativo', async () => {
  for (const canal of ['emergencia', 'emergencia_silenciosa']) {
    const j = montar();
    await carregar(j);
    assert.equal(j.win.__gcCascaTestarAvisos(canal, 1), 1, 'pedido aceito: devolve o número');
    await assentar();
    assert.equal(j.testes().length, 1);
    const { init, corpo } = j.testes()[0];
    assert.equal(init.method, 'POST');
    assert.equal(init.headers.Authorization, 'Bearer ' + BEARER);
    assert.equal(init.headers['Content-Type'], 'application/json');
    assert.deepEqual(corpo, { canal });
    assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: true, status: 200, aparelhos: 2, pedido: 1 }]);
    assert.deepEqual(j.erros(), []);
  }
});

test('a função não é enumerável, existe desde o começo da carga e não depende do token do Firebase', async () => {
  const j = montar();
  const d = Object.getOwnPropertyDescriptor(j.win, '__gcCascaTestarAvisos');
  assert.ok(d && typeof d.value === 'function', 'definida na instalação');
  assert.equal(d.enumerable, false);
  assert.equal(Object.keys(j.win).includes('__gcCascaTestarAvisos'), false);
  // Sem token (o Firebase não configurado): o teste manda aos OUTROS aparelhos da conta do mesmo jeito.
  await carregar(j, null);
  assert.equal(j.win.__gcCascaTestarAvisos('emergencia', 1), 1);
  await assentar();
  assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: true, status: 200, aparelhos: 2, pedido: 1 }]);
});

test('403, 401, 500 e sem rede: o resultado volta com o status e sem a contagem', async () => {
  const casos = [
    [responde(403, { erro: 'só admin' }), 403],
    [responde(401, {}), 401],
    [responde(500, ENVIADO), 500],
    [responde(404, {}), 404],
    [semRede, 'erro'],
    [() => { throw new TypeError('fetch quebrado'); }, 'erro'],
  ];
  for (const [teste, status] of casos) {
    const j = montar({ teste });
    await carregar(j);
    assert.equal(j.win.__gcCascaTestarAvisos('emergencia', 1), 1);
    await assentar();
    assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: false, status, aparelhos: null, pedido: 1 }], String(status));
  }
});

test('a contagem só vale como inteiro de zero para cima; o resto vai como null', async () => {
  const casos = [[0, 0], [7, 7], ['2', null], [-1, null], [1.5, null], [[1, 2], null], [undefined, null], [null, null]];
  for (const [aparelhos, esperado] of casos) {
    const j = montar({ teste: responde(200, { aparelhos }) });
    await carregar(j);
    j.win.__gcCascaTestarAvisos('emergencia_silenciosa', 1);
    await assentar();
    assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: true, status: 200, aparelhos: esperado, pedido: 1 }], String(aparelhos));
  }
  // O 200 com o corpo que não é JSON: enviado, sem a contagem (o nativo diz "enviado" sem o número).
  const k = montar({ teste: () => Promise.resolve({ ok: true, status: 200, json: () => Promise.reject(new SyntaxError('x')) }) });
  await carregar(k);
  k.win.__gcCascaTestarAvisos('emergencia', 1);
  await assentar();
  assert.deepEqual(k.resultados(), [{ t: 'pushTeste', ok: true, status: 200, aparelhos: null, pedido: 1 }]);
});

test('a contagem tem o mesmo teto no script e no nativo', async () => {
  // O nativo descarta a contagem acima do teto dele (a conta tem um ou dois aparelhos: mais que isso é resposta torta);
  // com tetos diferentes, o script mandava um número que o toast não mostraria.
  const java = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..',
    'android/app/src/main/java/br/com/gridco/campo/AtalhosDeTeste.java'), 'utf8');
  const m = /APARELHOS_NO_MAXIMO\s*=\s*([\d_]+)\s*;/.exec(java);
  assert.ok(m, 'o teto no AtalhosDeTeste.java');
  assert.equal(APARELHOS_NO_MAXIMO, Number(m[1].replace(/_/g, '')));
  for (const [aparelhos, esperado] of [[APARELHOS_NO_MAXIMO, APARELHOS_NO_MAXIMO], [APARELHOS_NO_MAXIMO + 1, null]]) {
    const j = montar({ teste: responde(200, { aparelhos }) });
    await carregar(j);
    j.win.__gcCascaTestarAvisos('emergencia', 1);
    await assentar();
    assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: true, status: 200, aparelhos: esperado, pedido: 1 }], String(aparelhos));
  }
});

test('sem login devolve "sem login" e não chama a rota', async () => {
  const j = montar({ bearer: null });
  await carregar(j);
  assert.equal(j.win.__gcCascaTestarAvisos('emergencia', 1), 1);
  await assentar();
  assert.deepEqual(j.testes(), []);
  assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: false, status: 'sem login', aparelhos: null, pedido: 1 }]);
});

test('na tela de login o teste espera a tela do App dentro do prazo; sem ela, "sem login"', async () => {
  // O toque no atalho na volta do login (o nativo guarda o pedido e o entrega na carga nova): até a tela do App, o
  // getTokenMW daria a conta anterior.
  const j = montar({ tela: 'login' });
  await carregar(j);
  assert.equal(j.win.__gcCascaTestarAvisos('emergencia', 1), 1);
  j.avancar(5000);
  await assentar();
  assert.deepEqual(j.testes(), [], 'nada com a tela de login');
  j.entrar();
  j.avancar(1000);
  await assentar();
  assert.equal(j.testes().length, 1);
  assert.equal(j.testes()[0].init.headers.Authorization, 'Bearer ' + BEARER);
  assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: true, status: 200, aparelhos: 2, pedido: 1 }]);

  // Sem a tela do App em 20 s: "sem login", uma vez, e nada sai depois.
  const k = montar({ tela: 'login' });
  await carregar(k);
  k.win.__gcCascaTestarAvisos('emergencia_silenciosa', 1);
  k.avancar(PRAZO_DO_TESTE_MS);
  await assentar();
  assert.deepEqual(k.resultados(), [{ t: 'pushTeste', ok: false, status: 'sem login', aparelhos: null, pedido: 1 }]);
  k.entrar();
  k.avancar(1000);
  await assentar();
  assert.deepEqual(k.testes(), []);
  assert.equal(k.resultados().length, 1);
});

test('a página sem o getTokenMW, sem o MIDDLEWARE_URL ou com o getTokenMW que lança: erro, sem chamar a rota', async () => {
  for (const falta of ['getTokenMW', 'MIDDLEWARE_URL', 'lança']) {
    const j = montar();
    if (falta === 'lança') j.win.getTokenMW = async () => { throw new Error('sem conta para ' + EMAIL); };
    else delete j.win[falta];
    assert.equal(j.win.__gcCascaTestarAvisos('emergencia', 1), 1, falta);
    await assentar();
    assert.deepEqual(j.testes(), [], falta);
    assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: false, status: 'erro', aparelhos: null, pedido: 1 }], falta);
    assert.equal(JSON.stringify(j.enviados).includes(EMAIL), false, falta);
  }
});

test('o número do pedido volta igual na aceitação e no resultado; o que não é número de pedido é recusado', async () => {
  // O nativo liga a resposta ao toque por este número: a de um teste não tira a espera do seguinte, e a que chega depois
  // do aviso de falha não dá outro aviso.
  const j = montar();
  await carregar(j);
  assert.equal(j.win.__gcCascaTestarAvisos('emergencia', 7), 7);
  await assentar();
  assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: true, status: 200, aparelhos: 2, pedido: 7 }]);
  assert.equal(j.win.__gcCascaTestarAvisos('emergencia_silenciosa', 8), 8);
  await assentar();
  assert.deepEqual(j.resultados().map((r) => r.pedido), [7, 8], 'cada resultado com o número do seu pedido');
  assert.equal(j.win.__gcCascaTestarAvisos('emergencia', 2147483647), 2147483647, 'o maior int do Java');

  // Sem o número, ou com o que não é número de pedido: recusado, sem chamar a rota e sem resultado nenhum (o nativo dá o
  // aviso de falha pelo false).
  const k = montar();
  await carregar(k);
  const antes = k.enviados.length;
  for (const pedido of [undefined, null, 0, -1, 1.5, '1', NaN, Infinity, 2147483648, true, [1]]) {
    assert.equal(k.win.__gcCascaTestarAvisos('emergencia', pedido), false, String(pedido));
  }
  k.avancar(PRAZO_DO_TESTE_MS * 2);
  await assentar();
  assert.deepEqual(k.testes(), []);
  assert.equal(k.enviados.length, antes, 'nada ao nativo');
});

test('o pedido que quebra na aceitação devolve false e não manda resultado depois (nem o do prazo)', async () => {
  // O nativo dá o aviso de falha pelo false: um resultado depois seria um segundo aviso para o mesmo toque.
  const j = montar();
  await carregar(j);
  j.win.document.getElementById = () => { throw new Error('quebrou com ' + EMAIL); };
  assert.equal(j.win.__gcCascaTestarAvisos('emergencia', 3), false);
  j.avancar(PRAZO_DO_TESTE_MS * 2);
  await assentar();
  assert.deepEqual(j.resultados(), []);
  assert.deepEqual(j.erros().map((e) => e.onde), ['push']);
});

test('canal que não é um dos dois da emergência: recusado, sem chamar a rota e sem nada ao nativo', async () => {
  const j = montar();
  await carregar(j);
  const antes = j.enviados.length;
  for (const canal of ['', 'resumo', 'EMERGENCIA', 'emergencia ', "emergencia'); x('", 'emergencia_silenciosa\n', null,
    undefined, 1, {}, ['emergencia']]) {
    assert.equal(j.win.__gcCascaTestarAvisos(canal, 1), false, String(canal));
  }
  await assentar();
  assert.deepEqual(j.testes(), []);
  assert.equal(j.enviados.length, antes, 'nada ao nativo');
});

test('o servidor que não responde: em 20 s o resultado é erro, e a resposta atrasada não manda outro', async () => {
  const atrasado = depois();
  const j = montar({ teste: () => atrasado.promessa });
  await carregar(j);
  j.win.__gcCascaTestarAvisos('emergencia', 1);
  await assentar();
  j.avancar(PRAZO_DO_TESTE_MS - 1);
  await assentar();
  assert.deepEqual(j.resultados(), [], 'ainda no prazo');
  j.avancar(1);
  await assentar();
  assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: false, status: 'erro', aparelhos: null, pedido: 1 }]);
  atrasado.resolver({ ok: true, status: 200, json: () => Promise.resolve(ENVIADO) });
  await assentar();
  assert.equal(j.resultados().length, 1, 'um resultado por toque');
});

test('a resposta no prazo cancela o prazo: nada chega depois dos 20 s', async () => {
  const j = montar();
  await carregar(j);
  j.win.__gcCascaTestarAvisos('emergencia', 1);
  await assentar();
  j.avancar(PRAZO_DO_TESTE_MS * 2);
  await assentar();
  assert.deepEqual(j.resultados(), [{ t: 'pushTeste', ok: true, status: 200, aparelhos: 2, pedido: 1 }]);
});

test('nada de e-mail, nome nem token no resultado ao nativo', async () => {
  // A resposta do teste que trouxesse dado da conta (o servidor não traz; a guarda é daqui): só os quatro campos.
  const j = montar({ teste: responde(200, { aparelhos: 1, email: EMAIL, nome: NOME, resumo: [{ token: TOKEN }] }) });
  await carregar(j);
  j.win.__gcCascaTestarAvisos('emergencia', 1);
  await assentar();
  const [r] = j.resultados();
  assert.deepEqual(Object.keys(r).sort(), ['aparelhos', 'ok', 'pedido', 'status', 't']);
  const tudo = JSON.stringify(j.enviados);
  for (const proibido of [EMAIL, NOME, TOKEN, 'APA91b', BEARER]) assert.equal(tudo.includes(proibido), false, proibido);
});

// ---------------------------------------------------------------------------------------------------------------------
// O Sair
// ---------------------------------------------------------------------------------------------------------------------

test('o Sair manda admin:false antes do sair da página, com token ou sem', { timeout: 10000 }, async () => {
  for (const token of [TOKEN, null]) {
    const j = montar();
    const ordem = [];
    j.win.sair = async function () { ordem.push(j.admins().map((m) => m.admin).join(',')); return 'saiu'; };
    await carregar(j, token);
    assert.equal(await j.win.sair(), 'saiu', String(token));
    // O /me já disse true nesta carga (com o token do Firebase ou sem); o Sair tira os atalhos antes de a página ir ao
    // logout.
    assert.deepEqual(ordem, ['true,false'], String(token));
    assert.deepEqual(j.admins().at(-1), { t: 'pushAdmin', admin: false });
  }
});

test('o /me que responde depois do Sair não publica os atalhos de volta', { timeout: 10000 }, async () => {
  const atrasado = depois();
  const j = montar({ me: () => atrasado.promessa });
  j.win.sair = async function () { return 'saiu'; };
  await carregar(j);
  await j.win.sair();
  atrasado.resolver({ ok: true, status: 200, json: () => Promise.resolve(doMe('Admin')) });
  await assentar();
  assert.deepEqual(j.admins(), [{ t: 'pushAdmin', admin: false }]);
});

test('a carga parada para o retrato não pergunta o papel', async () => {
  const j = montar({ ls: {}, retrato: { temEspelho: true, filaHashes: [], idbChaves: [] } });
  assert.equal(j.paradas.length, 1, 'a carga parou');
  await carregar(j);
  assert.deepEqual(j.perguntas(), []);
  assert.deepEqual(j.admins(), []);
});

// ---------------------------------------------------------------------------------------------------------------------
// A ligação do nativo (o JVM não roda a WebView nem o launcher: confere-se no texto do Java)
// ---------------------------------------------------------------------------------------------------------------------

const ler = (caminho) => readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', caminho), 'utf8');
const semComentarios = (java) => java.replace(/\/\*[\s\S]*?\*\//g, '').split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
const JAVA = 'android/app/src/main/java/br/com/gridco/campo/';

test('a ligação do nativo: o extra do atalho lido como o da OS, e o pedido entregue como a abertura da OS', () => {
  const main = semComentarios(ler(JAVA + 'MainActivity.java'));
  const plugin = semComentarios(ler(JAVA + 'CascaPlugin.java'));
  const atalhos = semComentarios(ler(JAVA + 'AtalhosDeTeste.java'));
  // A partida trata o intent uma vez, só sem estado salvo; a tela aberta, pelo onNewIntent (fora do super.onCreate).
  assert.match(main, /if \(savedInstanceState == null && !webViewAntiga\) \{[^}]*pedirTesteDoAtalho\(getIntent\(\)\);/);
  assert.match(main, /protected void onNewIntent\(Intent intent\) \{\s*super\.onNewIntent\(intent\);\s*if \(criando\) return;[^}]*pedirTesteDoAtalho\(intent\);/);
  // A página do App que termina de carregar abre a folga; qualquer página que começa tira o "pronta".
  assert.match(plugin, /public void onPageLoaded\(WebView webView\) \{[\s\S]*?Voltar\.eDoApp\(url, origem\)\) \{?[^}]*testeDosAvisos\.carregou\(\);/);
  assert.match(plugin, /public void onPageStarted\(WebView webView\) \{[^}]*testeDosAvisos\.comecouACarregar\(\);/);
  // O pushAdmin publica ou tira; o resultado do teste vira toast na thread de UI, e tira a espera da resposta.
  assert.match(plugin, /public void pushAdmin\(boolean admin\) \{[^}]*AtalhosDeTeste\.publicar\([^}]*AtalhosDeTeste\.tirar\(/);
  assert.match(plugin, /public void pushTeste\([^)]*int pedido\) \{[\s\S]*?naUi\(\(\) -> \{\s*if \(testeDosAvisos != null && !testeDosAvisos\.respondeu\(pedido\)\) \{[\s\S]*?return;\s*\}\s*AtalhosDeTeste\.avisar\(/);
  // A aceitação da página é o número do pedido, e a espera é desse pedido.
  assert.match(plugin, /int pedido = TesteDosAvisos\.pedidoAceito\(resposta\);[\s\S]*?testeDosAvisos\.paginaAceitou\(pedido\);/);
  // O toque recusado pelos 10 s ganha o toast dele (não o de falha: o primeiro teste já foi pedido).
  assert.match(plugin, /if \(pedido == Pedido\.JA_PEDIDO\) AtalhosDeTeste\.avisar\([^;]*Resultado\.JA_PEDIDO/);
  // Os atalhos são dinâmicos, pelo ShortcutManagerCompat, com os ids fixos. Publicar é um a um pelo push (o
  // setDynamicShortcuts trocaria todos os dinâmicos do app pelos dois), religando antes o fixado; tirar desliga o fixado.
  assert.doesNotMatch(atalhos, /setDynamicShortcuts\(/);
  assert.match(atalhos, /static void publicar\([^)]*\) \{[\s\S]*?ShortcutManagerCompat\.enableShortcuts\([\s\S]*?ShortcutManagerCompat\.pushDynamicShortcut\(/);
  assert.match(atalhos, /static void tirar\([^)]*\) \{[\s\S]*?ShortcutManagerCompat\.removeDynamicShortcuts\([\s\S]*?ShortcutManagerCompat\.disableShortcuts\([^;]*R\.string\.atalho_teste_desligado/);
  assert.match(atalhos, /"teste_avisos"/);
  assert.match(atalhos, /"teste_fora_expediente"/);
  // O atalho abre a porta interna (a activity-alias não exportada), e o toque conta o uso dele.
  assert.match(atalhos, /setClassName\(app, MainActivity\.PORTA_INTERNA\)/);
  assert.match(main, /private void pedirTesteDoAtalho\(Intent intent\) \{[\s\S]*?AtalhosDeTeste\.relatarUso\(this, canal\);/);
});
