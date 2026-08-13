import { ref } from 'vue'

const KB_PREFERENCE_STORAGE_KEY = 'sunshine-kb-preference'

const globalDefaultKb = ref<string | null>(null)
/** 当前生效 kbId（会话级可覆盖；默认来自账号 defaultKbId） */
const kbId = ref<string | null>(null)

function peekLegacyLocalDefault(): string | null {
  try {
    const raw = localStorage.getItem(KB_PREFERENCE_STORAGE_KEY)
    if (raw && raw.trim()) return raw.trim()
  } catch { /* ignore */ }
  return null
}

function clearLegacyLocalDefault() {
  try {
    localStorage.removeItem(KB_PREFERENCE_STORAGE_KEY)
  } catch { /* ignore */ }
}

/** 登录 / me 同步账号默认知识库；账号为空时短暂回落旧 localStorage（保存设置后清除） */
export function syncKbDefaultFromAuth(defaultKbId?: string | null) {
  const fromAuth = defaultKbId?.trim() || null
  if (fromAuth) {
    clearLegacyLocalDefault()
    globalDefaultKb.value = fromAuth
    kbId.value = fromAuth
    return
  }
  const legacy = peekLegacyLocalDefault()
  globalDefaultKb.value = legacy
  kbId.value = legacy
}

export function useKbPreference() {
  function setGlobalDefaultKb(next: string | null) {
    const normalized = next?.trim() || null
    globalDefaultKb.value = normalized
    kbId.value = normalized
    clearLegacyLocalDefault()
  }

  function applyConversationKb(stored?: string | null) {
    if (stored && stored.trim()) {
      kbId.value = stored.trim()
    } else {
      kbId.value = globalDefaultKb.value
    }
  }

  function setKbId(next: string | null) {
    kbId.value = next?.trim() || null
  }

  return {
    kbId,
    globalDefaultKb,
    setKbId,
    setGlobalDefaultKb,
    applyConversationKb,
  }
}
