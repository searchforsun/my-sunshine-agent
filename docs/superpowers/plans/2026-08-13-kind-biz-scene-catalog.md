# Kind · Biz-Scene Lab · Toolset Axis Implementation Plan

> **状态**：✅ 已实现（K0～K4 全绿；Live `scripts/verify_kind_biz_scene_live.py`）  
> **Spec**：[kind-biz-scene-catalog](../specs/archive/2026-08-13-kind-biz-scene-catalog-design.md) · [business-context-authority](../specs/2026-08-13-business-context-authority-design.md) §2.1（解析算法）  
> **前置**：routing v6 + H-5 ✅（`fast|pro|workflow`；会话 `kind` 已透传）· H-6 ✅  
> **本 plan 不做（仍延期）**：H-7 Live；阶段 D / R-4；完整业务权威装载（Policy∥任务板∥偏好注入 Prompt，属 business-context 后续）；Tool/Workflow 挂 `biz_scene`；独立场景分类器 / HITL 选场景  
>
> **分期**：K0 工具集轴 → K1 资源 `kind` 过滤 → K2 业务场景 Lab → K3 退役 react-prompt → K4 上下文分栏  

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 默认工具集与资源可发现面按会话 **`kind=chat|task`** 对齐；侧栏独立 **业务场景 Lab** 成为 `biz_scene` 闭集 SSOT；退役 Catalog `react-prompt` / `reactPromptId`；上下文管理页按 kind 分栏。

**Architecture:** 工具集枚举与 Admin/Runtime path 从 `react-default|plan-workflow` 迁到 `chat|task`（读双写旧、写只新）；Skill/Agent/Workflow 元数据加 `kind`（默认 `all`）与 Skill/Agent 可空 `biz_scene`；意图候选构建时按会话 kind 过滤；`biz_scene` 解析保持 authority §2.1（空则跳过权威层挂钩点）。Prompt 业务文案迁 Skill overlay，机制层 `mode-overlay.*` / harness 保留。

**Tech Stack:** Java 17 / Spring · resource-manager :8240 · tool-service :8210 · orchestrator :8200 · Vue3 + Naive UI · MySQL init SSOT（禁 Flyway）· JUnit5 + Vitest · Live `scripts/verify_kind_biz_scene_live.py`

## Global Constraints

- **四轴**：`kind` ⊥ `executionMode` ⊥ `biz_scene` ⊥ `callSite` — 禁止互写；资源字段名必须是 **`kind`**（禁止 `applicable_kind` / `kind_scope`）
- **默认工具集只按会话 kind**：`chat×fast` 与 `chat×pro` **共用** chat 集；禁止再按 `fast|pro` 分裂工具集
- **`executionMode=workflow`**：以节点/显式工具为准，不强制吃默认集
- **Lab**：独立侧栏与「提示词」平级；禁止挂 Prompt 子 Tab；禁止运行时 AI 新建码
- **退役**：禁止长期保留 `reactPromptId`「兼容双写」；K3 结束 grep 零业务引用（测试夹具可同步删）
- **禁止 Flyway**：DDL / 种子进 `docker/mysql/init/`
- 提示词正文 SSOT = Catalog；模型输出不二次加工
- 改 `docs/nacos/*.yaml` → `sync_nacos.py` + 重启消费服务；后端改完 `start.py --restart <svc>`
- 编译：`mvn test -pl <module> -Dtest=… -q`；前端：`cd sunshine-ui && npx vitest run <spec>`

---

## File map

