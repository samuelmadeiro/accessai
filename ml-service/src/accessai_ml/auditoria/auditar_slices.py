"""Auditoria profunda de integridade e particionamento do dataset (ADR 0002).

    accessai-auditar-slices --dataset data/alt_texts.jsonl

O dataset chegou ao ponto em que uma pessoa vai revisar 150 amostras a mao. Esse
trabalho nao pode comecar em cima de um arquivo que ninguem conferiu: uma linha
sintetica escapada para o lado avaliado, um `SUFFICIENT` sobrevivente de uma
versao antiga do enum, ou 43 sinteticas em vez de 44 mudam o que a revisao
significa DEPOIS que ela ja custou o tempo do revisor.

Este modulo confere cinco eixos e devolve `EXIT=1` na primeira divergencia real:

* **A - corpus.** Contagem por slice, duplicata de `id` e de alt normalizado,
  distribuicao de classe e de comprimento.
* **B - contaminacao.** Que a sintetica e marcada, e que `training.validacao` de
  fato a mantem FORA do lado avaliado de cada pasta. A conferencia e dinamica:
  as pastas sao recalculadas aqui, com a mesma semente, e o que `validar`
  reporta ter avaliado e comparado com o que ele DEVERIA ter avaliado.
* **C - fila de revisao.** Que `amostrar_balanceado` entrega as 150 do ADR, sem
  bloco por classe e sem vazar o pre-rotulo para a tela do revisor.
* **D - esquema.** Enum canonico e vocabulario de `origem_do_rotulo`, que sao o
  contrato com o PostgreSQL e com o DTO do Spring.
* **E - I/O de terminal.** Que a saida reconfigura para UTF-8 com
  `errors="replace"` — sem isso um alt com caractere fora da cp1252 mata a CLI
  no meio da sessao do revisor, no Windows.

**O que este auditor NAO faz.** Ele nao diz que os rotulos estao CERTOS. Hoje
`rotulo` e nulo em todas as linhas: o rotulo de trabalho usado no Eixo B e o
`rotulo_provisorio` da heuristica, e isso vai declarado no relatorio. Auditoria
de contagem e de vazamento e o que da para automatizar; concordancia com humano
e o que `dataset.revisao` mede, e ainda nao foi medida.
"""

from __future__ import annotations

import argparse
import ast
import dataclasses
import hashlib
import inspect
import json
import pathlib
import sys
from collections import Counter
from collections.abc import Iterator, Sequence
from datetime import UTC, datetime
from types import ModuleType
from typing import Any

from sklearn.model_selection import StratifiedGroupKFold

from ..dataset import cli_revisao, divisao, gerador_insufficient, revisao
from ..training import dados, modelo, validacao

# --------------------------------------------------------------- vocabulario

ROTULOS = dados.ROTULOS_VALIDOS

# Classe de uma versao anterior do esquema. Nao basta "nao esta na lista": o
# nome e conferido explicitamente porque um `SUFFICIENT` sobrevivente quebra a
# constraint do PostgreSQL e o enum do DTO do Spring, e a mensagem generica
# "rotulo invalido" nao diria a quem le onde o estrago aparece.
ROTULO_APOSENTADO = "SUFFICIENT"

ORIGENS_DE_ROTULO = ("heuristica", "humano", "sintetico_fallback",
                     "documentos_governamentais")

SLICE_GOVERNAMENTAL = "governamental_docx"
SLICE_COMMONS = "wikimedia_commons"
SLICE_SINTETICO = "sintetico_fallback"
SLICE_WEB = "web_coletado"
SLICE_DESCONHECIDO = "desconhecido"

FONTE_COMMONS = "wikimedia-commons"
ORIGEM_COLETADA = "coletado"

PAPEL_NO_TREINO = {
    SLICE_GOVERNAMENTAL: "nenhum (alt ausente = Rule Engine)",
    SLICE_COMMONS: "treino + validacao + teste",
    SLICE_SINTETICO: "somente treino",
    SLICE_WEB: "treino + validacao + teste",
    SLICE_DESCONHECIDO: "indefinido",
}

NOME_DO_RELATORIO = "relatorio_auditoria_slices.json"

SAIDA_OK = 0
SAIDA_DIVERGENCIA = 1

PASSOU = "PASS"
FALHOU = "FAIL"
AVISO = "AVISO"
NAO_AVALIAVEL = "NAO_AVALIAVEL"

# Faixas de comprimento do alt. Abaixo de 10 caracteres nao cabe descricao de
# nada; acima de 30 comeca a haver frase. Os cortes sao os mesmos que a
# heuristica de pre-rotulo usa, de proposito: auditar com regua diferente da que
# rotulou esconderia justamente o desacordo entre as duas.
CURTO = 10
MEDIO = 30

MODULOS_DE_TERMINAL = ("accessai_ml.dataset.cli_revisao",
                       "accessai_ml.auditoria.auditar_slices")


@dataclasses.dataclass(frozen=True)
class Esperado:
    """As contagens que o dataset precisa ter para a revisao humana comecar.

    Vem como parametro, e nao como constante solta, porque o teste precisa
    auditar corpus de dez linhas sem que o auditor exija as 749 de producao — e
    porque congelar o numero no meio do codigo faria a proxima coleta editar a
    regra junto com o dado, que e como invariante morre sem ninguem notar.
    """

    total: int = 749
    governamentais: int = 5
    commons: int = 700
    sinteticas: int = 44
    por_classe_na_fila: int = revisao.POR_CLASSE_PADRAO
    fila: int = revisao.MINIMO_DO_ADR
    f1_macro_media: float = 0.508
    f1_macro_desvio: float = 0.098
    # Tolerancia da faixa. `StratifiedGroupKFold` com a mesma semente e
    # deterministico, mas a versao do scikit-learn nao e: um upgrade menor move
    # a terceira casa, e falhar a auditoria inteira por isso seria alarme falso.
    tolerancia_f1: float = 0.02
    # O numero que a metrica assume quando a sintetica VOLTA para o lado
    # avaliado. Nao e chute: e o valor medido neste corpus removendo o filtro de
    # `validacao.validar`. Bater nele e a assinatura da contaminacao.
    f1_macro_contaminado: float = 0.709


