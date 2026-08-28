# 记忆系统「账本-视图」治理优化 — 设计方案

> 状态：**O1 中断落板 ✅ 已实现（2026-08-25）** · **O4 重建校验 ✅ 已实现（2026-08-25）** · O2 语义 merge ✅（统一压缩 §6.4 落地） · **O3 写路由收敛 ✅ 已实现（2026-08-25）** · **O5 审计与判定修正 ✅ 代码与 Catalog 已落地（2026-08-26）** — O1–O4 全部落地，O5 为线上数据实证后的根因修正
> 范围：承接 [unified-context-compression](./2026-07-31-unified-context-compression-design.md) v25/v26 与 [task-list-memory](./2026-08-14-task-list-memory-unification-design.md) M0–M3 的后续治理优化；纯后端，无前端改动。
> 起源：用户调研「经典四层 vs 生产级六层」记忆架构后逐层对照现状的收敛结论（2026-08-24）。

## 1. 背景与目标

调研方案主张**账本（原始事实）与视图（各层记忆）分离**：

| 层级 | 名称 | 核心作用 |
|------|------|----------|
| L1 | 原始事件账本 | 只追加、不修改的原始流水，唯一事实来源，上层记忆可由此重建 |
| L2 | 会话上下文 | 当前会话短期工作区（Context Window），会话结束后压缩归档 |
| L3 | 任务工作状态 | 跨会话持久化任务进度，支持长任务中断续跑，多任务隔离 |
| L4 | 语义记忆 | 提炼后的事实性知识（偏好/约定），结构化、不绑定时间 |
| L5 | 情景记忆 | 绑定时间与场景的事件记录（反馈/踩坑/异常） |
| L6 | 程序性记忆 | 沉淀的技能与 SOP |

**对照结论**：本系统 ≈ 六层方案 + **kind 双轨隔离**（chat/task 读写闸门，消除「多任务串扰」）+ **引用化**（代码只存 refs+摘要）+ **状态保真**（v21，宁缺摘要不丢状态）。四层方案的四个固有隐患（无法回溯依据 / 多任务串扰 / 技能硬编码 / 故障不可审计）现状均已基本规避。

**本 spec 目标**：不是加层，而是把已画出的层做完整——收敛 4 个优化点（O1–O4）+ 3 条防过度设计红线（§7）。

## 2. 现状映射

### 2.1 六层 ↔ 现有载体

| 六层 | 现有载体 | 差距 |
|------|----------|------|
| **L1 账本** | `chat_message` + `chat_message.steps` + AgentScope checkpoint（Redis 7d）+ JSONL offload | ✅ 已是准账本，只追加 |
| **L2 会话上下文** | L1 窗口（Near/Mid/Far）+ `ContextAssembler` 预算裁剪 + `CompactionConfig` 轮内压缩 | ✅ 完整对应 |
| **L3 任务工作状态** | fast：`tasksContext`（checkpoint）+ `react_task_board` 终态快照 + KV `todo`；pro：H1 | ✅ **O1 已补**：中断（CANCEL/ON_ERROR）经 `persistInterruptSnapshot` 落 MySQL 快照 |
| **L4 语义记忆** | KV Memory `scope=user/workspace`（`user_context_state`） | ⚠️ **O2**：语义 merge 二期可选未落地 |
| **L5 情景记忆** | L3 向量（Milvus 时间衰减）+ L1 Far 摘要 + 任务 item `fail_reason` | ✅ 主路径已覆盖（v14 砍 processTrail 是有意决策） |
| **L6 程序性记忆** | Skill Catalog + P0 + `/prompts` + `biz_scene_policy` | ⚠️ 分散但**不建议再抽象一层**（§7） |

### 2.2 Ledger-Views-Policy 对照

