/** 时间线步骤展示：摘要、展开区、耗时 */
import type { ProcessingStep, StepLifecycle } from './processingSteps'
import { linesFromEditDiffMeta } from './sandboxEditDiff'

export function catalogToolIdFromStepId(stepId: string): string | undefined {
  if (!stepId?.startsWith('tool-')) return undefined
  const raw = stepId.slice('tool-'.length)
  const toolId = raw.split('@')[0]?.trim()
  return toolId || undefined
}

/** catalog id 前缀 sandbox__*（勿维护六工具硬编码名单） */
export function isSandboxToolStep(step: { id: string; phase?: string }): boolean {
  const toolId = catalogToolIdFromStepId(step.id)
  return !!toolId?.startsWith('sandbox__')
}

/** sandbox 工具按用途细分：查看（read/glob/grep）/ 修改（write/edit）/ 查找（webfetch/websearch）/ 执行（其余） */
export type SandboxToolKind = 'view' | 'edit' | 'fetch' | 'exec'

export function sandboxToolKind(toolId: string | undefined): SandboxToolKind | null {
  if (!toolId?.startsWith('sandbox__')) return null
  switch (toolId) {
    case 'sandbox__read':
    case 'sandbox__glob':
    case 'sandbox__grep':
      return 'view'
    case 'sandbox__write':
    case 'sandbox__edit':
      return 'edit'
    case 'sandbox__webfetch':
    case 'sandbox__websearch':
      return 'fetch'
    default:
      return 'exec'
  }
}

export function isSandboxExecStep(step: { id: string }): boolean {
  return catalogToolIdFromStepId(step.id) === 'sandbox__exec'
}

export function isSandboxReadStep(step: { id: string }): boolean {
  return catalogToolIdFromStepId(step.id) === 'sandbox__read'
}

/** 可 hover 取消：跟 SSE metadata.cancellable（Nacos cancellable-tools） */
export function isCancellableSandboxTool(step: {
  id: string
  metadata?: { cancellable?: boolean }
}): boolean {
  return step.metadata?.cancellable === true
}

function isSandboxCancelLifecycle(lifecycle?: string): boolean {
  return lifecycle === 'paused' || lifecycle === 'terminated'
}

/** 从 after/active/detail 解析 exec 命令；取消终态只信 detail（勿按中文 after 门闩） */
export function extractSandboxExecCommand(step: {
  lifecycle?: string
  summary?: { after?: string; active?: string }
  detail?: string
}): string | undefined {
  const cancelled = isSandboxCancelLifecycle(step.lifecycle)
  const after = step.summary?.after?.trim() || ''
  if (after && !cancelled) {
    const afterMatch = after.match(/(?:完成\s*)?[·•]\s*(.+)$/s)
    if (afterMatch?.[1]?.trim()) return afterMatch[1].trim()
    const stripped = after
      .replace(/^执行命令(?:完成)?\s*/u, '')
      .replace(/^[·•]\s*/, '')
      .trim()
    if (stripped) return stripped
  }
  const active = step.summary?.active?.trim() || ''
  const activeMatch = active.match(/正在执行\s+(.+)$/s)
  if (activeMatch?.[1]?.trim()) return activeMatch[1].trim()
  const detail = step.detail?.trim() || ''
  if (detail) {
    const fromDetail = detail.match(/正在执行\s+(.+)$/s)
    if (fromDetail?.[1]?.trim()) return fromDetail[1].trim()
    // 取消终态：后端把 command 原样写入 detail（无 stdout）
    if (cancelled) {
      return detail
    }
    // HITL 等待确认（running 未完成）：command 经 HITL expandDetail 写入 detail，
    // 此时主行须展示命令便于用户决策；done 态 detail 为 stdout 输出，不能当命令
    if (step.lifecycle === 'running' && !detail.includes('等待用户确认')) {
      return detail
    }
  }
  return undefined
}

/** 将 exec 命令压缩为命令头摘要：按 | || && ; 切分（引号内不切），每段取首个 token，操作符保留。
 * 例：find ... | head -120 → find | head；ls -la && cat f → ls && cat */
export function formatExecCommandHeader(command: string): string {
  const segments: { text: string; op: string }[] = []
  let buf = ''
  let quote: string | null = null
  const ops = ['&&', '||', '|', ';']
  let i = 0
  while (i < command.length) {
    const ch = command[i]
    if (quote) {
      buf += ch
      if (ch === quote) quote = null
      i++
      continue
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      quote = ch
      buf += ch
      i++
      continue
    }
    const matched = ops.find(op => command.startsWith(op, i))
    if (matched) {
      segments.push({ text: buf, op: matched })
      buf = ''
      i += matched.length
      continue
    }
    buf += ch
    i++
  }
  segments.push({ text: buf, op: '' })

  const heads: string[] = []
  for (const seg of segments) {
    const firstToken = seg.text.trim().split(/\s+/)[0] ?? ''
    if (firstToken) heads.push(firstToken)
    if (seg.op) heads.push(seg.op)
  }
  return heads.join(' ')
}

/** exec 步主行命令头文本：think_summary 摘要前置 + 命令头（如「提交一下改动 git | grep」）；无摘要仅命令头 */
export function formatExecCommandHeaderText(command: string, summary?: string): string {
  const head = formatExecCommandHeader(command)
  const s = summary?.trim()
  return s ? `${s} ${head}` : head
}

/** 从沙箱工具 after 文案解析 /workspace 或 /skills 路径（legacy；优先 metadata.sandboxPath） */
export function extractSandboxWorkspacePath(summary?: string): string | undefined {
  if (!summary?.trim()) return undefined
  const m = summary.match(/(\/(?:workspace|skills)\/[^\s，,）)]+)/)
  return m?.[1]
}

