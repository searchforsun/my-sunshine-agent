# orchestrator 纯无状态化方案设计

> 日期：2026-08-03 · 状态：**待评审** · 类型：架构重构

## 1. 目标

让 orchestrator 成为**纯无状态服务**：任意实例可处理任意请求，任意实例可随时重启/缩容，会话可在任意节点继续执行，不依赖任何进程内内存态。唯一保留的进程内资源是"当前在飞的 LLM HTTP 连接"，但它被视为**可抛弃的瞬态资源**——连接断了从 Redis checkpoint 在任意节点接管续跑。

## 2. 核心约束分析

### 2.1 物理不可迁（唯一硬约束）

活跃的 LLM 流式 HTTP 连接绑定在发起它的进程上（Reactor `Disposable` 持有 OkHttp 连接）。没有任何技术手段能把活跃 TCP 连接搬到另一进程。

**应对策略**：不迁移连接，而是让连接**可断可续**。`agent.streamEvents()` 是冷流（`Flux.defer`，每次 subscribe 重新发起 HTTP），且 `doFinally` 在 CANCEL/ON_ERROR 时保存 checkpoint 到 Redis（`ReActAgentRuntime.java:180-199`）。续跑时从 checkpoint 重建 Agent（`RedisAgentStateStore`，TTL 7d），在任意节点重新 subscribe。代价是"重做最后一个 think 迭代"——可接受的断点粒度。

### 2.2 当前实现的内存态（全部可改造）

| 内存态 | 类 | 迁移方案 |
|--------|----|---------| 
| GenerationJob 注册表 | `GenerationRegistry.running` | 去掉，改为 Redis meta 驱动 |
| 消息并发锁 | `GenerationRegistry.messageLocks` | Redis 分布式锁 |
| Hook↔Timeline 绑定（15 个 Map） | `StepEventBridgeRegistry` | per-call 重建，不跨请求驻留 |
| HITL/Plan/Recovery 阻塞 Future | `HitlTokenRegistry.waiters` 等 3 处 | Redis pub/sub 唤醒 |
| spawn_subagent 取消句柄 | `SpawnRunRegistry.byRunId` | Redis 句柄表 + Agent 重建 interrupt |
| 沙箱工具取消句柄 | `CancellableToolRunRegistry` 6 个 Map | Redis 句柄表 |
| Workflow 暂停状态 | `WorkflowPauseService.byMessage` | Redis Hash |
| HarnessAgent 缓存 | `HarnessAgentHolder` Caffeine | 保留（纯缓存，丢失可重建） |

## 3. 总体架构

```
                          ┌─────────────────────────┐
   客户端 ──SSE 读──>     │  任意 orchestrator 实例  │  （无状态，全等价）
                          │  - 接收请求              │
                          │  - 从 Redis 读会话上下文 │
                          │  - 发起 LLM 流式调用     │
                          │  - chunk 写 Redis Stream │
                          │  - 周期检查取消标记      │
                          └───────────┬─────────────┘
                                      │
                          ┌───────────▼─────────────┐
                          │        Redis            │
                          │  - 会话/消息/Plan (MySQL)│
                          │  - AgentState checkpoint│
                          │  - Generation Stream    │
                          │  - 取消标记 / 句柄表    │
                          │  - pub/sub 通知通道     │
                          └─────────────────────────┘
```

**关键变化**：当前是"执行态在内存、缓冲在 Redis"，改为"**执行态全部在 Redis，内存只持有一过性的 Reactor 订阅句柄**"。

## 4. 详细设计

### 4.1 取消机制：内存 dispose -> Redis 取消标记 + 执行哨兵

这是整个方案的关键。当前 `cancel` 依赖 `registry.get(generationId).ifPresent(GenerationJob::cancel)` 在本实例 dispose subscription。改为**生产端哨兵轮询取消标记**。

**Redis 数据结构**：
```
key:  sunshine:gen:{id}:cancel
val:  { "reason": "user_cancel", "ts": 1691065234 }
TTL:  300s（终态后自动清理）
```

