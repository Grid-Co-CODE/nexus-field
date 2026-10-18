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

// O nome do aplicativo é Nexus Field (pedido do dono da casca, 17/10/2026); o site que ele abre continua sendo o App de
// Campo. O applicationId mudou antes da primeira Release, quando ainda era de graça: depois dela, mudar o pacote faria
// cada técnico desinstalar (e o desinstalar apaga o espelho). O pacote Java e o namespace ficam br.com.gridco.campo.
const BUILD_GRADLE = ler('android/app/build.gradle');
const STRINGS = lerXml(ler('android/app/src/main/res/values/strings.xml'));
const textoDaString = (nome) => {
  const achada = [...STRINGS.getElementsByTagName('string')].find((s) => s.getAttribute('name') === nome);
  assert.ok(achada, `falta a string ${nome}`);
  return achada.textContent;
};
const APPLICATION_ID = BUILD_GRADLE.match(/applicationId\s+"([^"]+)"/)?.[1];

test('CapacitorHttp desligado e pacote certo', () => {
  assert.notEqual(cfg.plugins?.CapacitorHttp?.enabled, true);
  assert.equal(APPLICATION_ID, 'br.com.gridco.nexusfield');
  assert.equal(cfg.appId, APPLICATION_ID, 'o appId do Capacitor é o applicationId do build.gradle');
  assert.equal(BUILD_GRADLE.match(/namespace\s*=\s*"([^"]+)"/)?.[1], 'br.com.gridco.campo', 'o pacote Java fica');
  assert.equal(textoDaString('package_name'), APPLICATION_ID);
  assert.equal(textoDaString('custom_url_scheme'), APPLICATION_ID);
});

test('o nome é Nexus Field: no Capacitor, em Configurações > Apps (app_name) e embaixo do ícone (o rótulo da activity)', () => {
  assert.equal(cfg.appName, 'Nexus Field');
  assert.equal(textoDaString('app_name'), 'Nexus Field');
  assert.equal(textoDaString('title_activity_main'), 'Nexus Field');
  const app = lerXml(manifesto).getElementsByTagName('application')[0];
  assert.equal(app.getAttribute('android:label'), '@string/app_name');
  const atividade = [...app.getElementsByTagName('activity')].find((a) => a.getAttribute('android:name') === '.MainActivity');
  assert.equal(atividade.getAttribute('android:label'), '@string/title_activity_main', 'o launcher mostra o rótulo da activity');
});

