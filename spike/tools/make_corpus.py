"""
Gera o corpus sintetico de .docx do spike de extracao de alt text.

AVISO DE PROCEDENCIA: estes arquivos sao SINTETICOS. Foram modelados nas
estruturas OOXML que Word, Google Docs e LibreOffice realmente produzem, mas
nao sao exports reais desses programas. Cada caso documenta qual exportador
ele imita e qual armadilha exercita. O unico arquivo real do corpus e
referenciado por caminho externo no teste (ver RealWorldSampleTest).

Uso:  python tools/make_corpus.py src/test/resources/corpus
"""

import base64
import os
import sys
import zipfile

# PNG 1x1 transparente. Conteudo da imagem e irrelevante para alt text;
# existe so para que o .docx seja um pacote OOXML coerente.
PNG_1X1 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
    "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)

NS = (
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
    'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" '
    'xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" '
    'xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" '
    'xmlns:v="urn:schemas-microsoft-com:vml" '
    'xmlns:o="urn:schemas-microsoft-com:office:office" '
    'mc:Ignorable="w14 wp14"'
)

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  {header_override}
</Types>"""

HEADER_OVERRIDE = (
    '<Override PartName="/word/header2.xml" ContentType="application/vnd.'
    'openxmlformats-officedocument.wordprocessingml.header+xml"/>'
)

ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""


def doc_rels(with_header: bool) -> str:
    header_rel = (
        '<Relationship Id="rIdHdr" Type="http://schemas.openxmlformats.org/'
        'officeDocument/2006/relationships/header" Target="header2.xml"/>'
        if with_header
        else ""
    )
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
  <Relationship Id="rId6" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
  <Relationship Id="rId7" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
  {header_rel}
</Relationships>"""


def graphic(rid: str) -> str:
    """Bloco a:graphic comum a inline e anchor."""
    return (
        "<a:graphic><a:graphicData "
        'uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
        "<pic:pic><pic:nvPicPr>"
        '<pic:cNvPr id="0" name="image1.png"/><pic:cNvPicPr/>'
        "</pic:nvPicPr>"
        f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/>'
        "</a:stretch></pic:blipFill>"
        "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>"
        '<a:ext cx="914400" cy="914400"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>'
        "</pic:pic></a:graphicData></a:graphic>"
    )


def docpr(pid: int, name: str, descr, title=None) -> str:
    """descr=None significa atributo AUSENTE (diferente de descr="")."""
    attrs = f'id="{pid}" name="{name}"'
    if descr is not None:
        attrs += f' descr="{descr}"'
    if title is not None:
        attrs += f' title="{title}"'
    return f"<wp:docPr {attrs}/>"


def inline_image(pid, name, descr, rid="rId5", title=None) -> str:
    return (
        "<w:p><w:r><w:drawing>"
        '<wp:inline distT="0" distB="0" distL="0" distR="0">'
        '<wp:extent cx="914400" cy="914400"/>'
        f"{docpr(pid, name, descr, title)}"
        f"{graphic(rid)}"
        "</wp:inline></w:drawing></w:r></w:p>"
    )


def anchor_image(pid, name, descr, rid="rId5") -> str:
    return (
        "<w:p><w:r><w:drawing>"
        '<wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" '
        'relativeHeight="1" behindDoc="0" locked="0" layoutInCell="1" '
        'allowOverlap="1">'
        '<wp:simplePos x="0" y="0"/>'
        '<wp:positionH relativeFrom="column"><wp:posOffset>0</wp:posOffset></wp:positionH>'
        '<wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV>'
        '<wp:extent cx="914400" cy="914400"/>'
        "<wp:wrapNone/>"
        f"{docpr(pid, name, descr)}"
        f"{graphic(rid)}"
        "</wp:anchor></w:drawing></w:r></w:p>"
    )


def vml_image(alt, rid="rId5") -> str:
    """Caminho legado VML: <w:pict><v:shape alt="..."/>."""
    alt_attr = f' alt="{alt}"' if alt is not None else ""
    return (
        "<w:p><w:r><w:pict>"
        f'<v:shape id="_x0000_s1026"{alt_attr} '
        'style="width:72pt;height:72pt">'
        f'<v:imagedata r:id="{rid}"/>'
        "</v:shape></w:pict></w:r></w:p>"
    )


def alternate_content(pid, name, descr, rid="rId5") -> str:
    """Word envolve alguns desenhos em mc:AlternateContent.

    O MESMO desenho aparece duas vezes: uma em mc:Choice (moderno) e outra
    em mc:Fallback (VML legado). Um extrator ingenuo conta duas imagens.
    """
    return (
        "<w:p><w:r><mc:AlternateContent>"
        '<mc:Choice Requires="wps"><w:drawing>'
        '<wp:inline distT="0" distB="0" distL="0" distR="0">'
        '<wp:extent cx="914400" cy="914400"/>'
        f"{docpr(pid, name, descr)}"
        f"{graphic(rid)}"
        "</wp:inline></w:drawing></mc:Choice>"
        "<mc:Fallback><w:pict>"
        f'<v:shape id="_x0000_s1027" alt="{descr or ""}" '
        'style="width:72pt;height:72pt">'
        f'<v:imagedata r:id="{rid}"/></v:shape>'
        "</w:pict></mc:Fallback>"
        "</mc:AlternateContent></w:r></w:p>"
    )


