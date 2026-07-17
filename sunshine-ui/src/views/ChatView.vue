<script setup lang="ts">
import { computed, nextTick, ref, watch, onMounted, onUnmounted, onUpdated, provide, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatSessions } from '../api/chatSessions'
import { createMarkdownIt } from '../utils/markdown/createMarkdownIt'
import 'katex/dist/katex.min.css'
import '../utils/stream-markdown/styles.css'
import { registerHljsLanguages } from '../utils/markdown/registerHljsLanguages'
import { useChatTimelineView } from '../composables/useChatTimelineView'
import { useChatScroll } from '../composables/useChatScroll'
import { useChatSkillMention } from '../composables/useChatSkillMention'
import { useChatExpertMention } from '../composables/useChatExpertMention'
import { useChatWorkflowMention } from '../composables/useChatWorkflowMention'
import { useChatStreamMarkdown } from '../composables/useChatStreamMarkdown'
import { useChatSessionHydration } from '../composables/useChatSessionHydration'
import { useChatStore } from '../stores/chatStore'
import { isValidConversationId } from '../api/conversations'
import { useTheme } from '../composables/useTheme'
import { useSidebar } from '../composables/useSidebar'
import { loadActiveGeneration } from '../composables/useActiveGeneration'
import OperationStack from '../components/operation/OperationStack.vue'
import PlanNodeDrawer from '../components/plan/PlanNodeDrawer.vue'
import SandboxWorkspaceDrawer from '../components/sandbox/SandboxWorkspaceDrawer.vue'
import PlanDagExpandLayer from '../components/plan/PlanDagExpandLayer.vue'
import { usePlanNodeDrawer } from '../composables/usePlanNodeDrawer'
import { useSandboxWorkspaceDrawer } from '../composables/useSandboxWorkspaceDrawer'
import { getWriteHitlMode } from '../composables/useWriteHitlMode'
import { usePlanDagExpand } from '../composables/usePlanDagExpand'
import { fetchSandboxWorkspaceStatus } from '../api/sandboxWorkspace'
import type { ChatMessage } from '../api/chat'
import { resumeButtonLabel, resolveResumeMode } from '../api/resumeMode'
import { resolveAssistantDisplayContent, resolveStreamErrorText } from '../api/streamError'
import {
  isContentFullyInterleaved,
  resolveStreamingContentText,
  shouldShowAssistantBottomContent,
} from '../api/contentInterleave'
import { resolveAgentNodeStepForDrawer, getPendingHitlConfirmations } from '../api/hitlSteps'
import ExecutionModeSelector from '../components/chat/ExecutionModeSelector.vue'
import KbSelector from '../components/knowledge/KbSelector.vue'
import ComposerSkillInput from '../components/chat/ComposerSkillInput.vue'
import UserMessageContent from '../components/chat/UserMessageContent.vue'
import SidebarToggle from '../components/SidebarToggle.vue'
import { useExecutionPreference } from '../composables/useExecutionPreference'
import { useKbPreference } from '../composables/useKbPreference'
import { listKbs, type KnowledgeBase } from '../api/ragAdmin'
import { useTenantPreference } from '../composables/useTenantPreference'
import { allowsExpertMention, allowsSkillMention, allowsWorkflowMention } from '../api/executionModes'
import { resolveSkillBindingForSend } from '../utils/skillMention'
import { resolveWorkflowBindingForSend } from '../utils/workflowMention'
import { reRenderStaticMermaids } from '../utils/stream-markdown/StaticEnhancer'
import { useConversationAttention } from '../composables/useConversationAttention'
import { useConversationSidebarIndicator } from '../composables/useConversationSidebarIndicator'
import { useChatViewport } from '../composables/useChatViewport'

const sessionHydrating = ref(true)
const hljs = registerHljsLanguages()
const md = createMarkdownIt(hljs)
const chatBodyRef = ref<HTMLElement | null>(null)
const streamMdBridge = shallowRef<ReturnType<typeof useChatStreamMarkdown> | null>(null)
const hydrationBridge = {
  flushPersist: (_sessionId?: string | null) => {},
  schedulePersist: (_sessionId: string) => {},
}

const chatStore = useChatStore()
const route = useRoute()
const router = useRouter()
const { theme, toggle: toggleTheme } = useTheme()
const isDark = computed(() => theme.value === 'dark')
const { sidebarVisible } = useSidebar()
const { close: closePlanDrawer, registerChatBody } = usePlanNodeDrawer()
const {
  open: openSandboxDrawer,
  close: closeSandboxDrawer,
  registerChatBody: registerSandboxChatBody,
} = useSandboxWorkspaceDrawer()
const sandboxWorkspaceActive = ref(false)

async function openWorkspaceDrawer() {
  const id = currentConversationId.value
  if (!id) return
  openSandboxDrawer({ conversationId: id })
  sandboxWorkspaceActive.value = true
}
const { state: planDagExpandState, isAnyExpanded: planDagExpanded, close: closePlanDagExpand, handleSelect: handlePlanDagExpandSelect } = usePlanDagExpand()
const sessionTitle = computed(() => chatStore.current?.title || '新对话')
const currentConversationId = computed(() => chatStore.currentId)

