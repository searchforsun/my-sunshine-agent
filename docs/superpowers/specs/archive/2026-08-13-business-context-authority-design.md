# 业务上下文权威层（Business Context Authority）

> **日期**：2026-08-13  
> **v2（2026-08-15）**：对齐 [task-list-memory](./2026-08-14-task-list-memory-unification-design.md) 与 [task-scene v14](./2026-08-01-task-scene-context-design.md)——会话级执行态 / KV Memory `todo` 与 `business_task` **边界隔离**；装配时序 P3′ 补位；`user_context_state` 表演进随 KV Memory 统一（§2.2 / §2.3 / §3 / §4.3 / §5.1 / §10 / §11）  
> **v3（2026-08-18）**：**biz_scene 解析补 embedding 回退路径**（§2.1b/§2.2/§4.4/§5.5）——当资源召回未命中时，用 query embedding 检索 `biz_scene_definition` 码表（零 LLM 延迟），使场景偏好/任务板在无 skill/agent 召回时仍可达；写路径同步回退。  
> **v4（2026-08-18）**：**场景来源双轨**（§2.1c/§4.4/§5.5/§9）——`biz_scene_definition` 增 `source` 列（`manual` 预定义 / `auto` 大模型自动发现）；`auto` 场景初始 `pending_review`，**不可**用于 Policy/任务板装载，仅嵌入检索过渡使用；运营审核后升 `active` 方可正式启用。前端 Lab 拆双 Tab：预定义 / 自动发现。**防污染机制**：`auto` 场景 TTL 自动清理 + 同 tenant 上限 + 相似度去重。  
> **状态**：**→ 已归档（2026-08-29）** · **M1–M3 ✅ 已实现**（2026-08-26，读侧装载：Policy 缓存装载 + business_task 召回阶梯 + 偏好白名单；`ReactExecutor` 资源召回后注入；Live `verify_business_context_live.py` A–D）· **M4 ✅ 已实现**（2026-08-26，冲突仲裁：`BizContextConflictArbiter` 有 scene ∧ 有 Policy/任务板权威参照 ∧ L3 非空时 LLM 判定并过滤矛盾断言 + 审计，ReactExecutor 注入点接入；Catalog `context.biz-scene.conflict-check`；Nacos `agent.business-context.conflict-check.enabled` 默认关；单测 9 例 + 全量 1355/1355 全绿；Live 多轮积累 L3 → 过滤 1 段冲突摘要 l3=111->0 + 审计送达）· **M5 ✅ 已实现**（2026-08-26，embedding 回退 + 场景双轨：`biz_scene_definition` 加向量/来源/审核列；`SceneEmbeddingService`（DashScope 向量化 + 余弦匹配 + 索引缓存 + 懒回填）；读路径 `ReactExecutor.resolveBizScene` 未命中 → embedding 回退；写路径 `SceneWriteResolver` 路由种子 → embedding → LLM 自动创建三级链；`SceneAutoCreateService` 防污染（≥2 轮/max-pending/rate-limit/相似度抑制）；`pending_review` 仅嵌入检索不装载 Policy/任务板；前端双 Tab + 审核；Live `verify_scene_dual_track_live.py` A–E 全绿）· **M0 ✅ 已实现**（2026-08-26，装配时序拆分 §2.2 方案 A：`AssembleRequest.deferL3` fast×chat 路由前仅底座（`assemble l3=0`），L3 延后由 `ReactExecutor` 资源召回后 `ContextAssembler.attachL3` 装配（分区锚点 `AssembledContext.L3Anchor` 排除 Near/Mid 已覆盖消息 + 剩余预算裁剪，先于 M4 仲裁）；pro/workflow 保持现状；单测 7 例 + 全量 1386/1386 全绿；Live fast×chat 验证路由前零 L3 召回 + attachL3 调用 + Near 覆盖排除）  
> **定位**：企业生产 Agent 的**结构化权威底座**——任务板 / 场景偏好白名单 / 场景 Policy；挂载于既有五层读路径之上，**不**替代 L1–L5 压缩管道，**不**新建 context 微服务。  
> **关联**：[unified-context-compression](../2026-07-31-unified-context-compression-design.md)（五层 SSOT）· [task-scene-context](./2026-08-01-task-scene-context-design.md)（chat/task 记忆闸门 · v14 KV Memory 统一）· [task-list-memory](./2026-08-14-task-list-memory-unification-design.md)（会话级执行态 + KV Memory `todo`，边界隔离）· [unified-routing v6](../2026-07-29-unified-routing-design.md)（`kind`/`executionMode`/`callSite`/`biz_scene` 四轴）· [kind-biz-scene-catalog](./2026-08-13-kind-biz-scene-catalog-design.md)（业务场景 Lab SSOT · 资源 `kind` · 工具集 chat/task · 退役 react-prompt）

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

`biz_scene` 取值必须落在 **业务场景 Lab** 闭集码表（与 `biz_scene_policy.biz_scene` 同码空间；见 [kind-biz-scene-catalog](./2026-08-13-kind-biz-scene-catalog-design.md) §3）；空 = 该资源不触发结构化业务记忆。码表**不**挂在「提示词 / react-prompt」下。

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

**不做**：用户选场景 UI、低置信追问、独立 `BizSceneResolver` LLM 分类、AI 动态建 scene、用 `reactPromptId` 充当业务域。  
**扩场景**：运营在 **业务场景 Lab** 建码 → 配 Policy → Skill/Agent 打标；无需改分类 prompt。原 `react-prompt.*` 场景退役，文案迁 Skill overlay（见 kind-biz-scene-catalog §5）。

**装配时序**：结构化业务块必须在**资源召回之后**组装（或召回后补注入）；禁止在未知 skill/agent 时空想 `biz_scene`。细则见 §2.2。

### 2.1b `biz_scene` embedding 回退路径（v3 · 资源召回未命中时）

> **动机**：§2.1 的解析算法完全依赖 skill/agent 召回。无召回 → `biz_scene = null` → 整层跳过 → 即使该用户在该场景下有大量历史偏好和任务板，也全部不可达。典型场景：用户输入"帮我查下上次那笔退款"——没召回任何 skill，但"退款"场景的偏好和任务板应该加载。

