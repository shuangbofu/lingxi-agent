package top.fusb.lingxi.entity;

import top.fusb.lingxi.dto.TaskInteractionOption;
import top.fusb.lingxi.dto.TaskInteractionAction;
import top.fusb.lingxi.dto.TaskInteractionAnswerValue;
import top.fusb.lingxi.dto.TaskInteractionField;
import top.fusb.lingxi.enums.TaskInteractionAnswerAction;
import top.fusb.lingxi.enums.TaskInteractionInputType;
import top.fusb.lingxi.enums.TaskInteractionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "task_interaction")
public class TaskInteractionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private AgentTaskEntity task;

    @Lob
    @Column(nullable = false)
    private String question;

    @Lob
    private String content;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 40)
    private TaskInteractionInputType inputType;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TaskInteractionOption> options;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TaskInteractionAction> actions;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TaskInteractionField> fields;

    @Column(nullable = false)
    private Boolean required;

    @Column(length = 500)
    private String placeholder;

    @Column(length = 1000)
    private String answerHint;

    @Column(length = 1000)
    private String defaultValue;

    @Column(length = 80)
    private String contextKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 40)
    private TaskInteractionStatus status;

    @Lob
    private String answerText;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> selectedValues;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TaskInteractionAnswerValue> answerValues;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private TaskInteractionAnswerAction answerAction;

    @Column(length = 80)
    private String answerActionKey;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime answeredAt;
}
