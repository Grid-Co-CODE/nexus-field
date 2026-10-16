# App de Campo no Android: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir uma casca Android (Capacitor 8.5.3) que abre o App de Campo ao vivo e espelha num armazenamento
nativo a fila, os rascunhos e as fotos, além da atualização própria e do build pelo GitHub Actions. Nada muda no site.

**Architecture:**
- Um script injetado antes da página (`www/casca/inicio.js`, por `addDocumentStartJavaScript`):
  - espelha as escritas do localStorage e do IndexedDB `gc_campo_fotos` por um canal `gcCasca` (`addWebMessageListener`);
  - restaura o que a WebView perdeu;
  - troca o `window.open`.
- Um plugin Java (`CascaPlugin`) guarda o espelho em SQLite e em arquivos e monta o "retrato" que o script recebe.
- Um `Atualizador` lê o `versao.json` da última Release pública.

**Tech Stack:**
- Capacitor 8.5.3 (Android), Java 21, Gradle do template, AndroidX WebKit e Browser, SQLite do Android.
- Testes: Node 22+ (`node --test`, jsdom, fake-indexeddb) e JUnit 4 com Robolectric.
- CI: GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-16-app-campo-android-design.md` (leia antes; este plano argumenta a partir dela).

## Global Constraints

- O site atual não muda: `campo.html`, `sw.js`, `function_app.py`, App Settings, CORS ou rotas. Só leitura, e só pelo
  teste de contrato.
- `server.url` = `https://gridco-campo-mw.azurewebsites.net`: só a origem, sem caminho e sem barra no fim.
  `server.appStartPath` = `/api/app`.
- `server.allowNavigation` = só hosts de login da Microsoft. O Fracttal nunca entra na lista.
- CapacitorHttp desligado. Capacitor fixo em 8.5.3 (`@capacitor/core`, `cli`, `android`).
- O user agent termina com `GridcoCampoApp/<versionName>`.
- Pacote `br.com.gridco.campo`, nome "App de Campo Grid Co.", rótulo do ícone "Campo GridCo".
- A casca nunca envia nada da fila ao servidor. Quem envia é a página.
- O espelho nunca guarda `msal.*`, o sessionStorage, nem token. `gc_campo_ident` fica cifrado em repouso (Android
  Keystore).
- `allowBackup=false` e `dataExtractionRules` excluindo tudo em `<cloud-backup>` e `<device-transfer>`.
- Sem serviço pago, sem segredo no repositório ou no APK, sem nome de colega em arquivo versionado. pt-BR, sem emoji.
- A chave de assinatura e as senhas nunca são impressas em terminal, log ou commit.
- Na máquina: JDK em `%LOCALAPPDATA%\Programs\jdk21`, SDK em `%LOCALAPPDATA%\Android\Sdk` (API 36,
  build-tools 36.0.0). Não há emulador.

## Review Focus

1. **A página escreve enquanto a casca restaura:** os scripts da página começam antes da restauração e gravam `[]` na
   fila vazia, e o espelho registra isso. Esperado: em estado "preciso de retrato", a casca para a carga
   (`window.stop()`) e não espelha nada. Teste na Task 3.
2. **O retrato é maior que a cota do localStorage:** esperado restaurar a fila primeiro, depois o trabalho, depois o
   cache, chave por chave, e contar o que não coube, sem lançar. Teste na Task 3.
3. **O processo morre no meio dos pedaços de um vídeo:** o arquivo parcial nunca vira entrada do espelho, e na partida
   seguinte a reconciliação reenvia a chave que falta. Testes nas Tasks 4 e 5.
4. **O Fracttal no caminho normal:** a página escreve "Abrindo o Fracttal…" e depois navega. Esperado: abre a Custom
   Tab e nunca mostra a camada. Teste na Task 4.
5. **APK de atualização com SHA-256 diferente:** esperado não instalar e apagar o arquivo. Teste na Task 7.

---

## Estrutura de arquivos

```
C:\GridcoAuto\app-campo-android\
  CLAUDE.md                      # o que é, como montar, regras (pt-BR)
  package.json                   # deps fixas; scripts test/sync
  capacitor.config.json
  www/index.html                 # exigido pelo webDir; nunca é a página do App
  www/erro.html                  # server.errorPath
  www/casca/inicio.js            # o script de início (UMD: Node para testes, navegador para valer)
  testes/*.test.mjs              # node --test
  testes/contrato.mjs            # teste de contrato com o site ao vivo (CI diário)
  ferramentas/ambiente.sh        # exporta JAVA_HOME/ANDROID_HOME desta máquina
  ferramentas/versao-json.mjs    # gera o versao.json da Release
  novidades/<versão>.md          # texto das novidades de cada versão
  android/...                    # gerado por `npx cap add android`, depois editado
  android/app/src/main/java/br/com/gridco/campo/
    MainActivity.java            # registra o plugin; primeira abertura
    CascaPlugin.java             # scripts, canal, cookies, UA, renderer, histórico, Custom Tab
    Mensageiro.java              # traduz mensagens do canal em chamadas ao ArmazemEspelho
    ArmazemEspelho.java          # interface do espelho
    EspelhoSqlite.java           # implementação (SQLite + arquivos em files/espelho/)
    Cifra.java, CifraKeystore.java
    Fnv.java                     # o mesmo hash do inicio.js
    Retrato.java                 # monta o retrato leve/completo e o script
    Atualizador.java, VersaoRemota.java
  android/app/src/test/java/br/com/gridco/campo/*Test.java
  .github/workflows/montar.yml, contrato.yml
  docs/prova-no-aparelho.md      # roteiro da prova 10.3 da spec
```

---

### Task 1: Projeto base, configuração e manifesto

**Files:**
- Create: `package.json`, `capacitor.config.json`, `www/index.html`, `www/erro.html`, `.gitignore`, `CLAUDE.md`,
  `ferramentas/ambiente.sh`, `testes/configuracao.test.mjs`
