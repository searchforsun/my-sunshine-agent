# 阶段一缺口补齐 — 技术设计

> 日期：2026-06-07 | 状态：待实施 | 对标：[tech-solution.md](../../tech-solution.md) + [implementation-plan.md](../../implementation-plan.md)

## 背景

4 条并行轨道，填补阶段一 6 个缺口。每条轨道改动不重叠，可独立开发。

## 轨道总览

| 轨道 | 模块 | 缺口 | 改动性质 |
|------|------|------|---------|
| A | llm-gateway | Qwen Adapter | 新增 1 个类 |
| B | orchestrator | RAG→Agent 集成 | 新增 2 个类 + 修改 AgentConfig |
| C | orchestrator + Nacos | 配置热更新 | 修改 application.yml + AgentConfig + 新增 bootstrap.yml |
| D | gateway + scripts + orchestrator | Gateway 路由 + SkyWalking + 集成测试 | 配置 + 脚本 + 新增 1 个测试类 |

## Track A — Qwen Adapter

### 问题
当前仅 `DeepSeekAdapter` 实现 `LlmAdapter`，`application.yml` 已配置 Qwen provider 但无对应适配器。

### 设计
新增 `QwenAdapter.java`，实现 `LlmAdapter` 接口。与 `DeepSeekAdapter` 唯一差异：base URL 已含 `/v1` 前缀，因此 `uri()` 调用路径为 `/chat/completions`（而非 `/v1/chat/completions`）。

### 改动文件
- **新增** `llm-gateway/src/main/java/com/sunshine/llm/adapter/QwenAdapter.java`

### 验证
```bash
curl -X POST http://localhost:8300/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"hello"}]}'
# 预期：ModelRouter 日志输出 qwen-plus → QwenAdapter，返回正常响应
```

---

## Track B — RAG → Agent 集成

### 问题
RAG Service 独立运行，Agent 的 Toolkit 为空（`new Toolkit()`），Agent 对话时不检索知识库。

### 设计
在 Orchestrator 中新增两类文件：
1. `RagClient` — WebClient 封装，HTTP 调用 RAG Service 的 `/api/rag/search`
2. `RagTool` — AgentScope Tool，标注 `@Tool` 注解，让 LLM 决定何时调用

Agent 思考 → 判断需要知识 → 调用 `search_knowledge(query)` → HTTP → RAG Service → Milvus 检索 → 返回片段 → Agent 整合回答

### 改动文件
- **新增** `orchestrator/src/main/java/com/sunshine/orchestrator/client/RagClient.java`
- **新增** `orchestrator/src/main/java/com/sunshine/orchestrator/agent/RagTool.java`
- **修改** `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentConfig.java` — toolkit 注入 RagTool

### 验证
```bash
# 1. 入库文档
curl -X POST http://localhost:8400/api/rag/documents \
  -H "Content-Type: application/json" \
  -d '{"content":"## 考勤制度\n员工每日工作8小时，弹性上下班。"}'

# 2. 知识库问答
curl -N -X POST http://localhost:8001/api/chat/stream \
  -H "x-user-id:test" -d '{"content":"考勤制度是什么？"}'
# 预期：Agent 自动调用 search_knowledge，基于知识库内容回答
```

---

## Track C — Nacos 配置热更新

### 问题
Agent System Prompt 通过 `@Value` 写死在 `application.yml`，修改 Nacos 不生效，需重启。

### 设计
1. 在 Nacos Console 创建 `sunshine-orchestrator.yaml`，承载 `agent.*` 配置
2. `application.yml` 通过 `spring.config.import: nacos:sunshine-orchestrator.yaml` 引入
3. `AgentConfig` 加 `@RefreshScope`，配置变更时重建 `ReActAgent` Bean
4. `InMemoryMemory` 独立为单独 Bean（不加 `@RefreshScope`），热更新 Prompt 不丢失对话历史

### 改动文件
- **修改** `orchestrator/src/main/resources/application.yml` — 增加 `spring.config.import` 和 `spring.cloud.nacos.config`
- **新增** `orchestrator/src/main/resources/bootstrap.yml` — 兜底配置（如需）
- **修改** `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentConfig.java` — `@RefreshScope` + Memory Bean 独立

### 验证
```bash
# 1. 启动后 Nacos Console 修改 system-prompt
# 2. curl 对话，观察 Agent 行为变化
# 预期：修改即时生效，无需重启
```

---

## Track D — Gateway 路由 + SkyWalking + 集成测试

### D1：Gateway 路由
`gateway/src/main/resources/application.yml` 增加显式路由规则：
```yaml
spring.cloud.gateway.routes:
  - id: sunshine-bff
    uri: lb://sunshine-bff
    predicates: [Path=/api/**]
```

### D2：SkyWalking 全链路追踪
- 下载 `skywalking-agent.jar` 放到 `docker/skywalking/agent/`
- `scripts/start.sh` 每个服务启动命令增加 `SKYWALKING_OPTS` 变量：
  ```
  -javaagent:docker/skywalking/agent/skywalking-agent.jar
  -Dskywalking.agent.service_name=sunshine-{service-name}
  -Dskywalking.collector.backend_service=ecs4c16g:11800
  ```

### D3：集成测试
新增 `orchestrator/src/test/java/.../ChatIntegrationTest.java`，`@SpringBootTest` + `WebTestClient` 验证全链路通畅。

### 改动文件
- **修改** `gateway/src/main/resources/application.yml`
- **修改** `scripts/start.sh`
- **新增** `docker/skywalking/agent/skywalking-agent.jar`
- **新增** `orchestrator/src/test/java/com/sunshine/orchestrator/ChatIntegrationTest.java`

### 验证
```bash
# Gateway
curl http://localhost:8000/api/chat/stream -H "x-user-id:test" -d '{"content":"hi"}'
# 预期：200 + SSE 流

# SkyWalking
# 打开 http://ecs4c16g:8084 → 拓扑图可见 sunshine-bff → sunshine-orchestrator → sunshine-llm-gateway

# 测试
mvn test -pl orchestrator -am
# 预期：BUILD SUCCESS
```

---

## 验收清单

- [ ] `curl localhost:8300/v1/chat/completions -d '{"model":"qwen-plus",...}'` → QwenAdapter 路由成功
- [ ] 上传文档后问知识库问题 → Agent 自动检索并基于知识库回答
- [ ] Nacos Console 修改 System Prompt → Agent 行为即时变化
- [ ] `curl localhost:8000/api/chat/stream` → Gateway 转发成功
- [ ] SkyWalking UI 拓扑图展示完整调用链
- [ ] `mvn test -pl orchestrator -am` → BUILD SUCCESS（全链路集成测试）
