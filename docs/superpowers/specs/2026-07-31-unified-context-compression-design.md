# 上下文压缩统一设计（五层渐进管道）

> 日期：2026-07-31
> 状态：**Layer 2/3/4/5 ✅ 已实现** · **Layer 1 ⚠️ 待恢复**（AgentScope 2.0 迁移后移除）
> **v2/v3 优化（2026-08-01）**：§5.5 压缩点模式（L1 压缩点前移、L3 尾部动态段、Budget「丢」改「退役并入」）；§5.5.3 起 v3 分层修正——**按变化频率 Tier 0/1/2 分层**、幂等 upsert、T0 降频、意图尾部注入（业界调研见 §5.5.5）· 关联 [task-scene-context-design](./2026-08-01-task-scene-context-design.md)
> 整合：`2026-07-17-autocontext-memory-design.md` + `2026-07-22-context-optimization-design.md` + `2026-07-24-dynamic-context-compression-design.md`（三者均已归档）
> 行业参考：Claude Code 五层渐进压缩 · Cursor 单层摘要 · Oracle 双层模式 · Mem0 LLM 记忆管理

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

**核心原则**（源自 Claude Code）：
- **先轻后重**：廉价操作（工具结果清理）每轮自动跑，昂贵操作（LLM 摘要）最后触发
- **减少触发频率**：token > 80% 窗口才触发跨轮压缩（非轮次 > 16），绝大多数对话不压缩
- **原文存 MySQL**：压缩不可逆但原文可查

---

## 3. 五层管道总览

```
每轮 LLM 调用前 / 每轮 assistant 完成后 / 每次读时组装：

 ┌── Layer 1  AutoContextMemory           ⚠️ 待恢复
 │    触发: PreReasoning（每轮自动）
 │    开销: 零 LLM 调用
 │    压缩: intra-turn ReAct 工具结果
 │
 ├── Layer 2  L1 Near/Mid/Far             ✅ 已实现
 │    触发: token > 80% 窗口 OR 轮次 > 40（异步）
 │    开销: 1-2 次 LLM 调用（Mid 摘要 + Far 折叠）
 │    压缩: cross-turn 对话历史 → 三层窗口
 │
 ├── Layer 3  L2 结构化状态               ✅ 已实现
 │    触发: assistant completed（异步）
 │    开销: 1 次 LLM 调用
 │    压缩: 对话 → 11 类结构化键值对
 │
 ├── Layer 4  L3 向量检索                 ✅ 已实现
 │    触发: 每次读时 query → Milvus
 │    开销: 1 次 embedding + search
 │    压缩: 对话 → 语义 chunk → 按需召回
 │
 └── Layer 5  Budget 读时裁剪             ✅ 已实现
      触发: 组装后 token > 80% 窗口
      开销: 零 LLM 调用
      顺序: L3 → Far → Mid 从头丢 → Near 永不丢
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

AgentScope 2.0 的 `CompactionConfig` 是一个**完整的多步骤管道**，通过 `HarnessAgent.compaction()` 注入，以 `CompactionMiddleware` 形式在每轮 `onReasoning` 前运行。

**唯一的实质问题**：当前 `buildCompactionConfig()` 只设了 `triggerMessages=40`。对于默认 `maxIters=5`（约 18-22 条消息），永远不会触发。

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

**MemoryProperties.AutoContext** —— 完整字段（Phase 0/1/2 参数）：

```java
public static class AutoContext {
    private boolean enabled = true;

    // ── Phase 0：tail 裁剪（CompactionConfig 参数）────────────────
    private long protectTokens = 40_000;          // 保护最近 N tokens
    private long minTokensToPrune = 20_000;       // 最小超限才触发
    private String summaryPrompt;                 // 可选摘要 prompt

    // ── Phase 1：跨轮激进压缩阈值 ────────────────────────────────
    private double crossTurnRatio = 0.85;          // 触发比例（modelWindow × 0.85）
    private int nearKeepTurns = 4;                 // 保留 Near 轮次
    private int midKeepTurns = 4;                  // 保留 Mid 压缩轮次

    // ── Phase 2：tail 收缩参数 ───────────────────────────────────
    private long phase2ProtectTokens = 20_000;     // 更激进保护
    private long phase2MinTokensToPrune = 10_000;
    private int phase2TruncateChars = 500;         // 更激进截断

