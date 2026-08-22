"""Pipeline de treino, exercitado com dados sinteticos.

AVISO DE PROCEDENCIA: os alt texts deste arquivo sao INVENTADOS. Existem para
provar que o fluxo roda do inicio ao fim — carregar, treinar, avaliar, exportar
— e nada mais. Nenhuma metrica daqui vale como resultado do modelo, e nenhum
artefato gerado aqui sai de `tmp_path`.

O dataset real tem zero amostras rotuladas: ver `docs/adr/0002-procedencia-do-dataset.md`.
"""

from __future__ import annotations

import json
import pathlib

import joblib
import pytest

from accessai_ml.dataset import divisao
from accessai_ml.training import dados, metricas, modelo, train

BOM = "GOOD"
FRACO = "WEAK"
INSUFICIENTE = "INSUFFICIENT"

# Textos escolhidos para que exista sinal aprendivel: descricoes longas contra
# rotulos genericos e nomes de arquivo. Sem sinal nenhum, o teste passaria a
# medir o acaso.
SINTETICOS: list[tuple[str, str]] = [
    ("Grafico de barras com a evolucao do orcamento entre 2020 e 2025", BOM),
    ("Mapa do Brasil com as regioes de atuacao do programa destacadas", BOM),
    ("Fotografia da fachada do predio sede visto da avenida principal", BOM),
    ("Fluxograma das etapas de habilitacao do pregao eletronico", BOM),
    ("Tabela comparativa dos indicadores de acessibilidade por orgao", BOM),
    ("Retrato do ministro durante a cerimonia de posse no auditorio", BOM),
    ("Diagrama da arquitetura do sistema com as tres camadas nomeadas", BOM),
    ("Brasao", FRACO),
    ("Logo institucional", FRACO),
    ("Assinatura", FRACO),
    ("Rodape", FRACO),
    ("Cabecalho do documento", FRACO),
    ("Selo", FRACO),
    ("Marca", FRACO),
    ("imagem", INSUFICIENTE),
    ("foto", INSUFICIENTE),
    ("IMG_0421.jpg", INSUFICIENTE),
    ("image1.png", INSUFICIENTE),
    ("clique aqui", INSUFICIENTE),
    ("figura", INSUFICIENTE),
    ("sem titulo", INSUFICIENTE),
]


def escrever_dataset(caminho: pathlib.Path,
                     amostras: list[tuple[str, str]] | None = None,
                     rotular: bool = True) -> pathlib.Path:
    """Escreve um JSONL no mesmo formato que `accessai_ml.dataset` produz."""
    caminho.parent.mkdir(parents=True, exist_ok=True)
    chaves = [divisao.chave_de_agrupamento(texto, "sintetico.docx")
              for texto, _ in (amostras or SINTETICOS)]
    particao = divisao.dividir(chaves)

    with caminho.open("w", encoding="utf-8", newline="\n") as arquivo:
        for indice, (texto, rotulo) in enumerate(amostras or SINTETICOS):
            grupo = divisao.chave_de_agrupamento(texto, "sintetico.docx")
            arquivo.write(json.dumps({
                "versao_do_formato": 2,
                "id": f"sintetico.docx#{indice}",
                "alt": texto,
                "tem_alt": True,
                "grupo": grupo,
                "divisao": particao.parte_de(grupo),
                "rotulo": rotulo if rotular else None,
            }, ensure_ascii=False) + "\n")
    return caminho


# ------------------------------------------------------------------ carga

def test_dataset_sem_rotulo_e_recusado(tmp_path):
    # Estado real do projeto hoje. Treinar assim produziria metrica de nada.
    caminho = escrever_dataset(tmp_path / "vazio.jsonl", rotular=False)

    with pytest.raises(dados.DatasetInvalidoError, match="NENHUMA rotulada"):
        dados.carregar(caminho)


def test_dataset_ausente_e_erro_explicito(tmp_path):
    with pytest.raises(dados.DatasetInvalidoError, match="dataset ausente"):
        dados.carregar(tmp_path / "nao-existe.jsonl")


