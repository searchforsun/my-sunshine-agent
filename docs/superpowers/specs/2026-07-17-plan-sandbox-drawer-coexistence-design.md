# Plan 节点抽屉 × 沙箱工作区对照模式

**日期**：2026-07-17  
**状态**：✅ 已实现  
**范围**：Chat 右侧 `PlanNodeDrawer` 与 `SandboxWorkspaceDrawer` 共存布局；取消硬互斥销毁。  
**关联**：修订 [sandbox-workspace-drawer](./2026-07-16-sandbox-workspace-drawer-design.md)「与 PlanNodeDrawer 互斥」条款；索引 [docs/sandbox/README.md](../../sandbox/README.md)。

## 问题

两抽屉共用 `.chat-body` 右侧同一 flex 槽，open 时互关。看节点详情再开工作区会卸掉节点抽屉，选中节点与滚动位置丢失；无法对照节点输出与沙箱文件。

## 目标

1. **节点 + 沙箱同时打开**时进入**对照模式**：隐藏 Chat 主区；节点抽屉占原 Chat 位，沙箱仍在原右侧槽。
2. 关闭任一抽屉 → 退出对照；留下的抽屉回到右侧，Chat 恢复。
3. 沙箱内**文件树可独立拖宽**（min/max + 持久化）。
4. 对照分界与非对照抽屉宽均可拖，并有全局最小宽度防压死。

## 非目标

- 对照时保留可见 Chat（已否决；选 C 隐藏）。
- 三栏同时显示 Chat | 节点 | 沙箱。
- 后端 / SSE / 沙箱会话生命周期变更。
- DAG 放大层（`PlanDagExpandLayer`）行为大改（对照模式下仍可按现有 absolute 叠在节点区内，若冲突另开任务）。

## 布局状态机

| 状态 | `planOpen` | `sandboxOpen` | 布局 |
|------|------------|---------------|------|
| 仅 Chat | false | false | `chat-main` 全宽 |
| 单抽屉·节点 | true | false | `chat-main \| PlanNodeDrawer(右)` |
| 单抽屉·沙箱 | false | true | `chat-main \| SandboxWorkspaceDrawer(右)` |
| **对照** | true | true | **隐藏 `chat-main`**；`PlanNodeDrawer(主区) \| SandboxWorkspaceDrawer(右)` |

```
open plan / open sandbox
        │
        ▼
  两者皆 open？ ──否──► 单抽屉（Chat 可见）
        │是
        ▼
   对照模式（Chat 隐藏）
        │
   关闭 plan 或 sandbox
        │
        ▼
   退出对照；若仍有一侧 open → 该侧回右侧槽 + Chat 恢复
```

## UI 行为

### 进入 / 退出

- **进入**：任一抽屉已开时再打开另一侧 → 直接对照；**禁止**再调用 `closeSandboxWorkspaceDrawerIfOpen` / `planDrawer.close()` 作为互斥。
- **退出**：`plan.close()` 或 `sandbox.close()` 任一执行后，若仅剩一侧 → 该侧保持 open，布局回到「单抽屉」；两侧皆关 → 仅 Chat。
- **换会话**：沿用现逻辑，两侧皆关。
- **状态保留**：对照进出不重置 `usePlanNodeDrawer` 的 `node`/`step`，不重置沙箱 tabs / `focusPath` / `previewCache`（仅 `v-show` 或布局 class 切换，避免 `v-if` 整树销毁；若需 `v-if` 则状态必须留在 composable 模块级，组件可重建）。

### DOM / CSS（ChatView）

`.chat-body` 仍为行 flex。对照时：

- `chat-main`：`display: none` 或等效（不占 flex 空间）。
- `PlanNodeDrawer`：`flex: 1; min-width: PLAN_COMPARE_MIN`（见宽度表），不再仅作右侧窄栏。
- `SandboxWorkspaceDrawer`：右侧 `flex-shrink: 0`，宽度用持久化 `sandboxDrawerWidth`。
- 节点区与沙箱区之间：可拖分界（拖沙箱左缘或节点右缘，与现 resize handle 一致语义）。

