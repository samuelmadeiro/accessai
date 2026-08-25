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
from sklearn.model_selection import StratifiedGroupKFold

from accessai_ml.dataset import divisao
from accessai_ml.training import dados, metricas, modelo, train, validacao

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
                     rotular: bool = True,
                     provisorio: bool = True) -> pathlib.Path:
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
                # As duas colunas coexistem no dataset real: o coletor grava o
                # pre-rotulo, e `rotulo` so aparece depois da revisao humana.
                "rotulo_provisorio": rotulo if provisorio else None,
                "rotulo": rotulo if rotular else None,
            }, ensure_ascii=False) + "\n")
    return caminho


# ------------------------------------------------------------------ carga

def test_dataset_sem_rotulo_e_recusado(tmp_path):
    # Estado real do projeto hoje. Treinar assim produziria metrica de nada.
    caminho = escrever_dataset(tmp_path / "vazio.jsonl", rotular=False)

    with pytest.raises(dados.DatasetInvalidoError, match="NENHUMA com rotulo"):
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


# --------------------------------------------------- validacao cruzada

def _amostras(rotuladas: list[tuple[str, str]]) -> list[dados.Amostra]:
    return [dados.Amostra(id=f"s{i}", texto=texto, rotulo=rotulo,
                          grupo=divisao.chave_de_agrupamento(texto, "s.docx"),
                          divisao="treino")
            for i, (texto, rotulo) in enumerate(rotuladas)]


def test_validacao_cruzada_roda_e_resume_as_pastas():
    amostras = _amostras(SINTETICOS)

    resultado = validacao.validar(amostras, [BOM, FRACO, INSUFICIENTE], c=1.0,
                                  semente=42, min_df=modelo.MIN_DF_PADRAO)

    assert resultado["executada"] is True
    assert resultado["pastas_efetivas"] == validacao.PASTAS_PADRAO
    assert len(resultado["por_pasta"]) == validacao.PASTAS_PADRAO
    assert resultado["pastas_com_falha"] == []
    for chave in ("modelo", "baseline_majoritario", "baseline_heuristico"):
        resumo = resultado["resumo"][chave]
        assert 0.0 <= resumo["minimo"] <= resumo["media"] <= resumo["maximo"] <= 1.0
        assert resumo["desvio"] >= 0.0
    # Toda amostra e avaliada exatamente uma vez ao longo das pastas.
    assert sum(p["amostras_teste"] for p in resultado["por_pasta"]) == len(amostras)


def test_pastas_nunca_partem_um_grupo_entre_treino_e_teste():
    # A razao de ser do StratifiedGroupKFold: alt repetido nos dois lados
    # inflaria a macro-F1 sem o modelo ter aprendido nada.
    repetidos = SINTETICOS + [(texto, rotulo) for texto, rotulo in SINTETICOS[:6]]
    amostras = _amostras(repetidos)
    textos = [a.texto for a in amostras]
    rotulos = [a.rotulo for a in amostras]
    grupos = [a.grupo for a in amostras]

    divisor = StratifiedGroupKFold(n_splits=validacao.PASTAS_PADRAO, shuffle=True,
                                   random_state=42)
    for indices_treino, indices_teste in divisor.split(textos, rotulos, groups=grupos):
        assert not ({grupos[i] for i in indices_treino}
                    & {grupos[i] for i in indices_teste})


def test_validacao_cruzada_reduz_as_pastas_quando_a_classe_rara_nao_da():
    # Tres amostras na classe rara nao sustentam cinco pastas.
    amostras = _amostras([(t, r) for t, r in SINTETICOS if r != INSUFICIENTE]
                         + [(t, r) for t, r in SINTETICOS if r == INSUFICIENTE][:3])

    resultado = validacao.validar(amostras, [BOM, FRACO, INSUFICIENTE], c=1.0,
                                  semente=42, min_df=1)

    assert resultado["executada"] is True
    assert resultado["pastas_efetivas"] == 3
    assert "classe mais rara" in resultado["reducao"]


