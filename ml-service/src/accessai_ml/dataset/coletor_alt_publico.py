"""Coleta texto alternativo publico do Wikimedia Commons.

    python -m accessai_ml.dataset.coletor_alt_publico --limite 1000

Existe para resolver o bloqueio registrado no ADR 0002: o corpus `.docx` tem
zero imagens com texto alternativo, e sem materia-prima nao ha o que rotular nem
o que treinar. O Commons e a fonte escolhida no ADR 0002 secao 3, com licenca
declarada por arquivo e API oficial — nada de raspar HTML.

**Duas fontes de texto, e a diferenca importa.**

1. `legenda` — a *caption* de dados estruturados do Commons (`entityterms.label`).
   E declaradamente uma linha curta descrevendo o arquivo: e o que mais se
   parece com alt text de verdade. Existe em poucos arquivos.
2. `descricao` — o campo `ImageDescription` do extmetadata. Existe em quase todo
   arquivo, mas e prosa de catalogo, com HTML dentro, mais longa que um alt
   tipico. Vem limpa daqui, e ainda assim o vies de comprimento e real.

O padrao e `ambos`, com a legenda preferida quando existe. Cada linha registra em
`origem_do_alt` de onde saiu, para que a mistura seja auditavel depois.

**Ressalva de procedencia que precisa sobreviver a este arquivo.** Isto e
alt-de-Commons aplicado a `.docx` de orgao publico brasileiro. O domain shift ja
esta declarado no ADR 0002 (treino em HTML, aplicacao em DOCX) e este coletor nao
o resolve — so o torna mensuravel.

**A pre-rotulagem NAO fecha o ADR 0002.** A secao 4 exige rotulagem hibrida
declarada: LLM pre-rotula, humano revisa, com taxa de correcao e kappa de Cohen
em 150 amostras. O que sai daqui e a primeira metade. Por isso o pre-rotulo vai
em `rotulo_provisorio` e `rotulo` fica nulo, a menos que alguem peca o contrario
por flag.
"""

from __future__ import annotations

import argparse
import dataclasses
import html
import json
import pathlib
import random
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterator
from datetime import UTC, datetime
from typing import Any

from . import divisao
from .montagem import VERSAO_DO_FORMATO

API = "https://commons.wikimedia.org/w/api.php"

FONTE = "wikimedia-commons"
LEGENDA = "legenda"
DESCRICAO = "descricao"
AMBOS = "ambos"
FONTES = (LEGENDA, DESCRICAO, AMBOS)

ORIGEM_LEGENDA = "legenda_commons"
ORIGEM_DESCRICAO = "descricao_commons"

# Politica de etiqueta da API da Wikimedia: User-Agent que identifique a
# ferramenta e de um caminho de contato. Agente generico e o que faz a fundacao
# bloquear o intervalo de IP inteiro, nao so este script.
#
# O padrao aponta para o repositorio, e nao para um e-mail: cabecalho HTTP fica
# em log de terceiro por tempo indeterminado, e endereco pessoal la dentro e
# decisao de quem roda, nao default de biblioteca. Para coleta longa, passe
# `--contato "https://... (voce@exemplo.org)"`, que e o que a politica prefere.
CONTATO_PADRAO = "https://github.com/accessai"

# `maxlag` faz a propria API recusar o pedido quando as replicas estao atrasadas,
# em vez de deixar o coletor piorar um incidente que ja esta acontecendo.
MAXLAG = 5

# Pausa entre pedidos. A etiqueta da Wikimedia pede acesso serial e sem rajada;
# na pratica, sem pausa a API comeca a devolver 429 depois de poucos pedidos
# seguidos e a coleta fica MAIS lenta por causa do recuo exponencial do que
# ficaria esperando de proposito.
PAUSA_PADRAO = 0.5

TENTATIVAS_PADRAO = 5
ESPERA_INICIAL = 1.0
ESPERA_MAXIMA = 60.0
TEMPO_LIMITE = 30.0

# Paginacao: 50 e o teto para cliente anonimo em `prop=imageinfo`.
LOTE = 50

