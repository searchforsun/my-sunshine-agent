# [REQ-PHASE2-AUTH] 阶段二 2.1 认证与用户体系 — 实施计划

> **执行说明：** 实施**仅**能通过 **`apk-subagent-coding`** skill 完成：按 Task 顺序用 Task Tool 派发子 Agent；**仅**更新每个 Task 标题下一行的任务级 `- [ ]` / `- [x]`，**不**维护步骤级勾选。**禁止**内联/本会话主 Agent 直接按本清单改业务代码；禁止 `executing-plans` 类同会话批量实现。计划落盘后须经人类伙伴明确同意再启动该 skill。

**Goal:** 建立 Sa-Token JWT 认证与用户注册/登录，Gateway 集中鉴权并注入 `x-user-id`，前端 Bearer Token 化，满足阶段二 2.1 检查门 G1–G9。

**Architecture:** auth-center（用户表 + Auth API）→ Gateway（Sa-Token Reactor 校验 + 白名单 + header 注入）→ BFF/Orchestrator（移除 anonymous 默认值）；sunshine-ui 登录/注册页 + authStore + 路由守卫；Vite 代理改 Gateway :8000。

**Tech Stack:** JDK 21, Spring Boot 3.2.9, Sa-Token 1.45.0, JPA + Flyway, Spring Cloud Gateway (WebFlux), Vue3 + Pinia + Naive UI, Redis DB1, MySQL `sunshine_auth`

**Constraints:**

### 项目规则
- JDK 21（见 `CLAUDE.md`）
- Sa-Token 1.45.0；不升级 Spring Boot 3.3+ / AgentScope 2.0
- 统一响应 `R<T>` + `BizException` + `GlobalExceptionHandler`
- BFF 只做透传；鉴权仅在 Gateway
- dev-yml 变更须同步 `docs/nacos/` 并提醒更新 Nacos 线上（IC-05）
- 不做多租户；`x-tenant-id` 固定 `default`
- 上线前执行清库脚本（design §1.4）

---

## 文件结构（本需求涉及）

| 操作 | 路径 | 职责 |
|------|------|------|
| 修改 | `auth-center/pom.xml` | web、jpa、mysql、flyway、spring-security-crypto |
| 新建 | `auth-center/.../entity/UserEntity.java` | sys_user JPA 实体 |
| 新建 | `auth-center/.../repo/UserRepository.java` | 用户仓储 |
| 新建 | `auth-center/.../service/UserService.java` | 注册/校验/查询 |
| 新建 | `auth-center/.../controller/AuthController.java` | register/login/logout/me |
| 新建 | `auth-center/.../dto/*.java` | 请求/响应 DTO |
| 新建 | `auth-center/.../config/SaTokenConfig.java` | Sa-Token 拦截器（auth-center 侧） |
| 新建 | `auth-center/src/main/resources/db/migration/V1__sys_user.sql` | Flyway（IC-04） |
| 新建 | `auth-center/src/main/resources/application-dev.yaml` | 本地 MySQL/Redis/Sa-Token |
| 修改 | `auth-center/src/main/resources/application.yml` | Nacos import 入口 |
| 新建 | `docs/nacos/sunshine-auth.yaml` | Nacos 配置模板（IC-05） |
| 修改 | `gateway/pom.xml` | sa-token-reactor + redis-jackson（IC-02） |
| 新建 | `gateway/.../config/SaTokenGatewayConfig.java` | Reactor 鉴权 + 白名单 |
| 新建 | `gateway/.../filter/UserIdInjectFilter.java` | 解析 loginId → x-user-id，剥离伪造 header |
| 修改 | `gateway/src/main/resources/application-dev.yaml` | auth 路由 + Sa-Token |
| 修改 | `docs/nacos/sunshine-gateway.yaml` | `/api/auth/**` → lb://sunshine-auth（IC-06） |
| 修改 | `bff/.../controller/*Controller.java` | 移除 x-user-id defaultValue |
| 修改 | `orchestrator/.../controller/*Controller.java` | 同上；缺 header 返回 401 |
| 新建 | `sunshine-ui/src/stores/authStore.ts` | token/user/login/logout/fetchMe |
| 新建 | `sunshine-ui/src/views/LoginView.vue` | 登录页 |
| 新建 | `sunshine-ui/src/views/RegisterView.vue` | 注册页 |
| 修改 | `sunshine-ui/src/router/index.ts` | 路由 + beforeEach 守卫 |
| 修改 | `sunshine-ui/src/composables/useUserId.ts` | 删除随机 ID，改读 authStore（或删除并迁移引用） |
| 修改 | `sunshine-ui/src/api/chat.ts` 等 | 统一 Bearer header |
| 修改 | `sunshine-ui/vite.config.ts` | proxy → :8000 |
| 修改 | `scripts/start.ps1` | 启动 auth-center |
| 新建 | `scripts/phase2-auth-reset.sql` | TRUNCATE 会话表 |
| 新建 | `scripts/phase2-auth-demo.ps1` | G1–G6 curl 验收 |
| 测试 | `auth-center/src/test/.../AuthControllerTest.java` | Auth API 单测 |
| 测试 | `gateway/src/test/.../SaTokenGatewayFilterTest.java` | 401/白名单/header 注入 |
| 修改 | `docs/implementation-plan.md` | 阶段二 JWT 检查门 |
| 修改 | `requirements/in-progress/REQ-PHASE2-AUTH.md` | 验收勾选 |

