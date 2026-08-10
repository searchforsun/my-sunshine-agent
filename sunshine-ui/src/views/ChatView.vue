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
import { useChatAgentMention } from '../composables/useChatAgentMention'
import { useChatWorkflowMention } from '../composables/useChatWorkflowMention'
import { useChatWorkspacePathMention } from '../composables/useChatWorkspacePathMention'
import { requestSandboxWorkspaceRefresh, sandboxPathIndexReady, sandboxPathIndexRefresh } from '../composables/sandboxWorkspaceRefresh'
import { flashWorkspaceBanner } from '../composables/sandboxWorkspaceBanner'
import { useSandboxPathIndex } from '../composables/useSandboxPathIndex'
import { useChatStreamMarkdown } from '../composables/useChatStreamMarkdown'
import { reEnhanceAllSandboxPathLinks } from '../utils/stream-markdown/StaticEnhancer'
import { useChatSessionHydration } from '../composables/useChatSessionHydration'
import { useChatStore } from '../stores/chatStore'
import { isValidConversationId } from '../api/conversations'
import { useTheme } from '../composables/useTheme'
import { useSidebar } from '../composables/useSidebar'
import { useSpeechRecognition } from '../composables/useSpeechRecognition'
import { listWorkspaces } from '../api/workspaces'
import type { WorkspaceVO } from '../api/workspaces'
import { gitStage, gitCommit, gitPush, gitPull, ensureCheckout, listCheckouts, gitDiffSummary, saveDiffBaseSnapshot } from '../api/workspaceGit'
import { loadActiveGeneration } from '../composables/useActiveGeneration'
import CopyToggleIcon from '../components/icons/CopyToggleIcon.vue'
import { NIcon, NPopover, NButton, NSpin } from 'naive-ui'
import { DocumentTextOutline, FolderOutline, ChevronDownOutline, GitBranchOutline, AddOutline, CloudUploadOutline, CloudDownloadOutline, CheckmarkOutline, CreateOutline, AlertCircleOutline, WarningOutline, ChatboxEllipsesOutline } from '@vicons/ionicons5'
import OperationStack from '../components/operation/OperationStack.vue'
import { liveTimelineExpanded } from '../composables/timelineCollapseBus'
import TaskBoardPanel from '../components/operation/TaskBoardPanel.vue'
import { hasRealTaskBoardItems, type TimelineMessageStatus } from '../api/processingSteps'
import { isElVisibleInRoot } from '../utils/floatingTaskboard'
import PlanNodeDrawer from '../components/plan/PlanNodeDrawer.vue'
import SandboxWorkspaceDrawer from '../components/sandbox/SandboxWorkspaceDrawer.vue'
import PlanDagExpandLayer from '../components/plan/PlanDagExpandLayer.vue'
import GitBranchSelector from '../components/chat/GitBranchSelector.vue'
import MessageDiffCard from '../components/chat/MessageDiffCard.vue'
import DrawerCollapseIcon from '../components/icons/DrawerCollapseIcon.vue'
import { usePlanNodeDrawer } from '../composables/usePlanNodeDrawer'
import { useSandboxWorkspaceDrawer } from '../composables/useSandboxWorkspaceDrawer'
import { getWriteHitlMode } from '../composables/useWriteHitlMode'
import { usePlanDagExpand } from '../composables/usePlanDagExpand'
import { fetchSandboxWorkspaceStatus } from '../api/sandboxWorkspace'
import type { ChatMessage } from '../api/chat'
import { resumeButtonLabel, resolveResumeMode } from '../api/resumeMode'
import { resolveAssistantDisplayContent, resolveStreamErrorText } from '../api/streamError'
import { formatConversationTime } from '../utils/conversationTime'
import { loadCachedMessages } from '../api/conversationCache'
import {
  isContentFullyInterleaved,
  resolveStreamingContentText,
  shouldShowAssistantBottomContent,
} from '../api/contentInterleave'
import { resolveAgentNodeStepForDrawer, getPendingHitlConfirmations } from '../api/hitlSteps'
import ExecutionModeSelector from '../components/chat/ExecutionModeSelector.vue'
import KbSelector from '../components/knowledge/KbSelector.vue'
import ComposerSkillInput from '../components/chat/ComposerSkillInput.vue'
import VoiceInputButton from '../components/chat/VoiceInputButton.vue'
import UserMessageContent from '../components/chat/UserMessageContent.vue'
import SidebarToggle from '../components/SidebarToggle.vue'
import { useExecutionPreference } from '../composables/useExecutionPreference'
import { useKbPreference } from '../composables/useKbPreference'
import { listKbs, type KnowledgeBase } from '../api/ragAdmin'
import { useTenantPreference } from '../composables/useTenantPreference'
import { allowsAgentMention, allowsSkillMention, allowsWorkflowMention } from '../api/executionModes'
import { resolveSkillBindingForSend } from '../utils/skillMention'
import { resolveWorkflowBindingForSend } from '../utils/workflowMention'
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
const { isSupported: voiceSupported, isListening: voiceListening, displayText: voiceDisplayText, stop: voiceStop } = useSpeechRecognition()
const { close: closePlanDrawer, registerChatBody } = usePlanNodeDrawer()
const {
  state: sandboxState,
  open: openSandboxDrawer,
  close: closeSandboxDrawer,
  updateConversationId,
  registerChatBody: registerSandboxChatBody,
  compareMode: drawerBothOpen,
} = useSandboxWorkspaceDrawer()
const sandboxWorkspaceActive = ref(false)
const sandboxDrawerOpen = computed(() => sandboxState.open)
const { state: planDagExpandState, isAnyExpanded: planDagExpanded, close: closePlanDagExpand, handleSelect: handlePlanDagExpandSelect } = usePlanDagExpand()
const sessionTitle = computed(() => {
  if (chatStore.newTaskMode) return '新任务'
  if (chatStore.pendingWorkspace) return '新任务'
  if (isCurrentTask.value && chatStore.current?.kind === 'task') return chatStore.current?.title || '新任务'
  return chatStore.current?.title || '新对话'
})
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
  cancelSpawnSubagent,
  cancelCancellableTool,
  ensureActive, getMessages, setMessages, migrateSession, destroySession, clearActive,
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
    void bridge.ensureStreamRenderer()
    bridge.scheduleStreamingContentSync(resolveStreamingContentText(last))
  },
  (id: string) => {
    hydrationBridge.flushPersist(id)
    chatStore.syncMessages(id, getMessages(id))
  },
  (sessionId: string) => {
    // 流式中勿每条 SSE 同步 JSON.stringify→localStorage；合并到 schedulePersist
    hydrationBridge.schedulePersist(sessionId)
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
      updateConversationId(convId)
    }
  },
  (convId: string, title: string) => {
    chatStore.updateTitleFromStream(convId, title)
  },
)

const {
  scrollRef,
  chatScrollPinned,
  forceChatScroll,
  onChatScroll,
  onChatWheelCapture,
  scrollToBottom,
  settleScrollToBottom,
  pinScrollForSend,
  forwardWheelToChatScroll,
} = useChatScroll(loading)

const historyLoading = ref(false)

/** 触顶加载更早消息（IM 游标分页）：保持滚动位置，完成后同步 session */
async function maybeLoadHistory(): Promise<void> {
  const cid = chatStore.currentId
  if (!cid || loading.value || historyLoading.value) return
  if (!chatStore.hasHistoryMore(cid)) return
  const el = scrollRef.value
  if (!el) return
  // 距顶 < 可视高度约 40%（至少 240px）即提前拉取历史，避免滚到顶才加载的顿挫
  const threshold = Math.max(240, el.clientHeight * 0.4)
  if (el.scrollTop > threshold) return
  const prevHeight = el.scrollHeight
  historyLoading.value = true
  try {
    await chatStore.loadHistory(cid)
    const updated = chatStore.conversations.find(c => c.id === cid)?.messages ?? []
    if (updated.length && cid === chatStore.currentId) {
      setMessages(cid, [...updated])
      await nextTick()
      enhanceAllStaticMarkdown()
      const el2 = scrollRef.value
      if (el2) {
        // 历史消息前插使内容变高：滚动偏移 = 高度差，用户视线不动
        el2.scrollTop = Math.max(0, el2.scrollTop + (el2.scrollHeight - prevHeight))
      }
    }
  } finally {
    historyLoading.value = false
  }
}

function handleChatScroll(): void {
  onChatScroll()
  void maybeLoadHistory()
}

watch(chatScrollPinned, pinned => {
  setScrollPinned(pinned)
  const cid = chatStore.currentId
  if (pinned && cid) clearAttention(cid)
})

/**
 * 输入框上方右侧圆形快捷按钮：
 * - 离开底部：回到底部气泡（待确认 → 黄色感叹号；对话完成 → 会话图标 + 红点；其余 → 向下箭头），点击回到底部；
 * - 最底部（回到底部图标消失）：运行中时间线展开 → 折叠气泡，点击收起运行过程。
 */
type ScrollFabKind = 'hitl_pending' | 'collapse' | 'completed' | 'down'

/** 折叠请求计数：点击底部折叠气泡时自增，经 collapseTick prop 触发运行中 OperationStack 折叠 */
const collapseTick = ref(0)

const scrollFab = computed<{ kind: ScrollFabKind } | null>(() => {
  const cid = chatStore.currentId
  if (!cid) return null
  void streamRevision.value
  // 最底部：回到底部图标消失；运行中时间线展开 → 折叠气泡
  if (chatScrollPinned.value) {
    return liveTimelineExpanded.value ? { kind: 'collapse' } : null
  }
  // 离开底部：回到底部快捷按钮
  const ind = resolveIndicator(cid, chatStore.current?.messages)
  if (ind === 'hitl_pending') return { kind: 'hitl_pending' }
  if (ind === 'completed') return { kind: 'completed' }
  return { kind: 'down' }
})

function handleScrollFabClick(): void {
  if (scrollFab.value?.kind === 'collapse') {
    collapseTick.value++
    return
  }
  const cid = chatStore.currentId
  if (cid) clearAttention(cid)
  chatScrollPinned.value = true
  setScrollPinned(true)
  scrollToBottom(true)
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

/** 运行中消息的 taskboard 步（有真实任务项才参与悬浮） */
const liveTaskboardStep = computed(() => {
  if (!loading.value) return undefined
  const last = messages.value[messages.value.length - 1]
  if (last?.role !== 'assistant') return undefined
  return resolveTimelineContext(last).steps.find(s => s.phase === 'tasks' && hasRealTaskBoardItems(s))
})

/**
 * 运行期间 todolist 滚出视口时，在输入框上方悬浮一个可折叠的任务板。
 * 用 IntersectionObserver（root = 滚动容器）跟踪 `[data-live-taskboard]` 元素：
 * 完全不可见 → 悬浮显示；重新可见 → 隐藏。
 */
const floatingTaskboardVisible = ref(false)
let taskboardObserver: IntersectionObserver | null = null

function updateFloatingTaskboard(): void {
  taskboardObserver?.disconnect()
  taskboardObserver = null
  if (!loading.value || !liveTaskboardStep.value) {
    floatingTaskboardVisible.value = false
    return
  }
  const el = document.querySelector<HTMLElement>('[data-live-taskboard="1"]')
  const root = scrollRef.value
  if (!el || !root) {
    floatingTaskboardVisible.value = false
    return
  }
  floatingTaskboardVisible.value = !isElVisibleInRoot(el, root)
  if (typeof IntersectionObserver !== 'undefined') {
    taskboardObserver = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          floatingTaskboardVisible.value = !entry.isIntersecting
        }
      },
      { root, threshold: 0 },
    )
    taskboardObserver.observe(el)
  }
}

