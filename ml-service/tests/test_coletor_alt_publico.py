"""Coletor do Wikimedia Commons, exercitado sem tocar na rede.

Todas as respostas da API sao falsas e montadas aqui. Bater na API de verdade
dentro da suite deixaria o teste dependente de conteudo que muda sozinho, de
disponibilidade externa e da politica de cota da Wikimedia — tres razoes para o
teste falhar sem nada ter quebrado no codigo.
"""

from __future__ import annotations

import json
import urllib.error

import pytest

from accessai_ml.dataset import coletor_alt_publico as coletor
from accessai_ml.dataset import divisao
from accessai_ml.training import dados

BOM = coletor.BOM
FRACO = coletor.FRACO
INSUFICIENTE = coletor.INSUFICIENTE


class ApiFalsa:
    """Devolve respostas na ordem em que foram registradas e conta os pedidos."""

    def __init__(self, respostas: list[dict]) -> None:
        self.respostas = list(respostas)
        self.pedidos: list[dict[str, str]] = []

    def __call__(self, parametros: dict[str, str]) -> dict:
        self.pedidos.append(dict(parametros))
        return self.respostas.pop(0) if self.respostas else {"batchcomplete": True}


def pagina_de_arquivos(*arquivos: dict, continuacao: dict | None = None) -> dict:
    corpo: dict = {"query": {"pages": list(arquivos)}}
    if continuacao:
        corpo["continue"] = continuacao
    return corpo


def arquivo(pageid: int, titulo: str, legenda: str | None = None,
            descricao: str | None = None, licenca: str = "CC BY-SA 4.0") -> dict:
    pagina: dict = {
        "pageid": pageid,
        "title": titulo,
        "imageinfo": [{
            "descriptionurl": f"https://commons.wikimedia.org/wiki/{titulo}",
            "extmetadata": {"LicenseShortName": {"value": licenca}},
        }],
    }
    if descricao is not None:
        pagina["imageinfo"][0]["extmetadata"]["ImageDescription"] = {"value": descricao}
    if legenda is not None:
        pagina["entityterms"] = {"label": [legenda]}
    return pagina


# ------------------------------------------------------------------- limpeza

@pytest.mark.parametrize("bruto", [
    None, "", "   ", "\t\n", "IMG_0001.jpg", "DS_Store", ".png", "foto.JPEG",
    "no description available", "---", "123 456", "??", "<p></p>",
])
def test_limpeza_descarta_o_que_nao_e_texto(bruto):
    assert coletor.limpar(bruto) is None


@pytest.mark.parametrize("bruto, esperado", [
    ("<p>Ponte medieval sobre o rio Neiva</p>", "Ponte medieval sobre o rio Neiva"),
    ("Mapa <span class=\"x\">do</span> Brasil", "Mapa do Brasil"),
    ("&lt;p&gt;Fachada do predio&lt;/p&gt;", "Fachada do predio"),
    ("&amp;lt;span&amp;gt;Grafico&amp;lt;/span&amp;gt;", "Grafico"),
    ("Linha\numa\tLinha\rdois", "Linha uma Linha dois"),
    ("Texto\x00com\x07controle", "Texto com controle"),
    ("  espacos    demais  ", "espacos demais"),
    ("“Citado entre aspas”", "Citado entre aspas"),
    ("Espaco nao quebravel", "Espaco nao quebravel"),
])
def test_limpeza_normaliza_html_e_ruido(bruto, esperado):
    assert coletor.limpar(bruto) == esperado


# -------------------------------------------------------------- pre-rotulagem

@pytest.mark.parametrize("texto, esperado", [
    ("imagem", INSUFICIENTE),
    ("foto", INSUFICIENTE),
    ("Logo", INSUFICIENTE),
    ("de a o", INSUFICIENTE),
    # 10 a 30 caracteres: generico por comprimento.
    ("Ponte antiga", FRACO),
    ("Brasao da Republica", FRACO),
    # Repeticao de palavra derruba mesmo passando dos 30.
    ("Fachada fachada fachada fachada do predio", FRACO),
    # Passa de 30 caracteres mas nao tem estrutura de frase: dois tokens.
    ("AAAAAAAAAAAAAAAA BBBBBBBBBBBBBBBB", FRACO),
    ("Grafico de barras com a evolucao do orcamento entre 2020 e 2025", BOM),
    ("Foto da fachada do predio sede vista da avenida principal", BOM),
])
def test_pre_rotulo_segue_os_limiares(texto, esperado):
    assert coletor.pre_rotular(texto) == esperado


