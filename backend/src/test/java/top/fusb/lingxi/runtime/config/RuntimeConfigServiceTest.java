package top.fusb.lingxi.runtime.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceTest {

    private final RuntimeConfigRepository repository = mock(RuntimeConfigRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ModelCatalogService modelCatalogService = mock(ModelCatalogService.class);
    private final RuntimeConfigService service = new RuntimeConfigService(
            repository, eventPublisher, modelCatalogService);
    private RuntimeConfigEntity existing;

    @BeforeEach
    void setUp() {
        existing = new RuntimeConfigEntity();
        existing.setId(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(RuntimeConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldSaveOnlyGlobalRuntimeSettings() {
        RuntimeConfigRequest request = new RuntimeConfigRequest();
        request.setMaxTaskConcurrency(30);
        request.setTaskExecutionTimeoutMinutes(90);
        request.setGlobalDailyTokenLimit(10_000L);
        request.setGlobalBoundaryPrompt("全局边界");

        RuntimeConfigResponse response = service.save(request);

        assertThat(response.getMaxTaskConcurrency()).isEqualTo(20);
        assertThat(response.getTaskExecutionTimeoutMinutes()).isEqualTo(90);
        assertThat(service.taskExecutionTimeoutSeconds()).isEqualTo(5_400L);
        assertThat(response.getGlobalDailyTokenLimit()).isEqualTo(10_000L);
        assertThat(response.getGlobalBoundaryPrompt()).isEqualTo("全局边界");
        verify(repository).save(existing);
        verify(eventPublisher).publishEvent(any(RuntimeConfigChangedEvent.class));
    }

    @Test
    void shouldUseOneHourDefaultTaskExecutionTimeout() {
        assertThat(service.detail().getTaskExecutionTimeoutMinutes()).isEqualTo(60);
        assertThat(service.taskExecutionTimeoutSeconds()).isEqualTo(3_600L);
    }

}
