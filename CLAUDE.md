# CLAUDE.md — Nexus Field (app-campo-android)

**Nexus Field** é a casca Android (Capacitor 8.5.3) do App de Campo da Grid Co. O App de Campo é um PWA que roda ao
vivo em `https://gridco-campo-mw.azurewebsites.net/api/app`; a casca só o abre em tela cheia, com ícone, GPS e câmera
nativos, e guarda no aparelho o que o navegador pode perder. O site não muda: aqui só há leitura dele, e só pelo teste
de contrato. O aplicativo se chama Nexus Field (pedido do dono da casca, 17/10/2026); o site dentro dele continua
sendo o App de Campo. A pasta local ainda se chama `app-campo-android`; o repositório é `Grid-Co-CODE/nexus-field`.

- Desenho: `docs/superpowers/specs/2026-10-16-app-campo-android-design.md`
- Plano: `docs/superpowers/plans/2026-10-16-app-campo-android.md`

## Montar e testar

```
npm install
npm test                                   # testes de Node (script de início, configuração, ícone, nome, workflows, contrato, governança)
source ferramentas/ambiente.sh             # JAVA_HOME, ANDROID_HOME, PATH e android/local.properties (JDK 21, SDK API 36)
npx cap sync android && (cd android && ./gradlew testDebugUnitTest assembleDebug lint)
```

O `lint` sai sem erro (os avisos que restam são do template e das versões). O `ambiente.sh` escreve o `sdk.dir` com o
`:` escapado (`C\:/Users/...`): sem o escape o lint falha com PropertyEscape, mesmo o Gradle lendo o caminho certo.

No Windows, rode o `npm test` pelo Git Bash: o `testes/workflows.test.mjs` roda os scripts dos passos da Release num
bash de verdade e PULA esses testes fora do Git Bash (o `bash.exe` do System32 é o do WSL, outro sistema de arquivos).

O APK de depuração sai em `android/app/build/outputs/apk/debug/app-debug.apk`. Não há emulador nesta máquina. O adb, num
aparelho de prova ligado por USB, só instala ou desinstala `br.com.gridco.nexusfield` e lê o logcat filtrado ao app: nada de
shell, `run-as`, `pull`, print de tela, outro app ou configuração do sistema. Sem o cabo, a prova inteira se faz com o APK
instalado por arquivo ou link: o roteiro é o `docs/prova-no-aparelho.md` (caixas de seleção). O APK de depuração nunca vai
a técnico: no campo, só o da Release.

O que o GitHub Actions roda (`.github/workflows/`): em todo push e PR, o `npm test`, o `testDebugUnitTest assembleDebug lint`
e o APK de depuração como artifact (`montar.yml`); em tag `v*`, a Release assinada; todo dia às 11h UTC, o contrato com o
site (`contrato.yml`). Detalhes na seção "A Release e o CI", mais abaixo.

## Regras que não mudam

- O site atual não muda (`campo.html`, `sw.js`, `function_app.py`, App Settings, CORS, rotas).
- `server.url` é só a origem (`https://gridco-campo-mw.azurewebsites.net`), sem caminho e sem barra no fim;
  `server.appStartPath` é `/api/app`.
- `server.allowNavigation` tem só hosts de login da Microsoft. O Fracttal nunca entra.
- `CapacitorHttp` fica desligado. Capacitor fixo em 8.5.3 (`@capacitor/core`, `cli`, `android`).
- O user agent termina com `NexusField/<versionName>` (`CascaPlugin.userAgent`).
- Nome "Nexus Field" (o `appName` do Capacitor, o `app_name` e o rótulo do ícone, `title_activity_main`);
  `applicationId` `br.com.gridco.nexusfield`. O pacote Java e o `namespace` ficam `br.com.gridco.campo` (só código):
  o que depende do `applicationId` o lê do build (`${applicationId}` no manifesto, `BuildConfig.APPLICATION_ID` no
  código), menos o `targetPackage` do atalho de depuração (o `res/xml` não aceita o `${applicationId}`; o
  `testes/depuracao.test.mjs` compara). Mudou em 17/10/2026, antes da primeira Release: depois dela, outro
  `applicationId` é outro aplicativo para o Android, e cada técnico teria de desinstalar o antigo (o que apaga o
  espelho). Um APK de depuração de antes (`br.com.gridco.campo`) instala AO LADO do novo, não por cima.
- **Texto da casca diz Nexus Field**, nunca "App de Campo" nem "o App" para falar do aplicativo; "App de Campo" só onde
  o texto fala do site. O `testes/nome.test.mjs` lê o que a casca mostra (o `strings.xml` do main e do debug, a tela
  "Sem internet", a página local, as novidades, o `appName`) e falha no nome errado; o trecho que fala do site entra na
  lista `SOBRE_O_SITE` dele, com o motivo.
