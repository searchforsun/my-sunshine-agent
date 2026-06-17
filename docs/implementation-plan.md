## 分阶段实施方案

> **前提约束**：兼职投入（1-2天/周）、2-3人全栈小团队、探索型节奏
> **对标方案**：[tech-solution.md](./tech-solution.md)

---

### 总览：里程碑时间线

```
周 0─1  ████████ 阶段〇：环境准备（2周）

周 2─9  ████████████████████████████████ 阶段一：底座搭建（8周）

周10─17 ████████████████████████████████ 阶段二：标杆打通（8周）

周18─23 ████████████████████████████████ 阶段三：生产加固（6周）

后续    ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ 阶段四：平台化（按需）
```

---

### 项目结构

```
my-sunshine-agent/
├── pom.xml                          # 父 POM（版本管控）
├── common/sunshine-common/          # 公共模块：R<T>、BizException、GlobalExceptionHandler
├── gateway/            :8000        # API 网关
├── bff/                :8001        # SSE 流式转发
├── auth-center/        :8100        # Sa-Token 认证
├── orchestrator/       :8200        # AgentScope ReActAgent 编排
├── tool-manager/       :8210        # 业务 Tool 包装
├── llm-gateway/        :8300        # 大模型网关（多厂商路由/缓存/熔断）
├── rag-service/        :8400        # RAG 检索（Milvus + Embedding）
├── prompt-manager/     :8500        # 提示词管理
├── desensitize/        :8600        # 数据脱敏
├── oa-service/         :8700        # OA 模拟
├── finance-service/    :8710        # 财务模拟
├── sunshine-ui/        :5173        # Vue3 + Naive UI 前端
├── docker/                          # Docker Compose + SkyWalking Agent
├── scripts/                         # 启动/演示脚本
└── docs/                            # 设计文档
```

---

### 阶段〇：环境准备（2周）✅ 已完成

| 任务 | 产出 | 状态 |
|------|------|:--:|
| 中间件部署 | 服务器 ecs4c16g 部署 Nacos/Redis/MySQL/Milvus/RocketMQ/SkyWalking 等 | ✅ |
| 项目骨架 | Maven 多模块 + 11 服务 + 公共模块 | ✅ |
| 前端骨架 | Vue3 + Naive UI + Vite + TypeScript | ✅ |

---

### 阶段一：底座搭建（8周）✅ 核心已完成

| 任务卡 | 内容 | 状态 |
|--------|------|:--:|
| 1.1 BFF SSE 转发 | WebFlux + SSE 透传 | ✅ |
| 1.2 LLM Gateway | DeepSeek V4 适配器 + 路由 + Redis 缓存 | ✅ |
| 1.3 Orchestrator | AgentScope ReActAgent + 流式对话 | ✅ |
| 1.4 RAG 服务 | Milvus 向量库 + Embedding + 文档入库 + 检索 | ✅ |
| 1.5 全链路追踪 | SkyWalking 探针 + Nacos 配置热更新 | ✅ |

#### 阶段一检查门
> 自动化脚本：[`scripts/phase1-demo.ps1`](../scripts/phase1-demo.ps1) / [`scripts/phase1-demo.sh`](../scripts/phase1-demo.sh)（2026-06-14 本地联调通过 Step 1–4）

- [x] 上传文档 → RAG 知识库问答可演示 — 入库 14 chunk + 检索命中 + BFF 知识库流
- [x] LLM Gateway → DeepSeek 调用成功
- [x] BFF → Orchestrator → LLM Gateway SSE 流式输出正常
- [x] 修改 Nacos System Prompt → Agent 行为即时变化（live Nacos 热更新已验，2026-06-16）

---

### 阶段 1.5：会话 MVP（可选，约 2 周）✅ 核心已完成

> 技术设计 spec：[superpowers/specs/2026-06-11-phase1.5-conversation-mvp-design.md](./superpowers/specs/2026-06-11-phase1.5-conversation-mvp-design.md)  
> 实施计划：[superpowers/plans/2026-06-11-phase1.5-conversation-mvp.md](./superpowers/plans/2026-06-11-phase1.5-conversation-mvp.md)

介于阶段一与阶段二之间，补齐**产品可用性**（非阶段一正式交付物）：

| 任务卡 | 内容 | 状态 |
|--------|------|:--:|
| 1.5.1 | MySQL 会话/消息表 + JPA + Flyway | ✅ |
| 1.5.2 | Orchestrator 多轮上下文（DB history 注入 LLM/Agent） | ✅ |
| 1.5.3 | BFF 会话 CRUD + `conversationId` 全链路贯通 | ✅ |
| 1.5.4 | 前端 chatStore 改后端数据源 | ✅ |
| 1.5.5 | `x-user-id` 轻量隔离 + 集成测试 | ✅ |
| 1.5.6 | **断点续传**：message 状态机 + resume API + 周期性 flush + 继续生成 UI | ✅ |

