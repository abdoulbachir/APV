package org.apvgakasinay.support;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * A breached-password list for tests that start the application as production does. It meets
 * the real production requirements, with no shortcut: one hundred million entries in the real
 * format with a real manifest. The entries are synthetic (evenly spread values that are the
 * hash of nothing), plus the hashes of the best-known breached passwords and of one fictional
 * passphrase the tests use. No breach data is in the repository or downloaded.
 */
public final class TestBlocklist {

    /** In the list: long enough to pass every other rule, so only the list can refuse it. */
    public static final String BREACHED_PASSPHRASE = "breached-example-passphrase-2026";
    public static final long SYNTHETIC_ENTRIES = 100_000_000L;

    private static Path production;

    private TestBlocklist() {
    }

    /** The list of production size, generated once per build into target/. About 2 GB. */
    public static synchronized Path production() {
        if (production == null) {
            Path file = Path.of("target", "test-blocklist", "breached-production-size.bin").toAbsolutePath();
            long expected = (SYNTHETIC_ENTRIES + 5) * 20;
            try {
                if (!Files.isRegularFile(file) || Files.size(file) != expected || !Files.isRegularFile(manifestOf(file))) {
                    write(file, SYNTHETIC_ENTRIES, List.of("password", "123456", "qwerty", "iloveyou", BREACHED_PASSPHRASE), "apv-sha1-sorted-v1");
                }
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            production = file;
        }
        return production;
    }

    public static Path manifestOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".manifest");
    }

    public static byte[] sha1(String text) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Writes a list: {@code synthetic} evenly spread entries merged, in order, with the hashes
     * of the given passwords, and its manifest.
     */
    public static void write(Path file, long synthetic, List<String> passwords, String format) throws IOException {
        Files.createDirectories(file.getParent());
        List<byte[]> special = new ArrayList<>();
        for (String password : passwords) {
            special.add(sha1(password));
        }
        special.sort(Arrays::compareUnsigned);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        long step = Long.divideUnsigned(-1L, synthetic + 2);
        long entries = 0;
        int next = 0;
        byte[] record = new byte[20];
        ByteBuffer view = ByteBuffer.wrap(record);
        try (OutputStream out = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 20), digest)) {
            for (long i = 1; i <= synthetic; i++) {
                view.putLong(0, i * step);
                // The last twelve bytes stay zero: no real SHA-1 looks like this.
                while (next < special.size() && Arrays.compareUnsigned(special.get(next), record) < 0) {
                    out.write(special.get(next++));
                    entries++;
                }
                out.write(record);
                entries++;
            }
            while (next < special.size()) {
                out.write(special.get(next++));
                entries++;
            }
        }
        writeManifest(file, format, entries, HexFormat.of().formatHex(digest.digest()));
    }

    public static void writeManifest(Path file, String format, long entries, String sha256) throws IOException {
        Files.writeString(manifestOf(file), "format=" + format + "\nentries=" + entries + "\nsha256=" + sha256
                + "\nsource=synthetic test data, not a breach corpus\nbuilt=2026-11-25T00:00:00Z\n");
    }
}
