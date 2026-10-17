// Prova o teste de contrato (testes/contrato.mjs) sem rede: o HTML é um texto fictício montado aqui, só com as formas
// que a casca procura. O campo.html de verdade nunca entra no repositório (é do site, que esta casca só lê).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { conferirContrato, executarContrato, URL_DO_APP } from './contrato.mjs';

const require = createRequire(import.meta.url);
const { CLASSES, CHAVE_FILA } = require('../www/casca/inicio.js');
const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');

const EXATAS = [...new Set([CHAVE_FILA, ...CLASSES.trabalho.exatas, ...CLASSES.trabalho.excecoes, ...CLASSES.cache.exatas])];
const PREFIXOS = [...CLASSES.trabalho.prefixos, ...CLASSES.cache.prefixos];

// O HTML fictício que tem tudo. Cada peça pode ser trocada ou tirada pelo teste que quer ver aquela falta.
function htmlFicticio(pecas = {}) {
  const p = {
    msal: '<script src="https://cdn.exemplo.test/browser/2.38.1/js/msal-browser.min.js"></script>',
    banco: "const IDB_NAME='gc_campo_fotos', IDB_STORE='fotos';",
    chaves: EXATAS.map((k) => `'${k}'`).join(', '),
    // Cada prefixo no jeito que o código costuma usá-lo: a chave montada por concatenação, o indexOf e o template.
    prefixos: PREFIXOS.map((x, i) => (i % 3 === 0 ? `const k${i}='${x}'+id;` : i % 3 === 1 ? `if(k.indexOf('${x}')===0) lixo.push(k);` : `const k${i}=\`${x}\${id}\`;`)).join('\n'),
    rota: "var servidoPeloMW = location.pathname.indexOf('/api/app')>=0;",
    fracttal: "async function conectarFracttal(){\n  let win=null;\n  try{ win=window.open('','_blank'); }catch(e){}\n}\n",
    login: 'async function entrar(){ await msalGet().loginRedirect({scopes:[]}); }',
    resto: "function ler(k){ return localStorage.getItem(k); }\nfunction gravar(k,v){ localStorage.setItem(k,v); }\nfunction apagar(k){ localStorage.removeItem(k); }",
    ...pecas,
  };
  return `<html><head>${p.msal}</head><body><script>\n${p.banco}\nconst LSK=[${p.chaves}];\n${p.prefixos}\n${p.rota}\n${p.fracttal}\n${p.login}\n${p.resto}\n</script></body></html>`;
}

// ---------------------------------------------------------------------------------------------------------------
// O que o plano pede
// ---------------------------------------------------------------------------------------------------------------

test('contrato passa com HTML que tem tudo', () => assert.deepEqual(conferirContrato(htmlFicticio()), []));

test('contrato aponta o que sumiu', () => {
  const htmlSemFotos = htmlFicticio({ banco: '' });
  assert.ok(conferirContrato(htmlSemFotos).includes("'gc_campo_fotos'"));
});

test('importar o contrato não roda a linha de comando (que faria um GET no site ao vivo e definiria o código de saída)', () => {
  // Se a decisão de "fui executado direto" errasse para o lado do sim, o npm test passaria a depender da rede.
  assert.equal(process.exitCode, undefined);
});

test('a URL do contrato é a do capacitor.config.json (origem + appStartPath)', () => {
  const cfg = JSON.parse(readFileSync(join(raiz, 'capacitor.config.json'), 'utf8'));
  assert.equal(URL_DO_APP, cfg.server.url + cfg.server.appStartPath);
});

// ---------------------------------------------------------------------------------------------------------------
// A lista da classificação existe num lugar só (inicio.js): o contrato a lê de lá
// ---------------------------------------------------------------------------------------------------------------

test('o HTML fictício é montado da CLASSES do inicio.js, e o contrato exige cada chave exata', () => {
  assert.ok(EXATAS.length >= 20, 'a lista do inicio.js tem as chaves exatas');
  for (const chave of EXATAS) {
    // Tira só esta chave: o resto do HTML fica igual, e só ela pode faltar. As outras chaves que contêm o nome desta
    // como pedaço (gc_campo_aprdia dentro de gc_campo_aprdia_f) não podem tapar o buraco.
    const html = htmlFicticio({ chaves: EXATAS.filter((k) => k !== chave).map((k) => `'${k}'`).join(', ') });
    assert.deepEqual(conferirContrato(html), [`'${chave}'`], `a falta de ${chave}`);
  }
});

test('o contrato exige cada prefixo do trabalho e do cache', () => {
  assert.ok(PREFIXOS.length >= 5);
  for (const prefixo of PREFIXOS) {
    const html = htmlFicticio({ prefixos: PREFIXOS.filter((x) => x !== prefixo).map((x) => `const a='${x}'+id;`).join('\n') });
    assert.deepEqual(conferirContrato(html), [`prefixo '${prefixo}'`], `a falta do prefixo ${prefixo}`);
  }
});

