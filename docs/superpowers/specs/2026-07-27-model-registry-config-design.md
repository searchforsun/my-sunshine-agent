# 模型配置化与注册中心 — 技术设计（SSOT）

> **日期**：2026-07-27
> **状态**：⬜ 待评审
> **关联**：阶段五 spec [phase5-operation-openness-design.md](./phase5-operation-openness-design.md) 5.3 的前置（模型注册表是场景路由/计费的底座）
> **需求来源**：llm-gateway 模型配置前端化，可任意添加模型；抽取公共配置能力屏蔽模型差异；不支持多模态前端报错；不支持 reasoning 的模型要兼容；各处模型配置改为下拉；chat 页支持选模型

---

## 1. 背景与现状问题

| 现状 | 问题 |
|------|------|
| 模型清单在 Nacos `sunshine-llm-gateway.yaml` `llm.providers.*`（apiKey 明文/`${ENV}`） | 加模型要改 YAML → `sync_nacos.py` → 重启 llm-gateway，无法"前端任意添加" |
| `QwenAdapter` / `DeepSeekAdapter` 两文件 90% 重复（仅 provider key、uri 前缀、`supports` 前缀判断不同） | 每接一个 OpenAI 兼容厂商就要复制一个 Adapter 类 |
| orchestrator 模型名硬编码在 Nacos（`agent.model.name` / `agent.intent.model` / `agent.planner.model` / `agent.rewrite.*.model`）；rag `rewrite.*.model` 在 kb 配置 bundle | 配置点分散，手填模型名易错，无校验 |
| `reasoning_content` 只在 orchestrator `LlmGatewayClient` 解析；adapter 层无归一化 | 非思考模型若返回厂商私有 reasoning 字段（或思考模型切到非思考模型），前端无防护 |
| 无多模态能力元数据 | 选了非多模态模型带图，错误只能等上游 API 报错，体验差 |
| chat 前端无模型选择 | 用户无法按会话切换模型 |

## 2. 目标与非目标

**目标**：
1. 模型配置 DB 化 + `/models` 管理页 CRUD，热更新（不重启）；
2. llm-gateway 抽取公共 `OpenAiCompatibleAdapter`，provider 差异收敛为**配置**（base_url / api_key / path 前缀 / capabilities），新接 OpenAI 兼容厂商**零代码**；
3. 能力元数据（capabilities）驱动差异屏蔽：`reasoning` / `multimodal` / `tool_call`；
4. 不支持多模态的模型收到带附件请求：前端选中即提示 + gateway 兜底 400；
5. 不支持 reasoning 的模型：gateway 归一化剥离 reasoning 字段，前端契约统一（永远只看 `reasoning_content`，没有就没有）；
6. 所有模型配置点改下拉（数据源统一 `/v1/models`）；chat 底栏加模型下拉，会话级绑定。

**非目标**：
- 场景化自动路由（`model=auto` → 场景池）——属阶段五 5.3，本期只做"注册表 + 手动选择"底座；
- token 计费/配额——属阶段五 5.2；
- 非 OpenAI 兼容协议（anthropic native / gemini native）——本期仅 OpenAI 兼容协议族（含 DashScope compatible-mode）；真 Anthropic/Gemini 接入再议；
- embedding / rerank / OCR 模型配置化——本期仅 chat completion 模型。

## 3. 架构总览

```
┌─────────────┐   ┌──────────────────────────────────────────┐
│ sunshine-ui │   │ llm-gateway (:8300)                       │
│  /models 页 │──▶│  ModelAdminController (CRUD)              │
│  chat 下拉  │──▶│  ModelController /v1/models (含 capabilities) │
└─────────────┘   │  ModelRegistry (DB 加载 + Redis 热更新)    │
                  │  OpenAiCompatibleAdapter (统一适配)        │
┌─────────────┐   │  NormalizeFilter (reasoning/multimodal)    │
│ orchestrator│──▶│  ModelRouter (按注册表路由 + 现有降级链)    │
│ modelOverride│  └──────────────┬───────────────────────────┘
└─────────────┘                  │ MySQL sunshine_llm.model_provider/model_definition
                                 │ Redis channel: model-catalog-changed
```

