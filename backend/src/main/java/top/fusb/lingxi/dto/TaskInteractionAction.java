package top.fusb.lingxi.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskInteractionAction {

    @Size(max = 100)
    private String key;

    @Size(max = 200)
    private String label;

    @Size(max = 2_000)
    private String description;

    @Size(max = 30)
    private String style;

    private Boolean validateInput;
}
