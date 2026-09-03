# orchestrator 完全无状态 + Activity 调度（三高）设计

> **日期**：2026-08-03 · **修订**：2026-08-12（v2：从「generation 粘连可续跑」升级为「Activity 任意实例调度」）  
> **状态**：📋 设计评审中 · **类型**：架构重构 · **编排层唯一 SSOT（无状态 / 扩缩）**  
> **对齐**：[planner-executor-rebuild](./archive/2026-08-05-planner-executor-rebuild-design.md) · [unified-routing v6](./archive/2026-07-29-unified-routing-design.md) · [request-decision D12](archive/2026-08-12-react-request-decision-planner-d12.md)

---

## 0. 目标与非目标

### 0.1 目标

1. **完全无状态**：执行态、句柄、锁、阻塞等待全部外置于 Redis/MySQL；进程内仅允许「当前 Activity 在飞的 LLM/工具连接」（可抛弃瞬态）。
2. **三高任意扩展**：同构 Worker 池水平扩缩；**每个可调度单元（Activity）可被任意实例领取执行**；失败/崩溃后 lease 过期回队，换机重跑，无需 sticky session。
3. **职责清晰的物理三层**：`orchestrator-router`（意图+路由+投递）· `orchestrator-worker`（Activity Runner 池）· `context-service`（异步上下文治理）。

### 0.2 成功判据（验收语言）

| # | 判据 |
|---|------|
| S1 | 任意 Worker 被 SIGKILL：进行中 Activity lease 过期后，**下一节点/下一任务在其他实例继续**（WF / Harness 默认自动续；策略可配） |
| S2 | 同一 Run 内相邻 `WF_NODE` / `AGENT_WORKER` 的 `workerId` 允许不同 |
| S3 | cancel / HITL confirm / Decision resolve / sandbox cancel 打到**非执行实例**均成功 |
| S4 | router / context-service 杀实例不影响进行中的 Activity |
| S5 | SSE 只读 Redis Stream；与执行实例无亲和 |

### 0.3 非目标

| 不做 | 原因 |
|------|------|
| 按角色拆「Planner 舰队 / Executor 舰队」两套部署 | 伪亲和，扩缩僵硬；应用同构池 + `activityType` |
| 把单次 LLM token 流拆到多机 | TCP 连接物理不可迁 |
| Activity 之间传未序列化 Java 对象 / 跨机 ThreadLocal bridge | 破坏无状态 |
| 将子 Agent 内部每个 think/tool 拆成 Activity | 过碎；整次 spawn = 一个 `AGENT_SUB` |
| 第一波就上 Temporal | 先 Redis 自研调度；`ActivityScheduler` 接口可替换 |
| 恢复 PlanApproval | 已由 [rebuild D5](./archive/2026-08-05-planner-executor-rebuild-design.md) 废弃 |

### 0.4 与 v1（2026-08-03）的差异

| | v1 | v2（本文） |
|--|----|-----------|
| 调度粒度 | **整次 generation** 粘在一台；崩溃后用户续跑换机 | **Activity** 正常路径即可跨机 |
| Worker 职责 | 收整段 chat 请求并跑完 | 只 claim/跑 Activity |
| Router 职责 | HTTP 转发整段流到某 Worker | 意图路由 + **创建 Run + enqueue**；SSE 读 Stream |
| 逻辑 Planner/Worker | 仍同进程工具调用 | 逻辑角色不变；物理上各是（或挂在）Activity |

---

## 1. 硬约束与原则

### 1.1 物理不可迁（唯一硬约束）

活跃 LLM 流式 HTTP 连接绑定发起进程。策略：**不迁连接，缩短连接生命周期到单个 Activity**；Activity 结束释放连接；状态已落 Redis 后，后继 Activity 任意机领取。

`agent.streamEvents()` 冷流 + `doFinally` checkpoint（既有 AgentScope StateStore，TTL 7d）仍然有效——粒度从「整段 ReAct」收束为「本 Activity 内最后一次 think」。

