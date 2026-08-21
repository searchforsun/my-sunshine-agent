# ReAct 实时轮次 / Context Token / 输入输出 Token 显示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 前端实时显示对话轮次、输入/输出 token、上下文占用百分比与分组构成（对标 Claude Code / DeepSeek Harness），刷新可恢复。

**Architecture:** llm-gateway 透传 `stream_options` → AgentScope SDK 流式末 chunk usage → `ModelCallEndEvent` 经 `ReActAgentRuntime` 采集累计 → 新增 SSE `type=usage` 帧（经既有 hookQueue/Redis Stream 通道）→ 前端 composer 状态栏 + 分组面板；终态随 `chat_message.usage_json` 落库恢复。

**Tech Stack:** Java 21 / Spring Boot（AgentScope 2.0 SDK `io.agentscope.*`）· Vue3 + Naive UI + Pinia · MySQL · Redis Stream SSE。

**Spec:** `docs/superpowers/specs/2026-08-17-react-usage-context-display-design.md`

## Global Constraints

- 禁止补丁式修改；禁止对模型输出做截断/摘要/过滤兜底。
- 代码加适量中文注释，仅解释「为什么」；禁止业务代码多余空行。
- UI：背景 `--sun-black` + 边框分区；禁止解释性说明文字，仅组名 + 数值。
- 库表 SQL SSOT 在 `docker/mysql/init/`（禁止 Flyway）；种子 SQL 全量策略。
- 总额/百分比/裁剪一律用网关真实 usage；jtokkit 估算仅用于分组展示（标 ~）。
- 前端禁止任何 token 估算逻辑。
- 运维统一 `scripts/*.py`；改后端必须重启对应 `start.py` 服务。
- 禁止 agent 自测前端业务功能；前端验收给出人工步骤。
- 提交信息用中文短句（跟随仓库风格，如 `feat(ui): …`）。

---

### Task 1: llm-gateway 透传 stream_options

**Files:**
- Modify: `llm-gateway/src/main/java/com/sunshine/llm/model/ChatCompletionRequest.java`
- Modify: `llm-gateway/src/main/java/com/sunshine/llm/adapter/OpenAiRequestBodyFactory.java`
- Test: `llm-gateway/src/test/java/com/sunshine/llm/adapter/OpenAiRequestBodyFactoryTest.java`

**Interfaces:**
- Produces: `ChatCompletionRequest.getStreamOptions(): Map<String,Object>`；`OpenAiRequestBodyFactory.build` 流式分支将非空 `stream_options` 写入上游 body。

- [ ] **Step 1: 写失败测试**

在 `OpenAiRequestBodyFactoryTest` 追加：

```java
    @Test
    void build_streamPassesThroughStreamOptions() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("deepseek-v4-pro");
        request.setStreamOptions(Map.of("include_usage", true));

        Map<String, Object> body = factory.build(request, true);

        assertThat(body.get("stream_options")).isEqualTo(Map.of("include_usage", true));
    }

    @Test
    void build_nonStreamOmitsStreamOptions() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("deepseek-v4-pro");
        request.setStreamOptions(Map.of("include_usage", true));

        Map<String, Object> body = factory.build(request, false);

        assertThat(body).doesNotContainKey("stream_options");
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl llm-gateway test -Dtest=OpenAiRequestBodyFactoryTest`
Expected: 编译失败（`setStreamOptions` 不存在）。

- [ ] **Step 3: ChatCompletionRequest 增加字段**

在 `fallback_model` 字段后追加：

```java
    /** 流式 usage 透传（AgentScope 自动携带 include_usage）；非流式不转发 */
    @JsonProperty("stream_options")
    private Map<String, Object> streamOptions;
```

并在 `copyWithModel` 中追加 `copy.setStreamOptions(this.streamOptions);`（降级链保留）。

- [ ] **Step 4: OpenAiRequestBodyFactory 流式分支写入**

在 `build(...)` 的 `body.put("stream", stream);` 后追加：

```java
        if (stream && request != null && request.getStreamOptions() != null
                && !request.getStreamOptions().isEmpty()) {
            body.put("stream_options", request.getStreamOptions());
        } else {
            body.remove("stream_options");
        }
```

（`objectMapper.convertValue` 已把字段带入 body；此处保证非流式必移除、流式必保留。）

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q -pl llm-gateway test -Dtest=OpenAiRequestBodyFactoryTest`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add llm-gateway/src/main/java/com/sunshine/llm/model/ChatCompletionRequest.java \
        llm-gateway/src/main/java/com/sunshine/llm/adapter/OpenAiRequestBodyFactory.java \
        llm-gateway/src/test/java/com/sunshine/llm/adapter/OpenAiRequestBodyFactoryTest.java
git commit -m "feat(llm-gateway): 透传 stream_options 打通流式 usage 链路"
```

---

