package org.apvgakasinay.identity.internal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The comprehensive list of breached passwords: a local file, searched on disk.
 *
 * Format "apv-sha1-sorted-v1": the SHA-1 of each breached password, 20 bytes each, in
 * ascending order with no duplicates and nothing else. Beside it, a small manifest
 * ({@code <file>.manifest}) says how many entries there are, the SHA-256 of the file and where
 * the data came from. A password is looked up by a binary search of about thirty small reads;
 * nothing is loaded into memory and nothing is ever sent anywhere.
 *
 * Outside the dev and test profiles the list is mandatory, and the application refuses to
 * start when it is missing, unreadable or unsuitable. Unsuitable means any of: no manifest or
 * a manifest of another format; a size that is not what the manifest says; fewer entries than
 * {@link #MIN_PRODUCTION_ENTRIES}; entries out of order; the best-known breached passwords
 * absent, which is what a file of something else looks like; or a SHA-256 that differs.
 */
@Component
public class BreachedPasswords implements AutoCloseable {

    static final String FORMAT = "apv-sha1-sorted-v1";
    static final int RECORD = 20;

    /**
     * Below this a list is not comprehensive. The public breach corpus this format is built
     * from holds several hundred million entries; a list of common passwords holds thousands
     * or a few million.
     */
    static final long MIN_PRODUCTION_ENTRIES = 100_000_000L;

    /** In every real breach corpus. Their absence means the file is not one, or not in this format. */
    static final List<String> ALWAYS_BREACHED = List.of("password", "123456", "qwerty", "iloveyou");

    private static final int ORDER_SAMPLES = 4096;

    private final FileChannel channel;
    private final long entries;
    private final String source;

    /** The web application: every check, including the SHA-256 of the whole file. */
    @Autowired
    public BreachedPasswords(IdentityProperties properties) {
        this(properties, true);
    }

    /**
     * The operator command: every check except the SHA-256 of the whole file, which takes
     * about a minute for a full list and would be paid on each command. The operator command
     * never evaluates a password; it still refuses to run without a suitable list.
     */
    static BreachedPasswords forOperator(IdentityProperties properties) {
        return new BreachedPasswords(properties, false);
    }

    private BreachedPasswords(IdentityProperties properties, boolean verifyDigest) {
        String configured = properties.blocklistPath();
        if (configured.isBlank()) {
            if (!properties.development()) {
                throw refused("APV_PASSWORD_BLOCKLIST_PATH is not set. A comprehensive local breached-password list is required outside the dev and test profiles.");
            }
            // Development and tests: the small bundled starter list alone.
            this.channel = null;
            this.entries = 0;
            this.source = "none";
            return;
        }
        Opened opened = open(Path.of(configured), properties.development() ? 1 : MIN_PRODUCTION_ENTRIES, !properties.development(), verifyDigest);
        this.channel = opened.channel();
        this.entries = opened.entries();
        this.source = opened.source();
    }

    private BreachedPasswords(Opened opened) {
        this.channel = opened.channel();
        this.entries = opened.entries();
        this.source = opened.source();
    }

    /** No comprehensive list: only for the dev and test profiles and for unit tests. */
    static BreachedPasswords none() {
        return new BreachedPasswords(new Opened(null, 0, "none"));
    }

    /** Opens and checks a list with explicit requirements. The production requirements are fixed in the constructor above. */
    static BreachedPasswords opened(Path file, long minimumEntries, boolean requireKnownPasswords, boolean verifyDigest) {
        return new BreachedPasswords(open(file, minimumEntries, requireKnownPasswords, verifyDigest));
    }

    private record Opened(FileChannel channel, long entries, String source) {
    }

    private static IllegalStateException refused(String why) {
        return new IllegalStateException("Breached-password list: " + why + " See docs/backend-password-blocklist.md.");
    }

    static Path manifestOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".manifest");
    }

    private static Opened open(Path file, long minimumEntries, boolean requireKnownPasswords, boolean verifyDigest) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw refused("the configured file does not exist or cannot be read.");
        }
        Properties manifest = new Properties();
        Path manifestFile = manifestOf(file);
        if (!Files.isRegularFile(manifestFile) || !Files.isReadable(manifestFile)) {
            throw refused("its manifest (" + manifestFile.getFileName() + ") does not exist or cannot be read.");
        }
        try (BufferedReader reader = Files.newBufferedReader(manifestFile, StandardCharsets.UTF_8)) {
            manifest.load(reader);
        } catch (IOException | IllegalArgumentException unreadable) {
            throw refused("its manifest cannot be read.");
        }
        if (!FORMAT.equals(manifest.getProperty("format"))) {
            throw refused("the manifest does not declare the format " + FORMAT + ".");
        }
        long declared;
        try {
            declared = Long.parseLong(manifest.getProperty("entries", "").trim());
        } catch (NumberFormatException invalid) {
            throw refused("the manifest does not say how many entries there are.");
        }
        String digest = manifest.getProperty("sha256", "").trim().toLowerCase();
        if (!digest.matches("[0-9a-f]{64}")) {
            throw refused("the manifest holds no SHA-256 of the file.");
        }
        FileChannel channel = null;
        try {
            long size = Files.size(file);
            if (declared < 1 || size != declared * RECORD) {
                throw refused("the file is not the size its manifest declares (" + declared + " entries of " + RECORD + " bytes).");
            }
            if (declared < minimumEntries) {
                throw refused("it holds " + declared + " entries; at least " + minimumEntries + " are required for a comprehensive list.");
            }
            channel = FileChannel.open(file, StandardOpenOption.READ);
            checkOrder(channel, declared);
            if (requireKnownPasswords) {
                for (String known : ALWAYS_BREACHED) {
                    if (!find(channel, declared, sha1(known))) {
                        throw refused("it does not contain the best-known breached passwords, so it is not a breach corpus in this format.");
                    }
                }
            }
            if (verifyDigest && !digest.equals(sha256(file))) {
                throw refused("its SHA-256 differs from the manifest: the file is damaged or was changed.");
            }
            return new Opened(channel, declared, manifest.getProperty("source", "not stated"));
        } catch (IOException unreadable) {
            closeQuietly(channel);
            throw refused("the file cannot be read.");
        } catch (RuntimeException failure) {
            closeQuietly(channel);
            throw failure;
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // Nothing to do: the file is being refused anyway.
        }
    }

    /** Neighbouring entries at evenly spread places, and the two ends, must be strictly ascending. */
    private static void checkOrder(FileChannel channel, long entries) throws IOException {
        if (entries < 2) {
            return;
        }
        long step = Math.max(1, (entries - 1) / ORDER_SAMPLES);
        for (long index = 0; index < entries - 1; index += step) {
            if (Arrays.compareUnsigned(read(channel, index), read(channel, index + 1)) >= 0) {
                throw refused("its entries are not in ascending order.");
            }
        }
        if (Arrays.compareUnsigned(read(channel, entries - 2), read(channel, entries - 1)) >= 0
                || Arrays.compareUnsigned(read(channel, 0), read(channel, entries - 1)) >= 0) {
            throw refused("its entries are not in ascending order.");
        }
    }

    private static byte[] read(FileChannel channel, long index) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(RECORD);
        long position = index * RECORD;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new IOException("unexpected end of the breached-password list");
            }
        }
        return buffer.array();
    }

    private static boolean find(FileChannel channel, long entries, byte[] hash) throws IOException {
        long low = 0;
        long high = entries - 1;
        while (low <= high) {
            long middle = (low + high) >>> 1;
            int order = Arrays.compareUnsigned(read(channel, middle), hash);
            if (order == 0) {
                return true;
            }
            if (order < 0) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return false;
    }

    static byte[] sha1(String text) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                byte[] buffer = new byte[1 << 20];
                while (in.read(buffer) >= 0) {
                    // Reading is what feeds the digest.
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** True when a comprehensive list is in use. */
    public boolean present() {
        return channel != null;
    }

    public long entries() {
        return entries;
    }

    public String source() {
        return source;
    }

    /**
     * True when the password, in the form it would be stored under, is in the list. If the
     * list cannot be read at that moment the answer is an error, never "not breached".
     */
    public boolean contains(String normalizedPassword) {
        if (channel == null) {
            return false;
        }
        try {
            return find(channel, entries, sha1(normalizedPassword));
        } catch (IOException unreadable) {
            throw new IllegalStateException("the breached-password list could not be read");
        }
    }

    @Override
    @jakarta.annotation.PreDestroy
    public void close() {
        closeQuietly(channel);
    }

    // ------------------------------------------------------------------ building a list

    /**
     * Builds a list from text: one SHA-1 per line as 40 hexadecimal characters, optionally
     * followed by a colon and anything (the public corpus appends a count), already in
     * ascending order. Writes the binary file and its manifest. Nothing is kept in memory.
     *
     * @return the number of entries written
     */
    public static long build(BufferedReader lines, Path output, String source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        long entries = 0;
        long lineNumber = 0;
        byte[] previous = null;
        HexFormat hex = HexFormat.of();
        try (OutputStream out = new DigestOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(output), 1 << 20), digest)) {
            String line;
            while ((line = lines.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (line.length() < 40 || (line.length() > 40 && line.charAt(40) != ':')) {
                    throw new IllegalArgumentException("line " + lineNumber + " is not a SHA-1 in hexadecimal, optionally followed by a colon");
                }
                byte[] hash;
                try {
                    hash = hex.parseHex(line, 0, 40);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("line " + lineNumber + " is not a SHA-1 in hexadecimal");
                }
                if (previous != null && Arrays.compareUnsigned(previous, hash) >= 0) {
                    throw new IllegalArgumentException("line " + lineNumber + " is not greater than the line before it: the input must be sorted "
                            + "by hash with no duplicates (for example with: LC_ALL=C sort -u)");
                }
                out.write(hash);
                previous = hash;
                entries++;
            }
        }
        if (entries == 0) {
            Files.deleteIfExists(output);
            throw new IllegalArgumentException("the input holds no entries");
        }
        try (BufferedWriter manifest = Files.newBufferedWriter(manifestOf(output), StandardCharsets.UTF_8)) {
            manifest.write("format=" + FORMAT + "\n");
            manifest.write("entries=" + entries + "\n");
            manifest.write("sha256=" + hex.formatHex(digest.digest()) + "\n");
            manifest.write("source=" + source.replace("\\", "/").replace("\n", " ") + "\n");
            manifest.write("built=" + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + "\n");
        }
        return entries;
    }
}
