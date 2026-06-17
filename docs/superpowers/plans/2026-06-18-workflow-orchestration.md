# Workflow 编排架构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 orchestrator 从硬编码 intent 分支重构为 `simple-llm | workflow | react` 三模式执行架构，引入简化 Dify Workflow 引擎与 Agent 子节点，实现高扩展与清晰分层。

**Architecture:** L1 `IntentRouter` + `WorkflowCatalog` 输出 `ExecutionPlan` JSON；L2 `ExecutionDispatcher` 分发至三个 Executor；workflow 模式由 `WorkflowExecutor` 线性执行 `NodeHandler` 链；Agent 节点包装现有 `SunshineAgent` 为 `f(input)→output` 子函数。配置 SSOT 在 `docs/nacos/sunshine-workflows.yaml`。

**Tech Stack:** JDK 21, Spring Boot 3.2.9, AgentScope-Java 1.0.7 ReActAgent, WebFlux SSE, Nacos YAML, Flyway, JUnit5 + Mockito + AssertJ

**Spec:** `docs/superpowers/specs/2026-06-18-workflow-orchestration-design.md`

---

## File Map（实施前必读）

### orchestrator — 新建

| 文件 | 职责 |
|------|------|
| `routing/ExecutionPlan.java` | mode + workflowId + params + reason |
| `routing/ExecutionMode.java` | 枚举：SIMPLE_LLM, WORKFLOW, REACT |
| `routing/WorkflowCatalog.java` | 读 catalog、渲染 prompt、校验 workflowId |
| `routing/ExecutionPlanParser.java` | 解析/校验 Intent LLM JSON，fallback react |
| `execution/ExecutionStreamContext.java` | 从 ChatController 抽出的流式上下文 record |
| `execution/ExecutionDispatcher.java` | 按 mode 分发 |
| `execution/SimpleLlmExecutor.java` | 直连 LLM |
| `execution/ReactExecutor.java` | 整单 SunshineAgent |
| `execution/WorkflowExecutor.java` | 线性 DAG 执行 |
| `execution/WorkflowContext.java` | 节点输出变量表 |
| `execution/WorkflowDefinition.java` | nodes + linearOrder |
| `execution/WorkflowDefinitionLoader.java` | Nacos 加载 + RefreshScope |
| `execution/NodeHandler.java` | 节点接口 |
| `execution/NodeSpec.java` | id + type + params |
| `execution/NodeResult.java` | success/fail + output map |
| `execution/TemplateResolver.java` | `{{nodeId.field}}` 替换 |
| `execution/handler/*.java` | 6 种 NodeHandler |
| `execution/agent/AgentNodeInput.java` | 子 Agent 入参 |
| `execution/agent/AgentNodeOutput.java` | 子 Agent 出参 |

### orchestrator — 修改

| 文件 | 改动 |
|------|------|
| `agent/IntentRouter.java` | 返回 `Mono<ExecutionPlan>` |
| `controller/ChatController.java` | 删除 `resolveByIntent` / `*AgentFlux`，委托 Dispatcher |
| `config/WorkflowProperties.java` | 绑定 sunshine-workflows.yaml |
| `resources/application.yml` | import sunshine-workflows.yaml |
| `conversation/entity/ChatMessageEntity.java` | +executionMode, +workflowId |
| `resources/db/migration/V6__execution_plan.sql` | 新列 |

### tool-manager — 新建/修改

| 文件 | 改动 |
|------|------|
| `tool/ToolHandler.java` | 接口 |
| `registry/ToolRegistry.java` | 自动注册 |
| `tool/FinanceToolHandler.java` | 迁移 FinanceTool |
| `service/ToolInvokeService.java` | 委托 Registry |

### Nacos / 文档

| 文件 | 改动 |
|------|------|
| `docs/nacos/sunshine-workflows.yaml` | catalog + 3 个 workflow 图 |
| `docs/nacos/sunshine-orchestrator.yaml` | intent JSON prompt、react 白名单 |
| `docs/nacos/README.md` | 登记新 Data ID |
| `orchestrator/src/main/resources/application.yml` | import workflows |

