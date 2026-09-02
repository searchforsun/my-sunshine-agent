# 上下文压缩统一设计（五层渐进管道）

> 日期：2026-07-31
> 状态：**基线管道 ✅**（Layer 1–5 骨架已落地）· **§6.4 语义 merge ✅ 已实现**（2026-08-25）：写路径三阶段（字面快路径 → 跨 kind 全量 active 候选检索（2026-08-26 扩面，防同义跨 kind 漏判） → LLM 判定 NOOP/MERGE/UPDATE/CONFLICT，`L2SemanticMergeService` + Catalog `context.l2.merge`；task.* 结构键与开关关闭走字面回退；失败一律保守回退 NOOP）· **§5.5 压缩点模式 ✅ 主体落地**（2026-08-26）：**task×fast|pro 启用压缩点读/写路径**——`L1Compressor.partitionByPoint` 以 `far_folded_msg_ids` 为界、`ContextAssembler` Near 不从头部丢轮次、压缩重组 2+2+Far（`compression-point.near-keep/mid-keep-rounds`）；**chat 二期 ✅**（2026-08-26）：启用面扩至 `chat×fast|pro`，Near/Mid 参数按 kind 分化（chat 4+4+Far / task 2+2+Far≤10k，`chat-near-keep/mid-keep-rounds`；chat 无 ≤10k 硬预算，靠组装侧 Budget 退役并入收敛）；**§5.5 延后四项 ✅ 收口**：① 同步推进 P（assemble 超预算零 LLM 前移压缩点 + 本轮按新 P 重组）② Budget 退役并入（压缩点模式 applyBudget 丢 L3→退役 Mid 进 P→丢 Far 块，Near/L2 永不丢）③ ≤10k 硬预算（`task-post-compact-budget`，超限先降级最旧 Mid、再折叠最旧 Near 保底 1 轮）④ Tier 定序（scope-prompt 静态前置 / nodePrompt 尾部）；**P/S 分离**（`far_folded_msg_ids`=压缩点边界 / `far_summarized_msg_ids`=已折叠子集，间隙轮写路径异步补折叠；存量行 NULL 回退视为一致）；**workflow 继续滑动窗基线**（chat 二期已启用压缩点，§13.3 ①③⑥✅ ④✅ ⑮✅；**②⑤⑦ ✅**（2026-08-26：②⑤ 架构核实已满足 / ⑦ 幂等增益判定落地）；**⑫⑬⑭⑲ 工具轮 schema 行 ✅ + ⑮ 装载细化 ✅（2026-08-26：`ToolSchemaRenderer`/`TaskProcessRenderer` 确定性渲染 + Near 完整过程）**；余 ⬜ 仅 ⑩ tools 分层注入（超阈值增强）见 §13.3）· **其余 §5.5 / v2–v15 增强 ⬜ 设计稿未落地**（见 §13.3）· **v25 收敛**：CrossTurnCompact/T0 已删项、L2+W0→KV Memory、L3 语义提取延后 · **v26 L3 增强 ✅ 已实现**（2026-08-24）：语义提取层 / 相似度去重 / 定期维护 / task process 层向量化（落点 §7.4 / §9.2 / §13.4）
> **基线对照（2026-08-10 代码核实 · 2026-08-26 增补）**：Layer 1 = `HarnessAgentFactory` → AgentScope `CompactionConfig`（token 动态触发 + Catalog 摘要保留思考，§4.5）；Layer 2 = `L1Compressor`：task|chat × fast|pro 走**压缩点模式**（`partitionByPoint` 以 `far_folded_msg_ids` 为界，Near 只增不减；**同步推进 P ✅**——assemble 超预算零 LLM 前移压缩点，`far_folded_msg_ids`(P)/`far_summarized_msg_ids`(S) 分离，间隙轮写路径异步补折叠，§8.2；Near/Mid 参数按 kind 分化——task 2+2+Far≤10k / chat 4+4+Far），其余（workflow）走**滑动窗** Near/Mid/Far（`far_folded_msg_ids` 作 Far 增量折叠去重）；Layer 3/4 = `L2ExtractService` / `L3IngestService`+`L3RecallService`；Layer 5 = `applyBudget` 滑动窗**静默丢弃** L3→Far→Mid / 压缩点模式**退役并入** ✅（丢 L3 → 退役 Mid 进 P → 丢 Far 块）。读路径 `ContextAssembler`，写路径 `ContextWritePath`。
> **v2/v3 优化（2026-08-01 · 设计稿）**：§5.5 压缩点模式（L1 压缩点前移、L3 尾部动态段、Budget「丢」改「退役并入」）；§5.5.3 起 v3 分层修正——**按变化频率 Tier 0/1/2 分层**、幂等 upsert、T0 降频、意图尾部注入（业界调研见 §5.5.5）· 关联 [task-scene-context-design](./archive/2026-08-01-task-scene-context-design.md)
> **v8（2026-08-02 · 历史）**：曾设想 HIERARCHICAL 下 H1 拆 Tier——**已由 v10/S3 + rebuild S5 v4 作废**；原详设见 [archive/planner-harness](./archive/2026-07-31-planner-harness-loop-design.md)
> **v9（2026-08-05）**：场景隔离——chat 保留 L3（`scene=chat` 通道）、task 不读不写；task 不读用户 L2（写/读双侧路由，[task-scene §2.1/§6.3](./archive/2026-08-01-task-scene-context-design.md)）；同步 §5.5.3 图注记与 §5.5.7 差异表
> **v10（2026-08-05 · harness 简化决议 S3 覆盖）**：Planner-Executor 场景**不新建压缩点基建**——run 内压缩用 AgentScope 官方 `CompactionMiddleware`，跨轮 L1 压缩用既有 `L1Compressor`；H1 仅作为注入块放 query 前（Tier 2 尾部语义），rounds 超阈值时**简单截断为摘要**。§5.5.7「Planner-Worker 适配」注记与落地清单 ⑨ 中「H1 拆 Tier 1/2」的 v8 方案**作废**。本文 §5.5 压缩点模式仍为**设计目标**（基线仍是滑动窗 + Budget 丢弃；未落地前继续服务普通 ReAct / 静态 Workflow 的是基线管道，不是压缩点模式）。
> **v11（2026-08-07 · 压缩点模式落地细化）**：确定「**同步推进 P + 异步折叠**」衔接——assemble 超预算时**同步**前移 `far_folded_msg_ids` 压缩点（纯写库零 LLM），Mid/Far 的 LLM 摘要/折叠仍**异步**；`trimByTokens` 禁止再丢 Near 头部。task Mid 见 **v19 / task-scene §6.5 v9**（schema 骨架优先；旧「结论+过程要点」整轮散文路径作废）。task L3 写 `scene=task` + 不自动注入。详见 [task-scene §4.2.1/§6.4/§6.5](./archive/2026-08-01-task-scene-context-design.md)
> **v12（2026-08-07 · 代码引用化原则）**：task 场景记忆**永不存代码内容**——只存 ① 代码引用（`path:line`/`path#symbol`）② 工具调用结果摘要（截断 200 chars）③ 对话/决策；**不做** blob 锚点校验 / asOfCommit 水位 / git 状态轮询（代码内容不进记忆则「内容过期」根除，agent 按引用读实时代码即真相）。T0 状态块字段改 codeRefs/verifiedRefs、轨迹条目加 refs；session_search 扩为 body（消息对）+ process（`ProcessingStep` 工具摘要）两层、会话级 + 项目级双范围（§5.5.7 差异表 + §13.3 ⑬）。详见 [task-scene §6.1/§6.4](./archive/2026-08-01-task-scene-context-design.md)
> **v13（2026-08-07 · chat Near 只留正文）**：chat 场景 Near **只保留终态正文**（user/assistant），**不注入轮次内过程**（工具调用/reasoning/result）——对话语义下过程短、实时性要求高、工具交互少，正文已承载全部信息，过程注入纯耗 token。task 侧 Near 保留轮次内过程（骨架 + 预算内原文），细节在 [task-scene](./archive/2026-08-01-task-scene-context-design.md) v6 细化。同步 §5.5.3 注记 / §5.5.7 差异表「Near 内容」行 / §13.3 ⑭
> **v14（2026-08-07 · 压缩后重组 4+4+Far）**：修正 §5.5.2「Near→Mid 摘要」表述——压缩触发时按「**近 4 原文 + 次 4 摘要 + 其余折叠**」重组，不全部转 Mid（避免压缩后 Near 空窗、近期追问/修正细节丢失）。规则：
> - **标准（Near ≥ 8 轮）**：源 Near 1-4 → 新 Near（**原文保留**）；5-8 → 新 Mid（**user 原文 + assistant 压缩 1-3 句**）；9+ 与旧 Mid、旧 Far → 新 Far（LLM 折叠合并）
> - **Near 不足 8 轮但 ≥ 4（罕见）**：1-4 保留为 Near；5-N 与旧 Mid 前部**凑满 4 轮 Mid**；旧 Mid 剩余 + 旧 Far → 新 Far
> - **Near < 4 轮（极致压缩，预算极紧）**：仅保留**最近 1 轮原文**为 Near；其余 Near + 旧 Mid + 旧 Far 全部折叠进 Far
> 压缩点（`far_folded_msg_ids`）前移到保留 Near 之前 → 该次唯一 prefix 重建（C3）。`near-turns` 语义=压缩后保底原文轮数（默认 4）；两次压缩间 Near 仍涨至 80%/40 轮再触发。压缩本就一次重建，保近期原文边际成本≈0、收益是跨轮修正精确性。同步 §5.5.2 / §5.5.7 差异表 / §13.3 ⑭
> **v15（2026-08-07 · task 压缩后重组 2+2+Far ≤10k）**：task 场景压缩触发后重组为「**近 2 轮完整过程 + 次 2 轮过程骨架 + 其余折叠**」，并加**硬性总量预算 Near+Mid+Far ≤ 10k**（Nacos `context.l1.task-post-compact-budget`）；**不设单轮上限**（Near 职责是保留上下文，单轮怪物由压缩兜底：总量超限先降级第 2 轮完整过程为骨架、再激进折叠 Far）；工具结果三级分级——**读/执行类摘要 ≤200 chars + refs、写/改类保留输出原文**（AI 产物可精确复述改动细则）。**引用化只约束跨轮记忆块，不约束 Near 短期窗口**（写/改原文在 Near 内留存，滑出后进 Mid 骨架/Far 折叠/终态 T0/process 层仍只存引用+摘要）。详见 [task-scene §6.6](./archive/2026-08-01-task-scene-context-design.md) · 同步 §5.5.7 差异表 / §13.3 ⑮
> **v17（2026-08-10 · 启用面收窄）**：对齐 [unified-routing v6](./2026-07-29-unified-routing-design.md) 与 [task-scene §2.2 v8](./archive/2026-08-01-task-scene-context-design.md)——压缩点 / W0 / T0 / `session_search` **优先** `kind=task` × (`executionMode=fast`|`pro`)；**workflow 退出**本套增强；**pro 计划态以 H1 为 SSOT、砍重型 T0**；禁止 L3 自判 planMode 进上下文。§5.5.4 ④「chat/task 统一启用」改为「机制同构、落地分期」——**chat 二期 2026-08-26 已落地**（`compressionPointActive` 扩至 chat×fast|pro，Near/Mid 参数按 kind 分化 4+4，Live 验收近 9 轮折叠 2/4+4/rebuild-check PASS）。（注：文中 Near「中断感知」已占用 v16 标签，本条用 v17。）
> **v18（2026-08-10 · 吸收 4.7.8）**：[harness-loop-enhancement](./archive/2026-07-28-harness-loop-enhancement-design.md) **已归档**。其阶段五「全面启用 compaction」相对 §4.5 **方案 A 已落地**为过时/负优化；**run 内压缩 SSOT = 本文 §4.5**；跨轮 Phase 1 / `CrossTurnCompactMiddleware` 仍属本文 §4.6（后续增强），**不**另起 4.7.8 工程。AS 原生 `session_search`（run 内 JSONL）与自研 `sunshine_session_search`（跨轮 L3）撞名约定见 [task-scene §6.4](./archive/2026-08-01-task-scene-context-design.md)——当前仍 `disableMemoryTools()`，放开 AS `session_search` 为可选（须保留禁 `memory_get/search`）。
> **v19（2026-08-10 · Mid schema 优先）**：对齐 [task-scene §6.5/§6.6 v9](./archive/2026-08-01-task-scene-context-design.md)——task Mid「过程骨架」= **确定性工具 schema 行**（非默认 LLM 散文）；LLM 仅可选补决策句。工具结果压缩行业共识：结构化裁剪/离线 > 语义摘要。
> **v20（2026-08-10 · 用户状态 L2 宁缺毋滥）**：§6.0 硬原则 + §6.3.4 **key 场景化命名** + **`background` 背景字段**；抽取歧义默认不入库。语义 merge（§6.4）已落地（2026-08-25，代码 ✅）。
> **v21（2026-08-10 · 状态保真原则）**：压缩主目标 = **下一轮可续跑的执行状态保真**（§2.1）；省 token / KV 为硬约束非唯一目的。三标签 L-narr / L-state / L-seal **映射现有载体**，不新建平行存储。细则与 task 侧启用面见 [task-scene §2.0](./archive/2026-08-01-task-scene-context-design.md)。
> **v22（2026-08-10 · L2 值形态自解释）**：结构化结果须 **key + value + background** 三件套均可指认；禁止裸 key / 布尔孤值 / 会话级计划进用户 L2（线上反例见 §6.3.5）。叠加 v20。
> **v23（2026-08-10 · chat 工具轮 schema）**：chat Near **正文为主**；多工具业务轮次可追加 **确定性 schema 行**（非 LLM 中间摘要、非 task 完整过程窗）；Mid 同口径保留 schema + 结论语义压缩；跨会话关键态仍走 L2（宁缺毋滥）。见 §5.5.8。
> **v24（2026-08-14 · skill 动态工具 sticky 化）**：主 agent 绑 skill 时工具并集**不得**按每轮最新 skillId 自由变化——须基于路由 **triggered 集**（[skill-sticky S-0/S-1](./2026-08-12-skill-sticky-process-chain-design.md)）**单调并集**：triggered 不变 → Tier 0 `tools` 字节不变；仅 triggered 变化（L0 整表替换 / 退出清空）时重建一次（C3 允许）。SUB/Worker 无前缀包袱不受限（即时并集）。装配契约见 [skill-sticky v3.2](./2026-08-12-skill-sticky-process-chain-design.md)。
> **v25（2026-08-14 · 清爽收敛，对齐 [task-scene v14](./archive/2026-08-01-task-scene-context-design.md) / [task-list-memory](./archive/2026-08-14-task-list-memory-unification-design.md)）**：
> 1. **L2 与 W0 统一为 KV Memory**（`scope=user|workspace` 列）：本文 L2（§6）与 task-scene W0 同表同模型同抽取服务（`context.memory.extract` 参数化）；Tier 1 由「L2 + W0」收敛为「KV Memory + Far/Mid」。
> 2. **会话级任务状态由 fast `react_task_board` 跨轮恢复 / pro H1 承接**：§5.5.3 Tier 1 的「T0 状态块」与 §5.5.7/§13.3 的 T0 相关条目**作废**（task-scene §6.1 T0 全套已废）；失败路径由任务 item `fail_reason` 承接。
> 3. **`CrossTurnCompactMiddleware`（§4.4/§4.6/§13.1）明确不做**：run 内压缩 SSOT = §4.5 AS `CompactionMiddleware` + tail 裁剪；跨轮走压缩点（§5.5）与 Budget 退役并入（§8.2），不再叠第三套。
> 4. **§6.4 L2 语义 merge / §7.4 L3 语义提取层标注二期可选/延后**：一期字面 + key 规范化门禁与现有分块召回已覆盖主路径。
> **整合**：`2026-07-17-autocontext-memory-design.md` + `2026-07-22-context-optimization-design.md` + `2026-07-24-dynamic-context-compression-design.md`（三者均已归档）

|> **v26（2026-08-18 · L3 增强升级 · 重新启用）**：原 v25 §7.4 标注「延后」的 L3 增强项升级为要做——
> 1. **L3 语义提取层**（§7.4.1）：对用户画像、历史任务结果、重要实时三类信息做 LLM 抽取并独立向量化，保证重要内容不被 L1/L5 压缩丢弃
> 2. **L3 相似度去重**（§7.4.3）：embedding 前 cosine 去重（>0.95 跳过、>0.85 合并），防同质化噪音
> 3. **L3 定期维护**（§9.2）：`ContextMaintenanceJob` 扩展 L3 维度——冲突向量打标 + 过期向量清理 + 与 L2 协同仲裁
> 4. **task process 层向量化**（§7.4.4）：恢复 task-scene §6.4 v5 设计——`ProcessingStep.result` 截断 200 chars 入库（`scene=task`、`layer=process`），扩 `session_search` 召回面
>
> §6.4 L2 语义 merge 仍维持 v25「二期可选」不动。
> 行业参考：Claude Code 五层渐进压缩 · Cursor 单层摘要 · Oracle 双层模式 · Mem0 LLM 记忆管理
>
> **v27（2026-08-27 · L2 kind 精简 12→9）**：`reasoning`/`option`/`interim_conclusion`/`topic` 合并为 **`process_note`**（§6.3.1 类别合并落地）——置信 0.65、TTL 7 天；存量四类行清理。代码：`VALID_KINDS`/`ContextWritePolicy`/`ContextProperties.L2` 收敛为 `process-note-min-confidence`/`process-note-ttl-days`；Catalog `context.memory.extract`/`context.l2.extract` kind 白名单同步 9 类。scope=user|workspace 两维不变（§6.3.2 topic 短 TTL 协调议题由合并天然消解）。
>
> **v27.1（2026-08-27 · 时间指代归一化）**：`context.memory.extract` 提示词加硬规则——value 中的相对时间指代（今天/明天/下周/最近/上个月/周三 等）必须换算为绝对日期 `YYYY-MM-DD`，当前日期经 `{today}` 占位注入（`L2ExtractService.buildSystemPrompt` 替换为 `LocalDate.now()`）；无法确定具体日期的禁止臆造、改为近似表述。防止跨会话召回读到漂移指代（L2 TTL 7~365 天）。
>
> **v28（2026-08-27 · L3 摘要化与 L2 对账）**：解决 L3 语义层与 L2 重复、以及 body 原文零散 chunk（user/assistant 一条一条）无摘要价值的问题。契约：**chat 场景 L3 只保留 semantic 摘要层**（body 原文层退役），task 保留 body+process（`session_search` 深挖原文依赖）。三处落地——① **语义提取摘要化（方案1）**：Catalog `context.l3.semantic-extract` v4，每段为摘要形式（合并同主题连续对话、保留 ID/数字/时间关键细节、每轮 ≤2 段）+ 排除 L2 已结构化覆盖内容；② **L2 写入对账（方案2）**：`LLMSemanticExtractor` 写 semantic 前读该用户 active L2 `stateValue`，语义段整段包含某条 L2 值 → abstain（强命中，查询失败保守不拦截）；③ **chat 召回/展示收敛**：`L3RecallService` layers body+semantic→仅 semantic，`listL3Entries` 面板仅 semantic 且 role 统一「Chunk」，Milvus `listByConv` 透出 layer。详见 §7.4 / §13.4.1。

