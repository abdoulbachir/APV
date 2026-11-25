package org.apvgakasinay.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.startup.Tomcat;
import org.apvgakasinay.support.Contract;
import org.apvgakasinay.support.RawHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Independent review, D4: what the embedded server answers by itself, before any filter of
 * the application runs, is the contract's error body and never its own HTML page. The server
 * alone, with nothing deployed, so every answer here is the server's own.
 */
class ApiErrorReportValveTest {

    private static Tomcat tomcat;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        Path base = Files.createTempDirectory("apv-valve-test");
        tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        ((StandardHost) tomcat.getHost()).setErrorReportValveClass(ApiErrorReportValve.class.getName());
        tomcat.addContext("", base.toString());
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    private static void assertContractError(RawHttp.Answer answer, int status, String code) {
        String body = answer.body();
        assertThat(answer.status()).as(body).isEqualTo(status);
        assertThat(answer.head()).contains("content-type: application/json").contains("cache-control: no-store").contains("x-request-id: ");
        assertThat(body).doesNotContain("<").doesNotContain("Tomcat").doesNotContain("timestamp");
        assertThat(Contract.get().violations("Error", body)).as(body).isEmpty();
        assertThat(body).contains("\"code\":\"" + code + "\"").containsPattern("\"requestId\":\"[0-9a-f]{16}\"");
    }

    @Test
    void a9_10_aPathTheServerRefusesIsAnsweredInTheContractShapeNotInHtml() {
        for (String path : new String[] {"/api/auth/login%2F", "/api/auth/csrf%00", "/api/%2e%2e%2F", "/api/auth/login%"}) {
            assertContractError(RawHttp.send(port, "GET " + path), 400, "validation_error");
            assertContractError(RawHttp.send(port, "POST " + path, "Content-Length: 0"), 400, "validation_error");
        }
    }

    /** A request line the server cannot even read: a raw backslash, a character no path may hold, a space, a broken method. */
    @Test
    void a9_10_aRequestLineTheServerCannotReadIsAnsweredTheSameWay() {
        for (String line : new String[] {"GET /api\\me", "GET /api/me|x", "GET /api/a b", "G<T /api/me", "GET /api/me%zz"}) {
            assertContractError(RawHttp.send(port, line), 400, "validation_error");
        }
    }

    @Test
    void a9_10_everyOtherErrorOfTheServerItselfHasTheSameShape() {
        // Nothing is deployed here, so any well-formed path is the server's own 404.
        assertContractError(RawHttp.send(port, "GET /api/anything"), 404, "not_found");
        assertContractError(RawHttp.send(port, "GET /"), 404, "not_found");
    }
}
