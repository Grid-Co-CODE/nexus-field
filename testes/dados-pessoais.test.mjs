// O repositório é PÚBLICO: nenhum arquivo versionado leva e-mail de pessoa, telefone, CPF ou o nome de alguém num caminho
// de pasta (C:/Users/<nome>). Exceções: os e-mails de "não responda" e os de domínio fictício (exemplo.com.br,
// example.com, *.test), que são os das fixtures. A varredura é a dos arquivos que o git versiona (git ls-files), e a
// mensagem diz o arquivo, a linha e o TIPO do achado, nunca o valor (o log do CI também é público).
//
// Os exemplos de dado pessoal deste teste são montados em tempo de execução, aos pedaços: escritos inteiros aqui, a
// varredura acharia este próprio arquivo.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join, extname } from 'node:path';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');

const BINARIOS = new Set(['.png', '.jpg', '.jpeg', '.webp', '.gif', '.ico', '.jar', '.jks', '.keystore', '.apk', '.zip']);

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g;
const DOMINIO_FICTICIO = /(?:^|\.)(?:exemplo\.com(?:\.br)?|example\.(?:com|org|net)|[a-z0-9-]+\.(?:test|example|invalid|localhost))$/i;
const NAO_RESPONDA = /^(?:no-?reply)(?:\+[\w.-]+)?$/i;

function emailPermitido(email) {
  const [local, dominio] = [email.slice(0, email.lastIndexOf('@')), email.slice(email.lastIndexOf('@') + 1)];
  if (NAO_RESPONDA.test(local) || /(?:^|\.)users\.noreply\.github\.com$/i.test(dominio)) return true;
  if (DOMINIO_FICTICIO.test(dominio)) return true;
  return email === 'git@github.com';
}

// Telefone do Brasil: com o DDD entre parênteses, com o +55, ou o celular de 11 dígitos corrido (DDD e o 9 na frente).
const TELEFONES = [
  /\(\s?[1-9][0-9]\s?\)\s?9?[0-9]{4}[-\s]?[0-9]{4}(?![0-9])/,
  /\+55[\s-]?\(?[1-9][0-9]\)?[\s-]?9?[0-9]{4}[\s-]?[0-9]{4}(?![0-9])/,
  /(?<![\w.:/-])[1-9][0-9]9[0-9]{8}(?![\w])/,
];

// O CPF: o formatado (000.000.000-00) sempre conta; o corrido de 11 dígitos conta se os dígitos verificadores baterem.
function cpfValido(digitos) {
  if (!/^\d{11}$/.test(digitos) || /^(\d)\1{10}$/.test(digitos)) return false;
  const d = [...digitos].map(Number);
  for (const n of [9, 10]) {
    let soma = 0;
    for (let i = 0; i < n; i++) soma += d[i] * (n + 1 - i);
    const dv = (soma * 10) % 11 % 10;
    if (dv !== d[n]) return false;
  }
  return true;
}
const CPF_FORMATADO = /(?<![\d.])\d{3}\.\d{3}\.\d{3}-\d{2}(?![\d])/g;
const ONZE_DIGITOS = /(?<![\w.:/-])\d{11}(?![\w])/g;

