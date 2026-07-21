package com.sunshine.rag.admin.catalog.dto;

import java.util.Map;

/** 分块预览请求：version 缺省取当前 draft */
public record ChunkPreviewRequest(
        String version,
        String strategy,
        Map<String, Object> params) {
}
