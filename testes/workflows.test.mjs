// Guarda as regras dos workflows do GitHub Actions (.github/workflows/). Não há como rodar o Actions daqui: os arquivos
// são lidos por um parser de YAML de verdade (o pacote yaml) e as regras são conferidas na estrutura (os jobs, os passos,
// o env de cada passo), não na indentação do texto. Os scripts dos passos que decidem se a Release sai são RODADOS num
// bash com curl, gh e apksigner falsos (o bash do Linux no CI; o Git Bash no Windows).
// Quem consertar um destes testes deve ler o comentário da regra antes: cada uma nasceu de uma decisão do dono da casca.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, mkdtempSync, mkdirSync, writeFileSync, rmSync, cpSync, existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join, delimiter } from 'node:path';
import YAML from 'yaml';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const ler = (caminho) => readFileSync(join(raiz, caminho), 'utf8');
const lerYaml = (caminho) => YAML.parse(ler(caminho), { uniqueKeys: true });

const PASTA = '.github/workflows';
const textoMontar = ler(`${PASTA}/montar.yml`);
const montar = lerYaml(`${PASTA}/montar.yml`);
const contrato = lerYaml(`${PASTA}/contrato.yml`);
const pacote = JSON.parse(ler('package.json'));

const passos = (job) => job?.steps ?? [];
const passo = (job, nome) => passos(job).find((p) => p.name === nome);
const indice = (job, teste) => passos(job).findIndex(teste);
const indiceDoNome = (job, nome) => indice(job, (p) => p.name === nome);
const indiceDoRun = (job, trecho) => indice(job, (p) => typeof p.run === 'string' && p.run.includes(trecho));
const indiceDoUses = (job, trecho) => indice(job, (p) => typeof p.uses === 'string' && p.uses.startsWith(trecho));
const usoDe = (job, trecho) => passos(job).find((p) => typeof p.uses === 'string' && p.uses.startsWith(trecho));
const release = montar.jobs.release;

// Toda string do workflow (chaves e valores), com o caminho dela: para saber ONDE um `secrets.` aparece.
function folhas(no, caminho = []) {
  if (typeof no === 'string') return [{ caminho, valor: no }];
  if (Array.isArray(no)) return no.flatMap((x, i) => folhas(x, [...caminho, i]));
  if (no && typeof no === 'object') {
    return Object.entries(no).flatMap(([k, v]) => [...folhas(k, [...caminho, '<chave>']), ...folhas(v, [...caminho, k])]);
  }
  return [];
}

// ---------------------------------------------------------------------------------------------------------------
// A forma geral
// ---------------------------------------------------------------------------------------------------------------

test('a pasta de workflows tem só os dois arquivos (um terceiro, com pull_request_target ou secrets, escaparia das regras)', () => {
  assert.deepEqual(readdirSync(join(raiz, PASTA)).sort(), ['contrato.yml', 'montar.yml']);
});

test('montar.yml: roda em push de branch, em tag v* e em pull request, com os jobs montar e release', () => {
  assert.deepEqual(montar.on, { push: { branches: ['**'], tags: ['v*'] }, pull_request: null });
  assert.deepEqual(Object.keys(montar.jobs), ['montar', 'release']);
});

test('permissões: o padrão é só leitura, e a única escrita em conteúdo é a do job release', () => {
  assert.deepEqual(montar.permissions, { contents: 'read' });
  assert.equal(montar.jobs.montar.permissions, undefined);
  assert.deepEqual(release.permissions, { contents: 'write' });
  // O contrato escreve issues e liga o próprio agendamento (actions: write); nada de conteúdo.
  assert.deepEqual(contrato.permissions, { contents: 'read', issues: 'write', actions: 'write' });
  for (const job of Object.values(contrato.jobs)) assert.equal(job.permissions, undefined);
  for (const wf of [montar, contrato]) assert.ok(!folhas(wf).some((f) => f.valor === 'write-all'));
});