# Sementes escolhidas por TAXA DE DESCRICAO medida, nao por tema. Categorias de
# concurso fotografico (Wiki Loves Monuments, por exemplo) tem dezenas de
# milhares de arquivos espalhados em milhares de subcategorias, e a expansao
# sozinha consome a coleta inteira antes da primeira amostra. As daqui deram
# ~95% de arquivos com legenda ou descricao aproveitavel em sondagem de 50.
CATEGORIAS_PADRAO = (
    "Category:Maps of Brazil",
    "Category:Government of Brazil",
    "Category:Featured pictures on Wikimedia Commons",
    "Category:Diagrams in Portuguese",
    "Category:Charts in Portuguese",
)

# Uma linha de progresso a cada N aceitas. Sem isso, uma categoria grande com
# poucos arquivos descritos fica minutos em silencio e parece travada.
PASSO_DO_PROGRESSO = 50

SAIDA_OK = 0
SAIDA_SEM_AMOSTRA = 4
SAIDA_SAIDA_EXISTENTE = 6

# ------------------------------------------------------------------- limpeza

_TAG_HTML = re.compile(r"<[^>]+>")
_CONTROLE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_ESPACOS = re.compile(r"\s+")
_ASPAS_DE_BORDA = "\"'“”‘’ "

# Nome de arquivo solto, com ou sem extensao, e extensao orfa. Vem de descricao
# preenchida por robo de upload em massa.
_NOME_DE_ARQUIVO = re.compile(
    r"^[\w\-. ()]*\.(jpe?g|png|gif|bmp|svg|webp|tiff?|pdf|ogv|webm)$", re.IGNORECASE)
_EXTENSAO_ORFA = re.compile(r"^\.\w{2,5}$")
_SO_RUIDO = re.compile(r"^[\W\d_]+$")
_TEM_LETRA = re.compile(r"[^\W\d_]")

# Descarte, nao rotulo. Sao strings que nao descrevem nada em NENHUM grau —
# manter uma delas como INSUFFICIENT encheria a classe rara de lixo de robo em
# vez de alt ruim escrito por gente, que e o que o modelo precisa reconhecer.
LIXO_LITERAL = frozenset({
    "ds_store", ".ds_store", "thumbs.db", "untitled", "sem titulo",
    "no description", "no description available", "n/a", "na", "none", "null",
    "nil", "unknown", "desconhecido", "sem descricao", "-", "--", "?", "...",
})

# -------------------------------------------------------------- pre-rotulagem

# Limiares da pre-rotulagem. Sao DIFERENTES dos de
# `training.modelo.BaselineHeuristico` (15/40) de proposito: se a pre-rotulagem
# usasse exatamente a heuristica que o modelo precisa superar, o baseline
# fecharia macro-F1 1.0 por construcao e o veredito do treino viraria teatro.
# Limiares diferentes NAO consertam a circularidade — so evitam o caso
# degenerado. A revisao humana do ADR 0002 secao 4 e o que conserta.
CURTO_DEMAIS = 10
GENERICO_ATE = 30

BOM = "GOOD"
FRACO = "WEAK"
INSUFICIENTE = "INSUFFICIENT"

TERMOS_BLOQUEADOS = frozenset({
    "imagem", "image", "img", "foto", "photo", "figura", "figure", "fig",
    "logo", "logotipo", "banner", "icone", "icon", "picture", "pic",
    "clique aqui", "click here", "saiba mais", "leia mais", "veja mais",
    "screenshot", "captura de tela", "arquivo", "file", "documento", "de", "da",
    "do", "of", "the", "a", "o",
})

# `GOOD` exige estrutura, nao so comprimento: quatro tokens, ao menos um com
# quatro letras. Sem isso, "AAAA BBBB CCCC DDDD EEEE FFFF" entraria como
# descricao rica so por passar de 30 caracteres.
MINIMO_DE_TOKENS_PARA_BOM = 4
MINIMO_DE_LETRAS_NO_TOKEN = 4


class ColetaError(Exception):
    """A coleta nao pode continuar, e a razao esta na mensagem."""


@dataclasses.dataclass(frozen=True)
class Bruto:
    """Um arquivo do Commons, antes de virar amostra."""

    pageid: int
    titulo: str
    legenda: str | None
    descricao: str | None
    licenca: str
    url: str
    categoria_semente: str


# ------------------------------------------------------------------- cliente


