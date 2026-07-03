package com.sunshine.rag.admin.config.dto;

import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;

public record PublishDraftResult(
        NacosPublishResult nacos,
        SmokeEvalResult eval,
        Long reportId) {
}