**GenerationJob 改造**：在 `chunkEmitter.onChunk` 调用链中插入取消检查点：

```java
// GenerationJob.start 内的 subscribe 回调
llmSubscription = llmFlux
    .subscribeOn(Schedulers.boundedElastic())
    .subscribe(
        chunk -> {
            if (cancelSignalChecker.shouldCancel(generationId)) {
                // 检测到取消标记，主动 dispose，触发 doFinally 保存 checkpoint
                disposeLlmSubscription();
                finishOnce(() -> handleCancelFromSignal());
                return;
            }
            chunkEmitter.onChunk(chunk, mysqlBuffer, guardedFlush, lastFlush);
        },
        ...
    );
```

`CancelSignalChecker` 周期性（每 N 个 chunk 或每 200ms）读 Redis cancel key。检测到则主动中断。

**cancel API 改造**（任意节点可调）：

```java
@PostMapping("/generations/{id}/cancel")
public Mono<Map<String, String>> cancel(...) {
    return ReactiveBlocking.call(() -> {
        streamService.assertOwned(id, userId, tenantId);
        // 写取消标记，而非 dispose 内存 subscription
        cancelMarkerService.markCancel(id, "user_cancel");
        return Map.of("status", "CANCEL_PENDING");
    });
}
```

**兜底**：若执行实例还活着，哨兵在 200ms 内检测到标记并自行 dispose；若执行实例已死，标记不会被消费，但 Redis Stream TTL 到期后 meta 自动消失，前端 reconnect 时读到 `INTERRUPTED` 终态。为加快"实例崩溃"场景的终态写入，增加心跳机制（见 4.6）。

### 4.2 spawn_subagent 取消：内存 Agent -> Redis 标记 + 重建 interrupt

当前 `SpawnRunRegistry.cancel` 调 `agent.interrupt()` 内存 Agent 对象。改为：

1. `register` 时把句柄元数据（runId/messageId/prompt/mainBridgeId）写入 Redis Hash `sunshine:spawn:{runId}`。
2. `cancel` 时写 Redis 标记 `sunshine:spawn:{runId}:cancel`，而非内存 interrupt。
3. `SpawnSubagentTool` 执行循环在每个 think 迭代间检查标记（复用 4.1 的哨兵机制），检测到则主动 interrupt 当前 agent（内存内，因为 agent 在本实例运行中）并下发 paused SSE。

**关键洞察**：spawn cancel 的"目标 agent"就在执行实例本地运行，所以 interrupt 仍在本地生效。区别只是 cancel 请求可以打到任意实例——任意实例写 Redis 标记，执行实例的哨兵读到后本地 interrupt。这和 4.1 是同一套机制。

### 4.3 沙箱工具取消：完全可跨实例

当前 `CancellableToolRunRegistry.cancel` 调 `sandboxClient.cancelInvocation(sessionId, invocationId)`。这是 HTTP 调用到 sandbox-service，**任意节点都能调**。改造：

- `register` 时把 Handle（toolUseId/messageId/sessionId/invocationId/toolName）序列化进 Redis Hash `sunshine:toolrun:{toolUseId}`。
- `cancel` 时任意节点从 Redis 读 Handle，直接调 `sandboxClient.cancelInvocation`。
- `followupBudget` 改 Redis 原子计数（`DECR`）。
- `pendingCancelStepIds` 改 Redis Hash，register 时 `HEXISTS` 检查。

**无需哨兵**：sandbox cancel 直接生效（HTTP kill 容器进程），不需要执行实例配合。

### 4.4 HITL/Recovery/Decision 阻塞：Future -> Redis pub/sub

两处结构相同（`HitlTokenRegistry`/`WorkflowNodeRecoveryService`），统一用 `RedisBlockingNotifier` 抽象；`request_decision`（4.7.9）复用 `HitlTokenRegistry` 的阻塞唤醒模式，无需新抽象。~~`PlanApprovalService`~~ **已删除**（[planner-executor-rebuild D5](./2026-08-05-planner-executor-rebuild-design.md) 废弃 PlanApproval，HITL 阻塞复用 `DecisionRegistry`/`HitlTokenRegistry`）：

