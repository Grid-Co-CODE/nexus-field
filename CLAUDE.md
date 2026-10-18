# CLAUDE.md — app-campo-android

Casca Android (Capacitor 8.5.3) do App de Campo da Grid Co. O App de Campo é um PWA que roda ao vivo em
`https://gridco-campo-mw.azurewebsites.net/api/app`; a casca só o abre em tela cheia, com ícone, GPS e câmera nativos,
e guarda no aparelho o que o navegador pode perder. O site não muda: aqui só há leitura dele, e só pelo teste de
contrato.

- Desenho: `docs/superpowers/specs/2026-10-16-app-campo-android-design.md`
- Plano: `docs/superpowers/plans/2026-10-16-app-campo-android.md`

## Montar e testar

```
npm install
npm test                                   # testes de Node (configuração, manifesto e regras de dados)
source ferramentas/ambiente.sh             # JAVA_HOME, ANDROID_HOME, PATH e android/local.properties (JDK 21, SDK API 36)
npx cap sync android && (cd android && ./gradlew testDebugUnitTest assembleDebug lint)
```

O `lint` sai sem erro (os avisos que restam são do template e das versões). O `ambiente.sh` escreve o `sdk.dir` com o
`:` escapado (`C\:/Users/...`): sem o escape o lint falha com PropertyEscape, mesmo o Gradle lendo o caminho certo.

O APK de depuração sai em `android/app/build/outputs/apk/debug/app-debug.apk`. Não há emulador nesta máquina, e
nenhum comando daqui toca em celular ligado por USB: instalar no aparelho é por link ou arquivo, pelo próprio dono. O
roteiro da prova no aparelho é o `docs/prova-no-aparelho.md` (caixas de seleção; quase tudo se faz sem o cabo). O APK de
depuração nunca vai a técnico: no campo, só o da Release.

O que o GitHub Actions roda (`.github/workflows/`): em todo push e PR, o `npm test`, o `testDebugUnitTest assembleDebug lint`
e o APK de depuração como artifact (`montar.yml`); em tag `v*`, a Release assinada; todo dia às 11h UTC, o contrato com o
site (`contrato.yml`). Detalhes na seção "A Release e o CI", mais abaixo.

## Regras que não mudam

- O site atual não muda (`campo.html`, `sw.js`, `function_app.py`, App Settings, CORS, rotas).
- `server.url` é só a origem (`https://gridco-campo-mw.azurewebsites.net`), sem caminho e sem barra no fim;
  `server.appStartPath` é `/api/app`.
- `server.allowNavigation` tem só hosts de login da Microsoft. O Fracttal nunca entra.
- `CapacitorHttp` fica desligado. Capacitor fixo em 8.5.3 (`@capacitor/core`, `cli`, `android`).
- O user agent termina com `GridcoCampoApp/<versionName>`.
- Pacote `br.com.gridco.campo`, nome "App de Campo Grid Co.", rótulo do ícone "Campo GridCo".
- A casca nunca envia nada da fila ao servidor. Quem envia é a página.
- O espelho nunca guarda `msal.*`, o `sessionStorage` nem token. `gc_campo_ident` fica cifrado em repouso (Android Keystore).
- `allowBackup=false` e `dataExtractionRules` excluindo tudo em `<cloud-backup>` e `<device-transfer>`.
- Sem serviço pago, sem segredo no repositório ou no APK, sem nome de colega em arquivo versionado. pt-BR, sem emoji.
- A chave de assinatura e as senhas nunca são impressas em terminal, log ou commit.

## O que o código não diz sozinho

- **`npm test` usa glob entre aspas** (`node --test "testes/*.test.mjs"`). No Node 24, `node --test testes/` trata a
  pasta como arquivo e quebra com `MODULE_NOT_FOUND`. O glob só pega `*.test.mjs`, então arquivo de apoio sem `.test.`
  no nome não roda sozinho dentro do `npm test`. É o caso do `testes/contrato.mjs` (o contrato com o site ao vivo, que usa a
  rede, e que o `contrato.yml` roda todo dia): o que o `npm test` roda dele é o `testes/contrato.test.mjs`, com HTML
  fictício e rede falsa.
- **Gradle com `Unable to establish loopback connection`:** o JDK 21 no Windows abre o `Selector` por um socket de
  domínio Unix numa pasta temporária, e onde o `TEMP` do perfil não é gravável (visto no terminal do assistente, em
  16/10/2026) isso falha, até no `./gradlew help`. Contorno, antes do `./gradlew`: `mkdir -p android/.gradle/uds` e
  `export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=<caminho de android/.gradle/uds no formato C:/...>"`
  (`android/.gradle/` é ignorado pelo git). No terminal normal do desenvolvedor não deve ser preciso.
