package com.sunshine.orchestrator.client.sandbox;

import java.util.List;

public record FsNodeDto(String name, String path, String type, Long size) {
    public record FsListResponse(String path, List<FsNodeDto> entries) {}
}
