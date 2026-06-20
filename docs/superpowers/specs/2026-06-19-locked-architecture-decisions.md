# 架构锁定决策（2026-06-19）

> **⚠️ 阶段三相关决策已摘要至** [phase3-production-hardening-design.md](./phase3-production-hardening-design.md) **§3.9–3.11**；OCR 见 [phase4-platformization-design.md](./phase4-platformization-design.md) **§4.2**。下文为完整锁定原文。

> **状态**：已锁定 — 后续 spec / 实施计划须与本文件一致  
> **适用范围**：动态 Plan、Planner 模型、Skills 运营、沙箱运行时、**OCR 提供商**

---

## D1. 动态 Plan = 独立 `ExecutionMode.PLAN_WORKFLOW`

### 决策

- 新增顶层执行模式 **`PLAN_WORKFLOW`**（对外标签 `plan-workflow`），与 `simple-llm` / `workflow` / `react` **平级**，由 `ExecutionDispatcher` 第四分支分发。
- **禁止**使用 `workflow + dynamic: true` 隐式表达动态 Plan。

### 路由

```java
public enum ExecutionMode {
    SIMPLE_LLM,
    WORKFLOW,       // 静态 Nacos YAML
    PLAN_WORKFLOW,  // Planner 运行时生成 DAG
    REACT;
}
```

`IntentRouter` / 规则路由输出示例：

```json
{"mode":"plan-workflow","workflowId":null,"params":{},"reason":"需组合制度检索与财务分析"}
```

### 持久化与可观测（阶段三必做，非阶段四）

动态 Plan **必须落库、可查询、可回放**，不能只存内存。

#### 数据模型（MySQL）

```sql
-- Flyway: Vx__execution_plan.sql
CREATE TABLE execution_plan (
    id              VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    message_id      VARCHAR(36) NOT NULL,   -- 关联 assistant 占位消息
    user_id         VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) DEFAULT 'default',
    status          VARCHAR(24) NOT NULL,   -- draft|validated|running|completed|failed|rejected
    planner_model   VARCHAR(64),
    planner_reason  VARCHAR(512),
    plan_json       MEDIUMTEXT NOT NULL,    -- Planner 原始输出
    validated_json  MEDIUMTEXT,             -- Validator 修正后（可选）
    execution_trace MEDIUMTEXT,             -- 节点执行摘要 JSON 数组
    trace_id        VARCHAR(64),              -- SkyWalking traceId
    created_at      TIMESTAMP NOT NULL,
    validated_at    TIMESTAMP NULL,
    started_at      TIMESTAMP NULL,
    completed_at    TIMESTAMP NULL,
    INDEX idx_conv (conversation_id),
    INDEX idx_msg (message_id)
);
```

`chat_message` 增加 `execution_plan_id` 外键（可空）。

#### 状态机

```
draft → validated → running → completed
              ↘ rejected    ↘ failed
```

- **draft**：Planner 产出，待 Validator
- **validated**：通过白名单/无环/技能校验
- **running**：`DynamicWorkflowExecutor` 执行中
- **completed / failed**：终态，触发审计

#### API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/execution-plans/{planId}` | Plan 详情 + 节点 trace |
| GET | `/api/execution-plans?conversationId=` | 会话下历史 Plan 列表 |
| GET | `/api/execution-plans/{planId}/nodes` | 节点级执行明细（运维回放） |

#### 可观测联动

| 层 | 内容 |
|----|------|
| Timeline | `plan` 步 `detail` 含 `planId` + 节点链摘要；每 `node-*` 关联 `planNodeId` |
| 审计 | RocketMQ：`plan.created` / `plan.validated` / `plan.completed` / `plan.failed` |
| Trace | Span：`plan.planner`、`plan.validate`、`plan.node.{nodeId}` |
| 前端 | 消息时间线 `plan` 步可点击跳转 Plan 详情（阶段三末） |

---

## D2. Planner 使用专用 Flash 模型

### 决策

- Planner **独立模型**，与主 ReAct Agent 模型分离。
- 默认模型：**`deepseek-v4-flash`**（与 intent classifier 同级，可单独配置）。

### Nacos 配置（`sunshine-orchestrator.yaml`）

```yaml
agent:
  planner:
    model: deepseek-v4-flash
    temperature: 0
    max_tokens: 1024
    prompt: |
      你是 Workflow Planner。根据用户问题与可用 workflow 目录、skill 目录、工具目录，
      输出一行 JSON 表示执行计划（nodes + edges）。禁止 markdown，禁止面向用户的答复。
```

### 原则

| 角色 | 模型 | 说明 |
|------|------|------|
| Intent 分类 | `agent.intent.model` (flash) | 选 mode |
| **Planner** | **`agent.planner.model` (flash)** | 产出 Plan JSON |
| 主 ReAct | `agent.model.name` (pro) | 用户可见推理质量 |
| 子 Agent | 可配置，默认 flash | Bounded 子任务 |

---

## D3. Skills 服务端管理 + 前端运营页

### 决策

- Skills **不以 Nacos YAML 为唯一 SSOT**；改为 **服务端 DB + 文件存储**，支持上传、版本、启停。
- 前端新增 **Skills 管理页** `/skills`（与 `/knowledge` 同级）。

### 服务边界

