package top.fusb.lingxi.demo.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;
import java.util.List;

public final class ApiModels {

    private ApiModels() {
    }

    public record EnvironmentInput(Long id,
                                   @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String code,
                                   @NotBlank String name,
                                   String deploymentBranch,
                                   @Valid List<RepositoryInput> repositories,
                                   @Valid List<DatabaseInput> databases) {
    }

    public record RepositoryInput(Long id,
                                  @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String code,
                                  @NotBlank String name,
                                  String description, @NotBlank String repositoryUrl,
                                  String baseBranch, boolean primaryRepo) {
    }

    public record DatabaseInput(Long id,
                                @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String code,
                                @NotBlank String name, @NotBlank String url,
                                String username, String password, String schemaHint) {
    }

    public record RelationInput(Long id, @NotNull Long relatedProjectId, @NotBlank String direction,
                                @NotBlank String relationType, String name, String description) {
    }

    public record ProjectInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String code,
                               @NotBlank String name, String description,
                               @Valid List<EnvironmentInput> environments,
                               @Valid List<RelationInput> relations) {
    }

    public record ProjectView(Long id, String code, String name, String description,
                              LocalDateTime createdAt, LocalDateTime updatedAt,
                              List<EnvironmentView> environments,
                              List<RepositoryView> repositories,
                              List<RelationView> relations) {
    }

    public record EnvironmentView(Long id, Long projectId, String code, String name,
                                  String deploymentBranch, boolean enabled,
                                  List<RepositoryView> repositories,
                                  List<DatabaseView> databases) {
    }

    public record RepositoryView(Long id, Long projectId, Long environmentId,
                                 String environmentCode, String environmentName,
                                 String code, String name,
                                 String description, String repositoryUrl, String baseBranch,
                                 boolean primaryRepo, boolean enabled) {
    }

    public record DatabaseView(Long id, Long projectId, Long environmentId,
                               String environmentCode, String environmentName,
                               String code, String name, String url, String username,
                               String password, String schemaHint, boolean enabled) {
    }

    public record RelationView(Long id, Long projectId, Long relatedProjectId,
                               String relatedProjectCode, String relatedProjectName,
                               String direction, String relationType, String name,
                               String description, boolean enabled) {
    }

    public record HttpLogConfig(String baseUrl, String root, String filePattern,
                                String encoding, String token, String readHint) {
    }

    public record JdbcConfig(String url, String username, String password, String schemaHint) {
    }

    public record ResourceConfigView(String id, Long projectId, Long environmentId,
                                     String environmentCode, String environmentName,
                                     String configType, String configCode, String name,
                                     String description, List<String> labels,
                                     Object config, boolean enabled) {
    }

    public record ProjectContextView(ProjectView project, List<ProjectView> relatedProjects,
                                     List<EnvironmentView> environments,
                                     List<RepositoryView> repositories,
                                     List<RelationView> relations,
                                     List<ResourceConfigView> resourceConfigs) {
    }

    public record DocumentInput(@NotBlank String projectCode, @NotBlank String title,
                                @NotBlank String path, String category, @NotBlank String content) {
    }

    public record DocumentView(Long id, String projectCode, String title, String path,
                               String category, String content, int revision,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record LogInput(@NotNull Long projectId,
                           @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String environmentCode,
                           @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String serviceName,
                           LocalDateTime occurredAt,
                           String level, String traceId, @NotBlank String content) {
    }

    public record LogBatchInput(@NotNull Long projectId,
                                @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String environmentCode,
                                @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String serviceName,
                                String level, String traceId,
                                @NotBlank String content) {
    }

    public record LogView(Long id, Long projectId, String projectCode, String environmentCode,
                          String serviceName, LocalDateTime occurredAt, String level,
                          String traceId, String content) {
    }

    public record DemoInfo(String baseUrl, String token, String projectHubBaseUrl,
                           String wikiBaseUrl, String httpLogBaseUrl) {
    }
}
