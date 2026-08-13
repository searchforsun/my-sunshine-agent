# 业务上下文权威层（Business Context Authority）

> **日期**：2026-08-13  
> **状态**：⬜ 设计评审中（用户已拍板：任务板 SSOT = 平台自建表 + `external_ticket_ref`）  
> **定位**：企业生产 Agent 的**结构化权威底座**——任务板 / 场景偏好白名单 / 场景 Policy；挂载于既有五层读路径之上，**不**替代 L1–L5 压缩管道，**不**新建 context 微服务。  
> **关联**：[unified-context-compression](./2026-07-31-unified-context-compression-design.md)（五层 SSOT）· [task-scene-context](./2026-08-01-task-scene-context-design.md)（chat/task 记忆闸门）· [unified-routing v6](./2026-07-29-unified-routing-design.md)（`kind`/`executionMode`/`callSite`/`biz_scene` 四轴）

---

## 1. 问题与目标

长对话若只靠滑动窗 + 向量摘要，会出现：Token 膨胀、跨会话无法续流程、偏好跨场景污染、业务硬规则被相似度「猜」出来。

**做**：

| 块 | 职责 |
|----|------|
| **任务板** `business_task` | 按用户 + 活跃状态 + 时间窗召回，跨会话续流程 |
| **用户偏好** | 按当前 `biz_scene` 白名单装载，禁止盲目全量 |
| **场景约束** `biz_scene_policy` | 按 `biz_scene` SQL 精确匹配硬规则（阈值/权限/风控/HITL） |

**不做**：

- 新建独立 context 微服务  
- 用向量检索 Policy 或活跃任务列表  
- 与 ReAct Todo / Planner H1 / task-scene T0·W0 合并命名或双写权威态  
- 对模型输出做截断/摘要式「权威修复」；权威只来自 SQL 回写与 HITL  
- 强制 C 端闲聊上全套（可关 Nacos 开关）

**核心原则**：结构化权威优先；向量仅语义补充；证据只留指针；冲突可仲裁且不交由模型抹平。

---

## 2. 命名四轴（硬隔离）

| 字段 | 取值示例 | 含义 | 定责 |
|------|----------|------|------|
| **`kind`** | `chat` \| `task` | 会话形态（记忆闸门） | 用户 / 会话；**旧名 `scene` 废弃** |
| `executionMode` | `fast` \| `pro` \| `workflow` | 执行器 | 用户选择 |
| **`biz_scene`** | `refund` / `contract_approve` / `ticket` / … | **业务域编码** | **由被召回的 Skill / 子 Agent 元数据带出**（见 §2.1）；禁止运行时 AI 发明新码 |
| **`callSite`**（`call_site`） | `plan` / `rewrite` / … | LLM 调用点 | orchestrator 注入；**旧名 `call_scene` 废弃** |

**禁止**把 `biz_scene` 写入 `kind` 或 `callSite`。**禁止**再用裸词「scene」同时指会话形态、业务域或调用点。

### 2.1 `biz_scene` 解析（简化 · 无用户介入）

> **决议 D7**：不做独立场景分类器、不做选场景 HITL、不让模型自由生成 scene 码。场景随 **Catalog 资源召回** 附带。

**元数据（运营事先配置）**：

| 资源 | 字段 | DDL |
|------|------|-----|
| Skill | `skill_definition.biz_scene`（可空 VARCHAR） | `19-sunshine-resource.sql` |
| 子 Agent | `agent_definition.biz_scene`（可空 VARCHAR） | 同上 |

`biz_scene` 取值必须落在 Policy/任务板使用的闭集码表（与 `biz_scene_policy.biz_scene` 对齐）；空 = 该资源不触发结构化业务记忆。

**解析算法（唯一）**：

```
1. IntentRouter / 资源召回得到 skillIds[]、agentIds[]（既有链路；含 $ 绑定、L3 候选合并）
2. 取 biz_scene：
   - 若命中 agent 且 agent.biz_scene 非空 → 用该值（多 agent 时取排序第一非空）
   - 否则扫 skillIds：第一个非空 skill.biz_scene
   - 均无 → biz_scene = null
3. 分支：
   - biz_scene != null → 装载结构化权威层（Policy + 同 scene 任务板 + 场景偏好白名单）
   - biz_scene == null → **跳过**结构化业务层；退化为既有「L1 上下文 + 用户提问 → L3 语义召回」
```