---

### Task 1: Sa-Token 集成核实 + auth-center 基础设施
- [x]

**涉及文件：**
- 修改：`auth-center/pom.xml`
- 新建：`auth-center/src/main/resources/application-dev.yaml`
- 修改：`auth-center/src/main/resources/application.yml`
- 新建：`auth-center/src/main/resources/db/migration/V1__sys_user.sql`
- 新建：`auth-center/src/main/java/com/sunshine/auth/entity/UserEntity.java`
- 新建：`auth-center/src/main/java/com/sunshine/auth/repo/UserRepository.java`
- 新建：`auth-center/src/main/java/com/sunshine/auth/config/SaTokenConfig.java`
- 新建：`docs/nacos/sunshine-auth.yaml`
- 参考：`orchestrator/src/main/resources/application-dev.yaml`（IC-04 数据源模式）

**实施说明：**

1. **dep-inspect（IC-01）**：核实 `StpUtil.login(Object loginId)`、`StpUtil.getTokenValue()`、`SaLoginModel` JWT 模式配置项（`sa-token.token-style=jwt`、`sa-token.jwt-secret-key`）；结论写入 Task commit message 或 `agent-workspace/req-REQ-PHASE2-AUTH/sa-token-notes.md`。
2. `auth-center/pom.xml` 增加：`spring-boot-starter-web`、`spring-boot-starter-data-jpa`、`mysql-connector-j`、`flyway-core`、`flyway-mysql`、`spring-security-crypto`（仅 BCrypt，不引入 security filter）。
3. Flyway `V1__sys_user.sql` 按 design §2.1；JPA `UserEntity` 字段对齐；`UserRepository.findByUsername`。
4. `application-dev.yaml`：`jdbc:mysql://ecs4c16g:3306/sunshine_auth`、Redis `database: 1` password redis123（IC-03）；Sa-Token 配置与 design §4 一致。
5. `application.yml` 对齐 orchestrator：Nacos discovery + optional config import；`SaTokenConfig` 注册 `/api/auth/**` 路由拦截（login/register 使用 `@SaIgnore` 或全局 exclude）。
6. 同步 `docs/nacos/sunshine-auth.yaml`（IC-05）；README 注释：首次部署需 `CREATE DATABASE sunshine_auth`。
7. **测试覆盖范围**：本 Task 以编译 + 启动为主；可选 `UserRepositoryTest`（@DataJpaTest + H2）验证 username 唯一约束。

**验证：**

- 命令：`mvn compile -pl auth-center -am -q`
- 预期：编译通过；本地 `--spring.profiles.active=dev` 启动后 Flyway 创建 `sys_user` 表（MySQL 可达时）

