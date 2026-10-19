# Notificações no Nexus Field 1.1.0: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A casca recebe e mostra as notificações que o servidor do App de Campo manda pelo Firebase: os resumos das
07:00 e das 13:00 e a emergência. Ela também registra o aparelho, pede a permissão e abre a OS no toque.

**Architecture:**
- O Firebase Messaging fica no Java. Um serviço recebe só mensagens de dados e monta a notificação por uma classe pura
  e testável, em três canais.
- O registro do aparelho é feito pela própria página, com o Bearer dela. O nativo dá o token do FCM ao script de
  início, e o script chama `/api/push/registrar` e `/api/push/esquecer` (no `sair`).
- O toque leva a OS ao script, que a abre pela busca da página.

**Tech Stack:** Capacitor 8.5.3, Java 21, Firebase Messaging (BOM fixado), AndroidX Core (NotificationCompat), Node
22+ com jsdom, JUnit 4 com Robolectric.

**Spec:** `docs/superpowers/specs/2026-10-18-notificacoes-design.md` (e a spec da casca, 2026-10-16). O servidor
(rotas, timers, envio) é da sessão "App de campo" e não entra aqui.

## Global Constraints

- O site não muda. A página é usada só pelo que ela já tem (os nomes da página de hoje, a v287, que o contrato com o site
  exige: o `PUSH` e o `ABRIR_OS` do `www/casca/inicio.js`):
  - o registro e o Sair: `getTokenMW()` (sem abrir o login), a `const MIDDLEWARE_URL` de topo, a `async function sair()`
    (com o único logout da página, chamada pelo menu) e a tela do App, `#s-app` com o `.on` (o "logado" da casca);
  - o toque na emergência: `setBusca()` e `#busca-os`, `setTab('tarefas')`, o `let RONDA` de topo e o `rdVoltar` (a
    expressão do `comecou`), as camadas do `VOLTAR` e a entrada do site (o `let _telaRestaurada` com o `_telaRestaurar`
    chamado no `renderTudo`, e o `let pollTimer`, que o `entrarComIdentidade` só liga depois do `await carregarDados()`).
- Mensagem do FCM: só dados, no formato
  `{tipo:'resumo'|'emergencia', canal:'resumo'|'emergencia'|'emergencia_silenciosa', titulo, texto, os?}`.
- Os canais têm os ids `resumo`, `emergencia` e `emergencia_silenciosa`:
  - "Resumo do dia": importância padrão;
  - "Emergências": importância alta, com som e vibração;
  - "Emergências fora do expediente": importância baixa, sem som.
- Rotas do servidor:
  - `POST {MIDDLEWARE_URL}/push/registrar` com `{token, aparelho:'android', app:'NexusField/<versionName>'}`;
  - `POST {MIDDLEWARE_URL}/push/esquecer` com `{token}`;
  - `GET {MIDDLEWARE_URL}/me` (o papel `Admin`) e `POST {MIDDLEWARE_URL}/push/teste` com `{canal}` (só Admin; a emergência
    de teste traz a OS `999999999` desde a v287);
  - todas com o Bearer da página, no endereço sem a barra do fim.
- Nada de token Microsoft no nativo nem no espelho, e nada de nome de usina ou de pessoa em log ou notificação montada
  pela casca.
- `google-services.json` não é segredo e entra no repositório quando o dono o baixar. Sem ele, o build de PR passa sem
  Firebase, mas o job release falha.
- Textos novos vão ao `strings.xml` como PENDENTES de aprovação e à prévia (`docs/previas/telas.html`).
- pt-BR, sem emoji, sem nome de pessoa em arquivo versionado. Lint sem erro. npm test e
  `./gradlew testDebugUnitTest assembleDebug lint` verdes.

## Review Focus

1. **A mesma emergência chega duas vezes:** esperado substituir, não empilhar (id da notificação estável por OS).
   Teste na Task 1.
2. **Resumo da manhã e da tarde:** o da tarde substitui o da manhã (id fixo `resumo`). Teste na Task 1.
3. **Mensagem estranha ou incompleta** (tipo desconhecido, título vazio, OS com letras): nada de travamento.
   - Tipo desconhecido não mostra nada.
   - Título vazio vira o padrão.
   - OS fora de `^\d{1,12}$` é ignorada.

   Teste na Task 1.
