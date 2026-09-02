

> 日期：2026-07-24
> 状态：✅ 已实现（WF-1～WF-5；TypedValue / VariableAssignmentNodeHandler / ParameterExtractorNodeHandler 落地，4.13.8）
> 前置依赖：AgentScope 2.0 迁移完成 ✅（P0–P3+P7，2026-07-26）

## 1. 背景与问题

### 1.1 当前 workflow 数据流机制

当前系统存在显式的变量引用机制（非纯透传）：WorkflowContext 是 nodeId -> field -> value 的全局变量表，TemplateResolver 支持 {{nodeId.field}} 模板解析。每个节点执行前由 WorkflowNodeRunner.resolveParams 解析 params 中的占位符，替换为上游节点输出。

### 1.2 七个核心缺陷

| 编号 | 缺陷 | 根因 |
|------|------|------|
| D1 | 参数是扁平 Map<String,String>，无类型 schema | NodeSpec.params 无类型声明、无校验 |
| D2 | 工具输出是纯文本，结构化数据靠脆弱的 output.extract | ToolNodeHandler 输出固定字符串字段 |
| D3 | 变量引用只有单层 nodeId.field，不支持嵌套 | WorkflowContext.resolvePath 仅 indexOf(".") |
| D4 | plan-workflow answer 节点无差别全量注入上游 | PlanAnswerPromptAssembler.buildUpstreamBlock |
| D5 | 缺少变量转换/代码/参数提取节点 | 节点类型不足 |
| D6 | 并行分支输出合并是空操作 | JoinNodeHandler 仅写 status=joined |
| D7 | 重试不重新解析模板 | NodeRetryExecutor 同参重试 |

### 1.3 与成熟 Agent 工作流平台（如 Dify）的差距

| 维度 | 当前系统 | 目标 |
|------|---------|------|
| 变量类型系统 | 扁平 Map<String,String> | 强类型 JSON 变量 |
| 变量引用 | {{nodeId.field}} 单层 | {{nodeId.path[0].field}} 嵌套 |
| 节点输入 schema | 无，靠保留 key 约定 | 显式 inputs 绑定 + 类型校验 |
| 节点输出 schema | 固定字符串字段 | 结构化 JSON 输出 |
| 数据转换节点 | 无 | 变量赋值 + 参数提取 |
| answer 数据引用 | plan-workflow 全量注入 | 精确变量引用 |
| 并行输出聚合 | join 空操作 | 支持聚合策略 |

## 2. 设计决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 范围策略 | 彻底重构变量类型系统 | 一次根治全部 7 个缺陷，避免过渡态维护负担 |
| 兼容策略 | 删旧建新 | 直接删除旧 workflow 数据，创建新结构，不做双格式兼容 |
| 时机 | AS2 迁移完成后 | workflow 引擎与 agent runtime 模块边界清晰，可独立实施 |
| 技术方案 | JSON-First 结构化变量管线 | Jackson 已是项目依赖，JSON 是通用格式，和工具契约天然对齐 |


## 3. 变量类型系统（地基）

### 3.1 TypedValue

统一的变量值类型，sealed interface + record 实现：

```java
public sealed interface TypedValue permits Scalar, JsonObject, JsonArray {
    String render();        // prompt 注入时的人类可读渲染
    JsonNode toJson();      // 结构化取值
}

public record Scalar(JsonNode value) implements TypedValue {
    public String render() { return value.isTextual() ? value.asText() : value.toString(); }
    public JsonNode toJson() { return value; }
}

public record JsonObject(ObjectNode node) implements TypedValue {
    public String render() { return node.toPrettyString(); }
    public JsonNode toJson() { return node; }
}

public record JsonArray(ArrayNode node) implements TypedValue {
    public String render() { return node.toPrettyString(); }
    public JsonNode toJson() { return node; }
}
```

render() 规则：
- Scalar：文本直接返回 asText()，数字/布尔返回 toString()
- JsonObject / JsonArray：返回 toPrettyString()（JSON 序列化），用于 prompt 注入时保持可读

### 3.2 WorkflowContext 重构

从 Map<String, Map<String, String>> 升级为 Map<String, Map<String, TypedValue>>：

