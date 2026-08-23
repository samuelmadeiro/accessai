"""Testes da auditoria de slices.

O ponto desta suite nao e provar que o auditor aprova o dataset bom — isso ele
faz rodando uma vez. E provar que ele REPROVA cada estrago que ele diz pegar:
contagem errada, id repetido, `SUFFICIENT` sobrevivente, e principalmente uma
`validacao` que devolveu a amostra sintetica para o lado avaliado. Auditor que
so foi exercitado contra o caso bom nao e auditor, e otimismo com nome tecnico.
"""

from __future__ import annotations

import json
import pathlib
from typing import Any

import pytest
from sklearn.model_selection import StratifiedGroupKFold

from accessai_ml.auditoria import auditar_slices as auditoria
from accessai_ml.training import dados, validacao

BOM = "GOOD"
FRACO = "WEAK"
INSUFICIENTE = "INSUFFICIENT"

POR_CLASSE = 6
COTA = 2


# ------------------------------------------------------------------ montagem


def linha(**campos: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "versao_do_formato": 2,
        "id": "commons:0",
        "arquivo": "File:Exemplo.png",
        "fonte": "wikimedia-commons",
        "alt": "descricao",
        "tem_alt": True,
        "grupo": "descricao",
        "divisao": "treino",
        "rotulo_provisorio": BOM,
        "origem_do_rotulo": None,
        "rotulo": None,
    }
    base.update(campos)
    return base


def _texto(rotulo: str, indice: int) -> str:
    """Texto por classe com vocabulario compartilhado dentro da classe.

    Sem palavra em comum o TF-IDF poda tudo e a validacao cruzada devolve
    "nenhuma pasta ajustou" — o teste passaria por NAO_AVALIAVEL sem ter medido
    o isolamento, que e justamente o que ele existe para medir.
    """
    if rotulo == BOM:
        return f"fotografia colorida de uma praca publica com pessoas numero {indice}"
    if rotulo == FRACO:
        return f"imagem generica sem detalhe numero {indice}"
    return f"IMG_{indice:04d}.jpg"


def corpus_bom() -> list[dict[str, Any]]:
    """Um dataset minimo que passa em todos os eixos.

    Tres classes cheias para a cota da fila, uma sintetica presa ao treino, e
    duas linhas governamentais sem alt — a mesma forma do dataset de producao,
    em escala que roda em segundos.
    """
    registros: list[dict[str, Any]] = []
    for rotulo in (BOM, FRACO, INSUFICIENTE):
        for indice in range(POR_CLASSE):
            alt = _texto(rotulo, indice)
            parte = ("treino" if indice < POR_CLASSE - 2
                     else ("validacao" if indice == POR_CLASSE - 2 else "teste"))
            registros.append(linha(id=f"commons:{rotulo}:{indice}", alt=alt,
                                   grupo=alt.casefold(), divisao=parte,
                                   rotulo_provisorio=rotulo))
    for indice in range(2):
        alt = f"DSC_{indice:04d}.jpg"
        registros.append(linha(
            id=f"sintetico:{indice}", arquivo="gerador_insufficient",
            fonte="sintetico", origem_do_dado="sintetico_fallback", alt=alt,
            grupo=alt.casefold(), divisao="treino", rotulo_provisorio=INSUFICIENTE))
    for indice in range(2):
        registros.append({
            "versao_do_formato": 2, "id": f"gov:{indice}",
            "arquivo": f"0{indice}-edital.docx", "alt": "", "tem_alt": False,
            "grupo": f"__sem_alt__0{indice}-edital.docx", "divisao": "treino",
            "rotulo": None,
        })
    return registros


def escrever(caminho: pathlib.Path, registros: list[dict[str, Any]]) -> pathlib.Path:
    caminho.parent.mkdir(parents=True, exist_ok=True)
    with caminho.open("w", encoding="utf-8", newline="\n") as arquivo:
        for registro in registros:
            arquivo.write(json.dumps(registro, ensure_ascii=False) + "\n")
    return caminho


