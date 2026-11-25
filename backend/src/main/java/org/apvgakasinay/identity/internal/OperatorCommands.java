package org.apvgakasinay.identity.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.apvgakasinay.identity.DatabasePurpose;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    public OperatorCommands(StaffAdministration staff, AccountRepository accounts, DatabasePurpose purpose) {
        this.staff = staff;
        this.accounts = accounts;
        this.purpose = purpose;
    }

    /** Runs one command. Returns the process exit code. */
    @Transactional
    public int run(String command, BufferedReader in, PrintStream out) throws IOException {
        if (accounts.disposableMarkerPresent()) {
            out.println("Refused: this database is marked disposable (development or test). The operator command is for real databases only.");
            return 2;
        }
        // From now on this database can never receive fictional data.
        accounts.markDatabaseReal();
        String operator = System.getProperty("user.name", "unknown");
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
                default -> {
                    out.println("Unknown command. Commands: create-super-admin, create-admin, issue-password-reset, recover-staff-access, "
                            + "disable-account, enable-account, set-can-disburse, list-staff");
                    return 2;
                }
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
