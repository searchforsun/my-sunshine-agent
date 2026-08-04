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
import { useSandboxPathIndex } from '../composables/useSandboxPathIndex'
import { useChatStreamMarkdown } from '../composables/useChatStreamMarkdown'
import { reEnhanceAllSandboxPathLinks } from '../utils/stream-markdown/StaticEnhancer'
import { useChatSessionHydration } from '../composables/useChatSessionHydration'
import { useChatStore } from '../stores/chatStore'
import { isValidConversationId } from '../api/conversations'
import { useTheme } from '../composables/useTheme'
import { useSidebar } from '../composables/useSidebar'
import { listWorkspaces } from '../api/workspaces'
import type { WorkspaceVO } from '../api/workspaces'
import { gitStage, gitCommit, gitPush, gitPull, ensureCheckout, listCheckouts } from '../api/workspaceGit'
import { loadActiveGeneration } from '../composables/useActiveGeneration'
import CopyToggleIcon from '../components/icons/CopyToggleIcon.vue'
import { NIcon, NPopover, NButton } from 'naive-ui'
import { DocumentTextOutline, FolderOutline, ChevronDownOutline, GitBranchOutline, AddOutline, CloudUploadOutline, CloudDownloadOutline, CheckmarkOutline, CreateOutline } from '@vicons/ionicons5'
import OperationStack from '../components/operation/OperationStack.vue'
import PlanNodeDrawer from '../components/plan/PlanNodeDrawer.vue'
import SandboxWorkspaceDrawer from '../components/sandbox/SandboxWorkspaceDrawer.vue'
import PlanDagExpandLayer from '../components/plan/PlanDagExpandLayer.vue'
import GitBranchSelector from '../components/chat/GitBranchSelector.vue'
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
    const apply = () => bridge.syncStreamFromContent(resolveStreamingContentText(last))
    void bridge.ensureStreamRenderer().then(apply)
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
)