watch(
  () => [loading.value, messages.value, liveTaskboardStep.value] as const,
  async () => {
    await nextTick()
    updateFloatingTaskboard()
  },
  { flush: 'post' },
)

onUnmounted(() => {
  taskboardObserver?.disconnect()
  taskboardObserver = null
})

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
  settleScrollToBottom,
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

/** 时间线状态映射：pending 续连（刷新后 active 锚点未落终态、SSE 尚未建立）期间，
 * 保持「运行中」语义，避免 sanitize 归一化的 interrupted 在续连成功前闪出「已中断」。
 * 仅当仍在 hydration 或续连流活跃（loading）时视为运行中；续连已结束且真实落 interrupted
 * 时回退真实状态，让「继续生成」入口与「已中断」展示一致。 */
function resolveTimelineMessageStatus(msg: ChatMessage, idx: number): TimelineMessageStatus {
  if (isPendingAutoReconnect(msg, idx) && (sessionHydrating.value || loading.value)) {
    return 'streaming'
  }
  return (msg.status as TimelineMessageStatus) ?? 'completed'
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

provide('stopGeneration', stop)
provide('cancelSpawnSubagent', cancelSpawnSubagent)
provide('cancelCancellableTool', cancelCancellableTool)
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
  showAgentSuggest,
  agentSuggestIndex,
  filteredAgents,
  agentCatalog,
  agentMentionAllowed,
  applyAgentSuggest,
  loadAgentCatalog,
  handleAgentKeydown,
} = useChatAgentMention(inputText, preference, loading)

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

const {
  showPathSuggest,
  pathSuggestIndex,
  pathSuggestLoading,
  filteredPaths,
  applyPathSuggest,
  handlePathKeydown,
} = useChatWorkspacePathMention(inputText, currentConversationId, loading, inputRef)

const composerPlaceholder = computed(() => {
  const hints = ['@ 工作区']
  if (allowsSkillMention(preference.value)) hints.push('/ Skill')
  if (allowsWorkflowMention(preference.value)) hints.push('# 工作流')
  if (allowsAgentMention(preference.value)) hints.push('$ 智能体')
  return `发消息，Enter 发送 · ${hints.join(' · ')}`
})

/** 新对话空态快捷提示：业务场景自然语言，点击即发送（意图路由自动匹配知识库 / Skill） */
const CHAT_EMPTY_HINTS = [
  { label: '青松假政策', prompt: '青松假有多少天、怎么申请？' },
  { label: '网约车上限', prompt: '市内网约车报销上限是多少？' },
  { label: '双路检索', prompt: '青松假有多少天，同时查一下网约车报销上限' },
  { label: '假期助手', prompt: '我今年还有几天假期？帮我列出请假单' },
  { label: '费用合规', prompt: '对照网约车制度，帮我看我的报销是否合规' },
  { label: 'OA 待办', prompt: '我的 OA 待办有哪些？' },
] as const

/** 新任务空态快捷提示：工作区代码任务场景，点击填入输入框（需先选项目与分支再发送） */
const TASK_EMPTY_HINTS = [
  { label: '分析代码', prompt: '分析当前工作区的代码结构，梳理核心模块与调用链路' },
  { label: '审查改动', prompt: '审查工作区中的代码改动，指出潜在问题并给出修改建议' },
  { label: '实现功能', prompt: '实现一个新功能，具体需求是：' },
  { label: '修复问题', prompt: '修复一个 Bug，具体现象是：' },
  { label: '补充测试', prompt: '为当前代码补充单元测试用例' },
  { label: '讲解项目', prompt: '讲解当前工作区的项目架构和关键文件' },
] as const

/** 空态场景：新任务（工作区代码任务）还是新对话（知识库/Skill） */
const emptyTaskState = computed(() => chatStore.newTaskMode || !!chatStore.pendingWorkspace)
const emptyHints = computed(() => (emptyTaskState.value ? TASK_EMPTY_HINTS : CHAT_EMPTY_HINTS))
const emptyTitle = computed(() => (emptyTaskState.value ? '有什么代码任务可以帮你？' : '有什么可以帮你的？'))
const emptyDesc = computed(() =>
  emptyTaskState.value
    ? '选择项目与分支 · 描述需求 · 自动拉取代码到工作区'
    : '政策、假期、报销、待办，随时问我',
)

function applyEmptyHint(prompt: string) {
  inputText.value = prompt
  void nextTick(() => inputRef.value?.focus())
}

const copiedIndex = ref<number | null>(null)
let copyResetTimer: ReturnType<typeof setTimeout> | null = null

/** workspace task：分支选择（分支名，与 checkout 目录解耦） */
const taskBranch = ref('')
/** 当前会话实际绑定的 checkoutId（由分支 ensure 得到，用于 checkoutPath / 文件树）；无绑定为空串 */
const taskCheckoutId = ref('')
/** 右侧工作区真实代码分支：新任务未发送时为缺省 checkout 分支，发送/会话恢复后为实际绑定分支 */
const taskActiveBranch = ref('')
/** 发送新任务时正在拉取分支代码到工作区（同步等待，成功后自动发消息） */
const wsPreparing = ref(false)
/** 拉取失败提示（不发消息，可重试） */
const wsPrepareError = ref('')
/** 发送失败提示（如「请求参数有误」4xx）：与对话前动作错误统一提升到输入框上方气泡展示 */
const sendFailedError = ref('')

/** 发送前分支切换：当前 checkout 有未提交改动时，输入框上方弹出提交确认框（样式对齐 todolist 卡片） */
const branchSwitchOpen = ref(false)
/** 提交框内的 commit 信息 */
const branchSwitchMsg = ref('')
/** 提交框输入框 ref（打开后聚焦便于输入 commit 信息） */
const branchSwitchInputRef = ref<HTMLTextAreaElement | null>(null)
/** 分支切换执行中（暂存/提交/切换）；期间禁止再次发送 */
const branchSwitchBusy = ref(false)
/** 切换步骤气泡：staging / committing / switching */
const branchSwitchStatus = ref<'staging' | 'committing' | 'switching' | ''>('')
/** 切换失败提示（气泡红色显示；输入框恢复原文，可重新发送重试） */
const branchSwitchError = ref('')
/** 挂起的发送上下文：弹框期间暂存，提交后恢复发送 */
const pendingSendText = ref('')
const pendingSendConvId = ref('')
const pendingTargetBranch = ref('')
/** 切换步骤气泡文案（模型/契约不涉及，纯前端提示；无省略号） */
const branchSwitchStatusText = computed(() => {
  switch (branchSwitchStatus.value) {
    case 'staging': return '正在暂存改动'
    case 'committing': return '正在提交改动'
    case 'switching': return '正在切换分支'
    default: return ''
  }
})

/**
 * 分支气泡：抽屉头部空间不足（气泡边框将碰到相邻元素/抽屉边缘）时收缩为「分支」两字。
 * 遍历 .drawer-head-top 全部子元素，分支气泡以完整分支名计自然宽，其余按 scrollWidth 计，
 * 与实际可用宽度比较；不依赖固定宽度阈值，也不受 flex 压缩吸收影响。
 * slot 渲染时机不可靠，故用 watch 在 label 挂载后建立 observer。
 */
const branchLabelRef = ref<HTMLElement | null>(null)
const branchFullNameRef = ref<HTMLElement | null>(null)
const branchCollapsed = ref(false)

let branchResizeObserver: ResizeObserver | null = null

/** label 挂载后建立 ResizeObserver 观察抽屉头部，并初始化收缩状态 */
function setupBranchObserver() {
  const el = branchLabelRef.value
  if (!el || branchResizeObserver) return
  const headTop = el.closest('.drawer-head-top') as HTMLElement | null
  if (!headTop || typeof ResizeObserver === 'undefined') return
  branchResizeObserver = new ResizeObserver(updateBranchCollapse)
  branchResizeObserver.observe(headTop)
  updateBranchCollapse()
}

watch(branchLabelRef, (el) => {
  if (el) setupBranchObserver()
})

function updateBranchCollapse() {
  const headTop = branchLabelRef.value?.closest('.drawer-head-top') as HTMLElement | null
  const full = branchFullNameRef.value
  if (!headTop || !full) return
  let natural = 0
  for (const child of Array.from(headTop.children)) {
    const el = child as HTMLElement
    if (el.classList.contains('drawer-head-actions')) {
      // head-actions 内各子元素逐项计宽；分支气泡恒按完整分支名克隆测量（与当前折叠状态无关，避免还原抖动）
      for (const sub of Array.from(el.children)) {
        const subEl = sub as HTMLElement
        if (subEl.classList.contains('git-dropdown-wrap')) {
          natural += full.getBoundingClientRect().width
        } else {
          natural += subEl.scrollWidth
        }
      }
    } else {
      natural += el.scrollWidth
    }
  }
  const available = headTop.clientWidth
  if (natural > available) {
    branchCollapsed.value = true
  } else if (branchCollapsed.value && natural < available - 16) {
    // 滞回：留 16px 余量才还原，避免临界宽度反复切换
    branchCollapsed.value = false
  }
}

onUnmounted(() => {
  branchResizeObserver?.disconnect()
  branchResizeObserver = null
})

/** 从 checkoutPath（/workspace/{checkoutId}）解析 checkoutId；空/无则返回空串 */
function checkoutIdFromPath(path?: string | null): string {
  if (!path) return ''
  const segs = path.split('/').filter(Boolean)
  return segs.length > 0 ? segs[segs.length - 1] : ''
}

/** 反查 checkoutId 对应的实际分支名；未就绪返回空串 */
async function branchNameOf(workspaceId: string, checkoutId: string): Promise<string> {
  try {
    const cs = await listCheckouts(workspaceId)
    return cs.find(c => c.checkoutId === checkoutId)?.branch ?? ''
  } catch {
    return ''
  }
}

/** 该工作区最后一个 task 会话的分支名；无历史会话则返回空串（新任务不预选） */
async function lastTaskBranch(workspaceId: string): Promise<string> {
  const tasks = [...chatStore.conversations]
    .filter(c => c.kind === 'task' && c.workspaceId === workspaceId)
    .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
  if (tasks[0]?.checkoutPath) {
    const cid = checkoutIdFromPath(tasks[0].checkoutPath)
    const branch = await branchNameOf(workspaceId, cid)
    if (branch) return branch
  }
  return ''
}