const {
  clearAttention,
  consumeScrollRequest,
} = useConversationAttention()
const { resolveIndicator } = useConversationSidebarIndicator()
const {
  setChatRouteActive,
  setActiveConversation,
  setScrollPinned,
} = useChatViewport()

const {
  messages, streamRevision, loading, send, resume, reconnectStream, stop,
  ensureActive, getMessages, setMessages, migrateSession, destroySession,
  applyHitlDecision,
  applyRecoveryDecision,
} = useChatSessions(
  (sid: string, _chunk: string) => {
    if (sid !== chatStore.currentId) return
    const last = messages.value[messages.value.length - 1]
    if (last?.role !== 'assistant') return
    if (isContentFullyInterleaved(last)) return
    const bridge = streamMdBridge.value
    if (!bridge) return
    const apply = () => bridge.syncStreamFromContent(resolveStreamingContentText(last))
    void bridge.ensureStreamRenderer().then(apply)
  },
  (id: string) => {
    hydrationBridge.flushPersist(id)
    chatStore.syncMessages(id, getMessages(id))
  },
  (sessionId: string) => {
    chatStore.syncMessages(sessionId, getMessages(sessionId))
    if (sessionId === chatStore.currentId) {
      hydrationBridge.schedulePersist(sessionId)
    }
  },
  (sid: string, convId: string) => {
    if (convId !== sid) migrateSession(sid, convId)
    if (sid === chatStore.currentId || convId === chatStore.currentId) {
      chatStore.setConversationIdFromStream(convId)
      setMessages(convId, [...getMessages(convId)])
    }
  },
  () => chatStore.recoverAfterStaleConversation(),
  (_sid: string, convId: string) => {
    if (convId === chatStore.currentId || _sid === chatStore.currentId) {
      sandboxWorkspaceActive.value = true
    }
  },
)

const {
  scrollRef,
  chatScrollPinned,
  forceChatScroll,
  onChatScroll,
  scrollToBottom,
  pinScrollForSend,
  forwardWheelToChatScroll,
  syncScrollPinned,
} = useChatScroll(loading)

watch(chatScrollPinned, pinned => {
  setScrollPinned(pinned)
  const cid = chatStore.currentId
  if (pinned && cid) clearAttention(cid)
})

const attentionBubble = computed(() => {
  const cid = chatStore.currentId
  if (!cid || chatScrollPinned.value) return null
  void streamRevision.value
  const ind = resolveIndicator(cid, chatStore.current?.messages)
  if (ind !== 'hitl_pending' && ind !== 'completed') return null
  return ind === 'hitl_pending'
    ? { kind: ind, text: '待确认' }
    : { kind: ind, text: '新回复' }
})

function handleAttentionBubbleClick(): void {
  const cid = chatStore.currentId
  if (!cid) return
  chatScrollPinned.value = true
  setScrollPinned(true)
  scrollToBottom(true)
  clearAttention(cid)
}

function scrollToBottomIfRequested(convId: string): void {
  if (!consumeScrollRequest(convId)) return
  chatScrollPinned.value = true
  setScrollPinned(true)
  void nextTick(() => scrollToBottom(true))
}

const {
  resolveTimelineContext,
  resolveUserQuery,
  showTimeline,
  operationStackKey,
  isTimelineLive,
  showStreamWaiting,
} = useChatTimelineView(messages, loading)

const markdown = useChatStreamMarkdown(
  md,
  messages,
  loading,
  currentConversationId,
  scrollToBottom,
  forceChatScroll,
)
streamMdBridge.value = markdown
const {
  settledHtml,
  sessionSettledHtml,
  setStreamingMdRef,
  renderAssistantHtml,
  enhanceAllStaticMarkdown,
  ensureStreamRenderer,
  clearStreamRenderer,
  cacheSettledHtmlForConversation,
  captureSettledAssistantHtml,
} = markdown

const {
  schedulePersist,
  flushPersist,
  hydrateSessionFromStore,
  tryAutoReconnect,
  syncSessionToStore,
  flushAllOnPageHide,
} = useChatSessionHydration({
  chatStore,
  loading,
  getMessages,
  setMessages,
  reconnectStream,
  captureSettledAssistantHtml,
  resolveAssistantDisplayContent,
  settledHtml,
  sessionSettledHtml,
  ensureStreamRenderer,
  scrollToBottom,
  enhanceAllStaticMarkdown,
})
hydrationBridge.flushPersist = flushPersist
hydrationBridge.schedulePersist = schedulePersist

function shouldShowBottomContent(msg: ChatMessage, _idx: number): boolean {
  return shouldShowAssistantBottomContent(msg)
}

function isInterleavedStreaming(msg: ChatMessage, idx: number): boolean {
  return loading.value && idx === messages.value.length - 1 && isContentFullyInterleaved(msg)
}

const streamingHasContent = computed(() => {
  if (!loading.value) return false
  const last = messages.value[messages.value.length - 1]
  return last?.role === 'assistant' && !!resolveStreamingContentText(last).trim()
})

