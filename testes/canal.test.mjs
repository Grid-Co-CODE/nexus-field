// Prova que a lista fechada de rótulos do erro da página (Mensageiro.ONDES_DA_PAGINA, no nativo) é a mesma que o
// www/casca/inicio.js usa. O nativo leva ao log só o rótulo e a contagem; rótulo fora da lista vira 'outro'. Se o script
// ganhar um rótulo e a lista não, o aviso dele chega ao log sem dizer de onde veio; se a lista guardar um que o script
// não usa mais, ela deixa de ser a do script.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const ler = (caminho) => readFileSync(join(raiz, caminho), 'utf8');

const script = ler('www/casca/inicio.js');
const mensageiro = ler('android/app/src/main/java/br/com/gridco/campo/Mensageiro.java');

function todos(texto, regex) {
  return [...texto.matchAll(regex)].map((m) => m[1]);
}

// Os rótulos do script: o 1º argumento do `falhou`, o 2º do `enviar`, e o que chega a eles pelas funções que repassam
// o rótulo (`tentar`, `seguro`, `registrar`, `espelharFila`, e o `motivo` do `pararCarga`).
function rotulosDoScript() {
  const rotulos = new Set([
    ...todos(script, /\bfalhou\(\s*'([^']+)'/g),
    ...todos(script, /\benviar\(\{[^}]*\},\s*'([^']+)'\)/g),
    ...todos(script, /\btentar\(ctx,\s*'([^']+)'/g),
    ...todos(script, /\bseguro\(\s*'([^']+)'/g),
    ...todos(script, /\bregistrar\(this,[^;]*\},\s*'([^']+)'\)/g),
    ...todos(script, /\bespelharFila\(ctx, ls, [^,()]+, '([^']+)'\)/g),
    ...todos(script, /\bpararCarga\(ctx, ls, '([^']+)'\)/g),
  ]);
  return [...rotulos].sort();
}

function rotulosDoNativo() {
  const bloco = mensageiro.match(/ONDES_DA_PAGINA = [\s\S]*?\)\)\);/);
  assert.ok(bloco, 'o Mensageiro.java declara ONDES_DA_PAGINA');
  return [...new Set(todos(bloco[0], /"([^"]+)"/g))].sort();
}

test('o rótulo que não é texto literal só vem das funções que repassam um', () => {
  // Um `falhou(x, ...)` com outro nome de variável traria um rótulo que este teste não vê.
  const variaveis = new Set(todos(script, /\bfalhou\(\s*([A-Za-z_$][\w$]*)\s*,/g));
  assert.deepEqual([...variaveis].sort(), ['motivo', 'onde']);
  const doEnviar = new Set(todos(script, /\benviar\(\{[^}]*\},\s*([A-Za-z_$][\w$]*)\)/g));
  assert.deepEqual([...doEnviar], ['onde']);
});

test('a lista do nativo é a dos rótulos do inicio.js', () => {
  const doScript = rotulosDoScript();
  assert.ok(doScript.length > 20, 'a extração achou os rótulos: ' + doScript.join(', '));
  assert.deepEqual(rotulosDoNativo(), doScript);
});
