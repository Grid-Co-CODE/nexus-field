// Prova a issue do contrato diário (ferramentas/issue-do-contrato.mjs): uma issue por TIPO de falha, para um site fora do
// ar (a issue de leitura) não esconder uma quebra de verdade que vier depois, e um comentário quando a quebra muda. O
// GitHub é falso: o `gh` daqui só anota o que lhe pediram.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { tipoDoResultado, decidir, executar, TIPOS } from '../ferramentas/issue-do-contrato.mjs';

const EXECUCAO = 'https://github.com/exemplo/repo/actions/runs/1';
const OK = 'Contrato ok: o campo.html tem tudo de que a casca depende.\n';
const QUEBRADO = "Contrato quebrado: 1 item(ns) do campo.html que a casca supõe não estão como antes.\n- 'gc_fech_draft'\nConfira a classificação.\n";
const QUEBRADO_OUTRO = "Contrato quebrado: 2 item(ns) do campo.html que a casca supõe não estão como antes.\n- 'gc_fech_draft'\n- 'gc_zl_pend'\nConfira a classificação.\n";
const LEITURA = 'Contrato: não consegui ler o site (o site respondeu 503). Nada foi conferido.\n';
const ESTOURO = 'file:///x/testes/contrato.mjs:18\nSyntaxError: Unexpected token\n';

const issue = (number, tipo, body, comentarios = []) => ({
  number, labels: [{ name: TIPOS[tipo].rotulo }], body, comments: comentarios.map((b) => ({ body: b })),
});
const corpoCom = (saida) => `intro\n\n\`\`\`\n${saida.trim()}\n\`\`\`\n\nExecução: x`;

// ---------------------------------------------------------------------------------------------------------------

test('tipoDoResultado: o código E a primeira linha do contrato dizem o tipo', () => {
  assert.equal(tipoDoResultado('0', OK), 'ok');
  assert.equal(tipoDoResultado('1', QUEBRADO), 'mudou');
  assert.equal(tipoDoResultado('2', LEITURA), 'leitura');
  // O Node que quebra ao carregar o contrato (um erro de sintaxe no inicio.js) sai com 1, como o "site mudou": sem a
  // linha do contrato, é erro do próprio teste, e não vira a issue de quebra do site.
  assert.equal(tipoDoResultado('1', ESTOURO), 'erro');
  assert.equal(tipoDoResultado('3', 'Contrato: o próprio teste falhou (TypeError).'), 'erro');
  assert.equal(tipoDoResultado('', ''), 'erro');
  assert.equal(tipoDoResultado(undefined, undefined), 'erro');
  assert.equal(tipoDoResultado('0', ''), 'erro', 'o 0 sem a linha de ok não é ok');
  assert.equal(tipoDoResultado('2', QUEBRADO), 'erro');
});

test('os três tipos têm rótulos e títulos diferentes', () => {
  const rotulos = Object.values(TIPOS).map((t) => t.rotulo);
  const titulos = Object.values(TIPOS).map((t) => t.titulo);
  assert.deepEqual(Object.keys(TIPOS).sort(), ['erro', 'leitura', 'mudou']);
  assert.equal(new Set(rotulos).size, 3);
  assert.equal(new Set(titulos).size, 3);
  assert.equal(TIPOS.mudou.rotulo, 'contrato', 'o rótulo de sempre continua sendo o da quebra de verdade');
});

test('quebra sem issue aberta: cria a issue de quebra, com a saída e a execução', () => {
  const acoes = decidir({ codigo: '1', saida: QUEBRADO, abertas: [], execucao: EXECUCAO });
  assert.equal(acoes.length, 1);
  assert.equal(acoes[0].tipo, 'criar');
  assert.equal(acoes[0].rotulo, 'contrato');
  assert.match(acoes[0].corpo, /'gc_fech_draft'/);
  assert.ok(acoes[0].corpo.includes(EXECUCAO));
});

test('a issue de leitura aberta NÃO esconde a quebra de verdade: a quebra abre a dela e a de leitura fecha', () => {
  const abertas = [issue(5, 'leitura', corpoCom(LEITURA))];
  const acoes = decidir({ codigo: '1', saida: QUEBRADO, abertas, execucao: EXECUCAO });
  assert.deepEqual(acoes.map((a) => [a.tipo, a.numero ?? a.rotulo]), [['fechar', 5], ['criar', 'contrato']]);
});

