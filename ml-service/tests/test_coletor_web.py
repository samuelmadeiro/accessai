"""Coletor web e gerador de fallback, sem uma unica requisicao externa.

`urllib.request.urlopen` e `RobotFileParser.read` sao substituidos em TODO teste
que poderia sair para a rede. Um teste que bate em site de terceiro falha quando
o site muda, quando a CI nao tem rede e quando o site resolve bloquear a CI —
tres formas de ficar vermelho sem nada ter quebrado aqui.
"""

from __future__ import annotations

import json
import pathlib
import urllib.error
from unittest import mock

import pytest

from accessai_ml.dataset import coletor_web, divisao, gerador_insufficient

INSUFICIENTE = "INSUFFICIENT"


@pytest.fixture(autouse=True)
def sem_rede(monkeypatch):
    """Trava de seguranca: qualquer chamada nao simulada estoura o teste."""
    def proibido(*_args, **_kwargs):
        raise AssertionError("a suite tentou sair para a rede")

    monkeypatch.setattr(coletor_web.urllib.request, "urlopen", proibido)
    monkeypatch.setattr(
        coletor_web.urllib.robotparser.RobotFileParser, "read", proibido)


class RespostaFalsa:
    def __init__(self, corpo: bytes, content_type: str = "text/html; charset=utf-8",
                 ) -> None:
        self.corpo = corpo
        self.headers = {"Content-Type": content_type}

    def read(self, _limite: int | None = None) -> bytes:
        return self.corpo

    def __enter__(self) -> RespostaFalsa:
        return self

    def __exit__(self, *_) -> bool:
        return False


def cliente_com(paginas: dict[str, bytes], monkeypatch,
                permitido: bool = True, **kwargs) -> coletor_web.ClienteWeb:
    """Cliente que serve `paginas` de memoria e nunca consulta robots de verdade."""
    cliente = coletor_web.ClienteWeb(pausa=0, semente=1, **kwargs)

    def abrir(pedido, timeout):
        url = pedido.full_url
        if url not in paginas:
            raise urllib.error.HTTPError(url, 404, "nao existe", {}, None)
        return RespostaFalsa(paginas[url])

    monkeypatch.setattr(coletor_web.urllib.request, "urlopen", abrir)
    monkeypatch.setattr(cliente, "permitido", lambda _url: permitido)
    return cliente


def pagina(*imgs: str) -> bytes:
    corpo = "".join(imgs)
    return f"<html><head><title>x</title></head><body>{corpo}</body></html>".encode()


# ------------------------------------------------------------------ extracao

def test_extrai_alt_de_cada_img_na_ordem():
    documento = ('<img src="a.jpg" alt="primeira">'
                 '<p>texto</p>'
                 '<img alt="segunda" src="b.png">')

    extrator = coletor_web.extrair_alts(documento)

    assert extrator.alts == ["primeira", "segunda"]
    assert extrator.total_de_imagens == 2
    assert extrator.sem_alt == 0


def test_img_sem_alt_e_contada_mas_nao_vira_amostra():
    # Alt ausente e deteccao deterministica do Rule Engine, nao amostra de ML.
    extrator = coletor_web.extrair_alts('<img src="a.jpg"><img src="b.jpg" alt="x">')

    assert extrator.alts == ["x"]
    assert extrator.sem_alt == 1
    assert extrator.total_de_imagens == 2


def test_alt_vazio_e_imagem_decorativa_nao_falta_de_alt():
    # `alt=""` e permitido pelo WCAG 1.1.1; contar como ausente acusaria quem
    # seguiu a norma.
    extrator = coletor_web.extrair_alts('<img src="a.jpg" alt="">')

    assert extrator.alts == [""]
    assert extrator.sem_alt == 0


