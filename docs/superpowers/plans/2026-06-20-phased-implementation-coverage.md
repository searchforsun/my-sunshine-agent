# 分阶段实施计划 — 覆盖度审计

> **Superseded（2026-06-30）**：阶段三已交付。**Live/检查门以** [phase3-production-hardening-design.md](../specs/phase3-production-hardening-design.md) **§6 为准**；下表「缺口 ⬜」列多为历史审计快照，已过时。

> **日期**：2026-06-30（更新：阶段三 closure）  
> **方法**：对照各阶段 SSOT（`specs/phaseN-*.md`）与 `plans/*.md`，按 writing-plans 标准检查（文件路径、测试命令、逐步 checkbox、无 TBD）。  
> **结论摘要**：阶段一/二/三 **已交付**（3.4 v6 相对 vector +15% 轨 WARN）；阶段四 **仅 spec，无 plans**。

---

## 评分标准

| 等级 | 含义 |
|:----:|------|
| **A** | SSOT + plan 均有；plan 含 Files + 验收命令 + checkbox |
| **B** | SSOT 有；plan 有任务表但缺逐步 TDD / 部分任务「见 spec」 |
| **C** | 仅 SSOT 任务表，无对应 plan |
| **D** | SSOT 与 plan 均缺 |

---

## 阶段一：底座搭建

**SSOT：** [phase1-foundation-design.md](../specs/phase1-foundation-design.md)  
**状态：** ✅ 已完成

| 任务 | SSOT | Plan | 等级 | 说明 |
|------|:----:|:----:|:----:|------|
| 1.1–1.5 核心五件套 | ✅ | ⚠️ | **B** | 无统一 `phase1` plan；能力由 gap-closure + 集成测试覆盖 |
| 1.5.1–1.5.6 会话 MVP | ✅ | ✅ | **A** | [2026-06-11-phase1.5-conversation-mvp.md](./2026-06-11-phase1.5-conversation-mvp.md) |
| 1.6.1–1.6.5 SSE 重连 | ✅ | ✅ | **A** | [2026-06-11-phase1.6-generation-reconnect.md](./2026-06-11-phase1.6-generation-reconnect.md) |
| 1.7 gap-closure 四轨道 | ✅ 附录 | ✅ | **A** | [2026-06-07-phase1-gap-closure.md](./2026-06-07-phase1-gap-closure.md) |

**检查门：** SSOT §3 共 11 条，均有历史验收记录。

**缺口（可接受）：** 无单文件 `phase1-foundation.md` 汇总 plan — 建议仅维护 SSOT + 子 plan 链接（已在 [README](../specs/README.md)）。

---

## 阶段二：标杆打通

**SSOT：** [phase2-benchmark-design.md](../specs/phase2-benchmark-design.md)  
**状态：** ✅ 已完成

| 任务 | SSOT | Plan | 等级 | 说明 |
|------|:----:|:----:|:----:|------|
| 2.1–2.8 MVP | ✅ | ⚠️ | **B** | 任务在 SSOT + implementation-plan；无逐步 plan（已交付） |
| 2.9.1–2.9.6 Workflow | ✅ | ✅ | **A** | [2026-06-18-workflow-orchestration.md](./2026-06-18-workflow-orchestration.md) |
| 2.10–2.16 收尾 | ✅ | ✅ | **A** | [2026-06-20-phase2-closure.md](./2026-06-20-phase2-closure.md) |
| 2.17 记忆 | ✅ | ⚠️ | **B** | 设计在 [agent-memory-design](../specs/2026-06-17-agent-memory-design.md)；无独立 plan |
| 2.18 Timeline V2 | ✅ | ✅ | **A** | [archive/plans/2026-06-13-processing-timeline-v2.md](../../archive/plans/2026-06-13-processing-timeline-v2.md) |

**检查门：** SSOT §5 共 10+ 条，2.12 `rag_eval --gate` 已 PASS。

**缺口（可接受）：** 2.1–2.8 无逐步 plan（历史交付）；2.17 仅设计 spec。

---

## 阶段三：生产加固 🟡 进行中（主线代码 ✅；live/收尾待关）

**SSOT：** [phase3-production-hardening-design.md](../specs/phase3-production-hardening-design.md)  
**Plans：** [2026-06-19-phase3-production-hardening.md](./2026-06-19-phase3-production-hardening.md) + [2026-06-19-multi-agent-architecture.md](./2026-06-19-multi-agent-architecture.md) + [2026-06-26-pause-resume-consistency.md](./2026-06-26-pause-resume-consistency.md)（3.9.5）