    // ── 保留字段（向后兼容）──────────────────────────────────────
    private long msgThreshold = 40;                // 消息数 fallback
    private int lastKeep = 12;
    private long maxToken = 128 * 1024;
    private double tokenRatio = 0.75;
    private long largePayloadThreshold = 5 * 1024;
    private int minCompressionTokenThreshold = 3000;
}
```

Nacos 对应配置：

```yaml
agent:
  memory:
    auto-context:
      enabled: true
      # Phase 0：tail 裁剪
      protect-tokens: 40000
      min-tokens-to-prune: 20000
      # Phase 1：跨轮激进压缩
      cross-turn-ratio: 0.85
      near-keep-turns: 4
      mid-keep-turns: 4
      # Phase 2：tail 收缩
      phase2-protect-tokens: 20000
      phase2-min-tokens-to-prune: 10000
      phase2-truncate-chars: 500
```

Layer 5 预算联动调整（`sunshine-orchestrator.yaml`）：

```yaml
agent:
  context:
    l1:
      max-tokens-ratio: 0.75    # 96k（给 Phase 0 留 13k 缓冲到 Phase 1 109k）
```

### 4.6 实施清单

| 文件 | 操作 | 说明 |
|------|------|------|
| **新增** `CrossTurnCompactMiddleware.java` | 新建 | Phase 1 跨轮激进压缩逻辑（L3 清 + Near↓ + Mid↓ + Far 合成），`compacted` 标记 |
| **修改** `ProcessingStepMiddlewareFactory.java` | 扩展 | 注入 `CrossTurnCompactMiddleware`，**放在 CompactionMiddleware 之前** |
| **修改** `HarnessAgentFactory.buildCompactionConfig()` | 3 行参数 | `triggerTokens=0` / `keepTokens=-1` / `PruneConfig` |
| **修改** `MemoryProperties.AutoContext` | 新增 7 字段 | Phase 1/2 参数 |
| **修改** `L1Compressor`（可选） | 调用封装 | Phase 1 复用于 Mid/Far 压缩的 LLM 调用 |

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
| `ProviderProperties.java` | llm-gateway | `ModelMeta`（`contextWindow`+`encoding`） |

---

### 5.5 压缩点模式（v2 优化 · 设计稿）

> 定位：把 L1 从「固定滑动窗」升级为「压缩点前移」，使**不触发压缩期间 messages 前缀完全稳定**，KV Cache 只 miss 尾部。chat/task 同构统一启用（差异仅 Tier 0/1 内容 + L3 开关，见 §5.5.7）。

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
- 触发压缩时：Near→Mid 摘要、Mid+旧Far→新Far 折叠、压缩点前移 → 该次唯一 prefix 重建（C3）

#### 5.5.3 组装结构（每轮 · v3 修正）

> **v3 修正（2026-08-01）**：原稿把「W0 / L2 / T0」标为 C1 稳定是**错误的**——三者由 LLM 异步抽取，每轮都可能 upsert，放在 prefix 中段会让其后全部失效。
> 修正原则：**按变化频率分层，而非按语义层级**（对齐 Anthropic / vLLM / MemGPT 约束，详见 §5.5.5）。

```
Tier 0 · 绝对静态核（字节恒定，永不失效）
  tools（确定性序列化：排序后渲染；工具规模大时改「名列表静态 + schema 尾部」，见下方 v6 注记）
  + System base · scene/mode overlay
  + P0 项目规范（用户手动编辑时才变，单次失效可接受）

Tier 1 · 低频记忆（content-hash 幂等 upsert，真变才失效一次）
  + L2 用户状态（11 类结构化键值，幂等）
  + W0 工作区记忆（索引/约束/事实，幂等）
  + T0 任务进度（降频：随压缩点推进刷新，非每轮）
  + L1 Far/Mid 摘要（压缩时才变）

Tier 2 · 动态段（每轮 append / 每轮变，物理隔离）
  Near 原文（压缩点之后逐轮增长）
  + L3 召回（U 形排序：高相关放首尾，Lost-in-Middle 缓解）
  + 意图/模式注入（追加为尾部 system 消息，Anthropic mode-switch 模式）
  + user query（tail 末尾）
