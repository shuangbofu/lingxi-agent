package top.fusb.lingxi.repository;

import top.fusb.lingxi.entity.AgentScenarioEntity;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.enums.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AgentScenarioDeletionRepositoryTest {

    @Autowired
    private AgentScenarioRepository scenarioRepository;

    @Autowired
    private AgentTaskRepository taskRepository;

    @Test
    void shouldPreserveTaskScenarioCodeAfterDeletingScenarioState() {
        LocalDateTime now = LocalDateTime.now();
        AgentScenarioEntity scenario = new AgentScenarioEntity();
        scenario.setCode("removed-scenario");
        scenario.setEnabled(false);
        scenario.setUserVisible(false);
        scenario.setSortOrder(1);
        scenario.setCreatedAt(now);
        scenario.setUpdatedAt(now);
        scenario = scenarioRepository.saveAndFlush(scenario);

        AgentTaskEntity task = new AgentTaskEntity();
        task.setScenarioCode(scenario.getCode());
        task.setScenarioName("Removed scenario");
        task.setScenario("REMOVED");
        task.setRuntimeCode("codex");
        task.setStatus(TaskStatus.SUCCESS);
        task.setTitle("Historical task");
        task.setUserInput("input");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        Long taskId = taskRepository.saveAndFlush(task).getId();

        scenarioRepository.deleteById(scenario.getCode());
        scenarioRepository.flush();

        assertThat(scenarioRepository.findById(scenario.getCode())).isEmpty();
        assertThat(taskRepository.findById(taskId)).get()
                .extracting(AgentTaskEntity::getScenarioCode)
                .isEqualTo("removed-scenario");
    }
}
