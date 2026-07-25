# 三层记忆系统动态上下文压缩与 L2/L3 优化设计

> 日期：2026-07-24
> 状态：设计待评审
> 范围：orchestrator（L1/L2/L3 上下文）+ llm-gateway（模型元信息）

## 1. 背景与问题

当前三层记忆系统（L1 会话内窗口 / L2 跨会话用户状态 / L3 跨会话向量检索）存在三个核心问题：

### 1.1 L1 压缩过于死板，压缩频率偏高

现状（`orchestrator/.../context/l1/L1Compressor.java:125-141`）：压缩触发是"双条件 OR"——超过 `near 8 + mid 8 = 16` 轮**或**历史总字符超 `120000`。轮次条件通常先于字符条件触发，导致"压缩太频繁"。

更根本的问题：**系统不知道模型上下文窗口多大，也不用真实 token 计量**。全系统无 tokenizer，所有"预算"都是 `String.length()` 字符数近似（见 `ContextAssembler.estimateChars` / `turnsChars` / `trimByChars`）。中文字符数与 token 偏差大（中文 1 字符 ≈ 1.5-2 token），估算不准。

### 1.2 L2 层过于简略，不捕获过程性记忆

现状：L2 每轮异步用 Catalog `context.l2.extract`（`17-sunshine-prompt-manager.sql:587-591`）抽取 7 类结构化状态（profile/preference/goal/agreement/constraint/fact/decision），prompt 明确要求"只抽明确表达、不猜测"。

L2 是"用户画像"型记忆，**不捕获推理过程、方案对比、临时结论**。跨会话时模型丢失对话脉络，只能靠 L3 向量召回补——但 L3 是原文分块，召回精度依赖 query 匹配，对话主线易丢。

### 1.3 L3 召回参数偏保守，跨月记忆衰减过快

现状（`L3RecallService`）：`topK=5`、`minScore=0.55`、时间衰减半衰期 30 天。`minScore=0.55` 对中文语义检索偏高，漏召回多；半衰期 30 天导致 1 个月后相关历史分值腰斩，跨月对话丢记忆。

## 2. 设计目标

1. **L1**：以真实 token 计量，达到模型上下文窗口 80% 才压缩（绝大多数对话不压缩）；轮次降为宽限兜底；压缩触发后 Near 保交互完整性，超阈值时渐进降级。
2. **L2**：扩充 kind 类别，新增 4 类过程性记忆（reasoning/option/interim_conclusion/topic），分级置信门禁。
3. **L3**：调优召回参数（topK/minScore/半衰期/fetchK），Far 已覆盖内容降权而非硬排除。
4. **Gateway**：新增 `/v1/models` 端点 + 模型元信息配置，暴露模型上下文窗口。

## 3. 方案选型记录

| 决策点 | 选择 | 备选 |
|--------|------|------|
| 容量基准计量 | 真实 tokenizer（jtokkit） | 字符比例 / 轻量估算 |
| L1 触发逻辑 | token 阈值为主 + 轮次宽限兜底 | 纯阈值 / 双档 |
| "100%"基准 | 模型上下文窗口的 80% | 有效预算 / 可配置上限 |
| Near/Mid 切分 | Near 保轮数完整 + 超 80% 渐进降级到 Mid | 固定轮数 / 纯 token 预算 |
| tokenizer encoding | 全局 cl100k_base × 1.1 保守系数 | 每模型配 / 纯 cl100k |
| L2 优化 | 扩充 kind 类别（+4 类） | 新增摘要类 / 放宽置信 |
| L3 优化 | 调优召回参数 | 先摘要再向量化 |
| 模型窗口来源 | Gateway 动态返回 | Nacos 映射 / 单默认值 |
| Gateway gap | 本次一并扩 Gateway | 后续独立做 |

## 4. L1 动态上下文压缩详细设计

### 4.1 触发条件改造

**改前**（`L1Compressor.shouldCompress:125-141`）：`countRounds > nearTurns + midTurns` **OR** `totalChars > maxChars`

**改后**：`effectiveToken > modelWindow × 0.8` **OR** `countRounds > turnBackstop`（宽限兜底，默认 40）

- `effectiveToken = TokenEstimator.count(history) × 1.1`（cl100k 估算后乘保守系数，提前触发留 buffer）
- `modelWindow`：从 Gateway `/v1/models` 动态读取并缓存当前模型上下文窗口；Gateway 不可用时降级到 Nacos 配置的默认窗口（`agent.context.l1.default-model-window`，默认 128000）
- `turnBackstop`：宽限轮次上限（`agent.context.l1.turn-backstop`，默认 40），防止极端短消息对话 token 永远到不了 80% 但历史无限膨胀

