# Workflow 结构化 I/O 与变量类型系统重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 workflow 数据流从扁平 `Map<String,String>` 彻底重构为 JSON 结构化 `TypedValue` 变量类型系统，节点显式 inputs/outputs 契约，新增变量赋值/参数提取节点，join 聚合策略，废弃 answer 全量注入。

**Architecture:** WF-1 建 TypedValue + WorkflowContext + TemplateResolver + NodeSpec 地基；WF-2 适配全部 NodeHandler；WF-3 新增 VariableAssignment/ParameterExtractor 节点；WF-4 重写 DB 种子 + checkpoint + PlanAnswerPromptAssembler；WF-5 Studio 前端结构化编辑器。删旧建新，不做双格式兼容。

**Tech Stack:** Java 21 - Spring Boot 3.2.9 - Jackson 2.x（已有依赖） - Reactor - JUnit5 + Mockito + AssertJ

## Global Constraints

- 版本：Java 21、Spring Boot 3.2.9（不升）
- AS2 迁移已完成（P0-P3+P7），前置条件满足
- 删旧建新：旧 WorkflowContext/NodeSpec/NodeResult 的 String 体系直接替换为 TypedValue，不做双格式运行期兼容
- 禁止 Flyway；库表变更 SQL SSOT 在 `docker/mysql/init/`（重写 `13-sunshine-workflow-manager.sql`）
- 提示词正文 SSOT = prompt-manager Catalog，禁止 Java 硬编码
- 模型输出不二次加工：禁截断/摘要/过滤
- 编译：`mvn -pl orchestrator -am compile`；启动：`python scripts/start.py`
- 改 orchestrator 后：编译 -> 重启 -> 跑 live/e2e 留记录
- commit 前缀：feat(wf-n) / test(wf-n) / chore(wf-n) / refactor(wf-n)
- 测试风格：JUnit5 + Mockito + AssertJ，handler 测试 mock 依赖 + `.block()` 同步断言
- NodeHandlerRegistry 是 Spring 自动收集 `List<NodeHandler>`，新 handler 加 `@Component` 即可注册

---

## WF-1 - 变量类型系统地基

**出口闸门**：编译绿 + TypedValue/WorkflowContext/TemplateResolver/NodeSpec 单测全过 + 嵌套取值 `{{tool_1.output.data.items[0].id}}` 单测通过。

