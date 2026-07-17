# 4.7.6 ReAct Spawn Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 主 ReAct 通过元工具 `spawn_subagent` 按需创建上下文隔离子 Agent（主动态 `prompt`、同工具集、一层、可并行）；主时间线一张卡 + 一行摘要，详情进抽屉（含传入 prompt / subSteps / HITL）。

**Architecture:** 对齐 `manage_tasks`：orchestrator 内置元工具（不进 Catalog）；Hook 跳过常规 tool 步；`AgentRuntime.run(SUB)` + `MemoryContext.forSubAgent()` + 透传 `conversationId`；用 `SpawnSubagentTimelineBridge`（仿 `SubAgentTimelineBridge`）把子 token 折叠进主卡 `subagent-{runId}.subSteps`。前端 `SubagentCard` + `SubagentDrawer`（Cursor 式），不在主栈内联展开。

**Tech Stack:** AgentScope-Java Hook/Toolkit · Spring · Vue3/Naive UI · Nacos `docs/nacos/sunshine-orchestrator.yaml` · Live `scripts/verify_spawn_subagent_live.py`

**Spec:** [2026-07-18-react-spawn-subagent-design.md](../specs/2026-07-18-react-spawn-subagent-design.md)

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../agent/SpawnSubagentTool.java` | 元工具：解析 prompt/label → 跑 SUB → 回传终态文本 |
| `orchestrator/.../agent/SpawnSubagentTimelineSupport.java` | 主卡创建/摘要/完成；折叠 subSteps 到 MAIN session |
| `orchestrator/.../agent/SpawnSubagentLabels.java` | Nacos `agent.timeline.subagent` 一行摘要 |
| `orchestrator/.../config/AgentExecutionProperties.java` | `react.subagent.enabled` / `max-iters` / `timeout-ms` |
| `orchestrator/.../agent/DynamicToolkitFactory.java` | MAIN 注册 `spawn_subagent`；SUB 永不注册 |
| `orchestrator/.../agent/ProcessingStepHook.java` | Pre/PostActing 跳过 `spawn_subagent`（同 manage_tasks） |
| `orchestrator/.../processing/StepMetadata.java` + Assembler/Serde | `spawnPrompt` 字段 |
| `docs/nacos/sunshine-orchestrator.yaml` | timeline + execution + react overlay |
| `sunshine-ui/.../SubagentCard.vue` | 主时间线卡片（状态 + 一行摘要） |
| `sunshine-ui/.../SubagentDrawer.vue` + composable | 抽屉：prompt / subSteps / HITL / result |
| `sunshine-ui/.../OperationStack.vue` | `phase===subagent` → Card；点开抽屉 |
| `scripts/verify_spawn_subagent_live.py` | S1–S6 Live |

---

### Task 1: Nacos 配置 + ExecutionProperties

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/config/AgentExecutionPropertiesSubagentTest.java`（新建，若项目已有类似 properties 测则并入）

- [ ] **Step 1: 在 `AgentExecutionProperties.React` 增加 Subagent**

```java
@Data
public static class Subagent {
    private boolean enabled = true;
    private int maxIters = 8;
    private long timeoutMs = 180_000L;
}
// React 内:
private Subagent subagent = new Subagent();
```

- [ ] **Step 2: Nacos `agent.execution.react` 追加**

```yaml
      subagent:
        enabled: true
        max-iters: 8
        timeout-ms: 180000
```

在 `agent.timeline.steps` 下追加（与 `tasks` 同级）：

```yaml
      subagent:
        label: 子任务
        before: 准备委派子任务
        active: "正在执行：{label}"
        after: 子任务已完成
        after-fail: 子任务失败
```

在 `agent.prompt.mode-overlays.react` 追加（禁止硬编码到 Java）：

```text
        - 【SpawnSubagent】需要隔离上下文的重活（长检索/多工具探索）时调用 `spawn_subagent`；`prompt` 写清完整任务与约束；可选 `label` 短标题。
        - 【SpawnSubagent】子跑结果仅终态文本回主；勿把子过程细节复述进主 reasoning。
        - 【SpawnSubagent】与 `manage_tasks` 分工：清单用 TaskBoard；真正隔离子跑只用 `spawn_subagent`。
```

