# 任务清单记忆一体化（Task List Memory Unification · 清爽收敛版）

> **日期**：2026-08-14（v2 收敛）· **v1**：2026-08-14 初稿
> **状态**：**M0 ✅ 已实现**（2026-08-23，fast 跨轮任务板恢复；Live `verify_task_list_restore_live.py`）· **M1 ✅ 已实现**（2026-08-23，KV Memory 统一 + `todo` 类 + scope 闸门；Live `verify_kv_memory_todo_live.py` T1–T5 全绿）· **M2 ✅ 已实现**（2026-08-24，pro 终态导出；Live `verify_pro_todo_export_live.py` P1–P6）· **M3 ✅ 已实现**（2026-08-24，session_search 收缩版 body + scope=session；Live `verify_session_search_live.py` P1–P4 全绿）
> **编号**：阶段四增量（上下文记忆 / 长任务执行能力）
> **一句话**：长任务续跑的关键是**未完成任务清单**。本方案把它统一为**两级作用域**——**会话级执行态**（fast `TaskList` / pro `H1`，跨轮恢复的真相源）+ **KV Memory 沉淀**（终态/显式停下时导出「未完成任务」到一张表，`scope=workspace`（task）/ `scope=user`（chat））。召回按「**先同会话、再跨会话**」装配；执行中**不双写**。
> **v2 收敛要点（对齐五层 v25 / task-scene v14）**：
> 1. **砍 T0**（task-scene §6.1 全套双块 + processTrail + extract/condense）——会话级任务状态真相源已有（`AgentState.tasksContext` + `react_task_board` 终态快照），T0 是第三份拷贝且与 taskboard 双写漂移。失败路径保真由**任务 item 自带 `status/fail_reason`** 承接。
> 2. **L2 + W0 统一为一张 `KV Memory`**（`scope=user|workspace` 列）——同一模型、同一抽取服务（prompt 按 scope 参数化）、同一注入渲染。本方案不再自创「L2 task 类 / W0 todo 类」两套，统一为 KV Memory 的 `kind=todo`。
> 3. **session_search 收缩**：一期仅 `body 层 + scope=session`（工具结果在 `chat_message.steps` 直接可查，不进向量）；`scope=workspace` 延后。
> **关联**：[unified-context-compression](./2026-07-31-unified-context-compression-design.md)（五层 SSOT · L-state）· [task-scene-context](./2026-08-01-task-scene-context-design.md)（KV 闸门 · 场景隔离）· [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md)（H1 SSOT）· [business-context-authority](./2026-08-13-business-context-authority-design.md)（企业任务板，**边界隔离**）· [unified-routing v6](./2026-07-29-unified-routing-design.md)（`kind`/`executionMode`）

---

## 1. 问题

长任务执行的关键载体是「未完成任务清单」。现状是三个孤岛，且设计稿里又叠了一套 T0：

| 载体 | 模式 | 存储 | 跨轮次 | 跨会话 |
|------|------|------|:---:|:---:|
| **AgentScope TaskList**（`todo_write` → `AgentState.tasksContext`） | fast | Redis checkpoint（key 含 `assistantMsgId`） | ❌ 不恢复 | ❌ |
| **H1 PlanNotebook** | pro | Redis `sunshine:plan:notebook:{conversationId}` TTL 7d | ✅ load 恢复 | ❌ 完成即失联 |
| **KV Memory（原 L2/W0）** | 通用 | MySQL | ✅ 注入 | ✅ 跨会话，但**无 `todo` 类** |
| **T0（设计稿）** | task×fast | — | — | — **第三份拷贝，双写漂移，砍** |

**缺口收敛后只剩两条**：
1. **fast TaskList 跨轮不恢复**——`AgentState` 按 `assistantMsgId` 隔离，新消息空板；终态快照 `react_task_board` 只做审计不回灌。长任务「继续」靠模型猜清单。
2. **执行态与记忆层无结构化接缝**——`todo_write`/H1 写完锁死在执行态，不沉淀到 KV Memory；新会话无法续接。

**本质**：任务清单是压缩 spec §2.1 的 **L-state（执行状态）**，须进结构化载体。它比普通记忆多一个维度：**执行态 vs 记忆**。本方案只解决这一条缝。

