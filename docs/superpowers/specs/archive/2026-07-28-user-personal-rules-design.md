# 设置页分组 + 个人规则（Personal Rules）设计

日期：2026-07-28
状态：已评审（用户确认方案 A）

## 1. 背景与目标

账号设置当前是 `UserSettingsModal.vue` 单一弹窗平铺 5 项配置（用户名、昵称、当前租户、默认执行模式、默认写操作确认）。本次增强两件事：

1. **设置页分组**：弹窗改为左侧分组导航 + 右侧配置面板，按类型收纳现有配置。
2. **个人规则（Personal Rules）**：用户自定义一段规则文本（类似 soul 文件），执行时注入系统提示，对**全部模式**（ReAct / Plan-Workflow / 静态 Workflow / 专家协作）统一生效。

**确认的关键决策**：

| 决策点 | 结论 |
|--------|------|
| 作用范围 | 全部模式的**顶层** Agent 回答生效；workflow 子 Agent 与 spawn 子 Agent 不自动继承（保持上下文隔离设计） |
| 配置形态 | 单条 Markdown 自由文本，上限 4000 字符 |
| 持久化 | `sys_user` 新增 `personal_rules` 字段（auth-center） |
| 运行时传递 | 前端随聊天请求体 `personalRules` 透传（与 `executionPreference` / `writeHitlMode` 同链路），**不**新增跨服务调用 |
| 页面形态 | 保持弹窗，弹窗内左侧分组导航 |

## 2. 设置页分组 UI

弹窗加宽至 `min(680px, 94vw)`，内部左右两栏：

- **左侧分组导航**（~160px）：三个分组项，竖排，选中态仅文字加粗 + 无灰底（遵循 UI SSOT：背景 `--sun-black`，边框分区，禁止灰底选中）。
- **右侧配置面板**：随分组切换。

分组与配置项：

| 分组 | 配置项 |
|------|--------|
| 账号 | 用户名（只读）、昵称 |
| 对话偏好 | 当前租户、默认执行模式、默认写操作确认 |
| 个人规则 | 多行 Markdown 文本域（`NInput type="textarea"`，autosize，maxlength 4000）+ 说明文案「将作为系统提示注入你的所有对话；留空则不注入」 |

保存逻辑不变：单「保存」按钮统一提交 `updateProfile` + 本地偏好（executionPreference 仍为 localStorage 级偏好）。

样式遵循 Codex 简约 SSOT：`--sun-black` 底、`1px var(--sun-border)` 分区、输入框 `sun-field` 覆写 Naive 灰底。

## 3. 数据模型与 API

### 3.1 数据库（`docker/mysql/init/10-sunshine-auth.sql` 追加，禁 Flyway）

```sql
ALTER TABLE sys_user ADD COLUMN personal_rules TEXT NULL COMMENT '用户个人规则（soul），注入系统提示';
```

（新环境由建表语句内联该列；既有环境执行 ALTER。按本仓库约定，SSOT 为该文件，直接更新 `CREATE TABLE` 并在文件尾部追加幂等 ALTER 注释段。）

### 3.2 auth-center

- `UserEntity`：新增 `personalRules`（`@Column(name = "personal_rules", columnDefinition = "TEXT")`）。
- `UpdateProfileRequest`：新增 `personalRules`（可空；`null` 表示不修改，空串表示清空）。
- `UserService.updateProfile`：非 `null` 时落库（`trim`，空串存 `NULL`）。
- `AuthUserVO` / `LoginResponse` / `UpdateProfileResponse`：均返回 `personalRules`，前端登录/`me()` 后即可拿到。

### 3.3 前端

- `authStore`：user 类型 + `updateProfile` 签名扩展 `personalRules`；`applyUser` 存储。
- `UserSettingsModal`：分组 UI + 个人规则编辑，随保存提交。
- `chatSessionRegistry.SendOptions` 与 `chatSessions.send`：**由 send 内部直接读取 authStore**（`SendOptions` 不加字段——规则是用户级常量，不是单次发送选项），请求体加 `personalRules`（仅当非空）。续跑（resume）请求不带。

## 4. 注入链路（orchestrator）

### 4.1 请求模型

- `ChatMessage`：新增 `personalRules` 字段。
- BFF `OrchestratorClient` / `ChatController` 透传（BFF 只读透传，不加工——符合「模型输出/用户输入不二次加工」原则）。

### 4.2 注入点

orchestrator 收到 `personalRules` 后，包装为一个带头的注入块：