def test_validacao_cruzada_nao_roda_com_amostras_de_menos():
    amostras = _amostras([("Grafico de barras do orcamento anual", BOM),
                          ("imagem", INSUFICIENTE)])

    resultado = validacao.validar(amostras, [BOM, INSUFICIENTE], c=1.0, semente=42,
                                  min_df=1)

    assert resultado["executada"] is False
    assert "abaixo do minimo" in resultado["motivo"]


def test_pasta_que_falha_ao_ajustar_nao_derruba_o_treino(monkeypatch):
    # Diagnostico que aborta o treino inteiro e pior que diagnostico ausente.
    amostras = _amostras(SINTETICOS)
    chamadas = {"n": 0}
    original = modelo.construir_pipeline

    def falha_na_primeira(*args, **kwargs):
        chamadas["n"] += 1
        if chamadas["n"] == 1:
            raise ValueError("vocabulario vazio depois da poda")
        return original(*args, **kwargs)

    monkeypatch.setattr(modelo, "construir_pipeline", falha_na_primeira)
    resultado = validacao.validar(amostras, [BOM, FRACO, INSUFICIENTE], c=1.0,
                                  semente=42, min_df=modelo.MIN_DF_PADRAO)

    assert resultado["executada"] is True
    assert len(resultado["pastas_com_falha"]) == 1
    assert "vocabulario vazio" in resultado["pastas_com_falha"][0]["erro"]
    assert len(resultado["por_pasta"]) == validacao.PASTAS_PADRAO - 1


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
    assert conteudo["hiperparametros"]["min_df"] == modelo.MIN_DF_PADRAO
    assert conteudo["validacao_cruzada"]["executada"] is True
    assert conteudo["validacao_cruzada"]["estrategia"].startswith(
        "StratifiedGroupKFold")
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


# ------------------------------------------- sintetica na validacao cruzada

def test_sintetica_treina_mas_nao_e_avaliada_na_validacao_cruzada():
    # Sem esta remocao a macro-F1 sobe medindo string escrita no repositorio.
    reais = _amostras(SINTETICOS)
    geradas = [dados.Amostra(id=f"sintetico:{i}", texto=texto, rotulo=INSUFICIENTE,
                             grupo=f"gerada-{i}", divisao="treino", sintetica=True)
               for i, texto in enumerate(["IMG_0001.jpg", "a1b2c3d4.png",
                                          "spacer.gif", "banner", "avatar",
                                          "thumbnail", "1x1.gif", "logotipo"])]

    resultado = validacao.validar(reais + geradas, [BOM, FRACO, INSUFICIENTE],
                                  c=1.0, semente=42, min_df=1)

    assert resultado["executada"] is True
    assert resultado["sinteticas"] == len(geradas)
    assert sum(p["sinteticas_removidas_da_avaliacao"]
               for p in resultado["por_pasta"]) == len(geradas)
    # Toda amostra REAL continua sendo avaliada exatamente uma vez.
    assert sum(p["amostras_teste"] for p in resultado["por_pasta"]) == len(reais)


def test_carga_marca_a_amostra_sintetica(tmp_path):
    caminho = tmp_path / "d.jsonl"
    escrever_dataset(caminho)
    with caminho.open("a", encoding="utf-8", newline="\n") as arquivo:
        arquivo.write(json.dumps({
            "id": "sintetico:abc", "alt": "IMG_0001.jpg", "grupo": "img_0001.jpg",
            "divisao": "treino", "rotulo": INSUFICIENTE,
            "origem_do_dado": dados.ORIGEM_SINTETICA}, ensure_ascii=False) + "\n")

    conjuntos = dados.carregar(caminho)
    gerada = [a for a in conjuntos.treino if a.id == "sintetico:abc"]

    assert gerada and gerada[0].sintetica is True
    assert all(not a.sintetica for a in conjuntos.treino if a.id != "sintetico:abc")


# ------------------------------------------- rotulo de trabalho (Slice 4)

