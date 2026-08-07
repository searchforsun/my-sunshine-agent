# 会话标题 LLM 摘要 — 首条消息小模型生成 ≤15 字标题

> **状态**：✅ 已实现（2026-08-07）
> **日期**：2026-08-07
> **编号**：阶段四增量（会话体验增强）
> **一句话**：新对话/新任务发送**首条消息**时，**异步调用小模型**（`deepseek-v4-flash`）对首条消息做一次**标题摘要**（≤15 字），作为会话/任务标题；生成完成经 **SSE `meta:title` 事件**推送前端即时更新，不拖慢首 token；失败/超时/用户已改名时降级保留截断标题。

---

## 1. 背景与目标

### 1.1 现状

当前会话标题机制（无 LLM 参与）：

- 新会话创建：后端落库 `title='新对话'`（`ChatConversationEntity.title` 默认值）。
- 发送首条消息：前后端**各自**用「首条消息截断 28 字」同步生成标题——
  - 前端：`chatStore.updateTitleLocal`（`ChatView.performSend` 在 `messages.length===0` 时触发，截 28 字）。
  - 后端：`ConversationService.autoTitleIfDefault → deriveAutoTitle`（`ChatStreamContextFactory.prepareNewMessage` 同步落库，截 28 字）。
- 刷新/重进会话：前端 `pickConversationTitle` 以 API 标题为准。

### 1.2 问题

- 标题 = 首条消息的前 28 字截断，缺乏语义概括；长消息标题冗长、含半截句子。
- 需求要求：新对话/新任务首个输入时，用小模型生成 **15 字以内**的标题摘要。

### 1.3 目标

1. 新对话/新任务（`kind=chat`/`task`）发送**首条消息**时，调用**小模型**做一次标题摘要，**≤15 字**，作为会话/任务标题。
2. 不拖慢首 token（异步生成）；生成完成经 SSE 推送前端**即时更新**侧栏/header。
3. 复用现成链路：小模型走 llm-gateway（`deepseek-v4-flash` 已配置），提示词正文走 prompt-manager **Catalog**（不硬编码）。

---

## 2. 现有链路与复用点

### 2.1 标题落库/更新链路

```
用户发送首条消息
 ├─ ChatView.handleSend → chatStore.ensureCurrent [无会话时 create() → POST /api/conversations]
 ├─ performSend: messages.length===0 → updateTitleLocal(convId, text)  前端截28字（store+localStorage）
 ├─ POST /api/chat（stream）
 │    └─ ChatStreamContextFactory.prepareNewMessage:
 │         1. resolveConversation（conversationId 空 → create，title='新对话'）
 │         2. appendMessage 落库 user/assistant
 │         3. autoTitleIfDefault → deriveAutoTitle(用户消息) 截28字 落库   ← 改造点
 │         4. assemble → ChatStreamExecutor/ChatController 组装 SSE
 ├─ SSE: meta(conversation) → meta(message STREAMING) → chunks → meta(message COMPLETED)
 └─ 前端: setConversationIdFromStream / 渲染 / header sessionTitle + 侧栏 conv.title
```

### 2.2 可复用的小模型调用先例

`QueryRewriteService.rewriteForPlanner`（`orchestrator/.../rewrite/QueryRewriteService.java`）：

```
AgentRewriteProperties.Planner cfg（model=deepseek-v4-flash，Nacos 配置）
  → PromptCatalogHolder.snapshot().text("rewrite.planner")
  → LlmGatewayClient.complete(model, systemPrompt, user)   // 非流式
  → parse + 失败降级为原输入
```

标题生成照抄该模式：**配置化小模型名 + Catalog prompt + `complete()` 非流式 + 后处理 + 失败降级**。

### 2.3 SSE 组装两条路径（都必须覆盖）

| 路径 | 位置 | 组装 |
|------|------|------|
| Redis GenerationJob 缓冲 | `ChatController.sseFluxFromRedis`（新消息默认走此路径） | `Flux.concat(meta, historical, live, done)` |
| 直连（无 Redis 组件） | `ChatStreamExecutor.wrapStream` | `Flux.concat(meta, chunks, done)` |

两者均在 `done` 后追加 title 事件 Flux 即可，无需改动主流。

---

## 3. 设计

### 3.1 时序

