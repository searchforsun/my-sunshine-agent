/** 会话形态（chat|task）与配置条目 kind（chat|task|all|缺省=all）是否匹配：仅命中同 kind 或 all */
export function matchesSessionKind(sessionKind: string, itemKind?: string | null): boolean {
  if (!itemKind || itemKind === 'all') return true
  return itemKind === sessionKind
}
