package io.github.jackbaozz.pocketbase.server.internal;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class ExternalDatabaseSupport {
    private ExternalDatabaseSupport() {
    }

    public static ResolvedConfig resolve(String storage) {
        return resolve(storage, System::getProperty, System::getenv);
    }

    static ResolvedConfig resolve(
            String storage,
            Function<String, String> propertyReader,
            Function<String, String> envReader
    ) {
        String prefix = normalizeStorage(storage);
        String envPrefix = switch (prefix) {
            case "mysql", "mariadb" -> "MYSQL";
            case "postgres", "postgresql" -> "POSTGRES";
            default -> "";
        };
        if (envPrefix.isBlank()) {
            return null;
        }

        Value url = firstValue(List.of(
                new Candidate("system property db.url", propertyReader.apply("db.url")),
                new Candidate("system property " + prefix + ".url", propertyReader.apply(prefix + ".url")),
                new Candidate("environment PB_DATABASE_URL", envReader.apply("PB_DATABASE_URL")),
                new Candidate("environment PB_" + envPrefix + "_URL", envReader.apply("PB_" + envPrefix + "_URL")),
                new Candidate("environment PB_" + envPrefix + "_TEST_URL", envReader.apply("PB_" + envPrefix + "_TEST_URL"))
        ));
        if (url == null) {
            return null;
        }

        Value user = firstValue(List.of(
                new Candidate("system property db.user", propertyReader.apply("db.user")),
                new Candidate("system property " + prefix + ".user", propertyReader.apply(prefix + ".user")),
                new Candidate("environment PB_DATABASE_USER", envReader.apply("PB_DATABASE_USER")),
                new Candidate("environment PB_" + envPrefix + "_USER", envReader.apply("PB_" + envPrefix + "_USER")),
                new Candidate("environment PB_" + envPrefix + "_TEST_USER", envReader.apply("PB_" + envPrefix + "_TEST_USER"))
        ));
        Value password = firstValue(List.of(
                new Candidate("system property db.password", propertyReader.apply("db.password")),
                new Candidate("system property " + prefix + ".password", propertyReader.apply(prefix + ".password")),
                new Candidate("environment PB_DATABASE_PASSWORD", envReader.apply("PB_DATABASE_PASSWORD")),
                new Candidate("environment PB_" + envPrefix + "_PASSWORD", envReader.apply("PB_" + envPrefix + "_PASSWORD")),
                new Candidate("environment PB_" + envPrefix + "_TEST_PASSWORD", envReader.apply("PB_" + envPrefix + "_TEST_PASSWORD"))
        ));

        return new ResolvedConfig(
                prefix,
                url.value(),
                user == null ? null : user.value(),
                password == null ? null : password.value(),
                url.source()
        );
    }

    public static String missingUrlMessage(String storage) {
        String prefix = normalizeStorage(storage);
        String envPrefix = switch (prefix) {
            case "mysql", "mariadb" -> "MYSQL";
            case "postgres", "postgresql" -> "POSTGRES";
            default -> prefix.toUpperCase(Locale.ROOT);
        };
        return "Missing JDBC URL for " + prefix + " storage. Set -Ddb.url, -D" + prefix + ".url, PB_DATABASE_URL, or PB_" + envPrefix + "_URL.";
    }

    private static String normalizeStorage(String storage) {
        return storage == null ? "" : storage.trim().toLowerCase(Locale.ROOT);
    }

    private static Value firstValue(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            if (candidate.value() != null && !candidate.value().isBlank()) {
                return new Value(candidate.value().trim(), candidate.source());
            }
        }
        return null;
    }

    private record Candidate(String source, String value) {
    }

    private record Value(String value, String source) {
    }

    public record ResolvedConfig(
            String storage,
            String url,
            String user,
            String password,
            String source
    ) {
        public void applySystemProperties() {
            System.setProperty(storage + ".url", url);
            System.setProperty("db.url", url);
            if (user != null && !user.isBlank()) {
                System.setProperty(storage + ".user", user);
                System.setProperty("db.user", user);
            }
            if (password != null && !password.isBlank()) {
                System.setProperty(storage + ".password", password);
                System.setProperty("db.password", password);
            }
        }
    }
}
