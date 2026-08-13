import { ref, type Ref } from 'vue'
import { dryRunRouting, type RoutingDryRunResponse } from '../api/prompts'
import { friendlyErrorMessage } from '../api/apiError'
import type { usePromptsRouteState } from './usePromptsRouteState'
import type { ExecutionPreference } from '../api/executionModes'

export interface RoutingDryRunDeps {
  routingPane: Ref<'editor' | 'dry-run'>
  routeState: ReturnType<typeof usePromptsRouteState>
  message: ReturnType<typeof import('naive-ui')['useMessage']>
}

export function useRoutingDryRun(deps: RoutingDryRunDeps) {
  const { routingPane, routeState, message } = deps
  const dryRunQuery = ref('')
  /** 试跑模拟的锁定执行模式（v6 三值；同 Chat 底栏） */
  const dryRunMode = ref<ExecutionPreference>('fast')
  const dryRunResult = ref<RoutingDryRunResponse | null>(null)
  const dryRunning = ref(false)

  function openRoutingDryRun() {
    routingPane.value = 'dry-run'
    routeState.syncQuery({ pane: 'dry-run', tab: 'routing' })
  }

  async function runDryRun() {
    const q = dryRunQuery.value.trim()
    if (!q) {
      message.warning('请输入试跑问句')
      return
    }
    dryRunning.value = true
    try {
      dryRunResult.value = await dryRunRouting(q, dryRunMode.value)
    } catch (e) {
      message.error(friendlyErrorMessage(e, '试跑失败'))
      console.error(e)
    } finally {
      dryRunning.value = false
    }
  }

  return {
    dryRunQuery,
    dryRunMode,
    dryRunResult,
    dryRunning,
    runDryRun,
    openRoutingDryRun,
  }
}
