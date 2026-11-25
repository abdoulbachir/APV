package org.apvgakasinay.support;

import java.nio.file.Path;

/** Locations of the shared contract files, relative to the repository root. */
public final class Repo {

    private Repo() {
    }

    public static Path root() {
        return Path.of(System.getProperty("apv.repository.root", "..")).toAbsolutePath().normalize();
    }

    public static Path contract(String relative) {
        return root().resolve("contract").resolve(relative);
    }
}
