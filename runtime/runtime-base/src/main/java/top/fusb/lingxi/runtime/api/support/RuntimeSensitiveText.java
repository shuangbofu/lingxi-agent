package top.fusb.lingxi.runtime.api.support;

import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDelta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuntimeSensitiveText {

    public static final String REDACTED = "[REDACTED]";
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|token|secret|api[_-]?key|private[_-]?key|access[_-]?key|authorization|credential)\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern ASSIGNMENT_SECRET = Pattern.compile(
            "(?i)((?:password|passwd|pwd|token|secret|api[_-]?key|private[_-]?key|access[_-]?key|authorization|credential)\\s*[=:]\\s*)([^\\s,;\\\"'&]+)");
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b");
    private static final Pattern URL_CREDENTIAL = Pattern.compile("(://[^:/\\s]+:)([^@/\\s]+)(@)");
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");
    private static final Pattern LINGXI_VIEW_BLOCK = Pattern.compile(
            "(?s)```lingxi-view[\\t ]*\\R(.*?)\\R```");
    private static final Pattern LINKS_VIEW_TYPE = Pattern.compile(
            "(?i)\\\"type\\\"\\s*:\\s*\\\"links\\\"");
    private static final Pattern LINKS_VIEW_URL = Pattern.compile(
            "(?i)(\\\"(?:url|href)\\\"\\s*:\\s*\\\")((?:\\\\.|[^\\\"\\\\])*)(\\\")");
    private static final String PROTECTED_LINK_PREFIX = "__LINGXI_VIEW_LINK_";

    private RuntimeSensitiveText() {
    }

    public static String redact(String value, Collection<String> knownSecrets) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        List<String> protectedLinks = new ArrayList<>();
        String result = protectLinksViewUrls(value, protectedLinks);
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
        result = PRIVATE_KEY_BLOCK.matcher(result).replaceAll(REDACTED);
        for (int index = 0; index < protectedLinks.size(); index++) {
            result = result.replace(PROTECTED_LINK_PREFIX + index + "__", protectedLinks.get(index));
        }
        return result;
    }

    /**
     * 保护链接组件中的目标 URL，避免结果脱敏破坏有时效签名的可访问链接。
     *
     * @param value 原始运行时文本
     * @param protectedLinks 用于暂存受保护链接的列表
     * @return 将链接替换为内部占位符的文本
     */
    private static String protectLinksViewUrls(String value, List<String> protectedLinks) {
        Matcher blockMatcher = LINGXI_VIEW_BLOCK.matcher(value);
        StringBuffer output = new StringBuffer();
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            if (!LINKS_VIEW_TYPE.matcher(block).find()) {
                blockMatcher.appendReplacement(output, Matcher.quoteReplacement(blockMatcher.group()));
                continue;
            }
            Matcher urlMatcher = LINKS_VIEW_URL.matcher(block);
            StringBuffer protectedBlock = new StringBuffer();
            while (urlMatcher.find()) {
                String url = urlMatcher.group(2);
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    urlMatcher.appendReplacement(protectedBlock, Matcher.quoteReplacement(urlMatcher.group()));
                    continue;
                }
                String placeholder = PROTECTED_LINK_PREFIX + protectedLinks.size() + "__";
                protectedLinks.add(url);
                urlMatcher.appendReplacement(protectedBlock,
                        Matcher.quoteReplacement(urlMatcher.group(1) + placeholder + urlMatcher.group(3)));
            }
            urlMatcher.appendTail(protectedBlock);
            String protectedFence = blockMatcher.group().replace(block, protectedBlock.toString());
            blockMatcher.appendReplacement(output, Matcher.quoteReplacement(protectedFence));
        }
        blockMatcher.appendTail(output);
        return output.toString();
    }

    public static RuntimeEvent redact(RuntimeEvent event, Collection<String> knownSecrets) {
        if (event == null) {
            return null;
        }
        RuntimeEventPayload payload = event.payload();
        RuntimeEventPayload safePayload = payload == null ? null : new RuntimeEventPayload(
                payload.rawType(), payload.itemType(), payload.itemId(), payload.status(),
                redact(payload.command(), knownSecrets), payload.toolName(), payload.callId(),
                redact(payload.arguments(), knownSecrets), redact(payload.output(), knownSecrets),
                redact(payload.message(), knownSecrets), payload.exitCode(), payload.actionKey(),
                payload.actionInstanceId(), payload.actionLabel(), redact(payload.actionTarget(), knownSecrets),
                payload.transientEvent(), redactMetrics(payload.metrics(), knownSecrets),
                payload.actionGroupId(), payload.actionGroupSize(), payload.semantic(), payload.visibility(),
                payload.modelTimingMode(), payload.actionIcon());
        return new RuntimeEvent(event.type(), event.status(), redact(event.title(), knownSecrets),
                redact(event.detail(), knownSecrets), safePayload);
    }

    private static Map<String, String> redactMetrics(Map<String, String> metrics, Collection<String> knownSecrets) {
        if (metrics == null) {
            return null;
        }
        Map<String, String> safeMetrics = new LinkedHashMap<>();
        metrics.forEach((key, value) -> safeMetrics.put(key, redact(value, knownSecrets)));
        return safeMetrics;
    }

    public static RuntimeMessageDelta redact(RuntimeMessageDelta delta, Collection<String> knownSecrets) {
        return delta == null ? null : new RuntimeMessageDelta(
                delta.messageId(), redact(delta.delta(), knownSecrets), delta.type());
    }

    public static RuntimeExecutionResult redact(RuntimeExecutionResult result, Collection<String> knownSecrets) {
        if (result == null) {
            return null;
        }
        return new RuntimeExecutionResult(result.exitCode(), redact(result.stdoutText(), knownSecrets),
                redact(result.stderrText(), knownSecrets), redact(result.resultText(), knownSecrets),
                result.session(), result.usage(), result.engineCompletedAt());
    }
}
