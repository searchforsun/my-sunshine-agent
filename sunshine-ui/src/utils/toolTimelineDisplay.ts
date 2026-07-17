/** 工具时间线摘要模板展示（SDK/MCP Catalog） */

export function formatTimelineTemplateLabel(template?: string | null): string {
  const t = template?.trim()
  return t ? t : '默认'
}

export function formatTimelineExtractHint(extract?: string | null): string | null {
  const e = extract?.trim()
  return e ? e : null
}
