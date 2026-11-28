package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Scenarios 9.2, 9.3 and 9.8: the brakes for the whole site (contract/donation-lifecycle.md,
 * 12.3), which are configuration. Here the site takes three reports an hour and lets five wait.
 */
@TestPropertySource(properties = {"apv.donations.brakes.reports-per-hour=3", "apv.donations.brakes.queue=5"})
class BrakesIT extends DonationIntegration {

    private Browser.Answer send(String details, long amount) {
        return post(visitor(), "/api/donations", body(reportOf("HELP-904", amount, details)), key());
    }

    @Test
    void d9_2_d9_8_whenTooManyReportsArriveInAnHourFurtherOnesWaitAndNothingIsRecorded() {
        String details = zelle();
        Browser first = visitor();
        String firstKey = key();
        String firstBody = body(reportOf("HELP-904", 1000, details));
        reported(post(first, "/api/donations", firstBody, firstKey));
        reported(send(details, 1001));
        reported(send(details, 1002));

        // From any address: the bound is the site's.
        Browser.Answer paused = send(details, 1003);
        assertError(paused, 503, "reporting_paused");
        assertThat(Integer.parseInt(paused.header("Retry-After"))).isBetween(1, 3600);
        assertError(send(details, 1004), 503, "reporting_paused");
        assertThat(count("select count(*) from donation")).isEqualTo(3);
        assertThat(count("select count(*) from idempotency_record")).isEqualTo(3);
        // One audit record when the brake starts to hold, however many reports it then refuses.
        assertThat(count("select count(*) from audit_event where event_type = 'donation.brake_held' and details ->> 'brake' = 'reports_per_hour'")).isEqualTo(1);

        // A report already accepted is still answered: a repeated request is recognised before the brake is reached.
        assertThat(post(first, "/api/donations", firstBody, firstKey).status()).isEqualTo(201);
        // Everything else keeps working: methods are read, a status is looked up.
        assertThat(visitor().get("/api/giving/methods").status()).isEqualTo(200);

        advance(Duration.ofHours(1));
        reported(send(details, 1005));
    }

    @Test
    void d9_3_whenTooManyGiftsWaitToBeReviewedReportsWaitUntilTheQueueFalls() {
        String details = zelle();
        for (int i = 0; i < 3; i++) {
            reported(send(details, 2000 + i));
        }
        advance(Duration.ofHours(1));
        for (int i = 0; i < 2; i++) {
            reported(send(details, 3000 + i));
        }
        // Five wait. The hour's own bound is not reached; the queue's is.
        Browser.Answer paused = send(details, 4000);
        assertError(paused, 503, "reporting_paused");
        assertThat(paused.header("Retry-After")).isNotNull();
        assertThat(count("select count(*) from donation")).isEqualTo(5);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.brake_held' and details ->> 'brake' = 'queue'")).isEqualTo(1);

        // The queue falls as the reviewer decides, and reports are taken again.
        Staff owner = owner();
        List<String> waiting = jdbc.sql("select public_id from donation order by id limit 2").query(String.class).list();
        owner.browser().post("/api/admin/donations/" + waiting.get(0) + "/reject", body(map("reason", "not_received")));
        reported(send(details, 4001));
        assertError(send(details, 4002), 503, "reporting_paused");
        assertThat(post(owner.browser(), "/api/admin/donations/" + waiting.get(1) + "/confirm", body(confirmationOf("HELP-904", 2001)), key()).status())
                .isEqualTo(200);
        // A gift entered by staff does not wait to be reviewed and is never held back.
        assertThat(post(owner.browser(), "/api/admin/donations", body(map("caseCode", "FUND", "amount", 500, "receivedOn", TODAY,
                "method", map("description", "Cash"), "payerName", "Sample Donor", "display", map("mode", "hidden"), "note", "Cash.")), key()).status())
                .isEqualTo(201);
    }
}