def esperado_do_corpus(**ajustes: Any) -> auditoria.Esperado:
    padrao: dict[str, Any] = {
        "total": POR_CLASSE * 3 + 4,
        "governamentais": 2,
        "commons": POR_CLASSE * 3,
        "sinteticas": 2,
        "por_classe_na_fila": COTA,
        "fila": COTA * 3,
    }
    padrao.update(ajustes)
    return auditoria.Esperado(**padrao)


def auditar(caminho: pathlib.Path, **ajustes: Any) -> dict[str, Any]:
    return auditoria.auditar(caminho, esperado_do_corpus(),
                             min_df=1, pastas=2, **ajustes)


def status_de(relatorio: dict[str, Any], prefixo: str) -> list[str]:
    return [v["status"] for v in relatorio["verificacoes"]
            if v["nome"].startswith(prefixo)]


@pytest.fixture
def dataset_bom(tmp_path: pathlib.Path) -> pathlib.Path:
    return escrever(tmp_path / "data" / "alt_texts.jsonl", corpus_bom())


# ----------------------------------------------------------- leitura em fluxo


def test_leitura_e_preguicosa_e_pula_linha_em_branco(tmp_path: pathlib.Path) -> None:
    caminho = tmp_path / "d.jsonl"
    caminho.write_text(json.dumps(linha()) + "\n\n" + json.dumps(linha(id="b")) + "\n",
                       encoding="utf-8")
    fluxo = auditoria.ler_em_fluxo(caminho)
    # O gerador nao pode ter lido nada antes do primeiro `next`: essa e a
    # diferenca entre percorrer o arquivo e carrega-lo.
    assert next(fluxo)[0] == 1
    assert [numero for numero, _ in fluxo] == [3]


@pytest.mark.parametrize(("campos", "esperado"), [
    ({"origem_do_dado": "sintetico_fallback"}, auditoria.SLICE_SINTETICO),
    ({"fonte": "sintetico"}, auditoria.SLICE_SINTETICO),
    ({"fonte": "wikimedia-commons"}, auditoria.SLICE_COMMONS),
    ({"fonte": None, "origem_do_dado": "coletado"}, auditoria.SLICE_WEB),
    ({"fonte": None, "arquivo": "edital.docx"}, auditoria.SLICE_GOVERNAMENTAL),
    ({"fonte": None, "arquivo": "sem-extensao"}, auditoria.SLICE_DESCONHECIDO),
])
def test_classificacao_de_slice(campos: dict[str, Any], esperado: str) -> None:
    assert auditoria.classificar_slice(linha(**campos)) == esperado


# ---------------------------------------------------------------- caso bom


def test_corpus_integro_passa_em_todos_os_eixos(dataset_bom: pathlib.Path) -> None:
    relatorio = auditar(dataset_bom)
    assert relatorio["falhas"] == []
    assert relatorio["pronto_para_revisao_humana"] is True
    assert relatorio["veredito"].startswith("PRONTO")
    assert {v["eixo"] for v in relatorio["verificacoes"]} == {"A", "B", "C", "D", "E"}


def test_cli_devolve_zero_e_grava_o_relatorio(dataset_bom: pathlib.Path,
                                              tmp_path: pathlib.Path,
                                              capsys: pytest.CaptureFixture[str]
                                              ) -> None:
    destino = tmp_path / "saida" / auditoria.NOME_DO_RELATORIO
    codigo = auditoria.main([
        "--dataset", str(dataset_bom), "--relatorio", str(destino),
        "--total", str(POR_CLASSE * 3 + 4), "--governamentais", "2",
        "--commons", str(POR_CLASSE * 3), "--sinteticas", "2",
        "--por-classe", str(COTA), "--min-df", "1", "--pastas", "2"])
    assert codigo == auditoria.SAIDA_OK
    gravado = json.loads(destino.read_text(encoding="utf-8"))
    assert gravado["pronto_para_revisao_humana"] is True
    saida = capsys.readouterr().out
    assert "VEREDITO GERAL DE INTEGRIDADE" in saida
    assert "Slice/Origem" in saida