```

- **Tier 0 是双层缓存的内层稳定核**：Tier 1 任何一次真实变化只使外层失效，Tier 0 仍命中（two-level caching）
- **意图识别结果不注入 prefix**：它是路由决策（控制流）；需告知模型当前模式时，用尾部 system 消息
- **Lost-in-the-Middle 收敛**：Far/Mid 本就是「允许模糊」的历史摘要，放中间注意力洼地无损失；必须精确记得的（约束/目标/事实）放 Tier 0/1 头部——中间模糊区与 KV 稳定区天然重合
- 溢出处理：tail 超 `modelWindow × 0.8` → 触发一次压缩（C3），不再从 Near 头部丢轮次

> **v6 注记（tools 分层注入，对齐 [phase5 §5.5](./phase5-operation-openness-design.md)）**：工具规模膨胀（>50）时 naive 全量 schema 进 Tier 0 会推高 token；若改为每轮按 query 检索 Top-K 注入，则 `tools` 块每轮变化 → **Tier 0 失效 → 全量 miss**。折中：**Tier 0 只放「全量工具名列表」**（确定性序列化、字节稳定）+ **Tier 2 尾部放 Top-K 工具完整 schema**（随 query 动态）。工具规模 ≤ 阈值（默认 20）时仍用全量 schema 进 Tier 0（`full` 模式），二选一由 Nacos `agent.tool.inject` 切换。

#### 5.5.4 五条优化建议

**① L1 压缩点前移（C1/C2）**：`L1Compressor.partition` 由固定 near/mid 轮数改为以压缩点为界；Near 起点 = 最后一个折叠 msgId 之后。非压缩期 Near 只 append；`trimByTokens` 不再从头部丢轮次（避免破坏 prefix），溢出走压缩而非裁剪。

**② L3 尾部动态段（C2）**：L3 渲染位置固定约束为「当前 user query 之前」，见 §7.5。禁止在 Far/Mid 之间注入（其后全部失配）。

**③ Budget「丢」改「退役并入」（C3 + 保质量）**：见 §8.2。Mid 头部不再直接丢，先触发 Far 折叠（并入 far_summary、压缩点前移），折叠后仍超预算才丢 Far。让 Budget 成为压缩点推进的触发源之一，保住「原文可查」原则。

**④ chat/task 统一启用（§3 前提修正）**：统一路由后（[routing v3](./2026-07-29-unified-routing-design.md)）chat 与 task 同走 ReAct，无 DIRECT 直答模式。压缩点作为 **L1 通用机制统一启用**，场景差异只留两处：静态层内容（P0 项目规范 / W0 / T0 仅 task）+ L3 开关（chat/task 均关闭或按场景配）。

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

#### 5.5.6 幂等 upsert 与定宽隔离（v3 设计稿）

> 落实 §5.5.5 约束 3/4 与 vLLM 实证结论：记忆层「低频」必须是工程可保证的，而非假设。

- **content-hash 幂等**：L2/W0 抽取服务每次产出结构化块后计算 `sha256(content)`，与 `conversation_context_l1`/`workspace_context_state` 现存块的 `content_hash` 比对；**未变化 → 跳过写库**，`assemble` 读到的字节不变 → 缓存不失效。
- **版本标签**：块体变更时更新 `content_hash` + `version`；`assemble` 用 `(kind, key, version)` 确定性拼接，避免「数据相同、序列化不同」造成的无效失效。
- **定宽隔离**：Tier 1/2 的附加项（如 W0 新增键值）追加到该块的 appendix 段，用固定宽度分隔符与 compiled 段隔离；appendix 超阈值（≥5 条或 ≥30% 总量）才触发一次整体重编译（新稳定前缀）——对齐 vLLM Memory Hub 修复方案。

---

#### 5.5.7 chat/task 差异收敛表

| 维度 | chat | task | 压缩点模式是否差异 |
|------|------|------|:---:|
| 执行路径 | 通用 ReAct（planMode=none/harness） | 同 | ❌ 统一 |
| L1 窗口 | 压缩点前移 | 压缩点前移 | ❌ 统一 |
| Tier 0 | base + overlay.chat + tools | base + overlay.task + **P0 项目规范** + tools | ✅ 差异（内容） |
| Tier 1 | L2 + Far/Mid | L2 + **W0 + T0** + Far/Mid | ✅ 差异（内容） |
| L3 | 关闭 | 移除 | ✅ 差异（开关） |
| run 内 Layer 1 | 三阶段一次（§4.4） | 同 | ❌ 统一 |

> **Planner-Worker 适配**：harness 场景下，Planner 是唯一带跨轮前缀包袱的角色，按本表分层并追加 H1（Tier 2 尾部，高频）——详见 [planner-harness spec §2.4](./2026-07-31-planner-harness-loop-design.md)。Worker/子 Agent 无前缀包袱，不占预算。

---

## 6. Layer 3 — L2 结构化状态（cross-session）

> ✅ **已实现**。`L2ExtractService` + `L2StateStore`

### 6.1 数据模型

表 `user_context_state`（`tenant_id` + `user_id`）：

| 字段 | 说明 |
|------|------|
| `kind` | 11 类：`profile` / `preference` / `goal` / `agreement` / `constraint` / `fact` / `decision` / `reasoning` / `option` / `interim_conclusion` / `topic` |
| `key` / `value` | 稳定键值对 |
| `confidence` | 分级门禁（见下表） |
| `status` | `active` / `superseded` / `void` / `conflict` |
| `expires_at` | 类型化 TTL |
| `source_msg_id` | 溯源 |

### 6.2 分类门禁

| kind | 含义 | 置信 | TTL |
|------|------|------|-----|
| `profile` / `preference` / `agreement` | 稳定画像/偏好/约定 | 0.75 | 365 天 |
| `goal` / `decision` | 目标/决策 | 0.75 | 90 天 |
| `fact` / `constraint` | 事实/约束 | 0.75 | 30 天 |
| `reasoning` | 推理依据 | 0.7 | 7 天 |
| `option` | 备选方案及取舍 | 0.7 | 7 天 |
| `interim_conclusion` | 临时结论 | 0.6 | 7 天 |
| `topic` | 当前话题焦点 | 无 | 1 天 |

写入：assistant completed → `ContextWritePath` → L2 抽取（异步）。高置信静默入库，低置信丢弃。

注入：`assembledContext.l2SystemBlock()` → system 消息块。

### 6.3 优化方向

**6.3.1 类别合并**

`reasoning` / `option` / `interim_conclusion` 三类在 LLM 抽取时边界模糊，错分率较高。建议合并为 `process_note`（过程笔记），统一 7 天 TTL、0.65 置信门禁。

| 当前（v1） | 建议（v2） |
|------------|-----------|
| `reasoning`（推理依据） | → `process_note` |
| `option`（备选方案） | → `process_note` |
| `interim_conclusion`（临时结论） | → `process_note` |

合并后 L2 从 11 类简化为 **9 类**（7 类基础画像 + `process_note` + `topic`）。

**与 Mem0 对照**：
- Mem0 采用单一 `ADD/UPDATE/DELETE/NOOP` 管道，不分类
- 我们的 9 类比 Mem0 粗（有分类价值区分 TTL），比当前 11 类细得合理
- `process_note` 语义清晰：`profile` 是"用户是谁"，`process_note` 是"对话中怎么想的"

**6.3.2 topic TTL 协调**

当前 `topic` TTL 为 1 天，其他过程记忆 7 天。跨天明会话时推理还在但话题锚点已失效。建议：
- `topic` TTL 延长至 3 天（覆盖周末空档）
- 或改为与 `process_note` 统一 7 天，依赖过期机制自然淘汰

**6.3.3 Prompt 改造**

Catalog `context.l2.extract` 需同步更新：

```
kind 只能是：profile, preference, goal, agreement, constraint, fact, decision,
          process_note, topic
