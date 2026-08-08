# 服务合并方案设计

> 日期：2026-08-03 · 修订：2026-08-06 · 状态：**✅ 已实现** · 类型：架构评估

## 1. 背景与问题

当前平台后端共 **16 个 Maven 子模块**，其中可独立部署服务 13 个。服务拆分过细导致：

- **运维成本高**：每服务独立 JVM、独立端口、独立 Nacos 注册、独立 start.py 条目、独立 MySQL 库。
- **代码规模两极分化**：`agent-manager`（759 行）、`desensitize`（200 行）作为独立微服务偏重，运维成本高于业务复杂度。
- **管理类服务零横向依赖**：`skill/agent/prompt-manager` 互不调用，只被 `orchestrator` 通过 `*CatalogClient` 调用，呈"星型"依赖，天然适合内聚。（`tool-service` 除外：其 `/api/tools/invoke` 是运行态热路径，见 §4）
- **业务模拟服务已共享库**：`oa/finance/hr` 已共用同一 `sunshine_biz` 库，技术栈 100% 一致，零互调，独立成服务纯属冗余。

### 1.1 当前服务全景

| 类别 | 服务 | 端口 | 代码规模 | 库 |
|------|------|:----:|:------:|:--:|
| 接入层 | gateway / bff / auth-center | 8000/8001/8100 | - | - |
| 编排 | orchestrator | 8200 | 42,550 行 | sunshine_chat |
| AI 能力/运行态 | **tool-service**（原 tool-manager） | 8210 | 3,006 行 | sunshine_tool |
| 管理类 | skill-manager | 8225 | 2,485 行 | sunshine_skill |
| 管理类 | agent-manager | 8235 | 759 行 | sunshine_agent |
| 管理类 | prompt-manager | 8500 | 1,071 行 | sunshine_prompt |
| 管理类 | desensitize | 8600 | 200 行 | （无） |
| AI 能力/运行态 | llm-gateway / rag-service / sandbox-service / workflow-manager | 8300/8400/8226/8230 | - | - |
| 业务模拟 | oa-service | 8700 | 481 行 | sunshine_biz |
| 业务模拟 | finance-service | 8710 | 994 行 | sunshine_biz |
| 业务模拟 | hr-biz-service | 8720 | 1,178 行 | sunshine_biz |

> 注：CLAUDE.md 中 `expert-manager (8235)` 为过时引用，实际已是 `agent-manager`（同端口）。
> 注：`tool-manager` 原归「管理类」，经评审修订为**运行态工具流量入口**（`/api/tools/invoke` 为 agent 主执行链热路径），独立保留并更名 `tool-service`，不参与合并项 A（见 §4）。

## 2. 目标与约束

- **首要目标**：降低运维成本（进程/端口/配置/库数量）。
- **次要目标**：保留管理端与编排端的独立扩缩容能力。
- **范围**：
  - 合并项 A：skill/agent/prompt-manager + desensitize -> `resource-manager`（tool-service 独立保留，见 §4）
  - 合并项 B：oa/finance/hr -> `biz-simulator`
- **不动的服务**：orchestrator、gateway/bff/auth-center、llm-gateway、rag-service、sandbox-service、workflow-manager、**tool-service**。
- **数据库策略**：直接重建（不做数据迁移），合并项 A 的 3 库合并为 1 库（`sunshine_tool` 随 tool-service 保留），业务模拟库无变化（已共享）。
- **不做兼容**：废弃旧 Nacos 服务名、旧 Catalog ID，直接改新值，同步更新种子 SQL。
- **交付物**：本评估方案文档（不进入实施）。

## 3. 方案选型

评估了三种管理类合并方案，选定 **方案 A（聚合管理服务）**，并附加独立的 **业务模拟聚合**（biz-simulator）。为避免命名混淆，下文用"合并项 A / 合并项 B"指代两个落地动作：

| 管理类方案 | 思路 | 扩缩容 | 故障隔离 | 推荐 |
|------------|------|--------|---------|:----:|
| A. 聚合管理服务 | 管理类合并为 `resource-manager`，与 orchestrator 分离 | 管理端独立于编排 | 好 | **是** |
| B. 全量内联 orchestrator | 管理服务全部进 orchestrator | 丧失独立扩缩容 | 差（管理端 OOM 拖垮编排） | 否 |
| C. 混合分组 | desensitize 进 orchestrator + 其余聚合 | desensitize 随编排 | 中 | 否 |

