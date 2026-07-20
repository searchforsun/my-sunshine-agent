/** 路由规则编辑表单字段说明（问号 Tooltip） */
const HELP: Record<string, string> = {
  priority: '数值越大优先匹配。同优先级按规则 ID 稳定排序。建议：多步跨域≈100、协作句式≈90、单域正则≈10–20。',
  matchType:
    '匹配策略：\n'
    + '· 多步跨域：先命中句式规则，再要求跨多个业务主题\n'
    + '· 协作句式：仅匹配协作相关句式\n'
    + '· 正则匹配：按正则判定（受「命中方式」控制）',
  match: '仅「正则匹配」生效：任一命中=有一条规则即可；全部命中=每条规则都要命中。',
  patterns:
    '句式/正则规则列表，每条一条。\n'
    + '多步跨域 / 协作句式：任一命中即可进入后续判断。\n'
    + '正则匹配：结合「命中方式」判定。',
  domainGroups:
    '仅「多步跨域」使用。把问句可能涉及的业务拆成多个「主题」。\n'
    + '主题名可自定（如「制度知识」「财务单据」），不是系统预置。\n'
    + '问句命中某主题的任一词，该主题即算命中；再与「至少命中主题数」比较。',
  minDomainGroups: '仅「多步跨域」：问句至少命中几个不同业务主题才算跨域。常见值 2。',
  mode: '命中后的执行模式：工作流 / 动态规划 / 多专家协作 / 自主推理。',
  workflowId: '仅「工作流」时填写，对应工作流模板 ID（如 finance-list）。',
  reactPromptId: '仅「自主推理」时可选。绑定 React 提示词场景，叠加到全局 overlay 之后。',
  params: '透传给执行计划的参数，每行 key=value。例如 status=pending。React 场景请用上方下拉选择，勿写在此处。',
}

export function routingFieldHelp(fieldId: string): string {
  return HELP[fieldId] ?? ''
}
