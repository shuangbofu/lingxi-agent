package top.fusb.lingxi.demo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "demo_log_entry")
public class LogEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 80)
    private String environmentCode;

    @Column(nullable = false, length = 160)
    private String serviceName;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false, length = 20)
    private String level;

    @Column(length = 160)
    private String traceId;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String content;
}