### 1.2 设计原则

1. **状态外置**：Run / Activity / Notebook / WF checkpoint / 取消 / lease / SSE Stream 均在 Redis（冷审计 MySQL/ES 照旧）。
2. **同构可调度**：任意 Worker 可跑任意已声明的 `activityType`（可用标签做能力过滤，不是异构舰队）。
3. **至少一次 + 幂等**：lease 过期回队；同一 `activityId` 重跑必须安全。
4. **逻辑分层 ≠ 物理分层**：Planner / AgentRole.WORKER / SUB 是逻辑角色；物理只有 Router + Worker 池 + Context。
5. **先外置内存，再细粒度调度，再拆进程**：迁移波次见 §10。

---

## 2. 总体架构

```
 Client ──SSE──► Gateway ──► orchestrator-router (:8200)
                              │ 意图识别 / 统一路由 (fast|pro|workflow)
                              │ 创建 Run、写 meta、enqueue 首个 Activity
                              │ cancel/confirm 只写 Redis
                              │ SSE：订阅 Redis Stream（不绑执行机）
                              ▼
                           Redis
                           · run:{runId} meta / 取消 / 锁
                           · activity 队列 + lease + 结果
                           · PlanNotebook / WF checkpoint / AgentState
                           · gen:{runId}:stream（SSE）
                              ▲
                              │ claim / heartbeat / complete / fail
                 ┌────────────┴────────────┐
                 │ orchestrator-worker ×N  │  同构 Activity Runner 池 (:8201)
                 │ 仅持有当前 Activity 的  │
                 │ LLM/工具连接（瞬态）    │
                 └────────────┬────────────┘
                              │ Run 终态 → RocketMQ
                              ▼
                      context-service (:8202)
                      L1/L2/L3 异步治理
```

**关键变化**：

- 当前：「执行态在内存、缓冲在 Redis」+ generation 粘连。
- 目标：「**调度与状态全在 Redis**；Worker 只是可随时杀死的 Activity 执行器」。

---

## 3. 核心模型：Run + Activity

### 3.1 概念

| 概念 | 含义 |
|------|------|
| **Run** | 一次用户生成（对应现有 `generationId` / message 执行），含模式、scene、路由结果 |
| **Activity** | 可调度单元；**任意 Worker 可领取**；输入输出可序列化 |
| **Lease** | Worker 对 Activity 的租约；心跳续期；过期回队 |

### 3.2 Activity 类型

| type | 对应逻辑 | 输入（引用） | 输出 | 落地优先级 |
|------|----------|--------------|------|:----------:|
| `WF_NODE` | 静态 Workflow 单节点 | planId, nodeId, ctx 版本 | nodeResult；调度器 enqueue 后继 | **P0** |
| `AGENT_WORKER` | Harness `AgentRole.WORKER` 一任务 | taskId, H1 引用, toolWhitelist | handoff → H1 | **P1** |
| `AGENT_SUB` | `spawn_subagent` **整次** run | prompt, agentId?, parent refs | 子结果摘要 | **P1** |
| `PLAN_ROUND` | Harness 一轮规划/自判/综合 | H1 引用, trigger | 更新 taskQueue；enqueue Workers 或终答 | **P2** |
| `REACT_TURN` | 普通 ReAct 一轮（可选） | StateStore checkpoint | 更新 checkpoint / 终态 | **P3** |

> **粒度红线**：`AGENT_SUB` = 一次 spawn 全过程，**禁止**把子 Agent 内 think/tool 再拆 Activity。`REACT_TURN` 未就绪前，fast 模式可暂保留「单 Activity 包整段 ReAct」（仍外置状态，崩溃换机续跑），但不阻塞 P0–P2。

### 3.3 调度接口（实现可 Redis，可日后 Temporal）