---

## Phase 1：路由层 + 三 Executor 抽离

### Task 1: ExecutionPlan 与 Parser 单元测试

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/ExecutionMode.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/ExecutionPlan.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/ExecutionPlanParser.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/ExecutionPlanParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sunshine.orchestrator.routing;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPlanParserTest {

    private final ExecutionPlanParser parser = new ExecutionPlanParser();

    @Test
    void parsesWorkflowJson() {
        String json = """
                {"mode":"workflow","workflowId":"knowledge-qa","params":{},"reason":"查制度"}
                """;
        ExecutionPlan plan = parser.parse(json);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("查制度");
    }

    @Test
    void invalidJsonFallsBackToReact() {
        ExecutionPlan plan = parser.parse("not json");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
        assertThat(plan.workflowId()).isNull();
    }

    @Test
    void normalizesSimpleLlmAlias() {
        ExecutionPlan plan = parser.parse("{\"mode\":\"simple-llm\"}");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.SIMPLE_LLM);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl orchestrator -Dtest=ExecutionPlanParserTest -q
```

Expected: FAIL — classes not found

- [ ] **Step 3: Write minimal implementation**

```java
// ExecutionMode.java
package com.sunshine.orchestrator.routing;
public enum ExecutionMode {
    SIMPLE_LLM, WORKFLOW, REACT;
    public static ExecutionMode from(String raw) {
        if (raw == null) return REACT;
        return switch (raw.toLowerCase().replace('_', '-')) {
            case "simple", "simple-llm", "direct" -> SIMPLE_LLM;
            case "workflow", "pipeline" -> WORKFLOW;
            default -> REACT;
        };
    }
}
```

```java
// ExecutionPlan.java
package com.sunshine.orchestrator.routing;
import java.util.Map;
public record ExecutionPlan(
        ExecutionMode mode,
        String workflowId,
        Map<String, String> params,
        String reason
) {
    public static ExecutionPlan reactFallback(String reason) {
        return new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), reason);
    }
    /** 写入 DB / Generation 的简短标签 */
    public String intentLabel() {
        return switch (mode) {
            case SIMPLE_LLM -> "simple-llm";
            case WORKFLOW -> "workflow:" + (workflowId != null ? workflowId : "unknown");
            case REACT -> "react";
        };
    }
}
```

```java
// ExecutionPlanParser.java
package com.sunshine.orchestrator.routing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Slf4j
@Component
public class ExecutionPlanParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExecutionPlan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExecutionPlan.reactFallback("empty intent response");
        }
        String trimmed = raw.trim();
        // 兼容旧版裸字符串
        if (!trimmed.startsWith("{")) {
            return legacyPlan(trimmed);
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            ExecutionMode mode = ExecutionMode.from(text(node, "mode"));
            String workflowId = text(node, "workflowId");
            String reason = text(node, "reason");
            Map<String, String> params = parseParams(node.get("params"));
            return new ExecutionPlan(mode, workflowId, params, reason);
        } catch (Exception e) {
            log.warn("[ExecutionPlanParser] parse failed: {}", e.getMessage());
            return ExecutionPlan.reactFallback("parse error");
        }
    }

    private static ExecutionPlan legacyPlan(String raw) {
        return switch (raw.toLowerCase()) {
            case "simple" -> new ExecutionPlan(ExecutionMode.SIMPLE_LLM, null, Map.of(), "legacy");
            case "knowledge" -> new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "legacy");
            case "finance" -> new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-list", Map.of("status", "pending"), "legacy");
            default -> ExecutionPlan.reactFallback("legacy:" + raw);
        };
    }

    private static Map<String, String> parseParams(JsonNode paramsNode) {
        Map<String, String> params = new HashMap<>();
        if (paramsNode == null || !paramsNode.isObject()) return params;
        Iterator<Map.Entry<String, JsonNode>> it = paramsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            params.put(e.getKey(), e.getValue().asText(""));
        }
        return params;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -pl orchestrator -Dtest=ExecutionPlanParserTest -q
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/routing/ \
        orchestrator/src/test/java/com/sunshine/orchestrator/routing/
