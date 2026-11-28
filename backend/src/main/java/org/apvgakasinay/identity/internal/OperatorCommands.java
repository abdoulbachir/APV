package org.apvgakasinay.identity.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.apvgakasinay.identity.DatabasePurpose;
import org.apvgakasinay.identity.OperatorCommandSet;
import org.springframework.stereotype.Component;

/**
 * The operator command (identity-and-security.md, 8.1): a mode of this application run on the
 * server, never a network endpoint.
 *
 *   java -jar apv-backend.jar operator create-super-admin
 *
 * It asks for what it needs on the terminal. Nothing secret is ever passed as an argument or
 * an environment variable, it never asks for or prints a password, and the one-time credential
 * it prints goes to the terminal only, never to the log.
 */
@Component
public class OperatorCommands {

    private final StaffAdministration staff;
    private final AccountRepository accounts;
    private final DatabasePurpose purpose;
    private final java.util.List<OperatorCommandSet> extensions;

    public OperatorCommands(StaffAdministration staff, AccountRepository accounts, DatabasePurpose purpose,
                            org.springframework.beans.factory.ObjectProvider<OperatorCommandSet> extensions) {
        this.staff = staff;
        this.accounts = accounts;
        this.purpose = purpose;
        this.extensions = extensions.orderedStream().toList();
    }

    private static final java.util.Set<String> COMMANDS = java.util.Set.of("create-super-admin", "create-admin", "issue-password-reset",
            "recover-staff-access", "disable-account", "enable-account", "set-can-disburse", "list-staff");

    /**
     * Runs one command. Returns the process exit code. Not one transaction itself: each staff
     * action is its own, so a refused action rolls back alone and its refusal stays recorded.
     */
    public int run(String command, BufferedReader in, PrintStream out) throws IOException {
        if (accounts.disposableMarkerPresent()) {
            out.println("Refused: this database is marked disposable (development or test). The operator command is for real databases only.");
            return 2;
        }
        // Commands another module contributes (payment methods) run under the same rules as the staff commands.
        OperatorCommandSet extension = extensions.stream().filter(set -> set.commands().contains(command)).findFirst().orElse(null);
        if (!COMMANDS.contains(command) && extension == null) {
            StringBuilder known = new StringBuilder("Unknown command. Commands: create-super-admin, create-admin, issue-password-reset, "
                    + "recover-staff-access, disable-account, enable-account, set-can-disburse, list-staff");
            extensions.forEach(set -> new java.util.TreeSet<>(set.commands()).forEach(name -> known.append(", ").append(name)));
            out.println(known);
            return 2;
        }
        // From now on this database can never receive fictional data.
        accounts.markDatabaseReal();
        String operator = System.getProperty("user.name", "unknown");
        if (extension != null) {
            return extension.run(command, operator, in, out);
        }
        try {
            switch (command) {
                case "create-super-admin" -> credential(out, "Activation", staff.createStaff(ask(in, out, "Email"), ask(in, out, "Display name"), "super_admin", operator), 60);
                case "create-admin" -> credential(out, "Activation", staff.createStaff(ask(in, out, "Email"), ask(in, out, "Display name"), "admin", operator), 60);
                case "issue-password-reset" -> credential(out, "Password reset",
                        staff.issuePasswordReset(ask(in, out, "Email"), operator, identityCheck(in, out)), 30);
                case "recover-staff-access" -> credential(out, "Staff recovery",
                        staff.recoverStaffAccess(ask(in, out, "Email"), operator, identityCheck(in, out)), 60);
                case "disable-account" -> {
                    staff.setEnabled(ask(in, out, "Email"), false, operator);
                    out.println("Disabled. Its sessions have ended.");
                }
                case "enable-account" -> {
                    staff.setEnabled(ask(in, out, "Email"), true, operator);
                    out.println("Enabled.");
                }
                case "set-can-disburse" -> {
                    String email = ask(in, out, "Email");
                    boolean value = ask(in, out, "Allow recording disbursements? (yes/no)").equalsIgnoreCase("yes");
                    staff.setCanDisburse(email, value, operator);
                    out.println("Done. The account's sessions have ended.");
                }
                case "list-staff" -> {
                    for (Account account : staff.listStaff()) {
                        out.printf("%s  %s  enabled=%s  activated=%s  second-factor=%s  can-disburse=%s%n", account.email(), account.role(),
                                account.enabled(), account.activated(), accounts.hasActiveTotp(account.id()), account.canDisburse());
                    }
                }
                default -> throw new IllegalStateException(command);
            }
            return 0;
        } catch (StaffAdministration.Refused refused) {
            out.println(refused.getMessage());
            return 1;
        }
    }

    public boolean disposableDatabase() {
        return purpose.disposable();
    }

    private static String ask(BufferedReader in, PrintStream out, String question) throws IOException {
        out.print(question + ": ");
        out.flush();
        String answer = in.readLine();
        if (answer == null || answer.isBlank()) {
            throw new StaffAdministration.Refused("nothing was entered for: " + question);
        }
        return answer.trim();
    }

    /** Recovery needs a check made through a channel the operator already had (8.4). */
    private static String identityCheck(BufferedReader in, PrintStream out) throws IOException {
        out.println("Confirm who is asking through a way of reaching them that you already had before this request.");
        out.println("Details the requester supplies do not count. If you cannot confirm, stop now (leave the next line empty).");
        return ask(in, out, "How did you confirm their identity (for the audit log)");
    }

    private static void credential(PrintStream out, String kind, String credential, int minutes) {
        out.println();
        out.println(kind + " credential (shown once, valid " + minutes + " minutes, works once):");
        out.println();
        out.println("    " + credential);
        out.println();
        out.println("Give it to the person through a channel you already trust with them. It only lets them choose a password.");
    }

    public static BufferedReader terminal() {
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }
}
