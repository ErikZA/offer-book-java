package com.vibranium.walletservice.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste arquitetural — AT-08.2: Garantias de documentação para limitação
 * single-instance do Debezium Embedded.
 *
 * <h2>Fase RED (TDD)</h2>
 * <p>Este teste FALHA intencionalmente antes dos artefatos de documentação
 * serem criados:</p>
 * <ul>
 *   <li>{@code docs/architecture/adr-001-debezium-single-instance.md}</li>
 *   <li>Seção {@code Debezium Embedded Limitation} no {@code README.md}</li>
 * </ul>
 *
 * <h2>Fase GREEN</h2>
 * <p>Passa após a criação do ADR e atualização do README (AT-08.2).</p>
 *
 * <h2>Raciocínio</h2>
 * <p>Decisões arquiteturais críticas de um sistema financeiro devem ser
 * auditáveis como artefatos versionados no repositório — não apenas em
 * comentários de código ou wikis voláteis. Este teste garante que a restrição
 * de single-instance do Debezium Embedded permaneça documentada e visível a
 * qualquer engenheiro que faça {@code git clone} do projeto.</p>
 */
@DisplayName("AT-08.2 — Debezium Single-Instance Constraint Documentation")
class DebeziumSingleInstanceConstraintTest {

    /**
     * O Maven Surefire executa testes com o diretório de trabalho ({@code user.dir})
     * apontado para a raiz do módulo ({@code apps/wallet-service}).
     * Subindo dois níveis chegamos à raiz do projeto.
     */
    private static final Path MODULE_ROOT   = Paths.get("").toAbsolutePath();
    private static final Path PROJECT_ROOT  = MODULE_ROOT.getParent().getParent();
    private static final Path WALLET_README = MODULE_ROOT.resolve("README.md");
    private static final Path ADR_FILE      = PROJECT_ROOT.resolve(
            "docs/architecture/adr-001-debezium-single-instance.md");

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ADR-001 deve existir em docs/architecture/")
    void adrFileMustExist() {
        assertThat(ADR_FILE)
                .as("""
                        [AT-08.2 RED] ADR-001 não encontrado.
                        Caminho esperado: %s
                        Crie o arquivo 'docs/architecture/adr-001-debezium-single-instance.md'
                        para documentar a limitação single-instance do Debezium Embedded.
                        """, ADR_FILE)
                .exists()
                .isRegularFile();
    }

    @Test
    @DisplayName("ADR-001 deve conter seções obrigatórias (Contexto, Decisão, Consequências, Alternativas)")
    void adrMustContainMandatorySections() throws IOException {
        // Pré-condição: o arquivo deve existir (falha em adrFileMustExist() antes deste)
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.exists(ADR_FILE),
                "ADR-001 ainda não foi criado — executar adrFileMustExist() primeiro."
        );

        String content = Files.readString(ADR_FILE);

        assertThat(content)
                .as("[AT-08.2] ADR-001 deve conter seção '## Contexto'")
                .containsIgnoringCase("## Contexto");

        assertThat(content)
                .as("[AT-08.2] ADR-001 deve conter seção '## Decisão'")
                .containsIgnoringCase("## Decisão");

        assertThat(content)
                .as("[AT-08.2] ADR-001 deve conter seção '## Consequências'")
                .containsIgnoringCase("## Consequências");

        assertThat(content)
                .as("[AT-08.2] ADR-001 deve conter seção '## Alternativas'")
                .containsIgnoringCase("## Alternativas");
    }

    @Test
    @DisplayName("README.md deve conter seção '## Debezium Embedded Limitation'")
    void readmeMustContainDebeziumEmbeddedLimitationSection() throws IOException {
        assertThat(WALLET_README)
                .as("[AT-08.2 RED] README.md não encontrado em: %s", WALLET_README)
                .exists();

        String content = Files.readString(WALLET_README);

        assertThat(content)
                .as("""
                        [AT-08.2 RED] README.md não contém a seção obrigatória.
                        Adicione a seção '## Debezium Embedded Limitation' ao README.md
                        do wallet-service para tornar a restrição visível e auditável.
                        """)
                .contains("Debezium Embedded Limitation");
    }

    @Test
    @DisplayName("ADR-001 deve referenciar explicitamente 'single-instance' ou 'single instance'")
    void adrMustReferenceSingleInstance() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.exists(ADR_FILE),
                "ADR-001 ainda não foi criado."
        );

        String content = Files.readString(ADR_FILE);

        assertThat(content)
                .as("[AT-08.2] ADR-001 deve referenciar 'single-instance' ou 'single instance'")
                .containsPattern("(?i)single[- ]instance");
    }

    @Test
    @DisplayName("ADR-001 deve referenciar risco de WAL bloat ou WAL retention")
    void adrMustReferenceWalBloatRisk() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.exists(ADR_FILE),
                "ADR-001 ainda não foi criado."
        );

        String content = Files.readString(ADR_FILE);

        assertThat(content)
                .as("[AT-08.2] ADR-001 deve mencionar risco de WAL bloat ou WAL retention")
                .containsPattern("(?i)wal.{0,30}(bloat|retention|acumulo|crescimento)");
    }
}
