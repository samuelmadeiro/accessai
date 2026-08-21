"""Linha de comando da montagem do dataset."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys

from . import corpus, montagem

VAR_CORPUS = "ACCESSAI_CORPUS"
VAR_SAIDA = "ACCESSAI_DATASET_SAIDA"

SAIDA_OK = 0
SAIDA_CORPUS_INVALIDO = 2
SAIDA_SEM_AMOSTRA = 3
SAIDA_SEM_CORPUS_DEFINIDO = 4


def _do_ambiente(variavel: str) -> pathlib.Path | None:
    valor = os.environ.get(variavel)
    return pathlib.Path(valor).expanduser() if valor else None


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(
        description="Monta o dataset de texto alternativo a partir do corpus.",
        epilog=f"O corpus vem de --corpus ou de {VAR_CORPUS}; a saida, de "
               f"--saida ou de {VAR_SAIDA} (padrao: ./data).")
    analisador.add_argument("--corpus", type=pathlib.Path,
                            help="pasta com manifest.json e raw/")
    analisador.add_argument("--saida", type=pathlib.Path,
                            help="onde escrever alt_texts.jsonl e relatorio.json")
    argumentos = analisador.parse_args(argv)

    # Sem caminho derivado de parents[N] a partir do arquivo do modulo: aquilo
    # funciona em instalacao editavel e aponta para dentro do site-packages
    # quando o pacote e instalado de verdade — que e justamente como o console
    # script `accessai-dataset` roda.
    raiz = argumentos.corpus or _do_ambiente(VAR_CORPUS)
    if raiz is None:
        print(f"informe o corpus com --corpus ou {VAR_CORPUS}", file=sys.stderr)
        return SAIDA_SEM_CORPUS_DEFINIDO

    saida = argumentos.saida or _do_ambiente(VAR_SAIDA) or pathlib.Path("data")

    try:
        relatorio = montagem.montar(raiz, saida)
    except corpus.CorpusInvalidoError as erro:
        print(f"corpus invalido: {erro}", file=sys.stderr)
        return SAIDA_CORPUS_INVALIDO

    resumo = {chave: relatorio[chave] for chave in (
        "documentos", "imagens", "imagens_com_alt", "imagens_sem_alt",
        "alts_distintos", "documentos_com_parte_ilegivel",
        "documentos_com_parte_grande_demais", "veredito")}
    print(json.dumps(resumo, ensure_ascii=False, indent=2))
    print(f"\nescrito em {saida.resolve()}")

    # Codigo de saida diferente quando nao ha amostra: um pipeline que segue em
    # frente com zero amostra treina em nada e reporta metrica de nada.
    return SAIDA_OK if relatorio["alts_distintos"] else SAIDA_SEM_AMOSTRA


if __name__ == "__main__":
    raise SystemExit(main())
