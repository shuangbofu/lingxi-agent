package top.fusb.lingxi.resource;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ResourceCatalogCandidate {

    private String providerCode;
    private Long sourceConfigId;
    private String resourceRef;
    private String resourceKind;
    private String name;
    private String description;
    private List<String> labels;
    private ResourceCatalogMatchType matchType;
    private List<String> matchedFields;
    private Long sourceTaskId;
    private LocalDateTime observedAt;
    private int score;
}
