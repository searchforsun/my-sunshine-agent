# 可观测性增强（Logging / Metrics / Trace + LLM Run Explorer）设计

> **阶段**：阶段三可观测收口 + 阶段五运营化底座
> **状态**：🟡 设计评审中（2026-07-27 立项）
> **日期**：2026-07-27
> **定位**：贯穿 logging(Kibana) / metrics(Grafana) / trace(SkyWalking) 三台的端到端可观测闭环，补齐"跟随大模型思考与工具调用"的可视化观测能力
> **前置**：阶段三 3.5 可观测（Grafana RAG + Sentinel + SkyWalking 部署 ✅）；阶段四收口
> **依赖**：阶段五 5.1（反馈 + `/ops` 运营页）、5.2（token 用量落库 + 配额）、5.3（模型场景路由）已立项；本设计**不重复**其数据模型，复用其落库与聚合产物

---

## 1. 背景与缺口

### 1.1 现状盘点（实地勘察）

| 维度 | 已有 | 缺口 |
|------|------|------|
| **Logging** | 统一 `logback-spring.xml`（文件滚动 + ERROR 单独）；`AuditElasticsearchWriter` 审计落 ES；es01/02/03 + Kibana 已部署 | ① 业务日志无 traceId/MDC 注入，无法在 Kibana 与 SkyWalking 双向跳转；② 无 Filebeat 采集，业务日志散落各机本地文件，Kibana 只有审计索引；③ `[LLM-IO]` 等关键日志非结构化，难做字段聚合 |
| **Metrics** | rag-service + sandbox-service 引入 `micrometer-registry-prometheus`；RAG 指标完整（8 项）；Prometheus + Grafana 部署 + RAG/Sandbox dashboard 自动导入 | ① orchestrator/llm-gateway/tool/skill/workflow/expert-manager **缺 prometheus registry**（orchestrator 只有 actuator）；② LLM 核心指标全缺（调用耗时/token/工具调用/流式错误）；③ `rag-alerts.yml` 未挂载进 Prometheus（compose 注释明说待补） |
| **Trace** | SkyWalking 9.7.0（OAP+UI）部署；`start.py` 经 `skywalking_java_opts()` 自动挂 agent | ① agent 缺失时静默跳过，线上可能"以为有 trace 实际没有"；② 自动埋点只覆盖 HTTP/RPC，ReAct think->tool->generate、Plan 节点、peer 专家轮次等**业务语义 span 缺失**；③ SSE 流式链路无端到端 trace 串联 |
| **LLM 可观测** | `LlmIoTracer` 记录模型/stream 分片/字符数/tool_calls 摘要（仅 log.info）；`ChatCompletionResponse.Usage` 模型已有 | ① token 用量不落库、不打点（5.2 已规划，本设计复用）；② Timeline step 的 `durationMs`/`totalDurationMs` 只服务前端展示，未导出可观测平台；③ 无"按 run/会话查看 LLM 调用链"能力 |
| **前端** | Chat 时间线组件齐全（OperationStack/PlanExecutionCanvas/PlanNodeDrawer/ReasoningPanel/SubagentCard/PeerCollabPanel）；`RetrievalWaterfall` 已有瀑布雏形；Status 页做服务健康探活 | ① 无 Trace/Run Explorer 专页；② Status 页只探活，无 metrics；③ 未引入图表库（无 echarts/d3）；④ 审计 `/api/audit/recent` 无前端消费页 |

### 1.2 与阶段五 5.1/5.2/5.3 的分工边界

阶段五已规划运营化数据底座，本设计**不重复建表**，明确分工：

| 能力 | 归属 | 本设计关系 |
|------|------|-----------|
| 反馈 👍/👎 + `chat_message_feedback` 表 + 归因快照 | 5.1 / `feedback-eval-dashboard` spec | **数据来源**：Run Explorer 复用 `chat_message_feedback.trace_id`/`latency_ms`/`execution_mode` 做过滤与关联 |
| token 用量 `llm_usage_record` + `llm_usage_daily` + 配额 | 5.2 | **数据来源**：Run Explorer 与 Grafana LLM 面板复用用量表；LLM 指标埋点与 5.2 `TokenUsageCollector` 共用采集点 |
| 模型场景路由 + Grafana scene×model 面板 | 5.3 | **指标来源**：本设计补的 `llm_call_duration_seconds{scene,model}` 标签供 5.3 面板使用 |
| `/ops` 运营页（Badcase/用量/密钥/优化 Tab） | 5.1/5.2/5.6/5.4 | **并列**：本设计新增 `/observability` Run Explorer 页，定位"单次会话的 LLM 调用链与 trace 深潜"，与 `/ops` 的"跨会话运营聚合"互补 |
| `/evaluation` 评测大盘（反馈统计/踩样本归因/评测集回流） | `feedback-eval-dashboard` spec | **并列**：与 `/ops`（运营聚合）、`/observability`（单次会话深潜）三页分工，数据互不重复建表 |

