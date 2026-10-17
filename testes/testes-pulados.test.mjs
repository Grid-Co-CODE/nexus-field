// Prova o conferidor de testes pulados (ferramentas/testes-pulados.mjs). O CI de PR roda no Linux, onde os dois testes do
// FileProvider do AtualizadorTest têm de RODAR (no Windows eles são pulados: o FileProvider compara caminhos com "/"). Se
// o Gradle os marcar como pulados, ou se alguém renomear um deles, o CI tem de falhar, e este conferidor é quem lê o XML.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, copyFileSync, symlinkSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { situacaoDosTestes, conferirTestes } from '../ferramentas/testes-pulados.mjs';

const FERRAMENTA = join(dirname(fileURLToPath(import.meta.url)), '..', 'ferramentas', 'testes-pulados.mjs');
const CLASSE = 'br.com.gridco.campo.ExemploTest';

// No formato que o Gradle escreve em build/test-results/<tarefa>/TEST-<classe>.xml (visto em 17/10/2026): o teste que
// passou é um <testcase/> vazio, o pulado leva <skipped>, o que falhou leva <failure> ou <error>.
const XML = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="${CLASSE}" tests="5" skipped="1" failures="1" errors="1" timestamp="2026-10-17T16:11:45.170Z" hostname="x" time="1.5">
  <properties/>
  <testcase name="passou" classname="${CLASSE}" time="0.1"/>
  <testcase name="pulado" classname="${CLASSE}" time="0.03">
    <skipped message="o FileProvider espera caminhos com '/'" type="org.junit.AssumptionViolatedException">org.junit.AssumptionViolatedException: x
\tat org.junit.Assume.assumeTrue(Assume.java:68)
</skipped>
  </testcase>
  <testcase name="falhou" classname="${CLASSE}" time="0.03">
    <failure message="esperado" type="java.lang.AssertionError">java.lang.AssertionError: esperado</failure>
  </testcase>
  <testcase name="quebrou" classname="${CLASSE}" time="0.03">
    <error message="boom" type="java.lang.IllegalStateException">java.lang.IllegalStateException: boom</error>
  </testcase>
  <testcase name="passouComSaida" classname="${CLASSE}" time="0.03"/>
  <system-out><![CDATA[uma linha com <skipped> e <failure> dentro do texto de saída]]></system-out>
  <system-err><![CDATA[]]></system-err>
</testsuite>
`;

test('lê a situação de cada teste do XML do Gradle', () => {
  const s = situacaoDosTestes(XML);
  assert.equal(s.get('passou'), 'passou');
  assert.equal(s.get('pulado'), 'pulado');
  assert.equal(s.get('falhou'), 'falhou');
  assert.equal(s.get('quebrou'), 'falhou');
  assert.equal(s.get('passouComSaida'), 'passou');
  assert.equal(s.size, 5);
});

test('o texto de saída do teste (CDATA) não vira falha nem pulo de ninguém', () => {
  const xml = `<testsuite><testcase name="a" classname="x" time="0">
    <system-out><![CDATA[<skipped> <failure>]]></system-out></testcase></testsuite>`;
  assert.equal(situacaoDosTestes(xml).get('a'), 'passou');
});

test('conferirTestes: os que passaram não dão problema', () => {
  assert.deepEqual(conferirTestes(XML, ['passou', 'passouComSaida']), []);
});

test('conferirTestes: pulado, falho e ausente (renomeado) são problema, cada um com o nome', () => {
  const problemas = conferirTestes(XML, ['passou', 'pulado', 'falhou', 'sumiu']);
  assert.equal(problemas.length, 3);
  assert.match(problemas.find((p) => p.includes('pulado')), /pulado/);
  assert.match(problemas.find((p) => p.includes('falhou')), /falhou/);
  assert.match(problemas.find((p) => p.includes('sumiu')), /não rodou|ausente|não há/i);
});

test('conferirTestes: pedir zero testes é um erro de quem chama', () => {
  assert.throws(() => conferirTestes(XML, []), /nenhum teste/i);
});

// ---------------------------------------------------------------------------------------------------------------
// A linha de comando, como o workflow a chama
// ---------------------------------------------------------------------------------------------------------------

function rodar(nomes, { escrever = true } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'pulados-'));
  try {
    if (escrever) writeFileSync(join(dir, `TEST-${CLASSE}.xml`), XML);
    return spawnSync(process.execPath, [FERRAMENTA, dir, CLASSE, ...nomes], { encoding: 'utf8' });
  } finally { rmSync(dir, { recursive: true, force: true }); }
}

test('linha de comando: sai com 0 quando todos rodaram', () => {
  const r = rodar(['passou', 'passouComSaida']);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /2/);
});

test('linha de comando: sai com 1 e diz qual foi pulado', () => {
  const r = rodar(['passou', 'pulado']);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /pulado/);
});

test('linha de comando: sem o XML da classe (o Gradle nem rodou) também falha', () => {
  const r = rodar(['passou'], { escrever: false });
  assert.equal(r.status, 1);
  assert.match(r.stderr, new RegExp(CLASSE.replace(/\./g, '\\.')));
});

test('linha de comando: sem os argumentos, explica o uso', () => {
  const r = spawnSync(process.execPath, [FERRAMENTA], { encoding: 'utf8' });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /uso/i);
});

test('linha de comando: roda também por caminho relativo (como o workflow chama)', () => {
  const raiz = join(dirname(FERRAMENTA), '..');
  const r = spawnSync(process.execPath, ['ferramentas/testes-pulados.mjs'], { cwd: raiz, encoding: 'utf8' });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /uso/i);
});

test('linha de comando: roda também por um atalho de pasta, sem sair com 0 em silêncio', (t) => {
  // O Node resolve o atalho para o módulo (import.meta.url) mas deixa o process.argv[1] como foi digitado. Comparar os
  // dois como texto faria o script não rodar e sair com 0: um CI verde sem conferir nada. O atalho aponta para uma CÓPIA
  // dos dois arquivos numa pasta temporária, para a limpeza nunca chegar perto da pasta de verdade.
  const dir = mkdtempSync(join(tmpdir(), 'pulados-atalho-'));
  try {
    mkdirSync(join(dir, 'real'));
    for (const arquivo of ['testes-pulados.mjs', 'executado-direto.mjs']) copyFileSync(join(dirname(FERRAMENTA), arquivo), join(dir, 'real', arquivo));
    try { symlinkSync(join(dir, 'real'), join(dir, 'atalho'), 'junction'); } catch { t.skip('o sistema não deixou criar o atalho de pasta'); return; }
    const r = spawnSync(process.execPath, [join(dir, 'atalho', 'testes-pulados.mjs')], { encoding: 'utf8' });
    assert.equal(r.status, 2);
    assert.match(r.stderr, /uso/i);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