function isPendingAutoReconnect(msg: ChatMessage, idx: number): boolean {
  if (msg.role !== 'assistant' || idx !== messages.value.length - 1) return false
  const active = loadActiveGeneration()
  const cid = chatStore.currentId
  if (!active || active.conversationId !== cid) return false
  if (active.messageId && msg.id && active.messageId !== msg.id) return false
  return true
}

const latestAssistantMessage = computed(() => {
  const msgs = messages.value
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].role === 'assistant') return msgs[i]
  }
  return undefined
})

function handleHitlDecision(token: string, approved: boolean) {
  applyHitlDecision(token, approved)
}

provide('applyHitlDecision', handleHitlDecision)
provide('applyRecoveryDecision', applyRecoveryDecision)
provide('pendingHitlConfirmations', computed(() => getPendingHitlConfirmations(latestAssistantMessage.value)))
provide('pendingHitlConfirmation', computed(() => getPendingHitlConfirmations(latestAssistantMessage.value)[0]))
provide('planDrawerLiveNodeStep', (nodeId: string) =>
  resolveAgentNodeStepForDrawer(
    latestAssistantMessage.value?.steps,
    nodeId,
    getPendingHitlConfirmations(latestAssistantMessage.value),
  ),
)

const inputText = ref('')
const { preference, setPreference, applyConversationPreference } = useExecutionPreference()
const { kbId, setKbId, applyConversationKb } = useKbPreference()
const { tenantId } = useTenantPreference()
const chatKbs = ref<KnowledgeBase[]>([])
const loadingChatKbs = ref(false)

async function loadChatKbs() {
  loadingChatKbs.value = true
  try {
    chatKbs.value = await listKbs(tenantId.value)
    if (!kbId.value) {
      const def = chatKbs.value.find((k) => k.isDefault) ?? chatKbs.value[0]
      if (def) setKbId(def.kbId)
    }
  } catch (e) {
    console.warn('[ChatView] 加载知识库列表失败', e)
  } finally {
    loadingChatKbs.value = false
  }
}

function onKbChange(next: string) {
  setKbId(next)
  const convId = chatStore.currentId
  if (convId) chatStore.updateKbIdLocal(convId, next)
}
const {
  inputRef,
  skillCatalog,
  showSkillSuggest,
  skillSuggestIndex,
  filteredSkills,
  skillMentionAllowed,
  applySkillSuggest,
  loadSkillCatalog,
  handleSkillKeydown,
} = useChatSkillMention(inputText, preference, loading)

const {
  showExpertSuggest,
  expertSuggestIndex,
  filteredExperts,
  expertCatalog,
  expertMentionAllowed,
  applyExpertSuggest,
  loadExpertCatalog,
  handleExpertKeydown,
} = useChatExpertMention(inputText, preference, loading)

const {
  showWorkflowSuggest,
  workflowSuggestIndex,
  filteredWorkflows,
  workflowCatalog,
  workflowMentionAllowed,
  applyWorkflowSuggest,
  loadWorkflowCatalog,
  handleWorkflowKeydown,
} = useChatWorkflowMention(inputText, preference, loading)

const composerPlaceholder = computed(() => {
  const parts = ['发消息，Enter 发送']
  if (allowsSkillMention(preference.value)) parts.push('输入 @ 指定 Skill')
  if (allowsWorkflowMention(preference.value)) parts.push('输入 # 指定工作流')
  if (allowsExpertMention(preference.value)) parts.push('输入 $ 指定专家')
  return parts.join('；')
})

const EMPTY_HINTS = [
  { label: '制度检索', prompt: '检索知识库：公司的差旅报销制度有哪些要点？' },
  { label: '知识库问答', prompt: '#knowledge-qa 年假可以请几天' },
  { label: '报销分析', prompt: '#finance-smart 待审批报销是否合规' },
  { label: 'Skill 合规', prompt: '@compliance-check 对照制度审查一笔差旅报销是否合规' },
] as const

function applyEmptyHint(prompt: string) {
  inputText.value = prompt
  void handleSend()
}

const copiedIndex = ref<number | null>(null)
let copyResetTimer: ReturnType<typeof setTimeout> | null = null

function canCopyAssistant(msg: { role: string; content: string }, idx: number): boolean {
  return msg.role === 'assistant'
    && !!msg.content.trim()
    && !(loading.value && idx === messages.value.length - 1)
}

async function copyAssistantMessage(text: string, idx: number) {
  if (!text.trim()) return
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
  copiedIndex.value = idx
  if (copyResetTimer) clearTimeout(copyResetTimer)
  copyResetTimer = setTimeout(() => { copiedIndex.value = null }, 2000)
}

