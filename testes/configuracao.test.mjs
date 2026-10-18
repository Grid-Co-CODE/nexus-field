// Prova a configuração da casca sem precisar do Android: o capacitor.config.json, o manifesto e as regras de dados.
// Cada regra aqui nasceu de uma decisão da spec (seções 6.4, 6.5 e 7) e quebra o build se alguém a desfizer sem querer.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const ler = (caminho) => readFileSync(join(raiz, caminho), 'utf8');

const cfg = JSON.parse(ler('capacitor.config.json'));
const manifesto = ler('android/app/src/main/AndroidManifest.xml');
const regras = ler('android/app/src/main/res/xml/regras_dados.xml');

// O XML lido por um parser de verdade (o do jsdom), e não por expressão regular: um `[\s\S]*` atravessa o fim de um bloco
// e acha no bloco de baixo o que falta no de cima.
const { DOMParser } = new JSDOM('').window;
function lerXml(texto) {
  const doc = new DOMParser().parseFromString(texto, 'application/xml');
  assert.equal(doc.getElementsByTagName('parsererror').length, 0, 'o XML não abre');
  return doc;
}

test('server.url é só a origem, sem caminho nem barra', () => {
  assert.equal(cfg.server.url, 'https://gridco-campo-mw.azurewebsites.net');
  assert.equal(cfg.server.appStartPath, '/api/app');
});

// O host inteiro: um ou mais rótulos e um ponto ANTES de microsoftonline, live ou windows. Sem a âncora, evil-live.com
// passava (termina em live.com).
const HOST_DA_MICROSOFT = /^([a-z0-9-]+\.)+(microsoftonline|live|windows)\.(com|net)$/;

test('a regra dos hosts da Microsoft não deixa passar host parecido', () => {
  for (const bom of ['login.microsoftonline.com', 'login.live.com', 'login.windows.net']) assert.match(bom, HOST_DA_MICROSOFT);
  for (const ruim of ['evil-live.com', 'live.com', 'xlive.com', 'login.microsoftonline.com.exemplo.test', 'login.live.com/', 'https://login.live.com', 'LOGIN.LIVE.COM', 'login.live.co', '*.live.com', 'a.b.fracttal.com']) {
    assert.doesNotMatch(ruim, HOST_DA_MICROSOFT, ruim);
  }
});

test('allowNavigation só tem hosts da Microsoft e nunca o Fracttal', () => {
  assert.ok(cfg.server.allowNavigation.length >= 1);
  for (const h of cfg.server.allowNavigation) assert.match(h, HOST_DA_MICROSOFT);
  assert.ok(!cfg.server.allowNavigation.some(h => /fracttal/i.test(h)));
});

test('CapacitorHttp desligado e pacote certo', () => {
  assert.notEqual(cfg.plugins?.CapacitorHttp?.enabled, true);
  assert.equal(cfg.appId, 'br.com.gridco.campo');
  assert.equal(cfg.appName, 'App de Campo Grid Co.');
});

test('manifesto: permissões, backup desligado e FileProvider', () => {
  for (const p of ['INTERNET','ACCESS_NETWORK_STATE','CAMERA','ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION','REQUEST_INSTALL_PACKAGES'])
    assert.match(manifesto, new RegExp(`android.permission.${p}"`));
  assert.match(manifesto, /android:allowBackup="false"/);
  assert.match(manifesto, /android:dataExtractionRules="@xml\/regras_dados"/);
  assert.match(manifesto, /br\.com\.gridco\.campo\.arquivos/);
  assert.match(manifesto, /android.media.action.IMAGE_CAPTURE/);
  assert.match(manifesto, /android.media.action.VIDEO_CAPTURE/);
});