- **Robolectric no Windows precisa de duas coisas** (medido em 17/10/2026, JDK 21, Robolectric 4.17, SDK 36), e as duas
  já estão no lugar: (1) `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` nos `jvmArgs` dos testes
  (`android/app/build.gradle`); sem ele o teste morre com "Failed to interact with raw FileDescriptor internals";
  (2) `CASCA_M2_LOCAL`, que o `ferramentas/ambiente.sh` exporta e o `build.gradle` repassa como `maven.repo.local`,
  para o jar do Android (~200 MB, baixado na primeira rodada) ficar numa pasta sem espaço. Com espaço no nome do
  usuário o Robolectric procura `...\Nome%20Sobrenome\.m2\...` e falha com "Unable to load Robolectric native runtime
  library". No Linux do CI nada disso é preciso, e a variável fica vazia.
- **Versão e assinatura vêm do ambiente** (`android/app/build.gradle`): `versionCode` de `GITHUB_RUN_NUMBER` (padrão 1),
  `versionName` de `VERSAO_CASCA` (padrão `0.0.0-dev`). O release só é assinado com `CASCA_KEYSTORE`,
  `CASCA_KEYSTORE_SENHA`, `CASCA_CHAVE_ALIAS` e `CASCA_CHAVE_SENHA` juntas. Com as quatro ausentes (ou vazias) sai sem
  assinar (`app-release-unsigned.apk`; é o caso do CI de PR). Com só 1 a 3 o build FALHA com uma `GradleException` que diz
  o NOME das que faltam (nunca o valor): o caso real é um secret vazio ou com o nome trocado, que sairia um release sem
  assinatura sem ninguém ver. Provado em 17/10/2026 com `CASCA_KEYSTORE` sozinha (falha), nenhuma (sem assinar) e as
  quatro de uma chave descartável (assinado, `apksigner verify` passa).
- **Dois FileProvider, de propósito.** `${applicationId}.fileprovider` é o do template do Capacitor, e o
  `BridgeWebChromeClient` o exige para a foto do `<input capture>` (a reserva da câmera interna): não remova.
  `br.com.gridco.campo.arquivos` (classe `ArquivosProvider`, caminhos em `caminhos_arquivos.xml`) é só do instalador da
  atualização e expõe apenas `cache/atualizacao/`. A subclasse é prevenção: o AGP 8.13.0 aceitou os dois com a mesma
  classe no mesmo arquivo (medido em 16/10/2026), mas para o sistema o `android:name` repetido é o mesmo componente.
- **As fotos da reserva da câmera têm faxina** (`FaxinaDaCamera`, chamada uma vez por processo pelo `CascaPlugin`, na
  thread do espelho): o `BridgeWebChromeClient` grava cada foto do `<input capture>` num `JPEG_*.jpg` em
  `getExternalFilesDir(Pictures)` (ou no cache, sem armazenamento externo) e nunca apaga. Eram MB por foto para sempre,
  legíveis por outro app no Android 7 a 10, e o disco cheio é o que faz o Chromium despejar o armazenamento da WebView.
  Sai o que tem mais de 48 h (a página já copiou a foto para o IndexedDB): na pasta das fotos, todo arquivo; no cache,
  só o `JPEG_*.jpg` da raiz (lá moram a WebView e o instalador). O arquivo "do futuro" (a hora voltou) fica.
- **`regras_dados.xml` exclui todos os domínios** nos dois blocos, porque a exclusão vale só para o domínio citado.
- **Ícone:** gerado do `icon-512.png` do site (`/api/icon-512.png`). O adaptativo tem fundo `#191528`, e o logo ocupa 60%
  da tela de 108dp (a zona segura do sistema é 66dp); os `ic_launcher*.png` antigos são o quadrado e o redondo do
  mesmo logo. O rótulo na tela inicial é o `title_activity_main` ("Campo GridCo"); o `app_name` é o nome completo.
- **`android/app/src/main/assets/public/`, `capacitor.config.json` e `capacitor.plugins.json` (em `assets/`) e
  `res/xml/config.xml` são gerados** pelo `cap sync` e ficam fora do git. O `local.properties` também.
