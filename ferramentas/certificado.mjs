// O pino do certificado da Release. Os aparelhos confiam para sempre na chave que assinou a primeira versão instalada: um
// APK assinado com outra chave (a de teste, uma regerada por quem achou que a antiga se perdeu, o base64 de outro arquivo
// no secret) vira a "latest", o app o oferece a cada 6 h, o SHA-256 bate (o versao.json sai do mesmo APK) e o instalador
// do Android o recusa. O conselho natural, "desinstale e instale de novo", apaga a fila e o espelho. Por isso o job release
// só publica se o certificado do APK for o da variável CASCA_CERT_SHA256 do repositório (o SHA-256 público da chave, o
// mesmo que o registro de desenvolvedor do Android pede).
//
//   apksigner verify --print-certs app-campo.apk > certificados.txt
//   CERT_ESPERADO=<sha256> node ferramentas/certificado.mjs certificados.txt
//
// Sai com 0 e imprime o SHA conferido, ou com 1 e diz por quê. Nada aqui é segredo: o SHA-256 do certificado é público.

import { readFileSync } from 'node:fs';
import { executadoDireto } from './executado-direto.mjs';

const LINHA_DO_SHA256 = /^(.*?)certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)\s*$/;
const SIGNATARIO = /^Signer #(\d+) $/;

/** O SHA-256 em minúsculas e corrido: o keytool o imprime em maiúsculas e com ':' entre os bytes, o apksigner não. */
export function normalizarSha256(texto) {
  if (typeof texto !== 'string') return null;
  const sha = texto.replace(/[\s:]/g, '').toLowerCase();
  return /^[0-9a-f]{64}$/.test(sha) ? sha : null;
}

/**
 * Confere a saída do `apksigner verify --print-certs` contra o SHA esperado. Exige exatamente um signatário ("Signer #1")
 * e que TODA linha de SHA-256 de certificado (a do signatário e qualquer outra, como a de um carimbo ou de uma linhagem
 * de chaves) seja a esperada. Devolve o SHA conferido; lança Error dizendo o que não bateu.
 */
export function conferirCertificado(saidaDoApksigner, esperado) {
  const alvo = normalizarSha256(esperado);
  if (!alvo) {
    throw new Error('a variável CASCA_CERT_SHA256 está vazia, não existe ou não é um SHA-256 (64 dígitos hexadecimais)');
  }
  const linhasPorSignatario = new Map();
  const achados = [];
  for (const linha of String(saidaDoApksigner ?? '').split(/\r?\n/)) {
    const m = LINHA_DO_SHA256.exec(linha);
    if (!m) continue;
    achados.push(normalizarSha256(m[2]));
    const s = SIGNATARIO.exec(m[1]);
    if (s) linhasPorSignatario.set(s[1], (linhasPorSignatario.get(s[1]) ?? 0) + 1);
  }
  if (achados.length === 0) throw new Error('nenhum certificado na saída do apksigner (ele não rodou ou mudou de formato)');
  const quantos = [...linhasPorSignatario.values()].reduce((a, b) => a + b, 0);
  if (quantos !== 1 || linhasPorSignatario.get('1') !== 1) {
    throw new Error(`o APK tem de ter exatamente um signatário, e tem ${quantos}`);
  }
  for (const sha of achados) {
    if (sha !== alvo) throw new Error(`o certificado do APK (${sha ?? 'ilegível'}) não é o esperado (${alvo})`);
  }
  return alvo;
}

if (executadoDireto(import.meta.url)) {
  try {
    const arquivo = process.argv[2];
    if (!arquivo) throw new Error('falta o arquivo com a saída do apksigner verify --print-certs');
    let texto;
    try { texto = readFileSync(arquivo, 'utf8'); } catch { throw new Error(`não consegui ler ${arquivo}`); }
    const sha = conferirCertificado(texto, process.env.CERT_ESPERADO);
    console.log(`certificado: o APK foi assinado pela chave esperada (SHA-256 ${sha})`);
  } catch (e) {
    console.error(`certificado: ${e.message}`);
    process.exitCode = 1;
  }
}