test('o user agent termina em NexusField/<versionName>, e o nome antigo não sobra no código', () => {
  // Os logs do servidor separam o app do navegador por aqui (o CascaPluginTest prova a função na JVM).
  const plugin = ler('android/app/src/main/java/br/com/gridco/campo/CascaPlugin.java');
  assert.match(plugin, /static String userAgent\(String doSistema, String versao\) \{\s*return doSistema \+ " NexusField\/" \+ versao;/);
  assert.match(plugin, /setUserAgentString\(userAgent\(ajustes\.getUserAgentString\(\), BuildConfig\.VERSION_NAME\)\)/);
  assert.ok(!plugin.includes('GridcoCampoApp'), 'o sufixo antigo saiu');
});

test('manifesto: permissões, backup desligado e FileProvider', () => {
  for (const p of ['INTERNET','ACCESS_NETWORK_STATE','CAMERA','ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION','REQUEST_INSTALL_PACKAGES'])
    assert.match(manifesto, new RegExp(`android.permission.${p}"`));
  assert.match(manifesto, /android:allowBackup="false"/);
  assert.match(manifesto, /android:dataExtractionRules="@xml\/regras_dados"/);
  assert.match(manifesto, /android:authorities="\$\{applicationId\}\.arquivos"/);
  assert.match(manifesto, /android.media.action.IMAGE_CAPTURE/);
  assert.match(manifesto, /android.media.action.VIDEO_CAPTURE/);
});

test('FileProvider do instalador: autoridade, só cache/atualizacao/ e não exportado', () => {
  // O JUnit do Windows não consegue provar isto (o FileProvider espera "/" nos caminhos), então a configuração é
  // conferida aqui: o Atualizador abre o instalador com content://<autoridade>/atualizacao/nexus-field.apk. A autoridade
  // sai do applicationId nos dois lados (o ${applicationId} do manifesto e o BuildConfig.APPLICATION_ID do código): com
  // o pacote escrito à mão, a troca do applicationId deixava o instalador apontando para um provider que não existe.
  const atualizador = ler('android/app/src/main/java/br/com/gridco/campo/Atualizador.java');
  assert.match(atualizador, /AUTORIDADE_DOS_ARQUIVOS = BuildConfig\.APPLICATION_ID \+ "\.arquivos";/);
  const pasta = atualizador.match(/PASTA_NO_CACHE = "([^"]+)"/)?.[1];
  assert.equal(pasta, 'atualizacao');
  assert.ok(!/"br\.com\.gridco\.[a-z]+\.arquivos"/.test(atualizador), 'nenhuma autoridade escrita à mão');

  const provider = manifesto.match(/<provider[^>]*android:name="\.ArquivosProvider"[^>]*>/)?.[0];
  assert.ok(provider, 'falta o <provider> .ArquivosProvider');
  assert.match(provider, /android:authorities="\$\{applicationId\}\.arquivos"/);
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

// ---------------------------------------------------------------------------------------------------------------
// As notificações (spec 2026-10-18): o Firebase Cloud Messaging entrega só dados, e a casca monta a notificação.
// ---------------------------------------------------------------------------------------------------------------

test('manifesto: a permissão de notificação e o serviço do Firebase, não exportado', () => {
  const doc = lerXml(manifesto);
  const permissoes = [...doc.getElementsByTagName('uses-permission')].map((p) => p.getAttribute('android:name'));
  assert.ok(permissoes.includes('android.permission.POST_NOTIFICATIONS'), 'Android 13+: sem ela nada aparece');

  const servicos = [...doc.getElementsByTagName('service')].filter((s) => s.getAttribute('android:name') === '.MensagensDoFirebase');
  assert.equal(servicos.length, 1, 'um <service> .MensagensDoFirebase');
  const servico = servicos[0];
  assert.equal(servico.parentNode.tagName, 'application');
  // Exportado, qualquer app do aparelho poderia mandar uma "emergência" para ele; o Firebase o chama por dentro.
  assert.equal(servico.getAttribute('android:exported'), 'false');
  const acoes = [...servico.getElementsByTagName('action')].map((a) => a.getAttribute('android:name'));
  assert.deepEqual(acoes, ['com.google.firebase.MESSAGING_EVENT']);
});

test('manifesto: o canal e o ícone padrão do Firebase são os da casca', () => {
  const app = lerXml(manifesto).getElementsByTagName('application')[0];
  const meta = (nome) => [...app.getElementsByTagName('meta-data')].find((m) => m.getAttribute('android:name') === nome);
  const canal = meta('com.google.firebase.messaging.default_notification_channel_id');
  assert.ok(canal, 'falta o canal padrão');
  assert.equal(canal.getAttribute('android:value'), 'resumo');
  // O mesmo id do canal do resumo no código: o canal padrão que não existe vira o "Miscellaneous" do Firebase.
  assert.match(ler('android/app/src/main/java/br/com/gridco/campo/Notificacoes.java'), /static final String CANAL_RESUMO = "resumo";/);
  const icone = meta('com.google.firebase.messaging.default_notification_icon');
  assert.ok(icone, 'falta o ícone padrão');
  assert.equal(icone.getAttribute('android:resource'), '@drawable/ic_notificacao');
});

test('o ícone das notificações é um vetor monocromático branco (o G)', () => {
  const doc = lerXml(ler('android/app/src/main/res/drawable/ic_notificacao.xml'));
  const vetor = doc.documentElement;
  assert.equal(vetor.tagName, 'vector');
  assert.equal(vetor.getAttribute('android:width'), '24dp');
  assert.equal(vetor.getAttribute('android:height'), '24dp');
  const caminhos = [...doc.getElementsByTagName('path')];
  assert.ok(caminhos.length >= 1);
  // O Android usa só o alfa do ícone pequeno; cor que não seja branca some ou vira um quadrado cinza em alguns aparelhos.
  for (const c of caminhos) {
    assert.equal(c.getAttribute('android:fillColor'), '#FFFFFFFF');
    assert.equal(c.getAttribute('android:strokeColor'), null);
    assert.ok((c.getAttribute('android:pathData') ?? '').length > 50, 'o desenho do G');
  }
});

test('Gradle: o Firebase Messaging pelo BOM com versão fixa, e o plugin do google-services só com o arquivo', () => {
  const raizGradle = ler('android/build.gradle');
  assert.match(raizGradle, /classpath 'com\.google\.gms:google-services:\d+\.\d+\.\d+'/);
  assert.match(BUILD_GRADLE, /implementation platform\("com\.google\.firebase:firebase-bom:\d+\.\d+\.\d+"\)/);
  assert.match(BUILD_GRADLE, /^\s*implementation "com\.google\.firebase:firebase-messaging"\s*$/m, 'a versão vem do BOM');
  assert.ok(!/firebase[^\n]*[:@]\+/.test(BUILD_GRADLE), 'nada de versão dinâmica');
  // Sem o arquivo (o CI de PR, a máquina do desenvolvedor antes de o dono criar o projeto), o build passa sem o plugin.
  assert.match(BUILD_GRADLE, /if \(file\('google-services\.json'\)\.exists\(\)\) \{\s*apply plugin: 'com\.google\.gms\.google-services'\s*\}/);
  assert.equal(BUILD_GRADLE.match(/apply plugin: 'com\.google\.gms\.google-services'/g).length, 1);
});
