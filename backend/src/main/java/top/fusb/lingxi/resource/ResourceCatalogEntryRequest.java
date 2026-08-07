package top.fusb.lingxi.resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ResourceCatalogEntryRequest {

    @NotBlank
    @Size(max = 300)
    private String ref;

    @NotBlank
    @Size(max = 100)
    private String kind;

    @NotBlank
    @Size(max = 300)
    private String name;

    @Size(max = 2000)
    private String description;

    @Size(max = 30)
    private List<String> aliases = List.of();

    @Size(max = 30)
    private List<String> labels = List.of();

    @Size(max = 30)
    private List<String> relations = List.of();

    @Size(max = 5000)
    private String searchText;

    @Size(max = 200)
    private String revision;
}
