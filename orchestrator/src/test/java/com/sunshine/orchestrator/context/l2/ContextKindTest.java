package com.sunshine.orchestrator.context.l2;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 kind 单点定义：wire 集合、解析归一、注入排序、高门槛子集。
 */
class ContextKindTest {

    @Test
    void wireSet_matchesLedgerNineKinds() {
        assertThat(List.of(ContextKind.values()))
                .extracting(ContextKind::wire)
                .containsExactly("profile", "preference", "goal", "agreement",
                        "constraint", "fact", "decision", "process_note", "todo");
    }

    @Test
    void fromWire_normalizesCaseAndWhitespace() {
        assertThat(ContextKind.fromWire("  Process_Note ")).isEqualTo(ContextKind.PROCESS_NOTE);
        assertThat(ContextKind.fromWire("TODO")).isEqualTo(ContextKind.TODO);
    }

    @Test
    void fromWire_unknownOrBlankReturnsNull() {
        assertThat(ContextKind.fromWire(null)).isNull();
        assertThat(ContextKind.fromWire("reasoning")).isNull();
        assertThat(ContextKind.normalizeWire("reasoning")).isEqualTo("reasoning");
        assertThat(ContextKind.normalizeWire(null)).isEmpty();
    }

    @Test
    void injectRank_ordersListedKinds_andUnknownSinksToBottom() {
        assertThat(ContextKind.PROFILE.injectRank()).isLessThan(ContextKind.FACT.injectRank());
        assertThat(ContextKind.FACT.injectRank()).isLessThan(ContextKind.DECISION.injectRank());
        assertThat(ContextKind.rankOf("process_note")).isEqualTo(ContextKind.rankOf("unknown"));
    }

    @Test
    void elevatedOverwriteConfidence_onlyConstraintAndFact() {
        assertThat(List.of(ContextKind.values()))
                .filteredOn(ContextKind::elevatedOverwriteConfidence)
                .extracting(ContextKind::wire)
                .containsExactlyInAnyOrder("constraint", "fact");
    }
}