**已实现（2026-06-27 代码审计）：** 3.4 全链路；3.8.1–3.8.7；3.9.1–3.9.4 + 静态 workflow Plan DAG；3.10.1–3.10.7；3.11.1–3.11.7 + 六种 Skill 触发；3.12 前端；3.6 审计三链路 API；3.3 HITL 全栈；3.7 Grounding 代码接入；3.2 多租户传播链 + 过滤（非 Milvus Partition）；基础 pause/resume。

**未实现 / 部分：** 3.9.5 pausePhase/pendingInteraction；3.13 AhoCorasick；3.14 Redis Job 锁；3.2 跨租户集成测试；3.7 集成测试；3.5 Docker/远程部署闭环。

### 3.1 Spec → Plan 映射

| 任务 | SSOT | Plan | 等级 | 实现 | 缺口 |
|------|:----:|:----:|:----:|:----:|------|
| **3.4.1** 评测 + v6 | ✅ | ✅ | **A** | ✅ | — |
| **3.4.2** ES 双写 | ✅ | ✅ | **A** | ✅ | Step 4 联机 ES `_count` 可选 |
| **3.4.3** BM25 | ✅ | ✅ | **A** | ✅ | — |
| **3.4.4** Hybrid RRF | ✅ | ✅ | **A** | ✅ | — |
| **3.4.5** Rerank | ✅ | ✅ | **A** | ✅ | 向量锚点门禁 |
| **3.4.6** Metrics | ✅ | ✅ | **A** | ✅ | 远程 Grafana 部署 ⬜ |
| **3.4.7** Query rewrite | ✅ | ✅ | **A** | ✅ | 与 3.8.1 合并 |
| **3.4.8** CI 门禁 | ✅ | ✅ | **A** | ✅ | v6 提升轨 WARN 见 closure |
| **3.2** 多租户 | ✅ | ✅ | **A** | **部分** | 代码 ✅；跨租户集成测 + live ⬜ |
| **3.3** HITL | ✅ | ✅ | **A** | **✅** | 代码 ✅；live ⬜ |
| **3.5** 可观测 | ✅ | ✅ | **A** | 部分 | 指标+JSON ✅；Sentinel/告警触发 ⬜ |
| **3.6** 审计 | ✅ | ✅ | **A** | **✅** | API ✅；可查 live ⬜ |
| **3.7** Grounding | ✅ | ✅ | **A** | **部分** | 代码 ✅；集成测试 ⬜ |
| **3.8.1** QueryRewrite | ✅ | ✅ | **A** | ✅ | 默认开启 |
| **3.8.2** PromptComposer | ✅ | ✅ | **A** | **✅** | — |
| **3.8.3** workflow llm → Composer | ✅ | ✅ | **A** | **✅** | — |
| **3.8.4–7** 改写增强 | ✅ | ✅ | **B** | **✅** | 非检查门 |
| **3.9.1–3.9.4** PLAN_WORKFLOW | ✅ | ✅ | **A** | **✅** | live 2+ agent ⬜ |
| **3.9.5** 暂停/续跑一致性 | ✅ | ✅ | **A** | **部分** | 基础续跑 ✅；pausePhase 等 ⬜ |
| **3.10.1–3.10.3** AgentRuntime 基础 | ✅ | ✅ | **A** | ✅ | params + 白名单 |
| **3.10.7** 子 Agent 上下文隔离 | ✅ | ✅ | **A** | ✅ | memory + skill→Composer |
| **3.10.4–3.10.6** Planner / 动态 DAG / 审计 | ✅ | ✅ | **A** | **✅** | live ⬜ |
| **3.11.1–3.11.7** skill-manager | ✅ | ✅ | **A** | **✅** | Live ⬜ |
| **3.12** 前端 | ✅ | ✅ | **A** | **✅** | `/skills` live ⬜ |
| **3.13** 并行 | ✅ | ✅ | **B** | 部分 | `source_type` ✅；AhoCorasick ⬜ |
| **3.14** Job 锁 | ✅ | ✅ | **B** | ⬜ | 多实例生产必做 |

### 3.2 检查门 → Plan 覆盖（17 条）

