# Planner-Executor Kernel（H-0～H-4 + 过渡入口）Implementation Plan

> **状态**：✅ **已完成**（2026-08-13；冒烟 `scripts/verify_planner_harness_kernel_smoke.py`）  
> **Spec**：[2026-08-05-planner-executor-rebuild-design.md](../specs/2026-08-05-planner-executor-rebuild-design.md)（v10 · §7.0 进度）  
> **后续**：routing v6 + H-5 ✅（[unified-routing-v6-h5](./2026-08-13-unified-routing-v6-h5.md)）；仍待 H-6 前端、H-7 Live 全量、阶段 D / R-4 删旧 plan-workflow  

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 Planner-Executor **内核**：PlanNotebook Redis 单写、WORKER/`forWorker`、HarnessPlanner 单一循环、过渡入口（`harness.enabled` + `PLAN_WORKFLOW`→harness），在**不删**现有动态 Plan-Workflow 的前提下可灰度跑通 Plan→Worker→Assess。

**Architecture:** `PlannerHarnessExecutor` 编排 `PlannerHarnessLoop`；Planner = 重写后的 `AgentRole.PLANNER`（ReAct + H1 注入，Catalog `planner.harness`）；Worker = `AgentRole.WORKER` 经 `ReActAgentRuntime`（`forWorker()` + Catalog `harness.worker`）；H1 = `PlanNotebookStore`（键 `sunshine:plan:notebook:{sessionId}`）。命名与 AS `HarnessAgent` 无关。

**Tech Stack:** Java 17 / Spring Boot / WebFlux · AgentScope 2.0 · Redis (`StringRedisTemplate`) · JUnit5 + AssertJ · Nacos · Catalog（`19-sunshine-resource.sql`）

---

## Global Constraints

- **SSOT**：逻辑以 rebuild v7 为准；预算默认值抄 §8.1 长负载档（4h / Worker 1h / 12 波次等）
- **禁止阶段 D**：不得删除 `PlanWorkflowExecutor` / `WorkflowPlanner` / Approval；`harness.enabled=false` 时行为与今日一致
- **过渡入口**：`harness.enabled=true` 且 `ExecutionMode.PLAN_WORKFLOW` → `PlannerHarnessExecutor`；否则仍走旧 `PlanWorkflowExecutor`
- **Redis 键**：`sunshine:plan:notebook:{sessionId}`，TTL `604800`；对齐 [orchestrator-stateless](../specs/2026-08-03-orchestrator-stateless-design.md)
- **提示词**：正文只进 Catalog（`planner.harness` / `harness.worker`）；Java/Nacos 禁止硬编码长 prompt
- **S7**：harness **不**调用 `PlanValidator`；轻量校验 id/label/依赖环即可
- **S1**：无独立 Evaluator；`selfAssess` 由 Planner 产出；GoalAlignment 做**机械薄实现**（可只看 `staleRounds`+完成比启发式）
- **模型输出不二次加工**：不对 Planner/Worker 正文做截断/摘要兜底
- **命名隔离**：包名 `com.sunshine.orchestrator.plan.harness`；勿改 `HarnessAgentFactory`（AS Compaction）
- 业务代码禁止多余空行；改 `docs/nacos/*.yaml` 后 `python scripts/sync_nacos.py` + `python scripts/start.py --restart orchestrator`
- 编译：`mvn test -pl orchestrator -Dtest=… -q`；全量相关：`mvn test -pl orchestrator -Dtest='*Harness*,*PlanNotebook*,*WorkerContext*' -q`

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../plan/harness/PlanNotebook.java` | H1 POJO + `TaskItem` / `RoundRecord` / `NodeResult`；`renderForPlanner()` |
| `orchestrator/.../plan/harness/PlanNotebookStore.java` | Redis 单写接口 |
| `orchestrator/.../plan/harness/PlanNotebookStoreImpl.java` | `StringRedisTemplate` + Jackson；load 时 IN_PROGRESS→FAIL |
| `orchestrator/.../plan/harness/TaskQueueValidator.java` | 轻量结构校验（S7） |
| `orchestrator/.../plan/harness/GoalAlignmentValidator.java` | DEVIATED/STUCK 机械判定（薄） |
| `orchestrator/.../plan/harness/WorkerContextFactory.java` | `AssembledContext.forWorker` + upstream handoff |
| `orchestrator/.../plan/harness/HarnessPlanner.java` | 吐调度单元 / selfAssess / 综合回答（调 `AgentRuntime` PLANNER） |
| `orchestrator/.../plan/harness/PlannerHarnessLoop.java` | Plan→Validate→Execute→Assess 循环 + 预算 |
| `orchestrator/.../plan/harness/PlannerHarnessExecutor.java` | 过渡入口；对标 `ReactExecutor` |
| `orchestrator/.../plan/harness/WorkerDispatchTool.java` | Planner 可调的「跑 Worker」元工具（或等价调度 API） |
| `orchestrator/.../config/AgentExecutionProperties.java` | 嵌套 `Harness` 配置组 |
| `docs/nacos/sunshine-orchestrator.yaml` | `agent.execution.harness` |
| `docker/mysql/init/19-sunshine-resource.sql` | `planner.harness` / `harness.worker` 种子 |
| `orchestrator/.../agent/runtime/AgentRole.java` | +`WORKER` |
| `orchestrator/.../agent/runtime/AgentRunRequest.java` | +`worker(...)`；扩展 `planner(...)` 供 harness |
| `orchestrator/.../context/AssembledContext.java` | +`forWorker(...)` |
| `orchestrator/.../agent/runtime/ReActAgentRuntime.java` | 接受 WORKER；PLANNER 仍拒或改走 Facade |
| `orchestrator/.../agent/runtime/PlannerAgentRuntime.java` | **重写**：harness 路径 ReAct+H1；保留旧 path 仅当非 harness 调用方需要时可委托删除（本 plan：PlanWorkflow 仍直调 `WorkflowPlanner`，本类专供 harness） |
| `orchestrator/.../agent/runtime/AgentRuntimeFacade.java` | PLANNER→新 Runtime；WORKER→ReAct |
| `orchestrator/.../execution/ExecutionDispatcher.java` | 灰度分支 |
| `orchestrator/.../agent/ReActAgentFactory.java` | WORKER toolkit / maxIters 取 `task-max-iters` |
| Tests under `orchestrator/src/test/java/.../plan/harness/` | 各 Task 单测 |

---

### Task 1: Nacos + `AgentExecutionProperties.Harness`

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`agent.execution` 内、`plan-workflow` 旁）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/config/AgentExecutionPropertiesHarnessTest.java`

