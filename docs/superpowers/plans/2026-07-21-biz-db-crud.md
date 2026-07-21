# 业务库落库 + 表级 CRUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将 finance/oa/hr 从进程内 JSON Mock 改为共享 MySQL `sunshine_biz` + SQL seed；`/mock-data` 改为左表类型 + 右 CRUD；用户下拉读 `sys_user`；身份统一为固定 UUID。

**Architecture:** 三服务 JPA 连同一库、各管本域表；Admin `/api/biz/{domain}/{table}` CRUD（`X-Admin-Token`）；SDK 工具同库读写；auth 提供 `GET /api/auth/users`；删除 Store/JSON/`/api/mock/*`/重置按钮。

**Tech Stack:** JDK 21 · Spring Boot Web + Data JPA · MySQL init（禁 Flyway）· Nacos · Vue3/Naive UI · Python 运维脚本

**设计 SSOT:** [2026-07-21-biz-db-crud-design.md](../specs/2026-07-21-biz-db-crud-design.md)

**硬约束:** 禁止 `/api/mock` alias、JSON 回退、`u-alice` 业务身份；配置键统一 `sunshine.biz.admin-token`（值仍 `sunshine-mock-admin-dev`）。

**演示用户 SSOT:**

| id | username | nickname | password |
|----|----------|----------|----------|
| `a1111111-1111-4111-a111-111111111111` | `alice` | 爱丽丝 | `password123` |
| `b2222222-2222-4222-b222-222222222222` | `bob` | 鲍勃 | `password123` |
| `c3333333-3333-4333-c333-333333333333` | `carol` | 卡罗尔 | `password123` |

BCrypt（password123）: `$2a$10$56JywJyd.ICYkiKmDc7jI.5RFrwrYDzETgcY6QsITMRjABIhFKW62`

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 删除 |
|------|------|------|------|
| **SQL** | `18-sunshine-biz.sql` | `01-init-databases.sql`；`10-sunshine-auth.sql` | — |
| **运维** | `scripts/apply_sunshine_biz_schema.py` | — | — |
| **Finance** | `entity/*`；`repo/*`；`service/FinanceBizService.java`；`controller/BizFinanceController.java` | `pom.xml`；`FinanceSunshineTools`；`FinanceController`；Nacos yaml | `TenantUserStore*`；`mock/seed-users.json`；`MockAdminController*` |
| **OA** | 同上模式（`OaBizService`；`BizOaController`） | tools/controller/pom/nacos | Store/JSON/MockAdmin |
| **HR** | 同上（含复合键实体） | 同上 | Store/JSON/MockAdmin |
| **Auth** | — | `UserRepository`；`UserService`；`AuthController`；单测 | — |
| **前端** | 重写 `MockDataView.vue`；`api/bizData.ts` | `vite.config.ts`；`api/auth.ts` | 旧 `api/mockData.ts` 或整文件替换 |
| **Live** | — | `verify_user_isolated_tools_live.py` 等 UUID | mock users/reset 调用 |

---

## Task 1: MySQL 建库 + Auth 演示用户 + biz 表种子

**Files:**
- Modify: `docker/mysql/init/01-init-databases.sql`
- Modify: `docker/mysql/init/10-sunshine-auth.sql`
- Create: `docker/mysql/init/18-sunshine-biz.sql`

- [x] **Step 1: 01 增加库**

在 `01-init-databases.sql` 末尾追加：

