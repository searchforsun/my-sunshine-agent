<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, provide, ref, watch, type ComputedRef } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import type { ContentBlock } from '../../api/contentInterleave'
import type { HitlConfirmationPayload } from '../../api/hitlSteps'
import { findHitlStep, isRecoveryAwaiting, isRecoverySkipped, stepHasHitlAwaiting } from '../../api/recoverySteps'
import { isHitlSummaryAwaiting } from '../../api/hitlSteps'
import HitlStepActions from '../operation/HitlStepActions.vue'
import {
  formatDuration,
  resolvePlanStepDetail,
  resolveRewriteDetail,
  resolveStepDurationMs,
  resolveStepExpandPanels,
  stepLifecycle,
  stripLoadedSkillPrefix,
  type TimelineMessageStatus,
} from '../../api/processingSteps'
import { formatPlanNodeType } from '../../api/executionPlans'
import type { DagNodeStatus } from '../../utils/planGraph'
import type { PlanNodeAttempt } from '../../api/executionPlans'
import PlanNodeIcon from './PlanNodeIcon.vue'
import DrawerCollapseIcon from '../icons/DrawerCollapseIcon.vue'
import StaticMarkdown from '../StaticMarkdown.vue'
import PlanNodeRecoveryActions from './PlanNodeRecoveryActions.vue'
import OperationStack from '../operation/OperationStack.vue'
import { usePlanNodeDrawer } from '../../composables/usePlanNodeDrawer'
import {
  CHAT_UNPIN_THRESHOLD_PX,
  CHAT_REPIN_THRESHOLD_PX,
  distanceFromChatBottom,
  resolveChatScrollPinned,
} from '../../composables/chatScrollPin'
import { usePlanDagExpand } from '../../composables/usePlanDagExpand'
import { resolveExclusiveBranches } from '../../utils/exclusiveBranchDisplay'
import { resolveLoopContinueRows } from '../../utils/loopContinueDisplay'
import { groupLoopBodySubStepsByRound } from '../../utils/loopBodyRoundGroups'

const { state, close, goBack, depth, drawerWidth, canResizeDrawer, onResizePointerDown } = usePlanNodeDrawer()
const { isAnyExpanded: planDagExpanded } = usePlanDagExpand()
/** 抽屉内嵌套卡（worker 抽屉 → 子 agent 卡）点击时 open 走 { push: true } 入栈，右上角逐层返回 */
provide('planNodeDrawerNested', true)
const applyHitlDecision = inject<(token: string, approved: boolean) => void>('applyHitlDecision', () => {})
const applyRecoveryDecision = inject<(token: string, action: 'retry' | 'terminate' | 'skip') => void>('applyRecoveryDecision', () => {})
const resolveLiveNodeStep = inject<(nodeId: string) => ProcessingStep | undefined>('planDrawerLiveNodeStep', () => undefined)
const pendingHitl = inject<ComputedRef<HitlConfirmationPayload | undefined>>(
  'pendingHitlConfirmation',
  computed(() => undefined),
)
const pendingHitlList = inject<ComputedRef<HitlConfirmationPayload[]>>(
  'pendingHitlConfirmations',
  computed(() => []),
)

const node = computed(() => state.node)
/** 优先从当前 assistant 消息 steps 读取，避免抽屉 step 快照滞后于 SSE HITL metadata */
const step = computed(() => {
  const nodeId = state.node?.id
  if (nodeId) {
    const live = resolveLiveNodeStep(nodeId)
    if (live) return live
  }
  return state.step
})
const userQuery = computed(() => state.userQuery?.trim() ?? '')

const title = computed(() => node.value?.label ?? '节点详情')
const typeLabel = computed(() => formatPlanNodeType(node.value?.type ?? ''))
const nodeType = computed(() => node.value?.type ?? 'node')

/** 优先 step.lifecycle，与 DAG 节点状态对齐；start 不跟 plan 步 running */
const displayStatus = computed((): DagNodeStatus => {
  if (node.value?.type === 'start') {
    return node.value.status === 'pending' ? 'pending' : 'done'
  }
  const stepLc = step.value ? stepLifecycle(step.value) : undefined
  const nodeStatus = node.value?.status
  if (stepLc === 'paused' || nodeStatus === 'paused') return 'paused'
  if (step.value && isRecoveryAwaiting(step.value)) return 'error'
  if (step.value && (stepHasHitlAwaiting(step.value) || isHitlSummaryAwaiting(step.value))) {
    return 'awaiting_confirm'
  }
  if (stepLc === 'terminated' || nodeStatus === 'terminated') return 'terminated'
  if (stepLc === 'skipped' || nodeStatus === 'skipped' || (step.value != null && isRecoverySkipped(step.value))) return 'skipped'
  if (stepLc === 'error' || nodeStatus === 'error') return 'error'
  if (stepLc === 'done' || nodeStatus === 'done') return 'done'
  if (nodeStatus === 'awaiting_confirm') return 'awaiting_confirm'
  if (stepLc === 'running' || nodeStatus === 'running') return 'running'
  return nodeStatus ?? 'pending'
})

/** 节点流式输出中：抽屉内 reasoning/正文暂缓路径增强，避免 v-html 重建闪烁 */
const isNodeStreaming = computed(() => displayStatus.value === 'running')

