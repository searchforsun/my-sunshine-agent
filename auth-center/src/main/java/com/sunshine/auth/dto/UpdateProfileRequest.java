package com.sunshine.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @NotBlank(message = "昵称不能为空")
    @Size(min = 1, max = 64, message = "昵称最长 64 字符")
    private String nickname;
}
