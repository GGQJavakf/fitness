package com.aifitness.assistant.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

class HealthEndpointConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void exposesOnlyTheSanitizedHealthEndpoint() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty(
                    "management.endpoints.web.exposure.include")).isEqualTo("health");
            assertThat(context.getEnvironment().getProperty(
                    "management.endpoint.health.show-details")).isEqualTo("never");
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8080");
        });
    }
}
