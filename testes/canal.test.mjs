// Prova que a lista fechada de rótulos do erro da página (Mensageiro.ONDES_DA_PAGINA, no nativo) é a mesma que o
// www/casca/inicio.js usa. O nativo leva ao log só o rótulo e a contagem; rótulo fora da lista vira 'outro'. Se o script
// ganhar um rótulo e a lista não, o aviso dele chega ao log sem dizer de onde veio; se a lista guardar um que o script
// não usa mais, ela deixa de ser a do script. E que todo tipo de mensagem que o script manda o nativo atende, e a ligação
// nativa da conversa das notificações (o que o JVM não roda: o plugin com a WebView e o serviço do Firebase).
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

test('o registro do aparelho (notificações) avisa com o rótulo push, e a lista do nativo o tem', () => {
  // Sem ele na lista, o aviso de que a rota ainda não está no ar (404) ou de que a rede falhou chegaria ao log como
  // 'outro', e a prova no aparelho não diria de onde veio.
  assert.ok(rotulosDoScript().includes('push'), 'o inicio.js usa o rótulo push');
  assert.ok(rotulosDoNativo().includes('push'), 'o Mensageiro.ONDES_DA_PAGINA tem push');
});

test('a lista do nativo é a dos rótulos do inicio.js', () => {
  const doScript = rotulosDoScript();
  assert.ok(doScript.length > 20, 'a extração achou os rótulos: ' + doScript.join(', '));
  // O da chave da equipe (a seção 10): sem ele na lista, o 401 ou o 503 do Nexus chegaria ao log como 'outro'.
  assert.ok(doScript.includes('equipe'), 'o inicio.js usa o rótulo equipe');
  assert.deepEqual(rotulosDoNativo(), doScript);
});

