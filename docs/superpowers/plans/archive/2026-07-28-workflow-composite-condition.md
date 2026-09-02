# Workflow 条件复合化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 loop 继续条件和 exclusive-gateway 出边条件从单三元组升级为多条件数组 + AND/OR 组合，复用结构化算子体系。

**Architecture:** 新增 `PlanEdgeConditionGroup`（logic + items[]）封装条件组；`EdgeConditionEvaluator` 新增 `matchesGroup` 统一求值；`PlanJsonParser` 兼容旧单条件格式；前端抽取 `ConditionGroupEditor.vue` 通用组件，loop 和 exclusive-gateway 共用。

**Tech Stack:** Java 17 / Spring Boot / Jackson / JUnit 5 / Mockito / AssertJ；Vue 3 / Naive UI / TypeScript。

## Global Constraints

- 不引入脚本表达式引擎，坚持"结构化算子，无脚本"哲学。
- 不做 Exit Loop 节点（YAGNI）。
- 不改变 loop 的 do-while 语义（至少一轮，body 后求值）。
- 旧 `condition.left/op/right` 单条件格式必须向后兼容，解析时自动转换。
- 算子仅新增 `not_eq`、`not_contains`，不新增其他。
- `PlanEdgeCondition`（单三元组 record）保留不变，作为 `PlanEdgeConditionGroup.items` 的元素类型。
- loop 空 conditions = 永远继续（true）；exclusive edge 空 items = 不命中（走 default）。
- 项目禁止 Flyway；SQL SSOT 在 `docker/mysql/init/`。
- 前端 UI 遵循 Codex 简约风格：`--sun-black` 底 + `--sun-border` 边框分区。
- 改 orchestrator 时间线 / workflow 后：编译 -> 重启 -> Agent 跑 live/e2e 留记录。

## 文件结构

### 后端（orchestrator）

| 文件 | 职责 | 操作 |
|------|------|------|
| `plan/PlanEdgeConditionGroup.java` | 条件组 record（logic + items） | **新建** |
| `plan/PlanEdgeCondition.java` | 单条件三元组；`isComplete` 新增算子分支 | 修改 |
| `plan/PlanEdge.java` | 边；`condition` 类型改 `PlanEdgeConditionGroup` | 修改 |
| `plan/PlanExecutionSchedule.java` | `ExclusiveArm.condition` 类型改 `PlanEdgeConditionGroup` | 修改 |
| `plan/PlanJsonParser.java` | `parseCondition` -> `parseConditionGroup`；loop params 解析 | 修改 |
| `execution/EdgeConditionEvaluator.java` | 新增 `not_eq`/`not_contains`/`matchesGroup` | 修改 |
| `execution/WorkflowExecutor.java` | loop 求值改 `matchesGroup`；exclusive 求值改 `matchesGroup` | 修改 |

### 后端测试

| 文件 | 职责 | 操作 |
|------|------|------|
| `execution/EdgeConditionEvaluatorTest.java` | 新算子 + `matchesGroup` 测试 | 修改 |
| `plan/PlanJsonParserTest.java` | 复合条件解析 + 兼容旧格式 | 修改 |

### 前端（sunshine-ui）

| 文件 | 职责 | 操作 |
|------|------|------|
| `src/api/workflows.ts` | `WorkflowPlanEdgeConditionGroup` 类型 | 修改 |
| `src/components/workflows/ConditionGroupEditor.vue` | 通用条件组编辑器 | **新建** |
| `src/components/workflows/WorkflowStudioPropsAside.vue` | loop 编辑区改用 `ConditionGroupEditor` | 修改 |
| `src/components/workflows/WorkflowExclusiveEdgesSection.vue` | exclusive 出边改用 `ConditionGroupEditor` | 修改 |
| `src/utils/workflowPlan.ts` | `reconcilePlanDataFlow` 适配新格式；算子选项 | 修改 |

### SQL 种子

| 文件 | 职责 | 操作 |
|------|------|------|
| `docker/mysql/init/13-sunshine-workflow-manager.sql` | `knowledge-loop` / `knowledge-branch` 标杆升级 | 修改 |

### 验收脚本

| 文件 | 职责 | 操作 |
|------|------|------|
| `scripts/verify_workflow_studio_live.py` | `suite_loop` / `suite_exclusive` 多条件断言 | 修改 |

---