---

## 2. 目标与非目标

### 2.1 目标

1. **两级作用域**：会话级执行态（真相源）+ KV Memory 沉淀（跨会话依据），一条沉淀通道。
2. **fast 跨轮恢复**：同会话新消息读 `react_task_board` 最近快照 → 注入【任务清单】块。
3. **pro 终态导出**：ANSWER/显式停下 → H1 未完成项 → KV Memory。
4. **chat/task 跨会话召回**：KV Memory `scope=user`（chat）/ `scope=workspace`（task）的 `kind=todo` 注入。
5. **hybrid 沉淀**：会话终态导出 + 用户显式停下/换题补一次；**执行中零写入**。

### 2.2 非目标

- **不物理合一**：执行态（TaskList/H1）与记忆（KV Memory）是两种性质，不合并存储。
- **不双写权威态**：执行中只认 `AgentState.tasksContext` / `H1`；KV Memory 只做**沉淀副本**。
- **不做 T0**：task-scene §6.1 全套作废（v14 同步），由本方案 §4 承接。
- **不与 `business_task` 合并**（business-context-authority 硬约束）：企业业务权威态 vs agent 执行态，不合并不双写。
- **Worker 二级板不入记忆**：pro 只认 H1 一级板。

---

## 3. 核心设计：两级作用域 + 一张 KV Memory

```
┌─ 会话级执行态（真相源 · 高变化频率 · 不落记忆）──────────┐
│   fast: AgentState.tasksContext（run 内实时 + checkpoint）│
│   pro:  H1 PlanNotebook（Redis，跨轮注入）               │
│   → 跨轮恢复注入（§4）；终态/显式停下 → 沉淀（§5）        │
└─────────────────────────────────────────────────────────┘

┌─ KV Memory（沉淀副本 · 低频稳定 · 跨会话依据）─────────────┐
│   一张表（user_context_state + scope 列）：               │
│     scope=user      （chat 读写；原 L2）                  │
│     scope=workspace （task 读写；原 W0）                  │
│   kind 收敛：fact / constraint / decision / todo /       │
│              profile / preference / goal / agreement     │
│   （todo = 本方案新增：未完成任务清单）                    │
└─────────────────────────────────────────────────────────┘
```

- **KV Memory 唯一性**：`(scope, tenant, user_or_workspace, kind, key)` 至多一条 active；与 L2 v20/v22（宁缺毋滥、key 场景化、value 自解释、background）同规则。
- **todo 类 schema**（与执行态同形子集，对齐 H1 `TaskItem` 五态）：

```
kind=todo / key={domain}.{facet}（如 finance.pending_approval）
value=自解释命题短句（「跟进审批单 PR-2026-0812 状态」）
background=成立场景 · status=active|done|void · TTL=7d（短）
items?[]= {id, label, status, fail_reason?, refs?[]}（可空，单条目标时仅 value）
```

- **引用化（v12）**：`refs` 只存 `path:line`/单据号，不存内容。
- **有界**：单条 todo 注入 ≤ 20 项，超出折叠为一句总结。

---

## 4. 召回层次：先同会话，再跨会话

```
① 会话级任务清单（精确注入 · Tier 2 尾部 · query 前）
   fast：同 conversation 最近一条 react_task_board 快照 → 渲染【任务清单】块
   pro： H1 renderForPlanner（既有）
   → 最高保真：同一任务链的直接延续
② KV Memory todo（结构化注入 · Tier 1）
   scope=workspace → task 会话（工作区未完成任务）
   scope=user      → chat 会话（用户未完成任务）
   → 跨会话续接：换会话不丢未完成项
③ session_search（按需工具 · 不进前缀 · 一期仅 body+scope=session）
   → 深挖：注入不够时按需恢复本会话历史正文
```

**对齐原则**：① 高频动态放 Tier 2 尾部（只 miss 尾部）；② 低频稳定放 Tier 1（content-hash 幂等 upsert 字节不变）；③ 按需不占前缀。**「先同会话再跨会话」是 prefix 稳定下的自然结果**。

**去重**：① 与 ② 相同 `goal`/`label` 只注入最近一层（① 优先）。

---

