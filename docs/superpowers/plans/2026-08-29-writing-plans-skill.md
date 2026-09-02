# writing-plans 技能落地实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Sunshine 平台落地官方 `writing-plans` 技能，使其进入技能目录，并能经 `sunshine_search_skills` 运行中加载、挂载到沙箱 `/skills/writing-plans/` 供模型读取（对齐 Cursor / Claude Code 的「先写计划再动手」流程）。

**Architecture:** 以 `docs/skills/writing-plans/SKILL.md` 为平台技能本体（frontmatter + 正文，正文即 `system_overlay` 来源）；经 `scripts/sync_enterprise_skills.py` 的 `ENTERPRISE_SKILLS` 元数据注册，走 `/api/skills/{id}/upload → publish → enable` 入库；skill 声明 `sandbox: docker` 使物料挂载到 `/skills/writing-plans/`。模型在 `kind: task` 会话经 `/writing-plans` 显式触发，或经 `sunshine_search_skills` 运行中加载后，在沙箱 `/skills/writing-plans/SKILL.md` 读取正文与步骤。

**Tech Stack:** Java 21（orchestrator）/ Spring Cloud Alibaba / AgentScope-Java 2.0 / MySQL（`skill_definition` + `skill_version`）/ Docker 沙箱（`sandbox-service`）/ `scripts/sync_enterprise_skills.py`（运维 Python）。

**Spec:** [skill-sticky-process-chain-design v3.16](docs/superpowers/specs/2026-08-12-skill-sticky-process-chain-design.md)（技能加载机制）· [CLAUDE.md 技能种子规约](CLAUDE.md)（`docs/skills/` SSOT + 全量快照原则）· [skill-sticky S-C 段](docs/superpowers/specs/2026-08-12-skill-sticky-process-chain-design.md)（`sunshine_search_skills` 常驻 MAIN 加载入口）

**假设（供审阅时确认）：**
- `writing-plans` 作为**可挂载沙箱的技能**落地（`sandbox: docker`）——用户明确要求「加载到沙箱工作区」，需 `/skills/writing-plans/SKILL.md` 物料。若只需 prompt 注入（无沙箱只读），可将 `sandbox` 字段置为 `none`，后续任务的沙箱挂载/断言步骤相应删除。
- `kind` 取 `task`（写计划是任务型场景，非闲聊）。如需在 `chat` 会话也触发，改 `all`。
- 技能正文以官方 superpowers 6.3.0 `writing-plans` 为蓝本，做 Sunshine 平台适配（保留「先计划后动手」核心方法论，去掉 Claude Code 专属措辞，提示词模板对齐 `docs/skills/sandbox-coding-demo` 风格）。

## Global Constraints

- 技能正文 SSOT = `docs/skills/{id}/SKILL.md`，`systemOverlay` 由 SKILL.md 正文（frontmatter 之后）生成，禁止在 DB 直接硬编码正文。
- 种子 SQL `docker/mysql/init/19-sunshine-resource.sql` 为**全量快照**；skills 改动的种子同步须按 `docs/skills/` + `sync_enterprise_skills.py` 物化，禁止手改 INSERT。
- 改 `docs/nacos/*.yaml` 后必跑 `python scripts/sync_nacos.py`；后端功能改动后必须重启对应服务 `start.py`。
- 沙箱挂载到 `/skills/{id}/`（只读），可写区仅为 `/workspace`；禁止模型写 `/skills`。
- `kind` 取值 `chat|task|all`；`sunshine_search_skills` 校验 `enabled` + 租户可见（`TenantVisibility.visible`），候选集仅作目录提权标记。
- 前端/后端禁止硬编码技能名；技能 id = 目录名 = `writing-plans`。

---

### Task 1: 创建 writing-plans 技能本体 SKILL.md

**Files:**
- Create: `docs/skills/writing-plans/SKILL.md`
- (可选) Create: `docs/skills/writing-plans/scripts/`、`docs/skills/writing-plans/references/`（如技能正文引用脚本/参考，本技能为纯流程技能，通常不需要，如无则省略）

**Interfaces:**
- Consumes: 官方 superpowers 6.3.0 `writing-plans`（`/root/.claude/plugins/cache/claude-plugins-official/superpowers/6.3.0/skills/writing-plans/SKILL.md`）作为内容蓝本。
- Produces: `docs/skills/writing-plans/SKILL.md`（frontmatter `name`/`description` + 正文）。正文将成为 `skill_version.system_overlay`；frontmatter 之后的正文即注入模型的 skill 指令。

