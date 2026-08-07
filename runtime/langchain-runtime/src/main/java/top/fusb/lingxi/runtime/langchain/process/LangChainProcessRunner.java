package top.fusb.lingxi.runtime.langchain.process;

import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class LangChainProcessRunner {

    private LangChainProcessRunner() {
    }

    public static ProcessResult execute(List<String> command,
                                 Path workingDirectory,
                                 Map<String, String> environment,
                                 Duration timeout,
                                 int maxOutputChars,
                                 LangChainExecutionContext context) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        Process process = builder.start();
        context.register(process);
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(
                () -> readOutput(process.getInputStream(), maxOutputChars));
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroy(process);
                throw new IllegalStateException("命令执行超时");
            }
            return new ProcessResult(process.exitValue(), outputFuture.get(10, TimeUnit.SECONDS));
        } finally {
            context.unregister(process);
            if (process.isAlive()) {
                destroy(process);
            }
        }
    }

    public static void destroy(Process process) {
        if (process == null) {
            return;
        }
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (Exception ignored) {
                // Process cleanup is best effort after cancellation or timeout.
            }
        });
        process.destroyForcibly();
    }

    private static String readOutput(InputStream input, int maxOutputChars) {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int retainedBytes = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = Math.max(0, maxOutputChars * 4 - retainedBytes);
                int retained = Math.min(read, remaining);
                if (retained > 0) {
                    output.write(buffer, 0, retained);
                    retainedBytes += retained;
                }
            }
            String text = output.toString(StandardCharsets.UTF_8);
            return text.length() <= maxOutputChars
                    ? text
                    : text.substring(0, maxOutputChars) + "\n[输出已截断]";
        } catch (Exception exception) {
            return "读取命令输出失败：" + exception.getMessage();
        }
    }

    public record ProcessResult(int exitCode, String output) {
    }
}