```
## 用户个人规则
{personalRules}
```

（包裹头文案为固定常量，非提示词 SSOT 违例——它是结构分隔标记，类比 `injectedBlocks` 现有的上下文块头。若评审认为应走 Catalog，则在 prompt-manager 增加 `personal-rules.wrapper` 条目；默认实现用常量。）

注入位置：作为 `AgentRunRequest.injectedBlocks` 的**首元素**（个人规则优先于业务注入上下文），复用 `PromptComposer` 现有 `injectedUserContexts` 注入点（USER 角色，在当前用户消息之前），**不改动 6 层叠加结构**。

### 4.3 覆盖范围

| 路径 | 是否注入 | 说明 |
|------|----------|------|
| ReAct 顶层（`ReactExecutor` → `AgentRunRequest.main`） | ✅ | 从 `ChatStreamContext` 取 `personalRules` 加入 injectedBlocks 首位 |
| Plan-Workflow 降级 ReAct（`executeWithInjected`） | ✅ | 同一入口，自然带上 |
| 专家协作 Hub 发言（`ExpertHubEngine` gather 阶段） | ✅ | 从执行上下文透传 |
| Workflow 顶层 answer/llm 节点 | ✅ | 经 `AnswerNodeHandler` / llm 节点的 compose 请求注入（DIRECT 模式 `forDirect`/`forWorkflowLlm` 增加可选参数或经上下文携带） |
| Workflow 子 Agent（`AgentRunRequest.sub`） | ❌ | 上下文隔离，节点 prompt 自描述 |
| spawn 子 Agent（`spawn_subagent`） | ❌ | 同上，spawnPrompt 自描述 |

> 实现时如「Workflow 顶层 answer 节点」的注入点与 injectedBlocks 通路不一致（answer 走 `forDirect`，无 injectedBlocks），则给 `PromptComposeRequest.forDirect/forWorkflowLlm` 增加可选 `personalRules` 参数，`PromptComposer` 在 base-system 之后追加同一包装块。两种通路共用同一包装常量。

### 4.4 安全与校验

- `personalRules` 是偏好数据，非身份凭证；`x-user-id` 仍由 Gateway 注入，客户端不得自填（约定不变）。
- 长度限制双端校验：前端 maxlength 4000；orchestrator 收到后超长截断至 4000 + warn（防御性，非「二次加工模型输出」）。
- 空串/null 不注入，无注入块。

## 5. 测试与验收

1. **单测**：
   - auth-center `UserServiceTest`：`personalRules` 保存/清空/不修改三态。
   - orchestrator `PromptComposerTest`：personalRules 块出现在 injectedBlocks 首位、包装头正确、空不注入。
   - `ReActAgentRuntimeTest`：`ChatMessage.personalRules` → `AgentRunRequest.injectedBlocks` 传递。
2. **前端**：`UserSettingsModal` 分组切换渲染（Vitest 组件测试或人工）。
3. **Live 验收**：新增 `scripts/verify_personal_rules_live.py`——设置规则（如「所有回答用文言文」）→ 发 Chat（auto / 强制 workflow 各一条）→ 断言回答体现规则 → 清空规则 → 断言不再体现。
4. **回归**：`AuthControllerTest`、`PromptComposerTest`、现有 `verify_*` 关键链路（`verify_prompt_catalog_live.py` 冒烟）。

## 6. 影响面清单

| 层 | 文件 |
|----|------|
| DB | `docker/mysql/init/10-sunshine-auth.sql` |
| auth-center | `UserEntity` / `UpdateProfileRequest` / `UserService` / `AuthUserVO` / `LoginResponse` / `UpdateProfileResponse` |
| orchestrator | `ChatMessage` / `ChatController` / `ChatStreamContext` / `ReactExecutor` / `ExpertHubEngine` / `PromptComposeRequest`（可选参数）/ `PromptComposer`（包装块）/ answer 节点 handler |
| BFF | `ChatController` / `OrchestratorClient`（透传字段） |
| 前端 | `UserSettingsModal.vue` / `authStore.ts` / `chatSessions.ts` / auth API 类型 |
| 脚本 | `scripts/verify_personal_rules_live.py`（新增） |

## 7. 非目标（YAGNI）

- 不做多条规则列表 / 规则启停。
- 不做子 Agent 规则继承配置项。
- 不做规则模板市场 / 分享。
- 不引入 orchestrator → auth-center 运行时调用。
