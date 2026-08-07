package top.fusb.lingxi.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.dto.ModelEndpointCheckResponse;
import top.fusb.lingxi.kit.TextKit;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class ModelEndpointService {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ModelEndpointService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * 按 OpenAI 兼容协议检测独立模型服务并读取模型列表。
     *
     * @param apiKey 模型服务 API Key
     * @param baseUrl 模型服务 Base URL
     * @param configuredModels 当前已配置模型，用于检测结果提示和保留已有选项
     * @return 连通状态、提示和可选模型列表
     */
    public ModelEndpointCheckResponse check(String apiKey, String baseUrl, List<String> configuredModels) {
        ModelEndpointCheckResponse result = new ModelEndpointCheckResponse();
        String credential = TextKit.blankToNull(apiKey);
        if (credential == null) {
            result.setMessage("API Key 未配置");
            result.setModels(configuredModels(configuredModels));
            return result;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl(baseUrl)))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + credential)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.setMessage(responseErrorMessage(response.statusCode(), response.body()));
                result.setModels(configuredModels(configuredModels));
                return result;
            }
            List<String> models = extractModels(response.body());
            models.addAll(configuredModels(configuredModels));
            result.setSuccess(true);
            result.setModels(List.copyOf(new LinkedHashSet<>(models)));
            result.setMessage(models.isEmpty() ? "连接成功，但服务未返回模型列表" : "检测通过");
            return result;
        } catch (Exception exception) {
            result.setMessage(exception.getMessage());
            result.setModels(configuredModels(configuredModels));
            return result;
        }
    }

    /**
     * 将供应商的 HTTP 错误响应转换为可直接展示的文本提示。
     *
     * @param statusCode HTTP 状态码
     * @param responseBody 供应商返回的响应体
     * @return 不包含 JSON 包装结构的错误提示
     */
    private String responseErrorMessage(int statusCode, String responseBody) {
        if (statusCode == 401 || statusCode == 403) {
            return "认证失败，请检查 API Key";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = root.path("error").path("message").asText("").trim();
            if (message.isEmpty() && root.path("error").isTextual()) {
                message = root.path("error").asText("").trim();
            }
            if (message.isEmpty()) {
                message = root.path("message").asText("").trim();
            }
            if (!message.isEmpty()) {
                return limit(message, 1000);
            }
            return "模型服务请求失败（HTTP " + statusCode + "）";
        } catch (Exception ignored) {
            // 非 JSON 错误响应按纯文本展示。
        }
        String message = TextKit.blankToNull(responseBody);
        return message == null ? "模型服务返回 HTTP " + statusCode : limit(message, 1000);
    }

    private List<String> extractModels(String responseBody) throws Exception {
        JsonNode data = objectMapper.readTree(responseBody).path("data");
        List<String> models = new ArrayList<>();
        if (!data.isArray()) {
            return models;
        }
        for (JsonNode item : data) {
            String id = item.path("id").asText("").trim();
            if (!id.isBlank()) {
                models.add(id);
            }
        }
        return models;
    }

    private List<String> configuredModels(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(TextKit::blankToNull).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private String modelsUrl(String baseUrl) {
        String value = TextKit.blankToNull(baseUrl);
        value = value == null ? DEFAULT_BASE_URL : value;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/models")) {
            return value;
        }
        if (!value.endsWith("/v1") && !value.contains("/v1/")) {
            value += "/v1";
        }
        return value + "/models";
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