- process_note：抽取对话中出现的推理依据、备选方案对比、临时结论等过程性信息，
  需有明确依据来源。
```

### 6.4 语义冲突识别（写路径 · v7）

> **问题根因（2026-08-01 线上 bug）**：`L2StateStore.upsert` 的唯一冲突判定入口是 **kind + key 字面精确匹配**（`findBy…KindAndStateKeyAndStatus`）。两个语义相似或相反的条目只要 key 字面不同（如 `fact/项目数据库=MySQL` vs `fact/项目存储用MySQL`、`constraint/用户不吃辣` vs `constraint/用户偏好重辣`），彼此完全不可见——`L2ConflictMerger` 仅在字面同 key 时触发，value 相反也无法判矛盾，两条同时 `active` 并存注入。事后腐败审计（§9 `auditL2`）虽可用 LLM 标 `conflict`，但它是**异步批量 + 防抖**：矛盾在写入时已被当作新条目接受，且在下一次审计前一直注入。
>
> **目标**：写入路径做**语义识别检索**——新 candidate 入库前，对同 kind 已有 active 条目做语义候选判定，从源头防止「语义相似 key 各自独立成条 / value 相反矛盾」。

**写入路径升级（`L2StateStore.upsert` → 三阶段）**：

```
① 字面快路径（保留现状，零额外成本）
   kind + key 精确命中 active：
     · value 相同 → refresh（不新增）
     · value 不同 → L2ConflictMerger 时间优先/置信门槛
   （命中即返回，不触发语义判定）