// Os tipos de mensagem que o script manda ao nativo (o `t` do `enviar` e do aviso de erro, que vai direto pelo canal) e
// os que o Mensageiro.tratar atende (os `case` do switch dele). Tipo que o nativo não atende cai em "tipo de mensagem
// desconhecido", só no log. Caso que criou a regra: a página passou a mandar o Sair, o registro aceito e a OS aberta
// ({t:'pushSaiu'}, {t:'pushRegistrado'}, {t:'osAberta'}), e o nativo os recusava sem ninguém ver.
function tiposDoScript() {
  return [...new Set([
    ...todos(script, /\benviar\(\{\s*t:\s*'([A-Za-z]+)'/g),
    ...todos(script, /postMessage\(JSON\.stringify\(\{\s*t:\s*'([A-Za-z]+)'/g),
  ])].sort();
}

function tiposDoNativo() {
  const inicio = mensageiro.indexOf('public boolean tratar(String json)');
  const fim = mensageiro.indexOf('private boolean pedaco(', inicio);
  assert.ok(inicio > 0 && fim > inicio, 'o teste achou o Mensageiro.tratar');
  return [...new Set(todos(mensageiro.slice(inicio, fim), /case "([A-Za-z]+)":/g))].sort();
}

test('todo tipo de mensagem que o script manda o nativo atende, e o nativo não atende tipo que o script não manda', () => {
  const doScript = tiposDoScript();
  assert.ok(doScript.length >= 15, 'a extração achou os tipos: ' + doScript.join(', '));
  for (const tipo of ['pushSaiu', 'pushRegistrado', 'osAberta', 'pushPedirToken', 'pushAdmin', 'pushTeste',
    'equipePedirAparelho', 'equipeChave']) {
    assert.ok(doScript.includes(tipo), `o script manda ${tipo}`);
  }
  assert.deepEqual(tiposDoNativo(), doScript);
});

// O plugin e o serviço do Firebase não rodam no JVM com a WebView: a ligação deles ao PushNaCasca (a regra, que tem teste
// de JVM) se confere no texto do Java.
const semComentarios = (java) => java.replace(/\/\*[\s\S]*?\*\//g, '').split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n');
const JAVA = 'android/app/src/main/java/br/com/gridco/campo/';

test('a ligação nativa das notificações: o canal, o token novo e a volta ao app vão ao PushNaCasca, na thread do espelho', () => {
  const plugin = semComentarios(ler(JAVA + 'CascaPlugin.java'));
  const servico = semComentarios(ler(JAVA + 'MensagensDoFirebase.java'));
  // O token novo do Firebase é guardado e vai à página da tela viva (a que se registrou como VIVO no load()).
  assert.match(servico, /public void onNewToken\(@NonNull String token\) \{\s*guardarTokenNovo\(this, token\);\s*CascaPlugin\.tokenNovo\(token\);/);
  assert.match(plugin, /static void tokenNovo\(String token\) \{\s*CascaPlugin vivo = VIVO\.get\(\);[\s\S]*?threadDoEspelho\(\)\.execute\(\(\) -> \{\s*if \(!vivo\.encerrado\) vivo\.push\.tokenNovo\(token\);/);
  assert.match(plugin, /push = new PushNaCasca\([^;]*OS_DO_TOQUE_DO_PROCESSO[^;]*\);\s*VIVO\.set\(this\);/);
  // A tela que sai e o renderer que morre deixam de ser a viva.
  assert.match(plugin, /protected void handleOnDestroy\(\) \{\s*encerrado = true;\s*VIVO\.compareAndSet\(this, null\);/);
  assert.match(plugin, /onRenderProcessGone\([^)]*\) \{\s*encerrado = true;\s*VIVO\.compareAndSet\(CascaPlugin\.this, null\);/);
  // A volta ao app confere os avisos e o token guardado.
  assert.match(plugin, /protected void handleOnResume\(\) \{[\s\S]*?threadDoEspelho\(\)\.execute\(\(\) -> \{\s*if \(!encerrado\) conversa\.voltouAoApp\(\);/);
  // As mensagens da página.
  assert.match(plugin, /public void pushPedirToken\(\) \{[^}]*push\.pediuToken\(mensagem -> responder\(ponte, mensagem\)\);/);
  assert.match(plugin, /public void pushSaiu\(\) \{\s*try \{\s*push\.saiu\(\);/);
  assert.match(plugin, /public void pushRegistrado\(\) \{\s*push\.registrado\(\);/);
  assert.match(plugin, /public void osAberta\(String os\) \{\s*push\.osAberta\(os\);/);
  // A OS do toque e a regra dos 10 s do teste são as do processo (atravessam a tela recriada).
  assert.match(plugin, /aberturaDaOS = new AberturaDaOS\([\s\S]*?OS_DO_TOQUE_DO_PROCESSO\);/);
  assert.match(plugin, /testeDosAvisos = new TesteDosAvisos\([\s\S]*?TESTES_DO_PROCESSO,/);
});

test('a ligação nativa da equipe: a volta ao app reavalia, o Sair desliga e as mensagens da página vão à EquipeNaCasca', () => {
  // A regra tem teste de JVM (EquipeNaCascaTest); o plugin, que só a liga, se confere no texto do Java.
  const plugin = semComentarios(ler(JAVA + 'CascaPlugin.java'));
  // A da tela, com o pedido da permissão registrado no load() (o registro da AndroidX só vale antes de a tela começar).
  assert.match(plugin, /ActivityResultLauncher<String\[\]> pedidoDaEquipe = bridge\.registerForActivityResult\(\s*new ActivityResultContracts\.RequestMultiplePermissions\(\), resultado -> \{[^}]*daEquipe\.aposOPedido\(resultado\);/);
  assert.match(plugin, /equipe = EquipeNaCasca\.daTela\(getActivity\(\), this::naUi, pedidoDaEquipe::launch, BuildConfig\.VERSION_NAME\);/);
  // A tela que nasce desfaz a conta conferida antes dela (R8), também a do disco, que o religamento sem tela lê.
  assert.match(plugin, /equipe = EquipeNaCasca\.daTela\([^;]*\);\s*equipe\.aTelaAbriu\(\);/);
  // A primeira abertura e a volta ao app (o onResume), o Sair, a tela que sai.
  assert.match(plugin, /protected void handleOnResume\(\) \{[\s\S]*?if \(daEquipe != null\) daEquipe\.reavaliar\(\);/);
  // O Sair da equipe também quando o das notificações lança: senão a coleta de quem saiu seguia até as 22h.
  assert.match(plugin, /public void pushSaiu\(\) \{\s*try \{\s*push\.saiu\(\);\s*\} finally \{\s*if \(equipe != null\) equipe\.sair\(\);\s*\}/);
  assert.match(plugin, /protected void handleOnDestroy\(\) \{[^}]*if \(equipe != null\) equipe\.encerrar\(\);/);
  // As mensagens da página, com a conta (o hash que a página calcula do token dela): a resposta do aparelho pela ponte
  // da mensagem que pediu, e o que o Nexus respondeu.
  assert.match(plugin, /public void equipePedirAparelho\(String conta\) \{[^}]*daEquipe\.pedirAparelho\(conta, mensagem -> responder\(ponte, mensagem\)\);/);
  assert.match(plugin, /public void equipeChave\(boolean coleta, String chave, String conta\) \{\s*if \(equipe != null\) equipe\.receberChave\(coleta, chave, conta\);/);
});

// Os motivos dos avisos da chave da equipe (seção 10): o nativo leva ao log só o motivo que está na lista dele
// (Mensageiro.MOTIVOS_DA_PAGINA), e o texto nunca. Motivo novo no script sem a lista chegaria ao log como "erro da
// página" sem dizer qual; o que a lista guardar e o script não usar deixa de ser o do script. Caso que criou a regra: o
// piloto, com o APK da Release, via só "equipe (3)" e não separava o token sem id, o 401 do Nexus e o CORS.
function motivosDoScript() {
  const lista = script.match(/var MOTIVOS_DA_EQUIPE = \[([^\]]*)\];/);
  assert.ok(lista, 'o inicio.js declara MOTIVOS_DA_EQUIPE');
  return todos(lista[1], /'([^']+)'/g).sort();
}

test('os motivos dos avisos da equipe são os da lista do nativo, e cada um é usado pela seção 10', () => {
  const doScript = motivosDoScript();
  const bloco = mensageiro.match(/MOTIVOS_DA_PAGINA = [\s\S]*?\)\)\);/);
  assert.ok(bloco, 'o Mensageiro.java declara MOTIVOS_DA_PAGINA');
  assert.deepEqual([...new Set(todos(bloco[0], /"([^"]+)"/g))].sort(), doScript);
  const secao = script.slice(script.indexOf('// 10. A chave da equipe'), script.indexOf('// Ponto único de entrada'));
  const usados = new Set([
    ...todos(secao, /\b(?:avisar|avisarUmaVez|falhar)\(\s*'([^']+)'/g),
    ...todos(secao, /return '([A-Za-z0-9]+)';/g),
    ...todos(secao, /\benviar\(\{[^}]*\},\s*'equipe',\s*'([^']+)'\)/g),
  ]);
  for (const motivo of usados) assert.ok(doScript.includes(motivo), `a seção 10 usa o motivo ${motivo}, fora da lista`);
  for (const motivo of doScript) assert.ok(usados.has(motivo), `o motivo ${motivo} está na lista e a seção 10 não o usa`);
});
