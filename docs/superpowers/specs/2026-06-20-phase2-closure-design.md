# 阶段二收尾 — 技术设计（细化版）

> **⚠️ 已并入** [phase2-benchmark-design.md](./phase2-benchmark-design.md) **§2.10–2.16**。下文为历史详设。

> **日期**：2026-06-20  
> **状态**：已评审锁定（brainstorming 2026-06-19/20）  
> **周期**：3–4 周（兼职 1–2 天/周）  
> **验收环境**：ecs4c16g 远程中间件 + Nacos SSOT  
> **前置**：阶段二 MVP + Workflow 2.9 核心已落地

---

## 1. 已锁定决策

| # | 项 | 结论 |
|---|-----|------|
| D1 | 收尾范围 | **完整集** `2.10`–`2.16` |
| D2 | 验收环境 | **ecs4c16g**；`sync_nacos.py` → `start.py` |
| D3 | G3 Rag 双路径 | **纳入 2.11**；统一 `rag-service` + Nacos `default-top-k` + 单一 Formatter |
| D4 | Live 脚本 | `phase2_agent_demo.py --suite all\|react\|workflow` |
| D5 | RAG 评测 | **清库重建** + **5–8 篇语料** + **60–80 条 query**（制度 + 财务 FAQ） |
| D6 | Legacy | **删除** `LegacyWorkflowExecutor`；未知 workflow **SSE 报错**，不 fallback |
| D7 | 白名单 | 保持显式白名单；**不做** `auto` 全量注册 |

---

## 2. 目标与非目标

### 2.1 目标

1. **可验收**：ecs4c16g 上 `--suite all` 一键全绿  
2. **可扩展**：单一 Workflow DAG 路径；Rag 参数 SSOT  
3. **可评测**：清库后标准语料 + 60–80 条 golden-set + `rag_eval` 基线报告  
4. **可控路由**：规则硬路由 + 审计 tool 摘要  

### 2.2 非目标

- Finance 真实持久化（保持 Mock）  
- 脱敏 AhoCorasick（阶段三）  
- Sentinel Dashboard（阶段三 3.5）  
- 独立 `tool_audit` 表 / ES 细项（阶段三 3.6）  

---

## 3. 任务总览

| 任务卡 | 优先级 | 摘要 |
|--------|:------:|------|
| 2.10 | P0 | Live 验收 `--suite all` |
| 2.11 | P0 | Legacy 删除 + Rag 统一 |
| 2.12 | P0 | RAG 清库 + 语料 + golden-set + eval |
| 2.13 | P1 | 审计 tool 摘要 |
| 2.14 | P1 | 规则硬路由 |
| 2.15 | P2 | Catalog 白名单校验 |
| 2.16 | P2 | 文档同步 |

---

## 4. 2.12 RAG 系统性评测（重点）

### 4.1 清库方案（推荐：rag-service Admin API）

新增 `POST /api/rag/admin/rebuild`：

- drop + recreate `sunshine_knowledge` collection（复用 `MilvusService` 现有逻辑）
- 可选请求头 `X-Admin-Token`（Nacos `rag.admin.token`）；MVP 可仅内网
- 返回 `{code, msg, collection}`

运维脚本 `scripts/rag_reset.py` 只调该 API，不直连 Milvus。

### 4.2 语料包（7 篇，60–80 query）

| doc_id | 文件 | 类型 | 预估 query |
|--------|------|------|:----------:|
| leave-policy-v1 | `docs/knowledge/公司请假流程规范.md` | 已有 | 12 |
| expense-policy-v1 | `docs/knowledge/公司报销管理制度.md` | 新建 | 12 |
| attendance-policy-v1 | `docs/knowledge/考勤与加班管理规定.md` | 新建 | 10 |
| onboarding-policy-v1 | `docs/knowledge/新员工入职指引.md` | 新建 | 8 |
| finance-approval-v1 | `docs/knowledge/财务审批权限矩阵.md` | 新建 | 10 |
| invoice-faq-v1 | `docs/knowledge/发票与税务合规FAQ.md` | 新建 | 10 |
| budget-policy-v1 | `docs/knowledge/部门预算管理办法.md` | 新建 | 8 |
| — | 负例 query（无 relevant_docs） | golden-set | 6 |

