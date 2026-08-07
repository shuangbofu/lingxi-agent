package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.TaskShareCreateResponse;
import top.fusb.lingxi.dto.TaskShareCreateRequest;
import top.fusb.lingxi.dto.TaskSharePreviewResponse;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskShareEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.kit.PasswordKit;
import top.fusb.lingxi.repository.TaskShareRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskShareServiceTest {

    @Test
    void rotatesExistingSharePasswordAndStoresOnlyItsHash() {
        TaskService taskService = mock(TaskService.class);
        TaskContentService taskContentService = mock(TaskContentService.class);
        TaskShareRepository repository = mock(TaskShareRepository.class);
        TaskShareService service = new TaskShareService(taskService, taskContentService, repository);
        UserEntity user = new UserEntity();
        user.setUsername("owner");
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(21L);
        task.setStatus(TaskStatus.SUCCESS);
        TaskShareEntity share = new TaskShareEntity();
        share.setShareCode("existing-code");
        share.setTask(task);
        share.setPasswordHash(PasswordKit.hash("OLDPASS"));
        share.setCreatedAt(LocalDateTime.now());
        share.setUpdatedAt(share.getCreatedAt());
        when(taskService.requireAccessibleEntity(21L, user)).thenReturn(task);
        when(taskService.conversationRounds(21L)).thenReturn(List.of(task));
        when(taskContentService.read(21L, "result", null)).thenReturn("result");
        when(repository.findFirstByTaskIdOrderByCreatedAtAsc(21L)).thenReturn(Optional.of(share));
        TaskShareCreateRequest request = new TaskShareCreateRequest();
        request.setRoundTaskIds(List.of(21L));

        TaskShareCreateResponse response = service.create(21L, request, user);

        verify(repository).save(share);
        assertThat(response.getShareCode()).isEqualTo("existing-code");
        assertThat(response.getPassword()).hasSize(6).isNotEqualTo("OLDPASS");
        assertThat(PasswordKit.matches(response.getPassword(), share.getPasswordHash())).isTrue();
        assertThat(share.getSharedRounds()).containsExactly(task);
    }

    @Test
    void previewsSelectedRoundsInConversationOrder() {
        TaskService taskService = mock(TaskService.class);
        TaskContentService taskContentService = mock(TaskContentService.class);
        TaskShareRepository repository = mock(TaskShareRepository.class);
        TaskShareService service = new TaskShareService(taskService, taskContentService, repository);
        AgentTaskEntity first = task(21L, 1, null, "首次问题", "首次回答");
        AgentTaskEntity third = task(23L, 3, 21L, "第三轮问题", "第三轮回答");
        TaskShareEntity share = new TaskShareEntity();
        share.setShareCode("multi-round");
        share.setTask(first);
        share.setSharedRounds(new java.util.LinkedHashSet<>(List.of(third, first)));
        share.setPasswordHash(PasswordKit.hash("ABC123"));
        when(repository.findWithTaskByShareCode("multi-round")).thenReturn(Optional.of(share));
        when(taskService.conversationRounds(21L)).thenReturn(List.of(first, third));
        when(taskContentService.read(21L, "result", "首次回答")).thenReturn("首次回答");
        when(taskContentService.read(23L, "result", "第三轮回答")).thenReturn("第三轮回答");

        TaskSharePreviewResponse response = service.preview("multi-round", "ABC123");

        assertThat(response.getTitle()).isEqualTo("首次问题");
        assertThat(response.getRounds()).extracting(value -> value.getRoundNo()).containsExactly(1, 3);
        assertThat(response.getRounds()).extracting(value -> value.getResultText()).containsExactly("首次回答", "第三轮回答");
        assertThat(response.getSharedRoundNo()).isNull();
    }

    @Test
    void createsOneShareForMultipleSelectedRounds() {
        TaskService taskService = mock(TaskService.class);
        TaskContentService taskContentService = mock(TaskContentService.class);
        TaskShareRepository repository = mock(TaskShareRepository.class);
        TaskShareService service = new TaskShareService(taskService, taskContentService, repository);
        UserEntity user = new UserEntity();
        user.setUsername("owner");
        AgentTaskEntity first = task(21L, 1, null, "首次问题", "首次回答");
        AgentTaskEntity second = task(22L, 2, 21L, "第二轮问题", "第二轮回答");
        AgentTaskEntity third = task(23L, 3, 21L, "第三轮问题", "第三轮回答");
        when(taskService.requireAccessibleEntity(23L, user)).thenReturn(third);
        when(taskService.conversationRounds(21L)).thenReturn(List.of(first, second, third));
        when(taskContentService.read(21L, "result", "首次回答")).thenReturn("首次回答");
        when(taskContentService.read(23L, "result", "第三轮回答")).thenReturn("第三轮回答");
        when(repository.findFirstByTaskIdOrderByCreatedAtAsc(21L)).thenReturn(Optional.empty());
        when(repository.existsByShareCode(anyString())).thenReturn(false);
        TaskShareCreateRequest request = new TaskShareCreateRequest();
        request.setRoundTaskIds(List.of(23L, 21L));

        service.create(23L, request, user);

        ArgumentCaptor<TaskShareEntity> captor = ArgumentCaptor.forClass(TaskShareEntity.class);
        verify(repository).save(captor.capture());
        TaskShareEntity saved = captor.getValue();
        assertThat(saved.getTask()).isSameAs(first);
        assertThat(saved.getSharedRounds()).containsExactly(first, third);
        assertThat(saved.getCreatedBy()).isSameAs(user);
    }

    private AgentTaskEntity task(Long id, int roundNo, Long rootId, String input, String result) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(id);
        task.setRoundNo(roundNo);
        task.setConversationRootTaskId(rootId);
        task.setStatus(TaskStatus.SUCCESS);
        task.setTitle(input);
        task.setUserInput(input);
        task.setScenario("test");
        task.setResultText(result);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        return task;
    }
}
