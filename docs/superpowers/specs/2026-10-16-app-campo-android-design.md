# App de Campo no Android: desenho

16/10/2026. Responsável: Levi Maia. Contexto de partida: `App_Campo/DOC/06_APK.md` (SharePoint), que traz as peças,
as rotas, o login e as regras do App de Campo. Leia antes.

## 1. Intenção

O App de Campo é um PWA usado por ~50 técnicos de O&M em Android simples (moto g35, 4 GB), quase sempre sem sinal na
usina. O Levi quer um **app Android instalado**, no padrão dos apps de campo do mercado: uma casca nativa com a
interface web dentro e o trabalho pendente guardado pelo próprio app, não só pelo navegador. Os grandes fazem assim:
o Dynamics 365 Field Service roda a aplicação web dentro de uma WebView, e o Maximo Mobile é uma casca que baixa as
telas web do servidor.

O defeito que mais pesa no campo é o "sumiu": o dado estava no aparelho e o caminho de volta falhou. Na WebView, o
`navigator.storage.persist()` é sempre negado. Por isso o localStorage e o IndexedDB ficam como "melhor esforço", e
só um armazenamento nativo do app garante a fila e as fotos.

## 2. Objetivos, não-objetivos e anti-objetivos

**Objetivos**
- Um APK que abre o App de Campo de hoje, com ícone e tela cheia, e que funciona sem sinal depois da primeira
  abertura.
- A fila, os rascunhos e as fotos do dia **espelhados num armazenamento nativo do app**. Se a WebView perder o que
  guarda, o app devolve tudo antes de a página rodar.
- Câmera, GPS, login Microsoft e conta do Fracttal funcionando como no PWA.
- Distribuição **fora da Play Store**, com atualização da casca oferecida pelo próprio app.
- Montagem pelo GitHub Actions, sem custo.

**Não-objetivos (ficam para a fase 2, seção 12)**
- Login nativo com o Authenticator (sessão de 90 dias).
- Envio com o app fechado.
- App de iPhone.
- O painel de gestão, que continua só no computador.

**Anti-objetivos (é fracasso mesmo que funcione)**
- Qualquer mudança no site atual: `campo.html`, `sw.js`, `function_app.py`, App Settings, CORS ou rotas.
- Uma cópia congelada do `campo.html` dentro do APK. O servidor libera regras pela versão (`X-App-Versao`), e o App
  muda várias vezes por dia.
- A casca mandar sozinha qualquer coisa da fila. Quem envia, ordena, espera o limite do Fracttal e marca "enviado" é
  sempre a página. Envio em dobro no Fracttal é pior que atraso.
- Pagar por serviço, licença ou loja, ou criar passo manual recorrente ("solução, não mais problemas").
- Segredo no repositório ou no APK.

## 3. Restrições

- **O site atual não muda.** Toda adaptação fica do lado do app: configuração do Capacitor, plugin nativo próprio e um
  script injetado antes da página.
- O servidor só aceita CORS da própria origem. Em 16/10 um preflight de `https://localhost` voltou sem
  `Access-Control-Allow-Origin`. O único redirect URI do Entra é a URL do App.
- Não há conta Google paga, Intune nem Play. O APK é baixado e instalado por fora.
- A chave de assinatura é segredo: nunca é impressa e nunca vai para o SharePoint.
- Repositório da Grid-Co-CODE (plano Free). Java 21 e Android SDK (API 36) já estão instalados nesta máquina, na
  pasta do usuário. Não há emulador: a prova é num celular de verdade.
- Tudo em pt-BR, sem emoji na interface, e nenhum nome de colega em arquivo versionado.

## 4. O que o Levi decidiu

- **Capacitor**, não TWA. O TWA só muda a embalagem, e a fila continuaria no Chrome.
- **Fora da Play Store**: o APK é baixado e instalado por fora.
- **Em paralelo e sem mexer no site atual.** O PWA segue no ar como está.
- **Fotos: só as do dia.** O histórico de fotos do PWA não vem para o app. O espelho (6.4) guarda o que a página
  guarda, inclusive rascunho de dias anteriores ainda pendente. Foto pendente nunca some por ser velha: quem poda é a
  própria página (o rascunho de fechamento, por exemplo, vale 7 dias).
- **Caminho grátis e sem dor de cabeça.** Prazo medido no código, não chutado.

## 5. A forma: o App ao vivo dentro da casca

