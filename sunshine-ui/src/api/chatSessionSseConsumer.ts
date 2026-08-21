import type { ChatMessage } from './chat'
import { hydrateStreamError, applyStreamErrorFromText } from './streamError'
import { stampTimelineEnded, stampTimelineStarted } from './timelineMessageClock'
import {
  saveActiveGeneration,
  updateLastSeq,
} from '../composables/useActiveGeneration'
import { ApiError } from './apiError'
import { drainSseBuffer, parseSseEvent } from './sseParse'
import { parseSsePayload, type SseMeta } from './sseDispatch'
import {
  mergeHitlIntoRunningToolStep,
  relocateAgentNodeHitl,
  applySyncedPendingHitl,
  getPendingHitlConfirmations,
  setPendingHitlConfirmations,
  upsertPendingHitlConfirmationList,
} from './hitlSteps'
import { upsertStep, applyStepDelta, findRunningStepId, isWorkflowNodeStepId } from './processingSteps'
import {
  reactivateOtherPausedWorkflowNodes,
  shouldIgnoreResumeStepReplay,
  shouldIgnoreResumeNodeStepReplay,
} from './processingStepsPause'
import {
  applyReactRestartSseGate,
  createReactRestartGate,
  shouldDropReactRestartSse,
  shouldDropReactRestartStream,
} from './reactRestartResume'
import {
  appendInterleavedContent,
  appendSegmentContent,
  appendStepSegmentContent,
  beginContentSegment,
  beginStepContentSegment,
  endContentSegment,
  endStepContentSegment,
  maybeReanchorContentBlocksToTail,
  stripPlanDrawerLeakFromMessage,
  syncPlanAnswerContentFromStep,
  normalizeRestoredInterleavedContent,
} from './contentInterleave'
import { notifyCompletedIfNeeded, notifyHitlIfNeeded } from './conversationAttentionNotify'
import { requestSandboxWorkspaceRefresh } from '../composables/sandboxWorkspaceRefresh'
import { resolveSandboxWorkspaceRefreshScope } from './sandboxWorkspaceRefreshPolicy'
import { appendChunk, getOrCreateSession, type SessionState } from './chatSessionRegistry'
import {
  scheduleAssistantMessageBump,
  flushAssistantMessageBump,
  updateNodeStepContent,
} from './chatSessionMutations'

export interface ChatSseStreamHooks {
  onChunk?: (sessionId: string, data: string) => void
  onProgress?: (sessionId: string) => void
}

export interface ChatSseStreamOptions {
  resume?: boolean
  reactRestart?: boolean
  resumeAtMs?: number
  onMeta?: (meta: SseMeta) => void
}

