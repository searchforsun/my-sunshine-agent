# Unified Routing v6 + Planner H-5 Implementation Plan

> **状态**：✅ 已完成（2026-08-13）  
> **Spec**：[unified-routing v6](../specs/2026-07-29-unified-routing-design.md) · [rebuild §7 H-5](../specs/2026-08-05-planner-executor-rebuild-design.md)  
> **前置**：4.14 kernel ✅（[planner-executor-kernel](./2026-08-13-planner-executor-kernel.md)）— `PlannerHarnessExecutor` 可跑  
> **本 plan 不做（仍延期）**：R-4 / 阶段 D 删 `PlanWorkflow*`；H-6 分层时间线+TaskBoard；H-7 §9.2 全量 Live；`intent.classifier` Catalog **live** 版本 bump；skill-sticky 深改；phase5 `callSite` 全量落库  
>
> **Commits（T1–T7）**：`9b4c1b52` modes · `054e5ee8` UI · `2d6d0ee8` ResourceDispatcher · `8cf62f6e` pin mode · `8ff70844` dual-track · `5d87f4c8` persist · `27e88b41` smoke  


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户显式三模式 `fast` / `pro` / `workflow` 钉死分发：快速→`ReactExecutor`，专业→`PlannerHarnessExecutor`，工作流→`WorkflowExecutor`；意图链按轨 A/B 收集资源且**永不改写** `executionMode`；用协议+Dispatcher 取代 `auto`/`plan-workflow`/`ForcedExecutionRouter` 的旧语义。

**Architecture:** 请求体 `executionMode`（必填，过渡可读旧 `executionPreference`）→ `ExecutionPlanRouter` 分轨收集 → `ResourceDispatcher`（由 `ExecutionDispatcher` 演进）只读 mode 分发。`pro` **直接**进 harness（不再依赖 `PLAN_WORKFLOW` 别名）；`harness.enabled=false` 时 `pro` **显式失败/提示**，禁止静默改 `fast`、禁止回落旧 DAG Approval。旧 `PlanWorkflowExecutor` 代码本 plan **保留但不进主路径**（阶段 D 再删）。

**Tech Stack:** Java 17 / Spring WebFlux · Vue3 + Naive UI · JUnit5 + AssertJ · 现有 `RoutingPolicyChain` / `IntentRouter` / Catalog

## Global Constraints