② 语义候选检索（仅当 ① 未命中 且 该 kind 存在其他 active 条目）
   候选集 = 同 user + 同 kind 的其余 active 条目（key/value 与 candidate 字面不同）
   v1：候选集全量交 LLM（同 kind 条目规模有限，通常 <50，不引入 embedding）
   v2 可选：embedding 召回 Top-N（复用 rag-service 通道），候选规模大时启用

③ 语义判定（LLM · Catalog context.l2.merge）
   输入：新 candidate(kind,key,value,conf) + 候选集
   输出（每条 candidate）：
     NOOP      → 与候选集语义无关           → 正常新增 active
     MERGE     → 语义等价/同指（措辞不同）   → 合并到 targetId：
                                               mergedKey/mergedValue 归一，
                                               target 刷新值 + 置信取高，不产生 superseded
     UPDATE    → 语义更新（用户改主意/事实演进）→ target 标 superseded + 新增新条
     CONFLICT  → 语义相反/互斥（无法用时间解释）→ target 标 conflict（不注入）
                                               + candidate 标 conflict（或丢弃，保守双标待澄清）
   每条输出含 targetIds + reason（审计可读）
```

**UPDATE vs CONFLICT 判定标准**（写入 Catalog prompt `context.l2.merge`）：
- 语义相反但可用**时间/场景演进**解释（偏好/目标/决策变更）→ `UPDATE`（覆盖，旧条 superseded 审计保留）
- 语义相反且**同为当前客观陈述**、无法用时间解释（事实/约束互斥）→ `CONFLICT`（双标不注入，防污染）
- 语义等价/同指不同措辞（"Java 版本" vs "Java 17"）→ `MERGE`（归一，防 key 碎片化）

**与压缩点 / KV 兼容**：
- 语义判定不破坏 content-hash 幂等（§5.5.6）——判定结果若未产生写库动作则不落库，组装字节不变
- 仅「字面未命中 + 同 kind 有 active」才触发语义路径，**不引入每轮全量 LLM**（字面命中 / 首次写入走快路径）
- Nacos 开关 `agent.context.l2.semantic-merge`（默认 on；关闭回退纯字面，兼容现行为）

**与腐败审计分工**：
- 写路径语义判定 = **增量、主动**（防新增矛盾）
- 腐败审计 `auditL2`（§9）= **批量、兜底**（清历史遗留 + 跨 kind 矛盾）
- 二者共享「双标 conflict 不注入」的判定标准（`context.l2.merge` / `context.l2.audit`），不重复实现

### 6.5 实现文件

| 文件 | 用途 |
|------|------|
| `L2ExtractService.java` | 11 类抽取 + 分级置信 |
| `L2StateStore.java` | CRUD + TTL + 注入组装 + 字面快路径 |
| `L2SemanticMergeService.java` | 语义候选检索 + LLM 判定（`context.l2.merge`）+ NOOP/MERGE/UPDATE/CONFLICT 落库 |
| `L2ConflictMerger.java` | 字面同 key 判定（保留）+ 语义判定结果执行 |
| `ContextProperties.java` | L2 门禁 + TTL + `semantic-merge` 开关 |
| Catalog `context.l2.merge` | 语义判定 prompt（正文 SSOT） |

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

### 7.4 优化方向

**7.4.1 嵌入前增加语义提取层**

当前 L3 直接对原始消息全量分块嵌入，未经任何语义过滤。这与 2026 年业界最佳实践有差距：

| 方案 | 做法 | 与我们的差距 |
|------|------|------------|
| **Oracle 双层模式** | raw event stream 不直接嵌入，异步计算 sparse semantic cache | L3 反之——全量嵌入 |
| **Mem0** | LLM 提取稳定事实 → 向量检索已有记忆 → ADD/UPDATE/DELETE/NOOP | L3 无提取步骤 |
| **Letta/MemGPT** | Chat History 超限后 LLM 摘要，原始归档到 Recall Memory | L3 无摘要步骤 |
| **当前 L3** | 原始消息 → FIXED 分块 → embedding → Milvus | **2024 年初水平** |

**具体问题**：
- "好的"、"谢谢"、"明白了" 等确认语与实质性内容同等嵌入——噪音污染向量空间
- 长回答切成多个 800 字 chunk，同一消息的多个 chunk 争抢 topK 位，检索结果同质化
- 用户反复问类似问题，每次回答独立嵌入（如"K8s Pod 重启怎么办"被问 5 次 → 5 份近似向量）

**建议方案**：在 `ChatHistoryRetrievalService.upsert` 的 `FixedLengthChunker` 之前增加一步 **LLM 语义提取**，将原始消息对转换为精炼的"可检索记忆片段"：

```
user + assistant 消息对
  → LLM 提取 Prompt:
    "从以下对话轮次中提取值得跨会话检索的关键信息：
     1. 用户做出的决策/偏好
     2. 达成的结论/方案
     3. 重要的上下文约定
     忽略确认语、寒暄、重复解释"
  → 多个精炼记忆片段 → 每个独立 embedding
  → 空提取结果 → 跳过（不浪费向量存储）
