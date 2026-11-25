package org.apvgakasinay.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.SpringVersion;
import org.springframework.security.core.SpringSecurityCoreVersion;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;

/** Probe 4: Argon2id through Spring Security's encoder on the pinned Bouncy Castle. No database. */
class Argon2ProbeTest {

    /** The accepted floor: OWASP's minimum of 19 MiB, 2 iterations, 1 lane; 16-byte salt, 32-byte hash. */
    private static final Argon2PasswordEncoder ENCODER = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    private static String implementationVersion(Class<?> type) {
        String version = type.getPackage().getImplementationVersion();
        if (version != null) {
            return version;
        }
        // Fall back to the jar file name when the manifest carries no version.
        String location = type.getProtectionDomain().getCodeSource().getLocation().getPath();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Test
    void resolvedVersionsAreThePinnedOnes() throws IOException {
        String bouncyCastle = new BouncyCastleProvider().getVersionStr();
        String bouncyCastleJar = implementationVersion(BouncyCastleProvider.class);
        List<String> lines = List.of(
                "Java runtime: " + Runtime.version(),
                "Spring Boot: " + SpringBootVersion.getVersion(),
                "Spring Framework: " + SpringVersion.getVersion(),
                "Spring Security: " + SpringSecurityCoreVersion.getVersion(),
                "Spring Session: " + implementationVersion(Session.class),
                "Bouncy Castle provider: " + bouncyCastle + " (" + bouncyCastleJar + ")");
        lines.forEach(System.out::println);
        write("versions.txt", lines);

        assertThat(Runtime.version().feature()).isEqualTo(21);
        assertThat(SpringBootVersion.getVersion()).isEqualTo("4.1.3");
        assertThat(SpringSecurityCoreVersion.getVersion()).isEqualTo("7.1.3");
        assertThat(implementationVersion(Session.class)).contains("4.1.2");
        assertThat(bouncyCastle + " " + bouncyCastleJar).contains("1.85");
    }

    @Test
    void hashesAreArgon2idWithTheAcceptedParametersAndAUniqueSalt() {
        String password = "correct horse battery staple 42";
        String first = ENCODER.encode(password);
        String second = ENCODER.encode(password);
        assertThat(first).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(second).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(first).as("a new salt for every hash").isNotEqualTo(second);
        // $argon2id$v=19$m=..,t=..,p=..$<salt>$<hash>: 16 and 32 bytes in unpadded Base64.
        String[] parts = first.split("\\$");
        assertThat(parts[4]).hasSize(22);
        assertThat(parts[5]).hasSize(43);
        assertThat(ENCODER.matches(password, first)).isTrue();
        assertThat(ENCODER.matches(password, second)).isTrue();
        assertThat(ENCODER.matches(password + " ", first)).as("nothing is trimmed").isFalse();
        assertThat(ENCODER.matches("Correct horse battery staple 42", first)).isFalse();
    }

    @Test
    void longUnicodePasswordsAreNeverCut() {
        // 128 code points: astral emoji, accents, CJK, spaces. 2 UTF-16 units per emoji.
        String body = "🌍".repeat(40) + " é ü ñ ".repeat(8) + "日本語".repeat(10);
        String password = body + "x".repeat(128 - body.codePointCount(0, body.length()));
        assertThat(password.codePointCount(0, password.length())).isEqualTo(128);
        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(200);

        String hash = ENCODER.encode(password);
        assertThat(ENCODER.matches(password, hash)).isTrue();
        // Differs only in the very last character: bcrypt would cut at 72 bytes and accept this.
        String almost = password.substring(0, password.length() - 1) + "y";
        assertThat(ENCODER.matches(almost, hash)).as("the last character counts").isFalse();
        // Differs only in the first character.
        assertThat(ENCODER.matches("🌎" + password.substring(2), hash)).isFalse();
        // 129 code points also hash without error; the 128 limit is enforced by the application, not hidden by the encoder.
        assertThat(ENCODER.matches(password + "z", ENCODER.encode(password + "z"))).isTrue();
        assertThat(ENCODER.matches(password + "z", hash)).isFalse();
    }

    @Test
    void nfcNormalizationBeforeHashingMakesComposedAndDecomposedFormsEqualAndKeepsCompatibilityCharactersDistinct() {
        String composed = "café au lait, s'il vous plaît";
        String decomposed = "café au lait, s'il vous plaît";
        assertThat(composed).isNotEqualTo(decomposed);
        String hash = ENCODER.encode(Normalizer.normalize(composed, Normalizer.Form.NFC));
        assertThat(ENCODER.matches(Normalizer.normalize(decomposed, Normalizer.Form.NFC), hash)).isTrue();
        // Without normalization they are different passwords: the encoder does not normalize by itself.
        assertThat(ENCODER.matches(decomposed, hash)).isFalse();
        // NFC keeps the "fi" ligature distinct from "fi"; NFKC would have merged them.
        String ligature = "ofﬁce passphrase 2027";
        String plain = "office passphrase 2027";
        assertThat(Normalizer.normalize(ligature, Normalizer.Form.NFC)).isNotEqualTo(plain);
        assertThat(Normalizer.normalize(ligature, Normalizer.Form.NFKC)).isEqualTo(plain);
        assertThat(ENCODER.matches(Normalizer.normalize(ligature, Normalizer.Form.NFC), ENCODER.encode(plain))).isFalse();
    }

    @Test
    void storedHashesCanBeUpgradedWhenTheWorkFactorIsRaised() {
        Argon2PasswordEncoder stronger = new Argon2PasswordEncoder(16, 32, 1, 47104, 2);
        String old = ENCODER.encode("correct horse battery staple 42");
        // The stronger encoder still verifies an old hash, because the parameters travel in the hash ...
        assertThat(stronger.matches("correct horse battery staple 42", old)).isTrue();
        // ... and reports that it should be re-encoded.
        assertThat(stronger.upgradeEncoding(old)).isTrue();
        assertThat(stronger.upgradeEncoding(stronger.encode("correct horse battery staple 42"))).isFalse();

        // With Spring's {id} prefix the algorithm travels with the hash as well.
        PasswordEncoder delegating = new DelegatingPasswordEncoder("argon2id-apv1", Map.of("argon2id-apv1", ENCODER));
        String stored = delegating.encode("correct horse battery staple 42");
        assertThat(stored).startsWith("{argon2id-apv1}$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(delegating.matches("correct horse battery staple 42", stored)).isTrue();
    }

    /** Median time of one verification. Verification is what a sign-in costs. */
    private static double medianMillis(Argon2PasswordEncoder encoder, int runs) {
        String password = "correct horse battery staple 42";
        String hash = encoder.encode(password);
        encoder.matches(password, hash);
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            encoder.matches(password, hash);
            samples.add((System.nanoTime() - start) / 1_000_000.0);
        }
        Collections.sort(samples);
        return samples.get(samples.size() / 2);
    }

    @Test
    void measureVerificationTime() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Environment: " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + ", " + Runtime.getRuntime().availableProcessors() + " processors, max heap "
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MiB, Java " + Runtime.version());
        lines.add("Median of 9 single-threaded verifications after one warm-up, in milliseconds:");
        int[][] candidates = {{19456, 2}, {47104, 1}, {47104, 2}, {65536, 3}};
        double floor = 0;
        for (int[] candidate : candidates) {
            double millis = medianMillis(new Argon2PasswordEncoder(16, 32, 1, candidate[0], candidate[1]), 9);
            if (candidate[0] == 19456) {
                floor = millis;
            }
            lines.add(String.format("  m=%d KiB (%d MiB), t=%d, p=1: %.1f ms", candidate[0], candidate[0] / 1024, candidate[1], millis));
        }
        lines.forEach(System.out::println);
        write("argon2-timing.txt", lines);
        // A sanity bound only: the accepted floor must be usable for an interactive sign-in here.
        assertThat(floor).isBetween(1.0, 5000.0);
    }

    private static void write(String name, List<String> lines) throws IOException {
        Path directory = Path.of("target", "probe-results");
        Files.createDirectories(directory);
        Files.write(directory.resolve(name), lines);
    }
}