**原则**：

1. **不做 LLM 分类器**（不违反 D7）：仅 embedding 匹配码表，零 LLM 延迟，与 L2 同层。
2. **资源召回优先**：skill/agent 带出的 `biz_scene` 精确度最高，优先级始终高于 embedding 回退。
3. **阈值保守**：`minScore` 默认高（0.7），宁可漏场景也不误灌。

**解析算法（v3 扩展）**：

```
1. IntentRouter / 资源召回得到 skillIds[]、agentIds[]（既有链路）
2. 取 biz_scene：
   a) 若命中 agent 且 agent.biz_scene 非空 → 用该值（多 agent 取排序第一非空）
   b) 否则扫 skillIds：第一个非空 skill.biz_scene
   c) 均无 → embedding 回退：
      · 输入：用户 query → embedding（DashScope text-embedding-v4）
      · 检索：biz_scene_definition 码表（仅 `status=active`，`tenant_id = 当前租户 OR *`）
      · 匹配：query embedding 与每条 scene 的 `description_vector` 做余弦相似度
      · 若最高分 ≥ minScore（默认 0.7）→ 采纳该 scene；否则 biz_scene = null
3. 分支：
   - biz_scene != null → 装载结构化权威层
   - biz_scene == null → 跳过
```

**写路径同步回退**（§5.5）：

```
assistant 完成 → 从本轮 RoutingResult 取 biz_scene（优先）
  → 若为空 → 从 assistant 终态正文 + user query 做 embedding 召回场景
  → 有 scene → 偏好抽取时带上 biz_scene_scope
  → 无 scene → 偏好落 scope=*（全局）或仅取通用 key
```

**与 D7 的兼容性**：

| D7 约束 | v3 回退 |
|----------|---------|
| 不做独立场景分类器 | ✅ embedding 匹配，非 LLM 分类 |
| 不做选场景 HITL | ✅ 自动匹配，无用户介入 |
| 不让模型自由生成 scene 码 | ✅ 码表闭集，仅匹配已有 active 码 |
| 场景随 Catalog 资源召回附带 | ✅ 资源召回仍为优先路径，embedding 仅回退 |

**码表向量化**（§4.4）：`biz_scene_definition` 新增 `description_vector` 列（1024 维 FLOAT 数组），运营维护 `description` 时异步更新向量。`description` 字段应写成**可检索的语义说明**（如"退款场景：用户咨询退款进度、退款原因、退款金额相关"），而非仅展示文案。

### 2.1c 场景来源双轨：预定义 + 自动发现（v4）

> **动机**：v3 的 embedding 回退解决了"无召回时场景不可达"的问题，但依赖于码表中已有对应场景。若企业未预定义某场景，该场景下的偏好和任务板仍无法归位。**必须给大模型留一个自动创建场景的出口**，同时防止滥用导致场景污染。

**双轨模型**：

```
场景来源
  ├── 预定义（source=manual）    运营/管理员在 Lab 手动创建
  │   · status 直接为 active
  │   · 可挂 Policy、可参与任务板装载
  │   · 可绑定到 Skill/Agent
  │
  └── 自动发现（source=auto）    大模型在写路径自动创建
      · status 初始为 pending_review
      · 不可挂 Policy、不可参与任务板装载
      · 仅 embedding 检索可用（过渡期）
      · 运营审核后升 active → 同预定义
```

**状态流转**：

```
auto 场景创建 → pending_review（仅嵌入检索可用）
  → 运营审核通过 → active（正式启用，可挂 Policy/任务板）
  → 运营驳回 → rejected（软删除，向量移除）
  → 30 天无人审核/无使用 → 自动清理（TTL）
```

**解析算法（v4 扩展，在 v3 基础上增加步骤 d）**：

```
1. IntentRouter / 资源召回得到 skillIds[]、agentIds[]
2. 取 biz_scene：
   a) 若命中 agent 且 agent.biz_scene 非空 → 用该值
   b) 否则扫 skillIds：第一个非空 skill.biz_scene
   c) 均无 → embedding 回退（检索所有 status=active 的场景，含 manual 与已审核 auto）
   d) 均无 → 写路径创建（见 §5.5b）：
      · 若读路径仍为空 → biz_scene = null（跳过结构化层）
      · 读路径不创建场景，仅写路径创建
3. 分支：
   - biz_scene != null → 装载结构化权威层
   - biz_scene == null → 跳过
```

**关键约束**：

| 约束 | 说明 |
|------|------|
| **读路径不创建** | 仅写路径（assistant 完成后）创建 `auto` 场景；读路径不行——读路径创建场景会导致 prefix 不稳定 |
| **pending_review 仅嵌入检索** | `auto` 场景在 `pending_review` 状态时，embedding 检索可命中，但**不可**用于 Policy 装载、任务板装载、Skill/Agent 绑定 |
| **审核升 active 即正式** | 运营审核通过后，`source` 仍为 `auto`，但 `status=active`，与 `manual` 场景完全等同 |
| **防污染机制** | 见下方 |

**防污染机制（硬约束）**：

```
① 同 tenant 上限：auto 场景总数 ≤ N（默认 20），超限 → 暂停创建 + 前端告警
② 相似度去重：新建 auto 场景前，与现有 active/pending 场景 description 做 cosine 相似度
   - 若相似度 > 0.85 → 复用已有场景，不重复创建
   - 若相似度 ≤ 0.85 → 允许创建
③ TTL 自动清理：pending_review 场景 30 天无人审核 → 自动软删除（status=auto_cleaned）
④ 创建频率限制：同一 tenant 10 分钟内最多创建 M 个 auto 场景（默认 3）
⑤ 名称规范：auto 场景 `biz_scene` 码由 LLM 生成，强制 `[a-z][a-z0-9_-]{2,48}` 正则
```

**前端 Lab 双 Tab（§9）**：

| Tab | 内容 | 操作 |
|-----|------|------|
| **预定义** | `source=manual` 的场景 | 新建/编辑/禁用/挂 Policy |
| **自动发现** | `source=auto` 的场景（含 pending_review / active / rejected） | 审核（通过/驳回）/ 手动删除 / 查看来源会话 |

