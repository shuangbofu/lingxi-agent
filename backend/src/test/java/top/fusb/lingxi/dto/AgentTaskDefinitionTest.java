package top.fusb.lingxi.dto;

import top.fusb.lingxi.definition.CapabilityActivationCondition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskDefinitionTest {

    @Test
    void activatesCapabilitiesFromExplicitAndDefaultParameterValues() {
        AgentTaskDefinition disabled = definition();
        TaskInputValue inputValue = new TaskInputValue();
        inputValue.setKey("interactionMode");
        inputValue.setValue("OFF");

        disabled.activateCapabilities(List.of(inputValue));

        assertThat(disabled.getCapabilities()).containsExactly("evidence");
        assertThat(disabled.getCapabilityCommands()).containsOnlyKeys("evidence");

        AgentTaskDefinition defaultEnabled = definition();
        defaultEnabled.activateCapabilities(List.of());

        assertThat(defaultEnabled.getCapabilities()).containsExactly("evidence", "interaction");
        assertThat(defaultEnabled.getCapabilityCommands()).containsKeys("evidence", "interaction");
    }

    private AgentTaskDefinition definition() {
        AgentTaskDefinition definition = new AgentTaskDefinition();
        definition.setCapabilities(new LinkedHashSet<>(List.of("evidence", "interaction")));
        Map<String, Set<String>> commands = new LinkedHashMap<>();
        commands.put("evidence", Set.of("READ"));
        commands.put("interaction", Set.of("ASK"));
        definition.setCapabilityCommands(commands);
        CapabilityActivationCondition condition = new CapabilityActivationCondition();
        condition.setParameter("interactionMode");
        condition.setValues(Set.of("NECESSARY", "CONFIRM"));
        definition.setCapabilityConditions(Map.of("interaction", condition));
        definition.setParameters(List.of(new AgentTaskDefinition.DefinitionParameter(
                "interactionMode", "交互模式", "select", false, null, "NECESSARY", true)));
        return definition;
    }
}
