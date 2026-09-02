# Task List Memory M1 — KV Memory 统一 + `todo` 类 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 把 chat 的 L2 与 task 的 W0 统一为一张 **KV Memory**（`user_context_state` 加 `scope=user|workspace` 列），并新增 **`kind=todo`**（未完成任务清单）：chat 会话沉淀**用户** todo、task 会话沉淀**工作区** todo，新会话按 kind 正确注入（chat→scope=user、task→scope=workspace），完成/取消即 `void`。同时补齐依赖的最小 **KV 读写闸门**（P1/P2 子集）——读侧按 `kind` 选 scope、写侧按 `conversation.kind` 分流抽取，杜绝 chat/task 跨场景污染。

**Architecture:** 复用 `user_context_state` 单表（加 `scope`/`workspace_id`/`background` 列，零新表）。`L2StateStore` 读写按 scope 维度路由（scope=user 以 `(userId, tenantId)` 为键，scope=workspace 以 `(tenantId, workspaceId)` 为键）；`L2ExtractService` 改用参数化 Catalog `context.memory.extract`（system 带 scope 说明，kind 含 `todo`，叠加 v22 门禁：key 场景化 `{domain}.{facet}` / background 必填 / value 自解释 / 宁缺毋滥）；`L2ConflictMerger.Candidate` 扩展 `background`/`status`；`L2StateStore.upsert` 支持 todo 的 done/void 显式失效。读写闸门：`AssembleRequest` 加 `kind`/`workspaceId`，`ContextAssembler` 按 kind 读 scope；`ContextWritePath` 按 `conversation.kind` 分流抽取（task 跳过 scope=user、执行 scope=workspace；chat 执行 scope=user）；`L1Compressor` 压缩读 L2 同步按 kind。

**Tech Stack:** Java 17 / Spring Data JPA / JUnit5 + Mockito + AssertJ · MySQL DDL + `scripts/*.sql` 迁移 · Python3 live 脚本（requests）

**Spec:** [task-list-memory-unification-design](../specs/archive/2026-08-14-task-list-memory-unification-design.md) §3 核心设计 / §5.3 KV Memory todo 类 / §6 沉淀通道 / §9 M1 / §10 验收 · [task-scene-context](../specs/archive/2026-08-01-task-scene-context-design.md) §2.1 读写闸门（最小子集） · [unified-context-compression](../specs/2026-07-31-unified-context-compression-design.md) §6.0/§6.3.3–6.3.5（v20/v22 门禁）

## Global Constraints

- **单表统一、零新表**：KV Memory = `user_context_state` 加列（`scope`/`workspace_id`/`background`）；禁止新建 `workspace_context_state`
- **scope 唯一性**：`(scope, tenant, user_id|workspace_id, kind, state_key)` 至多一条 active；旧 `(user_id, tenant_id, kind, state_key, status)` 索引语义扩为带 scope
- **不双写**：执行态（TaskList/H1）仍唯一权威；KV Memory 只做**沉淀副本**，单向（执行态→KV），KV 从不写回执行态
- **todo 只收长留项**：v22 门禁——仅「用户主动提出、跨会话仍有效、未完成」入库；会话计划/单次迭代**禁止**进（五层 §6.3.5 反例）；`key` 必须 `{domain}.{facet}`、`background` 必填、`value` 自解释命题短句（禁裸布尔/单 token 代号）
- **todo 生命周期**：`status=active`（注入）/ `done` / `void`（不注入）；TTL 7d；模型/抽取标记完成 → 显式 void；TTL 过期由既有 `ContextMaintenanceService.voidExpiredL2` 兜底（自动覆盖 workspace 行）
- **kind ⊥ executionMode**：闸门只按 `kind` 路由记忆读写；不因 fast/pro/workflow 改变 KV 选型
- **workflow 不启用**：`executionMode=workflow` 不注入 KV workspace/P0（退出本套，task-scene §2.2）
- **旧行兼容**：存量 `user_context_state` 行 `scope='user'`（迁移默认值），无 background 行注入时不展示括号、**新写入必须满足 P3**
- **Catalog SSOT**：新增 `context.memory.extract`（prompt 正文 + bump `catalog_version`），种子 SQL `19-sunshine-resource.sql` **全量**同步（禁止只补增量 INSERT）
- **不破坏 chat 现状**：chat 会话 L2 行为（scope=user 抽取/注入/审计/维护）保持兼容，只加 `todo` 类与 v22 门禁
- 编译/单测：`mvn test -pl orchestrator -Dtest=<TestClass> -q`（orchestrator 全量单测基线须保持全绿）
- DDL 禁 Flyway：`docker/mysql/init/11-sunshine-orchestrator.sql`（一项目一文件）+ `scripts/*.sql` 一次性迁移
- 前端零改动；live 脚本仅验证后端装配/沉淀/失效行为

