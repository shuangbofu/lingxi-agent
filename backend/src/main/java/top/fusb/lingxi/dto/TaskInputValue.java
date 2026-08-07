package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskInputValue {

    @NotBlank
    @Size(max = 100)
    private String key;

    @Size(max = 20_000)
    private String value;
}