# ------------------------------------------------------- eixo A: contagens


@pytest.mark.parametrize(("ajuste", "invariante"), [
    ({"total": 999}, "A4 total de linhas"),
    ({"governamentais": 7}, "A1 slice governamental (.docx): total"),
    ({"commons": 3}, "A2 slice Commons: total"),
    ({"sinteticas": 44}, "A3 slice sintetico: total"),
])
def test_contagem_divergente_reprova(dataset_bom: pathlib.Path,
                                     ajuste: dict[str, Any],
                                     invariante: str) -> None:
    relatorio = auditoria.auditar(dataset_bom, esperado_do_corpus(**ajuste),
                                  min_df=1, pastas=2, com_dinamico=False)
    assert invariante in relatorio["falhas"]
    assert relatorio["pronto_para_revisao_humana"] is False


def test_id_duplicado_reprova(tmp_path: pathlib.Path) -> None:
    registros = corpus_bom()
    registros.append(linha(id=registros[0]["id"], alt="outro alt qualquer",
                           grupo="outro alt qualquer"))
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(caminho, esperado_do_corpus(total=len(registros)),
                                  min_df=1, pastas=2, com_dinamico=False)
    assert "A4 ids duplicados" in relatorio["falhas"]


def test_alt_normalizado_duplicado_reprova(tmp_path: pathlib.Path) -> None:
    registros = corpus_bom()
    original = registros[0]["alt"]
    registros.append(linha(id="commons:copia", alt=f"  {original.upper()}  ",
                           grupo="grupo-diferente"))
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(caminho, esperado_do_corpus(total=len(registros)),
                                  min_df=1, pastas=2, com_dinamico=False)
    assert "A4 alt normalizado duplicado" in relatorio["falhas"]


def test_alt_vazio_repetido_nao_conta_como_duplicata(dataset_bom: pathlib.Path) -> None:
    # As duas linhas governamentais tem `alt: ""`. Sao o achado do ADR, nao
    # conteudo repetido — contar como duplicata reprovaria o dataset por existir.
    relatorio = auditar(dataset_bom, com_dinamico=False)
    assert "A4 alt normalizado duplicado" not in relatorio["falhas"]


def test_sintetica_fora_de_insufficient_reprova(tmp_path: pathlib.Path) -> None:
    registros = corpus_bom()
    for registro in registros:
        if registro.get("origem_do_dado") == "sintetico_fallback":
            registro["rotulo_provisorio"] = BOM
            break
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(caminho, esperado_do_corpus(), min_df=1, pastas=2,
                                  com_dinamico=False)
    assert "A3 sintetico: 100% em INSUFFICIENT" in relatorio["falhas"]


def test_linha_sem_proveniencia_reprova(tmp_path: pathlib.Path) -> None:
    registros = corpus_bom()
    registros.append(linha(id="orfa", fonte=None, arquivo="sem-extensao",
                           alt="alt sem origem declarada",
                           grupo="alt sem origem declarada"))
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(caminho, esperado_do_corpus(total=len(registros)),
                                  min_df=1, pastas=2, com_dinamico=False)
    assert "A4 linhas sem slice reconhecido" in relatorio["falhas"]


# ------------------------------------------------------- eixo D: esquema


@pytest.mark.parametrize("campo", ["rotulo", "rotulo_provisorio"])
def test_sufficient_aposentado_reprova(tmp_path: pathlib.Path, campo: str) -> None:
    registros = corpus_bom()
    registros[0][campo] = "SUFFICIENT"
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(caminho, esperado_do_corpus(), min_df=1, pastas=2,
                                  com_dinamico=False)
    assert "D1 rotulos dentro do enum canonico" in relatorio["falhas"]
    assert "D1 nenhum SUFFICIENT sobrevivente" in relatorio["falhas"]


