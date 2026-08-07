package top.fusb.lingxi.controller;

import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.dto.TaskContinueRequest;
import top.fusb.lingxi.dto.TaskCreateRequest;
import top.fusb.lingxi.dto.TaskEventContentResponse;
import top.fusb.lingxi.dto.TaskExecutionReportResponse;
import top.fusb.lingxi.dto.TaskInteractionAnswerRequest;
import top.fusb.lingxi.dto.TaskInteractionResponse;
import top.fusb.lingxi.dto.TaskResponse;
import top.fusb.lingxi.dto.TaskRoundSummaryResponse;
import top.fusb.lingxi.dto.TaskRerunRequest;
import top.fusb.lingxi.dto.TaskResumeRequest;
import top.fusb.lingxi.dto.UserResponse;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.auth.AuthService;
import top.fusb.lingxi.task.TaskEventContentService;
import top.fusb.lingxi.task.TaskEventService;
import top.fusb.lingxi.task.TaskEventStreamService;
import top.fusb.lingxi.task.TaskExecutionReportService;
import top.fusb.lingxi.task.TaskInteractionService;
import top.fusb.lingxi.task.TaskArtifactService;
import top.fusb.lingxi.task.TaskService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskEventStreamService taskEventStreamService;
    private final TaskEventContentService taskEventContentService;
    private final TaskEventService taskEventService;
    private final TaskInteractionService taskInteractionService;
    private final TaskArtifactService taskArtifactService;
    private final TaskExecutionReportService taskExecutionReportService;
    private final AuthService authService;

    @PostMapping
    @RequirePermission("TASK_CREATE")
    public TaskResponse create(@Valid @RequestBody TaskCreateRequest request, HttpSession session) {
        return taskService.create(request, authService.requireUser(session));
    }

    @GetMapping
    @RequirePermission("TASK_READ")
    public PageResult<TaskResponse> page(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size,
                                         @RequestParam(required = false) String scenario,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String recordType,
                                         @RequestParam(required = false) String scope,
                                         @RequestParam(required = false) String query,
                                         @RequestParam(required = false) Long ownerId,
                                         @RequestParam(required = false) String createdStart,
                                         @RequestParam(required = false) String createdEnd,
                                         @RequestParam(required = false) String runtimeCode,
                                         @RequestParam(required = false) String modelProfileId,
                                         @RequestParam(required = false) String sortBy,
                                         @RequestParam(required = false) Boolean aggregateConversation,
                                         HttpSession session) {
        return taskService.page(page, size, scenario, status, recordType, scope, query, ownerId, createdStart,
                createdEnd, runtimeCode, modelProfileId, sortBy, aggregateConversation, authService.requireUser(session));
    }

    @GetMapping("/owners")
    @RequirePermission("TASK_READ")
    public List<UserResponse> owners() {
        return taskService.owners();
    }

    @GetMapping("/{id}")
    @RequirePermission("TASK_READ")
    public TaskResponse detail(@PathVariable Long id,
                               @RequestParam(defaultValue = "false") boolean includeEvents,
                               HttpSession session) {
        UserEntity user = authService.requireUser(session);
        return taskService.detail(id, user, includeEvents);
    }

    @GetMapping("/{id}/execution-report")
    @RequirePermission("TASK_ADMIN")
    public TaskExecutionReportResponse executionReport(@PathVariable Long id, HttpSession session) {
        return taskExecutionReportService.build(
                taskService.requireProcessAccessibleEntity(id, authService.requireUser(session)));
    }

    @GetMapping("/{id}/rounds")
    @RequirePermission("TASK_READ")
    public List<TaskRoundSummaryResponse> rounds(@PathVariable Long id, HttpSession session) {
        return taskService.rounds(id, authService.requireUser(session));
    }

    @PostMapping("/{id}/rounds")
    @RequirePermission("TASK_CREATE")
    public TaskResponse continueRound(@PathVariable Long id,
                                      @Valid @RequestBody TaskContinueRequest request,
                                      HttpSession session) {
        return taskService.continueRound(id, request, authService.requireUser(session));
    }

    @GetMapping("/{id}/events/stream")
    @RequirePermission("TASK_READ")
    public SseEmitter streamEvents(@PathVariable Long id,
                                   @RequestParam(defaultValue = "0") Long afterId,
                                   @RequestParam(defaultValue = "true") boolean includeTaskEvents,
                                   @RequestParam String connectionId,
                                   HttpSession session) {
        UserEntity user = authService.requireUser(session);
        taskService.requireAccessibleEntity(id, user);
        return taskEventStreamService.stream(id, afterId, taskService.canViewProcessOutput(user), includeTaskEvents, connectionId);
    }

    @GetMapping("/{id}/events/{eventId}/content")
    @RequirePermission("TASK_READ")
    public TaskEventContentResponse eventContent(@PathVariable Long id,
                                                 @PathVariable Long eventId,
                                                 @RequestParam String field,
                                                 HttpSession session) {
        taskService.requireProcessAccessibleEntity(id, authService.requireUser(session));
        taskEventService.requireEventBelongsToTask(id, eventId);
        TaskEventContentResponse response = new TaskEventContentResponse();
        response.setContent(taskEventContentService.read(id, eventId, field));
        return response;
    }

    @GetMapping("/{id}/artifacts")
    @RequirePermission("TASK_READ")
    public ResponseEntity<Resource> artifact(@PathVariable Long id,
                                             @RequestParam String path,
                                             HttpSession session) {
        taskService.requireAccessibleEntity(id, authService.requireUser(session));
        Resource resource = taskArtifactService.read(id, path);
        String filename = resource.getFilename() == null ? "artifact" : resource.getFilename();
        MediaType mediaType = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
        if (mediaType.getCharset() == null && ("text".equals(mediaType.getType())
                || mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)
                || mediaType.isCompatibleWith(MediaType.APPLICATION_XML))) {
            mediaType = new MediaType(mediaType.getType(), mediaType.getSubtype(), StandardCharsets.UTF_8);
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header("Content-Disposition", ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }

    @GetMapping("/{id}/interactions")
    @RequirePermission("TASK_READ")
    public List<TaskInteractionResponse> interactions(@PathVariable Long id, HttpSession session) {
        taskService.detail(id, authService.requireUser(session));
        return taskInteractionService.list(id);
    }

    @PostMapping("/{id}/interactions/{interactionId}/answer")
    @RequirePermission("TASK_CREATE")
    public TaskInteractionResponse answerInteraction(@PathVariable Long id,
                                                     @PathVariable Long interactionId,
                                                     @Valid @RequestBody TaskInteractionAnswerRequest request,
                                                     HttpSession session) {
        taskService.detail(id, authService.requireUser(session));
        return taskInteractionService.answer(id, interactionId, request);
    }

    @PostMapping("/{id}/cancel")
    @RequirePermission("TASK_CANCEL")
    public TaskResponse cancel(@PathVariable Long id, HttpSession session) {
        return taskService.cancel(id, authService.requireUser(session));
    }

    @PostMapping("/{id}/retry")
    @RequirePermission("TASK_CREATE")
    public TaskResponse retry(@PathVariable Long id, HttpSession session) {
        return taskService.retry(id, authService.requireUser(session));
    }

    @PostMapping("/{id}/resume")
    @RequirePermission("TASK_CREATE")
    public TaskResponse resume(@PathVariable Long id,
                               @Valid @RequestBody(required = false) TaskResumeRequest request,
                               HttpSession session) {
        return taskService.resumeCanceled(id, request, authService.requireUser(session));
    }

    @PostMapping("/{id}/rerun")
    @RequirePermission("TASK_CREATE")
    public TaskResponse rerun(@PathVariable Long id,
                              @Valid @RequestBody TaskRerunRequest request,
                              HttpSession session) {
        return taskService.rerun(id, request, authService.requireUser(session));
    }
}