```java
public interface ActivityScheduler {
    String enqueue(ActivitySpec spec);
    Optional<ActivityLease> claim(String workerId, Duration lease, Set<String> types);
    void heartbeat(String activityId, String workerId);
    void complete(String activityId, ActivityResult result);
    void fail(String activityId, ActivityError error);
}
```

**后继怎么来**：

- `WF_NODE` complete → `WorkflowScheduler` 根据拓扑算 ready 节点，批量 `enqueue`（网关后可并行多 Activity）。
- `PLAN_ROUND` complete → 按 H1 taskQueue enqueue 多个 `AGENT_WORKER`；全部完成后由屏障（Redis 计数 / 父 Activity 等待）触发下一 `PLAN_ROUND`。
- 父 Activity 需要子结果时：**enqueue 子 Activity + 等待完成通知**（Redis key / pub/sub），禁止本进程同步嵌套长跑导致粘连（HITL 等待除外，见 §6）。

### 3.4 Redis 键（最小集）

```
sunshine:run:{runId}                         Hash   meta/status/mode/scene
sunshine:run:{runId}:cancel                  String 取消标记
sunshine:run:{runId}:lock:msg                String 消息互斥
sunshine:run:{runId}:stream                  Stream SSE
sunshine:run:{runId}:heartbeat               String Run 级存活（可选，聚合展示）

sunshine:activity:queue                      List/ZSET  待领取
sunshine:activity:{activityId}               Hash   spec/status/type/runId
sunshine:activity:{activityId}:lease         String workerId + TTL
sunshine:activity:{activityId}:cancel        String 单 Activity 取消（含 SUB）
sunshine:activity:{activityId}:result        String/Hash 完成结果

sunshine:plan:notebook:{sessionId}           String PlanNotebook JSON（对齐 rebuild）
sunshine:workflow:ckpt:{planId}              Hash   节点进度 + ctx
sunshine:agent:state:{...}                   既有 StateStore
sunshine:hitl:... / sunshine:toolrun:...     见 §6
```

---

## 4. 执行流（按模式）

### 4.1 公共入口（router）

```
用户请求 → orchestrator-router
  → 鉴权透传 x-user-id（只读）
  → 统一资源路由（L0–L3）→ RoutingResult
  → 用户显式 executionMode：fast | pro | workflow（routing v6）
  → 创建 Run meta + 消息锁
  → enqueue 首个 Activity：
       workflow → 首个 ready WF_NODE（可多个）
       pro      → PLAN_ROUND
       fast     → REACT_TURN（或过渡期 SINGLE_REACT 包整段）
  → 返回 SSE：订阅 sunshine:run:{runId}:stream
```

Router **不**持有 LLM 连接，**不**转发「整段执行」到固定 Worker。

### 4.2 workflow：节点级跨实例（P0）

```
claim WF_NODE → 读 ckpt → 执行单节点 → 写结果/推进 schedule
  → enqueue 下一波 ready 节点 → complete
```

暂停 / 节点 Recovery / HITL：节点边界落 Redis；resume = 再 enqueue（对齐现有 pause Hash，键迁到 run/plan 维度）。

**验收**：node-1 在 Worker-A，node-2 在 Worker-B；杀 A 不影响已入队的 node-2，进行中 node 回队后换机。

### 4.3 pro（Planner-Executor）：跨实例（P1+P2）

对齐 [rebuild](./archive/2026-08-05-planner-executor-rebuild-design.md) 单一循环，物理映射为：

```
PLAN_ROUND (任意机)
  → enqueue AGENT_WORKER×N（dependsOn 波次；任意机，可并行）
  → （Worker 内 spawn）enqueue AGENT_SUB（任意机）
  → 屏障：波次全部 complete
  → PLAN_ROUND 自判 / 重规划 / 综合回答
  → 写 Stream → Run 终态
```

H1 PlanNotebook **仅 Redis**（rebuild S2）；handoff 双写规则不变，但写入方是完成 `AGENT_WORKER` 的任意实例。

