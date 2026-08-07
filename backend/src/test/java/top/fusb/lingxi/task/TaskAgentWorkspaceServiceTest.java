package top.fusb.lingxi.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.dto.TaskAttachmentResponse;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.prompt.PromptTemplateService;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskRuntimeContextValueRepository;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.resource.ResourceCatalogService;
import top.fusb.lingxi.service.ModuleDefinitionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskAgentWorkspaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsBackendOwnedRuntimeWorkspaceLayout() {
        TaskAgentWorkspaceService service = service(mock(TaskAttachmentService.class));

        RuntimeWorkspaceLayout layout = service.runtimeLayout(tempDir.resolve("workspace"), "langchain");

        Path taskContextRoot = tempDir.resolve("workspace/.agent-task").toAbsolutePath().normalize();
        assertThat(layout.executionRoot()).isEqualTo(tempDir.resolve("workspace").toAbsolutePath().normalize().toString());
        assertThat(layout.taskContextRoot()).isEqualTo(taskContextRoot.toString());
        assertThat(layout.runtimeInputRoot()).isEqualTo(taskContextRoot.resolve("runtime-inputs").toString());
        assertThat(layout.artifactsRoot()).isEqualTo(taskContextRoot.resolve("artifacts").toString());
        assertThat(layout.runtimeRoot()).isEqualTo(taskContextRoot.resolve("runtime").toString());
        assertThat(layout.runtimeStateRoot()).isEqualTo(taskContextRoot.resolve("runtime/langchain").toString());
        assertThat(layout.privateRuntimeRoot()).isEqualTo(taskContextRoot.resolve("runtime/private").toString());
        assertThat(Path.of(layout.runtimeInputRoot())).isDirectory();
        assertThat(Path.of(layout.artifactsRoot())).isDirectory();
        assertThat(Path.of(layout.runtimeStateRoot())).isDirectory();
        assertThat(Path.of(layout.privateRuntimeRoot())).isDirectory();
    }

    @Test
    void attachmentManifestUsesPathRelativeToWorkspaceRoot() throws Exception {
        TaskAttachmentService attachmentService = mock(TaskAttachmentService.class);
        TaskAgentWorkspaceService service = service(attachmentService);
        TaskAttachmentResponse attachment = new TaskAttachmentResponse();
        attachment.setId("01234567-89ab-cdef-0123-456789abcdef");
        attachment.setName("原始图片.png");
        attachment.setContentType("image/png");
        attachment.setSize(3L);
        attachment.setInputKind(TaskAttachmentService.USER_INPUT_KIND);
        UserEntity owner = new UserEntity();
        owner.setId(7L);
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(174L);
        task.setOwner(owner);
        task.setAttachments(List.of(attachment));
        Path taskDir = tempDir.resolve(".agent-task");
        Path attachmentDir = taskDir.resolve("attachments");
        Files.createDirectories(taskDir);
        when(attachmentService.copyToWorkspace(any(), eq(owner.getId()), eq(attachmentDir)))
                .thenAnswer(invocation -> {
                    Files.createDirectories(attachmentDir);
                    Files.write(attachmentDir.resolve("01-attachment-0123456789ab.png"), new byte[]{1, 2, 3});
                    return List.of("01-attachment-0123456789ab.png");
                });

        ReflectionTestUtils.invokeMethod(service, "writeAttachments", task, taskDir);

        String manifest = Files.readString(taskDir.resolve("attachments.md"));
        String declaredPath = ".agent-task/attachments/01-attachment-0123456789ab.png";
        assertThat(manifest).contains("`" + declaredPath + "`");
        assertThat(manifest).contains("完整用户输入，分析前必须读取此文件");
        assertThat(tempDir.resolve(declaredPath)).hasBinaryContent(new byte[]{1, 2, 3});
    }

    private TaskAgentWorkspaceService service(TaskAttachmentService attachmentService) {
        return new TaskAgentWorkspaceService(
                mock(AgentTaskRepository.class),
                new ObjectMapper(),
                mock(TaskRuntimeContextValueRepository.class),
                mock(ResourceCatalogService.class),
                mock(TaskArtifactService.class),
                mock(TaskContentService.class),
                attachmentService,
                mock(TaskEventService.class),
                mock(ModuleDefinitionService.class),
                new PromptTemplateService()
        );
    }

}