- **SSOT**：路由逻辑以 [routing v6](../specs/2026-07-29-unified-routing-design.md) 为准；专业执行体以 [rebuild](../specs/2026-08-05-planner-executor-rebuild-design.md) 为准
- **四轴**：`kind` / `executionMode` / `biz_scene` / `callSite` — **禁止**互写（本 plan 至少打通 `executionMode` + 透传 `kind`）
- **禁止** L3 / IntentRouter 输出或改写执行模式（无 `planMode`、无 `auto` 判路径）
- **禁止** workflow 未命中时静默降级 `fast`
- **禁止** `pro` 静默改 `fast`；harness 关则明确错误
- **禁止阶段 D**：不得删除 `PlanWorkflowExecutor` / `WorkflowPlanner` / Approval 源文件（可从 Dispatcher 断流）
- **禁止 H-6**：不改 TaskBoard / PlanApproval UI 大清理（选择器三模式除外）
- 模型输出不二次加工；提示词正文只进 Catalog
- 改 `docs/nacos/*.yaml` → `python scripts/sync_nacos.py` + `python scripts/start.py --restart orchestrator`
- 编译：`mvn test -pl orchestrator -Dtest=… -q`；前端：`cd sunshine-ui && npm test` / `npx vitest`（若有）或类型检查

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../routing/ExecutionMode.java` | 枚举改为 `FAST` / `PRO` / `WORKFLOW` + 旧值兼容解析 |
| `orchestrator/.../routing/ExecutionPreference.java` | 过渡：`fast`/`pro`/`workflow` wire；旧 `auto`/`react`/`plan-workflow` 映射；最终可标 `@Deprecated` |
| `sunshine-ui/src/api/executionModes.ts` | 三选项 + mention 门控 + 旧值读映射 |
| `sunshine-ui/.../ExecutionModeSelector.vue` | 文案：快速 / 专业 / 工作流 |
| `orchestrator/.../execution/ResourceDispatcher.java` | 新建或由 `ExecutionDispatcher` 重命名：三分支分发 |
| `orchestrator/.../routing/ExecutionPlanRouter.java` | 用户 mode 必填路径；取消 `AUTO` 走「自判模式」 |
| `orchestrator/.../routing/ForcedExecutionRouter.java` | 演进为「钉死 mode 的资源收集」或内联进 Router；本 plan 末标记废止，**删类可留到 R-4** |
| `orchestrator/.../routing/policy/*` | 轨 A/B：L0/L1 过滤；L3 输出契约 |
| `orchestrator/.../agent/IntentRouter.java` | 分轨 prompt；禁止输出 mode |
| `scripts/verify_routing_v6_smoke.py` | V1/V3/V4/V5 冒烟 |

---

## 迁移对照（全 plan 共用）

| 旧 wire / 枚举 | 新 |
|----------------|-----|
| `auto` | → `fast`（默认；**不再**表示「路由自判模式」） |
| `react` | → `fast` |
| `plan-workflow` | → `pro` |
| `workflow` | → `workflow` |
| `ExecutionMode.REACT` | `FAST` |
| `ExecutionMode.PLAN_WORKFLOW` | `PRO` |
| `ExecutionMode.WORKFLOW` | `WORKFLOW` |

解析器须 **双向过渡**：读库/旧会话仍含 `react`/`plan-workflow`/`auto` 时映射到新枚举；写出 API 一律新值。

---

### Task 1: 协议枚举 `ExecutionMode` = fast/pro/workflow

**Files:**
- Modify: `orchestrator/.../routing/ExecutionMode.java`
- Modify: `orchestrator/.../routing/ExecutionPreference.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/routing/ExecutionModeMigrationTest.java`

**Interfaces:**
- Produces: `ExecutionMode.FAST|PRO|WORKFLOW`；`ExecutionMode.from(String)` 兼容旧 wire
- Produces: `ExecutionPreference.from` 映射表（见上）；`wireValue()` 写出 `fast|pro|workflow`
- Consumes: 无

- [ ] **Step 1: 写失败单测**

```java
@Test
void from_mapsLegacyWiresToV6() {
    assertThat(ExecutionMode.from("react")).isEqualTo(ExecutionMode.FAST);
    assertThat(ExecutionMode.from("plan-workflow")).isEqualTo(ExecutionMode.PRO);
    assertThat(ExecutionMode.from("fast")).isEqualTo(ExecutionMode.FAST);
    assertThat(ExecutionMode.from("pro")).isEqualTo(ExecutionMode.PRO);
    assertThat(ExecutionMode.from("workflow")).isEqualTo(ExecutionMode.WORKFLOW);
    assertThat(ExecutionPreference.from("auto").wireValue()).isEqualTo("fast");
    assertThat(ExecutionPreference.from("plan-workflow").wireValue()).isEqualTo("pro");
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `mvn test -pl orchestrator -Dtest=ExecutionModeMigrationTest -q`  
Expected: FAIL（枚举尚无 FAST/PRO）

- [ ] **Step 3: 改枚举 + 兼容解析**

`ExecutionMode`：

```java
public enum ExecutionMode {
    FAST, PRO, WORKFLOW;
    public static ExecutionMode from(String raw) {
        if (raw == null || raw.isBlank()) return FAST;
        return switch (raw.toLowerCase().replace('_', '-')) {
            case "workflow", "pipeline" -> WORKFLOW;
            case "pro", "plan-workflow", "plan", "plan_workflow" -> PRO;
            case "fast", "react", "agent", "auto" -> FAST;
            default -> FAST;
        };
    }
}
```

`ExecutionPreference`：取值改为 `FAST, PRO, WORKFLOW`（或保留旧名常量但 `wireValue` 出新串）；`isForced()` 恒 true（无 auto）；`allowsSkillBinding()` = FAST|PRO。

- [ ] **Step 4: 全量编译修复引用**

Run: `mvn test -pl orchestrator -Dtest=ExecutionModeMigrationTest,ForcedExecutionRouterTest,ExecutionPlanRouterTest,ExecutionDispatcherHarnessBranchTest -q`  
修复 `REACT`→`FAST`、`PLAN_WORKFLOW`→`PRO` 的 switch/import（含 harness 测里的 `ExecutionPlan`）。

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/routing/ExecutionMode.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/routing/ExecutionPreference.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/routing/ExecutionModeMigrationTest.java \
  # 及本任务强制的引用修复文件
git commit -m "$(cat <<'EOF'
feat(orchestrator): map execution modes to fast/pro/workflow

EOF
)"
```

---

### Task 2: 前端选择器三模式 + mention 门控

**Files:**
- Modify: `sunshine-ui/src/api/executionModes.ts`
- Modify: `sunshine-ui/src/api/executionModeIcons.ts`（若有）
- Modify: `sunshine-ui/src/components/chat/ExecutionModeSelector.vue`（仅文案依赖 options）
- Modify: `sunshine-ui/src/composables/useExecutionPreference.ts`（默认 `fast`；读旧 localStorage 映射）
- Test: `sunshine-ui/src/api/executionModes.spec.ts`（新建；若无 vitest 则用现有测试惯例）

**Interfaces:**
- Produces: `ExecutionPreference = 'fast' | 'pro' | 'workflow'`
- Produces: `normalizeExecutionPreference(raw): ExecutionPreference`（`auto`/`react`→`fast`，`plan-workflow`→`pro`）
- Produces: mention 门控 — fast/pro：`$` `@`；workflow：仅 `#`

- [ ] **Step 1: 写失败测**

```ts
expect(normalizeExecutionPreference('plan-workflow')).toBe('pro')
expect(allowsWorkflowMention('fast')).toBe(false)
expect(allowsWorkflowMention('workflow')).toBe(true)
expect(allowsAgentMention('pro')).toBe(true)
```

- [ ] **Step 2: 改 `EXECUTION_MODE_OPTIONS`**

三项：`快速/fast`、`专业/pro`、`工作流/workflow`；删除 auto / 动态规划文案。默认 `fast`。

- [ ] **Step 3: Chat 输入 mention 消费处**确认 `allowsWorkflowMention(preference)` 门控 `#`（已有则只改 options；无则补一行 gate）。

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): execution mode selector fast/pro/workflow

EOF
)"
```

---

### Task 3: `ResourceDispatcher` — 三模式钉死分发（H-5 核心）

**Files:**
- Modify: `orchestrator/.../execution/ExecutionDispatcher.java` → 演进为三分支（可保留类名一版，或新建 `ResourceDispatcher` 并让旧类委托）
- Modify: `orchestrator/.../plan/harness/PlannerHarnessExecutor.java`（Javadoc：`PRO` 主入口）
- Test: `orchestrator/.../execution/ResourceDispatcherTest.java`（或扩 `ExecutionDispatcherHarnessBranchTest`）

**Interfaces:**
- Consumes: `ExecutionStreamContext.plan().mode()` ∈ FAST|PRO|WORKFLOW
- Produces:
  - `FAST` → `reactExecutor.execute`
  - `PRO` → `plannerHarnessExecutor.execute`（**若** `harness.enabled=false` → `Flux.error` 明确文案，**不**调 PlanWorkflow、**不**改 mode）
  - `WORKFLOW` → `workflowExecutor.execute`
- **不再**：`PLAN_WORKFLOW` + `harness.enabled` 双路径；**不再**主路径调用 `planWorkflowExecutor`

- [ ] **Step 1: 写失败测**

```java
@Test
void pro_dispatchesToHarness_whenEnabled() { … }

@Test
void pro_failsExplicitly_whenHarnessDisabled() { … }

@Test
void fast_dispatchesToReact() { … }

@Test
void workflow_dispatchesToWorkflowExecutor() { … }

@Test
void neverCallsPlanWorkflowExecutor_onProOrFast() { … }
```

- [ ] **Step 2: 实现 switch**

```java
return switch (mode) {
    case FAST -> reactExecutor.execute(ctx);
    case PRO -> {
        if (!harnessEnabled()) {
            yield Flux.error(new BizException(/* 明确：专业模式未启用 harness */));
        }
        yield plannerHarnessExecutor.execute(ctx);
    }
    case WORKFLOW -> workflowExecutor.execute(ctx);
};
```

- [ ] **Step 3: 跑测 + 修复 `ChatStreamExecutor` 注入**

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(orchestrator): ResourceDispatcher pins fast/pro/workflow

EOF
)"
```