4. **Rota do servidor ainda não publicada** (404) ou sem sinal: o script tenta de novo mais tarde, sem laço nem erro na
   tela. Teste na Task 3.
5. **Aparelho compartilhado:** no `sair`, o token é esquecido antes de a página limpar a sessão. Se o `esquecer` falhar,
   o `sair` segue mesmo assim. Teste na Task 3.

---

### Task 1: Firebase, canais e a montagem da notificação

**Files:**
- Modify: `android/build.gradle` (classpath do `com.google.gms:google-services`), `android/app/build.gradle`
  (`firebase-bom` + `firebase-messaging`; plugin `com.google.gms.google-services` aplicado só se
  `android/app/google-services.json` existir), `android/app/src/main/AndroidManifest.xml`,
  `.github/workflows/montar.yml` (job release: passo que falha sem `android/app/google-services.json`)
- Create: `android/app/src/main/java/br/com/gridco/campo/Notificacoes.java`, `CanaisDeNotificacao.java`,
  `MensagensDoFirebase.java`, `android/app/src/main/res/drawable/ic_notificacao.xml` (o G branco, vetor monocromático)
- Test: `android/app/src/test/java/br/com/gridco/campo/NotificacoesTest.java`, `testes/configuracao.test.mjs`,
  `testes/workflows.test.mjs`

**Interfaces:**
- Produces:
  - `final class Notificacoes` com `static Montada montar(java.util.Map<String,String> dados)`, que devolve `null` se não
    for para mostrar;
  - `static final class Montada { String canal; String titulo; String texto; String os; int id; }`;
  - `static boolean osValida(String os)`;
  - `CanaisDeNotificacao.criar(Context)`, idempotente;
  - `MensagensDoFirebase extends FirebaseMessagingService`: o `onMessageReceived` monta e mostra; o `onNewToken` grava
    em `SharedPreferences("push")` a chave `token` (o que o registro faz com ela está na Task 3);
  - Intent do toque para a `MainActivity`, com o extra `gc_os` (só na emergência com OS válida).

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test public void resumoVaiAoCanalResumoComIdFixo() { Montada m = montar(dados("resumo","resumo","Bom dia!","Hoje: 4 tarefas.", null)); assertEquals("resumo", m.canal); assertEquals(Notificacoes.ID_RESUMO, m.id); assertNull(m.os); }
@Test public void emergenciaTemIdEstavelPorOs() { assertEquals(montar(dadosEmerg("12345")).id, montar(dadosEmerg("12345")).id); assertNotEquals(montar(dadosEmerg("12345")).id, montar(dadosEmerg("12346")).id); }
@Test public void canalSilenciosoRespeitado() { assertEquals("emergencia_silenciosa", montar(dados("emergencia","emergencia_silenciosa","t","x","1")).canal); }
@Test public void tipoOuCanalDesconhecidoNaoMostra() { assertNull(montar(dados("promo","resumo","t","x",null))); assertNull(montar(dados("resumo","outro","t","x",null))); }
@Test public void tituloVazioViraPadraoETextoLongoECortado() { /* título vazio → "Nexus Field"; texto > 240 chars → 240 com "…" */ }
@Test public void osComLetrasEIgnorada() { assertFalse(Notificacoes.osValida("12a")); assertNull(montar(dadosEmerg("12a")).os); }
```

(Os helpers `dados(...)` e `dadosEmerg(os)` montam o Map; `dadosEmerg` usa `tipo=emergencia`, `canal=emergencia`.)

Mais:
- no `configuracao.test.mjs`: o manifesto tem `android.permission.POST_NOTIFICATIONS`, o
  `<service android:name=".MensagensDoFirebase" android:exported="false">` com o intent-filter
  `com.google.firebase.MESSAGING_EVENT`, e o meta-data `com.google.firebase.messaging.default_notification_channel_id`
  igual a `resumo`;
- no `workflows.test.mjs`: o job release tem o passo que falha sem `android/app/google-services.json`.

- [ ] **Step 2: Rodar e ver falhar**

Run: `npm test` e `./gradlew testDebugUnitTest`
Expected: FAIL

- [ ] **Step 3: Implementar**

Como montar:
- `NotificationCompat` com `setSmallIcon(R.drawable.ic_notificacao)`;
- `setContentIntent`: PendingIntent imutável para a `MainActivity`, com `FLAG_ACTIVITY_SINGLE_TOP`;
- `setAutoCancel(true)`;
- `setCategory`: `CATEGORY_ALARM` na emergência e `CATEGORY_REMINDER` no resumo.

O `id` da emergência é `ID_EMERGENCIA_BASE + (os.hashCode() & 0x7fffffff) % 1_000_000`.

Os canais são criados no `load()` do `CascaPlugin` e no `onMessageReceived`.

- [ ] **Step 4: Rodar e ver passar; montar**

Run: `npm test` e `./gradlew testDebugUnitTest assembleDebug lint`
Expected: verde, com o lint sem erro. Sem `google-services.json` o `assembleDebug` passa sem o plugin.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(android): canais e montagem das notificações do Firebase"
```

