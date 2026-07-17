# 沙箱 Workspace 抽屉（对话级保留）

**日期**：2026-07-16  
**状态**：✅ 已实现  
**范围**：Chat 右侧抽屉浏览当前会话 `/workspace`（及挂载的 `/skills`）；对话级复用沙箱容器。  
**索引**：[docs/sandbox/README.md](../../sandbox/README.md)

## 需求（已确认）

1. **生命周期**：同一 Chat `conversationId` 内复用 workspace；idle TTL（默认 30min）或删除会话后销毁。
2. **入口**：Composer「工作区」按钮 + 时间线 `sandbox__*` 步骤点击（`focusPath`）。
3. **范围**：抽屉树含 workspace + 已挂载 skills（只读预览）。

## 架构要点

- Orchestrator Redis：`sandbox:conv:{tenant}:{conversationId}` → `{sessionId, loadedSkillIds[], userId}` + ZSET 到期索引。
- **方案 B**：容器在**首次 `sandbox__*`** 或抽屉 list 时 `ensureSession`。
- `closeQuietly`：仅 unbind，**不** DELETE 容器。
- 真正销毁：TTL Reaper / `DELETE conversation`。
- API：`GET /api/conversations/{id}/sandbox/workspace[+ /content|/status]`。
- SSE：`type:sandbox_session`。

## UI

- `SandboxWorkspaceDrawer`：左树右预览；与 `PlanNodeDrawer` 互斥。
- 顶栏 **写操作确认** 三档（`WriteHitlModeSelector`）：`never` / `always` / `smart` — 见 [write-hitl-skip](./2026-07-16-sandbox-write-hitl-skip-design.md)；本会话可覆盖用户默认 — 见 [user-default-write-hitl](./2026-07-16-user-default-write-hitl-design.md)。
- 换会话关闭抽屉；无绑定时可开抽屉（list 触发懒开箱或提示）。

### 资源树

- 分区：`/workspace` + 已挂载 `/skills/...`；只读浏览。
- 节点可 **拖入 Chat Composer** → 路径胶囊芯片（basename 展示；发送为 `` `/workspace/...` `` 或 `` `/skills/...` ``）；点击芯片 → 抽屉 `focusPath`。

### 多标签预览

| 行为 | 约定 |
|------|------|
| 打开文件 | 追加 tab；同路径不重复；切换 tab 复用 `previewCache` |
| 关闭 tab | 关闭当前则激活相邻 tab；全部关闭则空态 |
| Tab 栏 | 可横向滚动；**激活 tab 自动 `scrollIntoView`**（切文件 / 点树 / `focusPath`） |
| 面包屑 | 当前路径 + 可选 meta；右侧工具栏：复制；**.md 另有美化/原始切换** |

### 正文预览

| 类型 | 默认 | 滚动 / 换行 | 字体 |
|------|------|-------------|------|
| 代码 / 文本 | hljs 高亮（`pre`） | **不自动换行**；`.preview-scroll` **横向滚动** | `--sun-font-mono` |
| **Markdown 美化** | `StaticMarkdown` 渲染 | **自动换行**；**无**横向滚动（`overflow-x: hidden`） | 正文样式 |
| **Markdown 原始** | 同代码预览（markdown 高亮） | 同代码：不换行 + 横向滚动 | `--sun-font-mono` |

- `.md` 面包屑旁切换钮（复制左侧）：美化态显示「原始显示」（`CodeSlash`）；原始态显示「美化显示」（`Eye`）；**换文件重置为美化**。
- 复制：始终复制**源码正文**（与当前美化/原始视图无关）。

## 时间线与跳转（配套）

| 区域 | 展示 | 跳转 |
|------|------|------|
| 工具主行 | `label` + `summary` 目标；glob 为 `{pattern} · /skills`（或检索根）；**无**前导「·」、**无**「完成」后缀 | 点击行 → 抽屉 `focusPath`=完整容器路径 |
| grep 主行 | 仅 pattern（不加搜索根后缀） | 同上 |
| glob 展开列表 | **相对路径**（去 `/skills`/`/workspace` 前缀） | 点击项 → 同 focusPath |
| exec 展开 | 完整 command（换行）+ 输出 | — |
| 沙箱工具展开正文 | monospace（`--sun-font-mono`） | — |

文案 SSOT：Nacos `agent.timeline.sandbox` + `SandboxTimelineLabelService`（禁止前端硬编码步骤话术）。

## 非目标

编辑上传、二进制下载、多 skill 并行容器。