def test_termo_bloqueado_no_meio_de_uma_descricao_nao_derruba_para_insuficiente():
    # "foto" aparece, mas sobra descricao em volta. Bloquear por conter o termo
    # jogaria fora justamente as descricoes boas que comecam por "Foto de".
    assert coletor.pre_rotular("Foto da ponte de pedra sobre o rio Douro") == BOM


def test_pre_rotulo_nao_repete_os_limiares_do_baseline():
    # Se a pre-rotulagem usasse os limiares do BaselineHeuristico (15/40), o
    # baseline fecharia macro-F1 1.0 por construcao e o veredito do treino
    # viraria teatro.
    from accessai_ml.training import modelo

    assert coletor.CURTO_DEMAIS != modelo.CURTO_DEMAIS
    assert coletor.GENERICO_ATE != modelo.LONGO_O_BASTANTE


# --------------------------------------------------------- cliente resiliente

def test_cliente_manda_user_agent_com_contato(monkeypatch):
    cliente = coletor.ClienteCommons(contato="teste (eu@exemplo.org)", pausa=0)
    capturado: dict = {}

    class RespostaFalsa:
        def read(self):
            return json.dumps({"query": {}}).encode("utf-8")

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    def abrir(pedido, timeout):
        capturado["agente"] = pedido.get_header("User-agent")
        capturado["url"] = pedido.full_url
        return RespostaFalsa()

    monkeypatch.setattr(coletor.urllib.request, "urlopen", abrir)
    cliente.consultar({"list": "categorymembers"})

    assert "AccessAI-coletor" in capturado["agente"]
    assert "eu@exemplo.org" in capturado["agente"]
    # `maxlag` faz a API recusar quando as replicas estao atrasadas, em vez de
    # o coletor piorar um incidente em andamento.
    assert "maxlag" in capturado["url"]


def test_429_e_retentado_com_recuo_e_termina_bem(monkeypatch):
    cliente = coletor.ClienteCommons(contato="t", tentativas=3, pausa=0, semente=1)
    dormidas: list[float] = []
    monkeypatch.setattr(coletor.time, "sleep", dormidas.append)

    tentativas = {"n": 0}

    class RespostaFalsa:
        def read(self):
            return json.dumps({"query": {"ok": True}}).encode("utf-8")

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    def abrir(pedido, timeout):
        tentativas["n"] += 1
        if tentativas["n"] < 3:
            raise urllib.error.HTTPError(pedido.full_url, 429, "slow down",
                                         {}, None)  # type: ignore[arg-type]
        return RespostaFalsa()

    monkeypatch.setattr(coletor.urllib.request, "urlopen", abrir)
    corpo = cliente.consultar({"list": "x"})

    assert corpo["query"]["ok"] is True
    # `pausa=0` deixa apenas os recuos na lista.
    assert len(dormidas) == 2
    assert dormidas[1] > dormidas[0]  # recuo exponencial
    assert cliente.retentativas == 2


def test_retry_after_manda_sobre_o_recuo(monkeypatch):
    cliente = coletor.ClienteCommons(contato="t", tentativas=2, pausa=0, semente=1)
    dormidas: list[float] = []
    monkeypatch.setattr(coletor.time, "sleep", dormidas.append)

    class RespostaFalsa:
        def read(self):
            return json.dumps({"query": {}}).encode("utf-8")

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    primeira = {"passou": False}

    def abrir(pedido, timeout):
        if not primeira["passou"]:
            primeira["passou"] = True
            raise urllib.error.HTTPError(
                pedido.full_url, 429, "slow down", {"Retry-After": "7"},  # type: ignore[arg-type]
                None)
        return RespostaFalsa()

    monkeypatch.setattr(coletor.urllib.request, "urlopen", abrir)
    cliente.consultar({"list": "x"})

    assert dormidas == [7.0]


