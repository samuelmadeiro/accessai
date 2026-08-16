#!/usr/bin/env python3
"""
Baixa o corpus real de .docx publicos declarado em datasets/sources.json.

Regras (condicao C-3 de docs/architecture/fase-0.md):

  * NAO faz crawling. Baixa exatamente as URLs da lista-semente e nada mais.
  * Valida o tipo REAL do arquivo, nao a extensao (CLAUDE.md secao 5): tem que
    ser um zip contendo [Content_Types].xml e word/document.xml.
  * Escreve o manifesto em datasets/corpus/manifest.json — ESTE vai para o git.
  * Escreve os binarios em datasets/corpus/raw/ — ESTE fica no .gitignore.
    Documento publico real contem dado pessoal (nome, CPF, matricula); comitar
    o binario cria um problema de privacidade irreversivel no historico.

O manifesto e MESCLADO, nunca reescrito do zero: rodar com --limite deixaria de
fora os documentos nao baixados desta vez, e apaga-los do manifesto destruiria a
procedencia de arquivos que continuam em disco.

O SHA-256 e VERIFICADO contra o manifesto existente. Se a origem trocou o
arquivo, o script para com codigo de saida 2 e nao sobrescreve nada: o hash do
manifesto so serve para reprodutibilidade se alguem de fato conferir.

Uso:
    python scripts/fetch-corpus.py
    python scripts/fetch-corpus.py --limite 5
    python scripts/fetch-corpus.py --aceitar-mudanca-de-origem
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone

RAIZ = pathlib.Path(__file__).resolve().parent.parent
FONTES = RAIZ / "datasets" / "sources.json"
DESTINO = RAIZ / "datasets" / "corpus"
BRUTOS = DESTINO / "raw"
MANIFESTO = DESTINO / "manifest.json"

TIMEOUT_S = 30
PAUSA_S = 1.0          # cortesia com o servidor de origem
TAMANHO_MAX = 25 * 1024 * 1024
PREFIXO_DE_DIAGNOSTICO = 80   # bytes do corpo guardados quando o tipo nao bate
USER_AGENT = (
    "AccessAI-corpus-fetcher/0.1 (pesquisa de acessibilidade de documentos; "
    "contato via repositorio do projeto)"
)

SAIDA_OK = 0
SAIDA_ORIGEM_MUDOU = 2


class ErroDeColeta(Exception):
    """Falha esperada de coleta. `detalhe` vai inteiro para o manifesto."""

    def __init__(self, mensagem: str, detalhe: dict | None = None):
        super().__init__(mensagem)
        self.detalhe = detalhe or {}


def baixar(url: str) -> tuple[bytes, str]:
    """Devolve (conteudo, content-type declarado). Levanta ErroDeColeta."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            declarado = resp.headers.get("Content-Type", "")
            buf = io.BytesIO()
            lidos = 0
            while True:
                pedaco = resp.read(64 * 1024)
                if not pedaco:
                    break
                lidos += len(pedaco)
                if lidos > TAMANHO_MAX:
                    raise ErroDeColeta(
                        f"passou de {TAMANHO_MAX} bytes",
                        {"content_type_declarado": declarado},
                    )
                buf.write(pedaco)
            return buf.getvalue(), declarado
    except urllib.error.HTTPError as e:
        raise ErroDeColeta(
            f"HTTP {e.code}",
            {"http_status": e.code, "content_type_declarado": e.headers.get("Content-Type", "")},
        ) from e
    except urllib.error.URLError as e:
        raise ErroDeColeta(f"rede: {e.reason}") from e
    except TimeoutError as e:
        raise ErroDeColeta("timeout") from e


def validar_docx(conteudo: bytes, declarado: str = "") -> dict:
    """Confere que e mesmo um DOCX. Extensao e Content-Type nao sao prova."""
    # Guardado no manifesto quando o tipo nao bate: o caso classico e HTTP 200
    # com Content-Type: text/html e corpo <!DOCTYPE html> numa URL .docx. Sem
    # registrar isso, o relatorio da coleta vira afirmacao sem evidencia.
    diagnostico = {
        "http_status": 200,
        "content_type_declarado": declarado,
        "primeiros_bytes": repr(conteudo[:PREFIXO_DE_DIAGNOSTICO]),
    }
    if conteudo[:2] != b"PK":
        raise ErroDeColeta("nao e zip (sem assinatura PK)", diagnostico)
    try:
        with zipfile.ZipFile(io.BytesIO(conteudo)) as z:
            nomes = set(z.namelist())
            if "[Content_Types].xml" not in nomes:
                raise ErroDeColeta("zip sem [Content_Types].xml", diagnostico)
            if "word/document.xml" not in nomes:
                raise ErroDeColeta(
                    "zip sem word/document.xml (nao e WordprocessingML)", diagnostico)
            partes = sorted(
                n for n in nomes
                if n.startswith("word/") and n.endswith(".xml")
            )
            midias = sorted(n for n in nomes if n.startswith("word/media/"))
            return {"partes_xml": partes, "midias": midias}
    except zipfile.BadZipFile as e:
        raise ErroDeColeta("zip corrompido", diagnostico) from e


