# 工具集成员制 — 产品设计

> **日期**：2026-07-11  
> **状态**：✅ 已实现（2026-07-11）  
> **前置**：[2026-07-09-tool-integration-design.md](./2026-07-09-tool-integration-design.md) · TD-077/078 工具集语义修正  
> **决策**：关键工具采用 **方案 B** — 合并进 `plan-workflow` 成员表，用 `critical` 字段标记，废弃独立 `plan-workflow-critical` 工具集。

---

## 1. 背景与问题

当前工具集 Tab 将**工具池内全部已启用工具**平铺展示，并用开关决定「是否进 ReAct/Planner」。这与 SDK/MCP Tab 的 `tool_definition.enabled` 语义重叠，造成：

- 用户需在两个页面理解两套「启用」
- 默认种子预填成员，与「按需组集」的产品预期不符
- 列表随池膨胀，无法分页，难以维护大型工具集

**目标**：工具集 = **显式白名单**；启用 = **唯一写入口在 SDK/MCP 管理页**。

---

## 2. SSOT 模型

| 维度 | 存储 | 唯一写入口 | 运行时含义 |
|------|------|------------|------------|
| 是否可调用 | `tool_definition.enabled` | SDK / MCP Tab | invoke 门禁；LLM schema 构建时与成员求交 |
| 是否进模式 | `tool_set_member` | 工具集 Tab | ReAct / Planner 可见工具白名单 |
| 是否关键（Planner） | `tool_set_member.critical` | 工具集 Tab · Planner | `NodeRetryPolicyResolver` 判定 fail_fast 范围 |
| 读写 / 需确认 | `tool_definition.*` | SDK / MCP Tab | 工具集页**只读**展示 |

### 2.1 运行时公式（不变，语义收紧）

```
ReAct 可用      = members(react-default)      ∩ { catalog | enabled=true }
Planner 可用    = members(plan-workflow)      ∩ enabled池
Planner 关键    = members(plan-workflow, critical=true) ∩ enabled池
```

- **工具集 DB** 决定大模型「能看到哪些工具」（toolkit / Planner catalog 渲染）
- **enabled** 是唯一启用数据源；停用后成员可保留，运行时自动跳过
- SDK/MCP 停用工具时 **不再** 自动从工具集删成员（去掉现有 `handleToggleTool` 级联清理）

### 2.2 与旧模型差异

| 项 | 旧 | 新 |
|----|----|----|
| 默认成员 | 种子预填 finance/oa | **空集** |
| 工具集 Tab 列表 | 整池 + 成员开关 | **仅成员** + 只读池属性 |
| 关键工具 | 独立 `plan-workflow-critical` 集 | **`plan-workflow` 成员 `critical` 字段** |
| 写成员 API | `PUT` 整表替换 | **`add` / `remove` / `patch-critical` 增量** |

---

## 3. 数据模型

### 3.1 表变更（`docker/mysql/init/16-sunshine-tool-manager.sql`）

**`tool_set_member` 增列**：

```sql
ALTER TABLE tool_set_member
  ADD COLUMN critical TINYINT(1) NOT NULL DEFAULT 0
  COMMENT '仅 plan-workflow 集有效；1=Planner 关键工具';
```

- ReAct 集成员：`critical` 恒为 `0`（写入时忽略）
- Planner 集成员：`critical` 可选，默认 `0`

### 3.2 废弃

- 删除 `tool_set` 行：`global-plan-workflow-critical` 及租户覆盖
- 删除相关 `tool_set_member` 行
- 废弃 API：`GET/PUT /api/admin/tools/sets/plan-workflow-critical`（实现阶段移除，无兼容期）

### 3.3 种子数据

```sql
-- 保留 tool_set 定义行，不插入 tool_set_member
INSERT INTO tool_set (id, set_type, tenant_id, display_name) VALUES
('global-react-default', 'global_react_default', NULL, '平台 ReAct 工具集'),
('global-plan-workflow', 'global_plan_workflow', NULL, '平台 Plan-Workflow 工具集');
-- 不再创建 global-plan-workflow-critical
```

### 3.4 现网迁移（一次性）

1. 将 `global-plan-workflow-critical`（及租户覆盖）成员合并入对应 `plan-workflow` 集，`critical=1`
2. 删除 critical 专用集
3. 可选：清空全部 `tool_set_member` 若环境允许（Demo 重建）

---

## 4. API 设计（tool-manager → BFF 透传）

### 4.1 成员分页列表

```
GET /api/admin/tools/sets/{kind}/members
  ?tenantId=default
  &page=1&size=20
  &q=财务
```

`kind`：`react-default` | `plan-workflow`

**响应**：

