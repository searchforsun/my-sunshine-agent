import { ref } from 'vue'

const MODEL_PREFERENCE_STORAGE_KEY = 'sunshine-model-preference'
const REASONING_EFFORT_STORAGE_KEY = 'sunshine-reasoning-effort'
/** 思考深度值域（OpenAI reasoning_effort）；后端同源校验 */
export const REASONING_EFFORT_VALUES = ['minimal', 'low', 'medium', 'high'] as const
export type ReasoningEffort = (typeof REASONING_EFFORT_VALUES)[number]

function loadStoredValue(key: string): string | null {
  try {
    const raw = localStorage.getItem(key)
    if (raw && raw.trim()) return raw.trim()
  } catch { /* ignore */ }
  return null
}

const globalDefaultModel = ref<string | null>(loadStoredValue(MODEL_PREFERENCE_STORAGE_KEY))
/** 当前 Chat 底栏生效 modelName（会话级可覆盖） */
const modelName = ref<string | null>(globalDefaultModel.value)

const globalDefaultEffort = ref<ReasoningEffort | null>(
  normalizeEffort(loadStoredValue(REASONING_EFFORT_STORAGE_KEY)))
/** 当前 Chat 底栏生效思考深度（会话级可覆盖） */
const reasoningEffort = ref<ReasoningEffort | null>(globalDefaultEffort.value)

function normalizeEffort(value: string | null | undefined): ReasoningEffort | null {
  if (!value) return null
  return (REASONING_EFFORT_VALUES as readonly string[]).includes(value)
    ? (value as ReasoningEffort)
    : null
}

export function useModelPreference() {
  function setGlobalDefaultModel(next: string | null) {
    globalDefaultModel.value = next
    modelName.value = next
    try {
      if (next) localStorage.setItem(MODEL_PREFERENCE_STORAGE_KEY, next)
      else localStorage.removeItem(MODEL_PREFERENCE_STORAGE_KEY)
    } catch { /* ignore */ }
  }

  function setGlobalDefaultEffort(next: ReasoningEffort | null) {
    globalDefaultEffort.value = next
    reasoningEffort.value = next
    try {
      if (next) localStorage.setItem(REASONING_EFFORT_STORAGE_KEY, next)
      else localStorage.removeItem(REASONING_EFFORT_STORAGE_KEY)
    } catch { /* ignore */ }
  }

  function applyConversationModel(stored?: string | null) {
    if (stored && stored.trim()) {
      modelName.value = stored.trim()
    } else {
      modelName.value = globalDefaultModel.value
    }
  }

  function applyConversationEffort(stored?: string | null) {
    const normalized = normalizeEffort(stored)
    reasoningEffort.value = normalized ?? globalDefaultEffort.value
  }

  function setModelName(next: string | null) {
    modelName.value = next
  }

  function setReasoningEffort(next: ReasoningEffort | null) {
    reasoningEffort.value = next
  }

  return {
    modelName,
    reasoningEffort,
    globalDefaultModel,
    globalDefaultEffort,
    setModelName,
    setReasoningEffort,
    setGlobalDefaultModel,
    setGlobalDefaultEffort,
    applyConversationModel,
    applyConversationEffort,
  }
}
