# Plan 节点抽屉 × 沙箱工作区对照模式

**日期**：2026-07-17  
**状态**：✅ 已实现  
**范围**：Chat 右侧 `PlanNodeDrawer` 与 `SandboxWorkspaceDrawer` 共存布局；取消硬互斥销毁。  
**关联**：修订 [sandbox-workspace-drawer](./2026-07-16-sandbox-workspace-drawer-design.md)「与 PlanNodeDrawer 互斥」条款；索引 [docs/sandbox/README.md](../../../sandbox/README.md)。

## 问题

两抽屉共用 `.chat-body` 右侧同一 flex 槽，open 时互关。看节点详情再开工作区会卸掉节点抽屉，选中节点与滚动位置丢失；无法对照节点输出与沙箱文件。

## 目标

1. **节点 + 沙箱同时打开**时进入**双抽屉模式**：保留 Chat 主区（含执行计划/DAG）；布局为 `Chat | PlanNodeDrawer | SandboxWorkspaceDrawer`。
2. 关闭任一抽屉 → 留下的抽屉仍在右侧，Chat 恢复更宽。
3. 沙箱内**文件树可独立拖宽**（min/max + 持久化）。
4. 各抽屉可拖宽，并有全局最小宽度防压死。

## 非目标

- ~~对照时隐藏 Chat~~（已否决：会把执行计划/DAG 一并藏掉，节点抽屉误占主区）。
- 后端 / SSE / 沙箱会话生命周期变更。

## 布局状态机

| 状态 | `planOpen` | `sandboxOpen` | 布局 |
|------|------------|---------------|------|
| 仅 Chat | false | false | `chat-main` 全宽 |
| 单抽屉·节点 | true | false | `chat-main \| PlanNodeDrawer(右)` |
| 单抽屉·沙箱 | false | true | `chat-main \| SandboxWorkspaceDrawer(右)` |
| **双开** | true | true | `chat-main \| PlanNodeDrawer \| SandboxWorkspaceDrawer` |

### DOM / CSS（ChatView）

`.chat-body` 仍为行 flex。双开时：

- `chat-main`：**保持可见**（执行计划/放大 DAG 仍在主区）。
- `PlanNodeDrawer` / `SandboxWorkspaceDrawer`：均为右侧固定宽抽屉（`flex-shrink: 0`），不互关。
- `.chat-body--both-drawers { min-width: 1340px }`（420+400+520）。

### 宽度约定

| 变量 | Min | Max 公式 |
|------|-----|----------|
| Chat | **420**（`PANE_MIN_WIDTH`） | — |
| 节点抽屉 | **420** | 单开：`body - 420`；双开：`body - 420 - 沙箱实宽` |
| 沙箱抽屉 | **420**（默认偏好 520） | 单开：`body - 420`；双开：`body - 420 - 节点实宽` |
| 沙箱文件树 | 160–360 | `min(360, drawer - 240)` |

`.chat-body--both-drawers { min-width: 1260px }`（420×3）。

双开时节点↔沙箱分界：只要 `节点宽+沙箱宽 > 840` 即可拖（不因沙箱已顶到相对 Chat 的 max 而隐藏 handle）。


## 验收

1. 执行计划放大 + 点节点 → 右侧节点抽屉，DAG 仍在主区。  
2. 再开工作区 → 三栏：执行计划 | 节点抽屉 | 沙箱；DAG 不消失。  
3. 关其一 → 另一抽屉仍在，主区更宽。  
4. 树宽 / 抽屉宽可拖且有 min。

## 已否决

- 双开时隐藏 Chat 并把节点抽屉当主区——会吞掉执行计划/DAG。  
- 右侧 Tab 切换（可后续增强，本版三栏并排对照）。