- **O espelho nativo** (`android/app/src/main/java/br/com/gridco/campo/`): `EspelhoSqlite` guarda no `espelho.db` e em
  `files/espelho/{idb,tmp}` o que o `www/casca/inicio.js` manda; `Retrato` monta o `window.__GC_RETRATO__` que o mesmo
  script lê (o formato é o contrato dos dois lados). O que não aparece no código:
  - `Fnv.hash` é o `fnv1a32` do script, sobre as unidades UTF-16; o `FnvTest` traz um valor gerado pelo próprio JS com
    acento e emoji. Se um dos dois mudar, o outro muda junto, senão a fila é tomada por atrasada à toa.
  - A fila guarda as 3 versões mais novas, e gravar um valor IGUAL ao mais novo não gasta versão (a página regrava o mesmo
    conteúdo toda hora e empurraria uma versão de verdade para fora). `apagarLs` da fila apaga todas.
  - Valor é lido em pedaços de 512 KB (`lerEmPedacos`): o CursorWindow do Android recusa linha de mais de ~2 MB, e uma
    chave do localStorage chega a 5 MB. Escrita é por `execSQL`, não `insert()`, porque o `insert()` que falha registra no
    log o ContentValues inteiro (chave com e-mail, valor).
  - Blob só vira entrada no último pedaço (`i == n-1`) com todos na ordem; sequência quebrada é descartada. O `id` vira
    nome de arquivo e só aceita `[A-Za-z0-9_-]`. O arquivo do blob é `idb/<sha1 da chave>.bin`, nunca a chave. Parte com
    mais de 24 h é apagada na abertura. Blob sem o arquivo no disco não aparece em `idbChaves()` nem em `paraCadaIdb`.
  - A parte vira o arquivo do blob pelo rename direto (`trocarArquivo`): no Android é o rename(2), que troca o antigo de
    uma vez, e o processo que morre no meio deixa o antigo ou o novo. Só se ele falhar (o JVM do Windows, no teste, não
    troca arquivo que existe) o antigo é apagado e o rename repetido. Apagar antes, como era, abria a janela em que o
    espelho não tinha cópia nenhuma do blob. O arquivo não volta atrás com a transação da linha.
  - `gc_campo_ident` vai cifrada (`CifraKeystore`: `[tamanho do IV][IV][texto + etiqueta]`, AES/GCM, alias
    `gc_casca_ident`); se a cifra falhar, a identidade NÃO é gravada em claro, e a versão anterior sai do banco antes de
    a exceção subir (uma restauração devolveria a identidade velha com a fila nova: no aparelho dividido entre técnicos,
    troca a autoria). O que a chave de agora nunca vai ler (`Cifra.Ilegivel`: etiqueta que não confere, formato torto)
    sai do banco na primeira leitura; o Keystore que não respondeu agora (`IllegalStateException` comum) deixa a linha
    para o próximo retrato. A chave que o Keystore passa a recusar no init (`InvalidKeyException`, `ProviderException`:
    o defeito pós-atualização de sistema de alguns fabricantes) é apagada do cofre e refeita UMA vez por processo; o
    que a velha cifrou fica ilegível e sai, e a página regrava a identidade no próximo login. O AndroidKeyStore não roda
    no JVM do teste: o formato e a chave refeita são provados com chave de software (`CifraKeystoreTest`; a chave AES
    de 5 bytes faz o papel da recusada) e o resto dos testes usa a `CifraFalsa` (`falharAoCifrar`, `falharAoDecifrar`,
    `keystoreFora`). Gerar e refazer a chave no Keystore de verdade só se vê no aparelho.
  - `Retrato.completo(armazem, motivo)` só aceita `'perda'` ou `'fila'` e lança nos outros: quem chama valida o pedido.