// O nome de alguém na pasta do usuário do Windows. O genérico ("C:/Users/..." ou "<usuario>") pode.
const PASTA_DO_USUARIO = /[A-Za-z]:[\\/]+Users[\\/]+([^\\/\s'"`<>*?|:(),;]+)/gi;
const PASTA_GENERICA = /^(?:\.\.\.|<[^>]*>|%\w+%|\$\{?\w+\}?|Public|Default|Nome%20Sobrenome)$/;

/** Os achados de uma linha: só os tipos, nunca o valor. */
export function achadosDaLinha(linha) {
  const achados = [];
  for (const m of linha.matchAll(EMAIL)) if (!emailPermitido(m[0])) achados.push('e-mail');
  if (TELEFONES.some((re) => re.test(linha))) achados.push('telefone');
  for (const m of linha.matchAll(CPF_FORMATADO)) {
    if (!/^(\d)\1{2}\.\1{3}\.\1{3}-\1{2}$/.test(m[0])) { achados.push('CPF'); break; }
  }
  for (const m of linha.matchAll(ONZE_DIGITOS)) if (cpfValido(m[0])) { achados.push('CPF'); break; }
  for (const m of linha.matchAll(PASTA_DO_USUARIO)) if (!PASTA_GENERICA.test(m[1])) achados.push('nome na pasta do usuário');
  return [...new Set(achados)];
}

// ---------------------------------------------------------------------------------------------------------------
// O detector acha o que tem de achar (exemplos montados aos pedaços)
// ---------------------------------------------------------------------------------------------------------------

const junta = (...pedacos) => pedacos.join('');
// Um CPF que passa nos dígitos verificadores, calculado aqui a partir de nove dígitos.
function cpfDe(nove) {
  const d = [...nove].map(Number);
  for (const n of [9, 10]) {
    let soma = 0;
    for (let i = 0; i < n; i++) soma += d[i] * (n + 1 - i);
    d.push((soma * 10) % 11 % 10);
  }
  return d.join('');
}

test('o detector acha e-mail de pessoa, e deixa o de não responda e o fictício', () => {
  assert.deepEqual(achadosDaLinha(`contato: ${junta('fulano.tal', '@', 'empresa.com.br')}`), ['e-mail']);
  assert.deepEqual(achadosDaLinha(junta('alguem', '@', 'gmail', '.com')), ['e-mail']);
  for (const ok of [
    junta('noreply', '@', 'anthropic.com'), junta('no-reply', '@', 'exemplo.com'), junta('123+x', '@', 'users.noreply.github.com'),
    junta('tecnico', '@', 'exemplo.com.br'), junta('gc_ronda_tecnico', '@', 'exemplo.com.br'), junta('a', '@', 'example.org'),
    junta('a', '@', 'campo.test'), 'git@github.com:Grid-Co-CODE/x.git', '@capacitor/core@8.5.3', 'msal-browser@2.38.1',
  ]) {
    assert.deepEqual(achadosDaLinha(ok), [], ok);
  }
});

test('o detector acha telefone com DDD, com +55 e o celular corrido', () => {
  for (const tel of [junta('(1', '1) 9', '1234-5678'), junta('(2', '1)3', '3334444'), junta('+55 ', '11 9', '1234 5678'), junta('+55', '(31)', '988887777'), junta('Ligue ', '1198', '7654321', '.')]) {
    assert.deepEqual(achadosDaLinha(tel), ['telefone'], tel);
  }
  // Data, hora, versão, SHA e número grande não são telefone.
  for (const ok of ['2026-10-17', '17/10/2026 23:10', 'v1.0.0-rc.1', 'ab'.repeat(32), '2147483647', '1700000000000', 'GITHUB_RUN_NUMBER=12345678901x']) {
    assert.deepEqual(achadosDaLinha(ok), [], ok);
  }
});

test('o detector acha CPF formatado e o corrido que passa nos verificadores', () => {
  const cpf = cpfDe('123456789');
  const formatado = `${cpf.slice(0, 3)}.${cpf.slice(3, 6)}.${cpf.slice(6, 9)}-${cpf.slice(9)}`;
  assert.deepEqual(achadosDaLinha(`cpf ${formatado}`), ['CPF']);
  assert.deepEqual(achadosDaLinha(`cpf ${cpf}`), ['CPF']);
  // O corrido com o 9 na terceira casa também tem cara de celular: basta que o CPF seja achado.
  assert.ok(achadosDaLinha(`cpf ${cpfDe('529982247')}`).includes('CPF'));
  // O corrido que não passa nos verificadores é só um número; o de dígitos repetidos é o exemplo de sempre.
  const errado = cpf.slice(0, 10) + String((Number(cpf[10]) + 1) % 10);
  assert.deepEqual(achadosDaLinha(`n ${errado}`), []);
  assert.deepEqual(achadosDaLinha(junta('000', '.000', '.000', '-00')), []);
});

test('o detector acha o nome na pasta do usuário, e deixa o genérico', () => {
  assert.deepEqual(achadosDaLinha(junta('C:', '/Users/', 'Fulano Tal/AppData')), ['nome na pasta do usuário']);
  assert.deepEqual(achadosDaLinha(junta('C:', '\\Users\\', 'fulano\\Desktop')), ['nome na pasta do usuário']);
  for (const ok of ['C:/Users/...', 'C:/Users/<usuario>/x', 'C:\\Users\\%USERNAME%\\x', 'C:/Users/Public']) assert.deepEqual(achadosDaLinha(ok), [], ok);
});

// ---------------------------------------------------------------------------------------------------------------
// A varredura dos arquivos versionados
// ---------------------------------------------------------------------------------------------------------------

function arquivosVersionados() {
  const r = spawnSync('git', ['ls-files', '-z'], { cwd: raiz, encoding: 'utf8' });
  assert.equal(r.status, 0, `o git ls-files falhou: ${r.stderr}`);
  return r.stdout.split('\0').filter(Boolean);
}

test('nenhum arquivo versionado tem e-mail de pessoa, telefone, CPF ou nome na pasta do usuário', () => {
  const arquivos = arquivosVersionados();
  assert.ok(arquivos.length >= 50, 'a lista do git veio');
  const problemas = [];
  for (const caminho of arquivos) {
    if (BINARIOS.has(extname(caminho).toLowerCase())) continue;
    let texto;
    try { texto = readFileSync(join(raiz, caminho), 'utf8'); } catch { continue; /* apagado e não commitado */ }
    if (texto.slice(0, 8000).includes('\0')) continue;
    texto.split(/\r?\n/).forEach((linha, i) => {
      for (const tipo of achadosDaLinha(linha)) problemas.push(`${caminho}:${i + 1} (${tipo})`);
    });
  }
  assert.deepEqual(problemas, [], `dado pessoal em arquivo versionado (o repositório é público):\n${problemas.join('\n')}`);
});
