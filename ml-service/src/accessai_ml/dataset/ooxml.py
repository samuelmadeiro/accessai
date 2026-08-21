"""Leitura de texto alternativo direto do XML do pacote OOXML.

Por que XML direto e nao python-docx (mesmo motivo do ADR 0008, do lado Java):
biblioteca de alto nivel modela o documento que ela entende e descarta o resto.
Alt text mora justamente no que ela descarta — atributos de `wp:docPr` e
`pic:cNvPr` — e imagem em caixa de texto, em cabecalho ou em VML antigo
simplesmente nao aparece na arvore que ela expoe. Silencio de biblioteca vira
"documento sem imagem", que e a resposta errada mais cara possivel aqui.

**A unidade de varredura e a IMAGEM, nao o ancoradouro.** A primeira versao
iterava paragrafos e procurava `w:drawing`/`w:pict` dentro deles, e contava a
mesma imagem tres vezes quando ela estava em caixa de texto: `w:p` pode conter
`w:txbxContent` com outro `w:p` dentro, `iter()` desce nele, e o `w:pict` que
envolve o desenho tambem passava no teste de "e imagem". Partindo dos nos de
imagem (`pic:pic`, `v:imagedata`) e subindo pelo mapa de pais ate o ancoradouro,
cada pixel aparece uma vez so, por construcao.

Duplicata exata nao e so contagem errada no relatorio: e amostra repetida no
treino, e o numero de imagens e exatamente o que sustenta o veredito sobre o
corpus.
"""

from __future__ import annotations

import dataclasses
import xml.etree.ElementTree as ET
import zipfile
from collections.abc import Iterator

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
A = "http://schemas.openxmlformats.org/drawingml/2006/main"
PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
V = "urn:schemas-microsoft-com:vml"
NS_OFFICE = "urn:schemas-microsoft-com:office:office"

TAG_PARAGRAFO = f"{{{W}}}p"
TAG_TEXTO = f"{{{W}}}t"
TAG_INLINE = f"{{{WP}}}inline"
TAG_ANCHOR = f"{{{WP}}}anchor"
TAG_DOCPR = f"{{{WP}}}docPr"
TAG_PIC = f"{{{PIC}}}pic"
TAG_CNVPR = f"{{{PIC}}}cNvPr"
TAG_BLIP = f"{{{A}}}blip"
TAG_IMAGEDATA = f"{{{V}}}imagedata"

MAX_CONTEXTO = 400

# Teto por parte descomprimida. O corpus vem da internet aberta, e arquivo de
# fora e hostil (CONTRIBUTING.md secao 5): um .docx de 472 KB com uma parte
# altamente compressivel expande para 207 MB — razao de 428x, medida. Com o
# limite de 25 MB do fetch-corpus, isso chegaria a ordem de 10 GB em memoria.
# O lado Java nao tem o problema porque usa StAX e nunca materializa a parte.
MAX_PARTE_BYTES = 32 * 1024 * 1024

# Partes de configuracao, que nunca tem conteudo visivel. A selecao e por
# EXCLUSAO, espelhando `Ooxml.ehParteComConteudo` do backend. A lista branca que
# estava aqui antes ja tinha sido derrubada pelo corpus real do lado Java: um
# documento trazia `word/commentsDocument.xml`, nome que nenhuma lista escrita a
# mao teria previsto. Duas definicoes diferentes de "parte com conteudo" fariam
# o Rule Engine apontar uma imagem que o dataset de ML nunca viu.
PARTES_DE_CONFIGURACAO = frozenset({
    "styles.xml", "settings.xml", "webSettings.xml",
    "fontTable.xml", "numbering.xml", "stylesWithEffects.xml",
})

PREFIXOS_DE_COMENTARIO_SEM_TEXTO = ("commentsExtended", "commentsIds",
                                    "commentsExtensible")


@dataclasses.dataclass(frozen=True)
class CandidatoDeAlt:
    """Uma imagem encontrada no pacote, com o alt que ela declara (ou nao)."""

    arquivo: str
    parte: str
    indice: int
    nome: str
    alt: str
    origem_do_alt: str
    contexto_antes: str
    contexto_depois: str

    @property
    def tem_alt(self) -> bool:
        return bool(self.alt.strip())


@dataclasses.dataclass(frozen=True)
class ExtracaoDoDocumento:
    """O que saiu de um pacote, e o que nao deu para ler.

    As partes descartadas viajam junto com os candidatos porque o silencio delas
    e perigoso: um `word/document.xml` que nao parseia — ou que estourou o teto
    de tamanho — produz zero imagens, e zero imagens e indistinguivel de um
    documento que realmente nao tem nenhuma.
    """

    candidatos: list[CandidatoDeAlt]
    partes_ilegiveis: list[str]
    partes_grandes_demais: list[str]


def eh_parte_com_conteudo(nome: str) -> bool:
    """Espelha `Ooxml.ehParteComConteudo` do backend."""
    if not nome.startswith("word/") or not nome.endswith(".xml"):
        return False
    if nome.startswith(("word/theme/", "word/glossary/")):
        return False
    base = nome[len("word/"):]
    if "/" in base:
        return False
    return (base not in PARTES_DE_CONFIGURACAO
            and not base.startswith(PREFIXOS_DE_COMENTARIO_SEM_TEXTO))


def _texto_do_paragrafo(paragrafo: ET.Element) -> str:
    return "".join(t.text or "" for t in paragrafo.iter(TAG_TEXTO)).strip()


def _mapa_de_pais(raiz: ET.Element) -> dict[ET.Element, ET.Element]:
    """ElementTree nao guarda ponteiro para o pai; aqui ele e reconstruido."""
    return {filho: pai for pai in raiz.iter() for filho in pai}