def document(body: str, with_header: bool = False) -> str:
    header_ref = (
        '<w:sectPr><w:headerReference w:type="default" r:id="rIdHdr"/></w:sectPr>'
        if with_header
        else "<w:sectPr/>"
    )
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        f"<w:document {NS}><w:body>{body}{header_ref}</w:body></w:document>"
    )


def header_part(body: str) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        f"<w:hdr {NS}>{body}</w:hdr>"
    )


def para(text: str) -> str:
    return f"<w:p><w:r><w:t>{text}</w:t></w:r></w:p>"


# ---------------------------------------------------------------------------
# Os 10 casos. Cada tupla: (nome, exportador imitado, armadilha, doc, header)
# ---------------------------------------------------------------------------

CASES = [
    (
        "01-word-inline-alt-presente",
        "Word",
        "caso feliz: wp:inline com descr preenchido",
        document(para("Relatorio") + inline_image(1, "grafico.png", "Grafico de barras da receita trimestral")),
        None,
    ),
    (
        "02-word-inline-alt-vazio",
        "Word",
        'descr="" — imagem marcada como decorativa, NAO e alt faltando',
        document(para("Divisoria") + inline_image(1, "linha.png", "")),
        None,
    ),
    (
        "03-word-inline-alt-ausente",
        "Word",
        "atributo descr inexistente — este sim e alt faltando",
        document(para("Foto") + inline_image(1, "foto.png", None)),
        None,
    ),
    (
        "04-gdocs-anchor-sem-descr",
        "Google Docs",
        "wp:anchor em vez de wp:inline, docPr so com id e name "
        "(estrutura identica a do arquivo real encontrado na maquina)",
        document(para("Glossario") + anchor_image(1, "image1.png", None)),
        None,
    ),
    (
        "05-libreoffice-descr-e-title",
        "LibreOffice",
        "escreve descr E title; descr tem precedencia",
        document(
            para("Diagrama")
            + inline_image(1, "diagrama.png", "Fluxograma do processo de compra", title="Diagrama 1")
        ),
        None,
    ),
    (
        "06-word-altcontent-docpr-duplicado",
        "Word",
        "mc:AlternateContent repete o desenho em Choice e Fallback — "
        "deve contar UMA imagem, nao duas",
        document(para("Caixa de texto") + alternate_content(1, "caixa.png", "Selo de acessibilidade")),
        None,
    ),
    (
        "07-word-vml-pict-alt",
        "Word (legado)",
        "caminho VML antigo: w:pict/v:shape/@alt, sem wp:docPr",
        document(para("Logotipo") + vml_image("Logotipo da prefeitura")),
        None,
    ),
    (
        "08-header-imagem-alt-ausente",
        "Google Docs",
        "imagem so no cabecalho — quem le apenas document.xml nao ve nada",
        document(para("Corpo sem imagem"), with_header=True),
        header_part(anchor_image(1, "brasao.png", None)),
    ),
    (
        "09-word-multi-imagem-mista",
        "Word",
        "tres imagens no mesmo documento: presente, vazia e ausente",
        document(
            para("Anexo")
            + inline_image(1, "a.png", "Mapa da regiao sul", rid="rId5")
            + inline_image(2, "b.png", "", rid="rId6")
            + inline_image(3, "c.png", None, rid="rId7")
        ),
        None,
    ),
    (
        "10-word-alt-so-espacos",
        "Word",
        'descr="   " — texto presente mas em branco; conta como vazio, '
        "nao como preenchido",
        document(para("Assinatura") + inline_image(1, "assin.png", "   ")),
        None,
    ),
]


def build(out_dir: str) -> None:
    os.makedirs(out_dir, exist_ok=True)
    for name, exporter, trap, doc_xml, hdr_xml in CASES:
        path = os.path.join(out_dir, f"{name}.docx")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr(
                "[Content_Types].xml",
                CONTENT_TYPES.format(header_override=HEADER_OVERRIDE if hdr_xml else ""),
            )
            z.writestr("_rels/.rels", ROOT_RELS)
            z.writestr("word/document.xml", doc_xml)
            z.writestr("word/_rels/document.xml.rels", doc_rels(hdr_xml is not None))
            z.writestr("word/media/image1.png", PNG_1X1)
            if hdr_xml:
                z.writestr("word/header2.xml", hdr_xml)
        print(f"  {name}.docx  [{exporter}] {trap}")


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "src/test/resources/corpus"
    print(f"Gerando corpus sintetico em {target}/")
    build(target)
    print(f"\n{len(CASES)} arquivos gerados. TODOS SINTETICOS - ver aviso no topo.")
