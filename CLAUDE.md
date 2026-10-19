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

O `lint` sai sem erro. Os 13 avisos que restam (medido em 19/10/2026) são do template do Capacitor (`ManifestOrder`,
`UnusedResources`), da versão do Gradle (`AndroidGradlePluginVersion`), dos ícones quadrados do Android 7
(`IconLauncherShape`) e o `PluralsCandidate` do `casca_teste_enviado`, de propósito (ver "O atalho de teste dos avisos").
O `ambiente.sh` escreve o `sdk.dir` com o `:` escapado (`C\:/Users/...`): sem o escape o lint falha com PropertyEscape,
mesmo o Gradle lendo o caminho certo.

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
  library". No Linux do CI nada disso é preciso, e a variável fica vazia. Dois testes pedem mais, e já o têm: o
  `NotificacoesNoAndroid7Test` roda no SDK 24 (`@Config(sdk = N)`: o Android 7 não tem canais, e do 8 em diante o
  NotificationCompat apaga o som e a vibração da própria notificação), e o Robolectric baixa o jar do Android 7 (~88 MB)
  na primeira rodada, também no CI; o `MainActivityNaTelaTest` monta a MainActivity com o Bridge do Capacitor de verdade,
  com um shadow do `ServiceWorkerController` (o Robolectric não tem o provedor da WebView) e a versão da WebView dada ao
  `ShadowWebView`.
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
  (`#090d18`), o mesmo do ícone e da abertura (desde 18/10/2026). Até 17/10/2026 eram o roxo-escuro do site (`#191528`),
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
    `Voltar`, `TetoDeRecriacao`, `FaxinaDaCamera`, `PushNaCasca`, `OsDoToque`, `Expediente`), mais a `MainActivity` que
    nasce de verdade no Robolectric (`MainActivityNaTelaTest`: a marca `criando`, o `onNewIntent` e a tela recriada, pelo
    caminho da WebView antiga, a única que o Robolectric tem). O resto (WebView de verdade, o gesto do voltar, diálogos, a
    ligação de cada regra no plugin) é conferido pela montagem e pela prova no aparelho.
  - O AGP 8 não gera o `BuildConfig` sem `buildFeatures.buildConfig` (o user agent e o `DEBUG` vêm dele), e o
    `androidx.webkit` entra no app de novo porque o `capacitor-android` o tem só como `implementation`.
- **O script de início, por cima** (`www/casca/inicio.js`): o plugin o injeta com `addDocumentStartJavaScript`, só na
  origem do App, antes de qualquer script da página (venha o HTML da rede ou do cache do service worker). É script
  clássico (no Node dos testes vira CommonJS) e nunca pode quebrar a página: todo erro vira `{t:'erro', onde}`, e o
  `onde` é de uma lista fechada (`Mensageiro.ONDES_DA_PAGINA`, que o `testes/canal.test.mjs` compara com o script). Faz,
  na ordem das seções: (1) a classificação das chaves (`CLASSES`: fila, trabalho, cache; o resto não vai ao nativo) e os
  nomes do que a casca lê do site (`VOLTAR`, `PUSH`, `ABRIR_OS`), as listas que o contrato com o site lê; (2) o canal ao
  nativo (o `ouvirNativo` aceita vários ouvintes num `onmessage` só: a restauração das fotos e o registro do aparelho); (3)
  o espelho de escrita do localStorage, que embrulha só `setItem` e `removeItem` (a fila vai na hora; trabalho e cache em
  lote de 400 ms); (4) a sentinela, as versões da fila e a restauração do localStorage; (5) o espelho e a restauração do
  IndexedDB das fotos (`gc_campo_fotos`, store `fotos`); (6) o `window.open` do login e do Fracttal; (7) o voltar; (8) o
  registro do aparelho para as notificações, com o papel de Admin do atalho de teste; (9) a abertura da OS pelo toque na
  emergência (as seções 8 e 9 estão no bullet "As notificações do Nexus Field" e nos dois seguintes).
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
    manteve** (18/10/2026; `reenviarOQueAWebViewManteve`): depois do `restaurado` (e da fila juntada), vai
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
  (não enumerável) decide NA HORA a camada de cima e devolve true, e a fecha num `setTimeout(0)` pelo botão ou função
  que o técnico usaria (um `confirm()` da página não segura a resposta ao nativo). Sem nada aberto devolve false, e o
  nativo manda o app para o fundo; erro vira `{t:'erro', onde:'voltar'}` e false. A ordem é a do z-index do `campo.html`
  v282: a camada da casca; a foto `.rd-lb`; a câmera `#cam`; as esperas `#cham-wait`, `#geo-exige` e `#sonda.open`
  (consumidas sem fazer nada); a APR (o rascunho é salvo ANTES do `aprFechar`, que o botão de fechar da página não
  salva); os Extintores; as folhas de z 520 (o `#sheet` reabre a OS que ele tinha substituído); a OS, pelo botão do
  `#m-body` (o Cancelar da Pausa volta para a OS); o menu; as abas (o voltar da Ronda e da Zeladoria; as outras vão para
  Tarefas). Os nomes ficam todos no `VOLTAR` exportado, que o contrato com o site exige; um nome trocado no site só faz
  o voltar minimizar, nunca fecha a camada de baixo no lugar da de cima. O `sheetTask` é um `let` de topo do site, que
  não vira propriedade da janela: é lido pelo nome (o teste prova num jsdom que roda o script e a página como scripts de
  verdade). Se o site adotar o histórico (pushState ao abrir camada), a seção sai e o nativo passa ao `goBack()`. A
  pilha mora numa função só, `camadaDeCima`, que devolve também se a camada segura o técnico (`ocupa`; o serviço aberto
  da Zeladoria ocupa, e a Ronda numa etapa vem marcada com `ronda: true`, porque só segura com a ronda começada, o que a
  seção 9 decide pelo critério do site): é ela que a abertura da OS pelo toque (seção 9) pergunta, sem fechar nada. O
  que o voltar faz não muda com isso. Camada nova do site entra ali e vale para o voltar e para o toque juntos.
- **O voltar do Android, do lado nativo** (`Voltar`, ligado pelo `CascaPlugin` no começo do `load()`, também com a
  WebView antiga): um `OnBackPressedCallback` do androidx.activity, que cobre o voltar preditivo do targetSdk 36
  (sobrescrever o `onBackPressed` não cobriria). Com a página do App aberta (a URL na origem do `server.url` e o caminho
  `/api/app` inteiro: `/api/appx` não é o App) pergunta à página por `evaluateJavascript` (a `PERGUNTA`, que devolve
  true só se `__gcCascaVoltar` existe e respondeu true; erro vira false) e só `"true"` consome; o resto, e toda página
  fora do App (o `erro.html`, o login da Microsoft), faz `moveTaskToBack(true)`. A tela nunca é fechada: volta-se ao
  app onde estava, sem recarregar. Uma pergunta por vez (o segundo toque enquanto a página pensa não fecha duas
  camadas), com 1,5 s de prazo: o renderer travado não segura o voltar, e a resposta que chega depois do prazo não decide
  nada.
