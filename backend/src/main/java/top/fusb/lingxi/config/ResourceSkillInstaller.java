package top.fusb.lingxi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.fusb.lingxi.service.ResourceSkillInstallerService;

@Component
@Order(10)
@RequiredArgsConstructor
public class ResourceSkillInstaller implements CommandLineRunner {

    private final ResourceSkillInstallerService resourceSkillInstallerService;

    @Override
    public void run(String... args) {
        resourceSkillInstallerService.install();
    }
}
