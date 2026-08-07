package top.fusb.lingxi.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskInteractionOption {

    @Size(max = 500)
    private String label;

    @Size(max = 2_000)
    private String value;

    @Size(max = 2_000)
    private String description;
}
