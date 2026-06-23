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

Workflow 节点通过 `skillId` 引用已在平台发布并启用的 Skill，见 Nacos `sunshine-workflows.yaml`。