---

## 1. 问题

长对话/长任务中上下文持续膨胀，最终溢出模型窗口导致信息丢失。膨胀分两条独立路径：

```
intra-turn（单次 ReAct run 内）:
  多轮 TOOL → RESULT 累积 → 推理上下文被撑爆

cross-turn（跨用户问答轮次）:
  多轮 USER → ASSISTANT 累积 → 窗口溢出 → 旧轮次丢失
```

旧方案（STM 滑动窗 + MTM 整会话摘要 + LTM 空壳画像）与语义不对齐且未统一处理两条路径。本 spec 将其作为**同一管道的不同层级**统一定义，参考 Claude Code 的渐进式懒降级策略。

---

## 2. 设计目标

| 做 | 不做 |
|----|------|
| 五层渐进压缩：从廉价到昂贵，从自动到按需 | 兼容/双写旧 STM Redis、MTM |
| Layer 1（intra-turn）：每轮自动清理工具结果 | 改 Timeline / SSE 工具结果展示 |
| Layer 2（cross-turn）：token 触发 Near/Mid/Far 窗口 | 对最终答案二次加工 |
| Layer 3（cross-session）：11 类结构化状态静默抽取 | 用户侧 HITL 确认 |
| Layer 4（cross-session）：向量检索历史细节 | 与企业知识库混用 collection |
| Layer 5（budget）：读时裁剪，极端兜底 | 新建独立 context 微服务 |
| Gateway `/v1/models` 暴露模型窗口 | — |
| 冲突/过期/定时 GC 防记忆腐败 | SUB/PLANNER 跨轮记忆 |

**核心原则**：
- **状态保真优先（v21）**：压缩是为了让下一轮 Agent 拿到**最少但足够可靠的执行状态**；省 token 是手段。详见 §2.1
- **硬约束**：预算上限 + **prefix 稳定**（C1–C3 / KV）；不得为「多塞状态」每轮重排中段
- **先轻后重**：廉价操作（工具结果清理）每轮自动跑，昂贵操作（LLM 摘要）最后触发
- **减少触发频率**：token > 80% 窗口才触发跨轮压缩（非轮次 > 16），绝大多数对话不压缩
- **原文存 MySQL**：压缩不可逆但原文可查；结构态是路标，不是唯一档案

### 2.1 状态保真原则（v21 · 薄约定）

> 调研结论经本地裁剪后吸收：**不**另建「四类核心信息」库；**不**对代码路径恢复 asOfCommit；**不**用 LLM 从摘要里二次捞 ID。

**双目标**：① 主目标——续跑时仍能选对下一步、守禁止项、复现关键证据；② 硬约束——窗口预算 + 跨轮 prefix 稳定。

**三标签（装配策略，非新 Layer）**：

| 标签 | 含义 | 允许 | 禁止 |
|------|------|------|------|
| **L-narr** | 解释性背景 / 闲聊叙事 | 语义摘要（chat Mid/Far；Far 粗折叠） | 把唯一目标/禁止/失败原因只留在散文里 |
| **L-state** | 影响下一步的执行态 | 结构化载体（fast 任务清单恢复块 / H1 / Mid schema / P0） | 默认 LLM 整轮散文替代结构块 |
| **L-seal** | 安全边界与外部 ID | 原样保留在 schema 行 / HITL·审计引用 | 改写确认原文；把订单号等写入**用户 L2** |

**不可丢信息 → 现有载体（映射，勿平行新建）**：

| 信息 | 落点 | 备注 |
|------|------|------|
| 用户目标 / 验收 / 禁止项 | **fast 任务清单恢复块**（`react_task_board`，[task-list-memory](./archive/2026-08-14-task-list-memory-unification-design.md)）· H1（pro）· P0 项目规范 | 建议字段 `goalEpoch`（目标变更世代）；禁止项可进 P0 或任务 item 的 constraints |
| 工具关键字段（path、*Id、exitCode、trace…） | Mid/Near **schema 行**白名单原样（**chat 见 §5.5.8**；task 见 task-scene §6.5） | 渲染规则保真，禁止「先摘要再捞回」 |
| 失败路径（试过/为何失败/测不过/权限拒） | **任务 item `status/fail_reason`**（fast 任务清单）· processTrail 已被替代（v25）；详见 [task-list-memory](./archive/2026-08-14-task-list-memory-unification-design.md) | 硬保留；重要性 ≥ 成功叙事 |
| 证据与时效 | RAG/外网：source + queriedAt 可短解释不可抹锚；**代码**：只留 refs，实时重读（§v12） | 代码路径**不**引入 asOf 水位 |
| Skill **触发**态（轻 sticky） | 消息完整 `RoutingResult.skillIds`（=triggered SSOT）；上轮触发集种子；全文 overlay 仅 triggered | **不进** L1 Near/Mid/Far；可发现目录（名+描述）另层、非绑定 SSOT。见 [skill-sticky v3.1](./2026-08-12-skill-sticky-process-chain-design.md) |

**明确不做**：对话窗 alone 作为唯一续跑判据（正确形态可以是瘦对话 + 胖任务清单块/H1）；chat 与 task 同一套「四类全留」（chat 仍 **正文为主 + 工具轮 schema**，见 §5.5.8，非 task 完整过程窗）。

**补充验收（§12）**：压缩/结构态快照后发起续跑——能否遵守禁止项、选对下一步、引用关键 path/Id/失败记录；**通过不要求**对话窗含全部历史原文。

---

## 3. 五层管道总览

```
每轮 LLM 调用前 / 每轮 assistant 完成后 / 每次读时组装：

 ┌── Layer 1  CompactionConfig            ✅ 基线（AS 官方；CrossTurnCompact v25 不做）
 │    触发: CompactionMiddleware（每轮自动，阈值见 Nacos auto-context）
 │    开销: 触发后含 LLM 摘要（非零）
 │    压缩: intra-turn ReAct 工具 / 消息流
 │
 ├── Layer 2  L1 Near/Mid/Far             ✅ 基线（滑动窗）· 压缩点模式 ✅（task×fast|pro：同步推进 P + P/S 分离，见 §5.5 / §13.3）
 │    触发: token > 80% 窗口 OR 轮次 > 40（异步）
 │    开销: 1-2 次 LLM 调用（Mid 摘要 + Far 折叠）；同步推进 P 零 LLM
 │    压缩: cross-turn 对话历史 → 三层窗口
 │
 ├── Layer 3  KV Memory 结构化状态        ✅ 基线抽取 · **宁缺毋滥 / key·value 自解释 / background / 语义 merge ✅ §6（v20/v22/§6.4）**
 │    触发: assistant completed（异步）
 │    开销: 1 次 LLM 调用
 │    压缩: 对话 → 结构化键值对（scope=user|workspace）
 │
 ├── Layer 4  L3 向量检索                 ✅ 基线（scene=chat|task 通道 / session_search ⬜ body+session 一期）
 │    触发: 每次读时 query → Milvus
 │    开销: 1 次 embedding + search
 │    压缩: 对话 → 语义 chunk → 按需召回
 │
 └── Layer 5  Budget 读时裁剪             ✅ 基线（静默丢弃）· 压缩点模式「退役并入」✅（§8.2：丢 L3 → 退役 Mid 进 P → 丢 Far 块）
      触发: 组装后 token > 80% 窗口
      开销: 零 LLM 调用（退役零 LLM，折叠异步）
      顺序: L3 → Mid 退役进压缩点（P 前移）→ Far 摘要块 → Near 永不丢
```

### 行业对照

| 本系统 | Claude Code | Cursor |
|--------|------------|--------|
| Layer 1 (intra-turn 工具) | Tier 2 MicroCompact（每轮自动） | 无公开细节 |
| Layer 2 (跨轮摘要) | Tier 5 Auto-Compact（LLM 摘要 9 区） | 单层 LLM 摘要 |
| Layer 3 (结构化状态) | 无 | 无 |
| Layer 4 (向量历史) | 无 | @past chats JSONL |
| Layer 5 (读时裁剪) | Tier 3 Context Collapse（可回滚） | 简单截断 |

### 触发时机总览（关键）

| Layer | 触发点 | 在 ReAct run **内部**触发？ | 在 assistant 完成后触发？ |
|-------|--------|:---:|:---:|
| **Layer 1** | PreReasoning hook（每轮 LLM 前） | ✅ 设计如此 | — |
| **Layer 2** (L1 压缩写) | `ContextLifecycle.onTurnCompleted(COMPLETED)` | ❌ | ✅ 异步 |
| **Layer 3** (L2 抽取写) | `ContextWritePath.runAsync()` | ❌ | ✅ 异步 |
| **Layer 4 读** (L3 召回) | `ContextAssembler.assemble()` — 下一条用户消息 | ❌ | — |
| **Layer 4 写** (L3 ingest) | `ContextWritePath.runAsync()` | ❌ | ✅ 异步 |
| **Layer 5** (Budget) | `ContextAssembler.assemble()` — 下一条用户消息 | ❌ | — |

**结论**：Layer 1 是**唯一**能在 ReAct run 内部防御上下文溢出的层。Layer 1 缺失意味着——如果一次 ReAct 多轮工具调用导致上下文超窗，**全系统无任何防御**，LLM 调用会被截断或报错。Layer 2-5 在下一条用户消息前根本不介入。

---

## 4. Layer 1 — run 内上下文压缩（intra-run）

> ✅ **`CompactionMiddleware` 一直在运行**，但配置过于保守从未触发。
> 优化方案：**三阶段一次原则**——Phase 0 tail 裁剪（KV-Cache 友好）→ Phase 1 唯一一次跨轮激进压缩 → Phase 2 tail 收缩（永不再跨轮）。

### 4.1 为什么 Layer 1 是必需的

五层管道中，Layer 2-5 的触发时机全部在 **assistant 消息完成后或下一条用户消息到达时**——它们在 ReAct run 内部不运行。只有 Layer 1 能在 run 内拦截上下文溢出。

### 4.2 当前实际状态

> ✅ **已恢复（2026-08-05 方案 A）**。AgentScope 2.0 的 `CompactionConfig` 经 `HarnessAgent.compaction()` 注入，以 `CompactionMiddleware` 形式在每轮 `onReasoning` 前运行；`buildCompactionConfig()` 已改为 **token 动态触发** + 保留思考的自定义摘要（见 §4.5）。

历史问题：早期 `buildCompactionConfig()` 只设 `triggerMessages=40`，而 `maxIters=20` 下消息数通常达不到，token 触发因 `contextWindowSize` 未显式配置（`deepseek-v4-pro` 不在 AgentScope `ModelContextWindows` 表）而退化到巨大 fallback，**实际永不触发**——长 run 中思考样例被长期上下文稀释 → 后段「先思考再行动」行为退化。

详见 `ReActAgentFactory.java:22-24` 注释——"压缩改在 P2 用原生 CompactionConfig 重做"，`HarnessAgentFactory` 已实施，仅阈值配错。

### 4.3 设计约束：KV Cache 经济学

这是决定一步还是多步压缩的关键因素。每次修改 `messages[]` 中 **prefix 位置的早期消息**（Mid/Far/System）→ 整个 KV Cache 从被修改处起**全部失效**：

```
┌─ KV Cache 友好操作（tail 修改）───────────────────────────────┐
│ messages: [System, Near(8), Mid(8), Far, tools...]            │
│ 修改最后几条 tool result → prefix 不变 → 缓存命中 ✅           │
│ 代价：零，延迟不变                                             │
└──────────────────────────────────────────────────────────────┘

┌─ KV Cache 敌对操作（prefix 修改）─────────────────────────────┐
│ messages: [System, Near(8), Mid(8), Far, tools...]            │
│ 修改 Far 或 Mid → prefix 变化 → 全量 KV cache 重建 💸          │
│ 代价：128k 模型 ~3s 延迟 + 全量输入 token 费用（¥0.014/1k）    │
└──────────────────────────────────────────────────────────────┘
```

**结论**：跨轮压缩（改 prefix）应该**整个 run 只做一次**，把多次分散的小跨轮压缩合并为一次集中大跨轮压缩。Tail 操作可以频繁做，零代价。

### 4.4 优化方案：三阶段一次原则

> **v25（2026-08-14 · 明确不做）**：`CrossTurnCompactMiddleware`（Phase 1 三阶段批处理，5 次 LLM + 双压缩点衔接）**不再实现**。run 内压缩 SSOT = §4.5 的 AS `CompactionMiddleware`（token 动态触发 + tail 裁剪）；跨轮压缩走压缩点模式（§5.5）+ Budget 退役并入（§8.2）。正文 4.4.1–4.4.6 保留作历史设计参考，**禁止按本节新建中间件**。

```
┌─ 整个 ReAct Run 的生命周期（128k 模型） ──────────────────────┐
│                                                              │
│ Phase 0：tail 裁剪（KV-Cache 友好，覆盖 0%-85%）               │
│   每轮 LLM 前：工具参数截断 + 工具结果裁剪（只改 tail）          │
│   prefix [System, Near, Mid, Far] 始终不变 → 缓存命中 ✅        │
│   → 对标 Claude Tier 1 (Microcompact)                        │
│                                                              │
│ Phase 1：唯一一次跨轮激进压缩（85% 触发，整个 run 仅此一次）     │
│   ┌─ 一次批处理 —————————————————————————————————————————─────┐│
│   │ a) L3 全清 → -8k  ⚡ 零语义损失（run 内不重新召回）         ││
│   │ b) Near: 8→4 原文 + 退役 4→Mid 压缩 → -18k  🔧 4 次 LLM    ││
│   │ c) Mid: 旧 8 轮 + 新 4 轮 → 保留 4 + 退役 8 轮              ││
│   │    + 旧 Far 一起 LLM 合并为新 Far  → -22k + 新Far +2k       ││
│   │    🔧 1 次 LLM 调用                                        ││
│   │ 总回收：~48k（98k → 50k）                                   ││
│   └───────────────────────────────────────────────────────────┘│
│   KV Cache：1 次全量重建（~3s 延迟）                            │
│   新 prefix = [System, Near(4), Mid(4), Far(合成)]              │
│   → 对标 Claude Tier 2+3+4 合并                                │
│                                                              │
│ Phase 2：tail 收缩（永不再跨轮压缩）                            │
│   保持 Phase 1 新 prefix 不变 → 缓存命中 ✅                     │
│   • PruneConfig 保护阈值从 40k → 20k                           │
│   • 工具结果截断从 2000 chars → 500 chars                      │
│   • 若仍溢出 → maxIters 硬上限兜底                              │
│   → 对标 Claude Tier 1（更激进）                                │
└──────────────────────────────────────────────────────────────┘
```

#### 4.4.1 Phase 0：tail 裁剪（常态化）

每轮 LLM 调用前，通过 `CompactionMiddleware` 的 `onReasoning` 自动执行。**只修改 messages[] 尾部的最新工具结果**——prefix 不变，KV Cache 始终命中。

```java
// buildCompactionConfig() —— 仅改为 token 动态模式
CompactionConfig.builder()
    .triggerTokens(0)                   // modelWindow - 20k = 108k（远高于 Phase 1）
    .keepTokens(-1)                     // 动态比例保留
    .pruneConfig(PruneConfig.builder()
        .protectTokens(40_000)           // Phase 0：保守保护 40k
        .minTokensToPrune(20_000)
        .build())
    .build()
```

> Phase 0 的 CompactionConfig 在 Phase 0 期间**几乎不会被触发**（108k 阈值很高），其 PruneConfig + TruncateArgsConfig 作为 tail 裁剪的兜底。实际 tail 裁剪更多由 AgentScope 内部的 Step 1a/1b 处理。

#### 4.4.2 Phase 1：唯一一次跨轮激进压缩（85% 触发）

通过新增的 `CrossTurnCompactMiddleware` 实现，位于 CompactionMiddleware **之前**。