**提交建议：** `feat(auth): auth-center infrastructure with flyway and sa-token config`

---

### Task 2: auth-center Auth API（register / login / logout / me）
- [x]

**涉及文件：**
- 新建：`auth-center/src/main/java/com/sunshine/auth/controller/AuthController.java`
- 新建：`auth-center/src/main/java/com/sunshine/auth/service/UserService.java`
- 新建：`auth-center/src/main/java/com/sunshine/auth/dto/RegisterRequest.java`、`LoginRequest.java`、`AuthUserVO.java`、`LoginResponse.java`
- 测试：`auth-center/src/test/java/com/sunshine/auth/AuthControllerTest.java`
- 测试：`auth-center/src/test/resources/application-test.yaml`

**实施说明：**

1. `AuthController` 映射 `/api/auth`：`POST /register`、`POST /login`、`POST /logout`、`GET /me`；响应 `R<T>`（design §3）。
2. `UserService.register`：校验 username 4–32 `[a-zA-Z0-9_]`、password 8–64；BCrypt hash；冲突抛 `BizException(409)`；**不**自动 login。
3. `UserService.login`：校验密码；`status==0` → 403；成功 `StpUtil.login(userId)` 返回 token + userInfo（IC-01）。
4. `logout`：`StpUtil.logout()`；`me`：从 `StpUtil.getLoginId()` 查用户。
5. 参数校验失败 → 400；密码错误 → 401（不泄露用户名是否存在）。
6. **测试覆盖范围**（`AuthControllerTest` + H2/Testcontainers 或 @WebMvcTest + mock UserService）：
   - 注册成功 200 + 字段完整
   - 重复 username 409
   - 非法 username/password 400
   - 登录成功返回 token
   - 错误密码 401
   - 禁用用户 403
   - logout 后 me 401
   - me 带有效 token 200

**验证：**

- 命令：`mvn test -pl auth-center -am -q`
- 预期：全部测试绿

**提交建议：** `feat(auth): register login logout me APIs`

---

### Task 3: Gateway Sa-Token 鉴权 + 路由 + Header 注入
- [x]

**涉及文件：**
- 修改：`gateway/pom.xml`
- 新建：`gateway/src/main/java/com/sunshine/gateway/config/SaTokenGatewayConfig.java`
- 新建：`gateway/src/main/java/com/sunshine/gateway/filter/UserIdInjectGatewayFilterFactory.java`（或 GlobalFilter）
- 修改：`gateway/src/main/resources/application-dev.yaml`
- 修改：`docs/nacos/sunshine-gateway.yaml`
- 测试：`gateway/src/test/java/com/sunshine/gateway/SaTokenGatewayConfigTest.java`

**实施说明：**

1. **dep-inspect（IC-02）**：核实 `sa-token-reactor-spring-boot3-starter` 1.45.0 的 Gateway 全局过滤器注册方式（`SaReactorFilter` / `SaRouter`）；白名单 `POST /api/auth/register`、`POST /api/auth/login`。
2. `gateway/pom.xml` 增加 `sa-token-reactor-spring-boot3-starter`、`sa-token-redis-jackson`、`spring-boot-starter-data-redis`；Redis DB 1 与 auth-center 一致（IC-03）。
3. Nacos/dev 路由：**先**匹配 `Path=/api/auth/**` → `lb://sunshine-auth`；**后**匹配 `Path=/api/**` → `lb://sunshine-bff`（IC-06）。
4. 鉴权通过后 GlobalFilter：读 `StpUtil.getLoginIdAsString()` → 设置 `x-user-id`、`x-tenant-id: default`；`mutate` 请求移除客户端传入的 `x-user-id`/`x-tenant-id`（design §3 G9）。
5. 鉴权失败：HTTP 401 + JSON `{code:401,msg:"未登录或 Token 已失效"}`。
6. `/v1/**` 保持无鉴权（design 白名单）。
7. **测试覆盖范围**（WebTestClient + mock Sa-Token 或 @SpringBootTest）：
   - 无 Token 访问 `/api/chat/stream` → 401
   - 白名单 `/api/auth/login` 无 Token 可达（404/502 可接受，非 401）
   - 有效 Token mock 下 header 注入 x-user-id

