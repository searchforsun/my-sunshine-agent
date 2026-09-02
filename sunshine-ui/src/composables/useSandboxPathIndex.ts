import { ref, watch } from 'vue'
import type { Ref } from 'vue'
import { fetchSandboxFileIndex, fetchWorkspaceFileIndex } from '../api/sandboxWorkspace'
import { sandboxPathIndexReady, sandboxWorkspaceRefresh } from './sandboxWorkspaceRefresh'

const INDEX_DEBOUNCE_MS = 300

/**
 * 会话级文件路径索引：进入会话（有 conversationId 或 workspaceId）即调用后端递归列举接口，
 * 获取扁平化路径集合，写入 window.__smd_sandboxIndex 供 markdown 路径精确匹配。
 * 不依赖工作区抽屉是否打开。
 * - 防抖：短时多次变化合并为一次请求
 * - 去重：进行中的请求用 AbortController 取消，避免旧响应覆盖新结果
 * - 刷新触发：文件树刷新 / checkout 切换 / SSE 工具写文件（sandboxWorkspaceRefresh）
 * - 会话无效时清空索引（离开会话页 / 无任何会话上下文）
 */
export function useSandboxPathIndex(opts: {
  getOpen: () => boolean
  getConversationId: () => string
  getWorkspaceId?: () => string | null
  getCheckoutId?: () => string | null
  treeVersion: Ref<number>
}) {
  const pathIndex = ref<Set<string>>(new Set())
  const loading = ref(false)
  let debounceTimer: ReturnType<typeof setTimeout> | null = null
  let abortController: AbortController | null = null

  async function loadIndex() {
    if (!opts.getOpen()) return
    const cid = opts.getConversationId()
    const wsId = opts.getWorkspaceId?.()
    const checkoutId = opts.getCheckoutId?.()
    const rootPath = wsId && checkoutId ? `/workspace/${checkoutId}` : '/workspace'
    // 取消进行中的请求，避免旧响应覆盖新结果
    if (abortController) abortController.abort()
    abortController = new AbortController()
    loading.value = true
    try {
      let paths: string[]
      if (wsId) {
        paths = await fetchWorkspaceFileIndex(wsId, rootPath)
      } else if (cid) {
        paths = await fetchSandboxFileIndex(cid, rootPath)
      } else {
        paths = []
      }
      if (abortController.signal.aborted) return
      pathIndex.value = new Set(paths)
      ;(window as any).__smd_sandboxIndex = pathIndex.value
      // 索引就绪：通知已渲染消息重新增强路径链接（处理索引未就绪时漏增强的相对路径）
      sandboxPathIndexReady.tick++
    } catch {
      if (!abortController?.signal.aborted) {
        pathIndex.value = new Set()
        ;(window as any).__smd_sandboxIndex = pathIndex.value
      }
    } finally {
      if (!abortController?.signal.aborted) loading.value = false
      abortController = null
    }
  }

  /** 防抖加载：短时多次变化合并为一次请求 */
  function scheduleLoadIndex() {
    if (!opts.getOpen()) {
      pathIndex.value = new Set()
      ;(window as any).__smd_sandboxIndex = pathIndex.value
      return
    }
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      debounceTimer = null
      void loadIndex()
    }, INDEX_DEBOUNCE_MS)
  }

  watch(
    () => [opts.getOpen(), opts.getConversationId(), opts.getWorkspaceId?.(), opts.getCheckoutId?.(), opts.treeVersion.value] as const,
    () => scheduleLoadIndex(),
    { immediate: true },
  )

  // 文件变更信号（SSE 工具写文件等）：抽屉未打开时索引也要随文件变化更新
  watch(
    () => [sandboxWorkspaceRefresh.tick, sandboxWorkspaceRefresh.conversationId] as const,
    () => {
      const signalCid = sandboxWorkspaceRefresh.conversationId
      const cid = opts.getConversationId()
      if (!signalCid || signalCid !== cid) return
      scheduleLoadIndex()
    },
  )

  return { pathIndex, loading, loadIndex }
}
