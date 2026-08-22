"""O classificador e os baselines que ele precisa bater.

O CONTRIBUTING.md secao 7 define quando a Slice 4 esta pronta: "confusion matrix
e baseline documentados; modelo pior que baseline e reportado como tal". Por isso
os baselines nao sao enfeite — sao o criterio de aceitacao, e estao aqui ao lado
do modelo, treinados sobre exatamente os mesmos dados.

Os dois baselines vem do ADR 0002:

1. **Classe majoritaria.** Piso absoluto. Um modelo que nao bate isso nao
   aprendeu nada — so descobriu qual rotulo aparece mais.
2. **Heuristica.** Comprimento, expressao generica, nome de arquivo. E o piso
   que importa de verdade: se um punhado de regras alcanca o modelo, a resposta
   honesta e usar as regras (CONTRIBUTING.md secao 2 — nao use ML onde uma regra
   resolve).
"""

from __future__ import annotations

import re

import numpy as np
from sklearn.dummy import DummyClassifier
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import FeatureUnion, Pipeline

BOM = "GOOD"
FRACO = "WEAK"
INSUFICIENTE = "INSUFFICIENT"

# Regressao logistica, e nao SVM linear: a Slice 6 usa a confianca da predicao
# para ajustar severidade (CONTRIBUTING.md secao 6), e `LinearSVC` nao entrega
# probabilidade calibravel sem um envelope extra. Com algumas centenas de
# amostras curtas, a diferenca de acuracia entre os dois e ruido.
C_PADRAO = 1.0
MAX_ITER = 2000


def construir_pipeline(c: float = C_PADRAO, semente: int = 42) -> Pipeline:
    """TF-IDF de palavra e de caractere, seguido de regressao logistica.

    Os dois vetorizadores somados atacam problemas diferentes: o de palavra pega
    expressao generica ("clique aqui", "imagem de"), e o de caractere pega
    nome de arquivo e ruido tipografico ("IMG_0421.jpg", "image1") sem depender
    de tokenizacao. Alt text tem poucas palavras; sub-palavra e onde mora o sinal.

    `class_weight="balanced"` porque as tres classes nao chegam equilibradas do
    mundo real, e macro-F1 pune quem ignora a classe rara.
    """
    palavras = TfidfVectorizer(analyzer="word", ngram_range=(1, 2), min_df=1,
                               sublinear_tf=True, strip_accents="unicode",
                               lowercase=True)
    caracteres = TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=1,
                                 sublinear_tf=True, strip_accents="unicode",
                                 lowercase=True)
    return Pipeline([
        ("features", FeatureUnion([("palavras", palavras), ("caracteres", caracteres)])),
        ("classificador", LogisticRegression(
            C=c, max_iter=MAX_ITER, class_weight="balanced", random_state=semente)),
    ])


def construir_baseline_majoritario(semente: int = 42) -> DummyClassifier:
    return DummyClassifier(strategy="most_frequent", random_state=semente)


# --------------------------------------------------------------- heuristica

EXPRESSOES_GENERICAS = (
    "clique aqui", "click here", "saiba mais", "leia mais", "veja mais",
    "imagem", "image", "foto", "figura", "logo", "logotipo", "banner",
    "icone", "ícone", "picture", "sem titulo", "sem título", "untitled",
)

PADRAO_NOME_DE_ARQUIVO = re.compile(
    r"^[\w\-. ]+\.(jpe?g|png|gif|bmp|svg|webp|emf|wmf|tiff?)$", re.IGNORECASE)
PADRAO_SO_RUIDO = re.compile(r"^[\W\d_]+$")

CURTO_DEMAIS = 15
LONGO_O_BASTANTE = 40


class BaselineHeuristico:
    """Regras a mao, com a mesma interface do sklearn.

    Implementa `fit`/`predict` de proposito: assim ele passa pelo MESMO codigo de
    avaliacao do modelo, e a comparacao nao depende de ninguem lembrar de aplicar
    as mesmas metricas dos dois lados.

    `fit` nao aprende nada — e so onde as classes conhecidas sao registradas.
    """

    def __init__(self) -> None:
        self.classes_: np.ndarray | None = None

    def fit(self, X: list[str], y: list[str]) -> BaselineHeuristico:  # noqa: N803
        self.classes_ = np.unique(y)
        return self

    def predict(self, X: list[str]) -> np.ndarray:  # noqa: N803
        return np.array([self._classificar(texto) for texto in X])

    @staticmethod
    def _classificar(texto: str) -> str:
        limpo = (texto or "").strip()
        minusculo = limpo.lower()

        if PADRAO_NOME_DE_ARQUIVO.match(limpo) or PADRAO_SO_RUIDO.match(limpo):
            return INSUFICIENTE
        if len(limpo) < CURTO_DEMAIS and any(
                expressao in minusculo for expressao in EXPRESSOES_GENERICAS):
            return INSUFICIENTE
        if any(minusculo.startswith(expressao) for expressao in EXPRESSOES_GENERICAS):
            return FRACO
        if len(limpo) < CURTO_DEMAIS:
            return FRACO
        if len(limpo) >= LONGO_O_BASTANTE:
            return BOM
        return FRACO
