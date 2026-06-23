# Skills 管理页设计（SSOT）

> **状态**：3.12 已落地（2026-06-23 对齐 `sunshine-ui/src/views/SkillsView.vue`）  
> **路由**：`/skills` · **后端**：skill-manager :8225 · **BFF 透传** · **上传/下载二进制** 直连 Gateway :8000  
> **关联**： [multi-agent plan §3.11–3.12](../plans/2026-06-19-multi-agent-architecture.md) · [locked D3](./2026-06-19-locked-architecture-decisions.md#d3-skills-服务端管理--前端运营页)

---

## 1. 页面结构

```
┌─────────────────────────────────────────────────────────────────┐
│  Skills 管理                          [新建] [刷新]              │
├──────────────┬──────────────────────────────────────────────────┤
│ 列表（左）    │ 详情（右）                                        │
│ · 搜索       │ · 标题：skillId + displayName + 维护人            │
│ · Skill 卡片 │ · 工具栏：当前版本 + 状态 Tag + 版本下拉 + ⋯ 更多  │
│   └ ⋯ 更多   │ · 内容区：文件树 | 文件预览                       │
└──────────────┴──────────────────────────────────────────────────┘
```

| 区域 | 组件 | 说明 |
|------|------|------|
| 左栏 | `list-panel` | 搜索 + Skill 卡片列表；**loading 仅绑定列表刷新** |
| 右栏 | `detail-panel` | 选中 Skill 的元信息、版本、包浏览；**loading 独立**（切换/上传/下载） |
| 空态 | `detail-empty` | 未选中 Skill 时占位 |

**禁止**：前端维护 skill 话术 Map；步骤文案走 SSE / Catalog，与本页无关。

---

## 2. 概念与术语

| 概念 | UI 表现 | 后端 |
|------|---------|------|
| **Skill** | 左侧卡片 + `skillId` | `skill_definition` |
| **版本** | 时间戳下拉（`yyyy/MM/dd HH:mm:ss`） | `skill_version.version` + `created_at` |
| **版本状态** | Tag：**生效** / **草稿** / **非生效** | `published` + 是否等于 `active_version` |
| **Skill 开关** | 卡片右上角 Switch：**开启/关闭** | `PUT .../enable`；仅控制 runtime 是否可见 |
| **发布并生效** | 详情 ⋯ → 发布并生效 / 设为生效 | `POST .../publish`；设为 active 并自动开启 Skill |
| **元数据** | 卡片 ⋯ → 修改（displayName、description） | `PUT /api/skills/{id}`；**不改** SKILL.md 包内正文 |

上传 SKILL.md 后，frontmatter `description` 会回写 definition.description；**在线 overlay 编辑**见 §5.3。

---

## 3. 左侧 Skill 卡片

| 元素 | 行为 |
|------|------|
| 主区域点击 | 选中 Skill，加载版本列表与文件树 |
| 标题 | `skillId`（JetBrains Mono） |
| 副文案 | displayName、生效版本时间、维护人 |
| 描述 | **单行省略** |
| Switch | 开启/关闭 Skill；**未发布草稿不可开启**（`activeVersionPublished`） |
| 右下角 **⋯** | **修改**（displayName + description，ID 只读）· **删除**（整个 Skill） |

---

## 4. 右侧详情工具栏

| 元素 | 行为 |
|------|------|
| 标题区 | 仅 `skillId`；displayName / 维护人在副标题行 |
| **当前版本** | 状态 Tag + 版本下拉（**仅时间戳**，状态不在下拉文案中重复） |
| **⋯ 更多** | 见下表 |

### 4.1 详情 ⋯ 更多菜单

| 菜单项 | 显示条件 | 动作 |
|--------|----------|------|
| 设为此生效版 / 发布并生效 | 当前版本有文件且非 active 生效 | `publish` |
| 复制为草稿 | 当前为**生效**或**历史**版本且有文件 | `fork` → 新草稿 |
| 上传文件夹 | 非 setup；草稿首次发布前隐藏 | 本地完整 Skill 包 |
| 下载 ZIP | 当前版本有文件 | zip 下载 |
| 删除此版本 | 版本数 > 1 | 仅删该版本文件 |

**删除整个 Skill** 仅在**左侧卡片 ⋯** 中提供，不在详情 ⋯ 中重复。

---

## 5. 内容区与预览

### 5.1 生命周期阶段（`skillPhase`）

| 阶段 | 条件 | UI |
|------|------|-----|
| `setup` | 无任何已上传包 | 空状态 +「选择文件夹」 |
| `draft` | 当前选中版本为草稿 | 文件树 + 预览；引导发布 |
| `live` | 选中版本 = active 且已发布 | 正常浏览 |
| `history` | 选中历史非 active 版本 | 正常浏览 + 可「设为生效」 |

### 5.2 文件树与预览

- 树：相对包根路径；目录/文件图标区分
- **SKILL.md / Markdown**：`.msg-md` + 与对话页共享 `markdown-content.css`、Mermaid、代码高亮
- **代码/文本**：highlight.js
- **图片**：内联预览（base64）
- **二进制**：仅展示类型与估算大小
- 预览栏：**复制**（Markdown/文本）

参考包结构见 `docs/skills/demo-full-pack/`。

### 5.3 在线编辑（3.12.2）

| 规则 | 说明 |
|------|------|
| 可编辑范围 | **草稿**版本内的**文本**文件（含 SKILL.md、.py、.md 等） |
| 不可编辑 | 已发布/历史版本、二进制文件 |
| 入口 | 预览栏 **编辑** → 文本域；**保存** / **取消** |
| 保存 API | `PUT /api/skills/{id}/versions/{version}/file?path=` |
| 基于版本开草稿 | `POST /api/skills/{id}/versions/{version}/fork`（生效/历史版 ⋯ 菜单） |
| SKILL.md | 保存时解析 frontmatter，同步 `systemOverlay` 与 `definition.description` |
| 切换文件 | 有未保存修改时确认 |

保存后仍需 **发布并生效**，runtime Catalog 才会更新。

---

## 6. 上传流程

1. 用户点击「上传新版本」或 setup「选择文件夹」
2. **立即**进入右侧 loading（文案「等待选择文件夹…」）；左侧列表**不受影响**
3. 系统文件夹选择器（浏览器原生）；取消则结束 loading
4. 校验根目录含 `SKILL.md`
5. 客户端 `zipFolderFiles` 打包 → `POST /api/skills/{id}/upload`（**Gateway :8000**，避免 Vite 破坏 FormData）
6. 成功后选中最新草稿版本并刷新文件树；toast「已上传为草稿，请预览后发布并生效」

**Loading 约定**

| 状态 | 左侧 | 右侧 |
|------|------|------|
| `loading` | 列表 Spin | 未选中时 empty Spin |
| `detailLoading` / `uploading` / `downloading` | **不** Spin | 全详情区遮罩 + 文案 |
| 遮罩显示时 | — | **不渲染**底层文件树/预览（避免透出其他 Skill 内容） |

---

## 7. API（BFF → skill-manager）

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/skills` | 管理列表 |
| POST | `/api/skills` | 新建 Skill |
| PUT | `/api/skills/{id}` | 修改 displayName、description |
| PUT | `/api/skills/{id}/enable` | 开启/关闭 Skill |
| DELETE | `/api/skills/{id}` | 删除整个 Skill |
| GET | `/api/skills/{id}/versions` | 版本列表 |
| POST | `/api/skills/{id}/upload` | 上传 zip / SKILL.md |
| POST | `/api/skills/{id}/publish?version=` | 发布并生效 |
| DELETE | `/api/skills/{id}/versions/{version}` | 删除单版本 |
| GET | `/api/skills/{id}/versions/{version}/files` | 文件列表 |
| GET | `/api/skills/{id}/versions/{version}/file?path=` | 读单文件 |
| PUT | `/api/skills/{id}/versions/{version}/file?path=` | 在线编辑（仅草稿） |
| POST | `/api/skills/{id}/versions/{version}/fork` | 基于指定版本复制为新草稿 |
| GET | `/api/skills/{id}/versions/{version}/download` | 下载 zip |

Runtime Catalog（orchestrator 用，非本页主路径）：

- `GET /api/skills/catalog/index` — 摘要，Chat `@` 补全
- `GET /api/skills/{id}/catalog` — 含 overlay 正文

---

## 8. 代码映射

| 层级 | 路径 |
|------|------|
| 页面 | `sunshine-ui/src/views/SkillsView.vue` |
| API | `sunshine-ui/src/api/skills.ts` |
| 文件夹 zip | `sunshine-ui/src/utils/zipStore.ts` |
| 文件树 | `sunshine-ui/src/utils/buildFileTree.ts` |
| 版本时间 | `sunshine-ui/src/utils/formatSkillVersionTime.ts` |
| BFF | `bff/.../SkillsController.java`、`SkillManagerClient.java` |
| 服务 | `skill-manager/.../SkillAdminController.java` |

---

## 9. 待办（3.12 缺口）

| 编号 | 内容 | 状态 |
|------|------|:----:|
| 3.12.2 | 在线编辑 **overlay 正文**（SKILL.md body）；版本 diff / 回滚 | ✅ 编辑 ✅；diff ⬜ |
| 3.12.4 | Timeline `plan` 步跳转 Plan 详情页 | ⬜ |
| 3.11.7 | Chat `@` + 强提示绑定 Skill | ⬜ |
| — | 子 Agent 试跑 / 沙箱调试 | 阶段四 |

~~3.12.3 工具绑定~~：已取消；工具由 workflow 节点 `params.tools` 配置。

---

## 10. 验收清单

- [x] 新建 Skill → 上传文件夹 → 预览 → 发布并生效 → 开启 Switch
- [x] 多版本切换、设为生效、下载该版本、删除该版本
- [x] 卡片修改 displayName/description；卡片删除整个 Skill
- [x] 上传/刷新时左右 loading **相互独立**
- [x] 加载遮罩不透出其他 Skill 文件内容
- [x] 在线编辑 overlay（3.12.2）— 草稿版本文本文件 + SKILL.md 同步 overlay
