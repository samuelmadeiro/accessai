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

# Teto de itens por lote. O numero nao e arbitrario: com o p99 de ~9 ms por
# imagem medido em `bench/medir_latencia.py`, 200 itens cabem folgados no
# timeout de leitura de 1500 ms que o cliente Java usa — e um documento com mais
# de 200 imagens e caso de outro problema, nao de mais um lote.
#
# O limite mora no schema, e nao num `if` no meio do servico, pelo mesmo motivo
# dos tamanhos acima: pedido fora do contrato e recusado com 422 antes de
# encostar no modelo.
MAX_LOTE = 200


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


class RequisicaoDeLote(Base):
    """Varios textos alternativos de UM documento, numa chamada so.

    Existe porque a API de item unico fazia o backend abrir uma conexao por
    imagem. Medido, o custo normal era aceitavel — mas com o servico degradado
    ele vira linear no numero de imagens, e e ai que o documento de vinte
    imagens vira trinta segundos de espera.

    A lista nao pode ser vazia: lote vazio e sempre erro de quem chama, e
    devolver `[]` em silencio esconderia o defeito no cliente.
    """

    itens: list[RequisicaoAnalise] = Field(min_length=1, max_length=MAX_LOTE)


class RespostaDeLote(Base):
    """Um resultado por item, NA MESMA ORDEM do pedido.

    A ordem e o contrato: sem ela o consumidor nao tem como ligar resultado a
    imagem, porque o pedido nao carrega identificador. Um `id` por item seria a
    alternativa, e custaria ao backend inventar chave para algo que ele ja tem
    ordenado em memoria.
    """

    resultados: list[RespostaAnalise]


class RespostaDeSaude(Base):
    status: str
    modelo_carregado: bool
    modelo_versao: str | None
    motivo: str | None = None