git commit -m "feat(orchestrator): add ExecutionPlan parser with legacy fallback"
```

---

### Task 2: WorkflowCatalog 与 Nacos 配置骨架

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/routing/WorkflowCatalog.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/config/WorkflowProperties.java`
- Create: `docs/nacos/sunshine-workflows.yaml`
- Modify: `orchestrator/src/main/resources/application.yml`
- Modify: `docs/nacos/README.md`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/WorkflowCatalogTest.java`

- [ ] **Step 1: Write sunshine-workflows.yaml**

```yaml
# docs/nacos/sunshine-workflows.yaml
workflow-catalog:
  - id: knowledge-qa
    mode: workflow
    desc: 查企业制度/手册，检索后作答
    nodes: [start, rag, llm, answer]
    examples: ["请假制度是什么", "报销流程规定"]
  - id: finance-list
    mode: workflow
    desc: 列出待审批/已审批财务消息
    nodes: [start, tool, llm, answer]
    examples: ["有哪些待审批报销"]
  - id: finance-smart
    mode: workflow
    desc: 财务综合分析，含 Agent 推理
    nodes: [start, tool, agent, llm, answer]
    examples: ["待审批是否合规"]

workflows:
  knowledge-qa:
    nodes:
      - id: start
        type: start
      - id: rag
        type: rag
        params: { topK: "3" }
      - id: llm
        type: llm
        params:
          prompt: "根据知识库检索结果回答用户问题。\n\n检索结果：\n{{rag.output}}\n\n用户问题：{{start.userQuery}}"
      - id: answer
        type: answer
    edges: [start, rag, llm, answer]

  finance-list:
    nodes:
      - id: start
        type: start
      - id: finance-list
        type: tool
        params:
          tool: list_finance_messages
          status: "{{plan.params.status}}"
      - id: llm
        type: llm
        params:
          prompt: "根据财务工具查询结果回答。\n\n数据：\n{{finance-list.output}}\n\n用户问题：{{start.userQuery}}"
      - id: answer
        type: answer
    edges: [start, finance-list, llm, answer]

  finance-smart:
    nodes:
      - id: start
        type: start
      - id: finance-list
        type: tool
        params:
          tool: list_finance_messages
          status: "{{plan.params.status}}"
      - id: analyze
        type: agent
        params:
          query: "{{start.userQuery}}"
          context: "{{finance-list.output}}"
          tools: [list_finance_messages]
          maxIters: "5"
      - id: llm
        type: llm
        params:
          prompt: "根据 Agent 分析结果生成用户可见回答。\n\n分析：\n{{analyze.answer}}\n\n用户问题：{{start.userQuery}}"
      - id: answer
        type: answer
    edges: [start, finance-list, analyze, llm, answer]
```

- [ ] **Step 2: application.yml 增加 import**

```yaml
# orchestrator/src/main/resources/application.yml — spring.config.import 追加
      - optional:nacos:sunshine-workflows.yaml
```

- [ ] **Step 3: Write failing WorkflowCatalogTest**

```java
@Test
void rendersCatalogForPrompt() {
    WorkflowProperties props = new WorkflowProperties();
    props.setCatalog(List.of(catalogEntry("knowledge-qa", "查制度")));
    WorkflowCatalog catalog = new WorkflowCatalog(props);
    String rendered = catalog.renderForPrompt();
    assertThat(rendered).contains("knowledge-qa").contains("查制度");
}