### Task 2: StreamToken KIND_USAGE + splitter 透传

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/client/StreamToken.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/client/StreamChunkSplitter.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/client/StreamChunkSplitterUsageTest.java`（新建）

**Interfaces:**
- Produces: `StreamToken.KIND_USAGE="usage"`、`StreamToken.usage(String json)`、`isUsage()`；splitter 对 usage token 原样透传。

- [ ] **Step 1: 写失败测试**

新建 `StreamChunkSplitterUsageTest`：

```java
package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamChunkSplitterUsageTest {

    @Test
    void usageTokenPassesThroughUnsplit() {
        String json = "{\"type\":\"usage\",\"callSeq\":1,\"inputTokens\":10}";
        StreamToken token = StreamToken.usage(json);

        List<StreamToken> parts = StreamChunkSplitter.splitToken(token, 4);

        assertThat(parts).containsExactly(token);
        assertThat(token.isUsage()).isTrue();
        assertThat(token.text()).isEqualTo(json);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl orchestrator test -Dtest=StreamChunkSplitterUsageTest`
Expected: 编译失败（`usage` 工厂不存在）。

- [ ] **Step 3: StreamToken 增加 usage kind**

在 `KIND_SANDBOX_SESSION` 常量后追加：

```java
    public static final String KIND_USAGE = "usage";
```

在 `sandboxSession` 工厂后追加：

```java
    /** LLM usage 计量帧 — text 承载 wire JSON（metaUsage 原样下发，不写正文） */
    public static StreamToken usage(String usageJson) {
        return new StreamToken(KIND_USAGE, usageJson, null, null, null, null);
    }
```

在 `isSandboxSession()` 后追加：

```java
    public boolean isUsage() {
        return KIND_USAGE.equals(kind);
    }
```

- [ ] **Step 4: splitter 透传**

`StreamChunkSplitter.splitToken` 首个 if 改为：

```java
        if (token.isStep() || token.isContentStart() || token.isContentEnd()
                || token.isSandboxSession() || token.isUsage()) {
            return List.of(token);
        }
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q -pl orchestrator test -Dtest=StreamChunkSplitterUsageTest`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/client/StreamToken.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/client/StreamChunkSplitter.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/client/StreamChunkSplitterUsageTest.java
git commit -m "feat(orchestrator): StreamToken 新增 usage kind 并透传 splitter"
```

---

### Task 3: ContextGroupEstimator 分组估算器

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextGroupEstimator.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/ContextGroupEstimatorTest.java`

**Interfaces:**
- Consumes: `TokenEstimator.count(String)`、SDK `Msg.getTextContent()`、`ToolSchema.getName()/getDescription()/getParameters()`。
- Produces: `estimateText(String): int`、`estimateMessages(List<Msg>): int`、`estimateTools(List<ToolSchema>): int`。

- [ ] **Step 1: 写失败测试**

```java
package com.sunshine.orchestrator.context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextGroupEstimatorTest {

    private final ContextGroupEstimator estimator = new ContextGroupEstimator(new TokenEstimator());

    @Test
    void estimateMessagesSumsTextBlocks() {
        Msg a = Msg.builder().role(MsgRole.USER).textContent("你好世界").build();
        Msg b = Msg.builder().role(MsgRole.ASSISTANT).textContent("hello world").build();
        int expected = estimator.estimateText("你好世界") + estimator.estimateText("hello world");

        assertThat(estimator.estimateMessages(List.of(a, b))).isEqualTo(expected);
    }

    @Test
    void estimateToolsCoversNameDescriptionParameters() {
        ToolSchema tool = ToolSchema.builder()
                .name("search_knowledge")
                .description("检索知识库")
                .parameters(Map.of("type", "object"))
                .build();

        int expected = estimator.estimateText("search_knowledge")
                + estimator.estimateText("检索知识库")
                + estimator.estimateText("{\"type\":\"object\"}");

        assertThat(estimator.estimateTools(List.of(tool))).isEqualTo(expected);
    }

    @Test
    void estimateNullSafe() {
        assertThat(estimator.estimateText(null)).isZero();
        assertThat(estimator.estimateMessages(null)).isZero();
        assertThat(estimator.estimateTools(null)).isZero();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl orchestrator test -Dtest=ContextGroupEstimatorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package com.sunshine.orchestrator.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文分组构成展示估算（仅展示，标 ~）：总额/裁剪仍以网关真实 usage 为准。
 * 工具 schema 以 JSON 序列化文本估算，与上游分词偏差归入「其他」残差组。
 */
@Component
@RequiredArgsConstructor
public class ContextGroupEstimator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TokenEstimator tokenEstimator;

    public int estimateText(String text) {
        return tokenEstimator.count(text);
    }

    public int estimateMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Msg m : messages) {
            if (m != null) {
                total += tokenEstimator.count(m.getTextContent());
            }
        }
        return total;
    }

    public int estimateTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ToolSchema t : tools) {
            if (t == null) {
                continue;
            }
            total += tokenEstimator.count(t.getName());
            total += tokenEstimator.count(t.getDescription());
            total += tokenEstimator.count(writeJson(t.getParameters()));
        }
        return total;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl orchestrator test -Dtest=ContextGroupEstimatorTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextGroupEstimator.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/context/ContextGroupEstimatorTest.java
git commit -m "feat(orchestrator): 上下文分组估算器（展示用）"
```

---

### Task 4: PromptComposer 静态层分组记录

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/prompt/ComposedReactInputs.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/prompt/PromptComposer.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java`（composeSystemPrompt 抽离为 Resolver）
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActSystemPromptResolver.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/prompt/PromptComposerTest.java`（追加用例）

**Interfaces:**
- Produces: `ComposedReactInputs(List<Msg> inputs, Map<String,Integer> staticGroups)`，groups 键固定 `system/rules/skills/contextLayers`；`PromptComposer.composeReactInputs(PromptComposeRequest, String baseSystemPrompt)`；`ReActSystemPromptResolver.resolve(AgentRunRequest): String`（factory 委托）。

- [ ] **Step 1: 写失败测试**

`PromptComposerTest.setUp` 构造器同步改为 4 参（`PromptComposer` 为 `@RequiredArgsConstructor`，新增 `ContextGroupEstimator` 依赖）：

```java
        composer = new PromptComposer(catalogHolder, skillCatalogService, hitlProperties,
                new ContextGroupEstimator(new TokenEstimator()));
```

在 `PromptComposerTest` 追加：

```java
    @Test
    void composeReactInputs_recordsStaticGroupTokens() {
        // 既有 fixture：system-prompt / scope-prompt / mode-overlay.react 等 entry 已注入
        ComposedReactInputs composed = composer.composeReactInputs(
                PromptComposeRequest.forReact(AssembledContext.empty(), "问题", List.of()),
                "BASE_SYSTEM");

        assertThat(composed.inputs()).isNotEmpty();
        Map<String, Integer> groups = composed.staticGroups();
        assertThat(groups.get("system")).isPositive();
        assertThat(groups).containsKeys("rules", "skills", "contextLayers");
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl orchestrator test -Dtest=PromptComposerTest`
Expected: 编译失败（方法签名不存在）。

- [ ] **Step 3: 新建 ComposedReactInputs**

```java
package com.sunshine.orchestrator.prompt;

import io.agentscope.core.message.Msg;

import java.util.List;
import java.util.Map;

/** ReAct 输入 + 静态层分组 token 快照（键：system/rules/skills/contextLayers；仅展示用） */
public record ComposedReactInputs(List<Msg> inputs, Map<String, Integer> staticGroups) {
}
```

- [ ] **Step 4: 新建 ReActSystemPromptResolver 并让 factory 委托**

```java
package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** base-system 解析 SSOT：factory 构建 agent 与 runtime 分组估算共用，避免双份逻辑漂移 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActSystemPromptResolver {

    private final PromptCatalogHolder catalogHolder;

    public String resolve(AgentRunRequest request) {
        String base = catalogHolder.snapshot().entry("system-prompt")
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElseGet(() -> {
                    log.warn("[ReActSystemPromptResolver] catalog missing id=system-prompt");
                    return "";
                });
        String overlay = request != null ? request.systemOverlay() : null;
        if (!StringUtils.hasText(overlay)) {
            return base;
        }
        String trimmed = overlay.strip();
        if (base.isBlank()) {
            return trimmed;
        }
        return base + "\n\n" + trimmed;
    }
}
```

`ReActAgentFactory`：注入 `ReActSystemPromptResolver systemPromptResolver`，`composeSystemPrompt` 方法体改为 `return systemPromptResolver.resolve(request);`（保留 public 签名，删除原 catalog 逻辑）。

- [ ] **Step 5: PromptComposer 记录分组**

`composeReactInputs` 改签名为 `composeReactInputs(PromptComposeRequest request, String baseSystemPrompt)`，内部在 `appendCommonReactLayers` 中按层累计。将 `appendCommonReactLayers` 改为接收 `Map<String,Integer> groups` 并在各 `addReactUser` 调用处累加：

```java
    public ComposedReactInputs composeReactInputs(PromptComposeRequest request, String baseSystemPrompt) {
        List<Msg> inputs = new ArrayList<>();
        Map<String, Integer> groups = new LinkedHashMap<>();
        appendCommonReactLayers(inputs, request, false, groups, baseSystemPrompt);
        appendReactTail(inputs, request);
        return new ComposedReactInputs(inputs, groups);
    }
```

`appendCommonReactLayers` 内每个层追加后记录（示例，按现有顺序逐层补齐）：

```java
        if (includeBaseSystem) {
            addReactUser(inputs, catalogText("system-prompt"));
        }
        groups.merge("system", estimator.estimateText(baseSystemPrompt)
                + estimator.estimateText(catalogText("scope-prompt"))
                + estimator.estimateText(nodePromptOrEmpty(request.nodePrompt())), Integer::sum);
        String modeOverlay = resolveModeOverlay(request.mode(), request.workflowId());
        addReactUser(inputs, modeOverlay);
        String rules = PersonalRulesSupport.wrap(request.personalRules());
        addReactUser(inputs, rules);
        groups.merge("rules", estimator.estimateText(rules), Integer::sum);
        // skills 组：mode/harness/restart/hitl/skill/scene/workspace overlay 合计
        int skillsTokens = estimator.estimateText(modeOverlay)
                + estimator.estimateText(resolveHarnessOverlay(request.harnessPromptId()))
                + estimator.estimateText(resolveReactRestartOverlay(request))
                + estimator.estimateText(resolveHitlOverlay(request.mode()))
                + estimator.estimateText(resolveSkillOverlay(request.skillId()))
                + estimator.estimateText(resolveSceneOverlay(request.kind()))
                + estimator.estimateText(resolveWorkspaceCheckoutOverlay(request.workspaceCheckout()));
        groups.merge("skills", skillsTokens, Integer::sum);
```

context 层在 `appendReactContextLayers` 记录：

```java
    private void appendReactContextLayers(List<Msg> inputs, AssembledContext ctx, Map<String, Integer> groups) {
        List<Map<String, Object>> layers = new ArrayList<>();
        ContextMessageBuilder.appendAll(
                layers, ctx, catalogText("context.layer-prompt"), catalogText("context.usage-rules"));
        int tokens = 0;
        for (Map<String, Object> msg : layers) {
            String content = String.valueOf(msg.get("content"));
            tokens += estimator.estimateText(content);
            // ... 既有 role 转换与 add 逻辑不变 ...
        }
        groups.merge("contextLayers", tokens, Integer::sum);
    }
```

注入 `ContextGroupEstimator estimator`（构造器，`PromptComposer` 为 `@RequiredArgsConstructor`）。注意：原 `composeReactInputs(PromptComposeRequest)` 单参签名删除，调用方（`ReActAgentRuntime`）在 Task 6 同步更新；`scope-prompt`/`nodePrompt` 的 `addReactUser` 调用保持原位不重复添加。

- [ ] **Step 6: 运行确认通过**

Run: `mvn -q -pl orchestrator test -Dtest='PromptComposerTest,ReActAgentFactoryTest'`
Expected: PASS（factory 测试若 mock composeSystemPrompt 行为不变即通过）。

- [ ] **Step 7: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/prompt/ \
        orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActSystemPromptResolver.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/prompt/PromptComposerTest.java
git commit -m "feat(orchestrator): PromptComposer 记录静态层分组 token"
```

---

### Task 5: Middleware 动态组（对话消息/工具定义）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java`

**Interfaces:**
- Consumes: `ContextGroupEstimator`（经构造器，`ProcessingStepMiddlewareFactory` 同步注入）、`ModelCallInput.messages()/tools()`。
- Produces: RuntimeContext key `CTX_CONTEXT_GROUPS = "sunshine.contextGroups"`，值 `Map<String,Integer>`（含静态组 + messages/tools/other 占位 0）。

- [ ] **Step 1: 常量与依赖**

`ProcessingStepMiddleware` 增加：

```java
    /** RuntimeContext key：per-call 上下文分组 token 快照（静态组由 composer 记录，动态组 onModelCall 补齐） */
    public static final String CTX_CONTEXT_GROUPS = "sunshine.contextGroups";
```

构造器追加 `ContextGroupEstimator contextGroupEstimator` 字段；`ProcessingStepMiddlewareFactory` 追加字段 `private final ContextGroupEstimator contextGroupEstimator;`，`shared()` 内 `new ProcessingStepMiddleware(...)` 参数列表末尾追加 `contextGroupEstimator`（与 middleware 构造器顺序一致）。

- [ ] **Step 2: onModelCall 记录动态组**

在 `onModelCall` 的 `return next.apply(...)` 前（最终 messages 确定后）追加：

```java
        recordContextGroups(ctx, messages, input.tools());
```

新增私有方法：

```java
    /** 分组仅展示：messages 组 = 全量入参估算 − 静态组（composer 层已在静态组计过，避免双计） */
    private void recordContextGroups(RuntimeContext ctx, List<Msg> messages, List<ToolSchema> tools) {
        if (ctx == null) {
            return;
        }
        Map<String, Integer> groups = ctx.get(CTX_CONTEXT_GROUPS);
        if (groups == null) {
            return;
        }
        int staticSum = groups.values().stream().mapToInt(Integer::intValue).sum();
        int all = contextGroupEstimator.estimateMessages(messages);
        groups.put("messages", Math.max(0, all - staticSum));
        groups.put("tools", contextGroupEstimator.estimateTools(tools));
    }
```

- [ ] **Step 3: 编译验证**

Run: `mvn -q -pl orchestrator compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddlewareFactory.java
git commit -m "feat(orchestrator): onModelCall 记录动态上下文分组"
```

---

### Task 6: Runtime 采集 usage + 分组快照 + SSE 帧

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridge.java` + `StepEventBridgeRegistry.java`（groups 静态组绑定）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntimeTest.java`

**Interfaces:**
- Consumes: `ModelCallEndEvent.getUsage()`（`ChatUsage.getInputTokens()/getOutputTokens()/getCachedTokens()`）、`ModelWindowCache.windowFor(String)`（失败置 null）、`ComposedReactInputs`。
- Produces: `StreamToken.usage(json)` 经 `StepEventBridge.offerStreamToken(bridgeId, token)` 入 hookQueue；wire JSON 结构同 spec §3.2。

- [ ] **Step 1: 写失败测试**

`ReActAgentRuntimeTest` 追加（沿用既有 mock 装配；`agentHolder.get` 返回 mock agent，`streamEvents` 返回含 `ModelCallEndEvent` 的 Flux）：

```java
    @Test
    void modelCallEndEvent_emitsUsageToken() {
        Msg userMsg = Msg.builder().role(MsgRole.USER).content(List.of()).build();
        when(promptComposer.composeReactInputs(any(), any()))
                .thenReturn(new ComposedReactInputs(List.of(userMsg), Map.of("system", 10)));
        when(agentHolder.get(any())).thenReturn(reactAgent);
        when(reactAgent.streamEvents(anyList(), any()))
                .thenReturn(Flux.just(new ModelCallEndEvent("reply-1",
                        new ChatUsage(100, 50, 20, 1.0))));

        AgentRunRequest req = AgentRunRequest.main(
                AssembledContext.empty(), "q", "u1", "default", "msg-usage");
        List<StreamToken> tokens = runtime.run(req).collectList().block();

        StreamToken usageToken = tokens.stream()
                .filter(StreamToken::isUsage).findFirst().orElse(null);
        assertThat(usageToken).isNotNull();
        assertThat(usageToken.text()).contains("\"callSeq\":1")
                .contains("\"inputTokens\":100").contains("\"outputTokens\":50")
                .contains("\"llmCalls\":1");
    }
```

（`ChatUsage` 4 参构造器 `ChatUsage(int inputTokens, int outputTokens, int cachedTokens, double time)` 已确认存在。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl orchestrator test -Dtest=ReActAgentRuntimeTest`
Expected: FAIL（无 usage 帧）。

- [ ] **Step 3: RuntimeContext 注入静态组**

`startReActStream` 中 `composeReactInputs` 调用改为（注入 `ReActSystemPromptResolver`、`ModelWindowCache`、`ChatMessageRepository` 三个新依赖）：

```java
            ComposedReactInputs composed = promptComposer.composeReactInputs(
                    request.role() == AgentRole.PLANNER
                            ? PromptComposeRequest.forPlannerHarness(...)
                            : PromptComposeRequest.forReact(...),
                    systemPromptResolver.resolve(request));
            List<Msg> inputs = composed.inputs();
            Map<String, Integer> contextGroups = new ConcurrentHashMap<>(composed.staticGroups());
```

`RuntimeContext` 构建追加 `.put(ProcessingStepMiddleware.CTX_CONTEXT_GROUPS, contextGroups)`。

- [ ] **Step 4: 累计器与 usage 帧**

runtime 内新增私有 record 与方法：

```java
    /** 消息级 usage 累计（续跑从落库 usage_json 起算）；package-visible 供 UsageJsonSupport 引用 */
    record UsageAccumulator(long inputTokens, long outputTokens, int llmCalls) {
    }
```

`startReActStream` 内（bridge 绑定后）初始化：

```java
            UsageAccumulator seed = seedUsageFromPersisted(assistantMessageId);
            AtomicReference<UsageAccumulator> usageAcc = new AtomicReference<>(seed);
            String resolvedModel = resolveModelName(request);
```

模型名解析（注入 `ModelSceneResolver modelSceneResolver`，与 `ReActAgentFactory.resolveModel` 同语义）：

```java
    private String resolveModelName(AgentRunRequest request) {
        try {
            AgentRole role = request.role();
            if (role == AgentRole.MAIN) {
                return modelSceneResolver.resolveChat(request.modelOverride()).effectiveModel();
            }
            if (role == AgentRole.SUB || role == AgentRole.WORKER) {
                return modelSceneResolver.resolve(ModelSceneKey.SUBAGENT.key(), request.modelOverride()).effectiveModel();
            }
            return modelSceneResolver.resolve(ModelSceneKey.PLANNER.key(), request.modelOverride()).effectiveModel();
        } catch (Exception e) {
            return null;
        }
    }
```

（`ModelSceneKey` import 自 `com.sunshine.orchestrator.registry`，与 factory 一致。）

`seedUsageFromPersisted` 完整实现（注入 `ChatMessageRepository messageRepo`）：

```java
    private UsageAccumulator seedUsageFromPersisted(String assistantMessageId) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return new UsageAccumulator(0, 0, 0);
        }
        try {
            return messageRepo.findById(assistantMessageId)
                    .map(m -> UsageJsonSupport.parseAccumulator(m.getUsageJson()))
                    .orElse(new UsageAccumulator(0, 0, 0));
        } catch (Exception e) {
            log.warn("[AgentRuntime] seedUsageFromPersisted failed msg={}: {}", assistantMessageId, e.getMessage());
            return new UsageAccumulator(0, 0, 0);
        }
    }
