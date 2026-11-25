package org.apvgakasinay.shared;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;

/**
 * The server's last word on an error nothing else has given a body to, in the contract's
 * shape. The embedded server refuses some requests before the application sees them (a path
 * with an encoded slash or an encoded null, a request line it cannot read) and would answer
 * those with its own HTML page. This backend serves an API and files; it never answers HTML.
 */
public final class ApiErrorReportValve extends ErrorReportValve {

    public ApiErrorReportValve() {
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        // The same conditions as the server's own page: an error, no body yet, reported once.
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }
        String code = ErrorWriter.codeFor(status);
        String requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE) instanceof String known ? known : RequestIdFilter.newId();
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("X-Request-Id", requestId);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(ErrorWriter.body(code, ErrorWriter.messageFor(code), requestId));
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException clientGone) {
            // Nobody is left to tell.
        }
    }
}
