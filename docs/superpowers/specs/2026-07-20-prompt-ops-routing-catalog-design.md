# Prompt 运营中心 + 统一路由规则引擎

> **阶段**：四 · **任务卡**：4.11（扩展）  
> **状态**：🟡 实施中（backend + `/prompts` UI + Live 近收口；审核流二期）  
> **日期**：2026-07-20  
> **前置**：[phase3 §3.8 PromptComposer](./phase3-production-hardening-design.md) · [routing-golden-set.md](../../routing/routing-golden-set.md) · [skills-management-ui-design.md](./skills-management-ui-design.md) · [workflow-studio-design.md](./2026-06-25-workflow-studio-design.md)  
> **关联**：`prompt-manager`（:8500 骨架）· `docs/nacos/sunshine-orchestrator.yaml`（迁出后不再作提示词/规则 SSOT）

---

## 1. 定位

将阶段三已落地的 **PromptComposer 六层拼装** 与 **Policy Chain 意图路由**，升级为可运营的 **Prompt 中心（4.11）**：

| 能力 | 定义 |
|------|------|
| **Prompt Catalog** | `prompt-manager` DB 为唯一 SSOT；全量原 `agent.*` 提示词/时间线/改写等进 Catalog |
| **统一 Rule Engine** | 合并原 L1 structural / L1b peer / L2 golden-rule 为可无限扩展的规则表（priority + matchType） |
| **深度运营** | 路由：静态冲突 + 样例试跑；ReAct：Composer 层骨架 + 工具族 fragment |
| **前端** | `/prompts`：全部（系统配置，不可新建）/ 路由规则 / **React 提示词**（场景叠加）；版本栏对齐 Skills |

**不替代**：Skills / Experts / Workflow 节点各自 overlay（仍在原页维护）。本页只管 **orchestrator 全局** 提示词与路由规则。

### 1.1 已锁定决策

| # | 决策 |
|---|------|
| D1 | 信息架构：统一「提示词中心」；首期 Catalog **壳覆盖全 kind**，深度能力只砸 **路由规则** + **ReAct** |
| D2 | **DB（prompt-manager）唯一 SSOT**；规则/提示词 **不再经 Nacos**（体量与可运营性） |
| D3 | orchestrator **启动拉全量 Catalog → 本地缓存 + 热更新**（对齐 Skill/Tool Catalog）；刷新失败保留上一 Snapshot |
| D4 | 冲突检测：**保存时静态告警** + **样例句试跑**（展示命中层/规则；首期不调真 LLM） |
| D5 | ReAct 切块：Composer **层为骨架** + **工具族 fragment**；`timeline` 单独 kind，不混进 system overlay |
| D6 | 路由链固定：`#/$/@` 硬绑定 → **UnifiedRuleEngine** → L3 LLM；**不可拖拽阶段顺序** |
| D7 | 原 L1/L1b/L2 合并为引擎内不同 `matchType`；用种子 **priority** 保持 golden-set 行为，取消「L1 漏判则跳过 L2」隐藏特例 |
| D8 | 版本：`draft` / `published` + `active_version` 回滚；首期 **不做** Knowledge 评测门禁 / 审核流 |
| D9 | 禁止模型输出二次加工；提示词不对只改 Catalog，不在 Composer/前端打补丁 |
| D10 | Catalog 未就绪：**启动 fail-fast**；运行中刷新失败：**保留旧 Snapshot** + 告警 |

---

## 2. 信息架构与前端

### 2.1 导航

`MainLayout` 平台区增加 **提示词** → `/prompts`；`FILL_CONTENT_ROUTES` 纳入 `prompts`。

### 2.2 页面同构

对齐 Skills / Experts：

- 顶栏：`SidebarToggle` + 标题 +「新建」「刷新」
- 左列表 + 右详情；视觉 `--sun-black` + 边框；表单 `sun-field`
- 左卡：展示名、`kind` 标签、启用开关；`routing-rule` 额外展示 priority

### 2.3 三视图（同一路由 Tabs）

| 视图 | 左列表 | 新建 | 右栏 |
|------|--------|------|------|
| **全部** | 系统配置（排除 `routing-rule` / `react-prompt`） | **禁止**（页头无新建） | 编辑内容 + Skills 风格版本栏 |
| **路由规则** | 仅 `routing-rule`，priority 降序 | 左栏右上角「新建规则」 | 结构化表单 + 冲突/试跑；`mode=react` 可选 `reactPromptId` |
| **React 提示词** | 仅 `react-prompt`（场景叠加，非基础 system） | 左栏右上角「新建场景」 | 正文编辑 + 版本栏（同 Skills） |