test('quebra igual à da issue aberta: nada (o contrato falha todo dia até alguém consertar)', () => {
  assert.deepEqual(decidir({ codigo: '1', saida: QUEBRADO, abertas: [issue(7, 'mudou', corpoCom(QUEBRADO))], execucao: EXECUCAO }), []);
  // A saída é comparada sem o espaço da volta.
  assert.deepEqual(decidir({ codigo: '1', saida: `\n${QUEBRADO}\n\n`, abertas: [issue(7, 'mudou', corpoCom(QUEBRADO))], execucao: EXECUCAO }), []);
});

test('a quebra mudou (outra peça quebrou): comenta na issue aberta com a saída nova, uma vez', () => {
  const abertas = [issue(7, 'mudou', corpoCom(QUEBRADO))];
  const acoes = decidir({ codigo: '1', saida: QUEBRADO_OUTRO, abertas, execucao: EXECUCAO });
  assert.equal(acoes.length, 1);
  assert.equal(acoes[0].tipo, 'comentar');
  assert.equal(acoes[0].numero, 7);
  assert.match(acoes[0].corpo, /'gc_zl_pend'/);
  // No dia seguinte, a mesma saída nova já está no último comentário: nada.
  const depois = [issue(7, 'mudou', corpoCom(QUEBRADO), [acoes[0].corpo])];
  assert.deepEqual(decidir({ codigo: '1', saida: QUEBRADO_OUTRO, abertas: depois, execucao: EXECUCAO }), []);
});

test('site fora do ar: abre a issue de leitura, uma só, e não mexe na de quebra', () => {
  assert.deepEqual(decidir({ codigo: '2', saida: LEITURA, abertas: [], execucao: EXECUCAO }).map((a) => [a.tipo, a.rotulo]), [['criar', 'contrato-leitura']]);
  const abertas = [issue(5, 'leitura', corpoCom(LEITURA)), issue(7, 'mudou', corpoCom(QUEBRADO))];
  assert.deepEqual(decidir({ codigo: '2', saida: LEITURA.replace('503', '502'), abertas, execucao: EXECUCAO }), []);
});

test('contrato ok: fecha a de leitura e a de erro; na de quebra, comenta que voltou a passar, uma vez só', () => {
  const abertas = [issue(5, 'leitura', corpoCom(LEITURA)), issue(6, 'erro', corpoCom(ESTOURO)), issue(7, 'mudou', corpoCom(QUEBRADO))];
  const acoes = decidir({ codigo: '0', saida: OK, abertas, execucao: EXECUCAO });
  assert.deepEqual(acoes.map((a) => [a.tipo, a.numero]), [['fechar', 5], ['fechar', 6], ['comentar', 7]]);
  assert.match(acoes[2].corpo, /voltou a passar/);
  // A de quebra não é fechada sozinha: quem a fecha confere se a casca precisa de uma versão nova.
  const depois = [issue(7, 'mudou', corpoCom(QUEBRADO), [acoes[2].corpo])];
  assert.deepEqual(decidir({ codigo: '0', saida: OK, abertas: depois, execucao: EXECUCAO }), []);
  // E se quebrar de novo com a mesma saída do corpo, o último texto é o "voltou a passar": comenta a quebra de novo.
  assert.equal(decidir({ codigo: '1', saida: QUEBRADO, abertas: depois, execucao: EXECUCAO })[0].tipo, 'comentar');
});

test('erro do próprio teste: abre a issue de erro, uma só, sem tocar nas outras', () => {
  assert.deepEqual(decidir({ codigo: '1', saida: ESTOURO, abertas: [], execucao: EXECUCAO }).map((a) => [a.tipo, a.rotulo]), [['criar', 'contrato-erro']]);
  const abertas = [issue(6, 'erro', corpoCom(ESTOURO)), issue(5, 'leitura', corpoCom(LEITURA))];
  assert.deepEqual(decidir({ codigo: '1', saida: ESTOURO, abertas, execucao: EXECUCAO }), []);
});

