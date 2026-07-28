# 沙箱容器双层生命周期（停机 / 开机 / 7 天销毁）

> **阶段**：4.5 沙箱 · **状态**：✅ 已实现（2026-07-28 增补工作区级生命周期差异，见 §7）  
> **日期**：2026-07-17  
> **前序**：[conversation-sandbox-permanent-tools](./2026-07-16-conversation-sandbox-permanent-tools-design.md) · [sandbox-workspace-drawer](./2026-07-16-sandbox-workspace-drawer-design.md) · 索引 [docs/sandbox/README.md](../../sandbox/README.md)

---

## 1. 目标与边界

**目标**：对话级 Docker 沙箱按空闲双层回收——

| 时机 | 行为 |
|------|------|
| 空闲 **30min**（现有 `conversation-ttl-sec`，默认 1800） | **停机** `docker stop`；宿主机 workspace / skills 目录 **保留**；Redis binding 保留，`state=stopped` |
| 再进会话（点工作区抽屉 / 发消息触发 `sandbox__*`） | **开机** `docker start`；续期 idle；文件仍在 |
| 自 **上次活动** 起空闲满 **7 天**（`purge-ttl-sec`，默认 604800） | **销毁** `docker rm` + 清宿主机目录 + 清 Redis |
| `DELETE conversation` | **立即销毁**（与现网一致） |

**已确认**

| 项 | 选择 |
|----|------|
| 路径 | 方案 A：Redis 双计时 + sandbox-service stop/start |
| 7 天到期 | 容器 **与** 宿主机 workspace **一并删除** |
| purge 起算 | 自 **上次活动**（`touch`）起算；每次活动后移 7 天窗口 |

**非目标**

- 对象存储归档、跨机迁移、预热开机
- 改 PathJail / 六工具语义 / HITL
- 改「删对话立即销毁」

---

## 2. 状态机

```text
RUNNING ──idle 30min──► STOPPED ──idle 7d（自上次活动）──► DESTROYED
   ▲                      │
   └──── start（抽屉 / sandbox__*）─┘
DELETE conversation ──────────────────────► DESTROYED
```

| 状态 | Docker | Redis binding | 宿主机目录 |
|------|--------|---------------|------------|
| RUNNING | running | 有，`state=running` | 有 |
| STOPPED | exited（未 rm） | 有，`state=stopped` | **保留** |
| DESTROYED | 无 | 无 | 无 |

---

## 3. 架构与数据流

### 3.1 sandbox-service

| API | 行为 |
|-----|------|
| `POST /api/sandbox/sessions/{id}/stop` | `docker stop`；**不** `store.remove`；**不**删 hostRoot |
| `POST /api/sandbox/sessions/{id}/start` | `docker start`；已 running 则 no-op |
| `GET .../alive` | `{ alive, running }`：`alive`=会话元数据仍在；`running`=容器在跑 |
| `DELETE .../{id}` | 现有 close：`rm -f` + 删 hostRoot + store.remove |

内存 `SandboxSessionStore` 在 stop 后仍保留 session 记录（否则 start/list 找不到）。

### 3.2 orchestrator Redis

Key 仍为 `sandbox:conv:{tenant}:{conversationId}`。

Binding 字段（相对现状）：

| 字段 | 说明 |
|------|------|
| `sessionId` / `loadedSkillIds` / `userId` / `tenantId` / `conversationId` | 不变 |
| `state` | `running` \| `stopped` |
| `purgeAtEpochMs` | 上次活动 + `purge-ttl-sec` |

ZSET：

| ZSET | score | Reaper 动作 |
|------|-------|-------------|
| `sandbox:conv:expiry` | now + idle TTL | **stop**（不删 binding；改 state；从 expiry 移除；**保留** purge 成员） |
| `sandbox:conv:purge` | `purgeAtEpochMs` | **close** + `remove` binding |

`save` / `touch`：

- 续写 binding TTL（建议 Redis key TTL ≥ purge，或取消 key TTL 改靠 ZSET 回收，避免 key 先于 purge 蒸发）
- 刷新 `expiry` score = now + idle
- 刷新 `purgeAt` = now + purge；更新 purge ZSET score
- `state` 保持或在 start 后置 `running`

**关键**：当前 `StringRedisTemplate.set(..., ttl=idle)` 会在 30min 删掉 binding，与「停机后仍能 start」冲突。实现时 Redis key **不得**仅用 idle TTL 过期；以 ZSET 为回收权威，key TTL 可用 `purge-ttl-sec`（或略长）。

### 3.3 Lifecycle / Workspace

- `ensureBound` / `ensureConversationSession`：binding 存在且 session alive（元数据在）但 `!running` → `start`；不存在或元数据丢失 → `create`（现逻辑）
- `SandboxWorkspaceService`：status/list 对 stopped 先 start（或 ensure），再 list
- `SandboxSessionReaper`：拆 `reapIdleStop` + `reapPurgeDestroy`
- 删对话：`destroyConversationSession` 仍 `closeSession`

### 3.4 Nacos

```yaml
agent.sandbox:
  conversation-ttl-sec: 1800      # idle → stop
  purge-ttl-sec: 604800           # 自上次活动 → destroy
  reaper-interval-ms: 60000
```

---

## 4. 与现状差异

| 维度 | 现网 | 本文 |
|------|------|------|
| idle 30min | Reaper **close**（rm+清盘） | Reaper **stop** |
| 再进会话 | 只能 recreate（文件丢） | **start**（文件在） |
| 长期回收 | 无（仅 idle 即毁） | 自上次活动 7 天 **close** |

---

## 5. 验收

| # | 场景 | 期望 |
|---|------|------|
| L1 | idle Reaper | `stop`；binding 在；`state=stopped`；不 `close` | ✅ |
| L2 | ensure / 抽屉对 stopped | `start`；list 见文件 | ✅ 单测 |
| L3 | purge Reaper | `close` + 清 Redis | ✅ |
| L4 | `touch` | 续期 idle + 后移 purgeAt | ✅ Store |
| L5 | 删对话 | 立即 `close` | ✅ 未改 |
| L6 | Live（可选） | 短 idle 冒烟 stop→start→list | ⬜ |

---

## 6. 决策记录

| 项 | 决议 |
|----|------|
| 方案 | A |
| 7 天 | 容器+宿主机目录一并删 |
| purge 起算 | 自上次活动 |
| idle | 沿用 `conversation-ttl-sec`（默认 30min） |

---

## 7. 增补：工作区级生命周期差异（2026-07-28）

Codex 式智能体工作区（[task-workspace-codex](./2026-07-28-task-workspace-codex-design.md)）引入工作区级容器后，生命周期按粒度分流：

| 时机 | 对话级（本 spec） | 工作区级 |
|------|-------------------|----------|
| idle 30min | `docker stop` | 同（复用 reaper，ZSET 键换 `sandbox:ws:expiry`） |
| 再进 | `docker start` | 同 |
| 销毁 | 7d 自动 purge（注册 `sandbox:conv:purge` ZSET） | **不自动 purge**（不注册 purge ZSET）；仅手动 `DELETE /api/agent-workspaces/{id}` 确认后销毁 |
| 删会话 | 立即销毁容器 | **不销毁**（容器属工作区，删 `chat_conversation` 不影响） |

对话级 `sandbox:conv:*` 三 ZSET 与本 spec §3.2 不变；工作区级仅新增 `sandbox:ws:expiry` 一个 ZSET（idle stop），不建 purge ZSET。
