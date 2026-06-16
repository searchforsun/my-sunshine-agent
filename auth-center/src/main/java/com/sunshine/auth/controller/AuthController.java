package com.sunshine.auth.controller;

import com.sunshine.auth.dto.AuthUserVO;
import com.sunshine.auth.dto.LoginRequest;
import com.sunshine.auth.dto.LoginResponse;
import com.sunshine.auth.dto.RegisterRequest;
import com.sunshine.auth.dto.UpdateProfileRequest;
import com.sunshine.auth.service.UserService;
import com.sunshine.common.core.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public R<AuthUserVO> register(@Valid @RequestBody RegisterRequest request) {
        return R.ok(userService.register(request));
    }

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(userService.login(request));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        userService.logout();
        return R.ok();
    }

    @GetMapping("/me")
    public R<AuthUserVO> me() {
        return R.ok(userService.currentUser());
    }

    @PatchMapping("/profile")
    public R<AuthUserVO> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return R.ok(userService.updateProfile(request));
    }
}