def test_carga_com_pre_rotulo_treina_o_que_o_humano_ainda_nao_rotulou(tmp_path):
    # Estado real do dataset: 749 linhas com `rotulo_provisorio`, zero com
    # `rotulo`. O caminho `humano` recusa; o `provisorio` roda e declara.
    caminho = escrever_dataset(tmp_path / "d.jsonl", rotular=False)

    with pytest.raises(dados.DatasetInvalidoError, match="NENHUMA com rotulo"):
        dados.carregar(caminho)

    conjuntos = dados.carregar(caminho, dados.ROTULO_PROVISORIO)
    assert conjuntos.total == len(SINTETICOS)


def test_recusa_do_caminho_humano_aponta_as_duas_saidas(tmp_path):
    caminho = escrever_dataset(tmp_path / "d.jsonl", rotular=False)

    with pytest.raises(dados.DatasetInvalidoError) as erro:
        dados.carregar(caminho)

    # A mensagem precisa dizer o que fazer, e as duas opcoes nao sao
    # equivalentes: uma produz metrica do ADR, a outra produz metrica de
    # imitacao. Quem le tem que sair sabendo disso.
    assert "accessai-revisar" in str(erro.value)
    assert "imitacao da heuristica" in str(erro.value)


def test_dataset_sem_nenhuma_das_duas_colunas_e_recusado(tmp_path):
    caminho = escrever_dataset(tmp_path / "d.jsonl", rotular=False,
                               provisorio=False)

    with pytest.raises(dados.DatasetInvalidoError,
                       match="NENHUMA com rotulo_provisorio"):
        dados.carregar(caminho, dados.ROTULO_PROVISORIO)


@pytest.mark.parametrize("invalido", ["llm", "heuristica", "", "HUMANO"])
def test_rotulo_de_trabalho_desconhecido_e_recusado(tmp_path, invalido):
    caminho = escrever_dataset(tmp_path / "d.jsonl")

    with pytest.raises(dados.DatasetInvalidoError, match="fora de"):
        dados.carregar(caminho, invalido)


@pytest.mark.parametrize(("de_trabalho", "vale"), [
    (dados.ROTULO_HUMANO, True),
    (dados.ROTULO_PROVISORIO, False),
])
def test_relatorio_declara_a_procedencia_do_rotulo(tmp_path, de_trabalho, vale):
    caminho = escrever_dataset(tmp_path / "d.jsonl")
    relatorio = tmp_path / "rel.json"

    train.main(["--dataset", str(caminho), "--modelos", str(tmp_path / "models"),
                "--relatorio", str(relatorio),
                "--rotulo-de-trabalho", de_trabalho,
                "--exportar-pior-que-baseline", "--exportar-sem-revisao"])

    bloco = json.loads(relatorio.read_text(encoding="utf-8"))["rotulo_de_trabalho"]
    assert bloco["origem"] == de_trabalho
    assert bloco["campo"] == dados.CAMPO_DO_ROTULO[de_trabalho]
    assert bloco["vale_para_o_adr0002"] is vale
    assert bloco["ressalva"]


def test_pre_rotulo_nao_exporta_artefato_mesmo_superando_o_baseline(tmp_path,
                                                                    capsys):
    # O caso perigoso: passa no criterio numerico e mesmo assim nao pode ser
    # servido. `models/` alimenta o ML Service, que passaria a responder
    # `usouHeuristica: false` para heuristica imitada.
    caminho = escrever_dataset(tmp_path / "d.jsonl")
    modelos = tmp_path / "models"

    codigo = train.main(["--dataset", str(caminho), "--modelos", str(modelos),
                         "--relatorio", str(tmp_path / "rel.json"),
                         "--rotulo-de-trabalho", dados.ROTULO_PROVISORIO,
                         "--exportar-pior-que-baseline"])

    assert codigo == train.SAIDA_OK
    assert not (modelos / train.NOME_DO_ARTEFATO).exists()
    erro = capsys.readouterr().err
    assert "artefato NAO exportado" in erro
    assert "usouHeuristica" in erro


