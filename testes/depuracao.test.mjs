// Prova que o apagar do armazenamento da WebView (a prova da restauração no aparelho, spec 10.3) só existe no build de
// depuração. O caminho mora em android/app/src/debug, que o release nem compila; o src/release traz uma Depuracao com os
// mesmos métodos, vazios. Se alguém puser o WebStorage.deleteAllData ou o extra no código comum (src/main) ou no
// release, um APK de campo passaria a apagar o localStorage e o IndexedDB do técnico por um extra de intent: este teste
// falha antes.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const RAIZ = fileURLToPath(new URL('..', import.meta.url));
const SRC = join(RAIZ, 'android', 'app', 'src');
const ler = (caminho) => readFileSync(caminho, 'utf8').replace(/\r\n/g, '\n');
const DEBUG = ler(join(SRC, 'debug', 'java', 'br', 'com', 'gridco', 'campo', 'Depuracao.java'));
const RELEASE = ler(join(SRC, 'release', 'java', 'br', 'com', 'gridco', 'campo', 'Depuracao.java'));
const MAIN_ACTIVITY = ler(join(SRC, 'main', 'java', 'br', 'com', 'gridco', 'campo', 'MainActivity.java'));
const ATALHOS = ler(join(SRC, 'debug', 'res', 'xml', 'atalhos_depuracao.xml'));
const MANIFESTO_DEBUG = ler(join(SRC, 'debug', 'AndroidManifest.xml'));
const BUILD_GRADLE = ler(join(RAIZ, 'android', 'app', 'build.gradle'));

// O que denuncia o caminho: o nome do extra e a API que apaga o armazenamento da WebView.
// O atalho do ícone (o res/xml/atalhos_depuracao.xml e o meta-data android.app.shortcuts) também é só do debug.
const DENUNCIA = ['gc_apagar_webview', 'APAGAR_WEBVIEW', 'WebStorage', 'deleteAllData', 'deleteOrigin', 'apagarEDepois',
  'atalhos_depuracao', 'android.app.shortcuts'];

function arquivosDe(pasta) {
  const saida = [];
  if (!existsSync(pasta)) return saida;
  for (const nome of readdirSync(pasta)) {
    const caminho = join(pasta, nome);
    if (statSync(caminho).isDirectory()) saida.push(...arquivosDe(caminho));
    else saida.push(caminho);
  }
  return saida;
}

// O código comum e o do release, sem o que o cap sync gera (assets/public é a cópia do www, que não muda aqui).
function codigoQueVaiAoRelease() {
  const main = arquivosDe(join(SRC, 'main')).filter((c) => !c.includes(join('main', 'assets')));
  return [...main, ...arquivosDe(join(SRC, 'release'))].filter((c) => /\.(java|kt|xml)$/.test(c));
}

// O código sem os comentários: o que conta é o que roda (o comentário pode explicar que o espelho não é tocado).
const semComentarios = (fonte) => fonte.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

// O corpo do método `static <tipo> <nome>(...) { ... }` (sem chaves aninhadas: os métodos daqui são curtos).
function corpo(fonte, nome) {
  const m = fonte.match(new RegExp('static\\s+\\w+\\s+' + nome + '\\s*\\([^)]*\\)\\s*\\{([^{}]*)\\}'));
  assert.ok(m, `o método ${nome} não foi achado`);
  return m[1].replace(/\/\/[^\n]*/g, '').trim();
}

test('o caminho do apagar não existe no código comum nem no release', () => {
  const arquivos = codigoQueVaiAoRelease();
  assert.ok(arquivos.length >= 10, 'o teste achou o código do app');
  for (const arquivo of arquivos) {
    const fonte = ler(arquivo);
    for (const termo of DENUNCIA) {
      assert.ok(!fonte.includes(termo), `${arquivo.slice(RAIZ.length)} tem "${termo}": o apagar só pode morar em src/debug`);
    }
  }
});

test('a Depuracao do release é vazia: nunca pede o apagar e não faz nada', () => {
  assert.equal(corpo(RELEASE, 'pedeApagarWebView'), 'return false;');
  assert.equal(corpo(RELEASE, 'apagarWebView'), '');
  assert.ok(!/import android\.webkit/.test(RELEASE), 'o release não importa nada da WebView');
});

