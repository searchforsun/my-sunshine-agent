package com.sunshine.orchestrator.context.l2;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** §6.4 语义判定解析：payload 组装 / 四动作解析 / targetId 合法性校验 / 失败回退。 */
class L2SemanticMergeServiceTest {

    private static UserContextStateEntity row(String id, String key, String value, String background) {
        UserContextStateEntity e = new UserContextStateEntity();
        e.setId(id);
        e.setKind("fact");
        e.setStateKey(key);
        e.setStateValue(value);
        e.setBackground(background);
        e.setStatus("active");
        return e;
    }

    @Test
    void buildPayload_includesCandidateAndRowsWithBackground() {
        L2ConflictMerger.Candidate candidate =
                new L2ConflictMerger.Candidate("fact", "project.database", "项目数据库为 MySQL", 0.9, "部署评审", "active");
        String payload = L2SemanticMergeService.buildPayload(
                candidate, List.of(row("r1", "project.db", "项目存储用 MySQL", "架构文档")));

        assertThat(payload)
                .contains("【新候选】")
                .contains("key: project.database")
                .contains("value: 项目数据库为 MySQL")
                .contains("background: 部署评审")
                .contains("【同 kind 既有条目】")
                .contains("- id: r1 | key: project.db | value: 项目存储用 MySQL | background: 架构文档");
    }

    @Test
    void parseVerdict_merge_withTargetAndNormalizedFields() {
        String raw = """
                {"action":"MERGE","targetId":"r1","mergedKey":"project.database",\
                "mergedValue":"项目数据库为 MySQL","mergedBackground":"部署评审确认","reason":"同指不同措辞"}""";

        L2SemanticMergeService.Verdict verdict =
                L2SemanticMergeService.parseVerdict(raw, List.of(row("r1", "project.db", "项目存储用 MySQL", null)));

        assertThat(verdict.action()).isEqualTo(L2SemanticMergeService.Action.MERGE);
        assertThat(verdict.targetId()).isEqualTo("r1");
        assertThat(verdict.mergedKey()).isEqualTo("project.database");
        assertThat(verdict.mergedValue()).isEqualTo("项目数据库为 MySQL");
        assertThat(verdict.mergedBackground()).isEqualTo("部署评审确认");
    }

    @Test
    void parseVerdict_update_withTarget() {
        String raw = """
                {"action":"UPDATE","targetId":"r1","reason":"用户改主意"}""";

        L2SemanticMergeService.Verdict verdict =
                L2SemanticMergeService.parseVerdict(raw, List.of(row("r1", "diet.spicy", "用户不吃辣", null)));

        assertThat(verdict.action()).isEqualTo(L2SemanticMergeService.Action.UPDATE);
        assertThat(verdict.targetId()).isEqualTo("r1");
        assertThat(verdict.mergedKey()).isNull();
    }

    @Test
    void parseVerdict_conflict_withTarget() {
        String raw = """
                {"action":"CONFLICT","targetId":"r1","reason":"同为当前陈述且互斥"}""";

        L2SemanticMergeService.Verdict verdict =
                L2SemanticMergeService.parseVerdict(raw, List.of(row("r1", "diet.spicy", "用户不吃辣", null)));

        assertThat(verdict.action()).isEqualTo(L2SemanticMergeService.Action.CONFLICT);
        assertThat(verdict.targetId()).isEqualTo("r1");
    }

    @Test
    void parseVerdict_noop_explicitOrInvalidAction() {
        List<UserContextStateEntity> rows = List.of(row("r1", "a.b", "v", null));

        assertThat(L2SemanticMergeService.parseVerdict(
                "{\"action\":\"NOOP\",\"reason\":\"无关\"}", rows).action())
                .isEqualTo(L2SemanticMergeService.Action.NOOP);
        assertThat(L2SemanticMergeService.parseVerdict(
                "{\"action\":\"WHATEVER\"}", rows).action())
                .isEqualTo(L2SemanticMergeService.Action.NOOP);
    }

    @Test
    void parseVerdict_fabricatedTargetId_fallsBackNoop() {
        String raw = """
                {"action":"MERGE","targetId":"not-exists","mergedValue":"x"}""";

        L2SemanticMergeService.Verdict verdict =
                L2SemanticMergeService.parseVerdict(raw, List.of(row("r1", "a.b", "v", null)));

        assertThat(verdict.action()).isEqualTo(L2SemanticMergeService.Action.NOOP);
    }

    @Test
    void parseVerdict_garbageOrEmpty_fallsBackNoop() {
        List<UserContextStateEntity> rows = List.of(row("r1", "a.b", "v", null));

        assertThat(L2SemanticMergeService.parseVerdict("", rows).action())
                .isEqualTo(L2SemanticMergeService.Action.NOOP);
        assertThat(L2SemanticMergeService.parseVerdict("这不是 JSON", rows).action())
                .isEqualTo(L2SemanticMergeService.Action.NOOP);
    }

    @Test
    void parseVerdict_markdownFenceWrapped_parsesInnerObject() {
        String raw = "```json\n{\"action\":\"UPDATE\",\"targetId\":\"r1\"}\n```";

        L2SemanticMergeService.Verdict verdict =
                L2SemanticMergeService.parseVerdict(raw, List.of(row("r1", "a.b", "v", null)));

        assertThat(verdict.action()).isEqualTo(L2SemanticMergeService.Action.UPDATE);
        assertThat(verdict.targetId()).isEqualTo("r1");
    }
}
