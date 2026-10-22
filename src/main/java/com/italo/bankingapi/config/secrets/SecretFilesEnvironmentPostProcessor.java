package com.italo.bankingapi.config.secrets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import java.nio.file.Path;

/** Validates file-only deployments after native configtree loading, before beans/logging start. */
public final class SecretFilesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String directory = environment.getProperty("banking.secrets.directory");
        if (directory == null) return; // Explicit legacy local/test environment remains supported.
        try {
            if (directory.isBlank()) throw new IllegalArgumentException();
            for (String key : new String[]{"DATABASE_PASSWORD", "JWT_SECRET", "FLYWAY_PASSWORD", "FLYWAY_PASSWORD_FILE", "POSTGRES_PASSWORD"})
                if (environment.getProperty(key) != null) throw new IllegalArgumentException();
            for (String key : new String[]{"spring.datasource.password", "jwt.secret"}) {
                String value = SecretFiles.read(Path.of(directory).resolve(key));
                if (!value.equals(environment.getProperty(key))) throw new IllegalArgumentException();
            }
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid file-only API secrets configuration");
        }
    }
}
