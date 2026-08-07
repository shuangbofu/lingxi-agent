package top.fusb.lingxi.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskEventTypeTest {

    @Test
    void shouldNormalizeUnknownPersistedOperationAsCommand() {
        assertThat(TaskEventType.parse("LEGACY_OPERATION")).isEqualTo(TaskEventType.COMMAND);
        assertThat(TaskEventType.parse("thinking")).isEqualTo(TaskEventType.THINKING);
        assertThat(TaskEventType.parse(null)).isEqualTo(TaskEventType.SYSTEM);
    }
}