def test_pre_rotulo_avisa_na_saida_de_erro(tmp_path, capsys):
    caminho = escrever_dataset(tmp_path / "d.jsonl")

    train.main(["--dataset", str(caminho), "--modelos", str(tmp_path / "models"),
                "--relatorio", str(tmp_path / "rel.json"),
                "--rotulo-de-trabalho", dados.ROTULO_PROVISORIO])

    erro = capsys.readouterr().err
    assert "PRE-ROTULO DETERMINISTICO" in erro
    assert "ADR 0002" in erro


def test_flag_explicita_libera_a_exportacao_do_pre_rotulo(tmp_path):
    caminho = escrever_dataset(tmp_path / "d.jsonl")
    modelos = tmp_path / "models"

    codigo = train.main(["--dataset", str(caminho), "--modelos", str(modelos),
                         "--relatorio", str(tmp_path / "rel.json"),
                         "--rotulo-de-trabalho", dados.ROTULO_PROVISORIO,
                         "--exportar-pior-que-baseline",
                         "--exportar-sem-revisao"])

    assert codigo == train.SAIDA_OK
    artefato = joblib.load(modelos / train.NOME_DO_ARTEFATO)
    # A procedencia viaja para dentro do .joblib: quem achar o arquivo solto
    # seis meses depois consegue descobrir sobre que rotulo ele foi treinado.
    procedencia = artefato["metadados"]["rotulo_de_trabalho"]
    assert procedencia["origem"] == dados.ROTULO_PROVISORIO
    assert procedencia["vale_para_o_adr0002"] is False


def test_caminho_humano_continua_sendo_o_padrao(tmp_path):
    caminho = escrever_dataset(tmp_path / "d.jsonl")
    modelos = tmp_path / "models"

    train.main(["--dataset", str(caminho), "--modelos", str(modelos),
                "--relatorio", str(tmp_path / "rel.json"),
                "--exportar-pior-que-baseline"])

    artefato = joblib.load(modelos / train.NOME_DO_ARTEFATO)
    assert artefato["metadados"]["rotulo_de_trabalho"]["origem"] == \
        dados.ROTULO_HUMANO


# --------------------------- recall da classe minoritaria (fase-0.md D2)

def _avaliacao(por_classe: dict) -> dict:
    return {"por_classe": por_classe}


def test_classe_minoritaria_sai_pelo_suporte_e_nao_por_nome_fixo():
    # Se a classe rara mudar quando o corpus crescer, um nome cravado no codigo
    # apontaria para a classe errada sem ninguem perceber.
    avaliacao = _avaliacao({
        BOM: {"precision": 0.9, "recall": 0.9, "f1-score": 0.9, "support": 80},
        FRACO: {"precision": 0.4, "recall": 0.3, "f1-score": 0.34, "support": 6},
        INSUFICIENTE: {"precision": 0.8, "recall": 0.7, "f1-score": 0.75,
                       "support": 40},
    })

    bloco = metricas.recall_da_classe_minoritaria(
        avaliacao, [BOM, FRACO, INSUFICIENTE])

    assert bloco["classe"] == FRACO
    assert bloco["suporte"] == 6
    assert bloco["avaliavel"] is True
    assert bloco["recall"] == pytest.approx(0.3)


@pytest.mark.parametrize("suporte", [0, 1, 2, 4])
def test_suporte_pequeno_marca_a_classe_como_nao_avaliavel(suporte):
    # O caso do dataset real: uma unica amostra INSUFFICIENT fora do treino.
    # Recall 0.0 ali significa "errou a unica que existia", nao "nao detecta".
    avaliacao = _avaliacao({
        BOM: {"precision": 0.9, "recall": 0.9, "f1-score": 0.9, "support": 79},
        FRACO: {"precision": 0.6, "recall": 0.7, "f1-score": 0.65, "support": 35},
        INSUFICIENTE: {"precision": 0.0, "recall": 0.0, "f1-score": 0.0,
                       "support": suporte},
    })

    bloco = metricas.recall_da_classe_minoritaria(
        avaliacao, [BOM, FRACO, INSUFICIENTE])

    assert bloco["classe"] == INSUFICIENTE
    assert bloco["avaliavel"] is False
    assert str(suporte) in bloco["motivo"]
    assert "ruido" in bloco["motivo"]


