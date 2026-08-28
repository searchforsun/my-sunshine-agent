# 三层记忆系统动态上下文压缩与 L2/L3 优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 L1 压缩从"固定 16 轮/字符预算"改为"真实 token 达模型窗口 80% 才触发 + 轮次宽限兜底"，L2 扩充 4 类过程记忆，L3 调优召回参数，Gateway 新增 `/v1/models` 暴露模型上下文窗口。

**Architecture:** orchestrator 引入 jtokkit 做真实 token 计量，`TokenEstimator` + `ModelWindowCache` 提供窗口基准；`L1Compressor.shouldCompress` 改 token 阈值触发，压缩时 Near 保交互完整、超阈值渐进降级到 Mid。llm-gateway `ProviderConfig.models` 从 `List<String>` 升级为 `List<ModelMeta>`（带 contextWindow/encoding），新增 `GET /v1/models`。L2 prompt 扩 11 类 kind + 分级置信；L3 半衰期可配 + Far 降权。

**Tech Stack:** Spring Boot（MVC Gateway / WebFlux orchestrator）、Spring Cloud Alibaba Nacos、jtokkit 1.1.0、JUnit 5 + Mockito + AssertJ、MySQL（prompt-manager catalog）。

## Global Constraints

- 提示词 SSOT：prompt-manager DB（`/prompts` + Catalog），`docker/mysql/init/17-sunshine-prompt-manager.sql`；禁止硬编码提示词。
- Nacos 配置 SSOT：改 `docs/nacos/*.yaml` 后必须 `python scripts/sync_nacos.py` 并重启消费服务。
- 项目根：`/usr/local/gitproj/my-sunshine-agent`（**非 git repo**，不做 git commit 步骤，改为"任务完成标记"）。
- 编译命令（README §快速开始）：模块级 `mvn -q -pl <module> -am test-compile`，模块测试 `mvn -q -pl <module> test -Dtest=<Class>`。
- 单元测试风格：JUnit 5 + `@ExtendWith(MockitoExtension.class)` + `assertThat`（AssertJ），纯单元（非 Spring 容器）优先。
- Spring 版本：勿升 Spring Boot 3.3+；AgentScope 勿升 2.0.0。
- jtokkit 版本固定 `com.knuddels:jtokkit:1.1.0`（当前最新稳定）。
- `ChatCompletionResponse` 等用 `@Builder` 的类需 `@NoArgsConstructor` + `@AllArgsConstructor`（项目既有约定）。
- Lombok `@Getter @Setter` 用于配置类；`record` 用于不可变值对象。

---

### Task 1: jtokkit 依赖 + TokenEstimator（token 计量服务）

**Files:**
- Modify: `orchestrator/pom.xml`（dependencies 区，`:15-97`）
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/context/TokenEstimator.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/TokenEstimatorTest.java`

**Interfaces:**
- Consumes: `ChatTurn`（`conversation/ChatTurn.java`，record `role/content`）、`SessionTurn`（`context/SessionTurn.java`，record `messageId/role/content`）、`AssembledContext`（`context/AssembledContext.java`，record `l2SystemBlock/farSummaryBlock/midTurns/nearTurns/l3MaterialBlock`）
- Produces:
  - `int count(String text)` — 单段文本 token 数
  - `int count(List<ChatTurn> turns)` — ChatTurn 列表 token 数
  - `int countAssembled(AssembledContext ctx)` — 组装上下文总 token
  - `int effectiveCount(List<SessionTurn> history, double safetyFactor)` — 会话历史 token × 保守系数

- [ ] **Step 1: 加 jtokkit 依赖**

在 `orchestrator/pom.xml` 的 `<dependencies>` 区（紧跟 `caffeine` 依赖之后，`:45-48`）插入：

```xml
        <dependency>
            <groupId>com.knuddels</groupId>
            <artifactId>jtokkit</artifactId>
            <version>1.1.0</version>
        </dependency>
```

- [ ] **Step 2: 验证依赖拉取**

Run: `mvn -q -pl orchestrator -am dependency:resolve -DincludeArtifactIds=jtokkit`
Expected: 无报错，jtokkit 1.1.0 解析成功。

- [ ] **Step 3: 写失败测试**

创建 `orchestrator/src/test/java/com/sunshine/orchestrator/context/TokenEstimatorTest.java`：

```java
package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void count_nullOrEmpty_returnsZero() {
        assertThat(estimator.count((String) null)).isZero();
        assertThat(estimator.count("")).isZero();
    }

    @Test
    void count_englishText_positiveAndProportional() {
        int hello = estimator.count("hello");
        int helloWorld = estimator.count("hello world");
        assertThat(hello).isPositive();
        assertThat(helloWorld).isGreaterThan(hello);
    }

    @Test
    void count_chineseText_positive() {
        assertThat(estimator.count("你好，世界")).isPositive();
    }

    @Test
    void count_chatTurns_sumsContent() {
        List<ChatTurn> turns = List.of(
                new ChatTurn("user", "hello"),
                new ChatTurn("assistant", "hello world"));
        assertThat(estimator.count(turns))
                .isEqualTo(estimator.count("hello") + estimator.count("hello world"));
    }

    @Test
    void countAssembled_sumsAllBlocks() {
        AssembledContext ctx = new AssembledContext(
                "L2 block",
                "Far summary",
                List.of(new ChatTurn("assistant", "mid")),
                List.of(new ChatTurn("user", "near")),
                "L3 material");
        int expected = estimator.count("L2 block")
                + estimator.count("Far summary")
                + estimator.count("mid")
                + estimator.count("near")
                + estimator.count("L3 material");
        assertThat(estimator.countAssembled(ctx)).isEqualTo(expected);
    }

    @Test
    void effectiveCount_appliesSafetyFactor() {
        List<SessionTurn> history = List.of(
                SessionTurn.of("u1", "user", "hello"),
                SessionTurn.of("a1", "assistant", "hello world"));
        int raw = estimator.count("hello") + estimator.count("hello world");
        assertThat(estimator.effectiveCount(history, 1.1))
                .isEqualTo((int) Math.ceil(raw * 1.1));
    }

    @Test
    void effectiveCount_nullHistory_returnsZero() {
        assertThat(estimator.effectiveCount(null, 1.1)).isZero();
        assertThat(estimator.effectiveCount(List.of(), 1.1)).isZero();
    }
}
```

- [ ] **Step 4: 跑测试验证失败**

Run: `mvn -q -pl orchestrator test -Dtest=TokenEstimatorTest`
Expected: 编译失败，`TokenEstimator` 不存在。

- [ ] **Step 5: 实现 TokenEstimator**

创建 `orchestrator/src/main/java/com/sunshine/orchestrator/context/TokenEstimator.java`：

```java
package com.sunshine.orchestrator.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.sunshine.orchestrator.conversation.ChatTurn;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 真实 token 计量（jtokkit cl100k_base），替代原 String.length() 字符估算。
 * cl100k 对 deepseek/qwen 估算偏高 5-15%，经 effectiveCount 的 safetyFactor 保守系数提前触发。
 */
@Component
public class TokenEstimator {

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    /** 单段文本 token 数。 */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /** ChatTurn 列表 token 数（仅 content 求和）。 */
    public int count(List<ChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (ChatTurn t : turns) {
            if (t != null) {
                n += count(t.content());
            }
        }
        return n;
    }

    /** 组装上下文总 token：L2 + Far + L3 + Mid + Near。 */
    public int countAssembled(AssembledContext ctx) {
        if (ctx == null) {
            return 0;
        }
        return count(ctx.l2SystemBlock())
                + count(ctx.farSummaryBlock())
                + count(ctx.l3MaterialBlock())
                + count(ctx.midTurns())
                + count(ctx.nearTurns());
    }

    /** 会话历史 token × 保守系数（SessionTurn 仅 content 求和）。 */
    public int effectiveCount(List<SessionTurn> history, double safetyFactor) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int raw = 0;
        for (SessionTurn t : history) {
            if (t != null) {
                raw += count(t.content());
            }
        }
        return (int) Math.ceil(raw * safetyFactor);
    }
}
```

- [ ] **Step 6: 跑测试验证通过**

Run: `mvn -q -pl orchestrator test -Dtest=TokenEstimatorTest`
Expected: PASS（7 个测试全绿）。

- [ ] **Step 7: 任务完成标记**

TokenEstimator 就绪，后续 L1 压缩/组装都用它替换字符计量。

---

### Task 2: ContextProperties 配置字段改造（L1/L2/L3）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextProperties.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/ContextPropertiesTest.java`

**Interfaces:**
- Consumes: 无（纯配置类）
- Produces:
  - `L1.getMaxTokensRatio()`（默认 0.8）、`getTurnBackstop()`（默认 40）、`getDefaultModelWindow()`（默认 128000）、`getTokenSafetyFactor()`（默认 1.1）、`getMidCompressRatio()`（默认 0.15）
  - `L1.getNearTurns()` / `getMidTurns()`（不变）；**删除** `getMaxChars()`
  - `L2.getReasoningMinConfidence()`（0.7）、`getInterimConclusionMinConfidence()`（0.6）、`getReasoningTtlDays()`（7）、`getOptionTtlDays()`（7）、`getInterimConclusionTtlDays()`（7）、`getTopicTtlDays()`（1）
  - `L3.getDecayHalfLifeDays()`（90）

- [ ] **Step 1: 写失败测试**

创建 `orchestrator/src/test/java/com/sunshine/orchestrator/context/ContextPropertiesTest.java`：

