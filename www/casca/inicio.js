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
 *   5. espelho e restauração do IndexedDB das fotos              (Task 4; depois da 3 e da 4)
 *   6. window.open: o login da Microsoft e o "Conectar Fracttal"  (Task 4)
 */
(function () {
  'use strict';

  // ---------------------------------------------------------------------------------------------------------------
  // 1. Constantes e classificação das chaves
  // ---------------------------------------------------------------------------------------------------------------

  var CHAVE_FILA = 'gc_campo_queue';
  var SENTINELA_LS = 'gc_casca_sentinela';
  var MARCA_PERDA = 'gc_casca_perda_pendente';
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
  // Fora das duas listas: msal.*, tokens, gc_erros_js, gc_vivo, gc_tela, preferências e contadores, e as chaves da
  // própria casca (gc_casca_*: a sentinela e a marca da perda), que nunca vão ao nativo nem voltam dele. A fila
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
      // Ligado pela seção 4 quando esta carga não pode espelhar: foi parada para pedir o retrato completo (perda ou
      // fila atrasada), ou é uma perda que a trava da sessão não deixou parar. Daí até a próxima carga nenhum espelho
      // liga: a página que ainda rodar está vazia ou atrasada, e espelhá-la gravaria esse estado por cima do que o
      // nativo guardou. O `instalar` também o liga quando a marca da perda está no localStorage. Toda seção que
      // espelha confere este campo.
      espelhoDesligado: false,

      // Ligado pela seção 4 quando o win.stop() não lançou: a página não roda, o nativo a recarrega com o retrato
      // completo, e a seção 5 não confere nem restaura o IndexedDB nesta carga. Se o stop lançou, a página roda até a
      // recarga, sem espelho, e a seção 5 a trata como qualquer carga sem espelho.
      cargaParada: false,

      // O localStorage e a memória da sessão capturados pela seção 4 (capturarLS), ou null. A seção 5 guarda ali a
      // marca da reconciliação.
      ls: null,

      // Manda uma mensagem ao nativo e diz se ela saiu. Nunca lança: se o canal falhar, avisa por `falhou` e devolve
      // false (a seção 4 só lembra a fila como espelhada se a mensagem saiu).
      // O canal é lido a cada chamada (não guardado), porque é o objeto que o nativo injetou e pode ser trocado.
      enviar: function (mensagem, onde) {
        try {
          canal.postMessage(JSON.stringify(mensagem));
          return true;
        } catch (e) {
          ctx.falhou(onde, e);
          return false;
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

      // Passa a `fn` cada mensagem do nativo (o JavaScriptReplyProxy responde pelo `onmessage` do mesmo objeto, com
      // `event.data` em JSON). Só a restauração das fotos (seção 5) ouve. O texto que não for JSON vira um aviso sem
      // o conteúdo: a mensagem do JSON.parse cita o texto, e ele pode ter nome de chave.
      ouvirNativo: function (fn) {
        canal.onmessage = function (evento) {
          var mensagem;
          try {
            var dado = evento && evento.data;
            mensagem = typeof dado === 'string' ? JSON.parse(dado) : dado;
          } catch (e) {
            ctx.falhou('canal', new Error('mensagem do nativo que não é JSON'));
            return;
          }
          try { fn(mensagem); } catch (e) { ctx.falhou('canal', e); }
        };
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
        // A fila é o que o técnico não pode perder: vai agora, sem lote (e fica lembrada na sessão, ver a seção 4).
        espelharFila(ctx, ls, valor, 'setItem');
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

  // QUANDO É PERDA: sentinela ausente E localStorage da WebView vazio, lido no começo do documento, antes de qualquer
  // script da página. É uma decisão que desvia da spec (6.4 dizia "a falta de qualquer das duas sentinelas dispara a
  // restauração"), tomada porque a página também apaga a sentinela: o despejo por tamanho do `lsS` do campo.html
  // (quando a fila não cabe, derruba da maior chave para a menor tudo que o `_lsProtegida` não protege, e a sentinela
  // não está protegida) a leva com o aparelho cheio, e o site não muda. Tomar isso por perda pararia a carga justo sem
  // espaço, a restauração não teria onde pôr a sentinela e a carga seguinte pararia de novo: página em branco. A perda
  // de verdade (corrupção, troca de WebView, dados apagados) leva o localStorage da origem inteiro; o despejo da página
  // deixa no mínimo o que ela protege (msal e as chaves de trabalho). Custo aceito: um localStorage apagado só em
  // parte, com o processo vivo, não é visto como perda, e só a fila volta (pelas versões).
  //
  // A sentinela é uma chave da casca que a página nunca grava (o classificar a deixa fora do espelho). O valor não
  // importa, só a presença.
  var VALOR_SENTINELA = '1';

  // A MARCA DA PERDA. A regra do "localStorage vazio" só enxerga a primeira carga depois da perda: se a página rodar
  // antes de a restauração chegar, ela grava, e a carga seguinte acha chaves sem sentinela, que é a cara da faxina do
  // `lsS`. Caso que a criou: a perda que a trava da sessão (SESSAO_PAROU, abaixo) não deixou parar de novo; a página
  // gravou '[]' na fila e o gc_vivo, e a carga seguinte, tomada por faxina, ligaria o espelho e mandaria o '[]' por
  // cima da fila que o nativo guardou. Por isso, ao ver a perda, o script grava MARCA_PERDA (pelo original, antes do
  // stop) e, enquanto ela estiver no localStorage, toda carga é perda, tenha a WebView chaves ou sentinela, e roda
  // sem espelho. Só a restauração de uma perda a apaga; a da fila não mexe nela. A página nunca a grava nem a apaga
  // de propósito (o classificar a deixa fora do espelho e da restauração); o despejo do `lsS` vai da maior chave para a
  // menor e só chegaria a ela, que é pequena, depois de derrubar todas as maiores que ele não protege (os caches).
  var VALOR_MARCA = '1';

  // A MEMÓRIA DA SESSÃO. O nativo registra o retrato leve uma vez por partida, e ele vale para toda carga dela: as
  // versões da fila que ele traz envelhecem. Caso: o técnico abre o app com pendência (versões ['[x]', '[]']), a página
  // envia e grava '[]' (espelhada, o nativo a põe na frente); uma recarga na mesma partida (loginRedirect do MSAL,
  // atualização do service worker, 'Tentar de novo') acharia '[]' igual a uma versão antiga e pararia a carga à toa.
  // O sessionStorage da origem dura o mesmo que a partida (a WebView o mantém entre as cargas e o esquece ao fechar),
  // e guarda três chaves, sempre pelo setItem original (o espelho só olha o localStorage):
  //   SESSAO_FILA     fnv1a32 da última fila que o script espelhou ou restaurou nesta sessão: o nativo a tem como a
  //                   mais nova, então a WebView com ela está em dia, diga o retrato o que disser;
  //   SESSAO_PAROU    esta sessão já parou uma carga. O script nunca para duas: se o nativo não atendesse o segundo
  //                   pedido, a página ficaria em branco até matar o app;
  //   SESSAO_RETRATO  o id do último retrato completo aplicado: o mesmo retrato, de novo, regravaria sem espelhar por
  //                   cima do que a página fez depois dele.
  // Sem sessionStorage (falta, lança, ou a página o limpou no "limpar só o login"), vale a regra de antes: só as
  // versões do retrato.
  var SESSAO_FILA = 'gc_casca_fila_sessao';
  var SESSAO_PAROU = 'gc_casca_parou';
  var SESSAO_RETRATO = 'gc_casca_retrato_aplicado';

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
      sessao: capturarSessao(win),
    };
  }

  // O sessionStorage da origem, ou null. Ler `win.sessionStorage` pode lançar SecurityError, como o localStorage.
  function capturarSessao(win) {
    try { return win.sessionStorage || null; } catch (e) { return null; }
  }

  // Lê e grava a memória da sessão pelos métodos originais do Storage. Nunca lançam: sem sessionStorage (ou com um que
  // recusa), ler dá null, como se nada tivesse sido lembrado, e gravar não faz nada.
  function lerSessao(ls, chave) {
    try { return ls.sessao ? ls.getItem.call(ls.sessao, chave) : null; } catch (e) { return null; }
  }
  function gravarSessao(ls, chave, valor) {
    try { if (ls.sessao) ls.setItem.call(ls.sessao, chave, valor); } catch (e) { /* sem memória: a regra de antes */ }
  }

  // Manda a fila da WebView ao nativo e, se a mensagem saiu, lembra dela na sessão (SESSAO_FILA). É o caminho único da
  // fila para o nativo: o espelho (seção 3), a WebView à frente e a segunda parada recusada passam por aqui.
  function espelharFila(ctx, ls, valor, onde) {
    if (ctx.enviar({ t: 'ls', k: CHAVE_FILA, v: valor }, onde)) gravarSessao(ls, SESSAO_FILA, fnv1a32(valor));
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
  // O espelho é desligado antes do stop: se o stop falhar, a página roda, mas sem espelho até a próxima carga.
  // Uma vez por sessão (SESSAO_PAROU): se o nativo não atendesse o segundo pedido, a página ficaria em branco até matar
  // o app. Na segunda, a carga segue, sem stop e sem pedido, e o nativo é avisado (sem chave nem valor). O espelho:
  //   'fila'   fica ligado, e a fila da WebView vai ao nativo (ela vence, porque é o que a página vai usar). Sem fila
  //            na WebView, só o aviso: nunca se manda apagar a fila do nativo;
  //   'perda'  fica desligado na carga inteira: a cópia boa está no nativo, e o que a página gravar agora não pode ir
  //            por cima dela. A sentinela não volta e a marca da perda fica: a partida seguinte pede o retrato.
  function pararCarga(ctx, ls, motivo) {
    if (lerSessao(ls, SESSAO_PAROU) !== null) {
      if (motivo === 'perda') {
        // A marca da perda já desligaria o espelho (o `instalar` a confere), mas ela pode não ter cabido.
        ctx.espelhoDesligado = true;
      } else {
        var atual = ls.getItem.call(ls.real, CHAVE_FILA);
        if (atual !== null) espelharFila(ctx, ls, atual, 'fila');
      }
      ctx.falhou(motivo, new Error('conferência ignorada: já parou nesta sessão'));
      return;
    }
    gravarSessao(ls, SESSAO_PAROU, motivo);
    ctx.espelhoDesligado = true;
    try {
      ctx.win.stop();
      ctx.cargaParada = true;
    } catch (e) {
      ctx.falhou('stop', e);
    }
    ctx.enviar({ t: 'precisoRetrato', motivo: motivo }, 'precisoRetrato');
  }

  // A perda: grava a marca (antes do stop; ver A MARCA DA PERDA) e para a carga, ou, com a trava, segue sem espelho.
  // Se a marca não couber (o localStorage recusa até uma chave pequena), avisa sem chave e segue do mesmo jeito: sem
  // ela, vale só a regra do localStorage vazio.
  function declararPerda(ctx, ls) {
    if (!gravarOriginal(ls, MARCA_PERDA, VALOR_MARCA)) {
      ctx.falhou('marca', new Error('a marca da perda não coube no localStorage'));
    }
    pararCarga(ctx, ls, 'perda');
  }

  function temMarcaDePerda(ls) {
    return ls.getItem.call(ls.real, MARCA_PERDA) !== null;
  }

  // O id do retrato completo, quando é texto não vazio (o nativo gera um a cada retrato); senão null, e o retrato é
  // aplicado toda vez, como antes da regra.
  function idDoRetrato(retrato) {
    return typeof retrato.id === 'string' && retrato.id !== '' ? retrato.id : null;
  }

  // Regrava a sentinela pelo original. Se não couber (o aparelho cheio), avisa sem chave e segue: a falta dela não
  // para carga nenhuma enquanto a WebView tiver chaves (ver QUANDO É PERDA), e ela volta numa carga com espaço.
  // A casca nunca apaga chave da página para abrir espaço para ela.
  function gravarSentinela(ctx, ls) {
    if (gravarOriginal(ls, SENTINELA_LS, VALOR_SENTINELA)) return true;
    ctx.falhou('sentinela', new Error('a sentinela não coube no localStorage'));
    return false;
  }

  // Decide, antes da página, o que esta carga faz com o localStorage:
  //   modo 'restaurar' com id novo nesta sessão        → devolve o que o retrato traz (restaurarLS);
  //   o mesmo retrato completo de novo (mesmo id)       → segue como um leve (ele traz os mesmos campos);
  //   marca da perda no localStorage                   → perda, tenha a WebView chaves ou sentinela;
  //   sem sentinela, sem espelho                       → instalação nova: grava a sentinela, avisa ('sentinela') e
  //                                                       confere a fila (a que já estiver na WebView é espelhada);
  //   sem sentinela, com espelho, WebView vazia        → perda: grava a marca, para e pede o retrato;
  //   sem sentinela, com espelho, WebView com chaves   → a faxina da página levou a sentinela: regrava e confere
  //                                                       a fila;
  //   com sentinela, sem espelho                       → reenvia 'sentinela' e confere a fila;
  //   com sentinela, com espelho                       → confere a fila.
  // Fora da restauração, esta seção nunca escreve na página além da sentinela e da marca da perda.
  function conferirSentinelaEFila(ctx, ls) {
    var retrato = ctx.retrato;
    if (retrato.modo === 'restaurar') {
      var id = idDoRetrato(retrato);
      if (id === null || lerSessao(ls, SESSAO_RETRATO) !== id) {
        restaurarLS(ctx, ls);
        return;
      }
    }

    // A perda que ainda não foi restaurada vence o resto, inclusive a regra da faxina abaixo (ver A MARCA DA PERDA).
    if (temMarcaDePerda(ls)) {
      declararPerda(ctx, ls);
      return;
    }

    // Só `true` vale: um retrato torto não pode parar a carga do técnico.
    var temEspelho = retrato.temEspelho === true;
    if (ls.getItem.call(ls.real, SENTINELA_LS) === null) {
      if (!temEspelho) {
        if (gravarSentinela(ctx, ls)) ctx.enviar({ t: 'sentinela' }, 'sentinela');
      } else if (ls.real.length === 0) {
        // Lido agora, antes de qualquer script da página: nesta hora só a perda de verdade deixa o localStorage vazio.
        // A marca gravada aqui faz as cargas seguintes também verem a perda, mesmo depois de a página gravar alguma
        // coisa (na carga que a trava da sessão não deixou parar), até a restauração chegar.
        declararPerda(ctx, ls);
        return;
      } else {
        // Não é perda: o despejo por tamanho do `lsS` levou a sentinela com o aparelho cheio (ver QUANDO É PERDA). O
        // que a WebView tem é o que a página deixou, e a fila é conferida abaixo, como em qualquer carga. O nativo já
        // marcou o espelho (`temEspelho`), então não há `sentinela` a mandar.
        gravarSentinela(ctx, ls);
      }
    } else if (!temEspelho) {
      // A sentinela está na WebView, mas o nativo não marcou o espelho (a `sentinela` da instalação se perdeu, ou o
      // banco dele foi recriado). Sem o reenvio, uma perda futura seria tomada por instalação nova e nada voltaria.
      // O nativo trata a mensagem repetida como a primeira.
      ctx.enviar({ t: 'sentinela' }, 'sentinela');
    }

    conferirFila(ctx, ls);
  }

  // Compara a fila da WebView com as versões do retrato (mais nova primeiro, até 3, em fnv1a32) e com a última que esta
  // sessão espelhou ou restaurou (SESSAO_FILA).
  function conferirFila(ctx, ls) {
    var versoes = Array.isArray(ctx.retrato.filaHashes) ? ctx.retrato.filaHashes : [];
    var atual = ls.getItem.call(ls.real, CHAVE_FILA);
    if (atual === null) {
      // A página que apaga a fila manda lsDel, e o nativo apaga as versões junto. Versões no nativo sem fila na
      // WebView é, então, a fila que se perdeu sem a página saber: trata como fila atrasada. Custa um recarregar a mais
      // numa partida rara; o contrário perderia a fila inteira.
      if (versoes.length) pararCarga(ctx, ls, 'fila');
      return;
    }
    var hash = fnv1a32(atual);
    // A mais nova vem antes: a página pode ter gravado A, B e A de novo, e a WebView com A está em dia.
    if (hash === versoes[0]) return;
    // A última que chegou ao nativo nesta sessão: ele a tem como a mais nova, mesmo que o retrato (registrado no começo
    // da partida) ainda não saiba.
    if (hash === lerSessao(ls, SESSAO_FILA)) return;
    if (versoes.indexOf(hash) > 0) {
      // Bate com uma versão antiga: a WebView voltou no tempo (despejo parcial) e o nativo está à frente.
      pararCarga(ctx, ls, 'fila');
      return;
    }
    // Não bate com nenhuma: a WebView está à frente (o nativo perdeu a última gravação, ou não tem versão nenhuma).
    espelharFila(ctx, ls, atual, 'fila');
  }

  // Devolve ao localStorage o que o retrato completo traz, pelos métodos originais (sem eco ao nativo, que já tem
  // tudo isso). Ordem: a fila, depois o trabalho, depois o cache, chave por chave; a que não couber vai para a lista
  // `naoCouberam` e a próxima ainda é tentada (uma pequena pode caber depois de uma grande). Depois sai a marca da
  // perda (só no retrato da perda) e, por último, entra a sentinela.
  // A fila que voltou e o id do retrato ficam na memória da sessão (SESSAO_FILA e SESSAO_RETRATO).
  // O `motivo` do retrato é o do pedido que ele atende. Com 'fila' o nativo manda `ls` vazio, e só a fila é regravada.
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
      if (fila) gravarSessao(ls, SESSAO_FILA, fnv1a32(retrato.fila));
      else naoCouberam.push(CHAVE_FILA);
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

    // A perda está respondida: a marca sai pelo original (sem eco), depois do que voltou (se a carga morrer no meio, a
    // seguinte ainda vê a perda e pede de novo) e antes da sentinela, que ganha o espaço dela. O retrato da fila não
    // mexe na marca: uma perda pendente continua pendente. Sem `motivo` conta como perda, porque uma marca que nunca
    // saísse deixaria toda carga sem espelho, partida após partida.
    if (retrato.motivo !== 'fila') {
      try { ls.removeItem.call(ls.real, MARCA_PERDA); } catch (e) { ctx.falhou('marca', e); }
    }

    // Por último a sentinela. Se não couber, o que voltou fica (inclusive o cache, que o técnico usa sem sinal): a
    // carga seguinte acha a WebView com chaves e não toma a falta da sentinela por perda.
    gravarSentinela(ctx, ls);

    // Aplicado: o mesmo retrato numa recarga desta sessão segue como leve (ver SESSAO_RETRATO).
    var id = idDoRetrato(retrato);
    if (id !== null) gravarSessao(ls, SESSAO_RETRATO, id);

    ctx.enviar({ t: 'restaurado', chaves: chaves, naoCouberam: naoCouberam, fila: fila }, 'restaurado');
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 5. Espelho e restauração do IndexedDB das fotos (spec 6.3, item 4, e 6.4)
  // ---------------------------------------------------------------------------------------------------------------

  // O banco e a store do campo.html (`IDB_NAME`, `IDB_STORE`): fotos e vídeos das OS, das rondas e da APR, o espelho do
  // rascunho da ronda (rdraft:) e a programação offline. A store não tem keyPath: a chave vem sempre no put.
  var BANCO_FOTOS = 'gc_campo_fotos';
  var STORE_FOTOS = 'fotos';

  // Blob e File vão em pedaços de 512 KB: o vídeo chega a 15 MB e não pode virar um base64 inteiro na memória de um
  // aparelho de 4 GB. O nativo junta os pedaços pelo `id` e só cria a entrada no último (i === n-1).
  var TAMANHO_PEDACO = 524288;

  // Quanto a página espera a restauração das fotos antes de abrir o banco do mesmo jeito.
  var ESPERA_NATIVO_MS = 15000;

  // Memória da sessão (ver A MEMÓRIA DA SESSÃO, na seção 4): a reconciliação já rodou nesta sessão da WebView.
  var SESSAO_IDB = 'gc_casca_idb_reconciliado';

  // O banco sentinela da casca (BANCO_SENTINELA, `gc_casca`) quer dizer: o IndexedDB desta WebView está em dia com o
  // nativo, sob o espelho. É ele que autoriza a reconciliação a mandar apagar no nativo o que falta na WebView. Por
  // isso só nasce numa carga com o espelho ligado (na instalação nova, ou depois de uma restauração completa), e sai
  // numa carga sem espelho: a página que roda sem espelho (a perda que a trava da sessão não deixou parar) roda com o
  // localStorage vazio, e a faxina de órfãos dela (`idbKeys` + `idbDel` do campo.html) apaga as fotos que nenhum
  // registro cita. Com o banco sentinela de pé, a próxima reconciliação mandaria apagar no nativo a cópia boa delas.

  // Captura o IndexedDB e os métodos originais UMA vez, antes de qualquer embrulho e de qualquer script da página. A
  // restauração grava pelo put original (pelo embrulhado, ecoaria ao nativo o que acabou de vir dele).
  function capturarIDB(win) {
    var fabrica = win.indexedDB; // pode lançar SecurityError (armazenamento negado): o `tentar` de fora engole
    var proto = win.IDBObjectStore && win.IDBObjectStore.prototype;
    if (!fabrica || typeof fabrica.open !== 'function' || !proto) return null;
    return {
      fabrica: fabrica,
      abrir: fabrica.open,
      apagarBanco: fabrica.deleteDatabase,
      listarBancos: fabrica.databases,
      proto: proto,
      put: proto.put,
      apagar: proto['delete'],
      // Atados à janela, como no espelho do localStorage.
      agendar: win.setTimeout.bind(win),
      cancelar: win.clearTimeout.bind(win),
    };
  }

  function ehDasFotos(store) {
    return !!(store && store.name === STORE_FOTOS && store.transaction && store.transaction.db &&
      store.transaction.db.name === BANCO_FOTOS);
  }

  function ehBlob(win, valor) {
    return typeof win.Blob === 'function' && valor instanceof win.Blob;
  }

  // A operação que vai ao nativo para um valor do banco: texto, Blob/File (em pedaços, no envio) ou JSON. O JSON é feito
  // agora, no put: o banco guarda a cópia do momento do put, e a página pode mudar o objeto antes do complete. O erro
  // do JSON.stringify cita nomes de propriedade; vai um texto nosso no lugar.
  function representar(win, chave, valor) {
    if (typeof valor === 'string') return { t: 'put', k: chave, tipo: 'texto', v: valor };
    if (ehBlob(win, valor)) return { t: 'put', k: chave, blob: valor };
    var json;
    try { json = JSON.stringify(valor); } catch (e) { json = undefined; }
    if (typeof json !== 'string') throw new Error('valor sem representação em JSON: fica fora do espelho');
    return { t: 'put', k: chave, tipo: 'json', v: json };
  }

  // Base64 de um ArrayBuffer, em fatias de 32 K caracteres (o String.fromCharCode com 512 K argumentos estoura a pilha).
  function paraBase64(win, buffer) {
    var bytes = new Uint8Array(buffer);
    var partes = [];
    for (var p = 0; p < bytes.length; p += 0x8000) {
      partes.push(String.fromCharCode.apply(null, bytes.subarray(p, p + 0x8000)));
    }
    return win.btoa(partes.join(''));
  }

  function deBase64(win, texto) {
    var binario = win.atob(texto);
    var bytes = new Uint8Array(binario.length);
    for (var i = 0; i < binario.length; i++) bytes[i] = binario.charCodeAt(i);
    return bytes;
  }

  function chavesDoNativo(retrato) {
    var lista = Array.isArray(retrato.idbChaves) ? retrato.idbChaves : [];
    return lista.filter(function (k) { return typeof k === 'string'; });
  }

  function lerSessaoCtx(ctx, chave) { return ctx.ls ? lerSessao(ctx.ls, chave) : null; }
  function gravarSessaoCtx(ctx, chave, valor) { if (ctx.ls) gravarSessao(ctx.ls, chave, valor); }

  // O envio ao nativo, numa fila só: as operações saem na ordem dos complete, e um Blob termina todos os pedaços antes
  // da operação seguinte. Caso: a página grava um vídeo e logo o apaga; com o idbDel no meio dos pedaços, o último
  // pedaço chegaria depois dele, e o nativo ficaria com o vídeo que a página apagou.
  function criarEnvioIDB(ctx) {
    var win = ctx.win;
    var cadeia = Promise.resolve();
    var contador = 0;
    // O id vira nome de arquivo no nativo (files/espelho/tmp/<id>.parte): só [a-z0-9-]. Único por gravação (o contador)
    // e entre cargas (o começo), porque as partes de uma carga que morreu no meio ficam lá até 24 h.
    var prefixo = Math.floor(Math.random() * 0x7fffffff).toString(36) + '-' + Date.now().toString(36);

    function enviarBlob(k, blob) {
      var id = prefixo + '-' + (++contador);
      var n = Math.max(1, Math.ceil(blob.size / TAMANHO_PEDACO)); // o vazio vai num pedaço só, vazio
      var mime = typeof blob.type === 'string' ? blob.type : '';
      function pedaco(i) {
        if (i >= n) return undefined;
        // Um pedaço por vez: só ele fica na memória, em bytes e em base64.
        return blob.slice(i * TAMANHO_PEDACO, (i + 1) * TAMANHO_PEDACO).arrayBuffer().then(function (buffer) {
          var saiu = ctx.enviar({ t: 'idbPedaco', k: k, id: id, i: i, n: n, mime: mime, b64: paraBase64(win, buffer) },
            'idbPedaco');
          // Com o canal fora, o resto não adianta: sem o último pedaço o nativo não cria a entrada. A reconciliação da
          // próxima partida manda de novo.
          return saiu ? pedaco(i + 1) : undefined;
        });
      }
      return pedaco(0);
    }

    function enviar(op) {
      if (op.t === 'del') {
        ctx.enviar({ t: 'idbDel', k: op.k }, 'idbDel');
        return undefined;
      }
      if (op.blob) return enviarBlob(op.k, op.blob);
      ctx.enviar({ t: 'idbPut', k: op.k, tipo: op.tipo, v: op.v }, 'idbPut');
      return undefined;
    }

    return {
      operacao: function (op) {
        cadeia = cadeia.then(function () { return enviar(op); }).then(null, function (e) { ctx.falhou('idbEnvio', e); });
      },
    };
  }

  // Embrulha IDBObjectStore.prototype.put e delete e espelha só o banco e a store das fotos. O original vem primeiro, e
  // a exceção dele sobe intacta; a operação fica anotada na transação e só vai ao nativo no `complete` dela: a
  // transação que aborta não gravou nada. get, getAll, getAllKeys e o resto ficam intocados.
  function instalarEspelhoIDB(ctx, idb, envio) {
    var putOriginal = idb.put;
    var apagarOriginal = idb.apagar;
    var porTransacao = new WeakMap(); // transação -> Map(chave -> última operação)

    function anotar(store, op) {
      var tx = store.transaction;
      var ops = porTransacao.get(tx);
      if (!ops) {
        ops = new Map();
        porTransacao.set(tx, ops);
        // Pelo addEventListener: o oncomplete é da página (o idbPut do campo.html resolve a promessa nele). Na
        // transação abortada este ouvinte nunca roda, e o WeakMap solta as operações junto com ela.
        tx.addEventListener('complete', function () {
          porTransacao['delete'](tx);
          ops.forEach(function (o) { envio.operacao(o); });
        });
      }
      // Na mesma transação vence a última operação de cada chave (é o que o complete deixou no banco).
      ops.set(op.k, op);
    }

    // As chaves do campo.html são texto; uma que não seja não tem como ir ao nativo (ele guarda por texto).
    function registrar(store, chave, criarOp, onde) {
      if (!ehDasFotos(store)) return;
      if (typeof chave !== 'string') {
        ctx.falhou(onde, new Error('chave que não é texto: fica fora do espelho'));
        return;
      }
      anotar(store, criarOp());
    }

    idb.proto.put = function put(valor, chave) {
      var resultado = putOriginal.apply(this, arguments);
      try {
        registrar(this, chave, function () { return representar(ctx.win, chave, valor); }, 'idbPut');
      } catch (e) {
        ctx.falhou('idbPut', e);
      }
      return resultado;
    };

    idb.proto['delete'] = function apagar(chave) {
      var resultado = apagarOriginal.apply(this, arguments);
      try {
        registrar(this, chave, function () { return { t: 'del', k: chave }; }, 'idbDel');
      } catch (e) {
        ctx.falhou('idbDel', e);
      }
      return resultado;
    };
  }

  // Decide, antes de a página abrir o banco das fotos, o que esta carga faz com o IndexedDB. Devolve uma Promise que
  // nunca rejeita (é o que segura o open da página):
  //   carga parada                                 → nada (a página vai recarregar);
  //   sem indexedDB.databases()                    → "não sei": avisa e segue sem restaurar nem reconciliar;
  //   espelho desligado                            → derruba o banco sentinela (ver o comentário dele) e, se a WebView
  //                                                  perdeu as fotos e o nativo tem espelho, restaura sem recriá-lo;
  //   sem o banco sentinela (ou sem o banco das     → com espelho no nativo, restaura e cria o banco sentinela; sem,
  //   fotos, com chaves no nativo)                     só o cria;
  //   com o banco sentinela                        → reconcilia, uma vez por sessão.
  // O banco das fotos sumir inteiro com o banco sentinela de pé conta como perda: nenhuma faxina da página apaga o
  // banco, e a reconciliação mandaria apagar no nativo todas as fotos que ele guarda.
  function conferirIDB(ctx, idb, envio) {
    if (ctx.cargaParada) return Promise.resolve();
    if (typeof idb.listarBancos !== 'function') {
      ctx.falhou('idb', new Error('indexedDB.databases() indisponível: sem conferência do IndexedDB nesta carga'));
      return Promise.resolve();
    }
    return new Promise(function (ok) { ok(idb.listarBancos.call(idb.fabrica)); }) // a chamada que lança vira recusa
      .then(function (lista) {
        var nomes = new Set();
        (Array.isArray(lista) ? lista : []).forEach(function (d) { if (d && typeof d.name === 'string') nomes.add(d.name); });
        var temCasca = nomes.has(BANCO_SENTINELA);
        var temFotos = nomes.has(BANCO_FOTOS);
        var temEspelho = ctx.retrato.temEspelho === true;
        var doNativo = chavesDoNativo(ctx.retrato);
        var perdeu = !temCasca || (!temFotos && doNativo.length > 0);

        if (ctx.espelhoDesligado) {
          var antes = temCasca ? apagarBancoSentinela(ctx, idb) : Promise.resolve();
          // Ler do nativo não escreve nele: a restauração roda mesmo sem espelho, mas não recria o banco sentinela.
          return antes.then(function () { return perdeu && temEspelho ? restaurarIDB(ctx, idb, false) : undefined; });
        }
        if (perdeu) return temEspelho ? restaurarIDB(ctx, idb, true) : criarBancoSentinela(ctx, idb);
        if (lerSessaoCtx(ctx, SESSAO_IDB) !== null) return undefined;
        return reconciliar(ctx, idb, envio, temFotos, doNativo);
      })
      .then(null, function (e) { ctx.falhou('idb', e); });
  }

  // A RECONCILIAÇÃO, com o banco sentinela de pé: a WebView é a verdade.
  //   chave do banco real que falta em idbChaves  → reenvia (o espelho perdeu a gravação: canal fora, app morto no
  //                                                meio dos pedaços);
  //   chave de idbChaves que falta no banco real  → idbDel (o espelho perdeu a exclusão).
  // Uma vez por sessão da WebView (SESSAO_IDB): o idbChaves do retrato leve é o do começo da partida e envelhece dentro
  // dela; numa recarga ele reenviaria as mesmas fotos de novo. Sem sessionStorage, roda em toda carga.
  // Só lê da página: o banco das fotos é aberto e fechado aqui, e nada é gravado nele.
  function reconciliar(ctx, idb, envio, temFotos, doNativo) {
    var noNativo = new Set(doNativo);
    var leitura = temFotos ? lerParaReconciliar(ctx, idb, noNativo) : Promise.resolve({ chaves: new Set(), faltam: [] });
    return leitura.then(function (r) {
      if (!r) return; // o erro já foi avisado; a próxima carga tenta de novo
      noNativo.forEach(function (k) { if (!r.chaves.has(k)) envio.operacao({ t: 'del', k: k }); });
      r.faltam.forEach(function (item) {
        try { envio.operacao(representar(ctx.win, item.k, item.v)); } catch (e) { ctx.falhou('idbReconciliar', e); }
      });
      gravarSessaoCtx(ctx, SESSAO_IDB, '1');
    });
  }

  // As chaves do banco das fotos e o valor das que o nativo não tem, numa transação só de leitura. null em erro.
  function lerParaReconciliar(ctx, idb, noNativo) {
    return new Promise(function (ok) {
      var r;
      try { r = idb.abrir.call(idb.fabrica, BANCO_FOTOS); } catch (e) { ctx.falhou('idbReconciliar', e); ok(null); return; }
      // O banco estava na lista do databases(); se sumiu desde então, o open o criaria vazio, e a reconciliação mandaria
      // apagar tudo no nativo. Aborta: nada é criado, e a próxima carga confere de novo.
      r.onupgradeneeded = function () { r.transaction.abort(); };
      r.onerror = function () { ctx.falhou('idbReconciliar', r.error); ok(null); };
      r.onsuccess = function () {
        var db = r.result;
        function sair(valor) { try { db.close(); } catch (_) { /* já fechado */ } ok(valor); }
        if (!db.objectStoreNames.contains(STORE_FOTOS)) {
          ctx.falhou('idbReconciliar', new Error('o banco das fotos está sem a store'));
          sair(null);
          return;
        }
        var chaves = new Set();
        var faltam = [];
        try {
          var tx = db.transaction(STORE_FOTOS, 'readonly');
          var store = tx.objectStore(STORE_FOTOS);
          var pedido = store.getAllKeys();
          pedido.onsuccess = function () {
            (pedido.result || []).forEach(function (k) {
              if (typeof k !== 'string') return;
              chaves.add(k);
              if (noNativo.has(k)) return;
              var g = store.get(k);
              g.onsuccess = function () { faltam.push({ k: k, v: g.result }); };
            });
          };
          tx.oncomplete = function () { sair({ chaves: chaves, faltam: faltam }); };
          tx.onabort = function () { ctx.falhou('idbReconciliar', tx.error || new Error('leitura abortada')); sair(null); };
        } catch (e) {
          ctx.falhou('idbReconciliar', e);
          sair(null);
        }
      };
    });
  }

  // A RESTAURAÇÃO: pede ao nativo o que ele guarda (idbPedir) e grava cada item no banco real pelo put original, sem
  // espelhar. Respostas: {t:'idbItem', k, tipo, v} e {t:'idbPedaco', k, i, n, mime, b64} (pedaços de um Blob, na
  // ordem ou não), e no fim {t:'idbFim', n}, com `n` = quantas entradas o nativo mandou.
  // Resolve no que vier primeiro: o fim da restauração, ou ESPERA_NATIVO_MS sem ele (a página não pode esperar para
  // sempre). Depois do prazo, o que chegar ainda é gravado.
  // O banco sentinela só é criado (com `criarSentinela`) se tudo voltou: cada entrada gravada, nenhum pedaço faltando,
  // e o `n` igual ao que chegou. Sem ele a próxima carga restaura de novo, em vez de reconciliar, porque a
  // reconciliação mandaria apagar no nativo o que não voltou.
  function restaurarIDB(ctx, idb, criarSentinela) {
    var win = ctx.win;
    return new Promise(function (resolver) {
      var acabou = false;
      var timer = null;
      function soltar() {
        if (acabou) return;
        acabou = true;
        if (timer !== null) { idb.cancelar(timer); timer = null; }
        resolver();
      }

      var entradas = 0;
      var falhas = 0;
      var fim = false;
      var pedacos = new Map(); // chave -> { n, mime, partes, chegaram }
      var banco = abrirParaRestaurar(ctx, idb);
      var gravacoes = Promise.resolve();

      function gravar(k, valor) {
        gravacoes = gravacoes.then(function () {
          return banco.then(function (db) {
            if (!db) { falhas++; return undefined; }
            return gravarNoBanco(idb, db, k, valor).then(function (gravou) { if (!gravou) falhas++; });
          });
        });
      }

      function inteiro(x) { return typeof x === 'number' && x % 1 === 0; }

      function receberPedaco(m) {
        var k = m.k;
        var n = m.n;
        var i = m.i;
        if (typeof k !== 'string' || !inteiro(n) || n < 1 || !inteiro(i) || i < 0 || i >= n || typeof m.b64 !== 'string') {
          falhas++;
          return;
        }
        var p = pedacos.get(k);
        if (!p) {
          p = { n: n, mime: typeof m.mime === 'string' ? m.mime : '', partes: new Array(n), chegaram: 0 };
          pedacos.set(k, p);
        }
        if (p.n !== n || p.partes[i]) { falhas++; return; }
        try { p.partes[i] = deBase64(win, m.b64); } catch (e) { falhas++; return; }
        p.chegaram++;
        if (p.chegaram === p.n) {
          pedacos['delete'](k);
          entradas++;
          gravar(k, new win.Blob(p.partes, { type: p.mime }));
        }
      }

      function receberItem(m) {
        if (typeof m.k !== 'string' || typeof m.v !== 'string') { falhas++; return; }
        entradas++;
        var valor;
        if (m.tipo === 'texto') valor = m.v;
        else if (m.tipo === 'json') {
          try { valor = JSON.parse(m.v); } catch (e) { falhas++; return; }
        } else { falhas++; return; }
        gravar(m.k, valor);
      }

      function concluir(n) {
        banco.then(function (db) { if (db) db.close(); });
        var completa = falhas === 0 && pedacos.size === 0 && n === entradas;
        if (!completa) {
          // Só contagens: nada de chave nem de valor.
          ctx.falhou('idbRestaurar', new Error('restauração incompleta: ' + entradas + ' de ' +
            (typeof n === 'number' ? n : '?') + ' entradas, ' + falhas + ' falhas, ' + pedacos.size +
            ' sem todos os pedaços'));
          soltar();
          return;
        }
        (criarSentinela ? criarBancoSentinela(ctx, idb) : Promise.resolve()).then(soltar, soltar);
      }

      ctx.ouvirNativo(function (m) {
        if (fim || !m || typeof m !== 'object') return;
        if (m.t === 'idbItem') receberItem(m);
        else if (m.t === 'idbPedaco') receberPedaco(m);
        else if (m.t === 'idbFim') {
          fim = true;
          gravacoes.then(function () { concluir(m.n); }).then(null, function (e) { ctx.falhou('idbRestaurar', e); soltar(); });
        }
      });

      if (!ctx.enviar({ t: 'idbPedir' }, 'idbPedir')) { soltar(); return; }
      timer = idb.agendar(function () {
        timer = null;
        if (acabou) return;
        ctx.falhou('idbPedir', new Error('o nativo não terminou a restauração das fotos em 15 s: a página segue'));
        soltar();
      }, ESPERA_NATIVO_MS);
    });
  }

  // Abre o banco das fotos para a restauração como o idb() do campo.html o criaria (versão 1 e a store, se faltar). Se
  // o banco existe sem a store, não há como criá-la sem subir a versão, e a página (que abre na 1) quebraria: desiste.
  function abrirParaRestaurar(ctx, idb) {
    return new Promise(function (ok) {
      var r;
      try { r = idb.abrir.call(idb.fabrica, BANCO_FOTOS); } catch (e) { ctx.falhou('idbRestaurar', e); ok(null); return; }
      r.onupgradeneeded = function () {
        var db = r.result;
        if (!db.objectStoreNames.contains(STORE_FOTOS)) db.createObjectStore(STORE_FOTOS);
      };
      r.onsuccess = function () {
        var db = r.result;
        // Uma versão nova da página que suba a versão do banco não pode ficar presa nesta conexão.
        db.onversionchange = function () { db.close(); };
        if (db.objectStoreNames.contains(STORE_FOTOS)) { ok(db); return; }
        db.close();
        ctx.falhou('idbRestaurar', new Error('o banco das fotos existe sem a store'));
        ok(null);
      };
      r.onerror = function () { ctx.falhou('idbRestaurar', r.error); ok(null); };
    });
  }

  function gravarNoBanco(idb, db, k, valor) {
    return new Promise(function (ok) {
      try {
        var tx = db.transaction(STORE_FOTOS, 'readwrite');
        idb.put.call(tx.objectStore(STORE_FOTOS), valor, k);
        tx.oncomplete = function () { ok(true); };
        tx.onabort = function () { ok(false); };
      } catch (e) {
        ok(false);
      }
    });
  }

  function criarBancoSentinela(ctx, idb) {
    return new Promise(function (ok) {
      try {
        var r = idb.abrir.call(idb.fabrica, BANCO_SENTINELA);
        r.onsuccess = function () { try { r.result.close(); } catch (_) { /* já fechado */ } ok(); };
        r.onerror = function () { ctx.falhou('idbSentinela', r.error); ok(); };
      } catch (e) {
        ctx.falhou('idbSentinela', e);
        ok();
      }
    });
  }

  function apagarBancoSentinela(ctx, idb) {
    return new Promise(function (ok) {
      try {
        var r = idb.apagarBanco.call(idb.fabrica, BANCO_SENTINELA);
        r.onsuccess = function () { ok(); };
        r.onerror = function () { ctx.falhou('idbSentinela', r.error); ok(); };
        // Uma conexão aberta segura a exclusão até fechar; a página não espera por isso.
        r.onblocked = function () { ok(); };
      } catch (e) {
        ctx.falhou('idbSentinela', e);
        ok();
      }
    });
  }

  // O pedido que a página recebe do open do banco das fotos enquanto a conferência não termina. Tem o que o idb() do
  // campo.html usa (onupgradeneeded, onsuccess, onerror e result) e o resto de um IDBOpenDBRequest que alguém poderia
  // ler. `repassar` abre o real e repassa cada evento dele, com o result e o error copiados antes. A exceção de um
  // ouvinte da página sobe para o real, como subiria sem a casca (no upgradeneeded, ela aborta a versão nova).
  function criarPedidoSegurado(ctx) {
    var ouvintes = Object.create(null);
    var pedido = {
      result: undefined,
      error: null,
      readyState: 'pending',
      source: null,
      transaction: null,
      onsuccess: null,
      onerror: null,
      onupgradeneeded: null,
      onblocked: null,
      addEventListener: function (tipo, fn) {
        if (typeof fn !== 'function') return;
        (ouvintes[tipo] = ouvintes[tipo] || []).push(fn);
      },
      removeEventListener: function (tipo, fn) {
        if (ouvintes[tipo]) ouvintes[tipo] = ouvintes[tipo].filter(function (f) { return f !== fn; });
      },
    };

    function disparar(tipo, evento) {
      var noAtributo = pedido['on' + tipo];
      if (typeof noAtributo === 'function') noAtributo.call(pedido, evento);
      var lista = (ouvintes[tipo] || []).slice();
      for (var i = 0; i < lista.length; i++) lista[i].call(pedido, evento);
    }

    function copiar(real) {
      try { pedido.result = real.result; } catch (e) { /* ainda sem resultado */ }
      try { pedido.error = real.error; } catch (e) { /* idem */ }
      pedido.readyState = real.readyState;
      pedido.transaction = real.transaction;
    }

    function repassar(abrirReal) {
      var real;
      try {
        real = abrirReal();
      } catch (e) {
        // O open real lançou (um nome ou uma versão que ele recusa): a página recebe o erro pelo onerror.
        pedido.error = e;
        pedido.readyState = 'done';
        try { disparar('error', { type: 'error', target: pedido }); } catch (e2) { ctx.falhou('idbOpen', e2); }
        return;
      }
      ['upgradeneeded', 'success', 'error', 'blocked'].forEach(function (tipo) {
        real.addEventListener(tipo, function (evento) {
          copiar(real);
          disparar(tipo, evento);
        });
      });
    }

    return { pedido: pedido, repassar: repassar };
  }

  // Embrulha o `open` do window.indexedDB: o do banco das fotos espera o `pronto` da conferência; os outros bancos
  // passam direto, e o das fotos também, depois dele.
  function segurarAbertura(ctx, idb, pronto) {
    var liberado = false;
    pronto.then(function () { liberado = true; });
    idb.fabrica.open = function open(nome) {
      var dasFotos;
      try { dasFotos = String(nome) === BANCO_FOTOS; } catch (e) { dasFotos = false; }
      if (liberado || !dasFotos) return idb.abrir.apply(this, arguments);
      var fabrica = this;
      var argumentos = arguments;
      var segurado = criarPedidoSegurado(ctx);
      pronto.then(function () { segurado.repassar(function () { return idb.abrir.apply(fabrica, argumentos); }); });
      return segurado.pedido;
    };
  }

  // Liga a seção 5: o espelho (só com ele ligado), a conferência da partida e o open segurado até ela terminar.
  function instalarIDB(ctx) {
    var idb = capturarIDB(ctx.win);
    if (!idb) return;
    var envio = criarEnvioIDB(ctx);
    if (!ctx.espelhoDesligado) instalarEspelhoIDB(ctx, idb, envio);
    var pronto = conferirIDB(ctx, idb, envio);
    ctx.prontos.push(pronto);
    segurarAbertura(ctx, idb, pronto);
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 6. window.open: o login da Microsoft e o "Conectar conta Fracttal" (spec 6.3, itens 1 e 2)
  // ---------------------------------------------------------------------------------------------------------------

  // Na WebView sem múltiplas janelas, o window.open devolve a própria janela do App. O loginPopup do MSAL a usaria
  // como popup, e o document.write do conectarFracttal (campo.html) apagaria a tela do App.
  var HOSTS_LOGIN = ['login.microsoftonline.com', 'login.live.com', 'login.windows.net'];
  var ESPERA_JANELA_MS = 1000;
  var ID_CAMADA = 'gc-casca-aviso';

  // O endereço, como a página o daria a uma aba: relativo à página do App. null se não der para ler.
  function lerEndereco(win, url) {
    if (url === undefined || url === null || typeof win.URL !== 'function') return null;
    var base;
    try { base = win.location && win.location.href; } catch (e) { base = undefined; }
    try { return new win.URL(String(url), base || undefined); } catch (e) { return null; }
  }

  function semEndereco(url) {
    if (url === undefined || url === null) return true;
    var s = String(url);
    return s === '' || s === 'about:blank';
  }

  // O nome do popup do MSAL começa com `msal.` (msal.<clientId>.<escopos>...), e o loginPopup o abre com
  // 'about:blank'. Com null, o MSAL dá empty_window_error, e o campo.html cai no loginRedirect.
  function ehLogin(win, url, nome) {
    if (typeof nome === 'string' && nome.indexOf('msal') === 0) return true;
    var u = lerEndereco(win, url);
    return u !== null && HOSTS_LOGIN.indexOf(u.hostname.toLowerCase()) >= 0;
  }

  // O endereço absoluto que vai ao nativo (ele o abre numa Custom Tab), ou null se não for http nem https.
  function enderecoDeFora(win, url) {
    if (typeof win.URL !== 'function') {
      var s = String(url);
      return /^https?:\/\//i.test(s) ? s : null;
    }
    var u = lerEndereco(win, url);
    return u !== null && (u.protocol === 'http:' || u.protocol === 'https:') ? u.href : null;
  }

  // A camada por cima do App: o HTML que a página escreveu, num iframe sem permissão nenhuma (sandbox vazio: sem
  // script, sem formulário, sem navegar o App), e o botão Fechar. Estilo só inline, com tudo que importa declarado, para
  // o CSS da página não a desmontar. Cores neutras: véu escuro translúcido, cartão claro (o texto da página é escuro).
  function montarCamada(doc, aoFechar) {
    var velha = doc.getElementById(ID_CAMADA);
    if (velha && velha.parentNode) velha.parentNode.removeChild(velha);

    var raiz = doc.createElement('div');
    raiz.id = ID_CAMADA;
    raiz.setAttribute('role', 'dialog');
    raiz.setAttribute('aria-modal', 'true');
    raiz.setAttribute('aria-label', 'Aviso');
    raiz.setAttribute('style', 'position:fixed;top:0;right:0;bottom:0;left:0;z-index:2147483647;display:flex;' +
      'align-items:center;justify-content:center;margin:0;padding:16px;box-sizing:border-box;' +
      'background:rgba(0,0,0,0.6)');

    var cartao = doc.createElement('div');
    cartao.setAttribute('style', 'display:flex;flex-direction:column;width:100%;max-width:560px;height:100%;' +
      'max-height:640px;margin:0;padding:0;box-sizing:border-box;overflow:hidden;border-radius:12px;' +
      'background:#ffffff;color:#14181d;box-shadow:0 8px 32px rgba(0,0,0,0.35)');

    var quadro = doc.createElement('iframe');
    // O sandbox antes do srcdoc: as restrições valem já para o primeiro conteúdo.
    quadro.setAttribute('sandbox', '');
    quadro.setAttribute('title', 'Aviso');
    quadro.setAttribute('style', 'display:block;flex:1 1 auto;width:100%;min-height:0;margin:0;padding:0;border:0;' +
      'background:#ffffff');

    var botao = doc.createElement('button');
    botao.setAttribute('type', 'button');
    botao.textContent = 'Fechar';
    botao.setAttribute('style', 'display:block;flex:0 0 auto;width:100%;min-height:52px;margin:0;' +
      'padding:14px 16px;box-sizing:border-box;border:0;border-top:1px solid #e5e7eb;border-radius:0;' +
      'background:#f3f4f6;color:#14181d;font:600 16px/1.2 system-ui,-apple-system,sans-serif;cursor:pointer');
    botao.addEventListener('click', aoFechar);

    cartao.appendChild(quadro);
    cartao.appendChild(botao);
    raiz.appendChild(cartao);
    return { raiz: raiz, quadro: quadro };
  }

  // A janela de mentira do window.open que não é login. Imita uma aba: o document guarda o HTML escrito, e levá-la a
  // um endereço manda {t:'fora', url} ao nativo. Passado ESPERA_JANELA_MS sem endereço e com HTML, é o caso de erro do
  // conectarFracttal (a página escreveu uma explicação, como "sua sessão da Microsoft expirou"): a camada a mostra. Com
  // a rede lenta, a camada pode aparecer antes, com o "Abrindo o Fracttal…"; o que a página escrever depois troca o
  // conteúdo, e o endereço que chegar depois tira a camada. O Fechar só tira a camada: a explicação que vier depois a
  // traz de volta, porque é ela que diz ao técnico o que fazer.
  function criarJanelaDeMentira(ctx, urlInicial) {
    var win = ctx.win;
    var partes = [];
    var aberto = false; // o documento aceita escrita sem recomeçar (depois do close, um write recomeça, como no navegador)
    var navegou = false;
    var prazoVencido = false;
    var ultimaUrl = 'about:blank';
    var camada = null;
    var janela = { closed: false };

    // Cada entrada da página passa por aqui: um erro nosso nunca sobe para ela.
    function seguro(onde, fn) {
      return function () {
        try { return fn.apply(this, arguments); } catch (e) { ctx.falhou(onde, e); return undefined; }
      };
    }

    function tirarCamada() {
      if (camada && camada.raiz.parentNode) camada.raiz.parentNode.removeChild(camada.raiz);
      camada = null;
    }

    function mostrar() {
      var html = partes.join('');
      if (!prazoVencido || navegou || janela.closed || html === '') return;
      var doc = win.document;
      if (!camada) {
        camada = montarCamada(doc, seguro('camada', tirarCamada));
        (doc.body || doc.documentElement).appendChild(camada.raiz);
      }
      camada.quadro.setAttribute('srcdoc', html);
    }

    function irPara(url) {
      var destino = enderecoDeFora(win, url);
      if (destino === null) {
        ctx.falhou('fora', new Error('endereço que não é http nem https: não abre fora'));
        return;
      }
      navegou = true;
      ultimaUrl = destino;
      tirarCamada();
      ctx.enviar({ t: 'fora', url: destino }, 'fora');
    }

    function escrever(args, fimDeLinha) {
      if (!aberto) { partes = []; aberto = true; }
      for (var i = 0; i < args.length; i++) partes.push(String(args[i]));
      if (fimDeLinha) partes.push('\n');
      mostrar();
    }

    var documento = {};
    documento.open = seguro('document.open', function () { partes = []; aberto = true; return documento; });
    documento.write = seguro('document.write', function () { escrever(arguments, false); });
    documento.writeln = seguro('document.writeln', function () { escrever(arguments, true); });
    documento.close = seguro('document.close', function () { aberto = false; mostrar(); });

    var localizacao = {
      assign: seguro('location.assign', irPara),
      replace: seguro('location.replace', irPara),
      toString: function () { return ultimaUrl; },
    };
    Object.defineProperty(localizacao, 'href', {
      enumerable: true,
      get: function () { return ultimaUrl; },
      set: seguro('location.href', irPara),
    });

    janela.document = documento;
    janela.close = seguro('close', function () { janela.closed = true; tirarCamada(); });
    Object.defineProperty(janela, 'location', {
      enumerable: true,
      get: function () { return localizacao; },
      set: seguro('location', irPara),
    });

    if (!semEndereco(urlInicial)) irPara(urlInicial);
    if (!navegou) {
      win.setTimeout(seguro('janela', function () { prazoVencido = true; mostrar(); }), ESPERA_JANELA_MS);
    }
    return janela;
  }

  function instalarJanela(ctx) {
    var win = ctx.win;
    win.open = function open(url, nome) {
      try {
        if (ehLogin(win, url, nome)) return null;
        return criarJanelaDeMentira(ctx, url);
      } catch (e) {
        // Um erro nosso: o null é o que a página já trata (o campo.html navega a própria aba, e o MSAL cai no redirect).
        ctx.falhou('open', e);
        return null;
      }
    };
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
      ctx.ls = ls;
      // A 4 antes da 3: a sentinela e a restauração gravam pelos originais e decidem se esta carga para; só depois,
      // e só se ela puder espelhar, o espelho liga. Com a marca da perda ainda no localStorage, a carga não espelha,
      // venha a marca de onde vier (a perda que parou, a que a trava não deixou parar, a restauração só da fila): a
      // cópia boa está no nativo. Isso vai em `espelhoDesligado`, que é o que as outras seções conferem. Se a 4 falhar
      // por um erro nosso sem marca, a página abre com o espelho ligado.
      tentar(ctx, 'sentinela', function () { conferirSentinelaEFila(ctx, ls); });
      if (temMarcaDePerda(ls)) ctx.espelhoDesligado = true;
      if (!ctx.espelhoDesligado) instalarEspelhoLS(ctx, ls);
    });
    // A 5 depois da 3 e da 4: ela lê o `espelhoDesligado` e o `cargaParada` que a 4 decidiu. A 6 não espelha nada e
    // vale em toda carga.
    tentar(ctx, 'idb', function () { instalarIDB(ctx); });
    tentar(ctx, 'janela', function () { instalarJanela(ctx); });

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
      MARCA_PERDA: MARCA_PERDA,
      BANCO_SENTINELA: BANCO_SENTINELA,
    };
  } else if (typeof window !== 'undefined') {
    instalar(window, window.gcCasca, window.__GC_RETRATO__ || {});
  }
})();