**一句话**：5.x 解决"运营数据落库与聚合"，本设计解决"三台基础设施增强 + LLM 调用链可视化 + 三台联动"。

---

## 2. 目标与非目标

### 2.1 目标

1. **Logging 集中化 + traceId 关联**：业务日志经 Filebeat 进 ES，logback 注入 SkyWalking traceId，Kibana 可按 traceId 检索全链路日志并跳转 SkyWalking。
2. **Metrics 全服务覆盖 + LLM 指标**：所有 Java 服务暴露 Prometheus；补齐 LLM 调用耗时/token/工具调用/流式错误指标；告警规则落地。
3. **Trace 业务语义补全**：ReAct/Plan/peer 关键节点打业务 span；SSE 链路 trace 串联；agent 缺失有显式告警。
4. **前端 Run Explorer 观测页**：类 LangSmith，按会话/run 查看 LLM 调用瀑布（think->tool->generate，含 durationMs/token/status），并与 SkyWalking traceId 联动跳转。
5. **三台联动**：前端观测页 ↔ Kibana(日志) ↔ Grafana(指标) ↔ SkyWalking(trace) 经统一 traceId 串联。

### 2.2 非目标

- **不做**自建 LLM 可观测后端（不造 LangSmith 轮子）；Run Explorer 数据来自已有审计/步骤/5.2 用量表，前端聚合展示。
- **不做**通用 A/B 实验平台（5.7 仅 prompt 灰度，见 phase5 D1）。
- **不做**日志全量结构化改造（仅关键 logger：`[LLM-IO]`/`[Audit-ES]`/`tool_call`/`[LLM-GW]`）。
- **不重复** 5.1/5.2/5.3 的数据落库与聚合任务。
- **不引入** OpenTelemetry（已有 SkyWalking，不混用两套 trace 体系；见 §7 D1）。

---

## 3. 任务拆分

| 任务卡 | 内容 | 优先级 | 依赖 |
|--------|------|:------:|------|
| **6.1** | Logging 集中化 + traceId 关联 | P1 | SkyWalking agent ✅ |
| **6.2** | Metrics 全服务覆盖 + LLM 指标 + 告警落地 | **P0** | - |
| **6.3** | Trace 业务 span 补全 + SSE 串联 + agent 告警 | P1 | SkyWalking agent ✅ |
| **6.4** | 前端 Run Explorer 观测页（`/observability`） | **P0** | 6.2 LLM 指标 + 5.2 用量表 |
| **6.5** | 三台联动（traceId 贯穿 + 外链跳转） | P1 | 6.1/6.2/6.3 |

---

## 4. 详设

### 4.1 Logging 集中化 + traceId 关联（6.1）

#### 4.1.1 logback 注入 SkyWalking traceId

修改 `common/sunshine-common/src/main/resources/logback-spring.xml`，在 pattern 中加入 `%tid` 占位符（SkyWalking agent 的 `traceid-instrumentation` 自动将 `TID:` 注入 MDC）。

```xml
<!-- 控制台 pattern 追加 %tid -->
<pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) %cyan([%15.15thread]) %magenta([%-20.20logger{0}]) [%tid] - %msg%n</pattern>

<!-- 文件 pattern 同步追加 -->
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%tid] - %msg%n</pattern>
```

需在 agent 的 `config/agent.config` 确认 `plugin.tomcat.collect_http_params=true` 与 `traceID_inject=true`（默认开启，确认即可）。

#### 4.1.2 Filebeat 采集业务日志进 ES

`docker/docker-compose.yml` 新增 filebeat 服务，挂载各服务 `logs/` 目录：