版本栏：当前版本 Select + 状态 Tag + 主按钮（发布并生效 / 设为此生效版）+ 三点更多（复制为新草稿等）。

### 2.4 路由规则右栏

1. 表单：id、priority、matchType、match、patterns / domainGroups、plan（mode + workflowId/params）、可选 **`reactPromptId`**  
2. 冲突条 + 试跑  

### 2.5 React 提示词与运行时拼装

- kind=`react-prompt`：场景方向文案，**累加**在基础链之后，不替换 `system-prompt`  
- 拼装顺序：`system-prompt` → `mode-overlay.react` → **`react-prompt.{id}`**（仅当路由 `params.reactPromptId` 有值）→ HITL / skill / memory / scope…  
- 无 `reactPromptId`：不加场景段（兼容底栏强制 ReAct）

### 2.6 非目标（前端）

- Chat 底栏不嵌配置  
- 不迁入 Skill/Expert/Workflow 节点 prompt  
- 不做「ReAct 层拼装器」运营 UI（fragment 若仍存在仅作全局 overlay 内部消费） 

---

## 3. 数据模型与 API

### 3.1 服务

| 项 | 约定 |
|----|------|
| 服务 | `prompt-manager` :8500 |
| 库 | `sunshine_prompt`；init：`docker/mysql/init/17-sunshine-prompt-manager.sql` |
| 消费 | orchestrator `PromptCatalogClient`；BFF 透传 CRUD |

### 3.2 表

**`prompt_definition`**：`id`, `kind`, `display_name`, `description`, `enabled`, `priority`（规则用，其它 0）, `active_version`, 时间戳  

**`prompt_version`**：`prompt_id` + `version`, `status`(`draft`\|`published`), `content_text`, `content_json`, `change_note`, `maintainer`, `created_at`

### 3.3 kind

| kind | 示例 id | 载荷 |
|------|---------|------|
| `system` | `system-prompt` | text |
| `mode-overlay` | `mode-overlay.react` / `.react-restart` / `.subagent` / `.workflow`… | text + mode |
| `react-fragment` | `react-fragment.sandbox` 等 | text + `attachTo` + `sortOrder` |
| `intent` | `intent.classifier` | text |
| `planner` | `planner.prompt` | text |
| `answer` | `answer.template` / `answer.overlay` | text |
| `timeline` | `timeline.intent` / `timeline.steps.*` | json before/active/after |
| `rewrite` | `rewrite.intent` / `rewrite.planner` | text |
| `hitl` / `memory` / `scope` | 对应原键 | text |
| **`routing-rule`** | `routing-rule.{id}` | 见 §3.4 |

### 3.4 routing-rule `content_json`

```json
{
  "matchType": "regex | multi_step | domain_groups | peer_phrase",
  "match": "any | all",
  "patterns": ["..."],
  "domainGroups": ["knowledge", "finance"],
  "minDomainGroups": 2,
  "plan": {
    "mode": "WORKFLOW|PLAN_WORKFLOW|PEER_COLLAB|REACT",
    "workflowId": "...",
    "params": {}
  }
}
```

### 3.5 API

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/prompts` | 列表（kind/enabled） |
| GET | `/api/prompts/{id}` | 详情 + active |
| POST | `/api/prompts` | 新建 |
| PUT | `/api/prompts/{id}` | 元数据 |
| POST | `/api/prompts/{id}/versions` | draft / published |
| POST | `/api/prompts/{id}/publish` | 切 active |
| GET | `/api/prompts/{id}/versions` | 版本列表 |
| GET | `/api/prompts/catalog` | 运行时精简 Catalog |
| POST | `/api/prompts/routing/validate` | 静态冲突 |
| POST | `/api/prompts/routing/dry-run` | 样例试跑 |

匹配逻辑抽 **共享模块**（prompt-manager 与 orchestrator 共用），避免双写。

### 3.6 种子与迁移

- init 从现行 `sunshine-orchestrator.yaml` 导入为 `published` v1  
- 删除 orchestrator 对已迁键的 `@ConfigurationProperties`；**禁止 Nacos 影子兜底**  
- `sync_nacos.py` 不再同步已迁键（文档注明）

---

## 4. 统一 Rule Engine 与消费链路

### 4.1 固定链

```
消息 → L0（# > $ > @）→ UnifiedRuleEngine → L3 IntentRouter
       ↑ executionPreference ≠ auto 时 ForcedExecutionRouter 整段绕过（不变）