def test_rotulo_desconhecido_interrompe(tmp_path):
    caminho = escrever_dataset(tmp_path / "d.jsonl",
                               [*SINTETICOS, ("qualquer coisa aqui", "OTIMO")])

    with pytest.raises(dados.DatasetInvalidoError, match="fora de"):
        dados.carregar(caminho)


def test_linha_rotulada_sem_alt_interrompe(tmp_path):
    caminho = tmp_path / "d.jsonl"
    caminho.write_text(json.dumps({
        "id": "x#0", "alt": "   ", "grupo": "g", "divisao": "treino",
        "rotulo": BOM}) + "\n", encoding="utf-8")

    with pytest.raises(dados.DatasetInvalidoError, match="sem alt"):
        dados.carregar(caminho)


def test_grupo_em_duas_partes_e_barrado_como_vazamento(tmp_path):
    caminho = tmp_path / "d.jsonl"
    linhas = [
        {"id": "a#0", "alt": "mesma frase repetida aqui", "grupo": "g1",
         "divisao": "treino", "rotulo": BOM},
        {"id": "b#0", "alt": "mesma frase repetida aqui", "grupo": "g1",
         "divisao": "teste", "rotulo": BOM},
    ]
    caminho.write_text("".join(json.dumps(linha) + "\n" for linha in linhas),
                       encoding="utf-8")

    with pytest.raises(dados.DatasetInvalidoError, match="vazamento"):
        dados.carregar(caminho)


def test_carga_separa_pelas_partes_da_divisao(tmp_path):
    conjuntos = dados.carregar(escrever_dataset(tmp_path / "d.jsonl"))

    assert conjuntos.total == len(SINTETICOS)
    assert conjuntos.treino
    assert all(a.divisao == "treino" for a in conjuntos.treino)
    assert all(a.divisao == "validacao" for a in conjuntos.validacao)


# ------------------------------------------------------------- heuristica

@pytest.mark.parametrize("texto,esperado", [
    ("IMG_0421.jpg", INSUFICIENTE),
    ("image1.png", INSUFICIENTE),
    ("---", INSUFICIENTE),
    ("imagem", INSUFICIENTE),
    ("Grafico de barras com a evolucao do orcamento anual", BOM),
    ("Brasao", FRACO),
])
def test_baseline_heuristico_classifica_os_casos_obvios(texto, esperado):
    assert modelo.BaselineHeuristico().predict([texto])[0] == esperado


# ---------------------------------------------------------- fluxo completo

def test_treino_roda_do_inicio_ao_fim(tmp_path):
    conjuntos = dados.carregar(escrever_dataset(tmp_path / "d.jsonl"))

    resultado = train.treinar(conjuntos, c=1.0, semente=42)

    assert resultado["rotulos"] == [BOM, FRACO, INSUFICIENTE]
    assert "treino" in resultado["avaliacoes"]
    for avaliacao in resultado["avaliacoes"].values():
        for chave in ("modelo", "baseline_majoritario", "baseline_heuristico"):
            metrica = avaliacao[chave]
            assert 0.0 <= metrica["macro"]["f1"] <= 1.0
            assert 0.0 <= metrica["ponderado"]["precision"] <= 1.0
            assert 0.0 <= metrica["ponderado"]["recall"] <= 1.0


def test_matriz_de_confusao_e_quadrada_e_conta_todas_as_amostras(tmp_path):
    conjuntos = dados.carregar(escrever_dataset(tmp_path / "d.jsonl"))

    resultado = train.treinar(conjuntos, c=1.0, semente=42)
    matriz = resultado["avaliacoes"]["treino"]["modelo"]["matriz_de_confusao"]
    linhas = matriz["linhas_sao_verdadeiro_colunas_sao_previsto"]

    assert len(linhas) == len(matriz["rotulos"])
    assert all(len(linha) == len(matriz["rotulos"]) for linha in linhas)
    assert sum(sum(linha) for linha in linhas) == len(conjuntos.treino)