export async function consumeChatSseStream(
  s: SessionState,
  response: Response,
  hooks: ChatSseStreamHooks,
  options: ChatSseStreamOptions = {},
): Promise<void> {
  const reader = response.body?.getReader()
  if (!reader) throw new ApiError('服务响应异常，请稍后重试', { kind: 'parse' })

  const decoder = new TextDecoder()
  let buf = ''
  let streamConversationId = s.id
  let reactRestartGate = options.reactRestart
    ? createReactRestartGate(options.resumeAtMs ?? Date.now())
    : null

  while (true) {
    const { done, value } = await reader.read()
    if (value) {
      buf += decoder.decode(value, { stream: true })
    }    let { events, pending } = drainSseBuffer(done && buf.trim() ? `${buf}\n\n` : buf)
    buf = pending

    for (const rawEvent of events) {
      const { id: eventId, payload: data } = parseSseEvent(rawEvent)
      if (data === null) continue

      let eventSeq: number | null = null
      if (eventId) {
        const n = parseInt(eventId, 10)
        if (!Number.isNaN(n)) eventSeq = n
      }

      const parsed = parseSsePayload(data)
      if (parsed.kind === 'ignore') continue

      if (parsed.kind === 'meta') {
        options.onMeta?.(parsed.meta)
        if (parsed.meta.type === 'conversation' && parsed.meta.id) {
          streamConversationId = parsed.meta.id
        }
        if (parsed.meta.type === 'generation' && parsed.meta.id && parsed.meta.messageId) {
          const convId = streamConversationId ?? s.id
          if (convId) {
            const sess = getOrCreateSession(convId)
            sess.generationId = parsed.meta.id
            saveActiveGeneration({
              generationId: parsed.meta.id,
              messageId: parsed.meta.messageId,
              conversationId: convId,
              lastSeq: parsed.meta.seq ?? 0,
            })
          }
          const last = s.messages[s.messages.length - 1]
          if (last?.role === 'assistant') {
            last.id = parsed.meta.messageId
          }
        }
        if (parsed.meta.type === 'message' && parsed.meta.id) {
          const last = s.messages[s.messages.length - 1]
          if (last?.role === 'assistant') {
            last.id = parsed.meta.id
            if (parsed.meta.status) last.status = parsed.meta.status as ChatMessage['status']
          }
        }
        if (parsed.meta.type === 'message' && parsed.meta.status === 'completed') {
          const last = s.messages[s.messages.length - 1]
          if (last?.role === 'assistant') {
            last.status = 'completed'
            stampTimelineEnded(last)
            setPendingHitlConfirmations(last, undefined)
            normalizeRestoredInterleavedContent(last)
            notifyCompletedIfNeeded(streamConversationId ?? s.id, last)
            scheduleAssistantMessageBump(s)
          }
        }
        if (parsed.meta.type === 'message' && parsed.meta.status === 'interrupted') {
          const last = s.messages[s.messages.length - 1]
          if (last?.role === 'assistant') {
            last.status = 'interrupted'
            stampTimelineEnded(last)
            scheduleAssistantMessageBump(s)
          }
        }
        if (parsed.meta.type === 'message' && parsed.meta.status === 'failed') {
          const last = s.messages[s.messages.length - 1]
          if (last?.role === 'assistant') {
            last.status = 'failed'
            stampTimelineEnded(last)
            hydrateStreamError(last)
            if (!last.streamError) {
              last.streamError = '可点击下方继续生成重试'
            }
            scheduleAssistantMessageBump(s)
          }
        }
        continue
      }

      if (parsed.kind === 'error') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          applyStreamErrorFromText(lastMsg, parsed.text)
        }
        hooks.onProgress?.(s.id)
        continue
      }

      if (parsed.kind === 'usage') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          lastMsg.usage = parsed.usage
          scheduleAssistantMessageBump(s)
        }
        hooks.onProgress?.(s.id)
        continue
      }

      if (parsed.kind === 'reasoning') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        if (options.reactRestart && reactRestartGate
            && shouldDropReactRestartStream(reactRestartGate, { kind: 'reasoning' })) {
          hooks.onProgress?.(s.id)
          continue
        }
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          const runningId = findRunningStepId(lastMsg.steps ?? [])
          if (runningId) {
            lastMsg.steps = applyStepDelta(lastMsg.steps ?? [], {
              stepId: runningId,
              channel: 'reasoning',
              text: parsed.text,
            })
          }
          if (!isWorkflowNodeStepId(runningId)) {
            const prev = lastMsg.reasoning ?? ''
            lastMsg.reasoning = options.resume
              ? appendChunk(prev, parsed.text)
              : prev + parsed.text
          }
          scheduleAssistantMessageBump(s)
        }
        hooks.onProgress?.(s.id)
        continue
      }

      if (parsed.kind === 'step') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          if (options.reactRestart && reactRestartGate) {
            const gateEvent = { kind: 'step' as const, step: parsed.step }
            if (shouldDropReactRestartSse(reactRestartGate, gateEvent)) {
              hooks.onProgress?.(s.id)
              continue
            }
            reactRestartGate = applyReactRestartSseGate(reactRestartGate, gateEvent)
          }
          if (options.resume && shouldIgnoreResumeStepReplay(lastMsg.steps ?? [], parsed.step)) {
            hooks.onProgress?.(s.id)
            continue
          }
          if (options.resume && shouldIgnoreResumeNodeStepReplay(lastMsg.steps ?? [], parsed.step)) {
            hooks.onProgress?.(s.id)
            continue
          }
          lastMsg.steps = upsertStep(lastMsg.steps ?? [], parsed.step)
          maybeReanchorContentBlocksToTail(lastMsg.steps, lastMsg.contentBlocks)
          if (parsed.step.id === 'node-answer' && parsed.step.result?.trim()) {
            syncPlanAnswerContentFromStep(lastMsg)
          }
          if (options.resume && parsed.step.id.startsWith('node-')) {
            const lc = parsed.step.lifecycle
            if (lc === 'pending' || lc === 'running') {
              lastMsg.steps = reactivateOtherPausedWorkflowNodes(lastMsg.steps, parsed.step.id)
            }
          }
          lastMsg.steps = lastMsg.steps.map(st =>
            st.id.startsWith('node-') ? relocateAgentNodeHitl(st) : st,
          )
          const synced = applySyncedPendingHitl(lastMsg.steps, getPendingHitlConfirmations(lastMsg))
          lastMsg.steps = synced.steps
          setPendingHitlConfirmations(lastMsg, synced.pending)
          stripPlanDrawerLeakFromMessage(lastMsg)
          notifyHitlIfNeeded(streamConversationId ?? s.id, lastMsg)
          const refreshScope = resolveSandboxWorkspaceRefreshScope(parsed.step)
          if (refreshScope) {
            requestSandboxWorkspaceRefresh(streamConversationId ?? s.id, refreshScope)
          }
          flushAssistantMessageBump(s)
        }
        hooks.onProgress?.(s.id)
        continue
      }

      if (parsed.kind === 'step_delta') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        if (options.reactRestart && reactRestartGate
            && shouldDropReactRestartStream(reactRestartGate, {
              kind: 'step_delta',
              stepId: parsed.delta.stepId,
            })) {
          hooks.onProgress?.(s.id)
          continue
        }
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          let delta = parsed.delta
          if (delta.channel === 'reasoning' && delta.stepId === 'generate') {
            const steps = lastMsg.steps ?? []
            if (steps.some(st => st.id === 'think') || findRunningStepId(steps) === 'think') {
              delta = { ...delta, stepId: 'think' }
            }
          }
          lastMsg.steps = applyStepDelta(lastMsg.steps ?? [], delta)
          if (delta.stepId === 'node-answer' && (delta.channel === 'result' || delta.channel === 'output')) {
            syncPlanAnswerContentFromStep(lastMsg)
          }
          const isThinkStep = delta.stepId === 'think' || delta.stepId.startsWith('think-')
          const isNodeStep = isWorkflowNodeStepId(delta.stepId)
          if (delta.channel === 'reasoning' && delta.stepId !== 'agent' && !isThinkStep && !isNodeStep) {
            const prev = lastMsg.reasoning ?? ''
            lastMsg.reasoning = options.resume
              ? appendChunk(prev, delta.text)
              : prev + delta.text
          }
          // 原地改 step 字段后需 bump 才驱动 OperationStack（timelineRevision）；80ms 节流
          scheduleAssistantMessageBump(s)
        }
        hooks.onProgress?.(s.id)
        continue
      }

      if (parsed.kind === 'confirmation') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          if (options.reactRestart && reactRestartGate
              && shouldDropReactRestartSse(reactRestartGate, { kind: 'confirmation' })) {
            hooks.onProgress?.(s.id)
            continue
          }
          const prevSteps = lastMsg.steps ?? []
          const pendingList = upsertPendingHitlConfirmationList(
            getPendingHitlConfirmations(lastMsg),
            parsed.confirmation,
          )
          setPendingHitlConfirmations(lastMsg, pendingList)
          const merged = mergeHitlIntoRunningToolStep(prevSteps, parsed.confirmation)
          lastMsg.steps = (merged !== prevSteps ? merged : prevSteps).map(st =>
            st.id.startsWith('node-') ? relocateAgentNodeHitl(st) : st,
          )
          const synced = applySyncedPendingHitl(lastMsg.steps, getPendingHitlConfirmations(lastMsg))
          lastMsg.steps = synced.steps
          setPendingHitlConfirmations(lastMsg, synced.pending)
          stripPlanDrawerLeakFromMessage(lastMsg)
          notifyHitlIfNeeded(streamConversationId ?? s.id, lastMsg)
          flushAssistantMessageBump(s)
        }
        hooks.onProgress?.(s.id)
        continue
      }

      if (parsed.kind === 'content_start') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          if (parsed.nodeStepId) {
            lastMsg.steps = updateNodeStepContent(lastMsg.steps ?? [], parsed.nodeStepId, step => {
              beginStepContentSegment(step, parsed.segmentId, parsed.afterStepId)
            })
          } else {
            beginContentSegment(lastMsg, parsed.segmentId, parsed.afterStepId)
          }
          if (!lastMsg.status || lastMsg.status === 'interrupted') {
            lastMsg.status = 'streaming'
            stampTimelineStarted(lastMsg)
          }
          scheduleAssistantMessageBump(s)
        }
        hooks.onProgress?.(s.id)
        continue
      }

      if (parsed.kind === 'content_end') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          if (parsed.nodeStepId) {
            lastMsg.steps = updateNodeStepContent(lastMsg.steps ?? [], parsed.nodeStepId, step => {
              endStepContentSegment(step, parsed.segmentId)
            })
          } else {
            endContentSegment(lastMsg, parsed.segmentId)
          }
          scheduleAssistantMessageBump(s)
        }
        hooks.onProgress?.(s.id)
        continue
      }

      if (parsed.kind === 'chunk') {
        if (eventSeq !== null) updateLastSeq(eventSeq)
        if (options.reactRestart && reactRestartGate
            && shouldDropReactRestartStream(reactRestartGate, { kind: 'content' })) {
          hooks.onProgress?.(s.id)
          continue
        }
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          if (parsed.nodeStepId) {
            lastMsg.steps = updateNodeStepContent(lastMsg.steps ?? [], parsed.nodeStepId, step => {
              if (parsed.segmentId) {
                appendStepSegmentContent(step, parsed.segmentId, parsed.text, !!options.resume)
              }
            })
          } else if (parsed.segmentId) {
            appendSegmentContent(lastMsg, parsed.segmentId, parsed.text, !!options.resume)
          } else {
            appendInterleavedContent(lastMsg, parsed.text, parsed.afterStepId, !!options.resume)
          }
          if (!lastMsg.status || lastMsg.status === 'interrupted') {
            lastMsg.status = 'streaming'
            stampTimelineStarted(lastMsg)
          }
          stripPlanDrawerLeakFromMessage(lastMsg)
          scheduleAssistantMessageBump(s)
        }
        hooks.onChunk?.(s.id, parsed.text)
        hooks.onProgress?.(s.id)
        // resume 续连会回放已完成/积压的历史事件（量大），逐 chunk 等 rAF 会拖慢 catch-up，
        // 使任务已终态时 UI 仍长时间停在「正在处理」；bump 已有 80ms 节流兜底 UI 刷新，
        // 故 resume 场景跳过 rAF（实时流式仍在跑时也只是消费更快，不影响正确性）
        // 页面隐藏时 rAF 被浏览器挂起：仍逐 chunk 等 rAF 会卡住消费循环 → 停止读网络 →
        // TCP 背压使服务端 SSE 写阻塞，表现为后台标签页流式停滞；隐藏时无渲染节奏需求，直接消费
        if (!options.resume && document.visibilityState === 'visible') {
          await new Promise<void>(resolve => requestAnimationFrame(() => resolve()))
        }
        continue
      }

      if (eventSeq !== null) updateLastSeq(eventSeq)

      const lastMsg = s.messages[s.messages.length - 1]
      if (lastMsg?.role === 'assistant') {
        if (!lastMsg.status || lastMsg.status === 'interrupted') {
          lastMsg.status = 'streaming'
          stampTimelineStarted(lastMsg)
        }
        scheduleAssistantMessageBump(s)
      }

      hooks.onProgress?.(s.id)
      continue
    }

    if (events.length > 0) await new Promise(r => setTimeout(r, 0))

    if (done) {
      break
    }
  }
  flushAssistantMessageBump(s)
}
