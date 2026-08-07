package top.fusb.lingxi.controller;

import top.fusb.lingxi.dto.TaskShareAccessRequest;
import top.fusb.lingxi.dto.TaskShareCreateResponse;
import top.fusb.lingxi.dto.TaskShareCreateRequest;
import top.fusb.lingxi.dto.TaskSharePreviewResponse;
import top.fusb.lingxi.auth.AuthService;
import top.fusb.lingxi.task.TaskShareService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TaskShareController {

    private final TaskShareService taskShareService;
    private final AuthService authService;

    @PostMapping("/api/agent/tasks/{taskId}/share")
    public TaskShareCreateResponse create(@PathVariable Long taskId,
                                          @Valid @RequestBody(required = false) TaskShareCreateRequest request,
                                          HttpSession session) {
        return taskShareService.create(taskId, request, authService.requireUser(session));
    }

    @PostMapping("/api/public/task-shares/{shareCode}/preview")
    public TaskSharePreviewResponse preview(@PathVariable String shareCode, @Valid @RequestBody TaskShareAccessRequest request) {
        return taskShareService.preview(shareCode, request.getPassword());
    }

    @GetMapping("/api/public/task-shares/{shareCode}/meta")
    public TaskSharePreviewResponse meta(@PathVariable String shareCode) {
        return taskShareService.meta(shareCode);
    }
}
