/** 路由规则编辑表单字段说明（问号 Tooltip） */
const HELP: Record<string, string> = {
  priority: '数值越大优先匹配。同优先级按规则 ID 稳定排序。建议：structural≈100、peer≈90、单域 regex≈10–20。',
  matchType:
    '匹配策略：\n'
    + '· structural：先命中多步句式 patterns，再要求跨域 domainGroups\n'
    + '· peer_phrase：协作句式（仅 patterns）\n'
    + '· regex：正则匹配（受 match=any/all 控制）',
  match: '仅 regex 生效：any=任一正则命中即可；all=全部正则都要命中。',
  patterns:
    '正则表达式，每行一条。\n'
    + 'structural / peer_phrase：任一命中即可进入后续判断。\n'
    + 'regex：结合 match（any/all）判定。',
  domainGroups:
    '仅 structural 使用。每行「域名: 词1, 词2」。\n'
    + '问句命中该域任一词即计该域；再与 minDomainGroups 比较。',
  minDomainGroups: '仅 structural：问句至少命中几个不同域才算跨域。常见值 2。',
  mode: '命中后的执行模式：workflow / plan-workflow / peer-collab / react。',
  workflowId: '仅 mode=workflow 时填写，对应 workflow-manager 中的模板 ID（如 finance-list）。',
  reactPromptId: '仅 mode=react 时可选。绑定「React 提示词」场景，叠加到全局 ReAct overlay 之后。',
  params: '透传给执行计划的参数，每行 key=value。例如 status=pending。reactPromptId 请用上方下拉选择，勿写在此处。',
}

export function routingFieldHelp(fieldId: string): string {
  return HELP[fieldId] ?? ''
}
