# 多 Agent 架构 — 实施计划

> **Goal:** 建立可控的主子 Agent 体系 — L1 增强 → L3 Planner+动态 DAG 多子 Agent 主轴  
> **Spec:** `docs/superpowers/specs/2026-06-19-multi-agent-architecture-design.md`  
> **前置:** Workflow 2.9 `AgentNodeHandler` 已落地；Skills M1–M2（advanced-capabilities）建议同步

---

## 排期

```
周 1–2  M1–M3 AgentRuntime + 子 Agent skill/工具子集
周 3–4  M4–M5 Planner + 动态 DAG 多 agent 节点
周 5    M6–M7 审计 + 单测
周 6+   M8–M12（阶段四）delegate / 并行 / MsgHub / 前端展开
```

---

## Task M1: AgentRuntime 抽象

**Files:**
- Create: `orchestrator/.../agent/runtime/AgentRole.java`
- Create: `orchestrator/.../agent/runtime/AgentRunRequest.java`
- Create: `orchestrator/.../agent/runtime/AgentRuntime.java`
- Create: `orchestrator/.../agent/runtime/ReActAgentRuntime.java`
- Modify: `SunshineAgent.java` — 委托 `AgentRuntime`

- [ ] **M1.1** 定义 `MAIN | SUB | PLANNER` 三角色
- [ ] **M1.2** `ReActAgentRuntime` 实现 `run(AgentRunRequest)`
- [ ] **M1.3** `ReactExecutor` / `AgentNodeHandler` 改调 `AgentRuntime`
- [ ] **M1.4** 现有单测绿

**验收:** `mvn test -pl orchestrator -Dtest=AgentNodeHandlerTest` 绿

---

## Task M2: 子 Agent 工具子集 + Prompt Overlay

**Files:**
- Modify: `ReActAgentFactory.java` — `create(AgentRunRequest)`
- Modify: `DynamicToolkitFactory.java` — `build(List<String> toolWhitelist)`

- [ ] **M2.1** 子 Agent 仅注册 whitelist 内工具
- [ ] **M2.2** `systemOverlay` 追加到 sysPrompt
- [ ] **M2.3** 单测：whitelist 外工具不可调用

---

## Task M3: AgentNodeHandler 补齐 params

**Files:**
- Modify: `AgentNodeHandler.java`
- Modify: `docs/nacos/sunshine-workflows.yaml` — `finance-smart` agent 节点加 `skill`

- [ ] **M3.1** 解析 `skill`, `tools`, `maxIters`, `systemOverlay`
- [ ] **M3.2** 构建 `AgentRunRequest(SUB, ...)`
- [ ] **M3.3** `AgentNodeHandlerTest` 覆盖 skill 路径

---

## Task M4: PlannerAgentRuntime

**Files:**
- Create: `orchestrator/.../agent/runtime/PlannerAgentRuntime.java`
- Create: `orchestrator/.../plan/WorkflowPlanner.java`
- Create: `orchestrator/.../plan/PlanJson.java`

- [ ] **M4.1** Planner 专用 **`agent.planner.model: deepseek-v4-flash`**
- [ ] **M4.2** 输出 `PlanJson`（nodes + edges）
- [ ] **M4.3** Timeline `plan` 步展示节点链摘要
- [ ] **M4.4** 失败 fallback → react

**验收:** 单测：给定 query + catalog，Planner 输出合法 JSON

---

## Task M5: 动态 DAG 调度多子 Agent

**Files:**
- Create: `orchestrator/.../plan/DAGValidator.java`
- Create: `orchestrator/.../execution/DynamicWorkflowExecutor.java`
- Create: `orchestrator/.../routing/ExecutionMode.java` — 增加 `PLAN_WORKFLOW`
- Create: `orchestrator/.../execution/PlanWorkflowExecutor.java`
- Create: `orchestrator/.../plan/ExecutionPlanEntity` + Flyway `Vx__execution_plan.sql`
- Create: `orchestrator/.../plan/ExecutionPlanController.java`
- Modify: `ExecutionDispatcher.java` — `PLAN_WORKFLOW` 分支
- Nacos: `agent.planner.model: deepseek-v4-flash`

- [ ] **M5.1** Validator：skill 白名单（skill-manager catalog）、无环、≤8 节点
- [ ] **M5.2** Plan → `WorkflowDefinition` 物化；**`execution_plan` 表持久化** draft→validated→running→completed
- [ ] **M5.3** `GET /api/execution-plans/{planId}` 回放节点 trace
- [ ] **M5.4** 含 2 个 `agent` 节点的 Plan 端到端演示

**验收:** 「制度+财务+合规」三 agent 节点 Plan 执行成功

---

## Task M6: sub_agent_run 审计

**Files:**
- Create: `orchestrator/.../audit/SubAgentAuditEvent.java`
- Modify: `AuditService.java` 或新建 `SubAgentAuditService`

- [ ] **M6.1** 子 Agent 完成时发 `sub_agent_run` 事件
- [ ] **M6.2** payload：runId, skillId, toolCalls, outputSummary
- [ ] **M6.3** `GET /api/audit/sub-runs?messageId=` 查询

---

## Task M7: 记忆隔离验证

- [ ] **M7.1** 子 Agent 不写主 `reasoning` 字段（集成测试）
- [ ] **M7.2** 子 Agent 不注入 STM 完整轮次（日志断言）

---

### Task S1: skill-manager 服务

**Files:**
- Create: `skill-manager/` Maven 模块 :8225
- Create: `docs/nacos/sunshine-skill-manager.yaml`
- Flyway: `skill_definition`, `skill_version`

- [ ] **S1.1** CRUD + `POST /api/skills/{id}/upload`
- [ ] **S1.2** `GET /api/skills/catalog` 供 orchestrator 拉取
- [ ] **S1.3** 内置示例 skill `finance-analysis` 种子数据

### Task S3: 前端 `/skills` 页

**Files:**
- Create: `sunshine-ui/src/views/SkillsView.vue`
- Modify: router + BFF 透传

- [ ] **S3.1** 列表 + 上传 SKILL.md/zip
- [ ] **S3.2** 编辑 + 版本发布
- [ ] **S3.3** 工具绑定（读 tool-manager catalog）

---

| Task | 内容 |
|------|------|
| M8 | `DelegateSkillTool` — react 主 Agent 委派子 Skill |
| M9 | 前端 `node-*` 展开子 Timeline |
| M10 | `ParallelAgentNodeHandler` fan-out/join |
| M11 | MsgHub Peer + transcript 审计 |
| M12 | 子调子默认禁止开关 |

---

## Spec Coverage

- [ ] 主/子/Planner 三角色契约
- [ ] L1 增强 + L3 主轴
- [ ] Timeline 双层模型
- [ ] 与 Skills、动态 DAG spec 交叉引用一致
