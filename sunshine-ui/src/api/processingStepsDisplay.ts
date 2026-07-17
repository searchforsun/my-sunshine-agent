/** 时间线步骤展示：摘要、展开区、耗时 */
import type { ProcessingStep, StepLifecycle } from './processingSteps'

/** 4.5 沙箱六工具 — 主行摘要常驻；展开由 OperationCard 嵌入高亮面板（无 code 边框） */
export const SANDBOX_TOOL_IDS = [
  'sandbox__read',
  'sandbox__write',
  'sandbox__edit',
  'sandbox__glob',
  'sandbox__grep',
  'sandbox__exec',
] as const

export type SandboxToolId = (typeof SANDBOX_TOOL_IDS)[number]

export function catalogToolIdFromStepId(stepId: string): string | undefined {
  if (!stepId?.startsWith('tool-')) return undefined
  const raw = stepId.slice('tool-'.length)
  const toolId = raw.split('@')[0]?.trim()
  return toolId || undefined
}

export function isSandboxToolStep(step: { id: string; phase?: string }): boolean {
  const toolId = catalogToolIdFromStepId(step.id)
  if (!toolId) return false
  return (SANDBOX_TOOL_IDS as readonly string[]).includes(toolId)
}

export function isSandboxExecStep(step: { id: string }): boolean {
  return catalogToolIdFromStepId(step.id) === 'sandbox__exec'
}

/** 从 after/active 解析 exec 命令（「cmd」/「… · cmd」/「正在执行 cmd」） */
export function extractSandboxExecCommand(step: {
  summary?: { after?: string; active?: string }
}): string | undefined {
  const after = step.summary?.after?.trim() || ''
  const afterMatch = after.match(/(?:完成\s*)?[·•]\s*(.+)$/s)
  if (afterMatch?.[1]?.trim()) return afterMatch[1].trim()
  if (after) {
    const stripped = after
      .replace(/^执行命令(?:完成)?\s*/u, '')
      .replace(/^[·•]\s*/, '')
      .trim()
    if (stripped) return stripped
  }
  const active = step.summary?.active?.trim() || ''
  const activeMatch = active.match(/正在执行\s+(.+)$/s)
  if (activeMatch?.[1]?.trim()) return activeMatch[1].trim()
  return undefined
}

/** 从沙箱工具 after 文案解析 /workspace 或 /skills 路径 */
export function extractSandboxWorkspacePath(summary?: string): string | undefined {
  if (!summary?.trim()) return undefined
  const m = summary.match(/(\/(?:workspace|skills)\/[^\s，,）)]+)/)
  return m?.[1]
}

/** 路径末段文件名（主行展示用） */
export function sandboxBasename(path: string): string {
  const normalized = path.replace(/\\/g, '/').replace(/\/+$/, '')
  const i = normalized.lastIndexOf('/')
  return i >= 0 ? normalized.slice(i + 1) : normalized
}

/** 去掉 /skills|/workspace 前缀的相对路径（展开列表展示用） */
export function sandboxDisplayPath(path: string): string {
  const normalized = path.replace(/\\/g, '/').replace(/\/+$/, '')
  if (normalized.startsWith('/skills/')) return normalized.slice('/skills/'.length)
  if (normalized.startsWith('/workspace/')) return normalized.slice('/workspace/'.length)
  if (normalized === '/skills' || normalized === '/workspace') return ''
  return sandboxBasename(normalized) || normalized
}

/** 文件路径 → 文件名；目录/jail 根保留绝对路径（glob 搜索根） */
function formatSandboxPathForHeader(path: string): string {
  const norm = path.replace(/\\/g, '/').replace(/\/+$/, '')
  if (norm === '/skills' || norm === '/workspace') return norm
  const base = sandboxBasename(norm)
  // 无扩展名视为目录搜索根，保留绝对路径
  if (!/\.[^./]+$/.test(base)) return norm
  return base
}

/**
 * 沙箱主行展示：去掉与标签重复的工具名及前导 ·；旧括号搜索根 → · /path；文件路径 → 文件名。
 */