> **落地注记（2026-08-26）**：§2.1c 状态机、解析算法 d 步骤（写路径创建）、防污染 ①–⑤ 均由 `BizSceneAdminService`（状态流转 + 审核落库）、`SceneAutoCreateService`（≥2 轮 / max-pending / rate-limit / 相似度 ≥0.85 / 码正则）、前端双 Tab 实现；pending_review 仅嵌入检索的约束由 `SceneEmbeddingService.searchVector`（active + pending_review）与 `BusinessContextAssembler`（仅 active 装载）双端保证。

### 2.2 装配时序与并行（目标态）

> **现状缺口**：`ChatStreamContextFactory` 在路由**之前**同步跑完 `ContextAssembler`（L1+L2+L3+guide），预召回几乎全串行。本层要求 **先 Skill/Agent，再结构化业务记忆**，必须改时序；并行组用于降延迟，不改变依赖边。

**命名勿混**：意图链 L0–L3（收资源）≠ 记忆 L1–L5（装上下文）≠ 产品 `kind` ≠ `biz_scene` ≠ `callSite`。

**目标顺序**（含 [task-list-memory](./2026-08-14-task-list-memory-unification-design.md) §8 补位 · v3 embedding 回退）：

```
① 轻量会话底座
② 意图收集 → skillIds / agentIds（L0→规则→L2 embedding→必要时 Intent LLM）
③ 从命中资源读 biz_scene（§2.1）
   ③b 若资源未命中 → embedding 回退检索 biz_scene（§2.1b，与 ② 的 L2 可复用同一 embedding 管道）
④ 会话级任务清单 + KV Memory `todo`（task-list-memory §8，与 ⑤ 可并行；不依赖 scene）
   fast → `react_task_board` 最近快照 →【任务清单】块（Tier 2 尾部）
   pro  → H1 renderForPlanner（既有）
   KV   → `kind=todo`：task→`scope=workspace` / chat→`scope=user`（Tier 1，L2 块内）
⑤ 有 scene → Policy + 业务任务板 + 场景偏好（SQL）
   无 scene → 跳过 ⑤
⑥ L3 语义：近文 + 用户提问（无 scene 时的主长期补充；有 scene 时低优先级；可与 ④⑤ 并行）
⑦ PromptComposer + Toolkit → 主 LLM
   （知识库 RAG / 大工具结果：工具按需，不预召回）
```

**并行组（可多路）**：

| 组 | 内容 | 约束 |
|----|------|------|
| **P0** | `loadHistory` ∥ 脱敏（若契约允许） | 均需先于主装配 |
| **P1** | `L2`（全局极少项）∥ `projectGuide` ∥（L1 partition 完成后）可预热；**L3 建议延后到 ⑥** | 不依赖 biz_scene |
| **P2** | 轨 A：`agent` embedding ∥ `skill` embedding | L0/规则仍短路串行 |
| **P2b** | **③b embedding 场景回退**：query embedding 检索 `biz_scene_definition` 码表 | 可与 P2 复用同一 embedding 管道（同 embedding 模型），仅在 ③ 资源未命中时触发 |
| **P3′** | 会话级任务清单 ∥ KV Memory `todo`（[task-list-memory](./2026-08-14-task-list-memory-unification-design.md) §8 ③④） | **在 ② 之后即可**；不依赖 `biz_scene` |
| **P3** | 有 `biz_scene` 时：`Policy` ∥ `业务任务板` ∥ `场景偏好` | **必须在 ②③ 之后** |
| **P4** | `Toolkit` ∥ Prompt 静态层（skill overlay HTTP 除外） | 主 LLM 前合并 |

**必须串行**：

- L0 → 规则 →（需要时）Intent LLM  
- **②③ → ⑤**（先资源后结构化记忆）  
- Budget / 最终 messages 合并  
- 主 LLM  

**L3 时机（落地选一，推荐 A）**：

| 方案 | 做法 | 取舍 |
|------|------|------|
| **A（推荐）** | 意图完成后再跑 L3 | 逻辑干净；无 scene / 有 scene 同一插入点；多一次相对路由的等待可与 P3/P3′ 并行（L3∥P3′∥P3） |
| B | L3 与意图链部分重叠 | 省墙钟时间；无 scene 时白打或需取消 |

**与现状对照**：把「assemble 整包」拆成「底座 P1」+「路由后 P3+L3」；禁止继续在未知 skill/agent 时预装 Policy/任务板。

> **M0 落地注记（2026-08-26，方案 A）**：`AssembleRequest` 加 `deferL3`（fast×chat 生效），`ContextAssembler.assemble` 路由前仅装配底座（L2+L1+guide，日志 `l3=0`）并挂载 `AssembledContext.L3Anchor`（Near/Mid 排除 ID + Far ID + farSummary 标记）；`ReactExecutor` 在 `resolveBizScene`（P3 业务块注入点）之后调用 `ContextAssembler.attachL3` 按锚点召回 L3——与业务块同一注入点、先于 M4 冲突仲裁；L3 超剩余预算丢弃（保持「L3 最先让位」预算语义）；pro/workflow 保持 `deferL3=false` 现状（无 P3 可并行，装配点不动）。Live 验收：fast×chat 路由前 `assemble ... l3=0`、路由后 `attachL3` 执行（rag-service 收到 L3 检索请求）、Near 已覆盖消息按锚点排除不重复注入。

### 2.3 与压缩点模式 / chat·task 启用面（正交）

| 问题 | 结论 |
|------|------|
| 是不是 task 要的？ | **否。一期主路径 = `kind=chat`**。`kind=task` 走 [task-scene](./2026-08-01-task-scene-context-design.md)（P0 / KV Memory·H1、压缩点优先 task×fast\|pro），**默认跳过本层** |
| 是否等于压缩点模式？ | **否。正交增强**：不改 `far_folded_msg_ids`、不替代 Near/Mid/Far 重组、不抢 task 压缩点一期启用面 |
| 是否符合压缩点前置约束？ | **原则符合**，须遵守下列挂载纪律（对齐五层 §5.5 Tier / prefix C1–C3） |