```java
package com.sunshine.orchestrator.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPropertiesTest {

    @Test
    void l1_defaults() {
        ContextProperties.L1 l1 = new ContextProperties().getL1();
        assertThat(l1.getNearTurns()).isEqualTo(8);
        assertThat(l1.getMidTurns()).isEqualTo(8);
        assertThat(l1.getMaxTokensRatio()).isEqualTo(0.8);
        assertThat(l1.getTurnBackstop()).isEqualTo(40);
        assertThat(l1.getDefaultModelWindow()).isEqualTo(128000);
        assertThat(l1.getTokenSafetyFactor()).isEqualTo(1.1);
        assertThat(l1.getMidCompressRatio()).isEqualTo(0.15);
    }

    @Test
    void l2_newKindDefaults() {
        ContextProperties.L2 l2 = new ContextProperties().getL2();
        assertThat(l2.getMinConfidence()).isEqualTo(0.75);
        assertThat(l2.getReasoningMinConfidence()).isEqualTo(0.7);
        assertThat(l2.getInterimConclusionMinConfidence()).isEqualTo(0.6);
        assertThat(l2.getReasoningTtlDays()).isEqualTo(7);
        assertThat(l2.getOptionTtlDays()).isEqualTo(7);
        assertThat(l2.getInterimConclusionTtlDays()).isEqualTo(7);
        assertThat(l2.getTopicTtlDays()).isEqualTo(1);
    }

    @Test
    void l3_decayHalfLifeDaysDefault() {
        ContextProperties.L3 l3 = new ContextProperties().getL3();
        assertThat(l3.getTopK()).isEqualTo(5);
        assertThat(l3.getMinScore()).isEqualTo(0.55);
        assertThat(l3.getDecayHalfLifeDays()).isEqualTo(90);
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -q -pl orchestrator test -Dtest=ContextPropertiesTest`
Expected: 编译失败，`getMaxTokensRatio` 等方法不存在。

- [ ] **Step 3: 改造 ContextProperties**

修改 `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextProperties.java`：

`L1` 内部类（`:27-34`）整体替换为：

```java
    @Getter
    @Setter
    public static class L1 {
        /** 近窗保留的问答轮次数（一轮 = 1 次 user + 其后 assistant），非消息条数。 */
        private int nearTurns = 8;
        /** 中窗轮次数；仅压缩该窗内 assistant 答案。 */
        private int midTurns = 8;
        /** 压缩触发阈值（模型上下文窗口占比），达到即触发压缩。 */
        private double maxTokensRatio = 0.8;
        /** 轮次宽限兜底：即使 token 未到阈值，轮数超此值也触发（防极端短消息无限膨胀）。 */
        private int turnBackstop = 40;
        /** Gateway 不可用时的降级模型上下文窗口（token）。 */
        private int defaultModelWindow = 128000;
        /** cl100k 估算保守系数（对 deepseek/qwen 偏高 5-15%，提前触发留 buffer）。 */
        private double tokenSafetyFactor = 1.1;
        /** Mid 摘要后 token 估算比（1-3 句摘要约为原文 15%）。 */
        private double midCompressRatio = 0.15;
        /**
         * @deprecated 字符预算已废弃，token 计量上线后由收尾任务清理删除。暂保留以维持编译。
         */
        @Deprecated
        private int maxChars = 120_000;
    }
```

`L2` 内部类（`:38-47`）整体替换为：

```java
    @Getter
    @Setter
    public static class L2 {
        private double minConfidence = 0.75;
        private double constraintOverwriteConfidence = 0.9;
        private int preferenceTtlDays = 365;
        private int agreementTtlDays = 365;
        private int goalTtlDays = 90;
        private int decisionTtlDays = 90;
        private int factTtlDays = 30;
        private int constraintTtlDays = 30;
        /** 过程记忆（reasoning/option）分级置信门禁。 */
        private double reasoningMinConfidence = 0.7;
        /** 临时结论（interim_conclusion）分级置信门禁。 */
        private double interimConclusionMinConfidence = 0.6;
        /** 过程记忆 TTL（易过时）。 */
        private int reasoningTtlDays = 7;
        private int optionTtlDays = 7;
        private int interimConclusionTtlDays = 7;
        /** 话题锚点 TTL（短生命周期）。 */
        private int topicTtlDays = 1;
    }
```

`L3` 内部类（`:51-56`）整体替换为：

```java
    @Getter
    @Setter
    public static class L3 {
        private String collection = "sunshine_chat_history";
        private int topK = 5;
        private double minScore = 0.55;
        private boolean timeDecay = true;
        /** 时间衰减半衰期（天）：score *= 0.5^(ageDays / halfLife)。 */
        private int decayHalfLifeDays = 90;
    }
```

注意：删除了 `L1.maxChars` 字段。

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -q -pl orchestrator test -Dtest=ContextPropertiesTest`
Expected: PASS（3 个测试全绿）。

- [ ] **Step 5: 全模块编译验证**

Run: `mvn -q -pl orchestrator -am test-compile`
Expected: 编译成功（`maxChars` 保留为 deprecated，无引用断裂）。

- [ ] **Step 6: 任务完成标记**

ContextProperties 新字段就绪，`maxChars` 保留为 `@Deprecated` 待收尾清理。后续 L1 触发/降级、L2 分级、L3 半衰期都从这里读。

---

### Task 3: ModelWindowCache（模型上下文窗口缓存）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ModelWindowCache.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/ModelWindowCacheTest.java`

**Interfaces:**
- Consumes: `ContextProperties.L1.getDefaultModelWindow()`（Task 2）
- Produces:
  - `int windowFor(String model)` — 返回模型上下文窗口；缓存未命中且 Gateway 不可用时返回 `defaultModelWindow`
  - `void refresh(Map<String, Integer> windows)` — 用 Gateway `/v1/models` 响应刷新缓存

- [ ] **Step 1: 写失败测试**

创建 `orchestrator/src/test/java/com/sunshine/orchestrator/context/ModelWindowCacheTest.java`：

```java
package com.sunshine.orchestrator.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelWindowCacheTest {

    private ContextProperties properties;
    private ModelWindowCache cache;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        properties.getL1().setDefaultModelWindow(128000);
        cache = new ModelWindowCache(properties);
    }

    @Test
    void windowFor_knownModel_returnsCached() {
        cache.refresh(Map.of("deepseek-v4-pro", 128000, "qwen-plus", 131072));
        assertThat(cache.windowFor("deepseek-v4-pro")).isEqualTo(128000);
        assertThat(cache.windowFor("qwen-plus")).isEqualTo(131072);
    }

    @Test
    void windowFor_unknownModel_returnsDefault() {
        cache.refresh(Map.of("deepseek-v4-pro", 128000));
        assertThat(cache.windowFor("unknown-model")).isEqualTo(128000);
    }

    @Test
    void windowFor_noRefresh_returnsDefault() {
        assertThat(cache.windowFor("deepseek-v4-pro")).isEqualTo(128000);
    }

    @Test
    void refresh_replacesStaleEntries() {
        cache.refresh(Map.of("m1", 100));
        cache.refresh(Map.of("m2", 200));
        assertThat(cache.windowFor("m1")).isEqualTo(128000);
        assertThat(cache.windowFor("m2")).isEqualTo(200);
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -q -pl orchestrator test -Dtest=ModelWindowCacheTest`
Expected: 编译失败，`ModelWindowCache` 不存在。

- [ ] **Step 3: 实现 ModelWindowCache**

创建 `orchestrator/src/main/java/com/sunshine/orchestrator/context/ModelWindowCache.java`：

```java
package com.sunshine.orchestrator.context;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型上下文窗口缓存：Gateway /v1/models 响应刷新；未命中/未刷新降级 defaultModelWindow。
 */
@Component
public class ModelWindowCache {

    private final ContextProperties contextProperties;
    private final AtomicReference<Map<String, Integer>> windows = new AtomicReference<>(Map.of());

    public ModelWindowCache(ContextProperties contextProperties) {
        this.contextProperties = contextProperties;
    }

    /** 模型上下文窗口；未命中降级 defaultModelWindow。 */
    public int windowFor(String model) {
        if (model != null) {
            Integer w = windows.get().get(model);
            if (w != null && w > 0) {
                return w;
            }
        }
        return contextProperties.getL1().getDefaultModelWindow();
    }

    /** 用 Gateway /v1/models 响应整体替换缓存。 */
    public void refresh(Map<String, Integer> newWindows) {
        windows.set(newWindows != null
                ? Map.copyOf(new ConcurrentHashMap<>(newWindows))
                : Map.of());
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -q -pl orchestrator test -Dtest=ModelWindowCacheTest`
Expected: PASS（4 个测试全绿）。

- [ ] **Step 5: 任务完成标记**

ModelWindowCache 就绪。Gateway `/v1/models` 拉取逻辑在 Task 9（Gateway 端点 + orchestrator 消费）补全。

---

### Task 4: L1 触发条件改造（token 阈值 + 轮次宽限兜底）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l1/L1Compressor.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/l1/L1CompressorTriggerTest.java`

**Interfaces:**
- Consumes:
  - `TokenEstimator.effectiveCount(history, safetyFactor)`（Task 1）
  - `ModelWindowCache.windowFor(model)`（Task 3）
  - `ContextProperties.L1.getMaxTokensRatio()/getTurnBackstop()/getTokenSafetyFactor()`（Task 2）
- Produces:
  - `boolean shouldCompress(List<SessionTurn> history, ContextProperties.L1 l1, int modelWindow, TokenEstimator estimator)` — 静态判定，替换旧 `shouldCompress(history, nearTurns, midTurns, maxChars)`
  - 旧 `shouldCompress` 删除（被新签名替代）

- [ ] **Step 1: 写失败测试**

创建 `orchestrator/src/test/java/com/sunshine/orchestrator/context/l1/L1CompressorTriggerTest.java`：