| 治理层 | 现状 | 结论 |
|--------|------|------|
| **Ledger** | `chat_message` + steps 只追加 | ✅ 成立 |
| **Views** | L1 窗口 / KV Memory / L3 向量 / 任务恢复块 / H1 | ✅ 齐全 |
| **Policy** | 写路由决策分散在 `L2ExtractService`/`L1Compressor`/`L3IngestService` 各自内部 | ✅ **O3 已收敛**：`ContextWritePolicy` 写路由矩阵单点 |

## 3. 优化点 O1：fast 任务中断落板（根因修正）

### 3.1 现状与缺陷链

- `react_task_board` 终态快照只在 **answer 流正常结束**路径落盘：

```307:333:orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java
    private List<StreamToken> finishAnswerStream(
            ProcessingTimelineSession session,
            ...
            GroundingVerdict grounding = validateMainGrounding(request, answerContent, session);
            ...
        return ProcessingTimelineSupport.run(session, () -> {
            session.closeContentSegment();
            session.completeThinkIfRunning();
            if (isTaskboardEnabled(request)) {
                taskBoardService.finalizeNativeTimeline(
                        session, request,
                        agent.getDelegate().getAgentState(request.userId(), request.assistantMessageId()));
            }
        });
    }
```

- 中断（CANCEL/ON_ERROR）只走 `doFinally` **保存 checkpoint**，不落板：

```266:297:orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java
                    .doFinally(sig -> {
                        // 用户主动取消(CANCEL)与系统异常中断(ON_ERROR)都保存 checkpoint，保证续跑可断点续传
                        if (request.role() == AgentRole.MAIN
                                && (sig == reactor.core.publisher.SignalType.CANCEL
                                    || sig == reactor.core.publisher.SignalType.ON_ERROR)
                                ...) {
                            try {
                                if (agent.getDelegate() != null) {
                                    agent.getDelegate().saveAgentState(request.userId(), request.assistantMessageId());
                                    ...
```

- 恢复块只从**最近一条快照**渲染，且全完成/无快照不注入：

```29:37:orchestrator/src/main/java/com/sunshine/orchestrator/taskboard/TaskBoardRestoreService.java
    public Optional<String> renderRestoreBlock(String conversationId) {
        ...
        return repository.findFirstByConversationIdOrderByUpdatedAtDesc(conversationId)
                .flatMap(entity -> toItems(conversationId, entity))
                .filter(items -> !items.isEmpty() && !TaskBoardService.allTerminal(items))
                .map(TaskBoardService::renderTaskListBlock);
    }
```

**缺陷链**：中断 → 无新快照 → 之后**新发一条消息**（非 resume 路径）装配时，`findFirstByConversationId` 命中**上一次正常结束的快照**（可能已全部完成）→ `allTerminal` 触发 → 恢复块为空 → **Agent 感知不到未完成任务**。这是 L3「跨轮/跨会话任务进度」的关键断裂。

### 3.2 目标设计

把中断路径的落板补进 `doFinally` 终态收口——任何终止信号（COMPLETE/CANCEL/ON_ERROR）都落一张快照。中断场景落的是**非终态**快照，`allTerminal` 过滤天然兜住「全完成不注入」语义，无需新增状态标记。

**落地修正（2026-08-25，时序核实后）**：初稿「全部挪入 doFinally + 移除 `finishAnswerStream` 落板」与 Reactor 时序冲突——`FluxDoFinally.onComplete` **先向下游投递 `onComplete` 再执行 hook**，即 COMPLETE 路径 `GenerationJob.handleComplete`（`persistFinal` 快照 `stepsBuffer`）同步先于 doFinally；流关闭后 `finished=true` 拒收新 token，tasks 步 done token 将丢失（持久化 steps 残留 running、前端任务板卡永驻「执行中」）。故按信号互斥收口：

| 信号 | 收口点 | 理由 |
|------|--------|------|
| COMPLETE | `finishAnswerStream`（保留） | timeline done token 须经流进 `stepsBuffer`/SSE |
| CANCEL / ON_ERROR | `doFinally` 新增 `finishTaskboardOnInterrupt` | 流已终止无消费者；先 `saveAgentState` 再读 `tasksContext`，仅写 `task_board` MySQL 快照（恢复块读表） |

