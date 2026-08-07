package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginDemoDefinitionServiceTest {

    @Test
    void shouldLoadLoginDemoFromJsonResource() {
        var definition = new LoginDemoDefinitionService(new ObjectMapper()).get();

        assertThat(definition.getQuestion()).isNotBlank();
        assertThat(definition.getSteps()).isNotEmpty().doesNotContainNull();
        assertThat(definition.getSteps()).allSatisfy(step -> {
            assertThat(step.getLabel()).isNotBlank();
            assertThat(step.getIcon()).isNotNull();
        });
        assertThat(definition.getTimings().getStepDelayMs()).isPositive();
    }
}
