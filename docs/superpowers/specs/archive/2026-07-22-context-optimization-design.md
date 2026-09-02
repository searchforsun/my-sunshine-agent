# 上下文压缩统一设计（五层渐进管道）

> 日期：2026-07-22（原稿） · 2026-07-31（重写为统一规格）
> 整合：`2026-07-17-autocontext-memory-design.md` + `2026-07-24-dynamic-context-compression-design.md`
> 状态：**Layer 2/3/4/5 已实现** · **Layer 1 待重新实现**（AgentScope 2.0 迁移后恢复）
> 归属：对话上下文 / 记忆体系重建模（替换旧 STM/MTM/LTM）

---

## 1. 问题

长对话/长任务中上下文持续膨胀，最终溢出模型窗口导致信息丢失。膨胀来源有两条独立路径：

```
intra-turn（单次 ReAct run 内）:
  TOOL → RESULT → TOOL → RESULT → ...
  工具结果累积 → 推理上下文被撑爆

cross-turn（跨用户问答轮次）:
  USER → ASSISTANT → USER → ASSISTANT → ...
  历史对话积累 → 上下文窗口溢出
```

旧方案 C（STM 滑动窗 + MTM 整会话摘要 + LTM 空壳画像）与「同会话 mid/far + 跨会话结构化状态 + 对话 RAG」语义不对齐，且没有统一处理两条压缩路径。本 spec 将二者作为**同一管道的不同层级**统一定义。

## 2. 设计目标

| 做 | 不做 |
|----|------|
| 五层渐进压缩管道：从廉价到昂贵，从自动到按需 | 兼容/双写旧 STM Redis、MTM、`user_memory_profile` |
| Layer 1（intra-turn）：每轮自动清理过期工具结果 | 改 Timeline / SSE 工具结果展示 |
| Layer 2（cross-turn L1）：token 触发 Near/Mid/Far 窗口压缩 | 对最终答案截断/摘要二次加工 |
| Layer 3（cross-session L2）：11 类结构化状态静默抽取 | 用户侧 HITL 确认记忆 |
| Layer 4（cross-session L3）：向量语义检索对话历史 | 与企业知识库混用同一 collection |
| Layer 5（budget）：读时裁剪，极端兜底 | 新建独立 context 微服务 |
| Gateway `/v1/models` 暴露模型窗口 | — |
| 冲突/过期/定时 GC 防上下文腐败 | — |
| Admin 可读写纠错 | SUB/PLANNER 仍无跨轮记忆 |

**核心原则**（源自 Claude Code 五层渐进策略）：
- **先轻后重**：廉价压缩（工具结果清理）每轮自动跑，昂贵压缩（LLM 摘要）最后触发
- **减少触发频率**：token > 80% 窗口才触发跨轮压缩（而非轮次 > 16），绝大多数对话不压缩
- **压缩不可逆但原文可查**：MySQL `chat_message` 保留所有原始消息

---

## 3. 五层管道总览

```
每轮 LLM 调用前：
┌──────────────────────────────────────────────────┐
│ Layer 1  AutoContextMemory      intra-turn 工具压缩  │ ← ⚠️ 待重新实现（AS 2.0 迁移）
│   触发: PreReasoning（每轮自动）                      │
│   开销: 零 LLM 调用                                  │
├──────────────────────────────────────────────────┤
│ Layer 2  L1 Near/Mid/Far        cross-turn 窗口压缩  │ ← ✅ 已实现
│   触发: token > 80% 窗口 OR 轮次 > 40               │
│   开销: 1-2 次 LLM 调用（Mid 摘要 + Far 折叠）        │
├──────────────────────────────────────────────────┤
│ Layer 3  L2 结构化状态           cross-session 记忆  │ ← ✅ 已实现
│   触发: assistant completed → L2 抽取               │
│   开销: 1 次 LLM 调用                                │
├──────────────────────────────────────────────────┤
│ Layer 4  L3 向量检索             cross-session RAG   │ ← ✅ 已实现
│   触发: 每次读时 query → Milvus 语义搜索              │
│   开销: 1 次 embedding + Milvus search              │
├──────────────────────────────────────────────────┤
│ Layer 5  Budget Trimming         读时预算裁剪         │ ← ✅ 已实现
│   触发: 组装后 token > 80% 窗口                      │
│   开销: 零 LLM 调用                                  │
│   顺序: 先丢 L3 → 再丢 Far → Mid 从头丢 → Near 永留   │
└──────────────────────────────────────────────────┘
```