@pytest.mark.parametrize(("campo", "valor", "invariante"), [
    ("origem_do_rotulo", "llm", "D2 origem_do_rotulo no vocabulario permitido"),
    ("origem_do_rotulo", "chute", "D2 origem_do_rotulo no vocabulario permitido"),
    ("divisao", "producao", "D2 divisao dentro das partes conhecidas"),
])
def test_vocabulario_fora_do_contrato_reprova(tmp_path: pathlib.Path, campo: str,
                                              valor: str, invariante: str) -> None:
    registros = corpus_bom()
    registros[0][campo] = valor
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(caminho, esperado_do_corpus(), min_df=1, pastas=2,
                                  com_dinamico=False)
    assert invariante in relatorio["falhas"]


@pytest.mark.parametrize("origem", ["heuristica", "humano", "sintetico_fallback",
                                    "documentos_governamentais", None])
def test_origem_do_rotulo_permitida_nao_reprova(tmp_path: pathlib.Path,
                                                origem: str | None) -> None:
    registros = corpus_bom()
    registros[0]["origem_do_rotulo"] = origem
    if origem is not None:
        registros[0]["rotulo"] = registros[0]["rotulo_provisorio"]
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(caminho, esperado_do_corpus(), min_df=1, pastas=2,
                                  com_dinamico=False)
    assert "D2 origem_do_rotulo no vocabulario permitido" not in relatorio["falhas"]


def test_origem_do_dado_ausente_e_aviso_e_nao_falha(dataset_bom: pathlib.Path) -> None:
    relatorio = auditar(dataset_bom, com_dinamico=False)
    assert status_de(relatorio, "D3 origem_do_dado") == [auditoria.AVISO]
    assert relatorio["falhas"] == []
    assert "aviso" in relatorio["veredito"]


# ------------------------------------------------- arquivo corrompido


@pytest.mark.parametrize("conteudo", [
    '{"id": "a"\n',
    '{"id": "a"}\nnao e json\n',
    '["lista", "no", "lugar", "do", "objeto"]\n',
])
def test_jsonl_corrompido_vira_saida_um(tmp_path: pathlib.Path, conteudo: str,
                                        capsys: pytest.CaptureFixture[str]) -> None:
    caminho = tmp_path / "quebrado.jsonl"
    caminho.write_text(conteudo, encoding="utf-8")
    codigo = auditoria.main(["--dataset", str(caminho),
                             "--relatorio", str(tmp_path / "r.json"),
                             "--sem-dinamico"])
    assert codigo == auditoria.SAIDA_DIVERGENCIA
    assert "dataset ilegivel" in capsys.readouterr().err


def test_dataset_ausente_vira_saida_um(tmp_path: pathlib.Path,
                                       capsys: pytest.CaptureFixture[str]) -> None:
    codigo = auditoria.main(["--dataset", str(tmp_path / "nao-existe.jsonl"),
                             "--relatorio", str(tmp_path / "r.json")])
    assert codigo == auditoria.SAIDA_DIVERGENCIA
    assert "dataset ausente" in capsys.readouterr().err


# --------------------------------------------- eixo B: contaminacao


def _validacao_contaminada(sem_filtro: bool) -> Any:
    """Uma `validar` que devolve o formato do modulo real, com ou sem o filtro.

    Nao chama o scikit-learn: o que este auditor confere no Eixo B e a
    COMPOSICAO das pastas, e ajustar tres modelos por pasta so para chegar num
    numero que o teste ignora deixaria a suite lenta sem provar nada a mais.
    """

    def validar(amostras: list[dados.Amostra], rotulos: list[str], c: float,
                semente: int, min_df: int, pastas: int = 5) -> dict[str, Any]:
        divisor = StratifiedGroupKFold(n_splits=pastas, shuffle=True,
                                       random_state=semente)
        por_pasta: list[dict[str, Any]] = []
        for numero, (_, teste) in enumerate(
                divisor.split([a.texto for a in amostras],
                              [a.rotulo for a in amostras],
                              groups=[a.grupo for a in amostras]), start=1):
            avaliaveis = ([i for i in teste] if sem_filtro
                          else [i for i in teste if not amostras[i].sintetica])
            por_pasta.append({
                "pasta": numero,
                "amostras_teste": len(avaliaveis),
                "sinteticas_removidas_da_avaliacao": len(teste) - len(avaliaveis),
                "f1_macro_modelo": 0.709 if sem_filtro else 0.508,
            })
        media = 0.709 if sem_filtro else 0.508
        return {"executada": True, "por_pasta": por_pasta,
                "resumo": {"modelo": {"media": media, "desvio": 0.098}}}

    return validar


