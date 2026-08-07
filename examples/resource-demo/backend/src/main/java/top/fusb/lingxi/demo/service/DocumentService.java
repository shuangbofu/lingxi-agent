package top.fusb.lingxi.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.fusb.lingxi.demo.config.DemoProperties;
import top.fusb.lingxi.demo.domain.MarkdownDocumentEntity;
import top.fusb.lingxi.demo.domain.MarkdownVersionEntity;
import top.fusb.lingxi.demo.repository.MarkdownDocumentRepository;
import top.fusb.lingxi.demo.repository.MarkdownVersionRepository;
import top.fusb.lingxi.demo.repository.ProjectRepository;
import top.fusb.lingxi.demo.web.ApiModels.DocumentInput;
import top.fusb.lingxi.demo.web.ApiModels.DocumentView;
import top.fusb.lingxi.demo.web.BusinessException;
import top.fusb.lingxi.demo.web.ErrorCode;
import top.fusb.lingxi.demo.web.WikiModels.BatchSearchResult;
import top.fusb.lingxi.demo.web.WikiModels.ChangeView;
import top.fusb.lingxi.demo.web.WikiModels.DeepReadView;
import top.fusb.lingxi.demo.web.WikiModels.DiffView;
import top.fusb.lingxi.demo.web.WikiModels.MediaView;
import top.fusb.lingxi.demo.web.WikiModels.ReanalyzeView;
import top.fusb.lingxi.demo.web.WikiModels.SearchResult;
import top.fusb.lingxi.demo.web.WikiModels.SourceContent;
import top.fusb.lingxi.demo.web.WikiModels.SourceContext;
import top.fusb.lingxi.demo.web.WikiModels.SourceMatch;
import top.fusb.lingxi.demo.web.WikiModels.SourceSummary;
import top.fusb.lingxi.demo.web.WikiModels.TreeNode;
import top.fusb.lingxi.demo.web.WikiModels.VersionView;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final MarkdownDocumentRepository documentRepository;
    private final MarkdownVersionRepository versionRepository;
    private final ProjectRepository projectRepository;
    private final DemoProperties demoProperties;
    private final DocumentSearchIndex searchIndex;

    @Transactional(readOnly = true)
    public List<DocumentView> listAdminDocuments(String projectCode) {
        List<MarkdownDocumentEntity> documents = projectCode == null || projectCode.isBlank()
                ? documentRepository.findAllByOrderByUpdatedAtDesc()
                : documentRepository.findByProjectCodeIgnoreCaseOrderByPathAsc(projectCode);
        return documents.stream().map(this::toDocumentView).toList();
    }

    @Transactional
    public DocumentView saveDocument(Long documentId, DocumentInput input) {
        projectRepository.findByCodeIgnoreCase(input.projectCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "文档所属项目不存在"));
        MarkdownDocumentEntity document = documentId == null ? new MarkdownDocumentEntity() : requireDocument(documentId);
        int nextRevision = documentId == null ? 1 : document.getRevision() + 1;
        document.setProjectCode(input.projectCode().trim());
        document.setTitle(input.title().trim());
        document.setPath(normalizeDocumentPath(input.path()));
        document.setCategory(input.category() == null || input.category().isBlank() ? null : input.category().trim());
        document.setContent(input.content());
        document.setRevision(nextRevision);
        document = documentRepository.saveAndFlush(document);

        MarkdownVersionEntity version = new MarkdownVersionEntity();
        version.setDocumentId(document.getId());
        version.setRevision(nextRevision);
        version.setContent(document.getContent());
        version.setCreatedAt(LocalDateTime.now());
        versionRepository.save(version);
        searchIndex.update(document);
        log.info("Saved demo Markdown document documentId={}, projectCode={}, revision={}",
                document.getId(), document.getProjectCode(), nextRevision);
        return toDocumentView(document);
    }

    @Transactional
    public void deleteDocument(Long documentId) {
        MarkdownDocumentEntity document = requireDocument(documentId);
        documentRepository.delete(document);
        searchIndex.delete(documentId);
        log.info("Deleted demo Markdown document documentId={}, path={}", documentId, document.getPath());
    }

    @Transactional(readOnly = true)
    public List<SourceSummary> listSources(String projectCode, String ids) {
        List<Long> selectedIds = parseIds(ids);
        return documents(projectCode).stream()
                .filter(document -> selectedIds.isEmpty() || selectedIds.contains(document.getId()))
                .map(this::toSourceSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TreeNode> sourceTree(String projectCode) {
        return documents(projectCode).stream()
                .map(document -> new TreeNode(document.getId(), document.getTitle(), document.getPath(),
                        document.getCategory(), renderUrl(document)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SearchResult> search(String projectCode, String query, String mode, int limit) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "检索词不能为空");
        }
        return searchIndex.search(projectCode, query, mode, limit).stream()
                .map(documentRepository::findById)
                .flatMap(java.util.Optional::stream)
                .map(document -> toSearchResult(document, query))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BatchSearchResult> searchBatch(String projectCode, List<String> queries, String mode, int limitPerQuery) {
        return queries.stream()
                .filter(query -> query != null && !query.isBlank())
                .map(query -> new BatchSearchResult(query, search(projectCode, query, mode, limitPerQuery)))
                .toList();
    }

    @Transactional(readOnly = true)
    public SourceContent read(Long documentId, String projectCode, int offset, int limit) {
        MarkdownDocumentEntity document = requireProjectDocument(documentId, projectCode);
        return chunk(document, offset, limit);
    }

    @Transactional(readOnly = true)
    public SourceContext context(Long documentId, String projectCode, int offset, int radius) {
        MarkdownDocumentEntity document = requireProjectDocument(documentId, projectCode);
        int safeOffset = Math.max(0, Math.min(offset, document.getContent().length()));
        int start = Math.max(0, safeOffset - Math.max(1, radius));
        int end = Math.min(document.getContent().length(), safeOffset + Math.max(1, radius));
        return new SourceContext(document.getId(), document.getTitle(), safeOffset, start, end,
                document.getContent().substring(start, end), renderUrl(document));
    }

    @Transactional(readOnly = true)
    public List<VersionView> history(Long documentId, String projectCode) {
        requireProjectDocument(documentId, projectCode);
        return versionRepository.findByDocumentIdOrderByRevisionDesc(documentId).stream()
                .map(version -> new VersionView(version.getId(), version.getRevision(), version.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChangeView> changes(Long documentId, String projectCode, String query, int limit) {
        MarkdownDocumentEntity document = requireProjectDocument(documentId, projectCode);
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return versionRepository.findByDocumentIdOrderByRevisionDesc(documentId).stream()
                .filter(version -> normalizedQuery.isBlank() || version.getContent().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .limit(Math.max(1, limit))
                .map(version -> new ChangeView(version.getRevision(), version.getCreatedAt(),
                        "保存了《" + document.getTitle() + "》第 " + version.getRevision() + " 版 Markdown 内容"))
                .toList();
    }

    @Transactional(readOnly = true)
    public DiffView diff(Long fromId, Long toId, String projectCode) {
        MarkdownDocumentEntity from = requireProjectDocument(fromId, projectCode);
        MarkdownDocumentEntity to = requireProjectDocument(toId, projectCode);
        return new DiffView(fromId, toId, from.getTitle(), to.getTitle(), simpleDiff(from.getContent(), to.getContent()));
    }

    @Transactional(readOnly = true)
    public MediaView media(Long documentId, String projectCode) {
        requireProjectDocument(documentId, projectCode);
        return new MediaView(documentId, 0, List.of());
    }

    @Transactional(readOnly = true)
    public DeepReadView deepRead(Long documentId, String projectCode) {
        return new DeepReadView(read(documentId, projectCode, 0, 20000), media(documentId, projectCode));
    }

    public ReanalyzeView reanalyze() {
        return new ReanalyzeView("UNSUPPORTED", "Markdown 示例服务不保存图片，因此没有可重新分析的媒体资源");
    }

    private SearchResult toSearchResult(MarkdownDocumentEntity document, String query) {
        int contentOffset = document.getContent().toLowerCase(Locale.ROOT)
                .indexOf(query.toLowerCase(Locale.ROOT));
        int safeOffset = Math.max(0, contentOffset);
        int start = Math.max(0, safeOffset - 100);
        int end = Math.min(document.getContent().length(), safeOffset + Math.max(query.length(), 1) + 180);
        SourceMatch match = new SourceMatch("MARKDOWN", safeOffset,
                document.getContent().substring(start, end), renderUrl(document));
        return new SearchResult(document.getId(), document.getTitle(), document.getPath(), document.getCategory(),
                document.getRevision(), document.getUpdatedAt(), renderUrl(document), List.of(match));
    }

    private SourceContent chunk(MarkdownDocumentEntity document, int offset, int limit) {
        int start = Math.max(0, Math.min(offset, document.getContent().length()));
        int end = Math.min(document.getContent().length(), start + Math.max(1, limit));
        return new SourceContent(document.getId(), document.getProjectCode(), document.getTitle(), document.getPath(),
                document.getCategory(), document.getRevision(), start, end - start, document.getContent().length(),
                end < document.getContent().length(), document.getContent().substring(start, end), renderUrl(document));
    }

    private String simpleDiff(String from, String to) {
        List<String> fromLines = from.lines().toList();
        List<String> toLines = to.lines().toList();
        StringBuilder diff = new StringBuilder();
        int max = Math.max(fromLines.size(), toLines.size());
        for (int index = 0; index < max; index++) {
            String left = index < fromLines.size() ? fromLines.get(index) : null;
            String right = index < toLines.size() ? toLines.get(index) : null;
            if (left != null && left.equals(right)) {
                continue;
            }
            if (left != null) {
                diff.append("- ").append(left).append('\n');
            }
            if (right != null) {
                diff.append("+ ").append(right).append('\n');
            }
        }
        return diff.toString();
    }

    private List<MarkdownDocumentEntity> documents(String projectCode) {
        return documentRepository.findByProjectCodeIgnoreCaseOrderByPathAsc(projectCode);
    }

    private MarkdownDocumentEntity requireProjectDocument(Long documentId, String projectCode) {
        MarkdownDocumentEntity document = requireDocument(documentId);
        if (!document.getProjectCode().equalsIgnoreCase(projectCode)) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不属于当前项目");
        }
        return document;
    }

    private MarkdownDocumentEntity requireDocument(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "Markdown 文档不存在"));
    }

    private DocumentView toDocumentView(MarkdownDocumentEntity document) {
        return new DocumentView(document.getId(), document.getProjectCode(), document.getTitle(), document.getPath(),
                document.getCategory(), document.getContent(), document.getRevision(),
                document.getCreatedAt(), document.getUpdatedAt());
    }

    private SourceSummary toSourceSummary(MarkdownDocumentEntity document) {
        return new SourceSummary(document.getId(), document.getProjectCode(), document.getTitle(), document.getPath(),
                document.getCategory(), document.getRevision(), document.getUpdatedAt(), renderUrl(document));
    }

    private String renderUrl(MarkdownDocumentEntity document) {
        return demoProperties.getPublicBaseUrl().replaceAll("/+$", "")
                + "/#/documents?documentId=" + document.getId();
    }

    private List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(ids.split(",")).map(String::trim).filter(value -> !value.isBlank())
                    .map(Long::valueOf).toList();
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "文档 ID 列表格式错误");
        }
    }

    private String normalizeDocumentPath(String path) {
        String value = path.trim().replace('\\', '/');
        value = value.startsWith("/") ? value.substring(1) : value;
        if (value.equals("..") || value.contains("../") || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "文档路径不能包含上级目录");
        }
        return value;
    }
}
