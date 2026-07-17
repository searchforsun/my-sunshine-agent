package com.sunshine.orchestrator.client.sandbox;

public record FsContentDto(String path, String content, boolean truncated, boolean binary) {
}
