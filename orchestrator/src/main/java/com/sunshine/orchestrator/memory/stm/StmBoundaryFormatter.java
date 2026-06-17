package com.sunshine.orchestrator.memory.stm;

import com.sunshine.orchestrator.memory.MemoryProperties;
import org.springframework.util.StringUtils;

/**
 * STM 边界说明 — 紧挨完整对话轮次之前，标明「以下为已结束会话，非当前任务」。
 */
public final class StmBoundaryFormatter {

    private StmBoundaryFormatter() {
    }

    public static String format(MemoryProperties properties) {
        if (properties == null) {
            return "";
        }
        MemoryProperties.Stm stm = properties.getStm();
        StringBuilder sb = new StringBuilder(stm.getHeader().strip());
        if (StringUtils.hasText(stm.getPreamble())) {
            sb.append("\n").append(stm.getPreamble().strip());
        }
        return sb.toString().strip();
    }
}