```
触发条件：
  1. currentTokens > modelWindow × 0.85  （128k → 109k）
  2. compacted 标记为 false（本次 run 尚未执行过跨轮压缩）

操作（一次批处理，顺序执行）：

  Step A. L3 全清（零 LLM 调用）
    从 messages 中移除所有 L3 检索结果块（以 [检索上下文] 标记识别）
    释放 ~8k tokens
    理由：L3 在每个 run 开始时注入，run 内不会重新召回，清除零语义损失

  Step B. Near 8→4 + 退役 4→Mid（4 次 LLM 调用）
    保留最近 4 轮原文（Near）
    退役的 4 轮 → 每轮 LLM 压缩为 1-3 句（Mid 格式）
    ≈ -18k tokens

  Step C. 旧 Mid + 退役 Near + 旧 Far → 合并为新 Far（1 次 LLM 调用）
    旧 8 轮 Mid + Step B 新增 4 轮 = 12 轮 Mid
    → 保留最近 4 轮 Mid + 退役 8 轮
    → 退役 8 轮 + 旧 Far 内容 → LLM 摘要合并为新 Far
    旧 Far 中的跨轮摘要信息保留（非丢弃！）
    ≈ -22k + 新 Far +2k = 净回收 20k

  总成本：5 次 LLM 调用（4 次 Near→Mid + 1 次 Mid→Far） + 1 次 KV Cache 重建
  总回收：8k + 18k + 20k = 46k tokens（98k → 52k）

  结果标记：ctx.put("sunshine:cross-turn:compacted", true)
```

**为什么不触发 AgentScope 原生的 CompactionConfig LLM 摘要？**

AgentScope 的 `CompactionConfig` 对**所有** prefix 做一次 LLM 摘要（一个 prompt → 一个摘要块）。Phase 1 的分步策略（L3 清 → Near 降 → Mid 降 → Far 合成）与 Claude Code 的 9 段结构化摘要对齐，逐类型处理比全量 dump 给 LLM 质量高。

#### 4.4.3 Phase 2：tail 收缩（永不再跨轮）

Phase 1 执行后，`CrossTurnCompactMiddleware` 标记 `compacted=true`，所有后续调用跳过跨轮压缩，纯 tail 操作。

```java
// Phase 2：动态调整 CompactionConfig 参数
// 由 PhaseManager 在 Phase 1 完成后通过 RuntimeContext 注入
CompactionConfig.builder()
    .triggerTokens(0)                   // 保持不变（远高于 Phase 2 的 tail 裁剪触发的实际位置）
    .keepTokens(-1)
    .pruneConfig(PruneConfig.builder()
        .protectTokens(20_000)           // Phase 2：从 40k → 20k，更激进保护
        .minTokensToPrune(10_000)        // 降低触发门槛
        .build())
    .truncateArgsConfig(TruncateArgsConfig.builder()
        .maxChars(500)                   // Phase 2：从 2000 → 500 chars，更激进截断
        .build())
    .build()
```

若 Phase 2 tail 收缩仍无法控制溢出 → `maxIters` 硬上限兜底，当前默认 ReAct 5 轮 / Sub 8 轮。

#### 4.4.4 完整运行轨迹（128k 模型）

```
Layer 5 预算：96k（modelWindow × 0.75 = 96k）

┌─ Phase 0（常态）──────────────────────────────────────────────┐
│                                                              │
│ 注入 96k（System 12k + Near(8) 24k + Mid(8 压缩) 12k + Far 3k│
│          + L3 8k + 当前 query + 头尾 37k）                    │
│                                                              │
│ R1: LLM → search(5k)    → 累积 101k → 裁剪旧工具结果 → 97k    │
│ R2: LLM → read_file(8k) → 累积 105k → 裁剪 + 截断 → 98k      │
│ R3: LLM → read_file(12k)→ 累积 110k → 触发 Phase 1！          │
│                                                              │
│ ⚡ Phase 0 3 轮 LLM 调用全部缓存命中，零额外延迟                │
└──────────────────────────────────────────────────────────────┘

┌─ Phase 1（唯一一次跨轮压缩，109k 触发）──────────────────────┐
│                                                              │
│ Step A: 清 L3       → -8k  → 102k                            │
│ Step B: Near 8→4    → -18k →  84k    4 次 LLM 调用            │
│ Step C: Mid 12→4    → -12k →  72k                            │
│      + 退役 8 Mid + 旧 Far → 合并新 Far                       │
│         → -8k + 新 Far +2k = 净-6k →  66k   1 次 LLM 调用     │
│                                                              │
│ 🔧 5 次 LLM 调用（Step B 4 + Step C 1）                       │
│ 💸 1 次 KV Cache 全量重建（~3s）                               │
│ 📊 110k → 66k（回收 44k）                                     │
│                                                              │
│ 压缩后上下文结构：                                             │
│   System 12k + Near(4) 12k + Mid(4 压缩) 6k + Far(合成) 2k    │
│   + 当前 intra-turn + 头尾 ≈ 34k                              │
│                                                              │
│ 剩余可用空间：128k - 34k = 94k（相当于几乎全新的上下文窗口！）  │
│ ⚠️ 旧 Far 中的跨轮摘要信息已合并入新 Far，未丢失               │
└──────────────────────────────────────────────────────────────┘

┌─ Phase 2（永不再跨轮）────────────────────────────────────────┐
│                                                              │
│ 起点 66k + 可用 62k                                           │
│                                                              │
│ R4: LLM → edit(3k)     → 69k → 裁剪 tail → 66k               │
│ R5: LLM → grep(15k)    → 78k → 激进截断 500 chars → 65k      │
│ R6: LLM → 继续         → ...                                 │
│ ... 可持续到 maxIters 触发或自然完成                           │
│                                                              │
│ ⚡ Phase 2 所有 LLM 调用缓存命中（prefix 不变），零额外延迟     │
└──────────────────────────────────────────────────────────────┘
```

#### 4.4.5 为什么一次激进优于多次分散跨轮压缩

| 策略 | 跨轮压缩次数 | KV Cache 重建 | 总延迟 | LLM 摘要调用 | 最终上下文质量 |
|------|:---:|:---:|:---:|:---:|------|
| 多次分散（旧方案 L1a/L1b 交替） | 2-3 | 2-3 次 (~9s) | 分散但频繁 | 2-3 次 | 摘要套摘要，信息衰减 |
| **一次激进** | **1** | **1 次 (~3s)** | **集中一次** | **5 次**（但 context 完整） | **高**——压缩前有 3 轮完整上下文，旧 Far 信息不丢失 |

关键点：5 次 LLM 调用的总 token 量 ≈ 1 次全量摘要（每步处理的数据量远小于全量），但质量更好——逐类型精细化处理，且旧 Far 中的跨轮摘要信息通过合并 prompt 保留。

#### 4.4.6 与 Claude Code 对照

| Claude Code | Sunshine 一次原则 | 说明 |
|-------------|-----------------|------|
| Tier 1 MicroCompact（缓存重排） | —（AgentScope 内部） | 缓存层 |
| Tier 2 Snip（LRU 淘汰，多次触发） | Phase 1 集中清 L3 + Far | ✅ 等价，但合并为一次 |
| Tier 3 Context Collapse（分段摘要） | Phase 1: Near→Mid→Far 分步 | ✅ 等价 |
| Tier 4 Auto-Compact（LLM 摘要） | Phase 1: Mid→Far LLM 合并 | ✅ 等价，但分类型而非全量 dump |
| Tier 5 Reactive（413 恢复） | Phase 2 maxIters 兜底 | ✅ |

### 4.5 配置

> **实际落地（2026-08-05 方案 A · run 内唯一 SSOT）**：启用 AgentScope 原生 `CompactionConfig` 的 **token 动态触发 + tail 裁剪（truncateArgs/prune）+ offloadBeforeCompact + 保留思考的自定义摘要**；`flushBeforeCompact` **故意关闭**（省一次 LLM）。未实现下述 §4.6 的 `CrossTurnCompactMiddleware`（Phase 1 三阶段批处理，属后续增强）。原 4.7.8 阶段五「再全面启用一轮」**作废**（v18），勿重复施工。

**MemoryProperties.AutoContext** —— 实际字段（`HarnessAgentFactory.buildCompactionConfig()` 消费）：

```java
public static class AutoContext {
    private boolean enabled = true;

    // ── token 动态触发（方案 A）──────────────────────────────────
    private int triggerTokens = 0;          // 0=动态：effectiveTrigger = modelWindow - reserved
    private int reserved = 20_000;          // 摘要过程 token 缓冲
    private int keepTokens = -1;            // -1=动态保留 tail
    private int keepTokensMin = 2_000;
    private int keepTokensMax = 8_000;
    private double keepTokensRatio = 0.25;
    private boolean flushBeforeCompact = false;   // disableMemoryHooks，压缩前不做 LLM 记忆抽取
    private boolean offloadBeforeCompact = true;  // 压缩前原文落会话 JSONL
    private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;  // 保留思考的自定义摘要模板

    // ── tail 裁剪（非 LLM，常态操作）─────────────────────────────
    private boolean truncateArgsEnabled = true;
    private int truncateArgsMaxChars = 2_000;
    private boolean pruneEnabled = true;
    private int pruneProtectTokens = 40_000;
    private int pruneMinTokens = 20_000;
    private int pruneMaxOutputChars = 2_000;

    // ── 保留字段（消息数兜底 + 跨轮 L1）────────────────────────────
    private long msgThreshold = 0;          // 0=禁用消息数触发，仅 token 触发
    private int lastKeep = 12;
    private long maxToken = 128 * 1024;
    private double tokenRatio = 0.75;
    private long largePayloadThreshold = 5 * 1024;
    private int minConsecutiveToolMessages = 4;
    private double currentRoundCompressionRatio = 0.3;
    private int minCompressionTokenThreshold = 3000;
}
```

Nacos 对应配置（`sunshine-orchestrator.yaml`，`agent.model.context-window` 为动态触发依据）：

```yaml
agent:
  model:
    context-window: 200000    # 上下文窗口，须与实际模型一致（默认 200k）
  memory:
    auto-context:
      enabled: true
      msg-threshold: 0
      last-keep: 12
      trigger-tokens: 0
      reserved: 20000
      keep-tokens: -1
      keep-tokens-min: 2000
      keep-tokens-max: 8000
      keep-tokens-ratio: 0.25
      flush-before-compact: false
      offload-before-compact: true
      summary-prompt: ""      # 留空用内置保留思考模板；可覆盖
      truncate-args-enabled: true
      truncate-args-max-chars: 2000
      prune-enabled: true
      prune-protect-tokens: 40000
      prune-min-tokens: 20000
      prune-max-output-chars: 2000
```

**自定义摘要模板（DEFAULT_SUMMARY_PROMPT）**：AgentScope 默认模板丢弃 `ThinkingBlock`，导致压缩后模型失去「先思考再行动」样例 → 后段思考退化。方案 A 改为分段模板（SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS），并**明确要求归纳各轮思考要点**；`{messages}` 占位符保留原文供摘要。

Layer 5 预算联动调整（`sunshine-orchestrator.yaml`）：

```yaml
agent:
  context:
    l1:
      max-tokens-ratio: 0.75    # 96k（给 Phase 0 留 13k 缓冲到 Phase 1 109k）
```

### 4.6 实施清单

| 文件 | 操作 | 状态 | 说明 |
|------|------|:---:|------|
| `HarnessAgentFactory.buildCompactionConfig()` | 修改 | ✅ | token 动态触发（`triggerTokens=0`/`reserved`/`keepTokens=-1`+min/max/ratio）+ `PruneConfig` + `TruncateArgsConfig` + 保留思考的 `summaryPrompt`；指纹纳入全部压缩参数 |
| `MemoryProperties.AutoContext` | 修改 | ✅ | 新增方案 A 字段（见 §4.5） |
| `ReActAgentFactory.buildModel()` | 修改 | ✅ | 显式设置 `contextWindowSize`（Nacos `agent.model.context-window`，默认 200k），修正 AgentScope `ModelContextWindows` 缺表导致的 fallback |
| `mode-overlay.react`（Catalog v5） | 修改 | ✅ | 新增 【reasoning·限长】150 字软约束，控制思考量、缓解上下文膨胀 |
| **新增** `CrossTurnCompactMiddleware.java` | 新建 | ~~⏳ 后续增强~~ **v25 不做** | 已取消：run 内 SSOT=§4.5 AS compaction；跨轮=压缩点 §5.5 + Budget §8.2 |
| **修改** `ProcessingStepMiddlewareFactory.java` | 扩展 | ~~⏳ 后续增强~~ **v25 不做** | 同上；不再注入 CrossTurnCompactMiddleware |

#### 4.6.1 CrossTurnCompactMiddleware 核心算法

```java
@Override
public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx,
                                    ReasoningInput input,
                                    Function<ReasoningInput, Flux<AgentEvent>> next) {
    // Phase 1 已执行过 → 跳过（Phase 2 纯 tail 模式）
    if (Boolean.TRUE.equals(ctx.get("sunshine:cross-turn:compacted"))) {
        return next.apply(input);
    }

    long threshold = (long)(modelWindow * crossTurnRatio);
    long current = estimateTokens(input);

    if (current <= threshold) return next.apply(input);  // Phase 0，跳过

    if (log.isInfoEnabled()) {
        log.info("[Context] Phase 1 triggered: {}k/{}k ({}%)",
                 current / 1000, modelWindow / 1000, current * 100 / modelWindow);
    }

    List<Message> messages = input.getMessages();
    long before = current;

    // Step A: 清 L3（零 LLM）
    current -= evictL3Blocks(messages);

    // Step B: Near 8→4，退役 → Mid 压缩（4 次 LLM）
    current -= compactNearToMid(messages, nearKeepTurns);

    // Step C: Mid 缩为 4 + 退役 Mid + 旧 Far → LLM 合并为新 Far（1 次 LLM）
    // 旧 Far 内容不丢弃，与退役 Mid 一起送入合并 prompt
    current -= compactMidAndFarToNewFar(messages, midKeepTurns);

    ctx.put("sunshine:cross-turn:compacted", true);

    log.info("[Context] Phase 1 done: {}k → {}k (reclaimed {}k tokens, 5 LLM calls)",
             before / 1000, estimateTokens(messages) / 1000, 
             (before - estimateTokens(messages)) / 1000);

    return next.apply(input);
}
```

---

## 5. Layer 2 — L1 Near/Mid/Far（cross-turn 窗口）

> ✅ **已实现**。`L1Compressor` + `TokenEstimator` + `ModelWindowCache` + Gateway `/v1/models`

### 5.1 三层窗口

| 带 | 范围 | 注入形态 |
|----|------|----------|
| **Near** | 最近一次**压缩点**之后的原文轮次（v2 优化替代「最近 ~8 轮」滑动窗，见 §5.5） | `user` / `assistant` 原文 |
| **Mid** | 压缩点之前 ~8 轮 | `user` 原文 + `assistant` **LLM 压缩为 1-3 句** |
| **Far** | 更早全部 | **LLM 增量折叠**为边界摘要块；当前 query 命中时 L3 回填 |

- 原文 SSOT：MySQL `chat_message`（压缩不删原文）
- 派生：`conversation_context_l1`（mid_answers 映射 + far_summary + **far_folded_msg_ids 压缩点** + 窗口元数据）

### 5.2 触发

```
effectiveToken > modelWindow × 0.8  OR  轮次 > 40（宽限兜底）
```

- `effectiveToken` = jtokkit cl100k_base × 1.1（保守系数，替代原 `String.length()`）
- `modelWindow` = Gateway `GET /v1/models` 动态读取（→ `ModelWindowCache`）→ 不可用时降级 Nacos `defaultModelWindow`（128000）
- token 未到 80% 且轮次 < 40 → **不触发**（绝大多数对话）

### 5.3 自适应降级

压缩触发后，若组装估算仍超阈值，Near 逐轮缩小：

```
WHILE assembled > window × 0.8 AND nearRounds > 1:
  Near 最老一轮 → Mid 头部
  nearRounds--
  重新估算（Mid 摘要后 token ≈ 原文 × 0.15）

极端兜底（缩到 1 轮仍超）:
  applyBudget：L3 → Far → Mid 从头丢 → Near 永不丢
```

> **v2 优化（压缩点模式，§5.5）**：本节的「Near 逐轮缩小 / Near 头部移位」是 C2 敌对动作，仅在压缩点模式切换过渡期保留；切换后由 §5.5.3/§8.2 取代——tail 超预算直接触发一次压缩（压缩点前移），不再缩小 Near 头部。极端兜底顺序不变（L3 → Far → 退役并入 → Near 永不丢）。

### 5.4 实现文件

| 文件 | 模块 | 用途 |
|------|------|------|
| `L1Compressor.java` | orchestrator | `shouldCompress` token 触发 + `resolveNearRounds` 自适应降级 |
| `TokenEstimator.java` | orchestrator | jtokkit cl100k_base |
| `ModelWindowCache.java` | orchestrator | Gateway 模型窗口缓存 |
| `ContextAssembler.java` | orchestrator | `applyBudget` / `trimByTokens` |
| `ContextProperties.java` | orchestrator | `maxTokensRatio`/`turnBackstop`/`defaultModelWindow`/`tokenSafetyFactor`/`midCompressRatio` |
| `ModelController.java` | llm-gateway | `GET /v1/models` |
| ~~`ProviderProperties.java`~~ | llm-gateway | **已删除**（2026-08-10 模型注册表）。窗口/encoding SSOT = resource-manager `model_definition` → gateway `ModelRegistryCache` / orchestrator `ModelSceneResolver` |

---

### 5.5 压缩点模式（v2 优化 · 设计稿）

> 定位：把 L1 从「固定滑动窗」升级为「压缩点前移」，使**不触发压缩期间 messages 前缀完全稳定**，KV Cache 只 miss 尾部。机制 chat/task **同构**；**落地启用面以 [task-scene §2.2 v8](./archive/2026-08-01-task-scene-context-design.md) 为准**（优先 task×fast|pro；workflow 不做；chat 二期可选）。差异见 §5.5.7 + v17。