---

### Task 4: 路由入口 — 取消 auto 自判模式；用户 mode 钉死收集

**Files:**
- Modify: `orchestrator/.../routing/ExecutionPlanRouter.java`
- Modify: `orchestrator/.../routing/ForcedExecutionRouter.java`（或合并进 Router）
- Modify: `orchestrator/.../routing/policy/RoutingContext.java` — 字段 `executionMode` / `kind`；`preference` 过渡
- Modify: `orchestrator/.../controller/ChatController.java` / DTO — 读 `executionMode`，兼容旧 `executionPreference`
- Test: `ExecutionPlanRouterV6Test.java`

**Interfaces:**
- Consumes: 请求 `executionMode`（缺省→`fast`）；`kind`（可选，默认 chat）
- Produces: `ExecutionPlan` 的 `mode` **恒等于**用户选择；L0–L3 只填 `workflowId` / skill / agent 相关 params
- 行为：无 `AUTO`「整链自选 REACT vs PLAN_WORKFLOW」；原 `ForcedExecutionRouter` 逻辑变为**唯一**路径（所有用户模式均「钉死 mode + 收集绑定」）

- [ ] **Step 1: 单测**

```java
@Test
void userPro_neverBecomesFast_evenIfL3SaysReact() { … }

@Test
void userWorkflow_withoutCandidate_errors_notFast() { … }
```

