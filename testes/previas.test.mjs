// Prova que a prévia das telas novas (docs/previas/telas.html) mostra o que o APK vai mostrar. O dono da casca aprova
// os textos e o visual por ela; se a prévia e o aparelho divergirem, a aprovação vale para uma tela que não existe. Por
// isso a prévia não é conferida "por olho": ela é lida contra as fontes do que vai ao aparelho.
//   1. cada diálogo da prévia é um grupo do strings.xml (título, texto, botões no conjunto e na ordem em que a
//      MainActivity, o CascaPlugin e o Atualizador os montam), e cada toast é um do strings.xml que o Java mostra;
//   2. a tela de erro é o www/erro.html, byte a byte (a prévia o embute num iframe);
//   3. a camada do Fracttal é a que o www/casca/inicio.js monta de verdade, com o HTML que o campo.html escreve na aba;
//   4. o ícone é o PNG que vai ao APK (o primeiro plano de xxxhdpi, byte a byte, embutido como data: URI) sobre a cor do
//      fundo do ícone, e o rótulo embaixo dele é o do strings.xml;
//   5. os nomes dos canais de notificação são os do strings.xml que o CanaisDeNotificacao dá a cada canal;
//   6. toda string do strings.xml tem lugar na prévia: um prefixo novo não passa sem quadro (e sem aprovação).
// Quando o dono da casca pedir outro texto, o arquivo-fonte muda (strings.xml, erro.html ou inicio.js) e a prévia
// acompanha; este teste falha enquanto uma das duas estiver para trás.
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

