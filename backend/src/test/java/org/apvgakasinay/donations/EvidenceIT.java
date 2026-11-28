package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.apvgakasinay.identity.Browser;
import org.apvgakasinay.support.SharedPostgres;
import org.junit.jupiter.api.Test;

/** Section 3 of contract/tests/donation-acceptance.md: the one optional, private screenshot of a gift. */
class EvidenceIT extends DonationIntegration {

    private Browser guest() {
        Browser browser = browser();
        browser.csrf();
        return browser;
    }

    private Browser.Answer upload(Browser browser, String token, byte[] picture, String contentType) {
        if (token != null) {
            browser.extraHeaders.put("X-Evidence-Token", token);
        }
        try {
            return browser.sendBytes("POST", "/api/donations/evidence", picture, contentType, browser.token);
        } finally {
            browser.extraHeaders.remove("X-Evidence-Token");
        }
    }

    private Browser.Answer upload(String token, byte[] picture, String contentType) {
        return upload(guest(), token, picture, contentType);
    }

    private Path storedFile(String reference) {
        String key = jdbc.sql("select v.storage_key from donation_evidence v join donation d on d.id = v.donation_id where d.reference = :reference")
                .param("reference", reference).query(String.class).single();
        return SharedPostgres.evidenceRoot().resolve(key);
    }

    private long files() throws Exception {
        try (Stream<Path> all = Files.walk(SharedPostgres.evidenceRoot())) {
            return all.filter(Files::isRegularFile).count();
        }
    }

    private String evidenceOf(Staff owner, Reported gift) {
        return owner.browser().get("/api/admin/donations/" + giftId(gift.reference())).json().get("evidence").asText();
    }