@Test
void validateWorkflowIdExists() {
    WorkflowProperties props = new WorkflowProperties();
    props.setCatalog(List.of(catalogEntry("knowledge-qa", "查制度")));
    WorkflowCatalog catalog = new WorkflowCatalog(props);
    assertThat(catalog.isKnownWorkflow("knowledge-qa")).isTrue();
    assertThat(catalog.isKnownWorkflow("missing")).isFalse();
}
```

- [ ] **Step 4: Implement WorkflowProperties + WorkflowCatalog**

`WorkflowProperties` 使用 `@ConfigurationProperties(prefix = "workflow")` 绑定 `workflow-catalog` 与 `workflows` 节点（注意 Nacos 顶层 key 与 prefix 对齐，可在 yaml 顶层用 `workflow:` 包裹或直接用 `@ConfigurationProperties` 匹配文件结构）。

`WorkflowCatalog` 方法：
- `renderForPrompt()` → markdown 列表
- `isKnownWorkflow(String id)`
- `sanitize(ExecutionPlan plan)` → 未知 workflowId 时 `ExecutionPlan.reactFallback("unknown workflow")`

- [ ] **Step 5: Run tests + update docs/nacos/README.md 登记 Data ID**

```bash
mvn test -pl orchestrator -Dtest=WorkflowCatalogTest -q
```

- [ ] **Step 6: Commit**

```bash
git add docs/nacos/sunshine-workflows.yaml docs/nacos/README.md \
        orchestrator/src/main/resources/application.yml \
        orchestrator/src/main/java/com/sunshine/orchestrator/config/WorkflowProperties.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/routing/WorkflowCatalog.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/routing/WorkflowCatalogTest.java
git commit -m "feat(orchestrator): add WorkflowCatalog and sunshine-workflows Nacos SSOT"
```

---

### Task 3: IntentRouter 返回 ExecutionPlan

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/IntentRouter.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/IntentRouterPlanTest.java`

- [ ] **Step 1: Write failing test with MockWebServer**

```java
@Test
void classifyReturnsExecutionPlan() {
    // mock LLM 返回 JSON
    when(mockLlmResponse).thenReturn("""
        {"mode":"workflow","workflowId":"finance-list","params":{"status":"pending"},"reason":"查待审批"}
        """);
    StepVerifier.create(intentRouter.classifyPlan("有哪些待审批报销"))
            .assertNext(plan -> {
                assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
                assertThat(plan.workflowId()).isEqualTo("finance-list");
            })
            .verifyComplete();
}
```

- [ ] **Step 2: Modify IntentRouter**

新增方法：

```java
public Mono<ExecutionPlan> classifyPlan(String userMessage) {
    String prompt = workflowCatalog.renderIntoClassifier(
            prompts.intentClassifierPromptOrEmpty());
    // ... 调 LLM ...
    .map(content -> workflowCatalog.sanitize(planParser.parse(content)));
}
```

保留 `classify(String)` 委托 `classifyPlan().map(ExecutionPlan::intentLabel)` 以兼容旧测试，标记 `@Deprecated`。

- [ ] **Step 3: 更新 sunshine-orchestrator.yaml classifier-prompt**

要求只输出 JSON，并包含 `{{workflow-catalog}}` 占位（由 `WorkflowCatalog.renderIntoClassifier` 替换）。

- [ ] **Step 4: Run tests**

```bash
mvn test -pl orchestrator -Dtest=IntentRouterPlanTest,ExecutionPlanParserTest -q
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(orchestrator): IntentRouter returns ExecutionPlan JSON"
```

---

### Task 4: Flyway V6 与消息字段扩展

**Files:**
- Create: `orchestrator/src/main/resources/db/migration/V6__execution_plan.sql`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/entity/ChatMessageEntity.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/ConversationService.java`（updateMessageIntent 扩展）

- [ ] **Step 1: Write migration**

```sql
-- V6__execution_plan.sql
ALTER TABLE chat_message
    ADD COLUMN execution_mode VARCHAR(16) NULL AFTER intent,
    ADD COLUMN workflow_id   VARCHAR(64) NULL AFTER execution_mode;
