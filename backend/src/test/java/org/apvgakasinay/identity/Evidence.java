package org.apvgakasinay.identity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Measurements a test made, written where the CI run publishes them. Never a secret. */
final class Evidence {

    private Evidence() {
    }

    static void write(String name, String text) {
        try {
            Path folder = Path.of("target", "identity-evidence");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(name + ".txt"), text);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
