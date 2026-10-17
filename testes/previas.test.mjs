// Prova que a prévia das telas novas (docs/previas/telas.html) mostra o que o APK vai mostrar. O Levi aprova os textos
// e o visual por ela; se a prévia e o aparelho divergirem, a aprovação vale para uma tela que não existe. Por isso a
// prévia não é conferida "por olho": ela é lida contra as fontes do que vai ao aparelho.
//   1. cada diálogo da prévia é um grupo do strings.xml (título, texto, botões no conjunto e na ordem em que a
//      MainActivity, o CascaPlugin e o Atualizador os montam), e o toast é o do strings.xml;
//   2. a tela de erro é o www/erro.html, byte a byte (a prévia o embute num iframe);
//   3. a camada do Fracttal é a que o www/casca/inicio.js monta de verdade, com o HTML que o campo.html escreve na aba.
// Quando o Levi pedir outro texto, o arquivo-fonte muda (strings.xml, erro.html ou inicio.js) e a prévia acompanha; este
// teste falha enquanto uma das duas estiver para trás.
//
// A página é publicada como Artifact (página privada do claude.ai), que a embrulha: por isso o arquivo não tem
// doctype, html, head nem body, e o contrato da página (título, tokens de cor nos dois temas, gutter com a área
// segura) também é conferido aqui.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { JSDOM } from 'jsdom';
import { criarJanela } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar } = require('../www/casca/inicio.js');

