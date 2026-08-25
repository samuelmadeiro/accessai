"""Regera o corpus de contrato da heuristica de alt text.

    python scripts/gerar_golden_heuristica.py

O arquivo `docs/ml/heuristica-alt.golden.json` e o que impede a heuristica do
Python e a do Java de divergirem em silencio: os dois lados tem um teste que
reproduz todas as linhas dele.

A implementacao Python e a fonte da verdade, e por isso este script gera a
partir dela. Mudar uma regra la e regenerar aqui faz o teste do lado Java
quebrar — que e exatamente o comportamento desejado: a divergencia aparece no
build, nao em producao.

**Nao edite o JSON a mao.** Editar a saida para "consertar" um caso divergente
esconderia justamente o que o arquivo existe para mostrar.
"""

from __future__ import annotations

import collections
import json
import pathlib
import sys

RAIZ = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ / "ml-service" / "src"))

from accessai_ml.training.modelo import BaselineHeuristico

DESTINO = RAIZ / "docs" / "ml" / "heuristica-alt.golden.json"

# Um caso por ramo da heuristica, mais as bordas de comprimento (14/15 e 39/40)
# e o que costuma quebrar porte entre linguagens: acentuacao, caixa, espaco nas
# pontas e caractere fora do ASCII — o `\w` do Python casa letra Unicode por
# padrao, o do Java nao.
CASOS: tuple[str, ...] = (
    "IMG_0421.jpg", "DSC_0142.JPEG", "image1.png", "foto (3).jpeg",
    "Captura de tela 2024-03-11.png", "desenho.SVG", "grafico.emf", "x.tiff",
    "---", "...", "123", "___", "  ***  ", "42",
    "imagem", "foto", "logo", "icone", "ícone", "figura", "banner",
    "sem titulo", "sem título", "untitled", "image", "picture",
    "imagem de um predio antigo na avenida", "foto da equipe reunida em 2024",
    "clique aqui para baixar o edital completo", "logotipo institucional do orgao",
    "saiba mais sobre o programa de acessibilidade",
    "Brasao", "Assinatura", "Rodape", "Selo", "Marca", "Anexo I",
    "Tabela de indicadores por orgao", "Mapa das regioes atendidas",
    "Fluxograma do processo", "Organograma da diretoria geral",
    "Grafico de barras com a evolucao do orcamento entre 2020 e 2025",
    "Fotografia da fachada do predio sede vista da avenida principal",
    "Diagrama da arquitetura do sistema com as tres camadas nomeadas",
    "Mapa do Brasil com as regioes de atuacao do programa destacadas",
    "quatorze cara", "quinze caracte", "a" * 14, "a" * 15, "b" * 39, "b" * 40,
    "   IMG_0001.JPG   ", "  Selo  ", "IMAGEM", "FOTO DA EQUIPE REUNIDA HOJE",
    "Ilustração de um gráfico circular com quatro fatias coloridas",
    "写真", "Gráfico", "ícone de acessibilidade universal do programa",
    # Vazio e so-espaco: o schema da API recusa com 422 antes de chegar no
    # servico, mas a heuristica LOCAL do Java recebe o que vier do documento.
    # Estao aqui para que o comportamento nos dois lados seja acordado em vez de
    # acidental.
    "", "   ",
)

CABECALHO = [
    "Corpus de contrato da heuristica de qualidade de alt text.",
    "",
    "A heuristica existe em DUAS linguagens: `training.modelo.BaselineHeuristico`",
    "no Python e `integracao.ml.HeuristicaDeAltLocal` no Java. Duas",
    "implementacoes da mesma regra divergem — foi o defeito da lista branca de",
    "partes OOXML, corrigido na Slice 4.",
    "",
    "Este arquivo e o que impede a divergencia de passar despercebida: os dois",
    "lados tem um teste que reproduz TODAS as linhas abaixo. Quem mudar uma",
    "regra em um lado so quebra o teste do outro.",
    "",
    "Gerado a partir da implementacao Python, que e a fonte da verdade.",
    "Regenerar com `python scripts/gerar_golden_heuristica.py`.",
]


def main() -> int:
    heuristica = BaselineHeuristico()
    unicos = list(dict.fromkeys(CASOS))
    golden = {
        "leiaAntes": CABECALHO,
        "casos": [{"alt": alt, "categoria": str(heuristica.predict([alt])[0])}
                  for alt in unicos],
    }
    DESTINO.write_text(json.dumps(golden, ensure_ascii=False, indent=2) + "\n",
                       encoding="utf-8")

    contagem = collections.Counter(c["categoria"] for c in golden["casos"])
    print(f"{len(unicos)} casos em {DESTINO}: {dict(sorted(contagem.items()))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
