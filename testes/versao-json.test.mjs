// Prova o gerador do versao.json (ferramentas/versao-json.mjs). O que o gerador escreve é lido pelo VersaoRemota.java do
// app: aqui as regras do Java são lidas do próprio arquivo, para o gerador não aceitar o que o app recusaria.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { montarVersaoJson, apkValidoParaOApp, PREFIXO_DO_APK, PADRAO_RESTO_DO_APK, PADRAO_SHA256 } from '../ferramentas/versao-json.mjs';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const FERRAMENTA = join(raiz, 'ferramentas', 'versao-json.mjs');
const java = readFileSync(join(raiz, 'android/app/src/main/java/br/com/gridco/campo/VersaoRemota.java'), 'utf8');

// 64 dígitos hex minúsculos, o que o sha256sum escreve. O 'ab' do exemplo do plano não é um SHA-256 de verdade.
const SHA = 'ab'.repeat(32);
const base = { versionCode: 7, versionName: '1.0.0', sha256: SHA, novidadesMd: '- Primeira versão' };

// ---------------------------------------------------------------------------------------------------------------
// O que o plano pede
// ---------------------------------------------------------------------------------------------------------------

test('versao.json aponta para a Release da versão', () => {
  const v = montarVersaoJson(base);
  assert.equal(v.apk, 'https://github.com/Grid-Co-CODE/app-campo-android/releases/download/v1.0.0/app-campo.apk');
  assert.deepEqual(v.novidades, ['Primeira versão']);
});

test('o versao.json tem exatamente os campos que o app lê, com os tipos certos', () => {
  const v = montarVersaoJson(base);
  assert.deepEqual(Object.keys(v), ['versionCode', 'versionName', 'apk', 'sha256', 'novidades']);
  assert.equal(v.versionCode, 7);
  assert.equal(v.versionName, '1.0.0');
  assert.equal(v.sha256, SHA);
});

// ---------------------------------------------------------------------------------------------------------------
// As novidades: as linhas "- " do Markdown, sem o "- "
// ---------------------------------------------------------------------------------------------------------------

test('novidades: só as linhas "- ", sem o traço, aparadas, e o resto do Markdown fica de fora', () => {
  const md = [
    '# 1.0.0',
    '',
    'Texto solto que não é novidade.',
    '- Primeira',
    '-   Segunda com espaços   ',
    '- ',
    '-sem espaço depois do traço não é item',
    '* outro marcador não é item',
    '  - sublista não é item',
    '-\tTerceira com tabulação',
  ].join('\n');
  assert.deepEqual(montarVersaoJson({ ...base, novidadesMd: md }).novidades, ['Primeira', 'Segunda com espaços', 'Terceira com tabulação']);
});

test('novidades: fim de linha do Windows, e sem nenhuma linha vira lista vazia (a Release sem novidades)', () => {
  assert.deepEqual(montarVersaoJson({ ...base, novidadesMd: '- A\r\n- B\r\n' }).novidades, ['A', 'B']);
  assert.deepEqual(montarVersaoJson({ ...base, novidadesMd: '' }).novidades, []);
  assert.deepEqual(montarVersaoJson({ ...base, novidadesMd: 'só texto\n' }).novidades, []);
});

test('novidadesMd tem de ser texto', () => {
  assert.throws(() => montarVersaoJson({ ...base, novidadesMd: undefined }), /novidadesMd/);
  assert.throws(() => montarVersaoJson({ ...base, novidadesMd: ['- x'] }), /novidadesMd/);
});

// ---------------------------------------------------------------------------------------------------------------
// O que o app recusa, o gerador recusa antes
// ---------------------------------------------------------------------------------------------------------------

test('sha256: exatamente 64 dígitos hexadecimais minúsculos', () => {
  for (const ruim of ['ab', 'ab'.repeat(31), 'ab'.repeat(33), 'AB'.repeat(32), 'zz'.repeat(32), '', ` ${SHA}`, `${SHA}\n`, [SHA], { toString: () => SHA }, 12, null, undefined]) {
    assert.throws(() => montarVersaoJson({ ...base, sha256: ruim }), /sha256/, `devia recusar ${JSON.stringify(ruim)}`);
  }
});

test('versionCode: inteiro de 1 até o maior int do Java', () => {
  for (const ruim of [0, -1, 1.5, NaN, Infinity, 2147483648, '7', null, undefined, true]) {
    assert.throws(() => montarVersaoJson({ ...base, versionCode: ruim }), /versionCode/, `devia recusar ${String(ruim)}`);
  }
  assert.equal(montarVersaoJson({ ...base, versionCode: 1 }).versionCode, 1);
  assert.equal(montarVersaoJson({ ...base, versionCode: 2147483647 }).versionCode, 2147483647);
});

