/** Workflow Studio 节点/流程配置字段说明（点击 ? 展示） */

const FLOW_HELP: Record<string, string> = {
  displayName: '工作流在路由目录、Chat # 补全与意图步骤中展示的中文名称。',
  description: '写入路由 Catalog，供 L3 意图分类理解本流程适用场景。',
  planReason: '当前版本规划说明，持久化到 plan.reason，便于 Studio 与审计辨识版本用途。',
  catalogExamples: '每行一条用户问法示例，用于 L2/L3 路由命中与 Chat # 补全。Chat 中输入 #{workflowId} 可强制绑定本流程（如 #finance-list）。',
  catalogIntentAfter: '意图步骤完成后的摘要模板。占位符：{query} 用户问句、{displayName} 流程名、{workflowId} 流程 ID。',
  answerPrompt: '终态 answer 节点发给 LLM 的 prompt。可用 {{节点-id.output}} / {{节点-id.answer}} 引用上游结果。',
}

const NODE_HELP: Record<string, string> = {
  nodeId: '节点在 Plan DAG 与 execution_plan 中的唯一标识，由 Studio 自动生成。',
  displayName: '时间线主行、DAG 节点卡片上展示的中文名称。',
  topK: 'RAG 节点检索返回的最大片段数（1–20）。',
  ragQuery: 'RAG 检索主问句；常用 {{start.userQuery}} 透传用户原问。',
  ragContext: '追加到检索 query 的上游材料，支持 {{node-id.output}}。多段可用换行拼接。',
  kbId: '指定检索的知识库；选「会话默认」则使用 Chat 会话当前选中的知识库。',
  tool: '工具 Catalog ID（sdk__* / mcp__*），须已在 /tools 启用。',
  toolExtra: '除 tool 外的业务入参，每行 key=value。支持 {{node-id.field}} 模板，如 status=pending。',
  toolParam: '按工具 Schema 声明的入参；支持 {{start.userQuery}}、{{上游节点.output}} 等模板表达式。',
  nodeInputs: '本节点执行时消费的输入；支持 {{start.userQuery}}、{{上游节点-id.output}} 等模板表达式。',
  nodeOutputs: '下游节点可在 context / prompt 中引用的输出变量；点击右侧复制按钮复制 {{节点-id.字段}}。\n\n声明要提取的字段；LLM 输出 JSON 后每字段可被下游 {{节点-id.字段名}} 引用。',
  skill: '子 Agent 加载的 Skill Catalog ID，叠加 Skill 指令与 overlay。',
  query: '子 Agent 用户正文；常用 {{start.userQuery}} 透传用户原问。',
  context: '注入子 Agent 的上游材料，支持 {{node-id.output}}。多段可用换行拼接。',
  agentKbId: '子 Agent 内置 search_knowledge 检索的知识库；选「会话默认」则使用 Chat 会话知识库。',
  agentTools: '在内置 search_knowledge 之外追加的业务工具（多选）。留空则仅 RAG + 注入 context。',
  maxIters: 'ReAct 最大推理-行动轮次（1–12）。每轮可含一次工具调用；轮次用尽会中止分析。',
  systemOverlay: '追加到子 Agent system 的节点级说明，用于约束分析范围与输出格式。',
  joinTopology: '至少两条路线汇入、一条路线流出。分叉请用「并行分叉」，不要在普通步骤上直接拉出多条线。',
  joinMergeStrategy: '多路汇入后的合并方式：collect 收集为数组（默认）、merge 浅合并为对象、first 取第一个非空、last 取最后一个非空。',
  parallelGatewayTopology: '至少分出两条可同时进行的路线；各分支完成后应汇入「并行汇总」。',
  exclusiveGatewayTopology: '至少分出两条互斥路线；配置出边条件，并指定恰好一条默认分支。左值随上游自动填入。运行时按顺序求值，命中即走，否则走默认。',
  loopTopology: '循环容器（do-while）：首轮必进框内线性 rag/tool/agent；跑完后「继续条件」为真再进下一轮；maxIterations 硬顶 1–5；超限策略 fail_fast / exit / fallback_react。',
  variableAssignment: '将上游变量或字面量赋值给命名变量，供下游以 {{节点-id.变量名}} 引用。',
}

const RETRY_HELP: Record<string, string> = {
  maxAttempts: '节点失败后的最大执行次数（含首次），1 表示不重试。',
  backoffMs: '重试前等待毫秒数，用于退避。',
  onFailure: '耗尽重试后的策略：continue 继续下游；fail_fast 终止流程；skip 跳过并留空输出；fallback_react 降级主 ReAct。',
}

export function workflowFlowFieldHelp(field: keyof typeof FLOW_HELP | string): string {
  return FLOW_HELP[field] ?? ''
}

export function workflowNodeFieldHelp(field: keyof typeof NODE_HELP | string): string {
  return NODE_HELP[field] ?? ''
}

export function workflowRetryFieldHelp(field: keyof typeof RETRY_HELP | string): string {
  return RETRY_HELP[field] ?? ''
}
