> **ARCHIVED**（2026-07-17 · DOC-022）— 实现已完成；日常 SSOT 见 [`docs/sandbox/README.md`](../../../sandbox/README.md)。勿按本文继续改代码。

# 沙箱写操作 HITL 跳过模式 · Implementation Plan

> Spec: [2026-07-16-sandbox-write-hitl-skip-design.md](../../specs/2026-07-16-sandbox-write-hitl-skip-design.md)

**Goal:** Chat 工作区三档写确认模式（never/always/smart），本会话生效。

**状态：** ✅ Task 1–5 已完成（单测绿；orchestrator/BFF 已接线；工作区 UI 已对齐模式选择器样式）

| Task | 内容 | 状态 |
|------|------|:----:|
| 1 | `SandboxWriteHitlMode` + Policy 矩阵 + 单测 | ✅ |
| 2 | Chat 入参 + Bridge 绑定 + `SandboxAgentTools` | ✅ |
| 3 | BFF 透传 | ✅ |
| 4 | 前端会话态 + send + `WriteHitlModeSelector` | ✅ |
| 5 | 编译重启 + 单测验收 | ✅ |
| — | Live：`writeHitlMode` Chat 冒烟 | ✅ |