    @Test
    void d3_1_d3_8_aJpegIsStoredReencodedAndTheGiftShowsItAttached() throws Exception {
        Reported gift = report("HELP-904", 5000, zelle());
        byte[] sent = Pictures.jpeg(3200, 1600);
        Browser.Answer answer = upload(gift.uploadToken(), sent, "image/jpeg");
        assertThat(answer.status()).as(answer.body()).isEqualTo(204);
        assertThat(answer.body()).isEmpty();
        assertThat(answer.header("Cache-Control")).isEqualTo("no-store");

        // Only the copy the server wrote is on disk: another file, scaled to 2,400 pixels on its longer side.
        byte[] stored = Files.readAllBytes(storedFile(gift.reference()));
        assertThat(stored).isNotEqualTo(sent);
        BufferedImage picture = Pictures.decode(stored);
        assertThat(picture.getWidth()).isEqualTo(2400);
        assertThat(picture.getHeight()).isEqualTo(1200);
        try (Stream<Path> all = Files.walk(SharedPostgres.evidenceRoot())) {
            for (Path file : all.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readAllBytes(file)).as("the bytes that were sent are never written").isNotEqualTo(sent);
            }
        }
        // The database holds the key, the format, the size and a hash of the stored file; the key is derived from nothing.
        JsonNode row = org.apvgakasinay.support.Json.tree(jdbc.sql("""
                select v.format, v.width, v.height, v.bytes, encode(v.sha256, 'hex') as sha256, v.storage_key
                  from donation_evidence v join donation d on d.id = v.donation_id where d.reference = :reference
                """).param("reference", gift.reference()).query().singleRow());
        assertThat(row.get("format").asText()).isEqualTo("jpeg");
        assertThat(row.get("width").asInt()).isEqualTo(2400);
        assertThat(row.get("bytes").asLong()).isEqualTo(stored.length);
        assertThat(row.get("sha256").asText())
                .isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(stored)));
        assertThat(row.get("storage_key").asText()).matches("^[0-9a-f]{2}/[0-9a-f]{40}\\.jpg$").doesNotContain(gift.reference().replace("-", "").toLowerCase());

        Staff owner = owner();
        assertThat(evidenceOf(owner, gift)).isEqualTo("attached");
        Browser tracker = guest();
        assertThat(status(tracker, gift.trackingCode()).json().get("evidenceAttached").asBoolean()).isTrue();
        assertThat(count("select count(*) from donation_history where action = 'evidence_attached'")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.evidence_attached' and outcome = 'success'")).isEqualTo(1);
    }

    @Test
    void d3_2_d3_9_aPngIsStoredAndACameraJpegLosesItsDataButIsTurnedUpright() throws Exception {
        String details = zelle();
        Reported png = report("HELP-904", 5000, details);
        assertThat(upload(png.uploadToken(), Pictures.png(390, 844), "image/png").status()).isEqualTo(204);
        assertThat(storedFile(png.reference()).toString()).endsWith(".png");
        assertThat(Pictures.decode(Files.readAllBytes(storedFile(png.reference()))).getHeight()).isEqualTo(844);

        Reported camera = report("HELP-904", 6000, details);
        byte[] sent = Pictures.withExif(Pictures.jpeg(400, 200), 6, "SampleCameraMaker GPS 14.5N 5.9E");
        assertThat(upload(camera.uploadToken(), sent, "image/jpeg").status()).isEqualTo(204);
        byte[] stored = Files.readAllBytes(storedFile(camera.reference()));
        assertThat(Pictures.contains(stored, "SampleCameraMaker")).isFalse();
        assertThat(Pictures.contains(stored, "Exif")).isFalse();
        BufferedImage upright = Pictures.decode(stored);
        assertThat(upright.getWidth()).isEqualTo(200);
        assertThat(upright.getHeight()).isEqualTo(400);
        assertThat(Pictures.colour(upright, 100, 40)).isEqualTo("red");

        // The declared type is only what step 3 judges: the format is decided from the content.
        Reported mislabelled = report("HELP-904", 7000, details);
        assertThat(upload(mislabelled.uploadToken(), Pictures.png(100, 100), "image/jpeg").status()).isEqualTo(204);
        assertThat(storedFile(mislabelled.reference()).toString()).endsWith(".png");
    }

    @Test
    void d3_3_d3_12_whatIsNotAPictureIsRefusedAndTheGiftStaysReportedWithoutEvidence() throws Exception {
        Reported gift = report("HELP-904", 5000, zelle());
        long before = files();
        byte[] gif = "GIF89a\u0001\u0000\u0001\u0000\u0080\u0000\u0000".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] text = "This is a text file renamed screenshot.png".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte[] refused : List.of(gif, pdf, text, new byte[0])) {
            assertError(upload(gift.uploadToken(), refused, "image/png"), 400, "evidence_invalid");
        }
        assertThat(files()).isEqualTo(before);
        assertThat(count("select count(*) from donation_evidence")).isZero();
        assertThat(count("select count(*) from donation where state = 'reported'")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.evidence_refused' and details ->> 'reason' = 'content'")).isEqualTo(4);

        // A refusal with an answer spent nothing: the token still works while it lasts.
        assertThat(upload(gift.uploadToken(), Pictures.png(200, 200), "image/png").status()).isEqualTo(204);
        assertThat(status(guest(), gift.trackingCode()).json().get("evidenceAttached").asBoolean()).isTrue();
    }

    @Test
    void d3_4_aPictureAboveFiveMebibytesIsRefusedWithoutBeingRead() throws Exception {
        Reported gift = report("HELP-904", 5000, zelle());
        // Declared: refused at step 3, before the token of the session or of the gift is looked at. The
        // answer arrives although not one byte of the body was sent: the server did not wait to read it.
        String declared;
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("POST /api/donations/evidence HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nContent-Type: image/png\r\nContent-Length: 6291456\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            StringBuilder head = new StringBuilder();
            java.io.InputStream in = socket.getInputStream();
            while (!head.toString().contains("payload_too_large")) {
                int next = in.read();
                assertThat(next).as("the answer ended early: " + head).isNotNegative();
                head.append((char) next);
            }
            declared = head.toString();
        }
        assertThat(declared).startsWith("HTTP/1.1 413").contains("Cache-Control: no-store").contains("payload_too_large");

        // Not declared: a stream that turns out longer than the limit is cut off there.
        Browser guest = guest();
        byte[] tooLong = new byte[6 * 1024 * 1024];
        System.arraycopy(Pictures.png(50, 50), 0, tooLong, 0, Pictures.png(50, 50).length);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/donations/evidence"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(tooLong)))
                .header("Content-Type", "image/png").header("X-CSRF-TOKEN", guest.token).header("X-Evidence-Token", gift.uploadToken())
                .header("Cookie", guest.cookieName + "=" + guest.sessionId).header("X-Forwarded-For", guest.address).build();
        HttpResponse<String> streamed = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(streamed.statusCode()).as(streamed.body()).isEqualTo(413);
        assertThat(streamed.body()).contains("payload_too_large");
        assertThat(count("select count(*) from donation_evidence")).isZero();

        // Exactly at the limit is a size the server accepts; what it holds is then judged as a picture.
        assertError(upload(gift.uploadToken(), new byte[5 * 1024 * 1024], "image/png"), 400, "evidence_invalid");
    }

    @Test
    void d3_5_aDeclaredTypeThatIsNotAPictureIsRefused() {
        Reported gift = report("HELP-904", 5000, zelle());
        for (String type : List.of("application/pdf", "image/gif", "image/heic", "multipart/form-data; boundary=x", "application/json")) {
            assertError(upload(gift.uploadToken(), Pictures.png(50, 50), type), 415, "unsupported_media_type");
        }
        assertError(upload(gift.uploadToken(), Pictures.png(50, 50), null), 415, "unsupported_media_type");
        assertThat(count("select count(*) from donation_evidence")).isZero();
    }

    @Test
    void d3_6_aWrongExpiredOrUsedTokenAndAGiftNoLongerReportedAreAnsweredAlike() {
        String details = zelle();
        byte[] picture = Pictures.png(120, 120);
        Staff owner = owner();

        Reported used = report("HELP-904", 5000, details);
        assertThat(upload(used.uploadToken(), picture, "image/png").status()).isEqualTo(204);
        Reported decided = report("HELP-904", 5000, details);
        owner.browser().post("/api/admin/donations/" + giftId(decided.reference()) + "/reject", body(map("reason", "not_received")));
        Reported expired = report("HELP-904", 5000, details);
        Reported other = report("HELP-904", 5000, details);
        advance(Duration.ofMinutes(30));
        Reported fresh = report("HELP-904", 5000, details);

        // A token of another gift's shape that belongs to none: a real token with its last character changed.
        String wrong = fresh.uploadToken().substring(0, 42) + (fresh.uploadToken().endsWith("A") ? "B" : "A");
        String unknown = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        List<Browser.Answer> refusals = List.of(
                upload(wrong, picture, "image/png"),
                upload(unknown, picture, "image/png"),
                upload(used.uploadToken(), picture, "image/png"),
                upload(decided.uploadToken(), picture, "image/png"),
                upload(expired.uploadToken(), picture, "image/png"),
                upload(other.uploadToken(), picture, "image/png"));
        for (Browser.Answer refusal : refusals) {
            assertError(refusal, 400, "evidence_token_invalid");
            assertThat(refusal.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", ""))
                    .isEqualTo(refusals.get(0).body().replaceAll("\"requestId\":\"[0-9a-f]+\"", ""));
        }
        assertThat(count("select count(*) from donation_evidence")).isEqualTo(1);
        // A token is one of the operation's headers: missing or malformed is a validation error, named as it is written.
        Browser.Answer missing = upload(null, picture, "image/png");
        assertError(missing, 400, "validation_error");
        assertThat(missing.json().at("/error/fields/0/name").asText()).isEqualTo("X-Evidence-Token");
        assertThat(upload("short", picture, "image/png").fieldCode()).isEqualTo("invalid_format");
        // The token that is still good, is.
        assertThat(upload(fresh.uploadToken(), picture, "image/png").status()).isEqualTo(204);
    }

    @Test
    void d3_7_ofTwoUploadsWithOneTokenAtTheSameMomentExactlyOneIsStored() throws Exception {
        Reported gift = report("HELP-904", 5000, zelle());
        long before = files();
        byte[] picture = Pictures.jpeg(1600, 1200);
        Browser one = guest();
        Browser two = guest();
        List<Browser.Answer> answers = together(List.of(
                () -> upload(one, gift.uploadToken(), picture, "image/jpeg"),
                () -> upload(two, gift.uploadToken(), picture, "image/jpeg")));
        assertThat(answers.stream().map(Browser.Answer::status).sorted().toList()).isEqualTo(List.of(204, 400));
        assertThat(answers.stream().filter(answer -> answer.status() == 400).findFirst().orElseThrow().code()).isEqualTo("evidence_token_invalid");
        assertThat(count("select count(*) from donation_evidence")).isEqualTo(1);
        // The upload that lost left no file behind.
        assertThat(files()).isEqualTo(before + 1);
    }

    @Test
    void d3_13_d8_10_onlyAReviewerReadsAScreenshotThroughOneRouteWithItsHeaders() throws Exception {
        Reported gift = report("HELP-904", 5000, zelle());
        assertThat(upload(gift.uploadToken(), Pictures.png(300, 200), "image/png").status()).isEqualTo(204);
        String id = giftId(gift.reference());
        String key = storedFile(gift.reference()).getFileName().toString();

        // No public route serves it, under any name it could be guessed by, and the uploader cannot read it back.
        Browser guest = guest();
        for (String path : List.of("/media/public/" + key, "/media/public/evidence/" + key, "/media/" + key, "/api/donations/evidence",
                "/api/donations/" + gift.reference() + "/evidence")) {
            assertThat(guest.get(path).status()).as(path).isIn(404, 405);
        }
        assertError(guest.get("/api/admin/donations/" + id + "/evidence"), 401, "unauthenticated");
        assertError(donor("sample.donor@example.org").get("/api/admin/donations/" + id + "/evidence"), 403, "forbidden");
        assertError(staff("sample.admin@example.org", "admin").browser().get("/api/admin/donations/" + id + "/evidence"), 403, "forbidden");

        Staff owner = owner();
        Browser.Answer read = owner.browser().get("/api/admin/donations/" + id + "/evidence");
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.header("Content-Type")).isEqualTo("image/png");
        assertThat(read.header("Cache-Control")).isEqualTo("no-store");
        assertThat(read.header("ETag")).isNull();
        assertThat(read.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(read.header("Content-Disposition")).startsWith("inline; filename=\"evidence-").endsWith(".png\"");
        assertThat(read.header("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");
        assertThat(read.header("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        // What is served is the stored file, byte for byte.
        HttpResponse<byte[]> bytes = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/admin/donations/" + id + "/evidence"))
                .header("Cookie", owner.browser().cookieName + "=" + owner.browser().sessionId).header("X-Forwarded-For", owner.browser().address).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(bytes.body()).isEqualTo(Files.readAllBytes(storedFile(gift.reference())));

        // Every read is in the audit log, with the reviewer and the gift (11.4).
        assertThat(count("select count(*) from audit_event where event_type = 'donation.evidence_read' and details ->> 'gift' = '" + id
                + "' and actor_account_id = (select id from account where email_normalized = 'sample.owner@example.org')")).isEqualTo(2);

        // A gift with no screenshot, and no such gift.
        Reported bare = report("HELP-904", 100, currentDetails("zelle"));
        assertError(owner.browser().get("/api/admin/donations/" + giftId(bare.reference()) + "/evidence"), 404, "evidence_not_found");
        assertError(owner.browser().get("/api/admin/donations/don_AAAAAAAAAAAAAAAAAAAAAA/evidence"), 404, "donation_not_found");
    }

    @Test
    void d3_13_theReviewerCanDeleteAScreenshotAndTheGiftKeepsThatOneExisted() throws Exception {
        Reported gift = report("HELP-904", 5000, zelle());
        assertThat(upload(gift.uploadToken(), Pictures.png(300, 200), "image/png").status()).isEqualTo(204);
        Path file = storedFile(gift.reference());
        assertThat(file).exists();
        String id = giftId(gift.reference());
        Staff owner = owner();

        Browser.Answer deleted = owner.browser().postEmpty("/api/admin/donations/" + id + "/evidence/delete");
        assertThat(deleted.status()).as(deleted.body()).isEqualTo(200);
        assertValid("DonationDetail", deleted);
        assertThat(deleted.json().get("evidence").asText()).isEqualTo("deleted");
        assertThat(deleted.json().at("/history/2/action").asText()).isEqualTo("evidence_deleted");
        assertThat(deleted.json().at("/history/2/by/displayName").asText()).isEqualTo("Sample Staff");
        assertThat(file).doesNotExist();
        assertThat(count("select count(*) from donation_evidence where deleted_at is not null")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.evidence_deleted' and details ->> 'reason' = 'privacy_request'")).isEqualTo(1);

        assertError(owner.browser().get("/api/admin/donations/" + id + "/evidence"), 404, "evidence_not_found");
        assertError(owner.browser().postEmpty("/api/admin/donations/" + id + "/evidence/delete"), 404, "evidence_not_found");
        assertThat(status(guest(), gift.trackingCode()).json().get("evidenceAttached").asBoolean()).isFalse();
        // One, attached once, no replacement: the token does not come back with the deletion.
        assertError(upload(gift.uploadToken(), Pictures.png(300, 200), "image/png"), 400, "evidence_token_invalid");
    }

    @Test
    void d9_4_whenTheStorageBudgetIsFullUploadsWaitAndReportsContinue() {
        String details = zelle();
        Reported first = report("HELP-904", 5000, details);
        assertThat(upload(first.uploadToken(), Pictures.png(100, 100), "image/png").status()).isEqualTo(204);
        // The stored screenshots are made to weigh the whole budget of two gibibytes.
        jdbc.sql("update donation_evidence set bytes = 2147483648").update();

        Reported second = report("HELP-904", 6000, details);
        Browser.Answer refused = upload(second.uploadToken(), Pictures.png(100, 100), "image/png");
        assertError(refused, 503, "unavailable");
        assertThat(refused.header("Retry-After")).isNotNull();
        assertThat(count("select count(*) from donation_evidence")).isEqualTo(1);
        // The brake starting to hold is recorded once, however many uploads it refuses (9.8).
        assertError(upload(second.uploadToken(), Pictures.png(100, 100), "image/png"), 503, "unavailable");
        assertThat(count("select count(*) from audit_event where event_type = 'donation.brake_held' and details ->> 'brake' = 'evidence_storage'")).isEqualTo(1);
        // Evidence is optional: gifts are still reported.
        report("HELP-904", 7000, details);
    }
}
