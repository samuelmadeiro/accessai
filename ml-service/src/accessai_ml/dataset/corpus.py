"""O corpus visto pelo lado do ML: manifesto, integridade e procedencia.

O manifesto e a fonte de verdade sobre o que foi coletado (`scripts/fetch-corpus.py`
o escreve; os binarios ficam fora do git). Aqui ele volta a ser lido para uma
pergunta diferente: o arquivo em disco ainda e o arquivo que foi coletado?

Isso nao e zelo: dataset montado sobre binario trocado produz metrica que nao
reproduz, e o defeito aparece semanas depois como "o modelo piorou sozinho".
"""
from __future__ import annotations

import dataclasses
import hashlib
import json
import pathlib


class CorpusInvalidoError(Exception):
    """O corpus em disco nao corresponde ao manifesto."""


@dataclasses.dataclass(frozen=True)
class DocumentoDoCorpus:
    arquivo: str
    caminho: pathlib.Path
    sha256: str
    orgao: str
    esfera: str
    categoria: str
    licenca: str
    url: str


def _sha256(caminho: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with caminho.open("rb") as binario:
        for bloco in iter(lambda: binario.read(1024 * 1024), b""):
            digest.update(bloco)
    return digest.hexdigest()


def carregar(raiz_do_corpus: pathlib.Path) -> list[DocumentoDoCorpus]:
    """Le o manifesto e confere cada binario contra o sha256 registrado.

    Documento do manifesto que nao esta em disco e ignorado com registro: o
    manifesto e mesclado por URL e pode citar coleta anterior, feita com
    `--limite`. Documento em disco cujo hash NAO bate interrompe tudo — seguir
    seria montar dataset sobre conteudo de procedencia desconhecida.
    """
    manifesto = raiz_do_corpus / "manifest.json"
    if not manifesto.exists():
        raise CorpusInvalidoError(
            f"manifesto ausente em {manifesto}. Rode scripts/fetch-corpus.py antes."
        )

    dados = json.loads(manifesto.read_text(encoding="utf-8"))
    documentos: list[DocumentoDoCorpus] = []
    divergentes: list[str] = []

    for registro in dados.get("documentos", []):
        caminho = raiz_do_corpus / "raw" / registro["arquivo"]
        if not caminho.exists():
            continue
        if _sha256(caminho) != registro["sha256"]:
            divergentes.append(registro["arquivo"])
            continue
        documentos.append(DocumentoDoCorpus(
            arquivo=registro["arquivo"],
            caminho=caminho,
            sha256=registro["sha256"],
            orgao=registro.get("orgao", "desconhecido"),
            esfera=registro.get("esfera", "desconhecida"),
            categoria=registro.get("categoria", "desconhecida"),
            licenca=registro.get("licenca", "nao declarada"),
            url=registro.get("url", ""),
        ))

    if divergentes:
        raise CorpusInvalidoError(
            "sha256 divergente do manifesto em: " + ", ".join(divergentes)
            + ". O arquivo em disco nao e o que foi coletado; recolete antes de "
              "montar dataset."
        )

    return documentos
