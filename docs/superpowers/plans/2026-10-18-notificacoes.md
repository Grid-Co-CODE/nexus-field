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

- O site não muda. A página é usada só pelo que ela já tem: `getTokenMW()`, `MIDDLEWARE_URL`, `async function sair()`,
  `setBusca()` e `#busca-os`. O contrato com o site passa a exigir esses nomes.
- Mensagem do FCM: só dados, no formato
  `{tipo:'resumo'|'emergencia', canal:'resumo'|'emergencia'|'emergencia_silenciosa', titulo, texto, os?}`.
- Os canais têm os ids `resumo`, `emergencia` e `emergencia_silenciosa`:
  - "Resumo do dia": importância padrão;
  - "Emergências": importância alta, com som e vibração;
  - "Emergências fora do expediente": importância baixa, sem som.
- Rotas do servidor:
  - `POST {MIDDLEWARE_URL}/push/registrar` com `{token, aparelho:'android', app:'NexusField/<versionName>'}`;
  - `POST {MIDDLEWARE_URL}/push/esquecer` com `{token}`;
  - as duas com o Bearer da página.
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
    em `SharedPreferences("push")` a chave `token` e marca `registrado=false`;
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

**Files:**
- Modify: `www/casca/inicio.js` (seção nova "notificações"), `Mensageiro.java` e `CascaPlugin.java` (as mensagens
  novas; `push` em `ONDES_DA_PAGINA`), `testes/contrato.mjs` (exigir `getTokenMW`, `async function sair`,
  `MIDDLEWARE_URL`)
- Create: `testes/push.test.mjs`
- Test: `MensageiroTest.java`, `testes/canal.test.mjs`, `testes/contrato.test.mjs`

**Interfaces:**
- Mensagens:
  - script → nativo: `{t:'pushPedirToken'}` e `{t:'pushRegistrado', token}`;
  - nativo → script (resposta pelo `JavaScriptReplyProxy` do pedido):
    `{t:'pushToken', token: string|null, jaRegistrado: boolean, versao: string}`.
- O nativo responde `token:null` quando o Firebase não está configurado ou o token ainda não saiu.
- `jaRegistrado` vale true se o mesmo token foi registrado há menos de 7 dias (`SharedPreferences("push")`: `token`,
  `registradoEm`).

Regras do script:
1. Depois do `load`, espera 5 s e manda `pushPedirToken`.
2. Com `token` e `jaRegistrado:false`, chama `win.getTokenMW()`. Sem Bearer (ninguém logado), tenta de novo no próximo
   `online` ou `visibilitychange`.
3. Com Bearer, faz `win.fetch(MIDDLEWARE_URL + '/push/registrar', {method:'POST', headers:{Authorization:'Bearer
   '+b, 'Content-Type':'application/json'}, body: JSON.stringify({token, aparelho:'android', app:'NexusField/'+versao})})`.
   - Se der 2xx, manda `pushRegistrado`.
   - Se der 404, não tenta mais nesta sessão: a rota ainda não está no ar.
   - Outro erro ou sem rede: tenta no próximo `online`, no máximo 1 vez a cada 10 minutos.
4. Embrulha `win.sair` depois que a página o define (o script roda antes da página; detecta no `load`):
   1. antes de chamar o original, manda `POST /push/esquecer` com o token, com prazo de 3 s;
   2. chama o original sempre, mesmo se o `esquecer` falhar.
- Todo erro vira `ctx.falhou('push', e)`, sem token nem e-mail no texto.

- [ ] **Step 1: Testes que falham** (`testes/push.test.mjs`, com jsdom e o `apoio.mjs`)

```js
test('registra uma vez com o Bearer da página e o corpo do contrato', ...);   // fetch chamado com /push/registrar, Authorization 'Bearer X', body {token:'T', aparelho:'android', app:'NexusField/1.1.0'}; depois manda pushRegistrado
test('não registra sem login e tenta no próximo online', ...);
test('jaRegistrado: não chama a rota', ...);
test('404: não tenta mais nesta sessão', ...);
test('sem token do Firebase (null): não faz nada', ...);
test('sair: esquece o token antes e chama o original mesmo se o esquecer falhar', ...);
test('nada de token nem e-mail nas mensagens de erro', ...);
```

Mais:
- `MensageiroTest`: `pushPedirToken` responde com o token e `jaRegistrado` (com o relógio injetável para os 7 dias), e
  `pushRegistrado` grava;
- `canal.test.mjs`: a lista do Java tem `push`;
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
