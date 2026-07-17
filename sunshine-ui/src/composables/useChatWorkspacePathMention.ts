import { computed, nextTick, ref, watch, type Ref } from 'vue'
import type ComposerSkillInput from '../components/chat/ComposerSkillInput.vue'
import { sandboxPathPlainToken } from '../utils/sandboxPathChip'
import {
  collectWorkspacePaths,
  filterWorkspacePaths,
  matchWorkspacePathMention,
  type WorkspacePathRoot,
  type WorkspacePathSuggestEntry,
} from '../utils/workspacePathSuggest'
import {
  requestSandboxWorkspaceRefresh,
  sandboxWorkspaceRefresh,
  type SandboxWorkspaceRefreshScope,
} from './sandboxWorkspaceRefresh'
export function useChatWorkspacePathMention(
  inputText: Ref<string>,
  conversationId: Ref<string | null | undefined>,
  loading: Ref<boolean>,
  inputRef: Ref<InstanceType<typeof ComposerSkillInput> | undefined>,
) {
  const workspacePaths = ref<WorkspacePathSuggestEntry[]>([])
  const skillsPaths = ref<WorkspacePathSuggestEntry[]>([])
  const pathCatalogConvId = ref<string | null>(null)
  const pathSuggestLoading = ref(false)
  const showPathSuggest = ref(false)
  const pathSuggestIndex = ref(0)
  const pathMentionStart = ref(-1)
  const pathQuery = ref('')

  const pathCatalog = computed(() => [...workspacePaths.value, ...skillsPaths.value])

  const filteredPaths = computed(() =>
    filterWorkspacePaths(pathCatalog.value, pathQuery.value),
  )

  function invalidateCatalog(scope: SandboxWorkspaceRefreshScope) {
    if (scope === 'workspace') workspacePaths.value = []
    else skillsPaths.value = []
  }

  async function loadCatalogPart(convId: string, root: WorkspacePathRoot) {
    const entries = await collectWorkspacePaths(convId, [root])
    if (root === '/workspace') workspacePaths.value = entries
    else skillsPaths.value = entries
    pathCatalogConvId.value = convId
  }

  async function loadPathCatalog(
    convId: string,
    scope: SandboxWorkspaceRefreshScope | 'both' = 'both',
  ) {
    pathSuggestLoading.value = true
    try {
      const tasks: Promise<void>[] = []
      if (scope === 'both' || scope === 'workspace') {
        if (!(pathCatalogConvId.value === convId && workspacePaths.value.length > 0)) {
          tasks.push(loadCatalogPart(convId, '/workspace'))
        }
      }
      if (scope === 'both' || scope === 'skills') {
        if (!(pathCatalogConvId.value === convId && skillsPaths.value.length > 0)) {
          tasks.push(loadCatalogPart(convId, '/skills'))
        }
      }
      await Promise.all(tasks)
    } catch (e) {
      console.warn('[ChatView] workspace path catalog load failed', e)
      if (scope === 'both' || scope === 'workspace') workspacePaths.value = []
      if (scope === 'both' || scope === 'skills') skillsPaths.value = []
      pathCatalogConvId.value = convId
    } finally {
      pathSuggestLoading.value = false
    }
  }

  function refreshPathMention(text: string) {
    const convId = conversationId.value?.trim()
    if (!convId || loading.value) {
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
    void loadPathCatalog(convId)
  }

  watch(inputText, refreshPathMention)
  watch(conversationId, (id) => {
    if (!id || id !== pathCatalogConvId.value) {
      workspacePaths.value = []
      skillsPaths.value = []
      pathCatalogConvId.value = null
    }
    refreshPathMention(inputText.value)
  })
  watch(loading, (busy) => {
    if (busy) showPathSuggest.value = false
  })
  watch(() => sandboxWorkspaceRefresh.tick, () => {
    const convId = sandboxWorkspaceRefresh.conversationId
    if (!convId || convId !== conversationId.value?.trim()) return
    invalidateCatalog(sandboxWorkspaceRefresh.scope)
    if (showPathSuggest.value) {
      void loadPathCatalog(convId, sandboxWorkspaceRefresh.scope)
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
    const items = filteredPaths.value
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
    filteredPaths,
    applyPathSuggest,
    handlePathKeydown,
  }
}