**压缩点兼容纪律（chat 落地时）**：

1. **Policy / 场景偏好**：视为低频结构化块（类 Tier 0/1），**不进** L1 Near/Mid/Far 折叠；渲染顺序固定，禁止每轮重排中段。  
2. **任务板详情**：随工具回写会变 → 按 **content-hash** 仅在变更时改块（对齐压缩点降频）；或置于 query 前动态尾段，避免无意义打穿整段 KV。  
3. **L3**：保持「绝对尾部动态段」语义（五层 §7.5）；§2.2 方案 A（路由后再召回）与此一致，优于路由前灌 L3。  
4. **Skill 触发态轻 sticky**（[v3.1](../2026-08-12-skill-sticky-process-chain-design.md)）：粘的是 **triggered** `skillIds`（非可发现全集）→ `biz_scene` 更稳；换**触发** skill 导致 **biz_scene** 变属**允许的一次 prefix 重建**（C3）。可发现目录变化不视为业务域切换。  
5. **Budget**：超限时仍 L3→Far→Mid；**Policy 与活跃任务权威字段不因 Budget 静默丢弃**（可截任务目录，不可丢 Policy 红线）。  
6. **KV `todo` / 会话级恢复块**（[task-list-memory](./2026-08-14-task-list-memory-unification-design.md)）：归属其挂载纪律——KV `todo` Tier 1 幂等、恢复块 Tier 2 尾部；与本层任务板**不混挂、不双写**。

**分期关系**：压缩点机制一期优先 task（五层 v17）；本层一期优先 **企业 chat**。chat 二期若上压缩点，直接复用上表挂载位，不必重做业务权威模型。

---

## 3. 与现有载体边界

| 载体 | 作用域 | 与本层关系 |
|------|--------|------------|
| fast 会话级任务清单（`AgentState.tasksContext` + `react_task_board` 快照） | 会话内执行态 · 跨轮恢复 | agent 执行态，**非**业务任务板；不跨会话（[task-list-memory](./2026-08-14-task-list-memory-unification-design.md) §4/§5.1） |
| Planner H1 PlanNotebook | pro 计划态（唯一 SSOT） | 不存工单状态；终态未完成项导出 KV Memory `kind=todo`，不回写 H1（[task-list-memory](./2026-08-14-task-list-memory-unification-design.md) §5.2） |
| KV Memory `kind=todo`（原 L2/W0 统一 `user_context_state` + `scope` 列，task-scene v14） | 跨会话记忆层 | agent 执行态**沉淀副本**；与 `business_task` 不合并、不双写 |
| task-scene P0 项目规范 | `kind=task` 工作区 | 编码续跑；一期不强制挂业务任务板 |
| KV Memory `scope=user`（原 L2） | 用户级记忆 | 可演进为偏好存储，但装载必须经 `biz_scene` 白名单；task 仍遵守「不读用户 L2」闸门 |
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

> **对齐 task-scene v14 / task-list-memory v2**：`user_context_state` 已统一演进为 KV Memory（`scope=user|workspace` 列 + `kind` 收敛含 `todo`）。本层偏好列与之一张表共存，DDL 以 [task-scene v14](./2026-08-01-task-scene-context-design.md) §5 为准；偏好与 `todo` 分属不同 `kind`，装载分别经 `biz_scene` 白名单与 `scope` 门禁。

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

### 4.4 场景码表向量化 `biz_scene_definition`（v3 新增 · v4 双轨扩展）

> **用途**：支撑 §2.1b 的 embedding 回退路径与 §2.1c 的场景双轨。`biz_scene_definition` 已存在（`19-sunshine-resource.sql`），本节补向量列、来源列、状态扩展。

**DDL（增量）**：

```sql
-- v3：向量列
ALTER TABLE biz_scene_definition
  ADD COLUMN description_vector JSON NULL
  COMMENT 'description 的 embedding 向量（1024 维 float[]，DashScope text-embedding-v4）';

-- v4：场景来源双轨
ALTER TABLE biz_scene_definition
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'manual'
  COMMENT 'manual=运营预定义 | auto=大模型自动发现',
  ADD COLUMN source_conversation_id VARCHAR(64) NULL
  COMMENT 'auto 场景的首次触发会话（溯源）',
  ADD COLUMN approved_by VARCHAR(64) NULL
  COMMENT '审核人（auto 场景升 active 时记录）',
  ADD COLUMN approved_at TIMESTAMP NULL
  COMMENT '审核时间',
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active'
  COMMENT 'active|disabled|pending_review|rejected|auto_cleaned（v4 扩展）';
```

**索引**：`(tenant_id, source, status)`；`(status, created_at)` 用于 TTL 清理。

**维护规则**：

| 时机 | 操作 |
|------|------|
| 运营新建/修改 manual 场景 | 异步触发 embedding → 更新 `description_vector`；status 直接 `active` |
| 写路径自动创建 auto 场景（§5.5b） | status = `pending_review`；`source_conversation_id` 记录首次触发会话 |
| 运营审核通过 | status → `active`；`approved_by` / `approved_at` 记录；此时可挂 Policy |
| 运营驳回 | status → `rejected`；Milvus 删除向量；45 天后物理删除 |
| 30 天 TTL 清理 | `status=pending_review AND created_at < NOW() - 30d` → `auto_cleaned` |
| 运营禁用手动场景 | status → `disabled`（不可再绑到新资源；已绑资源解析时跳过） |

**`description` 字段写法规约**（与展示文案不同，需可检索）：

| 场景码 | 展示用 `display_name` | 可检索 `description`（须含语义关键词） |
|--------|----------------------|--------------------------------------|
| `compliance-review` | 费用合规审查 | 报销合规对照场景：用户上传或查询报销单据、费用合规性检查、审批流程相关 |
| `expense-assist` | 报销助手 | 报销查询/提交辅助场景：用户咨询报销进度、提交报销申请、查看报销历史 |
| `policy-qa` | 制度问答 | 企业制度/流程知识问答场景：用户询问公司制度、考勤政策、财务流程、HR规定 |
| `travel-budget` | 差旅预算 | 差旅额度与预算管控场景：用户查询差旅标准、申请差旅预算、报销差旅费用 |

