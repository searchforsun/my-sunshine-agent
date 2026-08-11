# 4.7.9-r1 Request Decision Cursor Align Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已落地的 `request_decision` 端到端对齐 Cursor ACP `ask_question`：`title?` + `questions[]`（多题/多选/`id`+`label`）、平台每题必有「其他」手写、resolve/tool result 改为 `answers` / `outcome=`。

**Architecture:** 在现有 DecisionRegistry + Timeline + DecisionCard 骨架上**替换契约**（非另起炉灶）。一次 tool call = 一个 token = 一张卡（卡内多题）；`DecisionOption` 仅 `id`/`label`；手写 `__custom__` 由 UI/校验注入，不进模型 options。暂停/续跑 fingerprint 改为 `title+questions`。

**Tech Stack:** AgentScope-Java · Spring WebFlux · Redis · Vue3/Naive UI · Prompt Catalog seed · `scripts/verify_decision_live.py`

**Spec:** [2026-08-11-request-decision-cursor-align-design.md](../specs/archive/2026-08-11-request-decision-cursor-align-design.md)

## Global Constraints

- Chat ReAct MAIN only；Planner / SUB / Workflow **不动**。
- `enabled` 默认 **false**（D21）；本计划不改默认值。
- 选项 **仅** `{id, label}`；label 即答案；**禁止** `description` / `requireInput` / 顶层 `allow_custom_input`。
- 同 `messageId` 同时最多 **1 份问卷**（C3）。
- 多题 UI = **同卡一页全展示**（C4）。
- tool result / resolve 固定短格式与 `answers[]`；**禁止**对模型 question/options 截断兜底。
- 不新增 SSE type；不改 `WorkflowNodeRunner`。
- 改后端 → `python scripts/start.py --restart orchestrator`（BFF 同理）；Prompt 改 DB/seed 后重启 resource-manager + 按需发版 overlay。

---

## File map

| 文件 | 职责 |
|------|------|
| `DecisionOption.java` | `{id, label}` |
| `DecisionQuestion.java` / `DecisionAnswer.java` | **新建** |
| `DecisionResult.java` / `DecisionStepMeta.java` | outcome + title + questions + answers |
| `DecisionFingerprint.java` | `of(title, questions)` |
| `DecisionPendingWaiter.java` / `DecisionRegistry.java` | 存 questions；resolve(answers) |
| `ResolveDecisionRequest.java` | `{ answers: [...] }` |
| `OrchestratorErrorCode.java` | 新增 `DECISION_INVALID_ANSWERS` |
| `RequestDecisionTool.java` | 新入参 + 新 result |
| `DecisionTimelineSupport.java` / `DecisionResumeSupport.java` / Labels | 新 meta |
| `ProcessingStepSerde.java` | 序列化 questions/answers |
| `GenerationController.java` | 调新 resolve |
| `sunshine-ui` `processingSteps*.ts` / `decisions.ts` / `DecisionCard.vue` | 多题多选+始终其他 |
| `19-sunshine-resource.sql` + 运行时 overlay | 示例改 Cursor 形 |
| `scripts/verify_decision_live.py` | R1–R4 等 |

---

### Task 1: DTO 形状（Option / Question / Answer / Result / StepMeta）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionOption.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionQuestion.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionAnswer.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionResult.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/DecisionStepMeta.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionPendingWaiter.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/DecisionDtoShapeTest.java`

**Interfaces:**
- Produces:
  - `DecisionOption(String id, String label)`
  - `DecisionQuestion(String id, String prompt, List<DecisionOption> options, boolean allowMultiple)`
  - `DecisionAnswer(String questionId, List<String> selectedOptionIds, String customInput)`
  - `DecisionResult(String outcome, String title, List<DecisionAnswer> answers, long decidedAt)`
  - `DecisionStepMeta(String token, String title, List<DecisionQuestion> questions, Long expiresAt, String outcome, List<DecisionAnswer> answers)`
  - `DecisionPendingWaiter(..., String title, List<DecisionQuestion> questions, ...)` — 去掉 `question`/`options`/`allowCustomInput`
- Constant: `public static final String CUSTOM_OPTION_ID = "__custom__"` 放在 `DecisionOption` 或新建 `DecisionConstants`

- [ ] **Step 1: 写形状单测**