```json
{
  "scope": "global",
  "page": 1,
  "size": 20,
  "total": 12,
  "items": [
    {
      "toolId": "sdk__sunshine-finance__list_finance_messages",
      "displayName": "查询待审批财务消息",
      "description": "...",
      "source": "sdk",
      "sourceRef": "sunshine-finance",
      "sourceLabel": "财务 Demo 应用",
      "sideEffect": "read",
      "enabled": true,
      "requireConfirmation": false,
      "critical": false,
      "sortOrder": 0
    }
  ]
}
```

- `sourceLabel`：SDK `display_name` 或 MCP `display_name`，服务端 join
- `enabled` / `sideEffect` / `requireConfirmation`：来自 `tool_definition`，**只读**
- `critical`：仅 `plan-workflow` 返回有效值；`react-default` 恒 `false`

### 4.2 添加成员

```
POST /api/admin/tools/sets/{kind}/members:add
Body: {
  "items": [
    { "toolId": "sdk__...", "critical": false },
    { "toolId": "mcp__...", "critical": true }
  ]
}
```

- 校验：`toolId` 存在于 catalog 且 `enabled=true`（未启用拒绝并返回明细错误）
- 已存在：幂等跳过或 409（实现选 **跳过 + 返回 skipped 列表**）
- `react-default`：忽略 `critical`
- 成功后 `publish` catalog change（与现有一致）

### 4.3 剔除成员

```
POST /api/admin/tools/sets/{kind}/members:remove
Body: { "toolIds": ["sdk__...", "mcp__..."] }
```

### 4.4 标记关键（仅 plan-workflow）

```
PATCH /api/admin/tools/sets/plan-workflow/members/{toolId}
Body: { "critical": true }
```

- 成员不存在 → 404
- 列表行内「关键」Switch 调此接口（非 ReAct Tab）

### 4.5 添加弹窗候选（picker）

```
GET /api/admin/tools/sets/{kind}/picker
  ?tenantId=default
  &q=
```

**响应**（按来源分组，仅 **enabled 且未在集中** 的工具）：

```json
{
  "groups": [
    {
      "source": "sdk",
      "sourceRef": "sunshine-finance",
      "title": "财务 Demo 应用",
      "tools": [
        { "toolId": "...", "displayName": "...", "sideEffect": "read", "requireConfirmation": false }
      ]
    },
    {
      "source": "mcp",
      "sourceRef": "demo-memory",
      "title": "Memory MCP (stdio)",
      "tools": [ ... ]
    }
  ]
}
```

### 4.6 保留 / 废弃

| 端点 | 处置 |
|------|------|
| `GET/PUT .../react-default` | **废弃 PUT**（前端改 members API）；GET 可保留只读兼容脚本，或改为 members 别名 |
| `GET/PUT .../plan-workflow` | 同上 |
| `GET/PUT .../plan-workflow-critical` | **删除** |
| `GET/PUT .../modes/plan-workflow` | **保留**（执行策略 JSON） |

### 4.7 orchestrator 消费

`ToolSetClient` 调整：

- `fetchReactDefault` → 调 members 列表全量 ID（或保留轻量 `GET .../tool-ids` 内部端点）
- `fetchPlanWorkflow` → 同上
- `fetchPlanWorkflowCritical` → 改为 `GET plan-workflow/members?criticalOnly=true` 或 members 响应内过滤

推荐新增内部端点（orchestrator 专用，不分页）：

```
GET /api/tools/sets/{kind}/tool-ids?tenantId=&criticalOnly=false
→ { "toolIds": ["..."], "criticalToolIds": ["..."] }  // 仅 plan-workflow 填 criticalToolIds
```

避免 orchestrator 拉分页 members。

---

## 5. 前端设计（`/tools` · 工具集 Tab）

### 5.1 布局

```
┌─ 工具集 · {tenant} ─────────────────────────────────────┐
│ [ReAct] [Planner Workflow]     租户▼  [+ 添加工具]      │
│ 搜索…                              共 N 条 · 分页       │
├────────────────────────────────────────────────────────┤
│ 表格列：展示名 | 来源 | 读写 | 池启用 | 需确认 | 关键* | 操作 │
│         ...    | 剔除 |                                  │
│ *「关键」列仅 Planner Workflow Tab 显示，Switch 调 PATCH   │
└────────────────────────────────────────────────────────┘
```

- **空态**：插图 +「尚未添加工具」+ 主按钮「添加工具」
- **池启用=false**：行展示灰色 Tag「池内已停用」，仍可剔除；不出现在 ReAct 运行时
- **读写 / 需确认**：Tag 只读，跳转提示「请在 SDK/MCP 管理页修改」
- **分页**：`NPagination`，绑定 members API `page/size`
- **批量剔除**：表格多选 + 工具栏「批量剔除」

### 5.2 添加工具弹窗 `ToolSetAddModal.vue`

- 分组树：SDK · {app} / MCP · {server}，组头「全选本组」
- 多选 checkbox；底部「已选 N 项」
- Planner Tab：每项可勾选「设为关键」（映射 `items[].critical`）
- 确认 → `members:add`
- 候选来自 `picker` API（仅 enabled、未入集）