---

### Task 2: A permissão de notificação

**Files:**
- Modify: `MainActivity.java`, `android/app/src/main/res/values/strings.xml`
- Test: `MainActivityTest.java`

**Interfaces:**
- Produces:
  - `static boolean devePedirNotificacao(int sdk, boolean concedida, boolean jaPediu)`;
  - a flag `jaPediuNotificacao` em `SharedPreferences("push")`.

Regras:
- No Android 13+ (`sdk >= 33`), sem a permissão e sem ter pedido antes, pede `POST_NOTIFICATIONS`.
- Na instalação nova, o pedido entra junto com câmera e localização no fluxo da primeira abertura. O texto do "Antes de
  começar" ganha a frase "e as notificações para avisar das tarefas do dia e das emergências".
- Quem já tinha o app (atualização para 1.1.0) vê antes um diálogo próprio:
  - título "Avisos do Nexus Field";
  - texto "O Nexus Field avisa às 7h e às 13h o que você tem para fazer, e na hora quando surgir uma emergência. Toque em
    Permitir na próxima tela.";
  - botão "Continuar".
- Negada, não pede de novo.

- [ ] **Step 1: Testes que falham**

```java
@Test public void pedeSoNoAndroid13ComPermissaoNegadaEUmaVez() {
  assertTrue(MainActivity.devePedirNotificacao(33, false, false));
  assertFalse(MainActivity.devePedirNotificacao(32, false, false));
  assertFalse(MainActivity.devePedirNotificacao(33, true, false));
  assertFalse(MainActivity.devePedirNotificacao(33, false, true));
}
```

- [ ] **Step 2: Ver falhar; Step 3: implementar; Step 4: `./gradlew testDebugUnitTest assembleDebug lint` verde**

- [ ] **Step 5: Commit** `feat(android): permissão de notificação na primeira abertura e na atualização`

---

### Task 3: O registro do aparelho pela página

O protocolo de hoje, o que a construção e a revisão deixaram. O primeiro (o nativo guardava `registradoEm` e respondia
`jaRegistrado`, e o script pulava o registro feito há menos de 7 dias) saiu na revisão da Task 3: o "já registrado" não
sabia de QUEM era o registro, e trocar de conta sem o Sair deixava os avisos indo ao e-mail anterior.

**Files:**
- Modify: `www/casca/inicio.js` (a seção 8, "notificações"), `Mensageiro.java` e `CascaPlugin.java` (as mensagens
  novas; `push` em `ONDES_DA_PAGINA`), `MensagensDoFirebase.java` (o `onNewToken` e a marca do Sair),
  `testes/contrato.mjs` (exigir `getTokenMW` sem abrir o login, `async function sair` com o único logout da página,
  `MIDDLEWARE_URL` no topo de um script clássico e o `#s-app`)
- Create: `testes/push.test.mjs`, `PushNaCasca.java` (a conversa nativa, com o Firebase e a ponte por interface, para
  o teste de JVM)
- Test: `MensageiroTest.java`, `PushNaCascaTest.java`, `testes/canal.test.mjs`, `testes/contrato.test.mjs`

