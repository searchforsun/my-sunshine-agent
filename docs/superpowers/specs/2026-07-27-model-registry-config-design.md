# 模型配置化与注册中心 — 技术设计（SSOT）

> **日期**：2026-07-27 · **修订**：2026-08-10（评审收敛）
> **状态**：📋 评审修订中
> **关联**：阶段五 [phase5-operation-openness-design.md](./phase5-operation-openness-design.md) 5.3 的前置（模型注册表是场景路由/计费的底座）
> **需求来源**：模型配置前端化；公共适配屏蔽厂商差异；多模态/reasoning 能力元数据；各配置点下拉；chat 可选模型；**去掉 Nacos 全部模型清单/模型名**

---

## 1. 背景与现状问题

| 现状 | 问题 |
|------|------|
| 模型清单在 Nacos `sunshine-llm-gateway.yaml` `llm.providers.*`（含 apiKey） | 加模型要改 YAML → sync → 重启；密钥与清单混在运维配置里 |
| orchestrator `agent.model.*` / `intent.model` / `planner.model` / `rewrite.*.model` / `title.model` 手填 | 配置点分散，无校验、无统一 fallback、无法前端管理 |
| `QwenAdapter` / `DeepSeekAdapter` 90% 重复 | 每接一个 OpenAI 兼容厂商就要复制 Adapter |
| `reasoning_content` 只在 orchestrator 解析；无 capabilities | 非思考/非多模态模型无契约级防护 |
| chat 无模型选择；Agents/KB/路由等手填模型名 | 用户与运营都无法从注册表选模型 |

---

## 2. 目标与非目标

**目标**：
1. **SSOT = MySQL（`sunshine_resource`）+ resource-manager 管理面**；llm-gateway / orchestrator **只消费**注册表与场景绑定，热更新、不重启；
2. **去掉 Nacos 全部模型相关配置**（providers / fallback.routes / `agent.*.model` / `context-window` 等），Nacos 仅保留加解密密钥与非模型运行参数（超时等）；
3. 统一 `OpenAiCompatibleAdapter`：provider 差异收敛为配置；新接 OpenAI 兼容厂商零代码；
4. **场景绑定**：先有通用默认模型与配置；各场景（intent / rewrite / title / planner…）可选主模型 + fallback + 场景专属参数；chat 等场景暴露**用户可选模型池**；
5. capabilities 驱动差异屏蔽：`reasoning` / `multimodal` / `tool_call`；
6. 前端所有模型配置点改为下拉（数据源统一注册表）；密钥存 DB，**Nacos 仅配 AES 密钥材料**做加解密。

**非目标**：
- 场景化自动路由（`model=auto` → 场景池权重）——属阶段五 5.3；本期为「注册表 + 场景手动绑定 + 用户手动选择」；
- token 计费/配额——属 5.2；
- 非 OpenAI 兼容协议（anthropic/gemini native）；
- embedding / rerank / OCR 模型配置化。

---

## 3. 架构总览

```
┌──────────────┐   CRUD / 下拉    ┌──────────────────────────────────────┐
│ sunshine-ui  │ ───────────────▶│ resource-manager (:8240)               │
│ /models 管理 │◀── BFF /api/ ──│  ModelAdmin / ModelScene / Crypto(AES) │
│ chat·各配置  │     models/**   │  Publisher → Redis model-catalog-changed│
└──────────────┘                 └──────────────────┬───────────────────┘
                                                    │ MySQL sunshine_resource
                                                    │  provider / definition / scene_binding
                     ┌──────────────────────────────┼────────────────────────┐
                     ▼                              ▼                        ▼
          ┌──────────────────┐         ┌────────────────────┐    ┌──────────────┐
          │ llm-gateway      │         │ orchestrator       │    │ 其他消费方    │
          │ RegistryCache    │         │ ModelSceneResolver │    │ KB rewrite 等 │
          │ OpenAI Adapter   │         │ scene 主/备 + chat │    └──────────────┘
          │ NormalizeFilter  │         │ override；Window   │
          │ GET /v1/models   │         │ 按 effectiveModel  │
          └──────────────────┘         └────────────────────┘
```

