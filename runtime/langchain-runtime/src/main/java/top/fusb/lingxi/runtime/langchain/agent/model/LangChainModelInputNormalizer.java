package top.fusb.lingxi.runtime.langchain.agent.model;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在每次模型请求前修复工具消息配对，并按模型输入能力清理多模态内容。
 */
@Slf4j
public final class LangChainModelInputNormalizer {

    private static final String MISSING_TOOL_RESULT =
            "{\"status\":\"aborted\",\"message\":\"工具调用缺少结果，已在发送模型前终止该调用。\"}";
    private static final String IMAGE_OMITTED = "[图片内容已省略：当前模型不支持图片输入]";
    private static final String AUDIO_OMITTED = "[音频内容已省略：当前模型不支持音频输入]";

    private final boolean imageInputSupported;
    private final boolean audioInputSupported;

    /**
     * 创建模型输入规范化器。
     *
     * @param imageInputSupported 当前模型是否支持图片输入
     * @param audioInputSupported 当前模型是否支持音频输入
     */
    public LangChainModelInputNormalizer(boolean imageInputSupported, boolean audioInputSupported) {
        this.imageInputSupported = imageInputSupported;
        this.audioInputSupported = audioInputSupported;
    }

    /**
     * 规范化一次即将发送的请求，不修改原请求及持久化历史。
     *
     * @param request LangChain4j 组装完成的模型请求
     * @return 无协议缺口且符合模型多模态能力的请求
     */
    public ChatRequest normalize(ChatRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return request;
        }
        NormalizedMessages normalized = normalizeToolProtocol(request.messages());
        List<ChatMessage> mediaNormalized = normalizeUnsupportedMedia(normalized.messages());
        boolean mediaChanged = mediaNormalized != normalized.messages();
        if (!normalized.changed() && !mediaChanged) {
            return request;
        }
        log.info("LangChain model input normalized missingToolResults={} orphanToolResults={} mediaMessages={}",
                normalized.missingToolResults(), normalized.orphanToolResults(),
                mediaChanged ? changedMessageCount(normalized.messages(), mediaNormalized) : 0);
        return request.toBuilder().messages(mediaNormalized).build();
    }

    private NormalizedMessages normalizeToolProtocol(List<ChatMessage> messages) {
        Map<String, Integer> callIndexes = new HashMap<>();
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    callIndexes.putIfAbsent(request.id(), index);
                }
            }
        }
        Set<String> validResultIds = new HashSet<>();
        Set<Integer> validResultIndexes = new HashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof ToolExecutionResultMessage result) {
                Integer callIndex = callIndexes.get(result.id());
                if (callIndex != null && callIndex < index && validResultIds.add(result.id())) {
                    validResultIndexes.add(index);
                }
            }
        }

        List<ChatMessage> normalized = new ArrayList<>();
        int missing = 0;
        int orphan = 0;
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof ToolExecutionResultMessage result) {
                if (!validResultIndexes.contains(index)) {
                    orphan++;
                    continue;
                }
                normalized.add(message);
                continue;
            }
            normalized.add(message);
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    if (!validResultIds.contains(request.id())) {
                        normalized.add(ToolExecutionResultMessage.builder()
                                .id(request.id())
                                .toolName(request.name())
                                .text(MISSING_TOOL_RESULT)
                                .isError(true)
                                .build());
                        missing++;
                    }
                }
            }
        }
        boolean changed = missing > 0 || orphan > 0;
        return new NormalizedMessages(changed ? List.copyOf(normalized) : messages, missing, orphan, changed);
    }

    private List<ChatMessage> normalizeUnsupportedMedia(List<ChatMessage> messages) {
        List<ChatMessage> normalized = null;
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            ChatMessage replacement = normalizeUnsupportedMedia(message);
            if (replacement == message) {
                continue;
            }
            if (normalized == null) {
                normalized = new ArrayList<>(messages);
            }
            normalized.set(index, replacement);
        }
        return normalized == null ? messages : List.copyOf(normalized);
    }

    private ChatMessage normalizeUnsupportedMedia(ChatMessage message) {
        List<Content> contents;
        if (message instanceof UserMessage userMessage) {
            contents = userMessage.contents();
        } else if (message instanceof ToolExecutionResultMessage result) {
            contents = result.contents();
        } else {
            return message;
        }
        List<Content> normalized = null;
        for (int index = 0; index < contents.size(); index++) {
            Content content = contents.get(index);
            TextContent replacement = null;
            if (content instanceof ImageContent && !imageInputSupported) {
                replacement = TextContent.from(IMAGE_OMITTED);
            } else if (content instanceof AudioContent && !audioInputSupported) {
                replacement = TextContent.from(AUDIO_OMITTED);
            }
            if (replacement == null) {
                continue;
            }
            if (normalized == null) {
                normalized = new ArrayList<>(contents);
            }
            normalized.set(index, replacement);
        }
        if (normalized == null) {
            return message;
        }
        return message instanceof UserMessage userMessage
                ? userMessage.toBuilder().contents(normalized).build()
                : ((ToolExecutionResultMessage) message).toBuilder().contents(normalized).build();
    }

    private int changedMessageCount(List<ChatMessage> original, List<ChatMessage> normalized) {
        int changed = Math.abs(original.size() - normalized.size());
        int sharedSize = Math.min(original.size(), normalized.size());
        for (int index = 0; index < sharedSize; index++) {
            if (!original.get(index).equals(normalized.get(index))) {
                changed++;
            }
        }
        return changed;
    }

    private record NormalizedMessages(List<ChatMessage> messages,
                                      int missingToolResults,
                                      int orphanToolResults,
                                      boolean changed) {
    }
}