@pytest.mark.parametrize("documento, esperado", [
    ('<IMG SRC="a.jpg" ALT="maiusculas">', ["maiusculas"]),
    ("<img src=a.jpg alt=sem_aspas>", ["sem_aspas"]),
    ('<img src="a.jpg" alt="com &amp; entidade">', ["com & entidade"]),
    ('<img src="a.jpg" alt="aspas &quot;dentro&quot;">', ['aspas "dentro"']),
    ('<img src="a.jpg" alt="sinal > dentro">', ["sinal > dentro"]),
    ("<img src='a.jpg' alt='aspas simples'/>", ["aspas simples"]),
])
def test_variantes_de_sintaxe_do_atributo(documento, esperado):
    assert coletor_web.extrair_alts(documento).alts == esperado


def test_html_quebrado_degrada_e_nao_levanta():
    documento = '<img src="a.jpg" alt="antes"><div <<< <img alt="depois">'

    extrator = coletor_web.extrair_alts(documento)

    assert "antes" in extrator.alts


# ------------------------------------------------------------------- limpeza

@pytest.mark.parametrize("bruto", [None, "", "   ", "\t\n"])
def test_limpeza_descarta_vazio(bruto):
    assert coletor_web.limpar(bruto) is None


@pytest.mark.parametrize("bruto, esperado", [
    ("  IMG_0001.jpg  ", "IMG_0001.jpg"),
    ("linha\numa\tlinha dois", "linha uma linha dois"),
    ("com\x00controle", "com controle"),
    ("<b>imagem</b>", "imagem"),
    ("espaco nao quebravel", "espaco nao quebravel"),
])
def test_limpeza_normaliza(bruto, esperado):
    assert coletor_web.limpar(bruto) == esperado


# ------------------------------------------------------- filtro INSUFFICIENT

@pytest.mark.parametrize("alt, motivo", [
    ("IMG_0001.jpg", coletor_web.MOTIVO_NOME),
    ("DSC_0142.JPG", coletor_web.MOTIVO_NOME),
    ("image1.png", coletor_web.MOTIVO_NOME),
    ("foto (3).jpeg", coletor_web.MOTIVO_NOME),
    ("logotipo-oficial.svg", coletor_web.MOTIVO_NOME),
    ("3f2a9c1e8b7d", coletor_web.MOTIVO_HASH),
    ("a1b2c3d4e5f6.png", coletor_web.MOTIVO_HASH),
    ("imagem", coletor_web.MOTIVO_GENERICO),
    ("Foto", coletor_web.MOTIVO_GENERICO),
    ("BANNER", coletor_web.MOTIVO_GENERICO),
    ("ícone", coletor_web.MOTIVO_GENERICO),
    ("sem titulo", coletor_web.MOTIVO_GENERICO),
    ("texto alternativo", coletor_web.MOTIVO_GENERICO),
    ("12345", coletor_web.MOTIVO_RUIDO),
    ("...", coletor_web.MOTIVO_RUIDO),
    ("---", coletor_web.MOTIVO_RUIDO),
    ("abc", coletor_web.MOTIVO_CURTO),
])
def test_filtro_reconhece_alt_insuficiente(alt, motivo):
    assert coletor_web.parece_insuficiente(alt) == motivo


@pytest.mark.parametrize("alt", [
    "Grafico de barras com a evolucao do orcamento entre 2020 e 2025",
    "Banner de divulgacao do edital de fomento cultural de 2025",
    "Fachada do predio sede vista da avenida principal",
    "Mapa do Brasil",
])
def test_filtro_deixa_passar_o_que_descreve(alt):
    # "banner" aparece no segundo caso e ainda assim ele descreve: o termo so
    # conta quando esta SOZINHO.
    assert coletor_web.parece_insuficiente(alt) is None


def test_polaridade_e_oposta_a_do_coletor_do_commons():
    from accessai_ml.dataset import coletor_alt_publico

    # O mesmo texto: descartado la, aceito aqui. Se os dois convergirem, alguem
    # juntou os filtros e a classe rara para de ser coletavel.
    assert coletor_alt_publico.limpar("IMG_0001.jpg") is None
    assert coletor_web.parece_insuficiente("IMG_0001.jpg") is not None


# ------------------------------------------------------------- decodificacao

