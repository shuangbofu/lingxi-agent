package top.fusb.lingxi.dto;

import lombok.Data;

import java.util.List;

@Data
public class CapabilityCommandOutputDefinition {

    private String type;

    private String pathField;

    private List<String> features = List.of();
}
