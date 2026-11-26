package org.apvgakasinay.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

/**
 * For a test class that changes log levels: puts them back when the class is done.
 *
 * Log levels belong to the whole JVM, not to one application context. A class that starts an
 * application with {@code logging.level.*} properties leaves those levels behind for every
 * class that runs after it, and which classes those are depends on the order of the run.
 * Every such class registers this extension, so no test sees levels another one chose.
 */
public final class RestoreLogLevels implements BeforeAllCallback, AfterAllCallback {

    /** The object Spring Boot leaves on the logger context once it has configured logging. */
    private static final String CONFIGURED_BY_BOOT = "org.springframework.boot.logging.LoggingSystem";

    private final Map<String, Level> before = new HashMap<>();
    private boolean configured;

    private static LoggerContext loggers() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        LoggerContext loggers = loggers();
        configured = loggers.getObject(CONFIGURED_BY_BOOT) != null;
        before.clear();
        for (Logger logger : loggers.getLoggerList()) {
            if (logger.getLevel() != null) {
                before.put(logger.getName(), logger.getLevel());
            }
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        for (Logger logger : loggers().getLoggerList()) {
            boolean root = Logger.ROOT_LOGGER_NAME.equals(logger.getName());
            if (configured) {
                // Exactly what was there: the levels of the application's own logging configuration.
                logger.setLevel(root && before.get(logger.getName()) == null ? Level.INFO : before.get(logger.getName()));
            } else {
                // This class was the first to start an application, so there is nothing earlier to
                // go back to: the normal level, and nothing set per logger.
                logger.setLevel(root ? Level.INFO : null);
            }
        }
    }
}