---

## File map

| 文件 | 职责 |
|------|------|
| `docker/mysql/init/11-sunshine-orchestrator.sql` | `user_context_state` 加 `scope`/`workspace_id`/`background` 列 + 索引调整 |
| `scripts/migrate_kv_memory_scope.sql`（新建） | 一次性 ALTER：加列 + 存量行回填 `scope='user'` + 新索引 |
| `orchestrator/.../context/l2/UserContextStateEntity.java` | 加 `scope`/`workspaceId`/`background` 字段 |
| `orchestrator/.../context/l2/UserContextStateRepository.java` | scope 维度查询（user/workspace 双键） |
| `orchestrator/.../context/l2/L2StateStore.java` | 读写按 scope 路由；渲染含 background/todo；upsert 支持 done/void |
| `orchestrator/.../context/l2/L2ConflictMerger.java` | `Candidate` 加 `background`/`status` |
| `orchestrator/.../context/l2/L2ExtractService.java` | `context.memory.extract` 参数化 + `VALID_KINDS` 加 todo + v22 门禁 + status 解析 + `extractWorkspace` |
| `orchestrator/.../context/ContextAssembler.java` | `AssembleRequest` 加 `kind`/`workspaceId`；按 kind 读 scope=user/workspace |
| `orchestrator/.../context/ContextWritePath.java` | 按 `conversation.kind` 分流 L2 抽取（scope=user/workspace） |
| `orchestrator/.../context/l1/L1Compressor.java` | 压缩读 L2 同步按 kind（补注入 `ChatConversationRepository`） |
| `orchestrator/.../controller/stream/ChatStreamContextFactory.java` | `prepareNewMessage`/`buildResumePreparation` 透传 `kind`/`workspaceId` 给 `AssembleRequest` |
| `orchestrator/.../context/ContextProperties.java` | todo TTL / 门禁参数 |
| `orchestrator/src/test/.../context/l2/*`（既有 + 新） | scope 路由 / todo 生命周期 / v22 门禁单测 |
| `docker/mysql/init/19-sunshine-resource.sql` | `context.memory.extract` 新 prompt（全量快照同步） |
| `scripts/verify_kv_memory_todo_live.py`（新建） | live 验收 |
| `docs/superpowers/specs/2026-08-14-task-list-memory-unification-design.md` | 状态行 → M1 ✅ |
| `docs/implementation-plan.md` | 进度行补 M1 ✅ |

---

### Task 1: KV Memory scope 数据层 — DDL + Entity + Repository + L2StateStore scope 路由

**Files:**
- Modify: `docker/mysql/init/11-sunshine-orchestrator.sql`
- Create: `scripts/migrate_kv_memory_scope.sql`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/UserContextStateEntity.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/UserContextStateRepository.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2StateStore.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextProperties.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/l2/L2StateStoreFilterTest.java`（扩展）

**Interfaces:**
- Produces: `UserContextStateEntity.scope()`（默认 `"user"`）/ `workspaceId()` / `background()`；`UserContextStateRepository.findByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatus(...)` 等 workspace 维度派生查询
- Produces: `L2StateStore.listInjectableWorkspace(workspaceId, tenantId, Instant now)`、`assembleSystemBlock(userId, tenantId)`（=scope=user 便捷）、`assembleWorkspaceBlock(workspaceId, tenantId)`、`upsertWorkspace(workspaceId, tenantId, Candidate, sourceMsgId, Instant)`
- Produces: `ContextProperties.L2.todoTtlDays=7`
- Consumes: 无

- [x] **Step 1: 写失败单测（scope 路由 + background 渲染）**

追加到 `L2StateStoreFilterTest`：

```java
@Test
void assembleWorkspaceBlock_readsOnlyWorkspaceScope() {
    // repository 按 workspaceId+tenantId+status=active 返回 workspace 行（含 todo），user 行不返回
    // 断言块含 workspace 条目、不含 user 条目
}

