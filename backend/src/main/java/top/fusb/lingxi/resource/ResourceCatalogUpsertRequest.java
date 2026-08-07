package top.fusb.lingxi.resource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ResourceCatalogUpsertRequest {

    @NotBlank
    @Size(max = 100)
    private String providerCode;

    private Long sourceConfigId;

    @Valid
    @Size(max = 200)
    private List<ResourceCatalogEntryRequest> entries = List.of();
}
