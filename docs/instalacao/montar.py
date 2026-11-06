"""Monta o guia de instalação do Nexus Field: a página (guia.html) e os dois cartões para o WhatsApp (PNG 1080 x 1920).

O código QR e o ícone vão embutidos (data URI), para a página e os cartões não dependerem de nada de fora além das
fontes. O link é o fixo da Release mais nova: muda a versão, o link e o código continuam valendo.

    python docs/instalacao/montar.py
"""

import base64
import io
import pathlib
import subprocess
import tempfile

import qrcode
from PIL import Image

AQUI = pathlib.Path(__file__).resolve().parent
RAIZ = AQUI.parent.parent
LINK = "https://github.com/Grid-Co-CODE/nexus-field/releases/latest/download/nexus-field.apk"
NAVY = (9, 13, 24, 255)
CHROME = pathlib.Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")


def qr_svg(texto):
    """O código QR como SVG (módulos escuros num quadrado só, nítido em qualquer tamanho), em data URI."""
    q = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=0)
    q.add_data(texto)
    q.make(fit=True)
    m = q.get_matrix()
    n = len(m)
    caminho = "".join(f"M{x} {y}h1v1h-1z" for y, linha in enumerate(m) for x, v in enumerate(linha) if v)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n} {n}" shape-rendering="crispEdges">'
           f'<rect width="{n}" height="{n}" fill="#ffffff"/><path d="{caminho}" fill="#090d18"/></svg>')
    return "data:image/svg+xml;base64," + base64.b64encode(svg.encode()).decode()


def icone():
    """O ícone do app como o launcher mostra: os 2/3 do meio do primeiro plano (o G) sobre o navy."""
    fg = Image.open(RAIZ / "android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png").convert("RGBA")
    lado = fg.width
    corte = fg.crop((lado // 6, lado // 6, lado - lado // 6, lado - lado // 6))
    fundo = Image.new("RGBA", corte.size, NAVY)
    fundo.alpha_composite(corte)
    fundo = fundo.resize((192, 192), Image.LANCZOS)
    saida = io.BytesIO()
    fundo.convert("RGB").save(saida, "PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(saida.getvalue()).decode()


def montar(fonte, destino, qr, ico):
    html = (AQUI / fonte).read_text(encoding="utf-8")
    html = html.replace("__QR__", qr).replace("__ICONE__", ico).replace("__LINK__", LINK)
    assert "__" not in html.replace("__gc", ""), f"marcador sobrando em {fonte}"
    destino = pathlib.Path(destino)
    destino.write_text(html, encoding="utf-8", newline="\n")
    return destino


def foto(html, png):
    """O cartão em PNG pelo Chrome sem janela: 540 x 960 com a escala 2 (1080 x 1920), esperando as fontes."""
    subprocess.run([str(CHROME), "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--force-device-scale-factor=2", "--window-size=540,960", "--virtual-time-budget=6000",
                    f"--screenshot={AQUI / png}", html.as_uri()], check=True, capture_output=True, timeout=120)


if __name__ == "__main__":
    qr, ico = qr_svg(LINK), icone()
    montar("guia.fonte.html", AQUI / "guia.html", qr, ico)
    # O HTML de cada cartão só existe para a foto: fica numa pasta temporária, fora do repositório.
    with tempfile.TemporaryDirectory() as tmp:
        for n in (1, 2):
            html = montar(f"cartao-{n}.fonte.html", pathlib.Path(tmp) / f"cartao-{n}.html", qr, ico)
            foto(html, f"nexus-field-instalar-{n}.png")
    print("guia.html e os cartões prontos em", AQUI)
