# Mapa de equipe: a coleta no Nexus Field 1.2.0 (plano)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o Nexus Field coleta a posição do técnico de campo da primeira abertura do dia até as 22h, sem pausa, e a manda
ao Nexus pela chave do aparelho, com o aviso antes e a notificação fixa durante.

**Architecture:** a página (o `inicio.js`, seção nova) pede a chave do aparelho ao Nexus com o login Microsoft dela e a
passa ao nativo; o nativo guarda a chave cifrada, decide se liga, roda um serviço em primeiro plano do tipo localização
que põe os pontos numa fila local e os manda em lotes ao Nexus, e para às 22h, no Sair ou quando o Nexus disser que a
coleta não vale. A lógica de decidir fica em classes puras, testadas na JVM; o serviço só junta as peças.

**Tech Stack:** Capacitor 8.5.3, Java (Android 7 a 16, minSdk 24, targetSdk 36), `com.google.android.gms:play-services-location:21.3.0`
(o FusedLocationProviderClient), SQLite do Android, HttpURLConnection (como o `Atualizador`), JavaScript do `inicio.js`
(jsdom nos testes), JUnit + Robolectric.

**Spec:** `docs/superpowers/specs/2026-11-08-mapa-de-equipe-design.md` (leia inteiro; o contrato é a seção 8).

## Global Constraints

- Janela: liga na primeira abertura (ou volta ao app) entre **06:00 e 22:00 na hora do celular**; para às **22:00**.
  Sem botão de pausa. 06:00:00 vale; 22:00:00 não vale.
- A coleta só vale com `coleta: true` do Nexus; `coleta: false` ou `401` param o serviço e apagam a chave.
- O token Microsoft nunca chega ao nativo, ao espelho nem ao log; a chave do aparelho é guardada só cifrada (`Cifra`).
- Nada de coordenada, chave, e-mail ou id de aparelho no log (`Log`), nem nos `erro` que a página manda.
- Endereços: `https://app.gridco.com.br` + `/t/campo/equipe/aparelho`, `/t/campo/equipe/posicoes`,
  `/t/campo/equipe/aparelho/revogar` (seção 8 do desenho; o chat do Nexus pode trocar, e avisa).
- Envio: até 200 pontos por lote; ritmo 120 000 ms em deslocamento e 600 000 ms parado.
- Fila local: no máximo 1000 pontos e nada com mais de 24 h; passou, sai o mais velho.
- Texto novo de interface: no `strings.xml` marcado PENDENTE de aprovação e na prévia `docs/previas/telas.html`.
- pt-BR, sem emoji, sem nome de pessoa em arquivo versionado; `npm test` e
  `./gradlew testDebugUnitTest assembleDebug lint` verdes; lint sem erro.
- O site (App de Campo) e o Fracttal não mudam.

## Review Focus

- **O Android derruba o serviço no meio do dia (Xiaomi):** na volta ao app dentro da janela ele religa. Teste na Task 5
  (`decidir` com o serviço parado, dentro da janela, chave e permissão em dia → `INICIAR`).
- **O técnico viaja ou muda a hora do celular:** a janela é calculada na hora do celular a cada decisão, e cada lote
  leva o `fuso`. Teste na Task 1 com dois fusos.
- **Horas sem sinal:** a fila corta em 1000 pontos / 24 h, sem travar. Teste na Task 2.
- **Troca de conta no mesmo celular:** o Sair revoga, apaga e para; a chave nova de outra pessoa mostra o aviso de novo.
  Testes nas Tasks 2 e 5.
- **A permissão tirada nos ajustes com o serviço rodando:** o serviço para sem quebrar o app, e a próxima decisão é
  `SEM_PERMISSAO`. Teste na Task 5.

---

### Task 1: A janela e o ritmo

**Files:**
- Create: `android/app/src/main/java/br/com/gridco/campo/JanelaDaEquipe.java`,
  `android/app/src/main/java/br/com/gridco/campo/RitmoDaColeta.java`
