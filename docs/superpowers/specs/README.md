# Superpowers 设计文档索引

> **阅读顺序**：本目录按 **阶段一 → 四** 组织；每阶段一份 **SSOT**（单一事实来源），任务编号 `阶段.序号`（如 `3.4.2`）。

## 四阶段 SSOT（主文档）

| 阶段 | 周期 | SSOT | 状态 |
|:----:|:----:|------|:----:|
| **一** | 8 周 + 1.5/1.6 | [phase1-foundation-design.md](./phase1-foundation-design.md) | ✅ 已完成 |
| **二** | 8 周 + 2.9/收尾 | [phase2-benchmark-design.md](./phase2-benchmark-design.md) | ✅ 已完成 |
| **三** | 8 周 | [phase3-production-hardening-design.md](./phase3-production-hardening-design.md) | ⬜ 待实施 |
| **四** | 按需 | [phase4-platformization-design.md](./phase4-platformization-design.md) | ⬜ 按需 |

总排期与检查门摘要：[implementation-plan.md](../../implementation-plan.md)

---

## 任务编号约定

```
阶段.任务          例：3.4 = RAG 检索增强（整块）
阶段.任务.子任务    例：3.4.2 = ES 双写入库
阶段.任务（并行）   例：3.13 = 不进检查门的并行项
```

一个阶段做不完时，**只增子编号**，不新建平行 spec。

---

## 已并入各阶段的旧文档（保留作历史参考）

| 旧文件 | 并入 |
|--------|------|
| `2026-06-07-phase1-gap-closure-design.md` | 阶段一 §1.7 |
| `2026-06-11-phase1.5-conversation-mvp-design.md` | 阶段一 §1.5–1.6 |
| `2026-06-17-agent-memory-design.md` | 阶段二 §2.17；阶段三 §3.2 |
| `2026-06-13-processing-timeline-design.md` | 阶段二 §2.18 |
| `2026-06-13-processing-timeline-v2-design.md` | 阶段二 §2.18 |
| `2026-06-18-workflow-orchestration-design.md` | 阶段二 §2.9 |
| `2026-06-20-phase2-closure-design.md` | 阶段二 §2.10–2.16 |
| `2026-06-19-locked-architecture-decisions.md` | 阶段三 §3.9–3.11 |
| `2026-06-19-multi-agent-architecture-design.md` | 阶段三 §3.9–3.10 |
| `2026-06-19-advanced-capabilities-design.md` | 阶段三 §3.4.7、§3.8–3.11；阶段四 §4.5–4.7 |
| `skills-management-ui-design.md` | 阶段三 **§3.12** `/skills` 管理页 UI/API SSOT |
| `2026-06-21-multimodal-ocr-design.md` | 阶段四 §4.2–4.3 |
| `2026-06-24-peer-collab-routing-design.md` | 阶段四 §4.7.3 · 第五顶层模式 `PEER_COLLAB` |
| `2026-06-19-phase3-production-hardening-design.md` | → 已迁移为 `phase3-production-hardening-design.md` |
| `2026-06-19-phase4-platformization-design.md` | → 已迁移为 `phase4-platformization-design.md` |

---

## 实施计划（plans/）

| 阶段 | 计划 |
|------|------|
| 一 | `plans/2026-06-11-phase1.5-conversation-mvp.md`、`plans/2026-06-11-phase1.6-generation-reconnect.md` |
| 二 | `plans/2026-06-18-workflow-orchestration.md`、`plans/2026-06-20-phase2-closure.md` |
| 三 | [phase3-production-hardening.md](../plans/2026-06-19-phase3-production-hardening.md)、[multi-agent-architecture.md](../plans/2026-06-19-multi-agent-architecture.md)、[覆盖度审计](../plans/2026-06-20-phased-implementation-coverage.md) |
| 四 | 按需；触发时从 spec §4.1 或 §4.2 写子 plan；**4.7.3 Peer 协作** 见 [peer-collab-routing-design.md](./2026-06-24-peer-collab-routing-design.md) |