function canResume(msg: { role: string; status?: string; intent?: string; id?: string }, idx: number): boolean {
  if (sessionHydrating.value) return false
  if (isPendingAutoReconnect(msg as ChatMessage, idx)) return false
  return msg.role === 'assistant'
    && !loading.value
    && idx === messages.value.length - 1
    && (msg.status === 'interrupted' || msg.status === 'failed')
    && msg.intent !== 'knowledge'
}

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  try {
    const convId = await chatStore.ensureCurrent()
    ensureActive(convId)
    if (messages.value.length === 0) chatStore.updateTitle(convId, text)
    pinScrollForSend()
    setScrollPinned(true)
    clearAttention(convId)
    inputText.value = ''
    settledHtml.value = ''
    sessionSettledHtml.delete(convId)
    clearStreamRenderer()
    await nextTick()
    const skillBinding = resolveSkillBindingForSend(text, skillCatalog.value, preference.value)
    const workflowBinding = resolveWorkflowBindingForSend(text, workflowCatalog.value, preference.value)
    const sendPromise = send(text, convId, {
      executionPreference: preference.value,
      skillId: skillBinding.skillId,
      workflowId: workflowBinding.workflowId,
      kbId: kbId.value,
      writeHitlMode: getWriteHitlMode(convId),
    })
    chatStore.updateExecutionPreferenceLocal(convId, preference.value)
    if (kbId.value) chatStore.updateKbIdLocal(convId, kbId.value)
    await nextTick()
    scrollToBottom(true)
    await ensureStreamRenderer()
    await sendPromise
    await nextTick()
    scrollToBottom(true)
  } catch (e) {
    console.error('[ChatView] 发送失败', e)
    inputText.value = text
  }
}

async function handleResume() {
  const last = messages.value[messages.value.length - 1]
  const convId = chatStore.currentId
  if (!last?.id || !convId || loading.value) return
  pinScrollForSend()
  setScrollPinned(true)
  if (convId) clearAttention(convId)
  settledHtml.value = ''
  sessionSettledHtml.delete(convId)
  await nextTick()
  const resumeMode = resolveResumeMode(last)
  const resumePromise = resume(convId, last.id)
  await nextTick()
  scrollToBottom(true)
  if (resumeMode === 'regenerate') await ensureStreamRenderer()
  await resumePromise
  await nextTick()
  scrollToBottom(true)
}

function handleKeydown(e: KeyboardEvent) {
  if (handleWorkflowKeydown(e)) return
  if (handleExpertKeydown(e)) return
  handleSkillKeydown(e, () => { void handleSend() })
}

function applyChatDeepLink() {
  const wf = typeof route.query.workflow === 'string' ? route.query.workflow.trim() : ''
  const prompt = typeof route.query.prompt === 'string' ? route.query.prompt : ''
  if (wf) {
    setPreference('workflow')
    inputText.value = prompt.trim() ? prompt : `#${wf} `
  } else if (prompt.trim()) {
    inputText.value = prompt
  }
  if (wf || prompt) {
    const nextQuery = { ...route.query }
    delete nextQuery.workflow
    delete nextQuery.prompt
    void router.replace({ query: nextQuery })
  }
}

onMounted(async () => {
  setChatRouteActive(true)
  void loadSkillCatalog()
  void loadExpertCatalog()
  void loadWorkflowCatalog()
  sessionHydrating.value = true
  try {
    await chatStore.init()
    const active = loadActiveGeneration()
    let cid: string
    if (active?.conversationId) {
      try {
        await chatStore.ensureConversation(active.conversationId)
        cid = active.conversationId
      } catch {
        cid = await chatStore.ensureCurrent()
      }
    } else {
      cid = await chatStore.ensureCurrent()
    }
    await chatStore.switchTo(cid)
    applyConversationPreference(chatStore.current?.executionPreference)
    applyConversationKb(chatStore.current?.kbId)
    void loadChatKbs()
    ensureActive(cid)
    const pendingReconnect = !!(active?.conversationId === cid)
    await hydrateSessionFromStore(cid, { skipApiLoad: pendingReconnect })
    if (active && active.conversationId === cid) {
      await tryAutoReconnect(cid, active)
      syncSessionToStore(cid)
    }
  } finally {
    sessionHydrating.value = false
  }
  setActiveConversation(chatStore.currentId)
  scrollToBottomIfRequested(chatStore.currentId ?? '')
  applyChatDeepLink()
  inputRef.value?.focus()
  window.addEventListener('pagehide', flushAllOnPageHide)
})

onUnmounted(() => {
  setChatRouteActive(false)
  setActiveConversation(null)
  window.removeEventListener('pagehide', flushAllOnPageHide)
  registerChatBody(null)
  registerSandboxChatBody(null)
})

watch(chatBodyRef, (el) => {
  registerChatBody(el)
  registerSandboxChatBody(el)
}, { immediate: true })
onUpdated(() => { nextTick(() => enhanceAllStaticMarkdown()) })
watch(theme, () => nextTick(() => reRenderStaticMermaids()))

