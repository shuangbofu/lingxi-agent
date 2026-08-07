package top.fusb.lingxi.dto;

import top.fusb.lingxi.enums.TaskInteractionInputType;
import top.fusb.lingxi.enums.TaskInteractionStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskInteractionResponse {

    private Long id;

    private Long taskId;

    private String question;

    private String content;

    private TaskInteractionInputType inputType;

    private List<TaskInteractionOption> options;

    private List<TaskInteractionAction> actions;

    private List<TaskInteractionField> fields;

    private Boolean required;

    private String placeholder;

    private String answerHint;

    private String defaultValue;

    private String contextKey;

    private TaskInteractionStatus status;

    private String answerText;

    private List<String> selectedValues;

    private List<TaskInteractionAnswerValue> answerValues;

    private String answerAction;

    private String answerActionKey;

    private LocalDateTime createdAt;

}
