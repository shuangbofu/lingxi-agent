package top.fusb.lingxi.runtime.codex.execution;

import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.support.RuntimeEnvironmentKit;
import top.fusb.lingxi.runtime.api.support.RuntimeSensitiveText;
import top.fusb.lingxi.runtime.codex.cli.CodexCliContent;
import top.fusb.lingxi.runtime.codex.cli.CodexCliEvent;
import top.fusb.lingxi.runtime.codex.cli.CodexEventParser;
import top.fusb.lingxi.runtime.codex.activity.CodexActivityEventCoordinator;
import top.fusb.lingxi.runtime.codex.maintenance.CodexCliManager;
import top.fusb.lingxi.runtime.codex.session.CodexSessionEventStream;
import top.fusb.lingxi.runtime.codex.config.CodexRuntimeProperties;
import top.fusb.lingxi.runtime.codex.config.CodexSkillAccessMode;
import top.fusb.lingxi.runtime.codex.cli.CodexSandboxMode;
import top.fusb.lingxi.runtime.codex.home.CodexHomeService;
import top.fusb.lingxi.runtime.codex.usage.CodexUsageSessionService;
import top.fusb.lingxi.runtime.codex.usage.CodexUsageSnapshot;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class CodexExecutor implements AutoCloseable {

    private static final int MAX_PROCESS_BUFFER_LENGTH = 200_000;
    private static final Pattern WORKING_DIRECTORY_VARIABLE = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)=(?:\"(/[^\"]*)\"|'(/[^']*)'|(/[^;\\s]*))\\s*;\\s*(.+)$");
    private static final Pattern CHANGE_DIRECTORY_PREFIX = Pattern.compile(
            "^cd\\s+(?:\"[^\"]+\"|'[^']+'|[^;&|\\s]+)\\s*(?:&&|;)\\s*(.+)$");

    private final CodexRuntimeProperties codexProperties;
    private final CodexCliManager codexCliManager;
    private final CodexHomeService codexHomeService;
    private final CodexUsageSessionService tokenUsageSessionService;
    private final ObjectMapper objectMapper;
    private final CodexEventParser codexEventParser;
    private final CodexSessionEventStream codexSessionEventStream;
    private final ConcurrentHashMap<Long, Process> runningProcesses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RuntimeActivity> runningCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RuntimeCommandDescriptor> commandDefinitions = new ConcurrentHashMap<>();
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    /**
     * 调用 Codex CLI 执行任务。
     *
     * @param request 通用 Agent Runtime 执行请求
     * @param listener 统一事件、消息增量和用量监听器
     * @return 进程执行结果
     * @throws IllegalStateException CLI 启动失败、超时或执行异常时抛出
     */
    public RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener) {
        Long taskId = numericId(request.executionId(), "executionId");
        Long conversationRootTaskId = nullableNumericId(request.conversationId(), "conversationId");
        String projectPath = request.workspace().executionRoot();
        RuntimeModelConfig modelConfig = request.modelConfig();
        String resumeSessionId = request.resumeSession() == null ? null : request.resumeSession().sessionId();
        long timeoutSeconds = request.timeoutSeconds();
        Consumer<RuntimeEvent> outputConsumer = listener::onEvent;
        Consumer<RuntimeUsage> tokenUsageConsumer = listener::onUsage;
        request.environment().commands().forEach(definition -> {
            String key = normalizeCommandSignature(definition.command());
            if (key != null) {
                commandDefinitions.put(key, definition);
            }
        });
        Process process = null;
        AtomicReference<CodexActivityEventCoordinator> activityCoordinatorRef = new AtomicReference<>();
        try {
            List<String> sensitiveValues = new ArrayList<>(request.environment().sensitiveValues());
            request.mcpServers().forEach(server -> {
                sensitiveValues.addAll(server.environment().values());
                sensitiveValues.addAll(server.headers().values());
            });
            Map<String, RuntimeEvent> capabilityFailures = new ConcurrentHashMap<>();
            Consumer<RuntimeEvent> secureOutputConsumer = event -> {
                RuntimeEvent redactedEvent = redactEvent(event, sensitiveValues);
                trackCapabilityFailure(redactedEvent, capabilityFailures);
                outputConsumer.accept(redactedEvent);
            };
            CodexActivityEventCoordinator activityCoordinator = new CodexActivityEventCoordinator(
                    request.executionId(), secureOutputConsumer);
            activityCoordinatorRef.set(activityCoordinator);
            activityCoordinator.preparing();
            Path codexHome = codexHomeService.prepare(
                    taskId, conversationRootTaskId, modelConfig, request.mcpServers(), request.environment().skills(),
                    request.environment().commandGuides(),
                    codexProperties.getSkillAccessMode());
            String runtimeInstructions = runtimeInstructions(request);
            if (codexProperties.getSkillAccessMode() == CodexSkillAccessMode.COMMAND_CATALOG) {
                runtimeInstructions = runtimeInstructions + "\n\n"
                        + commandCatalogInstructions(request.environment());
            } else if (!request.environment().commandGuides().isEmpty()) {
                runtimeInstructions = runtimeInstructions + "\n\n"
                        + platformCommandInstructions(request.environment());
            }
            String prompt = prompt(request.recovering() ? "" : runtimeInstructions,
                    request.prompt(), modelConfig.instructionPrompt(), request.finalResponseInstructions());
            CodexSessionEventStream.Cursor sessionEventCursor = codexSessionEventStream.open(
                    codexHome, request.mcpServers());
            secureOutputConsumer.accept(event(RuntimeEventType.SYSTEM, RuntimeEventStatus.INFO, "codex.config.loaded", null, null));
            activityCoordinator.processStarting();
            LocalDateTime executionStartedAt = LocalDateTime.now();
            String executable = codexCliManager.executablePath();
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.add("exec");
            applyRuntimeCliConfig(command, modelConfig, codexHome);
            command.add("--json");
            command.add("--skip-git-repo-check");
            command.add("--sandbox");
            command.add(codexProperties.getSandboxMode().getCliValue());
            command.add("-c");
            command.add("approval_policy=\"never\"");
            if (codexProperties.getSandboxMode() == CodexSandboxMode.WORKSPACE_WRITE) {
                command.add("-c");
                command.add("sandbox_workspace_write.network_access=" + codexProperties.isNetworkAccess());
            }
            command.add("--cd");
            command.add(projectPath);
            Path resultPath = Path.of(request.workspace().runtimeStateRoot())
                    .resolve("task-" + taskId + "-result.md").toAbsolutePath().normalize();
            Files.createDirectories(resultPath.getParent());
            command.add("--output-last-message");
            command.add(resultPath.toString());
            if (resumeSessionId != null && !resumeSessionId.isBlank()) {
                command.add("resume");
                command.add(resumeSessionId.trim());
                command.add("-");
            } else {
                command.add("-");
            }
            log.info("start codex taskId={} projectPath={} executable={} resume={}", taskId, projectPath, executable, resumeSessionId != null && !resumeSessionId.isBlank());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(Path.of(projectPath).toFile());
            applyRuntimeEnvironment(builder, modelConfig, codexHome);
            RuntimeEnvironmentKit.apply(builder, request.environment());
            log.info("执行引擎运行配置 taskId={} provider={} baseUrl={} model={} reasoningEffort={} apiKeySource={}",
                    taskId,
                    codexHomeService.providerName(modelConfig),
                    codexHomeService.codexBaseUrl(modelConfig),
                    modelConfig.model().trim(),
                    modelConfig.reasoningEffort(),
                    modelConfig.apiKeySource());
            process = builder.start();
            Process startedProcess = process;
            runningProcesses.put(taskId, startedProcess);
            writePrompt(startedProcess, prompt);

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            AtomicLong lastOutputAtNanos = new AtomicLong(System.nanoTime());
            AtomicLong tokenUsageCount = new AtomicLong(0);
            AtomicLong previousTotalTokens = new AtomicLong(-1);
            AtomicBoolean contextPressureReported = new AtomicBoolean(false);
            AtomicReference<CodexUsageSnapshot> currentTaskUsage = new AtomicReference<>(new CodexUsageSnapshot());
            AtomicReference<RuntimeEvent> pendingAgentMessage = new AtomicReference<>();
            CodexSessionEventStream.SessionObserver sessionObserver = new CodexSessionEventStream.SessionObserver() {
                @Override
                public void onEvent(RuntimeEvent event) {
                    if (event.type() == RuntimeEventType.METRIC) {
                        secureOutputConsumer.accept(event);
                        if ("runtime.model.request.started".equals(event.title())) {
                            activityCoordinator.modelRequestStarted();
                        }
                    } else {
                        activityCoordinator.accept(event);
                    }
                    lastOutputAtNanos.set(System.nanoTime());
                }

                @Override
                public void onSessionLine(String line) {
                    consumeTokenUsageLine(line, tokenUsageConsumer, tokenUsageCount, previousTotalTokens,
                            contextPressureReported, currentTaskUsage, activityCoordinator::accept);
                }

                @Override
                public void onToolBatchStarted(String callId, String modelRequestId) {
                    activityCoordinator.toolBatchStarted(callId, modelRequestId);
                }

                @Override
                public void onToolBatchCompleted(String callId) {
                    activityCoordinator.toolBatchCompleted(callId);
                }
            };
            Future<?> sessionEventReader = streamExecutor.submit(() -> codexSessionEventStream.watch(
                    sessionEventCursor, startedProcess::isAlive, sessionObserver));
            Future<?> stdoutReader = streamExecutor.submit(() -> readJsonStream(
                    startedProcess.getInputStream(), stdout, activityCoordinator, tokenUsageConsumer, lastOutputAtNanos,
                    tokenUsageCount, previousTotalTokens, contextPressureReported, currentTaskUsage, pendingAgentMessage,
                    () -> codexSessionEventStream.readAvailable(sessionEventCursor, sessionObserver)));
            Future<?> stderrReader = streamExecutor.submit(() -> readPlainStream(startedProcess.getErrorStream(), stderr));

            long startedAtNanos = System.nanoTime();
            long timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
            long heartbeatIntervalNanos = TimeUnit.SECONDS.toNanos(60);
            boolean finished = false;
            while (System.nanoTime() - startedAtNanos < timeoutNanos) {
                finished = startedProcess.waitFor(10, TimeUnit.SECONDS);
                if (finished) {
                    break;
                }
                long nowNanos = System.nanoTime();
                long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(nowNanos - startedAtNanos);
                if (nowNanos - lastOutputAtNanos.get() > heartbeatIntervalNanos) {
                    String heartbeat = "Execution engine is still running; no new process output. elapsedSeconds="
                            + elapsedSeconds;
                    activityCoordinator.accept(event(RuntimeEventType.SYSTEM, RuntimeEventStatus.INFO, heartbeat, null, null));
                    lastOutputAtNanos.set(nowNanos);
                }
                log.info("执行引擎运行中 taskId={} elapsedSeconds={}", taskId, elapsedSeconds);
            }
            if (!finished) {
                destroyProcessTree(startedProcess);
                throw new IllegalStateException("执行引擎执行超时");
            }
            stdoutReader.get(30, TimeUnit.SECONDS);
            stderrReader.get(30, TimeUnit.SECONDS);
            sessionEventReader.get(30, TimeUnit.SECONDS);
            activityCoordinator.finish();
            RuntimeSessionRef session = codexHomeService.latestSession(codexHome)
                    .map(value -> new RuntimeSessionRef(value.sessionId(), value.sessionPath()))
                    .orElse(null);
            CodexUsageSnapshot streamedUsage = currentTaskUsage.get();
            AtomicReference<RuntimeUsage> finalUsage = new AtomicReference<>();
            if (streamedUsage != null && streamedUsage.getRequestCount() != null && streamedUsage.getRequestCount() > 0) {
                finalUsage.set(toRuntimeUsage(streamedUsage));
            } else {
                tokenUsageSessionService.readSessionUsage(codexHome, executionStartedAt, LocalDateTime.now()).ifPresent(usage -> {
                    RuntimeUsage runtimeUsage = toRuntimeUsage(usage);
                    finalUsage.set(runtimeUsage);
                    try {
                        tokenUsageConsumer.accept(runtimeUsage);
                    } catch (Exception e) {
                        log.info("保存 session token 用量失败 taskId={} message={}", taskId, e.getMessage());
                    }
                });
            }
            int processExitCode = startedProcess.exitValue();
            String capabilityError = capabilityFailureMessage(capabilityFailures);
            int exitCode = processExitCode == 0 && capabilityError != null ? 1 : processExitCode;
            String stderrText = readableErrorText(stderr.toString());
            if (capabilityError != null) {
                stderrText = capabilityError + (stderrText.isBlank() ? "" : System.lineSeparator() + stderrText);
            }
            RuntimeExecutionResult result = new RuntimeExecutionResult(
                    exitCode,
                    RuntimeSensitiveText.redact(stdout.toString(), sensitiveValues),
                    RuntimeSensitiveText.redact(stderrText, sensitiveValues),
                    exitCode == 0 ? RuntimeSensitiveText.redact(readResultText(resultPath), sensitiveValues) : "",
                    session,
                    finalUsage.get(),
                    LocalDateTime.now()
            );
            log.info("执行引擎执行完成 taskId={} exitCode={}", taskId, exitCode);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(readableErrorText(e.getMessage()), e);
        } finally {
            CodexActivityEventCoordinator activityCoordinator = activityCoordinatorRef.get();
            if (activityCoordinator != null) {
                try {
                    activityCoordinator.finish();
                } catch (Exception e) {
                    log.info("清理 Codex 等待状态失败 taskId={} message={}", taskId, e.getMessage());
                }
            }
            runningProcesses.remove(taskId);
            if (process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
        }
    }

    /**
     * 记录能力命令的最终状态；同一能力后续重试成功时移除先前失败。
     *
     * @param event Codex CLI 解析后的运行事件
     * @param failures 当前仍未恢复的能力命令失败
     * @return 无返回值
     */
    private void trackCapabilityFailure(RuntimeEvent event, Map<String, RuntimeEvent> failures) {
        if (event == null || event.payload() == null || event.payload().actionKey() == null
                || !event.payload().actionKey().startsWith("capability:")) {
            return;
        }
        String actionKey = event.payload().actionKey();
        if (event.status() == RuntimeEventStatus.FAILED) {
            failures.put(actionKey, event);
        } else if (event.status() == RuntimeEventStatus.SUCCESS) {
            failures.remove(actionKey);
        }
    }

    /**
     * 汇总未恢复的能力命令错误，作为任务失败原因返回给平台。
     *
     * @param failures 当前仍未恢复的能力命令失败
     * @return 没有失败时返回 null，否则返回可展示的错误信息
     */
    private String capabilityFailureMessage(Map<String, RuntimeEvent> failures) {
        if (failures.isEmpty()) {
            return null;
        }
        RuntimeEvent failure = failures.values().iterator().next();
        String detail = failure.detail();
        if ((detail == null || detail.isBlank()) && failure.payload() != null) {
            detail = failure.payload().output();
        }
        String label = failure.payload() == null ? failure.title() : failure.payload().actionLabel();
        String message = detail == null || detail.isBlank() ? "能力命令返回非零退出码" : detail.strip();
        return "能力命令执行失败" + (label == null || label.isBlank() ? "" : "（" + label + "）") + "：" + message;
    }

    private void writePrompt(Process process, String prompt) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(prompt);
            writer.flush();
        }
    }

    private String prompt(String taskInstructions, String taskInput, String modelInstruction,
                          String finalResponseInstructions) {
        StringBuilder prompt = new StringBuilder();
        if (taskInstructions != null && !taskInstructions.isBlank()) {
            prompt.append(taskInstructions.strip());
        }
        if (taskInput != null && !taskInput.isBlank()) {
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append(taskInput.strip());
        }
        if (modelInstruction != null && !modelInstruction.isBlank()) {
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append("<model-instruction>\n").append(modelInstruction.strip())
                    .append("\n</model-instruction>\n")
                    .append("以上内容是当前模型的补充约束；若与平台、场景、能力或当前阶段的输出契约冲突，以原有契约为准。");
        }
        if (finalResponseInstructions != null && !finalResponseInstructions.isBlank()) {
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append("<final-response-instructions>\n")
                    .append(finalResponseInstructions.strip())
                    .append("\n</final-response-instructions>\n")
                    .append("以上约束仅在形成最终用户答案时生效，不得据此增加调查步骤、改变工具选择或重复已有内容。");
        }
        return prompt.toString();
    }

    /**
     * 将平台稳定任务指令和当前 MCP 服务说明转换为 Codex CLI 使用的完整指令文本。
     *
     * @param request 当前统一 Runtime 执行请求
     * @return 去除空段并以空行分隔的 Codex 指令文本
     */
    private String runtimeInstructions(RuntimeExecutionRequest request) {
        return java.util.stream.Stream.of(request.instructions(), request.mcpInstructions())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private String readResultText(Path resultPath) {
        try {
            if (Files.exists(resultPath)) {
                return Files.readString(resultPath, StandardCharsets.UTF_8);
            }
            return "";
        } catch (Exception e) {
            log.info("读取执行引擎最终结果失败 path={} message={}", resultPath, e.getMessage());
            return "";
        }
    }

    private void applyRuntimeEnvironment(ProcessBuilder builder, RuntimeModelConfig modelConfig, Path codexHome) {
        builder.environment().put("CODEX_HOME", codexHome.toString());
        builder.environment().put("HOME", codexHome.toString());
        builder.environment().put("AGENTS_HOME", codexHome.resolve("agents-home").toString());
        builder.environment().remove("CODEX_DISABLE_SKILLS");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().remove("LANG");
        builder.environment().remove("LC_ALL");
        builder.environment().remove("LC_CTYPE");
        String processLocale = codexHomeService.processLocale();
        builder.environment().put("LANG", processLocale);
        builder.environment().put("LC_ALL", processLocale);
        builder.environment().put("LC_CTYPE", processLocale);
        if (!codexHomeService.isDeepSeek(modelConfig)
                && modelConfig.apiKey() != null && !modelConfig.apiKey().isBlank()) {
            builder.environment().put("OPENAI_API_KEY", modelConfig.apiKey().trim());
        } else {
            builder.environment().remove("OPENAI_API_KEY");
        }
    }

    /**
     * 将当前任务授权的标准 Skill 映射为公开命令目录。
     *
     * @param environment Backend 已准备的能力执行环境
     * @return 包含按需说明路径和 PATH 命令的 Runtime 指令
     */
    String commandCatalogInstructions(RuntimeExecutionEnvironment environment) {
        StringBuilder result = new StringBuilder("# 可用能力命令\n\n")
                .append("以下公开业务命令已注入当前任务 PATH。先根据能力名称和用途选择最少的能力；首次实际使用某个能力前，只读取其对应说明文件，不要检查安装目录、私有脚本或包装器。\n\n")
                .append("## 能力说明\n\n");
        for (var skill : environment.skills()) {
            result.append("- ").append(skill.displayName()).append(" (`")
                    .append(skill.name()).append("`)：`")
                    .append(codexHomeService.commandGuideLocator(skill.name()))
                    .append("`\n");
        }
        for (var guide : environment.commandGuides()) {
            result.append("- ").append(guide.displayName()).append(" (`")
                    .append(guide.code()).append("`)：`")
                    .append(codexHomeService.commandGuideLocator(guide.code()))
                    .append("`\n");
        }
        result.append("\n## 公开命令\n\n");
        for (RuntimeCommandDescriptor command : environment.commands()) {
            result.append("- `").append(command.command()).append("`");
            if (command.name() != null && !command.name().isBlank()) {
                result.append("：").append(command.name().trim());
            }
            if (command.description() != null && !command.description().isBlank()) {
                result.append("；").append(command.description().trim());
            }
            result.append('\n');
        }
        result.append("\n命令参数、使用边界和结果含义以对应说明文件为准；直接执行上面的逻辑命令，不要运行说明文件中的私有实现路径。\n");
        return result.toString();
    }

    /**
     * 在原生 Skill 模式下只暴露平台命令说明和与其关联的公开命令。
     *
     * @param environment Backend 已准备的能力执行环境
     * @return 不重复注入原生 Skill 目录的精简平台命令指令
     */
    String platformCommandInstructions(RuntimeExecutionEnvironment environment) {
        StringBuilder result = new StringBuilder("# 平台命令\n\n")
                .append("以下平台命令已注入当前任务 PATH；首次使用前只读取对应说明，不要检查安装目录、私有脚本或包装器。\n\n")
                .append("## 命令说明\n\n");
        for (var guide : environment.commandGuides()) {
            result.append("- ").append(guide.displayName()).append(" (`")
                    .append(guide.code()).append("`)：`")
                    .append(codexHomeService.commandGuideLocator(guide.code()))
                    .append("`\n");
        }
        result.append("\n## 公开命令\n\n");
        for (RuntimeCommandDescriptor command : environment.commands()) {
            if (command.skillCommand() || command.guideCode() == null
                    || environment.commandGuides().stream()
                    .noneMatch(guide -> command.guideCode().equals(guide.code()))) {
                continue;
            }
            result.append("- `").append(command.command()).append("`");
            if (command.name() != null && !command.name().isBlank()) {
                result.append("：").append(command.name().trim());
            }
            if (command.description() != null && !command.description().isBlank()) {
                result.append("；").append(command.description().trim());
            }
            result.append('\n');
        }
        result.append("\n命令参数、使用边界和结果含义以对应说明文件为准。\n");
        return result.toString();
    }

    private void applyRuntimeCliConfig(List<String> command, RuntimeModelConfig modelConfig, Path codexHome) {
        String providerName = codexHomeService.providerName(modelConfig);
        command.add("-c");
        command.add("model_provider=\"" + providerName + "\"");
        command.add("-c");
        command.add("model=\"" + escapeTomlValue(modelConfig.model().trim()) + "\"");
        if (modelConfig.reasoningEffort() != null && !modelConfig.reasoningEffort().isBlank()) {
            command.add("-c");
            command.add("model_reasoning_effort=\"" + escapeTomlValue(modelConfig.reasoningEffort().trim()) + "\"");
        }
        if (!codexHomeService.isDeepSeek(modelConfig)
                && modelConfig.contextWindowTokens() != null && modelConfig.contextWindowTokens() > 0) {
            command.add("-c");
            command.add("model_context_window=" + modelConfig.contextWindowTokens());
        }
        if (codexHomeService.isDeepSeek(modelConfig)) {
            command.add("-c");
            command.add("preferred_auth_method=\"apikey\"");
            command.add("-c");
            command.add("forced_login_method=\"api\"");
            command.add("-c");
            command.add("model_catalog_json=\""
                    + escapeTomlValue(codexHome.resolve("models.json").toAbsolutePath().normalize().toString()) + "\"");
        }
        command.add("-c");
        command.add("disable_response_storage=true");
        command.add("-c");
        command.add("model_providers." + providerName + ".name=\""
                + (codexHomeService.isDeepSeek(modelConfig) ? "deepseek" : "Lingxi Agent") + "\"");
        command.add("-c");
        command.add("model_providers." + providerName + ".base_url=\""
                + escapeTomlValue(codexHomeService.codexBaseUrl(modelConfig)) + "\"");
        command.add("-c");
        command.add("model_providers." + providerName + ".wire_api=\"responses\"");
        if (!codexHomeService.isDeepSeek(modelConfig)) {
            command.add("-c");
            command.add("model_providers." + providerName + ".requires_openai_auth=true");
        }
    }

    private String escapeTomlValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private RuntimeEvent redactEvent(RuntimeEvent event, List<String> sensitiveValues) {
        return RuntimeSensitiveText.redact(event, sensitiveValues);
    }

    /**
     * 取消正在运行的 Codex 进程。
     *
     * @param taskId 任务 ID
     * @return 是否找到并取消进程
     */
    public boolean cancel(Long taskId) {
        return Optional.ofNullable(runningProcesses.remove(taskId))
                .map(process -> {
                    log.info("取消执行引擎进程 taskId={}", taskId);
                    destroyProcessTree(process);
                    return true;
                })
                .orElse(false);
    }

    @Override
    public void close() {
        runningProcesses.values().forEach(this::destroyProcessTree);
        runningProcesses.clear();
        streamExecutor.shutdownNow();
    }

    private void destroyProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(child -> {
            try {
                child.destroyForcibly();
            } catch (Exception e) {
                log.info("清理执行引擎子进程失败 pid={} message={}", child.pid(), e.getMessage());
            }
        });
        process.destroyForcibly();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.info("等待执行引擎进程退出失败 message={}", e.getMessage());
        }
    }

    private void readJsonStream(InputStream inputStream,
                                StringBuilder target,
                                CodexActivityEventCoordinator activityCoordinator,
                                Consumer<RuntimeUsage> tokenUsageConsumer,
                                AtomicLong lastOutputAtNanos,
                                AtomicLong tokenUsageCount,
                                AtomicLong previousTotalTokens,
                                AtomicBoolean contextPressureReported,
                                AtomicReference<CodexUsageSnapshot> currentTaskUsage,
                                AtomicReference<RuntimeEvent> pendingAgentMessage,
                                Runnable sessionEventSynchronizer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    consumeTokenUsageLine(line, tokenUsageConsumer, tokenUsageCount, previousTotalTokens,
                            contextPressureReported, currentTaskUsage, activityCoordinator::accept);
                    RuntimeEvent event = parseJsonEvent(line);
                    if (event != null && event.title() != null && !event.title().isBlank()) {
                        try {
                            if (event.type() == RuntimeEventType.COMMAND && event.status() == RuntimeEventStatus.RUNNING) {
                                sessionEventSynchronizer.run();
                            }
                            if (event.type() == RuntimeEventType.AGENT_MESSAGE) {
                                flushPendingAgentMessage(pendingAgentMessage, target, activityCoordinator::accept);
                                pendingAgentMessage.set(event);
                                activityCoordinator.modelResponseStarted();
                                lastOutputAtNanos.set(System.nanoTime());
                                log.info("读取执行引擎事件 {}", eventSummary(line, event));
                                continue;
                            }
                            if (!isCompletionEvent(event)) {
                                flushPendingAgentMessage(pendingAgentMessage, target, activityCoordinator::accept);
                            }
                            String value = displayText(event);
                            synchronized (target) {
                                appendLimited(target, value);
                            }
                            activityCoordinator.accept(event);
                        } catch (Exception e) {
                            log.warn("保存执行引擎事件失败，继续读取 stdout message={} event={}", e.getMessage(), eventSummary(line, event));
                        }
                        lastOutputAtNanos.set(System.nanoTime());
                    }
                    log.info("读取执行引擎事件 {}", eventSummary(line, event));
                } catch (Exception e) {
                    synchronized (target) {
                        appendLimited(target, line + System.lineSeparator());
                    }
                    lastOutputAtNanos.set(System.nanoTime());
                    log.warn("处理执行引擎 stdout 单行失败，继续读取 message={} bytes={}",
                            e.getMessage(), line.getBytes(StandardCharsets.UTF_8).length);
                }
            }
            pendingAgentMessage.set(null);
        } catch (Exception e) {
            log.info("读取执行引擎输出结束 message={}", e.getMessage());
        }
    }

    private void consumeTokenUsageLine(String line,
                                       Consumer<RuntimeUsage> tokenUsageConsumer,
                                       AtomicLong tokenUsageCount,
                                       AtomicLong previousTotalTokens,
                                       AtomicBoolean contextPressureReported,
                                       AtomicReference<CodexUsageSnapshot> currentTaskUsage,
                                       Consumer<RuntimeEvent> eventConsumer) {
        CodexUsageSnapshot tokenUsage = tokenUsageSessionService.parseTokenUsage(line);
        if (tokenUsage == null) {
            return;
        }
        synchronized (currentTaskUsage) {
            if (!tokenUsageSessionService.applyRequestCount(tokenUsage, tokenUsageCount, previousTotalTokens)) {
                return;
            }
            try {
                CodexUsageSnapshot usage = tokenUsageSessionService.addLastUsage(currentTaskUsage.get(), tokenUsage);
                currentTaskUsage.set(usage);
                tokenUsageConsumer.accept(toRuntimeUsage(usage));
                reportContextPressureIfNeeded(tokenUsage, contextPressureReported, eventConsumer);
            } catch (Exception e) {
                log.info("保存 token 用量失败，继续读取执行事件 message={}", e.getMessage());
            }
        }
    }

    private void flushPendingAgentMessage(AtomicReference<RuntimeEvent> pendingAgentMessage,
                                          StringBuilder target,
                                          Consumer<RuntimeEvent> outputConsumer) {
        RuntimeEvent pending = pendingAgentMessage.getAndSet(null);
        if (pending == null) {
            return;
        }
        synchronized (target) {
            appendLimited(target, displayText(pending));
        }
        outputConsumer.accept(pending);
    }

    private boolean isCompletionEvent(RuntimeEvent event) {
        return event != null
                && event.type() == RuntimeEventType.SYSTEM
                && ("codex.turn.completed".equals(event.title()) || "codex.task.completed".equals(event.title()));
    }

    private String displayText(RuntimeEvent event) {
        return event == null || event.title() == null || event.title().isBlank()
                ? ""
                : event.title() + System.lineSeparator();
    }

    private void readPlainStream(InputStream inputStream, StringBuilder target) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (target) {
                    appendLimited(target, line + System.lineSeparator());
                }
                log.info("读取执行引擎错误输出 bytes={}", line.getBytes(StandardCharsets.UTF_8).length);
            }
        } catch (Exception e) {
            log.info("读取执行引擎错误输出结束 message={}", e.getMessage());
        }
    }

    private void reportContextPressureIfNeeded(CodexUsageSnapshot usage,
                                               AtomicBoolean contextPressureReported,
                                               Consumer<RuntimeEvent> outputConsumer) {
        Long lastInputTokens = usage.getLastInputTokens();
        Long modelContextWindow = usage.getModelContextWindow();
        if (lastInputTokens == null || modelContextWindow == null || modelContextWindow <= 0) {
            return;
        }
        double ratio = lastInputTokens.doubleValue() / modelContextWindow.doubleValue();
        if (ratio < 0.90 || !contextPressureReported.compareAndSet(false, true)) {
            return;
        }
        String detail = "lastInputTokens=" + lastInputTokens
                + ", modelContextWindow=" + modelContextWindow
                + ", usageRatio=" + String.format(java.util.Locale.ROOT, "%.2f%%", ratio * 100);
        outputConsumer.accept(event(RuntimeEventType.SYSTEM, RuntimeEventStatus.INFO, "codex.context.pressure", detail, null));
    }

    private RuntimeUsage toRuntimeUsage(CodexUsageSnapshot usage) {
        if (usage == null) {
            return null;
        }
        return new RuntimeUsage(
                usage.getEventTimestamp(), usage.getRequestCount(), usage.getInputTokens(),
                usage.getCachedInputTokens(), usage.getCacheCreationInputTokens(), usage.getOutputTokens(),
                usage.getReasoningOutputTokens(), usage.getTotalTokens(), usage.getLastInputTokens(),
                usage.getLastCachedInputTokens(), usage.getLastCacheCreationInputTokens(), usage.getLastOutputTokens(),
                usage.getLastReasoningOutputTokens(), usage.getLastTotalTokens(), usage.getModelContextWindow()
        );
    }

    private void appendLimited(StringBuilder target, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        target.append(value);
        if (target.length() > MAX_PROCESS_BUFFER_LENGTH) {
            int removeLength = target.length() - MAX_PROCESS_BUFFER_LENGTH;
            target.delete(0, removeLength);
            target.insert(0, "[前序输出过长，已截断]" + System.lineSeparator());
        }
    }

    private String eventSummary(String line, RuntimeEvent event) {
        try {
            CodexCliEvent root = codexEventParser.parse(line);
            CodexCliEvent unwrapped = codexEventParser.unwrap(root);
            CodexCliEvent item = unwrapped.getItem();
            String eventType = firstNonBlank(unwrapped.getType(), root.getType(), "unknown");
            String itemType = item == null ? null : item.getType();
            String status = firstNonBlank(unwrapped.getStatus(), item == null ? null : item.getStatus());
            return "type=" + eventType
                    + " itemType=" + nullToEmpty(itemType)
                    + " status=" + nullToEmpty(status)
                    + " bytes=" + line.getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception e) {
            return "type=plain bytes=" + line.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private RuntimeEvent parseJsonEvent(String line) {
        try {
            CodexCliEvent root = codexEventParser.parse(line);
            CodexCliEvent event = codexEventParser.unwrap(root);
            if (event != root) {
                return parseEventNode(event);
            }
            return parseEventNode(root);
        } catch (Exception e) {
            return event(RuntimeEventType.AGENT_MESSAGE, RuntimeEventStatus.INFO, line, null, null);
        }
    }

    private RuntimeEvent parseEventNode(CodexCliEvent root) {
        try {
            String type = root.getType();
            if ("thread.started".equals(type)) {
                return event(RuntimeEventType.SYSTEM, RuntimeEventStatus.INFO, "codex.thread.started", null, root);
            }
            if ("turn.started".equals(type)) {
                return event(RuntimeEventType.SYSTEM, RuntimeEventStatus.INFO, "codex.turn.started", null, root);
            }
            if ("task_started".equals(type)) {
                return event(RuntimeEventType.SYSTEM, RuntimeEventStatus.INFO, "codex.task.started", null, root);
            }
            if (isReasoningType(type)) {
                return reasoningEvent(root, RuntimeEventStatus.RUNNING);
            }
            if ("turn.completed".equals(type)) {
                return transientEvent(RuntimeEventType.SYSTEM, RuntimeEventStatus.SUCCESS, "codex.turn.completed", null, root);
            }
            if ("task_complete".equals(type)) {
                return transientEvent(RuntimeEventType.SYSTEM, RuntimeEventStatus.SUCCESS, "codex.task.completed", null, root);
            }
            if ("error".equals(type)) {
                String message = nullToEmpty(root.getMessage());
                if (isCodexRuntimeAdvisory(message)) {
                    return advisoryEvent(message, root);
                }
                return event(RuntimeEventType.ERROR, RuntimeEventStatus.FAILED, "codex.error", message, root);
            }
            if ("turn.failed".equals(type)) {
                String message = root.getError() == null ? "" : nullToEmpty(root.getError().getMessage());
                return event(RuntimeEventType.ERROR, RuntimeEventStatus.FAILED, "codex.turn.failed", message, root);
            }
            if ("item.completed".equals(type)) {
                CodexCliEvent item = root.getItem();
                if ("mcp_tool_call".equals(item == null ? "" : item.getType())) {
                    return null;
                }
                if ("agent_message".equals(item == null ? "" : item.getType())) {
                    String text = eventReadableText(item);
                    if (text == null) {
                        text = eventReadableText(root);
                    }
                    return agentMessageEvent(text, root);
                }
                if ("message".equals(item == null ? "" : item.getType()) && "assistant".equals(item.getRole())) {
                    String message = eventReadableText(item);
                    return agentMessageEvent(message, root);
                }
                RuntimeEvent translated = translateCompletedItem(item);
                if (translated != null) {
                    return translated;
                }
                if ("error".equals(item == null ? "" : item.getType())) {
                    String message = firstText(item, "message");
                    if (message == null) {
                        return null;
                    }
                    if (isCodexRuntimeAdvisory(message)) {
                        return advisoryEvent(message, root);
                    }
                    return event(RuntimeEventType.ERROR, RuntimeEventStatus.FAILED, "codex.error", message, root);
                }
                String message = eventReadableText(item);
                if (message != null) {
                    return agentMessageEvent(message, root);
                }
            }
            if ("item.started".equals(type)) {
                CodexCliEvent item = root.getItem();
                if ("mcp_tool_call".equals(item == null ? "" : item.getType())) {
                    return null;
                }
                RuntimeEvent translated = translateStartedItem(item);
                if (translated != null) {
                    return translated;
                }
            }
            if ("agent_message".equals(type)) {
                String message = eventReadableText(root);
                return agentMessageEvent(message, root);
            }
            if ("message".equals(type) && "assistant".equals(root.getRole())) {
                String message = eventReadableText(root);
                return agentMessageEvent(message, root);
            }
            if ("function_call".equals(type)) {
                return translateFunctionCall(root);
            }
            if ("function_call_output".equals(type)) {
                return translateFunctionCallOutput(root);
            }
            String message = eventReadableText(root);
            return agentMessageEvent(message, root);
        } catch (Exception e) {
            return null;
        }
    }

    private RuntimeEvent agentMessageEvent(String message, CodexCliEvent payloadNode) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return event(RuntimeEventType.AGENT_MESSAGE, RuntimeEventStatus.INFO, messageTitle(message), message, payloadNode);
    }

    private String messageTitle(String message) {
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() <= 80 ? compact : compact.substring(0, 77) + "...";
    }

    private RuntimeEvent translateStartedItem(CodexCliEvent item) {
        String itemType = item == null ? "" : item.getType();
        if ("command_execution".equals(itemType)) {
            String id = firstText(item, "id");
            RuntimeActivity activity = commandActivity(firstText(item, "command"), id);
            if (id != null) {
                runningCalls.put(id, activity);
            }
            return activityEvent(activity, RuntimeEventStatus.RUNNING, firstText(item, "command"), item);
        }
        if (isReasoningType(itemType)) {
            return reasoningEvent(item, RuntimeEventStatus.RUNNING);
        }
        return null;
    }

    private RuntimeEvent translateCompletedItem(CodexCliEvent item) {
        String itemType = item == null ? "" : item.getType();
        if ("command_execution".equals(itemType)) {
            String id = firstText(item, "id");
            RuntimeActivity activity = id == null ? null : runningCalls.remove(id);
            if (activity == null) {
                activity = commandActivity(firstText(item, "command"), id);
            }
            boolean failed = (item.getExitCode() != null && item.getExitCode() != 0) || "failed".equals(item.getStatus());
            return activityEvent(activity, failed ? RuntimeEventStatus.FAILED : RuntimeEventStatus.SUCCESS, firstText(item, "aggregated_output"), item);
        }
        if ("function_call".equals(itemType)) {
            return translateFunctionCall(item);
        }
        if ("function_call_output".equals(itemType)) {
            return translateFunctionCallOutput(item);
        }
        if (isReasoningType(itemType)) {
            return reasoningEvent(item, RuntimeEventStatus.SUCCESS);
        }
        return null;
    }

    private boolean isReasoningType(String type) {
        return type != null && type.contains("reasoning");
    }

    /**
     * 将执行引擎的 reasoning 事件保留为思考状态，避免被普通消息或工具动作折叠掉。
     *
     * @param event 执行引擎原始事件
     * @param status 当前事件状态
     * @return 平台任务事件
     */
    private RuntimeEvent reasoningEvent(CodexCliEvent event, RuntimeEventStatus status) {
        return event(RuntimeEventType.THINKING, status, "codex.reasoning", eventReadableText(event), event);
    }

    private RuntimeEvent transientEvent(RuntimeEventType type, RuntimeEventStatus status, String title, String detail, CodexCliEvent payloadNode) {
        RuntimeEvent event = event(type, status, title, detail, payloadNode);
        return new RuntimeEvent(type, status, title, detail,
                copyPayload(event.payload(), null, null, null, null, true));
    }

    private RuntimeEvent translateFunctionCall(CodexCliEvent event) {
        String name = firstText(event, "name");
        String callId = firstText(event, "call_id", "id");
        String arguments = firstText(event, "arguments");
        RuntimeActivity activity = functionCallActivity(name, arguments, callId);
        if (callId != null) {
            runningCalls.put(callId, activity);
        }
        return activityEvent(activity, RuntimeEventStatus.RUNNING, arguments, event);
    }

    private RuntimeEvent translateFunctionCallOutput(CodexCliEvent event) {
        String callId = firstText(event, "call_id", "id");
        RuntimeActivity activity = callId == null ? null : runningCalls.remove(callId);
        if (activity == null) {
            activity = genericActivity(RuntimeEventType.COMMAND, "tool", callId, null);
        }
        String output = firstText(event, "output");
        return activityEvent(activity, RuntimeEventStatus.SUCCESS, output, event);
    }

    private RuntimeActivity functionCallActivity(String name, String arguments, String instanceId) {
        if (name == null || name.isBlank()) {
            return genericActivity(RuntimeEventType.COMMAND, "tool", instanceId, null);
        }
        if (!"exec_command".equals(name)) {
            return genericActivity(RuntimeEventType.COMMAND, "tool:" + name, instanceId, name);
        }
        String command = commandFromArguments(arguments);
        if (command == null || command.isBlank()) {
            return genericActivity(RuntimeEventType.COMMAND, "command", instanceId, null);
        }
        return commandActivity(command, instanceId);
    }

    private RuntimeActivity commandActivity(String command, String instanceId) {
        if (command == null || command.isBlank()) {
            return genericActivity(RuntimeEventType.COMMAND, "command", instanceId, null);
        }
        String unwrapped = unwrapShellCommand(command);
        RuntimeActivity capabilityActivity = directCapabilityCommandActivity(unwrapped, instanceId);
        if (capabilityActivity != null) {
            return capabilityActivity;
        }
        String presented = commandForPresentation(unwrapped);
        return genericActivity(RuntimeEventType.COMMAND, "command:" + firstCommandWord(presented), instanceId,
                compactCommand(presented));
    }

    /**
     * 从 Shell 命令中剥离仅用于定位外部工作区的前缀，保留真正执行的业务命令用于事件展示。
     *
     * @param command Codex 生成并实际执行的原始 Shell 命令
     * @return 不包含工作目录变量或 cd 前缀的展示命令
     */
    private String commandForPresentation(String command) {
        String value = command == null ? "" : command.trim();
        for (int index = 0; index < 4; index++) {
            Matcher variableMatcher = WORKING_DIRECTORY_VARIABLE.matcher(value);
            if (variableMatcher.matches()) {
                String variable = variableMatcher.group(1);
                String body = variableMatcher.group(5).trim();
                body = body.replace("${" + variable + "}/", "")
                        .replace("$" + variable + "/", "")
                        .replace("${" + variable + "}", ".")
                        .replace("$" + variable, ".");
                value = body;
                continue;
            }
            Matcher directoryMatcher = CHANGE_DIRECTORY_PREFIX.matcher(value);
            if (directoryMatcher.matches()) {
                value = directoryMatcher.group(1).trim();
                continue;
            }
            break;
        }
        return value;
    }

    private String unwrapShellCommand(String command) {
        String trimmed = command.replaceAll("\\s+", " ").trim();
        String marker = " -lc ";
        int markerIndex = trimmed.indexOf(marker);
        if (markerIndex < 0) {
            return trimmed;
        }
        String shellBody = trimmed.substring(markerIndex + marker.length()).trim();
        if ((shellBody.startsWith("\"") && shellBody.endsWith("\"")) || (shellBody.startsWith("'") && shellBody.endsWith("'"))) {
            return shellBody.substring(1, shellBody.length() - 1).trim();
        }
        return shellBody;
    }

    private RuntimeActivity directCapabilityCommandActivity(String command, String instanceId) {
        ResolvedCommand resolved = capabilityCommandDefinition(command);
        if (resolved == null) {
            return null;
        }
        RuntimeCommandDescriptor definition = resolved.definition();
        return genericActivity(RuntimeEventType.COMMAND, capabilityActivityKey(definition), instanceId,
                compactCommand(resolved.invocation()), definition.name(), definition.icon());
    }

    private String commandFromArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            String command = objectMapper.readTree(arguments).path("cmd").asText(null);
            return command == null || command.isBlank() ? null : command;
        } catch (Exception e) {
            return null;
        }
    }

    private String compactCommand(String command) {
        String trimmed = command.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 117) + "..." : trimmed;
    }

    private String firstCommandWord(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isBlank()) {
            return "unknown";
        }
        return trimmed.split("\\s+")[0];
    }

    private ResolvedCommand capabilityCommandDefinition(String command) {
        String normalized = normalizeCommandSignature(command);
        if (normalized == null) {
            return null;
        }
        ResolvedCommand matched = null;
        int matchedIndex = Integer.MAX_VALUE;
        for (Map.Entry<String, RuntimeCommandDescriptor> entry : commandDefinitions.entrySet()) {
            String signature = entry.getKey();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?:^|(?:&&|\\|\\||[;|])\\s*)(" + java.util.regex.Pattern.quote(signature)
                            + ")(?:\\s+([^;&|]*))?");
            java.util.regex.Matcher matcher = pattern.matcher(normalized);
            if (!matcher.find()) {
                continue;
            }
            int index = matcher.start(1);
            String invocation = matcher.group(1)
                    + (matcher.group(2) == null || matcher.group(2).isBlank() ? "" : " " + matcher.group(2).trim());
            if (matched == null || index < matchedIndex
                    || index == matchedIndex && signature.length() > matched.definition().command().length()) {
                matched = new ResolvedCommand(entry.getValue(), invocation);
                matchedIndex = index;
            }
        }
        return matched;
    }

    private String normalizeCommandSignature(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        return command.replaceAll("\\s+", " ").trim();
    }

    private String capabilityActivityKey(RuntimeCommandDescriptor definition) {
        String code = definition.code() == null ? "" : definition.code().trim();
        if (code.isBlank()) {
            code = definition.moduleCode();
        }
        return "capability:" + code.replace('.', ':').replaceAll("[^a-zA-Z0-9:_-]", "-");
    }

    private record ResolvedCommand(RuntimeCommandDescriptor definition, String invocation) {
    }

    private RuntimeActivity genericActivity(RuntimeEventType type, String key, String instanceId, String target) {
        return genericActivity(type, key, instanceId, target, key);
    }

    private RuntimeActivity genericActivity(RuntimeEventType type, String key, String instanceId, String target, String label) {
        return genericActivity(type, key, instanceId, target, label, null);
    }

    private RuntimeActivity genericActivity(RuntimeEventType type, String key, String instanceId, String target,
                                            String label, RuntimeActionIcon icon) {
        RuntimeActionIcon resolvedIcon = icon != null ? icon
                : type == RuntimeEventType.COMMAND && key != null && key.startsWith("command")
                        ? RuntimeActionIcon.TERMINAL : RuntimeActionIcon.WRENCH;
        return new RuntimeActivity(type, key, instanceId, label == null || label.isBlank() ? key : label,
                target, resolvedIcon);
    }

    private RuntimeEvent activityEvent(RuntimeActivity activity, RuntimeEventStatus status, String detail, CodexCliEvent payloadNode) {
        RuntimeEvent event = event(activity.type(), status, activity.key(), detail, payloadNode);
        RuntimeEventPayload payload = copyPayload(
                event.payload(), activity.key(), activity.instanceId(), activity.label(), activity.target(), null);
        if (activity.icon() != null) {
            payload = payload.withActionIcon(activity.icon());
        }
        return new RuntimeEvent(event.type(), event.status(), event.title(), event.detail(), payload);
    }

    private RuntimeEvent event(RuntimeEventType type, RuntimeEventStatus status, String title, String detail, CodexCliEvent payloadNode) {
        boolean errorEvent = type == RuntimeEventType.ERROR;
        String readableDetail = errorEvent ? readableErrorText(detail) : detail;
        RuntimeEventPayload payload = payload(payloadNode, errorEvent);
        String label = runtimeEventLabel(type, title);
        if (label != null) {
            payload = copyPayload(payload, null, null, label, null, null)
                    .withActionIcon(runtimeEventIcon(type, title));
        }
        return new RuntimeEvent(type, status, title, readableDetail, payload);
    }

    private String runtimeEventLabel(RuntimeEventType type, String title) {
        if (type == RuntimeEventType.THINKING) {
            return "正在思考";
        }
        if (title != null && title.startsWith("Execution engine is still running; no new process output.")) {
            return "执行引擎仍在分析，暂无新输出";
        }
        return switch (title == null ? "" : title) {
            case "codex.thread.started" -> "执行引擎会话已创建";
            case "codex.turn.started" -> "执行引擎开始分析";
            case "codex.task.started" -> "执行引擎开始准备";
            case "codex.turn.completed", "codex.task.completed" -> "模型响应完成，正在整理结果";
            case "codex.context.pressure" -> "执行上下文接近上限";
            case "codex.runtime.advisory" -> "执行引擎运行提示";
            case "codex.error" -> "执行引擎错误";
            case "codex.turn.failed" -> "执行引擎执行失败";
            case "codex.config.loaded" -> "执行引擎配置已加载";
            default -> null;
        };
    }

    private RuntimeActionIcon runtimeEventIcon(RuntimeEventType type, String title) {
        if (type == RuntimeEventType.THINKING) {
            return RuntimeActionIcon.HEAD_CIRCUIT;
        }
        if (title != null && title.startsWith("Execution engine is still running; no new process output.")) {
            return RuntimeActionIcon.CLOCK;
        }
        return switch (title == null ? "" : title) {
            case "codex.thread.started" -> RuntimeActionIcon.PLUGS_CONNECTED;
            case "codex.turn.started", "codex.task.started" -> RuntimeActionIcon.PLAY_CIRCLE;
            case "codex.turn.completed", "codex.task.completed", "codex.config.loaded" -> RuntimeActionIcon.CHECK_CIRCLE;
            case "codex.context.pressure", "codex.runtime.advisory" -> RuntimeActionIcon.WARNING_CIRCLE;
            case "codex.error", "codex.turn.failed" -> RuntimeActionIcon.X_CIRCLE;
            default -> RuntimeActionIcon.WRENCH;
        };
    }

    private RuntimeEventPayload payload(CodexCliEvent node, boolean formatError) {
        if (node == null) {
            return null;
        }
        String itemType;
        String itemId;
        String status = firstText(node, "status");
        String command = firstText(node, "command");
        String output = firstText(node, "aggregated_output", "output");
        String message = firstText(node, "message");
        Integer exitCode = node.getExitCode();
        CodexCliEvent item = node.getItem();
        if (item != null) {
            itemType = firstText(item, "type");
            itemId = firstText(item, "id");
            command = firstText(item, "command");
            output = firstText(item, "aggregated_output", "output");
            message = firstText(item, "message");
            status = firstText(item, "status");
            if (item.getExitCode() != null) {
                exitCode = item.getExitCode();
            }
        } else {
            itemType = firstText(node, "item_type");
            itemId = firstText(node, "item_id");
        }
        return new RuntimeEventPayload(
                firstText(node, "type"), itemType, itemId, status, command,
                firstText(node, "name"), firstText(node, "call_id", "id"),
                firstText(node, "arguments"), output, formatError ? readableErrorText(message) : message, exitCode,
                null, null, null, null, null
        );
    }

    /**
     * 将模型服务返回的标准 JSON 错误转换成用户可读文本，普通错误文本保持不变。
     *
     * @param value Codex CLI 事件消息、异常消息或 stderr 文本
     * @return 包含错误、类型、错误码和参数的可读文本；无法识别时返回原文
     */
    private String readableErrorText(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(value.trim());
            com.fasterxml.jackson.databind.JsonNode error = root.path("error");
            if (!error.isObject()) {
                error = root;
            }
            if (!error.isObject()) {
                return value;
            }
            String message = jsonText(error.get("message"));
            String errorType = jsonText(error.get("type"));
            String errorCode = jsonText(error.get("code"));
            boolean hasParameter = error.has("param");
            if (message == null && errorType == null && errorCode == null && !hasParameter) {
                return value;
            }
            List<String> lines = new ArrayList<>();
            if (message != null) {
                lines.add("错误：" + message);
            }
            if (errorType != null) {
                lines.add("类型：" + errorType);
            }
            if (errorCode != null) {
                lines.add("错误码：" + errorCode);
            }
            if (hasParameter) {
                String parameter = jsonText(error.get("param"));
                lines.add("参数：" + (parameter == null ? "无" : parameter));
            }
            return String.join(System.lineSeparator(), lines);
        } catch (Exception ignored) {
            return value;
        }
    }

    private RuntimeEventPayload copyPayload(RuntimeEventPayload source,
                                            String actionKey,
                                            String actionInstanceId,
                                            String actionLabel,
                                            String actionTarget,
                                            Boolean transientEvent) {
        RuntimeEventPayload value = source == null
                ? new RuntimeEventPayload(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null)
                : source;
        return new RuntimeEventPayload(
                value.rawType(), value.itemType(), value.itemId(), value.status(), value.command(),
                value.toolName(), value.callId(), value.arguments(), value.output(), value.message(), value.exitCode(),
                actionKey == null ? value.actionKey() : actionKey,
                actionInstanceId == null ? value.actionInstanceId() : actionInstanceId,
                actionLabel == null ? value.actionLabel() : actionLabel,
                actionTarget == null ? value.actionTarget() : actionTarget,
                transientEvent == null ? value.transientEvent() : transientEvent,
                value.metrics(), value.actionGroupId(), value.actionGroupSize(), value.semantic(), value.visibility(),
                value.modelTimingMode(), value.actionIcon());
    }

    private String assistantMessage(List<CodexCliContent> content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (CodexCliContent item : content) {
            String type = item == null ? "" : item.getType();
            if (type == null || type.isBlank() || "output_text".equals(type) || "text".equals(type) || "summary_text".equals(type) || "reasoning_text".equals(type)) {
                String text = firstText(item);
                if (text != null) {
                    if (!builder.isEmpty()) {
                        builder.append(System.lineSeparator());
                    }
                    builder.append(text);
                }
            }
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    /**
     * 从 Codex CLI 事件中提取可直接展示给用户的动作描述。
     *
     * @param event Codex CLI 原始事件或 item
     * @return 可展示文本；没有文本时返回 null
     */
    private String eventReadableText(CodexCliEvent event) {
        if (event == null) {
            return null;
        }
        String text = firstNonBlank(event.getMessage(), event.getText(), assistantMessage(event.getContent()));
        if (text != null) {
            return text;
        }
        return summaryText(event.getSummary());
    }

    /**
     * 从 reasoning summary JSON 中提取文本。
     *
     * @param summary summary 节点，可能是字符串、对象或数组
     * @return summary 文本；没有文本时返回 null
     */
    private String summaryText(com.fasterxml.jackson.databind.JsonNode summary) {
        if (summary == null || summary.isNull()) {
            return null;
        }
        if (summary.isTextual()) {
            return firstNonBlank(summary.asText());
        }
        if (summary.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (com.fasterxml.jackson.databind.JsonNode item : summary) {
                String text = summaryText(item);
                if (text != null) {
                    if (!builder.isEmpty()) {
                        builder.append(System.lineSeparator());
                    }
                    builder.append(text);
                }
            }
            return builder.isEmpty() ? null : builder.toString();
        }
        return firstNonBlank(
                summary.path("text").asText(null),
                summary.path("message").asText(null),
                summary.path("content").asText(null),
                summary.path("summary").asText(null)
        );
    }

    private String firstText(CodexCliContent content) {
        if (content == null) {
            return null;
        }
        return firstNonBlank(content.getText(), content.getMessage(), content.getContent());
    }

    private String firstText(CodexCliEvent event, String... fieldNames) {
        if (event == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            String value = switch (fieldName) {
                case "id" -> event.getId();
                case "type" -> event.getType();
                case "status" -> event.getStatus();
                case "name" -> event.getName();
                case "call_id" -> event.getCallId();
                case "item_id" -> event.getItemId();
                case "item_type" -> event.getItemType();
                case "command" -> event.getCommand();
                case "arguments" -> jsonText(event.getArguments());
                case "aggregated_output" -> event.getAggregatedOutput();
                case "output" -> event.getOutput();
                case "message" -> event.getMessage();
                case "text", "content" -> event.getText();
                default -> null;
            };
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim())) {
                return value;
            }
        }
        return null;
    }

    private String jsonText(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim())) {
                return value;
            }
        }
        return null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Long numericId(String value, String fieldName) {
        Long id = nullableNumericId(value, fieldName);
        if (id == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return id;
    }

    private Long nullableNumericId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " must be numeric", exception);
        }
    }

    /**
     * 判断 Codex CLI 输出是否只是运行时提示，而不是任务执行失败。
     *
     * @param message Codex CLI error 事件里的 message 文本
     * @return true 表示这是非致命提示；false 表示应继续按错误事件处理
     */
    private boolean isCodexRuntimeAdvisory(String message) {
        return message != null && message.startsWith("Heads up:");
    }

    /**
     * 将 Codex 私有提示转换为平台统一事件语义。
     *
     * @param message Codex CLI 提示文本
     * @param source 产生提示的原始事件
     * @return 保留原始诊断信息并完成语义归一化的事件
     */
    private RuntimeEvent advisoryEvent(String message, CodexCliEvent source) {
        RuntimeEvent event = event(RuntimeEventType.SYSTEM, RuntimeEventStatus.INFO,
                "codex.runtime.advisory", message, source);
        String normalized = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        RuntimeEventSemantic semantic = normalized.contains("compaction") || normalized.contains("compacted")
                ? RuntimeEventSemantic.CONTEXT_COMPACTION : null;
        return new RuntimeEvent(event.type(), event.status(), event.title(), event.detail(),
                event.payload() == null ? null : event.payload().withSemantic(semantic));
    }

    private record RuntimeActivity(
            RuntimeEventType type,
            String key,
            String instanceId,
            String label,
            String target,
            RuntimeActionIcon icon
    ) {
    }
}
