package org.apvgakasinay.shared;

import org.apache.catalina.Container;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Two settings of the embedded server, so that every answer under /api is the application's own.
 *
 * 1. Its error page is {@link ApiErrorReportValve}: the contract's envelope, never HTML. The
 *    framework's own error page and its /error route are switched off (application.yaml), so
 *    this is the one place an error that nothing else answered gets its body.
 * 2. It hands TRACE requests to the application like any other method. By default the server
 *    answers them itself, with an empty 405 and an Allow line naming methods this API does not
 *    have. Nothing here ever serves TRACE: the first step of the request order answers it 405
 *    method_not_allowed, or 404 for an unknown path, exactly as it answers PUT or DELETE.
 */
@Configuration
class ServerErrorConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> apiErrorsFromTheServer() {
        return new ErrorsCustomizer();
    }

    /** After the framework's own customizer, so the error page it installed is the one replaced. */
    private static final class ErrorsCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            factory.addConnectorCustomizers(connector -> connector.setAllowTrace(true));
            factory.addContextCustomizers(context -> {
                Container host = context.getParent();
                if (host instanceof StandardHost standardHost) {
                    for (Valve valve : standardHost.getPipeline().getValves()) {
                        if (valve instanceof ErrorReportValve) {
                            standardHost.getPipeline().removeValve(valve);
                        }
                    }
                    // The host creates its error page from this class name when it starts.
                    standardHost.setErrorReportValveClass(ApiErrorReportValve.class.getName());
                }
            });
        }
    }
}
