/*
 * Script de início da casca do App de Campo (spec 2026-10-16, seções 6.3 e 6.4).
 *
 * O plugin nativo o injeta com addDocumentStartJavaScript, restrito à origem do App, antes de qualquer script da
 * página (venha o HTML da rede ou do cache do service worker). É um script CLÁSSICO, sem import/export, porque é
 * assim que a WebView o executa. No Node (testes), o mesmo arquivo vira módulo CommonJS: ver o fim do arquivo.
 *
 * Regra de ouro: este script nunca pode quebrar a página. Todo erro daqui é engolido e vira {t:'erro', onde, msg}
 * para o nativo, sem valor e sem nome de chave (os nomes das chaves têm e-mail).
 *
 * Seções (o `instalar` é o ponto único de entrada e liga cada uma):
 *   1. constantes e classificação das chaves                     (Task 2)
 *   2. contexto: canal ao nativo e tratamento de erro            (Task 2)
 *   3. espelho de escrita do localStorage                        (Task 2)
 *   4. sentinela do localStorage, versões da fila e restauração  (Task 3; roda ANTES da 3)
 *   5. espelho do IndexedDB das fotos e window.open              (Task 4)
 */
(function () {
  'use strict';

  // ---------------------------------------------------------------------------------------------------------------
  // 1. Constantes e classificação das chaves
  // ---------------------------------------------------------------------------------------------------------------

  var CHAVE_FILA = 'gc_campo_queue';
  var SENTINELA_LS = 'gc_casca_sentinela';
  var BANCO_SENTINELA = 'gc_casca';

  // Espera do lote: o trabalho e o cache são agrupados por chave neste tempo (a spec pede 300 a 500 ms). A fila não
  // espera, porque é o que o técnico não pode perder; o resto aguenta 400 ms e poupa o canal (um rascunho é regravado
  // a cada tecla).
  var ESPERA_LOTE_MS = 400;

  // A lista do que se espelha existe NESTE objeto e em mais lugar nenhum: o `classificar` só lê daqui, e o teste de
  // contrato com o site (Task 9) importa o mesmo objeto para conferir que as chaves ainda existem no campo.html.
  //   trabalho: o que não volta da rede (rascunhos, pendências, o que o técnico preencheu);
  //   cache:    o que a página baixa e precisa para trabalhar sem sinal (prioridade menor);
  //   exatas:   a chave inteira; prefixos: a chave começa com isso;
  //   excecoes: chaves que casam com um prefixo do trabalho mas NÃO são trabalho (o gc_ronda_ctx é contexto
  //             baixado da rede, enquanto gc_ronda_<e-mail>_<dia>_<usina> é a ronda que o técnico preencheu).
  // Fora das duas listas: msal.*, tokens, gc_erros_js, gc_vivo, gc_tela, preferências e contadores. A fila
  // (CHAVE_FILA) é a classe 'fila' e fica de fora de propósito: tem tratamento próprio (vai ao nativo na hora).
  var CLASSES = {
    trabalho: {
      exatas: [
        'gc_fech_draft', 'gc_campo_aprdia', 'gc_zl_pend', 'gc_zl_draft', 'gc_v1_os_chamado', 'gc_campo_status',
        'gc_v1_os_voltou', 'gc_fb_draft', 'gc_v1_fora_local', 'gc_v1_pt_st', 'gc_campo_geo_ini',
        'gc_campo_ident', // dado pessoal: o nativo guarda cifrado (Android Keystore)
      ],
      prefixos: ['gc_ronda_', 'gc_apr_rasc_', 'gc_ext_rasc_'],
      excecoes: ['gc_ronda_ctx'],
    },
    cache: {
      exatas: [
        'gc_campo_apr', 'gc_campo_aprdia_f', 'gc_v1_pt_def', 'gc_ronda_ctx', 'gc_ext_ctx', 'gc_zl_modelos',
        'gc_zl_serv', 'gc_v1_minhas_prev',
      ],
      prefixos: ['gc_subs_', 'gc_v1_almox_'],
    },
  };

  // Conjuntos para o teste de pertinência: `Array.includes` serve, mas a chave 'constructor' ou '__proto__' não pode
  // casar com nada por herança do Object, e o Set garante isso.
  function conjunto(lista) {
    var s = new Set();
    for (var i = 0; i < lista.length; i++) s.add(lista[i]);
    return s;
  }
  var EXATAS_TRABALHO = conjunto(CLASSES.trabalho.exatas);
  var EXCECOES_TRABALHO = conjunto(CLASSES.trabalho.excecoes);
  var EXATAS_CACHE = conjunto(CLASSES.cache.exatas);

  function comecaCom(chave, prefixos) {
    for (var i = 0; i < prefixos.length; i++) if (chave.indexOf(prefixos[i]) === 0) return true;
    return false;
  }

  // 'fila' | 'trabalho' | 'cache' | null (null = nunca vai ao nativo).
  function classificar(chave) {
    if (typeof chave !== 'string') return null;
    if (chave === CHAVE_FILA) return 'fila';
    if (!EXCECOES_TRABALHO.has(chave) &&
        (EXATAS_TRABALHO.has(chave) || comecaCom(chave, CLASSES.trabalho.prefixos))) return 'trabalho';
    if (EXATAS_CACHE.has(chave) || comecaCom(chave, CLASSES.cache.prefixos)) return 'cache';
    return null;
  }

  // FNV-1a de 32 bits sobre as unidades UTF-16 do texto, em 8 dígitos hex minúsculos. Não é segurança: serve para a
  // regra das versões da fila (Task 3) saber se o valor da WebView é igual a uma versão que o nativo já guardou,
  // sem trafegar o texto inteiro. UTF-16 (e não UTF-8) porque é o que o JavaScript dá de graça, nos dois lados.
  function fnv1a32(texto) {
    var h = 0x811c9dc5;
    var s = String(texto);
    for (var i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    return ('00000000' + (h >>> 0).toString(16)).slice(-8);
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 2. Contexto: canal ao nativo e tratamento de erro
  // ---------------------------------------------------------------------------------------------------------------

  // O contexto é o que as seções compartilham: a janela, o retrato do nativo, o envio seguro ao canal e as listas em
  // que cada seção se registra (descarregadores: o que mandar já quando a página vai embora; prontos: o que o
  // `pronto` do `instalar` espera).
  function criarContexto(win, canal, retrato) {
    var ctx = {
      win: win,
      retrato: retrato,
      descarregadores: [],
      prontos: [],
      // Ligada pela seção 4 quando esta carga foi parada para pedir o retrato completo (perda ou fila atrasada). Daí
      // até a próxima carga nenhum espelho liga: a página que ainda rodar está vazia ou atrasada, e espelhá-la
      // gravaria esse estado por cima do que o nativo guardou. Toda seção que espelha confere esta marca.
      cargaParada: false,

      // Manda uma mensagem ao nativo. Nunca lança: se o canal falhar, avisa por `falhou` e segue.
      // O canal é lido a cada chamada (não guardado), porque é o objeto que o nativo injetou e pode ser trocado.
      enviar: function (mensagem, onde) {
        try {
          canal.postMessage(JSON.stringify(mensagem));
        } catch (e) {
          ctx.falhou(onde, e);
        }
      },

      // Conta o erro ao nativo: só `onde` (um rótulo nosso) e o texto do erro, cortado. Nunca a chave nem o valor,
      // que são dado do técnico. Se nem o aviso couber no canal, não há mais nada a fazer: engole.
      falhou: function (onde, e) {
        try {
          var msg;
          try { msg = String((e && e.message) || e); } catch (_) { msg = 'erro sem texto'; }
          canal.postMessage(JSON.stringify({ t: 'erro', onde: onde, msg: msg.slice(0, 300) }));
        } catch (_) { /* sem saída: o espelho jamais quebra a página */ }
      },
    };
    return ctx;
  }

  // Roda uma seção sem deixar nada escapar: se a instalação dela falhar, a página abre do mesmo jeito.
  function tentar(ctx, onde, fn) {
    try {
      fn();
    } catch (e) {
      ctx.falhou(onde, e);
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 3. Espelho de escrita do localStorage (spec 6.3, item 3)
  // ---------------------------------------------------------------------------------------------------------------

  // Embrulha Storage.prototype.setItem e removeItem, e SÓ quando `this` é o localStorage real capturado no início
  // (`ls`, ver capturarLS): o sessionStorage (onde o MSAL guarda o que importa) usa o mesmo prototype e fica de fora.
  // getItem, key, length e Object.keys(localStorage) ficam intocados: o real continua completo, e as faxinas da
  // página e o MSAL continuam enxergando tudo.
  function instalarEspelhoLS(ctx, ls) {
    var win = ctx.win;
    var lsReal = ls.real;
    var proto = ls.proto;
    var setItemOriginal = ls.setItem;
    var removeItemOriginal = ls.removeItem;
    // Guardados agora, atados à janela: a página pode trocar o setTimeout depois, e chamar a função solta daria
    // "Illegal invocation" no navegador.
    var agendar = win.setTimeout.bind(win);
    var cancelar = win.clearTimeout.bind(win);

    // chave -> { classe, v }. Só gravações: uma exclusão não espera (ver aoRemover). Map mantém a ordem de chegada,
    // e gravar a mesma chave de novo só troca o valor: vence a última.
    var pendentes = new Map();
    var timer = null;

    // Manda o lote agora: primeiro o trabalho, depois o cache (se a página for morta no meio, o que não volta da
    // rede já foi). Chamado pelo temporizador, pelo visibilitychange/pagehide e pelo `descarregar` do `instalar`.
    function descarregar() {
      try {
        if (timer !== null) { cancelar(timer); timer = null; }
        if (!pendentes.size) return;
        var lote = Array.from(pendentes.entries());
        pendentes.clear();
        var classes = ['trabalho', 'cache'];
        for (var c = 0; c < classes.length; c++) {
          for (var i = 0; i < lote.length; i++) {
            if (lote[i][1].classe === classes[c]) ctx.enviar({ t: 'ls', k: lote[i][0], v: lote[i][1].v }, 'lote');
          }
        }
      } catch (e) {
        ctx.falhou('lote', e);
      }
    }

    function aoGravar(chave, valor) {
      var classe = classificar(chave);
      if (!classe) return;
      if (classe === 'fila') {
        // A fila é o que o técnico não pode perder: vai agora, sem lote.
        ctx.enviar({ t: 'ls', k: chave, v: valor }, 'setItem');
        return;
      }
      pendentes.set(chave, { classe: classe, v: valor });
      if (timer === null) {
        timer = agendar(function () { timer = null; descarregar(); }, ESPERA_LOTE_MS);
      }
    }

    // A exclusão vai na hora e derruba a gravação ainda não enviada da mesma chave. Esperar o lote seria o lado
    // perigoso do atraso: se a página for morta nesses 400 ms, o nativo ficaria com uma chave que a página já
    // apagou (uma gc_zl_pend já enviada, devolvida depois, reenviaria registros). Atrasar uma gravação só perde o
    // último pedaço; atrasar uma exclusão ressuscita dado.
    function aoRemover(chave) {
      var classe = classificar(chave);
      if (!classe) return;
      pendentes.delete(chave);
      ctx.enviar({ t: 'lsDel', k: chave }, 'removeItem');
    }

    // O original vem sempre primeiro, e a exceção dele sobe intacta (nada de try em volta): o `lsS` e o despejo da
    // página dependem do QuotaExceededError chegar a eles, e uma gravação que falhou não pode ser espelhada.
    // Só o que vem depois do original é protegido, porque aí qualquer erro é nosso.
    proto.setItem = function setItem(chave, valor) {
      var resultado = setItemOriginal.apply(this, arguments);
      if (this === lsReal) {
        try { aoGravar(String(chave), String(valor)); } catch (e) { ctx.falhou('setItem', e); }
      }
      return resultado;
    };

    proto.removeItem = function removeItem(chave) {
      var resultado = removeItemOriginal.apply(this, arguments);
      if (this === lsReal) {
        try { aoRemover(String(chave)); } catch (e) { ctx.falhou('removeItem', e); }
      }
      return resultado;
    };

    // A página vai sumir ou ir para o fundo: o que está agrupado sai agora. O visibilitychange 'hidden' é o sinal
    // confiável no celular (o pagehide nem sempre dispara quando o sistema mata o app em segundo plano).
    var doc = win.document;
    if (doc && typeof doc.addEventListener === 'function') {
      doc.addEventListener('visibilitychange', function () {
        if (doc.visibilityState === 'hidden') descarregar();
      });
    }
    if (typeof win.addEventListener === 'function') {
      win.addEventListener('pagehide', function () { descarregar(); });
    }

    ctx.descarregadores.push(descarregar);
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 4. Sentinela do localStorage, versões da fila e restauração (spec 6.4, "Quando restaurar" e "A fila tem versões")
  // ---------------------------------------------------------------------------------------------------------------

  // A sentinela é uma chave da casca que a página nunca grava (o classificar a deixa fora do espelho). O valor não
  // importa, só a presença. Sozinha, a falta dela NÃO prova perda: a página pode apagá-la. O despejo por tamanho do
  // `lsS` do campo.html (quando a fila não cabe, derruba da maior chave para a menor tudo que o `_lsProtegida` não
  // protege, e a sentinela não está protegida) a leva junto com o aparelho cheio, e o site não muda. A perda de verdade
  // (corrupção, troca de WebView, dados apagados) leva o localStorage da origem inteiro; o despejo da página deixa no
  // mínimo o que ela protege (msal e as chaves de trabalho). Por isso a perda é "sem sentinela E sem chave nenhuma".
  var VALOR_SENTINELA = '1';

  // Captura o localStorage real e os métodos originais UMA vez, antes de qualquer embrulho e de qualquer script da
  // página. A restauração grava por eles (pelos embrulhados, ecoaria ao nativo o que acabou de vir dele) e o espelho
  // os chama por baixo do embrulho.
  function capturarLS(win) {
    var real = win.localStorage; // pode lançar SecurityError (armazenamento negado): o `tentar` de fora engole
    var proto = win.Storage && win.Storage.prototype;
    if (!real || !proto) return null;
    return {
      real: real,
      proto: proto,
      getItem: proto.getItem,
      setItem: proto.setItem,
      removeItem: proto.removeItem,
    };
  }

  // Grava pelo original e diz se coube. Nunca lança: a cota estourada (ou qualquer recusa) vira `false`, e quem chama
  // decide o que fazer com a chave que ficou de fora.
  function gravarOriginal(ls, chave, valor) {
    try {
      ls.setItem.call(ls.real, chave, valor);
      return true;
    } catch (e) {
      return false;
    }
  }

  // Para esta carga e pede ao nativo o retrato completo; o nativo recarrega a página com ele (modo 'restaurar').
  // A marca vem antes do stop: se o stop falhar, a página roda, mas o espelho continua desligado até a próxima carga.
  function pararCarga(ctx, motivo) {
    ctx.cargaParada = true;
    try { ctx.win.stop(); } catch (e) { ctx.falhou('stop', e); }
    ctx.enviar({ t: 'precisoRetrato', motivo: motivo }, 'precisoRetrato');
  }

  // Regrava a sentinela pelo original. Se não couber (o aparelho cheio), avisa sem chave e segue: a falta dela não
  // para carga nenhuma enquanto a WebView tiver chaves (ver VALOR_SENTINELA), e ela volta numa carga com espaço.
  // A casca nunca apaga chave da página para abrir espaço para ela.
  function gravarSentinela(ctx, ls) {
    if (gravarOriginal(ls, SENTINELA_LS, VALOR_SENTINELA)) return true;
    ctx.falhou('sentinela', new Error('a sentinela não coube no localStorage'));
    return false;
  }

  // Decide, antes da página, o que esta carga faz com o localStorage:
  //   modo 'restaurar'                                → devolve o que o retrato traz (restaurarLS);
  //   sem sentinela, sem espelho                      → instalação nova: grava a sentinela e avisa ('sentinela');
  //   sem sentinela, com espelho, WebView vazia       → perda: para e pede o retrato;
  //   sem sentinela, com espelho, WebView com chaves  → a faxina da página levou a sentinela: regrava e segue;
  //   com sentinela (ou depois de regravá-la)         → compara a fila da WebView com as versões do nativo.
  // Fora da restauração, esta seção nunca escreve na página além da sentinela.
  function conferirSentinelaEFila(ctx, ls) {
    var retrato = ctx.retrato;
    if (retrato.modo === 'restaurar') {
      restaurarLS(ctx, ls);
      return;
    }

    if (ls.getItem.call(ls.real, SENTINELA_LS) === null) {
      // Só `true` vale: um retrato torto não pode parar a carga do técnico.
      if (retrato.temEspelho !== true) {
        if (gravarSentinela(ctx, ls)) ctx.enviar({ t: 'sentinela' }, 'sentinela');
        return;
      }
      // Lido agora, antes de qualquer script da página: nesta hora só a perda de verdade deixa o localStorage vazio.
      if (ls.real.length === 0) {
        pararCarga(ctx, 'perda');
        return;
      }
      // Não é perda: o despejo por tamanho do `lsS` levou a sentinela com o aparelho cheio. Tomar isso por perda
      // pararia a carga justo com o armazenamento cheio, a restauração não teria onde pôr a sentinela, e a carga
      // seguinte da mesma partida pediria o retrato de novo (o nativo atende um por partida): a página ficaria em
      // branco. O que a WebView tem é o que a página deixou; a fila ainda é conferida abaixo, como em qualquer carga.
      // O nativo já marcou o espelho (`temEspelho`), então não há `sentinela` a mandar.
      gravarSentinela(ctx, ls);
    }

    // As versões da fila que o nativo guardou, mais nova primeiro (até 3), em fnv1a32.
    var versoes = Array.isArray(retrato.filaHashes) ? retrato.filaHashes : [];
    var atual = ls.getItem.call(ls.real, CHAVE_FILA);
    if (atual === null) {
      // Ruling R5: a página que apaga a fila manda lsDel, e o nativo apaga as versões junto. Versões no nativo sem fila
      // na WebView é a fila que se perdeu sem a página saber: trata como fila atrasada. Custa um recarregar a mais
      // numa partida rara; o contrário perderia a fila inteira.
      if (versoes.length) pararCarga(ctx, 'fila');
      return;
    }
    var hash = fnv1a32(atual);
    // A mais nova vem antes: a página pode ter gravado A, B e A de novo, e a WebView com A está em dia.
    if (hash === versoes[0]) return;
    if (versoes.indexOf(hash) > 0) {
      // Bate com uma versão antiga: a WebView voltou no tempo (despejo parcial) e o nativo está à frente.
      pararCarga(ctx, 'fila');
      return;
    }
    // Não bate com nenhuma: a WebView está à frente (o nativo perdeu a última gravação, ou não tem versão nenhuma).
    ctx.enviar({ t: 'ls', k: CHAVE_FILA, v: atual }, 'fila');
  }

  // Devolve ao localStorage o que o retrato completo traz, pelos métodos originais (sem eco ao nativo, que já tem
  // tudo isso). Ordem: a fila, depois o trabalho, depois o cache, chave por chave; a que não couber vai para a lista
  // `naoCouberam` e a próxima ainda é tentada (uma pequena pode caber depois de uma grande). Por último a sentinela.
  // Com motivo 'fila' o nativo manda `ls` vazio, e só a fila é regravada.
  // Do `ls` do retrato só entra o que o classificar diz que é trabalho ou cache: msal.*, a sentinela, a própria fila
  // (que vem no campo `fila`) e as preferências ficam de fora, mesmo que o nativo as mande por engano.
  function restaurarLS(ctx, ls) {
    var retrato = ctx.retrato;
    var origem = retrato.ls && typeof retrato.ls === 'object' ? retrato.ls : {};
    var naoCouberam = [];
    var chaves = 0;
    var fila = false;

    if (typeof retrato.fila === 'string') {
      fila = gravarOriginal(ls, CHAVE_FILA, retrato.fila);
      if (!fila) naoCouberam.push(CHAVE_FILA);
    }

    var nomes = Object.keys(origem);
    var classes = ['trabalho', 'cache'];
    for (var c = 0; c < classes.length; c++) {
      for (var i = 0; i < nomes.length; i++) {
        var chave = nomes[i];
        var valor = origem[chave];
        if (classificar(chave) !== classes[c] || typeof valor !== 'string') continue;
        if (gravarOriginal(ls, chave, valor)) chaves++;
        else naoCouberam.push(chave);
      }
    }

    // Por último a sentinela. Se não couber, o que voltou fica (inclusive o cache, que o técnico usa sem sinal): a
    // carga seguinte acha a WebView com chaves e não toma a falta da sentinela por perda.
    gravarSentinela(ctx, ls);

    ctx.enviar({ t: 'restaurado', chaves: chaves, naoCouberam: naoCouberam, fila: fila }, 'restaurado');
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Ponto único de entrada
  // ---------------------------------------------------------------------------------------------------------------

  // Liga cada seção e devolve { pronto, descarregar }. `pronto` resolve quando o que precisa terminar antes da
  // página já terminou (as seções acrescentam suas esperas em ctx.prontos). `descarregar()` manda já o que está
  // agrupado, em todas as seções.
  // Sem canal (o nativo não injetou window.gcCasca) não há para quem espelhar: não embrulha nada e não lança.
  function instalar(win, canal, retrato) {
    var vazio = { pronto: Promise.resolve(), descarregar: function () {} };
    if (!win || !canal || typeof canal.postMessage !== 'function') return vazio;

    var ctx = criarContexto(win, canal, retrato || {});
    tentar(ctx, 'espelho-ls', function () {
      var ls = capturarLS(win);
      if (!ls) return;
      // A 4 antes da 3: a sentinela e a restauração gravam pelos originais e decidem se esta carga para; só depois,
      // e só se ela seguir, o espelho liga. Se a 4 falhar por um erro nosso, a página abre com o espelho ligado.
      tentar(ctx, 'sentinela', function () { conferirSentinelaEFila(ctx, ls); });
      if (!ctx.cargaParada) instalarEspelhoLS(ctx, ls);
    });

    return {
      pronto: Promise.all(ctx.prontos).then(function () { return undefined; }),
      descarregar: function () {
        for (var i = 0; i < ctx.descarregadores.length; i++) {
          try { ctx.descarregadores[i](); } catch (e) { ctx.falhou('descarregar', e); }
        }
      },
    };
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Exportação (Node, nos testes) ou arranque (WebView)
  // ---------------------------------------------------------------------------------------------------------------

  if (typeof module === 'object' && module !== null && module.exports) {
    module.exports = {
      instalar: instalar,
      classificar: classificar,
      fnv1a32: fnv1a32,
      CLASSES: CLASSES,
      CHAVE_FILA: CHAVE_FILA,
      SENTINELA_LS: SENTINELA_LS,
      BANCO_SENTINELA: BANCO_SENTINELA,
    };
  } else if (typeof window !== 'undefined') {
    instalar(window, window.gcCasca, window.__GC_RETRATO__ || {});
  }
})();
