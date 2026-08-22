"""Leitura do dataset rotulado para treino.

O JSONL escrito por `accessai_ml.dataset` traz `rotulo: null` em toda linha:
ninguem rotulou ainda. Este modulo le o mesmo formato e separa o que TEM rotulo,
respeitando a coluna `divisao` — que ja foi calculada com a chave de
deduplicacao semantica de `dataset.divisao`.

Reaproveitar a coluna, em vez de re-sortear aqui, e o que garante que a protecao
contra vazamento sobreviva: um `train_test_split` neste ponto jogaria fora o
agrupamento por alt normalizado e devolveria o vazamento pela porta dos fundos.
"""

from __future__ import annotations

import dataclasses
import json
import pathlib
from typing import Any

from ..dataset import divisao

ROTULOS_VALIDOS = ("GOOD", "WEAK", "INSUFFICIENT")

# Minimo para que a divisao faca sentido. Abaixo disso, a metrica e ruido: uma
# unica amostra trocada de lado move a macro-F1 em dezenas de pontos.
MINIMO_POR_CLASSE = 3
MINIMO_TOTAL = 15


class DatasetInvalidoError(Exception):
    """O dataset nao sustenta um treino, e a razao esta na mensagem."""


@dataclasses.dataclass(frozen=True)
class Amostra:
    id: str
    texto: str
    rotulo: str
    grupo: str
    divisao: str

    @property
    def contexto_disponivel(self) -> bool:
        return False


@dataclasses.dataclass(frozen=True)
class Conjuntos:
    treino: list[Amostra]
    validacao: list[Amostra]
    teste: list[Amostra]

    @property
    def total(self) -> int:
        return len(self.treino) + len(self.validacao) + len(self.teste)

    def textos(self, parte: list[Amostra]) -> list[str]:
        return [a.texto for a in parte]

    def rotulos(self, parte: list[Amostra]) -> list[str]:
        return [a.rotulo for a in parte]


def _linha_para_amostra(linha: dict[str, Any], numero: int) -> Amostra | None:
    """Converte uma linha do JSONL. Devolve None quando ela nao e treinavel."""
    rotulo = linha.get("rotulo")
    if rotulo is None:
        return None
    if rotulo not in ROTULOS_VALIDOS:
        raise DatasetInvalidoError(
            f"linha {numero}: rotulo {rotulo!r} fora de {ROTULOS_VALIDOS}. "
            "Rotulo desconhecido em silencio vira classe fantasma no treino.")

    texto = (linha.get("alt") or "").strip()
    if not texto:
        raise DatasetInvalidoError(
            f"linha {numero}: rotulada como {rotulo} mas sem alt. "
            "Alt ausente e deteccao deterministica do Rule Engine, nao amostra "
            "de ML (CONTRIBUTING.md secao 2).")

    identificador = str(linha.get("id") or f"linha-{numero}")
    grupo = str(linha.get("grupo") or divisao.chave_de_agrupamento(texto, identificador))
    parte = linha.get("divisao")
    if parte not in divisao.PARTES:
        raise DatasetInvalidoError(
            f"linha {numero}: divisao {parte!r} fora de {divisao.PARTES}.")

    return Amostra(id=identificador, texto=texto, rotulo=rotulo, grupo=grupo,
                   divisao=str(parte))


def carregar(caminho: pathlib.Path) -> Conjuntos:
    """Le o JSONL e separa por divisao. Levanta quando nao da para treinar."""
    if not caminho.exists():
        raise DatasetInvalidoError(
            f"dataset ausente em {caminho}. Rode `accessai_ml.dataset.cli` antes.")

    por_parte: dict[str, list[Amostra]] = {parte: [] for parte in divisao.PARTES}
    total_de_linhas = 0

    with caminho.open(encoding="utf-8") as arquivo:
        for numero, bruta in enumerate(arquivo, start=1):
            bruta = bruta.strip()
            if not bruta:
                continue
            total_de_linhas += 1
            try:
                linha = json.loads(bruta)
            except json.JSONDecodeError as erro:
                raise DatasetInvalidoError(
                    f"linha {numero} nao e JSON valido: {erro}") from erro
            amostra = _linha_para_amostra(linha, numero)
            if amostra is not None:
                por_parte[amostra.divisao].append(amostra)

    conjuntos = Conjuntos(treino=por_parte[divisao.TREINO],
                          validacao=por_parte[divisao.VALIDACAO],
                          teste=por_parte[divisao.TESTE])
    # Vazamento antes de volume: um dataset com o mesmo grupo dos dois lados
    # esta QUEBRADO, e continua quebrado depois de crescer. Reportar o tamanho
    # primeiro mandaria a pessoa coletar mais dados para um defeito que coletar
    # mais dados nao resolve.
    _conferir_vazamento(conjuntos)
    _validar(conjuntos, total_de_linhas, caminho)
    return conjuntos


def _validar(conjuntos: Conjuntos, total_de_linhas: int, caminho: pathlib.Path) -> None:
    if conjuntos.total == 0:
        raise DatasetInvalidoError(
            f"{caminho} tem {total_de_linhas} linha(s) e NENHUMA rotulada. "
            "Nao ha o que treinar. A procedencia do dataset (D2) esta como "
            "PROPOSTA no ADR 0002: enquanto ninguem rotular, treinar aqui "
            "produziria metrica de nada.")

    if conjuntos.total < MINIMO_TOTAL:
        raise DatasetInvalidoError(
            f"apenas {conjuntos.total} amostras rotuladas (minimo {MINIMO_TOTAL}). "
            "Com esse volume a metrica e ruido: uma amostra trocada de lado move "
            "a macro-F1 em dezenas de pontos.")

    if not conjuntos.treino:
        raise DatasetInvalidoError("nenhuma amostra caiu no treino.")

    avaliacao = conjuntos.validacao or conjuntos.teste
    if not avaliacao:
        raise DatasetInvalidoError(
            "nenhuma amostra em validacao nem em teste. Metrica so no treino "
            "mede memorizacao, nao generalizacao.")

    contagem: dict[str, int] = {}
    for amostra in conjuntos.treino:
        contagem[amostra.rotulo] = contagem.get(amostra.rotulo, 0) + 1
    if len(contagem) < 2:
        raise DatasetInvalidoError(
            f"o treino tem uma classe so ({list(contagem)}). Classificador de "
            "classe unica nao classifica nada.")
    escassas = {r: n for r, n in contagem.items() if n < MINIMO_POR_CLASSE}
    if escassas:
        raise DatasetInvalidoError(
            f"classes com menos de {MINIMO_POR_CLASSE} amostras no treino: {escassas}.")


def _conferir_vazamento(conjuntos: Conjuntos) -> None:
    """Trava de seguranca: nenhum grupo pode aparecer em duas partes.

    A divisao ja garante isso na montagem do dataset. A conferencia existe para o
    caso de o JSONL ter sido editado a mao ou concatenado de duas execucoes —
    situacoes em que o vazamento entraria sem ninguem perceber.
    """
    origem: dict[str, str] = {}
    for parte, amostras in (("treino", conjuntos.treino),
                            ("validacao", conjuntos.validacao),
                            ("teste", conjuntos.teste)):
        for amostra in amostras:
            anterior = origem.setdefault(amostra.grupo, parte)
            if anterior != parte:
                raise DatasetInvalidoError(
                    f"vazamento: o grupo {amostra.grupo!r} aparece em {anterior} "
                    f"e em {parte}. Texto identico dos dois lados da divisao "
                    "infla a metrica sem o modelo ter aprendido nada.")