```
用户发送首条消息
 ├─ prepareNewMessage：autoTitleIfDefault 同步截断 15 字落库兜底（标题立即非"新对话"，避免 loadDetail 回退）
 │    └─ autoTitle 标志 =「本会话首条消息」真实值（原硬编码 true）
 ├─ SSE 主流（meta → chunks → done）   ← 与标题生成并行，互不阻塞
 │    └─ 并行启动 ConversationTitleService.generateIfFirstMessage(ctx)：
 │         ├─ 调小模型(deepseek-v4-flash) + Catalog prompt conversation.title
 │         ├─ 后处理：去引号/围栏/空白 → 截断 ≤15 字
 │         ├─ 校验 DB 当前 title 仍为自动值（用户未手动 rename）→ updateTitle 落库
 │         └─ 返回标题（空 = 跳过）
 ├─ SSE 尾部：Flux.concat(meta, chunks, done, titleEventSse(ctx))
 │    └─ title 完成 → 推 meta:title {type:'title', conversationId, title}；失败/空 → 不推
 └─ 前端：meta:title → chatStore.updateTitleFromStream(convId, title) → 侧栏/header 即时更新
```

### 3.2 SSE 契约（新增事件）

```
{ "type": "title", "conversationId": "<convId>", "title": "<≤15字标题>" }
```

- 位置：追加在 `meta(message COMPLETED)` 之后、SSE 连接关闭之前。
- 兼容性：前端 `parseSsePayload` 对未知 `type` 返回 `ignore`，新事件对旧前端安全；新前端对不推事件的场景安全（无事件 = 保持截断标题）。

### 3.3 并行与不阻塞首 token 的实现

`ConversationTitleService.titleEventSse(ctx)` 返回 `Flux<ServerSentEvent<String>>`：

- 入口处 `CompletableFuture<String> future = ctx.autoTitle() ? generateIfFirstMessage(ctx) : null;` —— future **创建即并行启动**（内部 `CompletableFuture.supplyAsync` + boundedElastic），与主流同时跑。
- 返回 `Flux.fromFuture(future).filter(hasText).map(title -> sse(metaTitle(...))).onErrorResume(→ empty)`。
- 两条 SSE 组装路径在 `Flux.concat(..., done, titleEventSse(ctx))` 中追加；主流结束后 future 通常已完成（LLM 1~2s vs 主流数秒），SSE 连接几乎不延长；若未来得及完成则短暂等待（上限受 LLM 超时约束）。

### 3.4 触发条件（只生成一次）

- `ChatStreamContext.autoTitle` 语义修正：`prepareNewMessage` 中在 `autoTitleIfDefault` 落库**之前**判断 `conv.getTitle()` 是否为默认值，得到真实首消息标记传入 `ChatStreamContext`。
- 续跑路径 `ChatResumePreparation.toStreamContext()` 的 `autoTitle` 已是 `false`，天然不触发。
- 后续轮次：标题已非默认 → `autoTitle=false`，不重复生成。

### 3.5 覆盖保护（用户手动改名不被覆盖）

LLM 标题落库前校验：DB 当前 title 仍为**自动值**（`DEFAULT_TITLE` 或该轮截断值 `deriveAutoTitle(userContent)`）。若用户已手动 rename，则跳过落库与推送。时序安全：`prepareNewMessage` 已同步落库截断值，`titleEventSse` 在其后校验覆盖。

---

## 4. 改动清单

### 4.1 后端（orchestrator）

| 文件 | 改动 |
|---|---|
| `config/ConversationTitleProperties.java`（新建） | `@ConfigurationProperties("agent.title")`：`enabled=true`、`model="deepseek-v4-flash"`、`maxLength=15`；`@RefreshScope` |
| `conversation/ConversationTitleService.java`（新建） | 标题生成核心：Catalog prompt 读取、`LlmGatewayClient.complete(model, systemPrompt, userContent)`、后处理（strip/去引号/限长）、未改名校验 + 落库、`titleEventSse(ctx)` 组装 SSE 事件 |
| `conversation/ConversationService.java` | `AUTO_TITLE_MAX_LEN` 28→15；`DEFAULT_TITLE` 改 `public static final` 供首消息判断 |
| `conversation/GenerationFlushScheduler.java` | 新增 `metaTitle(String conversationId, String title)` |
| `controller/stream/ChatStreamContextFactory.java` | `prepareNewMessage`：先算首消息标记再 `autoTitleIfDefault`，传给 `ChatStreamContext.autoTitle` |
| `controller/stream/ChatStreamExecutor.java` | `wrapStream`：`Flux.concat(meta, chunks, done, titleService.titleEventSse(ctx))`；移除 done 内 `maybeUpdateTitle` 冗余调用（`prepareNewMessage` 已同步落库） |
| `controller/ChatController.java` | `sseFluxFromRedis`：同样追加 title 事件 Flux（resume 路径 `maybeUpdateTitle` 保留） |

