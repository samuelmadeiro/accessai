"""Validacao cruzada com `StratifiedGroupKFold`.

A divisao fixa treino/validacao/teste de `dataset.divisao` responde "o modelo
generaliza?" com UMA amostragem. Com poucas centenas de alt texts, essa unica
amostragem tem variancia grande: trocar de semente move a macro-F1 em varios
pontos, e o numero que sobra no relatorio parece mais firme do que e.

A validacao cruzada roda o mesmo treino em varias particoes e reporta media e
desvio. O desvio e o ponto — ele diz se o ganho sobre o baseline sobrevive a
reamostragem ou se foi sorte de uma divisao especifica.

**Por que `StratifiedGroupKFold` e nao `StratifiedKFold`.** O agrupamento e a
mesma protecao contra vazamento que `dataset.divisao` aplica: alt text repetido
— logo de site que aparece em toda pagina — precisa cair inteiro de um lado so.
`StratifiedKFold` sozinho quebraria o grupo e inflaria a metrica sem o modelo ter
aprendido nada. `GroupKFold` sozinho protegeria o grupo mas deixaria uma pasta
sem a classe rara. So a versao combinada faz as duas coisas.

**O conjunto de teste fica de fora.** A validacao cruzada e ferramenta de
escolha (hiperparametro, features); usar o teste nela transforma o teste em
treino disfarcado, exatamente o que `train.treinar` ja evita no veredito.

**Amostra sintetica treina, mas nunca e avaliada.** As linhas geradas por
`dataset.gerador_insufficient` entram na metade de TREINO de cada pasta e saem
da metade avaliada. Sem essa remocao, o modelo e medido sobre string escrita
neste repositorio: a macro-F1 sobe vários pontos e o numero passa a dizer que o
modelo decorou uma lista, nao que ele detecta alt ruim.
"""

from __future__ import annotations

import statistics
from collections import Counter
from typing import Any

from sklearn.model_selection import StratifiedGroupKFold

from . import dados, metricas, modelo

PASTAS_PADRAO = 5

# Abaixo de duas pastas nao existe validacao cruzada: sobra um unico ajuste, que
# e o que a divisao fixa ja faz.
MINIMO_DE_PASTAS = 2


def pastas_viaveis(amostras: list[dados.Amostra], pedidas: int) -> tuple[int, str | None]:
    """Quantas pastas cabem de fato, e por que menos que o pedido.

    `StratifiedGroupKFold` levanta quando `n_splits` passa do numero de grupos ou
    do tamanho da classe mais rara. Descobrir o limite ANTES e o que permite
    reduzir com motivo registrado, em vez de estourar no meio do relatorio.
    """
    if not amostras:
        return 0, "nenhuma amostra fora do teste"

    grupos = len({a.grupo for a in amostras})
    por_classe = Counter(a.rotulo for a in amostras)
    classe_mais_rara = min(por_classe.values())

    teto = min(pedidas, grupos, classe_mais_rara)
    if teto < MINIMO_DE_PASTAS:
        return 0, (f"pastas possiveis = min(pedidas={pedidas}, grupos={grupos}, "
                   f"classe mais rara={classe_mais_rara}) = {teto}, "
                   f"abaixo do minimo de {MINIMO_DE_PASTAS}")
    if teto < pedidas:
        return teto, (f"reduzido de {pedidas} para {teto}: {grupos} grupos e "
                      f"{classe_mais_rara} amostras na classe mais rara")
    return teto, None