- 幂等性已验证：`TaskBoardAuditService.persistFinal` 按 `assistantMessageId` upsert（`findByMessageId(...).orElseGet`），同一 msgId 重复落板只是覆盖同一条记录，不产生重复行。

### 3.3 改造点（已落地）

| 位置 | 改动 |
|------|------|
| `ReActAgentRuntime` doFinally | CANCEL/ON_ERROR 分支：`saveAgentState` 后调 `finishTaskboardOnInterrupt` → `TaskBoardService.persistInterruptSnapshot`（复用 `isTaskboardEnabled` 条件） |
| `TaskBoardService.persistInterruptSnapshot` | 新增：读 `tasksContext` → 非空则 `auditService.persistFinal`；不碰 timeline（中断时无流消费者） |
| `ReActAgentRuntime.finishAnswerStream` | 现状保留（COMPLETE 落板 + timeline 收口） |

单测：`TaskBoardTest.persistInterruptSnapshot_*` 3 例 + `ReActAgentRuntimeTest` COMPLETE/ON_ERROR/CANCEL 三分支互斥验证。

### 3.4 边界

- 仅 fast MAIN（`isTaskboardEnabled` 已限定 `role == MAIN` + Nacos `react.taskboard.enabled`）。
- Worker / SUB 不入板，pro 走 H1 导出（既有语义不变）。
- 中断时 timeline `tasks` 步未开始 → `session.hasStep(TASKS)` 为 false → 走 `applyUpdate`+`completeOnRunEnd` 分支，语义正确（进度落地且收口）。
- 用户主动取消后不再续跑、直接新建会话：无快照可读 → 不注入（符合 M0「无快照不恢复」语义）。

## 4. 优化点 O2：L4 语义 merge 落地（承接统一压缩 §6.4）

### 4.1 现状

统一压缩 v25 将语义 merge（§6.4）标注**二期可选**；现状为**字面 + key 规范化门禁**（`L2ConflictMerger` 冲突合并 + `auditL2` 审计）。语义相近的 key 各自成条 / 矛盾并存的污染路径仍在（spec 已记录线上反例），这是 Policy 层「记忆写入仲裁」的核心缺口。

### 4.2 设计要点（承接既有 spec，不重复造轮子）

落地点统一压缩 §6.4 已画好，本 spec 仅排期前移并约束边界：

| 组件 | 设计（引用统一压缩 §6.4） |
|------|--------------------------|
| `L2SemanticMergeService.java` | 写路径语义候选检索 + LLM 判定 NOOP/MERGE/UPDATE/CONFLICT；输入含 `background` 消歧 |
| Catalog `context.l2.merge` | 语义判定标准（含 UPDATE vs CONFLICT 双标） |
| Nacos `agent.context.l2.semantic-merge` | 开关，默认 false，灰度开启 |
| `background` 列 | 已含在 v20/v22 设计；语义 merge 判定前需先有 background 保障（缺 background 的新写入丢弃，既有门禁） |

**约束**：
- 与 task-scene §5.2 workspace 侧语义 merge 同节奏、同判定口径（`context.l2.merge` 单一 Catalog 分支），不重复实现。
- 判定结果一律走 `auditL2` 审计，保证「为何不写/为何覆盖」可追溯（对齐 Policy 可审计诉求）。
- 判定为 CONFLICT 时**不注入**（宁缺毋滥），只在审计记录。

### 4.3 落地清单

1. DDL `background` 列（`user_context_state`，种子 SQL 全量同步）+ 注入展示。
2. Catalog `context.l2.merge` prompt 上线（bump `prompt_catalog_meta.catalog_version`）。
3. `L2SemanticMergeService` 接线到 `L2StateStore.upsertInternal`（user/workspace 双侧）。
4. Nacos 开关默认 false → 灰度 → 默认 on。

