package top.fusb.lingxi.definition;

import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class CapabilityActivationCondition {

    private String parameter;

    private Set<String> values = new LinkedHashSet<>();
}
