package com.sunshine.skill.util;

import com.sunshine.skill.dto.SkillDiffLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LineDiffTest {

    @Test
    void diff_detectsAddedAndRemovedLines() {
        String left = "line1\nline2\nline3";
        String right = "line1\nline2-updated\nline3\nline4";
        List<SkillDiffLine> lines = LineDiff.diff(left, right);
        assertThat(lines.stream().filter(l -> "removed".equals(l.type())).map(SkillDiffLine::text))
                .contains("line2");
        assertThat(lines.stream().filter(l -> "added".equals(l.type())).map(SkillDiffLine::text))
                .contains("line2-updated", "line4");
    }
}