- **O plugin e o canal** (`CascaPlugin`, `Mensageiro`; `MainActivity` registra o plugin antes do `super.onCreate`):
  - No Capacitor 8.5.3 o `load()` dos plugins roda no construtor do `Bridge`, antes do `loadWebView` (o primeiro
    `loadUrl`): o script registrado ali vale para a primeira carga. Mas o `Bridge.Builder.create()` troca a lista de
    `WebViewListener` logo depois do construtor, e o que um plugin puser nela dentro do `load()` some sem aviso. Por isso
    o ouvinte (renderer que morre, histórico depois do login) entra por um `post` na thread de UI.
  - Tudo que mexe no espelho roda numa thread só (`GcCasca-espelho`), na ordem em que a página mandou; o que mexe na
    WebView volta para a de UI. O `EspelhoSqlite` e a thread são um por processo (estáticos no plugin), e a thread não é
    desligada quando a tela sai: com uma por plugin, a da tela velha ainda esvaziava a fila dela enquanto a nova começava,
    e mensagens da página velha caíam depois das da nova. O que a tela que saiu deixou na fila é gravado; o `encerrado`
    impede que algo novo dela entre ou volte à WebView.
  - **O renderer que morre** recria a tela (sem isso o Android derruba o app), no máximo 3 vezes em 60 s por processo
    (`TetoDeRecriacao`, no `elapsedRealtime`; a recusada não ocupa a janela). Passado o teto, a tela recriada carrega a
    página de erro local (`bridge.getErrorUrl()`, o `www/erro.html`) logo depois do `load()`, e o "Tentar de novo" dela
    abre o App pela mão do técnico: o renderer que morria sempre na carga fazia a tela piscar para sempre, sem mensagem.
  - Todo `precisoRetrato` recarrega a página (ela parou e espera): com motivo `perda` ou `fila`, um retrato completo de
    id novo; com motivo torto, o sem conferência (o `tratar` devolve false, mas recarrega). O disjuntor deixa 2
    completos em 60 s POR PROCESSO (`Mensageiro.Disjuntor`, estático no plugin, como o espelho): o renderer que morre na
    recarga com o completo recria a tela, com Mensageiro e sessionStorage novos, e a contagem tem de atravessar a
    recriação. Daí em diante recarrega com o retrato sem conferência (`semConferencia`: o leve com `filaHashes` vazio e
    `semParar: true`). No `restaurado`, volta ao leve fresco; se o espelho falhar ao ler, vai o sem conferência, nunca
    fica no completo: ele seria reaplicado na carga sem o id no sessionStorage ("limpar só o login") e gravaria o `ls` e
    a fila velhos.
  - **O disjuntor do dia** (`Mensageiro.DisjuntorDaPerda`): no máximo 3 restaurações por 24 h NO APARELHO, porque a
    página refaz na carga seguinte a restauração que não terminou, e o disco cheio que recusa sempre faria cada abertura
    pedir tudo de novo e segurar a página. Somam as de perda (o `precisoRetrato` 'perda'; estourado, vai o sem
    conferência, e a página segue sem espelho com a marca de pé até a janela andar) e as só do IndexedDB (o `idbPedir`
    com `motivo: 'idb'`, ou sem motivo; estourado, o nativo responde `{t:'idbRecusado'}` pela ponte e a página abre na
    hora). O `idbPedir` da perda leva `motivo: 'perda'` e não conta de novo. O de 60 s é conferido antes: a recarga em
    laço não gasta o do dia. A contagem mora no SharedPreferences `casca_espelho` (fora do backup, como tudo), com o
    relógio de parede (atravessa a reinicialização); a marca "do futuro" (a hora do aparelho voltou) não conta, senão
    travaria pelo tempo que a hora voltou. Se o SharedPreferences não gravar, vale a contagem do objeto, um por processo.
  - Com `semParar` o `inicio.js` nunca chama `win.stop()`: a perda (a marca, ou o localStorage vazio com espelho) segue
    com o espelho desligado, como na trava da sessão, e avisa `{t:'erro', onde:'perda'}`; a fila atrasada segue. Sem
    ele, com a marca da perda e o sessionStorage que não guarda a trava, o leve sem `filaHashes` não evitava a parada da
    perda (que vem antes da conferência da fila) e toda recarga pedia de novo.
  - Nada do técnico vai ao log (os nomes das chaves têm e-mail): o `Mensageiro` avisa só com rótulo e contagem. Do
    `{t:'erro'}` da página vai só o `onde`, se estiver na lista fechada `Mensageiro.ONDES_DA_PAGINA` (senão `outro`), e
    a contagem; o texto nunca (o `QuotaExceededError` do Chromium cita a chave entre aspas, o `JSON.parse` cita o
    texto, e uma limpeza por aspas deixava passar um primeiro nome). Rótulo novo no script entra na lista no mesmo
    trabalho: o `testes/canal.test.mjs` compara os dois. O `MensageiroTest` confere o log pelo `ShadowLog`.
  - O `android.util.Base64` pula em silêncio o que não é do alfabeto: o pedaço é conferido antes (`base64Valido`).
  - **WebView antiga** (sem `DOCUMENT_START_SCRIPT` ou sem `WEB_MESSAGE_LISTENER`): o App não abre, porque sem o script
    de início a página rodaria sem espelho e a janela do Fracttal apagaria a tela. O `MainActivity` confere antes do
    `super.onCreate` e troca o config por um sem `server.url` (`configSemOApp`: o Bridge carrega `https://localhost`, o
    `index.html` do www, e o App nem é pedido ao servidor); o plugin desliga o JavaScript (o `initWebView` do Bridge o
    liga ANTES dos plugins, e o `loadWebView` não mexe mais nele) e mostra o aviso, que não fecha. Sem a primeira
    abertura (permissões) nesse caso.
  - Do `CascaPlugin` e do `MainActivity` só as regras puras têm teste de JVM (`navegacaoParaOLog`, o endereço no log de
    depuração; `avisoDepoisDoPedido`; `configSemOApp`; a thread do espelho única; e as classes separadas para isso:
    `Voltar`, `TetoDeRecriacao`, `FaxinaDaCamera`). O resto (WebView de verdade, o gesto do voltar, diálogos, a ligação
    de cada regra no plugin) é conferido pela montagem e pela prova no aparelho.
  - O AGP 8 não gera o `BuildConfig` sem `buildFeatures.buildConfig` (o user agent e o `DEBUG` vêm dele), e o
    `androidx.webkit` entra no app de novo porque o `capacitor-android` o tem só como `implementation`.
