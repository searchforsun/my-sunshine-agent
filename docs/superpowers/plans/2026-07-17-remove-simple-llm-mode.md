# 移除 simple-llm 执行模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底删除 Chat「简单对话 / `simple-llm`」执行模式与强制 preference；意图闲聊类统一 ReAct；内部直连 Gateway 的 `PromptMode.SIMPLE_LLM` 重命名为 `DIRECT`；上线清会话缓存，不做兼容。

**Architecture:** 删除 `ExecutionMode`/`ExecutionPreference`/`SimpleLlmExecutor` 路径；`ExecutionMode.from` 未知串 default→REACT；Nacos 意图词表去掉 simple-llm；`PromptMode.DIRECT("direct")` + `mode-overlays.direct` 替代原 prompt 键；前端 options 删一项。

**Tech Stack:** orchestrator (Java/Spring) · sunshine-ui (Vue3/TS) · Nacos YAML · `scripts/verify_execution_preference.py` · `scripts/clear_session_cache.py`

**Spec:** [2026-07-17-remove-simple-llm-mode-design.md](../specs/2026-07-17-remove-simple-llm-mode-design.md)

---

## File map

| 区域 | 路径 | 动作 |
|------|------|------|
| 枚举 | `orchestrator/.../routing/ExecutionMode.java` | 删 `SIMPLE_LLM`；`from` 不再识别 simple/simple-llm/direct |
| 枚举 | `orchestrator/.../routing/ExecutionPreference.java` | 删 `SIMPLE_LLM` |
| Plan | `orchestrator/.../routing/ExecutionPlan.java` | 删 `intentLabel` 的 SIMPLE_LLM 分支 |
| 强制路由 | `orchestrator/.../routing/ForcedExecutionRouter.java` | 删 SIMPLE_LLM case |
| 解析 | `orchestrator/.../routing/ExecutionPlanParser.java` | 删 stored `simple-llm` 分支（落入 unknown→reactFallback） |
| 规则 | `orchestrator/.../routing/RuleBasedRouter.java` | 删 simple-llm 映射 |
| 分发 | `orchestrator/.../execution/ExecutionDispatcher.java` | 删依赖与 case |
| 删除 | `orchestrator/.../execution/SimpleLlmExecutor.java` | **Delete file** |
| 续跑 | `orchestrator/.../controller/stream/ChatStreamExecutor.java` | 删 SIMPLE_LLM 续写分支与 `simpleLlmExecutor` 注入 |
| Prompt | `PromptMode.java` / `PromptComposeRequest.java` / `LlmGatewayClient.java` | SIMPLE_LLM→DIRECT；`forSimpleLlm*`→`forDirect*`；`forExpertSpeak` 用 DIRECT |
| Timeline | `IntentLabelService.java` / `TimelineLabelTemplates.java` / `AgentPromptProperties.java` | 删 SIMPLE_LLM / simple-llm 默认文案 |
| Nacos | `docs/nacos/sunshine-orchestrator.yaml` | intent 词表；timeline modes；`mode-overlays.direct` |
| 前端 | `executionModes.ts` / `executionModeIcons.ts` / `resumeMode.ts` / `contentInterleave.ts` | 去 simple-llm |
| 脚本/文档 | `verify_execution_preference.py`、golden-set、CLAUDE、README、旧 selector spec 注记 | 同步 |
| 测试 | 见各 Task | 改/删 SIMPLE_LLM 用例 |

---

### Task 1: 路由枚举 + Parser + 单测（先红后绿）

**Files:**
- Modify: `ExecutionMode.java`, `ExecutionPreference.java`, `ExecutionPlan.java`
- Modify: `ExecutionPlanParser.java`, `RuleBasedRouter.java`
- Modify: `ForcedExecutionRouter.java`
- Modify: `ForcedExecutionRouterTest.java`, `ExecutionPlanParserTest.java`, `RoutingGoldenSetTest.java`

- [ ] **Step 1:** 改测试 — 删除 `ForcedExecutionRouterTest.resolve_simpleLlm`；删除 `RoutingGoldenSetTest.forcedJ1_simpleLlm`
- [ ] **Step 2:** `ExecutionPlanParserTest`：将 `normalizesSimpleLlmAlias` / `parseStoredIntentSimpleLlm` 改为断言 `simple-llm` → **REACT**（`ExecutionMode.from` default 或 `reactFallback`），例如：

```java
@Test
void unknownMode_simpleLlmFallsToReact() {
    ExecutionPlan plan = parser.parse("{\"mode\":\"simple-llm\"}");
    assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
}

@Test
void parseStoredIntent_unknownSimpleLlmFallsToReact() {
    ExecutionPlan plan = parser.parseStoredIntent("simple-llm");
    assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
}
```

- [ ] **Step 3:** 实现枚举删除：