**Interfaces:**
- Mensagens:
  - script → nativo: `{t:'pushPedirToken'}`; `{t:'pushRegistrado'}` (o servidor respondeu 2xx; nunca depois do Sair);
    `{t:'pushSaiu'}` (o Sair, com token ou sem);
  - nativo → script: `{t:'pushToken', token: string|null, versao: string, avisos: boolean}`, a resposta do pedido
    (pelo `JavaScriptReplyProxy` dele), que também chega SOZINHA pela mesma ponte: no `onNewToken` com a página viva e
    na volta ao app, quando os avisos passaram a ligados ou quando o token guardado não é o que a página recebeu.
- O nativo responde `token:null` quando o Firebase não está configurado ou o token ainda não saiu. `avisos` é a
  permissão (Android 13+), as notificações do app ligadas e o canal `emergencia` não bloqueado.
- O nativo não guarda nada do registro: no `SharedPreferences("push")` ficam o último token do Firebase (`token`, para
  comparar na volta ao app) e a marca do Sair (`semConta`, que o `pushRegistrado` solta).

Regras do script:
1. Depois do `load`, espera 5 s e manda `pushPedirToken`.
2. "Logado" é a tela do App na frente (`#s-app.on`), não "o `getTokenMW` devolveu algo": antes dela, espera (o
   `MutationObserver` na classe do `#s-app`; sem ele, uma consulta de 1 s por 3 minutos), além do `online` e da volta ao
   app.
3. Registra uma vez por token em cada carga, só com `avisos` true, com o Bearer da conta de agora:
   `win.fetch(<MIDDLEWARE_URL sem a barra do fim> + '/push/registrar', {method:'POST', headers:{Authorization:'Bearer
   '+b, 'Content-Type':'application/json'}, body: JSON.stringify({token, aparelho:'android', app:'NexusField/'+versao})})`.
   - Se der 2xx, manda `pushRegistrado`.
   - Se der 404, não tenta mais nesta carga: a rota ainda não está no ar.
   - Outro erro ou sem rede: tenta no próximo `online`, no máximo 1 vez a cada 10 minutos.
   - Sem Bearer (a sessão vencida, a entrada sem sinal): tenta no próximo `online` ou na volta ao app.
   - Token null: pede o token de novo no próximo `online` ou na volta ao app, com a mesma espera de 10 minutos.
   - Um token DIFERENTE que o nativo mande depois registra de novo, uma vez; o mesmo, não.
4. Embrulha `win.sair` depois que a página o define (o script roda antes da página; detecta no `load`):
   1. manda `pushSaiu` (o nativo grava a marca `semConta`, não mostra nada até o próximo `pushRegistrado`, tira as
      notificações do app da barra e apaga o token no Firebase: é o que segura o Sair sem rede, com o servidor fora ou
      com a sessão vencida), tira os atalhos de teste (`pushAdmin:false`, Task 6) e apaga a OS pendente do toque (Task 4);
   2. manda `POST /push/esquecer {token}` com `keepalive: true` (sobrevive à navegação do logout) e chama o original
      assim que o pedido sai, sem esperar a resposta; o teto de 3 s fica para o Bearer que não vem;
   3. o segundo toque, com o primeiro em curso, recebe a mesma Promise, e nenhum registro começa depois do Sair nesta
      carga (a carga seguinte registra).
- Todo erro vira `ctx.falhou('push', e)`, sem token, Bearer nem e-mail no texto.

- [ ] **Step 1: Testes que falham** (`testes/push.test.mjs`, com jsdom e o `apoio.mjs`)

```js
test('registra uma vez por carga com o Bearer da página e o corpo do contrato', ...);
test('cada carga registra de novo com a conta atual', ...);
test('na volta do login, antes da tela do App, o getTokenMW dá a conta anterior: nada sai com ela', ...);
test('não registra sem login e tenta no próximo online', ...);
test('404: não tenta mais nesta sessão', ...);
test('outro erro ou sem rede: tenta no próximo online, no máximo 1 vez a cada 10 minutos', ...);
test('sem token do Firebase (null): nada a registrar, e o online e a volta ao app pedem de novo, no máximo 1 vez a cada 10 minutos', ...);
test('o token que chega depois da carga, ou um token diferente, registra uma vez por token', ...);
test('só registra com os avisos do app ligados; o nativo velho, sem o campo, vale como ligado', ...);
test('sair: o nativo ouve o pushSaiu antes de tudo, com token ou sem, e sem rede', ...);
test('sair: o segundo toque devolve o mesmo sair em curso, sem outro esquecer nem outro logout', ...);
test('sair: nenhum registro começa depois do Sair', ...);
test('nada de token nem e-mail nas mensagens de erro', ...);
```

