"""Divisao treino/validacao/teste.

**A unidade de agrupamento e o alt text normalizado, nao o documento.** Amostra
sem alt cai no grupo do proprio documento.

A versao anterior agrupava so por documento. Isso resolve vazamento de
vocabulario entre documentos, mas nao resolve o vazamento que de fato ameaca
este modelo: TEXTO IDENTICO nos dois lados da divisao. Na sondagem da fonte
escolhida no ADR 0002, 93 alts nao vazios tinham 10 distintos — logo de site
repetido em toda pagina. Com agrupamento por documento, `"Escoteiros do Brasil"`
apareceria no treino e no teste, e a macro-F1 subiria sem o modelo ter aprendido
nada.

A troca e consciente e tem custo: documento deixa de ser atomico, e duas imagens
do mesmo edital podem cair em partes diferentes quando tem alts diferentes. Para
um classificador de texto curto, onde a amostra E o alt, duplicata exata e o
risco dominante e a repeticao de vocabulario e de segunda ordem.

A divisao e deterministica, derivada de um hash da propria chave de agrupamento.
Sem semente global e sem embaralhamento: a mesma entrada produz sempre a mesma
divisao, em qualquer maquina, sem carregar arquivo de indices junto. Chave nova
cai onde seu proprio hash mandar, sem remexer as que ja estavam.
"""

from __future__ import annotations

import dataclasses
import hashlib
import unicodedata
from collections.abc import Iterable

TREINO = "treino"
VALIDACAO = "validacao"
TESTE = "teste"

PARTES = (TREINO, VALIDACAO, TESTE)

# Proporcao alvo 70/15/15, expressa em 100 baldes.
_FAIXAS = ((70, TREINO), (85, VALIDACAO), (100, TESTE))

PREFIXO_SEM_ALT = "__sem_alt__"


@dataclasses.dataclass(frozen=True)
class Divisao:
    """Chaves de agrupamento por parte, mais o indice para consulta."""

    treino: list[str]
    validacao: list[str]
    teste: list[str]
    _indice: dict[str, str] = dataclasses.field(default_factory=dict, repr=False,
                                                compare=False)

    def parte_de(self, chave: str) -> str | None:
        return self._indice.get(chave)


def normalizar_alt(alt: str) -> str:
    """Forma canonica para comparar dois alts.

    Caixa, espacos repetidos e acentuacao nao mudam se dois alts sao a mesma
    frase. `"BRASÃO  da   Republica"` e `"brasao da republica"` precisam cair no
    mesmo grupo, senao a deduplicacao deixa passar exatamente as variantes que
    um site gera sozinho.
    """
    sem_acento = unicodedata.normalize("NFKD", alt)
    sem_acento = "".join(c for c in sem_acento if not unicodedata.combining(c))
    return " ".join(sem_acento.lower().split())


def chave_de_agrupamento(alt: str, documento: str) -> str:
    """Alts iguais compartilham chave. Sem alt, o grupo e o documento.

    Imagem sem alt nao vira amostra de treino, mas continua no dataset para a
    contagem. Agrupa-la por documento evita que todas as imagens sem alt do
    corpus inteiro virem um unico grupo gigante e desequilibrem a divisao.
    """
    normalizado = normalizar_alt(alt)
    return normalizado if normalizado else f"{PREFIXO_SEM_ALT}{documento}"


def balde(chave: str) -> int:
    """Balde 0-99 estavel, derivado de um hash da chave.

    Hash proprio, e nao o sha256 do documento: a chave agora e texto arbitrario.
    Manter isso dentro da funcao deixa `dividir` indiferente ao que a chave e.
    """
    digest = hashlib.sha256(chave.encode("utf-8")).hexdigest()
    return int(digest[-8:], 16) % 100


def dividir(chaves: Iterable[str]) -> Divisao:
    """Recebe as chaves de agrupamento e devolve a divisao."""
    grupos: dict[str, list[str]] = {parte: [] for parte in PARTES}
    indice: dict[str, str] = {}

    for chave in sorted(set(chaves)):
        posicao = balde(chave)
        for limite, nome in _FAIXAS:
            if posicao < limite:
                grupos[nome].append(chave)
                indice[chave] = nome
                break

    return Divisao(treino=grupos[TREINO], validacao=grupos[VALIDACAO],
                   teste=grupos[TESTE], _indice=indice)
