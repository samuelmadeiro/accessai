"""Revisao humana dos pre-rotulos e a auditoria que o ADR 0002 secao 4 exige.

O coletor (`coletor_alt_publico`) grava `rotulo_provisorio` — pre-classificacao
deterministica — e deixa `rotulo` nulo. Este modulo e a outra metade: quem
revisa, quanto do pre-rotulo estava errado, e se a concordancia sustenta usar o
pre-rotulo no resto do dataset.

**O numero que decide e o kappa, nao a taxa de acerto.** Com 70% das amostras em
`GOOD`, um revisor que carimbasse `GOOD` em tudo acertaria 70% e nao teria
revisado nada. O kappa de Cohen desconta a concordancia que sairia do acaso dado
o quanto cada lado usa cada classe — e e por isso que o ADR pede kappa, e nao
acuracia.

**O que passar na auditoria autoriza, e o que nao autoriza.** Kappa alto diz que
o pre-rotulo concorda com o humano NA AMOSTRA REVISADA, o que sustenta promover
o pre-rotulo no restante. Nao diz que o rotulo esta certo: revisor e heuristica
podem estar consistentemente errados juntos, e o kappa nao ve isso.
"""

from __future__ import annotations

import dataclasses
import json
import os
import pathlib
import random
from datetime import UTC, datetime
from typing import Any

ROTULOS = ("GOOD", "WEAK", "INSUFFICIENT")

# ADR 0002 secao 4: kappa de Cohen em 150 amostras. O 50 por classe e o que
# produz as 150 com as tres classes igualmente representadas — amostrar 150 ao
# acaso encheria o lote de `GOOD` e mediria o kappa quase so nessa classe.
POR_CLASSE_PADRAO = 50
MINIMO_DO_ADR = 150
KAPPA_MINIMO_DO_ADR = 0.60

ORIGEM_HUMANA = "humano"
NOME_DO_RELATORIO = "relatorio_revisao.json"

PULAR = "PULAR"


class RevisaoError(Exception):
    """A revisao nao pode continuar, e a razao esta na mensagem."""


@dataclasses.dataclass(frozen=True)
class Amostra:
    """Uma linha do dataset oferecida para revisao."""

    indice: int
    id: str
    alt: str
    rotulo_provisorio: str


@dataclasses.dataclass(frozen=True)
class Decisao:
    """O que o humano respondeu sobre uma amostra."""

    amostra: Amostra
    rotulo: str

    @property
    def divergiu(self) -> bool:
        return self.rotulo != self.amostra.rotulo_provisorio


# ------------------------------------------------------------------- leitura


def carregar(caminho: pathlib.Path) -> list[dict[str, Any]]:
    """Le o JSONL inteiro, preservando a ordem das linhas.

    Inteiro, e nao so o que sera revisado: a gravacao reescreve o arquivo, e
    reescrever a partir de um subconjunto apagaria todo o resto.
    """
    if not caminho.exists():
        raise RevisaoError(
            f"dataset ausente em {caminho}. Rode o coletor antes.")

    registros: list[dict[str, Any]] = []
    with caminho.open(encoding="utf-8") as arquivo:
        for numero, bruta in enumerate(arquivo, start=1):
            bruta = bruta.strip()
            if not bruta:
                continue
            try:
                registros.append(json.loads(bruta))
            except json.JSONDecodeError as erro:
                raise RevisaoError(
                    f"linha {numero} nao e JSON valido: {erro}") from erro
    return registros


