# 4.7.9 D12 — Planner MAIN 注册 `request_decision`

> **状态**：✅ 已实现（Planner MAIN 注册 / 续跑 / 时间线；Chat MAIN 不重开）  
> **日期**：2026-08-12 · **落地**：2026-08-21  
> **父文档**：[archive/2026-07-28-react-request-decision-design.md](./archive/2026-07-28-react-request-decision-design.md) · 契约 SSOT：[archive/2026-08-11-request-decision-cursor-align-design.md](./archive/2026-08-11-request-decision-cursor-align-design.md)  
> **范围**：仅 **Planner-Executor MAIN** 注册 / 续跑 / 时间线；Chat ReAct MAIN 已 ✅，不在此重开

## 目标

Planner harness 的 MAIN toolkit 在 `react.decision.enabled` 下注册 `request_decision`，暂停/续跑与 Chat MAIN 同契约（`questions[]` / `answers[]`），**不做** Worker / SUB。

## 非目标

- 不改 Cursor wire 契约  
- 不重写 Chat DecisionCard  
- 不恢复已废弃 PlanApproval

## 验收（落地时）

- Live：扩展 `verify_decision_live.py` 或独立 Planner 用例覆盖 D12  
- 完成后本文件移入 `specs/archive/`，并更新 `CLAUDE.md` / `implementation-plan.md`