```java
// ExecutionMode — 仅 WORKFLOW, REACT, PLAN_WORKFLOW, PEER_COLLAB
public static ExecutionMode from(String raw) {
    if (raw == null || raw.isBlank()) return REACT;
    return switch (raw.toLowerCase().replace('_', '-')) {
        case "workflow", "pipeline" -> WORKFLOW;
        case "plan-workflow", "plan_workflow", "plan" -> PLAN_WORKFLOW;
        case "peer-collab", "peer_collab", "peer" -> PEER_COLLAB;
        default -> REACT; // 含历史 simple-llm / simple / direct
    };
}
```

```java
// ExecutionPreference — 无 SIMPLE_LLM；from 无 simple 分支；default → AUTO
```

- [ ] **Step 4:** `ForcedExecutionRouter` 删 `REASON_SIMPLE` 与 `case SIMPLE_LLM`；`ExecutionPlan.intentLabel` 删 SIMPLE_LLM；`ExecutionPlanParser` 删 `if ("simple-llm"...)`；`RuleBasedRouter` 删 simple 映射（若规则 YAML 仍写 simple-llm，落入 default 处理或改为 react——以代码 switch 为准）
- [ ] **Step 5:** 跑测：

```bash
./mvnw -pl orchestrator -am test -Dtest=ForcedExecutionRouterTest,ExecutionPlanParserTest,RoutingGoldenSetTest
```

Expected: PASS  
- [ ] **Step 6:** Commit `refactor(orchestrator): remove ExecutionMode.SIMPLE_LLM from routing`

---

### Task 2: 删除 SimpleLlmExecutor + Dispatcher + ChatStream 续写

**Files:**
- Delete: `SimpleLlmExecutor.java`
- Modify: `ExecutionDispatcher.java`, `ExecutionDispatcherTest.java`
- Modify: `ChatStreamExecutor.java`（去掉 `simpleLlmExecutor` 字段与 `plan.mode() == SIMPLE_LLM && existingContent` 分支，续跑统一 `executionDispatcher.execute`）
- Modify: 其它引用 `SIMPLE_LLM` 的测试改为 `REACT`：`GenerationJobTest`, `ThinkStepMapperTest`, `StepMetadataTest`, `GenerationReconnectIntegrationTest`, `ConversationIntegrationTest`（`updateMessageIntent(..., "simple-llm")` → `"react"`）

- [ ] **Step 1:** `ExecutionDispatcher` 变为：

```java
return switch (mode) {
    case WORKFLOW -> workflowExecutor.execute(ctx);
    case REACT -> reactExecutor.execute(ctx);
    case PLAN_WORKFLOW -> planWorkflowExecutor.execute(ctx);
    case PEER_COLLAB -> expertConsultationExecutor.execute(ctx);
};
```

- [ ] **Step 2:** 删除 `SimpleLlmExecutor.java`；修 `ChatStreamExecutor` 续跑
- [ ] **Step 3:** 批量替换测试中的 `ExecutionMode.SIMPLE_LLM` → `REACT`（或删仅验证 simple 文案的断言）
- [ ] **Step 4:**

```bash
./mvnw -pl orchestrator -am test -Dtest=ExecutionDispatcherTest,GenerationJobTest,ThinkStepMapperTest,StepMetadataTest
```

Expected: PASS  
- [ ] **Step 5:** Commit `refactor(orchestrator): delete SimpleLlmExecutor`

---

### Task 3: PromptMode.DIRECT 重命名

**Files:**
- Modify: `PromptMode.java` → `DIRECT("direct")`
- Modify: `PromptComposeRequest.java` → `forDirect` / `forDirectContinue`；`forExpertSpeak` 使用 `PromptMode.DIRECT`
- Modify: `LlmGatewayClient.java` 调用新工厂方法
- Modify: `PromptComposerTest.java`、`PromptOverlayProperties` 注释
- Modify: `docs/nacos/sunshine-orchestrator.yaml`：`mode-overlays.simple-llm` → `mode-overlays.direct`
- 注释：`PromptComposer` / `StreamDeltaNormalizer` / `StreamToken` / `DynamicToolkitFactory` 中 simple-llm 措辞改为「直连 Gateway / DIRECT」

- [ ] **Step 1:** 改 `PromptComposerTest` 使用 `forDirect` / overlay key `direct`
- [ ] **Step 2:** 实现重命名（IDE rename 或手工）
- [ ] **Step 3:**

```bash
./mvnw -pl orchestrator -am test -Dtest=PromptComposerTest
```

Expected: PASS  
- [ ] **Step 4:** Commit `refactor(orchestrator): rename PromptMode.SIMPLE_LLM to DIRECT`

---

### Task 4: Intent / Timeline 文案 + Nacos 意图词表

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（`classifier-prompt`、`timeline.intent.modes`、`timeline.steps.think.modes`）
- Modify: `AgentPromptProperties.java` 默认 map（删 simple-llm 默认 Intent/Think）
- Modify: `IntentLabelService.java`：`plan == null` 与 switch 默认改用 **REACT** 文案（勿再引用 SIMPLE_LLM）；删 SIMPLE_LLM case 与 forced 模板
- Modify: `TimelineLabelTemplates.java` 删 SIMPLE_LLM → `simple-llm` 映射
- Modify: `IntentRouter.java` / `ChatMessage.java` / `bff/.../ChatRequest.java` 注释中的合法 mode 列表