### 5.3 删除的前端逻辑

- 移除 `ToolPoolGroupSection` 在工具集 Tab 的「启用」Switch
- 移除 `criticalToolIds` 独立 Set 与 `plan-workflow-critical` API 调用
- 移除 SDK/MCP 停用时的工具集级联 `put*ToolSet`
- 工具栏 Tag 改为「集内 N 条 · 池启用 M/N」或「集内 N 条」

### 5.4 组件拆分（顺带 TD-080）

| 组件 / composable | 职责 |
|-------------------|------|
| `useToolsetPage.ts` | 成员分页、picker、add/remove、critical patch |
| `ToolsetMembersPanel.vue` | 表格 + 分页 + 空态 |
| `ToolSetAddModal.vue` | 分组多选弹窗 |
| `ToolsView.vue` | Tab 路由壳（目标 <200 行） |

---

## 6. 后端实现要点

### 6.1 `ToolSetMemberService`（新）

- `pageMembers(kind, tenantId, page, size, q)`
- `picker(kind, tenantId, q)`
- `addMembers` / `removeMembers` / `patchCritical`
- `listToolIdsForRuntime(kind, tenantId)` — orchestrator 用

### 6.2 `ToolSetAdminService` 收敛

- 删除 `PLAN_WORKFLOW_CRITICAL` 及 get/put 方法
- 旧 `putToolSet` 整表替换：**删除或仅留 `@Deprecated` 给迁移脚本**

### 6.3 `ToolSetResolver`（orchestrator）

```java
// critical 从新端点获取，不再 fetchPlanWorkflowCritical 独立集
resolvePlanWorkflowCriticalTools(tenantId)
  → toolSetClient.fetchPlanWorkflowToolIds(tenantId).criticalIds()
  → intersect enabledPool
```

### 6.4 Catalog DTO（TD-082 对齐）

成员 / picker 响应携带 `source` + `sourceRef`，前端停止 `id` 前缀解析。

---

## 7. 错误处理

| 场景 | 行为 |
|------|------|
| 添加未启用工具 | 400 + `{ code, rejected: [{ toolId, reason: "not_enabled" }] }` |
| 添加非法 toolId | 400 `TOOL_ID_INVALID` |
| patch critical 非成员 | 404 |
| 租户继承全局集 | `scope: inherited`；写操作创建租户覆盖集（与现逻辑一致） |
| 空集运行 ReAct | 仅内置工具（RagTool、manage_tasks 等），无业务 remote tool |

---

## 8. 测试与验收

### 8.1 单测

- `ToolSetMemberServiceTest`：空集、add、remove、critical patch、enabled 门禁、分页搜索
- `ToolSetResolverTest`：critical 从 plan-workflow 成员读取
- 迁移：critical 集合并后 ID 一致

### 8.2 Live（`verify_tool_integration_live.py` · toolset suite）

| 用例 | 预期 |
|------|------|
| T1 | 新环境 react/plan 成员数为 0 |
| T2 | add 2 个 enabled 工具 → members 列表 2 条 |
| T3 | 停用其中 1 个（SDK Tab）→ 成员仍在，enabled 只读 false；ReAct schema 仅 1 个 |
| T4 | remove 1 条 → 成员 1 条 |
| T5 | Planner add + critical=true → `resolvePlanWorkflowCriticalTools` 命中 |
| T6 | picker 不含已成员、不含 disabled |

### 8.3 前端

- `npm run build`
- 手工：空态 → 弹窗组选 → 分页 → 剔除 → Planner 关键 Switch

---

## 9. 实施顺序

1. **DB**：`critical` 列 + 种子清理 + 迁移脚本  
2. **tool-manager**：MemberService + API；删 critical 集；runtime tool-ids 端点  
3. **orchestrator**：`ToolSetClient` / `ToolSetResolver` 切换  
4. **BFF**：透传新 API；删 critical 路由  
5. **前端**：工具集 Tab 重做 + 弹窗 + 分页  
6. **验收脚本** + 文档（更新 `2026-07-09-tool-integration-design.md` §工具集）

---

## 10. 非目标（YAGNI）

- 工具集内修改 `enabled` / `sideEffect` / `requireConfirmation`
- per-tenant 工具池 enabled（仍全局共享，Phase 1 限制不变）
- 拖拽排序（`sort_order` 按添加顺序即可，后续再做）
- `PUT` 整表替换 UI 入口

---

## 11. 已确认决策

| 项 | 选择 |
|----|------|
| 关键工具 | **B** — `tool_set_member.critical`，废弃独立 critical 集 |
| 默认成员 | **空集** |
| 添加候选 | **仅 enabled 池内工具** |
| 停用级联 | **不自动剔除成员** |
| 分页 | **服务端分页** |
