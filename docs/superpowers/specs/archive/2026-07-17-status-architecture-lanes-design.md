# 系统状态页：架构泳道与微服务补全

> **状态**：✅ 已实现  
> **日期**：2026-07-17  
> **范围**：`sunshine-ui` Status 页 · Gateway health 路由 · `scripts/start.py`  
> **触发**：状态页仅卡片网格、缺 Sandbox/OA，无法表达上下游

---

## 1. 目标与边界

### 1.1 目标

1. **补全可探测微服务**：增加 Sandbox Service（:8226）与 OA Service（:8700）。
2. **OA 纳入启动链**：`scripts/start.py` 增加 `oa-service`。
3. **Gateway 健康路由**：新增 `/health/sandbox`、`/health/oa`，与现有 `/health/*` 模式一致。
4. **UI 改为分层架构泳道**：用上下游分层表达关系；节点紧凑展示；去掉原 3 列卡片网格；无详情侧栏。
5. **中间件条带**：底部展示 Nacos / Redis / MySQL / Milvus / ES / RocketMQ / MinIO，**仅展示、不探测**。

### 1.2 非目标

- 不为中间件做 HTTP 健康探测或跨机探活
- 不引入 Mermaid / SVG 手动画线拓扑库
- 不做节点详情侧栏、不做 hover 浮层（已否决）
- 不改各服务业务 API；仅 Gateway 路由 + 状态页展示 + start 列表
- 不在本变更中重写 README 架构 ASCII（可选后续对齐）

### 1.3 已确认选型

| 项 | 选择 |
|----|------|
| 布局 | Vue CSS 分层泳道（方案 A） |
| 节点信息 | 紧凑一行：名称 + 状态点 + 延迟/端口 |
| 详情栏 | 不要 |
| 中间件 | 底部条带，展示不探测 |
| Desensitize | L3 平台能力（与 RAG/LLM 同级） |
| OA / Finance / MCP | **L4 领域 / 接入**：OA、Finance 可探测；MCP 为已接入能力示意（经 tool-manager Catalog，无独立微服务探测） |

---

## 2. 分层与节点

```
L0 客户端（不探测）
  Browser / sunshine-ui :5173

L1 入口
  Gateway :8000 · BFF :8001 · Auth Center :8100

L2 编排核心
  Orchestrator :8200（视觉强调：边框高亮）

L3 平台能力
  Tool Manager :8210 · Skill Manager :8225 · Sandbox :8226
  Workflow Manager :8230 · Expert Manager :8235
  LLM Gateway :8300 · RAG Service :8400
  Prompt Manager :8500 · Desensitize :8600

L4 领域 / 接入
  OA :8700 · Finance :8710 · MCP（已接入能力示意，无独立端口探测）

基础设施（展示、不探测）
  Nacos · Redis · MySQL · Milvus · ES · RocketMQ · MinIO
```

**在线统计**：分母 = 可探测业务微服务数（含 Gateway），即 **15**（原 13 + Sandbox + OA）。Browser、MCP 示意节点与中间件不计。

**上下游表达**：层间 ↓ 箭头即可；不画逐条调用边。

---

## 3. UI 行为

### 3.1 页面结构

1. 页头：标题「系统状态」+ 探测说明 +「刷新」
2. 统计卡：`online/total` 微服务在线（逻辑不变）
3. **架构泳道**（替换原 `NCard` + `NGrid` 卡片区）
4. 底部中间件条带

### 3.2 节点展示

- 文案：`{name}` / `● {latency}ms · :{port}`（在线且有延迟时）；检测中/离线用对应状态文案与颜色
- 样式：`--sun-black` 底 + `1px var(--sun-border)`；Orchestrator 可用 `--sun` 强调色描边；禁止灰底卡片堆叠（对齐 Codex UI）
- 探测：复用现有 `resolveHealthProbeUrl` + `status=UP` + `expectedService` 校验

### 3.3 数据模型扩展

在现有 `ServiceStatus` 上增加泳道元数据（示例字段名，实现可微调）：

- `lane`: `'entry' | 'orchestrator' | 'platform' | 'domain'`

中间件用静态常量数组，无 status 探测。

---

## 4. Gateway / start.py

### 4.1 `docs/nacos/sunshine-gateway.yaml`

在现有 health 路由旁追加（与 finance 同模式）：

| id | Path | lb | Rewrite |
|----|------|-----|---------|
| health-sandbox | `/health/sandbox` | `lb://sunshine-sandbox-service` | → `/health` |
| health-oa | `/health/oa` | `lb://sunshine-oa` | → `/health` |

改后执行 `python scripts/sync_nacos.py`，并重启 Gateway（或全量 `start.py`）。

### 4.2 `scripts/start.py`

在 `SERVICES` 中增加（建议紧挨 finance）：

```python
("oa", "oa-service", "sunshine-oa", 8700),
```

`oa-service` / `sandbox-service` 已依赖 `sunshine-common` 的统一 `GET /health`（`status=UP` + `service` = `spring.application.name`）。

### 4.3 StatusView `SERVICE_DEFS` 新增

| name | port | gatewayPath | expectedService |
|------|------|-------------|-----------------|
| Sandbox Service | 8226 | `/health/sandbox` | `sunshine-sandbox-service` |
| OA | 8700 | `/health/oa` | `sunshine-oa` |

描述建议：Sandbox →「Skills Docker 沙箱」；OA →「OA 模拟 / Tool App」。

---

## 5. 验收

1. 打开 `/status`：见分层泳道，无旧卡片网格；Tool 下可见 OA、Finance；底有中间件条带。
2. 全链路已启动时：统计为 **15/15**（或实际在线数）；Sandbox、OA 节点在线且有延迟。
3. `curl`（或经 Vite 代理）`:8000/health/sandbox`、`:8000/health/oa` 返回 `status=UP` 且 `service` 匹配。
4. `python scripts/start.py` 日志出现启动 `sunshine-oa`（在服务列表中）。
5. 样式：黑底 + 边框分区，无 `--sun-surface` 灰底。

---

## 6. 实现落点（文件）

| 文件 | 变更 |
|------|------|
| `sunshine-ui/src/views/StatusView.vue` | 泳道布局 + 服务补全 + 去掉网格 |
| `docs/nacos/sunshine-gateway.yaml` | health-sandbox / health-oa |
| `scripts/start.py` | 加入 oa |
| （可选）`README.md` 架构图 | 与分层对齐，非必须 |

---

## 7. 明确不做

- 中间件探活、详情侧栏、hover 浮层、逐边 SVG 连线
- 为未启动的 OA 做「可选/内网」特殊状态（与其它服务同为 online/offline）
