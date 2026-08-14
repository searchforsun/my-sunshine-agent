import { nextTick, ref, watch, type Ref } from 'vue'
import type ComposerSkillInput from '../components/chat/ComposerSkillInput.vue'
import {
  fetchSandboxFileIndex,
  listSandboxWorkspace,
} from '../api/sandboxWorkspace'
import { sandboxPathPlainToken } from '../utils/sandboxPathChip'
import {
  matchWorkspacePathMention,
  searchFlatPaths,
  WorkspacePathSuggestIndex,
  type WorkspacePathSuggestEntry,
} from '../utils/workspacePathSuggest'
import { sandboxWorkspaceRefresh } from './sandboxWorkspaceRefresh'

const PATH_SEARCH_DEBOUNCE_MS = 300

export function useChatWorkspacePathMention(
  inputText: Ref<string>,
  conversationId: Ref<string | null | undefined>,
  loading: Ref<boolean>,
  inputRef: Ref<InstanceType<typeof ComposerSkillInput> | undefined>,
  sessionKind: Ref<string>,
) {
  const pathResults = ref<WorkspacePathSuggestEntry[]>([])
  const pathSuggestLoading = ref(false)
  const showPathSuggest = ref(false)
  const pathSuggestIndex = ref(0)
  const pathMentionStart = ref(-1)
  const pathQuery = ref('')

  let pathIndex: WorkspacePathSuggestIndex | null = null
  let flatPaths: string[] | null = null
  let indexConvId: string | null = null
  let searchSeq = 0
  let searchTimer: ReturnType<typeof setTimeout> | null = null

  /** 每个会话持有一个懒加载索引（按目录缓存，供空 query 展示两层） */
  function ensurePathIndex(convId: string): WorkspacePathSuggestIndex {
    if (!pathIndex || indexConvId !== convId) {
      pathIndex = new WorkspacePathSuggestIndex(
        (dirPath) => listSandboxWorkspace(convId, dirPath).then((d) => d.entries ?? []),
      )
      indexConvId = convId
    }
    return pathIndex
  }

  /**
   * 全量路径索引：优先复用 `window.__smd_sandboxIndex`（会话级索引已加载），
   * 未就绪则一次性请求后端索引接口并缓存，供关键词搜索命中任意层级。
   */
  async function ensureFlatPaths(convId: string): Promise<string[]> {
    if (flatPaths) return flatPaths
    const winIndex = (window as any).__smd_sandboxIndex as Set<string> | undefined
    if (winIndex instanceof Set && winIndex.size > 0) {
      flatPaths = [...winIndex]
      return flatPaths
    }
    const [ws, skills] = await Promise.all([
      fetchSandboxFileIndex(convId, '/workspace').catch(() => [] as string[]),
      fetchSandboxFileIndex(convId, '/skills').catch(() => [] as string[]),
    ])
    flatPaths = [...ws, ...skills]
    ;(window as any).__smd_sandboxIndex = new Set(flatPaths)
    return flatPaths
  }

  /** 懒加载搜索：乱序响应以序号丢弃，避免旧结果覆盖新 query */
  async function runSearch(convId: string, query: string) {
    const seq = ++searchSeq
    pathSuggestLoading.value = true
    try {
      // 空 query：目录式懒加载两层；有 query：全量索引模糊搜索
      const items = query.trim()
        ? searchFlatPaths(await ensureFlatPaths(convId), query)
        : await ensurePathIndex(convId).search('')
      if (seq !== searchSeq) return
      pathResults.value = items
    } finally {
      if (seq === searchSeq) pathSuggestLoading.value = false
    }
  }

  function scheduleSearch(convId: string, query: string) {
    if (searchTimer) clearTimeout(searchTimer)
    searchTimer = setTimeout(() => {
      searchTimer = null
      void runSearch(convId, query)
    }, PATH_SEARCH_DEBOUNCE_MS)
  }

  function refreshPathMention(text: string) {
    const convId = conversationId.value?.trim()
    // 工作区路径补全仅属任务会话（有工作区），对话会话不触发
    if (sessionKind.value !== 'task' || !convId || loading.value) {
      showPathSuggest.value = false
      return
    }
    const hit = matchWorkspacePathMention(text)
    if (!hit) {
      showPathSuggest.value = false
      return
    }
    pathMentionStart.value = hit.start
    pathQuery.value = hit.query
    showPathSuggest.value = true
    pathSuggestIndex.value = 0
    scheduleSearch(convId, hit.query)
  }

  watch(inputText, refreshPathMention)
  watch(conversationId, (id) => {
    if (id !== indexConvId) {
      pathIndex = null
      flatPaths = null
      indexConvId = null
      pathResults.value = []
    }
    refreshPathMention(inputText.value)
  })
  watch(loading, (busy) => {
    if (busy) showPathSuggest.value = false
  })
  watch(() => sandboxWorkspaceRefresh.tick, () => {
    const convId = sandboxWorkspaceRefresh.conversationId
    if (!convId || convId !== conversationId.value?.trim()) return
    pathIndex?.invalidate()
    flatPaths = null
    if (showPathSuggest.value) {
      scheduleSearch(convId, pathQuery.value)
    }
  })

  function applyPathSuggest(entry: WorkspacePathSuggestEntry) {
    if (pathMentionStart.value < 0) return
    const prefix = inputText.value.slice(0, pathMentionStart.value)
    inputText.value = `${prefix}${sandboxPathPlainToken(entry.path)} `
    showPathSuggest.value = false
    nextTick(() => inputRef.value?.focus())
  }

  function handlePathKeydown(e: KeyboardEvent): boolean {
    if (!showPathSuggest.value || pathSuggestLoading.value) return false
    const items = pathResults.value
    if (items.length === 0) {
      if (e.key === 'Escape') {
        showPathSuggest.value = false
        return true
      }
      return false
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      pathSuggestIndex.value = (pathSuggestIndex.value + 1) % items.length
      return true
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      pathSuggestIndex.value = (pathSuggestIndex.value - 1 + items.length) % items.length
      return true
    }
    if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
      e.preventDefault()
      applyPathSuggest(items[pathSuggestIndex.value])
      return true
    }
    if (e.key === 'Escape') {
      showPathSuggest.value = false
      return true
    }
    return false
  }

  return {
    showPathSuggest,
    pathSuggestIndex,
    pathSuggestLoading,
    filteredPaths: pathResults,
    applyPathSuggest,
    handlePathKeydown,
  }
}