方案 A 的关键优势：管理端合并为 1 进程大幅降运维成本，同时与 orchestrator 分离完整保留二者独立扩缩容；`*CatalogClient` 调用方式不变，只需把下游地址指向同一 `resource-manager`，orchestrator/BFF 侧几乎零改动。

> 评审修正：`tool-manager` 原按「管理类」并入合并项 A，经评审认定其 `/api/tools/invoke` 是 agent 主执行链上的运行态热路径，与 llm-gateway/rag-service/sandbox-service 同属「独立扩缩容需求」类，**独立保留并更名 `tool-service`**（详见 §4）。合并项 A 缩减为 skill/agent/prompt/desensitize。

业务模拟服务合并（合并项 B `biz-simulator`）作为独立合并项，与合并项 A 并行。二者负载特征、调用链路不同（管理类被 BFF/orchestrator 拉 Catalog；业务模拟被 tool-service 的 SdkInvokeExecutor 经 Nacos 发现调用），不合在一起以免模糊边界。

## 4. 评估修订：tool-service 独立保留 + A2A 直连

> 评审结论：`tool-manager` 归类错误，须从「管理类」移出、独立保留并更名 `tool-service`；`agent-manager` 归类正确（纯管理），A2A 外部智能体调用维持 **orchestrator 直连**，不经 agent-manager。

### 4.1 tool-service：运行态工具流量入口（独立保留）

**现状事实（代码确认）**：

- `/api/tools/invoke`（`ToolInvokeController` → `InvokeRouter`）是 agent 主执行链上的**同步热路径**：每次 ReAct 工具调用、Workflow tool 节点、spawn_subagent worker、Planner-Executor worker 都经 orchestrator `ToolManagerClient.invokeMono/invokeJsonMono` 同步打到本服务。
- `InvokeRouter` 按 source 分派：SDK → `SdkInvokeExecutor`（Nacos 服务发现 + 轮询负载均衡 + 超时 + `x-user-id`/`x-tenant-id` 透传）；MCP → `McpInvokeExecutor`（SSE/stdio JSON-RPC）。
- 后台运行态任务：`SdkDiscoveryPuller`（周期拉取 SDK 应用）、`McpSyncService`、`ToolCatalogChangePublisher`（Catalog 变更事件）。
- 编排运行辅助：`summarize-output`、`extract-bindings`。

**结论**：tool-manager 不是「只被 orchestrator 拉 Catalog」的纯管理服务。其 invoke 链路与 llm-gateway/rag-service/sandbox-service 同属**负载敏感的流量面**，若并入 resource-manager 将：

1. 把最高负载、最敏感的执行链路与 4 个近零负载的 CRUD 服务捆进同一进程，丧失工具执行链路的独立扩缩容；
2. 故障爆炸半径增大——任一管理子模块 OOM 直接拖垮所有工具调用，与方案 B 被否决的理由同构；
3. 违背 §11「各有独立扩缩容需求」的排除准则，tool-service 与该类别同等适用。

**落地**：

1. **更名 `tool-service`**：与 `rag-service` 命名一致，反映其「工具执行服务」定位。若需强调「工具流量入口」语义可选 `tool-gateway`（与 `llm-gateway` 平行），但其兼有管理面（catalog / tool-set / MCP 管理），`tool-service` 更贴切。
2. **独立保留**：端口 8210、`sunshine_tool` 库不变；Nacos 服务名更名 `sunshine-tool-service`（仅服务名与配置 Data ID 更名）。orchestrator `ToolManagerClient` / BFF `ToolManagerAdminClient` 代码与 base-url 配置均不变，**零改动**。
3. 合并项 A 缩减为 skill/agent/prompt/desensitize -> `resource-manager`。

### 4.2 A2A 外部接入：agent-manager 管注册，orchestrator 直连执行（维持现状）

**现状事实（代码确认）**：

