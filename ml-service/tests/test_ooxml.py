"""Extracao de texto alternativo do pacote OOXML."""

from __future__ import annotations

import pathlib

import pytest

import docx_de_teste as docx
from accessai_ml.dataset import ooxml


def escrever(tmp_path: pathlib.Path, conteudo: bytes) -> str:
    caminho = tmp_path / "documento.docx"
    caminho.write_bytes(conteudo)
    return str(caminho)


def test_alt_preenchido_vira_candidato(tmp_path):
    arquivo = escrever(tmp_path, docx.pacote(
        docx.imagem(descr="Grafico de barras com a evolucao do orcamento")))

    candidatos = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos

    assert len(candidatos) == 1
    assert candidatos[0].alt == "Grafico de barras com a evolucao do orcamento"
    assert candidatos[0].origem_do_alt == "wp:docPr/@descr"
    assert candidatos[0].tem_alt is True


def test_imagem_sem_alt_sai_marcada_e_nao_some(tmp_path):
    # Ela nao vira amostra de treino, mas some-la falsearia a razao entre
    # imagens com e sem alt, que e o numero que diz se o corpus sustenta modelo.
    arquivo = escrever(tmp_path, docx.pacote(docx.imagem(descr=None)))

    candidatos = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos

    assert len(candidatos) == 1
    assert candidatos[0].tem_alt is False
    assert candidatos[0].origem_do_alt == "ausente"


@pytest.mark.parametrize("valor", ["", "   "])
def test_alt_em_branco_nao_conta_como_alt(tmp_path, valor):
    arquivo = escrever(tmp_path, docx.pacote(docx.imagem(descr=valor)))

    candidatos = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos

    assert candidatos[0].tem_alt is False


def test_caixa_de_texto_nao_e_imagem(tmp_path):
    # wp:docPr existe em qualquer desenho. Conta-lo direto transformaria
    # autoforma decorativa em imagem sem alt — falso positivo no relatorio.
    arquivo = escrever(tmp_path, docx.pacote(docx.caixa_de_texto()))

    assert ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos == []


def test_contexto_vem_dos_paragrafos_vizinhos_nao_vazios(tmp_path):
    arquivo = escrever(tmp_path, docx.pacote(
        docx.paragrafo("Figura anterior ao grafico"),
        docx.paragrafo(""),
        docx.imagem(descr="grafico"),
        docx.paragrafo(""),
        docx.paragrafo("Fonte: Tribunal de Contas"),
    ))

    candidato = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos[0]

    assert candidato.contexto_antes == "Figura anterior ao grafico"
    assert candidato.contexto_depois == "Fonte: Tribunal de Contas"


def test_imagem_de_cabecalho_entra(tmp_path):
    # Logotipo institucional mora no cabecalho e e imagem como outra qualquer.
    arquivo = escrever(tmp_path, docx.pacote(
        docx.paragrafo("corpo"),
        partes={"word/header1.xml": docx.parte_de_cabecalho(
            docx.imagem(descr="Brasao da Republica", nome="Brasao"))},
    ))

    candidatos = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos

    assert [c.parte for c in candidatos] == ["word/header1.xml"]
    assert candidatos[0].alt == "Brasao da Republica"


def test_parte_ilegivel_nao_derruba_o_documento_mas_fica_registrada(tmp_path):
    arquivo = escrever(tmp_path, docx.pacote(
        docx.imagem(descr="valida"),
        partes={"word/header1.xml": "<w:hdr><w:body>"},
    ))

    extracao = ooxml.extrair_candidatos(arquivo, "documento.docx")

    assert [c.alt for c in extracao.candidatos] == ["valida"]
    # Sem esta lista, XML quebrado e documento sem imagem dao a mesma contagem.
    assert extracao.partes_ilegiveis == ["word/header1.xml"]


def test_pacote_integro_nao_reporta_parte_ilegivel(tmp_path):
    arquivo = escrever(tmp_path, docx.pacote(docx.imagem(descr="valida")))

    assert ooxml.extrair_candidatos(arquivo, "documento.docx").partes_ilegiveis == []


# ------------------------------------------------- F1: sem contagem duplicada