export function formatSandboxHeaderSummary(text: string): string {
  if (!text?.trim()) return ''
  let s = text.trim()
  // 标签已有「调用工具 xxx」，剥掉摘要里重复的工具名（含旧「完成」）
  s = s.replace(/^(读文件|写文件|编辑文件|查找文件|搜索内容|执行命令)(?:完成)?\s*/u, '')
  // 去掉前导 ·（标签与摘要之间由 UI 空格分隔）
  s = s.replace(/^[·•]\s*/, '')
  // （根 /skills...）/（/skills...）→ · /skills...
  s = s.replace(/（根\s*(\/(?:skills|workspace)(?:\/[^）]*)?)）/g, ' · $1')
  s = s.replace(/（(\/(?:skills|workspace)(?:\/[^）]*)?)）/g, ' · $1')
  // 行首或 · 后的容器路径：文件 → 文件名；目录/jail → 保留绝对路径
  s = s.replace(
    /(^|[·•]\s*)(\/(?:workspace|skills)(?:\/[^\s，,（）)]*)?)/g,
    (_m, pre: string, p: string) => {
      const fmt = formatSandboxPathForHeader(p)
      if (!pre || pre === '') return fmt
      return `· ${fmt}`
    },
  )
  // 行首若因括号转换留下 ·，再剥一次
  s = s.replace(/^[·•]\s*/, '')
  return s.replace(/\s{2,}/g, ' ').replace(/\s+·\s+/g, ' · ').replace(/\s+$/g, '').trim()
}

/** 去掉主行末尾搜索根（· /skills… 或旧括号） */
export function stripSandboxSearchRootSuffix(text: string): string {
  if (!text?.trim()) return ''
  return text
    .replace(/[·•]\s*\/(?:workspace|skills)(?:\/[^\s·•]*)?\s*$/g, '')
    .replace(/（(?:根\s*)?\/(?:workspace|skills)(?:\/[^）]*)?）/g, '')
    .replace(/（[^）]+）\s*$/g, '')
    .replace(/\s{2,}/g, ' ')
    .replace(/\s+·\s+/g, ' · ')
    .trim()
}

/** 解析 glob 搜索根：末尾 · /skills… 或旧 （/skills…） */
export function extractSandboxSearchRoot(summary?: string): string | undefined {
  if (!summary?.trim()) return undefined
  const dot = summary.match(/[·•]\s*(\/(?:workspace|skills)(?:\/[^\s·•]*)?)\s*$/)
  if (dot?.[1]) return dot[1]
  const paren = summary.match(/（(?:根\s*)?(\/(?:workspace|skills)(?:\/[^）\s]*)?)）/)
  return paren?.[1]
}

/** 结果路径均在同一 jail 时推断搜索根（工具未传 path 时兜底） */
export function inferSandboxSearchRoot(paths: string[]): string | undefined {
  if (!paths.length) return undefined
  const skills = paths.every(p => p === '/skills' || p.startsWith('/skills/'))
  if (skills) return '/skills'
  const ws = paths.every(p => p === '/workspace' || p.startsWith('/workspace/'))
  if (ws) return '/workspace'
  return undefined
}

/** 相对搜索根的展示名；无 root 时回落 jail 相对路径 */
export function sandboxPathRelativeToRoot(fullPath: string, searchRoot?: string): string {
  const full = fullPath.replace(/\\/g, '/').replace(/\/+$/, '')
  if (!searchRoot?.trim()) return sandboxDisplayPath(full)
  const root = searchRoot.replace(/\\/g, '/').replace(/\/+$/, '')
  if (full === root) return '.'
  if (full.startsWith(`${root}/`)) return full.slice(root.length + 1)
  return sandboxDisplayPath(full)
}

