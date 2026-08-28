# Skill 可发现 / 触发分离 + 绑定保真（行业对齐）

> **状态**：🟢 实施中（S-0/S-D/S-T/S-1/A-1/A-5-full/A-6/A-7 已实现；A-2~A-4 租户、S-C 双阈值、v3.6 retrieval 双层留待下一阶段） · **v3.2（2026-08-14）** · **v3.3（2026-08-14 · 对齐记忆收敛）** · **v3.4（2026-08-15 · 工具装配 defer-loading 对齐）** · **v3.5（2026-08-15 · 工具统一授权 租户×kind）** · **v3.6（2026-08-15 · T0 唯一数据源 = 工具集配置）** · **v3.7（2026-08-15 · 委派子 agent 工具召集双轨）** · **v3.8（2026-08-15 · 双阈值采纳 / 候选动态加载）** · **v3.9（2026-08-15 · L0 短路 / 多资源处置）** · **v3.10（2026-08-15 · agentIds 跨轮 sticky）** · **v3.11/v3.12（2026-08-15 · spawn-hint 工具清单渲染）** · **v3.13（2026-08-15 · 子 agent 抽屉 skill 加载步骤）** · **v3.14（2026-08-28 · 正文指令信封）** · **v3.15（2026-08-28 · 正文 SYSTEM 权威层）**  
> **实施（2026-08-24）**：✅ **S-0**（chat_message 落 `routing_skill_ids`/`routing_agent_ids` + 续跑/新建复用 `RoutingSeed`）· ✅ **S-D**（`context.skill-directory` 名+描述目录，召回不灌 overlay）· ✅ **S-T**（L0 短路 + triggered skillIds 全链路 + skill 工具 schema 召回）· ✅ **S-1**（`RoutingSeed` 跨轮：L0 整表替换 / 无触发继承；`RoutingStickyService`）· ✅ **A-1**（预定义 agent 子工具 = (tenant,kind) ∩ 声明；动态 sub `tool_ids` ⊆ 集）· ✅ **A-5-full**（主 agent T0 = 工具集配置，skill 声明不并集；retrieval 双层留后续）· ✅ **A-6**（`tool_ids` 参数）· ✅ **A-7**（spawn-hint 工具清单 + v3.13 抽屉 skill 加载步骤）· ✅ **A-2**（skill `tenant_id` 全链路：DDL+Entity+Catalog+orchestrator `TenantVisibility` 消费）· ✅ **A-3**（agent 写侧 `tenantId` 落地）· ✅ **A-4**（picker 按 (tenant,kind) 集收敛：`/sets/all/tool-ids` 并集 + BFF 代理 + Agents/Skills 候选过滤）· ✅ **S-C**（v3.8 双阈值采纳 / 候选动态加载：`SkillAdoptionService` trigger/candidate/δ + `sunshine_search_skills` 升级触发 + 分类器逐项置信契约；默认关闭待开启验收）。Live 验收 `scripts/verify_skill_sticky_live.py`。⏳ **v3.6 retrieval 双层**（工具规模超阈值时启用）。
> **日期**：2026-08-12（v3 收缩 → **v3.1 加载≠触发** → **v3.2 工具装配维度** → **v3.4 defer-loading 对齐** → **v3.5 统一授权** → **v3.6 装配收紧** → **v3.7 委派双轨** → **v3.8 双阈值采纳** → **v3.9 L0 短路 / 多资源处置** → **v3.10 agentIds 跨轮 sticky** → **v3.11/v3.12 spawn-hint 工具清单渲染** → **v3.13 子 agent 抽屉 skill 加载步骤**）  
> **v3.2（2026-08-14 · 工具装配维度）**：**triggered 集同时驱动 overlay 与主 agent 工具并集**——`DynamicToolkitFactory` 主 agent 按 triggered `skillIds` **单调并集**工具（默认工具集 ∪ 各 triggered skill 声明的工具），triggered 集不变 → `tools` 字节不变 → Tier 0 稳定（联动 [五层 §5.5.3 v6/v24](./2026-07-31-unified-context-compression-design.md)）。SUB/Worker 无前缀包袱，仍**即时并集**。装配依赖 S-0（消息存 triggered）+ S-1（轻 sticky）先落地。**修正 task-scene §7.4 约束 3**「只作用于子 Agent」——主 agent 允许绑 skill 工具，但必须 sticky 化，禁止按每轮最新 skillId 自由并集。
> **v3.3（2026-08-14 · 记忆收敛对齐）**：命名与五层 **v25** / task-scene **v14** 同步——「L2 用户状态」读作 **KV Memory（scope=user）**（§3.1/§3.2/§5）；**换题（清空 sticky seed）协同 [task-list-memory](./2026-08-14-task-list-memory-unification-design.md) 沉淀未完成任务到 KV Memory**（§4.3），随后 seed 清空、目录仍可发现。
> **v3.4（2026-08-15 · 工具装配 defer-loading 对齐）**：工具装配补充**行业对齐可选路径**（对齐 Claude Code `defer_loading` / Tool Search，官方博客「Prompt caching is everything」）——**规模二分**：工具规模 ≤ 阈值（默认 20）保持 `full` 全量 schema 进 Tier 0；规模超阈值启 `retrieval` 模式，**Tier 0 只放全量工具名 stub（确定性排序，字节恒定）**，触发集工具的完整 schema 经 **Tier 2 尾部**注入（平台 triggered 注入 / 模型自驱 `search_tools` 元工具两种形态）。**感知面/注册面分层**：模型所见（`PromptComposer` 分层渲染）与执行注册（`DynamicToolkitFactory` triggered 并集）解耦；`search_tools` 让「流内中间触发」**零重建**——工具面不变，仅尾部 tool result 增 schema。仍推荐默认 `full`（规模小）或平台 triggered 注入（规模大）；AgentScope ToolGroup 中间激活为框架可选能力，激活一次 = 一次全量重建，需定价限频，**非默认路径**。详见新增 **§4.6**。
> **v3.5（2026-08-15 · 工具统一授权 租户×kind）**：工具**可用性唯一控制点 = `tool_set`（tenant, kind）默认集**（`tenant_chat_default` / `tenant_task_default`，已具备租户维度、无 global 兜底）；skill/agent/workflow 创建时带 `tenantId` + `kind=chat|task|all`，**只声明不决定可用性**——声明工具引用必须 ⊆ 当前 (tenant, kind) 集，搜索候选按集过滤（`kind=all` = chat ∪ task 并集）；运行时 `intersectEnabledPool` 求交作防御双保险。**T0 面 = 当前会话 (tenant, kind) 集**（模型可见即可用，无 `missing` 幻觉调用），租户池（chat∪task 并集）仅作 kind=all 声明候选/审计，**不进装配面**。skill 域补 `tenant_id`（现状完全无租户）、agent 写侧 tenantId 落地为前置。详见新增 **§4.7**。
> **v3.6（2026-08-15 · T0 唯一数据源 = 工具集配置）**：装配口径**收紧**——**主 agent T0（前缀）只以 `(tenant, kind)` 工具集配置为唯一数据源**，**不再与 skill 声明并集**；v3.2「主 agent 按 triggered 集合并工具」仅保留给 SUB/Worker（无前缀包袱）。skill 声明工具降级为 **schema 召回加速索引**：triggered skill → 命中其声明的工具（须 ⊆ 当前集）→ **直接从工具集加载完整 schema** 注入 Tier 2 尾部，**命中即跳过相似读检索**；`search_tools` 元工具保留作模型自驱兜底（无声明命中时）。**工具规模 ≤ 阈值（默认 20）走 `full`，无需关心召回**；超阈值才启用 `retrieval` + 召回。详见 §4.6/§4.7 及验收 V8/V10/V14。
> **v3.7（2026-08-15 · 委派子 agent 工具召集双轨）**：`spawn_subagent` 委派工具分**两种召集**——**预定义 agent（`agent_id`）自动注入**：**(tenant, kind) 集 ∩ 声明工具**（`toolsJson` ∪ 绑定 skill 工具，求交去重），主 agent 不选；**动态 sub agent（仅 prompt）由主 agent 自取**：新增 `tool_ids` 参数从当前 (tenant, kind) 集取**子集**（越界运行时剔除并提示），缺省回退主 agent 同款集全量（现状 `sameToolsAsMain`）。SUB 无前缀包袱仍即时注入，可用性恒受 (tenant, kind) 集约束（v3.5）。详见 §4.5/§4.7 及验收 V9/V15。  
> **v3.8（2026-08-15 · 双阈值采纳 / 候选动态加载）**：轨 A 资源采纳**阈值化**——**skills 双阈值**：置信 > `trigger` → **直接触发最高 1 个**（≤1，进 triggered/overlay）；`candidate` < 置信 ≤ `trigger` → **仅进 discoverable 候选**（名+描述 + 可动态加载标记），运行中模型经**候选版 `search_tools`** 显式加载 → 升级 triggered；**agents 单阈值**：置信 ≥ `candidate` → 全量进**可调度池**（Top-K，只可调度不自动委派）。**候选 ≠ 触发底线不变**：候选永不进 T0 overlay，动态加载只动 Tier 2 尾部（零 prefix 重建）。详见 §3.1/§3.2/§4.3/§4.6/§6 S-C 及验收 V16/V17。
> **v3.9（2026-08-15 · L0 短路 / 多资源处置）**：L0（`/skill` 显式 / `$agent` 显式）命中即**短路**——只要命中任意 1 个 `/skill` **或**任意 1 个 `$agent`，直接跳过**规则层（L1）与 L3 意图识别**出方案，不再叠加规则、不再请求 LLM 兜底。多资源处置对齐行业：**多个 `/skill` 只 trigger 第一个**（首个可解析即绑定，其余丢弃——候选化见 v3.8 候选层，暂不落地）；**多个 `$agent` 全部进可调度池**（Top-K，只可调度不自动委派）。实现落点：`ForcedExecutionRouter.BindingAcc.hasL0Trigger`。详见 §3.2/§6 S-T 及验收 V18。
> **v3.10（2026-08-15 · agentIds 跨轮 sticky）**：**可调度 agentIds 与 skills 同语义跨轮接续**——上轮 `RoutingResult.agentIds`（指定 `$agent` / L1 规则命中 / L3 候选进池）作为 seed 粘到本轮，**本轮未产生新候选时继承不替换**；仅当本轮产生**新的候选 agent 集**（L0 `$agent` 或 L3 候选重选）才**整表覆盖替换**上轮 agentIds。退出/明确换题与 skill 同规则清空 seed。目的：避免跨轮次可调度 agent 断档（主 agent 持续可 spawn 上轮委派对象）、保持压缩点稳定（Prompt 委派提示字节跨轮可控）。seed 与 triggered skillIds 同存 `RoutingResult`（S-0），同步进 §4.1/§4.2/§4.3/§6/§7 验收。
> **v3.11（2026-08-15 · spawn-hint 工具装配告知 → v3.12 工具清单渲染）**：`react.spawn-hint`（委派提示）补一条**工具装配告知**——预定义 agent（`agent_id` 指定）**已自动装配其声明工具**（(tenant, kind) 集 ∩ 声明工具，v3.7），主 agent **无需代查业务数据**，直接 `spawn_subagent` 委派即可；动态 sub agent（仅 prompt）才由主 agent 自选工具（`tool_ids`/同款集全量）。**v3.12 落点升级**：`{agents}` 渲染不再只是 id/描述，而是**每个预定义智能体附带「已装配工具」可读名清单**（`toolsJson` 经 ToolCatalogService.displayName 转换，索引自带 toolsJson，禁止远程 find），提示词正文同步声明「委派后由子智能体自行调用工具获取数据」，主 agent 见工具证据直接委派，不再因「我无业务数据工具」拒绝委派/反向向用户要数据。落点：委派提示模板（Catalog `react.spawn-hint`）+ `AgentCatalogService.renderForSpawnHint` + `AgentCatalogIndexEntry.toolsJson` + §4.5 工具召集双轨说明。详见 §4.5 及验收 V20。
> **v3.13（2026-08-15 · 子 agent 抽屉 skill 加载步骤）**：预定义 agent 绑定的 skill 此前仅经 `PromptComposer.resolveSkillOverlay` **纯提示词装配**，子 agent 抽屉（subSteps）看不到「加载了哪个 skill」。现于 `SpawnSubagentTool` spawn 预定义 agent 时，若 `primarySkillId` 非空则向 subTimeline 注入一条**skill 加载步骤**（`phase=skill`，id/文案与主流程 `completeSkillLoad` 一致：label=「加载技能」、summary.after=「{skillId} {displayName}」、`StepMetadata.fromSkillLoad`），成为抽屉 subSteps 首行。动态 sub agent（无 skillId）不注入。落点：`SpawnSubagentTool.skillLoadToken`。详见验收 V20 补充。
> **v3.14（2026-08-28 · 正文指令信封 / 跨轮重注入综合）**：触发集全文 overlay 在 React 链路（USER 角色，AS 2.0 Hook 禁 SYSTEM）下以 **`<skill_information>` 指令信封**注入（对齐 Claude Code `skill_information`）——`PromptComposer.wrapSkillEnvelope` 把正文包裹为 `<skills_referenced>`（各触发 skill id 索引）+ `<skill_block>`（正文），给 HARD-GATE 这类否定式禁令一个明确的**指令身份边界**，让模型识别「这是须遵循的技能指令」而非普通用户闲聊，缓解「模型无视 skill 要求」。（此前 `resolveSkillOverlays` 以裸 USER 消息注入，模型易将其当作上下文。）**跨轮 sticky 重注入无需新增逻辑**——触发集（`RoutingResult.skillIds`=triggered SSOT）不进 L1/L2/L3（§5.5 v24），由消息字段 `routing_skill_ids` → `loadRoutingSeed` → `RoutingStickyService.applySeed` → 本轮 `triggeredSkillIds` 承载，每轮（含 sticky 继承轮）都会经信封注入，压缩点天然不丢触发态。落点：`PromptComposer.wrapSkillEnvelope`（React 路径仅此一处；gateway 路径 skill 仍作 system 角色注入，本身已有指令身份，不套信封）。单测 `PromptComposerTest.composeReactInputs_wrapsSkillOverlayInInstructionEnvelope`。
> **v3.15（2026-08-28 · 正文 SYSTEM 权威层）**：**v3.14 信封仍不足以让模型遵循 HARD-GATE**——实测「加载技能 brainstorming」后模型跳过 HARD-GATE 直接进入实现（建清单 → 改版本号 → 给结论），原因是信封只是 USER 角色消息，**惯性被用户「优化一下项目」这类可直接执行的任务感压过，而 USER 指令权重天然低于 SYSTEM**。根因：skill 正文被降级进 `inputMessages`（USER），未走 AS 2.0 官方 SYSTEM 通道。AS 2.0 约束 `PreCallEvent.inputMessages` 禁 SYSTEM（`AgentBase.notifyPreCall` 守卫），系统提示唯一官方通道是 `sysPrompt` / `PreCallEvent.setSystemMessage` / `appendSystemContent` / **`MiddlewareBase.onSystemPrompt`**（官方 skill 注入即经 `SkillHook.appendSystemContent`，2.0 起由 `DynamicSkillMiddleware.onSystemPrompt` 承接）。**修复**：新增 `SkillInjectionMiddleware implements MiddlewareBase`——在 `onSystemPrompt` 把触发集 skill 正文追加到 **system prompt（SYSTEM 权威层）**，取代 USER 信封。per-call 触发集经 `RuntimeContext` 注入（`CTX_TRIGGERED_SKILL_IDS`，由 `ReActAgentRuntime` 构建 rt 时 put），middleware 无状态共享单例满足 HarnessAgent 指纹缓存复用；仅 MAIN 生效（SUB/WORKER 单数 skillId 仍走 `systemOverlay`，PLANNER 走 harness）。`PromptComposer.composeReactInputs` 在 MAIN（`triggeredSkillIds` 非空）跳过 USER 信封，防重复注入稀释；SUB/WORKER（triggeredSkillIds 空）保留 USER 信封。**跨轮 sticky 重注入依旧自然成立**——每条触发集每轮经 `onSystemPrompt` 重注入 system，压缩点不丢触发态。落点：`SkillInjectionMiddleware`（`ProcessingStepMiddlewareFactory.sharedChain` 最内层）· `ReActAgentRuntime` rt 注入 · `PromptComposer` MAIN 跳过 USER 信封。单测 `SkillInjectionMiddlewareTest`（MAIN 注入/去重/非 MAIN 不注入/空集不注入）5 例 + `PromptComposerTest.composeReactInputs_mainSkipsUserSkillEnvelope`。
> **编号**：阶段四增量（路由 / Skill 会话态）  
> **前置**：[unified-routing v6](./2026-07-29-unified-routing-design.md) · [unified-context-compression](./2026-07-31-unified-context-compression-design.md) · [task-scene §7 插件/Skill 分层](./2026-08-01-task-scene-context-design.md)（名+描述静态 / 正文按需）  
> **一句话**：**可发现**（Catalog 名+description）与 **触发**（本轮注入 overlay 的 `skillIds`）分离；**物料加载**（sandbox）与触发正交。消息存完整 `RoutingResult` + 上轮**已触发** id 轻 sticky。对齐 Cursor：context discovered, not dumped。