```java
package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.DecisionStepMeta;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionDtoShapeTest {
    @Test
    void option_is_id_and_label_only() {
        DecisionOption o = new DecisionOption("agent", "Agent");
        assertThat(o.id()).isEqualTo("agent");
        assertThat(o.label()).isEqualTo("Agent");
    }

    @Test
    void result_carries_answers_and_outcome() {
        DecisionAnswer a = new DecisionAnswer("q1", List.of("agent"), null);
        DecisionResult r = new DecisionResult("answered", "Need input", List.of(a), 1L);
        assertThat(r.outcome()).isEqualTo("answered");
        assertThat(r.answers()).hasSize(1);
    }

    @Test
    void step_meta_has_questions_not_flat_options() {
        DecisionQuestion q = new DecisionQuestion(
                "q1", "Mode?", List.of(new DecisionOption("a", "A"), new DecisionOption("b", "B")), false);
        DecisionStepMeta m = new DecisionStepMeta("tok", "Title", List.of(q), 9L, null, null);
        assertThat(m.questions()).hasSize(1);
        assertThat(m.title()).isEqualTo("Title");
    }
}
```

- [ ] **Step 2: Run — expect compile fail**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn -pl orchestrator -Dtest=DecisionDtoShapeTest test
```

- [ ] **Step 3: 改 record 定义**（按 Interfaces；全仓暂时编译红是预期，后续 Task 接上）

```java
// DecisionOption.java
public record DecisionOption(String id, String label) {
    public static final String CUSTOM_ID = "__custom__";
}

// DecisionQuestion.java / DecisionAnswer.java — 按 Interfaces

// DecisionResult.java
public record DecisionResult(
        String outcome, String title, List<DecisionAnswer> answers, long decidedAt) {}

// DecisionStepMeta.java — 按 Interfaces

// DecisionPendingWaiter — 字段改为 title + List<DecisionQuestion> questions
```

- [ ] **Step 4: 再跑 DecisionDtoShapeTest — PASS**（允许其它测试类编译失败，本 Task 只保证本测与 DTO 文件）

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionOption.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionQuestion.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionAnswer.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionResult.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionPendingWaiter.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/processing/DecisionStepMeta.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/DecisionDtoShapeTest.java
git commit -m "$(cat <<'EOF'
refactor(decision): Cursor-shaped DTOs (id/label, questions, answers)

EOF
)"
```

---

### Task 2: Fingerprint + Serde

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionFingerprint.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepSerde.java`（decision 读写段）
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/ProcessingStepSerdeDecisionTest.java`
- Create/Modify: fingerprint 单测（若已有则改）

**Interfaces:**
- Produces: `DecisionFingerprint.of(String title, List<DecisionQuestion> questions)`
- Serde JSON keys: `token`, `title`, `questions[{id,prompt,options[{id,label}],allowMultiple}]`, `expiresAt`, `outcome`, `answers[{questionId,selectedOptionIds,customInput}]`
- **禁止**再写 `value`/`description`/`requireInput`/`allowCustomInput`/`question`/`choice`

- [ ] **Step 1: 写失败/更新 Serde 单测**

```java
@Test
void decision_serde_round_trips_questions() {
    DecisionQuestion q = new DecisionQuestion(
            "q1", "Mode?",
            List.of(new DecisionOption("agent", "Agent"), new DecisionOption("plan", "Plan")),
            true);
    DecisionStepMeta meta = new DecisionStepMeta("t1", "Need", List.of(q), 100L, null, null);
    // 经 ProcessingStepSerde 写入 map 再读回
    // assert questions[0].allowMultiple == true; options[0].id == "agent"
    // assert map 不含 "value" / "allowCustomInput"
}
```

- [ ] **Step 2: Run — FAIL/编译错**

```bash
mvn -pl orchestrator -Dtest=ProcessingStepSerdeDecisionTest,DecisionFingerprintTest test
```

- [ ] **Step 3: 实现 fingerprint**

```java
public static String of(String title, List<DecisionQuestion> questions) {
    // canonical JSON: title + questions 数组（id, prompt, allowMultiple, options[{id,label}]）
    // SHA-256 hex，同现有实现风格
}
```

删除旧 `of(String question, List<DecisionOption>)`。

- [ ] **Step 4: 改 Serde read/write decision** — 与 StepMeta 字段一一对应；缺字段用空列表/null。

- [ ] **Step 5: 测试 PASS → Commit**