test('versionName: o semver da casca, que cabe no nome de uma tag e de um arquivo', () => {
  for (const bom of ['1.0.0', '2.10.3', '1.0.0-rc.1', '1.0.0+7', '0.0.0-dev']) {
    assert.equal(montarVersaoJson({ ...base, versionName: bom }).versionName, bom);
  }
  // X.Y.Z, com pré-versão e metadado se houver; o que não é isso não vira tag nem link.
  for (const ruim of ['', '  ', ' 1.0.0', '1.0.0 ', '1', '1.0', '1.0.0.0', 'v1.0.0', '1/2', '../1', '1..', '1.0.0..1', '1.0.0-', '1.0.0?x=1', '1.0.0#a', '1.0.%2e', '1.0.0\\0', '1.0.0\n', 'a b', 7, null, undefined]) {
    assert.throws(() => montarVersaoJson({ ...base, versionName: ruim }), /versionName/, `devia recusar ${JSON.stringify(ruim)}`);
  }
});

// ---------------------------------------------------------------------------------------------------------------
// As regras são as do VersaoRemota.java (lidas do arquivo)
// ---------------------------------------------------------------------------------------------------------------

const doJava = (re) => java.match(re)?.[1];
const javaPrefixo = doJava(/PREFIXO_DO_APK = "([^"]+)"/);
const javaResto = doJava(/RESTO_DO_APK = Pattern\.compile\("([^"]+)"\)/);
const javaSha = doJava(/SHA256 = Pattern\.compile\("([^"]+)"\)/);

test('o gerador usa o prefixo e o padrão do apk que o VersaoRemota.java usa', () => {
  assert.ok(javaPrefixo && javaResto && javaSha, 'não achei as regras no VersaoRemota.java: o teste precisa acompanhar o arquivo');
  assert.equal(PREFIXO_DO_APK, javaPrefixo);
  assert.equal(PADRAO_RESTO_DO_APK, javaResto);
  // O Java aceita maiúsculas e minúsculas e lê em minúsculas; o contrato do versao.json é minúsculo. O gerador é o
  // lado estrito: tudo o que ele escreve passa no padrão do Java.
  assert.equal(PADRAO_SHA256, '[0-9a-f]{64}');
  assert.equal(javaSha, '[0-9A-Fa-f]{64}');
});

// A regra do apkValido do Java, refeita a partir das constantes lidas do arquivo.
function oAppAceitaOApk(apk) {
  if (!apk.startsWith(javaPrefixo)) return false;
  const resto = apk.slice(javaPrefixo.length);
  if (!new RegExp(`^(?:${javaResto})$`).test(resto)) return false;
  return !resto.split('/').some((pedaco) => pedaco === '.' || pedaco === '..');
}

test('apkValidoParaOApp decide como o apkValido do Java, em links bons e maus', () => {
  const P = javaPrefixo;
  const links = [
    `${P}v1.0.0/app-campo.apk`, `${P}v1.0.0-rc.1/app-campo.apk`, `${P}v1.0.0+7/app-campo.apk`, `${P}a`, `${P}a/b/c.apk`, `${P}_x/~y`,
    P, `${P}/`, `${P}a/`, `${P}/a`, `${P}a//b`, `${P}.`, `${P}..`, `${P}a/./b`, `${P}a/../b`, `${P}../a`, `${P}...`, `${P}a..b`,
    `${P}a?b`, `${P}a#b`, `${P}a%2e%2e/b`, `${P}a%20b`, `${P}a b`, `${P}a\\b`, `${P}a\nb`, `${P}á`, `${P}a;b`, `${P}a:b`, `${P}a@b`,
    'https://github.com/outro/repo/releases/download/v1/app-campo.apk', 'http://github.com/Grid-Co-CODE/app-campo-android/releases/download/v1/x',
    'https://github.com/Grid-Co-CODE/app-campo-android/releases/latest/download/app-campo.apk', '', 'x',
  ];
  for (const link of links) {
    assert.equal(apkValidoParaOApp(link), oAppAceitaOApk(link), `divergiu do Java em ${JSON.stringify(link)}`);
  }
  assert.equal(apkValidoParaOApp(undefined), false);
  assert.equal(apkValidoParaOApp(null), false);
});

