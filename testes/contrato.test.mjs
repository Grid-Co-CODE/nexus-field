// Prova o teste de contrato (testes/contrato.mjs) sem rede: o HTML é um texto fictício montado aqui, só com as formas
// que a casca procura. O campo.html de verdade nunca entra no repositório (é do site, que esta casca só lê).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { conferirContrato, executarContrato, rodarLinhaDeComando, URL_DO_APP, IGNORAR } from './contrato.mjs';

const require = createRequire(import.meta.url);
const { CLASSES, CHAVE_FILA, VOLTAR, PUSH, classificar } = require('../www/casca/inicio.js');
const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');

const EXATAS = [...new Set([CHAVE_FILA, ...CLASSES.trabalho.exatas, ...CLASSES.trabalho.excecoes, ...CLASSES.cache.exatas])];
const PREFIXOS = [...CLASSES.trabalho.prefixos, ...CLASSES.cache.prefixos];

// Os nomes do voltar do Android no jeito que o campo.html os escreve: o id no atributo ou na propriedade (a câmera e as
// esperas só existem criadas pelo script: d.id='cham-wait'), a classe num atributo ou num auxiliar do script, a função
// de topo, o `let` de topo e a aba inicial chamada pela função das abas.
function voltarFicticio({ semId = null, semClasse = null, semFuncao = null, semGlobal = false, semAba = false } = {}) {
  const ids = Object.values(VOLTAR.id).filter((id) => id !== semId);
  const classes = Object.values(VOLTAR.classe).filter((c) => c !== semClasse);
  const funcoes = Object.values(VOLTAR.funcao).filter((f) => f !== semFuncao);
  const marcacao = [
    ...ids.map((id, i) => (i % 2 ? `<div id="${id}"></div>` : `<script>const e${i}=document.createElement('div'); e${i}.id='${id}';</script>`)),
    ...classes.map((c, i) => (i % 2 ? `<button class="x ${c}"></button>` : `<script>_el('button','${c}','Voltar');</script>`)),
  ].join('\n');
  const script = [
    ...funcoes.map((f) => `function ${f}(){}`),
    semGlobal ? '' : `let ${VOLTAR.global.tarefaDoFechamento}=null, outra={};`,
    semAba ? '' : `function irAoInicio(){ ${VOLTAR.funcao.aba}('${VOLTAR.abaInicial}'); }`,
  ].join('\n');
  return { marcacao, script };
}

// O que o registro do aparelho usa do campo.html, e cada parte pode faltar.
function pushFicticio({ semBearer = false, semSair = false, semMiddleware = false } = {}) {
  return [
    semMiddleware ? '' : `const ${PUSH.global.middleware}='https://servidor.exemplo.test/api';   // o servidor`,
    semBearer ? '' : `async function ${PUSH.funcao.bearer}(){ return null; }`,
    semSair ? '' : `async function ${PUSH.funcao.sair}(){ document.body.className='login'; }`,
  ].join('\n');
}

