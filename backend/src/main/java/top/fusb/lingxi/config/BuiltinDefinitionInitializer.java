package top.fusb.lingxi.config;

import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.service.AgentCapabilityService;
import top.fusb.lingxi.service.AgentScenarioService;
import top.fusb.lingxi.service.ModuleDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class BuiltinDefinitionInitializer implements CommandLineRunner {

    private final ModuleDefinitionService moduleDefinitionService;
    private final AgentScenarioService agentScenarioService;
    private final AgentCapabilityService agentCapabilityService;

    @Override
    public void run(String... args) {
        log.info("Start synchronizing installed scenarios and Skills");
        for (ModuleDefinition definition : moduleDefinitionService.listInstalledScenarios()) {
            agentScenarioService.syncDefinition(definition);
        }
        for (ModuleDefinition definition : moduleDefinitionService.listCapabilities()) {
            agentCapabilityService.syncDefinition(definition);
        }
        log.info("Installed scenarios and Skills synchronized");
    }
}
