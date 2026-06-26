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
}