// O HTML fictício que tem tudo. Cada peça pode ser trocada ou tirada pelo teste que quer ver aquela falta.
function htmlFicticio(pecas = {}) {
  const voltar = voltarFicticio();
  const p = {
    msal: '<script src="https://cdn.exemplo.test/browser/2.38.1/js/msal-browser.min.js"></script>',
    banco: "const IDB_NAME='gc_campo_fotos', IDB_STORE='fotos';",
    // Como o campo.html abre o banco das fotos: na versão 1, criando a store sem keyPath (as chaves vão fora da linha).
    abrir: "function idb(){ const r=indexedDB.open(IDB_NAME,1); r.onupgradeneeded=()=>{ const db=r.result; if(!db.objectStoreNames.contains(IDB_STORE)) db.createObjectStore(IDB_STORE); }; }",
    chaves: EXATAS.map((k) => `'${k}'`).join(', '),
    // Cada prefixo no jeito que o código costuma usá-lo: a chave montada por concatenação, o indexOf e o template.
    prefixos: PREFIXOS.map((x, i) => (i % 3 === 0 ? `const k${i}='${x}'+id;` : i % 3 === 1 ? `if(k.indexOf('${x}')===0) lixo.push(k);` : `const k${i}=\`${x}\${id}\`;`)).join('\n'),
    ignoradas: Object.keys(IGNORAR).map((k) => `'${k}'`).join(', '),
    rota: "var servidoPeloMW = location.pathname.indexOf('/api/app')>=0;",
    fracttal: "async function conectarFracttal(){\n  let win=null;\n  try{ win=window.open('','_blank'); }catch(e){}\n}\n",
    login: 'async function entrar(){ await msalGet().loginRedirect({scopes:[]}); }',
    resto: "function ler(k){ return localStorage.getItem(k); }\nfunction gravar(k,v){ localStorage.setItem(k,v); }\nfunction apagar(k){ localStorage.removeItem(k); }",
    marcacao: voltar.marcacao,
    voltar: voltar.script,
    // O que o registro do aparelho usa (o PUSH do inicio.js), como o campo.html v284 o escreve: a const de topo do
    // servidor, a função do Bearer e o sair assíncrono.
    push: pushFicticio(),
    ...pecas,
  };
  return `<html><head>${p.msal}</head><body>\n${p.marcacao}\n<script>\n${p.banco}\n${p.abrir}\nconst LSK=[${p.chaves}];\nconst FORA=[${p.ignoradas}];\n${p.prefixos}\n${p.rota}\n${p.fracttal}\n${p.login}\n${p.resto}\n${p.voltar}\n${p.push}\n</script></body></html>`;
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
  // A que sumiu é apontada; a de nome parecido que apareceu é outra chave, e é chave nova sem classe.
  assert.deepEqual(conferirContrato(html), ["'gc_campo_status'", "chave nova sem classe: 'gc_campo_status_novo'"]);
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
// O que aparece no site: chave gc_ nova, outro banco, outra versão
// ---------------------------------------------------------------------------------------------------------------

test('chave gc_ nova no site, fora da CLASSES e da IGNORAR, quebra o contrato (dizendo o nome)', () => {
  // Um rascunho novo que o site passasse a gravar ficaria fora do espelho em silêncio: é trabalho que a perda do
  // armazenamento levaria. Vale do jeito que o site escreve: setItem direto, o auxiliar dele (lsS/lsG) e a constante.
  for (const resto of [
    "localStorage.setItem('gc_v1_rascunho_novo', v);",
    'const RASC="gc_v1_rascunho_novo";',
    "lsS('gc_v1_rascunho_novo', NOVO);",
    "try{ localStorage.removeItem('gc_v1_rascunho_novo'); }catch(e){}",
  ]) {
    assert.deepEqual(conferirContrato(htmlFicticio({ resto })), ["chave nova sem classe: 'gc_v1_rascunho_novo'"], resto);
  }
  // O prefixo novo montado por template ou por concatenação também.
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: 'const k=`gc_novo_${os}`;' })), ["chave nova sem classe: 'gc_novo_'"]);
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: "const k='gc_novo_'+os;" })), ["chave nova sem classe: 'gc_novo_'"]);
});

test('o que a casca já classifica, a IGNORAR, o banco das fotos e as chaves da casca não são chave nova', () => {
  const resto = [
    "lsS('gc_subs_123', x);", // começa com um prefixo do cache
    "const k='gc_ronda_'+email+'_'+dia;", // o prefixo do trabalho
    "localStorage.getItem('gc_ronda_ctx');",
    "localStorage.setItem('gc_casca_sentinela','1');", // a própria casca
    "const nome='gc_campo_fotos';", // o nome do banco das fotos, que o contrato confere à parte
  ].join('\n');
  assert.deepEqual(conferirContrato(htmlFicticio({ resto })), []);
});

test('a IGNORAR: cada entrada é gc_, tem motivo e não é algo que a casca espelha', () => {
  const nomes = Object.keys(IGNORAR);
  assert.ok(nomes.length >= 15, 'a lista do campo.html v282');
  for (const nome of nomes) {
    assert.match(nome, /^gc_[A-Za-z0-9_]+$/, nome);
    assert.equal(typeof IGNORAR[nome], 'string');
    assert.ok(IGNORAR[nome].trim().length >= 10, `o motivo de ${nome}`);
    assert.equal(classificar(nome), null, `${nome} está na IGNORAR e a casca o espelha: ou uma, ou outra`);
  }
  // As que a spec (6.4) manda nunca espelhar estão lá.
  for (const nome of ['gc_erros_js', 'gc_vivo', 'gc_tela']) assert.ok(Object.hasOwn(IGNORAR, nome), nome);
});