```

新建 `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/UsageJsonSupport.java`（wire JSON 构造/解析 SSOT，字段严格按 spec §3.2）：

```java
package com.sunshine.orchestrator.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ChatUsage;

import java.util.LinkedHashMap;
import java.util.Map;

/** usage SSE 帧 / usage_json 落库的 JSON SSOT（spec §3.2） */
public final class UsageJsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UsageJsonSupport() {
    }

    public static String buildUsageWire(
            int callSeq, ChatUsage usage,
            ReActAgentRuntime.UsageAccumulator acc,
            Integer contextWindow, Map<String, Integer> groups) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "usage");
        map.put("callSeq", callSeq);
        map.put("inputTokens", usage.getInputTokens());
        map.put("outputTokens", usage.getOutputTokens());
        map.put("cachedTokens", usage.getCachedTokens());
        long contextTokens = usage.getInputTokens() + usage.getOutputTokens();
        map.put("contextTokens", contextTokens);
        if (contextWindow != null && contextWindow > 0) {
            map.put("contextWindowTokens", contextWindow);
            map.put("contextPercent", Math.round(100.0 * contextTokens / contextWindow));
        }
        Map<String, Object> messageUsage = new LinkedHashMap<>();
        messageUsage.put("inputTokens", acc.inputTokens());
        messageUsage.put("outputTokens", acc.outputTokens());
        messageUsage.put("llmCalls", acc.llmCalls());
        map.put("messageUsage", messageUsage);
        if (groups != null && !groups.isEmpty()) {
            map.put("groups", groups);
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"usage\",\"callSeq\":" + callSeq + "}";
        }
    }

    /** 续跑起算：从落库 usage_json 的 messageUsage 恢复累计 */
    public static ReActAgentRuntime.UsageAccumulator parseAccumulator(String usageJson) {
        if (usageJson == null || usageJson.isBlank()) {
            return new ReActAgentRuntime.UsageAccumulator(0, 0, 0);
        }
        try {
            JsonNode root = MAPPER.readTree(usageJson);
            JsonNode mu = root.has("messageUsage") ? root.get("messageUsage") : root;
            return new ReActAgentRuntime.UsageAccumulator(
                    mu.path("inputTokens").asLong(0),
                    mu.path("outputTokens").asLong(0),
                    mu.path("llmCalls").asInt(0));
        } catch (Exception e) {
            return new ReActAgentRuntime.UsageAccumulator(0, 0, 0);
        }
    }
}
```

（`UsageAccumulator` record 声明为 `ReActAgentRuntime` 的 package-visible 嵌套类型，便于 SSOT 引用。）

- [ ] **Step 5: routeDeltaToBridge 增加 ModelCallEndEvent 分支**

```java
        } else if (ev instanceof ModelCallEndEvent end) {
            emitUsageToken(end, bridgeId, usageAcc, groupsSnapshot, resolvedModel);
        }
