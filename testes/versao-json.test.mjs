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
import { montarVersaoJson, apkValidoParaOApp, tipoDaVersao, conferirCrescimento, PREFIXO_DO_APK, PADRAO_RESTO_DO_APK, PADRAO_SHA256 } from '../ferramentas/versao-json.mjs';

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
  assert.equal(v.apk, 'https://github.com/Grid-Co-CODE/nexus-field/releases/download/v1.0.0/nexus-field.apk');
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

test('o repositório é o Grid-Co-CODE/nexus-field nos três lados: onde o app lê o versao.json, de onde aceita o APK e o que o gerador escreve', () => {
  // O app lê a releases/latest/download/ deste repositório e só aceita o APK das Releases dele. Com o nome trocado num
  // lado só, a Release sai e o app não a vê, em silêncio.
  const atualizador = readFileSync(join(raiz, 'android/app/src/main/java/br/com/gridco/campo/Atualizador.java'), 'utf8');
  const urlVersao = atualizador.match(/URL_VERSAO =\s*"([^"]+)"/)?.[1];
  assert.equal(urlVersao, 'https://github.com/Grid-Co-CODE/nexus-field/releases/latest/download/versao.json');
  assert.equal(javaPrefixo, 'https://github.com/Grid-Co-CODE/nexus-field/releases/download/');
  assert.equal(PREFIXO_DO_APK, javaPrefixo);
  assert.match(montarVersaoJson(base).apk, /\/nexus-field\.apk$/, 'o APK publicado é o nexus-field.apk');
  // O APK que o app guarda no cache para o instalador também leva o nome novo.
  assert.match(atualizador, /NOME_DO_APK = "nexus-field\.apk";/);
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
    `${P}v1.0.0/nexus-field.apk`, `${P}v1.0.0-rc.1/nexus-field.apk`, `${P}v1.0.0+7/nexus-field.apk`, `${P}a`, `${P}a/b/c.apk`, `${P}_x/~y`,
    P, `${P}/`, `${P}a/`, `${P}/a`, `${P}a//b`, `${P}.`, `${P}..`, `${P}a/./b`, `${P}a/../b`, `${P}../a`, `${P}...`, `${P}a..b`,
    `${P}a?b`, `${P}a#b`, `${P}a%2e%2e/b`, `${P}a%20b`, `${P}a b`, `${P}a\\b`, `${P}a\nb`, `${P}á`, `${P}a;b`, `${P}a:b`, `${P}a@b`,
    'https://github.com/outro/repo/releases/download/v1/nexus-field.apk', 'http://github.com/Grid-Co-CODE/nexus-field/releases/download/v1/x',
    'https://github.com/Grid-Co-CODE/nexus-field/releases/latest/download/nexus-field.apk', '', 'x',
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
    const apk = `${javaPrefixo}v${nome}/nexus-field.apk`;
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
  writeFileSync(join(dir, 'nexus-field.apk'), conteudo);
  writeFileSync(join(dir, '1.2.3.md'), notas);
  mkdirSync(join(dir, 'novidades'));
  writeFileSync(join(dir, 'novidades', '1.2.3.md'), notas);
  return { dir, sha: createHash('sha256').update(conteudo).digest('hex') };
}