test('uma chave exata que começa com o prefixo não conta como o prefixo', () => {
  // 'gc_ronda_ctx' começa com 'gc_ronda_', mas é outra coisa (a exceção do trabalho, que é cache). Sem o uso do prefixo
  // de verdade (a chave da ronda montada com o e-mail), a classificação da casca ficaria sem par no site.
  const html = htmlFicticio({ prefixos: PREFIXOS.filter((x) => x !== 'gc_ronda_').map((x) => `const a='${x}'+id;`).join('\n') });
  assert.deepEqual(conferirContrato(html), ["prefixo 'gc_ronda_'"]);
});

test('a chave entre crases (como nos comentários do site) não vale, e o template com interpolação vale para o prefixo', () => {
  const semAChave = EXATAS.filter((k) => k !== 'gc_fech_draft').map((k) => `'${k}'`).join(', ');
  assert.deepEqual(conferirContrato(htmlFicticio({ chaves: semAChave, resto: '// a chave `gc_fech_draft` guarda o rascunho' })), ["'gc_fech_draft'"]);

  const semOPrefixo = PREFIXOS.filter((x) => x !== 'gc_ronda_').map((x) => `const a='${x}'+id;`).join('\n');
  const comentario = '// nada apagava `gc_ronda_*`: acumulava um por dia';
  assert.deepEqual(conferirContrato(htmlFicticio({ prefixos: semOPrefixo, resto: comentario })), ["prefixo 'gc_ronda_'"]);
  assert.deepEqual(conferirContrato(htmlFicticio({ prefixos: `${semOPrefixo}\nconst k=\`gc_ronda_\${email}\`;`, resto: comentario })), []);
});

test('o nome da chave só dentro de outro nome (sem aspas na volta) não vale', () => {
  const html = htmlFicticio({ chaves: EXATAS.filter((k) => k !== 'gc_campo_status').map((k) => `'${k}'`).join(', ') + ", 'gc_campo_status_novo'" });
  assert.deepEqual(conferirContrato(html), ["'gc_campo_status'"]);
});

// ---------------------------------------------------------------------------------------------------------------
// Banco e store das fotos
// ---------------------------------------------------------------------------------------------------------------

test("a store 'fotos' vale junto do banco, não solta pelo HTML", () => {
  // O site usa 'fotos' também como tipo de campo de formulário. Sem o par perto do banco, renomear a store passaria.
  const longe = ' '.repeat(2000);
  const html = htmlFicticio({ banco: `const IDB_NAME='gc_campo_fotos'; const OUTRA='x';${longe}if(c.tipo==='fotos') x();` });
  assert.deepEqual(conferirContrato(html), ["'fotos'"]);
});

test('a store e o banco em linhas seguidas contam como juntos', () => {
  const html = htmlFicticio({ banco: "const IDB_NAME = \"gc_campo_fotos\";\nconst IDB_STORE = 'fotos';" });
  assert.deepEqual(conferirContrato(html), []);
});

test('sem o banco, aponta o banco e a store', () => {
  assert.deepEqual(conferirContrato(htmlFicticio({ banco: '' })), ["'gc_campo_fotos'", "'fotos'"]);
});

// ---------------------------------------------------------------------------------------------------------------
// MSAL, login, trava do /api/app
// ---------------------------------------------------------------------------------------------------------------

test('msal-browser: a versão 2.38.1 tem de estar no mesmo endereço', () => {
  const outra = '<script src="https://cdn.exemplo.test/browser/2.39.0/js/msal-browser.min.js"></script>';
  assert.deepEqual(conferirContrato(htmlFicticio({ msal: outra })), ['msal-browser 2.38.1']);
  // 2.38.1 solto em outro lugar (um comentário, outro pacote) não salva a versão errada do msal-browser.
  assert.deepEqual(conferirContrato(htmlFicticio({ msal: `${outra}\n<!-- antes era 2.38.1 -->` })), ['msal-browser 2.38.1']);
  assert.deepEqual(conferirContrato(htmlFicticio({ msal: '' })), ['msal-browser 2.38.1']);
});

test('msal-browser: serve o endereço de qualquer CDN que traga as duas coisas', () => {
  const jsdelivr = '<script src="https://cdn.exemplo.test/npm/@azure/msal-browser@2.38.1/lib/msal-browser.min.js"></script>';
  assert.deepEqual(conferirContrato(htmlFicticio({ msal: jsdelivr })), []);
});