```

`routeDeltaToBridge` 签名追加参数（`usageAcc`、`groupsSnapshot`、`resolvedModel`），调用处同步；实现：

```java
    private void emitUsageToken(ModelCallEndEvent end, String bridgeId,
            AtomicReference<UsageAccumulator> usageAcc,
            Map<String, Integer> groups, String modelName) {
        ChatUsage usage = end.getUsage();
        if (usage == null) {
            return;
        }
        UsageAccumulator next = usageAcc.updateAndGet(acc -> new UsageAccumulator(
                acc.inputTokens() + usage.getInputTokens(),
                acc.outputTokens() + usage.getOutputTokens(),
                acc.llmCalls() + 1));
        Integer window = resolveContextWindow(modelName);
        StepEventBridge.offerStreamToken(bridgeId, StreamToken.usage(
                UsageJsonSupport.buildUsageWire(next.llmCalls(), usage, next, window, groups)));
    }

    private Integer resolveContextWindow(String modelName) {
        if (modelName == null) {
            return null;
        }
        try {
            return modelWindowCache.windowFor(modelName);
        } catch (Exception e) {
            return null;
        }
    }
```

import `io.agentscope.core.event.ModelCallEndEvent`、`io.agentscope.core.model.ChatUsage`。

- [ ] **Step 6: 运行确认通过**

Run: `mvn -q -pl orchestrator test -Dtest=ReActAgentRuntimeTest`
Expected: PASS（既有用例需同步：① `composeReactInputs(any())` mock 改为 `composeReactInputs(any(), any())` 返回 `ComposedReactInputs`；② `setUp` 构造器补 `@Mock ReActSystemPromptResolver`（`when(...resolve(any())).thenReturn("SYS")`）、`@Mock ModelWindowCache`（`when(...windowFor(any())).thenReturn(128000)`）、`@Mock ChatMessageRepository`、`@Mock ModelSceneResolver`（`when(...resolveChat(any())).thenReturn(new ResolvedModelScene("m", null, null, 128000, 0, null, false))`，7 参 record 签名：effectiveModel/fallbackModel/extras/contextWindow/maxOutputTokens/capabilities/overrideInvalid）四个依赖。）

- [ ] **Step 7: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ \
        orchestrator/src/test/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntimeTest.java
git commit -m "feat(orchestrator): ReAct 采集 LLM usage 并经 SSE 下发分组快照"
```

