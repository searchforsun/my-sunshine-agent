# 阶段二：标杆打通 — 技术设计（SSOT）

> **周期**：8 周 MVP + 2.9 Workflow（~2 周）+ 收尾 2.10–2.16（~3–4 周）  
> **状态**：✅ 已完成  
> **目标**：财务智能助手端到端；三模式编排；RAG 评测基线；Timeline V2

---

## 1. 任务总览

### 1.1 MVP（2.1–2.8）

| 任务卡 | 内容 | 状态 |
|--------|------|:----:|
| **2.1** | Auth Center：Sa-Token JWT + Gateway 鉴权 | ✅ |
| **2.2** | Tool Manager：`ToolRegistry` + `ToolHandler` | ✅ |
| **2.3** | Finance Service：消息 Mock API | ✅ |
| **2.4** | 端到端串联 + `phase2_agent_demo.py` | ✅ |
| **2.5** | 脱敏：正则 MVP（手机号/身份证） | ✅ |
| **2.6** | 多厂商路由 + 进程内 `AdapterCircuitBreaker` | ✅ |
| **2.7** | RocketMQ 审计 → MySQL/ES | ✅ |
| **2.8** | 联调脚本与问题修复 | ✅ |

### 1.2 Workflow 编排（2.9）

| 任务卡 | 内容 | 状态 |
|--------|------|:----:|
| **2.9.1** | `ExecutionDispatcher` 三模式 + `WorkflowCatalog` | ✅ |
| **2.9.2** | `WorkflowExecutor` 线性 DAG（start/rag/tool/llm/agent/answer） | ✅ |
| **2.9.3** | Nacos `sunshine-workflows.yaml` SSOT | ✅ |
| **2.9.4** | `ToolRegistry` 替代硬编码 | ✅ |
| **2.9.5** | Timeline 统一（node-* / think / tool） | ✅ |
| **2.9.6** | `DynamicToolkit` + `RemoteToolProxy`（react 白名单） | ✅ |

### 1.3 收尾（2.10–2.16）

| 任务卡 | 内容 | 状态 |
|--------|------|:----:|
| **2.10** | Live：`phase2_agent_demo.py --suite all` | ✅ |
| **2.11** | 删除 Legacy + Rag 统一路径 | ✅ |
| **2.12** | RAG：rebuild API + 语料 + golden-set v5 + `rag_eval` 基线 | ✅ |
| **2.13** | 审计 payload `stepsSummary` / `toolNames` | ✅ |
| **2.14** | `RuleBasedRouter` 规则硬路由 | ✅ |
| **2.15** | react 白名单 vs Catalog 启动校验 | ✅ |
| **2.16** | CLAUDE / rag README 同步 | ✅ |

### 1.4 横切能力（2.17–2.18）

| 任务卡 | 内容 | 状态 |
|--------|------|:----:|
| **2.17** | Agent 记忆：LTM/MTM/STM + `MemoryMessageBuilder` | ✅ |
| **2.18** | Timeline V2：步骤三态 `summary.{before,active,after}` + `lifecycle` | ✅ |

---

## 2. 三模式编排（2.9 要点）

```
IntentRouter → ExecutionDispatcher
  ├─ simple-llm
  ├─ workflow（静态 Nacos YAML）
  └─ react（ReActAgent + Catalog 工具）
```

- Workflow 节点：`rag` / `tool` / `llm` / `agent` / `answer`
- 子 Agent：`AgentNodeHandler` → `AgentRunRequest.sub` → `AgentRuntime`；主 Timeline 仅 `node-{id}` 一步；上下文 SSOT 见 multi-agent plan §子 Agent 实现目标
- 配置：`docs/nacos/sunshine-workflows.yaml`（catalog + definitions）

详设历史稿：`2026-06-18-workflow-orchestration-design.md`

---

## 3. RAG 基线（2.12 要点）

- Admin：`POST /api/rag/admin/rebuild`
- 语料：`docs/knowledge/*.md`（阶段二收尾扩至 11 篇）
- 评测集：`docs/rag/golden-set.yaml` **v5**（123 条，含 multihop + 负例）
- 脚本：`rag_reset.py` → `rag_ingest_bulk.py` → `rag_eval.py --gate`
- 报告：`docs/rag/baseline-report.md`

---

## 4. Timeline V2（2.18 要点）

- SSE：`type:step` / `type:step_delta`；仅下发当前阶段一行 summary
- 步骤含 `label`（来自 Catalog / Nacos `agent.timeline.*`）
- 前端：`OperationStack` / `OperationCard`；禁止本地步骤话术 Map

详设历史稿：`2026-06-13-processing-timeline-v2-design.md`

---

## 5. 检查门

### 5.1 MVP

- [x] JWT 无效 → 401
- [x] Agent 调用财务工具成功
- [x] 脱敏过滤手机号/身份证
- [x] LLM 故障切换备用模型
- [x] 审计日志完整

### 5.2 Workflow 2.9

- [x] 意图输出 `simple-llm | workflow | react` + workflowId
- [x] knowledge-qa / finance-list / finance-smart 可配置
- [x] `mvn test -pl orchestrator` 绿

### 5.3 收尾

- [x] `rag_eval.py --gate` 全 PASS（v5）
- [x] Legacy 已删；`--suite all` PASS

---

## 6. 已知缺口（移交阶段三）

| 缺口 | 阶段三任务 |
|------|------------|
| 脱敏 AhoCorasick | **3.13**（并行） |
| Sentinel Dashboard | **3.5** |
| tool 细项审计 ES | **3.6** |
| 混合检索 / Rerank | **3.4** |
| 多租户 / HITL | **3.2** / **3.3** |
| PLAN_WORKFLOW / Skills | **3.9**–**3.12** |

---

## 7. 下一步

进入 [阶段三：生产加固](./phase3-production-hardening-design.md)

历史详设：`2026-06-20-phase2-closure-design.md`