```java
public class WorkflowContext {
    private final Map<String, Map<String, TypedValue>> nodes = new LinkedHashMap<>();
    private final Map<String, NodeFailureInfo> failures = new LinkedHashMap<>();

    public void putNode(String nodeId, Map<String, TypedValue> outputs) { ... }

    public TypedValue resolvePath(String path) {
        // 支持：nodeId.field.subfield[0].key
        // 解析路径段（点号分隔 + 方括号索引）
        // 逐层进入 JsonObject / JsonArray 取值
    }
}
```

resolvePath 路径解析算法：
1. 按第一个点号拆分 nodeId 和剩余 path
2. 从 nodes.get(nodeId) 取初始 TypedValue
3. 对剩余 path 逐段解析（field 或 [index]），在 JsonObject/JsonArray 上取值
4. 特殊处理 plan.params.xxx（plan 虚拟节点的 params）

### 3.3 变量引用语法

| 引用 | 含义 | 示例 |
|------|------|------|
| {{nodeId.output}} | 整个 output | {{tool_1.output}} |
| {{nodeId.output.data.id}} | 嵌套取值 | {{tool_1.output.data.id}} |
| {{nodeId.output.items[0].title}} | 数组索引取值 | {{rag_1.hits[0].title}} |
| {{start.userQuery}} | 用户问题（标量） | {{start.userQuery}} |
| {{plan.params.status}} | plan 参数（标量） | {{plan.params.status}} |

### 3.4 TemplateResolver 升级

```java
public final class TemplateResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\{\{([^}]+)}}");

    public static String resolve(String template, WorkflowContext ctx) {
        // 匹配 {{...}}，调 ctx.resolvePath，取 TypedValue.render()
    }

    public static TypedValue resolveTyped(String path, WorkflowContext ctx) {
        // 新增：返回 TypedValue，供需要结构化数据的场景（如 tool 入参）
        return ctx.resolvePath(path);
    }
}
```

### 3.5 NodeSpec 升级

```java
public record NodeSpec(
    String id,
    String type,
    Map<String, Object> params,        // 控制参数（tool/retry/prompt 等保留 key）
    List<InputBinding> inputs,          // 显式输入绑定
    NodeOutputSchema outputs,           // 输出 schema 声明（可省略，用节点类型默认）
    String displayName
) {}
```

params 值可以是：String（模板字符串）/ Number / Boolean / JsonNode。resolveParams 阶段对 String 值做模板解析，非 String 值原样保留。

### 3.6 InputBinding

```java
public record InputBinding(
    String name,          // 参数名（如 expenseId）
    String source,        // 引用路径（如 {{tool_1.output.data.id}}）
    VarType type,         // string / number / boolean / object / array
    boolean required      // 必填校验
) {}

public enum VarType { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY }
```


## 4. 节点 I/O 契约重构

### 4.1 Tool 节点：结构化输入 + 结构化输出

**输入端**：从 inputs 读取绑定值，按 type 做类型校验，构造结构化 invokeParams（JSON object）发给 tool-manager：

```java
// ToolNodeHandler.run() 伪代码
Map<String, Object> invokeParams = new LinkedHashMap<>();
for (InputBinding binding : spec.inputs()) {
    TypedValue val = TemplateResolver.resolveTyped(binding.source(), ctx);
    if (binding.required() && (val == null || val instanceof Scalar s && s.value().isNull())) {
        return NodeResult.fail("缺少必填参数: " + binding.name());
    }
    // 类型校验：按 binding.type() 校验 val 的 JSON 类型
    validateType(binding, val);
    invokeParams.put(binding.name(), val.toJson());
}
ObjectNode result = toolManagerClient.invokeJson(tool, invokeParams, userId, tenantId);
```

废弃 RESERVED_INVOKE_KEYS 约定，业务入参全部从 inputs 读取。params 只保留 tool / retry.* / output.* 控制字段。

**输出端**：tool 返回的 JSON 直接存为结构化 TypedValue，废弃 output.extract：