---

### Task 7: SSE 下发（metaUsage + emitter 分支）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/GenerationFlushScheduler.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJobChunkEmitter.java`

**Interfaces:**
- Produces: `GenerationFlushScheduler.metaUsage(String usageJson): String`；emitter 对 `isUsage()` token 分配 seq + XADD，并记录 `lastUsageJson()` 供终态落库。

- [ ] **Step 1: metaUsage**

`GenerationFlushScheduler` 追加：

```java
    /** LLM usage 计量帧 — wire JSON 由 UsageJsonSupport 构造，此处原样包装下发 */
    public String metaUsage(String usageJson) {
        return usageJson != null ? usageJson : "{\"type\":\"usage\"}";
    }
```

- [ ] **Step 2: emitter usage 分支**

`GenerationJobChunkEmitter`：

1. `onChunkUnfolded` 顶部追加：

```java
        if (token.isUsage()) {
            emitMappedChunk(token, mysqlBuffer, flushPartial, lastFlush);
            return;
        }
```

2. 字段追加 `private volatile String lastUsageJson;` 与访问器 `String lastUsageJson() { return lastUsageJson; }`。

3. `emitSingleMappedChunk` 的 `synchronized` 块内、`isSandboxSession` 分支后追加：

```java
            if (token.isUsage()) {
                lastUsageJson = token.text();
                streamService.appendChunk(generationId, nextSeq, flushScheduler.metaUsage(token.text()));
                return;
            }
```