## 5. 各场景落点

### 5.1 fast 跨轮恢复（修复最大缺口 · M0）✅

**方案**：复用终态快照 `react_task_board`（已有 `conversation_id` 索引，无 status 字段——天然只落已完成消息）：

```
fast 新消息装配：
  读同 conversation 最近一条 react_task_board
  存在 pending/running 项 → 渲染【任务清单】块（query 前）
  注入语义：接续未完成项；已完成项标注；不重建整个任务板
```

- 零新存储：只需把读路径从「按 messageId 审计」扩展到「按 conversationId 取最近」。
- 渲染复用 resume 路径（`ReactResumeContextSupport.appendTasksBlock`）同构实现。
- **实现落点（2026-08-23）**：`TaskBoardRestoreService.renderRestoreBlock`（`findFirstByConversationIdOrderByUpdatedAtDesc` + `allTerminal` 判定）+ `ChatStreamContextFactory.prepareNewMessage`（仅 `ExecutionMode.FAST` 注入，渲染在 L3 前）；单测 `TaskBoardRestoreServiceTest` / `ContextMessageBuilderTest`，Live `verify_task_list_restore_live.py`。

### 5.2 pro 终态导出（补 H1 出口 · M2 · ✅ 已实现）

pro（Planner-Executor）会话收束（success / error / cancel 三态，`loop.run.doFinally`）时，从 `PlanNotebook.taskQueue` 导出未完成项（pending / in_progress / fail）→ KV Memory `todo`：

- `kind=task` → `scope=workspace`（workspaceId 经 conversation 反查）；`kind=chat` → `scope=user`
- 确定性结构导出（非 LLM 抽取）：`H1TodoExportService.export` → `L2StateStore.syncTodoExport(Workspace)`
- key 编码 `task.{goalHash8}.{baseTaskId}`：goalHash = originalGoal SHA-256 前 8 hex，同一 goal 跨会话同前缀；baseTaskId 去版本后缀（t1-2 → t1）
- value = task label（自解释）；background = goal；confidence = 1.0；status = active
- 幂等：全量对比——本次未完成 key 集合之外的 `task.` 前缀 active 行显式 void（覆盖「全部完成」「换题 goal 变」「重复导出不膨胀」）
- 仅管理 `task.` 前缀，不触碰 LLM 抽取产生的其他 domain todo；`context.memory.extract` prompt 禁止 LLM 产出 `task.` 前缀（v144）
- 已完成项不沉淀；同会话续跑仍以 H1 为准（Redis 未删，7d TTL）；导出失败仅记日志，不阻断用户路径

### 5.3 KV Memory todo 类（chat L2 / task W0 统一）

**决策（用户拍板）**：L2 + W0 统一为一张 `KV Memory`，新增 `kind=todo`：

| 维度 | 规则 |
|------|------|
| 表 | `user_context_state` + `scope` 列（`user`/`workspace`）；workspace 行以 `(tenant, workspace_id)` 为键 |
| `kind` | 新增 `todo`（代码 `VALID_KINDS` 加项；Catalog `context.memory.extract` 按 scope 参数化加类） |
| `key` | `{domain}.{task_facet}` |
| `value` | 自解释命题短句（v22）；禁裸 todo / 会话代号 |
| `status` | `active`（注入）/ `done` / `void`（不注入） |
| `TTL` | 7d；完成/取消即时 `void` |
| 门禁 | 仅「用户主动提出、跨会话仍有效、未完成」入库；会话计划/单次迭代**禁止**进（对应五层 §6.3.5 反例） |

注入渲染（L2 块内，scope 不展示）：

```
[用户状态 · L2]
- todo / finance.pending_approval: 跟进审批单 PR-2026-0812 状态  （背景：OA 审批）
```

---

## 6. 沉淀通道（hybrid · 不双写）

```
执行中（零记忆层写入）：tasksContext / H1 实时权威
沉淀触发：
  A. 会话终态：assistant COMPLETED（fast）→ 从 tasksContext 导出未完成项
              / ANSWER（pro）→ 从 taskQueue 导出未完成项
  B. 用户显式停下/换题：stop / pause / 新话题接管 → 补一次导出
落点：kind=task → KV Memory scope=workspace
     kind=chat → KV Memory scope=user
失效：模型/tool 标记完成（todo 全 done）→ void；或 TTL 到期 → void
```

