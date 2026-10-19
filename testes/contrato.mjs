// Teste de contrato com o site ao vivo (spec 2026-10-16, seção 10.2). A casca depende de coisas que moram no campo.html,
// que é do site e muda sem aviso: este arquivo lê a página (um GET) e diz o que sumiu, antes de o campo sentir.
//
//   node testes/contrato.mjs      sai com 0 (tudo no lugar), 1 (o site mudou: a lista diz o que faltou) ou
//                                 2 (não deu para ler o site: rede, status, resposta que não é o App)
//
// Fica sem ".test." no nome de propósito: o glob do `npm test` só pega *.test.mjs, e este arquivo usa a rede. O que roda
// no `npm test` é o testes/contrato.test.mjs, com HTML fictício. Quem roda este é o .github/workflows/contrato.yml, todo
// dia, e abre uma issue se falhar.
//
// Só leitura. O campo.html nunca é gravado nem copiado: as mensagens dizem o NOME do que sumiu, nunca um trecho da página.

import { createRequire } from 'node:module';
import { Script } from 'node:vm';
import { executadoDireto } from '../ferramentas/executado-direto.mjs';

const require = createRequire(import.meta.url);
// A lista do que se espelha existe no script de início e em mais lugar nenhum; o contrato lê dela. O VOLTAR são os nomes
// que o voltar do Android usa do campo.html, o PUSH os do registro do aparelho para as notificações, o ABRIR_OS os da
// abertura da OS pelo toque na emergência, e o `classificar` decide, como na WebView, se uma chave é espelhada.
const { CLASSES, CHAVE_FILA, VOLTAR, PUSH, ABRIR_OS, classificar } = require('../www/casca/inicio.js');

// Origem + appStartPath do capacitor.config.json (o testes/contrato.test.mjs confere que continuam iguais).
export const URL_DO_APP = 'https://gridco-campo-mw.azurewebsites.net/api/app';

// As chaves gc_ que o campo.html usa e que a casca NÃO espelha de propósito, cada uma com o motivo (lidas no campo.html
// v282, 17/10/2026). O contrato falha com qualquer outra chave gc_ que apareça no site fora daqui e da CLASSES do
// inicio.js: um rascunho novo que o site passasse a gravar ficaria fora do espelho em silêncio, e a perda do
// armazenamento da WebView (a razão de ser da casca) o levaria. Chave nova de TRABALHO vai para a CLASSES (e a casca
// precisa de uma versão nova, porque o inicio.js vai dentro do APK); só o que se refaz sozinho entra aqui.
export const IGNORAR = Object.freeze({
  // O que a spec (6.4) manda nunca espelhar.
  gc_erros_js: 'o registro de erros da página, só diagnóstico',
  gc_vivo: 'a caixa-preta da carga (o último ato), só diagnóstico',
  gc_tela: 'a tela aberta por último, para reabrir nela',
  // Preferências: perder só volta ao padrão.
  gc_cam_pref: 'preferência: a câmera escolhida no menu',
  gc_campo_tema: 'preferência: o tema claro ou escuro',
  gc_campo_cab: 'preferência: o cabeçalho recolhido',
  gc_campo_semana: 'preferência: a semana escolhida na lista',
  gc_campo_fx_off: 'preferência: os efeitos desligados',
  // Contadores e marcas de tempo: perder só recomeça a conta.
  gc_cam_stats: 'contador de uso da câmera, só diagnóstico',
  gc_campo_xp: 'o placar do aparelho, que o servidor refaz',
  gc_v1_limite_fx: 'a espera entre tentativas no Fracttal',
  gc_v1_subs_tentado: 'quando as subtarefas foram pedidas (vale 6 h)',
  gc_campo_relatos_vistos: 'os relatos já lidos; perder só os mostra como novos',
  // Cópias do que o servidor manda: voltam da rede.
  gc_campo_notas: 'as notas que o servidor manda (as últimas da pessoa)',
  gc_campo_rd_apr: 'as OS de ronda que esperam a APR, que o servidor manda',
  gc_campo_rd_conta: 'a situação da conta no Fracttal que o servidor mandou (vale 72 h)',
  gc_campo_rd_sem_os: 'a contagem de rondas sem OS, que o servidor manda',
  gc_campo_bd_cache: 'a cópia antiga da programação (hoje no IndexedDB, que o espelho cobre)',
  // Não é chave: é o prefixo da lista de proteção contra o despejo do próprio site (_LS_TRABALHO).
  gc_ronda: 'prefixo da proteção contra o despejo do site, não uma chave',
});

// A linha de comando: o erro do PRÓPRIO contrato (um defeito aqui, não no site) sai com 3, para não virar a issue de
// "o site mudou", que é o 1.
export async function rodarLinhaDeComando(executar = executarContrato) {
  try {
    return await executar();
  } catch (e) {
    return { codigo: 3, linhas: [`Contrato: o próprio teste falhou (${e?.name || 'erro'}). Nada foi conferido.`] };
  }
}

// Texto entre aspas simples ou duplas, que é como o site escreve as chaves. A crase NÃO vale para a chave exata nem para o
// nome do banco: nos comentários do site a chave aparece entre crases (`gc_ronda_*`), e isso não prova que o código ainda
// a usa. A crase só vale para um prefixo montado por template (`gc_subs_${os}`), que é a forma de código dela.
const ASPAS = '[\'"]';

function escapar(texto) {
  return texto.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
}

// O texto entre aspas inteiro: 'gc_campo_status' casa, mas 'gc_campo_status_novo' não. Sem isso, a chave gc_campo_aprdia
// seria dada por presente só porque gc_campo_aprdia_f existe.
function temLiteral(html, texto) {
  return new RegExp(`(${ASPAS})${escapar(texto)}\\1`).test(html);
}

