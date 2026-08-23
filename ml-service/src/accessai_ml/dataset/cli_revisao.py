"""CLI interativa de revisao humana dos pre-rotulos (`accessai-revisar`).

    accessai-revisar --dataset data/alt_texts.jsonl

Mostra um texto alternativo por vez e espera uma tecla. O `rotulo_provisorio`
fica ESCONDIDO por padrao: quem ve o palpite da heuristica antes de julgar tende
a concorda-lo, e a concordancia medida deixa de medir concordancia — mede
ancoragem. `--mostrar-pre-rotulo` existe para depuracao, e invalida o kappa da
sessao para efeito de ADR.

O progresso e gravado ao sair, por qualquer caminho: `q`, fim da fila, Ctrl-C ou
EOF. Uma sessao interrompida que perde o trabalho faz a proxima pessoa comecar
do zero, e revisao de 150 amostras nao cabe numa sentada.
"""

from __future__ import annotations

import argparse
import dataclasses
import pathlib
import sys
from collections.abc import Callable
from typing import Any

from . import revisao

BOM = "GOOD"
FRACO = "WEAK"
INSUFICIENTE = "INSUFFICIENT"

SAIR = "SAIR"

# Uma tecla, sem Enter obrigatorio na cabeca de quem revisa: o numero para quem
# pensa na escala, a letra para quem pensa no nome. 150 amostras e trabalho
# repetitivo, e cada tecla a mais e 150 teclas a mais.
COMANDOS: dict[str, str] = {
    "1": BOM, "g": BOM,
    "2": FRACO, "w": FRACO,
    "3": INSUFICIENTE, "i": INSUFICIENTE,
    "s": revisao.PULAR,
    "q": SAIR,
}

LARGURA = 74

AJUDA = ("  [1|g] GOOD    descreve a imagem de forma util\n"
         "  [2|w] WEAK    generico ou incompleto\n"
         "  [3|i] INSUFFICIENT  nao descreve nada\n"
         "  [s]   pular   nao sei julgar esta\n"
         "  [q]   sair    grava o progresso e encerra")

SAIDA_OK = 0
SAIDA_DATASET_INVALIDO = 3
SAIDA_SEM_PENDENTE = 4

Leitor = Callable[[str], str]


@dataclasses.dataclass
class Sessao:
    """O que aconteceu numa rodada de revisao."""

    decisoes: list[revisao.Decisao] = dataclasses.field(default_factory=list)
    puladas: int = 0
    saiu_antes_do_fim: bool = False


# ------------------------------------------------------------------ desenho


def preparar_terminal() -> None:
    """Forca UTF-8 na saida, com `replace` para caractere que o console nao tem.

    Sem isto, o alt em japones ou com aspas tipograficas mata a CLI no meio da
    sessao: no Windows, `stdout` redirecionado assume cp1252 e `print` levanta
    `UnicodeEncodeError`. Perder 80 revisoes porque uma amostra tinha um
    caractere fora da pagina de codigo e inaceitavel — melhor imprimir `?` e
    seguir, que o revisor ainda julga o resto do texto.
    """
    for fluxo in (sys.stdout, sys.stderr):
        reconfigurar = getattr(fluxo, "reconfigure", None)
        if reconfigurar is not None:
            reconfigurar(encoding="utf-8", errors="replace")


def _moldura(texto: str, largura: int = LARGURA) -> str:
    """Quebra o alt em linhas e o poe numa moldura.

    O alt e o unico dado que importa na tela; sem moldura ele se mistura com o
    cabecalho e com o prompt, e o revisor le a pergunta errada.
    """
    palavras = texto.split()
    linhas: list[str] = []
    atual = ""
    for palavra in palavras:
        # Palavra sozinha maior que a moldura e cortada em pedacos: sem isso ela
        # estoura a borda e desalinha o quadro inteiro.
        while len(palavra) > largura - 4:
            if atual:
                linhas.append(atual)
                atual = ""
            linhas.append(palavra[:largura - 4])
            palavra = palavra[largura - 4:]
        candidata = f"{atual} {palavra}".strip()
        if len(candidata) > largura - 4:
            linhas.append(atual)
            atual = palavra
        else:
            atual = candidata
    if atual:
        linhas.append(atual)
    if not linhas:
        linhas = [""]

    borda = "+" + "-" * (largura - 2) + "+"
    corpo = "\n".join(f"| {linha.ljust(largura - 4)} |" for linha in linhas)
    return f"{borda}\n{corpo}\n{borda}"


def apresentar(amostra: revisao.Amostra, posicao: int, total: int,
               mostrar_pre_rotulo: bool) -> str:
    cabecalho = f"[{posicao}/{total}] {amostra.id}"
    partes = [cabecalho, _moldura(amostra.alt),
              f"{len(amostra.alt)} caracteres"]
    if mostrar_pre_rotulo:
        partes.append(f"pre-rotulo: {amostra.rotulo_provisorio}  "
                      "(VISIVEL — este kappa nao vale para o ADR)")
    return "\n".join(partes)


# -------------------------------------------------------------------- laco


