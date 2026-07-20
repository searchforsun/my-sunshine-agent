package com.sunshine.orchestrator.memory.stm;

import org.springframework.util.StringUtils;

/**
 * STM 边界说明 — header/preamble 正文 SSOT = Catalog
 * （{@code memory.stm.header} / {@code memory.stm.preamble}）。
 */
public final class StmBoundaryFormatter {

    private StmBoundaryFormatter() {
    }

    public static String format(String header, String preamble) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(header)) {
            sb.append(header.strip());
        }
        if (StringUtils.hasText(preamble)) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(preamble.strip());
        }
        return sb.toString().strip();
    }
}
