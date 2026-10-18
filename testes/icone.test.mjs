// O ícone e a abertura do Nexus Field (pedido do dono da casca, 17/10/2026): o G da Grid em BRANCO, inteiro, sobre o navy
// do Nexus. O ícone de antes (o logo do site em 61,6% do quadro) passava da zona segura e podia sair cortado na máscara
// do aparelho. Aqui os PNG gerados pelo ferramentas/gerar-icones.py são lidos pixel a pixel: o G tem de caber no círculo
// de 66 dp do quadro de 108 dp (a área que toda máscara mostra: círculo, squircle, gota), em toda densidade.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';
import { lerPng, pixel } from './png.mjs';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const RES = join(raiz, 'android', 'app', 'src', 'main', 'res');
const ler = (caminho) => readFileSync(caminho, 'utf8');

// Quanto um dp vale em pixels em cada pasta de densidade.
const DENSIDADES = [['mdpi', 1], ['hdpi', 1.5], ['xhdpi', 2], ['xxhdpi', 3], ['xxxhdpi', 4]];
const NAVY = [0x09, 0x0d, 0x18];
const QUADRO_DP = 108;      // o quadro do ícone adaptativo
const ZONA_SEGURA_DP = 66;  // o círculo que nenhuma máscara corta
const LEGADO_DP = 48;       // o ícone antigo (Android 7), que mostra só os 72 dp visíveis do quadro

const { DOMParser } = new JSDOM('').window;
function lerXml(caminho) {
  const doc = new DOMParser().parseFromString(ler(caminho), 'application/xml');
  assert.equal(doc.getElementsByTagName('parsererror').length, 0, `${caminho} não abre`);
  return doc;
}

// A caixa dos pixels que passam no critério: { x0, y0, x1, y1 } com x1 e y1 exclusivos, ou null.
function caixa(png, passa) {
  let x0 = Infinity, y0 = Infinity, x1 = -1, y1 = -1;
  for (let y = 0; y < png.altura; y++) {
    for (let x = 0; x < png.largura; x++) {
      if (!passa(pixel(png, x, y))) continue;
      if (x < x0) x0 = x;
      if (y < y0) y0 = y;
      if (x + 1 > x1) x1 = x + 1;
      if (y + 1 > y1) y1 = y + 1;
    }
  }
  return x1 < 0 ? null : { x0, y0, x1, y1 };
}

// O canto do pixel mais longe do centro: o pixel inteiro tem de caber no círculo, não só o meio dele.
function distanciaMaxima(x, y, centro) {
  const dx = Math.max(Math.abs(x - centro), Math.abs(x + 1 - centro));
  const dy = Math.max(Math.abs(y - centro), Math.abs(y + 1 - centro));
  return Math.hypot(dx, dy);
}

const visivel = ([, , , a]) => a > 0;

test('o fundo do ícone é o navy do Nexus (#090d18)', () => {
  const cor = lerXml(join(RES, 'values', 'ic_launcher_background.xml')).getElementsByTagName('color')[0];
  assert.equal(cor.getAttribute('name'), 'ic_launcher_background');
  assert.equal(cor.textContent.trim().toLowerCase(), '#090d18');
});

test('o ícone adaptativo: fundo navy, o G branco na frente e o mesmo G no ícone monocromático (Android 13+)', () => {
  for (const nome of ['ic_launcher.xml', 'ic_launcher_round.xml']) {
    const doc = lerXml(join(RES, 'mipmap-anydpi-v26', nome));
    const camada = (tag) => doc.getElementsByTagName(tag)[0]?.getAttribute('android:drawable');
    assert.equal(camada('background'), '@color/ic_launcher_background', nome);
    assert.equal(camada('foreground'), '@mipmap/ic_launcher_foreground', nome);
    assert.equal(camada('monochrome'), '@mipmap/ic_launcher_foreground', `${nome}: o tema do Android 13+ pinta o mesmo G`);
  }
});