watch(() => chatStore.currentId, async (newId, oldId) => {
  if (sessionHydrating.value || newId === oldId) return
  setActiveConversation(newId)
  closePlanDrawer()
  closeSandboxDrawer()
  closePlanDagExpand()
  sandboxWorkspaceActive.value = false
  if (newId) {
    try {
      sandboxWorkspaceActive.value = await fetchSandboxWorkspaceStatus(newId)
    } catch {
      sandboxWorkspaceActive.value = false
    }
  }
  if (oldId) {
    chatStore.syncMessages(oldId, getMessages(oldId))
    cacheSettledHtmlForConversation(oldId)
    if (!chatStore.conversations.some(c => c.id === oldId)) {
      destroySession(oldId)
      sessionSettledHtml.delete(oldId)
    }
  }
  clearStreamRenderer()
  settledHtml.value = ''
  if (!isValidConversationId(newId)) return
  ensureActive(newId)
  applyConversationPreference(chatStore.current?.executionPreference)
  applyConversationKb(chatStore.current?.kbId)
  if (!loading.value) await hydrateSessionFromStore(newId)
  await nextTick()
  if (loading.value) void ensureStreamRenderer()
  if (newId) scrollToBottomIfRequested(newId)
}, { flush: 'post' })

watch(
  () => streamRevision.value,
  async () => {
    if (!loading.value) return
    await nextTick()
    scrollToBottom(false)
    syncScrollPinned()
  },
)