/** 点击工作区聚焦：优先 after/active 完整路径，否则取 detail 首条路径行 */
export function resolveSandboxFocusPath(step: {
  summary?: { after?: string; active?: string }
  detail?: string
}): string | undefined {
  const fromAfter = extractSandboxWorkspacePath(step.summary?.after)
  if (fromAfter) return fromAfter
  const fromActive = extractSandboxWorkspacePath(step.summary?.active)
  if (fromActive) return fromActive
  const detail = step.detail?.trim() || ''
  for (const line of detail.split('\n')) {
    const t = line.trim()
    if (!t) continue
    if (/^\/(?:workspace|skills)\//.test(t)) return t
    const embedded = extractSandboxWorkspacePath(t)
    if (embedded) return embedded
  }
  return undefined
}

/** glob 等：detail 中的容器路径列表 → 相对搜索根展示名 + 完整 path（点击跳转） */
export function parseSandboxPathList(
  raw: string,
  searchRoot?: string,
): { path: string; name: string }[] {
  if (!raw?.trim()) return []
  const out: { path: string; name: string }[] = []
  for (const line of raw.split('\n')) {
    const t = line.trim()
    if (!t || !/^\/(?:workspace|skills)\//.test(t)) continue
    out.push({ path: t, name: sandboxPathRelativeToRoot(t, searchRoot) })
  }
  return out
}

export function isSandboxPathListOutput(step: { id: string }, raw: string): boolean {
  const toolId = catalogToolIdFromStepId(step.id)
  if (toolId === 'sandbox__glob') return true
  const paths = parseSandboxPathList(raw)
  if (!paths.length) return false
  const lines = raw.split('\n').map(l => l.trim()).filter(Boolean)
  return lines.length > 0 && paths.length === lines.length
}

export interface RewriteDetailView {
  from: string
  to: string
  targetLabel: string
  latencyText?: string
}

export function formatStepLabel(step: ProcessingStep): string {
  if (step.label?.trim()) {
    return step.label
  }
  return step.id
}

export function formatDuration(ms?: number): string {
  if (ms == null) return ''
  if (ms < 1) return '<1ms'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
}

export function stepLifecycle(step: ProcessingStep): StepLifecycle {
  return (step.lifecycle ?? 'pending') as StepLifecycle
}

export function formatStepMetadata(step: ProcessingStep): string {
  const m = step.metadata
  if (!m) return ''
  const parts: string[] = []
  if (typeof m.hitCount === 'number') {
    parts.push(`命中 ${m.hitCount} 条`)
  }
  const sources = m.sources?.filter(s => s.trim())
  if (sources?.length) {
    parts.push(`来源：${sources.join('、')}`)
  }
  return parts.join('，')
}

export function formatRewriteLatency(latencyMs: number): string {
  return formatDuration(latencyMs) || '<1ms'
}

export function formatRewriteMetadata(step: ProcessingStep): string {
  const m = step.metadata
  if (!m?.rewriteApplied || !m.rewriteFrom || !m.rewriteTo) return ''
  const targetLabel = m.rewriteScenario === 'hyde' ? '参考文档' : '优化后'
  const latency = typeof m.rewriteLatencyMs === 'number'
    ? `\n${formatRewriteLatency(m.rewriteLatencyMs)}`
    : ''
  const body = `原问题：${m.rewriteFrom}\n${targetLabel}：${m.rewriteTo}${latency}`
  if (m.rewriteScenarioLabel?.trim()) {
    return `${m.rewriteScenarioLabel.trim()}\n${body}`
  }
  return body
}

export function resolveRewriteDetail(step: ProcessingStep): RewriteDetailView | undefined {
  const m = step.metadata
  if (m?.rewriteInDetail) return undefined
  if (!m?.rewriteApplied || !m.rewriteFrom || !m.rewriteTo) return undefined
  const targetLabel = m.rewriteScenario === 'hyde' ? '参考文档' : '优化后'
  const latencyText = typeof m.rewriteLatencyMs === 'number'
    ? formatRewriteLatency(m.rewriteLatencyMs)
    : undefined
  return {
    from: m.rewriteFrom,
    to: m.rewriteTo,
    targetLabel,
    latencyText,
  }
}

export const STEP_HEADER_PREVIEW_MAX = 42

function stripPlanMetaText(text: string): string {
  const trimmed = text.trim()
  for (const segment of trimmed.split('|')) {
    const eq = segment.indexOf('=')
    if (eq > 0 && segment.slice(0, eq).trim() === 'chain') {
      return segment.slice(eq + 1).trim()
    }
  }
  return trimmed
}

function truncateStepPreview(text: string, max = STEP_HEADER_PREVIEW_MAX): string {
  if (text.length <= max) return text
  return `${text.slice(0, max)}…`
}

export function isWorkflowAnswerStep(step: ProcessingStep): boolean {
  return step.id === 'node-answer'
}

function resolveExpertSpeakPreview(step: ProcessingStep): string {
  const result = step.result?.trim()
  if (!result) return ''
  const line = extractFirstProseLine(result)
  if (line) return line
  return truncateStepPreview(result.replace(/\s+/g, ' '))
}

export function resolveStepSummaryFull(step: ProcessingStep): string {
  const lifecycle = stepLifecycle(step)
  const title = formatStepLabel(step)
  if (step.phase === 'expert') {
    const preview = resolveExpertSpeakPreview(step)
    if (preview) return preview
  }
  let header = ''
  if (lifecycle === 'running') {
    if (step.phase === 'expert') {
      const preview = resolveExpertSpeakPreview(step)
      if (preview) header = preview
    }
    if (!header) header = step.summary?.active?.trim() || ''
  } else if (lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'skipped') {
    header = step.summary?.after?.trim()
      || formatStepMetadata(step)
      || (!isWorkflowAnswerStep(step) && step.result?.trim())
      || step.detail?.trim()
      || ''
    if (step.phase === 'plan' && header) {
      header = stripPlanMetaText(header)
    }
  } else {
    header = step.summary?.before?.trim() || ''
  }
  if (!header || header === title) {
    return ''
  }
  return header
}

export function resolveStepHeaderText(step: ProcessingStep): string {
  const full = resolveStepSummaryFull(step)
  const oneLine = full.replace(/\s+/g, ' ').trim()
  if (!isSandboxToolStep(step)) {
    return truncateStepPreview(oneLine)
  }
  let display = formatSandboxHeaderSummary(oneLine)
  const toolId = catalogToolIdFromStepId(step.id)
  if (toolId === 'sandbox__grep') {
    display = stripSandboxSearchRootSuffix(display)
  }
  // glob：缺搜索根时用结果路径推断 jail，保证主行能与列表拼绝对路径（grep 不夹）
  if (toolId === 'sandbox__glob'
    && !extractSandboxSearchRoot(display)
    && step.detail?.trim()) {
    const paths = parseSandboxPathList(step.detail).map(e => e.path)
    const root = inferSandboxSearchRoot(paths)
    if (root) {
      display = `${display} · ${root}`
    }
  }
  return truncateStepPreview(display)
}

function extractFirstProseLine(text: string): string {
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#') || /^\|/.test(line)) continue
    if (/^[-*_]{3,}$/.test(line)) continue
    const plain = line.replace(/\*\*|__|`/g, '').replace(/^>\s*/, '').trim()
    if (plain.length >= 8 && /[\u4e00-\u9fff]/.test(plain)) {
      return plain.replace(/\s+/g, ' ')
    }
  }
  return ''
}

/** 展开区 lead：保留换行，供 StaticMarkdown 渲染（主行预览仍用 resolveStepHeaderText 单行截断） */
export function resolveStepExpandLead(step: ProcessingStep): string {
  const lifecycle = stepLifecycle(step)
  if (lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'skipped') {
    let text = step.summary?.after?.trim() || resolveStepSummaryFull(step)
    if (step.phase === 'plan' && text) {
      text = stripPlanMetaText(text)
    }
    return text
  }
  return resolveStepSummaryFull(step)
}

export function resolveStepExpandSummary(step: ProcessingStep): string {
  const lifecycle = stepLifecycle(step)
  let oneLine = ''
  if (lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'skipped') {
    oneLine = (step.summary?.after?.trim() || resolveStepSummaryFull(step)).replace(/\s+/g, ' ').trim()
    if (step.phase === 'plan' && oneLine) {
      oneLine = stripPlanMetaText(oneLine)
    }
  } else {
    oneLine = resolveStepSummaryFull(step).replace(/\s+/g, ' ').trim()
  }
  if (oneLine.endsWith('…') && step.detail?.trim()) {
    const fromDetail = extractFirstProseLine(step.detail)
    const prefix = oneLine.slice(0, -1).trim()
    if (fromDetail && (fromDetail.startsWith(prefix) || prefix.length >= 12 && fromDetail.startsWith(prefix.slice(0, 12)))) {
      return fromDetail
    }
  }
  return oneLine
}

/** 展开区内层正文（detail / rewrite / result），与 summary.after 分列 */
export function resolveStepExpandInner(step: ProcessingStep): string {
  if (isWorkflowAnswerStep(step)) {
    return ''
  }
  if (step.phase === 'expert') {
    return step.result?.trim() || ''
  }
  if (isSandboxToolStep(step)) {
    // 沙箱展开由 OperationCard 嵌入面板渲染（无 markdown code 边框）
    return resolveSandboxExpandRaw(step)
  }
  const detail = step.detail?.trim()
  if (detail) return detail
  const rewrite = formatRewriteMetadata(step)
  if (rewrite) return rewrite
  // 工具步无 detail 即无展开正文；result 常为历史 after 副本，勿当作 inner
  if (step.phase === 'tool' || step.id.startsWith('tool-') || step.id.startsWith('rag')) {
    return ''
  }
  return step.result?.trim() || ''
}

function resolveSandboxExpandRaw(step: ProcessingStep): string {
  const detail = step.detail?.trim()
  if (detail) return detail
  const result = step.result?.trim()
  if (result && result !== step.summary?.after?.trim()) {
    return result
  }
  return step.output?.trim() || ''
}

/**
 * 展开区两块互斥：有 inner 只展示 inner，否则展示 after 摘要。
 * 折叠主行仍用 summary.after 预览（resolveStepHeaderText）。
 */
export function resolveStepExpandPanels(step: ProcessingStep): { lead: string; body: string } {
  const inner = resolveStepExpandInner(step)
  if (inner) {
    return { lead: '', body: inner }
  }
  return { lead: resolveStepExpandLead(step), body: '' }
}

export function resolveStepExpandBody(step: ProcessingStep): string {
  return resolveStepExpandPanels(step).body
}

export function parseLoadedSkillLabel(text?: string): string | undefined {
  if (!text?.trim()) return undefined
  const match = text.trim().match(/^已加载技能：([^\n]+)/)
  const label = match?.[1]?.trim()
  return label || undefined
}

export function stripLoadedSkillPrefix(text?: string): string {
  if (!text?.trim()) return ''
  return text.replace(/^已加载技能：[^\n]+\n\n?/, '').trim()
}

/** 主行摘要是否被截断（带 …），展开后可看全文 */
export function isStepSummaryTruncated(step: ProcessingStep): boolean {
  const header = resolveStepHeaderText(step)
  if (!header) return false
  if (header.endsWith('…')) return true
  const full = resolveStepSummaryFull(step).replace(/\s+/g, ' ').trim()
  return !!full && full.length > STEP_HEADER_PREVIEW_MAX
}

export function shouldShiftSummaryOnExpand(step: ProcessingStep): boolean {
  // 沙箱：路径等信息留在主行，不随展开下移到详情区
  if (isSandboxToolStep(step)) return false
  if (resolveStepExpandInner(step)) return true
  return isStepSummaryTruncated(step)
}

export function hasExpandableContent(step: ProcessingStep): boolean {
  if (step.phase === 'tasks' && (step.metadata?.tasks?.length ?? 0) > 0) {
    return false
  }
  if (step.phase === 'peer-collab' || step.id === 'peer-collab') {
    return false
  }
  // loop 框内 agent：嵌套 think/正文可展开
  if (step.id.startsWith('i') && (step.subSteps?.length || step.contentBlocks?.length)) {
    return true
  }
  if (isSandboxExecStep(step) && extractSandboxExecCommand(step)) {
    return true
  }
  if (resolveStepExpandInner(step)) return true
  if (isStepSummaryTruncated(step)) return true
  if (isWorkflowAnswerStep(step)) {
    return !!formatRewriteMetadata(step)
      || !!step.reasoning?.trim()
      || !!step.output?.trim()
  }
  if (formatRewriteMetadata(step)) return true
  if (step.reasoning?.trim()) return true
  if (step.output?.trim()) return true
  return false
}

export function resolveStepDurationMs(step: ProcessingStep): number | undefined {
  if (step.durationMs != null && step.durationMs >= 0) {
    return step.durationMs
  }
  if (step.startedAt != null && step.endedAt != null && step.endedAt >= step.startedAt) {
    return step.endedAt - step.startedAt
  }
  return undefined
}

export function totalDuration(steps: ProcessingStep[]): number {
  return steps
    .filter(s => s.lifecycle === 'done')
    .reduce((sum, s) => sum + (resolveStepDurationMs(s) ?? 0), 0)
}

export function summarizeSteps(steps: ProcessingStep[]): string {
  const parts = steps
    .filter(s => s.lifecycle === 'done')
    .map(s => {
      if (s.summary?.after) return s.summary.after
      const title = formatStepLabel(s)
      return s.detail ? `${title} · ${s.detail}` : title
    })
    .filter(Boolean)
  const total = totalDuration(steps)
  if (total > 0) parts.push(formatDuration(total))
  return parts.join(' · ')
}