```

- [ ] **Step 2: Entity 增加字段 + ConversationService 新方法**

```java
public void updateMessageExecutionPlan(String messageId, ExecutionPlan plan) {
    // intent 存 intentLabel() 兼容审计；另写 execution_mode / workflow_id
}
```

- [ ] **Step 3: 运行 orchestrator 测试确保 Flyway validate 通过**

```bash
mvn test -pl orchestrator -Dtest=ConversationIntegrationTest -q
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(orchestrator): persist execution_mode and workflow_id on messages"
```

---

### Task 5: ExecutionStreamContext + SimpleLlmExecutor + ReactExecutor

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ExecutionStreamContext.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/SimpleLlmExecutor.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ReactExecutor.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/SimpleLlmExecutorTest.java`

- [ ] **Step 1: 定义 ExecutionStreamContext record**

从 `ChatController.StreamContext` 抽出字段：`conversationId, assistantMsgId, userContent, memory, existingContent, existingReasoning, existingSteps, resume, userId, tenantId, plan`。

- [ ] **Step 2: SimpleLlmExecutor 测试 + 实现**

```java
@Component
@RequiredArgsConstructor
public class SimpleLlmExecutor {
    private final LlmGatewayClient llmGateway;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        if (StringUtils.hasText(ctx.existingContent())) {
            return llmGateway.streamContinue(ctx.memory(), ctx.userContent(), ctx.existingContent());
        }
        return llmGateway.streamWithMemory(ctx.memory(), ctx.userContent());
    }
}
```

- [ ] **Step 3: ReactExecutor 实现**

```java
@Component
@RequiredArgsConstructor
public class ReactExecutor {
    private final SunshineAgent sunshineAgent;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        return sunshineAgent.chat(
                ctx.memory(), ctx.userContent(), ctx.userId(), ctx.tenantId(),
                ctx.assistantMsgId());
    }
}
```

- [ ] **Step 4: Run unit tests**

```bash
mvn test -pl orchestrator -Dtest=SimpleLlmExecutorTest -q
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(orchestrator): add SimpleLlmExecutor and ReactExecutor"
```

---

### Task 6: ExecutionDispatcher + ChatController 瘦身（Phase 1 收尾）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ExecutionDispatcher.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/ConversationIntegrationTest.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/GenerationReconnectIntegrationTest.java`

- [ ] **Step 1: ExecutionDispatcher**

```java
@Component
@RequiredArgsConstructor
public class ExecutionDispatcher {
    private final SimpleLlmExecutor simpleLlmExecutor;
    private final ReactExecutor reactExecutor;
    private final WorkflowExecutor workflowExecutor; // Task 8 前可先抛 UnsupportedOperationException

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        return switch (ctx.plan().mode()) {
            case SIMPLE_LLM -> simpleLlmExecutor.execute(ctx);
            case WORKFLOW -> workflowExecutor.execute(ctx);
            case REACT -> reactExecutor.execute(ctx);
        };
    }
}
```

- [ ] **Step 2: ChatController 改造 intent 流**

将 `resolveByIntent` / `knowledgeAgentFlux` / `financeAgentFlux` 替换为：

```java
return intentRouter.classifyPlan(ctx.userContent())
    .flatMapMany(plan -> {
        ExecutionStreamContext execCtx = ctx.withPlan(plan);
        conversationService.updateMessageExecutionPlan(ctx.assistantMsgId(), plan);
        String detail = plan.mode() + (plan.workflowId() != null ? " · " + plan.workflowId() : "");
        session.complete("intent", detail);
        return Flux.concat(
            Flux.fromIterable(intentDoneTokens),
            dispatcher.execute(execCtx)
        );
    });
```

续传逻辑：`execution_mode` 为 WORKFLOW 且无 content 时走 workflow；有 partial content 仍 `streamContinue`。

- [ ] **Step 3: 更新集成测试 mock**

```java
when(intentRouter.classifyPlan(anyString())).thenReturn(Mono.just(
    new ExecutionPlan(ExecutionMode.SIMPLE_LLM, null, Map.of(), "test")));
```