**SSOT 决策**：模型配置 SSOT = **MySQL**（`20-sunshine-llm.sql`，一项目一文件，禁 Flyway）。Nacos `sunshine-llm-gateway.yaml` 的 `llm.providers.*` 降级为**启动兜底**（DB 不可用时 fail-fast 原则下保留最小可用集，与 4.11 D10 "fail-fast / 保留旧 Snapshot" 同哲学）。

## 4. 数据模型

`docker/mysql/init/20-sunshine-llm.sql`（新库 `sunshine_llm`）：

```sql
CREATE TABLE model_provider (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_key  VARCHAR(64)  NOT NULL UNIQUE COMMENT 'deepseek/qwen/openai/azure/...',
  display_name  VARCHAR(128) NOT NULL,
  protocol      VARCHAR(32)  NOT NULL DEFAULT 'openai-compatible' COMMENT '本期仅 openai-compatible',
  base_url      VARCHAR(256) NOT NULL COMMENT '不含 /chat/completions；是否含 /v1 由 path_prefix 决定',
  path_prefix   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '请求路径前缀，deepseek=/v1，qwen dashscope compatible=空',
  api_key       VARCHAR(512) NOT NULL COMMENT '支持 ${ENV_VAR} 占位，运行时解析',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE model_definition (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_key   VARCHAR(64)  NOT NULL,
  model_name     VARCHAR(128) NOT NULL COMMENT '上游真实模型名，如 deepseek-v4-pro',
  display_name   VARCHAR(128) NOT NULL COMMENT '前端下拉展示名',
  context_window INT          NOT NULL DEFAULT 32768,
  encoding       VARCHAR(32)  NOT NULL DEFAULT 'cl100k_base',
  capabilities   JSON         NOT NULL COMMENT '{"reasoning":bool,"multimodal":bool,"tool_call":bool,"json_mode":bool}',
  enabled        TINYINT(1)   NOT NULL DEFAULT 1,
  sort_order     INT          NOT NULL DEFAULT 0 COMMENT '下拉排序',
  tenant_id      VARCHAR(64)  NOT NULL DEFAULT 'default',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_provider_model (provider_key, model_name, tenant_id)
);
```

**种子数据**：把现有 Nacos 的 4 个模型（deepseek-v4-pro / deepseek-v4-flash / qwen-plus / qwen-max）迁入，`capabilities` 标注：deepseek-v4-pro `{"reasoning":true,"tool_call":true}`，deepseek-v4-flash / qwen-plus / qwen-max `{"reasoning":false,"tool_call":true}`，均 `multimodal:false`（qwen-vl 系列后续自行添加时标 true）。

## 5. llm-gateway 改造

### 5.1 统一适配器（抽取公共能力）

合并 `QwenAdapter` + `DeepSeekAdapter` → 单一 `OpenAiCompatibleAdapter`：
- `supports(model)`：查 `ModelRegistry`，注册表内含该 model 即支持；
- 请求构造差异收敛到 provider 配置：`base_url` + `path_prefix` + `api_key`（`${ENV}` 运行时解析）；
- 删除 `ProviderProperties` 的 provider 硬编码读取（保留作兜底 snapshot）。

`OpenAiRequestBodyFactory` 保留（透传 tools/tool_calls 等字段），新增：按目标模型 capabilities 裁剪——`reasoning=false` 时**请求侧**剥离 `enable_thinking` / `reasoning_effort` 等思考参数（防某些厂商对不支持参数报错）。

### 5.2 归一化层（NormalizeFilter）— 屏蔽差异的核心

对**响应**统一处理，让下游（orchestrator/前端）只看到一种契约：

| 场景 | 处理 |
|------|------|
| 思考模型（`reasoning:true`）返回 `reasoning_content` | 原样透传 |
| 思考模型返回厂商私有字段（如 `reasoning` / `thinking`） | 重命名为 `reasoning_content` |
| 非思考模型（`reasoning:false`）返回任何 reasoning 类字段 | **剥离**，content 不受影响 |
| 带附件请求（messages 含 image_url）打到 `multimodal:false` 模型 | 请求前拦截，返回 400 `{error:{code:"model_not_multimodal",message:"模型 X 不支持图片输入"}}` |