- Test: `android/app/src/test/java/br/com/gridco/campo/JanelaDaEquipeTest.java`, `RitmoDaColetaTest.java`

**Interfaces:**
- Produces: `static boolean JanelaDaEquipe.dentro(long instante, TimeZone fuso)`;
  `static long JanelaDaEquipe.fimDaJanela(long instante, TimeZone fuso)` (as 22:00 daquele dia, em epoch ms);
  `static int JanelaDaEquipe.fusoEmMinutos(long instante, TimeZone fuso)`;
  `RitmoDaColeta.proximoIntervaloMs(double velocidadeMs, double metrosDesdeOUltimo)` → `120_000` ou `600_000`
  (parado: velocidade < 1 m/s e menos de 150 m do último ponto em 3 leituras seguidas; volta a 120 000 na primeira
  leitura acima disso).

- [ ] **Step 1: Testes que falham**
  - `JanelaDaEquipeTest`: em `America/Sao_Paulo`, 05:59:59 fora, 06:00:00 dentro, 21:59:59 dentro, 22:00:00 fora;
    `fimDaJanela` às 21:00 dá as 22:00 do mesmo dia; o mesmo instante em `America/Cuiaba` (uma hora atrás) às 06:30 de
    Brasília é 05:30 lá, fora; `fusoEmMinutos` dá -180 em Brasília.
  - `RitmoDaColetaTest`: em movimento 120 000; três leituras paradas seguidas 600 000; uma leitura em movimento depois
    volta a 120 000; duas paradas só não mudam.
- [ ] **Step 2: Ver falhar** — `./gradlew testDebugUnitTest --tests "*JanelaDaEquipeTest" --tests "*RitmoDaColetaTest"`
- [ ] **Step 3: Implementar** as duas classes, puras (sem Android), com `Calendar` no fuso dado.
- [ ] **Step 4: Ver passar** — o mesmo comando, verde.
- [ ] **Step 5: Commit** `feat(equipe): a janela das 6h às 22h e o ritmo da coleta`

### Task 2: A chave do aparelho e a fila de pontos

**Files:**
- Create: `ChaveDaEquipe.java`, `FilaDePontos.java`, `PontoDaEquipe.java` (em `android/app/src/main/java/br/com/gridco/campo/`)
- Test: `ChaveDaEquipeTest.java`, `FilaDePontosTest.java` (Robolectric, com a `CifraFalsa` que já existe)

**Interfaces:**
- Produces: `ChaveDaEquipe(Context, Cifra)` com `String aparelho()` (UUID aleatório criado uma vez e guardado no
  SharedPreferences `equipe`), `boolean guardar(String chave)` (true se é outra chave: zera o aviso aceito),
  `String chave()` (null sem chave), `void apagar()`, `boolean avisoAceito()`, `void aceitarAviso()`.
  `PontoDaEquipe(long t, double lat, double lon, float prec, Float vel, boolean mov)` com `JSONObject json()`.
  `FilaDePontos(Context)` sobre o banco `equipe.db` com `void guardar(PontoDaEquipe)`, `List<Long> idsDoLote(int max)`,
  `JSONArray lote(List<Long> ids)`, `void remover(List<Long> ids)`, `int tamanho()`, `void limpar()`.

- [ ] **Step 1: Testes que falham**
  - `ChaveDaEquipeTest`: o `aparelho()` é o mesmo em duas instâncias e não vazio; a chave guardada não aparece em claro
    no SharedPreferences (com a `CifraFalsa`, o valor guardado começa pelo prefixo dela); `guardar` da mesma chave dá
    false e mantém o aviso; `guardar` de outra dá true e `avisoAceito()` volta a false; `apagar` deixa `chave()` null e
    mantém o `aparelho()`.
  - `FilaDePontosTest`: guardar 3 e ler o lote na ordem; `remover` tira só os ids dados; com 1005 pontos ficam 1000 (os
    5 mais velhos saem); ponto com mais de 24 h sai na próxima gravação; `limpar` zera.
