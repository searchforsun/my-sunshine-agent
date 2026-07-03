package com.sunshine.rag.admin.config.dto;

import java.util.List;
import java.util.Map;

public record ConfigBundleDraftView(
        Long draftVersionId,
        int draftVersionNo,
        Map<String, Object> payload,
        Long activePublishedVersionId,
        Integer activePublishedVersionNo) {
}
