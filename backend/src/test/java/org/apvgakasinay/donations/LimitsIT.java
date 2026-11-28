package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;

/** Section 9 of contract/tests/donation-acceptance.md: the rate limits of contract/donation-lifecycle.md 12.2. */
class LimitsIT extends DonationIntegration {

    @Test
    void d9_1_anAddressReportsAtMostSixGiftsAnHour() {
        String details = zelle();
        Browser guest = visitor();
        for (int i = 0; i < 6; i++) {
            reported(post(guest, "/api/donations", body(reportOf("HELP-904", 1000 + i, details)), key()));
        }
        Browser.Answer limited = post(guest, "/api/donations", body(reportOf("HELP-904", 2000, details)), key());
        assertError(limited, 429, "rate_limited");
        // Until the window ends, and never more than sixty minutes.
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 3600);
        assertThat(count("select count(*) from donation")).isEqualTo(6);
        // Another address is not affected, and nothing escalates: the next hour starts at zero.
        report("HELP-904", 3000, details);
        advance(Duration.ofHours(1));
        Browser later = visitor();
        later.address = guest.address;
        reported(post(later, "/api/donations", body(reportOf("HELP-904", 4000, details)), key()));
    }

    @Test
    void d9_5_parallelReportsFromOneAddressCannotAllSlipUnderTheLimit() {
        String details = zelle();
        List<Callable<Browser.Answer>> calls = new ArrayList<>();
        Browser first = visitor();
        for (int i = 0; i < 12; i++) {
            // Twelve browsers behind one address, each with its own session, all at the same moment.
            Browser browser = visitor();
            browser.address = first.address;
            int amount = 1000 + i;
            calls.add(() -> post(browser, "/api/donations", body(reportOf("HELP-904", amount, details)), key()));
        }
        List<Browser.Answer> answers = together(calls);
        assertThat(answers.stream().filter(answer -> answer.status() == 201).count()).isEqualTo(6);
        assertThat(answers.stream().filter(answer -> answer.status() == 429).count()).isEqualTo(6);
        assertThat(count("select count(*) from donation")).isEqualTo(6);
    }

    @Test
    void d9_1_paymentMethodsAndEvidenceUploadsAreLimitedPerAddress() {
        Reported gift = report("HELP-904", 5000, zelle());
        Browser reader = browser();
        for (int i = 0; i < 120; i++) {
            assertThat(reader.get("/api/giving/methods").status()).isEqualTo(200);
        }
        Browser.Answer limited = reader.get("/api/giving/methods");
        assertError(limited, 429, "rate_limited");
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 60);
        advance(Duration.ofMinutes(1));
        assertThat(reader.get("/api/giving/methods").status()).isEqualTo(200);

        // Twelve uploads an hour, counted before the token is looked at: wrong tokens use the allowance too.
        Browser uploader = visitor();
        uploader.extraHeaders.put("X-Evidence-Token", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        for (int i = 0; i < 12; i++) {
            assertError(uploader.sendBytes("POST", "/api/donations/evidence", Pictures.png(20, 20), "image/png", uploader.token), 400, "evidence_token_invalid");
        }
        uploader.extraHeaders.put("X-Evidence-Token", gift.uploadToken());
        assertError(uploader.sendBytes("POST", "/api/donations/evidence", Pictures.png(20, 20), "image/png", uploader.token), 429, "rate_limited");
        assertThat(count("select count(*) from donation_evidence")).isZero();
    }

    @Test
    void d9_1_staffOperationsAreLimitedPerAccount() {
        Staff owner = owner();
        // A second sign-in of the same account, made first: signing in moves the clock to the next authenticator step.
        Browser elsewhere = signedInAgain(owner);
        // Sixty writes a minute for one account, counted before the gift is looked at.
        for (int i = 0; i < 60; i++) {
            assertError(owner.browser().postEmpty("/api/admin/donations/don_AAAAAAAAAAAAAAAAAAAAAA/reopen"), 404, "donation_not_found");
        }
        assertError(owner.browser().postEmpty("/api/admin/donations/don_AAAAAAAAAAAAAAAAAAAAAA/reopen"), 429, "rate_limited");
        // Reads have their own counter: three hundred a minute.
        for (int i = 0; i < 300; i++) {
            assertThat(owner.browser().get("/api/admin/donations?pageSize=1").status()).isEqualTo(200);
        }
        Browser.Answer limited = owner.browser().get("/api/admin/donations?pageSize=1");
        assertError(limited, 429, "rate_limited");
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 60);
        // The counter is the account's, whatever browser it uses; another account has its own.
        assertError(elsewhere.get("/api/admin/donations"), 429, "rate_limited");
        advance(Duration.ofMinutes(1));
        assertThat(owner.browser().get("/api/admin/donations").status()).isEqualTo(200);
    }

    @Test
    void d9_6_aLimitedRequestIsAnsweredTheSameWhateverItNames() {
        String details = zelle();
        Reported gift = report("HELP-904", 5000, details);
        Browser guest = visitor();
        for (int i = 0; i < 10; i++) {
            status(guest, gift.trackingCode());
        }
        Browser.Answer existing = status(guest, gift.trackingCode());
        Browser.Answer unknown = status(guest, "0000-0000-0000-0000-0000");
        assertError(existing, 429, "rate_limited");
        assertThat(unknown.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", "")).isEqualTo(existing.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", ""));
        assertThat(unknown.header("Retry-After")).isEqualTo(existing.header("Retry-After"));
    }

    @Test
    void d9_7_countersHoldKeyedHashesNeverAnAddressAReferenceOrAnAccount() {
        Reported gift = report("HELP-904", 5000, zelle());
        Browser guest = visitor();
        guest.address = "203.0.113.77";
        status(guest, gift.trackingCode());
        status(guest, gift.reference() + "-0000-0000-0000");
        guest.get("/api/giving/methods");
        Staff owner = owner();
        owner.browser().get("/api/admin/donations");

        assertThat(count("select count(*) from donation_throttle")).isGreaterThanOrEqualTo(4);
        assertThat(count("select count(*) from donation_throttle where length(key_hash) <> 32")).isZero();
        String ownerId = String.valueOf(count("select id from account where email_normalized = 'sample.owner@example.org'"));
        for (String plain : List.of("203.0.113.77", gift.reference(), gift.reference().replace("-", ""), "sample.owner")) {
            assertThat(count("select count(*) from donation_throttle t where position('" + plain + "' in row(t.*)::text) > 0"
                    + " or position(encode('" + plain + "'::bytea, 'hex') in encode(key_hash, 'hex')) > 0")).as(plain).isZero();
        }
        // A scope names the kind of counter, never who is counted.
        assertThat(jdbc.sql("select distinct scope from donation_throttle order by scope").query(String.class).list())
                .allMatch(scope -> scope.matches("^[a-z.#_]+$")).doesNotContain(ownerId);
    }
}
