# ADR-001：删除遗留兼容层优先于「锁定文档」中的实现保留

| 字段 | 值 |
|------|-----|
| 状态 | Accepted |
| 日期 | 2026-06-29 |
| 关联 | TD-004、TD-010；`/tech-debt-refactor` §0 P3/P7/P9 |

## 背景

仓库存在三类互相冲突的指引：

1. **[locked-architecture-decisions.md](../superpowers/specs/2026-06-19-locked-architecture-decisions.md)** 文首「已锁定 — 后续须一致」— 易被误读为**禁止删任何曾写入 spec 的 API/方法**。
2. **[2026-06-20-phase2-closure.md](../superpowers/plans/2026-06-20-phase2-closure.md) Task 8** 曾写：`toLegacyIntentLabel` **保留** `@Deprecated` 仅供测试。
3. **`/tech-debt-refactor` §0**：P3 删 > 包 > 迁；P7 质疑文档；P9 开发阶段禁止为旧 wire format 写兼容。

实际代码已在 **TD-004** 删除 `IntentRouter.classify` / `toLegacyIntentLabel` 及专用测试；**TD-010** 删除 `GET /api/skills/catalog`，SSOT 为 `/catalog/index` + `/catalog/{id}`。

## 决策

1. **锁定文档范围**：`2026-06-19-locked-architecture-decisions.md` 的 D1–D11 表示**架构边界**（执行模式、落库、服务拆分、Timeline 形态等），**不**保护已废弃的实现垫片、`@Deprecated` 测试专用方法、或双 Catalog 入口。
2. **冲突裁决**：文档与可运行代码 + 测试冲突时，以 **代码 + 测试** 为准（P1）；随后用 ADR 记录并**回写**锁定文档 / 阶段 spec 中的 API 表，而非恢复兼容层。
3. **已执行删除（归档）**：

| 项 | 原指引 | 处置 | 替代 |
|----|--------|------|------|
| `IntentRouter.toLegacyIntentLabel` | phase2 plan 建议保留 Deprecated | **已删**（TD-004） | Timeline `summary` / `agent.timeline.intent` 模板 |
| `IntentRouter.classify` | 历史 L3 入口 | **已删**（TD-004） | Policy Chain `ExecutionPlanRouter` |
| `GET /api/skills/catalog` | locked D3 API 表 | **已删，410 Gone**（TD-010） | `GET /api/skills/catalog/index` + `/{id}/catalog` |
| `LegacyWorkflowExecutor` | phase2 closure D6 | **已删**（TD-004 同期） | 未知 workflow SSE 明确报错 |

4. **今后**：phase2/历史 plan 中与上表矛盾的句子视为 **Superseded**；新兼容层须证明外部不可清库（P9），否则禁止合入。

## 后果

- **正面**：单一路由、单 Catalog SSOT；grep 无 `Legacy*` / `toLegacy*` 生产路径。
- **负面**：只读旧 spec 的读者可能看到过时 API；靠 ADR + 锁定文档修订 + `tech-debt-register` 归档对齐。
- **验证**：`mvn test -pl orchestrator`（路由/Timeline 相关）；`verify_skills_ui_live.py`；`grep -r toLegacyIntentLabel orchestrator` 为 0。

## 参考

- [tech-debt-register.md](../tech-debt-register.md) — TD-004、TD-010
- [phase2-closure-design.md](../superpowers/specs/2026-06-20-phase2-closure-design.md) D6 — 与实现一致（删 Legacy executor）