### 4.4 fast（ReAct）

- **目标态**：可选 `REACT_TURN` 细切。
- **过渡态**：一个 Activity 跑完整段 ReAct + 既有 StateStore checkpoint；崩溃 = lease 过期换机续跑（等同 v1 generation 续跑，但已纳入调度器）。

### 4.5 子 Agent（`AGENT_SUB`）

- **是**：整次 spawn = 一个 Activity。
- **否**：不拆内部 think/tool。
- 父（`AGENT_WORKER` 或过渡期 ReAct）`enqueue` + await；取消写 `activity:{id}:cancel`（吸收现 `SpawnRunRegistry` 语义）。
- 深度与预算：沿用 `max-sub-agents` / 沙箱同族预算。

---

## 5. 物理三层

### 5.1 orchestrator-router（:8200）

| 职责 | 模块 |
|------|------|
| HTTP：会话 CRUD、`/chat/stream`（建 Run+SSE）、`/generations/*` 查询 | `controller/` `conversation/` |
| **意图识别与统一路由**（L0–L3） | `routing/` |
| 查询改写、审计投递、只读 Catalog 缓存 | `rewrite/` `audit/` `catalog/` |
| **Activity 投递**（enqueue）、cancel/confirm 写 Redis | 调度客户端 |

**不包含**：LLM 长连接、Activity 执行循环、PlanNotebook 业务写入（只读查询可）。

### 5.2 orchestrator-worker（:8201）

| 职责 | 模块 |
|------|------|
| Activity claim 循环 + lease 心跳 | 新增 `activity/` |
| `WF_NODE` / `PLAN_ROUND` / `AGENT_WORKER` / `AGENT_SUB` /（后）`REACT_TURN` Runner | `execution/` `agent/` `plan/` |
| 事件写入 Run Stream、Timeline per-activity 上下文 | `generation/`（演化为 Stream 聚合）`processing/` |
| HITL / Decision 阻塞（等 pub/sub） | `hitl/` `decision/` |
| 沙箱工具句柄（Redis） | `sandbox/` |
| TaskBoard 投影写 Redis | `taskboard/` |

**扩缩容指标**：进行中 Activity 数 / lease 数 / 队列积压。缩容：停 claim → 等本机 Activity 结束或取消。

### 5.3 context-service（:8202）

L1 压缩 / L2 提取 / L3 摄入 / Catalog·Prompt 刷新；消费 `sunshine-context-turn-completed`（Run 终态后）。与流式路径解耦。

### 5.4 层间契约

| 调用方 | 被调方 | 方式 |
|--------|--------|------|
| router | Redis | 建 Run、enqueue、cancel、SSE 读 Stream |
| worker | Redis | claim、lease、结果、checkpoint、Stream 写 |
| worker | llm-gateway / rag / sandbox / resource-manager | HTTP |
| worker | context-service | RocketMQ 异步（Run 完成） |
| BFF | router | `lb://sunshine-orchestrator-router` |

**删除 v1 的「router HTTP 透传整段 SSE 到固定 worker」**——改为「router 只读 Stream」。

---

## 6. 跨实例控制面（由 v1 保留并升到 Activity）

### 6.1 Run / Activity 取消

```
sunshine:run:{runId}:cancel
sunshine:activity:{activityId}:cancel
```

- 任意实例写标记；执行机哨兵（每 N chunk / 200ms）检测后 dispose 本机连接并 `fail`/`complete(cancelled)`。
- Run 取消：级联标记所有未完成子 Activity。
- 沙箱取消：句柄在 Redis，任意机直接调 sandbox-service（无需哨兵）。

### 6.2 HITL / Decision / Recovery

`RedisBlockingNotifier`：执行机 await channel；confirm/resolve 任意机 `publish` + 结果 key 双写防丢。`request_decision`（含未来 Planner D12）复用同一模式。~~PlanApproval~~ 不恢复。