class ClienteCommons:
    """Fala com a Action API com retentativa e recuo exponencial.

    A retentativa cobre 429, 5xx e falha de rede — as tres coisas que uma API
    publica faz num dia normal e que nao sao erro de programa. 4xx que nao seja
    429 NAO e retentado: pedido malformado nao melhora sozinho, e insistir nele e
    exatamente o comportamento que a Wikimedia bloqueia.
    """

    def __init__(self, contato: str, tentativas: int = TENTATIVAS_PADRAO,
                 tempo_limite: float = TEMPO_LIMITE, pausa: float = PAUSA_PADRAO,
                 semente: int | None = None) -> None:
        self.agente = f"AccessAI-coletor/0.1 ({contato}) Python-urllib"
        self.tentativas = tentativas
        self.tempo_limite = tempo_limite
        self.pausa = pausa
        self._aleatorio = random.Random(semente)
        self._ultimo_pedido = 0.0
        self.pedidos = 0
        self.retentativas = 0

    def _esperar_a_vez(self) -> None:
        restante = self.pausa - (time.monotonic() - self._ultimo_pedido)
        if restante > 0:
            time.sleep(restante)
        self._ultimo_pedido = time.monotonic()

    def consultar(self, parametros: dict[str, str]) -> dict[str, Any]:
        completos = dict(parametros)
        completos.update({"action": "query", "format": "json",
                          "formatversion": "2", "maxlag": str(MAXLAG)})
        url = f"{API}?{urllib.parse.urlencode(completos)}"
        pedido = urllib.request.Request(url, headers={
            "User-Agent": self.agente,
            "Accept": "application/json",
        })

        espera = ESPERA_INICIAL
        ultimo: Exception | None = None

        for tentativa in range(1, self.tentativas + 1):
            try:
                self._esperar_a_vez()
                self.pedidos += 1
                with urllib.request.urlopen(
                        pedido, timeout=self.tempo_limite) as resposta:
                    corpo: dict[str, Any] = json.loads(resposta.read().decode("utf-8"))
            except urllib.error.HTTPError as http:
                if http.code != 429 and http.code < 500:
                    raise ColetaError(f"HTTP {http.code} em {url}") from http
                ultimo = http
                espera = self._recuar(espera, http.headers.get("Retry-After"),
                                      tentativa, f"HTTP {http.code}")
                continue
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as erro:
                ultimo = erro
                espera = self._recuar(espera, None, tentativa,
                                      f"{type(erro).__name__}: {erro}")
                continue

            # `maxlag` e cota estourada chegam como HTTP 200 com `error` no
            # corpo. Olhar so o codigo HTTP deixaria o coletor martelando a API
            # achando que esta tudo bem.
            erro_da_api = corpo.get("error", {})
            if erro_da_api.get("code") in ("maxlag", "ratelimited"):
                ultimo = ColetaError(f"API pediu espera: {erro_da_api.get('info')}")
                espera = self._recuar(espera, None, tentativa,
                                      str(erro_da_api.get("code")))
                continue
            if erro_da_api:
                raise ColetaError(
                    f"API recusou: {erro_da_api.get('code')} — {erro_da_api.get('info')}")
            return corpo

        raise ColetaError(
            f"desisti depois de {self.tentativas} tentativas: {ultimo}") from ultimo

    def _recuar(self, espera: float, retry_after: str | None, tentativa: int,
                motivo: str) -> float:
        """Dorme e devolve a proxima espera. `Retry-After` manda quando existe."""
        self.retentativas += 1
        if retry_after and retry_after.strip().isdigit():
            atraso = min(float(retry_after.strip()), ESPERA_MAXIMA)
        else:
            # Jitter para que varias execucoes simultaneas nao voltem juntas e
            # reproduzam o pico que causou o 429.
            atraso = min(espera, ESPERA_MAXIMA) * (0.5 + self._aleatorio.random())
        # O motivo vai junto: "aguardando 2s" sozinho nao diz se a coleta bateu
        # em cota, em replica atrasada ou em rede caindo — tres problemas com
        # respostas operacionais diferentes.
        print(f"  aguardando {atraso:.1f}s (tentativa {tentativa}): {motivo}",
              file=sys.stderr)
        time.sleep(atraso)
        return min(espera * 2, ESPERA_MAXIMA)

    def paginar(self, parametros: dict[str, str]) -> Iterator[dict[str, Any]]:
        """Percorre `continue` ate a API parar de oferecer continuacao."""
        continuacao: dict[str, str] = {}
        vistos: set[str] = set()
        while True:
            corpo = self.consultar({**parametros, **continuacao})
            yield corpo.get("query", {})
            adiante = corpo.get("continue")
            if not adiante:
                return
            marca = json.dumps(adiante, sort_keys=True)
            # Trava contra loop: uma API que devolvesse o mesmo `continue` faria
            # o coletor girar para sempre baixando a mesma pagina.
            if marca in vistos:
                return
            vistos.add(marca)
            continuacao = {chave: str(valor) for chave, valor in adiante.items()}