A casca abre a **URL ao vivo** do App (`server.url`), na mesma origem de hoje. Nessa forma:
- o redirect URI já registrado continua valendo;
- não existe CORS;
- o service worker de hoje guarda o App para abrir sem sinal;
- cada versão nova do `campo.html` chega na hora, sem loja.

**Por que não empacotar o HTML (`https://localhost`).** Sem mexer no servidor, essa forma morre no CORS. Contornar
exigiria um proxy HTTP nativo para cerca de 40 rotas. Esse proxy não repassa o `AbortSignal`, leva até 15 MB em base64
pela ponte e perde o ETag/304. Também seria preciso registrar um redirect novo no Entra. Os caminhos relativos
(`/api/dados`, `/api/msal`, `/api/sw`) receberiam o `index.html`.

**O custo honesto.** A doc do Capacitor marca `server.url` e `allowNavigation` como "not intended for use in
production". Eles nasceram para o live reload, mas não há trava no código. Aceitamos isso porque é o único caminho
sem mexer no site. Como contrapartida:
- a versão do Capacitor fica fixa (8.5.x);
- há teste de fumaça e um teste de contrato com o site (seção 10);
- a superfície nativa exposta à página é mínima.

## 6. As peças da casca

### 6.1 Configuração do Capacitor
- `server.url` = **só a origem** do Function App, sem caminho e sem barra no fim; `server.appStartPath` = `/api/app`. Com
  caminho no `server.url`, a regra de origem fica inválida. A ponte então cai no `addJavascriptInterface`, que fica
  exposto a todos os frames.
- `server.allowNavigation` = só os hosts de login da Microsoft que o tenant percorre. **O Fracttal fica de fora.** Cada
  host da lista também recebe a ponte, então a lista é mínima. A lista exata sai da prova no aparelho: a versão de teste
  registra as navegações bloqueadas.
- `server.errorPath` = uma página local: "Abra o App uma vez com internet" e um botão "Tentar de novo". Só aparece na
  primeira abertura sem sinal.
- CapacitorHttp **desligado** (é o padrão). Ligado, ele tira o cabeçalho `Origin` do MSAL, e o Entra recusa o token.
- `android.appendUserAgent` = `GridcoCampoApp/<versão da casca>`. Assim os logs do servidor distinguem o app do
  navegador, sem mudar o site.
- Capacitor fixo em 8.5.x.

### 6.2 Plugin nativo próprio (Android)
- Injeta o **script de início** (6.3) com `WebViewCompat.addDocumentStartJavaScript`, restrito à origem do Function App.
  Ele roda antes de qualquer script da página, venha o HTML da rede ou do cache do service worker. No arranque, o plugin
  confere `WebViewFeature.DOCUMENT_START_SCRIPT`. Sem esse recurso, mostra "atualize o Android System WebView" com o
  link da loja.
- O canal da página para o nativo usa `addWebMessageListener`, só para essa origem. Nunca `addJavascriptInterface`.
  As páginas de login da Microsoft não falam com o espelho.
- Liga `CookieManager.setAcceptThirdPartyCookies(webView, true)`. Sem isso, depois das 24 h do refresh token de SPA a
  renovação silenciosa do MSAL falha, e o técnico vê "sessão expirou" todo dia.
- Trata `onRenderProcessGone`: devolve `true`, recria a WebView e recarrega. Sem isso, a falta de memória num aparelho
  de 4 GB derruba o app inteiro.
- Abre URLs de fora numa Custom Tab, pelo `@capacitor/browser`.
- Limpa o histórico da WebView depois do login, para o "voltar" não cair nas páginas da Microsoft.

### 6.3 O script de início
1. **`window.open` devolve `null` para o login da Microsoft.** Na WebView sem múltiplas janelas, ele devolve hoje a
   própria janela do App. Com `null`, o MSAL cai no `loginRedirect`, que o `campo.html` já trata.
2. **`window.open` vazio ou de fora devolve uma janela de mentira**, para o "Conectar conta Fracttal":
   - `document.open`, `write` e `close` guardam o HTML escrito, sem tocar na tela do App;
   - o `location.href` abre a URL numa Custom Tab;
   - se a janela não for levada a uma URL em ~1 s, é o caso de erro: a página escreveu uma explicação, por exemplo
     "sua sessão da Microsoft expirou" com os passos. A casca mostra esse HTML, sem mudar o texto, numa camada por cima
     do App com um botão "Fechar". Essa camada vai ao campo, então o Levi aprova uma prévia antes.

   Isso é obrigatório, porque hoje o `document.write` apaga a tela do App. Na volta, o `visibilitychange` da página já
   reconfere a conta pelo `/fracttal/status`.
