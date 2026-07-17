import { computed, reactive, ref } from 'vue'
import type { WriteHitlMode } from '../api/writeHitlModes'
import { isWriteHitlMode } from '../api/writeHitlModes'

/** 用户级默认（login/me/profile） */
const globalDefault = ref<WriteHitlMode>('never')
/** conversationId → 本会话覆盖（内存；刷新后回落 globalDefault） */
const modes = reactive(new Map<string, WriteHitlMode>())

export function syncWriteHitlDefaultFromAuth(value: string | null | undefined) {
  globalDefault.value = isWriteHitlMode(value) ? value : 'never'
}

export function getWriteHitlMode(conversationId: string | null | undefined): WriteHitlMode {
  if (!conversationId?.trim()) return globalDefault.value
  return modes.get(conversationId.trim()) ?? globalDefault.value
}

export function setWriteHitlMode(
  conversationId: string | null | undefined,
  mode: WriteHitlMode,
): void {
  if (!conversationId?.trim()) return
  const m = isWriteHitlMode(mode) ? mode : 'never'
  modes.set(conversationId.trim(), m)
}

export function useWriteHitlMode(conversationId: () => string | null | undefined) {
  function setGlobalDefault(next: WriteHitlMode) {
    const m = isWriteHitlMode(next) ? next : 'never'
    globalDefault.value = m
    const cid = conversationId()?.trim()
    if (cid) {
      modes.set(cid, m)
    }
  }

  const mode = computed({
    get: () => getWriteHitlMode(conversationId()),
    set: (v: WriteHitlMode) => setWriteHitlMode(conversationId(), v),
  })

  return { mode, globalDefault, setGlobalDefault }
}