#### 5.5.1 动机：压缩低频 ≠ prefix 稳定

写路径（压缩）低频异步（§3 触发总览）不构成问题；但**读路径的每轮动作**若落在 messages 中段，即使未触发压缩也会让整个 prefix 失配。压缩点模式补足的是「不压缩时的稳定性」，而非「压缩的频次」。

判据（三层）：

| 判据 | 含义 | 违反后果 |
|------|------|----------|
| **C1 prefix 稳定** | 静态/历史层（System/L2/Far/Mid）跨轮逐字节不变 | 全量 prefill（~3s + 全量 token 费用） |
| **C2 tail 增量** | 新轮次只 append 尾部，中段不位移 | 中段之后全部失配 |
| **C3 压缩集中** | 跨轮压缩一次性触发，压缩点前移 | 多次重建前缀 |

#### 5.5.2 压缩点定义

**压缩点 = `conversation_context_l1.far_folded_msg_ids`（已折叠进 far_summary 的最大 msgId）**。既有字段复用，无需新表。

- **Near** = 压缩点之后的所有原文轮次（只增不减，直到触发压缩）
- **Mid/Far** = 压缩点之前（折叠 + 摘要，低频变更）
- 触发压缩时按「**近 4 原文 + 次 4 摘要 + 其余折叠**」重组（v14；**chat 工具轮 schema 见 v23 / §5.5.8**）：
  - **标准（Near ≥ 8 轮）**：源 Near 1-4 → 新 Near（原文保留，含已有 schema 行）；源 Near 5-8 → 新 Mid（user 原文 + assistant 压缩 1-3 句 + **原 schema 行原样**）；源 Near 9+ + 旧 Mid + 旧 Far → 新 Far（LLM 折叠合并；schema 关键 ids 优先保留再折叠叙事）
  - **Near 不足 8 轮但 ≥ 4（罕见）**：源 Near 1-4 → 新 Near；源 Near 5-N 与旧 Mid 前部**凑满 4 轮** → 新 Mid；旧 Mid 剩余 + 旧 Far → 新 Far
  - **Near < 4 轮（极致压缩）**：仅保留最近 1 轮原文为 Near；其余 Near + 旧 Mid + 旧 Far 全部折叠进 Far
  - 压缩点（`far_folded_msg_ids`）前移到保留 Near 之前 → 该次唯一 prefix 重建（C3）

#### 5.5.3 组装结构（每轮 · v3 修正）

> **v3 修正（2026-08-01）**：原稿把「W0 / L2 / T0」标为 C1 稳定是**错误的**——三者由 LLM 异步抽取，每轮都可能 upsert，放在 prefix 中段会让其后全部失效。
> 修正原则：**按变化频率分层，而非按语义层级**（对齐 Anthropic / vLLM / MemGPT 约束，详见 §5.5.5）。

```
Tier 0 · 绝对静态核（字节恒定，永不失效）
  tools（确定性序列化：排序后渲染；工具规模大时改「名列表静态 + schema 尾部」，见下方 v6 注记）
  + System base · scene/mode overlay
  + P0 项目规范（用户手动编辑时才变，单次失效可接受；仅 task）

Tier 1 · 低频记忆（content-hash 幂等 upsert，真变才失效一次）
  + KV Memory（scope=user：用户状态 + `todo`，幂等；仅 chat）
    （scope=workspace：工作区事实/约束/决策 + `todo`，幂等；仅 task——原 W0，v25 统一）
  + L1 Far/Mid 摘要（压缩时才变）

Tier 2 · 动态段（每轮 append / 每轮变，物理隔离）
  Near 原文（压缩点之后逐轮增长）
  + L3 召回（U 形排序：高相关放首尾，Lost-in-Middle 缓解；仅 chat，scene=chat 过滤）
  +【会话级任务清单恢复块】（fast：`react_task_board` 最近快照；仅 task×fast，见 [task-list-memory](./archive/2026-08-14-task-list-memory-unification-design.md)；原 T0 状态块作废 v25）
  +（可选）用户显式 executionMode 尾部 · **禁止** L3 自判 planMode（routing v6）
  + pro：H1 注入块（query 前）
  + user query（tail 末尾）
```

> **v9 注记（2026-08-05 场景隔离）**：上图为**最大并集**示意；实际按场景过滤——chat 注入 L2 + L3，不注入 W0/T0/P0；task 注入 W0 + P0（+ fast 的 T0 / pro 的 H1），不注入用户 L2、不召回 L3（[task-scene §2.1](./archive/2026-08-01-task-scene-context-design.md)）。
> **v25 修正**：上句 L2/W0/T0 读作 KV Memory scope=user/workspace + fast 任务清单恢复块（`react_task_board`）；T0 不再存在。
>
> **v17 注记（2026-08-10）**：再按 `executionMode` 裁剪——workflow 不注入 W0/T0/session_search；详见 [task-scene §2.2](./archive/2026-08-01-task-scene-context-design.md)。
>
> **v13 注记（2026-08-07 · Near 内容按场景差异）**：Tier 2 的「Near 原文」在 chat/task 下**内容不同**——chat **以 user/assistant 终态正文为主**（不注入 think/完整 tool dump）；**v23**：若该轮有工具且正文未复述关键字段，可追加 **schema 行**（§5.5.8），仍非 task 完整过程窗。task 保留**轮次内过程**（完整过程 + 骨架分级，[task-scene §6.6](./archive/2026-08-01-task-scene-context-design.md)）。**v14（chat 压缩后重组 4+4+Far）**：压缩触发时源 Near 1-4 保原文、5-8 转 Mid（user 原文 + assistant 压缩 **± schema 行**）、9+ 与旧 Mid/Far 折叠为新 Far；Near 不足 8 时 5-N 与旧 Mid 凑 4 轮；Near < 4 时极致压缩仅保 1 轮原文其余全 Far（详见 §5.5.2）；两次压缩间 Near 涨至 80%/40 轮再触发。**v15（task 压缩后重组 2+2+Far ≤10k）**：近 2 轮完整过程 + 次 2 轮骨架 + 其余折叠——详见 [task-scene §6.6](./archive/2026-08-01-task-scene-context-design.md)。

- **Tier 0 是双层缓存的内层稳定核**：Tier 1 任何一次真实变化只使外层失效，Tier 0 仍命中（two-level caching）
- **意图识别结果不注入 prefix**：它是路由决策（控制流）；需告知模型当前模式时，用尾部 system 消息
- **Lost-in-the-Middle 收敛**：Far/Mid 本就是「允许模糊」的历史摘要，放中间注意力洼地无损失；必须精确记得的（约束/目标/事实）放 Tier 0/1 头部——中间模糊区与 KV 稳定区天然重合
- 溢出处理：tail 超 `modelWindow × 0.8` → 触发一次压缩（C3），不再从 Near 头部丢轮次

> **v6 注记（tools 分层注入，对齐 [phase5 §5.5](./phase5-operation-openness-design.md)）**：工具规模膨胀（>50）时 naive 全量 schema 进 Tier 0 会推高 token；若改为每轮按 query 检索 Top-K 注入，则 `tools` 块每轮变化 → **Tier 0 失效 → 全量 miss**。折中：**Tier 0 只放「全量工具名列表」**（确定性序列化、字节稳定）+ **Tier 2 尾部放 Top-K 工具完整 schema**（随 query 动态）。工具规模 ≤ 阈值（默认 20）时仍用全量 schema 进 Tier 0（`full` 模式），二选一由 Nacos `agent.tool.inject` 切换。
>
> **v24 注记（skill 动态工具 sticky）**：主 agent 绑 skill 时并入 skill 声明工具，必须按路由 **triggered 集**单调并集（[skill-sticky v3.2](./2026-08-12-skill-sticky-process-chain-design.md)）——**禁止**每轮按最新 skillId 自由并集，否则 Tier 0 `tools` 逐轮变化全量 miss（正是本注记警告的场景）。SUB/Worker 即时并集不受限（子会话无前缀包袱，task-scene §7.4）。

#### 5.5.4 五条优化建议

**① L1 压缩点前移（C1/C2）**：`L1Compressor.partition` 由固定 near/mid 轮数改为以压缩点为界；Near 起点 = 最后一个折叠 msgId 之后。非压缩期 Near 只 append；`trimByTokens` 不再从头部丢轮次（避免破坏 prefix），溢出走压缩而非裁剪。

**② L3 尾部动态段（C2）**：L3 渲染位置固定约束为「当前 user query 之前」，见 §7.5。禁止在 Far/Mid 之间注入（其后全部失配）。

**③ Budget「丢」改「退役并入」（C3 + 保质量）**：见 §8.2。Mid 头部不再直接丢，先触发 Far 折叠（并入 far_summary、压缩点前移），折叠后仍超预算才丢 Far。让 Budget 成为压缩点推进的触发源之一，保住「原文可查」原则。

**④ 机制同构、落地分期（v17 覆盖原「全域统一启用」）**：压缩点仍是 **L1 通用机制**（chat/task 同构），但 **一期启用面** = `kind=task` × (`fast`|`pro`)（[routing v6](./2026-07-29-unified-routing-design.md) + [task-scene §2.2](./archive/2026-08-01-task-scene-context-design.md)）。**chat 二期 ✅ 已落地**（2026-08-26：启用面扩至 chat×fast|pro，Near/Mid 参数按 kind 分化——chat 4+4+Far / task 2+2+Far≤10k，无 ≤10k 硬预算、靠 Budget 退役并入收敛）；**workflow 退出**本套增强。场景差异：静态层（P0 / KV Memory workspace；fast 任务清单恢复块；pro→H1）+ L3（chat=`scene=chat`；task 写 `scene=task` 不自动注入）+ 写/读双侧 kind 闸门（§2.1）。

**⑤ 双压缩点衔接**：run 内压缩点（§4.4 Phase 1 后新 prefix 起点）与跨轮压缩点（far_folded_msg_ids）是两条独立线——run 内压缩**不落库、不移动 far_folded_msg_ids**；跨轮压缩在 assistant 完成后异步推进。二者互不干扰，实现时不得混淆（run 内压缩产物经 `ContextWritePath` 只取 user/assistant 角色入 history）。

#### 5.5.5 业界调研：动态状态 vs 前缀稳定（v3 设计稿）

> 触发：v3 修正发现原 §5.5.3 把 L2/W0/T0 标为稳定是错误的。以下为业界证据与落到本文的约束。

| 来源 | 结论 | 落地约束 |
|------|------|----------|
| Anthropic 官方 prompt-caching | prefix 逐字节匹配，**tools→system→messages 顺序固定**；中段任何变化全量失效 | 动态状态只能追加在 messages 尾部（§7.6）；tools 确定性序列化，中途不得增删 |
| Anthropic two-level caching | `system-only` 内层 + `system+context` 外层，外层 miss 内层仍命中 | 引入 Tier 0 内层稳定核 |
| vLLM Memory Hub 实证 | append 记忆也失效（分隔符/排序不稳定）；compiled + appendix 物理隔离、定宽分隔、阈值重编译才 98%+ 命中 | Tier 1/2 用定宽隔离与幂等重写，见 §5.5.6 |
| DeepSeek | 64-token 粒度缓存，优于整块 | 本项目 Gateway 透传 DeepSeek prefix caching |
| MemGPT | working context 固定大小可写块 + FIFO；外部 recall/archival 按需检索 | T0 有界块、随压缩点降频刷新 |
| Lost in the Middle（Stanford 2023） | 长上下文中间注意力最差（U 形），高相关放首尾 | Far/Mid 摘要放中段（允许模糊）；关键约束/目标放 Tier 0/1 |

**五条落地约束（本文 spec 级）：**

1. **按变化频率分层**：Tier 0（静态核，永不失效）→ Tier 1（低频记忆，幂等重写）→ Tier 2（动态段，每轮 append）。禁止把高频变化块放进 Tier 0/1 之间的位置。
2. **意图识别不进 prefix**：路由决策为控制流；模型需知当前模式时，以尾部 system 消息注入（Anthropic mode-switch 模式）。
3. **content-hash 幂等 upsert**：L2/W0 抽取后做 hash 比对，未变化不写库 → 组装字节不变 → 缓存不失效（§5.5.6）。
4. **确定性序列化**：所有注入块 JSON 键排序、无时间戳、无 session id、固定字段顺序；否则「相同数据不同字节」依然全 miss。
5. **Lost-in-Middle 布局**：中间段只放允许模糊的 Far/Mid 摘要；精确记忆（约束/目标/事实）置于头部高注意力区。

#### 5.5.6 幂等 upsert 与定宽隔离（v3 设计稿 · ✅ 核心落地 2026-08-26）

> 落实 §5.5.5 约束 3/4 与 vLLM 实证结论：记忆层「低频」必须是工程可保证的，而非假设。
>
> **落地差异（2026-08-26）**：以**字段级增益判定**替代 content-hash 列——L2 字面快路径同 key+value 时，`L2StateStore.refreshSameValue` 仅在候选带来实际增益（更高置信 / 新背景 / 新溯源）才刷新 `updatedAt` 写库，否则**零写**；效果等价（重复陈述不产生任何写与时间戳漂移），且省一列一算。确定性序列化无需额外工程：`renderSystemBlock` 本就只渲染 `(kind, key, value, background)` 四元组并按 `(kind, key)` 定序（无时间戳/session id/置信），同数据必同字节。L1 侧 `ConversationContextL1Store.upsert` 仅压缩窗口溢出触发、每轮必新增消息 → 不存在「未变化回写」路径。定宽 appendix 重编译依赖 KV Memory Hub 业务，暂不启用。

- **content-hash 幂等**：L2/W0 抽取服务每次产出结构化块后计算 `sha256(content)`，与 `conversation_context_l1`/`workspace_context_state` 现存块的 `content_hash` 比对；**未变化 → 跳过写库**，`assemble` 读到的字节不变 → 缓存不失效。
- **版本标签**：块体变更时更新 `content_hash` + `version`；`assemble` 用 `(kind, key, version)` 确定性拼接，避免「数据相同、序列化不同」造成的无效失效。
- **定宽隔离**：Tier 1/2 的附加项（如 W0 新增键值）追加到该块的 appendix 段，用固定宽度分隔符与 compiled 段隔离；appendix 超阈值（≥5 条或 ≥30% 总量）才触发一次整体重编译（新稳定前缀）——对齐 vLLM Memory Hub 修复方案。

---

#### 5.5.7 chat/task 差异收敛表