watch(
  () => {
    const last = messages.value[messages.value.length - 1]
    if (last?.role !== 'assistant') return 0
    return (last.content?.length ?? 0) + (last.reasoning?.length ?? 0)
  },
  async () => {
    if (!loading.value) return
    await nextTick()
    scrollToBottom(false)
    syncScrollPinned()
  },
)
</script>
<template>
  <div class="chat-page">
    <!-- 全宽会话头（豆包式） -->
    <header class="chat-header">
      <SidebarToggle />
      <div class="header-inner">
        <h1 class="header-title">{{ sessionTitle }}</h1>
        <span v-if="loading" class="header-status">
          <span class="typing-dots"><span class="dot"/><span class="dot"/><span class="dot"/></span>
          正在回复
        </span>
      </div>
      <button
        v-if="!sidebarVisible"
        type="button"
        class="header-theme-btn"
        :title="isDark ? '切换浅色模式' : '切换深色模式'"
        @click="toggleTheme"
      >
        <svg v-if="isDark" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <circle cx="12" cy="12" r="5" /><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
        </svg>
        <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
        </svg>
      </button>
    </header>

    <div ref="chatBodyRef" class="chat-body">
      <div class="chat-main" :class="{ 'plan-dag-expanded': planDagExpanded }">
    <!-- 消息区 -->
    <div ref="scrollRef" class="chat-scroll" @scroll="onChatScroll">
      <div class="chat-inner">
        <div v-if="chatStore.initializing && messages.length === 0" class="empty-state">
          <div class="empty-icon">
            <svg width="40" height="40" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="14" stroke="currentColor" stroke-width="1.2" opacity="0.35" />
              <circle cx="24" cy="24" r="5" fill="currentColor" opacity="0.5" />
            </svg>
          </div>
          <h2 class="empty-title">正在加载对话…</h2>
        </div>
        <div v-else-if="messages.length === 0" class="empty-state">
          <div class="empty-icon">
            <svg width="40" height="40" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="14" stroke="currentColor" stroke-width="1.2" opacity="0.35" />
              <circle cx="24" cy="24" r="5" fill="currentColor" opacity="0.5" />
            </svg>
          </div>
          <h2 class="empty-title">有什么可以帮你的？</h2>
          <p class="empty-desc">知识库检索 · ReAct 工具 · Plan 动态规划 · Skill @ 触发</p>
          <div class="hint-chips">
            <button
              v-for="hint in EMPTY_HINTS"
              :key="hint.label"
              type="button"
              class="hint-chip"
              @click="applyEmptyHint(hint.prompt)"
            >
              {{ hint.label }}
            </button>
          </div>
        </div>

        <div v-else class="msg-list">
          <div
            v-for="(msg, idx) in messages"
            :key="msg.id ?? `local-${idx}`"
            class="msg-block"
            :class="msg.role"
          >
            <!-- 用户消息：右对齐气泡 -->
            <div v-if="msg.role === 'user'" class="user-bubble">
              <UserMessageContent
                :content="msg.content"
                :catalog="skillCatalog"
                :expert-catalog="expertCatalog"
                :workflow-catalog="workflowCatalog"
                :execution-preference="msg.executionPreference"
              />
            </div>

            <!-- AI 消息：全宽左对齐 -->
            <div v-else class="assistant-body">
              <OperationStack
                v-if="showTimeline(msg, idx)"
                :key="operationStackKey(msg, idx)"
                :steps="resolveTimelineContext(msg).steps"
                :content-blocks="msg.contentBlocks"
                :stream-live="isInterleavedStreaming(msg, idx)"
                :timeline-revision="loading && idx === messages.length - 1 ? streamRevision : 0"
                :live="isTimelineLive(msg, idx)"
                :execution-plan-id="msg.executionPlanId"
                :user-query="resolveUserQuery(idx)"
                :message-id="msg.id"
                :pending-hitl-confirmations="resolveTimelineContext(msg).pending"
                @hitl-decided="handleHitlDecision"
              />
              <template v-if="loading && idx === messages.length - 1 && msg.status !== 'completed'">
                <div v-if="showStreamWaiting" class="stream-waiting-dots" aria-label="正在生成">
                  <span class="typing-dots"><span class="dot"/><span class="dot"/><span class="dot"/></span>
                </div>
                <div
                  v-if="streamingHasContent && !isInterleavedStreaming(msg, idx)"
                  :ref="setStreamingMdRef"
                  class="msg-md streaming"
                />
              </template>
              <div
                v-else-if="shouldShowBottomContent(msg, idx)"
                class="msg-md"
                v-html="renderAssistantHtml(msg, idx)"
              />
              <div v-if="canCopyAssistant(msg, idx)" class="msg-copy-bar">
                <button
                  type="button"
                  class="msg-copy-btn smd-toolbtn"
                  :title="copiedIndex === idx ? '已复制' : '复制'"
                  @click="copyAssistantMessage(msg.content, idx)"
                >
                  <svg v-if="copiedIndex === idx" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                    <rect x="9" y="9" width="13" height="13" rx="2" />
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                  </svg>
                </button>
              </div>
              <p
                v-if="resolveStreamErrorText(msg)"
                class="msg-stream-error"
              >
                发生错误：{{ resolveStreamErrorText(msg) }}
              </p>
              <div v-if="canResume(msg, idx)" class="msg-resume-bar">
                <button type="button" class="resume-btn" @click="handleResume">{{ resumeButtonLabel(msg) }}</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <PlanDagExpandLayer
      v-if="planDagExpanded && planDagExpandState.graph"
      :graph="planDagExpandState.graph"
      :nodes="planDagExpandState.nodes"
      :selected-id="planDagExpandState.selectedId"
      :live="planDagExpandState.live"
      :title="planDagExpandState.title"
      :user-query="planDagExpandState.userQuery"
      :loading-label="planDagExpandState.loadingLabel"
      @select="handlePlanDagExpandSelect"
      @close="closePlanDagExpand"
    />

    <!-- 悬浮输入区 -->
    <footer v-show="!planDagExpanded" class="chat-composer" @wheel="forwardWheelToChatScroll">
      <div class="composer-inner">
        <button
          v-if="attentionBubble"
          type="button"
          class="chat-attention-bubble"
          :class="`is-${attentionBubble.kind}`"
          @click="handleAttentionBubbleClick"
        >
          <span>{{ attentionBubble.text }}</span>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" aria-hidden="true">
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </button>
        <div
          class="composer-box composer-box--input"
          :class="{ 'composer-box--busy': loading }"
        >
          <ul v-if="showWorkflowSuggest && filteredWorkflows.length && !loading" class="skill-suggest">
            <li
              v-for="(wf, idx) in filteredWorkflows"
              :key="wf.id"
              :class="{ 'is-highlighted': idx === workflowSuggestIndex }"
              @mousedown.prevent="applyWorkflowSuggest(wf)"
            >
              <div class="skill-suggest-main">
                <span class="skill-suggest-id is-workflow">#{{ wf.id }}</span>
                <span class="skill-suggest-name">{{ wf.displayName }}</span>
              </div>
              <p v-if="wf.description" class="skill-suggest-desc">{{ wf.description }}</p>
            </li>
          </ul>
          <ul v-else-if="showExpertSuggest && filteredExperts.length && !loading" class="skill-suggest">
            <li
              v-for="(expert, idx) in filteredExperts"
              :key="expert.id"
              :class="{ 'is-highlighted': idx === expertSuggestIndex }"
              @mousedown.prevent="applyExpertSuggest(expert)"
            >
              <span class="skill-suggest-id is-expert">${{ expert.id }}</span>
              <span class="skill-suggest-name">{{ expert.displayName }}</span>
            </li>
          </ul>
          <ul v-else-if="showSkillSuggest && filteredSkills.length && !loading" class="skill-suggest">
            <li
              v-for="(skill, idx) in filteredSkills"
              :key="skill.id"
              :class="{ 'is-highlighted': idx === skillSuggestIndex }"
              @mousedown.prevent="applySkillSuggest(skill)"
            >
              <span class="skill-suggest-id is-skill">@{{ skill.id }}</span>
              <span class="skill-suggest-name">{{ skill.displayName }}</span>
            </li>
          </ul>
          <div class="composer-input-area">
            <div v-if="loading" class="composer-status">
              <span class="streaming-pulse" />
              <span>AI 正在回复…</span>
            </div>
            <ComposerSkillInput
              v-else
              ref="inputRef"
              v-model="inputText"
              :allows-skill-mention="skillMentionAllowed"
              :allows-expert-mention="expertMentionAllowed"
              :allows-workflow-mention="workflowMentionAllowed"
              :catalog="skillCatalog"
              :expert-catalog="expertCatalog"
              :workflow-catalog="workflowCatalog"
              :placeholder="composerPlaceholder"
              @keydown="handleKeydown"
            />
            <div class="composer-toolbar">
              <div class="composer-toolbar-left">
                <ExecutionModeSelector
                  :model-value="preference"
                  :disabled="loading"
                  @update:model-value="setPreference"
                />
                <KbSelector
                  :kbs="chatKbs"
                  :model-value="kbId"
                  :loading="loadingChatKbs"
                  :show-create="false"
                  @update:model-value="onKbChange"
                />
                <button
                  type="button"
                  class="composer-workspace-btn"
                  :disabled="!currentConversationId"
                  title="沙箱工作区"
                  @click="openWorkspaceDrawer"
                >
                  <span class="composer-workspace-leading">
                    <svg class="composer-workspace-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2h7.5A2.5 2.5 0 0 1 21 9.5v7A2.5 2.5 0 0 1 18.5 19h-13A2.5 2.5 0 0 1 3 16.5v-9Z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>
                    </svg>
                    <span class="composer-workspace-label">工作区</span>
                  </span>
                </button>
              </div>
              <button
                v-if="loading"
                type="button"
                class="composer-icon-btn stop"
                title="停止生成"
                @click="stop"
              >
                <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor"><rect x="3" y="3" width="10" height="10" rx="1.5"/></svg>
              </button>
              <button
                v-else
                type="button"
                class="composer-icon-btn send"
                :disabled="!inputText.trim()"
                title="发送"
                @click="handleSend"
              >
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M2 8l12-6-6 12-2-6-4-0z" fill="currentColor"/></svg>
              </button>
            </div>
          </div>
        </div>
        <p class="composer-hint">AI 生成内容仅供参考，请核实重要信息</p>
      </div>
    </footer>
      </div>
      <PlanNodeDrawer />
      <SandboxWorkspaceDrawer />
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  min-height: 0;
  background: var(--sun-black);
}