Mais:
- `MensageiroTest` e `PushNaCascaTest`: o `pushPedirToken` responde com o token, a versão e os avisos; o token novo do
  Firebase e a volta ao app com os avisos ligados vão à página viva; o `pushSaiu` grava a marca, tira as notificações e
  apaga o token; o `pushRegistrado` solta a marca;
- `canal.test.mjs`: a lista do Java tem `push`, e todo tipo que o script manda o `Mensageiro` atende;
- `contrato.test.mjs`: os nomes novos exigidos.

- [ ] **Step 2: Ver falhar; Step 3: implementar; Step 4: `npm test` e Gradle verdes**

- [ ] **Step 5: Commit** `feat(casca): o aparelho registrado no servidor pela própria página, e esquecido no sair`

---

### Task 4: Abrir a OS pela notificação

**Files:**
- Modify: `MainActivity.java` (`onCreate` e `onNewIntent` leem o extra `gc_os`), `CascaPlugin.java` (fila de OS a abrir
  até a página do App carregar), `www/casca/inicio.js` (`window.__gcCascaAbrirOS`, não enumerável),
  `testes/contrato.mjs` (exigir `function setBusca` e `id="busca-os"`)
- Create: `testes/abrir-os.test.mjs`
- Test: `CascaPluginTest.java`

**Interfaces:**
- `window.__gcCascaAbrirOS(os: string) -> boolean`: põe o número no `#busca-os` e chama `setBusca(os)`. Sem esses dois,
  devolve false.
- `CascaPlugin.abrirOS(String os)` só aceita `Notificacoes.osValida`. Chama
  `evaluateJavascript("window.__gcCascaAbrirOS&&window.__gcCascaAbrirOS('<os>')")` depois do `onPageLoaded` da
  `/api/app`, com 1,5 s de folga; se a página ainda não carregou, guarda o último pedido.

- [ ] **Step 1: Testes que falham**
  - `abrir-os.test.mjs`:
    - preenche o campo e chama `setBusca` com o número;
    - sem `setBusca`, devolve false sem lançar;
    - aspas e letras não passam: a OS chega validada pelo nativo, mas o script ainda confere `^\d{1,12}$`.
  - `CascaPluginTest`:
    - a OS inválida é recusada;
    - o pedido feito antes da carga é guardado e entregue depois.
- [ ] **Step 2: Ver falhar; Step 3: implementar; Step 4: verde**
- [ ] **Step 5: Commit** `feat: o toque na emergência abre o app na OS`

---

### Task 6: Atalho de teste dos avisos, só para administrador

Decisão do dono: testar as notificações no celular por um botão no Nexus Field que só aparece para quem é Admin. O botão
é um atalho do ícone (toque longo no ícone), para a casca não desenhar nada na página e o técnico nunca o ver.

**O servidor (sessão "App de campo", já no ar na v286; não mexa):**
- `GET {MIDDLEWARE_URL}/me` com o Bearer da página → `{email, nome, papel, clusters, ...}`; `papel==='Admin'` é o
  administrador (a página usa a mesma rota em `resolverViaMe`). Nada do que vem do /me vai ao nativo além de `admin`
  true/false: nem e-mail nem nome (regra: nada de dado pessoal no nativo, no log ou no espelho).
- `POST {MIDDLEWARE_URL}/push/teste {canal?: 'emergencia'|'emergencia_silenciosa'}` com o Bearer, só Admin (403 para
  os outros). Manda um resumo e uma emergência de mentira (desde a v287, a OS 999999999, fora da faixa das OS reais)
  para os aparelhos de quem chamou; nada no Fracttal.
  Resposta 200 `{aparelhos, resumo:[...], emergencia:[...]}`.