- Create (gerado): `android/` por `npx cap add android`
- Modify: `android/app/build.gradle`, `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/xml/regras_dados.xml`, `android/app/src/main/res/xml/caminhos_arquivos.xml`

**Interfaces:**
- Produces:
  - `npm test` roda `node --test testes/`.
  - O `build.gradle` lê `versionCode` de `GITHUB_RUN_NUMBER` (padrão 1) e `versionName` de `VERSAO_CASCA` (padrão
    `0.0.0-dev`).
  - A assinatura release vem de `CASCA_KEYSTORE`, `CASCA_KEYSTORE_SENHA`, `CASCA_CHAVE_ALIAS` e `CASCA_CHAVE_SENHA`.
    Sem elas, o release sai sem assinar.
  - FileProvider com authority `br.com.gridco.campo.arquivos`.

- [ ] **Step 1: `git init`, `package.json` e dependências**

Dependências exatas: `@capacitor/core@8.5.3`, `@capacitor/android@8.5.3`, `@capacitor/cli@8.5.3` (dev).

Dev: `jsdom` e `fake-indexeddb`, na versão estável atual, fixa (sem `^`).

Scripts:
- `"test": "node --test testes/"`;
- `"sync": "cap sync android"`.

`.gitignore`: `node_modules/`, `android/local.properties`, `android/**/build/`, `android/.gradle/`, `*.jks`,
`*.keystore`, `android/app/src/main/assets/public/`.

- [ ] **Step 2: Escrever o teste que falha, `testes/configuracao.test.mjs`**

```js
test('server.url é só a origem, sem caminho nem barra', () => {
  assert.equal(cfg.server.url, 'https://gridco-campo-mw.azurewebsites.net');
  assert.equal(cfg.server.appStartPath, '/api/app');
});
test('allowNavigation só tem hosts da Microsoft e nunca o Fracttal', () => {
  for (const h of cfg.server.allowNavigation) assert.match(h, /(microsoftonline|live|windows)\.(com|net)$/);
  assert.ok(!cfg.server.allowNavigation.some(h => /fracttal/i.test(h)));
});
test('CapacitorHttp desligado e pacote certo', () => {
  assert.notEqual(cfg.plugins?.CapacitorHttp?.enabled, true);
  assert.equal(cfg.appId, 'br.com.gridco.campo');
  assert.equal(cfg.appName, 'App de Campo Grid Co.');
});
test('manifesto: permissões, backup desligado e FileProvider', () => {
  for (const p of ['INTERNET','ACCESS_NETWORK_STATE','CAMERA','ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION','REQUEST_INSTALL_PACKAGES'])
    assert.match(manifesto, new RegExp(`android.permission.${p}"`));
  assert.match(manifesto, /android:allowBackup="false"/);
  assert.match(manifesto, /android:dataExtractionRules="@xml\/regras_dados"/);
  assert.match(manifesto, /br\.com\.gridco\.campo\.arquivos/);
  assert.match(manifesto, /android.media.action.IMAGE_CAPTURE/);
  assert.match(manifesto, /android.media.action.VIDEO_CAPTURE/);
});
test('regras de dados excluem tudo da nuvem e da transferência', () => {
  assert.match(regras, /<cloud-backup>[\s\S]*<exclude domain="root"/);
  assert.match(regras, /<device-transfer>[\s\S]*<exclude domain="root"/);
});
```

(`cfg` = JSON do `capacitor.config.json`; `manifesto` e `regras` = texto dos dois XML.)

- [ ] **Step 3: Rodar e ver falhar**

Run: `npm test`
Expected: FAIL (arquivos ainda não existem)

- [ ] **Step 4: Criar a configuração, `www/` e o Android**

`capacitor.config.json`:
- `appId`, `appName`, `webDir: "www"`;
- `server.url`, `server.appStartPath`, `server.errorPath: "erro.html"`;
- `server.allowNavigation: ["login.microsoftonline.com", "login.live.com", "login.windows.net"]`. A prova no aparelho
  completa a lista pelo log `GcCasca`.

`www/erro.html` (texto exato; cor só em variáveis CSS, fundo `#090d18` e texto claro):
- título "Sem internet";
- "Abra o App de Campo uma vez com internet para ele ficar guardado neste aparelho.";
- "Seus registros estão guardados neste aparelho. O App volta quando houver internet.";
- botão "Tentar de novo", que faz `location.href = 'https://gridco-campo-mw.azurewebsites.net/api/app'`.

Depois:
- `npx cap add android`;
- no manifesto: as permissões do teste, `<uses-feature android:name="android.hardware.location.gps" android:required="false"/>`,
  o `<queries>` com as duas ações de captura, `allowBackup="false"`, `dataExtractionRules`, `fullBackupContent="false"`
  e o `<provider>` FileProvider apontando para `@xml/caminhos_arquivos` (`<cache-path name="atualizacao" path="atualizacao/"/>`);
- `strings.xml` com `app_name` = "App de Campo Grid Co." e o rótulo do launcher "Campo GridCo";
- no `build.gradle`: `versionCode`, `versionName` e `signingConfigs.release` pelas variáveis da interface;
  `implementation "androidx.browser:browser:<estável atual>"`; `testImplementation` de JUnit 4 e Robolectric (a versão
  estável que suporte o compileSdk do template);
- ícone: gere os mipmaps a partir de `https://gridco-campo-mw.azurewebsites.net/api/icon-512.png` (GET de leitura),
  com o fundo do ícone adaptativo `#191528`.

`ferramentas/ambiente.sh` exporta `JAVA_HOME="$LOCALAPPDATA/Programs/jdk21"` e `ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"`
(convertidos com `cygpath`) e escreve o `android/local.properties` com `sdk.dir`.

`CLAUDE.md`, curto:
- o que é;
- link para a spec e este plano;
- como montar (`source ferramentas/ambiente.sh && npx cap sync android && (cd android && ./gradlew testDebugUnitTest assembleDebug)`);
- as Global Constraints em uma linha cada.

- [ ] **Step 5: Rodar e ver passar; montar o APK de depuração**

Run: `npm test` → PASS.