def test_404_nao_e_retentado(monkeypatch):
    # Pedido malformado nao melhora sozinho, e insistir nele e o comportamento
    # que a Wikimedia bloqueia.
    cliente = coletor.ClienteCommons(contato="t", tentativas=5, pausa=0)
    chamadas = {"n": 0}

    def abrir(pedido, timeout):
        chamadas["n"] += 1
        raise urllib.error.HTTPError(pedido.full_url, 404, "nope", {}, None)  # type: ignore[arg-type]

    monkeypatch.setattr(coletor.urllib.request, "urlopen", abrir)
    with pytest.raises(coletor.ColetaError, match="HTTP 404"):
        cliente.consultar({"list": "x"})

    assert chamadas["n"] == 1


def test_maxlag_chega_como_200_e_ainda_assim_e_espera(monkeypatch):
    cliente = coletor.ClienteCommons(contato="t", tentativas=2, pausa=0, semente=1)
    monkeypatch.setattr(coletor.time, "sleep", lambda _: None)
    corpos = [{"error": {"code": "maxlag", "info": "replica atrasada"}},
              {"query": {"ok": True}}]

    class RespostaFalsa:
        def __init__(self, corpo):
            self.corpo = corpo

        def read(self):
            return json.dumps(self.corpo).encode("utf-8")

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    monkeypatch.setattr(coletor.urllib.request, "urlopen",
                        lambda pedido, timeout: RespostaFalsa(corpos.pop(0)))
    assert cliente.consultar({"list": "x"})["query"]["ok"] is True
    assert cliente.retentativas == 1


# ----------------------------------------------------------------- paginacao

def test_paginacao_segue_o_continue(monkeypatch):
    cliente = coletor.ClienteCommons(contato="t", pausa=0)
    api = ApiFalsa([
        pagina_de_arquivos(arquivo(1, "File:a.jpg", legenda="Ponte de pedra"),
                           continuacao={"gcmcontinue": "pagina2"}),
        pagina_de_arquivos(arquivo(2, "File:b.jpg", legenda="Mapa antigo")),
    ])
    monkeypatch.setattr(cliente, "consultar", api)

    brutos = list(coletor.buscar_arquivos(cliente, "Category:X", "pt"))

    assert [b.pageid for b in brutos] == [1, 2]
    assert api.pedidos[1]["gcmcontinue"] == "pagina2"


def test_continue_repetido_nao_gira_para_sempre(monkeypatch):
    cliente = coletor.ClienteCommons(contato="t", pausa=0)
    repetida = pagina_de_arquivos(arquivo(1, "File:a.jpg", legenda="Ponte"),
                                  continuacao={"gcmcontinue": "sempre"})
    monkeypatch.setattr(cliente, "consultar", lambda _p: repetida)

    paginas = list(cliente.paginar({"list": "x"}))

    assert len(paginas) == 2  # a primeira, mais a que repetiu o marcador


# -------------------------------------------------------------------- coleta

def test_coleta_prefere_a_legenda_e_cai_para_a_descricao(monkeypatch):
    cliente = coletor.ClienteCommons(contato="t", pausa=0)
    monkeypatch.setattr(cliente, "consultar", ApiFalsa([pagina_de_arquivos(
        arquivo(1, "File:a.jpg", legenda="Ponte medieval sobre o rio Neiva",
                descricao="Descricao longa que nao deveria ser escolhida"),
        arquivo(2, "File:b.jpg", descricao="<p>Mapa das regioes do programa</p>"),
        arquivo(3, "File:c.jpg"),
    )]))

    amostras, descartes = coletor.coletar(cliente, ["Category:X"], coletor.AMBOS,
                                          "pt", limite=10)

    assert [(a[1], a[2]) for a in amostras] == [
        ("Ponte medieval sobre o rio Neiva", coletor.ORIGEM_LEGENDA),
        ("Mapa das regioes do programa", coletor.ORIGEM_DESCRICAO),
    ]
    assert descartes["sem_texto"] == 1