- [ ] **Step 3: sync Nacos（实施机）**

```bash
python scripts/sync_nacos.py
```

Expected: sync OK（重启 orchestrator 留到联调 Task）

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java \
  docs/nacos/sunshine-orchestrator.yaml
git commit -m "$(cat <<'EOF'
feat(4.7.6): add subagent Nacos flags and timeline keys

EOF
)"
```

---

### Task 2: StepMetadata.spawnPrompt + Serde

**Files:**
- Modify: `orchestrator/.../processing/StepMetadata.java`
- Modify: `orchestrator/.../processing/StepMetadataAssembler.java`（所有构造/copy 补参）
- Modify: `orchestrator/.../agent/ProcessingStepSerde.java`（若单独序列化 metadata）
- Modify: `sunshine-ui/src/api/processingSteps.ts` — `StepMetadata.spawnPrompt?: string`
- Test: 既有 `StepMetadataAssembler` / Serde 单测补一条

- [ ] **Step 1: 写失败单测**（Assembler `withSpawnPrompt`）

```java
@Test
void withSpawnPrompt_setsField() {
    StepMetadata m = StepMetadataAssembler.withSpawnPrompt(null, "检索差旅制度并摘要");
    assertThat(m.spawnPrompt()).isEqualTo("检索差旅制度并摘要");
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
cd /usr/local/gitproj/my-sunshine-agent
mvn -pl orchestrator -Dtest=StepMetadataAssemblerTest#withSpawnPrompt_setsField test
```

Expected: FAIL（方法不存在）

- [ ] **Step 3: 实现** — `StepMetadata` 增加末尾字段 `String spawnPrompt`；Assembler 增加 `withSpawnPrompt(base, prompt)`；Serde 读写 `spawnPrompt`；前端 interface 同步。

- [ ] **Step 4: 单测通过后 Commit**

```bash
git commit -m "feat(4.7.6): StepMetadata.spawnPrompt for subagent drawer"
```

---

### Task 3: SpawnSubagentLabels + TimelineSupport

**Files:**
- Create: `orchestrator/.../agent/SpawnSubagentLabels.java`
- Create: `orchestrator/.../agent/SpawnSubagentTimelineSupport.java`
- Create: `orchestrator/.../agent/SpawnSubagentTimelineBridge.java`（仿 `SubAgentTimelineBridge`，父步 id=`subagent-{runId}`，phase=`subagent`）
- Test: `orchestrator/.../agent/SpawnSubagentTimelineSupportTest.java`

- [ ] **Step 1: 失败单测 — 创建主卡带 prompt**

```java
@Test
void begin_emitsSubagentCardWithSpawnPrompt() {
    // 绑定假 session / bridge；begin(runId, label, prompt)
    // assert step.id == "subagent-"+runId, phase=="subagent", metadata.spawnPrompt==prompt
}
```

- [ ] **Step 2: 实现 Labels** — 读 `AgentPromptProperties`（或现有 timeline steps 绑定方式，对齐 `TaskBoardStepLabels` / `ThinkStepLabelService`）。`active(label)` 替换 `{label}`。

- [ ] **Step 3: 实现 TimelineBridge**

```java
public final class SpawnSubagentTimelineBridge {
    // wrap(token) → upsert subSteps → 返回父 ProcessingStep 更新（status running，一行 summary active）
    public List<StreamToken> wrap(StreamToken token) { /* 同 SubAgentTimelineBridge */ }
    public void complete(String after, String result, boolean ok) { /* status done/error + result */ }
}
```

- [ ] **Step 4: TimelineSupport** — `begin` / `ingestViaBridge` / `complete` / `fail`；通过 `StepEventBridge.emit(mainBridge, session -> …)` 写主 Timeline。HITL awaiting：若子 tool 步带 hitl，父卡 `status`/`lifecycle` 反映 `awaiting_confirm`（复用现有 step 合并逻辑）。

- [ ] **Step 5: 单测绿 → Commit**

```bash
git commit -m "feat(4.7.6): subagent timeline card and fold bridge"
```

---

### Task 4: SpawnSubagentTool（核心）

**Files:**
- Create: `orchestrator/.../agent/SpawnSubagentTool.java`
- Test: `orchestrator/.../agent/SpawnSubagentToolTest.java`
- Modify: `DynamicToolkitFactory.java`
- Modify: `ProcessingStepHook.java`（跳过 spawn_subagent 的 tool 步）

- [ ] **Step 1: 失败单测 — prompt 空**

```java
@Test
void emptyPrompt_returnsErrorJson() {
    String out = tool.spawnSubagent("  ", null);
    assertThat(out).contains("\"ok\":false");
}
```

- [ ] **Step 2: 失败单测 — SUB 不可见工具名常量**

```java
assertThat(SpawnSubagentTool.NAME).isEqualTo("spawn_subagent");
```

- [ ] **Step 3: 实现工具骨架**

```java
@Tool(name = NAME, description = "创建隔离子 Agent：传入完整 prompt，返回子任务最终文本；用于避免主上下文膨胀。")
public String spawnSubagent(
        @ToolParam(name = "prompt", description = "给子 Agent 的完整任务说明（必填）") String prompt,
        @ToolParam(name = "label", description = "时间线卡片短标题（可选）") String label) {
    // 1) enabled 门禁
    // 2) messageId = StepEventBridge.activeMessageId(); audit = toolAuditContext(messageId)
    // 3) runId = UUID; timelineSupport.begin(... spawnPrompt=prompt)
    // 4) AgentRunRequest.sub(MemoryContext.forSubAgent(), prompt, List.of(), userId, tenantId,
    //        assistantMessageId, null, null /* 同 Catalog via factory SUB */, null, maxIters, conversationId)
    // 5) bindTokenWrapper(subBridge, tokens → timelineSupport.fold + flush to MAIN generation)
    // 6) bindHitlBridge(subBridge, assistantMessageId, true)
    // 7) agentRuntime.run(req).collect 终态文本（timeoutMs）；complete/fail 主卡
    // 8) return 终态文本原文（失败则 error 文本）；禁止截断摘要
}
```

要点：
- `toolWhitelist=null` + `DynamicToolkitFactory.buildForSubAgent` 行为：与 MAIN 同启用池但 **不含** spawn/manage_tasks（现有 SUB 已不含 manage_tasks）。
- 并行：每次调用独立 `runId`/bridge；AgentScope 并行 acting 时无共享可变状态。
- 嵌套硬拒：若当前 bridge 已是 SUB（检测 `bridgeId.startsWith("sub-")` 且无 MAIN 注册 spawn）——SUB toolkit 根本不注册即可；额外在工具入口若 `AgentRole` 可探测则拒。

- [ ] **Step 4: DynamicToolkitFactory** — MAIN 且 `subagent.enabled` 时 `tk.registerTool(spawnSubagentTool)`；白名单里若出现 `spawn_subagent` 则 warn 跳过（同 manage_tasks）。

- [ ] **Step 5: ProcessingStepHook** — `PreActing`/`PostActing` 对 `SpawnSubagentTool.NAME` 直接 `return Mono.just(event)`（不 beginToolStep）。

- [ ] **Step 6: 单测（Mock AgentRuntime 返回固定 Flux content）绿 → Commit**

```bash
git commit -m "feat(4.7.6): SpawnSubagentTool meta-tool and MAIN-only register"
```

---

### Task 5: 前端 SubagentCard + Drawer

**Files:**
- Create: `sunshine-ui/src/components/operation/SubagentCard.vue`
- Create: `sunshine-ui/src/components/operation/SubagentDrawer.vue`
- Create: `sunshine-ui/src/composables/useSubagentDrawer.ts`（open/close/state，对齐 `usePlanNodeDrawer` 精简版）
- Modify: `sunshine-ui/src/components/operation/OperationStack.vue`
- Modify: `sunshine-ui/src/api/processingSteps.ts`（`phase === 'subagent'` 辅助函数）

- [ ] **Step 1: SubagentCard** — 展示 `label` + `resolveStepHeaderText` 一行摘要 + 状态色；点击 `$emit('open')` 或 composable.open(step)。**禁止**内联展开 subSteps。

样式：`--sun-black` 底 + `1px var(--sun-border)`；对齐 TaskBoard/Peer 卡片，无灰底。

- [ ] **Step 2: SubagentDrawer** — 区块顺序：
  1. 传入提示词（`step.metadata.spawnPrompt`，`StaticMarkdown` 或 pre-wrap 文本）
  2. `OperationStack` 嵌套 `step.subSteps`（HITL 开）
  3. 最终输出（`step.result`）

- [ ] **Step 3: OperationStack**

```ts
if (s.phase === 'subagent') return false // 不进默认 OperationCard 列表
// template:
<SubagentCard v-else-if="step.phase === 'subagent'" :step="step" :live="live" @open="openSubagent(step)" />
```

抽屉挂在 ChatView 或 Stack 旁（与 Plan 抽屉同级 portal 亦可）。

- [ ] **Step 4: 手动冒烟** — `npm run dev`，用强制 react 发一句会触发委派的提示（或等 Live）。

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(4.7.6): SubagentCard and drawer UI"
```

---

### Task 6: Live 脚本 + 文档收尾

**Files:**
- Create: `scripts/verify_spawn_subagent_live.py`
- Modify: `CLAUDE.md`（脚本表 + 时间线表一行）
- Modify: `docs/superpowers/specs/2026-07-18-react-spawn-subagent-design.md` 状态 → 实施中/完成
- Modify: `docs/implementation-plan.md` / `phase4` 4.7.6 状态

- [ ] **Step 1: Live 脚本**（仿 `verify_react_taskboard_live.py`）

```python
# S1: executionPreference=react，query 诱导 spawn（overlay + 明确「请用子任务隔离检索…」）
# assert 存在 phase==subagent 或 id.startswith("subagent-")
# assert 主 steps 中无子 think 抬升为顶层 think（子 think 仅在 subSteps）
# S4: 诱导两个 spawn（prompt 写「并行两个子任务」）→ >=2 张卡
# S5: 无法在 live 轻易测 SUB 再委派；单测覆盖即可，Live 标 skip 或查日志
# S6: 可选 sandbox write 路径在 workspace
```

```bash
python scripts/verify_spawn_subagent_live.py
```

Expected: S1/S2/S4 相关断言 PASS（S3 HITL 若环境无写工具可 WARN）

- [ ] **Step 2: 重启 orchestrator 后跑 Live**

```bash
python scripts/start.py --restart orchestrator
python scripts/verify_spawn_subagent_live.py
```

- [ ] **Step 3: 更新 CLAUDE.md / phase4 / implementation-plan 状态 ✅**

- [ ] **Step 4: Commit**

```bash
git commit -m "test(4.7.6): spawn_subagent live verify and docs gate"
```

---

## Spec coverage checklist

| Spec 项 | Task |
|---------|------|
| 元工具 `spawn_subagent` / 不进 Catalog | T4 |
| 仅 MAIN；SUB 无工具 | T4 |
| 同工具集；memory empty | T4 |
| 并行多卡 | T4 + T6 |
| 一层硬拒 | T4 toolkit + 单测 |
| 主卡一行摘要 + 抽屉含 prompt | T3 + T5 |
| HITL 抽屉内 | T5（复用 HitlStepActions） |
| 沙箱 conversationId | T4 ToolAuditContext |
| Nacos 文案/overlay | T1 |
| 不对产出截断 | T4 回传原文 |
| 4.7.1 废弃 / 4.7.4 不做 | 已在 spec/phase4；本 plan 不实现 |
| Live S1–S6 | T6 |

## Self-review

- 无 TBD 占位；类型名 `spawn_subagent` / `phase=subagent` / `subagent-{runId}` / `spawnPrompt` 全文一致。
- `SubAgentTimelineBridge` 与 `SpawnSubagentTimelineBridge` 职责分离：前者 Workflow `node-*`，后者 ReAct 主卡。