- [ ] **Step 4: 全量 orchestrator 单测**

```bash
mvn test -pl orchestrator -q
```

Expected: PASS（WorkflowExecutor 未实现前，WORKFLOW 用临时 legacy 映射到 ReactExecutor 或 stub）

**临时策略：** Phase 1 末 `WorkflowExecutor` 若未完成，Dispatcher 对 WORKFLOW 先委托 `LegacyWorkflowBridge`（调用原 knowledgeAgentFlux/financeAgentFlux 逻辑），Phase 2 Task 8 删除。

- [ ] **Step 5: Commit**

```bash
git commit -m "refactor(orchestrator): ChatController delegates to ExecutionDispatcher"
```

---

## Phase 2：Workflow 引擎 MVP

### Task 7: TemplateResolver + WorkflowContext + NodeResult

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/TemplateResolver.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowContext.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/NodeResult.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/TemplateResolverTest.java`

- [ ] **Step 1: TemplateResolver 测试**

```java
@Test
void replacesNodeField() {
    WorkflowContext ctx = new WorkflowContext();
    ctx.put("start", Map.of("userQuery", "你好"));
    String out = TemplateResolver.resolve("问题：{{start.userQuery}}", ctx);
    assertThat(out).isEqualTo("问题：你好");
}

@Test
void replacesPlanParams() {
    WorkflowContext ctx = new WorkflowContext();
    ctx.put("plan", Map.of("params", Map.of("status", "pending")));
    assertThat(TemplateResolver.resolve("{{plan.params.status}}", ctx)).isEqualTo("pending");
}
```

- [ ] **Step 2: 实现 TemplateResolver（正则 `\{\{([a-zA-Z0-9_.]+)\}\}`）**

- [ ] **Step 3: Run test**

```bash
mvn test -pl orchestrator -Dtest=TemplateResolverTest -q
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(orchestrator): add WorkflowContext and TemplateResolver"
```

---

### Task 8: NodeHandler 实现（start / rag / tool / llm / answer）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/NodeHandler.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/NodeHandlerRegistry.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/StartNodeHandler.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/RagNodeHandler.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/ToolNodeHandler.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/LlmNodeHandler.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/AnswerNodeHandler.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/ToolNodeHandlerTest.java`

- [ ] **Step 1: NodeHandler 接口**

```java
public interface NodeHandler {
    String type();
    Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx);
}
```

- [ ] **Step 2: StartNodeHandler** — 写入 userQuery/memory/userId/tenantId

- [ ] **Step 3: RagNodeHandler** — 调 `RagClient.search`，output=`chunks`/`hitCount`

- [ ] **Step 4: ToolNodeHandler** — 调 `ToolManagerClient.invoke(tool, params)`

- [ ] **Step 5: LlmNodeHandler** — 解析 prompt 模板，`LlmGatewayClient.streamWithMemory` 收集为 output answer（MVP 可同步 block 收集，与现网一致）

- [ ] **Step 6: AnswerNodeHandler** — 取上游 llm output 或最后节点 output 作为最终 Flux content

- [ ] **Step 7: ToolNodeHandlerTest with Mockito**

```bash
mvn test -pl orchestrator -Dtest=ToolNodeHandlerTest -q
```

- [ ] **Step 8: Commit**

```bash
git commit -m "feat(orchestrator): add workflow NodeHandlers start/rag/tool/llm/answer"
```

---

### Task 9: AgentNodeHandler（子 Agent）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/agent/AgentNodeInput.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/agent/AgentNodeOutput.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/AgentNodeHandler.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/SunshineAgent.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/AgentNodeHandlerTest.java`

- [ ] **Step 1: SunshineAgent 新增子 Agent 入口**

