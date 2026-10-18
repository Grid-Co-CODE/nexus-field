"""Gera os ícones do Nexus Field a partir do G da Grid em branco (docs/marca/grid-g-branco.png).

  python ferramentas/gerar-icones.py                      refaz os mipmaps a partir da cópia branca do repositório
  python ferramentas/gerar-icones.py --verde <arquivo>    refaz antes a cópia branca, a partir do G verde do Nexus
                                                          (nexus/torres/oscreator/os_creator/assets/grid-icon.png,
                                                          no repositório Grid-Co-CODE/nexus; o original só é lido)

Precisa do Pillow (pip install --user pillow). O resultado é o mesmo a cada execução com o mesmo Pillow, e o
testes/icone.test.mjs confere o que sai: o G inteiro na zona segura, branco puro, centrado, em toda densidade.

Por que assim (pedido do dono da casca, 17/10/2026: o app é o Nexus Field, e o ícone é o G da Grid em branco cabendo
no ícone): o ícone de antes punha o logo do site em 61,6% do quadro de 108 dp, além do círculo de 66 dp que toda
máscara mostra (círculo, squircle, gota), e ele podia sair cortado no aparelho. Aqui o G ocupa 52% do quadro: o G é
quase um círculo (o pixel mais longe do centro fica a 1,002 da meia largura), então o raio dele é ~28,1 dp, dentro dos
33 dp da zona segura com folga para o serrilhado.
"""

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit('gerar-icones: falta o Pillow (pip install --user pillow)')

RAIZ = Path(__file__).resolve().parent.parent
COPIA_BRANCA = RAIZ / 'docs' / 'marca' / 'grid-g-branco.png'
RES = RAIZ / 'android' / 'app' / 'src' / 'main' / 'res'

# O navy do Nexus, o mesmo do res/values/ic_launcher_background.xml (o teste compara os dois pela prévia).
FUNDO = (0x09, 0x0D, 0x18)
# A largura do G sobre a largura do quadro de 108 dp. O pedido é no máximo ~56%; 52% deixa o G grande e com folga.
PROPORCAO_DO_G = 0.52
QUADRO_DP = 108
VISIVEL_DP = 72   # o que a máscara mostra do quadro: o ícone antigo (48 dp) é essa parte
LEGADO_DP = 48
DENSIDADES = (('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4))


def branquear(verde):
    """O G em branco puro, com o alfa do original (a borda suave fica). A cor do original não importa."""
    if verde.mode != 'RGBA':
        sys.exit(f'gerar-icones: o original tem de ser RGBA com transparência (veio {verde.mode})')
    branco = Image.new('RGBA', verde.size, (255, 255, 255, 0))
    branco.putalpha(verde.getchannel('A'))
    return branco


def g_recortado(branco):
    """O G sem a margem transparente em volta (a caixa dos pixels visíveis)."""
    caixa = branco.getchannel('A').getbbox()
    if caixa is None:
        sys.exit('gerar-icones: a cópia branca está vazia')
    return branco.crop(caixa)


def alfa_no_quadro(g, proporcao):
    """O alfa do G centrado num quadro quadrado em que ele ocupa `proporcao` da largura, na resolução do original."""
    lado = round(g.width / proporcao)
    quadro = Image.new('L', (lado, lado), 0)
    quadro.paste(g.getchannel('A'), ((lado - g.width) // 2, (lado - g.height) // 2))
    return quadro


def reduzir(alfa, px):
    # BOX é a média da área: a borda do G sai serrilhada pela cobertura de verdade, sem o halo que o LANCZOS deixaria
    # para fora do contorno (e que contaria como pixel fora da zona segura).
    return alfa.resize((px, px), Image.Resampling.BOX)


def branco_com(alfa):
    imagem = Image.new('RGBA', alfa.size, (255, 255, 255, 0))
    imagem.putalpha(alfa)
    return imagem


def legado(alfa_do_g, redondo):
    """O ícone antigo na resolução do original: o fundo navy (o quadrado inteiro ou o círculo) com o G branco por cima."""
    lado = alfa_do_g.width
    forma = Image.new('L', (lado, lado), 0)
    if redondo:
        ImageDraw.Draw(forma).ellipse((0, 0, lado - 1, lado - 1), fill=255)
    else:
        forma.paste(255, (0, 0, lado, lado))
    imagem = Image.new('RGBA', (lado, lado), FUNDO + (0,))
    imagem.putalpha(forma)
    imagem.alpha_composite(branco_com(alfa_do_g))
    return imagem


def salvar(imagem, caminho):
    caminho.parent.mkdir(parents=True, exist_ok=True)
    imagem.save(caminho, optimize=True)
    print(f'gerar-icones: {caminho.relative_to(RAIZ).as_posix()} ({imagem.width} x {imagem.height})')


def main():
    args = argparse.ArgumentParser(description='Gera os ícones do Nexus Field.')
    args.add_argument('--verde', type=Path, help='o grid-icon.png verde do Nexus, para refazer a cópia branca')
    opcoes = args.parse_args()

    if opcoes.verde:
        with Image.open(opcoes.verde) as verde:
            salvar(branquear(verde.convert('RGBA') if verde.mode == 'P' else verde), COPIA_BRANCA)

    with Image.open(COPIA_BRANCA) as copia:
        g = g_recortado(copia.convert('RGBA'))

    frente = alfa_no_quadro(g, PROPORCAO_DO_G)
    # No ícone antigo, os 72 dp visíveis do quadro de 108 viram os 48 dp: o G fica do mesmo tamanho que no adaptativo.
    alfa_legado = alfa_no_quadro(g, PROPORCAO_DO_G * QUADRO_DP / VISIVEL_DP)
    quadrado = legado(alfa_legado, redondo=False)
    redondo = legado(alfa_legado, redondo=True)

    for pasta, escala in DENSIDADES:
        destino = RES / f'mipmap-{pasta}'
        salvar(branco_com(reduzir(frente, round(QUADRO_DP * escala))), destino / 'ic_launcher_foreground.png')
        lado = round(LEGADO_DP * escala)
        # O quadrado e o redondo vêm prontos da resolução do original: o resize do Pillow faz a média com o alfa
        # pré-multiplicado, e a borda do círculo não escurece.
        salvar(quadrado.resize((lado, lado), Image.Resampling.BOX), destino / 'ic_launcher.png')
        salvar(redondo.resize((lado, lado), Image.Resampling.BOX), destino / 'ic_launcher_round.png')


if __name__ == '__main__':
    main()
