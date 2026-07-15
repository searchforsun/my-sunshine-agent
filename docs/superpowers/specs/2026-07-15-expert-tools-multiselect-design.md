# 专家工具多选设计

> **状态**：✅ 已落地（2026-07-15）  
> **日期**：2026-07-15  
> **前置**：[2026-07-07-expert-consultation-design.md](./2026-07-07-expert-consultation-design.md)（MVP 工具只读「全部工具」）  
> **关联**：`/experts` · `expert_definition.tools_json` · `DynamicToolkitFactory.buildForSubAgent`

## 1. 目标

在 `/experts` 将工具从只读「全部工具」改为 **Catalog 多选**；落库 `tools_json`；专家 Hub 发言时按白名单装 Toolkit。Skill 只影响 prompt overlay，**不参与**工具并集。

## 2. 已确认决策

| 项 | 选择 |
|----|------|
| 范围 | 管理页可编辑 + API 持久化 + 运行时白名单生效 |
| 空选 | `[]` = 无业务工具（RAG 等系统注入仍保留） |
| `*` | **不保留**为可选快捷项；只存具体 Catalog ID |
| Skill × 工具 | 仅用专家 `tools_json`（Skill 不管工具） |
| 新建默认 | `[]`（不再默认 `["*"]`） |
| 历史 `["*"]` | 编辑页加载时展开为当前启用 Catalog 全量；保存后写出具体 ID；运行时若仍见 `*` 则按启用池全量解析（过渡） |

## 3. 数据契约

### 3.1 存库

字段：`expert_definition.tools_json`（已有，`VARCHAR(512)`）

| 值 | 含义 |
|----|------|
| `[]` | 无业务工具 |
| `["sdk__…","mcp__…"]` | 白名单；运行时与租户启用池求交 |
| `["*"]` | **仅过渡**；运行时当启用池全量；**保存后不再写出** |

### 3.2 API

- `POST /api/experts`、`PUT /api/experts/{id}`：请求体增加 `toolIds: string[]`（可空）
- 服务端序列化为 `tools_json`；响应继续返回 `toolsJson: string`
- 非法 / 未知 Catalog ID：保存时仍写入（与 Workflow agent tools 一致，启用池求交在运行时）；可不做严格校验，避免 Catalog 暂不可达时无法保存

### 3.3 种子 / 默认

- `ExpertAdminService.create`：`toolsJson` 改为 `"[]"`（不再 `"[\"*\"]"`）
- `15-sunshine-expert-manager.sql`：列 DEFAULT `'[]'`；四种子按职责写明 `tools_json`（财务 3 工具、合规 2 工具、制度/法务 `[]`）
- 已有行若为 `["*"]` 或空：不强制 SQL 迁移；依赖 UI 保存或按下方 UPDATE 对齐

## 4. UI（`/experts`）

- 「能力配置」中「工具」：用与「关联 Skill」同构的 `NSelect multiple filterable` 替换只读 `NInput`
- 选项：`listToolCatalog` 过滤 `enabled`；label = `displayName (id)`
- 加载：`JSON.parse(toolsJson)`；若含唯一元素 `"*"` → 展开为当前全部启用 ID（展开本身不标 dirty）
- 允许空选；placeholder：`可选 0~N 个工具`
- dirty 检测：将 `toolIds` 纳入与 `skillIds` 同级的比较
- 样式：`sun-field` + 现有 `expert-select-menu`，不新增布局分区

## 5. 运行时

```
ExpertHubEngine.createAgent(expert)
  → parse toolsJson → toolIds
       ["*"] → 租户启用池全量（过渡）
       []    → 空白名单
       [...] → 具体 ID
  → AgentRunRequest.toolWhitelist = toolIds
  → ExpertPeerAgentFactory
       → DynamicToolkitFactory.buildForSubAgent(whitelist, tenantId)
            始终 RAG；不含 manage_tasks
            业务工具 = whitelist ∩ 启用池
```

**禁止**：专家路径对空列表回退到 `build(tenantId)`（全开）。当前 `ExpertPeerAgentFactory` 在 whitelist 为空时走全开，**必须改掉**。

## 6. 非目标

- Chat peer-collab 底栏另开工具多选入口
- Skill `tools_json` 与专家工具求交 / 并集
- 管理页「全部工具」快捷项 / 独立「不限工具」开关
- Flyway 或强制清库迁移所有 `["*"]`

## 7. 验收

1. PUT 带 `toolIds`，GET `toolsJson` 一致  
2. UI 多选保存后刷新仍保留；空选 → `[]`  
3. 旧 `["*"]` 打开后展示为全量启用 ID；保存后 DB 无 `*`  
4. 白名单专家只能调名单内工具；空名单无业务 `tool_call`（RAG 仍可）  
5. 单测：解析 `toolsJson`、Hub 传入 whitelist、Factory 走 `buildForSubAgent`

## 8. 触及模块（实现时）

| 层 | 改动 |
|----|------|
| expert-manager | Create/Update DTO + AdminService 读写 `toolsJson`；create 默认 `[]` |
| BFF / UI API | `experts.ts` create/update 传 `toolIds` |
| sunshine-ui | `ExpertsView.vue` 多选 + catalog 加载 + `*` 展开 |
| orchestrator | `ExpertHubEngine` 解析并注入 whitelist；`ExpertPeerAgentFactory` 改 `buildForSubAgent` |
| 测试 | 上述路径单测；可选补 live 断言 |