```java
package com.sunshine.orchestrator.context.l1;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class L1CompressorTriggerTest {

    private final TokenEstimator estimator = new TokenEstimator();

    private ContextProperties.L1 l1() {
        ContextProperties.L1 l1 = new ContextProperties.L1();
        l1.setMaxTokensRatio(0.8);
        l1.setTurnBackstop(40);
        l1.setTokenSafetyFactor(1.1);
        return l1;
    }

    private List<SessionTurn> rounds(int n, String content) {
        List<SessionTurn> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(SessionTurn.of("u" + i, "user", content));
            out.add(SessionTurn.of("a" + i, "assistant", content));
        }
        return out;
    }

    @Test
    void shouldCompress_tokenOverThreshold() {
        // window=1000，ratio=0.8 → 阈值 800；effective = raw×1.1 > 800 → raw > 727
        // 用足够长的内容让 raw token 超阈值
        List<SessionTurn> history = rounds(3, "word ".repeat(400));
        assertThat(L1Compressor.shouldCompress(history, l1(), 1000, estimator)).isTrue();
    }

    @Test
    void shouldNotCompress_tokenUnderThresholdAndRoundsUnderBackstop() {
        // 5 轮短消息，token 远低于阈值，轮数 < 40
        List<SessionTurn> history = rounds(5, "hi");
        assertThat(L1Compressor.shouldCompress(history, l1(), 1000, estimator)).isFalse();
    }

    @Test
    void shouldCompress_roundsOverBackstopEvenIfTokenLow() {
        // 45 轮极短消息，token 低，但轮数 > 40 兜底触发
        List<SessionTurn> history = rounds(45, "hi");
        assertThat(L1Compressor.shouldCompress(history, l1(), 1000, estimator)).isTrue();
    }

    @Test
    void shouldCompress_emptyHistory_false() {
        assertThat(L1Compressor.shouldCompress(List.of(), l1(), 1000, estimator)).isFalse();
        assertThat(L1Compressor.shouldCompress(null, l1(), 1000, estimator)).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -q -pl orchestrator test -Dtest=L1CompressorTriggerTest`
Expected: 编译失败，`shouldCompress(history, l1, window, estimator)` 签名不存在。

- [ ] **Step 3: 改造 shouldCompress + 调用方 + 更新旧测试**

修改 `orchestrator/src/main/java/com/sunshine/orchestrator/context/l1/L1Compressor.java`：

注入 `TokenEstimator` 和 `ModelWindowCache`（类字段区 `:38-42` 之后加），并加模型名 `@Value`：

```java
    private final TokenEstimator tokenEstimator;
    private final ModelWindowCache modelWindowCache;

    @org.springframework.beans.factory.annotation.Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;
```

删除旧 `shouldCompress` 静态方法（`:125-141`），替换为：

```java
    /** token 阈值为主 + 轮次宽限兜底：effectiveToken > window×ratio 或轮数 > turnBackstop。 */
    public static boolean shouldCompress(
            List<SessionTurn> history,
            ContextProperties.L1 l1,
            int modelWindow,
            TokenEstimator estimator) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        if (l1 == null || estimator == null || modelWindow <= 0) {
            return false;
        }
        int effective = estimator.effectiveCount(history, l1.getTokenSafetyFactor());
        int threshold = (int) (modelWindow * l1.getMaxTokensRatio());
        if (effective > threshold) {
            return true;
        }
        return countRounds(history) > Math.max(1, l1.getTurnBackstop());
    }
```

修改 `compressLocked`（`:69-77`）的触发判定（`modelWindow` 在实例方法里取，传给静态 `shouldCompress`）：

```java
    private void compressLocked(String userId, String tenantId, String convId, List<SessionTurn> history) {
        ContextProperties.L1 l1 = contextProperties.getL1();
        int modelWindow = modelWindowCache.windowFor(modelName);
        if (!shouldCompress(history, l1, modelWindow, tokenEstimator)) {
            return;
        }
        int nearN = Math.max(1, l1.getNearTurns());
        int midN = Math.max(0, l1.getMidTurns());
        WindowBands bands = partition(history, nearN, midN);
        // ... 后续不变
```

**更新旧测试**：`L1CompressorTest`（`orchestrator/src/test/java/.../l1/L1CompressorTest.java`）的构造器和三个 `shouldCompress_*` 测试需适配：

1. `setUp()`（`:58-74`）：`compressor = new L1Compressor(...)` 构造器增加两个参数。在字段区加 `@Mock private TokenEstimator tokenEstimator;` 和 `@Mock private ModelWindowCache modelWindowCache;`，构造改为 `new L1Compressor(properties, llm, store, l2StateStore, catalogHolder, tokenEstimator, modelWindowCache)`。删除 `properties.getL1().setMaxChars(100_000)`（已废弃）。在 `setUp` 末尾加 `lenient().when(modelWindowCache.windowFor(anyString())).thenReturn(128000);` 和 `lenient().when(tokenEstimator.effectiveCount(any(), anyDouble())).thenReturn(10);`（默认低 token，靠轮数触发旧测试）。imports 需补 `import static org.mockito.ArgumentMatchers.anyDouble;` 和 `import com.sunshine.orchestrator.context.ModelWindowCache;`、`import com.sunshine.orchestrator.context.TokenEstimator;`。

2. 删除三个旧触发测试（`:76-102` 的 `shouldCompress_whenOverMaxCharsEvenIfUnderTurnCap`、`shouldCompress_whenOverTurnCapEvenIfUnderChars`、`shouldNotCompress_whenUnderBothCaps`）——它们测的是旧字符/轮次双条件，新逻辑由 `L1CompressorTriggerTest` 覆盖。

3. 其余测试（`compress_writesMidAnswersAndFarSummary` 等）依赖"轮数超 near+mid 即触发"的旧行为。新逻辑下 token 低（mock 返回 10）且轮数 > turnBackstop（默认 40）才触发，但这些测试只有 5-6 轮，**不会触发压缩**，导致 `verify(store).upsert(...)` 失败。**修法**：在 `setUp` 里把 `properties.getL1().setTurnBackstop(4)`（小于测试用的 5-6 轮），让轮数兜底触发，保持这些测试语义不变。

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -q -pl orchestrator test -Dtest='L1CompressorTriggerTest,L1CompressorTest'`
Expected: PASS（新触发测试 4 个 + 旧压缩测试全绿）。

- [ ] **Step 5: 全模块编译验证**

Run: `mvn -q -pl orchestrator -am test-compile`
Expected: 编译成功（旧测试已适配新构造器与触发逻辑）。

- [ ] **Step 6: 任务完成标记**

L1 触发改为 token 阈值 + 轮次兜底。压缩频率从"超 16 轮就压"降为"token 到 80% 才压"。旧测试已适配。

---

### Task 5: L1 自适应降级循环（Near 保完整 + 超阈值渐进降级到 Mid）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l1/L1Compressor.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/l1/L1CompressorAdaptiveTest.java`

**Interfaces:**
- Consumes:
  - `TokenEstimator.count(SessionTurn content)` / `count(List<ChatTurn>)`（Task 1）
  - `ContextProperties.L1.getMidCompressRatio()`（Task 2）
  - `ModelWindowCache.windowFor(model)`（Task 3）
  - `L2StateStore.assembleSystemBlock(userId, tenantId)`（既有）
- Produces:
  - `static int resolveNearRounds(List<SessionTurn> history, ContextProperties.L1 l1, int modelWindow, TokenEstimator estimator, String l2Block, String farSummary)` — 自适应计算实际 Near 轮数（默认 nearTurns，超阈值时逐轮递减到 1）

- [ ] **Step 1: 写失败测试**

创建 `orchestrator/src/test/java/com/sunshine/orchestrator/context/l1/L1CompressorAdaptiveTest.java`：

```java
package com.sunshine.orchestrator.context.l1;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class L1CompressorAdaptiveTest {

    private final TokenEstimator estimator = new TokenEstimator();

    private ContextProperties.L1 l1() {
        ContextProperties.L1 l1 = new ContextProperties.L1();
        l1.setNearTurns(8);
        l1.setMidTurns(8);
        l1.setMaxTokensRatio(0.8);
        l1.setTokenSafetyFactor(1.1);
        l1.setMidCompressRatio(0.15);
        return l1;
    }

    private List<SessionTurn> rounds(int n, String content) {
        List<SessionTurn> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(SessionTurn.of("u" + i, "user", content));
            out.add(SessionTurn.of("a" + i, "assistant", content));
        }
        return out;
    }

    @Test
    void resolveNearRounds_shortHistory_keepsDefault() {
        // 短对话，token 远低于阈值，Near 保持默认 8
        List<SessionTurn> history = rounds(10, "hi");
        int near = L1Compressor.resolveNearRounds(history, l1(), 100_000, estimator, "", "");
        assertThat(near).isEqualTo(8);
    }

    @Test
    void resolveNearRounds_longNear_shrinksBelowDefault() {
        // Near 原文超长导致组装超阈值，Near 应缩小
        // window=200，ratio=0.8 → 阈值 160；每轮约 50 token，8 轮 near ≈ 400 token 超阈值
        List<SessionTurn> history = rounds(10, "word ".repeat(12));
        int near = L1Compressor.resolveNearRounds(history, l1(), 200, estimator, "", "");
        assertThat(near).isLessThan(8);
        assertThat(near).isGreaterThanOrEqualTo(1);
    }

    @Test
    void resolveNearRounds_extremeLong_neverBelowOne() {
        // 极端超长，Near 缩到 1 轮（保当前交互完整）
        List<SessionTurn> history = rounds(10, "word ".repeat(500));
        int near = L1Compressor.resolveNearRounds(history, l1(), 100, estimator, "", "");
        assertThat(near).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -q -pl orchestrator test -Dtest=L1CompressorAdaptiveTest`
Expected: 编译失败，`resolveNearRounds` 不存在。