# -------------------------------------------------------------------- coleta


def _subcategorias(cliente: ClienteCommons, categoria: str) -> list[str]:
    achadas: list[str] = []
    for pagina in cliente.paginar({
        "list": "categorymembers", "cmtitle": categoria,
        "cmtype": "subcat", "cmlimit": str(LOTE),
    }):
        achadas += [membro["title"] for membro in pagina.get("categorymembers", [])]
    return achadas


def expandir_categorias(cliente: ClienteCommons, sementes: list[str],
                        profundidade: int) -> list[str]:
    """Desce `profundidade` niveis de subcategoria, em largura.

    Em largura, e nao em profundidade: uma unica cadeia de subcategorias do
    Commons desce dezenas de niveis, e o coletor terminaria com mil fotos de um
    assunto so — o oposto da variedade que o dataset precisa.
    """
    nivel = list(dict.fromkeys(sementes))
    todas = list(nivel)
    for _ in range(max(profundidade, 0)):
        proximo: list[str] = []
        for categoria in nivel:
            for filha in _subcategorias(cliente, categoria):
                if filha not in todas:
                    todas.append(filha)
                    proximo.append(filha)
        if not proximo:
            break
        nivel = proximo
    return todas


def _legenda_de(pagina: dict[str, Any]) -> str | None:
    rotulos = pagina.get("entityterms", {}).get("label")
    return str(rotulos[0]) if rotulos else None


def buscar_arquivos(cliente: ClienteCommons, categoria: str,
                    idioma: str) -> Iterator[Bruto]:
    """Um `Bruto` por arquivo da categoria, com legenda, descricao e licenca."""
    for pagina in cliente.paginar({
        "generator": "categorymembers", "gcmtitle": categoria,
        "gcmtype": "file", "gcmlimit": str(LOTE),
        "prop": "imageinfo|entityterms",
        "iiprop": "extmetadata|url",
        "iiextmetadatafilter": "ImageDescription|LicenseShortName",
        "wbetterms": "label", "uselang": idioma,
    }):
        for arquivo in pagina.get("pages", []):
            informacoes = (arquivo.get("imageinfo") or [{}])[0]
            extra = informacoes.get("extmetadata", {})
            yield Bruto(
                pageid=int(arquivo.get("pageid", 0)),
                titulo=str(arquivo.get("title", "")),
                legenda=_legenda_de(arquivo),
                descricao=(extra.get("ImageDescription") or {}).get("value"),
                # Licenca ausente vira "nao declarada" e NAO e descartada aqui:
                # descarte por licenca e decisao de curadoria, e sumiria se
                # ficasse escondido dentro do coletor. Fica no campo, visivel.
                licenca=str((extra.get("LicenseShortName") or {}).get(
                    "value", "nao declarada")),
                url=str(informacoes.get("descriptionurl", "")),
                categoria_semente=categoria,
            )


# ------------------------------------------------------------------- limpeza


def limpar(bruto: str | None) -> str | None:
    """Devolve o texto utilizavel, ou `None` quando nao ha texto utilizavel.

    Ordem importa: desescapar ANTES de tirar tag, senao `&lt;p&gt;` sobrevive
    como texto literal; tirar caractere de controle DEPOIS de desescapar, porque
    a entidade pode carregar o proprio controle.
    """
    if bruto is None:
        return None

    texto = html.unescape(str(bruto))
    texto = _TAG_HTML.sub(" ", texto)
    # Segunda passada: descricao do Commons costuma vir com escape duplo, e
    # `&amp;lt;span&amp;gt;` so vira tag depois do segundo unescape.
    texto = _TAG_HTML.sub(" ", html.unescape(texto))
    texto = unicodedata.normalize("NFC", texto)
    texto = _CONTROLE.sub(" ", texto).replace(" ", " ")
    texto = _ESPACOS.sub(" ", texto).strip().strip(_ASPAS_DE_BORDA).strip()

    if not texto:
        return None
    if texto.lower() in LIXO_LITERAL:
        return None
    if _NOME_DE_ARQUIVO.match(texto) or _EXTENSAO_ORFA.match(texto):
        return None
    if _SO_RUIDO.match(texto) or not _TEM_LETRA.search(texto):
        return None
    return texto