## 5. 优化点 O3：Policy 写路由收敛（写路由矩阵）✅ 已实现（2026-08-25）

### 5.1 收敛前现状

`ContextWritePath` 已是**唯一写入口**（符合「唯一闸门」纪律），但它是「顺序调用三个 Service」而非「策略决策点」——kind 闸门 / scope 路由 / TTL / 门禁分散在各 Service 内部（`ContextWritePath` if/else、`L2ExtractService` 内 `extract`/`extractWorkspace` 分支与置信门禁、`L1Compressor.resolveL2Block`、`L2StateStore` L2 TTL 表、`ContextMaintenanceService` L3 分层 TTL）。

### 5.2 落地（重构，不新增语义；路由结果与收敛前一致）

收敛为**写路由矩阵 + 写入门禁 + TTL 表**的单点策略组件 `ContextWritePolicy`（`@Component`，纯策略、不触库、不调 LLM）：

| 决策面 | 收敛前 | 收敛后 |
|--------|--------|--------|
| kind 闸门 + scope 路由 | `ContextWritePath` if/else | `route(conv)` 写路由矩阵 → `WriteDecision` |
| 写入门禁 | `L2ExtractService` 置信门禁 + v22 门禁 | `l2MinConfidenceFor` + `l2TodoGatePasses`（Policy 静态方法） |
| L2 TTL | `L2StateStore.ttlDays` | `ContextWritePolicy.l2TtlDays`（`L2StateStore.expiresAtFor` 委托） |
| L3 分层 TTL | `ContextMaintenanceService` 内联 | `ContextWritePolicy.l3TtlDays`（`gcL3Expired` 委托） |
| 审计 | 仅 `auditL2` | `WriteDecision.reason()` 携带可读理由，写路径落 info 日志 |

**写路由矩阵**（`ContextWritePolicy.route`）：

```
kind × mode        | L2 抽取 | L3 向量化 | scope     | scene
------------------ | ------- | -------- | --------- | -----
task × workflow    |   否    |    否    |    —      | task（task-scene §2.2 退出统一上下文链路；L1 折叠照常）
task × fast/pro/空 |   是    |    是    | workspace | task
chat × *           |   是    |    是    | user      | chat
```

`ContextWritePath.runAsync` 据此分支执行（`decision.writeL2()` → scope 选 `extract`/`extractWorkspace`；`decision.writeL3()` → `ingestTurnPair(scene)`），并输出路由决策 info 日志（conv/kind/reason → l2/l3/scope）。

**价值**：写决策单点可解释、可审计——直接回应「故障不可审计」的生产级诉求。参数全部对齐既有 Nacos `agent.context.*`，不新增配置面。

**配套清理**：删除 `L2ExtractService.extractAsync` 死代码（@Async 自调用无效）；`L2ConflictMerger.normalizeKind/normalizeStatus` 提为 `public` 供 Policy 跨包引用（同 `L1Compressor.shouldCompressAtPoint` 先例）。

## 6. 优化点 O4：账本可回放（视图重建校验）

### 6.1 现状

`conversation_context_l1` 是**覆盖式 upsert** 视图（`L1Compressor` → `l1Store.upsert`），无版本。六层方案强调「视图可由账本重建」，现状缺失「视图与账本一致性」的运维校验能力。

### 6.2 设计（低成本运维能力，不做视图版本化；✅ 已落地）

约束「复用既有 `TokenEstimator` / 分区逻辑，禁止复制实现」意味着对账算法必须与 `L1Compressor` **同进程同源**——Python 无法复用 Java 分区。故对账落 Java 侧只读端点，脚本做驱动与汇总：

