package com.sunshine.bizscene.dto;

import java.util.List;

/** 场景 description 向量回填请求（orchestrator 场景 embedding 服务计算后推送）。 */
public record BizSceneVectorRequest(
        List<Float> vector
) {
}