- `agent-manager` 为纯 CRUD（759 行）：`AgentCatalogController`（catalog index/detail）+ `AgentAdminController`（CRUD），**无任何 A2A 调用端点**，仅持久化 `source=EXTERNAL` 智能体的 `agentCardUrl` / `endpointOverride` / `authConfigJson`。
- 执行链路：`SpawnSubagentTool` → `AgentExecutorRouter.dispatch()`，`source==EXTERNAL` 时由 **orchestrator 内 `ExternalAgentClient` 直连远端**（Agent Card URL → `/tasks/sendSubscribe`，SSE 流式映射 `StreamToken`），不进 agent-manager。
- orchestrator 经 `AgentCatalogService` → `AgentCatalogClient` 拉取 agent-manager catalog，取得 endpoint/auth 后自行调用。

**结论**：A2A **直连 orchestrator、不经 agent-manager**，现状正确，维持不变：

1. A2A 调用是**编排运行态的一部分**：需流式回灌主时间线 `subSteps`、支持单独取消（`SpawnRunRegistry`）、超时与降级；agent-manager 不具备也不应具备这套运行时能力。
2. 经 agent-manager 只是多一跳透传，无任何增值，却要把 SSE 流式/取消/超时语义复制到管理服务。
3. agent-manager 并入 resource-manager 后，更不应把 A2A 运行流量引入管理聚合进程（与 §4.1 同源问题）。
4. 职责边界：agent-manager = A2A **注册/元数据面**（Agent Card 预填、auth 配置、白名单），orchestrator = **执行面**。故 agent-manager 归「管理类」参与合并项 A 是**正确**的。

## 5. 合并项 A：`resource-manager`（skill/agent/prompt/desensitize）

### 5.1 模块结构

```
resource-manager/                          # 新模块，端口 8240
├── pom.xml                                # 合并 4 服务依赖
├── src/main/java/com/sunshine/resource/
│   ├── ResourceManagerApplication.java    # @SpringBootApplication(scanBasePackages="com.sunshine")
│   ├── skill/                             # 原 skill-manager 全量代码（com.sunshine.skill）
│   ├── agent/                             # 原 agent-manager 全量代码（com.sunshine.agent）
│   ├── prompt/                            # 原 prompt-manager 全量代码（com.sunshine.prompt）
│   └── desensitize/                       # 原 desensitize 全量代码（com.sunshine.desensitize）
└── src/main/resources/application.yml     # 唯一配置入口
```

> 端口说明：tool-service 保留 8210，resource-manager 新分配 **8240**（避免与 sandbox-service 8226 / workflow-manager 8230 冲突）。

### 5.2 关键设计决策

1. **包名不变**：各子模块保留原包名 `com.sunshine.{skill|agent|prompt|desensitize}`，通过 `scanBasePackages="com.sunshine"` 统一扫描。原代码几乎零改动，仅物理位置迁移。

2. **端点路径不变**：`/api/skills/**`、`/api/agents/**`、`/api/prompts/**`、`/api/desensitize/**` 全部保留。BFF 和 orchestrator 的 `*Client` 只需把 `*.base-url` 统一指向 `http://localhost:8240`，代码零改动。（`/api/tools/**` 随 tool-service 保留在 8210）

3. **数据库合并**：3 个独立库（sunshine_skill/agent/prompt）合并为 `sunshine_resource` 单库。各表保持原表名（无冲突，表名已有 `skill_`/`agent_`/`prompt_` 前缀）。desensitize 无表。`sunshine_tool` 库随 tool-service 保留不动。**直接重建**：合并 SQL 文件为 `docker/mysql/init/sunshine-resource.sql`，删除原 3 个分库 SQL。

4. **依赖合并**：
   - 统一 `spring-boot-starter-web` + `webflux`（WebClient 用，与原各服务一致）。
   - `sunshine-routing`（prompt 用）、ahocorasick（desensitize 用）、MinIO（skill 用）合并到统一 pom。（tool-service 的 sunshine-tool-sdk / Redis 依赖随其独立保留）

5. **Nacos 注册**：合并后注册为单个服务 `sunshine-resource-manager`，废弃原 4 个服务名（sunshine-skill-manager / sunshine-agent-manager / sunshine-prompt-manager / sunshine-desensitize）。

### 5.3 调用方影响

**orchestrator**（4 个 Client）：`SkillCatalogClient`、`AgentCatalogClient`、`PromptCatalogClient`、`DesensitizeClient` 均通过 `@Value("${*.base-url}")` 配置 baseUrl。合并后只需把这 4 个 base-url 统一指向 `http://localhost:8240`，**代码零改动**。（`ToolManagerClient` 不变，仍指向 tool-service 8210）