**不做**：用户选场景 UI、低置信追问、独立 `BizSceneResolver` LLM 分类、AI 动态建 scene。  
**扩场景**：运营给 Skill/Agent 填 `biz_scene` + 配 Policy，无需改分类 prompt。

**装配时序**：结构化业务块必须在**资源召回之后**组装（或召回后补注入）；禁止在未知 skill/agent 时空想 `biz_scene`。细则见 §2.2。

### 2.2 装配时序与并行（目标态）

> **现状缺口**：`ChatStreamContextFactory` 在路由**之前**同步跑完 `ContextAssembler`（L1+L2+L3+guide），预召回几乎全串行。本层要求 **先 Skill/Agent，再结构化业务记忆**，必须改时序；并行组用于降延迟，不改变依赖边。

**命名勿混**：意图链 L0–L3（收资源）≠ 记忆 L1–L5（装上下文）≠ 产品 `kind` ≠ `biz_scene` ≠ `callSite`。

**目标顺序**：

```
① 轻量会话底座
② 意图收集 → skillIds / agentIds（L0→规则→L2 embedding→必要时 Intent LLM）
③ 从命中资源读 biz_scene（§2.1）
④ 有 scene → Policy + 任务板 + 场景偏好（SQL）
   无 scene → 跳过 ④
⑤ L3 语义：近文 + 用户提问（无 scene 时的主长期补充；有 scene 时低优先级）
⑥ PromptComposer + Toolkit → 主 LLM
   （知识库 RAG / 大工具结果：工具按需，不预召回）
```

**并行组（可多路）**：

| 组 | 内容 | 约束 |
|----|------|------|
| **P0** | `loadHistory` ∥ 脱敏（若契约允许） | 均需先于主装配 |
| **P1** | `L2`（全局极少项）∥ `projectGuide` ∥（L1 partition 完成后）可预热；**L3 建议延后到 ⑤** | 不依赖 biz_scene |
| **P2** | 轨 A：`agent` embedding ∥ `skill` embedding | L0/规则仍短路串行 |
| **P3** | 有 `biz_scene` 时：`Policy` ∥ `任务板` ∥ `场景偏好` | **必须在 ②③ 之后** |
| **P4** | `Toolkit` ∥ Prompt 静态层（skill overlay HTTP 除外） | 主 LLM 前合并 |

**必须串行**：

- L0 → 规则 →（需要时）Intent LLM  
- **②③ → ④**（先资源后结构化记忆）  
- Budget / 最终 messages 合并  
- 主 LLM  

**L3 时机（落地选一，推荐 A）**：

| 方案 | 做法 | 取舍 |
|------|------|------|
| **A（推荐）** | 意图完成后再跑 L3 | 逻辑干净；无 scene / 有 scene 同一插入点；多一次相对路由的等待可与 P3 并行（L3∥P3） |
| B | L3 与意图链部分重叠 | 省墙钟时间；无 scene 时白打或需取消 |

**与现状对照**：把「assemble 整包」拆成「底座 P1」+「路由后 P3+L3」；禁止继续在未知 skill/agent 时预装 Policy/任务板。

### 2.3 与压缩点模式 / chat·task 启用面（正交）

| 问题 | 结论 |
|------|------|
| 是不是 task 要的？ | **否。一期主路径 = `kind=chat`**。`kind=task` 走 [task-scene](./2026-08-01-task-scene-context-design.md)（P0/W0/T0·H1、压缩点优先 task×fast\|pro），**默认跳过本层** |
| 是否等于压缩点模式？ | **否。正交增强**：不改 `far_folded_msg_ids`、不替代 Near/Mid/Far 重组、不抢 task 压缩点一期启用面 |
| 是否符合压缩点前置约束？ | **原则符合**，须遵守下列挂载纪律（对齐五层 §5.5 Tier / prefix C1–C3） |