@Test
void renderSystemBlock_showsBackgroundWhenPresent() {
    // 条目带 background → 渲染 `- kind/key: value （背景：xxx）`；无 background → 无括号
}
```

（测试形态以既有 `L2StateStoreFilterTest` 的 mock repository 惯例为准。）

- [x] **Step 2: 跑测确认失败**

Run: `mvn test -pl orchestrator -Dtest=L2StateStoreFilterTest -q`
Expected: FAIL（方法/字段不存在）

- [x] **Step 3: DDL — `11-sunshine-orchestrator.sql` 加列**

```sql
ALTER TABLE user_context_state
  ADD COLUMN scope         VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT 'user|workspace' AFTER id,
  ADD COLUMN workspace_id  VARCHAR(64)  NULL COMMENT 'scope=workspace 时的工作区 id（scope=user 为 NULL）' AFTER user_id,
  ADD COLUMN background    VARCHAR(256) NULL COMMENT '成立场景背景（v20）' AFTER state_value;
```

索引调整：`idx_ctx_user_kind_key_status (user_id, tenant_id, kind, state_key, status)` 语义保持，另加 workspace 维度索引：

```sql
CREATE INDEX idx_ctx_ws_kind_key_status (workspace_id, tenant_id, kind, state_key, status) ON user_context_state;
```

（`docker/mysql/init/11-sunshine-orchestrator.sql` 中 `user_context_state` 的 `CREATE TABLE` 同步加列与索引；`user_id` 保留 NOT NULL——workspace 行 user_id 存 `''` 空串，唯一键靠 `workspace_id` 区分，避免改既有 NOT NULL 约束。）

- [x] **Step 4: 迁移脚本 `scripts/migrate_kv_memory_scope.sql`**

```sql
-- 一次性：KV Memory scope 化（user_context_state 加 scope/workspace_id/background）
-- 用法: mysql -h <host> -uroot -proot123 < scripts/migrate_kv_memory_scope.sql
USE sunshine_chat;

ALTER TABLE user_context_state
  ADD COLUMN scope         VARCHAR(16) NOT NULL DEFAULT 'user' AFTER id,
  ADD COLUMN workspace_id  VARCHAR(64) NULL AFTER user_id,
  ADD COLUMN background    VARCHAR(256) NULL AFTER state_value;

-- 存量行均为 user scope（默认值生效），无需回填

CREATE INDEX idx_ctx_ws_kind_key_status (workspace_id, tenant_id, kind, state_key, status) ON user_context_state;
```

- [x] **Step 5: Entity 加字段**

```java
@Column(nullable = false, length = 16)
private String scope = "user";

@Column(name = "workspace_id", length = 64)
private String workspaceId;

@Column(length = 256)
private String background;
```

- [x] **Step 6: Repository 加 workspace 维度派生查询**

```java
List<UserContextStateEntity> findByWorkspaceIdAndTenantIdAndStatus(String workspaceId, String tenantId, String status);

Optional<UserContextStateEntity> findByWorkspaceIdAndTenantIdAndKindAndStateKeyAndStatus(
        String workspaceId, String tenantId, String kind, String stateKey, String status);
```

- [x] **Step 7: `L2StateStore` scope 路由 + background 渲染**

- `listInjectable(userId, tenantId, Instant now)` 保留（兼容既有调用，scope=user）；新增 `listInjectableWorkspace(workspaceId, tenantId, Instant now)`（workspace 行过滤：`workspace_id` 匹配 + 非空 + active + 未过期）
- `assembleSystemBlock(userId, tenantId)` 保留；新增 `assembleWorkspaceBlock(workspaceId, tenantId)`（workspaceId 空/blank → 空串）
- `renderSystemBlock`：有 `background` 时渲染 `- kind/key: value （背景：xxx）`；无则保持 `- kind/key: value`；`kind=todo` 同格式渲染（value 即自解释命题）
- `upsert` 保持 user 路径；新增 `upsertWorkspace(workspaceId, tenantId, candidate, sourceMsgId, now)`（workspace_id 维度，同值刷新/冲突合并/落库逻辑一致，`workspaceId` blank → return）

- [x] **Step 8: `ContextProperties.L2` 加 todo TTL**

```java
/** todo（未完成任务清单）TTL，短生命周期 */
private int todoTtlDays = 7;
```

`L2StateStore.ttlDays` switch 加 `case "todo" -> l2.getTodoTtlDays();`

- [x] **Step 9: 跑测确认通过**

Run: `mvn test -pl orchestrator -Dtest=L2StateStoreFilterTest -q`
Expected: PASS（新增用例 + 既有用例全绿）

- [x] **Step 10: Commit**

```bash
git add docker/mysql/init/11-sunshine-orchestrator.sql scripts/migrate_kv_memory_scope.sql \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/UserContextStateEntity.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/UserContextStateRepository.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2StateStore.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextProperties.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/context/l2/L2StateStoreFilterTest.java
git commit -m "$(cat <<'EOF'
feat(orchestrator): KV memory scope data layer (M1)