```bash
git commit -m "$(cat <<'EOF'
refactor(decision): serde and fingerprint for questions questionnaire

EOF
)"
```

---

### Task 3: DecisionRegistry resolve(answers)

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionRegistry.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/DecisionRegistryTest.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/exception/OrchestratorErrorCode.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationController.java`（`mapResolveOutcome`）
- Modify: Redis payload 序列化（Registry 内 `storeToken`）

**Interfaces:**
- Produces:
  - `Registration register(String messageId, String userId, String title, List<DecisionQuestion> questions)`
  - `ResolveOutcome resolve(String token, List<DecisionAnswer> answers, String userId, String expectedMessageId)`
  - `ResolveOutcome` 增加 `INVALID_ANSWERS`
  - `OrchestratorErrorCode.DECISION_INVALID_ANSWERS(400, "decision_invalid_answers", "答案不完整或题目不匹配")`
- Redis value：JSON 含 `title`、`questions`（不要旧字段）
- Timeout/cancel Future：`new DecisionResult("timeout"|"cancelled", title, List.of(), now)`

- [ ] **Step 1: 更新/重写 Registry 单测**

```java
@Test
void resolve_accepts_multi_select_and_custom() {
    var questions = List.of(new DecisionQuestion(
            "q2", "关注？",
            List.of(new DecisionOption("perf", "性能"), new DecisionOption("ux", "体验")),
            true));
    Registration reg = registry.register(msgId, userId, "T", questions);
    var answers = List.of(new DecisionAnswer(
            "q2", List.of("perf", DecisionOption.CUSTOM_ID), "还要安全"));
    assertThat(registry.resolve(reg.token(), answers, userId, msgId))
            .isEqualTo(ResolveOutcome.ACCEPTED);
    DecisionResult r = reg.future().get(1, TimeUnit.SECONDS);
    assertThat(r.outcome()).isEqualTo("answered");
    assertThat(r.answers().get(0).selectedOptionIds()).containsExactly("perf", "__custom__");
}

@Test
void resolve_rejects_missing_question() {
    // 只答部分题 → INVALID_ANSWERS
}

@Test
void resolve_rejects_single_select_two_ids() {
    // allowMultiple=false 且 selected.size()!=1 → INVALID_CHOICE
}
```

- [ ] **Step 2: Run — FAIL**

```bash
mvn -pl orchestrator -Dtest=DecisionRegistryTest test
```

- [ ] **Step 3: 实现校验逻辑**

对每个 `DecisionQuestion`：
1. 必须有对应 `DecisionAnswer`（questionId 集合全等）。
2. `selectedOptionIds` 非空；`!allowMultiple` 则 size==1；每个 id ∈ options.id ∪ `{CUSTOM_ID}`。
3. 若含 `CUSTOM_ID`，`customInput` strip 非空，否则 `INPUT_REQUIRED`。
4. 通过则 `complete(new DecisionResult("answered", title, answers, now))`。

- [ ] **Step 4: Controller 映射 `INVALID_ANSWERS` → `DECISION_INVALID_ANSWERS`**；更新 `GenerationDecisionResolveTest`。

- [ ] **Step 5: 测试 PASS → Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(decision): registry resolve answers[] with multi-select and custom

EOF
)"
```

---

### Task 4: Timeline + Labels + Resume

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionTimelineSupport.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionResumeSupport.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/DecisionLabels.java`（若有 after 模板拼装）
- Modify: 对应 `*Test.java`

**Interfaces:**
- Produces:
  - `begin(bridge, token, title, questions, expiresAt)`
  - `complete(bridge, token, DecisionResult result)` — meta 写入 outcome + answers
  - `rebindAwaiting(..., title, questions, ...)`
  - Resume：`DecisionFingerprint.of(title, questions)`；pre-approval 整包 `DecisionResult`

- [ ] **Step 1: 改 Timeline 单测断言 `questions` / 无 `allowCustomInput`**

- [ ] **Step 2: 实现 begin/complete/pause 写新 `DecisionStepMeta`**

摘要 after：可用第一题选中 label 拼接，或 `用户已选择：` + 各题 `id=...`（**勿截断**模型 prompt/label）。Catalog 占位若仍用 `{choice}`，在 Labels 层把 answers 格式化为 choice 字符串注入（兼容旧模板键）。

- [ ] **Step 3: ResumeSupport 改读 `meta.questions()` / `meta.title()`；去掉 `allowCustomInput`**

- [ ] **Step 4: 单测 PASS → Commit**

```bash
git commit -m "$(cat <<'EOF'
refactor(decision): timeline and resume for multi-question cards