```java
Map<String, TypedValue> outputs = new LinkedHashMap<>();
outputs.put("output", TypedValue.fromJson(result));         // 完整 JSON 结构
outputs.put("tool", TypedValue.scalar(tool));                // 工具 ID
outputs.put("summary", TypedValue.scalar(summarize(result))); // 人类可读摘要
```

下游 tool 节点直接 {{tool_1.output.data.items[0].id}} 嵌套取值，不再需要 extract 配置。

### 4.2 RAG 节点：结构化检索结果

```java
Map<String, TypedValue> outputs = new LinkedHashMap<>();
outputs.put("output", TypedValue.scalar(formattedText));      // 兼容：格式化文本
outputs.put("hits", TypedValue.fromJson(hitsArray));           // 新增：结构化命中列表
outputs.put("hitCount", TypedValue.scalar(results.size()));
```

下游可 {{rag_1.hits[0].content}} 取单条检索内容，或 {{rag_1.output}} 取格式化全文。

### 4.3 Answer 节点：精确引用替代全量注入

废弃 PlanAnswerPromptAssembler 的全量上游注入。改为：

- **Plan-Workflow**：Planner 产出的 answer 节点 prompt 中显式写引用，如 {{rag_1.output}} + {{tool_1.output.data}}。Assembler 只负责把 Catalog answer.template 模板套上，不再自动拼接全部上游。
- **静态 Workflow**：Studio 编辑时手动写引用（已有能力，不变）。
- **passThrough 兜底保留**：无 prompt 时仍取上游最后一个有 output 的节点（安全网）。

PlanAnswerPromptAssembler 重写：删除 buildUpstreamBlock 逻辑，只做模板套用。

### 4.4 Agent 节点：结构化输出

```java
Map<String, TypedValue> outputs = new LinkedHashMap<>();
outputs.put("answer", TypedValue.scalar(answer));
outputs.put("output", TypedValue.scalar(answer));
outputs.put("toolCalls", TypedValue.fromJson(toolCallsArray));  // 结构化工具调用列表
outputs.put("detail", TypedValue.scalar(summaryLine));
```

### 4.5 各节点输出 schema 约定

| 节点类型 | 输出字段 | 类型 |
|---------|---------|------|
| start | userQuery, userId, tenantId | scalar |
| rag | output(格式化文本), hits(结构化数组), hitCount(number) | mixed |
| tool | output(完整 JSON), tool, summary(文本) | mixed |
| agent | answer(文本), output(=answer), toolCalls(array) | mixed |
| answer | answer, output(=answer) | scalar |
| llm | answer, output, reasoning | scalar |
| variable-assignment | 各 assignment name | per-type |
| parameter-extractor | output(完整 JSON), 各 schema 字段 | mixed |
| join | output(聚合结果), status | per-strategy |


## 5. 新增数据转换节点

### 5.1 变量赋值节点（VariableAssignmentNode）

用途：在流程中间设置/转换/拼接变量。

节点配置：
```json
{
  "id": "var_1",
  "type": "variable-assignment",
  "params": {
    "assignments": [
      { "name": "expenseId", "source": "{{tool_1.output.data.id}}", "type": "string" },
      { "name": "totalAmount", "source": "{{tool_1.output.data.total}}", "type": "number" },
      { "name": "combinedQuery", "source": "{{rag_1.output}}\n\n用户原始问题：{{start.userQuery}}", "type": "string" }
    ]
  }
}
```

输出：每个 assignment 成为一个输出字段，下游可 {{var_1.expenseId}} 引用。

执行逻辑：对每个 assignment 解析 source（模板解析），按 type 校验，存入 outputs。

### 5.2 参数提取节点（ParameterExtractorNode）

用途：用 LLM 从非结构化文本（如 agent 输出、长文本）中提取结构化参数。

节点配置：
```json
{
  "id": "extract_1",
  "type": "parameter-extractor",
  "params": {
    "input": "{{agent_1.output}}",
    "instruction": "从报销审批意见中提取：审批人、审批结果(approved/rejected)、审批意见",
    "schema": {
      "approver": { "type": "string", "description": "审批人姓名" },
      "result": { "type": "string", "enum": ["approved", "rejected"] },
      "comment": { "type": "string", "description": "审批意见" }
    }
  }
}
```