流式与非流式同样处理（流式在 SSE chunk 的 delta 上归一化）。

### 5.3 ModelRegistry（DB + 热更新）

- 启动加载 DB → 内存注册表；
- 管理页 CRUD 后发布 Redis channel `model-catalog-changed`（复用 `ToolCatalogChangePublisher` 模式），各实例收到后刷新；
- DB 不可用：fail-fast 原则——若内存注册表为空则启动失败；运行中 DB 抖动沿用旧 snapshot 并告警。

### 5.4 ModelRouter 调整

- `route()`/`stream()` 先经 `NormalizeFilter` 校验（多模态拦截），再走适配器；
- 降级链 `fallback.routes` 保留（Nacos 非提示词运行参数），但 fallback 目标模型也必须是注册表中 `enabled` 的模型，否则跳过。

### 5.5 API

| 端点 | 说明 |
|------|------|
| `GET /v1/models` | 扩展：`ModelInfo` 增加 `display_name` / `capabilities` / `provider`，供下拉 |
| `GET /api/models/providers` / `POST` / `PUT` / `DELETE` | provider CRUD（管理页） |
| `GET /api/models/definitions` / `POST` / `PUT` / `DELETE` | 模型 CRUD（管理页） |
| `POST /api/models/definitions/{id}/toggle` | 启用/停用 |

BFF 透传 `/api/models/**`（复用现有透传模式）。

## 6. orchestrator 透传（会话级模型）

| 改动点 | 内容 |
|--------|------|
| `AgentRunRequest` | 增加 `modelOverride` 字段（record 加字段，所有工厂方法追加重载或末尾参数） |
| `ReActAgentFactory` | `buildModel()` 优先 `request.modelOverride()`，空则 `modelName` 默认 |
| `ExpertPeerAgentFactory` / `IntentRouter` / `QueryRewriteService` / `WorkflowPlanner` | 同上：override 优先，否则用 Nacos `agent.*.model` 默认 |
| `LlmGatewayClient` | `completeMessages`/`doStream` 目前直接用实例字段 `modelName`（`@Value`），改为方法参数传入 model（override 优先），所有调用点（complete/stream/streamComposed/completeComposed）同步调整 |
| `ChatController` / `ExecutionDispatcher` | 从会话/请求取 `modelName` → 构造 `AgentRunRequest` 时填入 |
| `chat_conversation` | 追加列 `model_name VARCHAR(128) NULL`（`11-sunshine-orchestrator.sql` 追加，禁 Flyway） |

BFF `ChatRequest` 增加 `modelName` 字段；会话级绑定：前端首次选择后写会话，后续消息沿用，可中途改（改后该会话后续消息用新模型）。

## 7. 前端改动

### 7.1 `/models` 管理页（新视图 `ModelsView.vue`）
与 `/tools` 同构：左侧 provider 列表 + 右侧模型表格（display_name / context_window / capabilities 标签 / enabled 开关）+ 新增/编辑弹窗（capabilities 用 checkbox 组）。Codex 简约风格（`--sun-*` 变量，输入用 `sun-field` 覆写）。

### 7.2 chat 底栏模型下拉
- `ChatView.vue` 底栏新增模型下拉（与 executionPreference 同级，compact 304px、对号 18px、无灰底，复用 `ExecutionModeSelector` 样式约定）；
- 数据源 `/v1/models`（含 capabilities）；选 `multimodal:false` 模型且当前输入带图 → 选项禁用 + tooltip「该模型不支持图片」；
- 会话级绑定：选择写入会话，切换会话恢复其绑定模型。

### 7.3 其他配置点下拉化
| 页面 | 现状 | 改为 |
|------|------|------|
| prompts 路由规则（`RoutingRuleEditor`） | 手填模型名 | 下拉（`/v1/models`） |
| knowledge kb 配置（rewrite.model） | 手填 | 下拉 |
| orchestrator Nacos `agent.*.model` | 手填（无 UI） | 维持 Nacos（系统级默认，不进 UI；UI 下拉只覆盖业务配置点） |

