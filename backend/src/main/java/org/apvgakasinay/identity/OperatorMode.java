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
        SpringApplication operator = new SpringApplication(OperatorConfiguration.class);
        operator.setWebApplicationType(WebApplicationType.NONE);
        operator.setBannerMode(Banner.Mode.OFF);
        try (ConfigurableApplicationContext context = operator.run()) {
            return context.getBean(OperatorCommands.class).run(command, OperatorCommands.terminal(), System.out);
        }
    }
}