const {
  scrollRef,
  chatScrollPinned,
  forceChatScroll,
  onChatScroll,
  onChatWheelCapture,
  scrollToBottom,
  pinScrollForSend,
  forwardWheelToChatScroll,
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

const EMPTY_HINTS = [
  { label: '青松假政策', prompt: '#knowledge-qa 青松假有多少天、怎么申请' },
  { label: '网约车上限', prompt: '#knowledge-qa 市内网约车报销上限多少' },
  { label: '双路检索', prompt: '#knowledge-dual 青松假和网约车报销上限一起查' },
  { label: '假期助手', prompt: '#hr-leave-assist 青松假还有几天，列出我的请假单' },
  { label: '费用合规', prompt: '#expense-compliance 对照网约车制度看我的报销是否合规' },
  { label: 'OA 待办', prompt: '#oa-task-assist 我的 OA 待办有哪些' },
] as const

function applyEmptyHint(prompt: string) {
  inputText.value = prompt
  void handleSend()
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

const staging = ref(false)
const committing = ref(false)
const commitMsg = ref('')
/** 提交信息输入用侧边二级 Popover（替代原 NModal 弹窗） */
const showCommitPopover = ref(false)
const pushing = ref(false)
const pulling = ref(false)
/** git 操作内联提示（成功/失败/无可操作 checkout） */
const gitToast = ref<{ kind: 'info' | 'success' | 'error'; text: string } | null>(null)
let gitToastTimer: ReturnType<typeof setTimeout> | null = null
function flashGitToast(kind: 'info' | 'success' | 'error', text: string) {
  gitToast.value = { kind, text }
  if (gitToastTimer) clearTimeout(gitToastTimer)
  gitToastTimer = setTimeout(() => { gitToast.value = null }, 2800)
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

async function handleGitStage() {
  const wsId = currentWorkspaceId.value
  const cid = requireCheckout()
  if (!wsId || !cid) return
  staging.value = true
  try { await gitStage(wsId, cid, undefined, true); flashGitToast('success', '已暂存所有改动') }
  catch (e) { flashGitToast('error', (e as Error)?.message || '暂存失败') }
  finally { staging.value = false }
}

function openCommitPopover() {
  commitMsg.value = ''
  showCommitPopover.value = true
}

async function handleGitCommit() {
  const wsId = currentWorkspaceId.value
  const cid = requireCheckout()
  if (!wsId || !cid) return
  const msg = commitMsg.value.trim()
  if (!msg) return
  showCommitPopover.value = false
  committing.value = true
  try { await gitCommit(wsId, cid, msg); commitMsg.value = ''; flashGitToast('success', '提交成功') }
  catch (e) { flashGitToast('error', (e as Error)?.message || '提交失败'); showCommitPopover.value = true }
  finally { committing.value = false }
}

async function handleGitPush() {
  const wsId = currentWorkspaceId.value
  const cid = requireCheckout()
  if (!wsId || !cid) return
  pushing.value = true
  try { await gitPush(wsId, cid); flashGitToast('success', '推送成功') }
  catch (e) { flashGitToast('error', (e as Error)?.message || '推送失败') }
  finally { pushing.value = false }
}

async function handleGitPull() {
  const wsId = currentWorkspaceId.value
  const cid = requireCheckout()
  if (!wsId || !cid) return
  pulling.value = true
  try { await gitPull(wsId, cid); flashGitToast('success', '拉取成功') }
  catch (e) { flashGitToast('error', (e as Error)?.message || '拉取失败') }
  finally { pulling.value = false }
}

function canCopyAssistant(msg: { role: string; content: string }, idx: number): boolean {
  return msg.role === 'assistant'
    && !!msg.content.trim()
    && !(loading.value && idx === messages.value.length - 1)
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
  if (!text || loading.value || wsPreparing.value) return
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
    }
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
    if (skillBinding.skillId) {
      requestSandboxWorkspaceRefresh(convId, 'skills', true)
    }
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
  if (newId) {
    try {
      sandboxWorkspaceActive.value = await fetchSandboxWorkspaceStatus(newId)
    } catch {
      sandboxWorkspaceActive.value = false
    }
  }
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
  ensureActive(newId)
  updateConversationId(newId)
  applyConversationPreference(chatStore.current?.executionPreference)
  applyConversationKb(chatStore.current?.kbId)
  void applyConversationCheckout()
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
      @scroll="onChatScroll"
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
            <svg width="40" height="40" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="14" stroke="currentColor" stroke-width="1.2" opacity="0.35" />
              <circle cx="24" cy="24" r="5" fill="currentColor" opacity="0.5" />
            </svg>
          </div>
          <h2 class="empty-title">有什么可以帮你的？</h2>
          <p class="empty-desc">知识库检索 · ReAct 工具 · Plan 动态规划 · Skill / 触发</p>
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
                :execution-plan-id="msg.executionPlanId"
                :user-query="resolveUserQuery(idx)"
                :message-id="msg.id"
                :message-status="msg.status ?? 'completed'"
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
          <!-- 新任务发送前同步拉取分支代码：等待气泡 / 失败重试（项目选择气泡右侧） -->
          <div v-if="wsPreparing" class="ws-prepare-bubble">
            <span class="typing-dots"><span class="dot"/><span class="dot"/><span class="dot"/></span>
            <span class="ws-prepare-text">正在拉取分支代码到工作区...</span>
          </div>
          <div v-else-if="wsPrepareError" class="ws-prepare-error">
            <span class="ws-prepare-text">{{ wsPrepareError }}</span>
            <button type="button" class="resume-btn" @click="handleSend">重试</button>
          </div>
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
            <div v-if="loading" class="composer-status">
              <span class="streaming-pulse" />
              <span>AI 正在回复…</span>
            </div>
            <ComposerSkillInput
              v-else
              ref="inputRef"
              v-model="inputText"
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
                    :disabled="loading"
                    @update:model-value="setPreference"
                  />
                </template>
                <KbSelector
                  :kbs="chatKbs"
                  :model-value="kbId"
                  :loading="loadingChatKbs"
                  :show-create="false"
                  @update:model-value="onKbChange"
                />
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
      <SandboxWorkspaceDrawer
        :workspace-id="currentWorkspaceId ?? chatStore.pendingWorkspace?.wsId ?? null"
        :checkout-id="taskCheckoutId"
        :workspace-name="currentWorkspaceName"
      >
        <template v-if="isCurrentTask && currentWorkspaceId" #head-actions-pre>
          <div class="git-dropdown-wrap">
            <NPopover trigger="click" placement="bottom-end" :width="220" raw :show-arrow="false">
              <template #trigger>
                <button type="button" class="git-dropdown-trigger" title="分支信息">
                  <NIcon :size="14" :component="GitBranchOutline" />
                  <span class="git-dropdown-label">{{ taskBranch || taskCheckoutId }}</span>
                  <NIcon :size="12" :component="ChevronDownOutline" />
                </button>
              </template>
              <div class="git-dropdown-menu">
                <button type="button" class="git-dd-item" :disabled="staging || !taskCheckoutId" @click="handleGitStage">
                  <NIcon :size="14" :component="AddOutline" /><span>暂存所有</span>
                </button>
                <button type="button" class="git-dd-item" :disabled="committing || !taskCheckoutId" @click="openCommitPopover">
                  <NIcon :size="14" :component="CreateOutline" /><span>提交</span>
                </button>
                <button type="button" class="git-dd-item" :disabled="pushing || !taskCheckoutId" @click="handleGitPush">
                  <NIcon :size="14" :component="CloudUploadOutline" /><span>推送</span>
                </button>
                <button type="button" class="git-dd-item" :disabled="pulling || !taskCheckoutId" @click="handleGitPull">
                  <NIcon :size="14" :component="CloudDownloadOutline" /><span>拉取</span>
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
                  <NButton size="small" type="primary" :disabled="!commitMsg.trim() || committing" :loading="committing" @click="handleGitCommit">提交</NButton>
                </div>
              </div>
            </NPopover>
          </div>
          <!-- git 操作内联提示 -->
          <transition name="git-toast-fade">
            <span v-if="gitToast" class="git-toast" :class="`git-toast--${gitToast.kind}`">{{ gitToast.text }}</span>
          </transition>
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

/* ---- 工作区抽屉切换按钮（任务行下方右上角） ---- */
.ws-drawer-toggle {
  position: absolute;
  top: 52px;
  right: 8px;
  z-index: 20;
  width: 28px;
  height: 28px;
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

/* 新任务发送前同步拉取分支代码：等待气泡 / 失败重试（项目选择气泡右侧） */
.ws-prepare-bubble,
.ws-prepare-error {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: var(--sun-font-sm, 12px);
  flex-shrink: 0;
}

.ws-prepare-bubble {
  border: 1px solid var(--sun-border);
  color: var(--sun-text-muted);
}

.ws-prepare-error {
  border: 1px solid rgba(239, 68, 68, 0.35);
  color: rgba(239, 68, 68, 0.9);
}

.ws-prepare-text {
  white-space: nowrap;
}

.ws-project-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm, 12px);
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.15s, color 0.15s;
}

.ws-project-trigger:hover {
  border-color: var(--sun-border-light);
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
  background: var(--n-color, #fff);
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
}

.git-dropdown-trigger {
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
  font-family: inherit;
  cursor: pointer;
  flex-shrink: 0;
  transition: border-color 0.15s, color 0.15s;
}

.git-dropdown-trigger:hover {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}

.git-dropdown-label {
  font-weight: 500;
}

.git-dropdown-menu {
  padding: 3px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, #fff);
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

/* ---- Git 提交侧边 Popover（替代弹窗） ---- */
.commit-popover-anchor { display: inline-block; width: 0; height: 0; }
.commit-popover {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px 14px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, var(--sun-black, #0a0a0a));
  border: 1px solid var(--sun-border, #2a2a2a);
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

/* ---- Git 操作内联提示 ---- */
.git-toast {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 999px;
  max-width: 220px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.git-toast--success { color: #4ade80; background: rgba(34, 197, 94, 0.12); }
.git-toast--error { color: #f87171; background: rgba(239, 68, 68, 0.12); }
.git-toast--info { color: var(--sun-text-muted); background: rgba(148, 163, 184, 0.12); }
.git-toast-fade-enter-active, .git-toast-fade-leave-active { transition: opacity 0.2s; }
.git-toast-fade-enter-from, .git-toast-fade-leave-to { opacity: 0; }

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
</style>
