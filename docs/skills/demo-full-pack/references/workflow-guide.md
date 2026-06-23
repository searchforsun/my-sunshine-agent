# Workflow 引用指南

本子 Agent 在 workflow 中作为 **agent 节点** 内嵌运行，不直接面向用户。

## 输入期望

- `query`：用户原始问题（由编排器传入）
- `context`：上游 rag / tool 节点注入的结构化材料

## 输出期望

- 结构化内部分析 JSON（见 `templates/brief.schema.json`）
- 禁止面向用户的礼貌用语