def nome_local(indice: int, url: str) -> str:
    base = url.rstrip("/").rsplit("/", 1)[-1]
    base = urllib.parse.unquote(base)
    base = "".join(c if c.isalnum() or c in "-_." else "-" for c in base)
    if base.lower().endswith(".docx"):
        base = base[: -len(".docx")]
    # Truncar o radical, nunca a extensao: cortar o nome inteiro em N caracteres
    # come o ".docx" de nomes longos e o arquivo some de qualquer filtro por
    # extensao. Foi o que aconteceu com o anexo do Governo de Goias.
    return f"{indice:02d}-{base[:70]}.docx"


def carregar_manifesto() -> tuple[dict[str, dict], dict[str, dict]]:
    """Devolve (documentos por url, falhas por url) do manifesto existente."""
    if not MANIFESTO.exists():
        return {}, {}
    dados = json.loads(MANIFESTO.read_text(encoding="utf-8"))
    documentos = {d["url"]: d for d in dados.get("documentos", [])}
    falhas = {f["url"]: f for f in dados.get("falhas", []) if "url" in f}
    return documentos, falhas


def agora_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def escrever_manifesto(documentos: dict[str, dict], falhas: dict[str, dict]) -> None:
    MANIFESTO.parent.mkdir(parents=True, exist_ok=True)
    MANIFESTO.write_text(
        json.dumps(
            {
                "gerado_em": agora_iso(),
                "aviso": (
                    "Os binarios NAO estao versionados. Rode scripts/fetch-corpus.py "
                    "para reconstruir datasets/corpus/raw/ e confira pelo sha256."
                ),
                "documentos": sorted(documentos.values(), key=lambda d: d["arquivo"]),
                "falhas": sorted(falhas.values(), key=lambda f: f.get("url", "")),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--limite", type=int, default=0, help="baixa no maximo N (0 = todos)")
    p.add_argument(
        "--aceitar-mudanca-de-origem",
        action="store_true",
        help="grava o novo conteudo quando o SHA-256 divergir do manifesto",
    )
    args = p.parse_args()

    fontes = json.loads(FONTES.read_text(encoding="utf-8"))["fontes"]
    selecionadas = fontes[: args.limite] if args.limite else fontes

    BRUTOS.mkdir(parents=True, exist_ok=True)
    documentos, falhas = carregar_manifesto()
    divergencias: list[str] = []

    for i, fonte in enumerate(selecionadas, start=1):
        url = fonte["url"]
        print(f"[{i}/{len(selecionadas)}] {fonte['orgao']}")
        try:
            conteudo, declarado = baixar(url)
            estrutura = validar_docx(conteudo, declarado)
        except ErroDeColeta as e:
            print(f"    FALHOU: {e}")
            falhas[url] = {**fonte, "erro": str(e), "detalhe": e.detalhe,
                           "observado_em": agora_iso()}
            # O documento que ja estava no manifesto continua la: a falha de
            # hoje nao apaga a procedencia de uma coleta que deu certo antes.
            time.sleep(PAUSA_S)
            continue

        sha = hashlib.sha256(conteudo).hexdigest()
        anterior = documentos.get(url)

        if anterior and anterior["sha256"] != sha and not args.aceitar_mudanca_de_origem:
            print(f"    DIVERGENCIA: sha256 no manifesto {anterior['sha256'][:12]}..., "
                  f"baixado agora {sha[:12]}...")
            divergencias.append(url)
            falhas.pop(url, None)
            time.sleep(PAUSA_S)
            continue

        arquivo = anterior["arquivo"] if anterior else nome_local(i, url)
        (BRUTOS / arquivo).write_bytes(conteudo)

        documentos[url] = {
            "arquivo": arquivo,
            "url": url,
            "orgao": fonte["orgao"],
            "esfera": fonte["esfera"],
            "categoria": fonte["categoria"],
            "licenca": fonte["licenca"],
            "sha256": sha,
            "bytes": len(conteudo),
            "content_type_declarado": declarado,
            # A primeira coleta e o dado historico; a verificacao e o dado de hoje.
            "coletado_em": anterior["coletado_em"] if anterior else agora_iso(),
            "verificado_em": agora_iso(),
            "partes_xml": estrutura["partes_xml"],
            "midias": estrutura["midias"],
        }
        falhas.pop(url, None)

        print(f"    ok  {len(conteudo):>8} bytes  "
              f"{len(estrutura['partes_xml'])} partes  "
              f"{len(estrutura['midias'])} midias")
        time.sleep(PAUSA_S)

    escrever_manifesto(documentos, falhas)

    print(f"\n{len(documentos)} no manifesto, {len(falhas)} com falha registrada.")
    print(f"manifesto: {MANIFESTO.relative_to(RAIZ)}")

    if divergencias:
        print("\nA ORIGEM MUDOU e nada foi sobrescrito:")
        for url in divergencias:
            print(f"  {url}")
        print("Confira o que mudou na origem. Para aceitar o conteudo novo:")
        print("  python scripts/fetch-corpus.py --aceitar-mudanca-de-origem")
        return SAIDA_ORIGEM_MUDOU

    return SAIDA_OK


if __name__ == "__main__":
    sys.exit(main())