单抽屉时行为与现网一致：抽屉在右、Chat 在左；`drawerMaxWidth = bodyW - CHAT_CONTENT_MIN_WIDTH`。

### 宽度约定

| 变量 | 建议默认 | Min | Max | 持久化 key |
|------|----------|-----|-----|------------|
| 单抽屉·节点宽 | 现网 | 400 | `bodyW - CHAT_CONTENT_MIN` | `sunshine-plan-drawer-width` |
| 单抽屉·沙箱宽 | 现网 | 520 | 同上 | `sunshine-sandbox-workspace-drawer-width` |
| Chat 内容列最小（单抽屉） | — | **868**（现网 `CHAT_CONTENT_MIN_WIDTH`） | — | — |
| 对照·沙箱宽 | 沿用沙箱宽 | 520 | `bodyW - PLAN_COMPARE_MIN` | 同上 |
| 对照·节点区 | 剩余宽度 | **400**（`PLAN_COMPARE_MIN`） | — | 由沙箱宽反推，可不另存 |
| 沙箱内文件树宽 | 220 | **160** | **360** | `sunshine-sandbox-tree-width` |
| `.chat-body` 全局最小宽 | — | 单抽屉：`868 + 抽屉 min`；对照：`400 + 520` | — | 不足时横向滚动，不压穿 min |

对照模式下**不再**要求为 Chat 预留 868px。

### 沙箱文件树拖拽

- 树与预览分栏之间增加 resize handle（垂直分割）。
- 拖动钳制 `[160, 360]`，松手写入 `localStorage`。
- 与抽屉整体宽度独立；缩窄抽屉时树宽不超过「抽屉内可用宽 − 预览最小（建议 240）」。

### 入口（语义不变）

- Composer「工作区」、时间线沙箱路径、`focusPath`、DAG/HITL 打开节点抽屉：行为同现网，仅去掉互斥 close。
- 节点抽屉内若后续加「打开工作区」入口：等同 `sandbox.open()`，可进入对照。

## 代码落点（实现指引）

| 区域 | 变更 |
|------|------|
| `sandboxDrawerBridge.ts` / `usePlanNodeDrawer.open` / `useSandboxWorkspaceDrawer.open` | 删除互关；可选导出 `isCompareMode = plan.open && sandbox.open` |
| `ChatView.vue` | `chat-main` 对照时隐藏；class 如 `chat-body--compare` |
| `PlanNodeDrawer.vue` | 对照时样式：主区 flex；resize 仍调沙箱宽或节点/沙箱分界 |
| `SandboxWorkspaceDrawer.vue` | 文件树独立宽度 + handle |
| 文档 | 本 spec；`2026-07-16-sandbox-workspace-drawer-design.md` 互斥句改为指向本篇；`docs/sandbox/README.md` 索引一行 |

## 验收（前端手工 / 可选 e2e）

1. 仅开节点 → Chat 可见，抽屉在右。  
2. 再开工作区 → Chat 隐藏，节点在左主区、沙箱在右；节点详情与选中仍在。  
3. 关沙箱 → Chat 恢复，节点仍在右侧。  
4. 再开沙箱进入对照 → 关节点 → Chat 恢复，沙箱在右。  
5. 对照下拖分界：节点区 ≥400、沙箱 ≥520。  
6. 沙箱内拖文件树：宽在 160–360，刷新后记忆。  
7. 换会话 → 两侧关闭，Chat 全宽。

## 已否决

- Tab 同槽切换（保留状态但不并排）——用户要对照文件与节点。  
- 三栏保留 Chat——用户选 C 隐藏以换空间。  
- 工作区浮层叠在节点上——易遮挡，不选。
