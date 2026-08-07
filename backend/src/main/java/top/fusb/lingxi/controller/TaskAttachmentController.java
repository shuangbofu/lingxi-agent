package top.fusb.lingxi.controller;

import top.fusb.lingxi.auth.AuthService;
import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.dto.TaskAttachmentResponse;
import top.fusb.lingxi.task.TaskAttachmentService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/task-attachments")
public class TaskAttachmentController {

    private final TaskAttachmentService taskAttachmentService;
    private final AuthService authService;

    /**
     * 上传任务待处理的图片、视频或文本附件。
     *
     * @param file 用户选择的附件文件
     * @param inputKind 附件用途标记；user-input 表示超长用户输入文本
     * @param session 当前登录会话
     * @return 可用于任务创建和页面预览的附件信息
     * @throws top.fusb.lingxi.exception.BizException 文件不合法、用户未登录或保存失败时抛出
     */
    @PostMapping
    @RequirePermission("TASK_CREATE")
    public TaskAttachmentResponse upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "inputKind", required = false) String inputKind,
                                         HttpSession session) {
        return taskAttachmentService.upload(file, inputKind, authService.requireUser(session));
    }

    /**
     * 返回当前用户已上传附件的预览内容。
     *
     * @param attachmentId 附件 ID
     * @param session 当前登录会话
     * @return 带正确媒体类型和原文件名的附件响应
     * @throws top.fusb.lingxi.exception.BizException 附件不存在、无访问权限或用户未登录时抛出
     */
    @GetMapping("/{attachmentId}/content")
    @RequirePermission("TASK_READ")
    public ResponseEntity<org.springframework.core.io.Resource> content(@PathVariable String attachmentId, HttpSession session) {
        TaskAttachmentService.AttachmentContent content = taskAttachmentService.read(attachmentId, authService.requireUser(session));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.attachment().getContentType()))
                .contentLength(content.attachment().getSize())
                .header("Content-Disposition", ContentDisposition.inline()
                        .filename(content.attachment().getName(), StandardCharsets.UTF_8).build().toString())
                .body(content.resource());
    }
}