def validar(amostras: list[dados.Amostra], rotulos: list[str], c: float,
            semente: int, min_df: int, pastas: int = PASTAS_PADRAO) -> dict[str, Any]:
    """Roda a validacao cruzada e devolve o bloco que vai para o relatorio.

    Nunca levanta: a validacao cruzada e diagnostico, nao criterio de exportacao.
    Uma pasta que falha ao ajustar — vocabulario vazio depois da poda por
    `min_df`, por exemplo — entra em `pastas_com_falha` com o motivo, e as
    outras seguem. Falha em silencio seria pior; abortar o treino inteiro por
    causa de um diagnostico, tambem.
    """
    efetivas, motivo = pastas_viaveis(amostras, pastas)
    if efetivas < MINIMO_DE_PASTAS:
        return {"executada": False, "motivo": motivo, "pastas_pedidas": pastas}

    textos = [a.texto for a in amostras]
    verdadeiros = [a.rotulo for a in amostras]
    grupos = [a.grupo for a in amostras]

    divisor = StratifiedGroupKFold(n_splits=efetivas, shuffle=True,
                                   random_state=semente)

    por_pasta: list[dict[str, Any]] = []
    falhas: list[dict[str, Any]] = []

    for numero, (indices_treino, indices_teste) in enumerate(
            divisor.split(textos, verdadeiros, groups=grupos), start=1):
        x_treino = [textos[i] for i in indices_treino]
        y_treino = [verdadeiros[i] for i in indices_treino]
        # A remocao e so do lado avaliado: a sintetica continua ensinando na
        # metade de treino, que e como ela entra no ajuste final tambem.
        avaliaveis = [i for i in indices_teste if not amostras[i].sintetica]
        x_teste = [textos[i] for i in avaliaveis]
        y_teste = [verdadeiros[i] for i in avaliaveis]
        if not x_teste:
            falhas.append({"pasta": numero,
                           "erro": "pasta so com amostra sintetica no lado avaliado"})
            continue

        try:
            pipeline = modelo.construir_pipeline(c=c, semente=semente, min_df=min_df)
            pipeline.fit(x_treino, y_treino)

            majoritario = modelo.construir_baseline_majoritario(semente=semente)
            majoritario.fit(x_treino, y_treino)

            heuristico = modelo.BaselineHeuristico().fit(x_treino, y_treino)
        except Exception as erro:  # noqa: BLE001 - a pasta cai, o treino segue
            falhas.append({"pasta": numero, "erro": f"{type(erro).__name__}: {erro}"})
            continue

        por_pasta.append({
            "pasta": numero,
            "amostras_treino": len(x_treino),
            "amostras_teste": len(x_teste),
            "sinteticas_removidas_da_avaliacao": len(indices_teste) - len(avaliaveis),
            "f1_macro_modelo": metricas.f1_macro(pipeline, x_teste, y_teste, rotulos),
            "f1_macro_baseline_majoritario": metricas.f1_macro(
                majoritario, x_teste, y_teste, rotulos),
            "f1_macro_baseline_heuristico": metricas.f1_macro(
                heuristico, x_teste, y_teste, rotulos),
        })

    if not por_pasta:
        return {"executada": False,
                "motivo": "nenhuma pasta ajustou", "pastas_pedidas": pastas,
                "pastas_com_falha": falhas}

    resumo = {
        chave.removeprefix("f1_macro_"): _media_e_desvio(
            [p[chave] for p in por_pasta])
        for chave in ("f1_macro_modelo", "f1_macro_baseline_majoritario",
                      "f1_macro_baseline_heuristico")
    }

    return {
        "executada": True,
        "estrategia": "StratifiedGroupKFold (grupo = alt normalizado)",
        "pastas_pedidas": pastas,
        "pastas_efetivas": efetivas,
        "reducao": motivo,
        "amostras": len(amostras),
        "sinteticas": sum(1 for a in amostras if a.sintetica),
        "grupos": len({a.grupo for a in amostras}),
        "conjunto": "treino + validacao (teste fica de fora)",
        "metrica": metricas.METRICA_PRINCIPAL,
        "resumo": resumo,
        "por_pasta": por_pasta,
        "pastas_com_falha": falhas,
    }


def _media_e_desvio(valores: list[float]) -> dict[str, float]:
    # `pstdev` e nao `stdev`: com duas pastas, `stdev` divide por n-1 = 1 e
    # devolve um desvio inflado; e com uma pasta so ele levanta.
    return {"media": float(statistics.fmean(valores)),
            "desvio": float(statistics.pstdev(valores)),
            "minimo": float(min(valores)),
            "maximo": float(max(valores))}
