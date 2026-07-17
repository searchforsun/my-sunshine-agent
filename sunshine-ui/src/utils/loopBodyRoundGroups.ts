import type { ProcessingStep } from '../api/processingSteps'

export type LoopRoundGroup = {
  round: number
  steps: ProcessingStep[]
}

/**
 * 将 loop.subSteps（id=`i{n}-node-…`）按轮次分组；无前缀的步骤归入 round=0。
 */
export function groupLoopBodySubStepsByRound(steps: ProcessingStep[] | undefined): LoopRoundGroup[] {
  if (!steps?.length) return []
  const byRound = new Map<number, ProcessingStep[]>()
  for (const step of steps) {
    const m = /^i(\d+)-/.exec(step.id ?? '')
    const round = m ? Number(m[1]) : 0
    const list = byRound.get(round)
    if (list) list.push(step)
    else byRound.set(round, [step])
  }
  return [...byRound.keys()]
    .sort((a, b) => a - b)
    .map(round => ({ round, steps: byRound.get(round)! }))
}
