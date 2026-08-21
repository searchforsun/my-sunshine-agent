/** 时间线步骤排序 */
import type { ProcessingStep, StepPhase } from './processingSteps'

/** ReAct 设计序：intent → skill → tasks → think* → tool*（skill 固定第二步；tasks 在 think 前） */
export const STEP_ORDER: StepPhase[] = [
  'intent', 'skill', 'plan', 'node', 'rag', 'tasks', 'think', 'tool', 'agent', 'generate',
]

function isThinkStepId(id: string): boolean {
  return id === 'think' || id.startsWith('think-')
}

/** 静态 Workflow 的节点级 reasoning，不走 ReAct think 步骤 */
export function isWorkflowNodeStepId(id: string | undefined): boolean {
  return !!id && id.startsWith('node-')
}

/**
 * harness worker 卡任务序号：taskId 首个数字。
 * worker-t1-1 → 1；worker-r5-quality-2 → 5；worker-unknown → null。
 */
function workerTaskSeq(id: string): number | null {
  const m = /^worker-.*?(\d+)/.exec(id)
  return m ? Number(m[1]) : null
}

/** harness worker 卡执行版本（末尾 -N）：worker-t1-2 → 2；无版本返回 0 */
function workerTaskVersion(id: string): number {
  const m = /^worker-.*?-(\d+)$/.exec(id)
  return m ? Number(m[1]) : 0
}

/** 将指定 phase 步移到 anchorPhase 之后（若不存在则原样返回） */
function movePhaseAfterAnchor(
  steps: ProcessingStep[],
  phase: StepPhase,
  anchorPhase: StepPhase,
): ProcessingStep[] {
  const phaseIdx = steps.findIndex(s => s.phase === phase)
  if (phaseIdx < 0) return steps
  const anchorIdx = steps.findIndex(s => s.phase === anchorPhase)
  if (anchorIdx < 0) return steps
  if (phaseIdx === anchorIdx + 1) return steps
  const moved = steps[phaseIdx]
  const without = steps.filter((_, i) => i !== phaseIdx)
  const newAnchor = without.findIndex(s => s.phase === anchorPhase)
  const insertAt = newAnchor + 1
  return [...without.slice(0, insertAt), moved, ...without.slice(insertAt)]
}

/**
 * 头部钉扎：skill 固定为第二步（intent 之后）；
 * tasks 紧随 skill（无 skill 则紧随 intent），始终在 think 之前。
 */
function repositionPinnedHeaderSteps(steps: ProcessingStep[]): ProcessingStep[] {
  let next = movePhaseAfterAnchor(steps, 'skill', 'intent')
  const hasSkill = next.some(s => s.phase === 'skill')
  next = movePhaseAfterAnchor(next, 'tasks', hasSkill ? 'skill' : 'intent')
  return next
}

export function sortSteps(steps: ProcessingStep[]): ProcessingStep[] {
  const sorted = [...steps].sort((a, b) => {
    // harness worker 卡优先按任务序号稳定排序（T1/T2/T3，与 TaskBoard 一致）：
    // dispatch_worker 并发派发时各 worker begin 的 startedAt 毫秒级随机，且 auxiliary 直刷
    // 到达顺序不定，按时间戳排序会让卡片随到达顺序跳位；同序号（重派 t1-1/t1-2）按版本升序。
    const aSeq = workerTaskSeq(a.id)
    const bSeq = workerTaskSeq(b.id)
    if (aSeq != null && bSeq != null) {
      if (aSeq !== bSeq) return aSeq - bSeq
      // 无版本（描述后缀如 t1-arch）视为无穷大，排在有版本执行卡之后
      const aVer = workerTaskVersion(a.id) || Number.POSITIVE_INFINITY
      const bVer = workerTaskVersion(b.id) || Number.POSITIVE_INFINITY
      if (aVer !== bVer) return aVer - bVer
    }
    const aStart = a.startedAt ?? a.ts ?? 0
    const bStart = b.startedAt ?? b.ts ?? 0
    if (aStart !== bStart) return aStart - bStart
    const ai = STEP_ORDER.indexOf(a.phase)
    const bi = STEP_ORDER.indexOf(b.phase)
    const aOrder = ai >= 0 ? ai : STEP_ORDER.length
    const bOrder = bi >= 0 ? bi : STEP_ORDER.length
    if (aOrder !== bOrder) return aOrder - bOrder
    return a.id.localeCompare(b.id)
  })
  return repositionPinnedHeaderSteps(sorted)
}

export { isThinkStepId }
