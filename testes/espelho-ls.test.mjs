// Prova a classificação das chaves e o espelho de escrita do localStorage do script de início (spec, seções 6.3 e 6.4).
// O que se protege aqui: a fila de trabalho do técnico nunca fica só na WebView, e o espelho nunca quebra a página.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { criarJanela } from './apoio.mjs';

const require = createRequire(import.meta.url);
const { instalar, classificar, fnv1a32, CLASSES, CHAVE_FILA, SENTINELA_LS, BANCO_SENTINELA } =
  require('../www/casca/inicio.js');

// Janela falsa + script instalado. `antes` roda ANTES do script (para trocar o setItem original, por exemplo).
function montar(opcoes = {}, antes = () => {}) {
  const j = criarJanela(opcoes);
  antes(j);
  const casca = instalar(j.win, j.canal, j.retrato);
  return { ...j, casca, ls: j.win.localStorage, ss: j.win.sessionStorage };
}

// A WebView executa o arquivo como script clássico, sem `module`, e o arranque é só `instalar(window, window.gcCasca, ...)`.
// Rodar o texto do arquivo num contexto vazio, com `window` e `gcCasca` postos como o nativo os poria, prova os dois
// (e pega um `import`/`export` que alguém ponha no arquivo, que não rodaria na WebView).
const CODIGO = readFileSync(new URL('../www/casca/inicio.js', import.meta.url), 'utf8');

test('no navegador (sem module) o arquivo roda como script clássico e instala sozinho', () => {
  assert.doesNotMatch(CODIGO, /^\s*(import|export)\s/m, 'ES module não roda como script injetado');
  const j = criarJanela();
  j.win.gcCasca = j.canal;
  j.win.__GC_RETRATO__ = {};
  runInNewContext(CODIGO, { window: j.win });
  j.win.localStorage.setItem('gc_campo_queue', 'x');
  j.win.localStorage.setItem('msal.k', 'y');
  assert.deepEqual(j.enviados, [{ t: 'ls', k: 'gc_campo_queue', v: 'x' }]);
});

test('no navegador, sem window.gcCasca, o arquivo roda e não embrulha nada', () => {
  const j = criarJanela();
  const original = j.win.Storage.prototype.setItem;
  assert.doesNotThrow(() => runInNewContext(CODIGO, { window: j.win }));
  assert.equal(j.win.Storage.prototype.setItem, original);
});

test('classificar segue a lista da spec', () => {
  assert.equal(classificar('gc_campo_queue'), 'fila');
  for (const k of ['gc_fech_draft', 'gc_ronda_x@y_2026-10-16_U1', 'gc_apr_rasc_9', 'gc_campo_aprdia', 'gc_ext_rasc_3',
    'gc_zl_pend', 'gc_zl_draft', 'gc_v1_os_chamado', 'gc_campo_status', 'gc_v1_os_voltou', 'gc_fb_draft',
    'gc_v1_fora_local', 'gc_v1_pt_st', 'gc_campo_geo_ini', 'gc_campo_ident']) assert.equal(classificar(k), 'trabalho', k);
  for (const k of ['gc_subs_1', 'gc_campo_apr', 'gc_campo_aprdia_f', 'gc_v1_pt_def', 'gc_ronda_ctx', 'gc_ext_ctx',
    'gc_zl_modelos', 'gc_zl_serv', 'gc_v1_almox_2', 'gc_v1_minhas_prev']) assert.equal(classificar(k), 'cache', k);
  for (const k of ['msal.token.keys.x', 'gc_erros_js', 'gc_vivo', 'gc_tela', 'gc_campo_tema', 'gc_casca_sentinela'])
    assert.equal(classificar(k), null, k);
});

test('classificar não se confunde com nomes parecidos nem com chaves herdadas do Object', () => {
  // gc_campo_apr (cache) e gc_campo_aprdia (trabalho) são exatas: um não é prefixo do outro.
  assert.equal(classificar('gc_campo_apr'), 'cache');
  assert.equal(classificar('gc_campo_aprdia'), 'trabalho');
  assert.equal(classificar('gc_campo_aprdia_f'), 'cache');
  assert.equal(classificar('gc_campo_aprx'), null);
  // prefixo pede o sublinhado final, e a chave tem de COMEÇAR com ele.
  for (const k of ['gc_ronda', 'gc_subs', 'gc_v1_almox', 'x_gc_ronda_1', 'GC_RONDA_1', ' gc_campo_queue', '', 'gc_campo_queue2'])
    assert.equal(classificar(k), null, JSON.stringify(k));
  for (const k of ['constructor', 'toString', '__proto__', 'hasOwnProperty']) assert.equal(classificar(k), null, k);
  for (const k of [undefined, null, 7, {}, ['gc_campo_queue']]) assert.equal(classificar(k), null, String(k));
});