@dataclasses.dataclass(frozen=True)
class Verificacao:
    """Uma invariante conferida, com o que se esperava e o que se achou."""

    eixo: str
    nome: str
    status: str
    esperado: Any
    obtido: Any
    detalhe: str = ""

    @property
    def falhou(self) -> bool:
        return self.status == FALHOU

    def para_json(self) -> dict[str, Any]:
        return dataclasses.asdict(self)


@dataclasses.dataclass
class ContagemDeSlice:
    """O acumulado de um slice durante a leitura em fluxo."""

    nome: str
    total: int = 0
    por_classe: Counter[str] = dataclasses.field(default_factory=Counter)
    por_divisao: Counter[str] = dataclasses.field(default_factory=Counter)
    por_comprimento: Counter[str] = dataclasses.field(default_factory=Counter)
    sem_alt: int = 0
    sinteticas: int = 0
    origem_declarada: Counter[str] = dataclasses.field(default_factory=Counter)
    # Resumo dos ids, para o relatorio provar de que conjunto de linhas ele
    # estava falando. Guardar os ids inteiros seria guardar o dataset de novo.
    digest: Any = dataclasses.field(default_factory=hashlib.sha256, repr=False)

    def registrar(self, identificador: str, alt: str, rotulo: str | None,
                  parte: object, origem: object, sintetica: bool) -> None:
        self.total += 1
        self.por_classe[rotulo or "sem_rotulo"] += 1
        self.por_divisao[str(parte)] += 1
        self.origem_declarada[str(origem)] += 1
        if sintetica:
            self.sinteticas += 1
        if not alt:
            self.sem_alt += 1
        else:
            comprimento = len(alt)
            faixa = ("<=10" if comprimento <= CURTO
                     else ("11-30" if comprimento <= MEDIO else ">30"))
            self.por_comprimento[faixa] += 1
        self.digest.update(identificador.encode("utf-8"))
        self.digest.update(b"\x00")

    @property
    def hash_dos_ids(self) -> str:
        resultado: str = self.digest.hexdigest()
        return resultado

    @property
    def distribuicao(self) -> dict[str, int]:
        return {rotulo: self.por_classe.get(rotulo, 0) for rotulo in ROTULOS}

    def para_json(self) -> dict[str, Any]:
        return {
            "slice": self.nome,
            "total": self.total,
            "papel_no_treino": PAPEL_NO_TREINO.get(self.nome, "indefinido"),
            "distribuicao_de_classe": self.distribuicao,
            "sem_rotulo": self.por_classe.get("sem_rotulo", 0),
            "por_divisao": dict(sorted(self.por_divisao.items())),
            "por_comprimento_do_alt": dict(sorted(self.por_comprimento.items())),
            "sem_alt": self.sem_alt,
            "sinteticas": self.sinteticas,
            "origem_do_dado_declarada": dict(sorted(self.origem_declarada.items())),
            "hash_dos_ids": self.hash_dos_ids,
        }


@dataclasses.dataclass
class Corpus:
    """O resultado da unica passagem de leitura sobre o JSONL."""

    total_de_linhas: int = 0
    slices: dict[str, ContagemDeSlice] = dataclasses.field(default_factory=dict)
    ids_repetidos: list[str] = dataclasses.field(default_factory=list)
    alts_repetidos: list[str] = dataclasses.field(default_factory=list)
    rotulos_invalidos: list[str] = dataclasses.field(default_factory=list)
    origens_de_rotulo_invalidas: list[str] = dataclasses.field(default_factory=list)
    divisoes_invalidas: list[str] = dataclasses.field(default_factory=list)
    origem_do_dado_ausente: int = 0
    rotulos_humanos: int = 0
    # As duas listas que os eixos B e C consomem. Sao projecoes estreitas da
    # linha — texto, rotulo, grupo — e nao a linha inteira: o JSONL carrega
    # contexto e metadado de proveniencia que nenhum dos dois eixos olha.
    pendentes: list[revisao.Amostra] = dataclasses.field(default_factory=list)
    treinaveis: list[dados.Amostra] = dataclasses.field(default_factory=list)
    rotulo_de_trabalho: str = "rotulo_provisorio (heuristica)"

    def slice_de(self, nome: str) -> ContagemDeSlice:
        return self.slices.get(nome, ContagemDeSlice(nome=nome))


# ------------------------------------------------------------------- leitura


def ler_em_fluxo(caminho: pathlib.Path) -> Iterator[tuple[int, dict[str, Any]]]:
    """Percorre o JSONL linha a linha, sem materializar o arquivo inteiro.

    Gerador, e nao lista, porque a auditoria roda em CI ao lado do treino:
    dobrar o dataset na memoria so para conta-lo e desperdicio que cresce junto
    com a coleta.
    """
    with caminho.open(encoding="utf-8") as arquivo:
        for numero, bruta in enumerate(arquivo, start=1):
            bruta = bruta.strip()
            if not bruta:
                continue
            try:
                linha = json.loads(bruta)
            except json.JSONDecodeError as erro:
                raise ValueError(f"linha {numero} nao e JSON valido: {erro}") from erro
            if not isinstance(linha, dict):
                raise ValueError(f"linha {numero} nao e um objeto JSON.")
            yield numero, linha


def classificar_slice(linha: dict[str, Any]) -> str:
    """De que slice esta linha veio, pelo que ela declara.

    `origem_do_dado` so existe nas linhas escritas por `coletor_web` — o Commons
    e os `.docx` governamentais sao anteriores ao campo e se identificam por
    `fonte`. Derivar aqui e o que permite auditar o corpus como ele esta, em vez
    de exigir uma reescrita do arquivo antes de poder conferi-lo; a ausencia do
    campo vira um AVISO no Eixo D, nao some.
    """
    origem = linha.get("origem_do_dado")
    fonte = linha.get("fonte")
    if origem == gerador_insufficient.ORIGEM_SINTETICA or fonte == gerador_insufficient.FONTE:
        return SLICE_SINTETICO
    if fonte == FONTE_COMMONS or origem == SLICE_COMMONS:
        return SLICE_COMMONS
    if origem == ORIGEM_COLETADA:
        return SLICE_WEB
    if fonte is None and str(linha.get("arquivo") or "").endswith(".docx"):
        return SLICE_GOVERNAMENTAL
    return SLICE_DESCONHECIDO


