# 设置页分组 + 个人规则（Personal Rules）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 设置弹窗改为左侧分组导航（账号/对话偏好/个人规则），新增用户个人规则（soul）配置并注入全部顶层执行模式的提示词。

**Architecture:** `sys_user.personal_rules` 持久化（auth-center）；前端随聊天请求体 `personalRules` 透传（与 `executionPreference` 同链路，BFF 不加工）；orchestrator 统一包装为「## 用户个人规则」块——ReAct 链路走 `AgentRunRequest.injectedBlocks` 首位，Gateway 直链链路（workflow llm/answer、专家发言、Synthesizer）经 `PromptComposeRequest.personalRules` 在 base-system 之后注入。

**Tech Stack:** Java 17 + Spring Boot（WebFlux）、JPA/MySQL、Vue3 + Naive UI + Pinia、Python（运维脚本）。

**Spec:** `docs/superpowers/specs/2026-07-28-user-personal-rules-design.md`

## Global Constraints

- **禁 Flyway**：DDL SSOT 在 `docker/mysql/init/10-sunshine-auth.sql`（CREATE TABLE 内联新列 + 文件尾幂等 ALTER）。
- **禁硬编码提示词**：个人规则包装头「## 用户个人规则」是结构分隔标记（类比现有 injectedBlocks 块头），固定常量；不新增正文提示词。
- **模型输出不二次加工**：orchestrator 对 `personalRules` 仅做 trim + 超长（>4000）截断防御 + warn，不改写内容。
- **身份安全**：`x-user-id` 仍仅由 Gateway 注入；`personalRules` 是偏好数据，客户端可传。
- `ChatCompletionResponse` 类 DTO 用 `@Builder` 须加 `@NoArgsConstructor` + `@AllArgsConstructor`（本计划不新增 builder DTO 到该链路）。
- 前端 UI 遵循 Codex 简约 SSOT：`--sun-black` 底、`1px var(--sun-border)` 分区；输入框 `sun-field` 覆写；**禁止** `--sun-surface`/`--sun-deep` 灰底；分组选中态文字加粗 + 无灰底。
- 代码加适量中文注释；**禁止**业务代码中多余空行。
- 运维脚本统一 Python（`scripts/*.py`），禁临时脚本。
- 子 Agent（workflow 子 Agent / spawn 子 Agent）**不**注入个人规则（上下文隔离）。
- 后端改完：编译 → 重启对应服务（`scripts/start.py`）→ 跑验收。

## 统一包装约定（所有任务共用）

```java
// orchestrator 新类 com.sunshine.orchestrator.prompt.PersonalRulesSupport
public final class PersonalRulesSupport {
    public static final int MAX_LENGTH = 4000;
    private PersonalRulesSupport() {}
    /** 返回「## 用户个人规则\n{rules}」；空/超长处理：trim，超 MAX_LENGTH 截断 + 可传 log 处 warn；null/空白返回 null */
    public static String wrap(String personalRules) { /* trim；hasText 否则 null；超 4000 截断；返回 "## 用户个人规则\n" + trimmed */ }
}
```

---

### Task 1: DB DDL + auth-center 持久化（personal_rules 字段）

**Files:**
- Modify: `docker/mysql/init/10-sunshine-auth.sql`
- Modify: `auth-center/src/main/java/com/sunshine/auth/entity/UserEntity.java`
- Modify: `auth-center/src/main/java/com/sunshine/auth/dto/UpdateProfileRequest.java`
- Modify: `auth-center/src/main/java/com/sunshine/auth/dto/AuthUserVO.java`
- Modify: `auth-center/src/main/java/com/sunshine/auth/dto/LoginResponse.java`
- Modify: `auth-center/src/main/java/com/sunshine/auth/dto/UpdateProfileResponse.java`
- Modify: `auth-center/src/main/java/com/sunshine/auth/service/UserService.java`
- Test: `auth-center/src/test/java/com/sunshine/auth/AuthControllerTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: `UserEntity.getPersonalRules()`；`UpdateProfileRequest.getPersonalRules()`（`@Size(max=4000)`，`null`=不修改）；`AuthUserVO/LoginResponse/UpdateProfileResponse` 均含 `personalRules` 字段

- [ ] **Step 1: 写失败测试**

在 `AuthControllerTest` 新增用例（参考现有 updateProfile 测试的写法）：

```java
@Test
void updateProfileSavesAndReturnsPersonalRules() throws Exception {
    // 登录 alice → PATCH /api/auth/profile 带 personalRules="回答用文言文"
    // 断言 200 + 响应 personalRules="回答用文言文"
    // 再 GET /api/auth/me 断言 personalRules 持久化
}

