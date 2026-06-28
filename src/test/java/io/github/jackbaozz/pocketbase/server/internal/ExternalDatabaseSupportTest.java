package io.github.jackbaozz.pocketbase.server.internal;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExternalDatabaseSupportTest {

    @Test
    void resolvesGenericDbPropertiesBeforePrefixSpecificValues() {
        Map<String, String> properties = new HashMap<>();
        properties.put("db.url", "jdbc:mysql://generic/db");
        properties.put("db.user", "generic-user");
        properties.put("db.password", "generic-password");
        properties.put("mysql.url", "jdbc:mysql://prefix/db");

        ExternalDatabaseSupport.ResolvedConfig resolved = ExternalDatabaseSupport.resolve(
                "mysql",
                properties::get,
                key -> null
        );

        assertNotNull(resolved);
        assertEquals("jdbc:mysql://generic/db", resolved.url());
        assertEquals("generic-user", resolved.user());
        assertEquals("generic-password", resolved.password());
        assertEquals("system property db.url", resolved.source());
    }

    @Test
    void resolvesPrefixSpecificAndTestEnvironmentAliases() {
        Map<String, String> env = new HashMap<>();
        env.put("PB_POSTGRES_TEST_URL", "jdbc:postgresql://localhost:5432/pocketbase");
        env.put("PB_POSTGRES_TEST_USER", "pb");
        env.put("PB_POSTGRES_TEST_PASSWORD", "secret");

        ExternalDatabaseSupport.ResolvedConfig resolved = ExternalDatabaseSupport.resolve(
                "postgres",
                key -> null,
                env::get
        );

        assertNotNull(resolved);
        assertEquals("jdbc:postgresql://localhost:5432/pocketbase", resolved.url());
        assertEquals("pb", resolved.user());
        assertEquals("secret", resolved.password());
        assertEquals("environment PB_POSTGRES_TEST_URL", resolved.source());
    }

    @Test
    void returnsNullWhenNoExternalConfigurationExists() {
        ExternalDatabaseSupport.ResolvedConfig resolved = ExternalDatabaseSupport.resolve(
                "mysql",
                key -> null,
                key -> null
        );

        assertNull(resolved);
    }
}
