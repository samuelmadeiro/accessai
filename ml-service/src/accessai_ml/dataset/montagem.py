"""Monta o dataset de texto alternativo a partir do corpus real.

O que sai daqui NAO e um dataset rotulado. Sao os alt texts que existem nos
documentos, com contexto e procedencia — a materia-prima. O rotulo
(`GOOD` / `WEAK` / `INSUFFICIENT`, ADR 0002) depende de decisao que ainda nao
foi tomada sobre quem rotula, e inventa-lo aqui seria fabricar o dataset que a
secao 1 do CONTRIBUTING.md proibe.

Imagem sem alt tambem sai, marcada. Ela nao vira amostra de treino — alt ausente
e deteccao deterministica e ja e regra no Rule Engine (CONTRIBUTING.md secao 2) —
mas entra no relatorio, porque a razao entre imagens com e sem alt e o numero
que diz se este corpus sustenta um modelo.
"""

from __future__ import annotations

import json
import pathlib
from datetime import UTC, datetime
from typing import Any

from . import corpus, divisao, ooxml

# 1 -> 2: a divisao passou a ser por alt normalizado (antes era por documento),
# a linha do JSONL carrega a versao e o grupo, e o relatorio ganhou
# `partes_grandes_demais`. Quem leu um arquivo v1 nao pode supor o mesmo formato.
VERSAO_DO_FORMATO = 2

# Volume minimo declarado no ADR 0002: ~600 amostras, ~200 por classe.
MINIMO_POR_ADR = 600


def _agora() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def montar(raiz_do_corpus: pathlib.Path, saida: pathlib.Path) -> dict[str, Any]:
    """Le o corpus, extrai os candidatos, divide e escreve. Devolve o relatorio."""
    documentos = corpus.carregar(raiz_do_corpus)
    procedencia = {d.arquivo: d for d in documentos}

    extracoes = {
        documento.arquivo: ooxml.extrair_candidatos(
            str(documento.caminho), documento.arquivo)
        for documento in documentos
    }

    # A divisao precisa conhecer TODAS as chaves antes de atribuir qualquer
    # amostra: e o que garante que dois documentos com o mesmo alt caiam juntos.
    chaves = {
        divisao.chave_de_agrupamento(candidato.alt, candidato.arquivo)
        for extracao in extracoes.values()
        for candidato in extracao.candidatos
    }
    particao = divisao.dividir(chaves)

    linhas: list[dict[str, Any]] = []
    por_documento: dict[str, dict[str, Any]] = {}

    for documento in documentos:
        extracao = extracoes[documento.arquivo]
        candidatos = extracao.candidatos
        com_alt = sum(1 for c in candidatos if c.tem_alt)
        partes_do_documento: set[str] = set()

        for candidato in candidatos:
            origem = procedencia[candidato.arquivo]
            grupo = divisao.chave_de_agrupamento(candidato.alt, candidato.arquivo)
            parte = particao.parte_de(grupo)
            if parte:
                partes_do_documento.add(parte)
            linhas.append({
                # A versao viaja em CADA linha: quem ler o .jsonl sozinho, sem o
                # relatorio ao lado, precisa saber que esquema esta lendo.
                "versao_do_formato": VERSAO_DO_FORMATO,
                "id": f"{candidato.arquivo}#{candidato.indice}",
                "arquivo": candidato.arquivo,
                "sha256_documento": origem.sha256,
                "parte_pacote": candidato.parte,
                "nome_imagem": candidato.nome,
                "alt": candidato.alt,
                "origem_do_alt": candidato.origem_do_alt,
                "tem_alt": candidato.tem_alt,
                "contexto_antes": candidato.contexto_antes,
                "contexto_depois": candidato.contexto_depois,
                # O grupo fica visivel: sem ele nao da para auditar depois se
                # duas amostras iguais foram mesmo para o mesmo lado.
                "grupo": grupo,
                "divisao": parte,
                # Procedencia viaja junto com a amostra, e nao so no manifesto:
                # amostra sem origem rastreavel nao pode ser defendida depois.
                "orgao": origem.orgao,
                "esfera": origem.esfera,
                "categoria": origem.categoria,
                "licenca": origem.licenca,
                "url": origem.url,
                # Sem rotulo, e explicitamente: nulo diz "ninguem rotulou".
                # Um valor default aqui viraria rotulo de mentira no treino.
                "rotulo": None,
            })

        por_documento[documento.arquivo] = {
            "imagens": len(candidatos),
            "com_alt": com_alt,
            "sem_alt": len(candidatos) - com_alt,
            "partes_ilegiveis": extracao.partes_ilegiveis,
            "partes_grandes_demais": extracao.partes_grandes_demais,
            # Plural: com a divisao por alt, um documento pode aparecer em mais
            # de uma parte. Deixar no singular esconderia isso de quem audita.
            "divisoes": sorted(partes_do_documento),
        }

    relatorio = _relatorio(documentos, linhas, particao, por_documento)
    _escrever(saida, linhas, relatorio)
    return relatorio