```

### 4.2 替换

| 删除 | 由 |
|------|-----|
| `StructuralRoutingPolicy` | `matchType=multi_step` / `domain_groups` |
| `PeerStructuralRoutingPolicy` | `matchType=peer_phrase` |
| `GoldenRuleRoutingPolicy` + `RuleBasedRouter` | `matchType=regex` 等 |
| L2「L1 漏判保险丝」 | 种子 priority：多步/跨域 **高于** 单域 regex |

保留：`WorkflowBinding` / `ExpertBinding` / `SkillBinding`。  
新增：`UnifiedRuleRoutingPolicy`（L0 与 L3 之间）。

### 4.3 引擎语义

1. 加载 enabled 的 `routing-rule` active 版本  
2. `priority` 降序，同 priority 按 `id` 稳定序  
3. 首命中 → `ExecutionPlan`；否则 L3  
4. matchType 分派到迁入共享模块的既有匹配器

### 4.4 Catalog → Snapshot

```
prompt-manager catalog
  → PromptCatalogClient
      → RoutingRuleSnapshot → UnifiedRuleEngine
      → PromptOverlaySnapshot → PromptComposer / ReActAgentFactory
      → Intent / Timeline / Rewrite / Planner / Answer / … Snapshot
```

ReAct：`mode-overlay.react` + enabled fragments（`attachTo` + `sortOrder`）追加；其余层顺序不变，内容改读 Catalog。

### 4.5 验收锚点

- `RoutingGoldenSetTest` / `routing-golden-set.md` 行为不回退  
- Live：`verify_prompt_catalog_live.py`（拉取、改 priority、dry-run、回滚、热更新）

---

## 5. 错误处理、发布与测试

### 5.1 发布

| 动作 | 行为 |
|------|------|
| draft | 不影响运行时 |
| publish | `published` + 切 `active_version` + bump `catalogVersion` |
| 回滚 | active 指回历史 published |
| 热更新 | 原子替换 Snapshot；进行中对话不中断 |

`enabled=false` 立即从 Snapshot 排除。

### 5.2 错误

| 场景 | 策略 |
|------|------|
| 启动无 Catalog | fail-fast |
| 运行中刷新失败 | 保留旧 Snapshot + 告警 |
| 单条 JSON 非法 | 跳过该条 + error |
| workflowId 无效 | 校验警告；命中按现网缺失语义显式失败 |
| 并发发布 | 乐观锁 → 409 |

### 5.3 测试

- 单测：matchType、priority、冲突、fragment 拼接、脏 JSON  
- 回归：golden-set；Composer/Intent 改 Snapshot fixture  
- Live：catalog 脚本；前端三视图 + 试跑 + 视觉对齐 Experts  
- 权限首期：登录可写；`maintainer` + `change_note` 留痕

### 5.4 首期范围总表

| 做 | 不做 |
|----|------|
| DB SSOT + 全 kind 壳 | Nacos 双写 |
| 统一引擎 + validate/dry-run | 链拖拽、dry-run 真 LLM |
| ReAct 层 + fragment | Skill/Expert/Workflow 节点迁入 |
| draft/published + 回滚 | 评测门禁、审核流 |
| `/prompts` | Chat 内嵌配置 |

### 5.5 成功标准

1. 规则可无限增，不受 Nacos 体量限制  
2. UI 可调 priority，试跑即时见命中  
3. ReAct 可分块维护，拼装顺序与现 Composer 一致  
4. golden-set 不回退；可回滚至上一 published  

---

## 6. 与既有文档关系

| 文档 | 关系 |
|------|------|
| `phase4-platformization-design.md` §4.11 | 本 spec 为详设；「审核」降为二期，首期 published + 回滚 |
| `routing-golden-set.md` | 迁移后仍为路由验收 SSOT；配置源改为 Catalog |
| `phase3` §3.8 | PromptComposer 层序保留；数据源改 Catalog |
| Skills / Experts UI | `/prompts` 布局与交互参照 |

---

## 7. 实施备注（非本 spec 展开）

实施计划另文（writing-plans）；建议切片：

1. DB + prompt-manager CRUD/catalog + 种子迁移  
2. 共享 Rule Engine + orchestrator 切换 + golden-set  
3. PromptComposer/Timeline/Intent 切 Snapshot  
4. `/prompts` 三视图 + validate/dry-run  
5. Live 脚本与 Nacos 键退役文档