def test_imagem_em_caixa_de_texto_conta_uma_vez(tmp_path):
    # Antes contava 3: o w:p externo enxergava o w:drawing (iter desce no
    # w:txbxContent), enxergava tambem o w:pict que o envolve, e o w:p aninhado
    # repetia o mesmo desenho.
    arquivo = escrever(tmp_path, docx.pacote(
        docx.caixa_de_texto_com_imagem(descr="grafico na caixa")))

    candidatos = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos

    assert len(candidatos) == 1
    assert candidatos[0].alt == "grafico na caixa"


def test_duas_imagens_continuam_sendo_duas(tmp_path):
    arquivo = escrever(tmp_path, docx.pacote(
        docx.imagem(descr="primeira"),
        docx.caixa_de_texto_com_imagem(descr="segunda"),
    ))

    candidatos = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos

    assert sorted(c.alt for c in candidatos) == ["primeira", "segunda"]


def test_imagem_vml_com_titulo_e_lida(tmp_path):
    arquivo = escrever(tmp_path, docx.pacote(docx.imagem_vml(titulo="Selo antigo")))

    candidatos = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos

    assert len(candidatos) == 1
    assert candidatos[0].alt == "Selo antigo"
    assert candidatos[0].origem_do_alt == "v:imagedata/@o:title"


# ------------------------------------------------------- F2: teto de tamanho

def test_parte_grande_demais_e_descartada_e_registrada(tmp_path, monkeypatch):
    # 472 KB comprimidos viraram 207 MB descomprimidos na medicao do review.
    monkeypatch.setattr(ooxml, "MAX_PARTE_BYTES", 2048)
    recheio = docx.paragrafo("A" * 5000)
    arquivo = escrever(tmp_path, docx.pacote(recheio, docx.imagem(descr="perdida")))

    extracao = ooxml.extrair_candidatos(arquivo, "documento.docx")

    assert extracao.partes_grandes_demais == ["word/document.xml"]
    assert extracao.candidatos == []


def test_parte_dentro_do_teto_passa(tmp_path, monkeypatch):
    monkeypatch.setattr(ooxml, "MAX_PARTE_BYTES", 1024 * 1024)
    arquivo = escrever(tmp_path, docx.pacote(docx.imagem(descr="cabe")))

    extracao = ooxml.extrair_candidatos(arquivo, "documento.docx")

    assert extracao.partes_grandes_demais == []
    assert [c.alt for c in extracao.candidatos] == ["cabe"]


# ------------------------------- F3: selecao de partes por exclusao, como Java

def test_imagem_em_nota_de_rodape_entra(tmp_path):
    arquivo = escrever(tmp_path, docx.pacote(
        docx.paragrafo("corpo"),
        partes={"word/footnotes.xml": docx.parte_generica(
            "w:footnotes", docx.imagem(descr="figura da nota"))},
    ))

    candidatos = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos

    assert [c.alt for c in candidatos] == ["figura da nota"]


def test_imagem_em_comentario_entra(tmp_path):
    # commentsDocument.xml foi o nome que derrubou a lista branca do lado Java.
    arquivo = escrever(tmp_path, docx.pacote(
        docx.paragrafo("corpo"),
        partes={"word/commentsDocument.xml": docx.parte_generica(
            "w:comments", docx.imagem(descr="print no comentario"))},
    ))

    candidatos = ooxml.extrair_candidatos(arquivo, "documento.docx").candidatos

    assert [c.alt for c in candidatos] == ["print no comentario"]


@pytest.mark.parametrize("parte", [
    "word/styles.xml", "word/numbering.xml", "word/fontTable.xml",
    "word/settings.xml", "word/theme/theme1.xml",
    "word/commentsExtended.xml", "word/_rels/document.xml.rels",
])
def test_partes_de_configuracao_ficam_de_fora(parte):
    # numbering.xml pode ter w:numPicBullet — marcador de lista, decorativo.
    assert ooxml.eh_parte_com_conteudo(parte) is False


@pytest.mark.parametrize("parte", [
    "word/document.xml", "word/header1.xml", "word/footer2.xml",
    "word/footnotes.xml", "word/endnotes.xml", "word/commentsDocument.xml",
])
def test_partes_de_conteudo_entram(parte):
    assert ooxml.eh_parte_com_conteudo(parte) is True
