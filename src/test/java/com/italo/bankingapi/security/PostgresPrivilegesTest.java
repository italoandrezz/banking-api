package com.italo.bankingapi.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Always provisions its own tmpfs cluster; never uses TEST_DATABASE_URL. */
class PostgresPrivilegesTest {
    private static final String NAME = "banking-privileges-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String ADMIN = "security_admin", MIGRATOR = "security_migrator", APP = "security_app";
    private static final Map<String, String> PASSWORDS = Map.of(ADMIN, UUID.randomUUID().toString(),
            MIGRATOR, UUID.randomUUID().toString(), APP, UUID.randomUUID().toString());
    private static String url;
    private static String previousHistory;

    private static String docker(String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("docker"); command.addAll(java.util.List.of(args));
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().putAll(Map.of("POSTGRES_PASSWORD", PASSWORDS.get(ADMIN),
                "DATABASE_PASSWORD", PASSWORDS.get(APP), "FLYWAY_PASSWORD", PASSWORDS.get(MIGRATOR)));
        var process = builder.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Docker timeout"); }
        if (process.exitValue() != 0) throw new IllegalStateException("Isolated PostgreSQL Docker operation failed");
        return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private static Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(url, role, PASSWORDS.get(role));
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(url, MIGRATOR, PASSWORDS.get(MIGRATOR))
                .locations("classpath:db/migration", "classpath:db/callback").cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    @BeforeAll
    static void provisionAndUpgrade() throws Exception {
        try {
            docker("run", "-d", "--name", NAME, "--label", "banking.test-suite=postgres-privileges",
                    "--tmpfs", "/var/lib/postgresql/data", "-p", "127.0.0.1::5432",
                    "-v", Path.of("docker/postgres").toAbsolutePath() + ":/docker-entrypoint-initdb.d:ro",
                    "-e", "POSTGRES_DB=banking_security_test", "-e", "POSTGRES_USER=" + ADMIN,
                    "-e", "POSTGRES_PASSWORD", "-e", "DATABASE_USERNAME=" + APP, "-e", "DATABASE_PASSWORD",
                    "-e", "FLYWAY_USERNAME=" + MIGRATOR, "-e", "FLYWAY_PASSWORD", "postgres:17-alpine");
            String binding = docker("port", NAME, "5432");
            assertTrue(binding.matches("127\\.0\\.0\\.1:\\d+"));
            url = "jdbc:postgresql://" + binding + "/banking_security_test?connectTimeout=2";
            boolean ready = false;
            for (int i = 0; i < 60; i++) {
                try (var connection = connect(MIGRATOR)) { ready = true; break; }
                catch (SQLException ignored) { Thread.sleep(1000); }
            }
            assertTrue(ready, "Fresh database provisioning must succeed");
            flyway("4").migrate();
            try (var migration = connect(MIGRATOR); var app = connect(APP)) {
                previousHistory = scalar(migration, "SELECT string_agg(version || ':' || checksum, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version IS NOT NULL");
                execute(app, "INSERT INTO customers(id,full_name,cpf,email,password,birth_date) VALUES ('00000000-0000-0000-0000-000000000001','Security fixture','12345678901','fixture@example.test','synthetic','2000-01-01')");
                execute(app, "INSERT INTO accounts(id,customer_id,account_number,balance,status) VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','SECURITY',100.00,'ACTIVE')");
                execute(app, "INSERT INTO transactions(id,account_id,type,amount) VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','DEPOSIT',100.00)");
            }
            assertEquals(1, flyway(null).migrate().migrationsExecuted);
            flyway(null).validate();
        } catch (Exception | AssertionError failure) {
            docker("rm", "-f", NAME);
            throw failure;
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        docker("rm", "-f", NAME);
        assertEquals("", docker("ps", "-aq", "--filter", "name=^/" + NAME + "$"));
    }

    @Test
    void upgradePreservesDataHistoryAndFinancialConstraints() throws Exception {
        try (var migration = connect(MIGRATOR); var app = connect(APP)) {
            assertEquals(previousHistory, scalar(migration, "SELECT string_agg(version || ':' || checksum, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::int <= 4"));
            assertEquals("5", scalar(migration, "SELECT count(*) FROM flyway_schema_history WHERE success AND installed_by = current_user"));
            assertEquals("100.00", scalar(app, "SELECT balance FROM accounts"));
            assertEquals("1", scalar(app, "SELECT count(*) FROM transactions WHERE original_transaction_id IS NULL"));
            assertEquals("23514", assertThrows(SQLException.class, () -> execute(app, "UPDATE accounts SET balance=-1")).getSQLState());
            assertEquals("23514", assertThrows(SQLException.class, () -> execute(app, "UPDATE transactions SET amount=0")).getSQLState());
            execute(app, "BEGIN; SELECT * FROM accounts FOR UPDATE; UPDATE accounts SET balance=90; ROLLBACK;");
            assertEquals("100.00", scalar(app, "SELECT balance FROM accounts"));
        }
    }

    @Test
    void runtimeCannotAdministerOrChangeStructures() throws Exception {
        try (var admin = connect(ADMIN); var migration = connect(MIGRATOR); var app = connect(APP)) {
            assertEquals(ADMIN, scalar(admin, "SELECT current_user"));
            assertEquals(MIGRATOR, scalar(migration, "SELECT current_user"));
            assertEquals(APP, scalar(app, "SELECT current_user"));
            for (var connection : new Connection[]{app, migration}) {
                assertEquals("0", scalar(connection, "SELECT count(*) FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)"));
                assertEquals("0", scalar(connection, "SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)"));
            }
            assertEquals("0", scalar(app, "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user)"));
            for (String sql : new String[]{"CREATE TABLE public.forbidden(id int)", "ALTER TABLE accounts ADD COLUMN forbidden int",
                    "DROP TABLE addresses", "CREATE ROLE forbidden_role", "CREATE DATABASE forbidden_db",
                    "CREATE SCHEMA forbidden", "CREATE TEMP TABLE forbidden(id int)", "TRUNCATE addresses",
                    "SELECT * FROM flyway_schema_history", "SET ROLE security_migrator", "SET ROLE security_admin"}) {
                assertEquals("42501", assertThrows(SQLException.class, () -> execute(app, sql)).getSQLState(), sql);
            }
        }
    }

    @Test
    void futureObjectsRemainPrivateUntilExplicitlyGranted() throws Exception {
        try (var migration = connect(MIGRATOR); var app = connect(APP)) {
            execute(migration, "CREATE TABLE future_table(id bigint); CREATE SEQUENCE future_sequence; CREATE FUNCTION future_function() RETURNS int LANGUAGE sql AS 'SELECT 1'");
            try {
                execute(migration, "ALTER TABLE future_table ADD COLUMN note text");
                for (String sql : new String[]{"SELECT * FROM future_table", "SELECT nextval('future_sequence')", "SELECT future_function()"}) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> execute(app, sql)).getSQLState());
                }
                execute(migration, "GRANT SELECT, INSERT ON future_table TO security_app; GRANT USAGE ON SEQUENCE future_sequence TO security_app");
                execute(app, "INSERT INTO future_table(id) VALUES(nextval('future_sequence'))");
                assertEquals("1", scalar(app, "SELECT count(*) FROM future_table"));
            } finally {
                execute(migration, "DROP TABLE future_table; DROP SEQUENCE future_sequence; DROP FUNCTION future_function()");
            }
        }
    }
}