### 相对 v2 / v3

| 口径 | 取舍 |
|------|------|
| v2 Redis ledger / 软链 / processGraph | **不做**（同 v3） |
| v3「租户固定 ∪ 意图召回 → 一律 overlay」 | **纠正**：固定/召回默认只进**可发现集**；overlay 仅对**已触发** `skillIds` |
| 现状 `PromptComposer.resolveSkillOverlay(skillId)` 开场灌全文 | **目标**：仅触发集灌 overlay；目录摘要另层注入 |

---

## 1. 三层语义（行业同构）

| 层 | 含义 | 进 Prompt？ | 典型来源 |
|----|------|-------------|----------|
| **可发现 Discover** | Agent 知道有哪些 skill、何时该用 | **仅** `id + displayName + description`（「Use when…」） | 租户/场景启用 Catalog；可选 KV Memory（scope=user）提权排序 |
| **触发 Trigger** | 本轮按该 skill **正文**行动 | **全文** `systemOverlay` / SKILL 正文（稳定前缀或 Tier 2）**＋ 工具 schema 召回**（v3.6：skill 声明工具 → 从工具集加载 schema 注入 Tier 2 尾部；主 agent T0 恒 = 工具集配置，不并集） | L0 `/`；上轮已触发 sticky；极少数合规强制；高置信单点（可选） |
| **物料 Load** | 文件在沙箱可读 | 不进 prompt；`mountSkill` → `/skills/{id}/` | 随**触发**懒挂；或工具读路径时挂；**≠** 触发 |