- **O script de início e a restauração** (`www/casca/inicio.js`; os testes são `testes/restaurar-ls.test.mjs` e
  `testes/espelho-idb.test.mjs`). O que não aparece no código sem ler os comentários longos:
  - **A reconciliação nunca manda apagar no nativo** (nem `idbDel` nem `lsDel`): só reenvia o que falta lá. O nativo só
    encolhe pelas exclusões da própria página, espelhadas na hora. Caso que criou a regra: com o disco cheio, a
    restauração terminava sem gravar, e a reconciliação seguinte apagava no nativo a foto que só ele tinha. Custo aceito:
    uma exclusão da página que se perdeu no canal pode voltar numa restauração (a faxina de órfãos da página a limpa).
  - **A perda só termina inteira.** A marca `gc_casca_perda_pendente` sai, e a sentinela entra, só quando o localStorage
    E o IndexedDB das fotos voltaram (o `idbFim` com o `n` batendo e toda chave gravada). O banco `gc_casca` quer dizer só
    "a última restauração do IndexedDB terminou": toda restauração começa apagando-o e o recria no fim. A interrompida (o
    loginRedirect logo depois da perda, o renderer que morre num vídeo) recomeça na carga seguinte: com a marca, a carga
    para e pede o retrato de novo; sem o `gc_casca`, restaura de novo. A trava da sessão (`gc_casca_parou`) sai quando um
    retrato completo é aplicado com a fila dele, senão a sessão inteira não pararia mais carga nenhuma. A carga que aplica
    o retrato da perda espelha (o localStorage acabou de voltar do nativo); toda outra carga com a marca, não. Sem
    IndexedDB na janela, a perda termina só com o localStorage. A chave que o `idbChaves` anunciou e o nativo não mandou
    não conta (o `n` é o que ele mandou): contá-la travaria a perda para sempre por um blob ilegível no espelho.
  - **A perda só do IndexedDB** (sem o `gc_casca`, ou a store das fotos vazia com o nativo cheio) grava só as chaves
    AUSENTES na WebView, nunca sobrescreve: o espelho pode ter perdido a última gravação de um rascunho. A da perda grava
    tudo (a mesma geração do localStorage). No fim de toda restauração, o que só a WebView tem vai ao nativo.
  - **O prazo da seção do IndexedDB é de inatividade**: 20 s sem item nem pedaço do nativo, com teto absoluto de 180 s. O
    teto fixo de 15 s soltava a página no meio de uma restauração saudável de vídeos, e o envio da fila dava por enviadas
    as fotos que ainda não tinham chegado.
  - **A fila feita numa carga sem espelho** (a trava, o `semParar`) é juntada à do retrato da perda, em vez de ir por
    baixo dela: quando a da WebView difere da do retrato, não é uma versão que o nativo tem (`filaHashes` ou a última da
    sessão) e as duas são listas em JSON, ficam os itens do nativo mais os da WebView que ele não tem (pelo JSON do
    item), e a junta é espelhada depois do `restaurado`. Prefere duplicar a perder. A restauração só da fila não junta.
  - **O espelho nativo recriado** (a sentinela na WebView e `temEspelho` falso: o SQLite corrompido que o Android recria
    vazio) recebe, além da `sentinela` e da fila, todo o trabalho e o cache do localStorage (o trabalho primeiro), uma
    vez por sessão (`gc_casca_ls_reenviado`): a `gc_campo_ident` é gravada uma vez, no login, e só chegaria a ele no
    próximo login. Vale também na instalação nova com chaves na WebView.
- **O voltar do Android, do lado do script** (seção 7 do `inicio.js`, `testes/voltar.test.mjs`): o Capacitor 8.5.3 não
  trata o voltar e o `campo.html` não usa histórico, então o gesto saía do app no meio da OS. `window.__gcCascaVoltar()`
  (não enumerável) decide NA HORA a camada de cima e devolve true, e a fecha num `setTimeout(0)` pelo botão ou função que
  o técnico usaria (um `confirm()` da página não segura a resposta ao nativo). Sem nada aberto devolve false, e o nativo
  manda o app para o fundo; erro vira `{t:'erro', onde:'voltar'}` e false. A ordem é a do z-index do `campo.html` v282:
  a camada da casca; a foto `.rd-lb`; a câmera `#cam`; as esperas `#cham-wait`, `#geo-exige` e `#sonda.open` (consumidas
  sem fazer nada); a APR (o rascunho é salvo ANTES do `aprFechar`, que o botão de fechar da página não salva); os Extintores; as
  folhas de z 520 (o `#sheet` reabre a OS que ele tinha substituído); a OS, pelo botão do `#m-body` (o Cancelar da Pausa
  volta para a OS); o menu; as abas (o voltar da Ronda e da Zeladoria; as outras vão para Tarefas). Os nomes ficam todos
  no `VOLTAR` exportado, que o contrato com o site exige; um nome trocado no site só faz o voltar minimizar, nunca fecha a
  camada de baixo no lugar da de cima. O `sheetTask` é um `let` de topo do site, que não vira propriedade da janela: é
  lido pelo nome (o teste prova num jsdom que roda o script e a página como scripts de verdade). Se o site adotar o
  histórico (pushState ao abrir camada), a seção sai e o nativo passa ao `goBack()`.
- **O voltar do Android, do lado nativo** (`Voltar`, ligado pelo `CascaPlugin` no começo do `load()`, também com a
  WebView antiga): um `OnBackPressedCallback` do androidx.activity, que cobre o voltar preditivo do targetSdk 36
  (sobrescrever o `onBackPressed` não cobriria). Com a página do App aberta (a URL na origem do `server.url` e o caminho
  `/api/app` inteiro: `/api/appx` não é o App) pergunta à página por `evaluateJavascript` (a `PERGUNTA`, que devolve
  true só se `__gcCascaVoltar` existe e respondeu true; erro vira false) e só `"true"` consome; o resto, e toda página
  fora do App (o `erro.html`, o login da Microsoft), faz `moveTaskToBack(true)`. A tela nunca é fechada: volta-se ao
  app onde estava, sem recarregar. Uma pergunta por vez (o segundo toque enquanto a página pensa não fecha duas
  camadas), com 1,5 s de prazo: o renderer travado não segura o voltar, e a resposta que chega depois do prazo não decide
  nada.