**行业对照**：

| 本系统 Layer | Claude Code 对等层 | Cursor |
|-------------|-------------------|--------|
| Layer 1 | Tier 2 MicroCompact（每轮自动清除工具结果） | 无公开细节 |
| Layer 2 | Tier 5 Auto-Compact（LLM 摘要对话） | 单层 LLM 摘要 |
| Layer 3 | 无对等层（差异化能力） | 无 |
| Layer 4 | 无对等层（差异化能力） | @past chats JSONL |
| Layer 5 | Tier 3 Context Collapse（可回滚投影） | 简单截断 |

---

## 4. Layer 1 — AutoContextMemory（intra-turn 工具压缩）

> ⚠️ **状态：待重新实现**。AgentScope 2.0 迁移时 `AutoContextMemory` 被移除；配置仍在 `MemoryProperties`/Nacos，需重新接入。
> 原方案见 `2026-07-17-autocontext-memory-design.md`（已合并入本文）。

### 4.1 问题

单次 ReAct run 内，多轮 TOOL 结果进入 AgentScope Memory 后易撑爆下一轮 reasoning 上下文。

### 4.2 方案

接入 AgentScope 自带的 `AutoContextMemory` + `AutoContextHook`，不自研裁剪/摘要器。

```
ReActAgent.builder()
  .memory(AutoContextMemory(config, model))   // 替换默认 InMemoryMemory
  .hook(AutoContextHook)                        // priority=0；注册 ContextOffloadTool
  .hook(ProcessingStepHook)                    // Timeline 不变
```

- 压缩发生在 `PreReasoning`（给下一轮 LLM 提供压缩后的上下文）
- 每次 `create` 新建 Memory，无跨请求污染

### 4.3 配置

Nacos `agent.memory.auto-context`：

| 参数 | 默认值 | 说明 |
|------|-------|------|
| `msg-threshold` | 40 | 消息数超此值触发压缩 |
| `last-keep` | 12 | 最近保留的消息数 |
| `min-consecutive-tool-messages` | 4 | 连续工具消息触发清理 |
| `min-compression-token-threshold` | 3000 | 最小压缩 token 阈值 |

### 4.4 与 Layer 2 的关系

Layer 1 和 Layer 2 是**正交独立**的：
- Layer 1 处理**单次 run 内**的工具消息膨胀（每次 LLM 调用前自动跑）
- Layer 2 处理**跨用户轮次**的对话历史膨胀（assistant 完成后异步跑）
- Layer 2 的 token 80% 触发（而非轮次 16）降低了被未压缩工具结果过早触发的概率

---

## 5. Layer 2 — L1 Near/Mid/Far（cross-turn 窗口压缩）

> ✅ **状态：已实现**。`L1Compressor` + `TokenEstimator` + `ModelWindowCache`

### 5.1 三层窗口

| 带 | 范围 | 注入形态 |
|----|------|----------|
| **Near** | 最近约 8 轮 | 完整 `user` / `assistant` 原文 |
| **Mid** | 再前 8 轮 | 完整 `user` + **LLM 压缩后的** `assistant` |
| **Far** | 更早全部 | **LLM 增量折叠**为一条边界摘要块；当前 query 命中时 L3 回填细节 |