| 区域 | 文件 | 职责 |
|------|------|------|
| 工具集枚举 | `tool-service/.../ToolSetKind.java` | `CHAT_DEFAULT` / `TASK_DEFAULT` + `fromPath` 双读旧 path |
| DDL 工具 | `docker/mysql/init/16-sunshine-tool-service.sql` | 新 set id 种子；注释 critical= task 集 |
| Runtime/Admin | `ToolSetRuntimeController` · `ToolsAdminController` · `ToolSetMemberService` · BFF `ToolsAdminController` | path `chat`/`task`；读时可回落旧 set |
| Client | `orchestrator/.../ToolManagerClient.java` · `sunshine-ui/src/api/tools.ts` | `fetchChatDefault` / `fetchTaskDefault`；UI kind path |
| Resolver | `orchestrator/.../catalog/ToolSetResolver.java` | `resolveChatTools` / `resolveTaskTools` / `resolveTaskCriticalTools` |
| Toolkit | `DynamicToolkitFactory` · `ReactExecutor` · `HarnessPlanner` · `PlannerHarnessLoop` | 按 `conversation.kind` 选集 |
| UI 工具集 | `ToolsetTabPanel.vue` · `ToolSetAddModal.vue` | Tab **chat \| task** |
| 资源 DDL | `docker/mysql/init/19-sunshine-resource.sql` | `kind` / `biz_scene` 列；`biz_scene_definition`；`biz_scene_policy` |
| Skill/Agent | resource-manager entity/DTO/Admin/Catalog | 暴露 `kind`/`biz_scene`；保存校验 Lab active |
| Workflow | workflow-manager 定义表对等列（若独立库） | 资源 `kind` 默认 `all`；一期不挂 `biz_scene` |
| 过滤 | orchestrator `SkillCatalog*` / Agent catalog / Intent 候选 | `资源.kind ∈ {会话.kind, all}` |
| Lab UI | 新 `BizScenesView.vue` + `MainLayout` 侧栏项 | 码表 + Policy 同页或邻接 Tab |
| Prompt 退役 | `PromptsView` · `RoutingRuleEditor` · Intent/Forced 路由 · SQL 种子 | 删 Tab / 字段 / `react-prompt.*` |
| 上下文 | `ContextView.vue` · `ContextConversationList` · panels | chat\|task 分栏；Tab 随 kind |
| Live | `scripts/verify_kind_biz_scene_live.py` | K0/K1 工具集与过滤冒烟 |

---

## 迁移对照（全 plan 共用）

| 旧 | 新 |
|----|-----|
| `ToolSetKind.REACT_DEFAULT` / path `react-default` / id `global-react-default` | `CHAT_DEFAULT` / `chat` / `global-chat-default` |
| `ToolSetKind.PLAN_WORKFLOW` / path `plan-workflow` / id `global-plan-workflow` | `TASK_DEFAULT` / `task` / `global-task-default` |
| `tenant-*-react-default` | `tenant-*-chat-default` |
| `tenant-*-plan-workflow` | `tenant-*-task-default` |
| set_type `global_react_default` 等 | `global_chat_default` / `global_task_default`（及租户 type） |
| `resolveReactTools` | `resolveChatTools`（调用点全量替换；**勿**留 deprecated 长期） |
| `resolvePlanWorkflowTools` / `Critical` | `resolveTaskTools` / `resolveTaskCriticalTools` |
| Catalog `kind=react-prompt` / `reactPromptId` | **删除**；业务文案 → Skill overlay；码 → Lab |

**读双写策略（K0）**：`fromPath("react-default"|"chat")` → `CHAT_DEFAULT`；`toolIds`：优先新 set，若成员空则并入旧 set 成员（同一 tool 去重）。**写出 / Admin 新 API** 只接受 `chat`|`task`。

**装默认 Toolkit（运行时）**：

```
conversation.kind == chat → resolveChatTools(tenant)
conversation.kind == task → resolveTaskTools(tenant)
不按 executionMode 分支
```

---

### Task 1: K0 — `ToolSetKind` + DDL 种子 + Runtime 双读

**Files:**
- Modify: `tool-service/src/main/java/com/sunshine/tool/admin/ToolSetKind.java`
- Modify: `docker/mysql/init/16-sunshine-tool-service.sql`
- Modify: `tool-service/.../ToolSetMemberService.java`（`toolIds` / ensure-set 逻辑）
- Modify: `tool-service/.../ToolSetRuntimeController.java`（path 仍 `{kind}`，文档化新码）
- Test: `tool-service/src/test/java/com/sunshine/tool/admin/ToolSetKindMigrationTest.java`（新建）
- Test: 更新 `ToolSetMemberServiceTest` 夹具 id

**Interfaces:**
- Produces: `ToolSetKind.CHAT_DEFAULT` / `TASK_DEFAULT`；`fromPath("chat"|"task"|"react-default"|"plan-workflow")`
- Produces: 新全局 set id；`toolIds(kind, tenant)` 双读旧集
- Consumes: 无

- [x] **Step 1: 写失败单测**

```java
@Test
void fromPath_acceptsNewAndLegacy() {
    assertThat(ToolSetKind.fromPath("chat")).isEqualTo(ToolSetKind.CHAT_DEFAULT);
    assertThat(ToolSetKind.fromPath("react-default")).isEqualTo(ToolSetKind.CHAT_DEFAULT);
    assertThat(ToolSetKind.fromPath("task")).isEqualTo(ToolSetKind.TASK_DEFAULT);
    assertThat(ToolSetKind.fromPath("plan-workflow")).isEqualTo(ToolSetKind.TASK_DEFAULT);
}

@Test
void pathWire_isChatOrTaskOnly() {
    assertThat(ToolSetKind.CHAT_DEFAULT.path()).isEqualTo("chat");
    assertThat(ToolSetKind.TASK_DEFAULT.path()).isEqualTo("task");
}
```