def test_veredito_reporta_modelo_pior_que_baseline_como_tal():
    # CONTRIBUTING.md secao 7: modelo pior que baseline e reportado como tal.
    julgamento = metricas.veredito(0.30, 0.40, 0.35)

    assert julgamento["supera_baselines"] is False
    assert julgamento["frase"].startswith("MODELO INUTIL")


def test_veredito_recusa_modelo_que_so_empata_com_a_heuristica():
    julgamento = metricas.veredito(0.50, 0.20, 0.50)

    assert julgamento["supera_baselines"] is False
    assert "NAO SE JUSTIFICA" in julgamento["frase"]


def test_veredito_aprova_modelo_que_supera():
    julgamento = metricas.veredito(0.80, 0.30, 0.55)

    assert julgamento["supera_baselines"] is True
    assert julgamento["melhor_baseline"] == "heuristica"
    assert julgamento["ganho_sobre_melhor_baseline"] == pytest.approx(0.25)


# --------------------------------------------------------------------- CLI

def test_cli_recusa_dataset_sem_rotulo(tmp_path, capsys):
    caminho = escrever_dataset(tmp_path / "d.jsonl", rotular=False)

    codigo = train.main(["--dataset", str(caminho),
                         "--modelos", str(tmp_path / "models"),
                         "--relatorio", str(tmp_path / "rel.json")])

    assert codigo == train.SAIDA_DATASET_INVALIDO
    assert not (tmp_path / "models").exists()


def test_cli_escreve_relatorio_e_artefato(tmp_path):
    caminho = escrever_dataset(tmp_path / "d.jsonl")
    modelos = tmp_path / "models"
    relatorio = tmp_path / "training_report.json"

    codigo = train.main(["--dataset", str(caminho), "--modelos", str(modelos),
                         "--relatorio", str(relatorio),
                         "--exportar-pior-que-baseline"])

    assert codigo == train.SAIDA_OK
    conteudo = json.loads(relatorio.read_text(encoding="utf-8"))
    assert conteudo["versao_do_modelo"] == train.VERSAO_DO_MODELO
    assert conteudo["metrica_principal"] == "f1_macro"
    assert conteudo["hiperparametros"]["classificador"] == "LogisticRegression"
    assert conteudo["ambiente"]["scikit_learn"]
    assert (modelos / train.NOME_DO_ARTEFATO).exists()


def test_artefato_carrega_e_traz_metadados_e_versao(tmp_path):
    caminho = escrever_dataset(tmp_path / "d.jsonl")
    modelos = tmp_path / "models"

    train.main(["--dataset", str(caminho), "--modelos", str(modelos),
                "--relatorio", str(tmp_path / "rel.json"),
                "--exportar-pior-que-baseline"])
    artefato = joblib.load(modelos / train.NOME_DO_ARTEFATO)

    assert artefato["versao_do_modelo"] == train.VERSAO_DO_MODELO
    assert artefato["rotulos"] == [BOM, FRACO, INSUFICIENTE]
    assert artefato["metadados"]["hiperparametros"]["C"] == 1.0
    assert artefato["metadados"]["divisao"]["estrategia"].startswith("grupo por alt")
    # Binario sem procedencia e pior que nenhum: o artefato precisa dizer com
    # que versao de scikit-learn foi serializado.
    assert artefato["metadados"]["ambiente"]["scikit_learn"]

    previsto = artefato["pipeline"].predict(["IMG_0421.jpg"])
    assert previsto[0] in (BOM, FRACO, INSUFICIENTE)


def test_cli_nao_exporta_artefato_pior_que_baseline(tmp_path, monkeypatch):
    # A trava que impede um .joblib ruim de acabar servido em producao por
    # alguem que so viu o nome do arquivo.
    caminho = escrever_dataset(tmp_path / "d.jsonl")
    modelos = tmp_path / "models"
    monkeypatch.setattr(metricas, "f1_macro", lambda *_args, **_kwargs: 0.0)

    codigo = train.main(["--dataset", str(caminho), "--modelos", str(modelos),
                         "--relatorio", str(tmp_path / "rel.json")])

    assert codigo == train.SAIDA_PIOR_QUE_BASELINE
    assert not (modelos / train.NOME_DO_ARTEFATO).exists()