- **原文 SSOT**：MySQL `chat_message`（压缩不删原文）
- **派生**：`conversation_context_l1`（`mid_answers` 映射、`far_summary`、窗口元数据）
- **触发**：`TokenEstimator.effectiveCount(history) > modelWindow × 0.8` **OR** 轮次 > 40
- **计量**：jtokkit cl100k_base × 1.1 保守系数（替代原 `String.length()`）
- **模型窗口**：Gateway `GET /v1/models` 动态读取 → `ModelWindowCache` → 不可用时降级 Nacos `defaultModelWindow`（128000）

### 5.2 触发流程

```
Step 1: 判定
  effectiveToken <= window × 0.8  AND  rounds <= 40
  → 直接返回（绝大多数对话路径）
  │
  ▼ 满足触发条件
Step 2: partition(history, nearTurns, midTurns)   ← Near/Mid/Far 三段切分
  │
  ▼
Step 3: 自适应降级（仅组装估算仍超阈值时）
  WHILE assembled > window × 0.8 AND nearRounds > 1:
      Near 最老一轮 → Mid 头部
      nearRounds -= 1
      重新估算（Mid 摘要后 token ≈ 原文 × 0.15）
  │
  ▼
Step 4: 极端兜底（Near 缩到 1 轮仍超）
  applyBudget：先丢 L3 → 再丢 Far → Mid 从头丢
  Near 最后一轮（当前交互）永不丢
  │
  ▼
Step 5: 执行压缩
  Near（nearRounds 轮）：原文保留
  Mid：assistant 调 LLM 压成 1-3 句（Catalog context.l1.mid-compress）
  Far：增量折叠（Catalog context.l1.far-fold），已折叠 msgId 记入 far_folded_msg_ids
```

### 5.3 实现文件

| 文件 | 模块 | 用途 |
|------|------|------|
| `L1Compressor.java` | orchestrator | 触发判定 + partition + 自适应降级 + Mid/Far 压缩 |
| `TokenEstimator.java` | orchestrator | jtokkit cl100k_base token 计量 |
| `ModelWindowCache.java` | orchestrator | Gateway `/v1/models` 缓存 + 降级 |
| `ContextAssembler.java` | orchestrator | 读时组装 + `applyBudget`/`trimByTokens` token 裁剪 |
| `ContextProperties.java` | orchestrator | L1 配置 SSOT（`maxTokensRatio`/`turnBackstop`/`defaultModelWindow`/`tokenSafetyFactor`/`midCompressRatio`） |
| `ModelController.java` | llm-gateway | `GET /v1/models` 端点 |
| `ProviderProperties.java` | llm-gateway | `ModelMeta`（`contextWindow` + `encoding`） |

---

## 6. Layer 3 — L2 结构化状态（cross-session 记忆）

> ✅ **状态：已实现**。`L2ExtractService` + `L2StateStore`

### 6.1 数据模型

表 `user_context_state`（按 `tenant_id` + `user_id`）：

| 字段 | 说明 |
|------|------|
| `kind` | 11 类：`profile` / `preference` / `goal` / `agreement` / `constraint` / `fact` / `decision` / `reasoning` / `option` / `interim_conclusion` / `topic` |
| `key` / `value` | 稳定键与文本/JSON 值 |
| `confidence` | 抽取置信；分级门禁（见下表） |
| `status` | `active` / `superseded` / `void` / `conflict` |
| `expires_at` | 类型化 TTL |
| `source_msg_id` / `updated_at` | 溯源与时间优先 |

### 6.2 分类与门禁

| kind 类别 | 含义 | 置信门禁 | TTL | 备注 |
|----------|------|---------|-----|------|
| `profile` / `preference` / `agreement` | 稳定画像/偏好/约定 | 0.75 | 365 天 | 长寿命 |
| `goal` / `decision` | 目标/决策 | 0.75 | 90 天 | 中寿命 |
| `fact` / `constraint` | 事实/约束 | 0.75 | 30 天 | 短寿命 |
| `reasoning` | 推理链或判断依据 | 0.7 | 7 天 | 过程记忆 |
| `option` | 备选方案及取舍 | 0.7 | 7 天 | 过程记忆 |
| `interim_conclusion` | 临时结论（待验证） | 0.6 | 7 天 | 过程记忆 |
| `topic` | 当前话题焦点 | 无门禁 | 1 天 | 仅 1 条，key=`current_topic` |

