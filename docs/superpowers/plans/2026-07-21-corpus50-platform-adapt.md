# corpus-50 平台适配 + 用户隔离工具 + Mock 页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清除旧 demo 遗留（不做兼容），将 Skill/Workflow/Expert/Prompt/验收对齐 corpus-50；SDK 工具按 `x-user-id` 隔离真实参数数据；提供 `/mock-data` 联调页。

**Architecture:** invoke 链路透传身份 → biz 服务 `TenantUserStore` 按用户存种子数据 → 新工具短名替换旧 finance 三工具；新建 `hr-biz-service`；前端 Mock 页调 Admin API；方案 A 改 SQL/docs SSOT + `sync_corpus50_platform.py` 同步 Live。

**Tech Stack:** JDK 21 · Spring Boot Web · sunshine-tool-sdk · Nacos · Vue3/Naive UI · MySQL init · Python 运维脚本

**设计 SSOT:** [2026-07-21-corpus50-platform-adapt-design.md](../specs/2026-07-21-corpus50-platform-adapt-design.md)

**硬约束:** §0.1 — 删除旧工具名/全局 MOCK/旧验收句；禁止 alias、双 ID、无 user 回退全局数据。

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 删除 |
|------|------|------|------|
| **SDK 身份** | `sunshine-tool-sdk/.../ToolInvocationContext.java` | `SunshineToolController`；`SdkInvokeExecutor`；tool-manager invoke API；`ToolManagerClient`；`CatalogRemoteAgentTool`；`ToolNodeHandler` | — |
| **Finance 重写** | `finance/.../store/*`；`Expense*`；新 `FinanceSunshineTools`；`mock/seed-users.json`；Mock Admin | 控制器/服务 | 旧 `FinanceMessageService` 静态 MOCK；旧三工具方法 |
| **OA 重写** | `oa/.../store` 或复用模式 | `OaSunshineTools`；`OaTaskService` | 全局静态 MOCK |
| **HR 新服务** | `hr-biz-service/**`（镜像 finance-service 骨架） | `start.py`、父 POM、`16-*.sql` sdk_application | — |
| **前端** | `MockDataView.vue`、`api/mockData.ts` | `router`、`MainLayout` | — |
| **平台适配** | `sync_corpus50_platform.py` | `13/15/17` SQL、skills、Chat 空态、golden-set、verify_*、RAG rewrite seed | 旧工具 Catalog ID 引用 |
| **验收** | `verify_user_isolated_tools_live.py` | CLAUDE/README 工具表 | grep 旧名应为 0 |

---

## Task 1: ToolInvocationContext + Controller 读头

