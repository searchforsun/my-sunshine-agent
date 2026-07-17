import type { ProcessingStep } from './processingSteps'
import { catalogToolIdFromStepId, isSandboxToolStep } from './processingStepsDisplay'
import type { SandboxWorkspaceRefreshScope } from '../composables/sandboxWorkspaceRefresh'

const WORKSPACE_MUTATING = new Set(['sandbox__write', 'sandbox__edit', 'sandbox__exec'])

function sandboxPathRoot(path: string | undefined): SandboxWorkspaceRefreshScope | null {
  const p = path?.trim() ?? ''
  if (p.startsWith('/skills')) return 'skills'
  if (p.startsWith('/workspace') || !p) return 'workspace'
  return null
}

/** 沙箱工具步终态是否应触发工作区刷新 */
export function resolveSandboxWorkspaceRefreshScope(
  step: ProcessingStep,
): SandboxWorkspaceRefreshScope | null {
  if (!isSandboxToolStep(step)) return null
  if (step.lifecycle !== 'done' && step.lifecycle !== 'error') return null
  const toolId = catalogToolIdFromStepId(step.id)
  if (!toolId) return null
  if (WORKSPACE_MUTATING.has(toolId)) return 'workspace'
  if (toolId === 'sandbox__glob' || toolId === 'sandbox__grep') {
    const root = step.metadata?.sandboxSearchRoot?.trim()
      || step.metadata?.sandboxPath?.trim()
    return sandboxPathRoot(root)
  }
  return null
}
