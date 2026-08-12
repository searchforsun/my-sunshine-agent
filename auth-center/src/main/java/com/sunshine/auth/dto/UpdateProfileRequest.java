package com.sunshine.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @NotBlank(message = "昵称不能为空")
    @Size(min = 1, max = 64, message = "昵称最长 64 字符")
    private String nickname;

    @NotBlank(message = "租户不能为空")
    @Size(max = 32, message = "租户标识最长 32 字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "租户标识仅允许字母、数字、下划线、连字符")
    private String tenantId;

    /** never|always|smart；缺省 / 非法由服务端回落 never */
    @Size(max = 16, message = "写确认模式最长 16 字符")
    private String defaultWriteHitlMode;

    /** vertical|horizontal；缺省 / 非法由服务端回落 vertical */
    @Size(max = 16, message = "侧栏排布最长 16 字符")
    private String sidebarSectionsLayout;

    /** 个人规则（soul）；null=不修改，空串=清空，最长 4000 字符 */
    @Size(max = 4000, message = "个人规则最长 4000 字符")
    private String personalRules;

    /** Git 服务：GitHub 基础地址 */
    @Size(max = 255, message = "GitHub 地址最长 255 字符")
    private String githubUrl;

    /** Git 服务：GitHub PAT（空串=清空，null=不修改） */
    @Size(max = 255, message = "令牌最长 255 字符")
    private String githubToken;

    /** Git 服务：GitLab 基础地址 */
    @Size(max = 255, message = "GitLab 地址最长 255 字符")
    private String gitlabUrl;

    /** Git 服务：GitLab PAT（空串=清空，null=不修改） */
    @Size(max = 255, message = "令牌最长 255 字符")
    private String gitlabToken;
}
