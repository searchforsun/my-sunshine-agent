# Sunshine AI Platform

企业级 AI 中台 — 基于 AgentScope-Java + Spring Cloud Alibaba 的私有化智能体平台。

## 架构概览

```
前端 (Vue3 + Naive UI)
  │
  ▼
Gateway (:8000) ──▶ BFF (:8001) ──▶ Orchestrator (:8200) ──▶ LLM Gateway (:8300) ──▶ DeepSeek / Qwen API
                    │                      │
                    │              ┌───────┼───────┐
                    │              │       │       │
                    │         RAG (:8400) Tool   Prompt
                    │         Milvus 2.6  Manager Manager
                    │
               Auth Center (:8100)
               Sa-Token JWT
```

## 技术栈

| 层 | 组件 | 版本 |
|---|------|------|
| **JDK** | OpenJDK | 21 LTS |
| **框架** | Spring Boot + Spring Cloud + Spring Cloud Alibaba | 3.2.9 / 2023.0.3 / 2023.0.3.4 |
| **Agent** | AgentScope-Java（阿里通义实验室） | 1.0.7 |
| **认证** | Sa-Token（JWT + Redis） | 1.45.0 |
| **向量库** | Milvus | 2.6.16 |
| **消息队列** | Apache RocketMQ | 5.3.2 |
| **可观测** | SkyWalking + Prometheus + Grafana | 9.7.0 |
| **前端** | Vue 3 + TypeScript + Naive UI + Vite | — |

## 项目结构

```
my-sunshine-agent/
├── pom.xml                     # 父 POM（版本管控）
├── common/sunshine-common/     # 公共模块（R<T>、BizException、GlobalExceptionHandler）
├── gateway/         :8000      # Spring Cloud Gateway + Sentinel
├── bff/             :8001      # WebFlux + SSE 流式转发
├── auth-center/     :8100      # Sa-Token 认证中心
├── orchestrator/    :8200      # AgentScope ReActAgent 编排
├── tool-manager/    :8210      # 业务 API → Agent Tool 包装
├── llm-gateway/     :8300      # LLM 网关（多厂商路由/缓存/熔断）
├── rag-service/     :8400      # RAG 检索（Milvus + Embedding）
├── prompt-manager/  :8500      # 提示词管理
├── desensitize/     :8600      # 数据脱敏
├── oa-service/      :8700      # OA 模拟（阶段二）
├── finance-service/ :8710      # 财务模拟（阶段二）
├── sunshine-ui/     :5173      # 前端 WebUI
├── docker/                     # Docker Compose + SkyWalking Agent
├── scripts/                    # 启动脚本
└── docs/                       # 设计文档
```

## 快速开始

### 1. 环境要求

- JDK 21
- Maven 3.9+
- Node.js 22+
- 服务器中间件已部署（8.140.48.6）

### 2. 编译

```bash
# 后端
mvn clean package -DskipTests

# 前端
cd sunshine-ui && npm install && npm run build
```

### 3. 启动服务

```bash
# 按依赖顺序启动核心服务
java -jar llm-gateway/target/sunshine-llm-gateway-*.jar &    # :8300
java -jar orchestrator/target/sunshine-orchestrator-*.jar &  # :8200
java -jar bff/target/sunshine-bff-*.jar &                    # :8001

# 或使用脚本一键启动全部
bash scripts/start.sh
```

### 4. 启动前端

```bash
cd sunshine-ui && npm run dev    # http://localhost:5173
```

### 5. 测试

```bash
# 直接测试 LLM Gateway → DeepSeek
curl -X POST http://localhost:8300/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-pro","messages":[{"role":"user","content":"你好"}]}'

# 端到端 SSE 流式测试
curl -N -X POST http://localhost:8001/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "x-user-id: test" \
  -d '{"content":"你好，请介绍一下你自己"}'
```

## 服务器中间件

| 组件 | 端口 | 凭据 |
|------|------|------|
| Nacos | 8848/9848 | nacos / nacos |
| MySQL | 3306 | root / root123 |
| Redis | 6379 | redis123 |
| Milvus | 19530 | — |
| RocketMQ | 9876 | — |
| SkyWalking OAP | 11800 | — |
| SkyWalking UI | 8084 | — |
| Sentinel Dashboard | 8858 | sentinel / sentinel123 |
| Grafana | 3000 | admin / admin123 |
| Elasticsearch | 9200 | — |

## 环境变量

```bash
export DEEPSEEK_API_KEY=sk-xxx    # DeepSeek API Key
export QWEN_API_KEY=sk-xxx        # 通义千问 API Key（Embedding 也复用此 Key）
```

## 实施阶段

| 阶段 | 状态 | 内容 |
|------|:--:|------|
| 阶段〇 | ✅ | 环境准备：中间件部署 + 项目骨架 |
| 阶段一 | ✅ | 底座搭建：LLM Gateway + ReActAgent + RAG + SSE |
| 阶段二 | ⬜ | 标杆打通：Sa-Token 认证 + 财务工具 + 脱敏 + 审计 |
| 阶段三 | ⬜ | 生产加固：多 Agent + 多租户 + HITL + 监控告警 |
| 阶段四 | ⬜ | 平台化：MCP + K8s + Seata + Serverless（按需） |

## 文档

- [技术方案设计](./docs/tech-solution.md) — 完整架构设计与技术选型
- [实施计划](./docs/implementation-plan.md) — 分阶段任务卡与检查门
