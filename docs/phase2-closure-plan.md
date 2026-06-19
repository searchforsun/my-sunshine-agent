# 阶段二收尾完善方案

> **状态**：设计已锁定 → 详见 **[superpowers/specs/2026-06-20-phase2-closure-design.md](./superpowers/specs/2026-06-20-phase2-closure-design.md)**  
> **实施计划**（已就绪）：[superpowers/plans/2026-06-20-phase2-closure.md](./superpowers/plans/2026-06-20-phase2-closure.md)  
> **周期**：3–4 周 · **环境**：ecs4c16g

---

## 已锁定决策（摘要）

| 项 | 结论 |
|----|------|
| 范围 | 完整集 `2.10`–`2.16` |
| Live 脚本 | `phase2_agent_demo.py --suite all\|react\|workflow` |
| RAG | 清库 + 7 篇语料 + 60–80 query + `rag_eval` 基线 |
| Legacy | 删除 `LegacyWorkflowExecutor`；未知 workflow SSE 报错 |
| Rag 双路径 | Nacos `default-top-k` + 统一 Formatter |
| 规则路由 | `RuleBasedRouter` 优先于 intent LLM |

---

## 任务卡索引

| 任务 | 优先级 | 要点 |
|------|:------:|------|
| [2.10](./superpowers/specs/2026-06-20-phase2-closure-design.md#5-210-live-验收) | P0 | `--suite all` ecs4c16g 全绿 |
| [2.11](./superpowers/specs/2026-06-20-phase2-closure-design.md#6-211-legacy-清理--rag-统一) | P0 | Legacy 删 + Rag 统一 |
| [2.12](./superpowers/specs/2026-06-20-phase2-closure-design.md#4-212-rag-系统性评测重点) | P0 | rebuild API + 语料 + eval |
| [2.13](./superpowers/specs/2026-06-20-phase2-closure-design.md#7-213-审计-tool-摘要) | P1 | audit `toolNames` |
| [2.14](./superpowers/specs/2026-06-20-phase2-closure-design.md#8-214-规则硬路由) | P1 | `RuleBasedRouter` |
| [2.15](./superpowers/specs/2026-06-20-phase2-closure-design.md#9-215-catalog-白名单校验) | P2 | 启动 ERROR 校验 |
| [2.16](./superpowers/specs/2026-06-20-phase2-closure-design.md#10-216-文档) | P2 | CLAUDE / rag README |

---

## 总检查门

见 spec §12。全部勾选后宣告**阶段二完成**，进入阶段三。

---

## RAG 快速命令（实施后可用）

```bash
python scripts/rag_reset.py
python scripts/rag_ingest_bulk.py
python scripts/rag_eval.py
python scripts/phase2_agent_demo.py --suite all
```

详见 [docs/rag/README.md](./rag/README.md)。
