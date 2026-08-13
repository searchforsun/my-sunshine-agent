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
    /** vertical|horizontal */
    private String sidebarSectionsLayout;
    /** 对话默认知识库 ID */
    private String defaultKbId;
    /** 用户个人规则（soul） */
    private String personalRules;
    /** Git 服务配置（本人设置页回显 PAT 明文；*TokenSet 供兼容判断） */
    private String githubUrl;
    private String githubToken;
    private boolean githubTokenSet;
    private String gitlabUrl;
    private String gitlabToken;
    private boolean gitlabTokenSet;
}
