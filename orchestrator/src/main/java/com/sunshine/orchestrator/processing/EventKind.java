package com.sunshine.orchestrator.processing;

public enum EventKind {
    PENDING,
    START,
    PROGRESS,
    COMPLETE,
    FAIL,
    SKIP,
    PAUSE,
    TERMINATE,
    /** 复用已 done 的 think-N 续跑：翻回 running 但保留既有 reasoning（区别于 START 清空） */
    RESUME
}