```sql
CREATE DATABASE IF NOT EXISTS sunshine_biz DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- [x] **Step 2: Auth 演示用户 INSERT**

在 `10-sunshine-auth.sql` 建表语句后追加（时间用固定值）：

```sql
INSERT INTO sys_user (id, username, password_hash, nickname, status, created_at, updated_at, tenant_id, default_write_hitl_mode) VALUES
('a1111111-1111-4111-a111-111111111111', 'alice', '$2a$10$56JywJyd.ICYkiKmDc7jI.5RFrwrYDzETgcY6QsITMRjABIhFKW62', '爱丽丝', 1, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000', 'default', 'never'),
('b2222222-2222-4222-b222-222222222222', 'bob',   '$2a$10$56JywJyd.ICYkiKmDc7jI.5RFrwrYDzETgcY6QsITMRjABIhFKW62', '鲍勃',   1, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000', 'default', 'never'),
('c3333333-3333-4333-c333-333333333333', 'carol','$2a$10$56JywJyd.ICYkiKmDc7jI.5RFrwrYDzETgcY6QsITMRjABIhFKW62', '卡罗尔', 1, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000', 'default', 'never');
```

- [x] **Step 3: 写 `18-sunshine-biz.sql`**

完整内容须包含：`USE sunshine_biz;` + 六表 DDL + 对齐原 JSON 的 INSERT（user_id 用 §SSOT UUID）。最低种子：

- `fin_expense`: exp-a1/exp-a2 → alice；bob 无行
- `fin_inbox`: inbox-a1 → alice；inbox-c1 → carol
- `oa_task`: task-a1 → alice；task-b1/b2 → bob
- `hr_leave_balance`: alice/bob/carol 2026（qingsong 等与旧 JSON 一致）
- `hr_leave_request`: leave-a1/a2、leave-c1
- `hr_attendance_month`: alice/bob 的 2026-07

示例 DDL 片段（六表均需完整写出，含 index）：

```sql
CREATE TABLE fin_expense (
  id           VARCHAR(64)  NOT NULL PRIMARY KEY,
  tenant_id    VARCHAR(32)  NOT NULL DEFAULT 'default',
  user_id      VARCHAR(64)  NOT NULL,
  category     VARCHAR(64)  NOT NULL,
  amount       DECIMAL(12,2) NOT NULL,
  status       VARCHAR(32)  NOT NULL,
  occurred_on  DATE         NOT NULL,
  remark       VARCHAR(512) NULL,
  created_at   DATETIME(3)  NOT NULL,
  updated_at   DATETIME(3)  NOT NULL,
  INDEX idx_fin_expense_user (tenant_id, user_id),
  INDEX idx_fin_expense_status (tenant_id, user_id, status)
);
-- fin_inbox / oa_task / hr_leave_balance / hr_leave_request / hr_attendance_month 同规范
```

`hr_leave_balance` PK：`(tenant_id, user_id, year)`；`hr_attendance_month` PK：`(tenant_id, user_id, year_month)`。

- [x] **Step 4: Commit**

```bash
git add docker/mysql/init/01-init-databases.sql docker/mysql/init/10-sunshine-auth.sql docker/mysql/init/18-sunshine-biz.sql
git commit -m "chore(sql): add sunshine_biz schema and demo auth users"
```

---

## Task 2: Live 一键 apply schema 脚本

**Files:**
- Create: `scripts/apply_sunshine_biz_schema.py`

- [x] **Step 1: 实现脚本**

复用 `scripts/sunshine_lib.py` 中已有 MySQL 连接方式（若无，则用 `pymysql`/`mysql.connector`，凭据与 README 一致：`ecs4c16g` / `root` / `root123`）。

行为：

1. `CREATE DATABASE IF NOT EXISTS sunshine_biz ...`
2. 执行 `18-sunshine-biz.sql`（可按 `;` 拆分；跳过空语句）
3. 对 auth：若三演示用户不存在则 INSERT（`INSERT IGNORE` 或先 SELECT）
4. 支持 `--dry-run` 只打印将执行的语句数

```python
#!/usr/bin/env python3
"""Apply sunshine_biz schema + demo auth users to Live MySQL (idempotent where possible)."""
# argparse: --dry-run
# read ROOT/docker/mysql/init/18-sunshine-biz.sql and ensure DB exists
# upsert alice/bob/carol into sunshine_auth.sys_user with fixed UUIDs
```

注意：重复跑 DDL 可能失败——脚本应：`CREATE TABLE IF NOT EXISTS` 已在 SQL 中，或先检测表存在则跳过建表仅补缺失种子（实现选一种写清；推荐 SQL 用 `IF NOT EXISTS`，种子用 `INSERT IGNORE`）。

- [x] **Step 2: dry-run 冒烟**

```bash
python3 scripts/apply_sunshine_biz_schema.py --dry-run
```

Expected: 打印将执行的语句，exit 0。

- [x] **Step 3: Commit**

```bash
git add scripts/apply_sunshine_biz_schema.py
git commit -m "chore(scripts): apply sunshine_biz schema to Live MySQL"
```

---

## Task 3: Finance — JPA + 服务替换 Store + 工具改读库

**Files:**
- Modify: `finance-service/pom.xml`（加 `spring-boot-starter-data-jpa`、`mysql-connector-j`；test 加 H2）
- Modify: `docs/nacos/sunshine-finance.yaml`（datasource + jpa validate + `sunshine.biz.admin-token`）
- Create: `finance-service/src/main/java/com/sunshine/finance/entity/FinExpenseEntity.java`
- Create: `finance-service/src/main/java/com/sunshine/finance/entity/FinInboxEntity.java`
- Create: `finance-service/src/main/java/com/sunshine/finance/repo/FinExpenseRepository.java`
- Create: `finance-service/src/main/java/com/sunshine/finance/repo/FinInboxRepository.java`
- Create: `finance-service/src/main/java/com/sunshine/finance/service/FinanceBizService.java`
- Modify: `finance-service/.../tools/FinanceSunshineTools.java`
- Modify: `finance-service/.../controller/FinanceController.java`（若仍依赖 Store，改为 Service）
- Modify: `FinanceApplication.java`（`@EnableJpaRepositories` 若需）
- Delete: `store/TenantUserStore.java`；`resources/mock/seed-users.json`；`controller/MockAdminController.java` 及对应测试（本 Task 先删 Store 相关单测并改为 Service 单测；MockAdmin 删除可放 Task 4）
- Test: `FinanceBizServiceTest.java`（`@DataJpaTest` + H2 schema）

- [x] **Step 1: 写失败单测（alice 有 2 条报销）**

使用 `@DataJpaTest` + `schema.sql`/`data.sql` 或 `@Sql` 插入 alice UUID 两行；断言 `listExpenses("default", ALICE, "all").size() == 2`；bob 为 0。常量：

```java
static final String ALICE = "a1111111-1111-4111-a111-111111111111";
static final String BOB = "b2222222-2222-4222-b222-222222222222";
```

- [x] **Step 2: 依赖与 Nacos**

`sunshine-finance.yaml` 追加：

```yaml
spring:
  datasource:
    url: jdbc:mysql://ecs4c16g:3306/sunshine_biz?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: root
    password: root123
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

sunshine:
  biz:
    admin-token: sunshine-mock-admin-dev
```

删除旧 `sunshine.mock.admin-token`（禁止双键）。

- [x] **Step 3: Entity / Repository / FinanceBizService**

Service 方法签名对齐原 Store（便于工具改接线）：

```java
List<ExpenseRecord> listExpenses(String tenantId, String userId, String status);
Optional<ExpenseRecord> findExpense(String tenantId, String userId, String id);
ExpenseRecord submitExpense(...);
List<FinanceInboxItem> listInbox(...);
Optional<FinanceInboxItem> findInbox(...);
ExpenseSummaryVO summarize(...);
```

- [x] **Step 4: Tools / FinanceController 注入 Service；删除 Store 与 seed-users.json**

- [x] **Step 5: 跑测**

```bash
mvn -pl finance-service -am test -Dtest=FinanceBizServiceTest,FinanceSunshineToolsTest,FinanceControllerTest -q
```

Expected: PASS（同步改单测里的 `u-alice` → ALICE UUID）。

- [x] **Step 6: Commit**

```bash
git add finance-service docs/nacos/sunshine-finance.yaml
git commit -m "feat(finance): persist expenses/inbox in sunshine_biz via JPA"
```

---

## Task 4: Finance — Biz Admin CRUD（替换 MockAdmin）

**Files:**
- Create: `finance-service/.../controller/BizFinanceController.java`
- Delete: `MockAdminController.java` + `MockAdminControllerTest.java`
- Create: `BizFinanceControllerTest.java`
- Modify: `sunshine-ui/vite.config.ts`（可暂留到 Task 8 一并改 proxy）

- [x] **Step 1: 写失败单测**

```java
mockMvc.perform(get("/api/biz/finance/expenses").header("X-Admin-Token", TOKEN))
    .andExpect(status().isOk());
mockMvc.perform(get("/api/biz/finance/expenses")).andExpect(status().is4xxClientError());
```

- [x] **Step 2: 实现 Controller**

```java
@RestController
@RequestMapping("/api/biz/finance")
public class BizFinanceController {
  // GET/POST /expenses ; PUT/DELETE /expenses/{id}
  // GET/POST /inbox ; PUT/DELETE /inbox/{id}
  // requireAdmin: header X-Admin-Token == sunshine.biz.admin-token
}
```

POST body 字段含 `userId`（必填）、业务列；服务端写 `tenant_id`（默认 default）。

- [x] **Step 3: 单测 PASS；Commit**

```bash
git commit -m "feat(finance): add /api/biz/finance CRUD; remove mock admin"
```

---

## Task 5: OA — JPA + 工具 + Biz CRUD

**Files:**
- Modify: `oa-service/pom.xml`；`docs/nacos/sunshine-oa.yaml`
- Create: `entity/OaTaskEntity.java`；`repo/OaTaskRepository.java`；`service/OaBizService.java`；`controller/BizOaController.java`
- Modify: `OaSunshineTools`；`OaTaskController`
- Delete: `OaTenantUserStore*`；`mock/seed-users.json`；`MockAdminController*`
- Test: `OaBizServiceTest`；`BizOaControllerTest`；更新既有 tools/controller 测 UUID

- [x] **Step 1: 失败单测** — bob 有 2 条 pending task；alice 不能 approve bob 的 task-b1

- [x] **Step 2: 实现 Entity/Repo/Service/Tools/Controller**

表 `oa_task`；Admin：`/api/biz/oa/tasks` CRUD；字段 `assigneeUserId`。

- [x] **Step 3: Nacos datasource → sunshine_biz；`sunshine.biz.admin-token`**

- [x] **Step 4: 测试 PASS + Commit**

```bash
git commit -m "feat(oa): persist tasks in sunshine_biz; biz CRUD"
```

---

## Task 6: HR — JPA + 工具 + Biz CRUD（含复合键）

**Files:**
- Modify: `hr-biz-service/pom.xml`；`docs/nacos/sunshine-hr.yaml`
- Create: `HrLeaveBalanceEntity`（`@IdClass` 或 `@EmbeddedId`）；`HrLeaveRequestEntity`；`HrAttendanceMonthEntity` + repos + `HrBizService` + `BizHrController`
- Modify: `HrSunshineTools`；`HrController`
- Delete: Store/JSON/MockAdmin
- Test: 更新 UUID；Admin 复合键路径单测

- [x] **Step 1: 失败单测** — alice 2026 qingsong==12；`GET leave-balances` 列表非空

- [x] **Step 2: 实现**

Admin 路径：

- `/api/biz/hr/leave-balances` + `PUT/DELETE .../leave-balances/{userId}/{year}`
- `/api/biz/hr/leave-requests` CRUD by id
- `/api/biz/hr/attendance-months` + `PUT/DELETE .../attendance-months/{userId}/{yearMonth}`

- [x] **Step 3: 测试 PASS + Commit**

```bash
git commit -m "feat(hr): persist leave/attendance in sunshine_biz; biz CRUD"
```

---

## Task 7: Auth — `GET /api/auth/users`

**Files:**
- Modify: `auth-center/.../repo/UserRepository.java`
- Modify: `auth-center/.../service/UserService.java`
- Modify: `auth-center/.../controller/AuthController.java`
- Create/Modify: DTO（可复用 `UserBriefVO`：id/username/nickname）
- Test: `AuthControllerTest` 增加 listUsers

- [x] **Step 1: Repository**

```java
List<UserEntity> findByTenantIdAndStatus(String tenantId, byte status);
```

- [x] **Step 2: Service + Controller**

```java
@GetMapping("/users")
public R<List<UserBriefVO>> listUsers(@RequestParam(defaultValue = "default") String tenantId) {
    // 需登录：与 /me 相同（Sa-Token 已拦 /api/auth/** 除 login/register）
    return R.ok(userService.listActiveUsers(tenantId));
}
```

确认 Gateway/Sa-Token 白名单：**不要**把 `/users` 放进匿名；须带登录 token。

- [x] **Step 3: 单测** — 插入 alice 后 list 含其 id

- [x] **Step 4: Commit**

```bash
git commit -m "feat(auth): list tenant users for biz-data dropdown"
```

---

## Task 8: 前端 — 表级 CRUD 页

**Files:**
- Create: `sunshine-ui/src/api/bizData.ts`
- Modify: `sunshine-ui/src/api/auth.ts`（`listAuthUsers`）
- Rewrite: `sunshine-ui/src/views/MockDataView.vue`
- Modify: `sunshine-ui/src/vite.config.ts`（proxy `/api/biz/*`；删除 `/api/mock/*`）
- Delete or gut: `sunshine-ui/src/api/mockData.ts`

- [x] **Step 1: API 客户端**

```ts
export type BizDomain = 'finance' | 'hr' | 'oa'
export function listBizRows(domain: BizDomain, table: string, q?: Record<string, string>)
export function createBizRow(...)
export function updateBizRow(...)
export function deleteBizRow(...)
// headers: X-Admin-Token from import.meta.env or constant sunshine-mock-admin-dev
```

`listAuthUsers(tenantId)` → `GET /api/auth/users`（走已有 `apiHeaders()`）。

- [x] **Step 2: 重写 MockDataView**

- 顶栏 Tabs：财务 / 人事 / OA  
- 左栏：表类型（finance: 报销单/财务待办；hr: 假期余额/请假单/考勤月报；oa: OA 待办）  
- 右栏：`n-data-table` + 新建/编辑 `n-modal` + 删除确认  
- `userId`/`assigneeUserId`：`n-select` options=`{label: nickname, value: id}`  
- **无**「重置种子」；有「刷新」  
- 保留 Codex 简约：`--sun-black` 底 + 边框（对齐现页）

- [x] **Step 3: Vite proxy**

```ts
'/api/biz/finance': { target: 'http://127.0.0.1:8710', changeOrigin: true },
'/api/biz/oa': { target: 'http://127.0.0.1:8700', changeOrigin: true },
'/api/biz/hr': { target: 'http://127.0.0.1:8720', changeOrigin: true },
```

删除 `/api/mock/finance|oa|hr`。

- [x] **Step 4: Commit**

```bash
git commit -m "feat(ui): biz-data page as table CRUD with auth user select"
```

---

## Task 9: Nacos sync + Live apply + 脚本 UUID

**Files:**
- Modify: `scripts/verify_user_isolated_tools_live.py`（ALICE/BOB UUID；Admin 改调 `/api/biz/...`；去掉 reset）
- Grep 全仓业务路径替换残留 `u-alice`/`u-bob`/`u-carol`（测试/脚本；**不要**改 design 历史叙述除非必要）
- Run: `python3 scripts/sync_nacos.py`（finance/oa/hr yaml）
- Run: `python3 scripts/apply_sunshine_biz_schema.py`

- [x] **Step 1: 改 Live 脚本常量**

```python
ALICE = "a1111111-1111-4111-a111-111111111111"
BOB = "b2222222-2222-4222-b222-222222222222"
ADMIN_TOKEN = os.environ.get("BIZ_ADMIN_TOKEN", "sunshine-mock-admin-dev")
```

G3 改为：`GET /api/biz/finance/expenses` 带 Admin token 返回列表（非 `/api/mock/.../users`）。

- [x] **Step 2: sync_nacos + apply schema + 重启**

```bash
python3 scripts/sync_nacos.py
python3 scripts/apply_sunshine_biz_schema.py
python3 scripts/start.py --restart finance oa hr auth tool-manager
```

（服务名空格分隔，勿用逗号。）

- [x] **Step 3: Commit 脚本改动**

```bash
git commit -m "test: point user-isolation live verify at UUID + biz CRUD APIs"
```

---

## Task 10: 验收 G1–G5 + G4 grep

- [x] **Step 1: 单测回归**

```bash
mvn -pl finance-service,oa-service,hr-biz-service,auth-center -am test -q
```

Expected: PASS（若无关模块失败，缩小到本四个模块相关 test class）。

- [x] **Step 2: Live**

```bash
python3 scripts/verify_user_isolated_tools_live.py
```

Expected: G1/G2（及脚本内 HR/Admin）PASS。

手动 / Chat：登录 `alice` / `password123`，问报销；确认命中种子。

- [x] **Step 3: G4 grep**

```bash
rg -n "TenantUserStore|seed-users\.json|/api/mock/" \
  finance-service oa-service hr-biz-service sunshine-ui/src scripts \
  --glob '!**/docs/superpowers/**'
```

Expected: 无匹配（或仅计划/注释中允许的历史说明——业务代码须为 0）。

```bash
rg -n "u-alice|u-bob|u-carol" finance-service oa-service hr-biz-service scripts/verify_user_isolated_tools_live.py
```

Expected: 0。

- [x] **Step 4: 勾选本计划 checkbox；可选小幅更新 CLAUDE/README 端口说明（若提到 mock reset）**

- [x] **Step 5: 最终 commit（若有勾选/文档）**

```bash
git commit -m "docs: mark biz-db-crud plan gates done"
```

---

## Spec 覆盖自检

| Spec 项 | Task |
|---------|------|
| sunshine_biz 单库 + 六表 + SQL seed | 1 |
| Auth 演示 UUID 用户 | 1、7、9 |
| 删 Store/JSON/mock/reset | 3–6、8 |
| `/api/biz/...` CRUD | 4–6 |
| SDK 工具读库 + 短名不变 | 3、5、6 |
| `GET /api/auth/users` | 7 |
| 前端左表右 CRUD + auth 下拉 | 8 |
| Live apply + UUID 脚本 | 2、9、10 |
| G1–G5 | 10 |

**配置键：** 全仓仅 `sunshine.biz.admin-token`（无 `sunshine.mock.admin-token` 并存）。
