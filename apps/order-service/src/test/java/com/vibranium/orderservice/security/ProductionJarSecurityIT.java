package com.vibranium.orderservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de verificação pós-build: garante que o JAR de produção NÃO contém
 * classes E2E ({@code E2eSecurityConfig}, {@code E2eDataSeederController}).
 *
 * <p>Executa na fase {@code integration-test} (Failsafe) porque precisa do JAR
 * já empacotado pelo {@code spring-boot-maven-plugin:repackage} na fase {@code package}.</p>
 *
 * <p>Usa a API {@link JarFile} do Java para introspecção do artefato final.</p>
 */
@DisplayName("ProductionJarSecurityIT — JAR de produção não contém classes E2E")
class ProductionJarSecurityIT {

    /**
     * Localiza o JAR Spring Boot repackaged no diretório target/.
     * Ignora o .original (JAR pré-repackage) e test-jars.
     */
    private Path findProductionJar() throws IOException {
        Path targetDir = Path.of("target");
        assertThat(targetDir).isDirectory();

        List<Path> jars = Files.list(targetDir)
                .filter(p -> p.toString().endsWith(".jar"))
                .filter(p -> !p.toString().endsWith("-tests.jar"))
                .filter(p -> !p.toString().endsWith(".jar.original"))
                .toList();

        assertThat(jars)
                .as("Deve existir exatamente 1 JAR de produção em target/")
                .hasSize(1);

        return jars.get(0);
    }

    @Test
    @DisplayName("JAR de produção NÃO deve conter E2eSecurityConfig.class")
    void jarShouldNotContainE2eSecurityConfig() throws Exception {
        Path jarPath = findProductionJar();

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<String> e2eSecurityEntries = jar.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.contains("E2eSecurityConfig"))
                    .toList();

            assertThat(e2eSecurityEntries)
                    .as("E2eSecurityConfig NÃO deve estar no JAR de produção — "
                            + "risco de bypass de segurança se perfil e2e for ativado")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("JAR de produção NÃO deve conter E2eDataSeederController.class")
    void jarShouldNotContainE2eDataSeederController() throws Exception {
        Path jarPath = findProductionJar();

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<String> e2eSeederEntries = jar.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.contains("E2eDataSeederController"))
                    .toList();

            assertThat(e2eSeederEntries)
                    .as("E2eDataSeederController NÃO deve estar no JAR de produção — "
                            + "risco de injeção de dados via endpoint /e2e/**")
                    .isEmpty();
        }
    }
}