> 注：`ChatStreamExecutor.maybeUpdateTitle` 在 `wrapStream` 的 done 分支被移除（职责由 `prepareNewMessage` 同步落库 + title 事件承担）；`ChatController` 第 191 行 resume 路径的 `maybeUpdateTitle` 保留（resume 时 `autoTitleIfDefault` 因标题非默认天然 no-op，且 resume 不触发 LLM 标题）。

### 4.2 Catalog（prompt-manager DB）

`docker/mysql/init/17-sunshine-prompt-manager.sql` 追加：

```sql
INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version)
VALUES ('conversation.title', 'title', '会话 · 标题摘要', '首条消息标题摘要：新对话/新任务首个输入时，用小模型提炼 ≤15 字中文短语标题。', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer)
VALUES ('conversation.title', 1, 'published',
'你是对话标题生成器。根据用户的第一条消息，用 15 个字以内的中文短语概括对话主题。\n要求：\n- 只输出标题本身，不要引号、书名号、标点、编号或任何解释\n- 长度不超过 15 个汉字\n- 用短语而非完整句子，例如「排查订单支付失败」「新员工入职材料清单」', NULL, '初始种子', 'agent');
```

### 4.3 配置（Nacos）

`docs/nacos/sunshine-orchestrator.yaml` 追加：

```yaml
agent:
  title:
    enabled: true
    model: deepseek-v4-flash
    max-length: 15
```

> 交付前必跑 `python scripts/sync_nacos.py` 并重启 orchestrator。

### 4.4 前端（sunshine-ui）

| 文件 | 改动 |
|---|---|
| `api/sseDispatch.ts` | `SseMeta` 加 `title?: string`；新增 `title` handler → `{ kind: 'meta', meta: asMeta(obj) }` |
| `api/chatSessions.ts` | `useChatSessions` 新增 `onConversationTitle?: (convId, title) => void` 参数；`send()` 的 onMeta 增加 `title` 分支 → `onConversationTitle(meta.conversationId ?? sessionId, meta.title)` |
| `stores/chatStore.ts` | 新增并导出 `updateTitleFromStream(id, title)`：直接覆盖 `conv.title` + `upsertCachedIndex`（后端已保证仅未改名时推送） |
| `views/ChatView.vue` | `useChatSessions` 传入 `onConversationTitle: (cid, t) => chatStore.updateTitleFromStream(cid, t)`；`updateTitleLocal` 截断 28→15 对齐后端 |

---

## 5. 降级与边界

| 场景 | 行为 |
|---|---|
| LLM 调用异常/超时 | `onErrorResume` → 不推事件，保留同步截断 15 字标题，日志 warn |
| LLM 返回空/纯标点 | 后处理为空 → 不落库不推送 |
| 用户流式中手动 rename | DB title ≠ 自动值 → 跳过落库与推送 |
| 主流失败/中断 | `done` 不执行 → title 事件不推（截断标题保留） |
| 后续轮次 / resume | `autoTitle=false`，不生成 |
| `kind=task` 任务会话 | 与 chat 一致，同样触发（`prepareNewMessage` 统一路径） |

---

## 6. 验收与测试

### 6.1 功能验收

1. 新对话发首条消息：标题约 1~2s 内由截断变为 LLM 摘要（≤15 字）；侧栏与 header 同步更新。
2. 新任务（`kind=task`）发首条消息：同样生成。
3. 后续轮次不再触发；既有会话标题不变。
4. 流式过程中用户手动改名：不被 LLM 标题覆盖。
5. 停掉 llm-gateway：标题回退为截断 15 字，无报错。

### 6.2 自动化

- 新增/扩展 orchestrator 单测：`ConversationTitleService` 后处理（去引号、限长）、未改名校验分支。
- 前端 vitest：`sseDispatch` 的 `title` 事件解析、`chatStore.updateTitleFromStream`。
- 端到端（live 脚本，按项目惯例）：新增 `scripts/verify_title_generation_live.py` 或在既有验收脚本中覆盖——发首条消息 → 等待 → 断言 `/api/conversations` 返回标题 ≠ 截断值且 ≤15 字。

### 6.3 交付纪律

- 改 `docs/nacos/sunshine-orchestrator.yaml` 后必跑 `python scripts/sync_nacos.py` 并重启 orchestrator。
- Catalog 种子落库后重启 orchestrator（`PromptCatalogHolder` 定时刷新 + fail-fast）。
- 前端生产构建设 `VITE_BFF_STREAM_BASE`。