```
Discover ──（用户 / 、高置信、sticky 续）──► Trigger ──► Overlay 注入
                │                                │
                └──── 可选提权排序 ───────────────┘
                                                 │
                                            Sandbox mount（正交）
```

**禁止**：把「规则 / embedding 召回命中」直接等价为「开场强制触发 overlay」（现状+ v3 初稿的主要偏差）。

---

## 2. 目标与非目标

### 2.1 目标

1. **行业对齐**：静态可发现 = 名+描述；正文按需/按触发注入（对齐 Cursor / task-scene §7）。  
2. **续跑保真**：HITL / reconnect 复用完整 `RoutingResult`，不丢**已触发** `skillIds`。  
3. **轻 Sticky**：粘的是**触发集**，不是可发现全集；无 `/` 续聊时保持 overlay，直到退出/换题/L0 覆盖。  
4. **装配纪律**：`PromptComposer` 只对 `skillIds`（触发集）调用 overlay；可发现集走目录摘要层。

### 2.2 非目标

- 不建 Redis ledger / 软链一等 source / `processGraph`。  
- 不扩展 `RoutingResult` 为双数组（见 §3.1：可发现不进 RoutingResult）。  
- 不在 orchestrator 硬编码技能名。  
- L3 **禁止**改写 `executionMode`；workflow 轨不做 skill sticky。  
- 不做「未发消息后台自动开下一 skill」。  
- 不要求模型必须经工具读 SKILL.md 才触发（允许平台在 Trigger 时直接灌 overlay；与「可读沙箱副本」并存）。

---

## 3. 默认模型（覆盖大多数场景）

### 3.1 可发现集（每轮，轻）

```
discoverable =
  租户启用 Catalog ∩ 场景可见
  （可选）KV Memory（scope=user）Top-K 提到摘要前列，仍只暴露名+描述
```

- **租户/场景「固定」= 固定可发现**，不是固定触发。  
- 合规红线若必须每轮约束行为 → 标为 **force-trigger**（极少数），与「方法包常驻可发现」分开配置；禁止把整个方法包 Catalog 当 force-trigger。  
- **候选层（v3.8）**：`candidate` < 置信 ≤ `trigger` 的 skill → discoverable **提权排序 + `dynamicLoadable=true`**（仍只暴露名+描述），不进 overlay、不进 triggered；运行中可经候选版 `search_tools` 动态加载（§4.6 形态 3）。agent 候选（置信 ≥ `candidate`）→ 可调度池（§3.2）。

### 3.2 触发集 `skillIds`（进 RoutingResult）

```
triggered skillIds =
  L0 显式（/skill、clientSkillIds）        // 最高优先，可整表替换
  ∪ 上轮 triggered sticky（无退出/换题/L0）
  ∪ L3 置信 > `trigger` 阈值 → 直接触发「本轮唯一应执行」的 ≤1 个 skill（v3.8：置信度取 L3 classifier `confidence`，**禁 L2 原始相似度**——未校准、跨 skill 不可比；要求最高分**显著高于次高分**——相对差距 δ 未达标即使过线也不触发；阈值默认高、倾向**默认关闭**，主路径仍 L0 + sticky）
  ∪ 候选动态加载升级（v3.8）：模型运行中显式加载候选 skill → 本轮追加进 triggered，后续轮次 sticky 继承
  ∪ force-trigger（租户合规例外）

agentIds（可调度池，v3.8 / v3.10）=
  L0 显式（$agent、clientAgentIds）
  ∪ L1 规则命中（resourceType=agent）
  ∪ L3 置信 ≥ `candidate` 阈值 → 全量进可调度池（Top-K + Catalog 描述治理）；**只可调度不自动委派**——决定权在主 agent 运行时 `spawn_subagent(agent_id=…)`
  ∪ 上轮 agentIds sticky（v3.10：无本轮新候选时继承；有新一轮候选 → 整表替换覆盖，同 skills 的 L0 替换语义）

规则 / embedding 召回 → 默认只影响 discoverable 排序 / 给 L3 候选
           → 禁止无门槛写入 triggered
```