const statusLabel = computed(() => {
  const s = displayStatus.value
  if (node.value?.type === 'start') {
    if (s === 'pending') return '等待中'
    return '已通过'
  }
  // 终态话术只信 SSE after（禁止硬编码「已取消」/「已暂停」）
  if (s === 'paused') {
    return step.value?.summary?.after?.trim() || ''
  }
  if (s === 'terminated') return '已终止'
  if (s === 'awaiting_confirm') return '待确认'
  if (s === 'skipped') return '已跳过'
  if (s === 'error' && isRecoveryAwaiting(step.value)) return '发生错误'
  if (s === 'running') return '执行中'
  if (s === 'done') return '已完成'
  if (s === 'error') return '失败'
  return '等待中'
})

const liveElapsedMs = ref<number | null>(null)
let elapsedTimer: ReturnType<typeof setInterval> | null = null

function clearElapsedTimer() {
  if (elapsedTimer != null) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

watch(
  () => [displayStatus.value, step.value?.clientStartedAt] as const,
  ([status, clientStartedAt]) => {
    clearElapsedTimer()
    // 子 Agent / Workflow 节点 running 期间持续计时（客户端墙钟，完成后回归服务端 durationMs）
    if (status === 'running' && typeof clientStartedAt === 'number') {
      const tick = () => {
        liveElapsedMs.value = Math.max(0, Date.now() - clientStartedAt)
      }
      tick()
      elapsedTimer = setInterval(tick, 200)
    } else {
      liveElapsedMs.value = null
    }
  },
  { immediate: true },
)

onMounted(() => {
  window.addEventListener('pointerup', onDrawerPointerEnd)
  window.addEventListener('pointercancel', onDrawerPointerEnd)
})

onUnmounted(() => {
  clearElapsedTimer()
  teardownDrawerObserver()
  cancelFollowRaf()
  window.removeEventListener('pointerup', onDrawerPointerEnd)
  window.removeEventListener('pointercancel', onDrawerPointerEnd)
})

const durationText = computed(() => {
  if (displayStatus.value === 'running' && liveElapsedMs.value != null) {
    return formatDuration(liveElapsedMs.value)
  }
  const fromStep = step.value ? resolveStepDurationMs(step.value) : undefined
  const ms = fromStep ?? node.value?.durationMs
  return ms != null ? formatDuration(ms) : ''
})

const expandPanels = computed(() => (step.value ? resolveStepExpandPanels(step.value) : { lead: '', body: '' }))
const summary = computed(() => expandPanels.value.lead || node.value?.summary || '')

const analysisContent = computed(() => {
  const t = node.value?.type
  if (t === 'llm' || t === 'answer') {
    return step.value?.reasoning?.trim() || ''
  }
  return ''
})

const spawnPrompt = computed(() => step.value?.metadata?.spawnPrompt?.trim() ?? '')

/** ReAct spawn：仅多「传入提示词」；正文/思考与 Workflow agent 同构（contentBlocks + subSteps） */
const isSpawnSubagent = computed(() => {
  const s = step.value
  if (!s) return false
  return s.phase === 'subagent'
    || s.id.startsWith('subagent-')
    || !!s.metadata?.spawnPrompt
})

/** Planner-Executor worker：任务契约经 metadata.spawnPrompt 下发；正文/子步骤与 subagent 抽屉同构 */
const isWorkerNode = computed(() => {
  const t = node.value?.type === 'worker'
  const s = step.value
  return t
    || s?.phase === 'worker'
    || s?.id.startsWith('worker-')
})

const finalOutput = computed(() => {
  if (node.value?.type !== 'answer') return ''
  return step.value?.result?.trim() || ''
})

const showSpawnPrompt = computed(() => isSpawnSubagent.value && !!spawnPrompt.value)

const body = computed(() => {
  const t = node.value?.type
  if (t === 'llm') {
    return analysisContent.value
  }
  if (t === 'answer') {
    return finalOutput.value
  }
  if (step.value) return expandPanels.value.body
  return node.value?.detail?.trim() ?? ''
})
const bodyDisplay = computed(() => stripLoadedSkillPrefix(body.value))

const analysisDisplay = computed(() => stripLoadedSkillPrefix(analysisContent.value))

const bodySectionTitle = computed(() => {
  const t = node.value?.type
  if (t === 'answer') return '最终输出'
  if (t === 'llm') return '思考过程'
  return step.value?.metadata?.expandSectionTitle?.trim() || '详细输出'
})

const showAnalysisSection = computed(() =>
  (node.value?.type === 'answer' || node.value?.type === 'llm')
  && !!analysisDisplay.value,
)

const showSummary = computed(() => {
  // agent / worker 子 Timeline 已在「执行过程」展示，勿重复执行摘要
  if (node.value?.type === 'start' || node.value?.type === 'answer' || node.value?.type === 'llm' || node.value?.type === 'agent' || isWorkerNode.value) return false
  const bodyText = bodyDisplay.value
  if (bodyText) return false
  return !!summary.value.trim()
})
const rewriteDetail = computed(() => (step.value ? resolveRewriteDetail(step.value) : undefined))
const rewriteSectionTitle = computed(() => {
  const scenario = step.value?.metadata?.rewriteScenario
  if (scenario === 'intent') return '问句补全'
  return '检索优化'
})
const showRewriteDetail = computed(() => !!rewriteDetail.value)
const startPlan = computed(() => (step.value ? resolvePlanStepDetail(step.value) : { chainSteps: [] }))
const showStartPlan = computed(() => {
  if (node.value?.type !== 'start') return false
  const plan = startPlan.value
  return !!(plan.planId || plan.chainSteps.length || plan.replanCount)
})

const exclusiveBranches = computed(() => {
  if (node.value?.type !== 'exclusive-gateway') return []
  return resolveExclusiveBranches(state.graph, node.value.id)
})
const showExclusiveBranches = computed(() => exclusiveBranches.value.length > 0)

const loopContinueRows = computed(() => {
  if (node.value?.type !== 'loop') return []
  const graphNode = state.graph?.nodes?.find(n => n.id === node.value?.id)
  return resolveLoopContinueRows(graphNode?.params, step.value)
})
const showLoopContinue = computed(() => loopContinueRows.value.length > 0)

const showBodySection = computed(() => {
  if (node.value?.type === 'start') return false
  // agent（含 spawn）/ worker：正文已并入执行时间线（contentBlocks 穿插 + 终稿补段），无独立「详细输出」
  if (node.value?.type === 'agent' || isWorkerNode.value) return false
  return !!bodyDisplay.value
})
const showReasoningSection = computed(() =>
  node.value?.type !== 'llm'
  && node.value?.type !== 'answer'
  && !!step.value?.reasoning?.trim(),
)
const reasoning = computed(() => step.value?.reasoning?.trim() ?? '')
/** 与「详细输出」同文时不重复展示「日志」（loop 终态常双写 result/output） */
const output = computed(() => {
  const raw = step.value?.output?.trim() ?? ''
  if (!raw) return ''
  if (raw === bodyDisplay.value) return ''
  return raw
})

const subSteps = computed(() => step.value?.subSteps ?? [])
const showSubTimeline = computed(() =>
  (node.value?.type === 'agent' || isWorkerNode.value || node.value?.type === 'loop') && subSteps.value.length > 0)
/** agent / worker 抽屉时间线正文：contentBlocks 穿插 + 未承载的 result 终稿补段。
 * 终稿走 step_delta(result) 而未分段下发时（无 contentBlocks），补段到时间线末尾展示，
 * 使抽屉执行时间线与主 agent 一致；contentBlocks 已完整承载终稿时不重复。
 * worker 经 SubAgentContentTokens.route 双通道（contentBlocks 分段 + step_delta(result) 增量），
 * 前端 applyDeltaChannel(result) 用 concatText 累积，result 已与 contentBlocks 全等（joined === result），
 * joined.includes(result) 命中即拦截补段，避免 drawerContentBlocks 与 subSteps 渲染双份正文。 */
const drawerContentBlocks = computed(() => {
  const s = step.value
  if (!s || (node.value?.type !== 'agent' && !isWorkerNode.value)) return s?.contentBlocks
  const blocks = s.contentBlocks ?? []
  const result = s.result?.trim()
  if (!result) return blocks
  const joined = blocks.map(b => b.text).join('').trim()
  // contentBlocks 已承载正文（worker 双通道下 result 是冗余），不再追加补段
  if (blocks.length > 0 || (joined && joined.includes(result))) return blocks
  const lastSubId = subSteps.value[subSteps.value.length - 1]?.id
  return [...blocks, { segmentId: 'tail:final', afterStepId: lastSubId ?? s.id, text: result }]
})
/** loop：按 i{n}- 前缀分轮；agent：整段扁平 */
const loopRoundGroups = computed(() => {
  if (node.value?.type !== 'loop') return []
  return groupLoopBodySubStepsByRound(subSteps.value)
})
const showLoopRoundTimeline = computed(() => loopRoundGroups.value.length > 0)
/** workflow tool 写操作：与普通 tool 抽屉一致，仅在执行摘要前插入用户确认块 */
const hitlStep = computed(() => findHitlStep(step.value, pendingHitl.value))
const showHitlSection = computed(() => node.value?.type === 'tool' && !!hitlStep.value)
/** 优先 live step.metadata.nodeAttempts，与 DAG 构建逻辑一致，保证调用次数实时更新 */
const displayAttempts = computed((): PlanNodeAttempt[] | undefined => {
  const fromStep = step.value?.metadata?.nodeAttempts
  const fromNode = node.value?.attempts
  if (fromStep?.length && fromNode?.length) {
    return fromStep.length >= fromNode.length ? fromStep : fromNode
  }
  return fromStep?.length ? fromStep : fromNode
})
const displayAttemptCount = computed(() =>
  node.value?.attemptCount ?? displayAttempts.value?.length ?? 0,
)
/** agent / worker 用 subSteps；loop 走分轮组，不在此扁平列表 */
const drawerStackSteps = computed(() => {
  if ((node.value?.type === 'agent' || isWorkerNode.value) && showSubTimeline.value) return subSteps.value
  return []
})
const showDrawerOperationStack = computed(() =>
  drawerStackSteps.value.length > 0 || showLoopRoundTimeline.value,
)
/** agent / worker 无 subSteps 时正文无步骤可锚定，OperationStack 无法穿插，兜底直接渲染 */
const agentBareFinalText = computed(() => {
  if ((node.value?.type !== 'agent' && !isWorkerNode.value) || subSteps.value.length > 0) return ''
  return (drawerContentBlocks.value ?? []).map(b => b.text).join('').trim()
})
const showAgentBareFinal = computed(() => !!agentBareFinalText.value)
const showRecoverySection = computed(() => !!step.value && isRecoveryAwaiting(step.value))
const subTimelineLive = computed(() => {
  const s = displayStatus.value
  return s === 'running' || s === 'awaiting_confirm'
})

/** 抽屉子时间线与主 Chat 同构：启用 OperationStack 总览折叠（默认收起） */
const drawerTimelineMessageStatus = computed((): TimelineMessageStatus => {
  const s = displayStatus.value
  if (s === 'running' || s === 'awaiting_confirm' || s === 'pending') return 'streaming'
  if (s === 'paused' || s === 'terminated') return 'interrupted'
  if (s === 'error') return 'failed'
  return 'completed'
})

const drawerTimelineStartedAt = computed(() => {
  const s = step.value
  if (!s) return undefined
  return s.clientStartedAt ?? s.startedAt ?? s.ts
})

const drawerTimelineEndedAt = computed(() => {
  const s = step.value
  if (!s) return undefined
  if (subTimelineLive.value) return undefined
  return s.endedAt
})

const bodyRef = ref<HTMLElement | null>(null)
/** 贴底跟随：贴底时随流式输出下滑；用户上滑离开底部后闩锁停止跟随，回到真正贴底才恢复 */
const followBottom = ref(true)
/** 打开/切换节点时「内容不足一屏视为贴底」的判定阈值 */
const FOLLOW_BOTTOM_THRESHOLD = 60
const spawnPromptExpanded = ref(false)

let drawerObserver: MutationObserver | null = null

// —— 流式贴底状态机：与 useChatScroll 同构（项目内已测模式）——
/** 程序化贴底产生的 scroll 事件勿改写 followBottom（双 rAF 窗口后复位） */
let pinSyncSuppressed = false
let pinSyncSettleRaf = 0
/** 流式跟随合并到每帧最多一次，避免正文/步骤洪水抢滚轮 */
let followRaf = 0
let lastScrollTop = 0
/** 用户手动离开底部后置位：流式跟随立即停止，直到重新贴底。解决拖拽滚动条/触控上滑被拉回抢回的抖动 */
let userTakenOver = false
/** 持续贴底循环内上次观测的 scrollHeight：连续稳定 N 帧即视为跟随完成 */
let lastFollowHeight = 0
let stableFollowFrames = 0
/** 表格/大块 markdown 布局可滞后 DOM mutation 多帧，稳定帧数与正文 settleScrollToBottom 对齐 */
const STABLE_FOLLOW_FRAMES = 10
/** 用户在滚动条上按下：拖动期间的 scroll 方向才视为用户操作 */
let userScrolling = false
/** 触摸起点：手指下移（内容上滑）立即暂停跟随 */
let touchStartY: number | null = null

function isNearBottom(el: HTMLElement): boolean {
  return distanceFromChatBottom(el) <= FOLLOW_BOTTOM_THRESHOLD
}

function toggleSpawnPrompt() {
  // 拖选复制时不切换展开
  const sel = window.getSelection()?.toString()
  if (sel) return
  spawnPromptExpanded.value = !spawnPromptExpanded.value
}

function cancelFollowRaf(): void {
  if (!followRaf) return
  cancelAnimationFrame(followRaf)
  followRaf = 0
}

/** 用户上滑/接管滚动：立即硬性打断跟随（不等跨帧 ref/watch/rAF） */
function unpinFromUser(): void {
  followBottom.value = false
  userTakenOver = true
  cancelFollowRaf()
  pinSyncSuppressed = false
  if (pinSyncSettleRaf) {
    cancelAnimationFrame(pinSyncSettleRaf)
    pinSyncSettleRaf = 0
  }
}

function suppressPinSyncBriefly(): void {
  pinSyncSuppressed = true
  if (pinSyncSettleRaf) cancelAnimationFrame(pinSyncSettleRaf)
  pinSyncSettleRaf = requestAnimationFrame(() => {
    pinSyncSettleRaf = requestAnimationFrame(() => {
      pinSyncSettleRaf = 0
      pinSyncSuppressed = false
    })
  })
}

/** scroll 事件：仅同步位置；接管判定只在「滚动条拖动」（userScrolling）期间生效。
 * 表格流式渲染高度波动时浏览器会 clamp scrollTop（最大可滚位置变小），
 * 程序性 scroll 方向不可信，用户上滑由 wheel/touch 输入事件同步接管。 */
function syncScrollPinned(): void {
  const el = bodyRef.value
  if (!el) return
  const top = el.scrollTop
  const dist = distanceFromChatBottom(el)
  const scrolledUp = top < lastScrollTop - 0.5
  lastScrollTop = top
  // 非拖动滚动条产生的 scroll（程序化贴底/表格 clamp/浏览器补发）：不参与接管判定，
  // 仅允许「滚回底部」恢复跟随
  if (!userScrolling) {
    if (dist <= 1 && !scrolledUp) {
      followBottom.value = true
      userTakenOver = false
    }
    return
  }
  // 拖动滚动条上滑离开底部：立即接管并硬性打断跟随
  if (scrolledUp && dist > 1) {
    unpinFromUser()
    return
  }
  if (dist <= 1) {
    if (!scrolledUp) {
      followBottom.value = true
      userTakenOver = false
    }
    return
  }
  if (pinSyncSuppressed && followBottom.value) return
  const pinned = resolveChatScrollPinned({
    distanceFromBottom: dist,
    suppressed: pinSyncSuppressed,
    currentlyPinned: followBottom.value,
    scrolledUp,
  })
  followBottom.value = pinned
  if (pinned) {
    userTakenOver = false
  } else {
    pinSyncSuppressed = false
    cancelFollowRaf()
  }
}

function onDrawerBodyScroll() {
  syncScrollPinned()
}

/** 捕获阶段：滚轮/触控板上滑立即取消贴底（滚动发生前同步生效，免疫程序性 clamp 污染） */
function onDrawerWheel(e: WheelEvent): void {
  if (!state.open) return
  if (e.deltaY < 0) unpinFromUser()
}

function onDrawerTouchStart(e: TouchEvent) {
  touchStartY = e.touches[0]?.clientY ?? null
}

function onDrawerTouchMove(e: TouchEvent) {
  if (!state.open) return
  const y = e.touches[0]?.clientY
  if (touchStartY == null || y == null) return
  if (y > touchStartY) unpinFromUser()
  touchStartY = y
}

function onDrawerTouchEnd() {
  touchStartY = null
}

/** 滚动条拖动接管：拖动期间 scroll 方向才视为用户操作（window pointerup 兜底复位） */
function onDrawerPointerDown() {
  userScrolling = true
}

function onDrawerPointerEnd() {
  userScrolling = false
}

/** 程序化贴底：同步 lastScrollTop，避免下一帧被误判为用户上滑 */
function applyScrollBottom(): void {
  const el = bodyRef.value
  if (!el) return
  suppressPinSyncBriefly()
  const nextTop = Math.max(0, el.scrollHeight - el.clientHeight)
  el.scrollTop = nextTop
  lastScrollTop = nextTop
}

/** 抽屉内容变化（流式输出/步骤追加）：跟随态启动持续贴底循环，直到高度稳定或用户接管。
 * 表格/大块 markdown 布局可滞后 DOM mutation 多帧，须持续贴底至高度连续稳定（与正文 settle 一致）；
 * 用户上滑由 wheel/touch/滚动条拖动输入事件同步接管，循环内无需再判 scrollTop 方向。 */
function onDrawerContentMutated(): void {
  const el = bodyRef.value
  if (!el || !state.open || !followBottom.value || userTakenOver) return
  if (followRaf) return
  const tick = () => {
    followRaf = 0
    const target = bodyRef.value
    if (!target || !state.open || !followBottom.value || userTakenOver) return
    applyScrollBottom()
    if (target.scrollHeight === lastFollowHeight) {
      stableFollowFrames += 1
      if (stableFollowFrames >= STABLE_FOLLOW_FRAMES) {
        stableFollowFrames = 0
        lastFollowHeight = 0
        return
      }
    } else {
      stableFollowFrames = 0
      lastFollowHeight = target.scrollHeight
    }
    followRaf = requestAnimationFrame(tick)
  }
  followRaf = requestAnimationFrame(tick)
}

/** contentBlocks/reasoning 为原地增量更新（对象引用不变），watch 覆盖不全，统一用 MutationObserver 监听 DOM 变化 */
function ensureDrawerObserver(): void {
  const el = bodyRef.value
  if (!el || drawerObserver) return
  drawerObserver = new MutationObserver(onDrawerContentMutated)
  drawerObserver.observe(el, { childList: true, subtree: true, characterData: true })
}

function teardownDrawerObserver(): void {
  drawerObserver?.disconnect()
  drawerObserver = null
}

watch(
  () => [state.open, state.node?.id] as const,
  ([open, nodeId], [, prevId]) => {
    if (!open) {
      teardownDrawerObserver()
      return
    }
    const isNewNode = nodeId !== prevId
    if (isNewNode) spawnPromptExpanded.value = false
    followBottom.value = true
    userTakenOver = false
    cancelFollowRaf()
    pinSyncSuppressed = false
    lastScrollTop = 0
    lastFollowHeight = 0
    stableFollowFrames = 0
    void nextTick(() => {
      const el = bodyRef.value
      if (!el) return
      if (isNewNode) el.scrollTo(0, 0)
      lastScrollTop = el.scrollTop
      // 历史内容不足一屏视为贴底（跟随流式）；超一屏则停在顶部供回读
      followBottom.value = isNearBottom(el)
    })
    void nextTick(ensureDrawerObserver)
  },
)
</script>

<template>
  <aside
    v-if="state.open && node"
    class="plan-drawer"
    :class="{ 'is-over-expand': planDagExpanded }"
    role="complementary"
    aria-label="节点详情"
    :style="{ width: `${drawerWidth}px` }"
  >
    <div
      v-if="canResizeDrawer"
      class="drawer-resize-handle"
      role="separator"
      aria-orientation="vertical"
      aria-label="调整抽屉宽度"
      @pointerdown="onResizePointerDown"
    />
    <header class="drawer-header">
      <div class="drawer-head-top">
        <div class="drawer-title-row">
          <span class="drawer-type-icon" aria-hidden="true">
            <PlanNodeIcon :type="nodeType" :size="16" />
          </span>
          <h3 class="drawer-title">{{ title }}</h3>
        </div>
        <button
          type="button"
          class="drawer-close"
          :title="depth > 1 ? '返回上级' : '收起'"
          :aria-label="depth > 1 ? '返回上级' : '收起'"
          @click="depth > 1 ? goBack() : close()"
        >
          <svg
            v-if="depth > 1"
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M19 12H5" />
            <path d="m12 19-7-7 7-7" />
          </svg>
          <DrawerCollapseIcon v-else :size="16" />
        </button>
      </div>

      <p v-if="userQuery" class="drawer-meta-line" :title="`用户问题 ${userQuery}`">
        <span class="meta-line-label">用户问题</span>
        <span class="meta-line-detail">{{ userQuery }}</span>
      </p>

      <div class="drawer-status-row">
        <div class="drawer-status-left">
          <span class="meta-type">{{ typeLabel }}</span>
          <span class="meta-status" :class="`is-${displayStatus}`">
            <span class="status-dot" aria-hidden="true" />
            {{ statusLabel }}
          </span>
        </div>
        <span v-if="durationText" class="meta-dur">{{ durationText }}</span>
      </div>

    </header>
    <div
      ref="bodyRef"
      class="drawer-body"
      @scroll="onDrawerBodyScroll"
      @wheel.capture.passive="onDrawerWheel"
      @touchstart.passive="onDrawerTouchStart"
      @touchmove.passive="onDrawerTouchMove"
      @touchend.passive="onDrawerTouchEnd"
      @pointerdown="onDrawerPointerDown"
    >
      <section v-if="showExclusiveBranches" class="drawer-section">
        <h4>分支条件</h4>
        <ul class="exclusive-branch-list">
          <li
            v-for="b in exclusiveBranches"
            :key="`${b.toId}-${b.isDefault ? 'd' : 'c'}`"
            class="exclusive-branch-item"
          >
            <span class="exclusive-branch-target">{{ b.toLabel }}</span>
            <span class="exclusive-branch-cond" :class="{ 'is-default': b.isDefault }">
              {{ b.conditionText }}
            </span>
          </li>
        </ul>
      </section>
      <section v-if="showLoopContinue" class="drawer-section">
        <h4>继续条件</h4>
        <ul class="exclusive-branch-list">
          <li
            v-for="row in loopContinueRows"
            :key="row.key"
            class="exclusive-branch-item"
          >
            <span class="exclusive-branch-target">{{ row.label }}</span>
            <span class="exclusive-branch-cond">{{ row.value }}</span>
          </li>
        </ul>
      </section>
      <div v-if="displayAttempts?.length" class="drawer-section">
        <h4>执行记录（{{ displayAttemptCount }} 次）</h4>
        <ul class="attempt-list">
          <li v-for="a in displayAttempts" :key="a.attemptNo" class="attempt-item">
            <span class="attempt-no">#{{ a.attemptNo }}</span>
            <span class="attempt-status" :class="`is-${a.status}`">{{ a.status }}</span>
            <span v-if="a.summary" class="attempt-summary">{{ a.summary }}</span>
          </li>
        </ul>
      </div>
      <section v-if="showRecoverySection && step" class="drawer-section drawer-recovery">
        <PlanNodeRecoveryActions :step="step" @decided="applyRecoveryDecision" />
      </section>
      <section v-if="showSpawnPrompt" class="drawer-section">
        <h4>{{ isWorkerNode ? '任务契约' : '传入提示词' }}</h4>
        <div
          class="spawn-prompt"
          :class="{ 'is-expanded': spawnPromptExpanded }"
          role="button"
          tabindex="0"
          :title="spawnPromptExpanded ? '收起' : '展开全文'"
          :aria-expanded="spawnPromptExpanded"
          @click="toggleSpawnPrompt"
          @keydown.enter.prevent="toggleSpawnPrompt"
          @keydown.space.prevent="toggleSpawnPrompt"
        >{{ spawnPrompt }}</div>
      </section>
      <section v-if="showLoopRoundTimeline" class="drawer-section drawer-sub-timeline">
        <h4>执行过程</h4>
        <div
          v-for="group in loopRoundGroups"
          :key="`loop-round-${group.round}`"
          class="loop-round-block"
        >
          <h5 v-if="group.round > 0" class="loop-round-title">第 {{ group.round }} 轮</h5>
          <OperationStack
            :steps="group.steps"
            :stream-live="subTimelineLive"
            :live="subTimelineLive"
            :message-status="drawerTimelineMessageStatus"
            :embed-hitl="false"
            :pending-hitl-confirmations="pendingHitlList"
            @hitl-decided="applyHitlDecision"
          />
        </div>
      </section>
      <section v-else-if="showDrawerOperationStack" class="drawer-section drawer-sub-timeline">
        <!-- 有总览折叠行时不再重复「执行过程」标题，与主时间线一致 -->
        <OperationStack
          :steps="drawerStackSteps"
          :content-blocks="drawerContentBlocks"
          :stream-live="subTimelineLive"
          :live="subTimelineLive"
          :message-status="drawerTimelineMessageStatus"
          :timeline-started-at="drawerTimelineStartedAt"
          :timeline-ended-at="drawerTimelineEndedAt"
          :embed-hitl="false"
          :pending-hitl-confirmations="pendingHitlList"
          @hitl-decided="applyHitlDecision"
        />
      </section>
      <section v-if="showAgentBareFinal" class="drawer-section">
        <StaticMarkdown :source="agentBareFinalText" compact :streaming="isNodeStreaming" />
      </section>
      <section v-if="showHitlSection && hitlStep" class="drawer-section drawer-hitl">
        <h4>用户确认</h4>
        <HitlStepActions
          :step="hitlStep"
          :pending-confirmation="pendingHitl"
          @decided="applyHitlDecision"
        />
      </section>
      <section v-if="showSummary" class="drawer-section">
        <h4>执行摘要</h4>
        <StaticMarkdown :source="summary" compact />
      </section>
      <section v-if="showRewriteDetail" class="drawer-section">
        <h4>{{ rewriteSectionTitle }}</h4>
        <p class="drawer-meta-line" :title="rewriteDetail!.from">
          <span class="meta-line-label">原问题</span>
          <span class="meta-line-detail">{{ rewriteDetail!.from }}</span>
        </p>
        <p class="drawer-meta-line" :title="rewriteDetail!.to">
          <span class="meta-line-label">{{ rewriteDetail!.targetLabel }}</span>
          <span class="meta-line-detail">{{ rewriteDetail!.to }}</span>
        </p>
        <p v-if="rewriteDetail!.latencyText" class="drawer-meta-line" :title="rewriteDetail!.latencyText">
          <span class="meta-line-label">耗时</span>
          <span class="meta-line-detail">{{ rewriteDetail!.latencyText }}</span>
        </p>
      </section>
      <section v-if="showStartPlan" class="drawer-section">
        <h4>执行计划</h4>
        <p v-if="startPlan.replanCount" class="plan-replan-hint">
          经 {{ startPlan.replanCount }} 次修正后确定
        </p>
        <template v-if="startPlan.chainSteps.length">
          <p
            v-for="(name, index) in startPlan.chainSteps"
            :key="`${index}-${name}`"
            class="drawer-meta-line plan-step-line"
            :title="name"
          >
            <span class="meta-line-label">{{ index + 1 }}</span>
            <span class="meta-line-detail">{{ name }}</span>
          </p>
        </template>
        <p v-if="startPlan.planId" class="drawer-meta-line plan-id-line" :title="startPlan.planId">
          <span class="meta-line-label">Plan ID</span>
          <span class="meta-line-detail plan-id-value">{{ startPlan.planId }}</span>
        </p>
      </section>
      <section v-if="showAnalysisSection" class="drawer-section">
        <h4>综合分析</h4>
        <StaticMarkdown :source="analysisDisplay" compact :streaming="isNodeStreaming" />
      </section>
      <section v-if="showBodySection" class="drawer-section">
        <h4>{{ bodySectionTitle }}</h4>
        <StaticMarkdown :source="bodyDisplay" compact :streaming="isNodeStreaming" />
      </section>
      <section v-if="showReasoningSection" class="drawer-section">
        <h4>推理过程</h4>
        <StaticMarkdown :source="reasoning" compact :streaming="isNodeStreaming" />
      </section>
      <section v-if="output" class="drawer-section">
        <h4>日志</h4>
        <StaticMarkdown :source="output" compact :streaming="isNodeStreaming" />
      </section>
      <p v-if="!showHitlSection && !showSummary && !showRewriteDetail && !showStartPlan && !showAnalysisSection && !showBodySection && !showReasoningSection && !showDrawerOperationStack && !showAgentBareFinal && !showRecoverySection && !showSpawnPrompt && !output && !displayAttempts?.length" class="drawer-empty">
        {{ displayStatus === 'running' ? '节点执行中…' : '暂无详情' }}
      </p>
    </div>
  </aside>
</template>

<style scoped>
.plan-drawer {
  position: relative;
  flex-shrink: 0;
  height: 100%;
  min-height: 0;
  z-index: 120;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--sun-border);
  background: var(--sun-bg);
  box-shadow: -8px 0 24px color-mix(in srgb, black 8%, transparent);
}