- **As notificações do Nexus Field (1.1.0): o lado da casca, o contrato com o servidor e a ordem** (desenho
  `docs/superpowers/specs/2026-10-18-notificacoes-design.md`; plano `docs/superpowers/plans/2026-10-18-notificacoes.md`;
  a prévia é a seção Notificações de `docs/previas/telas.html` e o roteiro, a seção "As notificações" do
  `docs/prova-no-aparelho.md`). O técnico recebe o resumo das 7h, o das 13h e a emergência, mesmo com o app fechado. Quem
  MONTA e MANDA tudo é o servidor do App de Campo (outra sessão, a "App de campo"), pelo Firebase Cloud Messaging, que é
  grátis; a casca só mostra o que chega. Nada do servidor mora neste repositório.
  - **O contrato com o servidor** (tudo o que a casca assume; mudar um lado pede mudar o outro):
    - A mensagem do FCM é só de dados: `{tipo: 'resumo'|'emergencia', canal: 'resumo'|'emergencia'|'emergencia_silenciosa',
      titulo, texto, os?}`. O `Notificacoes.montar` a confere: tipo fora do contrato não aparece; o canal que não é do tipo
      é trocado (a emergência de canal errado vai ao silencioso e o resumo, ao do resumo, e o log diz o motivo sem conteúdo,
      porque descartar a emergência perderia o aviso); a emergência do canal alto que CHEGA fora do expediente vai ao
      silencioso (abaixo); título vazio vira "Nexus Field"; texto cortado em 240 caracteres; `os` só com 1 a 12 dígitos
      ASCII. A barra mostra a hora do ENVIO (o `getSentTime`, com `setShowWhen`), não a da chegada. **O título e o texto
      são do servidor**: a casca não acrescenta nada, e nada de nome de usina ou de pessoa pode ir pelo Firebase (a
      mensagem passa pelo Google). O log da casca diz só o canal e o motivo: nunca título, texto, OS nem token.
    - Os canais (os ids são o contrato e moram no `Notificacoes`; os nomes, no `strings.xml`): `resumo` ("Resumo do dia",
      importância padrão, som de notificação), `emergencia` ("Emergências", alta: aparece por cima, som de ALARME padrão do
      aparelho como toque de notificação, no volume das notificações, e vibração de três pulsos) e `emergencia_silenciosa`
      ("Emergências fora do expediente", baixa, sem som nem vibração). **Nenhum fura o Não perturbe nem o modo silencioso**
      (decisão do dono, 18/10/2026): nenhuma notificação leva categoria (a `CATEGORY_ALARM` passava pelo Não perturbe no
      modo prioridade, que libera os alarmes por padrão, e a `CATEGORY_REMINDER`, onde os lembretes estão liberados) e
      nenhum canal pede o `setBypassDnd`. Quem escolhe o canal é o servidor, pela hora do ENVIO: dia útil e expediente, das
      7h às 17h, o alto; fora disso, o silencioso. A casca só rebaixa ao silencioso a emergência que CHEGA fora do
      expediente (`Expediente.dentro`, em Brasília; feriado conta como dia útil): o FCM segura a mensagem por até 12 h, e a
      enviada às 16h50 de uma sexta a um celular sem sinal tocava às 19h40, em casa. No Android 7, sem canais, a própria
      notificação leva a prioridade, o alarme e a vibração do canal (`NotificacoesNoAndroid7Test`). O som, a vibração e a
      importância de um canal que já existe NÃO mudam (o `criar` seguinte só o renomeia): mudá-los pede um id de canal NOVO
      no Android, mas esse id não precisa ser o texto do contrato: um mapa no `Notificacoes` (o canal `emergencia` da
      mensagem indo ao canal `emergencia_2` do Android, com o `deleteNotificationChannel` do velho) muda o som sem o
      servidor mudar nada. Na Release, a 1.1.0 é a primeira a criar os canais; o aparelho de prova que rodou um APK de
      depuração deste ramo de antes do alarme tem as "Emergências" com o som padrão (só reinstalando, o que apaga o
      espelho).
    - As rotas, todas com o Bearer da página e no ar desde a v286 do App de Campo: `POST {MIDDLEWARE_URL}/push/registrar
      {token, aparelho:'android', app:'NexusField/<versionName>'}`, `POST /push/esquecer {token}`, `GET /me` (o papel
      `Admin`) e `POST /push/teste {canal}` (só Admin), no endereço do `MIDDLEWARE_URL` sem a barra do fim (como a página
      faz em toda chamada: com a barra, a casca pedia `…/api//push/…`, e o 404 parava o registro em silêncio). O contrato
      diário exige da página o que o `PUSH` lê (o `getTokenMW` sem abrir o login, a `async function sair` com o único
      logout da página e chamada pelo menu, a declaração do `MIDDLEWARE_URL` e o `#s-app`; ver o contrato, mais abaixo);
      o resto da conversa é do servidor.
    - Os ids das notificações: o resumo tem id fixo (o das 13h toma o lugar do das 7h, e toca de novo; o MESMO resumo de
      novo, com o mesmo título e texto ainda na barra, não toca: a entrega repetida do FCM), sai da barra no toque e, do
      Android 8 em diante, à meia-noite, em Brasília (`setTimeoutAfter`: o "Faltam 2 tarefas de hoje" de sexta não fica
      até segunda); a emergência tem id pela OS (`Notificacoes.idDaEmergencia`: a mesma OS substitui a anterior, sem tocar
      outra vez) e NÃO sai da barra no toque: só quando a página diz que abriu a OS (o `osAberta`, que a cancela) ou no
      Sair (que tira da barra tudo do app). O `PendingIntent` do toque abre a porta interna (ver "Abrir a OS pelo toque na
      emergência") e tem por código o id da notificação: com o mesmo código o Android acharia "igual" o que só muda nos
      extras, e a segunda emergência na barra abriria a OS da primeira.
  - **O lado da casca:**
    - `MensagensDoFirebase` (serviço NÃO exportado, no manifesto com o filtro `MESSAGING_EVENT`: exportado, qualquer app
      do aparelho mandaria uma "emergência") cria os canais, monta e mostra: a primeira mensagem pode chegar antes de o
      técnico abrir o app atualizado, e a notificação num canal que não existe não aparece. O
      `CanaisDeNotificacao.criar` roda também no `load()` do plugin, a cada abertura (até com a WebView antiga): os
      canais estão nos ajustes do Android desde a primeira abertura, e o nome segue o `strings.xml` da versão (criar de
      novo só renomeia; o que o técnico ajustou fica). Com a marca `semConta` (o Sair, abaixo) não mostra nada, e o log
      diz só que a mensagem veio depois do Sair. O `Notificacoes.mostrar` não mostra (e o log não diz "mostrada") sem a
      permissão, com as notificações do app desligadas ou com o canal desligado. O `onNewToken` grava a chave `token` do
      SharedPreferences `push` (o último token que o Firebase deu; o pedido da página também a atualiza) e manda o token
      à página viva (`CascaPlugin.tokenNovo`); a chave serve para COMPARAR na volta ao app (abaixo). Esse método está
      obsoleto no firebase-messaging 25.1 e segue chamado, porque o meta-data
      `firebase_messaging_installation_id_enabled` não está ligado (conferido no bytecode da 25.1.3).
    - A permissão (Android 13+): `MainActivity.devePedirNotificacao`. Instalação nova: o "Antes de começar" e o pedido,
      a notificação por último. Atualização da 1.0.0, com a câmera e a localização PRECISA já dadas: o diálogo "Avisos
      do Nexus Field" (não cancelável) e o pedido só da notificação; com a localização aproximada vem o "Antes de
      começar", que pede as três (o roteiro da prova da atualização parte da precisa). A marca `jaPediuNotificacao` (no
      arquivo `push`) só grava quando o técnico RESPONDE: negada, não se pede de novo (ligar é nos ajustes do Android),
      e o app morto com a tela do sistema aberta pergunta na abertura seguinte. Ninguém pode dar `clear()` no arquivo
      `push`.
    - O registro é da PÁGINA (seção 8 do `inicio.js`, `testes/push.test.mjs`), uma vez por token em cada carga, com o
      Bearer da conta de agora; o nativo só entrega o token do Firebase e nunca vê token da Microsoft. Caso que criou a
      regra: a primeira versão pulava o registro feito há menos de 7 dias, e o "já registrado" não sabia de QUEM (trocar de
      conta sem o Sair mandava os avisos ao e-mail anterior, e depois do Sair o aparelho ficava sem aviso por até 7 dias).
      O custo é uma chamada ao servidor do App de Campo por abertura, e nenhuma ao Fracttal. A conversa com o nativo:
      - página → nativo: `{t:'pushPedirToken'}` 5 s depois do load (e de novo quando o token veio null, abaixo);
        `{t:'pushRegistrado'}` quando o servidor respondeu 2xx (não depois do Sair); `{t:'pushSaiu'}` no Sair, com token
        ou sem; e o `pushAdmin`/`pushTeste` do atalho de teste.
      - nativo → página: `{t:'pushToken', token, versao, avisos}`, a resposta, que também chega SOZINHA pela ponte da
        página que pediu (`PushNaCasca`, na thread do espelho): no `onNewToken` com a tela viva (a primeira abertura sem
        sinal, o token renovado) e na volta ao app quando os avisos passaram a ligados ou quando o token guardado não é o
        que a página recebeu. `avisos` é a permissão (Android 13+), as notificações do app ligadas e o canal `emergencia`
        não bloqueado (o do resumo não conta). A página aceita um token DIFERENTE do atual e registra uma vez por token, e
        só com `avisos` true (ausente, do nativo antigo, vale true; qualquer outro valor, não): com as notificações
        desligadas o servidor contaria um aparelho que não mostra nada. Sem o `google-services.json` o `getInstance` lança
        e vai `token: null`. O `testes/canal.test.mjs` confere que todo tipo que o script manda o `Mensageiro` atende (o
        `pushSaiu`, o `pushRegistrado` e o `osAberta` caíam em "tipo desconhecido").
      - **"Logado" é a tela do App na frente (`#s-app.on`, o `PUSH.id.app`), não "o getTokenMW devolveu algo"**: o site
        só liga o `.on` no `entrarComIdentidade`, depois do `setActiveAccount` da conta escolhida. Caso: o aparelho
        dividido; A não saiu; B toca em Entrar e volta do login com 4G fraco: até a tela do App, o MSAL ainda tinha A como
        ativa, e o registro e o `/me` saíam com A (as emergências de A na mão de B, os atalhos de Admin de A para B). Antes
        dela, o registro, o `/me` e o teste dos avisos esperam (o `MutationObserver` na classe do `#s-app`; sem ele, uma
        consulta de 1 s por 3 minutos), além do `online` e da volta ao app. A página sem o `#s-app` nunca "loga": nada sai,
        e o contrato diário avisa.
      - Sem laço: o token null espera o próximo `online` ou a volta ao app para pedir de novo (a primeira abertura sem
        sinal: o SDK do Firebase só obtém o token quando a rede volta); a tela do App com o Bearer null (a entrada sem sinal,
        a sessão vencida), o próximo `online` ou a volta ao app; 404, a rota fora do ar, para nesta carga; sem rede, o
        próximo `online`. Entre tentativas (o pedido do token de novo, o registro que falhou), no mínimo 10 minutos; o
        `online` que chega na espera tenta quando ela acaba. Erro vira `{t:'erro', onde:'push'}` com um texto nosso (nunca
        o token, o Bearer nem o e-mail).
      - **O Sair**, embrulhado (o `sair` do site troca as telas na primeira linha e vai ao `logoutRedirect`): manda o
        `pushSaiu` (o nativo grava a marca `semConta`, com commit e sem `clear()`, e não mostra nada até o próximo
        `pushRegistrado`; tira da barra as notificações do app, esquece a OS do toque e apaga o token no Firebase, e o
        pedido de token que chega com o apagamento em curso espera ele terminar: é o que segura o Sair sem rede, com o
        servidor fora ou com a sessão vencida, quando o esquecer não chega ao servidor e o aparelho seguia com os avisos de
        quem saiu), o `pushAdmin:false` e apaga a OS pendente do toque (a da conta que saiu não abre para a que entra);
        depois pede ao servidor que esqueça o token com `keepalive: true` (o pedido sobrevive à
        navegação do logout) e roda o sair da página assim que o pedido sai, sem esperar a resposta (o teto de 3 s fica
        para o Bearer que não vem). O segundo toque com o primeiro em curso recebe a mesma Promise (o segundo
        `logoutRedirect` lançava `interaction_in_progress`, e a reserva da página apagava as contas enquanto o primeiro
        navegava). Nenhum registro começa depois do Sair nesta carga (o `online`, o fim da espera ou um token novo
        mandariam o Bearer de quem está saindo ao lado do esquecer); a carga seguinte registra.
    - O ícone pequeno é o `ic_notificacao.xml`: o G da Grid em vetor branco de 24 dp, sem fundo (o Android só usa o alfa e
      pinta com a cor da barra; o PNG do ícone do app viraria um quadrado). O traçado saiu do `docs/marca/grid-g-branco.png`
      (99,3% da área igual) e o desenho está explicado no próprio arquivo. O manifesto também o dá como ícone padrão do
      Firebase, e o canal do resumo como o padrão (uma mensagem com `notification` mostrada pelo próprio Firebase não cai no
      "Miscellaneous").
    - O toque na emergência e o atalho de teste dos avisos, abaixo: "Abrir a OS pelo toque na emergência" (a OS fica
      PENDENTE se o técnico está na tela de login, numa OS, numa ronda começada, num serviço da Zeladoria ou noutra
      camada) e "O atalho de teste dos avisos" (só Admin, no toque longo do ícone).
  - **O Firebase no build:** `firebase-bom:34.19.0` fixo e só o `firebase-messaging` (sem Analytics), para o APK de hoje e
    o de daqui a um mês saírem das mesmas bibliotecas. O `android/app/google-services.json` (do `applicationId`
    `br.com.gridco.nexusfield`) NÃO é segredo (só identifica o projeto; mandar aviso pede a chave de serviço, que mora só
    nas App Settings do servidor) e entra no repositório. Sem ele o plugin do `google-services` não é aplicado: o build de
    depuração e o de PR passam, o app roda, mas não recebe aviso; o atalho de teste aparece para o Admin assim mesmo (o
    `/me` não depende do token do Firebase), e o aviso rápido do teste conta só os outros aparelhos da conta. A Release
    FALHA sem ele (o passo "O Firebase do app está no repositório", logo depois de "O commit da tag está na main", antes
    de a chave sair do secret; o arquivo vazio também falha): um APK que nunca recebe aviso indo a ~50 aparelhos, sem erro
    em lugar nenhum. O arquivo que existe e está torto aplica o plugin e derruba o build.
  - **A chave de API do `google-services.json`, num repositório público.** O arquivo traz a chave de API do projeto do
    Firebase (o texto que começa por `AIza`). Ela não manda aviso, mas sem restrição serve a qualquer API que um dia for
    ligada no projeto. Antes do commit, no Google Cloud Console do projeto (APIs e serviços > Credenciais), na chave que o
    Firebase criou para o Android:
    1. Restrições do aplicativo: "Apps Android", com o pacote `br.com.gridco.nexusfield` duas vezes: com o SHA-1 do
       certificado da Release (o `apksigner verify --print-certs` de um APK já publicado, o da 1.0.0, mostra o SHA-1 sem
       pedir senha; é o mesmo certificado do `CASCA_CERT_SHA256`) e com o SHA-1 da chave de depuração de quem monta o
       APK de prova (o `./gradlew signingReport`, na pasta `android/`). Sem o da depuração, o APK de depuração com o
       Firebase não ganha token.
    2. Restrições de API: só a Firebase Installations API e a FCM Registration API (o registro do Firebase Cloud
       Messaging no aparelho; a Firebase Cloud Messaging API do envio é do servidor, pela chave de serviço, e não passa
       por esta chave). São as que a documentação do Firebase lista para o Cloud Messaging (lida em 19/10/2026).
    3. Salvar e, alguns minutos depois, conferir no aparelho de prova que o aviso ainda chega (o "Testar avisos" conta
       o aparelho): a restrição errada deixa o aparelho sem token, sem erro na tela.

    A varredura de segredos do GitHub reconhece o padrão `AIza` e pode abrir um alerta (e a proteção de push pode pedir
    confirmação no push do commit); o Google também pode mandar o aviso de chave exposta. Com este arquivo isso é
    esperado: libere o push de propósito e feche o alerta depois da restrição, sem tirar o arquivo do repositório (sem
    ele a Release falha).
  - **A ordem de dependência** (spec, seção 5), e por que a tag `v1.1.0` NÃO é da casca sozinha: 1) o dono cria o projeto
    do Firebase (grátis, com a conta Google da Grid), restringe a chave do `google-services.json` (acima) e o põe no
    repositório, e a chave de serviço vai às App Settings do servidor; 2) a casca 1.1.0 (este código), que chega ao
    aparelho de prova pelo aviso de atualização do próprio app (o que também prova a atualização); 3) o servidor, na
    sessão "App de campo" (as rotas, os dois timers e o envio na conferência do Fracttal; as rotas estão no ar desde a
    v286); 4) a prova pelo `docs/prova-no-aparelho.md` e, depois, o piloto. **A tag `v1.1.0` só sai depois de: os textos
    aprovados pelo dono na prévia, o `google-services.json` do dono no repositório (com a chave restrita) e as rotas do
    servidor no ar.**
    - **A Release final vai a TODO aparelho com a versão anterior** (a `releases/latest/` que o app lê a cada 6 h, e o
      link de primeira instalação), e a prova vem depois dela. Na 1.1.0, a 1.0.0 está só no aparelho de prova (decisão do
      dono, 18/10/2026), e a tag `v1.1.0` sai direto. Se outro aparelho tiver a versão anterior (o piloto, um técnico com
      o link), sai antes um pré-lançamento `vX.Y.Z-rc.N` (com o `novidades/X.Y.Z-rc.N.md`), que a `releases/latest/` não
      enxerga: o APK dele, da mesma chave, vai por arquivo ao aparelho de prova e instala por cima da versão anterior
      (sem perder o espelho), a prova se faz nele, e só então sai a tag final, com o versionCode maior, que o aparelho de
      prova recebe pelo próprio aviso de atualização. Caso que criou a regra: a revisão final das notificações viu que a
      tag final, posta antes da prova, levaria a 1.1.0 (e os canais, que não mudam depois) a quem tivesse a 1.0.0.
    - Os textos novos (os canais e o som, a vibração e a importância de cada um, os atalhos e o texto do atalho
      desligado, os avisos rápidos, o "Avisos do Nexus Field", o novo "Antes de começar" e as novidades da 1.1.0) estão
      PENDENTES de aprovação; quando ele aprovar, os cabeçalhos do `strings.xml` passam a dizer "aprovados", com a data.