- [ ] **Step 2: 实现** — `route(ctx)`：解析 mode → `resolvePinned(ctx, mode)`；IntentRouter 结果经 `applyLockedMode`（已有）合并绑定。

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(orchestrator): pin executionMode; stop auto mode judgement

EOF
)"
```

---

### Task 5: 分轨收集 L0/L1 + L3 契约（轨 A/B）

**Files:**
- Modify: `orchestrator/.../routing/policy/UnifiedRuleRoutingPolicy.java`（或 Chain）— 轨 A 跳过 workflow 规则；轨 B 只认 workflow
- Modify: `orchestrator/.../agent/IntentRouter.java` — 分轨 user/system 提示；JSON **禁止** `executionMode`/`planMode`
- Modify: Catalog `intent.classifier`（若硬编码禁止：只改 Catalog SQL 种子 + 文档）— 轨 A/B 两套或同模板加 mode 条件块
- Test: `TrackRoutingTest.java`

**Interfaces:**
- 轨 A（FAST|PRO）：累积 skill/agent；**忽略 `#workflow`**（warn log）
- 轨 B（WORKFLOW）：只累积 `workflowId`；忽略 `$`/`@`
- L3 轨 A 输出仅 `agentIds`/`skillIds`/`confidence`/`reason`
- L3 轨 B 输出仅 `workflowId`/`confidence`/`reason`

- [ ] **Step 1: 单测** — 同 query、不同 mode → 规则命中域不同；L3 mock 含 `planMode` 时被丢弃且 mode 不变

- [ ] **Step 2: 实现过滤 + IntentRouter 分轨**

- [ ] **Step 3: L2（若现网为统一召回）** — 最小：召回后按轨过滤 resourceType；**不**强制本 plan 新建三套 embedding 索引（可记 follow-up）

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(orchestrator): dual-track intent gather for fast/pro vs workflow

