package top.fusb.lingxi.runtime.langchain.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainFileToolsTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private Path repository;
    private Path taskContextRoot;
    private Path runtimeRoot;
    private Path evidenceRoot;
    private RuntimeWorkspaceLayout layout;
    private LangChainManagedFileRegistry registry;
    private LangChainFileTools tools;
    private LangChainEvidenceStore evidenceStore;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        workspace = tempDir.resolve("workspace");
        taskContextRoot = workspace.resolve("backend-context");
        Path runtimeInputRoot = taskContextRoot.resolve("inputs");
        Path artifactsRoot = taskContextRoot.resolve("artifacts");
        runtimeRoot = taskContextRoot.resolve("runtime-data");
        Path runtimeStateRoot = runtimeRoot.resolve("langchain-state");
        Path privateRuntimeRoot = runtimeRoot.resolve("private");
        Files.createDirectories(runtimeInputRoot);
        Files.createDirectories(artifactsRoot);
        Files.createDirectories(runtimeStateRoot);
        Files.createDirectories(privateRuntimeRoot);
        evidenceRoot = runtimeStateRoot.resolve("evidence");
        repository = workspace.resolve(".agent-worktrees/task-1/repository");
        Files.createDirectories(repository);
        git(repository, "init");
        git(repository, "config", "user.name", "Test User");
        git(repository, "config", "user.email", "test@example.com");
        Files.createDirectories(repository.resolve("src/main/java/demo"));
        Files.writeString(repository.resolve("src/main/java/demo/SampleService.java"), """
                package demo;

                class SampleService {
                    void executeOrder() {
                        updateStatus();
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("README.md"), "sample repository\n", StandardCharsets.UTF_8);
        git(repository, "add", ".");
        git(repository, "commit", "-m", "initial");

        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setSharedRepositoryCheckoutRoot(tempDir.resolve("shared").toString());
        properties.setToolOutputMaxChars(20_000);
        layout = new RuntimeWorkspaceLayout(
                workspace.toString(), taskContextRoot.toString(), runtimeInputRoot.toString(),
                artifactsRoot.toString(), runtimeRoot.toString(), runtimeStateRoot.toString(),
                privateRuntimeRoot.toString());
        LangChainExecutionContext context = new LangChainExecutionContext(
                "file-tools", layout, emptyListener(), null, null,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 0L);
        evidenceStore = new LangChainEvidenceStore(evidenceRoot, objectMapper = new ObjectMapper());
        registry = new LangChainManagedFileRegistry(properties, context);
        registry.registerExternalRoot(repository.toString(), true);
        tools = new LangChainFileTools(
                properties, context, registry, evidenceStore, objectMapper, true);
    }

    @Test
    void exposesOneGenericToolSetForAllManagedFiles() {
        String schemas = ToolSpecifications.toolSpecificationsFrom(LangChainFileTools.class).toString();

        assertThat(schemas).contains("glob", "grep", "read_file", "query_json", "root", "pattern",
                        "relativePath", "expression", "outputFormat", "rows[][0]", "unique()")
                .doesNotContain("find_files", "search_files")
                .doesNotContain("repository", "source", "image");
    }

    @Test
    void findsAndSearchesFilesInTaskAndCodeRoots() throws Exception {
        Path evidence = taskContextRoot.resolve("evidence");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("result.json"), "{\"status\":\"SUCCESS\"}\n");

        assertThat(tools.glob(LangChainManagedFileRegistry.TASK_ROOT, "evidence/**/*.json").replace('\\', '/'))
                .contains("evidence/result.json");
        assertThat(tools.grep(LangChainManagedFileRegistry.TASK_ROOT, "SUCCESS", "**/*.json", true).replace('\\', '/'))
                .contains("evidence/result.json:1:");
        assertThat(tools.glob(repository.toString(), "**/*.java"))
                .contains("src/main/java/demo/SampleService.java").doesNotContain("README.md");
        assertThat(tools.grep(repository.toString(), "executeOrder", "**/*.java", true))
                .contains("src/main/java/demo/SampleService.java:4:");
    }

    @Test
    void readsTaskAndCodeTextThroughTheSameTool() throws Exception {
        Files.writeString(taskContextRoot.resolve("context.md"), "task context\n");

        List<Content> taskContent = tools.readFile(LangChainManagedFileRegistry.TASK_ROOT, "context.md", null, null);
        List<Content> codeContent = tools.readFile(
                repository.toString(), "src/main/java/demo/SampleService.java", 3, 5);

        assertThat(((TextContent) taskContent.get(0)).text()).isEqualTo("1\ttask context");
        assertThat(((TextContent) codeContent.get(0)).text()).isEqualTo("""
                3\tclass SampleService {
                4\t    void executeOrder() {
                5\t        updateStatus();""");
    }

    @Test
    void readsImagesThroughTheSameFileTool() throws Exception {
        Path image = taskContextRoot.resolve("evidence/frame.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});

        List<Content> content = tools.readFile(LangChainManagedFileRegistry.TASK_ROOT, "evidence/frame.png", null, null);

        assertThat(content).singleElement().isInstanceOf(ImageContent.class);
        assertThat(((ImageContent) content.get(0)).image().mimeType()).isEqualTo("image/png");
        assertThat(((ImageContent) content.get(0)).detailLevel()).isEqualTo(ImageContent.DetailLevel.HIGH);
    }

    @Test
    void preservesImageContentThroughLangChainToolExecution() throws Exception {
        Path image = taskContextRoot.resolve("evidence/frame.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});
        DefaultToolExecutor executor = new DefaultToolExecutor(
                tools,
                LangChainFileTools.class.getMethod(
                        "readFile", String.class, String.class, Integer.class, Integer.class));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("read-image")
                .name("read_file")
                .arguments("""
                        {"root":"task-context","relativePath":"evidence/frame.png"}
                        """)
                .build();

        ToolExecutionResult result = executor.executeWithContext(request, null);

        assertThat(result.isError()).isFalse();
        assertThat(result.resultContents()).singleElement().isInstanceOf(ImageContent.class);
        assertThat(((ImageContent) result.resultContents().get(0)).image().mimeType()).isEqualTo("image/png");
    }

    @Test
    void rejectsImageContentWhenModelDoesNotSupportImages() throws Exception {
        Path image = taskContextRoot.resolve("evidence/frame.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});
        LangChainFileTools textOnlyTools = new LangChainFileTools(
                new LangChainRuntimeProperties(),
                new LangChainExecutionContext(
                        "text-only", layout, emptyListener(), null, null,
                        Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 0L),
                registry,
                new LangChainEvidenceStore(runtimeRoot.resolve("text-evidence"), objectMapper),
                objectMapper,
                false);

        assertThatThrownBy(() -> textOnlyTools.readFile(
                LangChainManagedFileRegistry.TASK_ROOT, "evidence/frame.png", null, null))
                .hasMessageContaining("未启用图片输入");
    }

    @Test
    void rejectsUnregisteredRootsEscapingPathsAndPrivateFiles() throws Exception {
        Path arbitrary = tempDir.resolve("arbitrary");
        Files.createDirectories(arbitrary);
        Files.writeString(arbitrary.resolve("outside.txt"), "outside");
        Files.writeString(runtimeRoot.resolve("langchain-state/chat-memory.json"), "secret");

        assertThatThrownBy(() -> tools.readFile(arbitrary.toString(), "outside.txt", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.readFile(LangChainManagedFileRegistry.TASK_ROOT, "../../outside.txt", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tools.glob(LangChainManagedFileRegistry.TASK_ROOT, "**/*"))
                .doesNotContain("runtime-data/langchain-state/chat-memory.json");
        assertThatThrownBy(() -> tools.readFile(
                LangChainManagedFileRegistry.TASK_ROOT, "runtime-data/langchain-state/chat-memory.json", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.readFile(repository.toString(), ".git/config", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSymbolicLinks() throws Exception {
        Path link = repository.resolve("source-link.java");
        try {
            Files.createSymbolicLink(link, Path.of("src/main/java/demo/SampleService.java"));
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            return;
        }

        assertThatThrownBy(() -> tools.readFile(repository.toString(), "source-link.java", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paginatesUnboundedReadsButPreservesExplicitRanges() throws Exception {
        Path report = taskContextRoot.resolve("report.txt");
        String content = java.util.stream.IntStream.rangeClosed(1, 500)
                .mapToObj(line -> "line-" + line + "-" + "x".repeat(80))
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(report, content);

        String paged = ((TextContent) tools.readFile(LangChainManagedFileRegistry.TASK_ROOT, "report.txt", null, null).get(0)).text();
        String explicit = ((TextContent) tools.readFile(LangChainManagedFileRegistry.TASK_ROOT, "report.txt", 1, 500).get(0)).text();

        assertThat(paged).contains("内容已分页", "startLine=").doesNotContain("500\tline-500-");
        assertThat(explicit).contains("1\tline-1-", "500\tline-500-").doesNotContain("内容已分页");
    }

    @Test
    void referencesPreviouslyObservedLargeFileContentOnlyAfterReadingItAgain() throws Exception {
        Path report = taskContextRoot.resolve("large-report.txt");
        Files.writeString(report, "evidence-line\n".repeat(3_000));

        String first = ((TextContent) tools.readFile(LangChainManagedFileRegistry.TASK_ROOT, "large-report.txt", 1, 3_000).get(0)).text();
        String second = ((TextContent) tools.readFile(LangChainManagedFileRegistry.TASK_ROOT, "large-report.txt", 1, 3_000).get(0)).text();

        assertThat(first).contains("evidence-line", "[证据 ev-", "read_evidence");
        var duplicate = new ObjectMapper().readTree(second);
        assertThat(duplicate.path("executed").asBoolean()).isTrue();
        assertThat(duplicate.path("deduplicated").asBoolean()).isTrue();
        assertThat(duplicate.path("evidenceId").asText()).startsWith("ev-");
        assertThat(evidenceStore.readAll(duplicate.path("evidenceId").asText()).content()).isEqualTo("1\tevidence-line\n"
                + java.util.stream.IntStream.rangeClosed(2, 3_000)
                        .mapToObj(line -> line + "\tevidence-line\n")
                        .collect(java.util.stream.Collectors.joining()).stripTrailing());
    }

    @Test
    void queriesAndProjectsLargeSingleLineJsonWithoutReadingTheWholeFile() throws Exception {
        Path output = taskContextRoot.resolve("artifacts/capability-outputs/wiki.source-search-batch.json");
        Files.createDirectories(output.getParent());
        var root = objectMapper.createObjectNode();
        var queries = root.putArray("queries");
        for (int index = 0; index < 200; index++) {
            var query = queries.addObject().put("query", "query-" + index);
            query.putArray("results").addObject()
                    .put("sourceDocumentId", "source-" + index)
                    .put("originalFilename", "document-" + index + ".md");
        }
        var selected = queries.addObject().put("query", "示例客户甲");
        var match = selected.putArray("results").addObject()
                .put("sourceDocumentId", "source-example")
                .put("latestSnapshot", true)
                .put("originalFilename", "customer.md")
                .put("treePath", "示例分类/客户资料");
        match.putArray("matches").addObject()
                .put("headingPath", "客户/示例客户甲")
                .put("snippet", "示例客户资料摘要");
        Files.writeString(output, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);

        String expression = "queries[?query == '示例客户甲'].results[] | []."
                + "[sourceDocumentId, latestSnapshot, originalFilename, treePath, "
                + "matches[0].headingPath, matches[0].snippet]";

        String result = tools.queryJson(
                LangChainManagedFileRegistry.TASK_ROOT,
                "artifacts/capability-outputs/wiki.source-search-batch.json",
                expression,
                "tsv");

        assertThat(result).isEqualTo(
                "source-example\ttrue\tcustomer.md\t示例分类/客户资料\t客户/示例客户甲\t示例客户资料摘要");
    }

    @Test
    void returnsJsonByDefaultAndExternalizesOnlyOversizedQueryResults() throws Exception {
        Path output = taskContextRoot.resolve("large.json");
        var root = objectMapper.createObjectNode();
        var values = root.putArray("items");
        for (int index = 0; index < 4_000; index++) {
            values.add("item-" + index);
        }
        Files.writeString(output, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);

        String projected = tools.queryJson(LangChainManagedFileRegistry.TASK_ROOT, "large.json", "items[0:2]", null);
        JsonNode oversized = objectMapper.readTree(
                tools.queryJson(LangChainManagedFileRegistry.TASK_ROOT, "large.json", "items", "json"));

        assertThat(projected).isEqualTo("[\"item-0\",\"item-1\"]");
        assertThat(oversized.path("truncated").asBoolean()).isTrue();
        assertThat(oversized.path("evidenceId").asText()).startsWith("ev-");
        assertThat(oversized.path("nextAction").asText()).contains("JMESPath");
        assertThat(evidenceStore.readAll(oversized.path("evidenceId").asText()).content())
                .startsWith("[\"item-0\"");
        assertThatThrownBy(() -> tools.readFile("runtime-evidence", "index.json", null, null))
                .hasMessageContaining("未由当前任务注册");
    }

    @Test
    void preservesEmptyTrailingTsvColumns() throws Exception {
        Path output = taskContextRoot.resolve("rows.json");
        Files.writeString(output, "{\"rows\":[[\"first\",null],[\"second\",\"value\"]]}",
                StandardCharsets.UTF_8);

        String result = tools.queryJson(LangChainManagedFileRegistry.TASK_ROOT, "rows.json", "rows", "tsv");

        assertThat(result).isEqualTo("first\t\nsecond\tvalue");
    }

    @Test
    void rejectsInvalidJsonQueriesAndFilesOverTheConfiguredLimit() throws Exception {
        Files.writeString(taskContextRoot.resolve("invalid.json"), "not-json", StandardCharsets.UTF_8);
        Files.writeString(taskContextRoot.resolve("oversized.json"), "{\"value\":1}", StandardCharsets.UTF_8);
        LangChainRuntimeProperties limitedProperties = new LangChainRuntimeProperties();
        limitedProperties.setSharedRepositoryCheckoutRoot(tempDir.resolve("shared-limited").toString());
        limitedProperties.setJsonQueryMaxBytes(5);
        LangChainExecutionContext context = new LangChainExecutionContext(
                "limited-json", layout, emptyListener(), null, null,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 0L);
        LangChainManagedFileRegistry limitedRegistry = new LangChainManagedFileRegistry(limitedProperties, context);
        LangChainFileTools limitedTools = new LangChainFileTools(
                limitedProperties, context, limitedRegistry,
                new LangChainEvidenceStore(runtimeRoot.resolve("limited-evidence"), objectMapper), objectMapper, true);

        assertThatThrownBy(() -> tools.queryJson(LangChainManagedFileRegistry.TASK_ROOT, "invalid.json", "value", "json"))
                .hasMessageContaining("JSON 文件解析失败");
        assertThatThrownBy(() -> tools.queryJson(LangChainManagedFileRegistry.TASK_ROOT, "oversized.json", "[", "json"))
                .hasMessageContaining("JMESPath 查询失败");
        assertThatThrownBy(() -> tools.queryJson(LangChainManagedFileRegistry.TASK_ROOT, "oversized.json", "value", "csv"))
                .hasMessageContaining("只支持 json 或 tsv");
        assertThatThrownBy(() -> limitedTools.queryJson(
                LangChainManagedFileRegistry.TASK_ROOT, "oversized.json", "value", "json"))
                .hasMessageContaining("大小不在查询允许范围内");
        assertThatThrownBy(() -> tools.queryJson(
                LangChainManagedFileRegistry.TASK_ROOT, "runtime-data/private/secret.json", "value", "json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RuntimeEventListener emptyListener() {
        return event -> {
        };
    }

    private void git(Path directory, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed = process.waitFor(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!completed || process.exitValue() != 0) {
            throw new IllegalStateException("Git test setup failed: " + output);
        }
    }
}
