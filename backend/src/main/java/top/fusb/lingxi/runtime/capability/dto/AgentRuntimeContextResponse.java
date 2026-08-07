package top.fusb.lingxi.runtime.capability.dto;

import top.fusb.lingxi.dto.TaskInputValue;
import lombok.Data;

import java.util.List;

@Data
public class AgentRuntimeContextResponse {

    private Long taskId;
    private String scenario;
    private List<TaskInputValue> inputValues = List.of();
    private List<AgentRuntimeContextValueResponse> values = List.of();
    private List<AgentRuntimeCapabilityConfigSummary> capabilityConfigs = List.of();
}