Run: `source ferramentas/ambiente.sh && npx cap sync android && cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL` e `android/app/build/outputs/apk/debug/app-debug.apk` existe.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: projeto Capacitor 8.5.3 da casca do App de Campo (configuração, manifesto, erro offline)"
```

---

### Task 2: Script de início, parte 1: classificação e espelho do localStorage

**Files:**
- Create: `www/casca/inicio.js`, `testes/apoio.mjs`, `testes/espelho-ls.test.mjs`

**Interfaces:**
- Produces, em `www/casca/inicio.js`:
  - no Node, `module.exports = { instalar, classificar, fnv1a32, CHAVE_FILA, SENTINELA_LS, BANCO_SENTINELA }`;
  - no navegador, roda `instalar(window, window.gcCasca, window.__GC_RETRATO__ || {})`;
  - `instalar(win, canal, retrato) -> { pronto: Promise<void>, descarregar(): void }`. O `descarregar()` envia já o que
    está agrupado;
  - `classificar(chave: string) -> 'fila' | 'trabalho' | 'cache' | null`;
  - `fnv1a32(texto: string) -> string`: FNV-1a 32 bits sobre as unidades UTF-16 (`h=0x811c9dc5; h^=c; h=Math.imul(h,0x01000193)>>>0`),
    devolvido como 8 dígitos hex minúsculos;
  - `CHAVE_FILA = 'gc_campo_queue'`, `SENTINELA_LS = 'gc_casca_sentinela'`, `BANCO_SENTINELA = 'gc_casca'`.
- Mensagens ao canal (`canal.postMessage(JSON.stringify(m))`): `{t:'ls', k, v}` e `{t:'lsDel', k}`.
- `testes/apoio.mjs`: `criarJanela({retrato}) -> { win, canal, enviados: object[] }`. É uma janela falsa com uma classe
  `Storage` própria (Map; `getItem`, `setItem`, `removeItem`, `key`, `length`; cota configurável que lança
  `QuotaExceededError`), `localStorage`, `sessionStorage`, timers falsos controláveis (`avancar(ms)`), `stop()`
  espionado, e IndexedDB/DOM a partir das Tasks 3 e 4.

- [ ] **Step 1: Escrever os testes que falham**

```js
test('classificar segue a lista da spec', () => {
  assert.equal(classificar('gc_campo_queue'), 'fila');
  for (const k of ['gc_fech_draft','gc_ronda_x@y_2026-10-16_U1','gc_apr_rasc_9','gc_campo_aprdia','gc_ext_rasc_3',
    'gc_zl_pend','gc_zl_draft','gc_v1_os_chamado','gc_campo_status','gc_v1_os_voltou','gc_fb_draft',
    'gc_v1_fora_local','gc_v1_pt_st','gc_campo_geo_ini','gc_campo_ident']) assert.equal(classificar(k), 'trabalho', k);
  for (const k of ['gc_subs_1','gc_campo_apr','gc_campo_aprdia_f','gc_v1_pt_def','gc_ronda_ctx','gc_ext_ctx',
    'gc_zl_modelos','gc_zl_serv','gc_v1_almox_2','gc_v1_minhas_prev']) assert.equal(classificar(k), 'cache', k);
  for (const k of ['msal.token.keys.x','gc_erros_js','gc_vivo','gc_tela','gc_campo_tema','gc_casca_sentinela'])
    assert.equal(classificar(k), null, k);
});
test('fila vai ao nativo na hora, depois de gravar no real', ...);   // setItem → real tem o valor E enviados[0] = {t:'ls',k:'gc_campo_queue',v}
test('trabalho e cache são agrupados por chave em 400 ms; vence a última', ...); // 3 setItem da mesma chave → nada antes de 400 ms, 1 mensagem com o último valor depois
test('cota estourada relança e não espelha', ...);   // cota pequena → setItem lança QuotaExceededError, enviados vazio
test('sessionStorage e msal.* nunca vão ao nativo', ...);
test('removeItem de chave espelhada manda lsDel', ...);
test('descarregar e visibilitychange hidden mandam o agrupado na hora', ...);
test('fnv1a32 é estável', () => { assert.equal(fnv1a32(''), '811c9dc5'); assert.equal(fnv1a32('a'), 'e40c292c'); });
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `node --test testes/espelho-ls.test.mjs`
Expected: FAIL (`instalar` não existe)

- [ ] **Step 3: Implementar a classificação e o espelho**

Em `inicio.js`:
- guarda `win.localStorage` e os originais de `Storage.prototype.setItem` e `removeItem` no início;
- embrulha os dois só quando `this === lsReal`;
- chama o original primeiro (exceção sobe intacta) e depois espelha conforme `classificar`;
- agrupa num `Map` com um temporizador de 400 ms por lote;
- ouve `visibilitychange` (quando `hidden`) e `pagehide` para descarregar;
- todo erro do espelho é engolido e vira `{t:'erro', onde, msg}`. O espelho nunca pode quebrar a página.

- [ ] **Step 4: Rodar e ver passar**

Run: `npm test`
Expected: PASS

- [ ] **Step 5: Mutação**

Inverta a ordem (espelhar antes do original) e confirme que o teste da cota falha. Desfaça.

- [ ] **Step 6: Commit**

```bash
git add www/casca/inicio.js testes/ && git commit -m "feat(casca): espelho de escrita do localStorage com a classificação da spec"
```

---

### Task 3: Script de início, parte 2: sentinela e restauração do localStorage

**Files:**
- Modify: `www/casca/inicio.js`
- Create: `testes/restaurar-ls.test.mjs`

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - Retrato leve (sempre): `{ temEspelho: boolean, filaHashes: string[] /* mais nova primeiro, até 3 */, idbChaves: string[] }`.
  - Retrato completo (depois de pedido): o leve mais `{ modo: 'restaurar', ls: {[chave]: string}, fila: string|null }`.
  - Mensagens novas:
    - `{t:'precisoRetrato', motivo:'perda'|'fila'}`;
    - `{t:'restaurado', chaves: number, naoCouberam: string[], fila: boolean}`;
    - `{t:'sentinela'}`, mandada depois que o script grava a sentinela pela primeira vez, para o nativo marcar
      `temEspelho`.

