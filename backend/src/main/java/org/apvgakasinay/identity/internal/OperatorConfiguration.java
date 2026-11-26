package org.apvgakasinay.identity.internal;

import org.apvgakasinay.audit.AuditService;
import org.apvgakasinay.identity.DatabasePurpose;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * The beans of the operator command, and nothing else. It is started on its own, without a
 * web server and without any controller, filter or session machinery: the command is not
 * reachable over the network because nothing that listens is ever created.
 *
 * Deliberately not a stereotype-annotated class, so the web application's component scan
 * ignores it.
 */
@EnableAutoConfiguration
@Import({IdentityProperties.class, OperatorConfiguration.RequiredConfiguration.class, IdentityClock.class, AccountRepository.class, SessionStore.class, Audit.class, StaffAdministration.class,
        OperatorCommands.class, DatabasePurpose.class})
@ComponentScan(basePackageClasses = AuditService.class)
public class OperatorConfiguration {

    /**
     * The operator command runs under the same configuration rules as the server. It refuses to
     * run without the keys and without a suitable breached-password list, although it evaluates
     * no password itself: a deployment whose server could not start must not be administered
     * as if it could.
     */
    static class RequiredConfiguration {

        @org.springframework.context.annotation.Bean
        BreachedPasswords breachedPasswords(IdentityProperties properties) {
            return BreachedPasswords.forOperator(properties);
        }
    }
}