**Interfaces:**
- Produces: `AgentExecutionProperties.getHarness()` → `Harness`，字段默认值与 rebuild §8.1 v7 一致：
  - `enabled=false`, `maxRounds=12`, `maxTotalTasks=24`, `maxDurationMs=14_400_000L`, `staleRoundsThreshold=3`
  - `task.maxRetries=2`
  - `planner.timeoutMs=300_000L`, `maxAttempts=3`, `maxReplans=6`
  - `worker.timeoutMs=3_600_000L`, `maxSubAgents=5`
  - `notebook.redisTtlSeconds=604_800L`, `keyPrefix="sunshine:plan:notebook:"`, `compression.nearKeepRounds=10`
  - `session.idleTimeoutMs=14_400_000L`

- [ ] **Step 1: 写失败测试**

```java
@SpringBootTest(classes = AgentExecutionPropertiesHarnessTest.Cfg.class)
class AgentExecutionPropertiesHarnessTest {
    @TestConfiguration
    @EnableConfigurationProperties(AgentExecutionProperties.class)
    static class Cfg {
        @Bean
        static AgentExecutionProperties props() { return new AgentExecutionProperties(); }
    }
    @Autowired AgentExecutionProperties props;

    @Test
    void harnessDefaultsMatchLongLoadV7() {
        var h = props.getHarness();
        assertThat(h.isEnabled()).isFalse();
        assertThat(h.getMaxRounds()).isEqualTo(12);
        assertThat(h.getMaxTotalTasks()).isEqualTo(24);
        assertThat(h.getMaxDurationMs()).isEqualTo(14_400_000L);
        assertThat(h.getWorker().getTimeoutMs()).isEqualTo(3_600_000L);
        assertThat(h.getNotebook().getRedisTtlSeconds()).isEqualTo(604_800L);
        assertThat(h.getNotebook().getKeyPrefix()).isEqualTo("sunshine:plan:notebook:");
        assertThat(h.getNotebook().getCompression().getNearKeepRounds()).isEqualTo(10);
    }
}
```

