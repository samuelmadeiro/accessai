"""Revisao humana dos pre-rotulos e a auditoria do ADR 0002 secao 4.

O kappa e conferido contra `sklearn.metrics.cohen_kappa_score`: a formula esta
implementada a mao no projeto — dependencia nova e ruido no lock — mas a
implementacao a mao so vale se alguem prova que ela bate com a de referencia.
"""

from __future__ import annotations

import json
import pathlib

import pytest
from sklearn.metrics import cohen_kappa_score

from accessai_ml.dataset import cli_revisao, revisao

BOM = "GOOD"
FRACO = "WEAK"
INSUFICIENTE = "INSUFFICIENT"


def linha(indice: int, alt: str, provisorio: str | None = BOM,
          rotulo: str | None = None, origem: str | None = None) -> dict:
    registro: dict = {
        "versao_do_formato": 2,
        "id": f"commons:{indice}",
        "alt": alt,
        "tem_alt": True,
        "grupo": alt.lower(),
        "divisao": "treino",
        "rotulo_provisorio": provisorio,
        "origem_do_rotulo": origem,
        "rotulo": rotulo,
    }
    return registro


def escrever_dataset(caminho: pathlib.Path, registros: list[dict]) -> pathlib.Path:
    caminho.parent.mkdir(parents=True, exist_ok=True)
    with caminho.open("w", encoding="utf-8", newline="\n") as arquivo:
        for registro in registros:
            arquivo.write(json.dumps(registro, ensure_ascii=False) + "\n")
    return caminho


def dataset_variado(por_classe: dict[str, int]) -> list[dict]:
    registros: list[dict] = []
    contador = 0
    for provisorio, quantidade in por_classe.items():
        for _ in range(quantidade):
            registros.append(linha(contador, f"texto alternativo numero {contador}",
                                   provisorio=provisorio))
            contador += 1
    return registros


# -------------------------------------------------------------------- kappa

@pytest.mark.parametrize("primeiro, segundo", [
    ([BOM, FRACO, INSUFICIENTE, BOM, FRACO], [BOM, FRACO, INSUFICIENTE, BOM, FRACO]),
    ([BOM, FRACO, INSUFICIENTE, BOM], [FRACO, BOM, BOM, INSUFICIENTE]),
    ([BOM] * 8 + [FRACO] * 2, [BOM] * 6 + [FRACO] * 4),
    ([BOM, BOM, FRACO, INSUFICIENTE, FRACO, BOM], [BOM, FRACO, FRACO, BOM, FRACO, BOM]),
    ([BOM, FRACO] * 10, [FRACO, BOM] * 10),
])
def test_kappa_bate_com_a_implementacao_de_referencia(primeiro, segundo):
    nosso = revisao.kappa_de_cohen(primeiro, segundo)
    referencia = cohen_kappa_score(primeiro, segundo, labels=list(revisao.ROTULOS))

    assert nosso == pytest.approx(float(referencia), abs=1e-9)


def test_kappa_de_concordancia_total_e_um():
    assert revisao.kappa_de_cohen([BOM, FRACO, INSUFICIENTE],
                                  [BOM, FRACO, INSUFICIENTE]) == 1.0


def test_kappa_de_discordancia_total_e_negativo():
    assert revisao.kappa_de_cohen([BOM, BOM, FRACO, FRACO],
                                  [FRACO, FRACO, BOM, BOM]) < 0.0


def test_kappa_com_uma_classe_so_dos_dois_lados_nao_divide_por_zero():
    # `pe == 1`: a formula dividiria por zero. Concordam em tudo, entao 1.0 —
    # e um `nan` aqui nem serializa em JSON.
    assert revisao.kappa_de_cohen([BOM] * 5, [BOM] * 5) == 1.0


def test_kappa_com_uma_classe_so_e_divergente_e_zero():
    assert revisao.kappa_de_cohen([BOM] * 3, [FRACO] * 3) == 0.0


def test_kappa_desconta_o_acaso_de_uma_classe_dominante():
    # O ponto de usar kappa e nao acuracia: um revisor que carimbasse GOOD em
    # tudo acertaria 90% e nao teria revisado nada. O kappa devolve 0.
    primeiro = [BOM] * 90 + [FRACO] * 10
    segundo = [BOM] * 100
    acuracia = sum(1 for a, b in zip(primeiro, segundo, strict=True) if a == b) / 100

    assert acuracia == pytest.approx(0.90)
    assert revisao.kappa_de_cohen(primeiro, segundo) == pytest.approx(0.0)