.plan-drawer.is-over-expand {
  z-index: 210;
}

.drawer-resize-handle {
  position: absolute;
  left: -5px;
  top: 0;
  bottom: 0;
  width: 10px;
  z-index: 5;
  cursor: col-resize;
  touch-action: none;
}

.drawer-resize-handle::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 0;
  bottom: 0;
  width: 2px;
  border-radius: 1px;
  background: transparent;
  transition: background 0.15s;
}

.drawer-resize-handle:hover::after {
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 55%, transparent);
}

:global(body.plan-drawer-resizing) {
  cursor: col-resize !important;
  user-select: none !important;
}

:global(body.plan-drawer-resizing .drawer-resize-handle::after) {
  background: var(--sun-blue, #58a6ff);
}

.drawer-header {
  flex-shrink: 0;
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-bg);
}

.drawer-head-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.drawer-title-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.drawer-type-icon {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-secondary);
}

.drawer-title {
  margin: 0;
  padding-top: 2px;
  font-size: var(--sun-font-lg);
  font-weight: 600;
  color: var(--sun-text);
  line-height: 1.35;
  word-break: break-word;
}

.drawer-meta-line {
  margin: 0;
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
  font-size: var(--sun-font-md);
  line-height: var(--sun-line-relaxed);
  overflow: hidden;
}