- [x] **Step 2: 跑测确认失败**

Run: `mvn test -pl tool-service -Dtest=ToolSetKindMigrationTest -q`  
Expected: FAIL（尚无 CHAT_DEFAULT / path()）

- [x] **Step 3: 改枚举 + DDL 种子**

- 枚举加 `path()` 返回 `chat`/`task`；保留旧 globalType 字段值**或**改为新 type 并在 MemberService 双查旧 type/id  
- SQL：`INSERT` `global-chat-default` / `global-task-default`；**保留**旧两行供双读（或同文件注释说明已有环境靠服务双读）  
- `tool_set_member.critical` 注释改为「仅 task 默认集有效」

- [x] **Step 4: MemberService 双读**

`toolIds`：加载新 set 成员；若空（或显式 merge 策略）再加载旧 id 成员并去重。Admin `pageMembers` / add / remove：**只**操作新 set（首次写入时 ensure 新 set 存在）。

- [x] **Step 5: 跑测通过 + 更新旧测试夹具**

Run: `mvn test -pl tool-service -Dtest=ToolSetKindMigrationTest,ToolSetMemberServiceTest -q`  
Expected: PASS

- [x] **Step 6: Commit**

```bash
git add tool-service docker/mysql/init/16-sunshine-tool-service.sql
git commit -m "$(cat <<'EOF'
feat(tool-service): migrate default toolsets to chat|task with legacy read

EOF
)"
```

---

### Task 2: K0 — Orchestrator Resolver + Client + 按会话 kind 装集

**Files:**
- Modify: `orchestrator/.../client/ToolManagerClient.java`
- Modify: `orchestrator/.../catalog/ToolSetResolver.java`
- Modify: `DynamicToolkitFactory.java` · `ReactExecutor` / runtime 入口 · `HarnessPlanner.java` · `PlannerHarnessLoop.java`（及仍调用旧名的 PlanWorkflow* **可暂双名转发到 task**，阶段 D 再删调用方）
- Modify: BFF `ToolManagerAdminClient` / Admin path 若硬编码旧 kind
- Test: `ToolSetResolverTest` · 更新 harness / DynamicToolkit 相关 mock

**Interfaces:**
- Produces: `resolveChatTools(tenant)` / `resolveTaskTools(tenant)` / `resolveTaskCriticalTools(tenant)`
- Produces: `ToolManagerClient.fetchChatDefault` / `fetchTaskDefault` / `fetchTaskCritical`（内部 path `chat`|`task`）
- Consumes: Task1 Runtime API
- **装集入口**须能读到 `conversation.kind`（已有则透传；缺省按 `chat`）

- [x] **Step 1: 改 Resolver 测试为新方法名 + kind 分支**

```java
@Test
void resolveChatTools_intersectsEnabledPool() { /* 原 resolveReactTools 断言 */ }

@Test
void resolveTaskTools_intersectsEnabledPool() { /* 原 plan-workflow */ }
```

并加工厂/执行路径测试：`kind=task` → 调 `resolveTaskTools`，**不**调 chat。

- [x] **Step 2: 跑测确认失败**

Run: `mvn test -pl orchestrator -Dtest=ToolSetResolverTest -q`  
Expected: FAIL（方法未改名）

- [x] **Step 3: 实现 Client + Resolver + 调用点**

全量替换 `resolveReactTools` → 按 kind 分支；**禁止** `executionMode==PRO` 再选另一套默认集。Harness / ReAct MAIN 均：`chat→chat集`，`task→task集`。

- [x] **Step 4: 跑相关测试**

Run: `mvn test -pl orchestrator -Dtest=ToolSetResolverTest,DynamicToolkitFactoryTest,HarnessPlannerTest,PlannerHarnessLoopTest -q`  
Expected: PASS

- [x] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(orchestrator): resolve default tools by conversation kind