test('o G da frente cabe inteiro na zona segura (66 dp no quadro de 108 dp), centrado e branco puro, em toda densidade', () => {
  for (const [pasta, dp] of DENSIDADES) {
    const caminho = join(RES, `mipmap-${pasta}`, 'ic_launcher_foreground.png');
    const png = lerPng(caminho);
    const lado = QUADRO_DP * dp;
    assert.equal(png.largura, lado, `${pasta}: o quadro tem 108 dp`);
    assert.equal(png.altura, lado, `${pasta}: o quadro tem 108 dp`);

    const c = caixa(png, visivel);
    assert.ok(c, `${pasta}: o G está lá`);
    const largura = c.x1 - c.x0;
    const altura = c.y1 - c.y0;
    // No máximo ~56% do quadro (o pedido); menos de 45% já seria um G perdido no meio do ícone.
    assert.ok(largura / lado <= 0.56, `${pasta}: o G ocupa ${(100 * largura / lado).toFixed(1)}% da largura do quadro`);
    assert.ok(largura / lado >= 0.45, `${pasta}: o G ficou pequeno demais (${(100 * largura / lado).toFixed(1)}%)`);
    assert.ok(Math.abs(largura - altura) <= 2, `${pasta}: o G não foi esticado (${largura} x ${altura})`);
    assert.ok(Math.abs((c.x0 + c.x1) / 2 - lado / 2) <= 1, `${pasta}: centrado na horizontal`);
    assert.ok(Math.abs((c.y0 + c.y1) / 2 - lado / 2) <= 1, `${pasta}: centrado na vertical`);

    const raio = (ZONA_SEGURA_DP / 2) * dp;
    let fora = 0;
    let naoBranco = 0;
    for (let y = 0; y < png.altura; y++) {
      for (let x = 0; x < png.largura; x++) {
        const [r, g, b, a] = pixel(png, x, y);
        if (a === 0) continue;
        if (distanciaMaxima(x, y, lado / 2) > raio) fora++;
        if (r !== 255 || g !== 255 || b !== 255) naoBranco++;
      }
    }
    assert.equal(fora, 0, `${pasta}: ${fora} pixels do G fora do círculo de 66 dp (sairiam cortados em alguma máscara)`);
    assert.equal(naoBranco, 0, `${pasta}: ${naoBranco} pixels do G que não são branco puro`);
  }
});

test('os ícones antigos (Android 7): o quadrado e o redondo, navy com o G branco, em toda densidade', () => {
  const claro = ([r, g, b, a]) => a > 0 && r > 128 && g > 128 && b > 128;
  for (const [pasta, dp] of DENSIDADES) {
    for (const nome of ['ic_launcher.png', 'ic_launcher_round.png']) {
      const png = lerPng(join(RES, `mipmap-${pasta}`, nome));
      const lado = LEGADO_DP * dp;
      assert.equal(png.largura, lado, `${pasta}/${nome}`);
      assert.equal(png.altura, lado, `${pasta}/${nome}`);

      // O mesmo G do adaptativo como ele aparece: os 72 dp visíveis do quadro de 108 viram os 48 dp do ícone antigo.
      const c = caixa(png, claro);
      assert.ok(c, `${pasta}/${nome}: o G branco está lá`);
      const proporcao = (c.x1 - c.x0) / lado;
      assert.ok(proporcao > 0.70 && proporcao < 0.84, `${pasta}/${nome}: o G ocupa ${(100 * proporcao).toFixed(1)}%`);
      assert.ok(Math.abs((c.x0 + c.x1) / 2 - lado / 2) <= 1, `${pasta}/${nome}: centrado`);

      const acima = Math.round(lado * 0.08); // dentro do fundo, acima do G
      assert.deepEqual(pixel(png, Math.floor(lado / 2), acima), [...NAVY, 255], `${pasta}/${nome}: o fundo é o navy`);
      const canto = pixel(png, 0, 0);
      if (nome === 'ic_launcher.png') assert.deepEqual(canto, [...NAVY, 255], `${pasta}: o quadrado vai até o canto`);
      else assert.equal(canto[3], 0, `${pasta}: o redondo é transparente no canto`);
    }
  }
});

test('a cópia branca em docs/marca é o G da Grid em branco puro, com o alfa do original', () => {
  const png = lerPng(join(raiz, 'docs', 'marca', 'grid-g-branco.png'));
  assert.deepEqual([png.largura, png.altura], [1147, 1146], 'o tamanho do grid-icon.png do Nexus');
  let opacos = 0;
  let transparentes = 0;
  let parciais = 0;
  for (let y = 0; y < png.altura; y++) {
    for (let x = 0; x < png.largura; x++) {
      const [r, g, b, a] = pixel(png, x, y);
      if (a === 0) { transparentes++; continue; }
      assert.deepEqual([r, g, b], [255, 255, 255], `pixel (${x}, ${y}) não é branco`);
      if (a === 255) opacos++; else parciais++;
    }
  }
  assert.ok(opacos > 100000 && transparentes > 100000, 'o G e o vazio em volta dele');
  assert.ok(parciais > 0, 'a borda suave do original (o alfa parcial) ficou');
});