test('classificar só usa o objeto CLASSES (a lista existe num lugar só)', () => {
  assert.deepEqual(Object.keys(CLASSES).sort(), ['cache', 'trabalho']);
  for (const classe of ['trabalho', 'cache']) {
    const { exatas, prefixos } = CLASSES[classe];
    assert.ok(exatas.length > 0 && prefixos.length > 0, classe);
    for (const k of exatas) if (!CLASSES.trabalho.excecoes.includes(k)) assert.equal(classificar(k), classe, k);
    for (const p of prefixos) {
      const k = `${p}qualquer_coisa`;
      if (!CLASSES.trabalho.excecoes.includes(k)) assert.equal(classificar(k), classe, k);
    }
  }
  // a exceção do trabalho (gc_ronda_ctx) é o que cai para o cache, e a fila não está em nenhuma das duas listas.
  assert.deepEqual(CLASSES.trabalho.excecoes, ['gc_ronda_ctx']);
  for (const k of CLASSES.trabalho.excecoes) assert.equal(classificar(k), 'cache', k);
  assert.ok(!CLASSES.trabalho.exatas.includes(CHAVE_FILA) && !CLASSES.cache.exatas.includes(CHAVE_FILA));
  // nenhuma exata do cache também é do trabalho (a mesma chave em duas classes esconderia um erro de digitação).
  for (const k of CLASSES.cache.exatas) assert.ok(!CLASSES.trabalho.exatas.includes(k), k);
});

test('as constantes públicas têm os nomes da spec, e as sentinelas não são espelhadas', () => {
  assert.equal(CHAVE_FILA, 'gc_campo_queue');
  assert.equal(SENTINELA_LS, 'gc_casca_sentinela');
  assert.equal(BANCO_SENTINELA, 'gc_casca');
  assert.equal(classificar(SENTINELA_LS), null);
  assert.equal(classificar(BANCO_SENTINELA), null);
});

test('fila vai ao nativo na hora, depois de gravar no real', () => {
  const { ls, enviados, avancar } = montar();
  ls.setItem('gc_campo_queue', '[{"id":1}]');
  assert.equal(ls.getItem('gc_campo_queue'), '[{"id":1}]');
  assert.deepEqual(enviados, [{ t: 'ls', k: 'gc_campo_queue', v: '[{"id":1}]' }]);
  avancar(5000);
  assert.equal(enviados.length, 1, 'a fila não pode ir de novo no lote');
});

test('trabalho e cache são agrupados por chave em 400 ms; vence a última', () => {
  const { ls, enviados, avancar, timersAtivos } = montar();
  ls.setItem('gc_fech_draft', 'um');
  ls.setItem('gc_fech_draft', 'dois');
  ls.setItem('gc_fech_draft', 'tres');
  assert.equal(ls.getItem('gc_fech_draft'), 'tres');
  assert.deepEqual(enviados, []);
  avancar(399);
  assert.deepEqual(enviados, [], 'nada antes de 400 ms');
  avancar(1);
  assert.deepEqual(enviados, [{ t: 'ls', k: 'gc_fech_draft', v: 'tres' }]);
  avancar(5000);
  assert.equal(enviados.length, 1);
  assert.equal(timersAtivos(), 0, 'o temporizador do lote não fica pendurado');

  // um lote novo recomeça a contagem dos 400 ms a partir da primeira gravação dele
  ls.setItem('gc_subs_1', 'a');
  avancar(399);
  assert.equal(enviados.length, 1);
  avancar(1);
  assert.deepEqual(enviados[1], { t: 'ls', k: 'gc_subs_1', v: 'a' });
});

test('o lote manda o trabalho antes do cache, mesmo que o cache tenha sido gravado primeiro', () => {
  const { ls, enviados, avancar } = montar();
  ls.setItem('gc_subs_1', 'cache');
  ls.setItem('gc_zl_pend', 'trabalho');
  avancar(400);
  assert.deepEqual(enviados.map((m) => m.k), ['gc_zl_pend', 'gc_subs_1']);
});