**Files:**
- Modify: `www/casca/inicio.js` (junto do registro da Task 3, uma vez por carga e depois do login: o /me; manda ao
  nativo `{t:'pushAdmin', admin:bool}`; e `window.__gcCascaTestarAvisos(canal)`, não enumerável, que chama a rota de
  teste e devolve o resultado ao nativo com `{t:'pushTeste', ok, status, aparelhos}`), `Mensageiro.java` (os dois tipos
  novos), `MainActivity.java` (lê o extra `gc_teste` do atalho no `onCreate`/`onNewIntent`, como o `gc_os`),
  `CascaPlugin.java` (entrega o pedido de teste à página como a abertura da OS: guarda até a `/api/app` carregar),
  `strings.xml` (textos PENDENTES de aprovação: os dois rótulos curtos e longos dos atalhos e os toasts), `CLAUDE.md`
  (a regra e o porquê, curto).
- Create: `AtalhosDeTeste.java` (publica e tira os atalhos dinâmicos com `ShortcutManagerCompat`; ids fixos
  `teste_avisos` e `teste_fora_expediente`; ícone do G branco já existente ou o do app), `testes/atalho-teste.test.mjs`.
- Test: `CascaPluginTest.java` / `MensageiroTest.java` / um teste novo dos atalhos (Robolectric).

**Regras:**
- No Sair (o mesmo gancho do `/push/esquecer`), a casca tira os atalhos.
- Erro de rede, 401 ou papel que não é Admin: tira os atalhos (nunca deixa para quem não é Admin).
- O toast diz o resultado sem dado pessoal: "Aviso de teste enviado para N aparelho(s)", "Só administrador pode
  testar", "Entre no Nexus Field para testar", "Não consegui enviar o teste agora".
- O canal só pode ser um dos dois; qualquer outro valor do extra é recusado no nativo e no script.
- Uma chamada de teste por toque; dois toques seguidos em menos de 10 s mandam só uma.

- [ ] **Step 1: Testes que falham**
  - `atalho-teste.test.mjs`: o /me Admin manda `admin:true`, outro papel ou erro manda `admin:false`; o teste chama a
    rota com o Bearer e o canal certo e devolve o resultado; canal inválido não chama; sem token devolve "sem login";
    o Sair manda `admin:false`; nada de e-mail ou nome na mensagem ao nativo.
  - Java: o Admin publica os dois atalhos, o não-Admin os tira; o extra inválido é recusado; o pedido antes da carga é
    guardado e entregue depois; o toast certo para cada resposta.
- [ ] **Step 2: Ver falhar; Step 3: implementar; Step 4: verde** (`npm test` e `./gradlew testDebugUnitTest assembleDebug lint`)
- [ ] **Step 5: Commit** `feat: atalho de teste dos avisos no ícone, só para administrador`

---

### Task 5: Prévia, roteiro e versão 1.1.0

**Files:**
- Modify:
  - `docs/previas/telas.html`: seção "Notificações", com o resumo da manhã e o da tarde, a emergência (alta) e a
    silenciosa, na barra do Android com o ícone do G branco, e o diálogo "Avisos do Nexus Field";
  - `testes/previas.test.mjs`;
  - `docs/prova-no-aparelho.md`: a prova com o envio de teste do servidor (os dois canais, o toque abrindo a OS, o
    resumo de um dia com tarefa);
  - `CLAUDE.md`: o lado da casca nas notificações, o contrato com o servidor e a ordem de dependência.
- Create: `novidades/1.1.0.md`, com as linhas "- Avisos às 7h e às 13h com as tarefas do dia" e "- Aviso na hora
  quando surgir uma emergência".

- [ ] **Step 1: Testes da prévia que falham** (os textos novos do `strings.xml` aparecem na seção; exemplos com OS fictícia)
- [ ] **Step 2: Implementar; Step 3: `npm test` verde**
- [ ] **Step 4: Commit** `docs: prévia das notificações, roteiro da prova e novidades da 1.1.0`

A tag `v1.1.0` não é desta tarefa. Ela sai depois:
1. da aprovação dos textos pelo dono;
2. do `google-services.json` do dono no repositório;
3. das rotas do servidor no ar.