**关键语义**：80% 是压缩的触发门，不是"每次都压缩到 80%"。**没到 80%，partition 根本不跑**，绝大多数对话不压缩。这才是"降低压缩频率"的核心。

### 4.2 压缩执行流程

压缩仍走现有异步链路（`ContextWritePath.runAsync` 在 turn completed 后），触发后流程：

```
Step 1: shouldCompress 判定
    effectiveToken <= window × 0.8  AND  rounds <= 40
    -> 直接返回，不 partition、不压缩、不摘要（绝大多数对话路径）
    │
    ▼ 满足触发条件
Step 2: partition(history, nearTurns=8, midTurns=8)   ← 三段切分逻辑不变
    Near = 最近 8 轮原文；Mid = 再前 8 轮；Far = 更早全部
    │
    ▼
Step 3: 自适应降级循环（仅当 partition 后组装仍超阈值）
    估算 assembled token = L2块 + farSummary + mid(摘要后估算) + near(原文)
    WHILE assembled > window × 0.8  AND  nearRounds > 1:
        Near 最老一轮 -> Mid 头部
        nearRounds -= 1
        重新估算（该轮从原文变摘要，token 下降）
    │
    ▼
Step 4: 极端兜底（Near 缩到 1 轮仍超）
    走现有 applyBudget 降级：先丢 L3 -> 再丢 Far -> Mid 从头丢
    Near 最后一轮（当前交互）永不丢
    │
    ▼
Step 5: 执行压缩
    Near（nearRounds 轮）：原文保留
    Mid（被吸收的 Near 溢出 + 原 Mid）：assistant 调 LLM 压成 1-3 句（Catalog context.l1.mid-compress）
    Far：增量折叠（Catalog context.l1.far-fold），已折叠 msgId 记入 far_folded_msg_ids 去重
```

**Mid 摘要后 token 估算**：压缩时才调 LLM 生成摘要，降级循环里只有原文。用压缩比估算：`midSummaryToken = midOriginalToken × 0.15`（经验值，1-3 句摘要约为原文 15% token；可配 `agent.context.l1.mid-compress-ratio`）。

### 4.3 partition 三段语义不变

`partition`（`L1Compressor:147-161`）的 Near/Mid/Far 切分逻辑保持不变--Near 永远是最新 N 轮，Mid 是再前 N 轮，Far 吃掉剩余。每次压缩后 Near 仍是最新轮，之前的 Near 降级成 Mid，之前的 Mid 折叠进 Far。

自适应降级循环只调整 `nearRounds` 的实际值（从默认 8 逐轮减），不改 partition 算法本身--partition 接收调整后的 `nearRounds` 参数即可。

### 4.4 token 计量替换

`ContextAssembler` 的字符计量全部替换为 token 计量：

| 方法 | 改前 | 改后 |
|------|------|------|
| `estimateChars`（`:134-142`） | `String.length()` 累加 | `TokenEstimator.countAssembled(ctx)` |
| `turnsChars`（`:144-155`） | `t.content().length()` | `TokenEstimator.count(turns)` |
| `trimByChars`（`:206-223`） | 按 `length()` 裁剪 | 按 token 裁剪，改名 `trimByTokens` |
| `applyBudget`（`:91-132`） | `maxChars` 预算 | `maxTokens` 预算（= `modelWindow × 0.8`） |

`L1Compressor.shouldCompress` 的字符路径删除，替换为 token 路径。

### 4.5 配置变更（Nacos `agent.context.l1.*`）

```yaml
context:
  l1:
    near-turns: 8              # Near 默认轮数（不变）
    mid-turns: 8               # Mid 默认轮数（不变）
    # 删除 max-chars
    max-tokens-ratio: 0.8      # 压缩触发阈值（窗口占比）
    turn-backstop: 40          # 轮次宽限兜底
    default-model-window: 128000  # Gateway 不可用时的降级窗口
    token-safety-factor: 1.1   # cl100k 估算保守系数
    mid-compress-ratio: 0.15   # Mid 摘要后 token 估算比
```

`ContextProperties.L1` 对应增删字段，`@RefreshScope` 支持热更新。

## 5. Gateway 模型元信息扩展 + tokenizer 集成

### 5.1 Gateway 配置改造

**Nacos `docs/nacos/sunshine-llm-gateway.yaml`**：`models` 从字符串列表升级为对象列表，带 `context-window` 和 `encoding`。

