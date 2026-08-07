package top.fusb.lingxi.entity;

import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.enums.TaskEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "task_event")
public class TaskEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private AgentTaskEntity task;

    @Column(nullable = false, length = 50)
    private String type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private TaskEventStatus status;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 5000)
    private String detail;

    @JdbcTypeCode(SqlTypes.JSON)
    private TaskEventPayload payload;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
