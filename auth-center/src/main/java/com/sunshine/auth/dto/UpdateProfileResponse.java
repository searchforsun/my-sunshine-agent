package com.sunshine.auth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpdateProfileResponse {

    private String userId;
    private String username;
    private String nickname;
    private String tenantId;
    /** 资料更新后重新签发的 JWT（extra 含 nickname / tenantId） */
    private String token;
}
