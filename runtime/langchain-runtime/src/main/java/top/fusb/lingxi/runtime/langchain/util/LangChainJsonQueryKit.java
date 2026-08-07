package top.fusb.lingxi.runtime.langchain.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.burt.jmespath.Expression;
import io.burt.jmespath.RuntimeConfiguration;
import io.burt.jmespath.jackson.JacksonRuntime;

import java.util.Locale;

public final class LangChainJsonQueryKit {

    private final ObjectMapper objectMapper;
    private final JacksonRuntime runtime;

    public LangChainJsonQueryKit(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("JSON 序列化组件不能为空");
        }
        this.objectMapper = objectMapper;
        this.runtime = new JacksonRuntime(RuntimeConfiguration.defaultConfiguration(), objectMapper);
    }

    /**
     * 对已经解析的 JSON 文档执行 JMESPath，并按 JSON 或 TSV 输出结果。
     *
     * @param document 待查询的 JSON 文档
     * @param expression 标准 JMESPath 表达式
     * @param outputFormat json 或 tsv，留空时使用 json
     * @return 查询结果正文及规范化后的输出格式
     * @throws IllegalArgumentException 文档、表达式、输出格式或查询结果无效时抛出
     */
    public QueryResult query(JsonNode document, String expression, String outputFormat) {
        if (document == null) {
            throw new IllegalArgumentException("JSON 文档不能为空");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("JMESPath 表达式不能为空");
        }
        if (expression.length() > 10_000) {
            throw new IllegalArgumentException("JMESPath 表达式过长");
        }
        String format = outputFormat == null || outputFormat.isBlank()
                ? "json" : outputFormat.trim().toLowerCase(Locale.ROOT);
        if (!format.equals("json") && !format.equals("tsv")) {
            throw new IllegalArgumentException("JSON 查询输出格式只支持 json 或 tsv");
        }

        try {
            Expression<JsonNode> compiled = runtime.compile(expression);
            JsonNode result = compiled.search(document);
            JsonNode normalized = result == null ? objectMapper.nullNode() : result;
            String output = format.equals("tsv")
                    ? renderTsv(normalized) : objectMapper.writeValueAsString(normalized);
            return new QueryResult(output, format);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("JMESPath 查询失败：" + exception.getMessage(), exception);
        }
    }

    private String renderTsv(JsonNode result) throws Exception {
        StringBuilder output = new StringBuilder();
        if (result.isArray()) {
            boolean rows = !result.isEmpty();
            for (JsonNode item : result) {
                rows &= item != null && (item.isArray() || item.isObject());
            }
            if (rows) {
                for (JsonNode item : result) {
                    appendTsvRow(output, item);
                }
            } else if (!result.isEmpty()) {
                appendTsvRow(output, result);
            }
        } else {
            appendTsvRow(output, result);
        }
        if (!output.isEmpty()) {
            output.setLength(output.length() - 1);
        }
        return output.toString();
    }

    private void appendTsvRow(StringBuilder output, JsonNode row) throws Exception {
        if (row != null && row.isArray()) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) {
                    output.append('\t');
                }
                output.append(tsvCell(row.get(index)));
            }
        } else if (row != null && row.isObject()) {
            int index = 0;
            for (JsonNode value : row) {
                if (index++ > 0) {
                    output.append('\t');
                }
                output.append(tsvCell(value));
            }
        } else {
            output.append(tsvCell(row));
        }
        output.append('\n');
    }

    private String tsvCell(JsonNode value) throws Exception {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        String text = value.isTextual() ? value.asText() : objectMapper.writeValueAsString(value);
        return text.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    public record QueryResult(String output, String outputFormat) {
    }
}