- **A atualização da casca** (`Atualizador`, `VersaoRemota`; o `CascaPlugin` a chama no `load()` e no `handleOnResume()`):
  - Lê o `versao.json` de `releases/latest/download/` (não conta no limite da API do GitHub), com as chaves `versionCode`
    (inteiro), `versionName`, `apk`, `sha256` (64 dígitos hex) e `novidades` (lista de textos). O `ferramentas/versao-json.mjs`
    da Release tem de gerar exatamente isso. Falta de campo, tipo errado, SHA que não seja hex de 64 ou `apk` fora de
    `https://github.com/Grid-Co-CODE/app-campo-android/releases/download/<pedaços simples>` (sem `..`, `?`, `%`) é recusado:
    o SHA que o próprio JSON traz não protegeria de um link para outro lugar.
  - A hora da checagem vai ao SharedPreferences ANTES da rede: falhar também conta as 6 h, senão o campo sem sinal esperaria
    15 s a cada volta ao app. A checagem é silêncio: rede e JSON torto vão só ao log, sem endereço nem nome de arquivo.
    Depois do "Atualizar agora" a pessoa pediu: um toast diz que está baixando (`casca_atualizacao_baixando`; sem retorno
    nenhum por vários MB de dados móveis, o técnico ia a outro app), e o download que não termina, o SHA errado e o erro
    antes do instalador avisam no toast de sempre (`casca_atualizacao_sha_toast`). Sem nova tentativa em laço.
  - Um fluxo por vez em todo o processo (`DONO_DO_PROCESSO`; a tela recriada do renderer que morreu é outra instância, e dois
    downloads para `cache/atualizacao/app-campo.apk` o corromperiam). O `load()` e o resume que vem logo depois caem aí.
  - **A pendência ("falta só instalar": versionCode, SHA e a hora do download do APK que ficou no cache)** vai ao
    SharedPreferences, porque o Android pode matar o processo e o resume seguinte seria de um processo novo. Nasce em dois
    casos: o toque em "Abrir ajustes" da permissão de instalar (o diálogo fechado sem o toque não deixa nada), e o
    download que termina com a tela fora da frente (o técnico foi a outro app: o Android 10+ bloqueia em silêncio a
    activity aberta do fundo, e a próxima checagem, 6 h depois, apagaria e baixaria tudo de novo). A tela confere o
    `Lifecycle` em RESUMED antes de abrir o instalador. A pendência vale 6 h desde o download (a retomada que cai no fundo
    de novo regrava com a hora da primeira, sem esticar); sem hora (de uma versão anterior) ou "do futuro" (a hora do
    aparelho voltou) não vale, e a checagem normal decide. É UMA tentativa e a retomada confere o arquivo de novo: voltar
    sem liberar, ou a câmera trazendo outro resume, não repete o diálogo nem reabre o instalador.
  - **O build de depuração não oferece a Release** (`verificar()` sai com o `BuildConfig.DEBUG`, antes até de gravar a
    hora): a assinatura dele é outra, o instalador recusaria a versão por cima a cada 6 h, e o reflexo de desinstalar
    apagaria o espelho.
  - O `cache/atualizacao/` é limpo a cada checagem de 6 h (sobra da versão instalada, diálogo fechado) e, antes do
    instalador, fica só o APK novo. O `confere` lê em pedaços de 64 KB.
  - Os textos estão no `strings.xml` (`casca_atualizacao_*`, `casca_instalar_*`), PENDENTES de aprovação do
    Levi na prévia. O `casca_atualizacao_texto_simples` (só "Versão X.", Release sem novidades) e o
    `casca_atualizacao_baixando` (17/10/2026) não estavam no brief original.
  - Do `Atualizador` testa-se em JVM o fluxo inteiro com rede, telas e relógio falsos (`AtualizadorTest`; a tela falsa
    também diz "fora da frente"); os diálogos de verdade, o toast e o instalador do sistema só se veem no aparelho. **Os
    dois testes que passam pelo `FileProvider` são pulados no Windows** (ele compara caminhos com `/`; medido em
    17/10/2026) e rodam no Linux do CI; a configuração do provider (autoridade, uma pasta só, não exportado) é conferida
    em Node, no `testes/configuracao.test.mjs`.