3. **Espelho de escrita do localStorage.** Embrulha `Storage.prototype.setItem` e `removeItem` só quando `this` for o
   localStorage real, capturado no início. O sessionStorage do MSAL fica de fora.
   - Ordem: chama o original primeiro. Se ele lançar `QuotaExceededError`, relança sem espelhar, porque o `lsS` e o
     despejo da página dependem disso.
   - `getItem`, `key`, `length` e `Object.keys(localStorage)` ficam intocados. O real continua completo, e as faxinas e
     o MSAL continuam enxergando tudo.
   - A `gc_campo_queue` vai ao nativo na hora. O resto é agrupado por chave, em 300 a 500 ms, e a última gravação vence.
4. **Espelho do IndexedDB das fotos** (banco `gc_campo_fotos`, store `fotos`).
   - Embrulha `put` e `delete` desse banco e espelha só no `oncomplete` da transação.
   - Blob e File vão em pedaços de ~512 KB. O vídeo chega a 15 MB e não pode virar um base64 inteiro num aparelho de
     4 GB.
   - `get` e `getAllKeys` ficam intocados.
   - Durante uma restauração (6.4), o `indexedDB.open('gc_campo_fotos')` é segurado até ela terminar.
5. **Restauração** (6.4), antes de qualquer script da página.

Não precisa de shim de Wake Lock nem da Permissions API: a verificação no código do Chromium mostrou que a WebView
concede o primeiro e responde "prompt" no segundo.

### 6.4 O espelho nativo
**O que espelhar** (lista do mapa do código; o construtor confere de novo no `campo.html` vigente):
- **Trabalho**, que não volta da rede:
  - `gc_campo_queue` (prioridade máxima);
  - `gc_fech_draft`;
  - `gc_ronda_<...>` (exceto `gc_ronda_ctx`);
  - `gc_apr_rasc_*`, `gc_campo_aprdia`, `gc_ext_rasc_*`;
  - `gc_zl_pend`, `gc_zl_draft`;
  - `gc_v1_os_chamado`, `gc_campo_status`, `gc_v1_os_voltou`, `gc_fb_draft`, `gc_v1_fora_local`, `gc_v1_pt_st`,
    `gc_campo_geo_ini`;
  - `gc_campo_ident`, **cifrado com o Android Keystore**, por ser dado pessoal;
  - no IndexedDB: `d_*`, `f_*`, `fi_*`, `v_*`, `a_*`, `idb:*`, `ext:*`, `rdraft:*` e as entradas
    `<gc_apr_rasc_...>|<campo>`.
- **Cache necessário para trabalhar sem sinal**, com prioridade menor:
  - `gc_subs_*`, `gc_campo_apr`, `gc_campo_aprdia_f`, `gc_v1_pt_def`, `gc_ronda_ctx`, `gc_ext_ctx`, `gc_zl_modelos`,
    `gc_zl_serv`, `gc_v1_almox_*`, `gc_v1_minhas_prev`;
  - no IndexedDB: `__programacao` e `fora_programacao`.
- **Nunca espelhar:**
  - as chaves `msal.*` e qualquer token (se perder, o técnico entra de novo);
  - o sessionStorage;
  - `gc_erros_js`, `gc_vivo`, `gc_tela` e as demais preferências e contadores.

**Regras**
- **A mesma geração ou nada.** A restauração devolve localStorage e IndexedDB do mesmo instante. Devolver só um dos
  dois faz a faxina de órfãos da página apagar as fotos restauradas.
- **Quando restaurar:** só quando a WebView perdeu o armazenamento. Há duas sentinelas da casca: uma chave no
  localStorage e um banco IndexedDB próprio (`gc_casca`), que a página nunca toca. O despejo do Chromium pode levar uma
  loja e deixar a outra, e a falta de qualquer das duas dispara a restauração. O construtor confere que nenhuma faxina
  da página apaga a chave. Fora disso, o espelho nunca escreve na página.
