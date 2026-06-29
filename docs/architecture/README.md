# 架构决策记录（ADR）

稳定、可推翻的**边界级**决策；一条一文件。入口文档（`README` / `CLAUDE.md`）只链索引，细则在此。

| ADR | 标题 | 状态 | 日期 |
|-----|------|:----:|------|
| [ADR-001](./ADR-001-delete-legacy-compat.md) | 删除遗留兼容层优先于「锁定文档」中的实现保留 | Accepted | 2026-06-29 |

**与「锁定决策」关系**：[locked-architecture-decisions.md](../superpowers/specs/2026-06-19-locked-architecture-decisions.md) 约束 **D1–D11 产品/边界**；若与 ADR 冲突，以 ADR + 代码为准，并回写锁定文档 API 表。