# -------------------------------------------------------------- pre-rotulagem


def _tokens(texto: str) -> list[str]:
    return [pedaco for pedaco in re.split(r"\W+", texto, flags=re.UNICODE) if pedaco]


def _tem_estrutura(texto: str) -> bool:
    tokens = _tokens(texto)
    if len(tokens) < MINIMO_DE_TOKENS_PARA_BOM:
        return False
    return any(len(token) >= MINIMO_DE_LETRAS_NO_TOKEN and _TEM_LETRA.search(token)
               for token in tokens)


def _repete_palavra(texto: str) -> bool:
    tokens = [token.lower() for token in _tokens(texto) if len(token) > 3]
    return len(tokens) >= 2 and len(set(tokens)) < len(tokens)


def pre_rotular(texto: str) -> str:
    """Pre-classificacao deterministica. NAO e rotulo revisado — ver o cabecalho."""
    normalizado = divisao.normalizar_alt(texto)
    tokens = set(_tokens(normalizado))

    if len(texto) < CURTO_DEMAIS:
        return INSUFICIENTE
    # `tokens <= bloqueados`, e nao "contem bloqueado": "foto da fachada do
    # predio sede" contem "foto" e mesmo assim descreve. So e insuficiente
    # quando NAO SOBRA NADA alem dos termos vazios.
    if tokens and tokens <= TERMOS_BLOQUEADOS:
        return INSUFICIENTE
    if normalizado in TERMOS_BLOQUEADOS:
        return INSUFICIENTE
    if len(texto) <= GENERICO_ATE or _repete_palavra(texto):
        return FRACO
    if not _tem_estrutura(texto):
        return FRACO
    return BOM


# ------------------------------------------------------------------ montagem


def _agora() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def montar_linhas(amostras: list[tuple[Bruto, str, str]],
                  rotular: bool) -> list[dict[str, Any]]:
    """Converte para o esquema do JSONL que `training.dados.carregar` le.

    A divisao e calculada com TODAS as chaves de uma vez, e nao amostra a
    amostra: e o que garante que dois alts identicos caiam do mesmo lado.
    """
    chaves = {divisao.chave_de_agrupamento(texto, str(bruto.pageid))
              for bruto, texto, _ in amostras}
    particao = divisao.dividir(chaves)

    linhas: list[dict[str, Any]] = []
    for bruto, texto, origem in amostras:
        grupo = divisao.chave_de_agrupamento(texto, str(bruto.pageid))
        provisorio = pre_rotular(texto)
        linhas.append({
            "versao_do_formato": VERSAO_DO_FORMATO,
            "fonte": FONTE,
            "id": f"commons:{bruto.pageid}",
            "arquivo": bruto.titulo,
            "sha256_documento": None,
            "parte_pacote": None,
            "nome_imagem": bruto.titulo.removeprefix("File:"),
            "alt": texto,
            "origem_do_alt": origem,
            "tem_alt": True,
            # O Commons nao entrega o texto em volta da imagem na pagina que a
            # usa. Vazio e honesto; preencher com a descricao inventaria
            # contexto que ninguem coletou.
            "contexto_antes": "",
            "contexto_depois": "",
            "grupo": grupo,
            "divisao": particao.parte_de(grupo),
            "orgao": "Wikimedia Commons",
            "esfera": "acervo publico",
            "categoria": bruto.categoria_semente,
            "licenca": bruto.licenca,
            "url": bruto.url,
            # Pre-rotulo e rotulo viajam em campos SEPARADOS. `rotulo` so e
            # preenchido sob `--rotular-com-heuristica`, e a origem fica gravada
            # na linha: dataset que nao diz quem rotulou nao se defende depois.
            "rotulo_provisorio": provisorio,
            "origem_do_rotulo": "heuristica" if rotular else None,
            "rotulo": provisorio if rotular else None,
        })
    return linhas


