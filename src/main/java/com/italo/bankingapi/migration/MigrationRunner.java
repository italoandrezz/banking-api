package com.italo.bankingapi.migration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;

/** One-shot entry point. Never starts Spring, HTTP, JPA or the runtime datasource. */
public final class MigrationRunner {
    private MigrationRunner() { }

    public static void main(String[] args) {
        // Third-party exceptions may contain SQL, identifiers or connection properties.
        // This process emits only the fixed events below, never native diagnostic messages.
        var logging = (LoggerContext) LoggerFactory.getILoggerFactory();
        logging.reset();
        logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
        java.util.logging.LogManager.getLogManager().reset();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            System.err.println("{\"event\":\"migration_failed\",\"stage\":\"process\"}");
            System.exit(1);
        });
        System.exit(execute(System.getenv(), args));
    }

    static int execute(Map<String, String> environment, String[] args) {
        String stage = "configuration";
        try {
            if (args.length != 0) throw new IllegalArgumentException("Arguments are not supported");
            var settings = settings(environment);
            stage = "migration_lock";
            // Flyway's history lock does not serialize afterMigrate permission callbacks.
            // A session lock covers validation, migrations AND callbacks, without schema changes.
            try (var lock = DriverManager.getConnection(settings.url(), settings.username(), settings.password())) {
                acquireLock(lock);
                stage = "validate_and_migrate";
                var result = Flyway.configure()
                    .dataSource(settings.url(), settings.username(), settings.password())
                    .locations("classpath:db/migration", "classpath:db/callback")
                    .defaultSchema("public").schemas("public")
                    .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false)
                    .validateOnMigrate(true).outOfOrder(false).loggers("slf4j")
                    .load().migrate();
                System.out.println("{\"event\":\"migration_completed\",\"migrationsExecuted\":"
                        + result.migrationsExecuted + "}");
            } // Closing the connection releases the session lock, including on failure.
            return 0;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("{\"event\":\"migration_failed\",\"stage\":\"" + stage
                    + "\",\"reason\":\"" + reason(failure) + "\"}");
            return 1;
        }
    }

    private static void acquireLock(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var role = statement.executeQuery("SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls) FROM pg_roles WHERE rolname=current_user")) {
                if (!role.next() || !role.getBoolean(1)) throw new IllegalStateException("Administrative migration role forbidden");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            do {
                // Stable key reserved for Banking API migrations, scoped to this database.
                try (var result = statement.executeQuery("SELECT pg_try_advisory_lock(4774188638755047250)")) {
                    if (result.next() && result.getBoolean(1)) return;
                }
                Thread.sleep(250);
            } while (System.nanoTime() < deadline);
            throw new IllegalStateException("Migration lock timeout");
        }
    }

    private static String reason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getMessage() != null
                    && sql.getMessage().contains("tuple concurrently updated")) return "concurrent_catalog_update";
        }
        return "configuration_or_migration";
    }

    static Settings settings(Map<String, String> environment) {
        String url = required(environment, "MIGRATION_DATABASE_URL");
        String username = required(environment, "FLYWAY_USERNAME");
        String password = com.italo.bankingapi.config.secrets.SecretFiles.resolve(environment, "FLYWAY_PASSWORD");
        if (!url.startsWith("jdbc:postgresql://") || !username.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Invalid migration connection configuration");
        }
        return new Settings(url, username, password);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    // Deliberately no generated record toString containing the password.
    static final class Settings {
        private final String url, username, password;
        Settings(String url, String username, String password) {
            this.url = url; this.username = username; this.password = password;
        }
        String url() { return url; }
        String username() { return username; }
        String password() { return password; }
    }
}
