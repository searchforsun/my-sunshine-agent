# Skill 包（文档 SSOT）

本目录为 **SKILL.md 与物料包** 的源码，供编写与评审；**不会**在 MySQL init 时自动入库。用运维脚本或 UI 上传后生效。

## 使用方式

1. 编辑本目录对应 Skill 包（根目录须含 `SKILL.md`）
2. 同步 Live：

```bash
python3 scripts/sync_enterprise_skills.py
```

或在 Sunshine UI **Skills 管理** 中上传 → 发布并生效 → 开启卡片 Switch。

## 企业技能列表

| 目录 | 说明 |
|------|------|
| `finance-analysis/` | 报销/费用与制度内部合规分析（corpus-50） |
| `policy-qa/` | 制度问答（制度/政策/办法咨询，轨 A） |
| `policy-review/` | 多域制度条款解读（青松假、网约车、锁钥通道等） |
| `compliance-check/` | 制度与业务数据逐项合规对比 |
| `compliance-review/` | 费用合规审查（报销合规对照场景） |
| `expense-assist/` | 报销助手（报销查询/提交辅助场景） |
| `travel-budget/` | 差旅预算（差旅额度与预算管控场景） |
| `finance-report/` | 本人费用汇总与待办构成解读 |
| `knowledge-brief/` | corpus-50 检索结果要点提炼 |
| `sandbox-coding-demo/` | 工作区沙箱编程（4.5；历史 id，供沙箱 Live 验收） |

> **已移除**：`demo-full-pack`（结构演示包）。禁止再入库 demo 技能。

### 沙箱与 Skill 的关系（方案 B）

> **SSOT 索引**：[docs/sandbox/README.md](../sandbox/README.md) · [permanent-tools](../superpowers/specs/2026-07-16-conversation-sandbox-permanent-tools-design.md)

- 主 Chat ReAct **始终**可使用 `sandbox__*`（对话级 `/workspace`）。
- Skill 提供 **指令 overlay** + 可选 **`/skills/{skillId}/` 物料挂载**（`/skill` 或 L3 `skillId`）。
- Catalog 字段 `sandbox=docker` 仅作展示/种子标识；**不再**决定 orchestrator 是否注入沙箱工具。
- 工作区抽屉可调 **写确认跳过**（`writeHitlMode`）；时间线 glob 展开显示相对路径。

### 沙箱技能入库（4.5）

```bash
# 需 Gateway + skill-manager 可用；镜像需已构建
python3 scripts/build_sandbox_image.py          # 若尚未构建
python3 scripts/seed_sandbox_skill.py           # 创建/上传/发布/启用 sandbox-coding-demo
python3 scripts/seed_sandbox_skill.py --force   # 已存在时重传并刷新 sandbox 元数据
# 或与企业技能一并同步：
python3 scripts/sync_enterprise_skills.py
```

Chat 试跑（有 `@` 时懒挂载物料到 `/skills/sandbox-coding-demo/`）：

```text
@sandbox-coding-demo 请用沙箱工具：读取 /skills/sandbox-coding-demo 下脚本，在 /workspace 写 test.txt，再 ls
```

Workflow 节点通过 `skillId` 引用已在平台发布并启用的 Skill；workflow 图定义在 **workflow-manager DB**（`/workflows` Studio），见 [workflow-studio-design](../superpowers/specs/2026-06-25-workflow-studio-design.md).