| 来源 | 进 discover | 进 triggered（overlay） |
|------|:-----------:|:----------------------:|
| 租户/场景启用 Catalog | ✅ | ❌（除非 force-trigger） |
| L0 `/` / 客户端点名 | ✅ | ✅ |
| 规则 / embedding 召回（L3 候选） | 排序/候选（v3.8：`candidate < 置信 ≤ trigger` → 提权 + `dynamicLoadable`） | ❌ 默认；置信 > `trigger` 阈值才可 ✅；模型动态加载升级后可 ✅ |
| 轻 sticky（上轮 triggered） | — | ✅ 继承 |
| 沙箱已 mount 的历史文件 | 物料层 | ❌ 不单独构成触发 |

**L0 短路（v3.9）**：命中任意 1 个 `/skill` **或**任意 1 个 `$agent` → **短路出方案**，跳过规则层（L1）与 L3 意图识别；不叠加规则绑定、不请求 LLM 兜底。多资源处置：**多个 `/skill` 只 trigger 第一个**（首个可解析即绑定，其余丢弃；候选化沿用 v3.8 候选层，暂不落地）；**多个 `$agent` 全部进可调度池**（Top-K，只可调度不自动委派，由主 agent 运行时 `spawn_subagent(agent_id=…)` 决定）。

**软链（产品语义）**：overlay 可写「完成后使用 X」；下轮靠 **description 可发现 + 用户推进/L0/高置信** 再触发 X——平台不建 `SOFT_CHAIN`。

### 3.3 装配（相对现状的根因修正）

| 步骤 | 现状（偏差） | 目标（行业） |
|------|--------------|--------------|
| 路由 | 召回 id ≈ 绑定 | 产出 discover 上下文 + **triggered** `skillIds` |
| Prompt | `resolveSkillOverlay(skillId)` 开场灌全文 | 目录摘要（名+描述）+ **仅 triggered** overlay |
| 工具集（v3.2 / v3.4 / v3.5 / v3.6） | `DynamicToolkitFactory.mergeSkillTools(whitelist, skillId)` 按**单 skillId** 即时并集（**v3.6 起仅 SUB/Worker 用**） | **主 agent T0 只以 (tenant, kind) 工具集配置为唯一数据源**（v3.6），不与 skill 声明并集——规模 ≤ 阈值走 `full` 全量 schema（无需召回），超阈值走 `retrieval` 名 stub（见 §4.6）；skill 声明工具降为 **schema 召回加速索引**：triggered skill 命中 → 直接从工具集加载完整 schema 注入 Tier 2 尾部，**命中即跳过相似读检索**。SUB/Worker 无前缀包袱仍**即时并集**（agent 自身 `toolsJson` ∪ 绑定 skill 工具，过 §4.7 (tenant, kind) 集求交）。**v3.5 授权前置（见 §4.7）**：全部工具经 `(tenant, kind)` 集约束——T0 = 该集配置，声明候选 ⊆ 该集 |
| 沙箱 | 绑定时 mount | 触发时懒 mount；与 loadedSkillIds 累积解耦 |

对齐 [task-scene §7](./2026-08-01-task-scene-context-design.md)：目录摘要稳定前缀；命中正文进动态段 / skill-overlay。

---

## 4. 数据契约

### 4.1 `RoutingResult.skillIds` = **本轮已触发**；`RoutingResult.agentIds` = **本轮可调度池**

```java
List<String> skillIds;   // triggered only；轨 A；可空
List<String> agentIds;   // 可调度池（L0 $agent ∪ L1 规则 ∪ L3 候选 ∪ 上轮 sticky）；轨 A；可空
// 不把 discoverable 全量写入 RoutingResult（避免续跑把「目录」当成「触发」）
```

- `agentIds` **可空**：无候选时不落空数组，避免污染 sticky seed。  
- 两者均**只存「本轮采纳」**，可发现集由 Catalog + 租户策略 **运行时解析**，不落消息 SSOT（除非日后要审计「当时可见集」，另开字段，非本版）。

### 4.2 消息完整 `RoutingResult`（S-0）

- 存完整 JSON（`skillIds` + `agentIds`）；修续跑丢 skill / 丢可调度 agent。  
- HITL / 同消息续跑：复用已存 **triggered** `skillIds` 与 **可调度** `agentIds`，不重跑收集、不重触发决策。

### 4.3 轻 Sticky（S-1）— 只粘触发（skills + agentIds 同语义）

```
seed ← 上条 assistant.RoutingResult.skillIds + agentIds
      // skillIds：已触发（含 v3.8 动态加载升级项）
      // agentIds：可调度池（含 v3.10 上轮候选）

L0 / 新一轮候选 → 替换 triggered / 整表替换 agentIds
  · skills：L0（/、clientSkillIds）→ 整表替换 triggered
  · agents：L0（$、clientAgentIds）或 L3 本轮重选候选 → 整表替换 agentIds
退出技能 / 明确换题 → 清空 seed（同时把未完成任务沉淀到 KV Memory，见 [task-list-memory §6](./2026-08-14-task-list-memory-unification-design.md)）
否则 → triggered 至少含 seed；L3 可追加高置信，禁止低置信清空 seed
      → agentIds 至少含 seed；无新候选时继承上轮，禁止因「本轮未命中」清空
discoverable ← 本轮按 §3.1 重算（与 sticky 无关）
```

> **v3.10 关键语义**：agentIds sticky 与 skillIds 完全对等——**本轮未产生新候选 agent 时继承上轮**（主 agent 持续可 spawn 上轮委派对象，避免跨轮断档）；**仅在 L0 `$agent` 或 L3 候选重选时整表覆盖替换**。两者都不受压缩点影响：seed 不进 L1 Near/Mid/Far，由消息 SSOT 承载（与 overlay 同压缩点策略）。

- SSOT = 消息 RoutingResult；无 Redis。  
- triggered **不进** L1 Near/Mid/Far；Mid 最多路标。  
- Overlay 仅 triggered，落 Prompt 稳定前缀 / Tier 2（与压缩点一致）。
- **工具装配联动（v3.2 / v3.4 / v3.5 / v3.6）**：`AgentRunRequest` 透传 triggered `skillIds`。**主 agent T0（前缀）只以 (tenant, kind) 工具集配置为唯一数据源（v3.6）**——装配面 = `ToolSetResolver.resolveDefaultTools`（§4.7 A-5），**不与 skill 声明并集**；会话 kind 固定 → T0 字节恒定。triggered skill 变化只触发 **schema 召回**：命中该 skill 声明工具（须 ⊆ 当前集）→ 从工具集加载完整 schema 注入 Tier 2 尾部，T0 仍命中。**v3.4**：`retrieval` 模式下 T0 是名 stub，注册面仍 = 工具集全集（构建时已注册）；模型感知面由 `PromptComposer` 分层渲染。**v3.5**：全部工具受 (tenant, kind) 集约束。SUB/Worker 即时并集，不受 sticky 约束，但并集结果须 ⊆ 该 (tenant, kind) 集。

### 4.4 沙箱

- `skillIds`（triggered）→ 懒 `mountSkill`。  
- `loadedSkillIds` = 物料累积；sticky 不强制 umount。  
- **仅 mount 不注入 overlay** ≠ 已触发。

### 4.5 SUB

- SUB 不继承父 triggered sticky。  
- Spawn 可显式带一个 `skillId`（视为该 SUB 的 triggered）。
- **工具召集双轨（v3.2 / v3.6 / v3.7）**——`spawn_subagent` 按委派形态分两种：