test('a hora dos eventos locais (gc_campo_status_meta) é trabalho, fora da IGNORAR, e o contrato a exige no site', () => {
  // Sem ela, depois de uma restauração, o `reconciliar` do campo.html (o reparo do estado órfão) descarta os estados
  // locais (gc_campo_status) que não têm item na fila: toda ação do técnico carimba a hora ali, e o estado sem carimbo é
  // tomado por lixo. Ela volta com o estado, ou o estado restaurado some na primeira programação que chega.
  const chave = 'gc_campo_status_meta';
  assert.equal(classificar(chave), 'trabalho');
  assert.ok(CLASSES.trabalho.exatas.includes(chave));
  assert.ok(!Object.hasOwn(IGNORAR, chave), 'ou espelha, ou ignora');
  // Como chave exata do trabalho, o contrato quebra se ela sumir do site.
  const html = htmlFicticio({ chaves: EXATAS.filter((k) => k !== chave).map((k) => `'${k}'`).join(', ') });
  assert.deepEqual(conferirContrato(html), [`'${chave}'`]);
});

test('entre crases sem interpolação (o jeito dos comentários do site) não conta como chave nova', () => {
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: '// antes a fila ficava em `gc_fila_velha`, hoje não' })), []);
  // Limite conhecido: entre aspas simples, num comentário, conta (o falso alarme custa uma issue).
  assert.deepEqual(conferirContrato(htmlFicticio({ resto: "// antes era 'gc_fila_velha'" })), ["chave nova sem classe: 'gc_fila_velha'"]);
});

test('o nome da chave nova é curto e não traz trecho do HTML', () => {
  const longa = `gc_${'x'.repeat(200)}`;
  const itens = conferirContrato(htmlFicticio({ resto: `lsS('${longa}', 1);` }));
  assert.equal(itens.length, 1);
  assert.ok(itens[0].length <= 60, itens[0]);
  assert.ok(itens[0].startsWith("chave nova sem classe: 'gc_xxx"));
});

test('outro banco IndexedDB na página quebra o contrato: a casca só espelha e restaura o gc_campo_fotos', () => {
  const doisBancos = htmlFicticio({ resto: "function videos(){ return indexedDB.open('gc_campo_videos', 1); }" });
  // O nome do banco novo também é uma chave gc_ sem classe; o que importa aqui é o banco.
  assert.ok(conferirContrato(doisBancos).includes('IndexedDB: outro banco além do gc_campo_fotos'));
  // O nome por constante também é lido.
  const porConstante = htmlFicticio({ abrir: "const OUTRO='videos'; function idb(){ const r=indexedDB.open(OUTRO,1); r.onupgradeneeded=()=>r.result.createObjectStore(IDB_STORE); }" });
  assert.deepEqual(conferirContrato(porConstante), ['IndexedDB: outro banco além do gc_campo_fotos']);
  // Sem nenhum open, a casca não tem o que espelhar.
  assert.deepEqual(conferirContrato(htmlFicticio({ abrir: '' })), ['IndexedDB: o gc_campo_fotos não é aberto']);
});

test('o gc_campo_fotos noutra versão, ou a store com keyPath ou de outro nome, quebra o contrato', () => {
  const versao2 = htmlFicticio({ abrir: 'function idb(){ const r=indexedDB.open(IDB_NAME,2); r.onupgradeneeded=()=>r.result.createObjectStore(IDB_STORE); }' });
  assert.deepEqual(conferirContrato(versao2), ['IndexedDB: gc_campo_fotos noutra versão (a casca usa a 1)']);
  const versaoPorConstante = htmlFicticio({ abrir: 'const IDB_VER=3; function idb(){ const r=indexedDB.open(IDB_NAME,IDB_VER); r.onupgradeneeded=()=>r.result.createObjectStore(IDB_STORE); }' });
  assert.deepEqual(conferirContrato(versaoPorConstante), ['IndexedDB: gc_campo_fotos noutra versão (a casca usa a 1)']);
  const keyPath = htmlFicticio({ abrir: "function idb(){ const r=indexedDB.open(IDB_NAME,1); r.onupgradeneeded=()=>r.result.createObjectStore(IDB_STORE,{keyPath:'id'}); }" });
  assert.deepEqual(conferirContrato(keyPath), ['IndexedDB: store com keyPath ou autoIncrement']);
  const duasStores = htmlFicticio({ abrir: "function idb(){ const r=indexedDB.open(IDB_NAME,1); r.onupgradeneeded=()=>{ r.result.createObjectStore(IDB_STORE); r.result.createObjectStore('videos'); }; }" });
  assert.deepEqual(conferirContrato(duasStores), ["IndexedDB: outra store além de 'fotos'"]);
});

