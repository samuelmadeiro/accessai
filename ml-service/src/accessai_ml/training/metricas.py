"""Metricas de avaliacao e o veredito contra os baselines."""

from __future__ import annotations

from typing import Any, Protocol

from sklearn.metrics import (
    classification_report,
    confusion_matrix,
    f1_score,
    precision_recall_fscore_support,
)

# Macro-F1 e a metrica principal, como manda o ADR 0002. Nao acuracia: com
# classes desbalanceadas, acuracia premia quem ignora a classe rara — e a classe
# rara aqui e justamente o alt ruim, que e o que o produto precisa detectar.
METRICA_PRINCIPAL = "f1_macro"

# `fase-0.md` D2 nomeia tres coisas como metrica de decisao, nao uma: macro-F1,
# matriz de confusao E recall da classe minoritaria. A terceira e a que o
# projeto existe para acertar — a classe rara aqui e o alt ruim, que e o que o
# produto precisa detectar. Deixa-la escondida dentro de `por_classe` faz a
# macro-F1 ser lida como resumo quando ela pode estar carregando um zero.
#
# Abaixo deste suporte, o recall da classe nao mede nada: com 1 amostra ele so
# pode ser 0.0 ou 1.0, e qualquer um dos dois seria lido como resultado.
MINIMO_PARA_MEDIR_CLASSE = 5


class Preditor(Protocol):
    def predict(self, X: list[str]) -> Any: ...  # noqa: N803


def avaliar(preditor: Preditor, textos: list[str], verdadeiros: list[str],
            rotulos: list[str]) -> dict[str, Any]:
    """Precision, recall e F1 em macro e ponderado, mais a matriz de confusao."""
    previstos = list(preditor.predict(textos))

    macro = precision_recall_fscore_support(
        verdadeiros, previstos, average="macro", labels=rotulos, zero_division=0)
    ponderado = precision_recall_fscore_support(
        verdadeiros, previstos, average="weighted", labels=rotulos, zero_division=0)

    return {
        "amostras": len(verdadeiros),
        "macro": {"precision": float(macro[0]), "recall": float(macro[1]),
                  "f1": float(macro[2])},
        "ponderado": {"precision": float(ponderado[0]), "recall": float(ponderado[1]),
                      "f1": float(ponderado[2])},
        "por_classe": classification_report(
            verdadeiros, previstos, labels=rotulos, output_dict=True,
            zero_division=0),
        # Ordem das linhas e colunas = `rotulos`. Sem isso registrado ao lado, a
        # matriz e um bloco de numeros que ninguem consegue ler seis meses depois.
        "matriz_de_confusao": {
            "rotulos": rotulos,
            "linhas_sao_verdadeiro_colunas_sao_previsto":
                confusion_matrix(verdadeiros, previstos, labels=rotulos).tolist(),
        },
    }


def recall_da_classe_minoritaria(avaliacao: dict[str, Any],
                                 rotulos: list[str]) -> dict[str, Any]:
    """Recall da classe menos representada no conjunto avaliado.

    A classe minoritaria e descoberta pelo SUPORTE do conjunto avaliado, nao
    fixada em `INSUFFICIENT`: quando o corpus crescer, a classe rara pode mudar,
    e um nome cravado aqui apontaria para a classe errada em silencio.

    Devolve `avaliavel: False` quando o suporte e pequeno demais para o numero
    significar algo. Isso NAO e um detalhe de apresentacao — e a diferenca entre
    "o modelo nao detecta a classe" e "existia uma amostra e ele errou".
    """
    por_classe = avaliacao["por_classe"]
    presentes = {r: por_classe[r] for r in rotulos if r in por_classe}
    if not presentes:
        return {"avaliavel": False, "motivo": "nenhuma classe no conjunto avaliado"}

    classe = min(presentes, key=lambda r: (presentes[r]["support"], r))
    suporte = int(presentes[classe]["support"])
    avaliavel = suporte >= MINIMO_PARA_MEDIR_CLASSE

    bloco: dict[str, Any] = {
        "classe": classe,
        "recall": float(presentes[classe]["recall"]),
        "precision": float(presentes[classe]["precision"]),
        "f1": float(presentes[classe]["f1-score"]),
        "suporte": suporte,
        "minimo_para_medir": MINIMO_PARA_MEDIR_CLASSE,
        "avaliavel": avaliavel,
    }
    if not avaliavel:
        bloco["motivo"] = (
            f"{suporte} amostra(s) de {classe} no conjunto avaliado, abaixo do "
            f"minimo de {MINIMO_PARA_MEDIR_CLASSE}. O recall aqui e ruido: com "
            "esse suporte ele so pode assumir poucos valores, e qualquer um "
            "deles seria lido como resultado. A macro-F1 ao lado carrega esse "
            "ruido em um terco do seu valor.")
    return bloco


def f1_macro(preditor: Preditor, textos: list[str], verdadeiros: list[str],
             rotulos: list[str]) -> float:
    return float(f1_score(verdadeiros, list(preditor.predict(textos)),
                          average="macro", labels=rotulos, zero_division=0))


def veredito(f1_modelo: float, f1_majoritario: float, f1_heuristico: float) -> dict[str, Any]:
    """Diz, em uma frase, se o modelo se justifica.

    O CONTRIBUTING.md secao 7 exige que modelo pior que baseline seja reportado
    como tal. Deixar isso como conclusao a ser tirada de tres numeros soltos e
    como nao exigir: alguem vai publicar o numero bonito e esquecer o resto.
    """
    melhor_baseline = max(f1_majoritario, f1_heuristico)
    nome_do_melhor = ("heuristica" if f1_heuristico >= f1_majoritario
                      else "classe majoritaria")
    ganho = f1_modelo - melhor_baseline

    if f1_modelo <= f1_majoritario:
        frase = (f"MODELO INUTIL: macro-F1 {f1_modelo:.3f} nao supera a classe "
                 f"majoritaria ({f1_majoritario:.3f}). Ele nao aprendeu nada.")
    elif f1_modelo <= f1_heuristico:
        frase = (f"MODELO NAO SE JUSTIFICA: macro-F1 {f1_modelo:.3f} contra "
                 f"{f1_heuristico:.3f} da heuristica. Se algumas regras alcancam "
                 "o modelo, use as regras (CONTRIBUTING.md secao 2).")
    else:
        frase = (f"MODELO SUPERA OS BASELINES: macro-F1 {f1_modelo:.3f} contra "
                 f"{melhor_baseline:.3f} da {nome_do_melhor} (+{ganho:.3f}).")

    return {
        "f1_macro_modelo": f1_modelo,
        "f1_macro_baseline_majoritario": f1_majoritario,
        "f1_macro_baseline_heuristico": f1_heuristico,
        "melhor_baseline": nome_do_melhor,
        "ganho_sobre_melhor_baseline": ganho,
        "supera_baselines": ganho > 0,
        "frase": frase,
    }