**BFF**（3 个 Client）：`SkillManagerClient`、`AgentManagerClient`、`PromptManagerClient` 同理，统一指向 `http://localhost:8240`，**代码零改动**。（`ToolManagerAdminClient` 不变，仍指向 tool-service 8210）

## 6. 合并项 B：`biz-simulator`

### 6.1 模块结构

```
biz-simulator/                             # 新模块，端口 8700
├── pom.xml                                # 与原三服务完全相同的依赖
├── src/main/java/com/sunshine/
│   ├── BizSimulatorApplication.java       # @SpringBootApplication(scanBasePackages="com.sunshine")
│   ├── oa/                                # 原 oa-service 全量代码（com.sunshine.oa 不变）
│   ├── finance/                           # 原 finance-service 全量代码（com.sunshine.finance）
│   └── hr/                                # 原 hr-biz-service 全量代码（com.sunshine.hr）
└── src/main/resources/application.yml     # 唯一配置入口
```

### 6.2 关键设计决策（统一 appId，不做兼容）

1. **统一 appId**：合并后注册**单个** Nacos 服务名 `sunshine-biz-simulator`，单一 appId。废弃原 `sunshine-oa`/`sunshine-finance`/`sunshine-hr` 三个服务名。

2. **Catalog ID 统一改新值**：废弃旧 ID `sdk__sunshine-{oa|finance|hr}__*`，统一改为 `sdk__sunshine-biz__*`（如 `sdk__sunshine-biz__list_oa_tasks`）。三个 `*SunshineTools` 类的 appId 配置统一改为 `sunshine-biz`。

3. **种子 SQL 同步更新**：旧 Catalog ID 在 4 个种子 SQL 中被引用，需同步改为新 ID：

   | SQL 文件 | 引用数 | 引用方 |
   |---------|:------:|--------|
   | `13-sunshine-workflow-manager.sql` | 10 | workflow plan_json 的 tool 节点 |
   | `15-sunshine-agent-manager.sql` | 3 | 子智能体工具白名单 |
   | `12-sunshine-skill-manager.sql` | 2 | skill 工具配置 |
   | `17-sunshine-prompt-manager.sql` | 1 | plan 示例 |

4. **数据库**：三服务已共享 `sunshine_biz` 库，**无变化**。`18-sunshine-biz.sql` 保持不变。

5. **端点路径**：`/api/oa/**`、`/api/finance/**`、`/api/hr/**`、`/api/biz/{oa|finance|hr}/**`、`/sunshine/tools/invoke/**` 全部保留，无冲突。

6. **tool-service DB**：`sdk_application` 表需更新，三个 SDK 应用记录合并为一条（`nacos_service = sunshine-biz-simulator`，`app_id = sunshine-biz`）；`tool_definition` 表的工具 ID 同步改新值。

### 6.3 调用链路影响

调用链路不变：`orchestrator -> tool-service SdkInvokeExecutor -> Nacos 发现 sunshine-biz-simulator -> POST /sunshine/tools/invoke/{tool}`。仅 Nacos 服务名和工具 ID 变更，链路结构不变。

## 7. 改造影响面全景

| 改造对象 | 合并项 A（管理类） | 合并项 B（业务模拟） |
|---------|-------------------|---------------------|
| 新模块 | `resource-manager`（8240） | `biz-simulator` |
| 保留模块 | **tool-service**（原 tool-manager，8210 不变，仅更名） | - |
| 删除模块 | skill/agent/prompt-manager/desensitize | oa/finance/hr-service |
| 根 pom.xml | 4 模块 -> 1 模块 | 3 模块 -> 1 模块 |
| orchestrator | 4 个 `*.base-url` 统一指向 8240；`ToolManagerClient` 不变指向 8210 | 无影响（经 tool-service 间接调用） |
| bff | 3 个 `*.base-url` 统一指向 8240；`ToolManagerAdminClient` 不变指向 8210 | 无影响 |
| gateway | 健康检查路由 4 条 -> 1 条（health-tool-service 保留） | 健康检查路由 3 条 -> 1 条 |
| tool-service DB | 无变化（`sunshine_tool` 保留） | `sdk_application` + `tool_definition` 改新 appId/工具 ID |
| agent-manager DB | 并入 resource-manager | 子智能体白名单 Catalog ID 改新（3 处） |
| workflow-manager DB | 无影响 | plan_json 中 Catalog ID 改新（10 处） |
| skill-manager DB | 并入 resource-manager | skill 工具配置改新（2 处） |
| prompt-manager DB | 并入 resource-manager | plan 示例改新（1 处） |
| MySQL | 3 库 -> 1 库（sunshine_resource）；重建 | 无变化（已共享 sunshine_biz） |
| Nacos 配置 | 4 份 -> 1 份（tool-service 保留） | 3 份 -> 1 份 |
| start.py | 4 条目 -> 1 条目（tool-service 条目保留） | 3 条目 -> 1 条目 |
| 前端 | 无影响（经 BFF 透传，路径不变） | 无影响 |
| CLAUDE.md | 服务端口表更新 | 服务端口表更新 |

