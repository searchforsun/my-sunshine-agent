# 会话 kind 资源过滤 · 业务场景 Lab · 工具集轴迁移

> **日期**：2026-08-13  
> **状态**：✅ 已实现（K0～K4 全绿；Live `scripts/verify_kind_biz_scene_live.py`；[plan](../../plans/2026-08-13-kind-biz-scene-catalog.md)）  
> **定位**：把「会话形态 `kind`」贯穿 Catalog 可发现面与默认工具集；建立独立 **业务场景 Lab**（`biz_scene` 闭集码表）；退役 React Prompt 场景与按执行模式划分的工具集 Tab。  
> **关联**：[unified-routing v6](../2026-07-29-unified-routing-design.md) · [business-context-authority](../2026-08-13-business-context-authority-design.md) · [task-scene-context](../2026-08-01-task-scene-context-design.md) · [toolset-membership](2026-07-11-toolset-membership-design.md)（历史）· [prompt-ops](./2026-07-20-prompt-ops-routing-catalog-design.md)

---

## 1. 问题与目标

| 现状 | 问题 |
|------|------|
| 默认工具集 Tab = ReAct / Planner Workflow | 绑在**执行模式**，与四轴中的会话 `kind` 错位；`fast`/`pro` 不应各吃一套默认工具 |
| Catalog 资源无会话形态过滤 | chat 会话可召回编码向 Skill；task 可灌企业问答型 Agent |
| 业务行为靠 `react-prompt.*` 场景 | 与 `biz_scene` / Skill overlay 双轨；场景 prompt 非结构化权威入口 |
| 上下文管理页混会话 | chat/task 保存内容不同，UI 未分栏易误读 |

**做**：

1. 资源元数据 `kind`（与会话同一轴）过滤可发现/可召回面  
2. 默认工具集按会话 `kind`：`chat` | `task`（UI Tab 同步改名）  
3. 独立侧栏 **业务场景** Lab：闭集码表 +（同页或邻接）Policy；Skill/Agent 单值引用  
4. 退役 Catalog `react-prompt` 与路由 `reactPromptId`  
5. 上下文管理页按 `kind` 分栏，Tab 载体对齐记忆闸门  

**不做**：

- 用 `executionMode` 再选默认工具集  
- 独立场景分类器 / 选场景 HITL / AI 发明 `biz_scene`  
- Tool / Workflow 一期参与 `biz_scene` 解析  
- 新建 context 微服务  

---

## 2. 命名（与四轴一致）

| 字段 | 会话 | Catalog 资源（Skill / Agent / Workflow） | 默认工具集 |
|------|------|------------------------------------------|------------|
| **`kind`** | `chat` \| `task` | `chat` \| `task` \| `all`（默认 `all`） | 集本身即 `chat` 或 `task`（无 `all` 集） |
| **`biz_scene`** | 运行时解析结果（可空） | Skill/Agent：可空单值 VARCHAR，须 ∈ Lab active | 不挂 |
| **`executionMode`** | 用户选择 | 不决定默认工具集 | 正交 |

**同一轴、不同基数**：会话二元；资源三元含 `all`。禁止另造 `applicable_kind` / `kind_scope` 别名。

过滤（资源召回 / 列表）：

```
资源.kind == 'all' OR 资源.kind == 会话.kind  → 保留
否则剔除
```

单工具是否出现在某形态：由「是否加入该 `kind` 的默认工具集」决定，不在工具行上再挂第三种 `all`（与资源 `kind` 分层）。

---

## 3. 业务场景 Lab（拍板 A）

### 3.1 产品入口

侧栏与「提示词」**平级**新增 **「业务场景」**，**不**挂在 Prompt 模块下。

页内：

1. **码表** `biz_scene_definition`（名称可落 DDL 时定）：`biz_scene`、`display_name`、`description`、`status(active|retired)`、`tenant_id`  
2. **场景 Policy**（既有 `biz_scene_policy`，按码精确匹配）

**禁止**运行时 AI / 模型新建码；仅运营在本页创建。`retired` 码不可再绑到新资源；已绑资源解析时若码 retired → 视为无效并跳过权威层（记 audit）。

### 3.2 谁引用

