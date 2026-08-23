"""Treina o classificador de qualidade de texto alternativo (Modelo 1).

Uso:

    python -m accessai_ml.training.train --dataset data/alt_texts.jsonl

O que este script NAO faz: treinar sem rotulo. Se o dataset nao tiver amostra
rotulada — que e o estado de hoje, com a procedencia (D2) ainda em PROPOSTA no
ADR 0002 — ele sai com codigo 3 e nao escreve artefato nenhum. Pipeline que
segue em frente com zero amostra produz um `.joblib` que parece modelo, reporta
metrica de nada, e mente para quem encontrar o arquivo depois.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import pathlib
import platform
import sys
from datetime import UTC, datetime
from typing import Any

import joblib
import sklearn

from ..dataset import divisao
from . import dados, metricas, modelo, validacao

VERSAO_DO_MODELO = "0.1.0"
NOME_DO_ARTEFATO = "accessibility_classifier.joblib"
NOME_DO_RELATORIO = "training_report.json"

SAIDA_OK = 0
SAIDA_DATASET_INVALIDO = 3
SAIDA_PIOR_QUE_BASELINE = 5


@dataclasses.dataclass(frozen=True)
class Artefato:
    """O que e gravado no .joblib.

    O pipeline sozinho nao basta: sem saber com que versao de scikit-learn ele
    foi serializado, nem com que rotulos, nem de que dataset, o arquivo vira um
    binario sem procedencia — e binario sem procedencia e pior que nenhum.
    """

    versao_do_modelo: str
    pipeline: Any
    rotulos: list[str]
    metadados: dict[str, Any]


def _agora() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def _rotulos_presentes(conjuntos: dados.Conjuntos) -> list[str]:
    """So os rotulos que existem, na ordem canonica do ADR 0002."""
    vistos = {a.rotulo for a in conjuntos.treino + conjuntos.validacao + conjuntos.teste}
    return [r for r in dados.ROTULOS_VALIDOS if r in vistos]


def treinar(conjuntos: dados.Conjuntos, c: float, semente: int,
            min_df: int = modelo.MIN_DF_PADRAO,
            pastas: int = validacao.PASTAS_PADRAO) -> dict[str, Any]:
    """Treina modelo e baselines nos MESMOS dados e avalia nos mesmos conjuntos."""
    rotulos = _rotulos_presentes(conjuntos)
    x_treino = conjuntos.textos(conjuntos.treino)
    y_treino = conjuntos.rotulos(conjuntos.treino)

    pipeline = modelo.construir_pipeline(c=c, semente=semente, min_df=min_df)
    pipeline.fit(x_treino, y_treino)

    majoritario = modelo.construir_baseline_majoritario(semente=semente)
    majoritario.fit(x_treino, y_treino)

    heuristico = modelo.BaselineHeuristico()
    heuristico.fit(x_treino, y_treino)

    avaliacoes: dict[str, Any] = {}
    for nome, amostras in (("treino", conjuntos.treino),
                           ("validacao", conjuntos.validacao),
                           ("teste", conjuntos.teste)):
        if not amostras:
            continue
        textos = conjuntos.textos(amostras)
        verdadeiros = conjuntos.rotulos(amostras)
        avaliacoes[nome] = {
            "modelo": metricas.avaliar(pipeline, textos, verdadeiros, rotulos),
            "baseline_majoritario": metricas.avaliar(
                majoritario, textos, verdadeiros, rotulos),
            "baseline_heuristico": metricas.avaliar(
                heuristico, textos, verdadeiros, rotulos),
        }

    # O veredito sai da validacao quando ela existe. O teste fica intocado para
    # a medida final; escolher modelo olhando o teste transforma o teste em
    # treino disfarcado.
    parte_do_veredito = "validacao" if conjuntos.validacao else "teste"
    amostras = (conjuntos.validacao if conjuntos.validacao else conjuntos.teste)
    textos = conjuntos.textos(amostras)
    verdadeiros = conjuntos.rotulos(amostras)

    # A validacao cruzada roda sobre treino + validacao, com o teste de fora, e
    # o artefato exportado continua sendo o ajustado no treino completo: as
    # pastas medem estabilidade, nao produzem o modelo que vai para producao.
    cruzada = validacao.validar(conjuntos.treino + conjuntos.validacao, rotulos,
                                c=c, semente=semente, min_df=min_df, pastas=pastas)

    julgamento = metricas.veredito(
        metricas.f1_macro(pipeline, textos, verdadeiros, rotulos),
        metricas.f1_macro(majoritario, textos, verdadeiros, rotulos),
        metricas.f1_macro(heuristico, textos, verdadeiros, rotulos))
    julgamento["medido_em"] = parte_do_veredito

    return {"pipeline": pipeline, "rotulos": rotulos, "avaliacoes": avaliacoes,
            "validacao_cruzada": cruzada, "veredito": julgamento}


def montar_relatorio(conjuntos: dados.Conjuntos, resultado: dict[str, Any],
                     caminho_do_dataset: pathlib.Path, c: float, semente: int,
                     min_df: int = modelo.MIN_DF_PADRAO) -> dict[str, Any]:
    return {
        "versao_do_modelo": VERSAO_DO_MODELO,
        "gerado_em": _agora(),
        "dataset": str(caminho_do_dataset),
        "amostras": {
            "treino": len(conjuntos.treino),
            "validacao": len(conjuntos.validacao),
            "teste": len(conjuntos.teste),
            "total": conjuntos.total,
        },
        "rotulos": resultado["rotulos"],
        "hiperparametros": {
            "classificador": "LogisticRegression",
            "C": c,
            "max_iter": modelo.MAX_ITER,
            "class_weight": "balanced",
            "random_state": semente,
            "features": "TfidfVectorizer word(1,2) + char_wb(3,5), sublinear_tf",
            "min_df": min_df,
        },
        "metrica_principal": metricas.METRICA_PRINCIPAL,
        "validacao_cruzada": resultado["validacao_cruzada"],
        "avaliacoes": resultado["avaliacoes"],
        "veredito": resultado["veredito"],
        "ambiente": {
            "python": platform.python_version(),
            "scikit_learn": sklearn.__version__,
            "joblib": joblib.__version__,
        },
    }


def exportar(destino: pathlib.Path, resultado: dict[str, Any],
             relatorio: dict[str, Any]) -> pathlib.Path:
    destino.mkdir(parents=True, exist_ok=True)
    caminho = destino / NOME_DO_ARTEFATO
    artefato = Artefato(
        versao_do_modelo=VERSAO_DO_MODELO,
        pipeline=resultado["pipeline"],
        rotulos=resultado["rotulos"],
        metadados={
            "treinado_em": relatorio["gerado_em"],
            "dataset": relatorio["dataset"],
            "amostras": relatorio["amostras"],
            "hiperparametros": relatorio["hiperparametros"],
            "validacao_cruzada": relatorio["validacao_cruzada"],
            "veredito": relatorio["veredito"],
            "ambiente": relatorio["ambiente"],
            "divisao": {
                "estrategia": "grupo por alt normalizado (dataset.divisao)",
                "partes": list(divisao.PARTES),
            },
        },
    )
    joblib.dump(dataclasses.asdict(artefato) | {"pipeline": resultado["pipeline"]},
                caminho)
    return caminho


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(
        description="Treina o classificador de qualidade de texto alternativo.")
    analisador.add_argument("--dataset", type=pathlib.Path,
                            default=pathlib.Path("data/alt_texts.jsonl"))
    analisador.add_argument("--modelos", type=pathlib.Path,
                            default=pathlib.Path("models"),
                            help="pasta de saida do artefato")
    analisador.add_argument("--relatorio", type=pathlib.Path,
                            default=pathlib.Path("data") / NOME_DO_RELATORIO)
    analisador.add_argument("--C", dest="c", type=float, default=modelo.C_PADRAO)
    analisador.add_argument("--min-df", dest="min_df", type=int,
                            default=modelo.MIN_DF_PADRAO,
                            help="corte de frequencia minima do TF-IDF")
    analisador.add_argument("--pastas", type=int, default=validacao.PASTAS_PADRAO,
                            help="pastas da validacao cruzada StratifiedGroupKFold")
    analisador.add_argument("--semente", type=int, default=42)
    analisador.add_argument("--exportar-pior-que-baseline", action="store_true",
                            help="grava o artefato mesmo quando ele nao supera "
                                 "os baselines")
    argumentos = analisador.parse_args(argv)

    try:
        conjuntos = dados.carregar(argumentos.dataset)
    except dados.DatasetInvalidoError as erro:
        print(f"dataset invalido: {erro}", file=sys.stderr)
        return SAIDA_DATASET_INVALIDO

    resultado = treinar(conjuntos, c=argumentos.c, semente=argumentos.semente,
                        min_df=argumentos.min_df, pastas=argumentos.pastas)
    relatorio = montar_relatorio(conjuntos, resultado, argumentos.dataset,
                                 argumentos.c, argumentos.semente,
                                 min_df=argumentos.min_df)

    argumentos.relatorio.parent.mkdir(parents=True, exist_ok=True)
    argumentos.relatorio.write_text(
        json.dumps(relatorio, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    cruzada = relatorio["validacao_cruzada"]
    if cruzada["executada"]:
        resumo = cruzada["resumo"]["modelo"]
        print(f"validacao cruzada ({cruzada['pastas_efetivas']} pastas, "
              f"StratifiedGroupKFold): macro-F1 {resumo['media']:.3f} "
              f"+/- {resumo['desvio']:.3f} "
              f"[{resumo['minimo']:.3f}, {resumo['maximo']:.3f}]")
    else:
        print(f"validacao cruzada NAO executada: {cruzada['motivo']}",
              file=sys.stderr)

    print(json.dumps(relatorio["veredito"], ensure_ascii=False, indent=2))
    print(f"\nrelatorio em {argumentos.relatorio.resolve()}")

    if not relatorio["veredito"]["supera_baselines"] \
            and not argumentos.exportar_pior_que_baseline:
        # Nao exportar e a parte que importa: um artefato pior que o baseline,
        # parado numa pasta, acaba servido em producao por alguem que so viu o
        # nome do arquivo. Quem quiser inspecionar usa a flag.
        print("artefato NAO exportado — use --exportar-pior-que-baseline para "
              "gravar mesmo assim", file=sys.stderr)
        return SAIDA_PIOR_QUE_BASELINE

    caminho = exportar(argumentos.modelos, resultado, relatorio)
    print(f"modelo em {caminho.resolve()}")
    return SAIDA_OK


if __name__ == "__main__":
    raise SystemExit(main())