EOF
)"
```

---

### Task 2: 最小 KV 读写闸门 — AssembleRequest 透传 kind/workspaceId + 读写路由

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextAssembler.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextWritePath.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l1/L1Compressor.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/stream/ChatStreamContextFactory.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2ExtractService.java`（仅最小 `extractWorkspace`，见 Step 5）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/ContextAssemblerTest.java`（扩展）

**Interfaces:**
- Produces: `AssembleRequest(userId, tenantId, conversationId, history, currentUserQuery, modelName, kind, workspaceId)`（8 参 canonical；6 参便捷保留默认 `kind="chat"`、`workspaceId=null`）
- Produces: `ContextAssembler.assemble` 按 `kind` 读 KV：chat/缺省 → `l2StateStore.assembleSystemBlock(userId, tenantId)`；task → `l2StateStore.assembleWorkspaceBlock(workspaceId, tenantId)`（workspaceId 空 → 空块）
- Produces: `ContextWritePath.runAsync` 从 conversation 读 kind/workspaceId，按 kind 调 `l2ExtractService.extract(userId, tenantId, msgId, history)`（chat）或 `l2ExtractService.extractWorkspace(workspaceId, tenantId, msgId, history)`（task）
- Produces: `L2ExtractService.extractWorkspace(...)` 最小实现（复用 `context.l2.extract` 逻辑，落库走 `upsertWorkspace`）
- Produces: `L1Compressor` 注入 `ChatConversationRepository`，压缩读 L2 按 kind
- Consumes: `L2StateStore.assembleWorkspaceBlock` / `upsertWorkspace`（T1 产物）

- [x] **Step 1: 写失败单测**

`ContextAssemblerTest` 新增：

```java
@Test
void assemble_taskKind_readsWorkspaceScope() {
    // AssembleRequest kind=task, workspaceId=ws-1 → l2StateStore.assembleWorkspaceBlock("ws-1", tid) 被调用
    // 断言结果 l2SystemBlock 为 workspace 块内容
}

@Test
void assemble_chatKind_readsUserScope() {
    // AssembleRequest kind=chat → assembleSystemBlock(userId, tid) 被调用
}
```

（既有 `ContextAssemblerTest` 用 `@Mock L2StateStore`，直接 `when(l2StateStore.assembleWorkspaceBlock(...))` 桩。）

- [x] **Step 2: 跑测确认失败**

Run: `mvn test -pl orchestrator -Dtest=ContextAssemblerTest -q`
Expected: FAIL（AssembleRequest 新字段不存在）

- [x] **Step 3: `AssembleRequest` 加 `kind`/`workspaceId`**

record 加两字段 → canonical 8 参；保留既有 6 参便捷构造（补默认 `kind="chat"`、`workspaceId=null`）兼容既有调用。

- [x] **Step 4: `ContextAssembler.assemble` 读路由**

```java
String l2Block = "task".equals(request.kind())
        ? l2StateStore.assembleWorkspaceBlock(request.workspaceId(), request.tenantId())
        : l2StateStore.assembleSystemBlock(request.userId(), request.tenantId());