EOF
)"
```

---

### Task 3: K0 — 前端工具集 Tab `chat | task`

**Files:**
- Modify: `sunshine-ui/src/api/tools.ts`（`ToolSetKindPath = 'chat' | 'task'`；读路径若需兼容可内部 map，**请求只发新码**）
- Modify: `sunshine-ui/src/components/tools/ToolsetTabPanel.vue`
- Modify: `ToolSetAddModal.vue`（若传 kind）
- Modify: BFF Admin 代理 path（与 tool-service 一致）
- Test: 若有 vitest 覆盖 tools API 则更新；否则手动点选验收清单写入 commit 说明

**Interfaces:**
- Consumes: Task1 Admin `.../sets/chat|task/...`
- Produces: UI 子 Tab 文案 **chat / task**（可用中文「对话默认」「任务默认」，path 仍英文）

- [x] **Step 1: 改 `ToolSetKindPath` 与 `subTab`**

`subTab: 'chat' | 'task'`；critical 开关仅 `task` 显示（原 plan-workflow 行为）。

- [x] **Step 2: 类型检查 / 构建**

Run: `cd sunshine-ui && npx vue-tsc --noEmit`（或项目惯用检查）  
Expected: 无 ToolSetKindPath 相关错误

- [x] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): toolset admin tabs chat|task

EOF
)"
```

---

### Task 4: K1 — Skill / Agent / Workflow 元数据 `kind` + 召回过滤

**Files:**
- Modify: `docker/mysql/init/19-sunshine-resource.sql` — `skill_definition` / `agent_definition` 加  
  `kind VARCHAR(16) NOT NULL DEFAULT 'all'`（`chat|task|all`）  
  （`biz_scene` 列可本 Task 先加可空，校验放 K2）
- Workflow：对应 init SQL / entity（库名以现有 workflow-manager 为准）加同名 `kind` 默认 `all`
- Modify: resource-manager `SkillDefinitionEntity` / `AgentDefinitionEntity` + Admin DTO + Catalog DTO/Index
- Modify: orchestrator `SkillCatalogIndexEntry` / Agent 索引 + `SkillCatalogService` / discovery 过滤
- 意图链：在 **候选构建**（L2 目录 / sanitize 前）过滤：`entry.kind == 'all' || entry.kind == sessionKind`
- Admin UI：Skill/Agent 编辑表单增加 `kind` 选择（默认 all）
- Test: `SkillCatalogServiceTest` / 新建 `ResourceKindFilterTest`

**Interfaces:**
- Produces: Catalog wire 字段 `kind`
- Produces: `filterByConversationKind(entries, sessionKind)`（或等价内联）
- Consumes: 会话 `kind`（RoutingContext / Conversation 已有）

- [x] **Step 1: 写过滤单测**

```java
@Test
void filter_keepsAllAndMatchingKind() {
    assertThat(ResourceKindFilter.retain(List.of(
            entry("a", "chat"), entry("b", "task"), entry("c", "all")), "chat"))
        .extracting(E::id).containsExactly("a", "c");
}
```

- [x] **Step 2: 跑测 FAIL → 实现 DDL + entity + filter 接线 → PASS**

Run: `mvn test -pl orchestrator -Dtest=ResourceKindFilterTest,SkillCatalogServiceTest -q`  
另：`mvn test -pl resource-manager -Dtest=…`（Admin 序列化若有测）

- [x] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(catalog): filter skill/agent/workflow by conversation kind

EOF
)"
```

---

### Task 5: K2 — 业务场景 Lab（码表 + Policy）DDL 与 Admin API

**Files:**
- Modify: `19-sunshine-resource.sql`  
  - `CREATE TABLE biz_scene_definition`（`biz_scene` PK 或 `(tenant_id,biz_scene)`、`display_name`、`description`、`status` active|retired、`tenant_id`）  
  - `CREATE TABLE biz_scene_policy`（按 [authority §4.2](../specs/2026-08-13-business-context-authority-design.md)）
- Create: resource-manager `BizSceneDefinition*` / `BizScenePolicy*` entity · repo · AdminController · Service  
  - CRUD 码表；Policy 按码精确匹配；**禁止**无 Lab 码创建 Policy  
  - `retired` 码：不可绑定到**新**资源保存
- Create: 种子 0～若干演示码（可选，与原 react-prompt 场景同名对齐：如 `compliance-review`、`expense-assist`、`policy-qa`、`travel-budget` — **无** `react-prompt.` 前缀）
- Test: `BizSceneAdminServiceTest`（非法码拒绝；retired 不可新绑）

**Interfaces:**
- Produces: `GET/POST/PATCH /api/biz-scenes`（路径以 resource-manager 惯例为准，经 BFF 聚合）  
- Produces: `isActiveBizScene(tenant, code) → boolean` 供 Skill/Agent 保存校验
- Consumes: 无

- [x] **Step 1: 写 Admin 单测（创建 / retired 拒绝新绑）**
- [x] **Step 2: FAIL → DDL + Service + Controller → PASS**
- [x] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(resource-manager): biz_scene lab definitions and policies

EOF
)"
```