- **A prova da restauração só existe no build de depuração** (spec 10.3: apagar o armazenamento da WebView e ver o
  espelho devolver a fila e as fotos; "Limpar armazenamento" nos ajustes apaga o espelho junto). A `Depuracao` tem duas
  versões com os mesmos métodos: a de `src/debug` e a de `src/release`, vazia (nunca pede, não faz nada), para o
  `MainActivity` ser um só. Com o extra booleano `gc_apagar_webview` e sem estado salvo (a tela recriada traz o mesmo
  intent, e apagar de novo ali faria a restauração nunca terminar), o `MainActivity` abre sem o App (`configSemOApp`),
  e a `Depuracao` chama o `WebStorage.deleteAllData()` (localStorage, IndexedDB, Web SQL e File System de todas as
  origens; os cookies ficam), mostra um toast, espera 3 s e recria a tela, que abre o App como sempre e cai na perda.
  O espelho nativo não é tocado. O extra chega pelo **atalho do ícone** (tocar e segurar: "Prova: apagar WebView",
  `src/debug/res/xml/atalhos_depuracao.xml`, posto pelo `src/debug/AndroidManifest.xml`), para a prova sem o cabo e
  sem o shell do adb, que a regra do celular pessoal não permite; ou pelo `am start ... --ez gc_apagar_webview true`
  num aparelho de prova. Com a tela aberta o atalho só a traz para a frente (`singleTask`): tire o App dos recentes
  antes. Os textos dela ficam em `src/debug/res/values/depuracao.xml`, sem o prefixo `casca_` e fora da prévia (quem
  os vê é quem faz a prova). Quem prova: o `testes/depuracao.test.mjs` (nada do caminho, nem o nome do extra nem o
  atalho, no `src/main` ou no `src/release`; a do release vazia; a do debug sem tocar no espelho; a ordem no
  `MainActivity`; o `targetPackage` do atalho igual ao `applicationId`, que muda junto com ele) e o `DepuracaoTest`
  (em `src/testDebug`, porque o release nem tem a classe; lê o XML do atalho pelo `Intent.parseIntent`, como o
  launcher, e confere que o `android:value="true"` chega como booleano). Medido em 17/10/2026: o
  `app-release-unsigned.apk` não tem o atalho no manifesto nem `gc_apagar_webview`/`deleteAllData` no dex, e o
  `aapt2 dump xmltree` do de depuração mostra o `value` compilado como booleano.
- **A prévia das telas novas** (`docs/previas/telas.html`, fora do APK): uma página só, autocontida, com os diálogos do
  `strings.xml`, os toasts, o `www/erro.html` num iframe e a camada do Fracttal, cada um numa moldura de 360 px, para o Levi
  aprovar texto e visual antes de ir ao campo. **Os textos estão PENDENTES de aprovação do Levi** (a página é publicada
  como Artifact privado do claude.ai e ele aprova por lá); quando ele aprovar, esta frase e os cabeçalhos do
  `strings.xml` passam a dizer "aprovados", com a data. O texto vive no arquivo-fonte, não na prévia: pedido de mudança
  se faz no `strings.xml`, no `erro.html` ou no `inicio.js` e a prévia acompanha.
  - O `testes/previas.test.mjs` falha se um diálogo da prévia não for um grupo do `strings.xml` (título, texto e botões
    no conjunto e na ordem do aplicativo: negativo à esquerda, positivo à direita, como a `MainActivity`, o `CascaPlugin`
    e o `Atualizador` os montam; o teste lê os `AlertDialog.Builder` do Java), se os toasts da prévia não forem, no
    conjunto, os `Toast.makeText(..., R.string.x, ...)` do Java (o teste os lê do código), se uma string da casca ficar
    sem quadro, se as novidades não forem uma linha `- item` cada, se o `srcdoc` do erro não for o `erro.html` inteiro (copie o arquivo
    escapando `&` como `&amp;` e `"` como `&quot;`, nessa ordem) ou se a camada do Fracttal não for igual à que o
    `inicio.js` monta (o estilo inline é o do aparelho; a moldura tem `transform` para a `position:fixed` da camada
    cobrir só o celular). A única diferença aceita é o `aria-modal="false"` da prévia: numa página estática um
    `aria-modal` ativo esconderia o resto da página da acessibilidade.
  - O HTML de dentro da camada do Fracttal é o que o `campo.html` escreve na aba quando a sessão da Microsoft expirou
    (`conectarFracttal`/`naAba`, App v282, lido em 17/10/2026), não um exemplo inventado; a legenda diz de onde veio. O
    `campo.html` mora fora deste repositório: se o App mudar o texto, atualize a cópia e a versão da legenda.
  - Contrato da página do Artifact: o arquivo não tem `doctype`, `html`, `head` nem `body` (o publicador embrulha),
    abre com `<title>`, `<meta name="claude:viewport-fit-sides" content="cover">` e `<style>`, leva o gutter lateral
    num envoltório (`.pagina`, com `env(safe-area-inset-*)`), sem script nem recurso externo. Toda cor da página é token
    no `:root` (claro), redefinido no escuro por `@media (prefers-color-scheme: dark)` com
    `:root:not([data-theme="light"])` e por `:root[data-theme="dark"]`, com os mesmos valores; token sem uso sai. As
    cores por extenso que restam estão dentro das cópias fiéis do aplicativo (o estilo da camada e os iframes). O teste
    confere tudo isso, e que nenhuma regra da folha alcança o iframe da camada (só o `#erro-html` é dimensionado por ela).
  - O desenho dos diálogos (AppCompat DayNight: fundo branco ou `#424242`, canto de 2 dp, botão verde-azulado) é
    aproximação: o que se aprova é texto, botão e quando cada aviso aparece.
