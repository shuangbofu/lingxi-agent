package top.fusb.lingxi.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.dto.TaskResultData;
import top.fusb.lingxi.dto.TaskResultSection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskResultStructuringService {

    private static final Pattern RECOMMENDATION_BLOCK_PATTERN = Pattern.compile("(?is)```\\s*agent_recommendations\\s*\\R(.*?)\\R```");

    private final ObjectMapper objectMapper;

    /**
     * 将 agent 最终回答整理成通用 Markdown 结构，避免 Java 继续理解具体 definition 业务语义。
     *
     * @param resultFormat 结果格式
     * @param resultRenderer 前端结果渲染器标识
     * @param markdown agent 输出的最终 Markdown
     * @param candidateScenarioCodes 当前场景配置的可联动候选场景编码
     * @return 通用结构化结果对象
     */
    public TaskResultData structure(String resultFormat, String resultRenderer, String markdown, Collection<String> candidateScenarioCodes) {
        String content = markdown == null ? "" : markdown;
        RecommendationExtraction extraction = extractRecommendations(content, candidateScenarioCodes);
        content = extraction.markdown();
        TaskResultData result = new TaskResultData();
        result.setFormat(resultFormat == null || resultFormat.isBlank() ? "markdown-sections" : resultFormat);
        result.setRenderer(resultRenderer);
        result.setMarkdown(content);
        result.setRecommendedScenarioCodes(extraction.scenarioCodes());
        result.setSections(parseSections(content));
        return result;
    }

    private RecommendationExtraction extractRecommendations(String markdown, Collection<String> candidateScenarioCodes) {
        Set<String> candidates = new LinkedHashSet<>(candidateScenarioCodes == null ? Set.of() : candidateScenarioCodes);
        Set<String> recommended = new LinkedHashSet<>();
        StringBuffer cleaned = new StringBuffer();
        Matcher matcher = RECOMMENDATION_BLOCK_PATTERN.matcher(markdown);
        while (matcher.find()) {
            recommended.addAll(parseRecommendationCodes(matcher.group(1), candidates));
            matcher.appendReplacement(cleaned, "");
        }
        matcher.appendTail(cleaned);
        return new RecommendationExtraction(cleaned.toString().trim(), recommended);
    }

    private Set<String> parseRecommendationCodes(String rawJson, Set<String> candidates) {
        Set<String> result = new LinkedHashSet<>();
        if (rawJson == null || rawJson.isBlank() || candidates.isEmpty()) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode codes = root.isArray() ? root : root.path("scenarioCodes");
            if (!codes.isArray()) {
                codes = root.path("recommendations");
            }
            if (codes.isArray()) {
                for (JsonNode item : codes) {
                    String code = item.isTextual() ? item.asText() : item.path("scenarioCode").asText(null);
                    if (code != null && candidates.contains(code.trim())) {
                        result.add(code.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.info("任务推荐元数据解析失败 message={}", e.getMessage());
        }
        return result;
    }

    private record RecommendationExtraction(String markdown, Set<String> scenarioCodes) {
    }

    private List<TaskResultSection> parseSections(String markdown) {
        List<TaskResultSection> sections = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return sections;
        }
        TaskResultSection current = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            String title = sectionTitle(line);
            if (title != null) {
                flushSection(sections, current, body);
                current = new TaskResultSection();
                current.setTitle(title);
                current.setKind("SECTION");
                body.setLength(0);
                continue;
            }
            body.append(line).append(System.lineSeparator());
        }
        flushSection(sections, current, body);
        if (sections.isEmpty()) {
            TaskResultSection section = new TaskResultSection();
            section.setTitle("结果");
            section.setKind("RESULT");
            section.setContent(markdown.trim());
            sections.add(section);
        }
        return sections;
    }

    private void flushSection(List<TaskResultSection> sections, TaskResultSection current, StringBuilder body) {
        if (current == null) {
            return;
        }
        current.setContent(body.toString().trim());
        sections.add(current);
    }

    private String sectionTitle(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.startsWith("#")) {
            return trimmed.replaceFirst("^#+\\s*", "").trim();
        }
        String strongTitle = trimmed.replaceFirst("^\\*\\*(.+?)[：:]\\*\\*$", "$1");
        return strongTitle.equals(trimmed) ? null : strongTitle.trim();
    }

}