@pytest.mark.parametrize("bruto, content_type, esperado", [
    ("acentuação".encode(), "text/html; charset=utf-8", "acentuação"),
    ("acentuação".encode("latin-1"), "text/html; charset=iso-8859-1", "acentuação"),
    ("acentuação".encode(), "text/html", "acentuação"),
])
def test_decodificacao_segue_a_ordem_do_navegador(bruto, content_type, esperado):
    assert coletor_web._decodificar(bruto, content_type) == esperado


def test_meta_charset_vale_quando_o_cabecalho_nao_diz():
    corpo = ('<meta charset="iso-8859-1"><img alt="acentuação">'.encode("latin-1"))

    assert "acentuação" in coletor_web._decodificar(corpo, "text/html")


def test_encoding_declarado_errado_nao_derruba_a_pagina():
    # Pagina real declara encoding errado o tempo todo. Trocar um caractere por
    # `?` custa uma amostra imperfeita; levantar custa a pagina inteira.
    corpo = "acentuação".encode()

    assert coletor_web._decodificar(corpo, "text/html; charset=ascii")


def test_encoding_inexistente_cai_para_utf8():
    assert coletor_web._decodificar(b"texto", "text/html; charset=nao-existe") == "texto"


# -------------------------------------------------------- cliente resiliente

def test_user_agent_identifica_a_ferramenta_e_o_contato(monkeypatch):
    cliente = coletor_web.ClienteWeb(contato="eu (eu@exemplo.org)", pausa=0)
    capturado: dict = {}

    def abrir(pedido, timeout):
        capturado["agente"] = pedido.get_header("User-agent")
        capturado["timeout"] = timeout
        return RespostaFalsa(b"<html></html>")

    monkeypatch.setattr(coletor_web.urllib.request, "urlopen", abrir)
    cliente.baixar("https://exemplo.org/")

    assert "AccessAI-coletor-web" in capturado["agente"]
    assert "eu@exemplo.org" in capturado["agente"]
    assert capturado["timeout"] == coletor_web.TEMPO_LIMITE


def test_timeout_e_passado_em_toda_leitura(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0, tempo_limite=3.5)
    vistos: list[float] = []

    def abrir(pedido, timeout):
        vistos.append(timeout)
        return RespostaFalsa(b"<html></html>")

    monkeypatch.setattr(coletor_web.urllib.request, "urlopen", abrir)
    cliente.baixar("https://exemplo.org/")

    assert vistos == [3.5]


def test_429_e_retentado_com_recuo_exponencial(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0, tentativas=3, semente=1)
    dormidas: list[float] = []
    monkeypatch.setattr(coletor_web.time, "sleep", dormidas.append)
    tentativas = {"n": 0}

    def abrir(pedido, timeout):
        tentativas["n"] += 1
        if tentativas["n"] < 3:
            raise urllib.error.HTTPError(pedido.full_url, 429, "devagar", {}, None)
        return RespostaFalsa(b"<html>ok</html>")

    monkeypatch.setattr(coletor_web.urllib.request, "urlopen", abrir)
    assert "ok" in cliente.baixar("https://exemplo.org/")
    assert len(dormidas) == 2
    assert dormidas[1] > dormidas[0]


def test_500_e_retentado(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0, tentativas=2, semente=1)
    monkeypatch.setattr(coletor_web.time, "sleep", lambda _: None)
    tentativas = {"n": 0}

    def abrir(pedido, timeout):
        tentativas["n"] += 1
        if tentativas["n"] == 1:
            raise urllib.error.HTTPError(pedido.full_url, 503, "fora", {}, None)
        return RespostaFalsa(b"<html>ok</html>")

    monkeypatch.setattr(coletor_web.urllib.request, "urlopen", abrir)
    assert "ok" in cliente.baixar("https://exemplo.org/")


