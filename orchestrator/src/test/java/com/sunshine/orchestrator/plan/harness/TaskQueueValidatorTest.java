package com.sunshine.orchestrator.plan.harness;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TaskQueueValidatorTest {
    @Test
    void acceptsValidQueue() {
        var items = List.of(
                TaskItem.initial("a", "A", List.of(), "", "", ""),
                TaskItem.initial("b", "B", List.of("a"), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isEmpty();
    }

    @Test
    void rejectsBlankTaskId() {
        var items = List.of(TaskItem.initial(" ", "A", List.of(), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isPresent();
    }

    @Test
    void rejectsBlankLabel() {
        var items = List.of(TaskItem.initial("a", " ", List.of(), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isPresent();
    }

    @Test
    void rejectsMissingDependencyRef() {
        var items = List.of(TaskItem.initial("a", "A", List.of("missing"), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isPresent();
    }

    @Test
    void rejectsDependencyCycle() {
        var items = List.of(
                TaskItem.initial("a", "A", List.of("b"), "", "", ""),
                TaskItem.initial("b", "B", List.of("a"), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isPresent();
    }
}
