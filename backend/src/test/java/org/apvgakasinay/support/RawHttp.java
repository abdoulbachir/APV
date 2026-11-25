package org.apvgakasinay.support;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * One request written to a socket byte for byte. An HTTP client library tidies a path before
 * sending it; the requests that matter here are the ones that are not tidy.
 */
public final class RawHttp {

    /** An answer: its status, its header block in lower case, and its body. */
    public record Answer(int status, String head, String body) {

        public String header(String name) {
            for (String line : head.split("\r\n")) {
                if (line.startsWith(name.toLowerCase() + ":")) {
                    return line.substring(name.length() + 1).trim();
                }
            }
            return null;
        }
    }

    private RawHttp() {
    }

    /** Sends {@code requestLine} (method and target, for example "GET /api/me") with the given header lines. */
    public static Answer send(int port, String requestLine, String... headers) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30_000);
            // HTTP/1.0: the answer ends when the connection closes, with no chunking to undo.
            StringBuilder request = new StringBuilder(requestLine).append(" HTTP/1.0\r\nHost: 127.0.0.1:").append(port).append("\r\n");
            for (String header : headers) {
                request.append(header).append("\r\n");
            }
            OutputStream out = socket.getOutputStream();
            out.write(request.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            String answer = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int split = answer.indexOf("\r\n\r\n");
            String head = split < 0 ? answer : answer.substring(0, split);
            String body = split < 0 ? "" : answer.substring(split + 4);
            int status = Integer.parseInt(head.substring(9, 12));
            return new Answer(status, head.toLowerCase(), body);
        } catch (Exception failure) {
            throw new AssertionError("raw request failed: " + requestLine, failure);
        }
    }
}