- **A fila tem versões.** O nativo guarda as 2 ou 3 últimas, com hash. Na partida:
  - se o valor da WebView bate com uma versão antiga, o nativo está à frente e a restaura;
  - se não bate com nenhuma, a WebView está à frente e é espelhada.

  O espelho nunca apaga uma versão que não leu.
- **A casca nunca devolve sozinha uma chave que a página apagou.** Uma `gc_zl_pend` já enviada, devolvida, reenviaria
  registros. O espelho repete as exclusões da página. Não há lixeira: ela pediria uma tela e um fluxo de recuperação,
  e a faxina da página já é a mesma do PWA de hoje.
- **Do que o espelho protege:** da perda do armazenamento da WebView (despejo por disco cheio, corrupção, troca de
  WebView). Não protege de "Limpar armazenamento" nos Ajustes nem de desinstalar, que apagam o espelho junto. Regra de
  suporte: nunca mandar limpar os dados do app. Para destravar o login, o App já tem o "limpar só o login".
- **Depois de uma perda sem sinal:** se o despejo levou também o cache do service worker, a página não abre sem rede.
  A página de erro diz "Seus registros estão guardados neste aparelho. O App volta quando houver internet". Com sinal, a
  página carrega e a restauração devolve tudo. É raro, e aceitamos.
- **Onde fica:** só no armazenamento privado do app, com `allowBackup=false` e `dataExtractionRules` excluindo tudo em
  `<cloud-backup>` e `<device-transfer>`. Os nomes das chaves têm e-mail, e nada disso pode ir para backup na nuvem.
- O construtor escolhe a mecânica do armazenamento nativo (SQLite, arquivos ou os dois).

### 6.5 Manifesto, permissões e primeira abertura
- Permissões:
  - `INTERNET` e `ACCESS_NETWORK_STATE`. Sem a segunda, o `navigator.onLine` fica sempre `true` e o evento `online`,
    que dispara a sincronização, nunca vem. Não chamar `setNetworkAvailable`, que desliga a detecção automática.
  - `CAMERA`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`;
  - `REQUEST_INSTALL_PACKAGES`, para a atualização da casca (seção 7);
  - `<queries>` para `IMAGE_CAPTURE` e `VIDEO_CAPTURE`;
  - `uses-feature` de GPS com `required=false`.
- **Tela de primeira abertura** (nativa): pede a câmera e a localização **precisa**. No Android 12+, se o técnico
  escolher "aproximada", a posição vem com erro de quilômetros e o "esquentar" do GPS não termina. A tela explica isso e
  pede de novo, com um botão para os ajustes do app. É tela que vai ao campo: o Levi aprova uma prévia antes.
- GPS: fica a geolocalização da própria WebView, que o Capacitor já libera. Se a prova no aparelho mostrar posição ruim,
  o plano B é trocar `navigator.geolocation` no script de início pelo `@capacitor/geolocation` (provedor fundido), com os
  mesmos códigos de erro 1, 2 e 3.
- Textos da página pensados para o navegador (o passo a passo do GPS manda abrir as permissões do Chrome) ficam como
  estão. Vão como aviso para a sessão "App de campo", que pode separar o app pelo `GridcoCampoApp` no user agent.

## 7. Distribuição e atualização da casca

Segue o precedente da própria Grid, o `Grid-Co-CODE/oem-release` (canal de atualização do OS Creator). É um repositório
**público** que guarda só o instalador e um `versao.json`, para nenhuma credencial ir dentro do app.

- Cada versão da casca vira uma Release do GitHub com:
  - o APK assinado (`app-campo.apk`);
  - o `versao.json`, com o número, o link do APK, o SHA-256 e as novidades.
- **Primeira instalação:** o link fixo
  `https://github.com/Grid-Co-CODE/<repo>/releases/latest/download/app-campo.apk`, mandado pelo supervisor (mensagem ou
  QR). Na primeira vez, o técnico autoriza "instalar apps desconhecidos" para o navegador.
- **Atualização da casca:** ao abrir e ao voltar ao app, no máximo uma vez a cada 6 h, o app lê o `versao.json` da
  `releases/latest/download/`. Esse endereço não conta no limite da API do GitHub. Se houver versão maior:
  1. o app oferece a atualização;
  2. baixa o APK e confere o SHA-256;
  3. abre o instalador do sistema.

  A assinatura tem de ser a mesma, ou o Android recusa a atualização.