Regras (do começo de `instalar`, antes de qualquer embrulho):

| Situação | Ação |
|---|---|
| `retrato.modo === 'restaurar'` | grava pelos originais, sem espelhar: primeiro a fila, depois `trabalho`, depois `cache`, chave por chave, pegando a cota; grava a sentinela; manda `restaurado` |
| sem sentinela e `temEspelho` | `win.stop()`, espelho desligado na carga inteira, manda `precisoRetrato {motivo:'perda'}` |
| sem sentinela e sem `temEspelho` | instalação nova: grava a sentinela, manda `sentinela` |
| com sentinela e `fnv1a32(fila atual)` igual a `filaHashes[1]` ou `[2]` | `win.stop()`, espelho desligado, `precisoRetrato {motivo:'fila'}`. No modo restaurar com esse motivo, só a fila é regravada |
| com sentinela e fila atual não nula, com hash fora de `filaHashes` | a WebView está à frente: manda `{t:'ls', k:'gc_campo_queue', v: atual}` |

- [ ] **Step 1: Escrever os testes que falham**

```js
test('instalação nova grava a sentinela e não para a carga', ...);          // stop não chamado; enviados contém {t:'sentinela'}
test('perda: para a carga, pede o retrato e não espelha nada', ...);         // win.stop chamado; depois disso, setItem('gc_campo_queue','[]') NÃO gera mensagem ls
test('restaurar devolve fila, trabalho e cache e grava a sentinela', ...);
test('restaurar com cota curta grava a fila primeiro e lista o que não coube', ...); // restaurado.naoCouberam contém as chaves de cache
test('fila atrasada: hash bate com versão antiga → pede retrato motivo fila', ...);
test('fila à frente do espelho: espelha a atual', ...);
test('restaurar não ecoa as próprias gravações ao nativo', ...);
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `node --test testes/restaurar-ls.test.mjs` → FAIL

- [ ] **Step 3: Implementar a tabela acima em `inicio.js`**

- [ ] **Step 4: Rodar a suíte inteira**

Run: `npm test` → PASS

- [ ] **Step 5: Mutação**

Tire o `win.stop()` do caso "perda" e confirme que o teste "perda" falha. Desfaça.

- [ ] **Step 6: Commit**

```bash
git commit -am "feat(casca): sentinela, versões da fila e restauração do localStorage antes da página"
```

---

### Task 4: Script de início, parte 3: IndexedDB das fotos e `window.open`

**Files:**
- Modify: `www/casca/inicio.js`, `testes/apoio.mjs` (IndexedDB de `fake-indexeddb` e `document` do `jsdom`)
- Create: `testes/espelho-idb.test.mjs`, `testes/janela.test.mjs`

**Interfaces:**
- Consumes: Tasks 2 e 3.
- Produces, mensagens novas:
  - Script → nativo:
    - `{t:'idbPut', k, tipo:'texto'|'json', v}`;
    - `{t:'idbPedaco', k, id, i, n, mime, b64}`, em pedaços de 524288 bytes; o `id` é único por gravação;
    - `{t:'idbDel', k}`;
    - `{t:'idbPedir'}`;
    - `{t:'fora', url}`.
  - Nativo → script (`canal.onmessage`, `event.data` em JSON):
    - `{t:'idbItem', k, tipo, v}`;
    - `{t:'idbPedaco', k, i, n, mime, b64}`;
    - `{t:'idbFim', n}`.
  - O `pronto` do `instalar` resolve quando a checagem ou a restauração do IndexedDB termina. Se o nativo não terminar,
    resolve depois de 15000 ms.

Regras do IndexedDB (banco `gc_campo_fotos`, store `fotos`, sem keyPath):
- **Espelho:** embrulha `IDBObjectStore.prototype.put(valor, chave)` e `delete(chave)` só desse banco e store. Junta as
  operações na transação e envia no evento `complete` (por `addEventListener`, sem tocar no `oncomplete` da página).
  - string → `texto`;
  - Blob/File → pedaços com `blob.slice().arrayBuffer()` em base64;
  - outro valor → `json`.
- **Checagem na partida:**
  1. `indexedDB.databases()`; se não houver `gc_casca`:
     - com `temEspelho`, manda `idbPedir`, grava cada item recebido no banco real (cria a store, versão 1, se faltar),
       sem espelhar, e cria `gc_casca`;
     - sem `temEspelho`, só cria `gc_casca`.
  2. Com `gc_casca` presente, faz a **reconciliação**:
     - chave do banco real que falta em `idbChaves` → reenvia;
     - chave de `idbChaves` que falta no banco real → manda `idbDel`.
- **`indexedDB.open('gc_campo_fotos', …)`** devolve um pedido de mentira até o `pronto`. É um objeto com `onsuccess`,
  `onerror`, `onupgradeneeded`, `result` e `error`; depois abre o real e repassa cada evento a ele. Outros bancos passam
  direto.

Regras do `window.open(url, nome)`:
- `nome` começando por `msal` ou `url` de host `login.microsoftonline.com`/`login.live.com`/`login.windows.net` →
  `null`.
- Qualquer outro → janela de mentira:
  - `document.open`, `write`, `writeln` e `close` acumulam o HTML;
  - `location.href =`, `location.assign()`, `location.replace()` e `win.location = url` → manda `{t:'fora', url}` e
    marca "navegou";
  - `url` não vazio na chamada → manda `fora` na hora;
  - `close()` → `closed = true`.
- 1000 ms depois, sem "navegou" e com HTML acumulado → mostra a camada:
  - `div#gc-casca-aviso` fixa, tela cheia;
  - um `iframe` com `srcdoc` = HTML e `sandbox=""`;
  - um botão "Fechar" que remove a camada;
  - estilo só inline, sem tocar no CSS da página.

- [ ] **Step 1: Escrever os testes que falham**