- [ ] **Step 1: 创建目录与 SKILL.md**

创建 `docs/skills/writing-plans/`，写入 `SKILL.md`（frontmatter + Sunshine 适配正文）。正文须覆盖：**开头声明**（"I'm using the writing-plans skill..."）、**Scope Check**、**File Structure**、**Task Right-Sizing**、**Bite-Sized Task Granularity**、**Plan Document Header**、**Task Structure**、**No Placeholders**、**Self-Review**、**Execution Handoff**、**保存路径规约**（`docs/superpowers/plans/YYYY-MM-DD-<feature>.md`）。

frontmatter 示例：

```markdown
---
name: writing-plans
description: Use when you have a spec or requirements for a multi-step task, before touching code — 先产出可执行分步实施计划，再动手编码
---
```

- [ ] **Step 2: 校验 SKILL.md 格式**

用 `build_zip`（sync 脚本）同款规则校验：目录下有 `SKILL.md` 且可读；frontmatter 含 `name`/`description`。

Run:
```bash
python3 -c "from pathlib import Path; p=Path('docs/skills/writing-plans/SKILL.md'); assert p.is_file(); print('OK', p)"
```
Expected: `OK docs/skills/writing-plans/SKILL.md`

- [ ] **Step 3: Commit**

```bash
git add docs/skills/writing-plans/
git commit -m "feat(skills): add writing-plans skill body (superpowers adaptation)"
```

---

### Task 2: 注册进 sync_enterprise_skills.py 并入库

**Files:**
- Modify: `scripts/sync_enterprise_skills.py`（`ENTERPRISE_SKILLS` 列表追加 `writing-plans` 元数据）

**Interfaces:**
- Consumes: `docs/skills/writing-plans/SKILL.md`（Task 1）、GATEWAY_URL（默认 `http://127.0.0.1:8000`）。
- Produces: Live `skill_definition`（id=writing-plans）+ `skill_version`（system_overlay、storage_path、sandbox=docker）。后续 Task 3 依赖其已入库。

- [ ] **Step 1: 追加元数据到 ENTERPRISE_SKILLS**

在 `scripts/sync_enterprise_skills.py` 的 `ENTERPRISE_SKILLS` 列表追加：

```python
{
    "id": "writing-plans",
    "displayName": "编写实施计划",
    "description": "多步任务先产出可执行分步实施计划再动手（对齐 Cursor / Claude Code writing-plans）",
    "sandbox": "docker",
    "sandboxPolicy": {
        "runtime": "docker",
        "image": "sunshine-sandbox-python:3.11-slim",
        "timeoutSec": 30,
        "memoryMb": 256,
        "cpus": 0.5,
        "networkAllow": [],
        "execReadonlyAllow": ["ls *", "pwd"],
    },
},
```

> 若确认无沙箱只读需求，将 `sandbox`/`sandboxPolicy` 整体去掉（默认 `none`），并删除 Task 3 的沙箱断言步骤。

- [ ] **Step 2: 跑同步脚本入库**

Run:
```bash
python3 scripts/sync_enterprise_skills.py
```
Expected: 日志出现 `[OK] POST /api/skills writing-plans`（首次）或 `[OK] PUT 元数据 writing-plans`（已存在），随后 `[OK] upload writing-plans -> v1`、`[OK] publish writing-plans v1`、`[OK] enabled writing-plans`、`[OK] sandbox=docker on writing-plans v1`。

- [ ] **Step 3: 校验目录可见 + 版本已发布**

Run:
```bash
GATEWAY_URL=http://127.0.0.1:8000 python3 -c "
import os,requests
from sunshine_lib import unwrap_r
g=os.environ['GATEWAY_URL']
# 用已登录会话 token 查询，或直接查 DB
"
```
Expected: skill id `writing-plans` 在 `/api/skills` 列表中出现；`/api/skills/writing-plans/versions` 最小 version 状态为 `published`。

- [ ] **Step 4: Commit**

```bash
git add scripts/sync_enterprise_skills.py
git commit -m "feat(skills): register writing-plans in enterprise skill sync"
```

---

### Task 3: 端到端验证 writing-plans 经 sunshine_search_skills 加载到沙箱工作区