def _distribuicao(linhas: list[dict[str, Any]], chave: str) -> dict[str, int]:
    contagem: dict[str, int] = {}
    for linha in linhas:
        valor = str(linha[chave])
        contagem[valor] = contagem.get(valor, 0) + 1
    return dict(sorted(contagem.items()))


def montar_relatorio(linhas: list[dict[str, Any]], descartes: dict[str, int],
                     cliente: ClienteCommons, categorias: list[str],
                     rotulado: bool) -> dict[str, Any]:
    return {
        "versao_do_formato": VERSAO_DO_FORMATO,
        "fonte": FONTE,
        "gerado_em": _agora(),
        "categorias_visitadas": len(categorias),
        "pedidos_a_api": cliente.pedidos,
        "retentativas": cliente.retentativas,
        "amostras": len(linhas),
        # Alt repetido nao e amostra nova; a diferenca entre os dois numeros e o
        # tamanho da duplicacao que a divisao por grupo esta segurando.
        "alts_distintos": len({linha["grupo"] for linha in linhas}),
        "descartes": descartes,
        "por_origem_do_alt": _distribuicao(linhas, "origem_do_alt"),
        "por_divisao": _distribuicao(linhas, "divisao"),
        "por_licenca": _distribuicao(linhas, "licenca"),
        "pre_rotulos": _distribuicao(linhas, "rotulo_provisorio"),
        "rotulo_preenchido": rotulado,
        "ressalva": (
            "Pre-rotulo HEURISTICO. O ADR 0002 secao 4 exige rotulagem hibrida "
            "declarada — LLM pre-rotula, humano revisa — com taxa de correcao e "
            "kappa de Cohen em 150 amostras. Enquanto essa revisao nao acontecer, "
            "metrica treinada sobre estes rotulos mede concordancia com uma "
            "regra, nao qualidade de alt text."),
    }


def coletar(cliente: ClienteCommons, categorias: list[str], fonte: str,
            idioma: str, limite: int) -> tuple[list[tuple[Bruto, str, str]],
                                               dict[str, int]]:
    """Percorre as categorias ate juntar `limite` amostras distintas."""
    aceitas: list[tuple[Bruto, str, str]] = []
    grupos_vistos: set[str] = set()
    descartes = {"sem_texto": 0, "limpeza": 0, "duplicado": 0}

    for categoria in categorias:
        if len(aceitas) >= limite:
            break
        print(f"[{len(aceitas)}/{limite}] {categoria}", file=sys.stderr)
        for bruto in buscar_arquivos(cliente, categoria, idioma):
            if len(aceitas) >= limite:
                break

            candidatos: list[tuple[str | None, str]] = []
            if fonte in (LEGENDA, AMBOS):
                candidatos.append((bruto.legenda, ORIGEM_LEGENDA))
            if fonte in (DESCRICAO, AMBOS):
                candidatos.append((bruto.descricao, ORIGEM_DESCRICAO))

            if not any(texto for texto, _ in candidatos):
                descartes["sem_texto"] += 1
                continue

            # Primeiro candidato que sobrevive a limpeza vence. Com `ambos`, a
            # legenda vem antes: e o texto mais parecido com alt de verdade.
            for candidato, origem in candidatos:
                texto = limpar(candidato)
                if texto is None:
                    continue
                grupo = divisao.chave_de_agrupamento(texto, str(bruto.pageid))
                if grupo in grupos_vistos:
                    descartes["duplicado"] += 1
                    break
                grupos_vistos.add(grupo)
                aceitas.append((bruto, texto, origem))
                if len(aceitas) % PASSO_DO_PROGRESSO == 0:
                    print(f"  {len(aceitas)}/{limite} aceitas "
                          f"({cliente.pedidos} pedidos, {descartes} descartadas)",
                          file=sys.stderr)
                break
            else:
                descartes["limpeza"] += 1

    return aceitas, descartes