```

（`AssembleRequest.workspaceId` 为空时 `assembleWorkspaceBlock` 返回空串，T1 已保证。）

- [x] **Step 5: `ContextWritePath` 写路由 + 最小 `extractWorkspace`**

`runAsync` 内从 `assistant.getConversationId()` → `conversationService.getOwned(convId, userId, tenantId)` 取 `conv.getKind()`/`conv.getWorkspaceId()`：

```java
if ("task".equals(conv.getKind())) {
    l2ExtractService.extractWorkspace(conv.getWorkspaceId(), tenantId, messageId, history);
} else {
    l2ExtractService.extract(userId, tenantId, messageId, history);
}
```

**本步同时创建 `L2ExtractService.extractWorkspace(workspaceId, tenantId, sourceMsgId, history)` 最小实现**（T2 写路由依赖它，不能等 T3）：复用现有 `extract` 的抽取链路（同一 `context.l2.extract` prompt、同 `parseCandidates`、同置信门禁），仅落库改走 `l2StateStore.upsertWorkspace(workspaceId, tenantId, c, sourceMsgId, now)`；`workspaceId` blank → return；失败仅日志。T3 再升级为 `context.memory.extract` 参数化（scope 拼接 + todo 类 + v22 门禁）。

（`l1Compressor.compress` 保持按 convId 读 L2——其内部读 L2 的调用点同步按 kind 选 scope，见 Step 6。）

- [x] **Step 6: `L1Compressor` 读 L2 按 kind**

`L1Compressor` **注入 `ChatConversationRepository`**（当前无该依赖，需补构造注入）。`compressLocked` 中 `l2StateStore.assembleSystemBlock(userId, tenantId)` → 从 `convId` 反查 conversation kind/workspaceId，task 走 `assembleWorkspaceBlock`。反查失败/无记录降级 user scope（不抛错）。

- [x] **Step 7: 调用点透传 kind/workspaceId**

- `ChatStreamContextFactory.prepareNewMessage`：`new AssembleRequest(userId, tenantId, conv.getId(), loadedHistory, executionQuery, modelOverride, conv.getKind(), conv.getWorkspaceId())`
- `ChatStreamContextFactory.buildResumePreparation`：同（`conv.getKind()`/`conv.getWorkspaceId()`）
- `HarnessPlanner.resolveMemory`：**不改**（kind 缺省 chat 走既有便捷构造；Planner 任务态由 H1 承接，KV workspace 注入非其主路径；Worker 已用 `AssembledContext.forWorker` 隔离）

- [x] **Step 8: 全量单测回归**

Run: `mvn test -pl orchestrator -q`
Expected: PASS（既有 1000+ 单测基线全绿）

- [x] **Step 9: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextAssembler.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/ContextWritePath.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/l1/L1Compressor.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2ExtractService.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/controller/stream/ChatStreamContextFactory.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/context/ContextAssemblerTest.java
git commit -m "$(cat <<'EOF'
feat(orchestrator): minimal KV read/write gate by kind (M1)

EOF
)"
```

---

### Task 3: `todo` 类 + Catalog 参数化 + v22 门禁

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2ConflictMerger.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2ExtractService.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2StateStore.java`
- Modify: `docker/mysql/init/19-sunshine-resource.sql`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/context/l2/L2ExtractServiceParseTest.java`（扩展）、`L2ExtractConfidenceTest.java`（扩展）

**Interfaces:**
- Produces: `L2ConflictMerger.Candidate(kind, key, value, confidence, background, status)`
- Produces: `L2ExtractService` 支持 `context.memory.extract`（参数化：system 提示带 `scope=user|workspace`）；`VALID_KINDS` 加 `todo`；解析 `background`/`status`（active/done/void）
- Produces: v22 代码门禁——key 正则 `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$`、background 必填（新写入）、value 非布尔孤值（`true/false/yes/no/1/0` 忽略大小写即弃）
- Consumes: `L2StateStore.upsert`/`upsertWorkspace` 扩展 status 处理（Step 5）

- [x] **Step 1: 写失败单测（v22 门禁 + todo 解析 + status）**

追加到 `L2ExtractServiceParseTest`：

```java
@Test
void parseCandidates_acceptsTodoWithBackgroundAndStatus() {
    // 输入含 {kind:"todo", key:"finance.pending_approval", value:"跟进审批单 PR-2026-0812", confidence:0.9, background:"OA 审批", status:"active"}
    // → Candidate 含 background/status
}

@Test
void parseCandidates_rejectsBareKeyOrBooleanValue() {
    // key 不含 dot / value 为 "true" → 丢弃
}

@Test
void parseCandidates_rejectsMissingBackgroundForTodo() {
    // v22 P3：todo 无 background → 不产出 candidate（非 todo 类不强弃，兼容 chat 现状）
}
```