输出：
```java
outputs.put("output", TypedValue.fromJson(extractedJson));   // 完整结构
outputs.put("approver", TypedValue.scalar(approver));        // 各字段单独暴露
outputs.put("result", TypedValue.scalar(result));
outputs.put("comment", TypedValue.scalar(comment));
```

下游可 {{extract_1.result}} 或 {{extract_1.output.approver}} 引用。

实现：复用 llm-gateway，prompt 由 Catalog parameter-extractor.template 提供，要求 LLM 输出 JSON，引擎解析后按 schema 校验。校验失败可配置重试（复用 NodeRetryExecutor）。

### 5.3 不新增的节点

- **代码节点（CodeNode）**：暂不实现。安全沙箱成本高，当前已有沙箱方案 B（4.5）。变量赋值 + 参数提取已覆盖 80% 数据转换场景。未来如需可作为 sandbox exec 特殊形态接入。
- **条件判断节点（ConditionNode）**：不新增。复用 exclusive-gateway 边条件（第 6 节增强），已覆盖条件分支需求。
- **不引入完整表达式引擎**（SpEL/Aviator）：YAGNI，当前业务场景不需要计算表达式。需要计算时用参数提取节点让 LLM 算，或用变量赋值节点拼接。未来可在变量赋值节点增加 expression 模式作为扩展点。


## 6. 并行聚合与条件增强

### 6.1 JoinNodeHandler 升级：输出聚合

当前 join 节点是空操作（只写 status=joined）。升级为支持聚合策略：

节点配置：
```json
{
  "id": "join_1",
  "type": "join",
  "params": {
    "mergeStrategy": "collect",
    "branches": ["branch_a", "branch_b"]
  }
}
```

聚合策略：

| strategy | 行为 | 输出 |
|----------|------|------|
| collect（默认） | 收集各分支 output 为数组 | {{join_1.output}} = [branch_a.output, branch_b.output] |
| merge | 深度合并各分支 output（object） | {{join_1.output}} = {...branch_a, ...branch_b} |
| first | 取第一个完成的分支 | {{join_1.output}} = first branch output |
| last | 取最后一个完成的分支 | {{join_1.output}} = last branch output |

实现：join 执行时从 WorkflowContext 读取各分支节点的 output，按策略聚合。collect 用 JsonArray，merge 用 JsonObject 深度合并。

下游可 {{join_1.output[0]}} 取第一个分支结果，或 {{join_1.output}} 取聚合结果。

### 6.2 EdgeConditionEvaluator 升级

当前边条件只支持 empty/not_empty/contains/eq 四个算子。升级为：

新增算子：

| op | 示例 | 说明 |
|----|------|------|
| gt / lt / gte / lte | {{rag_1.hitCount}} gt 5 | 数值比较（自动类型转换） |
| in / not_in | {{extract_1.result}} in ["approved","pending"] | 枚举判断 |
| contains | {{tool_1.output.data.tags}} contains "urgent" | 数组/字符串包含 |

left 支持 JSONPath：{{extract_1.output.result}} 而非只能 {{extract_1.result}}。
right 可为字面量：字符串、数字、JSON 数组。


## 7. DB Schema 与数据迁移

### 7.1 plan_json 结构变更

旧结构（扁平 params）：
```json
{
  "nodes": [{
    "id": "tool_1",
    "type": "tool",
    "params": { "tool": "sdk__xxx", "status": "{{plan.params.status}}" }
  }]
}
```

新结构（结构化 inputs + params）：
```json
{
  "nodes": [{
    "id": "tool_1",
    "type": "tool",
    "params": { "tool": "sdk__xxx", "retry.maxAttempts": "2", "retry.onFailure": "continue" },
    "inputs": [
      { "name": "status", "source": "{{plan.params.status}}", "type": "string", "required": true }
    ]
  }]
}
```

- params：只保留控制参数（tool / retry.* / prompt / query 等），业务入参移到 inputs
- inputs：显式输入绑定数组
- 不加 version 字段，新结构是唯一格式

### 7.2 迁移策略

按项目约定（禁止 Flyway，SSOT 在 docker/mysql/init/）：