**压缩点兼容纪律（chat 落地时）**：

1. **Policy / 场景偏好**：视为低频结构化块（类 Tier 0/1），**不进** L1 Near/Mid/Far 折叠；渲染顺序固定，禁止每轮重排中段。  
2. **任务板详情**：随工具回写会变 → 按 **content-hash** 仅在变更时改块（对齐 T0 降频）；或置于 query 前动态尾段，避免无意义打穿整段 KV。  
3. **L3**：保持「绝对尾部动态段」语义（五层 §7.5）；§2.2 方案 A（路由后再召回）与此一致，优于路由前灌 L3。  
4. **Skill 触发态轻 sticky**（[v3.1](./2026-08-12-skill-sticky-process-chain-design.md)）：粘的是 **triggered** `skillIds`（非可发现全集）→ `biz_scene` 更稳；换**触发** skill 导致 **biz_scene** 变属**允许的一次 prefix 重建**（C3）。可发现目录变化不视为业务域切换。  
5. **Budget**：超限时仍 L3→Far→Mid；**Policy 与活跃任务权威字段不因 Budget 静默丢弃**（可截任务目录，不可丢 Policy 红线）。

**分期关系**：压缩点机制一期优先 task（五层 v17）；本层一期优先 **企业 chat**。chat 二期若上压缩点，直接复用上表挂载位，不必重做业务权威模型。

---

## 3. 与现有载体边界

| 载体 | 作用域 | 与本层关系 |
|------|--------|------------|
| ReAct Todo / AS TaskList | 单次 run / 会话内待办 | **非**业务任务板；不跨会话权威 |
| Planner H1 PlanNotebook | Planner 计划态 | 执行计划 SSOT；不存工单状态 |
| task-scene T0 / W0 / P0 | `kind=task` 编码 | 编码续跑；**一期不强制**挂业务任务板 |
| L2 `user_context_state` | 用户级记忆 | 可演进为偏好存储，但装载必须经 `biz_scene` 白名单；task 仍遵守「不读用户 L2」闸门 |
| L1 Near/Mid/Far | 会话窗口 | 本层之后叠加 |
| L3 向量 | 语义摘要 | 最低优先级；标注可能过期 |

**启用面（一期）**：

- 主路径：`kind=chat` 且 `business-context.enabled=true`（接企业工具 / 工单类对话）  
- `kind=task`：默认跳过本层（走 task-scene）；可选只读 Policy，不写业务任务板  
- `executionMode=workflow`：Policy/任务态可由工作流节点与业务库提供；本层**可选只读**，不强绑压缩点增强  

---

## 4. 数据模型（MySQL · SSOT）

> DDL 落入 `docker/mysql/init/11-sunshine-orchestrator.sql`（或同库增量段）；**禁止 Flyway**。库：`sunshine_chat`。

### 4.1 任务板 `business_task`（拍板 A：平台自建）

跨会话流程权威态；外部 OA/工单仅挂指针。

| 列 | 说明 |
|----|------|
| `task_id` | PK |
| `tenant_id` / `user_id` | 租户与属主 |
| `biz_scene` | 业务场景 |
| `status` | `pending` \| `running` \| `awaiting_confirm` \| `done` \| `archived` \| `failed` |
| `title` | 短标题 |
| `steps_json` | 当前步骤骨架（结构化，非散文全文） |
| `pending_confirmations_json` | 待确认项 |
| `retry_count` | 重试次数 |
| `deadline` | 截止时间，可空 |
| `risk_level` | `low` \| `medium` \| `high` |
| `external_ticket_ref` | 外系统工单/审批单号，可空 |
| `evidence_refs_json` | 证据指针列表（OSS key / messageId / ticket），**不含原文** |
| `conversation_id` | 最近关联会话，可空 |
| `created_at` / `updated_at` | 时间 |

索引建议：`(tenant_id, user_id, status, updated_at)`；`(tenant_id, user_id, biz_scene, status, updated_at)`；`(tenant_id, external_ticket_ref)`。

