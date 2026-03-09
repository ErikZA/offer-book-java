# Contribuição de Documentação

Regras para manter a documentação consistente e evitar duplicação.

## Fonte única por tema

- Onboarding: `README.md`
- Índice geral: `docs/README.md`
- Testes: `docs/testing/COMPREHENSIVE_TESTING.md`
- Arquitetura: `docs/architecture/README.md`
- Dados/modelagem: `docs/database/README.md`
- Infra: `infra/README.md`

## Como atualizar

1. Identifique o documento canônico do tema.
2. Atualize primeiro o canônico.
3. Atualize documentos ponte apenas com resumo e link.
4. Se houver substituição grande, preserve versão anterior em `docs/archive/`.
5. Atualize `docs/DOCS_TRACEABILITY_MATRIX.md` se mover conteúdo entre arquivos.

## Estados de documento

- `canônico`: fonte oficial.
- `ponte`: resumo com redirecionamento.
- `histórico`: referência legada sem evolução ativa.

## Checklist antes de finalizar

- Links internos válidos.
- Comandos testáveis e consistentes com scripts reais.
- Sem duplicação desnecessária entre README de módulo e docs centrais.
- Documento com seção `Estado do Documento` quando for canônico/ponte.
