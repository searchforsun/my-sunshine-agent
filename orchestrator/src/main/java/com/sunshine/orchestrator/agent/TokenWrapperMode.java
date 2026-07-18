package com.sunshine.orchestrator.agent;

/**
 * Hook token 经 {@code bindTokenWrapper} 后的路由契约（route 与 drain 必须一致）。
 */
public enum TokenWrapperMode {
    /**
     * Workflow agent 等：flush/drain wrapper 的非空输出；空列表丢弃。
     */
    EMIT_OUTGOING,
    /**
     * spawn_subagent：wrapper 仅做 fold 副作用，原 token 入队供 Flux；
     * 不把 wrapper 输出（常为空）刷入 Generation，drain 时亦不透传原 token。
     */
    PASS_THROUGH
}
