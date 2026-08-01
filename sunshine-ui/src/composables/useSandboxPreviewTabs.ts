import { computed, nextTick, ref, watch, type Ref } from 'vue'
import { readSandboxWorkspaceFile, readWorkspaceSandboxFile } from '../api/sandboxWorkspace'
import { registerHljsLanguages } from '../utils/markdown/registerHljsLanguages'
import { copyText } from '../utils/stream-markdown/clipboard'
import markdown from 'highlight.js/lib/languages/markdown'

const hljs = registerHljsLanguages()
if (!hljs.getLanguage('markdown')) {
  hljs.registerLanguage('markdown', markdown)
}

export function tabFileName(path: string): string {
  const parts = path.split('/').filter(Boolean)
  return parts[parts.length - 1] || path
}

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
    '.md': 'markdown',
  }
  return map[ext] ?? null
}

export interface UseSandboxPreviewTabsOptions {
  getConversationId: () => string
  getWorkspaceId?: () => string | null
  selectedKeys: Ref<string[]>
}

interface PreviewEntry {
  content: string
  meta: string
  offset: number
  totalSize: number
  truncated: boolean
}

function computeHasMore(entry: PreviewEntry): boolean {
  return entry.truncated && (entry.offset + entry.content.length) < entry.totalSize
}
function computeNextOffset(entry: PreviewEntry): number {
  return entry.offset + entry.content.length
}

