// A issue do contrato diário (.github/workflows/contrato.yml). Uma issue por TIPO de falha, com rótulo e título próprios:
// com uma issue só para tudo, um "site fora do ar" passageiro (o App Service que dorme além das três tentativas) abria a
// issue, e a quebra de verdade do dia seguinte não gerava nada, porque já havia uma aberta e a equipe já a lia como ruído.
//
//   mudou    rótulo contrato          o campo.html mudou algo de que a casca depende (código 1). Não fecha sozinha: quem a
//                                     fecha confere se a casca precisa de uma versão nova. Se a saída mudar (outra peça
//                                     quebrou), ganha um comentário com a saída nova; se o contrato voltar a passar, um
//                                     comentário dizendo isso, uma vez.
//   leitura  rótulo contrato-leitura  não deu para ler o site (código 2). Fecha sozinha quando o site for lido de novo.
//   erro     rótulo contrato-erro     o próprio teste quebrou (outro código, ou a saída sem a linha do contrato: um erro de
//                                     sintaxe ao carregar o inicio.js sai com 1, como o "site mudou"). Fecha sozinha quando
//                                     o contrato voltar a rodar.
//
//   CODIGO=<saída do contrato> GH_REPO=dono/repo node ferramentas/issue-do-contrato.mjs <arquivo com a saída do contrato>
//
// A saída do contrato diz o NOME do que sumiu e nunca copia trecho do campo.html; o corpo das issues é ela.

import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { executadoDireto } from './executado-direto.mjs';

export const TIPOS = {
  mudou: {
    rotulo: 'contrato', cor: 'B60205', titulo: 'Contrato com o site quebrado',
    descricao: 'O campo.html mudou e a casca depende do que mudou',
  },
  leitura: {
    rotulo: 'contrato-leitura', cor: 'FBCA04', titulo: 'Contrato: não deu para ler o site',
    descricao: 'O contrato diário não conseguiu ler o /api/app; fecha sozinha quando voltar',
  },
  erro: {
    rotulo: 'contrato-erro', cor: 'D93F0B', titulo: 'Contrato: o próprio teste falhou',
    descricao: 'O testes/contrato.mjs quebrou antes de conferir o site; fecha sozinha quando voltar',
  },
};

// As primeiras linhas que o testes/contrato.mjs escreve em cada caso. Código sem a linha certa é erro do próprio teste.
const PRIMEIRA_LINHA = {
  ok: /^Contrato ok:/m,
  mudou: /^Contrato quebrado:/m,
  leitura: /^Contrato: não consegui ler o site/m,
};

const MARCA_OK = '<!-- contrato-voltou-a-passar -->';

/** 'ok', 'mudou', 'leitura' ou 'erro', pelo código de saída do contrato E pela linha que ele escreveu. */
export function tipoDoResultado(codigo, saida) {
  const texto = typeof saida === 'string' ? saida : '';
  const c = String(codigo ?? '').trim();
  if (c === '0' && PRIMEIRA_LINHA.ok.test(texto)) return 'ok';
  if (c === '1' && PRIMEIRA_LINHA.mudou.test(texto)) return 'mudou';
  if (c === '2' && PRIMEIRA_LINHA.leitura.test(texto)) return 'leitura';
  return 'erro';
}

const bloco = (saida) => `\`\`\`\n${String(saida ?? '').trim()}\n\`\`\``;

function blocoDe(texto) {
  const m = /```\n([\s\S]*?)\n```/.exec(String(texto ?? ''));
  return m ? m[1].trim() : null;
}

// O texto mais recente da issue: o último comentário, ou o corpo se não houver comentário.
function ultimoTexto(issue) {
  const comentarios = Array.isArray(issue.comments) ? issue.comments : [];
  return comentarios.length ? String(comentarios[comentarios.length - 1].body ?? '') : String(issue.body ?? '');
}

const temRotulo = (issue, rotulo) => Array.isArray(issue.labels) && issue.labels.some((l) => l?.name === rotulo);

function corpoNovo(tipo, codigo, saida, execucao) {
  const linhas = {
    mudou: [
      `O teste de contrato diário com o site falhou: o campo.html mudou algo de que a casca depende (código ${codigo}). Saída:`,
      '', bloco(saida), '', `Execução: ${execucao}`, '',
      'O que fazer: avisar a sessão "App de campo"; conferir a classificação das chaves em www/casca/inicio.js (CLASSES), ' +
      'a lista IGNORAR do testes/contrato.mjs, os nomes do voltar (VOLTAR), os do registro do aparelho para as ' +
      'notificações (PUSH), nos itens "push:", os da abertura da OS pelo toque na emergência (ABRIR_OS), nos itens ' +
      '"abrir-os:", e a janela do Fracttal; e só então mudar a casca ou o contrato, se a mudança no site for de ' +
      'propósito. Esta issue não fecha sozinha.',
    ],
    leitura: [
      `O contrato diário não conseguiu ler o site (código ${codigo}): nada foi conferido. Saída:`,
      '', bloco(saida), '', `Execução: ${execucao}`, '',
      'Esta issue fecha sozinha quando o contrato voltar a ler o site. Uma quebra de verdade abre outra issue, com o ' +
      'rótulo contrato.',
    ],
    erro: [
      `O próprio teste de contrato falhou antes de conferir o site (código ${codigo || 'nenhum'}). Saída:`,
      '', bloco(saida), '', `Execução: ${execucao}`, '',
      'O que fazer: rodar `npm test` e `node testes/contrato.mjs`. Esta issue fecha sozinha quando o contrato voltar a rodar.',
    ],
  };
  return linhas[tipo].join('\n');
}

