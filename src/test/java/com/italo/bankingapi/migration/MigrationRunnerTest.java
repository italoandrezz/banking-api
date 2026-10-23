package com.italo.bankingapi.migration;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MigrationRunnerTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void readsMigrationPasswordFileWithoutSpringOrRuntimeCredentials() throws Exception {
        var file = directory.resolve("migration-secret");
        java.nio.file.Files.writeString(file, "synthetic-file-only");
        var env = valid(); env.remove("FLYWAY_PASSWORD"); env.put("FLYWAY_PASSWORD_FILE", file.toString());
        assertEquals("synthetic-file-only", MigrationRunner.settings(env).password());
        env.put("FLYWAY_PASSWORD", "ambiguous");
        assertThrows(IllegalArgumentException.class, () -> MigrationRunner.settings(env));
    }
    private Map<String, String> valid() {
        return new HashMap<>(Map.of("MIGRATION_DATABASE_URL", "jdbc:postgresql://localhost/test",
                "FLYWAY_USERNAME", "test_migrator", "FLYWAY_PASSWORD", "synthetic-only"));
    }
    @Test void acceptsExplicitMigrationConfigurationWithoutRuntimeOrJwt() {
        var settings = MigrationRunner.settings(valid());
        assertEquals("test_migrator", settings.username());
        assertFalse(settings.toString().contains("synthetic-only"));
    }
    @Test void requiresEveryMigrationSettingWithoutRuntimeFallback() {
        for (String key : valid().keySet()) {
            var env = valid(); env.remove(key);
            env.put("DATABASE_URL", "jdbc:postgresql://localhost/runtime");
            env.put("DATABASE_USERNAME", "runtime"); env.put("DATABASE_PASSWORD", "runtime-password");
            assertThrows(IllegalArgumentException.class, () -> MigrationRunner.settings(env));
            env.put(key, " ");
            assertThrows(IllegalArgumentException.class, () -> MigrationRunner.settings(env));
        }
    }
    @Test void rejectsUnsupportedUrlAndRole() {
        var env = valid(); env.put("MIGRATION_DATABASE_URL", "jdbc:h2:mem:test");
        assertThrows(IllegalArgumentException.class, () -> MigrationRunner.settings(env));
        env.putAll(valid()); env.put("FLYWAY_USERNAME", "invalid role");
        assertThrows(IllegalArgumentException.class, () -> MigrationRunner.settings(env));
    }
    @Test void rejectsArgumentsRatherThanExposingCleanOrRepairCommands() {
        for (String argument : new String[]{"clean", "repair", "baseline", "--help"})
            assertEquals(1, MigrationRunner.execute(valid(), new String[]{argument}));
    }
}