- A casca nunca envia nada da fila ao servidor. Quem envia é a página.
- O espelho nunca guarda `msal.*`, o `sessionStorage` nem token. `gc_campo_ident` fica cifrado em repouso (Android Keystore).
- `allowBackup=false` e `dataExtractionRules` excluindo tudo em `<cloud-backup>` e `<device-transfer>`.
- Sem serviço pago, sem segredo no repositório ou no APK, sem nome de pessoa em arquivo versionado: cite pelo papel ("o
  dono da casca", "o responsável pela prova", "o aparelho de prova"). pt-BR, sem emoji.
  O `testes/dados-pessoais.test.mjs` varre o `git ls-files` e falha com `caminho:linha (tipo)`, nunca o valor, em
  e-mail de pessoa (passam o de "não responda" e os de domínio fictício: `exemplo.com.br`, `example.com`, `*.test`),
  telefone, CPF, nome na pasta do usuário (`C:/Users/<nome>`) e o nome de quem trabalha no repositório: a lista sai dos
  autores do `git log`, só na memória (como o teste do Nexus faz com o cadastro), e conta o nome inteiro em qualquer
  lugar e o primeiro nome com maiúscula depois de artigo ou preposição ("o X preenche", "do X") ou antes de um sobrenome
  com maiúscula ("Responsável: X Sobrenome": o autor daqui é só a conta "Nome-1234", e o nome inteiro nunca se
  monta). Caso que o criou: o
  roteiro da prova dizia quem preenche o usuário de teste e de quem era o celular. Ficam de fora só o desenho e o plano
  datados de 16/10, pelo caminho exato (`FORA_DA_VARREDURA_DE_NOMES`), porque ainda trazem o dono como responsável;
  nunca a pasta: desenho ou plano novo em `docs/superpowers/` é varrido, e entrada da lista cujo arquivo saiu do git faz
  o teste falhar. Nome de colega que não é autor do git ele não acha: isso segue na revisão. **No CI a lista sem nenhum
  autor que seja gente é FALHA, não pulo** (`CI=true`, que o GitHub Actions define; fora do CI, no computador de quem
  desenvolve, pula como antes), e o checkout do job `montar` traz o histórico inteiro (`fetch-depth: 0`): num
  pull_request o checkout raso tem só o commit de merge de teste do GitHub, de autor "GitHub", e a varredura de nomes
  ficava verde sem procurar ninguém. O `dados-pessoais.test.mjs` prova a fiação rodando uma cópia dele num repositório
  novo com esse único autor (falha com `CI=true`, pula sem).
- A chave de assinatura e as senhas nunca são impressas em terminal, log ou commit.
- O repositório é PÚBLICO e se chama `Grid-Co-CODE/nexus-field` (decidido em 17/10/2026, antes de existir no GitHub): o
  app lê a `releases/latest/download/` sem autenticação, e o `VersaoRemota` só aceita APK desse endereço. Privado,
  movido ou criado com outro nome, o app fica sem atualização em silêncio (o `testes/versao-json.test.mjs` confere o
  nome nos três lados: `Atualizador.URL_VERSAO`, `VersaoRemota.PREFIXO_DO_APK` e o gerador). O APK publicado é o
  `nexus-field.apk`.
- Suporte no campo: nunca mandar "Limpar armazenamento" nem desinstalar (os dois apagam o espelho, a última cópia do
  trabalho que não subiu). Para destravar o login, o "limpar só o login" do próprio App.

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
  `${applicationId}.arquivos` (classe `ArquivosProvider`, caminhos em `caminhos_arquivos.xml`) é só do instalador da
  atualização e expõe apenas `cache/atualizacao/`. A subclasse é prevenção: o AGP 8.13.0 aceitou os dois com a mesma
  classe no mesmo arquivo (medido em 16/10/2026), mas para o sistema o `android:name` repetido é o mesmo componente. O
  `Atualizador` monta a autoridade com o `BuildConfig.APPLICATION_ID` (escrita à mão, ela ficava para trás na troca do
  `applicationId`, e o instalador procuraria um provider que não existe).
- **As fotos da reserva da câmera têm faxina** (`FaxinaDaCamera`, chamada uma vez por processo pelo `CascaPlugin`, na
  thread do espelho): o `BridgeWebChromeClient` grava cada foto do `<input capture>` num `JPEG_*.jpg` em
  `getExternalFilesDir(Pictures)` (ou no cache, sem armazenamento externo) e nunca apaga. Eram MB por foto para sempre,
  legíveis por outro app no Android 7 a 10, e o disco cheio é o que faz o Chromium despejar o armazenamento da WebView.
  Sai o que tem mais de 48 h (a página já copiou a foto para o IndexedDB): na pasta das fotos, todo arquivo; no cache,
  só o `JPEG_*.jpg` da raiz (lá moram a WebView e o instalador). O arquivo "do futuro" (a hora voltou) fica.
- **`regras_dados.xml` exclui todos os domínios** nos dois blocos, porque a exclusão vale só para o domínio citado.
- **Ícone (17/10/2026):** o G da Grid em BRANCO sobre o navy do Nexus (`#090d18`, o `ic_launcher_background`). A
  fonte é o `docs/marca/grid-g-branco.png`: o `grid-icon.png` verde do Nexus (`nexus/torres/oscreator/os_creator/assets/`,
  1147 x 1146) recolorido para branco puro com o alfa do original. Os PNG saem do `ferramentas/gerar-icones.py`
  (Pillow; `--verde <grid-icon.png>` refaz também a cópia branca, só lendo o original): o primeiro plano do adaptativo
  (`ic_launcher_foreground`, 108 dp), o mesmo G no ícone monocromático do Android 13+ (o `<monochrome>` dos dois XML do
  `mipmap-anydpi-v26`) e os antigos do Android 7 (`ic_launcher` quadrado e `ic_launcher_round`, 48 dp, que mostram os
  72 dp visíveis do quadro), nas cinco densidades. O G ocupa 52% da largura do quadro: ele é quase um círculo (o pixel
  mais longe do centro fica a 1,002 da meia largura), e o raio dele (~28,1 dp) cabe no círculo de 66 dp que toda
  máscara mostra (círculo, squircle, gota). O ícone de antes (o logo do site em 61,6% do quadro) passava dessa zona. O
  `testes/icone.test.mjs` lê os PNG pixel a pixel (pelo `testes/png.mjs`, um leitor de PNG só com o zlib do Node): o G
  inteiro dentro do círculo de 66 dp, no máximo 56% do quadro, centrado e branco puro, em toda densidade. O `lint`
  avisa `IconLauncherShape` nos quadrados antigos (preenchem o quadro, como os de antes): é aviso, não erro.
- **A abertura (splash):** o navy do ícone com o G pequeno no centro (decisão: cor sólida COM o G, ~100 dp). O tema
  `AppTheme.NoActionBarLaunch` define o `windowSplashScreenBackground` (o navy) e o `windowSplashScreenAnimatedIcon`
  (`drawable/splash_icone.xml`: o primeiro plano com 48 dp de margem, porque o sistema o desenha num quadro de 288 dp e
  o G sairia com ~150 dp); sem eles, o padrão do androidx era o fundo do tema e o ícone genérico do Android. O
  `android:background` do tema é o `drawable/splash.xml` (o navy e o G num quadro de 192 dp, o mesmo tamanho). Os
  `splash.png` do template saíram. O aspecto exato no Android 12+ é do sistema e se confere no aparelho.
- **A tela "Sem internet" e a página local** (`www/erro.html`, `www/index.html`) têm o fundo navy do Nexus Field
  (`#090d18`), o mesmo do ícone e da abertura (R40, 18/10/2026). Até 17/10/2026 eram o roxo-escuro do site (`#191528`),
  porque o ícone também era; com o ícone e a abertura navy, a sequência no aparelho ficava abertura navy, erro roxo. O
  texto, o texto suave e o destaque ficaram como eram (o contraste não pediu troca); o texto do botão (`--sobre-destaque`)
  é o navy. O `testes/icone.test.mjs` confere que o `--fundo` das duas é o `ic_launcher_background`, que um comentário
  diz que a cor é a do Nexus Field e o contraste (WCAG AA) do texto, do texto suave e do botão. Na prévia, o `srcdoc` é a
  cópia do `erro.html` (o `previas.test.mjs` compara) e o quadro dele pinta o `--icone-fundo`; o `--app-fundo` dela
  segue o roxo do SITE, atrás dos diálogos e da camada do Fracttal.
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
  - **O disjuntor do dia** (`Mensageiro.DisjuntorDaPerda`): no máximo 3 restaurações por 24 h NO APARELHO em cada uma
    de duas contas separadas, porque a página refaz na carga seguinte a restauração que não terminou, e o disco cheio
    que recusa sempre faria cada abertura pedir tudo de novo e segurar a página. Uma conta é a das de perda
    (`liberaPerda`, o `precisoRetrato` 'perda'; estourada, vai o sem conferência, e a página segue sem espelho com a
    marca de pé até a janela andar); a outra, a das só do IndexedDB (`liberaIdb`, o `idbPedir` com `motivo: 'idb'`, ou
    sem motivo; estourada, o nativo responde `{t:'idbRecusado'}` pela ponte e a página abre na hora). Separadas porque,
    com uma conta só, as restaurações só do IndexedDB (o banco sentinela que falta a cada carga com o disco cheio)
    gastavam as vagas do dia, e a perda de verdade seguinte recarregava sem o retrato completo. O `idbPedir` da perda
    leva `motivo: 'perda'` e não conta de novo. O de 60 s é conferido antes: a recarga em laço não gasta o do dia. As
    contagens moram no SharedPreferences `casca_espelho` (fora do backup, como tudo), nas chaves `restauracoes` (perda)
    e `restauracoes_idb`, com o relógio de parede (atravessa a reinicialização); a marca "do futuro" (a hora do aparelho
    voltou) não conta, senão travaria pelo tempo que a hora voltou. Se o SharedPreferences não gravar, vale a contagem
    do objeto, um por processo.
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
- **O script de início, por cima** (`www/casca/inicio.js`): o plugin o injeta com `addDocumentStartJavaScript`, só na
  origem do App, antes de qualquer script da página (venha o HTML da rede ou do cache do service worker). É script
  clássico (no Node dos testes vira CommonJS) e nunca pode quebrar a página: todo erro vira `{t:'erro', onde}`, e o
  `onde` é de uma lista fechada (`Mensageiro.ONDES_DA_PAGINA`, que o `testes/canal.test.mjs` compara com o script). Faz,
  na ordem das seções: (1) a classificação das chaves (`CLASSES`: fila, trabalho, cache; o resto não vai ao nativo) e os
  nomes do voltar (`VOLTAR`), as duas listas que o contrato com o site lê; (2) o canal ao nativo; (3) o espelho de
  escrita do localStorage, que embrulha só `setItem` e `removeItem` (a fila vai na hora; trabalho e cache em lote de
  400 ms); (4) a sentinela, as versões da fila e a restauração do localStorage; (5) o espelho e a restauração do
  IndexedDB das fotos (`gc_campo_fotos`, store `fotos`); (6) o `window.open` do login e do Fracttal; (7) o voltar.
  - **As marcas no localStorage**, gravadas pelo `setItem` original (fora do espelho; o `classificar` as deixa de fora):
    `gc_casca_sentinela` (presente = o armazenamento não foi perdido) e `gc_casca_perda_pendente` (a perda começou e
    ainda não terminou). **O banco `gc_casca`** no IndexedDB: "a última restauração do IndexedDB terminou".
  - **É perda** quando a marca está de pé, ou quando a sentinela falta E o localStorage está vazio no começo do
    documento. Sentinela ausente com chaves é a faxina por tamanho do próprio site (o despejo do `lsS` não protege a
    sentinela): a casca a regrava e segue, porque parar justo com o disco cheio deixaria a página em branco.
  - **As chaves de sessão** (sessionStorage, que o "limpar só o login" apaga): `gc_casca_fila_sessao` (o `fnv1a32` da
    última fila espelhada ou restaurada nesta sessão: vale como "em dia"); `gc_casca_parou` (a trava: a carga só para
    uma vez por sessão para pedir o retrato); `gc_casca_retrato_aplicado` (o id do retrato completo já aplicado, para
    não aplicar o mesmo duas vezes); `gc_casca_ls_reenviado` (o reenvio do localStorage ao espelho recriado, uma vez);
    `gc_casca_idb_reconciliado` (a reconciliação do IndexedDB, uma vez).
  - **O retrato completo** (o que o nativo manda quando a carga para) é o leve mais `modo: 'restaurar'`, `motivo`
    (`'perda'` ou `'fila'`), um `id` novo e não vazio, o `ls` e a `fila`.
  - **O espelho do trabalho e do cache é de melhor esforço:** a mensagem que o canal recusa descarta aquela chave até a
    próxima gravação dela (a fila não espera lote; o reenvio ao espelho recriado e a reconciliação do IndexedDB cobrem o
    caso do banco nativo perdido). Não há lixeira: o espelho repete as exclusões da página.
  - **Pedidos que ficam com a sessão "App de campo"** (o site não muda daqui): proteger `gc_casca_*` no `_lsProtegida`
    do despejo por tamanho (numa carga travada ele pode levar a marca da perda); revisar os textos pensados para o
    navegador que aparecem dentro do app (o "Pode fechar esta aba" na camada do Fracttal).
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
  - **A restauração nunca sobrescreve o que a WebView tem.** Na da perda, a chave de trabalho ou de cache com valor na
    WebView fica como está e só as ausentes voltam do `ls` do retrato (a fila tem a regra dela, abaixo); no IndexedDB, a
    da perda e a só do IndexedDB (sem o `gc_casca`, ou a store das fotos vazia com o nativo cheio) gravam só as chaves
    AUSENTES. A da WebView é a mais nova: o nativo só recebe o que a página espelha. Caso que criou a regra: a
    restauração das fotos que não termina (a marca fica) e o disjuntor do dia deixam a página rodar sem espelho, com o
    localStorage restaurado, por até um dia; o técnico mexe na `gc_zl_pend`, no `gc_fech_draft` e no rascunho da ronda
    (`rdraft:` no IndexedDB), e a restauração da janela seguinte gravava por cima a versão velha do nativo. Na perda de
    verdade a WebView está vazia, e tudo volta. Custo aceito: o que a página apagou numa carga sem espelho volta. No fim
    de toda restauração, o que só a WebView tem no IndexedDB vai ao nativo; o valor da WebView que difere do nativo no
    IndexedDB só chega a ele quando a página o regravar. **No localStorage, a restauração da PERDA manda o que a WebView
    manteve** (R40, 18/10/2026; `reenviarOQueAWebViewManteve`): depois do `restaurado` (e da fila juntada), vai
    `{t:'ls', k, v}` de cada chave de trabalho e de cache cujo valor na WebView difere do `ls` do retrato ou que o
    retrato não tem, o trabalho primeiro, pelo mesmo `enviar` e rótulo `reenvio` do reenvio ao espelho recriado (as
    chaves vêm do mesmo `chavesDoEspelho`). Caso que criou a regra: o rascunho do fechamento refeito numa carga sem espelho
    ficava só na WebView, e uma SEGUNDA perda antes de a página regravá-lo devolvia a versão velha do nativo. Nunca manda
    apagar; é de melhor esforço (o canal que recusa uma chave é avisado só com o rótulo e as outras seguem); o que acabou de
    voltar do nativo é igual a ele e não é reenviado. A restauração só da fila não faz isso: o `ls` dela vem vazio, e vazio
    ali não quer dizer que o nativo não tem.
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
    `https://github.com/Grid-Co-CODE/nexus-field/releases/download/<pedaços simples>` (sem `..`, `?`, `%`) é recusado:
    o SHA que o próprio JSON traz não protegeria de um link para outro lugar.
  - A hora da checagem vai ao SharedPreferences ANTES da rede: falhar também conta as 6 h, senão o campo sem sinal esperaria
    15 s a cada volta ao app. A checagem é silêncio: rede e JSON torto vão só ao log, sem endereço nem nome de arquivo.
    Depois do "Atualizar agora" a pessoa pediu: um toast diz que está baixando (`casca_atualizacao_baixando`; sem retorno
    nenhum por vários MB de dados móveis, o técnico ia a outro app), e o download que não termina, o SHA errado e o erro
    antes do instalador avisam no toast de sempre (`casca_atualizacao_sha_toast`). Sem nova tentativa em laço.
  - Um fluxo por vez em todo o processo (`DONO_DO_PROCESSO`; a tela recriada do renderer que morreu é outra instância, e dois
    downloads para `cache/atualizacao/nexus-field.apk` o corromperiam). O `load()` e o resume que vem logo depois caem aí.
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
    dono da casca na prévia. O `casca_atualizacao_texto_simples` (só "Versão X.", Release sem novidades) e o
    `casca_atualizacao_baixando` (17/10/2026) não estavam no plano.
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
  sem o shell do adb, que a regra do aparelho de prova não permite; ou pelo
  `am start -n br.com.gridco.nexusfield/br.com.gridco.campo.MainActivity --ez gc_apagar_webview true` num aparelho de
  prova (a classe com o nome inteiro: o `.MainActivity` curto procuraria no `applicationId`). Com a tela aberta o atalho
  só a traz para a frente (`singleTask`): tire o Nexus Field dos recentes antes. Os textos dela ficam em
  `src/debug/res/values/depuracao.xml`, sem o prefixo `casca_` e fora da prévia (quem
  os vê é quem faz a prova). Quem prova: o `testes/depuracao.test.mjs` (nada do caminho, nem o nome do extra nem o
  atalho, no `src/main` ou no `src/release`; a do release vazia; a do debug sem tocar no espelho; a ordem no
  `MainActivity`; o `targetPackage` do atalho igual ao `applicationId`, que muda junto com ele; o `am start` do roteiro
  com o componente inteiro) e o `DepuracaoTest`
  (em `src/testDebug`, porque o release nem tem a classe; lê o XML do atalho pelo `Intent.parseIntent`, como o
  launcher, e confere que o `android:value="true"` chega como booleano). Medido em 17/10/2026: o
  `app-release-unsigned.apk` não tem o atalho no manifesto nem `gc_apagar_webview`/`deleteAllData` no dex, e o
  `aapt2 dump xmltree` do de depuração mostra o `value` compilado como booleano.
- **A prévia das telas novas** (`docs/previas/telas.html`, fora do APK; título "Telas novas do Nexus Field"): uma página
  só, autocontida, com o ícone e o nome (no topo), a abertura, os diálogos do `strings.xml`, os toasts, o `www/erro.html`
  num iframe, a camada do Fracttal e os nomes dos canais de notificação (seção Notificações, o quadro `#t-canais` dos
  ajustes do Android), cada tela numa moldura de 360 px, para o dono da casca aprovar texto e visual antes
  de ir ao campo. **O ícone, o nome e os textos estão PENDENTES de aprovação do dono da casca**
  (a página é publicada como Artifact privado do claude.ai e ele aprova por lá); quando ele aprovar, esta frase e os
  cabeçalhos do `strings.xml` passam a dizer "aprovados", com a data. O texto vive no arquivo-fonte, não na prévia:
  pedido de mudança se faz no `strings.xml`, no `erro.html` ou no `inicio.js` e a prévia acompanha.
  - **Ícone e nome:** o PNG do APK (o `ic_launcher_foreground` de xxxhdpi, byte a byte) embutido UMA vez como `data:` URI
    num `<image id="g-nexus-field">`, e cada cópia é um `<use>` (oito ícones e a abertura sem repetir 9 KB de base64).
    Cada ícone é um SVG com `viewBox="18 18 72 72"` (os 72 dp que o launcher mostra do quadro de 108), o fundo pelo token
    `--icone-fundo` (igual ao `ic_launcher_background`) e a máscara por `clip-path`: `circle(50%)` e a squircle do AOSP
    em pontos. Nos papéis de parede claro e escuro, em 48 px e 144 px, com o rótulo do `title_activity_main`. A abertura
    é o G num quadro de 192 px, como o `drawable/splash.xml`. Depois de rodar o `gerar-icones.py`, troque o `data:` (o
    teste compara os bytes e diz).
  - O `testes/previas.test.mjs` falha se um diálogo da prévia não for um grupo do `strings.xml` (título, texto e botões
    no conjunto e na ordem do aplicativo: negativo à esquerda, positivo à direita, como a `MainActivity`, o `CascaPlugin`
    e o `Atualizador` os montam; o teste lê os `AlertDialog.Builder` do Java), se os toasts da prévia não forem, no
    conjunto, os `Toast.makeText(..., R.string.x, ...)` do Java (o teste os lê do código), se uma string da casca ficar
    sem quadro, se os nomes no quadro dos canais não forem os `notificacao_canal_*` que o `CanaisDeNotificacao` dá a cada
    canal, se o `strings.xml` ganhar uma string de prefixo sem quadro na prévia (só `package_name` e
    `custom_url_scheme` ficam fora, porque o técnico não os lê; caso de 18/10/2026: os nomes dos canais trocaram o
    `casca_` por outro prefixo e nenhum teste exigiu o quadro), se as novidades não forem uma linha `- item` cada, se o `srcdoc` do erro não for o `erro.html` inteiro (copie o arquivo
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
- **A Release e o CI** (`.github/workflows/montar.yml` e `contrato.yml`; ainda sem repositório no GitHub: a primeira
  execução real é a da Task 10). O Actions não roda daqui, então o `testes/workflows.test.mjs` lê os dois arquivos com um
  parser de YAML (o pacote `yaml`, dependência de desenvolvimento) e confere as regras abaixo na estrutura (jobs, passos,
  o `env` de cada passo), e RODA os scripts dos passos que decidem a Release num bash com `curl`, `gh` e `apksigner`
  falsos (o commit na main num repositório git de verdade, a versão da tag, o versionCode, o certificado, a publicação e
  o agendamento). A pasta tem exatamente os dois arquivos: um terceiro escaparia das regras.
  - **Montagem (`montar`):** checkout com o histórico inteiro (`fetch-depth: 0`, para a varredura de nomes achar os
    autores; ver "Regras que não mudam"), Node `22.x` (o jsdom 30 pede `^22.22.2`; o `package.json` declara `engines`), `npm ci` e
    `npm test`, depois Java 21 (Temurin), `npx cap sync android` e `./gradlew testDebugUnitTest assembleDebug lint`, e o
    APK de depuração sobe como artifact. Neste job e no contrato as actions vão por versão maior (`actions/checkout@v7`,
    `setup-node@v7`, `setup-java@v6`, `upload-artifact@v7`, `gradle/actions/setup-gradle@v6`): não há segredo nem escrita
    em conteúdo. O `setup-gradle` usa `cache-provider: basic` (o `enhanced`, padrão, é um serviço comercial; regra de
    "sem serviço pago").
  - **Os dois testes do FileProvider têm de rodar no Linux.** `instaladorRecebeOUriDoProviderParaOApkDaPasta` e
    `oProviderDoInstaladorNaoExpoeNadaAlemDeCacheAtualizacao` são pulados no Windows e o Gradle conta pulado como sucesso:
    o `ferramentas/testes-pulados.mjs` lê o XML de resultado e derruba o CI se um deles aparecer pulado, falho ou sumido
    (renomeado). Esta é a primeira vez que eles rodam fora do Windows: se falharem no Linux, o problema é do teste ou do
    `FileProvider`, não do conferidor.
  - **A Release (`release`, só em tag `v*`, depois do `montar`, no ambiente `release`).** `contents: write` é só deste job.
    Ela vai aos ~50 aparelhos e não tem volta (o versionCode não desce, a chave não muda), e o GitHub roda o `montar.yml`
    do COMMIT da tag: uma tag num ramo com o arquivo mexido leva junto as conferências. A guarda de verdade é a
    configuração do repositório (abaixo, "O que a Task 10 configura no GitHub"); as conferências do job pegam o engano,
    nesta ordem, todas antes de a chave sair do secret:
    0. **o repositório é o `Grid-Co-CODE/nexus-field`** (R40, 18/10/2026; o primeiro passo do job, antes até do checkout, e
       compara o `GITHUB_REPOSITORY` do runner com o nome inteiro, na caixa certa): o app lê o `versao.json` de
       `releases/latest/download/` DESTE repositório e só aceita o APK das Releases dele. Uma Release que saísse de um fork,
       do nome antigo ou de um repositório criado com outro nome seria publicada, assinada e vista por ninguém, em silêncio.
       A mensagem diz que o app só enxerga esse repositório. O `testes/versao-json.test.mjs` confere o nome no
       `Atualizador.URL_VERSAO`, no `VersaoRemota` e no gerador do `versao.json`, e o `testes/workflows.test.mjs` põe este
       passo frente a frente com o `URL_VERSAO` e o roda com repositórios parecidos (outra caixa, sufixo, prefixo, vazio);
    1. **o commit da tag está na `main`** (`fetch-depth: 0` e `git merge-base --is-ancestor`): a tag posta por engano num
       ramo de worktree publicaria código que nunca passou pela `main`;
    2. **a versão da tag** pelo `node ferramentas/versao-json.mjs tipo-da-versao`: o semver mora só no `PADRAO_VERSAO` de
       lá (o mesmo do `versionName`), sem regex no bash; tem de existir `novidades/<versão>.md` (as linhas `- ` viram as
       novidades do aviso e o arquivo inteiro é o texto da Release). Sai `final` ou `pre-lancamento` (no
       `TIPO_DA_VERSAO`): `1.1.0-rc.1` vira pré-lançamento, que a `releases/latest/` do app não enxerga; o hífen do
       metadado de build (`1.0.0+ci-7`) não conta (o `case *-*` antigo soltava essa versão final como pré-lançamento);
    3. **o versionCode cresce**: o `versao.json` da `releases/latest/download/` é lido e o `conferir-crescimento` exige o
       `GITHUB_RUN_NUMBER` maior; o 404 só vale sem nenhuma Release publicada. O número é o `GITHUB_RUN_NUMBER` **do
       workflow `montar`: não renomeie, não divida nem apague o arquivo**, senão a contagem recomeça em 1 e o app deixaria
       de oferecer a atualização, em silêncio.
  - **A chave:** no job release, toda action é fixada por SHA de commit, com a tag num comentário (conferidos com
    `gh api repos/<action>/commits/<tag>` em 17/10/2026, cada versão com ao menos duas semanas): uma tag móvel sequestrada
    (o caso tj-actions/changed-files, 03/2025) rodaria ali com o token de escrita e perto das senhas. O `npm ci` e o
    `npx cap sync android` rodam ANTES de a chave sair do secret, sem as senhas; sem cache nenhum (npm e Gradle). O secret
    `CASCA_KEYSTORE_B64` é decodificado para `$RUNNER_TEMP/casca.jks` (permissão só do dono, `printf` direto no
    `base64 -d`, sem `echo`); as `CASCA_*` entram só no `env:` do passo do `./gradlew assembleRelease` (nunca no
    `GITHUB_ENV`), e a chave sai do disco quando o Gradle termina (`trap`) e de novo no último passo (`if: always()`). O
    alias vem de `vars.CASCA_CHAVE_ALIAS`: não é segredo, e como secret o GitHub mascarava "campo" em todo log do job
    (`app-***.apk`). Resta o Gradle (o AGP, o `capacitor-android`) rodando com as senhas, que a assinatura pelo AGP pede;
    o passo seguinte, se um dia valer, é montar sem assinar e assinar com o `apksigner` num passo só.
  - **O pino do certificado:** depois do build, o `apksigner verify --print-certs` (o build-tools do runner) tem de passar
    e o `ferramentas/certificado.mjs` exige exatamente um signatário e o SHA-256 dele igual ao da variável do repositório
    `CASCA_CERT_SHA256` (aceita o formato do `keytool`, maiúsculo e com `:`); sem a variável, nada é publicado. O
    `apksigner` do build-tools 37 (o mais novo do runner em 18/10/2026) imprime "V2 Signer: ..." (uma linha por esquema)
    no lugar do "Signer #1 ..." do 36: o conferidor aceita os dois (a 1ª tag, v1.0.0, caiu nisso e nada foi publicado). Os
    aparelhos confiam para sempre na chave da primeira versão: um APK com outra chave (a de teste, uma regerada) seria
    oferecido a cada 6 h e recusado pelo instalador, e o "desinstale" apagaria o espelho. A Release ("Nexus Field
    <versão>") leva `nexus-field.apk`, `nexus-field.apk.sha256` e `versao.json`; o APK de depuração do CI sobe como o
    artifact `nexus-field-debug`.
  - **O `ferramentas/versao-json.mjs`** gera o `versao.json` com as MESMAS regras do `VersaoRemota.java` (o
    `testes/versao-json.test.mjs` lê o `.java` e compara o prefixo, o padrão do link e o SHA; o SHA tem 64 hex minúsculos,
    o `versionName` é o semver, o `apk` sai de `v<versão>/nexus-field.apk`). O SHA-256 vem do próprio APK; o workflow ainda
    confere com o `sha256sum`. Sem nenhuma linha `- ` nas novidades o arquivo sai com a lista vazia (o app mostra só
    "Versão X."). Os subcomandos `tipo-da-versao` e `conferir-crescimento` são os dos passos 2 e 3 acima.
  - **O contrato com o site (`testes/contrato.mjs`):** um GET do `/api/app`, só leitura. Importa do `www/casca/inicio.js`
    a `CLASSES`, o `classificar` e o `VOLTAR` (as listas existem num lugar só). Falha, dizendo o NOME (nunca um trecho do
    HTML), se:
    - **sumir** do site uma chave exata (entre aspas) ou um prefixo da `CLASSES`, o banco `gc_campo_fotos` com a store
      `fotos` por perto, a `conectarFracttal` com o `window.open('','_blank')`, o `loginRedirect`, o `'/api/app'`, o
      `msal-browser` na 2.38.1 (no mesmo endereço), ou um id, classe, função de topo, o `let sheetTask` ou o
      `setTab('tarefas')` do `VOLTAR` (nome trocado no site só faz o voltar daquela camada minimizar o app);
    - **aparecer** no site uma chave `gc_` (entre aspas, em qualquer uso: `setItem`, o `lsS`/`lsG` do site, constante,
      lista; ou começo de template com `${`) que o `classificar` não classifique e que não esteja na `IGNORAR` do próprio
      contrato (cada entrada com o motivo: o que a spec manda nunca espelhar, preferências, contadores, cópias do que o
      servidor manda; as `gc_casca*` e o `gc_campo_fotos` passam). Chave nova de TRABALHO vai para a `CLASSES`, e isso
      pede uma Release (o `inicio.js` vai dentro do APK); só o que se refaz sozinho entra na `IGNORAR`. Caso que criou a
      regra: um rascunho novo que o site passasse a gravar ficaria fora do espelho em silêncio até a primeira perda;
    - **aparecer** outro `indexedDB.open` que não o do `gc_campo_fotos`, o `gc_campo_fotos` noutra versão que não a 1, uma
      store que não a `fotos`, ou `createObjectStore` com opções (keyPath, autoIncrement): a restauração cria o banco na
      versão 1 e grava `put(valor, chave)`, que numa store com keyPath lança;
    - o site passar a usar o que o espelho não enxerga (`localStorage.clear(`, atribuição por índice, `delete
      localStorage`): o espelho só embrulha `setItem` e `removeItem`.

    Sai com 0 (ok), 1 (o site mudou), 2 (não deu para ler o site) ou 3 (o próprio contrato falhou). A resposta
    descartada (5xx, 4xx, outro host, não HTML) tem o corpo cancelado. Limites: a chave citada entre aspas simples num
    comentário conta (como presente e como nova; entre crases não conta); chave com caractere fora de `[A-Za-z0-9_.:-]`
    não é vista. Medido em 17/10/2026: passa no site vivo e na cópia v282, e cada mudança de uma peça no HTML real (versão
    do MSAL, store, `window.open`, prefixo, chave nova, versão do banco, keyPath, um id ou função do voltar) é pega. Nota
    para quem classifica: o `gc_campo_status_meta` (a hora de cada ação do técnico por tarefa, o `META_KEY` do site) é
    TRABALHO, ao lado do `gc_campo_status`: sem ele, depois de uma restauração, o `reconciliar` do site (o reparo do
    estado órfão) descarta os estados locais sem item na fila. Que o "limpar só o login" do site o apague não muda nada:
    a exclusão vai ao espelho como qualquer outra (conferido no `campo.html` v282, 18/10/2026; o contrato passa nele).
  - **A issue diária (`contrato`, `ferramentas/issue-do-contrato.mjs`):** uma issue por TIPO, com rótulo e título
    próprios: `contrato` (o site mudou; não fecha sozinha, ganha um comentário quando a saída muda e outro, uma vez, quando
    volta a passar), `contrato-leitura` (não deu para ler o site; fecha sozinha quando o site for lido de novo) e
    `contrato-erro` (o próprio teste quebrou: código 3, ou a saída sem a linha do contrato, como o erro de sintaxe ao
    carregar o `inicio.js`, que sai com 1; fecha sozinha quando o contrato voltar a rodar). Com uma issue só para tudo,
    um site fora do ar passageiro escondia a quebra de verdade do dia seguinte. O corpo vai pelo stdin do `gh`, e sem
    conseguir listar as abertas nada é criado. O job fica vermelho quando o contrato falha.
  - **O contrato não morre calado:** o GitHub desliga o agendamento de repositório público sem atividade por 60 dias, e
    esta casca passa meses sem commit. A cada execução o passo "Manter o agendamento ligado" faz
    `gh api --method PUT repos/<repo>/actions/workflows/contrato.yml/enable` (por isso o `actions: write`). Não é
    documentado pelo GitHub; é o observado em 2026 (relato de 15/10/2026 na discussão #184653 da comunidade do GitHub: um
    repositório público seguiu rodando mais de 120 dias depois do último commit assim). Commit automático para "fazer
    atividade" não: o projeto mais usado que fazia isso foi desativado pelo GitHub por violar os termos. A primeira prova
    de que o PUT segura é nossa: conferir na aba Actions, uns 70 dias depois do último commit, que o contrato ainda roda
    (e não ignorar o e-mail "The workflow will be disabled soon", se ele vier).

## Distribuição

- **Primeira instalação:** o link fixo `https://github.com/Grid-Co-CODE/nexus-field/releases/latest/download/nexus-field.apk`,
  mandado pelo supervisor (mensagem ou QR). Na primeira vez o técnico autoriza "instalar apps desconhecidos" para o
  navegador. Depois, o próprio app se atualiza pelo `versao.json` (seção "A atualização da casca").
- **Verificação de desenvolvedor do Android:** no Brasil, desde 30/09/2026, vale para instalações vindas das lojas; o
  APK baixado por fora passa a ser cobrado na expansão global de 2027 (ainda sem mês). Antes disso, a Grid registra o
  pacote como organização no Android Developer Console: US$ 25 uma vez só, D-U-N-S, e-mail e site da empresa, e o
  SHA-256 da chave (o mesmo da `CASCA_CERT_SHA256`). Sem o registro, cada técnico teria de ligar o modo desenvolvedor e
  esperar 24 h, e as atualizações falhariam. O D-U-N-S é grátis e só demora: pedir já, e pagar os US$ 25 no dia em que
  for preciso. Se o aparelho do piloto pedir o fluxo avançado antes disso, o piloto usa a distribuição limitada (grátis,
  sem documento, até 20 aparelhos). Não é preciso publicar nada na Play.

## O que a Task 10 configura no GitHub

As conferências do `montar.yml` pegam o engano; quem segura a Release é esta configuração, que só se faz pela tela (ou
pela API) do GitHub e não aparece no código. Antes da primeira tag:

1. **A chave**, gerada uma vez, fora do repositório e do OneDrive: `keytool -genkeypair` com RSA 4096 e validade de 30
   anos. O `keytool` do JDK 21 cria PKCS12, que tem UMA senha: o `-keypass` diferente do `-storepass` é ignorado, e o
   `CASCA_CHAVE_SENHA` tem de ser a senha do keystore (senão a primeira tag cai em "Cannot recover key"). As senhas vão
   para um gerenciador de senhas, nunca para um arquivo ao lado do `.jks`. A cópia da chave fica fora do GitHub e do
   SharePoint, sob guarda do responsável; este arquivo não diz onde.
2. **O ambiente `release`** (Settings > Environments): regra de implantação "Selected branches and tags" só com a tag
   `v*` (nenhum ramo); revisor obrigatório (o dono da casca); os secrets `CASCA_KEYSTORE_B64`, `CASCA_KEYSTORE_SENHA` e
   `CASCA_CHAVE_SENHA` como secrets DESTE ambiente, nunca do repositório (o `montar` roda em todo push e PR).
3. **As variáveis do repositório** (Settings > Secrets and variables > Actions > Variables): `CASCA_CERT_SHA256` (o
   SHA-256 do certificado, do `keytool -list -v`; é público) e `CASCA_CHAVE_ALIAS`.
4. **Um ruleset de tag** para `v*`: criar, mover e apagar só pelos administradores (o papel de administrador na lista de
   exceções), e sem force push.
5. **Actions:** permissão padrão do token só de leitura (os workflows pedem o que precisam). O agente do GitHub
   (Copilot) não enxerga os secrets (moram só no ambiente `release`, com revisor) nem solta versão (o ruleset de tag).
6. **Primeira execução:** rodar o `contrato` à mão (`workflow_dispatch`) e ver o passo "Manter o agendamento ligado"
   passar (o PUT precisa do `actions: write`); depois da `v1.0.0`, conferir que a `releases/latest/download/versao.json`
   tem o `versionName` 1.0.0 e que o SHA-256 do APK baixado bate.