test('o site lido de novo (mesmo com quebra) fecha a de leitura e a de erro', () => {
  const abertas = [issue(5, 'leitura', corpoCom(LEITURA)), issue(6, 'erro', corpoCom(ESTOURO))];
  const acoes = decidir({ codigo: '2', saida: LEITURA, abertas, execucao: EXECUCAO });
  assert.deepEqual(acoes.map((a) => [a.tipo, a.numero]), [['fechar', 6]], 'a leitura que falhou de novo só resolve a de erro');
});

// ---------------------------------------------------------------------------------------------------------------
// A execução, com um gh falso
// ---------------------------------------------------------------------------------------------------------------

function ghFalso(abertasPorRotulo = {}, falhar = () => false) {
  const chamadas = [];
  const gh = (args, entrada) => {
    chamadas.push({ args, entrada });
    if (falhar(args)) return { status: 1, stdout: '', stderr: 'falhou' };
    if (args[0] === 'issue' && args[1] === 'list') {
      const rotulo = args[args.indexOf('--label') + 1];
      return { status: 0, stdout: JSON.stringify(abertasPorRotulo[rotulo] ?? []), stderr: '' };
    }
    return { status: 0, stdout: '', stderr: '' };
  };
  return { gh, chamadas };
}

test('executar: lista as abertas dos três rótulos e cria a issue pelo stdin (nada de corpo em argumento)', () => {
  const { gh, chamadas } = ghFalso();
  const r = executar({ codigo: '1', saida: QUEBRADO, execucao: EXECUCAO, gh });
  assert.equal(r.falhas, 0);
  const listas = chamadas.filter((c) => c.args[1] === 'list').map((c) => c.args[c.args.indexOf('--label') + 1]).sort();
  assert.deepEqual(listas, ['contrato', 'contrato-erro', 'contrato-leitura']);
  for (const c of chamadas.filter((x) => x.args[1] === 'list')) assert.ok(c.args.includes('open'), 'só as abertas');
  const criar = chamadas.find((c) => c.args[0] === 'issue' && c.args[1] === 'create');
  assert.ok(criar);
  assert.deepEqual(criar.args.slice(criar.args.indexOf('--label'), criar.args.indexOf('--label') + 2), ['--label', 'contrato']);
  assert.ok(criar.args.includes('--body-file') && criar.args.includes('-'));
  assert.match(criar.entrada, /'gc_fech_draft'/);
  const rotulo = chamadas.findIndex((c) => c.args[0] === 'label' && c.args[1] === 'create');
  assert.ok(rotulo >= 0 && rotulo < chamadas.indexOf(criar), 'o rótulo é criado antes da issue');
});

test('executar: comenta e fecha pelos números certos', () => {
  const { gh, chamadas } = ghFalso({ 'contrato-leitura': [issue(5, 'leitura', corpoCom(LEITURA))], contrato: [issue(7, 'mudou', corpoCom(QUEBRADO))] });
  executar({ codigo: '1', saida: QUEBRADO_OUTRO, execucao: EXECUCAO, gh });
  const fechar = chamadas.find((c) => c.args[1] === 'close');
  assert.deepEqual(fechar.args.slice(0, 3), ['issue', 'close', '5']);
  const comentar = chamadas.find((c) => c.args[1] === 'comment');
  assert.deepEqual(comentar.args.slice(0, 3), ['issue', 'comment', '7']);
  assert.match(comentar.entrada, /'gc_zl_pend'/);
});

test('executar: gh que falha ao listar não decide às cegas (nada é criado) e conta a falha', () => {
  const { gh, chamadas } = ghFalso({}, (args) => args[1] === 'list');
  const r = executar({ codigo: '1', saida: QUEBRADO, execucao: EXECUCAO, gh });
  assert.ok(r.falhas >= 1);
  assert.ok(!chamadas.some((c) => c.args[1] === 'create'), 'sem saber o que está aberto, não abre outra');
});

test('executar: a falha de uma ação conta, e as outras seguem', () => {
  const { gh } = ghFalso({ 'contrato-leitura': [issue(5, 'leitura', corpoCom(LEITURA))] }, (args) => args[1] === 'close');
  const r = executar({ codigo: '1', saida: QUEBRADO, execucao: EXECUCAO, gh });
  assert.equal(r.falhas, 1);
  assert.ok(r.feitas.some((a) => a.tipo === 'criar'));
});
