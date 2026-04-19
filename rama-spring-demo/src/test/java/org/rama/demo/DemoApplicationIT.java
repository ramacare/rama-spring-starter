package org.rama.demo;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("h2")
class DemoApplicationIT {

    @Test
    void contextLoads_whenStartedOnH2_shouldBootWithoutError() {
        // Intentionally empty — success is the absence of bean wiring failures.
    }
}