```js
test('put de texto espelha só depois do complete', ...);
test('put abortado não espelha', ...);
test('put de Blob de 1,2 MB vai em 3 pedaços com o mesmo id', ...);   // n === 3, mime preservado
test('delete espelha idbDel', ...);
test('outro banco não é espelhado', ...);
test('perda do IndexedDB: open da página espera a restauração e acha as fotos', ...); // página abre gc_campo_fotos, get('f_1') devolve o valor vindo de idbItem
test('restauração não ecoa', ...);
test('reconciliação reenvia chave que falta e apaga a que sobrou', ...);
test('sem resposta do nativo, pronto resolve em 15 s e o open segue', ...);
test('window.open de login devolve null', () => { assert.equal(win.open('about:blank', 'msal.abc'), null); });
test('Fracttal normal: escreve, navega, abre fora e não mostra camada', ...); // enviados contém {t:'fora',url}; depois de 1000 ms não há #gc-casca-aviso
test('Fracttal com erro: escreve e não navega → camada com o HTML e botão Fechar', ...);
test('window.open com url de fora manda fora na hora', ...);
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `node --test testes/espelho-idb.test.mjs testes/janela.test.mjs` → FAIL

- [ ] **Step 3: Implementar em `inicio.js`**

- [ ] **Step 4: Rodar a suíte inteira**

Run: `npm test` → PASS

- [ ] **Step 5: Mutação**

Espelhe no `put` em vez do `complete` e confirme que "put abortado não espelha" falha. Desfaça.

- [ ] **Step 6: Conferir no `campo.html`, só leitura**

As chamadas que estes embrulhos atendem:
- `window.open('','_blank')` e o fluxo `naAba` em ~4527-4593;
- `loginPopup` → `popup_window_error` → `loginRedirect` em ~4932-4944;
- `idb()` e `idbPut/Get/Del/Keys` em ~3786-3805;
- nenhuma faxina do localStorage (~2202-2221, ~5036-5068) apaga `gc_casca_sentinela`.

Anote as linhas conferidas na mensagem do commit.

- [ ] **Step 7: Commit**

```bash
git commit -am "feat(casca): espelho e restauração do IndexedDB das fotos, e window.open para login e Fracttal"
```

---

### Task 5: Espelho nativo (Java): armazém, cifra, hash e retrato

**Files:**
- Create: `ArmazemEspelho.java`, `EspelhoSqlite.java`, `Cifra.java`, `CifraKeystore.java`, `Fnv.java`, `Retrato.java`,
  todos em `android/app/src/main/java/br/com/gridco/campo/`
- Test: `android/app/src/test/java/br/com/gridco/campo/EspelhoSqliteTest.java`, `FnvTest.java`, `RetratoTest.java`

**Interfaces:**
- Produces:
  - `Fnv.hash(String texto) -> String`: igual ao `fnv1a32` do script.
  - `interface Cifra { byte[] cifrar(String texto); String decifrar(byte[] dados); }`. A `CifraKeystore` usa
    AES/GCM/NoPadding com a chave `gc_casca_ident` no AndroidKeyStore e guarda o IV junto do texto cifrado.
  - `interface ArmazemEspelho` com:
    - `void gravarLs(String k, String v)`, `void apagarLs(String k)`;
    - `void gravarIdbTexto(String k, String tipo, String v)`;
    - `void receberPedaco(String k, String id, int i, int n, String mime, byte[] dados)`;
    - `void apagarIdb(String k)`;
    - `void marcarSentinela()`, `boolean temEspelho()`;
    - `List<String> filaHashes()`, `String filaMaisNova()`, `Map<String,String> lsTudo()`, `List<String> idbChaves()`;
    - `void paraCadaIdb(ItemIdb consumidor)`, com
      `interface ItemIdb { void aceitar(String k, String tipo, String mime, String texto, File arquivo); }`.
  - `EspelhoSqlite(Context ctx, Cifra cifra)`:
    - banco `espelho.db` com as tabelas `ls(chave PK, valor BLOB, cifrado INT)`, `fila(id AUTOINCREMENT, valor TEXT, hash TEXT)`,
      `idb(chave PK, tipo, texto, arquivo, mime)` e `meta(k PK, v)`;
    - arquivos em `files/espelho/idb/<sha1 da chave>.bin`;
    - partes em `files/espelho/tmp/<id>.parte`.
  - `Retrato.leve(ArmazemEspelho) -> JSONObject`, `Retrato.completo(ArmazemEspelho, String motivo) -> JSONObject`,
    `Retrato.script(JSONObject retrato, String inicioJs) -> String` (`"window.__GC_RETRATO__=" + json + ";\n" + inicioJs`).

Regras:
- `gravarLs` da `gc_campo_queue` insere uma versão nova na `fila` e poda para as 3 mais novas.
- `apagarLs` da fila apaga as versões.
- `gc_campo_ident` grava cifrado; `lsTudo()` devolve decifrado.
- Chave `msal.*` ou `gc_casca_sentinela` é recusada em silêncio (defesa em profundidade).
- Um pedaço só vira entrada quando `i == n-1` e o total de pedaços do `id` confere; até lá, nada aparece em
  `idbChaves()`. Partes com mais de 24 h são apagadas na abertura.
- `completo(...,'fila')` leva só a `fila`, com `ls` vazio.

- [ ] **Step 1: Escrever os testes que falham** (Robolectric; `Cifra` falsa nos testes, com XOR e prefixo)

```java
@Test public void fnvIgualAoJs() { assertEquals("811c9dc5", Fnv.hash("")); assertEquals("e40c292c", Fnv.hash("a")); }
@Test public void filaGuardaTresVersoesMaisNovaPrimeiro() { /* grava v1..v4 → filaHashes() == [hash(v4),hash(v3),hash(v2)], filaMaisNova()=="v4" */ }
@Test public void identFicaCifradoEmRepouso() { /* gravarLs("gc_campo_ident","{...}") → valor cru na tabela != texto; lsTudo() devolve o texto */ }
@Test public void msalNuncaEntra() { /* gravarLs("msal.x","y") → lsTudo() vazio */ }
@Test public void pedacosSoViramEntradaNoFim() { /* 3 pedaços; depois do 2º idbChaves() vazio; depois do 3º contém k e o arquivo tem os bytes concatenados */ }
@Test public void parteOrfaMaisVelhaQue24hEApagada() { ... }
@Test public void retratoLeveECompleto() { /* leve sem "ls"; completo("perda") com ls e fila; completo("fila") só fila */ }
@Test public void temEspelhoSoDepoisDaSentinela() { ... }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `source ferramentas/ambiente.sh && cd android && ./gradlew testDebugUnitTest`
Expected: FAIL (classes não existem)

