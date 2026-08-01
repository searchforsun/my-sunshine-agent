# 知识库配置版本生命周期 V3 设计

> **日期**：2026-07-02（Suggest 规则修订 2026-07-03）  
> **状态**：已实施  
> **父文档**：[2026-07-01-rag-studio-v2-design.md](./2026-07-01-rag-studio-v2-design.md)  
> **索引**：[docs/rag/README.md](../../rag/README.md)

## 1. 状态机

```
草稿 (draft)
  → 提交评测 → 待评测 (pending_eval)
    → 评测通过 (eval_passed) → 生效 (active)
    → 评测失败 (eval_failed)
  ← 转为草稿（version_no 不变）← pending_eval / eval_passed / eval_failed
```

| 状态 | 可编辑 | 可应用配置（调试/评测） | 可转草稿 | 可应用参数建议 |
|------|:------:|:----------------------:|:--------:|:--------------:|
| draft | ✅ | ❌ | — | ❌ |
| pending_eval | ❌ | ✅ | ✅ | ❌ |
| eval_passed | ❌ | ✅ | ✅ | ❌ |
| eval_failed | ❌ | ✅ | ✅ | ✅ → 应用后变 draft |
| active | ❌ | ✅ | ❌（复制到草稿行） | ❌ |
| superseded | ❌ | ✅ | ❌（复制到草稿行） | ❌ |

## 2. 核心规则

1. **仅 draft 可编辑**（`saveDraft` / 导入 JSON）；**Suggest 应用**仅 **eval_failed** → 写入 payload 并 **转为 draft**。
2. **应用配置**（调试/评测）仅非 draft；右上角下拉过滤 draft。
3. **转为草稿**：`POST .../versions/{id}/revert-to-draft`，version_no 不变；若已有其他 draft 行则 409。
4. **生效**：仅**最新** `eval_passed` 且 `version_no > 当前 active.version_no`；禁止切换旧版。
5. **并发**：bundle 行 `PESSIMISTIC_WRITE` 锁；状态变更前校验指针与 status。
6. **新建 kb / seed**：仅 v1 `active`；`draft_version_id = NULL`；用户通过 **复制为草稿** 创建可编辑草稿。

## 3. API

| Method | Path | 说明 |
|--------|------|------|
| POST | `/config/publish` | 提交评测（draft→pending_eval→eval_passed/failed） |
| POST | `/config/versions/{id}/activate` | eval_passed → active（递增） |
| POST | `/config/versions/{id}/revert-to-draft` | 评测态 → draft |
| POST | `/config/versions/{id}/fork` | active/superseded 复制到草稿行；评测态走 revert |

## 4. 前端

- 参数配置：**保存草稿** / **提交评测** / **生效**（eval_passed 最新）
- 右上角应用配置：Tag + 时间戳，不含 draft
- 转为草稿时若正被应用：提示切换非草稿版本