def test_404_nao_e_retentado(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0, tentativas=5)
    chamadas = {"n": 0}

    def abrir(pedido, timeout):
        chamadas["n"] += 1
        raise urllib.error.HTTPError(pedido.full_url, 404, "nao existe", {}, None)

    monkeypatch.setattr(coletor_web.urllib.request, "urlopen", abrir)
    with pytest.raises(coletor_web.ColetaWebError, match="HTTP 404"):
        cliente.baixar("https://exemplo.org/")

    assert chamadas["n"] == 1


def test_timeout_de_rede_e_retentado_e_desiste_com_erro_claro(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0, tentativas=2, semente=1)
    monkeypatch.setattr(coletor_web.time, "sleep", lambda _: None)
    monkeypatch.setattr(coletor_web.urllib.request, "urlopen",
                        mock.Mock(side_effect=TimeoutError("estourou")))

    with pytest.raises(coletor_web.ColetaWebError, match="desisti depois de 2"):
        cliente.baixar("https://exemplo.org/")


def test_retry_after_manda_sobre_o_recuo(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0, tentativas=2, semente=1)
    dormidas: list[float] = []
    monkeypatch.setattr(coletor_web.time, "sleep", dormidas.append)
    primeira = {"passou": False}

    def abrir(pedido, timeout):
        if not primeira["passou"]:
            primeira["passou"] = True
            raise urllib.error.HTTPError(pedido.full_url, 429, "devagar",
                                         {"Retry-After": "9"}, None)
        return RespostaFalsa(b"<html></html>")

    monkeypatch.setattr(coletor_web.urllib.request, "urlopen", abrir)
    cliente.baixar("https://exemplo.org/")

    assert dormidas == [9.0]


def test_corpo_e_lido_com_teto(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0)
    limites: list[int] = []

    class RespostaQueRegistra(RespostaFalsa):
        def read(self, limite=None):
            limites.append(limite)
            return self.corpo

    monkeypatch.setattr(coletor_web.urllib.request, "urlopen",
                        lambda pedido, timeout: RespostaQueRegistra(b"<html></html>"))
    cliente.baixar("https://exemplo.org/")

    assert limites == [coletor_web.MAXIMO_DE_BYTES]


# -------------------------------------------------------------------- robots

def test_robots_que_proibe_bloqueia_a_url(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0)
    monkeypatch.setattr(
        coletor_web.urllib.robotparser.RobotFileParser, "read", lambda self: None)
    monkeypatch.setattr(
        coletor_web.urllib.robotparser.RobotFileParser, "can_fetch",
        lambda self, agente, url: False)

    assert cliente.permitido("https://exemplo.org/pagina") is False


def test_robots_ilegivel_e_tratado_como_permitido(monkeypatch):
    # Ausencia de robots.txt nao e proibicao.
    cliente = coletor_web.ClienteWeb(pausa=0)
    monkeypatch.setattr(coletor_web.urllib.robotparser.RobotFileParser, "read",
                        mock.Mock(side_effect=OSError("sem rede")))

    assert cliente.permitido("https://exemplo.org/pagina") is True


def test_robots_e_consultado_uma_vez_por_host(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0)
    leituras = mock.Mock(return_value=None)
    monkeypatch.setattr(coletor_web.urllib.robotparser.RobotFileParser, "read", leituras)
    monkeypatch.setattr(coletor_web.urllib.robotparser.RobotFileParser, "can_fetch",
                        lambda self, agente, url: True)

    for caminho in ("/a", "/b", "/c"):
        cliente.permitido(f"https://exemplo.org{caminho}")
    cliente.permitido("https://outro.org/a")

    assert leituras.call_count == 2


@pytest.mark.parametrize("url", ["ftp://exemplo.org/x", "file:///etc/passwd",
                                 "nao-e-url", "https://"])
def test_esquema_fora_de_http_e_recusado(url):
    assert coletor_web.ClienteWeb(pausa=0).permitido(url) is False


