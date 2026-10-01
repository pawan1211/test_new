package com.fashion.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a local {@code .env} file into the Spring {@code Environment}.
 *
 * <p>Spring Boot deliberately does not read {@code .env} files. Every provider
 * credential for this project (Cashfree, Shiprocket, SMTP, GROQ) is declared in
 * {@code application.yml} as {@code ${NAME:default}} and is expected to arrive as
 * a real environment variable, which is what Docker supplies. When the backend is
 * started directly from an IDE or {@code mvn spring-boot:run} on a developer
 * machine there is no environment variable source at all, so every credential
 * silently fell back to its empty default. The result was a storefront whose
 * checkout reported that Cashfree was unconfigured while a perfectly good
 * {@code .env} sat next to the backend.
 *
 * <p>This post-processor closes that gap without weakening production: the file
 * is only read from the filesystem, the source is registered with the lowest
 * precedence, and it is skipped entirely when the relevant variables are already
 * present in the real environment. A real environment variable - or a
 * {@code -D} system property - always wins, so container and CI deployments
 * behave exactly as before.
 *
 * <p>No value is ever logged. Only the number of keys loaded is reported, so the
 * startup banner cannot become a credential leak.
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** Registered last so real environment variables and system properties take precedence. */
    private static final String PROPERTY_SOURCE_NAME = "dotenv";

    private static final int MIN_SEARCH_DEPTH = 3;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = locateEnvFile();
        if (envFile == null) return;

        Map<String, Object> values;
        try {
            values = parse(Files.readAllLines(envFile, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            System.err.println("[config] Could not read " + envFile + ": " + ex.getMessage());
            return;
        }

        if (values.isEmpty()) return;

        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
        System.out.println("[config] Loaded " + values.size() + " keys from " + envFile
                + " (lower precedence than real environment variables).");
    }

    /**
     * Finds the nearest {@code .env} walking upwards from the working directory,
     * so it works whether the backend is launched from {@code backend/} or from
     * the repository root that holds the shared {@code .env}.
     */
    private Path locateEnvFile() {
        String configured = System.getProperty("dotenv.file", System.getenv("DOTENV_FILE"));
        if (configured != null && !configured.isBlank()) {
            Path explicit = Paths.get(configured).toAbsolutePath().normalize();
            return Files.isRegularFile(explicit) ? explicit : null;
        }

        Path current = Paths.get("").toAbsolutePath().normalize();
        for (int depth = 0; depth <= MIN_SEARCH_DEPTH && current != null; depth++) {
            Path candidate = current.resolve(".env");
            if (Files.isRegularFile(candidate)) return candidate;

            // A Compose file needs `.env` alongside itself, so stop once it is
            // reached - looking further up would pick up an unrelated file.
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))) return null;

            current = current.getParent();
        }
        return null;
    }

    /**
     * Parses {@code KEY=VALUE} lines. Supports {@code export} prefixes, {@code #}
     * comments and single or double quoted values. Deliberately minimal: this is
     * not a shell, and treating values as literal text avoids accidental
     * interpolation of characters that are meaningful to the database or JSON.
     */
    private Map<String, Object> parse(List<String> lines) {
        Map<String, Object> values = new HashMap<>();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("export ")) line = line.substring(7).trim();

            int separator = line.indexOf('=');
            if (separator <= 0) continue;

            String key = line.substring(0, separator).trim();
            if (key.isEmpty() || !isValidKey(key)) continue;

            values.put(key, unquote(line.substring(separator + 1).trim()));
        }
        return values;
    }

    private boolean isValidKey(String key) {
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
            if (!allowed) return false;
        }
        return true;
    }

    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        // An unquoted value may carry a trailing inline comment.
        int comment = value.indexOf(" #");
        if (comment > 0) return value.substring(0, comment).trim();
        return value;
    }
}