- [x] **Step 2: 跑测确认失败**

Run: `mvn test -pl orchestrator -Dtest=L2ExtractServiceParseTest -q`
Expected: FAIL

- [x] **Step 3: `Candidate` record 扩展**

```java
public record Candidate(String kind, String key, String value, double confidence, String background, String status) {
    // 4 参便捷构造保留：background=null、status="active"；调用点兼容
}
```

- [x] **Step 4: `L2ExtractService` 参数化 + todo + 门禁**

- `EXTRACT_PROMPT` → `context.memory.extract`；`extract`/`extractWorkspace` 内 system 用 `context.memory.extract` 正文 + `replace("{scope}", "user"/"workspace")`
- `VALID_KINDS` 加 `"todo"`
- `parseCandidates`：解析 `background`/`status`；`status` 非法值默认 `active`；`todo` 类缺 `background` → 丢弃（v22 P3）；key 不匹配 `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$` → 丢弃（对 todo 强制，对其他 kind 保持现状不强弃）；value 为布尔孤值（`true/false/yes/no/1/0` 忽略大小写）→ 丢弃
- `minConfidenceFor` 加 `case "todo" -> l2.getMinConfidence()`

- [x] **Step 5: `L2StateStore` status 生命周期**

`upsert`/`upsertWorkspace`：`candidate.status()` 为 `done`/`void` → 不新增，将同 scope+kind+key active 行置 `status=void`（显式失效）；`active` → 正常 Merger 流程。（`Candidate.status` 缺省 `active`。）

- [x] **Step 6: Catalog `context.memory.extract`（种子全量同步）**

`19-sunshine-resource.sql` 新增 prompt（含 version 表），正文对齐五层 §6.3.3（宁缺毋滥 + 结构自解释 + key/background/value 规则 + todo 类），并 bump `prompt_catalog_meta.catalog_version`。正文要点：

```
你是 KV Memory 抽取助手。当前 scope={scope}（user=用户级 / workspace=工作区级）。
从对话中识别可跨会话复用的结构化条目；仅输出 JSON 数组。
每项字段：kind, key, value, confidence(0~1), background, status(active|done|void)。
kind 只能是：profile, preference, goal, agreement, constraint, fact, decision, todo。
todo：仅当用户主动提出、跨会话仍有效、未完成；会话计划/单次迭代禁止进。
key 必须 {domain}.{facet}；value 必须自解释命题短句；background 说明成立场景。
禁止：裸 key、布尔孤值、本轮临时结论、工具日志。无条目时输出 []。
```

（线上 prompt 以 DB 为准，种子与线上有偏差时回写种子为全量快照。`context.l2.extract` 旧 prompt 保留种子不删。）

- [x] **Step 7: 跑测确认通过**

Run: `mvn test -pl orchestrator -Dtest=L2ExtractServiceParseTest,L2ExtractConfidenceTest -q`
Expected: PASS

- [x] **Step 8: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2ConflictMerger.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2ExtractService.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/l2/L2StateStore.java \
  docker/mysql/init/19-sunshine-resource.sql \
  orchestrator/src/test/java/com/sunshine/orchestrator/context/l2/L2ExtractServiceParseTest.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/context/l2/L2ExtractConfidenceTest.java
git commit -m "$(cat <<'EOF'
feat(orchestrator): KV memory todo kind with v22 gates (M1)

EOF
)"
```

---

### Task 4: 治理侧适配 — 审计 / 维护 / Admin 按 scope 兼容

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/audit/ContextAuditService.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/job/ContextMaintenanceService.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/context/admin/ContextAdminService.java`

**Interfaces:**
- Produces: workspace 维度审计/清理兼容（不破坏 user 维度现状）
- Consumes: `UserContextStateRepository` workspace 查询

- [x] **Step 1: `ContextMaintenanceService` — workspace 行清理**

`voidExpiredL2` 用 `findByStatusAndExpiresAtBefore("active", now)` 已覆盖全表（含 workspace 行），无需改；`cleanupLongSuperseded`/`cleanupLongVoid` 同理。确认现有查询不按 `user_id` 过滤即天然兼容。若存在按 user_id 的过滤，改为空串容忍（workspace 行 user_id=''）。

