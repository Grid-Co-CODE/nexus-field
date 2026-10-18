// Prova o pino do certificado da Release (ferramentas/certificado.mjs): o APK só é publicado se a chave que o assinou for a
// que os aparelhos já conhecem (a variável CASCA_CERT_SHA256 do repositório). A saída do apksigner aqui é a do formato
// real (`apksigner verify --print-certs`, build-tools 36, medido em 17/10/2026), com SHAs fictícios.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { conferirCertificado, normalizarSha256 } from '../ferramentas/certificado.mjs';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const FERRAMENTA = join(raiz, 'ferramentas', 'certificado.mjs');

const CERTO = '3f'.repeat(32);
const OUTRO = 'a1'.repeat(32);

function saida(...sha) {
  return sha.map((s, i) => [
    `Signer #${i + 1} certificate DN: CN=Exemplo, O=Exemplo, C=BR`,
    `Signer #${i + 1} certificate SHA-256 digest: ${s}`,
    `Signer #${i + 1} certificate SHA-1 digest: ${'0'.repeat(40)}`,
    `Signer #${i + 1} certificate MD5 digest: ${'0'.repeat(32)}`,
  ].join('\n')).join('\n') + '\n';
}

// O keytool imprime o SHA-256 em maiúsculas e com ':' entre os bytes; o apksigner, minúsculo e corrido.
const comoOKeytool = (sha) => sha.toUpperCase().match(/../g).join(':');

test('passa quando o único signatário é a chave esperada (e devolve o SHA)', () => {
  assert.equal(conferirCertificado(saida(CERTO), CERTO), CERTO);
});

test('aceita a variável no formato do keytool (maiúsculas, com dois-pontos, com espaço na volta)', () => {
  assert.equal(conferirCertificado(saida(CERTO), ` ${comoOKeytool(CERTO)} \n`), CERTO);
  assert.equal(normalizarSha256(comoOKeytool(CERTO)), CERTO);
});

test('recusa outra chave: a Release com a chave errada não tem volta nos aparelhos', () => {
  assert.throws(() => conferirCertificado(saida(OUTRO), CERTO), /não é o esperado/);
});

test('recusa sem a variável, ou com um valor que não é SHA-256', () => {
  for (const ruim of [undefined, null, '', '   ', 'abc', '3f'.repeat(31), '3f'.repeat(33), 'zz'.repeat(32)]) {
    assert.throws(() => conferirCertificado(saida(CERTO), ruim), /CASCA_CERT_SHA256/, `devia recusar ${JSON.stringify(ruim)}`);
  }
});

test('recusa mais de um signatário, mesmo que um deles seja o certo', () => {
  assert.throws(() => conferirCertificado(saida(CERTO, OUTRO), CERTO), /signatário/);
  assert.throws(() => conferirCertificado(saida(CERTO, CERTO), CERTO), /signatário/);
});

test('recusa a saída sem nenhum SHA-256 (o apksigner mudou de formato ou não rodou)', () => {
  assert.throws(() => conferirCertificado('', CERTO), /nenhum certificado/);
  assert.throws(() => conferirCertificado('Verifies\n', CERTO), /nenhum certificado/);
});

test('qualquer outra linha de SHA-256 de certificado tem de ser a mesma chave (carimbo, linhagem)', () => {
  const comCarimbo = `${saida(CERTO)}Source Stamp Signer certificate SHA-256 digest: ${OUTRO}\n`;
  assert.throws(() => conferirCertificado(comCarimbo, CERTO), /não é o esperado/);
  assert.equal(conferirCertificado(`${saida(CERTO)}Source Stamp Signer certificate SHA-256 digest: ${CERTO}\n`, CERTO), CERTO);
});

