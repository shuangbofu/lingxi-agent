package top.fusb.lingxi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PublicLoginResponse {

    private List<PublicScenarioResponse> scenarios;
    private LoginDemoDefinition demo;
    private boolean initializationRequired;
}
