package top.fusb.lingxi.runtime.codex.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.codex.config.CodexRuntimeProperties;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenance;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenanceOperation;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenanceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class CodexCliManager implements RuntimeMaintenance {

    private final CodexRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();
    private final Object installLock = new Object();
    private final Object versionLock = new Object();
    private RuntimeMaintenanceOperation installStatus = idleInstallStatus();
    private volatile LatestRelease latestReleaseCache;

    private static final URI LATEST_VERSION_URI = URI.create("https://registry.npmjs.org/@openai/codex/latest");
    private static final String LATEST_RELEASE_URL = "https://github.com/openai/codex/releases/latest";
    private static final Duration VERSION_CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration VERSION_CACHE_DURATION = Duration.ofMinutes(30);
    private static final Duration VERSION_FAILURE_CACHE_DURATION = Duration.ofMinutes(1);

    /**
     * 检测 Codex CLI 是否可用。
     *
     * @return CLI 可执行文件、版本和错误信息
     */
    @Override
    public RuntimeMaintenanceStatus status() {
        RuntimeMaintenanceStatus response = executionStatus();
        LatestRelease latestRelease = latestRelease();
        if (latestRelease.version() != null) {
            response.setLatestVersion(latestRelease.version());
            response.setReleaseUrl(latestRelease.releaseUrl());
            response.setUpdateAvailable(response.getCurrentVersion() != null
                    && CodexVersion.compare(latestRelease.version(), response.getCurrentVersion()) > 0);
        } else {
            response.setUpdateCheckError(latestRelease.errorText());
        }
        return response;
    }

    /**
     * 仅检测本机执行引擎，不查询远端版本。
     *
     * @return 本机 Codex CLI 可用状态
     */
    public RuntimeMaintenanceStatus executionStatus() {
        return inspectExecutable(executablePath());
    }

    private RuntimeMaintenanceStatus inspectExecutable(String executable) {
        RuntimeMaintenanceStatus response = new RuntimeMaintenanceStatus();
        response.setExecutable(executable);
        response.setOsType(CodexRuntimeOs.current().name());
        try {
            log.info("检测 Codex CLI executable={}", executable);
            Process process = new ProcessBuilder(executable, "--version").start();
            boolean finished = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                response.setAvailable(false);
                response.setErrorText("执行引擎检测超时");
                log.warn("检测 Codex CLI 超时 executable={}", executable);
                return response;
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            response.setAvailable(process.exitValue() == 0);
            response.setVersionText(stdout.trim());
            response.setCurrentVersion(CodexVersion.extract(stdout));
            response.setErrorText(stderr.trim());
            log.info("检测 Codex CLI 完成 executable={} exitCode={} available={} stdout={} stderr={}",
                    executable, process.exitValue(), response.isAvailable(), stdout.trim(), stderr.trim());
            return response;
        } catch (Exception e) {
            response.setAvailable(false);
            response.setErrorText(e.getMessage());
            log.warn("检测 Codex CLI 异常 executable={} message={}", executable, e.getMessage());
            return response;
        }
    }

    /**
     * 启动后台安装流程。
     *
     * @return 当前安装状态
     * @throws IllegalStateException 当前系统不支持自动安装时抛出
     */
    @Override
    public RuntimeMaintenanceOperation startInstall() {
        CodexRuntimeOs osType = CodexRuntimeOs.current();
        if (osType == CodexRuntimeOs.UNKNOWN) {
            throw new IllegalStateException("当前系统不支持自动安装 Codex CLI");
        }
        synchronized (installLock) {
            if (CodexInstallStatus.RUNNING.name().equals(installStatus.getStatus())) {
                return installStatus;
            }
            RuntimeMaintenanceOperation response = new RuntimeMaintenanceOperation();
            response.setStatus(CodexInstallStatus.RUNNING.name());
            response.setOsType(osType.name());
            response.setStartedAt(LocalDateTime.now());
            response.setUpdatedAt(LocalDateTime.now());
            response.setStdoutText("已提交安装任务" + System.lineSeparator());
            installStatus = response;
        }
        installExecutor.submit(() -> runInstall(osType));
        return installStatus;
    }

    /**
     * 查询当前安装流程状态。
     *
     * @return 当前安装状态
     */
    @Override
    public RuntimeMaintenanceOperation installStatus() {
        return installStatus;
    }

    private void runInstall(CodexRuntimeOs osType) {
        StringBuilder allStdout = new StringBuilder();
        StringBuilder allStderr = new StringBuilder();
        Integer lastExitCode = null;
        try {
            log.info("开始执行 Codex CLI 在线安装 osType={} installDir={}",
                    osType, properties.getInstallDir());
            OnlineInstallResult onlineInstallResult = runOnlineInstall(osType, allStdout, allStderr);
            lastExitCode = onlineInstallResult.exitCode();
            if (onlineInstallResult.available()) {
                saveInstallResult(CodexInstallStatus.SUCCESS, lastExitCode, allStdout.toString(), allStderr.toString());
                log.info("Codex CLI 在线安装完成 osType={} executable={}", osType, onlineInstallResult.executable());
                return;
            }
            String unavailableMessage = "在线安装执行完成，但执行引擎仍不可用：" + onlineInstallResult.errorText();
            appendLine(allStderr, unavailableMessage);
            appendInstallStderr(unavailableMessage);
        } catch (Exception e) {
            appendLine(allStderr, "安装失败：" + e.getMessage());
            appendInstallStderr("安装失败：" + e.getMessage());
            log.warn("Codex CLI 安装失败 osType={} message={}", osType, e.getMessage());
        }
        saveInstallResult(CodexInstallStatus.FAILED, lastExitCode, allStdout.toString(), allStderr.toString());
    }

    private void saveInstallResult(CodexInstallStatus status, Integer exitCode, String stdoutText, String stderrText) {
        synchronized (installLock) {
            installStatus.setStatus(status.name());
            installStatus.setEndedAt(LocalDateTime.now());
            installStatus.setUpdatedAt(LocalDateTime.now());
            installStatus.setExitCode(exitCode);
            installStatus.setStdoutText(stdoutText);
            installStatus.setStderrText(stderrText);
        }
    }

    private RuntimeMaintenanceOperation idleInstallStatus() {
        RuntimeMaintenanceOperation response = new RuntimeMaintenanceOperation();
        response.setStatus(CodexInstallStatus.IDLE.name());
        return response;
    }

    /**
     * 解析当前应使用的 Codex CLI 可执行文件，优先使用平台托管安装目录中的文件。
     *
     * @return Codex CLI 可执行文件路径或配置的兜底命令
     */
    public String executablePath() {
        return executablePath(installDir());
    }

    private String executablePath(Path installDir) {
        Path nativeExecutable = installDir.resolve("bin/codex");
        if (Files.isRegularFile(nativeExecutable)) {
            return nativeExecutable.toString();
        }
        Optional<Path> managedNativeExecutable = managedNativeExecutable(installDir);
        if (managedNativeExecutable.isPresent()) {
            return managedNativeExecutable.get().toString();
        }
        Path npmExecutable = installDir.resolve("node_modules/.bin/codex");
        if (Files.isRegularFile(npmExecutable)) {
            return npmExecutable.toString();
        }
        return properties.getExecutable();
    }

    private OnlineInstallResult runOnlineInstall(CodexRuntimeOs osType, StringBuilder allStdout, StringBuilder allStderr) throws Exception {
        if (osType == CodexRuntimeOs.MAC || osType == CodexRuntimeOs.LINUX) {
            try {
                return runManagedNpmInstall(allStdout, allStderr);
            } catch (Exception e) {
                String message = "受管 npm 安装失败，继续尝试官方安装脚本：" + e.getMessage();
                appendLine(allStderr, message);
                appendInstallStderr(message);
                log.warn("Codex CLI 受管 npm 安装失败 message={}", e.getMessage());
                return runOnlineInstallWithBackup(osType, allStdout, allStderr);
            }
        }
        return runOnlineInstallCommands(osType, allStdout, allStderr);
    }

    private OnlineInstallResult runManagedNpmInstall(StringBuilder allStdout, StringBuilder allStderr) throws Exception {
        Path stagingDir = stagingInstallDir();
        deleteDirectory(stagingDir);
        Files.createDirectories(stagingDir);
        Path npmCacheDir = npmCacheDir();
        Files.createDirectories(npmCacheDir);
        appendInstallStdout("使用 npm 安装最新稳定版到临时目录");
        Integer exitCode = runCommand(null, Duration.ofMinutes(3), allStdout, allStderr,
                "sh", "-lc", "exec npm \"$@\"", "npm",
                "install", "--no-audit", "--no-fund", "--fetch-retries=1", "--fetch-timeout=30000",
                "--cache", npmCacheDir.toString(), "--prefix", stagingDir.toString(), "@openai/codex@latest");
        RuntimeMaintenanceStatus stagedStatus = inspectExecutable(executablePath(stagingDir));
        if (!stagedStatus.isAvailable()) {
            throw new IllegalStateException("npm 安装完成，但执行验证失败：" + stagedStatus.getErrorText());
        }
        activateInstallDirectory(stagingDir);
        return new OnlineInstallResult(true, exitCode, executablePath(), stagedStatus.getErrorText());
    }

    private OnlineInstallResult runOnlineInstallWithBackup(CodexRuntimeOs osType, StringBuilder allStdout, StringBuilder allStderr) throws Exception {
        Path installDir = installDir();
        Path backupDir = backupInstallDir();
        boolean backupCreated = false;
        deleteDirectory(backupDir);
        if (Files.exists(installDir)) {
            Files.move(installDir, backupDir, StandardCopyOption.REPLACE_EXISTING);
            backupCreated = true;
        }
        try {
            OnlineInstallResult result = runOnlineInstallCommands(osType, allStdout, allStderr);
            if (result.available()) {
                deleteDirectory(backupDir);
                return result;
            }
            if (backupCreated) {
                deleteDirectory(installDir);
                Files.move(backupDir, installDir, StandardCopyOption.REPLACE_EXISTING);
            }
            return result;
        } catch (Exception e) {
            deleteDirectory(installDir);
            if (backupCreated && Files.exists(backupDir)) {
                Files.move(backupDir, installDir, StandardCopyOption.REPLACE_EXISTING);
            }
            throw e;
        }
    }

    private OnlineInstallResult runOnlineInstallCommands(CodexRuntimeOs osType, StringBuilder allStdout, StringBuilder allStderr) throws Exception {
        List<List<String>> commands = osType.buildOnlineInstallCommands();
        if (commands.isEmpty()) {
            throw new IllegalStateException("当前系统不支持自动安装 Codex CLI");
        }
        Integer lastExitCode = null;
        RuntimeMaintenanceStatus lastStatus = null;
        for (int i = 0; i < commands.size(); i++) {
            List<String> command = commands.get(i);
            appendInstallStdout("开始在线安装候选 " + (i + 1) + "/" + commands.size() + "：" + String.join(" ", command));
            log.info("执行 Codex CLI 在线安装候选 index={} command={}", i + 1, String.join(" ", command));
            try {
                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();
                Integer exitCode = runCommand(null, Duration.ofMinutes(5), stdout, stderr, command.toArray(String[]::new));
                lastExitCode = exitCode;
                allStdout.append(stdout);
                allStderr.append(stderr);
                if (exitCode == 0) {
                    RuntimeMaintenanceStatus cliStatus = inspectExecutable(executablePath());
                    lastStatus = cliStatus;
                    if (cliStatus.isAvailable()) {
                        return new OnlineInstallResult(true, exitCode, cliStatus.getExecutable(), cliStatus.getErrorText());
                    }
                    String unavailableMessage = "在线安装候选执行完成，但执行引擎仍不可用：" + cliStatus.getErrorText();
                    appendLine(allStderr, unavailableMessage);
                    appendInstallStderr(unavailableMessage);
                    log.warn("Codex CLI 在线安装后检测不可用 index={} error={}", i + 1, cliStatus.getErrorText());
                }
            } catch (Exception e) {
                appendLine(allStderr, "在线安装候选执行异常：" + e.getMessage());
                appendInstallStderr("在线安装候选执行异常：" + e.getMessage());
                log.warn("Codex CLI 在线安装候选异常 index={} command={} message={}", i + 1, String.join(" ", command), e.getMessage());
            }
        }
        return new OnlineInstallResult(false, lastExitCode, null, lastStatus == null ? "" : lastStatus.getErrorText());
    }

    private record OnlineInstallResult(boolean available, Integer exitCode, String executable, String errorText) {
    }

    private LatestRelease latestRelease() {
        LatestRelease cached = latestReleaseCache;
        if (cached != null && cached.checkedAt()
                .plus(cached.errorText() == null ? VERSION_CACHE_DURATION : VERSION_FAILURE_CACHE_DURATION)
                .isAfter(LocalDateTime.now())) {
            return cached;
        }
        synchronized (versionLock) {
            cached = latestReleaseCache;
            if (cached != null && cached.checkedAt()
                    .plus(cached.errorText() == null ? VERSION_CACHE_DURATION : VERSION_FAILURE_CACHE_DURATION)
                    .isAfter(LocalDateTime.now())) {
                return cached;
            }
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(VERSION_CHECK_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
                HttpRequest request = HttpRequest.newBuilder(LATEST_VERSION_URI)
                        .timeout(VERSION_CHECK_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String version = objectMapper.readTree(response.body()).path("version").asText(null);
                if (response.statusCode() >= 400 || version == null) {
                    throw new IllegalStateException("官方版本接口返回异常状态：" + response.statusCode());
                }
                latestReleaseCache = new LatestRelease(version, LATEST_RELEASE_URL, null, LocalDateTime.now());
            } catch (Exception e) {
                latestReleaseCache = new LatestRelease(null, null, "无法获取最新稳定版本：" + e.getMessage(), LocalDateTime.now());
                log.info("检查 Codex CLI 最新版本失败 message={}", e.getMessage());
            }
            return latestReleaseCache;
        }
    }

    private void activateInstallDirectory(Path stagingDir) throws Exception {
        Path installDir = installDir();
        Path backupDir = backupInstallDir();
        deleteDirectory(backupDir);
        if (Files.exists(installDir)) {
            Files.move(installDir, backupDir, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(stagingDir, installDir, StandardCopyOption.REPLACE_EXISTING);
            deleteDirectory(backupDir);
        } catch (Exception e) {
            deleteDirectory(installDir);
            if (Files.exists(backupDir)) {
                Files.move(backupDir, installDir, StandardCopyOption.REPLACE_EXISTING);
            }
            throw e;
        }
    }

    private Path installDir() {
        return Path.of(properties.getInstallDir()).toAbsolutePath().normalize();
    }

    private Path stagingInstallDir() {
        Path installDir = installDir();
        return installDir.resolveSibling(installDir.getFileName() + ".staging");
    }

    private Path backupInstallDir() {
        Path installDir = installDir();
        return installDir.resolveSibling(installDir.getFileName() + ".backup");
    }

    private Path npmCacheDir() {
        Path installDir = installDir();
        return installDir.resolveSibling(installDir.getFileName() + "-npm-cache");
    }

    private Optional<Path> managedNativeExecutable(Path installDir) {
        Path packageRoot = installDir.resolve("node_modules/@openai");
        if (!Files.isDirectory(packageRoot)) {
            return Optional.empty();
        }
        String executableName = CodexRuntimeOs.current() == CodexRuntimeOs.WINDOWS ? "codex.exe" : "codex";
        try (Stream<Path> files = Files.find(packageRoot, 7,
                (path, attributes) -> attributes.isRegularFile()
                        && executableName.equals(path.getFileName().toString())
                        && path.getParent() != null
                        && "bin".equals(path.getParent().getFileName().toString())
                        && path.toString().contains("vendor"))) {
            return files.sorted().findFirst();
        } catch (Exception exception) {
            log.info("查找 Codex CLI 原生执行文件失败 installDir={} message={}", installDir, exception.getMessage());
            return Optional.empty();
        }
    }

    private record LatestRelease(String version, String releaseUrl, String errorText, LocalDateTime checkedAt) {
    }

    private Integer runCommand(Path directory, Duration timeout, StringBuilder stdout, StringBuilder stderr, String... command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(false);
        if (directory != null) {
            processBuilder.directory(directory.toFile());
        }
        Process process = processBuilder.start();
        streamExecutor.submit(() -> readInstallStream(process.getInputStream(), stdout, true));
        streamExecutor.submit(() -> readInstallStream(process.getErrorStream(), stderr, false));
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("安装命令执行超时");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(stderr.toString());
        }
        return process.exitValue();
    }

    private void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }
    }

    private void readInstallStream(InputStream inputStream, StringBuilder target, boolean stdout) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line + System.lineSeparator();
                synchronized (target) {
                    target.append(value);
                }
                synchronized (installLock) {
                    if (stdout) {
                        installStatus.setStdoutText(appendLine(new StringBuilder(nullToEmpty(installStatus.getStdoutText())), line));
                    } else {
                        installStatus.setStderrText(appendLine(new StringBuilder(nullToEmpty(installStatus.getStderrText())), line));
                    }
                    installStatus.setUpdatedAt(LocalDateTime.now());
                }
                log.info("执行 Codex CLI 安装输出 stream={} line={}", stdout ? "stdout" : "stderr", line);
            }
        } catch (Exception e) {
            log.info("读取 Codex CLI 安装输出结束 message={}", e.getMessage());
        }
    }

    private String appendLine(StringBuilder builder, String line) {
        builder.append(line).append(System.lineSeparator());
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void appendInstallStdout(String line) {
        synchronized (installLock) {
            installStatus.setStdoutText(appendLine(new StringBuilder(nullToEmpty(installStatus.getStdoutText())), line));
            installStatus.setUpdatedAt(LocalDateTime.now());
        }
    }

    private void appendInstallStderr(String line) {
        synchronized (installLock) {
            installStatus.setStderrText(appendLine(new StringBuilder(nullToEmpty(installStatus.getStderrText())), line));
            installStatus.setUpdatedAt(LocalDateTime.now());
        }
    }
}
