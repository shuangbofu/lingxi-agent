package top.fusb.lingxi.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class TaskShareCreateRequest {

    @Size(max = 50)
    private List<Long> roundTaskIds = List.of();
}
