package com.sunshine.auth.service;

import com.sunshine.auth.dto.AuthUserVO;
import com.sunshine.auth.dto.LoginRequest;
import com.sunshine.auth.dto.LoginResponse;
import com.sunshine.auth.dto.RegisterRequest;
import com.sunshine.auth.dto.UpdateProfileRequest;
import com.sunshine.auth.dto.UserBriefVO;
import com.sunshine.auth.entity.UserEntity;
import com.sunshine.auth.repo.UserRepository;
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
            throw new BizException(409, "用户名已存在");
        }
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(resolveNickname(request.getNickname(), request.getUsername()));
        user.setStatus(STATUS_ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        return toVo(user);
    }

    public LoginResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BizException(401, "用户名或密码错误"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(401, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != STATUS_ACTIVE) {
            throw new BizException(403, "账号已禁用");
        }
        StpUtil.login(user.getId(), SaLoginModel.create()
                .setExtra("nickname", resolveNickname(user.getNickname(), user.getUsername())));
        return LoginResponse.builder()
                .token(StpUtil.getTokenValue())
                .tokenName("Authorization")
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(resolveNickname(user.getNickname(), user.getUsername()))
                .build();
    }

    public void logout() {
        StpUtil.logout();
    }

    public AuthUserVO currentUser() {
        String userId = StpUtil.getLoginIdAsString();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(401, "用户不存在或 Token 已失效"));
        return toVo(user);
    }

    @Transactional
    public AuthUserVO updateProfile(UpdateProfileRequest request) {
        String userId = StpUtil.getLoginIdAsString();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(401, "用户不存在或 Token 已失效"));
        user.setNickname(request.getNickname().trim());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return toVo(user);
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

    private static AuthUserVO toVo(UserEntity user) {
        return AuthUserVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(resolveNickname(user.getNickname(), user.getUsername()))
                .build();
    }
}