**auto 场景的 `description` 生成**（§5.5b）：由 LLM 从触发对话中提取，格式要求与 manual 一致——必须包含"用户可能使用的自然语言关键词 + 动词/名词组合"。`biz_scene` 码由 LLM 生成，格式 `[a-z][a-z0-9_-]{2,48}`（如 `refund-inquiry`）。

**检索参数**（Nacos `agent.business-context.scene-embedding`）：

```yaml
agent:
  business-context:
    scene-embedding:
      min-score: 0.7          # 余弦相似度阈值
      top-k: 1                # 仅取最高分（单场景）
      model: text-embedding-v4
      dimension: 1024
    scene-auto:
      max-pending: 20         # 同 tenant auto 场景上限
      create-rate-limit: 3    # 每 10 分钟最多创建 M 个
      ttl-days: 30            # pending_review 自动清理
      similarity-threshold: 0.85  # 去重阈值
```

### 4.5 证据与审计

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
+ ⑤ KV Memory `todo` + L2 残余（仅 chat/task 对应 scope、不得绕过白名单；todo 为 agent 执行态沉淀，低于 business_task）
+ ⑥ L1 Far / Mid / Near
+ ⑦ 会话级【任务清单】恢复块（fast `react_task_board` / pro H1；Tier 2 尾部 · query 前；agent 执行态，非 business_task）
+ ⑧ L3 向量摘要（最低；文案「历史材料·可能过期」）
+ ⑨ 当前 user 消息
```

**硬优先级**：`policy > business_task > prefs/KV-todo > L1 > 会话级【任务清单】恢复块 > L3`。  
低优先级**不得覆盖**高优先级权威字段；向量摘要不可替代任务状态或 Policy 阈值；`todo` / 恢复块是 agent 执行态，不可替代 `business_task` 业务权威态。

### 5.2 冲突仲裁

| 冲突 | 处置 |
|------|------|
| L3/Far 断言与 Policy 阈值/红线矛盾 | **不注入**该条摘要；记 audit；高风险可触发 HITL / `request_decision` |
| L3 与活跃 `business_task` 状态字段矛盾 | 以任务板为准；摘要丢弃或标风险 |
| 偏好与 Policy 矛盾 | 以 Policy 为准；偏好不注入冲突项 |
| 多条活跃任务（同 scene） | 只装最近 1 条详情；不弹选任务 HITL；可选附极简目录 |

**不做**：装载时对全部闲聊叙事做 LLM 全量对撞（成本与误杀高）。冲突检测针对**结构化字段 / 可解析断言**。

**M4 落地注记（2026-08-26）**：

- 实现 = `BizContextConflictArbiter`（`orchestrator/biz`）：闸门（`conflict-check.enabled` ∧ scene 非空 ∧ L3 块非空）→ 权威参照 = `BusinessContextAssembler.renderPolicyBlock/renderTaskBlock` 输出（与注入块同源）→ **无 Policy 且无活跃任务板即放行**（仲裁无参照无意义）→ LLM 判定（Catalog `context.biz-scene.conflict-check`，模板含 `=== USER ===` 分隔 system/user 两段，占位符 `{scene}/{policy}/{taskBoard}/{l3}`）。
- 过滤 = 对 L3 按空行分段，LLM 输出 `{"filter":[{"snippet":"原文片段","reason":"矛盾说明"}]}`，命中 snippet 的段落移除。
- **失败兜底**：LLM 异常/输出不可解析 → `llm-failure-policy=drop`（默认，整段 L3 丢弃——有权威块时低优先级安全）或 `keep`（原样）；均记审计。
- **审计**：`AuditPublisher` 事件 `eventType=BIZ_CONTEXT_CONFLICT`，payload 含 scene / hasPolicy / hasTaskBoard / filteredSnippets / 源·过滤长度；`filtered`（命中过滤）/ `llm-error` / `parse-error` / `catalog-missing` 状态。
- 接入点：`ReactExecutor.executeWithInjected`——`biz_scene` 解析提为局部变量后，`ctx.memory().l3MaterialBlock()` 非空时仲裁，结果经 `AssembledContext.withL3MaterialBlock` 替换后进 `AgentRunRequest.main`；L3 为空直接不调（无 L3 不仲裁）。
- Nacos `agent.business-context.conflict-check.*`（默认关，含 `max-l3-chars` 截断保护）。单测 9 例 + 全量 1355/1355 全绿；Live 验收见 §8 注记。

### 5.3 组件与读路径

```
ChatStreamContextFactory / 编排入口
  → [P0/P1] 历史·脱敏·L1·（可选全局 L2）·guide
  → 意图收集 skillIds / agentIds（[P2] agent∥skill 召回）
  → [P2b] 若资源未命中 → embedding 回退检索 biz_scene 码表（§2.1b）
  → §2.1 解析 biz_scene（资源优先，回退补位）
  → [P3′] 会话级任务清单 ∥ KV Memory `todo`（[task-list-memory](./2026-08-14-task-list-memory-unification-design.md) §8；不依赖 scene）
  → [P3] 有 scene：Policy ∥ 任务板 ∥ 场景偏好；无则 skip
  → [与 P3/P3′ 可并行] L3 语义（方案 A，§2.2）
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

### 5.5 写路径场景回退（v3 新增 · v4 双轨扩展）

> **动机**：写路径同样需要场景锚定，否则偏好抽取时不知道该打哪个 `biz_scene_scope`。与读路径对称，资源召回优先，未命中时 embedding 回退。v4 新增：回退均未命中时，**LLM 自动创建 `auto` 场景**（`pending_review` 状态），保留扩展出口。