- [ ] **Step 3: Implementar as classes**

- [ ] **Step 4: Rodar e ver passar**

Run: `./gradlew testDebugUnitTest` → PASS

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(android): espelho nativo em SQLite e arquivos, com cifra da identidade e retrato"
```

---

### Task 6: Plugin, canal e primeira abertura

**Files:**
- Create: `Mensageiro.java`, `CascaPlugin.java`
- Modify: `MainActivity.java`
- Test: `MensageiroTest.java`

**Interfaces:**
- Consumes: Task 5 (`ArmazemEspelho`, `Retrato`, `Fnv`) e o protocolo das Tasks 2 a 4.
- Produces:
  - `Mensageiro(ArmazemEspelho a, Acoes acoes)` com `boolean tratar(String json)`.
  - `interface Acoes { void precisoRetrato(String motivo); void restaurado(JSONObject info); void idbPedir(); void abrirFora(String url); void erro(String onde, String msg); }`.
  - O `tratar` devolve `false` para JSON inválido ou `t` desconhecido, sem lançar.
  - `CascaPlugin` (`@CapacitorPlugin(name = "Casca")`).

`CascaPlugin.load()`, nesta ordem (conferir no Capacitor 8.5.3 que o `load()` roda antes do primeiro `loadUrl`):
1. `ORIGEM = bridge.getServerUrl()`.
2. Sem `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)` → diálogo "Atualize o Android System WebView" (texto
   abaixo) e para por aí.
3. `CookieManager.setAcceptThirdPartyCookies(webView, true)`.
4. User agent `+ " GridcoCampoApp/" + BuildConfig.VERSION_NAME`.
5. `addWebMessageListener(webView, "gcCasca", Set.of(ORIGEM), ...)`: aceita só `isMainFrame` e `sourceOrigin` igual a
   `ORIGEM`, e repassa ao `Mensageiro`.
6. Registra o script leve: `Retrato.script(Retrato.leve(a), <asset public/casca/inicio.js>)`, com
   `addDocumentStartJavaScript` restrito a `ORIGEM`, e guarda o `ScriptHandler`.
7. `bridge.addWebViewListener`:
   - `onRenderProcessGone` → `activity.recreate()` e `return true`;
   - `onPageLoaded` com URL começando por `ORIGEM + "/api/app"` → `webView.clearHistory()`.

`Acoes`:
- `precisoRetrato(m)`, uma vez por partida: remove o handler e registra `Retrato.script(Retrato.completo(a, m), js)`.
  Depois `webView.reload()`.
- `restaurado`: remove e volta ao leve.
- `idbPedir`: percorre `paraCadaIdb` e responde pelo `JavaScriptReplyProxy` com `idbItem`/`idbPedaco` (mesmo tamanho de
  pedaço) e `idbFim`.
- `abrirFora(url)`: Custom Tab (`CustomTabsIntent`).
- `erro`: `Log.w("GcCasca", ...)`.
- `shouldOverrideLoad(Uri)`: em debug, loga a URL em `GcCasca` e devolve `null`, mantendo o comportamento padrão.

`MainActivity`:
- `registerPlugin(CascaPlugin.class)` antes do `super.onCreate`.
- Depois dele, a primeira abertura:
  - sem `CAMERA` ou `ACCESS_FINE_LOCATION` → diálogo "Antes de começar" e `requestPermissions(CAMERA, FINE, COARSE)`;
  - no resultado, com COARSE e sem FINE → diálogo "Localização precisa desligada";
  - com câmera ou localização negadas → "Permissões necessárias".

**Textos exatos** (vão à prévia da Task 8):

| Diálogo | Título | Texto | Botões |
|---|---|---|---|
| Primeira abertura | Antes de começar | O App de Campo usa a câmera para as fotos das OS e das rondas, e a localização precisa para registrar onde o serviço começou e terminou. | Continuar |
| Aproximada | Localização precisa desligada | Com a localização aproximada, a posição vem com erro de quilômetros e o App não consegue registrar o início e o fim do serviço. Nos ajustes, toque em Permissões > Localização e ligue Usar localização precisa. | Abrir ajustes / Agora não |
| Negadas | Permissões necessárias | Sem a câmera e a localização, o App não consegue registrar as OS. Nos ajustes, toque em Permissões e libere Câmera e Localização. | Abrir ajustes / Agora não |
| WebView antiga | Atualize o Android System WebView | O App de Campo precisa de uma versão mais nova do componente que mostra as telas. Atualize o Android System WebView na Play Store e abra o App de novo. | Abrir a Play Store |

"Abrir ajustes" = `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` do pacote. "Abrir a Play Store" =
`market://details?id=com.google.android.webview`.

- [ ] **Step 1: Escrever os testes que falham** (`MensageiroTest`, com `ArmazemEspelho` e `Acoes` falsos)

```java
@Test public void lsVaiAoArmazem() { assertTrue(m.tratar("{\"t\":\"ls\",\"k\":\"gc_fech_draft\",\"v\":\"x\"}")); assertEquals("x", fake.ls.get("gc_fech_draft")); }
@Test public void pedacoDecodificaBase64() { ... }
@Test public void precisoRetratoChamaAcao() { ... }
@Test public void foraChamaAbrirFora() { ... }
@Test public void jsonInvalidoOuTipoDesconhecidoDevolveFalse() { assertFalse(m.tratar("{")); assertFalse(m.tratar("{\"t\":\"xyz\"}")); }
@Test public void sentinelaMarcaEspelho() { ... }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./gradlew testDebugUnitTest` → FAIL

