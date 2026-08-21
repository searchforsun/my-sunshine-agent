# Workflow Studio 实施计划（DB 单轨）

> **日期**：2026-07-11  
> **状态**：✅ **当前形态收口**（2026-07-15）；v1 非目标不做  
> **详设 SSOT**：[2026-06-25-workflow-studio-design.md](../specs/archive/2026-06-25-workflow-studio-design.md)  
> **决策**：Workflow **完全 DB 单轨**；废弃 Nacos `sunshine-workflows.yaml` 与一切兼容逻辑；标杆由 **MySQL init 种子** 初始化（现 **7** 条，含 `knowledge-dual` / `knowledge-branch` / `knowledge-loop`）

---

## 目标

1. `workflow-manager` 为 workflow 定义唯一 SSOT（Catalog + PlanJson）
2. orchestrator 经 HTTP 读 DB；移除 `WorkflowProperties` / Nacos workflow 加载
3. `/workflows` Dify 式可视化编辑（MVP：线性 5 类型 + 高级图）
4. Chat `#workflowId` L0 绑定
5. DB workflow 节点重试/降级与 plan-workflow 对齐

---

## Phase 1 — DB 单轨切换（后端）

### Task 1.1 MySQL init 种子

- Modify: `docker/mysql/init/13-sunshine-workflow-manager.sql`
- 插入标杆 `workflow_definition` + `workflow_version`（`status=published`，`enabled=1`，`source=seed`）；现 **7** 条
- 直接在 `docker/mysql/init/13-sunshine-workflow-manager.sql` 维护；工具 ID 为 `sdk__sunshine-finance__*`
- 节点 id=`{type}-{8位hex}`；rag 须含 `params.query`（`{{start.userQuery}}`）
- **维护**：改 init SQL + 已部署 DB UPDATE，见 [docs/workflow/README.md](../../workflow/README.md)

### Task 1.2 workflow-manager API

- Create: Controller / Service / DTO
- Endpoints: catalog、list、CRUD draft、publish、import、published
- 发布：`PlanValidator`（可复用 orchestrator 校验逻辑或抽 common）
- Redis: `workflow-catalog-changed` on publish

### Task 1.3 orchestrator 切 DB

- Create: `WorkflowManagerClient`（Feign/RestTemplate）
- Create: `WorkflowCatalogService`（替换 `WorkflowCatalog` + `WorkflowProperties`）
- Modify: `WorkflowDefinitionLoader` → 读 client published plan
- Modify: `WorkflowStaticPlanRunner`、`WorkflowBindingParser`、`IntentLabelService`、`WorkflowNodeLabelService`
- Delete/deprecate: `WorkflowProperties`、Nacos `sunshine-workflows.yaml` 引用
- Modify: `orchestrator/src/main/resources/application.yml` — 移除 `sunshine-workflows.yaml`
- Modify: `scripts/sync_nacos.py` — 移除 `sunshine-workflows.yaml`

### Task 1.4 节点重试对齐

- Modify: `NodeRetryPolicyResolver` — DB workflow 走 `execution_mode_policy` + 节点 `retry.*`

### Task 1.5 BFF 透传

- Modify: bff routes → workflow-manager

**验收**：

```bash
# 新环境无 Nacos workflow 配置
python3 scripts/verify_workflow_studio_live.py --suite catalog
# golden-set §I
mvn test -pl orchestrator -Dtest=RoutingGoldenSetTest
```

---

## Phase 2 — Studio UI（4.13.5）

### Task 2.1 `/workflows` 页面

- 对称 `SkillsView`：左列表 + 右编辑
- API client: `sunshine-ui/src/api/workflows.ts`

### Task 2.2 DAG 编辑器 MVP

- 依赖: `@vue-flow/core`
- 线性节点：start / rag / tool / agent / answer（join 见 `knowledge-dual` 种子）
- 节点面板：分组配置（输入/检索/输出/执行策略）；Catalog 下拉（tools/skills/kb）；tool schema 入参；tool `output.mode/extract`
- 预览：复用 `PlanDagGraph` 样式

### Task 2.3 Chat `#` 补全

- Modify: `ChatView.vue` — `HASH_PATTERN` + workflow suggest
- placeholder 合并 `@` / `#` 提示

**验收**：Studio 新建 workflow → 发布 → `#my-flow 测试` 命中

---

## Phase 3 — 高级图（4.13.7 ✅ 收口）

| 顺序 | 引擎 + Studio | 状态 |
|------|---------------|:----:|
| 1 | `parallel-gateway` / `join`（种子 `knowledge-dual`） | ✅ |
| 2 | `exclusive-gateway` 边条件（种子 `knowledge-branch`） | ✅ |
| 3 | `loop` do-while + `parentId` body（种子 `knowledge-loop`） | ✅ |

**不做**（详设 §11）：for-each、预检测 while、框内嵌套网关/loop、多出边汇合、画布边条件标签、复合 AND/OR / 独立 if-else 节点。

---

## 删除清单（Phase 1 完成时）

| 项 | 动作 |
|----|------|
| `docs/nacos/sunshine-workflows.yaml` | **已删除**（2026-07-13） |
| `WorkflowProperties.java` | 删除 |
| `CompositeWorkflowDefinitionLoader` | 不实现 |
| `CompositeWorkflowCatalog` | 不实现 |
| orchestrator 测试 mock `WorkflowProperties` | 改为 mock `WorkflowManagerClient` |

---

## 检查门汇总

- [x] init SQL 后标杆 `#` 可命中（含 `#knowledge-dual` / `#knowledge-branch` / `#knowledge-loop`）— Live 脚本
- [x] 种子 rag 节点含 `params.query`；`WorkflowPlanValidatorTest` PASS
- [x] 标杆 workflow 种子 SSOT 为 `13-sunshine-workflow-manager.sql`（无独立 JSON）
- [x] 无 Nacos workflow 时 orchestrator 正常启动
- [x] Studio CRUD + 发布 + 缓存失效 — `--suite studio` + orchestrator catalog 刷新
- [x] `routing-golden-set` §I PASS — `RoutingGoldenSetTest` + Live hash/parallel/exclusive/loop
- [x] 节点重试角标 `×N`（DB workflow）— `PlanDagGraph` + Studio 预览
- [x] **4.13 当前形态收口**（2026-07-15）；v1 非目标不做