新建语料要求：Markdown、含表格/章节、与财务 workflow 场景可交叉引用。

### 4.3 评测资产

```
docs/rag/
├── golden-set.yaml          # corpus + 60–80 queries
├── golden-set-schema.md     # 标注规范
├── baseline-report.md       # 清库重建后实测
├── README.md                # SOP
└── reports/
    └── baseline-YYYY-MM-DD.json

scripts/
├── rag_reset.py
├── rag_ingest_bulk.py       # 读 golden-set.corpus 批量 POST /api/rag/documents
└── rag_eval.py              # Recall@K, MRR, EmptyRate, latency, 分 category
```

### 4.4 `rag_eval.py` 指标

| 指标 | 说明 |
|------|------|
| Recall@3/5/10 | doc 级：topK 命中 `relevant_docs` |
| MRR | 首个相关 doc 排名 |
| EmptyRate | 正例/负例分别统计 |
| P50/P95 | 检索延迟 ms |
| category | leave / expense / attendance / finance / negative |

CLI：

```bash
python scripts/rag_eval.py                    # 全量
python scripts/rag_eval.py --suite core       # 快检（可选子集）
python scripts/rag_eval.py --rag-url http://ecs4c16g:8400
```

阶段二**不设及格线**；须填 `baseline-report.md` 与 `reports/baseline-*.json`。

### 4.5 检查门

- [ ] `rag_reset` → `rag_ingest_bulk` → `rag_eval` 全链路绿  
- [ ] 全量 ≥60 条 query 有报告  
- [ ] 负例 EmptyRate 符合预期（多数为 empty）

---

## 5. 2.10 Live 验收

### 5.1 脚本

```bash
python scripts/phase2_agent_demo.py --suite all      # 检查门
python scripts/phase2_agent_demo.py --suite react
python scripts/phase2_agent_demo.py --suite workflow
python scripts/phase2_agent_demo.py --suite all --skip-rag-prep  # 已入库时
```

环境变量：`GATEWAY_URL`（默认 `http://ecs4c16g:8000`）、`RAG_URL`、`FINANCE_URL`、`PHASE2_AGENT_TIMEOUT_SEC`。

### 5.2 `--suite all` 步骤

| Step | 内容 | 通过条件 |
|:----:|------|----------|
| 0 | preflight + rag 语料检查 | finance pending ≥1；rag 抽样 search 命中 |
| 1 | auth | JWT 有效 |
| 2 | react-finance | SSE completed；content 含财务信息；tool 步 |
| 3 | wf-knowledge | golden-set 固定 query | node 步 + content |
| 4 | wf-finance-list | 待审批报销 | tool/node 步 + content |
| 5 | wf-finance-smart | 合规类 query | agent node 步 + completed |
| 6 | report | JSON 汇总 | 全 PASS |

### 5.3 手动补充

前端各 1 轮：知识库问答、财务智能分析，Timeline 步骤完整。

---

## 6. 2.11 Legacy 清理 + Rag 统一

### 6.1 Legacy 删除

| 动作 | 说明 |
|------|------|
| 删除 `LegacyWorkflowExecutor` | 含 knowledgeFlux / financeFlux |
| `WorkflowExecutor` | `definition` 缺失 → 明确错误 SSE，**禁止** fallback legacy/react |
| `IntentRouter.toLegacyIntentLabel` | 删除生产引用；测试迁移 |
| 验收 | `sunshine-workflows.yaml` 四条 definition 覆盖原行为；`mvn test -pl orchestrator` 绿 |

### 6.2 Rag 统一（G3）

**Nacos**（`sunshine-orchestrator.yaml`）：

```yaml
rag:
  base-url: http://ecs4c16g:8400
  search:
    default-top-k: 3
```

**代码**：

| 组件 | 改动 |
|------|------|
| `RagSearchProperties` | orchestrator 读取 `default-top-k` |
| `RagTool` | topK 从配置读取 |
| `RagNodeHandler` | 节点无 topK 时用同一默认 |
| `RagContextFormatter` | 统一 `formatHits(hits, mode)` |

**验收**：同 mock hits Tool/Node 输出一致；`rag_eval` 与 wf-knowledge 命中一致。

