package com.sunshine.auth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {

    private String token;
    private String tokenName;
    private String userId;
    private String username;
    private String nickname;
    private String tenantId;
    /** never|always|smart */
    private String defaultWriteHitlMode;
    /** vertical|horizontal */
    private String sidebarSectionsLayout;
    /** 用户个人规则（soul） */
    private String personalRules;
    private String githubUrl;
    private String githubToken;
    private boolean githubTokenSet;
    private String gitlabUrl;
    private String gitlabToken;
    private boolean gitlabTokenSet;
}
