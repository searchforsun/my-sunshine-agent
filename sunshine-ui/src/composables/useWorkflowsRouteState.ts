import { useRoute, useRouter } from 'vue-router'

/** 工作流管理 URL 状态：/workflows/:workflowId，刷新后恢复选中项 */
export function useWorkflowsRouteState() {
  const route = useRoute()
  const router = useRouter()

  function readWorkflowId(): string | null {
    const raw = route.params.workflowId
    if (typeof raw !== 'string') return null
    const trimmed = raw.trim()
    return trimmed || null
  }

  function syncWorkflowId(id: string | null) {
    const current = readWorkflowId()
    if (id === current) return
    if (!id) {
      void router.replace({ name: 'workflows' })
      return
    }
    void router.replace({ name: 'workflows', params: { workflowId: id } })
  }

  return { readWorkflowId, syncWorkflowId }
}