@Test
void updateProfileBlankPersonalRulesClearsToNull() throws Exception {
    // 先设置非空，再 PATCH personalRules=""，断言 me() 返回 personalRules 为 null
}

@Test
void updateProfileNullPersonalRulesKeepsExisting() throws Exception {
    // 先设置非空，再 PATCH 不带 personalRules 字段，断言 me() 仍返回原值
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl auth-center test -Dtest=AuthControllerTest#updateProfileSavesAndReturnsPersonalRules`
Expected: FAIL（字段不存在，编译失败或断言失败）

- [ ] **Step 3: 实现**

`10-sunshine-auth.sql`：`CREATE TABLE sys_user` 内联新列：

```sql
    personal_rules TEXT NULL COMMENT '用户个人规则（soul），注入系统提示',
```

文件尾追加幂等 ALTER（注释说明既有环境执行）：

```sql
-- 既有环境迁移：ALTER TABLE sys_user ADD COLUMN personal_rules TEXT NULL COMMENT '用户个人规则（soul），注入系统提示';
```

`UserEntity` 新增：

```java
    @Column(name = "personal_rules", columnDefinition = "TEXT")
    private String personalRules;
```

`UpdateProfileRequest` 新增：

```java
    /** 个人规则（soul）；null=不修改，空串=清空，最长 4000 字符 */
    @Size(max = 4000, message = "个人规则最长 4000 字符")
    private String personalRules;
```

三个 VO（`AuthUserVO` / `LoginResponse` / `UpdateProfileResponse`）各加字段：

```java
    /** 用户个人规则（soul） */
    private String personalRules;
```

`UserService`：
- `updateProfile` 中：

```java
        if (request.getPersonalRules() != null) {
            String trimmed = request.getPersonalRules().trim();
            user.setPersonalRules(trimmed.isEmpty() ? null : trimmed);
        }
```

- `toVo` / `toUpdateProfileResponse` / `login` 的 builder 各加 `.personalRules(user.getPersonalRules())`。

- [ ] **Step 4: 跑测试确认通过 + 既有库执行 ALTER**

Run: `mvn -pl auth-center test`
Expected: PASS（含新 3 用例 + 既有用例）

对运行中的 MySQL 执行 ALTER（经 `scripts/sunshine_lib.py` 的 MySQL 连接方式或 `docker exec` mysql 客户端）：

```sql
ALTER TABLE sunshine_auth.sys_user ADD COLUMN personal_rules TEXT NULL COMMENT '用户个人规则（soul），注入系统提示';
```

- [ ] **Step 5: 编译 auth-center 并重启**

Run: `mvn -pl auth-center -am compile -q` → 重启 auth-center（`python scripts/start.py` 或单服务重启方式）
Expected: 启动正常，`/api/auth/me` 返回含 `personalRules` 字段

---

### Task 2: orchestrator 包装工具 + PromptComposeRequest/Composer 注入（Gateway 直链链路）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/prompt/PersonalRulesSupport.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/prompt/PromptComposeRequest.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/prompt/PromptComposer.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/prompt/PromptComposerTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `PersonalRulesSupport.wrap(String) → String|null`（约定见 Global Constraints）
  - `PromptComposeRequest` 新增末尾字段 `String personalRules`；工厂 `forReact(...)`/`forDirect(...)`/`forDirectContinue(...)`/`forWorkflowLlm(...)`/`forExpertSpeak(...)` 各新增带 `personalRules` 的重载（原签名保留、委托传 `null`）
  - `PromptComposer` 行为：Gateway 链路在 base-system 之后追加 `addGatewaySystem(messages, PersonalRulesSupport.wrap(request.personalRules()))`；React 链路在 mode-overlay 之后 `addReactUser(inputs, wrap(...))`；空不注入

- [ ] **Step 1: 写失败测试**

`PromptComposerTest` 新增：

```java
@Test
void gatewayComposeInjectsPersonalRulesAfterBaseSystem() {
    // forDirect(ctx, "问题", "用文言文回答") → composeGatewayMessages
    // 断言 index("## 用户个人规则\n用文言文回答") == index(base-system) + 1，role=system
}

@Test
void reactComposeInjectsPersonalRules() {
    // forReact(ctx, "问题", List.of(), false, null, "用文言文回答") → composeReactInputs
    // 断言含 "## 用户个人规则\n用文言文回答" 的 USER 消息
}

@Test
void blankPersonalRulesNotInjected() {
    // personalRules="  " 与 null 两种，断言消息列表无「用户个人规则」
}

@Test
void oversizedPersonalRulesTruncated() {
    // 4001+ 字符 → 注入块正文长度为 4000
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=PromptComposerTest`
Expected: FAIL（`personalRules` 字段/重载不存在）

- [ ] **Step 3: 实现**

`PersonalRulesSupport`：

```java
package com.sunshine.orchestrator.prompt;

import org.springframework.util.StringUtils;

/** 用户个人规则（soul）包装 — 注入提示词前的统一分隔头；仅 trim + 长度防御，不改写内容 */
public final class PersonalRulesSupport {

    public static final int MAX_LENGTH = 4000;
    private static final String HEADER = "## 用户个人规则\n";

    private PersonalRulesSupport() {
    }

    /** 空/全空白 → null（不注入）；超 MAX_LENGTH 截断（防御，前端已限长） */
    public static String wrap(String personalRules) {
        if (!StringUtils.hasText(personalRules)) {
            return null;
        }
        String trimmed = personalRules.strip();
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        return HEADER + trimmed;
    }
}
```

`PromptComposeRequest`：record 末尾加 `String personalRules`；canonical 构造不变（record 自动）；所有现有工厂保持原签名、委托时末尾传 `null`；为五个工厂各加末参 `personalRules` 的新重载（如 `forDirect(context, userMessage, personalRules)`）。

`PromptComposer`：
- `appendCommonGatewayLayers`：在 `addGatewaySystem(messages, catalogText("system-prompt"))` 之后（`includeBaseSystem` 分支外，确保即使 includeBaseSystem=false 也注入）加：

```java
        addGatewaySystem(messages, PersonalRulesSupport.wrap(request.personalRules()));
```

- `appendCommonReactLayers`：在 `resolveModeOverlay` 之后加：

```java
        addReactUser(inputs, PersonalRulesSupport.wrap(request.personalRules()));
```

注意：`wrap` 返回 null 时 `addGatewaySystem`/`addReactUser` 的 hasText 判空天然跳过，无需额外分支。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=PromptComposerTest`
Expected: PASS

- [ ] **Step 5: 编译 orchestrator**

Run: `mvn -pl orchestrator -am compile -q`
Expected: BUILD SUCCESS

---

### Task 3: 请求模型 + 执行上下文透传（ChatMessage → ExecutionStreamContext）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/model/ChatMessage.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`（构造 ChatStreamContext 处）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/stream/ChatStreamContext.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ExecutionStreamContext.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/stream/ChatStreamExecutor.java`（`toExecutionContext`）
- Modify: `bff/src/main/java/com/sunshine/bff/model/ChatRequest.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/ReactExecutorTest.java`

**Interfaces:**
- Consumes: Task 2 的 `PersonalRulesSupport`
- Produces:
  - `ChatMessage.getPersonalRules()`；BFF `ChatRequest.getPersonalRules()`（透传字段，BFF 无逻辑）
  - `ChatStreamContext.personalRules()`（record 末尾新字段）
  - `ExecutionStreamContext.personalRules()`（record 末尾新字段；既有便捷构造器与 `withPlan/withPersistedPlanId/withWorkflowHitl/withResumeInteraction/withHitlPreApproved` 全部透传该字段；既有 9 参/13 参便捷构造器补 `null`）

- [ ] **Step 1: 写失败测试**

`ReactExecutorTest` 新增：

```java
@Test
void executePassesPersonalRulesAsFirstInjectedBlock() {
    // 构造 ExecutionStreamContext（新字段 personalRules="用文言文"）
    // mock agentRuntime.run 捕获 AgentRunRequest
    // 断言 injectedBlocks().get(0) == "## 用户个人规则\n用文言文"
}

@Test
void executeWithoutPersonalRulesKeepsInjectedBlocksUntouched() {
    // personalRules=null → 断言 injectedBlocks 与传入一致（无新增首元素）
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=ReactExecutorTest`
Expected: FAIL（字段不存在，编译失败）

- [ ] **Step 3: 实现**

1. `ChatMessage` / BFF `ChatRequest` 各加：

```java
    /** 用户个人规则（soul）；空则不注入，>4000 由 orchestrator 防御截断 */
    private String personalRules;
```

2. `ChatStreamContext` record 末尾加 `String personalRules`；`ChatController` 构造处传 `msg.getPersonalRules()`（resume 路径 `prep.toStreamContext()` 传 `null`——续跑不重注入）。
3. `ExecutionStreamContext` record 末尾加 `String personalRules`；所有便捷构造器与 `with*` 方法在 canonical 构造调用中透传/补 `null`。
4. `ChatStreamExecutor.toExecutionContext` 末参传 `ctx.personalRules()`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=ReactExecutorTest`
Expected: PASS（编译错误全部修复后；注意 main 代码与 test 代码中所有 `new ExecutionStreamContext(...)` 全参调用点都要补末参 `null`——`PlannerAgentRuntime.java:29` 及各 Test 文件）

- [ ] **Step 5: 编译 orchestrator + bff**

Run: `mvn -pl orchestrator,bff -am compile -q`
Expected: BUILD SUCCESS

---

### Task 4: 顶层执行路径注入（React / Plan-Workflow / Workflow llm+answer / 专家 Hub + Synthesizer）

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ReactExecutor.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/handler/WorkflowLlmStreamSupport.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ExpertConsultationExecutor.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertSpeakStreamer.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/expert/ConsultationSynthesizer.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/execution/ReactExecutorTest.java`（Task 3 已建，此处断言已覆盖）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertHubEngineCreateAgentTest.java`（或新增 `PersonalRulesInjectionTest`）

**Interfaces:**
- Consumes: Task 2 的 `forReact/forWorkflowLlm/forExpertSpeak` 带 personalRules 重载；Task 3 的 `ctx.personalRules()`
- Produces:
  - `ExpertHubEngine.run(..., String userId, String tenantId, String personalRules)`（新末参重载；原签名委托传 null）
  - `ExpertSpeakStreamer.streamSpeak(expert, userQuery, contextBlocks, gatheredContext, personalRules)`
  - `ConsultationSynthesizer.synthesize(userQuery, transcript, personalRules)`

- [ ] **Step 1: 写失败测试**

新增 `orchestrator/src/test/java/com/sunshine/orchestrator/expert/ExpertSpeakStreamerTest.java`（若已有同类测试则扩展）：

```java
@Test
void speakComposesWithPersonalRules() {
    // mock LlmGatewayClient.streamComposed 捕获 PromptComposeRequest
    // streamSpeak(expert, "q", List.of(), "", "用文言文")
    // 断言 captured.personalRules() == "用文言文"
}
```

`WorkflowLlmStreamSupport` 的测试（找现有 `buildRequest` 测试或新建）：

```java
@Test
void workflowLlmRequestCarriesPersonalRules() {
    // streamCtx 带 personalRules → buildRequest(...).personalRules() 非空
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=ExpertSpeakStreamerTest`
Expected: FAIL（方法签名不存在）

- [ ] **Step 3: 实现**

1. `ReactExecutor.executeWithInjected`：

```java
        List<String> blocks = new ArrayList<>();
        String wrapped = PersonalRulesSupport.wrap(ctx.personalRules());
        if (wrapped != null) {
            blocks.add(wrapped);
        }
        if (injectedBlocks != null) {
            blocks.addAll(injectedBlocks);
        }
        return agentRuntime.run(AgentRunRequest.main(
                        ctx.memory(), query, ctx.userId(), ctx.tenantId(), ctx.assistantMsgId(),
                        blocks, skillId, ctx.reactRestart(),
                        ctx.conversationId(), reactPromptId, checkpointThinkIteration));
```

（覆盖 React 顶层 + Plan-Workflow 降级 + ExpertConsultationExecutor 降级 react 路径）

2. `WorkflowLlmStreamSupport.buildRequest`：

```java
        return PromptComposeRequest.forWorkflowLlm(workflowId, memory, userQuery, nodePrompt, streamCtx.personalRules());
```

（覆盖 workflow llm 节点与终态 answer 节点）

3. `ExpertConsultationExecutor`：`expertHubEngine.run(...)` 末参加 `ctx.personalRules()`；`consultationSynthesizer.synthesize(query, hubResult.transcript(), ctx.personalRules())`。
4. `ExpertHubEngine.run`：新末参 `personalRules`（原 7 参签名委托传 `null`）；透传到 `invokeAgent` → `expertSpeakStreamer.streamSpeak(..., personalRules)`。
5. `ExpertSpeakStreamer.streamSpeak`：新末参；`forExpertSpeak(..., personalRules)`。
6. `ConsultationSynthesizer.synthesize`：新末参；改用 `llmGatewayClient.streamComposed(PromptComposeRequest.forDirect(AssembledContext.empty(), prompt, personalRules))` 替换 `streamDirectly(prompt)`（行为等价 + 携带 personalRules；`streamDirectly` 内部本来就是 forDirect 空上下文）。注意 `ConsultationSynthesizer` 当前没有 import `AssembledContext`/`PromptComposeRequest`，补上。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl orchestrator test -Dtest='ExpertSpeakStreamerTest,ReactExecutorTest,PromptComposerTest,ExpertHubEngineCreateAgentTest'`
Expected: PASS

- [ ] **Step 5: 全量 orchestrator 单测 + 编译 + 重启**

Run: `mvn -pl orchestrator test -q`（全量防回归）→ `mvn -pl orchestrator,bff -am compile -q` → 重启 orchestrator + bff（`python scripts/start.py`）
Expected: 全部 PASS；服务启动正常

---

### Task 5: 前端 — auth API/store + 聊天发送携带 personalRules

**Files:**
- Modify: `sunshine-ui/src/api/auth.ts`
- Modify: `sunshine-ui/src/stores/authStore.ts`
- Modify: `sunshine-ui/src/api/chatSessions.ts`
- Test: 无单测（前端无该层测试基建；由 Task 7 live 验收兜底）

**Interfaces:**
- Consumes: Task 1 的 API 字段 `personalRules`
- Produces:
  - `AuthUser.personalRules?: string | null`
  - `updateProfile(nickname, tenantId, defaultWriteHitlMode?, personalRules?)`（auth.ts + authStore 同签名）
  - `chatSessions.send` 请求体自动带 `personalRules`（非空时；resume 路径不带）

- [ ] **Step 1: 实现 auth.ts + authStore**

`auth.ts`：`AuthUser` 加 `personalRules?: string | null`；`updateProfile` 加第 4 参 `personalRules?: string | null`，body JSON 加 `personalRules`。

`authStore.ts`：`applyUser` 不变（user 对象类型已含）；`updateProfile(nickname, tenantId, defaultWriteHitlMode?, personalRules?)` 透传。

- [ ] **Step 2: 实现 chatSessions.send 携带**

`chatSessions.ts` 顶部 import auth store（注意循环依赖：`authStore` 不依赖 `chatSessions`，安全）：

```ts
import { useAuthStore } from '../stores/authStore'
```

`send` 函数构造 body 处（`writeHitlMode` 之后）：

```ts
      const personalRules = useAuthStore().user?.personalRules?.trim()
      if (personalRules) {
        body.personalRules = personalRules
      }
```

注意：`body` 类型是 `Record<string, string>`，无需改类型。resume/续跑发送路径（同文件 339 行附近的第二个 fetch）**不加**。

- [ ] **Step 3: 前端类型检查 + 构建**

Run: `cd sunshine-ui && npx vue-tsc --noEmit`（或项目既有 lint/build 命令）→ `npm run build`
Expected: 无类型错误；构建成功

---

### Task 6: 前端 — UserSettingsModal 分组 UI + 个人规则编辑

**Files:**
- Modify: `sunshine-ui/src/components/UserSettingsModal.vue`

**Interfaces:**
- Consumes: Task 5 的 `authStore.updateProfile(nickname, tenantId, defaultWriteHitlMode?, personalRules?)` 与 `auth.user.personalRules`
- Produces: 分组弹窗 UI（分组 id：`account` / `chat` / `rules`）

- [ ] **Step 1: 实现分组布局**

弹窗宽度 `min(680px, 94vw)`；卡片体内左右 flex 布局：

```
+----------------+------------------------------+
| 账号           |  [右侧当前分组配置项]        |
| 对话偏好       |                              |
| 个人规则       |                              |
+----------------+------------------------------+
```

- 左侧 `nav`：宽 160px，右border `1px solid var(--sun-border)`；三个按钮项，选中态 `font-weight: 600` + 无背景（禁灰底）。
- `activeGroup = ref<'account'|'chat'|'rules'>('account')`，打开弹窗时重置为 `'account'`。
- 右侧三组配置用 `v-show`/`v-if` 切换：
  - `account`：用户名（disabled）、昵称
  - `chat`：当前租户、默认执行模式、默认写操作确认（原 3 项原样搬迁）
  - `rules`：`NInput type="textarea"` `v-model:value="personalRules"` `maxlength="4000"` `autosize={minRows:8,maxRows:16}` `class="sun-field"` + hint：「将作为系统提示注入你的所有对话（ReAct / Workflow / 专家协作）；留空则不注入。子 Agent 不继承。」+ 字数统计（Naive `show-count`）

- [ ] **Step 2: 状态与保存接线**

- `personalRules = ref('')`；`watch(props.show)` 打开时 `personalRules.value = auth.user?.personalRules ?? ''`。
- `handleSave`：`await auth.updateProfile(value, tenantId.value, defaultWriteHitl.value, personalRules.value)`；成功提示「资料已更新」。
- 昵称必填校验保留；个人规则可空。

- [ ] **Step 3: 样式**

scoped style 追加（遵循 SSOT）：

```css
.settings-body { display: flex; gap: 16px; min-height: 320px; }
.settings-nav { width: 160px; flex-shrink: 0; border-right: 1px solid var(--sun-border); display: flex; flex-direction: column; gap: 2px; padding-right: 12px; }
.settings-nav-item { padding: 8px 10px; border: none; background: transparent; color: var(--sun-text); text-align: left; cursor: pointer; font-size: var(--sun-font-base); border-radius: 6px; }
.settings-nav-item:hover { color: var(--sun-text-strong, inherit); }
.settings-nav-item.active { font-weight: 600; background: transparent; }
.settings-panel { flex: 1; min-width: 0; }
```

输入框沿用 `sun-field`；弹窗底栏按钮不变。

- [ ] **Step 4: 构建 + 人工/Live 验证 UI**

Run: `cd sunshine-ui && npm run build` → 启动前端（或 dev server）登录后打开账号设置：三组切换正常、个人规则可编辑保存刷新后仍在
Expected: 构建成功；UI 符合分组设计

---

### Task 7: Live 验收脚本 + 全链路回归

**Files:**
- Create: `scripts/verify_personal_rules_live.py`
- Modify: `CLAUDE.md`（运维脚本表追加一行）+ `README.md`（若脚本表在 README 有镜像则同步）

**Interfaces:**
- Consumes: Task 1–6 全部
- Produces: `python scripts/verify_personal_rules_live.py` 可独立运行的验收

- [ ] **Step 1: 写验收脚本**

参考 `scripts/verify_hitl_live.py` / `verify_execution_preference.py` 的既有模式（BFF 登录拿 token → 调 API → SSE 消费断言），场景：

1. **P1 设置规则**：PATCH `/api/auth/profile` 设 `personalRules="无论用户问什么，回答开头必须先说一句「领命」二字"` → 断言 200 + me() 返回该规则。
2. **P2 ReAct 生效**：POST `/api/chat/stream`（body 带 `personalRules`，模拟前端行为；`executionPreference` 省略走 auto 或显式 `react`）问「你好」→ 消费 SSE 至 completed，断言 assistant content 以「领命」开头或包含「领命」。
3. **P3 Workflow 生效**：强制 `executionPreference=workflow`（或 `#knowledge-branch` 类标杆 workflowId）发问 → 断言 answer 含「领命」。
4. **P4 清空**：PATCH `personalRules=""` → me() 返回 null → 再发一条 chat 断言不再含「领命」。
5. **P5 请求体不带则不注入**：P1 重设规则后，构造**不带** personalRules 的 chat 请求（模拟旧前端/绕过）→ 断言不含「领命」（证明注入来源是请求体透传而非服务端状态）。

- [ ] **Step 2: 跑 Live 验收**

前置：全链路服务在跑（`python scripts/start.py`），MySQL 已执行 ALTER。

Run: `python scripts/verify_personal_rules_live.py`
Expected: P1–P5 全 PASS

- [ ] **Step 3: 全链路回归**

Run（按 CLAUDE.md 既有门禁挑选）:
- `mvn -pl auth-center,orchestrator,bff test -q`
- `python scripts/verify_prompt_catalog_live.py`（提示词链路冒烟）
- `python scripts/verify_hitl_live.py --live`（HITL 回归，因动了 ReactExecutor/ChatMessage）

Expected: 全 PASS

- [ ] **Step 4: 文档收尾**

`CLAUDE.md` 运维脚本表加：`| verify_personal_rules_live.py | 个人规则（soul）注入 Live（P1–P5：设置/各模式生效/清空/不带不注入） |`。
如 `docs/implementation-plan.md` 有进度看板，登记本特性完成。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(settings): 设置页分组 + 个人规则（soul）全模式注入

- sys_user.personal_rules + auth-center profile 保存/返回
- ChatMessage.personalRules 透传 → injectedBlocks 首位 / Gateway 直链注入
- 设置弹窗左侧分组导航（账号/对话偏好/个人规则）"
```

---

## Self-Review 记录

- **Spec 覆盖**：分组 UI(T6)、DDL/auth(T1)、包装+Composer(T2)、请求模型透传(T3)、四路径注入(T4)、前端发送(T5)、Live 验收+回归(T7) — 全覆盖；子 Agent 不注入 = 无改动，符合 spec。
- **类型一致性**：`personalRules` 全链路同名；`wrap` 唯一包装入口；`ExecutionStreamContext` 新字段在所有 `with*` 与便捷构造器透传（Step 3.3 明确）。
- **风险点**：① `ExecutionStreamContext` 全参构造调用点较多（main 1 处 + test 多处），Task 3 Step 4 已列出；② `ConsultationSynthesizer` 由 `streamDirectly` 切到 `streamComposed`，行为等价性已在 Task 4 Step 3.6 说明；③ resume 路径不重注入（Task 3 Step 3.2 明确传 null），与 spec 一致。