def test_isolamento_da_sintetica_passa_com_a_validacao_real(
        dataset_bom: pathlib.Path) -> None:
    relatorio = auditar(dataset_bom)
    assert status_de(relatorio, "B2 isolamento") == [auditoria.PASSOU]
    assert status_de(relatorio, "B1 sinteticas marcadas") == [auditoria.PASSOU]


def test_sintetica_no_lado_avaliado_e_detectada(dataset_bom: pathlib.Path,
                                                monkeypatch: pytest.MonkeyPatch
                                                ) -> None:
    monkeypatch.setattr(validacao, "validar", _validacao_contaminada(sem_filtro=True))
    relatorio = auditar(dataset_bom)
    assert ("B2 isolamento da sintetica no lado avaliado de cada pasta"
            in relatorio["falhas"])
    assert relatorio["pronto_para_revisao_humana"] is False
    assert relatorio["veredito"].startswith("NAO PRONTO")


def test_validacao_com_filtro_intacto_nao_e_acusada(dataset_bom: pathlib.Path,
                                                    monkeypatch: pytest.MonkeyPatch
                                                    ) -> None:
    # O contraponto do teste acima: a mesma dublê, com o filtro no lugar, tem de
    # passar. Sem este par, um auditor que reprovasse tudo pareceria correto.
    monkeypatch.setattr(validacao, "validar", _validacao_contaminada(sem_filtro=False))
    relatorio = auditar(dataset_bom)
    assert relatorio["falhas"] == []


def test_macro_f1_no_valor_contaminado_reprova(dataset_bom: pathlib.Path,
                                               monkeypatch: pytest.MonkeyPatch
                                               ) -> None:
    monkeypatch.setattr(validacao, "validar", _validacao_contaminada(sem_filtro=True))
    relatorio = auditar(dataset_bom)
    assert "B3 macro-F1 fora do valor contaminado conhecido" in relatorio["falhas"]


def test_cli_com_contaminacao_devolve_saida_um(dataset_bom: pathlib.Path,
                                               tmp_path: pathlib.Path,
                                               monkeypatch: pytest.MonkeyPatch
                                               ) -> None:
    monkeypatch.setattr(validacao, "validar", _validacao_contaminada(sem_filtro=True))
    codigo = auditoria.main([
        "--dataset", str(dataset_bom), "--relatorio", str(tmp_path / "r.json"),
        "--total", str(POR_CLASSE * 3 + 4), "--governamentais", "2",
        "--commons", str(POR_CLASSE * 3), "--sinteticas", "2",
        "--por-classe", str(COTA), "--min-df", "1", "--pastas", "2"])
    assert codigo == auditoria.SAIDA_DIVERGENCIA


def test_sintetica_em_validacao_ou_teste_reprova(tmp_path: pathlib.Path) -> None:
    registros = corpus_bom()
    for registro in registros:
        if registro.get("origem_do_dado") == "sintetico_fallback":
            registro["divisao"] = "teste"
            break
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(caminho, esperado_do_corpus(), min_df=1, pastas=2)
    assert "B2 sintetica presa a divisao de treino no arquivo" in relatorio["falhas"]


def test_sintetica_sem_marca_reprova(tmp_path: pathlib.Path) -> None:
    registros = corpus_bom()
    for registro in registros:
        if registro.get("origem_do_dado") == "sintetico_fallback":
            registro["origem_do_dado"] = "coletado"
            break
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(caminho, esperado_do_corpus(), min_df=1, pastas=2,
                                  com_dinamico=False)
    assert "A3 sintetico: marcado com origem_do_dado" in relatorio["falhas"]


