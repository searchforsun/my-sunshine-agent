# 阶段一：底座搭建 — 技术设计（SSOT）

> **周期**：8 周（核心）+ 阶段 1.5（~2 周）+ 阶段 1.6（~1 周）  
> **状态**：✅ 已完成  
> **目标**：BFF → Orchestrator → LLM Gateway → RAG 全链路可演示；会话持久化与 SSE 重连

---

## 1. 任务总览

| 任务卡 | 内容 | 状态 |
|--------|------|:----:|
| **1.1** | BFF SSE 转发（WebFlux 透传） | ✅ |
| **1.2** | LLM Gateway：DeepSeek 适配器 + 路由 + Redis 缓存 | ✅ |
| **1.3** | Orchestrator：AgentScope ReActAgent + 流式对话 | ✅ |
| **1.4** | RAG：Milvus + Embedding + 入库 + 检索 | ✅ |
| **1.5** | SkyWalking 探针 + Nacos 配置热更新 | ✅ |

### 1.5 会话 MVP（产品可用性）

| 任务卡 | 内容 | 状态 |
|--------|------|:----:|
| **1.5.1** | MySQL 会话/消息表 + Flyway | ✅ |
| **1.5.2** | 多轮上下文（DB history 注入 LLM） | ✅ |
| **1.5.3** | BFF 会话 CRUD + `conversationId` 贯通 | ✅ |
| **1.5.4** | 前端 chatStore 改后端数据源 | ✅ |
| **1.5.5** | `x-user-id` / `x-tenant-id` 轻量隔离 | ✅ |
| **1.5.6** | 断点续传：message 状态机 + resume API + 继续生成 UI | ✅ |

### 1.6 Redis SSE 无感重连

| 任务卡 | 内容 | 状态 |
|--------|------|:----:|
| **1.6.1** | GenerationJob + Redis Stream 缓冲 | ✅ |
| **1.6.2** | reconnect API（`afterSeq`）+ cancel/status | ✅ |
| **1.6.3** | BFF 透传 generation 端点 | ✅ |
| **1.6.4** | 前端 mount 自动 reconnect | ✅ |
| **1.6.5** | 集成测试（jedis-mock） | ✅ |

---

## 2. 架构要点

```
Browser → Gateway :8000 → BFF :8001 → Orchestrator :8200
                              ├─ llm-gateway :8300
                              └─ rag-service :8400
```

- 配置 SSOT：`docs/nacos/` → `sync_nacos.py`
- 会话：`chat_conversation` / `chat_message`；`message.status` 支持 interrupted → resume
- 生成流：Redis `sunshine:gen:{generationId}` + seq；断 SSE 后 `afterSeq` 重连

---

## 3. 检查门

### 3.1 阶段一核心

- [x] 上传文档 → RAG 问答可演示
- [x] LLM Gateway → DeepSeek 调用成功
- [x] BFF → Orchestrator → LLM SSE 流式正常
- [x] Nacos System Prompt 热更新生效

### 3.2 阶段 1.5

- [x] 同会话 3 轮追问上下文连贯
- [x] 越权访问会话 404
- [x] Stop / 刷新后可继续生成

### 3.3 阶段 1.6

- [x] 断 SSE 后 Redis 缓冲至 complete
- [x] `afterSeq` 重连 chunk 连续
- [x] generation 已停时 reconnect 410 → 降级 resume
- [x] 越权 reconnect 404

---

## 4. 已知缺口（移交后续阶段）

| 缺口 | 移交 |
|------|------|
| Sa-Token 正式认证 | 阶段二 2.1 |
| ReAct 工具链断点续传 | 阶段二 |
| 多 Orchestrator 实例 Job 锁 | 阶段三 **3.14** |
| MTM/LTM 记忆体系 | 阶段二 **2.17** |

---

## 5. 附录：阶段一缺口补齐（原 gap-closure）

四条可并行轨道（均已通过阶段一检查门覆盖）：

| 轨道 | 内容 |
|------|------|
| A | 多厂商路由（Qwen）预备 |
| B | RAG 入库 + Agent 调 `search_knowledge` |
| C | Nacos prompt 热更新验证 |
| D | SkyWalking 拓扑 + Gateway 集成测试 |

详设历史稿：`2026-06-07-phase1-gap-closure-design.md`、`2026-06-11-phase1.5-conversation-mvp-design.md`

---

## 6. 下一步

进入 [阶段二：标杆打通](./phase2-benchmark-design.md)