1. 重写 13-sunshine-workflow-manager.sql：删除 8 标杆的旧 plan_json，用新结构重写。删旧建新，不保留旧数据。

2. checkpoint 格式变更：WorkflowContextCodec 序列化格式从 Map<String,Map<String,String>> 改为 JSON。execution_plan 表的 checkpoint 列存新格式。存量运行中 workflow 清空（execution_plan 表 truncate）。

3. 新增节点类型注册：WorkflowNodeType 枚举新增 VARIABLE_ASSIGNMENT / PARAMETER_EXTRACTOR。

### 7.3 PlanJsonParser 校验

解析时校验节点结构合法性：
- tool 节点必须有 params.tool
- inputs 数组每项必须有 name / source / type
- 非法结构直接报错（因为旧数据已删，遇到非法说明是错误数据）


## 8. Studio 前端适配

### 8.1 节点编辑面板改造

Tool 节点编辑器：
- 当前：一个 params 键值对编辑器，用户手动填 status、{{tool_1.output}} 等
- 改为：
  - 工具选择：下拉选 Catalog ID（不变）
  - 输入参数编辑器（新增）：列出 inputs 绑定，每行：参数名 | 变量引用选择器 | 类型 | 必填
  - 变量引用选择器：树形下拉，展示所有上游节点的输出字段（基于输出 schema），点选即生成 {{nodeId.path}}

新增节点面板：
- 变量赋值节点编辑器：assignments 列表（name | source 引用选择器 | type）
- 参数提取节点编辑器：input 引用 | instruction 文本 | schema 编辑器（字段名 | 类型 | 描述 | 枚举）

Join 节点编辑器：
- 新增 mergeStrategy 下拉（collect / merge / first / last）

### 8.2 变量引用选择器（核心组件）

这是 Studio 体验提升的关键。当前用户要手写 {{tool_1.output}}，不知道有哪些字段可用。改为：

- 扫描当前节点之前的所有节点，基于节点类型默认输出 schema 生成可选变量树
- 树结构：节点名 > 输出字段 > 子字段（对 JsonObject 递归展开）
- 点击叶子节点插入 {{nodeId.path}} 到当前编辑位置

实现：前端维护各节点类型的输出 schema 字典（从 WorkflowNodeType 推导），Studio 编辑时实时构建变量树。

### 8.3 DAG 画布数据流可视化（可选增强）

在 DAG 连线上显示数据流标签：tool_1.output -> tool_2.inputs[0].expenseId。非 MVP 必需，视前端工作量决定。


## 9. 实施阶段划分与验收

### 9.1 阶段划分

按依赖顺序，分 5 个阶段。每个阶段独立可测、可提交。前置条件：AS2 迁移完成。

**阶段 WF-1：变量类型系统地基**
- TypedValue / WorkflowContext 重构 / TemplateResolver 升级
- NodeSpec 升级（params -> Map<String,Object> + inputs + outputs）
- PlanNode / PlanJsonParser 适配新结构
- 单测：嵌套取值、数组索引、render 规则
- 出口闸门：编译绿 + 单测全过 + TemplateResolver 嵌套路径单测

**阶段 WF-2：节点 Handler 适配**
- ToolNodeHandler：inputs 绑定 + 类型校验 + 结构化输出（废弃 extract）
- RagNodeHandler：结构化 hits 输出
- AnswerNodeHandler：废弃全量注入，保留 passThrough
- AgentNodeHandler / LlmNodeHandler：output 结构化
- JoinNodeHandler：聚合策略
- EdgeConditionEvaluator：新增算子
- 单测：各 handler I/O 契约
- 出口闸门：编译绿 + handler 单测 + 现有 workflow 标杆 e2e 跑通

**阶段 WF-3：新增节点**
- VariableAssignmentNodeHandler + 注册
- ParameterExtractorNodeHandler + 注册
- WorkflowNodeType 枚举新增
- 单测 + 集成测
- 出口闸门：新节点单测 + 简单 workflow 集成测试

**阶段 WF-4：DB 与种子数据**
- 重写 13-sunshine-workflow-manager.sql（8 标杆新结构）
- WorkflowContextCodec checkpoint 序列化升级
- PlanAnswerPromptAssembler 重写（废弃全量注入逻辑）
- 出口闸门：标杆 workflow 从 DB 加载并执行成功 + live 验收