| 方案 | 结论 |
|------|------|
| 新建 `skill-manager` :8225 | **推荐** — 与 tool-manager 对称 |
| 并入 orchestrator | 不采用 — 职责混杂 |

### 存储

```
skill-manager
├── MySQL skill_definition（元数据 + 状态）
├── MySQL skill_version（版本历史）
└── 文件存储 data/skills/{skillId}/{version}/SKILL.md（+ 可选 assets/）
```

**上传格式**：

- 单文件 `SKILL.md`（Cursor Skill 兼容结构）
- 或 zip 包：`SKILL.md` + `references/` + `scripts/`（scripts 仅元数据登记，执行仍走 Docker 沙箱）

### API（skill-manager）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/skills/catalog` | orchestrator 拉取（等同 Tool Catalog） |
| GET | `/api/skills` | 列表（管理端） |
| POST | `/api/skills` | 创建元数据 |
| POST | `/api/skills/{id}/upload` | 上传 SKILL.md 或 zip |
| PUT | `/api/skills/{id}/enable` | 启用/禁用 |
| GET | `/api/skills/{id}/versions` | 版本历史 |
| POST | `/api/skills/{id}/publish` | 发布版本 → 对 runtime 可见 |

`SkillCatalogService`（orchestrator）从 `skill-manager` HTTP 拉取并缓存，**禁止**前端维护 skill 话术 Map。

### 前端 `/skills` 页面

| 功能 | 说明 |
|------|------|
| 列表 | id、displayName、状态、工具数、是否含沙箱 |
| 上传 | 拖拽 SKILL.md / zip |
| 编辑 | 在线编辑 overlay prompt（Markdown） |
| 版本 | 查看 diff、回滚 |
| 调试 | 绑定工具白名单预览、试跑子 Agent（阶段四） |

技术栈：Vue3 + Naive UI，与 `knowledge` 页一致；BFF 透传 skill-manager。

### Skill 与 Tool 关系

- **独立目录**，不复用 `ToolCatalog` 的 `kind=skill`。
- Skill 条目引用 Tool id 列表；Tool 仍由 tool-manager 注册。

---

## D4. 沙箱 = Docker（自建）

### 决策

- 沙箱运行时 **采用 Docker**，不采用 E2B/Modal 等托管沙箱作为首实现。
- 镜像：内置 `sunshine-sandbox-python:3.11-slim`（pandas、regex，无网络）。

### 组件

```
skill-manager 或 sandbox-service (:8226)
└── SandboxExecutor
    ├── DockerClient（docker-java 或 ProcessBuilder docker run）
    ├── 容器池（warm 0~N，默认每次 fresh --rm）
    └── 策略来自 skill.sandbox_policy
```

### 默认策略

```yaml
sandbox_policy:
  runtime: docker
  image: sunshine-sandbox-python:3.11-slim
  timeout_sec: 30
  memory_mb: 256
  cpus: 0.5
  network: none
  read_only_rootfs: true
  cap_drop: [ALL]
```

### 运维

- `scripts/start.py` 增加 Docker 可用性检查
- 文档补充：Windows 开发机需 Docker Desktop；生产 ecs 需 Docker daemon

---

## D8. OCR / 文档解析首选千问（DashScope）

### 决策

- 阶段四 **4.DOC** OCR 与文档解析 **默认且唯一厂商路线**：**千问 / 阿里云 DashScope**（与现有 `text-embedding-v4`、LLM Gateway 通义适配器同一账号体系）。
- **电子版 PDF** 可先本地抽文本层（pdfbox），**扫描件 / 图片 / 抽层失败** 必须走千问 OCR API。
- **L3 Vision 对话** 优先 **Qwen-VL** 经 LLM Gateway，不默认 GPT-4o 等外厂 vision。
- **不默认** PaddleOCR、百度/腾讯 OCR；灾备切换需显式 Nacos 开关与变更评审。

### Nacos SSOT（`sunshine-rag.yaml`）

```yaml
rag:
  ocr:
    provider: qwen
    api-key: ${OCR_API_KEY:${QWEN_API_KEY:${DASHSCOPE_API_KEY}}}
    base-url: https://dashscope.aliyuncs.com/api/v1
    model: qwen-vl-ocr-latest
    doc-model: qwen-doc-parse
```

### 审计

- 入库记录 `ocrProvider: qwen`、`model`、`pageCount`；便于计费与问题追溯。

详设：`2026-06-21-multimodal-ocr-design.md` §3.1。

---

## 对实施计划的影响

| 原任务 | 调整 |
|--------|------|
| D6 Plan 落库 | **提前至阶段三 3.9**（与 PLAN_WORKFLOW 同步） |
| S1 SkillRegistry | 改为 **skill-manager 服务** + DB，阶段三末 |
| S6 Skill 运营 UI | **阶段三末/四初**，路由 `/skills` |
| S4 Sandbox | **明确 Docker**，可放在 skill-manager 或独立 sandbox-service |
| ExecutionMode | 阶段三增加 `PLAN_WORKFLOW` 枚举与 Dispatcher 分支 |

---

## 相关文档

- `2026-06-19-advanced-capabilities-design.md`
- `2026-06-19-multi-agent-architecture-design.md`
- `implementation-plan.md`