def test_kappa_recusa_listas_de_tamanhos_diferentes():
    with pytest.raises(revisao.RevisaoError, match="tamanhos diferentes"):
        revisao.kappa_de_cohen([BOM, FRACO], [BOM])


def test_kappa_recusa_lista_vazia():
    with pytest.raises(revisao.RevisaoError, match="sem nenhuma amostra"):
        revisao.kappa_de_cohen([], [])


@pytest.mark.parametrize("kappa, faixa", [
    (-0.1, "pior que o acaso"), (0.1, "insignificante"), (0.3, "fraca"),
    (0.5, "moderada"), (0.7, "substancial"), (0.9, "quase perfeita"),
])
def test_interpretacao_do_kappa(kappa, faixa):
    assert revisao.interpretar(kappa) == faixa


def test_matriz_de_confusao_conta_todas_as_amostras():
    primeiro = [BOM, BOM, FRACO, INSUFICIENTE]
    segundo = [BOM, FRACO, FRACO, BOM]

    matriz = revisao.matriz_de_confusao(primeiro, segundo)

    assert sum(sum(linha_) for linha_ in matriz) == 4
    assert matriz[0][0] == 1  # GOOD provisorio, GOOD humano
    assert matriz[0][1] == 1  # GOOD provisorio, WEAK humano


# --------------------------------------------------------------- amostragem

def test_amostragem_pega_a_cota_de_cada_classe():
    registros = dataset_variado({BOM: 200, FRACO: 100, INSUFICIENTE: 80})
    disponiveis = revisao.pendentes(registros)

    fila = revisao.amostrar_balanceado(disponiveis, por_classe=50)

    assert len(fila) == 150
    contagem = revisao.contar(a.rotulo_provisorio for a in fila)
    assert contagem == {BOM: 50, FRACO: 50, INSUFICIENTE: 50}


def test_classe_com_menos_que_a_cota_entrega_o_que_tem():
    # O caso normal com descricao publica: INSUFFICIENT e a classe rara.
    registros = dataset_variado({BOM: 200, FRACO: 100, INSUFICIENTE: 6})
    disponiveis = revisao.pendentes(registros)

    fila = revisao.amostrar_balanceado(disponiveis, por_classe=50)

    assert len(fila) == 106
    assert revisao.contar(a.rotulo_provisorio for a in fila)[INSUFICIENTE] == 6
    assert revisao.faltando_por_classe(disponiveis, 50) == {
        BOM: 0, FRACO: 0, INSUFICIENTE: 44}


def test_fila_nao_vem_agrupada_por_classe():
    # Cinquenta GOOD seguidos ensinam a sequencia ao revisor, e a concordancia
    # medida vira artefato da ordem de apresentacao.
    registros = dataset_variado({BOM: 50, FRACO: 50, INSUFICIENTE: 50})
    fila = revisao.amostrar_balanceado(revisao.pendentes(registros), por_classe=50)

    rotulos = [a.rotulo_provisorio for a in fila]
    # `strict=False`: as duas listas diferem em um item por construcao.
    pares = zip(rotulos, rotulos[1:], strict=False)
    blocos = sum(1 for anterior, atual in pares if anterior != atual)
    assert blocos > 50  # muito acima dos 2 de uma fila ordenada por classe


def test_mesma_semente_repete_a_fila():
    disponiveis = revisao.pendentes(dataset_variado({BOM: 40, FRACO: 40}))

    primeira = revisao.amostrar_balanceado(disponiveis, 20, semente=7)
    segunda = revisao.amostrar_balanceado(disponiveis, 20, semente=7)
    outra = revisao.amostrar_balanceado(disponiveis, 20, semente=8)

    assert [a.id for a in primeira] == [a.id for a in segunda]
    assert [a.id for a in primeira] != [a.id for a in outra]


def test_por_classe_invalido_e_recusado():
    with pytest.raises(revisao.RevisaoError, match="pelo menos 1"):
        revisao.amostrar_balanceado([], por_classe=0)


# ------------------------------------------------------------------ leitura

def test_pendentes_ignora_o_que_ja_foi_revisado():
    registros = [
        linha(1, "descricao boa e completa do grafico", provisorio=BOM),
        linha(2, "outra descricao", provisorio=FRACO, rotulo=BOM,
              origem=revisao.ORIGEM_HUMANA),
        linha(3, "sem pre-rotulo nenhum", provisorio=None),
        linha(4, "   ", provisorio=BOM),
    ]

    achadas = revisao.pendentes(registros)

    assert [a.id for a in achadas] == ["commons:1"]