test('tudo o que o gerador escreve, o app aceita (e o que o app recusaria, o gerador não escreve)', () => {
  const nomes = [
    '1.0.0', '1.0.0-rc.1', '1.0.0+7', '0.0.0-dev', '..', '.', '...', '1/2', '1/../2', 'a?b', 'a#b', 'a%2eb', 'a b', ' ', '', 'a\\b', 'a\nb', 'á',
    'a;b', 'a:b', 'a@b', 'a~b', '_x', 'x_', '1.0.0/../x', '1.0.0?x', '1.0.0#x',
  ];
  for (const nome of nomes) {
    let escrito = null;
    try { escrito = montarVersaoJson({ ...base, versionName: nome }); } catch { /* recusado */ }
    const apk = `${javaPrefixo}v${nome}/app-campo.apk`;
    const appAceita = nome.trim() !== '' && oAppAceitaOApk(apk);
    if (escrito) {
      assert.ok(appAceita, `o gerador escreveu a versão ${JSON.stringify(nome)} que o app recusaria`);
      assert.ok(oAppAceitaOApk(escrito.apk));
    }
  }
  // E o caso comum passa nos dois.
  assert.ok(oAppAceitaOApk(montarVersaoJson(base).apk));
  assert.match(montarVersaoJson(base).sha256, new RegExp(`^${javaSha}$`));
});

// ---------------------------------------------------------------------------------------------------------------
// A linha de comando: lê o APK, calcula o SHA-256 e escreve o versao.json
// ---------------------------------------------------------------------------------------------------------------

function rodar(args, env, cwd) {
  const limpo = { ...process.env };
  delete limpo.VERSAO_CASCA;
  delete limpo.GITHUB_RUN_NUMBER;
  return spawnSync(process.execPath, [FERRAMENTA, ...args], { cwd, env: { ...limpo, ...env }, encoding: 'utf8' });
}

function pastaComApk() {
  const dir = mkdtempSync(join(tmpdir(), 'versao-json-'));
  const conteudo = Buffer.from('um apk de mentira, só para o SHA-256');
  const notas = '# 1.2.3\n\n- Primeira\n- Segunda\n';
  writeFileSync(join(dir, 'app-campo.apk'), conteudo);
  writeFileSync(join(dir, '1.2.3.md'), notas);
  mkdirSync(join(dir, 'novidades'));
  writeFileSync(join(dir, 'novidades', '1.2.3.md'), notas);
  return { dir, sha: createHash('sha256').update(conteudo).digest('hex') };
}

test('linha de comando: gera o versao.json com o SHA-256 do APK, o número da execução e as novidades', () => {
  const { dir, sha } = pastaComApk();
  try {
    const r = rodar(['--apk', 'app-campo.apk', '--novidades', '1.2.3.md', '--saida', 'versao.json'], { VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '41' }, dir);
    assert.equal(r.status, 0, r.stderr);
    const v = JSON.parse(readFileSync(join(dir, 'versao.json'), 'utf8'));
    assert.deepEqual(v, {
      versionCode: 41,
      versionName: '1.2.3',
      apk: 'https://github.com/Grid-Co-CODE/app-campo-android/releases/download/v1.2.3/app-campo.apk',
      sha256: sha,
      novidades: ['Primeira', 'Segunda'],
    });
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('linha de comando: sem argumentos usa app-campo.apk, novidades/<versão>.md e versao.json', () => {
  const { dir, sha } = pastaComApk();
  try {
    const r = rodar([], { VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '41' }, dir);
    assert.equal(r.status, 0, r.stderr);
    const v = JSON.parse(readFileSync(join(dir, 'versao.json'), 'utf8'));
    assert.equal(v.sha256, sha);
    assert.deepEqual(v.novidades, ['Primeira', 'Segunda']);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('linha de comando: o que falta ou está torto para com erro e não deixa versao.json', () => {
  const { dir } = pastaComApk();
  const comuns = ['--apk', 'app-campo.apk', '--novidades', '1.2.3.md', '--saida', 'versao.json'];
  try {
    const casos = [
      [{ GITHUB_RUN_NUMBER: '41' }, comuns, /VERSAO_CASCA/],
      [{ VERSAO_CASCA: '1.2.3' }, comuns, /GITHUB_RUN_NUMBER/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '0' }, comuns, /GITHUB_RUN_NUMBER/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: 'abc' }, comuns, /GITHUB_RUN_NUMBER/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '1.5' }, comuns, /GITHUB_RUN_NUMBER/],
      [{ VERSAO_CASCA: '../1', GITHUB_RUN_NUMBER: '41' }, comuns, /versionName/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '41' }, ['--apk', 'nao-existe.apk', '--novidades', '1.2.3.md', '--saida', 'versao.json'], /nao-existe\.apk/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '41' }, ['--apk', 'app-campo.apk', '--novidades', 'nao-existe.md', '--saida', 'versao.json'], /nao-existe\.md/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '41' }, [...comuns, '--outra-coisa', 'x'], /--outra-coisa/],
    ];
    for (const [env, args, esperado] of casos) {
      const r = rodar(args, env, dir);
      assert.notEqual(r.status, 0, `devia falhar: ${JSON.stringify(env)} ${args.join(' ')}`);
      assert.match(r.stderr, esperado);
      assert.ok(!existsSync(join(dir, 'versao.json')), 'não deixa versao.json pela metade');
    }
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
