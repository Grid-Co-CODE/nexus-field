// O aplicativo se chama Nexus Field (pedido do dono da casca, 17/10/2026); o site que ele abre continua sendo o App de
// Campo, e esse nome não muda. Este teste lê todo texto que a casca mostra a alguém (os diálogos e toasts do strings.xml,
// os da prova no build de depuração, a tela "Sem internet", a página local, as novidades de cada Release) e falha se um
// deles chamar o aplicativo de "App de Campo" ou de "o App". O nome do site só aparece onde o texto fala do site, e cada
// um desses trechos está na lista abaixo, com o motivo.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const ler = (caminho) => readFileSync(join(raiz, caminho), 'utf8');

// Os trechos que falam do SITE. Trecho que sumiu do arquivo também falha: a lista não guarda exceção morta.
const SOBRE_O_SITE = [
  { arquivo: 'android/app/src/debug/res/values/depuracao.xml', trecho: 'a página do App de Campo abre em 3 s',
    motivo: 'depois de apagar a WebView a tela recriada carrega o site, que é o que abre' },
  { arquivo: 'novidades/1.0.0.md', trecho: 'O App de Campo em tela cheia',
    motivo: 'o que o Nexus Field mostra é o site do App de Campo, em tela cheia' },
];

// Os textos de um strings.xml, um por <string>, como o Android os mostra (o XML resolvido, sem os comentários).
function stringsDe(caminho) {
  const doc = new JSDOM(ler(caminho), { contentType: 'application/xml' }).window.document;
  return [...doc.getElementsByTagName('string')].map((s) => ({ onde: `${caminho} (${s.getAttribute('name')})`, texto: s.textContent }));
}

// O que uma página HTML mostra: o título e o texto do corpo, sem script, estilo nem comentário.
function textoDaPagina(caminho) {
  const doc = new JSDOM(ler(caminho)).window.document;
  for (const el of doc.querySelectorAll('script, style')) el.remove();
  return [{ onde: `${caminho} (title)`, texto: doc.title }, { onde: `${caminho} (body)`, texto: doc.body.textContent }];
}

function textosDaCasca() {
  const novidades = readdirSync(join(raiz, 'novidades')).filter((n) => n.endsWith('.md'))
    .map((n) => ({ onde: `novidades/${n}`, texto: ler(`novidades/${n}`) }));
  return [
    ...stringsDe('android/app/src/main/res/values/strings.xml'),
    ...stringsDe('android/app/src/debug/res/values/depuracao.xml'),
    ...textoDaPagina('www/erro.html'),
    ...textoDaPagina('www/index.html'),
    ...novidades,
    { onde: 'capacitor.config.json (appName)', texto: JSON.parse(ler('capacitor.config.json')).appName },
  ];
}

test('a lista do que fala do site só tem trechos que existem', () => {
  for (const { arquivo, trecho } of SOBRE_O_SITE) {
    assert.ok(ler(arquivo).includes(trecho), `${arquivo} não tem mais "${trecho}": tire a exceção da lista`);
  }
});

test('nenhum texto da casca chama o aplicativo de "App de Campo" nem de "o App"', () => {
  const textos = textosDaCasca();
  assert.ok(textos.length >= 25, 'o teste achou os textos da casca');
  for (const { onde, texto } of textos) {
    let resto = texto;
    for (const { arquivo, trecho } of SOBRE_O_SITE) {
      if (onde.startsWith(arquivo)) resto = resto.split(trecho).join('');
    }
    assert.ok(!/app de campo/i.test(resto), `${onde}: diz "App de Campo" falando do aplicativo (é Nexus Field)`);
    // "App" sozinho e com maiúscula é o aplicativo ("o App não consegue", "abra o App"); "apps desconhecidos", não.
    assert.ok(!/\bApp\b/.test(resto), `${onde}: diz "App" falando do aplicativo (é Nexus Field)`);
  }
});

test('os textos que nomeiam o aplicativo dizem Nexus Field', () => {
  const todos = textosDaCasca().map((t) => t.texto).join('\n');
  for (const esperado of ['O Nexus Field usa a câmera', 'Nova versão do Nexus Field', 'Abra o Nexus Field uma vez com internet',
    'O Nexus Field volta quando houver internet']) {
    assert.ok(todos.includes(esperado), `falta "${esperado}"`);
  }
});
