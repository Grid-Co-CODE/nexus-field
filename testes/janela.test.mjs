// Prova o `window.open` do script de início (spec, seção 6.3, itens 1 e 2). O que se protege aqui: o login da Microsoft
// cai no loginRedirect (que o campo.html já trata) em vez de abrir a própria janela do App, e o "Conectar conta
// Fracttal" nunca apaga a tela do App com o document.write: o endereço do Fracttal abre fora, e a explicação de erro que
// a página escreve aparece numa camada por cima do App, sem mudar o texto.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { criarJanela, ENDERECO_APP } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar } = require('../www/casca/inicio.js');

function montar(opcoes = {}) {
  const j = criarJanela(opcoes);
  const casca = instalar(j.win, j.canal, j.retrato);
  return { ...j, casca };
}

const camadaDe = (win) => win.document.getElementById('gc-casca-aviso');
const OAUTH = 'https://app.fracttal.com/oauth/authorize?client_id=x&state=y';

// O `naAba` do conectarFracttal do campo.html: abre o documento, escreve a explicação e fecha.
function naAba(aba, titulo, html) {
  aba.document.open();
  aba.document.write('<meta charset="utf-8"><title>Conectar Fracttal</title><h2>' + titulo + '</h2>' + html);
  aba.document.close();
}

test('window.open de login devolve null', () => {
  const { win, enviados, avancar } = montar();
  assert.equal(win.open('about:blank', 'msal.abc'), null);
  // o nome do popup do MSAL é msal.<clientId>.<escopos>...; o nome manda, qualquer que seja o endereço
  assert.equal(win.open('', 'msal'), null);
  assert.equal(win.open('https://www.gridco.com.br/', 'msal.123.User.Read'), null);
  for (const url of [
    'https://login.microsoftonline.com/x/oauth2/v2.0/authorize?client_id=1',
    'https://login.live.com/oauth20_authorize.srf',
    'https://LOGIN.WINDOWS.NET/common',
  ]) assert.equal(win.open(url, '_blank'), null, url);
  avancar(5000);
  assert.deepEqual(enviados, []);

  // host parecido não é login: vai fora
  assert.notEqual(win.open('https://login.microsoftonline.com.exemplo.com/', '_blank'), null);
  assert.deepEqual(enviados, [{ t: 'fora', url: 'https://login.microsoftonline.com.exemplo.com/' }]);
});

test('Fracttal normal: escreve, navega, abre fora e não mostra camada', () => {
  const navegacoes = {
    'location.href =': (aba, url) => { aba.location.href = url; },
    'location.assign': (aba, url) => { aba.location.assign(url); },
    'location.replace': (aba, url) => { aba.location.replace(url); },
    'win.location =': (aba, url) => { aba.location = url; },
  };
  for (const [nome, navegar] of Object.entries(navegacoes)) {
    const { win, enviados, avancar, timersAtivos } = montar();
    const corpo = win.document.body.innerHTML;
    const aba = win.open('', '_blank');
    assert.ok(aba, nome);
    assert.equal(aba.closed, false);
    aba.document.write('<p style="font-family:sans-serif;padding:24px">Abrindo o Fracttal…</p>');
    assert.equal(win.document.body.innerHTML, corpo, 'a tela do App não muda: ' + nome);
    avancar(500); // o token e o /fracttal/login
    navegar(aba, OAUTH);
    assert.deepEqual(enviados, [{ t: 'fora', url: OAUTH }], nome);
    avancar(1000);
    assert.equal(camadaDe(win), null, nome);
    assert.equal(win.document.body.innerHTML, corpo, nome);
    assert.equal(timersAtivos(), 0, nome);
  }
});

test('Fracttal com erro: escreve e não navega → camada com o HTML e botão Fechar', () => {
  const { win, enviados, avancar } = montar();
  const aba = win.open('', '_blank');
  aba.document.write('<p>Abrindo o Fracttal…</p>');
  naAba(aba, 'Sua sessão da Microsoft expirou', '<p>Para reconectar o Fracttal…</p>');
  avancar(999);
  assert.equal(camadaDe(win), null, 'nada antes de 1 s');
  avancar(1);

  const camada = camadaDe(win);
  assert.ok(camada, 'a camada aparece');
  assert.match(camada.getAttribute('style'), /position:\s*fixed/);
  const quadro = camada.querySelector('iframe');
  assert.equal(quadro.getAttribute('srcdoc'),
    '<meta charset="utf-8"><title>Conectar Fracttal</title><h2>Sua sessão da Microsoft expirou</h2>' +
    '<p>Para reconectar o Fracttal…</p>', 'o HTML como a página o deixou, sem mudar o texto');
  assert.equal(quadro.getAttribute('sandbox'), '', 'sem script, sem formulário, sem navegar o App');
  const botoes = [...camada.querySelectorAll('button')];
  assert.deepEqual(botoes.map((b) => b.textContent), ['Fechar']);
  // estilo só inline: nenhum <style> ou <link> novo, e nenhuma classe que dependa do CSS da página
  assert.equal(win.document.querySelectorAll('style, link').length, 0);
  assert.equal(camada.querySelectorAll('[class]').length + (camada.hasAttribute('class') ? 1 : 0), 0);
  assert.deepEqual(enviados, []);

  botoes[0].click();
  assert.equal(camadaDe(win), null, 'Fechar tira a camada');
  avancar(5000);
  assert.equal(camadaDe(win), null);
  assert.deepEqual(enviados, []);
});

