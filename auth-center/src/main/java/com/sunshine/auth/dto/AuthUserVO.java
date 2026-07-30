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
    /** never|always|smart */
    private String defaultWriteHitlMode;
    /** 用户个人规则（soul） */
    private String personalRules;
    /** Git 服务配置（令牌不回传明文，仅回传是否配置） */
    private String githubUrl;
    private boolean githubTokenSet;
    private String gitlabUrl;
    private boolean gitlabTokenSet;
}
