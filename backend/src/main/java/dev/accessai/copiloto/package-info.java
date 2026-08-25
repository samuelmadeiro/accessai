/**
 * Casa do copiloto conversacional da Slice 7. Ainda sem implementacao.
 *
 * <p>Este pacote existe vazio de proposito. As invariantes do ADR 0012 sao
 * travadas por {@code ArquiteturaDaIaTest}, e regra de arquitetura sobre pacote
 * inexistente passa por vacuidade — teste que passa porque nao ha o que
 * verificar e teste que mente. Com o pacote declarado, a guarda ja esta de pe
 * quando a primeira classe do copiloto for escrita, em vez de ser lembrada
 * depois.
 *
 * <p>O que vale aqui dentro, resumido (a decisao inteira esta no ADR 0012):
 *
 * <ul>
 *   <li><b>I1.</b> O copiloto nao produz achado: nao cria {@code Problema}, nao
 *       altera score, nao introduz criterio WCAG.</li>
 *   <li><b>I2.</b> O contexto e {@code VisaoDaAnalise} — a analise ja produzida.
 *       Nunca {@code DocumentoExtraido}, nunca o pacote OOXML. Sem acesso ao
 *       documento, opinar sobre o que a regra nao mediu e impossivel por
 *       construcao, e nao por instrucao no prompt.</li>
 *   <li><b>I3.</b> Pergunta fora do escopo da analise e recusada.</li>
 *   <li><b>I4.</b> {@code AiProvider} continua porta unica e {@code GatewayDeIa}
 *       continua o unico chamador. A Slice 7 estende a porta para multi-turno;
 *       nao abre uma segunda via de saida.</li>
 *   <li><b>I5.</b> {@code procedencia} (FIXTURE ou MODELO) continua visivel na
 *       resposta.</li>
 * </ul>
 */
package dev.accessai.copiloto;