| 资源 | `kind` | `biz_scene` |
|------|--------|-------------|
| Skill | `chat` \| `task` \| `all` | 可空单值；非空 ∈ Lab active |
| 智能体 | 同上 | 同上 |
| 工具 | 不进资源 `kind` 三元表；见 §4 工具集 | 一期不挂 |
| 工作流 | `chat` \| `task` \| `all` | 一期不挂（不参与 scene 解析） |

解析算法：保持 [business-context-authority §2.1](../2026-08-13-business-context-authority-design.md)（agent 优先，否则 skill 第一非空；均无 → null → 跳过权威层）。

### 3.3 与 Prompt 边界

| 载体 | 职责 |
|------|------|
| **业务场景 Lab** | `biz_scene` 闭集 SSOT；Policy / 任务板 / 偏好白名单键 |
| **Skill overlay** | 业务行为文案（承接原 react-prompt 内容迁移） |
| **`mode-overlay.*` / harness overlay** | 执行机制层（选工具、思考、收束）；**保留** |
| **`react-prompt.*`** | **退役删除** |

React Prompt 场景 ID 可与 `biz_scene` **同名对齐迁移**，但迁移后 SSOT 只在 Lab；禁止 Prompt 场景 ID 再当权威键。

---

## 4. 默认工具集轴迁移

### 4.1 枚举

| 旧 `ToolSetKind` | 新 |
|------------------|-----|
| `react-default` / `REACT_DEFAULT` | `chat` / `CHAT_DEFAULT` |
| `plan-workflow` / `PLAN_WORKFLOW` | `task` / `TASK_DEFAULT` |

全局/租户 set id 建议：`global-chat-default`、`tenant-{id}-chat-default`；`global-task-default`、`tenant-{id}-task-default`。  
过渡：读路径双读旧 id；写出与 Admin API 只认新码。UI「工具集配置」子 Tab：**chat | task**。

### 4.2 运行时

```
装默认 Toolkit：
  conversation.kind == chat → resolveChatTools(tenant)
  conversation.kind == task → resolveTaskTools(tenant)
  不按 executionMode=fast|pro 分支
```

- `chat × fast` 与 `chat × pro` **共用** chat 默认集  
- `executionMode=workflow`：以工作流节点 / 显式工具为准，**不强制**吃默认集  
- `ToolSetResolver`：`resolveReactTools` / `resolvePlanWorkflowTools` 重命名为 `resolveChatTools` / `resolveTaskTools`（调用点全量替换）

关键工具（原 plan-workflow critical）：挂在 **task** 默认集成员的 `critical` 标志上，语义不变，仅换集。

---

## 5. 退役 React Prompt 场景

| 动作 | 对象 |
|------|------|
| 删 UI | `/prompts`「React 提示词」Tab |
| 删/停用 Catalog | `prompt_definition.kind = react-prompt` 种子与运营数据 |
| 删协议 | 路由规则 / Intent 计划 `params.reactPromptId`；LLM 意图输出不再产该字段 |
| 迁内容 | 有业务价值的正文 → 对应 Skill overlay；码 → Lab `biz_scene` + Skill/Agent 打标 |
| 保留 | `mode-overlay.react*`、planner harness 等机制 overlay |

路由规则种子中「绑 react-prompt.xxx」改为绑 skill / 仅模式，或删规则改走 Skill 召回（实现计划里列迁移表）。

---

## 6. 上下文管理页

- 会话列表：**chat | task** 分栏或强过滤（字段 `conversation.kind`）。  
- 详情 Tab 随 kind 切换载体（只读展示，闸门 SSOT 仍在 task-scene / 五层）：

| chat | task |
|------|------|
| L1 会话快照 | L1（task Near/Mid 规则） |
| L2 用户状态 | W0 工作区（**不**展示用户 L2） |
| L3 历史索引 | T0（fast）/ H1（pro）说明 + task-L3；不自动展示 chat-L3 串读 |

---

## 7. 端到端装配顺序

```
① 会话 kind
   → 默认工具集（chat|task）
   → 资源候选过滤（skill/agent/workflow：kind ∈ {会话kind, all}）
② 意图收集 → skillIds / agentIds（或 workflow 轨）
③ 读 biz_scene（Lab 校验；空则跳过权威层）
④ 有 scene 且启用业务权威 → Policy ∥ 任务板 ∥ 场景偏好（一期主挂 chat）
⑤ L3 语义（无 reactPromptId）
⑥ PromptComposer（mode-overlay / Skill overlay / harness）+ Toolkit → 主 LLM
```

