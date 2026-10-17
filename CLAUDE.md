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
  no nome (e o teste de contrato com o site, que usa a rede) não roda sozinho dentro do `npm test`.
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
  `CASCA_KEYSTORE_SENHA`, `CASCA_CHAVE_ALIAS` e `CASCA_CHAVE_SENHA` juntas; sem elas sai sem assinar.
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
  - Os textos estão no `strings.xml` (`casca_atualizacao_*`, `casca_instalar_*`), aprovados na prévia. O
    `casca_atualizacao_texto_simples` (só "Versão X.", Release sem novidades) não estava no brief original.
  - Não há aviso durante o download (o brief não prevê texto para isso).
  - Do `Atualizador` testa-se em JVM o fluxo inteiro com rede, telas e relógio falsos (`AtualizadorTest`); os diálogos de
    verdade e o instalador do sistema só se veem no aparelho. **Os dois testes que passam pelo `FileProvider` são pulados no
    Windows** (ele compara caminhos com `/`; medido em 17/10/2026) e rodam no Linux do CI; a configuração do provider
    (autoridade, uma pasta só, não exportado) é conferida em Node, no `testes/configuracao.test.mjs`.
- A splash e o resto do nativo vêm nas tarefas seguintes do plano.