def test_coleta_descarta_duplicado_por_alt_normalizado(monkeypatch):
    cliente = coletor.ClienteCommons(contato="t", pausa=0)
    monkeypatch.setattr(cliente, "consultar", ApiFalsa([pagina_de_arquivos(
        arquivo(1, "File:a.jpg", legenda="Brasao da Republica"),
        arquivo(2, "File:b.jpg", legenda="BRASAO   da  republica"),
    )]))

    amostras, descartes = coletor.coletar(cliente, ["Category:X"], coletor.LEGENDA,
                                          "pt", limite=10)

    assert len(amostras) == 1
    assert descartes["duplicado"] == 1


def test_limite_para_a_coleta(monkeypatch):
    cliente = coletor.ClienteCommons(contato="t", pausa=0)
    monkeypatch.setattr(cliente, "consultar", ApiFalsa([pagina_de_arquivos(
        *[arquivo(i, f"File:{i}.jpg", legenda=f"Ponte numero {i} sobre o rio")
          for i in range(1, 21)])]))

    amostras, _ = coletor.coletar(cliente, ["Category:X"], coletor.LEGENDA, "pt",
                                  limite=5)

    assert len(amostras) == 5


def test_expansao_de_categorias_e_em_largura(monkeypatch):
    cliente = coletor.ClienteCommons(contato="t", pausa=0)
    respostas = {
        "Category:Raiz": ["Category:Filha1", "Category:Filha2"],
        "Category:Filha1": ["Category:Neta"],
        "Category:Filha2": [],
    }

    def consultar(parametros):
        titulo = parametros["cmtitle"]
        return {"query": {"categorymembers": [
            {"title": t} for t in respostas.get(titulo, [])]}}

    monkeypatch.setattr(cliente, "consultar", consultar)

    assert coletor.expandir_categorias(cliente, ["Category:Raiz"], 1) == [
        "Category:Raiz", "Category:Filha1", "Category:Filha2"]
    assert "Category:Neta" in coletor.expandir_categorias(
        cliente, ["Category:Raiz"], 2)


# ------------------------------------------------------- formato de saida

def _amostras_falsas(quantidade: int) -> list[tuple[coletor.Bruto, str, str]]:
    textos = [
        "Grafico de barras com a evolucao do orcamento entre 2020 e 2025",
        "Mapa do Brasil com as regioes de atuacao do programa destacadas",
        "Fotografia da fachada do predio sede vista da avenida principal",
        "Fluxograma das etapas de habilitacao do pregao eletronico",
        "Ponte antiga",
        "Brasao oficial",
        "Selo comemorativo",
        "imagem",
        "foto",
        "logotipo",
    ]
    return [
        (coletor.Bruto(pageid=i, titulo=f"File:{i}.jpg", legenda=None,
                       descricao=None, licenca="CC BY-SA 4.0",
                       url=f"https://commons.wikimedia.org/wiki/File:{i}.jpg",
                       categoria_semente="Category:X"),
         textos[i % len(textos)], coletor.ORIGEM_LEGENDA)
        for i in range(quantidade)
    ]


def test_linha_tem_os_campos_que_o_treino_le():
    linhas = coletor.montar_linhas(_amostras_falsas(4), rotular=False)

    for linha in linhas:
        assert linha["id"].startswith("commons:")
        assert linha["alt"]
        assert linha["divisao"] in divisao.PARTES
        assert linha["grupo"] == divisao.chave_de_agrupamento(
            linha["alt"], linha["id"].removeprefix("commons:"))
        assert linha["tem_alt"] is True
        assert linha["licenca"] == "CC BY-SA 4.0"
        assert linha["url"].startswith("https://commons.wikimedia.org/")


def test_pre_rotulo_nao_vira_rotulo_sem_ser_pedido():
    # O ADR 0002 secao 4 exige revisao humana. Preencher `rotulo` aqui faria o
    # treino rodar sobre concordancia com uma regra, sem ninguem perceber.
    linhas = coletor.montar_linhas(_amostras_falsas(4), rotular=False)

    assert all(linha["rotulo"] is None for linha in linhas)
    assert all(linha["rotulo_provisorio"] in
               (BOM, FRACO, INSUFICIENTE) for linha in linhas)
    assert all(linha["origem_do_rotulo"] is None for linha in linhas)


