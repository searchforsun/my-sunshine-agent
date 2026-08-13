import { ref } from 'vue'
import {
  EXECUTION_PREFERENCE_STORAGE_KEY,
  type ExecutionPreference,
  normalizeExecutionPreference,
} from '../api/executionModes'

function loadGlobalDefault(): ExecutionPreference {
  try {
    const raw = localStorage.getItem(EXECUTION_PREFERENCE_STORAGE_KEY)
    if (raw != null && raw !== '') return normalizeExecutionPreference(raw)
  } catch { /* ignore */ }
  return 'fast'
}

const globalDefault = ref<ExecutionPreference>(loadGlobalDefault())
/** 当前 Chat 底栏生效 preference（会话级可覆盖 globalDefault） */
const preference = ref<ExecutionPreference>(globalDefault.value)

export function useExecutionPreference() {
  /** P2：设置页全局默认，新会话 / 无记忆会话使用 */
  function setGlobalDefault(next: ExecutionPreference) {
    globalDefault.value = next
    preference.value = next
    try {
      localStorage.setItem(EXECUTION_PREFERENCE_STORAGE_KEY, next)
    } catch { /* ignore */ }
  }

  /** P1：切换会话时恢复该会话最近一次 preference（含旧 wire 映射） */
  function applyConversationPreference(stored?: string | null) {
    if (stored != null && stored !== '') {
      preference.value = normalizeExecutionPreference(stored)
    } else {
      preference.value = globalDefault.value
    }
  }

  function setPreference(next: ExecutionPreference) {
    preference.value = next
  }

  return {
    preference,
    globalDefault,
    setPreference,
    setGlobalDefault,
    applyConversationPreference,
  }
}