**SSOT 决策**：
- 模型定义 / 场景绑定 / 密钥密文 → **MySQL + resource-manager**（与 Skill/Agent/Prompt 管理聚合一致）；
- llm-gateway **不引入 JPA**（保持 WebFlux）；启动与热更新时拉取 Catalog（HTTP 内网或 Redis snapshot），内存路由；
- Nacos：**删除** `llm.providers.*`、`llm.fallback.routes`、`agent.model.name|api-key|context-window`、`agent.intent.model`、`agent.planner.model`、`agent.rewrite.*.model`、`agent.title.model`；**仅保留** `model.crypto.aes-key`（及 webclient 超时等非模型参数）。

**D7（评审）**：管理面落 resource-manager，禁止在 WebFlux llm-gateway 上堆 JPA。

---

## 4. 数据模型

库表挂在已有库 **`sunshine_resource`**（resource-manager 数据源），init：`docker/mysql/init/20-sunshine-model-registry.sql`（一项目一文件，禁 Flyway）。`01-init-databases.sql` 无需新建库。

### 4.1 `model_provider`

```sql
CREATE TABLE model_provider (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_key  VARCHAR(64)  NOT NULL COMMENT 'deepseek/qwen/openai/...',
  display_name  VARCHAR(128) NOT NULL,
  protocol      VARCHAR(32)  NOT NULL DEFAULT 'openai-compatible',
  base_url      VARCHAR(256) NOT NULL COMMENT '不含 /chat/completions；是否含 /v1 由 path_prefix 决定',
  path_prefix   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'deepseek=/v1，qwen dashscope compatible=空',
  api_key_enc   VARCHAR(1024) NOT NULL COMMENT 'AES 密文；明文仅写入时出现，读接口永不回明文',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_provider (provider_key, tenant_id)
);
```

### 4.2 `model_definition`（`model_name` **租户内全局唯一**）

```sql
CREATE TABLE model_definition (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_key     VARCHAR(64)  NOT NULL,
  model_name       VARCHAR(128) NOT NULL COMMENT '上游真实模型名，全局路由键',
  display_name     VARCHAR(128) NOT NULL,
  context_window   INT          NOT NULL DEFAULT 32768,
  encoding         VARCHAR(32)  NOT NULL DEFAULT 'cl100k_base',
  capabilities     JSON         NOT NULL COMMENT '{"reasoning":bool,"multimodal":bool,"tool_call":bool}',
  user_selectable  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=可出现在 chat 等用户下拉',
  enabled          TINYINT(1)   NOT NULL DEFAULT 1,
  sort_order       INT          NOT NULL DEFAULT 0,
  tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_model_name (tenant_id, model_name),  -- D8：全局唯一，禁止跨 provider 同名
  KEY idx_provider (provider_key, tenant_id)
);
```

### 4.3 `model_scene_binding`（场景主模型 / fallback / 专属配置）

```sql
CREATE TABLE model_scene_binding (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  scene_key       VARCHAR(64)  NOT NULL COMMENT '见 §4.4 枚举',
  primary_model   VARCHAR(128) NOT NULL COMMENT '须存在于 model_definition.model_name',
  fallback_model  VARCHAR(128) NULL COMMENT '可空；须存在且 enabled',
  extras          JSON         NULL COMMENT '场景专属：temperature/max_tokens/enable_thinking 等',
  enabled         TINYINT(1)   NOT NULL DEFAULT 1,
  tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
  remark          VARCHAR(256) NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_scene (tenant_id, scene_key)
);
```

### 4.4 场景枚举（`scene_key`）

| scene_key | 用途 | 说明 |
|-----------|------|------|
| `default` | 通用默认 | 未单独绑定的调用面；chat 用户未选模型时的缺省 |
| `chat` | 对话主循环 | `primary`/`fallback` 为系统缺省；**用户可选集合** = `user_selectable=1` 的定义（不必再维护池表） |
| `intent` | 意图分类 | 主模型 + fallback + extras（如更小 max_tokens） |
| `planner` | Planner / 规划类 LLM | 同上 |
| `rewrite.intent` | 路由域 query 改写 | 同上 |
| `rewrite.planner` | 规划域改写 | 同上 |
| `title` | 会话标题生成 | 同上 |
| `subagent` | spawn 未带 modelConfig 时 | 缺省子代理模型 |

后续 5.3 可扩展 `worker` / `plan` / `plan-phase` 等，**不改表结构**。

解析优先级（orchestrator / 调用方）：

```
显式 override（chat 会话 model_name / spawn modelConfigJson.model）
  → 该 scene_key 的 primary（失败走 fallback）
    → scene default 的 primary（再 fallback）
      → 仍无则 fail-fast（禁止再读 Nacos 模型名）
```

