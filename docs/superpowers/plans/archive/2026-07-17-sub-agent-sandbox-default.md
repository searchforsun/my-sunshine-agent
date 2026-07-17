> **ARCHIVED**（2026-07-17 · DOC-022）— 实现已完成；日常 SSOT 见 [`docs/sandbox/README.md`](../../../sandbox/README.md)。勿按本文继续改代码。

# SUB 默认沙箱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Workflow/Plan agent 子节点（SUB）默认注入 `sandbox__*` 六工具，并透传 `conversationId` 复用对话级容器；HITL 继承本轮 `writeHitlMode`。

**Architecture:** 方案 A — `DynamicToolkitFactory` SUB 与 MAIN 同样 register 沙箱工具；`AgentRunRequest.sub` + assembler 透传 `conversationId`；lifecycle/HITL 不改。

**Tech Stack:** Java / Spring / AgentScope Toolkit / JUnit5 / Nacos YAML

**Spec:** [2026-07-17-sub-agent-sandbox-default-design.md](../../specs/2026-07-17-sub-agent-sandbox-default-design.md)

---

### Task 1: Toolkit SUB 注入沙箱

**Files:**
- Modify: `orchestrator/.../DynamicToolkitFactory.java`
- Modify: `orchestrator/.../DynamicToolkitFactoryTest.java`

- [x] 改写 `buildForSubAgent_doesNotRegisterSandboxTools` → `buildForSubAgent_registersSandbox_withoutManageTasks`（期望含 SandboxIds.ALL，不含 manage_tasks）
- [x] 跑单测确认 RED
- [x] `buildFromWhitelist`：SUB 也注入 sandbox（TaskBoard 仍仅 MAIN）
- [x] 单测 GREEN

### Task 2: conversationId 透传

**Files:**
- Modify: `AgentRunRequest.java`（`sub(...)` 增加 conversationId）
- Modify: `AgentNodeRequestAssembler.java`
- Modify: 相关单测（`AgentRunRequestTest` / `AgentNodeHandlerTest`）

- [x] 失败单测：assembler 产出的 req.conversationId() == streamCtx
- [x] 实现透传；兼容旧 `sub(...)` 重载（conversationId=null）
- [x] GREEN

### Task 3: Nacos 文案 + 文档联动

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Modify: 方案 B spec B4 / 子 Agent 行、`docs/sandbox/README.md`、本 spec 状态
- Run: `python scripts/sync_nacos.py`（若需重启 orchestrator）

- [x] Nacos + sync + 文档

### Task 4: 验收

- [x] `mvn test -pl orchestrator -Dtest=DynamicToolkitFactoryTest,AgentRunRequestTest` + handler 相关断言
- [x] （可选）Live S4 → G12 `#sandbox-agent`