def pendentes(registros: list[dict[str, Any]]) -> list[Amostra]:
    """As linhas pre-rotuladas que ninguem revisou ainda.

    `rotulo` ja preenchido fica de fora de proposito: reoferecer uma amostra ja
    revisada faria o revisor gastar tempo no que ja decidiu e, pior, deixaria a
    segunda resposta sobrescrever a primeira sem registro.
    """
    achadas: list[Amostra] = []
    for indice, registro in enumerate(registros):
        provisorio = registro.get("rotulo_provisorio")
        if provisorio is None or registro.get("rotulo") is not None:
            continue
        if provisorio not in ROTULOS:
            raise RevisaoError(
                f"linha {indice + 1}: rotulo_provisorio {provisorio!r} fora de "
                f"{ROTULOS}.")
        alt = (registro.get("alt") or "").strip()
        if not alt:
            continue
        achadas.append(Amostra(indice=indice,
                               id=str(registro.get("id") or f"linha-{indice + 1}"),
                               alt=alt, rotulo_provisorio=provisorio))
    return achadas


def decisoes_registradas(registros: list[dict[str, Any]]) -> list[Decisao]:
    """Toda revisao humana ja gravada no arquivo, de qualquer sessao.

    A auditoria conta o ACUMULADO, e nao a sessao que acabou. Ninguem revisa 150
    amostras de uma sentada; um relatorio que contasse so a ultima rodada diria
    "80 revisadas" depois de 80 + 70 e mandaria a pessoa refazer trabalho que ja
    estava feito.
    """
    achadas: list[Decisao] = []
    for indice, registro in enumerate(registros):
        provisorio = registro.get("rotulo_provisorio")
        rotulo = registro.get("rotulo")
        if registro.get("origem_do_rotulo") != ORIGEM_HUMANA:
            continue
        if provisorio not in ROTULOS or rotulo not in ROTULOS:
            continue
        achadas.append(Decisao(
            amostra=Amostra(indice=indice,
                            id=str(registro.get("id") or f"linha-{indice + 1}"),
                            alt=str(registro.get("alt") or ""),
                            rotulo_provisorio=str(provisorio)),
            rotulo=str(rotulo)))
    return achadas


# ---------------------------------------------------------------- amostragem


def amostrar_balanceado(disponiveis: list[Amostra],
                        por_classe: int = POR_CLASSE_PADRAO,
                        semente: int = 42) -> list[Amostra]:
    """`por_classe` amostras de cada rotulo provisorio, embaralhadas no fim.

    Classe com menos que `por_classe` entrega o que tem — e o caso normal, nao
    excecao: `INSUFFICIENT` e a classe rara em corpus de descricao publica.
    Falhar aqui deixaria a auditoria impossivel justamente quando ela mais
    importa; a falta aparece no relatorio, em `faltando_por_classe`.

    O embaralhamento final e o ponto menos obvio e o mais importante: entregar
    50 `GOOD` seguidos, depois 50 `WEAK`, ensina a sequencia ao revisor. Ele
    passa a responder pelo bloco em que esta, e a concordancia medida vira
    artefato da ordem de apresentacao.
    """
    if por_classe < 1:
        raise RevisaoError("por_classe precisa ser pelo menos 1.")

    aleatorio = random.Random(semente)
    escolhidas: list[Amostra] = []
    for rotulo in ROTULOS:
        da_classe = [a for a in disponiveis if a.rotulo_provisorio == rotulo]
        aleatorio.shuffle(da_classe)
        escolhidas += da_classe[:por_classe]

    aleatorio.shuffle(escolhidas)
    return escolhidas


def faltando_por_classe(disponiveis: list[Amostra],
                        por_classe: int = POR_CLASSE_PADRAO) -> dict[str, int]:
    """Quanto cada classe ficou devendo em relacao a cota."""
    contagem = contar(a.rotulo_provisorio for a in disponiveis)
    return {rotulo: max(por_classe - contagem.get(rotulo, 0), 0)
            for rotulo in ROTULOS}


def contar(valores: Any) -> dict[str, int]:
    contagem: dict[str, int] = {}
    for valor in valores:
        chave = str(valor)
        contagem[chave] = contagem.get(chave, 0) + 1
    return contagem


# --------------------------------------------------------------------- kappa


