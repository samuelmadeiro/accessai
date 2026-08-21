"""Integridade do corpus e montagem do dataset."""

from __future__ import annotations

import hashlib
import json
import pathlib

import pytest

import docx_de_teste as docx
from accessai_ml.dataset import corpus, montagem


def montar_corpus(raiz: pathlib.Path, documentos: dict[str, bytes],
                  sha_falso: str | None = None) -> pathlib.Path:
    (raiz / "raw").mkdir(parents=True, exist_ok=True)
    registros = []
    for nome, conteudo in documentos.items():
        (raiz / "raw" / nome).write_bytes(conteudo)
        registros.append({
            "arquivo": nome,
            "url": f"https://exemplo.gov.br/{nome}",
            "orgao": "Orgao de Teste",
            "esfera": "federal",
            "categoria": "edital",
            "licenca": "nao declarada",
            "sha256": sha_falso or hashlib.sha256(conteudo).hexdigest(),
            "bytes": len(conteudo),
        })
    (raiz / "manifest.json").write_text(
        json.dumps({"gerado_em": "2026-08-21T00:00:00+00:00", "documentos": registros,
                    "falhas": []}, ensure_ascii=False), encoding="utf-8")
    return raiz


def ler_linhas(saida: pathlib.Path) -> list[dict]:
    bruto = (saida / "alt_texts.jsonl").read_text(encoding="utf-8")
    return [json.loads(linha) for linha in bruto.splitlines()]


def test_sha256_divergente_interrompe_a_montagem(tmp_path):
    # Dataset montado sobre binario trocado produz metrica que nao reproduz, e
    # o defeito so aparece semanas depois como "o modelo piorou sozinho".
    raiz = montar_corpus(tmp_path / "corpus", {"a.docx": docx.pacote()},
                         sha_falso="0" * 64)

    with pytest.raises(corpus.CorpusInvalidoError, match="sha256 divergente"):
        montagem.montar(raiz, tmp_path / "saida")


def test_manifesto_ausente_e_erro_explicito(tmp_path):
    (tmp_path / "corpus" / "raw").mkdir(parents=True)

    with pytest.raises(corpus.CorpusInvalidoError, match="manifesto ausente"):
        montagem.montar(tmp_path / "corpus", tmp_path / "saida")


def test_documento_do_manifesto_ausente_em_disco_e_ignorado(tmp_path):
    raiz = montar_corpus(tmp_path / "corpus", {"a.docx": docx.pacote(
        docx.imagem(descr="um grafico"))})
    (raiz / "raw" / "b.docx").write_bytes(docx.pacote())
    manifesto = json.loads((raiz / "manifest.json").read_text(encoding="utf-8"))
    manifesto["documentos"].append({"arquivo": "sumiu.docx", "sha256": "0" * 64})
    (raiz / "manifest.json").write_text(json.dumps(manifesto), encoding="utf-8")

    relatorio = montagem.montar(raiz, tmp_path / "saida")

    assert relatorio["documentos"] == 1


def test_amostra_carrega_procedencia_e_rotulo_nulo(tmp_path):
    raiz = montar_corpus(tmp_path / "corpus", {"a.docx": docx.pacote(
        docx.paragrafo("Antes"), docx.imagem(descr="Selo de acessibilidade"))})

    montagem.montar(raiz, tmp_path / "saida")

    linhas = ler_linhas(tmp_path / "saida")
    assert len(linhas) == 1
    assert linhas[0]["alt"] == "Selo de acessibilidade"
    assert linhas[0]["orgao"] == "Orgao de Teste"
    assert linhas[0]["contexto_antes"] == "Antes"
    # Nulo diz "ninguem rotulou". Um default aqui viraria rotulo de mentira.
    assert linhas[0]["rotulo"] is None


def test_corpus_sem_nenhum_alt_diz_isso_no_veredito(tmp_path):
    raiz = montar_corpus(tmp_path / "corpus", {
        "a.docx": docx.pacote(docx.imagem(descr=None)),
        "b.docx": docx.pacote(docx.paragrafo("texto sem imagem")),
    })

    relatorio = montagem.montar(raiz, tmp_path / "saida")

    assert relatorio["imagens"] == 1
    assert relatorio["imagens_com_alt"] == 0
    assert relatorio["amostras_rotulaveis"] == 0
    assert relatorio["veredito"].startswith("SEM AMOSTRA")