def test_suporte_no_limite_e_avaliavel():
    avaliacao = _avaliacao({
        BOM: {"precision": 0.9, "recall": 0.9, "f1-score": 0.9, "support": 50},
        INSUFICIENTE: {"precision": 0.5, "recall": 0.4, "f1-score": 0.44,
                       "support": metricas.MINIMO_PARA_MEDIR_CLASSE},
    })

    bloco = metricas.recall_da_classe_minoritaria(avaliacao, [BOM, INSUFICIENTE])

    assert bloco["avaliavel"] is True


def test_conjunto_sem_classe_conhecida_nao_estoura():
    bloco = metricas.recall_da_classe_minoritaria(_avaliacao({}), [BOM])

    assert bloco["avaliavel"] is False
    assert "nenhuma classe" in bloco["motivo"]


def test_veredito_carrega_a_classe_minoritaria(tmp_path):
    conjuntos = dados.carregar(escrever_dataset(tmp_path / "d.jsonl"))

    resultado = train.treinar(conjuntos, c=1.0, semente=42)
    bloco = resultado["veredito"]["classe_minoritaria"]

    assert bloco["classe"] in (BOM, FRACO, INSUFICIENTE)
    assert "suporte" in bloco
    assert "avaliavel" in bloco


def test_cli_avisa_quando_a_classe_minoritaria_nao_e_avaliavel(tmp_path, capsys):
    # Corpus com uma unica amostra da classe rara fora do treino — a forma exata
    # do dataset real.
    raras = [("IMG_0421.jpg", INSUFICIENTE), ("image1.png", INSUFICIENTE),
             ("figura", INSUFICIENTE), ("foto", INSUFICIENTE),
             ("imagem", INSUFICIENTE)]
    amostras = [*[(t, r) for t, r in SINTETICOS if r != INSUFICIENTE], *raras]
    caminho = escrever_dataset(tmp_path / "d.jsonl", amostras)

    train.main(["--dataset", str(caminho), "--modelos", str(tmp_path / "models"),
                "--relatorio", str(tmp_path / "rel.json"),
                "--exportar-pior-que-baseline"])

    erro = capsys.readouterr().err
    assert "classe minoritaria nao avaliavel" in erro


# ------------------ contrato da heuristica entre Python e Java (Slice 5)

GOLDEN = (pathlib.Path(__file__).resolve().parent.parent.parent
          / "docs" / "ml" / "heuristica-alt.golden.json")


def _golden() -> list[dict[str, str]]:
    return json.loads(GOLDEN.read_text(encoding="utf-8"))["casos"]


def test_golden_reproduz_a_heuristica_deste_modulo():
    # Este arquivo e o contrato com `HeuristicaDeAltLocal` do lado Java. Se ele
    # sair de sincronia com a implementacao daqui, o Java passa a concordar com
    # uma regra que nao existe mais — divergencia silenciosa, que e exatamente
    # o que o golden existe para impedir.
    heuristica = modelo.BaselineHeuristico()
    casos = _golden()

    assert casos, "golden vazio faria este teste passar sem provar nada"
    for caso in casos:
        assert heuristica.predict([caso["alt"]])[0] == caso["categoria"], (
            f"alt {caso['alt']!r}: regenerar com "
            "`python scripts/gerar_golden_heuristica.py`")


def test_golden_cobre_as_tres_classes():
    # Um corpus so de INSUFFICIENT passaria com uma implementacao que devolve
    # INSUFFICIENT para tudo, nos dois lados.
    assert {c["categoria"] for c in _golden()} == set(dados.ROTULOS_VALIDOS)


def test_golden_nao_tem_alt_repetido():
    alts = [c["alt"] for c in _golden()]
    assert len(alts) == len(set(alts))
