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
//   6. toda string do strings.xml tem lugar na prévia: um prefixo novo não passa sem quadro (e sem aprovação);
//   7. as notificações de exemplo (o resumo das 7h e o das 13h, a emergência que toca e a que chega em silêncio) estão no
//      canal e na importância que o CanaisDeNotificacao dá, com o ícone pequeno que o APK leva (o vetor, byte a byte) em
//      branco na barra; o texto delas é do servidor do App de Campo (a casca só mostra), e a prévia o diz;
//   8. o roteiro da prova (docs/prova-no-aparelho.md) só cita textos que o aplicativo tem e cobre cada atalho, canal e
//      toast das notificações.
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
// Os rótulos dos atalhos de teste do ícone (só para administrador, no toque longo do ícone): o curto e o longo de cada um.
const stringsDosAtalhos = () => stringsDe('atalho_');
// Só o nome no parâmetro: é usado como `.map(textoDe)`, que passaria o índice num segundo parâmetro.
const textoDe = (nome) => {
  const achada = [...stringsDaCasca(), ...stringsDosCanais(), ...stringsDosAtalhos()].find((s) => s.nome === nome);
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
// O `%1$d` é um número (os aparelhos do toast do teste dos avisos).
function regexDe(texto) {
  const fonte = texto.split(/(%\d\$[sd])/).map((parte) => {
    const marca = parte.match(/^%(\d)\$([sd])$/);
    if (!marca) return parte.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    if (marca[2] === 'd') return '(\\d+)';
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
// Os toasts, na ordem da prévia: o do download que começou e o da atualização que não deu certo; depois, os do resultado
// do teste dos avisos pelo atalho do ícone (só para administrador): enviado (com e sem a contagem), só administrador, sem
// login, a falha e o segundo toque em menos de 10 s.
const TOASTS = ['casca_atualizacao_baixando', 'casca_atualizacao_sha_toast',
  'casca_teste_enviado', 'casca_teste_enviado_sem_contagem', 'casca_teste_so_admin', 'casca_teste_entrar',
  'casca_teste_falhou', 'casca_teste_ja_pedido'];

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

// Os rótulos que o AtalhosDeTeste dá a cada atalho do ícone (o setShortLabel e o setLongLabel do ShortcutInfoCompat) e o
// texto do atalho fixado que fica desligado para quem não é Admin (o do disableShortcuts), que o launcher mostra no toque.
function rotulosDosAtalhosDoJava() {
  const fonte = lerArquivo(PASTA_JAVA + 'AtalhosDeTeste.java');
  const de = (metodo) => [...fonte.matchAll(new RegExp(`\\.${metodo}\\([^;]*?R\\.string\\.(\\w+)`, 'g'))].map((m) => m[1]);
  return { curtos: de('setShortLabel'), longos: de('setLongLabel'), desligado: de('disableShortcuts') };
}

// Os canais como o CanaisDeNotificacao os cria: o id (o valor da constante CANAL_* do Notificacoes), a importância
// (IMPORTANCE_HIGH, DEFAULT, LOW) e o nome (a string do strings.xml). É o que decide como cada notificação de exemplo
// aparece: só a de importância alta aparece por cima da tela.
function canaisDetalhadosDoJava() {
  const ids = Object.fromEntries([...lerArquivo(PASTA_JAVA + 'Notificacoes.java')
    .matchAll(/static final String (CANAL_\w+) = "(\w+)";/g)].map((m) => [m[1], m[2]]));
  const fonte = lerArquivo(PASTA_JAVA + 'CanaisDeNotificacao.java');
  const canais = [];
  for (const m of fonte.matchAll(/new NotificationChannelCompat\.Builder\(\s*Notificacoes\.(CANAL_\w+),\s*NotificationManagerCompat\.IMPORTANCE_(\w+)\)([\s\S]*?)\.build\(\)/g)) {
    const nome = m[3].match(/\.setName\([^;]*?R\.string\.(\w+)/);
    assert.ok(ids[m[1]] && nome, `o teste não leu o canal ${m[1]} do CanaisDeNotificacao`);
    canais.push({ id: ids[m[1]], importancia: m[2], nome: nome[1] });
  }
  return canais;
}

// O padrão do número da OS que a casca aceita no toque (Notificacoes.OS): a OS de exemplo tem de ser uma que abriria.
function padraoDaOSDoJava() {
  const achado = lerArquivo(PASTA_JAVA + 'Notificacoes.java').match(/Pattern\.compile\("((?:[^"\\]|\\.)*)"\)/);
  assert.ok(achado, 'o teste achou o padrão da OS no Notificacoes');
  // O literal do Java tem a barra dobrada ("\\d{1,12}"); a expressão é a de uma barra só.
  return new RegExp('^' + achado[1].replace(/\\\\/g, '\\') + '$');
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

  // O toast com número (os aparelhos do teste) mostra um exemplo no lugar do %1$d.
  const toasts = [...d.querySelectorAll('.celular .toast')].map((t) => compactar(t.textContent));
  assert.equal(toasts.length, TOASTS.length, `os toasts da prévia são os ${TOASTS.length} do strings.xml: ${toasts.join(' | ')}`);
  TOASTS.forEach((nome, i) => {
    assert.match(toasts[i], regexDe(textoDe(nome)), `o ${i + 1}º toast da prévia é o ${nome}, na ordem`);
  });
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

test('os rótulos dos atalhos de teste estão no quadro do toque longo: os que o AtalhosDeTeste dá a cada atalho, e só eles', () => {
  // O dono escolheu testar as notificações por um botão que só o administrador vê; o botão é o atalho do ícone (toque
  // longo). O menu do launcher mostra o rótulo longo quando cabe e o curto quando não cabe (ou no atalho fixado na tela).
  const { curtos, longos, desligado } = rotulosDosAtalhosDoJava();
  assert.equal(curtos.length, 2, 'o teste achou os dois atalhos do AtalhosDeTeste');
  assert.equal(longos.length, 2);
  assert.equal(desligado.length, 1, 'o texto do atalho fixado desligado');
  assert.deepEqual(stringsDosAtalhos().map((s) => s.nome).sort(), [...curtos, ...longos, ...desligado].sort(),
    'todo atalho_* do strings.xml é o rótulo de um atalho ou o texto do desligado, e o AtalhosDeTeste tira cada um dali');

  const figura = doc().getElementById('t-atalhos');
  const quadro = figura?.querySelector('.celular');
  assert.ok(quadro, 'o quadro do toque longo no ícone (figure#t-atalhos)');
  const ler = (dentro, seletor) => [...dentro.querySelectorAll(seletor)].map((e) => compactar(e.textContent));
  assert.deepEqual(ler(quadro, '.menu-atalhos .atalho-rotulo'), longos.map(textoDe), 'o menu mostra os rótulos longos, na ordem do código');
  assert.deepEqual(ler(figura, '.atalho-curto'), curtos.map(textoDe), 'e a legenda diz os curtos, na mesma ordem');
  assert.deepEqual(ler(figura, '.atalho-desligado'), desligado.map(textoDe), 'e o texto do atalho fixado desligado');
  // O ícone embaixo do menu é o do aplicativo, com o rótulo dele.
  assert.ok(quadro.querySelector('svg.icone use[href="#g-nexus-field"]'), 'o ícone do Nexus Field embaixo do menu');
  assert.equal(compactar(quadro.querySelector('.inicio-rotulo').textContent), stringDoApp('title_activity_main'));
});

test('toda string do strings.xml tem lugar na prévia: um prefixo novo não escapa da aprovação', () => {
  // Os casca_* têm quadro de diálogo ou de toast, os notificacao_canal_* o quadro dos ajustes e o nome (app_name e
  // title_activity_main) o rótulo do ícone e o título da página; o package_name e o custom_url_scheme são o
  // applicationId, que o técnico não lê. Fora disso é texto novo sem quadro: ganha o quadro e o teste dele na prévia
  // antes de ir ao campo, ou entra aqui com o motivo de não aparecer ao técnico. Caso que criou a regra: os nomes dos
  // canais (18/10/2026) saíram do prefixo casca_ e ficaram fora da prévia sem nenhum teste reclamar.
  const COM_QUADRO = [/^casca_[a-z_]+$/, /^notificacao_canal_[a-z_]+$/, /^atalho_[a-z_]+$/, /^(app_name|title_activity_main)$/];
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
  const permitidos = new Set([...stringsDaCasca(), ...stringsDosCanais(), ...stringsDosAtalhos()].map((s) => s.texto));
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

// ---------------------------------------------------------------------------------------------------------------
// As notificações de exemplo (spec 2026-10-18) e o roteiro da prova
// ---------------------------------------------------------------------------------------------------------------

// O texto das notificações é do SERVIDOR do App de Campo: a mensagem do Firebase traz {titulo, texto} e a casca só os
// mostra (Notificacoes.montar), sem acrescentar nada. Por isso estes textos não estão no strings.xml: são os exemplos
// do servidor no ar (v286, lido em 18/10/2026), com a OS fictícia 12345, e a prévia diz de onde vêm. A hora é a do
// cartão: 07:00 e 13:00 são os horários dos resumos; a emergência chega "agora".
const NOTIFICACOES = [
  { id: 't-notif-manha', canal: 'resumo', titulo: 'Bom dia!', texto: 'Hoje: 4 tarefas, 1 corretiva e 3 preventivas.', hora: '07:00' },
  { id: 't-notif-tarde', canal: 'resumo', titulo: 'Boa tarde!', texto: 'Faltam 2 tarefas de hoje: 1 corretiva e 1 preventiva.', hora: '13:00' },
  { id: 't-notif-emergencia', canal: 'emergencia', titulo: 'Emergência', texto: 'Corretiva emergencial: OS 12345', hora: 'agora' },
  { id: 't-notif-silenciosa', canal: 'emergencia_silenciosa', titulo: 'Emergência', texto: 'Religamento: OS 12345', hora: 'agora' },
];

const secaoDeNotificacoes = (d) => [...d.querySelectorAll('section.grupo')]
  .find((s) => compactar(s.querySelector('h2')?.textContent ?? '') === 'Notificações');

test('as notificações de exemplo: uma por quadro, no canal e na importância que o CanaisDeNotificacao dá, com o texto do servidor', () => {
  const d = doc();
  const secao = secaoDeNotificacoes(d);
  assert.ok(secao, 'a seção Notificações');
  const canais = canaisDetalhadosDoJava();
  assert.equal(canais.length, 3, 'o teste leu os três canais do CanaisDeNotificacao');
  const osQueAbre = padraoDaOSDoJava();

  // A seção diz de quem é o texto e de qual servidor ele foi copiado.
  const introducao = compactar(secao.querySelector('h2 + p').textContent);
  assert.match(introducao, /servidor/);
  assert.match(introducao, /\bv\d{3}\b/, 'a introdução diz de qual versão do servidor são os textos');

  assert.equal(secao.querySelectorAll('.celular.gaveta').length, NOTIFICACOES.length, 'um quadro por exemplo, e só eles');
  for (const ex of NOTIFICACOES) {
    const figura = d.getElementById(ex.id);
    assert.ok(figura && secao.contains(figura), `figure#${ex.id} na seção Notificações`);
    const quadro = figura.querySelector('.celular.gaveta');
    assert.ok(quadro, `${ex.id}: o quadro da barra do Android`);
    const canal = canais.find((c) => c.id === ex.canal);
    assert.ok(canal, `o CanaisDeNotificacao cria o canal ${ex.canal}`);
    assert.equal(quadro.getAttribute('data-canal'), ex.canal);

    // O cartão: o nome do aplicativo que o Android escreve (o rótulo do app), e o título e o texto do servidor.
    const cartao = quadro.querySelector('.notificacao');
    assert.ok(cartao, `${ex.id}: o cartão da notificação`);
    const de = (seletor) => compactar(cartao.querySelector(seletor)?.textContent ?? '');
    assert.equal(de('.notificacao-app'), stringDoApp('app_name'));
    assert.equal(de('.notificacao-titulo'), ex.titulo);
    assert.equal(de('.notificacao-texto'), ex.texto);
    assert.equal(de('.notificacao-hora'), ex.hora);

    // A legenda diz o canal, com o nome do strings.xml, e de quem é o texto.
    const legenda = figura.querySelector('figcaption');
    assert.equal(compactar(legenda.querySelector('.notificacao-canal')?.textContent ?? ''), textoDe(canal.nome),
      `${ex.id}: o canal da legenda é o ${canal.nome} do strings.xml`);
    assert.match(compactar(legenda.textContent), /servidor/, `${ex.id}: a legenda diz que o texto é do servidor`);

    // Só a notificação de importância alta aparece por cima da tela (heads-up): é o que o canal faz no aparelho.
    assert.equal(quadro.classList.contains('por-cima'), canal.importancia === 'HIGH',
      `${ex.id}: o canal ${ex.canal} tem importância ${canal.importancia}; por cima da tela só a alta`);

    // A emergência traz uma OS que o toque abriria (Notificacoes.osValida); o resumo não traz OS.
    const os = ex.texto.match(/\bOS (\S+)$/);
    if (ex.canal === 'resumo') assert.equal(os, null, `${ex.id}: o resumo não leva OS`);
    else assert.ok(os && osQueAbre.test(os[1]), `${ex.id}: a OS do exemplo é uma que o toque abriria`);

    // O ícone pequeno: o G, na barra de cima e no cartão.
    assert.ok(quadro.querySelector('.barra-status svg.notificacao-g use[href="#g-notificacao"]'), `${ex.id}: o G na barra de status`);
    assert.ok(cartao.querySelector('.notificacao-icone svg.notificacao-g use[href="#g-notificacao"]'), `${ex.id}: o G no cartão`);
  }
});

test('o toque na emergência, na legenda: abre a OS e, com o técnico ocupado, a OS fica pendente (sem tela nova)', () => {
  const d = doc();
  for (const id of ['t-notif-emergencia', 't-notif-silenciosa']) {
    const legenda = compactar(d.querySelector(`#${id} figcaption`).textContent);
    assert.match(legenda, /OS 12345/);
    assert.match(legenda, /pendente/, `${id}: a OS que espera o técnico fechar o que está fazendo`);
    assert.match(legenda, /ronda/, `${id}: a ronda em andamento também segura a OS`);
  }
  // O resumo só abre o Nexus Field: não leva a OS nenhuma.
  for (const id of ['t-notif-manha', 't-notif-tarde']) {
    assert.ok(!/pendente/.test(d.querySelector(`#${id} figcaption`).textContent), `${id}: o resumo não tem OS pendente`);
  }
});

test('o ícone pequeno da notificação na prévia é o vetor que vai ao APK (o desenho, byte a byte), em branco', () => {
  const vetor = lerArquivo('../android/app/src/main/res/drawable/ic_notificacao.xml');
  const desenho = vetor.match(/android:pathData="([^"]+)"/)[1];
  const cor = vetor.match(/android:fillColor="#([0-9A-Fa-f]{8})"/)[1].toUpperCase();
  assert.equal(cor, 'FFFFFFFF', 'o vetor do APK é branco opaco: o Android só usa o alfa e pinta com a cor da barra');
  const quadro = vetor.match(/android:viewportWidth="(\d+)"\s+android:viewportHeight="(\d+)"/);
  assert.ok(quadro, 'o teste leu o quadro do vetor');

  const d = doc();
  const caminhos = d.querySelectorAll('path#g-notificacao');
  assert.equal(caminhos.length, 1, 'o desenho do G entra uma vez só na página; as cópias são <use>');
  assert.equal(caminhos[0].getAttribute('d'), desenho, 'o d= tem de ser o android:pathData do ic_notificacao.xml (refaça a cópia)');
  assert.equal(caminhos[0].getAttribute('fill'), null, 'a cor vem do token, pela folha, e não do desenho');
  for (const svg of d.querySelectorAll('svg.notificacao-g')) {
    assert.equal(svg.getAttribute('viewBox'), `0 0 ${quadro[1]} ${quadro[2]}`, 'o quadro do vetor');
  }

  // Na barra de cima o ícone é o branco do vetor, pelo token.
  const f = folha();
  assert.equal(regraDe(f, '.barra-status .notificacao-g').decl.fill, 'var(--barra-icone)');
  assert.equal(regraDe(f, ':root').decl['--barra-icone'].toLowerCase(), '#ffffff');
});

test('a lista "O que aprovar" tem o item das notificações, e todo link "Ver" leva a um quadro que existe', () => {
  const d = doc();
  const item = d.getElementById('ok-notificacoes')?.closest('li');
  assert.ok(item, 'o item ok-notificacoes da lista');
  assert.equal(item.querySelector('a.ver').getAttribute('href'), '#t-notif-manha');
  const texto = compactar(item.querySelector('label').textContent);
  assert.match(texto, /servidor/, 'o item diz que o texto das notificações é do servidor');
  assert.match(texto, /pendente/, 'e que a OS da emergência espera o técnico ficar livre');
  for (const a of d.querySelectorAll('#o-que-aprovar a.ver')) {
    const alvo = a.getAttribute('href').replace(/^#/, '');
    assert.ok(d.getElementById(alvo), `o link Ver da lista leva a #${alvo}, que não existe na página`);
  }
});

test('o roteiro da prova das notificações só cita textos do aplicativo e cobre cada atalho, canal e toast', () => {
  const roteiro = lerArquivo('../docs/prova-no-aparelho.md');
  const secao = roteiro.split(/^## /m).find((parte) => parte.startsWith('As notificações'));
  assert.ok(secao, 'a seção "## As notificações" do roteiro');
  assert.ok((secao.match(/^- \[ \] /gm) || []).length >= 20, 'a seção é uma lista de caixas de seleção');

  // Tudo o que o roteiro põe em negrito é um texto que o técnico lê: do aplicativo (strings.xml) ou o título que o
  // servidor manda. Texto que o aplicativo não tem faz o roteiro mandar o responsável procurar o que não existe.
  const doApp = [...stringsDaCasca(), ...stringsDosCanais(), ...stringsDosAtalhos()];
  const doServidor = NOTIFICACOES.map((n) => n.titulo);
  const citados = [...secao.matchAll(/\*\*([^*]+)\*\*/g)].map((m) => compactar(m[1]));
  assert.ok(citados.length >= 12, 'o roteiro cita os textos que se veem');
  for (const t of citados) {
    const existe = t === stringDoApp('app_name') || doServidor.includes(t) || doApp.some((s) => regexDe(s.texto).test(t));
    assert.ok(existe, `o roteiro cita um texto que o aplicativo não tem: "${t}"`);
  }

  // E cita cada um dos que a prova precisa ver.
  const obrigatorios = [
    ...rotulosDosAtalhosDoJava().longos, ...canaisDoJava(),
    'casca_teste_enviado', 'casca_teste_so_admin', 'casca_teste_entrar', 'casca_teste_falhou', 'casca_teste_ja_pedido',
    'casca_avisos_titulo', 'casca_permissoes_titulo',
  ];
  for (const nome of obrigatorios) {
    assert.ok(citados.some((t) => regexDe(textoDe(nome)).test(t)), `o roteiro não cita o texto ${nome}`);
  }
  for (const t of doServidor) assert.ok(citados.includes(t), `o roteiro não cita o título "${t}" do servidor`);

  // Os casos que a prova no aparelho existe para ver (o toque que espera o técnico ficar livre e o atalho só do Admin): o
  // técnico ocupado, o sem Admin, o app fechado.
  for (const palavra of [/pendente/, /ronda/, /sem Admin/, /fechado/, /12345/]) {
    assert.match(secao, palavra, `o roteiro das notificações não fala de ${palavra}`);
  }
});
