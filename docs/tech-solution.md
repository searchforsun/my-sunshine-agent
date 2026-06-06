## 完整架构设计与技术方案

> **最后更新**：2026-06-06 | **实际版本基线**：JDK 21 + Spring Boot 3.2.9 + AgentScope 1.0.7

### 一、总体设计理念

本方案旨在构建一个**企业级 AI 中台**，核心原则如下：

- **编排引擎标准化**：采用 **AgentScope-Java**（阿里巴巴通义实验室）多智能体框架，利用其原生 ReActAgent 实现复杂任务编排，替代自研流程引擎。
- **基础设施全开源**：Nacos、Sentinel、RocketMQ、SkyWalking、Milvus、Redis、MySQL/PostgreSQL 均采用社区版自建，零云商业产品依赖。
- **AI 能力云化**：对话模型与嵌入模型调用云厂商 API（通义千问、DeepSeek、智谱 GLM 等），降低 GPU 运维成本，聚焦上层业务逻辑。
- **认证轻量化**：使用 **Sa-Token** 实现统一身份认证与鉴权，替代重型 IAM 系统，与 Spring Cloud Gateway 深度集成。
- **可观测性一体化**：以 **SkyWalking** 为核心全链路追踪，搭配 Prometheus + Grafana 实现指标监控与告警，补充 LLM 专项可观测（Langfuse / OpenTelemetry）。

---

### 二、技术选型与版本基线

| 领域 | 组件 | 实际版本 | 说明 |
|------|------|---------|------|
| **JDK** | OpenJDK | 21 LTS | 长期支持版 |
| **Spring Boot** | Spring Boot | 3.2.9 | 企业级微服务基座 |
| **Spring Cloud** | Spring Cloud | 2023.0.3 | 与 SB 3.2.x 配套 |
| **Spring Cloud Alibaba** | SCA | 2023.0.3.4 | Nacos/Sentinel/RocketMQ/Seata 版本管理 |
| **Agent 编排** | AgentScope-Java | 1.0.7 | ReActAgent 多步推理、工具调用、反思机制 |
| **对话模型** | DeepSeek V4 / Qwen3.5 / GLM-5.1 | — | 大模型网关统一接入 |
| **嵌入模型** | 通义 Embedding (text-embedding-v4) | — | 文本向量化 |
| **API 网关** | Spring Cloud Gateway + Nacos v2.3.1 | — | 动态路由、鉴权、限流 |
| **BFF** | Spring WebFlux + WebClient | — | SSE 流式转发 |
| **大模型网关** | 自研 Spring Boot 服务 | — | `/v1/chat/completions`，多厂商路由、Redis 缓存、Sentinel 熔断 |
| **RAG 检索** | Spring Boot + Milvus 2.6 | — | 向量检索，可选 BM25 + BGE-Reranker |
| **认证授权** | Sa-Token | 1.45.0 | JWT 签发/校验、权限注解 |
| **数据脱敏** | 自研 Spring Boot 微服务 | — | 正则 + AhoCorasick |
| **数据库** | MySQL 8.0.35 | — | 元数据、配置 |
| **缓存** | Redis 7.2 | — | 会话、业务缓存、模型精确缓存 |
| **消息队列** | Apache RocketMQ | 5.3.2 | 审计日志、异步解耦 |
| **可观测性** | SkyWalking 9.7.0 + Prometheus + Grafana | — | 全链路追踪、指标、日志关联 |
| **配置/注册中心** | Nacos | 2.3.1 | 服务发现与动态配置管理 |
| **向量数据库** | Milvus | 2.6.16 | 开源向量检索，支撑知识库 |
| **分布式事务** | Seata（按需） | 1.7.1 | AT/TCC/SAGA/XA |

#### 2026 年对话模型 API 价格参考（每百万 Token）

| 模型 | 输入（缓存未命中） | 输入（缓存命中） | 输出 | 适用场景 |
|------|-------------------|-----------------|------|---------|
| **DeepSeek-V4-Flash** | ¥1 | ¥0.02 | ¥2 | 高并发、高缓存命中 |
| **DeepSeek-V4-Pro** | ¥3 | ¥0.025 | ¥6 | 复杂推理、Agent |
| **Qwen3.5-Flash** | ¥0.2 | — | 阶梯 | 轻量任务 |
| **Qwen3.5-Plus** | ¥0.8 | — | ¥4.8 | 性价比之选 |
| **Qwen3.7-Max** | ¥12 | — | ¥36 | 极致性能 |
| **GLM-5.1** | ¥6 | — | ¥24 | 代码/长程 Agent |

---

### 三、架构分层设计

