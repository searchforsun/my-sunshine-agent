import { useRoute, useRouter } from 'vue-router'

/** Skills 管理 URL：/skills/:skillId?，刷新后恢复选中项 */
export function useSkillsRouteState() {
  const route = useRoute()
  const router = useRouter()

  function readSkillId(): string | null {
    const raw = route.params.skillId
    if (typeof raw !== 'string') return null
    const trimmed = raw.trim()
    return trimmed || null
  }

  function syncSkillId(id: string | null) {
    const current = readSkillId()
    if (id === current) return
    if (!id) {
      void router.replace({ name: 'skills' })
      return
    }
    void router.replace({ name: 'skills', params: { skillId: id } })
  }

  return { readSkillId, syncSkillId }
}