test('loginRedirect e a trava do /api/app', () => {
  assert.deepEqual(conferirContrato(htmlFicticio({ login: 'async function entrar(){ await msalGet().loginPopup({}); }' })), ['loginRedirect']);
  // O nome só citado (um comentário, um texto) não é a chamada.
  assert.deepEqual(conferirContrato(htmlFicticio({ login: '// o loginRedirect saiu daqui\nasync function entrar(){ await msalGet().loginPopup({}); }' })), ['loginRedirect']);
  assert.deepEqual(conferirContrato(htmlFicticio({ rota: "var a = location.pathname.indexOf('/outra')>=0;" })), ["'/api/app'"]);
});

// ---------------------------------------------------------------------------------------------------------------
// A janela do Fracttal
// ---------------------------------------------------------------------------------------------------------------

test("conectarFracttal tem de abrir a aba com window.open('','_blank')", () => {
  const semFuncao = htmlFicticio({ fracttal: '' });
  assert.deepEqual(conferirContrato(semFuncao), ['função conectarFracttal']);

  const semOpen = htmlFicticio({ fracttal: 'async function conectarFracttal(){\n  toast("x");\n}\n' });
  assert.deepEqual(conferirContrato(semOpen), ["window.open('','_blank') em conectarFracttal"]);
});

test('o window.open de outra função não vale para a conectarFracttal', () => {
  const html = htmlFicticio({
    fracttal: "async function conectarFracttal(){\n  toast('x');\n}\nfunction outra(){ window.open('','_blank'); }\n",
  });
  assert.deepEqual(conferirContrato(html), ["window.open('','_blank') em conectarFracttal"]);
});

test('window.open com espaços e aspas duplas é o mesmo window.open', () => {
  const html = htmlFicticio({ fracttal: 'async function conectarFracttal() {\n  win = window.open( "", "_blank" );\n}\n' });
  assert.deepEqual(conferirContrato(html), []);
});

test('uma função com nome parecido não é a conectarFracttal', () => {
  for (const parecida of ['promptConectarFracttal', 'meuconectarFracttal', 'conectarFracttalVelha', 'conectarFracttal2']) {
    const html = htmlFicticio({ fracttal: `function ${parecida}(){ window.open('','_blank'); }\n` });
    assert.deepEqual(conferirContrato(html), ['função conectarFracttal'], parecida);
  }
});

// ---------------------------------------------------------------------------------------------------------------
// R11: o espelho só vê setItem e removeItem
// ---------------------------------------------------------------------------------------------------------------

test('o site não pode usar o que o espelho não enxerga: clear, atribuição por índice e delete', () => {
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: 'function f(){ localStorage.clear(); }' })), ['proibido: localStorage.clear(']);
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: 'function f(){ window.localStorage.clear( ); }' })), ['proibido: localStorage.clear(']);
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: "function f(k){ localStorage[k] = 'x'; }" })), ['proibido: localStorage[...] = ...']);
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: "function f(){ localStorage['a b']='x'; }" })), ['proibido: localStorage[...] = ...']);
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: 'function f(k){ delete localStorage[k]; }' })), ['proibido: delete localStorage']);
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: 'function f(k){ delete window.localStorage.x; }' })), ['proibido: delete localStorage']);
});

test('ler o localStorage, comparar e o sessionStorage não são o que se proíbe', () => {
  const html = htmlFicticio({
    resto: [
      "if (localStorage[k] == 'x') f();",
      "if (localStorage[k] === 'x') f();",
      "var v = localStorage['a'];",
      'sessionStorage.clear();',
      'delete outraCoisa[k];',
      'minhaListaDeLocalStorage.clear();',
      'localStorage.setItem(k, v); localStorage.removeItem(k);',
    ].join('\n'),
  });
  assert.deepEqual(conferirContrato(html), []);
});

// ---------------------------------------------------------------------------------------------------------------
// A mensagem diz o que sumiu, nunca copia o HTML
// ---------------------------------------------------------------------------------------------------------------

test('os itens que faltaram nunca trazem trecho do HTML', () => {
  const marca = 'MARCA_DO_TRECHO_DO_SITE';
  const html = htmlFicticio({
    resto: `/* ${marca} */ function f(k){ localStorage.clear(); localStorage[k]='${marca}'; delete localStorage[k]; }`,
    login: `${marca}(); msalGet().loginPopup();`,
    fracttal: `async function conectarFracttal(){ ${marca}(); }\n`,
    banco: `const ${marca}=1;`,
    rota: marca,
    msal: `<script src="${marca}.js"></script>`,
  });
  const itens = conferirContrato(html);
  assert.ok(itens.length >= 8, 'tudo isto falta de propósito');
  for (const item of itens) {
    assert.ok(!item.includes(marca), `o item copia trecho do HTML: ${item}`);
    assert.ok(item.length <= 60, `o item é um nome curto, não um trecho: ${item}`);
  }
});