**验证：**

- 命令：`mvn test -pl gateway -am -q`
- 预期：测试绿；`mvn compile -pl gateway -am -q` 通过

**提交建议：** `feat(gateway): sa-token JWT auth filter and auth route`

---

### Task 4: BFF / Orchestrator 移除 anonymous 默认值
- [x]

**涉及文件：**
- 修改：`bff/src/main/java/com/sunshine/bff/controller/ChatController.java`
- 修改：`bff/src/main/java/com/sunshine/bff/controller/ConversationController.java`
- 修改：`bff/src/main/java/com/sunshine/bff/controller/GenerationController.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/controller/ConversationController.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationController.java`

**实施说明：**

1. 所有 `@RequestHeader(value = "x-user-id", defaultValue = "anonymous")` 改为 **required=true**（去掉 defaultValue）；`x-tenant-id` 保留 required=false + 默认 `default` 或同样 required 由 Gateway 保证。
2. 缺 `x-user-id` 时返回 401（可在 Controller 入口判断 blank → `BizException(401)`，或使用 `@RequestHeader` required 让框架 400/401）。
3. **不**在 BFF/Orchestrator 引入 Sa-Token 依赖（design 原则）。
4. 集成测试（`ConversationIntegrationTest` 等）仍直接传 `x-user-id` header，**无需 Token**（测试绕过 Gateway，行为不变）。
5. **测试覆盖范围**：现有 orchestrator 集成测试仍绿；可选新增 `ChatControllerTest` 缺 header 401（若尚无则补 1 个 WebFlux 测试）。

**验证：**

- 命令：`mvn test -pl orchestrator,bff -am -q`
- 预期：默认 exclude integration 的 surefire 绿；integration 测试若本地跑仍绿

**提交建议：** `refactor: require x-user-id header without anonymous default`

---

### Task 5: 前端登录/注册 + authStore + 路由守卫
- [x]

**涉及文件：**
- 新建：`sunshine-ui/src/stores/authStore.ts`
- 新建：`sunshine-ui/src/views/LoginView.vue`
- 新建：`sunshine-ui/src/views/RegisterView.vue`
- 修改：`sunshine-ui/src/router/index.ts`
- 修改：`sunshine-ui/src/composables/useUserId.ts`（删除或改为 authStore 薄封装）
- 修改：`sunshine-ui/src/api/chat.ts`、`sunshine-ui/src/api/chatSessions.ts` 等引用 `apiHeaders()` 的文件
- 修改：`sunshine-ui/src/main.ts`（App mount 调 `fetchMe()`）
- 测试：`sunshine-ui/e2e/auth-guard.spec.ts`（可选 Playwright）

**实施说明：**

1. `authStore`（Pinia）：state `token`（localStorage `sunshine-token`）、`user`；actions `login`、`register`、`logout`、`fetchMe`；401 时 `clearAuth()`。
2. `LoginView`：Naive UI 表单 → `POST /api/auth/login` → 存 token → 跳转 `redirect` 或 `/chat`。
3. `RegisterView`：注册成功 → 提示 → 跳转 `/login`（**不**自动登录，design 默认）。
4. `router.beforeEach`：未登录访问 `/chat|/knowledge|/status` → `/login?redirect=`；已登录访问 `/login|/register` → `/chat`。
5. `apiHeaders()`：`Authorization: Bearer ${token}`；删除 `x-user-id`/`x-tenant-id` 客户端发送；SSE fetch 同样带 Authorization（design §5.2 IC 风险）。
6. 删除随机 UUID 逻辑；登录时清除旧 `sunshine-user-id` localStorage key。
7. **测试覆盖范围**：
   - Playwright `auth-guard.spec.ts`：未登录访问 `/chat` 重定向 `/login`
   - 手动：登录后 chat 列表可加载（Task 7 live）

**验证：**

- 命令：`cd sunshine-ui && npm run build`
- 预期：TypeScript 编译通过，无 lint 错误