```java
public class RedisBlockingNotifier {
    // 阻塞当前线程，等待 channel 的通知；超时由 Redis key TTL 兜底
    public CompletableFuture<Boolean> await(String channel, long timeoutSec) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        // 订阅 channel，收到消息后 future.complete
        redisSubscriber.subscribe(channel, msg -> future.complete(Boolean.valueOf(msg)));
        // 超时兜底
        scheduleTimeout(future, timeoutSec);
        return future;
    }

    // 任意节点调用，发布通知
    public void notify(String channel, boolean approved) {
        redis.convertAndSend(channel, String.valueOf(approved));
    }
}
```

**HitlTokenRegistry 改造**：

```java
public HitlRegistration register(String messageId, String toolId, String userId) {
    String token = UUID.randomUUID().toString();
    String channel = "sunshine:hitl:notify:" + token;
    CompletableFuture<Boolean> future = notifier.await(channel, properties.getTimeoutSec());
    storeToken(token, messageId, toolId, userId, expiresAt, channel);  // channel 存入 Redis
    return new HitlRegistration(token, future, expiresAt);
}

public boolean confirm(String token, boolean approved, String currentUserId) {
    HitlTokenMeta meta = loadTokenFromRedis(token);  // 任意节点可读
    if (meta == null) return false;
    if (meta.userId() != null && !meta.userId().equals(currentUserId)) return false;
    notifier.notify(meta.channel(), approved);  // 广播到执行实例
    redis.delete(redisKey(token));
    return true;
}
```

阻塞线程在执行实例上等 pub/sub，但 `confirm` 请求可以打到任意实例。`WorkflowNodeRecoveryService` 同理。

### 4.5 StepEventBridgeRegistry：从"跨请求驻留"到"per-call 重建"

当前 15 个 Map 试图跨请求维持绑定（如 main bridge、HITL 预审批、token wrapper）。分析发现这些绑定**仅在单次生成的生命周期内有效**，生成结束即 `clear`。改造为**per-call 局部对象**：

```java
// 不再是 @Component 单例，改为 per-generation 创建
public class StepEventBridgeContext {
    private final Map<String, ProcessingTimelineSession> sessions = new HashMap<>();
    private final Map<String, Queue<StreamToken>> hookTokenQueues = new HashMap<>();
    // ... 其余 13 个 Map 同理

    public void clear(String messageId) { ... }  // 生命周期与 GenerationJob 绑定
}
```

`GenerationJob` 持有 `StepEventBridgeContext` 引用，Job 结束时整体丢弃。`StepEventBridge` 静态门面改为从 `ThreadLocal` 或 Reactor `Context` 取当前 `StepEventBridgeContext`。

**HITL 预审批等需跨步骤状态**：存 Redis（`sunshine:hitl:preapprove:{messageId}`），不存内存。

### 4.6 心跳与崩溃检测

当前缺陷：执行实例进程崩溃且未写终态时，消费端 SSE 挂住最多 1 小时。增加心跳：

```
key:  sunshine:gen:{id}:heartbeat
val:  { ts }  每个活跃 chunk 写入时刷新
TTL:  15s
```

- **生产端**：`chunkEmitter.onChunk` 顺带刷新 heartbeat。
- **消费端**：`subscribe` 轮询时检查 heartbeat，若 `now - ts > 30s` 且 status 仍为 RUNNING，判定生产端崩溃，写 `INTERRUPTED` 终态并下发错误 SSE。
- **哨兵 worker**（可选，用 `@Scheduled`）：周期扫描 `status=RUNNING` 但 heartbeat 过期的 generation，补写终态。任意实例都可跑，用 `SETNX` 防重复。

### 4.7 messageLocks：内存 Map -> Redis 分布式锁

```java
public boolean tryLockMessage(String messageId, String generationId) {
    String key = "sunshine:gen:lock:msg:" + messageId;
    Boolean ok = redis.opsForValue().setIfAbsent(key, generationId,
            Duration.ofSeconds(properties.messageLockTtlSec()));
    return Boolean.TRUE.equals(ok) || generationId.equals(redis.opsForValue().get(key));
}
```