/** 会话切换时从 checkoutPath 恢复：解析 checkoutId + 反查分支名 */
async function applyConversationCheckout() {
  // 切换会话：丢弃发送前分支切换的挂起状态（避免残留到新会话）
  resetBranchSwitchState()
  const cp = chatStore.current?.checkoutPath
  const wsId = chatStore.current?.workspaceId
  if (chatStore.current?.kind === 'task' && cp && wsId) {
    const cid = checkoutIdFromPath(cp)
    taskCheckoutId.value = cid
    const branch = (await branchNameOf(wsId, cid)) || cid
    taskBranch.value = branch
    taskActiveBranch.value = branch
  } else {
    taskCheckoutId.value = ''
    taskBranch.value = ''
    taskActiveBranch.value = ''
  }
}

watch(() => chatStore.pendingWorkspace, async (pw) => {
  if (pw?.wsId) {
    // 新任务默认继承该工作区最后一个已有会话的分支（无历史会话则留空，不预选）
    taskBranch.value = await lastTaskBranch(pw.wsId)
    await refreshCheckoutForBranch(pw.wsId, taskBranch.value)
  }
})

/** 分支变化时同步对应 checkout：已有该分支 worktree 则复用（右侧直接显示代码），否则清空等发送时懒创建 */
async function refreshCheckoutForBranch(wsId: string, branch: string) {
  if (!wsId || !branch) {
    taskCheckoutId.value = ''
    taskActiveBranch.value = ''
    return
  }
  try {
    const cs = await listCheckouts(wsId)
    const hit = cs.find(c => c.branch === branch)
    taskCheckoutId.value = hit?.checkoutId ?? ''
    taskActiveBranch.value = hit?.branch ?? ''
  } catch {
    taskCheckoutId.value = ''
    taskActiveBranch.value = ''
  }
}

watch(taskBranch, (branch) => {
  const inNewTask = chatStore.newTaskMode || chatStore.pendingWorkspace
  if (isCurrentTask.value && currentWorkspaceId.value && !inNewTask) {
    // 已有任务会话：选择分支仅更新发送意图（taskBranch），会话绑定 checkout 保持不变，
    // 发送时才统一「提交改动 → 切换 checkout」；避免提前改绑导致未提交改动检测丢失
    return
  }
  const wsId = currentWorkspaceId.value ?? chatStore.pendingWorkspace?.wsId
  if (wsId) void refreshCheckoutForBranch(wsId, branch)
})

/** is the current conversation a workspace task? */
const isCurrentTask = computed(() =>
  !!(chatStore.current?.kind === 'task' && chatStore.current?.workspaceId)
)
const currentWorkspaceId = computed(() =>
  chatStore.current?.workspaceId ?? null
)
const currentWorkspaceName = computed(() => {
  if (chatStore.pendingWorkspace) return chatStore.pendingWorkspace.wsName
  const ws = wsProjectList.value.find(w => w.id === chatStore.current?.workspaceId)
  return ws?.name ?? ''
})
const currentWorkspaceRepo = computed(() => '')

// 会话级文件路径索引：进入会话即加载（不依赖工作区抽屉打开），
// 注入 window.__smd_sandboxIndex 供 markdown 路径精确匹配。
// - 会话有效（有 conversationId 或 workspaceId，含新任务待选工作区）即加载；抽屉打开/关闭不影响
// - 文件树刷新 / checkout 切换 / sync 完成 / SSE 工具写文件后自动重载
useSandboxPathIndex({
  getOpen: () => !!currentConversationId.value || !!currentWorkspaceId.value || !!chatStore.pendingWorkspace?.wsId,
  getConversationId: () => currentConversationId.value ?? '',
  getWorkspaceId: () => currentWorkspaceId.value ?? chatStore.pendingWorkspace?.wsId ?? null,
  getCheckoutId: () => taskCheckoutId.value || null,
  treeVersion: sandboxPathIndexRefresh,
})

/** 工作区项目选择器 */
const wsProjectOpen = ref(false)
const wsProjectList = ref<WorkspaceVO[]>([])
const wsProjectLoading = ref(false)

async function loadWsProjects() {
  wsProjectLoading.value = true
  try { wsProjectList.value = await listWorkspaces() }
  catch { /* silently fail */ }
  finally { wsProjectLoading.value = false }
}

function onWsProjectShow(next: boolean) {
  wsProjectOpen.value = next
  if (next) loadWsProjects()
}

function selectWsProject(ws: WorkspaceVO) {
  chatStore.pendingWorkspace = { wsId: ws.id, wsName: ws.name }
  wsProjectOpen.value = false
}

/** 分支下拉 git 操作按钮状态机：idle -> loading（转圈）-> done（√，稍后回 idle） */
type GitOpKind = 'stage' | 'push' | 'pull'
const gitOpKind = ref<GitOpKind | ''>('')
const gitOpState = ref<'idle' | 'loading' | 'done'>('idle')
const commitMsg = ref('')
/** 提交信息输入用侧边二级 Popover（替代原 NModal 弹窗） */
const showCommitPopover = ref(false)
/** 提交按钮状态机：idle -> loading（转圈）-> done（√，稍后回 idle 并关闭弹窗） */
const commitState = ref<'idle' | 'loading' | 'done'>('idle')
/** git 操作提示：写入工作区标题栏下方统一横幅（成功不再提示） */
function flashGitToast(kind: 'info' | 'error', text: string) {
  flashWorkspaceBanner('git', { kind, text })
}
/** git 操作前置：无 checkoutId（新任务未发送，懒创建尚未发生）时提示并中止 */
function requireCheckout(): string | null {
  const cid = taskCheckoutId.value
  if (!cid) {
    flashGitToast('info', '请先发送消息以拉取代码到工作区')
    return null
  }
  return cid
}

/** 操作完成：按钮转 √，900ms 后恢复文本 */
function finishGitOp(kind: GitOpKind) {
  gitOpKind.value = kind
  gitOpState.value = 'done'
  window.setTimeout(() => {
    gitOpState.value = 'idle'
    gitOpKind.value = ''
  }, 900)
}

async function handleGitStage() {
  const wsId = currentWorkspaceId.value
  const cid = requireCheckout()
  if (!wsId || !cid || gitOpState.value !== 'idle') return
  gitOpKind.value = 'stage'
  gitOpState.value = 'loading'
  try {
    await gitStage(wsId, cid, undefined, true)
    finishGitOp('stage')
    bumpDiffCardRefresh()
  } catch (e) {
    gitOpState.value = 'idle'
    gitOpKind.value = ''
    flashGitToast('error', (e as Error)?.message || '暂存失败')
  }
}

function openCommitPopover() {
  commitMsg.value = ''
  commitState.value = 'idle'
  showCommitPopover.value = true
}

async function handleGitCommit() {
  const wsId = currentWorkspaceId.value
  const cid = requireCheckout()
  if (!wsId || !cid) return
  const msg = commitMsg.value.trim()
  if (!msg || commitState.value !== 'idle') return
  commitState.value = 'loading'
  try {
    await gitCommit(wsId, cid, msg)
    commitMsg.value = ''
    commitState.value = 'done'
    bumpDiffCardRefresh()
    window.setTimeout(() => {
      commitState.value = 'idle'
      showCommitPopover.value = false
    }, 900)
  } catch (e) {
    commitState.value = 'idle'
    flashGitToast('error', (e as Error)?.message || '提交失败')
  }
}

async function handleGitPush() {
  const wsId = currentWorkspaceId.value
  const cid = requireCheckout()
  if (!wsId || !cid || gitOpState.value !== 'idle') return
  gitOpKind.value = 'push'
  gitOpState.value = 'loading'
  try {
    await gitPush(wsId, cid)
    finishGitOp('push')
    bumpDiffCardRefresh()
  } catch (e) {
    gitOpState.value = 'idle'
    gitOpKind.value = ''
    flashGitToast('error', (e as Error)?.message || '推送失败')
  }
}

async function handleGitPull() {
  const wsId = currentWorkspaceId.value
  const cid = requireCheckout()
  if (!wsId || !cid || gitOpState.value !== 'idle') return
  gitOpKind.value = 'pull'
  gitOpState.value = 'loading'
  try {
    await gitPull(wsId, cid)
    finishGitOp('pull')
    bumpDiffCardRefresh()
  } catch (e) {
    gitOpState.value = 'idle'
    gitOpKind.value = ''
    flashGitToast('error', (e as Error)?.message || '拉取失败')
  }
}

/** 消息改动卡片刷新版本号：会话完成 / git 操作后 +1，触发卡片重新拉取 */
const diffCardRefreshTick = ref(0)

watch(loading, (now, prev) => {
  if (prev === true && now === false) diffCardRefreshTick.value++
})

function bumpDiffCardRefresh() {
  diffCardRefreshTick.value++
}

/** 复制按钮下方是否展示改动卡片：仅任务工作区、已完成的最后一条 assistant 消息 */
function canShowDiffCard(msg: ChatMessage, idx: number): boolean {
  return msg.role === 'assistant'
    && idx === messages.value.length - 1
    && isCurrentTask.value
    && !!currentWorkspaceId.value
    && !!taskCheckoutId.value
    && !(loading.value && idx === messages.value.length - 1)
}

/** 点击改动卡片行 -> 右侧工作区进入「改动」视图并展开该文件的 diff 详情 */
function openDiffInDrawer(path: string) {
  const cid = chatStore.currentId
  if (!cid || !path) return
  openSandboxDrawer({ conversationId: cid, diffPath: path })
}

function canCopyAssistant(msg: { role: string; content: string; status?: string }, idx: number): boolean {
  if (msg.role !== 'assistant' || !msg.content.trim()) return false
  const isLast = idx === messages.value.length - 1
  if (isLast && loading.value) return false
  if (msg.status === 'streaming') return false
  // hydration 期间末条 assistant 还未确定终态（可能在重连），避免复制图标闪烁
  if (isLast && sessionHydrating.value && msg.status !== 'completed') return false
  return true
}

/** 消息结束时间（epoch ms）：timelineEndedAt 优先，updatedAt 兜底 */
function resolveMessageEndedAt(msg: ChatMessage): number | null {
  if (typeof msg.timelineEndedAt === 'number' && msg.timelineEndedAt > 0) {
    return msg.timelineEndedAt
  }
  const u = msg.updatedAt
  if (typeof u === 'number' && u > 0) return u
  if (typeof u === 'string' && u) {
    const t = Date.parse(u)
    return Number.isNaN(t) ? null : t
  }
  return null
}

