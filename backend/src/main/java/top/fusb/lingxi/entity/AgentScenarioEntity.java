package top.fusb.lingxi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Data
@Entity
@Table(name = "agent_scenario")
public class AgentScenarioEntity {

    @Id
    @Column(length = 80)
    private String code;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private boolean userVisible = true;

    @Column(nullable = false)
    private Integer sortOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Set<String> capabilities = new LinkedHashSet<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Set<String>> capabilityCommands = new LinkedHashMap<>();

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
