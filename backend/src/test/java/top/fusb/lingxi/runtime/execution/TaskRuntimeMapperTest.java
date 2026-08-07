package top.fusb.lingxi.runtime.execution;

import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;
import top.fusb.lingxi.runtime.api.support.RuntimeSensitiveText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRuntimeMapperTest {

    @Test
    void preservesModelMetricsAndActionGroupAfterRuntimeRedaction() {
        RuntimeEvent source = new RuntimeEvent(
                RuntimeEventType.METRIC,
                RuntimeEventStatus.SUCCESS,
                "runtime.model.request.completed",
                null,
                new RuntimeEventPayload(
                        "runtime.model.request.completed", "model_request", "model-request-1", "success",
                        null, null, null, null, null, null, null,
                        "runtime:model-request", "model-request-1", "模型 API 请求", "MEASURED", false,
                        Map.of(
                                "model", "deepseek-test",
                                "inputTokens", "1200",
                                "errorMessage", "authorization=secret-token-value"
                        ),
                        "langchain:model-request-1", 2
                ).withActionIcon(RuntimeActionIcon.CODE)
        );

        RuntimeEvent safeEvent = RuntimeSensitiveText.redact(source, List.of("secret-token-value"));
        TaskExecutionEvent mapped = TaskRuntimeMapper.toTaskEvent(safeEvent);

        assertThat(mapped.getPayload().getMetrics())
                .containsEntry("model", "deepseek-test")
                .containsEntry("inputTokens", "1200")
                .containsEntry("errorMessage", "authorization=" + RuntimeSensitiveText.REDACTED);
        assertThat(mapped.getPayload().getActionGroupId()).isEqualTo("langchain:model-request-1");
        assertThat(mapped.getPayload().getActionGroupSize()).isEqualTo(2);
        assertThat(mapped.getPayload().getActionIcon()).isEqualTo(RuntimeActionIcon.CODE);
    }
}