test('cota estourada relança e não espelha', () => {
  const { ls, enviados, avancar } = montar({ cota: 40 });
  ls.setItem('gc_vivo', 'ok');
  const grande = 'x'.repeat(100);
  for (const k of ['gc_campo_queue', 'gc_fech_draft', 'gc_subs_1']) {
    assert.throws(() => ls.setItem(k, grande), (e) => e.name === 'QuotaExceededError', k);
    assert.equal(ls.getItem(k), null, `${k} não pode ter ficado no real`);
  }
  avancar(5000);
  assert.deepEqual(enviados, [], 'nem na hora (fila) nem no lote (trabalho e cache)');
});

test('a exceção do setItem original sobe intacta, a mesma, e nada é espelhado', () => {
  const erro = new DOMException('cota de mentira', 'QuotaExceededError');
  const { ls, enviados, avancar } = montar({}, ({ win }) => {
    win.Storage.prototype.setItem = function () { throw erro; };
  });
  assert.throws(() => ls.setItem('gc_campo_queue', 'x'), (e) => e === erro);
  assert.throws(() => ls.setItem('gc_zl_pend', 'x'), (e) => e === erro);
  avancar(5000);
  assert.deepEqual(enviados, []);
});

test('o espelho chama o original primeiro e só depois manda ao nativo', () => {
  const ordem = [];
  const { ls, avancar } = montar({}, ({ win, canal }) => {
    const original = win.Storage.prototype.setItem;
    win.Storage.prototype.setItem = function (k, v) { ordem.push(`original:${k}`); return original.call(this, k, v); };
    const postar = canal.postMessage;
    canal.postMessage = (texto) => { ordem.push(`canal:${JSON.parse(texto).k}`); return postar(texto); };
  });
  ls.setItem('gc_campo_queue', '1');
  ls.setItem('gc_fech_draft', '2');
  avancar(400);
  assert.deepEqual(ordem, ['original:gc_campo_queue', 'canal:gc_campo_queue', 'original:gc_fech_draft', 'canal:gc_fech_draft']);
});

test('sessionStorage, outros Storage e msal.* nunca vão ao nativo', () => {
  const { win, ls, ss, enviados, avancar } = montar();
  ss.setItem('gc_campo_queue', 'x');
  ss.setItem('gc_fech_draft', 'x');
  ss.removeItem('gc_campo_queue');
  win.Storage.prototype.setItem.call(ss, 'gc_zl_pend', 'x');
  new win.Storage().setItem('gc_campo_queue', 'x');
  ls.setItem('msal.token.keys.abc', 'segredo');
  ls.setItem('msal.abc.idtoken', 'segredo');
  ls.setItem('gc_erros_js', '[]');
  ls.setItem('gc_vivo', '1');
  ls.setItem('gc_tela', 'inicio');
  ls.removeItem('msal.token.keys.abc');
  avancar(5000);
  assert.deepEqual(enviados, []);
  assert.equal(ls.getItem('msal.abc.idtoken'), 'segredo', 'o real continua completo');
});

test('getItem, key, length e clear ficam intocados; só setItem e removeItem são embrulhados', () => {
  const j = criarJanela();
  const proto = j.win.Storage.prototype;
  const antes = {
    getItem: proto.getItem, key: proto.key, clear: proto.clear,
    length: Object.getOwnPropertyDescriptor(proto, 'length'),
    setItem: proto.setItem, removeItem: proto.removeItem,
  };
  instalar(j.win, j.canal, j.retrato);
  assert.equal(proto.getItem, antes.getItem);
  assert.equal(proto.key, antes.key);
  assert.equal(proto.clear, antes.clear);
  assert.deepEqual(Object.getOwnPropertyDescriptor(proto, 'length'), antes.length);
  assert.notEqual(proto.setItem, antes.setItem);
  assert.notEqual(proto.removeItem, antes.removeItem);
  // e o real continua legível por inteiro
  j.win.localStorage.setItem('gc_campo_queue', 'a');
  j.win.localStorage.setItem('msal.x', 'b');
  assert.equal(j.win.localStorage.length, 2);
  assert.equal(j.win.localStorage.key(1), 'msal.x');
});

