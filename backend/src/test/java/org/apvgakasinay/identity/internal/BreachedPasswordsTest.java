package org.apvgakasinay.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.apvgakasinay.identity.BlocklistTool;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.support.TestBlocklist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * Scenario 2.9: the local breached-password list. These tests need no database and no network.
 * The requirements of production are exercised through the same constructor production uses;
 * the one thing a small file cannot show, a list that is accepted in production, is shown by
 * the integration tests with a list of real size.
 */
class BreachedPasswordsTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final List<String> KNOWN = List.of("password", "123456", "qwerty", "iloveyou");

    @TempDir
    Path folder;

    private static MockEnvironment production(String path) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("apv.identity.mfa-encryption-key", KEY)
                .withProperty("apv.identity.hash-key", KEY);
        if (path != null) {
            environment.withProperty("apv.identity.password-blocklist-path", path);
        }
        return environment;
    }

    private static MockEnvironment development(String path) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        if (path != null) {
            environment.withProperty("apv.identity.password-blocklist-path", path);
        }
        return environment;
    }

    private Path list(String name, long synthetic, List<String> passwords) throws Exception {
        Path file = folder.resolve(name);
        TestBlocklist.write(file, synthetic, passwords, "apv-sha1-sorted-v1");
        return file;
    }

    private static String manifestDigest(Path file) throws Exception {
        java.util.Properties manifest = new java.util.Properties();
        try (java.io.Reader reader = Files.newBufferedReader(TestBlocklist.manifestOf(file))) {
            manifest.load(reader);
        }
        return manifest.getProperty("sha256");
    }

    private static void refusedInProduction(Path file, String because) {
        // The server and the operator command refuse alike.
        assertThatThrownBy(() -> new BreachedPasswords(new IdentityProperties(production(file == null ? null : file.toString()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(because);
        assertThatThrownBy(() -> BreachedPasswords.forOperator(new IdentityProperties(production(file == null ? null : file.toString()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(because);
    }

    @Test
    void a2_9_productionRefusesToStartWithoutAList() {
        refusedInProduction(null, "APV_PASSWORD_BLOCKLIST_PATH is not set");
        assertThatThrownBy(() -> new BreachedPasswords(new IdentityProperties(production("   "))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not set");
    }

    @Test
    void a2_9_productionRefusesAMissingOrUnreadableList() throws Exception {
        refusedInProduction(folder.resolve("nothing-here.bin"), "does not exist or cannot be read");
        refusedInProduction(folder, "does not exist or cannot be read");

        Path noManifest = list("no-manifest.bin", 1000, KNOWN);
        Files.delete(TestBlocklist.manifestOf(noManifest));
        refusedInProduction(noManifest, "manifest");

        Path unreadable = list("unreadable.bin", 1000, KNOWN);
        if (unreadable.toFile().setReadable(false) && !Files.isReadable(unreadable)) {
            refusedInProduction(unreadable, "does not exist or cannot be read");
        }
    }

    @Test
    void a2_9_productionRefusesAnUnsuitableList() throws Exception {
        // Well-formed and holding the known passwords, but a thousand entries is not a comprehensive list.
        refusedInProduction(list("small.bin", 1000, KNOWN), "at least 100000000 are required");
        // The bundled starter list is not in this format at all.
        Path starter = folder.resolve("starter.txt");
        Files.writeString(starter, "123456789012345\npasswordpassword\n");
        refusedInProduction(starter, "manifest");

        Path otherFormat = list("other-format.bin", 1000, KNOWN);
        TestBlocklist.writeManifest(otherFormat, "plain-text-v0", 1004, "0".repeat(64));
        refusedInProduction(otherFormat, "does not declare the format");

        Path wrongSize = list("wrong-size.bin", 1000, KNOWN);
        Files.write(wrongSize, new byte[7], StandardOpenOption.APPEND);
        refusedInProduction(wrongSize, "not the size its manifest declares");

        Path noDigest = list("no-digest.bin", 1000, KNOWN);
        Files.writeString(TestBlocklist.manifestOf(noDigest), "format=apv-sha1-sorted-v1\nentries=1004\n");
        refusedInProduction(noDigest, "no SHA-256");
    }

    /** The checks that come after the size requirement, with that requirement set aside by calling them directly. */
    @Test
    void a2_9_aListOfTheRightSizeIsStillRefusedWhenItsContentIsWrong() throws Exception {
        Path good = list("good.bin", 5000, KNOWN);
        try (BreachedPasswords list = BreachedPasswords.opened(good, 5000, true, true)) {
            assertThat(list.entries()).isEqualTo(5004);
        }

        Path notACorpus = list("not-a-corpus.bin", 5000, List.of());
        assertThatThrownBy(() -> BreachedPasswords.opened(notACorpus, 5000, true, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("best-known breached passwords");

        // One byte changed in the middle: the order sample may miss it, the SHA-256 does not.
        Path damaged = list("damaged.bin", 5000, KNOWN);
        byte[] bytes = Files.readAllBytes(damaged);
        bytes[bytes.length / 2 + 19] ^= 1;
        Files.write(damaged, bytes);
        assertThatThrownBy(() -> BreachedPasswords.opened(damaged, 5000, true, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHA-256 differs");

        // Entries shuffled: two halves swapped.
        Path unordered = list("unordered.bin", 5000, KNOWN);
        byte[] ordered = Files.readAllBytes(unordered);
        byte[] swapped = new byte[ordered.length];
        int half = (ordered.length / 40) * 20;
        System.arraycopy(ordered, half, swapped, 0, ordered.length - half);
        System.arraycopy(ordered, 0, swapped, ordered.length - half, half);
        Files.write(unordered, swapped);
        assertThatThrownBy(() -> BreachedPasswords.opened(unordered, 5000, true, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not in ascending order");
    }

    @Test
    void a2_9_developmentAndTestsMayRunOnTheStarterListAlone() throws Exception {
        try (BreachedPasswords none = new BreachedPasswords(new IdentityProperties(development(null)))) {
            assertThat(none.present()).isFalse();
            assertThat(none.contains("password")).isFalse();
            PasswordPolicy policy = new PasswordPolicy(none);
            // The starter list still refuses the obvious, and nothing else stands in for the comprehensive list.
            assertThatThrownBy(() -> policy.accept("password", "passwordpassword", List.of())).isInstanceOf(ApiException.class);
            assertThat(policy.accept("password", TestBlocklist.BREACHED_PASSPHRASE, List.of())).isEqualTo(TestBlocklist.BREACHED_PASSPHRASE);
        }
        // A small list may be used in development, but a broken one is refused there too.
        Path small = list("dev.bin", 100, List.of(TestBlocklist.BREACHED_PASSPHRASE));
        try (BreachedPasswords dev = new BreachedPasswords(new IdentityProperties(development(small.toString())))) {
            assertThat(dev.present()).isTrue();
            assertThat(dev.contains(TestBlocklist.BREACHED_PASSPHRASE)).isTrue();
        }
        Files.write(small, new byte[20], StandardOpenOption.APPEND);
        assertThatThrownBy(() -> new BreachedPasswords(new IdentityProperties(development(small.toString())))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a2_9_aListedPasswordIsRefusedAndAnUnlistedOneIsNot() throws Exception {
        List<String> listed = new ArrayList<>(KNOWN);
        listed.add(TestBlocklist.BREACHED_PASSPHRASE);
        listed.add("café au lait s'il vous plaît");
        Path file = list("lookup.bin", 20_000, listed);
        try (BreachedPasswords list = BreachedPasswords.opened(file, 20_000, true, true)) {
            PasswordPolicy policy = new PasswordPolicy(list);
            assertThatThrownBy(() -> policy.accept("newPassword", TestBlocklist.BREACHED_PASSPHRASE, List.of()))
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.fieldCode()).isEqualTo("compromised"));
            // Looked up in the form that would be stored: the decomposed spelling of a listed password is refused too.
            assertThatThrownBy(() -> policy.accept("newPassword", "café au lait s'il vous plaît", List.of())).isInstanceOf(ApiException.class);
            // Exact match only: one character more is another password.
            assertThat(policy.accept("newPassword", TestBlocklist.BREACHED_PASSPHRASE + "!", List.of())).endsWith("!");
            assertThat(policy.accept("newPassword", "a long harmless passphrase 2026", List.of())).isNotBlank();
            // First entry, last entry, and values that are in no list.
            byte[] all = Files.readAllBytes(file);
            assertThat(list.contains("password")).isTrue();
            assertThat(list.contains("definitely not in the list")).isFalse();
            assertThat(all.length).isEqualTo((int) list.entries() * 20);
        }
    }

    @Test
    void a2_9_theBuildToolWritesAListThatTheVerifierThenJudges() throws Exception {
        List<String> hashes = new ArrayList<>();
        for (String password : List.of("password", "123456", "qwerty", "iloveyou", TestBlocklist.BREACHED_PASSPHRASE)) {
            hashes.add(HexFormat.of().withUpperCase().formatHex(TestBlocklist.sha1(password)));
        }
        hashes.sort(String::compareTo);
        // The form the public corpus is distributed in: upper-case hash, a colon, a count.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < hashes.size(); i++) {
            text.append(hashes.get(i)).append(':').append(1000 - i).append("\r\n".substring(i % 2 == 0 ? 1 : 0));
        }
        Path output = folder.resolve("built.bin");
        long entries = BreachedPasswords.build(new BufferedReader(new StringReader(text.toString().replace("\r", ""))), output, "unit test");
        assertThat(entries).isEqualTo(5);
        assertThat(Files.size(output)).isEqualTo(100);
        try (BreachedPasswords list = BreachedPasswords.opened(output, 5, true, true)) {
            assertThat(list.contains(TestBlocklist.BREACHED_PASSPHRASE)).isTrue();
            assertThat(list.source()).isEqualTo("unit test");
        }
        // Unsorted, duplicated or malformed input is refused, not silently accepted.
        String unsorted = hashes.get(1) + "\n" + hashes.get(0) + "\n";
        assertThatThrownBy(() -> BreachedPasswords.build(new BufferedReader(new StringReader(unsorted)), folder.resolve("x.bin"), "t"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sorted");
        String duplicated = hashes.get(0) + "\n" + hashes.get(0) + "\n";
        assertThatThrownBy(() -> BreachedPasswords.build(new BufferedReader(new StringReader(duplicated)), folder.resolve("y.bin"), "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreachedPasswords.build(new BufferedReader(new StringReader("password\n")), folder.resolve("z.bin"), "t"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 1");

        // The command line: verification applies the production requirements, so five entries are refused, with the reason.
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        int exit = BlocklistTool.run(new String[] {"verify", output.toString()}, new PrintStream(printed, true, StandardCharsets.UTF_8));
        assertThat(exit).isEqualTo(1);
        assertThat(printed.toString(StandardCharsets.UTF_8)).contains("Refused").contains("at least 100000000");
        assertThat(BlocklistTool.run(new String[] {"nonsense"}, new PrintStream(printed, true, StandardCharsets.UTF_8))).isEqualTo(2);
    }

    /**
     * The manifest sits beside the file, so replacing both passes every check above. With the
     * digest recorded at build time given as APV_PASSWORD_BLOCKLIST_SHA256, it does not.
     */
    @Test
    void a2_9_aRecordedDigestCatchesAListReplacedTogetherWithItsManifest() throws Exception {
        Path installed = list("installed.bin", 5000, KNOWN);
        String recorded = manifestDigest(installed);
        assertThat(recorded).matches("[0-9a-f]{64}");

        // The recorded digest matches: accepted, by the server and by the operator command, however it is written.
        BreachedPasswords.opened(installed, 5000, true, true, recorded).close();
        BreachedPasswords.opened(installed, 5000, true, false, "  " + recorded.toUpperCase() + " ").close();
        // Not set: as before.
        BreachedPasswords.opened(installed, 5000, true, true, "").close();
        BreachedPasswords.opened(installed, 5000, true, true, null).close();

        // Another list, valid in itself, put in its place together with its own manifest.
        Path other = list("other.bin", 5001, KNOWN);
        Files.copy(other, installed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(TestBlocklist.manifestOf(other), TestBlocklist.manifestOf(installed), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // Without the setting nothing can notice.
        BreachedPasswords.opened(installed, 5000, true, true).close();
        // With it, the server and the operator command both refuse.
        assertThatThrownBy(() -> BreachedPasswords.opened(installed, 5000, true, true, recorded))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APV_PASSWORD_BLOCKLIST_SHA256");
        assertThatThrownBy(() -> BreachedPasswords.opened(installed, 5000, true, false, recorded))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APV_PASSWORD_BLOCKLIST_SHA256");
        // A setting that is not a digest is refused, not ignored.
        assertThatThrownBy(() -> BreachedPasswords.opened(other, 5000, true, true, "sha256:abc"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("is not a SHA-256");

        // The same through the configuration, as the application reads it.
        MockEnvironment pinned = development(installed.toString()).withProperty("apv.identity.password-blocklist-sha256", recorded);
        assertThatThrownBy(() -> new BreachedPasswords(new IdentityProperties(pinned)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APV_PASSWORD_BLOCKLIST_SHA256");
        assertThatThrownBy(() -> BreachedPasswords.forOperator(new IdentityProperties(pinned)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APV_PASSWORD_BLOCKLIST_SHA256");
        MockEnvironment current = development(installed.toString())
                .withProperty("apv.identity.password-blocklist-sha256", manifestDigest(installed));
        new BreachedPasswords(new IdentityProperties(current)).close();
    }
}
