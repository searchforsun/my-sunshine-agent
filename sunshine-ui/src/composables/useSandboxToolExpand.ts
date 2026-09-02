import { computed, onUnmounted, ref, type MaybeRefOrGetter, toValue } from 'vue'
import type { ProcessingStep } from '../api/processingSteps'
import {
  extractSandboxExecCommand,
  extractSandboxSearchRoot,
  inferSandboxSearchRoot,
  isSandboxExecStep,
  isSandboxToolStep,
  isSandboxPathListOutput,
  parseSandboxPathList,
  resolveSandboxFocusPath,
  resolveStepExpandInner,
} from '../api/processingSteps'
import { catalogToolIdFromStepId } from '../api/processingStepsDisplay'
import { linkifyWebSearchText } from '../utils/webSearchLinkify'
import {
  writeContentAsAddLines,
  countWriteAddLines,
  linesFromEditDiffMeta,
  isSandboxWriteStep,
  isSandboxEditStep,
  summarizeDiffCounts,
  type SandboxDiffLine,
} from '../api/sandboxEditDiff'
import { registerHljsLanguages } from '../utils/markdown/registerHljsLanguages'
import { copyText } from '../utils/stream-markdown/clipboard'

const hljs = registerHljsLanguages()

function langFromPath(path: string): string | null {
  const dot = path.lastIndexOf('.')
  if (dot < 0) return null
  const ext = path.slice(dot).toLowerCase()
  const map: Record<string, string> = {
    '.py': 'python',
    '.sh': 'bash',
    '.bash': 'bash',
    '.json': 'json',
    '.yaml': 'yaml',
    '.yml': 'yaml',
    '.sql': 'sql',
    '.xml': 'xml',
    '.html': 'xml',
    '.htm': 'xml',
    '.js': 'javascript',
    '.ts': 'typescript',
    '.jsx': 'javascript',
    '.tsx': 'typescript',
    '.java': 'java',
    '.rs': 'rust',
    '.cpp': 'cpp',
    '.c': 'c',
  }
  return map[ext] ?? null
}

function highlightCode(text: string, lang: string | null): string {
  if (!text) return ''
  try {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(text, { language: lang }).value
    }
    return hljs.highlightAuto(text).value
  } catch {
    return ''
  }
}

/** 沙箱工具步展开区：路径列表 / diff / exec / 高亮正文 */
export function useSandboxToolExpand(stepSource: MaybeRefOrGetter<ProcessingStep>) {
  const isSandboxTool = computed(() => isSandboxToolStep(toValue(stepSource)))
  const isSandboxExec = computed(() => isSandboxExecStep(toValue(stepSource)))
  /** 网页搜索：结果以标题/URL/摘要文本展示，URL 需链接化（webfetch 保持原文高亮） */
  const isWebSearch = computed(() =>
    catalogToolIdFromStepId(toValue(stepSource).id) === 'sandbox__websearch',
  )
  const execCommand = computed(() => extractSandboxExecCommand(toValue(stepSource)) ?? '')
  const sandboxRaw = computed(() => {
    if (!isSandboxTool.value) return ''
    return resolveStepExpandInner(toValue(stepSource))
  })
  const sandboxPathEntries = computed(() => {
    const step = toValue(stepSource)
    if (!isSandboxTool.value || isSandboxExec.value || !sandboxRaw.value) return []
    if (!isSandboxPathListOutput(step, sandboxRaw.value)) return []
    const fromMeta = step.metadata?.sandboxSearchRoot?.trim()
    const fromSummary = extractSandboxSearchRoot(step.summary?.after)
      || extractSandboxSearchRoot(step.summary?.active)
    const paths = parseSandboxPathList(sandboxRaw.value).map(e => e.path)
    const root = fromMeta || fromSummary || inferSandboxSearchRoot(paths)
    return parseSandboxPathList(sandboxRaw.value, root)
  })
  const sandboxEditDiffLines = computed((): SandboxDiffLine[] => {
    const step = toValue(stepSource)
    if (!isSandboxTool.value || isSandboxExec.value) return []
    if (sandboxPathEntries.value.length) return []
    if (isSandboxEditStep(step)) {
      return linesFromEditDiffMeta(step.metadata?.editDiff) ?? []
    }
    if (isSandboxWriteStep(step) && sandboxRaw.value) {
      return writeContentAsAddLines(sandboxRaw.value)
    }
    return []
  })
  const editDiffSummary = computed(() => {
    const step = toValue(stepSource)
    if (!isSandboxTool.value || isSandboxExec.value) return null
    if (sandboxPathEntries.value.length) return null
    // 折叠主行：write 只计数，勿构建整文件行数组（密集脚手架写文件时主路径）
    if (isSandboxWriteStep(step) && sandboxRaw.value) {
      const add = countWriteAddLines(sandboxRaw.value)
      return add > 0 ? { add, del: 0 } : null
    }
    if (isSandboxEditStep(step)) {
      const lines = linesFromEditDiffMeta(step.metadata?.editDiff) ?? []
      if (!lines.length) return null
      const { add, del } = summarizeDiffCounts(lines)
      if (!add && !del) return null
      return { add, del }
    }
    return null
  })
  const editDiffLang = computed(() => {
    const step = toValue(stepSource)
    const metaPath = step.metadata?.editDiff?.path?.trim()
    const path = metaPath || resolveSandboxFocusPath(step)
    return path ? langFromPath(path) : null
  })
  const execCommandHtml = computed(() => {
    if (!execCommand.value) return ''
    return highlightCode(execCommand.value, 'bash')
  })
  const execOutputHtml = computed(() => {
    if (!isSandboxExec.value || !sandboxRaw.value) return ''
    return highlightCode(sandboxRaw.value, 'bash')
  })
  const sandboxContentHtml = computed(() => {
    if (!isSandboxTool.value || isSandboxExec.value || !sandboxRaw.value) return ''
    if (sandboxPathEntries.value.length || sandboxEditDiffLines.value.length) return ''
    if (isWebSearch.value) return linkifyWebSearchText(sandboxRaw.value)
    const path = resolveSandboxFocusPath(toValue(stepSource))
    return highlightCode(sandboxRaw.value, path ? langFromPath(path) : null)
  })

  const sandboxCopyDone = ref(false)
  let sandboxCopyTimer: ReturnType<typeof setTimeout> | null = null

  async function copySandboxContent() {
    const parts: string[] = []
    if (isSandboxExec.value && execCommand.value) {
      parts.push(`$ ${execCommand.value}`)
    }
    if (sandboxRaw.value) parts.push(sandboxRaw.value)
    const text = parts.join('\n')
    if (!text) return
    const ok = await copyText(text)
    if (!ok) return
    sandboxCopyDone.value = true
    if (sandboxCopyTimer) clearTimeout(sandboxCopyTimer)
    sandboxCopyTimer = setTimeout(() => {
      sandboxCopyDone.value = false
      sandboxCopyTimer = null
    }, 2000)
  }

  onUnmounted(() => {
    if (sandboxCopyTimer) clearTimeout(sandboxCopyTimer)
  })

  return {
    isSandboxTool,
    isSandboxExec,
    isWebSearch,
    execCommand,
    sandboxRaw,
    sandboxPathEntries,
    sandboxEditDiffLines,
    editDiffSummary,
    editDiffLang,
    execCommandHtml,
    execOutputHtml,
    sandboxContentHtml,
    sandboxCopyDone,
    copySandboxContent,
  }
}