- [ ] **Step 3: 实现 resolveNearRounds + 集成到 compressLocked**

在 `L1Compressor` 加静态方法（放在 `partition` 方法之后，`:161` 附近）：

```java
    /**
     * 自适应 Near 轮数：默认 nearTurns 保交互完整；组装估算超阈值时逐轮缩小 Near（溢出转 Mid），
     * 直到 token 降到阈值内或 Near 缩到 1 轮（当前交互永不丢）。
     * Mid 摘要后 token 按 midCompressRatio 估算。
     */
    public static int resolveNearRounds(
            List<SessionTurn> history,
            ContextProperties.L1 l1,
            int modelWindow,
            TokenEstimator estimator,
            String l2Block,
            String farSummary) {
        int defaultNear = Math.max(1, l1.getNearTurns());
        if (history == null || history.isEmpty() || estimator == null || modelWindow <= 0) {
            return defaultNear;
        }
        int threshold = (int) (modelWindow * l1.getMaxTokensRatio());
        int totalRounds = countRounds(history);
        int near = Math.min(defaultNear, totalRounds);
        double midRatio = l1.getMidCompressRatio() > 0 ? l1.getMidCompressRatio() : 0.15;

        while (near > 1) {
            int assembled = estimateAssembled(history, l1, estimator, l2Block, farSummary, near, midRatio);
            if (assembled <= threshold) {
                break;
            }
            near--;
        }
        return near;
    }

    /** 估算组装后总 token：L2 + Far + Mid(摘要后估算) + Near(原文)。 */
    private static int estimateAssembled(
            List<SessionTurn> history,
            ContextProperties.L1 l1,
            TokenEstimator estimator,
            String l2Block,
            String farSummary,
            int nearRounds,
            double midRatio) {
        WindowBands bands = partition(history, nearRounds, l1.getMidTurns());
        int n = estimator.count(l2Block) + estimator.count(farSummary);
        int midRaw = 0;
        for (SessionTurn t : bands.mid()) {
            if (t != null) {
                midRaw += estimator.count(t.content());
            }
        }
        n += (int) (midRaw * midRatio);
        for (SessionTurn t : bands.near()) {
            if (t != null) {
                n += estimator.count(t.content());
            }
        }
        return n;
    }
```

在 `compressLocked` 里用 `resolveNearRounds` 替换固定 `nearN`（`:70-77` 区域）：

```java
    private void compressLocked(String userId, String tenantId, String convId, List<SessionTurn> history) {
        ContextProperties.L1 l1 = contextProperties.getL1();
        int modelWindow = modelWindowCache.windowFor(modelName);
        if (!shouldCompress(history, l1, modelWindow, tokenEstimator)) {
            return;
        }
        ConversationContextL1Entity existing = l1Store.find(convId).orElse(null);
        String farSummary = l1Store.farSummaryOf(existing);
        String l2Block = l2StateStore.assembleSystemBlock(userId, tenantId);
        int nearN = resolveNearRounds(history, l1, modelWindow, tokenEstimator, l2Block, farSummary);
        int midN = Math.max(0, l1.getMidTurns());
        WindowBands bands = partition(history, nearN, midN);
        Map<String, String> midAnswers = new HashMap<>(l1Store.parseMidAnswers(existing));
        LinkedHashSet<String> foldedIds = new LinkedHashSet<>(l1Store.parseFarFoldedMsgIds(existing));
        // ... 后续 mid/far 处理逻辑不变（原 :78-122）
```

注意：原 `compressLocked` 在 `:78-81` 也调用了 `l1Store.find` / `farSummaryOf` / `parseMidAnswers` / `parseFarFoldedMsgIds`，需调整顺序——`existing`/`farSummary`/`l2Block` 提前到 `resolveNearRounds` 之前取，后续复用，避免重复查询。

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -q -pl orchestrator test -Dtest='L1CompressorAdaptiveTest,L1CompressorTest,L1CompressorTriggerTest'`
Expected: PASS。注意旧 `L1CompressorTest` 里 `verify(store).upsert(..., eq(2), eq(2))` 断言 nearN=2：新逻辑下 `resolveNearRounds` 会用 `l1.getNearTurns()=2`（setUp 里设置）作默认，短内容下保持 2，断言仍成立。

- [ ] **Step 5: 全模块编译验证**

Run: `mvn -q -pl orchestrator -am test-compile`
Expected: 编译成功。

- [ ] **Step 6: 任务完成标记**

L1 自适应降级就绪：Near 默认保轮数完整，超阈值渐进缩小转 Mid，极端情况缩到 1 轮保当前交互。

---

### Task 6: ContextAssembler token 计量替换（字符 → token）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextAssembler.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/ContextAssemblerBudgetTest.java`（更新）

**Interfaces:**
- Consumes:
  - `TokenEstimator.countAssembled(ctx)` / `count(List<ChatTurn>)` / `count(String)`（Task 1）
  - `ContextProperties.L1.getMaxTokensRatio()` / `getDefaultModelWindow()`（Task 2）
  - `ModelWindowCache.windowFor(model)`（Task 3）
- Produces:
  - `ContextAssembler` 组装预算改为 token（`applyBudget` / `trimByTokens`），对外接口 `assemble(AssembleRequest)` 不变

- [ ] **Step 1: 更新预算测试（先改测试适配 token）**

`ContextAssemblerBudgetTest` 是纯静态方法测试（无 Spring/Mockito，直接 `new AssembledContext(...)` + `ContextAssembler.applyBudget(full, budget)`）。改造后 `applyBudget` 需 `TokenEstimator` 实例。修改 `orchestrator/src/test/java/com/sunshine/orchestrator/context/ContextAssemblerBudgetTest.java`：

1. 类字段加 `private final TokenEstimator estimator = new TokenEstimator();`，import `com.sunshine.orchestrator.context.TokenEstimator`（同包可省）。
2. 所有 `ContextAssembler.applyBudget(full, budgetChars)` 调用改为 `ContextAssembler.applyBudget(full, budgetTokens, estimator)`。
3. **预算值从字符长度改 token 数**：原测试用 `l2.length()` / `far.length()` / `"M3".repeat(20).length()` 计算预算，改为用 `estimator.count(...)` 计算。例如：
   - `int midNear = estimator.count("mid-q") + estimator.count("near-q");`
   - `int withL2Only = estimator.count(l2) + midNear;`
   - `int budgetDropL3KeepFar = withL2Only + estimator.count(far);`
   - `applyBudget_trimsMidFromHeadWhenStillOverBudget` 里 `keepL2NearAndOneMid = estimator.count(l2) + estimator.count("near") + estimator.count("M3".repeat(20));`
   - `applyBudget_withinLimit_keepsAll` 里预算 `100_000` 保持（token 预算足够大即可）。

- [ ] **Step 2: 改造 ContextAssembler**

修改 `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextAssembler.java`：

注入依赖（字段区 `:28-31` 之后加）：

```java
    private final TokenEstimator tokenEstimator;
    private final ModelWindowCache modelWindowCache;

    @org.springframework.beans.factory.annotation.Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;
```

`assemble` 方法（`:33-85`）中预算相关改动：

- `:39-41` 的 `l1.getMaxChars()` 相关：`near` 的 `trimByChars(bands.near(), l1.getMaxChars())` 改为 `trimByTokens(bands.near(), nearBudgetTokens)`。
- `:75` 的 `applyBudget(assembled, l1.getMaxChars())` 改为 `applyBudget(assembled, budgetTokens)`。

在 `assemble` 开头（`:37` 后）计算 token 预算：

```java
        int modelWindow = modelWindowCache.windowFor(modelName);
        int budgetTokens = (int) (modelWindow * l1.getMaxTokensRatio());
```

替换计量方法（`:134-159`）：

```java
    static int estimateTokens(AssembledContext ctx, TokenEstimator estimator) {
        return estimator.countAssembled(ctx);
    }
```

`applyBudget`（`:91-132`）签名与计量改为 token：

```java
    static AssembledContext applyBudget(AssembledContext ctx, int maxTokens, TokenEstimator estimator) {
        if (ctx == null) {
            return AssembledContext.empty();
        }
        if (maxTokens <= 0 || estimator == null) {
            return ctx;
        }
        if (estimator.countAssembled(ctx) <= maxTokens) {
            return ctx;
        }
        AssembledContext dropL3 = new AssembledContext(
                ctx.l2SystemBlock(), ctx.farSummaryBlock(), ctx.midTurns(), ctx.nearTurns(), "");
        if (estimator.countAssembled(dropL3) <= maxTokens) {
            return dropL3;
        }
        AssembledContext dropFar = new AssembledContext(
                ctx.l2SystemBlock(), "", ctx.midTurns(), ctx.nearTurns(), "");
        if (estimator.countAssembled(dropFar) <= maxTokens) {
            return dropFar;
        }
        List<ChatTurn> mid = ctx.midTurns() != null
                ? new ArrayList<>(ctx.midTurns())
                : new ArrayList<>();
        while (!mid.isEmpty() && estimator.countAssembled(new AssembledContext(
                ctx.l2SystemBlock(), "", mid, ctx.nearTurns(), "")) > maxTokens) {
            mid.remove(0);
        }
        return new AssembledContext(
                ctx.l2SystemBlock(), "", List.copyOf(mid), ctx.nearTurns(), "");
    }
```

`trimByChars`（`:206-223`）改名 `trimByTokens`，计量改 token：