```yaml
providers:
  deepseek:
    base-url: https://api.deepseek.com
    api-key: ${DEEPSEEK_API_KEY:...}
    models:
      - name: deepseek-v4-pro
        context-window: 128000
        encoding: cl100k_base
      - name: deepseek-v4-flash
        context-window: 64000
        encoding: cl100k_base
  qwen:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${QWEN_API_KEY:...}
    models:
      - name: qwen-plus
        context-window: 131072
        encoding: cl100k_base
      - name: qwen-max
        context-window: 32768
        encoding: cl100k_base
```

**`ProviderProperties.java`（`:16-28`）配置类改造**：

```java
@Data
public static class ProviderConfig {
    private String baseUrl;
    private String apiKey;
    private List<ModelMeta> models;   // List<String> -> List<ModelMeta>
}

@Data
public static class ModelMeta {
    private String name;
    private int contextWindow;
    private String encoding;   // 默认 cl100k_base
}
```

### 5.2 Gateway 新增 `/v1/models` 端点

新增 `ModelController`（或在 `ChatController` 扩展），暴露 `GET /v1/models`，聚合所有 provider 的 `ModelMeta` 返回：

```json
{
  "object": "list",
  "data": [
    {"id": "deepseek-v4-pro", "context_window": 128000, "encoding": "cl100k_base"},
    {"id": "deepseek-v4-flash", "context_window": 64000, "encoding": "cl100k_base"},
    {"id": "qwen-plus", "context_window": 131072, "encoding": "cl100k_base"},
    {"id": "qwen-max", "context_window": 32768, "encoding": "cl100k_base"}
  ]
}
```

### 5.3 orchestrator 侧：TokenEstimator + ModelWindowCache

**依赖**：jtokkit（`com.knuddels:jtokkit:1.0.0`），加到 orchestrator `pom.xml`。纯 JVM 无外部依赖，性能足够（编码缓存）。

**`TokenEstimator`**（新增）：

```java
@Component
public class TokenEstimator {
    private final Encoding encoding;  // 全局 cl100k_base

    public int count(String text) {
        return text != null ? encoding.countTokens(text) : 0;
    }

    public int count(List<ChatTurn> turns) {
        return turns.stream().mapToInt(t -> count(t.content())).sum();
    }

    public int countAssembled(AssembledContext ctx) {
        return count(ctx.l2SystemBlock()) + count(ctx.farSummaryBlock())
             + count(ctx.l3MaterialBlock())
             + count(ctx.midTurns()) + count(ctx.nearTurns());
    }

    public int effectiveCount(List<SessionTurn> history) {
        int raw = history.stream().mapToInt(t -> count(t.content())).sum();
        return (int) Math.ceil(raw * 1.1);  // 保守系数
    }
}
```

**`ModelWindowCache`**（新增）：启动时调 Gateway `GET /v1/models` 拉取，按模型名缓存 `contextWindow`。`@RefreshScope` 支持热更新。Gateway 不可用时降级到 `agent.context.l1.default-model-window`。

### 5.4 encoding 策略

全局统一 `cl100k_base`（不按模型配独立 encoding）。cl100k 对 deepseek/qwen 的实际 tokenizer 估算偏高 5-15%，通过 `token-safety-factor: 1.1` 保守系数提前触发，留出 buffer 避免真正超窗。简单优先，不为每个模型维护 encoding 映射。

## 6. L2 优化 - 扩充 kind 类别

### 6.1 新增 4 类 kind

在现有 7 类（profile/preference/goal/agreement/constraint/fact/decision）基础上新增：

| 新 kind | 含义 | 示例 |
|--------|------|------|
| `reasoning` | 对话中明确的推理链或判断依据 | "用户倾向方案 B，因为成本更低且团队已熟悉" |
| `option` | 讨论过的备选方案及取舍点 | "考虑过 A（高性能）vs B（低成本），最终选 B" |
| `interim_conclusion` | 临时性结论（尚未固化成 decision） | "暂定下周做 PoC，待数据验证后定" |
| `topic` | 当前活跃话题/上下文焦点 | "正在讨论记忆系统的 L2 优化" |

这 4 类补的是"过程记忆"：当前 L2 只记"是什么"（用户画像），不记"怎么想的、在讨论什么"。

### 6.2 Prompt 改造（Catalog `context.l2.extract`）

改 `17-sunshine-prompt-manager.sql:587-591` 的 prompt_version content_text（新增 catalog version 2）：

