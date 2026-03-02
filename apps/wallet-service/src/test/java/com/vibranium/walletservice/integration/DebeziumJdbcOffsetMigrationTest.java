package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AT-08.1 — FASE RED] Valida que a tabela {@code wallet_outbox_offset}
 * existe no banco de dados após a execução das migrations Flyway.
 *
 * <h2>Por que este teste é RED antes da implementação?</h2>
 * <p>Antes da migration V5, a tabela {@code wallet_outbox_offset} não existe.
 * Este teste falha com {@code AssertionError} confirmando o estado RED.
 * Após a criação da migration {@code V5__create_wallet_outbox_offset.sql},
 * o Flyway cria a tabela na inicialização do contexto Spring e o teste
 * passa (estado GREEN).</p>
 *
 * <h2>Relação com a migration Flyway</h2>
 * <p>O Flyway executa as migrations <em>antes</em> do Hibernate validar as
 * entidades. Logo, se a migration V5 existir, a tabela estará presente quando
 * este teste executar — sem necessidade de nenhum setup manual.</p>
 */
@DisplayName("[AT-08.1 RED] Tabela wallet_outbox_offset deve existir após migration V5")
class DebeziumJdbcOffsetMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Teste 1 — Existência da tabela
    // -------------------------------------------------------------------------

    /**
     * RED: falha antes da migration V5 ser criada.
     * GREEN: passa após criação de {@code V5__create_wallet_outbox_offset.sql}.
     */
    @Test
    @DisplayName("Tabela wallet_outbox_offset deve existir no schema public")
    void walletOutboxOffsetTableMustExist() {
        // Consulta o catálogo do PostgreSQL para verificar existência da tabela.
        // pg_tables inclui apenas tabelas de usuário; exclui system tables.
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM pg_tables
                    WHERE schemaname = 'public'
                      AND tablename  = 'wallet_outbox_offset'
                )
                """,
                Boolean.class);

        assertThat(exists)
                .as("Tabela 'wallet_outbox_offset' deve existir após migration V5. " +
                    "Se este assert falhar, significa que V5__create_wallet_outbox_offset.sql " +
                    "ainda não foi criada (estado RED esperado antes da implementação).")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Teste 2 — Estrutura mínima da tabela
    // -------------------------------------------------------------------------

    /**
     * Verifica que a tabela possui as colunas necessárias para o
     * {@code JdbcOffsetBackingStore} do Debezium funcionar corretamente.
     *
     * <p>O Debezium 2.7.x lê e escreve as colunas {@code id}, {@code offset_key}
     * e {@code offset_val}. As demais colunas ({@code record_insert_ts},
     * {@code record_insert_seq}) são metadados de auditoria.</p>
     */
    @Test
    @DisplayName("Colunas obrigatórias do JdbcOffsetBackingStore devem existir")
    void walletOutboxOffsetTableMustHaveRequiredColumns() {
        // Consulta o catálogo de colunas do PostgreSQL
        var columns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name   = 'wallet_outbox_offset'
                ORDER BY ordinal_position
                """,
                String.class);

        assertThat(columns)
                .as("Colunas da tabela wallet_outbox_offset")
                .containsExactlyInAnyOrder(
                        "id",
                        "offset_key",
                        "offset_val",
                        "record_insert_ts",
                        "record_insert_seq");
    }

    // -------------------------------------------------------------------------
    // Teste 3 — PK da tabela
    // -------------------------------------------------------------------------

    /**
     * Garante que {@code id} é a chave primária da tabela, como requerido
     * pelo {@code JdbcOffsetBackingStore} para lookup O(1) por ID de conector.
     */
    @Test
    @DisplayName("Coluna 'id' deve ser PRIMARY KEY da tabela wallet_outbox_offset")
    void walletOutboxOffsetIdMustBePrimaryKey() {
        Boolean isPk = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM   information_schema.table_constraints tc
                    JOIN   information_schema.key_column_usage   kcu
                           ON  tc.constraint_name = kcu.constraint_name
                           AND tc.table_schema    = kcu.table_schema
                    WHERE  tc.constraint_type = 'PRIMARY KEY'
                      AND  tc.table_schema    = 'public'
                      AND  tc.table_name      = 'wallet_outbox_offset'
                      AND  kcu.column_name    = 'id'
                )
                """,
                Boolean.class);

        assertThat(isPk)
                .as("Coluna 'id' deve ser PRIMARY KEY de wallet_outbox_offset")
                .isTrue();
    }
}