def _normalizar(alt: str) -> str:
    return " ".join(alt.split()).casefold()


def ler_corpus(caminho: pathlib.Path) -> Corpus:
    """Uma passagem, tres produtos: contagens, fila de revisao e amostras de treino."""
    corpus = Corpus()
    ids_vistos: set[str] = set()
    # Hash do alt normalizado, e nao o alt: a deteccao de duplicata precisa de
    # um conjunto do tamanho do corpus, e 64 bytes por linha e o preco fixo que
    # substitui guardar o texto inteiro de novo.
    alts_vistos: set[str] = set()

    for numero, linha in ler_em_fluxo(caminho):
        corpus.total_de_linhas += 1
        conta = corpus.slices.setdefault(
            classificar_slice(linha),
            ContagemDeSlice(nome=classificar_slice(linha)))

        identificador = str(linha.get("id") or f"linha-{numero}")
        alt = (linha.get("alt") or "").strip()
        provisorio = linha.get("rotulo_provisorio")
        rotulo = linha.get("rotulo")
        origem_do_rotulo = linha.get("origem_do_rotulo")
        origem_do_dado = linha.get("origem_do_dado")
        parte = linha.get("divisao")
        sintetica = origem_do_dado == dados.ORIGEM_SINTETICA

        conta.registrar(identificador, alt, rotulo or provisorio, parte,
                        origem_do_dado, sintetica)

        if origem_do_dado is None:
            corpus.origem_do_dado_ausente += 1
        if origem_do_rotulo == revisao.ORIGEM_HUMANA:
            corpus.rotulos_humanos += 1

        if identificador in ids_vistos:
            corpus.ids_repetidos.append(identificador)
        ids_vistos.add(identificador)

        # Alt vazio repete por construcao — sao as imagens governamentais sem
        # alt, que e o achado do ADR e nao duplicata de conteudo.
        if alt:
            chave = hashlib.sha256(_normalizar(alt).encode("utf-8")).hexdigest()
            if chave in alts_vistos:
                corpus.alts_repetidos.append(identificador)
            alts_vistos.add(chave)

        for campo, valor in (("rotulo", rotulo), ("rotulo_provisorio", provisorio)):
            if valor is not None and valor not in ROTULOS:
                corpus.rotulos_invalidos.append(f"linha {numero}: {campo}={valor!r}")
        if origem_do_rotulo is not None and origem_do_rotulo not in ORIGENS_DE_ROTULO:
            corpus.origens_de_rotulo_invalidas.append(
                f"linha {numero}: origem_do_rotulo={origem_do_rotulo!r}")
        if parte is not None and parte not in divisao.PARTES:
            corpus.divisoes_invalidas.append(f"linha {numero}: divisao={parte!r}")

        if alt and provisorio in ROTULOS and rotulo is None:
            corpus.pendentes.append(revisao.Amostra(
                indice=numero - 1, id=identificador, alt=alt,
                rotulo_provisorio=str(provisorio)))

        de_trabalho = rotulo if rotulo in ROTULOS else provisorio
        if alt and de_trabalho in ROTULOS and parte in divisao.PARTES:
            corpus.treinaveis.append(dados.Amostra(
                id=identificador, texto=alt, rotulo=str(de_trabalho),
                grupo=str(linha.get("grupo") or _normalizar(alt)),
                divisao=str(parte), sintetica=sintetica))

    if corpus.rotulos_humanos:
        corpus.rotulo_de_trabalho = (
            f"rotulo humano em {corpus.rotulos_humanos} linhas, "
            "rotulo_provisorio no resto")
    return corpus


def hash_do_arquivo(caminho: pathlib.Path) -> str:
    """SHA-256 do arquivo, lido em blocos — mesmo motivo do gerador acima."""
    digest = hashlib.sha256()
    with caminho.open("rb") as arquivo:
        for bloco in iter(lambda: arquivo.read(65536), b""):
            digest.update(bloco)
    return digest.hexdigest()


# ---------------------------------------------------- eixo A: corpus e slices


def auditar_corpus(corpus: Corpus, esperado: Esperado) -> list[Verificacao]:
    """Contagem por slice, duplicata e as invariantes de composicao do Eixo A."""
    achados: list[Verificacao] = []

    def conferir(nome: str, obtido: Any, alvo: Any, detalhe: str = "") -> None:
        achados.append(Verificacao(
            eixo="A", nome=nome, status=PASSOU if obtido == alvo else FALHOU,
            esperado=alvo, obtido=obtido, detalhe=detalhe))

    governamental = corpus.slice_de(SLICE_GOVERNAMENTAL)
    commons = corpus.slice_de(SLICE_COMMONS)
    sintetico = corpus.slice_de(SLICE_SINTETICO)

    conferir("A1 slice governamental (.docx): total", governamental.total,
             esperado.governamentais)
    conferir("A1 slice governamental: registros com alt nulo/vazio",
             governamental.sem_alt, esperado.governamentais,
             "as imagens sem alt do corpus .docx sao o sinalizador de dominio que "
             "mantem o pipeline cru em EXIT=3; some-las apagaria o achado do ADR")

    conferir("A2 slice Commons: total", commons.total, esperado.commons)
    achados.append(Verificacao(
        eixo="A", nome="A2 Commons: distribuicao de comprimento do alt",
        status=PASSOU if commons.total else NAO_AVALIAVEL,
        esperado="faixas <=10 / 11-30 / >30",
        obtido=dict(sorted(commons.por_comprimento.items())),
        detalhe="proporcao de ruido: alt curto e o candidato natural a WEAK e a "
                "INSUFFICIENT, e um corpus so de alt longo nao ensina a classe rara"))

    conferir("A3 slice sintetico: total", sintetico.total, esperado.sinteticas)
    conferir("A3 sintetico: marcado com origem_do_dado", sintetico.sinteticas,
             esperado.sinteticas)
    fora_da_classe = {r: n for r, n in sintetico.por_classe.items()
                      if r != gerador_insufficient.INSUFICIENTE}
    achados.append(Verificacao(
        eixo="A", nome="A3 sintetico: 100% em INSUFFICIENT",
        status=PASSOU if not fora_da_classe else FALHOU,
        esperado={}, obtido=fora_da_classe,
        detalhe="o gerador existe para a classe rara; sintetica em outra classe e "
                "string deste repositorio ensinando o modelo a reconhecer a si mesma"))

    conferir("A4 total de linhas", corpus.total_de_linhas, esperado.total)
    conferir("A4 ids duplicados", len(corpus.ids_repetidos), 0,
             "; ".join(corpus.ids_repetidos[:5]))
    conferir("A4 alt normalizado duplicado", len(corpus.alts_repetidos), 0,
             "; ".join(corpus.alts_repetidos[:5]))
    conferir("A4 soma dos slices fecha com o total",
             sum(c.total for c in corpus.slices.values()), corpus.total_de_linhas)
    conferir("A4 linhas sem slice reconhecido",
             corpus.slice_de(SLICE_DESCONHECIDO).total, 0,
             "linha que nao declara nem `fonte` nem `origem_do_dado` nao tem "
             "proveniencia auditavel")
    return achados


