package top.fusb.lingxi.demo.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.fusb.lingxi.demo.service.DocumentService;
import top.fusb.lingxi.demo.web.WikiModels.BatchSearchResult;
import top.fusb.lingxi.demo.web.WikiModels.ChangeView;
import top.fusb.lingxi.demo.web.WikiModels.DeepReadView;
import top.fusb.lingxi.demo.web.WikiModels.DiffView;
import top.fusb.lingxi.demo.web.WikiModels.MediaView;
import top.fusb.lingxi.demo.web.WikiModels.ReanalyzeView;
import top.fusb.lingxi.demo.web.WikiModels.SearchResult;
import top.fusb.lingxi.demo.web.WikiModels.SourceContent;
import top.fusb.lingxi.demo.web.WikiModels.SourceContext;
import top.fusb.lingxi.demo.web.WikiModels.SourceSummary;
import top.fusb.lingxi.demo.web.WikiModels.TreeNode;
import top.fusb.lingxi.demo.web.WikiModels.VersionView;

import java.util.List;

@RestController
@RequestMapping("/api/project-context/wiki")
@RequiredArgsConstructor
public class WikiController {

    private final DocumentService documentService;

    @GetMapping("/sources")
    public List<SourceSummary> sources(@RequestParam String projectCode,
                                       @RequestParam(required = false) String ids) {
        return documentService.listSources(projectCode, ids);
    }

    @GetMapping("/source-tree")
    public List<TreeNode> sourceTree(@RequestParam String projectCode,
                                     @RequestParam(required = false) String batchId) {
        return documentService.sourceTree(projectCode);
    }

    @GetMapping("/source-search")
    public List<SearchResult> search(@RequestParam String projectCode, @RequestParam String query,
                                     @RequestParam(defaultValue = "ALL_TERMS") String mode,
                                     @RequestParam(defaultValue = "false") boolean includeHistory,
                                     @RequestParam(defaultValue = "20") int limit) {
        return documentService.search(projectCode, query, mode, limit);
    }

    @PostMapping("/source-search-batch")
    public List<BatchSearchResult> searchBatch(@RequestParam String projectCode,
                                                @Valid @RequestBody SearchBatchInput input) {
        return documentService.searchBatch(projectCode, input.queries(), input.mode(), input.limitPerQuery());
    }

    @GetMapping("/sources/{sourceId}")
    public SourceContent read(@PathVariable Long sourceId, @RequestParam String projectCode,
                              @RequestParam(defaultValue = "0") int offset,
                              @RequestParam(defaultValue = "20000") int limit) {
        return documentService.read(sourceId, projectCode, offset, limit);
    }

    @GetMapping("/sources/{sourceId}/context")
    public SourceContext context(@PathVariable Long sourceId, @RequestParam String projectCode,
                                 @RequestParam int offset,
                                 @RequestParam(defaultValue = "1200") int radius) {
        return documentService.context(sourceId, projectCode, offset, radius);
    }

    @GetMapping("/sources/{sourceId}/raw")
    public SourceContent raw(@PathVariable Long sourceId, @RequestParam String projectCode,
                             @RequestParam(defaultValue = "0") int offset,
                             @RequestParam(defaultValue = "20000") int limit) {
        return documentService.read(sourceId, projectCode, offset, limit);
    }

    @GetMapping("/sources/{sourceId}/history")
    public List<VersionView> history(@PathVariable Long sourceId, @RequestParam String projectCode) {
        return documentService.history(sourceId, projectCode);
    }

    @GetMapping("/sources/{sourceId}/changes")
    public List<ChangeView> changes(@PathVariable Long sourceId, @RequestParam String projectCode,
                                    @RequestParam(required = false) String query,
                                    @RequestParam(defaultValue = "100") int limit) {
        return documentService.changes(sourceId, projectCode, query, limit);
    }

    @GetMapping("/source-diff")
    public DiffView diff(@RequestParam String projectCode, @RequestParam Long fromSourceId,
                         @RequestParam Long toSourceId) {
        return documentService.diff(fromSourceId, toSourceId, projectCode);
    }

    @GetMapping("/sources/{sourceId}/media")
    public MediaView media(@PathVariable Long sourceId, @RequestParam String projectCode) {
        return documentService.media(sourceId, projectCode);
    }

    @GetMapping("/sources/{sourceId}/deep")
    public DeepReadView deep(@PathVariable Long sourceId, @RequestParam String projectCode,
                             @RequestParam(defaultValue = "20") int maxImages) {
        return documentService.deepRead(sourceId, projectCode);
    }

    @PostMapping("/sources/{sourceId}/media/{mediaId}/reanalyze")
    public ReanalyzeView reanalyze(@PathVariable Long sourceId, @PathVariable Long mediaId,
                                   @RequestParam String projectCode,
                                   @RequestBody ReanalyzeInput input) {
        documentService.media(sourceId, projectCode);
        return documentService.reanalyze();
    }

    public record SearchBatchInput(@NotEmpty List<String> queries, String mode,
                                   boolean includeHistory, int limitPerQuery) {
    }

    public record ReanalyzeInput(String question) {
    }
}