```
接入层
  ├── API 网关（Spring Cloud Gateway + Sa-Token 鉴权 + Sentinel 限流）
  └── BFF（WebFlux + SSE 转发）

认证中心
  └── Sa-Token（登录 / Token 签发 / 权限校验）

AI 核心
  ├── Orchestrator（AgentScope ReActAgent 编排）
  ├── Tool Manager（业务 API 包装为 Tool）
  ├── RAG Service（Milvus + 云 Embedding + 重排）
  ├── LLM Gateway（多厂商路由 / 缓存 / 熔断）
  └── Prompt Manager（MySQL + Nacos 热更新）

业务微服务
  ├── OA 服务 / 财务服务 / HR 服务

基础设施
  ├── Nacos（注册/配置中心）
  ├── Redis 集群 / MySQL / Milvus
  ├── RocketMQ / SkyWalking / Prometheus + Grafana
  └── Langfuse（LLM 专项可观测）

云厂商 AI 服务
  └── 对话模型 API / 嵌入模型 API
```

---

### 四、各层详细设计

#### 1. 接入层

**API 网关**
- 基于 Spring Cloud Gateway，路由规则配置在 Nacos，支持动态刷新。
- 集成 `sa-token-reactor-spring-boot3-starter`，对进入请求自动校验 JWT 有效性。
- 解析后用户信息（userId、tenantId）放入请求头 `x-user-id`、`x-tenant-id` 向下透传。
- 使用 Sentinel 进行网关层限流与熔断。

**BFF**
- 采用 Spring WebFlux 构建，提供 SSE 流式接口给前端。
- Orchestrator 返回的 SSE 流直接透传至前端，BFF 不做业务处理。

#### 2. 认证中心（Sa-Token）

- 独立微服务 `auth-center`，基于 Spring Boot + Sa-Token。
- Token 采用 JWT 模式，降低服务间鉴权开销；会话和令牌信息缓存于 Redis。
- 网关层集成 Sa-Token 依赖，自动读取 Redis 中的权限数据完成校验。

#### 3. AI 核心模块

**Orchestrator (AgentScope-Java)**
- 启动时构建 `ReActAgent` 实例，注入模型客户端（指向大模型网关）、工具集 Toolkit、系统提示词（从 Nacos 动态加载）。
- 工作流程：Agent 接收用户问题 → 思考是否需要工具 → 调用工具 → 整合结果反思 → 流式输出。
- 流式响应通过 `Flux<String>` + SSE 返回，BFF 直接订阅并推向前端。

**大模型网关**
- 自研服务，对外提供 OpenAI 兼容的 `/v1/chat/completions`。
- 多厂商路由、精确缓存（MD5 哈希 Redis）、Sentinel 熔断降级、Token 计量。

**RAG 服务**
- 离线入库：文档上传 → 云 Embedding API 向量化 → 存入 Milvus。
- 在线检索：查询文本 → Embedding → Milvus 向量检索 → 返回文档片段。
- 对高频查询的 Embedding 结果进行 Redis 缓存。

**提示词管理**
- 提示词模板存储在 MySQL，通过 Nacos 监听配置变更，运行时动态刷新 Agent 的 System Prompt。

#### 4. 业务微服务
- 各业务系统（OA、财务、HR）保持独立，仅暴露标准 RESTful API。
- 通过 Sa-Token 的微服务权限组件保护接口。

#### 5. 基础设施与可观测性
- **SkyWalking**：所有 Java 服务注入 Java Agent，自动采集 Trace、Metrics。
- **Prometheus + Grafana**：Agent 调用量、工具成功率、LLM 延迟、Token 消耗、缓存命中率。
- **Langfuse**：LLM 专项可观测 — Token 追踪、Prompt 调试、成本分析。
- **日志中心**：Filebeat → Elasticsearch → SkyWalking UI 关联查看。

#### 6. 安全与治理
- 统一认证（Sa-Token）、数据脱敏、Sentinel 流量控制、多租户隔离。

---

### 五、实施计划

| 阶段 | 周期 | 主要任务 | 交付物 |
|------|------|---------|--------|
| **阶段〇** | 2周 | 中间件部署、项目骨架初始化 | Docker Compose + Maven 多模块 |
| **阶段一** | 8周 | LLM Gateway + ReActAgent + RAG + 全链路追踪 | 私有知识库问答原型 |
| **阶段二** | 8周 | Sa-Token 认证 + 业务工具封装 + 脱敏 + 审计 | 财务智能助手端到端 |
| **阶段三** | 6周 | 多 Agent 协作 + 多租户 + HITL + 监控告警 | 生产级高可用平台 |
| **阶段四** | 按需 | MCP 协议 + K8s + Seata + Serverless | 平台化能力 |

---

### 六、方案总结

该方案以 **AgentScope-Java** 为编排大脑，**Sa-Token** 为安全底座，采用全开源基础设施 + 云厂商 AI 服务的混合模式，兼顾了**自主可控**与**快速落地**。架构层次清晰，技术栈主流，适合大中型企业的私有化 AI 中台建设。