| # | 检查门 | 覆盖任务 | Plan 有？ | 实现 |
|---|--------|----------|:---------:|:----:|
| 1 | v5 回归轨 | 3.4.1, 3.4.8 | ✅ | **PASS**（closure） |
| 2 | v6 提升轨 | 3.4.1, 3.4.4–3.4.5 | ✅ | 生产门禁 PASS；**vs vector +15% WARN** |
| 3 | Grafana + 4 告警 | 3.4.6, 3.5 | ✅ | 部分（JSON ✅） |
| 4 | Sentinel Dashboard | 3.5 | ✅ | ⬜ |
| 5 | 租户 A/B 隔离 | 3.2 | ✅ | ⬜ |
| 6 | HITL 含子 Agent | 3.3 | ✅ | ⬜ |
| 7 | PLAN_WORKFLOW 三 API | 3.9.2–3.9.3 | ✅ | **✅** |
| 8 | 2+ agent + Plan 详情页 | 3.10.5, 3.12.4 | ✅ | **部分** | 代码 ✅；live ⬜ |
| 9 | IntentRouter + fallback | 3.9.1, 3.10.4d | ✅ | **✅** |
| 10 | finance-smart skill 子集 | 3.10.3, 3.11 | ✅ | **✅** |
| 11 | skill catalog + /skills | 3.11, 3.12 | ✅ | **部分** | 代码 ✅；live ⬜ |
| 12 | tool/sub_agent/plan 审计 | 3.6, 3.10.6, 3.9.4 | ✅ | **部分** | API ✅；live ⬜ |
| 13 | Grounding | 3.7 | ✅ | **部分** | 代码 ✅；集成测试 ⬜ |
| 14 | 子 Agent 不污染 reasoning | 3.10.7 | ✅ | 部分 | Prompt 无 STM ✅；持久化集成 ⬜ |
| 15 | phase2 demo 仍 PASS | 全阶段回归 | ✅ | 待总验收 |
| 16 | *(spec 列表 12 条主项 + 子项)* | — | — | — |
| 17 | 3.14 多实例（条件） | 3.14 | ✅ | ⬜ |

**结论：17 条检查门均有对应任务卡；无 spec 孤儿需求。**

### 3.3 仍欠 writing-plans「逐步 TDD」的深度

| 区域 | 现状 | 建议 |
|------|------|------|
| 3.4.1–3.4.8 RAG | 任务表 + 部分 Files | 实施 3.4.1 时按 plan 内 Task 模板扩写 3.4.2+ |
| 3.10.x / 3.9.x | Files + checkbox | 缺逐步测试代码块；实施前可逐 Task 补 |
| 3.2–3.8 | **本次已补** Files + 验收命令 | 达 B+；完整 TDD 在实施周展开 |

---

## 阶段四：平台化

**SSOT：** [phase4-platformization-design.md](../specs/phase4-platformization-design.md)  
**Plan：** ❌ 无

| 任务 | SSOT | Plan | 等级 |
|------|:----:|:----:|:----:|
| 4.1–4.12 全部 | ✅ 子任务表（**4.8** MCP 动态引入 + `/mcp` 前端已详设） | ❌ | **C** |

**检查门：** SSOT §5 按子项列出；无执行 plan。

**建议：** 触发 4.1 或 4.2 时再写 `2026-06-XX-phase4-rag-platform.md` 等**按子项** plans，勿一次写 12 项。

---

## 总览矩阵

| 阶段 | SSOT | Plan 覆盖 | 检查门可追溯 | writing-plans 深度 |
|:----:|:----:|:---------:|:------------:|:------------------:|
| 一 | ✅ | B+（子 plan 齐全） | ✅ | A（1.5/1.6） |
| 二 | ✅ | A-（2.9/2.10–18） | ✅ | A（workflow/closure/timeline） |
| **三** | ✅ | **A-（任务全覆盖）** | ✅ | **A-（主线已交付；live/3.9.5 待关）** |
| 四 | ✅ | C（无 plan） | ✅（spec 内） | — |

---

## 推荐执行顺序（阶段三，2026-06-27 更新）

**已完成（代码）：** 3.4 / 3.8 / 3.9 / 3.10 / 3.11 / 3.12 / 3.6 API / 3.3 HITL / 3.7 Grounding 接入 / 3.2 传播链。

**下一步 P0：**

1. [2026-06-26-pause-resume-consistency.md](./2026-06-26-pause-resume-consistency.md) — **3.9.5** 暂停/续跑一致性
2. [phase3-production-hardening.md](./2026-06-19-phase3-production-hardening.md) — **3.2.3** 跨租户集成测试 + `verify_tenant_live.py`
3. Live 验收：`verify_hitl_live.py`、`verify_grafana_rag_live.py`、`verify_sentinel_dashboard.py`、`verify_skill_5b_live.py`
4. **3.7** Grounding 集成测试
5. **3.14** Redis GenerationJob 锁 → **3.13** AhoCorasick

**排期偏差说明：** 原 8 周表假设多 Agent 滞后；实际 **3.10/3.9/3.11/3.12 已提前完成**；剩余为 live 检查门与 3.9.5/3.13/3.14。

---

## 文档索引

| 文档 | 用途 |
|------|------|
| [specs/README.md](../specs/README.md) | 四阶段 SSOT 入口 |
| [implementation-plan.md](../../implementation-plan.md) | 总排期 + 检查门摘要 |
| 本文件 | Plan 覆盖度审计（随 plan 更新而更新） |
