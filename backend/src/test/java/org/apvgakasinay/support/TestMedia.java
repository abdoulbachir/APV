package org.apvgakasinay.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * A throwaway picture folder for tests: a copy of contract/fixtures/images, plus a symbolic
 * link that points at a file outside the folder, to prove such a link is never followed.
 */
public final class TestMedia {

    public static final String ESCAPING_LINK = "images/escape.webp";

    private static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("apv-media-root");
            Path images = Files.createDirectory(ROOT.resolve("images"));
            try (Stream<Path> files = Files.list(Repo.contract("fixtures/images"))) {
                for (Path file : files.toList()) {
                    Files.copy(file, images.resolve(file.getFileName()));
                }
            }
            Path outside = Files.createTempDirectory("apv-outside").resolve("secret.webp");
            Files.writeString(outside, "this file is outside the picture folder");
            Files.createSymbolicLink(ROOT.resolve(ESCAPING_LINK), outside);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private TestMedia() {
    }

    public static Path root() {
        return ROOT;
    }
}
