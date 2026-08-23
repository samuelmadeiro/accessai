"""Coleta `alt` de HTML publico, mirando a classe `INSUFFICIENT`.

    python -m accessai_ml.dataset.coletor_web --url https://exemplo.org/pagina

Existe porque `INSUFFICIENT` e rara em acervo curado: a coleta do Commons trouxe
6 amostras dessa classe em 900. Descricao de catalogo e escrita por quem se
importa com o catalogo; o alt ruim mora em HTML comum, onde ninguem revisou.

**A polaridade do filtro e o oposto da do `coletor_alt_publico`.** La, nome de
arquivo e ruido a descartar; aqui, nome de arquivo E a amostra. Os dois modulos
nao compartilham o filtro de propósito — juntar os dois numa funcao com um
booleano de inversao deixaria uma mudanca em um lado alterando o outro em
silencio.

**Etiqueta de robo.** `robots.txt` e consultado por host, antes da primeira
pagina, e host que proibe e pulado sem tentativa. Nao ha flag para ignorar:
coletor com botao de desligar a etiqueta e coletor que vai ser usado com o botao
desligado. Alem disso: `User-Agent` identificando a ferramenta, pausa entre
pedidos, recuo exponencial em 429 e 5xx, e tempo limite em toda leitura.

**O que este modulo NAO faz.** Nao segue link, nao desce em profundidade, nao
descobre pagina sozinho. Recebe as URLs que o operador informou e le exatamente
essas. Rastejador que descobre a proxima pagina sozinho e como um coletor de
amostra vira carga em servidor de terceiro sem ninguem perceber.
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
import urllib.robotparser
from datetime import UTC, datetime
from html.parser import HTMLParser
from typing import Any

from . import divisao, gerador_insufficient
from .montagem import VERSAO_DO_FORMATO

FONTE_WEB = "web-html"
ORIGEM_WEB = "atributo_alt_html"
ORIGEM_COLETADA = "coletado"
ORIGEM_ALT_SINTETICO = "gerado"

INSUFICIENTE = "INSUFFICIENT"

CONTATO_PADRAO = "https://github.com/accessai"
AGENTE = "AccessAI-coletor-web/0.1"

PAUSA_PADRAO = 1.0
TENTATIVAS_PADRAO = 3
TEMPO_LIMITE = 15.0
ESPERA_INICIAL = 1.0
ESPERA_MAXIMA = 30.0

# Teto de corpo lido. Sem ele, um `Content-Length` mentiroso ou um endpoint que
# transmite para sempre enche a memoria do processo — e o coletor nao tem nada a
# ganhar lendo 200 MB de uma pagina HTML.
MAXIMO_DE_BYTES = 5 * 1024 * 1024

# Cota da classe rara. 50 e o `--por-classe` que a revisao humana pede
# (`revisao.POR_CLASSE_PADRAO`), e e por isso que este numero existe.
COTA_PADRAO = 50

SAIDA_OK = 0
SAIDA_SEM_URL = 2
SAIDA_DATASET_INVALIDO = 3
SAIDA_COTA_NAO_ATINGIDA = 4

# ------------------------------------------------------ filtro de INSUFFICIENT

_EXTENSOES = r"(?:jpe?g|png|gif|bmp|svg|webp|tiff?|ico|avif|psd)"
_NOME_DE_ARQUIVO = re.compile(rf"^[\w\-. ()\[\]]+\.{_EXTENSOES}$", re.IGNORECASE)
# Hash puro, com ou sem extensao: 8+ digitos hexadecimais e nada mais.
_HASH = re.compile(rf"^[0-9a-f]{{8,}}(?:\.{_EXTENSOES})?$", re.IGNORECASE)
_SO_RUIDO = re.compile(r"^[\W\d_]+$")
_TEM_LETRA = re.compile(r"[^\W\d_]")
_TAG = re.compile(r"<[^>]+>")
_CONTROLE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_ESPACOS = re.compile(r"\s+")
_CHARSET = re.compile(rb"""charset=["']?([\w\-]+)""", re.IGNORECASE)