（若项目配置绑定测试另有惯例，对齐 `AgentExecutionPropertiesAsyncToolTest` 写法。）

- [ ] **Step 2: 跑测确认失败**

Run: `mvn test -pl orchestrator -Dtest=AgentExecutionPropertiesHarnessTest -q`  
Expected: 无 `getHarness` / 编译失败

- [ ] **Step 3: 实现配置类 + yaml**

在 `AgentExecutionProperties` 增加 `private Harness harness = new Harness();` 及嵌套 `@Data` 静态类（字段如上）。  
在 `sunshine-orchestrator.yaml` 的 `agent.execution` 下插入完整 `harness:` 块（复制 rebuild §8.1 yaml）。

- [ ] **Step 4: 跑测通过 + sync**

Run: `mvn test -pl orchestrator -Dtest=AgentExecutionPropertiesHarnessTest -q`  
Then: `python scripts/sync_nacos.py`（本机有 Nacos 时）

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java \
  docs/nacos/sunshine-orchestrator.yaml \
  orchestrator/src/test/java/com/sunshine/orchestrator/config/AgentExecutionPropertiesHarnessTest.java
git commit -m "$(cat <<'EOF'
feat(orchestrator): add agent.execution.harness long-load defaults (4.14)

EOF
)"
```

---

### Task 2: `PlanNotebook` POJO + `renderForPlanner`

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/harness/PlanNotebook.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/harness/TaskItem.java`（或作 `PlanNotebook` 静态 record）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/plan/harness/PlanNotebookTest.java`

**Interfaces:**
- Produces（对齐 rebuild §5.0）:
  - `PlanNotebook` 字段：`originalGoal`, `userQuery`, `scene`, `taskQueue`(`Deque<TaskItem>`), `rounds`, `goalCompletion`, `nextDirection`, `createdAt`, `maxRounds`, `maxTotalTasks`, `currentRound`, `totalTasksCompleted`, `staleRounds`, `replanCount`
  - `TaskItem(taskId, label, status, dependsOn, constraints, expectedOutput, successCriteria)` — status ∈ `pending|in_progress|done|fail|obsolete`
  - `RoundRecord` / `NodeResult` 同 spec
  - **禁止**字段：`taskDecomposition` / `phases` / `completeness`
  - `String renderForPlanner(int nearKeepRounds)`：goal + taskQueue 摘要 + 近 N 轮 rounds；超阈时最老轮折叠为单行摘要（可用确定性截断，LLM 折叠可留 H-4 接线）

- [ ] **Step 1: 写失败测试**

```java
class PlanNotebookTest {
    @Test
    void newNotebookHasNoDecompositionFieldsAndDefaultBudgets() {
        PlanNotebook nb = PlanNotebook.create("goal", "query", "task", 12, 24);
        assertThat(nb.getMaxRounds()).isEqualTo(12);
        assertThat(nb.getMaxTotalTasks()).isEqualTo(24);
        assertThat(nb.getTaskQueue()).isEmpty();
        // 反射或序列化 JSON 不得出现这些键
        String json = new ObjectMapper().writeValueAsString(nb);
        assertThat(json).doesNotContain("taskDecomposition", "phases", "completeness");
    }

    @Test
    void renderForPlannerKeepsNearRounds() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "chat", 12, 24);
        for (int i = 0; i < 12; i++) {
            nb.appendRound(new RoundRecord(i, task("t"+i), List.of(), 0.1, "ok"));
        }
        String text = nb.renderForPlanner(10);
        assertThat(text).contains("t11").contains("t2");
        assertThat(text).doesNotContain("t0"); // 最老两轮被折叠/省略
    }
}
```

- [ ] **Step 2: 跑测失败** — `mvn test -pl orchestrator -Dtest=PlanNotebookTest -q`

- [ ] **Step 3: 实现 POJO**（可变集合用线程不安全即可；单会话单写）

- [ ] **Step 4: 跑测通过**

- [ ] **Step 5: Commit** `feat(orchestrator): add PlanNotebook H1 model for planner harness`

---

### Task 3: `PlanNotebookStore` Redis 单写 + 恢复规则

**Files:**
- Create: `.../plan/harness/PlanNotebookStore.java`
- Create: `.../plan/harness/PlanNotebookStoreImpl.java`
- Test: `.../plan/harness/PlanNotebookStoreTest.java`（可用嵌入式或 mock `StringRedisTemplate`）

**Interfaces:**
- Produces:
  ```java
  public interface PlanNotebookStore {
      void save(PlanNotebook notebook); // 需 sessionId：在 notebook 上增加 sessionId 字段或 save(sessionId, nb)
      Optional<PlanNotebook> load(String sessionId);
      void delete(String sessionId);
      void renewTtl(String sessionId);
  }
  ```
- 键：`properties.getHarness().getNotebook().getKeyPrefix() + sessionId`
- `load`：**先**反序列化，再把所有 `in_progress` task → `fail`，然后可选写回（或返回修复后对象由 Loop save）
- TTL：`redisTtlSeconds`；`renewTtl` 用 `expire`

- [ ] **Step 1: 写失败测试**（mock Redis）

```java
@ExtendWith(MockitoExtension.class)
class PlanNotebookStoreTest {
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    AgentExecutionProperties props = new AgentExecutionProperties();

