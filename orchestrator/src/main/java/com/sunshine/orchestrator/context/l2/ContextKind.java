package com.sunshine.orchestrator.context.l2;

import java.util.Locale;

/**
 * L2 记忆 kind 单点定义（SSOT）：wire 字面量、注入排序、审计具体性、冲突高门槛。
 * <p>DB {@code user_context_state.kind} 与模型抽取输出均使用 {@link #wire()} 字面量；
 * 前端展示文案见 {@code sunshine-ui/src/components/context/contextLabels.ts} KIND_META，两处集合须同步。
 */
public enum ContextKind {

    PROFILE("profile", 0, 3, false),
    PREFERENCE("preference", 1, 1, false),
    GOAL("goal", 2, 0, false),
    AGREEMENT("agreement", 3, 0, false),
    CONSTRAINT("constraint", 4, 0, true),
    FACT("fact", 5, 2, true),
    DECISION("decision", 6, 0, false),
    // 7 = 未列入注入排序表的 kind 统一沉底
    PROCESS_NOTE("process_note", 7, 0, false),
    TODO("todo", 7, 0, false);

    /** 未列入注入排序表的 kind 统一沉底。 */
    private static final int NOT_LISTED_RANK = 7;

    private final String wire;
    /** L2 system 块注入排序（越小越前）。 */
    private final int injectRank;
    /** 冲突审计语义具体性：profile 最具体（身份属性），fact 次之，preference 更泛。 */
    private final int specificity;
    /** 覆盖既有值需达到更高置信门槛（constraint/fact）。 */
    private final boolean elevatedOverwriteConfidence;

    ContextKind(String wire, int injectRank, int specificity, boolean elevatedOverwriteConfidence) {
        this.wire = wire;
        this.injectRank = injectRank;
        this.specificity = specificity;
        this.elevatedOverwriteConfidence = elevatedOverwriteConfidence;
    }

    public String wire() {
        return wire;
    }

    public int injectRank() {
        return injectRank;
    }

    public int specificity() {
        return specificity;
    }

    public boolean elevatedOverwriteConfidence() {
        return elevatedOverwriteConfidence;
    }

    /** 解析模型输出/DB 值（trim + 小写）；未知字面量 → null，由调用方决定缺省语义。 */
    public static ContextKind fromWire(String kind) {
        if (kind == null) {
            return null;
        }
        String k = kind.strip().toLowerCase(Locale.ROOT);
        for (ContextKind ck : values()) {
            if (ck.wire.equals(k)) {
                return ck;
            }
        }
        return null;
    }

    /** wire 字面量归一（trim + 小写）；未知字面量原样归一返回，接受与否由写入门禁决定。 */
    public static String normalizeWire(String kind) {
        ContextKind ck = fromWire(kind);
        if (ck != null) {
            return ck.wire;
        }
        return kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
    }

    /** 注入排序：未知 kind 沉底。 */
    public static int rankOf(String kind) {
        ContextKind ck = fromWire(kind);
        return ck != null ? ck.injectRank : NOT_LISTED_RANK;
    }
}