- **Abrir a OS pelo toque na emergência** (notificações, 18/10/2026; `MainActivity.osDoToque`, `CascaPlugin.AberturaDaOS`,
  `OsDoToque` e a seção 9 do `inicio.js`; testes `CascaPluginTest`, `OsDoToqueTest`, `PushNaCascaTest`, `MainActivityTest`,
  `MainActivityNaTelaTest` e `testes/abrir-os.test.mjs`). O Intent do toque traz o extra `gc_os` (o número da OS, só na
  emergência com OS válida; ver `Notificacoes.mostrar`):
  - **A porta interna** (`MainActivity.PORTA_INTERNA`, a `activity-alias` `.PortaInterna` do manifesto, NÃO exportada e
    sem filtro): o `PendingIntent` da notificação e o atalho de teste abrem a tela por ela (o PendingIntent é do próprio
    app, e o launcher abre o atalho em nome do app), e os extras `gc_os` e `gc_teste` só valem no Intent cujo componente é
    ela (`MainActivity.extraDoToque`, o ajudante dos dois). A MainActivity exportada (o ícone, e qualquer app do aparelho)
    os ignora e consome. Caso que criou a regra: um app qualquer mandava à MainActivity uma OS que tomava o lugar da
    pendente da emergência de verdade (a última vence) e trazia o app para a frente, ou um teste que mandava dois pares
    de avisos a cada aparelho da conta. A alias herda o `singleTask` da MainActivity, e o Android entrega o toque com a
    tela aberta no `onNewIntent` dela.
  - **A `MainActivity` o lê no `onCreate` (só sem estado salvo) e no `onNewIntent`**, confere com `Notificacoes.osValida`
    (1 a 12 dígitos ASCII: o número vai a um script), consome o extra na leitura e ignora o Intent que o Android
    reentrega ao voltar pelos recentes (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`). O `BridgeActivity.load()` do Capacitor
    8.5.3 chama `onNewIntent(getIntent())` dentro do próprio `onCreate`, com ou sem estado salvo; por isso o `onNewIntent`
    daqui ignora a chamada que vem durante o `super.onCreate` (a marca `criando`). Sem isso, a tela recriada pelo
    renderer que morreu leria o toque de novo. O `MainActivityNaTelaTest` prova as três coisas na tela de verdade, e o
    `testes/abrir-os.test.mjs` confere a ligação no texto do Java.
  - **O `CascaPlugin.abrirOS(os)` só decide QUANDO** (`AberturaDaOS`, regra pura com página e agenda falsas no teste): a
    cada página do App que termina de carregar (`onPageLoaded` com `Voltar.eDoApp`) abre-se uma folga de 1,5 s, porque o
    fim do documento vem antes de o App montar a tela; passada a folga a página está "pronta". Pedido com a página pronta
    vai logo; sem ela, o último pedido fica guardado e vai ao fim da folga (`evaluateJavascript("window.__gcCascaAbrirOS&&
    window.__gcCascaAbrirOS('<os>')")`). Qualquer página que comece a carregar (`onPageStarted`: o login da Microsoft, o
    erro) tira o "pronta" e invalida a folga da carga velha: o login silencioso volta ao App em cerca de 1 s, e entregar
    ali seria entregar a uma tela que não montou. A OS tocada fica guardada no PROCESSO (`OsDoToque`, estática no plugin)
    e vai de novo a CADA carga do App, até a página dizer que a abriu (o `osAberta`, que a esquece e tira a emergência
    da barra) ou o dia acabar (em Brasília); a última tocada vence. Caso que criou a regra: o técnico preenchendo uma OS
    com fotos num aparelho de 4 GB toca na emergência, a página a deixa pendente, o renderer morre e a tela recriada
    (plugin e sessionStorage novos) não sabia mais da OS, que já tinha saído da barra no toque. O número nunca vai ao log:
    da resposta da página sai só "aberta", "pendente" ou "não aberta" (`AberturaDaOS.resultado`); quem diz que abriu é o
    `osAberta`.
  - **No script, o toque nunca interrompe o técnico** (regra do dono, 18/10/2026: "Continua no preenchimento de OS ou
    ronda, a da notificação fica pendente"; o caso: a busca posta por baixo da OS aberta, que ele não via).
    `window.__gcCascaAbrirOS(os)` (não enumerável) confere `^[0-9]{1,12}$` de novo e vê se ele está ocupado:
    - **fora da tela do App** (`#s-app` sem o `.on`, a mesma porta do registro): a tela de login. Caso que criou a regra:
      toda partida a frio cai nela (online não há entrada automática), que é o caso de quem recebe a notificação com o app
      fechado, e o Entrar vai ao `loginRedirect`, que recarrega a página: a busca posta por trás do login se perdia. A
      pendência atravessa o redirecionamento (o sessionStorage é da aba) e abre com a tela do App na frente;
    - com uma camada aberta, pela `camadaDeCima` da seção 7 (a pilha do voltar, sem fechar nada), mesmo a que o voltar não
      saberia fechar, e o **serviço aberto da Zeladoria** (o `.zl-volta`, que o site só desenha dentro de um serviço: é
      preenchimento, como a OS; a lista dos serviços não ocupa);
    - com a **Ronda numa etapa (o `.rd-voltar` na `#tela-ronda.on`) e a ronda começada pelo critério do próprio site**: o
      `comecou` do `rdVoltar` (v284), que a casca repete lendo o `let RONDA` de topo pelo nome (como o `sheetTask`): a
      usina escolhida, uma resposta ou um disperso, e não finalizada no servidor. Caso que criou a regra: o `renderRonda`
      desenha o `.rd-voltar` em TODA etapa, até na Chegada antes de começar, e a aba só aberta segurava a OS. O `RONDA`
      nulo é a ronda que nem abriu nesta carga (livre); sem como ler o `RONDA` (o site trocou o nome), vale a regra de
      antes, a Ronda numa etapa segura, pelo lado de não interromper. O contrato prende o nome e o critério.
    Outra aba sem nada aberto não ocupa.
    - Livre: `setTab('tarefas')` se ele está noutra aba, o número no `#busca-os` e `setBusca(os)`, nessa ordem (a do
      `phTrazer` do site); a busca local e, sem achar, ao vivo no Fracttal é a do próprio site. Avisa o nativo
      (`{t:'osAberta', os}`, que ele espera para cancelar a notificação e parar de repetir a OS a cada carga) e devolve
      `'aberta'`. Toda entrega é um pedido novo, mesmo a de uma OS já mostrada nesta carga: livre, ela volta à busca;
      ocupado, fica pendente, e o `osAberta` só sai com a OS mostrada. Caso que criou a regra: a página guardava as OS
      abertas na carga e devolvia `'aberta'` sem fazer nada; quem tocava de novo numa OS já vista (ou a prova, com o
      Testar avisos, que manda sempre a mesma OS) não via nada, e o `osAberta` tirava da barra uma emergência que ninguém
      viu. A busca repetida não pergunta ao Fracttal de novo (o site guarda a resposta de cada número na carga).
    - Ocupado: nada é fechado, nenhuma aba trocada, nenhuma tela nova (a notificação já foi o aviso). A OS fica no
      sessionStorage, `gc_casca_os_pendente` = `{os, dia}`, e devolve `'pendente'`. Uma conferência de 2 s, que só existe
      enquanto houver pendência, a abre como acima (com o `osAberta`) quando ele ficar livre. A pendência vale só no dia
      (a de outro dia, ou torta, sai na leitura), atravessa as recargas da mesma partida (a carga nova retoma a
      conferência sozinha) e a OS nova a substitui: a última emergência vence. Sem onde guardar (o sessionStorage cheio
      ou negado), ela fica numa variável da página, até a recarga, e a gravação que falha apaga a velha antes (a última
      vence assim mesmo). O Sair da seção 8 apaga a pendência (`ctx.esquecerOsPendente`).
    - `false`, sem lançar e sem avisar o nativo, se faltar o campo ou o `setBusca` (o nativo entrega de novo na carga
      seguinte). O `setBusca` que lança vira `{t:'erro', onde:'abrirOS'}`; na conferência, a pendente que lança sai da
      pendência (o erro não se repete a cada 2 s).
    - Os nomes são o `ABRIR_OS` (o `setTab`, a aba e a Ronda numa etapa vêm do `VOLTAR`, e o `#s-app` do `PUSH`, por
      referência), e o contrato diário os exige com o rótulo `abrir-os` (função `setBusca` e `setTab`, ids `busca-os`,
      `tela-ronda` e `s-app`, classes `on` e `rd-voltar`, aba `tarefas`, o `let RONDA` de topo e o `rdVoltar` com
      `RONDA.usina`, `RONDA.respostas`, `RONDA.dispersos` e `RONDA.finalizadaNoServidor`); os que são também do voltar ou
      do registro faltam em cada lista.
  - **O que isto não faz** (conhecido, para a prova no aparelho): a pendência da página e a OS guardada no nativo não
    sobrevivem ao app fechado (o sessionStorage é da partida, o `OsDoToque` é do processo): quem segura a emergência aí é
    a notificação, que fica na barra até a OS abrir, e um toque novo refaz tudo. O toque traz a tela para a frente e o
    `singleTask` fecha o que estiver por cima dela no Android (o app de câmera da reserva, a Custom Tab do Fracttal, o
    instalador da atualização): a foto da reserva se tira de novo (o roteiro tem o passo). O `setTab` que não apaga a
    busca (o toque com outra aba aberta) e a volta do login que abre a OS pendente (o toque com o app fechado) estão no
    roteiro e ainda sem prova no aparelho; a camada que a página abrir fora da lista do `VOLTAR` o toque trataria como
    livre, e só uma versão nova do site a traz.
- **O atalho de teste dos avisos, só para administrador** (18/10/2026; `AtalhosDeTeste`,
  `CascaPlugin.TesteDosAvisos`, `CascaPlugin.UmTestePorVez`, `MainActivity.testeDoAtalho` e a seção 8 do `inicio.js`;
  testes `AtalhosDeTesteTest`, `CascaPluginTest`, `MensageiroTest`, `MainActivityTest` e `testes/atalho-teste.test.mjs`).
  O dono quis testar as notificações no celular por um botão que só quem é Admin vê, e o botão é um atalho do ícone
  (toque longo), não um botão na página: a casca não desenha nada no App de Campo, e o técnico nunca o vê.
  - **Quem decide é a página, uma vez por carga:** 5 s depois do load, com a tela do App na frente (a mesma porta do
    registro: antes dela o Bearer seria o da conta anterior) e sem depender do token do Firebase (o teste manda aos
    aparelhos da conta, e o aparelho sem Firebase não pode deixar o Admin sem o atalho), o `GET {MIDDLEWARE_URL}/me` com
    o Bearer da página; ao nativo vai só `{t:'pushAdmin', admin}` (nada do e-mail nem do nome). True (papel `Admin`)
    publica os dois atalhos dinâmicos (`teste_avisos`, canal `emergencia`; `teste_fora_expediente`, canal
    `emergencia_silenciosa`); qualquer outro papel, erro, 401, a mensagem torta, a página sem o `getTokenMW` ou o
    `MIDDLEWARE_URL`, o `getTokenMW` que lança, o Sair e o 403 do teste os tiram. O Bearer null (a sessão vencida) espera
    o próximo `online` ou a volta ao app, e os atalhos ficam como estavam até lá.
  - **Publicar e tirar:** publicar é pelo `pushDynamicShortcut`, um a um (com o rank da ordem do menu), e nunca pelo
    `setDynamicShortcuts`, que troca TODOS os dinâmicos do app pelos da lista (um atalho de outra origem sumiria a cada
    carga do Admin); o push também conta o uso, como o `reportShortcutUsed` do toque. Tirar é o `removeDynamicShortcuts`
    só dos dois e o `disableShortcuts` deles, com o texto `atalho_teste_desligado` ("Só para administrador"): o atalho
    que o administrador FIXOU na tela inicial não sai com o remove, e tocado por outra conta mandava o pedido ao servidor;
    desligado, o launcher mostra o texto e nem abre o app. O Admin o religa (`enableShortcuts`) antes de publicar. O Sair
    também desliga (ele manda `admin:false`): o "Entre no Nexus Field para testar" fica para o toque com a tela de login
    na frente.
  - **O toque** abre a tela pela porta interna com o extra `gc_teste` (ação VIEW, sem CLEAR_TASK: com o app aberto a
    página não recarrega), lido como o `gc_os` (consumido, só os dois canais, só pela porta interna, ignorado o Intent
    dos recentes). O plugin o entrega como a OS do toque (as duas usam a `EntregaNaPagina`: a folga de 1,5 s depois da
    carga do App, o último pedido guardado) e segura o toque aceito há menos de 10 s (dois toques mandariam dois pares de
    avisos a cada aparelho): a contagem é uma por PROCESSO (`UmTestePorVez`, estática no plugin, como o teto das
    recriações), porque a tela recriada pelo renderer que morreu tem plugin novo, e o toque recusado ganha o toast dele
    (`casca_teste_ja_pedido`; o de falha seria falso, o primeiro teste já foi pedido). O pedido guardado vale 60 s: a
    página que não fica pronta nesse tempo (o login que demora, sem sinal) não manda o teste minutos depois, de surpresa,
    e ele sai com o toast de falha; o pedido que a página aceitou e não respondeu em 30 s (ela responde em 20 s; o caso é o
    Entrar da tela de login, que navega antes da resposta) também. A página chama `POST {MIDDLEWARE_URL}/push/teste
    {canal}` com o Bearer dela e devolve `{t:'pushTeste', ok, status, aparelhos}` em no máximo 20 s; na tela de login,
    espera a tela do App dentro desse prazo (o pedido entregue na volta do login), e sem ela devolve `status: 'sem
    login'`. A contagem só vai como inteiro de 0 ao teto do nativo (`APARELHOS_NO_MAXIMO`, o mesmo número no `inicio.js`
    e no `AtalhosDeTeste`, que o `testes/atalho-teste.test.mjs` compara); o 200 sem contagem que se leia vai `{ok:true,
    status:200, aparelhos:null}` e dá o toast de enviado sem o número (`casca_teste_enviado_sem_contagem`): o de falha
    convidaria a repetir. Quem decide o texto do toast é o nativo (`casca_teste_*`). A página que nem aceitou o pedido dá
    o toast de falha.
  - Os textos (rótulos `atalho_teste_*`, o do atalho desligado e os toasts) estão PENDENTES de aprovação na prévia
    (`#t-atalhos`). Nenhum rótulo promete "sem som" (o teste manda também um resumo, que toca no canal dele), e os curtos
    têm no máximo 10 caracteres (o launcher corta o do atalho fixado; o `AtalhosDeTesteTest` confere). O lint dá o aviso
    `PluralsCandidate` no "aparelho(s)", e ele fica: no pt-BR o Android põe o 0 no singular do `plurals` ("0 aparelho"),
    e o 0 é o caso que a prova procura (o aparelho que não se registrou).
- **A atualização da casca** (`Atualizador`, `VersaoRemota`; o `CascaPlugin` a chama no `load()` e no `handleOnResume()`):
  - Lê o `versao.json` de `releases/latest/download/` (não conta no limite da API do GitHub), com as chaves
    `versionCode` (inteiro), `versionName`, `apk`, `sha256` (64 dígitos hex) e `novidades` (lista de textos). O
    `ferramentas/versao-json.mjs` da Release tem de gerar exatamente isso. Falta de campo, tipo errado, SHA que não seja
    hex de 64 ou `apk` fora de `https://github.com/Grid-Co-CODE/nexus-field/releases/download/<pedaços simples>` (sem
    `..`, `?`, `%`) é recusado: o SHA que o próprio JSON traz não protegeria de um link para outro lugar.
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
  só, autocontida, com o ícone e o nome (no topo), a abertura, os diálogos do `strings.xml` (o aviso de versão nova com
  as novidades de verdade da próxima Release), os toasts, o `www/erro.html` num iframe, a camada do Fracttal e, na seção
  Notificações, as quatro notificações de exemplo na barra do Android (os quadros `#t-notif-*`: o resumo das 7h e o das
  13h, a emergência que aparece por cima e a que chega em silêncio), os nomes dos canais (o quadro `#t-canais` dos
  ajustes do Android) e os atalhos de teste do ícone (o quadro `#t-atalhos`), cada tela numa moldura de 360 px, para o
  dono da casca aprovar texto e visual antes de ir ao campo. A lista "O que aprovar", no topo, pede também o que não é
  texto e não muda depois de a versão chegar aos aparelhos: o som, a vibração e a importância de cada canal. **O ícone,
  o nome e os textos estão PENDENTES de aprovação do dono da casca** (a página é publicada como Artifact privado do
  claude.ai e ele aprova por lá); quando ele aprovar, esta frase e os cabeçalhos do `strings.xml` passam a dizer
  "aprovados", com a data. O texto vive no arquivo-fonte, não na prévia: pedido de mudança se faz no `strings.xml`, no
  `erro.html`, no `inicio.js` ou no `novidades/`, e a prévia acompanha.
  - **Ícone e nome:** o PNG do APK (o `ic_launcher_foreground` de xxxhdpi, byte a byte) embutido UMA vez como `data:` URI
    num `<image id="g-nexus-field">`, e cada cópia é um `<use>` (oito ícones e a abertura sem repetir 9 KB de base64).
    Cada ícone é um SVG com `viewBox="18 18 72 72"` (os 72 dp que o launcher mostra do quadro de 108), o fundo pelo token
    `--icone-fundo` (igual ao `ic_launcher_background`) e a máscara por `clip-path`: `circle(50%)` e a squircle do AOSP
    em pontos. Nos papéis de parede claro e escuro, em 48 px e 144 px, com o rótulo do `title_activity_main`. A abertura
    é o G num quadro de 192 px, como o `drawable/splash.xml`. Depois de rodar o `gerar-icones.py`, troque o `data:` (o
    teste compara os bytes e diz).
  - O `testes/previas.test.mjs` falha se:
    - um diálogo da prévia não for um grupo do `strings.xml` (título, texto e botões no conjunto e na ordem do
      aplicativo: negativo à esquerda, positivo à direita, como a `MainActivity`, o `CascaPlugin` e o `Atualizador` os
      montam; o teste lê os `AlertDialog.Builder` do Java), ou os toasts da prévia não forem, no conjunto, os
      `Toast.makeText(..., R.string.x, ...)` do Java (lidos do código);
    - uma string do `strings.xml` ficar sem quadro (só `package_name` e `custom_url_scheme` ficam fora, porque o técnico
      não os lê; caso de 18/10/2026: os nomes dos canais trocaram o `casca_` por outro prefixo e nenhum teste exigiu o
      quadro);
    - o aviso de versão nova (`#t-nova-com`) não mostrar as novidades da versão mais nova do `novidades/` (só as finais,
      `X.Y.Z`), tiradas do arquivo como a Release as tira (o `ferramentas/versao-json.mjs`) e montadas como o
      `Atualizador` monta, ou a lista "O que aprovar" não as pedir. Um `novidades/` de versão nova pede o quadro de novo,
      e o `npm test` da tag não passa sem ele. Caso que criou a regra: as novidades da 1.1.0 iam aos técnicos sem nunca
      terem aparecido na prévia, que mostrava linhas inventadas;
    - os nomes no quadro dos canais não forem os `notificacao_canal_*` que o `CanaisDeNotificacao` dá a cada canal, ou os
      rótulos do quadro dos atalhos não forem os `atalho_teste_*` que o `AtalhosDeTeste` dá a cada atalho (o longo no
      menu, o curto e o texto do atalho desligado na legenda);
    - a lista "O que aprovar" não citar cada texto do teste dos avisos (os `atalho_*` e os `casca_teste_*`; o do número,
      com um número de exemplo), ou o item do som dos canais (`#ok-canais-som`) não disser, canal por canal, o que o
      `CanaisDeNotificacao` cria: a importância (por cima da tela só a alta), o som (o alarme padrão do aparelho, que o
      `USAGE_NOTIFICATION_EVENT` toca no volume das notificações e que não toca no silencioso nem no Não perturbe; o
      padrão de notificação; nenhum), a vibração (os pulsos do padrão, por extenso) e que mudar depois pede canal novo;
    - o `casca_avisos_texto` ou a linha da emergência nas novidades da 1.1.0 prometerem a emergência "na hora" (a spec
      garante em até 10 minutos, e sem som fora do expediente);
    - uma notificação de exemplo não estiver no canal que a legenda diz, com a importância que o `CanaisDeNotificacao` dá
      e uma OS que o `Notificacoes` aceitaria; a legenda e a lista não descreverem o toque como "põe o número da OS na
      busca da aba Tarefas" (e a emergência que fica na barra até a OS ser mostrada); ou o G da barra não for o
      `pathData` do `ic_notificacao.xml` (byte a byte) em branco;
    - o roteiro da prova das notificações citar em negrito um texto que o aplicativo não tem, deixar de cobrir um atalho,
      canal ou toast, tiver um item fora de caixa de seleção (ou uma subseção sem caixa), deixar de provar um dos casos
      da lista `CASOS` do teste (cada um com o porquê) ou sair da ordem da atualização (o aviso da versão nova, os
      Avisos, negar, a reabertura sem pergunta, o teste do Admin sem aviso nenhum, os ajustes); ou se o roteiro tiver
      espaço para preencher quem é (o usuário de teste, uma conta, o e-mail, a OS), que vai numa cópia fora do git;
    - o `srcdoc` do erro não for o `erro.html` inteiro (copie o arquivo escapando `&` como `&amp;` e `"` como `&quot;`,
      nessa ordem) ou a camada do Fracttal não for igual à que o `inicio.js` monta (o estilo inline é o do aparelho; a
      moldura tem `transform` para a `position:fixed` da camada cobrir só o celular). A única diferença aceita é o
      `aria-modal="false"` da prévia: numa página estática um `aria-modal` ativo esconderia o resto da página da
      acessibilidade.
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
  - **As notificações de exemplo** têm o título e o texto do SERVIDOR (não estão no `strings.xml`): são os exemplos do
    servidor no ar (v286, lidos em 18/10/2026: "Bom dia!", "Boa tarde!", "Emergência"), com a OS fictícia 12345, e a
    legenda de cada quadro e a introdução da seção dizem de onde vêm. Se o servidor mudar o texto, a prévia e a tabela
    `NOTIFICACOES` do `testes/previas.test.mjs` mudam juntas; a casca não muda. O G da barra é o `pathData` do
    `ic_notificacao.xml` copiado UMA vez num `<path id="g-notificacao">` (as cópias são `<use>`; a cor sai da folha: o
    `--barra-icone`, branco, na barra de cima, e um tom do tema do aparelho no cartão, como o Android pinta o ícone
    pequeno). A barra de cima é sempre escura, para o G branco aparecer; os cartões seguem o tema do aparelho (o botão
    Claro/Escuro da página). O que o aparelho decide (a gaveta, o cartão, o "agora") é aproximação.
  - O desenho dos diálogos (AppCompat DayNight: fundo branco ou `#424242`, canto de 2 dp, botão verde-azulado) é
    aproximação: o que se aprova é texto, botão e quando cada aviso aparece.
- **A Release e o CI** (`.github/workflows/montar.yml` e `contrato.yml`; ainda sem repositório no GitHub: a primeira
  execução real é a da Task 10). O Actions não roda daqui, então o `testes/workflows.test.mjs` lê os dois arquivos com um
  parser de YAML (o pacote `yaml`, dependência de desenvolvimento) e confere as regras abaixo na estrutura (jobs, passos,
  o `env` de cada passo), e RODA os scripts dos passos que decidem a Release num bash com `curl`, `gh` e `apksigner`
  falsos (o commit na main num repositório git de verdade, a versão da tag, o versionCode, o certificado, a publicação e
  o agendamento). A pasta tem exatamente os dois arquivos: um terceiro escaparia das regras.
  - **Montagem (`montar`):** checkout com o histórico inteiro (`fetch-depth: 0`, para a varredura de nomes achar os
    autores; ver "Regras que não mudam"), Node `22.x` (o jsdom 30 pede `^22.22.2`; o `package.json` declara `engines`),
    `npm ci` e `npm test`, depois Java 21 (Temurin), `npx cap sync android` e
    `./gradlew testDebugUnitTest assembleDebug lint`, e o APK de depuração sobe como artifact. Neste job e no contrato as
    actions vão por versão maior (`actions/checkout@v7`, `setup-node@v7`, `setup-java@v6`, `upload-artifact@v7`,
    `gradle/actions/setup-gradle@v6`): não há segredo nem escrita em conteúdo. O `setup-gradle` usa
    `cache-provider: basic` (o `enhanced`, padrão, é um serviço comercial; regra de "sem serviço pago").
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
    0. **o repositório é o `Grid-Co-CODE/nexus-field`** (18/10/2026; o primeiro passo do job, antes até do checkout, e
       compara o `GITHUB_REPOSITORY` do runner com o nome inteiro, na caixa certa): o app lê o `versao.json` de
       `releases/latest/download/` DESTE repositório e só aceita o APK das Releases dele. Uma Release que saísse de um fork,
       do nome antigo ou de um repositório criado com outro nome seria publicada, assinada e vista por ninguém, em silêncio.
       A mensagem diz que o app só enxerga esse repositório. O `testes/versao-json.test.mjs` confere o nome no
       `Atualizador.URL_VERSAO`, no `VersaoRemota` e no gerador do `versao.json`, e o `testes/workflows.test.mjs` põe este
       passo frente a frente com o `URL_VERSAO` e o roda com repositórios parecidos (outra caixa, sufixo, prefixo, vazio);
    1. **o commit da tag está na `main`** (`fetch-depth: 0` e `git merge-base --is-ancestor`): a tag posta por engano num
       ramo de worktree publicaria código que nunca passou pela `main`;
    2. **o `android/app/google-services.json` está no repositório** (não vazio; o passo "O Firebase do app está no
       repositório", logo depois do checkout e da conferência da `main`): o arquivo é o projeto do Firebase das
       notificações, e sem ele o APK sai sem Firebase, sem erro nenhum: iria aos ~50 aparelhos sem nunca receber aviso. O
       job `montar` (o PR) passa sem o arquivo de propósito. Ver "As notificações do Nexus Field";
    3. **a versão da tag** pelo `node ferramentas/versao-json.mjs tipo-da-versao`: o semver mora só no `PADRAO_VERSAO` de
       lá (o mesmo do `versionName`), sem regex no bash; tem de existir `novidades/<versão>.md` (as linhas `- ` viram as
       novidades do aviso e o arquivo inteiro é o texto da Release). Sai `final` ou `pre-lancamento` (no
       `TIPO_DA_VERSAO`): `1.1.0-rc.1` vira pré-lançamento, que a `releases/latest/` do app não enxerga; o hífen do
       metadado de build (`1.0.0+ci-7`) não conta (o `case *-*` antigo soltava essa versão final como pré-lançamento);
    4. **o versionCode cresce**: o `versao.json` da `releases/latest/download/` é lido e o `conferir-crescimento` exige o
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
    "Versão X."). Os subcomandos `tipo-da-versao` e `conferir-crescimento` são os dos passos 3 e 4 acima.
  - **O contrato com o site (`testes/contrato.mjs`):** um GET do `/api/app`, só leitura. Importa do `www/casca/inicio.js`
    a `CLASSES`, o `classificar`, o `VOLTAR`, o `PUSH` e o `ABRIR_OS` (as listas existem num lugar só). Falha, dizendo o
    NOME (nunca um trecho do HTML), se:
    - **sumir** do site uma chave exata (entre aspas) ou um prefixo da `CLASSES`, o banco `gc_campo_fotos` com a store
      `fotos` por perto, a `conectarFracttal` com o `window.open('','_blank')`, o `loginRedirect`, o `'/api/app'`, o
      `msal-browser` na 2.38.1 (no mesmo endereço), ou um id, classe, função de topo, o `let sheetTask` ou o
      `setTab('tarefas')` do `VOLTAR` (nome trocado no site só faz o voltar daquela camada minimizar o app); ou o que as
      notificações usam: a função `getTokenMW`, a `async function sair`, a declaração do `MIDDLEWARE_URL` e o `#s-app` (o
      `PUSH`: sem elas o aparelho não se registra, sem erro na tela) e a `setBusca`, o `setTab`, o `#busca-os`, o `let
      RONDA`, o `rdVoltar` com o critério da ronda começada (`RONDA.usina`, `.respostas`, `.dispersos` e
      `.finalizadaNoServidor`) e o resto do `ABRIR_OS` (sem eles o toque na emergência não abre a OS, ou abre por cima de
      uma ronda começada); o rótulo de cada um sai na mensagem (`push:`, `abrir-os:`). **Toda declaração que a casca lê da
      página tem de estar no topo de um script clássico**: a função e o `let`/`const`/`var` na coluna 0 (a mesma regra
      que delimita o corpo das funções) de um `<script>` sem `type` ou com um `type` de JavaScript; a atribuição
      `window.nome = ...` vale em qualquer script. Caso que criou a regra: o contrato aceitava a declaração dentro de uma
      função, de um bloco ou de um `<script type="module">`, que não vira propriedade da janela nem entra no ambiente
      global, e a casca ficaria sem o endereço do servidor em todo aparelho com o contrato verde;
    - o Sair deixar de passar pelo embrulho (`push: logout fora do sair`, `push: chamada do sair`): todo `logoutRedirect(`
      ou `logoutPopup(` da página (fora dos comentários) mora no corpo do `sair`, e alguém chama o `sair(` fora da
      declaração (o `onclick` do menu; `window.sair(` vale, `menu.sair(` ou `sairDaConta(` não). Caso: um "Trocar de
      conta" com o próprio logout, ao lado do `sair` antigo, deixaria o aparelho com a conta que saiu;
    - o `getTokenMW` abrir o login (`push: getTokenMW abre o login`): `loginRedirect`, `acquireTokenRedirect`,
      `loginPopup` ou `acquireTokenPopup` no corpo dele. A casca o chama sem o técnico pedir (5 s depois do load, no
      `online`, na volta ao app, no Sair e no atalho), e o login interativo levaria a página para a Microsoft no meio do
      preenchimento;
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
    do MSAL, store, `window.open`, prefixo, chave nova, versão do banco, keyPath, um id ou função do voltar) é pega. Em
    19/10/2026, com as regras do Sair, do `getTokenMW`, do topo de script clássico, do `#s-app` e da ronda: passa no site
    vivo (v284), e cada uma dessas peças mudada no HTML real (o id, um logout noutra função, o menu chamando outra
    função, um `acquireTokenRedirect` no `getTokenMW`, o `MIDDLEWARE_URL` fora da coluna 0, o `RONDA` renomeado, um campo
    a menos no critério) é pega com o nome certo. Nota
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
    conseguir listar as abertas nada é criado. O job fica vermelho quando o contrato falha. O corpo da issue de quebra
    diz onde conferir: a `CLASSES`, a `IGNORAR`, o `VOLTAR`, o `PUSH` (os itens `push:`), o `ABRIR_OS` (os itens
    `abrir-os:`) e a janela do Fracttal (o `testes/issue-do-contrato.test.mjs` confere).
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
