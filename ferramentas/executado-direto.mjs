// Diz se o arquivo é o que o Node foi mandado rodar (`node arquivo.mjs`) ou se só foi importado (por um teste, por exemplo).
// Os scripts de linha de comando daqui (contrato, versao-json, testes-pulados) também exportam funções para os testes, e
// só rodam a linha de comando no primeiro caso.
//
// Compara os caminhos REAIS (com a caixa certa das letras no Windows e os atalhos resolvidos), não o texto digitado:
// comparar o texto falharia em silêncio com um caminho relativo, uma pasta ligada por atalho ou outra caixa de letra, e o
// script sairia com 0 sem ter feito nada, o pior defeito para um teste de CI.
import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export function executadoDireto(importMetaUrl) {
  if (!process.argv[1]) return false;
  try {
    return realpathSync.native(process.argv[1]) === realpathSync.native(fileURLToPath(importMetaUrl));
  } catch {
    return false;
  }
}
