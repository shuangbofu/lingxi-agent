package top.fusb.lingxi.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.fusb.lingxi.demo.config.DemoProperties;
import top.fusb.lingxi.demo.domain.DatabaseEntity;
import top.fusb.lingxi.demo.domain.EnvironmentEntity;
import top.fusb.lingxi.demo.domain.ProjectEntity;
import top.fusb.lingxi.demo.domain.RelationEntity;
import top.fusb.lingxi.demo.domain.RepositoryEntity;
import top.fusb.lingxi.demo.repository.DatabaseRepository;
import top.fusb.lingxi.demo.repository.DemoRepositoryRepository;
import top.fusb.lingxi.demo.repository.EnvironmentRepository;
import top.fusb.lingxi.demo.repository.MarkdownDocumentRepository;
import top.fusb.lingxi.demo.repository.ProjectRepository;
import top.fusb.lingxi.demo.repository.RelationRepository;
import top.fusb.lingxi.demo.web.ApiModels.DatabaseInput;
import top.fusb.lingxi.demo.web.ApiModels.DatabaseView;
import top.fusb.lingxi.demo.web.ApiModels.EnvironmentInput;
import top.fusb.lingxi.demo.web.ApiModels.EnvironmentView;
import top.fusb.lingxi.demo.web.ApiModels.HttpLogConfig;
import top.fusb.lingxi.demo.web.ApiModels.JdbcConfig;
import top.fusb.lingxi.demo.web.ApiModels.ProjectContextView;
import top.fusb.lingxi.demo.web.ApiModels.ProjectInput;
import top.fusb.lingxi.demo.web.ApiModels.ProjectView;
import top.fusb.lingxi.demo.web.ApiModels.RelationInput;
import top.fusb.lingxi.demo.web.ApiModels.RelationView;
import top.fusb.lingxi.demo.web.ApiModels.RepositoryInput;
import top.fusb.lingxi.demo.web.ApiModels.RepositoryView;
import top.fusb.lingxi.demo.web.ApiModels.ResourceConfigView;
import top.fusb.lingxi.demo.web.BusinessException;
import top.fusb.lingxi.demo.web.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;
    private final DemoRepositoryRepository repositoryRepository;
    private final DatabaseRepository databaseRepository;
    private final RelationRepository relationRepository;
    private final MarkdownDocumentRepository documentRepository;
    private final DocumentSearchIndex documentSearchIndex;
    private final DemoProperties demoProperties;

    @Transactional(readOnly = true)
    public List<ProjectView> listProjects(List<String> keywords) {
        return listProjects(keywords, false);
    }

    @Transactional(readOnly = true)
    public List<ProjectView> listAdminProjects() {
        return listProjects(List.of(), true);
    }

    @Transactional(readOnly = true)
    public ProjectView getProject(Long projectId) {
        return toProjectView(requireProject(projectId), true);
    }

    @Transactional
    public ProjectView saveProject(Long projectId, ProjectInput input) {
        ProjectEntity project = projectId == null ? new ProjectEntity() : requireProject(projectId);
        project.setCode(input.code().trim());
        project.setName(input.name().trim());
        project.setDescription(trimToNull(input.description()));
        project = projectRepository.saveAndFlush(project);

        repositoryRepository.deleteByProjectId(project.getId());
        databaseRepository.deleteByProjectId(project.getId());
        relationRepository.deleteByProjectId(project.getId());
        environmentRepository.deleteByProjectId(project.getId());
        repositoryRepository.flush();
        databaseRepository.flush();
        relationRepository.flush();
        environmentRepository.flush();

        int repositoryCount = 0;
        int databaseCount = 0;
        for (EnvironmentInput item : safeList(input.environments())) {
            EnvironmentEntity environment = new EnvironmentEntity();
            environment.setProjectId(project.getId());
            environment.setCode(item.code().trim());
            environment.setName(item.name().trim());
            environment.setDeploymentBranch(trimToNull(item.deploymentBranch()));
            environment = environmentRepository.saveAndFlush(environment);

            for (RepositoryInput repositoryInput : safeList(item.repositories())) {
                RepositoryEntity repository = new RepositoryEntity();
                repository.setProjectId(project.getId());
                repository.setEnvironmentId(environment.getId());
                repository.setCode(repositoryInput.code().trim());
                repository.setName(repositoryInput.name().trim());
                repository.setDescription(trimToNull(repositoryInput.description()));
                repository.setRepositoryUrl(repositoryInput.repositoryUrl().trim());
                repository.setBaseBranch(repositoryInput.baseBranch() == null || repositoryInput.baseBranch().isBlank()
                        ? "main" : repositoryInput.baseBranch().trim());
                repository.setPrimaryRepo(repositoryInput.primaryRepo());
                repositoryRepository.save(repository);
                repositoryCount++;
            }
            for (DatabaseInput databaseInput : safeList(item.databases())) {
                DatabaseEntity database = new DatabaseEntity();
                database.setProjectId(project.getId());
                database.setEnvironmentId(environment.getId());
                database.setCode(databaseInput.code().trim());
                database.setName(databaseInput.name().trim());
                database.setUrl(databaseInput.url().trim());
                database.setUsername(trimToNull(databaseInput.username()));
                database.setPassword(trimToNull(databaseInput.password()));
                database.setSchemaHint(trimToNull(databaseInput.schemaHint()));
                databaseRepository.save(database);
                databaseCount++;
            }
        }
        for (RelationInput item : safeList(input.relations())) {
            requireProject(item.relatedProjectId());
            RelationEntity relation = new RelationEntity();
            relation.setProjectId(project.getId());
            relation.setRelatedProjectId(item.relatedProjectId());
            relation.setDirection(item.direction().trim());
            relation.setRelationType(item.relationType().trim());
            relation.setName(trimToNull(item.name()));
            relation.setDescription(trimToNull(item.description()));
            relationRepository.save(relation);
        }
        log.info("Saved demo project projectId={}, code={}, environments={}, repositories={}, databases={}, relations={}",
                project.getId(), project.getCode(), safeList(input.environments()).size(), repositoryCount,
                databaseCount, safeList(input.relations()).size());
        return toProjectView(project, true);
    }

    @Transactional
    public void deleteProject(Long projectId) {
        ProjectEntity project = requireProject(projectId);
        documentRepository.deleteByProjectCodeIgnoreCase(project.getCode());
        documentSearchIndex.deleteProject(project.getCode());
        projectRepository.delete(project);
        log.info("Deleted demo project projectId={}, code={}", projectId, project.getCode());
    }

    @Transactional(readOnly = true)
    public List<EnvironmentView> environments(Long projectId) {
        requireProject(projectId);
        return environmentRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(this::toEnvironmentView).toList();
    }

    @Transactional(readOnly = true)
    public List<RepositoryView> repositories(Long projectId, String environmentCode) {
        requireProject(projectId);
        if (environmentCode == null || environmentCode.isBlank()) {
            return repositoryRepository.findByProjectIdOrderByPrimaryRepoDescNameAsc(projectId).stream()
                    .map(this::toRepositoryView).toList();
        }
        EnvironmentEntity environment = requireEnvironment(projectId, environmentCode);
        return repositoryRepository.findByEnvironmentIdOrderByPrimaryRepoDescNameAsc(environment.getId()).stream()
                .map(this::toRepositoryView).toList();
    }

    @Transactional(readOnly = true)
    public List<RelationView> relations(Long projectId) {
        requireProject(projectId);
        return relationRepository.findByProjectIdOrderByIdAsc(projectId).stream().map(this::toRelationView).toList();
    }

    @Transactional(readOnly = true)
    public List<ResourceConfigView> resourceConfigs(Long projectId, String environmentCode,
                                                    String configType, String configCode, String label) {
        ProjectEntity project = requireProject(projectId);
        List<ResourceConfigView> result = new ArrayList<>();
        for (EnvironmentEntity environment : environmentRepository.findByProjectIdOrderByNameAsc(projectId)) {
            if (environmentCode != null && !environmentCode.isBlank()
                    && !environment.getCode().equalsIgnoreCase(environmentCode)) {
                continue;
            }
            result.add(toLogConfig(project, environment));
            databaseRepository.findByEnvironmentIdOrderByNameAsc(environment.getId()).stream()
                    .map(database -> toJdbcConfig(project, environment, database))
                    .forEach(result::add);
        }
        return result.stream()
                .filter(config -> configType == null || configType.isBlank() || config.configType().equalsIgnoreCase(configType))
                .filter(config -> configCode == null || configCode.isBlank() || config.configCode().equalsIgnoreCase(configCode))
                .filter(config -> label == null || label.isBlank() || config.labels().stream().anyMatch(label::equalsIgnoreCase))
                .toList();
    }

    @Transactional(readOnly = true)
    public ResourceConfigView resourceConfig(String configId, Long projectId) {
        String[] parts = configId.split(":", 3);
        if (parts.length != 3) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源配置不存在");
        }
        Long configProjectId = parseId(parts[1]);
        if (projectId != null && !projectId.equals(configProjectId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不属于指定项目");
        }
        ProjectEntity project = requireProject(configProjectId);
        if ("http-log".equals(parts[0])) {
            return toLogConfig(project, requireEnvironment(configProjectId, parts[2]));
        }
        if ("jdbc".equals(parts[0])) {
            DatabaseEntity database = databaseRepository.findById(parseId(parts[2]))
                    .filter(item -> item.getProjectId().equals(configProjectId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "数据库资源配置不存在"));
            EnvironmentEntity environment = environmentRepository.findById(database.getEnvironmentId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "数据库所属环境不存在"));
            return toJdbcConfig(project, environment, database);
        }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源配置不存在");
    }

    @Transactional(readOnly = true)
    public ProjectContextView context(Long projectId, String environmentCode) {
        ProjectView project = getProject(projectId);
        List<ProjectView> relatedProjects = project.relations().stream()
                .map(RelationView::relatedProjectId)
                .distinct()
                .map(id -> toProjectView(requireProject(id), false))
                .toList();
        return new ProjectContextView(project, relatedProjects, project.environments(),
                repositories(projectId, environmentCode), project.relations(),
                resourceConfigs(projectId, environmentCode, null, null, null));
    }

    private List<ProjectView> listProjects(List<String> keywords, boolean details) {
        List<String> normalized = keywords == null ? List.of() : keywords.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
        return projectRepository.findAllByOrderByNameAsc().stream()
                .filter(project -> normalized.isEmpty() || normalized.stream().anyMatch(keyword ->
                        contains(project.getCode(), keyword) || contains(project.getName(), keyword)
                                || contains(project.getDescription(), keyword)))
                .map(project -> toProjectView(project, details)).toList();
    }

    private ProjectView toProjectView(ProjectEntity project, boolean details) {
        return new ProjectView(project.getId(), project.getCode(), project.getName(), project.getDescription(),
                project.getCreatedAt(), project.getUpdatedAt(), details ? environments(project.getId()) : List.of(),
                details ? repositories(project.getId(), null) : List.of(), details ? relations(project.getId()) : List.of());
    }

    private EnvironmentView toEnvironmentView(EnvironmentEntity entity) {
        List<RepositoryView> repositories = repositoryRepository
                .findByEnvironmentIdOrderByPrimaryRepoDescNameAsc(entity.getId()).stream()
                .map(this::toRepositoryView).toList();
        List<DatabaseView> databases = databaseRepository.findByEnvironmentIdOrderByNameAsc(entity.getId()).stream()
                .map(this::toDatabaseView).toList();
        return new EnvironmentView(entity.getId(), entity.getProjectId(), entity.getCode(), entity.getName(),
                entity.getDeploymentBranch(), true, repositories, databases);
    }

    private RepositoryView toRepositoryView(RepositoryEntity entity) {
        EnvironmentEntity environment = environmentRepository.findById(entity.getEnvironmentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "仓库所属环境不存在"));
        return new RepositoryView(entity.getId(), entity.getProjectId(), entity.getEnvironmentId(),
                environment.getCode(), environment.getName(), entity.getCode(), entity.getName(),
                entity.getDescription(), entity.getRepositoryUrl(), entity.getBaseBranch(),
                entity.isPrimaryRepo(), true);
    }

    private DatabaseView toDatabaseView(DatabaseEntity entity) {
        EnvironmentEntity environment = environmentRepository.findById(entity.getEnvironmentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "数据库所属环境不存在"));
        return new DatabaseView(entity.getId(), entity.getProjectId(), entity.getEnvironmentId(),
                environment.getCode(), environment.getName(), entity.getCode(), entity.getName(), entity.getUrl(),
                entity.getUsername(), entity.getPassword(), entity.getSchemaHint(), true);
    }

    private RelationView toRelationView(RelationEntity entity) {
        ProjectEntity related = requireProject(entity.getRelatedProjectId());
        return new RelationView(entity.getId(), entity.getProjectId(), entity.getRelatedProjectId(),
                related.getCode(), related.getName(), entity.getDirection(), entity.getRelationType(),
                entity.getName(), entity.getDescription(), true);
    }

    private ResourceConfigView toLogConfig(ProjectEntity project, EnvironmentEntity environment) {
        String baseUrl = demoProperties.getPublicBaseUrl().replaceAll("/+$", "");
        String root = "/demo/logs/" + project.getCode() + "/" + environment.getCode();
        HttpLogConfig config = new HttpLogConfig(baseUrl + "/files/logs", root, "*.log", "UTF-8",
                demoProperties.getAccessToken(), "优先按 traceId、服务名和异常关键词检索示例日志。");
        return new ResourceConfigView("http-log:" + project.getId() + ":" + environment.getCode(),
                project.getId(), environment.getId(), environment.getCode(), environment.getName(),
                "http-log", "http-log-" + environment.getCode(), environment.getName() + " HTTP 日志",
                "由示例资源服务提供的只读日志", List.of("日志", "HTTP"), config, true);
    }

    private ResourceConfigView toJdbcConfig(ProjectEntity project, EnvironmentEntity environment,
                                            DatabaseEntity database) {
        JdbcConfig config = new JdbcConfig(database.getUrl(), database.getUsername(),
                database.getPassword(), database.getSchemaHint());
        return new ResourceConfigView("jdbc:" + project.getId() + ":" + database.getId(), project.getId(),
                environment.getId(), environment.getCode(), environment.getName(), "jdbc", database.getCode(),
                database.getName(), "由示例资源服务维护的环境级数据库连接", List.of("数据库", "JDBC"),
                config, true);
    }

    private ProjectEntity requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在"));
    }

    private EnvironmentEntity requireEnvironment(Long projectId, String environmentCode) {
        return environmentRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .filter(item -> item.getCode().equalsIgnoreCase(environmentCode))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目环境不存在"));
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源配置不存在");
        }
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }
}