/** hover 复制栏时右侧显示的结束时间文案（复用侧栏相对时间格式） */
function messageEndTimeLabel(msg: ChatMessage): string {
  const ts = resolveMessageEndedAt(msg)
  return ts ? formatConversationTime(ts) : ''
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

/** 给 Promise 加超时；超时抛异常 */
function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms)
    promise.then(
      (v) => { clearTimeout(timer); resolve(v) },
      (e) => { clearTimeout(timer); reject(e) },
    )
  })
}

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || wsPreparing.value || branchSwitchBusy.value || branchSwitchOpen.value) return
  try {
    const pending = chatStore.pendingWorkspace
    let convId: string
    if (chatStore.newTaskMode || pending) {
      const wsId = pending?.wsId
      // 所选分支尚无 checkout → 显示「正在拉取分支代码到工作区...」同步等待，成功才发消息
      let checkoutId = taskCheckoutId.value
      if (wsId && taskBranch.value) {
        if (!checkoutId) {
          wsPreparing.value = true
          wsPrepareError.value = ''
          try {
            checkoutId = await withTimeout(ensureCheckout(wsId, taskBranch.value), 60000, '拉取分支代码超时')
          } catch (e) {
            wsPreparing.value = false
            wsPrepareError.value = (e instanceof Error ? e.message : String(e)) || '拉取分支代码失败'
            return
          } finally {
            wsPreparing.value = false
          }
          taskCheckoutId.value = checkoutId
        }
        taskActiveBranch.value = taskBranch.value
      } else if (!taskBranch.value) {
        wsPrepareError.value = '请先选择分支'
        return
      }
      convId = await chatStore.create({
        kind: 'task',
        workspaceId: wsId,
        checkoutPath: '/workspace/' + checkoutId,
      })
      chatStore.pendingWorkspace = null
      chatStore.newTaskMode = false
    } else {
      convId = await chatStore.ensureCurrent()
      // 已有任务会话：发送前若切换了分支，先统一走「改动检测 → 提交 → 切换 checkout」流程；
      // 当前 checkout 有未提交改动时挂起发送，等待用户在提交框内决定（取消则放弃本次发送）
      if (isCurrentTask.value && currentWorkspaceId.value && taskBranch.value && taskBranch.value !== taskActiveBranch.value) {
        const canSend = await maybeSwitchBranchBeforeSend(text, convId)
        if (!canSend) return
      }
    }
    await performSend(text, convId)
  } catch (e) {
    console.error('[ChatView] 发送失败', e)
    inputText.value = text
  }
}

/** 发送主流程：清空输入、落基线、发起 SSE 并等待完成（新任务创建 / 分支切换成功后统一调用） */
async function performSend(text: string, convId: string) {
  ensureActive(convId)
  // 新一轮发送即「继续执行」：清空上一次发送失败的提示（气泡），避免残留
  sendFailedError.value = ''
  if (messages.value.length === 0) chatStore.updateTitle(convId, text)
  // 本轮改动基线：记录发送瞬间工作区已有改动，供完成后 diff 卡片做差集（只显示本轮新增）
  if (currentWorkspaceId.value && taskCheckoutId.value) {
    const wsId = currentWorkspaceId.value
    const cid = taskCheckoutId.value
    void gitDiffSummary(wsId, cid)
      .then(items => saveDiffBaseSnapshot(convId, items.map(i => i.path)))
      .catch(() => { /* 基线快照失败不阻塞发送 */ })
  }
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
  if (skillBinding.skillId) {
    requestSandboxWorkspaceRefresh(convId, 'skills', true)
  }
  // 发送时若上一轮仍在运行：先暂停（停止）上一轮，再开始新一轮
  if (loading.value) await stop()
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
  // 发送失败（如后端校验 4xx「请求参数有误」）：统一在输入框上方气泡展示（消息卡不再显示错误）
  const lastMsg = messages.value[messages.value.length - 1]
  if (lastMsg?.role === 'assistant') {
    const errText = resolveStreamErrorText(lastMsg)
    if (errText && lastMsg.status !== 'completed') {
      sendFailedError.value = errText
    }
  }
}

/**
 * 发送前分支切换检测：目标分支与当前 checkout 分支不一致时，
 * 当前 checkout 有未提交改动 → 弹出提交框挂起发送；无改动 → 直接切换后继续发送。
 * 返回 false 表示发送已挂起，由提交框交互接管。
 */
async function maybeSwitchBranchBeforeSend(text: string, convId: string): Promise<boolean> {
  const wsId = currentWorkspaceId.value
  const targetBranch = taskBranch.value
  if (!wsId || !targetBranch) return true
  const currentCheckoutId = taskCheckoutId.value
  if (!currentCheckoutId) {
    // 异常态（会话绑定丢失）：直接切换到目标分支即可发送
    await switchBranch(wsId, targetBranch, convId)
    return true
  }
  let hasChanges = false
  try {
    const items = await gitDiffSummary(wsId, currentCheckoutId)
    hasChanges = items.length > 0
  } catch {
    // 改动检测失败不阻塞发送：按无改动处理，直接切换
  }
  if (!hasChanges) {
    await switchBranch(wsId, targetBranch, convId)
    return true
  }
  // 有未提交改动：挂起发送，弹出提交框等待用户提交或取消
  pendingSendText.value = text
  pendingSendConvId.value = convId
  pendingTargetBranch.value = targetBranch
  branchSwitchMsg.value = ''
  branchSwitchError.value = ''
  branchSwitchOpen.value = true
  void nextTick(() => branchSwitchInputRef.value?.focus())
  return false
}

/** 暂存全部 → 提交 → 切换目标分支 checkout，并重绑定会话 checkoutPath；commitMsg 为空时跳过提交 */
async function switchBranch(wsId: string, targetBranch: string, convId: string, commitMsg?: string) {
  branchSwitchBusy.value = true
  branchSwitchError.value = ''
  try {
    if (commitMsg) {
      branchSwitchStatus.value = 'staging'
      await gitStage(wsId, taskCheckoutId.value, undefined, true)
      branchSwitchStatus.value = 'committing'
      await gitCommit(wsId, taskCheckoutId.value, commitMsg)
    }
    branchSwitchStatus.value = 'switching'
    const newCheckoutId = await withTimeout(ensureCheckout(wsId, targetBranch), 60000, '切换分支超时')
    if (chatStore.currentId !== convId) {
      // 切换期间用户已离开该会话：git 提交已完成，但不把 checkout 绑定套用到当前会话
      return
    }
    // 先持久化会话 checkoutPath（失败则中止，本地状态保持原分支，可重试），成功后才更新本地绑定
    await chatStore.updateCheckout(convId, '/workspace/' + newCheckoutId)
    taskCheckoutId.value = newCheckoutId
    taskActiveBranch.value = targetBranch
    taskBranch.value = targetBranch
    bumpDiffCardRefresh()
  } catch (e) {
    branchSwitchError.value = (e instanceof Error ? e.message : String(e)) || '切换分支失败'
    throw e
  } finally {
    branchSwitchStatus.value = ''
    branchSwitchBusy.value = false
  }
}

/** 提交框「提交改动」：提交当前改动并切换到目标分支，随后恢复发送挂起的消息 */
async function handleBranchSwitchCommit() {
  const msg = branchSwitchMsg.value.trim()
  const convId = pendingSendConvId.value
  const targetBranch = pendingTargetBranch.value
  const wsId = currentWorkspaceId.value
  if (!msg || !convId || !targetBranch || !wsId || branchSwitchBusy.value) return
  if (chatStore.currentId !== convId) {
    // 挂起期间用户已切换会话：丢弃本次发送
    resetBranchSwitchState()
    return
  }
  // 挂起期间用户可能编辑过输入框：以最新输入为准，空则用挂起时的文本
  const text = inputText.value.trim() || pendingSendText.value
  resetBranchSwitchState()
  try {
    await switchBranch(wsId, targetBranch, convId, msg)
    await performSend(text, convId)
  } catch (e) {
    console.error('[ChatView] 提交改动并发送失败', e)
    inputText.value = text
  }
}

/** 清理发送前分支切换的挂起状态（提交 / 取消 / 会话切换时调用） */
function resetBranchSwitchState() {
  branchSwitchOpen.value = false
  branchSwitchMsg.value = ''
  branchSwitchStatus.value = ''
  branchSwitchError.value = ''
  branchSwitchBusy.value = false
  pendingSendText.value = ''
  pendingSendConvId.value = ''
  pendingTargetBranch.value = ''
}

/** 对话前动作（拉取分支 / 切换分支 / 发送失败）出错详情：统一展示于输入框上方的详情卡片（非红色，可关闭） */
const preActionError = computed(() => wsPrepareError.value || branchSwitchError.value || sendFailedError.value)

function dismissPreActionError() {
  wsPrepareError.value = ''
  branchSwitchError.value = ''
  sendFailedError.value = ''
}

/** 提交框「取消」：放弃本次发送，输入框保留原文（改动留在当前分支） */
function handleBranchSwitchCancel() {
  resetBranchSwitchState()
  void nextTick(() => inputRef.value?.focus())
}