# Palavra que nomeia a categoria da coisa em vez de descrever a coisa. So conta
# quando ela esta SOZINHA: "banner de divulgacao do edital de 2025" descreve.
TERMOS_SOLTOS = frozenset({
    "imagem", "imagens", "image", "images", "img", "foto", "fotos", "photo",
    "figura", "figure", "fig", "logo", "logotipo", "banner", "icone", "ícone",
    "icon", "picture", "pic", "thumbnail", "thumb", "avatar", "ilustracao",
    "ilustração", "grafico", "gráfico", "capa", "arte", "midia", "mídia",
    "alt", "placeholder", "spacer", "sem titulo", "sem título", "untitled",
    "texto alternativo", "descricao da imagem", "descrição da imagem",
    # Texto de template que ninguem trocou. Passa dos 6 caracteres, entao o
    # limiar de comprimento nao pega — e sem estes dois o gerador produz
    # amostras que o proprio filtro nao reconheceria.
    "alt text", "lorem ipsum", "insira a descricao", "insira a descrição",
})

# Abaixo disso nao ha descricao possivel. Deliberadamente mais curto que os 10
# de `coletor_alt_publico.CURTO_DEMAIS`: la o comprimento decide entre classes;
# aqui ele e criterio de INCLUSAO, e um limiar frouxo encheria a cota de `WEAK`
# disfarcado de `INSUFFICIENT`.
CURTO_DEMAIS = 6

MOTIVO_NOME = "nome_de_arquivo"
MOTIVO_HASH = "hash"
MOTIVO_GENERICO = "termo_generico_solto"
MOTIVO_RUIDO = "sem_letra"
MOTIVO_CURTO = "curto_demais"


class ColetaWebError(Exception):
    """A coleta nao pode continuar, e a razao esta na mensagem."""


@dataclasses.dataclass(frozen=True)
class AmostraWeb:
    """Um `alt` extraido de uma pagina, com de onde veio e por que foi aceito."""

    alt: str
    url: str
    motivo: str
    indice_na_pagina: int


# ------------------------------------------------------------------ extracao


