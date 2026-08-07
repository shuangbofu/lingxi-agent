package top.fusb.lingxi.dto;

import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LoginDemoDefinition {

    private String question;
    private String inputPlaceholder;
    private String scenarioCode;
    private String scenarioName;
    private String premiseName;
    private DisplayTarget runtime;
    private DisplayTarget model;
    private String thinkingTitle;
    private String agentMessage;
    private List<Step> steps = new ArrayList<>();
    private String processedLabel;
    private String emptyProcessText;
    private String result;
    private Timings timings;

    @Data
    public static class DisplayTarget {
        private String name;
        private String iconUrl;
        private String darkIconUrl;
    }

    @Data
    public static class Step {
        private String label;
        private RuntimeActionIcon icon;
    }

    @Data
    public static class Timings {
        private Integer typingIntervalMs;
        private Integer submitDelayMs;
        private Integer launchDurationMs;
        private Integer thinkingDelayMs;
        private Integer messageDelayMs;
        private Integer stepDelayMs;
        private Integer resultDelayMs;
        private Integer completedDelayMs;
        private Integer resetDelayMs;
    }
}