test('linha de comando: gera o versao.json com o SHA-256 do APK, o número da execução e as novidades', () => {
  const { dir, sha } = pastaComApk();
  try {
    const r = rodar(['--apk', 'nexus-field.apk', '--novidades', '1.2.3.md', '--saida', 'versao.json'], { VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '41' }, dir);
    assert.equal(r.status, 0, r.stderr);
    const v = JSON.parse(readFileSync(join(dir, 'versao.json'), 'utf8'));
    assert.deepEqual(v, {
      versionCode: 41,
      versionName: '1.2.3',
      apk: 'https://github.com/Grid-Co-CODE/nexus-field/releases/download/v1.2.3/nexus-field.apk',
      sha256: sha,
      novidades: ['Primeira', 'Segunda'],
    });
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('linha de comando: sem argumentos usa nexus-field.apk, novidades/<versão>.md e versao.json', () => {
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
  const comuns = ['--apk', 'nexus-field.apk', '--novidades', '1.2.3.md', '--saida', 'versao.json'];
  try {
    const casos = [
      [{ GITHUB_RUN_NUMBER: '41' }, comuns, /VERSAO_CASCA/],
      [{ VERSAO_CASCA: '1.2.3' }, comuns, /GITHUB_RUN_NUMBER/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '0' }, comuns, /GITHUB_RUN_NUMBER/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: 'abc' }, comuns, /GITHUB_RUN_NUMBER/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '1.5' }, comuns, /GITHUB_RUN_NUMBER/],
      [{ VERSAO_CASCA: '../1', GITHUB_RUN_NUMBER: '41' }, comuns, /versionName/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '41' }, ['--apk', 'nao-existe.apk', '--novidades', '1.2.3.md', '--saida', 'versao.json'], /nao-existe\.apk/],
      [{ VERSAO_CASCA: '1.2.3', GITHUB_RUN_NUMBER: '41' }, ['--apk', 'nexus-field.apk', '--novidades', 'nao-existe.md', '--saida', 'versao.json'], /nao-existe\.md/],
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

// ---------------------------------------------------------------------------------------------------------------
// A versão da tag (o semver vive num lugar só: o PADRAO_VERSAO daqui, que o montar.yml chama)
// ---------------------------------------------------------------------------------------------------------------

test('tipoDaVersao: final ou pré-lançamento, com o hífen do metadado de build fora da conta', () => {
  assert.equal(tipoDaVersao('1.0.0'), 'final');
  assert.equal(tipoDaVersao('2.10.3'), 'final');
  assert.equal(tipoDaVersao('1.0.0-rc.1'), 'pre-lancamento');
  assert.equal(tipoDaVersao('0.0.0-dev'), 'pre-lancamento');
  // O caso que o `case *-*` do bash errava: o hífen está no metadado (depois do +), e a versão é final. Saindo como
  // pré-lançamento, ela ficaria fora da releases/latest/ e o app nunca a veria.
  assert.equal(tipoDaVersao('1.0.0+ci-7'), 'final');
  assert.equal(tipoDaVersao('1.0.0+7'), 'final');
  assert.equal(tipoDaVersao('1.0.0-rc.1+b-2'), 'pre-lancamento');
});

test('tipoDaVersao aceita exatamente o que o versao.json aceita como versionName', () => {
  for (const nome of ['1.0.0', '1.0.0-rc.1', '1.0.0+7', '', ' 1.0.0', 'v1.0.0', '1.0', '1.0.0-', '../1', '1.0.0\n', '1/2', 'a b']) {
    let noJson = true;
    try { montarVersaoJson({ ...base, versionName: nome }); } catch { noJson = false; }
    let naTag = true;
    try { tipoDaVersao(nome); } catch { naTag = false; }
    assert.equal(naTag, noJson, `divergiu em ${JSON.stringify(nome)}`);
  }
  for (const ruim of [undefined, null, 7]) assert.throws(() => tipoDaVersao(ruim), /semver/);
});

// ---------------------------------------------------------------------------------------------------------------
// O versionCode só cresce
// ---------------------------------------------------------------------------------------------------------------

const anterior = (codigo) => JSON.stringify({ ...montarVersaoJson({ ...base, versionCode: 7 }), versionCode: codigo });

test('conferirCrescimento: o número novo tem de ser maior que o da última Release', () => {
  assert.doesNotThrow(() => conferirCrescimento(8, anterior(7)));
  assert.doesNotThrow(() => conferirCrescimento(100, anterior(7)));
  // Igual ou menor: o app compara `v.versionCode <= versaoAtual` e não oferece nada, em silêncio.
  assert.throws(() => conferirCrescimento(7, anterior(7)), /não é maior/);
  assert.throws(() => conferirCrescimento(1, anterior(7)), /não é maior/);
});

test('conferirCrescimento: o versao.json anterior torto não deixa passar (não dá para conferir)', () => {
  for (const ruim of ['', '{', 'null', '[]', '{}', JSON.stringify({ versionCode: '7' }), JSON.stringify({ versionCode: 0 }), JSON.stringify({ versionCode: 7.5 })]) {
    assert.throws(() => conferirCrescimento(8, ruim), /última Release/, `devia recusar ${ruim}`);
  }
  for (const ruim of [0, -1, 1.5, '8', null, undefined]) assert.throws(() => conferirCrescimento(ruim, anterior(7)), /versionCode/);
});

test('linha de comando: tipo-da-versao imprime final ou pre-lancamento, e falha com o que não é semver', () => {
  const final = rodar(['tipo-da-versao', '1.0.0+ci-7'], {});
  assert.equal(final.status, 0, final.stderr);
  assert.equal(final.stdout, 'final\n');
  const pre = rodar(['tipo-da-versao', '1.1.0-rc.1'], {});
  assert.equal(pre.stdout, 'pre-lancamento\n');
  for (const args of [['tipo-da-versao', '1.0'], ['tipo-da-versao', '../1'], ['tipo-da-versao'], ['tipo-da-versao', '1.0.0', 'x']]) {
    const r = rodar(args, {});
    assert.notEqual(r.status, 0, args.join(' '));
    assert.equal(r.stdout, '', 'nada no stdout: o montar.yml guarda o stdout numa variável');
  }
});

test('linha de comando: conferir-crescimento lê o versao.json anterior e o GITHUB_RUN_NUMBER', () => {
  const dir = mkdtempSync(join(tmpdir(), 'versao-json-'));
  try {
    writeFileSync(join(dir, 'anterior.json'), anterior(40));
    assert.equal(rodar(['conferir-crescimento', 'anterior.json'], { GITHUB_RUN_NUMBER: '41' }, dir).status, 0);
    const igual = rodar(['conferir-crescimento', 'anterior.json'], { GITHUB_RUN_NUMBER: '40' }, dir);
    assert.equal(igual.status, 1);
    assert.match(igual.stderr, /não é maior/);
    assert.equal(rodar(['conferir-crescimento', 'anterior.json'], {}, dir).status, 1, 'sem o GITHUB_RUN_NUMBER');
    assert.equal(rodar(['conferir-crescimento', 'nao-existe.json'], { GITHUB_RUN_NUMBER: '41' }, dir).status, 1);
    assert.equal(rodar(['conferir-crescimento'], { GITHUB_RUN_NUMBER: '41' }, dir).status, 1);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
