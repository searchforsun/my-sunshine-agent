> **ARCHIVED**（2026-07-17 · DOC-022）— 实现已完成；日常 SSOT 见 [`docs/sandbox/README.md`](../../../sandbox/README.md)。勿按本文继续改代码。

# 沙箱容器双层生命周期 Implementation Plan

> **For agentic workers:** Use executing-plans / TDD. Steps use checkbox syntax.

**Goal:** idle 30min → docker stop（保 workspace）；再进 → start；自上次活动 7d → rm+清盘。

**Architecture:** sandbox-service stop/start；orchestrator Redis dual ZSET（expiry/purge）；Reaper 分 stop/destroy；ensure 对 stopped 调 start。

**Spec:** [2026-07-17-sandbox-container-lifecycle-design.md](../../specs/2026-07-17-sandbox-container-lifecycle-design.md)

---

### Task 1: sandbox-service stop/start + alive
### Task 2: Orchestrator Client + Binding/Store dual TTL
### Task 3: Lifecycle ensure start + Reaper stop/purge
### Task 4: Nacos + 文档 + 单测绿 + 重启
