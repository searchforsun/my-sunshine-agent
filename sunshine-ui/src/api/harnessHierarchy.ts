/** harness 分层时间线：plan -> worker 行分组（worker 不缩进，与工具折叠一致平铺） */

export function isWorkerStep(step: { id?: string; phase?: string }): boolean {
  if (step.phase === 'worker') return true
  // id 前缀 worker-（worker 卡主步；handoff 子步已下线，无需排除）
  return !!step.id?.startsWith('worker-')
}

/** harness 规划步：phase=plan 或 id 为 plan / plan-R{n} */
export function isHarnessPlanStep(step: { id?: string; phase?: string }): boolean {
  if (step.phase === 'plan') return true
  const id = step.id ?? ''
  return id === 'plan' || /^plan-R\d+$/.test(id)
}