test('a explicação que chega depois de 1 s troca o que a camada mostra, e a navegação tardia tira a camada', () => {
  // A rede lenta: o "Abrindo o Fracttal…" já está na camada quando a página escreve o erro, ou quando o endereço chega.
  const { win, enviados, avancar } = montar();
  const aba = win.open('', '_blank');
  aba.document.write('<p>Abrindo o Fracttal…</p>');
  avancar(1000);
  assert.equal(camadaDe(win).querySelector('iframe').getAttribute('srcdoc'), '<p>Abrindo o Fracttal…</p>');

  naAba(aba, 'Não consegui falar com o servidor', '<p>Verifique a internet.</p>');
  const camadas = win.document.querySelectorAll('#gc-casca-aviso');
  assert.equal(camadas.length, 1, 'uma camada só');
  assert.match(camadas[0].querySelector('iframe').getAttribute('srcdoc'), /Não consegui falar com o servidor/);

  aba.location.href = OAUTH;
  assert.equal(camadaDe(win), null, 'foi para fora: a camada sai');
  assert.deepEqual(enviados, [{ t: 'fora', url: OAUTH }]);

  // Fechar e depois a explicação: a camada volta com ela (é ela que diz ao técnico o que fazer).
  const outra = montar();
  const aba2 = outra.win.open('', '_blank');
  aba2.document.write('<p>Abrindo o Fracttal…</p>');
  outra.avancar(1000);
  camadaDe(outra.win).querySelector('button').click();
  naAba(aba2, 'Sua sessão da Microsoft expirou', '<p>passos</p>');
  assert.match(camadaDe(outra.win).querySelector('iframe').getAttribute('srcdoc'), /expirou/);
});

test('document.open, write, writeln e close acumulam o HTML como o navegador', () => {
  const { win, avancar } = montar();
  const aba = win.open('', '_blank');
  aba.document.write('<p>a', 'b</p>');
  aba.document.writeln('<p>c</p>');
  avancar(1000);
  assert.equal(camadaDe(win).querySelector('iframe').getAttribute('srcdoc'), '<p>ab</p><p>c</p>\n');

  // depois do close, um write sem open recomeça o documento (o navegador chama o open sozinho)
  aba.document.close();
  aba.document.write('<p>novo</p>');
  assert.equal(camadaDe(win).querySelector('iframe').getAttribute('srcdoc'), '<p>novo</p>');
  assert.equal(aba.document.open(), aba.document, 'o open devolve o próprio documento');
});

test('sem HTML, ou com a janela fechada pela página, não há camada', () => {
  const { win, avancar } = montar();
  win.open('', '_blank');
  avancar(2000);
  assert.equal(camadaDe(win), null);

  const aba = win.open('', '_blank');
  aba.document.write('<p>Abrindo…</p>');
  aba.close();
  assert.equal(aba.closed, true);
  avancar(2000);
  assert.equal(camadaDe(win), null);
});

test('window.open com url de fora manda fora na hora', () => {
  const { win, enviados, avancar } = montar();
  const aba = win.open('https://www.gridco.com.br/manual', '_blank');
  assert.ok(aba);
  assert.deepEqual(enviados, [{ t: 'fora', url: 'https://www.gridco.com.br/manual' }]);
  aba.document.write('<p>qualquer coisa</p>');
  avancar(2000);
  assert.equal(camadaDe(win), null, 'já navegou');

  // endereço relativo vira absoluto, pela página do App
  enviados.length = 0;
  win.open('/api/manual.pdf');
  assert.deepEqual(enviados, [{ t: 'fora', url: new URL('/api/manual.pdf', ENDERECO_APP).href }]);

  // só http e https vão ao nativo (ele os abre numa Custom Tab); o aviso não leva o endereço
  enviados.length = 0;
  const estranha = win.open('javascript:alert(1)', '_blank');
  assert.ok(estranha);
  assert.equal(enviados.length, 1);
  assert.equal(enviados[0].t, 'erro');
  assert.ok(!/alert/.test(enviados[0].msg));
});

test('o window.open nunca quebra a página', () => {
  const { win, canal, avancar } = montar();
  canal.postMessage = () => { throw new Error('canal caiu'); };
  let aba;
  assert.doesNotThrow(() => { aba = win.open('', '_blank'); });
  assert.doesNotThrow(() => { aba.document.write('<p>x</p>'); aba.location.href = OAUTH; });
  assert.doesNotThrow(() => avancar(2000));

  // sem document.body (a carga ainda no começo): a camada vai para o documentElement
  const outra = montar();
  outra.win.document.body.remove();
  const aba2 = outra.win.open('', '_blank');
  aba2.document.write('<p>erro</p>');
  assert.doesNotThrow(() => outra.avancar(1000));
  assert.ok(camadaDe(outra.win));
});