```yaml
filebeat:
  image: elastic/filebeat:7.17.23
  container_name: smt-filebeat
  restart: unless-stopped
  user: root
  volumes:
    - ./filebeat/filebeat.yml:/usr/share/filebeat/filebeat.yml:ro
    - ../orchestrator/logs:/var/log/orchestrator:ro
    - ../llm-gateway/logs:/var/log/llm-gateway:ro
    - ../rag-service/logs:/var/log/rag:ro
    - ../tool-manager/logs:/var/log/tool-manager:ro
    - ../skill-manager/logs:/var/log/skill-manager:ro
    - ../workflow-manager/logs:/var/log/workflow-manager:ro
    - ../expert-manager/logs:/var/log/expert-manager:ro
    - ../sandbox-service/logs:/var/log/sandbox:ro
    - ../bff/logs:/var/log/bff:ro
    - ../gateway/logs:/var/log/gateway:ro
  command: ["-e", "-strict.perms=false"]
  networks:
    - smt-net
  depends_on:
    - es01
```

`docker/filebeat/filebeat.yml`：多路径 input -> ES index `sunshine-logs-%{+yyyy.MM.dd}`，解析 `%tid` 为 `trace_id` 字段。

#### 4.1.3 关键日志结构化

对 `[LLM-IO]`、`[Audit-ES]`、`[LLM-GW]`、`tool_call` 关键 logger，新增 `logback-spring.xml` 的 `<appender name="JSON_FILE">`（`LogstashEncoder`），输出到独立 `logs/{APP_NAME}-json.log`，Filebeat 单独采集至 `sunshine-llm-trace-*` 索引，供 Kibana 字段级检索 LLM 调用。

需在 `common/sunshine-common/pom.xml` 加 `net.logstash.logback:logstash-logback-encoder:7.4`。

#### 4.1.4 Kibana Index Pattern

新增索引模板：`sunshine-logs-*`（业务日志）、`sunshine-llm-trace-*`（LLM 结构化）、`chat_audit_log-*`（已有审计）。Kibana Discover 即可按 `trace_id` 跨三个索引检索同一请求的全链路日志。

### 4.2 Metrics 全服务覆盖 + LLM 指标 + 告警落地（6.2）

#### 4.2.1 补齐 prometheus registry

| 服务 | 现状 | 动作 |
|------|------|------|
| orchestrator | 仅 actuator | pom 加 `micrometer-registry-prometheus`；Nacos `sunshine-orchestrator.yaml` 加 `management.endpoints.web.exposure.include: health,info,prometheus` |
| llm-gateway | 无 | 同上 |
| tool-manager | 无 | 同上 |
| skill-manager | 无 | 同上 |
| workflow-manager | 无 | 同上 |
| expert-manager | 无 | 同上 |
| bff / gateway | 无 | gateway（Spring Cloud Gateway）原生支持，仅需配置暴露 |

#### 4.2.2 LLM 核心指标埋点（llm-gateway）

新增 `LlmMetricsRecorder`（`llm-gateway/.../trace/LlmMetricsRecorder.java`），与 `LlmIoTracer` 同包，复用 `ModelRouter` 的调用点（`invokeChat`/`invokeStream`）埋点：

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `llm_call_duration_seconds` | Timer | model, mode(chat\|stream), scene, status | LLM 调用端到端耗时 |
| `llm_tokens_total` | Counter | model, type(prompt\|completion), scene | token 用量（与 5.2 `TokenUsageCollector` 共用采集点） |
| `llm_tool_calls_total` | Counter | tool, mode(react\|plan) | ReAct 工具调用次数（来自 `LlmIoTracer` 的 toolCalls 解析） |
| `llm_stream_errors_total` | Counter | model, reason | 流式错误（超时/熔断/上游 5xx） |
| `llm_fallback_total` | Counter | from_model, to_model | 模型降级次数（`ModelRouter.tryFallback*`） |
| `llm_circuit_breaker_state` | Gauge | model | 熔断器状态（0=closed,1=open,2=half-open） |

`scene` 标签由 orchestrator 在 `ChatCompletionRequest` 注入（5.3.2 已规划），透传至 llm-gateway。

#### 4.2.3 编排与工具指标（orchestrator / tool-manager）