- [x] **Step 2: `ContextAuditService` — workspace 行跳过 LLM 审计**

`auditUserLight` 的 `findByUserIdAndTenantIdAndStatus(userId, tid, "active")` 不命中 workspace 行（user_id=''），天然隔离。补充注释说明「workspace 行走 TTL/显式 void，不做用户级 LLM 审计」即可；如需 workspace 审计留待后续，不做超出范围实现。

- [x] **Step 3: `ContextAdminService` — L2 视图补 scope/background 展示**

`toL2View` 透出 `scope`/`workspaceId`/`background`（仅 DTO 扩展，不改变列表查询）。若 DTO 改动过大致影响前端，则**降级为不改**（管理员视图可选展示，不阻塞 M1 验收）——实施者按「改动最小」原则判断。

- [x] **Step 4: 全量单测回归**

Run: `mvn test -pl orchestrator -q`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/context/audit/ContextAuditService.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/job/ContextMaintenanceService.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/context/admin/ContextAdminService.java
git commit -m "$(cat <<'EOF'
chore(orchestrator): governance compatible with KV memory scope (M1)

EOF
)"
```

---

### Task 5: live 验收脚本 + 文档同步

**Files:**
- Create: `scripts/verify_kv_memory_todo_live.py`
- Modify: `docs/superpowers/specs/2026-08-14-task-list-memory-unification-design.md` — 状态行 → M1 ✅（§9 M1 / §10 验收勾选；M2–M3 仍 ⬜）
- Modify: `docs/implementation-plan.md` — 阶段四缺口进度行补 M1 ✅

**测试步骤（live 脚本须覆盖以下场景；执行由实施者跑，前端不涉及）：**

| # | 场景 | 步骤 | 预期 |
|---|------|------|------|
| T1 | chat 沉淀用户 todo | 1) 建 chat 会话；2) 发「帮我记住跟进审批单 PR-2026-0812 状态」；3) 等抽取完成 | `user_context_state` 出现 `scope='user'` + `kind='todo'` + key `finance.*` 行；background 非空 |
| T2 | chat 新会话注入 todo | 4) 另建 chat 会话发「继续上次的事」；5) 断言 Prompt 组装含 `- todo / finance.*`（orchestrator debug 日志或审计） | 新会话上下文含 todo 行（跨会话续接） |
| T3 | 完成即 void | 6) 原会话发「PR-2026-0812 已审批完，不用跟了」；7) 等抽取 | 该 todo 行 `status=void`；新会话不再注入 |
| T4 | task 工作区 todo 隔离 | 8) 建 kind=task + workspaceId 会话（`create` 接口带 kind/workspaceId）；9) 发工作区任务；10) 断言 scope='workspace' 行写入；11) chat 会话上下文**不含** workspace 行 | 写路由分流正确，chat/task 隔离（最小闸门生效） |
| T5 | 存量 chat 兼容 | 12) chat 会话普通对话 | 既有 L2（preference 等）仍正常注入，无回归 |

**验收红线：** T3/T4 用请求体对比确认「todo 完成后不注入」「task 不污染 chat」；`user_context_state` 行数仅按沉淀语义增长（执行中不双写）。

- [x] **Step 1: 编写脚本**（参照 `scripts/verify_planner_executor_live.py` / `verify_task_list_restore_live.py` 的登录/建会话/发消息/查库惯例；`sunshine_lib.run_mysql` 查 `user_context_state`；断言 T1–T5）
- [x] **Step 2: 执行迁移** — `mysql -h <host> -uroot -proot123 < scripts/migrate_kv_memory_scope.sql`（或经 `sunshine_lib`）
- [x] **Step 3: 执行并全绿** — `python3 scripts/verify_kv_memory_todo_live.py`（orchestrator 已 `scripts/start.py --restart orchestrator`，prompt 热更新）
- [x] **Step 4: 更新 spec 状态与 implementation-plan 进度行**
- [x] **Step 5: Commit**

```bash
git add scripts/verify_kv_memory_todo_live.py \
  docs/superpowers/specs/2026-08-14-task-list-memory-unification-design.md \
  docs/implementation-plan.md