def test_pre_rotulo_desconhecido_interrompe():
    with pytest.raises(revisao.RevisaoError, match="fora de"):
        revisao.pendentes([linha(1, "texto", provisorio="OTIMO")])


def test_decisoes_registradas_acumulam_entre_sessoes():
    # 150 amostras nao cabem numa sentada; o relatorio conta o arquivo inteiro.
    registros = [
        linha(1, "a", provisorio=BOM, rotulo=BOM, origem=revisao.ORIGEM_HUMANA),
        linha(2, "b", provisorio=FRACO, rotulo=BOM, origem=revisao.ORIGEM_HUMANA),
        linha(3, "c", provisorio=BOM, rotulo=BOM, origem="heuristica"),
        linha(4, "d", provisorio=BOM),
    ]

    achadas = revisao.decisoes_registradas(registros)

    assert [d.amostra.id for d in achadas] == ["commons:1", "commons:2"]
    assert [d.divergiu for d in achadas] == [False, True]


def test_dataset_ausente_e_erro_explicito(tmp_path):
    with pytest.raises(revisao.RevisaoError, match="dataset ausente"):
        revisao.carregar(tmp_path / "nao_existe.jsonl")


def test_linha_invalida_interrompe(tmp_path):
    caminho = tmp_path / "d.jsonl"
    caminho.write_text('{"id": 1}\nnao sou json\n', encoding="utf-8")

    with pytest.raises(revisao.RevisaoError, match="linha 2"):
        revisao.carregar(caminho)


# ----------------------------------------------------------------- gravacao

def test_gravacao_preserva_as_linhas_nao_revisadas(tmp_path):
    caminho = escrever_dataset(tmp_path / "d.jsonl", [
        linha(1, "primeira", provisorio=BOM),
        linha(2, "segunda", provisorio=FRACO),
        linha(3, "terceira", provisorio=INSUFICIENTE),
    ])
    registros = revisao.carregar(caminho)
    alvo = revisao.pendentes(registros)[1]

    revisao.aplicar(registros, [revisao.Decisao(amostra=alvo, rotulo=BOM)])
    revisao.gravar(caminho, registros)

    depois = revisao.carregar(caminho)
    assert len(depois) == 3
    assert depois[1]["rotulo"] == BOM
    assert depois[1]["origem_do_rotulo"] == revisao.ORIGEM_HUMANA
    assert depois[1]["data_revisao"]
    assert depois[0]["rotulo"] is None
    assert depois[2]["rotulo"] is None
    # Campos que a revisao nao toca continuam la.
    assert depois[1]["rotulo_provisorio"] == FRACO
    assert depois[1]["grupo"] == "segunda"


def test_gravacao_nao_deixa_temporario_para_tras(tmp_path):
    caminho = escrever_dataset(tmp_path / "d.jsonl", [linha(1, "unica")])
    registros = revisao.carregar(caminho)

    revisao.gravar(caminho, registros)

    assert list(tmp_path.iterdir()) == [caminho]


# ---------------------------------------------------------------- relatorio

def test_relatorio_traz_o_que_o_adr_pede(tmp_path):
    disponiveis = revisao.pendentes(dataset_variado({BOM: 60, FRACO: 60,
                                                     INSUFICIENTE: 60}))
    decisoes = [revisao.Decisao(amostra=a, rotulo=a.rotulo_provisorio)
                for a in disponiveis[:150]]

    relatorio = revisao.montar_relatorio(decisoes, 3, disponiveis, 50,
                                         tmp_path / "d.jsonl")

    assert relatorio["total_revisado"] == 150
    assert relatorio["distribuicao_rotulos"]["humano"]
    assert relatorio["taxa_correcao"] == 0.0
    assert relatorio["kappa_cohen"] == 1.0
    assert relatorio["atende_adr0002"] is True
    assert relatorio["veredito"].startswith("ATENDE")


def test_taxa_de_correcao_conta_divergencias():
    disponiveis = revisao.pendentes(dataset_variado({BOM: 10}))
    decisoes = [revisao.Decisao(amostra=a, rotulo=FRACO if i < 3 else BOM)
                for i, a in enumerate(disponiveis)]

    relatorio = revisao.montar_relatorio(decisoes, 0, disponiveis, 50,
                                         pathlib.Path("d.jsonl"))

    assert relatorio["divergencias"] == 3
    assert relatorio["taxa_correcao"] == pytest.approx(0.3)


