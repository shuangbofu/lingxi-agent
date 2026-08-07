package top.fusb.lingxi.runtime.langchain.agent.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.fusb.lingxi.runtime.langchain.util.LangChainToolOutputKit;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class LangChainEvidenceStore {

    private static final int CATALOG_VERSION = 1;
    private static final int MAX_SOURCE_CHARS = 500;
    private static final String INDEX_FILENAME = "index.json";
    private static final String EVIDENCE_REFERENCE_KIND = "langchain.evidence_reference";
    private static final String ACTIVE_RESULT_REFERENCE_KIND = "langchain.active_tool_result_evidence";
    private static final String EVIDENCE_READ_INSTRUCTIONS =
            "需要完整内容时按 recommendedOperation 调用工具；JSON 使用 query_evidence 和 JMESPath，文本使用 read_evidence 并沿连续分页游标读取。";
    private static final String EVIDENCE_ID_PATTERN = "ev-[0-9a-f]{16}";
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";
    private final Path evidenceDirectory;
    private final Path indexFile;
    private final ObjectMapper objectMapper;
    private final Map<String, EvidenceEntry> entries = new LinkedHashMap<>();

    public LangChainEvidenceStore(Path evidenceDirectory, ObjectMapper objectMapper) {
        this.evidenceDirectory = resolveEvidenceDirectory(evidenceDirectory);
        this.indexFile = this.evidenceDirectory.resolve(INDEX_FILENAME);
        this.objectMapper = objectMapper;
        try {
            secureCreateEvidenceDirectory();
        } catch (Exception exception) {
            throw new IllegalStateException("初始化 LangChain 上下文证据目录失败：" + evidenceDirectory, exception);
        }
        load();
    }

    /**
     * 保存一次已经真实执行的超长工具结果，并按内容哈希复用已有证据文件。
     *
     * @param toolName 产生结果的工具名称
     * @param source 工具参数或文件位置的脱敏摘要
     * @param content 工具返回的完整文本
     * @return 证据标识、文件路径和本次内容是否重复
     * @throws IllegalStateException 证据文件或索引无法写入时抛出
     */
    public synchronized EvidenceReference record(String toolName, String source, String content) {
        if (content == null) {
            throw new IllegalArgumentException("证据内容不能为空");
        }
        try {
            String sha256 = sha256(content);
            EvidenceEntry existing = entries.get(sha256);
            String observedAt = Instant.now().toString();
            if (existing != null) {
                secureCreateEvidenceDirectory();
                Path outputFile = evidenceFile(existing.evidenceId());
                if (!Files.isRegularFile(outputFile, LinkOption.NOFOLLOW_LINKS)
                        || !sha256.equals(sha256(Files.readString(outputFile, StandardCharsets.UTF_8)))) {
                    writeContent(outputFile, content);
                }
                EvidenceEntry updated = new EvidenceEntry(
                        existing.evidenceId(), existing.sha256(), outputFile.getFileName().toString(),
                        existing.originalChars(),
                        existing.toolName(), existing.firstSource(), sourceSummary(source),
                        existing.firstObservedAt(), observedAt, existing.observationCount() + 1);
                entries.put(sha256, updated);
                writeIndex();
                return reference(updated, true);
            }

            secureCreateEvidenceDirectory();
            String evidenceId = "ev-" + sha256.substring(0, 16);
            boolean idCollision = entries.values().stream()
                    .anyMatch(entry -> evidenceId.equals(entry.evidenceId()) && !sha256.equals(entry.sha256()));
            if (idCollision) {
                throw new IllegalStateException("上下文证据标识发生哈希前缀冲突：" + evidenceId);
            }
            Path outputFile = evidenceFile(evidenceId);
            if (!Files.isRegularFile(outputFile, LinkOption.NOFOLLOW_LINKS)
                    || !sha256.equals(sha256(Files.readString(outputFile, StandardCharsets.UTF_8)))) {
                writeContent(outputFile, content);
            }
            EvidenceEntry created = new EvidenceEntry(
                    evidenceId,
                    sha256,
                    outputFile.getFileName().toString(),
                    content.length(),
                    toolName == null ? "unknown" : toolName,
                    sourceSummary(source),
                    sourceSummary(source),
                    observedAt,
                    observedAt,
                    1
            );
            entries.put(sha256, created);
            writeIndex();
            return reference(created, false);
        } catch (Exception exception) {
            throw new IllegalStateException("保存 LangChain 上下文证据失败", exception);
        }
    }

    public synchronized void clear() {
        try {
            secureCreateEvidenceDirectory();
            entries.clear();
            try (var files = Files.list(evidenceDirectory)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && (INDEX_FILENAME.equals(name)
                            || name.matches(EVIDENCE_ID_PATTERN + "\\.txt")
                            || name.matches("evidence-(index|content)-.*\\.tmp"))) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("清理 LangChain 上下文证据失败", exception);
        }
    }

    /**
     * 从上一会话复制证据索引及其内容寻址文件。
     *
     * @param sourceIndex 上一会话的证据索引文件
     * @return 恢复的证据数量
     * @throws IllegalStateException 索引损坏、内容哈希不匹配或文件复制失败时抛出
     */
    public synchronized int restoreFrom(Path sourceIndex) {
        Path source = sourceIndex == null ? null : sourceIndex.toAbsolutePath().normalize();
        if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            clear();
            return 0;
        }
        if (source.equals(indexFile)) {
            return entries.size();
        }
        try {
            EvidenceCatalog catalog = objectMapper.readValue(Files.readString(source), EvidenceCatalog.class);
            List<EvidenceEntry> restored = validateCatalog(catalog, source.getParent());
            clear();
            secureCreateEvidenceDirectory();
            for (EvidenceEntry entry : restored) {
                Path sourceContent = source.getParent().resolve(entry.evidenceId() + ".txt");
                String content = Files.readString(sourceContent, StandardCharsets.UTF_8);
                Path target = evidenceFile(entry.evidenceId());
                writeContent(target, content);
                EvidenceEntry normalized = new EvidenceEntry(
                        entry.evidenceId(), entry.sha256(), target.getFileName().toString(),
                        entry.originalChars(), entry.toolName(), entry.firstSource(), entry.lastSource(),
                        entry.firstObservedAt(), entry.lastObservedAt(), entry.observationCount());
                entries.put(normalized.sha256(), normalized);
            }
            writeIndex();
            return entries.size();
        } catch (Exception exception) {
            throw new IllegalStateException("恢复 LangChain 上下文证据失败：" + source, exception);
        }
    }

    public boolean isEvidenceFile(Path file) {
        Path normalized = file == null ? null : file.toAbsolutePath().normalize();
        return normalized != null && normalized.startsWith(evidenceDirectory);
    }

    /**
     * Resolve a model-facing JSON result that already references evidence in this store.
     *
     * @param content tool result that may contain a platform evidence-reference envelope
     * @return the validated reference and its original content, or empty for ordinary tool output
     * @throws IllegalStateException when the referenced evidence file is missing or corrupted
     */
    public synchronized Optional<ResolvedEvidence> resolveAnnotatedReference(String content) {
        if (content == null || !content.contains("\"kind\"")) {
            return Optional.empty();
        }
        JsonNode result;
        try {
            result = objectMapper.readTree(content);
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (result == null || !result.isObject()) {
            return Optional.empty();
        }
        JsonNode kindNode = result.get("kind");
        JsonNode evidenceIdNode = result.get("evidenceId");
        JsonNode sha256Node = result.get("sha256");
        JsonNode originalCharsNode = result.get("originalChars");
        boolean evidenceEnvelope = kindNode != null && kindNode.isTextual()
                && (EVIDENCE_REFERENCE_KIND.equals(kindNode.textValue())
                || ACTIVE_RESULT_REFERENCE_KIND.equals(kindNode.textValue()));
        if (!evidenceEnvelope) {
            return Optional.empty();
        }
        if (evidenceIdNode == null || !evidenceIdNode.isTextual()
                || sha256Node == null || !sha256Node.isTextual()
                || originalCharsNode == null || !originalCharsNode.isIntegralNumber()) {
            throw new IllegalStateException("平台 Evidence 引用字段无效");
        }
        String evidenceId = evidenceIdNode.textValue();
        String sha256 = sha256Node.textValue();
        EvidenceEntry entry = entries.get(sha256);
        if (entry == null || !entry.evidenceId().equals(evidenceId)
                || originalCharsNode.longValue() != entry.originalChars()) {
            throw new IllegalStateException("平台 Evidence 引用与当前任务证据索引不匹配");
        }
        EvidenceDocument document = readAll(evidenceId);
        return Optional.of(new ResolvedEvidence(reference(entry, false), document.content()));
    }

    public ObjectNode annotate(ObjectNode result, EvidenceReference reference) {
        if (result == null || reference == null) {
            throw new IllegalArgumentException("证据引用和响应对象不能为空");
        }
        result.put("kind", EVIDENCE_REFERENCE_KIND);
        result.put("evidenceId", reference.evidenceId());
        result.put("sha256", reference.sha256());
        result.put("originalChars", reference.originalChars());
        result.put("observationCount", reference.observationCount());
        result.put("readInstructions", EVIDENCE_READ_INSTRUCTIONS);
        return result;
    }

    public ObjectNode duplicateResult(EvidenceReference reference) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("deduplicated", true);
        result.put("executed", true);
        result.put("originalChars", reference.originalChars());
        annotate(result, reference);
        result.put("nextAction", "本次工具已经重新执行，结果与该证据完全一致；JSON 使用 query_evidence，文本使用 read_evidence，不要再次调用原工具。");
        return result;
    }

    /**
     * 生成请求投影使用的可读回工具结果占位符。
     *
     * @param reference 已归档工具结果的证据引用
     * @param toolCallId 原始工具调用 ID
     * @param toolName 原始工具名称
     * @param originalTokens 原始工具结果的估算 Token 数
     * @param content 原始工具结果正文，用于生成有界结构说明
     * @return 包含内容校验信息和按需读取指令的结构化占位符
     * @throws IllegalArgumentException 工具调用标识无效时抛出
     */
    public ObjectNode activeResultPlaceholder(EvidenceReference reference,
                                              String toolCallId,
                                              String toolName,
                                              int originalTokens,
                                              String content) {
        if (reference == null || toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("活动工具结果证据引用和调用 ID 不能为空");
        }
        if (content == null || content.length() != reference.originalChars()
                || !reference.sha256().equals(sha256(content))) {
            throw new IllegalArgumentException("活动工具结果正文与证据引用不匹配");
        }
        ObjectNode result = objectMapper.createObjectNode();
        annotate(result, reference);
        result.put("kind", ACTIVE_RESULT_REFERENCE_KIND);
        result.put("toolCallId", toolCallId);
        result.put("toolName", toolName == null ? "unknown" : toolName);
        result.put("originalEstimatedTokens", originalTokens);
        result.put("originalChars", reference.originalChars());
        ObjectNode structure = LangChainToolOutputKit.describeEvidenceStructure(objectMapper, content);
        result.set("structure", structure);
        return result;
    }

    /**
     * 按字符偏移读取一段已归档证据，并在读取前校验内容哈希。
     *
     * @param evidenceId 证据标识
     * @param offset 从 0 开始的字符偏移
     * @param maxChars 本次最多返回的字符数
     * @return 证据片段、下一页偏移和完整内容校验信息
     * @throws IllegalArgumentException 证据不存在或分页参数无效时抛出
     * @throws IllegalStateException 证据文件损坏或无法读取时抛出
     */
    public synchronized EvidenceSlice read(String evidenceId, int offset, int maxChars) {
        if (offset < 0 || maxChars < 1) {
            throw new IllegalArgumentException("证据读取 offset 不能小于 0，maxChars 必须大于 0");
        }
        EvidenceDocument document = readAll(evidenceId);
        if (offset > document.content().length()) {
            throw new IllegalArgumentException("证据读取 offset 超出内容长度：" + offset);
        }
        int end = (int) Math.min(document.content().length(), (long) offset + maxChars);
        return new EvidenceSlice(
                document.evidenceId(), document.sha256(), document.content().length(), offset, end,
                end < document.content().length() ? end : null, document.content().substring(offset, end));
    }

    /**
     * 读取并校验一份完整 Evidence，供受控查询入口处理结构化内容。
     *
     * @param evidenceId Evidence 占位符中的内容标识
     * @return 包含完整正文和完整性元数据的已校验文档
     * @throws IllegalArgumentException Evidence 不存在或标识无效时抛出
     * @throws IllegalStateException Evidence 文件不存在、损坏或无法读取时抛出
     */
    public synchronized EvidenceDocument readAll(String evidenceId) {
        EvidenceEntry entry = entries.values().stream()
                .filter(candidate -> candidate.evidenceId().equals(evidenceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("上下文证据不存在：" + evidenceId));
        try {
            Path contentFile = evidenceFile(evidenceId);
            if (!Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(contentFile)) {
                throw new IllegalStateException("上下文证据文件不存在或不是普通文件：" + evidenceId);
            }
            String content = Files.readString(contentFile, StandardCharsets.UTF_8);
            if (content.length() != entry.originalChars() || !entry.sha256().equals(sha256(content))) {
                throw new IllegalStateException("上下文证据内容校验失败：" + evidenceId);
            }
            return new EvidenceDocument(entry.evidenceId(), entry.sha256(), content);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("读取 LangChain 上下文证据失败：" + evidenceId, exception);
        }
    }

    public Path indexFile() {
        return indexFile;
    }

    public Path root() {
        return evidenceDirectory;
    }

    public static Path indexFileForMemory(Path memoryFile) {
        Path normalized = memoryFile.toAbsolutePath().normalize();
        Path runtimeStateDirectory = normalized.getParent();
        if (runtimeStateDirectory == null) {
            throw new IllegalArgumentException("无法根据会话文件定位上下文证据目录：" + memoryFile);
        }
        return runtimeStateDirectory.resolve("evidence").resolve(INDEX_FILENAME);
    }

    private void load() {
        if (!Files.isRegularFile(indexFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            EvidenceCatalog catalog = objectMapper.readValue(Files.readString(indexFile), EvidenceCatalog.class);
            validateCatalog(catalog, evidenceDirectory)
                    .forEach(entry -> entries.put(entry.sha256(), entry));
        } catch (Exception exception) {
            throw new IllegalStateException("读取 LangChain 上下文证据索引失败：" + indexFile, exception);
        }
    }

    private void writeIndex() throws Exception {
        secureCreateEvidenceDirectory();
        Path temporaryFile = Files.createTempFile(evidenceDirectory, "evidence-index-", ".json.tmp");
        Files.writeString(temporaryFile,
                objectMapper.writeValueAsString(new EvidenceCatalog(CATALOG_VERSION, new ArrayList<>(entries.values()))),
                StandardCharsets.UTF_8);
        try {
            Files.move(temporaryFile, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFile, indexFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<EvidenceEntry> validateCatalog(EvidenceCatalog catalog, Path contentDirectory) throws Exception {
        if (catalog == null || catalog.version() != CATALOG_VERSION || catalog.evidence() == null) {
            throw new IllegalStateException("上下文证据索引格式或版本无效");
        }
        Set<String> hashes = new HashSet<>();
        Set<String> evidenceIds = new HashSet<>();
        for (EvidenceEntry entry : catalog.evidence()) {
            if (entry == null || entry.sha256() == null || !entry.sha256().matches(SHA256_PATTERN)) {
                throw new IllegalStateException("上下文证据索引包含无效哈希");
            }
            String expectedId = "ev-" + entry.sha256().substring(0, 16);
            Path expectedOutput = Path.of(expectedId + ".txt");
            if (!expectedId.equals(entry.evidenceId())
                    || entry.outputFile() == null
                    || Path.of(entry.outputFile()).isAbsolute()
                    || !expectedOutput.equals(Path.of(entry.outputFile()).normalize())
                    || entry.originalChars() < 0
                    || entry.observationCount() < 1
                    || !hashes.add(entry.sha256())
                    || !evidenceIds.add(entry.evidenceId())) {
                throw new IllegalStateException("上下文证据索引条目无效：" + entry.evidenceId());
            }
            Path contentFile = contentDirectory.resolve(expectedId + ".txt").normalize();
            if (!contentFile.startsWith(contentDirectory.toAbsolutePath().normalize())
                    || !Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(contentFile)) {
                throw new IllegalStateException("上下文证据文件不存在或不是普通文件：" + expectedId);
            }
            String content = Files.readString(contentFile, StandardCharsets.UTF_8);
            if (content.length() != entry.originalChars() || !entry.sha256().equals(sha256(content))) {
                throw new IllegalStateException("上下文证据内容校验失败：" + expectedId);
            }
        }
        return catalog.evidence();
    }

    private void secureCreateEvidenceDirectory() throws Exception {
        if (Files.exists(evidenceDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(evidenceDirectory)
                    || !Files.isDirectory(evidenceDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("上下文证据目录包含符号链接或非目录节点：" + evidenceDirectory);
            }
            return;
        }
        Files.createDirectory(evidenceDirectory);
    }

    private Path evidenceFile(String evidenceId) {
        if (evidenceId == null || !evidenceId.matches(EVIDENCE_ID_PATTERN)) {
            throw new IllegalArgumentException("上下文证据标识无效：" + evidenceId);
        }
        return evidenceDirectory.resolve(evidenceId + ".txt");
    }

    private void writeContent(Path outputFile, String content) throws Exception {
        secureCreateEvidenceDirectory();
        Path temporaryFile = Files.createTempFile(evidenceDirectory, "evidence-content-", ".txt.tmp");
        Files.writeString(temporaryFile, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporaryFile, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path resolveEvidenceDirectory(Path evidenceDirectory) {
        try {
            Path normalized = evidenceDirectory.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("上下文证据目录缺少父目录：" + evidenceDirectory);
            }
            Path realParent = parent.toRealPath();
            Path resolved = realParent.resolve(normalized.getFileName());
            if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(resolved)
                        || !Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("上下文证据目录包含符号链接或非目录节点：" + resolved);
                }
                return resolved.toRealPath();
            }
            return resolved;
        } catch (Exception exception) {
            throw new IllegalStateException("LangChain 上下文证据目录无法访问：" + evidenceDirectory, exception);
        }
    }

    private String sourceSummary(String source) {
        String redacted = LangChainToolOutputKit.redactSensitiveText(source == null ? "" : source).strip();
        return redacted.substring(0, Math.min(MAX_SOURCE_CHARS, redacted.length()));
    }

    private EvidenceReference reference(EvidenceEntry entry, boolean duplicate) {
        return new EvidenceReference(
                entry.evidenceId(), entry.sha256(), entry.outputFile(), entry.originalChars(),
                entry.observationCount(), duplicate);
    }

    public static String sha256(String content) {
        if (content == null) {
            throw new IllegalArgumentException("哈希内容不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("计算上下文证据哈希失败", exception);
        }
    }

    public record EvidenceReference(
            String evidenceId,
            String sha256,
            String outputFile,
            int originalChars,
            int observationCount,
            boolean duplicate
    ) {
    }

    public record EvidenceSlice(
            String evidenceId,
            String sha256,
            int totalChars,
            int offset,
            int endOffset,
            Integer nextOffset,
            String content
    ) {
    }

    public record EvidenceDocument(
            String evidenceId,
            String sha256,
            String content
    ) {
    }

    public record ResolvedEvidence(
            EvidenceReference reference,
            String content
    ) {
    }

    public record EvidenceCatalog(int version, List<EvidenceEntry> evidence) {
    }

    public record EvidenceEntry(
            String evidenceId,
            String sha256,
            String outputFile,
            int originalChars,
            String toolName,
            String firstSource,
            String lastSource,
            String firstObservedAt,
            String lastObservedAt,
            int observationCount
    ) {
    }
}
