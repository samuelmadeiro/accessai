# Journal — Slice 2: Rule Engine Completo e Calculadora de Score

## O que entregamos
Evoluímos o motor determinístico de 1 para 6 regras de acessibilidade baseadas no WCAG2ICT, reformulamos a camada de extração para varredura em passagem única via streaming e implementamos a calculadora de pontuação baseada no modelo POUR.

### Regras Implementadas
1. `IMAGEM_SEM_TEXTO_ALTERNATIVO` (WCAG 1.1.1 - A)
2. `TABELA_SEM_CABECALHO` (WCAG 1.3.1 - A) — Verificação de `w:tblHeader` em tabelas com dados.
3. `ORDEM_HIERARQUICA_CABECALHOS` (WCAG 1.3.1 - A) — Inspeção via `w:outlineLvl` antes de recorrer a nomes de estilo regionais.
4. `TITULO_AUSENTE` (WCAG 2.4.2 - A) — Leitura de propriedades em `docProps/core.xml`.
5. `LINK_SEM_TEXTO_DESCRITIVO` (WCAG 2.4.4 - A) — Detecção de URLs expostas e termos genéricos sem heurísticas arbitrárias de tamanho.
6. `IDIOMA_NAO_DECLARADO` (WCAG 3.1.1 - A) — Leitura da marcação `w:lang`.

### Extração em Passagem Única
O `ExtratorDeImagens` foi substituído pelo `ExtratorDeDocumento`. Uma única iteração no `ZipInputStream` alimenta todos os coletores, evitando reaberturas repetitivas do arquivo `.docx` e unificando a API para `avaliar(DocumentoExtraido)`.

### Calculadora de Score (POUR)
- Adotamos o modelo POUR conforme reescrita do `CLAUDE.md §6` e formalização no **ADR 0009**.
- Categorias sem regras aplicadas (como a dimensão *Robusto*) são mapeadas em `naoAvaliados` e excluídas do cálculo global via renormalização dinâmica.
- Pesos de 25% por categoria externalizados no `application.yml`.

---

## Dívidas e Limitações Mapeadas
- **Contraste (1.4.3):** Adiado conforme ADR 0001 devido à alta complexidade da cascata `themeTint`/`themeShade`.
- **Campos Legados:** Links do tipo `HYPERLINK` legado não são mapeados nesta slice.
- **Resiliência e Outbox:** O pipeline assíncrono segue sem Transactional Outbox e DLT (escopo central da **Slice 3**).
