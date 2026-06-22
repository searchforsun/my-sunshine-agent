# 阶段三：多 Agent / Skills — 实施计划

> **SSOT 设计：** [phase3-production-hardening-design.md](../specs/phase3-production-hardening-design.md)  
> **历史详设：** [2026-06-19-multi-agent-architecture-design.md](../specs/2026-06-19-multi-agent-architecture-design.md)  
> **总排期：** 见 [phase3-production-hardening.md](./2026-06-19-phase3-production-hardening.md)（与 3.4 RAG 周级并行）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans。  
> **覆盖度：** 见 [2026-06-20-phased-implementation-coverage.md](./2026-06-20-phased-implementation-coverage.md)

**Goal:** L1 增强（skill 子集）→ L3 主轴（PLAN_WORKFLOW + 多子 Agent）。

**实现状态（2026-06-22）：** **3.10.1 ✅** · **3.9 / 3.10.2+ / 3.11 / 3.12 ⬜ 未开始**。现有 `AgentNodeHandler` 已改调 `AgentRuntime`；**不含** `PLAN_WORKFLOW` / `skill-manager` 模块。

**排期偏差：** 原周 1–2 应与 3.4 并行启动 3.10.1–3.10.3；RAG 已提前完成，**多 Agent 主线滞后**，当前迭代优先补 3.10.1 → 3.11 → 3.9。

**Architecture:** `AgentRuntime` 统一 MAIN/SUB/PLANNER；Planner 产出 Plan JSON；`skill-manager` Catalog 驱动 agent 节点白名单。

**Tech Stack:** JDK 21, AgentScope-Java 1.0.7, Spring Boot, Vue3, MySQL Flyway

**SSOT 设计:** [phase3-production-hardening-design.md](../specs/phase3-production-hardening-design.md)  

---

## 编号对照（旧 → 新）

| 旧编号 | 新编号 | 说明 |
|--------|--------|------|
| M1 | **3.10.1** | AgentRuntime |
| M2 | **3.10.2** | 工具白名单 + overlay |
| M3 | **3.10.3** | AgentNodeHandler params |
| M4 | **3.10.4** | PlannerAgentRuntime |
| M5 | **3.9.1–3.9.4** + **3.10.5** | PLAN_WORKFLOW 落库/API + DynamicExecutor |
| M6 | **3.10.6**（审计汇总见 **3.6**） | sub_agent_run |
| M7 | **3.10.7** | 记忆隔离 |
| S1 | **3.11.1–3.11.3** | skill-manager 服务 |
| S2 | **3.11.4** | SkillCatalogService |
| S3 | **3.12.1–3.12.3** | `/skills` UI |
| — | **3.12.4** | Plan 详情页 |
| M8–M12 | **4.7.1–4.7.4** | 阶段四 |

---

## 排期（嵌入阶段三 8 周）

```
周 1    3.10.1 AgentRuntime
周 2    3.10.2–3.10.3 子 Agent 子集 + workflow skill 节点
周 3    3.11.1–3.11.4 skill-manager（阻塞 3.9）
周 6    3.10.4 + 3.9.1–3.9.4 + 3.10.5 PLAN_WORKFLOW
周 7    3.10.6 + 3.6 审计（sub_agent / plan.*）
周 8    3.10.7 + 3.12.1–3.12.4 前端
```

---

## 3.10 多 Agent 运行时

### 3.10.1 AgentRuntime 抽象

**Files:**
- Create: `orchestrator/.../agent/runtime/AgentRole.java`
- Create: `orchestrator/.../agent/runtime/AgentRunRequest.java`
- Create: `orchestrator/.../agent/runtime/AgentRuntime.java`
- Create: `orchestrator/.../agent/runtime/ReActAgentRuntime.java`
- Modify: `ReactExecutor` / `AgentNodeHandler` — 直接注入 `AgentRuntime`（已删除 `SunshineAgent` 门面）

- [x] **3.10.1a** 定义 `MAIN | SUB | PLANNER` 三角色
- [x] **3.10.1b** `ReActAgentRuntime.run(AgentRunRequest)`
- [x] **3.10.1c** `ReactExecutor` / `AgentNodeHandler` 改调 `AgentRuntime`
- [x] **3.10.1d** 现有单测绿

**验收:** `mvn test -pl orchestrator -Dtest=AgentNodeHandlerTest` 绿

---

### 3.10.2 子 Agent 工具子集 + Prompt Overlay

**Files:**
- Modify: `ReActAgentFactory.java` — `create(AgentRunRequest)`
- Modify: `DynamicToolkitFactory.java` — `build(List<String> toolWhitelist)`

- [ ] **3.10.2a** 子 Agent 仅注册 whitelist 内工具
- [ ] **3.10.2b** `systemOverlay` 追加到 sysPrompt
- [ ] **3.10.2c** 单测：whitelist 外工具不可调用

---

### 3.10.3 AgentNodeHandler 补齐 params

**Files:**
- Modify: `AgentNodeHandler.java`
- Modify: `docs/nacos/sunshine-workflows.yaml` — `finance-smart` agent 节点加 `skill`

- [ ] **3.10.3a** 解析 `skill`, `tools`, `maxIters`, `systemOverlay`
- [ ] **3.10.3b** 构建 `AgentRunRequest(SUB, ...)`
- [ ] **3.10.3c** `AgentNodeHandlerTest` 覆盖 skill 路径

---

### 3.10.4 PlannerAgentRuntime

