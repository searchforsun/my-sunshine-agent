---
name: demo-full-pack
description: 标准 Skill 包结构演示（含引用、脚本、模板、资源）
---

# 全量结构演示 Skill

用于验证 Skills 管理页对**标准 Skill 包**的上传、目录树与多类型文件预览。

## 目录结构

1. `SKILL.md` — 主说明与 frontmatter（本文件）
2. `references/` — 供 Agent 引用的制度、术语等文档
3. `scripts/` — 可执行脚本（Python / Shell）
4. `templates/` — 输出模板与 JSON Schema
5. `assets/` — 样例数据、图标、清单
6. `resources/` — 其他辅助资源

## 操作步骤

1. 在 UI 新建 Skill，ID 填 `demo-full-pack`
2. 上传本目录（根目录须含 `SKILL.md`）
3. 左侧文件树逐一点击，确认 md / 代码 / JSON / CSV / 图片均可预览
4. 预览无误后 **发布并生效**

## 推荐编排流程

```mermaid
flowchart LR
  A[检索制度] --> B[拉取待办]
  B --> C[本子 Skill 分析]
  C --> D[下游 LLM 答复]
```

## 约束

- 本子 Skill 仅用于平台能力验收，不参与生产 workflow
- 脚本为示例，勿在生产环境直接执行