def test_pastas_esperadas_isola_toda_sintetica(dataset_bom: pathlib.Path) -> None:
    corpus = auditoria.ler_corpus(dataset_bom)
    selecionadas = [a for a in corpus.treinaveis if a.divisao in ("treino", "validacao")]
    esperadas = auditoria.pastas_esperadas(selecionadas, semente=42, pastas=2)
    assert esperadas
    assert sum(s for _, s in esperadas) == sum(1 for a in selecionadas if a.sintetica)


def test_sem_dinamico_marca_o_eixo_b_como_nao_avaliavel(
        dataset_bom: pathlib.Path) -> None:
    relatorio = auditar(dataset_bom, com_dinamico=False)
    assert status_de(relatorio, "B2 isolamento") == [auditoria.NAO_AVALIAVEL]
    assert relatorio["falhas"] == []


# ------------------------------------------------ eixo C: fila de revisao


def test_fila_entrega_a_cota_de_cada_classe(dataset_bom: pathlib.Path) -> None:
    relatorio = auditar(dataset_bom, com_dinamico=False)
    assert status_de(relatorio, "C1 fila") == [auditoria.PASSOU]
    assert status_de(relatorio, "C2 nenhuma classe") == [auditoria.PASSOU]


def test_classe_sem_cota_reprova_a_fila(tmp_path: pathlib.Path) -> None:
    registros = [r for r in corpus_bom()
                 if r.get("rotulo_provisorio") != INSUFICIENTE]
    caminho = escrever(tmp_path / "d.jsonl", registros)
    relatorio = auditoria.auditar(
        caminho, esperado_do_corpus(total=len(registros), commons=POR_CLASSE * 2,
                                    sinteticas=0),
        min_df=1, pastas=2, com_dinamico=False)
    assert "C1 fila com o total do ADR 0002 secao 4" in relatorio["falhas"]
    assert "C2 nenhuma classe devendo cota" in relatorio["falhas"]
    assert relatorio["pronto_para_revisao_humana"] is False


def test_fila_e_deterministica_e_embaralhada(dataset_bom: pathlib.Path) -> None:
    relatorio = auditar(dataset_bom, com_dinamico=False)
    assert status_de(relatorio, "C3") == [auditoria.PASSOU, auditoria.PASSOU]


def test_pre_rotulo_fica_oculto_por_padrao(dataset_bom: pathlib.Path) -> None:
    relatorio = auditar(dataset_bom, com_dinamico=False)
    verificacao = next(v for v in relatorio["verificacoes"]
                       if v["nome"].startswith("C4"))
    assert verificacao["status"] == auditoria.PASSOU
    assert verificacao["obtido"]["vazou_por_padrao"] is False
    assert verificacao["obtido"]["aparece_com_a_flag"] is True


@pytest.mark.parametrize(("sequencia", "esperado"), [
    ([], 0),
    ([BOM], 1),
    ([BOM, FRACO, BOM, FRACO], 1),
    ([BOM, BOM, BOM, FRACO], 3),
    ([FRACO, BOM, BOM, BOM, BOM], 4),
])
def test_maior_corrida(sequencia: list[str], esperado: int) -> None:
    assert auditoria.maior_corrida(sequencia) == esperado


# ------------------------------------------------ eixo E: I/O de terminal


@pytest.mark.parametrize(("fonte", "esperado"), [
    ('fluxo.reconfigure(encoding="utf-8", errors="replace")', True),
    ('fluxo.reconfigure(encoding="UTF-8", errors="replace")', True),
    ('fluxo.reconfigure(encoding="utf8", errors="replace")', True),
    ('fluxo.reconfigure(encoding="utf-8", errors="strict")', False),
    ('fluxo.reconfigure(encoding="utf-8")', False),
    ("print('sem reconfigurar nada')", False),
])
def test_deteccao_da_sanitizacao_utf8(fonte: str, esperado: bool) -> None:
    assert auditoria.reconfigura_para_utf8(fonte) is esperado


