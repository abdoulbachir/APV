package org.apvgakasinay.probe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One simulated browser talking to the probe over real HTTP. It keeps the session cookie the
 * way a browser does, and holds a CSRF token in memory the way the frontend will. Both can be
 * read and overwritten, so a test can replay stale ones.
 */
final class Browser {

    record Answer(int status, String body, String setCookie) {

        String code() {
            Matcher matcher = Pattern.compile("\"code\":\"([a-z_]+)\"").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }

        String field(String name) {
            Matcher matcher = Pattern.compile("\"" + name + "\":\"?([^\",}\\]]+)").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }
    }

    private static final Pattern SESSION_COOKIE = Pattern.compile("(apv_session|JSESSIONID)=([^;]*)");

    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;
    String cookieName;
    String sessionId;
    String token;

    Browser(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    Answer get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    /** POST with the token this browser currently holds. */
    Answer post(String path, String json) {
        return post(path, json, token);
    }

    /** POST with an explicit token; null sends no CSRF header at all. */
    Answer post(String path, String json, String csrfToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return send(request);
    }

    /** Fetches a token, as the frontend does before any POST and after every session change. */
    String fetchToken() {
        Answer answer = get("/probe/csrf");
        if (answer.status() != 200) {
            throw new AssertionError("csrf endpoint answered " + answer.status() + " " + answer.body());
        }
        token = answer.field("token");
        return token;
    }

    Answer login(String email, String password) {
        fetchToken();
        Answer answer = post("/probe/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        if (answer.status() == 200) {
            fetchToken();
        }
        return answer;
    }

    private Answer send(HttpRequest.Builder request) {
        if (sessionId != null) {
            request.header("Cookie", (cookieName == null ? "apv_session" : cookieName) + "=" + sessionId);
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String setCookie = String.join(" | ", response.headers().allValues("Set-Cookie"));
            for (String header : response.headers().allValues("Set-Cookie")) {
                Matcher matcher = SESSION_COOKIE.matcher(header);
                if (matcher.find()) {
                    cookieName = matcher.group(1);
                    // An emptied cookie means the server ended the session.
                    sessionId = matcher.group(2).isEmpty() ? null : matcher.group(2);
                }
            }
            return new Answer(response.statusCode(), response.body(), setCookie);
        } catch (Exception failure) {
            throw new AssertionError("request failed", failure);
        }
    }
}
