package dev.accessai.analise.regras;

import dev.accessai.analise.dominio.Problema;
import dev.accessai.analise.extracao.ImagemDoDocumento;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 */
@Component
public class MotorDeRegras {

    private static final Logger log = LoggerFactory.getLogger(MotorDeRegras.class);

    private final List<RegraDeAcessibilidade> regras;
    private final CatalogoWcag catalogo;

    public MotorDeRegras(List<RegraDeAcessibilidade> regras, CatalogoWcag catalogo) {
        this.regras = List.copyOf(regras);
        this.catalogo = catalogo;
        validarCriterios();
    }

    private void validarCriterios() {
        for (RegraDeAcessibilidade regra : regras) {
            // Lanca CriterioDesconhecidoException e derruba a subida da aplicacao.
            catalogo.buscar(regra.criterioWcag());
        }
        log.info("motor de regras pronto: {} regra(s) validada(s) contra a tabela WCAG",
                regras.size());
    }

    public List<Problema> executar(UUID analiseId, List<ImagemDoDocumento> imagens, Instant agora) {
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
            for (Achado achado : regra.avaliar(imagens)) {
                problemas.add(Problema.registrar(analiseId, regra.id(), criterio.id(),
                        criterio.nivel(), achado.severidade(), achado.partePacote(),
                        achado.evidencia(), agora));
            }
        }
        return problemas;
    }
}
