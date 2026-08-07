package top.fusb.lingxi.runtime.codex.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

public class CodexEventParser {

    private final ObjectMapper objectMapper;

    public CodexEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 Codex CLI 输出的一行 JSON 事件。
     *
     * @param line Codex CLI JSONL 单行
     * @return 原始 Codex 事件
     * @throws com.fasterxml.jackson.core.JsonProcessingException JSON 无法解析时抛出
     */
    public CodexCliEvent parse(String line) throws com.fasterxml.jackson.core.JsonProcessingException {
        return objectMapper.readValue(line, CodexCliEvent.class);
    }

    /**
     * 解开旧版 Codex CLI 的 event_msg 和 response_item 包装。
     *
     * @param root 原始根事件
     * @return 实际事件节点
     */
    public CodexCliEvent unwrap(CodexCliEvent root) {
        String type = root == null ? null : root.getType();
        if (("event_msg".equals(type) || "response_item".equals(type)) && root.getPayload() != null) {
            return root.getPayload();
        }
        return root;
    }
}
