# 移除「简单对话」执行模式（simple-llm）

> **状态**：设计已确认，待实现  
> **日期**：2026-07-17  
> **关联**：[2026-06-25-chat-execution-mode-selector-design.md](./2026-06-25-chat-execution-mode-selector-design.md)（底栏选择器；本变更废止其中 simple-llm 行）  
> **触发**：企业场景不再适合无工具单轮直答；底栏「简单对话」需完全去掉，不做兼容

---

## 1. 目标与边界

### 1.1 目标

彻底移除用户可见与路由层的「简单对话 / `simple-llm`」：

| 层 | 动作 |
|----|------|
| Chat 底栏 | 不再展示「简单对话」 |
| `executionPreference` | 不再接受 `simple-llm` 为合法强制模式 |
| 意图 L3 | classifier 词表删除 `simple-llm`；原闲聊/通识/润色类统一走 **ReAct** |
| 执行器 | 删除 `SimpleLlmExecutor` 与 `ExecutionDispatcher` 对应分支 |
| 枚举 | 删除 `ExecutionMode.SIMPLE_LLM`、`ExecutionPreference.SIMPLE_LLM` |

### 1.2 方案选型（已确认）

在「硬删除执行模式」之上，同步将内部「直连 Gateway、无工具」的 prompt 枚举从 `PromptMode.SIMPLE_LLM` **重命名**为 `PromptMode.DIRECT`（overlay 键 `direct`），避免与已删除的执行模式同名。直连能力本身保留。

### 1.3 非目标

- 不为旧 `simple-llm` preference / intent 做映射、降级或 4xx 专用错误
- 不改 ReAct / Workflow / Plan-Workflow / Peer-Collab 的业务语义
- 不保留「无工具顶层执行模式」的替代入口
- 不借机重构 `StreamDeltaNormalizer` / content 无分段路径（仍服务直连 Gateway）

### 1.4 上线后底栏选项

自动 · 自主推理 · 工作流 · 动态规划 · 多专家协作

---

## 2. 路由 / 执行器 / Prompt 重命名

### 2.1 删除清单（执行层）

- `ExecutionMode.SIMPLE_LLM`、`ExecutionPreference.SIMPLE_LLM` 及 `from` / `wireValue` 分支
- `ForcedExecutionRouter` 的 `SIMPLE_LLM` 分支与 `user:forced-simple-llm`
- `SimpleLlmExecutor` 类；`ExecutionDispatcher` 的 `case SIMPLE_LLM`
- `RuleBasedRouter` 中 `simple-llm` / `simple` → `SIMPLE_LLM` 映射
- `ExecutionPlanParser.parseStoredIntent` 中 `"simple-llm"` 显式分支
- `IntentLabelService` / `TimelineLabelTemplates` 中 SIMPLE_LLM 文案分支
- 注释与 BFF/模型字段注释中的 `simple-llm` 合法值说明

### 2.2 解析默认（不做兼容分支）

清库后不依赖历史值。实现上：

- `ExecutionMode.from`：未知串（含历史 `simple-llm` / `simple` / 旧别名 `direct`）走现有 **default → REACT**
- `ExecutionPreference.from`：未知串走现有 **default → AUTO**

禁止新增「`simple-llm` → REACT」的显式兼容 case。

> **注意**：Prompt overlay 新键名为 `direct`；`ExecutionMode.from("direct")` 不得再映射到已删除的 SIMPLE_LLM，应落入 default → REACT。二者命名空间分离（prompt overlay vs 路由 mode）。

### 2.3 意图词表（Nacos SSOT）

`docs/nacos/sunshine-orchestrator.yaml`：

- `agent.intent.classifier-prompt`：`mode` 仅 `workflow|react|plan-workflow|peer-collab`；删除 simple-llm 规则行；原「简单对话、通识百科…」场景写入 **react** 规则（拿不准仍用 react）
- 删除 `agent.timeline.intent.modes.simple-llm` 整段
- 删除 `agent.timeline.steps.think.modes.simple-llm` 整段
- `AgentPromptProperties` 中对应 Java 默认 map 同步删除

同步：`python scripts/sync_nacos.py` 后重启 orchestrator。

### 2.4 Prompt 内部重命名（方案 2）