test('removeItem de chave espelhada manda lsDel na hora, e o real perde a chave', () => {
  const { ls, enviados, avancar } = montar();
  for (const k of ['gc_campo_queue', 'gc_zl_pend', 'gc_subs_9']) ls.setItem(k, 'v');
  avancar(400);
  enviados.length = 0;
  for (const k of ['gc_campo_queue', 'gc_zl_pend', 'gc_subs_9']) {
    ls.removeItem(k);
    assert.equal(ls.getItem(k), null);
  }
  assert.deepEqual(enviados, [
    { t: 'lsDel', k: 'gc_campo_queue' }, { t: 'lsDel', k: 'gc_zl_pend' }, { t: 'lsDel', k: 'gc_subs_9' },
  ]);
});

test('removeItem de chave que não é espelhada não manda nada', () => {
  const { ls, enviados, avancar } = montar();
  ls.setItem('msal.token.keys.abc', 'x');
  ls.setItem('gc_vivo', '1');
  ls.removeItem('msal.token.keys.abc');
  ls.removeItem('gc_vivo');
  avancar(5000);
  assert.deepEqual(enviados, []);
});

test('apagar cancela a gravação que ainda estava no lote, e gravar de novo depois vale', () => {
  const { ls, enviados, avancar } = montar();
  ls.setItem('gc_zl_pend', 'velho');
  ls.removeItem('gc_zl_pend');
  avancar(5000);
  assert.deepEqual(enviados, [{ t: 'lsDel', k: 'gc_zl_pend' }], 'o valor apagado nunca pode chegar ao nativo depois');

  enviados.length = 0;
  ls.setItem('gc_zl_pend', 'novo');
  ls.removeItem('gc_zl_pend');
  ls.setItem('gc_zl_pend', 'novissimo');
  avancar(400);
  assert.deepEqual(enviados, [{ t: 'lsDel', k: 'gc_zl_pend' }, { t: 'ls', k: 'gc_zl_pend', v: 'novissimo' }]);
});

test('descarregar manda o agrupado na hora e não repete depois', () => {
  const { ls, casca, enviados, avancar, timersAtivos } = montar();
  ls.setItem('gc_fech_draft', 'a');
  ls.setItem('gc_subs_1', 'b');
  assert.deepEqual(enviados, []);
  casca.descarregar();
  assert.deepEqual(enviados.map((m) => m.k), ['gc_fech_draft', 'gc_subs_1']);
  assert.equal(timersAtivos(), 0);
  avancar(5000);
  assert.equal(enviados.length, 2);
  casca.descarregar();
  assert.equal(enviados.length, 2, 'sem nada agrupado, descarregar não manda nada');
});

test('visibilitychange hidden e pagehide mandam o agrupado na hora; visible não', () => {
  const { win, ls, enviados, avancar } = montar();
  ls.setItem('gc_fech_draft', 'a');
  win.document.visibilityState = 'visible';
  win.document.dispatchEvent({ type: 'visibilitychange' });
  assert.deepEqual(enviados, [], 'visível: segue esperando o lote');

  win.document.visibilityState = 'hidden';
  win.document.dispatchEvent({ type: 'visibilitychange' });
  assert.deepEqual(enviados, [{ t: 'ls', k: 'gc_fech_draft', v: 'a' }]);

  ls.setItem('gc_fech_draft', 'b');
  win.dispatchEvent({ type: 'pagehide' });
  assert.deepEqual(enviados[1], { t: 'ls', k: 'gc_fech_draft', v: 'b' });
  avancar(5000);
  assert.equal(enviados.length, 2);
});

test('o espelho nunca quebra a página: canal que lança é engolido e vira um erro, sem valor nem chave', () => {
  const enviados = [];
  let chamadas = 0;
  const { ls, avancar } = montar({}, ({ canal }) => {
    canal.postMessage = (texto) => {
      if (chamadas++ === 0) throw new Error('canal caiu');
      enviados.push(JSON.parse(texto));
    };
  });
  assert.doesNotThrow(() => ls.setItem('gc_campo_queue', 'dado-do-tecnico'));
  assert.equal(ls.getItem('gc_campo_queue'), 'dado-do-tecnico', 'o real gravou do mesmo jeito');
  assert.equal(enviados.length, 1);
  assert.equal(enviados[0].t, 'erro');
  assert.equal(enviados[0].onde, 'setItem');
  assert.equal(enviados[0].msg, 'canal caiu');
  assert.ok(!JSON.stringify(enviados[0]).includes('dado-do-tecnico'));
  avancar(5000);
});