**写入**：每轮 assistant completed → `ContextWritePath` → L2 抽取（异步）。高置信静默入库；低置信丢弃。用户无感知。

**注入**：仅 `active` 且未过期的条目 → `AssembledContext.l2SystemBlock()` → system 消息结构化块。

### 6.3 已知局限

- `reasoning` / `option` / `interim_conclusion` 边界模糊，LLM 抽取易混淆。后续可考虑合并为 `process_note`。
- `topic` TTL（1 天）与其他过程记忆（7 天）不协调：跨天明会话时推理还在但话题锚点已失效。

### 6.4 实现文件

| 文件 | 用途 |
|------|------|
| `L2ExtractService.java` | 11 类抽取 + 分级置信门禁 |
| `L2StateStore.java` | `user_context_state` CRUD + TTL 计算 + 注入组装 |
| `ContextProperties.java` | L2 配置 SSOT（门禁 + TTL） |

---

## 7. Layer 4 — L3 向量检索（cross-session RAG）

> ✅ **状态：已实现**。`L3RecallService` + `ChatHistoryRetrievalService` + Milvus

### 7.1 数据流程

```
写入：assistant completed
  → L3IngestService.ingestAsync(user+assistant 消息对)
  → ChatHistoryRetrievalService.upsert
  → FixedLengthChunker（800 字窗口，100 字重叠）
  → EmbeddingService（DashScope text-embedding-v4，1024 维）
  → Milvus sunshine_chat_history（IVF_FLAT / IP）

读取：每次 ContextAssembler.assemble
  → L3RecallService.recall(query)
  → embedding(query)
  → Milvus search(topK=8, 含时间衰减)
  → filterAndRank（排除 Near/Mid 已覆盖 + 时间衰减 + minScore 门禁 + Far 降权）
  → 渲染为「历史材料·可能过期」块 → AssembledContext.l3MaterialBlock
```

### 7.2 检索参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `topK` | **8** | 生产环境 8，Java 默认 5（Nacos 覆盖） |
| `minScore` | **0.45** | 生产环境 0.45，Java 默认 0.55（Nacos 覆盖） |
| `fetchK` | topK × 4 | 候选池，给重排空间（上限 50） |
| 时间衰减 | `score ×= 0.5^(ageDays / 90)` | 半衰期 90 天可配 |
| Far 降权 | `score ×= 0.5` | 非硬排除，原文仍有补充价值 |
| 同 msgId 去重 | 仅保留最高分 chunk | — |
| Near/Mid 排除 | 已在 L1 窗口中的 msgId 硬排除 | — |

### 7.3 实现文件

| 文件 | 模块 | 用途 |
|------|------|------|
| `L3RecallService.java` | orchestrator | 召回 + filterAndRank + 渲染 |
| `L3IngestService.java` | orchestrator | 异步 upsert（user + assistant 消息对） |
| `HistoryRagClient.java` | orchestrator | WebClient 调 rag-service |
| `ChatHistoryRetrievalService.java` | rag-service | 分块 + embedding + Milvus upsert/search |
| `FixedLengthChunker.java` | rag-service | 定长滑动窗口分块 |
| `EmbeddingService.java` | rag-service | DashScope text-embedding-v4 |
| `ChatHistoryMilvusService.java` | rag-service | Milvus sunshine_chat_history CRUD |
| `ContextProperties.java` | orchestrator | L3 配置 SSOT（`topK`/`minScore`/`decayHalfLifeDays`） |

### 7.4 已知局限

