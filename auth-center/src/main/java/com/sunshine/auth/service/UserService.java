package com.sunshine.auth.service;

import com.sunshine.auth.dto.AuthUserVO;
import com.sunshine.auth.dto.LoginRequest;
import com.sunshine.auth.dto.LoginResponse;
import com.sunshine.auth.dto.RegisterRequest;
import com.sunshine.auth.dto.UpdateProfileRequest;
import com.sunshine.auth.dto.UpdateProfileResponse;
import com.sunshine.auth.dto.UserBriefVO;
import com.sunshine.auth.entity.UserEntity;
import com.sunshine.auth.repo.UserRepository;
import com.sunshine.auth.exception.AuthErrorCode;
import com.sunshine.common.core.exception.BizException;
import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final byte STATUS_ACTIVE = 1;

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public AuthUserVO register(RegisterRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new BizException(AuthErrorCode.USERNAME_TAKEN);
        }
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(resolveNickname(request.getNickname(), request.getUsername()));
        user.setTenantId(resolveTenantId(request.getTenantId()));
        user.setStatus(STATUS_ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        return toVo(user);
    }

    public LoginResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BizException(AuthErrorCode.LOGIN_FAILED));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(AuthErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() != STATUS_ACTIVE) {
            throw new BizException(AuthErrorCode.USER_DISABLED);
        }
        StpUtil.login(user.getId(), SaLoginModel.create()
                .setExtra("nickname", resolveNickname(user.getNickname(), user.getUsername()))
                .setExtra("tenantId", resolveTenantId(user.getTenantId())));
        return LoginResponse.builder()
                .token(StpUtil.getTokenValue())
                .tokenName("Authorization")
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(resolveNickname(user.getNickname(), user.getUsername()))
                .tenantId(resolveTenantId(user.getTenantId()))
                .build();
    }

    public void logout() {
        StpUtil.logout();
    }

    public AuthUserVO currentUser() {
        String userId = StpUtil.getLoginIdAsString();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(AuthErrorCode.USER_NOT_FOUND));
        return toVo(user);
    }

    @Transactional
    public UpdateProfileResponse updateProfile(UpdateProfileRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(AuthErrorCode.USER_NOT_FOUND));
        user.setNickname(request.getNickname().trim());
        user.setTenantId(resolveTenantId(request.getTenantId()));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        String newToken = reissueToken(user);
        return toUpdateProfileResponse(user, newToken);
    }

    /** 注销当前 JWT 并签发含最新 extra 的新 token（避免 getTokenValue 返回旧值） */
    private String reissueToken(UserEntity user) {
        String nickname = resolveNickname(user.getNickname(), user.getUsername());
        String tenantId = resolveTenantId(user.getTenantId());
        String previousToken = StpUtil.getTokenValue();
        if (previousToken != null && !previousToken.isBlank()) {
            StpUtil.logoutByTokenValue(previousToken);
        }
        return StpUtil.createLoginSession(user.getId(), SaLoginModel.create()
                .setExtra("nickname", nickname)
                .setExtra("tenantId", tenantId));
    }

    /** 批量查询用户展示信息 — 供 skill-manager BFF 解析维护人 */
    public List<UserBriefVO> findBriefByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> normalized = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return List.of();
        }
        Map<String, UserEntity> found = new LinkedHashMap<>();
        for (UserEntity user : userRepository.findAllById(normalized)) {
            found.put(user.getId(), user);
        }
        List<UserBriefVO> result = new ArrayList<>(normalized.size());
        for (String id : normalized) {
            UserEntity user = found.get(id);
            if (user != null) {
                result.add(UserBriefVO.builder()
                        .userId(user.getId())
                        .username(user.getUsername())
                        .nickname(resolveNickname(user.getNickname(), user.getUsername()))
                        .build());
            }
        }
        return result;
    }

    private static String resolveNickname(String nickname, String username) {
        return (nickname == null || nickname.isBlank()) ? username : nickname;
    }

    private static String resolveTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "default";
        }
        return tenantId.strip();
    }

    private static AuthUserVO toVo(UserEntity user) {
        return AuthUserVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(resolveNickname(user.getNickname(), user.getUsername()))
                .tenantId(resolveTenantId(user.getTenantId()))
                .build();
    }

    private UpdateProfileResponse toUpdateProfileResponse(UserEntity user, String token) {
        return UpdateProfileResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(resolveNickname(user.getNickname(), user.getUsername()))
                .tenantId(resolveTenantId(user.getTenantId()))
                .token(token)
                .build();
    }
}
