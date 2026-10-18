// Prova a abertura da OS pelo toque na notificação, do lado do script de início (spec 2026-10-18, seção 4.2, "Ao tocar"):
// o nativo manda `window.__gcCascaAbrirOS('<os>')` e a página faz o que o técnico faria, digitar o número no campo de
// busca (`#busca-os`) e deixar o `setBusca` procurar, local e depois ao vivo no Fracttal. A página não abre OS pelo
// endereço, então este é o caminho que ela já tem. O que se protege aqui: o campo é preenchido ANTES do setBusca (a
// página lê o valor dele em outros lugares); só entra número de OS (1 a 12 dígitos ASCII), porque o nativo valida mas o
// extra do Intent vem de fora (a MainActivity é exportada) e o texto vai parar numa busca; a página sem o campo ou sem o
// setBusca (o login, uma versão do site que mudou) devolve false sem lançar e sem tocar em nada; e o setBusca que
// quebra vira só o rótulo `abrirOS` ao nativo.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';
import { criarJanela, ENDERECO_APP } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, ABRIR_OS } = require('../www/casca/inicio.js');
const CODIGO = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'www', 'casca', 'inicio.js'), 'utf8');

// A janela do App com o campo de busca (`<input id="busca-os">`) no body e o `setBusca` de topo da página, que anota o que
// recebeu e o que o campo já tinha naquela hora. Cada peça pode faltar.
function montar({ semCampo = false, semBusca = false, busca = null, ...opcoes } = {}) {
  const j = criarJanela(opcoes);
  const casca = instalar(j.win, j.canal, j.retrato);
  const doc = j.win.document;
  const buscas = [];
  let campo = null;
  if (!semCampo) {
    campo = doc.createElement('input');
    campo.id = 'busca-os';
    doc.body.appendChild(campo);
  }
  if (!semBusca) {
    j.win.setBusca = busca || function (v) { buscas.push({ v, noCampo: campo ? campo.value : null }); };
  }
  const abrir = (os) => j.win.__gcCascaAbrirOS(os);
  const erros = () => j.enviados.filter((m) => m.t === 'erro');
  return { ...j, casca, campo, buscas, abrir, erros };
}

test('preenche o campo de busca e chama o setBusca com o número, nessa ordem', () => {
  const j = montar();
  assert.equal(j.abrir('12345'), true);
  assert.equal(j.campo.value, '12345');
  assert.deepEqual(j.buscas, [{ v: '12345', noCampo: '12345' }], 'o campo já estava preenchido quando o setBusca rodou');
  assert.deepEqual(j.erros(), []);

  // Outra OS no lugar da primeira: o campo troca, sem acumular.
  assert.equal(j.abrir('7'), true);
  assert.equal(j.campo.value, '7');
  assert.deepEqual(j.buscas.map((b) => b.v), ['12345', '7']);
});

test('a função não é enumerável, existe em toda carga e não manda nada ao nativo', () => {
  const j = montar();
  assert.equal(typeof j.win.__gcCascaAbrirOS, 'function');
  assert.equal(Object.keys(j.win).includes('__gcCascaAbrirOS'), false, 'o que da página varre a janela não a vê');
  assert.deepEqual(j.enviados, []);
  assert.equal(j.timersAtivos(), 0, 'nada agendado: quem espera a página é o nativo');
});

test('sem o setBusca ou sem o campo devolve false, sem lançar e sem tocar em nada', () => {
  // O login (o campo existe, mas a página ainda não definiu o resto), ou uma versão do site que trocou os nomes: o app
  // abre normalmente, sem busca, e o contrato diário avisa o dono. Não é erro da casca: nada vai ao nativo.
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

  for (const j of [semBusca, semCampo, nenhum, naoEFuncao]) {
    assert.deepEqual(j.enviados, []);
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

  // E as bordas que valem: de 1 a 12 dígitos.
  assert.equal(j.abrir('1'), true);
  assert.equal(j.abrir('1'.repeat(12)), true);
  assert.deepEqual(j.buscas.map((b) => b.v), ['1', '1'.repeat(12)]);
});

test('o setBusca que lança: devolve false e avisa o nativo só com o rótulo abrirOS', () => {
  const j = montar({ busca: () => { throw new Error('quebrou na OS 12345 de tecnico.ficticio@exemplo.com.br'); } });
  assert.equal(j.abrir('12345'), false);
  const erros = j.erros();
  assert.equal(erros.length, 1);
  assert.equal(erros[0].onde, 'abrirOS');
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
  const rodar = (codigo) => {
    const s = window.document.createElement('script');
    s.textContent = codigo;
    window.document.head.appendChild(s);
  };
  rodar(CODIGO);
  window.document.body.innerHTML =
    '<input id="busca-os" type="search" oninput="setBusca(this.value)"><button id="busca-x" style="display:none"></button>';
  assert.equal(window.__gcCascaAbrirOS('4521'), false, 'antes de a página definir o setBusca');
  rodar(`
    let BUSCA = '';
    function setBusca(v) { BUSCA = String(v || '').trim(); window.buscouPor = BUSCA; document.getElementById('busca-x').style.display = BUSCA ? '' : 'none'; }
  `);
  assert.equal(window.setBusca === undefined, false, 'a função de topo é propriedade da janela');
  assert.equal(window.__gcCascaAbrirOS('4521'), true);
  assert.equal(window.document.getElementById('busca-os').value, '4521');
  assert.equal(window.buscouPor, '4521');
  assert.equal(window.document.getElementById('busca-x').style.display, '', 'o setBusca da página rodou inteiro');
  assert.equal(Object.keys(window).includes('__gcCascaAbrirOS'), false);
  window.close();
});

test('o ABRIR_OS do inicio.js tem os nomes que o contrato com o site exige, e a seção 9 só os usa por ele', () => {
  assert.deepEqual(ABRIR_OS, { funcao: { buscar: 'setBusca' }, id: { busca: 'busca-os' } });
  const secao = CODIGO.slice(CODIGO.indexOf('// 9. Abrir a OS pelo toque'), CODIGO.indexOf('// Ponto único de entrada'));
  assert.ok(secao.includes('function instalarAbrirOS('), 'o corte pega a seção 9');
  const codigo = secao.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
  assert.ok(codigo.includes('ABRIR_OS.funcao.buscar'), 'usado: ABRIR_OS.funcao.buscar');
  assert.ok(codigo.includes('ABRIR_OS.id.busca'), 'usado: ABRIR_OS.id.busca');
  // Nenhum nome do site escrito por fora do objeto: um nome trocado lá ficaria sem o contrato ver.
  assert.equal(codigo.match(/setBusca|busca-os/g), null);
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
  assert.match(plugin, /evaluateJavascript\(js,/);
});
