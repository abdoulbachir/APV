package org.apvgakasinay.fixtures;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In the dev profile only: replaces the database content with the fixture file named by
 * {@code apv.fixtures.path} every time the application starts. With no path, nothing is loaded.
 */
@Component
@Profile("dev")
class DevFixtureLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevFixtureLoader.class);

    private final FixtureImporter importer;
    private final String path;

    DevFixtureLoader(FixtureImporter importer, @Value("${apv.fixtures.path:}") String path) {
        this.importer = importer;
        this.path = path;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (path.isBlank()) {
            log.warn("Profile dev is active but apv.fixtures.path is empty: no fixture loaded.");
            return;
        }
        Path file = Path.of(path).toAbsolutePath().normalize();
        importer.replaceAllData(file);
        log.warn("DEVELOPMENT DATA: replaced all data with fictional fixture {}. The clock is pinned to the fixture's clock.", file);
    }
}