def kappa_de_cohen(primeiro: list[str], segundo: list[str],
                   rotulos: tuple[str, ...] = ROTULOS) -> float:
    """Kappa de Cohen entre dois avaliadores sobre as mesmas amostras.

        kappa = (po - pe) / (1 - pe)

    `po` e a proporcao de concordancia observada. `pe` e a concordancia esperada
    por acaso: para cada classe, a chance de o primeiro avaliador escolhe-la
    vezes a chance de o segundo escolher a mesma, somada sobre as classes.

    O caso `pe == 1` nao e detalhe: acontece quando os DOIS lados usaram uma
    unica classe, e ai a formula divide por zero. Concordancia total nesse
    cenario e 1.0 — concordam — e discordancia impossivel. Devolver `nan` aqui
    faria o relatorio carregar um numero que nao serializa em JSON.
    """
    if len(primeiro) != len(segundo):
        raise RevisaoError(
            f"listas de tamanhos diferentes: {len(primeiro)} e {len(segundo)}.")
    total = len(primeiro)
    if total == 0:
        raise RevisaoError("nao da para calcular kappa sem nenhuma amostra.")

    concordancias = sum(1 for a, b in zip(primeiro, segundo, strict=True) if a == b)
    po = concordancias / total

    contagem_primeiro = contar(primeiro)
    contagem_segundo = contar(segundo)
    pe = sum((contagem_primeiro.get(rotulo, 0) / total)
             * (contagem_segundo.get(rotulo, 0) / total)
             for rotulo in rotulos)

    if pe >= 1.0:
        return 1.0 if po >= 1.0 else 0.0
    return (po - pe) / (1.0 - pe)


def interpretar(kappa: float) -> str:
    """Faixa qualitativa de Landis & Koch, para o relatorio nao ser so um numero."""
    if kappa < 0.0:
        return "pior que o acaso"
    if kappa < 0.20:
        return "insignificante"
    if kappa < 0.40:
        return "fraca"
    if kappa < 0.60:
        return "moderada"
    if kappa < 0.80:
        return "substancial"
    return "quase perfeita"


def matriz_de_confusao(primeiro: list[str], segundo: list[str],
                       rotulos: tuple[str, ...] = ROTULOS) -> list[list[int]]:
    """Linhas = pre-rotulo, colunas = humano. A ordem e `rotulos`."""
    posicao = {rotulo: i for i, rotulo in enumerate(rotulos)}
    matriz = [[0] * len(rotulos) for _ in rotulos]
    for pre, humano in zip(primeiro, segundo, strict=True):
        if pre in posicao and humano in posicao:
            matriz[posicao[pre]][posicao[humano]] += 1
    return matriz


# ------------------------------------------------------------------ relatorio


def _agora() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def montar_relatorio(decisoes: list[Decisao], puladas_na_sessao: int,
                     disponiveis: list[Amostra], por_classe: int,
                     caminho_do_dataset: pathlib.Path) -> dict[str, Any]:
    """O bloco que vai para `relatorio_revisao.json`.

    `decisoes` e o ACUMULADO do arquivo (ver `decisoes_registradas`); so
    `puladas_na_sessao` fala da rodada que acabou, e o nome diz isso para que
    ninguem some os dois numeros achando que medem a mesma coisa.
    """
    provisorios = [d.amostra.rotulo_provisorio for d in decisoes]
    humanos = [d.rotulo for d in decisoes]
    total = len(decisoes)

    divergencias = sum(1 for d in decisoes if d.divergiu)
    taxa = divergencias / total if total else 0.0
    kappa = kappa_de_cohen(provisorios, humanos) if total else 0.0
    atende = total >= MINIMO_DO_ADR and kappa >= KAPPA_MINIMO_DO_ADR

    return {
        "gerado_em": _agora(),
        "dataset": str(caminho_do_dataset),
        "total_revisado": total,
        "puladas_na_sessao": puladas_na_sessao,
        "por_classe_pedido": por_classe,
        "faltando_por_classe": faltando_por_classe(disponiveis, por_classe),
        "distribuicao_rotulos": {
            "humano": {r: contar(humanos).get(r, 0) for r in ROTULOS},
            "provisorio": {r: contar(provisorios).get(r, 0) for r in ROTULOS},
        },
        "divergencias": divergencias,
        "taxa_correcao": taxa,
        "kappa_cohen": kappa,
        "interpretacao_do_kappa": interpretar(kappa) if total else "sem amostra",
        "matriz_de_confusao": {
            "rotulos": list(ROTULOS),
            "linhas_sao_provisorio_colunas_sao_humano":
                matriz_de_confusao(provisorios, humanos),
        },
        "criterio_adr0002": {
            "minimo_revisado": MINIMO_DO_ADR,
            "kappa_minimo": KAPPA_MINIMO_DO_ADR,
        },
        "atende_adr0002": atende,
        "veredito": _veredito(total, kappa, atende),
    }