---

### Task 6: K2 — Skill/Agent 打标 `biz_scene` + 运行时解析挂钩

**Files:**
- Modify: Skill/Agent Admin 保存：非空 `biz_scene` 必须 `isActiveBizScene`  
- Modify: Catalog 条目带出 `biz_scene`  
- Create: `orchestrator/.../biz/BizSceneResolver.java`（或 context 包）实现 authority §2.1：  
  agent 优先非空 → 否则 skillIds 第一非空 → 否则 null；若码 retired/未知 → **视为无效**，返回 null + audit log  
- 接线：资源召回之后调用一次，结果放入 RoutingContext / AssembleRequest 扩展字段（若 Assemble 尚未消费，至少结构化日志 + 单测；**不要**假造 Prompt 注入）
- UI：Skill/Agent 表单 `biz_scene` 下拉（仅 active Lab 码 + 空）
- Test: `BizSceneResolverTest`

**Interfaces:**
- Produces: `Optional<String> resolve(agentMetas, skillMetas)`  
- Consumes: Task5 Lab；K1 Catalog 元数据  
- **不做**：完整 Policy∥任务板∥偏好装载（标 `// 权威层 P3：business-context 后续`）

- [x] **Step 1: Resolver 单测（agent 优先、空跳过、retired→null）**
- [x] **Step 2: 实现 + 召回后接线 → PASS**
- [x] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat: bind biz_scene on skills/agents and resolve after recall

EOF
)"
```

---

### Task 7: K2 — 侧栏「业务场景」Lab UI

**Files:**
- Create: `sunshine-ui/src/views/BizScenesView.vue`（+ 小组件：码表表、Policy 编辑）
- Create: `sunshine-ui/src/api/bizScenes.ts`
- Modify: `MainLayout.vue` 菜单：与「提示词」平级插入 **业务场景**（`key: 'biz-scenes'`）
- Modify: `router` 注册路由
- 样式：`--sun-black`、边框分区；**禁止**冗余说明文案

**Interfaces:**
- Consumes: Task5 Admin API

- [x] **Step 1: 路由 + 空页可打开**
- [x] **Step 2: 码表 CRUD + Policy 邻接 Tab**
- [x] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): biz-scene lab sidebar page

EOF
)"
```

---

### Task 8: K3 — 退役 `react-prompt` / `reactPromptId`

**Files:**
- SQL：`19-sunshine-resource.sql` 删除（或不再 INSERT）`react-prompt.*` 种子；路由规则去掉 `params.reactPromptId`，改为绑 skillId 或仅 mode（**迁移表**见下）
- 内容迁移：将有价值的 `content_text` 写入对应 Skill `system_overlay`（若尚无 skill，新建 skill + Lab 码打标）— 在计划执行时按下列对照操作，勿留「兼容读 react-prompt」代码
- Modify: `IntentRouter` / `ForcedExecutionRouter` / `ExecutionPlanParser` — 删除 `PARAM_REACT_PROMPT` / 解析与透传
- Modify: `PromptComposer` / `ReActAgentRuntime` — 去掉 `reactPromptId` 叠加层
- Modify: UI `PromptsView` 删「React 提示词」Tab；`RoutingRuleEditor` 删 React 提示词字段；`prompts.ts` 类型去掉 `react-prompt`
- 更新全部引用单测 / golden / fixtures
- Grep 门禁：业务源码 `react-prompt` / `reactPromptId` 为零（允许本 plan 文档与 archive spec 叙述）

**迁移表（执行时勾选）：**

| 旧 react-prompt | 建议 |
|-----------------|------|
| `react-prompt.compliance-review` | Lab `compliance-review` + Skill overlay（合规审查）+ 规则改 skill / 仅 fast |
| `react-prompt.expense-assist` | Lab `expense-assist` + Skill |
| `react-prompt.policy-qa` | Lab `policy-qa` + Skill |
| `react-prompt.travel-budget` | Lab `travel-budget` + Skill |
| `react-prompt.demo-scenario` | **删除**（兜底改靠 mode-overlay.react） |

- [x] **Step 1: 列出 grep 命中并改测试为「无该字段」**
- [x] **Step 2: 删协议 + Composer + UI + SQL → 测试全绿**
- [x] **Step 3: 仓库 grep 确认**

