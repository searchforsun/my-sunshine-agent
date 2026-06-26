package com.sunshine.auth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthUserVO {

    private String userId;
    private String username;
    private String nickname;
    private String tenantId;
}