- [ ] **Step 2: Ver falhar**
- [ ] **Step 3: Implementar** (a chave em base64 do `Cifra.cifrar`; a fila com `SQLiteOpenHelper` próprio, fora do
  espelho, para nunca misturar com os dados do técnico).
- [ ] **Step 4: Ver passar**
- [ ] **Step 5: Commit** `feat(equipe): a chave do aparelho cifrada e a fila de pontos`

### Task 3: O envio ao Nexus

**Files:**
- Create: `EnvioDePontos.java`
- Test: `EnvioDePontosTest.java`

**Interfaces:**
- Consumes: `ChaveDaEquipe`, `FilaDePontos`, `JanelaDaEquipe.fusoEmMinutos` (Tasks 1 e 2).
- Produces: `interface Transporte { Resposta postar(String url, String chave, String corpoJson) throws IOException; }`
  com `Resposta(int status, String corpo)`; `EnvioDePontos(ChaveDaEquipe, FilaDePontos, Transporte, LongSupplier relogio)`
  com `Resultado enviar()` (`enum Resultado { NADA, ENVIADO, PARAR, SEM_REDE }`) e `void revogar()`.
  Constantes `NEXUS = "https://app.gridco.com.br"`, `ROTA_POSICOES = "/t/campo/equipe/posicoes"`,
  `ROTA_REVOGAR = "/t/campo/equipe/aparelho/revogar"`. O `Transporte` de verdade usa HttpURLConnection com 15 s de prazo.

- [ ] **Step 1: Testes que falham** (com um `Transporte` falso que grava o pedido)
  - sem chave ou fila vazia: `NADA`, nenhum pedido;
  - com 250 pontos: o primeiro pedido leva 200, com `aparelho`, `fuso` e o `Authorization` com a chave; 200
    `{aceitos:200, coleta:true}` tira os 200 da fila e dá `ENVIADO`;
  - 200 `{coleta:false}`: `PARAR`, a chave apagada e a fila limpa;
  - 401: `PARAR`, a chave apagada;
  - IOException: `SEM_REDE`, a fila intacta;
  - `revogar()`: um pedido à rota de revogar com a chave e `{aparelho}`, e a chave apagada mesmo se o pedido falhar.
- [ ] **Step 2: Ver falhar**
- [ ] **Step 3: Implementar**
- [ ] **Step 4: Ver passar**
- [ ] **Step 5: Commit** `feat(equipe): o envio dos pontos ao Nexus e a revogação da chave`

### Task 4: O serviço de coleta

**Files:**
- Create: `ServicoDaEquipe.java`
- Modify: `android/app/src/main/AndroidManifest.xml` (as permissões `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_LOCATION`; o `<service android:name=".ServicoDaEquipe" android:exported="false"
  android:foregroundServiceType="location" />`), `android/app/build.gradle` (a dependência do play-services-location
  21.3.0), `CanaisDeNotificacao.java` (o canal `equipe_localizacao`, importância baixa, sem som), `strings.xml` (o nome
  do canal e o título e o texto da notificação fixa, PENDENTES)
- Test: `ServicoDaEquipeTest.java` (Robolectric)

**Interfaces:**
- Consumes: `JanelaDaEquipe`, `RitmoDaColeta`, `FilaDePontos`, `EnvioDePontos` (Tasks 1 a 3).
- Produces: `static void ServicoDaEquipe.iniciar(Context)` e `static void ServicoDaEquipe.parar(Context)`;
  `static boolean ServicoDaEquipe.rodando()`. O serviço: `startForeground` com a notificação fixa no canal
  `equipe_localizacao`; pede posições ao FusedLocationProviderClient com o intervalo do `RitmoDaColeta` (prioridade
  `PRIORITY_BALANCED_POWER_ACCURACY` parado e `PRIORITY_HIGH_ACCURACY` em deslocamento); cada posição vai à fila; a
  cada 120 000 ms chama `EnvioDePontos.enviar()`; `PARAR` ou o `fimDaJanela` param o serviço; `SecurityException`
  (permissão tirada) para o serviço com um log sem conteúdo; `START_STICKY`, e o religamento pelo sistema fora do
  primeiro plano que o Android recusar só registra e para (a próxima abertura religa).

