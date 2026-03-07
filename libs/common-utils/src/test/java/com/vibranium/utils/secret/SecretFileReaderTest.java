package com.vibranium.utils.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Testes unitários do {@link SecretFileReader}.
 *
 * <p>Fase RED (TDD): estes testes foram escritos ANTES da implementação
 * para validar o contrato do reader de Docker Secrets.</p>
 *
 * <h2>Cenários cobertos</h2>
 * <ul>
 *   <li>Leitura de secret file com senha válida</li>
 *   <li>Trim automático de whitespace e newlines</li>
 *   <li>Rejeição de arquivo vazio</li>
 *   <li>Erro ao ler arquivo inexistente</li>
 *   <li>Fallback para variável de ambiente</li>
 *   <li>Retorno null quando nenhuma fonte está disponível</li>
 * </ul>
 */
@DisplayName("SecretFileReader — Docker Secrets reader")
class SecretFileReaderTest {

    @TempDir
    Path tempDir;

    // ====================================================================
    // readSecretFile — leitura direta de arquivo
    // ====================================================================

    @Nested
    @DisplayName("readSecretFile()")
    class ReadSecretFile {

        @Test
        @DisplayName("deve carregar senha de arquivo de secret corretamente")
        void shouldLoadPasswordFromSecretFile() throws IOException {
            // Arrange: cria temp file simulando /run/secrets/db_password
            Path secretFile = tempDir.resolve("db_password");
            Files.writeString(secretFile, "mySuperSecretPassword123");

            // Act
            String result = SecretFileReader.readSecretFile(secretFile);

            // Assert
            assertEquals("mySuperSecretPassword123", result);
        }

        @Test
        @DisplayName("deve aplicar trim em whitespace e newlines do conteúdo")
        void shouldTrimWhitespaceAndNewlines() throws IOException {
            // Arrange: secret com newline no final (comportamento comum no Docker)
            Path secretFile = tempDir.resolve("db_password");
            Files.writeString(secretFile, "  secretWithSpaces  \n\n");

            // Act
            String result = SecretFileReader.readSecretFile(secretFile);

            // Assert
            assertEquals("secretWithSpaces", result);
        }

        @Test
        @DisplayName("deve aplicar trim em tab e carriage return")
        void shouldTrimTabsAndCarriageReturn() throws IOException {
            Path secretFile = tempDir.resolve("db_password");
            Files.writeString(secretFile, "\t\r\npassword123\r\n\t");

            String result = SecretFileReader.readSecretFile(secretFile);

            assertEquals("password123", result);
        }

        @Test
        @DisplayName("deve lançar SecretReadException para arquivo vazio")
        void shouldThrowExceptionForEmptyFile() throws IOException {
            Path secretFile = tempDir.resolve("empty_secret");
            Files.writeString(secretFile, "");

            SecretReadException exception = assertThrows(
                    SecretReadException.class,
                    () -> SecretFileReader.readSecretFile(secretFile)
            );

            assertNotNull(exception.getMessage());
            // Garante que a exceção não vaza o conteúdo do secret
            assertEquals("Secret file is empty: " + secretFile, exception.getMessage());
        }

        @Test
        @DisplayName("deve lançar SecretReadException para arquivo apenas com whitespace")
        void shouldThrowExceptionForWhitespaceOnlyFile() throws IOException {
            Path secretFile = tempDir.resolve("whitespace_secret");
            Files.writeString(secretFile, "   \n\n  \t  ");

            assertThrows(SecretReadException.class, () -> SecretFileReader.readSecretFile(secretFile));
        }

        @Test
        @DisplayName("deve lançar SecretReadException para arquivo inexistente")
        void shouldThrowExceptionForNonExistentFile() {
            Path secretFile = tempDir.resolve("nonexistent_secret");

            assertThrows(SecretReadException.class, () -> SecretFileReader.readSecretFile(secretFile));
        }

        @Test
        @DisplayName("deve aceitar overload com String path")
        void shouldAcceptStringPath() throws IOException {
            Path secretFile = tempDir.resolve("string_path_secret");
            Files.writeString(secretFile, "testPassword");

            String result = SecretFileReader.readSecretFile(secretFile.toString());

            assertEquals("testPassword", result);
        }
    }

    // ====================================================================
    // readSecretWithFallback — prioridade: arquivo > env var > null
    // ====================================================================

    @Nested
    @DisplayName("readSecretWithFallback()")
    class ReadSecretWithFallback {

        @Test
        @DisplayName("deve priorizar arquivo de secret sobre variável de ambiente")
        void shouldPrioritizeFileOverEnvVar() throws IOException {
            // Arrange: cria secret file no tempDir
            Path secretFile = tempDir.resolve("db_password");
            Files.writeString(secretFile, "filePassword123\n");

            // Act: usa tempDir como secrets dir — simula /run/secrets/
            String result = SecretFileReader.readSecretWithFallback(
                    "db_password", "POSTGRES_PASSWORD", tempDir.toString());

            // Assert: deve retornar o valor do arquivo (com trim)
            assertEquals("filePassword123", result);
        }

        @Test
        @DisplayName("deve retornar null quando arquivo e env var não existem")
        void shouldReturnNullWhenNeitherFileNorEnvExists() {
            // Act: diretório sem arquivo + env var inexistente
            String result = SecretFileReader.readSecretWithFallback(
                    "nonexistent_secret",
                    "VIBRANIUM_TEST_NONEXISTENT_VAR_12345",
                    tempDir.toString());

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("deve retornar null quando diretório de secrets não existe")
        void shouldReturnNullWhenSecretsDirDoesNotExist() {
            String result = SecretFileReader.readSecretWithFallback(
                    "db_password",
                    "VIBRANIUM_TEST_NONEXISTENT_VAR_12345",
                    "/nonexistent/path/to/secrets");

            assertNull(result);
        }
    }
}