def revisar(amostras: list[revisao.Amostra], ler: Leitor,
            escrever: Callable[[str], None],
            mostrar_pre_rotulo: bool = False,
            sessao: Sessao | None = None) -> Sessao:
    """Percorre as amostras coletando decisoes. Nunca levanta por entrada ruim.

    `ler` e `escrever` sao injetados em vez de `input`/`print` diretos para que o
    teste exercite o laco de verdade, com a sequencia de teclas que uma pessoa
    digitaria — inclusive as invalidas.

    `sessao` pode vir de fora para que o chamador ainda alcance as decisoes se
    algo inesperado levantar no meio: quem revisou 80 amostras nao pode perder
    as 80 por causa de uma excecao na 81.
    """
    sessao = sessao if sessao is not None else Sessao()
    total = len(amostras)

    for posicao, amostra in enumerate(amostras, start=1):
        escrever("")
        escrever(apresentar(amostra, posicao, total, mostrar_pre_rotulo))

        while True:
            try:
                resposta = ler("> ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                # Ctrl-C e fim de entrada valem `q`: o trabalho ja feito e
                # gravado pelo chamador. Perder 80 revisoes por causa de um
                # Ctrl-C e o defeito que faz ninguem terminar as 150.
                escrever("")
                sessao.saiu_antes_do_fim = True
                return sessao

            if not resposta or resposta not in COMANDOS:
                escrever(AJUDA)
                continue

            comando = COMANDOS[resposta]
            if comando == SAIR:
                sessao.saiu_antes_do_fim = True
                return sessao
            if comando == revisao.PULAR:
                sessao.puladas += 1
                break
            sessao.decisoes.append(revisao.Decisao(amostra=amostra, rotulo=comando))
            break

    return sessao


def resumo(relatorio: dict[str, Any]) -> str:
    return "\n".join([
        "",
        f"revisadas no total: {relatorio['total_revisado']}"
        f"  (puladas nesta sessao: {relatorio['puladas_na_sessao']})",
        f"taxa de correcao:   {relatorio['taxa_correcao']:.1%}",
        f"kappa de Cohen:     {relatorio['kappa_cohen']:.3f} "
        f"({relatorio['interpretacao_do_kappa']})",
        "",
        relatorio["veredito"],
    ])


# --------------------------------------------------------------------- CLI


def main(argv: list[str] | None = None) -> int:
    analisador = argparse.ArgumentParser(
        description="Revisao humana dos pre-rotulos (ADR 0002 secao 4).")
    analisador.add_argument("--dataset", type=pathlib.Path,
                            default=pathlib.Path("data") / "alt_texts.jsonl")
    analisador.add_argument("--relatorio", type=pathlib.Path,
                            default=pathlib.Path("data") / revisao.NOME_DO_RELATORIO)
    analisador.add_argument("--por-classe", type=int,
                            default=revisao.POR_CLASSE_PADRAO,
                            help="amostras de cada rotulo provisorio na fila")
    analisador.add_argument("--semente", type=int, default=42,
                            help="semente da amostragem; a mesma repete a fila")
    analisador.add_argument("--mostrar-pre-rotulo", action="store_true",
                            help="mostra o palpite da heuristica. ANCORA o "
                                 "revisor e invalida o kappa para efeito de ADR")
    argumentos = analisador.parse_args(argv)
    preparar_terminal()

    try:
        registros = revisao.carregar(argumentos.dataset)
        disponiveis = revisao.pendentes(registros)
    except revisao.RevisaoError as erro:
        print(f"dataset invalido: {erro}", file=sys.stderr)
        return SAIDA_DATASET_INVALIDO

    ja_revisadas = revisao.decisoes_registradas(registros)
    if not disponiveis:
        print("nenhuma amostra pendente de revisao: ou o dataset nao tem "
              "`rotulo_provisorio`, ou tudo ja foi revisado.", file=sys.stderr)
        if not ja_revisadas:
            return SAIDA_SEM_PENDENTE

    fila = revisao.amostrar_balanceado(disponiveis, argumentos.por_classe,
                                       argumentos.semente) if disponiveis else []
    if fila:
        print(f"{len(fila)} amostras na fila "
              f"({argumentos.por_classe} por classe pedidas). "
              f"{len(ja_revisadas)} ja revisadas antes desta sessao.")
        print(AJUDA)

    # A sessao nasce aqui, e nao dentro de `revisar`, para que o `finally`
    # alcance as decisoes mesmo se o laco morrer por algo que ninguem previu.
    sessao = Sessao()
    try:
        revisar(fila, ler=input, escrever=print,
                mostrar_pre_rotulo=argumentos.mostrar_pre_rotulo, sessao=sessao)
    finally:
        if sessao.decisoes:
            revisao.aplicar(registros, sessao.decisoes)
            revisao.gravar(argumentos.dataset, registros)

    # O relatorio conta o ACUMULADO no arquivo, nao a sessao: as 150 do ADR
    # somam ao longo de varias sentadas.
    todas = revisao.decisoes_registradas(registros)
    relatorio = revisao.montar_relatorio(todas, sessao.puladas, disponiveis,
                                         argumentos.por_classe, argumentos.dataset)
    revisao.gravar_relatorio(argumentos.relatorio, relatorio)

    print(resumo(relatorio))
    print(f"\nrelatorio em {argumentos.relatorio.resolve()}")
    if sessao.saiu_antes_do_fim and sessao.decisoes:
        print(f"{len(sessao.decisoes)} decisoes gravadas em "
              f"{argumentos.dataset.resolve()}. Rode de novo para continuar.")
    return SAIDA_OK


if __name__ == "__main__":
    raise SystemExit(main())