.meta-line-label {
  flex-shrink: 0;
  font-weight: 450;
  color: var(--sun-text-secondary);
}

.meta-line-detail {
  min-width: 0;
  font-weight: 400;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.drawer-close {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.drawer-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.drawer-status-left {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.meta-type {
  font-size: 11px;
  font-weight: 500;
  color: var(--sun-text-muted);
  letter-spacing: 0.02em;
}

.meta-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 500;
  color: var(--sun-text-muted);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.85;
}

.meta-status.is-pending { color: var(--sun-text-muted); }
.meta-status.is-running { color: var(--sun-blue, #58a6ff); }
.meta-status.is-paused { color: #ca8a04; }
.meta-status.is-terminated { color: var(--sun-text-muted); }
.meta-status.is-done { color: var(--sun-green, #3fb950); }
.meta-status.is-awaiting_confirm { color: var(--sun-purple, #9333ea); }
.meta-status.is-skipped { color: #64748b; }
.meta-status.is-error { color: var(--sun-red, #f85149); }

.meta-dur {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--sun-text-secondary);
  font-variant-numeric: tabular-nums;
}

.drawer-close:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 16px 16px 28px;
}

.drawer-section {
  margin-bottom: 16px;
}

.attempt-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.exclusive-branch-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.exclusive-branch-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: transparent;
}

.exclusive-branch-target {
  font-size: var(--sun-font-base);
  font-weight: 600;
  color: var(--sun-text);
}

.exclusive-branch-cond {
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-secondary);
  word-break: break-word;
}

.exclusive-branch-cond.is-default {
  color: var(--sun-text);
}

.attempt-item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  color: var(--sun-text-secondary);
}

.attempt-no {
  font-weight: 600;
  color: var(--sun-text-muted);
}

.attempt-status.is-failed {
  color: #f87171;
}

.attempt-status.is-completed {
  color: #4ade80;
}

.drawer-section h4 {
  margin: 0 0 8px;
  font-size: var(--sun-font-sm);
  font-weight: 600;
  color: var(--sun-text-secondary);
}

/* 对齐 Chat 用户气泡；收起可滚，展开全文无区内滚动条 */
.spawn-prompt {
  display: block;
  width: 100%;
  margin: 0;
  max-height: 160px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 10px 16px;
  border: none;
  border-radius: 20px;
  background: var(--sun-surface);
  color: var(--sun-text);
  font-family: inherit;
  font-size: var(--sun-font-md);
  line-height: var(--sun-line-relaxed);
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
  cursor: text;
  user-select: text;
  -webkit-user-select: text;
}

.spawn-prompt.is-expanded {
  max-height: none;
  overflow: visible;
}

.drawer-section .drawer-meta-line + .drawer-meta-line {
  margin-top: 6px;
}

.drawer-section .drawer-meta-line {
  align-items: flex-start;
}

.drawer-section .meta-line-detail {
  white-space: pre-wrap;
  word-break: break-word;
  overflow: visible;
  text-overflow: unset;
}

.plan-id-line {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--sun-border);
  align-items: center;
  font-size: var(--sun-font-sm);
  line-height: 1.45;
}

.plan-id-line .meta-line-label,
.plan-id-line .meta-line-detail {
  font-size: inherit;
  line-height: inherit;
}

.plan-id-value {
  font-family: ui-monospace, 'JetBrains Mono', monospace;
}

.plan-replan-hint {
  margin: 0 0 8px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

.plan-step-line {
  align-items: center;
}

.plan-step-line .meta-line-label {
  min-width: 1.25rem;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.plan-step-line + .plan-step-line {
  margin-top: 6px;
}

.drawer-section :deep(.static-md-compact) {
  color: var(--sun-text-muted);
}

.drawer-sub-timeline :deep(.operation-lines) {
  margin-left: 0;
  padding-bottom: 0;
}

.loop-round-block + .loop-round-block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--sun-border);
}

.loop-round-title {
  margin: 0 0 8px;
  font-size: var(--sun-font-sm);
  font-weight: 600;
  color: var(--sun-text);
}

.drawer-recovery :deep(.collapsible-confirm) {
  margin-left: 0;
  margin-top: 6px;
  margin-bottom: 2px;
}

.drawer-empty {
  margin: 24px 0 0;
  font-size: var(--sun-font-base);
  color: var(--sun-text-muted);
  text-align: center;
}
</style>