- **单写通道**：执行态 →（沉淀）→ KV Memory，方向唯一；KV Memory 从不写回执行态。
- **幂等**：同一 `goal` 重复沉淀以 `updated_at` 覆盖。
- **为什么终态而非每轮**：执行中任务变化高频，每轮写入破坏 Tier 1 稳定（压缩点 C1）；终态沉淀一次字节稳定。

---

## 7. 与既有设计边界

| 边界 | 结论 |
|------|------|
| vs `business_task`（business-context-authority） | **隔离**：企业业务权威态（业务工具回写/Policy/HITL）vs agent 执行态（模型 todo/H1 产出）。不合并命名、不双写、不互相替代 |
| vs T0（task-scene §6.1） | **作废**：会话级任务状态真相源 = `tasksContext` + `react_task_board`；失败路径由任务 item `fail_reason` 承接；processTrail 不再单独建块 |
| vs skill-sticky（triggered 集） | 任务清单召回与 skill triggered 正交；换题即沉淀 + 清空 sticky seed |
| vs 五层压缩 | 沉淀副本 Tier 1（幂等）；会话级恢复块 Tier 2 尾部；均不进 Near/Mid/Far 折叠、不移动 `far_folded_msg_ids` |
| vs H1 | H1 仍是 pro 计划态唯一 SSOT；沉淀副本是只读快照，不回写 H1 |

---

## 8. 装配时序（对齐 business-context §2.2）

```
① 轻量会话底座（L1 partition / guide）
② 意图收集 → skillIds / agentIds
③ 资源召回后：
   fast → 读 react_task_board（conversationId 最近）→ 【任务清单】块（Tier 2）
   pro  → H1 load → renderForPlanner（既有）
④ KV Memory 召回（可与 ③ 并行）：
   task → scope=workspace todo（Tier 1）
   chat → scope=user todo（Tier 1）
⑤ session_search（按需工具，一期 body+session）
⑥ PromptComposer + Toolkit → 主 LLM
```

**依赖**：本方案在记忆装配层（③④）落地，依赖 task-scene P1/P2（读写闸门）透传 `kind`/`workspaceId`/`executionMode`；无该闸门时先按「chat/task 都注入会话级 + user scope」过渡。

---

## 9. 落地分期

| 阶段 | 内容 | 依赖 | 验收 |
|------|------|------|------|
| **M0** | fast 跨轮恢复：`react_task_board` 按 conversationId 取最近 → 注入【任务清单】块 | 无（复用既有表与 resume 渲染） | **✅ 已实现**（2026-08-23；`TaskBoardRestoreService` + FAST 新消息注入；Live `verify_task_list_restore_live.py` T1–T4 全绿）：长任务「继续」看到未完成任务；已完成项标注；全 terminal / 无快照不注入 |
| **M1** | KV Memory 统一 + `todo` 类：`scope` 列 + `VALID_KINDS` + Catalog extract 参数化 + v22 门禁 | task-scene P1/P2（闸门） | **✅ 已实现**（2026-08-23；`L2StateStore` scope 路由 + `context.memory.extract` 参数化 + todo 生命周期 + 读写闸门；Live `verify_kv_memory_todo_live.py` T1–T5 全绿）：chat 沉淀用户 todo 新会话注入；task 沉淀工作区 todo；完成即 void；chat/task 隔离 |
| **M2** | pro 终态导出：ANSWER/显式停下 → H1 未完成项 → KV Memory | M1 | **✅ 已实现**（2026-08-24；`H1TodoExportService` + `PlannerHarnessExecutor.doFinally` 三态收束导出 + `L2StateStore.syncTodoExport` 全量对比 void + key 编码 `task.{goalHash8}.{baseTaskId}`；Live `verify_pro_todo_export_live.py` P1–P6 全绿）：pro 会话收束后未完成项（pending/in_progress/fail）按会话 kind 分流（task→workspace / chat→user）沉淀 KV `todo`；幂等覆盖不膨胀；全完成/换题 → `task.` 前缀 active 行全量对比 void；pro 新会话续接未完成任务 |
| **M3** | session_search 收缩版（body + scope=session）：task 会话按需恢复本会话正文，不进前缀 | L3 task 通道 | **✅ 已实现**（2026-08-24；`SessionSearchTool` + rag-service convId 过滤 + `ContextAssembler` task 跳过 L3 自动注入 + Nacos `react.session-search.enabled`；单测 `SessionSearchToolTest`/`ChatHistorySearchExprTest`/工厂注册断言；Live `verify_session_search_live.py` P1–P4 全绿）：task（fast）MAIN 注册 `sunshine_session_search`；scope=session 仅本会话正文；chat/workflow/SUB/PLANNER 不注册；无会话上下文降级报错不抛异常；chat 自动召回不受影响 |
| 并行 | task-scene 读写闸门 P1/P2、KV Memory 表迁移 | — | — |

