package com.sunshine.orchestrator.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateWorkspaceRequest(
        @Size(max = 128) String name,
        @NotBlank @Size(max = 512) String repoUrl,
        @Size(max = 128) String repoBranch,
        Integer memoryMb,
        BigDecimal cpus) {
}
