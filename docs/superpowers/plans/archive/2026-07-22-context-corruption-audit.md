# Context Corruption Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 规则 + LLM 审计 L2/L1 矛盾与腐败：抽取后轻量检查 + 小时维护批量；明确 void、暧昧 conflict 不注入。

**Architecture:** 新增 `ContextAuditService`；规则去重同 key active；Catalog `context.l2.audit` / `context.l1.audit`；`L2ExtractService` 成功后 debounce 异步审计；`ContextMaintenanceService.runOnce` 末尾批量调用。

**Tech Stack:** Java 21 / Spring / JPA / Prompt Catalog / Nacos / Vue Admin

**Spec:** `docs/superpowers/specs/2026-07-22-context-corruption-audit-design.md`

---

### Task 1: 配置与 Admin 允许 conflict

**Files:**
- Modify: `orchestrator/.../ContextProperties.java`（Maintenance 增加 audit* 字段）
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Modify: `orchestrator/.../ContextAdminService.java`（ALLOWED_STATUS + conflict）
- Modify: `sunshine-ui/src/views/ContextView.vue`（状态选项/标签）

- [x] Step 1: 扩展 Maintenance 配置与 Nacos
- [x] Step 2: Admin API/UI 支持 conflict
- [x] Step 3: sync_nacos

### Task 2: 规则快扫 + 单测

**Files:**
- Create: `orchestrator/.../context/audit/ContextAuditService.java`
- Create: `orchestrator/.../context/audit/ContextAuditServiceTest.java`

- [x] Step 1: 写 `dedupeActiveSameKey_voidsLosers` 单测
- [x] Step 2: 实现规则快扫并通过单测

### Task 3: LLM L2/L1 审计 + 抽取钩子

**Files:**
- Modify: `ContextAuditService`（LLM + apply + debounce）
- Modify: `L2ExtractService`（extract 末尾触发）
- Modify: `docker/mysql/init/17-sunshine-prompt-manager.sql`（种子 audit prompts）

- [x] Step 1: Catalog 种子文案
- [x] Step 2: 实现 auditUserLight + id 门禁
- [x] Step 3: extract 后 async + debounce
- [x] Step 4: 单测 voidIds/conflictIds/幻觉 id 忽略

### Task 4: 维护任务接入 + 验收

**Files:**
- Modify: `ContextMaintenanceService`
- Modify: `ContextMaintenanceServiceTest`

- [x] Step 1: runOnce 末尾批量 audit
- [x] Step 2: 编译重启 orchestrator；Admin 可见 conflict
