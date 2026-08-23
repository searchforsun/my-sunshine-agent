package com.sunshine.orchestrator.context.l2;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class L2ExtractServiceParseTest {

    @Test
    void parseCandidates_readsJsonArrayAndSkipsInvalidKind() {
        String raw = """
                ```json
                [
                  {"kind":"preference","key":"style","value":"简洁","confidence":0.9},
                  {"kind":"unknown","key":"x","value":"y","confidence":0.99},
                  {"kind":"reasoning","key":"why-b","value":"成本更低","confidence":0.8},
                  {"kind":"constraint","key":"budget","value":"单次不超过500","confidence":0.95}
                ]
                ```
                """;
        List<L2ConflictMerger.Candidate> list = L2ExtractService.parseCandidates(raw);
        assertThat(list).hasSize(3);
        assertThat(list.get(0).kind()).isEqualTo("preference");
        assertThat(list.get(0).key()).isEqualTo("style");
        assertThat(list.get(1).kind()).isEqualTo("reasoning");
        assertThat(list.get(2).kind()).isEqualTo("constraint");
    }

    @Test
    void parseCandidates_emptyOrMalformed_returnsEmpty() {
        assertThat(L2ExtractService.parseCandidates("")).isEmpty();
        assertThat(L2ExtractService.parseCandidates("not json")).isEmpty();
        assertThat(L2ExtractService.parseCandidates("[]")).isEmpty();
    }

    @Test
    void parseCandidates_acceptsTodoWithBackgroundAndStatus() {
        String raw = """
                [
                  {"kind":"todo","key":"finance.pending_approval","value":"跟进审批单 PR-2026-0812","confidence":0.9,"background":"OA 审批","status":"active"},
                  {"kind":"todo","key":"hr.onboarding_docs","value":"收集新员工入职材料","confidence":0.85,"background":"新员工入职流程","status":"done"}
                ]
                """;
        List<L2ConflictMerger.Candidate> list = L2ExtractService.parseCandidates(raw);
        assertThat(list).hasSize(2);
        L2ConflictMerger.Candidate active = list.get(0);
        assertThat(active.kind()).isEqualTo("todo");
        assertThat(active.key()).isEqualTo("finance.pending_approval");
        assertThat(active.background()).isEqualTo("OA 审批");
        assertThat(active.status()).isEqualTo("active");
        assertThat(list.get(1).status()).isEqualTo("done");
    }

    @Test
    void parseCandidates_rejectsBareKeyOrBooleanValue() {
        String raw = """
                [
                  {"kind":"todo","key":"collect_receipts","value":"收齐报销发票","confidence":0.9,"background":"报销收尾","status":"active"},
                  {"kind":"todo","key":"finance.follow_up","value":"true","confidence":0.9,"background":"审批跟进","status":"active"}
                ]
                """;
        List<L2ConflictMerger.Candidate> list = L2ExtractService.parseCandidates(raw);
        assertThat(list).isEmpty();
    }

    @Test
    void parseCandidates_rejectsMissingBackgroundForTodo() {
        String raw = """
                [
                  {"kind":"todo","key":"finance.follow_up","value":"跟进审批单","confidence":0.9,"status":"active"},
                  {"kind":"preference","key":"style","value":"简洁","confidence":0.9}
                ]
                """;
        List<L2ConflictMerger.Candidate> list = L2ExtractService.parseCandidates(raw);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).kind()).isEqualTo("preference");
        assertThat(list.get(0).background()).isNull();
        assertThat(list.get(0).status()).isEqualTo("active");
    }

    @Test
    void parseCandidates_todoDoneWithoutBackground_isAccepted() {
        // P2-1：done/void 豁免 background 必填，否则完成标记被丢弃 → active 行无法 void
        String raw = """
                [
                  {"kind":"todo","key":"finance.follow_up","value":"审批单已处理完毕","confidence":0.9,"status":"done"}
                ]
                """;
        List<L2ConflictMerger.Candidate> list = L2ExtractService.parseCandidates(raw);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).status()).isEqualTo("done");
        assertThat(list.get(0).background()).isNull();
    }

    @Test
    void parseCandidates_nonTodoStatusIsForcedActive() {
        // P2-2：status 生命周期仅 todo；非 todo 模型误产 done/void 一律归 active
        String raw = """
                [
                  {"kind":"preference","key":"style","value":"简洁","confidence":0.9,"status":"done"},
                  {"kind":"fact","key":"company.policy","value":"报销需附发票","confidence":0.9,"status":"void"}
                ]
                """;
        List<L2ConflictMerger.Candidate> list = L2ExtractService.parseCandidates(raw);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).status()).isEqualTo("active");
        assertThat(list.get(1).status()).isEqualTo("active");
    }

    @Test
    void buildSystemPrompt_replacesScopePlaceholder() {
        String catalog = "你是 KV Memory 抽取助手。当前 scope={scope}（user=用户级 / workspace=工作区级）。";
        assertThat(L2ExtractService.buildSystemPrompt(catalog, "user"))
                .isEqualTo("你是 KV Memory 抽取助手。当前 scope=user（user=用户级 / workspace=工作区级）。");
        assertThat(L2ExtractService.buildSystemPrompt(catalog, "workspace"))
                .isEqualTo("你是 KV Memory 抽取助手。当前 scope=workspace（user=用户级 / workspace=工作区级）。");
        assertThat(L2ExtractService.buildSystemPrompt("", "user")).isEmpty();
    }
}