// O prefixo no começo de um texto entre aspas que continua além dele ('gc_ronda_'+email, 'gc_subs_'+os), ou que é o
// próprio prefixo ('gc_apr_rasc_'), ou de um template com interpolação (`gc_subs_${os}`). Um texto que é OUTRA chave
// exata conhecida ('gc_ronda_ctx' começa com 'gc_ronda_') não conta: o prefixo de verdade (a chave da ronda montada com o
// e-mail) é que a classificação da casca espera achar no site.
// Limite conhecido: um comentário que cita a chave entre aspas simples ainda conta. O contrato avisa quando a chave some
// do site, não prova que o código a usa.
function temPrefixo(html, prefixo, chavesExatas) {
  const re = new RegExp(`(['"\`])${escapar(prefixo)}([^'"\`\\n]*)`, 'g');
  for (const m of html.matchAll(re)) {
    if (chavesExatas.has(prefixo + m[2])) continue;
    if (m[1] === '`' && !m[2].includes('${')) continue;
    return true;
  }
  return false;
}

// O banco e a store das fotos têm de estar juntos: o site usa 'fotos' também como tipo de campo do formulário, e a
// palavra solta pelo HTML não prova que a store ainda se chama assim.
const DISTANCIA_BANCO_STORE = 300;
function temStoreDasFotos(html) {
  const banco = new RegExp(`${ASPAS}gc_campo_fotos${ASPAS}`, 'g');
  const store = new RegExp(`(${ASPAS})fotos\\1`, 'g');
  const posicoesDaStore = [...html.matchAll(store)].map((m) => m.index);
  for (const m of html.matchAll(banco)) {
    if (posicoesDaStore.some((p) => Math.abs(p - m.index) <= DISTANCIA_BANCO_STORE)) return true;
  }
  return false;
}

