package com.italo.bankingapi.config.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SecretFilesTest {
    @TempDir Path directory;
    @Test void filePreservesExactBytesAndWhitespace() throws Exception {
        Path file = directory.resolve("secret"); Files.writeString(file, " synthetic value ");
        assertEquals(" synthetic value ", SecretFiles.read(file));
        assertEquals(" synthetic value ", SecretFiles.resolve(Map.of("KEY_FILE", file.toString()), "KEY"));
    }
    @Test void rejectsInvalidFormatsWithoutDisclosingContentOrPath() throws Exception {
        Path file = directory.resolve("private-name");
        for (byte[] value : new byte[][] { new byte[0], " ".getBytes(), "value\n".getBytes(), "value\r\n".getBytes(),
                "\ufeffvalue".getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[]{(byte)0xc3, 0x28}, "x".repeat(4097).getBytes(), new byte[]{0} }) {
            Files.write(file, value);
            var error = assertThrows(IllegalArgumentException.class, () -> SecretFiles.read(file));
            assertNull(error.getCause()); assertFalse(error.getMessage().contains("private-name"));
        }
    }
    @Test void rejectsMissingFilesDirectoriesAndAmbiguousSources() {
        assertThrows(IllegalArgumentException.class, () -> SecretFiles.read(directory.resolve("missing")));
        assertThrows(IllegalArgumentException.class, () -> SecretFiles.read(directory));
        assertThrows(IllegalArgumentException.class, () -> SecretFiles.resolve(Map.of("KEY", "value", "KEY_FILE", "file"), "KEY"));
        assertThrows(IllegalArgumentException.class, () -> SecretFiles.resolve(Map.of("KEY_FILE", ""), "KEY"));
        assertThrows(IllegalArgumentException.class, () -> SecretFiles.resolve(Map.of(), "KEY"));
        assertEquals("legacy", SecretFiles.resolve(Map.of("KEY", "legacy"), "KEY"));
    }
    @Test void fileOnlyApiValidatesBothNativePropertiesAndRejectsOverrides() throws Exception {
        var env = new MockEnvironment().withProperty("banking.secrets.directory", directory.toString())
                .withProperty("spring.datasource.password", "synthetic-password").withProperty("jwt.secret", "synthetic-jwt");
        Files.writeString(directory.resolve("spring.datasource.password"), "synthetic-password");
        Files.writeString(directory.resolve("jwt.secret"), "synthetic-jwt");
        var validator = new SecretFilesEnvironmentPostProcessor();
        validator.postProcessEnvironment(env, null);
        env.setProperty("jwt.secret", "override");
        assertThrows(IllegalArgumentException.class, () -> validator.postProcessEnvironment(env, null));
        env.setProperty("jwt.secret", "synthetic-jwt"); env.setProperty("JWT_SECRET", "unexpected");
        assertThrows(IllegalArgumentException.class, () -> validator.postProcessEnvironment(env, null));
        validator.postProcessEnvironment(new MockEnvironment(), null);
    }
    @Test void jwtRotationRejectsOldTokens() throws Exception {
        Path file = directory.resolve("jwt.secret"); Files.writeString(file, "a".repeat(64));
        var old = new com.italo.bankingapi.service.JwtService(SecretFiles.read(file), 60000);
        String token = old.generateToken(java.util.UUID.randomUUID());
        Files.writeString(file, "b".repeat(64));
        var rotated = new com.italo.bankingapi.service.JwtService(SecretFiles.read(file), 60000);
        assertThrows(io.jsonwebtoken.JwtException.class, () -> rotated.extractCustomerId(token));
        assertNotNull(rotated.extractCustomerId(rotated.generateToken(java.util.UUID.randomUUID())));
    }
}
