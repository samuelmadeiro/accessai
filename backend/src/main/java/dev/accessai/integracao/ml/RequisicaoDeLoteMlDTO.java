package dev.accessai.integracao.ml;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Os textos alternativos de UM documento, numa chamada so.
 *
 * <p>Existe porque a API de item unico abria uma conexao por imagem. Medido, o
 * custo normal era aceitavel — p99 de 9 ms por chamada, ~180 ms para vinte
 * imagens. O problema e o cenario degradado: com o servico travado, o custo vira
 * linear no numero de imagens e cada uma paga o timeout de 1,5 s inteiro.
 *
 * @param itens um pedido por imagem, na ordem em que serao lidos de volta
 */
public record RequisicaoDeLoteMlDTO(@NonNull List<RequisicaoMlDTO> itens) {

    public RequisicaoDeLoteMlDTO {
        itens = List.copyOf(itens);
    }
}