export function useSandboxPreviewTabs(options: UseSandboxPreviewTabsOptions) {
  const selectedPath = ref('')
  const openTabs = ref<{ path: string }[]>([])
  const tabbarRef = ref<HTMLElement | null>(null)
  const mdRawMode = ref(false)
  const preview = ref('')
  const previewMeta = ref('')
  const previewLoading = ref(false)
  const previewLoadingMore = ref(false)
  const previewCache = ref<Record<string, PreviewEntry>>({})
  const copyDone = ref(false)
  let copyTimer: ReturnType<typeof setTimeout> | null = null

  function scrollActiveTabIntoView() {
    void nextTick(() => {
      const active = tabbarRef.value?.querySelector('.editor-tab.active') as HTMLElement | null
      active?.scrollIntoView({ behavior: 'smooth', inline: 'nearest', block: 'nearest' })
    })
  }

  watch(selectedPath, (path) => {
    mdRawMode.value = false
    if (path) scrollActiveTabIntoView()
  })

  const isMarkdownFile = computed(() => selectedPath.value.toLowerCase().endsWith('.md'))
  const showMarkdownRendered = computed(() => isMarkdownFile.value && !mdRawMode.value)

  const previewCodeHtml = computed(() => {
    if (!preview.value || !selectedPath.value || showMarkdownRendered.value) return ''
    const lang = langFromPath(selectedPath.value)
    try {
      if (lang && hljs.getLanguage(lang)) {
        return hljs.highlight(preview.value, { language: lang }).value
      }
      return hljs.highlightAuto(preview.value).value
    } catch {
      return ''
    }
  })

  const previewLangClass = computed(() => {
    const lang = langFromPath(selectedPath.value) || 'plaintext'
    return `hljs language-${lang}`
  })

  const canCopyPreview = computed(() => !!preview.value && !previewLoading.value)
  const hasMoreContent = computed(() => {
    if (!selectedPath.value) return false
    const entry = previewCache.value[selectedPath.value]
    if (!entry) return false
    return computeHasMore(entry)
  })

  const breadcrumbs = computed(() => {
    if (!selectedPath.value) return [] as { label: string; path: string }[]
    const parts = selectedPath.value.split('/').filter(Boolean)
    const out: { label: string; path: string }[] = []
    let acc = ''
    for (const p of parts) {
      acc += `/${p}`
      out.push({ label: p, path: acc })
    }
    return out
  })

  async function copyPreview() {
    if (!preview.value) return
    const ok = await copyText(preview.value)
    if (!ok) return
    copyDone.value = true
    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      copyDone.value = false
      copyTimer = null
    }, 2000)
  }

  async function openFile(path: string) {
    const conversationId = options.getConversationId()
    const wsId = options.getWorkspaceId?.()
    if ((!conversationId && !wsId) || !path || path === '/workspace' || path === '/skills') return
    if (!openTabs.value.some((t) => t.path === path)) {
      openTabs.value = [...openTabs.value, { path }]
    }
    selectedPath.value = path
    options.selectedKeys.value = [path]
    const cached = previewCache.value[path]
    if (cached) {
      preview.value = cached.content
      previewMeta.value = cached.meta
      previewLoading.value = false
      return
    }
    previewLoading.value = true
    preview.value = ''
    previewMeta.value = ''
    try {
      let data: { content?: string; binary?: boolean; truncated?: boolean; offset?: number; totalSize?: number }
      if (conversationId) {
        data = await readSandboxWorkspaceFile(conversationId, path, 0)
      } else if (wsId) {
        data = await readWorkspaceSandboxFile(wsId, path, 0)
      } else {
        return
      }
      let content = ''
      let meta = ''
      if (data.binary) {
        meta = '二进制文件，暂不支持预览'
      } else {
        content = data.content ?? ''
        const newOffset = data.offset ?? 0
        meta = data.truncated ? '内容已截断，可滚动加载更多' : ''
        previewCache.value = {
          ...previewCache.value,
          [path]: { content, meta, offset: newOffset, totalSize: data.totalSize ?? content.length, truncated: data.truncated ?? false },
        }
        if (selectedPath.value === path) {
          preview.value = content
          previewMeta.value = meta
        }
        return
      }
      previewCache.value = {
        ...previewCache.value,
        [path]: { content, meta, offset: 0, totalSize: 0, truncated: false },
      }
      if (selectedPath.value === path) {
        preview.value = content
        previewMeta.value = meta
      }
    } catch (e) {
      const meta = e instanceof Error ? e.message : '读取失败'
      previewCache.value = {
        ...previewCache.value,
        [path]: { content: '', meta, offset: 0, totalSize: 0, truncated: false },
      }
      if (selectedPath.value === path) {
        preview.value = ''
        previewMeta.value = meta
      }
    } finally {
      if (selectedPath.value === path) {
        previewLoading.value = false
      }
    }
  }

  async function loadMore(path: string) {
    const entry = previewCache.value[path]
    if (!entry || !computeHasMore(entry)) return
    const conversationId = options.getConversationId()
    const wsId = options.getWorkspaceId?.()
    if (!conversationId && !wsId) return
    previewLoadingMore.value = true
    try {
      let data: { content?: string; binary?: boolean; truncated?: boolean; offset?: number; totalSize?: number }
      if (conversationId) {
        data = await readSandboxWorkspaceFile(conversationId, path, computeNextOffset(entry))
      } else if (wsId) {
        data = await readWorkspaceSandboxFile(wsId, path, computeNextOffset(entry))
      } else {
        return
      }
      const chunk = data.content ?? ''
      const newOffset = data.offset ?? 0
      const newTotalSize = data.totalSize ?? (entry.offset + chunk.length)
      const newTruncated = data.truncated ?? false
      const newContent = entry.content + chunk
      const newMeta = newTruncated ? '内容已截断，可滚动加载更多' : ''
      previewCache.value = {
        ...previewCache.value,
        [path]: { content: newContent, meta: newMeta, offset: entry.offset, totalSize: newTotalSize, truncated: newTruncated },
      }
      if (selectedPath.value === path) {
        preview.value = newContent
        previewMeta.value = newMeta
      }
    } catch (e) {
      // silently ignore load-more failures
    } finally {
      previewLoadingMore.value = false
    }
  }

  function activateTab(path: string) {
    if (selectedPath.value === path) return
    selectedPath.value = path
    options.selectedKeys.value = [path]
    const cached = previewCache.value[path]
    if (cached) {
      preview.value = cached.content
      previewMeta.value = cached.meta
      previewLoading.value = false
    } else {
      void openFile(path)
    }
  }

  function closeTab(path: string, ev?: Event) {
    ev?.stopPropagation()
    const idx = openTabs.value.findIndex((t) => t.path === path)
    if (idx < 0) return
    const next = openTabs.value.filter((t) => t.path !== path)
    openTabs.value = next
    const { [path]: _removed, ...rest } = previewCache.value
    previewCache.value = rest
    if (selectedPath.value !== path) return
    if (next.length === 0) {
      selectedPath.value = ''
      options.selectedKeys.value = []
      preview.value = ''
      previewMeta.value = ''
      previewLoading.value = false
      return
    }
    const fallback = next[Math.min(idx, next.length - 1)]
    activateTab(fallback.path)
  }

  function resetPreview() {
    selectedPath.value = ''
    openTabs.value = []
    previewCache.value = {}
    preview.value = ''
    previewMeta.value = ''
    previewLoading.value = false
    previewLoadingMore.value = false
    mdRawMode.value = false
    options.selectedKeys.value = []
  }

  function resetTabsOnConversationChange() {
    openTabs.value = []
    previewCache.value = {}
    selectedPath.value = ''
    options.selectedKeys.value = []
    preview.value = ''
    previewMeta.value = ''
    previewLoadingMore.value = false
  }

  function clearCache() {
    previewCache.value = {}
  }

  function clearCacheUnder(pathPrefix: string) {
    const prefix = pathPrefix.trim()
    if (!prefix) return
    const next = { ...previewCache.value }
    let changed = false
    for (const key of Object.keys(next)) {
      if (key === prefix || key.startsWith(`${prefix}/`)) {
        delete next[key]
        changed = true
      }
    }
    if (changed) previewCache.value = next
  }

  return {
    selectedPath,
    openTabs,
    tabbarRef,
    mdRawMode,
    preview,
    previewMeta,
    previewLoading,
    previewLoadingMore,
    previewCache,
    copyDone,
    isMarkdownFile,
    showMarkdownRendered,
    previewCodeHtml,
    previewLangClass,
    canCopyPreview,
    hasMoreContent,
    breadcrumbs,
    copyPreview,
    openFile,
    loadMore,
    activateTab,
    closeTab,
    resetPreview,
    resetTabsOnConversationChange,
    clearCache,
    clearCacheUnder,
  }
}