- [ ] **Step 3: 编译验证**

Run: `mvn -q -pl orchestrator compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/conversation/GenerationFlushScheduler.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJobChunkEmitter.java
git commit -m "feat(orchestrator): SSE 下发 usage 帧并记录末帧供落库"
```

---

### Task 8: 持久化 + 审计 + DTO

**Files:**
- Modify: `docker/mysql/init/11-sunshine-orchestrator.sql`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/entity/ChatMessageEntity.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/ConversationService.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/GenerationFlushScheduler.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJob.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/audit/AuditService.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/dto/ConversationDetailDto.java`

**Interfaces:**
- Produces: `chat_message.usage_json` 列；`updateMessage(..., String usageJson)` 7 参重载；`commitFinal(..., String usageJson)` 7 参重载；`MessageDto.usage`（String）。

- [ ] **Step 1: DDL**

`11-sunshine-orchestrator.sql` `chat_message` 表 `content_blocks` 行后追加：

```sql
    usage_json       MEDIUMTEXT   NULL COMMENT '消息级 LLM usage + 上下文分组快照 JSON',
```

线上环境单独执行 `ALTER TABLE chat_message ADD COLUMN usage_json MEDIUMTEXT NULL COMMENT '消息级 LLM usage + 上下文分组快照 JSON' AFTER content_blocks;`（部署时人工执行，不入库脚本）。

- [ ] **Step 2: Entity**

`ChatMessageEntity` `contentBlocks` 字段后追加：

```java
    /** 消息级 LLM usage + 上下文分组快照 JSON */
    @Column(name = "usage_json", columnDefinition = "MEDIUMTEXT")
    private String usageJson;
```

- [ ] **Step 3: ConversationService.updateMessage 7 参重载**

```java
    @Transactional
    public ChatMessageEntity updateMessage(
            String messageId,
            String content,
            String reasoning,
            String status,
            String stepsJson,
            String contentBlocksJson,
            String usageJson) {
        // 既有 6 参逻辑不变，追加：
        // if (usageJson != null) { msg.setUsageJson(usageJson); }
    }
```

（把既有 6 参方法体改为委托 7 参：`return updateMessage(messageId, content, reasoning, status, stepsJson, contentBlocksJson, null);`）

- [ ] **Step 4: commitFinal 7 参重载**

`GenerationFlushScheduler.commitFinal` 6 参方法委托新 7 参（末参 `usageJson`），7 参内调用 `conversationService.updateMessage(messageId, scrubbed, reasoning, status, stepsJson, contentBlocksJson, usageJson);`。

- [ ] **Step 5: GenerationJob.persistFinal 携带 usage**

`persistFinal` 内两处 `flushScheduler.commitFinal(...)` 调用末参追加 `chunkEmitter != null ? chunkEmitter.lastUsageJson() : null`。

- [ ] **Step 6: 审计 payload**

`AuditService.auditAssistantMessage` payload 追加：

```java
            if (message.getUsageJson() != null && !message.getUsageJson().isBlank()) {
                try {
                    payload.put("usage", objectMapper.readTree(message.getUsageJson()));
                } catch (Exception ignored) {
                    // 脏数据不阻断审计
                }
            }
```

- [ ] **Step 7: MessageDto 透出**

`ConversationDetailDto.MessageDto` 追加字段 `private String usage;`，`from` 内 `dto.setUsage(m.getUsageJson());`。

- [ ] **Step 8: 编译 + 全量 orchestrator 测试**

Run: `mvn -q -pl orchestrator test`
Expected: PASS。

- [ ] **Step 9: Commit**

```bash
git add docker/mysql/init/11-sunshine-orchestrator.sql \
        orchestrator/src/main/java/com/sunshine/orchestrator/conversation/ \
        orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJob.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/audit/AuditService.java