**建议顺序**：M0（成本最低、修复最大缺口）→ M1（KV 统一）→ M2（pro 出口）→ M3。

---

## 10. 验收

| 项 | 预期 | 状态 |
|----|------|:----:|
| fast 长任务跨轮 | 会话内新消息看到上轮未完成任务板（非重建） | **✅ M0**（Live T2） |
| 已完成项 | 已 done 项不重复提醒 | **✅ M0**（Live T3：全 terminal 不注入） |
| KV todo 类 | 用户「记得帮我看 X」→ 新会话召回；完成后 void；无裸 todo | **✅ M1**（Live T1/T2/T3） |
| pro 续接 | ANSWER/终态后新会话召回未完成项，不丢 goal | **✅ M2**（Live P1/P3） |
| session_search | task 会话按需恢复本会话早前正文；chat 不注册（隔离） | **✅ M3**（Live P1–P4：检索→恢复标记端到端） |
| 不双写 | 执行中 `react_task_board`/H1 无记忆层写入；仅终态/显式停下沉淀 | **✅ M0**（Live 红线：快照行数不随普通轮次增长） |
| 去重 | 会话级与 KV 同 goal 只注入最近一层 | ⬜（M1 后） |
| 压缩点兼容 | 沉淀副本 Tier 1 幂等字节稳定；恢复块 Tier 2 尾部，prefix 不漂移 | ⬜（五层 §5.5） |

---

## 11. 文档关系

| 文件 | 关系 |
|------|------|
| [unified-context-compression](./2026-07-31-unified-context-compression-design.md) | 五层 SSOT（v25）；L-state 任务清单落地；T0 作废联动 |
| [task-scene-context](./2026-08-01-task-scene-context-design.md) | KV 闸门与场景隔离（v14）；W0/L2 统一为 KV Memory |
| [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md) | H1 SSOT；补「会话完成后未完成项出口」 |
| [business-context-authority](./2026-08-13-business-context-authority-design.md) | `business_task` 边界隔离，不合并 |
| [unified-routing v6](./2026-07-29-unified-routing-design.md) | `kind`/`executionMode` 正交 |

---

## 12. 决议记录

| # | 决议 | 日期 |
|---|------|------|
| D1 | 落地范围：先写 spec 设计稿，不落代码 | 2026-08-14 |
| D2 | chat 未完成任务清单进 L2 `task` 类（v1）→ **v2 统一为 KV Memory `todo` 类** | 2026-08-14 |
| D3 | 沉淀时机 hybrid：会话终态导出 + 用户显式停下/换题补一次；执行中不双写 | 2026-08-14 |
| D4 | **砍 T0**（task-scene §6.1 全套），由 fast 跨轮恢复 + 任务 item 状态承接 | 2026-08-14 |
| D5 | **L2 + W0 统一一张 KV Memory**（scope=user\|workspace 列），同一模型/服务/渲染 | 2026-08-14 |
| D6 | 两级作用域：会话级执行态（真相源）+ KV Memory 沉淀（跨会话依据） | 2026-08-14 |
| D7 | 召回层次：先同会话（Tier 2 尾部注入）→ 再 KV（Tier 1 注入）→ 按需检索 | 2026-08-14 |
| D8 | session_search 一期收缩：body 层 + scope=session；workspace 延后 | 2026-08-14 |