test('FileProvider do instalador: autoridade, só cache/atualizacao/ e não exportado', () => {
  // O JUnit do Windows não consegue provar isto (o FileProvider espera "/" nos caminhos), então a configuração é
  // conferida aqui: o Atualizador abre o instalador com content://<autoridade>/atualizacao/app-campo.apk.
  const atualizador = ler('android/app/src/main/java/br/com/gridco/campo/Atualizador.java');
  const autoridade = atualizador.match(/AUTORIDADE_DOS_ARQUIVOS = "([^"]+)"/)?.[1];
  const pasta = atualizador.match(/PASTA_NO_CACHE = "([^"]+)"/)?.[1];
  assert.equal(autoridade, 'br.com.gridco.campo.arquivos');
  assert.equal(pasta, 'atualizacao');

  const provider = manifesto.match(/<provider[^>]*android:name="\.ArquivosProvider"[^>]*>/)?.[0];
  assert.ok(provider, 'falta o <provider> .ArquivosProvider');
  assert.match(provider, new RegExp(`android:authorities="${autoridade.replace(/\./g, '\\.')}"`));
  assert.match(provider, /android:exported="false"/);
  assert.match(provider, /android:grantUriPermissions="true"/);

  const caminhos = ler('android/app/src/main/res/xml/caminhos_arquivos.xml').replace(/<!--[\s\S]*?-->/g, '');
  const entradas = [...caminhos.matchAll(/<([a-z-]+-path)\s([^>]*)\/>/g)];
  assert.equal(entradas.length, 1, 'o provider do instalador expõe uma pasta só');
  assert.equal(entradas[0][1], 'cache-path');
  assert.match(entradas[0][2], new RegExp(`path="${pasta}/"`));
});

test('manifesto: a câmera e o GPS não são exigidos do aparelho', () => {
  // A permissão CAMERA, sozinha, faz a loja e o lint (PermissionImpliesUnsupportedChromeOsHardware) tomarem a câmera
  // por obrigatória, e o aparelho sem ela nem veria o app. As duas são pedidas em tempo de execução.
  for (const recurso of ['android.hardware.camera', 'android.hardware.location.gps']) {
    const etiqueta = manifesto.match(new RegExp(`<uses-feature[^>]*android:name="${recurso.replace(/\./g, '\\.')}"[^>]*>`));
    assert.ok(etiqueta, `falta o <uses-feature> de ${recurso}`);
    assert.match(etiqueta[0], /android:required="false"/, recurso);
  }
});

// Uma exclusão só vale para o domínio que ela cita: o root não cobre o database (o espelho.db, com e-mail nos nomes das
// chaves) nem o file (os blobs das fotos). No Android 12+ o allowBackup=false não desliga a transferência de aparelho, e
// só estas regras seguram o espelho.
const DOMINIOS = ['root', 'file', 'database', 'sharedpref', 'external', 'device_root', 'device_file', 'device_database', 'device_sharedpref'];

test('regras de dados: os dois blocos excluem todos os domínios, um por um, e não incluem nada', () => {
  const doc = lerXml(regras);
  assert.equal(doc.documentElement.tagName, 'data-extraction-rules');
  for (const bloco of ['cloud-backup', 'device-transfer']) {
    const achados = doc.getElementsByTagName(bloco);
    assert.equal(achados.length, 1, `um bloco <${bloco}> só`);
    const filhos = [...achados[0].children];
    assert.deepEqual(filhos.filter((f) => f.tagName !== 'exclude').map((f) => f.tagName), [], `<${bloco}> só tem <exclude>`);
    const excluidos = filhos.map((f) => f.getAttribute('domain')).sort();
    assert.deepEqual(excluidos, [...DOMINIOS].sort(), `<${bloco}> exclui cada domínio`);
    for (const f of filhos) assert.equal(f.getAttribute('path'), null, `<${bloco}>: exclusão sem path vale para o domínio inteiro`);
  }
  assert.equal(doc.getElementsByTagName('include').length, 0, 'nenhum <include>');
});

test('manifesto: o backup antigo (Android 11 e anteriores) também desligado', () => {
  const app = lerXml(manifesto).getElementsByTagName('application')[0];
  assert.ok(app, 'o manifesto tem <application>');
  assert.equal(app.getAttribute('android:allowBackup'), 'false');
  assert.equal(app.getAttribute('android:fullBackupContent'), 'false');
  assert.equal(app.getAttribute('android:dataExtractionRules'), '@xml/regras_dados');
});
