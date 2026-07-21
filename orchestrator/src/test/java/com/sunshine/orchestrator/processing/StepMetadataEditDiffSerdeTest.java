package com.sunshine.orchestrator.processing;

import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.common.sandbox.SandboxEditDiffLine;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepMetadataEditDiffSerdeTest {

    @Test
    void editDiff_roundTrip_viaMetadataToMap() {
        SandboxEditDiff diff = new SandboxEditDiff("/workspace/a.txt", 3, List.of(
                new SandboxEditDiffLine("del", "old", 2, null),
                new SandboxEditDiffLine("add", "new", null, 2)));
        StepMetadata meta = StepMetadata.withEditDiff(null, diff);
        Map<String, Object> map = ProcessingStepSerde.metadataToMap(meta);

        assertThat(map).containsKey("editDiff");
        @SuppressWarnings("unchecked")
        Map<String, Object> editDiffMap = (Map<String, Object>) map.get("editDiff");
        assertThat(editDiffMap.get("path")).isEqualTo("/workspace/a.txt");
        assertThat(editDiffMap.get("contextRadius")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) editDiffMap.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("kind")).isEqualTo("del");
        assertThat(lines.get(0).get("text")).isEqualTo("old");
        assertThat(lines.get(0).get("oldLine")).isEqualTo(2);
        assertThat(lines.get(0)).doesNotContainKey("newLine");
        assertThat(lines.get(1).get("kind")).isEqualTo("add");
        assertThat(lines.get(1).get("newLine")).isEqualTo(2);

        StepMetadata parsed = ProcessingStepSerde.metadataFromMap(map);
        assertThat(parsed).isNotNull();
        assertThat(parsed.editDiff()).isEqualTo(diff);
    }

    @Test
    void merge_prefersIncomingEditDiff() {
        SandboxEditDiff baseDiff = new SandboxEditDiff("/a.txt", 1, List.of(
                new SandboxEditDiffLine("ctx", "line", 1, 1)));
        SandboxEditDiff incomingDiff = new SandboxEditDiff("/b.txt", 2, List.of(
                new SandboxEditDiffLine("del", "x", 3, null)));
        StepMetadata base = StepMetadata.withEditDiff(null, baseDiff);
        StepMetadata incoming = StepMetadata.withEditDiff(null, incomingDiff);

        StepMetadata merged = StepMetadata.merge(base, incoming);

        assertThat(merged.editDiff()).isEqualTo(incomingDiff);
    }
}