实例崩溃后 TTL 自动释放，续跑请求可在任意节点抢锁。

### 4.8 WorkflowPauseService：内存 Map -> Redis Hash

```
key:  sunshine:workflow:pause:{messageId}
hash: pauseRequested=1, planId=xxx, currentNodeId=node-3, committedCtxJson={...}
TTL:  与 generation 生命周期一致
```

`consumePauseRequested` 用 Lua 脚本保证 `HGET + HSET 0` 原子。

### 4.9 SSE reconnect：纯 Redis

`GenerationController.buildReconnectFlux` 去掉 `registry.get(generationId)` 依赖：
- `onSubscriberAttached`/`onSubscriberGone` 改为更新 Redis heartbeat，不操作内存 Job。
- orphan timer 改为 Redis 侧判定：heartbeat 超时即标记 `INTERRUPTED`。

## 5. 会话路由

**无需 sticky session**。任意 orchestrator 实例都能：
- 发起新生成（从 Redis/MySQL 读会话上下文）
- 读 SSE / reconnect（纯读 Redis Stream）
- cancel / confirm（写 Redis 标记或 pub/sub）
- 续跑（从 Redis checkpoint 重建 Agent）

生成期间若执行实例崩溃：
1. 心跳超时，哨兵/消费端写 `INTERRUPTED` 终态
2. 前端收到终态，用户可点"继续"
3. 续跑请求打到任意实例，从 checkpoint 接管

## 6. 改造影响面

| 改造对象 | 变更类型 | 风险 |
|---------|---------|------|
| `GenerationJob` | 增加取消哨兵 + 心跳 | 中（核心路径） |
| `GenerationRegistry` | `messageLocks` 转 Redis，`running` 弱化为缓存 | 中 |
| `GenerationController` | cancel/reconnect 去 Job 依赖 | 低 |
| `CancelSignalChecker` | 新增 | 低 |
| `RedisBlockingNotifier` | 新增 | 低 |
| `HitlTokenRegistry` | Future -> pub/sub | 中 |
| `WorkflowNodeRecoveryService` | 同上 | 中 |
| `CancellableToolRunRegistry` | 6 个 Map -> Redis | 中 |
| `SpawnRunRegistry` | 句柄转 Redis + 哨兵 | 中 |
| `WorkflowPauseService` | Map -> Redis Hash | 低 |
| `StepEventBridgeRegistry` | 单例 -> per-call context | 高（贯穿 SSE 链路） |
| `ConversationSandboxStore` | 无变化（已在 Redis） | 无 |
| `DistributedGenerationLock` | 无变化 | 无 |
| 前端 | 无变化 | 无 |

## 7. 迁移步骤

### 第一批：解锁"确认/取消跨实例"（低风险高收益）

1. `RedisBlockingNotifier` 抽象
2. `HitlTokenRegistry` / `WorkflowNodeRecoveryService` 两处 Future -> pub/sub（`request_decision` 4.7.9 复用 HitlTokenRegistry 模式，届时一并覆盖）
3. `WorkflowPauseService` -> Redis Hash
4. `GenerationRegistry.messageLocks` -> Redis 锁

验收：HITL 确认、Decision 决议（4.7.9）、节点 Recovery 可打到非执行实例成功。

### 第二批：解锁"取消跨实例"

5. `CancellableToolRunRegistry` 6 个 Map -> Redis 句柄表
6. `CancelSignalChecker` + `GenerationJob` 取消哨兵
7. `GenerationController.cancel` 改写 Redis 标记
8. `SpawnRunRegistry` 句柄转 Redis + 哨兵

验收：cancel generation / cancel subagent / cancel tool 可打到非执行实例成功。

### 第三批：消除执行注册表依赖

9. `StepEventBridgeRegistry` 单例 -> per-call context
10. `GenerationController.reconnect` 去 Job 依赖
11. heartbeat + 崩溃检测 + 哨兵 worker

