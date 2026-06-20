# Agent 三层记忆方案（方案 C）

> **⚠️ 已并入** [phase2-benchmark-design.md](./phase2-benchmark-design.md) **§2.17**；租户过滤见 [phase3-production-hardening-design.md](./phase3-production-hardening-design.md) **§3.2**。下文为历史详设。

> 日期：2026-06-17 · **LTM/MTM 摘要 + STM 完整轮次**

## 注入顺序（LLM messages）

```
system  主提示词
system  记忆分层说明（layer-prompt）
system  [用户画像 · LTM] …              ← 摘要
system  [相关历史情景 · MTM] …          ← 摘要
system  [本会话近期对话 · STM] 边界说明
user    …                               ← STM 完整轮次
assistant …
…
system  scope-prompt（作答边界）
user    【当前提问 · 仅此作答】\n{本轮问题}
```

## 分层策略

| 层 | 注入形式 | 说明 |
|----|----------|------|
| **LTM** | system 摘要块 | 用户画像，≤500 字 |
| **MTM** | system 摘要块 | 跨会话情景，向量召回 |
| **STM** | 边界 system + **完整 user/assistant 轮次** | 滑动窗口 12 条 / 8000 字（窗口级裁剪，单条不截断） |
| **当前提问** | 带标记的 user | 唯一作答对象 |

## 组件

- `MemoryComposer` — 编排三层
- `MemoryMessageBuilder` — LTM/MTM 摘要 + STM 轮次 + 当前提问
- `StmWindowPolicy` — 窗口条数/总字符
- `StmBoundaryFormatter` — STM 边界 system 文案

配置 SSOT：`docs/nacos/sunshine-orchestrator.yaml` → `agent.memory.*`
