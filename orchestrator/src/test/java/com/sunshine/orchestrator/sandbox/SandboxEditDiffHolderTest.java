package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.common.sandbox.SandboxEditDiffLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxEditDiffHolderTest {

    @Test
    void putThenTake_returnsSame_andSecondTakeNull() {
        SandboxEditDiff diff = new SandboxEditDiff("/workspace/a.txt", 3, List.of(
                new SandboxEditDiffLine("del", "old", 2, null),
                new SandboxEditDiffLine("add", "new", null, 2)));
        String toolUseId = "tu-edit-1";
        SandboxEditDiffHolder.put(toolUseId, diff);
        assertThat(SandboxEditDiffHolder.take(toolUseId)).isSameAs(diff);
        assertThat(SandboxEditDiffHolder.take(toolUseId)).isNull();
    }
}
