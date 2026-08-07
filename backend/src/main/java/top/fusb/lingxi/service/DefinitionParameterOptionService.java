package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.definition.ModuleParameterDefinition;
import top.fusb.lingxi.definition.ParameterOptionSourceDefinition;
import top.fusb.lingxi.dto.DefinitionParameterOption;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefinitionParameterOptionService {

    private static final int MAX_OPTIONS = 500;
    private final ModuleDefinitionService moduleDefinitionService;
    private final CapabilityConfigService capabilityConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * 读取内置场景参数声明的远程选项；没有远程选项源时返回静态选项。
     *
     * @param scenarioCode 场景编码
     * @param parameterKey 参数编码
     * @return 可供前端 Select 使用的标签和值
     * @throws BizException 场景、参数、能力配置或远程响应不正确时抛出
     */
    public List<DefinitionParameterOption> options(String scenarioCode, String parameterKey) {
        ModuleDefinition scenario = moduleDefinitionService.listInstalledScenarios().stream()
                .filter(definition -> scenarioCode.equals(definition.getCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "场景定义不存在：" + scenarioCode));
        ModuleParameterDefinition parameter = (scenario.getParameters() == null
                ? List.<ModuleParameterDefinition>of() : scenario.getParameters()).stream()
                .filter(item -> parameterKey.equals(item.getKey()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "场景参数不存在：" + parameterKey));
        if (parameter.getOptionSource() == null) {
            return parameter.getOptions() == null ? List.of() : parameter.getOptions();
        }
        return remoteOptions(parameter.getOptionSource());
    }

    private List<DefinitionParameterOption> remoteOptions(ParameterOptionSourceDefinition source) {
        String capabilityCode = required(source.getCapabilityCode(), "动态选项源缺少 capabilityCode");
        String path = required(source.getPath(), "动态选项源缺少 path");
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "动态选项源 path 必须是站内绝对路径");
        }
        Map<String, Object> config = capabilityConfigService.enabledConfigData(capabilityCode);
        String baseUrl = required(stringValue(config.get(source.getBaseUrlConfigKey())), "动态选项源缺少服务地址");
        String token = TextKit.blankToNull(stringValue(config.get(source.getTokenConfigKey())));
        URI uri;
        try {
            uri = URI.create(baseUrl.replaceAll("/+$", "") + path);
        } catch (Exception exception) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "动态选项源服务地址无效");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "动态选项源只支持 HTTP 服务");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                        "动态选项源请求失败，HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root.isObject() && root.has("code") && !"SUCCESS".equals(root.path("code").asText())) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                        "动态选项源返回失败：" + root.path("message").asText("未知错误"));
            }
            JsonNode items = root.isObject() && root.has("data") ? root.path("data") : root;
            if (!items.isArray()) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "动态选项源返回的 data 不是数组");
            }
            String labelField = required(source.getLabelField(), "动态选项源缺少 labelField");
            String valueField = required(source.getValueField(), "动态选项源缺少 valueField");
            List<DefinitionParameterOption> result = new ArrayList<>();
            for (JsonNode item : items) {
                String label = TextKit.blankToNull(item.path(labelField).asText());
                String value = TextKit.blankToNull(item.path(valueField).asText());
                if (label != null && value != null) {
                    DefinitionParameterOption option = new DefinitionParameterOption();
                    option.setLabel(label);
                    option.setValue(value);
                    result.add(option);
                }
                if (result.size() >= MAX_OPTIONS) {
                    break;
                }
            }
            return result;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            log.info("读取动态参数选项失败 capability={} path={} message={}", capabilityCode, path, exception.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "读取动态参数选项失败：" + exception.getMessage());
        }
    }

    private String required(String value, String message) {
        String normalized = TextKit.blankToNull(value);
        if (normalized == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, message);
        }
        return normalized;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