| 委派形态 | 工具来源 | 说明 |
|----------|---------|------|
| **预定义 agent**（`agent_id` 指定） | **(tenant, kind) 集 ∩ 声明工具**（`toolsJson` ∪ 绑定 skill 工具，求交去重） | **自动注入**，主 agent 不选；可用性自动收敛到当前集 |
| **动态 sub agent**（仅 `prompt`） | 主 agent **自取工具集子集**：`tool_ids` 参数（须 ⊆ 当前 (tenant, kind) 集，越界运行时剔除并提示）；**缺省 = 主 agent 同款 (tenant, kind) 集全量**（现状 `sameToolsAsMain`） | 委派可裁剪工具面，「只给部分工具」；缺省行为不变 |

- **spawn-hint 工具清单渲染（v3.11/v3.12）**：委派提示 `react.spawn-hint` 的 `{agents}` 渲染每个预定义 agent 时**附带其声明工具的可读名清单**（`toolsJson` → `ToolCatalogService.displayName()`；索引 `AgentCatalogIndexEntry` 自带 `toolsJson`，渲染走内存索引，禁止远程 find）——预定义 agent **已按自身配置自动装配其声明工具**（具体工具随各 agent 配置动态展示，不写死业务域），主 agent **无需代查业务数据或追问用户提供数据**，直接 `spawn_subagent(agent_id=…)` 委派即可；仅动态 sub agent（不传 `agent_id`）才由主 agent 自选工具（`tool_ids` / 缺省同款集全量）。消除「主 agent 无业务工具 → 拒绝委派 / 反向向用户要数据」的思维链偏差。

- SUB 无前缀包袱，工具即时注入（不经 Tier 0）；**可用性恒受 (tenant, kind) 集约束**（v3.5）。  
- Workflow 子 Agent / Worker 同口径（`buildForSubAgent` 白名单并入）。主 agent 不在此列（T0 = 工具集配置，见 §4.3/§4.6）。

### 4.6 工具装配行业对齐（v3.4/v3.6 · defer-loading 双层）

> **依据**：Claude Code 官方博客「Prompt caching is everything」明确「中间改工具集是破坏 prompt 缓存最常见的方式」——工具 schema 位于缓存 prefix，增删一个工具即整段失效；对策是 **`defer_loading`**：工具全部已注册可执行，但模型先只见**轻量名 stub**，选中后完整 schema 才经搜索结果进请求**尾部**。Cursor 同口径：工具面恒定、技能/规则按需进 context 尾部。本节把该模式落到本项目。

**目标**：让「流内中间触发 skill 工具」**不破坏 Tier 0**——工具 schema 面（prefix）永远字节稳定，动态性全部移到 Tier 2 尾部（L3 / user query / tool result / skill 声明 schema 召回注入区）。

**规模二分**（复用 [五层 §5.5.3 v6 注记](./2026-07-31-unified-context-compression-design.md) `agent.tool.inject` 二选一）：

| 模式 | 触发条件 | Tier 0（prefix 静态） | Tier 2（尾部动态） |
|------|---------|----------------------|-------------------|
| `full`（默认） | 工具规模 ≤ 阈值（默认 20） | **全量工具 schema**（(tenant, kind) 工具集配置，确定性排序，字节恒定） | **无召回，无工具 schema 注入** |
| `retrieval`（v3.4/v3.6 可选） | 规模 > 阈值 | **全量工具名 stub**（(tenant, kind) 工具集配置，确定性排序，永不增删） | skill 声明命中 / `search_tools` 命中的完整 schema |

**感知面/注册面分层**（`retrieval` 模式下关键，禁止混为单层）：

| 层 | 职责 | 落点 |
|----|------|------|
| **模型感知面** | 模型看到什么：T0 名 stub + T2 尾部完整 schema | `PromptComposer` 分层渲染（改 prompt，不动 toolkit） |
| **执行注册面** | 工具实际可调用：**（v3.6）(tenant, kind) 工具集全集**（构建时已注册；skill 声明**不改变**注册面，只触发 schema 召回） | `DynamicToolkitFactory` |

两者必须同源——stub 名与注册集同出 **(tenant, kind) 工具集配置**渲染，禁止手工维护两套名单；单测校验「模型可见名 ⊆ 可执行注册」。

**动态注入两种形态**（都在尾部，零 prefix 重建）：

1. **skill 声明召回（平台驱动，v3.6 收紧）**——路由产出 triggered `skillIds` → 命中该 skill **声明**的工具（必须 ⊆ (tenant, kind) 集）→ **直接从工具集加载完整 schema** 渲染到 Tier 2 尾部。**召回优先级：声明命中 > 相似读检索**——声明命中即**跳过 embedding/相似度检索**直接加载，成本更低、结果更确定。triggered 变化 → 仅尾部变化 → T0 命中。
2. **模型自驱**（对齐 Claude Code Tool Search）——元工具 `search_tools(query)`：返回候选工具完整 schema 作为 tool result，自然落在 messages 尾部。用于**无声明命中**时的流内发现；**流内任意点触发零重建**，是「中间触发」唯一不破坏缓存的形态。  
3. **候选 skill 动态加载（v3.8，模型自驱）**——`candidate < 置信 ≤ trigger` 的 skill 以名+描述暴露在 discoverable 层（`dynamicLoadable=true`）；模型需要时经**候选版 `search_tools`（skill 域）**主动加载：完整 overlay + skill 声明工具 schema（须 ⊆ (tenant, kind) 集，声明命中即跳过相似读检索）落 Tier 2 尾部，**零 prefix 重建**。加载后**升级 triggered**：本轮 `skillIds` 追加 + 懒挂沙箱 + sticky 继承（§4.3）。**禁止**平台按「检测到模型提到 skill id」模糊自动注入——动态加载唯一入口 = 模型显式调用。

> **v3.6 判定顺序**：先看工具规模——**≤ 阈值（默认 20）走 `full`，根本不进入召回问题**；> 阈值才进 `retrieval` + 召回。召回内再按「skill 声明命中 → 直接加载工具集 schema；无命中 → 模型 `search_tools` 自驱」判定。

，，**与 ToolGroup 中间激活的关系**：AgentScope `Toolkit` 原生 `updateToolGroups` / `reset_equipped_tools` 是框架可选能力——激活新组后下一轮 `getToolSchemas(activeGroups)` 输出集合变化 → tools 字节变化 → **一次全量 prefix 重建**。属「省 token」换取「重建」，**非默认路径**；如用须定价限频（仅用户显式 `/skill` / L0 整表替换 / 单次高置信）。v3.6 默认仍推荐 `full`（规模小，无需召回）或 skill 声明召回注入（规模大），二者均无流内重建。

### 4.7 工具统一授权（v3.5/v3.6 · 租户×kind 双头）

> **问题**：工具**可用性**的控制点分散——skill 声明工具（`skill_version.tools_json`）与预定义 agent 工具（`toolsJson` ∪ skillIds 工具）只做去重不进白名单求交，仅靠工具注册层租户可见性**软性**兜底（不可见落 `missing`）；且 skill 域**完全无租户**（无 `tenant_id`、catalog 无租户参数、orchestrator 拉取不带租户）。多租户下 skill/agent 声明可引用任意全局工具，跨租户隔离不彻底。
>
> **解法**：把工具**可用性唯一控制点**收敛为 **`tool_set`（tenant, kind）默认集**（`tenant_chat_default` / `tenant_task_default`，已具备租户维度、无 global 兜底）。skill/agent/workflow 只**声明**工具引用，**不决定可用性**。

**三层职责分离**：

