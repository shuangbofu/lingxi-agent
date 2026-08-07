package top.fusb.lingxi.definition;

import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class ScenarioRecommendationDefinition {

    private String source;
    private Set<String> targets = new LinkedHashSet<>();
}
