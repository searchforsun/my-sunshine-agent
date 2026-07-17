# 4.5 沙箱文档索引

> **状态**：方案 B 已落地 · 工作区抽屉 / 写确认跳过 / 时间线路径展示已齐  
> **Live**：`python3 scripts/verify_sandbox_live.py --suite all` · `python3 scripts/verify_sandbox_workspace_live.py`

## 设计 SSOT

| 文档 | 内容 |
|------|------|
| [skills-docker-sandbox-design](../superpowers/specs/2026-07-15-skills-docker-sandbox-design.md) | 4.5 初版六工具 + sandbox-service + PathJail / HITL / 审计 |
| [conversation-sandbox-permanent-tools](../superpowers/specs/2026-07-16-conversation-sandbox-permanent-tools-design.md) | **方案 B**：MAIN 常驻六工具 + 懒开箱 |
| [conversation-sandbox-multi-skill](../superpowers/specs/2026-07-16-conversation-sandbox-multi-skill-design.md) | 同会话多 Skill 懒挂载 `/skills/{id}/` |
| [sandbox-workspace-drawer](../superpowers/specs/2026-07-16-sandbox-workspace-drawer-design.md) | Chat 工作区抽屉（多 tab / 代码横向滚动 / `.md` 美化·原始 / 路径芯片） |
| [sandbox-write-hitl-skip](../superpowers/specs/2026-07-16-sandbox-write-hitl-skip-design.md) | 工作区三档写确认：`never` / `always` / `smart` |
| [user-default-write-hitl](../superpowers/specs/2026-07-16-user-default-write-hitl-design.md) | 用户级默认写确认（auth `sys_user`，账号设置） |

## 运维与示例

| 路径 | 说明 |
|------|------|
| `docs/nacos/sunshine-orchestrator.yaml` → `agent.sandbox` / `agent.timeline.sandbox` / `agent.hitl` | 运行时与时间线文案 |
| `docs/nacos/sunshine-sandbox-service.yaml` | Docker / 出网等 |
| [sandbox-coding-demo howto](../skills/sandbox-coding-demo/references/sandbox-howto.md) | 路径约定与推荐工具 |
| [docs/skills/README.md](../skills/README.md) | 示例入库与方案 B 关系 |
| `docs/grafana/sandbox-dashboard.json` | Grafana 面板 |

## 当前产品行为（摘要）

| 能力 | 行为 |
|------|------|
| 工具 | MAIN ReAct 始终 `sandbox__read/write/edit/glob/grep/exec`；不进 tool-manager Catalog |
| 开箱 | 首次 `sandbox__*` 或抽屉 list → `ensureSession`；同 `conversationId` 复用 |
| PathJail | `/workspace` 可写；`/skills/{id}/` 只读挂载 |
| write | **拒覆盖**已存在文件（须 edit / 换路径） |
| exec | `SandboxExecGuard` 硬拒破坏性命令；只读白名单免 HITL |
| HITL 默认 | write/edit 确认；危险 exec 确认；读类免确认 |
| 工作区跳过 | 会话 `writeHitlMode`：`never` / `always` / `smart`；**用户默认**见账号设置（auth） |
| 工作区抽屉 | 多 tab 预览；激活 tab 自动滚入可视区；代码不换行+横向滚动；**.md 美化/原始切换**（复制旁）；树节点拖入 Composer 为路径芯片 |
| 时间线主行 | 标签「调用工具 xxx」+ 摘要目标（无前导 ·）；glob 为 `{pattern} · /skills`；grep 仅 pattern |
| HITL 确认框 | 不展示 content/old_string/new_string/command 正文（进展开） |
| edit 展开 | 行级 unified diff（同屏 `-`/`+`/` `，兼容旧 `<<< old`） |

## 已知缺口（可选后续）

| 项 | 说明 | 优先级 |
|----|------|:------:|
| Live：`writeHitlMode` | Chat SSE 带 `always`/`smart` 断言无/有 confirmation（现仅单测） | 中 |
| 工作区可编辑 | 抽屉仍只读；写靠 Agent 工具 | 低（非目标） |
| SUB / Workflow 节点沙箱 | 默认不注入；需节点显式开启 | 低 |
| 用户级默认写确认 | auth `sys_user.default_write_hitl_mode` + 账号设置；工作区仍本会话覆盖 · [详设](../superpowers/specs/2026-07-16-user-default-write-hitl-design.md) | ✅ |
| 二进制 / 下载 | 抽屉非目标 | — |
