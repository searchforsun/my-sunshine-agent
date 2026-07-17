# 4.6.4 ContextCompressor（AutoContextMemory）设计

> 状态：已确认 · 2026-07-17  
> 归属：阶段四 4.6.4 / 原 P5「STM 工具结果摘要」

## 1. 问题

单次 ReAct run 内，多轮 TOOL 结果进入 AgentScope `Memory` 后易撑爆下一轮 reasoning 上下文。跨轮 Sunshine STM（user/assistant）不含 tool 轨迹，现有 `StmWindowPolicy` 解决不了本问题。

## 2. 方案

接入 AgentScope 1.0.7 自带的 **`AutoContextMemory` + `AutoContextHook`**（方案 C），不自研裁剪/摘要器。

| 做 | 不做 |
|----|------|
| ReAct / SUB 经 `ReActAgentFactory` 注入 AutoContext | 改 Timeline / SSE 工具结果 |
| Nacos `agent.memory.auto-context.*` 可开关与调参 | 改跨轮 STM/MTM/LTM |
| 压缩发生在 `PreReasoning`（给下一轮 LLM） | 对模型最终答案二次加工 |

## 3. 接入

```text
ReActAgent.builder()
  .memory(enabled ? AutoContextMemory(config, model) : 默认)
  .hook(AutoContextHook)           // priority=0；注册 ContextOffloadTool
  .hook(ProcessingStepHook)        // Timeline 不变
```

每次 `create` 新建 Memory，无跨请求污染。

## 4. 配置默认值

见 Nacos `agent.memory.auto-context`：相对库默认略收紧（`msg-threshold=40`、`last-keep=12`、`min-consecutive-tool-messages=4`、`min-compression-token-threshold=3000`）。

## 5. 验收

- 单测：enabled 开关决定 memory 类型  
- Live：长工具链 ReAct 可完成；Timeline 工具步仍完整  
- 文档：phase4 4.6.4 ✅
