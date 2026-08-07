package top.fusb.lingxi.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Data
public class AnalysisPremiseResponse {

    private Long id;
    private String code;
    private String name;
    private String description;
    private String promptText;
    private Map<String, String> contextValues;
    private Map<String, Map<String, String>> scenarioContextValues;
    private Set<String> visibleScenarioCodes;
    private boolean enabled;
    private boolean globalVisible;
    private Integer sortOrder;
    private Set<Long> assignedUserIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
