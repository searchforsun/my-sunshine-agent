package com.sunshine.skill.dto;

import java.util.List;

public record SkillVersionDiffResponse(
        String path,
        int fromVersion,
        int toVersion,
        boolean binary,
        String fromMd5,
        String toMd5,
        List<SkillDiffLine> lines) {
}
