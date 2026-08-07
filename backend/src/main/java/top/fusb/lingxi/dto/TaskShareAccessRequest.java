package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TaskShareAccessRequest {

    @NotBlank
    private String password;
}
