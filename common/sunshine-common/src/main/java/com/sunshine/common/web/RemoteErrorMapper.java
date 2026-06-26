package com.sunshine.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.exception.FixedErrorCode;
import org.springframework.util.StringUtils;

/** 解析下游服务 R 错误体 */
public final class RemoteErrorMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RemoteErrorMapper() {
    }

    public static BizException fromBody(int httpStatus, String body) {
        if (StringUtils.hasText(body)) {
            try {
                JsonNode node = MAPPER.readTree(body);
                int code = node.has("code") ? node.get("code").asInt() : httpStatus;
                String msg = node.path("msg").asText("");
                String key = node.path("errorKey").asText("remote_error");
                if (code != CommonErrorCode.SUCCESS.getCode() && StringUtils.hasText(msg)) {
                    return new BizException(new FixedErrorCode(code, key, msg));
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return new BizException(CommonErrorCode.INTERNAL_ERROR);
    }
}