git commit -m "feat(orchestrator): usage 随消息落库 + 审计透出"
```

---

### Task 9: 前端数据层（SSE 解析 + store + 历史恢复）

**Files:**
- Modify: `sunshine-ui/src/api/chat.ts`
- Modify: `sunshine-ui/src/api/sseDispatch.ts`
- Modify: `sunshine-ui/src/api/conversations.ts`
- Modify: `sunshine-ui/src/stores/chatStore.ts`
- Modify: `sunshine-ui/src/api/chatSessionSseConsumer.ts`

**Interfaces:**
- Produces: `MessageUsage` 类型；`ParsedSsePayload` `kind:'usage'`；`ChatMessage.usage`；consumer 就地更新末条 assistant。

- [ ] **Step 1: chat.ts 类型**

```ts
/** 消息级 LLM usage（SSE type=usage 末帧 / 历史 usage_json） */
export interface MessageUsage {
  inputTokens: number
  outputTokens: number
  llmCalls: number
  contextTokens?: number
  contextWindowTokens?: number
  contextPercent?: number
  groups?: Record<string, number>
}
```

`ChatMessage` 追加 `usage?: MessageUsage`。

- [ ] **Step 2: sseDispatch handler**

`ParsedSsePayload` union 追加 `| { kind: 'usage'; usage: MessageUsage }`；`handlers` 追加：

```ts
  usage(obj) {
    const num = (k: string) => (typeof obj[k] === 'number' ? (obj[k] as number) : 0)
    const usage: MessageUsage = {
      inputTokens: num('inputTokens'),
      outputTokens: num('outputTokens'),
      llmCalls: num('callSeq'),
      contextTokens: typeof obj.contextTokens === 'number' ? obj.contextTokens : undefined,
      contextWindowTokens: typeof obj.contextWindowTokens === 'number' ? obj.contextWindowTokens : undefined,
      contextPercent: typeof obj.contextPercent === 'number' ? obj.contextPercent : undefined,
    }
    const mu = obj.messageUsage as Record<string, unknown> | undefined
    if (mu && typeof mu === 'object') {
      usage.inputTokens = typeof mu.inputTokens === 'number' ? mu.inputTokens : usage.inputTokens
      usage.outputTokens = typeof mu.outputTokens === 'number' ? mu.outputTokens : usage.outputTokens
      usage.llmCalls = typeof mu.llmCalls === 'number' ? mu.llmCalls : usage.llmCalls
    }
    const groups = obj.groups as Record<string, unknown> | undefined
    if (groups && typeof groups === 'object') {
      usage.groups = Object.fromEntries(
        Object.entries(groups).filter(([, v]) => typeof v === 'number') as [string, number][],
      )
    }
    return { kind: 'usage', usage }
  },
```

- [ ] **Step 3: conversations.ts 透传**

`ConversationMessage` 追加 `usage?: string`；`parseMessage(m: Record<string, unknown>)`（conversations.ts:133）映射对象内追加 `usage: typeof m.usage === 'string' ? m.usage : undefined`。

- [ ] **Step 4: chatStore 历史恢复**

`mapApiMessages` 内 assistant 分支追加：

```ts
      usage: parseMessageUsage(m.usage),
```

文件内新增：

```ts
function parseMessageUsage(raw: string | undefined): MessageUsage | undefined {
  if (!raw) return undefined
  try {
    const obj = JSON.parse(raw) as Record<string, unknown>
    const mu = (obj.messageUsage ?? obj) as Record<string, unknown>
    return {
      inputTokens: typeof mu.inputTokens === 'number' ? mu.inputTokens : 0,
      outputTokens: typeof mu.outputTokens === 'number' ? mu.outputTokens : 0,
      llmCalls: typeof mu.llmCalls === 'number' ? mu.llmCalls : 0,
      contextTokens: typeof obj.contextTokens === 'number' ? obj.contextTokens : undefined,
      contextWindowTokens: typeof obj.contextWindowTokens === 'number' ? obj.contextWindowTokens : undefined,
      contextPercent: typeof obj.contextPercent === 'number' ? obj.contextPercent : undefined,
      groups: typeof obj.groups === 'object' && obj.groups ? (obj.groups as Record<string, number>) : undefined,
    }
  } catch {
    return undefined
  }
}
```

- [ ] **Step 5: consumer usage 分支**

`chatSessionSseConsumer.ts` 在 `parsed.kind === 'reasoning'` 分支前追加：

```ts
      if (parsed.kind === 'usage') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          lastMsg.usage = parsed.usage
          scheduleAssistantMessageBump(s)
        }
        hooks.onProgress?.(s.id)
        continue
      }
```

- [ ] **Step 6: 前端类型检查**

Run: `cd sunshine-ui && npx vue-tsc --noEmit`（或项目既有 `npm run type-check`）
Expected: 无新增错误。

- [ ] **Step 7: Commit**

```bash
git add sunshine-ui/src/api/chat.ts sunshine-ui/src/api/sseDispatch.ts \
        sunshine-ui/src/api/conversations.ts sunshine-ui/src/stores/chatStore.ts \
        sunshine-ui/src/api/chatSessionSseConsumer.ts
git commit -m "feat(ui): usage SSE 解析与历史恢复"
```

---

### Task 10: UsageStatusBar + 消息尾 meta

**Files:**
- Create: `sunshine-ui/src/components/chat/UsageStatusBar.vue`
- Modify: `sunshine-ui/src/views/ChatView.vue`

**Interfaces:**
- Consumes: `ChatMessage.usage`、前端轮次计算。
- Produces: composer toolbar 状态栏 `T{n} · ↑x · ↓y · ctx p%` + 分组 NPopover；消息尾 `n calls · ↑x ↓y`。

- [ ] **Step 1: UsageStatusBar.vue**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { NPopover } from 'naive-ui'
import type { MessageUsage } from '../../api/chat'

const props = defineProps<{
  turn: number
  usage?: MessageUsage | null
}>()

const GROUP_LABELS: Record<string, string> = {
  system: '系统提示词',
  rules: '用户规则',
  skills: '技能·模式',
  tools: '工具定义',
  contextLayers: '上下文层',
  messages: '对话消息',
  other: '其他',
}

function fmtK(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n)
}

const ctxLabel = computed(() => {
  const u = props.usage
  if (!u) return ''
  if (u.contextPercent != null) return `ctx ${u.contextPercent}%`
  if (u.contextTokens != null) return `ctx ${fmtK(u.contextTokens)}`
  return ''
})

const ctxLevel = computed(() => {
  const p = props.usage?.contextPercent
  if (p == null) return ''
  if (p > 85) return 'usage--error'
  if (p >= 60) return 'usage--warn'
  return ''
})

const groupRows = computed(() => {
  const g = props.usage?.groups
  if (!g) return []
  const total = props.usage?.contextTokens ?? 0
  const sum = Object.values(g).reduce((a, b) => a + Math.max(0, b), 0)
  const other = Math.max(0, total - sum)
  const rows = Object.entries(g)
    .filter(([k]) => k !== 'other')
    .map(([k, v]) => ({ label: GROUP_LABELS[k] ?? k, tokens: Math.max(0, v) }))
  if (other > 0) rows.push({ label: GROUP_LABELS.other, tokens: other })
  return rows.filter(r => r.tokens > 0)
})
</script>

<template>
  <div class="usage-status" :class="ctxLevel">
    <span class="usage-turn">T{{ turn }}</span>
    <template v-if="usage">
      <span class="usage-sep">·</span>
      <span>↑ {{ fmtK(usage.inputTokens) }}</span>
      <span>↓ {{ fmtK(usage.outputTokens) }}</span>
      <template v-if="ctxLabel">
        <NPopover v-if="groupRows.length" trigger="click" placement="top">
          <template #trigger>
            <button type="button" class="usage-ctx-btn">{{ ctxLabel }}</button>
          </template>
          <div class="usage-panel">
            <div class="usage-panel-total">
              ~{{ fmtK(usage.contextTokens ?? 0) }}<template v-if="usage.contextWindowTokens"> / {{ fmtK(usage.contextWindowTokens) }}</template>
            </div>
            <div v-for="row in groupRows" :key="row.label" class="usage-panel-row">
              <span>{{ row.label }}</span>
              <span>~{{ fmtK(row.tokens) }}</span>
            </div>
          </div>
        </NPopover>
        <span v-else>{{ ctxLabel }}</span>
      </template>
    </template>
  </div>
</template>

<style scoped>
.usage-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
  height: 24px;
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.12));
  border-radius: 6px;
  font-size: 12px;
  color: var(--text-3, rgba(255, 255, 255, 0.55));
  white-space: nowrap;
}
.usage-status.usage--warn { color: var(--warning-color, #f0a020); border-color: var(--warning-color, #f0a020); }
.usage-status.usage--error { color: var(--error-color, #de5762); border-color: var(--error-color, #de5762); }
.usage-ctx-btn {
  border: none;
  background: transparent;
  padding: 0;
  font: inherit;
  color: inherit;
  cursor: pointer;
}
.usage-panel { min-width: 180px; display: flex; flex-direction: column; gap: 4px; }
.usage-panel-total { font-weight: 600; border-bottom: 1px solid var(--border-color, rgba(255, 255, 255, 0.12)); padding-bottom: 4px; }
.usage-panel-row { display: flex; justify-content: space-between; gap: 16px; }
</style>
```