EOF
)"
```

---

### Task 5: RequestDecisionTool 新入参与 result

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/RequestDecisionTool.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/RequestDecisionToolTest.java`

**Interfaces:**
- Tool params: `title` (optional String), `questions` (Object — JSON 数组或原生 List)
- Produces result text:

```text
outcome=answered
title=...
q.{id}=id1,id2
q.{id}.custom=...
```

timeout/cancelled 见 spec §6.2。

- [ ] **Step 1: 重写 Tool 单测**

```java
@Test
void formats_answered_result_with_multi_question() {
    var answers = List.of(
            new DecisionAnswer("q1", List.of("agent"), null),
            new DecisionAnswer("q2", List.of("perf", "__custom__"), "安全"));
    String text = RequestDecisionTool.formatSuccessResult("Need", answers);
    assertThat(text).contains("outcome=answered");
    assertThat(text).contains("q.q1=agent");
    assertThat(text).contains("q.q2=perf,__custom__");
    assertThat(text).contains("q.q2.custom=安全");
}

@Test
void parses_questions_native_list() {
    // mock registry 立即 complete answered；调用 requestDecision("T", questionsList)
    // verify register(title, questions) 且 options 无 description
}
```

- [ ] **Step 2: Run — FAIL**

```bash
mvn -pl orchestrator -Dtest=RequestDecisionToolTest test
```

- [ ] **Step 3: 实现**

```java
@Tool(name = NAME, description = "向用户出选择题并等待作答。需求歧义或下一步依赖用户偏好时使用。勿用于写工具 HITL 确认。")
public String requestDecision(
        @ToolParam(name = "title", description = "可选总标题") String title,
        @ToolParam(name = "questions",
                description = "问题数组≥1。项：{id, prompt, options:[{id,label}]≥2, allowMultiple?}")
                Object questionsInput) {
    // validate → fingerprint → preApproval → register → begin → await → format
}
```

解析规则：与现 `parseAndValidateOptions` 同级，新增 `parseAndValidateQuestions`；**拒绝**旧扁平 `question`/`options` 顶层（无兼容分支）。

- [ ] **Step 4: PASS → Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(decision): request_decision tool accepts title + questions[]

EOF
)"
```

---

### Task 6: API body + BFF 透传

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ResolveDecisionRequest.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationController.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/generation/GenerationDecisionResolveTest.java`
- BFF：`OrchestratorClient.resolveDecision` 已透传 `Map` — **通常无需改**；确认 BFF Controller 原样转发 body。

**Interfaces:**
- `ResolveDecisionRequest(List<DecisionAnswer> answers)` 或嵌套 record：

```java
public record ResolveDecisionRequest(List<AnswerBody> answers) {
    public record AnswerBody(String questionId, List<String> selectedOptionIds, String customInput) {}
}
```

Controller：`decisionRegistry.resolve(token, mapToAnswers(body), userId, messageId)`。

- [ ] **Step 1: 更新 mapResolveOutcome 单测含 INVALID_ANSWERS**

- [ ] **Step 2: 改 Request + Controller 调用**

- [ ] **Step 3: `mvn -pl orchestrator -Dtest=GenerationDecisionResolveTest,DecisionRegistryTest,RequestDecisionToolTest test` PASS**

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(decision): resolve API body uses answers[]

