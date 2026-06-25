package com.sunshine.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 32, message = "用户名长度须为 4-32 字符")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名仅允许字母、数字、下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须为 8-64 字符")
    private String password;

    @Size(max = 64, message = "昵称最长 64 字符")
    private String nickname;

    /** 租户标识，缺省 default（仅测试/演示；生产由管理员分配） */
    @Size(max = 32, message = "租户标识最长 32 字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "租户标识仅允许字母、数字、下划线、连字符")
    private String tenantId;
}