**召回原则**：有 `biz_scene` 时才走本阶梯；无 scene 则整块跳过（改走 L3 语义，见 §2.1）。裸「按时间 Top-K 灌详情」不合理——Top-K 只作目录/候选盖帽。

**召回阶梯（有 `biz_scene` 时 · 无用户选任务）**：

```
1) 候选池（SQL）
   tenant_id + user_id
   + status IN (pending, running, awaiting_confirm)
   + updated_at >= now() - N days
   + biz_scene = 当前 biz_scene

2) 锚定（可选，仍无用户介入）
   ① 请求已带 task_id / ticket_ref（工具/系统传入，非问卷）→ 只装该条详情
   ② conversation_id = 当前会话已绑定活跃任务 → 装该条详情
   ③ 否则：同 scene 活跃任务按 updated_at DESC
        - 0 条 → 不注入任务板
        - ≥1 条 → 只装最近 1 条详情（detail-max=1）
        - 可选：另附极简目录（≤top-k 行：id+title+status），供模型自行点查工具，不弹 HITL

3) 封顶：详情同时 ≤1；禁止多条全详情
```

`done` / `archived` 默认不进 Prompt。  
**禁止**用向量相似度决定焦点任务；无 `biz_scene` 时也**禁止**用跨 scene 任务 Top-K 硬灌。

### 4.2 场景 Policy `biz_scene_policy`

| 列 | 说明 |
|----|------|
| `policy_id` | PK |
| `tenant_id` | 租户；可支持 `*` 平台默认 |
| `biz_scene` | 精确匹配键 |
| `version` | 单调版本 |
| `status` | `active` \| `retired` |
| `rules_json` | 阈值、权限、必填字段、风控红线、HITL 开关、工具约束提示等 |
| `effective_from` / `effective_to` | 生效窗 |
| `updated_at` | |

装载：`tenant` 精确优先，否则回落平台默认；**仅一条**当前 `active` 且在生效窗内的最高 `version`。  
**禁止**向量召回 Policy；**禁止**模型写 Policy（仅运营/管理 API）。

### 4.3 偏好：表策略

**推荐（一期）**：扩展现有 `user_context_state`，避免双份用户事实库。

新增列（或等价 JSON 元数据）：

| 列/字段 | 说明 |
|---------|------|
| `biz_scene_scope` | `*` = 全局候选；或具体 `biz_scene` |
| `confirm_status` | `confirmed` \| `inferred`（仅 confirmed 默认可装载） |

装载算法：

1. 解析当前 `biz_scene`  
2. 取 Nacos **该 scene 的 preference key 白名单**（+ 全局白名单：如 `locale.language`、`locale.timezone`）  
3. 过滤：`confirm_status=confirmed` ∧ 未过期 ∧ (`scope=*` ∈ 全局白名单 ∨ `scope=biz_scene`) ∧ key∈白名单  
4. 条数仍超预算则按白名单优先级截断  

**反模式**：`SELECT * WHERE status=active` 全量注入。

若扩展 L2 成本高，可二期独立 `user_preference` 表，同一装载契约，迁移后删双写。

### 4.4 证据与审计

- 大原文 / 工具大返回 → 对象存储；Prompt 与 `evidence_refs_json` 只存指针  
- 装载审计（可落现有 audit 或薄表）：`biz_scene`、目录 `task_id[]`、详情焦点 `focus_task_id`、`policy_id+version`、偏好 key 列表、与 L3 冲突标记、assemble hash  

---

## 5. 装配与优先级

### 5.1 最终上下文组成

```
最终 Prompt 相关块 =
  ① System 稳定前缀（Catalog / 产品 scene overlay / P0 等既有）
+ ② biz_scene_policy          ← 最高业务权威
+ ③ business_task（目录或单条详情，见 §4.1 阶梯）
+ ④ scene-filtered prefs      ← 白名单偏好
+ ⑤ L2 残余（仅 chat、且不得绕过白名单；逐步收敛到 ④）
+ ⑥ L1 Far / Mid / Near
+ ⑦ L3 向量摘要（最低；文案「历史材料·可能过期」）
+ ⑧ 当前 user 消息
```