def test_menos_de_150_nao_atende_mesmo_com_kappa_alto():
    disponiveis = revisao.pendentes(dataset_variado({BOM: 30, FRACO: 30}))
    decisoes = [revisao.Decisao(amostra=a, rotulo=a.rotulo_provisorio)
                for a in disponiveis]

    relatorio = revisao.montar_relatorio(decisoes, 0, disponiveis, 50,
                                         pathlib.Path("d.jsonl"))

    assert relatorio["kappa_cohen"] == 1.0
    assert relatorio["atende_adr0002"] is False
    assert relatorio["veredito"].startswith("INCOMPLETO")


def test_kappa_baixo_com_150_nao_atende_e_diz_por_que():
    disponiveis = revisao.pendentes(dataset_variado({BOM: 100, FRACO: 100}))
    # Metade das GOOD viradas: concordancia cai o bastante para reprovar.
    decisoes = [revisao.Decisao(amostra=a,
                                rotulo=FRACO if i % 2 else a.rotulo_provisorio)
                for i, a in enumerate(disponiveis[:160])]

    relatorio = revisao.montar_relatorio(decisoes, 0, disponiveis, 50,
                                         pathlib.Path("d.jsonl"))

    assert relatorio["kappa_cohen"] < revisao.KAPPA_MINIMO_DO_ADR
    assert relatorio["atende_adr0002"] is False
    assert "NAO pode ser promovido" in relatorio["veredito"]


def test_relatorio_sem_revisao_nao_estoura():
    relatorio = revisao.montar_relatorio([], 0, [], 50, pathlib.Path("d.jsonl"))

    assert relatorio["total_revisado"] == 0
    assert relatorio["kappa_cohen"] == 0.0
    assert relatorio["veredito"].startswith("SEM REVISAO")


# ---------------------------------------------------------------- laco da CLI

class Teclado:
    """Devolve as teclas na ordem; levanta EOFError quando acabam."""

    def __init__(self, teclas: list[str]) -> None:
        self.teclas = list(teclas)
        self.prompts: list[str] = []

    def __call__(self, prompt: str = "") -> str:
        self.prompts.append(prompt)
        if not self.teclas:
            raise EOFError
        return self.teclas.pop(0)


def _fila(quantidade: int) -> list[revisao.Amostra]:
    return revisao.pendentes(dataset_variado({BOM: quantidade}))


@pytest.mark.parametrize("tecla, esperado", [
    ("1", BOM), ("g", BOM), ("G", BOM),
    ("2", FRACO), ("w", FRACO),
    ("3", INSUFICIENTE), ("i", INSUFICIENTE),
])
def test_cada_atalho_grava_o_rotulo_certo(tecla, esperado):
    sessao = cli_revisao.revisar(_fila(1), ler=Teclado([tecla]), escrever=lambda _: None)

    assert [d.rotulo for d in sessao.decisoes] == [esperado]


def test_pular_nao_vira_decisao():
    sessao = cli_revisao.revisar(_fila(3), ler=Teclado(["1", "s", "2"]),
                                 escrever=lambda _: None)

    assert [d.rotulo for d in sessao.decisoes] == [BOM, FRACO]
    assert sessao.puladas == 1
    assert sessao.saiu_antes_do_fim is False


def test_q_encerra_e_preserva_o_que_ja_foi_decidido():
    sessao = cli_revisao.revisar(_fila(5), ler=Teclado(["1", "2", "q", "3"]),
                                 escrever=lambda _: None)

    assert [d.rotulo for d in sessao.decisoes] == [BOM, FRACO]
    assert sessao.saiu_antes_do_fim is True


def test_tecla_invalida_reexibe_a_ajuda_e_nao_avanca():
    saida: list[str] = []
    teclado = Teclado(["x", "", "9", "1"])

    sessao = cli_revisao.revisar(_fila(1), ler=teclado, escrever=saida.append)

    assert [d.rotulo for d in sessao.decisoes] == [BOM]
    assert "\n".join(saida).count("[1|g] GOOD") == 3