/**
 * O que fazer com as issues, dado o resultado do contrato e as issues abertas dos três rótulos (como o
 * `gh issue list --json number,labels,body,comments` as devolve). Devolve uma lista de ações, na ordem:
 * { tipo: 'fechar', numero, comentario } | { tipo: 'comentar', numero, corpo } | { tipo: 'criar', rotulo, titulo, cor,
 * descricao, corpo }.
 */
export function decidir({ codigo, saida, abertas = [], execucao = '' }) {
  const resultado = tipoDoResultado(codigo, saida);
  const acoes = [];
  const doTipo = (tipo) => abertas.filter((i) => temRotulo(i, TIPOS[tipo].rotulo)).sort((a, b) => a.number - b.number);

  // O contrato rodou: a issue de erro do teste se resolveu. Leu o site: a de leitura também.
  const resolvidas = { ok: ['leitura', 'erro'], mudou: ['leitura', 'erro'], leitura: ['erro'], erro: [] }[resultado];
  for (const tipo of resolvidas) {
    for (const i of doTipo(tipo)) {
      acoes.push({ tipo: 'fechar', numero: i.number, comentario: `Voltou: o contrato rodou de novo e ${tipo === 'leitura' ? 'leu o site' : 'conferiu o site'}. Execução: ${execucao}` });
    }
  }

  const quebra = doTipo('mudou').at(-1);
  if (resultado === 'ok') {
    if (quebra && !ultimoTexto(quebra).includes(MARCA_OK)) {
      acoes.push({
        tipo: 'comentar', numero: quebra.number,
        corpo: `${MARCA_OK}\nO contrato voltou a passar (execução ${execucao}). Antes de fechar, confira se a casca precisa de uma versão nova.`,
      });
    }
    return acoes;
  }

  const aberta = resultado === 'mudou' ? quebra : doTipo(resultado).at(-1);
  if (!aberta) {
    const t = TIPOS[resultado];
    acoes.push({ tipo: 'criar', rotulo: t.rotulo, titulo: t.titulo, cor: t.cor, descricao: t.descricao, corpo: corpoNovo(resultado, codigo, saida, execucao) });
  } else if (resultado === 'mudou' && blocoDe(ultimoTexto(aberta)) !== String(saida ?? '').trim()) {
    // Outra peça quebrou (ou quebrou de novo depois de voltar a passar): a issue aberta ganha a saída nova.
    acoes.push({ tipo: 'comentar', numero: aberta.number, corpo: `A saída do contrato mudou (execução ${execucao}):\n\n${bloco(saida)}` });
  }
  return acoes;
}

function ghDeVerdade(args, entrada) {
  const r = spawnSync('gh', args, { input: entrada ?? '', encoding: 'utf8' });
  return { status: r.status ?? 1, stdout: r.stdout ?? '', stderr: r.stderr ?? String(r.error ?? '') };
}

/**
 * Lê as issues abertas dos três rótulos, decide e executa, pelo `gh` (trocável nos testes). Sem conseguir listar, não
 * decide às cegas: nada é criado nem fechado. O corpo vai pelo stdin (`--body-file -`), nunca em argumento.
 */
export function executar({ codigo, saida, execucao, gh = ghDeVerdade }) {
  const abertas = [];
  for (const t of Object.values(TIPOS)) {
    const r = gh(['issue', 'list', '--label', t.rotulo, '--state', 'open', '--json', 'number,labels,body,comments', '--limit', '50']);
    let lista = null;
    if (r.status === 0) { try { lista = JSON.parse(r.stdout || '[]'); } catch { lista = null; } }
    if (!Array.isArray(lista)) return { falhas: 1, feitas: [] };
    abertas.push(...lista);
  }
  let falhas = 0;
  const feitas = [];
  for (const acao of decidir({ codigo, saida, abertas, execucao })) {
    let r;
    if (acao.tipo === 'criar') {
      // O rótulo pode já existir (o create falha, e tudo bem).
      gh(['label', 'create', acao.rotulo, '--color', acao.cor, '--description', acao.descricao]);
      r = gh(['issue', 'create', '--label', acao.rotulo, '--title', acao.titulo, '--body-file', '-'], acao.corpo);
    } else if (acao.tipo === 'comentar') {
      r = gh(['issue', 'comment', String(acao.numero), '--body-file', '-'], acao.corpo);
    } else {
      r = gh(['issue', 'close', String(acao.numero), '--comment', acao.comentario]);
    }
    if (r.status === 0) feitas.push(acao); else falhas += 1;
  }
  return { falhas, feitas };
}

if (executadoDireto(import.meta.url)) {
  let saida = '';
  try { saida = readFileSync(process.argv[2] ?? '', 'utf8'); } catch { saida = ''; }
  const env = process.env;
  const execucao = `${env.GITHUB_SERVER_URL ?? ''}/${env.GITHUB_REPOSITORY ?? ''}/actions/runs/${env.GITHUB_RUN_ID ?? ''}`;
  const { falhas, feitas } = executar({ codigo: env.CODIGO, saida, execucao });
  for (const a of feitas) console.log(`issue: ${a.tipo} ${a.numero ?? a.rotulo}`);
  if (feitas.length === 0 && falhas === 0) console.log('issue: nada a fazer');
  if (falhas) console.error(`issue: ${falhas} pedido(s) ao GitHub falharam`);
  process.exitCode = falhas ? 1 : 0;
}
