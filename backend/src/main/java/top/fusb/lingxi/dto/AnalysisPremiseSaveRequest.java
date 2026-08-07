package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;
import java.util.Set;

@Data
public class AnalysisPremiseSaveRequest {

    @NotBlank
    private String name;

    private String description;

    private String promptText;

    private Map<String, String> contextValues;

    private Map<String, Map<String, String>> scenarioContextValues;

    private Set<String> visibleScenarioCodes;

    private Boolean enabled;

    private Boolean globalVisible;

    private Integer sortOrder;

    private Set<Long> assignedUserIds;
}
