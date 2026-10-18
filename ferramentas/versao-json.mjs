// Gera o versao.json da Release (spec 2026-10-16, seção 7). O app o lê na releases/latest/download/ e só oferece a
// atualização se ele passar nas regras do VersaoRemota.java: este gerador aplica as MESMAS regras antes de escrever, para
// um erro aparecer no CI da Release e não no celular do técnico (que apenas ignoraria o arquivo, em silêncio).
//
//   VERSAO_CASCA=1.0.1 GITHUB_RUN_NUMBER=12 node ferramentas/versao-json.mjs [--apk app-campo.apk]
//                                            [--novidades novidades/<versão>.md] [--saida versao.json]
//   node ferramentas/versao-json.mjs tipo-da-versao 1.0.1        imprime "final" ou "pre-lancamento"; falha sem semver
//   GITHUB_RUN_NUMBER=12 node ferramentas/versao-json.mjs conferir-crescimento <versao.json da última Release>
//
// O SHA-256 vem do próprio APK (lido aqui). Quem chama no CI ainda confere com o `sha256sum`: duas contas independentes.

import { createReadStream, readFileSync, writeFileSync, renameSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { executadoDireto } from './executado-direto.mjs';

// Tudo o que o apk tem de ter na frente, e o padrão do resto do link (pedaços simples separados por barra). Iguais aos do
// VersaoRemota.java; o testes/versao-json.test.mjs lê o .java e falha se um dos lados mudar sem o outro.
export const PREFIXO_DO_APK = 'https://github.com/Grid-Co-CODE/app-campo-android/releases/download/';
export const PADRAO_RESTO_DO_APK = '[A-Za-z0-9._~+-]+(?:/[A-Za-z0-9._~+-]+)*';
// O Java aceita maiúsculas e lê em minúsculas. O contrato do arquivo é minúsculo (é o que o sha256sum escreve).
export const PADRAO_SHA256 = '[0-9a-f]{64}';
// O semver da casca (X.Y.Z, com pré-versão e metadado se houver). Só tem letras, dígitos, ponto, hífen e mais, que cabem
// no nome da tag (v<versão>) e do link. Sem barra, ponto duplo, espaço, "?", "#" nem "%".
export const PADRAO_VERSAO = '\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?';

const INT_MAX_DO_JAVA = 2147483647;
const NOME_DO_APK = 'app-campo.apk';

/**
 * O `apkValido` do VersaoRemota.java: o link começa no prefixo das Releases deste repositório e o resto são pedaços simples
 * de caminho (sem ".", "..", "?", "#", "%", espaço nem barra invertida). O SHA que o próprio JSON traz não protegeria de
 * um link para outro lugar, por isso o app só segue links daqui.
 */
export function apkValidoParaOApp(apk) {
  if (typeof apk !== 'string' || !apk.startsWith(PREFIXO_DO_APK)) return false;
  const resto = apk.slice(PREFIXO_DO_APK.length);
  if (!new RegExp(`^(?:${PADRAO_RESTO_DO_APK})$`).test(resto)) return false;
  return !resto.split('/').some((pedaco) => pedaco === '.' || pedaco === '..');
}

/**
 * Monta o objeto do versao.json. Lança Error (com o nome do campo, nunca o valor) se algo não passar nas regras do app.
 * `novidadesMd` é o Markdown das novidades: cada linha "- texto" vira um item, o resto do texto fica de fora.
 */
export function montarVersaoJson({ versionCode, versionName, sha256, novidadesMd } = {}) {
  if (!Number.isInteger(versionCode) || versionCode < 1 || versionCode > INT_MAX_DO_JAVA) {
    throw new Error(`versionCode inválido: tem de ser um número inteiro de 1 a ${INT_MAX_DO_JAVA}`);
  }
  if (typeof versionName !== 'string' || !new RegExp(`^${PADRAO_VERSAO}$`).test(versionName)) {
    throw new Error('versionName inválido: tem de ser o semver da casca (X.Y.Z, com pré-versão se houver), sem espaço nem barra');
  }
  if (typeof sha256 !== 'string' || !new RegExp(`^${PADRAO_SHA256}$`).test(sha256)) {
    throw new Error('sha256 inválido: tem de ter exatamente 64 dígitos hexadecimais minúsculos');
  }
  if (typeof novidadesMd !== 'string') throw new Error('novidadesMd inválido: tem de ser o texto Markdown das novidades');

  const apk = `${PREFIXO_DO_APK}v${versionName}/${NOME_DO_APK}`;
  // O gerador nunca escreve o que o app recusaria: a conferência do Java, sobre o link já montado.
  if (!apkValidoParaOApp(apk)) throw new Error('apk fora das Releases do repositório');

  const novidades = [];
  for (const linha of novidadesMd.split(/\r?\n/)) {
    const m = /^-[ \t]+(.*)$/.exec(linha);
    const texto = m ? m[1].trim() : '';
    if (texto) novidades.push(texto);
  }

  return { versionCode, versionName, apk, sha256, novidades };
}

/**
 * A versão da tag, conferida pela MESMA regra do versionName (o semver mora só no PADRAO_VERSAO): o passo "Versão da tag"
 * do montar.yml chama isto antes de a versão virar variável e nome de arquivo. Devolve 'final' ou 'pre-lancamento'. A
 * pré-versão é o hífen ANTES do metadado de build: em 1.0.0+ci-7 o hífen é do metadado e a versão é final (o `case *-*`
 * do bash a soltava como pré-lançamento, fora da releases/latest/ que o app lê).
 */
export function tipoDaVersao(versao) {
  if (typeof versao !== 'string' || !new RegExp(`^${PADRAO_VERSAO}$`).test(versao)) {
    throw new Error('a versão da tag não é o semver da casca (vX.Y.Z, com pré-versão se houver)');
  }
  return versao.split('+')[0].includes('-') ? 'pre-lancamento' : 'final';
}

/**
 * O versionCode novo tem de ser MAIOR que o do versao.json da última Release (`anteriorTexto`, o arquivo como veio). O app
 * ignora em silêncio um número menor ou igual (`v.versionCode <= versaoAtual`), e o número é o GITHUB_RUN_NUMBER do
 * montar.yml: renomear ou dividir o arquivo, ou recriar o repositório, recomeça a contagem em 1. Lança se não crescer
 * ou se o anterior não der para ler (sem conferência, nada é publicado).
 */
export function conferirCrescimento(versionCode, anteriorTexto) {
  if (!Number.isInteger(versionCode) || versionCode < 1 || versionCode > INT_MAX_DO_JAVA) {
    throw new Error('versionCode novo inválido: tem de ser um número inteiro de 1 em diante');
  }
  let anterior;
  try { anterior = JSON.parse(anteriorTexto); } catch { anterior = null; }
  const codigo = anterior && typeof anterior === 'object' ? anterior.versionCode : undefined;
  if (!Number.isInteger(codigo) || codigo < 1) {
    throw new Error('o versao.json da última Release não tem um versionCode legível: não dá para conferir que o número cresce');
  }
  if (versionCode <= codigo) {
    throw new Error(`o versionCode ${versionCode} não é maior que o da última Release (${codigo}): o app não ofereceria a ` +
      'atualização. A contagem do montar.yml recomeçou? (renomear, dividir ou recriar o workflow zera o GITHUB_RUN_NUMBER)');
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Linha de comando
// ---------------------------------------------------------------------------------------------------------------

const OPCOES = new Set(['--apk', '--novidades', '--saida']);

function lerArgumentos(argv) {
  const dados = {};
  for (let i = 0; i < argv.length; i += 2) {
    if (!OPCOES.has(argv[i])) throw new Error(`opção desconhecida: ${argv[i]}`);
    if (argv[i + 1] === undefined) throw new Error(`falta o valor de ${argv[i]}`);
    dados[argv[i].slice(2)] = argv[i + 1];
  }
  return dados;
}

function sha256DoArquivo(caminho) {
  return new Promise((ok, falha) => {
    const hash = createHash('sha256');
    createReadStream(caminho)
      .on('error', () => falha(new Error(`não consegui ler o APK (${caminho})`)))
      .on('data', (pedaco) => hash.update(pedaco))
      .on('end', () => ok(hash.digest('hex')));
  });
}

function lerNumeroDaExecucao(env) {
  const numero = env.GITHUB_RUN_NUMBER;
  if (!numero || !/^[1-9]\d*$/.test(numero)) throw new Error('GITHUB_RUN_NUMBER tem de ser um número inteiro de 1 em diante');
  return Number(numero);
}

// Os dois comandos de conferência do job release. Só o tipo vai ao stdout (o montar.yml o guarda numa variável); o erro
// vai ao stderr.
const COMANDOS = {
  'tipo-da-versao'(args) {
    if (args.length !== 1) throw new Error('uso: versao-json.mjs tipo-da-versao <versão sem o v>');
    console.log(tipoDaVersao(args[0]));
  },
  'conferir-crescimento'(args, env) {
    if (args.length !== 1) throw new Error('uso: versao-json.mjs conferir-crescimento <versao.json da última Release>');
    const versionCode = lerNumeroDaExecucao(env);
    let texto;
    try { texto = readFileSync(args[0], 'utf8'); } catch { throw new Error(`não consegui ler o versao.json anterior (${args[0]})`); }
    conferirCrescimento(versionCode, texto);
    console.log(`versionCode ${versionCode}: maior que o da última Release`);
  },
};

async function principal(argv, env) {
  if (Object.hasOwn(COMANDOS, argv[0] ?? '')) return COMANDOS[argv[0]](argv.slice(1), env);
  const opcoes = lerArgumentos(argv);

  const versionName = env.VERSAO_CASCA;
  if (!versionName) throw new Error('falta a variável VERSAO_CASCA (a versão da tag, sem o v)');
  const numero = lerNumeroDaExecucao(env);

  const apk = opcoes.apk ?? NOME_DO_APK;
  const caminhoNovidades = opcoes.novidades ?? `novidades/${versionName}.md`;
  const saida = opcoes.saida ?? 'versao.json';

  let novidadesMd;
  try { novidadesMd = readFileSync(caminhoNovidades, 'utf8'); } catch { throw new Error(`não consegui ler as novidades (${caminhoNovidades})`); }
  const sha256 = await sha256DoArquivo(apk);

  const versao = montarVersaoJson({ versionCode: numero, versionName, sha256, novidadesMd });
  // Escreve num arquivo ao lado e troca: um erro no meio não deixa um versao.json pela metade para a Release levar.
  const provisorio = `${saida}.tmp`;
  try {
    writeFileSync(provisorio, `${JSON.stringify(versao, null, 2)}\n`);
    renameSync(provisorio, saida);
  } finally {
    rmSync(provisorio, { force: true });
  }
  console.log(`${saida}: versão ${versao.versionName} (código ${versao.versionCode}), SHA-256 ${versao.sha256}, ${versao.novidades.length} novidade(s)`);
}

if (executadoDireto(import.meta.url)) {
  try {
    await principal(process.argv.slice(2), process.env);
  } catch (e) {
    console.error(`versao-json: ${e.message}`);
    process.exitCode = 1;
  }
}
