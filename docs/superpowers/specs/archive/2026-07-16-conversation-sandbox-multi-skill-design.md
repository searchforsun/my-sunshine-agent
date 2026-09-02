# 会话沙箱多 Skill 挂载（方案 A）

**日期**：2026-07-16  
**状态**：已实现（挂载与路径）；**沙箱门控**见 [演进](#演进-方案-b)  
**前序**：`2026-07-16-sandbox-workspace-drawer-design.md`（对话级 workspace）  
**原则**：**不做** `/skill` 兼容；仅最新契约。

## 演进（方案 B）

> **SSOT**：[2026-07-16-conversation-sandbox-permanent-tools-design.md](./2026-07-16-conversation-sandbox-permanent-tools-design.md)

- 主 ReAct **始终**可见 `sandbox__*`；容器在**首次工具调用**时懒创建（不再 `needsSandbox(skillId)` 才开箱）。
- 本文的 **多 Skill 懒挂载**、`/skills/{id}/`、Redis `loadedSkillIds` **保留**；仅去掉「Skill 门控沙箱」。
- Skill Catalog `sandbox` 字段不再作为 orchestrator 注入开关。

## 已确认需求

1. 沙箱与 **会话** 绑定，`/workspace` 对话级保留  
2. 多 Skill 并存：`/skills/{skillId}/...`（只读）  
3. **懒加载**：本轮 `@skill` / 路由命中且未挂载时写入  
4. **统一基座镜像**：不绑 Skill；会话策略来自 Nacos `agent.sandbox.runtime`  
5. Skill Catalog `sandbox` 字段：**展示/种子元数据**；方案 B 起 orchestrator **不**据此门控工具（见 [permanent-tools 设计](./2026-07-16-conversation-sandbox-permanent-tools-design.md)）

## 路径与 Jail

| 路径 | 权限 |
|------|------|
| `/workspace/**` | 读写 |
| `/skills/{skillId}/**` | 只读 |
| `/skill` | **不存在** |

## Redis

```
sandbox:conv:{tenant}:{conversationId} → {
  sessionId, userId, loadedSkillIds: string[]
}
```

换 Skill **不**销毁容器。

## API

- `POST /sessions`：空 `skills/` + `workspace/`；policy = 会话 runtime  
- `PUT /sessions/{id}/skills/{skillId}`：物料 map → `host/skills/{skillId}/`  
- FS list/content：允许 `/workspace` 与 `/skills`

## Orchestrator 会话沙箱（演进前实现）

> **目标态**见 [2026-07-16-conversation-sandbox-permanent-tools-design.md](./2026-07-16-conversation-sandbox-permanent-tools-design.md)：`ensureOnToolCall` 替代下列 `openIfNeeded(skillId)` 门控。

1. ~~`needsSandbox(skillId)`~~（待移除）  
2. 无/死会话 → create(空) + Redis  
3. 复用；若 skill ∉ loaded → fetchMaterial → mount → 更新 loadedSkillIds  
4. bind bridge；SSE `sandbox_session` 含 `loadedSkillIds`

## UI

抽屉双根：工作区 | Skills；Timeline 可聚焦 `/skills/...`

## 非目标

卸载 skill、多基座镜像、旧路径兼容、编辑 `/skills`
