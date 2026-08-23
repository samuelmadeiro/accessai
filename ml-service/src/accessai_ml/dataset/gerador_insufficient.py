"""Gerador deterministico de `alt` reconhecidamente ruim, para quando a coleta falha.

Existe por um motivo estreito: a classe `INSUFFICIENT` e rara em acervo curado —
a coleta do Commons devolveu 6 em 900 — e sem ela o classificador nao aprende a
detectar justamente o que o produto precisa detectar.

**Isto NAO e dataset.** O ADR 0002 abre dizendo que dado gerado e apresentado
como real destroi a credibilidade do projeto. O que salva estas amostras de
serem exatamente isso sao tres coisas, e nenhuma e opcional:

1. `origem_do_dado: "sintetico_fallback"` em cada linha, e a contagem no
   relatorio. Quem ler o dataset sabe quantas linhas ninguem coletou.
2. **Elas so entram no treino**, nunca em validacao nem em teste. Metrica medida
   sobre string que este arquivo escreveu nao mede deteccao de alt ruim no
   mundo — mede se o modelo decorou uma lista que esta no repositorio.
3. Sao fallback. Com URL de verdade disponivel, `coletor_web` coleta e estas
   ficam de fora.

As variacoes vem de padroes observados, nao inventados do nada: nome de arquivo
de camera, hash de CDN, GIF de espacamento de layout antigo, placeholder de
template, e a palavra generica solta.
"""

from __future__ import annotations

import dataclasses
import hashlib

INSUFICIENTE = "INSUFFICIENT"
ORIGEM_SINTETICA = "sintetico_fallback"
FONTE = "sintetico"

# Teto do que este modulo se propoe a produzir. Acima disso a lista deixaria de
# ser "os padroes de alt ruim" e viraria enchimento — cinquenta variacoes do
# mesmo nome de arquivo nao ensinam nada que dez ja nao ensinem.
MAXIMO = 50


@dataclasses.dataclass(frozen=True)
class Sintetica:
    """Um alt gerado, com o padrao que ele representa."""

    alt: str
    padrao: str

    @property
    def id(self) -> str:
        # Id derivado do proprio texto: reexecutar o gerador nao cria linha nova
        # para a mesma string, e a mesclagem consegue reconhecer o repetido.
        digest = hashlib.sha256(self.alt.encode("utf-8")).hexdigest()[:12]
        return f"sintetico:{digest}"


def _nomes_de_arquivo() -> list[Sintetica]:
    """Nome de arquivo vazando para o alt. O caso mais comum de todos."""
    brutos = [
        "IMG_0001.jpg", "IMG_4821.JPG", "DSC_0142.jpg", "DSCN2201.JPG",
        "P1010334.JPG", "image1.png", "image_02.png", "img-03.gif",
        "foto (3).jpeg", "foto1.jpg", "Captura de tela 2024-03-11 101122.png",
        "Screenshot 2023-11-02 at 09.14.55.png", "Sem titulo-1.psd",
        "Documento1.png", "Imagem colada.png", "unnamed.jpg", "download.png",
    ]
    return [Sintetica(alt=bruto, padrao="nome_de_arquivo") for bruto in brutos]


def _hashes() -> list[Sintetica]:
    """Nome gerado por CDN ou por upload em massa."""
    brutos = [
        "3f2a9c1e8b7d.jpg", "a1b2c3d4e5f6.png", "0c9f4e2b.webp",
        "b47e1f90ac33d2.jpeg", "f1e2d3c4b5a6978.png", "e3b0c44298fc.gif",
        "1x1.gif", "spacer.gif", "pixel.png", "blank.gif", "dot.png",
    ]
    return [Sintetica(alt=bruto, padrao="hash_ou_placeholder_de_layout")
            for bruto in brutos]


def _genericos() -> list[Sintetica]:
    """A palavra solta que descreve a categoria da coisa, nao a coisa."""
    # Sem variante de caixa nem de acento: o grupo do dataset e o alt
    # NORMALIZADO (`divisao.normalizar_alt`), entao "imagem", "Imagem" e
    # "IMAGEM" sao a mesma amostra. Inclui-las inflaria a contagem e depois a
    # mesclagem descartaria as repetidas, entregando menos que a cota pedida.
    brutos = [
        "imagem", "foto", "figura", "image", "picture", "logo", "logotipo",
        "banner", "ícone", "thumbnail", "avatar", "midia", "arte", "capa",
    ]
    return [Sintetica(alt=bruto, padrao="termo_generico") for bruto in brutos]


def _placeholders() -> list[Sintetica]:
    """Texto de template que ninguem trocou."""
    brutos = [
        "alt", "alt text", "texto alternativo", "descricao da imagem",
        "TODO", "xxx", "teste", "lorem ipsum", "n/a", "-", "...", "0", "123",
    ]
    return [Sintetica(alt=bruto, padrao="placeholder") for bruto in brutos]


def catalogo() -> list[Sintetica]:
    """Todas as variacoes, em ordem fixa.

    Ordem fixa e o que torna o gerador deterministico de verdade: duas execucoes
    produzem as mesmas linhas, com os mesmos ids, e a mesclagem no dataset e
    idempotente. Embaralhar aqui — mesmo com semente — faria `--cota 20` trazer
    um conjunto diferente a cada mudanca no catalogo.

    Os grupos vem intercalados, e nao um bloco de cada padrao: com `--cota 20` um
    catalogo em blocos entregaria so nomes de arquivo, e o treino aprenderia
    "termina em .jpg" em vez de "nao descreve".
    """
    grupos = [_nomes_de_arquivo(), _hashes(), _genericos(), _placeholders()]
    intercalado: list[Sintetica] = []
    for posicao in range(max(len(grupo) for grupo in grupos)):
        for grupo in grupos:
            if posicao < len(grupo):
                intercalado.append(grupo[posicao])
    return intercalado


def gerar(quantidade: int = MAXIMO) -> list[Sintetica]:
    """As primeiras `quantidade` variacoes do catalogo, ate `MAXIMO`."""
    if quantidade < 0:
        raise ValueError("quantidade nao pode ser negativa.")
    return catalogo()[:min(quantidade, MAXIMO)]
