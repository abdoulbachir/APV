package org.apvgakasinay.identity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apvgakasinay.identity.internal.BreachedPasswords;
import org.apvgakasinay.identity.internal.BreachedPasswordsVerifier;

/**
 * Builds and verifies the local breached-password list (docs/backend-password-blocklist.md).
 * Runs without the application: no database, no network, no web server.
 *
 * <pre>
 * blocklist build &lt;sorted-hashes.txt | -&gt; &lt;output file&gt; &lt;where the data came from&gt;
 * blocklist verify &lt;file&gt;
 * </pre>
 */
public final class BlocklistTool {

    private BlocklistTool() {
    }

    /** Returns the process exit code. */
    public static int run(String[] args, PrintStream out) {
        try {
            if (args.length == 4 && args[0].equals("build")) {
                Path output = Path.of(args[2]);
                try (BufferedReader lines = args[1].equals("-")
                        ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII), 1 << 20)
                        : Files.newBufferedReader(Path.of(args[1]), StandardCharsets.US_ASCII)) {
                    long entries = BreachedPasswords.build(lines, output, args[3]);
                    out.println("Wrote " + entries + " entries (" + entries * 20 + " bytes) to " + output + " and its manifest.");
                }
                out.println(BreachedPasswordsVerifier.verify(output));
                return 0;
            }
            if (args.length == 2 && args[0].equals("verify")) {
                out.println(BreachedPasswordsVerifier.verify(Path.of(args[1])));
                return 0;
            }
            out.println("Usage: blocklist build <sorted-hashes.txt | -> <output file> <where the data came from>");
            out.println("       blocklist verify <file>");
            return 2;
        } catch (IllegalStateException | IllegalArgumentException | java.io.IOException refused) {
            out.println("Refused: " + refused.getMessage());
            return 1;
        }
    }
}
