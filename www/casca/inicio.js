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
 *   7. o voltar do Android (window.__gcCascaVoltar)               (onda final)
 *   8. o registro do aparelho para as notificações               (notificações, Task 3)
 *   9. abrir a OS pelo toque na notificação (__gcCascaAbrirOS)   (notificações, Task 4)
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
        // A hora de cada ação do técnico por tarefa. Vai junto com o gc_campo_status: sem ela, o `reconciliar` do site
        // (o reparo do estado órfão) toma por lixo o estado local sem item na fila, e o estado restaurado some.
        'gc_campo_status_meta',
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

  // O que o VOLTAR DO ANDROID (seção 7) usa do campo.html (v282, lido em 17/10/2026), e só daqui: o teste de contrato com
  // o site importa este objeto e exige cada nome no HTML vivo. Se o site trocar um, o voltar daquela camada só manda o
  // app para o fundo (como antes da casca tratar o voltar), sem quebrar a página.
  //   id:     as camadas e as telas das abas (sem o '#');
  //   classe: os botões que o técnico tocaria e as marcas de camada aberta ('open') e de aba ativa ('on');
  //   funcao: as funções de topo da página, que no script clássico dela são propriedades da janela;
  //   global: o `let` de topo que não vira propriedade da janela e é lido pelo nome (ver tarefaDoFechamento).
  var VOLTAR = {
    id: {
      camera: 'cam', sonda: 'sonda', esperaChamado: 'cham-wait', exigeGps: 'geo-exige', apr: 'apr-modal',
      extintores: 'ext-tela', fora: 'fora-modal', ver: 'ver-modal', fechamento: 'sheet', feedback: 'fb-sheet',
      os: 'modal', corpoDaOs: 'm-body', menu: 'user-menu', ronda: 'tela-ronda', zeladoria: 'tela-zl',
      sincronizacao: 'tela-sync', conquistas: 'tela-conquistas', acompanhamento: 'tela-acomp',
    },
    classe: {
      aberta: 'open', abaAtiva: 'on', foto: 'rd-lb', cameraVolta: 'cam-volta', extintoresVolta: 'ext-volta',
      fecharOs: 'm-close-btn', rondaVolta: 'rd-voltar', zeladoriaVolta: 'zl-volta',
    },
    funcao: {
      camera: 'camFechar', aprSalvar: '_aprRascSalvar', aprFechar: 'aprFechar', extintores: 'extFechar',
      fora: 'foraFechar', ver: 'fecharVer', fechamento: 'fecharSheet', abrirOs: 'abrirModal', feedback: 'fecharFeedback',
      os: 'fecharModal', menu: 'fecharUserMenu', aba: 'setTab',
    },
    global: { tarefaDoFechamento: 'sheetTask' },
    abaInicial: 'tarefas',
  };

  // O que o REGISTRO DO APARELHO (seção 8) usa do campo.html (v284, lido em 18/10/2026), e só daqui: o teste de
  // contrato com o site exige cada nome no HTML vivo. Se o site trocar um, o aparelho deixa de ser registrado (ou o sair
  // deixa de esquecê-lo), sem quebrar a página.
  //   funcao.bearer: a função de topo que dá o token da Microsoft da conta ativa (null sem login; não lança);
  //   funcao.sair:   a `async function` de topo do "Sair", que a casca embrulha para esquecer o aparelho antes;
  //   global:        a `const` de topo com o endereço do servidor, que não vira propriedade da janela e é lida pelo nome
  //                  (ver enderecoDoServidor).
  var PUSH = {
    funcao: { bearer: 'getTokenMW', sair: 'sair' },
    global: { middleware: 'MIDDLEWARE_URL' },
  };

  // O que a ABERTURA DA OS PELO TOQUE na notificação (seção 9) usa do campo.html (v284, lido em 18/10/2026), e só daqui: o
  // teste de contrato com o site exige cada nome no HTML vivo. Se o site trocar um, o toque na emergência abre o app sem
  // a busca (o técnico procura a OS à mão), sem quebrar a página.
  //   funcao.buscar: a função de topo que o campo de busca chama a cada digitação (`oninput="setBusca(this.value)"`);
  //                  em script clássico ela é propriedade da janela;
  //   id.busca:      o campo de busca por OS (sem o '#').
  var ABRIR_OS = {
    funcao: { buscar: 'setBusca' },
    id: { busca: 'busca-os' },
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
      // a seção 5 devolve também o IndexedDB do nativo (o que falta na WebView), com o banco sentinela de pé ou não.
      // Esta carga espelha (o localStorage acabou de voltar do nativo), mesmo com a marca da perda ainda de pé.
      restauracaoDePerda: false,

      // Na carga da restauração da perda, a função (da seção 4) que tira a marca e grava a sentinela: a perda só termina
      // quando o localStorage E o IndexedDB voltaram (ver A MARCA DA PERDA). Quem a chama é a seção 5, no fim da
      // restauração dela (`idbCuidaDaPerda`), ou o `instalar`, quando a seção 5 nem roda nesta carga (sem IndexedDB).
      concluirPerda: null,
      idbCuidaDaPerda: false,

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
      // `event.data` em JSON). Ouvem a restauração das fotos (seção 5) e o registro do aparelho (seção 8), cada uma
      // pelo `t` dela; o `onmessage` é um só, e cada mensagem vai a todas, na ordem em que se registraram (sem isso, a
      // segunda a ouvir tirava a primeira). O texto que não for JSON vira um aviso sem o conteúdo: a mensagem do
      // JSON.parse cita o texto, e ele pode ter nome de chave.
      ouvirNativo: function (fn) {
        ouvintes.push(fn);
        canal.onmessage = entregarAosOuvintes;
      },
    };
    var ouvintes = [];
    function entregarAosOuvintes(evento) {
      var mensagem;
      try {
        var dado = evento && evento.data;
        mensagem = typeof dado === 'string' ? JSON.parse(dado) : dado;
      } catch (e) {
        ctx.falhou('canal', new Error('mensagem do nativo que não é JSON'));
        return;
      }
      var lista = ouvintes.slice();
      for (var i = 0; i < lista.length; i++) {
        try { lista[i](mensagem); } catch (e) { ctx.falhou('canal', e); }
      }
    }
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
  // sem espelho. A exceção é a carga que aplica o retrato completo da perda: o localStorage dela acabou de voltar do
  // nativo, e ela espelha. A marca só sai quando a restauração inteira terminou, o localStorage E o IndexedDB das fotos
  // (o idbFim recebido e as gravações conferidas, na seção 5). Caso que criou a regra: a restauração do IndexedDB que
  // morria no meio (o técnico toca em Entrar logo depois da perda e o loginRedirect leva a página) não era retomada, e a
  // página dava por enviadas as fotos que só o nativo tinha. Com a marca de pé, a carga seguinte para e pede o retrato de
  // novo, e a restauração recomeça inteira (é idempotente: só volta o que falta na WebView, no localStorage e nas fotos,
  // e o que ela tem fica; ver A RESTAURAÇÃO NUNCA SOBRESCREVE). Quem segura o laço é o disjuntor do nativo (3
  // restaurações de perda por 24 h, contadas à parte das só do IndexedDB).
  // A da fila não mexe na marca. A página nunca a grava nem a apaga de propósito (o classificar a deixa fora do espelho
  // e da restauração); o despejo do `lsS` vai da maior chave para a menor e só chegaria a ela, que é pequena, depois de
  // derrubar todas as maiores que ele não protege (os caches).
  var VALOR_MARCA = '1';

  // A MEMÓRIA DA SESSÃO. O nativo registra o retrato leve uma vez por partida, e ele vale para toda carga dela: as
  // versões da fila que ele traz envelhecem. Caso: o técnico abre o app com pendência (versões ['[x]', '[]']), a página
  // envia e grava '[]' (espelhada, o nativo a põe na frente); uma recarga na mesma partida (loginRedirect do MSAL,
  // atualização do service worker, 'Tentar de novo') acharia '[]' igual a uma versão antiga e pararia a carga à toa.
  // O sessionStorage da origem dura o mesmo que a partida (a WebView o mantém entre as cargas e o esquece ao fechar),
  // e guarda três chaves, sempre pelo setItem original (o espelho só olha o localStorage):
  //   SESSAO_FILA     fnv1a32 da última fila que o script espelhou ou restaurou nesta sessão: o nativo a tem como a
  //                   mais nova, então a WebView com ela está em dia, diga o retrato o que disser;
  //   SESSAO_PAROU    esta sessão parou uma carga e o pedido ainda não foi atendido. O script nunca para duas: se o
  //                   nativo não atendesse o segundo pedido, a página ficaria em branco até matar o app. Sai quando um
  //                   retrato completo é aplicado (com a fila dele no lugar): senão, depois de uma restauração, a sessão
  //                   inteira (às vezes o dia, com o app vivo) não pararia mais carga nenhuma, e uma segunda perda nela,
  //                   ou a restauração interrompida, cairia na carga sem espelho;
  //   SESSAO_RETRATO  o id do último retrato completo aplicado: o mesmo retrato, de novo, regravaria sem espelhar por
  //                   cima do que a página fez depois dele;
  //   SESSAO_LS       o localStorage já foi mandado inteiro ao espelho recriado nesta sessão (ver reenviarLS).
  // Sem sessionStorage (falta, lança, ou a página o limpou no "limpar só o login"), vale a regra de antes: só as
  // versões do retrato.
  var SESSAO_FILA = 'gc_casca_fila_sessao';
  var SESSAO_PAROU = 'gc_casca_parou';
  var SESSAO_RETRATO = 'gc_casca_retrato_aplicado';
  var SESSAO_LS = 'gc_casca_ls_reenviado';

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
  function apagarSessao(ls, chave) {
    try { if (ls.sessao) ls.removeItem.call(ls.sessao, chave); } catch (e) { /* sem memória: nada a apagar */ }
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
  //   com sentinela, sem espelho                       → reenvia 'sentinela', confere a fila e manda o resto do
  //                                                       localStorage (reenviarLS);
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
    var mandarOResto = false;
    if (ls.getItem.call(ls.real, SENTINELA_LS) === null) {
      if (!temEspelho) {
        if (gravarSentinela(ctx, ls)) mandarOResto = ctx.enviar({ t: 'sentinela' }, 'sentinela');
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
      mandarOResto = ctx.enviar({ t: 'sentinela' }, 'sentinela');
    }

    conferirFila(ctx, ls);
    if (mandarOResto && !ctx.espelhoDesligado) reenviarLS(ctx, ls);
  }

  // O ESPELHO RECRIADO. O nativo sem a marca do espelho (temEspelho falso) com a WebView em uso é o banco dele que foi
  // recriado: o SQLite corrompido que o Android apaga e abre vazio, ou a `sentinela` da instalação que se perdeu. A fila
  // a conferirFila já mandou (sem versões no nativo, a da WebView está à frente); o resto do localStorage só chegaria a
  // ele quando a página o regravasse, e a gc_campo_ident é gravada uma vez, no login. Uma perda nesse meio tempo
  // devolveria a fila sem os rascunhos. Então vai tudo que é trabalho e cache (o trabalho primeiro), lido agora, antes
  // de qualquer script da página, uma vez por sessão (SESSAO_LS: o retrato leve da partida continua dizendo temEspelho
  // falso nas recargas dela). Só manda o que a WebView tem; nunca manda apagar. Se o canal recusar alguma, a marca da
  // sessão não entra e a carga seguinte manda de novo.
  function reenviarLS(ctx, ls) {
    if (lerSessao(ls, SESSAO_LS) !== null) return;
    var todas = true;
    var chaves = chavesDoEspelho(ls);
    for (var j = 0; j < chaves.length; j++) {
      var k = chaves[j];
      var v = ls.getItem.call(ls.real, k);
      if (v !== null && !ctx.enviar({ t: 'ls', k: k, v: v }, 'reenvio')) todas = false;
    }
    if (todas) gravarSessao(ls, SESSAO_LS, '1');
  }

  // As chaves de trabalho e de cache que o localStorage tem agora, o trabalho primeiro (prioridade maior), cada classe
  // na ordem do localStorage. A fila, a sentinela, a marca e o que a casca não espelha ficam de fora (ver CLASSES).
  function chavesDoEspelho(ls) {
    var real = ls.real;
    var trabalho = [];
    var cache = [];
    for (var i = 0; i < real.length; i++) {
      var chave = real.key(i);
      var classe = classificar(chave);
      if (classe === 'trabalho') trabalho.push(chave);
      else if (classe === 'cache') cache.push(chave);
    }
    return trabalho.concat(cache);
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
  // `naoCouberam` e a próxima ainda é tentada (uma pequena pode caber depois de uma grande). Na perda, a marca sai e a
  // sentinela entra só quando o IndexedDB também voltou (ctx.concluirPerda); na fila, a sentinela entra já.
  // A RESTAURAÇÃO NUNCA SOBRESCREVE: a chave de trabalho ou de cache que a WebView tem (com valor) fica como está, e só
  // as ausentes voltam (a fila tem a regra dela: juntarFilas). A que a WebView tem é a mais nova: o nativo só recebe o
  // que a página espelha, e uma carga sem espelho não manda nada. Caso que criou a regra: a restauração das fotos que
  // não termina (a marca fica) e o disjuntor do dia deixam a página rodar sem espelho, com o localStorage restaurado,
  // por até um dia; o técnico mexe nas pendências e nos rascunhos, e a restauração da janela seguinte gravava por cima a
  // versão velha do nativo. A perda de verdade leva o localStorage inteiro, e nela tudo volta. Custo aceito: o valor da
  // WebView fica no lugar do do nativo só quando ela não perdeu aquela chave, e aí ela é a mais nova. Como antes, a
  // chave que a página apagou numa carga sem espelho volta (a exclusão não chegou ao nativo). O que a WebView manteve
  // vai ao nativo logo depois do `restaurado` (reenviarOQueAWebViewManteve, no fim desta função), na perda.
  // A fila que voltou e o id do retrato ficam na memória da sessão (SESSAO_FILA e SESSAO_RETRATO), e a trava sai.
  // O `motivo` do retrato é o do pedido que ele atende. Com 'fila' o nativo manda `ls` vazio, e só a fila é regravada.
  // Do `ls` do retrato só entra o que o classificar diz que é trabalho ou cache: msal.*, a sentinela, a própria fila
  // (que vem no campo `fila`) e as preferências ficam de fora, mesmo que o nativo as mande por engano.
  function restaurarLS(ctx, ls) {
    var retrato = ctx.retrato;
    var origem = retrato.ls && typeof retrato.ls === 'object' ? retrato.ls : {};
    // Sem `motivo` conta como perda: uma marca que nunca saísse deixaria toda carga sem espelho, partida após partida.
    var daPerda = retrato.motivo !== 'fila';
    var naoCouberam = [];
    var chaves = 0;
    var fila = false;
    var juntada = null;

    if (typeof retrato.fila === 'string') {
      // Na perda, a fila que o técnico fez numa carga sem espelho (a trava, o semParar) entra junto com a do nativo.
      if (daPerda) juntada = juntarFilas(ctx, ls, retrato.fila);
      var valorFila = juntada !== null ? juntada : retrato.fila;
      fila = gravarOriginal(ls, CHAVE_FILA, valorFila);
      if (!fila) naoCouberam.push(CHAVE_FILA);
      // A do nativo, ele já tem como a mais nova. A junta só vale como em dia depois de chegar a ele (abaixo).
      else if (juntada === null) gravarSessao(ls, SESSAO_FILA, fnv1a32(valorFila));
    }

    var nomes = Object.keys(origem);
    var classes = ['trabalho', 'cache'];
    for (var c = 0; c < classes.length; c++) {
      for (var i = 0; i < nomes.length; i++) {
        var chave = nomes[i];
        var valor = origem[chave];
        if (classificar(chave) !== classes[c] || typeof valor !== 'string') continue;
        if (ls.getItem.call(ls.real, chave) !== null) continue; // a da WebView fica: A RESTAURAÇÃO NUNCA SOBRESCREVE
        if (gravarOriginal(ls, chave, valor)) chaves++;
        else naoCouberam.push(chave);
      }
    }

    if (daPerda) {
      // A perda só termina com o IndexedDB das fotos de volta (seção 5, ou o `instalar` sem ela): a marca sai pelo
      // original (sem eco), depois do que voltou, e antes da sentinela, que ganha o espaço dela. Se a carga morrer antes,
      // a seguinte ainda vê a perda e pede de novo (ver A MARCA DA PERDA). O retrato da fila não mexe na marca.
      ctx.restauracaoDePerda = true;
      ctx.concluirPerda = function () {
        ctx.concluirPerda = null; // uma vez só
        try { ls.removeItem.call(ls.real, MARCA_PERDA); } catch (e) { ctx.falhou('marca', e); }
        gravarSentinela(ctx, ls);
      };
    } else {
      // Por último a sentinela. Se não couber, o que voltou fica (inclusive o cache, que o técnico usa sem sinal): a
      // carga seguinte acha a WebView com chaves e não toma a falta da sentinela por perda.
      gravarSentinela(ctx, ls);
    }

    // Aplicado: o mesmo retrato numa recarga desta sessão segue como leve (ver SESSAO_RETRATO), e o pedido que parou a
    // carga está atendido, então a trava sai. Com a fila que não coube, fica: senão a fila nula com versões no nativo
    // pararia a carga seguinte e pediria o mesmo retrato que de novo não cabe.
    var id = idDoRetrato(retrato);
    if (id !== null) gravarSessao(ls, SESSAO_RETRATO, id);
    if (fila || typeof retrato.fila !== 'string') apagarSessao(ls, SESSAO_PAROU);

    ctx.enviar({ t: 'restaurado', chaves: chaves, naoCouberam: naoCouberam, fila: fila }, 'restaurado');
    // A junta é nova para o nativo: vai depois do restaurado, pelo caminho único da fila (e fica lembrada na sessão).
    if (juntada !== null && fila) espelharFila(ctx, ls, juntada, 'fila');
    // E o que a WebView manteve (a restauração não a sobrescreve) também é novo para ele: só a perda; a restauração da
    // fila traz o `ls` vazio, e vazio ali não quer dizer que o nativo não tem.
    if (daPerda) reenviarOQueAWebViewManteve(ctx, ls, origem);
  }

  // O QUE A WEBVIEW MANTEVE VAI AO NATIVO. A restauração da perda nunca sobrescreve a chave que a WebView tem (acima), e o
  // nativo ficaria com a cópia velha dela até a página regravar a chave: o rascunho do fechamento refeito numa carga sem
  // espelho só chegaria a ele quando o técnico mexesse nele de novo. Se a WebView fosse perdida OUTRA vez antes disso, a
  // restauração seguinte devolveria o rascunho de antes. Então, depois do `restaurado`, vai ao nativo cada chave de
  // trabalho e de cache cujo valor na WebView difere do `ls` do retrato (ou que o retrato não tem), o trabalho primeiro,
  // pelo mesmo `enviar` e rótulo do reenvio do espelho recriado. Só manda o que a WebView tem: nunca manda apagar. É de
  // melhor esforço: a chave que o canal recusar é avisada (só o rótulo) e as outras seguem, e a página a regrava no
  // próximo setItem. O que acabou de voltar do nativo é igual ao dele e não é mandado de volta.
  function reenviarOQueAWebViewManteve(ctx, ls, doNativo) {
    var chaves = chavesDoEspelho(ls);
    for (var j = 0; j < chaves.length; j++) {
      var k = chaves[j];
      var v = ls.getItem.call(ls.real, k);
      if (v === null || (typeof doNativo[k] === 'string' && doNativo[k] === v)) continue;
      ctx.enviar({ t: 'ls', k: k, v: v }, 'reenvio');
    }
  }

  // JUNTAR AS FILAS (só na restauração da perda). A WebView pode ter fila própria, feita numa carga que rodou sem espelho
  // com a marca de pé (a trava da sessão, o semParar do disjuntor): o técnico fechou OS sem sinal, e a fila e as fotos
  // ficaram só na WebView. Gravar a do nativo por cima apagaria esses itens, e a faxina de órfãos da página levaria as
  // fotos deles. Se a da WebView difere da do retrato, não é uma versão que o nativo tem (filaHashes, ou a última que
  // esta sessão espelhou) e as duas são listas em JSON, fica a do nativo mais os itens da WebView que ela não tem
  // (comparando o JSON de cada item). Prefere duplicar a perder: um item que mudou na WebView entra de novo ao lado do
  // antigo. Devolve o texto da junta, ou null (vale a do nativo, como antes).
  function juntarFilas(ctx, ls, doNativo) {
    var daWebView = ls.getItem.call(ls.real, CHAVE_FILA);
    if (daWebView === null || daWebView === doNativo) return null;
    var hash = fnv1a32(daWebView);
    var versoes = Array.isArray(ctx.retrato.filaHashes) ? ctx.retrato.filaHashes : [];
    if (versoes.indexOf(hash) >= 0 || hash === lerSessao(ls, SESSAO_FILA)) return null;
    var nativo = null;
    var webview = null;
    try {
      nativo = JSON.parse(doNativo);
      webview = JSON.parse(daWebView);
    } catch (e) { /* abaixo: o erro do JSON.parse cita o texto, e ele não vai ao nativo */ }
    if (!Array.isArray(nativo) || !Array.isArray(webview)) {
      ctx.falhou('fila', new Error('a fila da WebView não é uma lista em JSON: fica a do nativo'));
      return null;
    }
    var vistos = new Set();
    for (var i = 0; i < nativo.length; i++) vistos.add(JSON.stringify(nativo[i]));
    var junta = nativo.slice();
    for (var j = 0; j < webview.length; j++) {
      var item = JSON.stringify(webview[j]);
      if (vistos.has(item)) continue;
      vistos.add(item);
      junta.push(webview[j]);
    }
    return junta.length > nativo.length ? JSON.stringify(junta) : null;
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

  // O PRAZO DA SEÇÃO (ver comPrazo): a página espera para abrir o banco das fotos enquanto a conferência anda. É de
  // INATIVIDADE: reinicia a cada item ou pedaço que o nativo manda, e vence com ESPERA_INATIVIDADE_MS sem nada; com um
  // teto absoluto TETO_ABSOLUTO_MS desde o começo. Caso que criou a regra: o teto fixo de 15 s soltava a página no meio
  // de uma restauração saudável (vários vídeos de 15 MB, 512 KB por mensagem, num aparelho de 4 GB), e o envio da fila,
  // que roda no início com sinal, dava por enviadas as fotos que ainda não tinham chegado. Só o nativo que parou de
  // responder solta a página antes do teto.
  var ESPERA_INATIVIDADE_MS = 20000;
  var TETO_ABSOLUTO_MS = 180000;

  // Memória da sessão (ver A MEMÓRIA DA SESSÃO, na seção 4): a reconciliação já rodou nesta sessão da WebView.
  var SESSAO_IDB = 'gc_casca_idb_reconciliado';

  // O BANCO SENTINELA (BANCO_SENTINELA, `gc_casca`) quer dizer uma coisa só: a última restauração do IndexedDB desta
  // WebView terminou (ou não havia o que restaurar: a instalação nova). Só a existência dele conta (ele não guarda nada;
  // o de uma versão antiga da casca, com stores, vale igual). Toda restauração começa apagando-o, e ele só volta no fim
  // dela: com o idbFim recebido, o `n` batendo com as chaves que chegaram, e cada uma gravada, já presente na WebView ou
  // tocada pela página depois do prazo. A restauração que morre no meio (o loginRedirect, o renderer que morre num vídeo
  // grande) ou que não grava tudo (o disco cheio) deixa o banco faltando, e a carga seguinte restaura de novo: só as
  // chaves ausentes, sem passar por cima do que a WebView tem (ver restaurarIDB). Quem segura o laço do disco que recusa
  // sempre é o disjuntor do nativo (no máximo 3 restaurações só do IndexedDB por 24 h no aparelho, contadas fora da
  // cota do IndexedDB e à parte das de perda): estourado, ele responde idbRecusado, e a página abre na hora. Na perda
  // (a marca no localStorage), o mesmo vale para a marca, que só sai no fim (ver A MARCA DA PERDA, na seção 4).
  //
  // A RECONCILIAÇÃO (o banco sentinela de pé) nunca manda apagar no nativo: só reenvia o que falta lá. Uma chave que o
  // nativo tem e a WebView não pode ser a exclusão da página que se perdeu no canal, mas também o que ainda não voltou, e
  // na dúvida a cópia fica: o nativo só encolhe pelas exclusões da própria página, espelhadas na hora. Caso que criou a
  // regra: com o disco cheio, a restauração terminava sem gravar e sem registrar o que faltava, e a reconciliação da
  // partida seguinte apagava no nativo a foto que só ele tinha. Custo aceito: uma chave que a página apagou e cuja
  // exclusão se perdeu pode voltar numa restauração futura; a faxina de órfãos da página a limpa.
  //
  // A CARGA SEM ESPELHO (a perda que a trava não deixou parar, o stop que lançou, a marca da perda) não toca no
  // IndexedDB: nem restaura, nem reconcilia, nem cria ou apaga o banco sentinela. A faxina de órfãos da página que roda
  // assim (`idbKeys` + `idbDel` do campo.html, com o localStorage vazio) apaga fotos sem espelhar; quem as devolve é a
  // marca da perda, que leva a carga seguinte ao retrato completo, e a restauração da perda devolve o que falta no
  // IndexedDB junto com o localStorage, com o banco sentinela de pé ou não (o que a WebView tem fica: ver restaurarIDB).

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
  // Promise que nunca rejeita; o `comPrazo` a corta (ver O PRAZO DA SEÇÃO).
  //   restauração da perda (retrato completo, motivo   → restaura do nativo as chaves ausentes na WebView, esteja o
  //   'perda' ou sem motivo)                              banco sentinela de pé ou não (junto com o localStorage);
  //   sem indexedDB.databases()                        → "não sei": avisa e segue sem restaurar nem reconciliar;
  //   sem o banco sentinela                            → perda só do IndexedDB (ou a restauração que não terminou):
  //                                                       com espelho no nativo, restaura só as chaves ausentes; sem
  //                                                       (instalação nova), só cria o banco sentinela;
  //   com o banco sentinela                            → reconcilia, uma vez por sessão.
  function conferirIDB(ctx, idb, envio, estado) {
    var doNativo = chavesDoNativo(ctx.retrato);
    // O localStorage acabou de voltar do retrato completo (seção 4). Sem o IndexedDB junto, as fotos que ele cita e a
    // WebView perdeu (ou que a faxina de órfãos da página apagou numa carga sem espelho) não voltariam.
    if (ctx.restauracaoDePerda) return restaurarIDB(ctx, idb, envio, estado, 'perda', doNativo);
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
          if (ctx.retrato.temEspelho === true) return restaurarIDB(ctx, idb, envio, estado, 'idb', doNativo);
          return criarCasca(ctx, idb);
        }
        if (lerSessaoCtx(ctx, SESSAO_IDB) !== null) return undefined;
        return reconciliar(ctx, idb, envio, estado, nomes.has(BANCO_FOTOS), doNativo);
      })
      .then(null, function (e) { ctx.falhou('idb', e); });
  }

  // A RECONCILIAÇÃO, com o banco sentinela de pé e o espelho ligado (então sem a marca da perda): a WebView é a verdade,
  // e o que falta no nativo vai a ele. Nunca manda apagar nada lá (ver A RECONCILIAÇÃO).
  //   a store das fotos vazia e idbChaves não vazio → perda só do IndexedDB: restaura (nenhuma faxina da página esvazia
  //                                                   o banco inteiro; o despejo do Chromium pode levar um banco e
  //                                                   deixar o outro);
  //   chave da WebView que falta em idbChaves        → reenvia (o espelho perdeu a gravação: canal fora, app morto no
  //                                                   meio dos pedaços);
  //   chave de idbChaves que falta na WebView        → nada.
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
        if (!locais.length && doNativo.length) {
          fechar(db);
          return restaurarIDB(ctx, idb, envio, estado, 'idb', doNativo);
        }
        reenviarOQueFalta(ctx, db, envio, locais, new Set(doNativo));
        return undefined;
      });
    });
  }

  // Manda ao nativo as `locais` que ele não tem (`noNativo`), uma por vez, fecha o banco e marca a sessão se passou por
  // todas. Não segura a página: é o fim da reconciliação e da restauração.
  function reenviarOQueFalta(ctx, db, envio, locais, noNativo) {
    var faltam = locais.filter(function (k) { return !noNativo.has(k); });
    return reenviar(ctx, db, envio, faltam).then(function (terminou) {
      fechar(db);
      if (terminou) gravarSessaoCtx(ctx, SESSAO_IDB, '1');
    });
  }

  // Abre o banco das fotos só para ler. Ele estava na lista do databases(); se sumiu desde então, o open o criaria vazio
  // e a reconciliação o tomaria por perda: aborta, nada é criado, e a próxima carga confere de novo.
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

  // A RESTAURAÇÃO: apaga o banco sentinela (ver O BANCO SENTINELA), pede ao nativo o que ele guarda
  // ({t:'idbPedir', motivo}) e grava cada item no banco real pelo put original, sem espelhar. Respostas:
  // {t:'idbItem', k, tipo, v} e {t:'idbPedaco', k, i, n, mime, b64} (pedaços de um Blob, na ordem ou não), e no fim
  // {t:'idbFim', n}, com `n` = quantas entradas (chaves) o nativo mandou; ou {t:'idbRecusado'}, quando o disjuntor do
  // nativo não deixa (a página abre na hora, e a próxima janela tenta de novo).
  // O `motivo` vai ao nativo porque o disjuntor dele conta a restauração uma vez só e em separado: a da perda ('perda')
  // já foi contada no precisoRetrato; a só do IndexedDB ('idb') é contada aqui, na conta dela.
  // As duas gravam só as chaves AUSENTES na WebView e nunca sobrescrevem uma que existe: o espelho pode ter perdido a
  // última gravação de um rascunho, ou a página rodou sem espelho depois de uma restauração da perda que não terminou
  // (ver A RESTAURAÇÃO NUNCA SOBRESCREVE, na seção 4), e o da WebView é o novo. Na 'perda', no fim, a perda termina
  // (ctx.concluirPerda: sai a marca, entra a sentinela).
  // Terminou (idbFim com o `n` batendo, nenhuma falha, todo Blob inteiro, toda chave resolvida): o banco sentinela volta
  // e o que só a WebView tem vai ao nativo (o mesmo reenvio da reconciliação). Não terminou: avisa só com contagens, e a
  // carga seguinte restaura de novo. Uma chave que o idbChaves anunciou e o nativo não mandou não conta: o `n` é o que ele
  // mandou (o blob ilegível no espelho fica de fora lá, com aviso no log dele), e contá-la travaria a perda para sempre.
  // Depois do prazo a página já abriu o banco: o que chega atrasado ainda é gravado, menos o que a página tocou
  // (`estado.tocadas`, que conta como resolvido: o dela vence), e o idbFim atrasado ainda termina a restauração.
  function restaurarIDB(ctx, idb, envio, estado, motivo, doNativo) {
    var win = ctx.win;
    var daPerda = motivo === 'perda';
    return new Promise(function (resolver) {
      var recebidas = new Set();  // as chaves que chegaram, inteiras ou não
      var resolvidas = new Set(); // gravadas, já presentes ou tocadas pela página depois do prazo
      var falhas = 0;
      var fim = false;
      var pedacos = new Map(); // chave -> { n, mime, partes, chegaram }
      var banco = Promise.resolve(null); // o banco das fotos, aberto depois de o banco sentinela sair
      var gravacoes = Promise.resolve();

      function gravar(k, valor) {
        gravacoes = gravacoes.then(function () {
          return banco.then(function (db) {
            if (!db) { falhas++; return undefined; }
            // O que a página gravou ou apagou depois do prazo vence a cópia que chega atrasada.
            if (estado.tocadas.has(k)) { resolvidas.add(k); return undefined; }
            return gravarSeAusente(idb, db, k, valor).then(function (gravou) {
              if (gravou) resolvidas.add(k); else falhas++;
            });
          });
        });
      }

      function inteiro(x) { return typeof x === 'number' && x % 1 === 0; }

      function receberPedaco(m) {
        var k = m.k;
        var n = m.n;
        var i = m.i;
        if (typeof k !== 'string') { falhas++; return; }
        recebidas.add(k);
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
        recebidas.add(m.k);
        if (typeof m.v !== 'string') { falhas++; return; }
        var valor;
        if (m.tipo === 'texto') valor = m.v;
        else if (m.tipo === 'json') {
          try { valor = JSON.parse(m.v); } catch (e) { falhas++; return; }
        } else { falhas++; return; }
        gravar(m.k, valor);
      }

      function terminou(n) {
        if (n !== recebidas.size || falhas || pedacos.size) return false;
        var todas = true;
        recebidas.forEach(function (k) { if (!resolvidas.has(k)) todas = false; });
        return todas;
      }

      function concluir(n) {
        if (!terminou(n)) {
          banco.then(fechar);
          // Só contagens: nada de chave nem de valor.
          ctx.falhou('idbRestaurar', new Error('restauração incompleta: ' + recebidas.size + ' de ' +
            (inteiro(n) ? n : '?') + ' entradas, ' + falhas + ' falhas, ' + pedacos.size +
            ' sem todos os pedaços; a próxima carga restaura de novo'));
          return undefined;
        }
        return criarCasca(ctx, idb).then(function () {
          // Mesmo sem o banco sentinela (o disco cheio pegou só ele), as fotos voltaram: a perda termina, e a carga
          // seguinte confere de novo só as ausentes, que já estão todas.
          if (daPerda && ctx.concluirPerda) ctx.concluirPerda();
          var noNativo = new Set(doNativo);
          recebidas.forEach(function (k) { noNativo.add(k); });
          return banco.then(function (db) {
            if (!db) return undefined;
            return lerChaves(ctx, db).then(function (locais) {
              if (locais === null) { fechar(db); return undefined; }
              reenviarOQueFalta(ctx, db, envio, locais, noNativo);
              return undefined;
            });
          });
        });
      }

      ctx.ouvirNativo(function (m) {
        if (fim || !m || typeof m !== 'object') return;
        if (m.t === 'idbItem') { estado.atividade(); receberItem(m); }
        else if (m.t === 'idbPedaco') { estado.atividade(); receberPedaco(m); }
        else if (m.t === 'idbFim') {
          fim = true;
          gravacoes.then(function () { return concluir(m.n); })
            .then(null, function (e) { ctx.falhou('idbRestaurar', e); })
            .then(function () { resolver(); });
        } else if (m.t === 'idbRecusado') {
          fim = true;
          banco.then(fechar);
          ctx.falhou('idbRestaurar', new Error('o nativo recusou a restauração (o disjuntor dele, 3 por 24 h): a ' +
            'próxima janela tenta de novo'));
          resolver();
        }
      });

      // O pedido só sai com o banco sentinela apagado e o banco das fotos aberto. Sem um dos dois, nada a fazer nesta
      // carga: a restauração não terminou, e a seguinte tenta de novo. Se o pedido nem sai (o canal caiu), também.
      apagarCasca(ctx, idb).then(function (apagou) {
        if (!apagou || estado.vencido || fim) { resolver(); return undefined; }
        banco = abrirParaRestaurar(ctx, idb);
        return banco.then(function (db) {
          if (!db || estado.vencido || fim) { fechar(db); resolver(); return; }
          if (!ctx.enviar({ t: 'idbPedir', motivo: motivo }, 'idbPedir')) { fechar(db); resolver(); }
        });
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

  // Grava uma entrada pelo put original, numa transação só dela, só se a chave não está lá: conta a chave antes, na
  // mesma transação (a chave presente resolve do mesmo jeito: true, e o valor da WebView fica). Resolve false se a
  // transação não terminou (o disco cheio aborta, ou o put lança). Nunca rejeita.
  function gravarSeAusente(idb, db, k, valor) {
    return new Promise(function (ok) {
      try {
        var tx = db.transaction(STORE_FOTOS, 'readwrite');
        var store = tx.objectStore(STORE_FOTOS);
        tx.oncomplete = function () { ok(true); };
        tx.onabort = function () { ok(false); };
        var conta = store.count(k);
        conta.onsuccess = function () {
          if (conta.result !== 0) return;
          try { idb.put.call(store, valor, k); } catch (e) { try { tx.abort(); } catch (_) { /* já terminou */ } }
        };
      } catch (e) {
        ok(false);
      }
    });
  }

  // Apaga o banco sentinela (o começo de toda restauração). Resolve true quando ele não existe mais (inclusive quando nem
  // existia), false se o apagar falhou (avisado). Um apagar que nunca responde fica para o prazo da seção.
  function apagarCasca(ctx, idb) {
    return new Promise(function (ok) {
      var r;
      try { r = idb.apagarBanco.call(idb.fabrica, BANCO_SENTINELA); } catch (e) { ctx.falhou('idbSentinela', e); ok(false); return; }
      r.onsuccess = function () { ok(true); };
      r.onerror = function () { ctx.falhou('idbSentinela', r.error); ok(false); };
    });
  }

  // Cria o banco sentinela (vazio, versão 1; um que já existe só é aberto e fechado). Resolve true se deu, false se não
  // (avisado). Nunca rejeita.
  function criarCasca(ctx, idb) {
    return new Promise(function (ok) {
      var r;
      try { r = idb.abrir.call(idb.fabrica, BANCO_SENTINELA); } catch (e) { ctx.falhou('idbSentinela', e); ok(false); return; }
      r.onsuccess = function () { fechar(r.result); ok(true); };
      r.onerror = function () { ctx.falhou('idbSentinela', r.error); ok(false); };
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

  // O PRAZO DA SEÇÃO (ESPERA_INATIVIDADE_MS sem nada do nativo, ou TETO_ABSOLUTO_MS desde o começo): o `pronto` (e com
  // ele o open da página) espera a conferência, seja o que for que não responda: o databases(), um open, o apagar do
  // banco sentinela, o nativo. `estado.atividade()` (a restauração chama a cada item ou pedaço) reinicia a espera. No
  // prazo, `estado.vencido` liga: daí em diante a conferência não decide mais nada, e a restauração atrasada pula as
  // chaves que a página gravou ou apagou (`estado.tocadas`, anotadas pelo espelho).
  function comPrazo(ctx, idb, estado, conferir) {
    return new Promise(function (resolver) {
      var solto = false;
      var inativo = null;
      var absoluto = null;
      function soltar() {
        if (solto) return;
        solto = true;
        if (inativo !== null) { idb.cancelar(inativo); inativo = null; }
        if (absoluto !== null) { idb.cancelar(absoluto); absoluto = null; }
        resolver();
      }
      function vencer(motivo) {
        if (solto) return;
        estado.vencido = true;
        ctx.falhou('idb', new Error(motivo));
        soltar();
      }
      function armar() {
        if (solto) return;
        if (inativo !== null) idb.cancelar(inativo);
        inativo = idb.agendar(function () {
          inativo = null;
          vencer('a conferência do IndexedDB ficou 20 s sem resposta: a página segue');
        }, ESPERA_INATIVIDADE_MS);
      }
      estado.atividade = armar;
      armar();
      absoluto = idb.agendar(function () {
        absoluto = null;
        vencer('a conferência do IndexedDB passou de 180 s: a página segue');
      }, TETO_ABSOLUTO_MS);
      var conferencia;
      try { conferencia = conferir(); } catch (e) { ctx.falhou('idb', e); }
      Promise.resolve(conferencia).then(soltar, function (e) { ctx.falhou('idb', e); soltar(); });
    });
  }

  // Liga a seção 5: o espelho, a conferência da partida e o open segurado até ela terminar (ou até o prazo). A carga
  // parada vai recarregar, e a carga sem espelho não toca no IndexedDB (ver A CARGA SEM ESPELHO): nas duas, nada é
  // embrulhado, e o open da página passa direto.
  function instalarIDB(ctx) {
    if (ctx.cargaParada || ctx.espelhoDesligado) return;
    var idb = capturarIDB(ctx.win);
    if (!idb) return;
    // vencido: o prazo passou; tocadas: as chaves que a página mexeu depois dele; atividade: o comPrazo a troca.
    var estado = { vencido: false, tocadas: new Set(), atividade: function () {} };
    var envio = criarEnvioIDB(ctx);
    instalarEspelhoIDB(ctx, idb, envio, estado);
    // Daqui em diante, na carga da restauração da perda, é esta seção que a termina (no fim da restauração dela).
    if (ctx.restauracaoDePerda) ctx.idbCuidaDaPerda = true;
    var pronto = comPrazo(ctx, idb, estado, function () { return conferirIDB(ctx, idb, envio, estado); });
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
  // 7. O voltar do Android
  // ---------------------------------------------------------------------------------------------------------------

  // O Capacitor 8.5.3 não trata o voltar, e o campo.html não põe nada no histórico ao abrir uma camada (nem pushState,
  // nem <dialog>, nem popover): o gesto tirava o técnico do app no meio da OS. O nativo (um OnBackPressedCallback)
  // pergunta à página por window.__gcCascaVoltar(): ela decide NA HORA qual é a camada de cima e devolve true (o nativo
  // não faz mais nada), e a fecha no tempo seguinte (setTimeout 0), pelo mesmo botão ou função que o técnico usaria,
  // para um confirm() da página (o da Ronda com pendência) não segurar a resposta. Sem nada aberto devolve false, e o
  // nativo manda o app para o fundo (nunca o fecha). Um nome que o site trocou faz a camada não ser achada, ou a função
  // dela faltar: devolve false (só minimiza, como antes), nunca fecha a camada de baixo no lugar da de cima. Os nomes
  // vêm todos do VOLTAR (seção 1), que o contrato com o site confere. Se o site um dia adotar o histórico (pushState ao
  // abrir uma camada), esta seção sai: o nativo passa ao goBack(), senão a mesma camada fecha duas vezes.

  // O voltar consumido sem fazer nada: as esperas que travam a tela (o chamado sendo aberto, o GPS exigido, a sonda da
  // câmera) são de propósito sem saída, ou a única saída envia algo.
  var CONSUMIR = {};

  // A camada de cima e como fechá-la: uma função (que roda no tempo seguinte), CONSUMIR, ou null (nada aberto, ou o
  // jeito de fechar sumiu). A ordem é a do z-index do campo.html, de cima para baixo.
  function decidirVoltar(ctx) {
    var win = ctx.win;
    var doc = win.document;
    if (!doc || typeof doc.getElementById !== 'function') return null;
    function porId(id) { return doc.getElementById(id); }
    function temClasse(el, classe) { return !!el && !!el.classList && el.classList.contains(classe); }
    function aberta(el) { return temClasse(el, VOLTAR.classe.aberta); }
    function ativa(el) { return temClasse(el, VOLTAR.classe.abaAtiva); }
    function dentro(el, classe) { return el ? el.querySelector('.' + classe) : null; }
    function clicar(el) { return function () { el.click(); }; }
    function funcao(nome) {
      var f = win[nome];
      return typeof f === 'function' ? f : null;
    }
    function chamar(nome, args) {
      var f = funcao(nome);
      return f ? function () { return f.apply(win, args || []); } : null;
    }
    function botaoOu(botao, alternativa) { return botao ? clicar(botao) : alternativa; }
    var irParaTarefas = chamar(VOLTAR.funcao.aba, [VOLTAR.abaInicial]);

    // 1. A camada da própria casca (o aviso do Fracttal, seção 6), por cima de tudo: o Fechar dela.
    var camada = porId(ID_CAMADA);
    if (camada) {
      var fecharCamada = camada.querySelector('button');
      if (fecharCamada) return clicar(fecharCamada);
      return function () { if (camada.parentNode) camada.parentNode.removeChild(camada); };
    }
    // 2. A foto ampliada (z 9999): a última aberta é a de cima; o clique dela fecha e devolve o objectURL.
    var fotos = doc.querySelectorAll('.' + VOLTAR.classe.foto);
    if (fotos.length) return clicar(fotos[fotos.length - 1]);
    // 3. A câmera do App (z 1500; o elemento só existe aberta): o Voltar dela. As fotos já tiradas seguem na fila.
    var camera = porId(VOLTAR.id.camera);
    if (camera) return botaoOu(dentro(camera, VOLTAR.classe.cameraVolta), chamar(VOLTAR.funcao.camera));
    // 4. As esperas que travam a tela (z 560 e 1500).
    if (porId(VOLTAR.id.esperaChamado) || porId(VOLTAR.id.exigeGps) || aberta(porId(VOLTAR.id.sonda))) return CONSUMIR;
    // 5. A APR (z 555). O botão de fechar dela fecha sem salvar o rascunho (ele espera 300 ms, e o aprFechar zera a
    //    APR antes): aqui o rascunho é salvo primeiro, e a APR só é fechada se ainda estiver aberta. O rascunho que
    //    falha não segura o voltar (é o que o botão de fechar faria).
    if (aberta(porId(VOLTAR.id.apr))) {
      var aprFechar = funcao(VOLTAR.funcao.aprFechar);
      if (!aprFechar) return null;
      var aprSalvar = funcao(VOLTAR.funcao.aprSalvar);
      return function () {
        var salvo;
        try { salvo = aprSalvar ? aprSalvar.call(win) : undefined; } catch (e) { salvo = Promise.reject(e); }
        return Promise.resolve(salvo).then(null, function (e) { ctx.falhou('voltar', e); }).then(function () {
          if (aberta(porId(VOLTAR.id.apr))) aprFechar.call(win);
        });
      };
    }
    // 6. Os Extintores (z 545): o voltar da subtela (da conferência para a lista, da lista para fora).
    var extintores = porId(VOLTAR.id.extintores);
    if (aberta(extintores)) {
      return botaoOu(dentro(extintores, VOLTAR.classe.extintoresVolta), chamar(VOLTAR.funcao.extintores));
    }
    // 7. As folhas de baixo de z 520. O Concluir (#sheet) tinha substituído a OS (o abrirFechamentoTask a fecha): o
    //    fecharSheet salva o rascunho, e a OS volta, com a tarefa em foco.
    if (aberta(porId(VOLTAR.id.fora))) return chamar(VOLTAR.funcao.fora);
    if (aberta(porId(VOLTAR.id.ver))) return chamar(VOLTAR.funcao.ver);
    if (aberta(porId(VOLTAR.id.fechamento))) {
      var fecharSheet = funcao(VOLTAR.funcao.fechamento);
      if (!fecharSheet) return null;
      var abrirOs = funcao(VOLTAR.funcao.abrirOs);
      return function () {
        var tarefa = tarefaDoFechamento(win); // antes do fecharSheet, que a solta
        fecharSheet.call(win);
        if (tarefa && abrirOs && tarefa.osRef !== undefined && tarefa.osRef !== null) {
          abrirOs.call(win, tarefa.osRef, tarefa.tid);
        }
      };
    }
    if (aberta(porId(VOLTAR.id.feedback))) return chamar(VOLTAR.funcao.feedback);
    // 8. A OS (z 500), e também a Pausa e os avisos que reaproveitam o #modal: o botão do #m-body já é o passo anterior
    //    (o Cancelar da Pausa volta para a OS).
    if (aberta(porId(VOLTAR.id.os))) {
      return botaoOu(dentro(porId(VOLTAR.id.corpoDaOs), VOLTAR.classe.fecharOs), chamar(VOLTAR.funcao.os));
    }
    // 9. O menu do usuário.
    if (aberta(porId(VOLTAR.id.menu))) return chamar(VOLTAR.funcao.menu);
    // 10. As abas: a Ronda e a Zeladoria pelo voltar delas (o da Ronda pede confirmação com pendência); fora de uma
    //     tela interna, e nas outras abas, para Tarefas.
    var ronda = porId(VOLTAR.id.ronda);
    if (ativa(ronda)) return botaoOu(dentro(ronda, VOLTAR.classe.rondaVolta), irParaTarefas);
    var zeladoria = porId(VOLTAR.id.zeladoria);
    if (ativa(zeladoria)) return botaoOu(dentro(zeladoria, VOLTAR.classe.zeladoriaVolta), irParaTarefas);
    if (ativa(porId(VOLTAR.id.sincronizacao)) || ativa(porId(VOLTAR.id.conquistas)) ||
        ativa(porId(VOLTAR.id.acompanhamento))) return irParaTarefas;
    // 11. Tarefas, sem nada aberto.
    return null;
  }

  // A tarefa do Concluir. O `let` de topo do campo.html não vira propriedade da janela, mas todo script clássico da
  // página o enxerga pelo nome (o ambiente léxico global é um só, e este script também roda nele). Lido com typeof
  // dentro de try: antes da declaração dele (a zona morta), até o typeof lança.
  function tarefaDoFechamento(win) {
    var naJanela = win[VOLTAR.global.tarefaDoFechamento];
    if (naJanela) return naJanela;
    try {
      return typeof sheetTask !== 'undefined' ? sheetTask : null;
    } catch (e) {
      return null;
    }
  }

  function instalarVoltar(ctx) {
    var win = ctx.win;
    // Guardado agora, atado à janela, como no espelho do localStorage.
    var agendar = win.setTimeout.bind(win);
    function voltar() {
      try {
        var acao = decidirVoltar(ctx);
        if (acao === null) return false;
        if (acao !== CONSUMIR) {
          agendar(function () {
            try {
              var r = acao();
              if (r && typeof r.then === 'function') r.then(null, function (e) { ctx.falhou('voltar', e); });
            } catch (e) {
              ctx.falhou('voltar', e);
            }
          }, 0);
        }
        return true;
      } catch (e) {
        ctx.falhou('voltar', e);
        return false;
      }
    }
    // Não enumerável: o que da página varre a janela não a vê.
    Object.defineProperty(win, '__gcCascaVoltar', { value: voltar, enumerable: false, configurable: true, writable: false });
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 8. O registro do aparelho para as notificações (spec 2026-10-18, seção 4.2)
  // ---------------------------------------------------------------------------------------------------------------

  // O servidor do App de Campo manda os resumos e as emergências pelo Firebase ao token deste aparelho, e precisa saber
  // de quem ele é. Quem registra é a PRÓPRIA página, com o Bearer dela (o getTokenMW), como em toda chamada ao servidor:
  // o nativo nunca vê token da Microsoft, e o script não guarda o Bearer (pede na hora a cada chamada). O nativo só dá o
  // token do Firebase:
  //   script -> nativo: {t:'pushPedirToken'}, 5 s depois do load (a página já definiu o que esta seção usa, e a carga
  //                     já terminou);
  //   nativo -> script: {t:'pushToken', token (null: sem Firebase, ou o token ainda não saiu), versao}.
  // As rotas (da sessão "App de campo"): POST {MIDDLEWARE_URL}/push/registrar {token, aparelho:'android',
  // app:'NexusField/<versao>'} e POST {MIDDLEWARE_URL}/push/esquecer {token}, as duas com o Bearer da página.
  // UMA VEZ POR CARGA, SEM ATALHO (Ruling N3, 18/10/2026): toda carga registra, depois do login, com o Bearer da conta
  // de agora, e o servidor guarda pelo token (o mesmo registro de novo não muda nada; o de outra conta passa o aparelho
  // a ela). A primeira versão pulava o registro feito há menos de 7 dias, e o "já registrado" não sabia de QUEM: o
  // técnico que trocava de conta sem o Sair seguia recebendo no e-mail anterior, e depois do Sair (que tira o aparelho
  // do servidor) o nativo seguia achando que estava registrado, e o aparelho ficava sem aviso por até 7 dias. O custo é
  // uma chamada ao servidor do App de Campo por abertura (não ao Fracttal). Sem laço e sem nada na tela:
  //   - ninguém logado (o Bearer null): tenta de novo no próximo `online` ou na volta ao app (visibilitychange);
  //   - 404: a rota ainda não está no ar; não tenta mais nesta carga;
  //   - outro erro, ou sem rede: tenta no próximo `online`, no máximo uma vez a cada 10 minutos (o online que chega
  //     dentro da espera tenta quando ela acaba);
  //   - a carga seguinte pede o token e registra de novo.
  // O "Sair" (o aparelho dividido entre técnicos): antes do sair da página (que troca a tela e vai ao logout da
  // Microsoft, e a navegação cortaria a chamada), o servidor esquece o token, com prazo de 3 s; o sair da página roda
  // sempre, mesmo se o esquecer falhar. Todo aviso vai como {t:'erro', onde:'push'} com um texto nosso: nunca o token,
  // o Bearer nem o texto do erro (a mensagem da rede ou do MSAL pode citar endereço com parâmetro ou a conta).
  var ESPERA_PUSH_MS = 5000;
  var INTERVALO_PUSH_MS = 10 * 60 * 1000;
  var PRAZO_ESQUECER_MS = 3000;
  var ROTA_REGISTRAR = '/push/registrar';
  var ROTA_ESQUECER = '/push/esquecer';
  // A marca do sair já embrulhado: uma propriedade da função, não enumerável.
  var MARCA_SAIR = '__gcCascaSair';

  // O endereço do servidor, ou null. A `const` de topo do campo.html não vira propriedade da janela, mas todo script
  // clássico da página a enxerga pelo nome (o ambiente léxico global é um só, e este script também roda nele), como o
  // sheetTask do voltar. Lida com typeof dentro de try: antes da declaração dela (a zona morta), até o typeof lança. Na
  // janela falsa dos testes vem da propriedade.
  function enderecoDoServidor(win) {
    var naJanela = win[PUSH.global.middleware];
    if (typeof naJanela === 'string' && naJanela) return naJanela;
    try {
      return typeof MIDDLEWARE_URL === 'string' && MIDDLEWARE_URL ? MIDDLEWARE_URL : null;
    } catch (e) {
      return null;
    }
  }

  // Do erro, só o nome (TypeError, AbortError), que é uma palavra nossa conhecida; o texto dele nunca.
  function nomeDoErro(e) {
    var nome;
    try { nome = e && e.name; } catch (_) { nome = null; }
    return typeof nome === 'string' && /^[A-Za-z]{1,40}$/.test(nome) ? nome : 'erro';
  }

  // O status da resposta (um número), para o aviso.
  function situacao(resposta) {
    return resposta && typeof resposta.status === 'number' ? String(resposta.status) : 'sem status';
  }

  function instalarPush(ctx) {
    // A carga parada para pedir o retrato vai ser recarregada pelo nativo: a página nem roda.
    if (ctx.cargaParada) return;
    var win = ctx.win;
    var doc = win.document;
    if (typeof win.addEventListener !== 'function') return;
    // Guardados agora, atados à janela, como no espelho do localStorage.
    var agendar = win.setTimeout.bind(win);
    var cancelar = win.clearTimeout.bind(win);

    var token = null;          // o do Firebase, quando o nativo respondeu com um
    var versao = '';
    var emCurso = false;
    // O que faz tentar de novo: 'login' (sem Bearer: o próximo online ou a volta ao app) ou 'rede' (erro ou sem rede: o
    // próximo online, depois da espera). null é "nada mais nesta carga": registrado, a rota que ainda não está no ar
    // (404), ou a página sem o que esta seção usa.
    var aguardando = null;
    var esfriando = false;     // a última falha tem menos de 10 minutos
    var tentarDepois = false;  // um online chegou na espera: tenta quando ela acabar
    var carregou = false;

    function avisar(texto) {
      ctx.falhou('push', new Error(texto));
    }

    // O Bearer da página agora (o getTokenMW chamado como função de topo), ou null sem login. Nunca lança: a falha
    // sai como Promise rejeitada.
    function pedirBearer() {
      return new Promise(function (ok) { ok(win[PUSH.funcao.bearer]()); })
        .then(function (b) { return typeof b === 'string' && b ? b : null; });
    }

    function postar(rota, bearer, corpo, extra) {
      var opcoes = {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + bearer, 'Content-Type': 'application/json' },
        body: JSON.stringify(corpo),
      };
      for (var k in extra) if (Object.prototype.hasOwnProperty.call(extra, k)) opcoes[k] = extra[k];
      return win.fetch(enderecoDoServidor(win) + rota, opcoes);
    }

    // A falha que espera a rede: a próxima tentativa sai no próximo online, e não antes de 10 minutos.
    function esperarARede(texto) {
      avisar(texto);
      aguardando = 'rede';
      esfriando = true;
      agendar(function () {
        esfriando = false;
        if (tentarDepois) { tentarDepois = false; registrar(); }
      }, INTERVALO_PUSH_MS);
    }

    function registrar() {
      if (!token || emCurso) return;
      aguardando = null;
      if (typeof win[PUSH.funcao.bearer] !== 'function' || !enderecoDoServidor(win)) {
        avisar('a página não tem o ' + PUSH.funcao.bearer + ' ou o ' + PUSH.global.middleware +
          ': o aparelho não é registrado nesta carga');
        return;
      }
      emCurso = true;
      pedirBearer().then(function (bearer) {
        if (!bearer) { aguardando = 'login'; return undefined; }
        // O gcQuieto é o do próprio campo.html (v284): a barra de carregamento conta só o que o técnico pediu, e o
        // registro é de fundo.
        return postar(ROTA_REGISTRAR, bearer, { token: token, aparelho: 'android', app: 'NexusField/' + versao },
          { gcQuieto: true }).then(function (resposta) {
          // 2xx: registrado nesta carga, e nada mais até a próxima (o nativo não guarda nada do registro).
          if (resposta && resposta.ok) return;
          if (resposta && resposta.status === 404) {
            avisar('a rota de registro do aparelho ainda não está no ar (404): nada mais nesta carga');
          } else {
            esperarARede('o registro do aparelho respondeu ' + situacao(resposta) + '; tenta no próximo online');
          }
        });
      }).then(null, function (e) {
        esperarARede('o registro do aparelho falhou (' + nomeDoErro(e) + '); tenta no próximo online');
      }).then(function () { emCurso = false; });
    }

    // Pede ao servidor que esqueça o token, com o Bearer de agora (antes do logout). Resolve quando terminou, deu erro
    // ou passaram 3 s, o que vier primeiro; nunca rejeita. Ninguém logado: nada a pedir (o servidor não aceitaria).
    function esquecer(esteToken) {
      return new Promise(function (pronto) {
        var acabou = false;
        var prazo = agendar(function () {
          prazo = null;
          if (acabou) return;
          avisar('o esquecer do aparelho passou de 3 s: o sair segue');
          terminar();
        }, PRAZO_ESQUECER_MS);
        function terminar() {
          if (acabou) return;
          acabou = true;
          if (prazo !== null) { cancelar(prazo); prazo = null; }
          pronto();
        }
        pedirBearer().then(function (bearer) {
          if (!bearer || !enderecoDoServidor(win)) return undefined;
          return postar(ROTA_ESQUECER, bearer, { token: esteToken }, {}).then(function (resposta) {
            if (!resposta || !resposta.ok) avisar('o esquecer do aparelho respondeu ' + situacao(resposta) + ': o sair segue');
          });
        }).then(null, function (e) {
          avisar('o esquecer do aparelho falhou (' + nomeDoErro(e) + '): o sair segue');
        }).then(terminar);
      });
    }

    // O sair da página é definido depois deste script (que roda antes dela): embrulhado no load. Sem token a esquecer,
    // é o da página na hora, sem nada no meio. Com token, o da página vem depois do esquecer, com o mesmo this e os
    // mesmos argumentos, e devolve o que ele devolver (o erro dele sobe como subiria).
    function embrulharSair() {
      var original = win[PUSH.funcao.sair];
      if (typeof original !== 'function' || original[MARCA_SAIR]) return;
      var embrulho = function () {
        if (!token) return original.apply(this, arguments);
        var este = this;
        var argumentos = arguments;
        return esquecer(token).then(function () { return original.apply(este, argumentos); });
      };
      Object.defineProperty(embrulho, MARCA_SAIR, { value: true });
      win[PUSH.funcao.sair] = embrulho;
    }

    ctx.ouvirNativo(function (m) {
      if (!m || typeof m !== 'object' || m.t !== 'pushToken') return;
      // Sem token (o Firebase não configurado, ou o token ainda não saiu): nada a registrar nem a esquecer.
      if (typeof m.token !== 'string' || !m.token) return;
      // Uma vez por carga: o token é pedido uma vez, e só a primeira resposta com token vale.
      if (token !== null) return;
      token = m.token;
      versao = typeof m.versao === 'string' ? m.versao : '';
      registrar();
    });

    win.addEventListener('load', function () {
      if (carregou) return;
      carregou = true;
      try { embrulharSair(); } catch (e) { avisar('o sair da página não foi embrulhado (' + nomeDoErro(e) + ')'); }
      agendar(function () { ctx.enviar({ t: 'pushPedirToken' }, 'push'); }, ESPERA_PUSH_MS);
    });

    win.addEventListener('online', function () {
      if (aguardando === 'login') registrar();
      else if (aguardando === 'rede') {
        if (esfriando) tentarDepois = true;
        else registrar();
      }
    });

    if (doc && typeof doc.addEventListener === 'function') {
      doc.addEventListener('visibilitychange', function () {
        if (doc.visibilityState === 'visible' && aguardando === 'login') registrar();
      });
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 9. Abrir a OS pelo toque na notificação (spec 2026-10-18, seção 4.2, "Ao tocar")
  // ---------------------------------------------------------------------------------------------------------------

  // O toque numa emergência com OS abre o app com o número no Intent (o extra gc_os da MainActivity), e o nativo, com a
  // página do App carregada e 1,5 s de folga para ela montar a tela, roda window.__gcCascaAbrirOS('<os>'). A página não
  // abre OS pelo endereço nem expõe função para isso; o que ela tem é a busca por OS: o campo #busca-os, que a cada
  // digitação chama setBusca(valor), procura em todas as semanas que o App já baixou e, se o número não está na fila,
  // pergunta ao Fracttal ao vivo (a OS que é do técnico e está aberta entra como avulsa). É o que o técnico faria à mão,
  // com os nomes do ABRIR_OS (seção 1), que o contrato diário com o site confere.
  //
  // Devolve true quando mandou a busca; false quando não pôde: o número que não é de OS, a página sem o campo ou sem o
  // setBusca (uma versão do site que trocou os nomes, ou uma página que não é a do App). Isso não é erro da casca, e
  // nada vai ao nativo; o app só abre sem a busca. O campo é preenchido ANTES do setBusca, na ordem do phTrazer do próprio site (v284, o "Trazer
  // para a fila"): o setBusca recebe o valor por argumento e não escreve no campo, e sem isso a lista ficaria filtrada
  // com a caixa de busca vazia. Só um setBusca que lança vai ao nativo, como `abrirOS`, e sem devolver a exceção a quem
  // chamou (o evaluateJavascript do nativo não a trataria).
  //
  // O número é conferido AQUI de novo, mesmo o nativo (Notificacoes.osValida) já o tendo validado: o extra do Intent pode
  // vir de qualquer app (a MainActivity é exportada), e esta função monta uma busca na tela do técnico. De 1 a 12 dígitos
  // ASCII, escritos como [0-9] para não depender de como o motor trata o \d, e só texto.
  var OS_DO_TOQUE = /^[0-9]{1,12}$/;

  function instalarAbrirOS(ctx) {
    var win = ctx.win;
    function abrir(os) {
      try {
        if (typeof os !== 'string' || !OS_DO_TOQUE.test(os)) return false;
        var doc = win.document;
        var campo = doc && typeof doc.getElementById === 'function' ? doc.getElementById(ABRIR_OS.id.busca) : null;
        var buscar = win[ABRIR_OS.funcao.buscar];
        if (!campo || typeof buscar !== 'function') return false;
        campo.value = os;
        buscar.call(win, os);
        return true;
      } catch (e) {
        ctx.falhou('abrirOS', e);
        return false;
      }
    }
    // Não enumerável: o que da página varre a janela não a vê.
    Object.defineProperty(win, '__gcCascaAbrirOS', { value: abrir, enumerable: false, configurable: true, writable: false });
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
      // A exceção é a carga que aplicou o retrato completo da perda: o localStorage dela acabou de voltar do nativo, e o
      // que a página gravar nela vai a ele (se a restauração for interrompida, a próxima a refaz com isso junto).
      tentar(ctx, 'sentinela', function () { conferirSentinelaEFila(ctx, ls); });
      if (temMarcaDePerda(ls) && !ctx.restauracaoDePerda) ctx.espelhoDesligado = true;
      if (!ctx.espelhoDesligado) instalarEspelhoLS(ctx, ls);
    });
    // A 5 depois da 3 e da 4: ela lê o `espelhoDesligado` e o `cargaParada` que a 4 decidiu. A 6 e a 7 não espelham
    // nada e valem em toda carga; a 8 também não espelha, e fica fora só da carga parada.
    tentar(ctx, 'idb', function () { instalarIDB(ctx); });
    // Sem a seção 5 nesta carga (a janela sem IndexedDB, ou ele negado), a perda termina só com o localStorage: não há
    // fotos a esperar, e uma marca que nunca saísse deixaria toda carga sem espelho.
    if (ctx.concluirPerda && !ctx.idbCuidaDaPerda) tentar(ctx, 'marca', function () { ctx.concluirPerda(); });
    tentar(ctx, 'janela', function () { instalarJanela(ctx); });
    tentar(ctx, 'voltar', function () { instalarVoltar(ctx); });
    tentar(ctx, 'push', function () { instalarPush(ctx); });
    // A 9 só define uma função que o nativo chama, e vale em toda carga (a do toque na notificação é uma carga como outra).
    tentar(ctx, 'abrirOS', function () { instalarAbrirOS(ctx); });

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
      VOLTAR: VOLTAR,
      PUSH: PUSH,
      ABRIR_OS: ABRIR_OS,
    };
  } else if (typeof window !== 'undefined') {
    instalar(window, window.gcCasca, window.__GC_RETRATO__ || {});
  }
})();
