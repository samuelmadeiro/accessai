# Journal — Slice 4: Dataset, Extração e Módulo Python (ml-service)

## O que entregamos
Estruturamos a base do módulo `ml-service/` em Python, focado no processamento de linguagem natural e extração de recursos de acessibilidade a partir do corpus `.docx`.

### Módulo `ml-service/`
- Criada a estrutura base de código em `src/dataset/` com suporte a execução via CLI.
- Implementado o extrator OOXML otimizado para varredura direta de elementos de imagem (`pic:pic` e `v:imagedata`), contornando duplicações causadas por caixas de texto (`w:txbxContent`).
- Adicionada proteção contra arquivos XML maliciosos/comprimidos (teto de descompressão de 32MB por parte) e alinhada a lista de exclusão de arquivos XML com a implementação Java.
- Implementada a divisão e deduplicação semântica do dataset baseada no texto dos `alt_text` para evitar vazamento de dados (*data leakage*) entre conjuntos de treino e teste.

---

## Alternativas Descartadas
- **Varredura por parágrafos (`w:p`):** Descartada pois causava a duplicação de imagens ancoradas em caixas de texto.
- **Lista branca de partes XML (`PARTES_COM_CONTEUDO`):** Descartada por causar divergência silenciosa com o motor Java, visto que partes como `commentsDocument.xml` seriam ignoradas.
- **Divisão puramente por arquivo/documento:** Descartada para o caso de textos idênticos repetidos (como logotipos), migrando para agrupamento normalizado por conteúdo do texto alternativo.

---

## Limitações e Próximos Passos
- Treinamento do modelo (`src/training/`) e serviço de inferência (`src/inference/`) mantidos pendentes para a sequência da Slice 4/Slice 5.
