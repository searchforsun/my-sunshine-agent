package com.sunshine.orchestrator.prompt;

import com.sunshine.routing.RoutingRuleDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptCatalogHolderTest {

    @Test
    void replace_thenSnapshot_returnsSame() {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        PromptCatalogSnapshot snap = PromptCatalogSnapshot.of(3L, List.of(
                new PromptCatalogEntry("system-prompt", "system", "系统提示", true, 0, 1,
                        "you are helpful", null)));
        holder.replace(snap);
        assertThat(holder.snapshot()).isSameAs(snap);
        assertThat(holder.snapshot().catalogVersion()).isEqualTo(3L);
        assertThat(holder.snapshot().text("system-prompt")).contains("you are helpful");
    }

    @Test
    void snapshot_whenNull_throwsIllegalState() {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        assertThatThrownBy(holder::snapshot)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not loaded");
    }

    @Test
    void refreshSafely_onFailure_keepsPrevious() {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        PromptCatalogSnapshot first = PromptCatalogSnapshot.of(1L, List.of(
                new PromptCatalogEntry("a", "system", "A", true, 0, 1, "v1", null)));
        holder.replace(first);
        boolean replaced = holder.refreshSafely(() -> {
            throw new RuntimeException("network down");
        });
        assertThat(replaced).isFalse();
        assertThat(holder.snapshot()).isSameAs(first);
        assertThat(holder.snapshot().catalogVersion()).isEqualTo(1L);
    }

    @Test
    void refreshSafely_sameVersion_doesNotReplace() {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        PromptCatalogSnapshot first = PromptCatalogSnapshot.of(2L, List.of());
        holder.replace(first);
        boolean replaced = holder.refreshSafely(() -> PromptCatalogSnapshot.of(2L, List.of(
                new PromptCatalogEntry("x", "system", "X", true, 0, 1, "t", null))));
        assertThat(replaced).isFalse();
        assertThat(holder.snapshot()).isSameAs(first);
    }

    @Test
    void refreshSafely_newerVersion_replaces() {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        holder.replace(PromptCatalogSnapshot.of(1L, List.of()));
        PromptCatalogSnapshot next = PromptCatalogSnapshot.of(2L, List.of(
                new PromptCatalogEntry("routing-rule.react-policy-qa", "routing-rule", "react", true, 40, 1, null,
                        "{\"matchType\":\"regex\",\"match\":\"any\",\"patterns\":[\"差旅办法\"],\"plan\":{\"mode\":\"react\",\"params\":{\"skill\":\"policy-qa\"}}}")));
        boolean replaced = holder.refreshSafely(() -> next);
        assertThat(replaced).isTrue();
        assertThat(holder.snapshot()).isSameAs(next);
        List<RoutingRuleDef> rules = holder.snapshot().routingRules();
        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst().matchType()).isEqualTo("regex");
        assertThat(rules.getFirst().plan().mode()).isEqualTo("react");
        assertThat(holder.snapshot().json("routing-rule.react-policy-qa")).isPresent();
    }
}
