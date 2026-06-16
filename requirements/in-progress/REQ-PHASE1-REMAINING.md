# REQ-PHASE1-REMAINING — 一阶段剩余任务补齐与验收闭环

> **状态**：in-progress  
> **创建**：2026-06-16  
> **关联文档**：[`docs/implementation-plan.md`](../../docs/implementation-plan.md)

## 背景

阶段一（含 1.5 会话 MVP、1.6 Redis SSE 重连、Processing Timeline）**核心代码已落地**，但检查门、缺口补齐联调、可观测性接线、文档同步仍有未完成项。本需求将上述剩余工作收敛为可执行 Task 清单，便于逐项验收后进入阶段二。

## 目标

1. **P0**：完成阶段一 / 1.5 / 1.6 检查门全部手动与自动化验收，更新 `implementation-plan.md` 勾选状态。
2. **P1**：SkyWalking Agent 接入、Gateway 默认链路、ReActAgent Memory 隔离、Timeline V2 E2E 收尾。
3. **P2**：CI 流水线、演示脚本增强、文档 debt 清理；knowledge 路径重连作为可选增量。

## 范围

**包含：**

- 检查门与 `phase1-gap-closure` 全链路验收
- SkyWalking / Gateway / Nacos 配置同步
- 已知技术债（Memory 隔离、demo 脚本、集成测试分层说明）
- Processing Timeline V2 E2E 验证
- GitHub Actions 基础 CI

**不包含（延至阶段二/三）：**

- Sa-Token 认证、Tool Manager、脱敏、RocketMQ 审计
- Sentinel 多模型故障切换（阶段二 2.6）
- BM25 混合检索、多租户、HITL（阶段三）

## 验收标准（需求级）

- [x] `docs/implementation-plan.md` 阶段一 / 1.5 / 1.6 检查门全部 `[x]`
- [x] `phase1-demo.ps1` / `phase1-demo.sh` Step 1–6 自动化通过；Step 5 Gateway 非 Optional
- [ ] SkyWalking UI 可见 gateway→bff→orchestrator→llm-gateway 拓扑（D2 live 待中间件）
- [x] `mvn test -pl orchestrator -am` 默认通过（exclude integration）
- [x] `npx playwright test e2e/processing-timeline.spec.ts` 通过（mock 模式）

## 设计文档

[`REQ-PHASE1-REMAINING-design.md`](./REQ-PHASE1-REMAINING-design.md)

## 实施计划

[`REQ-PHASE1-REMAINING-task.md`](./REQ-PHASE1-REMAINING-task.md)
