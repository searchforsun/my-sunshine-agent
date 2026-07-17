package com.sunshine.orchestrator.sandbox;

import org.springframework.util.StringUtils;

/**
 * Chat 工作区写操作 HITL 跳过模式 — 仅门控沙箱六工具写相关确认。
 * SSOT：docs/superpowers/specs/2026-07-16-sandbox-write-hitl-skip-design.md
 */
public enum SandboxWriteHitlMode {
    /** 永不跳过：write/edit 必确认；exec 非只读白名单才确认 */
    NEVER,
    /** 总是跳过：write/edit/exec 全部免确认 */
    ALWAYS,
    /** 智能跳过：write/edit + 只读 exec 免确认；危险 exec 仍确认 */
    SMART;

    public static SandboxWriteHitlMode from(String raw) {
        if (!StringUtils.hasText(raw)) {
            return NEVER;
        }
        return switch (raw.strip().toLowerCase()) {
            case "always" -> ALWAYS;
            case "smart" -> SMART;
            default -> NEVER;
        };
    }

    public String wireValue() {
        return name().toLowerCase();
    }
}
