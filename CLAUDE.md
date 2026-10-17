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
nenhum comando daqui toca em celular ligado por USB: instalar no aparelho é por link ou arquivo, pelo próprio dono.

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
  - `gc_campo_ident` vai cifrada (`CifraKeystore`: `[tamanho do IV][IV][texto + etiqueta]`, AES/GCM, alias
    `gc_casca_ident`); se a cifra falhar, a identidade NÃO é gravada em claro. O AndroidKeyStore não roda no JVM do teste:
    o formato é provado com chave de software (`CifraKeystoreTest`) e o resto dos testes usa a `CifraFalsa`. Gerar a chave
    no Keystore de verdade só se vê no aparelho.
  - `Retrato.completo(armazem, motivo)` só aceita `'perda'` ou `'fila'` e lança nos outros: quem chama valida o pedido.
- **O plugin e o canal** (`CascaPlugin`, `Mensageiro`; `MainActivity` registra o plugin antes do `super.onCreate`):
  - No Capacitor 8.5.3 o `load()` dos plugins roda no construtor do `Bridge`, antes do `loadWebView` (o primeiro
    `loadUrl`): o script registrado ali vale para a primeira carga. Mas o `Bridge.Builder.create()` troca a lista de
    `WebViewListener` logo depois do construtor, e o que um plugin puser nela dentro do `load()` some sem aviso. Por isso
    o ouvinte (renderer que morre, histórico depois do login) entra por um `post` na thread de UI.
  - Tudo que mexe no espelho roda numa thread só (`GcCasca-espelho`), na ordem em que a página mandou; o que mexe na
    WebView volta para a de UI. O `EspelhoSqlite` é um por processo: a tela recriada (renderer que morreu) reaproveita.
  - Todo `precisoRetrato` recarrega a página (ela parou e espera): com motivo `perda` ou `fila`, um retrato completo de
    id novo; com motivo torto, o sem conferência (o `tratar` devolve false, mas recarrega). O disjuntor deixa 2
    completos em 60 s POR PROCESSO (`Mensageiro.Disjuntor`, estático no plugin, como o espelho): o renderer que morre na
    recarga com o completo recria a tela, com Mensageiro e sessionStorage novos, e a contagem tem de atravessar a
    recriação. Daí em diante recarrega com o retrato sem conferência (`semConferencia`: o leve com `filaHashes` vazio e
    `semParar: true`). No `restaurado`, volta ao leve fresco; se o espelho falhar ao ler, vai o sem conferência, nunca
    fica no completo: ele seria reaplicado na carga sem o id no sessionStorage ("limpar só o login") e gravaria o `ls` e
    a fila velhos.
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
    depuração; `avisoDepoisDoPedido`; `configSemOApp`). O resto (WebView de verdade, diálogos) é conferido pela
    montagem e pela prova no aparelho.
  - O AGP 8 não gera o `BuildConfig` sem `buildFeatures.buildConfig` (o user agent e o `DEBUG` vêm dele), e o
    `androidx.webkit` entra no app de novo porque o `capacitor-android` o tem só como `implementation`.
- **A atualização da casca** (`Atualizador`, `VersaoRemota`; o `CascaPlugin` a chama no `load()` e no `handleOnResume()`):
  - Lê o `versao.json` de `releases/latest/download/` (não conta no limite da API do GitHub), com as chaves `versionCode`
    (inteiro), `versionName`, `apk`, `sha256` (64 dígitos hex) e `novidades` (lista de textos). O `ferramentas/versao-json.mjs`
    da Release tem de gerar exatamente isso. Falta de campo, tipo errado, SHA que não seja hex de 64 ou `apk` fora de
    `https://github.com/Grid-Co-CODE/app-campo-android/releases/download/<pedaços simples>` (sem `..`, `?`, `%`) é recusado:
    o SHA que o próprio JSON traz não protegeria de um link para outro lugar.
  - A hora da checagem vai ao SharedPreferences ANTES da rede: falhar também conta as 6 h, senão o campo sem sinal esperaria
    15 s a cada volta ao app. Rede, JSON torto e download cortado são silêncio (só o log, sem endereço nem nome de arquivo);
    só o SHA errado avisa, num toast. Sem nova tentativa em laço.
  - Um fluxo por vez em todo o processo (`DONO_DO_PROCESSO`; a tela recriada do renderer que morreu é outra instância, e dois
    downloads para `cache/atualizacao/app-campo.apk` o corromperiam). O `load()` e o resume que vem logo depois caem aí.
  - **A permissão de instalar:** o Android pode matar o processo quando o técnico a libera nos ajustes, e o resume seguinte
    seria de um processo novo. Por isso o "falta só instalar" (versionCode e SHA do APK que ficou no cache) vai ao
    SharedPreferences quando ele toca em "Abrir ajustes" (e só aí: o diálogo fechado não deixa pendência), e a retomada
    confere o arquivo de novo. É UMA tentativa: voltar sem liberar, ou a câmera trazendo outro resume, não repete o diálogo
    nem reabre o instalador.
  - O `cache/atualizacao/` é limpo a cada checagem de 6 h (sobra da versão instalada, diálogo fechado) e, antes do
    instalador, fica só o APK novo. O `confere` lê em pedaços de 64 KB.
  - Os textos estão no `strings.xml` (`casca_atualizacao_*`, `casca_instalar_*`), PENDENTES de aprovação do
    Levi na prévia. O `casca_atualizacao_texto_simples` (só "Versão X.", Release sem novidades) não estava no brief original.
  - Não há aviso durante o download (o brief não prevê texto para isso).
  - Do `Atualizador` testa-se em JVM o fluxo inteiro com rede, telas e relógio falsos (`AtualizadorTest`); os diálogos de
    verdade e o instalador do sistema só se veem no aparelho. **Os dois testes que passam pelo `FileProvider` são pulados no
    Windows** (ele compara caminhos com `/`; medido em 17/10/2026) e rodam no Linux do CI; a configuração do provider
    (autoridade, uma pasta só, não exportado) é conferida em Node, no `testes/configuracao.test.mjs`.
- **A prévia das telas novas** (`docs/previas/telas.html`, fora do APK): uma página só, autocontida, com os diálogos do
  `strings.xml`, o toast, o `www/erro.html` num iframe e a camada do Fracttal, cada um numa moldura de 360 px, para o Levi
  aprovar texto e visual antes de ir ao campo. **Os textos estão PENDENTES de aprovação do Levi** (a página é publicada
  como Artifact privado do claude.ai e ele aprova por lá); quando ele aprovar, esta frase e os cabeçalhos do
  `strings.xml` passam a dizer "aprovados", com a data. O texto vive no arquivo-fonte, não na prévia: pedido de mudança
  se faz no `strings.xml`, no `erro.html` ou no `inicio.js` e a prévia acompanha.
  - O `testes/previas.test.mjs` falha se um diálogo da prévia não for um grupo do `strings.xml` (título, texto e botões
    no conjunto e na ordem do aplicativo: negativo à esquerda, positivo à direita, como a `MainActivity`, o `CascaPlugin`
    e o `Atualizador` os montam; o teste lê os `AlertDialog.Builder` do Java), se uma string da casca ficar sem quadro, se
    as novidades não forem uma linha `- item` cada, se o `srcdoc` do erro não for o `erro.html` inteiro (copie o arquivo
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