def _veredito(total: int, kappa: float, atende: bool) -> str:
    """A frase que o relatorio nao pode deixar de dizer."""
    if total == 0:
        return ("SEM REVISAO: nenhuma amostra foi rotulada por humano. O ADR 0002 "
                "secao 4 segue em aberto.")
    if total < MINIMO_DO_ADR:
        return (f"INCOMPLETO: {total} amostras revisadas contra as "
                f"{MINIMO_DO_ADR} do ADR 0002. O kappa de {kappa:.3f} e "
                "indicativo, nao a medida que o ADR pede.")
    if not atende:
        return (f"NAO ATENDE: kappa {kappa:.3f} abaixo de "
                f"{KAPPA_MINIMO_DO_ADR:.2f}. O pre-rotulo NAO pode ser promovido "
                "no resto do dataset — a heuristica discorda do humano com "
                "frequencia alta demais para servir de rotulo.")
    return (f"ATENDE: {total} amostras revisadas, kappa {kappa:.3f} "
            f"({interpretar(kappa)}). A concordancia sustenta promover o "
            "pre-rotulo no restante — o que NAO significa que o rotulo esta "
            "certo, so que os dois lados concordam.")


# ------------------------------------------------------------------ gravacao


def aplicar(registros: list[dict[str, Any]], decisoes: list[Decisao],
            quando: str | None = None) -> int:
    """Escreve as decisoes nos registros em memoria. Devolve quantas entraram."""
    momento = quando or _agora()
    for decisao in decisoes:
        registro = registros[decisao.amostra.indice]
        registro["rotulo"] = decisao.rotulo
        registro["origem_do_rotulo"] = ORIGEM_HUMANA
        registro["data_revisao"] = momento
    return len(decisoes)


def gravar(caminho: pathlib.Path, registros: list[dict[str, Any]]) -> None:
    """Reescreve o JSONL por arquivo temporario e troca atomica.

    Escrever por cima do original seria perder o dataset inteiro se o processo
    morresse no meio — e este e o unico arquivo onde a revisao humana existe.
    O temporario fica NA MESMA PASTA de proposito: `os.replace` so e atomico
    dentro do mesmo sistema de arquivos, e um temporario em `/tmp` quebraria
    essa garantia sem nenhum aviso.
    """
    temporario = caminho.with_name(caminho.name + ".tmp")
    with temporario.open("w", encoding="utf-8", newline="\n") as arquivo:
        for registro in registros:
            arquivo.write(json.dumps(registro, ensure_ascii=False) + "\n")
        arquivo.flush()
        os.fsync(arquivo.fileno())
    os.replace(temporario, caminho)


def gravar_relatorio(caminho: pathlib.Path, relatorio: dict[str, Any]) -> None:
    caminho.parent.mkdir(parents=True, exist_ok=True)
    caminho.write_text(json.dumps(relatorio, ensure_ascii=False, indent=2) + "\n",
                       encoding="utf-8")
