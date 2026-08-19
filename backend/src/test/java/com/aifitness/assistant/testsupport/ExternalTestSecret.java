package com.aifitness.assistant.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class ExternalTestSecret {
    private ExternalTestSecret() {}

    public static String read(String environmentValue, String filePath, String label) {
        boolean hasEnvironmentValue = environmentValue != null && !environmentValue.isEmpty();
        boolean hasFile = filePath != null && !filePath.isBlank();
        if (hasEnvironmentValue && hasFile) {
            throw new IllegalArgumentException(label + " must use either an environment value or a secret file");
        }
        if (!hasFile) return environmentValue;

        Path path;
        try {
            path = Path.of(filePath).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException(label + " secret file path is invalid");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(label + " secret file must be a regular non-symlink file");
        }
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8);
            if (value.endsWith("\r\n")) value = value.substring(0, value.length() - 2);
            else if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
            if (value.isEmpty()) throw new IllegalArgumentException(label + " secret file must not be empty");
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(label + " secret file must contain exactly one line");
            }
            return value;
        } catch (IOException ignored) {
            throw new IllegalArgumentException(label + " secret file cannot be read");
        }
    }
}