EOF
)"
```

---

### Task 6: 会话持久化与 API 兼容

**Files:**
- Modify: `ConversationService` / DB 读写 `execution_preference` 列 — 写出 `fast|pro|workflow`；读入走 `from()` 映射
- Modify: BFF/前端 `conversations.ts` 已随 Task 2
- Test: 现有 `ConversationIntegrationTest` 或轻量单测

- [x] **Step 1: 确认写出新 wire；旧行可读**

- [x] **Step 2: Commit**

```bash
git commit -m "$(cat <<'EOF'
chore(orchestrator): persist executionMode as fast/pro/workflow

EOF
)"
```

---

### Task 7: 三模式冒烟脚本（routing V1/V3/V4/V5）

**Files:**
- Create: `scripts/verify_routing_v6_smoke.py`
- 可参考: `scripts/verify_planner_harness_kernel_smoke.py`、`sunshine_lib.py`

**Steps:**
- [x] 登录 → 建会话  
- [x] V1: `executionMode=fast` 简单问 → 日志/时间线走 ReAct（无 plan harness notebook 键或无 `PlannerHarnessLoop`）  
- [x] V3: `executionMode=pro` → Redis `sunshine:plan:notebook:*` 或 harness 日志  
- [x] V4: `executionMode=workflow` + `#` 已知模板 → 静态 Workflow  
- [x] V5: `executionMode=workflow` 无候选 → **失败/引导**，断言未变成 ReAct 成功答  

- [x] **Commit**

```bash
git commit -m "$(cat <<'EOF'
test(scripts): routing v6 three-mode smoke

EOF
)"
```


---

### Task 8: 文档状态同步

**Files:**
- Modify: `docs/superpowers/specs/2026-07-29-unified-routing-design.md` — 状态 → R-0～R-3 ✅ / R-4 ⬜；§13 勾选说明
- Modify: `docs/superpowers/specs/2026-08-05-planner-executor-rebuild-design.md` §7.0 — H-5 ✅（本 plan）
- Modify: `docs/superpowers/specs/README.md` 落地顺序
- Modify: `CLAUDE.md` / `docs/implementation-plan.md` 进度行

- [x] **Step 1: 更新状态文案（勿宣称阶段 D / H-6 / H-7 完成）**

- [x] **Step 2: Commit**

```bash
git commit -m "$(cat <<'EOF'
docs: mark routing v6 + H-5 plan complete

EOF
)"
```

---

## Out of scope（后续 plan）

| 项 | 去向 |
|----|------|
| 删除 `PlanWorkflowExecutor` / Approval / Catalog `plan-workflow.*` | 阶段 D / routing **R-4** |
| 删除 `ForcedExecutionRouter` 类文件（若已内联） | R-4 |
| 前端分层时间线 + TaskBoard + 去 Approval UI | rebuild **H-6** |
| §9.2 P1–P8 全量 Live | rebuild **H-7** |
| L2 独立 Agent/Skill/Workflow embedding 索引重建 | routing 增强 plan |
| `callSite` 全量注入 + llm_usage | phase5 5.2/5.3 |
| skill 可发现≠触发深改 | skill-sticky |

---

## Spec coverage（self-review）

| Spec 要求 | Task |
|-----------|------|
| 三模式显式 + 映射旧值 | T1 T2 T6 |
| ResourceDispatcher 三分支、无 PlanWorkflow 主路径 | T3 |
| 禁止 L3 改 mode / 无 auto 自判 | T4 T5 |
| 轨 A/B 收集 | T5 |
| `pro`→PlannerHarnessExecutor（H-5） | T3 |
| workflow 未命中不降级 | T4 T7 |
| 前端选择器 + `#` 仅 workflow | T2 |
| V1/V3/V4/V5 冒烟 | T7 |
| 阶段 D / H-6 / H-7 | **明确不做** |

---

## 风险与默认假设

1. **DB 列名**仍叫 `execution_preference` 可保留，只改取值；不强制 DDL 改列名。  
2. **`harness.enabled`** 变为 `pro` 的总闸：关=专业模式不可用（显式错），不再用于切回旧 DAG。  
3. **Golden set** `RoutingGoldenSetTest` 须随枚举改写期望 mode；允许本 plan 内修复至绿。  
4. L2 索引未拆前，用 **召回后过滤** 满足轨语义；完整索引重建另开 plan。