**Files:**
- (仅验证，不新增源文件) 参考已有 Live 脚本 `scripts/verify_skill_sticky_live.py` 的注册/建会话/SSE 手法；可复用其鉴权与流式解析辅助函数。

**Interfaces:**
- Consumes: Live 已启用技能 `writing-plans`（Task 2）、`sunshine_search_skills`（常驻 MAIN，本次已修复）、`kind: task` 会话。
- Produces: 端到端结论（技能正文进上下文 + 物料挂到 `/skills/writing-plans/`）。

- [ ] **Step 1: 建 kind:task 会话并触发 /writing-plans**

用与 `verify_skill_sticky_live.py` 相同的注册/登录/建会话流程，`content: "/writing-plans 为 XX 功能实现计划"`、`executionMode: "fast"`、`kind: task`，POST `/api/chat/stream` 观察 SSE。

Expected: SSE 出现 `skill_information`（或 SYSTEM 层注入的技能正文），时间线出现「加载技能 writing-plans」步骤。

- [ ] **Step 2: 验证沙箱物料挂载（sunshine_search_skills 加载后）**

在会话内让模型调用 `sunshine_search_skills({"skill_id": "writing-plans"})` 或显式触发后，检查 orchestrator 日志：

Run:
```bash
grep -E "SkillSearchTool.*writing-plans|sandbox skill mounted.*writing-plans|ensureBound.*loading" <orchestrator-log>
```
Expected: 出现 `[SkillSearchTool] 运行中加载技能升级 triggered skill=writing-plans ...` 与 `sandbox skill mounted ... skillId=writing-plans`。

- [ ] **Step 3: 沙箱内读取 /skills/writing-plans/SKILL.md**

在会话中令模型执行 `sandbox__glob` / `sandbox__read` 指向 `/skills/writing-plans/SKILL.md`。

Expected: 模型读到 skill 正文（frontmatter 之外的操作指引），能给出计划文档的结构（Plan Header / Task 结构 / No Placeholders）。若未挂载，检查 `mountSkillForBridge` 是否在会话创建前调用（`runContexts` 缺 `skillId` 时懒挂未落地）。

- [ ] **Step 4: 记录结论并回归**

Run:
```bash
mvn test -pl orchestrator 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: orchestrator 全量测试通过（参考基线 1440/1440）。

---

## Self-Review

**1. Spec coverage:** spec（skill-sticky v3.16）要求「运行中加载任意 enabled + 租户可见技能并挂载 `/skills/{id}/`」——Task 2 的 `sandbox: docker` + sync 入库满足注册；Task 3 验证 `sunshine_search_skills` 加载路径。`kind: task` 满足 spec 技能路由。✅

**2. Placeholder scan:** 无 TBD/TODO/占位步骤；每步给真实文件路径、命令、断言。Task 1 Step 1 的正文要点为枚举而非占位。✅

**3. Type consistency:** `writing-plans` id、`docs/skills/writing-plans` 路径、`SKILL.md`、`sunshine_search_skills`、`/skills/writing-plans/` 全链一致；`sandbox` 字段在 Task 2 声明、Task 3 断言，与 `sync_enterprise_skills.py` 的 `meta["sandbox"]` 键名一致。✅

**已知缺口：**
- 本次代码改动（`SkillSearchTool`/`DynamicToolkitFactory`/`ReactExecutor` + 3 个测试文件）**尚未提交**，与本文档是两批工作。`sunshine_search_skills` 常驻 MAIN 依赖该改动已在本地编译（orchestrator 1440 全绿），但**未部署到生产**——需在 Task 3 验证前先 `python scripts/start.py --restart orchestrator` 装载新代码。
- 沙箱物料挂载对「会话创建前调用的技能」依赖 `prepareRun`/`runContexts` 已登记 `skillId`；若 `/writing-plans` 是 L0 显式触发，`triggeredSkillIds` 会带 `skillId`，`mountSkillForBridge` 正常。需在 Task 3 Step 3 实测确认。

## Execution Handoff

「Plan complete and saved to `docs/superpowers/plans/2026-08-29-writing-plans-skill.md`。Two execution options:

**1. Subagent-Driven (recommended)** — 每个 Task 派发独立 subagent，Task 间审查。

**2. Inline Execution** — 本会话内按 executing-plans 分批执行，带检查点。

Which approach?」