验收：杀死执行实例，前端 reconnect 后收到 `INTERRUPTED`，续跑可在新实例成功。

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 取消哨兵延迟 | cancel 后最多 200ms+ 才生效 | 哨兵频率可配；cancel API 返回 `CANCEL_PENDING`，前端轮询终态 |
| `StepEventBridgeRegistry` 改造范围大 | 贯穿 SSE 链路，回归风险高 | 放第三批；先做单测覆盖，灰度切换 |
| 实例崩溃续跑粒度 | 重做最后一个 think 迭代 | 可接受；checkpoint 每 think 存，非每 token |
| Redis pub/sub 消息丢失 | HITL 确认丢失，阻塞超时 | TTL 兜底超时；confirm 时双写 Redis key + pub/sub，重试 confirm 可查 key |
| 哨兵 worker 重复写终态 | 数据冲突 | `SETNX` 防重复；终态写入幂等 |

## 9. orchestrator 三层物理拆分

无状态化（§4-§8）是拆分的前提：先把内存态外迁到 Redis，让"活跃 LLM 连接"成为唯一残留的进程内资源，再按职责物理拆分为三个独立部署的服务。

### 9.1 拆分依据

orchestrator 现有 435 个文件、约 42,550 行，职责可按状态特征清晰归类：

| 层 | 职责包 | 行数 | 状态特征 |
|----|--------|:----:|----------|
| **router** | controller / routing / client / rewrite / grounding / audit / conversation / config | ~8.8K | 全部无状态 |
| **worker** | generation / agent / execution / processing / hitl / plan / sandbox / taskboard | ~27.9K | 13 处内存态（无状态化后仅剩活跃 LLM 连接） |
| **context-service** | context / catalog / prompt / skill | ~5.8K | 缓存可重建 + 1 处内存锁 |

### 9.2 目标架构

```
                          ┌──────────────────┐
   gateway ──lb://──>     │  orchestrator    │  无状态 · 可任意扩缩容
   (8000)                 │  -router (8200)  │  接收请求 / 路由 / 会话读写
                          └────────┬─────────┘
                                   │ 内部调用（同 K8s 集群）
                          ┌────────▼─────────┐
                          │  orchestrator    │  持活跃连接 · 可扩缩容
                          │  -worker (8201)  │  LLM 流式 / 工具执行 / HITL 阻塞
                          └────────┬─────────┘
                                   │ 异步事件
                          ┌────────▼─────────┐
                          │  orchestrator    │  纯异步 · 可任意扩缩容
                          │  -context (8202) │  L1 压缩 / L2 提取 / L3 摄入
                          └──────────────────┘
```

### 9.3 router 层（orchestrator-router，端口 8200）

**职责**：无状态接入与路由。

| 模块 | 来源包 | 说明 |
|------|--------|------|
| HTTP 端点 | `controller/` | `/chat/stream` 入口（转发到 worker）、`/generations/*` 查询、`/conversations/*` CRUD |
| 意图路由 | `routing/` | 统一资源路由 v3：Pre-Routing + Policy Chain（L0 显式绑定 -> L1 规则 -> L2 语义 -> L3 LLM 兜底），对齐 [unified-routing](./2026-07-29-unified-routing-design.md)；`RoutingResult{type, planMode, scene}` 落 Redis 供 worker 消费 |
| 会话管理 | `conversation/` | 纯 MySQL 读写 |
| 查询改写 | `rewrite/` | 纯计算 |
| RAG 客户端 | `rag/` | WebClient 调 rag-service |
| 审计 | `audit/` | 异步写 RocketMQ/ES |
| Catalog 缓存 | `catalog/`（只读） | 从 resource-manager 拉取，本地缓存可丢失重建 |

**关键设计**：`/chat/stream` 收到请求后，从 Redis/MySQL 组装会话上下文，再通过**内部 HTTP 调用**转发到 worker 执行。router 不持有任何执行态，即使重启也不影响进行中的生成（worker 持有活跃连接）。

