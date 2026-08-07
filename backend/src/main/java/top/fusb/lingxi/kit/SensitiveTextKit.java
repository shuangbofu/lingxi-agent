package top.fusb.lingxi.kit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class SensitiveTextKit {

    public static final String REDACTED = "[REDACTED]";
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|token|secret|apikey|privatekey|accesskey|authorization|credential).*");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|token|secret|api[_-]?key|private[_-]?key|access[_-]?key|authorization|credential)\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern ASSIGNMENT_SECRET = Pattern.compile(
            "(?i)((?:password|passwd|pwd|token|secret|api[_-]?key|private[_-]?key|access[_-]?key|authorization|credential)\\s*[=:]\\s*)([^\\s,;\\\"'&]+)");
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b");
    private static final Pattern URL_CREDENTIAL = Pattern.compile("(://[^:/\\s]+:)([^@/\\s]+)(@)");
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");

    private SensitiveTextKit() {
    }

    /**
     * 脱敏文本中的已知密钥和常见凭证格式。
     *
     * @param value 原始文本
     * @param knownSecrets 当前任务已知的敏感明文
     * @return 脱敏后的文本
     */
    public static String redact(String value, Collection<String> knownSecrets) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = value;
        if (knownSecrets != null) {
            List<String> secrets = knownSecrets.stream()
                    .filter(secret -> secret != null && secret.length() >= 8)
                    .distinct()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
            for (String secret : secrets) {
                result = result.replace(secret, REDACTED);
            }
        }
        result = JSON_SECRET.matcher(result).replaceAll("$1" + REDACTED + "$3");
        result = BEARER_SECRET.matcher(result).replaceAll("$1" + REDACTED);
        result = ASSIGNMENT_SECRET.matcher(result).replaceAll("$1" + REDACTED);
        result = OPENAI_KEY.matcher(result).replaceAll(REDACTED);
        result = URL_CREDENTIAL.matcher(result).replaceAll("$1" + REDACTED + "$3");
        return PRIVATE_KEY_BLOCK.matcher(result).replaceAll(REDACTED);
    }

    /**
     * 从结构化配置中提取由敏感字段承载的明文值。
     *
     * @param config 能力服务配置
     * @return 可用于任务输出脱敏的敏感值列表
     */
    public static List<String> sensitiveValues(Map<String, Object> config) {
        List<String> values = new ArrayList<>();
        collect(config, false, values);
        return values;
    }

    private static void collect(Object value, boolean sensitive, List<String> result) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> collect(child, sensitive || isSensitiveKey(String.valueOf(key)), result));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(child -> collect(child, sensitive, result));
            return;
        }
        if (sensitive && value != null) {
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
    }

    private static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEY.matcher(key == null ? "" : key.replaceAll("[^A-Za-z]", "")).matches();
    }
}