```java
    /** 超 maxTokens 从头整条丢弃（不截断单条 content）；入参为 Near 带 SessionTurn。 */
    static List<SessionTurn> trimByTokens(List<SessionTurn> turns, int maxTokens, TokenEstimator estimator) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        if (maxTokens <= 0 || estimator == null) {
            return List.copyOf(turns);
        }
        int total = 0;
        for (SessionTurn t : turns) {
            total += t != null ? estimator.count(t.content()) : 0;
        }
        if (total <= maxTokens) {
            return List.copyOf(turns);
        }
        List<SessionTurn> out = new ArrayList<>(turns);
        while (!out.isEmpty() && total > maxTokens) {
            SessionTurn removed = out.remove(0);
            total -= removed.content() != null ? estimator.count(removed.content()) : 0;
        }
        return List.copyOf(out);
    }
```

删除旧 `estimateChars` / `turnsChars` / `len` / `trimByChars` 方法（`:134-159`、`:206-223` 的旧版本）。

`assemble` 里 `trimByTokens` 调用（`:48`）改为传 `budgetTokens`（Near 也受整体预算约束）：

```java
        List<ChatTurn> near = toChatTurns(trimByTokens(bands.near(), budgetTokens, tokenEstimator));
```

- [ ] **Step 3: 更新 ContextAssemblerL1Test / ContextAssemblerTest**

**`ContextAssemblerL1Test`**（4 参构造器 `new ContextAssembler(properties, l1Store, l2StateStore, l3RecallService)`）：
1. 字段区加 `@Mock private ModelWindowCache modelWindowCache;`，`private TokenEstimator tokenEstimator = new TokenEstimator();`（真实实例，非 mock——token 计量是纯函数）。
2. `setUp()`：构造改 6 参 `new ContextAssembler(properties, l1Store, l2StateStore, l3RecallService, tokenEstimator, modelWindowCache)`；删除 `properties.getL1().setMaxChars(100_000)`；加 `lenient().when(modelWindowCache.windowFor(anyString())).thenReturn(128000);`（窗口足够大，测试不触发预算裁剪）。
3. 断言不变（这些测试验 L2/Far/Mid 注入逻辑，不涉预算边界）。

**`ContextAssemblerTest`**（4 参构造器 + 多处 `setMaxChars`）：
1. 字段区加 `@Mock private ModelWindowCache modelWindowCache;`，`private TokenEstimator tokenEstimator = new TokenEstimator();`。
2. `setUp()`：构造改 6 参 `new ContextAssembler(properties, l1Store, l2StateStore, l3RecallService, tokenEstimator, modelWindowCache)`；加 `lenient().when(modelWindowCache.windowFor(anyString())).thenReturn(128000);`（默认大窗口不触发裁剪）。
3. 删除所有 `properties.getL1().setMaxChars(...)` 调用（共 5 处：`:54`、`:77`、`:94`、`:152`、及 `assemble_keepsLastNearTurns` 内的）。这些原本控制裁剪预算，现由 `windowFor × maxTokensRatio` 决定。
4. **`assemble_dropsWholeTurnsFromHeadWhenOverMaxChars` 测试**（`:74-89`）：原用 `setMaxChars(6)` 触发裁剪，改为 mock 小窗口触发 token 裁剪。该测试用 3 条消息（"aaaa"/"bbbb"/"cc"），需让 `budgetTokens` 小到只装下后两条。改法：测试体内 `when(modelWindowCache.windowFor(anyString())).thenReturn(2);`（窗口 2 × ratio 0.8 ≈ 1 token 预算，裁剪到只剩能放下的尾部）。断言保持 `nearTurns` 从头丢弃的逻辑不变，但具体保留几条需按实际 token 数调整——执行时用 `estimator.count("aaaa")` 等确认阈值，使"aaaa"被丢、"bbbb"+"cc"保留（或按 token 数重设窗口值）。**执行时先跑一遍看实际裁剪结果，再把窗口值/断言校准到确定性。**
5. 其余测试（`assemble_keepsLastNearTurns` / `assemble_historyWithinBudget_keepsAll` 等）默认大窗口不触发裁剪，断言不变。

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -q -pl orchestrator test -Dtest='ContextAssembler*Test'`
Expected: PASS（所有 ContextAssembler 相关测试全绿）。

- [ ] **Step 5: 全模块编译验证**

Run: `mvn -q -pl orchestrator -am test-compile`
Expected: 编译成功（无 `getMaxChars` / `estimateChars` / `trimByChars` 残留引用）。

- [ ] **Step 6: 任务完成标记**

ContextAssembler 全面切换 token 计量。组装预算与 L1 压缩用同一窗口基准。

---

### Task 7: L2 扩充 kind 类别 + 分级置信门禁

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2ExtractService.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2StateStore.java`
- Modify: `docker/mysql/init/17-sunshine-prompt-manager.sql`（`context.l2.extract` 新增 version 2）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/l2/L2ExtractServiceParseTest.java`（更新）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/l2/L2ExtractConfidenceTest.java`（新增）

**Interfaces:**
- Consumes:
  - `ContextProperties.L2.getReasoningMinConfidence()/getInterimConclusionMinConfidence()/getReasoningTtlDays()/getOptionTtlDays()/getInterimConclusionTtlDays()/getTopicTtlDays()`（Task 2）
- Produces:
  - `L2ExtractService.minConfidenceFor(String kind, ContextProperties.L2 l2)` — 按 kind 分级查置信门禁
  - `L2StateStore.ttlDays` 支持 4 个新 kind
  - `VALID_KINDS` 扩到 11 类

- [ ] **Step 1: 写失败测试（置信分级）**

创建 `orchestrator/src/test/java/com/sunshine/orchestrator/context/l2/L2ExtractConfidenceTest.java`：

```java
package com.sunshine.orchestrator.context.l2;

import com.sunshine.orchestrator.context.ContextProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class L2ExtractConfidenceTest {

    private final ContextProperties.L2 l2 = new ContextProperties.L2();

    @Test
    void minConfidenceFor_originalKinds_usesDefault() {
        assertThat(L2ExtractService.minConfidenceFor("profile", l2)).isEqualTo(0.75);
        assertThat(L2ExtractService.minConfidenceFor("decision", l2)).isEqualTo(0.75);
        assertThat(L2ExtractService.minConfidenceFor("constraint", l2)).isEqualTo(0.75);
    }

    @Test
    void minConfidenceFor_reasoningAndOption_uses070() {
        assertThat(L2ExtractService.minConfidenceFor("reasoning", l2)).isEqualTo(0.7);
        assertThat(L2ExtractService.minConfidenceFor("option", l2)).isEqualTo(0.7);
    }

    @Test
    void minConfidenceFor_interimConclusion_uses060() {
        assertThat(L2ExtractService.minConfidenceFor("interim_conclusion", l2)).isEqualTo(0.6);
    }

    @Test
    void minConfidenceFor_topic_noGate() {
        assertThat(L2ExtractService.minConfidenceFor("topic", l2)).isEqualTo(0.0);
    }

    @Test
    void ttlDays_newKinds() {
        ContextProperties.L2 props = new ContextProperties.L2();
        assertThat(L2StateStore.ttlDays("reasoning", props)).isEqualTo(7);
        assertThat(L2StateStore.ttlDays("option", props)).isEqualTo(7);
        assertThat(L2StateStore.ttlDays("interim_conclusion", props)).isEqualTo(7);
        assertThat(L2StateStore.ttlDays("topic", props)).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -q -pl orchestrator test -Dtest=L2ExtractConfidenceTest`
Expected: 编译失败，`minConfidenceFor` 不存在，`ttlDays` 对新 kind 返回 default。

- [ ] **Step 3: 改造 L2ExtractService（VALID_KINDS + 分级门禁）**

修改 `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2ExtractService.java`：

`VALID_KINDS`（`:33-34`）扩到 11 类：

```java
    private static final Set<String> VALID_KINDS = Set.of(
            "profile", "preference", "goal", "agreement", "constraint", "fact", "decision",
            "reasoning", "option", "interim_conclusion", "topic");
```

`extract` 方法（`:84-96`）的置信门禁改为按 kind 分级：

```java
        List<L2ConflictMerger.Candidate> candidates = parseCandidates(raw);
        ContextProperties.L2 l2 = contextProperties.getL2();
        Instant now = Instant.now();
        int accepted = 0;
        for (L2ConflictMerger.Candidate c : candidates) {
            double minConf = minConfidenceFor(c.kind(), l2);
            if (c.confidence() < minConf) {
                log.debug("[ContextL2] drop low confidence kind={} key={} conf={}",
                        c.kind(), c.key(), c.confidence());
                continue;
            }
            l2StateStore.upsert(userId, tenantId, c, sourceMsgId, now);
            accepted++;
        }
```

新增静态方法（放在 `buildExtractPayload` 之前）：

```java
    /** 按 kind 分级置信门禁：原 7 类 0.75，reasoning/option 0.7，interim_conclusion 0.6，topic 无门禁。 */
    static double minConfidenceFor(String kind, ContextProperties.L2 l2) {
        if (l2 == null) {
            l2 = new ContextProperties.L2();
        }
        return switch (L2ConflictMerger.normalizeKind(kind)) {
            case "reasoning", "option" -> l2.getReasoningMinConfidence();
            case "interim_conclusion" -> l2.getInterimConclusionMinConfidence();
            case "topic" -> 0.0;
            default -> l2.getMinConfidence();
        };
    }
```

- [ ] **Step 4: 改造 L2StateStore.ttlDays**

修改 `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2StateStore.java` 的 `ttlDays`（`:179-192`）：

```java
    static int ttlDays(String kind, ContextProperties.L2 l2) {
        if (l2 == null) {
            l2 = new ContextProperties.L2();
        }
        return switch (L2ConflictMerger.normalizeKind(kind)) {
            case "preference", "profile" -> l2.getPreferenceTtlDays();
            case "agreement" -> l2.getAgreementTtlDays();
            case "goal" -> l2.getGoalTtlDays();
            case "decision" -> l2.getDecisionTtlDays();
            case "fact" -> l2.getFactTtlDays();
            case "constraint" -> l2.getConstraintTtlDays();
            case "reasoning" -> l2.getReasoningTtlDays();
            case "option" -> l2.getOptionTtlDays();
            case "interim_conclusion" -> l2.getInterimConclusionTtlDays();
            case "topic" -> l2.getTopicTtlDays();
            default -> l2.getFactTtlDays();
        };
    }
```

