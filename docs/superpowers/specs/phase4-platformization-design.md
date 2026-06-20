# 阶段四：平台化 — 技术设计（SSOT）

> **周期**：按需启动（子项独立排期）  
> **状态**：⬜ 按需  
> **触发**：阶段三检查门通过 + 业务接入量/运维复杂度达阈值  
> **前置**：[阶段三](./phase3-production-hardening-design.md) 文本 RAG hybrid+rerank 稳定

---

## 1. 启动条件

| 条件 | 说明 |
|------|------|
| 阶段三 17 条检查门通过 | 含 v5/v6 评测、PLAN_WORKFLOW、租户、HITL |
| 业务阈值（任一） | 语料 >20 篇需运营自助；PDF/扫描件入库需求；多副本部署；异构系统接入 |

---

## 2. 任务总览

| 任务卡 | 摘要 | 触发 | 优先级 |
|--------|------|------|:------:|
| **4.1** | RAG 平台化：多知识库、运营后台、评测周报、检索调试 | 语料增长 | **高** |
| **4.2** | 文档 OCR 入库 L1：PDF/图片 → 文本 chunk | 非 Markdown 语料 | **高** |
| **4.3** | 文档理解 L2：版面/表格 + quarantine | L1 稳定后 | 中 |
| **4.4** | 多模态对话 L3：Vision + `/chat` 附件 | 拍图问一问 | 中 |
| **4.5** | Skills Docker 沙箱 | 代码执行 skill | 中 |
| **4.6** | 动态 DAG 增强：if-else、并行 fan-out、Replan | 静态 workflow 不够 | 中 |
| **4.7** | 多 Agent 增强：Coordinator、并行子 Agent、MsgHub、子 Timeline 展开 | 复杂协作 | 中 |
| **4.8** | MCP 协议：Tool Manager 注册 MCP Server | 非 HTTP 遗留系统 | 中 |
| **4.9** | K8s：Helm + HPA + Nacos GitOps | 流量/HA | 中 |
| **4.10** | Seata 分布式事务 + HITL 串联 | 跨服务写操作 | 低 |
| **4.11** | Prompt 运营后台：版本/审核/回滚 | 提示词 >10 + 非研发维护 | 中 |
| **4.12** | Serverless 冷启动 | 调用量波动大 | 低 |

**建议顺序**：4.1 → 4.2 → 4.8 → 4.11 → 4.4 → 4.9 → 4.10 → 4.12

---

## 3. 任务详设

### 4.1 RAG 平台化

| 子任务 | 内容 |
|--------|------|
| **4.1.1** | 知识库 `namespace`：`tenant/dept/kbId` 三级 |
| **4.1.2** | 文档版本：新 v 入库自动失效旧 chunk |
| **4.1.3** | `scripts/rag_reindex.py` 全量重建 + 进度 |
| **4.1.4** | Admin API：`POST /api/kb/{kbId}/evaluate` |
| **4.1.5** | 前端检索调试页（vector/bm25/rerank 分数瀑布） |
| **4.1.6** | Badcase：`POST /api/rag/feedback` → 回流 golden-set |
| **4.1.7** | 策略 A/B：Nacos `rag.experiments` |
| **4.1.8** | 评测周报 Cron |

**检查门**：UI 上传 5 分钟内可检索；v2 入库后 v1 不可检；周报自动生成；调试页可见各阶段分数。

### 4.2 文档 OCR 入库（L1）

**OCR 锁定**：千问 DashScope（与 Embedding 同账号）；电子版 PDF 优先本地文本层，失败再走 OCR。

| 子任务 | 内容 |
|--------|------|
| **4.2.1** | `POST /api/rag/ingest/file` + 类型检测 |
| **4.2.2** | DashScope OCR + PDF 文本层抽取 |
| **4.2.3** | → Markdown 规范化 → 现有 `MarkdownParser` → Milvus |
| **4.2.4** | `/knowledge` 扩展 PDF/图片上传 |
| **4.2.5** | ocr golden-set + `rag_eval` 扩展 |

**原则**：产出文本后复用阶段三 hybrid+rerank；不为 OCR 改 Milvus 主 schema（用 `source_type` metadata）。