```
你是用户状态与对话脉络抽取助手。从对话中识别可跨会话复用的结构化条目。
仅输出 JSON 数组，不要其它文字或 markdown。每项字段：kind、key、value、confidence（0~1）。
kind 只能是：profile、preference、goal、agreement、constraint、fact、decision、reasoning、option、interim_conclusion、topic。
- 前 7 类（profile~decision）：只抽取用户明确表达或双方已确认的内容；不要猜测。
- reasoning/option：抽取对话中出现的推理依据与备选方案对比，需有明确依据来源。
- interim_conclusion：抽取临时性、待验证的结论，value 须含"待验证/暂定"语义。
- topic：抽取当前对话焦点话题，仅 1 条，key 固定 "current_topic"。
无条目时输出 []。
```

### 6.3 置信度门禁分级

现有统一门禁 `minConfidence=0.75`。新增类别分级：

| kind 类别 | minConfidence | 理由 |
|----------|--------------|------|
| 原有 7 类（profile~decision） | 0.75（不变） | 高精度，防幻觉 |
| `reasoning` / `option` | 0.7 | 过程记忆允许略宽松，但有依据要求 |
| `interim_conclusion` | 0.6 | 临时结论本身就不确定，门槛放宽 |
| `topic` | 不设门禁（必抽） | 每轮强制更新话题锚点 |

`ContextProperties.L2` 新增字段：`reasoningMinConfidence=0.7`、`interimConclusionMinConfidence=0.6`。`L2ExtractService` 按 kind 查对应门禁。

### 6.4 存储与 TTL

存储仍用 `user_context_state` 表（`kind` 是字符串，无需改表结构）。新增 TTL 配置：

| kind | TTL |
|------|-----|
| `reasoning` / `option` | 7 天（过程记忆易过时） |
| `interim_conclusion` | 7 天 |
| `topic` | 1 天（话题锚点短生命周期） |

`ContextProperties.L2` 新增：`reasoningTtlDays=7`、`optionTtlDays=7`、`interimConclusionTtlDays=7`、`topicTtlDays=1`。

注入仍走 `AssembledContext.l2SystemBlock()`，新增 4 类拼入。

## 7. L3 优化 - 调优召回参数

### 7.1 参数调整

| 参数 | 改前 | 改后 | 理由 |
|------|------|------|------|
| `topK` | 5 | 8 | 召回更多相关历史；L1 压缩频率下降后 Near 变长，L3 需补更多远期记忆 |
| `minScore` | 0.55 | 0.45 | 中文 embedding 相似度分布偏低，0.55 漏召多；0.45 平衡精度/召回 |
| 半衰期 | 30 天 | 90 天 | 跨月对话记忆不应 1 个月就腰斩，90 天贴合"长期记忆"语义 |
| `fetchK` | topK×3=15 | topK×4=32 | 候选池扩大，给重排更多空间 |

时间衰减公式不变（`score *= 0.5^(ageDays/halfLife)`），只改半衰期常数。

### 7.2 半衰期可配置化

当前 `DECAY_HALF_LIFE_DAYS` 是 `L3RecallService` 的 `private static final`（`:30`）。改为从 `ContextProperties.L3` 读取，新增 `decayHalfLifeDays=90` 字段，`@RefreshScope` 热更新。

### 7.3 Far 已覆盖内容降权（非硬排除）

当前 `filterAndRank`（`L3RecallService:82-120`）对 Near/Mid msgId 硬排除，Far 命中可进 L3。改进：Far 已折叠的 msgId **不硬排除，而是 `score *= 0.5` 降权**--Far 摘要可能丢失细节，L3 原文仍有补充价值。

`fetchK` 公式调整（`:54`）：`Math.min(50, Math.max(topK * 4, topK + excludeSize))`。

### 7.4 配置变更（Nacos `agent.context.l3.*`）

```yaml
l3:
  collection: sunshine_chat_history
  top-k: 8           # 5 -> 8
  min-score: 0.45    # 0.55 -> 0.45
  time-decay: true
  decay-half-life-days: 90   # 新增，原 30 天硬编码
```

## 8. 改造影响清单

### 8.1 新增文件

| 文件 | 模块 | 用途 |
|------|------|------|
| `TokenEstimator.java` | orchestrator | jtokkit token 计量 |
| `ModelWindowCache.java` | orchestrator | Gateway 模型窗口缓存 |
| `ModelController.java` | llm-gateway | `GET /v1/models` 端点 |

### 8.2 修改文件