```java
public Flux<StreamToken> chatAsSubAgent(
        MemoryContext memory, String query, String injectedContext,
        String assistantMessageId, List<String> allowedTools) {
    // MVP：injectedContext 作为 financeContext 注入；allowedTools 暂用全量 Toolkit，Phase 3 收紧
    return chat(memory, query, userId, tenantId, assistantMessageId, null, injectedContext);
}
```

- [ ] **Step 2: AgentNodeHandler 收集 StreamToken → AgentNodeOutput**

从 flux 中提取最终 content 作为 `answer`，收集 tool step 作为 `toolCalls` 摘要。

- [ ] **Step 3: 测试 + commit**

```bash
mvn test -pl orchestrator -Dtest=AgentNodeHandlerTest -q
git commit -m "feat(orchestrator): AgentNodeHandler wraps SunshineAgent as sub-agent"
```

---

### Task 10: WorkflowExecutor + WorkflowDefinitionLoader

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowDefinition.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowDefinitionLoader.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowExecutor.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/WorkflowExecutorTest.java`

- [ ] **Step 1: WorkflowExecutorTest — 线性 3 节点 mock**

```java
@Test
void runsLinearWorkflow() {
    // mock handlers, definition knowledge-qa 简化版
    StepVerifier.create(executor.execute(ctxWithPlan("knowledge-qa")))
        .expectNextMatches(StreamToken::isStep) // node-rag start
        .thenConsumeWhile(t -> true)
        .verifyComplete();
}
```

- [ ] **Step 2: WorkflowExecutor 实现**

```java
public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
    WorkflowDefinition def = loader.require(ctx.plan().workflowId());
    WorkflowContext wfCtx = new WorkflowContext();
    wfCtx.put("plan", Map.of("params", ctx.plan().params()));
    List<StreamToken> planStep = emitPlanStep(def);
    return Flux.concat(
        Flux.fromIterable(planStep),
        Flux.fromIterable(runNodes(def, wfCtx, ctx))
    );
}
```

每个节点发射 Timeline：`node-{id}` start/complete（复用 `ProcessingTimelineSession`）。

- [ ] **Step 3: 删除 ChatController 中 legacy flux 方法与 LegacyWorkflowBridge**

- [ ] **Step 4: 回归**

```bash
mvn test -pl orchestrator -q
powershell -ExecutionPolicy Bypass -File scripts/phase2-agent-demo.ps1
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(orchestrator): WorkflowExecutor runs linear DAG from Nacos"
```

---

## Phase 3：ToolRegistry + Timeline 统一

### Task 11: tool-manager ToolRegistry

**Files:**
- Create: `tool-manager/src/main/java/com/sunshine/tool/tool/ToolHandler.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/registry/ToolRegistry.java`
- Create: `tool-manager/src/main/java/com/sunshine/tool/tool/FinanceToolHandler.java`
- Modify: `tool-manager/src/main/java/com/sunshine/tool/service/ToolInvokeService.java`
- Create: `tool-manager/src/test/java/com/sunshine/tool/registry/ToolRegistryTest.java`

- [ ] **Step 1: ToolHandler 接口**

```java
public interface ToolHandler {
    String name();
    String invoke(Map<String, String> params);
}
```

- [ ] **Step 2: FinanceToolHandler 包装 FinanceTool**

- [ ] **Step 3: ToolRegistry 注入 List<ToolHandler>**

```java
public String invoke(String name, Map<String, String> params) {
    ToolHandler handler = handlers.get(name);
    if (handler == null) throw new IllegalArgumentException("unknown tool: " + name);
    return handler.invoke(params);
}
```

- [ ] **Step 4: ToolInvokeService 委托 Registry**

- [ ] **Step 5: 测试**

```bash
mvn test -pl tool-manager -Dtest=ToolRegistryTest -q
git commit -m "feat(tool-manager): replace switch with ToolRegistry"
```

---

### Task 12: Timeline 统一（think / node-* / 废弃 agent 容器步）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/ThinkStepMapper.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentScopeEventMapper.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/SunshineAgent.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/processing/ThinkStepMapperTest.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/StepLabels.java`