    @BeforeEach
    void bind() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void saveLoadRoundTrip() throws Exception {
        PlanNotebookStoreImpl store = new PlanNotebookStoreImpl(redis, props, new ObjectMapper());
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.setSessionId("s1");
        store.save(nb);
        verify(values).set(eq("sunshine:plan:notebook:s1"), anyString(), eq(Duration.ofSeconds(604_800)));
        when(values.get("sunshine:plan:notebook:s1")).thenReturn(new ObjectMapper().writeValueAsString(nb));
        assertThat(store.load("s1")).isPresent();
    }

    @Test
    void loadMarksInProgressTasksFailed() throws Exception {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.setSessionId("s1");
        nb.getTaskQueue().add(new TaskItem("t1", "x", "in_progress", List.of(), "", "", ""));
        when(values.get(anyString())).thenReturn(new ObjectMapper().writeValueAsString(nb));
        PlanNotebook loaded = new PlanNotebookStoreImpl(redis, props, new ObjectMapper()).load("s1").orElseThrow();
        assertThat(loaded.getTaskQueue().peekFirst().status()).isEqualTo("fail");
    }
}
```

（`set` 重载若项目用 `set(key, val)` + 单独 `expire`，测试对齐实现。）

- [ ] **Step 2–4: TDD 实现**（仿 `WorkspaceSandboxStore`）

- [ ] **Step 5: Commit** `feat(orchestrator): Redis PlanNotebookStore with in_progress repair`

---

### Task 4: `WORKER` 角色 + `forWorker` + `AgentRunRequest.worker`

**Files:**
- Modify: `AgentRole.java`, `AgentRunRequest.java`, `AssembledContext.java`
- Modify: `AgentRuntimeFacade.java`, `ReActAgentRuntime.java`（允许 WORKER；PLANNER 仍由 PlannerAgentRuntime）
- Modify: `ReActAgentFactory.java`（WORKER：taskboard 可选关；maxIters←`react.taskMaxIters`；工具白名单）
- Test: `AgentRunRequestTest` 增补；`AssembledContextTest`（新建或扩）

**Interfaces:**
- Produces:
  - `AgentRole.WORKER`
  - `AssembledContext.forWorker(String stablePrefixBlock, String dynamicQueryBlock)` — **不**填 L2/L3/mid/near；稳定前缀进 `projectGuideBlock` 或专用约定字段；动态段仅经 `query`/injected 传入也可。推荐：
    - `l2/far/mid/near/l3` 全空
    - `projectGuideBlock = stablePrefix`（字节稳定）
    - 动态 upstream 由调用方拼进 `query` 或 `injectedBlocks`（query 附近）
  - `AgentRunRequest.worker(AssembledContext memory, String query, List<String> toolWhitelist, String userId, String tenantId, String assistantMessageId, String conversationId, int maxIters, String parentRunId)`
  - Timeline：新建 `TimelineBinding.WORKER_NESTED` 或复用 `SUB_COMPRESSED`（plan 写明选哪个；推荐 **新枚举值** 便于前端后期区分）

- [ ] **Step 1: 测试**

```java
@Test
void workerFactorySetsRoleAndWhitelist() {
    var req = AgentRunRequest.worker(
            AssembledContext.forWorker("STABLE", ""),
            "do task", List.of("sandbox__exec"), "u", "t", "a1", "c1", 100, "parent");
    assertThat(req.role()).isEqualTo(AgentRole.WORKER);
    assertThat(req.toolWhitelist()).containsExactly("sandbox__exec");
    assertThat(req.memory().projectGuideBlock()).isEqualTo("STABLE");
    assertThat(req.memory().l2SystemBlock()).isEmpty();
}
```

- [ ] **Step 2–4: 实现并让 `ReActAgentRuntime` 对 WORKER 走与 MAIN 类似的 stream，但对 PLANNER 继续拒绝（改由 Facade）**

- [ ] **Step 5: Commit** `feat(orchestrator): AgentRole.WORKER and AssembledContext.forWorker`

---

### Task 5: Catalog 种子 `planner.harness` / `harness.worker`

**Files:**
- Modify: `docker/mysql/init/19-sunshine-resource.sql`（紧接 `planner.prompt` 后）
- 运维：已有库用 `/prompts` UI 或 SQL 补种（plan 执行时二选一写清）

**Interfaces:**
- Produces Catalog IDs：
  - `planner.harness`：动作经 `plan_submit`/`self_assess`/`dispatch_worker` 工具表达（v15 起不再输出文本 JSON）；信息不足先调研；禁止 full/hier；说明可调 worker 工具
  - `harness.worker`：执行单元内细则；handoff 摘要格式；不全局重规划

- [ ] **Step 1: 追加 INSERT IGNORE**（`prompt_definition` + `prompt_version`，`kind` 可用 `planner` / `harness`）

正文要点（写入 SQL `content_text`，中文）：

**planner.harness**（摘要，实施时写完整段落）：
```
你是专业模式 Planner。根据用户目标与 H1 计划状态，调用 plan_submit 提交调度单元、self_assess 汇报决策、dispatch_worker 调度 Worker，信息已足时正文综合回答（v15 起动作经工具表达，不输出文本 JSON）。
规则：信息不足先排调研单元；细则留给 Worker；禁止输出 full/hierarchical 模式标签；依赖用 dependsOn。
```

**harness.worker**：
```
你是 Worker。只执行当前单元目标，使用工具完成任务，结束后给出 handoff 摘要（做了什么/结论/未决）。禁止全局重规划。
```

- [ ] **Step 2: 本地若用 init 卷，重建或手动 INSERT；验证 resource-manager `/prompts` 可见**

- [ ] **Step 3: Commit** `feat(catalog): seed planner.harness and harness.worker prompts`

---

### Task 6: 轻量校验 + GoalAlignment 薄实现

**Files:**
- Create: `TaskQueueValidator.java`, `GoalAlignmentValidator.java`
- Test: `TaskQueueValidatorTest.java`, `GoalAlignmentValidatorTest.java`

**Interfaces:**
- `TaskQueueValidator.validate(List<TaskItem>)` → `Optional<String> error`：非空 id/label、dependsOn 无环、引用存在
- `GoalAlignmentValidator.assess(PlanNotebook)` → enum `OK | DEVIATED | STUCK`
  - STUCK：`staleRounds >= staleRoundsThreshold`
  - DEVIATED：启发式——连续 round `goalCompletion` 下降或长期 <0.1 且已完成 task≥1（写清阈值常量）
  - 无 LLM

- [ ] **Step 1–4: TDD**

```java
@Test
void rejectsDependencyCycle() {
    var items = List.of(
        new TaskItem("a", "A", "pending", List.of("b"), "", "", ""),
        new TaskItem("b", "B", "pending", List.of("a"), "", "", ""));
    assertThat(TaskQueueValidator.validate(items)).isPresent();
}

