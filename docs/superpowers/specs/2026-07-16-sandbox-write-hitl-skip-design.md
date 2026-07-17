# Chat 工作区 · 沙箱写操作 HITL 跳过模式

> **阶段**：4.5 沙箱 · **状态**：✅ 已实现  
> **触发**：Chat 工作区需可按会话选择写操作确认强度，避免每次 write/edit 打断  
> **关联**：[sandbox-workspace-drawer-design.md](./2026-07-16-sandbox-workspace-drawer-design.md)、[skills-docker-sandbox-design.md](./2026-07-15-skills-docker-sandbox-design.md)、[conversation-sandbox-permanent-tools-design.md](./2026-07-16-conversation-sandbox-permanent-tools-design.md) · 索引 [docs/sandbox/README.md](../../sandbox/README.md)

---

## 1. 定位

在 Chat **工作区抽屉**增加「写操作确认」三档选择，仅作用于沙箱六工具中的**写相关** HITL：

| UI 文案 | 字段值 `writeHitlMode` | 行为 |
|---------|------------------------|------|
| **永不跳过** | `never`（默认） | **现状**：`sandbox__write` / `sandbox__edit` 必确认；`sandbox__exec` 非只读白名单才确认 |
| **总是跳过** | `always` | `write` / `edit` / `exec` **全部免确认** |
| **智能跳过** | `smart` | `write` / `edit` + **只读 exec** 免确认；**危险 exec** 仍确认 |

**不变**：

- `sandbox__read` / `glob` / `grep` 本就不进 HITL
- `SandboxExecGuard` 硬拒（破坏性命令）与 HITL 正交，任何模式都不放行硬拒命令
- sdk__ / mcp__ Catalog HITL、Plan/Workflow 非沙箱写工具 HITL **不在本方案范围**
- 作用域：**本会话**（`conversationId`）；换对话重置为 `never`；**不**落库、**不**进用户偏好

---

## 2. UI

### 2.1 入口

`SandboxWorkspaceDrawer` 顶栏（刷新旁）`WriteHitlModeSelector`：样式对齐 `ExecutionModeSelector`（胶囊触发、圆角菜单、去掉 raw Popover 直角阴影）。

| 展示 | 说明 |
|------|------|
| 永不跳过 | 写操作均需确认 |
| 总是跳过 | 跳过全部沙箱写确认 |
| 智能跳过 | 跳过非危险写操作 |

会话态：`useWriteHitlMode` → `reactive(Map<conversationId, mode>)`（须 reactive，否则切换不刷新）。

### 2.2 前端状态

- 默认 `never`；切换会话读 map，缺省回落 `never`
- **不**写 localStorage / 服务端

---

## 3. 请求契约

### 3.1 SSE 发送体（BFF / Orchestrator 一致）

```json
{
  "content": "...",
  "conversationId": "uuid",
  "executionPreference": "react",
  "writeHitlMode": "smart"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `writeHitlMode` | string? | `never` \| `always` \| `smart`；缺省 / 非法 → `never` |

前端始终下发当前会话值。续跑：请求若带 mode 则覆盖本续跑门闸。

### 3.2 类型对齐

| 层 | 位置 |
|----|------|
| UI | `SendOptions.writeHitlMode` / `chatSessions.send` |
| BFF | `ChatRequest.writeHitlMode` |
| Orchestrator | `ChatMessage.writeHitlMode` → `StepEventBridge.bindWriteHitlMode(assistantMsgId, mode)` |

---

## 4. 后端门闸

### 4.1 绑定

`ChatController` 新消息 / 续跑：`StepEventBridge.bindWriteHitlMode(assistantMsgId, SandboxWriteHitlMode.from(...))`。

### 4.2 判定（SSOT）

`SandboxHitlPolicy.requiresConfirmation(toolId, params, mode)`：

| tool | never | smart | always |
|------|-------|-------|--------|
| read / glob / grep | 否 | 否 | 否 |
| write | **是** | 否 | 否 |
| edit | **是** | 否 | 否 |
| exec（只读白名单） | 否 | 否 | 否 |
| exec（危险） | **是** | **是** | 否 |

`SandboxAgentTools.shouldAwaitHitl` 读取 bridge → assistantMsgId → mode。

---

## 5. 非目标

- 不改 Nacos 全局 `agent.hitl.enabled`
- 不扩展危险判定（仍用 `exec-readonly-allow` + `SandboxExecGuard`）
- 不持久化到 MySQL / 用户设置

---

## 6. 验收

| # | 场景 | 期望 | 状态 |
|---|------|------|:----:|
| A1–A7 | 见原矩阵（never/always/smart × write/exec） | 单测 `SandboxHitlPolicyTest` | ✅ |
| UI | 工作区切换三档即时刷新 | reactive Map | ✅ |
| Live | Chat 带 `writeHitlMode=always/smart` 冒烟 | **待补** `verify_sandbox_live` 或独立脚本 | ⬜ |

---

## 7. 决策记录

| 项 | 决议 |
|----|------|
| 智能跳过语义 | write/edit/只读 exec 免确认；仅危险 exec 确认 |
| 作用域 | 本会话，默认 never |
| 下发方式 | Chat stream 字段 `writeHitlMode` |
| UI 位置 | 工作区抽屉顶栏 |