```
ContextWritePath.runAsync 执行 KV Memory 偏好抽取前：
  ① 从本轮 RoutingResult 取 biz_scene（优先）
  ② 若为空 → embedding 回退：
     · 输入：assistant 终态正文（截断 500 chars）+ user query
     · 检索：biz_scene_definition 码表（status=active，同 §2.1b）
     · 若最高分 ≥ minScore → 采纳该 scene
  ③ 若仍为空 → LLM 场景创建（v4 新增 · §5.5b）：
     · 触发条件：本会话已 ≥ 2 轮对话（避免单轮闲聊创建）
     · 输入：本会话 user+assistant 摘要（最近 3 轮）
     · 动作：LLM 判断是否为新业务场景，若是则生成 biz_scene + display_name + description
     · 若创建 → 该 scene 作为本轮 biz_scene 使用
  ④ 有 scene → 偏好抽取 prompt 注入 `biz_scene_scope = {scene}`
     · 抽取结果中的偏好条目自动带 `biz_scene_scope`
  ⑤ 无 scene → 偏好落 `scope=*`（全局）或仅允许通用 key
```

### 5.5b LLM 自动场景创建（v4 新增）

> **原则**：仅写路径创建，读路径不创建。创建为 `pending_review` 状态，**不可**用于 Policy/任务板装载——仅嵌入检索可用，运营审核后才能正式启用。

**触发条件**（全部满足）：

1. 读路径 ② embedding 回退未命中（所有 active 场景 cosine < 0.7）
2. 本会话已 ≥ 2 轮对话（排除单轮闲聊）
3. 同 tenant 的 `auto` 场景总数 < `max-pending`（默认 20）
4. 10 分钟内同 tenant 创建次数 < `create-rate-limit`（默认 3）

**LLM 输入**（Catalog `context.biz-scene.auto-create`，**仅写路径调用，不阻塞读路径**）：

```
输入：本会话最近 3 轮 user+assistant 摘要
任务：判断是否形成了一个新的、区别于所有已有场景的业务场景
已有场景（仅 active）：{场景码表 JSON}
若确为新场景，生成：
  - biz_scene: [a-z][a-z0-9_-]{2,48}（小写英文码）
  - display_name: ≤16 字中文名
  - description: 50-200 字，包含该场景下用户可能使用的自然语言关键词
  - 若不确定，输出 skip
```

**创建后**：

- `status = pending_review`，`source = auto`
- 异步 embedding → 更新 `description_vector`
- 该场景立即进入 embedding 检索（后续读路径可命中）
- **不**装载 Policy / 任务板（pending_review 无此权限）
- 前端 Lab「自动发现」Tab 出现新条目，运营可审核

**与读路径差异**：

| 维度 | 读路径 | 写路径 |
|------|--------|--------|
| 输入 | 用户 query | assistant 终态正文 + user query |
| 触发时机 | 装配时（§2.2 ③b） | ContextWritePath 异步（assistant 完成后） |
| 误判代价 | 灌错场景偏好 → 本会话污染 | 偏好落错 scope → 下次读时可能漏/误 |
| 场景创建 | **不创建** | 可创建 auto 场景（§5.5b） |
| 优先级 | 资源召回 > embedding | 同上 |

> **落地注记（2026-08-26）**：三级链实现 = `SceneWriteResolver.resolve`（① 路由种子 → ② `SceneEmbeddingService.search` → ③ `SceneAutoCreateService.tryCreate`），由 `ContextWritePath.runAsync` 在 L2 偏好抽取前调用，结果经 `L2ExtractService.extract(..., bizSceneScope)` 注入。LLM 判定输入含既有 active 场景表（Catalog `context.biz-scene.auto-create`，`{scenes}` 占位），不确定性输出 `skip` 不创建。

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
| **M0** ✅ | 拆分装配时序（§2.2 方案 A）：路由前仅底座（`deferL3` + `L3Anchor`）；路由后 `attachL3` 与 P3 同一注入点 | 未知 skill 时零 Policy/任务板；L3 不早于资源召回 |
| **M1** ✅ | Skill/Agent 增 `biz_scene` + Policy 装载 + 有 scene 才注入 | 无资源 scene → 零 Policy/任务板；有则精确命中；不弹选场景 |
| **M2** ✅ | 偏好白名单装载 | 仅随 scene 过滤；无 scene 不灌场景偏好 |
| **M3** ✅ | `business_task` + 同 scene 最近 1 条详情 | 无用户选任务；无 scene 不灌任务板 |
| **M4** ✅ | 有 scene 时 L3 vs Policy/任务冲突过滤 + 审计 | 无 scene 时仅 L3 语义路径 |
| **M5（v3/v4）** ✅ | embedding 场景回退 + 场景双轨：`biz_scene_definition` 向量化 + 读/写路径回退逻辑 + LLM 自动创建场景 + 防污染机制 + 前端双 Tab | 资源未命中时 query 可召回场景；写路径偏好带对 `biz_scene_scope`；auto 场景 pending_review 不装载 Policy/任务板；前端可审核 |
| **并行** | task-scene 读写闸门、L2 语义 merge、Budget 退役并入 | 见五层 §13.3 / task-scene P1–P2；**不阻塞**本层 M0/M1 |

**M5 落地注记（2026-08-26）**：

