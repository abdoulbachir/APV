package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Regression tests for R1 of the independent re-check of 1d5a766 (2026-11-25): a sign-out that
 * read its session before a password change replaced the identifier answered 204 and left the
 * session signed in under the new identifier.
 *
 * The order that matters is made on purpose, not hoped for. A filter that exists only in this
 * test holds a sign-out request at the point where it has read its session and has checked
 * nothing yet. The session change then runs from start to finish as a real request, and the
 * sign-out is let go. After a sign-out answered 204, nobody is signed in.
 */
class SignOutDuringSessionChangeIT extends IdentityIntegration {

    @TestConfiguration
    static class Holding {

        @Bean
        HoldSignOut holdSignOut() {
            return new HoldSignOut();
        }
    }

    /** Sits between the session store's filter and the security filters. Does nothing unless a test has armed it. */
    static final class HoldSignOut extends OncePerRequestFilter implements Ordered {

        volatile CountDownLatch reached;
        volatile CountDownLatch release;
        volatile boolean sawSession;

        @Override
        public int getOrder() {
            // The session store's filter is first of all; the security filters are at -100.
            return -101;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CountDownLatch gate = release;
            if (gate != null && request.getRequestURI().equals("/api/auth/logout")) {
                // What the request's own filters do a moment later: read the stored session, once.
                sawSession = request.getSession(false) != null;
                reached.countDown();
                try {
                    if (!gate.await(60, TimeUnit.SECONDS)) {
                        throw new ServletException("the held sign-out was never released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ServletException(interrupted);
                }
            }
            chain.doFilter(request, response);
        }
    }

    @Autowired
    HoldSignOut hold;

    /**
     * Sends a sign-out with the cookie and token the browser holds now, holds it once it has
     * read its session, runs the change, and lets the sign-out finish.
     */
    private Browser.Answer signOutHeldDuring(Browser browser, Runnable change) throws Exception {
        Browser otherTab = sameSession(browser);
        hold.sawSession = false;
        hold.reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hold.release = release;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Browser.Answer> signOut = pool.submit(() -> otherTab.postEmpty("/api/auth/logout"));
            try {
                assertThat(hold.reached.await(60, TimeUnit.SECONDS)).as("the sign-out reached the holding filter").isTrue();
                assertThat(hold.sawSession).as("the held sign-out has read its session").isTrue();
                hold.release = null;
                change.run();
            } finally {
                hold.release = null;
                release.countDown();
            }
            return signOut.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private long signedInSessions() {
        return count("select count(*) from spring_session where principal_name is not null");
    }

    /**
     * R1. The sign-out reads the session; the password change raises the security version and
     * replaces the identifier; the sign-out then finds at step 6 that its session belongs to an
     * older security version and ends it, under an identifier that is no longer anybody's. It
     * answered 204 and the cookie the password change had set still answered GET /api/me 200.
     */
    @Test
    void a3_6_aSignOutThatReadItsSessionBeforeAPasswordChangeLeavesNobodySignedIn() throws Exception {
        String email = "sample.donor@example.org";
        Browser tab = donor(email);
        String before = tab.sessionId;

        Browser.Answer signOut = signOutHeldDuring(tab, () -> {
            Browser.Answer changed = tab.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD));
            assertThat(changed.status()).as(changed.body()).isEqualTo(204);
            assertThat(tab.sessionId).as("the password change replaced the identifier").isNotEqualTo(before);
            assertThat(tab.get("/api/me").status()).as("signed in under the new identifier while the sign-out is held").isEqualTo(200);
        });

        assertThat(signOut.status()).as(signOut.body()).isEqualTo(204);
        assertError(tab.get("/api/me"), 401, "unauthenticated");
        assertThat(signedInSessions()).isZero();
        // The password change itself stands.
        assertError(browser().login(email, PASSWORD), 401, "invalid_credentials");
        assertThat(browser().login(email, OTHER_PASSWORD).status()).isEqualTo(200);
    }

    /** The same order with the second factor of a pending sign-in in place of the password change. */
    @Test
    void a3_6_aSignOutThatReadItsSessionBeforeTheSecondFactorLeavesNobodySignedIn() throws Exception {
        Staff owner = staff("sample.owner@example.org", "super_admin");
        assertThat(owner.browser().postEmpty("/api/auth/logout").status()).isEqualTo(204);
        Browser tab = pending(owner);
        String before = tab.sessionId;

        Browser.Answer signOut = signOutHeldDuring(tab, () -> {
            Browser.Answer verified = tab.post("/api/auth/mfa/verify", json("method", "totp", "code", code(owner.secret())));
            assertThat(verified.status()).as(verified.body()).isEqualTo(200);
            assertThat(tab.sessionId).isNotEqualTo(before);
            assertThat(tab.get("/api/me").status()).isEqualTo(200);
        });

        assertThat(signOut.status()).as(signOut.body()).isEqualTo(204);
        assertError(tab.get("/api/me"), 401, "unauthenticated");
        assertThat(signedInSessions()).isZero();
    }

    /** And with the confirmation of a first second factor, which also turns a pending session into a full one. */
    @Test
    void a3_6_aSignOutThatReadItsSessionBeforeEnrollmentIsConfirmedLeavesNobodySignedIn() throws Exception {
        String email = "sample.admin@example.org";
        Browser tab = activatedStaff(email, "admin");
        Browser.Answer login = tab.login(email, PASSWORD);
        assertThat(login.json().get("status").asText()).as(login.body()).isEqualTo("mfa_enrollment_required");
        Browser.Answer enrollment = tab.postEmpty("/api/auth/mfa/enroll");
        assertThat(enrollment.status()).as(enrollment.body()).isEqualTo(200);
        byte[] secret = base32Decode(enrollment.json().get("secret").asText());
        String before = tab.sessionId;

        Browser.Answer signOut = signOutHeldDuring(tab, () -> {
            Browser.Answer confirmed = tab.post("/api/auth/mfa/confirm", json("code", code(secret)));
            assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
            assertThat(tab.sessionId).isNotEqualTo(before);
            assertThat(tab.get("/api/me").status()).isEqualTo(200);
        });

        assertThat(signOut.status()).as(signOut.body()).isEqualTo(204);
        assertError(tab.get("/api/me"), 401, "unauthenticated");
        assertThat(signedInSessions()).isZero();
    }
}
