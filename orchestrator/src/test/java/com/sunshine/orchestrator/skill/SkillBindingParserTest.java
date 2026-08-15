package com.sunshine.orchestrator.skill;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.SkillBindingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillBindingParserTest {

    @Mock
    private SkillCatalogService skillCatalogService;

    private SkillBindingParser parser;

    private static final List<SkillCatalogIndexEntry> INDEX = List.of(
            new SkillCatalogIndexEntry("finance-analysis", "财务分析", "报销合规分析", 1, true, "none", "all", null),
            new SkillCatalogIndexEntry("policy-review", "制度审查", "制度对照", 1, true, "none", "all", null),
            new SkillCatalogIndexEntry("disabled-skill", "已禁用", "不可用", 1, false, "none", "all", null),
            new SkillCatalogIndexEntry("task-only", "任务专用", "仅任务会话", 1, true, "none", "task", null));

    @BeforeEach
    void setUp() {
        SkillBindingProperties properties = new SkillBindingProperties();
        parser = new SkillBindingParser(skillCatalogService, properties);
        lenient().when(skillCatalogService.indexEntries()).thenReturn(INDEX);
    }

    @Test
    void slashMention_bindsSkill() {
        when(skillCatalogService.findIndex("finance-analysis"))
                .thenReturn(Optional.of(INDEX.get(0)));

        SkillBindingOutcome outcome = parser.parse("/finance-analysis 这笔报销是否合规？");
        assertThat(outcome.bound()).isTrue();
        assertThat(outcome.skillId()).isEqualTo("finance-analysis");
        assertThat(outcome.effectiveQuery()).isEqualTo("/finance-analysis 这笔报销是否合规？");
        assertThat(outcome.source()).isEqualTo(SkillBindingSource.SLASH_MENTION);
    }

    @Test
    void slashMention_resolvesDisplayName() {
        when(skillCatalogService.findIndex("财务分析")).thenReturn(Optional.empty());

        SkillBindingOutcome outcome = parser.parse("/财务分析 分析单据");
        assertThat(outcome.bound()).isTrue();
        assertThat(outcome.skillId()).isEqualTo("finance-analysis");
        assertThat(outcome.effectiveQuery()).isEqualTo("/财务分析 分析单据");
    }

    @Test
    void slashMention_unknownSkillFallsThroughAsPlainText() {
        when(skillCatalogService.findIndex("unknown-skill")).thenReturn(Optional.empty());

        SkillBindingOutcome outcome = parser.parse("/unknown-skill 问题");
        assertThat(outcome.bound()).isFalse();
        assertThat(outcome.unknown()).isFalse();
        assertThat(outcome.effectiveQuery()).isEqualTo("/unknown-skill 问题");
    }

    @Test
    void slashMention_disabledSkillFallsThroughAsPlainText() {
        when(skillCatalogService.findIndex("disabled-skill"))
                .thenReturn(Optional.of(INDEX.get(2)));

        SkillBindingOutcome outcome = parser.parse("/disabled-skill 问题");
        assertThat(outcome.bound()).isFalse();
        assertThat(outcome.unknown()).isFalse();
    }

    @Test
    void slashMention_taskKindSkillUnreachableFromChatSession() {
        when(skillCatalogService.findIndex("task-only"))
                .thenReturn(Optional.of(INDEX.get(3)));

        SkillBindingOutcome chat = parser.parse("/task-only 执行任务", null, "chat");
        assertThat(chat.bound()).isFalse();
        assertThat(chat.unknown()).isFalse();
        assertThat(parser.parse("/task-only 执行任务", null, "task").bound()).isTrue();
    }

    @Test
    void hintPattern_bindsSkill() {
        when(skillCatalogService.findIndex("finance-analysis"))
                .thenReturn(Optional.of(INDEX.get(0)));

        SkillBindingOutcome outcome = parser.parse("请使用 finance-analysis skill 处理待审批单据");
        assertThat(outcome.bound()).isTrue();
        assertThat(outcome.skillId()).isEqualTo("finance-analysis");
        assertThat(outcome.source()).isEqualTo(SkillBindingSource.HINT_PATTERN);
    }

    @Test
    void noBinding_returnsOriginalQuery() {
        SkillBindingOutcome outcome = parser.parse("这笔报销是否合规？");
        assertThat(outcome.bound()).isFalse();
        assertThat(outcome.unknown()).isFalse();
        assertThat(outcome.effectiveQuery()).isEqualTo("这笔报销是否合规？");
    }

    @Test
    void stripSlashMention_removesPrefix() {
        assertThat(parser.stripSlashMention("/policy-review 青松假有多少天、怎么申请"))
                .isEqualTo("青松假有多少天、怎么申请");
    }

    @Test
    void stripSlashMention_unknownSkillStillStrips() {
        assertThat(parser.stripSlashMention("/unknown-skill 问题")).isEqualTo("问题");
    }

    @Test
    void stripSlashMention_plainTextUnchanged() {
        assertThat(parser.stripSlashMention("年假制度")).isEqualTo("年假制度");
    }

    @Test
    void inlineSlashMention_bindsSkillKeepsRawQuery() {
        when(skillCatalogService.findIndex("finance-analysis"))
                .thenReturn(Optional.of(INDEX.get(0)));

        SkillBindingOutcome outcome = parser.parse("123 /finance-analysis 123");
        assertThat(outcome.bound()).isTrue();
        assertThat(outcome.skillId()).isEqualTo("finance-analysis");
        assertThat(outcome.effectiveQuery()).isEqualTo("123 /finance-analysis 123");
        assertThat(outcome.source()).isEqualTo(SkillBindingSource.SLASH_MENTION);
    }

    @Test
    void clientSkillId_bindsKeepsRawQuery() {
        when(skillCatalogService.findIndex("finance-analysis"))
                .thenReturn(Optional.of(INDEX.get(0)));

        SkillBindingOutcome outcome = parser.parse("123 /finance-analysis 123", "finance-analysis");
        assertThat(outcome.bound()).isTrue();
        assertThat(outcome.skillId()).isEqualTo("finance-analysis");
        assertThat(outcome.effectiveQuery()).isEqualTo("123 /finance-analysis 123");
        assertThat(outcome.source()).isEqualTo(SkillBindingSource.CLIENT);
    }

    @Test
    void clientSkillId_unknownSkill() {
        when(skillCatalogService.findIndex("unknown-skill")).thenReturn(Optional.empty());

        SkillBindingOutcome outcome = parser.parse("问题", "unknown-skill");
        assertThat(outcome.unknown()).isTrue();
        assertThat(outcome.unknownToken()).isEqualTo("unknown-skill");
    }

    @Test
    void stripSkillMentions_removesInlineToken() {
        assertThat(parser.stripSkillMentions("123 /finance-analysis 123")).isEqualTo("123 123");
    }
}
