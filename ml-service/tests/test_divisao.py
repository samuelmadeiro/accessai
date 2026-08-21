"""Divisao treino/validacao/teste e a chave de agrupamento."""

from __future__ import annotations

from accessai_ml.dataset import divisao


def chaves(n: int) -> list[str]:
    return [f"alt numero {i}" for i in range(n)]


def test_a_mesma_entrada_produz_sempre_a_mesma_divisao():
    assert divisao.dividir(chaves(50)) == divisao.dividir(chaves(50))


def test_chave_nova_nao_remexe_as_que_ja_estavam():
    # Divisao por embaralhamento com semente global tem esse defeito: uma chave
    # a mais reordena tudo, o conjunto de teste deixa de ser o mesmo entre duas
    # execucoes, e as metricas ficam incomparaveis.
    antes = chaves(20)
    depois = [*antes, "um alt inedito"]

    divisao_antes = divisao.dividir(antes)
    divisao_depois = divisao.dividir(depois)

    for chave in antes:
        assert divisao_antes.parte_de(chave) == divisao_depois.parte_de(chave)


def test_cada_chave_cai_em_exatamente_uma_parte():
    entrada = chaves(100)

    particao = divisao.dividir(entrada)

    todas = particao.treino + particao.validacao + particao.teste
    assert sorted(todas) == sorted(entrada)
    assert len(todas) == len(set(todas))


def test_proporcao_fica_perto_de_70_15_15_em_volume_grande():
    particao = divisao.dividir(chaves(1000))

    assert 650 <= len(particao.treino) <= 750
    assert 110 <= len(particao.validacao) <= 190
    assert 110 <= len(particao.teste) <= 190


def test_entrada_vazia_produz_divisao_vazia():
    particao = divisao.dividir([])

    assert (particao.treino, particao.validacao, particao.teste) == ([], [], [])


def test_parte_de_chave_desconhecida_e_nula():
    particao = divisao.dividir(["conhecida"])

    assert particao.parte_de("nunca vista") is None


# ---------------------------------------------------------------- agrupamento

def test_alts_identicos_compartilham_chave_mesmo_em_documentos_diferentes():
    # E o ponto do F4: sem isso, o mesmo logo institucional aparece no treino e
    # no teste, e a macro-F1 sobe sem o modelo ter aprendido nada.
    a = divisao.chave_de_agrupamento("Brasao da Republica", "edital-a.docx")
    b = divisao.chave_de_agrupamento("Brasao da Republica", "edital-b.docx")

    assert a == b


def test_normalizacao_ignora_caixa_espaco_e_acento():
    assert (divisao.chave_de_agrupamento("BRASÃO  da   República", "a.docx")
            == divisao.chave_de_agrupamento("brasao da republica", "b.docx"))


def test_imagem_sem_alt_e_agrupada_pelo_documento():
    # Todas juntas num grupo unico desequilibrariam a divisao inteira.
    a = divisao.chave_de_agrupamento("", "edital-a.docx")
    b = divisao.chave_de_agrupamento("   ", "edital-b.docx")

    assert a != b
    assert a.startswith(divisao.PREFIXO_SEM_ALT)


def test_alts_identicos_caem_na_mesma_parte():
    entrada = [divisao.chave_de_agrupamento("Selo de acessibilidade", f"doc{i}.docx")
               for i in range(10)]

    particao = divisao.dividir(entrada)

    assert len({particao.parte_de(chave) for chave in entrada}) == 1