@Test
void stuckWhenStaleRoundsReachThreshold() {
    PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
    nb.setStaleRounds(3);
    assertThat(new GoalAlignmentValidator(3).assess(nb)).isEqualTo(Alignment.STUCK);
}
```

- [ ] **Step 5: Commit** `feat(orchestrator): harness task queue and goal alignment validators`

---

### Task 7: `WorkerContextFactory` + `WorkerDispatchTool`

**Files:**
- Create: `WorkerContextFactory.java`, `WorkerDispatchTool.java`（`@SunshineTool` 或 orchestrator 内建元工具，对齐 `SpawnSubagentTool` 注册方式）
- Test: `WorkerContextFactoryTest.java`

**Interfaces:**
- `AssembledContext WorkerContextFactory.build(TaskItem task)`
  - 稳定前缀：`harness.worker` 模板占位替换后的 taskGoal/constraints/expectedOutput/successCriteria（同一 plan run 内不变；工具白名单由 `AgentRunRequest.toolWhitelist` 运行时控制注册，不写入 prompt）
  - 动态：按 `dependsOn` 从 `rounds`/已完成 task 取 handoff `summary`，拼进 query 段
- `WorkerDispatchTool`：参数 `taskId`；由 Loop/Planner 调用 → `AgentRuntime.run(workerRequest)`；结果写回 notebook（status/done + NodeResult.summary）

- [ ] **Step 1–4: TDD Factory**（稳定前缀不含 upstream；换 upstream 后 `projectGuideBlock` 字节不变）

```java
@Test
void stablePrefixListsTaskContractAndWhitelistToolIds() {
    // 同一 task 契约 + whitelist → projectGuideBlock 含契约字段与工具 ID，不含 upstream handoff
}
```

- [ ] **Step 5: Commit** `feat(orchestrator): WorkerContextFactory and worker dispatch tool`

---

### Task 8: `HarnessPlanner` + 重写 `PlannerAgentRuntime`

**Files:**
- Create: `HarnessPlanner.java`
- Modify: `PlannerAgentRuntime.java`（改为 ReAct + Catalog `planner.harness` + H1 `injectedBlocks`；**删除**对 `WorkflowPlanner` 的依赖）
- Modify: `AgentRuntimeFacade` / `ReActAgentFactory`（PLANNER scene=`plan`；注册 WorkerDispatchTool；`request_decision` 依 `react.decision.enabled` 注册——D12 ✅，见 [specs/archive/2026-08-12-react-request-decision-planner-d12.md](../specs/archive/2026-08-12-react-request-decision-planner-d12.md)）
- Test: `HarnessPlannerTest.java`（mock `AgentRuntime`）

**Interfaces:**
- `HarnessPlanner.planNext(PlanNotebook, ExecutionStreamContext)` → 更新 `taskQueue`（解析模型 JSON；失败重试 `planner.maxAttempts`）
- `HarnessPlanner.selfAssess(PlanNotebook, …)` → 写 `goalCompletion` / `nextDirection`
- `HarnessPlanner.synthesizeAnswer(…)` → `Flux<StreamToken>` 综合回答
- Planner 上下文：`ContextAssembler.assemble(...)` + `injectedBlocks` 含 `notebook.renderForPlanner(nearKeepRounds)`

**注意：** `PlanWorkflowExecutor` 继续 **直接**调 `WorkflowPlanner`，不经过 `PlannerAgentRuntime`。因此重写本类 **不破坏**旧 plan-workflow。

- [ ] **Step 1: 单测** mock runtime 返回固定调度 JSON，断言 queue 写入

- [ ] **Step 2–4: 实现**；跑 `ReActAgentRuntimeTest` / `PlannerAgentRuntime` 相关测，修复 PLANNER 断言（旧「仅 plan 步」用例改为 harness 语义或删）

- [ ] **Step 5: Commit** `feat(orchestrator): HarnessPlanner and ReAct-based PlannerAgentRuntime`

---

### Task 9: `PlannerHarnessLoop` + 预算熔断

**Files:**
- Create: `PlannerHarnessLoop.java`
- Test: `PlannerHarnessLoopTest.java`（mock planner/worker/store）

**Interfaces:**
- `Flux<StreamToken> run(ExecutionStreamContext ctx, PlanNotebook notebook)`
- 循环：
  1. 检查 `maxDurationMs` / `maxRounds` / `maxReplans` / `maxTotalTasks`
  2. `HarnessPlanner.planNext` → `TaskQueueValidator` → 失败则 replanCount++
  3. `GoalAlignmentValidator`：STUCK → synthesize；DEVIATED → replan
  4. 选一波 ready tasks（dependsOn 已 done）并行/串行调 Worker（本 plan **允许串行先做**，并行作可选）
  5. handoff：更新 H1 +（Planner L1 尾部：run 内 messages 由 AgentScope tool_result 自然形成）
  6. `selfAssess` → done? synthesize : continue
- 每轮结束 `store.save`；超时 Worker → 按 `task.maxRetries` 再跑 → `fail` → replan
- SSE 最小契约（供后续 H-6）：`plan` 步、`worker-{taskId}` 步、最终 content；步结构对齐现有 `StreamToken` / `ProcessingTimelineSession` API（读 `ReactExecutor` / `PlanTimeline` 用法，**不要**发明第二套 phase）

- [ ] **Step 1: 测试**

```java
@Test
void stopsWhenMaxRoundsExhaustedAndSynthesizes() {
    // mock planNext 每次只加 1 pending；maxRounds=2 → 最终调用 synthesizeAnswer
}
@Test
void workerFailAfterRetriesTriggersReplan() { … }
```

- [ ] **Step 2–4: 实现循环**

- [ ] **Step 5: Commit** `feat(orchestrator): PlannerHarnessLoop single-cycle engine`

---

### Task 10: `PlannerHarnessExecutor` + `ExecutionDispatcher` 过渡分支

**Files:**
- Create: `PlannerHarnessExecutor.java`
- Modify: `ExecutionDispatcher.java`
- Test: `ExecutionDispatcherHarnessBranchTest.java`（或扩现有 dispatcher 测）

**Interfaces:**
- `PlannerHarnessExecutor.execute(ExecutionStreamContext ctx): Flux<StreamToken>`
  - sessionId ← `conversationId`（缺则 assistantMessageId）
  - `store.load` or `PlanNotebook.create(goal=query, …)` 填预算自 `Harness` props
  - `loop.run`；`renewTtl`；终态错误 → 若配置允许可降级 `ReactExecutor`（复用 plan-workflow `fallbackReact` 开关语义，读 `planWorkflow.fallbackReact` 或 harness 内复制 `fallbackReact.enabled`——**本 plan 在 Harness 下增加 `fallbackReact.enabled=true` 字段**，默认 true）
- Dispatcher：

```java
case PLAN_WORKFLOW -> harnessPropertiesEnabled(ctx)
        ? plannerHarnessExecutor.execute(ctx)
        : planWorkflowExecutor.execute(ctx);
