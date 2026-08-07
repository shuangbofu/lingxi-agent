package top.fusb.lingxi.demo.web;

import java.time.LocalDateTime;
import java.util.List;

public final class WikiModels {

    private WikiModels() {
    }

    public record SourceSummary(Long sourceDocumentId, String projectCode, String title,
                                String path, String category, int revision,
                                LocalDateTime updatedAt, String renderUrl) {
    }

    public record SourceMatch(String matchType, int offset, String text, String renderUrl) {
    }

    public record SearchResult(Long sourceDocumentId, String title, String path,
                               String category, int revision, LocalDateTime updatedAt,
                               String renderUrl, List<SourceMatch> matches) {
    }

    public record BatchSearchResult(String query, List<SearchResult> results) {
    }

    public record SourceContent(Long sourceDocumentId, String projectCode, String title,
                                String path, String category, int revision, int offset,
                                int length, int totalLength, boolean hasMore,
                                String content, String renderUrl) {
    }

    public record SourceContext(Long sourceDocumentId, String title, int requestedOffset,
                                int startOffset, int endOffset, String content,
                                String renderUrl) {
    }

    public record TreeNode(Long sourceDocumentId, String title, String path,
                           String category, String renderUrl) {
    }

    public record VersionView(Long id, int revision, LocalDateTime createdAt) {
    }

    public record ChangeView(int revision, LocalDateTime changedAt, String summary) {
    }

    public record DiffView(Long fromSourceId, Long toSourceId, String fromTitle,
                           String toTitle, String diff) {
    }

    public record MediaView(Long sourceDocumentId, int total, List<Object> items) {
    }

    public record DeepReadView(SourceContent source, MediaView media) {
    }

    public record ReanalyzeView(String status, String message) {
    }
}