def _relatorio(documentos: list[corpus.DocumentoDoCorpus],
               linhas: list[dict[str, Any]],
               particao: divisao.Divisao,
               por_documento: dict[str, dict[str, Any]]) -> dict[str, Any]:
    com_alt = [linha for linha in linhas if linha["tem_alt"]]
    distintos = {linha["grupo"] for linha in com_alt}
    return {
        "versao_do_formato": VERSAO_DO_FORMATO,
        "gerado_em": _agora(),
        "documentos": len(documentos),
        "imagens": len(linhas),
        "imagens_com_alt": len(com_alt),
        "imagens_sem_alt": len(linhas) - len(com_alt),
        "amostras_rotulaveis": len(com_alt),
        # Alt repetido nao e amostra nova. A diferenca entre estes dois numeros
        # e o tamanho do vazamento que a divisao por grupo esta evitando.
        "alts_distintos": len(distintos),
        "rotuladas": 0,
        "grupos_por_divisao": {
            divisao.TREINO: len(particao.treino),
            divisao.VALIDACAO: len(particao.validacao),
            divisao.TESTE: len(particao.teste),
        },
        "por_documento": por_documento,
        "documentos_com_parte_ilegivel": {
            arquivo: dados["partes_ilegiveis"]
            for arquivo, dados in por_documento.items() if dados["partes_ilegiveis"]
        },
        "documentos_com_parte_grande_demais": {
            arquivo: dados["partes_grandes_demais"]
            for arquivo, dados in por_documento.items()
            if dados["partes_grandes_demais"]
        },
        "veredito": _veredito(len(distintos)),
    }


def _veredito(distintos: int) -> str:
    """A frase que o relatorio nao pode deixar de dizer.

    Conta alts DISTINTOS, e nao ocorrencias: 600 copias da mesma frase nao sao
    600 amostras. Existe para que o estado do dataset seja um dado que se le, e
    nao uma conclusao que alguem precisa lembrar de tirar olhando as contagens.
    """
    if distintos == 0:
        return ("SEM AMOSTRA: nenhuma imagem do corpus declara texto alternativo. "
                "Nao ha o que rotular nem o que treinar a partir deste corpus.")
    if distintos < MINIMO_POR_ADR:
        return (f"INSUFICIENTE: {distintos} alts distintos contra os "
                f"{MINIMO_POR_ADR} do ADR 0002.")
    return f"SUFICIENTE em volume: {distintos} alts distintos."


def _escrever(saida: pathlib.Path, linhas: list[dict[str, Any]],
              relatorio: dict[str, Any]) -> None:
    saida.mkdir(parents=True, exist_ok=True)
    with (saida / "alt_texts.jsonl").open("w", encoding="utf-8", newline="\n") as arquivo:
        for linha in linhas:
            arquivo.write(json.dumps(linha, ensure_ascii=False) + "\n")
    (saida / "relatorio.json").write_text(
        json.dumps(relatorio, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