```

`harnessPropertiesEnabled`：注入 `AgentExecutionProperties`，`getHarness().isEnabled()`。

- [ ] **Step 1: 测试** enabled=false → 调 planWorkflow；enabled=true → 调 harness（mock）

- [ ] **Step 2–4: 实现**

- [ ] **Step 5: Commit** `feat(orchestrator): wire PLAN_WORKFLOW to harness when enabled`

---

### Task 11: 内核冒烟（非完整 H-7）

**Files:**
- Create: `scripts/verify_planner_harness_kernel_smoke.py`（可选但推荐）
- 或文档化手工步骤

**Steps:**
- [x] `harness.enabled: true` → sync_nacos → restart orchestrator
- [x] Chat 选「动态规划」发简单任务（如「分两步：先列出要点再总结」）— `scripts/verify_planner_harness_kernel_smoke.py`
- [x] 日志出现 `PlannerHarnessLoop` / Redis 键 `sunshine:plan:notebook:*`（脚本断言）
- [x] `harness.enabled: false` → 旧 Approval/DAG 行为恢复（脚本 docstring 文档化回滚步骤）
- [x] Commit smoke 脚本（若写了）：`test(scripts): planner harness kernel smoke`

---

## Out of scope（写入后续 plan）

| 项 | 去向 |
|----|------|
| `fast`/`pro`/`workflow` + `ResourceDispatcher` | routing v6 + H-5 plan |
| OperationStack 分层 / TaskBoard 一二级 | H-6 plan |
| `verify_planner_executor_live.py` P1–P8 | H-7 plan |
| 删除 PlanWorkflow/Approval/Catalog 旧条目 | 阶段 D |
| D12 `request_decision` on Planner | 独立切片 |
| Activity 跨机（stateless B2/B3） | orchestrator-stateless |

---

## Spec coverage（自检）

| Spec | Task |
|------|------|
| H-0 PlanNotebook | T2 |
| H-1 Redis Store | T3 |
| H-2 IN_PROGRESS→FAIL | T3 |
| H-3 Planner/校验/WORKER/forWorker | T4–T8 |
| H-4 Loop + WorkerContextFactory | T7, T9 |
| §8.1 长负载配置 | T1 |
| Catalog §8.2 | T5 |
| 过渡入口（非完整 H-5） | T10 |
| S7 不复用 PlanValidator | T6 |
| 不删旧 plan-workflow | T10 分支 + Global Constraints |

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-13-planner-executor-kernel.md`.

**Two execution options:**

1. **Subagent-Driven（推荐）** — 每 Task 新开子代理，Task 间复查  
2. **Inline Execution** — 本会话按 executing-plans 连续做并设检查点  

Which approach?