- **全量嵌入无语义过滤**：当前所有 user + assistant 消息直接分块嵌入，无提取/去重/价值判定。参考本文开头的分析，后续应在 ingest 前增加 LLM 语义提取层，过滤"好的"、"谢谢"等无价值消息，对长回答做要点压缩。
- **触发时机偏激进**：每轮即时嵌入，无攒批。高并发场景下 Milvus 写入压力大。

---

## 8. Layer 5 — Budget Trimming（读时预算裁剪）

> ✅ **状态：已实现**。`ContextAssembler.applyBudget` + `trimByTokens`

### 8.1 裁剪顺序

组装后 token 超 `modelWindow × 0.8` 时，按优先级从低到高裁剪：

```
先丢 L3（历史材料块）
  → 再丢 Far（远窗摘要块）
  → Mid 从头丢轮次
  → Near 永不丢
  → L2 constraint 类永不丢
```

### 8.2 实现

`ContextAssembler.applyBudget(AssembledContext, maxTokens, TokenEstimator)`：
- 用 `TokenEstimator.countAssembled()` 计算总 token
- 超限时按上述顺序逐层裁剪
- Near 最后一轮（当前交互）为硬保底

---

## 9. 治理与防腐败

> ✅ **状态：已实现**。`ContextMaintenanceJob` + 腐败审计

### 9.1 冲突

- **时间优先**：新高置信同 key 覆盖旧条，旧条 `superseded`（保留审计）
- **类型门槛**：`constraint` / `fact` 覆盖需更高置信或多次印证

### 9.2 过期

- **硬过期**：定时任务 → `void`，不再注入
- **软过期**：仍可注入但标注「可能过期」
- **过程记忆短 TTL**：7 天（reasoning/option/interim_conclusion），1 天（topic）

### 9.3 GC

- `gcL3Vectors()`：删除 MySQL 中不存在消息的孤儿向量
- `superseded` 行 180 天物理删除；`void` 行 30 天物理删除

### 9.4 腐败审计

- 每次 L2 抽取后轻量审计：明确冲突自动 void/清派生，暧昧打标 `conflict`
- 定时全量审计：每小时最多审阅 50 用户
- 详见 `2026-07-22-context-corruption-audit-design.md`

---

## 10. 配置

### Nacos `sunshine-orchestrator.yaml`

```yaml
agent:
  context:
    enabled: true
    l1:
      near-turns: 8
      mid-turns: 8
      max-tokens-ratio: 0.8
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

### Nacos `sunshine-llm-gateway.yaml`（模型窗口暴露）

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
  qwen:
    models:
      - name: qwen-plus
        context-window: 131072
        encoding: cl100k_base
```

Gateway `GET /v1/models` 聚合暴露给 orchestrator 的 `ModelWindowCache`。

### 独立配置区

Layer 1 的 AutoContextMemory 配置独立于本 spec 的三层 context：

```yaml
# Nacos sunshine-orchestrator.yaml — agent.memory.auto-context
agent:
  memory:
    auto-context:
      enabled: true
      msg-threshold: 40
      last-keep: 12
      min-consecutive-tool-messages: 4
      min-compression-token-threshold: 3000
```

---

## 11. 架构总图