**提交建议：** `feat(ui): login register pages and auth store`

---

### Task 6: 开发环境对齐（Vite 代理 + 启动脚本 + 清库脚本）
- [x]

**涉及文件：**
- 修改：`sunshine-ui/vite.config.ts`
- 修改：`scripts/start.ps1`
- 新建：`scripts/phase2-auth-reset.sql`
- 修改：`gateway/src/main/resources/application-dev.yaml`（若 Task 3 未完整）
- 修改：`docs/nacos/sunshine-gateway.yaml`、`docs/nacos/sunshine-auth.yaml`

**实施说明：**

1. `vite.config.ts`：`proxy['/api'].target` 改为 `http://localhost:8000`（Gateway）。
2. `start.ps1`：在 bff 之前启动 `auth-center`（`Start-Service "auth" "auth-center" "sunshine-auth"`）；日志目录 `auth-center/logs`。
3. `phase2-auth-reset.sql`：`TRUNCATE chat_message; TRUNCATE chat_conversation;` + 注释说明清除 localStorage keys。
4. 确认 dev/Nacos 双模式 Gateway 路由均含 auth-center；dev-yml 与 `docs/nacos/` 同步（IC-05）。
5. **测试覆盖范围**：脚本语法检查；`start.ps1 -Profile dev` 能拉起 auth-center 进程（手动 smoke）。

**验证：**

- 命令：`powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Profile dev`（可选，需已 package）；检查 `http://localhost:8100` 或 auth 健康
- 预期：5+1 服务启动顺序含 auth-center；Vite 经 Gateway 可达 `/api/auth/login`

**提交建议：** `chore: vite proxy to gateway and start auth-center`

---

### Task 7: 端到端验收脚本 + 检查门落盘
- [x]

**涉及文件：**
- 新建：`scripts/phase2-auth-demo.ps1`
- 修改：`docs/implementation-plan.md`（阶段二检查门 JWT 项）
- 修改：`requirements/in-progress/REQ-PHASE2-AUTH.md`（验收标准勾选）
- 参考：design §8 G1–G9

**实施说明：**

1. `phase2-auth-demo.ps1` 自动化（Gateway :8000）：
   - Step 1：register 新用户 200
   - Step 2：重复 register 409
   - Step 3：login 200 取 token
   - Step 4：错误密码 login 401
   - Step 5：无 Token POST `/api/chat/stream` → 401
   - Step 6：Bearer Token 创建会话 + list conversations 200
   - Step 7：logout + me 401
   - Step 8：带 Token + 伪造 `x-user-id: hacker` 创建会话，list 仍只见 Token 用户会话（G9）
2. 手动 G7/G8：浏览器登录 → `/chat` SSE 对话一条。
3. 执行 `phase2-auth-reset.sql` 说明写入 `REQ-PHASE2-AUTH-design.md` 或部署 README 一句。
4. 更新 `implementation-plan.md` 阶段二检查门「JWT 校验：无效 Token → 401」为 `[x]`；REQ 验收项同步勾选。
5. **测试覆盖范围**：脚本本身即 live 验收；curl 失败时 exit 1。

**验证：**

- 命令：`powershell -ExecutionPolicy Bypass -File scripts/phase2-auth-demo.ps1`
- 预期：Step 1–8 全部 PASS；implementation-plan JWT 检查门 `[x]`

**提交建议：** `docs: phase2.1 auth acceptance gate and demo script`

---

## Task 依赖顺序

```
Task 1 → Task 2 → Task 3 → Task 4
                    ↓
              Task 5 + Task 6（可并行，均依赖 Task 3）
                    ↓
                 Task 7
```

## 自检摘要

| 项 | 结果 |
|----|------|
| 设计覆盖 G1–G9 + 全部模块 | ✅ Task 2–7 |
| 占位符 | ✅ 无 TBD |
| IC 对齐 | ✅ Task 1/2 引用 IC-01；Task 3 IC-02/03/06；Task 6 IC-05 |
| 每 Task 有测试路径 | ✅ |
