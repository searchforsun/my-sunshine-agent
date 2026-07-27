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
}