# --------------------------------------------- eixo B: contaminacao e vazamento


def pastas_esperadas(amostras: Sequence[dados.Amostra], semente: int,
                     pastas: int) -> list[tuple[int, int]]:
    """Recalcula as pastas aqui, para ter com que comparar o que `validar` reporta.

    Confiar no numero que o proprio modulo auditado imprime seria auditar a
    afirmacao dele, nao o comportamento: se o filtro sumir, ele reportaria
    "0 sinteticas removidas" com a mesma sinceridade.

    Devolve, por pasta, `(avaliaveis_sem_sintetica, sinteticas_no_lado_avaliado)`.
    """
    efetivas, _ = validacao.pastas_viaveis(list(amostras), pastas)
    if efetivas < validacao.MINIMO_DE_PASTAS:
        return []
    textos = [a.texto for a in amostras]
    rotulos = [a.rotulo for a in amostras]
    grupos = [a.grupo for a in amostras]
    divisor = StratifiedGroupKFold(n_splits=efetivas, shuffle=True,
                                   random_state=semente)
    esperadas: list[tuple[int, int]] = []
    for _, indices_teste in divisor.split(textos, rotulos, groups=grupos):
        sinteticas = sum(1 for i in indices_teste if amostras[i].sintetica)
        esperadas.append((len(indices_teste) - sinteticas, sinteticas))
    return esperadas


def auditar_contaminacao(corpus: Corpus, esperado: Esperado, *, semente: int = 42,
                         c: float = modelo.C_PADRAO,
                         min_df: int = modelo.MIN_DF_PADRAO,
                         pastas: int = validacao.PASTAS_PADRAO,
                         modulo: ModuleType = validacao) -> list[Verificacao]:
    """Eixo B: a sintetica treina, e nunca e avaliada.

    `modulo` e injetado para que o teste passe uma validacao contaminada de
    proposito e prove que este auditor a pega. Auditor exercitado so contra o
    caso bom nao e auditor, e otimismo com nome tecnico.
    """
    achados: list[Verificacao] = []

    marcadas = sum(1 for a in corpus.treinaveis if a.sintetica)
    achados.append(Verificacao(
        eixo="B", nome="B1 sinteticas marcadas com Amostra.sintetica",
        status=PASSOU if marcadas == esperado.sinteticas else FALHOU,
        esperado=esperado.sinteticas, obtido=marcadas,
        detalhe="sem a marca a validacao cruzada nao tem como remove-las do lado "
                "avaliado — a protecao inteira depende deste booleano"))
    achados.append(Verificacao(
        eixo="B", nome="B1 constante de origem sintetica bate entre gerador e treino",
        status=(PASSOU if dados.ORIGEM_SINTETICA == gerador_insufficient.ORIGEM_SINTETICA
                else FALHOU),
        esperado=gerador_insufficient.ORIGEM_SINTETICA, obtido=dados.ORIGEM_SINTETICA,
        detalhe="duas constantes com o mesmo papel em modulos diferentes: se uma "
                "mudar sozinha, a marca para de ser aplicada em silencio"))

    fora_do_treino = [a.id for a in corpus.treinaveis
                      if a.sintetica and a.divisao != divisao.TREINO]
    achados.append(Verificacao(
        eixo="B", nome="B2 sintetica presa a divisao de treino no arquivo",
        status=PASSOU if not fora_do_treino else FALHOU,
        esperado=0, obtido=len(fora_do_treino),
        detalhe="; ".join(fora_do_treino[:5])))

    selecionadas = [a for a in corpus.treinaveis
                    if a.divisao in (divisao.TREINO, divisao.VALIDACAO)]
    esperadas = pastas_esperadas(selecionadas, semente, pastas)
    if not esperadas:
        achados.append(Verificacao(
            eixo="B", nome="B2 isolamento da sintetica no lado avaliado de cada pasta",
            status=NAO_AVALIAVEL, esperado="pastas viaveis", obtido=0,
            detalhe="o corpus nao sustenta validacao cruzada: sem pasta nao ha lado "
                    "avaliado para conferir"))
        return achados

    rotulos = [r for r in ROTULOS if any(a.rotulo == r for a in selecionadas)]
    relatorio = modulo.validar(selecionadas, rotulos, c=c, semente=semente,
                               min_df=min_df, pastas=pastas)
    if not relatorio.get("executada"):
        achados.append(Verificacao(
            eixo="B", nome="B2 isolamento da sintetica no lado avaliado de cada pasta",
            status=NAO_AVALIAVEL, esperado="validacao cruzada executada",
            obtido=relatorio.get("motivo")))
        return achados

    por_pasta = relatorio["por_pasta"]
    divergencias: list[str] = []
    if len(por_pasta) != len(esperadas):
        divergencias.append(
            f"{len(por_pasta)} pastas ajustaram, {len(esperadas)} eram esperadas")
    for pasta, (avaliaveis, sinteticas) in zip(por_pasta, esperadas, strict=False):
        if pasta["amostras_teste"] != avaliaveis:
            divergencias.append(
                f"pasta {pasta['pasta']}: avaliou {pasta['amostras_teste']} amostras, "
                f"o esperado sem sintetica era {avaliaveis}")
        if pasta["sinteticas_removidas_da_avaliacao"] != sinteticas:
            divergencias.append(
                f"pasta {pasta['pasta']}: removeu "
                f"{pasta['sinteticas_removidas_da_avaliacao']} sinteticas, "
                f"a pasta contem {sinteticas}")
    achados.append(Verificacao(
        eixo="B", nome="B2 isolamento da sintetica no lado avaliado de cada pasta",
        status=PASSOU if not divergencias else FALHOU,
        esperado=[{"avaliaveis": a, "sinteticas_removidas": s} for a, s in esperadas],
        obtido=[{"avaliaveis": p["amostras_teste"],
                 "sinteticas_removidas": p["sinteticas_removidas_da_avaliacao"]}
                for p in por_pasta],
        detalhe="; ".join(divergencias)))

    resumo = relatorio["resumo"]["modelo"]
    media = float(resumo["media"])
    desvio = float(resumo["desvio"])

    contaminada = abs(media - esperado.f1_macro_contaminado) <= esperado.tolerancia_f1
    achados.append(Verificacao(
        eixo="B", nome="B3 macro-F1 fora do valor contaminado conhecido",
        status=FALHOU if contaminada else PASSOU,
        esperado=f"longe de {esperado.f1_macro_contaminado:.3f}",
        obtido=round(media, 4),
        detalhe="macro-F1 nesta faixa e o que este corpus produz quando a sintetica "
                "volta para o lado avaliado: o modelo passa a ser medido sobre "
                "string escrita neste repositorio, e o numero deixa de significar "
                "deteccao de alt ruim"))

    na_faixa = (abs(media - esperado.f1_macro_media) <= esperado.tolerancia_f1
                and abs(desvio - esperado.f1_macro_desvio) <= esperado.tolerancia_f1)
    achados.append(Verificacao(
        eixo="B", nome="B3 macro-F1 na faixa registrada",
        # AVISO, e nao FAIL: a faixa e a medida de uma execucao anterior, nao um
        # contrato. O que FALHA neste eixo e vazamento; o corpus crescer e mover
        # a metrica e o funcionamento normal do projeto.
        status=PASSOU if na_faixa else AVISO,
        esperado=f"{esperado.f1_macro_media:.3f} +/- {esperado.f1_macro_desvio:.3f}",
        obtido=f"{media:.3f} +/- {desvio:.3f}",
        detalhe=f"rotulo de trabalho: {corpus.rotulo_de_trabalho}; "
                f"{corpus.rotulos_humanos} rotulos humanos no arquivo. Enquanto "
                "esse numero for zero, a metrica mede a heuristica contra ela mesma"))
    return achados