| 组件 | 落地 |
|------|------|
| `ContextAdminService.verifyRebuild(convId)` | 账本重放（与 `ContextWritePath` 同过滤：非 streaming、user/assistant、正文非空）→ `L1Compressor.groupRounds/partition/partitionByPoint/roundFullyFolded/shouldCompress*` 同源分区 → 对账 `conversation_context_l1` |
| `GET /api/admin/context/l1/rebuild-check` | orchestrator 只读端点 + BFF 透传（`/api/admin/context/l1/rebuild-check`） |
| `scripts/verify_context_rebuild.py` | 驱动脚本：扫描近 `REBUILD_SCAN_LIMIT` 会话 / `--conv-id` 单会话 / `--self-test` 夹具正负例（45 轮账本 + 一致视图 → PASS；删 L1 行 → ERROR(H1)；恢复 → PASS；退出即清理） |

**判定分级**（Mid/Far 摘要为 LLM 输出，不重放重算，仅校验结构不变量）：

| 级 | 不变量 | 含义 |
|----|--------|------|
| ERROR | H1 | 账本已达压缩条件（`shouldCompress`）但 L1 视图缺失（删行/压缩失败 = 内容丢失） |
| ERROR | H3/H4 | 折叠引用不在远窗区（分区失配）/ 折叠链非空但 `far_summary` 为空 |
| ERROR | H6 | 压缩点前缀不变量破坏（折叠轮与活跃轮交错） |
| WARN | S1–S6 | 可解释漂移：政策/窗口漂移、孤儿引用、异步收敛滞后、中窗滑出、摘要缺失回退原文 |

**约束遵守**：只读校验不写库（脚本仅 `--self-test` 写自建夹具且退出即删）；对账与 `L1Compressor` 同源（直接调用其静态分区与触发判定）；一期仅脚本人工触发，不进 `ContextMaintenanceJob` 定时。

### 6.3 验收（✅ 2026-08-25）

- 单测 `ContextAdminRebuildCheckTest` 8 例：滑动窗一致视图 PASS / 视图缺失 ERROR(H1) / 阈值之上无视图 PASS / `far_summary` 缺失 ERROR(H4) / 折叠越区 ERROR(H3) / 压缩点前缀 PASS / 交错折叠 ERROR(H6) / 中窗滑出 WARN。
- Live：`--self-test` 正负例全绿；扫描近 5 会话（含 2 个 task 压缩点会话）一致率 100%，0 ERROR 0 WARN。

## 7. 优化点 O5：审计与语义判定修正（线上数据实证后的根因修正）

### 7.1 问题现场（2026-08-25/26 线上 `user_context_state` 快照实证）

同一用户两轮数据快照暴露 5 类缺陷，全部可归因到代码路径：

| 编号 | 现象 | 根因 |
|------|------|------|
| P1 | 有效事实被审计误杀（`fact/travel.destination_country`「用户计划明天前往美国」被 void） | `ContextLlmAuditClient.buildL2Payload` **不喂 background**（08-08 v1 载荷），审计 LLM 无从区分「确定性陈述」与「过期猜测」；`context.l2.audit` 提示词未随 v20/v22 background 机制演进 |
| P2 | 冗余被误诊为矛盾（两条「常住北京」双标 conflict） | 审计决策只有 `voidIds/conflictIds` 两档动作，无 merge；冗余 ≠ 矛盾，正确动作是归一 |
| P3 | 偏好演进判 CONFLICT 而非 UPDATE，且 conflict 是死状态（永不回收） | 审计口径无「演进豁免」；`ContextMaintenanceService` 只清 superseded/void/active 过期，conflict 无生命周期 |
| P4 | 跨 kind 重复（「北京」一条 `fact`、一条 `profile`；「美国」三条跨 key） | 语义候选检索**只查同 kind**（`findBy...KindAndStatus`）；抽取时非 todo 类**无既有 key 参照**（仅 `existingTodoHints` 喂 todo），模型每轮自由起 key |
| P5 | 用户疑问被抽成事实（「用户询问'明天要去哪里'，助手据此提取」） | 抽取提示词无「事实来源」约束：用户疑问句与助手回答被反向沉淀为用户画像，溯源方向反了 |