| 指标 | 类型 | 标签 | 服务 |
|------|------|------|------|
| `orch_execution_duration_seconds` | Timer | mode(react\|workflow\|plan-workflow\|peer-collab), status | orchestrator |
| `orch_intent_total` | Counter | route(L0\|L1\|L3), intent | orchestrator |
| `orch_step_total` | Counter | type(intent\|think\|tool\|generate\|plan\|rag\|expert), status | orchestrator |
| `orch_react_loops_total` | Counter | - | orchestrator |
| `orch_plan_replan_total` | Counter | reason | orchestrator |
| `tool_invoke_duration_seconds` | Timer | tool_id, status | tool-manager |
| `tool_hitl_total` | Counter | tool_id, action(confirm\|reject\|edit) | tool-manager |
| `wf_node_duration_seconds` | Timer | type(llm\|tool\|agent\|rag), status | orchestrator |

埋点位置：`ExecutionDispatcher`（execution 耗时/intent）、`AgentRuntime.run`（step/react loops）、`WorkflowExecutor`（node）、`ToolNodeHandler`/`CatalogRemoteAgentTool`（tool invoke）。

#### 4.2.4 Prometheus 抓取配置

`docker/prometheus/prometheus.yml` 补全所有服务 job：

```yaml
scrape_configs:
  - job_name: sunshine-orchestrator
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8300']
        labels: { application: sunshine-orchestrator }
  - job_name: sunshine-llm-gateway
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8500']
        labels: { application: sunshine-llm-gateway }
  # ... tool-manager:8310 / skill-manager:8225 / workflow-manager:8230 / expert-manager:8235 / sandbox:8240
```

#### 4.2.5 Grafana Dashboard 与告警

新增 dashboard JSON（`docker/grafana/provisioning/dashboards/json/`）：
- `llm-dashboard.json`：调用量/耗时 P50/P95/token/降级/熔断（按 model/scene）
- `orchestrator-dashboard.json`：执行耗时/意图分布/步骤分布/ReAct 轮次/Replan
- `tool-dashboard.json`：工具调用耗时/成功率/HITL 分布

告警规则落地（修复 phase3 遗留）：`docker/prometheus/prometheus.yml` 的 `rule_files` 已配 `rag-alerts.yml`，但 compose 未挂载。修 `docker-compose.yml` prometheus volumes 加 `- ../docs/grafana/rag-alerts.yml:/etc/prometheus/rag-alerts.yml:ro`，并新增 `llm-alerts.yml`：
- LLM P95 耗时 > 10s（告警）
- LLM 错误率 > 5%（告警）
- 模型熔断 open（告警）
- 熔断降级频次 > 10/min（告警）

### 4.3 Trace 业务 span 补全 + SSE 串联 + agent 告警（6.3）

#### 4.3.1 业务语义 span

SkyWalking 自动埋点只覆盖 HTTP/RPC，业务语义层级需手动 `@Trace` 或 `ContextManager.createEntrySpan`。在以下位置补 span：

| 位置 | span 名 | 标签 | 实现 |
|------|---------|------|------|
| `ExecutionDispatcher.dispatch` | `orchestrator.execution` | mode, intent, conversationId | `@Trace` + tag |
| `AgentRuntime.run` | `agent.run` | agentType(MAIN\|SUB\|PLANNER), skillId | `@Trace` |
| `ReActAgentRuntime` 循环体 | `react.loop` | iteration, toolCalled | `@Trace` opName 动态 |
| `WorkflowExecutor.executeNode` | `workflow.node` | nodeId, type, status | `@Trace` |
| `ExpertConsultationExecutor` | `expert.consult` | round, expertIds | `@Trace` |
| `ToolNodeHandler.invoke` / `CatalogRemoteAgentTool` | `tool.invoke` | toolId, hitl | `@Trace` |
| `KnowledgeRetrievalPipeline` | `rag.search` | strategy, hits | `@Trace`（rag-service） |

依赖：`apm-toolkit-trace`（SkyWalking 提供的 `@Trace` 注解包），各服务 pom 加 `org.apache.skywalking:apm-toolkit-trace:9.7.0`。agent 已部署，注解会被自动增强。

#### 4.3.2 SSE 链路 trace 串联

当前 SSE 链路：`ChatController` -> `ExecutionDispatcher` -> `GenerationJob`(Redis 缓冲+seq) -> BFF 透传 -> 前端 `parseSsePayload`。

