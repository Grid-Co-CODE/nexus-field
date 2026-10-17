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
import { executadoDireto } from '../ferramentas/executado-direto.mjs';

const require = createRequire(import.meta.url);
// A lista do que se espelha existe no script de início e em mais lugar nenhum; o contrato lê dela.
const { CLASSES, CHAVE_FILA } = require('../www/casca/inicio.js');

// Origem + appStartPath do capacitor.config.json (o testes/contrato.test.mjs confere que continuam iguais).
export const URL_DO_APP = 'https://gridco-campo-mw.azurewebsites.net/api/app';

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

// O corpo da função de nível alto `nome`: do parêntese dela até a próxima função na coluna 0 (ou até o teto). A indentação
// do site pode mudar, por isso só a coluna 0 delimita; o teto evita ler a página inteira se a função for a última.
const TETO_DA_FUNCAO = 12000;
function corpoDaFuncao(html, nome) {
  const inicio = new RegExp(`\\bfunction\\s+${escapar(nome)}\\s*\\(`).exec(html);
  if (!inicio) return null;
  const resto = html.slice(inicio.index + inicio[0].length);
  const proxima = /\n(?:async\s+)?function\s+[\w$]+\s*\(/.exec(resto);
  return resto.slice(0, Math.min(proxima ? proxima.index : resto.length, TETO_DA_FUNCAO));
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

/**
 * Confere o campo.html contra o que a casca supõe. Devolve a lista do que faltou (ou do que apareceu e é proibido);
 * vazia = tudo no lugar. Cada item é o NOME do que falta, nunca um trecho do HTML.
 */
export function conferirContrato(html) {
  if (typeof html !== 'string') throw new TypeError('conferirContrato espera o texto do HTML');
  const faltou = [];
  const falta = (item) => { if (!faltou.includes(item)) faltou.push(item); };

  // O IndexedDB das fotos que o espelho copia.
  if (!temLiteral(html, 'gc_campo_fotos')) falta("'gc_campo_fotos'");
  if (!temStoreDasFotos(html)) falta("'fotos'");

  // As chaves e os prefixos da classificação (www/casca/inicio.js): a fila, o trabalho e o cache.
  const exatas = [...new Set([CHAVE_FILA, ...CLASSES.trabalho.exatas, ...CLASSES.trabalho.excecoes, ...CLASSES.cache.exatas])];
  const conhecidas = new Set(exatas);
  for (const chave of exatas) if (!temLiteral(html, chave)) falta(`'${chave}'`);
  for (const prefixo of [...CLASSES.trabalho.prefixos, ...CLASSES.cache.prefixos]) {
    if (!temPrefixo(html, prefixo, conhecidas)) falta(`prefixo '${prefixo}'`);
  }

  // O login, a trava do /api/app e o MSAL fixo.
  if (!/\bloginRedirect\s*\(/.test(html)) falta('loginRedirect');
  if (!temLiteral(html, '/api/app')) falta("'/api/app'");
  if (!temMsalFixo(html)) falta('msal-browser 2.38.1');

  // A aba do Fracttal: aberta já no clique, em branco, para o iPhone e a casca não a bloquearem.
  const corpo = corpoDaFuncao(html, 'conectarFracttal');
  if (corpo === null) falta('função conectarFracttal');
  else if (!WINDOW_OPEN_EM_BRANCO.test(corpo)) falta("window.open('','_blank') em conectarFracttal");

  for (const [item, padrao] of PROIBIDOS) if (padrao.test(html)) falta(item);

  return faltou;
}

const ESPERAS_PADRAO_MS = [3000, 8000];
const TEMPO_LIMITE_MS = 30000;

const dorme = (ms) => (ms > 0 ? new Promise((r) => setTimeout(r, ms)) : Promise.resolve());

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
        headers: { accept: 'text/html', 'user-agent': 'app-campo-android-contrato' },
        signal: AbortSignal.timeout(TEMPO_LIMITE_MS),
      });
    } catch (e) {
      erro = `a rede falhou (${e?.name || 'erro'})`;
      continue;
    }
    if (res.status >= 500) { erro = `o site respondeu ${res.status}`; continue; }
    if (!res.ok) return { erro: `o site respondeu ${res.status}` };
    if (res.url && new URL(res.url).origin !== new URL(url).origin) {
      return { erro: `a resposta parou em outro endereço (${new URL(res.url).host}), não no App` };
    }
    if (!/text\/html/i.test(res.headers.get('content-type') || '')) return { erro: 'a resposta não é uma página HTML' };
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
      'Confira a classificação em www/casca/inicio.js (CLASSES) e a janela do Fracttal com a sessão "App de campo".',
    ],
  };
}

if (executadoDireto(import.meta.url)) {
  const { codigo, linhas } = await executarContrato();
  console[codigo === 0 ? 'log' : 'error'](linhas.join('\n'));
  process.exitCode = codigo;
}
