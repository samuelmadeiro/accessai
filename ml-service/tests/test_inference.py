"""API de inferencia, com e sem modelo carregado."""

from __future__ import annotations

import pathlib

import joblib
import pytest
from fastapi.testclient import TestClient

from accessai_ml.inference import main, schemas
from accessai_ml.inference.servico import ServicoDePredicao
from accessai_ml.training import train
from test_training import escrever_dataset  # noqa: I001  (helper local de teste)


@pytest.fixture
def sem_modelo(tmp_path, monkeypatch):
    """App apontando para uma pasta de modelos vazia — o estado real de hoje."""
    monkeypatch.setenv(main.VAR_MODELOS, str(tmp_path / "models"))
    with TestClient(main.app) as cliente:
        yield cliente


def _treinar_em(tmp_path: pathlib.Path) -> pathlib.Path:
    """Treina um modelo sintetico e devolve a pasta do artefato.

    AVISO: o modelo daqui vem dos alt texts INVENTADOS de `test_training`. Serve
    para exercitar o caminho "modelo carregado", e para nada mais.
    """
    dataset = escrever_dataset(tmp_path / "d.jsonl")
    modelos = tmp_path / "models"
    codigo = train.main(["--dataset", str(dataset), "--modelos", str(modelos),
                         "--relatorio", str(tmp_path / "rel.json"),
                         "--exportar-pior-que-baseline"])
    assert codigo == train.SAIDA_OK
    return modelos


@pytest.fixture
def com_modelo(tmp_path, monkeypatch):
    monkeypatch.setenv(main.VAR_MODELOS, str(_treinar_em(tmp_path)))
    with TestClient(main.app) as cliente:
        yield cliente


def pedido(alt: str = "IMG_0421.jpg") -> dict:
    return {"altText": alt, "contextoAntes": "", "contextoDepois": ""}


# ------------------------------------------------------------------ /health

def test_health_responde_200_mesmo_sem_modelo(sem_modelo):
    # 503 sem modelo faria a orquestracao reiniciar um container saudavel em
    # loop; o servico continua util pela heuristica.
    resposta = sem_modelo.get("/health")

    assert resposta.status_code == 200
    corpo = resposta.json()
    assert corpo["status"] == "ok"
    assert corpo["modeloCarregado"] is False
    assert corpo["modeloVersao"] is None
    assert "ausente" in corpo["motivo"]


def test_health_declara_o_modelo_quando_ele_existe(com_modelo):
    corpo = com_modelo.get("/health").json()

    assert corpo["modeloCarregado"] is True
    assert corpo["modeloVersao"] == train.VERSAO_DO_MODELO
    assert corpo["motivo"] is None


# ---------------------------------------------------------------- /v1/predict

def test_predict_sem_modelo_usa_heuristica_e_declara(sem_modelo):
    resposta = sem_modelo.post("/v1/predict", json=pedido("IMG_0421.jpg"))

    assert resposta.status_code == 200
    corpo = resposta.json()
    assert corpo["categoria"] == "INSUFFICIENT"
    assert corpo["usouHeuristica"] is True
    assert corpo["modeloVersao"] is None
    # Regra nao tem probabilidade. Um numero aqui faria o consumidor tratar
    # heuristica como modelo confiante.
    assert corpo["confianca"] is None


def test_predict_com_modelo_nao_marca_heuristica_e_traz_confianca(com_modelo):
    corpo = com_modelo.post("/v1/predict",
                            json=pedido("Grafico de barras")).json()

    assert corpo["usouHeuristica"] is False
    assert corpo["modeloVersao"] == train.VERSAO_DO_MODELO
    assert corpo["categoria"] in ("GOOD", "WEAK", "INSUFFICIENT")
    assert 0.0 <= corpo["confianca"] <= 1.0


def test_predict_aceita_contexto_em_camel_case(sem_modelo):
    resposta = sem_modelo.post("/v1/predict", json={
        "altText": "Selo", "contextoAntes": "antes", "contextoDepois": "depois"})

    assert resposta.status_code == 200


def test_contexto_e_opcional(sem_modelo):
    assert sem_modelo.post("/v1/predict", json={"altText": "Selo"}).status_code == 200


# ------------------------------------------------------------ entrada hostil

@pytest.mark.parametrize("corpo", [
    {},
    {"altText": ""},
    {"alt_text_errado": "x"},
    {"altText": "ok", "campoInventado": 1},
])
def test_pedido_fora_do_contrato_e_recusado_com_422(sem_modelo, corpo):
    assert sem_modelo.post("/v1/predict", json=corpo).status_code == 422


def test_alt_gigante_e_recusado_antes_de_encostar_no_modelo(sem_modelo):
    resposta = sem_modelo.post("/v1/predict", json={"altText": "a" * 5000})

    assert resposta.status_code == 422


