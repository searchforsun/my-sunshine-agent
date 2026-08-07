package com.sunshine.skill.dto;

/** 单行 diff — 供前端着色展示 */
public record SkillDiffLine(
        String type,
        String text,
        Integer oldLineNo,
        Integer newLineNo) {
}
