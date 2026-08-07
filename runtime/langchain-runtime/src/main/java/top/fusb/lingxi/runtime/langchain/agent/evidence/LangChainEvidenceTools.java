package top.fusb.lingxi.runtime.langchain.agent.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import top.fusb.lingxi.runtime.langchain.util.LangChainJsonQueryKit;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.TokenCountEstimator;

public final class LangChainEvidenceTools {

    static final int DEFAULT_PAGE_CHARS = 4_000;
    static final int MAX_PAGE_CHARS = 6_000;
    static final int MAX_RESPONSE_CHARS = 7_500;

    private final LangChainRuntimeProperties properties;
    private final LangChainExecutionContext context;
    private final LangChainEvidenceStore evidenceStore;
    private final ObjectMapper objectMapper;
    private final TokenCountEstimator tokenEstimator;
    private final LangChainJsonQueryKit jsonQueryKit;

    public LangChainEvidenceTools(LangChainRuntimeProperties properties,
                                  LangChainExecutionContext context,
                                  LangChainEvidenceStore evidenceStore,
                                  ObjectMapper objectMapper,
                                  TokenCountEstimator tokenEstimator) {
        this.properties = properties;
        this.context = context;
        this.evidenceStore = evidenceStore;
        this.objectMapper = objectMapper;
        this.tokenEstimator = tokenEstimator;
        this.jsonQueryKit = new LangChainJsonQueryKit(objectMapper);
    }

    /**
     * 按字符分页读取运行时归档的文本工具结果。
     *
     * @param evidenceId 工具结果占位符中的 evidenceId
     * @param offset 可选字符偏移，从 0 开始，默认 0
     * @param maxChars 可选最大返回字符数，受运行时工具输出上限约束
     * @return 包含正文、完整性信息和下一页游标的 JSON
     * @throws Exception 证据不存在、分页参数无效、内容损坏或序列化失败时抛出
     */
    @Tool(name = "read_evidence", value = {
            "按字符分页读取已从活动上下文归档的文本工具结果。",
            "默认读取 4000 字符、单页最多 6000 字符；后续只使用返回的 nextOffset 连续读取。",
            "JSON Evidence 应使用 query_evidence 精确查询，不要用本工具读取完整 JSON。"
    })
    public String readEvidence(
            @P("工具结果占位符中的 evidenceId") String evidenceId,
            @P(value = "从 0 开始的字符偏移，默认 0", required = false) Integer offset,
            @P(value = "单页最多读取字符数，默认 4000，超过 6000 时按 6000 处理", required = false) Integer maxChars
    ) throws Exception {
        context.beginEvidenceRead();
        int requested = maxChars == null ? DEFAULT_PAGE_CHARS : maxChars;
        if (requested < 1) {
            throw new IllegalArgumentException("maxChars 必须大于 0");
        }
        requested = Math.min(requested, MAX_PAGE_CHARS);
        LangChainEvidenceStore.EvidenceSlice slice = evidenceStore.read(
                evidenceId, offset == null ? 0 : offset, requested);
        int maximumResponseTokens = Math.max(1, properties.getActiveToolResultMaxTokens());
        String response = serializeSlice(slice, slice.content().length());
        if (response.length() <= MAX_RESPONSE_CHARS
                && tokenEstimator.estimateTokenCountInText(response) <= maximumResponseTokens) {
            return response;
        }

        int low = 0;
        int high = slice.content().length();
        String boundedResponse = serializeSlice(slice, 0);
        if (boundedResponse.length() > MAX_RESPONSE_CHARS
                || tokenEstimator.estimateTokenCountInText(boundedResponse) > maximumResponseTokens) {
            throw new IllegalStateException("read_evidence 响应元数据超过活动工具结果 Token 上限");
        }
        while (low <= high) {
            int visibleChars = low + (high - low) / 2;
            String candidate = serializeSlice(slice, visibleChars);
            if (candidate.length() <= MAX_RESPONSE_CHARS
                    && tokenEstimator.estimateTokenCountInText(candidate) <= maximumResponseTokens) {
                boundedResponse = candidate;
                low = visibleChars + 1;
            } else {
                high = visibleChars - 1;
            }
        }
        return boundedResponse;
    }