另有实证：`response.detail_level` 下一轮原样重复输出走字面快路径刷新（6 轮窗口重复判定成本实锤）；「偏好简洁」三轮产出三个措辞变体形成 superseded 链（同义措辞漂移——`sameValue()` 纯字面比较认不出「仅给出」与「只给」）。

### 7.2 根因定位（两条结构性缺口）

1. **抽取侧无全量参照**：模型每轮从 6 轮窗口自由抽取，拿不到既有非 todo 条目的 key/kind/value 参照 → 措辞漂移（字面快路径失效 → 走置信合并产生 superseded 链）+ key/kind 漂移（跨 kind 重复）。
2. **审计侧信息不全 + 口径过严**：载荷缺 background、判定口径只有两档动作且无演进/冗余豁免 → 误杀有效事实、误诊演进与冗余；且 conflict 无生命周期，误诊后永久滞留。

### 7.3 修正设计（五处，全部根因级，无补丁）

| # | 修正 | 落点 | 对应问题 |
|---|------|------|----------|
| ① | 抽取时注入**全量**既有状态参照（`existingTodoHints` → `existingStateHints`：kind/key/value 全量；todo 保留完成/取消语义） | `L2ExtractService` | P4/P5 + 措辞漂移 |
| ② | 审计载荷补 **background** 字段 | `ContextLlmAuditClient.buildL2Payload` | P1 |
| ③ | 语义候选检索从**同 kind 扩到跨 kind** 全量 active（`findBy...AndStatus`；`task.*` 仍排除） | `L2StateStore.semanticVerdict` | P4 写路径 |
| ④ | 规则审计增加**跨 kind 同 value 去重**（同值多条 → 保留 kind 具体性最高者：profile > fact > preference，其余 void；与既有同 key 去重并列） | `L2RuleAuditor.dedupeSameValue` + `kindSpecificity` | P2/P4 存量 |
| ⑤ | **conflict 生命周期**：`voidRetentionDays` 天未澄清 → 自动转 void（随 `ContextMaintenanceJob` tick） | `ContextMaintenanceService.cleanupLongConflict` | P3 |

### 7.4 Catalog 修订（种子 `19-sunshine-resource.sql` 全量，catalog_version 149 → 150）

| Catalog | 修订 |
|---------|------|
| `context.memory.extract` | v2：增量约束（既有条目未变化不重复输出；变化时沿用原 key/kind/措辞）；**事实来源约束**（仅从用户主动陈述抽取；用户疑问句与助手回答不作事实来源，除非用户后续主动确认）；kind 归类稳定；background 须标注来自用户哪句陈述（勿写「助手确认/提取」） |
| `context.l2.audit` | v2：判定口径宁松勿紧——① 仅明确错误才 void；② 仅无法用时间演进解释的客观互斥才 conflict；③ **偏好演进/改主意豁免**（写路径已按时间优先处理）；④ **同义重复豁免**（规则去重处理）；⑤ 有 background 佐证的用户陈述不得仅凭「可能过期」void；⑥ 时效性陈述按当前时间判断 |

> 注：`context.l2.merge` 写路径判定不改动——演进场景本应由写路径 UPDATE 承接（§7.1 P3 的真正防线），本次通过抽取参照（同 key 沿用）+ 审计豁免双保险降低其漏网率。

### 7.5 不做的事（承接 §8 红线）

- **不给审计加 mergePairs 动作**：跨 kind 归一由规则去重（④）承接，审计保持只读判定，不引入第三种写动作——避免审计层变成第二个写路径。
- **不扩抽取窗口/不改 `sameValue()` 语义比较**：重复候选由增量约束（Catalog）从源头收敛；字面快路径保持纯字面，语义归一归写路径语义判定（③ 已扩跨 kind）。

## 8. 排除项（防过度设计红线）