test('sem repetir item e sem exigir o que não existe', () => {
  const itens = conferirContrato('');
  assert.equal(new Set(itens).size, itens.length);
  assert.ok(itens.includes("'gc_campo_queue'"));
  assert.ok(!itens.some((i) => i.startsWith('proibido')), 'um HTML vazio não usa nada proibido');
});

test('quem chama passa texto', () => {
  assert.throws(() => conferirContrato(undefined), TypeError);
  assert.throws(() => conferirContrato(Buffer.from('x')), TypeError);
});

// ---------------------------------------------------------------------------------------------------------------
// O GET: executarContrato (a rede é falsa)
// ---------------------------------------------------------------------------------------------------------------

function resposta({ status = 200, corpo = '', tipo = 'text/html; charset=utf-8', url = URL_DO_APP } = {}) {
  return { ok: status >= 200 && status < 300, status, url, headers: { get: (n) => (n.toLowerCase() === 'content-type' ? tipo : null) }, text: async () => corpo };
}
const SEM_ESPERA = { esperasMs: [0, 0] };

test('executarContrato: sai com 0 quando o site tem tudo e faz um GET só', async () => {
  const chamadas = [];
  const buscar = async (url, opcoes) => { chamadas.push({ url, opcoes }); return resposta({ corpo: htmlFicticio() }); };
  const r = await executarContrato({ buscar, ...SEM_ESPERA });
  assert.equal(r.codigo, 0);
  assert.equal(chamadas.length, 1);
  assert.equal(chamadas[0].url, URL_DO_APP);
  assert.ok([undefined, 'GET'].includes(chamadas[0].opcoes?.method), 'só leitura');
  assert.ok(r.linhas.join('\n').includes('ok'));
});

test('executarContrato: sai com 1 e lista o que faltou, sem o HTML', async () => {
  const html = htmlFicticio({ banco: '', resto: 'localStorage.clear();' });
  const r = await executarContrato({ buscar: async () => resposta({ corpo: html }), ...SEM_ESPERA });
  assert.equal(r.codigo, 1);
  const texto = r.linhas.join('\n');
  assert.match(texto, /- 'gc_campo_fotos'/);
  assert.match(texto, /- 'fotos'/);
  assert.match(texto, /- proibido: localStorage\.clear\(/);
  assert.ok(!texto.includes('<script'), 'a saída não copia o HTML');
});

test('executarContrato: rede que cai e 5xx são tentados de novo, e passam se a segunda der certo', async () => {
  let n = 0;
  const buscar = async () => {
    n += 1;
    if (n === 1) throw new TypeError('fetch failed');
    if (n === 2) return resposta({ status: 503 });
    return resposta({ corpo: htmlFicticio() });
  };
  const r = await executarContrato({ buscar, ...SEM_ESPERA });
  assert.equal(r.codigo, 0);
  assert.equal(n, 3);
});

test('executarContrato: sem conseguir ler o site, sai com 2 (e não confunde com o site ter mudado)', async () => {
  const cai = await executarContrato({ buscar: async () => { throw new TypeError('fetch failed'); }, ...SEM_ESPERA });
  assert.equal(cai.codigo, 2);
  const naoAcha = await executarContrato({ buscar: async () => resposta({ status: 404 }), ...SEM_ESPERA });
  assert.equal(naoAcha.codigo, 2);
  assert.match(naoAcha.linhas.join('\n'), /404/);
});

test('executarContrato: a resposta que corta no meio da leitura também é rede que caiu (tenta de novo, e termina com 2)', async () => {
  let n = 0;
  const corta = { ...resposta(), text: async () => { n += 1; throw new TypeError('terminated'); } };
  const r = await executarContrato({ buscar: async () => corta, ...SEM_ESPERA });
  assert.equal(r.codigo, 2);
  assert.equal(n, 3);
  const volta = [corta, resposta({ corpo: htmlFicticio() })];
  const depois = await executarContrato({ buscar: async () => volta.shift(), ...SEM_ESPERA });
  assert.equal(depois.codigo, 0);
});

test('executarContrato: 4xx não é tentado de novo', async () => {
  let n = 0;
  await executarContrato({ buscar: async () => { n += 1; return resposta({ status: 403 }); }, ...SEM_ESPERA });
  assert.equal(n, 1);
});

test('executarContrato: resposta que não é HTML, ou que parou em outro host, não é o App', async () => {
  const json = await executarContrato({ buscar: async () => resposta({ tipo: 'application/json', corpo: '{}' }), ...SEM_ESPERA });
  assert.equal(json.codigo, 2);
  const login = await executarContrato({
    buscar: async () => resposta({ url: 'https://login.exemplo.test/entrar', corpo: htmlFicticio() }),
    ...SEM_ESPERA,
  });
  assert.equal(login.codigo, 2, 'um redirecionamento para o login não deve ser lido como o App');
});
