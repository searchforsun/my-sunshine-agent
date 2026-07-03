package com.sunshine.rag.admin.catalog.dto;

import java.util.List;

/** 文档详情 + 版本列表 */
public record DocumentDetail(
        String docId,
        String displayName,
        String sourceType,
        String activeVersion,
        List<DocumentVersionSummary> versions) {
}
