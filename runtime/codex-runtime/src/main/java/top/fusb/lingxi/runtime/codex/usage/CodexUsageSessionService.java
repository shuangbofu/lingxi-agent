package top.fusb.lingxi.runtime.codex.usage;

import com.fasterxml.jackson.databind.JsonNode;
import top.fusb.lingxi.runtime.codex.cli.CodexCliEvent;
import top.fusb.lingxi.runtime.codex.cli.CodexEventParser;
import top.fusb.lingxi.runtime.codex.home.CodexHomeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class CodexUsageSessionService {

    private final CodexHomeService codexHomeService;
    private final CodexEventParser codexEventParser;

    /**
     * 读取单个任务隔离 CODEX_HOME 中最新 session 的累计 token 和模型请求次数。
     *
     * @param taskId 任务 ID
     * @return session 中最后一条累计 token 用量，requestCount 为有效模型用量快照数量
     */
    public Optional<CodexUsageSnapshot> readTaskUsage(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        Optional<CodexUsageSnapshot> conversationUsage = readLatestSessionUsage(codexHomeService.conversationHome(taskId));
        return conversationUsage.isPresent() ? conversationUsage : readLatestSessionUsage(codexHomeService.taskHome(taskId));
    }

    /**
     * 按通用运行时任务标识和时间窗读取 Codex session 用量。
     *
     * @param taskId 当前任务 ID
     * @param conversationRootTaskId 会话根任务 ID，可为空
     * @param startedAt 本轮开始时间，可为空
     * @param endedAt 本轮结束时间，可为空
     * @return 本轮 Token 用量
     */
    public Optional<CodexUsageSnapshot> readTaskUsage(Long taskId,
                                                      Long conversationRootTaskId,
                                                      LocalDateTime startedAt,
                                                      LocalDateTime endedAt) {
        Path codexHome = conversationRootTaskId == null
                ? codexHomeService.taskHome(taskId)
                : codexHomeService.conversationHome(conversationRootTaskId);
        Optional<CodexUsageSnapshot> usage = readSessionUsage(codexHome, startedAt, endedAt);
        return usage.isPresent() ? usage : readTaskUsage(taskId);
    }

    /**
     * 读取指定 CODEX_HOME 中最新 session 的累计 token 和模型请求次数。
     *
     * @param codexHome CODEX_HOME 目录
     * @return session 中最后一条累计 token 用量，requestCount 为有效模型用量快照数量
     */
    public Optional<CodexUsageSnapshot> readLatestSessionUsage(Path codexHome) {
        Path sessionsDir = codexHome.resolve("sessions");
        if (!Files.exists(sessionsDir)) {
            return Optional.empty();
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(sessionsDir)) {
            Optional<Path> latestFile = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparing(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (Exception e) {
                            return 0L;
                        }
                    }));
            if (latestFile.isEmpty()) {
                return Optional.empty();
            }
            return readSessionUsage(latestFile.get());
        } catch (Exception e) {
            log.info("读取 session token 用量失败 codexHome={} message={}", codexHome, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 读取指定 CODEX_HOME 在给定时间窗内的模型请求用量。
     *
     * @param codexHome CODEX_HOME 目录
     * @param startedAt 起始时间，可为空
     * @param endedAt 结束时间，可为空
     * @return 时间窗内 last_token_usage 汇总后的用量
     */
    public Optional<CodexUsageSnapshot> readSessionUsage(Path codexHome, LocalDateTime startedAt, LocalDateTime endedAt) {
        Path sessionsDir = codexHome.resolve("sessions");
        if (!Files.exists(sessionsDir)) {
            return Optional.empty();
        }
        CodexUsageSnapshot total = new CodexUsageSnapshot();
        AtomicLong requestCount = new AtomicLong(0);
        try (java.util.stream.Stream<Path> stream = Files.walk(sessionsDir)) {
            for (Path sessionFile : stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(this::lastModifiedMillis))
                    .toList()) {
                accumulateSessionUsage(sessionFile, startedAt, endedAt, total, requestCount);
            }
            total.setRequestCount(requestCount.get());
            return requestCount.get() == 0 ? Optional.empty() : Optional.of(total);
        } catch (Exception e) {
            log.info("按时间窗读取 session token 用量失败 codexHome={} message={}", codexHome, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<CodexUsageSnapshot> readSessionUsage(Path sessionFile) {
        try {
            CodexUsageSnapshot latestUsage = null;
            AtomicLong requestCount = new AtomicLong(0);
            AtomicLong previousTotalTokens = new AtomicLong(-1);
            for (String line : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                CodexUsageSnapshot usage = parseTokenUsage(line);
                if (usage != null) {
                    applyRequestCount(usage, requestCount, previousTotalTokens);
                    latestUsage = usage;
                }
            }
            if (latestUsage != null) {
                latestUsage.setRequestCount(requestCount.get());
            }
            return Optional.ofNullable(latestUsage);
        } catch (Exception e) {
            log.info("读取 session token 文件失败 path={} message={}", sessionFile, e.getMessage());
            return Optional.empty();
        }
    }

    private void accumulateSessionUsage(Path sessionFile,
                                        LocalDateTime startedAt,
                                        LocalDateTime endedAt,
                                        CodexUsageSnapshot total,
                                        AtomicLong requestCount) {
        try {
            AtomicLong previousTotalTokens = new AtomicLong(-1);
            for (String line : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                CodexUsageSnapshot usage = parseTokenUsage(line);
                if (!inRange(usage, startedAt, endedAt)) {
                    continue;
                }
                if (applyRequestCount(usage, requestCount, previousTotalTokens)) {
                    addLastUsage(total, usage);
                }
            }
        } catch (Exception e) {
            log.info("累计 session token 文件失败 path={} message={}", sessionFile, e.getMessage());
        }
    }

    /**
     * 解析 Codex CLI token_count 事件。
     *
     * @param line jsonl 单行内容
     * @return token 用量快照，非 token_count 事件返回空
     */
    public CodexUsageSnapshot parseTokenUsage(String line) {
        try {
            CodexCliEvent root = codexEventParser.parse(line);
            CodexCliEvent event = codexEventParser.unwrap(root);
            if (!"token_count".equals(event.getType()) || event.getInfo() == null) {
                return null;
            }
            JsonNode usage = event.getInfo().path("total_token_usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return null;
            }
            JsonNode lastUsage = event.getInfo().path("last_token_usage");
            CodexUsageSnapshot snapshot = new CodexUsageSnapshot();
            snapshot.setEventTimestamp(root.getTimestamp());
            snapshot.setInputTokens(longValue(usage, "input_tokens"));
            snapshot.setCachedInputTokens(longValue(usage, "cached_input_tokens"));
            snapshot.setCacheCreationInputTokens(firstLongValue(usage, "cache_creation_input_tokens", "cache_creation_tokens"));
            snapshot.setOutputTokens(longValue(usage, "output_tokens"));
            snapshot.setReasoningOutputTokens(longValue(usage, "reasoning_output_tokens"));
            snapshot.setTotalTokens(longValue(usage, "total_tokens"));
            snapshot.setLastInputTokens(longValue(lastUsage, "input_tokens"));
            snapshot.setLastCachedInputTokens(longValue(lastUsage, "cached_input_tokens"));
            snapshot.setLastCacheCreationInputTokens(firstLongValue(lastUsage, "cache_creation_input_tokens", "cache_creation_tokens"));
            snapshot.setLastOutputTokens(longValue(lastUsage, "output_tokens"));
            snapshot.setLastReasoningOutputTokens(longValue(lastUsage, "reasoning_output_tokens"));
            snapshot.setLastTotalTokens(longValue(lastUsage, "total_tokens"));
            snapshot.setModelContextWindow(longValue(event.getInfo(), "model_context_window"));
            return snapshot.getTotalTokens() == null ? null : snapshot;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据累计 token 是否推进和 last_token_usage 是否有效计算模型请求次数。
     *
     * @param usage 当前 token 用量快照
     * @param requestCount 已统计的模型请求次数
     * @param previousTotalTokens 上一个累计 total_tokens
     * @return 当前快照是否代表一次有效模型请求
     */
    public boolean applyRequestCount(CodexUsageSnapshot usage, AtomicLong requestCount, AtomicLong previousTotalTokens) {
        if (usage == null || usage.getTotalTokens() == null || safe(usage.getLastTotalTokens()) <= 0) {
            return false;
        }
        long totalTokens = usage.getTotalTokens();
        long previous = previousTotalTokens.get();
        if (totalTokens <= previous) {
            return false;
        }
        previousTotalTokens.set(totalTokens);
        usage.setRequestCount(requestCount.incrementAndGet());
        return true;
    }

    /**
     * 将当前请求的 last_token_usage 累加到运行中任务用量。
     *
     * @param total 当前任务已累计用量
     * @param usage 当前 token_count 事件
     * @return 累加后的任务用量
     */
    public CodexUsageSnapshot addLastUsage(CodexUsageSnapshot total, CodexUsageSnapshot usage) {
        CodexUsageSnapshot target = total == null ? new CodexUsageSnapshot() : total;
        target.setInputTokens(safe(target.getInputTokens()) + safe(usage.getLastInputTokens()));
        target.setCachedInputTokens(safe(target.getCachedInputTokens()) + safe(usage.getLastCachedInputTokens()));
        target.setCacheCreationInputTokens(safe(target.getCacheCreationInputTokens()) + safe(usage.getLastCacheCreationInputTokens()));
        target.setOutputTokens(safe(target.getOutputTokens()) + safe(usage.getLastOutputTokens()));
        target.setReasoningOutputTokens(safe(target.getReasoningOutputTokens()) + safe(usage.getLastReasoningOutputTokens()));
        target.setTotalTokens(safe(target.getTotalTokens()) + safe(usage.getLastTotalTokens()));
        target.setRequestCount(safe(target.getRequestCount()) + 1);
        target.setLastInputTokens(usage.getLastInputTokens());
        target.setLastCachedInputTokens(usage.getLastCachedInputTokens());
        target.setLastCacheCreationInputTokens(usage.getLastCacheCreationInputTokens());
        target.setLastOutputTokens(usage.getLastOutputTokens());
        target.setLastReasoningOutputTokens(usage.getLastReasoningOutputTokens());
        target.setLastTotalTokens(usage.getLastTotalTokens());
        return target;
    }

    private Long firstLongValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Long value = longValue(node, fieldName);
            if (value != null) {
                return value;
            }
        }
        return 0L;
    }

    private boolean inRange(CodexUsageSnapshot usage, LocalDateTime startedAt, LocalDateTime endedAt) {
        if (usage == null) {
            return false;
        }
        LocalDateTime eventTime = parseEventTime(usage.getEventTimestamp());
        if (eventTime == null || startedAt == null) {
            return true;
        }
        if (eventTime.isBefore(startedAt.minusSeconds(2))) {
            return false;
        }
        return endedAt == null || !eventTime.isAfter(endedAt.plusSeconds(2));
    }

    private LocalDateTime parseEventTime(String value) {
        try {
            return value == null ? null : LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private Long longValue(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        return value.isNumber() ? value.asLong() : null;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

}
