// Prova a configuração da casca sem precisar do Android: o capacitor.config.json, o manifesto e as regras de dados.
// Cada regra aqui nasceu de uma decisão da spec (seções 6.4, 6.5 e 7) e quebra o build se alguém a desfizer sem querer.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const ler = (caminho) => readFileSync(join(raiz, caminho), 'utf8');

const cfg = JSON.parse(ler('capacitor.config.json'));
const manifesto = ler('android/app/src/main/AndroidManifest.xml');
const regras = ler('android/app/src/main/res/xml/regras_dados.xml');

test('server.url é só a origem, sem caminho nem barra', () => {
  assert.equal(cfg.server.url, 'https://gridco-campo-mw.azurewebsites.net');
  assert.equal(cfg.server.appStartPath, '/api/app');
});

test('allowNavigation só tem hosts da Microsoft e nunca o Fracttal', () => {
  for (const h of cfg.server.allowNavigation) assert.match(h, /(microsoftonline|live|windows)\.(com|net)$/);
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

test('regras de dados excluem tudo da nuvem e da transferência', () => {
  assert.match(regras, /<cloud-backup>[\s\S]*<exclude domain="root"/);
  assert.match(regras, /<device-transfer>[\s\S]*<exclude domain="root"/);
});