test('o espelho nunca quebra a página: canal que sempre lança não derruba setItem, removeItem, lote nem descarregar', () => {
  const { ls, casca, avancar } = montar({}, ({ canal }) => {
    canal.postMessage = () => { throw new Error('sempre'); };
  });
  assert.doesNotThrow(() => ls.setItem('gc_campo_queue', '1'));
  assert.doesNotThrow(() => ls.setItem('gc_fech_draft', '2'));
  assert.doesNotThrow(() => ls.removeItem('gc_campo_queue'));
  assert.doesNotThrow(() => avancar(400));
  assert.doesNotThrow(() => casca.descarregar());
  assert.equal(ls.getItem('gc_fech_draft'), '2');
});

test('erro do lote leva o "onde" do lote', () => {
  const enviados = [];
  let falhar = false;
  const { ls, avancar } = montar({}, ({ canal }) => {
    canal.postMessage = (texto) => {
      const m = JSON.parse(texto);
      if (falhar && m.t === 'ls') throw new Error('lote caiu');
      enviados.push(m);
    };
  });
  ls.setItem('gc_fech_draft', 'a');
  falhar = true;
  avancar(400);
  assert.deepEqual(enviados, [{ t: 'erro', onde: 'lote', msg: 'lote caiu' }]);
});

test('sem canal (o nativo não injetou) o script não espelha nada e não lança', async () => {
  for (const canal of [undefined, null, {}]) {
    const j = criarJanela();
    const { setItem, removeItem } = j.win.Storage.prototype;
    let casca;
    assert.doesNotThrow(() => { casca = instalar(j.win, canal, {}); });
    assert.equal(j.win.Storage.prototype.setItem, setItem, 'nada embrulhado sem canal');
    assert.equal(j.win.Storage.prototype.removeItem, removeItem);
    j.win.localStorage.setItem('gc_campo_queue', 'x');
    assert.equal(j.win.localStorage.getItem('gc_campo_queue'), 'x');
    j.avancar(5000);
    assert.equal(j.timersAtivos(), 0);
    assert.doesNotThrow(() => casca.descarregar());
    await casca.pronto;
  }
  assert.doesNotThrow(() => instalar(undefined, undefined, undefined));
});

test('pronto é uma Promise que resolve; o retrato pode faltar', async () => {
  const j = criarJanela();
  const casca = instalar(j.win, j.canal);
  assert.ok(casca.pronto instanceof Promise);
  assert.equal(await casca.pronto, undefined);
  assert.equal(typeof casca.descarregar, 'function');
});

test('se o localStorage nega acesso (SecurityError), a instalação não lança e avisa o nativo', () => {
  const j = criarJanela();
  Object.defineProperty(j.win, 'localStorage', {
    get() { throw new DOMException('negado', 'SecurityError'); },
  });
  const original = j.win.Storage.prototype.setItem;
  let casca;
  assert.doesNotThrow(() => { casca = instalar(j.win, j.canal, {}); });
  assert.equal(j.win.Storage.prototype.setItem, original);
  assert.deepEqual(j.enviados, [{ t: 'erro', onde: 'espelho-ls', msg: 'negado' }]);
  assert.doesNotThrow(() => casca.descarregar());
});

test('fnv1a32 é estável', () => {
  assert.equal(fnv1a32(''), '811c9dc5');
  assert.equal(fnv1a32('a'), 'e40c292c');
  assert.equal(fnv1a32('foobar'), 'bf9cf968');
});

test('fnv1a32 percorre as unidades UTF-16, sempre com 8 dígitos hex minúsculos', () => {
  // contas feitas à parte, em BigInt, sobre as unidades UTF-16 (não sobre os bytes UTF-8)
  const referencia = (texto) => {
    let h = 0x811c9dc5n;
    for (let i = 0; i < texto.length; i++) h = ((h ^ BigInt(texto.charCodeAt(i))) * 0x01000193n) & 0xffffffffn;
    return h.toString(16).padStart(8, '0');
  };
  // \u{1F600} é um par substituto: duas unidades UTF-16, que um FNV sobre bytes UTF-8 trataria diferente.
  for (const t of ['ã', 'Revisão geral', '\u{1F600}', 'gc_campo_queue', '[{"id":1,"fotos":["ã","\u{1F600}"]}]'])
    assert.equal(fnv1a32(t), referencia(t), t);
  assert.notEqual(fnv1a32('ã'), fnv1a32('a'));
  assert.match(fnv1a32('qualquer'), /^[0-9a-f]{8}$/);
  assert.equal(fnv1a32('\u0000'), referencia('\u0000'));
});
