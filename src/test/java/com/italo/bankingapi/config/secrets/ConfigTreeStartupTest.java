package com.italo.bankingapi.config.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ConfigTreeStartupTest {
    @TempDir Path directory;
    private static final String PASSWORD = "synthetic-dml-file-only";
    private static final String JWT = "synthetic-jwt-file-only-01234567890123456789";
    @Configuration(proxyBeanMethods = false) static class EmptyApplication { }

    private String[] arguments(String... extra) throws Exception {
        Files.writeString(directory.resolve("spring.datasource.password"), PASSWORD);
        Files.writeString(directory.resolve("jwt.secret"), JWT);
        var args = new ArrayList<>(List.of("--spring.config.import=configtree:" + directory.toString().replace('\\', '/') + "/",
                "--banking.secrets.directory=" + directory, "--spring.main.banner-mode=off"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }
    private SpringApplication application() {
        var app = new SpringApplication(EmptyApplication.class);
        // This fixture models the API's file-only environment, not the test database provisioner's.
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        app.setEnvironment(environment);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        return app;
    }
    @Test void actualBootStartupLoadsConfigtreeAndKeepsDdlAbsent(CapturedOutput output) throws Exception {
        try (var context = application().run(arguments())) {
            var env = context.getEnvironment();
            assertEquals(PASSWORD, env.getProperty("spring.datasource.password"));
            assertEquals(JWT, env.getProperty("jwt.secret"));
            assertEquals("false", env.getProperty("spring.flyway.enabled"));
            for (String key : List.of("FLYWAY_PASSWORD", "FLYWAY_PASSWORD_FILE", "POSTGRES_PASSWORD"))
                assertNull(env.getProperty(key));
        }
        assertFalse(output.getAll().contains(PASSWORD));
        assertFalse(output.getAll().contains(JWT));
    }
    @Test void registeredValidatorRejectsOverrideAndDdlWithoutLoggingValues(CapturedOutput output) throws Exception {
        for (String option : List.of("--jwt.secret=synthetic-override", "--FLYWAY_PASSWORD=synthetic-ddl",
                "--FLYWAY_PASSWORD_FILE=synthetic-private-path")) {
            var args = arguments(option);
            var failure = assertThrows(IllegalArgumentException.class, () -> application().run(args));
            assertEquals("Invalid file-only API secrets configuration", failure.getMessage());
        }
        for (String value : List.of(PASSWORD, JWT, "synthetic-override", "synthetic-ddl", "synthetic-private-path"))
            assertFalse(output.getAll().contains(value));
    }
    @Test void missingOrMalformedFileFailsBeforeBeans(CapturedOutput output) throws Exception {
        var args = arguments();
        Files.delete(directory.resolve("jwt.secret"));
        assertThrows(IllegalArgumentException.class, () -> application().run(args));
        Files.writeString(directory.resolve("jwt.secret"), JWT + "\n");
        assertThrows(IllegalArgumentException.class, () -> application().run(args));
        assertFalse(output.getAll().contains(JWT));
    }
}
