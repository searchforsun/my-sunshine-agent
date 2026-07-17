# 用户级默认写操作确认（writeHitlMode）

> **阶段**：4.5 沙箱 · **状态**：✅ 已实现  
> **触发**：工作区 `writeHitlMode` 仅本会话内存；需与「账号设置 · 租户 / 默认执行模式」同构的**用户级默认**，跨设备/刷新保留  
> **关联**：[sandbox-write-hitl-skip-design.md](./2026-07-16-sandbox-write-hitl-skip-design.md) · 索引 [docs/sandbox/README.md](../../sandbox/README.md) · 计划 [2026-07-16-user-default-write-hitl.md](../plans/2026-07-16-user-default-write-hitl.md)

---

## 1. 目标

| 项 | 行为 |
|----|------|
| **用户默认** | 账号设置可改；落 **auth `sys_user`**；`login` / `me` / `profile` 可读可写 |
| **会话覆盖** | 工作区 `WriteHitlModeSelector` 仍只改本会话 Map；**不**回写用户默认 |
| **新会话** | 无会话记忆时使用用户默认（缺省 / 非法 → `never`） |
| **请求契约** | 不变：Chat 仍按**当前会话**下发 `writeHitlMode` |

**不变**：三档语义 `never` / `always` / `smart`（见写确认跳过详设）；`SandboxHitlPolicy` 门闸不变。

---

## 2. 语义（对齐执行模式）

| 场景 | 取值 |
|------|------|
| 账号设置保存 | 更新用户默认；**同时**把当前 Chat 会话的生效值切到该默认（同 `setGlobalDefault`） |
| 工作区切换 | 仅 `modes[conversationId]` |
| 切换 / 新建会话 | 有会话记忆用记忆；否则用用户默认 |
| 未登录 / 无字段 | `never` |

与「默认执行模式」差异：执行模式全局默认现为 **localStorage**；本项按租户链路进 **服务端**。

---

## 3. 数据与 API

### 3.1 DB（SSOT：`docker/mysql/init/10-sunshine-auth.sql`）

```sql
ALTER TABLE sys_user
  ADD COLUMN default_write_hitl_mode VARCHAR(16) NOT NULL DEFAULT 'never'
  COMMENT 'never|always|smart 沙箱写 HITL 用户默认';
```

禁止 Flyway；已有环境手工执行同等 `ALTER`（或重建 init）。

### 3.2 Auth 契约

| 端点 | 变更 |
|------|------|
| `login` / `me` / `register` 响应 | 增 `defaultWriteHitlMode: string` |
| `PATCH /api/auth/profile` | 请求增可选或必填 `defaultWriteHitlMode`；响应带回 |

校验：仅允许 `never|always|smart`；缺省 / 非法 → 存读均按 `never`。

**JWT**：不必塞进 token extra（非网关路由依赖）；以 `me` / `profile` 响应为准。

### 3.3 类型对齐

| 层 | 位置 |
|----|------|
| auth-center | `UserEntity` / `AuthUserVO` / `LoginResponse` / `UpdateProfileRequest|Response` |
| UI | `AuthUser.defaultWriteHitlMode`；`updateProfile(..., mode)` |
| UI 状态 | `useWriteHitlMode`：`globalDefault` + 会话 Map（对齐 `useExecutionPreference`） |

---

## 4. 前端

### 4.1 账号设置

`UserSettingsModal` 增表单项「默认写操作确认」：

- 复用 `WriteHitlModeSelector`（`variant="block"`，若尚无则补齐，对齐 `ExecutionModeSelector`）
- 保存：`auth.updateProfile(nickname, tenantId, defaultWriteHitlMode)` → `setGlobalDefault(mode)`

### 4.2 会话 composable

`useWriteHitlMode` 演进：

```
globalDefault  ← login/me/profile + setGlobalDefault
get(conversationId) → Map.get ?? globalDefault
set(conversationId, mode) → 只写 Map
applyConversationMode(stored?) → 有记忆用记忆，否则 globalDefault
```

登录后：`syncWriteHitlDefaultFromAuth(user.defaultWriteHitlMode)`（对齐 `syncTenantFromAuth`）。

Chat 发送逻辑不变：仍读当前会话 `getWriteHitlMode(conversationId)`。

### 4.3 工作区

抽屉选择器继续绑会话 `mode`；文案可加 hint：「仅本会话；默认请在账号设置修改」（可选，不强制）。

---

## 5. 非目标

- 不把 `writeHitlMode` 落到 `chat_conversation` 表（会话记忆保持前端 Map；刷新后回落用户默认——与现执行模式「会话 preference 落库」不对齐处接受，避免扩 orchestrator schema）
- 不改 Nacos / Orchestrator HITL 策略
- 不做 Live 冒烟必选项（可后续补 auth 单测 + 可选 UI 手测）

**说明**：刷新页面后会话 Map 清空 → 各会话回到用户默认。若需「刷新后仍记会话档」，另开 conversation 落库需求。

---

## 6. 验收

| # | 场景 | 期望 |
|---|------|------|
| U1 | profile 写 `smart` → me 读到 `smart` | auth 单测 / API |
| U2 | 设置页保存后新会话发 Chat | body `writeHitlMode=smart` |
| U3 | 工作区改 `always`，不保存设置 | 仅该会话；me 仍为设置页值 |
| U4 | 非法值 | 回落 `never` |
| U5 | 未改列的旧库兼容 | DEFAULT `never`；代码缺字段容错 |

---

## 7. 决策记录

| 项 | 决议 |
|----|------|
| 持久化 | auth `sys_user` 独立列（方案 A） |
| UI 入口 | 账号设置 + 现有工作区会话选择器 |
| 会话 vs 默认 | 同执行模式：设置=默认；工作区=本会话 |
| 刷新后会话 Map | 丢失，回落用户默认（本版接受） |