- [ ] **Step 5: 更新 parse 测试（新 kind 通过校验）**

修改 `L2ExtractServiceParseTest.java`，在 `parseCandidates_readsJsonArrayAndSkipsInvalidKind` 测试里加新 kind 用例，断言新 kind 不再被当作 invalid 丢弃：

在该测试的 `raw` JSON 数组里加一项 `{"kind":"reasoning","key":"why-b","value":"成本更低","confidence":0.8}`，断言 `list` 从 `hasSize(2)` 改 `hasSize(3)`，且包含 `reasoning`。

- [ ] **Step 6: 更新 prompt catalog（version 2）**

修改 `docker/mysql/init/17-sunshine-prompt-manager.sql`。在 `context.l2.extract` 的 version 1 INSERT（`:587-591`）之后新增 version 2，并把 `active_version` 指向 2：

在 `:591` 的 version 1 INSERT 后追加：

```sql
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('context.l2.extract', 2, 'published', '你是用户状态与对话脉络抽取助手。从对话中识别可跨会话复用的结构化条目。
仅输出 JSON 数组，不要其它文字或 markdown。每项字段：kind、key、value、confidence（0~1）。
kind 只能是：profile、preference、goal、agreement、constraint、fact、decision、reasoning、option、interim_conclusion、topic。
- 前 7 类（profile~decision）：只抽取用户明确表达或双方已确认的内容；不要猜测。
- reasoning/option：抽取对话中出现的推理依据与备选方案对比，需有明确依据来源。
- interim_conclusion：抽取临时性、待验证的结论，value 须含"待验证/暂定"语义。
- topic：抽取当前对话焦点话题，仅 1 条，key 固定 "current_topic"。
无条目时输出 []。', NULL, 'L2 extend kinds +4 (reasoning/option/interim/topic)', 'prompt-ops');
UPDATE prompt_definition SET active_version = 2, catalog_version = catalog_version + 1 WHERE id = 'context.l2.extract';
```

注意：`prompt_definition` 的 `active_version` 字段原值是 1，需 UPDATE 到 2。同时文件末尾 `:613` 已有 `UPDATE prompt_catalog_meta SET catalog_version = catalog_version + 1`，保留。

- [ ] **Step 7: 跑测试验证通过**

Run: `mvn -q -pl orchestrator test -Dtest='L2Extract*Test,L2StateStoreFilterTest'`
Expected: PASS。

- [ ] **Step 8: 全模块编译验证**

Run: `mvn -q -pl orchestrator -am test-compile`
Expected: 编译成功。

- [ ] **Step 9: 任务完成标记**

L2 扩到 11 类 kind，分级置信门禁 + 新 TTL 生效。prompt catalog 升级 version 2（需后续 sync + 重启生效，见 Task 11）。

---

### Task 8: L3 调优召回参数（半衰期可配 + Far 降权 + fetchK 扩大）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l3/L3RecallService.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/l3/L3RecallServiceTest.java`（更新）

**Interfaces:**
- Consumes: `ContextProperties.L3.getDecayHalfLifeDays()`（Task 2）
- Produces:
  - `L3RecallService.applyTimeDecay(score, createdAtMs, now, halfLifeDays)` — 半衰期参数化
  - `filterAndRank` 对 Far 已折叠 msgId 降权 `score × 0.5`（非硬排除）
  - `fetchK` 公式改 `topK×4`

- [ ] **Step 1: 更新 L3 测试**

`L3RecallServiceTest`（`orchestrator/src/test/java/.../l3/L3RecallServiceTest.java`）改造点：

1. `setUp()`（`:34-43`）：无需改构造器（`L3RecallService(properties, historyRagClient, catalogHolder)` 不变）。可保留 `setTopK(5)/setMinScore(0.55)/setTimeDecay(false)`（这些仍是有效果配置）。

2. `filterAndRank_appliesTimeDecay`（`:80-96`）：`filterAndRank` 静态签名不变（仍传 `l3` + `now`），但半衰期从 `l3.getDecayHalfLifeDays()` 读（默认 90）。`new ContextProperties.L3()` 默认 `decayHalfLifeDays=90`，91 天衰减 = `0.5^(91/90)≈0.496`，`0.8×0.496≈0.397`。断言 `isLessThan(0.8)` + `isGreaterThan(0.05)` 仍成立，**无需改**。但更新注释 `:85` 从 `~91 days ≈ 1/8 score` 改为 `~91 days ≈ 0.5 score（90 天半衰期）`。

3. 新增 Far 降权测试（加在类末尾）：

```java
    @Test
    void filterAndRank_farHit_isDownWeightedNotExcluded() {
        ContextProperties.L3 l3 = new ContextProperties.L3();
        l3.setMinScore(0.3);
        l3.setTimeDecay(false);
        // Far 已折叠 msgId 命中：score 0.92 × 0.5 = 0.46 ≥ minScore 0.3 → 保留（非硬排除）
        List<L3RecallService.ScoredHit> kept = L3RecallService.filterAndRank(
                List.of(new HistoryRagClient.HistoryHit("c1", "msg-far", "远窗细节", 0.92f, 1L)),
                Set.of(),
                Set.of("msg-far"),
                true,
                l3,
                Instant.now());
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).score()).isCloseTo(0.46, org.assertj.core.data.Offset.offset(0.001));
        assertThat(kept.get(0).farBackfill()).isTrue();
    }

    @Test
    void filterAndRank_farHit_belowMinScoreAfterDownWeight_excluded() {
        ContextProperties.L3 l3 = new ContextProperties.L3();
        l3.setMinScore(0.5);
        l3.setTimeDecay(false);
        // score 0.92 × 0.5 = 0.46 < minScore 0.5 → 降权后被过滤
        List<L3RecallService.ScoredHit> kept = L3RecallService.filterAndRank(
                List.of(new HistoryRagClient.HistoryHit("c1", "msg-far", "远窗细节", 0.92f, 1L)),
                Set.of(),
                Set.of("msg-far"),
                true,
                l3,
                Instant.now());
        assertThat(kept).isEmpty();
    }
```

注意：`recall_farBackfill_includesFarHitsWhenSummaryPresent`（`:65-77`）现有测试的 hit score=0.92，降权后 0.46，若 `setUp` 的 `minScore=0.55` 生效则会被过滤导致断言失败。**但该测试走 `recall.recall(...)` 路径且 `setTimeDecay(false)`**：降权 0.46 < 0.55 → 被过滤，`block` 不含"远窗细节"断言失败。**修法**：该测试体内把 `properties.getL3().setMinScore(0.4);`（让 0.46 ≥ 0.4 通过），保持"Far 命中可进 L3"的语义断言。

- [ ] **Step 2: 改造 L3RecallService**

修改 `orchestrator/src/main/java/com/sunshine/orchestrator/context/l3/L3RecallService.java`：

删除 `DECAY_HALF_LIFE_DAYS` 常量（`:29-30`），改从配置读。

`recall` 方法（`:51-54`）的 `fetchK` 公式调整：

```java
        int fetchK = Math.min(50, Math.max(topK * 4, topK + (excludeMsgIds != null ? excludeMsgIds.size() : 0)));
```

`filterAndRank`（`:82-120`）改造——Far 降权 + 半衰期参数化：

```java
    static List<ScoredHit> filterAndRank(
            List<HistoryRagClient.HistoryHit> raw,
            Set<String> excludeMsgIds,
            Set<String> farMsgIds,
            boolean farSummaryNonEmpty,
            ContextProperties.L3 l3,
            Instant now) {
        double minScore = l3.getMinScore();
        boolean timeDecay = l3.isTimeDecay();
        double halfLife = l3.getDecayHalfLifeDays() > 0 ? l3.getDecayHalfLifeDays() : 90.0;
        Map<String, ScoredHit> bestByMsg = new LinkedHashMap<>();
        for (HistoryRagClient.HistoryHit hit : raw) {
            if (hit == null || !StringUtils.hasText(hit.content())) {
                continue;
            }
            String msgId = hit.msgId() != null ? hit.msgId() : "";
            if (StringUtils.hasText(msgId) && excludeMsgIds.contains(msgId)) {
                continue;
            }
            boolean inFar = StringUtils.hasText(msgId) && farMsgIds.contains(msgId);
            double score = hit.score();
            if (timeDecay) {
                score = applyTimeDecay(score, hit.createdAtMs(), now, halfLife);
            }
            // Far 已折叠 msgId 降权（非硬排除）：摘要可能丢细节，原文仍有补充价值
            if (inFar) {
                score *= 0.5;
            }
            if (score < minScore) {
                continue;
            }
            boolean farBackfill = inFar && farSummaryNonEmpty;
            ScoredHit scored = new ScoredHit(hit.convId(), msgId, hit.content(), score, farBackfill);
            ScoredHit prev = bestByMsg.get(msgId.isEmpty() ? hit.content() : msgId);
            if (prev == null || scored.score() > prev.score()) {
                bestByMsg.put(msgId.isEmpty() ? hit.content() : msgId, scored);
            }
        }
        List<ScoredHit> out = new ArrayList<>(bestByMsg.values());
        out.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return out;
    }
```

`applyTimeDecay`（`:122-130`）参数化半衰期：