def test_flag_promove_o_pre_rotulo_e_grava_a_origem():
    linhas = coletor.montar_linhas(_amostras_falsas(4), rotular=True)

    assert all(linha["rotulo"] == linha["rotulo_provisorio"] for linha in linhas)
    assert all(linha["origem_do_rotulo"] == "heuristica" for linha in linhas)


def test_alts_iguais_caem_na_mesma_parte_da_divisao():
    amostras = _amostras_falsas(30)
    linhas = coletor.montar_linhas(amostras, rotular=True)

    por_grupo: dict[str, set[str]] = {}
    for linha in linhas:
        por_grupo.setdefault(linha["grupo"], set()).add(linha["divisao"])
    assert all(len(partes) == 1 for partes in por_grupo.values())


def test_saida_rotulada_e_lida_pelo_carregador_do_treino(tmp_path):
    # A prova de compatibilidade que importa: o arquivo escrito aqui passa pelo
    # mesmo `dados.carregar` que o `train.py` usa, com as travas dele ligadas.
    saida = tmp_path / "alt_texts.jsonl"
    linhas = coletor.montar_linhas(_amostras_falsas(40), rotular=True)
    with saida.open("w", encoding="utf-8", newline="\n") as arquivo:
        for linha in linhas:
            arquivo.write(json.dumps(linha, ensure_ascii=False) + "\n")

    conjuntos = dados.carregar(saida)

    assert conjuntos.total > 0
    assert conjuntos.treino


# ----------------------------------------------------------------------- CLI

def test_cli_recusa_sobrescrever_dataset_existente(tmp_path):
    saida = tmp_path / "alt_texts.jsonl"
    saida.write_text("nao me apague\n", encoding="utf-8")

    codigo = coletor.main(["--saida", str(saida), "--limite", "1"])

    assert codigo == coletor.SAIDA_SAIDA_EXISTENTE
    assert saida.read_text(encoding="utf-8") == "nao me apague\n"


def test_cli_escreve_jsonl_e_relatorio(tmp_path, monkeypatch):
    saida = tmp_path / "alt_texts.jsonl"
    relatorio = tmp_path / "relatorio_coleta.json"
    monkeypatch.setattr(coletor, "expandir_categorias",
                        lambda _c, sementes, _p: list(sementes))
    monkeypatch.setattr(coletor, "coletar",
                        lambda *_a, **_k: (_amostras_falsas(12),
                                           {"sem_texto": 1, "limpeza": 0,
                                            "duplicado": 2}))

    codigo = coletor.main(["--saida", str(saida), "--relatorio", str(relatorio),
                           "--limite", "12", "--rotular-com-heuristica"])

    assert codigo == coletor.SAIDA_OK
    linhas = [json.loads(linha) for linha in
              saida.read_text(encoding="utf-8").splitlines()]
    assert len(linhas) == 12
    conteudo = json.loads(relatorio.read_text(encoding="utf-8"))
    assert conteudo["fonte"] == coletor.FONTE
    assert conteudo["rotulo_preenchido"] is True
    assert conteudo["descartes"]["duplicado"] == 2
    # A ressalva do ADR 0002 nao pode sumir do relatorio.
    assert "kappa de Cohen" in conteudo["ressalva"]


def test_cli_sem_amostra_nao_escreve_nada(tmp_path, monkeypatch):
    saida = tmp_path / "alt_texts.jsonl"
    monkeypatch.setattr(coletor, "expandir_categorias",
                        lambda _c, sementes, _p: list(sementes))
    monkeypatch.setattr(coletor, "coletar",
                        lambda *_a, **_k: ([], {"sem_texto": 3, "limpeza": 0,
                                                "duplicado": 0}))

    codigo = coletor.main(["--saida", str(saida), "--limite", "5"])

    assert codigo == coletor.SAIDA_SEM_AMOSTRA
    assert not saida.exists()