class ExtratorDeAlt(HTMLParser):
    """Junta o atributo `alt` de cada `<img>`, na ordem em que aparecem.

    `HTMLParser` da stdlib, e nao regex sobre o HTML: `alt` costuma conter aspas
    escapadas, entidades e `>` dentro do valor, e um regex que "quase funciona"
    entrega alt truncado sem nenhum sinal de que truncou.

    `<img>` sem `alt` NAO entra. Alt ausente e deteccao deterministica do Rule
    Engine (CONTRIBUTING.md secao 2), nao amostra de ML — mas e contado, porque a
    razao entre com e sem alt e o que diz se a pagina vale a pena.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.alts: list[str] = []
        self.sem_alt = 0
        self.total_de_imagens = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() != "img":
            return
        self.total_de_imagens += 1
        atributos = {nome.lower(): valor for nome, valor in attrs}
        if "alt" not in atributos:
            self.sem_alt += 1
            return
        # `alt=""` e declaracao de imagem decorativa, permitida pelo WCAG 1.1.1.
        # Contar como "sem alt" seria acusar quem seguiu a norma.
        self.alts.append(atributos["alt"] or "")

    def error(self, message: str) -> None:  # pragma: no cover - API antiga
        """`HTMLParser` legado exige o metodo; a versao tolerante nunca o chama."""


def extrair_alts(documento: str) -> ExtratorDeAlt:
    """Roda o parser sobre o HTML. HTML quebrado degrada, nao levanta."""
    extrator = ExtratorDeAlt()
    try:
        extrator.feed(documento)
        extrator.close()
    except Exception:  # noqa: BLE001 - pagina malformada nao derruba a coleta
        # Uma pagina com HTML impossivel nao pode custar a coleta inteira. O que
        # ja foi extraido antes do ponto quebrado continua valendo.
        pass
    return extrator


# ------------------------------------------------------------------- limpeza


def limpar(bruto: str | None) -> str | None:
    """Normaliza o valor do atributo. `None` quando nao sobra texto.

    Alt vazio some aqui de propósito: e imagem decorativa declarada, nao amostra.
    """
    if bruto is None:
        return None
    texto = html.unescape(str(bruto))
    texto = _TAG.sub(" ", texto)
    texto = unicodedata.normalize("NFC", texto)
    texto = _CONTROLE.sub(" ", texto).replace(" ", " ")
    texto = _ESPACOS.sub(" ", texto).strip()
    return texto or None


def parece_insuficiente(alt: str) -> str | None:
    """O motivo pelo qual este alt e `INSUFFICIENT`, ou `None` se nao parece.

    Devolve o MOTIVO em vez de um booleano: o relatorio precisa dizer de que
    padrao a cota foi preenchida. Cinquenta amostras todas de nome de arquivo
    ensinam "termina em .jpg", nao "nao descreve" — e so da para ver isso se o
    motivo viajar junto.
    """
    texto = alt.strip()
    if not texto:
        return None
    if _HASH.match(texto):
        return MOTIVO_HASH
    if _NOME_DE_ARQUIVO.match(texto):
        return MOTIVO_NOME
    if _SO_RUIDO.match(texto) or not _TEM_LETRA.search(texto):
        return MOTIVO_RUIDO
    if divisao.normalizar_alt(texto) in TERMOS_SOLTOS:
        return MOTIVO_GENERICO
    if len(texto) < CURTO_DEMAIS:
        return MOTIVO_CURTO
    return None


# -------------------------------------------------------------------- rede


class ClienteWeb:
    """Baixa paginas com etiqueta: robots.txt, pausa, recuo e tempo limite."""

    def __init__(self, contato: str = CONTATO_PADRAO,
                 tentativas: int = TENTATIVAS_PADRAO, pausa: float = PAUSA_PADRAO,
                 tempo_limite: float = TEMPO_LIMITE,
                 semente: int | None = None) -> None:
        self.agente = f"{AGENTE} ({contato}) Python-urllib"
        self.tentativas = tentativas
        self.pausa = pausa
        self.tempo_limite = tempo_limite
        self._aleatorio = random.Random(semente)
        self._robots: dict[str, urllib.robotparser.RobotFileParser | None] = {}
        self._ultimo_pedido = 0.0
        self.pedidos = 0
        self.retentativas = 0

    # ------------------------------------------------------------- robots

    def permitido(self, url: str) -> bool:
        """Consulta o `robots.txt` do host, uma vez por host.

        `robots.txt` inacessivel e tratado como PERMITIDO, que e o que a norma
        de fato diz: ausencia de arquivo nao e proibicao. O que nao pode e
        tratar timeout como permissao silenciosa — por isso a decisao e
        registrada e reutilizada, em vez de repetida a cada pagina.
        """
        partes = urllib.parse.urlsplit(url)
        if partes.scheme not in ("http", "https") or not partes.netloc:
            return False
        host = f"{partes.scheme}://{partes.netloc}"

        if host not in self._robots:
            leitor = urllib.robotparser.RobotFileParser()
            leitor.set_url(f"{host}/robots.txt")
            try:
                leitor.read()
                self._robots[host] = leitor
            except Exception:  # noqa: BLE001 - sem robots.txt legivel, segue
                self._robots[host] = None

        leitor_do_host = self._robots[host]
        if leitor_do_host is None:
            return True
        return bool(leitor_do_host.can_fetch(self.agente, url))

    # -------------------------------------------------------------- baixa

    def _esperar_a_vez(self) -> None:
        restante = self.pausa - (time.monotonic() - self._ultimo_pedido)
        if restante > 0:
            time.sleep(restante)
        self._ultimo_pedido = time.monotonic()

    def _recuar(self, espera: float, retry_after: str | None, tentativa: int,
                motivo: str) -> float:
        self.retentativas += 1
        if retry_after and retry_after.strip().isdigit():
            atraso = min(float(retry_after.strip()), ESPERA_MAXIMA)
        else:
            atraso = min(espera, ESPERA_MAXIMA) * (0.5 + self._aleatorio.random())
        print(f"  aguardando {atraso:.1f}s (tentativa {tentativa}): {motivo}",
              file=sys.stderr)
        time.sleep(atraso)
        return min(espera * 2, ESPERA_MAXIMA)

    def baixar(self, url: str) -> str:
        """Devolve o HTML decodificado. Levanta `ColetaWebError` ao desistir."""
        pedido = urllib.request.Request(url, headers={
            "User-Agent": self.agente,
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "pt-BR,pt;q=0.9,en;q=0.8",
        })
        espera = ESPERA_INICIAL
        ultimo: Exception | None = None

        for tentativa in range(1, self.tentativas + 1):
            try:
                self._esperar_a_vez()
                self.pedidos += 1
                with urllib.request.urlopen(
                        pedido, timeout=self.tempo_limite) as resposta:
                    bruto = resposta.read(MAXIMO_DE_BYTES)
                    cabecalhos = resposta.headers
                return _decodificar(bruto, cabecalhos.get("Content-Type"))
            except urllib.error.HTTPError as http:
                if http.code != 429 and http.code < 500:
                    raise ColetaWebError(f"HTTP {http.code} em {url}") from http
                ultimo = http
                espera = self._recuar(espera, http.headers.get("Retry-After"),
                                      tentativa, f"HTTP {http.code}")
            except (urllib.error.URLError, TimeoutError, OSError) as erro:
                ultimo = erro
                espera = self._recuar(espera, None, tentativa,
                                      f"{type(erro).__name__}: {erro}")

        raise ColetaWebError(f"{url}: desisti depois de {self.tentativas} "
                             f"tentativas ({ultimo})")


def _decodificar(bruto: bytes, content_type: str | None) -> str:
    """Bytes para texto, sempre terminando em str utilizavel.

    A ordem — cabecalho, depois `<meta charset>`, depois UTF-8 — segue a que o
    navegador usa. `errors="replace"` no fim porque pagina real declara encoding
    errado o tempo todo, e trocar um caractere por `?` custa uma amostra
    imperfeita; levantar custa a pagina inteira.
    """
    candidatos: list[str] = []
    if content_type:
        achado = _CHARSET.search(content_type.encode("ascii", "ignore"))
        if achado:
            candidatos.append(achado.group(1).decode("ascii"))
    achado_meta = _CHARSET.search(bruto[:4096])
    if achado_meta:
        candidatos.append(achado_meta.group(1).decode("ascii", "ignore"))
    candidatos.append("utf-8")

    for codificacao in candidatos:
        try:
            return bruto.decode(codificacao)
        except (LookupError, UnicodeDecodeError):
            continue
    return bruto.decode("utf-8", errors="replace")


# -------------------------------------------------------------------- coleta


def coletar_de(cliente: ClienteWeb, url: str) -> tuple[list[AmostraWeb], dict[str, int]]:
    """Le uma pagina e devolve as amostras que passam no filtro, mais a contagem."""
    contagem = {"imagens": 0, "sem_alt": 0, "alt_vazio": 0, "nao_insuficiente": 0}
    documento = cliente.baixar(url)
    extrator = extrair_alts(documento)

    contagem["imagens"] = extrator.total_de_imagens
    contagem["sem_alt"] = extrator.sem_alt

    achadas: list[AmostraWeb] = []
    for indice, bruto in enumerate(extrator.alts):
        texto = limpar(bruto)
        if texto is None:
            contagem["alt_vazio"] += 1
            continue
        motivo = parece_insuficiente(texto)
        if motivo is None:
            contagem["nao_insuficiente"] += 1
            continue
        achadas.append(AmostraWeb(alt=texto, url=url, motivo=motivo,
                                  indice_na_pagina=indice))
    return achadas, contagem


def coletar(cliente: ClienteWeb, urls: list[str], cota: int) -> tuple[
        list[AmostraWeb], dict[str, Any]]:
    """Percorre as URLs ate a cota. Pagina que falha nao derruba as outras."""
    aceitas: list[AmostraWeb] = []
    vistos: set[str] = set()
    totais = {"imagens": 0, "sem_alt": 0, "alt_vazio": 0, "nao_insuficiente": 0,
              "duplicado": 0}
    paginas: dict[str, Any] = {}

    for url in urls:
        if len(aceitas) >= cota:
            break
        if not cliente.permitido(url):
            paginas[url] = {"estado": "robots.txt proibe"}
            print(f"  pulando (robots.txt): {url}", file=sys.stderr)
            continue
        try:
            achadas, contagem = coletar_de(cliente, url)
        except ColetaWebError as erro:
            # Uma pagina fora do ar nao pode custar a lista inteira: o operador
            # informou dez URLs e espera o que der para colher das outras nove.
            paginas[url] = {"estado": f"falhou: {erro}"}
            print(f"  falhou: {erro}", file=sys.stderr)
            continue

        for chave, valor in contagem.items():
            totais[chave] += valor

        novas = 0
        for amostra in achadas:
            if len(aceitas) >= cota:
                break
            grupo = divisao.normalizar_alt(amostra.alt)
            if grupo in vistos:
                totais["duplicado"] += 1
                continue
            vistos.add(grupo)
            aceitas.append(amostra)
            novas += 1

        paginas[url] = {"estado": "ok", "aceitas": novas, **contagem}
        print(f"  [{len(aceitas)}/{cota}] {url} (+{novas})", file=sys.stderr)

    return aceitas, {"totais": totais, "por_pagina": paginas}


# ------------------------------------------------------------------ registros


def _base(indice_do_grupo: str) -> dict[str, Any]:
    return {
        "versao_do_formato": VERSAO_DO_FORMATO,
        "sha256_documento": None,
        "parte_pacote": None,
        "contexto_antes": "",
        "contexto_depois": "",
        "grupo": indice_do_grupo,
        "tem_alt": True,
        "rotulo_provisorio": INSUFICIENTE,
        "origem_do_rotulo": None,
        "rotulo": None,
    }


def registro_da_web(amostra: AmostraWeb, parte: str | None) -> dict[str, Any]:
    grupo = divisao.normalizar_alt(amostra.alt)
    partes = urllib.parse.urlsplit(amostra.url)
    return {
        **_base(grupo),
        "fonte": FONTE_WEB,
        "origem_do_dado": ORIGEM_COLETADA,
        "id": f"web:{partes.netloc}{partes.path}#{amostra.indice_na_pagina}",
        "arquivo": partes.netloc,
        "nome_imagem": None,
        "alt": amostra.alt,
        "origem_do_alt": ORIGEM_WEB,
        "motivo_do_filtro": amostra.motivo,
        "divisao": parte,
        "orgao": partes.netloc,
        "esfera": "web publica",
        "categoria": "pagina html",
        "licenca": "nao declarada",
        "url": amostra.url,
    }


def registro_sintetico(sintetica: gerador_insufficient.Sintetica) -> dict[str, Any]:
    """Amostra gerada. Sempre no TREINO, nunca em validacao nem em teste.

    Este e o ponto que separa "fallback declarado" de "dataset fabricado":
    metrica medida sobre string escrita neste repositorio nao mede deteccao de
    alt ruim no mundo. Forcar a parte aqui, e nao confiar no hash da divisao, e
    o que garante que nenhuma delas vaze para a medida final.
    """
    return {
        **_base(divisao.normalizar_alt(sintetica.alt)),
        "fonte": gerador_insufficient.FONTE,
        "origem_do_dado": gerador_insufficient.ORIGEM_SINTETICA,
        "id": sintetica.id,
        "arquivo": "gerador_insufficient",
        "nome_imagem": None,
        "alt": sintetica.alt,
        "origem_do_alt": ORIGEM_ALT_SINTETICO,
        "motivo_do_filtro": sintetica.padrao,
        "divisao": divisao.TREINO,
        "orgao": "AccessAI",
        "esfera": "sintetico",
        "categoria": "fallback de classe rara",
        "licenca": "nao aplicavel",
        "url": None,
    }


# ------------------------------------------------------------------ mesclagem


def carregar(caminho: pathlib.Path) -> list[dict[str, Any]]:
    """Le o dataset existente. Arquivo ausente e lista vazia, nao erro."""
    if not caminho.exists():
        return []
    registros: list[dict[str, Any]] = []
    with caminho.open(encoding="utf-8") as arquivo:
        for numero, bruta in enumerate(arquivo, start=1):
            bruta = bruta.strip()
            if not bruta:
                continue
            try:
                registros.append(json.loads(bruta))
            except json.JSONDecodeError as erro:
                raise ColetaWebError(
                    f"{caminho} linha {numero} nao e JSON valido: {erro}") from erro
    return registros


def contar_insuficientes(registros: list[dict[str, Any]]) -> int:
    """Quantas amostras da classe rara ja existem, por pre-rotulo ou por rotulo."""
    return sum(1 for registro in registros
               if (registro.get("rotulo") or registro.get("rotulo_provisorio"))
               == INSUFICIENTE)


def mesclar(existentes: list[dict[str, Any]],
            novos: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], int]:
    """Anexa os novos, pulando os que ja existem. Nao toca no que ja estava.

    A chave e o `grupo` — alt normalizado —, a mesma de `dataset.divisao`. Anexar
    por id deixaria a mesma frase entrar duas vezes com ids diferentes, e o
    vazamento entre treino e teste voltaria pela porta dos fundos.
    """
    conhecidos = {str(registro.get("grupo") or
                      divisao.normalizar_alt(str(registro.get("alt") or "")))
                  for registro in existentes}
    mesclados = list(existentes)
    anexados = 0
    for registro in novos:
        grupo = str(registro.get("grupo"))
        if grupo in conhecidos:
            continue
        conhecidos.add(grupo)
        mesclados.append(registro)
        anexados += 1
    return mesclados, anexados


def gravar(caminho: pathlib.Path, registros: list[dict[str, Any]]) -> None:
    """Reescreve por temporario na mesma pasta e troca atomica."""
    caminho.parent.mkdir(parents=True, exist_ok=True)
    temporario = caminho.with_name(caminho.name + ".tmp")
    with temporario.open("w", encoding="utf-8", newline="\n") as arquivo:
        for registro in registros:
            arquivo.write(json.dumps(registro, ensure_ascii=False) + "\n")
    temporario.replace(caminho)


# ------------------------------------------------------------------ relatorio


def montar_relatorio(novos: dict[str, list[dict[str, Any]]], anexados: int,
                     antes: int, depois: int, cota: int,
                     diagnostico: dict[str, Any], urls: list[str],
                     cliente: ClienteWeb) -> dict[str, Any]:
    coletados = novos["web"]
    sinteticos = novos["sintetico"]
    por_motivo: dict[str, int] = {}
    for registro in coletados + sinteticos:
        chave = str(registro["motivo_do_filtro"])
        por_motivo[chave] = por_motivo.get(chave, 0) + 1

    return {
        "versao_do_formato": VERSAO_DO_FORMATO,
        "gerado_em": datetime.now(UTC).isoformat(timespec="seconds"),
        "urls_informadas": len(urls),
        "pedidos_http": cliente.pedidos,
        "retentativas": cliente.retentativas,
        "cota_de_insuficientes": cota,
        "insuficientes_antes": antes,
        "insuficientes_depois": depois,
        "cota_atingida": depois >= cota,
        "anexados_ao_dataset": anexados,
        # A contagem por origem e a rastreabilidade que o fallback exige: quem
        # ler o dataset precisa saber quantas linhas ninguem coletou.
        "por_origem_do_dado": {
            ORIGEM_COLETADA: len(coletados),
            gerador_insufficient.ORIGEM_SINTETICA: len(sinteticos),
        },
        "por_motivo_do_filtro": por_motivo,
        "diagnostico_da_web": diagnostico,
        "ressalva": (
            "Amostras com origem_do_dado=sintetico_fallback foram GERADAS por "
            "accessai_ml.dataset.gerador_insufficient, nao coletadas. Elas "
            "entram apenas na parte de TREINO: metrica medida sobre texto "
            "escrito neste repositorio nao mede deteccao de alt ruim no mundo. "
            "O ADR 0002 exige que a procedencia do dataset seja declarada."),
    }


# ----------------------------------------------------------------------- CLI


def _urls_do_arquivo(caminho: pathlib.Path) -> list[str]:
    if not caminho.exists():
        raise ColetaWebError(f"lista de URLs ausente em {caminho}.")
    linhas = caminho.read_text(encoding="utf-8").splitlines()
    return [linha.strip() for linha in linhas
            if linha.strip() and not linha.strip().startswith("#")]


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(
        description="Coleta alt INSUFFICIENT de HTML publico, com fallback "
                    "sintetico declarado.")
    analisador.add_argument("--url", action="append", dest="urls", metavar="URL",
                            help="repetivel; sem URL nenhuma so o fallback roda")
    analisador.add_argument("--urls-de", type=pathlib.Path,
                            help="arquivo com uma URL por linha ('#' comenta)")
    analisador.add_argument("--dataset", type=pathlib.Path,
                            default=pathlib.Path("data") / "alt_texts.jsonl")
    analisador.add_argument("--relatorio", type=pathlib.Path,
                            default=pathlib.Path("data") / "relatorio_coleta_web.json")
    analisador.add_argument("--cota", type=int, default=COTA_PADRAO,
                            help="minimo de INSUFFICIENT no dataset ao terminar")
    analisador.add_argument("--sem-fallback", action="store_true",
                            help="nao completa a cota com amostra gerada")
    analisador.add_argument("--contato", default=CONTATO_PADRAO,
                            help="vai no User-Agent")
    analisador.add_argument("--pausa", type=float, default=PAUSA_PADRAO)
    analisador.add_argument("--tentativas", type=int, default=TENTATIVAS_PADRAO)
    analisador.add_argument("--tempo-limite", type=float, default=TEMPO_LIMITE)
    analisador.add_argument("--semente", type=int, default=42)
    argumentos = analisador.parse_args(argv)

    try:
        urls = list(argumentos.urls or [])
        if argumentos.urls_de:
            urls += _urls_do_arquivo(argumentos.urls_de)
        existentes = carregar(argumentos.dataset)
    except ColetaWebError as erro:
        print(f"entrada invalida: {erro}", file=sys.stderr)
        return SAIDA_DATASET_INVALIDO

    if not urls and argumentos.sem_fallback:
        print("nenhuma URL informada e --sem-fallback ligado: nada a fazer.",
              file=sys.stderr)
        return SAIDA_SEM_URL

    antes = contar_insuficientes(existentes)
    faltando = max(argumentos.cota - antes, 0)
    print(f"{antes} INSUFFICIENT no dataset; cota {argumentos.cota}; "
          f"faltam {faltando}.")

    cliente = ClienteWeb(contato=argumentos.contato,
                         tentativas=argumentos.tentativas,
                         pausa=argumentos.pausa,
                         tempo_limite=argumentos.tempo_limite,
                         semente=argumentos.semente)

    coletadas: list[AmostraWeb] = []
    diagnostico: dict[str, Any] = {"totais": {}, "por_pagina": {}}
    if faltando and urls:
        coletadas, diagnostico = coletar(cliente, urls, faltando)

    # A divisao das amostras da web sai do hash da chave, como o resto do
    # dataset. So as sinteticas sao forcadas para o treino.
    particao = divisao.dividir(divisao.normalizar_alt(a.alt) for a in coletadas)
    registros_web = [registro_da_web(a, particao.parte_de(
        divisao.normalizar_alt(a.alt))) for a in coletadas]

    registros_sinteticos: list[dict[str, Any]] = []
    if not argumentos.sem_fallback:
        ainda_faltam = max(faltando - len(registros_web), 0)
        # Pega do catalogo INTEIRO e descarta o que ja existe antes de contar.
        # Cortar em `ainda_faltam` primeiro entregaria menos que a cota sempre
        # que uma variacao ja estivesse no dataset — e a mesclagem descarta em
        # silencio, entao ninguem veria de onde veio a diferenca.
        ja_existem = {str(r.get("grupo") or
                          divisao.normalizar_alt(str(r.get("alt") or "")))
                      for r in existentes} | {r["grupo"] for r in registros_web}
        candidatos = [registro_sintetico(s)
                      for s in gerador_insufficient.gerar(gerador_insufficient.MAXIMO)]
        registros_sinteticos = [c for c in candidatos
                                if c["grupo"] not in ja_existem][:ainda_faltam]

    mesclados, anexados = mesclar(existentes, registros_web + registros_sinteticos)
    depois = contar_insuficientes(mesclados)

    if anexados:
        gravar(argumentos.dataset, mesclados)

    relatorio = montar_relatorio(
        {"web": registros_web, "sintetico": registros_sinteticos},
        anexados, antes, depois, argumentos.cota, diagnostico, urls, cliente)
    argumentos.relatorio.parent.mkdir(parents=True, exist_ok=True)
    argumentos.relatorio.write_text(
        json.dumps(relatorio, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps({chave: relatorio[chave] for chave in (
        "insuficientes_antes", "insuficientes_depois", "cota_atingida",
        "anexados_ao_dataset", "por_origem_do_dado", "por_motivo_do_filtro")},
        ensure_ascii=False, indent=2))
    print(f"\nrelatorio em {argumentos.relatorio.resolve()}")
    if anexados:
        print(f"{anexados} linhas anexadas a {argumentos.dataset.resolve()}")
    if registros_sinteticos:
        print(f"ATENCAO: {len(registros_sinteticos)} amostras SINTETICAS entraram "
              "(origem_do_dado=sintetico_fallback, so no treino).", file=sys.stderr)

    return SAIDA_OK if depois >= argumentos.cota else SAIDA_COTA_NAO_ATINGIDA


if __name__ == "__main__":
    raise SystemExit(main())