def test_ctrl_c_encerra_sem_perder_trabalho():
    class TecladoQueInterrompe(Teclado):
        def __call__(self, prompt: str = "") -> str:
            if not self.teclas:
                raise KeyboardInterrupt
            return super().__call__(prompt)

    sessao = cli_revisao.revisar(_fila(5), ler=TecladoQueInterrompe(["1", "2"]),
                                 escrever=lambda _: None)

    assert [d.rotulo for d in sessao.decisoes] == [BOM, FRACO]
    assert sessao.saiu_antes_do_fim is True


def test_fim_da_entrada_encerra_sem_perder_trabalho():
    sessao = cli_revisao.revisar(_fila(5), ler=Teclado(["1"]),
                                 escrever=lambda _: None)

    assert len(sessao.decisoes) == 1
    assert sessao.saiu_antes_do_fim is True


def test_pre_rotulo_fica_escondido_por_padrao():
    saida: list[str] = []
    cli_revisao.revisar(_fila(1), ler=Teclado(["1"]), escrever=saida.append)

    assert BOM not in "\n".join(saida).replace("[1|g] GOOD", "")


def test_flag_mostra_o_pre_rotulo_com_o_aviso():
    saida: list[str] = []
    cli_revisao.revisar(_fila(1), ler=Teclado(["1"]), escrever=saida.append,
                        mostrar_pre_rotulo=True)
    texto = "\n".join(saida)

    assert "pre-rotulo: GOOD" in texto
    assert "nao vale para o ADR" in texto


def test_alt_longo_cabe_na_moldura():
    amostra = revisao.Amostra(indice=0, id="x", alt="palavra " * 40,
                              rotulo_provisorio=BOM)

    desenho = cli_revisao.apresentar(amostra, 1, 1, False)

    assert all(len(linha_) <= cli_revisao.LARGURA
               for linha_ in desenho.splitlines())


def test_palavra_maior_que_a_moldura_nao_estoura_a_borda():
    amostra = revisao.Amostra(indice=0, id="x", alt="a" * 300,
                              rotulo_provisorio=BOM)

    desenho = cli_revisao.apresentar(amostra, 1, 1, False)

    assert all(len(linha_) <= cli_revisao.LARGURA
               for linha_ in desenho.splitlines())


# --------------------------------------------------------------- CLI ponta a ponta

def test_cli_revisa_grava_e_relata(tmp_path, monkeypatch, capsys):
    caminho = escrever_dataset(tmp_path / "alt_texts.jsonl",
                               dataset_variado({BOM: 2, FRACO: 2}))
    relatorio = tmp_path / "relatorio_revisao.json"
    monkeypatch.setattr("builtins.input", Teclado(["1", "1", "2", "s"]))

    codigo = cli_revisao.main(["--dataset", str(caminho),
                               "--relatorio", str(relatorio),
                               "--por-classe", "2"])

    assert codigo == cli_revisao.SAIDA_OK
    registros = revisao.carregar(caminho)
    revisados = [r for r in registros
                 if r["origem_do_rotulo"] == revisao.ORIGEM_HUMANA]
    assert len(revisados) == 3
    assert all(r["data_revisao"] for r in revisados)

    conteudo = json.loads(relatorio.read_text(encoding="utf-8"))
    assert conteudo["total_revisado"] == 3
    assert conteudo["puladas_na_sessao"] == 1
    assert conteudo["atende_adr0002"] is False
    assert "kappa de Cohen" in capsys.readouterr().out


def test_cli_soma_as_revisoes_de_sessoes_anteriores(tmp_path, monkeypatch):
    caminho = escrever_dataset(tmp_path / "alt_texts.jsonl", [
        linha(1, "ja revisada antes", provisorio=BOM, rotulo=BOM,
              origem=revisao.ORIGEM_HUMANA),
        linha(2, "pendente agora", provisorio=FRACO),
    ])
    relatorio = tmp_path / "rel.json"
    monkeypatch.setattr("builtins.input", Teclado(["2"]))

    cli_revisao.main(["--dataset", str(caminho), "--relatorio", str(relatorio),
                      "--por-classe", "5"])

    conteudo = json.loads(relatorio.read_text(encoding="utf-8"))
    assert conteudo["total_revisado"] == 2


def test_terminal_e_reconfigurado_para_utf8(monkeypatch):
    # Sem isso, um alt em japones mata a sessao no meio no Windows.
    chamadas: list[dict] = []

    class FluxoFalso:
        def reconfigure(self, **kwargs):
            chamadas.append(kwargs)

    monkeypatch.setattr(cli_revisao.sys, "stdout", FluxoFalso())
    monkeypatch.setattr(cli_revisao.sys, "stderr", FluxoFalso())
    cli_revisao.preparar_terminal()

    assert chamadas == [{"encoding": "utf-8", "errors": "replace"}] * 2