// O build-tools 37 (o mais novo no runner do GitHub em 18/10/2026, o que o job release escolhe) mudou o prefixo: em vez de
// "Signer #1 ", uma linha por esquema de assinatura, "V2 Signer: ". Visto na 1ª tag (v1.0.0): o pino recusou um APK
// assinado com a chave certa porque contou 0 signatários. Amostra real do runner, com o SHA trocado.
function saida37(...linhas) {
  return linhas.map(([esquema, sha]) => [
    `${esquema} Signer: certificate DN: CN=Exemplo`,
    `${esquema} Signer: certificate SHA-256 digest: ${sha}`,
    `${esquema} Signer: certificate SHA-1 digest: ${'0'.repeat(40)}`,
    `${esquema} Signer: certificate MD5 digest: ${'0'.repeat(32)}`,
  ].join('\n')).join('\n') + '\n';
}

test('build-tools 37: aceita o formato "V2 Signer:" com a chave esperada', () => {
  assert.equal(conferirCertificado(saida37(['V2', CERTO]), CERTO), CERTO);
});

test('build-tools 37: v2 e v3 com o mesmo certificado são um signatário só', () => {
  assert.equal(conferirCertificado(saida37(['V2', CERTO], ['V3', CERTO]), CERTO), CERTO);
});

test('build-tools 37: outra chave em qualquer esquema é recusada', () => {
  assert.throws(() => conferirCertificado(saida37(['V2', OUTRO]), CERTO), /não é o esperado/);
  assert.throws(() => conferirCertificado(saida37(['V2', CERTO], ['V3', OUTRO]), CERTO), /não é o esperado/);
});

test('build-tools 37: dois signatários no mesmo esquema são recusados', () => {
  const dois = `V2 Signer #1: certificate SHA-256 digest: ${CERTO}\nV2 Signer #2: certificate SHA-256 digest: ${CERTO}\n`;
  assert.throws(() => conferirCertificado(dois, CERTO), /signatário/);
  const repetido = `V2 Signer: certificate SHA-256 digest: ${CERTO}\nV2 Signer: certificate SHA-256 digest: ${CERTO}\n`;
  assert.throws(() => conferirCertificado(repetido, CERTO), /signatário/);
});

test('fim de linha do Windows e avisos do apksigner no meio não atrapalham', () => {
  const texto = `WARNING: META-INF/x.SF not protected by signature\r\n${saida(CERTO).replace(/\n/g, '\r\n')}`;
  assert.equal(conferirCertificado(texto, CERTO), CERTO);
});

// ---------------------------------------------------------------------------------------------------------------
// A linha de comando, como o montar.yml a chama
// ---------------------------------------------------------------------------------------------------------------

function rodar(conteudo, esperado) {
  const pasta = mkdtempSync(join(tmpdir(), 'casca-cert-'));
  try {
    const arquivo = join(pasta, 'certificados.txt');
    writeFileSync(arquivo, conteudo);
    const env = { ...process.env };
    delete env.CERT_ESPERADO;
    if (esperado !== undefined) env.CERT_ESPERADO = esperado;
    return spawnSync(process.execPath, [FERRAMENTA, arquivo], { env, encoding: 'utf8' });
  } finally {
    rmSync(pasta, { recursive: true, force: true });
  }
}

test('linha de comando: sai com 0 e diz o SHA conferido; 1 com a chave errada ou sem a variável', () => {
  const ok = rodar(saida(CERTO), comoOKeytool(CERTO));
  assert.equal(ok.status, 0, ok.stderr);
  assert.match(ok.stdout, new RegExp(CERTO));

  const errada = rodar(saida(OUTRO), CERTO);
  assert.equal(errada.status, 1);
  assert.match(errada.stderr, /não é o esperado/);

  const sem = rodar(saida(CERTO), undefined);
  assert.equal(sem.status, 1);
  assert.match(sem.stderr, /CASCA_CERT_SHA256/);
});

test('linha de comando: sem o arquivo da saída do apksigner, falha', () => {
  const r = spawnSync(process.execPath, [FERRAMENTA], { env: { ...process.env, CERT_ESPERADO: CERTO }, encoding: 'utf8' });
  assert.equal(r.status, 1);
});