test('o gc_campo_fotos sem versão, na versão 1 por constante, ou com o nome direto, passa', () => {
  for (const abrir of [
    'function idb(){ const r=indexedDB.open(IDB_NAME); r.onupgradeneeded=()=>r.result.createObjectStore(IDB_STORE); }',
    'const IDB_VER = 1; function idb(){ const r=window.indexedDB.open( IDB_NAME , IDB_VER ); r.onupgradeneeded=()=>r.result.createObjectStore( IDB_STORE ); }',
    "function idb(){ const r=indexedDB.open('gc_campo_fotos',1); r.onupgradeneeded=()=>r.result.createObjectStore('fotos'); }",
  ]) {
    assert.deepEqual(conferirContrato(htmlFicticio({ abrir })), [], abrir);
  }
});

// ---------------------------------------------------------------------------------------------------------------
// Os nomes do voltar do Android (o VOLTAR do inicio.js)
// ---------------------------------------------------------------------------------------------------------------

test('o contrato exige cada id, classe e função do VOLTAR, e diz qual sumiu', () => {
  for (const id of Object.values(VOLTAR.id)) {
    const v = voltarFicticio({ semId: id });
    assert.deepEqual(conferirContrato(htmlFicticio({ marcacao: v.marcacao })), [`voltar: id '${id}'`], id);
  }
  for (const classe of Object.values(VOLTAR.classe)) {
    const v = voltarFicticio({ semClasse: classe });
    assert.deepEqual(conferirContrato(htmlFicticio({ marcacao: v.marcacao })), [`voltar: classe '${classe}'`], classe);
  }
  for (const funcao of Object.values(VOLTAR.funcao)) {
    const v = voltarFicticio({ semFuncao: funcao });
    assert.deepEqual(conferirContrato(htmlFicticio({ voltar: v.script })), [`voltar: função ${funcao}`], funcao);
  }
  assert.deepEqual(conferirContrato(htmlFicticio({ voltar: voltarFicticio({ semGlobal: true }).script })), [`voltar: global ${VOLTAR.global.tarefaDoFechamento}`]);
  assert.deepEqual(conferirContrato(htmlFicticio({ voltar: voltarFicticio({ semAba: true }).script })), [`voltar: aba '${VOLTAR.abaInicial}'`]);
});

test('voltar: um nome só dentro de outro nome não vale (modal dentro de modal-velho, rd-lb dentro de rd-lb-2)', () => {
  const v = voltarFicticio({ semId: 'modal', semClasse: 'rd-lb' });
  const marcacao = `${v.marcacao}\n<div id="modal-velho"></div><div class="rd-lb-2"></div>`;
  assert.deepEqual(conferirContrato(htmlFicticio({ marcacao })), ["voltar: id 'modal'", "voltar: classe 'rd-lb'"]);
  // A função só citada (num comentário, numa chamada) não é a função de topo que a janela enxerga.
  const semCam = voltarFicticio({ semFuncao: 'camFechar' });
  assert.deepEqual(conferirContrato(htmlFicticio({ voltar: `${semCam.script}\n// camFechar saiu\nbotao.onclick=()=>camFechar();` })), ['voltar: função camFechar']);
});

test('voltar: o VOLTAR do inicio.js tem as partes que o contrato lê', () => {
  assert.ok(Object.keys(VOLTAR.id).length >= 10);
  assert.ok(Object.keys(VOLTAR.classe).length >= 5);
  assert.ok(Object.keys(VOLTAR.funcao).length >= 10);
  assert.equal(typeof VOLTAR.global.tarefaDoFechamento, 'string');
  assert.equal(typeof VOLTAR.abaInicial, 'string');
});

// ---------------------------------------------------------------------------------------------------------------
// O registro do aparelho para as notificações (o PUSH do inicio.js)
// ---------------------------------------------------------------------------------------------------------------

test('o contrato exige o getTokenMW, o async function sair e o MIDDLEWARE_URL, e diz qual sumiu', () => {
  assert.deepEqual(conferirContrato(htmlFicticio({ push: pushFicticio({ semBearer: true }) })), ['push: função getTokenMW']);
  assert.deepEqual(conferirContrato(htmlFicticio({ push: pushFicticio({ semSair: true }) })), ['push: async function sair']);
  assert.deepEqual(conferirContrato(htmlFicticio({ push: pushFicticio({ semMiddleware: true }) })), ['push: global MIDDLEWARE_URL']);
  assert.deepEqual(conferirContrato(htmlFicticio({ push: '' })),
    ['push: função getTokenMW', 'push: async function sair', 'push: global MIDDLEWARE_URL']);
});

