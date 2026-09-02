package com.sunshine.common.sandbox;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EditDiffBuilderTest {

    @Test
    void middleReplace_hasContextRadius3_andAbsoluteLines() {
        String before = String.join("\n",
                "L1", "L2", "L3", "L4", "L5", "OLD", "L7", "L8", "L9", "L10");
        SandboxEditDiff diff = EditDiffBuilder.build(before, "OLD", "NEW", 3)
                .withPath("/workspace/a.txt");
        assertThat(diff.contextRadius()).isEqualTo(3);
        assertThat(diff.path()).isEqualTo("/workspace/a.txt");
        // 文件头被 fold；ctx L3..L5；del OLD@6；add NEW@6；ctx L7..L9；尾 fold
        assertThat(diff.lines().get(0).kind()).isEqualTo("fold");
        assertThat(diff.lines().stream().filter(l -> "ctx".equals(l.kind())).map(SandboxEditDiffLine::text))
                .containsExactly("L3", "L4", "L5", "L7", "L8", "L9");
        SandboxEditDiffLine del = diff.lines().stream().filter(l -> "del".equals(l.kind())).findFirst().orElseThrow();
        SandboxEditDiffLine add = diff.lines().stream().filter(l -> "add".equals(l.kind())).findFirst().orElseThrow();
        assertThat(del.oldLine()).isEqualTo(6);
        assertThat(del.newLine()).isNull();
        assertThat(add.newLine()).isEqualTo(6);
        assertThat(add.oldLine()).isNull();
        assertThat(diff.toUnifiedText()).contains("-OLD").contains("+NEW").contains(" L5");
    }

    @Test
    void nearFileStart_noLeadingFold_partialContext() {
        String before = "A\nB\nC\nD\nE\n";
        SandboxEditDiff diff = EditDiffBuilder.build(before, "B", "X", 1);
        assertThat(diff.lines().get(0).kind()).isNotEqualTo("fold");
        assertThat(diff.lines().stream().anyMatch(l -> "fold".equals(l.kind()))).isTrue();
    }

    @Test
    void notFound_returnsEmptyOptional() {
        assertThat(EditDiffBuilder.tryBuild("abc", "zzz", "q", 3)).isEmpty();
    }

    @Test
    void notUnique_returnsEmptyOptional() {
        assertThat(EditDiffBuilder.tryBuild("x\nx\n", "x", "y", 3)).isEmpty();
    }

    @Test
    void overlappingNeedle_nonOverlappingCountOne_succeeds() {
        assertThat(EditDiffBuilder.tryBuild("aaa", "aa", "x", 3)).isPresent();
    }

    @Test
    void overlappingNeedle_nonOverlappingCountTwo_returnsEmpty() {
        assertThat(EditDiffBuilder.tryBuild("aaaa", "aa", "x", 3)).isEmpty();
    }
}