def test_fluxo_sem_reconfigure_nao_estoura(monkeypatch):
    monkeypatch.setattr(cli_revisao.sys, "stdout", object())
    monkeypatch.setattr(cli_revisao.sys, "stderr", object())

    cli_revisao.preparar_terminal()  # nao levanta


def test_excecao_no_meio_do_laco_nao_perde_o_ja_decidido(tmp_path, monkeypatch):
    caminho = escrever_dataset(tmp_path / "d.jsonl", dataset_variado({BOM: 4}))
    explodiu = {"n": 0}

    def escrever_que_explode(texto: str = "") -> None:
        explodiu["n"] += 1
        if explodiu["n"] > 6:
            raise RuntimeError("console morreu")

    monkeypatch.setattr("builtins.input", Teclado(["1", "1", "1", "1"]))
    monkeypatch.setattr("builtins.print", escrever_que_explode)

    with pytest.raises(RuntimeError, match="console morreu"):
        cli_revisao.main(["--dataset", str(caminho),
                          "--relatorio", str(tmp_path / "rel.json"),
                          "--por-classe", "4"])

    revisados = [r for r in revisao.carregar(caminho)
                 if r["origem_do_rotulo"] == revisao.ORIGEM_HUMANA]
    assert revisados  # o trabalho ate a explosao esta no disco


def test_cli_recusa_dataset_ausente(tmp_path, capsys):
    codigo = cli_revisao.main(["--dataset", str(tmp_path / "nao_existe.jsonl")])

    assert codigo == cli_revisao.SAIDA_DATASET_INVALIDO
    assert "dataset invalido" in capsys.readouterr().err


def test_cli_sem_pendentes_nem_revisoes_avisa_e_sai(tmp_path, capsys):
    caminho = escrever_dataset(tmp_path / "d.jsonl",
                               [linha(1, "sem pre-rotulo", provisorio=None)])

    codigo = cli_revisao.main(["--dataset", str(caminho)])

    assert codigo == cli_revisao.SAIDA_SEM_PENDENTE
    assert "nenhuma amostra pendente" in capsys.readouterr().err


def test_cli_sem_pendentes_ainda_reemite_o_relatorio(tmp_path):
    # Tudo revisado: a auditoria continua disponivel sem precisar revisar de novo.
    caminho = escrever_dataset(tmp_path / "d.jsonl", [
        linha(1, "a", provisorio=BOM, rotulo=BOM, origem=revisao.ORIGEM_HUMANA),
        linha(2, "b", provisorio=FRACO, rotulo=FRACO, origem=revisao.ORIGEM_HUMANA),
    ])
    relatorio = tmp_path / "rel.json"

    codigo = cli_revisao.main(["--dataset", str(caminho),
                               "--relatorio", str(relatorio)])

    assert codigo == cli_revisao.SAIDA_OK
    assert json.loads(relatorio.read_text(encoding="utf-8"))["total_revisado"] == 2


def test_dataset_revisado_e_lido_pelo_carregador_do_treino(tmp_path, monkeypatch):
    # A prova que importa: o arquivo que sai daqui passa pelo mesmo
    # `dados.carregar` do `train.py`, com as travas dele ligadas.
    from accessai_ml.dataset import divisao
    from accessai_ml.training import dados

    registros = []
    for indice in range(24):
        provisorio = (BOM, FRACO, INSUFICIENTE)[indice % 3]
        alt = f"texto alternativo distinto numero {indice}"
        grupo = divisao.chave_de_agrupamento(alt, f"commons:{indice}")
        registros.append({**linha(indice, alt, provisorio=provisorio),
                          "grupo": grupo,
                          "divisao": divisao.dividir([grupo]).parte_de(grupo)})
    caminho = escrever_dataset(tmp_path / "alt_texts.jsonl", registros)
    monkeypatch.setattr("builtins.input", Teclado(["1", "2", "3"] * 8))

    cli_revisao.main(["--dataset", str(caminho),
                      "--relatorio", str(tmp_path / "rel.json"),
                      "--por-classe", "8"])

    conjuntos = dados.carregar(caminho)
    assert conjuntos.total == 24