- [ ] **Step 1: Testes que falham**
  - o manifesto declara o serviço com `foregroundServiceType` de localização e as três permissões novas;
  - `iniciar` dentro da janela põe o serviço em primeiro plano com a notificação no canal `equipe_localizacao`;
  - o serviço iniciado às 21:59 (relógio falso) se para ao passar das 22:00;
  - `EnvioDePontos` devolvendo `PARAR` para o serviço e tira a notificação.
- [ ] **Step 2: Ver falhar**
- [ ] **Step 3: Implementar** (o relógio e o `EnvioDePontos` injetáveis por um campo estático de teste, como as outras
  peças do processo)
- [ ] **Step 4: Ver passar** — e `./gradlew assembleDebug lint`, sem erro
- [ ] **Step 5: Commit** `feat(equipe): o serviço de coleta em primeiro plano, das 6h às 22h`

### Task 5: A casca liga e desliga (nativo)

**Files:**
- Create: `EquipeNaCasca.java`
- Modify: `Mensageiro.java` (as mensagens `equipePedirAparelho`, que responde `{t:'equipeAparelho', aparelho, versao}`,
  e `equipeChave {coleta, chave}`; `'equipe'` no `ONDES_DA_PAGINA`), `CascaPlugin.java` (as ações novas; no
  `handleOnResume`, `EquipeNaCasca.reavaliar()`; no `pushSaiu`, também `EquipeNaCasca.sair()`), `strings.xml` (o aviso
  antes de ligar e a explicação da permissão "o tempo todo", PENDENTES)
- Test: `EquipeNaCascaTest.java`, `MensageiroTest.java`

**Interfaces:**
- Consumes: `ChaveDaEquipe`, `EnvioDePontos.revogar`, `ServicoDaEquipe.iniciar/parar/rodando`, `JanelaDaEquipe.dentro`.
- Produces: `enum Acao { INICIAR, PARAR, MOSTRAR_AVISO, PEDIR_PERMISSAO, NADA }`;
  `static Acao EquipeNaCasca.decidir(boolean coleta, boolean temChave, boolean avisoAceito, boolean permissaoDeUso,
  boolean permissaoOTempoTodo, boolean dentroDaJanela, boolean rodando)`; `void receberChave(boolean coleta, String chave)`;
  `void reavaliar()`; `void sair()`. O aviso é um diálogo que não fecha tocando fora; o Continuar marca
  `aceitarAviso()` e segue para a permissão; no Android 10 pede `ACCESS_BACKGROUND_LOCATION` junto, no 11 ou mais novo
  explica e pede, e o Android leva aos ajustes.

- [ ] **Step 1: Testes que falham**
  - `decidir`: coleta false → `PARAR`; sem aviso aceito → `MOSTRAR_AVISO`; sem a permissão o tempo todo →
    `PEDIR_PERMISSAO`; fora da janela → `NADA` (e `PARAR` se estiver rodando); tudo em dia e parado → `INICIAR`; tudo em
    dia e rodando → `NADA`;
  - `receberChave(false, null)` apaga a chave e para; `receberChave(true, outra)` zera o aviso (vem da Task 2);
  - `sair()` revoga, apaga e para, mesmo sem rede;
  - `MensageiroTest`: `equipePedirAparelho` responde com o `aparelho` da `ChaveDaEquipe`; `equipeChave` sem `chave` com
    `coleta:true` é recusada com um `erro` sem conteúdo; nenhuma mensagem nova grava no log a chave ou o aparelho.
- [ ] **Step 2: Ver falhar**
- [ ] **Step 3: Implementar**
- [ ] **Step 4: Ver passar**
- [ ] **Step 5: Commit** `feat(equipe): a casca liga a coleta com o aviso e a permissão, e desliga no Sair`

### Task 6: A página pede a chave (o `inicio.js`)

