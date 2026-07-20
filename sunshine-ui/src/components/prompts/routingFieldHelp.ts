/** 路由规则编辑表单字段说明（问号 Tooltip） */
const HELP: Record<string, string> = {
  priority: '数值越大优先匹配。同优先级按规则 ID 稳定排序。建议：多步跨域≈100、协作句式≈90、单域正则≈10–20。',
  matchType:
    '匹配策略：\n'
    + '· 多步跨域：先命中多步句式，再要求跨域关键词组\n'
    + '· 协作句式：仅匹配协作相关句式\n'
    + '· 正则匹配：按正则判定（受「命中方式」控制）',
  match: '仅「正则匹配」生效：任一命中=有一条正则即可；全部命中=每条正则都要命中。',
  patterns:
    '正则表达式，每行一条。\n'
    + '多步跨域 / 协作句式：任一命中即可进入后续判断。\n'
    + '正则匹配：结合「命中方式」判定。',
  domainGroups:
    '仅「多步跨域」使用。每行「域名: 词1, 词2」。\n'
    + '问句命中该域任一词即计该域；再与「最少命中域数」比较。',
  minDomainGroups: '仅「多步跨域」：问句至少命中几个不同域才算跨域。常见值 2。',
  mode: '命中后的执行模式：静态 Workflow / 动态规划 / 多专家协作 / 自主推理。',
  workflowId: '仅「静态 Workflow」时填写，对应工作流模板 ID（如 finance-list）。',
  reactPromptId: '仅「自主推理」时可选。绑定 React 提示词场景，叠加到全局 overlay 之后。',
  params: '透传给执行计划的参数，每行 key=value。例如 status=pending。React 场景请用上方下拉选择，勿写在此处。',
}

export function routingFieldHelp(fieldId: string): string {
  return HELP[fieldId] ?? ''
}