# ----------------------------------------------- eixo C: fila de revisao humana


def maior_corrida(rotulos: Sequence[str]) -> int:
    """A maior sequencia de amostras seguidas com o mesmo pre-rotulo."""
    maior = 0
    atual = 0
    anterior: str | None = None
    for rotulo in rotulos:
        atual = atual + 1 if rotulo == anterior else 1
        anterior = rotulo
        maior = max(maior, atual)
    return maior


def auditar_fila_de_revisao(corpus: Corpus, esperado: Esperado,
                            semente: int = 42) -> list[Verificacao]:
    """Eixo C: as 150 do ADR, embaralhadas, sem o pre-rotulo na tela."""
    achados: list[Verificacao] = []
    disponiveis = corpus.pendentes

    fila = revisao.amostrar_balanceado(disponiveis, esperado.por_classe_na_fila,
                                       semente)
    achados.append(Verificacao(
        eixo="C", nome="C1 fila com o total do ADR 0002 secao 4",
        status=PASSOU if len(fila) == esperado.fila else FALHOU,
        esperado=esperado.fila, obtido=len(fila),
        detalhe=f"{esperado.por_classe_na_fila} por classe"))

    faltando = revisao.faltando_por_classe(disponiveis, esperado.por_classe_na_fila)
    achados.append(Verificacao(
        eixo="C", nome="C2 nenhuma classe devendo cota",
        status=PASSOU if not any(faltando.values()) else FALHOU,
        esperado=dict.fromkeys(ROTULOS, 0), obtido=faltando,
        detalhe="classe em falta faz o kappa ser medido quase so nas classes cheias, "
                "que e exatamente o que amostrar ao acaso ja faria"))

    repetida = revisao.amostrar_balanceado(disponiveis, esperado.por_classe_na_fila,
                                           semente)
    outra = revisao.amostrar_balanceado(disponiveis, esperado.por_classe_na_fila,
                                        semente + 1)
    determinista = [a.id for a in fila] == [a.id for a in repetida]
    embaralha = [a.id for a in fila] != [a.id for a in outra]
    corrida = maior_corrida([a.rotulo_provisorio for a in fila])
    limite = max(3, esperado.por_classe_na_fila // 10)

    achados.append(Verificacao(
        eixo="C", nome="C3 amostragem deterministica pela semente",
        status=PASSOU if determinista else FALHOU,
        esperado="mesma semente, mesma fila", obtido=determinista,
        detalhe="sem isso, retomar a revisao no dia seguinte reordena a fila e "
                "reoferece o que ja foi julgado"))
    achados.append(Verificacao(
        eixo="C", nome="C3 sem vies de sequencia (bloco por classe)",
        status=PASSOU if embaralha and corrida <= limite else FALHOU,
        esperado=f"embaralha com semente diferente e maior corrida <= {limite}",
        obtido={"maior_corrida": corrida, "muda_com_outra_semente": embaralha},
        detalhe="50 GOOD seguidos ensinam a sequencia ao revisor, e a concordancia "
                "medida vira artefato da ordem de apresentacao"))

    # Sonda com id e alt controlados, e nao a primeira amostra da fila: o
    # cabecalho da tela imprime o `id`, e um id que por acaso contenha a palavra
    # "GOOD" acusaria vazamento que nao existe. O que se mede aqui e o
    # comportamento da tela, que nao depende de qual amostra esta nela.
    amostra = revisao.Amostra(indice=0, id="sonda-da-auditoria",
                              alt="alt de sonda, sem nome de classe no texto",
                              rotulo_provisorio=ROTULOS[0])
    oculto = cli_revisao.apresentar(amostra, 1, len(fila) or 1,
                                    mostrar_pre_rotulo=False)
    visivel = cli_revisao.apresentar(amostra, 1, len(fila) or 1,
                                     mostrar_pre_rotulo=True)
    padrao = inspect.signature(cli_revisao.revisar).parameters["mostrar_pre_rotulo"]
    vazou = amostra.rotulo_provisorio in oculto or "pre-rotulo" in oculto
    achados.append(Verificacao(
        eixo="C", nome="C4 pre-rotulo oculto na tela do revisor",
        status=(PASSOU if not vazou and padrao.default is False else FALHOU),
        esperado="ausente por padrao, presente so com --mostrar-pre-rotulo",
        obtido={"vazou_por_padrao": vazou,
                "aparece_com_a_flag": amostra.rotulo_provisorio in visivel,
                "padrao_do_parametro": padrao.default},
        detalhe="quem ve o palpite da heuristica antes de julgar concorda com ele; "
                "o kappa passa a medir ancoragem, nao concordancia"))
    return achados


# -------------------------------------------------- eixo D: esquema e contratos


def auditar_esquema(corpus: Corpus) -> list[Verificacao]:
    """Eixo D: o enum canonico e o vocabulario de proveniencia do rotulo."""
    aposentado = [d for d in corpus.rotulos_invalidos if ROTULO_APOSENTADO in d]
    return [
        Verificacao(
            eixo="D", nome="D1 rotulos dentro do enum canonico",
            status=PASSOU if not corpus.rotulos_invalidos else FALHOU,
            esperado=list(ROTULOS), obtido=corpus.rotulos_invalidos[:10],
            detalhe="o enum e contrato com a constraint do PostgreSQL e com o DTO do "
                    "Spring: valor fora dele quebra a insercao, nao o treino"),
        Verificacao(
            eixo="D", nome=f"D1 nenhum {ROTULO_APOSENTADO} sobrevivente",
            status=PASSOU if not aposentado else FALHOU,
            esperado=0, obtido=len(aposentado), detalhe="; ".join(aposentado[:5])),
        Verificacao(
            eixo="D", nome="D2 origem_do_rotulo no vocabulario permitido",
            status=PASSOU if not corpus.origens_de_rotulo_invalidas else FALHOU,
            esperado=list(ORIGENS_DE_ROTULO),
            obtido=corpus.origens_de_rotulo_invalidas[:10],
            detalhe="nulo e permitido e significa nao revisado; o que nao pode e um "
                    "quinto valor entrar sem ninguem decidir o que ele quer dizer"),
        Verificacao(
            eixo="D", nome="D2 divisao dentro das partes conhecidas",
            status=PASSOU if not corpus.divisoes_invalidas else FALHOU,
            esperado=list(divisao.PARTES), obtido=corpus.divisoes_invalidas[:10]),
        Verificacao(
            eixo="D", nome="D3 origem_do_dado declarada em toda linha",
            # AVISO: o campo nasceu com `coletor_web`, e as linhas anteriores nao
            # o tem. A proveniencia delas ainda e auditavel por `fonte`, e
            # reescrever o dataset so para preencher o campo mexeria justamente
            # no arquivo que a revisao humana vai usar. Divida registrada, nao
            # falha.
            status=PASSOU if not corpus.origem_do_dado_ausente else AVISO,
            esperado=0, obtido=corpus.origem_do_dado_ausente,
            detalhe="linhas anteriores ao campo; a origem foi derivada de `fonte`"),
    ]


# ------------------------------------------------------ eixo E: I/O de terminal


def reconfigura_para_utf8(fonte: str) -> bool:
    """Procura na arvore sintatica uma chamada `reconfigure(utf-8, replace)`.

    Estatico, e nao "roda e ve se quebra": o defeito que este eixo previne so
    aparece com console cp1252 e um alt fora dele, combinacao que a maquina da
    auditoria pode nao ter. Ler o codigo acha a ausencia em qualquer sistema.
    """
    for no in ast.walk(ast.parse(fonte)):
        if not isinstance(no, ast.Call):
            continue
        argumentos = {p.arg: p.value for p in no.keywords}
        codificacao = argumentos.get("encoding")
        erros = argumentos.get("errors")
        if not isinstance(codificacao, ast.Constant):
            continue
        if not isinstance(erros, ast.Constant):
            continue
        if (str(codificacao.value).lower().replace("-", "") == "utf8"
                and erros.value == "replace"):
            return True
    return False


def auditar_io_de_terminal(
        modulos: Sequence[str] = MODULOS_DE_TERMINAL) -> list[Verificacao]:
    """Eixo E: a saida do terminal aguenta um alt fora da pagina de codigo."""
    achados: list[Verificacao] = []
    for nome in modulos:
        try:
            fonte = inspect.getsource(__import__(nome, fromlist=["_"]))
        except (ImportError, OSError, TypeError) as erro:
            achados.append(Verificacao(
                eixo="E", nome=f"E1 {nome}", status=FALHOU,
                esperado="modulo legivel", obtido=f"{type(erro).__name__}: {erro}"))
            continue
        achados.append(Verificacao(
            eixo="E", nome=f"E1 {nome} reconfigura UTF-8 com errors=replace",
            status=PASSOU if reconfigura_para_utf8(fonte) else FALHOU,
            esperado='reconfigure(encoding="utf-8", errors="replace")',
            obtido=reconfigura_para_utf8(fonte),
            detalhe="sem isto, no Windows um alt com caractere fora da cp1252 "
                    "levanta UnicodeEncodeError e derruba a sessao no meio"))
    return achados


# ------------------------------------------------------------------ auditoria


def _agora() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def auditar(caminho: pathlib.Path, esperado: Esperado | None = None, *,
            semente: int = 42, c: float = modelo.C_PADRAO,
            min_df: int = modelo.MIN_DF_PADRAO,
            pastas: int = validacao.PASTAS_PADRAO,
            com_dinamico: bool = True,
            modulo_de_validacao: ModuleType = validacao) -> dict[str, Any]:
    """Roda os cinco eixos e monta o relatorio. Nao grava nada, nao imprime nada."""
    esperado = esperado or Esperado()
    corpus = ler_corpus(caminho)

    achados = auditar_corpus(corpus, esperado)
    achados += auditar_esquema(corpus)
    achados += auditar_fila_de_revisao(corpus, esperado, semente)
    achados += auditar_io_de_terminal()
    if com_dinamico:
        achados += auditar_contaminacao(corpus, esperado, semente=semente, c=c,
                                        min_df=min_df, pastas=pastas,
                                        modulo=modulo_de_validacao)
    else:
        achados.append(Verificacao(
            eixo="B", nome="B2 isolamento da sintetica no lado avaliado de cada pasta",
            status=NAO_AVALIAVEL, esperado="validacao cruzada executada",
            obtido="--sem-dinamico",
            detalhe="a conferencia dinamica foi desligada por opcao de quem rodou"))

    falhas = [v for v in achados if v.falhou]
    avisos = [v for v in achados if v.status == AVISO]
    fila_completa = all(
        v.status == PASSOU for v in achados if v.eixo == "C" and v.nome.startswith("C1"))

    return {
        "gerado_em": _agora(),
        "dataset": str(caminho),
        "hash_do_dataset": hash_do_arquivo(caminho),
        "total_de_linhas": corpus.total_de_linhas,
        "rotulo_de_trabalho": corpus.rotulo_de_trabalho,
        "rotulos_humanos": corpus.rotulos_humanos,
        "esperado": dataclasses.asdict(esperado),
        "parametros": {"semente": semente, "C": c, "min_df": min_df,
                       "pastas": pastas, "dinamico": com_dinamico},
        "slices": [corpus.slices[nome].para_json() for nome in sorted(corpus.slices)],
        "verificacoes": [v.para_json() for v in achados],
        "resumo": {
            "total": len(achados),
            PASSOU: sum(1 for v in achados if v.status == PASSOU),
            FALHOU: len(falhas),
            AVISO: len(avisos),
            NAO_AVALIAVEL: sum(1 for v in achados if v.status == NAO_AVALIAVEL),
        },
        "falhas": [v.nome for v in falhas],
        "avisos": [v.nome for v in avisos],
        "pronto_para_revisao_humana": not falhas and fila_completa,
        "veredito": _veredito(falhas, avisos, fila_completa),
    }


def _veredito(falhas: list[Verificacao], avisos: list[Verificacao],
              fila_completa: bool) -> str:
    """A frase que o relatorio nao pode deixar de dizer."""
    if falhas:
        primeira = falhas[0]
        return (f"NAO PRONTO: {len(falhas)} invariante(s) quebrada(s). A primeira e "
                f"{primeira.nome!r} — esperado {primeira.esperado!r}, obtido "
                f"{primeira.obtido!r}. Revisar 150 amostras sobre um dataset nesse "
                "estado gasta o tempo do revisor num arquivo que ainda vai mudar.")
    if not fila_completa:
        return ("NAO PRONTO: a fila de revisao nao fecha as 150 amostras do ADR 0002 "
                "secao 4. O kappa sairia de uma amostra menor que a acordada.")
    if avisos:
        return (f"PRONTO PARA REVISAO HUMANA, com {len(avisos)} aviso(s): "
                f"{', '.join(v.nome for v in avisos)}. Nenhum deles altera o que o "
                "revisor vai ver; ficam registrados como divida do esquema.")
    return ("PRONTO PARA REVISAO HUMANA: contagens por slice, isolamento da amostra "
            "sintetica, enum canonico e fila balanceada conferem. Isto NAO diz que os "
            "pre-rotulos estao certos — e exatamente isso que a revisao vai medir.")


# -------------------------------------------------------------------- saida


def preparar_terminal() -> None:
    """Forca UTF-8 na saida, com `replace` para caractere que o console nao tem.

    O relatorio imprime `id` e trecho de alt vindos do Commons, que trazem
    caractere fora da cp1252. Sem isto, a auditoria morreria de
    `UnicodeEncodeError` no meio da tabela — falhando por causa da propria
    invariante que o Eixo E confere.
    """
    for fluxo in (sys.stdout, sys.stderr):
        reconfigurar = getattr(fluxo, "reconfigure", None)
        if reconfigurar is not None:
            reconfigurar(encoding="utf-8", errors="replace")


def _linha_de_tabela(celulas: Sequence[str], larguras: Sequence[int]) -> str:
    preenchidas = (c.ljust(largura) for c, largura in zip(celulas, larguras,
                                                          strict=True))
    return "| " + " | ".join(preenchidas) + " |"


def formatar_tabela(relatorio: dict[str, Any]) -> str:
    """A tabela por slice, em Markdown que tambem le bem em terminal cru."""
    cabecalho = ("Slice/Origem", "Total", "Distribuicao (G/W/I)", "Papel no Treino",
                 "Invariante")
    falhas_por_slice = _falhas_por_slice(relatorio)

    linhas: list[tuple[str, ...]] = []
    for bloco in relatorio["slices"]:
        distribuicao = bloco["distribuicao_de_classe"]
        linhas.append((
            bloco["slice"],
            str(bloco["total"]),
            "/".join(str(distribuicao[r]) for r in ROTULOS),
            bloco["papel_no_treino"],
            FALHOU if bloco["slice"] in falhas_por_slice else PASSOU,
        ))
    linhas.append((
        "TOTAL", str(relatorio["total_de_linhas"]),
        "/".join(str(sum(b["distribuicao_de_classe"][r] for b in relatorio["slices"]))
                 for r in ROTULOS),
        "-", FALHOU if relatorio["falhas"] else PASSOU))

    larguras = [max(len(cabecalho[i]), max(len(linha[i]) for linha in linhas))
                for i in range(len(cabecalho))]
    separador = "|" + "|".join("-" * (largura + 2) for largura in larguras) + "|"
    corpo = "\n".join(_linha_de_tabela(linha, larguras) for linha in linhas)
    return "\n".join([_linha_de_tabela(cabecalho, larguras), separador, corpo])


def _falhas_por_slice(relatorio: dict[str, Any]) -> set[str]:
    """A que slice cada falha do Eixo A pertence, para marcar a linha da tabela."""
    marcados: set[str] = set()
    rotulo_do_eixo = {"A1": SLICE_GOVERNAMENTAL, "A2": SLICE_COMMONS,
                      "A3": SLICE_SINTETICO}
    for verificacao in relatorio["verificacoes"]:
        if verificacao["status"] != FALHOU:
            continue
        prefixo = verificacao["nome"][:2]
        if prefixo in rotulo_do_eixo:
            marcados.add(rotulo_do_eixo[prefixo])
    return marcados


def formatar_verificacoes(relatorio: dict[str, Any]) -> str:
    """Uma linha por invariante, agrupada por eixo."""
    linhas: list[str] = []
    eixo_atual = ""
    for verificacao in relatorio["verificacoes"]:
        if verificacao["eixo"] != eixo_atual:
            eixo_atual = verificacao["eixo"]
            linhas.append(f"\nEixo {eixo_atual}")
        marca = {PASSOU: "PASS", FALHOU: "FAIL", AVISO: "AVISO",
                 NAO_AVALIAVEL: "N/A"}[verificacao["status"]]
        linhas.append(f"  [{marca:5}] {verificacao['nome']}")
        if verificacao["status"] in (FALHOU, AVISO):
            linhas.append(f"           esperado: {verificacao['esperado']!r}")
            linhas.append(f"           obtido:   {verificacao['obtido']!r}")
            if verificacao["detalhe"]:
                linhas.append(f"           {verificacao['detalhe']}")
    return "\n".join(linhas)


def formatar_painel(relatorio: dict[str, Any]) -> str:
    """O painel final. Uma auditoria que termina sem veredito nao decidiu nada."""
    resumo = relatorio["resumo"]
    largura = 78
    conteudo = [
        "VEREDITO GERAL DE INTEGRIDADE",
        "",
        f"invariantes: {resumo['total']}   PASS: {resumo[PASSOU]}   "
        f"FAIL: {resumo[FALHOU]}   AVISO: {resumo[AVISO]}   "
        f"N/A: {resumo[NAO_AVALIAVEL]}",
        f"dataset:     {relatorio['dataset']}",
        f"sha256:      {relatorio['hash_do_dataset'][:32]}...",
        f"pronto para revisao humana: "
        f"{'SIM' if relatorio['pronto_para_revisao_humana'] else 'NAO'}",
    ]
    corpo: list[str] = []
    for bloco in conteudo:
        corpo.extend(_quebrar(bloco, largura - 4) or [""])
    for pedaco in _quebrar(relatorio["veredito"], largura - 4):
        corpo.append(pedaco)

    borda = "+" + "-" * (largura - 2) + "+"
    miolo = "\n".join(f"| {linha.ljust(largura - 4)} |" for linha in corpo)
    return f"{borda}\n{miolo}\n{borda}"


def _quebrar(texto: str, largura: int) -> list[str]:
    if not texto:
        return [""]
    # Linha que ja cabe volta intacta: quebrar por palavra colapsaria o
    # alinhamento por espaco das linhas de contagem do painel.
    if len(texto) <= largura:
        return [texto]
    linhas: list[str] = []
    atual = ""
    for palavra in texto.split():
        candidata = f"{atual} {palavra}".strip()
        if len(candidata) > largura and atual:
            linhas.append(atual)
            atual = palavra
        else:
            atual = candidata
    if atual:
        linhas.append(atual)
    return linhas


def gravar_relatorio(caminho: pathlib.Path, relatorio: dict[str, Any]) -> None:
    caminho.parent.mkdir(parents=True, exist_ok=True)
    caminho.write_text(json.dumps(relatorio, ensure_ascii=False, indent=2) + "\n",
                       encoding="utf-8")


# ---------------------------------------------------------------------- CLI


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(
        description="Auditoria de integridade e particionamento do dataset "
                    "(ADR 0002), antes da revisao humana.")
    analisador.add_argument("--dataset", type=pathlib.Path,
                            default=pathlib.Path("data") / "alt_texts.jsonl")
    analisador.add_argument("--relatorio", type=pathlib.Path,
                            default=pathlib.Path("data") / NOME_DO_RELATORIO)
    analisador.add_argument("--semente", type=int, default=42)
    analisador.add_argument("--C", dest="c", type=float, default=modelo.C_PADRAO)
    analisador.add_argument("--min-df", dest="min_df", type=int,
                            default=modelo.MIN_DF_PADRAO)
    analisador.add_argument("--pastas", type=int, default=validacao.PASTAS_PADRAO)
    analisador.add_argument("--sem-dinamico", action="store_true",
                            help="pula a validacao cruzada do Eixo B. O isolamento "
                                 "da sintetica deixa de ser conferido")
    analisador.add_argument("--total", type=int, default=Esperado.total)
    analisador.add_argument("--governamentais", type=int,
                            default=Esperado.governamentais)
    analisador.add_argument("--commons", type=int, default=Esperado.commons)
    analisador.add_argument("--sinteticas", type=int, default=Esperado.sinteticas)
    analisador.add_argument("--por-classe", type=int,
                            default=Esperado.por_classe_na_fila,
                            help="cota por classe da fila de revisao; o total da "
                                 "fila e essa cota vezes as tres classes")
    argumentos = analisador.parse_args(argv)
    preparar_terminal()

    esperado = Esperado(total=argumentos.total,
                        governamentais=argumentos.governamentais,
                        commons=argumentos.commons,
                        sinteticas=argumentos.sinteticas,
                        por_classe_na_fila=argumentos.por_classe,
                        fila=argumentos.por_classe * len(ROTULOS))

    if not argumentos.dataset.exists():
        print(f"dataset ausente em {argumentos.dataset}. Rode o coletor antes.",
              file=sys.stderr)
        return SAIDA_DIVERGENCIA

    try:
        relatorio = auditar(argumentos.dataset, esperado, semente=argumentos.semente,
                            c=argumentos.c, min_df=argumentos.min_df,
                            pastas=argumentos.pastas,
                            com_dinamico=not argumentos.sem_dinamico)
    except ValueError as erro:
        # JSONL corrompido nao e excecao inesperada aqui: e um dos achados que
        # esta auditoria existe para pegar, e vira EXIT=1 com a linha culpada.
        print(f"dataset ilegivel: {erro}", file=sys.stderr)
        return SAIDA_DIVERGENCIA

    gravar_relatorio(argumentos.relatorio, relatorio)

    print(formatar_tabela(relatorio))
    print(formatar_verificacoes(relatorio))
    print()
    print(formatar_painel(relatorio))
    print(f"\nrelatorio em {argumentos.relatorio.resolve()}")

    return SAIDA_DIVERGENCIA if relatorio["falhas"] else SAIDA_OK


if __name__ == "__main__":
    raise SystemExit(main())