| 候选 | 否决理由 |
|------|----------|
| 严格事件溯源（全量事件类型 + 事件总线 + append-only 任务状态账本） | `chat_message`+steps 已是准账本；任务状态/偏好再事件化是重架构，违背「简单设计」纪律，收益不抵成本 |
| L6 程序性记忆独立建模 | Skill Catalog + P0 + `/prompts` + `biz_scene_policy` 已是「注册 + Catalog 驱动」且正被 skill-sticky 收敛；真正优化是推进 skill-sticky（S-0/S-D/S-T/S-1）与 business-context-authority，而非再抽象「程序性记忆管理器」 |
| L5 独立「情景事件」载体 | v14 砍 processTrail 的决策（防双写漂移）仍正确；`fail_reason` + steps + L3 向量 + session_search 已覆盖主路径 |

## 9. 验收标准

后端（可用脚本验证）：

1. **O1**：✅ `scripts/verify_taskboard_interrupt_live.py`（I1–I4 全绿，2026-08-25）——发起 fast 任务对话生成任务板 → 中断（取消）→ 新发一条消息 → 断言恢复块注入前置（最近快照含未完成项）+ 模型回复引用未完成项关键词；同一 msgId 仅一条 `task_board` 记录（幂等）。
2. **O2**：语义 merge 开启后，同 background 不同表述写入 → 触发 MERGE 且 `auditL2` 记录判定；矛盾事实 → CONFLICT 不注入；开关关闭时行为与现状一致（回归）。
3. **O3**：✅ 写路由矩阵单点——`ContextWritePolicy` 决策记录齐全（`WriteDecision.reason()` + 写路径 info 日志「写/不写 + 理由」）；kind/scope/scene 路由结果与现状一致（`ContextWritePathTest` 3 例回归全绿）；门禁与 TTL 表收敛单点（`ContextWritePolicyTest` 16 例）。
4. **O4**：✅ `scripts/verify_context_rebuild.py` 对近期会话重建一致率 100%（0 ERROR 0 WARN）；`--self-test` 删一条 L1 行 → 脚本报 ERROR（H1 账本可重建而视图缺失），恢复后回 PASS。
5. **O5**：① 同用户多轮对话后，`user_context_state` 中同一事实不再出现跨 kind 重复（规则去重兜底）；② 偏好演进（简洁→详细）不再被审计标 conflict（演进豁免）；③ 有 background 佐证的确定性事实不被审计 void；④ conflict 滞留超 `voidRetentionDays` 自动转 void；⑤ 抽取输入含全量既有状态参照，重复候选显著减少（回归：`L2ExtractServiceParseTest`/`ContextWritePathTest` 全绿）。

前端（**不由 agent 自测，人工按以下步骤验证**）：

1. 打开 fast 会话，发一个带任务列表的多步任务，等待任务板出现。
2. 在任务执行中点击取消。
3. 在输入框新发一条普通消息（**不点续跑**），观察 Agent 回复中是否带【任务清单】未完成任务恢复块。
4. 全部任务完成后重发一条消息，确认无恢复块注入。

## 10. 实施顺序

| 波次 | 内容 | 依据 | 状态 |
|------|------|------|------|
| 1 | **O1** 中断落板（无 DDL、无 Catalog、根因修正，成本最低） | 独立可验 | ✅ 2026-08-25（信号互斥收口，见 §3.2 修正） |
| 2 | **O4** 重建校验脚本（纯运维只读，可并行 O1） | 独立可验 | ✅ 2026-08-25（同源对账端点 + 驱动脚本；见 §6.2/§6.3） |
| 3 | **O2** 语义 merge（需 DDL `background` + Catalog prompt + 开关灰度） | 依赖统一压缩 §6.4 既有设计 | ✅ 2026-08-25（随统一压缩 §6.4 提前落地） |
| 4 | **O3** 写路由收敛（重构；等 O1/O2 落点稳定后做，避免返工） | 依赖 O1/O2 落点 | ✅ 2026-08-25（`ContextWritePolicy` 写路由矩阵 + 门禁 + TTL 表单点；路由结果回归一致；见 §5.2） |
| 5 | **O5** 审计与判定修正（线上数据实证驱动；代码五处 + Catalog 两个，无 DDL） | 依赖 O2/O3 落点 | ✅ 2026-08-26（`existingStateHints` 全量参照 / 审计载荷补 background / 语义候选跨 kind / 跨 kind 同值去重 / conflict 生命周期；Catalog `context.memory.extract`+`context.l2.audit` v2，catalog_version 150） |