### 6.3 锁与暂停

- 消息锁：`run:{runId}:lock:msg` Redis SETNX。
- Workflow 暂停：Redis Hash（plan/run 维度）；resume enqueue。

### 6.4 StepEventBridge

禁止跨请求单例驻留。**per-Activity**（或 per-Run 但存 Redis）上下文；跨 Activity 只共享 Redis 中的 timeline/seq 约定，不共享堆内 Map。

### 6.5 心跳

| 级别 | 用途 |
|------|------|
| Activity lease TTL | **调度正确性**（过期回队） |
| Run heartbeat（可选） | 前端「仍在跑」与孤儿检测 |
| SSE orphan | Stream 侧超时 → 可标 INTERRUPTED；自动续跑由调度器回队完成时可不依赖用户点击（WF/Harness 默认） |

---

## 7. 幂等、失败与降级

| 场景 | 行为 |
|------|------|
| Worker 崩溃 | lease 过期 → Activity 回队 → 他机重跑（at-least-once） |
| Activity 业务失败 | 按类型重试上限 → fail → 父级屏障感知 → WF 补偿 / Harness replan / Run `completed_with_errors` |
| Planner 全失败 | 对齐 rebuild：`degraded_react`（enqueue fast 路径或标记降级） |
| Redis 队列不可用 | 拒绝新 Run；进行中尽量落盘审计；不静默改模式 |
| 重复 complete | 幂等：已终态忽略 |

**幂等要点**：`activityId` 稳定；WF 节点写结果用节点版本号；Worker handoff 写 H1 用 taskId 占位，重复执行覆盖或跳过 `done`。

---

## 8. 逻辑角色 ↔ Activity（防混淆）

| 逻辑（4.14 / ReAct） | 物理 |
|---------------------|------|
| Planner | 通常跑在 `PLAN_ROUND` Activity 内 |
| AgentRole.WORKER | `AGENT_WORKER` Activity |
| AgentRole.SUB | `AGENT_SUB` Activity |
| 静态 WF 节点 | `WF_NODE` Activity |
| orchestrator-worker **服务** | 同构进程池，可跑上述任意类型 |

跨任务共享记忆仍是 **H1 PlanNotebook**（rebuild S4：无第三份 KV）；与「Activity 跨机」正交。

---

## 9. 周边服务

| 服务 | 要求 |
|------|------|
| **sandbox-service** | Session/Invocation 句柄 → Redis；推荐 Docker remote + `sessionId→host`（多实例） |
| **llm-gateway / rag / workflow-manager / biz-simulator** | 已基本无状态，多实例即可 |
| **gateway / bff / auth-center** | BFF 下游改 `lb://sunshine-orchestrator-router`；无需 sticky |
| **start.py** | 多实例端口偏移 + 按 `nacos_name+port` 精确 stop |

---

## 10. 迁移波次

### 波次 A — 内存外置（原 v1 §7，仍为前置）

1. `RedisBlockingNotifier`；HITL / Decision / Recovery  
2. WorkflowPause / messageLocks → Redis  
3. 工具取消 / Spawn 取消句柄 → Redis + 哨兵  
4. Bridge per-call；Run 级 heartbeat / 孤儿检测  

**出口**：控制面跨实例；崩溃可续跑（仍可 generation 粘连）。

### 波次 B1 — Activity 骨架 + `WF_NODE`

5. `ActivityScheduler`（Redis）+ Worker claim 循环  
6. `WorkflowExecutor` 改为「单节点 Activity」；router 改 enqueue + Stream SSE  
7. Live：`verify_workflow_studio_live` / 节点跨机专项  

**出口**：S1/S2 对静态 WF 成立。

### 波次 B2 — `AGENT_WORKER` + `AGENT_SUB`

8. Harness 执行路径改为 enqueue Worker/Sub；取消对齐 activity cancel  
9. Live：spawn / harness 跨机  