**硬优先级**：`policy > task board > prefs > L1 > L3`。  
低优先级**不得覆盖**高优先级权威字段；向量摘要不可替代任务状态或 Policy 阈值。

### 5.2 冲突仲裁

| 冲突 | 处置 |
|------|------|
| L3/Far 断言与 Policy 阈值/红线矛盾 | **不注入**该条摘要；记 audit；高风险可触发 HITL / `request_decision` |
| L3 与活跃 `business_task` 状态字段矛盾 | 以任务板为准；摘要丢弃或标风险 |
| 偏好与 Policy 矛盾 | 以 Policy 为准；偏好不注入冲突项 |
| 多条活跃任务（同 scene） | 只装最近 1 条详情；不弹选任务 HITL；可选附极简目录 |

**不做**：装载时对全部闲聊叙事做 LLM 全量对撞（成本与误杀高）。冲突检测针对**结构化字段 / 可解析断言**。

### 5.3 组件与读路径

```
ChatStreamContextFactory / 编排入口
  → [P0/P1] 历史·脱敏·L1·（可选全局 L2）·guide
  → 意图收集 skillIds / agentIds（[P2] agent∥skill 召回）
  → §2.1 解析 biz_scene（可空）
  → [P3] 有 scene：Policy ∥ 任务板 ∥ 场景偏好；无则 skip
  → [与 P3 可并行] L3 语义（方案 A，§2.2）
  → [P4] Toolkit ∥ Prompt 静态层 → 合并 → AgentRuntime
```

新建（建议包名）：

- `orchestrator/.../context/biz/BusinessContextAssembler`  
- `BusinessTaskRepository` / `BizScenePolicyRepository`  
- **不建**独立场景 LLM 分类器；scene 只读资源元数据  
- 改造点：拆分「路由前底座 assemble」与「路由后 P3+L3」（§2.2）；禁止路由前预装 Policy/任务板

`AssembleRequest` 增补：`kind`、`workspaceId`、`executionMode`、`biz_scene`（可空）。

### 5.4 写路径 / 回写

| 事件 | 写入 |
|------|------|
| 工具推进步骤 / 状态变更 | `business_task`（服务 API 或 `@SunshineTool` 受控写，禁止模型直接 SQL） |
| HITL / `request_decision` 确认 | 更新 `pending_confirmations` / `status` |
| 用户显式「记住」或确认偏好卡 | 偏好 `confirmed` |
| 推断偏好 | 仅 `inferred`，默认不装载 |
| 运营改规则 | Policy 新 `version`，旧版 `retired` |
| 轮次结束 | 既有 `ContextWritePath`（L1/L2/L3）；不把 Policy 写入 L3 |

---

## 6. 执行流程（端到端）

1. 用户输入 → [P0/P1] 底座 → 资源召回 → §2.1 取 `biz_scene`  
2. **有 scene**：[P3] Policy ∥ 任务 ∥ 偏好；**无 scene**：跳过  
3. L3 语义（§2.2 方案 A，可与 P3 并行）  
4. [P4] Prompt+Toolkit → Agent 执行  
5. 工具确认类 HITL 仍走既有 `require_confirmation`（与选场景无关）  
6. 回写任务板时带上当前 `biz_scene`；写装载审计  

---

## 7. 配置（Nacos 草案）

```yaml
agent:
  business-context:
    enabled: false          # 一期默认关；企业 chat 打开
    task:
      active-days: 14
      top-k: 5              # 可选极简目录宽度
      detail-max: 1         # 同时详情条数
      # 无独立 scene 分类器；无选场景 / 选任务 HITL
    preference:
      global-keys:
        - locale.language
        - locale.timezone
      # scene → 允许的 pref key 列表
      whitelist:
        refund:
          - refund.notify_channel
          - refund.default_reason_style
        contract_approve:
          - approve.notify_channel
    conflict:
      drop-l3-on-policy-clash: true
      hitl-on-high-risk-clash: true
```

