package top.fusb.lingxi.runtime.langchain.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class LangChainToolOutputKit {

    private static final int MAX_SCALAR_CHARS = 500;
    private static final int MAX_COLLECTION_SAMPLES = 12;
    private static final int MAX_EVIDENCE_FIELDS = 12;
    private static final int MAX_EVIDENCE_COLLECTIONS = 8;
    private static final int MAX_EVIDENCE_ITEM_SAMPLES = 3;
    private static final Pattern AUTHORIZATION_SECRET = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)([^\\s,;\\\"'}]+)");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|secret|password|passwd|pwd)"
                    + "\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s,;\\\"'}]+)");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");

    private LangChainToolOutputKit() {
    }

    public static String redactSensitiveText(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String redacted = AUTHORIZATION_SECRET.matcher(text).replaceAll("$1****");
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1****");
        return OPENAI_STYLE_KEY.matcher(redacted).replaceAll("sk-****");
    }

    /**
     * 将已经外部化保存的超长工具结果压缩为可继续推理的统一结构摘要。
     *
     * @param objectMapper JSON 序列化组件
     * @param output 工具完整原始输出
     * @param parsed 能够解析时的 JSON 节点，否则为 null
     * @param outputFile 可选的内部输出文件路径，不向模型开放时传 null
     * @param previewChars 最多保留的原始文本预览字符数
     * @return 包含结构、样本、预览和证据路径的 JSON 摘要
     * @throws IllegalArgumentException previewChars 小于 0 时抛出
     */
    public static ObjectNode summarize(ObjectMapper objectMapper,
                                       String output,
                                       JsonNode parsed,
                                       String outputFile,
                                       int previewChars) {
        if (previewChars < 0) {
            throw new IllegalArgumentException("工具输出预览字符数不能小于 0");
        }
        String value = output == null ? "" : output;
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("truncated", true);
        summary.put("originalChars", value.length());
        if (outputFile != null && !outputFile.isBlank()) {
            summary.put("outputFile", outputFile);
        }
        if (previewChars > 0 && !value.isEmpty()) {
            summary.put("preview", value.substring(0, Math.min(previewChars, value.length())));
        }
        if (parsed instanceof ArrayNode array) {
            summary.put("itemCount", array.size());
            summary.put("itemType", arrayItemType(array));
            addScalarSamples(objectMapper, summary.putArray("samples"), array);
            ArrayNode itemFields = objectItemFields(objectMapper, array);
            if (!itemFields.isEmpty()) {
                summary.set("itemFields", itemFields);
            }
        } else if (parsed instanceof ObjectNode object) {
            summarizeObject(objectMapper, summary, object);
        }
        summary.put("recommendedOperation", parsed == null
                ? "read_evidence" : "query_evidence");
        summary.put("nextAction", parsed == null
                ? "结构摘要不足时，使用 evidenceId 调用 read_evidence 分页读取；不要重复执行原工具。"
                : "结构摘要不足时，使用 evidenceId 调用 query_evidence 精确查询；不要重复执行原工具。");
        return summary;
    }

    /**
     * 为活动上下文占位符生成有界的内容结构说明，不复制正文和字段样本。
     *
     * @param objectMapper JSON 序列化组件
     * @param output 已归档的完整工具结果
     * @return 包含内容类型、有限字段和集合规模的结构说明
     */
    public static ObjectNode describeEvidenceStructure(ObjectMapper objectMapper, String output) {
        String value = output == null ? "" : output;
        ObjectNode description = objectMapper.createObjectNode();
        int firstContent = 0;
        while (firstContent < value.length() && Character.isWhitespace(value.charAt(firstContent))) {
            firstContent++;
        }
        if (firstContent >= value.length()
                || value.charAt(firstContent) != '{' && value.charAt(firstContent) != '[') {
            description.put("contentType", "text");
            description.put("recommendedOperation", "read_evidence");
            return description;
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(value);
        } catch (Exception exception) {
            description.put("contentType", "text");
            description.put("recommendedOperation", "read_evidence");
            return description;
        }
        if (parsed == null) {
            description.put("contentType", "text");
            description.put("recommendedOperation", "read_evidence");
            return description;
        }

        description.put("contentType", "json");
        description.put("recommendedOperation", "query_evidence");
        if (parsed instanceof ArrayNode array) {
            description.put("valueType", "array");
            description.put("itemCount", array.size());
            description.put("itemType", arrayItemType(array));
            ArrayNode itemFields = objectItemFields(objectMapper, array);
            if (!itemFields.isEmpty()) {
                description.set("itemFields", itemFields);
            }
            return description;
        }
        if (!(parsed instanceof ObjectNode object)) {
            description.put("valueType", "scalar");
            return description;
        }

        description.put("valueType", "object");
        description.put("fieldCount", object.size());
        ArrayNode fields = description.putArray("fields");
        ObjectNode collectionSizes = description.putObject("collectionSizes");
        ObjectNode collectionItemFields = description.putObject("collectionItemFields");
        int inspected = 0;
        Iterator<Map.Entry<String, JsonNode>> iterator = object.fields();
        while (iterator.hasNext() && inspected < MAX_EVIDENCE_FIELDS) {
            Map.Entry<String, JsonNode> field = iterator.next();
            fields.add(field.getKey());
            if (field.getValue() != null && field.getValue().isContainerNode()
                    && collectionSizes.size() < MAX_EVIDENCE_COLLECTIONS) {
                collectionSizes.put(field.getKey(), field.getValue().size());
            }
            if (field.getValue() instanceof ArrayNode array
                    && collectionItemFields.size() < MAX_EVIDENCE_COLLECTIONS) {
                ArrayNode itemFields = objectItemFields(objectMapper, array);
                if (!itemFields.isEmpty()) {
                    collectionItemFields.set(field.getKey(), itemFields);
                }
            }
            inspected++;
        }
        if (object.size() > fields.size()) {
            description.put("fieldsTruncated", true);
        }
        return description;
    }

    /**
     * 提取对象顶层字段、标量、嵌套对象标量和集合规模。
     *
     * @param objectMapper JSON 节点工厂
     * @param summary 接收结构摘要的目标对象
     * @param object 待压缩的原始 JSON 对象
     * @return 无返回值，结果写入 summary
     */
    private static void summarizeObject(ObjectMapper objectMapper, ObjectNode summary, ObjectNode object) {
        ArrayNode fields = summary.putArray("fields");
        ObjectNode scalarFields = summary.putObject("scalarFields");
        ObjectNode objectFields = summary.putObject("objectFields");
        ObjectNode collectionSizes = summary.putObject("collectionSizes");
        ObjectNode collectionSamples = summary.putObject("collectionSamples");
        ObjectNode collectionItemFields = summary.putObject("collectionItemFields");
        Iterator<Map.Entry<String, JsonNode>> iterator = object.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> field = iterator.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            fields.add(name);
            if (value == null || value.isNull() || value.isValueNode()) {
                scalarFields.set(name, compactScalar(objectMapper, value));
            } else if (value instanceof ObjectNode nested) {
                ObjectNode compact = compactObject(objectMapper, nested);
                if (!compact.isEmpty()) {
                    objectFields.set(name, compact);
                }
            } else if (value instanceof ArrayNode array) {
                collectionSizes.put(name, array.size());
                ArrayNode samples = objectMapper.createArrayNode();
                addScalarSamples(objectMapper, samples, array);
                if (!samples.isEmpty()) {
                    collectionSamples.set(name, samples);
                }
                ArrayNode itemFields = objectItemFields(objectMapper, array);
                if (!itemFields.isEmpty()) {
                    collectionItemFields.set(name, itemFields);
                }
            }
        }
        JsonNode runtimeResources = object.get("runtimeResources");
        if (runtimeResources != null && runtimeResources.isArray()) {
            summary.set("runtimeResources", runtimeResources.deepCopy());
        }
    }

    /**
     * 将一个嵌套对象压缩为标量字段和直接子集合数量。
     *
     * @param objectMapper JSON 节点工厂
     * @param object 待压缩的嵌套对象
     * @return 不包含大型嵌套内容的对象摘要
     */
    private static ObjectNode compactObject(ObjectMapper objectMapper, ObjectNode object) {
        ObjectNode compact = objectMapper.createObjectNode();
        object.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            if (value == null || value.isNull() || value.isValueNode()) {
                compact.set(field.getKey(), compactScalar(objectMapper, value));
            } else if (value.isArray()) {
                compact.put(field.getKey() + "Count", value.size());
            }
        });
        return compact;
    }

    /**
     * 保留普通标量并限制超长字符串长度。
     *
     * @param objectMapper JSON 节点工厂
     * @param value 标量或空值节点
     * @return 可安全放入工具摘要的标量节点
     */
    private static JsonNode compactScalar(ObjectMapper objectMapper, JsonNode value) {
        if (value == null || value.isNull()) {
            return objectMapper.nullNode();
        }
        if (!value.isTextual() || value.asText().length() <= MAX_SCALAR_CHARS) {
            return value.deepCopy();
        }
        return objectMapper.getNodeFactory().textNode(
                value.asText().substring(0, MAX_SCALAR_CHARS) + "[已截断]");
    }

    /**
     * 从集合中按原顺序抽取有限数量的标量样本。
     *
     * @param objectMapper JSON 节点工厂
     * @param samples 接收样本的目标数组
     * @param values 原始集合
     * @return 无返回值，结果写入 samples
     */
    private static void addScalarSamples(ObjectMapper objectMapper, ArrayNode samples, ArrayNode values) {
        for (JsonNode value : values) {
            if (samples.size() >= MAX_COLLECTION_SAMPLES) {
                return;
            }
            if (value == null || value.isNull() || value.isValueNode()) {
                samples.add(compactScalar(objectMapper, value));
            }
        }
    }

    /**
     * 识别数组中非空元素的共同 JSON 类型，帮助调用方选择正确的 JMESPath 投影语法。
     *
     * @param values 待描述的 JSON 数组
     * @return string、object 等共同类型；空数组返回 unknown，混合类型返回 mixed
     */
    private static String arrayItemType(ArrayNode values) {
        String itemType = null;
        for (JsonNode value : values) {
            if (value == null || value.isNull()) {
                continue;
            }
            String currentType = value.getNodeType().name().toLowerCase(java.util.Locale.ROOT);
            if (itemType == null) {
                itemType = currentType;
            } else if (!itemType.equals(currentType)) {
                return "mixed";
            }
        }
        return itemType == null ? "unknown" : itemType;
    }

    /**
     * 从数组开头的有限对象样本中提取字段并集，帮助模型构造首次 JMESPath 查询。
     *
     * @param objectMapper JSON 节点工厂
     * @param values 待描述的 JSON 数组
     * @return 按首次出现顺序排列且数量受限的对象字段名
     */
    private static ArrayNode objectItemFields(ObjectMapper objectMapper, ArrayNode values) {
        Set<String> names = new LinkedHashSet<>();
        int inspected = 0;
        for (JsonNode value : values) {
            if (inspected >= MAX_EVIDENCE_ITEM_SAMPLES || names.size() >= MAX_EVIDENCE_FIELDS) {
                break;
            }
            inspected++;
            if (!(value instanceof ObjectNode object)) {
                continue;
            }
            Iterator<String> fields = object.fieldNames();
            while (fields.hasNext() && names.size() < MAX_EVIDENCE_FIELDS) {
                names.add(fields.next());
            }
        }
        ArrayNode result = objectMapper.createArrayNode();
        names.forEach(result::add);
        return result;
    }
}