| 文件 | 改动 |
|------|------|
| `orchestrator/.../context/l1/L1Compressor.java` | `shouldCompress` 改 token 触发；`compressLocked` 加自适应降级循环 |
| `orchestrator/.../context/ContextAssembler.java` | `estimateChars`/`turnsChars`/`trimByChars`/`applyBudget` 全部从字符改 token |
| `orchestrator/.../context/ContextProperties.java` | L1 增删字段（max-chars -> max-tokens-ratio/turn-backstop 等）；L2 加 4 类 TTL + 分级置信；L3 加 decayHalfLifeDays |
| `orchestrator/.../context/l2/L2ExtractService.java` | 按 kind 分级查置信门禁 |
| `orchestrator/.../context/l3/L3RecallService.java` | 半衰期改可配；Far 降权非硬排除；fetchK 公式调整 |
| `llm-gateway/.../config/ProviderProperties.java` | `models` 类型 `List<String>` -> `List<ModelMeta>` |
| `orchestrator/pom.xml` | 加 jtokkit 依赖 |
| `docs/nacos/sunshine-orchestrator.yaml` | `agent.context.l1.*` / `l2.*` / `l3.*` 配置变更 |
| `docs/nacos/sunshine-llm-gateway.yaml` | `providers.*.models` 升级为对象列表 |
| `docker/mysql/init/17-sunshine-prompt-manager.sql` | `context.l2.extract` 新增 version 2（11 类 kind） |

### 8.3 不改动的部分

- `partition` 三段切分算法（Near/Mid/Far 语义不变）
- L1 压缩的异步链路（`ContextWritePath.runAsync`）
- L2 存储 `user_context_state` 表结构
- L3 ingest 逻辑（原文入 Milvus）
- SUB Agent 上下文隔离（`AssembledContext.forSubAgent()` 仍 empty）
- Catalog `context.l1.mid-compress` / `context.l1.far-fold` prompt（压缩摘要质量已验证）

## 9. 验收方案

### 9.1 L1 动态压缩验收

新增 `scripts/verify_dynamic_context_live.py`：

- **T1 短对话不压缩**：5 轮短消息对话，断言 `conversation_context_l1` 无新写入（token 未到 80%）
- **T2 长对话触发压缩**：连续多轮长答案对话，断言 token 超 80% 后才触发 `mid_answers` / `far_summary` 写入
- **T3 轮次宽限兜底**：构造 45 轮极短消息（每轮 <10 token），断言到 40 轮触发压缩
- **T4 自适应降级**：构造 Near 原文超 80% 的场景，断言 `nearRounds` 从 8 逐轮减少，Mid 吸收溢出
- **T5 Gateway 降级**：模拟 Gateway 不可用，断言用 `default-model-window` 降级压缩

### 9.2 L2 扩充 kind 验收

- **T6 新 kind 抽取**：构造含推理/方案对比/临时结论/话题的对话，断言 L2 抽取到 4 类新 kind
- **T7 分级置信**：构造低置信 reasoning（0.65），断言被丢弃；同值 interim_conclusion（0.65）断言保留

### 9.3 L3 调参验收

- **T8 召回增强**：跨月对话召回，断言 topK=8 且 minScore=0.45 召回更多结果
- **T9 半衰期**：60 天前的历史，断言衰减后分值 > 改前（90 天半衰期 vs 30 天）
- **T10 Far 降权**：Far 已折叠 msgId 命中，断言 `score *= 0.5` 后仍可进 L3（非硬排除）

### 9.4 既有验收回归

- `verify_context_layers_live.py`（上下文 L1/L2/L3 Admin + 单测门禁）必须全绿，确保不破坏现有行为。

## 10. 风险与回滚

### 10.1 风险

| 风险 | 缓解 |
|------|------|
| jtokkit 估算偏差（中英文混合） | 1.1 保守系数提前触发，留 buffer |
| Gateway `/v1/models` 不可用 | 降级到 Nacos `default-model-window`，压缩逻辑不阻断 |
| L2 新 kind 抽取质量（reasoning/option 易幻觉） | 分级置信门禁 + prompt 明确"需有依据来源" |
| L3 minScore 降低引入弱相关 | topK 上限控制总量；Far 降权避免重复 |
| 压缩频率下降导致单次压缩量大 | 自适应降级循环控制单次处理量；异步不阻塞用户 |

### 10.2 回滚

所有参数经 Nacos `@RefreshScope` 热更新，可即时回滚：
- L1：`max-tokens-ratio` 调到 `2.0`（永不达，禁用 token 触发）+ `turn-backstop` 调回 `16`（退回旧轮次触发行为）
- L2：`context.l2.extract` catalog rollback 到 version 1（7 类）
- L3：`top-k=5` / `min-score=0.55` / `decay-half-life-days=30`

jtokkit 依赖与 Gateway `/v1/models` 为新增能力，不影响既有路径（Gateway 不可用时降级）。