```java
    static double applyTimeDecay(double score, long createdAtMs, Instant now, double halfLifeDays) {
        if (createdAtMs <= 0 || now == null) {
            return score;
        }
        long ageMs = Math.max(0L, now.toEpochMilli() - createdAtMs);
        double ageDays = ageMs / 86_400_000.0;
        double factor = Math.pow(0.5, ageDays / halfLifeDays);
        return score * factor;
    }
```

- [ ] **Step 3: 跑测试验证通过**

Run: `mvn -q -pl orchestrator test -Dtest=L3RecallServiceTest`
Expected: PASS（含 2 个新增 Far 降权测试）。

- [ ] **Step 4: 全模块编译验证**

Run: `mvn -q -pl orchestrator -am test-compile`
Expected: 编译成功。

- [ ] **Step 5: 任务完成标记**

L3 半衰期可配（默认 90 天）、Far 降权非硬排除、fetchK 扩到 topK×4。topK/minScore 改值走 Nacos 配置（Task 10）。

---

### Task 9: Gateway 模型元信息扩展（ProviderConfig.models 升级 + /v1/models + orchestrator 消费）

**Files:**
- Modify: `llm-gateway/src/main/java/com/sunshine/llm/config/ProviderProperties.java`
- Modify: `llm-gateway/src/main/java/com/sunshine/llm/adapter/DeepSeekAdapter.java`（`supports` 适配）
- Modify: `llm-gateway/src/main/java/com/sunshine/llm/adapter/QwenAdapter.java`（`supports` 适配）
- Create: `llm-gateway/src/main/java/com/sunshine/llm/model/ModelListResponse.java`
- Create: `llm-gateway/src/main/java/com/sunshine/llm/controller/ModelController.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ModelWindowCache.java`（加 Gateway 拉取）
- Test: `llm-gateway/src/test/java/com/sunshine/llm/controller/ModelControllerTest.java`
- Test: `llm-gateway/src/test/java/com/sunshine/llm/config/ProviderPropertiesTest.java`

**Interfaces:**
- Consumes: Nacos `llm.providers.*.models`（升级为对象列表，Task 11 配置）
- Produces:
  - `ProviderProperties.ModelMeta`（`name/contextWindow/encoding`）
  - `GET /v1/models` 返回 `ModelListResponse`（`object=list` + `data: [{id, context_window, encoding}]`）
  - `ModelWindowCache.refreshFromGateway()` — orchestrator 侧拉取并刷新缓存

- [ ] **Step 1: 写失败测试（ProviderProperties 绑定）**

创建 `llm-gateway/src/test/java/com/sunshine/llm/config/ProviderPropertiesTest.java`：

```java
package com.sunshine.llm.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderPropertiesTest {

    @Test
    void bindsModelMetaList() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "llm.providers.deepseek.base-url", "https://api.deepseek.com",
                "llm.providers.deepseek.api-key", "k",
                "llm.providers.deepseek.models[0].name", "deepseek-v4-pro",
                "llm.providers.deepseek.models[0].context-window", "128000",
                "llm.providers.deepseek.models[0].encoding", "cl100k_base"));
        ProviderProperties props = new Binder(source)
                .bind("llm", Bindable.of(ProviderProperties.class))
                .orElseThrow();
        ProviderProperties.ProviderConfig ds = props.getProviders().get("deepseek");
        assertThat(ds.getModels()).hasSize(1);
        ProviderProperties.ModelMeta meta = ds.getModels().get(0);
        assertThat(meta.getName()).isEqualTo("deepseek-v4-pro");
        assertThat(meta.getContextWindow()).isEqualTo(128000);
        assertThat(meta.getEncoding()).isEqualTo("cl100k_base");
    }

    @Test
    void modelNames_returnsNamesForSupports() {
        ProviderProperties.ProviderConfig config = new ProviderProperties.ProviderConfig();
        ProviderProperties.ModelMeta m = new ProviderProperties.ModelMeta();
        m.setName("deepseek-v4-pro");
        config.setModels(List.of(m));
        assertThat(config.modelNames()).containsExactly("deepseek-v4-pro");
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -q -pl llm-gateway test -Dtest=ProviderPropertiesTest`
Expected: 编译失败，`ModelMeta` / `modelNames()` 不存在。

- [ ] **Step 3: 改造 ProviderProperties**

修改 `llm-gateway/src/main/java/com/sunshine/llm/config/ProviderProperties.java`：

```java
package com.sunshine.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 厂商配置（映射 application.yml 中 llm.providers.*）
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class ProviderProperties {

    private Map<String, ProviderConfig> providers;

    @Data
    public static class ProviderConfig {
        /** API 地址 */
        private String baseUrl;
        /** API Key */
        private String apiKey;
        /** 支持的模型列表（带上下文窗口元信息） */
        private List<ModelMeta> models;

        /** 模型名列表（供 Adapter.supports 判断）。 */
        public List<String> modelNames() {
            return models != null
                    ? models.stream().map(ModelMeta::getName).toList()
                    : List.of();
        }
    }

    @Data
    public static class ModelMeta {
        private String name;
        private int contextWindow;
        /** tokenizer 编码名，默认 cl100k_base。 */
        private String encoding = "cl100k_base";
    }
}
```

- [ ] **Step 4: 适配两个 Adapter 的 supports**

`DeepSeekAdapter.supports`（`:33-39`）：

```java
    @Override
    public boolean supports(String model) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("deepseek");
        if (config == null) {
            return false;
        }
        return config.modelNames().contains(model) || model.startsWith("deepseek-");
    }
```

`QwenAdapter.supports`（`:41-47`）：

```java
    @Override
    public boolean supports(String model) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("qwen");
        if (config == null) {
            return false;
        }
        return config.modelNames().contains(model) || model.startsWith("qwen-");
    }
```

- [ ] **Step 5: 写失败测试（ModelController）**

创建 `llm-gateway/src/test/java/com/sunshine/llm/controller/ModelControllerTest.java`：

```java
package com.sunshine.llm.controller;

import com.sunshine.llm.config.ProviderProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelControllerTest {

    @Test
    void listModels_aggregatesAllProviders() {
        ProviderProperties props = new ProviderProperties();
        ProviderProperties.ProviderConfig ds = new ProviderProperties.ProviderConfig();
        ProviderProperties.ModelMeta m1 = new ProviderProperties.ModelMeta();
        m1.setName("deepseek-v4-pro");
        m1.setContextWindow(128000);
        m1.setEncoding("cl100k_base");
        ds.setModels(List.of(m1));
        ProviderProperties.ProviderConfig qw = new ProviderProperties.ProviderConfig();
        ProviderProperties.ModelMeta m2 = new ProviderProperties.ModelMeta();
        m2.setName("qwen-plus");
        m2.setContextWindow(131072);
        m2.setEncoding("cl100k_base");
        qw.setModels(List.of(m2));
        props.setProviders(Map.of("deepseek", ds, "qwen", qw));

        ModelController controller = new ModelController(props);
        var resp = controller.listModels();
        assertThat(resp.getObject()).isEqualTo("list");
        assertThat(resp.getData()).hasSize(2);
        assertThat(resp.getData())
                .anySatisfy(d -> {
                    assertThat(d.getId()).isEqualTo("deepseek-v4-pro");
                    assertThat(d.getContextWindow()).isEqualTo(128000);
                })
                .anySatisfy(d -> {
                    assertThat(d.getId()).isEqualTo("qwen-plus");
                    assertThat(d.getContextWindow()).isEqualTo(131072);
                });
    }
}
```

- [ ] **Step 6: 跑测试验证失败**

Run: `mvn -q -pl llm-gateway test -Dtest=ModelControllerTest`
Expected: 编译失败，`ModelController` / `ModelListResponse` 不存在。

- [ ] **Step 7: 实现 ModelListResponse + ModelController**

创建 `llm-gateway/src/main/java/com/sunshine/llm/model/ModelListResponse.java`：

```java
package com.sunshine.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** OpenAI 兼容模型列表响应（含上下文窗口元信息）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelListResponse {

    private String object;
    private List<ModelInfo> data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelInfo {
        private String id;
        @JsonProperty("context_window")
        private Integer contextWindow;
        private String encoding;
    }
}
```

创建 `llm-gateway/src/main/java/com/sunshine/llm/controller/ModelController.java`：

```java
package com.sunshine.llm.controller;

import com.sunshine.llm.config.ProviderProperties;
import com.sunshine.llm.model.ModelListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 模型元信息端点：聚合所有 provider 的模型 + 上下文窗口。 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ModelController {

    private final ProviderProperties providerProperties;

    @GetMapping("/models")
    public ModelListResponse listModels() {
        List<ModelListResponse.ModelInfo> data = new ArrayList<>();
        if (providerProperties.getProviders() != null) {
            providerProperties.getProviders().values().forEach(config -> {
                if (config.getModels() != null) {
                    config.getModels().forEach(m -> data.add(
                            ModelListResponse.ModelInfo.builder()
                                    .id(m.getName())
                                    .contextWindow(m.getContextWindow())
                                    .encoding(m.getEncoding())
                                    .build()));
                }
            });
        }
        return ModelListResponse.builder().object("list").data(data).build();
    }
}
```

- [ ] **Step 8: 跑测试验证通过 + 适配 QwenAdapterTest**

`ProviderConfig.models` 从 `List<String>` 改 `List<ModelMeta>` 后，全仓库只有 `QwenAdapterTest`（`:25` 用 `qwen.setModels(List.of("qwen-plus", "qwen-turbo"))`）会编译失败。`ModelRouterTest`/`ChatControllerTest`/`LlmIoTracerTest`/`OpenAiRequestBodyFactoryTest`/`AdapterCircuitBreakerTest` 均不引用 `getModels`/`setModels`（已 grep 确认），无需改。

适配 `QwenAdapterTest.setUp()`：

