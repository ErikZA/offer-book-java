package com.vibranium.utils.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lê credenciais de arquivos Docker Secret ({@code /run/secrets/}).
 *
 * <p>O Docker Swarm e Docker Compose montam secrets como arquivos em
 * {@code /run/secrets/<secretname>}. Este reader:
 * <ul>
 *   <li>Lê o conteúdo do arquivo com trim de whitespace/newlines</li>
 *   <li>Fornece fallback para variável de ambiente quando o arquivo não existe</li>
 *   <li><strong>NUNCA</strong> loga o conteúdo do secret</li>
 * </ul>
 *
 * <h2>Uso típico</h2>
 * <pre>{@code
 *   String dbPassword = SecretFileReader.readSecret(
 *       "/run/secrets/db_password",
 *       "POSTGRES_PASSWORD"
 *   );
 * }</pre>
 *
 * @see DockerSecretEnvironmentPostProcessor
 */
public final class SecretFileReader {

    private static final Logger logger = LoggerFactory.getLogger(SecretFileReader.class);

    /** Diretório padrão onde Docker monta os secrets. */
    public static final String DEFAULT_SECRETS_DIR = "/run/secrets";

    private SecretFileReader() {
        // Utility class — não instanciável
    }

    /**
     * Lê o conteúdo de um arquivo de secret, com trim automático de whitespace e newlines.
     *
     * @param secretPath caminho absoluto do arquivo (ex: {@code /run/secrets/db_password})
     * @return conteúdo do secret com trim aplicado
     * @throws SecretReadException se o arquivo não puder ser lido
     */
    public static String readSecretFile(String secretPath) {
        return readSecretFile(Path.of(secretPath));
    }

    /**
     * Lê o conteúdo de um arquivo de secret, com trim automático de whitespace e newlines.
     *
     * @param secretPath {@link Path} do arquivo de secret
     * @return conteúdo do secret com trim aplicado
     * @throws SecretReadException se o arquivo não puder ser lido
     */
    public static String readSecretFile(Path secretPath) {
        try {
            String content = Files.readString(secretPath).trim();
            if (content.isEmpty()) {
                throw new SecretReadException("Secret file is empty: " + secretPath);
            }
            // Log apenas a existência e tamanho — NUNCA o conteúdo
            logger.debug("Secret loaded from file: {} ({} chars)", secretPath, content.length());
            return content;
        } catch (IOException e) {
            throw new SecretReadException("Failed to read secret file: " + secretPath, e);
        }
    }

    /**
     * Lê um secret de arquivo Docker com fallback para variável de ambiente.
     *
     * <p>Prioridade:
     * <ol>
     *   <li>Arquivo em {@code /run/secrets/<secretName>} (se existir)</li>
     *   <li>Variável de ambiente {@code envVarName} (se definida)</li>
     *   <li>{@code null} se nenhum dos dois estiver disponível</li>
     * </ol>
     *
     * @param secretName  nome do secret file (ex: {@code db_password})
     * @param envVarName  nome da variável de ambiente (ex: {@code POSTGRES_PASSWORD})
     * @return valor do secret, ou {@code null} se não encontrado em nenhuma fonte
     */
    public static String readSecretWithFallback(String secretName, String envVarName) {
        return readSecretWithFallback(secretName, envVarName, DEFAULT_SECRETS_DIR);
    }

    /**
     * Lê um secret com fallback para variável de ambiente, usando diretório customizado.
     *
     * @param secretName  nome do secret file
     * @param envVarName  nome da variável de ambiente para fallback
     * @param secretsDir  diretório base dos secrets
     * @return valor do secret, ou {@code null} se não encontrado
     */
    public static String readSecretWithFallback(String secretName, String envVarName, String secretsDir) {
        Path secretPath = Path.of(secretsDir, secretName);

        // Prioridade 1: arquivo de secret
        if (Files.exists(secretPath) && Files.isReadable(secretPath)) {
            logger.info("Loading secret '{}' from Docker secret file", secretName);
            return readSecretFile(secretPath);
        }

        // Prioridade 2: variável de ambiente
        String envValue = System.getenv(envVarName);
        if (envValue != null && !envValue.isBlank()) {
            logger.info("Loading secret '{}' from environment variable '{}'", secretName, envVarName);
            return envValue.trim();
        }

        logger.warn("Secret '{}' not found in file ({}) nor env var ({})", secretName, secretPath, envVarName);
        return null;
    }
}