.chat-body {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: row;
  position: relative;
}

.chat-attention-bubble {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  margin-bottom: 8px;
  max-width: 100%;
  padding: 5px 12px;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm);
  line-height: 1.35;
  cursor: pointer;
  box-shadow: var(--composer-shadow);
  transition: border-color 0.15s, color 0.15s;
}

.chat-attention-bubble:hover {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}

.chat-attention-bubble.is-hitl_pending {
  border-color: color-mix(in srgb, var(--sun-amber) 45%, var(--sun-border));
  color: color-mix(in srgb, var(--sun-amber-light) 65%, var(--sun-text-secondary));
}

.chat-attention-bubble.is-completed {
  border-color: color-mix(in srgb, #ef4444 40%, var(--sun-border));
  color: color-mix(in srgb, #f87171 75%, var(--sun-text-secondary));
}

.chat-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  position: relative;
  /* 滚动区底部留白，避免最后一条回复贴住悬浮输入框 */
  --chat-composer-gap: 152px;
}

.chat-main.plan-dag-expanded .chat-scroll {
  visibility: hidden;
  overflow: hidden;
  pointer-events: none;
}

/* ── 全宽会话头 ── */
.chat-header {
  flex-shrink: 0;
  width: 100%;
  height: 48px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-black);
  z-index: 10;
  display: flex;
  align-items: center;
  padding: 0 12px 0 8px;
  gap: 4px;
}

.header-theme-btn {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.header-theme-btn:hover {
  background: var(--sun-surface-hover);
  color: var(--sun-text);
}

.header-inner {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  position: relative;
  padding: 0 8px;
}

.header-title {
  font-size: var(--sun-font-lg);
  font-weight: 600;
  color: var(--sun-text);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: min(480px, 70vw);
  text-align: center;
}

.header-status {
  position: absolute;
  right: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

/* ── 滚动消息区 ── */
.chat-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.chat-inner {
  max-width: 820px;
  margin: 0 auto;
  padding: 24px 24px calc(var(--chat-composer-gap) + 28px);
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

/* ── 空状态 ── */
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 48px 0 80px;
}

.empty-icon {
  color: var(--sun-text-muted);
  margin-bottom: 20px;
  opacity: 0.7;
}

.empty-title {
  font-size: var(--sun-font-2xl);
  font-weight: 600;
  color: var(--sun-text);
  margin: 0 0 8px;
  letter-spacing: -0.4px;
}

.empty-desc {
  font-size: var(--sun-font-base);
  color: var(--sun-text-muted);
  margin: 0 0 28px;
  line-height: var(--sun-line-relaxed);
}

.hint-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  max-width: 560px;
}

.hint-chip {
  padding: 8px 14px;
  background: transparent;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-base);
  font-weight: 500;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
  font-family: inherit;
}

