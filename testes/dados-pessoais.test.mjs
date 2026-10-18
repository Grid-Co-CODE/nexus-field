// O repositório é PÚBLICO: nenhum arquivo versionado leva e-mail de pessoa, telefone, CPF, o nome de alguém num caminho
// de pasta (C:/Users/<nome>) ou o nome de quem trabalha no repositório (os autores do git; ver NOME DE PESSOA, abaixo).
// Exceções: os e-mails de "não responda" e os de domínio fictício (exemplo.com.br, example.com, *.test), que são os das
// fixtures. A varredura é a dos arquivos que o git versiona (git ls-files), e a mensagem diz o arquivo, a linha e o TIPO
// do achado, nunca o valor (o log do CI também é público).
//
// Os exemplos de dado pessoal deste teste são montados em tempo de execução, aos pedaços: escritos inteiros aqui, a
// varredura acharia este próprio arquivo.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, mkdirSync, copyFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
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

// ---------------------------------------------------------------------------------------------------------------
// NOME DE PESSOA: os autores do git
// ---------------------------------------------------------------------------------------------------------------
// Quem trabalha no repositório é citado pelo papel ("o dono da casca", "o responsável pela prova", "o aparelho de prova"),
// nunca pelo nome. Caso que criou a regra: o roteiro da prova dizia quem preenche o usuário de teste e de quem era o
// celular em que o adb não pode tudo. Como no teste do Nexus (que monta os nomes do cadastro), a lista de nomes é
// montada SÓ NA MEMÓRIA, aqui dos nomes de autor do `git log`: nenhum nome é escrito neste arquivo nem na mensagem de
// falha, que diz `caminho:linha`. Do nome do autor valem as palavras de 3 letras ou mais, sem acento nem caixa (a conta
// "Nome-1234" do GitHub dá "nome"; as partículas "de", "da"... não contam):
//   - o nome inteiro (a primeira palavra e qualquer outra, com espaço, ponto, hífen ou sublinhado no meio, como num
//     e-mail): em qualquer lugar;
//   - o primeiro nome sozinho, só onde parece gente: com maiúscula, sem dígito ou hífen logo depois, e depois de artigo
//     ou preposição ("o X preenche", "no celular do X", "para o X aprovar") ou antes de um sobrenome com maiúscula
//     ("Responsável: X Sobrenome"). O que estas regras não pegam, a revisão pega.
// Ficam de fora, pelo caminho exato, só o desenho e o plano datados de 16/10 (FORA_DA_VARREDURA_DE_NOMES), que trazem o
// dono como responsável; a lista não cresce sozinha, e entrada cujo arquivo saiu do git faz o teste falhar. Conta
// automática (nome com "[bot]") e as do próprio GitHub não são gente.