def test_pagina_proibida_por_robots_nao_e_baixada(monkeypatch):
    cliente = coletor_web.ClienteWeb(pausa=0)
    monkeypatch.setattr(cliente, "permitido", lambda _url: False)
    monkeypatch.setattr(coletor_web.urllib.request, "urlopen",
                        mock.Mock(side_effect=AssertionError("nao devia baixar")))

    aceitas, diagnostico = coletor_web.coletar(cliente, ["https://exemplo.org/"], 10)

    assert aceitas == []
    assert "robots" in diagnostico["por_pagina"]["https://exemplo.org/"]["estado"]


# -------------------------------------------------------------------- coleta

def test_coleta_fica_so_com_o_que_parece_insuficiente(monkeypatch):
    corpo = pagina(
        '<img src="1.jpg" alt="IMG_0001.jpg">',
        '<img src="2.jpg" alt="Grafico de barras com a evolucao do orcamento">',
        '<img src="3.jpg" alt="banner">',
        '<img src="4.jpg" alt="">',
        '<img src="5.jpg">',
    )
    cliente = cliente_com({"https://exemplo.org/": corpo}, monkeypatch)

    aceitas, diagnostico = coletor_web.coletar(cliente, ["https://exemplo.org/"], 10)

    assert [a.alt for a in aceitas] == ["IMG_0001.jpg", "banner"]
    totais = diagnostico["totais"]
    assert totais["imagens"] == 5
    assert totais["sem_alt"] == 1
    assert totais["alt_vazio"] == 1
    assert totais["nao_insuficiente"] == 1


def test_coleta_para_na_cota(monkeypatch):
    corpo = pagina(*[f'<img alt="IMG_{i:04d}.jpg">' for i in range(20)])
    cliente = cliente_com({"https://exemplo.org/": corpo}, monkeypatch)

    aceitas, _ = coletor_web.coletar(cliente, ["https://exemplo.org/"], 5)

    assert len(aceitas) == 5


def test_alt_repetido_entre_paginas_entra_uma_vez_so(monkeypatch):
    paginas = {
        "https://exemplo.org/a": pagina('<img alt="banner">'),
        "https://exemplo.org/b": pagina('<img alt="BANNER">'),
    }
    cliente = cliente_com(paginas, monkeypatch)

    aceitas, diagnostico = coletor_web.coletar(cliente, list(paginas), 10)

    assert len(aceitas) == 1
    assert diagnostico["totais"]["duplicado"] == 1


def test_pagina_que_falha_nao_derruba_as_outras(monkeypatch):
    paginas = {"https://exemplo.org/boa": pagina('<img alt="IMG_1.jpg">')}
    cliente = cliente_com(paginas, monkeypatch)

    aceitas, diagnostico = coletor_web.coletar(
        cliente, ["https://exemplo.org/morta", "https://exemplo.org/boa"], 10)

    assert [a.alt for a in aceitas] == ["IMG_1.jpg"]
    assert "falhou" in diagnostico["por_pagina"]["https://exemplo.org/morta"]["estado"]


# ------------------------------------------------------- gerador de fallback

def test_gerador_e_deterministico():
    assert gerador_insufficient.gerar(20) == gerador_insufficient.gerar(20)


def test_gerador_respeita_o_teto():
    assert len(gerador_insufficient.gerar(500)) == gerador_insufficient.MAXIMO


def test_gerador_produz_50_variacoes_distintas():
    gerados = gerador_insufficient.gerar(50)

    assert len(gerados) == 50
    assert len({g.alt for g in gerados}) == 50


def test_cota_pequena_traz_padroes_variados():
    # Catalogo em blocos entregaria so nome de arquivo com `--cota 8`, e o
    # treino aprenderia "termina em .jpg" em vez de "nao descreve".
    padroes = {g.padrao for g in gerador_insufficient.gerar(8)}

    assert len(padroes) >= 3


def test_todo_gerado_e_reconhecido_pelo_filtro_de_insuficiente():
    # A prova de que os dois lados concordam: se o gerador produzisse algo que o
    # filtro nao reconhece, um dos dois estaria errado.
    for sintetica in gerador_insufficient.gerar(50):
        assert coletor_web.parece_insuficiente(sintetica.alt) is not None, sintetica