// As strings de um prefixo do strings.xml, já como o Android as entrega à tela: entidades do XML e `\n` resolvidos.
// `texto` vem compactado (para comparar com o que a tela mostra); `bruto` guarda as quebras de linha (as novidades são
// uma por linha). Os `casca_*` são os diálogos e os toasts; os `notificacao_canal_*`, os nomes dos canais.
function stringsDe(prefixo) {
  const lidas = [];
  for (const [, nome, original] of STRINGS.matchAll(new RegExp(`<string name="(${prefixo}[a-z_]+)">([\\s\\S]*?)</string>`, 'g'))) {
    const bruto = original
      .replace(/\\n/g, '\n').replace(/\\'/g, "'")
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
    lidas.push({ nome, bruto, texto: compactar(bruto) });
  }
  return lidas;
}
const stringsDaCasca = () => stringsDe('casca_');
const stringsDosCanais = () => stringsDe('notificacao_canal_');
// Só o nome no parâmetro: é usado como `.map(textoDe)`, que passaria o índice num segundo parâmetro.
const textoDe = (nome) => {
  const achada = [...stringsDaCasca(), ...stringsDosCanais()].find((s) => s.nome === nome);
  assert.ok(achada, `${nome} não existe no strings.xml`);
  return achada.texto;
};
// Uma string do aplicativo fora das da casca (o nome: app_name, title_activity_main).
const stringDoApp = (nome) => {
  const achada = STRINGS.match(new RegExp(`<string name="${nome}">([^<]*)</string>`));
  assert.ok(achada, `${nome} não existe no strings.xml`);
  return achada[1];
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
  // Quem já tinha o aplicativo e atualizou (Android 13+): o motivo da notificação, antes de o Android pedi-la.
  { titulo: 'casca_avisos_titulo', textos: ['casca_avisos_texto'], botoes: ['casca_avisos_continuar'] },
  { titulo: 'casca_aproximada_titulo', textos: ['casca_aproximada_texto'], botoes: ['casca_agora_nao', 'casca_abrir_ajustes'] },
  { titulo: 'casca_negadas_titulo', textos: ['casca_negadas_texto'], botoes: ['casca_agora_nao', 'casca_abrir_ajustes'] },
  { titulo: 'casca_webview_titulo', textos: ['casca_webview_texto'], botoes: ['casca_webview_abrir_loja'] },
  { titulo: 'casca_atualizacao_titulo', textos: ['casca_atualizacao_texto', 'casca_atualizacao_texto_simples'],
    botoes: ['casca_atualizacao_depois', 'casca_atualizacao_atualizar'] },
  { titulo: 'casca_instalar_titulo', textos: ['casca_instalar_texto'], botoes: ['casca_abrir_ajustes'] },
];
// Os toasts, na ordem da prévia: o do download que começou e o da atualização que não deu certo.
const TOASTS = ['casca_atualizacao_baixando', 'casca_atualizacao_sha_toast'];

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

// As strings que o Java mostra num Toast.makeText(..., R.string.x, ...): o toast que o aplicativo mostra de verdade.
function toastsDoJava() {
  const nomes = [];
  for (const arquivo of readdirSync(new URL(PASTA_JAVA, import.meta.url)).filter((n) => n.endsWith('.java'))) {
    const fonte = lerArquivo(PASTA_JAVA + arquivo);
    for (const m of fonte.matchAll(/Toast\.makeText\([^;]*?R\.string\.(\w+)/g)) nomes.push(m[1]);
  }
  return nomes;
}

// Os nomes que o CanaisDeNotificacao dá aos canais (o setName de cada NotificationChannelCompat.Builder): é o que o
// técnico lê nos ajustes do Android.
function canaisDoJava() {
  const fonte = lerArquivo(PASTA_JAVA + 'CanaisDeNotificacao.java');
  return [...fonte.matchAll(/\.setName\([^;]*?R\.string\.(\w+)/g)].map((m) => m[1]);
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
  assert.deepEqual(toasts, TOASTS.map(textoDe), 'os toasts da prévia são os do strings.xml, na ordem');
});

test('cada toast da prévia é um que o Java mostra, e todo toast do Java tem quadro', () => {
  const doJava = toastsDoJava();
  assert.ok(doJava.length >= 2, 'o teste achou os toasts do Atualizador');
  assert.deepEqual([...new Set(doJava)].sort(), [...TOASTS].sort());
});

test('nenhuma string da casca fica sem quadro, e a tabela do teste não cita string que não existe', () => {
  const citadas = new Set([...TOASTS, ...DIALOGOS.flatMap((g) => [g.titulo, ...g.textos, ...g.botoes])]);
  const existentes = new Set(stringsDaCasca().map((s) => s.nome));
  for (const nome of existentes) assert.ok(citadas.has(nome), `${nome} está no strings.xml e não tem quadro na prévia`);
  for (const nome of citadas) assert.ok(existentes.has(nome), `${nome} está na tabela do teste e não existe no strings.xml`);
});

test('os nomes dos canais de notificação estão no quadro dos ajustes: os que o CanaisDeNotificacao cria, e só eles', () => {
  const doJava = canaisDoJava();
  assert.equal(doJava.length, 3, 'o teste achou os três canais do CanaisDeNotificacao');
  assert.deepEqual(stringsDosCanais().map((s) => s.nome).sort(), [...doJava].sort(),
    'todo notificacao_canal_* do strings.xml é o nome de um canal, e todo canal tira o nome de um deles');

  const quadro = doc().querySelector('#t-canais .celular.ajustes');
  assert.ok(quadro, 'o quadro dos ajustes de notificação (figure#t-canais)');
  const naPrevia = [...quadro.querySelectorAll('.canal-nome')].map((e) => compactar(e.textContent));
  assert.deepEqual([...naPrevia].sort(), doJava.map(textoDe).sort(),
    'os nomes no quadro são os do strings.xml, um por canal (a ordem fica com o Android)');
});

test('toda string do strings.xml tem lugar na prévia: um prefixo novo não escapa da aprovação', () => {
  // Os casca_* têm quadro de diálogo ou de toast, os notificacao_canal_* o quadro dos ajustes e o nome (app_name e
  // title_activity_main) o rótulo do ícone e o título da página; o package_name e o custom_url_scheme são o
  // applicationId, que o técnico não lê. Fora disso é texto novo sem quadro: ganha o quadro e o teste dele na prévia
  // antes de ir ao campo, ou entra aqui com o motivo de não aparecer ao técnico. Caso que criou a regra: os nomes dos
  // canais (18/10/2026) saíram do prefixo casca_ e ficaram fora da prévia sem nenhum teste reclamar.
  const COM_QUADRO = [/^casca_[a-z_]+$/, /^notificacao_canal_[a-z_]+$/, /^(app_name|title_activity_main)$/];
  const SEM_TELA = ['package_name', 'custom_url_scheme'];
  const nomes = [...STRINGS.matchAll(/<string\b[^>]*\bname="([^"]*)"/g)].map((m) => m[1]);
  assert.ok(nomes.length >= 20, 'o teste leu as strings do strings.xml');
  for (const nome of nomes) {
    assert.ok(SEM_TELA.includes(nome) || COM_QUADRO.some((r) => r.test(nome)),
      `${nome} está no strings.xml e não tem lugar na prévia`);
  }
  // O que os leitores de cima não leem: um atributo antes do name (translatable, formatted) ou um plurals/string-array.
  for (const [inteira] of STRINGS.matchAll(/<string\b[^>]*>/g)) {
    assert.match(inteira, /^<string name="[^"]*">$/, `string com outro formato, que a prévia não lê: ${inteira}`);
  }
  assert.ok(!/<(plurals|string-array)\b/.test(STRINGS), 'plurals e string-array não têm quadro na prévia');
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
  const permitidos = new Set([...stringsDaCasca(), ...stringsDosCanais()].map((s) => s.texto));
  for (const nome of ['app_name', 'title_activity_main']) permitidos.add(stringDoApp(nome)); // o nome, Nexus Field
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

test('Ícone e nome, no topo: o PNG do APK sobre o navy, nas máscaras redonda e squircle, nos dois papéis de parede, em 48 dp e ampliado', () => {
  const d = doc();
  const secao = d.querySelector('section.grupo');
  assert.equal(secao?.id, 't-icone', 'a primeira seção de telas é a do ícone e nome');
  assert.equal(compactar(secao.querySelector('h2').textContent), 'Ícone e nome');

  // A imagem é o primeiro plano de xxxhdpi, byte a byte, e entra uma vez só: as cópias da página a usam por <use>.
  const imagens = d.querySelectorAll('image');
  assert.equal(imagens.length, 1, 'o G entra uma vez só na página');
  const png = readFileSync(new URL('../android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png', import.meta.url));
  assert.equal(imagens[0].getAttribute('href'), 'data:image/png;base64,' + png.toString('base64'),
    'o data: URI tem de ser o PNG que vai ao APK (refaça a cópia depois do ferramentas/gerar-icones.py)');
  const id = imagens[0].getAttribute('id');
  assert.ok(id, 'a imagem tem id para o <use>');

  // O fundo é a cor do ic_launcher_background do APK.
  const navy = lerArquivo('../android/app/src/main/res/values/ic_launcher_background.xml').match(/>\s*(#[0-9a-fA-F]{6})\s*</)[1];
  const f = folha();
  assert.equal(regraDe(f, ':root').decl['--icone-fundo'].toLowerCase(), navy.toLowerCase());
  assert.equal(regraDe(f, '.icone-fundo').decl.fill, 'var(--icone-fundo)');

  const papeis = [...secao.querySelectorAll('.papel')];
  const tipo = (el, opcoes) => opcoes.find((o) => el.classList.contains(o)) ?? '?';
  assert.deepEqual(papeis.map((p) => tipo(p, ['papel-claro', 'papel-escuro'])).sort(), ['papel-claro', 'papel-escuro']);
  for (const papel of papeis) {
    const icones = [...papel.querySelectorAll('svg.icone')];
    const vistos = icones.map((s) => `${tipo(s, ['icone-redondo', 'icone-squircle'])}/${tipo(s, ['icone-real', 'icone-ampliado'])}`);
    assert.deepEqual(vistos.sort(), ['icone-redondo/icone-ampliado', 'icone-redondo/icone-real', 'icone-squircle/icone-ampliado',
      'icone-squircle/icone-real'], 'as duas máscaras, em tamanho real e ampliado, em cada papel de parede');
    for (const svg of icones) {
      // A máscara mostra os 72 dp do meio do quadro de 108 dp, como o launcher.
      assert.equal(svg.getAttribute('viewBox'), '18 18 72 72');
      const fundo = svg.querySelector('rect.icone-fundo');
      assert.deepEqual([fundo?.getAttribute('width'), fundo?.getAttribute('height')], ['108', '108']);
      assert.equal(svg.querySelector('use')?.getAttribute('href'), '#' + id);
      assert.ok(svg.getAttribute('aria-label'), 'o ícone tem nome acessível');
      const rotulo = svg.closest('figure')?.querySelector('figcaption');
      assert.equal(compactar(rotulo?.textContent ?? ''), stringDoApp('title_activity_main'), 'o rótulo embaixo do ícone é o do aplicativo');
    }
  }
  assert.equal(regraDe(f, '.icone-real').decl.width, '48px', 'o tamanho real é 48 dp');
  assert.ok(parseFloat(regraDe(f, '.icone-ampliado').decl.width) >= 96, 'o ampliado é ao menos o dobro');
  assert.equal(regraDe(f, '.icone-redondo').decl['clip-path'], 'circle(50%)');
  assert.match(regraDe(f, '.icone-squircle').decl['clip-path'], /^polygon\(/);
});

test('a abertura na prévia é a do APK: o navy do ícone com o G no quadro de 192 dp do res/drawable/splash.xml', () => {
  const d = doc();
  const abertura = d.getElementById('t-abertura');
  assert.ok(abertura, 'figure#t-abertura');
  assert.ok(abertura.closest('#t-icone'), 'na seção do ícone e nome');
  const svg = abertura.querySelector('.celular.abertura svg.abertura-g');
  assert.ok(svg, 'o G dentro da moldura do celular');
  assert.equal(svg.getAttribute('viewBox'), '0 0 108 108', 'o quadro inteiro, como o item do layer-list');
  assert.equal(svg.querySelector('use')?.getAttribute('href'), '#' + d.querySelector('image').getAttribute('id'));
  const quadro = lerArquivo('../android/app/src/main/res/drawable/splash.xml').match(/android:width="(\d+)dp"/)[1];
  const f = folha();
  assert.equal(regraDe(f, '.abertura-g').decl.width, `${quadro}px`);
  assert.equal(regraDe(f, '.celular.abertura').decl.background, 'var(--icone-fundo)');
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
  assert.match(HTML, /^\s*<title>Telas novas do Nexus Field<\/title>\s*<meta name="claude:viewport-fit-sides" content="cover">\s*<style>/);
  assert.equal(d.title, 'Telas novas do Nexus Field');
  assert.equal(d.title, `Telas novas do ${stringDoApp('app_name')}`, 'o título acompanha o nome do aplicativo');
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
  // A única imagem é o ícone, embutido como data: URI (a página continua autocontida).
  const embutida = '[src^="data:image/png;base64,"], [href^="data:image/png;base64,"]';
  for (const el of d.querySelectorAll(`[src]:not(${embutida}), link, [href]:not([href^="#"]):not(${embutida})`)) {
    assert.fail('recurso externo ou link na prévia: ' + el.outerHTML.slice(0, 80));
  }
  assert.ok(!/@import|url\(/.test(d.querySelector('style').textContent), 'sem fonte nem imagem externa');
  assert.ok(!/\p{Extended_Pictographic}/u.test(HTML), 'sem emoji');
  assert.ok(d.querySelector('#o-que-aprovar'), 'a lista "O que aprovar" no topo');
});

test('o quadro da tela Sem internet tem o fundo do Nexus Field (o do ícone), não o do site que fica atrás dos avisos', () => {
  const f = folha();
  assert.equal(regraDe(f, '#erro-html').decl.background, 'var(--icone-fundo)',
    'o iframe do erro.html pinta o navy enquanto carrega, como a página dele');
  // O --app-fundo continua sendo o do site (atrás dos diálogos e da camada do Fracttal), e é outra cor.
  const raizCss = regraDe(f, ':root').decl;
  assert.notEqual(raizCss['--app-fundo'].toLowerCase(), raizCss['--icone-fundo'].toLowerCase());
  assert.match(ERRO_HTML, /--fundo:\s*#090d18/i, 'e o srcdoc (a cópia do erro.html) está no navy');
});