**Files:**
- Create: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/context/ToolInvocationContext.java`
- Modify: `common/sunshine-tool-sdk/src/main/java/com/sunshine/tools/sdk/web/SunshineToolController.java`
- Test: `common/sunshine-tool-sdk/src/test/java/com/sunshine/tools/sdk/context/ToolInvocationContextTest.java`

- [ ] **Step 1: 写失败单测（无上下文时 requireUserId 抛错）**

```java
@Test
void requireUserId_withoutContext_throws() {
    ToolInvocationContext.clear();
    assertThatThrownBy(ToolInvocationContext::requireUserId)
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: 实现 Context（ThreadLocal）**

```java
public final class ToolInvocationContext {
    private static final ThreadLocal<Identity> HOLDER = new ThreadLocal<>();
    public record Identity(String tenantId, String userId) {}
    public static void set(String tenantId, String userId) { /* blank → default tenant; user 可空 */ }
    public static void clear() { HOLDER.remove(); }
    public static String requireUserId() { /* 空则 IllegalStateException */ }
    public static String tenantIdOrDefault() { /* default */ }
}
```

- [ ] **Step 3: Controller 注入头并 try/finally clear**

```java
@PostMapping("/invoke/{toolId}")
public SdkToolInvokeResponse invoke(
        @PathVariable String toolId,
        @RequestBody Map<String, String> params,
        @RequestHeader(value = "x-user-id", required = false) String userId,
        @RequestHeader(value = "x-tenant-id", required = false) String tenantId) {
    ToolInvocationContext.set(tenantId, userId);
    try {
        return registry.invoke(toolId, params);
    } finally {
        ToolInvocationContext.clear();
    }
}
```

- [ ] **Step 4: 单测通过后 commit**

```bash
mvn -pl common/sunshine-tool-sdk -am test -Dtest=ToolInvocationContextTest,SunshineToolControllerTest
git add common/sunshine-tool-sdk && git commit -m "feat(tool-sdk): ToolInvocationContext from x-user-id headers"
```

---

## Task 2: tool-manager / orchestrator 透传身份

**Files:**
- Modify: `tool-manager/.../SdkInvokeExecutor.java` — `.header("x-user-id", ...)`
- Modify: tool-manager invoke 入口（`ToolInvokeController` / service）接收并下传 headers
- Modify: `orchestrator/.../ToolManagerClient.java` — `invokeMono(name, params, userId, tenantId)`
- Modify: `CatalogRemoteAgentTool.java`、`ToolNodeHandler.java` — 传入当前 user/tenant
- Test: `SdkInvokeExecutorTest`、`ToolNodeHandlerTest`（旧 `list_finance_messages` 断言一并改为新工具名，见 Task 4）

- [ ] **Step 1: SdkInvokeExecutor 增加 Identity 参数并写头**

```java
public String invoke(ToolDefinitionEntity tool, Map<String, String> params, String userId, String tenantId) {
    // ...
    var spec = webClient.post().uri(url);
    if (StringUtils.hasText(userId)) spec = spec.header("x-user-id", userId);
    if (StringUtils.hasText(tenantId)) spec = spec.header("x-tenant-id", tenantId);
    // bodyValue(body)...
}
```

- [ ] **Step 2: ToolManagerClient 签名扩展（无兼容重载：直接改调用方）**

```java
public Mono<String> invokeMono(String name, Map<String, String> params, String userId, String tenantId) {
    return webClient.post()
            .uri("/api/tools/invoke")
            .header("x-user-id", userId != null ? userId : "")
            .header("x-tenant-id", tenantId != null ? tenantId : "default")
            // ...
}
```

- [ ] **Step 3: CatalogRemoteAgentTool / ToolNodeHandler 从 Agent/Execution 上下文取 userId**

（与现有 `AgentRunRequest.userId` / chat session user 字段对齐；缺 user 时写工具应失败。）

- [ ] **Step 4: 编译相关模块 + commit**

```bash
mvn -pl tool-manager,orchestrator -am test -DskipTests=false -Dtest=SdkInvokeExecutorTest,ToolNodeHandlerTest
git commit -m "feat(tools): propagate x-user-id through invoke chain"
```

---

## Task 3: Finance TenantUserStore + 种子 JSON

**Files:**
- Create: `finance-service/src/main/resources/mock/seed-users.json`
- Create: `finance-service/src/main/java/com/sunshine/finance/store/TenantUserStore.java`
- Create: `.../model/ExpenseRecord.java`、`FinanceInboxItem.java`
- Delete: 全局静态 MOCK 实现（`FinanceMessageService` 旧逻辑）
- Test: `TenantUserStoreTest`

- [ ] **Step 1: seed-users.json 结构（u-alice/u-bob/u-carol）**

```json
{
  "default": {
    "u-alice": {
      "expenses": [
        {"id": "exp-a1", "category": "市内交通", "amount": 86.5, "status": "pending", "occurredOn": "2026-07-18", "remark": "客户拜访网约车"}
      ],
      "inbox": [
        {"id": "inbox-a1", "title": "报销单待补充发票", "status": "pending", "amount": 86.5}
      ]
    },
    "u-bob": { "expenses": [], "inbox": [] },
    "u-carol": { "expenses": [], "inbox": [{"id": "inbox-c1", "title": "待审大额报销", "status": "pending", "amount": 8800}] }
  }
}
```

- [ ] **Step 2: Store API**

```java
List<ExpenseRecord> listExpenses(String tenantId, String userId, String status);
Optional<ExpenseRecord> findExpense(String tenantId, String userId, String expenseId);
ExpenseRecord submitExpense(...);
void reset(String tenantId); // 从 classpath 重载
```

- [ ] **Step 3: 单测 alice/bob 列表不同；跨用户 find 为空**

- [ ] **Step 4: commit**

```bash
git commit -m "feat(finance): TenantUserStore with per-user seed data"
```

---

## Task 4: 重写 FinanceSunshineTools（删除旧三工具）

**Files:**
- Rewrite: `finance-service/.../FinanceSunshineTools.java`
- Update/Delete: `FinanceSunshineToolsTest.java`、`FinanceMessageController*`（REST 若保留则同样按用户；否则改为 mock admin）
- Update: orchestrator 单测中旧 Catalog ID

- [ ] **Step 1: 删除** `list_finance_messages` / `get_finance_message_detail` / `summarize_finance_by_status`

- [ ] **Step 2: 实现新工具（身份来自 Context）**

```java
@SunshineTool(id = "list_my_expenses", displayName = "查询我的报销单", ...)
public String listMyExpenses(@ToolParam(value = "status", required = false) String status) {
    String userId = ToolInvocationContext.requireUserId();
    String tenantId = ToolInvocationContext.tenantIdOrDefault();
    // store.listExpenses(...)
}
// get_expense_detail / submit_expense / list_my_finance_inbox / get_finance_inbox_item / summarize_my_expenses
```

- [ ] **Step 3: grep 确认模块内无旧短名**

```bash
rg "list_finance_messages|summarize_finance_by_status" finance-service orchestrator
# 期望：无业务引用（仅本 plan/历史 commit 除外）
```

- [ ] **Step 4: commit**

```bash
git commit -m "feat(finance): replace demo tools with user-scoped expense APIs"
```

---

## Task 5: OA 按用户重写

**Files:**
- Modify: `oa-service/.../OaTaskService.java`、`OaSunshineTools.java`
- Add: seed 中带 `assigneeUserId`
- Test: alice 看不见 bob 的待办；approve 越权失败

- [ ] **Step 1–4:** 同 Finance 模式；保留短名 `list_oa_tasks` / `approve_oa_task` 但数据模型必须含负责人且过滤；**删除**无用户字段的静态 5 条全局列表。

```bash
git commit -m "feat(oa): user-scoped tasks; remove global mock list"
```

---

## Task 6: 新建 hr-biz-service（sunshine-hr）

**Files:**
- Create module: `hr-biz-service/`（拷贝 finance-service 骨架：pom、Application、application.yml、Nacos metadata `sunshine.tool-app-id: sunshine-hr`、port **8720**）
- Create: `HrSunshineTools.java` — `get_leave_balance`、`list_leave_requests`、`submit_leave_request`、`get_attendance_month`
- Create: `mock/seed-users.json`（青松假余额等与 corpus 锚点一致）
- Modify: 根 `pom.xml` modules、`scripts/start.py` 注册 `hr`、`docker/mysql/init/16-sunshine-tool-manager.sql` 增加 sdk_application
- Create: `docs/nacos/sunshine-hr.yaml`（若项目惯例需要）+ sync_nacos

- [ ] **Step 1: 模块可启动且 `/sunshine/tools/catalog` 含 4 个工具**

- [ ] **Step 2: 单测余额/请假隔离**

- [ ] **Step 3: commit**

```bash
git commit -m "feat(hr): add sunshine-hr tool app with leave/attendance APIs"
```

---

## Task 7: Mock Admin API（三服务）

**Files:**
- Create: 各服务 `.../controller/MockAdminController.java`
  - `GET /api/mock/users`
  - `GET /api/mock/{domain}?userId=`
  - `POST /api/mock/reset`
  - `PATCH /api/mock/expenses/{id}`（finance）等
- Header: `X-Admin-Token` 或与现有 RAG admin 同模式；开发态可读配置 token

- [ ] **Step 1–3:** 实现 + 用 curl 验证 reset 恢复种子

```bash
git commit -m "feat(mock): admin APIs to inspect/reset per-user enterprise data"
```

---

## Task 8: 前端 `/mock-data` 页

**Files:**
- Create: `sunshine-ui/src/views/MockDataView.vue`
- Create: `sunshine-ui/src/api/mockData.ts`
- Modify: `sunshine-ui/src/router/index.ts`、`MainLayout.vue`（侧栏「业务数据」）
- Modify: Vite proxy（若需直连 :8710/:8700/:8720）

- [ ] **Step 1: 路由 + 空壳页（`--sun-black` + 边框，左用户/域，右表格）**

- [ ] **Step 2: 拉取 alice/bob 数据切换；重置按钮**

- [ ] **Step 3: `npx vue-tsc -b` 通过 + commit**

```bash
git commit -m "feat(ui): add /mock-data page for enterprise mock inspection"
```

---

## Task 9: Workflow / Prompt / Expert SQL — 去旧工具与 demo 句

**Files:**
- Modify: `docker/mysql/init/13-sunshine-workflow-manager.sql`
  - 所有 `sdk__sunshine-finance__list_finance_messages` → `sdk__sunshine-finance__list_my_expenses`（或 inbox 视节点语义）
  - `summarize_finance_by_status` → `summarize_my_expenses`
  - knowledge-* description/examples/answer 举例 → §5.3 锚点句
- Modify: `docker/mysql/init/17-sunshine-prompt-manager.sql` — 域词与 examples
- Modify: `docker/mysql/init/15-sunshine-expert-manager.sql` — 提示对齐；skill_link 有效
- Modify: `rag-service/.../config-seed.json` + `14-*.sql` rewrite/hyde 域词

- [ ] **Step 1: rg 确认 13/17 无 `list_finance_messages`、无「年假可以请几天」**

- [ ] **Step 2: commit**

```bash
git commit -m "chore(seed): point workflows/prompts at corpus-50 and new tool IDs"
```

---

## Task 10: Skills 文档包 + Chat 空态 + golden / verify 脚本

**Files:**
- Modify: `docs/skills/policy-review/SKILL.md`、`finance-analysis`、`knowledge-brief`、`compliance*`（若有）
- Modify: `sunshine-ui/src/views/ChatView.vue` EMPTY_HINTS
- Modify: `sunshine-ui/src/views/KnowledgeView.vue` placeholder
- Modify: `docs/routing/routing-golden-set.md`
- Modify: `scripts/verify_workflow_studio_live.py`、`verify_exclusive_gateway_live.py`、`verify_loop_live.py`、`verify_rag_studio.py`、`phase2_agent_demo.py`、`verify_tenant_live.py` 等 — 去掉 leave-policy-v1 / 年假句

- [ ] **Step 1–3:** 改完后 `rg "leave-policy-v1|list_finance_messages|年假可以请几天" scripts sunshine-ui docs/routing docs/skills docker/mysql/init` 仅允许出现在本 plan/spec 历史说明（业务路径为 0）

```bash
git commit -m "chore: purge demo copy from skills, chat hints, and live scripts"
```

---

## Task 11: sync_corpus50_platform.py + 工具启用

**Files:**
- Create: `scripts/sync_corpus50_platform.py`
  - MySQL：执行对 workflow_version / prompt / expert 的 UPDATE（或 source 片段）
  - 调用 tool-manager sync + 启用新工具 + 写入 tool_set_member
  - 删除/禁用旧 tool_definition（`list_finance_messages` 等）
  - 可选：skill zip 上传提示
- Modify: `CLAUDE.md` / `README.md` 命令表

- [ ] **Step 1: 脚本 `--dry-run` 打印将改对象**

- [ ] **Step 2: Live 执行 sync；重启 finance/oa/hr/tool-manager/orchestrator/workflow-manager/prompt-manager**

```bash
python3 scripts/sync_nacos.py   # 若新增 sunshine-hr.yaml
python3 scripts/start.py --restart finance,oa,hr,tool,orchestrator,workflow,prompt
python3 scripts/sync_corpus50_platform.py
```

- [ ] **Step 3: commit 脚本**

---

## Task 12: Live 验收

**Files:**
- Create: `scripts/verify_user_isolated_tools_live.py`（G1/G2：不同 user 头调 invoke）
- Run: `verify_workflow_studio_live.py`、`rag_eval.py --suite-key sunshine-smoke --gate`、手动 `/mock-data`

- [ ] **Step 1: 隔离工具 Live PASS**

- [ ] **Step 2: knowledge-* Live PASS**

- [ ] **Step 3: G8 grep 清零检查写入报告**

```bash
rg -n "list_finance_messages|leave-policy-v1" \
  finance-service oa-service hr-biz-service orchestrator tool-manager \
  sunshine-ui/src scripts docker/mysql/init docs/skills docs/routing \
  --glob '!**/plans/**' --glob '!**/specs/**'
# 期望：无匹配
```

- [ ] **Step 4: 最终 commit（若有修复）+ 勾选本 plan 全部 Task**

---

## 执行建议

本计划跨 **SDK 透传 / 三业务服务 / 前端 / 种子与 Live sync**，建议用 **subagent-driven-development** 按 Task 1→12 顺序推进；每 Task 提交一次，Task 12 前不得宣称完成。

**不要**在中途恢复旧工具名或全局 MOCK「临时打通」。
