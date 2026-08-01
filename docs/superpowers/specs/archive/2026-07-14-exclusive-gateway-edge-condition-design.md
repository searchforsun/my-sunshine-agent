# 条件分支（exclusive-gateway）边条件设计

> **状态**：✅ **已落地收口** · 引擎 + Studio + 种子 `knowledge-branch` + Live  
> **日期**：2026-07-14  
> **依赖**：4.13 Studio · 现有 `exclusive-gateway` 拓扑

## 目标

排他网关按**出边条件**在运行时选择唯一下游路径；替代「按目标节点 ID 排序取第一条」的占位逻辑。

## 模型

```json
{
  "from": "xg-1",
  "to": "rag-a",
  "condition": { "left": "{{start.userQuery}}", "op": "contains", "right": "可报销" }
}
```

```json
{ "from": "xg-1", "to": "answer", "default": true }
```

- 仅 `from` 为 `exclusive-gateway` 的边可带 `condition` / `default`
- 每个网关恰好 1 条 `default: true`
- 非 default 边须有 `condition`（`empty`/`not_empty` 可不要求 `right`）
- 算子：`empty` | `not_empty` | `contains` | `eq`
- `left`/`right` 经 `TemplateResolver` 解析 `{{node.field}}`

## 运行时

1. 执行网关节点（trace）
2. 按边声明顺序求值非 default 条件，首个 true 命中
3. 皆否 → default；无 default → fail
4. 仅执行命中臂路径节点，再继续汇合后调度

## Studio

- 选中网关出边可编辑：default / op / right；**左值随上游自动填入**（业务前驱 `{{id.output|answer}}`，否则 `{{start.userQuery}}`），不可手改
- 校验：出度 ≥ 2、恰一条 default、条件完整
- **画布不展示边条件标签**（配置摘要易误导且非运行态）；Chat 点条件分支节点时在抽屉「分支条件」展示

## 非目标（明确不做）

- 复合 AND/OR
- 数值比较
- 独立 `if-else` 节点类型（条件分支用 exclusive-gateway）
- 画布边条件标签（与上 Studio 约定一致）

> loop 容器见独立详设 [2026-07-14-workflow-loop-container-design.md](./2026-07-14-workflow-loop-container-design.md)（已收口）。

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-07-14 | 初稿：出边条件 + default |
| 2026-07-15 | **收口**：当前算子集为终态；§非目标明确不做 |