```java
// router 的 ChatController
@PostMapping("/chat/stream")
public Flux<ServerSentEvent<String>> chatStream(...) {
    // 1. 读会话上下文（MySQL）
    // 2. 选 worker 实例（lb://orchestrator-worker）
    // 3. 转发请求到 worker，透传 SSE
    return workerClient.streamChat(request, userId, tenantId);
}
```

cancel/confirm 类请求：router 直接写 Redis 标记或发 pub/sub（见 §4），不需转发到 worker。

### 9.4 worker 层（orchestrator-worker，端口 8201）

**职责**：持有活跃 LLM 连接，执行编排。

| 模块 | 来源包 | 说明 |
|------|--------|------|
| 流式生成 | `generation/` | GenerationJob + Redis Stream 写入 |
| ReAct 编排 | `agent/` | ReActAgentRuntime + StepEventBridgeContext（per-call） |
| 执行引擎 | `execution/` | 三种 Executor：`ReactExecutor`（通用 ReAct / planMode=none）、`PlannerHarnessExecutor`（Planner-Worker / planMode=harness）、`WorkflowExecutor`（静态 Workflow）——对齐 [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md)，动态 Plan-Workflow 已删 |
| Timeline 聚合 | `processing/` | per-call，生命周期与 GenerationJob 绑定 |
| HITL | `hitl/` | 阻塞线程等 Redis pub/sub |
| Decision 阻塞 | `decision/`（4.7.9） | 复用 HitlTokenRegistry 模式；`plan/` 审批已随 PlanApproval 删除 |
| 沙箱工具 | `sandbox/`（orchestrator 侧） | CancellableToolRunRegistry（Redis 句柄表） |
| TaskBoard | `taskboard/` | Redis 存储 |
| Prompt 组装 | `prompt/` | 从 prompt-manager 拉取 Catalog |

**关键设计**：worker 收到 router 转发的请求后，创建 GenerationJob 并 subscribe LLM Flux。chunk 写 Redis Stream（router 和前端都从 Redis Stream 读）。无状态化改造后，worker 实例崩溃时：

1. 活跃 LLM 连接断开，checkpoint 已由 `doFinally` 保存到 Redis
2. heartbeat 超时，哨兵写 `INTERRUPTED` 终态
3. 前端收到终态，用户点继续
4. router 将续跑请求转发到**任意存活 worker**，从 checkpoint 接管

**扩缩容**：worker 按"活跃生成数"指标扩缩。缩容前等待活跃生成结束或通过 Redis 取消标记优雅中断。

### 9.5 context-service 层（orchestrator-context，端口 8202）

**职责**：纯异步上下文治理，无会话亲和。

| 模块 | 来源包 | 说明 |
|------|--------|------|
| L1 压缩 | `context/l1/` | Mid/Far 压缩（MySQL），`compressLocks` 改 Redis 分布式锁 |
| L2 提取 | `context/l2/` | 用户状态抽取（MySQL） |
| L3 召回/摄入 | `context/l3/` | 历史向量（Milvus） |
| 定时治理 | `context/job/` | `@Scheduled` GC（加分布式锁防多实例重复） |
| Catalog 全量缓存 | `catalog/`（读写） | ToolCatalog/AgentCatalog/SkillCatalog |
| Prompt Catalog | `prompt/`（全量） | PromptComposer + Catalog 刷新调度 |

**关键设计**：worker 完成一轮生成后，通过 Redis Stream 或 RocketMQ 发异步事件给 context-service。context-service 消费事件执行 L1/L2/L3 治理。这把耗时的压缩/检索从编排链路彻底剥离，不再与流式生成争抢线程。

**触发方式**：当前 `ContextLifecycle.onTurnCompleted` 是 worker 内 `@Async` 调用。拆分后改为发消息：

```java
// worker 完成生成后
contextEventPublisher.publishTurnCompleted(messageId, userId, tenantId);

// context-service 消费
@RocketMQMessageListener(topic = "sunshine-context-turn-completed")
public class TurnCompletedConsumer {
    public void onMessage(TurnCompletedEvent event) {
        contextLifecycle.onTurnCompleted(event.messageId(), ...);
    }
}
```