# ----------------------------------------------------------------------- CLI


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(
        description="Coleta alt text publico do Wikimedia Commons para o Modelo 1.")
    analisador.add_argument("--saida", type=pathlib.Path,
                            default=pathlib.Path("data") / "alt_texts.jsonl")
    analisador.add_argument("--relatorio", type=pathlib.Path,
                            default=pathlib.Path("data") / "relatorio_coleta.json")
    analisador.add_argument("--categoria", action="append", dest="categorias",
                            metavar="Category:...",
                            help="repetivel; sem isto usa as categorias padrao")
    # Padrao 0: uma unica descida em categoria grande do Commons pode render
    # milhares de subcategorias, e a expansao termina custando mais tempo que a
    # coleta. Quem precisa de variedade sobe para 1 sabendo do custo.
    analisador.add_argument("--profundidade", type=int, default=0,
                            help="niveis de subcategoria a descer (0 = so as sementes)")
    analisador.add_argument("--fonte", choices=FONTES, default=AMBOS)
    analisador.add_argument("--idioma", default="pt")
    analisador.add_argument("--limite", type=int, default=1000,
                            help="maximo de amostras distintas")
    analisador.add_argument("--contato", default=CONTATO_PADRAO,
                            help="vai no User-Agent, como a politica da Wikimedia exige")
    analisador.add_argument("--tentativas", type=int, default=TENTATIVAS_PADRAO)
    analisador.add_argument("--pausa", type=float, default=PAUSA_PADRAO,
                            help="segundos entre pedidos, por etiqueta da API")
    analisador.add_argument("--semente", type=int, default=42,
                            help="semente do jitter do recuo exponencial")
    analisador.add_argument("--sobrescrever", action="store_true",
                            help="permite substituir um dataset que ja existe")
    analisador.add_argument(
        "--rotular-com-heuristica", action="store_true", dest="rotular",
        help="preenche `rotulo` com o pre-rotulo. SEM revisao humana, o treino "
             "passa a medir concordancia com uma regra (ADR 0002 secao 4)")
    argumentos = analisador.parse_args(argv)

    # Guarda contra apagar o dataset do corpus `.docx` sem querer: ele e a unica
    # prova de que aquele corpus tem zero alt, e nao da para regerar sem os
    # documentos originais.
    if argumentos.saida.exists() and not argumentos.sobrescrever:
        print(f"{argumentos.saida} ja existe. Use --sobrescrever para substituir.",
              file=sys.stderr)
        return SAIDA_SAIDA_EXISTENTE

    cliente = ClienteCommons(contato=argumentos.contato,
                             tentativas=argumentos.tentativas,
                             pausa=argumentos.pausa,
                             semente=argumentos.semente)
    sementes = list(argumentos.categorias or CATEGORIAS_PADRAO)

    try:
        categorias = expandir_categorias(cliente, sementes, argumentos.profundidade)
        amostras, descartes = coletar(cliente, categorias, argumentos.fonte,
                                      argumentos.idioma, argumentos.limite)
    except ColetaError as erro:
        print(f"coleta interrompida: {erro}", file=sys.stderr)
        return SAIDA_SEM_AMOSTRA

    linhas = montar_linhas(amostras, rotular=argumentos.rotular)
    relatorio = montar_relatorio(linhas, descartes, cliente, categorias,
                                 rotulado=argumentos.rotular)

    if not linhas:
        print(json.dumps(relatorio, ensure_ascii=False, indent=2))
        print("nenhuma amostra sobreviveu aos filtros; nada foi escrito",
              file=sys.stderr)
        return SAIDA_SEM_AMOSTRA

    argumentos.saida.parent.mkdir(parents=True, exist_ok=True)
    with argumentos.saida.open("w", encoding="utf-8", newline="\n") as arquivo:
        for linha in linhas:
            arquivo.write(json.dumps(linha, ensure_ascii=False) + "\n")
    argumentos.relatorio.parent.mkdir(parents=True, exist_ok=True)
    argumentos.relatorio.write_text(
        json.dumps(relatorio, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps(relatorio, ensure_ascii=False, indent=2))
    print(f"\n{len(linhas)} amostras em {argumentos.saida.resolve()}")
    if not argumentos.rotular:
        print("`rotulo` esta nulo em todas as linhas: o pre-rotulo ficou em "
              "`rotulo_provisorio`. `train.py` vai recusar este arquivo ate "
              "alguem revisar, ou ate --rotular-com-heuristica ser usado.",
              file=sys.stderr)
    return SAIDA_OK


if __name__ == "__main__":
    raise SystemExit(main())