test('as duas variantes têm os métodos que o MainActivity chama', () => {
  for (const metodo of ['pedeApagarWebView', 'apagarWebView']) {
    assert.match(MAIN_ACTIVITY, new RegExp('Depuracao\\.' + metodo + '\\('), `o MainActivity chama ${metodo}`);
    for (const [nome, fonte] of [['debug', DEBUG], ['release', RELEASE]]) {
      assert.match(fonte, new RegExp('static\\s+\\w+\\s+' + metodo + '\\s*\\('), `a Depuracao do ${nome} tem ${metodo}`);
    }
  }
});

test('a do debug lê o extra booleano só na abertura nova e apaga pelo WebStorage, sem tocar no espelho', () => {
  assert.match(DEBUG, /APAGAR_WEBVIEW = "gc_apagar_webview"/);
  assert.match(corpo(DEBUG, 'pedeApagarWebView'), /salvo == null && intent != null && intent\.getBooleanExtra\(APAGAR_WEBVIEW, false\)/);
  assert.match(DEBUG, /WebStorage\.getInstance\(\)\.deleteAllData\(\)/);
  const codigo = semComentarios(DEBUG);
  for (const termo of ['EspelhoSqlite', 'ArmazemEspelho', 'espelho.db', 'NOME_DO_BANCO', '"espelho', 'getFilesDir',
    'getDatabasePath', 'deleteDatabase', 'clearCache', 'CookieManager', 'SharedPreferences', '.delete(']) {
    assert.ok(!codigo.includes(termo), `a Depuracao do debug não mexe em "${termo}": o espelho (e o login) ficam`);
  }
});

test('o MainActivity troca o config pelo sem o App antes do super.onCreate e só então apaga', () => {
  const pede = MAIN_ACTIVITY.indexOf('Depuracao.pedeApagarWebView(');
  const troca = MAIN_ACTIVITY.search(/if \(webViewAntiga \|\| apagarWebView\) config = configSemOApp\(this\);/);
  const sup = MAIN_ACTIVITY.indexOf('super.onCreate(savedInstanceState)');
  const apaga = MAIN_ACTIVITY.indexOf('Depuracao.apagarWebView(this)');
  assert.ok(pede > 0 && troca > pede && sup > troca && apaga > sup,
    'a ordem: pergunta, troca o config (o App não abre nesta tela), cria a tela, apaga');
});

test('o roteiro da prova no aparelho cita o extra que o código lê, no componente deste pacote', () => {
  const roteiro = ler(join(RAIZ, 'docs', 'prova-no-aparelho.md'));
  assert.ok(roteiro.includes('--ez gc_apagar_webview true'), 'o comando de abertura com o extra');
  // O applicationId (br.com.gridco.nexusfield) não é o pacote Java (br.com.gridco.campo): o atalho ".MainActivity" do am
  // start procuraria a classe dentro do applicationId e não acharia. O componente vai com o nome inteiro da classe.
  const applicationId = BUILD_GRADLE.match(/applicationId\s+"([^"]+)"/)[1];
  const namespace = BUILD_GRADLE.match(/namespace\s*=\s*"([^"]+)"/)[1];
  const componentes = [...roteiro.matchAll(/am start -n (\S+)/g)].map((m) => m[1]);
  assert.ok(componentes.length >= 1, 'o roteiro tem o am start');
  for (const c of componentes) assert.equal(c, `${applicationId}/${namespace}.MainActivity`);
  assert.match(roteiro, /- \[ \] /, 'o roteiro é uma lista de caixas de seleção');
});

test('o atalho do ícone (só no debug) abre a MainActivity deste pacote com o extra que o código lê', () => {
  const applicationId = BUILD_GRADLE.match(/applicationId\s+"([^"]+)"/)[1];
  const namespace = BUILD_GRADLE.match(/namespace\s*=\s*"([^"]+)"/)[1];
  const intent = ATALHOS.match(/<intent\s([^>]*)>([\s\S]*?)<\/intent>/);
  assert.ok(intent, 'o atalho tem um <intent>');
  const attr = (nome) => (intent[1].match(new RegExp('android:' + nome + '="([^"]*)"')) || [])[1];
  assert.equal(attr('action'), 'android.intent.action.MAIN', 'o sistema recusa atalho sem ação');
  assert.equal(attr('targetPackage'), applicationId, 'o targetPackage muda junto com o applicationId');
  assert.equal(attr('targetClass'), namespace + '.MainActivity');
  assert.match(intent[2], /<extra\s+android:name="gc_apagar_webview"\s+android:value="true"\s*\/>/);
  assert.match(MANIFESTO_DEBUG, /<activity android:name="\.MainActivity">[\s\S]*android:name="android\.app\.shortcuts"[\s\S]*android:resource="@xml\/atalhos_depuracao"/);
});