详设历史稿：`2026-06-21-multimodal-ocr-design.md` §1–3

### 4.3 文档理解 L2

| 子任务 | 内容 |
|--------|------|
| **4.3.1** | 表格/多栏版面（`qwen-doc-parse`） |
| **4.3.2** | 低置信度 quarantine 队列 |
| **4.3.3** | 脱敏后再 embed |

### 4.4 多模态对话 L3

| 子任务 | 内容 |
|--------|------|
| **4.4.1** | LLM Gateway vision 路由（Qwen-VL） |
| **4.4.2** | `/chat` 图片附件 + BFF 暂存 |
| **4.4.3** | Grounding 强制引用 OCR 原文 |

### 4.5 Skills Docker 沙箱

| 子任务 | 内容 |
|--------|------|
| **4.5.1** | `SandboxExecutor` + `sunshine-sandbox-python:3.11-slim` |
| **4.5.2** | 沙箱审计 + Grafana 指标 |
| **4.5.3** | `/skills` 试跑子 Agent（调试） |

锁定：`network=none`，`read_only_rootfs`（原 locked D4）。

### 4.6 动态 DAG 增强

| 子任务 | 内容 |
|--------|------|
| **4.6.1** | `IfElseNodeHandler` |
| **4.6.2** | `ParallelNodeHandler` fan-out/join |
| **4.6.3** | Plan 缓存与 Replan |
| **4.6.4** | P5 `ContextCompressor`（STM 工具结果摘要） |

### 4.7 多 Agent 增强

| 子任务 | 内容 |
|--------|------|
| **4.7.1** | M8 `DelegateSkillTool`（主 Coordinator react 委派） |
| **4.7.2** | M10 并行子 Agent fan-out/join |
| **4.7.3** | M11 MsgHub Peer（maxRounds≤3，transcript 审计） |
| **4.7.4** | M9 前端子 Agent 详情展开 UI |

### 4.8 MCP 协议

- `McpToolAdapter` → Catalog `kind=mcp`
- 检查门：注册 1 个 MCP Server，ReAct 可调用

### 4.9 K8s 生产部署

| 子任务 | 内容 |
|--------|------|
| **4.9.1** | Helm：gateway / bff / orchestrator / rag / llm-gateway |
| **4.9.2** | HPA + 滚动更新零中断 SSE（配合阶段三 3.14 Job 锁） |
| **4.9.3** | Milvus/ES 有状态集评估 |
| **4.9.4** | Nacos GitOps |

### 4.10 Seata 分布式事务

- 写工具 `transactional: true`；TCC/SAGA 模板；与 **3.3 HITL** 确认后开启

### 4.11 Prompt 运营后台

- `prompt_version` 表；草稿→审核→发布 Nacos；与 **4.1.7** 实验联动 rag_eval

### 4.12 Serverless

- 仅无状态服务（rag、llm-gateway 适配器）缩容；orchestrator + Redis 保持热实例

---

## 4. 阶段演进关系

```
阶段一 → 底座 + 会话 + SSE 重连
阶段二 → 三模式 + Workflow + RAG 基线 + Timeline V2
阶段三 → hybrid RAG + 租户 + HITL + PLAN_WORKFLOW + Skills
阶段四 → 运营平台 + OCR + 沙箱 + MCP + K8s + …
```

---

## 5. 检查门（按启动子项）

| 子项 | 核心检查 |
|------|----------|
| 4.1 | 运营自助上传 + 调试页 + 周报 |
| 4.2–4.3 | PDF/发票 OCR 入库可检索 + ocr eval |
| 4.4 | 聊天发图识图 + Grounding |
| 4.5 | Docker 沙箱执行 + 审计 |
| 4.8 | MCP 工具可调用 |
| 4.9 | 3 副本 orchestrator 滚动无中断 |

---

## 6. 相关文档

- [阶段三](./phase3-production-hardening-design.md)
- `docs/rag/golden-set.yaml`
- 历史详设：`2026-06-21-multimodal-ocr-design.md`、`2026-06-19-advanced-capabilities-design.md`