---

## 7. 2.13 审计 Tool 摘要

`AuditService` payload 扩展：

```json
{
  "contentLen": 1200,
  "hasReasoning": true,
  "hasSteps": true,
  "stepsSummary": {
    "toolNames": ["list_finance_messages"],
    "stepCount": 5,
    "totalDurationMs": 3200
  }
}
```

从 `chat_message.steps` 解析；`GET /api/audit/recent` 保持兼容，payload 含新字段。

验收：finance-smart 后 recent 审计可见 `toolNames`。

---

## 8. 2.14 规则硬路由

```
userQuery → RuleBasedRouter → 命中则 ExecutionPlan + ruleId
           └─ 未命中 → IntentRouter (flash LLM)
```

**Nacos** `agent.routing.rules`：见 `golden-set-schema` 同类文档；支持 `priority`、`match: any|all`、简单正则。

**文件**：

- `RuleBasedRouter.java`
- `RoutingRuleProperties.java`
- `IntentRouter` 委托前调用

验收：单测「有哪些待审批报销」→ `finance-list`，无 intent HTTP 调用。

---

## 9. 2.15 Catalog 白名单校验

`DynamicToolkitFactory.build()` 结束时：

- 白名单每个 tool 必须在 Catalog（含本地 rag）
- 缺失 → `log.error` 列出，不静默跳过

不做 `tools: auto`。

---

## 10. 2.16 文档

| 文件 | 内容 |
|------|------|
| `CLAUDE.md` | 收尾命令、RAG SOP |
| `phase2-closure-plan.md` | 指向本 spec |
| `implementation-plan.md` | 检查门状态 |
| `docs/rag/README.md` | 清库→入库→评测 |

---

## 11. 排期

| 周 | 任务 |
|:--:|------|
| 1 | 2.12 语料 + rebuild API + 三脚本 + golden-set + baseline |
| 2 | 2.11 Legacy + Rag 统一 + 2.14 规则路由 |
| 3 | 2.10 `--suite all` + 2.13 审计 + 前端手动 |
| 4 | 2.15 + 2.16 + 全量回归缓冲 |

---

## 12. 总检查门

- [ ] `rag_reset` → `ingest_bulk` → `rag_eval` 全量报告归档  
- [ ] `phase2_agent_demo.py --suite all` ecs4c16g 全 PASS  
- [ ] `LegacyWorkflowExecutor` 已删除  
- [ ] Rag 双路径统一配置  
- [ ] 规则路由单测绿  
- [ ] 审计含 `toolNames`  
- [ ] `mvn test -pl orchestrator,rag-service` 绿  
- [ ] 前端 Timeline 手动 2 轮  

---

## 13. 与阶段三衔接

| 阶段二产出 | 阶段三消费 |
|------------|------------|
| golden-set 60–80 条 + baseline | 3.4 混合检索 / Rerank 前后对比 |
| `rag_eval.py` | CI 回归门禁 |
| 规则路由 | Planner / HITL 策略叠加 |
| tool 摘要审计 | 3.6 全链路 tool audit |
| Admin rebuild API | 语料版本重建、租户 partition |

---

## 14. 模块改动范围

| 模块 | 改动 |
|------|------|
| `rag-service` | Admin rebuild API |
| `orchestrator` | Legacy 删除、Rag 统一、RuleRouter、Audit payload |
| `scripts` | rag_reset / ingest_bulk / eval；phase2 demo --suite |
| `docs/knowledge` | +6 篇语料 |
| `docs/rag` | golden-set 扩充、schema、README |
| `sunshine-ui` | 无（手动验收 only） |

---

## 15. 自审清单

- [x] 无 TBD / 占位符（语料正文在实施时撰写，结构已定义）
- [x] 与 locked-architecture-decisions、implementation-plan 一致
- [x] 范围聚焦阶段二收尾，不含阶段三实现
- [x] RAG 清库、语料、评测闭环完整
- [x] Legacy 与 Rag 统一路径明确

---

## 16. 下一步

用户审阅本 spec 通过后，使用 **writing-plans** 产出 `docs/superpowers/plans/2026-06-20-phase2-closure.md` 实施计划（按 Task 分步，含 checkbox）。
