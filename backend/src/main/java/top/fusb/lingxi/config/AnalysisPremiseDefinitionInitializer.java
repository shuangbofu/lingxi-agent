package top.fusb.lingxi.config;

import top.fusb.lingxi.definition.AnalysisPremiseDefinition;
import top.fusb.lingxi.service.AnalysisPremiseService;
import top.fusb.lingxi.service.ModuleDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(25)
@RequiredArgsConstructor
public class AnalysisPremiseDefinitionInitializer implements CommandLineRunner {

    private final ModuleDefinitionService moduleDefinitionService;
    private final AnalysisPremiseService analysisPremiseService;

    @Override
    public void run(String... args) {
        log.info("开始初始化定义目录中的分析情境");
        for (AnalysisPremiseDefinition definition : moduleDefinitionService.listPremises()) {
            analysisPremiseService.seedBuiltin(
                    definition.getCode(),
                    definition.getName(),
                    definition.getDescription(),
                    definition.getContextValues(),
                    definition.getPromptText(),
                    definition.getSortOrder() == null ? 0 : definition.getSortOrder()
            );
        }
        log.info("分析情境定义初始化完成");
    }
}
