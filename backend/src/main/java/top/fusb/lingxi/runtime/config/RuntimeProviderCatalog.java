package top.fusb.lingxi.runtime.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.kit.TextKit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 加载并校验平台维护的模型供应商能力目录。
 */
@Component
public class RuntimeProviderCatalog {

    private static final String CATALOG_PATH = "runtime-provider-types.json";
    private final List<RuntimeProviderType> definitions;
    private final Map<String, RuntimeProviderType> definitionsByValue;

    /**
     * 从 classpath JSON 文件加载供应商能力定义。
     *
     * @param objectMapper 平台统一 JSON 解析器
     * @throws IllegalStateException 目录缺失或内容不合法时抛出
     */
    public RuntimeProviderCatalog(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(CATALOG_PATH).getInputStream()) {
            List<RuntimeProviderType> loaded = objectMapper.readValue(
                    input, new TypeReference<List<RuntimeProviderType>>() { });
            if (loaded == null || loaded.isEmpty()) {
                throw new IllegalStateException("供应商类型目录不能为空");
            }
            LinkedHashMap<String, RuntimeProviderType> indexed = new LinkedHashMap<>();
            for (RuntimeProviderType definition : loaded) {
                validate(definition);
                String key = definition.value().trim().toUpperCase(Locale.ROOT);
                if (indexed.putIfAbsent(key, definition) != null) {
                    throw new IllegalStateException("供应商类型重复：" + definition.value());
                }
            }
            definitions = List.copyOf(loaded);
            definitionsByValue = Map.copyOf(indexed);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载供应商类型目录：" + CATALOG_PATH, exception);
        }
    }

    /**
     * 返回全部平台供应商能力定义。
     *
     * @return 按 JSON 文件顺序排列的供应商定义
     */
    public List<RuntimeProviderType> definitions() {
        return definitions;
    }

    /**
     * 按类型键查找供应商定义。
     *
     * @param value 供应商类型键
     * @return 匹配的供应商定义
     */
    public Optional<RuntimeProviderType> find(String value) {
        String normalized = TextKit.blankToNull(value);
        return normalized == null
                ? Optional.empty()
                : Optional.ofNullable(definitionsByValue.get(normalized.toUpperCase(Locale.ROOT)));
    }

    private void validate(RuntimeProviderType definition) {
        if (definition == null || TextKit.blankToNull(definition.value()) == null
                || TextKit.blankToNull(definition.label()) == null
                || TextKit.blankToNull(definition.icon()) == null
                || TextKit.blankToNull(definition.defaultBaseUrl()) == null) {
            throw new IllegalStateException("供应商类型目录存在不完整定义");
        }
        if (definition.supportedProtocols() == null || definition.supportedProtocols().isEmpty()
                || definition.defaultProtocol() == null
                || !definition.supportedProtocols().contains(definition.defaultProtocol())) {
            throw new IllegalStateException("供应商默认协议必须包含在允许协议中：" + definition.value());
        }
        if (definition.reasoningEffortOptions() == null) {
            throw new IllegalStateException("供应商推理强度定义不能为空：" + definition.value());
        }
        String thinkingFieldName = TextKit.blankToNull(definition.thinkingFieldName());
        if (thinkingFieldName != null && !thinkingFieldName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("供应商思考字段名无效：" + definition.value());
        }
        long disableOptions = definition.reasoningEffortOptions().stream()
                .filter(java.util.Objects::nonNull)
                .filter(RuntimeReasoningEffortOption::isDisablesReasoning)
                .count();
        if (disableOptions > 1) {
            throw new IllegalStateException("供应商只能声明一个关闭思考选项：" + definition.value());
        }
    }
}