- **A Release e o CI** (`.github/workflows/montar.yml` e `contrato.yml`; escritos em 17/10/2026, ainda sem repositório no
  GitHub: a primeira execução real é a da Task 10, e o `testes/workflows.test.mjs` guarda as regras abaixo no texto dos
  arquivos, porque o Actions não roda daqui):
  - **Montagem (`montar`):** Node `22.x` (o jsdom 30 pede `^22.22.2`; o `package.json` declara `engines`), `npm ci` e
    `npm test`, depois Java 21 (Temurin), `npx cap sync android` e `./gradlew testDebugUnitTest assembleDebug lint`, e o
    APK de depuração sobe como artifact. As actions estão fixadas por versão maior (`actions/checkout@v7`,
    `setup-node@v7`, `setup-java@v6`, `upload-artifact@v7`, `gradle/actions/setup-gradle@v6`; conferidas em 17/10/2026 com
    `gh api repos/<action>/releases/latest`). O `setup-gradle` usa `cache-provider: basic` (o `enhanced`, padrão, é um
    serviço comercial; regra de "sem serviço pago").
  - **Os dois testes do FileProvider têm de rodar no Linux.** `instaladorRecebeOUriDoProviderParaOApkDaPasta` e
    `oProviderDoInstaladorNaoExpoeNadaAlemDeCacheAtualizacao` são pulados no Windows e o Gradle conta pulado como sucesso:
    o `ferramentas/testes-pulados.mjs` lê o XML de resultado e derruba o CI se um deles aparecer pulado, falho ou sumido
    (renomeado). Esta é a primeira vez que eles rodam fora do Windows: se falharem no Linux, o problema é do teste ou do
    `FileProvider`, não do conferidor.
  - **A Release (`release`, só em tag `v*`, depois do `montar`, no ambiente `release`).** `contents: write` é só deste job. A
    tag tem de ser `vX.Y.Z` (semver; `vX.Y.Z-rc.1` sai como pré-lançamento, que a `releases/latest/` do app não enxerga) e
    tem de existir `novidades/<versão>.md` (as linhas `- ` viram as novidades do aviso e o arquivo inteiro é o texto da
    Release). `VERSAO_CASCA` é a tag sem o `v`; o `versionCode` é o `GITHUB_RUN_NUMBER` **do workflow `montar`: não renomeie
    nem apague o arquivo**, senão a contagem recomeça em 1 e o Android recusa a atualização.
  - **A chave:** o secret `CASCA_KEYSTORE_B64` é decodificado para `$RUNNER_TEMP/casca.jks` (permissão só do dono, `printf`
    direto no `base64 -d`, sem `echo`) e apagado no último passo (`if: always()`). As quatro `CASCA_*` entram só no `env:`
    do passo do `assembleRelease` (nunca no `GITHUB_ENV`, que as passaria a todos os passos). Depois do build, o
    `apksigner verify --print-certs` (o build-tools do runner) tem de passar, senão nada é publicado: o SHA-256 do
    certificado que ele imprime é público e dá para comparar com o da chave guardada. A Release leva `app-campo.apk`,
    `app-campo.apk.sha256` e `versao.json`.
  - **O `ferramentas/versao-json.mjs`** gera o `versao.json` com as MESMAS regras do `VersaoRemota.java` (o
    `testes/versao-json.test.mjs` lê o `.java` e compara o prefixo, o padrão do link e o SHA; o SHA tem 64 hex minúsculos,
    o `versionName` é o semver, o `apk` sai de `v<versão>/app-campo.apk`). O SHA-256 vem do próprio APK; o workflow ainda
    confere com o `sha256sum`. Sem nenhuma linha `- ` nas novidades o arquivo sai com a lista vazia (o app mostra só
    "Versão X.").
  - **O contrato com o site (`testes/contrato.mjs`):** um GET do `/api/app`, só leitura. Importa as chaves e os prefixos
    da `CLASSES` do `www/casca/inicio.js` (a lista existe num lugar só) e exige no HTML cada chave exata entre aspas e cada
    prefixo; mais o banco `gc_campo_fotos` com a store `fotos` por perto, a `conectarFracttal` com o
    `window.open('','_blank')`, o `loginRedirect`, o `'/api/app'` e o `msal-browser` na 2.38.1 (no mesmo endereço). Falha
    também se o site passar a usar o que o espelho não enxerga (`localStorage.clear(`, atribuição por índice,
    `delete localStorage`): o espelho só embrulha `setItem` e `removeItem`. A mensagem diz o NOME do que sumiu, nunca um
    trecho do HTML. Sai com 0 (ok), 1 (o site mudou) ou 2 (não deu para ler o site). Limite: a chave citada num comentário
    entre aspas simples ainda conta como presente (entre crases não conta). Medido em 17/10/2026: passa no site vivo, e
    cada mudança de uma peça no HTML real (versão do MSAL, store, `window.open`, prefixo...) é pega.
  - **A issue diária (`contrato`)**: se o contrato falhar, abre UMA issue com o rótulo `contrato` (não abre outra
    enquanto houver uma aberta) e deixa o job vermelho. O GitHub desliga os workflows agendados de um repositório sem
    atividade por 60 dias: se a issue nunca vem, confira que o workflow continua ligado.
- A splash e o resto do nativo vêm nas tarefas seguintes do plano.