git commit -m "$(cat <<'EOF'
feat(orchestrator): verify KV memory todo kind (M1)

EOF
)"
```

---

## Out of scope（后续 plan / 分期）

| 项 | 去向 |
|----|------|
| pro 终态导出（ANSWER → H1 未完成项 → KV Memory） | task-list-memory **M2** |
| session_search（body + scope=session） | task-list-memory **M3** |
| 完整 P1/P2 闸门（含 L3 `scene=chat\|task` 向量通道隔离、session_search 工具注册、workflow 边界细化） | task-scene 独立工程 |
| 压缩点模式 / Tier 0/1/2 定序（todo 块正式落 Tier 1） | 五层 §5.5 / task-scene §4 |
| `content_hash` 幂等 upsert（抽取未变不写库） | 五层 §5.5.6（预留；M1 仍按字面合并，字节稳定靠 Merger 同值刷新） |
| 语义 merge（`context.memory.merge` / CONFLICT 判定） | 五层 §6.4（二期可选） |
| todo `items[]` 结构化子项（多目标单条） | spec §3 预留；M1 单条目标仅 `value` 自解释命题 |
| Admin 前端 L2 管理页 scope 分栏 | 前端（本 plan 零前端改动） |

---

## Spec coverage（self-review）

| Spec 要求（task-list-memory §9 M1 / §5.3 / §6） | Task |
|-----------------------------------------------|------|
| `scope` 列（user\|workspace）+ 唯一性 | T1（DDL + Repository + L2StateStore 路由） |
| `VALID_KINDS` 加 `todo` | T3 |
| Catalog extract 参数化（`context.memory.extract` 按 scope） | T3（extract/extractWorkspace + 种子） |
| v22 门禁（key 场景化 / background 必填 / value 自解释 / 宁缺毋滥） | T3（代码门禁 + prompt） |
| chat 沉淀用户 todo 新会话注入 | T3 + T5（T1/T2） |
| task 沉淀工作区 todo | T2（写路由）+ T5（T4） |
| 完成/取消即时 void | T3（status 生命周期）+ T5（T3） |
| 读写隔离闸门（task 跳过 scope=user / chat 跳过 workspace） | T2（AssembleRequest 透传 + 读/写路由） |
| 不双写（执行态仍权威） | 全局约束 + T5 红线 |
| §10 验收：KV todo 类召回 / 完成后 void / 不双写 | T5 |

---

## 风险与默认假设

1. **workspace 行 user_id 语义**：任务采用 `user_id=''` + `workspace_id` 维度（不改既有 NOT NULL 约束）；唯一性靠 scope + workspace_id 派生查询保证。若后续需要审计 workspace 行，user_id='' 行天然被 user 级查询排除，需新增 workspace 审计（Out of scope）。
2. **`AssembleRequest` record 变更**：6 参→8 参，既有 6 参便捷构造保留默认 `kind="chat"`；直接调用点（ContextAssembler 自身测试、HarnessPlanner、ChatStreamContextFactory 两处）在 T2 全量更新（HarnessPlanner 不改，走便捷构造）。
3. **prompt 参数化实现形态**：`context.memory.extract` 正文含 `{scope}` 占位，`extract`/`extractWorkspace` 各自 `replace("{scope}", "user"/"workspace")` 后拼接 user payload；旧 `context.l2.extract` 保留种子不删（T2 最小 `extractWorkspace` 仍用旧 id，T3 切换）。
4. **旧行兼容**：存量 L2 行 `scope='user'`（DEFAULT 生效）、无 background——注入时不展示括号；新写入必须带 background（v22 P3），`parseCandidates` 对 todo 强制、对其他 kind 不强弃（避免 chat 现状回归），由 prompt 宁缺毋滥约束。
5. **status 解析**：LLM 输出 status 可能缺失/非法 → 默认 `active`；`done`/`void` 仅对同 scope+kind+key active 行生效，不误伤其他 key。
6. **L1Compressor 反查 conversation**：`compress` 需补注入 `ChatConversationRepository`（T2 Step 6）；反查失败降级 user scope，不抛错阻断压缩。
7. **前端兼容**：`ContextAdminDtos` 若扩展 DTO 字段影响前端序列化 → 实施者按最小改动原则，可仅加后端字段不依赖前端使用。
