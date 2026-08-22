"""Mede a latencia de inferencia. Criterio de pronto da Slice 5.

Tres camadas, medidas separadas porque respondem perguntas diferentes:

1. **processo** — so a predicao, sem rede nem serializacao. E o piso: nenhuma
   otimizacao de transporte leva a latencia abaixo disso.
2. **http** — ida e volta contra o servico rodando, incluindo Pydantic,
   Starlette e o loopback. E o que o backend Java realmente espera.

A diferenca entre as duas e o custo do transporte, e e ela que decide se vale
mexer em lote, em keep-alive ou em nada.

O que estes numeros NAO dizem: latencia em rede real. Tudo aqui e loopback na
mesma maquina — sem salto de rede, sem contencao, sem TLS. Num compose com dois
containers o numero sobe, e num salto entre hosts sobe mais. O valor deste
medidor e o piso e a proporcao, nao o absoluto.

Uso:

    python -m bench.medir_latencia --modo processo
    python -m bench.medir_latencia --modo http --url http://127.0.0.1:8000
"""

from __future__ import annotations

import argparse
import json
import pathlib
import statistics
import sys
import time
import urllib.error
import urllib.request

# Textos de tamanhos diferentes: alt curto e alt longo passam por quantidades
# diferentes de n-gramas, e medir so um esconde metade da distribuicao.
AMOSTRAS = (
    "IMG_0421.jpg",
    "Brasao",
    "clique aqui",
    "Grafico de barras com a evolucao do orcamento entre 2020 e 2025",
    "Mapa do Brasil com as regioes de atuacao do programa destacadas em azul, "
    "com legenda no canto inferior direito indicando o percentual por estado",
)


def percentis(amostras_ms: list[float]) -> dict[str, float]:
    ordenado = sorted(amostras_ms)

    def p(fracao: float) -> float:
        # Indice do menor elemento com pelo menos `fracao` da massa abaixo.
        indice = min(len(ordenado) - 1, int(fracao * len(ordenado)))
        return ordenado[indice]

    return {
        "n": len(ordenado),
        "media_ms": round(statistics.fmean(ordenado), 3),
        "p50_ms": round(p(0.50), 3),
        "p95_ms": round(p(0.95), 3),
        "p99_ms": round(p(0.99), 3),
        "max_ms": round(ordenado[-1], 3),
    }


def medir(chamada, iteracoes: int, aquecimento: int) -> dict[str, float]:
    """Executa `chamada(texto)` e devolve a distribuicao em milissegundos.

    O aquecimento existe porque a primeira chamada paga import tardio, cache de
    vetorizador e JIT do interpretador. Inclui-la na amostra transformaria um
    custo pago uma vez em latencia tipica.
    """
    for i in range(aquecimento):
        chamada(AMOSTRAS[i % len(AMOSTRAS)])

    amostras_ms: list[float] = []
    for i in range(iteracoes):
        texto = AMOSTRAS[i % len(AMOSTRAS)]
        inicio = time.perf_counter_ns()
        chamada(texto)
        amostras_ms.append((time.perf_counter_ns() - inicio) / 1_000_000)
    return percentis(amostras_ms)


def medir_em_processo(pasta_de_modelos: pathlib.Path, iteracoes: int,
                      aquecimento: int) -> dict:
    from accessai_ml.inference.servico import ServicoDePredicao

    servico = ServicoDePredicao(pasta_de_modelos)
    distribuicao = medir(servico.prever, iteracoes, aquecimento)
    return {
        "modo": "processo",
        "modelo_carregado": servico.modelo_carregado,
        "origem": "modelo" if servico.modelo_carregado else "heuristica",
        "motivo": servico.motivo,
        **distribuicao,
    }


def medir_por_http(url: str, iteracoes: int, aquecimento: int) -> dict:
    destino = url.rstrip("/") + "/v1/predict"

    def chamar(texto: str) -> None:
        corpo = json.dumps({"altText": texto}).encode("utf-8")
        pedido = urllib.request.Request(
            destino, data=corpo, method="POST",
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(pedido, timeout=10) as resposta:
            resposta.read()

    try:
        with urllib.request.urlopen(url.rstrip("/") + "/health", timeout=5) as r:
            saude = json.loads(r.read())
    except urllib.error.URLError as erro:
        raise SystemExit(f"servico nao respondeu em {url}: {erro}") from erro

    distribuicao = medir(chamar, iteracoes, aquecimento)
    return {
        "modo": "http",
        "url": url,
        "modelo_carregado": saude["modeloCarregado"],
        "origem": "modelo" if saude["modeloCarregado"] else "heuristica",
        **distribuicao,
    }


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analisador.add_argument("--modo", choices=("processo", "http"), required=True)
    analisador.add_argument("--modelos", type=pathlib.Path,
                            default=pathlib.Path("models"))
    analisador.add_argument("--url", default="http://127.0.0.1:8000")
    analisador.add_argument("--iteracoes", type=int, default=1000)
    analisador.add_argument("--aquecimento", type=int, default=50)
    argumentos = analisador.parse_args(argv)

    if argumentos.modo == "processo":
        resultado = medir_em_processo(argumentos.modelos, argumentos.iteracoes,
                                      argumentos.aquecimento)
    else:
        resultado = medir_por_http(argumentos.url, argumentos.iteracoes,
                                   argumentos.aquecimento)

    print(json.dumps(resultado, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