```
┌─ 授权上界：租户池（chat ∪ task 集并集）────────────────────────┐
│   用途：kind=all 资源的声明候选 + 跨 kind 审计；不进装配面      │
│                                                              │
│   ┌─ 控制层：ToolSet (tenant, kind) 默认集 ← 可用性唯一 SSOT ┐ │
│   │   chat 会话 = tenant_chat_default                        │ │
│   │   task 会话 = tenant_task_default                        │ │
│   │   T0 面（full/retrieval）= 该集成员 ← 模型真实可用面       │ │
│   └──────────────────────────────────────────────────────────┘ │
│                                                              │
│   ┌─ 声明层：skill / agent / workflow（只声明，不产生可用性） ┐ │
│   │   tenant_id + kind=chat|task|all                         │ │
│   │   创建时搜索候选 = 当前 (tenant, kind) 集（all=并集）      │ │
│   │   声明工具 ⊆ 集；仅作 schema 召回索引（SUB 并集求交）     │ │
│   └───────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

**关键边界**：

1. **T0 面 = 当前会话 (tenant, kind) 集**，非租户池全量——chat 会话不出现 task 工具 stub，模型可见即可用，无 `missing` 幻觉调用；T0 字节随会话 kind 确定（会话创建时固定），不随 skill 触发变化。
2. **kind=all 工具搜索候选** = 当前租户 chat ∪ task 集**并集**——只用于声明层候选，不进入任何会话的 T0 面。
3. **声明只是声明**——主 agent T0 不含声明工具（v3.6）；SUB/Worker 并集运行时以 `intersectEnabledPool` 求交为最终裁决（防御工具集变更）；声明引用落到集外时不硬删声明，前端提示 + 运行时降级。
4. **skill 域补租户**为硬前置：`skill_definition` 加 `tenant_id` + Registry/Catalog 按租户过滤 + orchestrator 拉取带租户（存量归 `default`）；agent 写侧 tenantId 落地（现状创建硬编码 `default`）。

**现状核实（2026-08-15 代码）**：

| 引用源 | 装配路径 | 过租户池 |
|--------|---------|:---:|
| 主 agent 默认集 | `ToolSetResolver.resolveDefaultTools` → `/sets/{kind}/tool-ids`（带租户）→ `enabledIds` 求交 | ✅ |
| skill 声明工具 | `DynamicToolkitFactory.mergeSkillTools` 去重后直接进白名单 | ❌ |
| 预定义 agent 工具 | `SpawnSubagentTool.mergeAgentSkillTools`（toolsJson ∪ skillIds）直进 sub 请求 | ❌（v3.7：改 (tenant, kind) ∩ 声明） |
| 动态 sub agent 工具 | `SpawnSubagentTool` 无 agentId 时 = `sameToolsAsMain`（当前 (tenant, kind) 集全量） | ✅（v3.7：增 `tool_ids` 自取子集） |
| workflow 子 agent | `buildForSubAgent` → `intersectEnabledPool` | ✅ |

**落地清单**（对应 §6 新增切片）：

| # | 改动 | 说明 |
|---|------|------|
| A-1 | **v3.6/v3.7**：`mergeSkillTools` 脱离主 agent T0（降为 schema 召回索引，命中校验 ⊆ 集）；预定义 agent 子工具 = (tenant, kind) 集 ∩ 声明（`mergeAgentSkillTools` 结果求交）；动态 sub `tool_ids` ⊆ 集校验（越界剔除） | 主 agent T0 = 工具集配置唯一数据源；SUB 两种形态授权约束统一 |
| A-2 | skill 加 `tenant_id` 全链路（DDL + Entity + Registry + Catalog + orchestrator 消费） | 「skill 对应租户」的载体 |
| A-3 | agent 写侧 tenantId 落地 | 租户私有 agent 可创建 |
| A-4 | picker 按 (tenant, kind) 集过滤候选（all=并集） | 创建时声明天然 ⊆ 可用集 |
| A-5 | T0 装配 = (tenant, kind) **工具集配置**（full/retrieval 二选一，skill 声明不参与） | 模型真实可用面 = 工具集配置 |
| A-6 | **v3.7**：`spawn_subagent` 增 `tool_ids` 参数（动态 sub agent 主 agent 自选子集；缺省回退主 agent 同款集全量） | 委派可裁剪工具面 |

---

## 5. 相对 unified-routing v6

| v6 | 本设计（v3.1） |
|----|----------------|
| 轨 A 收集 `skillIds[]` | 语义收窄为 **triggered**；装配禁止「召回即 overlay」 |
| §10「skillIds → overlays + 沙箱」 | 改为：discover 摘要 + triggered overlays + 触发时 mount |
| Pre-Routing 复用 | S-0 保真 |
| — | S-1 轻 sticky（触发集） |
| — | 主 agent 工具集按 **triggered 集合并**（v3.2，已作废） | **v3.6**：主 agent T0 恒 = (tenant, kind) 工具集配置；skill 触发仅作 schema 召回（注入 Tier 2 尾部）；并集语义仅保留给 SUB/Worker |
| — | 不做 ledger / 软链 / processGraph |
| — | **v3.8**：agent 单阈值（置信 ≥ `candidate`）进可调度池（Top-K），只可调度不自动委派；skill 双阈值（置信 > `trigger` 直接触发 ≤1 / `candidate < 置信 ≤ trigger` 动态加载） |
| — | **v3.10**：`agentIds`（可调度池）与 `skillIds` 同语义跨轮 sticky——无新候选继承上轮，新一轮候选整表替换；同存 RoutingResult，退出/换题清空 |

---

## 6. 实施切片

| 阶段 | 内容 | 出口 | 状态 |
|------|------|------|------|
| **S-0** | 消息存完整 `RoutingResult`（`skillIds` + `agentIds`）；续跑复用 triggered 与可调度池 | live：不丢 skill / 不丢可调度 agent | ✅ 已实现（`ConversationService.updateMessageExecutionPlan` / `loadRoutingSeed`） |
| **S-D** | 可发现层：Prompt 注入租户可见 **名+描述**目录；**召回默认不灌 overlay** | 无 L0 时不应出现无关 skill 全文 | ✅ 已实现（`context.skill-directory` + `SkillCatalogService.renderDiscoverableForPrompt`） |
| **S-T** | 触发：仅 L0 / sticky / force-trigger /（可选）L3 高置信 → `resolveSkillOverlay` + **skill 工具 schema 召回**（v3.6：triggered skill 声明工具 → 从工具集加载 schema 注入 Tier 2 尾部；主 agent T0 恒 = (tenant, kind) 工具集，不并集）。**L0 短路（v3.9）**：命中任意 `/skill` 或 `$agent` → 跳过规则层与 L3 直接出方案 | `/` 与续聊行为正确；embedding 召回 alone 不触发；T0 字节跨轮稳定；`$agent` 命中不再走 L3 | ✅ 已实现（`ForcedExecutionRouter` L0 短路 + `ReactExecutor` triggeredSkillIds + schema 召回） |
| **S-1** | 上轮 triggered 轻 sticky + agentIds 跨轮接续（v3.10，依赖 S-0） | 「继续」无 `/` 仍有 overlay + schema 召回稳定；无新候选时可调度池继承上轮，新候选整表替换 | ✅ 已实现（`RoutingSeed` + `RoutingStickyService` + `ExecutionPlanRouter.applySeed`） |
| **A-1（v3.5 / v3.6 / v3.7）** | `mergeSkillTools` 脱离主 agent T0 装配（降为 schema 召回索引，命中校验 ⊆ 集）；**预定义 agent 子工具 = (tenant, kind) ∩ 声明**（`mergeAgentSkillTools` 结果求交）；**动态 sub `tool_ids` ⊆ 集校验** | 主 agent T0 唯一数据源 = 工具集；SUB 两种委派形态授权约束统一 | ✅ 已实现（`ToolSetResolver.intersectToolSet` + `SpawnSubagentTool` 双轨） |
| **A-2（v3.5）** | skill 加 `tenant_id` 全链路（DDL + Entity + Registry + Catalog + orchestrator 消费端） | skill 目录/详情/工具绑定按租户隔离；存量归 default | ⏳ 下一阶段 |
| **A-3（v3.5）** | agent 写侧 tenantId 落地（`AgentAdminService.create`） | 租户私有 agent 可创建 | ⏳ 下一阶段 |
| **A-4（v3.5）** | picker 按 (tenant, kind) 集过滤候选；kind=all = chat ∪ task 并集 | 创建时声明天然 ⊆ 可用集 | ⏳ 下一阶段 |
| **A-5（v3.5 / v3.6）** | T0 装配 = 当前会话 (tenant, kind) **工具集配置**（full/retrieval 二选一，关联 §4.6；skill 声明不参与） | T0 面 = 工具集配置 = 模型真实可用面；chat 会话不见 task 工具 | ✅ full 口径已实现；⏳ retrieval 双层留后续 |
| **A-6（v3.7）** | `spawn_subagent` 增 `tool_ids` 参数（动态 sub agent 主 agent 自选工具集子集；越界剔除提示；缺省回退主 agent 同款集全量） | 委派可裁剪工具面；缺省行为不变 | ✅ 已实现（`parseToolIdsParam` + 缺省 `sameToolsAsMain`） |
| **A-7（v3.11/v3.12）** | `react.spawn-hint` 渲染 `{agents}` 携带各预定义智能体**已装配工具清单**（可读名）：预定义 agent 已自动装配声明工具，主 agent 无需代查业务数据，直接委派；动态 sub agent 才自选工具 | 主 agent 不因「无业务工具」拒绝委派/反向要数据 | ✅ 已实现（`AgentCatalogService.renderForSpawnHint` + v3.13 `skillLoadToken` 抽屉步骤） |
| **S-C（v3.8）** | 双阈值采纳：轨 A L3 输出置信度（复用 `confidence` + 相对差距 δ 判定）；`trigger` / `candidate` 阈值配置化（默认 `trigger` 高/关）；discoverable 层 `dynamicLoadable` 标记；候选版 `search_tools`（skill 域，加载后升级 triggered）；agent 可调度池 Top-K | trigger 直接触发 ≤1；候选动态加载零重建；agent 可调度不自动委派 | ✅ 已实现（`SkillAdoptionService` + `sunshine_search_skills`；分类器契约线上 v2；Nacos `skill-adoption` 默认关，开启后走 Live V16/V17） |
| **延期** | Redis、SOFT_CHAIN、processGraph、模型强制读 SKILL.md 才触发 | YAGNI | — |

**顺序建议**：S-0 → S-D + S-T（可同 PR 纪律）→ S-1。S-D/S-T 是相对 v3 的**根因修正**，优先于加厚 sticky 算法。**工具装配（v3.2/v3.6）随 S-T 一并落地**。**v3.5/v3.6/v3.7 授权与装配（A-1～A-6）**：A-1（skill 脱离 T0 装配、SUB 双轨求交）无前置、可先做；A-2（skill 租户）是 A-4 的前置；A-5（T0 = (tenant, kind) 工具集配置）依赖 A-1，与 §4.6 retrieval 双层联动；A-6（`tool_ids` 自选）仅涉及 `spawn_subagent` 参数面，可独立于 A-1 之后做。**S-C（v3.8）依赖 S-0/S-D（discoverable 候选层）与 S-T（trigger 阈值化）**，建议 S-T 落地后跟进。

---

## 7. 验收标准

| # | 场景 | 预期 | 状态 |
|---|------|------|------|
| V0 | 仅租户启用、无 `/`、无高置信 | Prompt 有目录名+描述；**无**任意 skill 全文 overlay | ✅ `PromptComposerTest` |
| V1 | `/skill-A` | triggered=A，全文 overlay；可 mount | ✅ `RoutingStickyServiceTest` + `ReactExecutorTest` |
| V2 | 上轮已触发 A，本轮「继续」无 `/` | 仍 triggered=A（sticky） | ✅ Live `verify_skill_sticky_live.py` T2 |
| V3 | embedding 召回 B 但未 L0/未高置信 | B 可出现在目录/排序；**不**自动全文 overlay | ✅ `PromptComposerTest`（目录含名+描述、无全文） |
| V4 | 「退出技能」/ 换题 | 清空 triggered；目录仍可发现 | ✅ 同 S-1 替换语义（L0 整表替换） |
| V5 | HITL / 续跑 | 复用 triggered，不丢 | ✅ `ConversationServiceTest.loadRoutingSeed_*` |
| V6 | workflow / SUB | 无父 sticky；SUB 不继承 | ✅ `RoutingStickyServiceTest`（workflow 不套 seed） |
| V7 | 软链自动切下一 skill | **不验收** | — |
| V8 | 主 agent 连续轮次无 L0 / 换题（v3.2 / v3.6） | T0 工具面（(tenant, kind) 工具集配置）跨轮**字节不变**（Tier 0 不失效）；triggered skill 变化仅动尾部 schema 召回区 | ⏳ retrieval 双层未落地（full 口径已覆盖） |
| V9 | SUB/Worker 绑 skill（v3.2 / v3.6 / v3.7） | 工具即时注入（可用性 ⊆ (tenant, kind) 集）；预定义 agent 自动注入 (tenant, kind) ∩ 声明，动态 sub 主 agent 自选子集 | ✅ `SpawnSubagentToolTest` 双轨三例 |
| V10 | `retrieval` 模式下连续轮次无 triggered 变化（v3.4 / v3.6） | 请求体 T0 工具名 stub（工具集配置）**字节不变**；仅尾部 schema 召回区变化；skill 声明命中 → **跳过相似读检索**直接加载；`search_tools` 流内触发**零重建** | ⏳ retrieval 双层未落地 |
| V11 | 感知面/注册面一致性（v3.4 / v3.6） | 单测：模型可见工具名 ⊆ 可执行注册集（同 (tenant, kind) 工具集渲染） | ⏳ 随 retrieval 双层 |
| V12 | skill 声明工具 ⊆ 租户集、不进 T0（v3.5 / v3.6） | 声明跨出 (tenant, kind) 集 → 召回校验剔除；主 agent T0 不含任何 skill 声明工具；`missing` 日志不出现跨租户工具 | ✅ `DynamicToolkitFactoryTest` A-1/A-5（schema 召回剔除） |
| V13 | skill 租户隔离（v3.5） | 租户 A 的 skill 目录/详情/工具绑定对租户 B 不可见；存量数据归 default 兼容 | ⏳ A-2~A-4 |
| V14 | T0 面 = (tenant, kind) 工具集配置（v3.5 / v3.6） | chat 会话 T0 无 task 专用工具；T0 字节随会话 kind 恒定（skill 触发不影响）；`kind=all` 声明候选 = chat ∪ task 并集 | ✅ full 口径（T0 = 工具集配置，skill 声明不并集） |
| V15 | 委派子 agent 工具召集双轨（v3.7） | 预定义 agent：注入 = (tenant, kind) ∩ 声明（跨出剔除）；动态 sub：主 agent 传 `tool_ids` → 子 agent 仅见该子集，越界 id 运行时剔除 + 提示；不传 = 主 agent 同款集全量 | ✅ `SpawnSubagentToolTest` |
| V16 | 双阈值采纳（v3.8） | 置信 > `trigger` → 直接触发最高 ≤1 个（相对差距 δ 校验）；`candidate < 置信 ≤ trigger` → 仅 discoverable 候选（名+描述），模型显式动态加载后升级 triggered；agent 置信 ≥ `candidate` → 可调度池（Top-K），不自动委派 | ✅ 单测覆盖（`SkillAdoptionServiceTest`）；⏳ 开启开关后 Live |
| V17 | 候选 ≠ 触发底线（v3.8） | 候选永不进 T0 overlay；动态加载只动 Tier 2 尾部（V10 请求体字节稳定仍成立）；候选版 `search_tools` 为唯一动态加载入口；触发集公式不含低置信候选 | ✅ 单测覆盖（`SkillSearchToolTest`）；⏳ 开启开关后 Live |
| V18 | L0 短路 / 多资源处置（v3.9） | 命中任意 `/skill` 或 `$agent` → 规则层（L1）与 L3 均不执行（trace 无 rule/L3、无额外 LLM 调用）；多 `/skill` 只 trigger 第一个、其余丢弃；多 `$agent` 全部进可调度池（不自动委派） | ✅ `ForcedExecutionRouter` L0 短路 |
| V19 | agentIds 跨轮 sticky（v3.10） | 上轮指定/候选 agent（`$agent`、L1 规则、L3 候选）跨轮接续：本轮无新候选时继承不变（主 agent 持续可 spawn）；新一轮候选（L0 `$agent` 或 L3 重选）→ 整表替换；退出/换题 → 清空；HITL/续跑经 RoutingResult 复用不丢；seed 不进 L1 压缩 | ✅ `RoutingStickyServiceTest` + Live T4/T5 |
| V20 | spawn-hint 工具清单渲染（v3.11/v3.12）→ 子 agent 抽屉 skill 步骤（v3.13） | `{agents}` 渲染每个预定义智能体附带「已装配工具」可读名清单；委派提示声明「子智能体自行调用工具获取数据」；`$compliance-agent 查我的报销` 类消息主 agent **直接 spawn 委派**，不因「无业务工具」拒绝委派或反向向用户索要数据；子 agent 抽屉 subSteps 首行展示 **skill 加载步骤**（`phase=skill`，「已加载 Skill compliance-check 业务合规检查」） | ✅ `AgentCatalogService.renderForSpawnHint` 单测 + `SpawnSubagentToolTest` skillLoadToken |

---

## 8. 风险与对策

| 风险 | 对策 |
|------|------|
| 目录过长占前缀 | Top-N + 「更多经 / 或检索」；对齐 task-scene 字节稳定 |
| L3 乱触发 | 默认关高置信自动触发；先 L0+sticky |
| 误以 mount=触发 | 文档+单测：仅 mount 无 overlay |
| 压缩后丢触发态 | S-0 消息 SSOT；∉ L1 折叠 |
| 与旧「召回即绑定」习惯冲突 | 验收 V0/V3；改 routing §10 文案 |
| **工具面膨胀（v3.2 / v3.6）** | 主 agent T0 = (tenant, kind) 工具集配置，规模受工具集成员约束（≤ 阈值走 `full`，否则 `retrieval`，见 [五层 §5.5.3 v6](./2026-07-31-unified-context-compression-design.md)）；skill 声明召回有界（仅 triggered 集声明工具） |
| **stub 名列表与执行注册不一致（v3.4 / v3.6）** | 感知面/注册面**必须同源**——stub 名来自 (tenant, kind) 工具集配置渲染，禁止手工维护两套名单；验收 V11 单测校验「模型可见名 ⊆ 可执行注册」 |
| **`retrieval` 模式工具面字节漂移（v3.4）** | 名 stub 确定性排序（不依赖 `ConcurrentHashMap.values()` 迭代序——后者无契约保证，跨进程/实现变更可能漂移致 Tier 0 静默失效）；必要处按名排序渲染 + 验收 V10 请求体对比 |
| **ToolGroup 中间激活滥用（v3.4）** | 一次激活 = 一次全量 prefix 重建，非默认路径；如用限频（仅 L0 / 显式 `/skill` / 单次高置信），并把每次激活成本计入预算 |
| **声明与可用集脱节（v3.5 / v3.6）** | 创建时搜索候选即 (tenant, kind) 集（A-4）+ 运行时召回校验（A-1）双保险；skill 声明工具不在集内 → 召回空，前端提示 + 运行时降级（仅丢该 skill 的 schema 召回，不影响 T0） |
| **skill 存量数据无租户（v3.5）** | 迁移策略：存量 `skill_definition` 归 `default` 租户（与 agent 现有 `tenant_id=default` 语义一致），租户 A 读不到 default 之外 skill |
| **kind=all 工具进装配面（v3.5）** | 禁止——all 仅声明候选/审计；装配面恒为 (tenant, kind) 集，防止 chat 会话出现 task 工具 stub |
| **v3.6 回归：skill 工具不再常驻 T0（行为变更）** | 主 agent 场景 skill 工具从「前缀并集」变「尾部召回」——工具 ≤ 20 时 `full` 全量 schema 仍在 T0，无感知；> 20 时依赖声明命中 / `search_tools` 才见 schema；验收 V8/V10 覆盖字节稳定，声明命中路径覆盖 V12 |
| **动态 sub agent 工具自选越界（v3.7）** | `tool_ids` 运行时 ⊆ 集校验 + 越界剔除回提示（复用 A-1 求交路径）；预定义 agent 自动注入路径不受影响；验收 V15 |
| **候选动态加载滥用（v3.8）** | 候选只以名+描述暴露 + Top-K 有界；动态加载唯一入口 = 模型显式元工具调用，只动 Tier 2 尾部（零 prefix 重建）；加载后升级 triggered 受退出/换题/L0 语义约束 |
| **trigger/candidate 阈值误设（v3.8）** | 置信度用 L3 classifier `confidence`（校准后），禁 L2 原始相似度（未校准、跨 skill 不可比）；`trigger` 默认高/默认关闭（先 L0+sticky）；相对差距 δ 防压线误触发 |

---

## 9. 关联文档

| 文档 | 关系 |
|------|------|
| [unified-routing v6](./2026-07-29-unified-routing-design.md) | 轨 A；`skillIds`=triggered；装配改 discover/trigger |
| [task-scene](./2026-08-01-task-scene-context-design.md) §7 | 名+描述 / 正文按需的直接依据 |
| [unified-context-compression](./2026-07-31-unified-context-compression-design.md) | 触发态不进 L1；Tier 0 `tools` 稳定（v24/v25） |
| [task-list-memory](./2026-08-14-task-list-memory-unification-design.md) | 换题清空 seed 时沉淀未完成任务到 KV Memory（v3.3 协同） |
| [business-context-authority](./2026-08-13-business-context-authority-design.md) | 触发稳定有助于 biz_scene；可发现≠ scene 乱跳 |
| [sandbox multi-skill](./archive/2026-07-16-conversation-sandbox-multi-skill-design.md) | 物料层 |
| Claude Code 官方「[Prompt caching is everything](https://claude.com/blog/lessons-from-building-claude-code-prompt-caching-is-everything)」 | §4.6 依据：静态工具面 + `defer_loading` / Tool Search；中间改工具集破坏 prefix 缓存 |
| [model-registry-config](./archive/2026-07-27-model-registry-config-design.md) | 多租户资源治理上下文（§4.7 授权分层的同源约束） |
| [service-consolidation](./archive/2026-08-03-service-consolidation-design.md) | tool-service / resource-manager 聚合归属（§4.7 A-2 skill 租户落点） |

### 归档备注

v1 过程图、v2 软链+ledger、v3「固定∪召回→一律 overlay」均废弃；以本 v3.1 为准。
