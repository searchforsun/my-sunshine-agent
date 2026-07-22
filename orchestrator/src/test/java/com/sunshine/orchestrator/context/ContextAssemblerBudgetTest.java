package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerBudgetTest {

    @Test
    void applyBudget_dropsL3FirstThenFar_neverDropsL2Constraint() {
        String l2 = """
                [用户状态 · L2]
                - constraint/budget: 单次不超过500
                - preference/style: 简洁""";
        String far = "远窗摘要：" + "F".repeat(40);
        String l3 = "[历史材料 · L3 · 可能过期]\n- " + "L".repeat(40);
        AssembledContext full = new AssembledContext(
                l2,
                far,
                List.of(new ChatTurn("user", "mid-q")),
                List.of(new ChatTurn("user", "near-q")),
                l3);

        int midNear = "mid-q".length() + "near-q".length();
        int withL2Only = l2.length() + midNear;
        // 允许 L2+Mid+Near，但装不下 Far；L3 必须先被丢掉
        int budgetDropL3KeepFar = withL2Only + far.length();
        AssembledContext afterL3 = ContextAssembler.applyBudget(full, budgetDropL3KeepFar);
        assertThat(afterL3.l3MaterialBlock()).isBlank();
        assertThat(afterL3.farSummaryBlock()).isEqualTo(far);
        assertThat(afterL3.l2SystemBlock()).contains("constraint/budget: 单次不超过500");

        int budgetDropFar = withL2Only;
        AssembledContext afterFar = ContextAssembler.applyBudget(full, budgetDropFar);
        assertThat(afterFar.l3MaterialBlock()).isBlank();
        assertThat(afterFar.farSummaryBlock()).isBlank();
        assertThat(afterFar.l2SystemBlock()).contains("constraint/budget: 单次不超过500");
        assertThat(afterFar.l2SystemBlock()).contains("preference/style: 简洁");
    }

    @Test
    void applyBudget_withinLimit_keepsAll() {
        AssembledContext ctx = new AssembledContext(
                "[用户状态 · L2]\n- constraint/x: y",
                "far",
                List.of(),
                List.of(new ChatTurn("user", "hi")),
                "[历史材料 · L3 · 可能过期]\n- old");
        AssembledContext out = ContextAssembler.applyBudget(ctx, 100_000);
        assertThat(out.l3MaterialBlock()).isEqualTo(ctx.l3MaterialBlock());
        assertThat(out.farSummaryBlock()).isEqualTo("far");
        assertThat(out.l2SystemBlock()).contains("constraint/x: y");
    }
}