def test_contexto_gigante_e_recusado(sem_modelo):
    resposta = sem_modelo.post("/v1/predict",
                               json={"altText": "ok", "contextoAntes": "a" * 5000})

    assert resposta.status_code == 422


# ----------------------------------------------------- artefato corrompido

def test_artefato_ilegivel_cai_para_heuristica(tmp_path):
    modelos = tmp_path / "models"
    modelos.mkdir()
    (modelos / train.NOME_DO_ARTEFATO).write_bytes(b"isto nao e um pickle")

    servico = ServicoDePredicao(modelos)

    assert servico.modelo_carregado is False
    assert "ilegivel" in (servico.motivo or "")
    assert servico.prever("imagem").usou_heuristica is True


def test_artefato_sem_as_chaves_obrigatorias_e_recusado(tmp_path):
    # Pior que ausente: passa no exists() e falharia na primeira predicao.
    modelos = tmp_path / "models"
    modelos.mkdir()
    joblib.dump({"versao_do_modelo": "0.1.0"}, modelos / train.NOME_DO_ARTEFATO)

    servico = ServicoDePredicao(modelos)

    assert servico.modelo_carregado is False
    assert "chaves" in (servico.motivo or "")


def test_artefato_sem_predict_e_recusado(tmp_path):
    modelos = tmp_path / "models"
    modelos.mkdir()
    joblib.dump({"versao_do_modelo": "0.1.0", "pipeline": {"nao": "sou modelo"},
                 "rotulos": ["GOOD"]}, modelos / train.NOME_DO_ARTEFATO)

    servico = ServicoDePredicao(modelos)

    assert servico.modelo_carregado is False
    assert "predict" in (servico.motivo or "")


def test_falha_na_predicao_degrada_a_chamada_e_nao_o_servico(tmp_path, monkeypatch):
    # Uma falha transitoria NAO pode desligar o modelo: antes, um unico blip
    # deixava o servico respondendo por heuristica ate o restart, e nada no
    # /health dizia que a causa ja tinha passado.
    servico = ServicoDePredicao(_treinar_em(tmp_path))
    assert servico.modelo_carregado is True
    original = servico._pipeline.predict_proba
    chamadas = {"n": 0}

    def falha_uma_vez(textos):
        chamadas["n"] += 1
        if chamadas["n"] == 1:
            raise RuntimeError("blip transitorio")
        return original(textos)

    monkeypatch.setattr(servico._pipeline, "predict_proba", falha_uma_vez)

    primeira = servico.prever("imagem")
    segunda = servico.prever("imagem")

    assert primeira.usou_heuristica is True
    assert primeira.confianca is None
    assert segunda.usou_heuristica is False, "o modelo tem que voltar sozinho"
    assert servico.modelo_carregado is True


def test_predicao_roda_o_pipeline_uma_vez_so(tmp_path, monkeypatch):
    # Vetorizar duas vezes o mesmo texto dobra o custo com um timeout de 1,5s do
    # outro lado, e abre espaco para os dois caminhos divergirem.
    servico = ServicoDePredicao(_treinar_em(tmp_path))
    contador = {"predict": 0, "proba": 0}
    p_orig = servico._pipeline.predict
    pp_orig = servico._pipeline.predict_proba

    monkeypatch.setattr(servico._pipeline, "predict",
                        lambda t: (contador.__setitem__("predict",
                                                        contador["predict"] + 1),
                                   p_orig(t))[1])
    monkeypatch.setattr(servico._pipeline, "predict_proba",
                        lambda t: (contador.__setitem__("proba",
                                                        contador["proba"] + 1),
                                   pp_orig(t))[1])

    servico.prever("um texto qualquer")

    assert contador["predict"] + contador["proba"] == 1


def test_categoria_e_a_classe_de_maior_probabilidade(tmp_path):
    servico = ServicoDePredicao(_treinar_em(tmp_path))

    resultado = servico.prever("Grafico de barras com a evolucao do orcamento")

    esperado = servico._pipeline.predict(
        ["Grafico de barras com a evolucao do orcamento"])[0]
    assert resultado.categoria == esperado


