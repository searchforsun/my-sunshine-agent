# L1 Admin 窗口列表设计

**日期**：2026-07-22  
**状态**：已实现

## 目标

Admin「L1 会话快照」按运行时近/中/远窗口展示为固定行高列表，便于核对注入上下文。

## 行为

- 顺序：近 → 中 → 远；区内新 → 旧
- 近窗行：user + assistant **原文**
- 中窗行：user **原文** + assistant **mid_answers 摘要**（无摘要则原文兜底）
- 远窗：0/1 行，仅 `far_summary`；时间取 L1 `updatedAt`
- 行标签：`近 #k · 时间` / `中 #k · 时间` / `远 · 时间`（时间优先 assistant `createdAt`）
- 最多 `nearN + midN + 1` 行；行高固定，正文区内滚动

## API

`GET /api/admin/context/l1?convId=` 的 `L1SnapshotView` 增加 `rows: L1WindowRowView[]`。
