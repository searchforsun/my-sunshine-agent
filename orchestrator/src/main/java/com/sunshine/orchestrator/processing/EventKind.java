package com.sunshine.orchestrator.processing;

public enum EventKind {
    PENDING,
    START,
    PROGRESS,
    COMPLETE,
    FAIL,
    SKIP
}