### 波次 B3 — `PLAN_ROUND`

10. Planner 循环 Activity 化；对齐 rebuild 预算与重规划  
11. Live：`verify_planner_executor_live`（跨实例）  

### 波次 C — 物理拆分与周边

12. 拆 `orchestrator-router` / `orchestrator-worker` / `context-service`  
13. sandbox-service Redis 化；start.py 多实例；更新 CLAUDE.md 端口表  

### 波次 D（可选）

14. `REACT_TURN` 细切；或 `ActivityScheduler` 换 Temporal，业务 Runner 不动  

**顺序约束**：A → B1 → B2/B3 → C；禁止未外置内存就拆进程；禁止未 Activity 化就按角色拆舰队。

---

## 11. 配置（Nacos 草案）

```yaml
agent:
  activity:
    enabled: false
    claim-batch-size: 1
    lease-ttl-ms: 30000
    heartbeat-interval-ms: 5000
    max-retries-per-activity: 2
    queue-key: sunshine:activity:queue
    auto-requeue-on-worker-death: true   # WF/Harness 默认 true
  execution:
    harness:
      # 沿用 rebuild §8.1（v7 长负载档：4h 墙钟 / Worker 1h 等）；物理执行改为 Activity 后语义不变
      enabled: false
```

灰度：`agent.activity.enabled`；WF 可先开，Harness/ReAct 后开。  
Notebook 键：`sunshine:plan:notebook:{sessionId}`（与 rebuild §5.1 v7 一致）。

---

## 12. 风险与对策

| 风险 | 对策 |
|------|------|
| 调度实现变成二次 Temporal | 接口窄；B 波只做清单语义；复杂度爆则波次 D 换引擎 |
| at-least-once 双写业务副作用 | 节点/task 级幂等；工具侧尽量自然幂等或幂等键 |
| SSE 与执行脱节导致「假死」 | Run 级聚合状态 + 队列积压指标；lease 回队要写 Stream 提示 |
| Bridge/Timeline seq 跨机乱序 | seq 分配改 Redis INCR；禁止本机单调假设 |
| 过早物理拆分 | 波次 C 必须在 B1 验收后 |

---

## 13. 改造后服务全景

| 类别 | 服务 | 端口 | 实例 | 状态特征 |
|------|------|:----:|:----:|----------|
| 接入 | gateway / bff / auth-center | 8000/8001/8100 | 多 | 无状态 |
| 编排 | **orchestrator-router** | 8200 | 多 | 无状态（意图+路由+投递） |
| 编排 | **orchestrator-worker** | 8201 | 多 | 仅当前 Activity 连接 |
| 编排 | **context-service** | 8202 | 多 | 无状态异步 |
| 能力 | tool-service / resource-manager / sandbox / workflow-manager / llm-gateway / rag | 8210/8240/8226/8230/8300/8400 | 多 | 见 §9 |
| 模拟 | biz-simulator | 8700 | 多 | 无状态 |

---

## 14. 关联文档

| 文档 | 关系 |
|------|------|
| [planner-executor-rebuild](./archive/2026-08-05-planner-executor-rebuild-design.md) | 逻辑 Planner/Worker/H1；本文提供物理 Activity 映射 |
| [unified-routing v6](./archive/2026-07-29-unified-routing-design.md) | router 侧 fast/pro/workflow；本文负责投递哪种首 Activity |
| [request-decision D12](archive/2026-08-12-react-request-decision-planner-d12.md) | Planner HITL；阻塞走 §6.2 |
| 本文 v1 段落 | 控制面细节（cancel/HITL/sandbox）并入 §6；generation 粘连模型废弃 |

---

## 15. 一句话

**完全无状态 = 状态全在外部；三高 = 同构 Worker 对 Activity 任意领取。**  
Router 做意图与路由；Worker 只跑可调度单元；Workflow 节点、Harness Worker、子 Agent 均为 Activity——不是 Plan/Execute 两套服务。
