"""API de inferencia do AccessAI.

    uvicorn accessai_ml.inference.main:app --port 8000

Tres endpoints: `/health` para a orquestracao, `/v1/predict` e
`/v1/predict:batch` para o backend.

O servico NAO acessa o banco principal (CONTRIBUTING.md secao 5). A unica
entrada e o corpo do pedido; a unica saida, o corpo da resposta.
"""

from __future__ import annotations

import logging
import os
import pathlib
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request

from . import schemas
from .servico import Resultado, ServicoDePredicao

log = logging.getLogger(__name__)

VAR_MODELOS = "ACCESSAI_MODELOS"
PASTA_PADRAO = pathlib.Path("models")

VERSAO_DA_API = "v1"


def pasta_de_modelos() -> pathlib.Path:
    valor = os.environ.get(VAR_MODELOS)
    return pathlib.Path(valor).expanduser() if valor else PASTA_PADRAO


@asynccontextmanager
async def ciclo_de_vida(app: FastAPI) -> AsyncIterator[None]:
    """Carrega o artefato UMA vez, na subida.

    Carregar por requisicao colocaria leitura de disco e desserializacao de
    pickle no caminho quente, com o backend esperando do outro lado com um
    timeout de 1,5s.
    """
    app.state.servico = ServicoDePredicao(pasta_de_modelos())
    if not app.state.servico.modelo_carregado:
        log.warning("subindo SEM modelo: %s", app.state.servico.motivo)
    yield


app = FastAPI(title="AccessAI ML Service", version="0.1.0",
              lifespan=ciclo_de_vida)


def _servico(requisicao: Request) -> ServicoDePredicao:
    return requisicao.app.state.servico  # type: ignore[no-any-return]


@app.get("/health", response_model=schemas.RespostaDeSaude)
def saude(requisicao: Request) -> schemas.RespostaDeSaude:
    """Sempre 200 quando o processo responde.

    Ausencia de modelo NAO e 503: o servico continua entregando resultado util
    pela heuristica, e devolver 503 faria a orquestracao reiniciar um container
    saudavel em loop. O que muda e `modeloCarregado`, com o motivo ao lado.
    """
    servico = _servico(requisicao)
    return schemas.RespostaDeSaude(
        status="ok",
        modelo_carregado=servico.modelo_carregado,
        modelo_versao=servico.versao,
        motivo=servico.motivo,
    )


@app.post(f"/{VERSAO_DA_API}/predict", response_model=schemas.RespostaAnalise)
def prever(pedido: schemas.RequisicaoAnalise,
           requisicao: Request) -> schemas.RespostaAnalise:
    """Classifica a qualidade de um texto alternativo.

    Nunca 5xx por ausencia de modelo: sem artefato, a heuristica responde e a
    resposta sai com `usouHeuristica = true`.
    """
    servico = _servico(requisicao)
    resultado = servico.prever(pedido.alt_text)
    return _para_resposta(resultado, servico.versao)


@app.post(f"/{VERSAO_DA_API}/predict:batch",
          response_model=schemas.RespostaDeLote)
def prever_lote(pedido: schemas.RequisicaoDeLote,
                requisicao: Request) -> schemas.RespostaDeLote:
    """Classifica os textos alternativos de um documento numa chamada so.

    Mesmo contrato de degradacao do `/predict`: sem artefato, ou com o pipeline
    falhando, a heuristica responde e cada item sai com `usouHeuristica = true`.

    Endpoint novo em vez de aceitar lista no `/predict`: mudar o corpo do
    endpoint existente quebraria o cliente que ja esta em producao, e um caminho
    que aceita "ou objeto ou lista" e o tipo de contrato que ninguem consegue
    versionar depois.
    """
    servico = _servico(requisicao)
    resultados = servico.prever_lote([item.alt_text for item in pedido.itens])
    return schemas.RespostaDeLote(
        resultados=[_para_resposta(r, servico.versao) for r in resultados])


def _para_resposta(resultado: Resultado, versao: str | None
                   ) -> schemas.RespostaAnalise:
    return schemas.RespostaAnalise(
        categoria=resultado.categoria,
        confianca=resultado.confianca,
        modelo_versao=versao,
        usou_heuristica=resultado.usou_heuristica,
    )