与 [business-context-authority §2.2](../2026-08-13-business-context-authority-design.md) 并行组兼容：本文件的 ① 过滤发生在意图链候选构建时；③④ 仍必须在资源召回之后。

---

## 8. 数据与 API（草案）

| 项 | 落点 |
|----|------|
| `skill_definition.kind` / `agent_definition.kind` / workflow 定义 `kind` | `19-sunshine-resource.sql`（或工作流库对等列）；默认 `all` |
| `skill_definition.biz_scene` / `agent_definition.biz_scene` | 权威层已述；FK/校验对 Lab |
| `biz_scene_definition` | 新表；与 `biz_scene_policy.biz_scene` 同码空间 |
| 工具集 type / set_id | tool-service DDL + `ToolSetKind` 枚举迁移 |
| Admin API | 业务场景 CRUD；工具集 path `.../chat` `.../task`；资源 PATCH 含 `kind`/`biz_scene` |

禁止 Flyway；增量进 `docker/mysql/init/`。

---

## 9. 分期与验收

| 阶段 | 内容 | 验收 |
|------|------|------|
| **K0** | 工具集枚举/UI/Resolver：chat|task；双读旧码 | Tab 文案正确；`kind=chat` 会话只装 chat 集 |
| **K1** | Skill/Agent/Workflow 元数据 `kind` + 召回过滤 | task 会话召不回 `kind=chat` 资源 |
| **K2** | 业务场景 Lab + Policy 同管；Skill/Agent 打标 | 无 Lab 码无法保存非法 scene；有 scene 才进权威层 |
| **K3** | 退役 react-prompt + `reactPromptId`；内容迁 Skill | grep 零 `react-prompt` / `reactPromptId`（测试夹具除外可删） |
| **K4** | 上下文页 chat/task 分栏 + Tab 载体 | task 会话不展示用户 L2 为权威 |

Live：扩展或新建 `verify_kind_biz_scene_live.py`（K0/K1 起可测工具集与过滤）。

---

## 10. 反模式

1. 默认工具集再按 `fast`/`pro` 分裂  
2. 资源字段改名 `applicable_kind` 造成双文档  
3. 把 Lab 做进「提示词」子 Tab  
4. Tool/Workflow 多源解析 `biz_scene`  
5. 保留 `reactPromptId`「兼容一层」长期双写  
6. task 会话强上业务任务板（仍遵权威层一期主挂 chat）  

---

## 11. 决议记录

| # | 决议 | 日期 |
|---|------|------|
| K-D1 | 资源 `kind` 与会话 `kind` 同一轴；资源含 `all` | 2026-08-13 |
| K-D2 | 业务场景 = 独立侧栏 Lab（方案 A），不挂 Prompt | 2026-08-13 |
| K-D3 | 默认工具集 Tab/解析轴 = chat\|task；废 ReAct/Plan-Workflow 集名 | 2026-08-13 |
| K-D4 | 退役整层 `react-prompt`；业务文案迁 Skill overlay + Lab | 2026-08-13 |
| K-D5 | Skill/Agent `biz_scene` 单值；Tool/Workflow 一期不参与解析 | 2026-08-13 |
| K-D6 | 上下文管理页按 kind 分栏；Tab 对齐记忆载体 | 2026-08-13 |

---

## 12. 文档关系

| 文档 | 本设计对其的补丁要求 |
|------|----------------------|
| [unified-routing](../2026-07-29-unified-routing-design.md) | 意图候选前按资源 `kind` 过滤；工具集不绑 executionMode |
| [business-context-authority](../2026-08-13-business-context-authority-design.md) | Lab 为码表 SSOT；扩场景入口 = Lab + Skill/Agent 打标 |
| [task-scene](../2026-08-01-task-scene-context-design.md) | 管理 UI 分栏引用本节 §6 |
| prompt-ops | 标记 `react-prompt` 退役 |
| toolset-membership（归档） | 以本节 §4 为现行 SSOT |
