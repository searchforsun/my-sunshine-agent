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
            new SkillCatalogIndexEntry("finance-analysis", "财务分析", "报销合规分析", 1, true),
            new SkillCatalogIndexEntry("policy-review", "制度审查", "制度对照", 1, true),
            new SkillCatalogIndexEntry("disabled-skill", "已禁用", "不可用", 1, false));

    @BeforeEach
    void setUp() {
        SkillBindingProperties properties = new SkillBindingProperties();
        parser = new SkillBindingParser(skillCatalogService, properties);
        lenient().when(skillCatalogService.indexEntries()).thenReturn(INDEX);
    }

    @Test
    void atMention_stripsPrefixAndBindsSkill() {
        when(skillCatalogService.findIndex("finance-analysis"))
                .thenReturn(Optional.of(INDEX.get(0)));

        SkillBindingOutcome outcome = parser.parse("@finance-analysis 这笔报销是否合规？");
        assertThat(outcome.bound()).isTrue();
        assertThat(outcome.skillId()).isEqualTo("finance-analysis");
        assertThat(outcome.effectiveQuery()).isEqualTo("这笔报销是否合规？");
        assertThat(outcome.source()).isEqualTo(SkillBindingSource.AT_MENTION);
    }

    @Test
    void atMention_resolvesDisplayName() {
        when(skillCatalogService.findIndex("财务分析")).thenReturn(Optional.empty());

        SkillBindingOutcome outcome = parser.parse("@财务分析 分析单据");
        assertThat(outcome.bound()).isTrue();
        assertThat(outcome.skillId()).isEqualTo("finance-analysis");
        assertThat(outcome.effectiveQuery()).isEqualTo("分析单据");
    }

    @Test
    void atMention_unknownSkill() {
        when(skillCatalogService.findIndex("unknown-skill")).thenReturn(Optional.empty());

        SkillBindingOutcome outcome = parser.parse("@unknown-skill 问题");
        assertThat(outcome.unknown()).isTrue();
        assertThat(outcome.unknownToken()).isEqualTo("unknown-skill");
    }

    @Test
    void atMention_disabledSkillTreatedAsUnknown() {
        when(skillCatalogService.findIndex("disabled-skill"))
                .thenReturn(Optional.of(INDEX.get(2)));

        SkillBindingOutcome outcome = parser.parse("@disabled-skill 问题");
        assertThat(outcome.unknown()).isTrue();
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
}
