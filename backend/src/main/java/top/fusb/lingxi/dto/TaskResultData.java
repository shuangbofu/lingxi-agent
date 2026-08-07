package top.fusb.lingxi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskResultData {

    private String format;
    private String renderer;
    private String markdown;
    private Set<String> recommendedScenarioCodes = new LinkedHashSet<>();
    private List<TaskResultSection> sections = new ArrayList<>();
}
