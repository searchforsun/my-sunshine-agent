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
    /** never|always|smart */
    private String defaultWriteHitlMode;
    /** 用户个人规则（soul） */
    private String personalRules;
    private String githubUrl;
    private String githubToken;
    private boolean githubTokenSet;
    private String gitlabUrl;
    private String gitlabToken;
    private boolean gitlabTokenSet;
    /** 资料更新后重新签发的 JWT（extra 含 nickname / tenantId） */
    private String token;
}
