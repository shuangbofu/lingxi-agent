package top.fusb.lingxi.demo.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.fusb.lingxi.demo.config.DemoProperties;
import top.fusb.lingxi.demo.service.DocumentService;
import top.fusb.lingxi.demo.service.LogService;
import top.fusb.lingxi.demo.service.ProjectService;
import top.fusb.lingxi.demo.web.ApiModels.DemoInfo;
import top.fusb.lingxi.demo.web.ApiModels.DocumentInput;
import top.fusb.lingxi.demo.web.ApiModels.DocumentView;
import top.fusb.lingxi.demo.web.ApiModels.LogBatchInput;
import top.fusb.lingxi.demo.web.ApiModels.LogInput;
import top.fusb.lingxi.demo.web.ApiModels.LogView;
import top.fusb.lingxi.demo.web.ApiModels.ProjectInput;
import top.fusb.lingxi.demo.web.ApiModels.ProjectView;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ProjectService projectService;
    private final DocumentService documentService;
    private final LogService logService;
    private final DemoProperties demoProperties;

    @GetMapping("/info")
    public DemoInfo info() {
        String baseUrl = demoProperties.getPublicBaseUrl().replaceAll("/+$", "");
        return new DemoInfo(baseUrl, demoProperties.getAccessToken(), baseUrl, baseUrl, baseUrl + "/files/logs");
    }

    @GetMapping("/projects")
    public List<ProjectView> projects() {
        return projectService.listAdminProjects();
    }

    @GetMapping("/projects/{projectId}")
    public ProjectView project(@PathVariable Long projectId) {
        return projectService.getProject(projectId);
    }

    @PostMapping("/projects")
    public ProjectView createProject(@Valid @RequestBody ProjectInput input) {
        return projectService.saveProject(null, input);
    }

    @PutMapping("/projects/{projectId}")
    public ProjectView updateProject(@PathVariable Long projectId, @Valid @RequestBody ProjectInput input) {
        return projectService.saveProject(projectId, input);
    }

    @DeleteMapping("/projects/{projectId}")
    public boolean deleteProject(@PathVariable Long projectId) {
        projectService.deleteProject(projectId);
        return true;
    }

    @GetMapping("/documents")
    public List<DocumentView> documents(@RequestParam(required = false) String projectCode) {
        return documentService.listAdminDocuments(projectCode);
    }

    @PostMapping("/documents")
    public DocumentView createDocument(@Valid @RequestBody DocumentInput input) {
        return documentService.saveDocument(null, input);
    }

    @PutMapping("/documents/{documentId}")
    public DocumentView updateDocument(@PathVariable Long documentId, @Valid @RequestBody DocumentInput input) {
        return documentService.saveDocument(documentId, input);
    }

    @DeleteMapping("/documents/{documentId}")
    public boolean deleteDocument(@PathVariable Long documentId) {
        documentService.deleteDocument(documentId);
        return true;
    }

    @GetMapping("/logs")
    public List<LogView> logs(@RequestParam(required = false) Long projectId,
                              @RequestParam(required = false) String environmentCode) {
        return logService.listAdminLogs(projectId, environmentCode);
    }

    @PostMapping("/logs")
    public LogView createLog(@Valid @RequestBody LogInput input) {
        return logService.saveLog(input);
    }

    @PostMapping("/logs/batch")
    public List<LogView> createLogBatch(@Valid @RequestBody LogBatchInput input) {
        return logService.saveBatch(input);
    }

    @DeleteMapping("/logs/{logId}")
    public boolean deleteLog(@PathVariable Long logId) {
        logService.deleteLog(logId);
        return true;
    }
}