### 4.5 种子数据

迁入现有 4 模型：`deepseek-v4-pro` / `deepseek-v4-flash` / `qwen-plus` / `qwen-max`；capabilities：pro=`reasoning+tool_call`，其余 `tool_call`，均 `multimodal:false`；`user_selectable=1` 对全部 chat 可用模型。

场景种子（对齐当前 Nacos 习惯）：

| scene | primary | fallback |
|-------|---------|----------|
| `default` / `chat` | deepseek-v4-pro | qwen-plus |
| `intent` / `rewrite.*` / `title` / `planner` | deepseek-v4-flash | qwen-plus |
| `subagent` | deepseek-v4-flash | qwen-plus |

---

## 5. 密钥与加解密

- **密文存 DB**（`api_key_enc`）；管理 API 读回 **脱敏**（如 `sk-****` / `configured=true`），永不回传明文。
- **AES 密钥材料仅在 Nacos**（resource-manager + llm-gateway 同源配置），例如：

```yaml
# docs/nacos/sunshine-resource-manager.yaml 与 sunshine-llm-gateway.yaml 各保留一段
model:
  crypto:
    aes-key: ${MODEL_AES_KEY}   # 环境注入；禁止提交真实密钥
```

- `ModelCryptoService`：写入时 encrypt；gateway 拉 Catalog 后 decrypt 再调上游。
- 轮换：换 AES key 需批量重加密（运维脚本，本期可后置；文档标明风险）。
- **禁止**再在 Nacos 写 `${DEEPSEEK_API_KEY}` 明文默认值进 providers（providers 整段删除）。

---

## 6. resource-manager（管理面 + Catalog 发布）

### 6.1 API

| 端点 | 说明 |
|------|------|
| `GET/POST/PUT/DELETE /api/models/providers` | provider CRUD（api_key 仅写） |
| `GET/POST/PUT/DELETE /api/models/definitions` | 模型 CRUD；校验 `model_name` 租户唯一 |
| `POST /api/models/definitions/{id}/toggle` | 启用/停用 |
| `GET/PUT /api/models/scenes` | 场景绑定列表 / 批量或单条更新 |
| `GET /api/models/catalog` | **内网 Catalog**：definitions（无明文 key）+ providers 元数据 + scenes；供 orchestrator / 前端下拉 |
| `GET /api/models/catalog/gateway` | **内网**：含 `api_key_enc`，仅 llm-gateway 服务账号/内网调用 |

BFF：新增 `ModelManagerClient`（对齐 `AgentManagerClient`）透传 `/api/models/**`。

鉴权：与 `/api/agents` 同级（登录 + 管理角色）；`catalog/gateway` 不对浏览器开放（Gateway 路由限制或 mTLS/内网 DNS）。

### 6.2 热更新

CRUD 成功后 `StringRedisTemplate.convertAndSend("model-catalog-changed", tenantId)`（对齐 ToolCatalog）。  
llm-gateway / orchestrator 订阅后刷新本地 cache；刷新失败保留旧 snapshot 并告警。

启动：cache 为空则拉取 Catalog；仍空 → **fail-fast 拒绝启动**（无 Nacos 模型兜底）。

---

## 7. llm-gateway 改造

### 7.1 统一适配器

合并 `QwenAdapter` + `DeepSeekAdapter` → `OpenAiCompatibleAdapter`：
- `supports(model)`：**仅**注册表 `enabled` 模型（**禁止** `startsWith("deepseek-")` 前缀猜测，D9）；
- URI = `base_url + path_prefix + /chat/completions`；
- provider 变更后重建 WebClient（清 `LlmWebClientFactory` 缓存）。

`OpenAiRequestBodyFactory`：按 capabilities 裁剪——`reasoning=false` 剥离 `enable_thinking` / `reasoning_effort`；`tool_call=false` 且请求带 `tools` → **400** `model_not_tool_call`。

### 7.2 NormalizeFilter

| 场景 | 处理 |
|------|------|
| `reasoning:true` + `reasoning_content` | 透传 |
| 思考模型返回 `reasoning` / `thinking` | 重命名为 `reasoning_content` |
| `reasoning:false` 的任何 reasoning 字段 | **剥离** |
| 含 `image_url` 且 `multimodal:false` | 400 `model_not_multimodal` |

流式/非流式一致。

### 7.3 ModelRegistryCache + 路由

