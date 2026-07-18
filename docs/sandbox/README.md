# 4.5 沙箱文档索引

> **状态**：方案 B 已落地 · 工作区抽屉 / 写确认跳过 / 时间线路径 · **可取消工具（exec/grep/glob）** 已齐  
> **Live**：`python3 scripts/verify_sandbox_live.py --suite all`（含 G12 `#sandbox-agent` S4）· `python3 scripts/verify_sandbox_workspace_live.py` · `python3 scripts/verify_sandbox_tool_cancel_live.py`

## 设计 SSOT

| 文档 | 内容 |
|------|------|
| [skills-docker-sandbox-design](../superpowers/specs/2026-07-15-skills-docker-sandbox-design.md) | 4.5 初版六工具 + sandbox-service + PathJail / HITL / 审计 |
| [conversation-sandbox-permanent-tools](../superpowers/specs/2026-07-16-conversation-sandbox-permanent-tools-design.md) | **方案 B**：MAIN 常驻六工具 + 懒开箱 |
| [conversation-sandbox-multi-skill](../superpowers/specs/2026-07-16-conversation-sandbox-multi-skill-design.md) | 同会话多 Skill 懒挂载 `/skills/{id}/` |
| [sandbox-workspace-drawer](../superpowers/specs/2026-07-16-sandbox-workspace-drawer-design.md) | Chat 工作区抽屉（多 tab / 代码横向滚动 / `.md` 美化·原始 / 路径芯片） |
| [plan-sandbox-drawer-coexistence](../superpowers/specs/2026-07-17-plan-sandbox-drawer-coexistence-design.md) | 节点抽屉 × 沙箱对照模式（隐藏 Chat；树可独立调宽） |
| [sandbox-write-hitl-skip](../superpowers/specs/2026-07-16-sandbox-write-hitl-skip-design.md) | 工作区三档写确认：`never` / `always` / `smart` |
| [user-default-write-hitl](../superpowers/specs/2026-07-16-user-default-write-hitl-design.md) | 用户级默认写确认（auth `sys_user`，账号设置） |
| [sub-agent-sandbox-default](../superpowers/specs/2026-07-17-sub-agent-sandbox-default-design.md) | SUB / Workflow agent 默认六工具 + 对话级复用 |
| [sandbox-container-lifecycle](../superpowers/specs/2026-07-17-sandbox-container-lifecycle-design.md) | idle 停机 / 再进开机 / 7 天销毁 |
| [sandbox-tool-cancel](../superpowers/specs/2026-07-18-sandbox-tool-cancel-design.md) | **✅** exec/grep/glob 单工具取消（杀进程）· 主行「已取消」· 同族预算 3 · Live `verify_sandbox_tool_cancel_live` |

## 运维与示例

| 路径 | 说明 |
|------|------|
| `docs/nacos/sunshine-orchestrator.yaml` → `agent.sandbox` / `agent.sandbox.tools` / `agent.timeline.sandbox` / `agent.hitl` | 运行时、工具 schema 与时间线文案 |
| `com.sunshine.common.sandbox` | Policy + RPC DTO SSOT（orchestrator · skill-manager · sandbox-service） |
| `docs/nacos/sunshine-sandbox-service.yaml` | Docker / 出网等 |
| [sandbox-coding-demo howto](../skills/sandbox-coding-demo/references/sandbox-howto.md) | 路径约定与推荐工具 |
| [docs/skills/README.md](../skills/README.md) | 示例入库与方案 B 关系 |
| `docs/grafana/sandbox-dashboard.json` | Grafana 面板 |
| `docs/superpowers/plans/archive/*sandbox*` | 已完成 implementation plan（勿再当 SSOT） |

## 当前产品行为（摘要）

| 能力 | 行为 |
|------|------|
| 工具 | MAIN / SUB ReAct 始终 `sandbox__*`；不进 tool-manager Catalog；SUB 复用对话容器 |
| 开箱 | 首次 `sandbox__*` 或抽屉 list → `ensureSession`；同 `conversationId` 复用；**idle 30min 停机、再进 start；自上次活动 7d 销毁** |
| PathJail | `/workspace` 可写；`/skills/{id}/` 只读挂载 |
| write | **拒覆盖**已存在文件（须 edit / 换路径） |
| exec | `SandboxExecGuard` 硬拒破坏性命令；只读白名单免 HITL |
| HITL 默认 | write/edit 确认；危险 exec 确认；读类免确认 |
| 工作区跳过 | 会话 `writeHitlMode`：`never` / `always` / `smart`；**用户默认**见账号设置（auth） |
| 工作区抽屉 | 多 tab 预览；与 Plan 节点抽屉**可同时开**（`Chat \| 节点 \| 沙箱`，保留执行计划/DAG）；树可独立调宽；激活 tab 自动滚入可视区；代码不换行+横向滚动；**.md 美化/原始切换**；树节点拖入 Composer 为路径芯片 |
| 时间线主行 | 标签「调用工具 xxx」+ 摘要目标（无前导 ·）；glob 为 `{pattern} · /skills`；grep 仅 pattern；read 为 `{headerPath}` |
| **工具取消** | `exec`/`grep`/`glob` hover 圆形停止钮 → 杀该次调用；主行 **已取消**（`lifecycle=paused`）；展开可见 command/pattern；同族再调用 ≤3 · [详设](../superpowers/specs/2026-07-18-sandbox-tool-cancel-design.md) |
| HITL 确认框 | 不展示 content/old_string/new_string/command 正文（进展开） |
| edit 展开 | 行级 unified diff（同屏 `-`/`+`/` `） |

## 已知缺口（可选后续）

| 项 | 说明 | 优先级 |
|----|------|:------:|
| Live：`writeHitlMode` | Chat SSE：`G7` never 有确认 · `G10` always / `G11` smart 写免确认（`verify_sandbox_live --suite chat`） | ✅ |
| **单工具取消** | exec/grep/glob · Live `verify_sandbox_tool_cancel_live.py` | ✅ |
| 工作区可编辑 | 抽屉仍只读；写靠 Agent 工具 | 低（非目标） |
| SUB / Workflow 节点沙箱 | 默认注入六工具 + 对话级复用 · [详设](../superpowers/specs/2026-07-17-sub-agent-sandbox-default-design.md) | ✅ |
| 用户级默认写确认 | auth `sys_user.default_write_hitl_mode` + 账号设置；工作区仍本会话覆盖 · [详设](../superpowers/specs/2026-07-16-user-default-write-hitl-design.md) | ✅ |
| 二进制 / 下载 | 抽屉非目标 | — |
