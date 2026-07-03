# 知识库评测 Tab 简化设计

> **状态**：已归档（2026-07-03）  
> **并入**：[2026-07-02-kb-eval-ui-redesign.md](../../superpowers/specs/2026-07-02-kb-eval-ui-redesign.md)（§1–2、§12）  
> **索引**：[docs/rag/README.md](../../rag/README.md)

本文档仅保留历史决策摘要；实施与验收以 **eval-ui-redesign** 为准。

---

## 1. 目标

- **一个概念**：评测集 = queries + 期望文档；去掉 Badcase、Profile、多层 Tab
- **种子 SSOT**：`docker/mysql/init/`（条目）；MinIO 用于 Python suite / 报告（见 backlog §7）
- **前端**：「选集 → 运行 → 看结果」；脚本 Tab 管理条目

## 2. 内置评测集（拆 Profile）

| suiteKey | 显示名 | 用途 |
|----------|--------|------|
| `sunshine-regression` | 标准回归 | 默认全量回归（123 条） |
| `sunshine-adversarial` | 难例对抗 | adversarial category |
| `sunshine-smoke` | 冒烟门禁 | 发布/CI（50 条） |

每 KB 可编辑自定义集：`{kbId}-custom`

## 3. 存储

- **MySQL `eval_suite` + `eval_suite_item`**：内置集条目 SSOT（`10/11-sunshine-rag-eval-suite*.sql`）
- **MinIO**：Python 脚本 suite、评测报告正文
- **已删除**：独立 badcase 表、Java 启动 seed、文件 golden-set 运行时回退

## 4. 前端

- `KbEvalPanel`：「运行评测 | 评测脚本」；记录嵌运行页 + 抽屉
- `KbDebugPanel`：「加入评测集」
- 无 `KbBadcasePanel`、Profile 下拉

## 5. 验收

1. `docker compose up` 后可见 3 内置集
2. 不依赖 `docs/rag/golden-set.yaml` 运行时读取
3. 主路径 2 步：选集 → 运行
