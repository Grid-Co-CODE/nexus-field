// Confere, nos XML de resultado do Gradle, que certos testes RODARAM (nem pulados, nem falhos, nem sumidos).
//
//   node ferramentas/testes-pulados.mjs <pasta dos XML> <classe completa> <teste>...
//
// Por que existe: dois testes do AtualizadorTest passam pelo FileProvider do Android, que no Windows é pulado (ele compara
// caminhos com "/"). Um teste pulado o Gradle conta como sucesso, então só o Linux do CI prova esses dois. Se o Assume
// passar a pulá-los lá também, ou se alguém renomear um deles, o CI tem de falhar, e quem enxerga isso é este conferidor.
// Sai com 0 (todos rodaram), 1 (algum pulado, falho ou ausente) ou 2 (uso errado).

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { executadoDireto } from './executado-direto.mjs';

/** Mapa nome -> 'passou' | 'pulado' | 'falhou' de cada <testcase> do XML do Gradle (JUnit). */
export function situacaoDosTestes(xml) {
  const situacao = new Map();
  for (const m of xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
    const nome = /\bname="([^"]*)"/.exec(m[1])?.[1];
    if (nome === undefined) continue;
    // O texto de saída do teste (CDATA) pode ter "<skipped>" escrito: só o que está fora dele conta.
    const corpo = (m[2] ?? '').replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '');
    situacao.set(nome, /<(?:failure|error)\b/.test(corpo) ? 'falhou' : /<skipped\b/.test(corpo) ? 'pulado' : 'passou');
  }
  return situacao;
}

/** O que há de errado com os `nomes` pedidos: uma frase por teste que não passou. Vazia = todos rodaram e passaram. */
export function conferirTestes(xml, nomes) {
  if (!nomes.length) throw new Error('nenhum teste pedido para conferir');
  const situacao = situacaoDosTestes(xml);
  const problemas = [];
  for (const nome of nomes) {
    const s = situacao.get(nome);
    if (s === undefined) problemas.push(`${nome}: não rodou (não há esse teste no resultado; foi renomeado?)`);
    else if (s === 'pulado') problemas.push(`${nome}: pulado`);
    else if (s === 'falhou') problemas.push(`${nome}: falhou`);
  }
  return problemas;
}

if (executadoDireto(import.meta.url)) {
  const [pasta, classe, ...nomes] = process.argv.slice(2);
  if (!pasta || !classe || nomes.length === 0) {
    console.error('uso: node ferramentas/testes-pulados.mjs <pasta dos XML> <classe completa> <teste>...');
    process.exitCode = 2;
  } else {
    const arquivo = join(pasta, `TEST-${classe}.xml`);
    let xml = null;
    try { xml = readFileSync(arquivo, 'utf8'); } catch { /* sem o arquivo */ }
    if (xml === null) {
      console.error(`testes-pulados: não achei o resultado de ${classe} (${arquivo}); o Gradle não rodou a classe`);
      process.exitCode = 1;
    } else {
      const problemas = conferirTestes(xml, nomes);
      if (problemas.length) {
        console.error(`testes-pulados: ${classe}\n${problemas.map((p) => `- ${p}`).join('\n')}`);
        process.exitCode = 1;
      } else {
        console.log(`testes-pulados: os ${nomes.length} teste(s) de ${classe} rodaram e passaram`);
      }
    }
  }
}