- [ ] **Step 1: 修改 ThinkStepMapperTest — agent 路径 reasoning 进 think**

更新 `reasoningStaysOnAgentWhenAgentRunning` → 期望 `think` step。

- [ ] **Step 2: 删除 `isAgentManagedPath()` 特殊逻辑**

所有 reasoning → `think`；content → `generate`。

- [ ] **Step 3: SunshineAgent 删除 agent bootstrap steps**

去掉 `session.pending("agent")` / `session.start("agent")`；ReAct 推理走 think，工具走 Hook。

- [ ] **Step 4: AgentScopeEventMapper REASONING → think stepDelta**

- [ ] **Step 5: StepLabels 增加 node-* 与 plan 文案**

- [ ] **Step 6: 全量测试**

```bash
mvn test -pl orchestrator -Dtest=ThinkStepMapperTest,AgentScopeEventMapperTest -q
mvn test -pl orchestrator -q
git commit -m "refactor(orchestrator): unify timeline to think/tool/node steps"
```

---

### Task 13: DynamicToolkit（react 白名单）+ 清理 *AgentTool

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentConfig.java`
- Delete: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/FinanceAgentTool.java`（评估后）
- Modify: `docs/nacos/sunshine-orchestrator.yaml`

- [ ] **Step 1: Nacos 配置 react 工具白名单**

```yaml
agent:
  execution:
    default-mode: react
    react:
      tools: [search_knowledge, list_finance_messages]
```

- [ ] **Step 2: DynamicToolkitFactory 按白名单注册 RagTool + RemoteToolProxy**

`RemoteToolProxy` 统一调 `ToolManagerClient`，替代 FinanceAgentTool。

- [ ] **Step 3: 更新 AgentConfigRefreshTest / SunshineAgentMemoryTest**

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(orchestrator): DynamicToolkit from react tool whitelist"
```

---

## Phase 4：文档、Nacos 同步与验收

### Task 14: 文档与 CLAUDE.md 更新

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/implementation-plan.md`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（完整 intent JSON prompt）

- [ ] **Step 1: CLAUDE.md 追加三模式说明与 sunshine-workflows.yaml**

- [ ] **Step 2: implementation-plan 阶段三前插入 Workflow 编排里程碑**

- [ ] **Step 3: sync-nacos 并手动验证**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1
powershell -ExecutionPolicy Bypass -File scripts/phase2-agent-demo.ps1
```

- [ ] **Step 4: Commit**

```bash
git commit -m "docs: workflow orchestration architecture and nacos SSOT"
```

---

## Spec Coverage Self-Review

| Spec 要求 | Task |
|-----------|------|
| D1 三模式路由 | Task 1, 3, 6 |
| D2 简化 Dify workflow | Task 2, 8, 10 |
| D3 Agent 子函数 | Task 9 |
| D4 顶层 react | Task 5, 6 |
| D5 节点间引擎推进 | Task 10 |
| D6 catalog 注入 prompt | Task 2, 3 |
| D7 JSON 输出 | Task 1, 3 |
| D8 Nacos SSOT | Task 2, 14 |
| D9 SunshineAgent 包装 | Task 9 |
| D10 Timeline 统一 | Task 12 |
| D11 ToolRegistry | Task 11 |
| D12 渐进迁移 | Phase 1 LegacyBridge → Phase 2 删除 |
| 错误降级 | Task 1 parser fallback, Task 2 sanitize |
| 续传 | Task 6 保留 streamContinue |
| 扩展性验收 | Task 10 + 11 |

无 TBD 占位符。

---

## 执行选项

Plan complete and saved to `docs/superpowers/plans/2026-06-18-workflow-orchestration.md`.

**两种执行方式：**

1. **Subagent-Driven（推荐）** — 每个 Task 派发独立子 Agent，Task 间你做 review，迭代快  
2. **Inline Execution** — 本会话按 Task 顺序直接实施，每 Phase 结束设检查点

你选哪种？
