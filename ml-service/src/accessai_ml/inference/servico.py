"""Carrega o artefato e prediz — ou cai para a heuristica, dizendo que caiu.

A degradacao e o ponto deste modulo. Hoje `models/` esta vazio: a procedencia do
dataset (D2) segue como PROPOSTA no ADR 0002 e nenhum modelo foi treinado. O
servico sobe assim mesmo, responde, e marca `usouHeuristica = true` em toda
predicao.

Marcar e obrigatorio, nao cortesia. Um servico chamado `/predict` que devolve o
resultado de um punhado de regras sem dizer que sao regras faz o consumidor
acreditar que existe um modelo — exatamente o "ML que e if/else" que a secao 1 do
CONTRIBUTING.md proibe.

**Duas falhas diferentes, dois tratamentos diferentes.** Nao conseguir CARREGAR o
artefato e permanente: nao ha modelo, e nao vai haver ate alguem trocar o
arquivo. Uma PREDICAO falhar e transitorio: degrada aquela chamada e nada mais.
Tratar as duas igual — como esta classe fazia — transformava um blip de memoria
em degradacao permanente e global, invisivel para quem chama, ate o restart.
"""

from __future__ import annotations

import dataclasses
import logging
import pathlib
import threading
from typing import Any

import joblib

from ..training import modelo as construtor
from ..training.train import NOME_DO_ARTEFATO

log = logging.getLogger(__name__)

# Chaves que o artefato precisa ter para ser aceito. Um .joblib que carrega mas
# nao tem rotulos e pior que um ausente: ele passa no `exists()` e falha na
# primeira predicao, em producao.
CHAVES_OBRIGATORIAS = ("versao_do_modelo", "pipeline", "rotulos")


@dataclasses.dataclass(frozen=True)
class Resultado:
    categoria: str
    confianca: float | None
    usou_heuristica: bool


class ServicoDePredicao:
    """Prediz com o modelo quando ele existe, com a heuristica quando nao.

    O endpoint sincrono do FastAPI roda no threadpool do Starlette, entao varias
    threads chamam `prever` ao mesmo tempo. O estado do modelo e trocado sob
    trava, e lido UMA vez por chamada para uma variavel local: assim uma troca no
    meio do caminho nao produz meia predicao.
    """

    def __init__(self, caminho_dos_modelos: pathlib.Path) -> None:
        self._caminho = caminho_dos_modelos / NOME_DO_ARTEFATO
        self._trava = threading.Lock()
        self._pipeline: Any | None = None
        self._versao: str | None = None
        self._motivo: str | None = None
        self._heuristica = construtor.BaselineHeuristico()
        self._carregar()

    # ------------------------------------------------------------- carga

    def _carregar(self) -> None:
        """So no arranque. Falha aqui e permanente: nao ha artefato utilizavel."""
        with self._trava:
            if not self._caminho.exists():
                self._sem_modelo(f"artefato ausente em {self._caminho}")
                return
            try:
                artefato = joblib.load(self._caminho)
            except Exception as erro:  # noqa: BLE001 - qualquer falha vira fallback
                # Amplo de proposito: joblib levanta de tudo quando o pickle veio
                # de outra versao de biblioteca. Subir com heuristica e melhor
                # que nao subir, desde que o motivo fique registrado.
                self._sem_modelo(f"artefato ilegivel ({type(erro).__name__}: {erro})")
                return

            if not isinstance(artefato, dict):
                self._sem_modelo(
                    f"artefato com formato inesperado: {type(artefato).__name__}")
                return
            faltando = [c for c in CHAVES_OBRIGATORIAS if c not in artefato]
            if faltando:
                self._sem_modelo(f"artefato sem as chaves {faltando}")
                return
            if not hasattr(artefato["pipeline"], "predict"):
                self._sem_modelo("artefato sem pipeline com `predict`")
                return

            self._pipeline = artefato["pipeline"]
            self._versao = str(artefato["versao_do_modelo"])
            self._motivo = None
            log.info("modelo carregado versao=%s de %s", self._versao, self._caminho)

    def _sem_modelo(self, motivo: str) -> None:
        """Chamado SO com a trava tomada, e so na carga."""
        self._pipeline = None
        self._versao = None
        self._motivo = motivo
        log.warning("sem modelo, usando heuristica: %s", motivo)

    # ------------------------------------------------------------ estado

    @property
    def modelo_carregado(self) -> bool:
        return self._pipeline is not None

    @property
    def versao(self) -> str | None:
        return self._versao

    @property
    def motivo(self) -> str | None:
        return self._motivo

    # ------------------------------------------------------------ predicao

    def prever(self, alt: str) -> Resultado:
        """Classifica um texto alternativo. Nunca lanca."""
        # Leitura unica da referencia: entre o teste e o uso, outra thread pode
        # trocar o campo. Com a local, esta chamada usa um pipeline coerente.
        pipeline = self._pipeline
        if pipeline is None:
            return self._prever_com_heuristica(alt)

        try:
            return self._prever_com(pipeline, alt)
        except Exception as erro:  # noqa: BLE001 - degrada a CHAMADA, nao o servico
            # Nao zera o pipeline. A falha pode ser deste texto ou de um blip;
            # desligar o modelo aqui faria uma chamada ruim contaminar todas as
            # seguintes, sem caminho de volta a nao ser reiniciar o processo.
            log.warning("falha ao predizer, heuristica nesta chamada (%s: %s)",
                        type(erro).__name__, erro)
            return self._prever_com_heuristica(alt)

    @staticmethod
    def _prever_com(pipeline: Any, alt: str) -> Resultado:
        """Uma passada so pelo pipeline.

        A categoria SAI da probabilidade, em vez de vir de um `predict` paralelo:
        alem de vetorizar o texto duas vezes — TF-IDF de palavra somado ao de
        caractere —, dois caminhos independentes podem divergir no dia em que o
        classificador mudar, sem nenhum teste reclamar.
        """
        if hasattr(pipeline, "predict_proba"):
            probabilidades = pipeline.predict_proba([alt])[0]
            indice = int(probabilidades.argmax())
            return Resultado(categoria=str(pipeline.classes_[indice]),
                             confianca=float(probabilidades[indice]),
                             usou_heuristica=False)
        return Resultado(categoria=str(pipeline.predict([alt])[0]),
                         confianca=None, usou_heuristica=False)

    def _prever_com_heuristica(self, alt: str) -> Resultado:
        categoria = str(self._heuristica.predict([alt])[0])
        # confianca=None: regra nao tem probabilidade, e um numero inventado aqui
        # faria o consumidor tratar heuristica como modelo confiante.
        return Resultado(categoria=categoria, confianca=None, usou_heuristica=True)
