package top.fusb.lingxi.resource;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ResourceCatalogCandidateFile {

    private String notice;
    private List<ResourceCatalogCandidate> candidates;
}
