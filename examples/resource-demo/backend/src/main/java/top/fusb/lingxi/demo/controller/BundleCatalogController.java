package top.fusb.lingxi.demo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.fusb.lingxi.demo.config.DemoProperties;
import top.fusb.lingxi.demo.web.BundleCatalogModels.BundleCapability;
import top.fusb.lingxi.demo.web.BundleCatalogModels.BundleCatalog;
import top.fusb.lingxi.demo.web.BundleCatalogModels.BundleConfiguration;
import top.fusb.lingxi.demo.web.BundleCatalogModels.ServiceConnection;
import top.fusb.lingxi.demo.web.BusinessException;
import top.fusb.lingxi.demo.web.ErrorCode;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/setup")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BundleCatalogController {

    private static final List<String> SCENARIO_CODES = List.of(
            "business-question",
            "code-analysis",
            "code-change",
            "code-review",
            "data-change",
            "document-understanding",
            "log-analysis",
            "project-relation-scan",
            "sql-query",
            "test-case-generation"
    );
    private static final Map<String, String> PACKAGES = Map.of(
            "project-hub", "demo-packages/project-hub.zip",
            "wiki-ingest", "demo-packages/wiki-ingest.zip",
            "http-log-read", "demo-packages/http-log-read.zip"
    );

    private final DemoProperties demoProperties;
    private final ObjectMapper objectMapper;

    /**
     * 返回 Demo 页面可选择的公开能力、接入配置和场景清单。
     *
     * @return 能力安装包、服务接入配置和外置场景完整定义
     * @throws BusinessException 场景资源无法读取或格式错误时抛出
     */
    @GetMapping("/catalog")
    public BundleCatalog catalog() {
        String baseUrl = demoProperties.getPublicBaseUrl().replaceAll("/+$", "");
        ServiceConnection connection = new ServiceConnection(baseUrl, demoProperties.getAccessToken());
        return new BundleCatalog(
                1,
                "Lingxi 示例资源套件",
                "1.0.0",
                "项目、Markdown 文档和日志资源的公开接入案例",
                List.of(
                        capability("project-hub", "项目管理上下文", "读取项目、环境、仓库和资源配置", baseUrl),
                        capability("wiki-ingest", "文档证据读取", "检索和读取示例服务中的 Markdown 文档", baseUrl),
                        capability("http-log-read", "HTTP 日志读取", "读取环境对应的示例日志资源", baseUrl)
                ),
                List.of(
                        new BundleConfiguration("project-hub", "示例项目服务", "项目、环境与资源配置接口", connection),
                        new BundleConfiguration("wiki-ingest", "示例文档服务", "Markdown 检索与原文接口", connection)
                ),
                SCENARIO_CODES.stream().map(code -> scenario(code, baseUrl)).toList()
        );
    }

    /**
     * 下载构建时生成并随 Demo 发布的白名单能力包。
     *
     * @param code 能力编码
     * @return ZIP 能力安装包
     * @throws BusinessException 能力编码不在示例套件中或构建产物缺失时抛出
     */
    @GetMapping("/packages/{code}.zip")
    public ResponseEntity<Resource> packageFile(@PathVariable String code) {
        String resourcePath = PACKAGES.get(code);
        if (resourcePath == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "示例能力包不存在");
        }
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "示例能力包尚未生成");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(code + ".zip").build());
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    /**
     * 下载一个可由 Lingxi 正式场景安装接口处理的独立场景包。
     *
     * @param code 场景编码
     * @return ZIP 场景安装包
     * @throws BusinessException 场景编码不在公开目录或资源缺失时抛出
     */
    @GetMapping("/scenario-packages/{code}.zip")
    public ResponseEntity<byte[]> scenarioPackage(@PathVariable String code) {
        requireScenarioCode(code);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            addScenarioResource(zip, code, "manifest.json");
            addScenarioResource(zip, code, "prompt.md");
            ClassPathResource icon = scenarioResource(code, "icon.svg");
            if (icon.exists()) {
                addResource(zip, code + "/icon.svg", icon);
            }
            zip.finish();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.setContentDisposition(ContentDisposition.attachment().filename(code + ".zip").build());
            return ResponseEntity.ok().headers(headers).body(output.toByteArray());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "示例场景包无法生成：" + code);
        }
    }

    /**
     * 返回场景 manifest 声明的 SVG 图标。
     *
     * @param code 场景编码
     * @return 场景图标
     * @throws BusinessException 场景或图标不存在时抛出
     */
    @GetMapping("/scenario-icons/{code}")
    public ResponseEntity<Resource> scenarioIcon(@PathVariable String code) {
        requireScenarioCode(code);
        ClassPathResource icon = scenarioResource(code, "icon.svg");
        if (!icon.exists()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "示例场景图标不存在");
        }
        return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml")).body(icon);
    }

    /**
     * 返回公开能力的 SVG 图标。
     *
     * @param code 能力编码
     * @return 能力图标
     * @throws BusinessException 能力或图标不存在时抛出
     */
    @GetMapping("/capability-icons/{code}")
    public ResponseEntity<Resource> capabilityIcon(@PathVariable String code) {
        if (!PACKAGES.containsKey(code)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "示例能力图标不存在");
        }
        ClassPathResource icon = new ClassPathResource("demo-capability-icons/" + code + ".svg");
        if (!icon.exists()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "示例能力图标不存在");
        }
        return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml")).body(icon);
    }

    private BundleCapability capability(String code, String name, String description, String baseUrl) {
        return new BundleCapability(code, name, description,
                baseUrl + "/setup/packages/" + code + ".zip",
                baseUrl + "/setup/capability-icons/" + code);
    }

    private JsonNode scenario(String code, String baseUrl) {
        try {
            ClassPathResource manifestResource = scenarioResource(code, "manifest.json");
            ObjectNode manifest = (ObjectNode) objectMapper.readTree(manifestResource.getInputStream());
            manifest.put("packageUrl", baseUrl + "/setup/scenario-packages/" + code + ".zip");
            if (scenarioResource(code, "icon.svg").exists()) {
                manifest.put("iconUrl", baseUrl + "/setup/scenario-icons/" + code);
            }
            return manifest;
        } catch (IOException | ClassCastException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "示例场景定义无法读取：" + code);
        }
    }

    private void requireScenarioCode(String code) {
        if (!SCENARIO_CODES.contains(code)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "示例场景不存在");
        }
    }

    private ClassPathResource scenarioResource(String code, String fileName) {
        return new ClassPathResource("bundle-scenarios/" + code + "/" + fileName);
    }

    private void addScenarioResource(ZipOutputStream zip, String code, String fileName) throws IOException {
        ClassPathResource resource = scenarioResource(code, fileName);
        if (!resource.exists()) {
            throw new IOException("场景资源不存在：" + fileName);
        }
        addResource(zip, code + "/" + fileName, resource);
    }

    private void addResource(ZipOutputStream zip, String path, ClassPathResource resource) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        try (var input = resource.getInputStream()) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }
}