Run: `rg -n 'reactPromptId|react-prompt' --glob '!docs/**' --glob '!**/plans/**' --glob '!**/specs/**'`  
Expected: 无业务命中（或仅历史注释待删）

- [x] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat: retire react-prompt catalog and reactPromptId routing

EOF
)"
```

---

### Task 9: K4 — 上下文管理页 chat | task 分栏

**Files:**
- Modify: `sunshine-ui/src/views/ContextView.vue`
- Modify: `ContextConversationList.vue` / `useContextPage.ts` — 列表强过滤或分栏 `kind=chat|task`
- Modify: Tab 可见性：  
  - **chat**：L1 会话快照 · L2 用户状态 · L3 历史索引  
  - **task**：L1（task 规则）· W0 工作区（替换「用户 L2」展示；**不**把用户 L2 当权威）· T0/H1 + task-L3 说明区（只读；闸门 SSOT 仍在 task-scene，本 Task 只做载体切换）
- 禁止用 `--sun-surface` 铺灰底；禁止大段解释文案

**Interfaces:**
- Consumes: 会话列表 API 已有 `kind` 字段（若无则补 BFF/orch 列表投影）

- [x] **Step 1: 列表分栏 + 选中会话驱动 Tab**
- [x] **Step 2: task 选中时隐藏用户 L2 权威展示**
- [x] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): context admin split by conversation kind

EOF
)"
```

---

### Task 10: Live 冒烟 + 文档状态同步

**Files:**
- Create: `scripts/verify_kind_biz_scene_live.py`  
  - V0：Admin/Runtime `sets/chat` tool-ids 非空（或双读后与旧集一致）  
  - V1：造/用 `kind=chat` 资源与 `kind=task` 资源，task 会话意图候选不含 chat-only（可用 dry-run / catalog 调试接口；若无则文档化用单测代替并标 skip）  
  - V2：Lab 创建码 → Skill 绑码成功；绑 retired 失败  
- Modify: spec `2026-08-13-kind-biz-scene-catalog-design.md` 状态 → ✅ 已实现（本 Task 全绿后）  
- Modify: `docs/superpowers/specs/README.md` 依赖表 / 第三波备注  
- Modify: `CLAUDE.md` / `implementation-plan.md` 若有对应缺口行

- [x] **Step 1: 写脚本骨架 + 本地可跑检查**
- [x] **Step 2: 重启相关服务后跑 Live（tool-service / resource-manager / orchestrator）**

Run: `python scripts/verify_kind_biz_scene_live.py`  
Expected: V0–V2 PASS（或明确 SKIP 原因）

- [x] **Step 3: 文档勾选 K0–K4 ✅ + Commit**

```bash
git commit -m "$(cat <<'EOF'
docs: mark kind-biz-scene-catalog done and add live verify

EOF
)"
```

---

## 风险与回滚

| 风险 | 缓解 |
|------|------|
| 已有租户工具集成员仍在旧 set id | Runtime **双读**至数据迁完；可另加一次性 `scripts/` 复制成员（勿留临时 JS） |
| Harness 仍调 `resolveReactTools` 导致 task 会话工具错集 | Task2 强制按 `conversation.kind`；单测锁死 |
| 权威层未实现导致「有 scene 无感」 | K2 只保证解析 + 校验 + 挂钩；装载属后续 plan，勿在 Prompt 里硬编码场景文案 |
| 路由规则删 reactPromptId 后召回变差 | 迁移表落到 Skill + Lab；用 routing golden / dry-run 回归 |
| 上下文 Tab 误展示用户 L2 给 task | K4 验收：task 会话 UI 断言无「用户 L2 权威」 |

## 验收对照（spec §9）

| 阶段 | 验收 |
|------|------|
| K0 | Tab 为 chat\|task；`kind=chat` 会话只装 chat 集 |
| K1 | task 会话召不回 `kind=chat` 资源 |
| K2 | 无 Lab 码无法保存非法 scene；有 scene 才进入权威挂钩（空则跳过） |
| K3 | grep 零业务 `react-prompt` / `reactPromptId` |
| K4 | task 会话不展示用户 L2 为权威 |

## 执行方式

推荐：`superpowers:subagent-driven-development`，按 Task 1→10 顺序；每 Task 独立 commit + 评审门。  
分支：现有 `feature/multi-agent-unified`（与 H-5/H-6 同波）即可，勿另开无关大重构。
