package top.fusb.lingxi.runtime.codex.activity;

import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexActivityEventCoordinatorTest {

    @Test
    void shouldReflectTurnToolAndModelWaitingState() {
        List<RuntimeEvent> events = new ArrayList<>();
        CodexActivityEventCoordinator coordinator = new CodexActivityEventCoordinator(events::add);

        coordinator.accept(system("codex.turn.started", RuntimeEventStatus.INFO));
        coordinator.accept(activity("image-call", RuntimeEventStatus.RUNNING));
        coordinator.accept(activity("image-call", RuntimeEventStatus.SUCCESS));
        coordinator.accept(message("图片分析完成"));
        coordinator.accept(system("codex.turn.completed", RuntimeEventStatus.SUCCESS));

        assertEquals(List.of(
                        "codex.turn.started", "codex.reasoning", "tool:view_image", "tool:view_image",
                        "codex.reasoning", "图片分析完成", "codex.response.processing",
                        "codex.turn.completed", "codex.run.finishing"),
                events.stream().map(RuntimeEvent::title).toList());
        assertEquals(2, events.stream().filter(this::isRunningThinking).count());
    }

    @Test
    void shouldWaitUntilEveryConcurrentToolHasCompleted() {
        List<RuntimeEvent> events = new ArrayList<>();
        CodexActivityEventCoordinator coordinator = new CodexActivityEventCoordinator(events::add);

        coordinator.accept(system("codex.turn.started", RuntimeEventStatus.INFO));
        coordinator.accept(activity("first-call", RuntimeEventStatus.RUNNING));
        coordinator.accept(activity("second-call", RuntimeEventStatus.RUNNING));
        coordinator.accept(activity("first-call", RuntimeEventStatus.SUCCESS));
        coordinator.accept(activity("second-call", RuntimeEventStatus.FAILED));

        assertEquals(2, events.stream().filter(this::isRunningThinking).count());
        assertTrue(isRunningThinking(events.get(events.size() - 1)));
    }

    @Test
    void shouldGroupActivitiesRunningInTheSameConcurrentWave() {
        List<RuntimeEvent> events = new ArrayList<>();
        CodexActivityEventCoordinator coordinator = new CodexActivityEventCoordinator("execution-1", events::add);

        coordinator.accept(activity("first-call", RuntimeEventStatus.RUNNING));
        coordinator.accept(activity("second-call", RuntimeEventStatus.RUNNING));
        coordinator.accept(activity("first-call", RuntimeEventStatus.SUCCESS));
        coordinator.accept(activity("second-call", RuntimeEventStatus.SUCCESS));

        List<RuntimeEvent> activities = events.stream()
                .filter(event -> "tool:view_image".equals(event.title()))
                .toList();
        assertTrue(activities.stream().allMatch(event ->
                "execution-1:action-group-1".equals(event.payload().actionGroupId())));
        assertTrue(activities.subList(1, activities.size()).stream().allMatch(event ->
                Integer.valueOf(2).equals(event.payload().actionGroupSize())));
    }

    @Test
    void shouldKeepSequentialActivitiesInTheNativeCodexToolBatch() {
        List<RuntimeEvent> events = new ArrayList<>();
        CodexActivityEventCoordinator coordinator = new CodexActivityEventCoordinator("execution-137", events::add);

        coordinator.toolBatchStarted("outer-call", "model-request-4");
        coordinator.accept(activity("first-call", RuntimeEventStatus.RUNNING));
        coordinator.accept(activity("first-call", RuntimeEventStatus.SUCCESS));
        coordinator.accept(activity("second-call", RuntimeEventStatus.RUNNING));
        coordinator.accept(activity("second-call", RuntimeEventStatus.SUCCESS));
        coordinator.toolBatchCompleted("outer-call");

        List<RuntimeEvent> activities = events.stream()
                .filter(event -> "tool:view_image".equals(event.title()))
                .toList();
        assertTrue(activities.stream().allMatch(event ->
                "execution-137:model-request-4".equals(event.payload().actionGroupId())));
        assertEquals(Integer.valueOf(2), activities.get(activities.size() - 1).payload().actionGroupSize());
        assertEquals(1, events.stream().filter(this::isRunningThinking).count());
    }

    @Test
    void shouldKeepTheNativeGroupWhenSessionCompletionArrivesBeforeStdoutCompletion() {
        List<RuntimeEvent> events = new ArrayList<>();
        CodexActivityEventCoordinator coordinator = new CodexActivityEventCoordinator("execution-137", events::add);

        coordinator.toolBatchStarted("outer-call", "model-request-4");
        coordinator.accept(activity("inner-call", RuntimeEventStatus.RUNNING));
        coordinator.toolBatchCompleted("outer-call");
        coordinator.accept(activity("inner-call", RuntimeEventStatus.SUCCESS));

        List<RuntimeEvent> activities = events.stream()
                .filter(event -> "tool:view_image".equals(event.title()))
                .toList();
        assertEquals(2, activities.size());
        assertTrue(activities.stream().allMatch(event ->
                "execution-137:model-request-4".equals(event.payload().actionGroupId())));
    }

    @Test
    void shouldExposeResponseProcessingAndResultFinalization() {
        List<RuntimeEvent> events = new ArrayList<>();
        CodexActivityEventCoordinator coordinator = new CodexActivityEventCoordinator(events::add);

        coordinator.accept(system("codex.turn.started", RuntimeEventStatus.INFO));
        coordinator.modelResponseStarted();
        coordinator.accept(system("codex.turn.started", RuntimeEventStatus.INFO));
        coordinator.finish();

        assertEquals(List.of(
                        "codex.turn.started", "codex.reasoning", "codex.response.processing",
                        "codex.turn.started", "codex.reasoning", "codex.result.finalizing"),
                events.stream().map(RuntimeEvent::title).toList());
        assertTrue(events.get(2).payload().transientEvent());
        assertTrue(events.get(5).payload().transientEvent());
    }

    @Test
    void shouldRestoreWaitingAfterAHeartbeatEvent() {
        List<RuntimeEvent> events = new ArrayList<>();
        CodexActivityEventCoordinator coordinator = new CodexActivityEventCoordinator(events::add);

        coordinator.accept(system("codex.turn.started", RuntimeEventStatus.INFO));
        coordinator.accept(system("codex.context.pressure", RuntimeEventStatus.INFO));

        assertEquals(List.of(
                        "codex.turn.started", "codex.reasoning", "codex.context.pressure", "codex.reasoning"),
                events.stream().map(RuntimeEvent::title).toList());
    }

    @Test
    void shouldExposeRuntimePreparationUntilTurnStarts() {
        List<RuntimeEvent> events = new ArrayList<>();
        CodexActivityEventCoordinator coordinator = new CodexActivityEventCoordinator(events::add);

        coordinator.preparing();
        coordinator.accept(system("codex.config.loaded", RuntimeEventStatus.INFO));
        coordinator.processStarting();
        coordinator.accept(system("codex.turn.started", RuntimeEventStatus.INFO));

        assertEquals(List.of(
                        "codex.runtime.preparing", "codex.config.loaded", "codex.process.starting",
                        "codex.turn.started", "codex.reasoning"),
                events.stream().map(RuntimeEvent::title).toList());
        assertEquals(RuntimeActionIcon.WRENCH, events.get(0).payload().actionIcon());
        assertEquals(RuntimeActionIcon.PLAY_CIRCLE, events.get(2).payload().actionIcon());
    }

    @Test
    void shouldExposeThinkingWhenTheSessionReportsARealModelRequest() {
        List<RuntimeEvent> events = new ArrayList<>();
        CodexActivityEventCoordinator coordinator = new CodexActivityEventCoordinator(events::add);

        coordinator.accept(system("codex.turn.started", RuntimeEventStatus.INFO));
        coordinator.modelRequestStarted();

        assertEquals(List.of("codex.turn.started", "codex.reasoning", "codex.reasoning"),
                events.stream().map(RuntimeEvent::title).toList());
        assertTrue(isRunningThinking(events.get(events.size() - 1)));
        assertTrue(events.get(events.size() - 1).payload().transientEvent());
    }

    private RuntimeEvent system(String title, RuntimeEventStatus status) {
        return new RuntimeEvent(RuntimeEventType.SYSTEM, status, title, null, null);
    }

    private RuntimeEvent activity(String callId, RuntimeEventStatus status) {
        RuntimeEventPayload payload = new RuntimeEventPayload(
                null, null, null, null, null, "view_image", callId, null,
                null, null, null, "tool:view_image", callId, null, null, false
        );
        return new RuntimeEvent(RuntimeEventType.COMMAND, status, "tool:view_image", null, payload);
    }

    private RuntimeEvent message(String title) {
        return new RuntimeEvent(RuntimeEventType.AGENT_MESSAGE, RuntimeEventStatus.INFO, title, title, null);
    }

    private boolean isRunningThinking(RuntimeEvent event) {
        return event.type() == RuntimeEventType.THINKING && event.status() == RuntimeEventStatus.RUNNING;
    }
}
