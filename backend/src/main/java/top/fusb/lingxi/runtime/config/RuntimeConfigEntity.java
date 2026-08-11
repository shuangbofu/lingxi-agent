package top.fusb.lingxi.runtime.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "runtime_config")
public class RuntimeConfigEntity {

    @Id
    private Long id;

    @Column
    private Integer maxTaskConcurrency;

    @Column
    private Integer taskExecutionTimeoutMinutes;

    @Column
    private Long globalDailyTokenLimit;

    @Column
    private Long globalWeeklyTokenLimit;

    @Column
    private Long globalMonthlyTokenLimit;

    @Column(columnDefinition = "clob")
    private String globalBoundaryPrompt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
