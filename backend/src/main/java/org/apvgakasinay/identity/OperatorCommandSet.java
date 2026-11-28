package org.apvgakasinay.identity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;

/**
 * Commands another module adds to the operator command (identity-and-security.md, 8.1). They
 * run under the same rules as the staff commands: on the server, on the terminal, never over
 * the network, refused on a database marked disposable, and each one recorded with the
 * operating-system user who ran it.
 */
public interface OperatorCommandSet {

    /** The names of the commands, as typed after {@code operator}. */
    Set<String> commands();

    /**
     * Runs one of {@link #commands()}. It asks for what it needs on {@code in} and answers on
     * {@code out}; nothing is passed as an argument.
     *
     * @param operator the operating-system user running the command, for the audit log
     * @return the process exit code: 0 when done, 1 when refused
     */
    int run(String command, String operator, BufferedReader in, PrintStream out) throws IOException;
}
