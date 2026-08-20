package dev.accessai.analise.extracao;

import javax.xml.stream.XMLStreamReader;

/**
 * Um pedaco de informacao extraida do pacote.
 *
 * <p>Coletor nao decide se algo e problema: ele so descreve o que o documento
 * tem. Quem julga sao as regras (CONTRIBUTING.md secao 2). A separacao importa porque
 * a mesma tabela alimenta a regra de linha de cabecalho hoje e a de resumo de
 * tabela amanha, sem reabrir o pacote.
 *
 * <p>Implementacao e mutavel e de vida curta: um coletor por analise, criado e
 * descartado pelo {@link ExtratorDeDocumento}. Nao ha estado compartilhado entre
 * threads.
 */
interface ColetorDeParte {

    /** Se esta parte do pacote interessa a este coletor. */
    boolean aceita(String parte);

    default void aoIniciarParte(String parte) {
    }

    default void aoAbrirElemento(XMLStreamReader r, int profundidade) {
    }

    default void aoTexto(String texto) {
    }

    default void aoFecharElemento(int profundidade) {
    }

    default void aoTerminarParte(String parte) {
    }
}
