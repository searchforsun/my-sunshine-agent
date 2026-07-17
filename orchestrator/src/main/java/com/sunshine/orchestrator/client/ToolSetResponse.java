package com.sunshine.orchestrator.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolSetResponse(List<String> toolIds) {
}
