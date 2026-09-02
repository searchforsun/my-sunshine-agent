package com.sunshine.tool.catalog;

import com.sunshine.common.tool.ToolIds;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import lombok.extern.slf4j.Slf4j;

/** 写入 Catalog 前校验工具 ID，非法则标记 id_valid=false 并强制停用。 */
@Slf4j
public final class ToolIdValidation {

    private ToolIdValidation() {
    }

    public static String resolveIdError(String sourceRef, String externalName, String catalogId) {
        if (!ToolIds.isValidSegment(sourceRef)) {
            return "来源 ID 不符合规范: " + sourceRef;
        }
        if (!ToolIds.isValidSegment(externalName)) {
            return "externalName 不符合规范: " + externalName;
        }
        return ToolIds.invalidReason(catalogId);
    }

    public static void apply(ToolDefinitionEntity entity, String idError) {
        boolean valid = idError == null;
        entity.setIdValid(valid);
        entity.setIdError(idError);
        if (!valid) {
            entity.setEnabled(false);
            log.error("[ToolIdValidation] 非法工具 ID {}: {}", entity.getId(), idError);
        }
    }
}
