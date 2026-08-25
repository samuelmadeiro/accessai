package dev.accessai.ia;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * O guardrail do §7: pergunta sem base na analise e RECUSADA.
 *
 * <p>Ele age nas duas pontas, e as duas importam por motivos diferentes.
 *
 * <p><b>Na entrada</b>, recusa pergunta que fala de coisa que a analise nao
 * encontrou. Sem isso, "por que o contraste esta ruim?" num documento onde
 * contraste nunca foi verificado produziria uma resposta plausivel sobre um
 * problema inexistente — e o usuario nao tem como saber que ela foi inventada.
 * Recusar e a resposta honesta: o sistema so fala do que mediu.
 *
 * <p><b>Na saida</b>, descarta recomendacao que cita regra ausente da analise.
 * O provider de hoje e uma fixture e nao tem como inventar; o de amanha e um
 * modelo generativo, e alucinar criterio WCAG plausivel e o modo de falha mais
 * provavel dele. Guardrail que so existe depois que o modelo chega e guardrail
 * escrito com pressa.
 */
@Component
public class GuardrailDeFundamentacao {

    private static final Logger log = LoggerFactory.getLogger(GuardrailDeFundamentacao.class);

    /**
     * Numero de criterio WCAG dentro de um texto livre: {@code 1.4.3}, {@code 2.4.4}.
     *
     * <p>E o unico gancho objetivo que existe numa pergunta em lingua natural.
     * Tentar casar assunto por palavra-chave — "contraste", "tabela" — seria
     * inventar um classificador de intencao a mao, com falso positivo e falso
     * negativo, para decidir se o sistema pode ou nao responder.
     */
    private static final Pattern CRITERIO = Pattern.compile("\\b([1-4]\\.\\d+\\.\\d+)\\b");

    /**
     * Recusa a pergunta que nao se sustenta no que a analise encontrou.
     *
     * @throws SemFundamentoException quando nao ha achado nenhum, ou quando a
     *     pergunta cita criterio que a analise nao encontrou
     */
    public void conferirEntrada(AiProvider.@NonNull Fundamento fundamento) {
        if (!fundamento.temAchados()) {
            // Documento sem problema nenhum nao rende recomendacao: pedir a um
            // LLM que fale sobre um resultado limpo produz conselho generico
            // apresentado como analise deste documento.
            throw new SemFundamentoException(
                    "a analise nao encontrou problema nenhum: nao ha o que recomendar");
        }

        String pergunta = fundamento.pergunta();
        if (pergunta == null || pergunta.isBlank()) {
            return;
        }

        Set<String> criteriosDaAnalise = fundamento.achados().stream()
                .map(AiProvider.Fundamento.Achado::criterioWcag)
                .collect(Collectors.toSet());

        List<String> citados = new java.util.ArrayList<>();
        Matcher achador = CRITERIO.matcher(pergunta);
        while (achador.find()) {
            citados.add(achador.group(1));
        }

        List<String> semBase = citados.stream()
                .filter(c -> !criteriosDaAnalise.contains(c))
                .toList();
        if (!semBase.isEmpty()) {
            log.info("pergunta recusada: cita {} e a analise so tem {}",
                    semBase, criteriosDaAnalise);
            throw new SemFundamentoException(
                    "a pergunta cita " + String.join(", ", semBase)
                            + ", que esta analise nao verificou. O sistema so responde "
                            + "sobre o que mediu: " + String.join(", ", criteriosDaAnalise));
        }
    }

    /**
     * Descarta o que o provider disse sobre regra que nao existe na analise.
     *
     * <p>Descarta em silencio para o usuario e com WARN no log, em vez de
     * recusar a resposta inteira: uma recomendacao alucinada no meio de quatro
     * corretas nao justifica devolver nada. O WARN e o que permite descobrir
     * que o provider esta inventando, em vez de o defeito virar ruido invisivel.
     */
    public @NonNull RespostaDeIa filtrarSaida(AiProvider.@NonNull Fundamento fundamento,
                                              @NonNull RespostaDeIa resposta) {
        Set<String> regrasDaAnalise = fundamento.achados().stream()
                .map(AiProvider.Fundamento.Achado::regraId)
                .map(r -> r.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<RespostaDeIa.Recomendacao> fundamentadas = resposta.recomendacoes().stream()
                .filter(r -> r.regraId() != null
                        && regrasDaAnalise.contains(r.regraId().toUpperCase(Locale.ROOT)))
                .toList();

        int descartadas = resposta.recomendacoes().size() - fundamentadas.size();
        if (descartadas > 0) {
            log.warn("GUARDRAIL: {} recomendacao(oes) descartada(s) por citar regra "
                    + "ausente da analise {}. Provider: {}",
                    descartadas, fundamento.analiseId(), resposta.modelo());
        }
        return new RespostaDeIa(fundamentadas, resposta.procedencia(), resposta.modelo(),
                resposta.custoEstimadoEmCentavos());
    }

    /** A pergunta nao tem base no que foi medido. */
    public static class SemFundamentoException extends RuntimeException {
        public SemFundamentoException(String motivo) {
            super(motivo);
        }
    }
}
