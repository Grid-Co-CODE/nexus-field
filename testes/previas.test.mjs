// Prova que a prévia das telas novas (docs/previas/telas.html) mostra o que o APK vai mostrar. O Levi aprova os textos
// e o visual por ela; se a prévia e o aparelho divergirem, a aprovação vale para uma tela que não existe. Por isso a
// prévia não é conferida "por olho": ela é lida contra as três fontes do que vai ao aparelho.
//   1. os textos dos diálogos e do toast saem do strings.xml: nenhum a mais na prévia (nada inventado) e nenhum a menos;
//   2. a tela de erro é o www/erro.html, byte a byte (a prévia o embute num iframe);
//   3. a camada do Fracttal é a que o www/casca/inicio.js monta de verdade, com o HTML de exemplo da própria prévia.
// Quando o Levi pedir outro texto, o arquivo-fonte muda (strings.xml, erro.html ou inicio.js) e a prévia acompanha; este
// teste falha enquanto uma das duas estiver para trás.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { JSDOM } from 'jsdom';
import { criarJanela } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar } = require('../www/casca/inicio.js');

const lerArquivo = (caminho) => readFileSync(new URL(caminho, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const HTML = lerArquivo('../docs/previas/telas.html');
const STRINGS = lerArquivo('../android/app/src/main/res/values/strings.xml');
const ERRO_HTML = lerArquivo('../www/erro.html');

const doc = () => new JSDOM(HTML).window.document;
const compactar = (s) => s.replace(/\s+/g, ' ').trim();

// Os `casca_*` do strings.xml, já como o Android os entrega ao diálogo: entidades do XML e `\n` resolvidos.
function stringsDaCasca() {
  const lidas = [];
  for (const [, nome, bruto] of STRINGS.matchAll(/<string name="(casca_[a-z_]+)">([\s\S]*?)<\/string>/g)) {
    const texto = bruto
      .replace(/\\n/g, '\n').replace(/\\'/g, "'")
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
    lidas.push({ nome, texto: compactar(texto) });
  }
  return lidas;
}

// Cada string vai a um tipo de elemento da prévia. O papel sai do sufixo, a mesma convenção do strings.xml.
function papelDe(nome) {
  if (nome.endsWith('_titulo')) return '.dialogo-titulo';
  if (nome.endsWith('_toast')) return '.toast';
  if (nome.includes('_texto')) return '.dialogo-texto';
  return '.dialogo-botao';
}
const PAPEIS = ['.dialogo-titulo', '.dialogo-texto', '.dialogo-botao', '.toast'];

// O `%1$s` do strings.xml vira "qualquer coisa": a versão (%1) é uma palavra só; a lista de novidades (%2) pode ter várias.
function regexDe({ texto }) {
  const fonte = texto.split(/(%\d\$s)/).map((parte) => {
    const marca = parte.match(/^%(\d)\$s$/);
    if (!marca) return parte.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return marca[1] === '1' ? '(\\S+)' : '(.+)';
  }).join('');
  return new RegExp('^' + fonte + '$');
}

test('a prévia existe e lê o strings.xml: há strings da casca para conferir', () => {
  assert.ok(stringsDaCasca().length >= 15, 'o strings.xml deve trazer os textos dos diálogos da casca');
});

test('cada texto da prévia é um do strings.xml, e cada string da casca aparece na prévia', () => {
  const d = doc();
  const strings = stringsDaCasca();
  const naPrevia = new Map(PAPEIS.map((p) => [p, [...d.querySelectorAll('.celular ' + p)].map((e) => compactar(e.textContent))]));

  for (const s of strings) {
    const papel = papelDe(s.nome);
    const casa = naPrevia.get(papel).some((t) => regexDe(s).test(t));
    assert.ok(casa, `${s.nome} (${papel}) não aparece na prévia: "${s.texto}"`);
  }
  for (const [papel, textos] of naPrevia) {
    assert.ok(textos.length > 0, `a prévia não mostra nenhum ${papel}`);
    for (const t of textos) {
      const origem = strings.filter((s) => papelDe(s.nome) === papel).find((s) => regexDe(s).test(t));
      assert.ok(origem, `texto da prévia que não existe no strings.xml (${papel}): "${t}"`);
    }
  }
});

test('os nomes entre aspas da lista "O que aprovar" são textos de verdade do aplicativo', () => {
  // A lista é uma terceira cópia dos títulos e dos botões; sem esta conferência ela envelhece sozinha.
  const permitidos = new Set(stringsDaCasca().map((s) => s.texto));
  const erro = new JSDOM(ERRO_HTML).window.document;
  for (const el of erro.querySelectorAll('h1, p, button')) permitidos.add(compactar(el.textContent));
  const j = criarJanela();
  instalar(j.win, j.canal, j.retrato);
  j.win.open('', '_blank').document.write('<p>x</p>');
  j.avancar(1000);
  permitidos.add(compactar(j.win.document.querySelector('#gc-casca-aviso button').textContent)); // o "Fechar"

  const citados = [...doc().querySelectorAll('#o-que-aprovar .cita')].map((e) => compactar(e.textContent));
  assert.ok(citados.length >= 10, 'a lista cita os títulos e os botões de cada tela');
  for (const t of citados) assert.ok(permitidos.has(t), `a lista cita um texto que o aplicativo não tem: "${t}"`);
});

test('cada diálogo tem título, texto e ao menos um botão, e a prévia mostra os sete quadros de diálogo', () => {
  const d = doc();
  const dialogos = [...d.querySelectorAll('.celular .dialogo')];
  assert.equal(dialogos.length, 7, '4 da Task 6 + 3 da Task 7 (a nova versão aparece com e sem novidades: 2 quadros)');
  for (const dialogo of dialogos) {
    const titulo = dialogo.querySelector('.dialogo-titulo');
    assert.ok(titulo && compactar(titulo.textContent) !== '', 'diálogo sem título');
    assert.ok(dialogo.querySelector('.dialogo-texto'), `"${titulo.textContent}" sem texto`);
    assert.ok(dialogo.querySelectorAll('.dialogo-botao').length >= 1, `"${titulo.textContent}" sem botão`);
  }
  assert.equal(d.querySelectorAll('.celular .toast').length, 1, 'o toast do SHA errado');
});

test('a tela de erro é o www/erro.html de verdade, num iframe que não roda o script dele', () => {
  const quadro = doc().getElementById('erro-html');
  assert.ok(quadro, 'iframe#erro-html');
  assert.equal(quadro.tagName, 'IFRAME');
  assert.equal(quadro.getAttribute('srcdoc'), ERRO_HTML,
    'o srcdoc deve ser o www/erro.html inteiro (copie o arquivo, trocando " por &quot;)');
  // Sem allow-scripts: o "Tentar de novo" do erro.html leva ao site de verdade, e a prévia não pode sair navegando.
  assert.equal(quadro.getAttribute('sandbox'), '', 'iframe sem permissão nenhuma');
});

test('a camada do Fracttal é exatamente a que o inicio.js monta com o HTML de exemplo da prévia', () => {
  const camada = doc().getElementById('gc-casca-aviso');
  assert.ok(camada, 'div#gc-casca-aviso');
  const exemplo = camada.querySelector('iframe').getAttribute('srcdoc');
  assert.match(exemplo, /Sua sessão da Microsoft expirou/, 'o exemplo é o da sessão que expirou');

  const j = criarJanela();
  instalar(j.win, j.canal, j.retrato);
  const aba = j.win.open('', '_blank');
  aba.document.write(exemplo);
  j.avancar(1000);
  const real = j.win.document.getElementById('gc-casca-aviso');
  assert.ok(real, 'o inicio.js mostrou a camada');

  assert.equal(camada.outerHTML, real.outerHTML,
    'estilo, atributos e botão da prévia devem ser os do inicio.js (montarCamada): é o que vai ao aparelho');
});

test('a página é autocontida, em pt-BR, sem emoji e com tema escuro', () => {
  const d = doc();
  assert.equal(d.documentElement.getAttribute('lang'), 'pt-BR');
  assert.ok(d.querySelector('meta[name="viewport"]'), 'viewport para o celular');
  assert.equal(d.querySelectorAll('script').length, 0, 'sem script: nada para quebrar e nada de CDN');
  for (const el of d.querySelectorAll('[src], link, [href]:not([href^="#"])')) {
    assert.fail('recurso externo ou link na prévia: ' + el.outerHTML.slice(0, 80));
  }
  assert.ok(!/@import|url\(/.test(d.querySelector('style').textContent), 'sem fonte nem imagem externa');
  assert.match(d.querySelector('style').textContent, /@media \(prefers-color-scheme: dark\)/);
  assert.ok(!/\p{Extended_Pictographic}/u.test(HTML), 'sem emoji');
  assert.ok(d.querySelector('#o-que-aprovar'), 'a lista "O que aprovar" no topo');
});