**阶段 WF-5：Studio 前端**
- Tool 节点结构化 inputs 编辑器
- 变量引用选择器组件
- 新节点编辑器面板
- Join mergeStrategy 选择
- 出口闸门：Studio 可编辑新结构 + 变量引用可视化选择 + live 验收

### 9.2 验收标准

| 维度 | 验收点 | 方式 |
|------|--------|------|
| 类型系统 | {{tool_1.output.data.items[0].id}} 嵌套取值 | 单测 |
| Tool I/O | 两个连续 tool 节点，tool_2 从 tool_1.output 取结构化字段 | live |
| RAG | {{rag_1.hits[0].content}} 取单条检索内容 | live |
| Answer | plan-workflow answer prompt 只含显式引用的上游，非全量注入 | 日志检查 |
| 新节点 | 变量赋值 + 参数提取在标杆 workflow 中使用 | live |
| 并行聚合 | parallel + join(collect) 两个分支输出聚合 | live |
| 边条件 | {{extract_1.result}} eq approved 路由正确 | live |
| DB | 8 标杆新结构 SQL 导入成功 + 执行成功 | 集成测 |
| Studio | 变量引用选择器树形下拉 + 新节点编辑器可用 | live |
| 回归 | verify_workflow_studio_live.py / verify_plan_dag_live.py 全过 | 脚本 |

### 9.3 与 AS2 迁移的关系

本方案在 AS2 迁移完成后启动。两者模块边界清晰：
- AS2 改 agent runtime（HarnessAgent / Middleware / streamEvents）
- 本方案改 workflow 引擎（WorkflowExecutor / NodeHandler / WorkflowContext / Studio）
- 交叉点仅在 AgentNodeHandler（workflow 调 agent runtime），AS2 完成后 AgentNodeHandler 的 agent 调用方式稳定，本方案只改其 I/O 契约，不动 agent 调用逻辑。

## 10. 关键文件索引

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| orchestrator/.../execution/WorkflowContext.java | 重写 | String -> TypedValue，resolvePath 嵌套 |
| orchestrator/.../execution/TemplateResolver.java | 重写 | JSONPath-aware + resolveTyped |
| orchestrator/.../execution/NodeSpec.java | 重写 | params + inputs + outputs |
| orchestrator/.../execution/NodeHandler.java | 修改 | run 签名适配 TypedValue |
| orchestrator/.../execution/handler/ToolNodeHandler.java | 重写 | inputs 绑定 + 结构化输出 |
| orchestrator/.../execution/handler/RagNodeHandler.java | 修改 | 结构化 hits 输出 |
| orchestrator/.../execution/handler/AnswerNodeHandler.java | 修改 | 精确引用（passThrough 保留） |
| orchestrator/.../execution/handler/AgentNodeHandler.java | 修改 | 结构化输出 |
| orchestrator/.../execution/handler/JoinNodeHandler.java | 重写 | 聚合策略 |
| orchestrator/.../execution/handler/VariableAssignmentNodeHandler.java | 新建 | 变量赋值节点 |
| orchestrator/.../execution/handler/ParameterExtractorNodeHandler.java | 新建 | 参数提取节点 |
| orchestrator/.../execution/EdgeConditionEvaluator.java | 修改 | 新增算子 |
| orchestrator/.../plan/PlanAnswerPromptAssembler.java | 重写 | 废弃全量注入 |
| orchestrator/.../plan/PlanJsonParser.java | 修改 | 新结构解析 + 校验 |
| orchestrator/.../plan/PlanNode.java | 修改 | 新增 inputs 字段 |
| common/.../workflow/WorkflowNodeType.java | 修改 | 新增枚举值 |
| orchestrator/.../execution/WorkflowContextCodec.java | 重写 | checkpoint JSON 序列化 |
| docker/mysql/init/13-sunshine-workflow-manager.sql | 重写 | 8 标杆新结构 |
| sunshine-ui/.../workflow/* | 修改 | Studio 结构化编辑器 |
