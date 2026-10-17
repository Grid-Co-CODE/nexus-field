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
      // recarga, sem espelho, e a seção 5 a trata como qualquer carga sem espelho (não toca no IndexedDB).
      cargaParada: false,

      // Ligado pela seção 4 quando esta carga aplicou o retrato completo de uma perda (motivo 'perda', ou sem motivo):
      // a seção 5 devolve também o IndexedDB inteiro do nativo, a mesma geração do localStorage que acabou de voltar.
      restauracaoDePerda: false,

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
  // o app. Na segunda, a carga segue, sem stop e sem pedido, e o nativo é avisado (sem chave nem valor).
  // O mesmo vale, sem contar a sessão, quando o retrato traz `semParar: true` (o retrato sem conferência do nativo: o
  // disjuntor desarmou, o pedido veio torto ou o espelho não pôde ser lido). Caso que o criou: com a marca da perda e o
  // sessionStorage que não guarda a trava (a WebView recriada começa sem ele), toda carga parava e pedia, e o retrato
  // sem as versões da fila não evitava a parada da perda, que vem antes da conferência da fila. O espelho:
  //   'fila'   fica ligado, e a fila da WebView vai ao nativo (ela vence, porque é o que a página vai usar). Sem fila
  //            na WebView, só o aviso: nunca se manda apagar a fila do nativo;
  //   'perda'  fica desligado na carga inteira: a cópia boa está no nativo, e o que a página gravar agora não pode ir
  //            por cima dela. A sentinela não volta e a marca da perda fica: a partida seguinte pede o retrato.
  function pararCarga(ctx, ls, motivo) {
    var semParar = ctx.retrato.semParar === true;
    if (semParar || lerSessao(ls, SESSAO_PAROU) !== null) {
      if (motivo === 'perda') {
        // A marca da perda já desligaria o espelho (o `instalar` a confere), mas ela pode não ter cabido.
        ctx.espelhoDesligado = true;
      } else {
        var atual = ls.getItem.call(ls.real, CHAVE_FILA);
        if (atual !== null) espelharFila(ctx, ls, atual, 'fila');
      }
      ctx.falhou(motivo, new Error(semParar ? 'conferência ignorada: o nativo pediu para não parar'
        : 'conferência ignorada: já parou nesta sessão'));
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
    // saísse deixaria toda carga sem espelho, partida após partida. Na perda, o IndexedDB volta junto (seção 5).
    if (retrato.motivo !== 'fila') {
      ctx.restauracaoDePerda = true;
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

  // O teto da seção (ver O TETO DA SEÇÃO): o máximo que a página espera para abrir o banco das fotos, com a conferência
  // ou a restauração terminadas ou não.
  var ESPERA_NATIVO_MS = 15000;

  // Memória da sessão (ver A MEMÓRIA DA SESSÃO, na seção 4): a reconciliação já rodou nesta sessão da WebView.
  var SESSAO_IDB = 'gc_casca_idb_reconciliado';

  // O que o banco sentinela guarda (versão 1): na store `pendentes`, as chaves que a última restauração não trouxe de
  // volta (chave -> 1); na store `estado`, a marca CHAVE_RESTAURACAO -> 'incompleta' quando o `n` do nativo não bateu
  // (ou não chegou), ou desde o começo de uma restauração (ela entra antes do idbPedir) até as pendentes dela entrarem.
  var STORE_PENDENTES = 'pendentes';
  var STORE_ESTADO = 'estado';
  var CHAVE_RESTAURACAO = 'restauracao';

  // O BANCO SENTINELA (BANCO_SENTINELA, `gc_casca`) quer dizer: o IndexedDB desta WebView já foi conferido com o
  // nativo (na instalação nova ou numa restauração). Numa carga normal (sem a marca da perda), a falta dele é o
  // IndexedDB que se perdeu sozinho (o despejo do Chromium pode levar uma loja e deixar a outra): restaura do nativo.
  // Com ele de pé, a carga reconcilia, uma vez por sessão.
  // Ele nasce (ou é marcado 'incompleta') no começo de toda restauração, antes do idbPedir, e no fim guarda na store
  // STORE_PENDENTES as chaves que não voltaram. Caso da revisão: com o banco sentinela só depois da restauração
  // completa, bastava uma entrada recusada (o disco cheio) e TODA carga pedia tudo de novo, segurava a página 15 s e
  // regravava por cima dela. Agora não há nova tentativa automática: a reconciliação nunca manda apagar no nativo uma
  // chave pendente (ele ainda a tem e a WebView não), não pede de novo quando todas as chaves do nativo são pendentes,
  // e com a marca 'incompleta' (o `n` do nativo não bateu ou não chegou, ou a restauração morreu no meio: pode faltar
  // uma chave que nem se sabe qual) não manda apagar nada. Custo aceito: uma chave velha pode ficar no nativo e voltar
  // numa restauração futura; a faxina de órfãos da página a limpa.
  //
  // A CARGA SEM ESPELHO (a perda que a trava não deixou parar, o stop que lançou, a marca da perda) não toca no
  // IndexedDB: nem restaura, nem reconcilia, nem cria ou apaga o banco sentinela. A faxina de órfãos da página que roda
  // assim (`idbKeys` + `idbDel` do campo.html, com o localStorage vazio) apaga fotos sem espelhar; quem as devolve é a
  // marca da perda, que leva a carga seguinte ao retrato completo, e a restauração da perda devolve o IndexedDB inteiro
  // junto com o localStorage, da mesma geração, com o banco sentinela de pé ou não. A reconciliação nunca roda com a
  // marca (sem espelho não há seção 5), então o que a página apagou sem espelho não vira idbDel no nativo.

  // Captura o IndexedDB e os métodos originais UMA vez, antes de qualquer embrulho e de qualquer script da página. A
  // restauração grava pelo put original (pelo embrulhado, ecoaria ao nativo o que acabou de vir dele).
  function capturarIDB(win) {
    var fabrica = win.indexedDB; // pode lançar SecurityError (armazenamento negado): o `tentar` de fora engole
    var proto = win.IDBObjectStore && win.IDBObjectStore.prototype;
    if (!fabrica || typeof fabrica.open !== 'function' || !proto) return null;
    return {
      fabrica: fabrica,
      abrir: fabrica.open,
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

  function fechar(db) {
    if (db) { try { db.close(); } catch (e) { /* já fechado */ } }
  }

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
      // Devolve a promessa da fila até esta operação sair (nunca rejeita): a reconciliação só lê o valor seguinte
      // depois dela.
      operacao: function (op) {
        cadeia = cadeia.then(function () { return enviar(op); }).then(null, function (e) { ctx.falhou('idbEnvio', e); });
        return cadeia;
      },
    };
  }

  // Embrulha IDBObjectStore.prototype.put e delete e espelha só o banco e a store das fotos. O original vem primeiro, e
  // a exceção dele sobe intacta; a operação fica anotada na transação e só vai ao nativo no `complete` dela: a
  // transação que aborta não gravou nada. get, getAll, getAllKeys e o resto ficam intocados.
  function instalarEspelhoIDB(ctx, idb, envio, estado) {
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
      // Depois do prazo a página já abriu o banco e mexe nele: a restauração atrasada pula esta chave (ver O TETO).
      // Anotada na chamada, não no complete: a transação da página foi criada antes da que a restauração criaria.
      if (estado.vencido) estado.tocadas.add(chave);
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

  // Decide, antes de a página abrir o banco das fotos, o que esta carga (com espelho) faz com o IndexedDB. Devolve uma
  // Promise que nunca rejeita; o `comTeto` a corta em ESPERA_NATIVO_MS.
  //   restauração da perda (retrato completo, motivo   → restaura o IndexedDB inteiro do nativo, esteja o banco
  //   'perda' ou sem motivo)                              sentinela de pé ou não (a mesma geração do localStorage);
  //   sem indexedDB.databases()                        → "não sei": avisa e segue sem restaurar nem reconciliar;
  //   sem o banco sentinela                            → perda só do IndexedDB: com espelho no nativo, restaura; sem
  //                                                       (instalação nova), só cria o banco sentinela;
  //   com o banco sentinela                            → reconcilia, uma vez por sessão.
  function conferirIDB(ctx, idb, envio, estado) {
    var doNativo = chavesDoNativo(ctx.retrato);
    // O localStorage acabou de voltar do retrato completo (seção 4). Sem o IndexedDB da mesma geração, a faxina de órfãos
    // da página apagaria o que a WebView tem e o localStorage restaurado não cita, e as fotos que ele cita e a WebView
    // perdeu (ou que a página apagou numa carga sem espelho) não voltariam.
    if (ctx.restauracaoDePerda) return restaurarIDB(ctx, idb, estado, doNativo);
    if (typeof idb.listarBancos !== 'function') {
      ctx.falhou('idb', new Error('indexedDB.databases() indisponível: sem conferência do IndexedDB nesta carga'));
      return Promise.resolve();
    }
    return new Promise(function (ok) { ok(idb.listarBancos.call(idb.fabrica)); }) // a chamada que lança vira recusa
      .then(function (lista) {
        // Depois do prazo a página já abriu o banco das fotos: restaurar ou reconciliar agora seria agir às cegas. A
        // próxima carga confere de novo.
        if (estado.vencido) return undefined;
        var nomes = new Set();
        (Array.isArray(lista) ? lista : []).forEach(function (d) { if (d && typeof d.name === 'string') nomes.add(d.name); });
        if (!nomes.has(BANCO_SENTINELA)) {
          if (ctx.retrato.temEspelho === true) return restaurarIDB(ctx, idb, estado, doNativo);
          return gravarCasca(ctx, idb, [], false);
        }
        if (lerSessaoCtx(ctx, SESSAO_IDB) !== null) return undefined;
        return reconciliar(ctx, idb, envio, estado, nomes.has(BANCO_FOTOS), doNativo);
      })
      .then(null, function (e) { ctx.falhou('idb', e); });
  }

  // A RECONCILIAÇÃO, com o banco sentinela de pé e o espelho ligado (então sem a marca da perda): a WebView é a verdade.
  //   a store das fotos vazia e idbChaves não vazio → perda só do IndexedDB: restaura (nenhuma faxina da página esvazia
  //                                                   o banco inteiro, e reconciliar mandaria apagar tudo no nativo),
  //                                                   a menos que toda chave de idbChaves já seja pendente: a última
  //                                                   restauração não gravou nenhuma (o disco cheio recusou todas), e
  //                                                   pedir de novo seria o laço que as pendentes evitam; segue sem
  //                                                   restaurar nem apagar, com um aviso sem chave;
  //   chave de idbChaves que falta na WebView        → idbDel (o espelho perdeu a exclusão), se ela não for pendente
  //                                                   e o banco sentinela não estiver marcado 'incompleta';
  //   chave da WebView que falta em idbChaves        → reenvia (o espelho perdeu a gravação: canal fora, app morto no
  //                                                   meio dos pedaços).
  // Uma vez por sessão da WebView (SESSAO_IDB): o idbChaves do retrato leve é o do começo da partida e envelhece dentro
  // dela; numa recarga ele reenviaria as mesmas fotos de novo. Sem sessionStorage, roda em toda carga.
  // O `pronto` espera só a decisão; os reenvios seguem depois, uma chave por vez (lida, mandada, e só então a seguinte:
  // um vídeo de 15 MB por vez na memória, nunca todos), e a marca da sessão entra depois do último. Só lê da página.
  function reconciliar(ctx, idb, envio, estado, temFotos, doNativo) {
    var aberto = temFotos ? abrirFotosParaLer(ctx, idb) : Promise.resolve(null);
    return aberto.then(function (db) {
      if (temFotos && !db) return undefined; // o erro já foi avisado; a próxima carga confere de novo
      return (db ? lerChaves(ctx, db) : Promise.resolve([])).then(function (locais) {
        if (locais === null || estado.vencido) { fechar(db); return undefined; }
        if (!locais.length) {
          fechar(db);
          if (!doNativo.length) { gravarSessaoCtx(ctx, SESSAO_IDB, '1'); return undefined; }
          return lerCasca(ctx, idb).then(function (casca) {
            if (estado.vencido) return undefined;
            if (casca && doNativo.every(function (k) { return casca.pendentes.has(k); })) {
              // Só a contagem: nada de chave.
              ctx.falhou('idb', new Error('a store das fotos está vazia e as ' + doNativo.length + ' entradas do ' +
                'nativo são pendentes da última restauração: sem novo pedido nesta carga'));
              gravarSessaoCtx(ctx, SESSAO_IDB, '1');
              return undefined;
            }
            return restaurarIDB(ctx, idb, estado, doNativo);
          });
        }
        return lerCasca(ctx, idb).then(function (casca) {
          if (estado.vencido) { fechar(db); return undefined; }
          var naWebView = new Set(locais);
          var noNativo = new Set(doNativo);
          // Sem o estado do banco sentinela (não abriu), ou com a marca, não se sabe o que a restauração deixou de
          // trazer: nada é apagado no nativo.
          if (casca && !casca.incompleta) {
            doNativo.forEach(function (k) {
              if (!naWebView.has(k) && !casca.pendentes.has(k)) envio.operacao({ t: 'del', k: k });
            });
          }
          var faltam = locais.filter(function (k) { return !noNativo.has(k); });
          reenviar(ctx, db, envio, faltam).then(function (terminou) {
            fechar(db);
            if (terminou) gravarSessaoCtx(ctx, SESSAO_IDB, '1');
          });
          return undefined;
        });
      });
    });
  }

  // Abre o banco das fotos só para ler. Ele estava na lista do databases(); se sumiu desde então, o open o criaria vazio
  // e a reconciliação mandaria apagar tudo no nativo: aborta, nada é criado, e a próxima carga confere de novo.
  function abrirFotosParaLer(ctx, idb) {
    return new Promise(function (ok) {
      var r;
      try { r = idb.abrir.call(idb.fabrica, BANCO_FOTOS); } catch (e) { ctx.falhou('idbReconciliar', e); ok(null); return; }
      r.onupgradeneeded = function () { r.transaction.abort(); };
      r.onerror = function () { ctx.falhou('idbReconciliar', r.error); ok(null); };
      r.onsuccess = function () {
        var db = r.result;
        // Uma versão nova da página que suba a versão do banco não pode ficar presa nesta conexão (os reenvios param).
        db.onversionchange = function () { db.close(); };
        if (db.objectStoreNames.contains(STORE_FOTOS)) { ok(db); return; }
        db.close();
        ctx.falhou('idbReconciliar', new Error('o banco das fotos está sem a store'));
        ok(null);
      };
    });
  }

  // As chaves (só elas) da store das fotos, ou null em erro.
  function lerChaves(ctx, db) {
    return new Promise(function (ok) {
      try {
        var pedido = db.transaction(STORE_FOTOS, 'readonly').objectStore(STORE_FOTOS).getAllKeys();
        pedido.onsuccess = function () {
          ok((pedido.result || []).filter(function (k) { return typeof k === 'string'; }));
        };
        pedido.onerror = function () { ctx.falhou('idbReconciliar', pedido.error); ok(null); };
      } catch (e) {
        ctx.falhou('idbReconciliar', e);
        ok(null);
      }
    });
  }

  // Lê e manda as `chaves`, uma por vez: a seguinte só é lida depois de a anterior sair inteira pela fila do envio.
  // Resolve true se passou por todas, false se parou num erro (o banco fechou: a próxima carga reconcilia de novo).
  function reenviar(ctx, db, envio, chaves) {
    var i = 0;
    function proxima() {
      if (i >= chaves.length) return true;
      var k = chaves[i++];
      return lerValor(db, k).then(function (lido) {
        // Sumiu desde a lista: a página a apagou, e o espelho já mandou o idbDel.
        if (!lido.achou) return proxima();
        var op;
        try { op = representar(ctx.win, k, lido.valor); } catch (e) { ctx.falhou('idbReconciliar', e); return proxima(); }
        return envio.operacao(op).then(proxima);
      });
    }
    return Promise.resolve().then(proxima).then(null, function (e) { ctx.falhou('idbReconciliar', e); return false; });
  }

  function lerValor(db, k) {
    return new Promise(function (ok, falha) {
      var pedido = db.transaction(STORE_FOTOS, 'readonly').objectStore(STORE_FOTOS).get(k); // lança com o banco fechado
      pedido.onsuccess = function () { ok({ achou: pedido.result !== undefined, valor: pedido.result }); };
      pedido.onerror = function () { falha(pedido.error); };
    });
  }

  // A RESTAURAÇÃO: pede ao nativo o que ele guarda (idbPedir) e grava cada item no banco real pelo put original, sem
  // espelhar. Respostas: {t:'idbItem', k, tipo, v} e {t:'idbPedaco', k, i, n, mime, b64} (pedaços de um Blob, na
  // ordem ou não), e no fim {t:'idbFim', n}, com `n` = quantas entradas (chaves) o nativo mandou.
  // Antes do idbPedir, a marca 'incompleta' entra no banco sentinela (que nasce, se faltar). Caso da revisão: a
  // restauração que roda por cima de um banco sentinela no estado velho, completo (a da perda, ou a da store vazia),
  // e que morre antes do idbFim e do teto (o técnico fecha o app na espera, o renderer morre num vídeo grande),
  // deixava esse estado; a reconciliação da partida seguinte mandava idbDel de toda chave que ainda não tinha voltado, e
  // o nativo perdia a única cópia. Custo aceito: a restauração que morre no meio não é tentada de novo sozinha (a store
  // com alguma coisa vai à reconciliação, que com a marca só reenvia); o que não voltou fica guardado no nativo.
  // No idbFim grava o estado no banco sentinela (e o cria, se faltar), completa ou não: as PENDENTES são as chaves que o
  // nativo anunciou (`anunciadas`, o idbChaves do retrato) ou mandou e que não foram gravadas; a marca 'incompleta' vai
  // quando o `n` não bate com as chaves que chegaram. Nada de nova tentativa automática (ver O BANCO SENTINELA).
  // O prazo é o do `comTeto`: vencido antes do idbFim, o estado é gravado já (tudo que falta pendente, e a marca), a
  // página abre o banco, o que chegar depois ainda é gravado (menos o que a página tocou: `estado.tocadas`) e o idbFim
  // atrasado refaz o estado.
  function restaurarIDB(ctx, idb, estado, anunciadas) {
    var win = ctx.win;
    return new Promise(function (resolver) {
      var conhecidas = new Set(anunciadas); // o que o nativo disse ter: o retrato e as chaves que chegaram
      var recebidas = new Set();            // as chaves que chegaram, inteiras ou não
      var gravadas = new Set();
      var falhas = 0;
      var fim = false;
      var pedacos = new Map(); // chave -> { n, mime, partes, chegaram }
      var banco = abrirParaRestaurar(ctx, idb);
      var gravacoes = Promise.resolve();
      // As gravações do estado, uma depois da outra (a última vence), e a primeira é a marca, antes do idbPedir.
      var registro = marcarIncompleta(ctx, idb);

      function gravar(k, valor) {
        gravacoes = gravacoes.then(function () {
          return banco.then(function (db) {
            if (!db) { falhas++; return undefined; }
            // O que a página gravou ou apagou depois do prazo vence a cópia que chega atrasada. A chave fica pendente
            // (a transação da página pode ter abortado: aí o nativo ainda é o único que a tem).
            if (estado.tocadas.has(k)) return undefined;
            return gravarNoBanco(idb, db, k, valor).then(function (gravou) {
              if (gravou) gravadas.add(k); else falhas++;
            });
          });
        });
      }

      function inteiro(x) { return typeof x === 'number' && x % 1 === 0; }

      function anotar(k) {
        conhecidas.add(k);
        recebidas.add(k);
      }

      function receberPedaco(m) {
        var k = m.k;
        var n = m.n;
        var i = m.i;
        if (typeof k !== 'string') { falhas++; return; }
        anotar(k);
        if (!inteiro(n) || n < 1 || !inteiro(i) || i < 0 || i >= n || typeof m.b64 !== 'string') { falhas++; return; }
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
          gravar(k, new win.Blob(p.partes, { type: p.mime }));
        }
      }

      function receberItem(m) {
        if (typeof m.k !== 'string') { falhas++; return; }
        anotar(m.k);
        if (typeof m.v !== 'string') { falhas++; return; }
        var valor;
        if (m.tipo === 'texto') valor = m.v;
        else if (m.tipo === 'json') {
          try { valor = JSON.parse(m.v); } catch (e) { falhas++; return; }
        } else { falhas++; return; }
        gravar(m.k, valor);
      }

      function pendentes() {
        var lista = [];
        conhecidas.forEach(function (k) { if (!gravadas.has(k)) lista.push(k); });
        return lista;
      }

      function registrar(incompleta) {
        var lista = pendentes();
        registro = registro.then(function () { return gravarCasca(ctx, idb, lista, incompleta); });
        return registro;
      }

      function concluir(n) {
        banco.then(fechar);
        var todas = n === recebidas.size; // nenhuma entrada sumiu no caminho
        var faltam = pendentes().length;
        if (!todas || falhas || pedacos.size || faltam) {
          // Só contagens: nada de chave nem de valor.
          ctx.falhou('idbRestaurar', new Error('restauração incompleta: ' + recebidas.size + ' de ' +
            (typeof n === 'number' ? n : '?') + ' entradas, ' + falhas + ' falhas, ' + pedacos.size +
            ' sem todos os pedaços, ' + faltam + ' pendentes'));
        }
        return registrar(!todas);
      }

      // O prazo venceu antes do idbFim: o banco sentinela nasce agora, com o que ainda não voltou pendente e a marca (o
      // `n` não chegou). Com o idbFim já recebido, quem grava o estado é o `concluir`, logo depois das gravações.
      estado.aoVencer.push(function () { if (!fim) registrar(true); });

      ctx.ouvirNativo(function (m) {
        if (fim || !m || typeof m !== 'object') return;
        if (m.t === 'idbItem') receberItem(m);
        else if (m.t === 'idbPedaco') receberPedaco(m);
        else if (m.t === 'idbFim') {
          fim = true;
          gravacoes.then(function () { return concluir(m.n); })
            .then(null, function (e) { ctx.falhou('idbRestaurar', e); })
            .then(function () { resolver(); });
        }
      });

      // O pedido só sai com a marca gravada (ou com a gravação dela falhada e avisada: sem restaurar, a reconciliação
      // seguinte apagaria no nativo o que a WebView perdeu, que é pior). Se ele nem sai (o canal caiu), nada foi
      // restaurado, e a marca fica: a carga seguinte não apaga nada no nativo. Isso não vira o laço da revisão: o envio
      // falha na hora, sem segurar a página.
      registro.then(function () {
        if (!ctx.enviar({ t: 'idbPedir' }, 'idbPedir')) resolver();
      });
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

  // Abre o banco sentinela (versão 1, com as duas stores). O banco que nasce aqui já nasce com a marca 'incompleta', na
  // mesma transação que o cria: se a gravação das pendentes não entrar depois, a reconciliação não apaga nada no nativo.
  function abrirCasca(ctx, idb) {
    return new Promise(function (ok) {
      var r;
      try { r = idb.abrir.call(idb.fabrica, BANCO_SENTINELA, 1); } catch (e) { ctx.falhou('idbSentinela', e); ok(null); return; }
      r.onupgradeneeded = function () {
        var db = r.result;
        if (!db.objectStoreNames.contains(STORE_PENDENTES)) db.createObjectStore(STORE_PENDENTES);
        if (!db.objectStoreNames.contains(STORE_ESTADO)) {
          idb.put.call(db.createObjectStore(STORE_ESTADO), 'incompleta', CHAVE_RESTAURACAO);
        }
      };
      r.onsuccess = function () {
        var db = r.result;
        db.onversionchange = function () { db.close(); };
        if (db.objectStoreNames.contains(STORE_PENDENTES) && db.objectStoreNames.contains(STORE_ESTADO)) { ok(db); return; }
        db.close();
        ctx.falhou('idbSentinela', new Error('o banco sentinela está sem as stores'));
        ok(null);
      };
      r.onerror = function () { ctx.falhou('idbSentinela', r.error); ok(null); };
    });
  }

  // { pendentes: Set, incompleta } do banco sentinela, ou null se não deu para ler (quem chama não apaga nada no nativo).
  // Qualquer valor na marca conta como 'incompleta': na dúvida, o lado que não apaga.
  function lerCasca(ctx, idb) {
    return abrirCasca(ctx, idb).then(function (db) {
      if (!db) return null;
      return new Promise(function (ok) {
        var pendentes = new Set();
        var incompleta = true;
        try {
          var tx = db.transaction([STORE_PENDENTES, STORE_ESTADO], 'readonly');
          var chaves = tx.objectStore(STORE_PENDENTES).getAllKeys();
          chaves.onsuccess = function () { (chaves.result || []).forEach(function (k) { pendentes.add(k); }); };
          var marca = tx.objectStore(STORE_ESTADO).get(CHAVE_RESTAURACAO);
          marca.onsuccess = function () { incompleta = marca.result !== undefined; };
          tx.oncomplete = function () { fechar(db); ok({ pendentes: pendentes, incompleta: incompleta }); };
          tx.onabort = function () {
            fechar(db);
            ctx.falhou('idbSentinela', tx.error || new Error('leitura do banco sentinela abortada'));
            ok(null);
          };
        } catch (e) {
          fechar(db);
          ctx.falhou('idbSentinela', e);
          ok(null);
        }
      });
    });
  }

  // Uma transação de escrita nas duas stores do banco sentinela. Resolve true se ela terminou (complete), false se não
  // (o erro já foi avisado). Nunca rejeita.
  function transacaoCasca(ctx, db, fn) {
    return new Promise(function (ok) {
      var tx = null;
      try {
        tx = db.transaction([STORE_PENDENTES, STORE_ESTADO], 'readwrite');
        fn(tx.objectStore(STORE_PENDENTES), tx.objectStore(STORE_ESTADO));
      } catch (e) {
        try { if (tx) tx.abort(); } catch (_) { /* já terminou */ }
        ctx.falhou('idbSentinela', e);
        ok(false);
        return;
      }
      tx.oncomplete = function () { ok(true); };
      tx.onabort = function () { ctx.falhou('idbSentinela', tx.error || new Error('gravação abortada')); ok(false); };
    });
  }

  // Só a marca 'incompleta', no banco sentinela (que nasce com ela, se faltar); as pendentes ficam como estão. É o
  // primeiro passo de toda restauração, antes do idbPedir (ver A RESTAURAÇÃO). Resolve true se gravou; nunca rejeita.
  function marcarIncompleta(ctx, idb) {
    return abrirCasca(ctx, idb).then(function (db) {
      if (!db) return false;
      return transacaoCasca(ctx, db, function (lista, marca) { idb.put.call(marca, 'incompleta', CHAVE_RESTAURACAO); })
        .then(function (marcou) { fechar(db); return marcou; });
    });
  }

  // Grava o estado de uma restauração no banco sentinela (e o cria, se faltar): as `pendentes` trocam as de antes, e a
  // marca vai ou sai. Em duas transações: primeiro só a marca, depois as pendentes e a marca final juntas. Entre as duas,
  // ou se a segunda não entrar (o disco cheio), o estado diz 'incompleta', e a reconciliação não apaga nada no nativo.
  function gravarCasca(ctx, idb, pendentes, incompleta) {
    return abrirCasca(ctx, idb).then(function (db) {
      if (!db) return undefined;
      return transacaoCasca(ctx, db, function (lista, marca) { idb.put.call(marca, 'incompleta', CHAVE_RESTAURACAO); })
        .then(function (marcou) {
          if (!marcou) return undefined;
          return transacaoCasca(ctx, db, function (lista, marca) {
            lista.clear();
            for (var i = 0; i < pendentes.length; i++) idb.put.call(lista, 1, pendentes[i]);
            if (incompleta) idb.put.call(marca, 'incompleta', CHAVE_RESTAURACAO);
            else idb.apagar.call(marca, CHAVE_RESTAURACAO);
          });
        })
        .then(function () { fechar(db); });
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

  // O TETO DA SEÇÃO: o `pronto` (e com ele o open da página) espera a conferência no máximo ESPERA_NATIVO_MS, seja o
  // que for que não responda: o databases(), um open, o nativo. No prazo, `estado.vencido` liga e os `estado.aoVencer`
  // rodam (a restauração em andamento grava o banco sentinela). Daí em diante a conferência não decide mais nada, e a
  // restauração atrasada pula as chaves que a página gravou ou apagou (`estado.tocadas`, anotadas pelo espelho).
  function comTeto(ctx, idb, estado, conferir) {
    return new Promise(function (resolver) {
      var solto = false;
      var timer = null;
      function soltar() {
        if (solto) return;
        solto = true;
        if (timer !== null) { idb.cancelar(timer); timer = null; }
        resolver();
      }
      timer = idb.agendar(function () {
        timer = null;
        if (solto) return;
        estado.vencido = true;
        ctx.falhou('idb', new Error('a conferência do IndexedDB não terminou em 15 s: a página segue'));
        for (var i = 0; i < estado.aoVencer.length; i++) {
          try { estado.aoVencer[i](); } catch (e) { ctx.falhou('idb', e); }
        }
        soltar();
      }, ESPERA_NATIVO_MS);
      var conferencia;
      try { conferencia = conferir(); } catch (e) { ctx.falhou('idb', e); }
      Promise.resolve(conferencia).then(soltar, function (e) { ctx.falhou('idb', e); soltar(); });
    });
  }

  // Liga a seção 5: o espelho, a conferência da partida e o open segurado até ela terminar (ou até o teto). A carga
  // parada vai recarregar, e a carga sem espelho não toca no IndexedDB (ver A CARGA SEM ESPELHO): nas duas, nada é
  // embrulhado, e o open da página passa direto.
  function instalarIDB(ctx) {
    if (ctx.cargaParada || ctx.espelhoDesligado) return;
    var idb = capturarIDB(ctx.win);
    if (!idb) return;
    // vencido: o teto passou; aoVencer: o que roda nessa hora; tocadas: as chaves que a página mexeu depois dele.
    var estado = { vencido: false, aoVencer: [], tocadas: new Set() };
    var envio = criarEnvioIDB(ctx);
    instalarEspelhoIDB(ctx, idb, envio, estado);
    var pronto = comTeto(ctx, idb, estado, function () { return conferirIDB(ctx, idb, envio, estado); });
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
      STORE_PENDENTES: STORE_PENDENTES,
      STORE_ESTADO: STORE_ESTADO,
      CHAVE_RESTAURACAO: CHAVE_RESTAURACAO,
    };
  } else if (typeof window !== 'undefined') {
    instalar(window, window.gcCasca, window.__GC_RETRATO__ || {});
  }
})();
