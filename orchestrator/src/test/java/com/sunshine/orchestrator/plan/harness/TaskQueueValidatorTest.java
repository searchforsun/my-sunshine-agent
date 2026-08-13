package com.sunshine.orchestrator.plan.harness;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TaskQueueValidatorTest {
    @Test
    void acceptsValidQueue() {
        var items = List.of(
                new TaskItem("a", "A", "pending", List.of(), "", "", ""),
                new TaskItem("b", "B", "pending", List.of("a"), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isEmpty();
    }

    @Test
    void rejectsBlankTaskId() {
        var items = List.of(new TaskItem(" ", "A", "pending", List.of(), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isPresent();
    }

    @Test
    void rejectsBlankLabel() {
        var items = List.of(new TaskItem("a", " ", "pending", List.of(), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isPresent();
    }

    @Test
    void rejectsMissingDependencyRef() {
        var items = List.of(new TaskItem("a", "A", "pending", List.of("missing"), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isPresent();
    }

    @Test
    void rejectsDependencyCycle() {
        var items = List.of(
                new TaskItem("a", "A", "pending", List.of("b"), "", "", ""),
                new TaskItem("b", "B", "pending", List.of("a"), "", "", ""));
        assertThat(TaskQueueValidator.validate(items)).isPresent();
    }
}
