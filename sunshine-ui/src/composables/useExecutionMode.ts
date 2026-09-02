import { ref } from 'vue'
import {
  EXECUTION_PREFERENCE_STORAGE_KEY,
  type ExecutionMode,
  normalizeExecutionMode,
} from '../api/executionModes'

function loadGlobalDefault(): ExecutionMode {
  try {
    const raw = localStorage.getItem(EXECUTION_PREFERENCE_STORAGE_KEY)
    if (raw != null && raw !== '') return normalizeExecutionMode(raw)
  } catch { /* ignore */ }
  return 'fast'
}

const globalDefault = ref<ExecutionMode>(loadGlobalDefault())
/** 当前 Chat 底栏生效 preference（会话级可覆盖 globalDefault） */
const preference = ref<ExecutionMode>(globalDefault.value)

export function useExecutionMode() {
  /** 读取当前生效 preference（供 store 在新建/复用会话时播种，避免 watch 空值回退覆盖用户选择） */
  function currentPreference(): ExecutionMode {
    return preference.value
  }

  /** P2：设置页全局默认，新会话 / 无记忆会话使用 */
  function setGlobalDefault(next: ExecutionMode) {
    globalDefault.value = next
    preference.value = next
    try {
      localStorage.setItem(EXECUTION_PREFERENCE_STORAGE_KEY, next)
    } catch { /* ignore */ }
  }

  /** P1：切换会话时恢复该会话最近一次 preference（含旧 wire 映射） */
  function applyConversationPreference(stored?: string | null) {
    if (stored != null && stored !== '') {
      preference.value = normalizeExecutionMode(stored)
    } else {
      preference.value = globalDefault.value
    }
  }

  function setPreference(next: ExecutionMode) {
    preference.value = next
  }

  return {
    preference,
    globalDefault,
    currentPreference,
    setPreference,
    setGlobalDefault,
    applyConversationPreference,
  }
}
