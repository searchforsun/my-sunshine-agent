package com.sunshine.auth.dto;

import lombok.Builder;
import lombok.Data;

/** 内部批量查询 — 供 BFF 解析维护人展示名 */
@Data
@Builder
public class UserBriefVO {

    private String userId;
    private String username;
    private String nickname;
}
