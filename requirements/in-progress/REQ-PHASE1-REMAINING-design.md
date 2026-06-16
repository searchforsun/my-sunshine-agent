# REQ-PHASE1-REMAINING — 技术设计（收尾）

> **状态**：已批准（基于既有 spec 汇总）  
> **日期**：2026-06-16  
> **对标**：[implementation-plan.md](../../docs/implementation-plan.md)、[phase1-gap-closure-design.md](../../docs/superpowers/specs/2026-06-07-phase1-gap-closure-design.md)、[phase1.5-conversation-mvp-design.md](../../docs/superpowers/specs/2026-06-11-phase1.5-conversation-mvp-design.md)

## 适用规则

- 项目根 [`CLAUDE.md`](../../CLAUDE.md)：JDK 21、Spring Boot 3.2.9、AgentScope 1.0.7、不升级 Boot 3.3+
- dev-yml 变更须同步 [`docs/nacos/`](../../docs/nacos/) 并提醒更新 Nacos 线上配置
- 禁止保存临时脚本；SkyWalking agent jar 可 gitignore（`.gitignore` 已含 `!skywalking-agent.jar` 例外逻辑需确认）

## 架构摘要

本需求**不新增微服务**，仅在既有链路上补验收、接线与修复：

```
Browser → Gateway(:8000) → BFF(:8001) → Orchestrator(:8200) → LLM GW(:8300)
                              │                    ├── RAG(:8400)
                              │                    └── MySQL + Redis
                              └── SSE 透传 + 会话 CRUD
```

缺口补齐轨道（代码已存在，待验）：

| Track | 模块 | 能力 |
|-------|------|------|
| A | llm-gateway | QwenAdapter + ModelRouter |
| B | orchestrator | RagClient + RagTool → ReActAgent |
| C | orchestrator | @RefreshScope System Prompt + 独立 Memory Bean |
| D | gateway + docker | 显式路由 + SkyWalking Agent |
| E | orchestrator test | ChatIntegrationTest (@Tag integration) |
| F/G | orchestrator + bff + ui | 断点续传 + Redis Generation 重连 |
| Timeline | orchestrator + ui | Processing Timeline V2（Aggregator + 三态 UI） |

## 外部依赖与集成契约（Integration Contracts）

| ID | 依赖 | 端点/资源 | 用途 | 验证状态 |
|----|------|-----------|------|----------|
| IC-01 | Nacos | `ecs4c16g:8848`，Data ID `sunshine-orchestrator.yaml` 等 | Prompt 热更新、服务配置 | VERIFIED（本地 optional import） |
| IC-02 | LLM Gateway | `http://localhost:8300/v1/chat/completions` | DeepSeek / Qwen 路由 | VERIFIED |
| IC-03 | BFF | `http://localhost:8001/api/**` | SSE + 会话 CRUD | VERIFIED |
| IC-04 | API Gateway | `http://localhost:8000/api/**` | 统一入口转发 | PARTIAL（路由已配，默认未启） |
| IC-05 | SkyWalking OAP | `ecs4c16g:11800` | 全链路 Trace | UNVERIFIED（Agent 未注入） |
| IC-06 | RAG Service | `http://localhost:8400/api/rag/**` | 入库 + 检索 | VERIFIED |
| IC-07 | MySQL | `ecs4c16g:3306/sunshine_chat` | 会话持久化 | VERIFIED |
| IC-08 | Redis | `ecs4c16g:6379` | LLM 缓存 + Generation Stream | VERIFIED |
| IC-09 | DeepSeek API | 经 LLM Gateway | 对话推理 | VERIFIED |
| IC-10 | Qwen API | 经 LLM Gateway QwenAdapter | 备用模型 / Embedding | PARTIAL（Adapter 存在，curl 待验） |

## 关键决策

1. **Memory 隔离**：`SunshineAgent.buildInputs(history)` 已注入 DB 历史；全局 `InMemoryMemory` 仍可能在 ReAct 内部累积 — Task 7 改为 per-call 清空或移除 Agent Memory 依赖。
2. **验收优先于新功能**：P0 全部检查门通过后再做 P2 knowledge 重连。
3. **CI 默认 exclude `@Tag("integration")`**：与 `orchestrator/pom.xml` surefire 配置一致。

## 非目标

- 阶段二 Sa-Token / 财务工具 / 脱敏
- Langfuse / Grafana 大盘（可后续独立需求）
- 多 Orchestrator 实例分布式 Job 锁