Policy 与任务数据在 DB；白名单在 Nacos（改完 `sync_nacos.py` + 重启 orchestrator）。

---

## 8. 分期与验收

| 阶段 | 内容 | 验收要点 |
|------|------|----------|
| **M0** | 拆分装配时序（§2.2）：路由前仅底座；路由后 P3+L3；落地 P1/P3/P4 并行 | 未知 skill 时零 Policy/任务板；L3 不早于资源召回（方案 A） |
| **M1** | Skill/Agent 增 `biz_scene` + Policy DDL + 有 scene 才注入 | 无资源 scene → 零 Policy/任务板；有则精确命中；不弹选场景 |
| **M2** | 偏好白名单装载 | 仅随 scene 过滤；无 scene 不灌场景偏好 |
| **M3** | `business_task` + 同 scene 最近 1 条详情 | 无用户选任务；无 scene 不灌任务板 |
| **M4** | 有 scene 时 L3 vs Policy/任务冲突过滤 + 审计 | 无 scene 时仅 L3 语义路径 |
| **并行** | task-scene 读写闸门、L2 语义 merge、Budget 退役并入 | 见五层 §13.3 / task-scene P1–P2；**不阻塞**本层 M0/M1 |

Live 建议：`scripts/verify_business_context_live.py`（M1 起可测 Policy 注入；M3 补任务板）。

---

## 9. 反模式（验收红线）

1. 全量历史/全量偏好灌进 Prompt  
2. 业务阈值/权限只靠向量相似度  
3. 记忆无过期、无确认态、无白名单  
4. 把 L3 摘要当工单真相  
5. 将本层与 Todo/H1/T0 混为一个「任务板」概念  
6. 仅按 `updated_at` Top-K 灌多条任务详情（无 scene 过滤）  
7. 用向量决定「当前焦点任务」或用 AI **发明** `biz_scene`  
8. 独立场景分类器 / 选场景·选任务 HITL（本层明确不做）  
9. 在资源召回前空想 scene 并装载 Policy  
10. 路由前一次性 assemble 含 L3+业务块，导致无法按 skill 字段装结构化记忆  

---

## 10. 文档关系

| 文档 | 关系 |
|------|------|
| 五层压缩 | 压缩与会话记忆 SSOT；本层为**业务权威前缀**，优先级写入其装配序旁注 |
| task-scene | chat/task 记忆隔离；本层一期主挂 chat；task 不强制 |
| routing v6 | 产品 `scene` / `executionMode`；本层新增 `biz_scene` 第三业务轴 |
| request_decision | 仅业务工具确认等既有 HITL；**不**用于选场景/选任务板焦点 |
| Skill / Agent Catalog | `biz_scene` 元数据 SSOT 入口；与路由召回同链路 |

---

## 11. 决议记录

| # | 决议 | 日期 |
|---|------|------|
| D1 | 方案 C：薄权威层挂载五层，不新建微服务 | 2026-08-13 |
| D2 | 任务板 SSOT = 平台自建 `business_task` + `external_ticket_ref`（**A**） | 2026-08-13 |
| D3 | 业务场景字段名 = `biz_scene`（不用 scene_code 进路由字段） | 2026-08-13 |
| D4 | 偏好一期优先扩展 L2 列 + Nacos 白名单，可迁独立表 | 2026-08-13 |
| D5 | 装配序 policy > task > prefs > L1 > L3 | 2026-08-13 |
| D6 | 有 scene 时任务：同 scene + 最近 1 条详情；Top-K 仅可选目录 | 2026-08-13 |
| D7 | `biz_scene` 由 Skill/Agent 元数据带出；无则跳过结构化层 → L1+L3 语义；无用户选场景、无独立分类器 | 2026-08-13 |
| D8 | 目标时序：先意图收 Skill/Agent，再结构化记忆；L3 方案 A（路由后，可与 P3 并行）；并行组 P0–P4 见 §2.2 | 2026-08-13 |
| D9 | 本层一期 = chat；与压缩点正交；挂载遵守 prefix/Tier/L3 尾部纪律（§2.3）；task 默认不启用 | 2026-08-13 |