test('a abertura (splash) é o navy com o G pequeno no centro, sem as imagens do template do Capacitor', () => {
  const pngsDaAbertura = [];
  for (const pasta of readdirSync(RES)) {
    const caminho = join(RES, pasta);
    if (statSync(caminho).isDirectory() && pasta.startsWith('drawable')) {
      for (const nome of readdirSync(caminho)) if (/^splash.*\.png$/.test(nome)) pngsDaAbertura.push(`${pasta}/${nome}`);
    }
  }
  assert.deepEqual(pngsDaAbertura, [], 'a imagem padrão do Capacitor saiu');

  const estilos = lerXml(join(RES, 'values', 'styles.xml'));
  const lancamento = [...estilos.getElementsByTagName('style')].find((s) => s.getAttribute('name') === 'AppTheme.NoActionBarLaunch');
  assert.ok(lancamento, 'o tema da abertura');
  const item = (nome) => [...lancamento.getElementsByTagName('item')].find((i) => i.getAttribute('name') === nome)?.textContent.trim();
  // O Android 12+ desenha a abertura sozinho com estes dois; o 7 a 11 também, pelo Theme.SplashScreen do androidx.
  assert.equal(item('windowSplashScreenBackground'), '@color/ic_launcher_background');
  assert.equal(item('windowSplashScreenAnimatedIcon'), '@drawable/splash_icone');
  assert.equal(item('android:background'), '@drawable/splash');

  // O ícone da abertura é o G com uma margem a mais: o sistema o desenha num quadro de 288 dp, e a margem de 48 dp de
  // cada lado deixa o G com ~100 dp (pequeno no centro, como pedido), em vez dos ~150 dp do quadro inteiro.
  const icone = lerXml(join(RES, 'drawable', 'splash_icone.xml')).documentElement;
  assert.equal(icone.tagName, 'inset');
  assert.equal(icone.getAttribute('android:drawable'), '@mipmap/ic_launcher_foreground');
  assert.equal(icone.getAttribute('android:inset'), '48dp');

  // O fundo da activity enquanto ela abre: a mesma cor e o G no mesmo tamanho (288 - 2 x 48 = 192 dp de quadro).
  const fundo = lerXml(join(RES, 'drawable', 'splash.xml')).documentElement;
  assert.equal(fundo.tagName, 'layer-list');
  const camadas = [...fundo.getElementsByTagName('item')];
  assert.equal(camadas.length, 2);
  assert.equal(camadas[0].getAttribute('android:drawable'), '@color/ic_launcher_background');
  assert.equal(camadas[1].getAttribute('android:drawable'), '@mipmap/ic_launcher_foreground');
  assert.equal(camadas[1].getAttribute('android:gravity'), 'center');
  assert.equal(camadas[1].getAttribute('android:width'), '192dp');
  assert.equal(camadas[1].getAttribute('android:height'), '192dp');
});

// ---------------------------------------------------------------------------------------------------------------
// A tela "Sem internet" e a página local no navy do Nexus Field (R40, 18/10/2026)
// ---------------------------------------------------------------------------------------------------------------
// A tela de erro (www/erro.html) e a página local (www/index.html) aparecem logo depois da abertura. Até 17/10/2026 elas
// tinham o fundo do App de Campo (o roxo-escuro do site), porque o ícone também tinha; o ícone e a abertura passaram ao
// navy do Nexus (#090d18), e a sequência no aparelho ficaria navy -> roxo -> site. As duas páginas acompanham o ícone, e
// o texto continua legível nele.

const luminancia = (hex) => {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contraste = (a, b) => {
  const [claro, escuro] = [luminancia(a), luminancia(b)].sort((x, y) => y - x);
  return (claro + 0.05) / (escuro + 0.05);
};

// As variáveis do :root da página (`--nome:#rrggbb`), já em minúsculas.
function coresDaRaiz(html) {
  const raizCss = html.match(/:root\s*\{([^}]*)\}/)?.[1] ?? '';
  const cores = {};
  for (const [, nome, valor] of raizCss.matchAll(/--([a-z-]+)\s*:\s*(#[0-9a-fA-F]{6})\b/g)) cores[nome] = valor.toLowerCase();
  return cores;
}

test('a tela Sem internet e a página local têm o fundo do ícone (o navy do Nexus Field), com o texto legível nele', () => {
  const navy = ler(join(RES, 'values', 'ic_launcher_background.xml')).match(/>\s*(#[0-9a-fA-F]{6})\s*</)[1].toLowerCase();
  for (const arquivo of ['www/erro.html', 'www/index.html']) {
    const html = ler(join(raiz, arquivo));
    const cores = coresDaRaiz(html);
    assert.equal(cores.fundo, navy, `${arquivo}: o fundo é o do ícone`);
    assert.ok(!/#191528/i.test(html), `${arquivo}: o roxo-escuro do App de Campo não sobra, nem em comentário`);
    const comentarios = [...html.matchAll(/\/\*([\s\S]*?)\*\//g)].map((m) => m[1]);
    assert.ok(comentarios.some((c) => /Nexus Field/.test(c) && c.includes(navy)),
      `${arquivo}: um comentário diz que o fundo é a cor do Nexus Field (e qual é)`);
    assert.ok(contraste(cores.texto, cores.fundo) >= 7, `${arquivo}: o texto no fundo`);
  }
  // Só a tela de erro tem texto suave e botão.
  const erro = coresDaRaiz(ler(join(raiz, 'www/erro.html')));
  assert.ok(contraste(erro['texto-suave'], erro.fundo) >= 4.5, 'o texto suave no fundo (WCAG AA)');
  assert.ok(contraste(erro['sobre-destaque'], erro.destaque) >= 4.5, 'o texto do botão sobre o destaque (WCAG AA)');
  assert.ok(contraste(erro.destaque, erro.fundo) >= 3, 'o botão se destaca do fundo');
});
