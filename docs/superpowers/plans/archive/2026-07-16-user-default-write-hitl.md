# 用户级默认写确认 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `writeHitlMode` 用户默认落入 auth `sys_user`，账号设置可改；工作区仍仅本会话覆盖。

**Architecture:** `sys_user.default_write_hitl_mode` + auth `me`/`profile`；前端 `useWriteHitlMode` 增加 `globalDefault`（对齐 `useExecutionPreference`）；`UserSettingsModal` 增一项。

**Tech Stack:** auth-center (JPA) · sunshine-ui Vue3 · MySQL init `10-sunshine-auth.sql`

**Spec:** [2026-07-16-user-default-write-hitl-design.md](../specs/archive/2026-07-16-user-default-write-hitl-design.md)

---

### Task 1: Auth DB + Entity + DTOs

**Files:**
- Modify: `docker/mysql/init/10-sunshine-auth.sql`
- Modify: `auth-center/src/main/java/com/sunshine/auth/entity/UserEntity.java`
- Modify: `AuthUserVO` / `LoginResponse` / `UpdateProfileRequest` / `UpdateProfileResponse`
- Create: `auth-center/.../WriteHitlModeSupport.java`（`from(String)` → never|always|smart）

- [ ] 列：`default_write_hitl_mode VARCHAR(16) NOT NULL DEFAULT 'never'`
- [ ] Entity 字段 `defaultWriteHitlMode`
- [ ] Request/Response 字段 `defaultWriteHitlMode`
- [ ] 已有库手工：`ALTER TABLE sys_user ADD COLUMN ...`

### Task 2: UserService + 单测

**Files:**
- Modify: `UserService.java`（register 默认 never；login/me/profile 读写；非法回落）
- Modify: `AuthControllerTest.java`（profile 写 smart → me 读 smart）

- [ ] `updateProfile` 写入规范化 mode
- [ ] `toVo` / login / updateProfileResponse 带出字段
- [ ] 跑：`./mvnw -pl auth-center -am test -Dtest=AuthControllerTest`

### Task 3: 前端 API + composable

**Files:**
- Modify: `sunshine-ui/src/api/auth.ts`
- Modify: `sunshine-ui/src/stores/authStore.ts`（`syncWriteHitlDefaultFromAuth`）
- Modify: `sunshine-ui/src/composables/useWriteHitlMode.ts`（globalDefault + Map）

- [ ] `AuthUser.defaultWriteHitlMode`
- [ ] `updateProfile(nickname, tenantId, defaultWriteHitlMode)`
- [ ] `get` → Map ?? globalDefault；`setGlobalDefault`；登录同步

### Task 4: UI

**Files:**
- Modify: `WriteHitlModeSelector.vue`（`variant: compact|block`）
- Modify: `UserSettingsModal.vue`
- 文档：spec 状态 → 已实现；`docs/sandbox/README.md` 缺口勾掉

- [ ] 设置页「默认写操作确认」
- [ ] 保存走 profile + `setGlobalDefault`
- [ ] `vue-tsc --noEmit`

---

## Spec coverage

| Spec | Task |
|------|------|
| DB 列 | 1 |
| auth API | 1–2 |
| 账号设置 | 4 |
| 会话覆盖 / 新会话默认 | 3 |
| 非法 → never | 2–3 |
