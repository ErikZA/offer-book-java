-- ==============================================================================
-- V6: Índice parcial para SagaTimeoutCleanupJob (AT-09.1)
--
-- O SagaTimeoutCleanupJob executa periodicamente:
--
--     SELECT * FROM tb_orders
--      WHERE status = 'PENDING'
--        AND created_at < :cutoff
--
-- Sem índice, esta consulta requer full table scan → O(n).
-- Com índice parcial em (created_at) WHERE status = 'PENDING':
--   - O banco lê apenas as linhas PENDING (sub-conjunto pequeno em regime normal)
--   - O índice parcial é mais compacto que um índice global sobre (status, created_at)
--   - Performance O(log n) na pior das hipóteses
--
-- O índice usa BRIN seria inadequado aqui pois a coluna não tem correlação física
-- perfeita com a ordem de inserção após UPDATEs de status. B-tree é correto.
-- ==============================================================================

CREATE INDEX IF NOT EXISTS idx_orders_status_created_at
    ON tb_orders (created_at)
    WHERE status = 'PENDING';

COMMENT ON INDEX idx_orders_status_created_at IS
    'Índice parcial para o SagaTimeoutCleanupJob (AT-09.1): '
    'busca eficiente de ordens PENDING criadas antes do threshold de timeout. '
    'WHERE status = PENDING garante índice compacto — apenas linhas elegíveis para cancelamento.';