## 8. 迁移步骤（高层）

1. **创建/更名模块**：创建 `resource-manager` 和 `biz-simulator`，迁移代码（保持包名）；`tool-manager` 更名 `tool-service`（代码不变，仅 Nacos 服务名与配置 Data ID 更新）。
2. **数据库重建**：合并管理类 3 库 SQL 为 `sunshine-resource.sql`（直接重建）；`sunshine_tool` 保留；业务库无变化。
3. **Catalog ID 改新**：更新 4 个种子 SQL 中的旧 ID -> 新 ID（共 16 处）；更新 `sdk_application`/`tool_definition` 种子。
4. **配置**：创建 resource-manager/biz-simulator 2 份新 Nacos 配置，tool-service 沿用原 tool-manager 配置（更名）；orchestrator 的 4 个管理类 `*.base-url` 统一指向 `8240`（`ToolManagerClient` 不变），BFF 的 3 个同理。
5. **部署脚本**：更新 start.py（8 条目 -> 3 条目：tool-service + resource-manager + biz-simulator）、gateway 健康检查路由。
6. **验收**：运行现有 verify 脚本确认功能无回归：
   - `verify_tool_integration_live.py`（工具调用，tool-service 独立验证）
   - `verify_skills_ui_live.py`（Skill 管理）
   - `verify_enterprise_workflow_live.py`（workflow 含业务工具）
   - `verify_spawn_subagent_live.py`（子智能体白名单）
   - `verify_external_agent_live.py`（A2A 直连 orchestrator 回归）
7. **清理**：删除旧模块，更新 CLAUDE.md 服务端口表与 implementation-plan.md。

## 9. 收益汇总

| 维度 | 合并前 | 合并后 | 变化 |
|------|--------|--------|------|
| 管理+工具+业务进程数 | 8 | 3 | -5 |
| 端口数（管理+工具+业务） | 8 | 3 | -5 |
| MySQL 库（管理类） | 4 | 2 | -2 |
| Nacos 配置份数 | 8 | 3 | -5 |
| start.py 条目 | 8 | 3 | -5 |
| orchestrator/BFF 代码改动 | - | 零 | 仅改配置（ToolManager* 不变） |
| 前端改动 | - | 零 | - |

## 10. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 单进程故障爆炸半径增大 | 管理端任一子模块 OOM 影响整个 resource-manager | 各子模块独立异常隔离；监控内存；管理端负载低，风险可控；**工具执行链路（tool-service）独立，不受影响** |
| Catalog ID 变更遗漏 | 工具调用失败 | 种子 SQL 16 处引用已全部盘点（§6.2）；验收脚本覆盖 |
| desensitize 内联后编排链路依赖 | desensitize 故障影响编排链路 | desensitize 仅 200 行纯计算，无外部依赖，风险极低；保留 `desensitize.enabled` 开关可降级 |
| 合并后 JVM 内存增大 | 单进程内存占用上升 | 管理类总量约 4.5K 行（tool-service 独立不并入），内存增量可控；按需调 JVM 堆 |

## 11. 不在本次范围

- orchestrator 内部重构（42K 行，需独立评估）
- llm-gateway / rag-service / sandbox-service / workflow-manager / **tool-service** 合并（各有独立扩缩容需求或特殊栈）
- 接入层（gateway/bff/auth-center）调整