**Files:**
- Create: `orchestrator/.../agent/runtime/PlannerAgentRuntime.java`
- Create: `orchestrator/.../plan/WorkflowPlanner.java`
- Create: `orchestrator/.../plan/PlanJson.java`
- Nacos: `agent.planner.model: deepseek-v4-flash`

- [ ] **3.10.4a** Planner 专用 flash 模型
- [ ] **3.10.4b** 输出 `PlanJson`（nodes + edges）
- [ ] **3.10.4c** Timeline `plan` 步展示节点链摘要
- [ ] **3.10.4d** 失败 fallback → react

**验收:** 单测：给定 query + catalog，Planner 输出合法 JSON

---

### 3.10.5 DynamicWorkflowExecutor

**Files:**
- Create: `orchestrator/.../plan/DAGValidator.java`
- Create: `orchestrator/.../execution/DynamicWorkflowExecutor.java`

- [ ] **3.10.5a** Validator：skill 白名单、无环、≤8 节点
- [ ] **3.10.5b** Plan → `WorkflowDefinition` 物化
- [ ] **3.10.5c** 含 2+ `agent` 节点端到端演示

**验收:** 「制度+财务+合规」三 agent 节点 Plan 执行成功

---

### 3.10.6 sub_agent_run 审计

> 与 **3.6** tool-audit / plan.* 同周联调。

**Files:**
- Create: `orchestrator/.../audit/SubAgentAuditEvent.java`
- Modify: `AuditService` 或新建 `SubAgentAuditService`

- [ ] **3.10.6a** 子 Agent 完成时发 `sub_agent_run` 事件
- [ ] **3.10.6b** payload：runId, skillId, toolCalls, outputSummary
- [ ] **3.10.6c** `GET /api/audit/sub-runs?messageId=` 查询

---

### 3.10.7 记忆隔离验证

- [ ] **3.10.7a** 子 Agent 不写主 `reasoning`（集成测试）
- [ ] **3.10.7b** 子 Agent 不污染 STM（日志断言）

---

## 3.9 PLAN_WORKFLOW（编排与持久化）

### 3.9.1 执行模式与路由

**Files:**
- Modify: `ExecutionMode.java` — 增加 `PLAN_WORKFLOW`
- Create: `orchestrator/.../execution/PlanWorkflowExecutor.java`
- Modify: `ExecutionDispatcher.java` — 第四分支
- Modify: `IntentRouter` / `RuleBasedRouter` — 可输出 `plan-workflow`

- [ ] **3.9.1a** `PLAN_WORKFLOW` 与 simple/workflow/react 平级
- [ ] **3.9.1b** IntentRouter 路由 `plan-workflow` 单测

---

### 3.9.2 Plan 持久化

**Files:**
- Create: `orchestrator/.../plan/ExecutionPlanEntity.java`
- Flyway: `Vx__execution_plan.sql`
- Modify: `chat_message` — `execution_plan_id` 外键

- [ ] **3.9.2a** 状态机 draft→validated→running→completed|failed
- [ ] **3.9.2b** `plan_json` / `execution_trace` 字段

---

### 3.9.3 Plan 回放 API

**Files:**
- Create: `orchestrator/.../plan/ExecutionPlanController.java`
- Modify: BFF 透传

- [ ] **3.9.3a** `GET /api/execution-plans/{planId}`
- [ ] **3.9.3b** `GET /api/execution-plans?conversationId=`
- [ ] **3.9.3c** `GET /api/execution-plans/{planId}/nodes`
- [ ] **3.9.3d** Timeline `planId` / `planNodeId`；SkyWalking span `plan.*`

---

### 3.9.4 plan.* 审计事件

- [ ] **3.9.4a** RocketMQ：`plan.created` / `plan.validated` / `plan.completed` / `plan.failed`

---

## 3.11 skill-manager

**Files:**
- Create: `skill-manager/` Maven 模块 :8225
- Create: `docs/nacos/sunshine-skill-manager.yaml`
- Flyway: `skill_definition`, `skill_version`

- [ ] **3.11.1** CRUD + `POST /api/skills/{id}/upload`
- [ ] **3.11.2** `GET /api/skills/catalog`
- [ ] **3.11.3** 种子 skill：`finance-analysis`、`policy-review`、`compliance-check`
- [ ] **3.11.4** orchestrator `SkillCatalogService` HTTP 拉取 + 缓存

---

## 3.12 前端

**Files:**
- Create: `sunshine-ui/src/views/SkillsView.vue`
- Create: Plan 详情页/抽屉
- Modify: router + BFF 透传

- [ ] **3.12.1** `/skills` 列表 + 上传 SKILL.md/zip
- [ ] **3.12.2** 编辑 overlay + 版本发布
- [ ] **3.12.3** 工具绑定（tool-manager catalog）
- [ ] **3.12.4** Timeline `plan` 步跳转 Plan 详情

---

## 阶段四（原 M8–M12 → 4.7）

| 新编号 | 内容 |
|--------|------|
| **4.7.1** | `DelegateSkillTool` — react Coordinator 委派 |
| **4.7.2** | `ParallelAgentNodeHandler` fan-out/join |
| **4.7.3** | MsgHub Peer + transcript 审计 |
| **4.7.4** | 前端子 Agent Timeline 展开 |

详设：[phase4-platformization-design.md](../specs/phase4-platformization-design.md) §4.7

---

## Spec Coverage

- [x] 任务编号与 phase3 SSOT §3.9–3.12 一致
- [x] 旧 M/S 编号对照表
- [x] 3.11 阻塞 3.9 依赖已标注
- [ ] 实施时以 phase3 §6 检查门为最终验收