#### 阶段 1.5 检查门
- [x] 同一会话 3 轮追问，上下文连贯 — `ConversationIntegrationTest.threeTurnContext_simpleIntent`
- [x] 换浏览器同用户可恢复历史（脚本 Step 7 / 前端手动验收）— 集成测试已验；跨浏览器 live 待中间件
- [x] 越权访问会话返回 404 — `ConversationIntegrationTest.forbiddenAccess_returns404`
- [x] Stop / 刷新后可「继续生成」，同一条 assistant 追加完成 — `streamInterrupted_*` + `resumeContinue_*`

---

### 阶段 1.6：Redis SSE 无感重连（约 1 周）✅ 核心已完成

> 技术设计 spec 同文档 **Track G**：[superpowers/specs/2026-06-11-phase1.5-conversation-mvp-design.md](./superpowers/specs/2026-06-11-phase1.5-conversation-mvp-design.md#track-g--redis-sse-缓冲--客户端无感重连阶段-16)  
> 实施计划：[superpowers/plans/2026-06-11-phase1.6-generation-reconnect.md](./superpowers/plans/2026-06-11-phase1.6-generation-reconnect.md)  
> **前置**：Track F（断点续传）已落地

| 任务卡 | 内容 | 状态 |
|--------|------|:--:|
| 1.6.1 | Orchestrator GenerationJob + Redis Stream 缓冲 | ✅ |
| 1.6.2 | GET reconnect API（`afterSeq`）+ cancel/status | ✅ |
| 1.6.3 | BFF 透传 generation 端点 | ✅ |
| 1.6.4 | 前端 active generation 指针 + mount 自动 reconnect | ✅ |
| 1.6.5 | 集成测试（jedis-mock）+ mock-server | ✅ |

#### 阶段 1.6 检查门
- [x] 中途断 SSE 后 Redis 仍缓冲至 complete 或 orphan-timeout — `GenerationReconnectIntegrationTest.generationBuffersWhileNoSubscriber`
- [x] `afterSeq` 重连 chunk 连续、不重复开新 assistant — `reconnectAfterSeq_resumesStream`
- [x] generation 已停时 reconnect 410，自动降级「继续生成」（Track F）— `reconnectWhenInterrupted_returns410` + `reconnect410ThenResume_succeeds`（前端降级 mock 已验；live 点验待中间件）
- [x] 越权 reconnect → 404 — `reconnectForbiddenUser_returns404`

---

### 阶段二：标杆打通（8周）✅ MVP 核心已完成

| 任务卡 | 内容 | 状态 |
|--------|------|:--:|
| 2.1 Auth Center | Sa-Token JWT 认证中心 + Gateway 鉴权 — 设计：[REQ-PHASE2-AUTH-design.md](../requirements/in-progress/REQ-PHASE2-AUTH-design.md) | ✅ MVP |
| 2.2 Tool Manager | 业务 API → AgentScope Tool 包装机制 | ✅ 单工具标杆 |
| 2.3 Finance Service | 财务微服务模拟（消息/交易 API） | ✅ 消息 Mock |
| 2.4 端到端串联 | 财务智能助手全链路演示 | ✅ |
| 2.5 Data Desensitize | 正则 + AhoCorasick 脱敏引擎 | ✅ 正则 MVP |
| 2.6 多厂商路由 | Qwen Adapter + Sentinel 熔断降级 | ✅ 进程内熔断 |
| 2.7 RocketMQ 审计 | 审计日志异步发送 + Elasticsearch 落盘 | ✅ |
| 2.8 联调演示 | 一键演示脚本 + 问题修复 | ✅ |

> **自动化脚本**：[`scripts/phase2-demo.ps1`](../scripts/phase2-demo.ps1)（含 2.4 Agent 全链路，可用 `PHASE2_SKIP_AGENT=1` 跳过）；[`scripts/phase2-agent-demo.ps1`](../scripts/phase2-agent-demo.ps1)（单独跑 2.4）；[`scripts/phase2-auth-demo.ps1`](../scripts/phase2-auth-demo.ps1)

#### 阶段二检查门
- [x] JWT 校验：无效 Token → 401（`scripts/phase2-auth-demo.ps1` Step 5）
- [x] Agent 调用财务工具 → 查询消息列表（`scripts/phase2-agent-demo.ps1` 全链路 + `phase2-demo.ps1` 分层 HTTP 探测）
- [x] 脱敏：手机号/身份证号自动过滤（`scripts/phase2-demo.ps1` + desensitize-service）
- [x] LLM 故障 → 自动切换备用模型（`ModelRouter` 降级链 + `AdapterCircuitBreaker` 单测）
- [x] RocketMQ 审计日志完整（`sunshine-audit` topic + MySQL/ES 双写 + `/api/audit/recent`）

#### 阶段二已知缺口（不阻塞进入阶段三）
- ~~2.2 Tool Manager 尚未做成可注册通用框架~~ → **已落地 `ToolRegistry`（Task 11）**；新增工具仍须实现 `ToolHandler` Bean
- 2.3 Finance 为内存 Mock，无交易 API / 持久化
- 2.5 脱敏未实现 AhoCorasick 与可配置规则库
- 2.6 熔断为进程内 `AdapterCircuitBreaker`，**Sentinel Dashboard 联调为后续增强**
- 2.1 认证无登录限流 / Refresh Token / RBAC（设计非目标）
- 2.7 审计粒度为 assistant 消息终态元数据，未覆盖 tool call 细项

---

### 阶段 2.9：Workflow 编排架构 ✅ 已完成

> 设计 spec：[superpowers/specs/2026-06-18-workflow-orchestration-design.md](./superpowers/specs/2026-06-18-workflow-orchestration-design.md)  
> 实施计划：[superpowers/plans/2026-06-18-workflow-orchestration.md](./superpowers/plans/2026-06-18-workflow-orchestration.md)

| 任务卡 | 内容 | 状态 |
|--------|------|:--:|
| 2.9.1 | `ExecutionPlan` 三模式路由 + `WorkflowCatalog` | ✅ |
| 2.9.2 | `WorkflowExecutor` 线性 DAG（start/rag/tool/llm/agent/answer） | ✅ |
| 2.9.3 | Nacos `sunshine-workflows.yaml` SSOT | ✅ |
| 2.9.4 | `ToolRegistry` 替代 switch 硬编码 | ✅ |
| 2.9.5 | Timeline 统一（think/tool/node-*，废弃 agent 容器步） | ✅ |
| 2.9.6 | `DynamicToolkit` + `RemoteToolProxy`（react 白名单） | ✅ |

#### 阶段 2.9 检查门
- [x] 意图 JSON 输出 `simple-llm | workflow | react` + workflowId
- [x] knowledge-qa / finance-list / finance-smart 三张 workflow 图可配置
- [x] orchestrator 单测全绿（`mvn test -pl orchestrator`）
- [ ] live 验收：`sync-nacos.ps1` 后 `phase2-agent-demo.ps1`（需中间件在线）

---

### 阶段三：生产加固（6周）

| 任务卡 | 内容 |
|--------|------|
| 3.1 多 Agent 协作 | MsgHub + Planner/Executor Agent |
| 3.2 多租户隔离 | Milvus Partition + Memory 隔离 + Sentinel 配额 |
| 3.3 Human-in-the-Loop | PreToolCallHook 暂停 → 人工确认 |
| 3.4 RAG 检索增强 | BM25 + 向量混合 + BGE-Reranker 精排 |
| 3.5 Grafana 告警 | Dashboard 大盘 + 4 条 Prometheus 告警规则 |

#### 阶段三检查门
- [ ] Planner + Executor 协作完成复杂任务
- [ ] 租户A数据对租户B不可见
- [ ] 写操作工具 → 前端确认对话框
- [ ] BM25 + 向量混合检索命中率提升
- [ ] Grafana Dashboard + 告警触发

---

### 阶段四：平台化（按需启动）

| 任务卡 | 触发条件 |
|--------|---------|
| 4.1 MCP 协议 | 有非 HTTP 协议遗留系统需被 Agent 调用 |
| 4.2 K8s 生产部署 | 流量增长，需要水平扩展 |
| 4.3 Seata 分布式事务 | 工具调用涉及跨服务写操作 |
| 4.4 独立提示词管理后台 | 提示词 >10 个 + 非技术人员管理 |
| 4.5 Serverless 冷启动 | 调用量波动大，降低闲置成本 |

---

### 前端模块

| 页面 | 路由 | 功能 |
|------|------|------|
| AI 对话 | `/chat` | SSE 流式聊天，消息列表，发送/停止/清空 |
| 知识库 | `/knowledge` | Markdown 文档上传入库 + 向量检索测试 |
| 系统状态 | `/status` | 11 微服务 + 12 中间件状态矩阵 |

**技术栈**：Vue 3 + TypeScript + Naive UI + Vite + Pinia + Vue Router

---

### 关键版本基线

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 21 LTS | — |
| Spring Boot | 3.2.9 | 企业微服务基座 |
| Spring Cloud | 2023.0.3 | 匹配 SB 3.2.x |
| Spring Cloud Alibaba | 2023.0.3.4 | 管理 Nacos/Sentinel/RocketMQ |
| AgentScope-Java | 1.0.7 | Maven Central 可用 |
| Sa-Token | 1.45.0 | 轻量认证 |
| Milvus | 2.6.16 | 向量数据库 |
| SkyWalking | 9.7.0 | 全链路追踪 |

---

### 服务器中间件（ecs4c16g）

| 组件 | 端口 | 凭据 |
|------|------|------|
| Nacos | 8848/9848 | nacos/nacos |
| MySQL | 3306 | root/root123 |
| Redis | 6379 | redis123 |
| Milvus | 19530/9091 | — |
| RocketMQ | 9876/10911 | — |
| SkyWalking OAP | 11800/12800 | — |
| SkyWalking UI | 8084 | — |
| Sentinel | 8858 | sentinel/sentinel123 |
| Grafana | 3000 | admin/admin123 |
| Prometheus | 9090 | — |
| Elasticsearch | 9200 | — |
| Seata | 8091 | — |