- [ ] **Step 3: Implementar `Mensageiro`, `CascaPlugin` e `MainActivity`**

- [ ] **Step 4: Rodar os testes e montar**

Run: `./gradlew testDebugUnitTest assembleDebug` → PASS e `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(android): plugin da casca (script de início, canal, cookies, UA, renderer, Custom Tab) e primeira abertura"
```

---

### Task 7: Atualização da casca

**Files:**
- Create: `VersaoRemota.java`, `Atualizador.java`
- Modify: `CascaPlugin.java` (chama o `Atualizador` no `load()` e no `handleOnResume()`)
- Test: `AtualizadorTest.java`

**Interfaces:**
- Produces:
  - `VersaoRemota.ler(String json) -> VersaoRemota`, com os campos `int versionCode`, `String versionName`, `String apk`,
    `String sha256` e `List<String> novidades`. Lança `IllegalArgumentException` se faltar campo ou se `apk` não for
    `https://github.com/Grid-Co-CODE/app-campo-android/releases/download/`.
  - `static boolean Atualizador.devoChecar(long agoraMs, long ultimaMs)`: verdadeiro se a última foi há 6 h
    (21600000 ms) ou mais.
  - `static boolean Atualizador.confere(File apk, String sha256)`.
  - `URL_VERSAO = "https://github.com/Grid-Co-CODE/app-campo-android/releases/latest/download/versao.json"`.

Fluxo, numa thread:
1. Com `devoChecar`, faz o GET do `URL_VERSAO` (segue redirecionamento) e grava a hora em `SharedPreferences`.
2. Se `versionCode > BuildConfig.VERSION_CODE` → diálogo.
3. "Atualizar agora" → baixa para `cache/atualizacao/app-campo.apk`.
4. Se não `confere` → apaga o arquivo e mostra o toast.
5. Sem `canRequestPackageInstalls()` → diálogo de permissão, abre `ACTION_MANAGE_UNKNOWN_APP_SOURCES` e tenta de novo
   no próximo resume.
6. Abre o instalador: `ACTION_VIEW` com o URI do FileProvider, tipo `application/vnd.android.package-archive` e
   `FLAG_GRANT_READ_URI_PERMISSION`.

Falha de rede: silêncio, tenta de novo depois de 6 h.

**Textos exatos:**

| Momento | Título | Texto | Botões |
|---|---|---|---|
| Versão nova | Nova versão do App de Campo | Versão {versionName}. Novidades: uma linha por item, começando por "- " | Atualizar agora / Depois |
| Permissão | Permitir a atualização | Para instalar a versão nova, libere Instalar apps desconhecidos para o App de Campo e volte para cá. | Abrir ajustes |
| SHA errado (toast) | | Não consegui conferir o arquivo da atualização. Tento de novo mais tarde. | |

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test public void leVersaoValida() { ... assertEquals(12, v.versionCode); }
@Test(expected = IllegalArgumentException.class) public void recusaApkDeOutroEndereco() { ... }
@Test public void checaSoDepoisDeSeisHoras() { assertFalse(Atualizador.devoChecar(21_599_999L, 0L)); assertTrue(Atualizador.devoChecar(21_600_000L, 0L)); }
@Test public void shaErradoNaoConfere() { /* arquivo temporário com "abc" e sha de "abd" → false */ }
@Test public void shaCertoConfere() { ... }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./gradlew testDebugUnitTest` → FAIL

- [ ] **Step 3: Implementar e ligar ao plugin**

- [ ] **Step 4: Rodar e montar**

Run: `./gradlew testDebugUnitTest assembleDebug` → PASS

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(android): atualização da casca pelo versao.json da Release, com SHA-256 e permissão de instalar"
```

---

### Task 8: Prévias das telas novas

**Files:**
- Create: `docs/previas/telas.html`, uma página só, fora do APK

**Interfaces:**
- Consumes: os textos exatos das Tasks 1, 4, 6 e 7.

- [ ] **Step 1: Montar `docs/previas/telas.html`**

Mostra, em molduras de celular de 360 px:
- os quatro diálogos da Task 6, no estilo do diálogo Material padrão do Android;
- os três da Task 7;
- o `erro.html` real, num iframe;
- a camada do Fracttal da Task 4, com um HTML de exemplo de "sessão expirou".

- [ ] **Step 2: Conferir no navegador**

Abra em desktop e em 375 px, sem rolagem horizontal.

- [ ] **Step 3: Publicar e pedir aprovação**

Publique como Artifact privado e peça ao Levi a aprovação dos textos. Ajuste o que ele pedir nos arquivos-fonte das
Tasks 1, 4, 6 e 7, e não só na prévia.

- [ ] **Step 4: Commit**

```bash
git commit -am "docs: prévias das telas novas da casca"
```

---

### Task 9: GitHub Actions, contrato com o site e Release

**Files:**
- Create: `.github/workflows/montar.yml`, `.github/workflows/contrato.yml`
- Create: `testes/contrato.mjs`, `testes/contrato.test.mjs`, `ferramentas/versao-json.mjs`,
  `testes/versao-json.test.mjs`, `novidades/1.0.0.md`

**Interfaces:**
- Produces:
  - `conferirContrato(html: string) -> string[]`, exportada de `testes/contrato.mjs`, que devolve a lista do que faltou
    (vazia = ok). Rodada direto (`node testes/contrato.mjs`), faz um GET em
    `https://gridco-campo-mw.azurewebsites.net/api/app` e sai com 1 se faltar algo.
  - `montarVersaoJson({versionCode, versionName, sha256, novidadesMd}) -> object`, exportada de
    `ferramentas/versao-json.mjs`, com `apk = https://github.com/Grid-Co-CODE/app-campo-android/releases/download/v<versionName>/app-campo.apk`
    e `novidades` = as linhas `- ` do Markdown, sem o `- `.

