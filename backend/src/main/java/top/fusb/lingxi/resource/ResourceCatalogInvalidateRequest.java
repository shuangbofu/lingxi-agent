package top.fusb.lingxi.resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ResourceCatalogInvalidateRequest {

    @NotBlank
    @Size(max = 100)
    private String providerCode;

    private Long sourceConfigId;

    @NotEmpty
    @Size(max = 200)
    private List<@NotBlank @Size(max = 300) String> refs = List.of();
}