test('push: o sair que deixou de ser async, um nome só dentro de outro e o nome só citado não valem', () => {
  // O embrulho do sair devolve uma Promise: com o sair síncrono, quem usasse o retorno dele veria outra coisa.
  const semSair = pushFicticio({ semSair: true });
  assert.deepEqual(conferirContrato(htmlFicticio({ push: `${semSair}\nfunction sair(){ x(); }` })), ['push: async function sair']);
  assert.deepEqual(conferirContrato(htmlFicticio({ push: `${semSair}\nasync function sairDaConta(){}` })), ['push: async function sair']);
  const semMw = pushFicticio({ semMiddleware: true });
  assert.deepEqual(conferirContrato(htmlFicticio({ push: `${semMw}\nconst MIDDLEWARE_URL_VELHA='x';\nfetch(MIDDLEWARE_URL+'/a');` })),
    ['push: global MIDDLEWARE_URL']);
  const semBearer = pushFicticio({ semBearer: true });
  assert.deepEqual(conferirContrato(htmlFicticio({ push: `${semBearer}\nconst t=await getTokenMW();` })), ['push: função getTokenMW']);
  // O var e o let também declaram o endereço (o script o lê pelo nome).
  assert.deepEqual(conferirContrato(htmlFicticio({ push: `${semMw}\nvar MIDDLEWARE_URL='x';` })), []);
  assert.deepEqual(conferirContrato(htmlFicticio({ push: `${semMw}\nlet MIDDLEWARE_URL='x';` })), []);
});

// ---------------------------------------------------------------------------------------------------------------
// O espelho só vê setItem e removeItem
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
  // O log do site distingue a leitura do contrato pelo nome do repositório (Grid-Co-CODE/nexus-field).
  assert.equal(chamadas[0].opcoes?.headers?.['user-agent'], 'nexus-field-contrato');
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

test('executarContrato: a resposta descartada (5xx, 4xx, outro host, não HTML) tem o corpo cancelado', async () => {
  // O fetch do Node segura a conexão até o corpo ser lido ou cancelado: as três tentativas no 5xx deixavam três abertas.
  const casos = [
    ['503', resposta({ status: 503 }), 3],
    ['404', resposta({ status: 404 }), 1],
    ['outro host', resposta({ url: 'https://login.exemplo.test/entrar' }), 1],
    ['não HTML', resposta({ tipo: 'application/json' }), 1],
  ];
  for (const [nome, base, vezes] of casos) {
    let cancelados = 0;
    const res = { ...base, body: { cancel: async () => { cancelados += 1; } } };
    const r = await executarContrato({ buscar: async () => res, ...SEM_ESPERA });
    assert.equal(r.codigo, 2, nome);
    assert.equal(cancelados, vezes, `${nome}: o corpo de cada resposta descartada é cancelado`);
  }
});

test('executarContrato: o cancelamento que falha, ou a resposta sem corpo, não muda o resultado', async () => {
  const falha = { ...resposta({ status: 404 }), body: { cancel: () => Promise.reject(new Error('já fechado')) } };
  assert.equal((await executarContrato({ buscar: async () => falha, ...SEM_ESPERA })).codigo, 2);
  const lanca = { ...resposta({ status: 404 }), body: { cancel: () => { throw new Error('já fechado'); } } };
  assert.equal((await executarContrato({ buscar: async () => lanca, ...SEM_ESPERA })).codigo, 2);
  const semCorpo = { ...resposta({ status: 404 }), body: null };
  assert.equal((await executarContrato({ buscar: async () => semCorpo, ...SEM_ESPERA })).codigo, 2);
  // A resposta boa é lida, não cancelada.
  let cancelados = 0;
  const boa = { ...resposta({ corpo: htmlFicticio() }), body: { cancel: async () => { cancelados += 1; } } };
  assert.equal((await executarContrato({ buscar: async () => boa, ...SEM_ESPERA })).codigo, 0);
  assert.equal(cancelados, 0);
});

test('linha de comando: o erro do próprio contrato sai com 3, e não com o 1 do "site mudou"', async () => {
  const r = await rodarLinhaDeComando(async () => { throw new TypeError('x'); });
  assert.equal(r.codigo, 3);
  assert.match(r.linhas.join('\n'), /^Contrato: o próprio teste falhou \(TypeError\)/);
  const ok = await rodarLinhaDeComando(async () => ({ codigo: 0, linhas: ['Contrato ok: x'] }));
  assert.equal(ok.codigo, 0);
});