EOF
)"
```

---

### Task 7: 前端类型 + DecisionCard

**Files:**
- Modify: `sunshine-ui/src/api/processingSteps.ts`（`DecisionOptionView` / `DecisionMeta`）
- Modify: `sunshine-ui/src/api/processingStepsParse.ts`
- Modify: `sunshine-ui/src/api/decisions.ts`
- Modify: `sunshine-ui/src/components/operation/DecisionCard.vue`

**Interfaces:**
- Types:

```ts
export interface DecisionOptionView { id: string; label: string }
export interface DecisionQuestionView {
  id: string; prompt: string; options: DecisionOptionView[]; allowMultiple?: boolean
}
export interface DecisionAnswerView {
  questionId: string; selectedOptionIds: string[]; customInput?: string
}
export interface DecisionMeta {
  token?: string; title?: string; questions?: DecisionQuestionView[]
  expiresAt?: number; outcome?: string; answers?: DecisionAnswerView[]
}
```

- `resolveDecision(generationId, token, answers: DecisionAnswerView[])`
- Card：同卡渲染全部 questions；每题末尾追加 `{ id: '__custom__', label: '其他' }`；单选/多选；全部合法才可提交。

- [ ] **Step 1: 改 types + parse**（读 `questions`；忽略旧 `question/options` 若出现可视为无题，不本地编造）

- [ ] **Step 2: 改 `decisions.ts` body 为 `{ answers }`**

- [ ] **Step 3: 重写 DecisionCard 交互状态**

```ts
// selections: Record<questionId, string[]>
// customInputs: Record<questionId, string>
// 提交构建 answers；含 __custom__ 则带 customInput
```

样式沿用现有 `.mode-menu-item` / 对号；多选时点击 toggle id，不互斥。

- [ ] **Step 4: 手工或既有前端检查无 TS 报错**（`npm`/`pnpm` 按仓库惯例）

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): DecisionCard multi-question multi-select with always-on custom

EOF
)"
```

---

### Task 8: Prompt Catalog + seed

**Files:**
- Modify: `docker/mysql/init/19-sunshine-resource.sql`（`mode-overlay.react` 中 RequestDecision 段）
- 运行时：经 resource-manager `/prompts` 发新版本（与既有 v7 流程一致），示例改为：

```text
【RequestDecision】
- 入参：title? + questions[{id,prompt,options:[{id,label}],allowMultiple?}]
- 选项只有 id 与 label（label 即答案，勿加 description）
- 禁止在用户可见正文写 A/B/C 选项列表
```

**勿改**外部 skill（如 brainstorming）。

- [ ] **Step 1: 改 seed SQL 文本**

- [ ] **Step 2: 发布运行时 overlay（若环境已有旧版）并重启 resource-manager / orchestrator**

- [ ] **Step 3: Commit seed**

```bash
git commit -m "$(cat <<'EOF'
docs(prompt): RequestDecision overlay examples use Cursor questions shape

EOF
)"
```

---

### Task 9: Live 脚本 + 文档收尾

**Files:**
- Modify: `scripts/verify_decision_live.py`
- Modify: specs 状态（修订 ✅；父文档指向已实施）
- Modify: `docs/implementation-plan.md` / `CLAUDE.md` 若有 4.7.9 一行描述则补「Cursor 对齐」

**Live 用例映射：**

| ID | 断言 |
|----|------|
| R1 | 单题；UI/API 提交 `__custom__` + custom → tool 侧 answered |
| R2 | `allowMultiple` 两 id |
| R3 | 两题 answers 全覆盖才 200 |
| R4 | 第二次 call 错误（可从 tool result / 无第二卡） |
| 保留 | 超时/取消/暂停续跑（改断言读 `outcome` / `questions`） |

- [ ] **Step 1: 改脚本解析 `metadata.decision.questions`，resolve body 用 answers**

- [ ] **Step 2: 开 `enabled: true`（临时）→ sync_nacos → restart → 跑脚本关键路径**

```bash
python scripts/sync_nacos.py
python scripts/start.py --restart orchestrator bff
python scripts/verify_decision_live.py
```

- [ ] **Step 3: 验收后按 D21 把 Nacos `enabled` 恢复 false（除非用户要求保持开）**

- [ ] **Step 4: 文档状态 ✅ → Commit**

```bash
git commit -m "$(cat <<'EOF'
test(decision): live script and docs for Cursor-aligned request_decision

EOF
)"
```

---

## Spec coverage self-check

| Spec | Task |
|------|------|
| C1 端到端 wire | 1–7 |
| C2 始终其他 | 3, 7 |
| C3 一问卷一 token | 3, 5（既有 hasAwaiting） |
| C4 同卡一页 | 7 |
| C5 无 description/requireInput/allow_custom | 1, 5, 8 |
| C6 短 description + overlay 策略 | 5, 8 |
| resolve answers + 错误码 | 3, 6 |
| tool result outcome= | 5 |
| resume fingerprint | 2, 4 |
| Live R1–R4 | 9 |
| 不改 Planner / WorkflowNodeRunner | 全局约束 |

无 TBD；类型名前后一致（`DecisionQuestion` / `DecisionAnswer` / `CUSTOM_ID`）。
