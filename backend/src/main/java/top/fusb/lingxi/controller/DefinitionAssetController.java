package top.fusb.lingxi.controller;

import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.service.ModuleDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/definition-assets")
public class DefinitionAssetController {

    private static final Map<String, String> MODULE_DIRS = Map.of(
            "scenarios", "scenarios",
            "capabilities", "capabilities"
    );
    private final ModuleDefinitionService moduleDefinitionService;

    @GetMapping("/{kind}/{code}/icon")
    public ResponseEntity<Resource> icon(@PathVariable String kind, @PathVariable String code) {
        String moduleType = MODULE_DIRS.get(kind);
        if (moduleType == null || !code.matches("[A-Za-z0-9._-]+")) {
            return ResponseEntity.notFound().build();
        }
        ModuleDefinition definition = definitions(moduleType).stream()
                .filter(item -> code.equals(item.getCode()))
                .findFirst()
                .orElse(null);
        if (definition == null || definition.getIcon() == null || definition.getIcon().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Path moduleDir = definition.getModuleDirectory();
        if (moduleDir == null || !Files.isDirectory(moduleDir)) {
            return ResponseEntity.notFound().build();
        }
        Path icon = moduleDir.resolve(definition.getIcon()).normalize().toAbsolutePath().normalize();
        if (icon.startsWith(moduleDir) && Files.isRegularFile(icon)) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                    .contentType(contentType(icon.getFileName().toString()))
                    .body(new FileSystemResource(icon));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 按模块类型读取对应的 manifest 定义集合。
     *
     * @param moduleType 模块目录类型
     * @return 对应类型的模块定义
     */
    private List<ModuleDefinition> definitions(String moduleType) {
        return "scenarios".equals(moduleType)
                ? moduleDefinitionService.listInstalledScenarios()
                : moduleDefinitionService.listCapabilities();
    }

    private MediaType contentType(String fileName) {
        if (fileName.endsWith(".svg")) {
            return MediaType.valueOf("image/svg+xml");
        }
        if (fileName.endsWith(".webp")) {
            return MediaType.valueOf("image/webp");
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.IMAGE_PNG;
    }
}
