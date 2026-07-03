package com.sunshine.rag.admin.config.dto;

import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;

public record PublishBundleResult(
        Long versionId,
        int versionNo,
        SmokeEvalResult eval,
        Long reportId) {
}
