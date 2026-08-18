package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.PrincipioWcag;
import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.DocumentoExtraido;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executa as regras e converte achados em {@link Problema}.
 *
 * <p>O nivel WCAG de cada problema vem do {@link CatalogoWcag}, nunca da regra.
 * As regras sao validadas contra o catalogo no momento da construcao: se
 * alguma citar criterio inexistente, a aplicacao nao sobe. Criterio errado
 * descoberto em producao ja virou relatorio entregue a alguem.
 *
 * <p>O motor tambem sabe quais principios WCAG ele de fato verifica. Esse
 * conjunto e o que impede o score de dar nota a uma categoria sem nenhuma
 * regra: hoje nao ha regra de 4.x, entao Robusto nao entra na media em vez de
 * entrar valendo 100.
 */
@Component
public class MotorDeRegras {

    private static final Logger log = LoggerFactory.getLogger(MotorDeRegras.class);

    private final List<RegraDeAcessibilidade> regras;
    private final CatalogoWcag catalogo;
    private final Set<PrincipioWcag> principiosAvaliados;

    public MotorDeRegras(List<RegraDeAcessibilidade> regras, CatalogoWcag catalogo) {
        this.regras = List.copyOf(regras);
        this.catalogo = catalogo;
        this.principiosAvaliados = validarEMapearPrincipios();
    }

    private Set<PrincipioWcag> validarEMapearPrincipios() {
        Set<PrincipioWcag> principios = EnumSet.noneOf(PrincipioWcag.class);
        for (RegraDeAcessibilidade regra : regras) {
            // Lanca CriterioDesconhecidoException e derruba a subida da aplicacao.
            CatalogoWcag.Criterio criterio = catalogo.buscar(regra.criterioWcag());
            if (criterio.geraViolacao()) {
                principios.add(PrincipioWcag.doCriterio(criterio.id()));
            }
        }
        log.info("motor de regras pronto: {} regra(s) validada(s), principios avaliados {}",
                regras.size(), principios);
        return Set.copyOf(principios);
    }

    public List<Problema> executar(UUID analiseId, DocumentoExtraido documento, Instant agora) {
        List<Problema> problemas = new ArrayList<>();
        for (RegraDeAcessibilidade regra : regras) {
            CatalogoWcag.Criterio criterio = catalogo.buscar(regra.criterioWcag());
            if (!criterio.geraViolacao()) {
                // WCAG2ICT marcou o criterio como inaplicavel a documento
                // nao-web: vira recomendacao, e recomendacao nao e problema
                // (CLAUDE.md secao 6). Recomendacao entra na Slice 6.
                log.debug("regra {} ignorada: criterio {} e inaplicavel a documento nao-web",
                        regra.id(), criterio.id());
                continue;
            }
            for (Achado achado : regra.avaliar(documento)) {
                problemas.add(Problema.registrar(analiseId, regra.id(), criterio.id(),
                        criterio.nivel(), achado.severidade(), achado.partePacote(),
                        achado.evidencia(), agora));
            }
        }
        return problemas;
    }

    /** Principios com ao menos uma regra que pode gerar violacao. */
    public Set<PrincipioWcag> principiosAvaliados() {
        return principiosAvaliados;
    }
}
