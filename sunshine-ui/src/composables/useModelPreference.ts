import { ref } from 'vue'

const MODEL_PREFERENCE_STORAGE_KEY = 'sunshine-model-preference'

function loadGlobalDefaultModel(): string | null {
  try {
    const raw = localStorage.getItem(MODEL_PREFERENCE_STORAGE_KEY)
    if (raw && raw.trim()) return raw.trim()
  } catch { /* ignore */ }
  return null
}

const globalDefaultModel = ref<string | null>(loadGlobalDefaultModel())
/** 当前 Chat 底栏生效 modelName（会话级可覆盖） */
const modelName = ref<string | null>(globalDefaultModel.value)

export function useModelPreference() {
  function setGlobalDefaultModel(next: string | null) {
    globalDefaultModel.value = next
    modelName.value = next
    try {
      if (next) localStorage.setItem(MODEL_PREFERENCE_STORAGE_KEY, next)
      else localStorage.removeItem(MODEL_PREFERENCE_STORAGE_KEY)
    } catch { /* ignore */ }
  }

  function applyConversationModel(stored?: string | null) {
    if (stored && stored.trim()) {
      modelName.value = stored.trim()
    } else {
      modelName.value = globalDefaultModel.value
    }
  }

  function setModelName(next: string | null) {
    modelName.value = next
  }

  return {
    modelName,
    globalDefaultModel,
    setModelName,
    setGlobalDefaultModel,
    applyConversationModel,
  }
}
