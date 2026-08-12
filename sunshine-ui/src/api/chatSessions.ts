/**
 * 多会话聊天管理 —— 每对话独立 DOM 容器 + StreamMarkdownRenderer
 * 切换只是显示/隐藏容器，不销毁、不中断后台渲染
 */
import { ref, computed } from 'vue'
import type { ChatMessage } from './chat'
import {
  applyStreamError,
  hydrateStreamError,
  isAbortError,
  isPageUnloading,
} from './streamError'
import { stampTimelineEnded, stampTimelineStarted } from './timelineMessageClock'
import { apiHeaders, useAuthStore } from '../stores/authStore'
import {
  loadActiveGeneration,
  clearActiveGenerationIfMatch,
} from '../composables/useActiveGeneration'
import { resolveBffStreamBase } from './config'
import { isConversationNotFoundError, throwIfHttpError, throwIfNotEventStream } from './apiError'
import {
  applyHitlDecision as applyHitlDecisionToSteps,
  reapplyPendingHitl,
  getPendingHitlConfirmations,
  setPendingHitlConfirmations,
  removePendingHitlConfirmationList,
} from './hitlSteps'
import { applyRecoveryDecision as applyRecoveryDecisionToSteps } from './recoverySteps'
import {
  pauseRunningWorkflowNodes,
  reactivatePausedStepsForResume,
  reactivatePausedPlanHitlNodes,
  resetStepsForReactResume,
} from './processingStepsPause'
import { isExecutionRestartMessage, isReactAssistantMessage, resolveResumeMode } from './resumeMode'
import {
  clearSegmentIdRemap,
  joinedContentBlocks,
  normalizeRestoredInterleavedContent,
  pruneContentBlocksForReactResume,
  stripPlanDrawerLeakFromMessage,
} from './contentInterleave'
import { notifyCompletedIfNeeded } from './conversationAttentionNotify'
import {
  getOrCreateSession,
  getSessionRegistry,
  type SendOptions,
  type SessionState,
} from './chatSessionRegistry'
import { bumpAssistantMessage, flushAssistantMessageBump } from './chatSessionMutations'
import { consumeChatSseStream } from './chatSessionSseConsumer'
import { requestSandboxWorkspaceRefresh } from '../composables/sandboxWorkspaceRefresh'
import { getWriteHitlMode } from '../composables/useWriteHitlMode'

export type { SendOptions, SessionState } from './chatSessionRegistry'
export { appendChunk } from './chatSessionRegistry'

const API_BASE = () => resolveBffStreamBase()
const sessions = getSessionRegistry()