**Nacos intent 片段（替换 mode 行与规则）：**

```yaml
{"mode":"workflow|react|plan-workflow|peer-collab",...}

规则：
- react：审批/提交/确认/继续；多工具；沙箱；通识闲聊/百科/写作润色/纯概念讲解（原 simple-llm）；或不确定
- workflow：...
- plan-workflow：...
- peer-collab：...
- 拿不准时用 react
```

- [ ] **Step 1:** 改 YAML + Java 默认与 `IntentLabelService`
- [ ] **Step 2:** `python scripts/sync_nacos.py`（实现机可访问 Nacos 时）
- [ ] **Step 3:** 相关单测若有 Intent 文案断言则更新
- [ ] **Step 4:** Commit `chore(nacos): drop simple-llm from intent and timeline`

---

### Task 5: 前端

**Files:**
- Modify: `sunshine-ui/src/api/executionModes.ts` — 删类型联合与 options 项与 `isExecutionPreference` 中的 `simple-llm`
- Modify: `sunshine-ui/src/api/executionModeIcons.ts` — 删 `'simple-llm'` 项
- Modify: `sunshine-ui/src/api/resumeMode.ts` — `isReactAssistantMessage`：删 `intent === 'simple-llm'`（及可选旧 `'simple'`）；`simple-llm` 历史 intent 清库后不出现
- Modify: `sunshine-ui/src/api/contentInterleave.ts` — 注释改为「generate 步骤隐藏（ReAct）」；逻辑 `isHiddenReactTimelineStep` 可保留
- Modify: `sunshine-ui/e2e/processing-timeline.spec.ts` — 勿断言「简单对话」；改为自动路由下出现 ReAct 时间线特征（如「自主智能体」或 think/tool）
- Modify: `sunshine-ui/mock-server.mjs` — 删简单对话分支

- [ ] **Step 1:** 改 `executionModes.ts` 使 options 剩 5 项（auto+4）
- [ ] **Step 2:** 其余文件同步
- [ ] **Step 3:** `cd sunshine-ui && npx vue-tsc --noEmit`（或项目惯用检查）
- [ ] **Step 4:** Commit `feat(ui): remove 简单对话 from execution mode selector`

---

### Task 6: Live 脚本 + 文档 + 清库验收

**Files:**
- Modify: `scripts/verify_execution_preference.py` — 删除 J1 行；J2–J6 保留（可重编号或保持 J2 起）
- Modify: `docs/routing/routing-golden-set.md` §J — 合法值去掉 `simple-llm`；删 J1 行
- Modify: `CLAUDE.md`、`README.md` — 执行模式列表去掉 simple-llm
- Modify: `docs/superpowers/specs/2026-06-25-chat-execution-mode-selector-design.md` — §1 表「简单对话」行加 **废止** 注记并链到新 spec
- Modify: design spec 状态 → 实现中/已完成（收尾时）

- [ ] **Step 1:** 改脚本与文档
- [ ] **Step 2:** 编译重启 orchestrator（若尚未）：按 README 惯用命令
- [ ] **Step 3:** 清库：

```bash
python scripts/clear_session_cache.py --force --restart-orchestrator
```

并执行脚本打印的浏览器 localStorage 清理（含 `sunshine-execution-preference`）

- [ ] **Step 4:** Live：

```bash
python scripts/verify_execution_preference.py
```

Expected: `[PASS] executionPreference §J`（无 J1）

- [ ] **Step 5:** 手工：Chat 底栏无「简单对话」；自动发「写一段快速排序」走 ReAct（有 think/tool 或自主智能体 intent，而非「简单对话」）
- [ ] **Step 6:** Commit `docs: update routing golden-set and CLAUDE after removing simple-llm`；spec 标 ✅

---

## Spec coverage

| Spec 要求 | Task |
|-----------|------|
| 删 ExecutionMode/Preference SIMPLE_LLM | 1 |
| 删 Forced / Parser / Rule 分支 | 1 |
| 删 SimpleLlmExecutor + Dispatcher | 2 |
| ChatStream 续写分支 | 2 |
| PromptMode → DIRECT + Nacos overlay | 3 |
| 意图词表 + timeline 删 simple-llm | 4 |
| 前端 options / e2e / mock | 5 |
| 清库、verify 脚本、文档 | 6 |
| 不做兼容映射 | 1（default only）+ 6（清库） |
| 闲聊 → ReAct | 4（prompt）+ 1（from default） |

## Self-review

- 无 TBD；`direct` overlay 与 `ExecutionMode.from` 对 `direct` 走 REACT 已在 Task 1/3 分离
- `forExpertSpeak` 纳入 Task 3
- Audit 测试中的 `user:forced-simple-llm` 字符串：若仅测解析 metadata 可保留字符串，或改为 `user:forced-react`（Task 2 扫 `ForcedExecutionRouter` 相关测试时处理）
