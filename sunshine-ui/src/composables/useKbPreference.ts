import { ref } from 'vue'

const KB_PREFERENCE_STORAGE_KEY = 'sunshine-kb-preference'

function loadGlobalDefaultKb(): string | null {
  try {
    const raw = localStorage.getItem(KB_PREFERENCE_STORAGE_KEY)
    if (raw && raw.trim()) return raw.trim()
  } catch { /* ignore */ }
  return null
}

const globalDefaultKb = ref<string | null>(loadGlobalDefaultKb())
/** 当前 Chat 底栏生效 kbId（会话级可覆盖） */
const kbId = ref<string | null>(globalDefaultKb.value)

export function useKbPreference() {
  function setGlobalDefaultKb(next: string | null) {
    globalDefaultKb.value = next
    kbId.value = next
    try {
      if (next) localStorage.setItem(KB_PREFERENCE_STORAGE_KEY, next)
      else localStorage.removeItem(KB_PREFERENCE_STORAGE_KEY)
    } catch { /* ignore */ }
  }

  function applyConversationKb(stored?: string | null) {
    if (stored && stored.trim()) {
      kbId.value = stored.trim()
    } else {
      kbId.value = globalDefaultKb.value
    }
  }

  function setKbId(next: string | null) {
    kbId.value = next
  }

  return {
    kbId,
    globalDefaultKb,
    setKbId,
    setGlobalDefaultKb,
    applyConversationKb,
  }
}
