"""Monta pacotes .docx em memoria para os testes.

Mesma escolha do lado Java (`DocxDeTeste`): nenhum `.docx` binario no
repositorio. O XML fica a vista ao lado da assercao, e um zip commitado e um
arquivo que ninguem revisa em pull request.
"""

from __future__ import annotations

import io
import zipfile

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml"
            ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

ABERTURA = (
    '<w:document '
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
    'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
    'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" '
    'xmlns:v="urn:schemas-microsoft-com:vml" '
    'xmlns:o="urn:schemas-microsoft-com:office:office" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
    '><w:body>'
)
FECHAMENTO = "</w:body></w:document>"


def paragrafo(texto: str) -> str:
    return f"<w:p><w:r><w:t>{texto}</w:t></w:r></w:p>"


def imagem(descr: str | None = None, nome: str = "Imagem 1") -> str:
    """Imagem de verdade: docPr com pic:pic e a:blip na subarvore."""
    atributo_descr = f' descr="{descr}"' if descr is not None else ""
    return (
        "<w:p><w:r><w:drawing><wp:inline>"
        f'<wp:docPr id="1" name="{nome}"{atributo_descr}/>'
        "<a:graphic><a:graphicData><pic:pic>"
        f'<pic:nvPicPr><pic:cNvPr id="1" name="{nome}"/></pic:nvPicPr>'
        '<pic:blipFill><a:blip r:embed="rId1"/></pic:blipFill>'
        "</pic:pic></a:graphicData></a:graphic>"
        "</wp:inline></w:drawing></w:r></w:p>"
    )


def caixa_de_texto(nome: str = "Caixa 1") -> str:
    """Desenho que NAO e imagem: docPr sem pic/blip/imagedata na subarvore."""
    return (
        "<w:p><w:r><w:drawing><wp:inline>"
        f'<wp:docPr id="9" name="{nome}"/>'
        "<a:graphic><a:graphicData/></a:graphic>"
        "</wp:inline></w:drawing></w:r></w:p>"
    )


def caixa_de_texto_com_imagem(descr: str = "grafico na caixa") -> str:
    """Imagem dentro de w:txbxContent: um w:p aninhado dentro de outro w:p.

    Era a forma que fazia a versao antiga contar a mesma imagem tres vezes.
    """
    return (
        "<w:p><w:r><w:pict><v:shape><v:textbox><w:txbxContent>"
        + imagem(descr=descr)
        + "</w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"
    )


def imagem_vml(titulo: str | None = None) -> str:
    """Imagem no VML antigo, onde o alt mora em o:title ou @alt."""
    atributo = f' o:title="{titulo}"' if titulo is not None else ""
    return (
        "<w:p><w:r><w:pict><v:shape>"
        f'<v:imagedata r:id="rId9"{atributo}/>'
        "</v:shape></w:pict></w:r></w:p>"
    )


def parte_generica(raiz: str, *corpo: str) -> str:
    """Parte de conteudo qualquer (footnotes, comentarios, ...)."""
    return (ABERTURA.replace("w:document", raiz) + "".join(corpo)
            + FECHAMENTO.replace("w:document", raiz))


def pacote(*corpo: str, partes: dict[str, str] | None = None) -> bytes:
    documento = ABERTURA + "".join(corpo) + FECHAMENTO
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zip_do_pacote:
        zip_do_pacote.writestr("[Content_Types].xml", CONTENT_TYPES)
        zip_do_pacote.writestr("word/document.xml", documento)
        for nome, conteudo in (partes or {}).items():
            zip_do_pacote.writestr(nome, conteudo)
    return buffer.getvalue()


def parte_de_cabecalho(*corpo: str) -> str:
    return ABERTURA.replace("w:document", "w:hdr") + "".join(corpo) \
        + FECHAMENTO.replace("w:document", "w:hdr")
