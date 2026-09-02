package com.sunshine.skill.skillmd;

import java.util.List;

/** 上传包 — 完整 SKILL.md 原文 + 解析结果 + 相对路径文件（references/、scripts/ 等） */
public record SkillPackage(
        String rawSkillMd,
        SkillMdDocument document,
        java.util.Map<String, byte[]> files
) {
}