- **A parte web não depende disso:** chega na hora, pelo servidor, como hoje.
- O APK não tem segredo. Ele equivale à URL pública do App de hoje, que sem login Microsoft da Grid não abre nada.

**Verificação de desenvolvedor do Android.** No Brasil, desde 30/09/2026 ela vale para instalações vindas das lojas. O
APK baixado por fora passa a ser cobrado na expansão global de 2027, ainda sem mês. Antes disso, a Grid registra o
pacote como organização no Android Developer Console: US$ 25 uma vez só, D-U-N-S, e-mail e site da empresa, e o SHA-256
da chave. Sem o registro, cada técnico teria de ligar o modo desenvolvedor e esperar 24 h, e as atualizações falhariam.
Não é preciso publicar nada na Play.
- A FAQ oficial (atualizada em 30/09/2026) diz que, para quem instala por fora, "these new verification requirements
  won't apply to your app yet".
- **Se mesmo assim** o aparelho do piloto pedir o fluxo avançado, o piloto usa a distribuição limitada: grátis, sem
  documento, até 20 aparelhos.
- **Recomendação:** pedir já o D-U-N-S da Grid, que é grátis e só demora. Assim os US$ 25 do registro podem ser pagos
  no dia em que forem necessários.

## 8. GitHub

- **Repositório `Grid-Co-CODE/app-campo-android`, público**, com o código da casca e as Releases. Público pelo mesmo
  motivo do `oem`/`oem-release`, e porque no plano Free o repositório público tem Actions sem limite de minutos e regras
  da `main`. Não entra segredo, nome de colega nem dado do campo.
- **Workflows:**
  - em todo push e PR: testes do script de início e montagem de um APK de depuração;
  - em tag `v*`: montagem do APK assinado, Release com o APK, o `versao.json` e o SHA-256;
  - diário: o teste de contrato com o site (10.2), que abre uma issue se falhar.
- **Assinatura:**
  - a chave (keystore) é gerada uma vez nesta máquina, com validade de 30 anos;
  - vai para os Secrets do Actions em base64, com as senhas, num ambiente `release`;
  - fica com **cópia guardada fora do GitHub e fora do SharePoint**.

  Perder a chave significa nunca mais atualizar o app: o técnico teria de desinstalar e perderia o que está no aparelho.
- `versionCode` = número da execução; `versionName` = semver da casca (1.0.0). A versão da página continua sendo o
  `APP_VERSAO`.
- **O agente do GitHub (Copilot)** só cuida de manutenção pequena e revisada, como subir dependências ou o targetSdk. Ele
  não enxerga os segredos e não solta versão.

## 9. A troca do PWA para o app

O armazenamento da WebView do app é separado do Chrome, então **o app começa vazio**. Por técnico:
1. No PWA, a aba Sincronização tem de mostrar zero pendências. Também não pode haver trabalho aberto fora da fila:
   - fechamento começado;
   - ronda em andamento;
   - APR do dia;
   - ronda de extintores do mês.
2. **Desinstalar o PWA.** Com os dois instalados e o mesmo ícone, o técnico abre o antigo por hábito, e a volta do
   Fracttal pode cair nele.
3. Instalar o APK.
4. Abrir com sinal e entrar com a conta Microsoft.
5. Conectar o Fracttal.

Começa com um **piloto de 3 a 5 técnicos**. Saem os dois textos de sempre: a mensagem para o campo, com saudação e
agradecimento, e o relatório.

## 10. Como provar que funciona

1. **Testes do script de início** (Node, com localStorage e IndexedDB falsos):
   - o espelho chama o original primeiro;
   - a cota estourada relança sem espelhar;
   - o sessionStorage e as chaves `msal.*` nunca vão ao nativo;
   - o `put` e o `delete` só espelham no `oncomplete`;
   - a restauração devolve as duas lojas da mesma geração;
   - a regra das versões da fila;
   - a `window.open` de mentira.

   Cada teste roda vermelho → verde → mutação, como no App de Campo.
2. **Teste de contrato com o site ao vivo**, diário no Actions, só leitura (um GET do `/api/app`). Falha se sumir do
   `campo.html` algo de que a casca depende:
   - o banco `gc_campo_fotos` e a store `fotos`;
   - as chaves da lista 6.4;
   - o `window.open('', '_blank')` do Fracttal;
   - o `loginRedirect`;
   - a trava do `/api/app`;
   - a versão fixa do MSAL.

   É o aviso de que a sessão "App de campo" mudou algo, antes de o campo sentir.