.hint-chip:hover {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

/* ── 消息列表 ── */
.msg-list {
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding-bottom: 32px;
}

.msg-block.user {
  display: flex;
  justify-content: flex-end;
}

.user-bubble {
  max-width: 75%;
  padding: 10px 16px;
  background: var(--sun-surface);
  border: none;
  border-radius: 20px;
  font-size: var(--sun-font-md);
  line-height: var(--sun-line-relaxed);
  color: var(--sun-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-block.assistant {
  width: 100%;
}

.assistant-body {
  width: 100%;
  min-width: 0;
}

.stream-waiting-dots {
  padding: 8px 0 12px;
  min-height: 28px;
  display: flex;
  align-items: center;
}

.msg-copy-bar {
  margin-top: 10px;
  margin-bottom: 12px;
  display: flex;
  justify-content: flex-start;
}

.msg-resume-bar {
  margin-top: 8px;
}

.msg-stream-error {
  margin: 10px 0 0;
  font-size: var(--sun-font-base);
  line-height: 1.5;
  color: var(--sun-text-muted);
}

.resume-btn {
  font-size: var(--sun-font-base);
  padding: 4px 12px;
  border-radius: 8px;
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s, color 0.15s;
}

.resume-btn:hover {
  background: var(--sun-accent-muted);
  color: var(--sun-text);
  border-color: var(--sun-border-light);
}

.msg-copy-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s, color 0.15s;
  line-height: 0;
}

.msg-copy-btn:hover {
  background: var(--sun-surface-hover);
  color: var(--sun-text);
}

.msg-copy-btn svg {
  flex-shrink: 0;
}

/* ── 悬浮输入区 ── */
.chat-composer {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 20;
  padding: 0 24px 10px;
  background: linear-gradient(to bottom, transparent 0%, var(--sun-black) 20%);
  pointer-events: none;
}

.composer-inner {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  max-width: 720px;
  margin: 0 auto;
  padding-bottom: 18px;
  pointer-events: auto;
}

.composer-box {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  border-radius: 20px;
  padding: 8px 10px 8px 18px;
  min-height: 48px;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-shadow: var(--composer-shadow);
}

.composer-box:focus-within {
  border-color: var(--sun-border-light);
  box-shadow: var(--composer-shadow-focus);
}

.composer-box--busy {
  opacity: 0.92;
}

.composer-box--busy:focus-within {
  border-color: var(--sun-border);
  box-shadow: var(--composer-shadow);
}

.composer-status {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
  padding: 4px 2px;
  font-size: var(--sun-font-base);
  color: var(--sun-text-muted);
  user-select: none;
}

.composer-box--input {
  position: relative;
  flex-direction: column;
  align-items: stretch;
  padding: 10px 12px 8px 14px;
  gap: 0;
}

.composer-input-area {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  gap: 6px;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 4px;
  min-height: 34px;
}

.composer-toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.composer-workspace-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm, 12px);
  cursor: pointer;
  flex-shrink: 0;
  transition: border-color 0.15s, color 0.15s;
}

.composer-workspace-leading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.composer-workspace-icon {
  flex-shrink: 0;
  opacity: 0.9;
}

.composer-workspace-label {
  font-weight: 500;
  white-space: nowrap;
}

.composer-workspace-btn:hover:not(:disabled) {
  border-color: var(--sun-border-light, #ccc);
  color: var(--sun-text, #212121);
}

.composer-workspace-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.skill-suggest {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + 6px);
  margin: 0;
  padding: 4px;
  list-style: none;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-elevated);
  max-height: 240px;
  overflow-y: auto;
  z-index: 20;
}

.skill-suggest li {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
  padding: 7px 8px;
  border-radius: calc(var(--radius-lg) - 2px);
  cursor: pointer;
  font-size: var(--sun-font-base);
  transition: background 0.15s;
}

.skill-suggest-main {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.skill-suggest-desc,
.skill-suggest-meta {
  margin: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1.4;
  padding-left: 2px;
}

.skill-suggest-meta {
  font-family: var(--sun-font-mono);
  font-size: 11px;
}

.skill-suggest li:hover,
.skill-suggest li.is-highlighted {
  background: var(--sun-row-hover);
}

.skill-suggest-id {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-base);
  font-weight: 600;
  letter-spacing: 0.01em;
  -webkit-font-smoothing: antialiased;
  flex-shrink: 0;
}

.skill-suggest-id.is-skill {
  color: var(--mention-skill-prefix);
}

.skill-suggest-id.is-expert {
  color: var(--mention-expert-prefix);
}

.skill-suggest-id.is-workflow {
  color: var(--mention-workflow-prefix);
}

.skill-suggest-name {
  color: var(--sun-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.streaming-status {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--sun-font-base);
  color: var(--sun-text-muted);
  user-select: none;
}

.streaming-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--sun-text-muted);
  flex-shrink: 0;
  animation: glow-pulse 1.5s ease-in-out infinite;
}

.composer-icon-btn {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s, opacity 0.15s;
}

.composer-icon-btn.send {
  background: var(--sun-accent);
  color: var(--btn-primary-text);
}

.composer-icon-btn.send:hover:not(:disabled) {
  background: var(--sun-accent-hover);
}

.composer-icon-btn.send:disabled {
  background: var(--sun-border);
  color: var(--sun-text-muted);
  cursor: not-allowed;
  opacity: 0.7;
}

.composer-icon-btn.stop {
  background: transparent;
  border: 1px solid var(--sun-border);
  color: var(--sun-text-secondary);
}

.composer-icon-btn.stop:hover {
  border-color: var(--sun-red);
  color: var(--sun-red);
  background: rgba(248, 113, 113, 0.08);
}

.composer-hint {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  margin: 0;
  text-align: center;
  font-size: var(--sun-font-xs);
  line-height: 1.3;
  color: var(--sun-text-muted);
  pointer-events: none;
  user-select: none;
}
</style>