- 从 resource-manager `catalog/gateway` 加载；订阅 Redis 刷新；
- `ModelRouter`：先 Normalize 校验 → Adapter；fallback **优先**用场景链不再读 Nacos——网关侧对单次请求的 fallback 改为：请求头或 body 扩展可选 `fallback_model`，或按 **调用方已解析好的 primary→fallback** 重试（orchestrator 在失败时用 scene.fallback 再打一枪）。  
  **删除** `llm.fallback.routes`；原 pro→qwen-plus 迁到 `model_scene_binding`。
- `GET /v1/models`：从 cache 输出 `id` / `display_name` / `context_window` / `encoding` / `capabilities` / `provider` / `user_selectable`（**无密钥**）。

---

## 8. orchestrator：场景解析 + 会话 override

| 改动点 | 内容 |
|--------|------|
| `ModelSceneResolver` | 订阅 Catalog；`resolve(sceneKey, modelOverride)` → effectiveModel + extras + fallback |
| `AgentRunRequest` | 增加 `modelOverride`；与现有 `modelConfigJson` 优先级见下 |
| `ReActAgentFactory.buildModel` | override → scene(`chat`/`subagent`) → default |
| `IntentRouter` / `QueryRewriteService` / `Title` / Planner 调用点 | **固定 scene_key**，不吃 chat 会话 override |
| `LlmGatewayClient` | 所有 complete/stream 传入 effective model；去掉 `@Value agent.model.name` |
| `ModelWindowCache` / L1 | `windowFor(effectiveModel)`，窗口只来自注册表 |
| `chat_conversation` | 追加 `model_name VARCHAR(128) NULL` |

**优先级（D10）**：

```
spawn: modelConfigJson.model
  > 会话 modelOverride（仅 MAIN chat / Planner 主对话）
    > scene_binding.primary → fallback
      > default.primary → fallback
```

Intent / Rewrite / Title **忽略**会话 `modelOverride`，只走自身 scene。

BFF `ChatRequest.modelName`；会话级绑定，中途可改（轻提示跨模型续跑风险）。

删除 orchestrator Nacos 段：`agent.model.name|api-key|context-window`、`agent.intent.model`、`agent.planner.model`、`agent.rewrite.intent.model`、`agent.rewrite.planner.model`、`agent.title.model`。  
`agent.context.l1.default-model-window`：**删除**或改为「仅当注册表暂时缺该模型 meta 时的硬编码常量 128000」（推荐删除，缺 meta 则用该模型行的默认或拒绝压缩估算）。

---

## 9. 前端

### 9.1 `/models` 管理页（`ModelsView.vue`）

三栏或 Tab，对齐 `/tools` / `/agents` 风格（`--sun-*`，无灰底）：
1. **Providers**：base_url / path_prefix / api_key（写时明文，读时脱敏）/ enabled；
2. **Models**：display_name、context_window、capabilities 勾选、user_selectable、enabled、排序；
3. **Scenes**：表格编辑 scene_key → 主模型下拉 + fallback 下拉 + extras（JSON 或常用字段表单）。

### 9.2 配置点下拉化（一律 `/api/models/catalog` 或 `/v1/models`）

| 页面 | 改动 |
|------|------|
| Chat 底栏 | 模型下拉（仅 `user_selectable && enabled`）；多模态不符则禁用 + tooltip |
| `/models` | 管理 SSOT |
| Agents（`modelConfig`） | 模型字段改为下拉写入 `{"model":"..."}`（修正现 placeholder `modelName` 与后端 `model` 不一致） |
| Prompts 路由规则 | 若规则携带模型相关参数 → 下拉 |
| Knowledge KB rewrite.model | 下拉 |
| 场景绑定页 | 见 §9.1 Scenes（替代原 Nacos 各 `*.model`） |

系统级「intent 用哪个模型」**不再出现在 Nacos**，只在 `/models` → Scenes。

---

## 10. 错误处理

| 场景 | 行为 |
|------|------|
| 非多模态 + 图 | 前端禁用；直调 → 400 `model_not_multimodal` |
| 非 tool_call + tools | 400 `model_not_tool_call` |
| 非思考模型带 reasoning 字段 | gateway 剥离 |
| 会话绑定模型已停用/删除 | 回落 `chat`/`default` scene + 时间线 warning |
| primary 失败 | 自动尝试同 scene 的 `fallback_model`（仍失败再抛） |
| Catalog 启动为空 | fail-fast |
| AES key 未配置 | 启动失败（无法解密） |
| api_key 解密失败 / 上游 401 | 记日志 → 走 scene fallback |