**Files:**
- Modify: `www/casca/inicio.js` (a seção 10, "A chave da equipe", depois da seção 9)
- Create: `testes/equipe.test.mjs`
- Modify: `testes/contrato.mjs` só se a seção usar nome novo do site (hoje: `getTokenMW`, `#s-app` e o `sair`, já
  presos pela seção 8)

**Interfaces:**
- Consumes: o `quandoLogado`/`appNaTela`, o `pedirBearer` e o `saiu` da seção 8, que moram no escopo do
  `instalarPush`: a seção 10 roda dentro dele (um bloco no fim, chamado `instalarEquipe(ctx, pecas)`), sem copiar
  essas funções; `equipePedirAparelho` / `equipeAparelho` / `equipeChave` (Task 5).
- Produces: uma vez por carga, com o `#s-app.on` e depois de `ESPERA_PUSH_MS`: pede o `aparelho` ao nativo, faz
  `POST https://app.gridco.com.br/t/campo/equipe/aparelho` com `Authorization: Bearer <getTokenMW>` e
  `{aparelho, app: 'NexusField/<versao>'}`, e manda ao nativo `{t:'equipeChave', coleta, chave}` com a resposta 200.
  401, erro de rede ou CORS: nada nesta carga (a próxima tenta). Depois do Sair, nada.

- [ ] **Step 1: Testes que falham** (`testes/equipe.test.mjs`, jsdom com `fetch` falso)
  - logado: um pedido ao endereço certo, com o Bearer e o corpo `{aparelho, app}`, e a mensagem `equipeChave` com o que
    o Nexus respondeu;
  - na tela de login: nada até o `#s-app` ganhar o `.on`, e aí um pedido só;
  - duas cargas da mesma página não fazem dois pedidos;
  - o token Microsoft não aparece em nenhuma mensagem ao nativo;
  - 401 e `fetch` que lança: nenhuma mensagem `equipeChave`, nenhuma exceção que escape;
  - depois do `sair()`: nenhum pedido.
- [ ] **Step 2: Ver falhar** — `node --test testes/equipe.test.mjs`
- [ ] **Step 3: Implementar**
- [ ] **Step 4: Ver passar** — e `npm test` inteiro
- [ ] **Step 5: Commit** `feat(equipe): a página pede a chave do aparelho ao Nexus`

### Task 7: Prévia, guia, roteiro e versão 1.2.0

**Files:**
- Modify: `docs/previas/telas.html` e `testes/previas.test.mjs` (a seção "Localização da equipe": o aviso antes de
  ligar, a explicação da permissão, a notificação fixa e o nome do canal; no "O que aprovar", um item para cada texto e
  um para o que o aviso promete: quando, por quê, quem vê e por quanto tempo, como na seção 4.1 do desenho)
- Modify: `docs/instalacao/guia.fonte.html`, `cartao-2.fonte.html` (a linha "Localização: Permitir o tempo todo" e o
  início automático da Xiaomi também para a localização); rodar `python docs/instalacao/montar.py`
- Modify: `docs/prova-no-aparelho.md` (a seção do piloto: a bateria das 6h às 22h, a parada às 22h, a coleta com o app
  fechado, o Sair que revoga, o técnico sem a permissão o tempo todo, a troca de conta)
- Create: `novidades/1.2.0.md`
- Modify: `CLAUDE.md` (a seção "Localização da equipe": a regra, o porquê, o contrato com o Nexus e o que não se faz)

- [ ] **Step 1: Testes da prévia que falham** (os textos novos do `strings.xml` no quadro certo e no "O que aprovar")
- [ ] **Step 2: Implementar; Step 3: `npm test` verde** e os cartões refeitos
- [ ] **Step 4: Commit** `docs(equipe): prévia, guia, roteiro e novidades da 1.2.0`

A tag `v1.2.0` não é deste plano. Ela sai depois de: os textos aprovados pelo dono na prévia e pelo RH e pelo jurídico;
as rotas do Nexus no ar (chat "Nexus (Usinas 360)"); o piloto com 1 ou 2 técnicos.