def test_id_do_gerado_e_estavel_e_derivado_do_texto():
    primeiro = gerador_insufficient.gerar(3)
    segundo = gerador_insufficient.gerar(3)

    assert [g.id for g in primeiro] == [g.id for g in segundo]
    assert all(g.id.startswith("sintetico:") for g in primeiro)


def test_quantidade_negativa_e_recusada():
    with pytest.raises(ValueError, match="negativa"):
        gerador_insufficient.gerar(-1)


# ------------------------------------------------------------------ registros

def test_registro_sintetico_declara_a_origem_e_fica_no_treino():
    sintetica = gerador_insufficient.gerar(1)[0]

    registro = coletor_web.registro_sintetico(sintetica)

    assert registro["origem_do_dado"] == gerador_insufficient.ORIGEM_SINTETICA
    assert registro["rotulo_provisorio"] == INSUFICIENTE
    assert registro["rotulo"] is None
    # A parte e FORCADA: metrica sobre texto escrito neste repositorio nao mede
    # deteccao de alt ruim no mundo.
    assert registro["divisao"] == divisao.TREINO


def test_nenhum_sintetico_escapa_para_validacao_ou_teste():
    registros = [coletor_web.registro_sintetico(s)
                 for s in gerador_insufficient.gerar(50)]

    assert {r["divisao"] for r in registros} == {divisao.TREINO}


def test_registro_da_web_guarda_a_procedencia():
    amostra = coletor_web.AmostraWeb(alt="IMG_1.jpg", url="https://exemplo.org/p",
                                     motivo=coletor_web.MOTIVO_NOME,
                                     indice_na_pagina=2)

    registro = coletor_web.registro_da_web(amostra, divisao.TESTE)

    assert registro["origem_do_dado"] == coletor_web.ORIGEM_COLETADA
    assert registro["url"] == "https://exemplo.org/p"
    assert registro["motivo_do_filtro"] == coletor_web.MOTIVO_NOME
    assert registro["id"] == "web:exemplo.org/p#2"
    assert registro["divisao"] == divisao.TESTE


# ------------------------------------------------------------------ mesclagem

def _registro(alt: str, rotulo_provisorio: str | None = INSUFICIENTE) -> dict:
    return {"id": f"x:{alt}", "alt": alt, "grupo": divisao.normalizar_alt(alt),
            "divisao": divisao.TREINO, "rotulo_provisorio": rotulo_provisorio,
            "rotulo": None}


def test_mesclagem_preserva_os_registros_existentes():
    existentes = [_registro("primeira"), _registro("segunda")]
    novos = [_registro("terceira")]

    mesclados, anexados = coletor_web.mesclar(existentes, novos)

    assert [r["alt"] for r in mesclados] == ["primeira", "segunda", "terceira"]
    assert anexados == 1


def test_mesclagem_nao_duplica_alt_ja_presente():
    # Chave e o alt normalizado, e nao o id: anexar por id deixaria a mesma
    # frase entrar duas vezes e o vazamento voltaria pela porta dos fundos.
    existentes = [_registro("Banner")]
    novos = [_registro("BANNER  "), _registro("nova")]

    mesclados, anexados = coletor_web.mesclar(existentes, novos)

    assert len(mesclados) == 2
    assert anexados == 1


def test_mesclagem_em_dataset_sem_grupo_usa_o_alt():
    existentes = [{"id": "antigo", "alt": "banner"}]

    _, anexados = coletor_web.mesclar(existentes, [_registro("banner")])

    assert anexados == 0


def test_contagem_de_insuficientes_ve_rotulo_e_pre_rotulo():
    registros = [
        _registro("a"),
        {**_registro("b"), "rotulo_provisorio": "GOOD"},
        {**_registro("c"), "rotulo_provisorio": "GOOD", "rotulo": INSUFICIENTE},
    ]

    assert coletor_web.contar_insuficientes(registros) == 2