```
┌─────────────────────────────────────────────────────────────┐
│                        读路径                                │
│  ChatStreamContextFactory                                    │
│    → ContextAssembler.assemble(user, conv, query)            │
│         ├─ L2StateStore (Layer 3)     → system 稳定状态       │
│         ├─ L1 Store (Layer 2)         → Near/Mid/Far 窗口    │
│         │   └─ TokenEstimator + ModelWindowCache             │
│         ├─ L3 HistoryRagClient (Layer 4) → 按需 chunk        │
│         └─ applyBudget (Layer 5)      → 裁剪或降级           │
│    → AssembledContext → PromptComposer → LLM                  │
├─────────────────────────────────────────────────────────────┤
│                        写路径                                │
│  assistant completed                                         │
│    → ContextLifecycle.onTurnCompleted                        │
│    → ContextWritePath.runAsync（异步，顺序固定）               │
│         ├─ L2 抽取 (Layer 3)    → 分级置信门禁 → upsert      │
│         ├─ L1 压缩 (Layer 2)    → token 判定 → 自适应降级     │
│         └─ L3 ingest (Layer 4)  → 分块+embedding+Milvus      │
├─────────────────────────────────────────────────────────────┤
│                      intra-turn 路径                          │
│  ReActAgent PreReasoning                                     │
│    → AutoContextMemory (Layer 1) → 压缩工具结果 → 下一轮 LLM  │
├─────────────────────────────────────────────────────────────┤
│                       治理路径                                │
│  ContextMaintenanceJob（定时）                                │
│    ├─ L2 过期 void / superseded 归档 / 矛盾打标               │
│    ├─ L3 向量 GC（孤儿清理）                                  │
│    └─ L1 无主会话派生行清理                                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 12. 验收

| 验收项 | 脚本 | Layer |
|--------|------|-------|
| 长会话触发 Mid/Far + Near 自适应降级 | `verify_context_layers_live.py` | 2 |
| 跨会话 L2 11 类静默写入 + 分级置信注入 | `verify_context_layers_live.py` | 3 |
| L3 召回（排除 Near/Mid + Far 降权 + 时间衰减）| `verify_context_layers_live.py` | 4 |
| 短对话不触发 L1 压缩 | `verify_dynamic_context_live.py` T1 | 2 |
| 长对话 token 80% 触发 + 轮次兜底 | `verify_dynamic_context_live.py` T2-T3 | 2 |
| 自适应降级（Near 逐轮缩小） | `verify_dynamic_context_live.py` T4 | 2 |
| Gateway 降级（`defaultModelWindow`） | `verify_dynamic_context_live.py` T5 | 2 |
| L2 新 kind 抽取 + 分级置信 | `verify_dynamic_context_live.py` T6-T7 | 3 |
| L3 调参后召回增强 + 半衰期 + Far 降权 | `verify_dynamic_context_live.py` T8-T10 | 4 |
| AutoContextMemory 长工具链可完成 | `phase2_agent_demo.py`（待恢复） | 1 |
| 回归：SUB 无记忆、AutoContext 行为、企业 KB RAG 不受影响 | 各脚本 | All |

---

## 13. 已知局限与后续规划

| 局限 | 影响 | 建议 |
|------|------|------|
| **Layer 1 缺失** | 长 ReAct 任务无工具结果压缩，推理上下文易撑爆 | P0：重新接入 AutoContextMemory（或自研等效替代） |
| **Layer 4 全量嵌入** | L3 无语义过滤，"好的"等低价值消息浪费向量空间 | 见上文分析，增加 LLM 提取层 |
| **无用户指令保护** | Far 折叠可能丢失用户原始意图 | 在折叠 prompt 中要求逐字保留用户问题（参考 Claude Code 神圣区） |
| **L2 类别粒度过细** | reasoning/option/interim_conclusion 边界模糊 | 后续可合并为 process_note |
| **Layer 1/2 互不感知** | token 估算可能偏大（未压缩工具结果计入历史） | 实际影响有限（L1 在 assistant 完成后跑，此时工具结果不直接参与 L1 计量） |

---

## 14. 文档关系

| 文件 | 关系 |
|------|------|
| `archive/2026-06-17-agent-memory-design.md` | 旧方案 C，已归档 |
| `2026-07-17-autocontext-memory-design.md` | Layer 1 原稿，**已合并入本文**，原文件归档 |
| `2026-07-24-dynamic-context-compression-design.md` | Layer 2/3/4 v2 增强原稿，**已合并入本文**，原文件归档 |
| `2026-07-22-context-corruption-audit-design.md` | 腐败审计子设计，本文 §9 引用 |
| `2026-07-22-l1-admin-window-rows-design.md` | L1 Admin 工具页，小粒度 |