### 9.6 三层间的通信契约

| 调用方 | 被调方 | 通信方式 | 说明 |
|--------|--------|----------|------|
| router | worker | HTTP + SSE 透传 | `/chat/stream` 转发，WebClient `bodyToFlux` |
| router | Redis | 直连 | cancel 标记 / pub/sub 通知 |
| router | MySQL | 直连 | 会话/消息读写 |
| worker | Redis | 直连 | Stream / checkpoint / 句柄表 / 心跳 |
| worker | context-service | RocketMQ 异步 | turn-completed 事件 |
| worker | llm-gateway | HTTP | LLM 调用 |
| worker | rag-service | HTTP | RAG 检索 |
| worker | sandbox-service | HTTP | 沙箱操作 |
| worker | resource-manager | HTTP | Catalog 查询 |

## 10. 其他服务调整

### 10.1 sandbox-service 无状态化（硬亲和性改造）

`sandbox-service` 是唯一有硬亲和性的 AI 能力服务。`SandboxSessionStore`（`sandbox-service/.../session/SandboxSessionStore.java:13`）是纯内存 `ConcurrentHashMap`，容器句柄只在创建实例的内存中。多实例部署时，实例 B 收到对实例 A 创建的 session 的操作会报 `SESSION_NOT_FOUND`。

**改造方案**：Docker 操作与实例解耦，session 句柄外迁到 Redis。

```
Redis Key:  sunshine:sandbox:session:{sessionId}
Value:      { containerName, hostRoot, policy, createdAt }
TTL:        跟随 purge 时间
```

- `create`：创建容器后写 Redis（而非内存 Map）
- `exec`/`mountSkill`/`close`/`start`/`stop`：从 Redis 读 containerName，直接调 `docker` CLI（`docker exec`/`docker stop`），不依赖本地 session 对象
- `SandboxSessionStore` 降级为可重建的本地缓存（`docker ps` 恢复），实例重启后自动重建
- `SandboxInvocationRegistry`（容器内进程取消句柄）也外迁 Redis

**多实例容器亲和**：Docker daemon 是本地的，容器创建在哪个实例的宿主机上。两种选择：
- **方案 A（推荐）**：sandbox-service 与 Docker daemon 同宿主机，多实例对应多宿主机。Redis 记录 `sessionId -> host`，操作时用 Docker remote API 跨宿主机调用。
- **方案 B**：sandbox-service 单实例，不拆分。简单但牺牲沙箱层的水平扩展。

### 10.2 llm-gateway 调整

基本支持无状态多实例。唯一内存态 `AdapterCircuitBreaker`（`router/AdapterCircuitBreaker.java:23`，内存熔断器）可接受各实例独立计数。可选优化：熔断状态外迁 Redis（共享计数），但非必须。

**结论**：无需改造即可多实例。

### 10.3 rag-service 调整

完全无状态：Milvus/ES/MySQL/MinIO 均为外部存储，进程内无会话状态。

**结论**：无需改造即可多实例。

### 10.4 接入层调整

| 服务 | 调整 |
|------|------|
| **gateway** (8000) | 无需改动。已用 `lb://` + Nacos 发现，多实例自动负载均衡。无需 sticky session |
| **bff** (8001) | 无需改动。完全无状态（11 个 Controller 纯 WebClient 透传）。下游 `*.base-url` 从指向 `localhost:8200` 改为 `lb://sunshine-orchestrator-router` |
| **auth-center** (8100) | 无需改动。Sa-Token JWT + Redis，天然无状态 |

### 10.5 start.py 多实例支持

当前 `start.py` 端口硬编码、按 JAR 名 kill，不支持多实例。改造：

```python
# 端口分配：base_port + instance_index
def start_service(name, module, nacos_name, base_port, instances=1):
    for i in range(instances):
        port = base_port + i
        start_java_detached(module, nacos_name, port)
```

