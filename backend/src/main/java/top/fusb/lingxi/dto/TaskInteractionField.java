package top.fusb.lingxi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class TaskInteractionField {

    @Size(max = 100)
    private String key;

    @Size(max = 200)
    private String label;

    @Size(max = 30)
    private String type;

    @Valid
    @Size(max = 300)
    private List<TaskInteractionOption> options;

    private Boolean required;

    @Size(max = 500)
    private String placeholder;

    @Size(max = 20_000)
    private String defaultValue;

    @Size(max = 2_000)
    private String description;

    @Size(max = 100)
    private String contextKey;
}