const PARTICULAS = new Set(['de', 'da', 'do', 'das', 'dos', 'di', 'du', 'del', 'van', 'von']);
const SEPARADOR = '[\\s._-]+';
const ANTES_DE_GENTE = /(?:^|[\s("'])(?:o|a|do|da|pelo|pela|ao|à|com o|com a|que o|que a|para o|para a)\s+$/i;
// O sobrenome logo depois: outra palavra com maiúscula, só com espaço no meio (e a partícula, "de Tal"). Pega o
// "Responsável: <nome> <sobrenome>" do cabeçalho de um desenho, que não tem artigo antes, quando o autor do git é só a
// conta "Nome-1234" e o nome inteiro nunca é montado (o caso deste repositório).
const SOBRENOME_DEPOIS = /^[ \t]+(?:(?:de|da|do|das|dos|di|du|del|van|von)[ \t]+)?\p{Lu}\p{L}/u;
const CONTAS_QUE_NAO_SAO_GENTE = new Set(['github', 'copilot', 'claude', 'dependabot']);
// Pelo caminho exato, nunca pela pasta: com o prefixo `docs/superpowers/`, todo desenho ou plano novo gravado ali (o
// lugar padrão deles) nunca seria varrido. Arquivo novo nessa pasta passa pela varredura como qualquer outro.
const FORA_DA_VARREDURA_DE_NOMES = new Set([
  'docs/superpowers/specs/2026-10-16-app-campo-android-design.md',
  'docs/superpowers/plans/2026-10-16-app-campo-android.md',
]);

/** Se o caminho versionado (como o `git ls-files` o escreve) fica fora da varredura de nomes. */
export function foraDaVarreduraDeNomes(caminho) {
  return FORA_DA_VARREDURA_DE_NOMES.has(caminho);
}

const semAcento = (s) => s.normalize('NFKD').replace(/[̀-ͯ]/g, '');
const escaparRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** As regras de busca para uma lista de nomes (só na memória). */
export function padroesDeNomes(nomes) {
  const inteiros = new Set();
  const primeiros = new Set();
  for (const nome of nomes) {
    if (/\[bot\]/i.test(nome)) continue;
    const palavras = semAcento(String(nome)).toLowerCase().split(/[^a-z]+/)
      .filter((p) => p.length >= 3 && !PARTICULAS.has(p));
    if (!palavras.length || CONTAS_QUE_NAO_SAO_GENTE.has(palavras[0])) continue;
    primeiros.add(escaparRe(palavras[0]));
    for (const outra of palavras.slice(1)) inteiros.add(escaparRe(palavras[0]) + SEPARADOR + escaparRe(outra));
  }
  const alternativas = (conjunto) => [...conjunto].sort((a, b) => b.length - a.length).join('|');
  return {
    inteiro: inteiros.size ? new RegExp(`(?<![a-z0-9])(?:${alternativas(inteiros)})(?![a-z0-9])`) : null,
    primeiro: primeiros.size ? new RegExp(`(?<![a-z0-9])(?:${alternativas(primeiros)})(?![a-z0-9])`, 'g') : null,
  };
}

/** Se a linha cita alguém da lista pelo nome (as regras acima). */
export function citaNome(linha, { inteiro, primeiro }) {
  // A linha sem acento e em minúsculas, com o índice no original de cada caractere (para ver a maiúscula e o que vem
  // antes no texto de verdade).
  let norm = '';
  const mapa = [];
  for (let i = 0; i < linha.length; i++) {
    for (const c of semAcento(linha[i]).toLowerCase()) { norm += c; mapa.push(i); }
  }
  if (inteiro && inteiro.test(norm)) return true;
  if (!primeiro) return false;
  for (const m of norm.matchAll(primeiro)) {
    const i = mapa[m.index];
    const f = mapa[m.index + m[0].length - 1] + 1;
    const maiuscula = linha[i] !== linha[i].toLowerCase();
    if (!maiuscula || /^\s*[\d-]/.test(linha.slice(f, f + 3))) continue;
    if (ANTES_DE_GENTE.test(linha.slice(0, i)) || SOBRENOME_DEPOIS.test(linha.slice(f))) return true;
  }
  return false;
}

test('o detector de nome acha o nome inteiro em qualquer lugar e o primeiro nome só onde parece gente', () => {
  const p = padroesDeNomes([junta('Fulano', ' de ', 'Tal'), junta('Beltrano', '-1234'), 'algum-robo[bot]', 'GitHub']);
  for (const linha of [
    'Usuário de teste: (o Fulano preenche; nunca um técnico real)', 'no celular pessoal do Beltrano não é',
    'PENDENTES de aprovação do Fulano na prévia', 'para o Beltrano aprovar', 'contato: fulano.tal', 'Fulano Tal assina',
    'por FULANO_TAL',
  ]) {
    assert.equal(citaNome(linha, p), true, linha);
  }
  for (const linha of [
    'o responsável pela prova preenche', 'do fulano em minúscula, que pode ser palavra comum',
    'Fulano disse, no começo da frase', 'a conta Beltrano-1234 do GitHub', 'o Fulanos', 'o GitHub desliga o agendamento',
    'algum-robo abriu a issue',
  ]) {
    assert.equal(citaNome(linha, p), false, linha);
  }
  assert.deepEqual(padroesDeNomes([]), { inteiro: null, primeiro: null });
});

test('autor só com a conta do GitHub (sem sobrenome): o primeiro nome seguido de sobrenome é nome em qualquer lugar', () => {
  // O caso deste repositório: o único autor é a conta "Nome-1234", e o nome inteiro nunca é montado; a linha
  // "Responsável: <nome> <sobrenome>" do cabeçalho de um desenho novo passava (não há artigo antes).
  const p = padroesDeNomes([junta('Beltrano', '-1234')]);
  assert.equal(p.inteiro, null);
  for (const linha of [
    junta('16/10/2026. Responsável: ', 'Beltrano', ' Souza.'), junta('Responsável: ', 'Beltrano', ' de Souza'),
    junta('assinado ', 'BELTRANO', ' SOUZA'), junta('(', 'Beltrano', ' Souza, dono)'),
  ]) {
    assert.equal(citaNome(linha, p), true, linha);
  }
  for (const linha of [
    junta('Beltrano', ' disse'), junta('a conta ', 'Beltrano', '-1234 Souza'), junta('Responsável: ', 'Beltrano', '. Outra'),
    junta('Beltrano', ', Souza'), junta('beltrano', ' Souza'), junta('Beltranos', ' Souza'),
  ]) {
    assert.equal(citaNome(linha, p), false, linha);
  }
});

test('fora da varredura de nomes ficam só os dois arquivos datados, e arquivo novo em docs/superpowers/ é varrido', () => {
  for (const caminho of [
    'docs/superpowers/specs/2026-10-16-app-campo-android-design.md',
    'docs/superpowers/plans/2026-10-16-app-campo-android.md',
  ]) {
    assert.equal(foraDaVarreduraDeNomes(caminho), true, caminho);
  }
  for (const caminho of [
    'docs/superpowers/specs/2026-11-01-outro-design.md', 'docs/superpowers/plans/2026-11-01-outro.md',
    'docs/superpowers/notas.md', 'docs/superpowers/specs/2026-10-16-app-campo-android-design.md.bak',
    'docs/superpowers/plans/', 'docs/prova-no-aparelho.md', 'CLAUDE.md',
  ]) {
    assert.equal(foraDaVarreduraDeNomes(caminho), false, caminho);
  }
});

/**
 * O que a varredura de nomes faz com a lista de autores: 'varrer' (há autor que seja gente), 'pular' (não há, e é o
 * computador de quem desenvolve, onde o repositório pode estar sem histórico) ou 'falhar' (não há, e é o CI). No CI a
 * lista vazia quer dizer checkout raso: o pull_request traz só o commit de merge de teste do GitHub, de autor "GitHub".
 * Pular ali deixava o teste verde sem procurar nome nenhum. O GitHub Actions define CI=true.
 */
export function decisaoDaVarreduraDeNomes(padroes, ambiente) {
  if (padroes.primeiro) return 'varrer';
  return /^(?:true|1)$/i.test(String(ambiente?.CI ?? '')) ? 'falhar' : 'pular';
}

test('sem autor que seja gente no git log: fora do CI a varredura de nomes pula, e no CI ela falha', () => {
  // O caso: o checkout raso do CI (um commit só) de um pull_request traz o commit de merge de teste do GitHub, de autor
  // "GitHub", que não é gente. A lista de nomes ficava vazia e a varredura era pulada, com o teste verde: ninguém via
  // que nenhum nome estava sendo procurado. No CI isso é falha (o checkout traz o histórico inteiro, fetch-depth: 0).
  const vazia = padroesDeNomes([]);
  const soContas = padroesDeNomes(['GitHub', 'algum-robo[bot]']);
  const comGente = padroesDeNomes([junta('Beltrano', '-1234'), 'GitHub']);
  assert.equal(decisaoDaVarreduraDeNomes(comGente, {}), 'varrer');
  assert.equal(decisaoDaVarreduraDeNomes(comGente, { CI: 'true' }), 'varrer');
  for (const padroes of [vazia, soContas]) {
    assert.equal(decisaoDaVarreduraDeNomes(padroes, {}), 'pular', 'no computador de quem desenvolve, o repositório pode estar sem histórico');
    assert.equal(decisaoDaVarreduraDeNomes(padroes, { CI: '' }), 'pular');
    assert.equal(decisaoDaVarreduraDeNomes(padroes, { CI: 'false' }), 'pular');
    assert.equal(decisaoDaVarreduraDeNomes(padroes, { CI: 'true' }), 'falhar', 'o GitHub Actions define CI=true');
    assert.equal(decisaoDaVarreduraDeNomes(padroes, { CI: 'TRUE' }), 'falhar');
    assert.equal(decisaoDaVarreduraDeNomes(padroes, { CI: '1' }), 'falhar');
  }
});

test('o teste da varredura de nomes, num repositório raso do CI (só o autor "GitHub"), falha com CI=true e pula sem ele', (t) => {
  // A fiação, não só a decisão: roda uma CÓPIA deste arquivo num repositório novo cujo único commit é de "GitHub" (o merge
  // de teste de um pull_request num checkout raso) e confere o que a varredura de nomes faz de verdade. O filtro por nome
  // deixa o filho rodar só a varredura (este teste não casa com ele: sem recursão).
  const dir = mkdtempSync(join(tmpdir(), 'casca-raso-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  mkdirSync(join(dir, 'testes'));
  copyFileSync(join(raiz, 'testes', 'dados-pessoais.test.mjs'), join(dir, 'testes', 'dados-pessoais.test.mjs'));
  const git = (...args) => {
    const r = spawnSync('git', ['-c', 'user.name=GitHub', '-c', 'user.email=noreply@github.com', '-c', 'commit.gpgsign=false', ...args],
      { cwd: dir, encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
  };
  git('init', '-q');
  git('add', '-A');
  git('commit', '-q', '-m', 'merge de teste');
  const rodar = (ci) => {
    const env = { ...process.env };
    delete env.CI;
    delete env.NODE_TEST_CONTEXT; // o filho é uma execução nova do node --test, não um subteste desta
    if (ci !== undefined) env.CI = ci;
    const r = spawnSync(process.execPath, ['--test', '--test-reporter=spec', '--test-name-pattern=cita pelo nome quem trabalha',
      'testes/dados-pessoais.test.mjs'], { cwd: dir, encoding: 'utf8', env });
    return { status: r.status, saida: (r.stdout ?? '') + (r.stderr ?? '') };
  };
  const noCi = rodar('true');
  assert.notEqual(noCi.status, 0, 'no CI, sem autor que seja gente, o teste cai');
  assert.match(noCi.saida, /fetch-depth: 0/, 'e diz o que conferir');
  const local = rodar(undefined);
  assert.equal(local.status, 0, 'fora do CI pula');
  assert.match(local.saida, /skipped 1/);
});

function autoresDoGit() {
  const r = spawnSync('git', ['log', '--format=%an'], { cwd: raiz, encoding: 'utf8' });
  assert.equal(r.status, 0, `o git log falhou: ${r.stderr}`);
  return [...new Set(r.stdout.split('\n').map((s) => s.trim()).filter(Boolean))];
}

test('nenhum arquivo versionado cita pelo nome quem trabalha no repositório (os autores do git)', (t) => {
  const padroes = padroesDeNomes(autoresDoGit());
  const decisao = decisaoDaVarreduraDeNomes(padroes, process.env);
  if (decisao === 'pular') {
    t.skip('o git log não tem autor que seja gente');
    return;
  }
  assert.notEqual(decisao, 'falhar',
    'no CI o git log não trouxe nenhum autor que seja gente, e a varredura de nomes não procuraria ninguém: confira se o checkout ' +
    'tem o histórico inteiro (fetch-depth: 0 no montar.yml)');
  const arquivos = arquivosVersionados();
  // A exceção é de arquivo que existe: renomeado ou apagado, a entrada sai da lista (e o renomeado é varrido).
  for (const caminho of FORA_DA_VARREDURA_DE_NOMES) {
    assert.ok(arquivos.includes(caminho), `${caminho} está fora da varredura de nomes, mas não é mais versionado`);
  }
  const problemas = [];
  for (const caminho of arquivos) {
    if (foraDaVarreduraDeNomes(caminho) || BINARIOS.has(extname(caminho).toLowerCase())) continue;
    let texto;
    try { texto = readFileSync(join(raiz, caminho), 'utf8'); } catch { continue; /* apagado e não commitado */ }
    if (texto.slice(0, 8000).includes('\0')) continue;
    texto.split(/\r?\n/).forEach((linha, i) => {
      if (citaNome(linha, padroes)) problemas.push(`${caminho}:${i + 1} (nome de pessoa)`);
    });
  }
  assert.deepEqual(problemas, [],
    `nome de pessoa em arquivo versionado (o repositório é público; cite pelo papel):\n${problemas.join('\n')}`);
});

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