def test_volume_abaixo_do_adr_e_reportado_como_insuficiente(tmp_path):
    raiz = montar_corpus(tmp_path / "corpus", {"a.docx": docx.pacote(
        docx.imagem(descr="um"), docx.imagem(descr="dois"))})

    relatorio = montagem.montar(raiz, tmp_path / "saida")

    assert relatorio["amostras_rotulaveis"] == 2
    assert relatorio["alts_distintos"] == 2
    assert relatorio["veredito"].startswith("INSUFICIENTE")


def test_alt_identico_em_documentos_diferentes_cai_na_mesma_parte(tmp_path):
    # O vazamento que ameaca este modelo e TEXTO IDENTICO nos dois lados, nao
    # vocabulario parecido: na sondagem da fonte do ADR 0002, 93 alts nao vazios
    # tinham 10 distintos, porque logo de site repete em toda pagina.
    mesmo = "Brasao da Republica"
    raiz = montar_corpus(tmp_path / "corpus", {
        "a.docx": docx.pacote(docx.imagem(descr=mesmo)),
        "b.docx": docx.pacote(docx.imagem(descr=mesmo.upper())),
        "c.docx": docx.pacote(docx.imagem(descr="  brasao   da  republica ")),
    })

    montagem.montar(raiz, tmp_path / "saida")

    linhas = ler_linhas(tmp_path / "saida")
    assert len(linhas) == 3
    assert len({linha["grupo"] for linha in linhas}) == 1
    assert len({linha["divisao"] for linha in linhas}) == 1


def test_documento_deixou_de_ser_atomico_e_o_relatorio_mostra(tmp_path):
    # Custo assumido do agrupamento por alt: alts diferentes do mesmo documento
    # podem cair em partes diferentes. Fica visivel em `divisoes`, no plural,
    # em vez de escondido atras de um campo singular que mentiria.
    raiz = montar_corpus(tmp_path / "corpus", {"a.docx": docx.pacote(
        docx.imagem(descr="um"), docx.imagem(descr="dois"), docx.imagem(descr="tres"))})

    relatorio = montagem.montar(raiz, tmp_path / "saida")

    divisoes = relatorio["por_documento"]["a.docx"]["divisoes"]
    assert divisoes == sorted(divisoes)
    assert set(divisoes) <= {"treino", "validacao", "teste"}


def test_alts_repetidos_nao_contam_como_amostras_novas(tmp_path):
    raiz = montar_corpus(tmp_path / "corpus", {
        "a.docx": docx.pacote(docx.imagem(descr="mesmo alt"),
                              docx.imagem(descr="mesmo alt")),
        "b.docx": docx.pacote(docx.imagem(descr="mesmo alt")),
    })

    relatorio = montagem.montar(raiz, tmp_path / "saida")

    assert relatorio["imagens_com_alt"] == 3
    # 600 copias da mesma frase nao sao 600 amostras.
    assert relatorio["alts_distintos"] == 1
    assert relatorio["veredito"].startswith("INSUFICIENTE")


def test_cada_linha_carrega_a_versao_do_formato(tmp_path):
    # Quem ler o .jsonl sem o relatorio ao lado precisa saber o esquema.
    raiz = montar_corpus(tmp_path / "corpus", {"a.docx": docx.pacote(
        docx.imagem(descr="um"))})

    montagem.montar(raiz, tmp_path / "saida")

    linhas = ler_linhas(tmp_path / "saida")
    assert {linha["versao_do_formato"] for linha in linhas} == {
        montagem.VERSAO_DO_FORMATO}


def test_documento_com_xml_quebrado_aparece_no_relatorio(tmp_path):
    # O caso perigoso: word/document.xml que nao parseia produz zero imagens, e
    # zero imagens le-se como "documento sem figura". Sao coisas diferentes.
    raiz = montar_corpus(tmp_path / "corpus", {"a.docx": docx.pacote(
        docx.imagem(descr="ok"),
        partes={"word/footer1.xml": "<w:ftr>"})})

    relatorio = montagem.montar(raiz, tmp_path / "saida")

    assert relatorio["documentos_com_parte_ilegivel"] == {
        "a.docx": ["word/footer1.xml"]}
    assert relatorio["por_documento"]["a.docx"]["partes_ilegiveis"] == [
        "word/footer1.xml"]