const lerArquivo = (caminho) => readFileSync(new URL(caminho, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const PASTA_JAVA = '../android/app/src/main/java/br/com/gridco/campo/';
const HTML = lerArquivo('../docs/previas/telas.html');
const STRINGS = lerArquivo('../android/app/src/main/res/values/strings.xml');
const ERRO_HTML = lerArquivo('../www/erro.html');

const doc = () => new JSDOM(HTML).window.document;
const compactar = (s) => s.replace(/\s+/g, ' ').trim();

// Os `casca_*` do strings.xml, já como o Android os entrega ao diálogo: entidades do XML e `\n` resolvidos. `texto` vem
// compactado (para comparar com o que a tela mostra); `bruto` guarda as quebras de linha (as novidades são uma por linha).
function stringsDaCasca() {
  const lidas = [];
  for (const [, nome, original] of STRINGS.matchAll(/<string name="(casca_[a-z_]+)">([\s\S]*?)<\/string>/g)) {
    const bruto = original
      .replace(/\\n/g, '\n').replace(/\\'/g, "'")
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
    lidas.push({ nome, bruto, texto: compactar(bruto) });
  }
  return lidas;
}
const textoDe = (nome) => {
  const achada = stringsDaCasca().find((s) => s.nome === nome);
  assert.ok(achada, `${nome} não existe no strings.xml`);
  return achada.texto;
};

// O `%1$s` do strings.xml vira "qualquer coisa": a versão (%1) é uma palavra só; a lista de novidades (%2) pode ter várias.
function regexDe(texto) {
  const fonte = texto.split(/(%\d\$s)/).map((parte) => {
    const marca = parte.match(/^%(\d)\$s$/);
    if (!marca) return parte.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return marca[1] === '1' ? '(\\S+)' : '(.+)';
  }).join('');
  return new RegExp('^' + fonte + '$');
}

// Cada diálogo da casca, pelo título. `botoes` na ordem em que aparecem da esquerda para a direita: o negativo
// (setNegativeButton) à esquerda e o positivo (setPositiveButton) à direita, como o AlertDialog do AppCompat os põe.
// `textos` tem mais de um quando o aplicativo escolhe entre dois (a Release com e sem novidades).
const DIALOGOS = [
  { titulo: 'casca_permissoes_titulo', textos: ['casca_permissoes_texto'], botoes: ['casca_permissoes_continuar'] },
  { titulo: 'casca_aproximada_titulo', textos: ['casca_aproximada_texto'], botoes: ['casca_agora_nao', 'casca_abrir_ajustes'] },
  { titulo: 'casca_negadas_titulo', textos: ['casca_negadas_texto'], botoes: ['casca_agora_nao', 'casca_abrir_ajustes'] },
  { titulo: 'casca_webview_titulo', textos: ['casca_webview_texto'], botoes: ['casca_webview_abrir_loja'] },
  { titulo: 'casca_atualizacao_titulo', textos: ['casca_atualizacao_texto', 'casca_atualizacao_texto_simples'],
    botoes: ['casca_atualizacao_depois', 'casca_atualizacao_atualizar'] },
  { titulo: 'casca_instalar_titulo', textos: ['casca_instalar_texto'], botoes: ['casca_abrir_ajustes'] },
];
const TOAST = 'casca_atualizacao_sha_toast';

// Os botões de cada AlertDialog.Builder do código Java, "negativo>positivo" (ou só o positivo): é como o aplicativo os
// monta de verdade. Vai até o primeiro .show() ou .create() da cadeia (as lambdas dos botões não têm nenhum dos dois).
function paresDeBotoesDoJava() {
  const pares = [];
  for (const arquivo of readdirSync(new URL(PASTA_JAVA, import.meta.url)).filter((n) => n.endsWith('.java'))) {
    const fonte = lerArquivo(PASTA_JAVA + arquivo);
    for (const m of fonte.matchAll(/new AlertDialog\.Builder\(/g)) {
      const resto = fonte.slice(m.index);
      const fim = resto.search(/\.(show|create)\(\)/);
      assert.ok(fim > 0, `${arquivo}: não achei o fim da cadeia do AlertDialog.Builder`);
      const cadeia = resto.slice(0, fim);
      const pos = cadeia.match(/\.setPositiveButton\(R\.string\.(\w+)/);
      const neg = cadeia.match(/\.setNegativeButton\(R\.string\.(\w+)/);
      assert.ok(pos, `${arquivo}: diálogo sem botão positivo`);
      pares.push([neg && neg[1], pos[1]].filter(Boolean).join('>'));
    }
  }
  return pares;
}

// Lê a folha de estilo da página sem depender do CSSOM do jsdom (que descarta o que não entende): regra a regra, com o
// contexto do @media e as declarações num objeto.
function lerFolha(css) {
  const limpo = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const regras = [];
  const declaracoes = (corpo) => {
    const decl = {};
    for (const par of corpo.split(';')) {
      const i = par.indexOf(':');
      if (i > 0) decl[par.slice(0, i).trim()] = par.slice(i + 1).trim().replace(/\s+/g, ' ');
    }
    return decl;
  };
  const bloco = (texto, contexto) => {
    let i = 0;
    while (i < texto.length) {
      const abre = texto.indexOf('{', i);
      if (abre < 0) break;
      let prof = 1;
      let j = abre + 1;
      while (j < texto.length && prof > 0) {
        if (texto[j] === '{') prof++;
        else if (texto[j] === '}') prof--;
        j++;
      }
      const cabeca = texto.slice(i, abre).trim().replace(/\s+/g, ' ');
      const corpo = texto.slice(abre + 1, j - 1);
      if (cabeca.startsWith('@')) bloco(corpo, cabeca);
      else regras.push({ contexto, seletor: cabeca, decl: declaracoes(corpo) });
      i = j;
    }
  };
  bloco(limpo, null);
  return regras;
}
const folha = () => lerFolha(doc().querySelector('style').textContent);
const regraDe = (f, seletor, contexto = null) => {
  const r = f.find((x) => x.seletor === seletor && x.contexto === contexto);
  assert.ok(r, `falta a regra ${contexto ? contexto + ' { ' : ''}${seletor}`);
  return r;
};

test('a prévia existe e lê o strings.xml: há strings da casca para conferir', () => {
  assert.ok(stringsDaCasca().length >= 15, 'o strings.xml deve trazer os textos dos diálogos da casca');
});

test('cada quadro de diálogo é um grupo do strings.xml: título, texto e os botões no conjunto e na ordem do aplicativo', () => {
  const d = doc();
  const quadros = [...d.querySelectorAll('.celular .dialogo')];
  const vistos = new Set();

  for (const quadro of quadros) {
    const titulo = compactar(quadro.querySelector('.dialogo-titulo').textContent);
    const grupo = DIALOGOS.find((g) => textoDe(g.titulo) === titulo);
    assert.ok(grupo, `título sem grupo no strings.xml: "${titulo}"`);

    const texto = compactar(quadro.querySelector('.dialogo-texto').textContent);
    const variante = grupo.textos.find((nome) => regexDe(textoDe(nome)).test(texto));
    assert.ok(variante, `o texto do diálogo "${titulo}" não é nenhum de ${grupo.textos.join(' / ')}: "${texto}"`);
    assert.ok(!vistos.has(variante), `o texto ${variante} aparece em dois quadros`);
    vistos.add(variante);

    const botoes = [...quadro.querySelectorAll('.dialogo-botao')].map((b) => compactar(b.textContent));
    assert.deepEqual(botoes, grupo.botoes.map(textoDe),
      `botões de "${titulo}": o conjunto e a ordem são negativo à esquerda e positivo à direita`);
  }

  for (const grupo of DIALOGOS) {
    for (const nome of grupo.textos) assert.ok(vistos.has(nome), `${nome} não aparece em nenhum quadro da prévia`);
  }

  const toasts = [...d.querySelectorAll('.celular .toast')].map((t) => compactar(t.textContent));
  assert.deepEqual(toasts, [textoDe(TOAST)], 'o toast da prévia é o do strings.xml');
});

test('nenhuma string da casca fica sem quadro, e a tabela do teste não cita string que não existe', () => {
  const citadas = new Set([TOAST, ...DIALOGOS.flatMap((g) => [g.titulo, ...g.textos, ...g.botoes])]);
  const existentes = new Set(stringsDaCasca().map((s) => s.nome));
  for (const nome of existentes) assert.ok(citadas.has(nome), `${nome} está no strings.xml e não tem quadro na prévia`);
  for (const nome of citadas) assert.ok(existentes.has(nome), `${nome} está na tabela do teste e não existe no strings.xml`);
});

test('os botões de cada quadro são os que o código Java monta, e todo diálogo do Java tem quadro', () => {
  const doJava = paresDeBotoesDoJava();
  assert.ok(doJava.length >= 5, 'o teste achou os diálogos da MainActivity, do CascaPlugin e do Atualizador');
  const daPrevia = DIALOGOS.map((g) => g.botoes.join('>'));
  for (const par of daPrevia) assert.ok(doJava.includes(par), `a prévia mostra os botões "${par}" e o Java não os monta assim`);
  for (const par of doJava) assert.ok(daPrevia.includes(par), `o Java monta um diálogo com os botões "${par}" que a prévia não mostra`);
});

test('as novidades vêm como o Atualizador as monta: a versão, "Novidades:" e uma linha "- item" por novidade', () => {
  const d = doc();
  const quadro = [...d.querySelectorAll('.celular .dialogo-texto')].find((t) => /Novidades:/.test(t.textContent));
  assert.ok(quadro, 'o quadro da versão nova com novidades');
  const bruto = quadro.textContent;
  const [primeira, ...linhas] = bruto.split('\n');
  const versao = primeira.match(/^Versão (\S+)\. Novidades:$/);
  assert.ok(versao, `a primeira linha é a versão: "${primeira}"`);
  assert.ok(linhas.length >= 2, 'o exemplo mostra mais de uma novidade');
  for (const linha of linhas) assert.match(linha, /^- \S/, `cada novidade é "- item": "${linha}"`);

  const modelo = stringsDaCasca().find((s) => s.nome === 'casca_atualizacao_texto').bruto;
  const esperado = modelo.replace('%1$s', () => versao[1]).replace('%2$s', () => linhas.join('\n'));
  assert.equal(bruto, esperado, 'o texto é o casca_atualizacao_texto preenchido como o Atualizador faz');

  // O pre-line é o que faz cada "\n" virar uma linha na tela: sem ele as novidades se juntariam num parágrafo só.
  assert.match(regraDe(folha(), '.dialogo-texto').decl['white-space'], /^pre-line$/);
});

test('os diálogos e o toast estáticos têm nome acessível, e os ids não se repetem', () => {
  const d = doc();
  const dialogos = [...d.querySelectorAll('.celular .dialogo')];
  assert.equal(d.querySelectorAll('.celular [role="alertdialog"]').length, dialogos.length, 'todo diálogo é um alertdialog');
  for (const dialogo of dialogos) {
    const titulo = dialogo.querySelector('.dialogo-titulo');
    assert.equal(dialogo.getAttribute('role'), 'alertdialog');
    assert.ok(titulo.id && dialogo.getAttribute('aria-labelledby') === titulo.id,
      `"${titulo.textContent}": o aria-labelledby deve apontar para o título do próprio diálogo`);
    const texto = dialogo.querySelector('.dialogo-texto');
    assert.ok(texto.id && dialogo.getAttribute('aria-describedby') === texto.id,
      `"${titulo.textContent}": o aria-describedby deve apontar para o texto do próprio diálogo`);
  }
  for (const toast of d.querySelectorAll('.celular .toast')) {
    assert.equal(toast.getAttribute('role'), 'status');
    const nome = d.getElementById(toast.getAttribute('aria-labelledby') || '');
    assert.ok(nome && compactar(nome.textContent) !== '', 'o toast tem nome acessível (aria-labelledby para um texto)');
  }
  const ids = [...d.querySelectorAll('[id]')].map((e) => e.id);
  assert.deepEqual(ids.filter((id, i) => ids.indexOf(id) !== i), [], 'id repetido na página');
});

test('os links "Ver" da lista têm alvo de toque de pelo menos 44 px', () => {
  const ver = regraDe(folha(), '.aprovar .ver');
  assert.match(ver.decl.display, /flex|block/, 'o min-height só vale em elemento que não é inline');
  assert.ok(parseFloat(ver.decl['min-height']) >= 44, `min-height: ${ver.decl['min-height']}`);
  assert.ok(parseFloat(ver.decl['min-width']) >= 44, `min-width: ${ver.decl['min-width']}`);
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

test('a tela de erro é o www/erro.html de verdade, num iframe que não roda o script dele', () => {
  const quadro = doc().getElementById('erro-html');
  assert.ok(quadro, 'iframe#erro-html');
  assert.equal(quadro.tagName, 'IFRAME');
  assert.equal(quadro.getAttribute('srcdoc'), ERRO_HTML,
    'o srcdoc deve ser o www/erro.html inteiro (copie o arquivo, trocando & por &amp; e " por &quot;)');
  // Sem allow-scripts: o "Tentar de novo" do erro.html leva ao site de verdade, e a prévia não pode sair navegando.
  assert.equal(quadro.getAttribute('sandbox'), '', 'iframe sem permissão nenhuma');
});

test('a camada do Fracttal é a que o inicio.js monta com o HTML que o campo.html escreve, menos o aria-modal da prévia', () => {
  const camada = doc().getElementById('gc-casca-aviso');
  assert.ok(camada, 'div#gc-casca-aviso');
  const exemplo = camada.querySelector('iframe').getAttribute('srcdoc');

  // O texto é o que o campo.html escreve na aba quando a sessão da Microsoft expirou (conectarFracttal/naAba), não um
  // exemplo inventado: o cabeçalho da aba, o título, as duas explicações e a despedida.
  assert.match(exemplo, /^<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Conectar Fracttal<\/title><div /);
  for (const trecho of ['<h2 style="font-size:19px;margin:0 0 12px;line-height:1.3">Sua sessão da Microsoft expirou</h2>',
    'Para reconectar o Fracttal, o app precisa primeiro que você <b>entre de novo com a conta Microsoft</b>.',
    '<b>O que fazer:</b><br>1. Volte para o app<br>2. Toque no seu nome, no topo<br>3. Toque em <b>Sair</b><br>',
    '5. Aí sim, toque em Conectar conta Fracttal</p>', 'Pode fechar esta aba.</p></div>']) {
    assert.ok(exemplo.includes(trecho), `o exemplo deveria trazer o trecho do campo.html: ${trecho}`);
  }
  assert.ok(!exemplo.includes('Entre de novo com a sua conta'), 'o exemplo inventado não volta');

  // A legenda diz de onde o texto veio e de qual versão do App.
  const legenda = doc().querySelector('#t-fracttal figcaption').textContent;
  assert.match(legenda, /campo\.html/);
  assert.match(legenda, /conectarFracttal/);
  assert.match(legenda, /\bv\d{3}\b/, 'a legenda diz de qual versão do App é o texto');

  const j = criarJanela();
  instalar(j.win, j.canal, j.retrato);
  const aba = j.win.open('', '_blank');
  aba.document.write(exemplo);
  j.avancar(1000);
  const real = j.win.document.getElementById('gc-casca-aviso');
  assert.ok(real, 'o inicio.js mostrou a camada');
  assert.equal(real.getAttribute('aria-modal'), 'true', 'no aparelho a camada é modal');

  // Na página estática o aria-modal ativo esconderia o resto da página da acessibilidade: a prévia usa "false", e é a
  // única diferença aceita (explicada num comentário ao lado da camada).
  assert.equal(camada.getAttribute('aria-modal'), 'false');
  assert.ok((HTML.match(/<!--[\s\S]*?-->/g) || []).some((c) => /aria-modal/.test(c)),
    'um comentário ao lado da camada explica por que a prévia usa aria-modal="false"');
  const igualadaAoAparelho = camada.cloneNode(true);
  igualadaAoAparelho.setAttribute('aria-modal', 'true');
  assert.equal(igualadaAoAparelho.outerHTML, real.outerHTML,
    'estilo, atributos e botão da prévia devem ser os do inicio.js (montarCamada): é o que vai ao aparelho');
});

test('só o iframe do erro.html é dimensionado pela folha da página: o da camada fica com o estilo inline do inicio.js', () => {
  const d = doc();
  const quadroDaCamada = d.querySelector('#gc-casca-aviso iframe');
  assert.ok(quadroDaCamada, 'iframe da camada');
  const f = folha();
  for (const regra of f) {
    if (regra.seletor === '*') {
      assert.deepEqual(Object.keys(regra.decl), ['box-sizing'], 'a regra universal só põe o box-sizing');
      continue;
    }
    let alcanca = false;
    try { alcanca = quadroDaCamada.matches(regra.seletor); } catch { /* seletor que o jsdom não entende não alcança */ }
    assert.ok(!alcanca, `a regra "${regra.seletor}" alcança o iframe da camada e muda o que o inicio.js declarou`);
  }
  const erro = regraDe(f, '#erro-html');
  assert.equal(erro.decl.height, '100%');
  assert.equal(erro.decl.width, '100%');
  assert.equal(erro.decl.position, 'absolute');
});

test('contrato da página do Artifact: sem doctype, html, head nem body; abre com title, meta e style; lang no envoltório', () => {
  const d = doc();
  // O publicador embrulha a página; o srcdoc do erro.html tem o doctype dele, por isso sai da conta.
  const semSrcdoc = HTML.replace(/srcdoc="[^"]*"/g, 'srcdoc=""');
  assert.ok(!/<!doctype|<\/?(html|head|body)[\s>]/i.test(semSrcdoc), 'a página não tem doctype, html, head nem body próprios');
  assert.match(HTML, /^\s*<title>Telas novas do App de Campo<\/title>\s*<meta name="claude:viewport-fit-sides" content="cover">\s*<style>/);
  assert.equal(d.title, 'Telas novas do App de Campo');
  assert.equal(d.querySelector('meta[name="claude:viewport-fit-sides"]').getAttribute('content'), 'cover');

  const pagina = d.querySelector('.pagina');
  assert.ok(pagina, 'o envoltório externo .pagina');
  assert.deepEqual([...d.body.children], [pagina], 'o envoltório é a única coisa dentro do body');
  assert.equal(pagina.getAttribute('lang'), 'pt-BR');
});

test('o gutter lateral fica no envoltório, com a área segura do aparelho; o body não leva padding', () => {
  const f = folha();
  const pagina = regraDe(f, '.pagina');
  assert.equal(pagina.decl['padding-left'], 'max(16px, env(safe-area-inset-left, 0px))');
  assert.equal(pagina.decl['padding-right'], 'max(16px, env(safe-area-inset-right, 0px))');
  assert.ok(pagina.decl['padding-block'], 'o vertical vai por padding-block');
  const body = regraDe(f, 'body');
  for (const prop of Object.keys(body.decl)) assert.ok(!prop.startsWith('padding'), `o body não leva ${prop}`);
});

test('cores só por token: o :root claro define todos, o escuro redefine igual pelos dois caminhos, nada tem cor por extenso', () => {
  const f = folha();
  const claro = regraDe(f, ':root');
  const pelaMedia = regraDe(f, ':root:not([data-theme="light"])', '@media (prefers-color-scheme: dark)');
  const peloTema = regraDe(f, ':root[data-theme="dark"]');
  assert.equal(claro.decl['color-scheme'], 'light');
  assert.equal(pelaMedia.decl['color-scheme'], 'dark');
  assert.deepEqual(peloTema.decl, pelaMedia.decl, 'o escuro do @media e o do data-theme="dark" devem ser o mesmo');

  const tokens = (decl) => Object.keys(decl).filter((n) => n.startsWith('--'));
  const definidos = tokens(claro.decl);
  assert.ok(tokens(pelaMedia.decl).length >= 5, 'o escuro redefine os tokens da página');
  for (const nome of tokens(pelaMedia.decl)) assert.ok(definidos.includes(nome), `${nome} está no escuro e não no :root`);
  // O tema do aparelho (a escolha de quem confere) também só redefine tokens que o :root tem.
  const aparelho = regraDe(f, ':root:has(#ap-escuro:checked)');
  for (const nome of tokens(aparelho.decl)) assert.ok(definidos.includes(nome), `${nome} está no tema do aparelho e não no :root`);

  const css = doc().querySelector('style').textContent;
  const usados = new Set([...css.matchAll(/var\((--[a-z0-9-]+)/g)].map((m) => m[1]));
  for (const nome of usados) assert.ok(definidos.includes(nome), `${nome} é usado e não está definido no :root`);
  for (const nome of definidos) assert.ok(usados.has(nome), `${nome} está definido e ninguém usa: tire o token`);

  const body = regraDe(f, 'body');
  assert.match(body.decl.background, /^var\(--/, 'o body tem o fundo por token');
  assert.match(body.decl.color, /^var\(--/, 'o body tem a cor do texto por token');

  for (const regra of f) {
    if (regra.seletor.startsWith(':root')) continue; // aqui é que os tokens são definidos
    for (const [prop, valor] of Object.entries(regra.decl)) {
      assert.ok(!/#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(/i.test(valor), `${regra.seletor} { ${prop}: ${valor} } tem cor por extenso`);
    }
  }
});

test('a página é autocontida, sem emoji e com a lista "O que aprovar" no topo', () => {
  const d = doc();
  assert.equal(d.querySelectorAll('script').length, 0, 'sem script: nada para quebrar e nada de CDN');
  for (const el of d.querySelectorAll('[src], link, [href]:not([href^="#"])')) {
    assert.fail('recurso externo ou link na prévia: ' + el.outerHTML.slice(0, 80));
  }
  assert.ok(!/@import|url\(/.test(d.querySelector('style').textContent), 'sem fonte nem imagem externa');
  assert.ok(!/\p{Extended_Pictographic}/u.test(HTML), 'sem emoji');
  assert.ok(d.querySelector('#o-que-aprovar'), 'a lista "O que aprovar" no topo');
});
