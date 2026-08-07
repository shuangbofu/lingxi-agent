package top.fusb.lingxi.dto;

import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.runtime.api.event.RuntimeEventVisibility;
import top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TaskEventPayload {

    private String rawType;
    private String itemType;
    private String itemId;
    private String status;
    private String command;
    private String toolName;
    private String callId;
    private String arguments;
    private String output;
    private String message;
    private Integer exitCode;
    private String actionKey;
    private String actionInstanceId;
    private String actionLabel;
    private String actionTarget;
    private Boolean detailFile;
    private Boolean argumentsFile;
    private Boolean outputFile;
    private Boolean messageFile;
    private Boolean commandFile;
    private Boolean actionTargetFile;
    private Boolean transientEvent;
    private Map<String, String> metrics;
    private String actionGroupId;
    private Integer actionGroupSize;
    private RuntimeEventSemantic semantic;
    private RuntimeEventVisibility visibility;
    private RuntimeModelTimingMode modelTimingMode;
    private RuntimeActionIcon actionIcon;
    private Long interactionId;
    private String interactionStatus;
    private String interactionInputType;
    private String question;

    private String interactionContent;
    private List<TaskInteractionOption> options;
    private List<TaskInteractionAction> actions;
    private List<TaskInteractionField> fields;
    private Boolean required;
    private String placeholder;
    private String answerHint;
    private String defaultValue;
    private String contextKey;
    private String answerText;
    private List<String> selectedValues;
    private List<TaskInteractionAnswerValue> answerValues;
    private String interactionAnswerAction;
    private String interactionAnswerActionKey;
}