问题：Gateway->BFF->orchestrator 的 HTTP 自动埋点 OK，但 orchestrator 内 `GenerationJob` 异步缓冲 + Redis 跨实例分发可能断链。

方案：
- orchestrator 在 SSE 首事件注入 `traceId` 字段（来自 SkyWalking `ContextManager.getGlobalTraceId()`）到 SSE payload；
- 前端 `parseSsePayload` 提取 `traceId`，Run Explorer 与"跳转 SkyWalking"共用；
- `GenerationJob` 跨实例分发时，在 Redis payload 携带 `traceId`，消费端用 `ContextManager.continueContext` 续接（如 SkyWalking agent 对 WebFlux 自动续接则无需手动）。

#### 4.3.3 agent 缺失显式告警

`scripts/start.py` 当前 `skywalking_agent().is_file()` 为 false 时只打 INFO。改为：
- 启动时若 agent 不存在，**打印显著警告**（`[WARN] SkyWalking agent missing, trace will be unavailable. Run: python scripts/download_skywalking_agent.py`）；
- 在 orchestrator `/actuator/info` 暴露 `skywalking.agent.loaded` 健康指标（检测 `ContextManager` 是否可用），供 Status 页与 Grafana 告警。

### 4.4 前端 Run Explorer 观测页（6.4）

#### 4.4.1 路由与定位

- 路由 `/observability`，菜单"运行观测"，视图 `ObservabilityView.vue`。
- **与 `/ops` 区分**：`/ops` = 跨会话运营聚合（Badcase/用量/密钥/优化）；`/observability` = 单次会话的 LLM 调用链与 trace 深潜（类 LangSmith 的 run 树）。

#### 4.4.2 页面结构

```
┌─────────────────────────────────────────────────────────┐
│ 顶部 KPI 卡片行                                          │
│ [会话数] [LLM 调用数] [P95 耗时] [token 总量] [错误率]    │
├──────────────────┬──────────────────────────────────────┤
│ 左：会话/Run 列表  │ 右：Run 详情                         │
│ ┌──────────────┐ │ ┌──────────────────────────────────┐ │
│ │ 过滤栏        │ │ │ 顶部：traceId + 模式 + 跳转       │ │
│ │ mode/model/  │ │ │ [SkyWalking trace] [Kibana 日志] │ │
│ │ 时间/踩样本   │ │ ├──────────────────────────────────┤ │
│ ├──────────────┤ │ │ Run 瀑布图（echarts）             │ │
│ │ conv-abc ... │ │ │ intent(120ms)                    │ │
│ │   └ react    │ │ │   think(800ms, 320 tok)          │ │
│ │   └ tool:rag │ │ │     tool:rag(450ms)              │ │
│ │ conv-def ... │ │ │   think-2(600ms, 280 tok)        │ │
│ │   └ plan     │ │ │   generate(1.2s, 500 tok)        │ │
│ └──────────────┘ │ ├──────────────────────────────────┤ │
│                  │ │ 步骤详情（选中后）                 │ │
│                  │ │ prompt / response / reasoning     │ │
│                  │ │ tool input/output                 │ │
│                  │ │ token / duration / status         │ │
│                  │ └──────────────────────────────────┘ │
└──────────────────┴──────────────────────────────────────┘
```

#### 4.4.3 数据来源 API

复用已有数据，不新建落库（与 5.x 分工）：

| 数据 | 来源 | 备注 |
|------|------|------|
| 会话/Run 列表 | `GET /api/audit/recent`（已有）+ `chat_message`（execution_mode/intent/latency_ms） | 按 mode/model/时间/反馈过滤 |
| Run 步骤瀑布 | `chat_message.steps`（已有 ProcessingStep JSON，含 durationMs/type/label） | 前端 `processingStepsParse.ts` 已能解析 |
| LLM 调用明细 | 5.2 `llm_usage_record`（messageId 关联） | 含 model/token/duration；未落 5.2 前用 `[LLM-IO]` ES 索引兜底 |
| 反馈过滤 | 5.1 `chat_message_feedback`（signal=down 过滤踩样本） | 未落 5.1 前先不支持该过滤 |
| traceId | orchestrator SSE 首事件 + `chat_message`（新增列 `trace_id`） | 见 6.3.2 |