---

## 11. 实施切片

| 切片 | 内容 | 验收焦点 |
|------|------|----------|
| **P0** | 表结构 + resource-manager CRUD/Catalog + 加密 + 种子；gateway Adapter 合并 + Normalize + RegistryCache；删 Nacos providers/fallback | M1/M3/M4；旧 YAML 模型段删除且 sync |
| **P1** | `model_scene_binding` + orchestrator `ModelSceneResolver`；去掉 orchestrator 全部 `*.model`；WindowCache 按 effectiveModel | 各 scene Live 调用模型与种子一致 |
| **P2** | 前端 `/models` + 各配置点下拉 + chat 会话 `model_name` + Agents 键名对齐 | M2/M5；UI 无手填模型名 |

---

## 12. 测试与验收

- **单测**：Crypto 加解密；`OpenAiCompatibleAdapter` path_prefix；Normalize 四类；scene 解析优先级；`model_name` 唯一约束冲突；
- **Live** `verify_model_registry_live.py`：
  - M1：`/models` 新增 provider+模型 → 不重启 → `/v1/models` 可见；
  - M2：chat 选模型对话 → tracer 中 model=所选；
  - M3：非多模态带图 → 400；
  - M4：reasoning 能力开关行为；
  - M5：停用会话模型 → scene 默认 + warning；
  - M6：intent scene 改 primary/fallback → 分类请求走新模型（与 chat 选择无关）；
  - M7：Nacos 中已无 `llm.providers` / `agent.model.name` 等键（配置漂移检查）；
- **回归**：phase2 suite、spawn/沙箱/HITL/peer Live。

---

## 13. 文件与配置变更索引

| 变更 | 位置 |
|------|------|
| 新建 `20-sunshine-model-registry.sql`（三表 + 种子） | `docker/mysql/init/` |
| `chat_conversation.model_name` | `11-sunshine-orchestrator.sql` |
| resource-manager：model 包（Entity/Repo/Admin/Scene/Crypto/Publisher） | `resource-manager/` |
| 删除 Nacos 模型段；新增 `model.crypto.aes-key` | `docs/nacos/sunshine-llm-gateway.yaml`、`sunshine-orchestrator.yaml`、`sunshine-resource-manager.yaml` → `sync_nacos.py` |
| llm-gateway：RegistryCache、OpenAiCompatibleAdapter、NormalizeFilter；删双 Adapter；无 JPA | `llm-gateway/` |
| orchestrator：ModelSceneResolver、modelOverride、去 `@Value` 模型名、WindowCache | `orchestrator/` |
| BFF：`ModelManagerClient` + `ChatRequest.modelName` | `bff/` |
| 前端：`ModelsView` + 各下拉 + Agents 键名 | `sunshine-ui/` |

---

## 14. 决策记录

| ID | 决策 |
|----|------|
| D1 | 模型 SSOT = MySQL（`sunshine_resource`），由 **resource-manager** 管理；Nacos 不含模型清单 |
| D2 | 单一 `OpenAiCompatibleAdapter`，差异全配置化 |
| D3 | 归一化在 llm-gateway，下游只认 `reasoning_content` |
| D4 | 用户会话 override 只影响 chat/主对话；intent/rewrite/title 等走 scene 绑定 |
| D5 | 本期仅 OpenAI 兼容协议族 |
| D6 | 会话模型中途可改（轻提示） |
| **D7** | 管理面在 resource-manager；llm-gateway 不引入 JPA，只消费 Catalog |
| **D8** | `model_name` 租户内全局唯一 |
| **D9** | 禁止按模型名前缀猜测 supports；未登记即不可用 |
| **D10** | 解析优先级：显式 override → scene primary/fallback → default；无 Nacos 回落 |
| **D11** | api_key 密文存 DB；AES 密钥材料仅 Nacos；读 API 脱敏 |
| **D12** | 场景绑定表替代原 Nacos 各 `*.model` 与 gateway `fallback.routes` |
| **D13** | chat 用户可选集 = `user_selectable=1`，不另建池表 |

---

## 15. 明确不做（本期）

- `model=auto` 权重路由（5.3）
- 计费配额（5.2）
- AES 密钥在线轮换工具（可后置脚本）
- embedding/rerank 注册
- 前缀兼容未登记模型
