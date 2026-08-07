package top.fusb.lingxi.demo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.fusb.lingxi.demo.service.ProjectService;
import top.fusb.lingxi.demo.web.ApiModels.EnvironmentView;
import top.fusb.lingxi.demo.web.ApiModels.ProjectContextView;
import top.fusb.lingxi.demo.web.ApiModels.ProjectView;
import top.fusb.lingxi.demo.web.ApiModels.RelationView;
import top.fusb.lingxi.demo.web.ApiModels.RepositoryView;
import top.fusb.lingxi.demo.web.ApiModels.ResourceConfigView;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/project-context")
@RequiredArgsConstructor
public class ProjectContextController {

    private final ProjectService projectService;

    @GetMapping("/projects")
    public List<ProjectView> projects(@RequestParam(required = false) String query,
                                      @RequestParam(required = false) List<String> keywords) {
        List<String> terms = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            terms.add(query);
        }
        if (keywords != null) {
            terms.addAll(keywords);
        }
        return projectService.listProjects(terms);
    }

    @GetMapping("/environments")
    public List<EnvironmentView> environments(@RequestParam Long projectId) {
        return projectService.environments(projectId);
    }

    @GetMapping("/repositories")
    public List<RepositoryView> repositories(@RequestParam Long projectId,
                                             @RequestParam(required = false) String environmentCode) {
        return projectService.repositories(projectId, environmentCode);
    }

    @GetMapping("/relations")
    public List<RelationView> relations(@RequestParam Long projectId) {
        return projectService.relations(projectId);
    }

    @GetMapping("/resource-configs")
    public List<ResourceConfigView> resourceConfigs(@RequestParam Long projectId,
                                                    @RequestParam(required = false) String environmentCode,
                                                    @RequestParam(required = false) String configType,
                                                    @RequestParam(required = false) String configCode,
                                                    @RequestParam(required = false) String label) {
        return projectService.resourceConfigs(projectId, environmentCode, configType, configCode, label);
    }

    @GetMapping("/resource-configs/{configId}")
    public ResourceConfigView resourceConfig(@PathVariable String configId,
                                             @RequestParam(required = false) Long projectId) {
        return projectService.resourceConfig(configId, projectId);
    }

    @GetMapping("/project-info")
    public ProjectContextView projectInfo(@RequestParam Long projectId,
                                          @RequestParam(required = false) String environmentCode) {
        return projectService.context(projectId, environmentCode);
    }

    @GetMapping("/project-context")
    public ProjectContextView projectContext(@RequestParam Long projectId,
                                             @RequestParam(required = false) String environmentCode) {
        return projectService.context(projectId, environmentCode);
    }
}