/** 路径末段文件名 */
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

/** 解析 glob 搜索根：末尾 · /skills… */
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

/** 点击工作区聚焦：metadata.sandboxPath 优先，其次 after/active/detail */
export function resolveSandboxFocusPath(step: {
  summary?: { after?: string; active?: string }
  detail?: string
  metadata?: { sandboxPath?: string }
}): string | undefined {
  const fromMeta = step.metadata?.sandboxPath?.trim()
  if (fromMeta) return fromMeta
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

/** read 步骤行范围：从 summary.after「读文件 xxx.py L20-28」解析 { start, end }；无行范围返回 undefined */
export function resolveSandboxReadLineRange(step: {
  summary?: { after?: string }
}): { start: number; end: number } | undefined {
  const after = step.summary?.after?.trim() || ''
  const m = after.match(/\bL(\d+)-(\d+)\b/)
  if (!m) return undefined
  const start = Number(m[1])
  const end = Number(m[2])
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < 1 || end < start) return undefined
  return { start, end }
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

export function resolveStepSummaryFull(step: ProcessingStep): string {
  const lifecycle = stepLifecycle(step)
  const title = formatStepLabel(step)
  let header = ''
  if (lifecycle === 'running') {
    header = step.summary?.active?.trim() || ''
  } else if (
    lifecycle === 'done'
    || lifecycle === 'error'
    || lifecycle === 'skipped'
    || lifecycle === 'paused'
    || lifecycle === 'terminated'
  ) {
    // paused/terminated：只信 after（后端必下发）；勿回退 active
    header = step.summary?.after?.trim()
      || formatStepMetadata(step)
      || (!isWorkflowAnswerStep(step) && step.result?.trim())
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
  return truncateStepPreview(oneLine)
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
  if (
    lifecycle === 'done'
    || lifecycle === 'error'
    || lifecycle === 'skipped'
    || lifecycle === 'paused'
    || lifecycle === 'terminated'
  ) {
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
  if (
    lifecycle === 'done'
    || lifecycle === 'error'
    || lifecycle === 'skipped'
    || lifecycle === 'paused'
    || lifecycle === 'terminated'
  ) {
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
  const lifecycle = stepLifecycle(step)
  // 取消：detail 存的是命令/pattern 快照，不是 stdout；由 extractSandboxExecCommand 展示
  if (
    isSandboxExecStep(step)
    && (lifecycle === 'paused' || lifecycle === 'terminated')
  ) {
    return ''
  }
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
  if (step.phase === 'subagent' || step.id.startsWith('subagent-')) {
    return false
  }
  // loop 框内 agent：嵌套 think/正文可展开
  if (step.id.startsWith('i') && (step.subSteps?.length || step.contentBlocks?.length)) {
    return true
  }
  if (isSandboxExecStep(step) && extractSandboxExecCommand(step)) {
    return true
  }
  // 读文件：内容在工作区预览，不重复渲染展开（点击卡片定位到文件即可）
  if (isSandboxReadStep(step)) {
    return false
  }
  if (linesFromEditDiffMeta(step.metadata?.editDiff)?.length) {
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

export function formatElapsedClock(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return ''
  const totalSec = Math.floor(ms / 1000)
  if (totalSec < 60) return `${totalSec}s`
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}h ${m}m ${s}s`
  return `${m}m ${s}s`
}

/**
 * 总览耗时：含正文流式。
 * 起止优先用客户端墙钟（timelineStartedAt / timelineEndedAt），避免与步骤上服务端
 * startedAt/endedAt 混算导致「先涨到 20s 再掉到 15s」。
 */
export function resolveTimelineElapsedMs(opts: {
  steps: ProcessingStep[]
  live: boolean
  nowMs?: number
  fallbackStartMs?: number
  fallbackEndMs?: number
}): number | undefined {
  let stepStart: number | undefined
  let maxEnded: number | undefined
  for (const step of opts.steps) {
    const t = step.startedAt ?? step.ts
    if (typeof t === 'number' && Number.isFinite(t)) {
      stepStart = stepStart == null ? t : Math.min(stepStart, t)
    }
    if (typeof step.endedAt === 'number' && Number.isFinite(step.endedAt)) {
      maxEnded = maxEnded == null ? step.endedAt : Math.max(maxEnded, step.endedAt)
    }
  }
  // 有本地 start 时不用服务端 step 时钟（防 JVM/浏览器时钟差）
  const start = opts.fallbackStartMs ?? stepStart
  if (start == null) return undefined
  let end: number | undefined
  if (opts.live) {
    end = opts.nowMs ?? Date.now()
  } else if (opts.fallbackEndMs != null) {
    end = opts.fallbackEndMs
  } else {
    end = maxEnded
  }
  if (end == null || end < start) return undefined
  return end - start
}

export type TimelineMessageStatus = 'streaming' | 'interrupted' | 'failed' | 'completed'

export function resolveTimelineSummaryPrefix(opts: {
  live: boolean
  messageStatus?: TimelineMessageStatus
}): string {
  if (opts.live || opts.messageStatus === 'streaming') return '正在处理'
  if (opts.messageStatus === 'interrupted') return '已中断'
  if (opts.messageStatus === 'failed') return '已失败'
  return '已完成'
}

export function formatTimelineSummaryText(prefix: string, clock: string): string {
  const c = clock.trim()
  return c ? `${prefix} ${c}` : prefix
}
