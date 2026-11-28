package org.apvgakasinay.identity;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apvgakasinay.support.Json;

/**
 * One simulated browser talking to the application over real HTTP. It keeps the session cookie
 * the way a browser does and holds a CSRF token in memory the way the frontend will. Both can
 * be read and overwritten, so a test can replay stale ones. Every answer is recorded.
 */
public final class Browser {

    /** Every answer any browser received in the current test, for checks that apply to all of them. */
    public static final List<Answer> RECORDED = java.util.Collections.synchronizedList(new ArrayList<>());

    public record Answer(String method, String path, int status, String body, HttpHeaders headers) {

        public JsonNode json() {
            return Json.parse(body);
        }

        public String code() {
            return body.isBlank() ? null : json().at("/error/code").asText(null);
        }

        public String fieldCode() {
            return json().at("/error/fields/0/code").asText(null);
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private static final Pattern SESSION_COOKIE = Pattern.compile("(?:__Host-)?apv_session=([^;]*)");
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;
    /** The client address this browser appears to come from (the test profile trusts 127.0.0.1 as a proxy). */
    public String address;
    public String sessionId;
    /** "apv_session" in development and tests; "__Host-apv_session" as in production. */
    public String cookieName = "apv_session";
    public String token;
    public final Map<String, String> extraHeaders = new LinkedHashMap<>();

    public Browser(int port) {
        this.base = "http://127.0.0.1:" + port;
        int n = ADDRESSES.incrementAndGet();
        this.address = "10." + (n / 65536 % 250) + "." + (n / 256 % 256) + "." + (n % 256);
    }

    /** The identifier of the stored session, as it is in the database. */
    public String storedSessionId() {
        return new String(Base64.getDecoder().decode(sessionId), StandardCharsets.UTF_8);
    }

    public Answer get(String path) {
        return send("GET", path, null, null, null);
    }

    /** POST of a JSON body with the token this browser holds. */
    public Answer post(String path, String json) {
        return send("POST", path, json, "application/json", token);
    }

    /** POST with an explicit token; null sends no CSRF header at all. */
    public Answer post(String path, String json, String csrfToken) {
        return send("POST", path, json, "application/json", csrfToken);
    }

    /** POST with no body and no Content-Type, for the operations that define none. */
    public Answer postEmpty(String path) {
        return send("POST", path, null, null, token);
    }

    public Answer send(String method, String path, String body, String contentType, String csrfToken) {
        return sendBytes(method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), contentType, csrfToken);
    }

    /** A request whose body is bytes, as a picture is sent. */
    public Answer sendBytes(String method, String path, byte[] body, String contentType, String csrfToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        if (address != null) {
            request.header("X-Forwarded-For", address);
        }
        if (sessionId != null) {
            request.header("Cookie", cookieName + "=" + sessionId);
        }
        extraHeaders.forEach(request::header);
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                Matcher matcher = SESSION_COOKIE.matcher(header);
                if (matcher.find()) {
                    // An emptied cookie means the server ended the session.
                    sessionId = matcher.group(1).isEmpty() ? null : matcher.group(1);
                }
            }
            Answer answer = new Answer(method, path, response.statusCode(), response.body(), response.headers());
            RECORDED.add(answer);
            return answer;
        } catch (Exception failure) {
            throw new AssertionError("request failed: " + method + " " + path, failure);
        }
    }

    /** Fetches a token, as the frontend does before any POST and after every session change. */
    public String csrf() {
        Answer answer = get("/api/auth/csrf");
        if (answer.status() != 200) {
            throw new AssertionError("csrf endpoint answered " + answer.status() + " " + answer.body());
        }
        token = answer.json().get("token").asText();
        return token;
    }

    /** Signs in the way the frontend does: token, sign-in, new token. */
    public Answer login(String email, String password) {
        csrf();
        Answer answer = post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        if (answer.status() == 200) {
            csrf();
        }
        return answer;
    }

    /** A second browser that presents this one's current cookie: what someone holding a stolen or stale cookie would send. */
    public Browser copyOfCookie() {
        Browser copy = new Browser(Integer.parseInt(base.substring(base.lastIndexOf(':') + 1)));
        copy.sessionId = sessionId;
        copy.address = address;
        return copy;
    }
}