def test_modulo_de_terminal_sem_sanitizacao_reprova() -> None:
    achados = auditoria.auditar_io_de_terminal(["accessai_ml.training.metricas"])
    assert [a.status for a in achados] == [auditoria.FALHOU]


def test_modulo_inexistente_reprova_em_vez_de_estourar() -> None:
    achados = auditoria.auditar_io_de_terminal(["accessai_ml.nao_existe"])
    assert [a.status for a in achados] == [auditoria.FALHOU]


# ------------------------------------------------------- relatorio em disco


def test_relatorio_tem_a_quebra_por_slice_e_o_hash_do_arquivo(
        dataset_bom: pathlib.Path, tmp_path: pathlib.Path) -> None:
    relatorio = auditar(dataset_bom, com_dinamico=False)
    destino = tmp_path / "r.json"
    auditoria.gravar_relatorio(destino, relatorio)
    gravado = json.loads(destino.read_text(encoding="utf-8"))

    assert set(gravado) >= {"gerado_em", "dataset", "hash_do_dataset", "slices",
                            "verificacoes", "resumo", "falhas", "avisos",
                            "pronto_para_revisao_humana", "veredito"}
    assert gravado["hash_do_dataset"] == auditoria.hash_do_arquivo(dataset_bom)
    por_slice = {b["slice"]: b for b in gravado["slices"]}
    assert por_slice[auditoria.SLICE_SINTETICO]["total"] == 2
    assert por_slice[auditoria.SLICE_SINTETICO]["papel_no_treino"] == "somente treino"
    assert por_slice[auditoria.SLICE_COMMONS]["total"] == POR_CLASSE * 3
    assert por_slice[auditoria.SLICE_GOVERNAMENTAL]["sem_alt"] == 2
    assert sum(gravado["resumo"][chave] for chave in
               (auditoria.PASSOU, auditoria.FALHOU, auditoria.AVISO,
                auditoria.NAO_AVALIAVEL)) == gravado["resumo"]["total"]


def test_relatorio_declara_o_rotulo_de_trabalho(dataset_bom: pathlib.Path) -> None:
    # Enquanto ninguem revisar, a metrica do Eixo B mede a heuristica contra ela
    # mesma. O relatorio nao pode omitir isso: um numero sem essa frase ao lado
    # seria lido como validacao do pre-rotulo.
    relatorio = auditar(dataset_bom, com_dinamico=False)
    assert relatorio["rotulos_humanos"] == 0
    assert "rotulo_provisorio" in relatorio["rotulo_de_trabalho"]


def test_hash_dos_ids_muda_quando_o_slice_muda(tmp_path: pathlib.Path) -> None:
    primeiro = auditoria.ler_corpus(escrever(tmp_path / "a.jsonl", corpus_bom()))
    registros = corpus_bom()
    registros[0]["id"] = "commons:outro-id"
    segundo = auditoria.ler_corpus(escrever(tmp_path / "b.jsonl", registros))
    assert (primeiro.slice_de(auditoria.SLICE_COMMONS).hash_dos_ids
            != segundo.slice_de(auditoria.SLICE_COMMONS).hash_dos_ids)


def test_tabela_marca_o_slice_da_falha(dataset_bom: pathlib.Path) -> None:
    relatorio = auditoria.auditar(dataset_bom, esperado_do_corpus(sinteticas=44),
                                  min_df=1, pastas=2, com_dinamico=False)
    linhas = auditoria.formatar_tabela(relatorio).splitlines()
    do_sintetico = next(x for x in linhas if auditoria.SLICE_SINTETICO in x)
    do_commons = next(x for x in linhas if auditoria.SLICE_COMMONS in x)
    assert auditoria.FALHOU in do_sintetico
    assert auditoria.FALHOU not in do_commons