def test_gravacao_e_atomica_e_nao_deixa_temporario(tmp_path):
    caminho = tmp_path / "alt_texts.jsonl"

    coletor_web.gravar(caminho, [_registro("unica")])

    assert list(tmp_path.iterdir()) == [caminho]
    assert json.loads(caminho.read_text(encoding="utf-8").strip())["alt"] == "unica"


def test_carregar_arquivo_ausente_e_lista_vazia(tmp_path):
    assert coletor_web.carregar(tmp_path / "nao_existe.jsonl") == []


def test_carregar_linha_invalida_interrompe(tmp_path):
    caminho = tmp_path / "d.jsonl"
    caminho.write_text('{"id": 1}\nnao sou json\n', encoding="utf-8")

    with pytest.raises(coletor_web.ColetaWebError, match="linha 2"):
        coletor_web.carregar(caminho)


# ----------------------------------------------------------------------- CLI

def _dataset(tmp_path: pathlib.Path, registros: list[dict]) -> pathlib.Path:
    caminho = tmp_path / "alt_texts.jsonl"
    with caminho.open("w", encoding="utf-8", newline="\n") as arquivo:
        for registro in registros:
            arquivo.write(json.dumps(registro, ensure_ascii=False) + "\n")
    return caminho


def test_cli_completa_a_cota_com_fallback_quando_nao_ha_url(tmp_path, capsys):
    existentes = [_registro(f"alt bom numero {i}", "GOOD") for i in range(5)]
    caminho = _dataset(tmp_path, existentes)
    relatorio = tmp_path / "rel.json"

    codigo = coletor_web.main(["--dataset", str(caminho),
                               "--relatorio", str(relatorio), "--cota", "12"])

    assert codigo == coletor_web.SAIDA_OK
    registros = coletor_web.carregar(caminho)
    assert len(registros) == 17
    assert coletor_web.contar_insuficientes(registros) == 12
    # Os 5 originais continuam la, intactos.
    assert [r["alt"] for r in registros[:5]] == [r["alt"] for r in existentes]

    conteudo = json.loads(relatorio.read_text(encoding="utf-8"))
    assert conteudo["por_origem_do_dado"][gerador_insufficient.ORIGEM_SINTETICA] == 12
    assert conteudo["por_origem_do_dado"][coletor_web.ORIGEM_COLETADA] == 0
    assert conteudo["cota_atingida"] is True
    assert "sintetico_fallback" in conteudo["ressalva"]
    assert "SINTETICAS" in capsys.readouterr().err


def test_cli_prefere_a_web_e_so_completa_o_que_faltou(tmp_path, monkeypatch):
    corpo = pagina(*[f'<img alt="IMG_{i:04d}.jpg">' for i in range(4)])
    caminho = _dataset(tmp_path, [_registro("alt bom", "GOOD")])
    relatorio = tmp_path / "rel.json"

    cliente = cliente_com({"https://exemplo.org/": corpo}, monkeypatch)
    monkeypatch.setattr(coletor_web, "ClienteWeb", lambda **_kwargs: cliente)

    coletor_web.main(["--dataset", str(caminho), "--relatorio", str(relatorio),
                      "--cota", "10", "--url", "https://exemplo.org/"])

    conteudo = json.loads(relatorio.read_text(encoding="utf-8"))
    assert conteudo["por_origem_do_dado"][coletor_web.ORIGEM_COLETADA] == 4
    assert conteudo["por_origem_do_dado"][gerador_insufficient.ORIGEM_SINTETICA] == 6
    assert coletor_web.contar_insuficientes(coletor_web.carregar(caminho)) == 10


def test_cli_sem_fallback_para_na_web(tmp_path, monkeypatch):
    caminho = _dataset(tmp_path, [])
    relatorio = tmp_path / "rel.json"
    cliente = cliente_com({"https://exemplo.org/": pagina('<img alt="a.jpg">')},
                          monkeypatch)
    monkeypatch.setattr(coletor_web, "ClienteWeb", lambda **_kwargs: cliente)

    codigo = coletor_web.main(["--dataset", str(caminho), "--relatorio",
                               str(relatorio), "--cota", "10", "--sem-fallback",
                               "--url", "https://exemplo.org/"])

    assert codigo == coletor_web.SAIDA_COTA_NAO_ATINGIDA
    conteudo = json.loads(relatorio.read_text(encoding="utf-8"))
    assert conteudo["por_origem_do_dado"][gerador_insufficient.ORIGEM_SINTETICA] == 0
    assert conteudo["cota_atingida"] is False