export function useChatSessions(
  onChunk?: (sessionId: string, data: string) => void,
  onSessionEnd?: (id: string) => void,
  onProgress?: (sessionId: string) => void,
  onConversationMeta?: (sessionId: string, convId: string) => void,
  onStaleConversation?: () => Promise<string | null>,
  onSandboxSession?: (sessionId: string, conversationId: string) => void,
  onConversationTitle?: (conversationId: string, title: string) => void,
) {
  const activeId = ref<string | null>(null)
  const sseHooks = { onChunk, onProgress }

  const activeSession = computed(() => {
    const id = activeId.value
    return id ? getOrCreateSession(id) : null
  })

  const messages = computed(() => {
    const session = activeSession.value
    if (!session) return [] as ChatMessage[]
    void session.streamRevision
    return session.messages as ChatMessage[]
  })
  const streamRevision = computed(() => activeSession.value?.streamRevision ?? 0)
  const loading = computed(() => activeSession.value?.loading ?? false)
  const activeContainer = computed(() => activeSession.value?.containerEl ?? null)
  /** 与 cancelSpawnSubagent / cancel 同源：session.generationId → active-generation 回退 */
  const generationId = computed(() => {
    const s = activeSession.value
    if (!s) return ''
    if (s.generationId) return s.generationId
    const stored = loadActiveGeneration()
    return stored?.conversationId === s.id ? stored.generationId : ''
  })

  function mountContainer(session: SessionState, parent: HTMLElement): void {
    if (session.mounted) return
    if (!session.containerEl.parentElement) {
      parent.appendChild(session.containerEl)
    }
    session.containerEl.style.display = ''
    session.mounted = true
  }

  function unmountContainer(session: SessionState): void {
    session.containerEl.style.display = 'none'
    session.mounted = false
  }

  function switchTo(id: string): void {
    if (activeId.value) {
      const old = sessions.get(activeId.value)
      if (old) unmountContainer(old)
    }
    activeId.value = id
  }

  function clearActive(): void {
    if (activeId.value) {
      const s = sessions.get(activeId.value)
      if (s) {
        s.abort?.abort()
        s.containerEl.remove()
        sessions.delete(activeId.value)
      }
      activeId.value = null
    }
  }

  function ensureActive(id: string): void {
    if (activeId.value !== id) switchTo(id)
  }

  async function send(content: string, conversationId?: string | null, options?: SendOptions): Promise<void> {
    const convId = conversationId ?? activeId.value
    if (!convId || !content.trim()) return

    ensureActive(convId)
    const s = activeSession.value
    if (!s || s.loading) return

    const pref = options?.executionPreference ?? 'auto'
    s.messages.push({ role: 'user', content, executionPreference: pref })
    s.loading = true
    s.generationId = undefined
    s.messages.push({ role: 'assistant', content: '', reasoning: '', steps: [], status: 'streaming' })
    stampTimelineStarted(s.messages[s.messages.length - 1])

    s.abort = new AbortController()
    const thisRequestId = ++s.requestId
    const sessionId = s.id
    onProgress?.(sessionId)

    try {
      const body: Record<string, string> = { content, conversationId: convId }
      if (pref !== 'auto') {
        body.executionPreference = pref
      }
      if (options?.workflowId) {
        body.workflowId = options.workflowId
      }
      if (options?.skillId) {
        body.skillId = options.skillId
      }
      if (options?.kbId) {
        body.kbId = options.kbId
      }
      if (options?.writeHitlMode) {
        body.writeHitlMode = options.writeHitlMode
      }
      // modelName：显式传（含空串清绑定）写入会话；未传则后端沿用已存值
      if (options?.modelName !== undefined && options.modelName !== null) {
        body.modelName = options.modelName
      }
      // 个人规则（soul）：用户级常量随请求透传，非单次发送选项，不入 SendOptions
      const personalRules = useAuthStore().user?.personalRules?.trim()
      if (personalRules) {
        body.personalRules = personalRules
      }

      const response = await fetch(`${API_BASE()}/api/chat/stream`, {
        method: 'POST',
        headers: { ...apiHeaders(), Accept: 'text/event-stream' },
        body: JSON.stringify(body),
        signal: s.abort.signal,
      })

      await throwIfNotEventStream(response)

      await consumeChatSseStream(s, response, sseHooks, {
        onMeta: (meta) => {
          if (meta.type === 'conversation' && meta.id) {
            onConversationMeta?.(sessionId, meta.id)
          }
          if (meta.type === 'sandbox_session' && meta.active !== false) {
            const cid = meta.conversationId || sessionId
            if (cid) {
              onSandboxSession?.(sessionId, cid)
              requestSandboxWorkspaceRefresh(cid, 'skills', true)
            }
          }
          if (meta.type === 'title' && meta.conversationId && meta.title) {
            onConversationTitle?.(meta.conversationId, meta.title)
          }
        },
      })
    } catch (err: unknown) {
      if (
        onStaleConversation
        && isConversationNotFoundError(err)
        && s.messages.length >= 2
        && s.messages[s.messages.length - 1]?.role === 'assistant'
        && s.messages[s.messages.length - 2]?.role === 'user'
      ) {
        s.messages.pop()
        s.messages.pop()
        try {
          const newConvId = await onStaleConversation()
          if (newConvId && newConvId !== convId) {
            switchTo(newConvId)
            return send(content, newConvId, options)
          }
        } catch (recoverErr) {
          applyStreamError(s.messages, recoverErr)
        }
      } else {
        applyStreamError(s.messages, err)
      }
      if (isAbortError(err) && !isPageUnloading()) {
        // 用户主动停止（stop()）：abort 前在途 chunk 会把 status 从 interrupted 改回 streaming，
        // 而 stop() 已 ++requestId 使 finally 失效，此处兜底落回 interrupted 并立即持久化
        const last = s.messages[s.messages.length - 1]
        if (last?.role === 'assistant' && last.status === 'streaming') {
          last.status = 'interrupted'
          stampTimelineEnded(last)
          flushAssistantMessageBump(s)
        }
        return
      }
      if (isAbortError(err) || isPageUnloading()) {
        return
      }
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        const last = s.messages[s.messages.length - 1]
        const aborted = s.abort?.signal.aborted ?? false
        if (last?.role === 'assistant' && last.status === 'streaming' && !aborted && !isPageUnloading()) {
          hydrateStreamError(last)
          last.status = last.streamError ? 'failed' : 'completed'
          stampTimelineEnded(last)
        }
        if (last?.role === 'assistant' && last.status === 'completed') {
          stampTimelineEnded(last)
          normalizeRestoredInterleavedContent(last)
          notifyCompletedIfNeeded(sessionId, last)
          clearActiveGenerationIfMatch(sessionId)
          s.generationId = undefined
        }
        onSessionEnd?.(sessionId)
      }
    }
  }

  async function cancelActiveGenerationForSession(s: SessionState): Promise<void> {
    const stored = loadActiveGeneration()
    const generationId = s.generationId
      ?? (stored?.conversationId === s.id ? stored.generationId : undefined)
    if (!generationId) return
    try {
      await fetch(`${API_BASE()}/api/generations/${generationId}/cancel`, {
        method: 'POST',
        headers: apiHeaders(),
      })
    } catch { /* fire and forget */ }
    clearActiveGenerationIfMatch(s.id)
    s.generationId = undefined
  }

  /** 单独取消一次 spawn_subagent（非整轮停止） */
  async function cancelSpawnSubagent(runId: string): Promise<void> {
    const s = activeSession.value
    if (!s || !runId?.trim()) return
    const stored = loadActiveGeneration()
    const generationId = s.generationId
      ?? (stored?.conversationId === s.id ? stored.generationId : undefined)
    if (!generationId) return
    const id = runId.trim().startsWith('subagent-')
      ? runId.trim().slice('subagent-'.length)
      : runId.trim()
    const stepId = `subagent-${id}`
    // 乐观：切 lifecycle + after 文案（与后端 SpawnSubagentLabels.afterCancel 一致）；SSE 终态会覆盖
    for (const msg of s.messages) {
      if (!msg.steps?.length) continue
      const idx = msg.steps.findIndex(st => st.id === stepId)
      if (idx < 0) continue
      const prev = msg.steps[idx]
      msg.steps[idx] = {
        ...prev,
        lifecycle: 'paused',
        summary: {
          before: prev.summary?.before,
          active: undefined,
          after: prev.summary?.after?.trim() || '已取消',
        },
        endedAt: prev.endedAt ?? Date.now(),
      }
      break
    }
    await fetch(
      `${API_BASE()}/api/generations/${generationId}/subagents/${encodeURIComponent(id)}/cancel`,
      {
        method: 'POST',
        headers: apiHeaders(),
      },
    )
  }

  /** 单独取消一次可取消沙箱工具（step.id = tool-sandbox__*@…） */
  async function cancelCancellableTool(stepId: string): Promise<void> {
    const s = activeSession.value
    if (!s || !stepId?.trim()) return
    const stored = loadActiveGeneration()
    const generationId = s.generationId
      ?? (stored?.conversationId === s.id ? stored.generationId : undefined)
    if (!generationId) return
    await fetch(
      `${API_BASE()}/api/generations/${generationId}/tools/${encodeURIComponent(stepId.trim())}/cancel`,
      {
        method: 'POST',
        headers: apiHeaders(),
      },
    )
  }

  async function resume(conversationId: string, resumeMessageId: string): Promise<void> {
    ensureActive(conversationId)
    const s = activeSession.value ?? getOrCreateSession(conversationId)
    if (s.loading) return

    const target = s.messages.find(m => m.id === resumeMessageId)
    if (!target || target.role !== 'assistant') return

    // 流式中 bumpAssistantMessage 会用浅拷贝替换 last 对象引用，闭包 target 会失效，
    // catch/finally 的终态判断须按 resumeMessageId 重查最新对象（见 reconnectStream 同款注释）。
    const resolveTarget = (): ChatMessage =>
      s.messages.find(m => m.id === resumeMessageId && m.role === 'assistant') ?? target

    const planWorkflowResume = target.steps?.some(
      step => step.id.startsWith('node-') && step.lifecycle === 'paused',
    )
    const executionRestart = !planWorkflowResume
      && resolveResumeMode(target) === 'regenerate'
      && isExecutionRestartMessage(target)
    const reactRestart = false
    if (planWorkflowResume) {
      target.content = ''
      target.reasoning = ''
      target.contentBlocks = undefined
      if (target.steps?.length) {
        target.steps = reactivatePausedPlanHitlNodes(target.steps)
        target.steps = reactivatePausedStepsForResume(target.steps)
      }
    } else if (executionRestart) {
      target.content = ''
      target.reasoning = ''
      target.contentBlocks = undefined
      setPendingHitlConfirmations(target, undefined)
      if (target.steps?.length) {
        target.steps = pauseRunningWorkflowNodes(target.steps)
        // intent 保留：续跑重新识别后由 SSE 覆盖（shouldIgnoreResumeStepReplay 忽略 pending/running 回退）
      }
    } else if (target.steps?.length) {
      // ReAct 续跑（reactRestart/checkpoint）：后端对复用 id 的步重放 running→done。
      // 暂停期被乐观标「已取消/已暂停」（paused + after 非空）的步在前端是 cancel-terminal 硬终态，
      // resolveMergedLifecycle 会挡住后端重放的 running/done → 卡死。恢复时统一重置这些步：
      // 解除终态保护（回 pending、清 after）、清旧半截 reasoning，让重放从空白干净落地。
      target.steps = resetStepsForReactResume(target.steps)
      // 与后端 truncateToLastCompleteThink 对齐：丢掉截断点之后的正文块，避免错位/重放重复
      target.contentBlocks = pruneContentBlocksForReactResume(target.contentBlocks, target.steps)
      // content 与 blocks 同 SSOT，避免裁块后 content 仍含半截/将被重放的正文
      target.content = joinedContentBlocks(target.contentBlocks)
      clearSegmentIdRemap(target.id)
      // 消息级 reasoning 同样残留旧流（综合分析等 step_delta(reasoning) 会经 appendChunk 叠加到
      // lastMsg.reasoning），一并清空，避免旧（英文）与新（中文）互相覆盖。
      target.reasoning = ''
    }
    stripPlanDrawerLeakFromMessage(target)
    if (executionRestart || planWorkflowResume) bumpAssistantMessage(s)

    s.loading = true
    target.status = 'streaming'
    s.abort = new AbortController()
    const thisRequestId = ++s.requestId
    onProgress?.(conversationId)

    const resumeAtMs = Date.now()
    try {
      await cancelActiveGenerationForSession(s)
      const response = await fetch(`${API_BASE()}/api/chat/stream`, {
        method: 'POST',
        headers: { ...apiHeaders(), Accept: 'text/event-stream' },
        body: JSON.stringify({
          conversationId,
          resumeMessageId,
          writeHitlMode: getWriteHitlMode(conversationId),
        }),
        signal: s.abort.signal,
      })

      await throwIfHttpError(response)
      await throwIfNotEventStream(response)

      await consumeChatSseStream(s, response, sseHooks, { resume: true, reactRestart, resumeAtMs })
    } catch (err: unknown) {
      applyStreamError(s.messages, err)
      const current = resolveTarget()
      if (current.status === 'streaming' && !isPageUnloading()) {
        // AbortError 分支同样兜底：stop() 已 ++requestId 使 finally 失效，
        // abort 前在途 chunk 会把 interrupted 改回 streaming，此处统一落终态
        current.status = !isAbortError(err) && current.streamError ? 'failed' : 'interrupted'
        stampTimelineEnded(current)
        flushAssistantMessageBump(s)
      }
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        const current = resolveTarget()
        const endStatus = current.status as ChatMessage['status']
        if (endStatus === 'streaming') {
          hydrateStreamError(current)
          current.status = current.streamError ? 'failed' : 'interrupted'
        } else if (endStatus === 'completed') {
          normalizeRestoredInterleavedContent(current)
          notifyCompletedIfNeeded(conversationId, current)
          clearActiveGenerationIfMatch(conversationId)
          s.generationId = undefined
        }
        onSessionEnd?.(conversationId)
      }
    }
  }

  async function reconnectStream(
    generationId: string,
    afterSeq: number,
    conversationId: string,
  ): Promise<void> {
    ensureActive(conversationId)
    const s = activeSession.value ?? getOrCreateSession(conversationId)
    if (s.loading) return

    const active = loadActiveGeneration()
    const messageId = active?.messageId

    let target = messageId
      ? s.messages.find(m => m.id === messageId && m.role === 'assistant')
      : s.messages[s.messages.length - 1]

    if (!target || target.role !== 'assistant') {
      target = { role: 'assistant', content: '', reasoning: '', steps: [], status: 'streaming', id: messageId }
      s.messages.push(target)
    }

    // 流式中 bumpAssistantMessage 用浅拷贝替换最后一条对象引用，闭包捕获的 target 会失效；
    // 终态判断必须按 messageId 重查 s.messages 里当前对象，否则 completed 落到新拷贝而
    // finally 读到旧引用的 streaming，误标 interrupted 且不清 active 锚点（重生成又被拒）。
    const resolveTarget = (): ChatMessage =>
      (messageId
        ? s.messages.find(m => m.id === messageId && m.role === 'assistant')
        : s.messages[s.messages.length - 1]) ?? target

    s.loading = true
    target.status = 'streaming'
    stampTimelineStarted(target)
    s.abort = new AbortController()
    const thisRequestId = ++s.requestId
    onProgress?.(conversationId)

    try {
      const response = await fetch(
        `${API_BASE()}/api/chat/stream/${generationId}?afterSeq=${afterSeq}`,
        { headers: { ...apiHeaders(), Accept: 'text/event-stream' }, signal: s.abort.signal },
      )

      if (response.status === 410) {
        clearActiveGenerationIfMatch(conversationId)
        s.generationId = undefined
        target.status = 'interrupted'
        stampTimelineEnded(target)
        return
      }

      if (!response.ok) await throwIfHttpError(response)

      await consumeChatSseStream(s, response, sseHooks, { resume: true })
    } catch (err: unknown) {
      const current = resolveTarget()
      if (err instanceof DOMException && err.name === 'AbortError') {
        if (current.status === 'streaming') {
          current.status = 'interrupted'
          stampTimelineEnded(current)
        }
        return
      }
      applyStreamError(s.messages, err)
      if (current.status === 'streaming') {
        current.status = current.streamError ? 'failed' : 'interrupted'
        stampTimelineEnded(current)
      }
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        // 正常结束必然收到 doneMeta 终态；若流结束仍停留在 streaming，说明 SSE 异常中断
        // （未收到终态），标 interrupted 交 tryAutoReconnect hydrate/用户重新生成恢复，
        // 勿乐观标 completed，避免「后端仍在跑/已终态」被前端误判为完成而丢失续连锚点
        const current = resolveTarget()
        const endStatus = current.status as ChatMessage['status']
        if (endStatus === 'streaming') {
          current.status = 'interrupted'
          stampTimelineEnded(current)
        } else if (endStatus === 'completed' || endStatus === 'interrupted' || endStatus === 'failed') {
          stampTimelineEnded(current)
        }
        if (endStatus === 'completed') {
          normalizeRestoredInterleavedContent(current)
          notifyCompletedIfNeeded(conversationId, current)
          clearActiveGenerationIfMatch(conversationId)
          s.generationId = undefined
        }
        onSessionEnd?.(conversationId)
      }
    }
  }

  async function stop(): Promise<void> {
    const s = activeSession.value
    if (!s) return

    // 先本地落终态 + 中断连接，再异步 cancel：cancel 响应慢会阻塞后续步骤，
    // 且 stop() 已 ++requestId 使 send/resume/reconnect 的 finally 失效，终态必须就地写死
    s.requestId++
    const last = s.messages[s.messages.length - 1]
    if (last?.role === 'assistant') {
      if (last.steps?.length) {
        last.steps = pauseRunningWorkflowNodes(last.steps)
      }
      stripPlanDrawerLeakFromMessage(last)
      if (last.status === 'streaming' || !last.status) {
        last.status = 'interrupted'
      }
      stampTimelineEnded(last)
    }
    s.abort?.abort()
    s.loading = false
    flushAssistantMessageBump(s)

    const stored = loadActiveGeneration()
    const generationId = s.generationId
      ?? (stored?.conversationId === s.id ? stored.generationId : undefined)
    if (generationId) {
      void fetch(`${API_BASE()}/api/generations/${generationId}/cancel`, {
        method: 'POST',
        headers: apiHeaders(),
      }).catch(() => { /* fire and forget */ })
    }
    if (stored?.conversationId === s.id) {
      clearActiveGenerationIfMatch(s.id)
    }
    s.generationId = undefined
    onProgress?.(s.id)
  }

  function clearSession(): void {
    const s = activeSession.value
    if (!s) return
    s.messages = []
    s.containerEl.innerHTML = ''
  }

  function getMessages(id: string): ChatMessage[] {
    return getOrCreateSession(id).messages
  }

  function setMessages(id: string, msgs: ChatMessage[]): void {
    getOrCreateSession(id).messages = msgs
  }

  function migrateSession(fromId: string, toId: string): void {
    if (!fromId || !toId || fromId === toId) return
    const from = sessions.get(fromId)
    if (!from) return
    const to = getOrCreateSession(toId)
    if (!to.messages.length && from.messages.length) {
      to.messages = from.messages
    }
    if (activeId.value === fromId) activeId.value = toId
    sessions.delete(fromId)
  }

  function destroySession(id: string): void {
    const s = sessions.get(id)
    if (s) {
      s.abort?.abort()
      s.containerEl.remove()
      sessions.delete(id)
    }
    if (activeId.value === id) activeId.value = null
  }

  function applyHitlDecision(token: string, approved: boolean): void {
    const s = activeSession.value
    if (!s) return
    for (let i = s.messages.length - 1; i >= 0; i--) {
      const msg = s.messages[i]
      if (msg.role !== 'assistant' || !msg.steps?.length) continue
      const next = applyHitlDecisionToSteps(
        msg.steps,
        token,
        approved,
        getPendingHitlConfirmations(msg),
      )
      if (next !== msg.steps) {
        msg.steps = next
        const remaining = removePendingHitlConfirmationList(getPendingHitlConfirmations(msg), token)
        setPendingHitlConfirmations(msg, remaining.length ? remaining : undefined)
        bumpAssistantMessage(s)
        onProgress?.(s.id)
        return
      }
    }
  }

  function applyRecoveryDecision(token: string, action: 'retry' | 'terminate' | 'skip'): void {
    const s = activeSession.value
    if (!s) return
    for (let i = s.messages.length - 1; i >= 0; i--) {
      const msg = s.messages[i]
      if (msg.role !== 'assistant' || !msg.steps?.length) continue
      const next = applyRecoveryDecisionToSteps(msg.steps, token, action)
      if (next !== msg.steps) {
        msg.steps = next
        onProgress?.(s.id)
        return
      }
    }
  }

  return {
    messages, streamRevision, loading, activeContainer, generationId,
    switchTo, clearActive, ensureActive, send, resume, reconnectStream, stop, clearSession,
    cancelSpawnSubagent,
    cancelCancellableTool,
    getMessages, setMessages, destroySession, migrateSession,
    mountContainer, unmountContainer, getOrCreate: getOrCreateSession, applyHitlDecision, applyRecoveryDecision,
  }
}
