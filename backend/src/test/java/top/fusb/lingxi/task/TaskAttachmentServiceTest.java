package top.fusb.lingxi.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.dto.TaskAttachmentResponse;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskAttachmentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void copiesAttachmentToWorkspaceWithoutUsingOriginalName() throws Exception {
        LingxiProperties properties = new LingxiProperties();
        properties.getTask().setContentDir(tempDir.resolve("task-content").toString());
        TaskAttachmentService service = new TaskAttachmentService(properties, new ObjectMapper());
        UserEntity owner = new UserEntity();
        owner.setId(7L);
        MockMultipartFile file = new MockMultipartFile("file",
                "ChatGPT Image 2026年7月20日 15_00_09.png", "image/png", new byte[]{1, 2, 3});

        TaskAttachmentResponse uploaded = service.upload(file, null, owner);
        Path workspaceAttachments = tempDir.resolve("workspace/attachments");
        List<String> filenames = service.copyToWorkspace(List.of(uploaded), owner.getId(), workspaceAttachments);

        assertThat(uploaded.getName()).isEqualTo("ChatGPT Image 2026年7月20日 15_00_09.png");
        assertThat(filenames).singleElement().satisfies(filename -> {
            assertThat(filename).matches("01-attachment-[a-f0-9]{12}\\.png");
            assertThat(filename).doesNotContain("ChatGPT", "年", "月", "日");
            assertThat(workspaceAttachments.resolve(filename)).hasBinaryContent(new byte[]{1, 2, 3});
        });
        try (var files = Files.list(workspaceAttachments)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void uploadsLongUserInputAsTextAttachmentAndSkipsItInAttachmentLimit() throws Exception {
        LingxiProperties properties = new LingxiProperties();
        properties.getTask().setContentDir(tempDir.resolve("task-content").toString());
        TaskAttachmentService service = new TaskAttachmentService(properties, new ObjectMapper());
        UserEntity owner = new UserEntity();
        owner.setId(7L);

        MockMultipartFile textFile = new MockMultipartFile("file",
                "用户输入详情.txt", "text/plain;charset=utf-8",
                "很长的用户输入内容".getBytes(StandardCharsets.UTF_8));
        TaskAttachmentResponse textAttachment = service.upload(textFile, "user-input", owner);
        assertThat(textAttachment.getInputKind()).isEqualTo("user-input");
        assertThat(textAttachment.getContentType()).isEqualTo("text/plain");

        String generatedContent = "后端自动保存的长文本";
        TaskAttachmentResponse generatedAttachment = service.createUserInputAttachment(generatedContent, owner);
        assertThat(service.read(generatedAttachment.getId(), owner).resource().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(generatedContent);

        List<TaskAttachmentResponse> regular = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            regular.add(service.upload(new MockMultipartFile("file",
                    "image-" + index + ".png", "image/png", new byte[]{1, 2, 3}), null, owner));
        }
        List<String> ids = new ArrayList<>();
        regular.forEach(attachment -> ids.add(attachment.getId()));
        ids.add(textAttachment.getId());
        assertThat(service.resolve(ids, owner)).hasSize(6);

        List<String> sixRegular = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            sixRegular.add(service.upload(new MockMultipartFile("file",
                    "image-" + index + ".png", "image/png", new byte[]{1, 2, 3}), null, owner).getId());
        }
        assertThatThrownBy(() -> service.resolve(sixRegular, owner))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多上传 5 个附件");

        assertThatThrownBy(() -> service.resolve(List.of(textAttachment.getId(), generatedAttachment.getId()), owner))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能包含 1 个全文附件");

        MockMultipartFile oversizedText = new MockMultipartFile("file", "超长输入.txt", "text/plain",
                "文".repeat(50_001).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.upload(oversizedText, "user-input", owner))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能超过 50000 个字符");
    }
}
