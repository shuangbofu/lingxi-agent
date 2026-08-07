package top.fusb.lingxi.runtime.langchain.agent.activity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将模型实际请求内容按 Token 分类写入任务本地工作区，供提示词和工具定义优化使用。
 */
final class LangChainRequestContentSnapshotWriter {

    private static final List<Category> CATEGORIES = List.of(
            new Category("systemInstructionTokens", "系统基础指令"),
            new Category("taskInstructionTokens", "任务/场景指令"),
            new Category("mcpInstructionTokens", "MCP 指令"),
            new Category("toolSchemaTokens", "工具 Schema"),
            new Category("conversationTokens", "对话历史"),
            new Category("toolResultTokens", "工具结果"),
            new Category("imageTokens", "图片")
    );

    private final Path root;
    private final String taskInstructions;
    private final String mcpInstructions;

    LangChainRequestContentSnapshotWriter(Path root, String taskInstructions, String mcpInstructions) {
        this.root = root;
        this.taskInstructions = normalize(taskInstructions);
        this.mcpInstructions = normalize(mcpInstructions);
    }

    Path write(String requestId, ChatRequest request, Map<String, String> metrics) throws Exception {
        Files.createDirectories(root);
        Path target = root.resolve(requestId + ".md").normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IllegalArgumentException("模型请求快照路径无效");
        }
        Files.writeString(target, markdown(requestId, request, metrics), StandardCharsets.UTF_8);
        return target;
    }

    private String markdown(String requestId, ChatRequest request, Map<String, String> metrics) {
        List<ChatMessage> messages = request == null || request.messages() == null
                ? List.of() : request.messages();
        List<SystemMessage> systemMessages = messages.stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).toList();
        List<ChatMessage> conversation = messages.stream()
                .filter(message -> !(message instanceof SystemMessage)
                        && !(message instanceof ToolExecutionResultMessage))
                .toList();
        List<ToolExecutionResultMessage> toolResults = messages.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).toList();

        StringBuilder markdown = new StringBuilder("# 模型请求输入快照\n\n")
                .append("- 请求：`").append(requestId).append("`\n")
                .append("- 时间：`").append(Instant.now()).append("`\n")
                .append("- 模型：`").append(request == null || request.modelName() == null
                        ? "未记录" : request.modelName()).append("`\n")
                .append("- 估算输入 Token：`").append(metrics.getOrDefault("estimatedInputTokens", "0"))
                .append("`\n\n")
                .append("| 分类 | 估算 Token |\n| --- | ---: |\n");
        CATEGORIES.forEach(category -> markdown.append("| ").append(category.label()).append(" | ")
                .append(metrics.getOrDefault(category.metricKey(), "0")).append(" |\n"));

        appendSection(markdown, "系统基础指令", systemInstructions(systemMessages));
        appendSection(markdown, "任务/场景指令", taskInstructions);
        appendSection(markdown, "MCP 指令", mcpInstructions);
        appendSection(markdown, "工具 Schema", toolSchemas(request));
        appendSection(markdown, "对话历史", messages(conversation));
        appendSection(markdown, "工具结果", toolResults(toolResults));
        appendSection(markdown, "图片", images(messages));
        return markdown.toString();
    }

    private String systemInstructions(List<SystemMessage> messages) {
        String system = messages.stream().map(SystemMessage::text)
                .filter(text -> text != null && !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        system = removeFirst(system, taskInstructions);
        system = removeFirst(system, mcpInstructions);
        return system.replaceAll("\n{3,}", "\n\n").strip();
    }

    private String toolSchemas(ChatRequest request) {
        if (request == null || request.toolSpecifications() == null || request.toolSpecifications().isEmpty()) {
            return "无";
        }
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < request.toolSpecifications().size(); index++) {
            value.append("### 工具 ").append(index + 1).append("\n\n")
                    .append(request.toolSpecifications().get(index)).append("\n\n");
        }
        return value.toString().strip();
    }

    private String messages(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return "无";
        }
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            value.append("### 消息 ").append(index + 1).append(" · ")
                    .append(message.type()).append("\n\n")
                    .append(messageContent(message)).append("\n\n");
        }
        return value.toString().strip();
    }

    private String toolResults(List<ToolExecutionResultMessage> messages) {
        if (messages.isEmpty()) {
            return "无";
        }
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < messages.size(); index++) {
            ToolExecutionResultMessage message = messages.get(index);
            value.append("### 工具结果 ").append(index + 1).append(" · `")
                    .append(message.toolName()).append("`\n\n")
                    .append(contents(message.contents(), false)).append("\n\n");
        }
        return value.toString().strip();
    }

    private String images(List<ChatMessage> messages) {
        List<String> images = new ArrayList<>();
        for (ChatMessage message : messages) {
            Iterable<Content> contents = message instanceof UserMessage userMessage ? userMessage.contents()
                    : message instanceof ToolExecutionResultMessage resultMessage ? resultMessage.contents() : List.of();
            for (Content content : contents) {
                if (content instanceof ImageContent imageContent) {
                    images.add("- 来源：`" + message.type() + "`；类型：`"
                            + imageContent.image().mimeType() + "`");
                }
            }
        }
        return images.isEmpty() ? "无" : String.join("\n", images);
    }

    private String messageContent(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return contents(userMessage.contents(), true);
        }
        if (message instanceof AiMessage) {
            return fenced(ChatMessageSerializer.messageToJson(message), "json");
        }
        return fenced(ChatMessageSerializer.messageToJson(message), "json");
    }

    private String contents(Iterable<Content> contents, boolean includeImageMarkers) {
        List<String> values = new ArrayList<>();
        for (Content content : contents) {
            if (content instanceof TextContent textContent) {
                values.add(fenced(textContent.text(), "text"));
            } else if (content instanceof ImageContent imageContent && includeImageMarkers) {
                values.add("[图片内容：" + imageContent.image().mimeType() + "]");
            }
        }
        return values.isEmpty() ? "无" : String.join("\n\n", values);
    }

    private void appendSection(StringBuilder target, String title, String content) {
        target.append("\n## ").append(title).append("\n\n")
                .append(content == null || content.isBlank() ? "无" : content.strip()).append("\n");
    }

    private String fenced(String value, String language) {
        return "~~~~" + language + "\n" + (value == null ? "" : value) + "\n~~~~";
    }

    private String removeFirst(String source, String fragment) {
        if (source == null || source.isBlank() || fragment == null || fragment.isBlank()) {
            return source == null ? "" : source;
        }
        int index = source.indexOf(fragment);
        return index < 0 ? source : source.substring(0, index) + source.substring(index + fragment.length());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    private record Category(String metricKey, String label) {
    }
}
