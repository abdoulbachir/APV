package org.apvgakasinay.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogsTest {

    /** A verbose line that names an exception class is not an error; a record at ERROR is. */
    @Test
    void anErrorIsARecordAtErrorNotAWordInAClassName() {
        String verbose = """
                2026-11-25T17:20:42.604-06:00 TRACE 666517 --- [apv-backend] [nio-1] o.s.security.web.FilterChainProxy        : Invoking ExceptionTranslationFilter (12/14)
                2026-11-25T17:20:42.605-06:00 DEBUG 666517 --- [apv-backend] [nio-1] o.s.w.s.m.m.a.ExceptionHandlerExceptionResolver : Using @ExceptionHandler ERROR Exception
                2026-11-25T17:20:42.606Z  WARN 666517 --- [apv-backend] [nio-1] o.a.c.Example : something ERROR something
                """;
        assertThat(Logs.errorRecords(verbose)).isEmpty();
        assertThat(Logs.records(verbose, "WARN")).hasSize(1);
        String failing = verbose + """
                2026-11-25T17:20:42.607-06:00 ERROR 666517 --- [apv-backend] [nio-1] o.a.shared.RequestIdFilter : Unexpected error
                java.lang.IllegalStateException: no
                \tat org.apvgakasinay.shared.RequestIdFilter.doFilterInternal(RequestIdFilter.java:1)
                \tat org.apache.catalina.Example.run(Example.java:1)
                """;
        assertThat(Logs.errorRecords(failing)).hasSize(1);
        assertThat(Logs.applicationStackFrames(failing)).hasSize(1);
    }
}