**边界**：Nacos 里的系统级默认模型（`agent.model.name` 等）不进 UI 下拉——它们是"缺省值"，由运维在 YAML 维护；UI 下拉只覆盖"业务可选"的配置点。chat 页选择的模型是会话级 override，不回写 Nacos。

## 8. 错误处理

| 场景 | 行为 |
|------|------|
| 非多模态模型 + 带图 | 前端选项禁用；绕过前端直调 → gateway 400 `model_not_multimodal` |
| 非思考模型返回 reasoning 字段 | gateway 剥离，前端无感知 |
| 选了已停用/删除的模型（会话历史绑定） | orchestrator fallback 到 Nacos 默认模型 + 时间线 warning step |
| DB 启动不可用 | fail-fast（注册表为空拒绝启动） |
| api_key `${ENV}` 未配置 | 适配器调用时 401 → 现有降级链 fallback |

## 9. 测试与验收

- **单测**：`OpenAiCompatibleAdapter` supports/请求构造（path_prefix 差异）；`NormalizeFilter` 四类场景（透传/重命名/剥离/多模态拦截）；`ModelRegistry` 热更新；
- **Live**：`verify_model_registry_live.py`——
  - M1：`/models` 页新增 provider + 模型 → 不重启 → `/v1/models` 可见；
  - M2：chat 选新模型对话 → 时间线正常 + `LlmIoTracer` 日志 model=新模型；
  - M3：非多模态模型带图 → 400 `model_not_multimodal`；
  - M4：非思考模型（capabilities reasoning=false）→ 响应无 reasoning 字段；思考模型 → reasoning 正常透传；
  - M5：停用会话绑定模型 → fallback 默认模型 + warning；
- **回归**：`phase2_agent_demo.py --suite all` PASS；orchestrator 单测全绿；spawn/沙箱/HITL/peer/expert Live 不回退。

## 10. 文件与库表变更索引

| 变更 | 位置 |
|------|------|
| 新建 `20-sunshine-llm.sql`（新库 `sunshine_llm` 两表 + 种子） | `docker/mysql/init/` |
| `chat_conversation` 加 `model_name` 列 | `docker/mysql/init/11-sunshine-orchestrator.sql` 追加 |
| llm-gateway：`OpenAiCompatibleAdapter` / `NormalizeFilter` / `ModelRegistry` / `ModelAdminController` / `ModelController` 扩展 / 删 `QwenAdapter`+`DeepSeekAdapter` | `llm-gateway/src/main/java/com/sunshine/llm/` |
| llm-gateway 依赖：+ spring-boot-starter-data-jpa + mysql（同 tool-manager 模式）+ Nacos 加 datasource | `llm-gateway/pom.xml` + `docs/nacos/sunshine-llm-gateway.yaml` |
| orchestrator：`AgentRunRequest.modelOverride` + 各 Factory/Client override 优先 | `orchestrator/.../agent/runtime/` + `agent/` + `client/` |
| BFF：`ChatRequest.modelName` + `/api/models/**` 透传 | `bff/` |
| 前端：`ModelsView.vue` + chat 模型下拉 + 路由规则/kb 配置下拉 | `sunshine-ui/src/` |

## 11. 决策记录

- **D1**：模型配置 SSOT = MySQL，Nacos 仅启动兜底——与 tools/workflows/prompts 的 DB SSOT 惯例一致。
- **D2**：合并为单一 `OpenAiCompatibleAdapter` 而非每厂商一类——当前两 Adapter 90% 重复，差异全是配置。
- **D3**：归一化层放 llm-gateway 而非 orchestrator——屏蔽差异是网关职责，下游只认一种契约。
- **D4**：系统级默认模型（Nacos `agent.*.model`）不进 UI 下拉——UI 下拉只覆盖业务可选点；chat 选择是会话级 override，不污染系统默认。
- **D5**：本期仅 OpenAI 兼容协议族；anthropic/gemini native 协议接入时再扩展 `protocol` 分支。
- **D6**：会话级 override 中途可改——改后该会话后续消息用新模型，历史消息不变（跨模型续跑上下文不一致风险由用户选择承担，前端在切换时给轻提示）。