def test_predicoes_concorrentes_nao_desligam_o_modelo(tmp_path, monkeypatch):
    # Endpoint sincrono do FastAPI roda no threadpool: varias threads chamam
    # `prever` ao mesmo tempo. Falha em algumas nao pode derrubar as outras.
    import threading

    servico = ServicoDePredicao(_treinar_em(tmp_path))
    original = servico._pipeline.predict_proba

    def intermitente(textos):
        if threading.current_thread().name.endswith(("1", "3", "5")):
            raise RuntimeError("pressao")
        return original(textos)

    monkeypatch.setattr(servico._pipeline, "predict_proba", intermitente)
    erros: list[BaseException] = []

    def trabalhar() -> None:
        try:
            servico.prever("texto")
        except BaseException as e:  # noqa: BLE001
            erros.append(e)

    threads = [threading.Thread(target=trabalhar, name=f"t{i}") for i in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert erros == []
    assert servico.modelo_carregado is True


def test_endpoint_nao_devolve_5xx_com_artefato_quebrado(tmp_path, monkeypatch):
    # O contrato com o Java: ausencia de modelo nunca vira erro HTTP.
    modelos = tmp_path / "models"
    modelos.mkdir()
    (modelos / train.NOME_DO_ARTEFATO).write_bytes(b"lixo")
    monkeypatch.setenv(main.VAR_MODELOS, str(modelos))

    with TestClient(main.app) as cliente:
        resposta = cliente.post("/v1/predict", json=pedido())

    assert resposta.status_code == 200
    assert resposta.json()["usouHeuristica"] is True


# ------------------------------------------------------------------- lote

def test_lote_devolve_um_resultado_por_item_na_mesma_ordem(sem_modelo):
    # A ordem e o contrato: o pedido nao carrega identificador, entao e por ela
    # que o backend liga resultado a imagem.
    alts = ["IMG_0421.jpg", "Grafico de barras com a evolucao do orcamento",
            "Brasao"]
    resposta = sem_modelo.post("/v1/predict:batch",
                               json={"itens": [{"altText": a} for a in alts]})

    assert resposta.status_code == 200
    resultados = resposta.json()["resultados"]
    assert len(resultados) == len(alts)
    individuais = [sem_modelo.post("/v1/predict", json={"altText": a}).json()
                   for a in alts]
    assert [r["categoria"] for r in resultados] == \
        [r["categoria"] for r in individuais]


def test_lote_sem_modelo_marca_heuristica_em_todos(sem_modelo):
    resposta = sem_modelo.post("/v1/predict:batch", json={
        "itens": [{"altText": "imagem"}, {"altText": "foto"}]})

    assert all(r["usouHeuristica"] is True for r in resposta.json()["resultados"])
    assert all(r["confianca"] is None for r in resposta.json()["resultados"])


def test_lote_com_modelo_nao_marca_heuristica(com_modelo):
    resposta = com_modelo.post("/v1/predict:batch", json={
        "itens": [{"altText": "Fotografia da fachada do predio sede"},
                  {"altText": "IMG_0001.jpg"}]})

    resultados = resposta.json()["resultados"]
    assert all(r["usouHeuristica"] is False for r in resultados)
    assert all(0.0 <= r["confianca"] <= 1.0 for r in resultados)


def test_lote_e_individual_concordam_com_modelo(com_modelo):
    # Uma passada vetorizada nao pode dar resultado diferente de n passadas.
    alts = ["Diagrama da arquitetura com as tres camadas", "image1.png", "Selo"]
    lote = com_modelo.post("/v1/predict:batch",
                           json={"itens": [{"altText": a} for a in alts]}).json()
    um_a_um = [com_modelo.post("/v1/predict", json={"altText": a}).json()
               for a in alts]

    assert [r["categoria"] for r in lote["resultados"]] == \
        [r["categoria"] for r in um_a_um]


@pytest.mark.parametrize("corpo", [
    {"itens": []},
    {"itens": [{"altText": ""}]},
    {"itens": [{"altText": "ok"}] * (schemas.MAX_LOTE + 1)},
    {"itens": [{"altText": "ok", "campoDesconhecido": 1}]},
    {"lista": [{"altText": "ok"}]},
])
def test_lote_fora_do_contrato_e_recusado_com_422(sem_modelo, corpo):
    assert sem_modelo.post("/v1/predict:batch", json=corpo).status_code == 422


def test_lote_no_teto_e_aceito(sem_modelo):
    corpo = {"itens": [{"altText": "imagem"}] * schemas.MAX_LOTE}

    resposta = sem_modelo.post("/v1/predict:batch", json=corpo)

    assert resposta.status_code == 200
    assert len(resposta.json()["resultados"]) == schemas.MAX_LOTE


def test_lote_degrada_inteiro_quando_o_pipeline_falha(com_modelo, monkeypatch):
    # Falha na passada vetorizada nao pode virar 5xx: o lote inteiro cai para a
    # heuristica e cada item diz isso.
    servico = main.app.state.servico

    class PipelineQuebrado:
        classes_ = ["GOOD"]

        def predict_proba(self, textos):
            raise RuntimeError("estouro simulado")

    monkeypatch.setattr(servico, "_pipeline", PipelineQuebrado())
    resposta = com_modelo.post("/v1/predict:batch", json={
        "itens": [{"altText": "imagem"}, {"altText": "foto"}]})

    assert resposta.status_code == 200
    assert all(r["usouHeuristica"] is True for r in resposta.json()["resultados"])


def test_lote_vazio_no_servico_nao_chama_o_pipeline():
    servico = ServicoDePredicao(pathlib.Path("nao-existe"))

    assert servico.prever_lote([]) == []