新增 BFF 聚合 API（orchestrator 出数据）：
- `GET /api/observability/runs?mode&model&from&to&feedback&page` -> Run 列表（含 traceId/latency/token 摘要）
- `GET /api/observability/runs/{messageId}` -> 单 Run 详情（steps 瀑布 + LLM 调用明细 + traceId）

#### 4.4.4 图表库引入

引入 `echarts`（`sunshine-ui` 加依赖），对齐 `--sun-*` 主题（`echarts.registerTheme` 注入暗色变量）。瀑布图用 `series.type=custom` 或 Gantt-like bar；复用 `RetrievalWaterfall.vue` 的视觉范式。

#### 4.4.5 三台联动外链

Run 详情顶部提供：
- **SkyWalking trace**：`http://ecs4c16g:8084/trace/{traceId}` 外链（新窗口）
- **Kibana 日志**：`http://ecs4c16g:5601/app/discover#/?_a=(query:'trace_id:"{traceId}"')` 外链
- **Grafana 指标**：`http://ecs4c16g:3000/d/llm-dashboard?var-model={model}&from={ts}&to={ts}` 外链

实现 traceId 贯穿后，三台与前端观测页即可双向跳转。

### 4.5 三台联动（6.5）

以 `traceId` 为统一关联键，贯穿：

```
前端 Run Explorer (/observability)
    │ traceId
    ├──> SkyWalking UI  /trace/{traceId}        （业务 span + HTTP 链路）
    ├──> Kibana Discover ?q=trace_id:{traceId}  （业务日志 + LLM 结构化日志 + 审计）
    └──> Grafana /d/llm-dashboard?var-model=... （LLM 指标趋势）
```

落地条件：
1. logback `%tid` 注入（6.1.1）+ Filebeat 解析为 `trace_id` 字段（6.1.2）-> Kibana 可检索
2. SkyWalking agent 自动生成 traceId -> 业务 span 补全（6.3.1）-> SkyWalking UI 可查
3. SSE 首事件携带 traceId -> 前端可获取（6.3.2）
4. `chat_message.trace_id` 落库（orchestrator 在终态写消息时从 `ContextManager.getGlobalTraceId()` 取）-> Run Explorer 可展示与跳转

---

## 5. 库表与模块变更索引

| 变更 | 位置 | 备注 |
|------|------|------|
| `chat_message.trace_id` 列 | `docker/mysql/init/11-sunshine-orchestrator.sql` 追加 | VARCHAR(64) NULL，终态写消息时填充 |
| `logback-spring.xml` `%tid` + JSON appender | `common/sunshine-common/src/main/resources/logback-spring.xml` | + `logstash-logback-encoder` 依赖 |
| filebeat 服务 + 配置 | `docker/docker-compose.yml` + `docker/filebeat/filebeat.yml` | 采集各服务 logs/ 进 ES |
| prometheus 抓取全服务 | `docker/prometheus/prometheus.yml` | 补 7 个 job |
| Grafana dashboard ×3 | `docker/grafana/provisioning/dashboards/json/` | llm/orchestrator/tool |
| 告警规则挂载 | `docker/docker-compose.yml` prometheus volumes + `docs/grafana/llm-alerts.yml` | 修 phase3 遗留 + LLM 告警 |
| `LlmMetricsRecorder` | `llm-gateway/.../trace/` | Micrometer 埋点 |
| `OrchestratorMetrics` / `ToolMetrics` | orchestrator / tool-manager | Micrometer 埋点 |
| `@Trace` 业务 span | orchestrator / rag-service pom + 关键类 | `apm-toolkit-trace` 依赖 |
| SSE traceId 注入 | orchestrator `GenerationFlushScheduler` / SSE payload | 首事件携带 |
| `chat_message.trace_id` 写入 | orchestrator `ChatMessageEntity` 终态持久化 | `ContextManager.getGlobalTraceId()` |
| 前端 `/observability` | `sunshine-ui/src/views/ObservabilityView.vue` + router | echarts 依赖 |
| BFF 聚合 API | orchestrator `ObservabilityController` + BFF 透传 | runs 列表 + 详情 |
| `start.py` agent 告警 | `scripts/start.py` | 显式 WARN |

---

## 6. 检查门