test('o job release só roda em tag v*, depois dos testes, no ambiente release', () => {
  assert.equal(release.if, "startsWith(github.ref, 'refs/tags/v')");
  assert.equal(release.environment, 'release');
  assert.equal(release.needs, 'montar');
  assert.equal(montar.jobs.montar.environment, undefined, 'os testes e o PR não pedem o ambiente dos secrets');
});

// ---------------------------------------------------------------------------------------------------------------
// As actions
// ---------------------------------------------------------------------------------------------------------------

const SHA_DE_COMMIT = /^[\w.-]+\/[\w./-]+@[0-9a-f]{40}$/;

test('no job release, toda action é fixada por SHA de commit, com a tag num comentário', () => {
  // Uma tag móvel sequestrada (o caso tj-actions/changed-files, 03/2025) rodaria aqui com o token de escrita e perto das
  // senhas da chave, que não têm volta: o Android confia na chave para sempre.
  const usos = passos(release).filter((p) => p.uses).map((p) => p.uses);
  assert.ok(usos.length >= 4);
  for (const uso of usos) {
    assert.match(uso, SHA_DE_COMMIT, `fixe por SHA de commit: ${uso}`);
    const linha = textoMontar.split('\n').find((l) => l.includes(`uses: ${uso}`));
    assert.match(linha, / # v\d+\.\d+\.\d+$/, `a tag num comentário: ${uso}`);
  }
  for (const nome of ['actions/checkout@', 'actions/setup-node@', 'actions/setup-java@', 'gradle/actions/setup-gradle@']) {
    assert.ok(usos.some((u) => u.startsWith(nome)), `${nome} no release`);
  }
});

test('fora do release, a action vem por versão maior (vN) ou por SHA, nunca solta nem por branch', () => {
  const usos = [
    ...passos(montar.jobs.montar).filter((p) => p.uses).map((p) => p.uses),
    ...Object.values(contrato.jobs).flatMap((j) => passos(j).filter((p) => p.uses).map((p) => p.uses)),
  ];
  assert.ok(usos.length >= 6);
  for (const uso of usos) assert.ok(/^[\w.-]+\/[\w./-]+@v\d+$/.test(uso) || SHA_DE_COMMIT.test(uso), `fixe por major ou SHA: ${uso}`);
});

test('o cache: o aberto no teste; nenhum no release (o APK do campo sai de dependências baixadas agora)', () => {
  assert.deepEqual(usoDe(montar.jobs.montar, 'gradle/actions/setup-gradle@').with, { 'cache-provider': 'basic' });
  assert.deepEqual(usoDe(release, 'gradle/actions/setup-gradle@').with, { 'cache-disabled': true });
  assert.equal(usoDe(release, 'actions/setup-node@').with?.cache, undefined, 'sem o cache do npm no release');
});

// ---------------------------------------------------------------------------------------------------------------
// Os segredos
// ---------------------------------------------------------------------------------------------------------------

test('os secrets só entram por env: de passo, cada um inteiro, e o contrato não usa nenhum', () => {
  const nomes = [];
  for (const [arquivo, wf] of [['montar', montar], ['contrato', contrato]]) {
    for (const f of folhas(wf).filter((x) => x.valor.includes('secrets.'))) {
      assert.notEqual(arquivo, 'contrato', 'o contrato diário não precisa de secret (só do token da própria execução)');
      const [jobs, , steps, , env] = f.caminho;
      assert.ok(jobs === 'jobs' && steps === 'steps' && env === 'env' && f.caminho.length === 6, `secret fora do env de um passo (${f.caminho.join('.')})`);
      const nome = /^\$\{\{ secrets\.([A-Z0-9_]+) \}\}$/.exec(f.valor)?.[1];
      assert.ok(nome, `o secret é o valor inteiro do env (${f.caminho.join('.')})`);
      nomes.push(nome);
    }
  }
  // O alias da chave não é segredo, e como secret mascarava "campo" nos logs (app-***.apk): vem de vars.
  assert.deepEqual(nomes.sort(), ['CASCA_CHAVE_SENHA', 'CASCA_KEYSTORE_B64', 'CASCA_KEYSTORE_SENHA']);
  for (const wf of [montar, contrato]) {
    assert.equal(wf.env, undefined, 'nada de env no nível do workflow');
    for (const job of Object.values(wf.jobs)) assert.equal(job.env, undefined, 'nada de env no nível do job');
  }
});

test('as variáveis do repositório: o pino do certificado e o alias, cada um só no passo que o usa', () => {
  const usos = folhas(montar).filter((f) => f.valor.includes('vars.')).map((f) => `${f.caminho.join('.')}=${f.valor}`);
  const conferir = indiceDoNome(release, 'Conferir a assinatura e o certificado');
  const assinado = indiceDoNome(release, 'APK assinado');
  assert.deepEqual(usos.sort(), [
    `jobs.release.steps.${assinado}.env.CASCA_CHAVE_ALIAS=\${{ vars.CASCA_CHAVE_ALIAS }}`,
    `jobs.release.steps.${conferir}.env.CERT_ESPERADO=\${{ vars.CASCA_CERT_SHA256 }}`,
  ].sort());
  assert.ok(!folhas(contrato).some((f) => f.valor.includes('vars.')));
});

test('nenhum comando imprime as chaves nem despeja o ambiente, e nenhum run usa ${{ }} (tudo entra por env)', () => {
  for (const wf of [montar, contrato]) {
    for (const job of Object.values(wf.jobs)) {
      for (const p of passos(job).filter((x) => typeof x.run === 'string')) {
        assert.ok(!p.run.includes('${{'), `expressão dentro do run (${p.name}): passe por env`);
        for (const linha of p.run.split('\n')) {
          const imprime = /\b(echo|printenv|tee|cat|printf|env|declare)\b/.test(linha) && /CASCA_(KEYSTORE|CHAVE)/.test(linha);
          const decodifica = /^\s*printf '%s' "\$CASCA_KEYSTORE_B64" \| base64 -d > "\$RUNNER_TEMP\/casca\.jks"$/.test(linha);
          assert.ok(!imprime || decodifica, `imprime a chave: ${linha.trim()}`);
          assert.ok(!/^\s*(env|printenv|set|declare -p|export -p)\s*($|[|;&>])/.test(linha), `despeja o ambiente: ${linha.trim()}`);
          assert.ok(!(/GITHUB_ENV/.test(linha) && /CASCA_(KEYSTORE|CHAVE)/.test(linha)), 'as CASCA_* não vão para o GITHUB_ENV (passariam a todos os passos)');
        }
        assert.ok(!/set\s+-\w*x|bash\s+-\w*x|xtrace/.test(p.run), 'nada de set -x: ele imprimiria os secrets');
      }
    }
  }
});

test('as CASCA_* chegam só ao passo do Gradle; o npm ci e o cap sync rodam antes, sem elas e sem a chave no disco', () => {
  const comCasca = passos(release).map((p, i) => (Object.keys(p.env ?? {}).some((n) => n.startsWith('CASCA_')) ? i : -1)).filter((i) => i >= 0);
  const decodificar = indiceDoNome(release, 'Decodificar a chave de assinatura');
  const assinado = indiceDoNome(release, 'APK assinado');
  assert.deepEqual(comCasca, [decodificar, assinado]);
  assert.deepEqual(Object.keys(release.steps[decodificar].env), ['CASCA_KEYSTORE_B64']);
  assert.deepEqual(Object.keys(release.steps[assinado].env).sort(), ['CASCA_CHAVE_ALIAS', 'CASCA_CHAVE_SENHA', 'CASCA_KEYSTORE', 'CASCA_KEYSTORE_SENHA']);
  // O passo das senhas só monta: o CLI do Capacitor e as dependências npm já rodaram, antes de a chave sair do secret.
  assert.match(release.steps[assinado].run, /\.\/gradlew assembleRelease/);
  assert.ok(!/cap sync|npm|npx/.test(release.steps[assinado].run), 'nada de npm no passo das senhas');
  const sync = indiceDoRun(release, 'npx cap sync android');
  const npmCi = indiceDoRun(release, 'npm ci');
  assert.ok(npmCi >= 0 && sync > npmCi && decodificar > sync && assinado > decodificar, 'ordem: npm ci, cap sync, chave, Gradle');
  // A chave sai do disco assim que o Gradle termina, e de novo no último passo, se algo cair antes.
  assert.match(release.steps[assinado].run, /trap 'rm -f "\$CASCA_KEYSTORE"' EXIT/);
  assert.match(release.steps[decodificar].run, /printf '%s' "\$CASCA_KEYSTORE_B64" \| base64 -d > "\$RUNNER_TEMP\/casca\.jks"/);
  assert.match(release.steps[decodificar].run, /^umask 077$/m);
  assert.deepEqual(release.steps.at(-1), { name: 'Apagar a chave do runner', if: 'always()', run: 'rm -f "$RUNNER_TEMP/casca.jks"' });
});

// ---------------------------------------------------------------------------------------------------------------
// O que tem de passar antes de publicar
// ---------------------------------------------------------------------------------------------------------------

test('antes de a chave sair do secret: o commit da tag na main, a versão da tag e o versionCode que cresce', () => {
  const decodificar = indiceDoNome(release, 'Decodificar a chave de assinatura');
  assert.equal(indiceDoUses(release, 'actions/checkout@'), 0);
  assert.deepEqual(release.steps[0].with, { 'persist-credentials': false, 'fetch-depth': 0 }, 'o histórico inteiro, para achar a main');
  const naMain = indiceDoNome(release, 'O commit da tag está na main');
  const versao = indiceDoNome(release, 'Versão da tag');
  const cresce = indiceDoNome(release, 'O versionCode cresce');
  assert.equal(naMain, 1, 'logo depois do checkout');
  assert.ok(versao > naMain && cresce > versao && decodificar > cresce, 'ordem: main, versão, versionCode, chave');
  assert.match(release.steps[naMain].run, /git merge-base --is-ancestor "\$alvo" origin\/main/);
  assert.match(release.steps[versao].run, /node ferramentas\/versao-json\.mjs tipo-da-versao "\$versao"/);
  assert.ok(!/=~/.test(release.steps[versao].run), 'o semver mora só no versao-json.mjs (nada de regex no bash)');
  assert.match(release.steps[cresce].run, /releases\/latest\/download\/versao\.json/);
  assert.match(release.steps[cresce].run, /node ferramentas\/versao-json\.mjs conferir-crescimento "\$anterior"/);
});

test('a Release confere a assinatura e o pino do certificado antes de publicar, e leva o APK, o SHA-256 e o versao.json', () => {
  const conferir = indiceDoNome(release, 'Conferir a assinatura e o certificado');
  const versaoJson = indiceDoRun(release, 'node ferramentas/versao-json.mjs --apk');
  const publicar = indiceDoRun(release, 'gh release create');
  assert.ok(conferir > indiceDoNome(release, 'APK assinado') && versaoJson > conferir && publicar > versaoJson, 'ordem: certificado, versao.json, publicação');
  const c = release.steps[conferir].run;
  assert.match(c, /app-release\.apk/);
  assert.match(c, /build-tools\/\*\/apksigner/);
  assert.match(c, /"\$apksigner" verify --print-certs app-campo\.apk > "\$RUNNER_TEMP\/certificados\.txt"/);
  assert.match(c, /node ferramentas\/certificado\.mjs "\$RUNNER_TEMP\/certificados\.txt"/);
  const v = release.steps[versaoJson].run;
  assert.match(v, /sha256sum app-campo\.apk > app-campo\.apk\.sha256/);
  assert.match(v, /^test "\$\(cut -d' ' -f1 app-campo\.apk\.sha256\)" = /m, 'duas contas do SHA-256 têm de bater');
  const p = release.steps[publicar].run;
  assert.match(p, /gh release create "\$GITHUB_REF_NAME" app-campo\.apk app-campo\.apk\.sha256 versao\.json/);
  assert.match(p, /--notes-file "novidades\/\$\{VERSAO_CASCA\}\.md"/);
  assert.match(p, /--verify-tag/);
});

test('CI de PR: Node 22.x, npm test, os testes de JVM, o APK de depuração, o lint e os dois testes do FileProvider', () => {
  for (const wf of [montar, contrato]) {
    for (const job of Object.values(wf.jobs)) assert.equal(usoDe(job, 'actions/setup-node@')?.with?.['node-version'], '22.x');
  }
  assert.match(pacote.engines.node, /22\.22\.2/, 'o jsdom 30 exige ^22.22.2');
  const job = montar.jobs.montar;
  const npmTest = indiceDoRun(job, 'npm test');
  const gradle = indiceDoRun(job, './gradlew testDebugUnitTest assembleDebug lint');
  const pulados = indiceDoRun(job, 'ferramentas/testes-pulados.mjs');
  assert.ok(npmTest >= 0 && gradle > npmTest && pulados > gradle, 'ordem: npm test, Gradle, conferência dos testes pulados');
  assert.match(job.steps[gradle].run, /npx cap sync android/);
  assert.equal(passo(job, 'APK de depuração').with.path, 'android/app/build/outputs/apk/debug/app-debug.apk');
  // Os nomes dos dois testes têm de existir no AtualizadorTest.java: renomear um deles sem mexer aqui derrubaria o CI.
  const nomes = job.steps[pulados].run.trim().split(/\s+/).slice(4);
  assert.deepEqual(nomes, ['instaladorRecebeOUriDoProviderParaOApkDaPasta', 'oProviderDoInstaladorNaoExpoeNadaAlemDeCacheAtualizacao']);
  const java = ler('android/app/src/test/java/br/com/gridco/campo/AtualizadorTest.java');
  for (const nome of nomes) assert.match(java, new RegExp(`public void ${nome}\\(`), `${nome} no AtualizadorTest.java`);
});

// ---------------------------------------------------------------------------------------------------------------
// O contrato diário
// ---------------------------------------------------------------------------------------------------------------

test('contrato.yml: diário e manual; abre a issue do tipo certo, liga o próprio agendamento e fica vermelho se falhar', () => {
  assert.deepEqual(contrato.on, { schedule: [{ cron: '0 11 * * *' }], workflow_dispatch: null });
  const job = contrato.jobs.contrato;
  const conferir = indice(job, (p) => p.id === 'conferir');
  assert.ok(conferir >= 0);
  assert.match(job.steps[conferir].run, /node testes\/contrato\.mjs/);
  assert.match(job.steps[conferir].run, /echo "codigo=.*" >> "\$GITHUB_OUTPUT"/);

  const issue = indiceDoRun(job, 'node ferramentas/issue-do-contrato.mjs');
  assert.ok(issue > conferir);
  assert.equal(job.steps[issue].if, 'always()', 'roda também com o contrato ok (fecha as issues de leitura e de erro)');
  assert.equal(job.steps[issue].env.CODIGO, '${{ steps.conferir.outputs.codigo }}');

  // O nome do arquivo no PUT tem de ser o deste workflow.
  const ligar = indiceDoNome(job, 'Manter o agendamento ligado');
  assert.ok(ligar > issue);
  assert.equal(job.steps[ligar].if, 'always()');
  assert.equal(job.steps[ligar].run.trim(), 'gh api --method PUT "repos/${GH_REPO}/actions/workflows/contrato.yml/enable"');

  const ultimo = job.steps.at(-1);
  assert.equal(ultimo.name, 'Derrubar o job se o contrato falhou');
  assert.equal(ultimo.if, "always() && steps.conferir.outputs.codigo != '0'");
  assert.equal(ultimo.run.trim(), 'exit 1');
});

// ---------------------------------------------------------------------------------------------------------------
// Os scripts dos passos, rodados num bash de verdade com ferramentas falsas
// ---------------------------------------------------------------------------------------------------------------

// No Windows só o Git Bash serve (o bash.exe do System32 é o do WSL, outro sistema de arquivos).
const temBash = process.platform !== 'win32' || Boolean(process.env.MSYSTEM);
const pularSemBash = temBash ? {} : { skip: 'sem bash (no Windows, rode o npm test pelo Git Bash)' };

function bash(script, { cwd, env = {}, falsos = {} }) {
  const bin = mkdtempSync(join(tmpdir(), 'casca-bin-'));
  try {
    for (const [nome, corpo] of Object.entries(falsos)) writeFileSync(join(bin, nome), `#!/usr/bin/env bash\n${corpo}\n`, { mode: 0o755 });
    const r = spawnSync('bash', ['--noprofile', '--norc', '-eo', 'pipefail', '-c', script], {
      cwd, encoding: 'utf8', env: { ...process.env, ...env, PATH: `${bin}${delimiter}${process.env.PATH}` },
    });
    return { status: r.status, stdout: r.stdout ?? '', stderr: r.stderr ?? '' };
  } finally {
    rmSync(bin, { recursive: true, force: true });
  }
}

function pastaTemporaria(t) {
  const dir = mkdtempSync(join(tmpdir(), 'casca-passo-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

// O caminho para dentro de um script bash: no Git Bash, C:\x vira C:/x (que ele entende).
const noBash = (caminho) => caminho.replace(/\\/g, '/');

test('todo run dos dois workflows passa no bash -n', pularSemBash, () => {
  for (const wf of [montar, contrato]) {
    for (const job of Object.values(wf.jobs)) {
      for (const p of passos(job).filter((x) => typeof x.run === 'string')) {
        const r = spawnSync('bash', ['-n', '-c', p.run], { encoding: 'utf8' });
        assert.equal(r.status, 0, `${p.name ?? p.run.slice(0, 40)}: ${r.stderr}`);
      }
    }
  }
});

test('passo "O commit da tag está na main": a tag num ramo fora da main não publica', pularSemBash, (t) => {
  const dir = pastaTemporaria(t);
  const git = (...args) => {
    const r = spawnSync('git', ['-c', 'user.name=Teste', '-c', 'user.email=teste@exemplo.com.br', '-c', 'commit.gpgsign=false',
      '-c', 'tag.gpgsign=false', '-c', 'init.defaultBranch=main', ...args], { cwd: dir, encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
  };
  git('init', '-q');
  git('checkout', '-q', '-b', 'main');
  git('commit', '-q', '--allow-empty', '-m', 'na main');
  git('tag', '-a', 'v1.0.0', '-m', 'v1.0.0');
  git('update-ref', 'refs/remotes/origin/main', 'main');
  git('checkout', '-q', '-b', 'ramo');
  git('commit', '-q', '--allow-empty', '-m', 'fora da main');
  git('tag', '-a', 'v1.0.1', '-m', 'v1.0.1');
  const script = passo(release, 'O commit da tag está na main').run;

  git('checkout', '-q', 'v1.0.0');
  const naMain = bash(script, { cwd: dir, env: { GITHUB_REF_NAME: 'v1.0.0' } });
  assert.equal(naMain.status, 0, `a tag (anotada) na main passa: ${naMain.stderr}`);
  git('checkout', '-q', 'v1.0.1');
  const fora = bash(script, { cwd: dir, env: { GITHUB_REF_NAME: 'v1.0.1' } });
  assert.notEqual(fora.status, 0);
  assert.match(fora.stderr, /não está na main/);
  // O checkout de outro commit que não o da tag também não passa.
  git('checkout', '-q', 'v1.0.0');
  assert.notEqual(bash(script, { cwd: dir, env: { GITHUB_REF_NAME: 'v1.0.1' } }).status, 0);
});

test('passo "Versão da tag": só semver, com o arquivo de novidades, e o tipo certo no GITHUB_ENV', pularSemBash, (t) => {
  const dir = pastaTemporaria(t);
  cpSync(join(raiz, 'ferramentas'), join(dir, 'ferramentas'), { recursive: true });
  mkdirSync(join(dir, 'novidades'));
  for (const v of ['1.2.3', '1.2.3+ci-7', '1.3.0-rc.1']) writeFileSync(join(dir, 'novidades', `${v}.md`), '- x\n');
  const script = passo(release, 'Versão da tag').run;
  let n = 0;
  const rodar = (tag) => {
    const arquivo = join(dir, `github-env-${n++}`);
    writeFileSync(arquivo, '');
    const r = bash(script, { cwd: dir, env: { GITHUB_REF_NAME: tag, GITHUB_ENV: arquivo } });
    return { ...r, env: readFileSync(arquivo, 'utf8') };
  };
  assert.deepEqual(rodar('v1.2.3').env.trim().split('\n'), ['VERSAO_CASCA=1.2.3', 'TIPO_DA_VERSAO=final']);
  assert.deepEqual(rodar('v1.2.3+ci-7').env.trim().split('\n'), ['VERSAO_CASCA=1.2.3+ci-7', 'TIPO_DA_VERSAO=final']);
  assert.deepEqual(rodar('v1.3.0-rc.1').env.trim().split('\n'), ['VERSAO_CASCA=1.3.0-rc.1', 'TIPO_DA_VERSAO=pre-lancamento']);
  for (const ruim of ['v1.2', 'v1.2.3/../x', 'v1.2.4']) {
    const r = rodar(ruim);
    assert.notEqual(r.status, 0, ruim);
    assert.equal(r.env, '', `${ruim}: nada vai ao GITHUB_ENV`);
  }
});

test('passo "O versionCode cresce": compara com a última Release, e o 404 só vale sem nenhuma', pularSemBash, (t) => {
  const dir = pastaTemporaria(t);
  const script = passo(release, 'O versionCode cresce').run;
  // curl falso: escreve o corpo no -o e imprime o código do -w; gh falso: quantas Releases publicadas.
  const curl = 'saida=""; while [ $# -gt 0 ]; do if [ "$1" = "-o" ]; then saida="$2"; shift; fi; shift; done; printf "%s" "$FALSO_CORPO" > "$saida"; printf "%s" "$FALSO_CODIGO"';
  const gh = '[ "$1 $2" = "release list" ] || exit 9; printf "%s\\n" "$FALSO_PUBLICADAS"';
  const rodar = (env) => bash(script, { cwd: raiz, falsos: { curl, gh }, env: { RUNNER_TEMP: dir, GH_REPO: 'exemplo/repo', GITHUB_SERVER_URL: 'https://github.com', ...env } });
  const anterior = (n) => JSON.stringify({ versionCode: n, versionName: '1.0.0', apk: 'x', sha256: 'ab'.repeat(32), novidades: [] });

  const cresceu = rodar({ GITHUB_RUN_NUMBER: '41', FALSO_CODIGO: '200', FALSO_CORPO: anterior(40) });
  assert.equal(cresceu.status, 0, cresceu.stderr);
  const igual = rodar({ GITHUB_RUN_NUMBER: '40', FALSO_CODIGO: '200', FALSO_CORPO: anterior(40) });
  assert.notEqual(igual.status, 0);
  assert.match(igual.stderr, /não é maior/);
  assert.notEqual(rodar({ GITHUB_RUN_NUMBER: '1', FALSO_CODIGO: '200', FALSO_CORPO: anterior(40) }).status, 0, 'a contagem que recomeçou');
  assert.notEqual(rodar({ GITHUB_RUN_NUMBER: '41', FALSO_CODIGO: '200', FALSO_CORPO: '<html>' }).status, 0, 'o anterior ilegível');
  const primeira = rodar({ GITHUB_RUN_NUMBER: '1', FALSO_CODIGO: '404', FALSO_CORPO: 'Not Found', FALSO_PUBLICADAS: '0' });
  assert.equal(primeira.status, 0, `a primeira Release: ${primeira.stderr}`);
  assert.notEqual(rodar({ GITHUB_RUN_NUMBER: '41', FALSO_CODIGO: '404', FALSO_CORPO: 'Not Found', FALSO_PUBLICADAS: '1' }).status, 0, 'há Release, mas sem versao.json');
  assert.notEqual(rodar({ GITHUB_RUN_NUMBER: '41', FALSO_CODIGO: '500', FALSO_CORPO: 'x' }).status, 0, 'o GitHub fora do ar');
});

test('passo "Conferir a assinatura e o certificado": só passa a chave da variável', pularSemBash, (t) => {
  const dir = pastaTemporaria(t);
  cpSync(join(raiz, 'ferramentas'), join(dir, 'ferramentas'), { recursive: true });
  const pastaApk = join(dir, 'android/app/build/outputs/apk/release');
  mkdirSync(pastaApk, { recursive: true });
  writeFileSync(join(pastaApk, 'app-release.apk'), 'apk de mentira');
  const buildTools = join(dir, 'sdk/build-tools/99.0.0');
  mkdirSync(buildTools, { recursive: true });
  const CERTO = '3f'.repeat(32);
  writeFileSync(join(buildTools, 'apksigner'), '#!/usr/bin/env bash\n[ "$1 $2 $3" = "verify --print-certs app-campo.apk" ] || exit 9\necho "Signer #1 certificate DN: CN=Exemplo"\necho "Signer #1 certificate SHA-256 digest: $FALSO_SHA"\n', { mode: 0o755 });
  const script = passo(release, 'Conferir a assinatura e o certificado').run;
  const rodar = (env) => bash(script, { cwd: dir, env: { ANDROID_HOME: noBash(join(dir, 'sdk')), RUNNER_TEMP: noBash(dir), FALSO_SHA: CERTO, ...env } });

  const certo = rodar({ CERT_ESPERADO: CERTO.toUpperCase().match(/../g).join(':') });
  assert.equal(certo.status, 0, certo.stderr);
  assert.ok(existsSync(join(dir, 'app-campo.apk')));
  const outra = rodar({ CERT_ESPERADO: CERTO, FALSO_SHA: 'a1'.repeat(32) });
  assert.notEqual(outra.status, 0);
  assert.match(outra.stderr, /não é o esperado/);
  assert.notEqual(rodar({ CERT_ESPERADO: '' }).status, 0, 'sem a variável não publica');
  rmSync(join(pastaApk, 'app-release.apk'));
  assert.notEqual(rodar({ CERT_ESPERADO: CERTO }).status, 0, 'sem o APK assinado');
});

test('passo "Publicar a Release": o pré-lançamento vem do tipo da versão, e tipo desconhecido não publica', pularSemBash, (t) => {
  const dir = pastaTemporaria(t);
  const script = passo(release, 'Publicar a Release').run;
  const registro = join(dir, 'gh.txt');
  const gh = `printf "%s\\n" "$*" >> "${noBash(registro)}"`;
  const rodar = (env) => {
    writeFileSync(registro, '');
    const r = bash(script, { cwd: dir, falsos: { gh }, env: { GITHUB_REF_NAME: 'v1.0.0', VERSAO_CASCA: '1.0.0', ...env } });
    return { ...r, gh: readFileSync(registro, 'utf8') };
  };
  const final = rodar({ TIPO_DA_VERSAO: 'final' });
  assert.equal(final.status, 0, final.stderr);
  assert.match(final.gh, /^release create v1\.0\.0 app-campo\.apk app-campo\.apk\.sha256 versao\.json --verify-tag/);
  assert.ok(!final.gh.includes('--prerelease'));
  assert.match(rodar({ TIPO_DA_VERSAO: 'pre-lancamento' }).gh, /--prerelease/);
  const nenhum = rodar({ TIPO_DA_VERSAO: '' });
  assert.notEqual(nenhum.status, 0);
  assert.equal(nenhum.gh, '', 'nada publicado');
});

test('passo "Manter o agendamento ligado": um PUT no enable deste workflow', pularSemBash, (t) => {
  const dir = pastaTemporaria(t);
  const registro = join(dir, 'gh.txt');
  const gh = `printf "%s\\n" "$*" >> "${noBash(registro)}"`;
  const r = bash(passo(contrato.jobs.contrato, 'Manter o agendamento ligado').run, { cwd: dir, falsos: { gh }, env: { GH_REPO: 'exemplo/repo' } });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(readFileSync(registro, 'utf8'), 'api --method PUT repos/exemplo/repo/actions/workflows/contrato.yml/enable\n');
});