```

**收益**：
- 过滤 30-50% 低价值消息（寒暄/确认/衔接）
- 长回答压缩为要点（减少 chunk 数，降低同消息 chunk 争抢）
- 跨轮上下文更完整（提供对话对而非孤立消息给 LLM）

**与 L2 的关系**：不冲突。L2 抽取**结构化键值对**（`preference: "用户偏好 Java 17"`），L3 应保留**语义连续的细节段落**（如"上次讨论了 K8s Pod 重启的三种原因：OOMKilled、Liveness probe 失败、节点资源不足，最终确认是第三种..."）。

**7.4.2 触发时机优化**

| 当前 | 建议 | 理由 |
|------|------|------|
| 每轮即时 upsert | **攒批触发**：累积 N 轮或 M 分钟后批量提取+嵌入 | 降低 Milvus 写入频率，LLM 提取可一次处理多轮 |
| user + assistant 独立嵌入 | **轮次对（turn-pair）合并提取** | 保留上下文关系，提取质量更高 |
| 无去重 | 嵌入前检查与已有向量的余弦相似度，> 0.95 跳过 | 减少同质化重复 |

**7.4.3 优先级**

| 改进 | 优先级 | 说明 |
|------|--------|------|
| LLM 语义提取层 | **P1** | 投入 1 次 LLM 调用/轮次对，换取向量质量显著提升 |
| 攒批触发 | P2 | 需改动异步链路，先做语义提取再优化触发 |
| 相似度去重 | P3 | 锦上添花，需评估 Milvus 查询开销 |

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

> 落实 §5.5 建议③：Budget 不再做「静默丢弃」，而是**推进压缩点**的触发源之一，保住「压缩不删原文、摘要可查」原则。

- 原始裁剪顺序中「Mid 从头丢」/「丢 Far」改为**退役并入**：
  1. 超预算 → 先触发一次 **Far 折叠**（Mid 头部并入 far_summary，`far_folded_msg_ids` 前移）
  2. 折叠后仍超预算 → 才丢 Far 摘要块（保留原文 + far_folded 边界，可再次折叠或 L3 回填）
  3. Near 永不丢、L2 constraint 类永不丢（保持既有不变量）
- 触发源链路：`applyBudget` 超限 → 写一条「需压缩」信号 → 跨轮压缩异步执行 → 下一轮 prefix 按新压缩点重建（C3 唯一一次重建）

---

## 9. 治理与防腐败

> ✅ **已实现**。`ContextMaintenanceJob`

| 机制 | 说明 |
|------|------|
| 冲突 | 时间优先覆盖，旧条 `superseded` 审计保留 |
| 语义冲突识别 | **写路径**：语义候选检索 + LLM 判定 NOOP/MERGE/UPDATE/CONFLICT（§6.4，防语义相似 key 各自成条） |
| 过期 | 硬过期 → `void`；过程记忆 7 天短 TTL |
| GC | `gcL3Vectors()` 清理 MySQL 中不存在消息的孤儿向量 |
| 腐败审计 | 明确冲突自动 void；暧昧打标 `conflict`（不注入）；与写路径语义判定互补（增量防新增 / 批量清遗留） |
| 清理 | superseded 180 天 / void 30 天物理删除 |

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
| AutoContext 长工具链可完成 | `phase2_agent_demo.py`（待 `CrossTurnCompactMiddleware` + CompactionConfig 上线后） | 1 |
| SUB 无记忆、企业 KB 不受影响 | 各脚本回归 | All |

---

## 13. 已知局限与后续

### 13.1 P0：Layer 1 三阶段一次原则 — 跨轮压缩仅 1 次 KV Cache 重建

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

> L2 优化方向见 §6.3（类别合并 + topic TTL），L3 优化方向见 §7.4（语义提取层 + 触发时机）。

### 13.3 压缩点模式落地清单（v2 优化 · 设计稿）

> 对应 §5.5 五条建议，回写本文各节后统一在实现时落地：

| # | 建议 | 落点 | 改动 |
|---|------|------|------|
| ① | L1 压缩点前移 | §5.1 / §5.3 | `L1Compressor.partition` 以 `far_folded_msg_ids` 为界；`trimByTokens` 不丢 Near 头部，溢出走压缩 |
| ② | L3 尾部动态段 | §7.5 | `ContextMessageBuilder` 渲染顺序固定：L3 在 query 前绝对尾部 |
| ③ | Budget 退役并入 | §8.2 | `applyBudget` 超限 → 触发 Far 折叠（压缩点前移），非静默丢弃 |
| ④ | chat/task 统一 | §5.5.7 | 压缩点机制场景无关；静态层差异由 scene overlay 注入 |
| ⑤ | 双压缩点衔接 | §5.5.4 | run 内压缩不落库不移动 far_folded_msg_ids；跨轮压缩异步推进 |
| ⑥ | **按频率分层（v3）** | §5.5.3 | Tier 0/1/2 定序；高频块（T0 原稿）移出 Tier 0/1 前部，意图注入走尾部 system 消息 |
| ⑦ | **幂等 upsert + 定宽隔离** | §5.5.6 | L2/W0 抽取加 content-hash 比对；appendix 定宽分隔、阈值重编译；确定性序列化（键排序、无时间戳/session id） |
| ⑧ | **T0 降频** | §5.5.6 / task-scene §6.1 | T0 有界块随压缩点推进刷新，非每轮；与 T0 写路径解耦 |
| ⑨ | **Planner-Worker 分层适配** | §5.5.7 注记 / [planner-harness §2.4](./2026-07-31-planner-harness-loop-design.md) | Planner 上下文 Tier 0/1/2 定序（H1 在 Tier 2 尾部、Worker handoff 双写 L1 尾部 + H1）；Worker 稳定前缀跨 worker 复用，upstream 结果经 `plan_shared_memory` 按需读取 |
| ⑩ | **tools 分层注入** | §5.5.3 v6 注记 / [phase5 §5.5](./phase5-operation-openness-design.md) | 工具规模 > 阈值时：全量名列表进 Tier 0 + Top-K schema 进 Tier 2 尾部；`full`/`retrieval` 由 Nacos `agent.tool.inject` 切换 |
| ⑪ | **L2/W0 语义冲突识别** | §6.4 / task-scene §5.2 | 写路径加语义候选检索 + LLM 判定（NOOP/MERGE/UPDATE/CONFLICT，`context.l2.merge` / `context.ws.merge`）；Nacos `agent.context.l2.semantic-merge` 开关 |

**验收**：`verify_context_compression_live.py` — 非压缩期连续 3 轮 prefix 一致（对比 Gateway 请求体）；压缩后 prefix 重建仅 1 次；Near 尾部随轮次只增不减；L2/W0 未变化时请求体字节级一致（幂等验证）。

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
| `2026-08-01-task-scene-context-design.md` | task 场景适配；压缩点模式据此回写本文 §5.5/§7.5/§8.2（v2 优化） |
| `2026-07-31-planner-harness-loop-design.md` | Planner-Worker 场景；其 §2.4 落地本文 §5.5 分层与压缩点模式（H1 在 Tier 2 尾部、handoff 双写、Worker 稳定前缀） |
| `phase5-operation-openness-design.md` | 运营化；其 5.5 工具分层注入对齐本文 §5.5.3 tools（名列表静态 + schema 尾部）；5.3 `call_scene` 与路由 `scene` 命名隔离 |
