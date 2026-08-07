package top.fusb.lingxi.runtime.langchain.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import top.fusb.lingxi.runtime.langchain.process.LangChainProcessRunner;
import top.fusb.lingxi.runtime.langchain.util.LangChainJsonQueryKit;
import top.fusb.lingxi.runtime.langchain.util.LangChainToolOutputKit;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class LangChainFileTools {

    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(60);

    private final LangChainRuntimeProperties properties;
    private final LangChainExecutionContext context;
    private final LangChainManagedFileRegistry files;
    private final LangChainEvidenceStore evidenceStore;
    private final ObjectMapper objectMapper;
    private final LangChainJsonQueryKit jsonQueryKit;
    private final boolean imageInputSupported;

    public LangChainFileTools(LangChainRuntimeProperties properties,
                              LangChainExecutionContext context,
                              LangChainManagedFileRegistry files,
                              LangChainEvidenceStore evidenceStore,
                              ObjectMapper objectMapper,
                              boolean imageInputSupported) {
        this.properties = properties;
        this.context = context;
        this.files = files;
        this.evidenceStore = evidenceStore;
        this.objectMapper = objectMapper;
        this.jsonQueryKit = new LangChainJsonQueryKit(objectMapper);
        this.imageInputSupported = imageInputSupported;
    }

    /**
     * 按通用 glob 表达式查找已注册根目录中的文件。
     *
     * @param root `task-context` 或工具输出中的 fileRoot
     * @param pattern 相对于 root 的文件路径表达式
     * @return 匹配文件的相对路径
     * @throws Exception 根目录未注册、表达式无效或文件遍历失败时抛出
     */
    @Tool(name = "glob", value = {
            "按 glob 模式匹配已注册根目录中的文件路径，不搜索文件内容。root 使用 task-context 或工具输出中的 fileRoot；pattern 支持目录、文件名和 *、**、? 通配符。"
    })
    public String glob(
            @P("task-context 或工具输出中的 fileRoot") String root,
            @P(value = "glob 路径模式，例如 **/*.java、evidence/**/*.json；留空列出全部文件",
                    required = false) String pattern
    ) throws Exception {
        context.beginToolCall("glob");
        Path fileRoot = files.resolveRoot(root);
        if (isGitWorktree(fileRoot)) {
            List<String> arguments = new ArrayList<>(List.of(
                    "ls-files", "--cached", "--others", "--exclude-standard"));
            if (pattern != null && !pattern.isBlank()) {
                arguments.add("--");
                arguments.add(":(glob)" + pattern.trim());
            }
            return requireGitResult(fileRoot, arguments, "查找文件");
        }
        return findRegularFiles(fileRoot, pattern);
    }

    /**
     * 在已注册根目录中搜索文本，Git worktree 使用 Git 加速，普通目录直接遍历文本文件。
     *
     * @param root `task-context` 或工具输出中的 fileRoot
     * @param pattern 搜索文本或扩展正则表达式
     * @param include 可选文件路径表达式
     * @param literal 是否按普通文本匹配
     * @return 带相对路径和行号的命中行
     * @throws Exception 根目录未注册、参数无效或搜索失败时抛出
     */
    @Tool(name = "grep", value = {
            "在已注册根目录的文件内容中搜索模式，不用于按文件名找文件。返回相对路径、行号和命中行；pattern 默认按正则表达式匹配，literal=true 时按普通文本匹配，include 可限定文件路径。"
    })
    public String grep(
            @P("task-context 或工具输出中的 fileRoot") String root,
            @P("要在文件内容中搜索的文本或扩展正则表达式") String pattern,
            @P(value = "可选文件 glob，例如 **/*.java、evidence/**", required = false) String include,
            @P(value = "是否按普通文本而非正则表达式匹配", required = false) Boolean literal
    ) throws Exception {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern 不能为空");
        }
        context.beginToolCall("grep");
        Path fileRoot = files.resolveRoot(root);
        if (isGitWorktree(fileRoot)) {
            List<String> arguments = new ArrayList<>(List.of(
                    "grep", "--untracked", "--line-number", "-I"));
            arguments.add(Boolean.TRUE.equals(literal) ? "--fixed-strings" : "--extended-regexp");
            arguments.add("-e");
            arguments.add(pattern);
            if (include != null && !include.isBlank()) {
                arguments.add("--");
                arguments.add(":(glob)" + include.trim());
            }
            LangChainProcessRunner.ProcessResult result = runGit(fileRoot, arguments);
            if (result.exitCode() == 1) {
                return "未找到匹配内容";
            }
            if (result.exitCode() != 0) {
                throw new IllegalStateException("搜索文件失败：" + result.output().trim());
            }
            return result.output().stripTrailing();
        }
        return searchRegularFiles(fileRoot, pattern, include, Boolean.TRUE.equals(literal));
    }

    /**
     * 读取已注册根目录中的一个文件；文本返回带行号内容，图片返回多模态图片块。
     *
     * @param root `task-context` 或工具输出中的 fileRoot
     * @param relativePath 根目录内相对文件路径
     * @param startLine 文本文件可选起始行，从 1 开始
     * @param endLine 文本文件可选结束行，包含该行且不得小于 startLine
     * @return 文本或图片内容块
     * @throws Exception 根目录未注册、路径越界、文件类型不支持或读取失败时抛出
     */
    @Tool(name = "read_file", value = {
            "读取已注册文件根目录中的一个文件。文本返回带原文件行号的内容；未指定 endLine 时按页返回并给出下一页 startLine，明确指定 startLine/endLine 时可在硬上限内读取完整区间。图片直接返回给支持图片输入的当前模型。"
    })
    public List<Content> readFile(
            @P("task-context 或工具输出中的 fileRoot") String root,
            @P("根目录内相对文件路径") String relativePath,
            @P(value = "文本文件可选起始行号，从 1 开始", required = false) Integer startLine,
            @P(value = "文本文件可选结束行号，包含该行；提供时必须大于或等于 startLine", required = false) Integer endLine
    ) throws Exception {
        context.beginToolCall("read_file");
        Path fileRoot = files.resolveRoot(root);
        Path file = files.resolveFile(fileRoot, relativePath);
        String mimeType = imageMimeType(file);
        if (mimeType != null) {
            if (!imageInputSupported) {
                throw new IllegalStateException("当前模型配置未启用图片输入");
            }
            if (startLine != null || endLine != null) {
                throw new IllegalArgumentException("图片文件不接受行号范围");
            }
            long size = Files.size(file);
            if (size <= 0 || size > properties.getWorkspaceImageMaxBytes()) {
                throw new IllegalArgumentException("图片大小不在允许范围内：" + relativePath);
            }
            return List.of(ImageContent.from(file, mimeType, ImageContent.DetailLevel.HIGH));
        }
        String text = readText(file, startLine, endLine);
        int evidenceThreshold = Math.max(1_000, properties.getToolOutputMaxChars());
        if (text.length() <= evidenceThreshold || evidenceStore.isEvidenceFile(file)) {
            return List.of(TextContent.from(text));
        }
        LangChainEvidenceStore.EvidenceReference evidence = evidenceStore.record(
                "read_file",
                "root=" + root + ", relativePath=" + relativePath + ", startLine=" + startLine
                        + ", endLine=" + endLine,
                text);
        if (evidence.duplicate()) {
            return List.of(TextContent.from(evidenceStore.duplicateResult(evidence).toString()));
        }
        return List.of(TextContent.from(text + "\n\n[证据 " + evidence.evidenceId()
                + "，recommendedOperation=read_evidence；后续按 nextOffset 连续分页]"));
    }

    /**
     * 使用 JMESPath 对受管 JSON 文件执行只读结构化查询。
     *
     * @param root `task-context` 或工具输出中的 fileRoot
     * @param relativePath 根目录内 JSON 文件相对路径
     * @param expression JMESPath 查询表达式
     * @param outputFormat json 或 tsv，默认 json
     * @return 查询后的紧凑 JSON 或 TSV
     * @throws Exception 文件、JSON、表达式或输出格式无效时抛出
     */
    @Tool(name = "query_json", value = {
            "使用 JMESPath 查询已注册根目录中的普通 JSON 文件，不接受 evidenceId。Evidence 必须按占位符使用 read_evidence 或 query_evidence。",
            "适合筛选数组、展开嵌套结果和字段投影，无需读取完整大文件。",
            "root 使用 task-context 或工具输出中的 fileRoot；relativePath 是根目录内相对路径；expression 使用标准 JMESPath，数组当前元素使用 @。",
            "二维数组取第一列使用 rows[][0]，截取前 60 项使用 rows[][0] | [:60]；不支持 unique()，去重优先在数据源完成。",
            "outputFormat 默认为 json；需要紧凑表格时可用 tsv，支持二维数组或同字段对象数组，例如 rows[].{time: time, message: message}。"
    })
    public String queryJson(
            @P("task-context 或工具输出中的 fileRoot") String root,
            @P("根目录内 JSON 文件相对路径") String relativePath,
            @P("标准 JMESPath 查询表达式；数组当前元素使用 @，不支持非标准扩展函数") String expression,
            @P(value = "输出格式：json 或 tsv，默认 json；tsv 支持二维数组或同字段对象数组", required = false) String outputFormat
    ) throws Exception {
        context.beginToolCall("query_json");
        Path fileRoot = files.resolveRoot(root);
        Path file = files.resolveFile(fileRoot, relativePath);
        long size = Files.size(file);
        long maxBytes = Math.max(1L, properties.getJsonQueryMaxBytes());
        if (size <= 0 || size > maxBytes) {
            throw new IllegalArgumentException("JSON 文件大小不在查询允许范围内：" + size + " bytes");
        }

        JsonNode document;
        try (InputStream input = Files.newInputStream(file)) {
            document = objectMapper.readTree(input);
        } catch (Exception exception) {
            throw new IllegalArgumentException("JSON 文件解析失败：" + relativePath, exception);
        }
        if (document == null) {
            throw new IllegalArgumentException("JSON 文件内容为空：" + relativePath);
        }

        LangChainJsonQueryKit.QueryResult result = jsonQueryKit.query(document, expression, outputFormat);
        return limitJsonQueryOutput(root, relativePath, expression, result.outputFormat(), result.output());
    }

    private String limitJsonQueryOutput(String root,
                                        String relativePath,
                                        String expression,
                                        String format,
                                        String output) throws Exception {
        int limit = Math.max(1_000, properties.getToolOutputMaxChars());
        if (output.length() <= limit) {
            return output;
        }
        LangChainEvidenceStore.EvidenceReference evidence = evidenceStore.record(
                "query_json",
                "root=" + root + ", relativePath=" + relativePath + ", expression=" + expression
                        + ", outputFormat=" + format,
                output);
        if (evidence.duplicate()) {
            return objectMapper.writeValueAsString(evidenceStore.duplicateResult(evidence));
        }
        JsonNode parsed = format.equals("json") ? objectMapper.readTree(output) : null;
        ObjectNode summary = LangChainToolOutputKit.summarize(
                objectMapper,
                output,
                parsed,
                null,
                Math.min(limit, Math.max(0, properties.getToolOutputPreviewChars()))
        );
        evidenceStore.annotate(summary, evidence);
        summary.put("nextAction", switch (format) {
            case "json" -> "按 recommendedOperation 使用 evidenceId 调用 query_evidence，并用更精确的 JMESPath 筛选、投影或切片缩小结果。";
            case "tsv" -> "原 TSV 查询结果过大，优先使用更精确的 JMESPath 重新调用 query_json；确需读取当前结果时按 recommendedOperation 分页。";
            default -> throw new IllegalStateException("未知 JSON 查询输出格式：" + format);
        });
        return objectMapper.writeValueAsString(summary);
    }

    private String findRegularFiles(Path root, String glob) throws Exception {
        PathMatcher matcher = pathMatcher(glob);
        int limit = Math.max(1, properties.getToolOutputMaxChars());
        StringBuilder output = new StringBuilder(Math.min(limit, 16_384));
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> iterator = paths.filter(Files::isRegularFile)
                    .filter(candidate -> isVisible(root, candidate)).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                Path relative = root.relativize(path);
                if (!matches(matcher, glob, relative)) {
                    continue;
                }
                String line = relative + "\n";
                if (output.length() + line.length() > limit) {
                    output.append("[结果已截断]");
                    break;
                }
                output.append(line);
            }
        }
        return output.isEmpty() ? "未找到匹配文件" : output.toString().stripTrailing();
    }

    private String searchRegularFiles(Path root, String query, String includeGlob, boolean literal) throws Exception {
        java.util.regex.Pattern pattern = literal
                ? java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(query))
                : java.util.regex.Pattern.compile(query);
        PathMatcher matcher = pathMatcher(includeGlob);
        int limit = Math.max(1, properties.getToolOutputMaxChars());
        StringBuilder output = new StringBuilder(Math.min(limit, 16_384));
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> iterator = paths.filter(Files::isRegularFile)
                    .filter(candidate -> isVisible(root, candidate)).iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                Path relative = root.relativize(file);
                if (!matches(matcher, includeGlob, relative) || imageMimeType(file) != null) {
                    continue;
                }
                int lineNumber = 0;
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        if (!pattern.matcher(line).find()) {
                            continue;
                        }
                        String result = relative + ":" + lineNumber + ":" + line + "\n";
                        if (output.length() + result.length() > limit) {
                            output.append("[结果已截断]");
                            return output.toString().stripTrailing();
                        }
                        output.append(result);
                    }
                } catch (java.nio.charset.MalformedInputException ignored) {
                    // 非 UTF-8 二进制文件不参与文本搜索。
                }
            }
        }
        return output.isEmpty() ? "未找到匹配内容" : output.toString().stripTrailing();
    }

    private String readText(Path file, Integer startLine, Integer endLine) throws Exception {
        int start = startLine == null ? 1 : startLine;
        int end = endLine == null ? Integer.MAX_VALUE : endLine;
        if (start < 1 || end < start) {
            throw new IllegalArgumentException("读取行号范围无效：" + start + "-" + end);
        }
        int hardLimit = Math.max(1, properties.getWorkspaceFileMaxChars());
        int limit = endLine == null
                ? Math.min(hardLimit, Math.max(1_000, properties.getToolOutputMaxChars()))
                : hardLimit;
        StringBuilder content = new StringBuilder(Math.min(limit, 16_384));
        int lineNumber = 0;
        boolean truncated = false;
        int nextStartLine = -1;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < start) {
                    continue;
                }
                if (lineNumber > end) {
                    break;
                }
                String rendered = lineNumber + "\t" + line + "\n";
                if (content.length() + rendered.length() > limit) {
                    if (content.isEmpty() && endLine == null && rendered.length() <= hardLimit) {
                        content.append(rendered);
                        continue;
                    }
                    truncated = true;
                    if (content.isEmpty()) {
                        content.append(rendered, 0, Math.min(hardLimit, rendered.length()));
                    } else {
                        nextStartLine = lineNumber;
                    }
                    break;
                }
                content.append(rendered);
            }
        } catch (java.nio.charset.MalformedInputException exception) {
            throw new IllegalArgumentException("不支持读取非 UTF-8 二进制文件", exception);
        }
        if (truncated) {
            if (endLine == null && nextStartLine > 0) {
                content.append("[内容已分页，请使用 startLine=")
                        .append(nextStartLine)
                        .append(" 继续读取]");
            } else if (endLine == null) {
                content.append("[当前单行已达到读取硬上限，请使用 grep 定位所需内容]");
            } else {
                content.append("[文件内容已达到读取硬上限，请缩小行号范围]");
            }
        }
        return content.isEmpty() ? "文件在指定行号范围内没有内容" : content.toString().stripTrailing();
    }

    private PathMatcher pathMatcher(String glob) {
        return glob == null || glob.isBlank() ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob.trim());
    }

    private boolean matches(PathMatcher matcher, String glob, Path path) {
        if (matcher == null || matcher.matches(path)) {
            return true;
        }
        String relaxed = glob;
        int recursiveDirectory = relaxed == null ? -1 : relaxed.indexOf("**/");
        while (recursiveDirectory >= 0) {
            relaxed = relaxed.substring(0, recursiveDirectory) + relaxed.substring(recursiveDirectory + 3);
            if (FileSystems.getDefault().getPathMatcher("glob:" + relaxed).matches(path)) {
                return true;
            }
            recursiveDirectory = relaxed.indexOf("**/");
        }
        return false;
    }

    private boolean isGitWorktree(Path root) {
        return Files.exists(root.resolve(".git"));
    }

    private boolean isVisible(Path root, Path file) {
        Path relative = root.relativize(file);
        return !Files.isSymbolicLink(file)
                && !relative.startsWith(".git")
                && !files.isRuntimePath(root, file);
    }

    private String imageMimeType(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".png")) {
            return "image/png";
        }
        if (filename.endsWith(".webp")) {
            return "image/webp";
        }
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return null;
    }

    private String requireGitResult(Path root, List<String> arguments, String action) throws Exception {
        LangChainProcessRunner.ProcessResult result = runGit(root, arguments);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(action + "失败：" + result.output().trim());
        }
        return result.output().isBlank() ? "未找到匹配文件" : result.output().stripTrailing();
    }

    private LangChainProcessRunner.ProcessResult runGit(Path root, List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(arguments);
        return LangChainProcessRunner.execute(
                command, root, Map.of(), SEARCH_TIMEOUT, properties.getToolOutputMaxChars(), context);
    }
}
