# Skill 示例（文档）

本目录为 **SKILL.md 参考示例**，供编写与评审使用，**不会**自动写入 skill-manager 或 Agent Catalog。

## 使用方式

1. 在 Sunshine UI **Skills 管理** 中新建 Skill（ID 可与示例目录名一致）
2. 上传对应文件夹（根目录须含 `SKILL.md`）
3. 预览后 **发布并生效**（详情 ⋯ 菜单），再开启卡片 Switch

## 示例列表

| 目录 | 说明 |
|------|------|
| `finance-analysis/` | 财务合规分析子 Agent |
| `policy-review/` | 制度审查子 Agent |
| `compliance-check/` | 合规对比子 Agent |
| `demo-full-pack/` | **全量结构演示**（references / scripts / templates / assets / resources） |
| `finance-report/` | 财务报告类示例 |
| `knowledge-brief/` | 知识摘要类示例 |
| `sandbox-coding-demo/` | **4.5 Docker 沙箱** Coding Agent 示例（scripts + references；`sandbox=docker`） |

### 沙箱示例入库（4.5）

```bash
# 需 Gateway + skill-manager 可用；镜像需已构建
python3 scripts/build_sandbox_image.py          # 若尚未构建
python3 scripts/seed_sandbox_skill.py           # 创建/上传/发布/启用 sandbox-coding-demo
python3 scripts/seed_sandbox_skill.py --force   # 已存在时重传并刷新 sandbox
```

Chat 试跑：`@sandbox-coding-demo 请用沙箱工具：读取 /skill 下脚本，在 /workspace 写 test.txt，再 ls`  
或在 `/skills` 打开该 Skill 点 **试跑**。

Workflow 节点通过 `skillId` 引用已在平台发布并启用的 Skill；workflow 图定义在 **workflow-manager DB**（`/workflows` Studio），见 [workflow-studio-design](../superpowers/specs/2026-06-25-workflow-studio-design.md)。