3. **Prova no aparelho.** Usa o usuário e a OS de teste combinados com o PCM: o Levi passa quais são, e nenhuma OS real
   é usada. As primeiras rodadas são num Android ligado neste PC pelo USB, com `adb`. A rodada final é num moto g35, com
   o APK instalado pelo link. A prova cobre:
   - partida em modo avião depois da primeira abertura, inclusive logo depois de uma troca de versão do `sw.js`;
   - login com MFA dentro do app, e a lista de hosts do `allowNavigation`;
   - login ainda válido depois de 25 h, com os cookies de terceiros ligados;
   - conectar o Fracttal e voltar ao app com a conta reconhecida;
   - foto pela câmera interna e pela reserva do `<input capture>`; vídeo;
   - GPS com localização precisa e com aproximada;
   - fechar uma OS sem sinal, voltar o sinal e ver a fila subir sozinha (evento `online`);
   - matar o app no meio de uma gravação e ver que nada se perdeu;
   - apagar o armazenamento da WebView numa versão de teste e ver o espelho devolver a fila e as fotos;
   - atualizar a casca pelo `versao.json`.

   A "Sonda da câmera interna" do próprio App mede a câmera dentro do APK.

## 11. Riscos aceitos

- `server.url` em produção, contra a recomendação da doc do Capacitor (seção 5).
- **Login Microsoft dentro da WebView.** A RFC 8252 desaconselha, e a Microsoft avisa que a WebView embutida não tem
  SSO. Se o tenant tiver Acesso Condicional exigindo dispositivo em conformidade ou app aprovado, a WebView é recusada.
  Nesse caso, a fase 2 (login nativo) vira necessária. A prova no aparelho mostra.
- **Um XSS no site ganharia os plugins nativos**, porque a ponte vale para a origem do site. Contrapartida: o plugin
  expõe só espelhar, ler para restaurar e abrir fora, e confere a origem de quem chama.
- **A primeira abertura e o primeiro login exigem sinal.**
- **Com o app fechado, nada sobe.** É o mesmo comportamento do PWA de hoje.

## 12. Fase 2 (fora deste trabalho)

- Login nativo com o Authenticator (MSAL Android): sessão de 90 dias. Pede a plataforma Android no mesmo registro do
  Entra, mudança feita pela T.I.
- Envio com o app fechado (WorkManager), que depende do login nativo.
- Empacotar o HTML, se um dia o servidor liberar CORS para o app.
- Registro no Android Developer Console antes da cobrança de 2027.

## 13. Dependências de terceiros

- **T.I. / Entra:** nenhuma mudança na fase 1. Se o login falhar dentro do app, a pergunta é se há Acesso Condicional.
- **D-U-N-S da Grid**, gratuito e pode levar até 30 dias, e os **US$ 25**: só antes da cobrança de 2027.

## 14. O que fica para o construtor

- A estrutura do plugin e do script de início.
- A mecânica do espelho nativo e o agrupamento.
- O `minWebViewVersion`.
- O ícone, a partir do `icon-512` do App.
- A página de erro.
- O código da atualização da casca.
- O nome exato dos workflows.
- Conferir de novo a lista de chaves no `campo.html` vigente.

## 15. Decisões que foram minhas (para o Levi conferir)

- **A forma "App ao vivo" (`server.url`)** no lugar do HTML empacotado, por causa da restrição de não mexer no site.
- **Repositório público e download pelo GitHub**, no lugar do "link nosso com login". Sem mexer no servidor, não há onde
  pôr um link com login, e o precedente `oem-release` já faz assim.
- **Sem lixeira:** o espelho repete as exclusões da página, e a regra de suporte é nunca limpar os dados do app.
- **Duas sentinelas** (chave no localStorage e banco `gc_casca`) para perceber a perda.
- **A mensagem de erro do Fracttal** que a página escreve aparece numa camada por cima do App, com o texto dela, sem
  mudança (tem prévia).
- **Desinstalar o PWA na troca**, depois de conferir a fila e o trabalho aberto.
- **Pacote `br.com.gridco.campo`, nome "App de Campo Grid Co.", rótulo do ícone "Campo GridCo"**, os mesmos do
  manifesto de hoje.
- **Checagem de atualização da casca no máximo a cada 6 h.**
- **Piloto de 3 a 5 técnicos** antes de liberar para todos.
