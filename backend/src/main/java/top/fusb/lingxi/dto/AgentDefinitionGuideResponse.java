package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class AgentDefinitionGuideResponse {

    private Long id;
    private String key;
    private String title;
    private String description;
    private String content;
    private Integer sortOrder;
}
