import { upsertStep, applyStepDelta } from './src/api/processingSteps'
import { isHarnessTimelineMessage } from './src/api/harnessTimeline'
import { isHiddenReactTimelineStep } from './src/api/contentInterleave'
import { sortSteps } from './src/api/processingStepsNormalize'
import fs from 'fs'

const raw = fs.readFileSync('/usr/local/gitproj/my-sunshine-agent/.vscode/1.txt', 'utf8')
let steps: any[] = []
let stepCount = 0, deltaCount = 0, contentCount = 0
for (const line of raw.split('\n')) {
  if (!line.startsWith('data:')) continue
  const p = line.slice(5).trim()
  if (!p) continue
  let ev: any
  try { ev = JSON.parse(p) } catch { continue }
  if (ev.type === 'step') {
    steps = upsertStep(steps, ev)
    stepCount++
  } else if (ev.type === 'step_delta') {
    steps = applyStepDelta(steps, { stepId: ev.stepId, channel: ev.channel, text: ev.text })
    deltaCount++
  } else if (ev.type === 'content') {
    contentCount++
  }
}
console.log(`steps=${stepCount} deltas=${deltaCount} content=${contentCount}`)
console.log('\n最终 steps（ChatView sortSteps 前）:')
for (const s of steps) {
  console.log(`  ${s.id} phase=${s.phase} lc=${s.lifecycle} reasoning=${s.reasoning?.length ?? 0} subSteps=${(s.subSteps ?? []).length}`)
}
const sorted = sortSteps(steps)
console.log('\nsortSteps 后:')
for (const s of sorted) {
  console.log(`  ${s.id} phase=${s.phase} lc=${s.lifecycle}`)
}
console.log('\nisHarnessTimelineMessage:', isHarnessTimelineMessage(sorted, undefined))
console.log('hidden steps:', sorted.filter(isHiddenReactTimelineStep).map(s => s.id))
const worker = sorted.filter(s => s.phase === 'worker')
console.log(`\nworker 步: ${worker.length} 个`)
for (const w of worker) {
  console.log(`  ${w.id} lc=${w.lifecycle} subSteps=[${(w.subSteps ?? []).map(x => x.id).join(',')}]`)
}
