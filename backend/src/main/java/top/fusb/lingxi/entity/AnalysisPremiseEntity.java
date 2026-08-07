package top.fusb.lingxi.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
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
@Table(name = "analysis_premise")
public class AnalysisPremiseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 80)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Lob
    private String promptText;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> contextValues = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Map<String, String>> scenarioContextValues = new LinkedHashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "analysis_premise_visible_scenario", joinColumns = @JoinColumn(name = "premise_id"))
    @Column(name = "scenario_code", nullable = false, length = 80)
    private Set<String> visibleScenarioCodes = new LinkedHashSet<>();

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean globalVisible = false;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "analysis_premise_user", joinColumns = @JoinColumn(name = "premise_id"))
    @Column(name = "user_id", nullable = false)
    private Set<Long> assignedUserIds = new LinkedHashSet<>();

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