O contrato exige no HTML:
- `'gc_campo_fotos'`, `'fotos'`, `'gc_campo_queue'`;
- cada chave exata e prefixo da classificação da Task 2;
- `window.open('','_blank')`, `loginRedirect`, `'/api/app'`;
- `msal-browser` com `2.38.1`.

- [ ] **Step 1: Escrever os testes que falham**

```js
test('contrato passa com HTML que tem tudo', () => assert.deepEqual(conferirContrato(htmlCompletoFicticio), []));
test('contrato aponta o que sumiu', () => assert.ok(conferirContrato(htmlSemFotos).includes("'gc_campo_fotos'")));
test('versao.json aponta para a Release da versão', () => {
  const v = montarVersaoJson({versionCode: 7, versionName: '1.0.0', sha256: 'ab', novidadesMd: '- Primeira versão'});
  assert.equal(v.apk, 'https://github.com/Grid-Co-CODE/app-campo-android/releases/download/v1.0.0/app-campo.apk');
  assert.deepEqual(v.novidades, ['Primeira versão']);
});
```

(HTMLs fictícios montados no teste. O `campo.html` nunca entra no repositório.)

- [ ] **Step 2: Rodar, ver falhar, implementar, ver passar**

Run: `npm test` → FAIL → implementar → PASS

- [ ] **Step 3: Rodar o contrato contra o site ao vivo, uma vez**

Run: `node testes/contrato.mjs` (GET de leitura)
Expected: sai com 0. Se sair com 1, o site já mudou: pare e confira a classificação com o `campo.html` vigente.

- [ ] **Step 4: Escrever os workflows**

`montar.yml`:
- `on: push, pull_request, tags v*`;
- ubuntu-latest, `actions/checkout`, `actions/setup-node` (22) com `npm ci` e `npm test`;
- `actions/setup-java` (temurin 21), `npx cap sync android`, `./gradlew testDebugUnitTest assembleDebug`, e o APK de
  depuração como artifact.

Job `release`, só em tag `v*`, no ambiente `release`:
1. `VERSAO_CASCA=${GITHUB_REF_NAME#v}`.
2. Decodifica o secret `CASCA_KEYSTORE_B64` para `$RUNNER_TEMP/casca.jks`, sem `echo`.
3. Exporta `CASCA_KEYSTORE`, `CASCA_KEYSTORE_SENHA`, `CASCA_CHAVE_ALIAS` e `CASCA_CHAVE_SENHA` dos secrets.
4. `./gradlew assembleRelease`; copia para `app-campo.apk` e calcula o `sha256sum`.
5. `node ferramentas/versao-json.mjs` gera o `versao.json` com `versionCode=$GITHUB_RUN_NUMBER` e
   `novidades/$VERSAO_CASCA.md`.
6. `gh release create "$GITHUB_REF_NAME" app-campo.apk versao.json --notes-file novidades/$VERSAO_CASCA.md`.

Permissões: `contents: write` só no job `release`.

`contrato.yml`:
- `schedule` diário (`0 11 * * *`) e `workflow_dispatch`;
- roda `node testes/contrato.mjs`;
- na falha, `gh issue create --label contrato` se não houver issue aberta com esse rótulo (`issues: write`).

- [ ] **Step 5: Commit**

```bash
git commit -am "ci: montagem, Release assinada com versao.json e contrato diário com o site"
```

---

### Task 10: Chave de assinatura, repositório público e primeira Release

**Files:**
- Create: `docs/prova-no-aparelho.md` (o roteiro 10.3 da spec, em caixas de seleção, com o usuário e a OS de teste do
  PCM como campos a preencher, sem nome)
- Modify: `CLAUDE.md`, com o estado e onde está a cópia da chave (o lugar, nunca a senha)

- [ ] **Step 1: Gerar a chave fora do repositório e do OneDrive**

Pasta: `C:\GridcoAuto\_segredos\app-campo-android\`. Senhas aleatórias de 32 caracteres com o `secrets` do Python,
escritas em `senhas.txt` nessa pasta, sem imprimir.

Run: `keytool -genkeypair -keystore casca.jks -alias campo -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=App de Campo, O=Grid Co., C=BR"`,
com as senhas por variável de ambiente lida do arquivo.

Expected: `casca.jks` existe. Mostre só o SHA-256 do certificado (`keytool -list -v ... | grep SHA256`), que é público.

- [ ] **Step 2: Parar e pedir ao Levi**

Pergunte duas coisas:
- a confirmação para criar o repositório **público** `Grid-Co-CODE/app-campo-android` e subir;
- onde ele guarda a segunda cópia da chave e das senhas (fora do GitHub e do SharePoint).

Nada sobe antes do sim.

- [ ] **Step 3: Criar o repositório, os secrets e subir**

Run:
- `gh repo create Grid-Co-CODE/app-campo-android --public --source . --push`;
- `gh secret set` para `CASCA_KEYSTORE_B64` (`base64 -w0 casca.jks` pela entrada padrão), `CASCA_KEYSTORE_SENHA`,
  `CASCA_CHAVE_ALIAS` e `CASCA_CHAVE_SENHA`, cada um lido de arquivo, no ambiente `release`.

Expected: o workflow `montar` passa no push.

- [ ] **Step 4: Tag `v1.0.0` e conferência**

Run: `git tag v1.0.0 && git push origin v1.0.0`

Expected:
- a Release `v1.0.0` tem `app-campo.apk` e `versao.json`;
- `curl -sL https://github.com/Grid-Co-CODE/app-campo-android/releases/latest/download/versao.json` devolve
  `versionName` `1.0.0`;
- o SHA-256 do APK baixado bate com o do `versao.json`.

- [ ] **Step 5: Commit**

```bash
git commit -am "docs: roteiro da prova no aparelho e estado da casca"
```

A prova no aparelho (`docs/prova-no-aparelho.md`) acontece quando o Levi passar o celular, o usuário e a OS de teste.
Os ajustes que ela pedir (a lista do `allowNavigation`, o GPS) entram como versões `v1.0.x`.