async function handleResume() {
  const last = messages.value[messages.value.length - 1]
  const convId = chatStore.currentId
  if (!last?.id || !convId || loading.value) return
  // 继续执行：清空上一次发送失败的提示
  sendFailedError.value = ''
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

/** 输入框右下暂停按钮：运行中显示，点击暂停当前生成 */
function handleComposerPause() {
  if (!loading.value) return
  void stop()
}

function handleVoiceCancel() {
  voiceStop()
}

function handleVoiceConfirm() {
  const text = voiceStop()
  if (text) {
    inputText.value = inputText.value ? inputText.value + ' ' + text : text
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (handlePathKeydown(e)) return
  if (handleWorkflowKeydown(e)) return
  if (handleAgentKeydown(e)) return
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
  void loadAgentCatalog()
  void loadWorkflowCatalog()
  void loadWsProjects()
  sessionHydrating.value = true
  // 刷新无感：先用 localStorage 缓存渲染目标会话（URL cid / active generation / 本地当前 id 同优先序），
  // 网络 init/loadDetail 返回后由 hydrateSessionFromStore 合并覆盖，避免刷新后出现空态期
  const preloadCid = (typeof route.query.cid === 'string' && route.query.cid.trim())
    || loadActiveGeneration()?.conversationId
    || localStorage.getItem('sunshine-current-conversation-id') || ''
  if (isValidConversationId(preloadCid)) {
    const cached = loadCachedMessages(preloadCid)
    if (cached?.length) {
      ensureActive(preloadCid)
      // 勿经 sanitizeRestoredMessages——缓存中最后一条 streaming 状态是精确的
      //（schedulePersist 在流式中保存），不应被过早标为 interrupted，否则复制图标闪烁。
      // API 回来后会由 hydrateSessionFromStore 正确归一。
      setMessages(preloadCid, cached)
      // 等待 Vue 渲染新消息到 DOM，再贴底，避免 scrollHeight 尚未更新就同步贴底到 0
      await nextTick()
      // 缓存消息立即可见，贴底避免用户看到顶部停留；网络 API 回包后消息替换时
      // 滚动高度变化会重置稳定帧，settle 持续跟随到底部
      settleScrollToBottom()
    }
  }
  try {
    await chatStore.init()
    // URL ?cid= 优先：刷新后定位回用户打开的那个会话（URL 是唯一能跨刷新保留的会话锚点）
    const urlCid = typeof route.query.cid === 'string' ? route.query.cid.trim() : ''
    if (urlCid) {
      try {
        await chatStore.ensureConversation(urlCid)
        await chatStore.switchTo(urlCid)
      } catch {
        // URL cid 已失效（会话被删/库被清），忽略并走默认恢复
      }
    }
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
      // 新任务模式（点「新任务」未发消息）：不创建会话，保持空白新任务页，
      // 等真正发送时才 create，避免侧栏冒出幽灵「新对话」
      if (chatStore.newTaskMode || chatStore.pendingWorkspace) {
        cid = ''
      } else {
        cid = await chatStore.ensureCurrent()
      }
    }
    await chatStore.switchTo(cid)
    applyConversationPreference(chatStore.current?.executionPreference)
    applyConversationKb(chatStore.current?.kbId)
    void applyConversationCheckout()
    void loadChatKbs()
    ensureActive(cid)
    const pendingReconnect = !!(active?.conversationId === cid)
    if (cid) {
      await hydrateSessionFromStore(cid, { skipApiLoad: pendingReconnect })
      if (active && active.conversationId === cid) {
        await tryAutoReconnect(cid, active)
        syncSessionToStore(cid)
      }
    }
  } finally {
    sessionHydrating.value = false
  }
  setActiveConversation(chatStore.currentId)
  scrollToBottomIfRequested(chatStore.currentId ?? '')
  // 刷新后定位到会话底部：消息/时间线分帧渲染增高，单次贴底会停在中间高度，settle 至高度稳定；
  // 空会话与新任务模式（无消息）跳过
  if (chatStore.currentId && messages.value.length) {
    settleScrollToBottom()
  }
  applyChatDeepLink()
  inputRef.value?.focus()
  window.addEventListener('pagehide', flushAllOnPageHide)
  ;(window as any).__smd_openSandboxPath = (path: string) => {
    const cid = chatStore.currentId
    if (!cid || !path) return
    // 相对路径结合当前工作区根解析为绝对路径
    let resolved = path
    const root = (window as any).__smd_sandboxRoot as string | undefined
    if (root && !path.startsWith('/workspace/') && !path.startsWith('/skills/')) {
      resolved = `${root.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
    }
    openSandboxDrawer({ conversationId: cid, focusPath: resolved })
  }
  // 工作区选中行 -> 插入输入框引用 `path` L120-125
  ;(window as any).__smd_addSandboxSelection = (path: string, start: number, end: number) => {
    const cid = chatStore.currentId
    if (!cid || !path || typeof start !== 'number') return
    const resolved = (window as any).__smd_sandboxRoot
      ? path.startsWith('/workspace/') || path.startsWith('/skills/')
        ? path
        : `${(window as any).__smd_sandboxRoot.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
      : path
    inputRef.value?.insertPathRange(resolved, start, end)
  }
})

// 注入工作区根路径供 markdown 路径增强使用（相对路径 -> 绝对路径解析）
watch(taskCheckoutId, (cid) => {
  ;(window as any).__smd_sandboxRoot = cid ? `/workspace/${cid}` : ''
}, { immediate: true })

onUnmounted(() => {
  setChatRouteActive(false)
  setActiveConversation(null)
  window.removeEventListener('pagehide', flushAllOnPageHide)
  registerChatBody(null)
  registerSandboxChatBody(null)
  delete (window as any).__smd_openSandboxPath
  delete (window as any).__smd_addSandboxSelection
  delete (window as any).__smd_sandboxRoot
  delete (window as any).__smd_sandboxIndex
})

watch(chatBodyRef, (el) => {
  registerChatBody(el)
  registerSandboxChatBody(el)
}, { immediate: true })
onUpdated(() => { nextTick(() => enhanceAllStaticMarkdown()) })
// 沙箱路径索引就绪后，重新增强已渲染消息中的相对路径链接（索引未就绪时漏增强的）
watch(() => sandboxPathIndexReady.tick, () => {
  nextTick(() => reEnhanceAllSandboxPathLinks())
})
watch(() => chatStore.currentId, async (newId, oldId) => {
  if (sessionHydrating.value || newId === oldId) return
  // 把当前会话锚定到 URL：刷新后可定位回同一会话
  if (isValidConversationId(newId)) {
    const nextQuery = { ...route.query, cid: newId }
    if (route.query.cid !== newId) void router.replace({ query: nextQuery })
  } else if (route.query.cid) {
    const nextQuery = { ...route.query }
    delete nextQuery.cid
    void router.replace({ query: nextQuery })
  }
  setActiveConversation(newId)
  closePlanDrawer()
  closeSandboxDrawer()
  closePlanDagExpand()
  sandboxWorkspaceActive.value = false
  // 切换会话前，把旧会话的状态落盘
  if (oldId) {
    chatStore.syncMessages(oldId, getMessages(oldId))
    cacheSettledHtmlForConversation(oldId)
    // 切换到 null（新任务模式）或旧会话被删除时，彻底清理
    if (!isValidConversationId(newId) || !chatStore.conversations.some(c => c.id === oldId)) {
      destroySession(oldId)
      sessionSettledHtml.delete(oldId)
    }
  }
  clearStreamRenderer()
  settledHtml.value = ''
  // 切换为 null（如新任务模式）：额外调用 clearActive 确保 session 和 DOM 彻底清空
  if (!isValidConversationId(newId)) {
    clearActive()
    return
  }
  // 切换 DOM 立即响应：缓存消息先渲染到滚动区，同步贴底避免闪现，DB 查询后台更新
  ensureActive(newId)
  updateConversationId(newId)
  applyConversationPreference(chatStore.current?.executionPreference)
  applyConversationKb(chatStore.current?.kbId)
  void applyConversationCheckout()
  // 沙箱状态异步查询（有超时兜底），不阻塞 DOM 切换
  void (async () => {
    try {
      sandboxWorkspaceActive.value = await fetchSandboxWorkspaceStatus(newId)
    } catch {
      sandboxWorkspaceActive.value = false
    }
  })()
  // 缓存消息立即贴底，避免用户看到旧滚动位置的残留
  if (messages.value.length) settleScrollToBottom()
  // DB 后台对齐：有更新则 setMessages 触发响应式重渲染，settle 持续跟随
  // 显式传 skipApiLoad: false：当前 loading 属于旧会话，不能阻止新会话的 DB 查询
  void hydrateSessionFromStore(newId, { skipApiLoad: false })
}, { flush: 'post' })

watch(
  () => streamRevision.value,
  async () => {
    if (!loading.value) return
    await nextTick()
    scrollToBottom(false)
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
    <button
      v-if="(chatStore.newTaskMode || isCurrentTask || chatStore.pendingWorkspace || chatStore.currentId) && !sandboxDrawerOpen"
      type="button"
      class="ws-drawer-toggle"
      title="展开工作区"
      @click="() => { openSandboxDrawer({ conversationId: currentConversationId ?? '' }) }"
    >
      <DrawerCollapseIcon :size="16" />
    </button>

    <div
      ref="chatBodyRef"
      class="chat-body"
      :class="{ 'chat-body--both-drawers': drawerBothOpen }"
    >
      <div
        class="chat-main"
        :class="{ 'plan-dag-expanded': planDagExpanded }"
      >
    <!-- 消息区 -->
    <div
      ref="scrollRef"
      class="chat-scroll"
      @scroll="handleChatScroll"
      @wheel.capture="onChatWheelCapture"
    >
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
            <!-- 新任务：代码任务图标；新对话：对话图标 -->
            <svg v-if="emptyTaskState" width="40" height="40" viewBox="0 0 48 48" fill="none">
              <rect x="9" y="9" width="30" height="30" rx="6" stroke="currentColor" stroke-width="1.2" opacity="0.35" />
              <path d="M19 21l-5 4 5 4M29 21l5 4-5 4M24 18l-3 12" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" opacity="0.6" />
            </svg>
            <svg v-else width="40" height="40" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="14" stroke="currentColor" stroke-width="1.2" opacity="0.35" />
              <circle cx="24" cy="24" r="5" fill="currentColor" opacity="0.5" />
            </svg>
          </div>
          <h2 class="empty-title">{{ emptyTitle }}</h2>
          <p class="empty-desc">{{ emptyDesc }}</p>
          <div class="hint-chips">
            <button
              v-for="hint in emptyHints"
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
            v-if="historyLoading"
            class="history-load-bar"
          >
            <NSpin size="small" />
          </div>
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
                :agent-catalog="agentCatalog"
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
                :collapse-tick="isTimelineLive(msg, idx) ? collapseTick : undefined"
                :execution-plan-id="msg.executionPlanId"
                :user-query="resolveUserQuery(idx)"
                :message-id="msg.id"
                :message-status="resolveTimelineMessageStatus(msg, idx)"
                :message-content="msg.content"
                :timeline-started-at="msg.timelineStartedAt"
                :timeline-ended-at="msg.timelineEndedAt"
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
                  <CopyToggleIcon :copied="copiedIndex === idx" />
                </button>
                <span v-if="messageEndTimeLabel(msg)" class="msg-end-time">{{ messageEndTimeLabel(msg) }}</span>
              </div>
              <!-- 对话完成后：复制按钮下方的改动文件卡片（点击打开右侧工作区 diff 详情） -->
              <MessageDiffCard
                v-if="canShowDiffCard(msg, idx)"
                :key="`${msg.id ?? idx}-${diffCardRefreshTick}`"
                :workspace-id="currentWorkspaceId"
                :checkout-id="taskCheckoutId"
                :conversation-id="chatStore.currentId"
                @open-diff="openDiffInDrawer"
              />
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
        <!-- 输入框上方右侧圆形快捷按钮；始终置于其它气泡（todolist/错误信息等）之上。
             离开底部 → 回到底部（待确认 → 黄色感叹号；对话完成 → 会话图标 + 红点；其余 → 向下箭头）；
             最底部且运行中时间线展开 → 折叠图标（点击收起运行过程） -->
        <transition name="fab-fade">
          <button
            v-if="scrollFab"
            type="button"
            class="scroll-fab"
            :class="`is-${scrollFab.kind}`"
            :title="scrollFab.kind === 'hitl_pending' ? '待确认，点击回到底部' : scrollFab.kind === 'collapse' ? '折叠运行过程' : '回到底部'"
            @click="handleScrollFabClick"
          >
            <NIcon v-if="scrollFab.kind === 'hitl_pending'" :size="16" :component="WarningOutline" />
            <svg
              v-else-if="scrollFab.kind === 'collapse'"
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2.5"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <polyline points="18 15 12 9 6 15" />
            </svg>
            <template v-else-if="scrollFab.kind === 'completed'">
              <NIcon :size="16" :component="ChatboxEllipsesOutline" />
              <span class="scroll-fab-dot" aria-hidden="true" />
            </template>
            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>
        </transition>
        <!-- 工作区项目选择器（任务模式下显示在输入框上方，胶囊样式） -->
        <div v-if="chatStore.newTaskMode || chatStore.pendingWorkspace" class="ws-project-pill">
          <NPopover
            trigger="click"
            placement="top-start"
            :width="280"
            raw
            :show-arrow="false"
            content-class="ws-project-popover"
            :show="wsProjectOpen"
            @update:show="onWsProjectShow"
          >
            <template #trigger>
              <button type="button" class="ws-project-trigger">
                <NIcon :size="14" :component="FolderOutline" />
                <span class="ws-project-label">{{ currentWorkspaceName || chatStore.pendingWorkspace?.wsName || '选择工作区…' }}</span>
                <NIcon :size="12" :component="ChevronDownOutline" class="ws-project-chevron" />
              </button>
            </template>
            <div class="ws-project-menu">
              <div v-if="wsProjectList.length === 0 && !wsProjectLoading" class="ws-project-menu-empty">暂无工作区</div>
              <button
                v-for="ws in wsProjectList"
                :key="ws.id"
                type="button"
                class="ws-project-menu-item"
                :class="{ 'is-selected': ws.id === (currentWorkspaceId ?? chatStore.pendingWorkspace?.wsId) }"
                @click="selectWsProject(ws)"
              >
                <NIcon :size="18" :component="FolderOutline" class="ws-project-menu-icon" />
                <span class="ws-project-menu-text">
                  <span class="ws-project-menu-title">{{ ws.name }}</span>
                </span>
                <span class="ws-project-menu-check-slot">
                  <NIcon
                    v-if="ws.id === (currentWorkspaceId ?? chatStore.pendingWorkspace?.wsId)"
                    class="ws-project-menu-check"
                    :component="CheckmarkOutline"
                    :size="18"
                  />
                </span>
              </button>
            </div>
          </NPopover>
          <!-- 新任务发送前同步拉取分支代码：进行中气泡（对话前动作统一样式，复用分支切换气泡） -->
          <div v-if="wsPreparing" class="pre-action-bubble">
            <span class="typing-dots"><span class="dot"/><span class="dot"/><span class="dot"/></span>
            <span class="pre-action-text">正在拉取分支代码到工作区</span>
          </div>
        </div>
        <div
          v-if="floatingTaskboardVisible && liveTaskboardStep"
          class="floating-taskboard"
        >
          <TaskBoardPanel
            :step="liveTaskboardStep"
            :live="true"
            :default-collapsed="true"
            floating
          />
        </div>
        <!-- 发送前分支切换：当前分支有未提交改动 → 输入框上方提交确认框（样式对齐 todolist 卡片） -->
        <div v-if="branchSwitchOpen" class="branch-switch-commit">
          <div class="branch-switch-commit-head">
            <NIcon :size="14" :component="CreateOutline" />
            <span>当前分支有未提交改动</span>
            <button type="button" class="branch-switch-close" title="取消" @click="handleBranchSwitchCancel">×</button>
          </div>
          <p class="branch-switch-desc">
            切换分支前需先提交当前改动（暂存全部并提交到「{{ taskActiveBranch || '当前分支' }}」），
            提交完成后自动切换到「{{ pendingTargetBranch }}」再发送消息。
          </p>
          <textarea
            ref="branchSwitchInputRef"
            v-model="branchSwitchMsg"
            class="branch-switch-input"
            placeholder="feat: 描述你的变更…"
            maxlength="256"
            rows="2"
            spellcheck="false"
            @keydown.enter.exact.prevent="handleBranchSwitchCommit"
            @keydown.esc="handleBranchSwitchCancel"
          />
          <div class="branch-switch-actions">
            <span class="branch-switch-kbd">Enter 提交 · Esc 取消</span>
            <NButton size="small" quaternary :disabled="branchSwitchBusy" @click="handleBranchSwitchCancel">取消</NButton>
            <NButton size="small" type="primary" :disabled="!branchSwitchMsg.trim() || branchSwitchBusy" :loading="branchSwitchBusy" @click="handleBranchSwitchCommit">提交改动</NButton>
          </div>
        </div>
        <!-- 对话前动作出错详情卡片（拉取分支 / 切换分支）：todolist 同款样式，非红色，最小高度，右上角可关闭 -->
        <div v-if="preActionError" class="pre-action-error">
          <div class="pre-action-error-head">
            <NIcon :size="14" :component="AlertCircleOutline" />
            <span>操作失败</span>
            <button type="button" class="pre-action-error-close" title="关闭" @click="dismissPreActionError">×</button>
          </div>
          <div class="pre-action-error-body">{{ preActionError }}</div>
          <div v-if="wsPrepareError" class="pre-action-error-actions">
            <NButton size="small" type="primary" @click="handleSend">重试</NButton>
          </div>
        </div>
        <!-- 分支切换步骤气泡：输入框上方右侧（暂存 → 提交 → 切换），与拉取分支气泡统一样式 -->
        <div v-else-if="branchSwitchStatus" class="pre-action-bubble pre-action-bubble--float">
          <span class="typing-dots"><span class="dot"/><span class="dot"/><span class="dot"/></span>
          <span class="pre-action-text">{{ branchSwitchStatusText }}</span>
        </div>
        <div
          class="composer-box composer-box--input"
          :class="{ 'composer-box--busy': loading }"
        >
          <ul v-if="showPathSuggest && !loading && (pathSuggestLoading || filteredPaths.length)" class="skill-suggest">
            <li v-if="pathSuggestLoading" class="skill-suggest-loading">正在加载工作区…</li>
            <template v-else>
              <li
                v-for="(entry, idx) in filteredPaths"
                :key="entry.path"
                class="path-suggest-item"
                :class="{ 'is-highlighted': idx === pathSuggestIndex }"
                @mousedown.prevent="applyPathSuggest(entry)"
              >
                <div class="skill-suggest-main">
                  <NIcon
                    :component="entry.isDir ? FolderOutline : DocumentTextOutline"
                    :size="14"
                    class="path-suggest-icon"
                  />
                  <span class="path-suggest-name">{{ entry.name }}</span>
                </div>
                <span class="skill-suggest-meta path-suggest-path">{{ entry.path }}</span>
              </li>
            </template>
          </ul>
          <ul v-else-if="showWorkflowSuggest && filteredWorkflows.length && !loading" class="skill-suggest">
            <li
              v-for="(wf, idx) in filteredWorkflows"
              :key="wf.id"
              :class="{ 'is-highlighted': idx === workflowSuggestIndex }"
              @mousedown.prevent="applyWorkflowSuggest(wf)"
            >
              <div class="skill-suggest-main">
                <span class="skill-suggest-id is-workflow">#{{ wf.id }}</span>
                <span class="skill-suggest-title">{{ wf.displayName }}</span>
              </div>
              <p v-if="wf.description" class="skill-suggest-desc">{{ wf.description }}</p>
            </li>
          </ul>
          <ul v-else-if="showAgentSuggest && filteredAgents.length && !loading" class="skill-suggest">
            <li
              v-for="(agent, idx) in filteredAgents"
              :key="agent.id"
              :class="{ 'is-highlighted': idx === agentSuggestIndex }"
              @mousedown.prevent="applyAgentSuggest(agent)"
            >
              <div class="skill-suggest-main">
                <span class="skill-suggest-id is-agent">${{ agent.id }}</span>
                <span class="skill-suggest-title">{{ agent.displayName }}</span>
              </div>
              <p v-if="agent.description" class="skill-suggest-desc">{{ agent.description }}</p>
            </li>
          </ul>
          <ul v-else-if="showSkillSuggest && filteredSkills.length && !loading" class="skill-suggest">
            <li
              v-for="(skill, idx) in filteredSkills"
              :key="skill.id"
              :class="{ 'is-highlighted': idx === skillSuggestIndex }"
              @mousedown.prevent="applySkillSuggest(skill)"
            >
              <div class="skill-suggest-main">
                <span class="skill-suggest-id is-skill">/{{ skill.id }}</span>
                <span class="skill-suggest-title">{{ skill.displayName }}</span>
              </div>
              <p v-if="skill.description" class="skill-suggest-desc">{{ skill.description }}</p>
            </li>
          </ul>
          <div class="composer-input-area">
            <div v-if="voiceListening" class="voice-recording-overlay">
              <div class="voice-waveform">
                <span class="voice-waveform-bar" />
                <span class="voice-waveform-bar" />
                <span class="voice-waveform-bar" />
                <span class="voice-waveform-bar" />
                <span class="voice-waveform-bar" />
              </div>
              <div class="voice-recording-body">
                <span class="voice-rec-indicator">
                  <span class="voice-rec-dot" />
                  <span class="voice-rec-label">录音中</span>
                </span>
                <span class="voice-recording-text">{{ voiceDisplayText || '正在聆听...' }}</span>
              </div>
            </div>
            <ComposerSkillInput
              v-show="!voiceListening"
              ref="inputRef"
              v-model="inputText"
              :disabled="voiceListening"
              :allows-skill-mention="skillMentionAllowed"
              :allows-agent-mention="agentMentionAllowed"
              :allows-workflow-mention="workflowMentionAllowed"
              :catalog="skillCatalog"
              :agent-catalog="agentCatalog"
              :workflow-catalog="workflowCatalog"
              :placeholder="composerPlaceholder"
              @keydown="handleKeydown"
            />
            <div class="composer-toolbar">
              <div class="composer-toolbar-left">
                <template v-if="chatStore.newTaskMode || chatStore.pendingWorkspace || (isCurrentTask && currentWorkspaceId)">
                  <GitBranchSelector
                    :workspace-id="currentWorkspaceId ?? (chatStore.pendingWorkspace?.wsId ?? '')"
                    :model-value="taskBranch"
                    :active-branch="taskActiveBranch"
                    :create-mode="!!(chatStore.newTaskMode || chatStore.pendingWorkspace)"
                    @update:model-value="taskBranch = $event"
                  />
                </template>
                <template v-else-if="!(chatStore.newTaskMode || chatStore.pendingWorkspace)">
                  <ExecutionModeSelector
                    :model-value="preference"
                    @update:model-value="setPreference"
                  />
                </template>
                <KbSelector
                  v-if="!voiceListening"
                  :kbs="chatKbs"
                  :model-value="kbId"
                  :loading="loadingChatKbs"
                  :show-create="false"
                  @update:model-value="onKbChange"
                />
              </div>
              <div class="composer-toolbar-right">
                <button
                  v-if="loading"
                  type="button"
                  class="composer-icon-btn pause"
                  title="暂停当前生成"
                  @click="handleComposerPause"
                >
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor"><rect x="3" y="3" width="10" height="10" rx="1.5"/></svg>
                </button>
                <template v-else-if="voiceListening">
                  <button
                    type="button"
                    class="composer-icon-btn voice-cancel"
                    title="取消语音输入"
                    @click="handleVoiceCancel"
                  >
                    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><line x1="4" y1="4" x2="12" y2="12"/><line x1="12" y1="4" x2="4" y2="12"/></svg>
                  </button>
                  <button
                    type="button"
                    class="composer-icon-btn voice-confirm"
                    title="确认语音输入"
                    @click="handleVoiceConfirm"
                  >
                    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3,8 7,12 14,3"/></svg>
                  </button>
                </template>
                <template v-else>
                  <VoiceInputButton v-if="voiceSupported" />
                  <button
                    v-if="!voiceSupported || inputText.trim()"
                    type="button"
                    class="composer-icon-btn send"
                    :disabled="!inputText.trim()"
                    title="发送"
                    @click="handleSend"
                  >
                    <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M2 8l12-6-6 12-2-6-4-0z" fill="currentColor"/></svg>
                  </button>
                </template>
              </div>
            </div>
          </div>
        </div>
        <p class="composer-hint">AI 生成内容仅供参考，请核实重要信息</p>
      </div>
    </footer>
      </div>
      <PlanNodeDrawer />
      <SandboxWorkspaceDrawer
        :workspace-id="currentWorkspaceId ?? chatStore.pendingWorkspace?.wsId ?? null"
        :checkout-id="taskCheckoutId"
        :workspace-name="currentWorkspaceName"
      >
        <template v-if="isCurrentTask && currentWorkspaceId" #head-actions-pre>
          <div class="git-dropdown-wrap">
            <!-- 隐藏测量：完整分支名气泡的自然宽度（不参与布局），用于判断气泡是否会被挤压 -->
            <span ref="branchFullNameRef" class="branch-fullname-measure" aria-hidden="true">
              <NIcon :size="14" :component="GitBranchOutline" />
              <span class="git-dropdown-label">{{ taskBranch || taskCheckoutId }}</span>
              <NIcon :size="12" :component="ChevronDownOutline" class="git-dropdown-chevron" />
            </span>
            <NPopover trigger="click" placement="bottom-end" :width="150" raw :show-arrow="false">
              <template #trigger>
                <button type="button" class="git-dropdown-trigger" title="分支信息">
                  <NIcon :size="14" :component="GitBranchOutline" />
                  <!-- 气泡边框将碰到相邻元素时收缩为「分支」两字，空间宽裕后还原完整分支名 -->
                  <span ref="branchLabelRef" class="git-dropdown-label">{{ branchCollapsed ? '分支' : (taskBranch || taskCheckoutId) }}</span>
                  <NIcon :size="12" :component="ChevronDownOutline" class="git-dropdown-chevron" />
                </button>
              </template>
              <div class="git-dropdown-menu">
                <button
                  type="button"
                  class="git-dd-item"
                  :disabled="gitOpState !== 'idle' || !taskCheckoutId"
                  title="暂存全部改动"
                  @click="handleGitStage"
                >
                  <span v-if="gitOpKind === 'stage' && gitOpState === 'loading'" class="git-btn-spinner" />
                  <NIcon v-else-if="gitOpKind === 'stage' && gitOpState === 'done'" :size="14" :component="CheckmarkOutline" />
                  <NIcon v-else :size="14" :component="AddOutline" />
                  <span>{{ gitOpKind === 'stage' && gitOpState !== 'idle' ? '' : '暂存' }}</span>
                </button>
                <button
                  type="button"
                  class="git-dd-item"
                  :disabled="gitOpState !== 'idle' || !taskCheckoutId"
                  @click="openCommitPopover"
                >
                  <NIcon :size="14" :component="CreateOutline" /><span>提交</span>
                </button>
                <button
                  type="button"
                  class="git-dd-item"
                  :disabled="gitOpState !== 'idle' || !taskCheckoutId"
                  title="推送到远端"
                  @click="handleGitPush"
                >
                  <span v-if="gitOpKind === 'push' && gitOpState === 'loading'" class="git-btn-spinner" />
                  <NIcon v-else-if="gitOpKind === 'push' && gitOpState === 'done'" :size="14" :component="CheckmarkOutline" />
                  <NIcon v-else :size="14" :component="CloudUploadOutline" />
                  <span>{{ gitOpKind === 'push' && gitOpState !== 'idle' ? '' : '推送' }}</span>
                </button>
                <button
                  type="button"
                  class="git-dd-item"
                  :disabled="gitOpState !== 'idle' || !taskCheckoutId"
                  title="从远端拉取"
                  @click="handleGitPull"
                >
                  <span v-if="gitOpKind === 'pull' && gitOpState === 'loading'" class="git-btn-spinner" />
                  <NIcon v-else-if="gitOpKind === 'pull' && gitOpState === 'done'" :size="14" :component="CheckmarkOutline" />
                  <NIcon v-else :size="14" :component="CloudDownloadOutline" />
                  <span>{{ gitOpKind === 'pull' && gitOpState !== 'idle' ? '' : '拉取' }}</span>
                </button>
              </div>
            </NPopover>
            <!-- 提交信息输入：侧边二级 Popover（替代弹窗） -->
            <NPopover
              trigger="manual"
              placement="bottom-end"
              :width="300"
              :show="showCommitPopover"
              :show-arrow="false"
              raw
            >
              <template #trigger>
                <span class="commit-popover-anchor" />
              </template>
              <div class="commit-popover">
                <div class="commit-popover-head">
                  <NIcon :size="14" :component="CreateOutline" />
                  <span>提交变更</span>
                  <button type="button" class="commit-popover-close" title="关闭" @click="showCommitPopover = false">×</button>
                </div>
                <textarea
                  v-model="commitMsg"
                  class="commit-popover-input"
                  placeholder="feat: 描述你的变更…"
                  maxlength="256"
                  rows="3"
                  spellcheck="false"
                  @keydown.enter.exact.prevent="handleGitCommit"
                  @keydown.esc="showCommitPopover = false"
                />
                <div class="commit-popover-actions">
                  <span class="commit-popover-hint">Enter 提交 · Esc 取消</span>
                  <NButton size="small" quaternary @click="showCommitPopover = false">取消</NButton>
                  <NButton
                    size="small"
                    type="primary"
                    :disabled="!commitMsg.trim() || commitState !== 'idle'"
                    :loading="commitState === 'loading'"
                    @click="handleGitCommit"
                  >
                    <template v-if="commitState === 'done'">
                      <NIcon :component="CheckmarkOutline" :size="14" /> 已提交
                    </template>
                    <template v-else>提交</template>
                  </NButton>
                </div>
              </div>
            </NPopover>
          </div>
        </template>
      </SandboxWorkspaceDrawer>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  position: relative;
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
  overflow-x: auto;
}

/* 双开：三栏各 min 420 */
.chat-body--both-drawers {
  min-width: 1260px;
}

.chat-attention-bubble.is-completed {
  border-color: color-mix(in srgb, #ef4444 40%, var(--sun-border));
  color: color-mix(in srgb, #f87171 75%, var(--sun-text-secondary));
}

/* 回到底部/折叠运行过程圆形按钮：右对齐，置于输入框上方所有气泡之上。
   离开底部 → 回到底部（待确认 → 黄色感叹号；对话完成 → 会话图标 + 红点；其余 → 向下箭头）；
   最底部且运行中时间线展开 → 折叠图标 */
.scroll-fab {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  margin-left: auto;
  margin-bottom: 8px;
  padding: 0;
  border: 1px solid var(--sun-border);
  border-radius: 50%;
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  box-shadow: var(--composer-shadow);
  cursor: pointer;
  flex-shrink: 0;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.scroll-fab:hover {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

.scroll-fab.is-hitl_pending {
  border-color: color-mix(in srgb, var(--sun-amber) 45%, var(--sun-border));
  color: color-mix(in srgb, var(--sun-amber-light) 75%, var(--sun-text-secondary));
}

/* 对话完成：会话图标右上角的未读红点 */
.scroll-fab-dot {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ef4444;
  border: 2px solid var(--sun-black);
}

.fab-fade-enter-active,
.fab-fade-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.fab-fade-enter-from,
.fab-fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

.chat-main {
  flex: 1;
  /* 与 CHAT_CONTENT_MIN_WIDTH 一致：底栏四控件单行 */
  min-width: 420px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  position: relative;
  /* 滚动区底部留白，避免最后一条回复贴住悬浮输入框 */
  --chat-composer-gap: 152px;
}

/* 放大 DAG 时抬高层叠，避免被右侧抽屉 z-index 挡住命中 */
.chat-main.plan-dag-expanded {
  z-index: 200;
  isolation: isolate;
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
  height: 36px;
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
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 8px;
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
  font-size: var(--sun-font-md);
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
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
}

/* workspace task：分支选择器 */
.ws-task-header {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.ws-task-folder {
  color: var(--sun-text-muted);
}

.ws-task-name {
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-task-sep {
  color: var(--sun-text-muted);
  font-size: var(--sun-font-sm);
}

/* ---- 工作区展开按钮：与抽屉 header 同带（会话头 36px 下、高 34px = padding6+tab22+padding6） ---- */
.ws-drawer-toggle {
  position: absolute;
  top: 36px;
  right: 8px;
  z-index: 20;
  width: 28px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.ws-drawer-toggle:hover {
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

/* ---- 工作区项目选择器（输入框上方，胶囊样式，动态宽度不截断） ---- */
.ws-project-pill {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 0 6px;
}

/* 对话前动作进行中气泡（新任务拉取分支 / 分支切换暂存-提交-切换）：三点动画 + 纯文案（无省略号） */
.pre-action-bubble {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  padding: 0 12px;
  border-radius: var(--radius-lg, 12px);
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  white-space: nowrap;
  flex-shrink: 0;
}

/* 分支切换气泡：独立一行，右对齐显示于输入框上方 */
.pre-action-bubble--float {
  margin-left: auto;
  margin-bottom: 8px;
}

.pre-action-text {
  white-space: nowrap;
}

.ws-project-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  border: none;
  border-radius: var(--radius-lg, 12px);
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm, 12px);
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s;
}

.ws-project-trigger:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.ws-project-label {
  font-weight: 500;
  white-space: nowrap;
}

.ws-project-chevron {
  opacity: 0.55;
  flex-shrink: 0;
}

.ws-project-menu {
  padding: 3px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, var(--sun-black));
  box-shadow: var(--shadow-elevated, 0 4px 12px rgba(0, 0, 0, 0.12));
  border: 1px solid var(--sun-border, #e8e8e8);
  overflow: hidden;
}

.ws-project-menu-empty {
  padding: 12px 10px;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  text-align: center;
}

.ws-project-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  border-radius: calc(var(--radius-md, 10px) - 2px);
  background: transparent;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s;
}

.ws-project-menu-item:hover {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
}

.ws-project-menu-icon {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
}

.ws-project-menu-item.is-selected .ws-project-menu-icon {
  color: var(--sun-text);
}

.ws-project-menu-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ws-project-menu-title {
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  color: var(--sun-text);
  white-space: nowrap;
}

.ws-project-menu-desc {
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ws-project-menu-check-slot {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
}

.ws-project-menu-check {
  color: var(--sun-text);
}

/* ---- 抽屉头部 Git 操作下拉 ---- */
.git-dropdown-wrap {
  display: inline-flex;
  margin-right: 4px;
  position: relative;
}

/* 隐藏测量：完整分支名气泡的克隆（与 trigger 同尺寸），不参与布局；宽度不受 flex 压缩影响 */
.branch-fullname-measure {
  position: absolute;
  visibility: hidden;
  pointer-events: none;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 10px;
  white-space: nowrap;
  font-size: var(--sun-font-sm, 12px);
}

.git-dropdown-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 10px;
  border: none;
  border-radius: var(--radius-lg, 12px);
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm, 12px);
  font-family: inherit;
  cursor: pointer;
  flex-shrink: 1;
  min-width: 0;
  max-width: 100%;
  transition: background 0.15s, color 0.15s;
}

.git-dropdown-trigger:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.git-dropdown-label {
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.git-dropdown-menu {
  padding: 3px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, var(--sun-black));
  box-shadow: var(--shadow-elevated, 0 4px 12px rgba(0, 0, 0, 0.12));
  border: 1px solid var(--sun-border, #e8e8e8);
  overflow: hidden;
}

.git-dd-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-radius: calc(var(--radius-md, 10px) - 2px);
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm, 12px);
  font-family: inherit;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s;
}

.git-dd-item:hover:not(:disabled) {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.git-dd-item:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* 操作进行中：仅显示 spinner/√，保持居中 */
.git-dd-item.is-busy {
  justify-content: center;
}

/* 分支下拉按钮内 loading 转圈（与 diff 面板提交按钮一致） */
.git-btn-spinner {
  display: inline-block;
  width: 13px;
  height: 13px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: git-btn-spin 0.7s linear infinite;
  flex-shrink: 0;
}

@keyframes git-btn-spin {
  to {
    transform: rotate(360deg);
  }
}

/* ---- Git 提交侧边 Popover（替代弹窗） ---- */
.commit-popover-anchor { display: inline-block; width: 0; height: 0; }
.commit-popover {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px 14px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, var(--sun-black, #212121));
  border: 1px solid var(--sun-border, #5a5a5a);
  box-shadow: var(--shadow-elevated, 0 6px 20px rgba(0, 0, 0, 0.35));
}
.commit-popover-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 600;
  color: var(--sun-text);
}
.commit-popover-close {
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 0 4px;
}
.commit-popover-close:hover { color: var(--sun-text); }
.commit-popover-input {
  width: 100%;
  min-height: 64px;
  resize: vertical;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: var(--sun-black);
  color: var(--sun-text);
  font-size: var(--sun-font-base, 14px);
  font-family: inherit;
  outline: none;
  transition: border-color 0.15s;
  box-sizing: border-box;
}
.commit-popover-input:focus { border-color: var(--sun-accent); }
.commit-popover-input::placeholder { color: var(--sun-text-muted); }
.commit-popover-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 2px;
}
.commit-popover-hint {
  margin-right: auto;
  font-size: 11px;
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
  border-radius: var(--radius-lg, 12px);
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

/* 触顶加载更早消息时的 loading 指示（无文字，避免误以为卡顿） */
.history-load-bar {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 8px 0;
}

/* 视口外消息跳过布局/绘制：多轮长对话滚动不卡；
   contain-intrinsic-size 记忆实际高度，滚动条不跳动（auto 需 Chrome 107+） */
.msg-block {
  content-visibility: auto;
  contain-intrinsic-size: auto 320px;
}

.msg-block.user {
  display: flex;
  justify-content: flex-end;
}

.user-bubble {
  max-width: 85%;
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
  align-items: center;
  gap: 8px;
}

/* 对话结束时间：hover 消息行时显示在复制按钮右侧 */
.msg-end-time {
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1;
  opacity: 0;
  transition: opacity 0.15s ease;
  user-select: none;
}

.msg-block:hover .msg-end-time {
  opacity: 1;
}

.msg-resume-bar {
  margin-top: 8px;
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

/* 运行期间 todolist 滚出视口时悬浮于输入框上方，样式与下方输入框一致（圆角/阴影对齐） */
.floating-taskboard {
  margin-bottom: 8px;
  padding: 0;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  border-radius: 20px;
  box-shadow: var(--composer-shadow);
}

.floating-taskboard :deep(.taskboard-wrap) {
  margin: 0;
}

/* ---- 发送前分支切换：提交确认框（样式对齐 todolist 卡片）/ 切换步骤气泡 ---- */
.branch-switch-commit {
  margin-bottom: 8px;
  padding: 12px 14px 14px;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  border-radius: 20px;
  box-shadow: var(--composer-shadow);
}
.branch-switch-commit-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 600;
  color: var(--sun-text);
}
.branch-switch-close {
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 0 4px;
}
.branch-switch-close:hover { color: var(--sun-text); }
.branch-switch-desc {
  margin: 8px 0 10px;
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.5;
  color: var(--sun-text-secondary);
}
.branch-switch-input {
  width: 100%;
  min-height: 56px;
  resize: vertical;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: var(--sun-black);
  color: var(--sun-text);
  font-size: var(--sun-font-base, 14px);
  font-family: inherit;
  outline: none;
  transition: border-color 0.15s;
  box-sizing: border-box;
}
.branch-switch-input:focus { border-color: var(--sun-accent); }
.branch-switch-input::placeholder { color: var(--sun-text-muted); }
.branch-switch-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 10px;
}
.branch-switch-kbd {
  margin-right: auto;
  font-size: 11px;
  color: var(--sun-text-muted);
}

/* ---- 对话前动作出错详情卡片（拉取分支 / 切换分支失败）：todolist 同款样式，非红色，最小高度，右上角可关闭 ---- */
.pre-action-error {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
  padding: 12px 14px 14px;
  min-height: 92px;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  border-radius: 20px;
  box-shadow: var(--composer-shadow);
}
.pre-action-error-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 600;
  color: var(--sun-text);
}
.pre-action-error-close {
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 0 4px;
}
.pre-action-error-close:hover { color: var(--sun-text); }
.pre-action-error-body {
  flex: 1;
  min-height: 0;
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.5;
  color: var(--sun-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  overflow-y: auto;
}
.pre-action-error-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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
  flex-wrap: nowrap;
  gap: 8px;
  padding-top: 4px;
  min-height: 34px;
}

.composer-toolbar-left {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  flex-shrink: 0;
  gap: 8px;
  min-width: 0;
}

.composer-toolbar-right {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  flex-shrink: 0;
  gap: 4px;
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
  align-items: center;
  gap: 8px;
}

.skill-suggest-desc,
.skill-suggest-meta {
  margin: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1.4;
  padding-left: 2px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.skill-suggest-id.is-agent {
  color: var(--mention-agent-prefix);
}

.skill-suggest-id.is-workflow {
  color: var(--mention-workflow-prefix);
}

.skill-suggest-id.is-path {
  color: var(--mention-path-prefix);
}

/* 第一行中文名（id 之后） */
.skill-suggest-title {
  color: var(--sun-text);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-suggest li.path-suggest-item {
  flex-direction: column;
  align-items: stretch;
  gap: 2px;
}

.path-suggest-icon {
  display: inline-flex;
  flex-shrink: 0;
  color: var(--mention-path-prefix);
}

.path-suggest-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-suggest-loading {
  cursor: default;
  color: var(--sun-text-muted);
}

.skill-suggest-loading:hover {
  background: transparent;
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

[data-theme="dark"] .composer-icon-btn.send {
  background: #ececec;
  color: #212121;
}

[data-theme="dark"] .composer-icon-btn.send:hover:not(:disabled) {
  background: #ffffff;
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

.composer-icon-btn.pause {
  background: transparent;
  border: 1px solid var(--sun-border);
  color: var(--sun-text-secondary);
}

[data-theme="dark"] .composer-icon-btn.pause {
  border-color: #5a5a5a;
  color: #b4b4b4;
}

.composer-icon-btn.pause:hover {
  border-color: var(--sun-red);
  color: var(--sun-red);
  background: rgba(248, 113, 113, 0.08);
}

/* 语音录音覆盖层 */
.voice-recording-overlay {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 14px;
  min-height: 52px;
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(248, 113, 113, 0.06), rgba(248, 113, 113, 0.02));
  border: 1px solid rgba(248, 113, 113, 0.18);
  box-shadow: 0 0 12px rgba(248, 113, 113, 0.06);
}

/* 音频波形条容器 */
.voice-waveform {
  display: flex;
  align-items: center;
  gap: 3px;
  flex-shrink: 0;
  height: 32px;
}

.voice-waveform-bar {
  width: 3px;
  height: 0;
  border-radius: 2px;
  background: var(--sun-red);
  opacity: 0;
  animation: voice-wave 1.6s ease-in-out infinite;
}

.voice-waveform-bar:nth-child(1) { animation-delay: 0.00s; }
.voice-waveform-bar:nth-child(2) { animation-delay: 0.12s; }
.voice-waveform-bar:nth-child(3) { animation-delay: 0.24s; }
.voice-waveform-bar:nth-child(4) { animation-delay: 0.36s; }
.voice-waveform-bar:nth-child(5) { animation-delay: 0.48s; }

@keyframes voice-wave {
  0%, 100% { height: 8px; opacity: 0.35; }
  25% { height: 28px; opacity: 0.9; }
  50% { height: 14px; opacity: 0.55; }
  75% { height: 32px; opacity: 0.85; }
}

/* 录音主体：指示器 + 识别文本 */
.voice-recording-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
  min-width: 0;
}

/* 录音指示灯 + 标签 */
.voice-rec-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.voice-rec-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--sun-red);
  animation: voice-rec-blink 1.8s ease-in-out infinite;
}

@keyframes voice-rec-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.25; }
}

.voice-rec-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--sun-red);
  text-transform: uppercase;
}

.voice-recording-text {
  font-size: var(--sun-font-base);
  color: var(--sun-text);
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.composer-icon-btn.voice-cancel {
  background: transparent;
  border: 1px solid var(--sun-border);
  color: var(--sun-text-secondary);
  transition: all 0.2s;
}

.composer-icon-btn.voice-cancel:hover {
  border-color: var(--sun-red);
  color: var(--sun-red);
  background: rgba(248, 113, 113, 0.08);
}

.composer-icon-btn.voice-confirm {
  background: var(--sun-accent);
  color: var(--btn-primary-text);
  box-shadow: 0 0 8px rgba(0, 0, 0, 0.1);
  transition: all 0.2s;
}

[data-theme="dark"] .composer-icon-btn.voice-confirm {
  background: #ececec;
  color: #212121;
  box-shadow: 0 0 8px rgba(255, 255, 255, 0.08);
}

.composer-icon-btn.voice-confirm:hover {
  background: var(--sun-accent-hover);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.2);
}

[data-theme="dark"] .composer-icon-btn.voice-confirm:hover {
  background: #ffffff;
  box-shadow: 0 2px 12px rgba(255, 255, 255, 0.12);
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

<style>
/* 工作区项目选择器 Popover 全局样式（修复直角） */
.n-popover.n-popover--raw:has(.ws-project-menu),
.n-popover-shared:has(.ws-project-menu) {
  box-shadow: none !important;
  background: transparent !important;
  border-radius: 0 !important;
  padding: 0 !important;
}

.ws-project-popover {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}
.ws-project-popover .n-popover__content,
.ws-project-popover .v-binder-follower-content {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}

/* 分支操作 Git 下拉 Popover 全局样式（修复直角阴影残余）：内层 .git-dropdown-menu
   自带圆角 + 阴影，外层 Naive UI 容器直角 box-shadow 会从四角露出，去掉它 */
.n-popover.n-popover--raw:has(.git-dropdown-menu),
.n-popover-shared:has(.git-dropdown-menu) {
  box-shadow: none !important;
  background: transparent !important;
  border-radius: 0 !important;
  padding: 0 !important;
}
</style>
