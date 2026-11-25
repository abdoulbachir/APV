package org.apvgakasinay.probe;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The scenarios with the embedded server's own sessions. Needs no database and no Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("memory")
class MemoryProbeTest extends ProbeScenarios {
}