| 维度 | chat | task | 压缩点模式是否差异 |
|------|------|------|:---:|
| 执行路径（v17） | 用户显式 `fast`/`pro`/`workflow`（[routing v6](./2026-07-29-unified-routing-design.md)）；chat 侧以 fast/pro 为主 | 同轴；**一期压缩点**优先 task×(fast\|pro)；workflow 退出本套 | ❌ 机制同构 / ✅ 启用分期 |
| L1 窗口 | 压缩点前移（二期可选） | 压缩点前移（一期优先） | ❌ 机制同构 |
| Tier 0 | base + overlay + tools | base + overlay.task + mode overlay（fast→react / pro→harness）+ **P0** + tools | ✅ 差异（内容） |
| Tier 1 | KV Memory（scope=user）+ Far/Mid | KV Memory（scope=workspace）+ Far/Mid（不读 scope=user）；**fast 任务清单恢复块在 Tier 2**；pro 计划态→H1（Tier 2） | ✅ 差异（内容 + 模式） |
| L3 | 保留（scene=chat 隔离通道） | **写 `scene=task`（body 消息对；process 层与 scope=workspace 延后 v14）+ 不自动注入**（读路由闸门，[task-scene §6.4](./archive/2026-08-01-task-scene-context-design.md)）；`session_search` 一期 scope=session | ✅ 差异（开关 + 通道隔离） |
| Mid 摘要 / 骨架 | **结论语义为主**（`context.l1.mid-compress`）+ **有工具则保留 schema 行**（§5.5.8 v23；非整轮过程骨架） | **schema 过程骨架优先**（tool 行确定性渲染；可选 `context.l1.mid-compress.task` 仅补决策句，[task-scene §6.5 v9](./archive/2026-08-01-task-scene-context-design.md)） | ✅ 差异（装配路径） |
| 记忆存内容 | 对话/偏好原文、语义向量；业务关键态优先 **KV Memory scope=user**（§6）或轮内 **schema**（§5.5.8） | **代码引用化（v12）**：KV workspace 记忆/任务清单 item 只存引用（`path:line`/`path#symbol`）+ 结果摘要（≤200 chars）；**永不存代码内容**；无 blob 锚点/asOfCommit 水位。**仅约束跨轮记忆块**——Near 原文与 `chat_message` 原始记录完整保留（[task-scene §6.1](./archive/2026-08-01-task-scene-context-design.md)） | ✅ 差异（内容边界） |
| Near 内容（v13/v14/v15/v19/**v23**） | **正文为主**（user/assistant）；无 think / 无完整 tool dump；**工具轮可追加 schema 行**（确定性，§5.5.8）；**压缩后重组 4+4+Far**（近 4 原文 ±schema + 次 4 语义结论 ±schema + 其余折叠）；可选「短轮不占名额」。**中断感知（v16）**见下 | **压缩后重组 2+2+Far ≤ 10k**（近 2 轮完整过程 + 次 2 轮 **schema 骨架** + 其余折叠）；总量硬限；**读/执行摘要+refs、写/改 Near 原文 / Mid 变更摘要+refs**；Mid **禁止**默认工具散文摘要（[task-scene §6.6 v9](./archive/2026-08-01-task-scene-context-design.md)）；**中断感知（v16）**同 chat | ✅ 差异（内容 + 装载路径） |
| run 内 Layer 1 | 三阶段一次（§4.4） | 同 | ❌ 统一 |

> **Planner-Worker 适配**：用户选 **pro** 时 Planner 是唯一带跨轮前缀包袱的角色，按本表分层并追加 H1（query 前）——见 [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md)。Worker/子 Agent 无前缀包袱。分解模式枚举已取消（S5 v4）；旧稿 [archive/planner-harness](./archive/2026-07-31-planner-harness-loop-design.md)。
>
> **v8 注记（H1 拆分 · 历史）**：曾设想 HIERARCHICAL 下骨架进 Tier 1 / 细节进 Tier 2——**已废**。
>
> **v10 + v17**：H1 不拆 Tier、不建专用压缩基建；**整体 query 前注入**；pro **不做重型 T0**。跨轮 L1 仍 `L1Compressor`；run 内用 AS `CompactionMiddleware`。
>
> **v11（2026-08-07 · Planner/Worker 上下文契约，对齐 [rebuild §3.1.1](./2026-08-05-planner-executor-rebuild-design.md)）**：① **Planner 的 L1 组装与普通 ReAct MAIN 完全一致**（复用 `ContextAssembler.assemble`，按 scene 走本表 Near 差异：chat 4+4+Far / task 2+2+Far≤10k），差异仅追加 H1 注入块（query 前）+ Worker handoff（run 内视同 tool_result）。② **Worker 无压缩点包袱**——`forWorker()` 只含任务契约 + 定向上游，**不注入 L2 用户画像**，内部 ReAct 循环由 AS `CompactionMiddleware` 管控（S 域有界）。③ H1 注入块内部**两级**（当前计划摘要 + 近 `near-keep-rounds` 轮原文，超阈值折叠为摘要；**无**阶段骨架），与 L1 压缩窗口无关。
>
> **v16（2026-08-07 · 中断感知，方案 A 已实现）**：`chat_message.status=INTERRUPTED` 的 assistant 条在**装载层**（`ChatStreamContextFactory.prepareNewMessage` / `buildResumePreparation`）折叠为显式中断注记——正文非空时「注记 + 已生成部分」、空时仅注记（保证不被 `hasText` 过滤）。注记正文 SSOT = Catalog `context.l1.interrupted-marker`（缺 id/读取失败降级保留原文）；COMPLETED/FAILED 与 user 条不变。chat/task 统一生效；对 KV 前缀无破坏（仅 tail 的 INTERRUPTED 条变化，COMPLETED 条字节不变）。

#### 5.5.8 chat Near / Mid schema（v23 · 多工具业务）

> **问题**：chat Near「正文-only」（v13）对闲聊/少工具合理；对 **OA/财务等工具轮**，正文常漏单据号、失败 exit、HITL 关键字段 → 下一轮续跑靠猜。  
> **不做**：把 chat 改成 task 的完整过程窗（think + 全量 tool dump + 写/改原文）。  
> **定稿**：对话主轴仍是 **user / assistant 终态正文**；工具轮用 **确定性 schema 行**补关键态；跨会话仍成立的业务偏好/约束走 **L2（§6 宁缺毋滥）**。

**触发**：该轮 `ProcessingStep`（或等价 tool 块）非空，且存在白名单关键字段（见下）。无工具轮 → 仍纯正文，不加空 schema。

**Near 轮结构（chat）**：

```
user: 原文
tools?:  0..N 行 schema（仅工具轮；确定性渲染，零 LLM）
assistant: 终态正文
# 禁止：think / reasoning 全文；完整 tool_result dump；task 式写/改原文窗
```

**Mid 轮结构（chat · 滑出 Near 后）**：

```
user: 原文（可短）
tools?:  同形 schema 行（原样保留，禁止再 LLM 改写工具字段）
assistant: context.l1.mid-compress → 结论优先 1–3 句（只压叙事，不压 schema）
```

**schema 行（与 task Mid 同形子集，字段白名单）**：

```
[toolName] keyArgs=… status=ok|fail|denied exit=? · result≤200 · ids=[orderId|approvalId|…]
```

| 字段 | 规则 |
|------|------|
| `toolName` / `status` / `exit` | 原样 |
| `keyArgs` | 白名单入参（金额、单据类型、员工号等）；禁止整段 payload |
| `result` | ≤200 chars 机械截断，非语义摘要 |
| `ids` | 业务 ID 白名单原样（L-seal）；禁止写入用户 L2 当「开关」 |

**与 L2 的分工（硬）**：

| 信息 | 落点 | 例 |
|------|------|-----|
| 本轮/本会话工具关键态 | **Near/Mid schema** | 审批单号、本轮查单失败 exit |
| 跨会话仍成立的用户业务事实 | **L2**（§6.0/v22） | `finance.default_cost_center=…` |
| 对话结论与解释 | **assistant 正文**（Mid 可语义压缩） | 「已提交，请在 OA 确认」 |
| 完整工具轨迹 / 推理 | **不进** chat Near/Mid；档案在 `chat_message` | — |

**装载**：`L1Compressor` / SessionTurn 按 `kind=chat`：默认不折叠 steps；**有工具时**从 steps 抽 schema 行附在该轮（Near 装载与 Mid 退役同口径）。Catalog `context.l1.mid-compress` **不得**接收完整 tool dump；若需决策句，输入仅限正文线索（chat 默认不启用 task 的 `mid-compress.task`）。

**明确不做**：chat Near = task 2 轮完整过程；对 chat tool_result 默认 LLM 中间摘要；把会话单据号塞进用户 L2 裸 key（§6.3.5 反例）。

> **✅ 已落地（2026-08-26）**：`ToolSchemaRenderer`（steps JSON → 确定性 schema 行，零 LLM）在四处历史构建点统一装载（`ChatStreamContextFactory` 新建/续跑 · `ContextWritePath` · `ContextAdminService` rebuild/print）——assistant 消息从 `chat_message.steps` 渲染 `[toolName] keyArgs=… status=ok|fail|denied exit=? · result≤200 · refs=[path]`；写/改类工具省略 result（禁止 patch 原文），沙箱路径进 refs。Near/Mid 均以 `SessionTurn.toolSchemaLines` 原样附加（未经 LLM 改写）；`StepMetadata.toolArgs`（白名单标量入参，`ToolArgsRenderer`）+ `toolExitCode`（`SandboxExitCodeHolder`）在工具步收口时落库。落地差异：① `ids=[…]` 未单列——业务 ID 字段（orderId/approvalId/billId/…）并入 `keyArgs` 白名单渲染；② schema 行附于 assistant 内容尾部（user → assistant「摘要/正文 + schema」），未插入独立 tools 消息（独立 role 会被 `appendTurns` 过滤，信息完整性与确定性不受影响）。chat 滑动窗 Mid 保持 LLM 结论压缩；task 压缩点 Mid 改 **机械短结论**（`extractShortConclusion` 前 2 句 ≤120 字，零 LLM，§6.5 决策句 LLM 分支保持默认关）。

---

## 6. Layer 3 — KV Memory 结构化状态（cross-session · 原 L2 用户状态）

> **基线 ✅**：`L2ExtractService` + `L2StateStore`（字面同 key 合并 + 置信门禁）。  
> **增强 ✅**：§6.0 宁缺毋滥 · §6.3.4 key/`background` · **§6.3.5 value 自解释** · **§6.4 语义 merge（2026-08-25 落地）**；task 写隔离见 [task-scene §2.1](./archive/2026-08-01-task-scene-context-design.md)。
> **v25（2026-08-14 · L2 与 W0 统一）**：本层扩展为 **KV Memory**——`user_context_state` 加 `scope=user|workspace` 列，workspace 行（原 W0）同表同模型、抽取服务 `context.memory.extract` 按 scope 参数化；`kind` 新增 `todo`（未完成任务清单，见 [task-list-memory](./archive/2026-08-14-task-list-memory-unification-design.md)）。下方正文以 scope=user 为例，workspace 侧规则一致。

### 6.0 硬原则：宁缺毋滥（v20）+ 结构自解释（v22）

用户状态 L2 注入 system，错误条目会跨会话当「真事实」→ **幻觉成本远高于漏记**。  
结构化不是「有 kind/key/value 三列」就够——**短而含糊的字段会比没有更糟**（模型把 `plan`/`true` 当全局指令）。

| # | 原则 | 落地 |
|---|------|------|
| P1 | **宁可不加，也不要加错** | 场景不明 / 仅本轮临时 / 无法指认「用户长期成立」→ **不输出候选**（空数组合法） |
| P2 | **禁止裸关键字** | `key` 必须带领域前缀、可消歧（§6.3.4）；禁止 `plan`/`style`/`偏好` 这类无场景键 |
| P3 | **背景可解释** | 每条带 `background`（短场景说明）；注入与语义 merge 可读；缺 background → 新写入丢弃 |
| P4 | **低置信丢弃** | 继续 `minConfidence`；过程类默认更高门禁或并入短 TTL（§6.3.1） |
| P5 | **矛盾不注入** | 字面冲突 + 语义 CONFLICT → `conflict`/`void`，**不进** system 块 |
| P6 | **值必须自解释（v22）** | `value` = 完整命题短句（主语可省略但不可仅 `true`/`false`/单 token 代号）；脱离 key 仍可读；细则 §6.3.5 |

**抽取默认策略**：偏召回的「尽量抽关键字」**废止**；Catalog `context.l2.extract` 以 **abstain 为默认**，只收稳定、跨会话仍成立、场景可指认的条目。

### 6.1 数据模型

表 `user_context_state`（`tenant_id` + `user_id`）：

| 字段 | 说明 |
|------|------|
| `kind` | 11 类（基线）→ 建议收窄为 9 类（§6.3.1） |
| `state_key` | **场景化键**（§6.3.4）：`{domain}.{facet}`，VARCHAR(128) |
| `state_value` | **自解释命题**（§6.3.5）：稳定短句；禁止裸布尔 / 无语境代号；禁止大段日志 |
| `background` | **v20 新增**：场景背景 ≤256 字（如「餐饮偏好」「Sunshine 编码约定」）；可空但 P3 约束下实质必填 |
| `confidence` | 分级门禁 |
| `status` | `active` / `superseded` / `void` / `conflict` |
| `expires_at` | 类型化 TTL |
| `source_msg_id` | 溯源 |

**唯一性（active）**：仍为 `(user, tenant, kind, state_key)` 至多一条 active——因此 **歧义必须消解在 key 上**，不能靠两条同 key 不同 background 并存。`background` 用于：① 注入展示「在什么背景下成立」；② 语义 merge 消歧；③ 抽取自检（写不出 background → 不入库）。

**DDL（落地时写入 `docker/mysql/init/`，禁止 Flyway）**：

```sql
ALTER TABLE user_context_state
  ADD COLUMN background VARCHAR(256) NULL COMMENT 'L2 场景背景（v20）' AFTER state_value;
```

**注入渲染（示例）**：

```
[用户状态 · L2]
- preference / diet.spice = 不吃辣  （背景：餐饮点餐）
- constraint / work.jdk = 17       （背景：Sunshine 后端）
```

无 background 的旧数据：注入时可不展示括号，或审计标记待补全；**新写入必须满足 P3**。

### 6.2 分类门禁

| kind | 含义 | 置信 | TTL |
|------|------|------|-----|
| `profile` / `preference` / `agreement` | 稳定画像/偏好/约定 | 0.75 | 365 天 |
| `goal` / `decision` | 目标/决策 | 0.75 | 90 天 |
| `fact` / `constraint` | 事实/约束 | 0.75 | 30 天 |
| `process_note` | 过程笔记（v27：原 `reasoning`/`option`/`interim_conclusion`/`topic` 合并） | 0.65 | 7 天 |
| `todo` | 未完成任务清单（task-list-memory M1） | 0.75 | 7 天 |

> **v27**：当前共 9 类；`reasoning`/`option`/`interim_conclusion`/`topic` 四个旧 kind 已从 `VALID_KINDS` 移除（合并为 `process_note`），存量行清理。

写入：assistant completed → `ContextWritePath` → L2 抽取（异步）。高置信静默入库，低置信丢弃；**v20**：另过 §6.0 / §6.3.4 门禁。

注入：`assembledContext.l2SystemBlock()` → system 消息块（含 background）。

### 6.3 优化方向

**6.3.1 类别合并（✅ v27 已落地 2026-08-27）**

`reasoning` / `option` / `interim_conclusion` 三类在 LLM 抽取时边界模糊，错分率较高，连同短 TTL 的 `topic` 一并合并为 `process_note`（过程笔记）：统一 7 天 TTL、0.65 置信门禁。

| 当前（v1） | 建议（v2） |
|------------|-----------|
| `reasoning`（推理依据） | → `process_note` |
| `option`（备选方案） | → `process_note` |
| `interim_conclusion`（临时结论） | → `process_note` |
| `topic`（话题锚点，v27 追加并入） | → `process_note` |

合并后 L2 从 11 类简化为 **9 类**（7 类基础画像 + `process_note` + `todo`）。落地差异：`topic` 并入使原「无置信门禁 + 1 天 TTL」特性并入 0.65/7 天统一口径；存量四类行清理。

**与 Mem0 对照**：
- Mem0 采用单一 `ADD/UPDATE/DELETE/NOOP` 管道，不分类
- 我们的 9 类比 Mem0 粗（有分类价值区分 TTL），比当前 11 类细得合理
- `process_note` 语义清晰：`profile` 是"用户是谁"，`process_note` 是"对话中怎么想的"

**6.3.2 topic TTL 协调（✅ v27 已消解 2026-08-27）**

当前 `topic` TTL 为 1 天，其他过程记忆 7 天。跨天明会话时推理还在但话题锚点已失效。建议：
- `topic` TTL 延长至 3 天（覆盖周末空档）
- 或改为与 `process_note` 统一 7 天，依赖过期机制自然淘汰

> **v27**：`topic` 已并入 `process_note`（统一 7 天 TTL），本议题由合并天然消解。

**6.3.3 Prompt 改造（叠加 v20）**

Catalog `context.l2.extract` 同步：

```
硬规则（宁缺毋滥 + 结构自解释）：
- 只抽取跨会话仍成立、可指认用户的稳定信息；拿不准 → 输出 []。
- 禁止：本轮临时结论、单次任务/迭代计划（P10/P11…）、工具日志、布尔孤值、无明确主体的「项目/系统」指代。
- 每条必须含：kind, key, value, confidence, background。
- key 必须符合 {domain}.{facet}；value 必须是自解释命题短句（§6.3.5）；background 说明成立场景。
- 写不出合格 key / value / background → 该条丢弃，不要用模糊短字段凑数。

kind 只能是：profile, preference, goal, agreement, constraint, fact, decision,
          process_note, todo（v27：reasoning/option/interim_conclusion/topic 已合并）
- process_note：须有明确依据；默认倾向不抽（易幻觉），确需则短 TTL。
- 会话/工作区过程态 → 不进 KV Memory scope=user（应落 Near/任务清单恢复块/H1，见 §6.3.5）。
```

**6.3.4 key 场景化 + background（v20 定稿）**

> **问题**：现状 LLM 常产裸 key（`style`/`数据库`/`偏好`），同 key 跨餐饮/编码/产品完全不同义；字面合并会错并，字面不同又冗余并存 → 注入后模型无法区分场景 → 幻觉。

**key 命名规范（强制）**：

| 规则 | 说明 | 例 |
|------|------|-----|
| 形态 | `{domain}.{facet}`，小写，`.` 分段，`[a-z0-9_]+` | `diet.spice`、`work.jdk`、`ui.verbosity` |
| domain | 稳定领域：`diet` / `work` / `ui` / `team` / `finance`… | 禁止用 `misc` / `other` / `temp` |
| facet | 该领域下的具体侧面 | `spice`、`jdk`、`reply_length` |
| 禁止 | 单段裸词、纯中文 key、过长叙述当 key | `风格`、`用户喜欢的` |

**background（强制语义、存储可空兼容旧行）**：

| 规则 | 说明 |
|------|------|
| 内容 | 1 句场景：这条在什么对话/业务背景下成立 |
| 长度 | ≤256 字；建议 ≤40 字 |
| 不进唯一键 | 避免「同事实多 background」碎片化；消歧优先改 **key.domain** |
| 注入 | 与 value 一并展示，降低模型误用跨场景事实的概率 |
| merge | §6.4 语义判定输入含 background，减少「同 key 不同场景」误 MERGE |

**代码门禁（`L2ExtractService` / upsert 前）**：

1. `key` 正则：`^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$`（至少一段 domain）  
2. `background` blank → **丢弃**（新写入）；旧行无 background 仅读路径兼容  
3. `value` 超长（如 >500 字）→ 丢弃（宁缺）；**仅** `true`/`false`/`yes`/`no`/`1`/`0`（忽略大小写）→ **丢弃**（v22）  
4. task 会话：写路由跳过用户 L2（[task-scene §2.1](./archive/2026-08-01-task-scene-context-design.md)），编码事实不进用户画像

**与「加 scope 列」的取舍**：单独 `scope` 枚举易与 kind/domain 三重叠。v20 **用 key.domain + background 表达场景**；若日后多租户产品线需要硬隔离，再加 `scope` 进唯一键（另开变更），本期不做。

**6.3.5 value 自解释 + 反例（v22 定稿）**

> **问题**：线上用户 L2 出现大量「短字段」——裸 key（`plan`）、布尔孤值（`approve_P11_1=true`）、会话迭代目标（`continue_project_optimization`）。注入 system 后模型会把**本轮任务态**当成**用户长期事实**，直接诱发跨会话幻觉与互相矛盾（多条 `conflict` 并存）。

**value 形态（强制）**：

| 规则 | 说明 | 例（✅） | 反例（❌） |
|------|------|---------|-----------|
| 命题句 | 可读完整主张；可省略「用户」主语 | `答复默认简洁、少客套` | `true` / `简洁` |
| 可枚举则枚举 | 稳定枚举优于口号 | `jdk=17`（配 `work.jdk`） | `optimization_choice` |
| 禁止布尔孤值 | 布尔须并入命题或改写成约束句 | `同意对回复使用 Markdown` | `mermaid_prerender_svg=true` |
| 禁止会话代号 | 无跨会话意义的 P 序号/审批代号 | （不入库） | `approve_P11_1` / `plan` |
| 长度 | 建议 8–120 字；>500 丢弃 | — | 大段任务说明书 |

**归属闸门（比改 value 更优先）**：

| 信息类型 | 应落点 | 禁止 |
|----------|--------|------|
| 用户长期偏好/约束/画像 | **用户 L2** | 写成裸 key |
| 本会话/本迭代计划、审批勾选、优化 sprint | Near / 任务清单恢复块 / H1 | **KV Memory scope=user** |
| 工具调用关键字段 | Mid schema / processTrail | 用户 L2 的 `true` 开关 |

**线上反例 → 处置**（存量由 `auditL2` + 人工/脚本清洗；增量靠门禁拒写）：

| 现状（反例） | 问题 | 正确处置 |
|--------------|------|----------|
| `agreement` / `plan` =「优先执行 P12.1–P12.3…」 | 裸 key + 会话计划 | **不入库**（或仅当时会话 Near）；非用户长期约定 |
| `agreement` / `p11_plan_agreement` | 单次同意 | **不入库**；过程在对话正文 |
| `decision` / `approve_P11_1` = `true` | 布尔孤值 + 任务代号 | **丢弃** |
| `goal` / `continue_project_optimization` 与 `continuous_optimization` | 口号目标、互相矛盾 | **丢弃**或收敛为一条可指认长期目标（极少） |
| `agreement` / `mermaid_prerender_svg` = `true` | 项目技术开关 | 属 **KV Memory scope=workspace / P0**，非 scope=user |
| `decision` / `optimization_choice` =「Implement Mermaid…」 | 会话方案选择 | **KV Memory scope=workspace** 或对话正文，不进用户画像 |

**合格样例（对比）**：

```
preference / diet.spice = 不吃辣     （背景：餐饮点餐）
constraint / work.jdk = 使用 JDK 17 （背景：Sunshine 后端工程）
agreement  / ui.reply_style = 默认简洁少客套 （背景：聊天回复风格）
```

### 6.4 语义冲突识别（写路径 · v7）

> **v25（2026-08-14 · 二期可选）→ 2026-08-25 已落地 ✅**：一期写路径用**字面 + key 规范化门禁**（§6.0/§6.3.4 正则）覆盖主路径；语义候选检索 + LLM 判定（NOOP/MERGE/UPDATE/CONFLICT）已按本节契约实现——`L2SemanticMergeService` + Catalog `context.l2.merge` + Nacos `agent.context.l2.semantic-merge-enabled`（默认开）；落地差异：Catalog 以本节 `context.l2.merge` 为 SSOT（task-scene §5.2 早期别名 `context.memory.merge` 未启用）、`task.*` 结构键（M2 导出）不走语义路径、判定失败一律保守回退 NOOP（正常新增）。

> **问题根因（2026-08-01 线上 bug）**：`L2StateStore.upsert` 的唯一冲突判定入口是 **kind + key 字面精确匹配**（`findBy…KindAndStateKeyAndStatus`）。两个语义相似或相反的条目只要 key 字面不同（如 `fact/项目数据库=MySQL` vs `fact/项目存储用MySQL`、`constraint/用户不吃辣` vs `constraint/用户偏好重辣`），彼此完全不可见——`L2ConflictMerger` 仅在字面同 key 时触发，value 相反也无法判矛盾，两条同时 `active` 并存注入。事后腐败审计（§9 `auditL2`）虽可用 LLM 标 `conflict`，但它是**异步批量 + 防抖**：矛盾在写入时已被当作新条目接受，且在下一次审计前一直注入。
>
> **目标**：写入路径做**语义识别检索**——新 candidate 入库前，对已有 active 条目做语义候选判定，从源头防止「语义相似 key 各自独立成条 / value 相反矛盾」。（落地后候选检索为**跨 kind 全量 active**，见 ② 落地差异。）

**写入路径升级（`L2StateStore.upsert` → 三阶段）**：

```
① 字面快路径（保留现状，零额外成本）
   kind + key 精确命中 active：
     · value 相同 → refresh（不新增）
     · value 不同 → L2ConflictMerger 时间优先/置信门槛
   （命中即返回，不触发语义判定）

② 语义候选检索（仅当 ① 未命中 且 存在其他 active 条目）
   候选集 = 同 user + 同 kind 的其余 active 条目（key/value 与 candidate 字面不同）
   v1：候选集全量交 LLM（同 kind 条目规模有限，通常 <50，不引入 embedding）
   **落地差异（2026-08-26）**：候选检索面扩展为**跨 kind 全量 active**——同义事实可能落不同
   kind（如 `fact/travel.origin` vs `profile/location.current_city`），同 kind 检索会漏判；
   LLM 判不相干时返回 NOOP，误召回只增判次不误伤。
   v2 可选：embedding 召回 Top-N（复用 rag-service 通道），候选规模大时启用

③ 语义判定（LLM · Catalog context.l2.merge）
   输入：新 candidate(kind,key,value,background,conf) + 候选集（含各条 background）
   输出（每条 candidate）：
     NOOP      → 与候选集语义无关           → 正常新增 active
     MERGE     → 语义等价/同指（措辞不同）   → 合并到 targetId：
                                               mergedKey/mergedValue/background 归一，
                                               target 刷新值 + 置信取高，不产生 superseded
     UPDATE    → 语义更新（用户改主意/事实演进）→ target 标 superseded + 新增新条
     CONFLICT  → 语义相反/互斥（无法用时间解释）→ target 标 conflict（不注入）
                                               + candidate 标 conflict（或丢弃，保守双标待澄清）
   每条输出含 targetIds + reason（审计可读）
   **v20**：background 明显不同域（餐饮 vs 编码）且 value 不可比 → 倾向 NOOP（各管各的），
            禁止仅因「都叫偏好」就 MERGE；若误用同 key 跨域 → CONFLICT 或要求抽取侧重写 key
```

**UPDATE vs CONFLICT 判定标准**（写入 Catalog prompt `context.l2.merge`）：
- 语义相反但可用**时间/场景演进**解释（偏好/目标/决策变更）→ `UPDATE`（覆盖，旧条 superseded 审计保留）
- 语义相反且**同为当前客观陈述**、无法用时间解释（事实/约束互斥）→ `CONFLICT`（双标不注入，防污染）
- 语义等价/同指不同措辞（"Java 版本" vs "Java 17"）→ `MERGE`（归一，防 key 碎片化）

**与压缩点 / KV 兼容**：
- 语义判定不破坏 content-hash 幂等（§5.5.6）——判定结果若未产生写库动作则不落库，组装字节不变
- 仅「字面未命中 + 有 active 候选」才触发语义路径，**不引入每轮全量 LLM**（字面命中 / 首次写入 / 零候选走快路径）
- Nacos 开关 `agent.context.l2.semantic-merge`（默认 on；关闭回退纯字面，兼容现行为）

**与腐败审计分工**：
- 写路径语义判定 = **增量、主动**（防新增矛盾）
- 腐败审计 `auditL2`（§9）= **批量、兜底**（清历史遗留 + 跨 kind 矛盾）
- 二者共享「双标 conflict 不注入」的判定标准（`context.l2.merge` / `context.l2.audit`），不重复实现

### 6.5 实现文件

| 文件 | 用途 |
|------|------|
| `L2ExtractService.java` | 抽取 + **v20 key/background 门禁** + 分级置信 |
| `L2StateStore.java` | CRUD + TTL + 注入（含 background）+ 字面快路径 + **三阶段语义判定集成（✅）** |
| `L2SemanticMergeService.java` | 语义候选 + LLM 判定（✅ 2026-08-25；输入含 background；失败回退 NOOP） |
| `L2ConflictMerger.java` | 字面同 key 判定（保留） |
| `ContextProperties.java` | L2 门禁 + TTL + `semanticMergeEnabled` 开关 |
| `UserContextStateEntity` + init SQL | **新增 `background` 列** |
| Catalog `context.l2.extract` | 宁缺毋滥 + key 规范 + **value 自解释** + background（正文 SSOT） |
| Catalog `context.l2.merge` | 语义判定（含 background 消歧）（✅ v147 上线） |

**建议落地顺序**：① Catalog extract 收紧（含 value 形态）+ 代码正则门禁（裸 key / 布尔孤值，无 DDL 也可先拒）→ ② DDL `background` + 注入展示 → ③ 语义 merge §6.4 **✅（2026-08-25）** → ④ 类别合并 §6.3.1 + 存量 `auditL2` 清洗裸 key / 会话计划 / 布尔孤值。

---

## 7. Layer 4 — L3 向量检索（cross-session RAG）

> ✅ **已实现**。`L3RecallService` + `ChatHistoryRetrievalService` + Milvus

### 7.1 数据流程

```
写：assistant completed
  → L3IngestService.ingestAsync(user + assistant 消息对)
  → FixedLengthChunker（800 字 / 100 重叠 / 句末标点切割）
  → EmbeddingService（DashScope text-embedding-v4 · 1024 维）
  → Milvus sunshine_chat_history（IVF_FLAT / IP / BOUNDED）

读：每次 ContextAssembler.assemble
  → L3RecallService.recall(query)
  → embedding(query) → Milvus search
  → filterAndRank（Near/Mid 排除 + 时间衰减 + minScore + Far 降权 + 同 msgId 去重）
  → 渲染为「历史材料·可能过期」→ assembledContext.l3MaterialBlock
```

### 7.2 检索参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `topK` | **8** | Nacos 覆盖 Java 默认 5 |
| `minScore` | **0.45** | Nacos 覆盖 Java 默认 0.55 |
| `fetchK` | topK × 4（≤50） | 候选池冗余 |
| 时间衰减 | `score ×= 0.5^(ageDays / 90)` | 半衰期 90 天可配（原硬编码 30 天） |
| Far 降权 | `score ×= 0.5` | 非硬排除 |
| Near/Mid 排除 | 硬排除 | 已在 L1 窗口中的 msgId |

### 7.3 实现文件

| 文件 | 模块 | 用途 |
|------|------|------|
| `L3RecallService.java` | orchestrator | 召回 + filterAndRank + 渲染 |
| `L3IngestService.java` | orchestrator | 异步 upsert |
| `HistoryRagClient.java` | orchestrator | WebClient → rag-service |
| `ChatHistoryRetrievalService.java` | rag-service | 分块 + embedding + Milvus |
| `FixedLengthChunker.java` | rag-service | 定长分块 |
| `EmbeddingService.java` | rag-service | DashScope embedding |
| `ChatHistoryMilvusService.java` | rag-service | Milvus CRUD |
| `ContextProperties.java` | orchestrator | topK/minScore/decayHalfLifeDays |

### 7.4 L3 增强（v26 · 重新启用）

> **v26（2026-08-18 · 重启）**：原 v25 整体延后的 L3 增强项升级为要做——语义提取层、相似度去重、定期维护、task process 层向量化。本节为设计定稿，落点见 §13.4。
>
> **v26.2（2026-08-26 · body 层非全量 / 置信门禁 ✅）**：兑现 §7.4.1 动机「向量空间被噪音污染」的最后一环——**body 层不再全量落库**。写路径 `ContextWritePath` 改为单入口 `L3IngestService.ingestTurnPair`：semantic-extract 开启时按 turn-pair 攒批（N 轮 / M 分钟），flush 时 `LLMSemanticExtractor` **按轮判定**（prompt v2 输出与轮数等长的二维数组，第 i 元素 = 第 i 轮片段数组，无则 `[]`），abstain 轮 **body 原文 + semantic 段均不落库**，仅重要轮双写。即语义提取结果同时充当 body 层的置信门禁——确认语/寒暄/纯过程叙述不再进 Milvus（长期上下文只留重要内容）。开关 `agent.context.l3.body-gate-enabled`（默认 true；关 → 回退 v26 两路并存全量）。兼容与边界：① 语义提取失败逐轮 abstain（保守不落库）；② LLM 返回平铺一维数组（旧响应/单轮）兼容解析为第 0 轮；③ 运维重建端点（ContextAdminService reingest）为显式全量 escape hatch，不经门禁；④ process 层（task 工具结果）即时落库不受影响；⑤ `SessionSearchTool` 检索 body+process，body 延迟 ≤ 攒批窗口（5 分钟），近期轮次由 L1 Near 覆盖。
>
> **v28（2026-08-27 · L3 摘要化与 L2 对账）**：本节 v26/v26.2 的语义提取路径在 v28 进一步收口——**chat 场景 L3 只保留 semantic 摘要层**（body 原文层退役，`L3IngestService` 对 chat 恒不写 body，`body-gate-enabled` 仅对 task 生效）；`context.l3.semantic-extract` 升 v4 为**摘要形式**（合并同主题、保留关键细节、每轮 ≤2 段）；`LLMSemanticExtractor` 增加 **L2 对账**（写 semantic 前读 active L2，整段覆盖 → abstain）。chat 召回 `L3RecallService` 收敛为仅 semantic，面板 `listL3Entries` 仅展示 semantic（role 统一「Chunk」）。**本小节下方各设计描述（§7.4.1 两路并存、§7.4.2 触发、§7.4.3 去重、§7.4.4 process 层）在 task 场景仍成立**；chat 场景按 v28 契约以 §13.4.1 为准。

**7.4.1 嵌入前语义提取层（v26 · 升级为要做）**

**动机**：当前 L3 直接对原始消息全量分块嵌入，未经任何语义过滤。确认语、寒暄、长答案多个 chunk 争抢 topK、用户反复相似问题产生多份近似向量——向量空间被噪音污染，关键信息召回率低。

**方案**：

```
写路径升级（v26）：
  assistant completed
    → L3IngestService.ingestAsync
    → 【新增】LLMSemanticExtractor（异步，独立于原文 chunk 路径）
      · 输入：本轮 user+assistant 消息对
      · Catalog: context.l3.semantic-extract
      · 抽取维度（v26 定稿）：
        ① 用户画像信号：用户表达的身份/角色/习惯（与 L2 scope=user 解耦，不重复抽取）
        ② 历史任务关键结果：达成结论、决策、方案、关键 ID（仅"关键"，不抽全部）
        ③ 重要实时事件：订单/工单/审批类外部 ID、时间敏感事件（不可被 L1 压缩丢的硬证据）
      · 抽取默认策略：abstain 默认（与 L2 同口径）
      · 空抽取结果 → 跳过（不浪费向量存储）
    → FixedLengthChunker（body 原文保留，scene=chat/task）
    → 【新增】SemanticChunkIngest（语义提取结果独立入库，scene 沿用，layer=semantic）
    → EmbeddingService（两路并存：原文 chunk + 语义提取结果）
    → Milvus sunshine_chat_history
```

**与 L2 的边界**：

| 维度 | L2（KV Memory） | L3 语义提取（v26） |
|------|-----------------|---------------------|
| 形式 | 结构化键值（key/value/background） | 自然语言短文本（精炼片段） |
| 注入位置 | Tier 1 system 块 | Tier 2 L3 召回块 |
| 过滤方式 | 业务规则 + 白名单 | 语义相似度 |
| 持久性 | 跨会话长留 | 按时间衰减（与 L3 一致） |
| 写入约束 | 宁缺毋滥（v20） | 同样宁缺毋滥（abstain 默认） |

**两类并存不冲突**：L2 抽取结构化事实（如"`preference: 用户偏好 Java 17`"）；L3 语义提取保留语义连续段落（如"上次讨论 K8s Pod 重启三种原因：OOMKilled、Liveness probe 失败、节点资源不足，最终确认是第三种…"）。

**Milvus schema 扩展**：

```
sunshine_chat_history 增加字段：
  layer: VARCHAR(16)  默认 'body'
         取值：'body' | 'semantic' | 'process'
         （v26 同时启用 semantic 和 process，body 为原文 chunk）
```

**7.4.2 触发时机（v26 · 攒批触发 + turn-pair 合并）**

| 当前 | v26 升级 | 理由 |
|------|---------|------|
| 每轮即时 upsert | **攒批触发**：累积 N 轮（默认 3）或 M 分钟（默认 5）后批量提取+嵌入 | 降低 Milvus 写入频率；LLM 提取可一次处理多轮 |
| user + assistant 独立嵌入 | **轮次对（turn-pair）合并提取** | 保留上下文关系，提取质量更高 |

**实现**：`L3IngestService` 维护内存缓冲（按 conversation_id 分组），到达阈值或定时器触发批处理；不阻塞 assistant 完成路径（依然异步）。

**7.4.3 相似度去重（v26 · 升级为要做）**

**目标**：减少同质化噪音，节省向量存储与召回开销。

**去重规则**（embedding 前 cosine 比对）：

```
for each new chunk (待写入):
  对比窗口：同 tenant + 同 scene + 同 layer + 最近 24h 内 Top-50 已有向量
  cosine 相似度判定：
    > 0.95 → 跳过（完全重复，不写）
    0.85 ~ 0.95 → 合并：保留较早一条 + 更新 timestamp；当前条丢弃
    ≤ 0.85 → 正常写入
```

> **layer 隔离（2026-08-24 实现定稿）**：去重窗口必须限定同 `layer`。semantic/process 段是 body 原文的精炼产物，与 body 天然高相似；若跨 layer 比对，语义提取段/工具结果摘要会被误判为完全重复而整体丢弃，语义层永远为空。同层去重仍满足「重复 query / 重复工具调用只保留一份」目标。

**实现**：Milvus `search` expr 限定 `created_at > NOW() - 24h`，取 Top-50（按时间倒序），本地 cosine 计算。批量写入前一次性比对，减少单条 round-trip。

**与 L1 压缩点不冲突**：去重只发生在 L3 ingest 路径；L1 Near/Mid/Far 仍按原文保留原文（`chat_message` 原文不删）。

**7.4.4 task process 层向量化（v26 · 重新启用）**

> 原 [task-scene §6.4 v14](./archive/2026-08-01-task-scene-context-design.md) 标注延后，本节重新启用。

**数据源**：

| 层 | 内容 | 来源 |
|----|------|------|
| `layer=body` | task 会话 user+assistant 消息对（已有） | L3 ingest 现有路径 |
| `layer=process`（v26 新增） | `ProcessingStep.result` 截断 200 chars + `refs` | `chat_message.steps` 每步 |

**边界（与代码引用化原则一致）**：

- ✅ **存**：工具调用结果摘要（≤200 chars）、`refs`（path:line/path#symbol）、status/exitCode
- ❌ **不存**：`reasoning`、完整 `output`、文件内容（agent 按 refs 读实时代码）

**Milvus schema**：

```
scene=task
layer IN ('body', 'process')
scope=session（一期）：expr `conversation_id == X`
scope=workspace（二期）：expr `workspace_id == Y`
```

**session_search 升级**（v26）：

| 维度 | v14（一期） | v26（升级） |
|------|------------|-------------|
| 数据层 | body only | body + process |
| scope | session only | session（一期）+ workspace（二期） |
| 召回面 | 对话正文 | 对话正文 + 工具调用结果 |

**触发**：task 写路径 ingest 时，`ProcessingStep` 数量 > 0 → 抽取每步 `result` 截断 200 chars → 入库带 `layer=process`。

**与 §7.4.3 去重的协作**：process 层向量同样过 cosine 去重，避免重复工具调用产生同质化向量。

### 7.5 渲染位置约束（v2 优化）

> 落实 §5.5 判据 C2：L3 是**尾部动态段**，位置固定，禁止漂移。

- **唯一合法位置**：messages 的绝对尾部，紧邻当前 user query 之前
- **禁止**：L3 插在 Far / Mid 之间、L3 合并进 L2 system 块——该位置每轮都可能变化，其后方全部消息失配，破坏 prefix 稳定性
- 效果：L3 命中回填的每轮差异仅牺牲「L3 块 + query」两个小块，属 C2 允许的 tail 变化

---

## 8. Layer 5 — Budget Trimming（读时裁剪）

> ✅ **已实现**。`ContextAssembler.applyBudget`

### 8.1 裁剪顺序

组装后 token 超 `modelWindow × 0.8` 时降级：

```
丢 L3（历史材料块）
  → 丢 Far（远窗摘要块）
  → Mid 从头丢轮次
  → Near 永不丢
  → L2 constraint 类永不丢
```

### 8.2 「丢」改「退役并入」（v2 优化）

> ✅ **已落地（2026-08-26）**：压缩点模式 `ContextAssembler.applyBudgetAtPoint` 实现——超预算不静默丢弃，按「① 丢 L3（零损失）→ ② 退役 Mid 头部进压缩点（`L1Compressor.advanceCompressionPoint` 零 LLM 纯写库，`far_folded_msg_ids` 前移；`far_summarized_msg_ids` 不动，写路径异步补折叠进 far_summary）→ ③ 仍超预算丢 Far 摘要块」顺序；**Near / L2 永不丢**。滑动窗模式（chat / workflow）保持基线 `applyBudget` 静默丢弃。与原始设计的差异：②的「触发一次 Far 折叠（LLM）」落地为零 LLM 的同步退役（P/S 分离，间隙轮异步补折叠），装配热路径不含 LLM 调用。

> 落实 §5.5 建议③：Budget 不再做「静默丢弃」，而是**推进压缩点**的触发源之一，保住「压缩不删原文、摘要可查」原则。

- 原始裁剪顺序中「Mid 从头丢」/「丢 Far」改为**退役并入**：
  1. 超预算 → 先触发一次 **Far 折叠**（Mid 头部并入 far_summary，`far_folded_msg_ids` 前移）
  2. 折叠后仍超预算 → 才丢 Far 摘要块（保留原文 + far_folded 边界，可再次折叠或 L3 回填）
  3. Near 永不丢、L2 constraint 类永不丢（保持既有不变量）
- 触发源链路：`applyBudget` 超限 → 写一条「需压缩」信号 → 跨轮压缩异步执行 → 下一轮 prefix 按新压缩点重建（C3 唯一一次重建）

---

## 9. 治理与防腐败

> ✅ **已实现**。`ContextMaintenanceJob`
> **v26 扩展**：L3 维度纳入定期维护，新增冲突向量打标 + 过期向量清理 + 与 L2 协同仲裁。

### 9.1 L2 维护（原 Job）

| 机制 | 说明 |
|------|------|
| 冲突 | 时间优先覆盖，旧条 `superseded` 审计保留 |
| 语义冲突识别 | **写路径**：语义候选检索 + LLM 判定 NOOP/MERGE/UPDATE/CONFLICT（§6.4，防语义相似 key 各自成条） |
| 过期 | 硬过期 → `void`；过程记忆 7 天短 TTL |
| GC | `gcL3Vectors()` 清理 MySQL 中不存在消息的孤儿向量 |
| 腐败审计 | 明确冲突自动 void；暧昧打标 `conflict`（不注入）；与写路径语义判定互补（增量防新增 / 批量清遗留） |
| 清理 | superseded 180 天 / void 30 天物理删除 |

### 9.2 L3 维护（v26 · 升级为要做）

**触发**：`ContextMaintenanceJob` 每小时运行（与既有对齐）。

**核心动作**：

```
L3 维度维护（v26）：
  ① 冲突向量打标：
     · 与 L2 同口径——语义判定复用 context.l2.merge（同 Catalog 分支）
     · 判定结果 NOOP / MERGE / UPDATE / CONFLICT
     · CONFLICT → 打标 conflict（不注入）
     · MERGE → 合并向量（旧条 supersede，新条继承 freq/recency）
  ② 过期向量清理：
     · 对应原消息已被 GC（chat_message 物理删除）→ 同步删除向量
     · decay TTL：scene=chat 默认 30 天 / scene=task process 层 7 天 / scene=task body 90 天
     · 召回冷数据自动降权（decayHalfLifeDays 默认 14）
  ③ 与 L2 协同仲裁：
     · L2 已存的结构化事实（如 preference）— L3 同主题向量降权（0.5x）
     · L3 与 L2 冲突 → 以 L2 为准（L2 是显式抽取，置信度更高）
  ④ 相似度合并（与 §7.4.3 互补）：
     · 维护阶段对 24h 内 Top-100 重做 cosine 扫描（与 ingest 去重阈值一致）
     · 高相似对合并，避免存量噪音累积
```

**判定复用**：

| 维护动作 | 复用 Catalog | 备注 |
|----------|--------------|------|
| L3 冲突判定 | `context.l2.merge` | L2/L3 同判定口径 |
| L3 过期清理 | `context.l3.recall.ttl` | 按 scene 分层 |
| L3 降权仲裁 | `context.l3.conflict.policy` | 默认 L2 优先 |

---

## 10. 配置

### 10.1 Nacos `sunshine-orchestrator.yaml`（Layer 2/3/4/5）

```yaml
agent:
  context:
    enabled: true
    l1:
      near-turns: 8
      mid-turns: 8
      max-tokens-ratio: 0.75              # Layer 5 初始预算 96k，留 13k 到 Phase 1 109k 触发（详见 §4.4.4）
      turn-backstop: 40
      default-model-window: 128000
      token-safety-factor: 1.1
      mid-compress-ratio: 0.15
    l2:
      min-confidence: 0.75
      constraint-overwrite-confidence: 0.9
      reasoning-min-confidence: 0.7
      interim-conclusion-min-confidence: 0.6
      reasoning-ttl-days: 7
      option-ttl-days: 7
      interim-conclusion-ttl-days: 7
      topic-ttl-days: 1
    l3:
      collection: sunshine_chat_history
      top-k: 8
      min-score: 0.45
      time-decay: true
      decay-half-life-days: 90
    maintenance:
      interval-ms: 3600000
      audit-enabled: true
```

### 10.2 Nacos `sunshine-llm-gateway.yaml`（模型窗口）

```yaml
providers:
  deepseek:
    models:
      - name: deepseek-v4-pro
        context-window: 128000
        encoding: cl100k_base
      - name: deepseek-v4-flash
        context-window: 64000
        encoding: cl100k_base
```

### 10.3 Nacos `sunshine-orchestrator.yaml`（Layer 1 独立区）

```yaml
agent:
  memory:
    auto-context:
      enabled: true
      # Phase 0：tail 裁剪（CompactionConfig）
      msg-threshold: 40                   # 保留（静态 fallback）
      last-keep: 12                       # 保留
      min-consecutive-tool-messages: 4
      min-compression-token-threshold: 3000
      protect-tokens: 40000               # Phase 0：保护 40k
      # Phase 1：跨轮激进压缩
      cross-turn-ratio: 0.85              # 85% 触发
      near-keep-turns: 4                  # 保留 Near 4 轮
      mid-keep-turns: 4                   # 保留 Mid 4 轮
      # Phase 2：tail 收缩
      phase2-protect-tokens: 20000        # 更激进 20k
      phase2-min-tokens-to-prune: 10000
      phase2-truncate-chars: 500
```

---

## 11. 架构总图

```
┌── 读 ────────────────────────────────────────────────────┐
│  ContextAssembler.assemble(user, conv, query)              │
│    ├─ L2StateStore (Layer 3)    → system 稳定状态          │
│    ├─ L1 Store (Layer 2)        → Near/Mid/Far 窗口        │
│    ├─ L3 HistoryRagClient (L4)  → 按需 chunk               │
│    └─ applyBudget (Layer 5)     → 裁剪/降级                │
│  → AssembledContext → PromptComposer → LLM                 │
├── 写 ────────────────────────────────────────────────────┤
│  assistant completed                                       │
│    → ContextWritePath.runAsync（异步，顺序固定）            │
│      ├─ L2 抽取 (Layer 3)    → 分级置信 → upsert           │
│      ├─ L1 压缩 (Layer 2)    → token 判定 → 自适应降级     │
│      └─ L3 ingest (Layer 4)  → 分块+embedding+Milvus       │
├── intra-turn ─────────────────────────────────────────────┤
│  ReActAgent PreReasoning                                   │
│    → AutoContextMemory (Layer 1)  → 压缩工具结果            │
├── 治理 ───────────────────────────────────────────────────┤
│  ContextMaintenanceJob（定时）                              │
│    ├─ L2 过期 void / superseded / 矛盾                     │
│    ├─ L3 向量 GC                                           │
│    └─ L1 无主派生清理                                      │
└───────────────────────────────────────────────────────────┘
```

---

## 12. 验收

| 项 | 脚本 | Layer |
|----|------|-------|
| 长会话 Mid/Far + 自适应降级 | `verify_context_layers_live.py` | 2 |
| 短对话不触发压缩 | `verify_dynamic_context_live.py` T1 | 2 |
| token 80% 触发 + 轮次兜底 | `verify_dynamic_context_live.py` T2-T3 | 2 |
| Gateway 降级 | `verify_dynamic_context_live.py` T5 | 2 |
| L2 11 类写入 + 分级置信 | `verify_context_layers_live.py` + `verify_dynamic_context_live.py` T6-T7 | 3 |
| L3 召回 + Far 降权 + 时间衰减 | `verify_context_layers_live.py` + `verify_dynamic_context_live.py` T8-T10 | 4 |
| Budget 裁剪顺序 | 单测 `ContextAssemblerBudgetTest` | 5 |
| AutoContext 长工具链可完成 | `phase2_agent_demo.py`（AS `CompactionConfig` + tail 裁剪；v25 不再依赖 `CrossTurnCompactMiddleware`） | 1 |
| SUB 无记忆、企业 KB 不受影响 | 各脚本回归 | All |
| **状态保真续跑（v21）** | 压缩/结构态就绪后续跑：能守禁止项、选对下一步、引用 path/失败记录；**不要求**对话窗含全历史 | `verify_context_layers_live.py` / task 脚本扩展 |

---

## 13. 已知局限与后续

### 13.1 P0：Layer 1 三阶段一次原则 — 跨轮压缩仅 1 次 KV Cache 重建

> **v25（2026-08-14）**：本小节计划**不再实施**（见 §4.4 注记）。run 内压缩 SSOT = §4.5 AS `CompactionMiddleware` + tail 裁剪；跨轮走压缩点 §5.5 + Budget 退役并入 §8.2。正文保留作历史对照。

**原判断**（已纠正）：Layer 1 "完全缺失" → **实际 CompactionMiddleware 已运行**，仅配置阈值过高。

**核心问题**：AgentScope CompactionConfig 只有一个触发阈值——一旦触发就执行全管道（PruneConfig + LLM 摘要）。在长 run 中会反复触发，每次跨轮压缩都引发 KV Cache 全量重建（~3s 延迟 + 全量 token 费用）。

**对标方案（§4.4 详述）**：三阶段一次原则

| 阶段 | 触发 | 操作 | KV Cache 影响 | LLM 调用 |
|------|:---:|------|:---:|:---:|
| **Phase 0** | 每轮 LLM 前 | tail 工具结果裁剪（只改尾部） | ✅ 零（prefix 不变） | 零 |
| **Phase 1** | `modelWindow × 0.85` = 109k，且未执行过 | L3 全清 + Near→Mid + (旧Mid+旧Far)→新Far 合并 | 💸 1 次全量重建（~3s） | 5 次 |
| **Phase 2** | Phase 1 完成后每轮 | 激进 tail 收缩（20k 保护 / 500 chars 截断） | ✅ 零（新 prefix 不变） | 零 + maxIters 兜底 |

**实施**：
1. **新增** `CrossTurnCompactMiddleware.java` — Phase 1 跨轮激进压缩
2. **修改** `HarnessAgentFactory.buildCompactionConfig()` — 改 3 行参数
3. **修改** `ProcessingStepMiddlewareFactory` — 注入 CrossTurnCompactMiddleware
4. **修改** `MemoryProperties.AutoContext` — 新增 7 字段
5. **修改** `sunshine-orchestrator.yaml` — `max-tokens-ratio: 0.75`

### 13.2 P2：无用户指令保护

Claude Code Auto-Compact 在摘要中逐字保留用户原始问题（"神圣区"）。建议在 Far 折叠 prompt 中保留原始问题。

> L2 优化见 §6.0/§6.3/§6.4（宁缺毋滥 · key/background · 语义 merge）；L3 见 §7.4。

### 13.3 压缩点模式落地清单（v2 优化 · 🟡 部分落地）

> 对应 §5.5 及后续 v3–v15 增强。**2026-08-26 更新**：① 压缩点读/写路径已落地（`L1Compressor.partitionByPoint` + `compressByCompressionPoint` + `ContextAssembler` Near 不丢头部）；④ 启用面门控已落地；⑪ 语义 merge 已落地（见 §6.4 落地注记）；**⑤ 同步推进 P ✅ / ③ Budget 退役并入 ✅ / ⑥ Tier 定序 ✅ / ⑮ ≤10k 硬预算 ✅**（2026-08-26，`L1Compressor.advanceCompressionPoint` + P/S 分离 + `applyBudgetAtPoint` + `enforcePostCompactBudget` + `PromptComposer` scope-prompt 前置）；**⑫⑬⑭⑲ 工具轮 schema 行 ✅ / ⑦ 幂等 upsert ✅ / ⑯⑰⑱ ✅ / ⑮ 装载细化 ✅**（2026-08-26，`ToolSchemaRenderer`/`TaskProcessRenderer` + `SessionTurn` schema/process 行 + Near 完整过程，见对应行）；**仍 ⬜**：⑩ tools 分层注入（工具规模超阈值增强，见 skill-sticky `retrieval` 双层）。**v25：⑧ 作废（T0→fast 跨轮恢复）；Tier 1 的 L2/W0 统一为 KV Memory**。

| # | 建议 | 落点 | 改动 |
|---|------|------|------|
| ① ✅ | L1 压缩点前移 | §5.1 / §5.3 | `L1Compressor.partitionByPoint` 以 `far_folded_msg_ids` 为界；`ContextAssembler` 压缩点模式不裁 Near 头部（C2）；**同步推进 P ✅（2026-08-26）**——assemble L1 组装超预算 → `advanceCompressionPoint` 零 LLM 前移压缩点（纯写库）→ 本轮按新 P 重组（Near 收缩、Mid/Far 暂用旧值），退役轮写路径异步补折叠；[task-scene §4.2.1](./2026-08-01-task-scene-context-design.md#421-同步异步衔接同步推进-p--异步折叠v4-细化) |
| ② ✅ | L3 尾部动态段 | §7.5 | **架构已满足（核实 2026-08-26）**：`ContextMessageBuilder.appendAll` 渲染顺序固定——L3 块（`l3MaterialBlock`）位于全部上下文层（Far/Mid/Near/TaskListRestore）之后、当前 user 消息之前（`PromptComposer` Gateway/ReAct 双路径 `appendTail` 在当前消息之后才追尾部）；task 的 session_search 检索结果经 tool_result 进 tail，不注入 prefix |
| ③ ✅ | Budget 退役并入 | §8.2 | **已落地（2026-08-26）**：压缩点模式 `applyBudgetAtPoint` 丢 L3 → 退役 Mid 头部进压缩点（零 LLM，写路径异步折叠）→ 丢 Far 摘要块；Near / L2 永不丢（原有不变量保持）；滑动窗模式保持基线静默丢弃 |
| ④ ✅ | 机制同构 / 分期启用（v17） | §5.5.4 ④ · task-scene §2.2 | 压缩点机制场景无关；**一期** task×(fast\|pro) ✅（`L1Compressor.compressionPointActive`）；**chat 二期 ✅ 已落地**（2026-08-26：chat×fast\|pro 启用，Near/Mid 按 kind 分化 4+4，无 ≤10k 硬预算，Live 验收近 9 轮折叠/rebuild-check PASS）；workflow 不做；fast 任务清单恢复块、pro→H1（v25） |
| ⑤ ✅ | 双压缩点衔接 | §5.5.4 | **架构已满足（核实 2026-08-26）**：跨轮压缩唯一入口 = `ContextWritePath.run` 轮末调用 `L1Compressor.compress`（会话级锁串行化）——run 内无压缩写库；AgentScope 自带 `CompactionMiddleware` 仅管 run 内 S 域、不触碰 `far_folded_msg_ids`；`advanceCompressionPoint` 为装配侧零 LLM 退役专用，写路径异步折叠间隙轮补位 |
| ⑥ ✅ | **按频率分层（v3）** | §5.5.3 | **已落地（2026-08-26）**：`PromptComposer` scope-prompt（静态）前置进稳定前缀、nodePrompt（按节点变化）留尾部；`ContextMessageBuilder` Far/Mid/Near/TaskListRestore/L3 定序符合 Tier 0/1/2（② 落点不变）；高频块移出前部、意图注入走尾部 |
| ⑦ ✅ | **幂等 upsert + 定宽隔离** | §5.5.6 | **核心已落地（2026-08-26）**：L2 字面快路径同 key+value **零增益跳过写库**（`L2StateStore.refreshSameValue`——仅更高置信/新背景/新溯源才刷新 `updatedAt`；重复陈述零写，KV 前缀无时间戳漂移源）；确定性序列化**架构已满足**——`renderSystemBlock` 只含 `(kind, key, value, background)` 按 `(kind, key)` 定序、无时间戳/置信；L1 upsert 仅压缩窗口溢出触发、每轮必新增，无未变化回写。定宽 appendix 重编译依赖 KV Memory Hub 业务需求，**暂不启用** |
| ⑧ | **T0 状态块降频 + 轨迹块 Tier 2** | §5.5.6 / task-scene §6.1 | **v25 作废**：会话级任务状态由 fast `react_task_board` 跨轮恢复 / pro H1 承接（[task-list-memory](./archive/2026-08-14-task-list-memory-unification-design.md)）；失败路径挂任务 item `fail_reason`。原 T0 双块/processTrail/`context.t0.extract/condense` 不再实现 |
| ⑨ ✅ | **Planner-Worker 分层适配** | §5.5.7 注记 / [rebuild §3.1.1](./2026-08-05-planner-executor-rebuild-design.md) | **随 4.14 v17 已落地（核实 2026-08-26）**：Planner = 普通 ReAct MAIN，L1 组装与 `ContextAssembler.assemble` 完全一致（task/chat 按场景走 Near 差异）+ H1 注入（query 前，`PlannerHarnessExecutor`）；Worker = `WorkerContextFactory.forWorker` 仅稳定前缀 + 动态 handoff（无 L2/Far/Mid/Near 包袱，run 内 AS Compaction 管控）；H1 不拆 Tier、不建压缩点（v10/S3） |
| ⑩ | **tools 分层注入** | §5.5.3 v6 注记 / [phase5 §5.5](./phase5-operation-openness-design.md) | 工具规模 > 阈值时：全量名列表进 Tier 0 + Top-K schema 进 Tier 2 尾部；`full`/`retrieval` 由 Nacos `agent.tool.inject` 切换 |
| ⑪ | **KV Memory 语义冲突识别** ✅ | §6.4 / task-scene §5.2 | **已落地（2026-08-25）**：写路径三阶段（字面快路径 → 语义候选检索 → LLM 判定），`L2SemanticMergeService` + Catalog `context.l2.merge`（NOOP/MERGE/UPDATE/CONFLICT）；Nacos `agent.context.l2.semantic-merge-enabled`；`task.*` 结构键排除、失败回退 NOOP；**候选检索跨 kind 全量 active**（2026-08-26，§6.4 落地差异） |
| ⑫ ✅ | **Mid schema 骨架（v19/v23）** | §5.5.7 / §5.5.8 / task-scene §6.5 v9 | **已落地（2026-08-26）**：`ToolSchemaRenderer` 确定性渲染 + `SessionTurn.toolSchemaLines` 原样附加（未经 LLM 改写）；**task Mid**：机械短结论（`extractShortConclusion` 前 2 句 ≤120 字，零 LLM）+ tool schema 行 + refs；**chat Mid**：结论语义压缩（LLM 只压叙事）+ 同形 schema 行 |
| ⑯ ✅ | **L2 宁缺毋滥 + key/background（v20）** | §6.0 / §6.3.4 | **已落地（2026-08-25/26）**：extract abstain + 事实来源约束 = Catalog `context.memory.extract` v2；`{domain}.{facet}` key + background 必填 + 布尔孤值拒 = `ContextWritePolicy.l2TodoGatePasses`；`background` 列与注入 = `UserContextStateEntity.background` + `renderSystemBlock`（背景：…）；语义 merge 输入含 background（`context.l2.merge`）；存量清理 = O5 审计 v2。**落地差异**：代码门禁仅 `todo` 类强制，其他 kind 靠 Catalog 提示词约束、不强弃（兼容 chat 现状，类注释自述） |
| ⑰ ✅ | **状态保真原则（v21）** | §2.1 | **映射全部落地（核实 2026-08-26）**：L-state = fast 任务清单恢复块（task-list-memory M0 ✅）+ H1（Planner ✅）+ P0；失败路径 = `TaskItem.failReason` ✅；Skill 触发态 SSOT = `ChatMessageEntity.routingSkillIds` ✅（skill-sticky S-0）；未新建四类平行库；续跑验收随各载体 Live 脚本覆盖 |
| ⑱ ✅ | **L2 value 自解释（v22）** | §6.0 P6 / §6.3.5 | **已落地（2026-08-25/26）**：value 命题句 / 拒布尔孤值（`l2IsBooleanLoneValue`，todo 强制）/ 会话计划与审批代号不进用户 L2 = Catalog `context.memory.extract` v2 提示词约束（O5）；存量审计 = `context.l2.audit` v2（演进豁免 / 有佐证防误杀） |
| ⑲ ✅ | **chat 工具轮 schema（v23）** | §5.5.8 / §5.5.7 | **已落地（2026-08-26）**：chat Near/Mid 正文为主 + 工具轮确定性 schema 行（steps JSON → `[toolName] keyArgs=… status=ok|fail|denied exit=? · result≤200 · refs=[path]`）；与 L2 分工；非 task 完整过程窗 |
| ⑬ ✅ | **代码引用化（v12）** | §5.5.7 / task-scene §6.1/§6.4 | **已落地（2026-08-26）**：Mid schema 行 **result ≤200 机械截断**（非语义摘要）+ **refs=[path]**（沙箱路径引用），代码内容不进压缩记忆；session_search 一期 body+session（task-list-memory M3 ✅）；KV workspace 记忆/任务清单 item 只存引用（`path:line`/`path#symbol`）+ 结果摘要 ≤200 由 task-list-memory M1/M2 承载；L3 process 层向量化同样 ≤200（v26 ㉕）；无 blob 锚点/asOfCommit 水位/git 状态轮询 |
| ⑭ ✅ | **chat Near 正文为主 + 工具 schema + 4+4+Far（v13/v14/v23）** | §5.5.2 / §5.5.3 / §5.5.7 / **§5.5.8** | **已落地（2026-08-26）**：chat Near 终态正文为主（无 think/完整 tool dump）；工具轮追加确定性 schema 行（非完整过程窗）；Mid 语义结论 + 同形 schema；压缩重组 4+4+Far（v14）；可选短轮不占名额 |
| ⑮ ✅ | **task 压缩后重组 2+2+Far ≤10k（v15）** | task-scene §6.6 / §5.5.3 / §5.5.7 | **已落地（2026-08-26）**：`compression-point.task-post-compact-budget`（Nacos `task-post-compact-budget`，默认 10000）；写路径 `enforcePostCompactBudget` 超限先降级最旧 Mid 轮为折叠、再极端折叠最旧 Near（保底 1 轮）；**装载细化 ✅**——近 2 轮**完整过程**（`TaskProcessRenderer` think 推理全文 + tool 序列原文，写/改保留 patch 原文、读/执行 ≤200+refs）+ 次 2 轮**过程骨架**（§6.5 schema 行 + 短结论机械截取）+ 其余折叠；预算估算按完整渲染内容计（`l1OverBudget`/`applyBudgetAtPoint`/`trimByTokens`），超限走压缩点退役兜底 |
| ⑳ ✅ | **skill 动态工具 sticky 化（v24）** | §5.5.3 v6/v24 注记 · [skill-sticky v3.2](./2026-08-12-skill-sticky-process-chain-design.md) | **v24 原案已被 v3.6/A-5-full 取代落地（2026-08-24）**：主 agent T0 唯一数据源 = (tenant, kind) 工具集配置（确定性排序字节恒定，会话 kind 固定 → Tier 0 永不失效——比 triggered 集并集更简单更稳）；triggered skill 声明降级为 **schema 召回加速索引**（Tier 2 尾部）；SUB/Worker 即时并集（须 ⊆ 该集）。目标（triggered 不变 → tools 字节不变）已超额满足；`retrieval` 双层为超阈值增强（⏳，见 skill-sticky） |

**验收**：`verify_context_compression_live.py` — 非压缩期连续 3 轮 prefix 一致（对比 Gateway 请求体）；压缩后 prefix 重建仅 1 次；Near 尾部随轮次只增不减；KV Memory（scope=user|workspace）未变化时请求体字节级一致（幂等验证）。

### 13.4 L3 增强落地清单（v26 · ✅ 已实现）

> v26 把原 v25 §7.4 延后的 L3 增强升级为要做。落地明细：代码实现（2026-08-24）+ 单测全绿 + Live `verify_l3_enhancement_live.py` V-L3-1~6 全绿。

| # | 建议 | 落点 | 改动 |
|---|------|------|------|
| ㉑ | **L3 语义提取层**（§7.4.1） | `L3IngestService` 新增 `LLMSemanticExtractor` | ✅ Catalog `context.l3.semantic-extract` v2；抽取维度（用户画像/历史任务/重要实时）；与 L2 解耦；abstain 默认；**v26.2 起按轮判定**（二维数组，per-pair 置信门禁） |
| ㉒ | **L3 攒批触发 + turn-pair 合并**（§7.4.2） | `L3IngestService` 缓冲逻辑 | ✅ 累积 N=3 轮 / M=5 分钟触发；user+assistant 成对提交抽取器；**v26.2 body 非全量**——写路径单入口 `ingestTurnPair`，abstain 轮 body+semantic 均不落库（`agent.context.l3.body-gate-enabled` 默认 true；关 → 回退即时全量） |
| ㉓ | **L3 相似度去重**（§7.4.3） | rag-service 入库前 | ✅ cosine 阈值 0.95 跳过 / 0.85-0.95 合并 / ≤0.85 写入；24h 窗口 + Top-50 比对；**按 layer 隔离**（`queryRecentVectors` 加 layer 过滤，semantic/process 精炼段不与 body 跨层误删） |
| ㉔ | **L3 Milvus schema 扩展** | `sunshine_chat_history` schema | ✅ 新增 `layer` + `scene` + `status` 字段（`layer`=`body`/`semantic`/`process`，默认 `body`）；重建 collection + scene/layer 过滤检索 |
| ㉕ | **task process 层向量化**（§7.4.4） | `L3IngestService` task 写路径 | ✅ `ProcessingStep.result` 截断 200 chars → 入库 `layer=process`；Live 验证 scene=task 召回命中 + scene=chat 隔离 |
| ㉖ | **L3 session_search 召回面扩展** | `L3RecallService` | ✅ 召回面从 body 扩展到 body+process；expr 加 `layer IN ('body','process')` |
| ㉗ | **L3 定期维护**（§9.2） | `ContextMaintenanceService` 扩展 L3 维度 | ✅ 分层 TTL 清理（chat 30d / task-body 90d / task-process 7d / task-semantic 90d）；`deleteExpired(scene,layer,cutOffMs)` |
| ㉘ | **Milvus 索引更新** | Milvus 部署 | ✅ `layer`/`scene` 字段过滤检索 + `deleteByFilter(scene,layer,status)` 状态过滤清理链路 |

**v26 验收脚本扩展**：

```
verify_l3_enhancement_live.py（独立脚本，2026-08-24）：
  · V-L3-1：LLMSemanticExtractor 对噪音消息（"好的"/"谢谢"）abstain → semantic 层不写
  · V-L3-2：实质事实消息（用户偏好/历史关键结果/实时事件 3 条凑满攒批）→ semantic 层入库 + orchestrator extract 日志 + L2 已存（解耦）
  · V-L3-3：同语义 content 经 API upsert 5 次（dedupe=true）→ body 向量 ≤ 2（去重生效）
  · V-L3-4：API upsert layer=process → scene=task + layer IN(body,process) 召回命中；scene=chat 隔离
  · V-L3-5：deleteByFilter(scene,layer,status=active) 删除生效（2 → 0）
  · V-L3-6：deleteExpired(scene=chat, cutOff=30d) 仅删过期向量（40d 旧删、新近留）
```

### 13.4.1 L3 摘要化与 L2 对账（v28 · ✅ 已实现）

> v28（2026-08-27）解决 L3 语义层与 L2 重复、以及 body 原文零散 chunk（user/assistant 一条一条）无摘要价值的问题。契约：**chat 场景 L3 只保留 semantic 摘要层**，body 原文层退役；task 场景保留 body+process（`session_search` 深挖原文依赖）。

| # | 变更 | 落点 | 说明 |
|---|------|------|------|
| ㉙ | **chat body 原文层退役** | `L3IngestService.ingestTurnPair` / `ingest` / `flush` | chat 场景不再写 layer=body；仅落 semantic 摘要（`sem:{conv}:{ts}:{i}`）。task 仍走 body+process。消除「用户一个 chunk 回答一个 chunk」零散结构 |
| ㉚ | **语义提取摘要化**（方案1） | Catalog `context.l3.semantic-extract` v4 | 每段为**摘要形式**：合并同主题连续对话为一段连贯自然语言摘要，禁止按 role 切零散；保留关键细节（ID/数字/时间）；每轮 ≤2 段；新增「已由 L2 结构化覆盖的内容 abstain」 |
| ㉛ | **L2 写入对账**（方案2） | `LLMSemanticExtractor` | 写 semantic 前读取该用户 active L2 `stateValue` 集合，语义段完整包含某条 L2 值 → 整段 abstain（強命中），杜绝 L3 与 L2 重复、无增量却占向量空间；查询失败保守不拦截 |
| ㉜ | **chat 召回收敛** | `L3RecallService` | layers `body+semantic` → 仅 `semantic`；`listL3Entries`（对话面板）仅展示 semantic，role 统一为「Chunk」；task 面板保持 body+process |
| ㉝ | **Milvus list 透出 layer** | `ChatHistoryMilvusService.listByConv` / `ChatHistoryController` | `listByConv` 结果透出 `layer` 字段，供展示链路按层过滤（对话仅 semantic） |

**落地差异**：运维重建端点 `ContextAdminService.reingest` 对 chat 会话不再写 body（提示「对话 L3 已仅保留语义摘要层」）；`agent.context.l3.body-gate-enabled` 仅对 task 场景生效（chat 恒不写 body）。对话面板 `l3RoleLabel` 恒返回「Chunk」。

---

## 14. 文档关系

| 文件 | 关系 |
|------|------|
| `archive/2026-06-17-agent-memory-design.md` | 旧方案 C，已归档 |
| `archive/2026-07-17-autocontext-memory-design.md` | Layer 1 原稿，**已归档**（内容整合入本文 §4） |
| `archive/2026-07-22-context-optimization-design.md` | 三层模型原稿，**已归档**（内容整合入本文） |
| `archive/2026-07-24-dynamic-context-compression-design.md` | v2 增强原稿，**已归档**（内容整合入本文 §§5-8） |
| `archive/2026-07-22-context-corruption-audit-design.md` | 腐败审计子设计，本文 §9 引用 |
| `archive/2026-07-22-l1-admin-window-rows-design.md` | L1 Admin 工具页，小粒度 |
| `archive/2026-08-01-task-scene-context-design.md` | task 场景适配；压缩点模式据此回写本文 §5.5/§7.5/§8.2（v2 优化） |
| [business-context-authority](./archive/2026-08-13-business-context-authority-design.md) | 企业结构化权威前缀（任务板/场景偏好白名单/Policy）；装配序 policy > task > prefs > L1 > L3；不替代本五层管道 |
| [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md) | 4.14 SSOT：H1 注入 + handoff 双写；压缩点不新建（S3） |
| [archive/planner-harness-loop](./archive/2026-07-31-planner-harness-loop-design.md) | **已归档**；勿再按该文落地 |
| [archive/harness-loop-enhancement 4.7.8](./archive/2026-07-28-harness-loop-enhancement-design.md) | **已归档**；run 内能力由本文 §4.5 吸收；可选门禁/重试见 [goal-alignment §12](./2026-07-27-react-goal-alignment-design.md) |
| `phase5-operation-openness-design.md` | 运营化；5.5 工具分层对齐本文 §5.5.3；5.3 **`callSite`/`call_site`**（旧 call_scene）与会话形态 **`kind`**（旧 scene）命名隔离 |