- **数据模型**：`biz_scene_definition` 加 `description_vector`（JSON）/`source`（manual\|auto）/`source_conversation_id`/`approved_by`/`approved_at` + `idx_biz_scene_source_status`/`idx_biz_scene_status_created` 索引；`status` 扩展 `pending_review`/`rejected`/`auto_cleaned`。线上 DDL 见 `scripts/migrate_biz_scene_dual_track.sql`。
- **资源管理器**：`BizSceneAdminService` 扩 source·状态流转（auto 创建即 `pending_review` + 溯源会话；审核升 active 记审核人/时间）、`PUT /{code}/vector` 向量回填、`GET /embedding-index` 检索索引（active + pending_review，排除 auto_cleaned）；BFF 透传。
- **SceneEmbeddingService**（orchestrator/biz）：DashScope `text-embedding-v4` 向量化（与 rag-service 同厂商）、启动预热 + 5min 定时刷新索引、active 缺向量懒回填、cosine 匹配（min-score 0.7 / top-k 1）。**事件循环线程安全**：embed 的 HTTP 调用统一调度 `boundedElastic` 线程执行 + `Future.get` 等待——读路径 `ReactExecutor.resolveBizScene` 在 reactor-http 事件循环上同步执行，直接 `WebClient.block()` 会触发 Reactor NonBlocking 检查抛异常（写路径普通线程不受影响），回归单测 `embed_inReactorEventLoopThread_returnsVector` 固化。
- **读路径**：`ReactExecutor.resolveBizScene` 资源召回未命中 → `sceneEmbeddingService.search(query)` → 命中取 scene；`pending_review` 仅嵌入检索可用，Policy/任务板/偏好装载仍要求 `active`。
- **写路径**：`SceneWriteResolver` 三级链 = ① 路由种子（skill/agent 绑定）→ ② embedding 回退（user+assistant 摘要）→ ③ `SceneAutoCreateService` LLM 自动创建（Catalog `context.biz-scene.auto-create` + 既有 active 场景表注入；防污染：≥2 轮 / max-pending 20 / rate-limit 3 每 10 分钟 / 相似度 ≥0.85 抑制重复创建）；结果注入 `L2ExtractService.extract` 的 `biz_scene_scope`。
- **前端**：BizScenesView 双 Tab「预定义 / 自动发现」，auto 待审核卡片「通过并启用 / 拒绝」，`reviewBizScene` 记 operator。
- Nacos `agent.business-context.scene-embedding.*`/`scene-auto.*`（默认关）；单测 10 例（含事件循环线程回归）+ 全量 1379/1379 全绿；Live `verify_scene_dual_track_live.py` A（端点）/ B（懒回填 + 无召回 null）/ C（auto 创建 + 落库）/ B′（读/写路径 embedding 命中 pending_review）/ D（审核落库）/ E（清理还原）全绿。

**M4 落地注记（2026-08-26）**：Live 验收 = 开关开 + 种 Policy（重启预热缓存）+ 活跃任务板 → 同一会话 3 轮普通对话积累 L3（`semantic-batch-turns: 3`）→ 第 4 轮 `/expense-assist` 触发：日志 `[BizContext] loaded scene=expense-assist policy=true task=true` + `[BizConflict] 过滤 1 段冲突摘要 l3=111->0`（LLM 判定与任务板/Policy 直接矛盾的 L3 断言并过滤）+ `[Audit] 已发送` 审计送达；开关还原默认关。验收要点「无 scene 时仅 L3 语义路径」= 仲裁闸门要求 scene 非空 + 权威参照存在 + L3 非空三者同时满足，缺一即原样放行（无 LLM 调用）。

**M1–M3 落地注记（2026-08-26）**：

- 读侧装载 = `BusinessContextAssembler`（`orchestrator/biz`）：闸门（开关 ∧ kind=chat ∧ scene 非空）→ 三块按 policy > task > prefs 序渲染，经 `ReactExecutor` 在**资源召回后**注入 `AgentRunRequest.injectedBlocks`（落点 = L1 上下文层之后、当前 user 消息之前——§5.1 优先级语义）。
- **Policy**：resource-manager `/api/biz-scenes/policies/active` 全租户 active 快照 → `BizSceneCatalogClient` 启动预热 + 5min 刷新（请求路径零阻塞）→ 消费侧解析「租户精确 > `*` 平台默认 ∧ 生效窗 ∧ 最高 version」。
- **business_task**：orchestrator 自有表（`sunshine_chat`）；召回阶梯 = 同场景活跃（时间窗）→ 会话绑定锚定 → 最近 1 条详情 + ≤top-k 极简目录；`done`/`archived` 不进 Prompt。
- **偏好**：`user_context_state` 扩 `biz_scene_scope`/`confirm_status`；装载 = preference 类 ∧ confirmed ∧ 未过期 ∧ key ∈ 白名单（`*` → 全局白名单；`{scene}` → 场景白名单）；写路径 `biz_scene_scope` 回退属 §5.5（M5 范畴）。
- Nacos `agent.business-context.*`（默认关）；Live `verify_business_context_live.py` A（开关关）/ B（无场景）/ C（三块装载硬证据）/ D（task 隔离）。

> 与 [task-list-memory](./2026-08-14-task-list-memory-unification-design.md) M0–M3 并行落地；装配时序统一见 §2.2（P3′ 为其块，不阻塞本层 M0/M1）。

Live 建议：`scripts/verify_business_context_live.py`（M1 起可测 Policy 注入；M3 补任务板）。

---

## 9. 反模式（验收红线）

1. 全量历史/全量偏好灌进 Prompt  
2. 业务阈值/权限只靠向量相似度  
3. 记忆无过期、无确认态、无白名单  
4. 把 L3 摘要当工单真相  
5. 将本层与 agent 执行态（fast 会话级任务清单 / H1 / KV `todo`）混为一个「任务板」概念  
6. 仅按 `updated_at` Top-K 灌多条任务详情（无 scene 过滤）  
7. 用向量决定「当前焦点任务」或用 AI **发明** `biz_scene`  
8. 独立场景分类器 / 选场景·选任务 HITL（本层明确不做）  
9. 在资源召回前空想 scene 并装载 Policy  
10. 路由前一次性 assemble 含 L3+业务块，导致无法按 skill 字段装结构化记忆  
11. 场景码表 `description` 写成展示文案而非检索锚点，导致 embedding 回退命中率低  
12. auto 场景未经审核直接用于 Policy/任务板装载（必须 pending_review 隔离）  
13. 读路径创建 auto 场景（破坏 prefix 稳定；仅写路径可创建）  
14. auto 场景无限增长不设上限/不清理（污染码表）  

---

## 10. 文档关系

