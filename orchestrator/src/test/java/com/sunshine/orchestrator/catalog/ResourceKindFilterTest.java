package com.sunshine.orchestrator.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceKindFilterTest {

    record E(String id, String kind) implements ResourceKindFilter.Kinded {
    }

    private static E entry(String id, String kind) {
        return new E(id, kind);
    }

    @Test
    void filter_keepsAllAndMatchingKind() {
        assertThat(ResourceKindFilter.retain(List.of(
                        entry("a", "chat"), entry("b", "task"), entry("c", "all")), "chat"))
                .extracting(E::id).containsExactly("a", "c");
    }

    @Test
    void filter_keepsAllAndTaskForTaskSession() {
        assertThat(ResourceKindFilter.retain(List.of(
                        entry("a", "chat"), entry("b", "task"), entry("c", "all")), "task"))
                .extracting(E::id).containsExactly("b", "c");
    }

    @Test
    void filter_defaultsBlankSessionKindToChat() {
        assertThat(ResourceKindFilter.retain(List.of(
                        entry("a", "chat"), entry("b", "task"), entry("c", "all")), null))
                .extracting(E::id).containsExactly("a", "c");
    }

    @Test
    void filter_treatsBlankResourceKindAsAll() {
        assertThat(ResourceKindFilter.retain(List.of(
                        entry("a", null), entry("b", "task")), "chat"))
                .extracting(E::id).containsExactly("a");
    }

    @Test
    void matches_allOrEqual() {
        assertThat(ResourceKindFilter.matches("all", "chat")).isTrue();
        assertThat(ResourceKindFilter.matches("chat", "chat")).isTrue();
        assertThat(ResourceKindFilter.matches("task", "chat")).isFalse();
    }
}
