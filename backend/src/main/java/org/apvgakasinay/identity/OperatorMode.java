package org.apvgakasinay.identity;

import org.apvgakasinay.identity.internal.OperatorCommands;
import org.apvgakasinay.identity.internal.OperatorConfiguration;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs one operator command and returns the exit code. No web server is started: the command
 * is not reachable over the network because nothing that listens is ever created.
 */
public final class OperatorMode {

    private OperatorMode() {
    }

    public static int run(String command) throws Exception {
        return run(command, new Class<?>[0]);
    }

    /**
     * @param extensions configuration classes of other modules that contribute commands
     *                   ({@link OperatorCommandSet}); they are started in the same context, still without a web server
     */
    public static int run(String command, Class<?>... extensions) throws Exception {
        Class<?>[] sources = new Class<?>[extensions.length + 1];
        sources[0] = OperatorConfiguration.class;
        System.arraycopy(extensions, 0, sources, 1, extensions.length);
        SpringApplication operator = new SpringApplication(sources);
        operator.setWebApplicationType(WebApplicationType.NONE);
        operator.setBannerMode(Banner.Mode.OFF);
        // Start-up problems are reported by the message below, not by a framework stack trace.
        operator.setLogStartupInfo(false);
        ConfigurableApplicationContext started;
        try {
            started = operator.run();
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            // The same configuration rules as the server: without them nothing is administered.
            System.out.println("Refused to start: " + cause.getMessage());
            return 3;
        }
        try (ConfigurableApplicationContext context = started) {
            return context.getBean(OperatorCommands.class).run(command, OperatorCommands.terminal(), System.out);
        }
    }
}