> 每一波完成后同步更新本 spec 状态与 `specs/README.md` 活跃索引；**O1–O5 已全部落地**（O1–O4：2026-08-25；O5：2026-08-26）。O5 上线需：重启编排器 + `python scripts/sync_nacos.py` 不受影响（无 Nacos 变更）+ 线上库执行 Catalog 同步（种子全量快照策略）。

## 11. 变更记录

| 版本 | 日期 | 内容 |
|------|------|------|
| v1 | 2026-08-24 | 初稿：四层 vs 六层对照结论 + O1–O4 + 排除项红线 |
| v2 | 2026-08-25 | O1 落地：时序核实发现「全挪 doFinally」与 GenerationJob persistFinal 冲突，改为信号互斥收口（COMPLETE 走 `finishAnswerStream`，CANCEL/ON_ERROR 走 `doFinally` → `persistInterruptSnapshot`）；orchestrator 1251 单测全绿；Live `verify_taskboard_interrupt_live.py` I1–I4 全绿（取消落快照/恢复块注入证据/幂等） |
| v3 | 2026-08-25 | O4 落地：同源对账约束决定重建校验落 Java 侧 `ContextAdminService.verifyRebuild`（复用 `L1Compressor` 分区 + `TokenEstimator`），只读端点 `rebuild-check` + BFF 透传；`verify_context_rebuild.py` 扫描/单会话/`--self-test` 三模式，判定分级 ERROR（H1/H3/H4/H6）/WARN（S1–S6）；`shouldCompressAtPoint` 提为 public 供跨包同源调用；单测 8 例 + 全量回归 + Live 正负例全绿 |
| v4 | 2026-08-25 | O3 落地（O1–O4 全部完成）：新建 `ContextWritePolicy` 写路由策略单点——`route()` 矩阵（kind×mode → writeL2/writeL3/scope/scene + reason）、`l2MinConfidenceFor`/`l2TodoGatePasses` 写入门禁、`l2TtlDays`/`l3TtlDays` TTL 表；`ContextWritePath` 委托 Policy 并落路由决策 info 日志；`L2StateStore.expiresAtFor`、`ContextMaintenanceService.gcL3Expired`、`L2ExtractService` 门禁全部委托；删除 `extractAsync` 死代码，`normalizeKind/normalizeStatus` 提 public；`ContextWritePolicyTest` 16 例 + `ContextWritePathTest` 路由回归；clean 全量 1274/1274 全绿（首轮 107 Mockito 错误为增量缓存类重定义，clean 后消失） |
| v5 | 2026-08-26 | O5 新增并落地（线上 `user_context_state` 快照实证 5 类缺陷后根因修正）：代码五处——① `existingTodoHints`→`existingStateHints` 全量既有状态参照（防 key/kind/措辞漂移）② 审计载荷补 background ③ 语义候选检索同 kind→跨 kind 全量 active ④ `L2RuleAuditor.dedupeSameValue` 跨 kind 同值去重（kindSpecificity profile>fact>preference）⑤ `cleanupLongConflict` conflict 滞留超期转 void；Catalog 两个——`context.memory.extract` v2（增量输出/事实来源约束/kind 归类稳定）+ `context.l2.audit` v2（演进与同义重复豁免/有佐证陈述防误杀），种子全量快照同步、catalog_version 150、prompt_version 归一 version=1（约定）；相关单测回归全绿 |