| 现名 | 新名 |
|------|------|
| `PromptMode.SIMPLE_LLM("simple-llm")` | `PromptMode.DIRECT("direct")` |
| `PromptComposeRequest.forSimpleLlm` | `forDirect` |
| `PromptComposeRequest.forSimpleLlmContinue` | `forDirectContinue` |
| Nacos `agent.prompt.mode-overlays.simple-llm` | `mode-overlays.direct`（值可仍为空串） |

调用方（`LlmGatewayClient`、专家/workflow 直连拼装等）只改符号与 overlay 键；**流式 / 无工具语义不变**。  
相关注释由「simple-llm」改为「直连 Gateway / DIRECT」。

### 2.5 保留

- `LlmGatewayClient.streamWithMemory` / `streamContinue` 及 `StreamDeltaNormalizer`（直连路径）
- ReAct / Workflow / Plan / Peer 全链路

---

## 3. 前端 / 清库 / 验收

### 3.1 前端

- `sunshine-ui/src/api/executionModes.ts`：去掉 `simple-llm` 类型、选项、`isExecutionPreference`
- `executionModeIcons.ts`：删除对应图标
- `resumeMode.ts`、`contentInterleave.ts`：删除对 `simple-llm` 的特殊分支
- `ExecutionModeSelector` 随 `EXECUTION_MODE_OPTIONS` 自动少一项
- e2e（如 `processing-timeline.spec.ts` 期望「简单对话」）改为自动/ReAct 路径断言
- `mock-server.mjs`：删除或改写「简单对话」演示分支

### 3.2 清库（实现后、验收前必跑）

**不做代码侧兼容**；直接清除历史会话与缓存：

```bash
python scripts/clear_session_cache.py --force --restart-orchestrator
```

浏览器执行该脚本输出的 localStorage 清理 JS（含 `sunshine-execution-preference`）。

### 3.3 单测 / Live / 文档

| 项 | 动作 |
|----|------|
| `ForcedExecutionRouterTest` / `RoutingGoldenSetTest` J1 | 删除 simple-llm 强制用例 |
| `ExecutionPlanParserTest` | 去掉 simple-llm 期望；未知 → REACT |
| `PromptComposerTest` 等 | 跟随 `DIRECT` / `forDirect*` |
| `GenerationJobTest` 等以 `SIMPLE_LLM` 为 mode 的 | 改用 `REACT` 或直连无关断言 |
| `scripts/verify_execution_preference.py` | 去掉 J1 `simple-llm`；保留其余强制模式 |
| `docs/routing/routing-golden-set.md` §J | 更新合法 preference 集合与用例表 |
| CLAUDE.md / README 等「含简单对话」表述 | 同步删改 |
| 旧 selector design | 文首或 §1 表加废止注记，指向本文 |

### 3.4 成功标准

1. 底栏无「简单对话」
2. 强制 `simple-llm` 不再作为产品合法模式（清库后不依赖）
3. 自动路由下原闲聊类问题 → **ReAct**（无 `SIMPLE_LLM` 执行路径）
4. 直连 Gateway（workflow llm 等）经 `PromptMode.DIRECT` 仍可用
5. `verify_execution_preference.py`（无 J1）与相关单测通过

---

## 4. 风险与约束

| 风险 | 处理 |
|------|------|
| 清库不可逆 | 上线前确认；仅用现有 `clear_session_cache.py`，范围 chat_* + Redis STM/gen |
| 意图模型仍输出 `simple-llm` | 改 prompt 后 `ExecutionMode.from` default → REACT；无专用兼容分支 |
| overlay 键 `direct` 与旧 mode 别名 | 路由 `from` 不再识别 `direct` 为执行模式；仅 Prompt overlay 使用 |

**约束**：禁止对模型输出做截断/摘要兜底；不对就改 Nacos 意图提示词。禁止 Flyway；本变更无新表。

---

## 5. 实现顺序（概要）

1. Nacos intent + timeline + mode-overlays 改键/删段 → sync →（实现阶段再重启）
2. 后端枚举 / 路由 / 删除 SimpleLlmExecutor / Prompt 重命名 + 单测
3. 前端 options / 图标 / e2e / mock
4. 文档与 Live 脚本
5. `clear_session_cache.py --force --restart-orchestrator` + 浏览器 localStorage
6. Live 验收 `verify_execution_preference.py` 等

详细任务拆分见后续 implementation plan。