- [ ] **Step 2: ChatView 集成状态栏**

`ChatView.vue`：

1. import 组件；script 追加：

```ts
const currentTurn = computed(() => messages.value.filter(m => m.role === 'user').length)
const lastUsage = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role === 'assistant' && m.usage) return m.usage
  }
  return null
})
```

2. `composer-toolbar-left`（`ExecutionModeSelector` 后）插入：

```html
                <UsageStatusBar :turn="Math.max(currentTurn, 1)" :usage="lastUsage" />
```

- [ ] **Step 3: 消息尾 meta**

`msg-copy-bar` 内 `msg-end-time` span 后追加：

```html
                <span v-if="msg.usage" class="msg-usage-meta">
                  {{ msg.usage.llmCalls }} calls · ↑{{ fmtTokens(msg.usage.inputTokens) }} ↓{{ fmtTokens(msg.usage.outputTokens) }}
                </span>
```

script 追加 `fmtTokens`（与组件同逻辑的局部函数）；style 追加 `.msg-usage-meta { font-size: 11px; color: var(--text-3, rgba(255,255,255,0.45)); margin-left: 8px; }`。

- [ ] **Step 4: 前端类型检查**

Run: `cd sunshine-ui && npx vue-tsc --noEmit`
Expected: 无新增错误。

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/components/chat/UsageStatusBar.vue sunshine-ui/src/views/ChatView.vue
git commit -m "feat(ui): composer 状态栏 + 上下文分组面板 + 消息尾 usage meta"
```

---

### Task 11: 联调验收脚本 + 部署 + 文档状态

**Files:**
- Create: `scripts/verify_usage_stream_live.py`
- Modify: `docs/superpowers/specs/2026-08-17-react-usage-context-display-design.md`（状态行）

- [ ] **Step 1: 验收脚本**

参照 `verify_routing_v6_smoke.py` 的 `auth_json/login/new_conversation/chat_sse` 模式编写：登录 → 新建会话 → 发送多步 ReAct 问题（`executionMode=pro`，query 用「分两步：先列出要点再总结。不要调用外部工具。」保证 ≥2 次模型调用）→ 解析 SSE 断言：

1. `type=usage` 帧数 ≥ 2，`callSeq` 单调递增；
2. 末帧 `messageUsage.llmCalls` == usage 帧数；
3. 末帧含 `contextWindowTokens` 且 `contextPercent` ≈ round(100*contextTokens/contextWindowTokens)；
4. 终态后 `GET /api/conversations/{id}/messages` 末条 assistant `usage` 字段非空且 `messageUsage.llmCalls` 与 SSE 一致；
5. `groups` 含 `system` 键且 > 0。

脚本头注释写明前置（gateway/orchestrator/llm-gateway 已重启加载新代码）与用法。

- [ ] **Step 2: 打包重启 + 运行**

```bash
python scripts/start.py --restart llm-gateway orchestrator
python scripts/verify_usage_stream_live.py
```

Expected: 全部 ✅。

- [ ] **Step 3: 前端人工验收步骤（写入交付说明，不自动执行）**

1. 发起多步 ReAct 对话：composer 状态栏出现 `T1 · ↑… ↓… · ctx …%`，每次模型调用结束数值更新；
2. 点击 `ctx` 弹出分组面板，各组 ~值与总额同屏；
3. 刷新页面：状态栏与消息尾 `n calls · ↑… ↓…` 恢复；
4. 第二轮对话 `T` 递增为 2。

- [ ] **Step 4: 文档状态更新**

spec 状态行改为 `✅ 已实现（待前端人工验收）`；`CLAUDE.md` 进度行追加「**Usage 状态栏 ✅**（轮次/输入输出/ctx 分组；[spec](../specs/2026-08-17-react-usage-context-display-design.md)）」。

- [ ] **Step 5: Commit**

```bash
git add scripts/verify_usage_stream_live.py docs/superpowers/specs/2026-08-17-react-usage-context-display-design.md CLAUDE.md
git commit -m "feat(scripts): usage 链路 live 验收 + 文档状态收口"
```