| 文档 | 关系 |
|------|------|
| 五层压缩 | 压缩与会话记忆 SSOT；本层为**业务权威前缀**，优先级写入其装配序旁注 |
| task-scene | chat/task 记忆隔离；本层一期主挂 chat；task 不强制 |
| routing v6 | 产品 `kind` / `executionMode`；本层 `biz_scene` 业务轴 |
| request_decision | 仅业务工具确认等既有 HITL；**不**用于选场景/选任务板焦点 |
| Skill / Agent Catalog | `biz_scene` **引用**入口；码表 SSOT = 业务场景 Lab；与路由召回同链路 |
| [kind-biz-scene-catalog](./2026-08-13-kind-biz-scene-catalog-design.md) | Lab UI/DDL、资源 `kind` 过滤、工具集 chat/task、退役 react-prompt |
| [task-list-memory](./2026-08-14-task-list-memory-unification-design.md) | 会话级执行态 + KV Memory `todo` 沉淀；与 `business_task` **边界隔离**、不双写；装配时序对齐 §2.2（P3′） |

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
| D10 | `biz_scene` 码表 = 独立业务场景 Lab（非 Prompt 子页）；与 kind-biz-scene-catalog 对齐 | 2026-08-13 |
| D11 | 对齐 task-list-memory v2 / task-scene v14：KV `todo`、会话级恢复块与 `business_task` 边界隔离；§2.2 P3′ 补位；§4.3 表演进随 KV Memory 统一 | 2026-08-15 |
| D12 | **biz_scene embedding 回退（v3）**：当资源召回未命中时，用 query embedding 检索 `biz_scene_definition` 码表（零 LLM 延迟，阈值保守）；资源召回优先，embedding 仅回退。读/写路径对称。**不违反 D7**（非 LLM 分类、非 HITL、非 AI 自由生成） | 2026-08-18 |
| D13 | **场景来源双轨（v4）**：`biz_scene_definition` 增 `source` 列（manual/auto）；`auto` 场景初始 `pending_review`，仅嵌入检索可用，不可装载 Policy/任务板；防污染机制（上限+去重+TTL+频率限制）；前端 Lab 双 Tab；仅写路径创建，读路径不创建 | 2026-08-18 |

---

## 12. v3 embedding 回退：风险与验收

### 12.1 风险

| 风险 | 对策 |
|------|------|
| 场景码表 `description` 质量差，embedding 命中率低 | 运营侧写法规约（§4.4）：`description` 必须包含该场景下用户可能使用的自然语言关键词；可提供"检索预览"工具验证 |
| embedding 误召回场景（query 模糊跨域） | `minScore` 默认 0.7 保守，且仅取 Top-1；误召回代价可控（偏好白名单过滤 + 任务板无匹配则空） |
| 码表场景数膨胀（>50），全量 embedding 检索延迟 | 码表数量有限（业务场景闭集），全量 cosine 计算毫秒级；若未来超百可迁 Milvus |
| 写路径误判场景 → 偏好落错 `biz_scene_scope` | 容忍度高于读路径：偏好落错 scope → 下次读时场景不匹配 → 白名单过滤不加载；实际影响有限 |
| 与 D7「场景随资源召回」表面冲突 | D12 已裁定不冲突：embedding 匹配非 LLM 分类，非 HITL，非 AI 自由生成，资源召回仍优先 |
| LLM 自动创建场景质量差（名称/描述不准确） | `pending_review` 隔离 + 运营审核兜底；去重防重复；相似度 > 0.85 复用已有场景 |
| auto 场景泛滥，码表膨胀 | 硬上限（20）+ TTL 30 天自动清理 + 创建频率限制（10 分钟 3 个） |
| auto 场景误用于 Policy 装载 | `pending_review` 状态不可挂 Policy/任务板；仅 `active` 可正式使用 |
| 读路径意外创建场景 | 硬约束：仅写路径 `ContextWritePath` 可创建；读路径代码路径不含创建逻辑 |

### 12.2 验收（v3 补充）

| # | 场景 | 预期 |
|---|------|------|
| V10 | 用户输入"帮我查下上次那笔退款"，无 skill/agent 召回 | embedding 回退命中 `compliance-review` → 加载该场景的 Policy + 任务板 + 偏好 |
| V11 | 用户输入"今天天气怎么样"，无 skill/agent 召回 | embedding 回退所有场景得分均 < 0.7 → `biz_scene = null` → 跳过结构化层 |
| V12 | 用户输入"审批一下"，同时命中 `compliance-review` agent 且 embedding 回退最高分是 `expense-assist` | agent 带出的 `compliance-review` 优先（资源召回 > embedding 回退） |
| V13 | 写路径：assistant 完成一轮报销咨询，无资源召回 | embedding 回退命中 `expense-assist` → 偏好抽取带 `biz_scene_scope = expense-assist` |
| V14 | 码表 `description` 修改后，向量更新 | 运营修改 `description` → 异步触发 re-embedding → 下次检索命中新向量 |
| V15 | 码表 `status=disabled`（旧 `retired`） | embedding 检索过滤 disabled 码；已绑资源解析时视为无效跳过 |
| V16 | 读路径 embedding 回退延迟 | 与 P2（agent/skill embedding）复用同一管道，无额外 embedding 调用；仅多一次码表 cosine 计算（毫秒级） |
| **v4 场景双轨** | | |
| V17 | 写路径：用户连续 3 轮咨询"设备采购"流程，无任何 skill/agent 召回，embedding 回退也未命中 | LLM 自动创建 `auto` 场景（`status=pending_review`），偏好抽取带 `biz_scene_scope`；后续读路径 embedding 可命中该 pending_review 场景 |
| V18 | 用户单轮闲聊"今天天气不错" | 不满足 ≥2 轮对话条件，不触发 auto 场景创建 |
| V19 | 同 tenant 已有 20 个 auto 场景（达上限） | 新 auto 场景创建被拒绝，前端告警；现有场景正常检索 |
| V20 | LLM 生成的 auto 场景与已有 active 场景相似度 > 0.85 | 去重命中，复用已有场景，不重复创建 |
| V21 | auto 场景 pending_review 期间，用户发起相关对话 | embedding 检索可命中该场景（pending_review 可嵌入检索），但**不装载 Policy/任务板**；仅偏好带 scope |
| V22 | 运营审核通过 auto 场景 | status → `active`，此后可挂 Policy，任务板/偏好装载等同于 manual 场景 |
| V23 | 运营驳回 auto 场景 | status → `rejected`，Milvus 向量移除，45 天后物理删除 |
| V24 | auto 场景 30 天无人审核 | 自动清理 → `auto_cleaned`，向量移除 |
| V25 | 前端 Lab「自动发现」Tab | 显示所有 `source=auto` 场景（含 pending_review/active/rejected），可审核/删除；`source_conversation_id` 可溯源 |
| V26 | 读路径不存在 auto 场景创建 | 请求体对比：读路径前后 messages 无新增场景写入；仅写路径异步创建 |
