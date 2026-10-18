// Leitor mínimo de PNG para os testes do ícone (testes/icone.test.mjs), sem dependência nova: só o zlib do Node. Lê o
// que o ferramentas/gerar-icones.py escreve (RGBA de 8 bits, sem entrelaçamento) e recusa o resto em voz alta, para um
// PNG de outro formato não passar por "transparente" sem ninguém ver.
import { readFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';

const ASSINATURA = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

// O preditor de Paeth da especificação do PNG (filtro 4).
function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

/** Devolve { largura, altura, rgba } (rgba: Uint8Array com 4 bytes por pixel, linha a linha). */
export function lerPng(caminho) {
  const bytes = readFileSync(caminho);
  if (!bytes.subarray(0, 8).equals(ASSINATURA)) throw new Error(`${caminho}: não é PNG`);
  let i = 8;
  let cabecalho = null;
  const dados = [];
  while (i < bytes.length) {
    const tamanho = bytes.readUInt32BE(i);
    const tipo = bytes.toString('latin1', i + 4, i + 8);
    const corpo = bytes.subarray(i + 8, i + 8 + tamanho);
    if (tipo === 'IHDR') {
      cabecalho = {
        largura: corpo.readUInt32BE(0), altura: corpo.readUInt32BE(4),
        bits: corpo[8], tipoDeCor: corpo[9], entrelacado: corpo[12],
      };
    } else if (tipo === 'IDAT') {
      dados.push(corpo);
    } else if (tipo === 'IEND') {
      break;
    }
    i += 12 + tamanho;
  }
  if (!cabecalho) throw new Error(`${caminho}: sem IHDR`);
  const { largura, altura, bits, tipoDeCor, entrelacado } = cabecalho;
  if (bits !== 8 || tipoDeCor !== 6 || entrelacado !== 0) {
    throw new Error(`${caminho}: o leitor só entende RGBA de 8 bits sem entrelaçamento (bits ${bits}, cor ${tipoDeCor})`);
  }
  const cru = inflateSync(Buffer.concat(dados));
  const porLinha = largura * 4;
  if (cru.length !== altura * (porLinha + 1)) throw new Error(`${caminho}: dados com tamanho inesperado`);
  const rgba = new Uint8Array(altura * porLinha);
  for (let y = 0; y < altura; y++) {
    const filtro = cru[y * (porLinha + 1)];
    const origem = y * (porLinha + 1) + 1;
    const destino = y * porLinha;
    for (let x = 0; x < porLinha; x++) {
      const bruto = cru[origem + x];
      const a = x >= 4 ? rgba[destino + x - 4] : 0;
      const b = y > 0 ? rgba[destino - porLinha + x] : 0;
      const c = x >= 4 && y > 0 ? rgba[destino - porLinha + x - 4] : 0;
      let valor;
      switch (filtro) {
        case 0: valor = bruto; break;
        case 1: valor = bruto + a; break;
        case 2: valor = bruto + b; break;
        case 3: valor = bruto + ((a + b) >> 1); break;
        case 4: valor = bruto + paeth(a, b, c); break;
        default: throw new Error(`${caminho}: filtro ${filtro} desconhecido na linha ${y}`);
      }
      rgba[destino + x] = valor & 0xff;
    }
  }
  return { largura, altura, rgba };
}

/** O pixel (x, y) como [r, g, b, a]. */
export function pixel(png, x, y) {
  const k = (y * png.largura + x) * 4;
  return [png.rgba[k], png.rgba[k + 1], png.rgba[k + 2], png.rgba[k + 3]];
}