// O trecho da função de nível alto `nome`: do parêntese dela até a próxima função na coluna 0 (ou até o teto), como
// { inicio, fim } no HTML. A indentação do site pode mudar, por isso só a coluna 0 delimita; o teto evita ler a página
// inteira se a função for a última. A declaração vale primeiro; sem ela, a atribuição à janela (window.nome = function,
// async function ou arrow), que também é de topo (ver temFuncaoDeTopo): o corpo começa no parêntese ou na seta.
const TETO_DA_FUNCAO = 12000;
function trechoDaFuncao(html, nome) {
  const n = escapar(nome);
  const inicio = new RegExp(`\\bfunction\\s+${n}\\s*\\(`).exec(html) ||
    new RegExp(`\\bwindow\\.${n}\\s*=\\s*(?:async\\s+)?(?:function\\b[^(]*\\(|\\([^()]*\\)\\s*=>|[\\w$]+\\s*=>)`).exec(html);
  if (!inicio) return null;
  const comeco = inicio.index + inicio[0].length;
  const proxima = /\n(?:async\s+)?function\s+[\w$]+\s*\(/.exec(html.slice(comeco));
  return { inicio: comeco, fim: comeco + Math.min(proxima ? proxima.index : html.length - comeco, TETO_DA_FUNCAO) };
}
function corpoDaFuncao(html, nome) {
  const trecho = trechoDaFuncao(html, nome);
  return trecho ? html.slice(trecho.inicio, trecho.fim) : null;
}

// Os blocos <script> da página, cada um com { inicio, fim, classico, js }: o que fica entre a tag que abre e a que fecha,
// se ele é um script clássico (sem type, ou com um type de JavaScript) e se é JavaScript (o clássico ou o de módulo). Num
// script de módulo, a declaração de topo não vira propriedade da janela nem entra no ambiente léxico global, e a casca
// não a enxerga; mas o código dele roda. Guardado para o último HTML lido: o contrato pergunta várias vezes do mesmo.
const TIPOS_CLASSICOS = new Set(['', 'text/javascript', 'application/javascript', 'text/ecmascript', 'application/ecmascript']);
let blocosLidos = { html: null, blocos: [] };
function blocosDeScript(html) {
  if (blocosLidos.html === html) return blocosLidos.blocos;
  const blocos = [];
  const abre = /<script\b([^>]*)>/gi;
  let m;
  while ((m = abre.exec(html))) {
    const inicio = m.index + m[0].length;
    const fecha = /<\/script\s*>/i.exec(html.slice(inicio));
    const fim = fecha ? inicio + fecha.index : html.length;
    const tipo = /\btype\s*=\s*(['"]?)([^'"\s>]*)\1/i.exec(m[1]);
    const t = tipo ? tipo[2].trim().toLowerCase() : '';
    blocos.push({ inicio, fim, classico: TIPOS_CLASSICOS.has(t), js: TIPOS_CLASSICOS.has(t) || t === 'module' });
    abre.lastIndex = fim;
  }
  blocosLidos = { html, blocos };
  return blocos;
}

// Os comentários de um JavaScript, lidos como o JavaScript os lê: o `/*` e o `//` só abrem comentário fora de um texto
// entre aspas, de um template (com o `${...}` dele) e de uma regex. Devolve o texto do mesmo tamanho, com cada comentário
// trocado por espaços e as quebras de linha no lugar: os índices e as colunas são os do original (a regra da coluna 0
// continua valendo, e o corpo de uma função se acha no texto limpo como no original). Caso que criou a regra: o campo.html
// tem o accept='image/*' (e o 'video/*') em seis lugares, e a leitura que só procurava o `/*` tomava cada um por começo
// de comentário e engolia o código até o próximo `*/` (de 837 a 5.099 caracteres, com funções de topo dentro): um logout
// posto ali ficava invisível para a regra do Sair.
// A regex e a divisão se separam pelo que vem antes da barra, como no JavaScript: depois de um valor (um nome, um número,
// um texto, o `)`, o `]`) é divisão; depois de um operador, de um `(`, de um `{`, de um `}` ou de palavras como
// `return`, é regex. Limite conhecido: a regex logo depois de um `)` (o `if (x) /re/.test(y)`) é lida como divisão; por
// isso quem chama compila o script de novo sem os comentários (ver semComentarios). Um texto entre aspas e uma regex não
// atravessam a linha (o JavaScript também não deixa): a aspa de uma leitura errada vale só até o fim da linha.
const ANTES_DE_REGEX = new Set(['return', 'typeof', 'instanceof', 'in', 'of', 'new', 'delete', 'void', 'throw', 'case',
  'do', 'else', 'yield', 'await']);
const LETRA_DE_NOME = /[A-Za-z0-9_$\u0080-\uffff]/;
export function semComentariosNoScript(js) {
  return lerJavaScript(js, false);
}
// O mesmo, e também sem o que está DENTRO dos textos: o conteúdo de cada texto entre aspas, o texto de cada template
// (o `${...}` dele é código e fica) e o corpo de cada regex viram espaços. É onde se procura uma chamada: o nome num
// texto (um toast, um innerHTML montado num texto) não é a chamada.
export function soCodigoNoScript(js) {
  return lerJavaScript(js, true);
}
function lerJavaScript(js, apagarTextos) {
  const n = js.length;
  const saida = js.split('');
  const apagar = (de, ate) => { for (let k = de; k < Math.min(ate, n); k++) if (saida[k] !== '\n' && saida[k] !== '\r') saida[k] = ' '; };
  const templates = []; // a profundidade das chaves em que cada `${` de um template abriu
  let chaves = 0;
  let regexPode = true;
  let antes = '';        // o último caractere de código (fora de espaço), para o nome depois de um ponto
  // O texto de um template, de `p` até a crase que o fecha ou até um `${`, que volta ao código.
  function template(p) {
    const de = p;
    while (p < n) {
      const c = js[p];
      if (c === '\\') p += 2;
      else if (c === '`') { if (apagarTextos) apagar(de, p); return p + 1; }
      else if (c === '$' && js[p + 1] === '{') { if (apagarTextos) apagar(de, p); templates.push(chaves); return p + 2; }
      else p++;
    }
    if (apagarTextos) apagar(de, n);
    return n;
  }
  let i = 0;
  while (i < n) {
    const c = js[i];
    const d = js[i + 1];
    if (c === '/' && (d === '/' || d === '*')) {
      const f = d === '/' ? js.indexOf('\n', i) : js.indexOf('*/', i + 2);
      const fim = f < 0 ? n : d === '/' ? f : f + 2;
      apagar(i, fim);
      i = fim;
      continue;
    }
    if (c === "'" || c === '"') {
      let p = i + 1;
      while (p < n && js[p] !== c && js[p] !== '\n') p += js[p] === '\\' ? 2 : 1;
      if (apagarTextos) apagar(i + 1, p);
      i = p < n && js[p] === c ? p + 1 : p;
      regexPode = false;
      antes = c;
      continue;
    }
    if (c === '`' || (c === '}' && templates.length && templates[templates.length - 1] === chaves)) {
      if (c === '}') templates.pop();
      i = template(i + 1);
      regexPode = false;
      antes = '`';
      continue;
    }
    if (c === '/' && regexPode) {
      let p = i + 1;
      let classe = false;
      while (p < n && js[p] !== '\n' && (classe || js[p] !== '/')) {
        if (js[p] === '\\') p++;
        else if (js[p] === '[') classe = true;
        else if (js[p] === ']') classe = false;
        p++;
      }
      if (apagarTextos) apagar(i + 1, p);
      i = p < n && js[p] === '/' ? p + 1 : p;
      regexPode = false;
      antes = '/';
      continue;
    }
    if (LETRA_DE_NOME.test(c)) {
      let p = i + 1;
      while (p < n && LETRA_DE_NOME.test(js[p])) p++;
      // O nome depois de um ponto é uma propriedade (o x.in, o x.return), um valor: depois dele, a barra é divisão.
      regexPode = antes !== '.' && ANTES_DE_REGEX.has(js.slice(i, p));
      antes = 'a';
      i = p;
      continue;
    }
    if (c === '{') chaves++;
    else if (c === '}') chaves--;
    if ((c === '+' || c === '-') && d === c) {
      // O i++ e o i-- de antes de uma barra: a barra é divisão.
      regexPode = false;
      antes = c;
      i += 2;
      continue;
    }
    if (!/\s/.test(c)) {
      regexPode = c !== ')' && c !== ']';
      antes = c;
    }
    i++;
  }
  return saida.join('');
}

function compila(js) {
  try {
    new Script(js);
    return true;
  } catch {
    return false;
  }
}

// O HTML com os comentários dos scripts de JavaScript (o clássico e o de módulo) trocados por espaços, do mesmo tamanho
// (`texto`), e o mesmo sem o que está dentro dos textos desses scripts (`codigo`, ver soCodigoNoScript). O resto (as
// marcações, os atributos, o onclick, os scripts que não são JavaScript) fica como está: ali não há comentário de
// JavaScript, e é lá que o botão do menu chama o sair. O script que compilava e deixa de compilar sem os comentários foi
// lido errado: ele fica inteiro nos dois (o código dele não some atrás de um comentário falso) e `ilegivel` diz que houve
// um. Limite: o `node:vm` compila como script clássico, e o script de módulo com import ou export não compila nem com os
// comentários; nele a leitura não tem essa defesa.
let comentariosLidos = { html: null, texto: '', codigo: '', ilegivel: false };
function semComentarios(html) {
  if (comentariosLidos.html === html) return comentariosLidos;
  let texto = '';
  let codigo = '';
  let desde = 0;
  let ilegivel = false;
  for (const b of blocosDeScript(html)) {
    if (!b.js) continue;
    const original = html.slice(b.inicio, b.fim);
    let limpo = semComentariosNoScript(original);
    let soCodigo = soCodigoNoScript(original);
    if (limpo !== original && !compila(limpo) && compila(original)) {
      limpo = original;
      soCodigo = original;
      ilegivel = true;
    }
    texto += html.slice(desde, b.inicio) + limpo;
    codigo += html.slice(desde, b.inicio) + soCodigo;
    desde = b.fim;
  }
  texto += html.slice(desde);
  codigo += html.slice(desde);
  comentariosLidos = { html, texto, codigo, ilegivel };
  return comentariosLidos;
}
function emScriptClassico(html, indice) {
  return blocosDeScript(html).some((b) => b.classico && indice >= b.inicio && indice < b.fim);
}
// A declaração na coluna 0 (a mesma regra que delimita o corpo das funções: o que está mais para dentro pode estar dentro
// de uma função ou de um bloco) e dentro de um script clássico. `padrao` é o texto de uma regex, sem âncora.
function temNoTopo(html, padrao) {
  for (const m of html.matchAll(new RegExp(`^${padrao}`, 'gm'))) if (emScriptClassico(html, m.index)) return true;
  return false;
}

// Um endereço (sem espaço nem aspas) que traz o msal-browser E a versão fixa: .../browser/2.38.1/js/msal-browser.min.js,
// ou .../@azure/msal-browser@2.38.1/... A versão tem de estar no mesmo endereço: 2.38.1 solto pela página, ou um
// msal-browser de outra versão ao lado, não valem.
function temMsalFixo(html) {
  for (const m of html.matchAll(/[^\s"'`<>]*msal-browser[^\s"'`<>]*/g)) {
    if (/(?<![\d.])2\.38\.1(?!\d|\.\d)/.test(m[0])) return true;
  }
  return false;
}

const WINDOW_OPEN_EM_BRANCO = /window\.open\(\s*(['"`])\1\s*,\s*(['"`])_blank\2\s*\)/;

// O espelho do localStorage só enxerga setItem e removeItem (ele embrulha esses dois no Storage.prototype). Quem limpa ou
// atribui por fora desses métodos apagaria o trabalho do técnico sem o nativo saber.
const PROIBIDOS = [
  ['proibido: localStorage.clear(', /(?<![\w$])localStorage\s*\.\s*clear\s*\(/],
  ['proibido: localStorage[...] = ...', /(?<![\w$])localStorage\s*\[(?:[^[\]\n]|\[[^[\]\n]*\])*\]\s*=(?!=)/],
  ['proibido: delete localStorage', /\bdelete\s+(?:window\.)?localStorage\b/],
];

// --- O que APARECE no site -------------------------------------------------------------------------------------
// Toda chave gc_ do site, do jeito que o código a escreve: entre aspas (no setItem, no auxiliar lsS/lsG, numa constante,
// numa lista) ou como começo de um template com interpolação (`gc_subs_${os}`). A crase sem interpolação não conta: é o
// jeito dos comentários do site. Limite conhecido: a chave citada entre aspas simples num comentário conta (falso alarme,
// que custa uma issue); e a chave escrita com caractere fora de [A-Za-z0-9_.:-] não é vista.
const LITERAL_GC = /(['"])(gc_[A-Za-z0-9_.:-]*)\1/g;
const TEMPLATE_GC = /`(gc_[A-Za-z0-9_.:-]*)\$\{/g;
const BANCO_DAS_FOTOS = 'gc_campo_fotos';
const STORE_DAS_FOTOS = 'fotos';
// As chaves da própria casca (a sentinela, a marca da perda, as de sessão): o site pode protegê-las do despejo dele.
const PREFIXO_DA_CASCA = 'gc_casca';
// O nome vem do HTML, mas só com [A-Za-z0-9_.:-]; o teto o mantém um nome curto na issue.
const TETO_DO_NOME = 32;

function chavesNovas(html) {
  const vistas = new Set();
  for (const m of html.matchAll(LITERAL_GC)) vistas.add(m[2]);
  for (const m of html.matchAll(TEMPLATE_GC)) vistas.add(m[1]);
  return [...vistas].filter((nome) => classificar(nome) === null && nome !== BANCO_DAS_FOTOS &&
    !nome.startsWith(PREFIXO_DA_CASCA) && !Object.hasOwn(IGNORAR, nome));
}

const curto = (nome) => (nome.length > TETO_DO_NOME ? `${nome.slice(0, TETO_DO_NOME)}…` : nome);

// O valor de um argumento: texto entre aspas, número, ou o nome de uma constante do site (const IDB_NAME='...'). Sem
// conseguir ler, undefined.
function valorDe(html, expressao) {
  const e = String(expressao ?? '').trim();
  let m = /^(['"])([^'"\n]*)\1$/.exec(e);
  if (m) return m[2];
  if (/^\d+$/.test(e)) return Number(e);
  if (!/^[A-Za-z_$][\w$]*$/.test(e)) return undefined;
  const nome = escapar(e);
  m = new RegExp(`(?<![\\w$.])${nome}\\s*=\\s*(['"])([^'"\\n]*)\\1`).exec(html);
  if (m) return m[2];
  m = new RegExp(`(?<![\\w$.])${nome}\\s*=\\s*(\\d+)\\b`).exec(html);
  return m ? Number(m[1]) : undefined;
}

// Argumentos de uma chamada sem parêntese dentro: o primeiro e o resto (depois da primeira vírgula).
function argumentos(texto) {
  const virgula = texto.indexOf(',');
  return virgula < 0 ? [texto, ''] : [texto.slice(0, virgula), texto.slice(virgula + 1)];
}

// O banco das fotos é o único que a casca espelha e restaura, e a restauração o cria na versão 1 com a store sem keyPath
// (as chaves vão fora da linha: put(valor, chave)). Outro banco ficaria sem espelho; outra versão, ou keyPath, faria a
// restauração falhar em silêncio (o put com chave fora da linha numa store com keyPath lança DataError).
function conferirIndexedDB(html, falta) {
  const aberturas = [...html.matchAll(/\bindexedDB\s*\.\s*open\s*\(/g)].length;
  if (aberturas === 0) falta('IndexedDB: o gc_campo_fotos não é aberto');
  const lidas = [...html.matchAll(/\bindexedDB\s*\.\s*open\s*\(([^()]*)\)/g)].map((m) => argumentos(m[1]));
  if (lidas.length < aberturas && aberturas > 1) falta('IndexedDB: outro banco além do gc_campo_fotos');
  for (const [nomeExpr, versaoExpr] of lidas) {
    const nome = valorDe(html, nomeExpr);
    if (typeof nome === 'string' && nome !== BANCO_DAS_FOTOS) { falta('IndexedDB: outro banco além do gc_campo_fotos'); continue; }
    // O nome que não se lê só é tomado pelo das fotos se for o único open da página.
    if (nome === undefined && aberturas > 1) { falta('IndexedDB: outro banco além do gc_campo_fotos'); continue; }
    if (versaoExpr.trim() !== '' && valorDe(html, versaoExpr) !== 1) falta('IndexedDB: gc_campo_fotos noutra versão (a casca usa a 1)');
  }
  for (const m of html.matchAll(/\bcreateObjectStore\s*\(([^()]*)\)/g)) {
    const [nomeExpr, opcoes] = argumentos(m[1]);
    const nome = valorDe(html, nomeExpr);
    if (typeof nome === 'string' && nome !== STORE_DAS_FOTOS) falta(`IndexedDB: outra store além de '${STORE_DAS_FOTOS}'`);
    if (opcoes.trim() !== '') falta('IndexedDB: store com keyPath ou autoIncrement');
  }
}

// --- Os nomes do voltar do Android -----------------------------------------------------------------------------
// O id num atributo ou numa propriedade (a câmera e as esperas só existem criadas pelo script: d.id='cham-wait'); a
// classe como palavra inteira dentro de um texto entre aspas (atributo, classList, o auxiliar que cria o botão) ou num
// seletor do CSS; a função como declaração de topo (é o que vira propriedade da janela); o `let` de topo; e a aba
// inicial chamada pela função das abas. Nome trocado no site só faz o voltar daquela camada minimizar o app: o aviso
// vem daqui, antes de o técnico sentir.
function temId(html, id) {
  return new RegExp(`(?<![\\w$-])id\\s*=\\s*\\\\?(['"])${escapar(id)}\\\\?\\1`).test(html);
}
function temClasse(html, classe) {
  const c = escapar(classe);
  return new RegExp(`(['"])(?:[^'"\\n]*\\s)?${c}(?=[\\s'"\\\\])`).test(html) ||
    new RegExp(`(?<![\\w$-])\\.${c}(?![\\w-])`).test(html);
}
// Toda declaração que a casca lê da página está no topo de um script clássico (ver temNoTopo): a função, para ser
// propriedade da janela (ou a atribuição window.nome = ..., que é propriedade em qualquer script), e o let, o const e o
// var, para estar no ambiente léxico global, onde a casca os lê pelo nome. Caso que criou a regra: o contrato aceitava a
// declaração dentro de uma função, de um bloco ou de um script de módulo, e a casca ficaria sem o endereço do servidor
// em todo aparelho, com o contrato verde.
function temFuncaoDeTopo(html, nome) {
  const n = escapar(nome);
  return temNoTopo(html, `(?:async\\s+)?function\\s+${n}\\s*\\(`) || new RegExp(`\\bwindow\\.${n}\\s*=`).test(html);
}
function temDeclaracao(html, nome) {
  return temNoTopo(html, `(?:let|var|const)\\s+(?:[\\w$]+\\s*=[^,;\\n]*,\\s*)*${escapar(nome)}\\b`);
}

function conferirVoltar(html, falta) {
  for (const id of Object.values(VOLTAR.id)) if (!temId(html, id)) falta(`voltar: id '${id}'`);
  for (const classe of Object.values(VOLTAR.classe)) if (!temClasse(html, classe)) falta(`voltar: classe '${classe}'`);
  for (const funcao of Object.values(VOLTAR.funcao)) if (!temFuncaoDeTopo(html, funcao)) falta(`voltar: função ${funcao}`);
  for (const global of Object.values(VOLTAR.global)) if (!temDeclaracao(html, global)) falta(`voltar: global ${global}`);
  const aba = new RegExp(`\\b${escapar(VOLTAR.funcao.aba)}\\s*\\(\\s*\\\\?(['"])${escapar(VOLTAR.abaInicial)}\\\\?\\1\\s*\\)`);
  if (!aba.test(html)) falta(`voltar: aba '${VOLTAR.abaInicial}'`);
}

// --- O registro do aparelho para as notificações ---------------------------------------------------------------
// O script de início registra o aparelho no servidor pela própria página: o Bearer vem da função de topo getTokenMW, o
// endereço da const de topo MIDDLEWARE_URL (lida pelo nome), e o "Sair" (a async function sair) é embrulhado para o
// servidor esquecer o aparelho antes do logout. Sem um deles, o aparelho deixa de ser registrado (o técnico não recebe
// o resumo nem a emergência) ou o sair deixa de esquecê-lo (o próximo técnico do aparelho receberia os avisos do
// anterior), tudo em silêncio. O sair tem de ser async: o embrulho devolve uma Promise.
function temFuncaoAsync(html, nome) {
  return temNoTopo(html, `async\\s+function\\s+${escapar(nome)}\\s*\\(`);
}

// O embrulho do sair só alcança quem chama o sair pelo nome. Um "Trocar de conta" com o próprio logoutRedirect, ou o menu
// passando a chamar outra função, deixaria o aparelho com a conta que saiu, e o `async function sair` antigo seguiria
// no HTML: todo logout da página mora no corpo do sair, e alguém (o botão do menu) chama o sair fora da declaração dele.
const LOGOUT = /\blogout(?:Redirect|Popup)\s*\(/g;
const CHAMADA_DO_SAIR = (nome) => new RegExp(`(?<![\\w$.])(?:window\\.)?${escapar(nome)}\\s*\\(`, 'g');
// O getTokenMW é chamado pela casca sem o técnico pedir (5 s depois do load, no online, na volta ao app, no Sair e no
// atalho de teste): se ele caísse no login interativo, uma chamada de fundo levaria a página para o login da Microsoft no
// meio do preenchimento, e o que só estava na memória da página se perderia.
const ABRE_O_LOGIN = /\b(?:loginRedirect|acquireTokenRedirect|loginPopup|acquireTokenPopup)\s*\(/;

function conferirPush(html, falta) {
  if (!temFuncaoDeTopo(html, PUSH.funcao.bearer)) falta(`push: função ${PUSH.funcao.bearer}`);
  if (!temFuncaoAsync(html, PUSH.funcao.sair)) falta(`push: async function ${PUSH.funcao.sair}`);
  if (!temDeclaracao(html, PUSH.global.middleware)) falta(`push: global ${PUSH.global.middleware}`);
  for (const id of Object.values(PUSH.id)) if (!temId(html, id)) falta(`push: id '${id}'`);
  for (const classe of Object.values(PUSH.classe)) if (!temClasse(html, classe)) falta(`push: classe '${classe}'`);

  // O logout e o corpo do getTokenMW são lidos sem os comentários do JavaScript (ver semComentarios): o que só um
  // comentário cita não é código. A chamada do sair, também fora dos textos do JavaScript (o nome num toast, ou num
  // innerHTML montado num texto, não é quem chama). A declaração (`function sair(`, `async function sair(`) não é
  // chamada.
  const { texto: semComentario, codigo, ilegivel } = semComentarios(html);
  if (ilegivel) falta('push: comentários de um script que o contrato não lê');
  const chamadas = [...codigo.matchAll(CHAMADA_DO_SAIR(PUSH.funcao.sair))]
    .filter((m) => !/\bfunction\s+$/.test(codigo.slice(Math.max(0, m.index - 20), m.index)));
  if (!chamadas.length) falta(`push: chamada do ${PUSH.funcao.sair}`);
  const sairSemComentario = trechoDaFuncao(semComentario, PUSH.funcao.sair);
  for (const m of semComentario.matchAll(LOGOUT)) {
    if (!sairSemComentario || m.index < sairSemComentario.inicio || m.index >= sairSemComentario.fim) {
      falta(`push: logout fora do ${PUSH.funcao.sair}`);
      break;
    }
  }

  const bearer = corpoDaFuncao(semComentario, PUSH.funcao.bearer);
  if (bearer !== null && ABRE_O_LOGIN.test(bearer)) falta(`push: ${PUSH.funcao.bearer} abre o login`);
}

// --- A abertura da OS pelo toque na notificação -----------------------------------------------------------------
// O toque numa emergência abre o app e o script de início põe o número da OS no campo de busca (`<input id="busca-os">`) e
// chama a função de topo setBusca, que é o que o técnico faria à mão; antes, se ele está noutra aba, vai a Tarefas pelo
// setTab('tarefas'). Com a Ronda numa etapa (o .rd-voltar dentro da #tela-ronda.on) e a ronda começada, a OS espera ele
// terminar, como com qualquer camada do voltar aberta, e na entrada do site ela espera a página restaurar a tela de antes.
// Sem um desses nomes o app abre sem a OS na tela, ou com ela por cima da ronda ou por baixo da folha reaberta, em
// silêncio: o aviso vem daqui. O setTab, a aba e o sinal da Ronda também são do voltar, e faltam nas duas listas.
function conferirAbrirOS(html, falta) {
  for (const funcao of Object.values(ABRIR_OS.funcao)) if (!temFuncaoDeTopo(html, funcao)) falta(`abrir-os: função ${funcao}`);
  for (const id of Object.values(ABRIR_OS.id)) if (!temId(html, id)) falta(`abrir-os: id '${id}'`);
  for (const classe of Object.values(ABRIR_OS.classe)) if (!temClasse(html, classe)) falta(`abrir-os: classe '${classe}'`);
  const aba = new RegExp(`\\b${escapar(ABRIR_OS.funcao.aba)}\\s*\\(\\s*\\\\?(['"])${escapar(ABRIR_OS.aba)}\\\\?\\1\\s*\\)`);
  if (!aba.test(html)) falta(`abrir-os: aba '${ABRIR_OS.aba}'`);
  conferirRonda(html, falta);
  conferirEntrada(html, falta);
}

// A ronda começada, pelo critério do próprio site: a casca lê o `let RONDA` de topo pelo nome e repete o `comecou` do
// voltar da Ronda (o rdVoltar: a usina escolhida, uma resposta ou um disperso, e não finalizada no servidor). Se o site
// trocar o nome ou o critério, a OS do toque passaria a abrir por cima de uma ronda começada (ou a esperar uma que nem
// começou): o critério tem de continuar no corpo do rdVoltar, campo por campo, e a expressão inteira do `comecou` (sem
// os espaços) tem de ser a que a casca repete, com o uso dela junto do `!RONDA.finalizadaNoServidor`. Caso que criou a
// regra: a conferência só da presença dos campos deixava passar um `||` trocado por `&&`, ou o `.length` que saísse, que
// mudam o que é "começada" sem tirar campo nenhum. Lido sem os comentários do JavaScript.
function conferirRonda(html, falta) {
  const R = ABRIR_OS.ronda;
  const G = R.global;
  if (!temDeclaracao(html, G)) falta(`abrir-os: global ${G}`);
  const criterio = corpoDaFuncao(semComentarios(html).texto, R.criterio);
  if (criterio === null || !temFuncaoDeTopo(html, R.criterio)) { falta(`abrir-os: função ${R.criterio}`); return; }
  let semCampo = false;
  for (const campo of [R.usina, R.respostas, R.dispersos, R.finalizada]) {
    if (!new RegExp(`\\b${escapar(G)}\\s*\\.\\s*${escapar(campo)}\\b`).test(criterio)) {
      falta(`abrir-os: ${R.criterio} sem ${G}.${campo}`);
      semCampo = true;
    }
  }
  if (semCampo) return;
  const esperado = `${G}&&(${G}.${R.usina}||Object.keys(${G}.${R.respostas}||{}).length||` +
    `Object.keys(${G}.${R.dispersos}||{}).length)`;
  const compacto = criterio.replace(/\s+/g, '');
  const declarado = new RegExp(`(?<![\\w$])(?:const|let|var)${escapar(R.comecou)}=([^;]*);`).exec(compacto);
  if (!declarado || declarado[1] !== esperado || !compacto.includes(`${R.comecou}&&!${G}.${R.finalizada}`)) {
    falta(`abrir-os: ${R.criterio} com outro critério de começada`);
  }
}

// A entrada do site (o ABRIR_OS.entrada): a casca só põe a OS do toque na busca depois de a entrada terminar, e lê dois
// `let` de topo pelo nome: o _telaRestaurada (a restauração da tela de antes rodou) e o pollTimer (a fila terminou de
// carregar). O contrato prende: os dois `let` no topo de um script clássico; o _telaRestaurar de topo, que liga o
// _telaRestaurada; o renderTudo de topo, que chama o _telaRestaurar; e o entrarComIdentidade de topo, que só liga o
// pollTimer depois do `await carregarDados()`. Sem eles, a casca espera o teto de 20 s em toda entrada, ou põe a OS na
// busca antes de a página reabrir por cima dela a folha de antes. Os corpos são lidos sem os comentários do JavaScript.
function conferirEntrada(html, falta) {
  const E = ABRIR_OS.entrada;
  const { texto } = semComentarios(html);
  for (const global of [E.restaurada, E.filaCarregada]) if (!temDeclaracao(html, global)) falta(`abrir-os: global ${global}`);
  const corpo = (nome) => {
    const c = temFuncaoDeTopo(html, nome) ? corpoDaFuncao(texto, nome) : null;
    if (c === null) falta(`abrir-os: função ${nome}`);
    return c;
  };
  const restaurar = corpo(E.restaurar);
  if (restaurar !== null && !new RegExp(`(?<![\\w$.])${escapar(E.restaurada)}\\s*=\\s*true\\b`).test(restaurar)) {
    falta(`abrir-os: ${E.restaurar} sem ${E.restaurada} = true`);
  }
  const render = corpo(E.render);
  if (render !== null && !new RegExp(`(?<![\\w$.])${escapar(E.restaurar)}\\s*\\(`).test(render)) {
    falta(`abrir-os: ${E.render} sem ${E.restaurar}()`);
  }
  const entrar = corpo(E.entrar);
  if (entrar === null) return;
  const carga = new RegExp(`\\bawait\\s+${escapar(E.carregarFila)}\\s*\\(`).exec(entrar);
  const liga = new RegExp(`(?<![\\w$.])${escapar(E.filaCarregada)}\\s*=(?!=)`).exec(entrar);
  if (!carga) falta(`abrir-os: ${E.entrar} sem await ${E.carregarFila}`);
  else if (!liga) falta(`abrir-os: ${E.entrar} sem ${E.filaCarregada}`);
  else if (liga.index < carga.index) falta(`abrir-os: ${E.filaCarregada} antes do ${E.carregarFila}`);
}

/**
 * Confere o campo.html contra o que a casca supõe. Devolve a lista do que faltou (ou do que apareceu e é proibido);
 * vazia = tudo no lugar. Cada item é o NOME do que falta, nunca um trecho do HTML.
 */
export function conferirContrato(html) {
  if (typeof html !== 'string') throw new TypeError('conferirContrato espera o texto do HTML');
  const faltou = [];
  const falta = (item) => { if (!faltou.includes(item)) faltou.push(item); };

  // O IndexedDB das fotos que o espelho copia: o nome, a store, e nenhum outro banco, versão ou store.
  if (!temLiteral(html, BANCO_DAS_FOTOS)) falta(`'${BANCO_DAS_FOTOS}'`);
  if (!temStoreDasFotos(html)) falta(`'${STORE_DAS_FOTOS}'`);
  conferirIndexedDB(html, falta);

  // As chaves e os prefixos da classificação (www/casca/inicio.js): a fila, o trabalho e o cache.
  const exatas = [...new Set([CHAVE_FILA, ...CLASSES.trabalho.exatas, ...CLASSES.trabalho.excecoes, ...CLASSES.cache.exatas])];
  const conhecidas = new Set(exatas);
  for (const chave of exatas) if (!temLiteral(html, chave)) falta(`'${chave}'`);
  for (const prefixo of [...CLASSES.trabalho.prefixos, ...CLASSES.cache.prefixos]) {
    if (!temPrefixo(html, prefixo, conhecidas)) falta(`prefixo '${prefixo}'`);
  }
  // E o contrário: chave gc_ do site que a casca não classifica nem ignora de propósito.
  for (const nome of chavesNovas(html)) falta(`chave nova sem classe: '${curto(nome)}'`);

  // O login, a trava do /api/app e o MSAL fixo.
  if (!/\bloginRedirect\s*\(/.test(html)) falta('loginRedirect');
  if (!temLiteral(html, '/api/app')) falta("'/api/app'");
  if (!temMsalFixo(html)) falta('msal-browser 2.38.1');

  // A aba do Fracttal: aberta já no clique, em branco, para o iPhone e a casca não a bloquearem.
  const corpo = corpoDaFuncao(html, 'conectarFracttal');
  if (corpo === null) falta('função conectarFracttal');
  else if (!WINDOW_OPEN_EM_BRANCO.test(corpo)) falta("window.open('','_blank') em conectarFracttal");

  conferirVoltar(html, falta);
  conferirPush(html, falta);
  conferirAbrirOS(html, falta);

  for (const [item, padrao] of PROIBIDOS) if (padrao.test(html)) falta(item);

  return faltou;
}

const ESPERAS_PADRAO_MS = [3000, 8000];
const TEMPO_LIMITE_MS = 30000;

const dorme = (ms) => (ms > 0 ? new Promise((r) => setTimeout(r, ms)) : Promise.resolve());

// A resposta que não vai ser lida tem o corpo cancelado: o fetch do Node segura a conexão até o corpo ser lido ou
// cancelado, e as três tentativas num 5xx deixavam três abertas. O cancelamento que falha não muda nada.
function descartar(res) {
  try {
    const p = res?.body?.cancel?.();
    if (p && typeof p.catch === 'function') p.catch(() => {});
  } catch { /* já fechado */ }
}

// Um GET, com 2 novas tentativas se a rede cair ou o servidor responder 5xx (o site é um Azure App Service e dorme). 4xx e
// resposta que não é a página do App não se repetem. Devolve { html } ou { erro }.
async function lerOSite({ buscar, url, esperasMs }) {
  let erro = 'sem resposta';
  for (let tentativa = 0; tentativa <= esperasMs.length; tentativa++) {
    if (tentativa > 0) await dorme(esperasMs[tentativa - 1]);
    let res;
    try {
      res = await buscar(url, {
        method: 'GET',
        redirect: 'follow',
        headers: { accept: 'text/html', 'user-agent': 'nexus-field-contrato' },
        signal: AbortSignal.timeout(TEMPO_LIMITE_MS),
      });
    } catch (e) {
      erro = `a rede falhou (${e?.name || 'erro'})`;
      continue;
    }
    if (res.status >= 500) { descartar(res); erro = `o site respondeu ${res.status}`; continue; }
    if (!res.ok) { descartar(res); return { erro: `o site respondeu ${res.status}` }; }
    if (res.url && new URL(res.url).origin !== new URL(url).origin) {
      descartar(res);
      return { erro: `a resposta parou em outro endereço (${new URL(res.url).host}), não no App` };
    }
    if (!/text\/html/i.test(res.headers.get('content-type') || '')) { descartar(res); return { erro: 'a resposta não é uma página HTML' }; }
    try {
      return { html: await res.text() };
    } catch (e) {
      erro = `a leitura da resposta falhou (${e?.name || 'erro'})`;
    }
  }
  return { erro };
}

/**
 * O contrato inteiro: lê o site e confere. `buscar` é o fetch (trocável nos testes). Devolve o código de saída e as linhas
 * a mostrar: 0 = ok, 1 = o site mudou, 2 = não deu para ler o site.
 */
export async function executarContrato({ buscar = fetch, url = URL_DO_APP, esperasMs = ESPERAS_PADRAO_MS } = {}) {
  const lido = await lerOSite({ buscar, url, esperasMs });
  if (lido.erro) {
    return { codigo: 2, linhas: [`Contrato: não consegui ler o site (${lido.erro}). Nada foi conferido.`] };
  }
  const faltou = conferirContrato(lido.html);
  if (faltou.length === 0) return { codigo: 0, linhas: ['Contrato ok: o campo.html tem tudo de que a casca depende.'] };
  return {
    codigo: 1,
    linhas: [
      `Contrato quebrado: ${faltou.length} item(ns) do campo.html que a casca supõe não estão como antes.`,
      ...faltou.map((item) => `- ${item}`),
      'Confira com a sessão "App de campo" a classificação em www/casca/inicio.js (CLASSES), a IGNORAR daqui, os nomes ' +
        'do voltar (VOLTAR), os do registro do aparelho (PUSH), os da abertura da OS pelo toque (ABRIR_OS) e a janela ' +
        'do Fracttal.',
    ],
  };
}

if (executadoDireto(import.meta.url)) {
  const { codigo, linhas } = await rodarLinhaDeComando();
  console[codigo === 0 ? 'log' : 'error'](linhas.join('\n'));
  process.exitCode = codigo;
}