def test_cli_sem_url_e_sem_fallback_nao_faz_nada(tmp_path, capsys):
    codigo = coletor_web.main(["--dataset", str(_dataset(tmp_path, [])),
                               "--sem-fallback"])

    assert codigo == coletor_web.SAIDA_SEM_URL
    assert "nada a fazer" in capsys.readouterr().err


def test_cli_com_a_cota_ja_cheia_nao_anexa_nada(tmp_path):
    existentes = [_registro(f"ruim {i}") for i in range(12)]
    caminho = _dataset(tmp_path, existentes)
    relatorio = tmp_path / "rel.json"

    codigo = coletor_web.main(["--dataset", str(caminho),
                               "--relatorio", str(relatorio), "--cota", "10"])

    assert codigo == coletor_web.SAIDA_OK
    assert len(coletor_web.carregar(caminho)) == 12
    assert json.loads(relatorio.read_text(encoding="utf-8"))["anexados_ao_dataset"] == 0


def test_cli_le_urls_de_arquivo(tmp_path, monkeypatch):
    lista = tmp_path / "urls.txt"
    lista.write_text("# comentario\nhttps://exemplo.org/\n\n", encoding="utf-8")
    caminho = _dataset(tmp_path, [])
    cliente = cliente_com({"https://exemplo.org/": pagina('<img alt="IMG_1.jpg">')},
                          monkeypatch)
    monkeypatch.setattr(coletor_web, "ClienteWeb", lambda **_kwargs: cliente)

    coletor_web.main(["--dataset", str(caminho), "--urls-de", str(lista),
                      "--relatorio", str(tmp_path / "rel.json"), "--cota", "1"])

    assert [r["alt"] for r in coletor_web.carregar(caminho)] == ["IMG_1.jpg"]


def test_cli_recusa_lista_de_urls_ausente(tmp_path, capsys):
    codigo = coletor_web.main(["--dataset", str(_dataset(tmp_path, [])),
                               "--urls-de", str(tmp_path / "nao_existe.txt")])

    assert codigo == coletor_web.SAIDA_DATASET_INVALIDO
    assert "entrada invalida" in capsys.readouterr().err


def test_dataset_completado_e_lido_pelo_carregador_do_treino(tmp_path):
    from accessai_ml.training import dados

    existentes = []
    for indice in range(30):
        alt = f"descricao longa e informativa de numero {indice}"
        grupo = divisao.chave_de_agrupamento(alt, f"x{indice}")
        existentes.append({
            "id": f"x:{indice}", "alt": alt, "grupo": grupo,
            "divisao": divisao.dividir([grupo]).parte_de(grupo),
            "rotulo_provisorio": "GOOD" if indice % 2 else "WEAK",
            "rotulo": "GOOD" if indice % 2 else "WEAK",
            "origem_do_rotulo": "humano"})
    caminho = _dataset(tmp_path, existentes)

    coletor_web.main(["--dataset", str(caminho),
                      "--relatorio", str(tmp_path / "rel.json"), "--cota", "10"])

    # As sinteticas entram com `rotulo` nulo: elas ainda precisam de revisao.
    registros = coletor_web.carregar(caminho)
    for registro in registros:
        if registro.get("origem_do_dado") == gerador_insufficient.ORIGEM_SINTETICA:
            registro["rotulo"] = registro["rotulo_provisorio"]
    coletor_web.gravar(caminho, registros)

    conjuntos = dados.carregar(caminho)
    assert conjuntos.total == 40
    assert {a.rotulo for a in conjuntos.treino} >= {INSUFICIENTE}