def _ancestral(no: ET.Element, pais: dict[ET.Element, ET.Element],
               *tags: str) -> ET.Element | None:
    atual = pais.get(no)
    while atual is not None:
        if atual.tag in tags:
            return atual
        atual = pais.get(atual)
    return None


def _nos_de_imagem(raiz: ET.Element,
                   pais: dict[ET.Element, ET.Element]) -> Iterator[ET.Element]:
    """Um no por imagem de verdade, sem repetir.

    `pic:pic` cobre DrawingML e `v:imagedata` cobre o VML antigo. `a:blip` so
    entra quando NAO esta dentro de um `pic:pic` — caso raro de desenho que
    referencia a midia direto; contado sempre, viraria duplicata do `pic:pic`.
    """
    yield from raiz.iter(TAG_PIC)
    yield from raiz.iter(TAG_IMAGEDATA)
    for blip in raiz.iter(TAG_BLIP):
        if _ancestral(blip, pais, TAG_PIC) is None:
            yield blip


def _alt_e_nome(no: ET.Element, pais: dict[ET.Element, ET.Element]) -> tuple[str, str, str]:
    """Devolve (alt, origem_do_alt, nome). A ordem e a de precedencia do Word."""
    ancoradouro = _ancestral(no, pais, TAG_INLINE, TAG_ANCHOR)
    nome = ""

    if ancoradouro is not None:
        docpr = next(ancoradouro.iter(TAG_DOCPR), None)
        if docpr is not None:
            nome = (docpr.get("name") or "").strip()
            for atributo in ("descr", "title"):
                valor = (docpr.get(atributo) or "").strip()
                if valor:
                    return valor, f"wp:docPr/@{atributo}", nome

    cnvpr = next(no.iter(TAG_CNVPR), None) if no.tag == TAG_PIC else None
    if cnvpr is not None:
        nome = nome or (cnvpr.get("name") or "").strip()
        valor = (cnvpr.get("descr") or "").strip()
        if valor:
            return valor, "pic:cNvPr/@descr", nome

    if no.tag == TAG_IMAGEDATA:
        for atributo, rotulo in ((f"{{{NS_OFFICE}}}title", "v:imagedata/@o:title"),
                                 ("alt", "v:imagedata/@alt")):
            valor = (no.get(atributo) or "").strip()
            if valor:
                return valor, rotulo, nome

    return "", "ausente", nome


def _ler_parte(pacote: zipfile.ZipFile, parte: str) -> bytes | None:
    """Le a parte com teto. Devolve None quando ela e grande demais.

    A leitura e limitada no fluxo, e nao pelo `file_size` do cabecalho do zip:
    esse campo e escrito por quem montou o arquivo e pode mentir.
    """
    with pacote.open(parte) as fluxo:
        dados = fluxo.read(MAX_PARTE_BYTES + 1)
    return None if len(dados) > MAX_PARTE_BYTES else dados


def extrair_candidatos(caminho_docx: str, arquivo: str) -> ExtracaoDoDocumento:
    """Percorre o pacote e devolve uma entrada por imagem encontrada.

    O contexto e o texto do paragrafo nao vazio imediatamente antes e depois do
    paragrafo que contem a imagem. Ele existe porque a inadequacao de um alt
    costuma so ser visivel ao lado do que esta em volta: "clique aqui" e ruim
    sozinho, e um alt igual a legenda logo abaixo e redundante — nenhum dos dois
    se detecta olhando so o alt.
    """
    candidatos: list[CandidatoDeAlt] = []
    ilegiveis: list[str] = []
    grandes_demais: list[str] = []

    with zipfile.ZipFile(caminho_docx) as pacote:
        for parte in sorted(n for n in pacote.namelist() if eh_parte_com_conteudo(n)):
            dados = _ler_parte(pacote, parte)
            if dados is None:
                grandes_demais.append(parte)
                continue
            try:
                raiz = ET.fromstring(dados)
            except ET.ParseError:
                # Parte ilegivel nao derruba o documento inteiro: as outras
                # continuam valendo. Mas fica registrada — ver ExtracaoDoDocumento.
                ilegiveis.append(parte)
                continue

            pais = _mapa_de_pais(raiz)
            paragrafos = list(raiz.iter(TAG_PARAGRAFO))
            textos = [_texto_do_paragrafo(p) for p in paragrafos]
            posicao_do_paragrafo = {p: i for i, p in enumerate(paragrafos)}

            for no in _nos_de_imagem(raiz, pais):
                alt, origem, nome = _alt_e_nome(no, pais)
                dono = _ancestral(no, pais, TAG_PARAGRAFO)
                posicao = posicao_do_paragrafo.get(dono) if dono is not None else None
                candidatos.append(CandidatoDeAlt(
                    arquivo=arquivo,
                    parte=parte,
                    indice=len(candidatos),
                    nome=nome,
                    alt=alt,
                    origem_do_alt=origem,
                    contexto_antes=_vizinho(textos, posicao, -1),
                    contexto_depois=_vizinho(textos, posicao, +1),
                ))

    return ExtracaoDoDocumento(candidatos=candidatos, partes_ilegiveis=ilegiveis,
                               partes_grandes_demais=grandes_demais)


def _vizinho(textos: list[str], posicao: int | None, passo: int) -> str:
    """Primeiro paragrafo nao vazio na direcao dada."""
    if posicao is None:
        return ""
    i = posicao + passo
    while 0 <= i < len(textos):
        if textos[i]:
            return textos[i][:MAX_CONTEXTO]
        i += passo
    return ""