```java
        ProviderProperties.ModelMeta plus = new ProviderProperties.ModelMeta();
        plus.setName("qwen-plus");
        ProviderProperties.ModelMeta turbo = new ProviderProperties.ModelMeta();
        turbo.setName("qwen-turbo");
        qwen.setModels(List.of(plus, turbo));
```

Run: `mvn -q -pl llm-gateway test -Dtest='ProviderPropertiesTest,ModelControllerTest,QwenAdapterTest,ModelRouterTest,ChatControllerTest'`
Expected: PASS。

- [ ] **Step 9: 全模块编译验证**

Run: `mvn -q -pl llm-gateway -am test-compile`
Expected: 编译成功。

- [ ] **Step 10: orchestrator 侧消费 /v1/models（ModelWindowCache 加拉取）**

在 `ModelWindowCache`（Task 3 创建）加 Gateway 拉取方法。注入 WebClient 用与 `LlmGatewayClient` 相同的 baseUrl 模式：

```java
    private final com.sunshine.orchestrator.context.ContextProperties contextProperties;
    private final java.util.concurrent.atomic.AtomicReference<Map<String, Integer>> windows =
            new java.util.concurrent.atomic.AtomicReference<>(Map.of());

    @org.springframework.beans.factory.annotation.Value("${agent.model.base-url:http://127.0.0.1:8300/v1}")
    private String gatewayBaseUrl;

    private org.springframework.web.reactive.function.client.WebClient webClient;

    public ModelWindowCache(ContextProperties contextProperties) {
        this.contextProperties = contextProperties;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.webClient = org.springframework.web.reactive.function.client.WebClient.builder()
                .baseUrl(gatewayBaseUrl)
                .build();
        refreshFromGateway();
    }

    /** 启动/刷新时从 Gateway /v1/models 拉取模型窗口；失败保留旧缓存（降级 defaultModelWindow）。 */
    public void refreshFromGateway() {
        try {
            ModelListDto resp = webClient.get()
                    .uri("/models")
                    .retrieve()
                    .bodyToMono(ModelListDto.class)
                    .block(java.time.Duration.ofSeconds(5));
            if (resp != null && resp.data() != null && !resp.data().isEmpty()) {
                Map<String, Integer> map = new java.util.HashMap<>();
                for (ModelInfoDto d : resp.data()) {
                    if (d.id() != null && d.contextWindow() != null && d.contextWindow() > 0) {
                        map.put(d.id(), d.contextWindow());
                    }
                }
                if (!map.isEmpty()) {
                    refresh(map);
                }
            }
        } catch (Exception e) {
            // Gateway 不可用：保留旧缓存，windowFor 降级 defaultModelWindow
            org.slf4j.LoggerFactory.getLogger(ModelWindowCache.class)
                    .warn("[ModelWindowCache] refresh 失败，降级默认窗口: {}", e.getMessage());
        }
    }

    record ModelListDto(String object, List<ModelInfoDto> data) {
    }

    record ModelInfoDto(String id,
                        @com.fasterxml.jackson.annotation.JsonProperty("context_window") Integer contextWindow,
                        String encoding) {
    }
```

注意：`ModelWindowCache` 构造器原本只注入 `ContextProperties`，保持单参构造（`@Value` 字段注入，WebClient 在 `@PostConstruct` 建）。Task 3 的测试用 `new ModelWindowCache(properties)` 仍兼容（单参构造保留）。

- [ ] **Step 11: orchestrator 编译验证**

Run: `mvn -q -pl orchestrator -am test-compile`
Expected: 编译成功。

- [ ] **Step 12: 任务完成标记**

Gateway 暴露 `/v1/models` + 模型上下文窗口；orchestrator `ModelWindowCache` 启动拉取 + 降级。Adapter `supports` 适配新配置类型。

---

### Task 10: Nacos 配置变更（orchestrator context.* + llm-gateway providers.models）

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`agent.context.*`，`:264-292`）
- Modify: `docs/nacos/sunshine-llm-gateway.yaml`（`llm.providers.*.models`，`:22-38`）

**Interfaces:**
- Consumes: Task 2（ContextProperties 新字段）、Task 9（ProviderProperties.ModelMeta）
- Produces: Nacos 配置 SSOT 与新代码字段对齐

- [ ] **Step 1: 改 orchestrator context 配置**

修改 `docs/nacos/sunshine-orchestrator.yaml` 的 `agent.context` 段（`:264-292`），整体替换为：

```yaml
  context:
    enabled: true
    l1:
      # near/mid 按「问答轮次」计（一轮 = 1 次 user + 其后 assistant），非消息条数
      near-turns: 8
      mid-turns: 8
      # 压缩触发：真实 token 达模型上下文窗口 80% 才触发；轮次为宽限兜底
      max-tokens-ratio: 0.8
      turn-backstop: 40
      default-model-window: 128000
      token-safety-factor: 1.1
      mid-compress-ratio: 0.15
    l2:
      min-confidence: 0.75
      constraint-overwrite-confidence: 0.9
      preference-ttl-days: 365
      agreement-ttl-days: 365
      goal-ttl-days: 90
      decision-ttl-days: 90
      fact-ttl-days: 30
      constraint-ttl-days: 30
      # 过程记忆分级置信 + TTL
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
      superseded-retention-days: 180
      void-retention-days: 30
      audit-enabled: true
      audit-on-extract: true
      audit-max-users-per-tick: 50
      audit-extract-debounce-ms: 30000
```

注意：删除了 `l1.max-chars`（Task 2 代码里 `maxChars` 保留为 deprecated，Nacos 不再配置）；`l3.top-k` 5→8、`min-score` 0.55→0.45。

- [ ] **Step 2: 改 llm-gateway providers.models 配置**

修改 `docs/nacos/sunshine-llm-gateway.yaml` 的 `llm.providers` 段（`:22-38`），`models` 从字符串列表升级为对象列表：

```yaml
  providers:
    deepseek:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY:sk-1e18dbd6edd4477bac49a0a290e92462}
      models:
        - name: deepseek-v4-pro
          context-window: 128000
          encoding: cl100k_base
        - name: deepseek-v4-flash
          context-window: 64000
          encoding: cl100k_base
    qwen:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key: ${QWEN_API_KEY:sk-22e7ab6f7bbb4078b7163facb4d3aee0}
      models:
        - name: qwen-plus
          context-window: 131072
          encoding: cl100k_base
        - name: qwen-max
          context-window: 32768
          encoding: cl100k_base
```

- [ ] **Step 3: 配置同步（执行时）**

Run: `python scripts/sync_nacos.py`
Expected: 两个配置文件同步到 Nacos 成功。

**注意**：`context.window` 值（128000/64000/131072/32768）为占位估算，执行时需按实际模型真实上下文窗口核实修正（deepseek-v4 / qwen 官方文档）。

- [ ] **Step 4: 任务完成标记**

Nacos 配置与新代码字段对齐。重启消费服务（orchestrator + llm-gateway）后生效。

---

### Task 11: 收尾清理 + 既有验收回归

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextProperties.java`（删除 deprecated maxChars）
- Modify: 所有残留 `getMaxChars` 引用
- Create: `scripts/verify_dynamic_context_live.py`（Live 验收）

**Interfaces:**
- Consumes: Task 2-10 全部
- Produces: 无 deprecated 残留；Live 验收脚本

- [ ] **Step 1: 清理 deprecated maxChars**

删除 `ContextProperties.L1.maxChars` 字段（Task 2 标记的 `@Deprecated`）。全局搜索 `getMaxChars` / `maxChars` / `max-chars` 确认无残留引用（Nacos yaml 已在 Task 10 删除）。

Run: `grep -rn "getMaxChars\|maxChars\|max-chars" orchestrator/src docs/nacos/`
Expected: 无匹配。

- [ ] **Step 2: 全量编译 + 全量单测**

Run: `mvn -q -pl orchestrator,llm-gateway -am test-compile`
Expected: 编译成功。

Run: `mvn -q -pl orchestrator test`
Expected: 全部单测 PASS。

Run: `mvn -q -pl llm-gateway test`
Expected: 全部单测 PASS。

- [ ] **Step 3: 既有验收回归**

Run: `python scripts/verify_context_layers_live.py`
Expected: 上下文 L1/L2/L3 Admin + 单测门禁全绿，不破坏现有行为。

- [ ] **Step 4: 新增 Live 验收脚本**

创建 `scripts/verify_dynamic_context_live.py`，覆盖设计 §9 的 T1-T10：

- T1 短对话不压缩（token 未到 80%，`conversation_context_l1` 无新写入）
- T2 长对话触发压缩（token 超 80% 后才写 mid_answers/far_summary）
- T3 轮次宽限兜底（45 轮极短消息触发）
- T4 自适应降级（nearRounds 缩小，Mid 吸收溢出）
- T5 Gateway 降级（Gateway 不可用时用 default-model-window）
- T6 新 kind 抽取（reasoning/option/interim_conclusion/topic）
- T7 分级置信（低置信 reasoning 丢弃，同值 interim_conclusion 保留）
- T8 召回增强（topK=8 + minScore=0.45）
- T9 半衰期（90 天 vs 30 天）
- T10 Far 降权（Far msgId 命中 score × 0.5）

参考既有 `scripts/verify_context_layers_live.py` 的 Admin API 调用模式与断言结构。

- [ ] **Step 5: 重启服务 + Live 验收**

Run: `python scripts/start.py`（或按 README §快速开始重启 orchestrator + llm-gateway）
Expected: 服务启动成功。

Run: `python scripts/verify_dynamic_context_live.py`
Expected: T1-T10 全绿。

- [ ] **Step 6: 任务完成标记**

deprecated 清理完成，既有验收回归通过，新 Live 验收 T1-T10 全绿。改造收口。









