package com.sunshine.rag.admin.eval;

import java.util.Set;

/** 评测集常量（SSOT） */
public final class EvalSuiteKeys {

    public static final String REGRESSION = "sunshine-regression";
    public static final String ADVERSARIAL = "sunshine-adversarial";
    public static final String SMOKE = "sunshine-smoke";

    public static final String DEFAULT_SUITE = REGRESSION;

    private static final Set<String> BUILTIN = Set.of(REGRESSION, ADVERSARIAL, SMOKE);

    private EvalSuiteKeys() {
    }

    public static boolean isBuiltin(String suiteKey) {
        if (suiteKey == null || suiteKey.isBlank()) {
            return false;
        }
        return BUILTIN.contains(suiteKey.strip());
    }

    public static String normalizeSuiteKey(String suiteKey) {
        if (suiteKey == null || suiteKey.isBlank()) {
            return DEFAULT_SUITE;
        }
        return suiteKey.strip();
    }

    public static String kbCustomSuiteKey(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return "default-custom";
        }
        return kbId.strip().replaceAll("[^a-zA-Z0-9_-]", "-") + "-custom";
    }
}
