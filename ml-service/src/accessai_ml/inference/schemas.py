"""DTOs da API de inferencia.

Os nomes viajam em camelCase porque quem consome e o backend Java, e Jackson
espera camelCase. Os nomes internos seguem em snake_case: o estilo do consumidor
nao deve vazar para dentro do modulo.

O texto que chega aqui saiu de documento enviado por usuario e e hostil
(CONTRIBUTING.md secao 5). Por isso os limites de tamanho estao no schema, e nao
num `if` esquecido no meio do servico: pedido fora do contrato e recusado com 422
antes de encostar no modelo.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

MAX_ALT = 2_000
MAX_CONTEXTO = 2_000


def _camel(nome: str) -> str:
    primeira, *resto = nome.split("_")
    return primeira + "".join(parte.capitalize() for parte in resto)


class Base(BaseModel):
    # `populate_by_name` deixa o Python construir pelos nomes snake_case sem
    # precisar escrever o alias; a serializacao continua saindo em camelCase.
    # `extra="forbid"` recusa campo desconhecido: cliente desatualizado falha
    # alto em vez de ter um campo ignorado em silencio.
    model_config = ConfigDict(alias_generator=_camel, populate_by_name=True,
                              extra="forbid")


class RequisicaoAnalise(Base):
    """Um texto alternativo a classificar, com o contexto em volta.

    O contexto viaja mesmo sem ser usado pelo modelo de hoje: a inadequacao de um
    alt costuma so ser visivel ao lado do que esta em volta (alt igual a legenda
    logo abaixo e redundante), e mudar o contrato depois custa mais que carregar
    o campo agora.
    """

    alt_text: str = Field(min_length=1, max_length=MAX_ALT)
    contexto_antes: str = Field(default="", max_length=MAX_CONTEXTO)
    contexto_depois: str = Field(default="", max_length=MAX_CONTEXTO)


class RespostaAnalise(Base):
    """A classificacao de um texto alternativo.

    `confianca` e ANULAVEL de proposito, contra a tentacao de tipar como float
    obrigatorio: a heuristica nao tem probabilidade. Preencher com 1.0 quando a
    resposta veio de regra faria o consumidor tratar regra como modelo
    confiante — o "ML que e if/else" que a secao 1 do CONTRIBUTING.md proibe.
    Nulo diz "esta resposta nao tem confianca associada".
    """

    categoria: str
    confianca: float | None = Field(default=None, ge=0.0, le=1.0)
    modelo_versao: str | None = None
    usou_heuristica: bool


class RespostaDeSaude(Base):
    status: str
    modelo_carregado: bool
    modelo_versao: str | None
    motivo: str | None = None