- **进程识别**：`stop` 改为按 `nacos_name + port` 精确 kill，不再按 JAR 名批量 kill
- **Nacos 注册**：多实例用不同端口，Nacos 自动生成不同 instanceId
- **配置**：每个实例可覆盖 `server.port`，其余配置共享同一 Nacos dataId

## 11. 改造全景与迁移步骤（修订）

在原三批无状态化基础上，增加三层拆分和服务调整。

### 第一批：无状态化 - 确认/取消跨实例（§7 原第一批）

1. `RedisBlockingNotifier` 抽象
2. HITL/Decision/Recovery 阻塞 Future -> pub/sub（`PlanApproval` 已随 planner-executor-rebuild 删除，Decision 4.7.9 复用 HitlTokenRegistry 模式）
3. `WorkflowPauseService` -> Redis Hash
4. `messageLocks` -> Redis 锁

### 第二批：无状态化 - 取消跨实例（§7 原第二批）

5. `CancellableToolRunRegistry` -> Redis 句柄表
6. `CancelSignalChecker` + 取消哨兵
7. `SpawnRunRegistry` -> Redis + 哨兵

### 第三批：无状态化 - 消除执行注册表（§7 原第三批）

8. `StepEventBridgeRegistry` 单例 -> per-call context
9. `GenerationController.reconnect` 去 Job 依赖
10. heartbeat + 崩溃检测 + 哨兵 worker

### 第四批：orchestrator 三层物理拆分

11. 抽取 `orchestrator-router` 模块（controller/routing/conversation/rewrite/audit/client）
12. 抽取 `orchestrator-context` 模块（context/catalog/prompt），对外服务名 `context-service`
13. `orchestrator` 重命名为 `orchestrator-worker`（保留 generation/agent/execution/processing/hitl/plan/sandbox/taskboard）
14. router <-> worker 通信契约（HTTP SSE 透传）
15. worker -> context-service 异步事件（RocketMQ `sunshine-context-turn-completed`）
16. BFF `*.base-url` 改指向 `lb://sunshine-orchestrator-router`

验收：router/context-service 各杀一个实例不影响进行中的生成；worker 杀一个实例，heartbeat 超时后前端收到 `INTERRUPTED`，续跑在新 worker 成功。

### 第五批：sandbox-service 无状态化

17. `SandboxSessionStore` 内存 -> Redis
18. `SandboxInvocationRegistry` 内存 -> Redis
19. Docker 操作解耦本地 session 对象
20. `ConversationSandboxStore` 补充 `sessionId -> host` 映射

验收：sandbox-service 实例 A 创建容器后，实例 B 可执行 exec/close 操作。

### 第六批：部署与运维

21. `start.py` 支持多实例（端口偏移 + 精确 kill）
22. Gateway/BFF 验证 lb 自动发现（无需改代码）
23. CLAUDE.md 服务端口表更新（orchestrator 拆为 3 服务）

## 12. 改造后服务全景

| 类别 | 服务 | 端口 | 实例数 | 状态 |
|------|------|:----:|:------:|------|
| 接入层 | gateway | 8000 | 多 | 无状态 |
| 接入层 | bff | 8001 | 多 | 无状态 |
| 接入层 | auth-center | 8100 | 多 | 无状态（Redis） |
| 编排-router | orchestrator-router | 8200 | 多 | 无状态 |
| 编排-worker | orchestrator-worker | 8201 | 多 | 仅活跃 LLM 连接（可断可续） |
| 编排-context | context-service | 8202 | 多 | 无状态 |
| 管理类 | resource-manager | 8210 | 多 | 无状态（MySQL） |
| AI 能力 | llm-gateway | 8300 | 多 | 基本无状态 |
| AI 能力 | rag-service | 8400 | 多 | 无状态 |
| AI 能力 | sandbox-service | 8226 | 多 | Redis 句柄（方案 A 需 Docker remote API） |
| AI 能力 | workflow-manager | 8230 | 多 | 无状态（MySQL） |
| 业务模拟 | biz-simulator | 8700 | 多 | 无状态（MySQL） |

**编排层从 1 个 42K 行单进程演变为 3 个各司其职的服务**，全部支持无状态多实例水平扩展。
