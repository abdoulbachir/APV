package org.apvgakasinay.donations.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Where screenshots are kept (contract/donation-lifecycle.md, 4.3): a private directory outside
 * anything the web server or the public picture route can reach, named by APV_EVIDENCE_ROOT.
 * A file is stored under a random key that is derived from nothing: not the gift, not the donor,
 * not the file. No file-system path ever leaves this class; callers hold keys only.
 */
@Component
class EvidenceStore {

    private final Path root;

    EvidenceStore(DonationSettings settings) {
        this.root = settings.evidenceRoot();
        if (root == null) {
            org.slf4j.LoggerFactory.getLogger(EvidenceStore.class).warn(
                    "APV_EVIDENCE_ROOT is not set: gifts can be reported, but no screenshot can be attached until it names a private directory");
        }
    }

    boolean available() {
        return root != null;
    }

    /** A new random key: two levels, 160 random bits, and the extension of the format. */
    static String newKey(String format) {
        String name = HexFormat.of().formatHex(Codes.random(20));
        return name.substring(0, 2) + "/" + name + (format.equals("jpeg") ? ".jpg" : ".png");
    }

    /** Writes the file completely, then gives it its name: a reader never sees part of one. */
    void write(String key, byte[] bytes) {
        Path target = path(key);
        try {
            createPrivately(target.getParent());
            Path partial = Files.createTempFile(target.getParent(), "incoming-", ".part");
            try {
                Files.write(partial, bytes);
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(partial);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("a screenshot could not be stored", failure);
        }
    }

    /** The stored file, or empty when it is not there. */
    Optional<byte[]> read(String key) {
        try {
            return Optional.of(Files.readAllBytes(path(key)));
        } catch (NoSuchFileException gone) {
            return Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException("a screenshot could not be read", failure);
        }
    }

    /** Deletes the stored file. Deleting one that is already gone is not an error. */
    void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException failure) {
            throw new UncheckedIOException("a screenshot could not be deleted", failure);
        }
    }

    private Path path(String key) {
        if (root == null) {
            throw new IllegalStateException("no evidence directory is configured");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("a storage key cannot leave the evidence directory");
        }
        return resolved;
    }

    private static void createPrivately(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        try {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createDirectories(directory);
        }
    }
}
