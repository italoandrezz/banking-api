package com.italo.bankingapi.config.secrets;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class SecretFiles {
    private SecretFiles() { }

    public static String read(Path path) {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw new IllegalArgumentException();
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(4097); }
            if (bytes.length == 0 || bytes.length > 4096) throw new IllegalArgumentException();
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (value.isBlank() || value.chars().anyMatch(c -> c == 0xfeff || Character.isISOControl(c)))
                throw new IllegalArgumentException();
            return value;
        } catch (Exception failure) {
            // No path, value or nested exception: these can disclose deployment information.
            throw new IllegalArgumentException("Secret file must be readable UTF-8, 1..4096 bytes, without BOM or control characters");
        }
    }

    public static String resolve(Map<String, String> environment, String name) {
        String file = environment.get(name + "_FILE");
        String value = environment.get(name);
        if (file != null) {
            if (value != null || file.isBlank()) throw new IllegalArgumentException("Ambiguous or invalid secret configuration");
            try { return read(Path.of(file)); }
            catch (Exception failure) { throw new IllegalArgumentException("Invalid secret file configuration"); }
        }
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required secret is missing");
        return value;
    }
}