    /**
     * 使用 JMESPath 查询运行时归档的 JSON 工具结果。
     *
     * @param evidenceId 工具结果占位符中的 evidenceId
     * @param expression 标准 JMESPath 表达式
     * @param outputFormat 可选 json 或 tsv 输出格式，默认 json
     * @return 包含查询结果和证据完整性信息的有界 JSON
     * @throws Exception 证据不存在、内容不是 JSON、表达式无效或序列化失败时抛出
     */
    @Tool(name = "query_evidence", value = {
            "使用标准 JMESPath 查询 JSON Evidence；先依据摘要中的 valueType、itemType 和 itemFields 构造表达式，数组当前元素使用 @。",
            "适合筛选、字段投影和切片；outputFormat=tsv 时支持二维数组或同字段对象数组。",
            "只接受 evidenceId、expression 和 outputFormat，不要传分页参数，也不要改用 query_json、read_file 或重新执行原工具。"
    })
    public String queryEvidence(
            @P("工具结果占位符中的 evidenceId") String evidenceId,
            @P("标准 JMESPath 表达式；数组当前元素使用 @，不支持非标准扩展函数") String expression,
            @P(value = "输出格式：json 或 tsv，默认 json；tsv 支持二维数组或同字段对象数组", required = false) String outputFormat
    ) throws Exception {
        context.beginEvidenceRead();
        LangChainEvidenceStore.EvidenceDocument evidence = evidenceStore.readAll(evidenceId);
        long bytes = evidence.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes <= 0L || bytes > Math.max(1L, properties.getJsonQueryMaxBytes())) {
            throw new IllegalArgumentException("Evidence 大小不在 JSON 查询允许范围内：" + bytes + " bytes");
        }
        JsonNode document;
        try {
            document = objectMapper.readTree(evidence.content());
        } catch (Exception exception) {
            throw new IllegalArgumentException("当前 Evidence 不是合法 JSON，请使用 read_evidence", exception);
        }
        if (document == null) {
            throw new IllegalArgumentException("当前 Evidence 内容为空");
        }
        LangChainJsonQueryKit.QueryResult query = jsonQueryKit.query(document, expression, outputFormat);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("evidenceId", evidence.evidenceId());
        response.put("sha256", evidence.sha256());
        response.put("operation", "query");
        response.put("expression", expression);
        response.put("outputFormat", query.outputFormat());
        if (query.outputFormat().equals("json")) {
            response.set("result", objectMapper.readTree(query.output()));
        } else {
            response.put("result", query.output());
        }
        String serialized = objectMapper.writeValueAsString(response);
        int maximumResponseTokens = Math.max(1, properties.getActiveToolResultMaxTokens());
        if (serialized.length() <= MAX_RESPONSE_CHARS
                && tokenEstimator.estimateTokenCountInText(serialized) <= maximumResponseTokens) {
            return serialized;
        }
        ObjectNode oversized = objectMapper.createObjectNode();
        oversized.put("ok", false);
        oversized.put("evidenceId", evidence.evidenceId());
        oversized.put("operation", "query");
        oversized.put("reason", "query_result_too_large");
        oversized.put("resultChars", query.output().length());
        oversized.put("nextAction", "缩小 JMESPath 的筛选范围、字段投影或切片后重试，不要读取完整 Evidence。");
        return objectMapper.writeValueAsString(oversized);
    }

    /**
     * 将证据切片序列化为带连续分页游标的 JSON 响应。
     *
     * @param slice 已完成完整性校验的证据切片
     * @param visibleChars 本次响应实际包含的正文字符数
     * @return 包含证据标识、完整性信息、分页游标和正文的 JSON
     * @throws Exception JSON 序列化失败时抛出
     */
    private String serializeSlice(LangChainEvidenceStore.EvidenceSlice slice,
                                  int visibleChars) throws Exception {
        int endOffset = slice.offset() + visibleChars;
        ObjectNode result = objectMapper.createObjectNode();
        result.put("evidenceId", slice.evidenceId());
        result.put("sha256", slice.sha256());
        result.put("totalChars", slice.totalChars());
        result.put("offset", slice.offset());
        result.put("endOffset", endOffset);
        if (endOffset >= slice.totalChars()) {
            result.putNull("nextOffset");
        } else {
            result.put("nextOffset", endOffset);
        }
        result.put("content", slice.content().substring(0, visibleChars));
        return objectMapper.writeValueAsString(result);
    }
}