### Task WF-1-1: TypedValue 类型体系

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/TypedValue.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/TypedValueTest.java`

**Interfaces:**
- Produces: `TypedValue` sealed interface（Scalar/JsonObject/JsonArray），`render()` 返回 prompt 可读字符串，`toJson()` 返回 JsonNode；`TypedValue.fromJson(JsonNode)` 工厂方法；`TypedValue.scalar(String)` / `TypedValue.scalar(int)` 便捷工厂。

- [ ] **Step 1: 写失败单测--Scalar render + toJson**

```java
package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypedValueTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void scalarStringRenderReturnsText() {
        TypedValue v = TypedValue.scalar("hello");
        assertThat(v.render()).isEqualTo("hello");
        assertThat(v.toJson().isTextual()).isTrue();
        assertThat(v.toJson().asText()).isEqualTo("hello");
    }

    @Test
    void scalarNumberRenderReturnsToString() {
        TypedValue v = TypedValue.scalar(42);
        assertThat(v.render()).isEqualTo("42");
        assertThat(v.toJson().isInt()).isTrue();
    }

    @Test
    void jsonObjectRenderReturnsPrettyString() {
        ObjectNode node = om.createObjectNode();
        node.put("id", "exp-001");
        node.put("amount", 100);
        TypedValue v = TypedValue.fromJson(node);
        assertThat(v.render()).contains(""id" : "exp-001"");
        assertThat(v.render()).contains(""amount" : 100");
    }

    @Test
    void jsonArrayRenderReturnsPrettyString() {
        var arr = om.createArrayNode();
        arr.add("a").add("b");
        TypedValue v = TypedValue.fromJson(arr);
        assertThat(v.render()).contains(""a"").contains(""b"");
    }

    @Test
    void fromJsonNullReturnsScalarNull() {
        TypedValue v = TypedValue.fromJson(om.nullNode());
        assertThat(v.render()).isEqualTo("null");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=TypedValueTest -q 2>&1 | tail -5`
Expected: FAIL - TypedValue 类不存在

- [ ] **Step 3: 实现 TypedValue**

```java
package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/** workflow 变量值的统一类型（sealed），支持结构化 JSON 取值与 prompt 可读渲染 */
public sealed interface TypedValue permits TypedValue.Scalar, TypedValue.JsonObject, TypedValue.JsonArray {

    String render();

    JsonNode toJson();

    record Scalar(JsonNode value) implements TypedValue {
        @Override
        public String render() {
            if (value == null || value.isNull()) {
                return "null";
            }
            return value.isTextual() ? value.asText() : value.toString();
        }

        @Override
        public JsonNode toJson() {
            return value;
        }
    }

    record JsonObject(ObjectNode node) implements TypedValue {
        @Override
        public String render() {
            return node.toPrettyString();
        }

        @Override
        public JsonNode toJson() {
            return node;
        }
    }

    record JsonArray(ArrayNode node) implements TypedValue {
        @Override
        public String render() {
            return node.toPrettyString();
        }

        @Override
        public JsonNode toJson() {
            return node;
        }
    }

    static TypedValue scalar(String text) {
        return new Scalar(text != null ? TextNode.valueOf(text) : new com.fasterxml.jackson.databind.node.NullNode());
    }

    static TypedValue scalar(int number) {
        return new Scalar(new com.fasterxml.jackson.databind.node.IntNode(number));
    }

    static TypedValue fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return new Scalar(new com.fasterxml.jackson.databind.node.NullNode());
        }
        if (node.isObject()) {
            return new JsonObject((ObjectNode) node);
        }
        if (node.isArray()) {
            return new JsonArray((ArrayNode) node);
        }
        return new Scalar(node);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=TypedValueTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/TypedValue.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/TypedValueTest.java
git commit -m "feat(wf-1): TypedValue sealed interface (Scalar/JsonObject/JsonArray)"
```


### Task WF-1-2: WorkflowContext 重构（String -> TypedValue + 嵌套 resolvePath）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowContext.java`（全量重写）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/WorkflowContextTest.java`（新建）

**Interfaces:**
- Consumes: `TypedValue`（WF-1-1）
- Produces: `WorkflowContext` 内部为 `Map<String, Map<String, TypedValue>>`；`putNode(String, Map<String,TypedValue>)`；`node(String)` 返回 `Map<String,TypedValue>`；`nodeEntries()` 返回 `Iterable<Map.Entry<String, Map<String,TypedValue>>>`；`resolvePath(String)` 返回 `TypedValue`（支持 `nodeId.field.subfield[0].key`）；`resolvePathString(String)` 返回 `String`（兼容旧调用方）。

- [ ] **Step 1: 写失败单测--嵌套取值 + 数组索引**

```java
package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowContextTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void resolvePathSimpleField() {
        var ctx = new WorkflowContext();
        ctx.putNode("start", Map.of("userQuery", TypedValue.scalar("hello")));
        TypedValue v = ctx.resolvePath("start.userQuery");
        assertThat(v.render()).isEqualTo("hello");
    }

    @Test
    void resolvePathNestedObject() {
        var ctx = new WorkflowContext();
        ObjectNode data = om.createObjectNode();
        data.put("id", "exp-001");
        ObjectNode root = om.createObjectNode();
        root.set("data", data);
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(root)));
        TypedValue v = ctx.resolvePath("tool_1.output.data.id");
        assertThat(v.render()).isEqualTo("exp-001");
    }

    @Test
    void resolvePathArrayIndex() {
        var ctx = new WorkflowContext();
        var arr = om.createArrayNode();
        var item0 = om.createObjectNode();
        item0.put("title", "first");
        arr.add(item0);
        var item1 = om.createObjectNode();
        item1.put("title", "second");
        arr.add(item1);
        ObjectNode root = om.createObjectNode();
        root.set("items", arr);
        ctx.putNode("rag_1", Map.of("hits", TypedValue.fromJson(arr)));
        TypedValue v = ctx.resolvePath("rag_1.hits[1].title");
        assertThat(v.render()).isEqualTo("second");
    }

    @Test
    void resolvePathPlanParams() {
        var ctx = new WorkflowContext();
        ctx.putNode("plan", Map.of("status", TypedValue.scalar("approved")));
        TypedValue v = ctx.resolvePath("plan.params.status");
        assertThat(v.render()).isEqualTo("approved");
    }

    @Test
    void resolvePathMissingNodeReturnsScalarNull() {
        var ctx = new WorkflowContext();
        TypedValue v = ctx.resolvePath("missing.output");
        assertThat(v.render()).isEqualTo("null");
    }

    @Test
    void resolvePathStringReturnsRender() {
        var ctx = new WorkflowContext();
        ctx.putNode("tool_1", Map.of("output", TypedValue.scalar("result text")));
        assertThat(ctx.resolvePathString("tool_1.output")).isEqualTo("result text");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=WorkflowContextTest -q 2>&1 | tail -5`
Expected: FAIL - resolvePath 返回 String 而非 TypedValue

- [ ] **Step 3: 重写 WorkflowContext**

```java
package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workflow 运行时变量表 - nodeId -> field -> TypedValue
 */
public class WorkflowContext {

    private static final Pattern ARRAY_INDEX = Pattern.compile("\[([0-9]+)]");

    private final Map<String, Map<String, TypedValue>> nodes = new LinkedHashMap<>();
    private final Map<String, NodeFailureInfo> failures = new LinkedHashMap<>();

    public void putNode(String nodeId, Map<String, TypedValue> outputs) {
        if (nodeId == null || outputs == null) {
            return;
        }
        nodes.put(nodeId, new LinkedHashMap<>(outputs));
    }

    /** 测试与模板解析兼容别名 */
    public void put(String nodeId, Map<String, TypedValue> outputs) {
        putNode(nodeId, outputs);
    }

    public Map<String, TypedValue> node(String nodeId) {
        return nodes.getOrDefault(nodeId, Collections.emptyMap());
    }

    /** 按插入顺序遍历节点输出 */
    public Iterable<Map.Entry<String, Map<String, TypedValue>>> nodeEntries() {
        return nodes.entrySet();
    }

    public void putNodeFailure(String nodeId, String error, int attemptCount) {
        if (nodeId == null) {
            return;
        }
        failures.put(nodeId, new NodeFailureInfo(error, attemptCount));
    }

    public NodeFailureInfo nodeFailure(String nodeId) {
        return failures.get(nodeId);
    }

    /** 解析 nodeId.path[0].field，返回 TypedValue */
    public TypedValue resolvePath(String path) {
        if (path == null || path.isBlank()) {
            return TypedValue.fromJson(new NullNode());
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            return TypedValue.fromJson(new NullNode());
        }
        String nodeId = path.substring(0, dot);
        String remaining = path.substring(dot + 1);
        if ("plan".equals(nodeId) && remaining.startsWith("params.")) {
            String paramKey = remaining.substring("params.".length());
            TypedValue val = node("plan").get(paramKey);
            return val != null ? val : TypedValue.fromJson(new NullNode());
        }
        TypedValue current = node(nodeId).get(firstSegment(remaining));
        if (current == null) {
            return TypedValue.fromJson(new NullNode());
        }
        return descend(current, remaining);
    }

    /** 兼容旧调用方：返回 render() 字符串 */
    public String resolvePathString(String path) {
        return resolvePath(path).render();
    }

    private static String firstSegment(String remaining) {
        int dot = remaining.indexOf('.');
        int bracket = remaining.indexOf('[');
        int end = -1;
        if (dot >= 0 && bracket >= 0) {
            end = Math.min(dot, bracket);
        } else if (dot >= 0) {
            end = dot;
        } else if (bracket >= 0) {
            end = bracket;
        }
        return end >= 0 ? remaining.substring(0, end) : remaining;
    }

    private static TypedValue descend(TypedValue current, String path) {
        if (current == null || path == null || path.isEmpty()) {
            return current != null ? current : TypedValue.fromJson(new NullNode());
        }
        // 去掉已消费的第一段
        String rest = skipFirstSegment(path);
        if (rest.isEmpty()) {
            return current;
        }
        // 处理 [index]
        Matcher m = ARRAY_INDEX.matcher(rest);
        if (rest.startsWith("[")) {
            if (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                if (current instanceof TypedValue.JsonArray arr) {
                    ArrayNode arrNode = arr.node();
                    JsonNode elem = arrNode.has(idx) ? arrNode.get(idx) : new NullNode();
                    return descend(TypedValue.fromJson(elem), rest.substring(m.end()));
                }
            }
            return TypedValue.fromJson(new NullNode());
        }
        // 处理 .field
        String field = firstSegment(rest);
        if (current instanceof TypedValue.JsonObject obj) {
            ObjectNode objNode = obj.node();
            JsonNode child = objNode.has(field) ? objNode.get(field) : new NullNode();
            return descend(TypedValue.fromJson(child), rest);
        }
        return TypedValue.fromJson(new NullNode());
    }

    private static String skipFirstSegment(String path) {
        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        if (dot >= 0 && bracket >= 0) {
            return path.substring(Math.min(dot, bracket));
        }
        if (dot >= 0) {
            return path.substring(dot);
        }
        if (bracket >= 0) {
            return path.substring(bracket);
        }
        return "";
    }

    public record NodeFailureInfo(String error, int attemptCount) {
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=WorkflowContextTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowContext.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/WorkflowContextTest.java
git commit -m "feat(wf-1): WorkflowContext String->TypedValue + nested resolvePath"
```


### Task WF-1-3: TemplateResolver 升级（JSONPath-aware + resolveTyped）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/TemplateResolver.java`（全量重写）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/TemplateResolverTest.java`（新建或更新）

**Interfaces:**
- Consumes: `TypedValue`（WF-1-1）、`WorkflowContext`（WF-1-2，`resolvePath` 返回 `TypedValue`）
- Produces: `resolve(String, WorkflowContext)` 仍返回 `String`（内部调 `resolvePath().render()`）；`resolveTyped(String, WorkflowContext)` 返回 `TypedValue`（新增）。

- [ ] **Step 1: 写失败单测--嵌套模板 + resolveTyped**

```java
package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateResolverTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void resolveSimplePlaceholder() {
        var ctx = new WorkflowContext();
        ctx.putNode("start", Map.of("userQuery", TypedValue.scalar("hello")));
        assertThat(TemplateResolver.resolve("Q: {{start.userQuery}}", ctx)).isEqualTo("Q: hello");
    }

    @Test
    void resolveNestedPath() {
        var ctx = new WorkflowContext();
        ObjectNode data = om.createObjectNode();
        data.put("id", "exp-001");
        ObjectNode root = om.createObjectNode();
        root.set("data", data);
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(root)));
        assertThat(TemplateResolver.resolve("ID={{tool_1.output.data.id}}", ctx)).isEqualTo("ID=exp-001");
    }

    @Test
    void resolveArrayIndex() {
        var ctx = new WorkflowContext();
        var arr = om.createArrayNode();
        var item = om.createObjectNode();
        item.put("title", "first");
        arr.add(item);
        ObjectNode root = om.createObjectNode();
        root.set("items", arr);
        ctx.putNode("rag_1", Map.of("output", TypedValue.fromJson(root)));
        assertThat(TemplateResolver.resolve("{{rag_1.output.items[0].title}}", ctx)).isEqualTo("first");
    }

    @Test
    void resolveJsonObjectRendersToPrettyString() {
        var ctx = new WorkflowContext();
        ObjectNode data = om.createObjectNode();
        data.put("id", "exp-001");
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(data)));
        String result = TemplateResolver.resolve("data={{tool_1.output}}", ctx);
        assertThat(result).startsWith("data={");
        assertThat(result).contains(""id" : "exp-001"");
    }

    @Test
    void resolveTypedReturnsTypedValue() {
        var ctx = new WorkflowContext();
        ObjectNode data = om.createObjectNode();
        data.put("id", "exp-001");
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(data)));
        TypedValue v = TemplateResolver.resolveTyped("tool_1.output.data.id", ctx);
        assertThat(v.render()).isEqualTo("exp-001");
    }

    @Test
    void resolveMissingReturnsEmptyString() {
        var ctx = new WorkflowContext();
        assertThat(TemplateResolver.resolve("{{missing.field}}", ctx)).isEqualTo("null");
    }

    @Test
    void resolveNullTemplateReturnsEmpty() {
        var ctx = new WorkflowContext();
        assertThat(TemplateResolver.resolve(null, ctx)).isEqualTo("");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=TemplateResolverTest -q 2>&1 | tail -5`
Expected: FAIL - resolveTyped 方法不存在 / 旧 resolvePath 返回 String

- [ ] **Step 3: 重写 TemplateResolver**

```java
package com.sunshine.orchestrator.execution;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析节点参数中的 {{nodeId.path[0].field}} 模板，支持嵌套 JSONPath 取值
 */
public final class TemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\{\{([^}]+)}}");

    private TemplateResolver() {
    }

    /** 模板解析：{{nodeId.path}} 替换为 resolvePath().render() */
    public static String resolve(String template, WorkflowContext ctx) {
        if (template == null || template.isBlank() || ctx == null) {
            return template != null ? template : "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = ctx.resolvePath(matcher.group(1).trim()).render();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 直接取结构化值（供 tool inputs 等需要 TypedValue 的场景） */
    public static TypedValue resolveTyped(String path, WorkflowContext ctx) {
        if (path == null || path.isBlank() || ctx == null) {
            return TypedValue.fromJson(new com.fasterxml.jackson.databind.node.NullNode());
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            trimmed = trimmed.substring(2, trimmed.length() - 2).trim();
        }
        return ctx.resolvePath(trimmed);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=TemplateResolverTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/TemplateResolver.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/TemplateResolverTest.java
git commit -m "feat(wf-1): TemplateResolver JSONPath-aware + resolveTyped"
```


### Task WF-1-4: NodeSpec 升级 + InputBinding + NodeResult 升级

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/InputBinding.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/VarType.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/NodeSpec.java`（全量重写）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/NodeResult.java`（outputs 类型升级）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/NodeHandler.java`（run 签名不变，但 NodeSpec/NodeResult 变了）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/NodeResultTest.java`（新建）

**Interfaces:**
- Consumes: `TypedValue`（WF-1-1）
- Produces: `NodeSpec(params: Map<String,Object>, inputs: List<InputBinding>, outputs: String)`；`InputBinding(name, source, type, required)`；`VarType` 枚举；`NodeResult.outputs: Map<String,TypedValue>` + 兼容工厂 `okString(Map<String,String>)`。

- [ ] **Step 1: 写失败单测--NodeResult TypedValue outputs**

```java
package com.sunshine.orchestrator.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NodeResultTest {

    @Test
    void okTypedOutputs() {
        var outputs = Map.of("output", TypedValue.scalar("result"));
        NodeResult r = NodeResult.ok(outputs);
        assertThat(r.success()).isTrue();
        assertThat(r.safeOutputs().get("output").render()).isEqualTo("result");
    }

    @Test
    void okStringOutputsCompatible() {
        var outputs = Map.of("output", "result text", "tool", "sdk__test");
        NodeResult r = NodeResult.okString(outputs);
        assertThat(r.success()).isTrue();
        assertThat(r.safeOutputs().get("output").render()).isEqualTo("result text");
        assertThat(r.safeOutputs().get("tool").render()).isEqualTo("sdk__test");
    }

    @Test
    void failReturnsErrorOutput() {
        NodeResult r = NodeResult.fail("missing param");
        assertThat(r.success()).isFalse();
        assertThat(r.safeOutputs().get("error").render()).isEqualTo("missing param");
    }

    @Test
    void safeOutputsEmptyOnNull() {
        NodeResult r = new NodeResult(true, null, java.util.List.of(), java.util.List.of());
        assertThat(r.safeOutputs()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=NodeResultTest -q 2>&1 | tail -5`
Expected: FAIL - NodeResult.outputs 仍是 Map<String,String>

- [ ] **Step 3: 创建 VarType 枚举**

```java
package com.sunshine.orchestrator.execution;

/** 输入参数类型声明 */
public enum VarType {
    STRING, NUMBER, BOOLEAN, OBJECT, ARRAY
}
```

- [ ] **Step 4: 创建 InputBinding record**

```java
package com.sunshine.orchestrator.execution;

/** 节点显式输入绑定：参数名 -> 上游变量引用 + 类型 + 必填 */
public record InputBinding(
        String name,
        String source,
        VarType type,
        boolean required
) {
    public InputBinding {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("InputBinding.name 不可为空");
        }
        if (source == null) {
            source = "";
        }
        if (type == null) {
            type = VarType.STRING;
        }
    }

    /** 简化构造（默认 STRING + 非必填） */
    public InputBinding(String name, String source) {
        this(name, source, VarType.STRING, false);
    }
}
```

- [ ] **Step 5: 重写 NodeSpec**

```java
package com.sunshine.orchestrator.execution;

import java.util.List;
import java.util.Map;

/**
 * 单个 DAG 节点定义（params 已解析模板；inputs 为显式输入绑定；displayName 供 Timeline / Plan 图展示）
 */
public record NodeSpec(
        String id,
        String type,
        Map<String, Object> params,
        List<InputBinding> inputs,
        String displayName
) {
    public NodeSpec(String id, String type, Map<String, Object> params) {
        this(id, type, params, List.of(), null);
    }

    public NodeSpec(String id, String type, Map<String, Object> params, String displayName) {
        this(id, type, params, List.of(), displayName);
    }
}
```

- [ ] **Step 6: 重写 NodeResult**

```java
package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点执行结果 - 写入 WorkflowContext，并可选携带 Timeline token
 */
public record NodeResult(
        boolean success,
        Map<String, TypedValue> outputs,
        List<StreamToken> timelineTokens,
        List<StreamToken> contentTokens
) {
    public static NodeResult ok(Map<String, TypedValue> outputs) {
        return new NodeResult(true, outputs, List.of(), List.of());
    }

    public static NodeResult ok(Map<String, TypedValue> outputs, List<StreamToken> timelineTokens) {
        return new NodeResult(true, outputs,
                timelineTokens != null ? timelineTokens : List.of(), List.of());
    }

    public static NodeResult withContent(Map<String, TypedValue> outputs, List<StreamToken> contentTokens) {
        return new NodeResult(true, outputs, List.of(),
                contentTokens != null ? contentTokens : List.of());
    }

    /** 兼容旧调用方：String outputs 自动转 Scalar */
    public static NodeResult okString(Map<String, String> outputs) {
        return new NodeResult(true, toTyped(outputs), List.of(), List.of());
    }

    public static NodeResult fail(String message) {
        return new NodeResult(false, Map.of("error", TypedValue.scalar(message)), List.of(), List.of());
    }

    public Map<String, TypedValue> safeOutputs() {
        return outputs != null ? outputs : Collections.emptyMap();
    }

    /** 兼容旧调用方：取 output 字段的 render 字符串 */
    public String outputString() {
        TypedValue v = safeOutputs().get("output");
        return v != null ? v.render() : "";
    }

    private static Map<String, TypedValue> toTyped(Map<String, String> stringOutputs) {
        if (stringOutputs == null) {
            return Map.of();
        }
        Map<String, TypedValue> typed = new LinkedHashMap<>();
        stringOutputs.forEach((k, v) -> typed.put(k, TypedValue.scalar(v)));
        return typed;
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=NodeResultTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 8: 编译检查（其他文件会因 NodeSpec/NodeResult 签名变化报错，暂不修，WF-2 逐个修）**

Run: `mvn -pl orchestrator -am compile -q 2>&1 | grep -c "error:" `
Expected: 多个编译错误（来自 ToolNodeHandler/RagNodeHandler 等），这是预期的，WF-2 逐个修复

- [ ] **Step 9: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/InputBinding.java orchestrator/src/main/java/com/sunshine/orchestrator/execution/VarType.java orchestrator/src/main/java/com/sunshine/orchestrator/execution/NodeSpec.java orchestrator/src/main/java/com/sunshine/orchestrator/execution/NodeResult.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/NodeResultTest.java
git commit -m "feat(wf-1): NodeSpec/NodeResult TypedValue + InputBinding + VarType"
```


---

## WF-2 - 节点 Handler 适配

**出口闸门**：编译绿 + 全部 Handler 单测通过 + 现有 workflow 标杆 e2e 跑通。

### Task WF-2-1: WorkflowNodeRunner.resolveParams 适配 TypedValue

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/workflow/WorkflowNodeRunner.java:406-424`（resolveParams 方法）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/workflow/WorkflowNodeFinalizer.java:88-108`（finalizeNode 中 putNode 调用）

**Interfaces:**
- Consumes: `NodeSpec.params: Map<String,Object>`（WF-1-4）、`NodeResult.outputs: Map<String,TypedValue>`（WF-1-4）、`WorkflowContext.putNode(Map<String,TypedValue>)`（WF-1-2）
- Produces: resolveParams 对 String 值做模板解析，非 String 值原样保留；finalizeNode 直接传 TypedValue outputs 给 putNode。

- [ ] **Step 1: 修改 WorkflowNodeRunner.resolveParams**

将原方法（第 406-424 行）替换为：

```java
private NodeSpec resolveParams(NodeSpec spec, WorkflowContext ctx, WorkflowDefinition def, boolean planWorkflow) {
    Map<String, Object> resolved = new LinkedHashMap<>();
    if (spec.params() != null) {
        spec.params().forEach((k, v) -> {
            if (planWorkflow && "prompt".equals(k) && v instanceof String s) {
                resolved.put(k, upstreamOutputResolver.resolvePrompt(s, ctx, def));
            } else if (v instanceof String s) {
                resolved.put(k, TemplateResolver.resolve(s, ctx));
            } else {
                resolved.put(k, v);
            }
        });
    }
    return new NodeSpec(spec.id(), spec.type(), resolved, spec.inputs(), spec.displayName());
}
```

- [ ] **Step 2: 修改 WorkflowNodeFinalizer.finalizeNode**

将原第 88 行 `Map<String, String> outs = result.safeOutputs();` 改为：
```java
Map<String, TypedValue> outs = result.safeOutputs();
```

将原第 103 行 `wfCtx.putNode(nodeId, outs);` 保持不变（putNode 已接受 `Map<String,TypedValue>`）。

将其余引用 `outs.get(...)` 的地方改为 `outs.get(...).render()` 或视场景处理。需检查 finalizeNode 方法内所有 `outs.get` 调用，改为返回 TypedValue 后取 render()。

- [ ] **Step 3: 编译检查**

Run: `mvn -pl orchestrator -am compile -q 2>&1 | grep -c "error:" `
Expected: 仍有错误（来自各 Handler），但 WorkflowNodeRunner/Finalizer 本身无错误

- [ ] **Step 4: Commit（编译未全绿，但本 Task 改动自洽）**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/workflow/WorkflowNodeRunner.java orchestrator/src/main/java/com/sunshine/orchestrator/execution/workflow/WorkflowNodeFinalizer.java
git commit -m "refactor(wf-2): WorkflowNodeRunner/Finalizer adapt TypedValue outputs"
```


### Task WF-2-2: ToolNodeHandler 重写（inputs 绑定 + 结构化输出 + 废弃 extract）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/ToolNodeHandler.java`（全量重写）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/client/ToolManagerClient.java:120-138`（新增 invokeJsonMono 方法）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/ToolNodeHandlerTest.java`（新建或更新）

**Interfaces:**
- Consumes: `InputBinding`（WF-1-4）、`TypedValue`（WF-1-1）、`TemplateResolver.resolveTyped`（WF-1-3）、`NodeSpec.inputs`（WF-1-4）
- Produces: ToolNodeHandler 从 `spec.inputs()` 读取绑定，按 `VarType` 校验，构造 `Map<String,Object>` invokeParams 调 `toolManagerClient.invokeJsonMono`；输出 `output`(完整 JSON TypedValue) + `tool` + `summary`。废弃 `RESERVED_INVOKE_KEYS` 和 `appendParsedOutputs`。
- ToolManagerClient 新增 `invokeJsonMono(String name, Map<String,Object> params, String userId, String tenantId) -> Mono<JsonNode>`

- [ ] **Step 1: 写失败单测--inputs 绑定 + 结构化输出**

```java
package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.execution.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolNodeHandlerTest {

    @Mock private ToolManagerClient toolManagerClient;
    @Mock private com.sunshine.orchestrator.catalog.ToolCatalogService toolCatalogService;
    @Mock private com.sunshine.orchestrator.audit.ToolAuditService toolAuditService;
    @Mock private com.sunshine.orchestrator.hitl.HitlConfirmationService hitlConfirmationService;
    @InjectMocks private ToolNodeHandler handler;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void inputsBindingResolvesAndInvokesWithStructuredParams() {
        var ctx = new WorkflowContext();
        ObjectNode toolOutput = om.createObjectNode();
        toolOutput.put("id", "exp-001");
        toolOutput.put("amount", 100);
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(toolOutput)));

        var spec = new NodeSpec("tool_2", "tool",
                Map.of("tool", "sdk__finance__get_detail"),
                List.of(new InputBinding("expenseId", "{{tool_1.output.id}}", VarType.STRING, true)),
                "查询报销详情");

        when(toolManagerClient.invokeJsonMono(eq("sdk__finance__get_detail"), anyMap(), any(), any()))
                .thenReturn(Mono.of(toolOutput));

        NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("output")).isInstanceOf(TypedValue.JsonObject.class);
        assertThat(result.safeOutputs().get("output").toJson().get("id").asText()).isEqualTo("exp-001");
        verify(toolManagerClient).invokeJsonMono(eq("sdk__finance__get_detail"),
                argThat(m -> "exp-001".equals(m.get("expenseId"))), any(), any());
    }

    @Test
    void missingRequiredInputFailsNode() {
        var ctx = new WorkflowContext();
        var spec = new NodeSpec("tool_1", "tool",
                Map.of("tool", "sdk__test__tool"),
                List.of(new InputBinding("id", "{{missing.output}}", VarType.STRING, true)),
                "测试");

        NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.safeOutputs().get("error").render()).contains("缺少必填参数");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=ToolNodeHandlerTest -q 2>&1 | tail -5`
Expected: FAIL - invokeJsonMono 方法不存在

- [ ] **Step 3: ToolManagerClient 新增 invokeJsonMono**

在 `ToolManagerClient.java` 第 138 行后新增方法。复用现有 WebClient 调用模式，body 传 `Map<String,Object>`，返回 `Mono<JsonNode>`。解析失败时 fallback 为 `ObjectNode{output: text}`。失败时返回含 `error` 字段的 JsonNode（保持 `isInvokeFailureResult` 兼容判断）。

- [ ] **Step 4: 重写 ToolNodeHandler**

核心改动：
1. 删除 `RESERVED_INVOKE_KEYS` 和 `appendParsedOutputs`
2. `run()` 方法从 `spec.inputs()` 读取绑定，调 `resolveInvokeParams(inputs, ctx)` 构造 `Map<String,Object>`
3. 必填参数缺失返回 `NodeResult.fail("缺少必填参数: " + binding.name())`
4. 调 `toolManagerClient.invokeJsonMono` 获取 `JsonNode` 结果
5. 输出 `output` = `TypedValue.fromJson(result)`（结构化）、`tool` = scalar、`summary` = scalar
6. HITL 逻辑保持不变，但 invokeParams 类型改为 `Map<String,Object>`

`resolveInvokeParams` 逻辑：
```java
private Map<String, Object> resolveInvokeParams(List<InputBinding> inputs, WorkflowContext ctx) {
    Map<String, Object> params = new LinkedHashMap<>();
    if (inputs == null || inputs.isEmpty()) return params;
    for (InputBinding binding : inputs) {
        TypedValue val = TemplateResolver.resolveTyped(binding.source(), ctx);
        if (binding.required() && isNullTypedValue(val)) return null;
        params.put(binding.name(), val.toJson());
    }
    return params;
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=ToolNodeHandlerTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/ToolNodeHandler.java orchestrator/src/main/java/com/sunshine/orchestrator/client/ToolManagerClient.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/ToolNodeHandlerTest.java
git commit -m "feat(wf-2): ToolNodeHandler inputs binding + structured output (drop extract)"
```


### Task WF-2-3: RagNodeHandler 适配（结构化 hits 输出）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/RagNodeHandler.java:93-107`（buildOkResult / buildEmptyResult）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/RagNodeHandlerTest.java`（新建或更新）

**Interfaces:**
- Consumes: `TypedValue`（WF-1-1）、`NodeResult.ok(Map<String,TypedValue>)`（WF-1-4）
- Produces: outputs 新增 `hits`（结构化 ArrayNode TypedValue）；保留 `output`（格式化文本 scalar）、`hitCount`（scalar）。

- [ ] **Step 1: 写失败单测--结构化 hits 输出**

```java
@Test
void buildOkResultContainsStructuredHits() {
    List<RagClient.RagHit> hits = List.of(
            new RagClient.RagHit("doc1", "标题1", "内容1", 0.9, Map.of()),
            new RagClient.RagHit("doc2", "标题2", "内容2", 0.8, Map.of()));
    NodeResult result = RagNodeHandler.buildOkResultForTest(hits);
    assertThat(result.success()).isTrue();
    assertThat(result.safeOutputs().get("hits")).isInstanceOf(TypedValue.JsonArray.class);
    assertThat(result.safeOutputs().get("hits").toJson().size()).isEqualTo(2);
    assertThat(result.safeOutputs().get("hitCount").render()).isEqualTo("2");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=RagNodeHandlerTest -q 2>&1 | tail -5`
Expected: FAIL - hits 字段不存在

- [ ] **Step 3: 修改 buildOkResult / buildEmptyResult**

将 `Map<String, String> outputs` 改为 `Map<String, TypedValue> outputs`，新增 `hits` 字段：

```java
private static NodeResult buildOkResult(List<RagClient.RagHit> results) {
    Map<String, TypedValue> outputs = new LinkedHashMap<>();
    outputs.put("output", TypedValue.scalar(RagContextFormatter.formatAgentContext(results)));
    outputs.put("hits", TypedValue.fromJson(buildHitsArray(results)));
    outputs.put("hitCount", TypedValue.scalar(results.size()));
    outputs.put("detail", TypedValue.scalar(WorkflowNodeCompletionLabels.hitCount(String.valueOf(results.size()))));
    return NodeResult.ok(outputs);
}

private static JsonNode buildHitsArray(List<RagClient.RagHit> results) {
    var om = new ObjectMapper();
    var arr = om.createArrayNode();
    for (RagClient.RagHit hit : results) {
        var obj = arr.addObject();
        obj.put("docId", hit.docId());
        obj.put("title", hit.title());
        obj.put("content", hit.content());
        obj.put("score", hit.score());
    }
    return arr;
}
```

`buildEmptyResult` 同理：`hits` = 空数组，`hitCount` = "0"。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=RagNodeHandlerTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/RagNodeHandler.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/RagNodeHandlerTest.java
git commit -m "feat(wf-2): RagNodeHandler structured hits output"
```

### Task WF-2-4: JoinNodeHandler 重写（聚合策略）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/JoinNodeHandler.java`（全量重写）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/JoinNodeHandlerTest.java`（新建）

**Interfaces:**
- Consumes: `TypedValue`（WF-1-1）、`NodeSpec.params`（mergeStrategy / branches）
- Produces: 按 `mergeStrategy`（collect/merge/first/last，默认 collect）聚合 `branches` 节点的 output 字段，输出 `output`（聚合 TypedValue）+ `status`（scalar "joined"）。

- [ ] **Step 1: 写失败单测--collect 策略**

```java
@Test
void collectStrategyAggregatesBranchesToArray() {
    var ctx = new WorkflowContext();
    ctx.putNode("branch_a", Map.of("output", TypedValue.scalar("resultA")));
    ctx.putNode("branch_b", Map.of("output", TypedValue.scalar("resultB")));

    var spec = new NodeSpec("join_1", "join",
            Map.of("mergeStrategy", "collect", "branches", "branch_a,branch_b"), "汇合");

    NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();
    assertThat(result.success()).isTrue();
    assertThat(result.safeOutputs().get("output")).isInstanceOf(TypedValue.JsonArray.class);
    assertThat(result.safeOutputs().get("output").toJson().size()).isEqualTo(2);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=JoinNodeHandlerTest -q 2>&1 | tail -5`
Expected: FAIL - 旧实现只返回 status=joined

- [ ] **Step 3: 重写 JoinNodeHandler**

```java
@Slf4j
@Component
public class JoinNodeHandler implements NodeHandler {

    @Override
    public String type() { return WorkflowNodeType.JOIN.id(); }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, Object> params = spec.params() != null ? spec.params() : Map.of();
        String strategy = params.getOrDefault("mergeStrategy", "collect").toString();
        List<String> branches = parseBranches(params.get("branches"));
        TypedValue merged = aggregate(strategy, branches, ctx);
        Map<String, TypedValue> outputs = new LinkedHashMap<>();
        outputs.put("output", merged);
        outputs.put("status", TypedValue.scalar("joined"));
        return Mono.just(NodeResult.ok(outputs));
    }

    private TypedValue aggregate(String strategy, List<String> branches, WorkflowContext ctx) {
        List<TypedValue> outputs = branches.stream()
                .map(id -> ctx.node(id).get("output"))
                .filter(java.util.Objects::nonNull)
                .toList();
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        return switch (strategy) {
            case "first" -> outputs.isEmpty() ? TypedValue.fromJson(om.nullNode()) : outputs.get(0);
            case "last" -> outputs.isEmpty() ? TypedValue.fromJson(om.nullNode()) : outputs.get(outputs.size() - 1);
            case "merge" -> mergeObjects(outputs, om);
            default -> collectArray(outputs, om);
        };
    }

    private TypedValue collectArray(List<TypedValue> outputs, ObjectMapper om) {
        var arr = om.createArrayNode();
        for (TypedValue v : outputs) arr.add(v.toJson());
        return TypedValue.fromJson(arr);
    }

    private TypedValue mergeObjects(List<TypedValue> outputs, ObjectMapper om) {
        var merged = om.createObjectNode();
        for (TypedValue v : outputs) {
            JsonNode json = v.toJson();
            if (json.isObject()) merged.setAll((ObjectNode) json);
        }
        return TypedValue.fromJson(merged);
    }

    private List<String> parseBranches(Object raw) {
        if (raw == null) return List.of();
        return java.util.Arrays.stream(raw.toString().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=JoinNodeHandlerTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/JoinNodeHandler.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/JoinNodeHandlerTest.java
git commit -m "feat(wf-2): JoinNodeHandler aggregate strategies (collect/merge/first/last)"
```


### Task WF-2-5: EdgeConditionEvaluator 升级（新增算子 + JSONPath）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/EdgeConditionEvaluator.java`（全量重写）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/EdgeConditionEvaluatorTest.java`（新建或更新）

**Interfaces:**
- Consumes: `TemplateResolver.resolve`（WF-1-3，已支持嵌套路径）
- Produces: 新增算子 `gt/lt/gte/lte/in/not_in`；right 可为字面量 JSON 数组。

- [ ] **Step 1: 写失败单测--新增算子**

```java
@Test
void gtOperatorNumericCompare() {
    var ctx = new WorkflowContext();
    ctx.putNode("rag_1", Map.of("hitCount", TypedValue.scalar(5)));
    var cond = new PlanEdgeCondition("{{rag_1.hitCount}}", "gt", "3");
    assertThat(EdgeConditionEvaluator.matches(cond, ctx)).isTrue();
}

@Test
void inOperatorEnumCheck() {
    var ctx = new WorkflowContext();
    ctx.putNode("extract_1", Map.of("result", TypedValue.scalar("approved")));
    var cond = new PlanEdgeCondition("{{extract_1.result}}", "in", "[\"approved\",\"pending\"]");
    assertThat(EdgeConditionEvaluator.matches(cond, ctx)).isTrue();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=EdgeConditionEvaluatorTest -q 2>&1 | tail -5`
Expected: FAIL - default 分支返回 false

- [ ] **Step 3: 重写 EdgeConditionEvaluator**

在原有 `empty/not_empty/contains/eq` 基础上新增：

```java
case "gt" -> toDouble(left) > toDouble(right);
case "lt" -> toDouble(left) < toDouble(right);
case "gte" -> toDouble(left) >= toDouble(right);
case "lte" -> toDouble(left) <= toDouble(right);
case "in" -> parseJsonArray(right).contains(left);
case "not_in" -> !parseJsonArray(right).contains(left);
```

`toDouble` 辅助方法：`Double.parseDouble(left.strip())`，异常返回 `Double.NEGATIVE_INFINITY`。
`parseJsonArray` 辅助方法：用 ObjectMapper 解析 `["a","b"]` 字符串为 `List<String>`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=EdgeConditionEvaluatorTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/EdgeConditionEvaluator.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/EdgeConditionEvaluatorTest.java
git commit -m "feat(wf-2): EdgeConditionEvaluator add gt/lt/in/not_in operators"
```

### Task WF-2-6: 其他 Handler 适配（Answer/Agent/Llm/Start/Gateway）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/AnswerNodeHandler.java`（passThrough 中 outs 类型适配）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/AgentNodeHandler.java`（outputs 改 TypedValue）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/StartNodeHandler.java`（outputs 改 TypedValue）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/WorkflowLlmStreamSupport.java`（outputs 改 TypedValue）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/UpstreamOutputResolver.java`（resolvePath -> resolvePathString）

**Interfaces:**
- Consumes: 全部 WF-1 类型升级
- Produces: 所有 Handler 的 `NodeResult.ok(Map<String,String>)` 改为 `NodeResult.ok(Map<String,TypedValue>)` 或 `NodeResult.okString(Map<String,String>)`（兼容工厂）。

- [ ] **Step 1: 逐个 Handler 适配**

每个 Handler 的改动模式一致：
1. `Map<String, String> outputs` -> `Map<String, TypedValue> outputs`
2. `outputs.put("key", "value")` -> `outputs.put("key", TypedValue.scalar("value"))`
3. `NodeResult.ok(outputs)` 保持不变（ok 已接受 `Map<String,TypedValue>`)
4. 读取上游值处：`ctx.resolvePath(path)` -> `ctx.resolvePath(path).render()` 或 `ctx.resolvePathString(path)`

**AnswerNodeHandler** 特殊：`passThrough` 方法中 `ctx.nodeEntries()` 遍历改为 `Map<String,TypedValue>`，取 `answer`/`output` 字段的 render()。

**UpstreamOutputResolver** 特殊：`ctx.resolvePath(nodeId + ".output")` 原返回 String，现返回 TypedValue，需调 `.render()`。

- [ ] **Step 2: 编译验证**

Run: `mvn -pl orchestrator -am compile -q 2>&1 | grep -c "error:"`
Expected: 0（全绿）

- [ ] **Step 3: 运行全部 workflow 单测**

Run: `mvn -pl orchestrator test -Dtest="*Workflow*,*NodeHandler*,*TemplateResolver*,*WorkflowContext*" -q 2>&1 | tail -10`
Expected: PASS（如有旧测试断言 String outputs，更新为 `.render()` 断言）

- [ ] **Step 4: Commit**

```bash
git add -A orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/ orchestrator/src/main/java/com/sunshine/orchestrator/execution/UpstreamOutputResolver.java orchestrator/src/test/
git commit -m "refactor(wf-2): adapt all NodeHandlers to TypedValue outputs"
```

### Task WF-2-7: WorkflowContextCodec checkpoint 序列化升级

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowContextCodec.java`（全量重写）

**Interfaces:**
- Consumes: `WorkflowContext`（WF-1-2，nodeEntries 返回 `Map<String,TypedValue>`）
- Produces: `toJson` 序列化 TypedValue 为 JSON（`{nodes: {nodeId: {field: jsonValue}}}`）；`fromJson` 反序列化时用 `TypedValue.fromJson` 重建。

- [ ] **Step 1: 重写 WorkflowContextCodec**

`toJson`：遍历 `ctx.nodeEntries()`，每个 TypedValue 用 `toJson()` 取 JsonNode，整体序列化为 JSON 字符串。

`fromJson`：解析 JSON 为 `Map<String, Map<String, JsonNode>>`，每项用 `TypedValue.fromJson(jsonNode)` 重建，调 `ctx.putNode`。

- [ ] **Step 2: 写 checkpoint 往返单测**

```java
@Test
void checkpointRoundTripPreservesStructuredData() {
    var ctx = new WorkflowContext();
    ObjectNode data = new ObjectMapper().createObjectNode();
    data.put("id", "exp-001");
    ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(data)));

    String json = WorkflowContextCodec.toJson(ctx);
    WorkflowContext restored = WorkflowContextCodec.fromJson(json);

    TypedValue v = restored.resolvePath("tool_1.output.id");
    assertThat(v.render()).isEqualTo("exp-001");
}
```

- [ ] **Step 3: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=WorkflowContextCodecTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowContextCodec.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/WorkflowContextCodecTest.java
git commit -m "refactor(wf-2): WorkflowContextCodec TypedValue JSON serialization"
```


---

## WF-3 - 新增数据转换节点

**出口闸门**：新节点单测通过 + 简单 workflow 集成测试通过。

### Task WF-3-1: WorkflowNodeType 枚举新增节点类型

**Files:**
- Modify: `common/sunshine-common/src/main/java/com/sunshine/common/workflow/WorkflowNodeType.java:10-21`（枚举值）+ 静态方法集合

**Interfaces:**
- Produces: `VARIABLE_ASSIGNMENT("variable-assignment")` + `PARAMETER_EXTRACTOR("parameter-extractor")` 枚举值；更新 `studioTypeIds()`、`businessTypeIds()`、`outputTypeIds()`、`loopBodyTypeIds()` 包含新类型。

- [ ] **Step 1: 新增枚举值**

在第 21 行 `LOOP("loop")` 后新增：
```java
VARIABLE_ASSIGNMENT("variable-assignment"),
PARAMETER_EXTRACTOR("parameter-extractor");
```

- [ ] **Step 2: 更新静态方法集合**

`studioTypeIds()`：追加 `VARIABLE_ASSIGNMENT.id, PARAMETER_EXTRACTOR.id`
`businessTypeIds()`：追加 `VARIABLE_ASSIGNMENT.id, PARAMETER_EXTRACTOR.id`
`outputTypeIds()`：追加 `VARIABLE_ASSIGNMENT.id, PARAMETER_EXTRACTOR.id`
`loopBodyTypeIds()`：追加 `VARIABLE_ASSIGNMENT.id`（参数提取不进 loop body）

- [ ] **Step 3: 编译验证**

Run: `mvn -pl common/sunshine-common -am compile -q 2>&1 | tail -3`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add common/sunshine-common/src/main/java/com/sunshine/common/workflow/WorkflowNodeType.java
git commit -m "feat(wf-3): add VARIABLE_ASSIGNMENT + PARAMETER_EXTRACTOR node types"
```

### Task WF-3-2: VariableAssignmentNodeHandler

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/VariableAssignmentNodeHandler.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/VariableAssignmentNodeHandlerTest.java`

**Interfaces:**
- Consumes: `NodeHandler`、`TypedValue`、`TemplateResolver`、`WorkflowNodeType.VARIABLE_ASSIGNMENT`
- Produces: 从 `params.assignments`（JSON 数组）解析 assignment 列表，每个解析 source 模板，按 type 校验，输出每个 assignment name 为字段。

- [ ] **Step 1: 写失败单测**

```java
@Test
void assignmentsResolvedToOutputs() {
    var ctx = new WorkflowContext();
    var data = new ObjectMapper().createObjectNode();
    data.put("id", "exp-001");
    data.put("total", 100);
    ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(data)));

    var spec = new NodeSpec("var_1", "variable-assignment",
            Map.of("assignments", """
                [{"name":"expenseId","source":"{{tool_1.output.id}}","type":"string"},
                 {"name":"totalAmount","source":"{{tool_1.output.total}}","type":"number"}]
                """),
            "提取变量");

    NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();
    assertThat(result.success()).isTrue();
    assertThat(result.safeOutputs().get("expenseId").render()).isEqualTo("exp-001");
    assertThat(result.safeOutputs().get("totalAmount").render()).isEqualTo("100");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=VariableAssignmentNodeHandlerTest -q 2>&1 | tail -5`
Expected: FAIL - handler 不存在

- [ ] **Step 3: 实现 VariableAssignmentNodeHandler**

```java
@Slf4j
@Component
public class VariableAssignmentNodeHandler implements NodeHandler {

    @Override
    public String type() { return WorkflowNodeType.VARIABLE_ASSIGNMENT.id(); }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, Object> params = spec.params() != null ? spec.params() : Map.of();
        String assignmentsJson = params.getOrDefault("assignments", "[]").toString();
        Map<String, TypedValue> outputs = new LinkedHashMap<>();
        try {
            var om = new ObjectMapper();
            var arr = om.readTree(assignmentsJson);
            for (JsonNode item : arr) {
                String name = item.get("name").asText();
                String source = item.get("source").asText();
                TypedValue val = TemplateResolver.resolveTyped(source, ctx);
                outputs.put(name, val);
            }
        } catch (Exception e) {
            return Mono.just(NodeResult.fail("assignments 解析失败: " + e.getMessage()));
        }
        return Mono.just(NodeResult.ok(outputs));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=VariableAssignmentNodeHandlerTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/VariableAssignmentNodeHandler.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/VariableAssignmentNodeHandlerTest.java
git commit -m "feat(wf-3): VariableAssignmentNodeHandler (set/transform variables)"
```

### Task WF-3-3: ParameterExtractorNodeHandler

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/ParameterExtractorNodeHandler.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/ParameterExtractorNodeHandlerTest.java`

**Interfaces:**
- Consumes: `NodeHandler`、`LlmGatewayClient`（已有）、`PromptCatalogHolder`（Catalog `parameter-extractor.template`）、`TemplateResolver`
- Produces: 从 `params.input` 取上游文本，`params.instruction` + `params.schema` 构造 LLM prompt，调用 llm-gateway 输出 JSON，按 schema 解析为各字段输出。

- [ ] **Step 1: 写失败单测--LLM 提取结构化参数**

```java
@Test
void extractsStructuredParametersFromText() {
    var ctx = new WorkflowContext();
    ctx.putNode("agent_1", Map.of("output", TypedValue.scalar("审批人张三同意了报销，金额200元")));

    when(llmGatewayClient.chat(any(), any())).thenReturn(Mono.just("""
        {"approver":"张三","result":"approved","comment":"同意报销"}
        """));

    var spec = new NodeSpec("extract_1", "parameter-extractor",
            Map.of("input", "{{agent_1.output}}",
                   "instruction", "提取审批人、结果、意见",
                   "schema", """
                       {"approver":{"type":"string"},"result":{"type":"string","enum":["approved","rejected"]},"comment":{"type":"string"}}
                       """),
            "参数提取");

    NodeResult result = handler.run(spec, ctx, mock(ExecutionStreamContext.class)).block();
    assertThat(result.success()).isTrue();
    assertThat(result.safeOutputs().get("approver").render()).isEqualTo("张三");
    assertThat(result.safeOutputs().get("result").render()).isEqualTo("approved");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=ParameterExtractorNodeHandlerTest -q 2>&1 | tail -5`
Expected: FAIL - handler 不存在

- [ ] **Step 3: 实现 ParameterExtractorNodeHandler**

核心逻辑：
1. 从 `params.input` 解析上游文本（TemplateResolver）
2. 从 Catalog 取 `parameter-extractor.template`，注入 instruction + schema + input
3. 调 `llmGatewayClient.chat()` 获取 JSON 响应
4. 用 ObjectMapper 解析 JSON，按 schema 字段名逐个提取为 `TypedValue.scalar`
5. 同时输出 `output` = 完整 JSON TypedValue

Catalog prompt 模板在 WF-4-3 中新增到 `17-sunshine-prompt-manager.sql`（`parameter-extractor.template`）。本任务的测试用 mock `PromptCatalogHolder` 返回固定模板，不依赖真实 Catalog；实现中若 Catalog 缺失则 fail（禁止 Java 硬编码 prompt 兜底，遵循 SSOT）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=ParameterExtractorNodeHandlerTest -q 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/ParameterExtractorNodeHandler.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/handler/ParameterExtractorNodeHandlerTest.java
git commit -m "feat(wf-3): ParameterExtractorNodeHandler (LLM extract structured params)"
```


---

## WF-4 - DB 与种子数据 + PlanAnswerPromptAssembler 重写

**出口闸门**：标杆 workflow 从 DB 加载并执行成功 + live 验收。

### Task WF-4-1: PlanNode + PlanJsonParser 适配新结构（inputs 字段）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanNode.java`（新增 inputs 字段）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanJsonParser.java:51-71`（parseNodes 解析 inputs）

**Interfaces:**
- Consumes: `InputBinding`（WF-1-4）、`VarType`（WF-1-4）
- Produces: `PlanNode` 新增 `List<InputBinding> inputs` 字段；`PlanJsonParser.parseNodes` 解析 JSON 的 `inputs` 数组为 `List<InputBinding>`。

- [ ] **Step 1: PlanNode 新增 inputs 字段**

```java
public record PlanNode(
        String id,
        String type,
        Map<String, Object> params,
        List<InputBinding> inputs,
        String displayName,
        String parentId
) {
    // 简化构造器保留
}
```

- [ ] **Step 2: PlanJsonParser.parseNodes 解析 inputs**

在 `parseNodes` 方法（第 51-71 行）中，解析每个 node 的 `inputs` JSON 数组：

```java
List<InputBinding> inputs = parseInputs(node.get("inputs"));
```

`parseInputs` 方法：遍历 JSON 数组，每项取 `name`/`source`/`type`/`required`，构造 `InputBinding`。

- [ ] **Step 3: 编译 + 单测**

Run: `mvn -pl orchestrator test -Dtest=PlanJsonParserTest -q 2>&1 | tail -5`
Expected: PASS（更新已有测试，新增 inputs 断言）

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanNode.java orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanJsonParser.java
git commit -m "feat(wf-4): PlanNode + PlanJsonParser parse inputs field"
```

### Task WF-4-2: PlanAnswerPromptAssembler 重写（废弃全量注入）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanAnswerPromptAssembler.java`（全量重写）

**Interfaces:**
- Consumes: `PromptCatalogHolder`（Catalog `answer.template`）
- Produces: 删除 `buildUpstreamBlock` 逻辑，只做 Catalog 模板套用（不再自动拼接全部上游 `{{nodeId.output}}`）。Planner 产出的 answer prompt 中显式写引用。

- [ ] **Step 1: 重写 PlanAnswerPromptAssembler**

删除 `buildUpstreamBlock` 方法。`buildPrompt` 简化为：
1. 取 Catalog `answer.template`
2. 如果模板含 `{{plan.upstream}}`，替换为空串（Planner 自己写引用，不再自动注入）
3. 覆盖 answer 节点 params.prompt

- [ ] **Step 2: 更新单测**

Run: `mvn -pl orchestrator test -Dtest=PlanAnswerPromptAssemblerTest -q 2>&1 | tail -5`
Expected: PASS（断言 prompt 不含 `{{nodeId.output}}` 全量块，只含 Catalog 模板）

- [ ] **Step 3: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanAnswerPromptAssembler.java
git commit -m "refactor(wf-4): PlanAnswerPromptAssembler drop full upstream injection"
```

### Task WF-4-3: 重写 13-sunshine-workflow-manager.sql（8 标杆新结构）

**Files:**
- Modify: `docker/mysql/init/13-sunshine-workflow-manager.sql`（全量重写 plan_json）

**Interfaces:**
- Consumes: 新 NodeSpec 结构（params + inputs）
- Produces: 8 标杆 workflow 的 plan_json 全部用新结构重写：tool 节点业务入参移到 inputs 数组，废弃 `output.extract`。

- [ ] **Step 1: 确认现有 8 标杆**

Run: `grep -c "plan_json" docker/mysql/init/13-sunshine-workflow-manager.sql`
Expected: 8（确认标杆数量）

- [ ] **Step 2: 逐个重写 plan_json**

每个 tool 节点：
- `params` 只保留 `tool` / `retry.*`
- 业务入参移到 `inputs` 数组：`[{name, source, type, required}]`
- 删除 `output.mode` / `output.extract`

每个 agent/answer 节点：
- answer 节点 prompt 显式写 `{{rag_1.output}}` / `{{tool_1.output}}` 引用（不再靠全量注入）

- [ ] **Step 3: 新增 parameter-extractor.template Catalog**

在 `docker/mysql/init/17-sunshine-prompt-manager.sql` 追加 `parameter-extractor.template` prompt 记录（用于 WF-3-3 的 ParameterExtractorNodeHandler）。

- [ ] **Step 4: 重启 + 验收**

Run: `python scripts/start.py` 重启全链路

Run: `python scripts/verify_workflow_studio_live.py`
Expected: 8 标杆加载成功 + 执行成功

- [ ] **Step 5: Commit**

```bash
git add docker/mysql/init/13-sunshine-workflow-manager.sql docker/mysql/init/17-sunshine-prompt-manager.sql
git commit -m "feat(wf-4): rewrite 8 benchmark workflows new structure + parameter-extractor catalog"
```

### Task WF-4-4: 全链路编译 + e2e 验收

- [ ] **Step 1: 全量编译**

Run: `mvn -pl orchestrator -am compile -q 2>&1 | tail -3`
Expected: BUILD SUCCESS

- [ ] **Step 2: 全量单测**

Run: `mvn -pl orchestrator test -q 2>&1 | tail -10`
Expected: 全部 PASS

- [ ] **Step 3: Live 验收**

Run: `python scripts/verify_workflow_studio_live.py`
Expected: 全过

Run: `python scripts/verify_plan_dag_live.py`
Expected: 全过

- [ ] **Step 4: Commit（如有 fix）**

```bash
git add -A
git commit -m "test(wf-4): e2e + live verification pass"
```


---

## WF-5 - Studio 前端适配

**出口闸门**：Studio 可编辑新结构 + 变量引用可视化选择 + live 验收。

### Task WF-5-1: Tool 节点结构化 inputs 编辑器

**Files:**
- Modify: `sunshine-ui/src/views/workflow/components/node-editors/ToolNodeEditor.vue`（新建或更新）
- Create: `sunshine-ui/src/views/workflow/components/VariableReferencePicker.vue`（变量引用选择器组件）

**Interfaces:**
- Consumes: 新 NodeSpec 结构（params + inputs）
- Produces: Tool 节点编辑面板从 params 键值对编辑器改为 inputs 绑定编辑器 + 变量引用选择器树形下拉。

- [ ] **Step 1: 创建 VariableReferencePicker 组件**

核心功能：
1. 接收 `upstreamNodes` prop（当前节点之前的所有节点列表）
2. 基于节点类型默认输出 schema 构建变量树
3. 树形下拉展示：节点名 > 输出字段 > 子字段
4. 点击叶子节点 emit `select` 事件，payload 为 `{{nodeId.path}}`

节点输出 schema 字典（前端常量）：
```typescript
const OUTPUT_SCHEMAS: Record<string, OutputField[]> = {
  start: [{ name: 'userQuery', type: 'string' }],
  rag: [{ name: 'output', type: 'string' }, { name: 'hits', type: 'array', children: [{ name: 'title', type: 'string' }, { name: 'content', type: 'string' }] }, { name: 'hitCount', type: 'number' }],
  tool: [{ name: 'output', type: 'object' }, { name: 'summary', type: 'string' }],
  agent: [{ name: 'answer', type: 'string' }, { name: 'toolCalls', type: 'array' }],
  'variable-assignment': [], // 动态
  'parameter-extractor': [], // 动态
  join: [{ name: 'output', type: 'mixed' }],
}
```

- [ ] **Step 2: 改造 ToolNodeEditor**

从 `params` 键值对编辑器改为：
- 工具选择下拉（不变）
- inputs 绑定列表：每行 = 参数名（输入框）| 变量引用选择器（VariableReferencePicker）| 类型下拉（string/number/boolean/object/array）| 必填开关
- 添加/删除 input 行按钮

- [ ] **Step 3: 前端编译验证**

Run: `cd sunshine-ui && npm run build 2>&1 | tail -5`
Expected: 无类型错误

- [ ] **Step 4: Commit**

```bash
git add sunshine-ui/src/views/workflow/components/
git commit -m "feat(wf-5): ToolNodeEditor structured inputs + VariableReferencePicker"
```

### Task WF-5-2: 新节点编辑器面板

**Files:**
- Create: `sunshine-ui/src/views/workflow/components/node-editors/VariableAssignmentNodeEditor.vue`
- Create: `sunshine-ui/src/views/workflow/components/node-editors/ParameterExtractorNodeEditor.vue`
- Modify: `sunshine-ui/src/views/workflow/components/node-editors/JoinNodeEditor.vue`（新增 mergeStrategy 下拉）

- [ ] **Step 1: VariableAssignmentNodeEditor**

assignments 列表编辑器：每行 = 变量名 | 变量引用选择器 | 类型下拉。添加/删除行按钮。

- [ ] **Step 2: ParameterExtractorNodeEditor**

- input 变量引用选择器
- instruction 文本域
- schema 编辑器：字段名 | 类型 | 描述 | 枚举值（可选）

- [ ] **Step 3: JoinNodeEditor 新增 mergeStrategy**

下拉选择：collect（默认）/ merge / first / last。

- [ ] **Step 4: 前端编译验证**

Run: `cd sunshine-ui && npm run build 2>&1 | tail -5`
Expected: 无类型错误

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/views/workflow/components/node-editors/
git commit -m "feat(wf-5): VariableAssignment + ParameterExtractor + Join editors"
```

### Task WF-5-3: Studio live 验收

- [ ] **Step 1: 启动全链路**

Run: `python scripts/start.py`

- [ ] **Step 2: Live 验收**

Run: `python scripts/verify_workflow_studio_live.py`
Expected: 全过

- [ ] **Step 3: 手动验收（Studio 编辑器）**

打开 Studio，验证：
1. Tool 节点编辑器显示 inputs 绑定列表 + 变量引用选择器
2. 变量引用选择器树形下拉可点选上游节点输出
3. 变量赋值节点编辑器可添加 assignments
4. 参数提取节点编辑器可编辑 schema
5. Join 节点可选 mergeStrategy

- [ ] **Step 4: Commit（如有 fix）**

```bash
git add -A
git commit -m "test(wf-5): Studio live verification pass"
```

---

## 验收标准汇总

| 维度 | 验收点 | 阶段 | 方式 |
|------|--------|------|------|
| 类型系统 | `{{tool_1.output.data.items[0].id}}` 嵌套取值 | WF-1 | 单测 |
| Tool I/O | 两个连续 tool 节点，tool_2 从 tool_1.output 取结构化字段 | WF-2 | live |
| RAG | `{{rag_1.hits[0].content}}` 取单条检索内容 | WF-2 | live |
| Answer | plan-workflow answer prompt 只含显式引用，非全量注入 | WF-4 | 日志 |
| 新节点 | 变量赋值 + 参数提取在标杆 workflow 中使用 | WF-3/4 | live |
| 并行聚合 | parallel + join(collect) 两个分支输出聚合 | WF-2 | live |
| 边条件 | `{{extract_1.result}} eq approved` 路由正确 | WF-2 | live |
| DB | 8 标杆新结构 SQL 导入成功 + 执行成功 | WF-4 | 集成测 |
| Studio | 变量引用选择器 + 新节点编辑器可用 | WF-5 | live |
| 回归 | verify_workflow_studio_live.py / verify_plan_dag_live.py 全过 | WF-4/5 | 脚本 |

## 关键文件索引

| 文件 | 改动类型 | 阶段 |
|------|---------|------|
| `orchestrator/.../execution/TypedValue.java` | 新建 | WF-1 |
| `orchestrator/.../execution/InputBinding.java` | 新建 | WF-1 |
| `orchestrator/.../execution/VarType.java` | 新建 | WF-1 |
| `orchestrator/.../execution/WorkflowContext.java` | 重写 | WF-1 |
| `orchestrator/.../execution/TemplateResolver.java` | 重写 | WF-1 |
| `orchestrator/.../execution/NodeSpec.java` | 重写 | WF-1 |
| `orchestrator/.../execution/NodeResult.java` | 重写 | WF-1 |
| `orchestrator/.../execution/NodeHandler.java` | 修改 | WF-1 |
| `orchestrator/.../execution/handler/ToolNodeHandler.java` | 重写 | WF-2 |
| `orchestrator/.../execution/handler/RagNodeHandler.java` | 修改 | WF-2 |
| `orchestrator/.../execution/handler/JoinNodeHandler.java` | 重写 | WF-2 |
| `orchestrator/.../execution/handler/AnswerNodeHandler.java` | 修改 | WF-2 |
| `orchestrator/.../execution/handler/AgentNodeHandler.java` | 修改 | WF-2 |
| `orchestrator/.../execution/handler/StartNodeHandler.java` | 修改 | WF-2 |
| `orchestrator/.../execution/handler/VariableAssignmentNodeHandler.java` | 新建 | WF-3 |
| `orchestrator/.../execution/handler/ParameterExtractorNodeHandler.java` | 新建 | WF-3 |
| `orchestrator/.../execution/EdgeConditionEvaluator.java` | 重写 | WF-2 |
| `orchestrator/.../execution/UpstreamOutputResolver.java` | 修改 | WF-2 |
| `orchestrator/.../execution/WorkflowContextCodec.java` | 重写 | WF-2 |
| `orchestrator/.../execution/workflow/WorkflowNodeRunner.java` | 修改 | WF-2 |
| `orchestrator/.../execution/workflow/WorkflowNodeFinalizer.java` | 修改 | WF-2 |
| `orchestrator/.../client/ToolManagerClient.java` | 修改 | WF-2 |
| `orchestrator/.../plan/PlanNode.java` | 修改 | WF-4 |
| `orchestrator/.../plan/PlanJsonParser.java` | 修改 | WF-4 |
| `orchestrator/.../plan/PlanAnswerPromptAssembler.java` | 重写 | WF-4 |
| `common/.../workflow/WorkflowNodeType.java` | 修改 | WF-3 |
| `docker/mysql/init/13-sunshine-workflow-manager.sql` | 重写 | WF-4 |
| `docker/mysql/init/17-sunshine-prompt-manager.sql` | 修改 | WF-4 |
| `sunshine-ui/.../workflow/components/` | 修改/新建 | WF-5 |