- [ ] **6.1**：Filebeat 采集后 Kibana `sunshine-logs-*` 可按 `trace_id` 检索到同一请求跨服务日志；`[LLM-IO]` 结构化字段可聚合
- [ ] **6.2**：`/actuator/prometheus` 所有 Java 服务可访问；Grafana LLM 面板显示调用耗时/token/降级；LLM 告警规则在 Prometheus Rules 页可见
- [ ] **6.3**：SkyWalking UI 某会话 trace 可见 `orchestrator.execution` -> `agent.run` -> `react.loop` -> `tool.invoke` 业务 span 层级；SSE 首事件含 traceId
- [ ] **6.4**：`/observability` 页可按模式/模型过滤 Run 列表；选中 Run 显示瀑布图（含 durationMs/token）；可跳转 SkyWalking/Kibana
- [ ] **6.5**：traceId 在前端观测页 / Kibana / SkyWalking / Grafana 四处一致可查
- [ ] **回归**：`phase2_agent_demo.py --suite all` PASS；`verify_grafana_rag_live.py` PASS；spawn/沙箱/HITL/peer/expert Live 全绿

### Live 验收脚本

新增 `scripts/verify_observability_live.py`：
- L1：`/actuator/prometheus` 全服务有 `llm_`/`orch_`/`tool_` 指标
- L2：发一次 ReAct 对话 -> Run Explorer API 返回 steps 瀑布 + traceId
- L3：traceId 在 Kibana `sunshine-logs-*` 命中日志条数 > 0
- L4：SkyWalking UI trace 详情含业务 span（HTTP 探测 + span 名校验）
- L5：Grafana LLM 面板有数据点

---

## 7. 决策记录

- **D1（不引入 OpenTelemetry）**：已有 SkyWalking 9.7.0 全套（OAP+UI+agent），2-3 人团队维护不起两套 trace 体系；OTel 与 SkyWalking agent 混用会增加排障复杂度。SkyWalking 9.x 已支持 OTLP 接收，未来需对接 OTel 生态时可在 OAP 侧接收，不影响 agent 侧。
- **D2（Run Explorer 不新建落库）**：复用 `chat_message.steps` + 5.2 `llm_usage_record` + 5.1 `chat_message_feedback`，前端聚合展示。避免与 5.x 重复建表；若 5.x 未落地，先用 `[LLM-IO]` ES 索引兜底。
- **D3（LLM 指标与 5.2 共用采集点）**：`LlmMetricsRecorder` 与 5.2 `TokenUsageCollector` 都在 `ModelRouter` 调用点采集，避免重复埋点；指标打 Micrometer，用量落 MQ+MySQL，两条通道独立不耦合。
- **D4（filebeat 而非 logstash）**：filebeat 轻量、资源占用低，与 ES 7.17.23 同生态；logstash 重，2-3 人团队无独立运维。
- **D5（echarts 而非 d3/vis-network）**：echarts 开箱即用、瀑布/Gantt/折线/热力图都支持、与 Naive UI 暗色主题易对齐；d3 学习成本高、vis-network 偏图论场景。
- **D6（`@Trace` 注解而非手动 span API）**：`@Trace` 声明式，改动小、侵入低；复杂标签用 `Snapshot` API 补充；不混用 AspectJ。

---

## 8. 实施拆分（建议顺序）

1. **P0 第一波（LLM 可观测闭环）**：6.2.1 + 6.2.2 + 6.2.4 + 6.2.5 LLM 面板 -> LLM 指标可见
2. **P0 第二波（Run Explorer）**：6.3.2 SSE traceId + 6.4 前端页（先支持 react 模式 + ES 兜底）-> "跟随大模型思考"可视化
3. **P1（日志集中化）**：6.1 全量 -> Kibana 可查全链路日志
4. **P1（trace 业务 span）**：6.3.1 + 6.3.3 -> SkyWalking 业务语义可见
5. **P1（三台联动）**：6.5 -> 四台跳转闭环
6. **P2（编排/工具指标）**：6.2.3 + 6.2.5 orchestrator/tool 面板 -> 全服务指标覆盖

> 顺序依据：P0 先打通"跟随大模型"主线（LLM 指标 + Run Explorer），最快体现价值；日志/trace 增强作为 P1 补全；编排/工具指标 P2 收口。
