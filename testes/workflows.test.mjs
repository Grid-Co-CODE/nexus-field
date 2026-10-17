// Guarda as regras dos workflows do GitHub Actions (.github/workflows/). Não há como rodar o Actions daqui, então estas
// regras (que protegem a chave de assinatura e a Release) são conferidas no TEXTO dos dois arquivos, sem um parser de YAML:
// o YAML é escrito no formato simples de sempre (2 espaços, um job por chave), e o teste falha se alguém sair dele.
// Quem consertar um destes testes deve ler o comentário da regra antes: cada uma nasceu de uma decisão do dono da casca.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const ler = (caminho) => readFileSync(join(raiz, caminho), 'utf8');

// Sem as linhas de comentário: as explicações dos arquivos citam as mesmas palavras que as regras proíbem ou exigem.
const semComentarios = (yml) => yml.split('\n').filter((l) => !/^\s*#/.test(l)).join('\n');

const montar = semComentarios(ler('.github/workflows/montar.yml'));
const contrato = semComentarios(ler('.github/workflows/contrato.yml'));
const pacote = JSON.parse(ler('package.json'));

// Os jobs são as chaves de 2 espaços debaixo de `jobs:`; o corpo de cada um vai até o próximo.
function jobsDe(yml) {
  const jobs = {};
  let atual = null;
  let dentro = false;
  for (const linha of yml.split('\n')) {
    if (/^jobs:\s*$/.test(linha)) { dentro = true; continue; }
    if (!dentro) continue;
    const m = /^ {2}([A-Za-z][\w-]*):\s*$/.exec(linha);
    if (m) { atual = m[1]; jobs[atual] = []; continue; }
    if (atual) jobs[atual].push(linha);
  }
  return Object.fromEntries(Object.entries(jobs).map(([nome, linhas]) => [nome, linhas.join('\n')]));
}
const jobsMontar = jobsDe(montar);

// O texto dos passos de um job, na ordem: cada passo começa em "      - " (6 espaços).
function passosDe(corpoDoJob) {
  return corpoDoJob.split(/^ {6}- /m).slice(1).map((p) => `- ${p}`);
}
const indiceDoPasso = (passos, trecho) => passos.findIndex((p) => p.includes(trecho));

// ---------------------------------------------------------------------------------------------------------------

test('montar.yml: roda em push de branch, em tag v* e em pull request', () => {
  assert.match(montar, /^on:\n {2}push:\n {4}branches: \['\*\*'\]\n {4}tags: \['v\*'\]\n {2}pull_request:\n/m);
  assert.deepEqual(Object.keys(jobsMontar), ['montar', 'release']);
});

test('toda action vem fixada por versão maior (vN), nunca solta nem por branch', () => {
  const usos = [...(montar + contrato).matchAll(/^\s*(?:- )?uses:\s*(\S+)\s*$/gm)].map((m) => m[1]);
  assert.ok(usos.length >= 8);
  for (const uso of usos) assert.match(uso, /^[\w.-]+\/[\w./-]+@v\d+$/, `fixe por major: ${uso}`);
});

test('permissões: o padrão é só leitura, e a única escrita em conteúdo é a do job release', () => {
  assert.match(montar, /^permissions:\n {2}contents: read\n/m);
  assert.equal((montar.match(/contents: write/g) || []).length, 1);
  assert.match(jobsMontar.release, /^ {4}permissions:\n {6}contents: write\n/m);
  assert.ok(!/contents: write/.test(jobsMontar.montar));
  assert.ok(!/contents: write|write-all/.test(contrato));
  assert.ok(!/write-all/.test(montar));
});

test('o job release só roda em tag v*, depois dos testes, no ambiente release', () => {
  assert.match(jobsMontar.release, /^ {4}if: startsWith\(github\.ref, 'refs\/tags\/v'\)$/m);
  assert.match(jobsMontar.release, /^ {4}environment: release$/m);
  assert.match(jobsMontar.release, /^ {4}needs: montar$/m);
  assert.ok(!/environment:/.test(jobsMontar.montar), 'os testes e o PR não pedem o ambiente dos secrets');
});

test('os secrets só entram por env: de passo, e nenhum comando os imprime', () => {
  for (const linha of (montar + contrato).split('\n').filter((l) => l.includes('secrets.'))) {
    assert.match(linha, /^\s+[A-Z][A-Z0-9_]*: \$\{\{ secrets\.[A-Z0-9_]+ \}\}$/, `secret fora de um env: ${linha.trim()}`);
  }
  const secrets = [...montar.matchAll(/secrets\.([A-Z0-9_]+)/g)].map((m) => m[1]).sort();
  assert.deepEqual(secrets, ['CASCA_CHAVE_ALIAS', 'CASCA_CHAVE_SENHA', 'CASCA_KEYSTORE_B64', 'CASCA_KEYSTORE_SENHA']);
  assert.ok(!/secrets\./.test(contrato), 'o contrato diário não precisa de secret (só do token da própria execução)');

  const tudo = montar + contrato;
  for (const linha of tudo.split('\n')) {
    assert.ok(!(/\b(echo|printenv|tee|cat)\b/.test(linha) && /CASCA_(KEYSTORE|CHAVE)/.test(linha)), `imprime a chave: ${linha.trim()}`);
  }
  assert.ok(!/set\s+-\w*x|bash\s+-\w*x|xtrace/.test(tudo), 'nada de set -x: ele imprimiria os secrets');
  assert.ok(!/GITHUB_ENV[^\n]*CASCA_|CASCA_[^\n]*GITHUB_ENV/.test(tudo), 'as CASCA_* não vão para o GITHUB_ENV (passariam a todos os passos)');
});

test('as quatro CASCA_* chegam só ao passo do build assinado, e a chave decodificada é apagada no fim', () => {
  const passos = passosDe(jobsMontar.release);
  const assinado = indiceDoPasso(passos, 'name: APK assinado');
  assert.ok(assinado > 0);
  for (const nome of ['CASCA_KEYSTORE', 'CASCA_KEYSTORE_SENHA', 'CASCA_CHAVE_ALIAS', 'CASCA_CHAVE_SENHA']) {
    assert.match(passos[assinado], new RegExp(`^ {10}${nome}: `, 'm'), `${nome} no passo do build`);
    const onde = passos.map((p, i) => (new RegExp(`^ {10}${nome}: `, 'm').test(p) ? i : -1)).filter((i) => i >= 0);
    assert.deepEqual(onde, [assinado], `${nome} só entra no passo do build assinado`);
  }
  assert.match(passos[assinado], /gradlew assembleRelease/);

  const decodificar = indiceDoPasso(passos, 'name: Decodificar a chave de assinatura');
  assert.ok(decodificar >= 0 && decodificar < assinado);
  assert.match(passos[decodificar], /printf '%s' "\$CASCA_KEYSTORE_B64" \| base64 -d > "\$RUNNER_TEMP\/casca\.jks"/);
  assert.match(passos[decodificar], /^ {10}umask 077$/m);

  const ultimo = passos[passos.length - 1];
  assert.match(ultimo, /^- name: Apagar a chave do runner\n {8}if: always\(\)\n {8}run: rm -f "\$RUNNER_TEMP\/casca\.jks"\n?$/);
});

test('a Release confere a assinatura com o apksigner antes de publicar, e leva o APK, o SHA-256 e o versao.json', () => {
  const passos = passosDe(jobsMontar.release);
  const assinatura = indiceDoPasso(passos, '"$apksigner" verify --print-certs app-campo.apk');
  const versaoJson = indiceDoPasso(passos, 'node ferramentas/versao-json.mjs');
  const publicar = indiceDoPasso(passos, 'gh release create');
  assert.ok(assinatura >= 0 && versaoJson > assinatura && publicar > versaoJson, 'ordem: assinatura, versao.json, publicação');
  assert.match(passos[assinatura], /app-release\.apk/);
  assert.match(passos[assinatura], /build-tools\/\*\/apksigner/);
  assert.match(passos[publicar], /gh release create "\$GITHUB_REF_NAME" app-campo\.apk app-campo\.apk\.sha256 versao\.json/);
  assert.match(passos[publicar], /--notes-file "novidades\/\$\{VERSAO_CASCA\}\.md"/);
  assert.match(passos[publicar], /--verify-tag/);
  assert.match(passos[versaoJson], /sha256sum app-campo\.apk > app-campo\.apk\.sha256/);
  assert.match(passos[versaoJson], /^ {10}test "\$\(cut -d' ' -f1 app-campo\.apk\.sha256\)" = /m, 'duas contas do SHA-256 têm de bater');
});

test('versionName é a tag sem o v, conferida como semver antes de virar variável e nome de arquivo', () => {
  const passos = passosDe(jobsMontar.release);
  const versao = indiceDoPasso(passos, 'name: Versão da tag');
  assert.ok(versao >= 0);
  assert.match(passos[versao], /versao="\$\{GITHUB_REF_NAME#v\}"/);
  assert.match(passos[versao], /\^\[0-9\]\+\\\.\[0-9\]\+\\\.\[0-9\]\+/);
  assert.match(passos[versao], /novidades\/\$\{versao\}\.md/);
  assert.match(passos[versao], /echo "VERSAO_CASCA=\$\{versao\}" >> "\$GITHUB_ENV"/);
  assert.ok(versao < indiceDoPasso(passos, 'name: APK assinado'));
});

test('CI de PR: Node 22.x, npm test, os testes de JVM, o APK de depuração, o lint e os dois testes do FileProvider', () => {
  const passos = passosDe(jobsMontar.montar);
  for (const job of Object.values(jobsMontar)) assert.match(job, /node-version: 22\.x/);
  assert.match(pacote.engines.node, /22\.22\.2/, 'o jsdom 30 exige ^22.22.2');

  const node = indiceDoPasso(passos, 'npm test');
  const gradle = indiceDoPasso(passos, './gradlew testDebugUnitTest assembleDebug lint');
  const pulados = indiceDoPasso(passos, 'ferramentas/testes-pulados.mjs');
  assert.ok(node >= 0 && gradle > node && pulados > gradle, 'ordem: npm test, Gradle, conferência dos testes pulados');
  assert.match(passos[gradle], /npx cap sync android/);
  assert.match(passos[indiceDoPasso(passos, 'name: APK de depuração')], /path: android\/app\/build\/outputs\/apk\/debug\/app-debug\.apk/);

  // Os nomes dos dois testes têm de existir no AtualizadorTest.java: renomear um deles sem mexer aqui derrubaria o CI.
  const nomes = passos[pulados].replace(/\s+/g, ' ').match(/AtualizadorTest (.+?)\s*$/)?.[1].split(' ');
  assert.deepEqual(nomes, ['instaladorRecebeOUriDoProviderParaOApkDaPasta', 'oProviderDoInstaladorNaoExpoeNadaAlemDeCacheAtualizacao']);
  const java = ler('android/app/src/test/java/br/com/gridco/campo/AtualizadorTest.java');
  for (const nome of nomes) assert.match(java, new RegExp(`public void ${nome}\\(`), `${nome} no AtualizadorTest.java`);
});

test('o cache do Gradle: o aberto no teste, nenhum no build da Release', () => {
  assert.match(jobsMontar.montar, /gradle\/actions\/setup-gradle@v\d+\n\s+with:\n\s+cache-provider: basic/);
  assert.match(jobsMontar.release, /gradle\/actions\/setup-gradle@v\d+\n\s+with:\n\s+cache-disabled: true/);
});

test('contrato.yml: diário, manual, só leitura mais issues, e abre uma issue com o rótulo contrato se falhar', () => {
  assert.match(contrato, /^on:\n {2}schedule:\n {4}- cron: '0 11 \* \* \*'\n {2}workflow_dispatch:\n/m);
  assert.match(contrato, /^permissions:\n {2}contents: read\n {2}issues: write\n/m);
  assert.match(contrato, /node testes\/contrato\.mjs/);
  assert.match(contrato, /gh issue create --label contrato/);
  assert.match(contrato, /gh issue list --label contrato --state open/, 'uma issue só enquanto houver uma aberta');
  assert.match(contrato, /gh label create contrato/);
  const passos = passosDe(jobsDe(contrato).contrato);
  assert.match(passos[indiceDoPasso(passos, 'name: Derrubar o job')], /exit 1/, 'o job fica vermelho depois de abrir a issue');
});
