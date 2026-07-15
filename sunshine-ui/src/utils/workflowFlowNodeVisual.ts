import { formatDuration } from '../api/processingSteps'
import type { WorkflowFlowExecOverlay } from './workflowFlowProjection'

/** Chat 执行态节点角标文案 */
export function resolveExecStatusText(
  exec: WorkflowFlowExecOverlay | undefined,
  nodeType: string,
): string {
  if (!exec?.status) return ''
  if (exec.status === 'awaiting_confirm') return '待确认'
  if (exec.status === 'paused') return '暂停'
  if (exec.status === 'terminated') return '已终止'
  if (exec.status === 'skipped') return '已跳过'
  if (exec.status === 'pending' && nodeType !== 'start') return '等待中'
  if (exec.status === 'error' && exec.live && exec.recoveryAwaiting) return '发生错误'
  if (exec.durationMs != null) return formatDuration(exec.durationMs)
  if (exec.live && exec.status === 'running') return '进行中'
  return ''
}

/** 执行态节点：与 PlanExecutionCanvas / WorkflowFlowNode 同套状态色（边框 + 浅底） */
export function resolveExecVisualClasses(
  exec: WorkflowFlowExecOverlay | undefined,
): Record<string, boolean> {
  if (!exec?.status) return {}
  const live = !!exec.live
  const status = exec.status
  const out: Record<string, boolean> = { 'has-exec-state': true }
  if (status === 'running') {
    out['is-running'] = true
    if (live) out['is-live'] = true
  } else if (status === 'done') {
    out['is-done'] = true
  } else if (status === 'error') {
    out['is-error'] = true
    if (live && exec.recoveryAwaiting) out['is-live-recovery'] = true
  } else if (status === 'pending') {
    out['is-pending'] = true
  } else if (status === 'skipped') {
    out['is-skipped'] = true
  } else if (status === 'terminated') {
    out['is-terminated'] = true
  } else if (status === 'awaiting_confirm') {
    out['is-awaiting-confirm'] = true
    if (live) out['is-awaiting-breathe'] = true
  } else if (status === 'paused') {
    out['is-paused'] = true
    if (live) out['is-paused-breathe'] = true
  }
  return out
}