## Task 1: PlanEdgeConditionGroup + PlanEdgeCondition 算子扩展

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanEdgeConditionGroup.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanEdgeCondition.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/plan/PlanEdgeConditionGroupTest.java`

**Interfaces:**
- Produces: `PlanEdgeConditionGroup(String logic, List<PlanEdgeCondition> items)` record with `single(PlanEdgeCondition)`, `empty()`, `isEmpty()` static factories.
- Produces: `PlanEdgeCondition.isComplete()` now handles `not_eq`/`not_contains`.

- [ ] **Step 1: Write failing test for PlanEdgeConditionGroup**

```java
package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanEdgeConditionGroupTest {

    @Test
    void emptyGroupIsEmpty() {
        PlanEdgeConditionGroup g = PlanEdgeConditionGroup.empty();
        assertThat(g.isEmpty()).isTrue();
        assertThat(g.logic()).isEqualTo("and");
        assertThat(g.items()).isEmpty();
    }

    @Test
    void singleWrapsOneCondition() {
        PlanEdgeCondition c = new PlanEdgeCondition("{{x}}", "eq", "y");
        PlanEdgeConditionGroup g = PlanEdgeConditionGroup.single(c);
        assertThat(g.isEmpty()).isFalse();
        assertThat(g.logic()).isEqualTo("and");
        assertThat(g.items()).containsExactly(c);
    }

    @Test
    void defaultLogicIsAnd() {
        PlanEdgeConditionGroup g = new PlanEdgeConditionGroup(null, List.of());
        assertThat(g.logic()).isEqualTo("and");
    }

    @Test
    void orLogicPreserved() {
        PlanEdgeCondition c = new PlanEdgeCondition("{{x}}", "eq", "y");
        PlanEdgeConditionGroup g = new PlanEdgeConditionGroup("or", List.of(c));
        assertThat(g.logic()).isEqualTo("or");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd orchestrator && ./mvnw test -pl . -Dtest=PlanEdgeConditionGroupTest -q`
Expected: FAIL with compilation error (class not found).

- [ ] **Step 3: Create PlanEdgeConditionGroup**

```java
package com.sunshine.orchestrator.plan;

import java.util.List;

/** 条件组：多条件 + AND/OR 组合（loop 继续条件 / exclusive-gateway 出边条件共用） */
public record PlanEdgeConditionGroup(
        String logic,
        List<PlanEdgeCondition> items) {

    public PlanEdgeConditionGroup {
        logic = (logic == null || logic.isBlank()) ? "and" : logic.strip().toLowerCase();
        items = items != null ? List.copyOf(items) : List.of();
    }

    public static PlanEdgeConditionGroup single(PlanEdgeCondition c) {
        return new PlanEdgeConditionGroup("and", c != null ? List.of(c) : List.of());
    }

    public static PlanEdgeConditionGroup empty() {
        return new PlanEdgeConditionGroup("and", List.of());
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
```

- [ ] **Step 4: Extend PlanEdgeCondition.isComplete for not_eq/not_contains**

In `PlanEdgeCondition.java`, add `not_eq`/`not_contains` to the `isComplete()` method. The current code has a branch for `gt/lt/gte/lte/in/not_in` requiring only `left`; `not_eq`/`not_contains` need `left + right` (same as `eq`/`contains`). Modify the final `return` line to include them:

```java
public boolean isComplete() {
    if (op.isBlank()) {
        return false;
    }
    if ("empty".equals(op) || "not_empty".equals(op)) {
        return !left.isBlank();
    }
    if ("gt".equals(op) || "lt".equals(op) || "gte".equals(op)
            || "lte".equals(op) || "in".equals(op) || "not_in".equals(op)) {
        return !left.isBlank();
    }
    // eq / not_eq / contains / not_contains 需 left + right
    return !left.isBlank() && !right.isBlank();
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd orchestrator && ./mvnw test -pl . -Dtest=PlanEdgeConditionGroupTest -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanEdgeConditionGroup.java orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanEdgeCondition.java orchestrator/src/test/java/com/sunshine/orchestrator/plan/PlanEdgeConditionGroupTest.java
git commit -m "feat: add PlanEdgeConditionGroup + not_eq/not_contains operators"
```

---

## Task 2: EdgeConditionEvaluator matchesGroup + new operators

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/EdgeConditionEvaluator.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/EdgeConditionEvaluatorTest.java`

**Interfaces:**
- Consumes: `PlanEdgeConditionGroup` from Task 1.
- Produces: `EdgeConditionEvaluator.matchesGroup(PlanEdgeConditionGroup, WorkflowContext)` static method.
- Produces: `not_eq`/`not_contains` operator support in `matches`.

- [ ] **Step 1: Write failing tests for not_eq/not_contains and matchesGroup**

Append to `EdgeConditionEvaluatorTest.java`:

```java
@Test
void notEqOperator() {
    var ctx = new WorkflowContext();
    ctx.putNode("n1", Map.of("output", TypedValue.scalar("done")));
    assertThat(EdgeConditionEvaluator.matches(
            new PlanEdgeCondition("{{n1.output}}", "not_eq", "done"), ctx)).isFalse();
    assertThat(EdgeConditionEvaluator.matches(
            new PlanEdgeCondition("{{n1.output}}", "not_eq", "pending"), ctx)).isTrue();
}

@Test
void notContainsOperator() {
    var ctx = new WorkflowContext();
    ctx.putNode("n1", Map.of("output", TypedValue.scalar("已完成")));
    assertThat(EdgeConditionEvaluator.matches(
            new PlanEdgeCondition("{{n1.output}}", "not_contains", "已完成"), ctx)).isFalse();
    assertThat(EdgeConditionEvaluator.matches(
            new PlanEdgeCondition("{{n1.output}}", "not_contains", "待处理"), ctx)).isTrue();
}

@Test
void matchesGroupAndAllTrue() {
    var ctx = new WorkflowContext();
    ctx.putNode("n1", Map.of("count", TypedValue.scalar(5)));
    ctx.putNode("n2", Map.of("status", TypedValue.scalar("running")));
    var group = new PlanEdgeConditionGroup("and", List.of(
            new PlanEdgeCondition("{{n1.count}}", "gt", "3"),
            new PlanEdgeCondition("{{n2.status}}", "not_eq", "done")));
    assertThat(EdgeConditionEvaluator.matchesGroup(group, ctx)).isTrue();
}

@Test
void matchesGroupAndOneFalse() {
    var ctx = new WorkflowContext();
    ctx.putNode("n1", Map.of("count", TypedValue.scalar(2)));
    ctx.putNode("n2", Map.of("status", TypedValue.scalar("running")));
    var group = new PlanEdgeConditionGroup("and", List.of(
            new PlanEdgeCondition("{{n1.count}}", "gt", "3"),
            new PlanEdgeCondition("{{n2.status}}", "not_eq", "done")));
    assertThat(EdgeConditionEvaluator.matchesGroup(group, ctx)).isFalse();
}

@Test
void matchesGroupOrOneTrue() {
    var ctx = new WorkflowContext();
    ctx.putNode("n1", Map.of("count", TypedValue.scalar(2)));
    ctx.putNode("n2", Map.of("status", TypedValue.scalar("done")));
    var group = new PlanEdgeConditionGroup("or", List.of(
            new PlanEdgeCondition("{{n1.count}}", "gt", "3"),
            new PlanEdgeCondition("{{n2.status}}", "not_eq", "done")));
    assertThat(EdgeConditionEvaluator.matchesGroup(group, ctx)).isFalse();
}

@Test
void matchesGroupEmptyReturnsTrue() {
    var ctx = new WorkflowContext();
    var group = PlanEdgeConditionGroup.empty();
    assertThat(EdgeConditionEvaluator.matchesGroup(group, ctx)).isTrue();
}
```

Also add imports at the top of the test file:

```java
import com.sunshine.orchestrator.plan.PlanEdgeConditionGroup;
import java.util.List;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd orchestrator && ./mvnw test -pl . -Dtest=EdgeConditionEvaluatorTest -q`
Expected: FAIL (matchesGroup not found, not_eq/not_contains not supported).

- [ ] **Step 3: Add not_eq/not_contains cases and matchesGroup method**

In `EdgeConditionEvaluator.java`, add two cases to the `switch` in `matches`:

```java
case "not_eq" -> !normalize(left).equals(normalize(right));
case "not_contains" -> left == null || right == null || !left.contains(right);
```

Add the `matchesGroup` method:

```java
public static boolean matchesGroup(PlanEdgeConditionGroup group, WorkflowContext ctx) {
    if (group == null || group.isEmpty()) {
        return true;
    }
    if ("or".equals(group.logic())) {
        return group.items().stream().anyMatch(c -> matches(c, ctx));
    }
    return group.items().stream().allMatch(c -> matches(c, ctx));
}
```

Add import:

```java
import com.sunshine.orchestrator.plan.PlanEdgeConditionGroup;
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd orchestrator && ./mvnw test -pl . -Dtest=EdgeConditionEvaluatorTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/EdgeConditionEvaluator.java orchestrator/src/test/java/com/sunshine/orchestrator/execution/EdgeConditionEvaluatorTest.java
git commit -m "feat: add matchesGroup + not_eq/not_contains to EdgeConditionEvaluator"
```

---

## Task 3: PlanEdge + ExclusiveArm 类型升级

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanEdge.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanExecutionSchedule.java`

**Interfaces:**
- Consumes: `PlanEdgeConditionGroup` from Task 1.
- Produces: `PlanEdge.condition` is now `PlanEdgeConditionGroup` (nullable).
- Produces: `PlanExecutionSchedule.ExclusiveArm.condition` is now `PlanEdgeConditionGroup` (nullable).
- Produces: `PlanEdge.hasCondition()` checks `conditionGroup != null && !conditionGroup.isEmpty()`.

- [ ] **Step 1: Modify PlanEdge to use PlanEdgeConditionGroup**

Replace the `condition` field type and adjust constructors/methods in `PlanEdge.java`:

```java
package com.sunshine.orchestrator.plan;

/** Planner / Studio 有向边；条件字段仅用于 exclusive-gateway 出边 */
public record PlanEdge(
        String from,
        String to,
        PlanEdgeConditionGroup condition,
        boolean isDefault) {

    public PlanEdge(String from, String to) {
        this(from, to, null, false);
    }

    public PlanEdge {
        if (condition != null && condition.isEmpty()) {
            condition = null;
        }
    }

    public boolean hasCondition() {
        return condition != null && !condition.isEmpty();
    }
}
```

- [ ] **Step 2: Modify ExclusiveArm to use PlanEdgeConditionGroup**

In `PlanExecutionSchedule.java`, change the `ExclusiveArm` record's `condition` field type:

```java
public record ExclusiveArm(
        String targetNodeId,
        PlanEdgeConditionGroup condition,
        boolean isDefault,
        List<String> pathNodeIds) {
    public ExclusiveArm {
        pathNodeIds = pathNodeIds != null ? List.copyOf(pathNodeIds) : List.of();
    }
}
```

- [ ] **Step 3: Compile to find all call sites that need updating**

Run: `cd orchestrator && ./mvnw compile -pl . -q 2>&1 | head -40`
Expected: Compilation errors in `PlanExecutionSchedule.buildExclusive` (constructing `ExclusiveArm` with `PlanEdgeCondition`), `PlanJsonParser.parseEdges` (constructing `PlanEdge` with `PlanEdgeCondition`), and `WorkflowExecutor.pickExclusiveArm` (calling `arm.condition()` expecting `PlanEdgeCondition`).

- [ ] **Step 4: Fix PlanExecutionSchedule.buildExclusive arm construction**

In `PlanExecutionSchedule.java`, the line constructing `ExclusiveArm` currently passes `edge.condition()` (which is now `PlanEdgeConditionGroup`). Since `PlanEdge.condition` is already `PlanEdgeConditionGroup`, this should compile without change after Task 4 updates the parser. But verify the `buildExclusive` method still compiles. If there are explicit `new PlanEdgeCondition(...)` calls, replace with `PlanEdgeConditionGroup.single(...)`.

- [ ] **Step 5: Commit (compilation will be fixed in Task 4)**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanEdge.java orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanExecutionSchedule.java
git commit -m "refactor: PlanEdge/ExclusiveArm use PlanEdgeConditionGroup"
```

---

## Task 4: PlanJsonParser 条件组解析 + 向后兼容

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanJsonParser.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/plan/PlanJsonParserTest.java`

**Interfaces:**
- Consumes: `PlanEdgeConditionGroup` from Task 1, `PlanEdge` new signature from Task 3.
- Produces: `parseConditionGroup(JsonNode)` -> `PlanEdgeConditionGroup` (handles both `{logic, items}` and legacy `{left, op, right}`).

- [ ] **Step 1: Write failing tests for composite condition parsing**

Append to `PlanJsonParserTest.java`:

```java
@Test
void parsesCompositeEdgeCondition() {
    String json = """
            {
              "planId": "xg-2",
              "reason": "复合条件分支",
              "nodes": [
                {"id":"xg-1","type":"exclusive-gateway","params":{}},
                {"id":"rag-a","type":"rag","params":{"topK":"3"}},
                {"id":"rag-b","type":"rag","params":{"topK":"3"}},
                {"id":"answer","type":"answer","params":{}}
              ],
              "edges": [
                {"from":"start","to":"xg-1"},
                {"from":"xg-1","to":"rag-a","condition":{
                  "logic":"or",
                  "items":[
                    {"left":"{{start.userQuery}}","op":"contains","right":"报销"},
                    {"left":"{{start.userQuery}}","op":"contains","right":"发票"}
                  ]
                }},
                {"from":"xg-1","to":"rag-b","default":true},
                {"from":"rag-a","to":"answer"},
                {"from":"rag-b","to":"answer"}
              ]
            }
            """;
    PlanJson plan = parser.parse(json);
    PlanEdge cond = plan.edges().stream()
            .filter(e -> "rag-a".equals(e.to()))
            .findFirst()
            .orElseThrow();
    assertThat(cond.hasCondition()).isTrue();
    assertThat(cond.condition().logic()).isEqualTo("or");
    assertThat(cond.condition().items()).hasSize(2);
    assertThat(cond.condition().items().get(0).op()).isEqualTo("contains");
    assertThat(cond.condition().items().get(1).right()).isEqualTo("发票");
}

@Test
void parsesLegacySingleConditionAsGroup() {
    String json = """
            {
              "planId": "xg-3",
              "reason": "兼容旧单条件",
              "nodes": [
                {"id":"xg-1","type":"exclusive-gateway","params":{}},
                {"id":"rag-a","type":"rag","params":{"topK":"3"}},
                {"id":"rag-b","type":"rag","params":{"topK":"3"}},
                {"id":"answer","type":"answer","params":{}}
              ],
              "edges": [
                {"from":"start","to":"xg-1"},
                {"from":"xg-1","to":"rag-a","condition":{"left":"{{start.userQuery}}","op":"contains","right":"报销"}},
                {"from":"xg-1","to":"rag-b","default":true},
                {"from":"rag-a","to":"answer"},
                {"from":"rag-b","to":"answer"}
              ]
            }
            """;
    PlanJson plan = parser.parse(json);
    PlanEdge cond = plan.edges().stream()
            .filter(e -> "rag-a".equals(e.to()))
            .findFirst()
            .orElseThrow();
    assertThat(cond.hasCondition()).isTrue();
    assertThat(cond.condition().logic()).isEqualTo("and");
    assertThat(cond.condition().items()).hasSize(1);
    assertThat(cond.condition().items().get(0).op()).isEqualTo("contains");
}

@Test
void parsesLoopConditionsArray() {
    String json = """
            {
              "planId": "lp-2",
              "reason": "多条件循环",
              "nodes": [
                {"id":"loop-1","type":"loop","params":{
                  "conditions":[
                    {"left":"{{rag-b.hitCount}}","op":"gt","right":"0"},
                    {"left":"{{rag-b.output}}","op":"not_contains","right":"已完成"}
                  ],
                  "conditionLogic":"and",
                  "maxIterations":"3","onMaxIterations":"exit"
                }},
                {"id":"rag-b","type":"rag","parentId":"loop-1","params":{"query":"{{start.userQuery}}","topK":"3"}},
                {"id":"answer","type":"answer","params":{}}
              ],
              "edges": [
                {"from":"start","to":"loop-1"},
                {"from":"loop-1","to":"answer"}
              ]
            }
            """;
    PlanJson plan = parser.parse(json);
    assertThat(PlanExecutionSchedule.validateLoopTopology(plan)).isNull();
    assertThat(PlanExecutionSchedule.build(plan).get(0)).isInstanceOf(PlanExecutionSchedule.Loop.class);
}

@Test
void parsesLegacyLoopSingleCondition() {
    String json = """
            {
              "planId": "lp-3",
              "reason": "兼容旧 loop 单条件",
              "nodes": [
                {"id":"loop-1","type":"loop","params":{
                  "condition.left":"{{start.userQuery}}","condition.op":"contains","condition.right":"继续",
                  "maxIterations":"3","onMaxIterations":"exit"
                }},
                {"id":"rag-b","type":"rag","parentId":"loop-1","params":{"query":"{{start.userQuery}}","topK":"3"}},
                {"id":"answer","type":"answer","params":{}}
              ],
              "edges": [
                {"from":"start","to":"loop-1"},
                {"from":"loop-1","to":"answer"}
              ]
            }
            """;
    PlanJson plan = parser.parse(json);
    assertThat(plan.nodesById().get("rag-b").parentId()).isEqualTo("loop-1");
    assertThat(PlanExecutionSchedule.validateLoopTopology(plan)).isNull();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd orchestrator && ./mvnw test -pl . -Dtest=PlanJsonParserTest -q`
Expected: FAIL (compilation errors from Task 3 changes + parseConditionGroup not found).

- [ ] **Step 3: Implement parseConditionGroup in PlanJsonParser**

Replace `parseCondition` with `parseConditionGroup` in `PlanJsonParser.java`:

```java
private static PlanEdgeConditionGroup parseConditionGroup(JsonNode node) {
    if (node == null || !node.isObject()) {
        return null;
    }
    // 新格式：{logic, items: [...]}
    JsonNode itemsNode = node.get("items");
    if (itemsNode != null && itemsNode.isArray()) {
        String logic = text(node, "logic");
        List<PlanEdgeCondition> items = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            PlanEdgeCondition c = parseSingleCondition(item);
            if (c != null) {
                items.add(c);
            }
        }
        if (items.isEmpty() && logic == null) {
            return null;
        }
        return new PlanEdgeConditionGroup(logic, items);
    }
    // 兼容旧格式：{left, op, right}
    PlanEdgeCondition single = parseSingleCondition(node);
    if (single == null) {
        return null;
    }
    return PlanEdgeConditionGroup.single(single);
}

private static PlanEdgeCondition parseSingleCondition(JsonNode node) {
    if (node == null || !node.isObject()) {
        return null;
    }
    String left = text(node, "left");
    String op = text(node, "op");
    String right = text(node, "right");
    if ((left == null || left.isBlank()) && (op == null || op.isBlank()) && (right == null || right.isBlank())) {
        return null;
    }
    return new PlanEdgeCondition(
            left != null ? left : "",
            op != null ? op : "",
            right != null ? right : "");
}
```

Update `parseEdges` to call `parseConditionGroup`:

```java
PlanEdgeConditionGroup condition = parseConditionGroup(edge.get("condition"));
edges.add(new PlanEdge(from, to, condition, isDefault));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd orchestrator && ./mvnw test -pl . -Dtest=PlanJsonParserTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/plan/PlanJsonParser.java orchestrator/src/test/java/com/sunshine/orchestrator/plan/PlanJsonParserTest.java
git commit -m "feat: PlanJsonParser parses composite condition groups with legacy compat"
```

---

## Task 5: WorkflowExecutor 求值改用 matchesGroup

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowExecutor.java`

**Interfaces:**
- Consumes: `PlanEdgeConditionGroup` from Task 1, `EdgeConditionEvaluator.matchesGroup` from Task 2, `PlanEdge.condition` new type from Task 3.
- Produces: `WorkflowExecutor.runLoopIterations` reads `conditions` array from loop params (with legacy `condition.*` compat); loop and exclusive both call `matchesGroup`.

- [ ] **Step 1: Update loop condition parsing in runLoopIterations**

In `WorkflowExecutor.runLoopIterations`, replace the single `PlanEdgeCondition` construction with `PlanEdgeConditionGroup` parsing:

```java
private Flux<StreamToken> runLoopIterations(
        PlanExecutionSchedule.Loop loop,
        LoopBodyTimelineBridge bridge,
        AtomicInteger foldIter,
        ProcessingTimelineSession session,
        WorkflowDefinition def,
        WorkflowContext wfCtx,
        ExecutionStreamContext streamCtx,
        WorkflowRunSession runSession,
        boolean planWorkflow) {
    if (hasLoopSettled(wfCtx, loop.loopNodeId())) {
        return Flux.empty();
    }
    NodeSpec loopSpec = def.node(loop.loopNodeId());
    Map<String, Object> params = loopSpec != null ? loopSpec.params() : Map.of();
    int maxIterations = parseMaxIterations(params);
    String onMax = readParamString(params, "onMaxIterations", "fail_fast").strip().toLowerCase();
    PlanEdgeConditionGroup conditionGroup = parseLoopConditionGroup(params);
    AtomicInteger iter = new AtomicInteger(0);
    AtomicReference<String> buffer = new AtomicReference<>("");
    return loopCycle(
            loop, bridge, foldIter, conditionGroup, maxIterations, onMax, iter, buffer,
            session, def, wfCtx, streamCtx, runSession, planWorkflow);
}
```

- [ ] **Step 2: Add parseLoopConditionGroup helper**

Add a private static method:

```java
private static PlanEdgeConditionGroup parseLoopConditionGroup(Map<String, Object> params) {
    // 新格式：conditions 数组 + conditionLogic
    Object conditionsObj = params.get("conditions");
    if (conditionsObj instanceof com.fasterxml.jackson.databind.JsonNode conditionsNode
            && conditionsNode.isArray()) {
        String logic = readParamString(params, "conditionLogic", "and");
        List<PlanEdgeCondition> items = new ArrayList<>();
        for (JsonNode item : conditionsNode) {
            String left = item.has("left") ? item.get("left").asText("") : "";
            String op = item.has("op") ? item.get("op").asText("") : "";
            String right = item.has("right") ? item.get("right").asText("") : "";
            if (!op.isBlank()) {
                items.add(new PlanEdgeCondition(left, op, right));
            }
        }
        return new PlanEdgeConditionGroup(logic, items);
    }
    // 兼容旧格式：condition.left / condition.op / condition.right
    String op = readParamString(params, "condition.op", "");
    if (!op.isBlank()) {
        return PlanEdgeConditionGroup.single(new PlanEdgeCondition(
                readParamString(params, "condition.left", ""),
                op,
                readParamString(params, "condition.right", "")));
    }
    // 无条件 -> 空组（永远继续，靠 maxIterations 兜底）
    return PlanEdgeConditionGroup.empty();
}
```

Add imports:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.sunshine.orchestrator.plan.PlanEdgeConditionGroup;
```

- [ ] **Step 3: Update loopCycle signature and evaluation**

Change `loopCycle` to accept `PlanEdgeConditionGroup conditionGroup` instead of `PlanEdgeCondition condition`:

```java
private Flux<StreamToken> loopCycle(
        PlanExecutionSchedule.Loop loop,
        LoopBodyTimelineBridge bridge,
        AtomicInteger foldIter,
        PlanEdgeConditionGroup conditionGroup,
        int maxIterations,
        String onMax,
        AtomicInteger iter,
        AtomicReference<String> buffer,
        ProcessingTimelineSession session,
        WorkflowDefinition def,
        WorkflowContext wfCtx,
        ExecutionStreamContext streamCtx,
        WorkflowRunSession runSession,
        boolean planWorkflow) {
    // do-while：至少一轮；继续条件在 body 之后求值
    if (iter.get() >= maxIterations) {
        return applyLoopMaxIterations(loop.loopNodeId(), onMax, buffer.get(), iter.get(), wfCtx, runSession)
                .concatWith(Flux.just(loopCompleteToken(
                        loop.loopNodeId(),
                        def.node(loop.loopNodeId()),
                        buffer.get(),
                        iter.get(),
                        bridge.subSteps())));
    }
    List<String> body = loop.bodyNodeIds();
    if (body.isEmpty()) {
        return Flux.error(new IllegalStateException("loop " + loop.loopNodeId() + " body 为空"));
    }
    int round = iter.get() + 1;
    foldIter.set(round);
    return executeNodeOrder(body, session, def, wfCtx, streamCtx, runSession, planWorkflow)
            .concatMap(token -> {
                if (bridge.isBodyToken(token)) {
                    return Flux.fromIterable(bridge.wrap(token, round));
                }
                return Flux.just(token);
            })
            .concatWith(Flux.defer(() -> {
                buffer.set(resolveBodyTailOutput(wfCtx, body));
                iter.incrementAndGet();
                if (!EdgeConditionEvaluator.matchesGroup(conditionGroup, wfCtx)) {
                    settleLoop(wfCtx, loop.loopNodeId(), buffer.get(), iter.get(), "completed");
                    return Flux.just(loopCompleteToken(
                            loop.loopNodeId(),
                            def.node(loop.loopNodeId()),
                            buffer.get(),
                            iter.get(),
                            bridge.subSteps()));
                }
                return loopCycle(
                        loop, bridge, foldIter, conditionGroup, maxIterations, onMax, iter, buffer,
                        session, def, wfCtx, streamCtx, runSession, planWorkflow);
            }));
}
```

- [ ] **Step 4: Update pickExclusiveArm to use matchesGroup**

```java
private static PlanExecutionSchedule.ExclusiveArm pickExclusiveArm(
        List<PlanExecutionSchedule.ExclusiveArm> arms,
        WorkflowContext wfCtx) {
    PlanExecutionSchedule.ExclusiveArm fallback = null;
    for (PlanExecutionSchedule.ExclusiveArm arm : arms) {
        if (arm.isDefault()) {
            fallback = arm;
            continue;
        }
        if (EdgeConditionEvaluator.matchesGroup(arm.condition(), wfCtx)) {
            return arm;
        }
    }
    return fallback;
}
```

- [ ] **Step 5: Compile and run all orchestrator tests**

Run: `cd orchestrator && ./mvnw test -pl . -q`
Expected: PASS (all existing tests + new tests from Tasks 1-4)

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/execution/WorkflowExecutor.java
git commit -m "feat: WorkflowExecutor uses matchesGroup for loop + exclusive conditions"
```

---

## Task 6: 前端类型 + ConditionGroupEditor 组件

**Files:**
- Modify: `sunshine-ui/src/api/workflows.ts`
- Create: `sunshine-ui/src/components/workflows/ConditionGroupEditor.vue`

**Interfaces:**
- Produces: `WorkflowPlanEdgeConditionGroup` interface in `workflows.ts`.
- Produces: `ConditionGroupEditor.vue` component with `modelValue: { logic, items }`, `upstreamNodes`, `disabled` props; emits `update:modelValue`.

- [ ] **Step 1: Add WorkflowPlanEdgeConditionGroup type**

In `sunshine-ui/src/api/workflows.ts`, add the new interface and update `WorkflowPlanEdge`:

```typescript
export interface WorkflowPlanEdgeCondition {
  left: string
  op: string
  right?: string
}

export interface WorkflowPlanEdgeConditionGroup {
  logic: 'and' | 'or'
  items: WorkflowPlanEdgeCondition[]
}

export interface WorkflowPlanEdge {
  from: string
  to: string
  /** 复合条件（新格式 {logic, items}）；兼容旧 {left, op, right} */
  condition?: WorkflowPlanEdgeConditionGroup | WorkflowPlanEdgeCondition
  default?: boolean
}
```

- [ ] **Step 2: Create ConditionGroupEditor.vue**

Create `sunshine-ui/src/components/workflows/ConditionGroupEditor.vue`:

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NInput, NRadio, NRadioGroup, NSelect, NSpace } from 'naive-ui'
import type { WorkflowPlanNode } from '../../api/workflows'
import type { WorkflowPlanEdgeCondition, WorkflowPlanEdgeConditionGroup } from '../../api/workflows'
import VariableReferencePicker from './VariableReferencePicker.vue'

const props = defineProps<{
  modelValue: WorkflowPlanEdgeConditionGroup
  upstreamNodes: WorkflowPlanNode[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [val: WorkflowPlanEdgeConditionGroup]
}>()

const CONDITION_OP_OPTIONS = [
  { label: '为空 empty', value: 'empty' },
  { label: '非空 not_empty', value: 'not_empty' },
  { label: '包含 contains', value: 'contains' },
  { label: '不包含 not_contains', value: 'not_contains' },
  { label: '等于 eq', value: 'eq' },
  { label: '不等于 not_eq', value: 'not_eq' },
  { label: '大于 gt', value: 'gt' },
  { label: '小于 lt', value: 'lt' },
  { label: '大于等于 gte', value: 'gte' },
  { label: '小于等于 lte', value: 'lte' },
  { label: '属于 in', value: 'in' },
  { label: '不属于 not_in', value: 'not_in' },
]

function updateLogic(logic: 'and' | 'or') {
  emit('update:modelValue', { ...props.modelValue, logic })
}

function updateItem(index: number, patch: Partial<WorkflowPlanEdgeCondition>) {
  const items = props.modelValue.items.map((item, i) =>
    i === index ? { ...item, ...patch } : item,
  )
  emit('update:modelValue', { ...props.modelValue, items })
}

function removeItem(index: number) {
  const items = props.modelValue.items.filter((_, i) => i !== index)
  emit('update:modelValue', { ...props.modelValue, items })
}

function addItem() {
  const items = [...props.modelValue.items, { left: '', op: 'not_empty', right: '' }]
  emit('update:modelValue', { ...props.modelValue, items })
}
</script>

<template>
  <div class="condition-group-editor">
    <div class="condition-logic-row">
      <NRadioGroup
        :value="modelValue.logic"
        :disabled="disabled"
        @update:value="v => updateLogic(v as 'and' | 'or')"
      >
        <NRadio value="and">全部满足 (AND)</NRadio>
        <NRadio value="or">任一满足 (OR)</NRadio>
      </NRadioGroup>
    </div>
    <div
      v-for="(item, idx) in modelValue.items"
      :key="idx"
      class="condition-row"
    >
      <VariableReferencePicker
        class="cond-left"
        :model-value="item.left"
        :upstream-nodes="upstreamNodes"
        :disabled="disabled"
        placeholder="{{node.field}}"
        @update:modelValue="v => updateItem(idx, { left: v })"
      />
      <NSelect
        class="cond-op"
        :value="item.op"
        :options="CONDITION_OP_OPTIONS"
        :disabled="disabled"
        @update:value="v => updateItem(idx, { op: String(v) })"
      />
      <NInput
        v-if="item.op !== 'empty' && item.op !== 'not_empty'"
        class="cond-right"
        :value="item.right ?? ''"
        :disabled="disabled"
        placeholder="比较值"
        @update:value="v => updateItem(idx, { right: v })"
      />
      <NButton
        quaternary
        size="small"
        :disabled="disabled"
        @click="removeItem(idx)"
      >
        ✕
      </NButton>
    </div>
    <NButton
      quaternary
      size="small"
      :disabled="disabled"
      @click="addItem"
    >
      + 添加条件
    </NButton>
  </div>
</template>

<style scoped>
.condition-group-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.condition-logic-row {
  font-size: 12px;
  color: var(--sun-text-secondary);
}
.condition-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.cond-left {
  flex: 1;
  min-width: 0;
}
.cond-op {
  width: 140px;
  flex-shrink: 0;
}
.cond-right {
  flex: 1;
  min-width: 0;
}
</style>
```

- [ ] **Step 3: Commit**

```bash
git add sunshine-ui/src/api/workflows.ts sunshine-ui/src/components/workflows/ConditionGroupEditor.vue
git commit -m "feat: add ConditionGroupEditor component + condition group types"
```

---

## Task 7: 前端 loop 编辑器集成 ConditionGroupEditor

**Files:**
- Modify: `sunshine-ui/src/components/workflows/WorkflowStudioPropsAside.vue`
- Modify: `sunshine-ui/src/utils/workflowPlan.ts`

**Interfaces:**
- Consumes: `ConditionGroupEditor.vue` from Task 6.
- Produces: loop 编辑区使用 `ConditionGroupEditor`，数据格式为 `params.conditions` + `params.conditionLogic`。

- [ ] **Step 1: Add helper functions in workflowPlan.ts**

Add functions to normalize loop condition params between old/new format:

```typescript
/** 将 loop params 中的条件规范化为 {logic, items} 结构（用于 UI 编辑） */
export function normalizeLoopConditionGroup(
  params: Record<string, unknown> | undefined,
): WorkflowPlanEdgeConditionGroup {
  const conditions = params?.conditions
  if (Array.isArray(conditions) && conditions.length >= 0) {
    const logic = (params?.conditionLogic === 'or' ? 'or' : 'and') as 'and' | 'or'
    const items = conditions.filter(c => c && typeof c === 'object') as WorkflowPlanEdgeCondition[]
    return { logic, items: items.length > 0 ? items : [] }
  }
  // 兼容旧格式
  const left = String(params?.['condition.left'] ?? '')
  const op = String(params?.['condition.op'] ?? '')
  const right = String(params?.['condition.right'] ?? '')
  if (op) {
    return { logic: 'and', items: [{ left, op, right }] }
  }
  return { logic: 'and', items: [] }
}

/** 将条件组写回 loop params（新格式 conditions + conditionLogic） */
export function writeLoopConditionGroup(
  params: Record<string, unknown>,
  group: WorkflowPlanEdgeConditionGroup,
): Record<string, unknown> {
  const next = { ...params }
  delete next['condition.left']
  delete next['condition.op']
  delete next['condition.right']
  next.conditions = group.items
  next.conditionLogic = group.logic
  return next
}
```

Add imports at top of `workflowPlan.ts`:

```typescript
import type { WorkflowPlanEdgeCondition, WorkflowPlanEdgeConditionGroup } from '../api/workflows'
```

- [ ] **Step 2: Update reconcilePlanDataFlow loop handling**

In `reconcilePlanDataFlow` within `workflowPlan.ts`, replace the `nodesWithLoop` block. Instead of forcing `condition.left` from upstream, only ensure `maxIterations` and `onMaxIterations` defaults exist. Do NOT override `conditions`:

```typescript
const nodesWithLoop = nodes.map(n => {
  if (n.type !== 'loop') return n
  const params = { ...(n.params ?? {}) }
  // 仅补全 maxIterations / onMaxIterations 默认值，不强制覆盖条件
  if (!params.maxIterations) params.maxIterations = '3'
  if (!params.onMaxIterations) params.onMaxIterations = 'fail_fast'
  // 兼容旧格式：如果只有 condition.* 无 conditions，保留原样（解析层兼容）
  return { ...n, params }
})
```

- [ ] **Step 3: Replace loop condition UI in WorkflowStudioPropsAside.vue**

Replace the loop template section (lines ~636-682) that has the old `condition.left/op/right` fields. Import `ConditionGroupEditor` and the helper functions. Replace the `继续条件` form items with:

```vue
<template v-else-if="page.selectedNode.type === 'loop'">
  <WorkflowNodeConfigSection title="循环" :help="workflowNodeFieldHelp('loopTopology')">
    <div class="loop-condition-group">
      <span class="condition-label">继续条件</span>
      <ConditionGroupEditor
        :model-value="loopConditionGroup"
        :upstream-nodes="loopUpstreamNodes"
        :disabled="readOnly"
        @update:modelValue="onLoopConditionUpdate"
      />
    </div>
    <NFormItem label="最大轮次" :show-feedback="false">
      <NInputNumber
        :value="Number(page.selectedNode.params?.maxIterations ?? 3)"
        :min="1"
        :max="5"
        :disabled="readOnly"
        @update:value="v => updateNodeParam('maxIterations', String(v ?? 3))"
      />
    </NFormItem>
    <NFormItem label="超限策略" :show-feedback="false">
      <NSelect
        :value="String(page.selectedNode.params?.onMaxIterations || 'fail_fast')"
        :options="[
          { label: '失败终止 fail_fast', value: 'fail_fast' },
          { label: '正常退出 exit', value: 'exit' },
          { label: '降级 ReAct fallback_react', value: 'fallback_react' },
        ]"
        :disabled="readOnly"
        @update:value="v => updateNodeParam('onMaxIterations', String(v))"
      />
    </NFormItem>
  </WorkflowNodeConfigSection>
</template>
```

Add computed and methods in the `<script setup>`:

```typescript
import ConditionGroupEditor from './ConditionGroupEditor.vue'
import { normalizeLoopConditionGroup, writeLoopConditionGroup } from '../../utils/workflowPlan'
import { upstreamNodesOf } from '../../utils/workflowVariableRefs'

const loopConditionGroup = computed(() =>
  normalizeLoopConditionGroup(page.selectedNode?.params),
)

const loopUpstreamNodes = computed(() => {
  if (!page.plan || !page.selectedNode) return []
  return upstreamNodesOf(page.plan, page.selectedNode.id)
})

function onLoopConditionUpdate(group: WorkflowPlanEdgeConditionGroup) {
  if (!page.selectedNode) return
  const params = writeLoopConditionGroup(
    page.selectedNode.params ?? {},
    group,
  )
  page.plan = updateBusinessNode(page.plan, page.selectedNode.id, { params })
}
```

Add import for the type:

```typescript
import type { WorkflowPlanEdgeConditionGroup } from '../../api/workflows'
```

- [ ] **Step 4: Verify frontend builds**

Run: `cd sunshine-ui && npx vue-tsc --noEmit 2>&1 | head -20`
Expected: No errors.

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/components/workflows/WorkflowStudioPropsAside.vue sunshine-ui/src/utils/workflowPlan.ts
git commit -m "feat: loop editor uses ConditionGroupEditor for multi-condition support"
```

---

## Task 8: 前端 exclusive-gateway 编辑器集成 ConditionGroupEditor

**Files:**
- Modify: `sunshine-ui/src/components/workflows/WorkflowExclusiveEdgesSection.vue`
- Modify: `sunshine-ui/src/utils/workflowPlan.ts`

**Interfaces:**
- Consumes: `ConditionGroupEditor.vue` from Task 6.
- Produces: exclusive 出边条件使用 `ConditionGroupEditor`，数据格式为 `edge.condition = {logic, items}`。

- [ ] **Step 1: Add helper functions for edge condition normalization**

In `workflowPlan.ts`, add:

```typescript
/** 将 edge.condition 规范化为 {logic, items}（兼容旧 {left, op, right}） */
export function normalizeEdgeConditionGroup(
  condition: WorkflowPlanEdgeConditionGroup | WorkflowPlanEdgeCondition | undefined,
): WorkflowPlanEdgeConditionGroup {
  if (!condition) return { logic: 'and', items: [] }
  if ('items' in condition && Array.isArray(condition.items)) {
    return {
      logic: condition.logic === 'or' ? 'or' : 'and',
      items: condition.items,
    }
  }
  // 旧格式 {left, op, right}
  const single = condition as WorkflowPlanEdgeCondition
  if (single.op) {
    return { logic: 'and', items: [single] }
  }
  return { logic: 'and', items: [] }
}
```

- [ ] **Step 2: Rewrite WorkflowExclusiveEdgesSection.vue to use ConditionGroupEditor**

Replace the `updateExclusiveEdgeField` and `updateExclusiveEdge` logic. Each non-default edge now uses `ConditionGroupEditor`:

```vue
<script setup lang="ts">
import { computed, inject } from 'vue'
import WorkflowNodeConfigSection from './WorkflowNodeConfigSection.vue'
import ConditionGroupEditor from './ConditionGroupEditor.vue'
import { formatPlanNodeType } from '../../api/executionPlans'
import { workflowNodeFieldHelp } from './workflowFieldHelp'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import { countNodeDegree } from '../../utils/workflowPlanValidation'
import { normalizeEdgeConditionGroup } from '../../utils/workflowPlan'
import { upstreamNodesOf } from '../../utils/workflowVariableRefs'
import type { WorkflowPlanEdgeConditionGroup } from '../../api/workflows'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi
const readOnly = computed(() => !page.canEditPlan)

const gatewayTopology = computed(() => {
  const node = page.selectedNode
  if (!node || node.type !== 'exclusive-gateway' || !page.plan) return null
  const degree = countNodeDegree(page.plan, node.id)
  return { ...degree, okOut: degree.out >= 2 }
})

const exclusiveOutEdges = computed(() => {
  const node = page.selectedNode
  if (!node || node.type !== 'exclusive-gateway' || !page.plan) return []
  const labelById = new Map(
    (page.plan.nodes ?? []).map(n => [
      n.id,
      n.displayName?.trim() || formatPlanNodeType(n.type) || n.id,
    ]),
  )
  return (page.plan.edges ?? [])
    .filter(e => e.from === node.id)
    .map(e => ({
      ...e,
      toLabel: labelById.get(e.to) || e.to,
      conditionGroup: normalizeEdgeConditionGroup(e.condition),
    }))
})

const gatewayUpstreamNodes = computed(() => {
  if (!page.plan || !page.selectedNode) return []
  return upstreamNodesOf(page.plan, page.selectedNode.id)
})

function updateEdgeCondition(to: string, group: WorkflowPlanEdgeConditionGroup) {
  if (!page.plan || readOnly.value || !page.selectedNode) return
  const from = page.selectedNode.id
  const edges = (page.plan.edges ?? []).map(e => {
    if (e.to !== to || e.from !== from) return e
    if (group.items.length === 0) {
      const { condition: _c, ...rest } = e
      return rest
    }
    return { ...e, condition: group }
  })
  page.plan = { ...page.plan, edges }
}

function updateEdgeDefault(to: string, isDefault: boolean) {
  if (!page.plan || readOnly.value || !page.selectedNode) return
  const from = page.selectedNode.id
  const edges = (page.plan.edges ?? []).map(e => {
    if (e.from !== from) return e
    if (e.to === to) {
      if (isDefault) {
        const { condition: _c, ...rest } = e
        return { ...rest, default: true }
      }
      const { default: _d, ...rest } = e
      return { ...rest, condition: rest.condition ?? { logic: 'and', items: [{ left: '{{start.userQuery}}', op: 'contains', right: '' }] } }
    }
    return e
  })
  page.plan = { ...page.plan, edges }
}
</script>

<template>
  <WorkflowNodeConfigSection title="条件分支" :help="workflowNodeFieldHelp('exclusiveGatewayTopology')">
    <p v-if="gatewayTopology" class="join-topology-lines">
      <span :class="{ 'join-ok': gatewayTopology.okOut, 'join-warn': !gatewayTopology.okOut }">
        出边 {{ gatewayTopology.out }} 条{{ gatewayTopology.okOut ? '' : '（须 ≥ 2）' }}
      </span>
    </p>
    <p class="join-topology-hint">按条件选择其中一条路继续；须恰好一条默认分支。</p>
    <div
      v-for="edge in exclusiveOutEdges"
      :key="`${edge.from}->${edge.to}`"
      class="exclusive-edge-card"
    >
      <div class="exclusive-edge-head">
        <div class="exclusive-edge-target" :title="edge.to">
          <span class="exclusive-edge-to-label">-> {{ edge.toLabel }}</span>
          <span class="exclusive-edge-to-id">{{ edge.to }}</span>
        </div>
        <label class="exclusive-default">
          <input
            type="checkbox"
            :checked="!!edge.default"
            :disabled="readOnly"
            @change="updateEdgeDefault(edge.to, ($event.target as HTMLInputElement).checked)"
          />
          默认
        </label>
      </div>
      <template v-if="!edge.default">
        <ConditionGroupEditor
          :model-value="edge.conditionGroup"
          :upstream-nodes="gatewayUpstreamNodes"
          :disabled="readOnly"
          @update:modelValue="g => updateEdgeCondition(edge.to, g)"
        />
      </template>
    </div>
  </WorkflowNodeConfigSection>
</template>
```

Keep the existing `<style scoped>` section unchanged.

- [ ] **Step 3: Update reconcilePlanDataFlow edge handling**

In `workflowPlan.ts` `reconcilePlanDataFlow`, update the edges mapping to NOT override `condition.left` for edges that already have `condition.items` (new format):

```typescript
const edges = (plan.edges ?? []).map(e => {
  if (typeById.get(e.from) !== 'exclusive-gateway' || e.default) return e
  // 新格式 {logic, items} 保留不动
  if (e.condition && 'items' in e.condition) return e
  // 旧格式：保持原样（后端兼容）
  return e
})
```

- [ ] **Step 4: Verify frontend builds**

Run: `cd sunshine-ui && npx vue-tsc --noEmit 2>&1 | head -20`
Expected: No errors.

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/components/workflows/WorkflowExclusiveEdgesSection.vue sunshine-ui/src/utils/workflowPlan.ts
git commit -m "feat: exclusive-gateway editor uses ConditionGroupEditor for multi-condition"
```

---

## Task 9: SQL 标杆升级（knowledge-loop + knowledge-branch）

**Files:**
- Modify: `docker/mysql/init/13-sunshine-workflow-manager.sql`

**Interfaces:**
- Consumes: 后端 `PlanJsonParser` 兼容解析（Task 4）。
- Produces: `knowledge-loop` 的 loop params 使用 `conditions` 数组；`knowledge-branch` 的出边使用 `{logic, items}`。

- [ ] **Step 1: Update knowledge-loop plan_json in SQL**

在 `13-sunshine-workflow-manager.sql` 中，找到 `knowledge-loop` 的 `INSERT INTO workflow_version` 行。将其中的 loop 节点 params 从：

```json
"params":{"condition.left":"{{start.userQuery}}","condition.op":"contains","condition.right":"继续","maxIterations":"2","onMaxIterations":"exit","retry.maxAttempts":"1","retry.backoffMs":"500","retry.onFailure":"fail_fast"}
```

改为：

```json
"params":{"conditions":[{"left":"{{rag-l1o2o3p4.output}}","op":"contains","right":"继续"},{"left":"{{tool-t1o2o3p4.output}}","op":"not_contains","right":"已完成"}],"conditionLogic":"and","maxIterations":"2","onMaxIterations":"exit","retry.maxAttempts":"1","retry.backoffMs":"500","retry.onFailure":"fail_fast"}
```

同时更新 `workflow_definition` 的 description 和 `workflow_version` 的 reason，反映多条件语义。

> 注意：`{{rag-l1o2o3p4.output}}` 和 `{{tool-t1o2o3p4.output}}` 是 body 内节点变量。body 执行后 `wfCtx` 已含这些值，求值点正确。

- [ ] **Step 2: Update knowledge-branch plan_json in SQL**

在 `13-sunshine-workflow-manager.sql` 中，找到 `knowledge-branch` 的 `INSERT INTO workflow_version` 行。将出边条件从：

```json
"condition":{"left":"{{start.userQuery}}","op":"contains","right":"报销"}
```

改为：

```json
"condition":{"logic":"or","items":[{"left":"{{start.userQuery}}","op":"contains","right":"报销"},{"left":"{{start.userQuery}}","op":"contains","right":"发票"}]}
```

同时更新 reason 反映 OR 多条件语义。

- [ ] **Step 3: 同步更新 prompt-manager SQL 中的 loop 文档**

在 `17-sunshine-prompt-manager.sql` 中找到 loop 条件说明（第 210 行附近），更新文档描述：

```
**loop.params 必填**：conditions[]（每项 {left, op, right}）+ conditionLogic(and|or)；maxIterations(1-5)、onMaxIterations(fail_fast|exit|fallback_react)。兼容旧 condition.left/op/right。
```

- [ ] **Step 4: 重建 DB 种子数据**

```bash
mysql -h 127.0.0.1 -u root -proot123 sunshine_workflow_manager -e "DELETE FROM workflow_version WHERE workflow_id IN ('knowledge-loop', 'knowledge-branch'); DELETE FROM workflow_definition WHERE id IN ('knowledge-loop', 'knowledge-branch');"
```

然后仅导入 `knowledge-loop` 和 `knowledge-branch` 的 INSERT 语句（用 Python pymysql 或 mysql -e 逐行执行）。

- [ ] **Step 5: Commit**

```bash
git add docker/mysql/init/13-sunshine-workflow-manager.sql docker/mysql/init/17-sunshine-prompt-manager.sql
git commit -m "feat: upgrade knowledge-loop/branch seeds to composite conditions"
```

---

## Task 10: 验收脚本更新 + Live 验收

**Files:**
- Modify: `scripts/verify_workflow_studio_live.py`

**Interfaces:**
- Consumes: 升级后的 `knowledge-loop` / `knowledge-branch` 标杆（Task 9）。
- Produces: `suite_loop` 和 `suite_exclusive` 多条件断言。

- [ ] **Step 1: Update suite_loop assertions**

在 `scripts/verify_workflow_studio_live.py` 的 `suite_loop` 函数中，更新断言逻辑以验证多条件行为。

现有断言（无"继续"-> 1 轮；含"继续"-> 2 轮）仍然有效，因为标杆升级后条件是 `rag output contains "继续" AND tool output not_contains "已完成"`。

更新 `q1`（无"继续"）的语义注释：
- `q1 = "#knowledge-loop 分析青松假余额和我的待报销"` -- rag 输出不含"继续" -> 条件不满足 -> 1 轮退出
- `q2 = "#knowledge-loop 继续分析青松假余额和我的待报销"` -- rag 输出含"继续"且 tool 输出不含"已完成" -> 条件满足 -> 继续 2 轮

保持现有断言不变，添加注释说明多条件语义。

- [ ] **Step 2: Update suite_exclusive assertions**

在 `suite_exclusive` 函数中，验证 OR 多条件：
- 含"报销" -> 命中 `rag-f1a2b3c4`（财务制度检索）
- 含"发票" -> 命中 `rag-f1a2b3c4`（OR 第二个条件）
- 都不含 -> 走 default `rag-d5e6f7a8`（人事制度检索）

添加一个新测试用例：

```python
# 含「发票」-> OR 条件命中财务分支
conv3 = conversation_id(auth_json("POST", "/api/conversations", None, token))
q3 = "#knowledge-branch 发票申请流程"
print(f"  query={q3}")
chat_sse(token, conv3, q3, executionPreference="auto")
a3 = wait_assistant(token, conv3, max(HASH_TIMEOUT_SEC, 180))
if not workflow_hit(a3, "knowledge-branch"):
    raise RuntimeError(f"exclusive OR 路由失败 workflowId={a3.get('workflowId')}")
print("  [OK] 含「发票」-> OR 条件命中财务分支")
```

- [ ] **Step 3: 编译 + 重启服务**

```bash
cd orchestrator && ./mvnw compile -pl . -q
cd sunshine-ui && npm run build
python3 scripts/start.py
```

- [ ] **Step 4: 运行 Live 验收**

```bash
python3 scripts/verify_loop_live.py
python3 scripts/verify_exclusive_gateway_live.py
```

Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add scripts/verify_workflow_studio_live.py
git commit -m "test: update loop/exclusive live verification for composite conditions"
```

---

## Self-Review

### 1. Spec coverage

| Spec 要求 | 对应 Task |
|-----------|-----------|
| `PlanEdgeConditionGroup` 新增 | Task 1 |
| `not_eq`/`not_contains` 算子 | Task 1, 2 |
| `matchesGroup` 求值 | Task 2 |
| `PlanEdge`/`ExclusiveArm` 类型升级 | Task 3 |
| `PlanJsonParser` 兼容解析 | Task 4 |
| `WorkflowExecutor` loop 求值 | Task 5 |
| `WorkflowExecutor` exclusive 求值 | Task 5 |
| 前端 `ConditionGroupEditor` 组件 | Task 6 |
| 前端 loop 编辑器集成 | Task 7 |
| 前端 exclusive 编辑器集成 | Task 8 |
| 标杆 `knowledge-loop` 升级 | Task 9 |
| 标杆 `knowledge-branch` 升级 | Task 9 |
| Live 验收 | Task 10 |
| 向后兼容（旧单条件） | Task 4 (parser), Task 5 (executor) |

### 2. Placeholder scan

无 TBD/TODO。Task 9 中 SQL 具体替换内容已给出（旧 -> 新），实施时需精确定位 SQL 文件中的行。

### 3. Type consistency

- `PlanEdgeConditionGroup` 在 Task 1 定义，Task 2/3/4/5 消费，签名一致。
- `matchesGroup(PlanEdgeConditionGroup, WorkflowContext)` 在 Task 2 定义，Task 5 消费，签名一致。
- 前端 `WorkflowPlanEdgeConditionGroup` 在 Task 6 定义，Task 7/8 消费，签名一致。
- `normalizeLoopConditionGroup` / `writeLoopConditionGroup` 在 Task 7 定义，`normalizeEdgeConditionGroup` 在 Task 8 定义，无跨 Task 调用冲突。

### 4. 编译窗口

- Task 3 修改 `PlanEdge`/`ExclusiveArm` 类型后会导致编译错误（Task 4 修复 parser，Task 5 修复 executor）。这是预期的原子单元划分。Task 3 的 commit 时刻编译不通过，但 Task 4+5 完成后恢复。如果需要保持每个 commit 可编译，可将 Task 3+4+5 合并为一个 Task。但考虑到 TDD 节奏和 reviewer gate，当前划分更清晰。

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-28-workflow-composite-condition.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?