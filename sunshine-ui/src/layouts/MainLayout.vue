<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutContent, NMenu, NDropdown, NIcon, NInput, useDialog, NButton, useMessage, NModal, type MenuOption, type DropdownOption } from 'naive-ui'
import { BookOutline, StatsChartOutline, SettingsOutline, LogOutOutline, EllipsisHorizontal, SparklesOutline, AppsOutline, HardwareChipOutline, ConstructOutline, CubeOutline, CodeSlashOutline, GitNetworkOutline, ChevronDownOutline, CreateOutline, TrashOutline, DocumentTextOutline, BriefcaseOutline, AlbumsOutline, AddOutline, ChatbubblesOutline, FolderOutline, FolderOpenOutline, SearchOutline } from '@vicons/ionicons5'
import { h, type Component, computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useTheme } from '../composables/useTheme'
import { useSidebar, SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH } from '../composables/useSidebar'
import { useChatStore } from '../stores/chatStore'
import { useAuthStore } from '../stores/authStore'
import { useConversationAttention } from '../composables/useConversationAttention'
import { friendlyErrorMessage } from '../api/apiError'
import { listWorkspaces, createWorkspace, destroyWorkspace } from '../api/workspaces'
import type { WorkspaceVO } from '../api/workspaces'
import BrandMark from '../components/BrandMark.vue'
import SidebarToggle from '../components/SidebarToggle.vue'
import UserSettingsModal from '../components/UserSettingsModal.vue'
import ProjectGuideModal from '../components/sandbox/ProjectGuideModal.vue'
import ConversationSidebarList from '../components/ConversationSidebarList.vue'
import ConversationSearchModal from '../components/ConversationSearchModal.vue'
import ConversationStatusIcon from '../components/ConversationStatusIcon.vue'
import ConversationHoverCard from '../components/ConversationHoverCard.vue'
import { useConversationSidebarIndicator, type SidebarConvIndicator } from '../composables/useConversationSidebarIndicator'
import { formatSidebarItemTime, conversationDayBucketKey, conversationDayLabel, daysBeforeToday, formatConversationTime } from '../utils/conversationTime'
import type { Conversation } from '../stores/chatStore'

type SectionKey = 'platform' | 'chat' | 'workspace'
const sectionKeys: readonly SectionKey[] = ['platform', 'chat', 'workspace']

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const chatStore = useChatStore()
const message = useMessage()
const {
  attentionByConv,
  getAttention,
  clearAttention,
  requestScrollToBottom,
} = useConversationAttention()

// 各分组的展开/折叠状态：对话默认展开
const expanded = reactive<Record<SectionKey, boolean>>({
  platform: false,
  chat: true,
  workspace: false,
})

function toggleSection(key: SectionKey) {
  const wasExpanded = expanded[key]
  // accordion: close all first
  for (const k of sectionKeys) {
    expanded[k] = false
  }
  // then toggle the clicked one (open if it was closed; close if it was open)
  if (!wasExpanded) {
    expanded[key] = true
  }
}

const platformMenuOptions: MenuOption[] = [
  { label: '知识库', key: 'knowledge', icon: renderIcon(BookOutline) },
  { label: 'Skills', key: 'skills', icon: renderIcon(SparklesOutline) },
  { label: '工作流', key: 'workflows', icon: renderIcon(GitNetworkOutline) },
  { label: '工具', key: 'tools', icon: renderIcon(ConstructOutline) },
  { label: '智能体', key: 'agents', icon: renderIcon(HardwareChipOutline) },
  { label: '模型', key: 'models', icon: renderIcon(CubeOutline) },
  { label: '上下文', key: 'context', icon: renderIcon(AlbumsOutline) },
  { label: '提示词', key: 'prompts', icon: renderIcon(DocumentTextOutline) },
  { label: '业务数据', key: 'biz-data', icon: renderIcon(BriefcaseOutline) },
  { label: '系统状态', key: 'status', icon: renderIcon(StatsChartOutline) },
]

const FILL_CONTENT_ROUTES = new Set(['chat', 'knowledge', 'skills', 'workflows', 'tools', 'agents', 'models', 'context', 'prompts', 'biz-data', 'workflow-diff', 'skill-diff'])
const contentFill = computed(() => FILL_CONTENT_ROUTES.has(String(route.name ?? '')))
const hideSidebarFab = computed(() => contentFill.value || route.name === 'skill-diff')

function renderIcon(icon: Component) {
  return () => h(icon)
}

function renderDropdownIcon(icon: Component) {
  return () => h(NIcon, { size: 16 }, { default: () => h(icon) })
}

function handleMenuClick(key: string) {
  void router.push({ name: key })
}

const activeKey = computed(() => {
  if (route.name === 'skill-diff') return 'skills'
  if (route.name === 'workflow-diff') return 'workflows'
  if (route.name === 'chat') return ''
  return (route.name as string) || ''
})

const { theme, toggle: toggleTheme } = useTheme()
const { sidebarVisible, sidebarWidth } = useSidebar()

const sidebarResizing = ref(false)

function onSidebarResizePointerDown(ev: PointerEvent) {
  if (!ev.isPrimary) return
  sidebarResizing.value = true
  document.body.classList.add('sun-sidebar-resizing')
  const startX = ev.clientX
  const startW = sidebarWidth.value
  function onMove(e: PointerEvent) {
    const w = Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, startW + (e.clientX - startX)))
    sidebarWidth.value = w
  }
  function onUp() {
    sidebarResizing.value = false
    document.body.classList.remove('sun-sidebar-resizing')
    document.removeEventListener('pointermove', onMove)
    document.removeEventListener('pointerup', onUp)
  }
  document.addEventListener('pointermove', onMove)
  document.addEventListener('pointerup', onUp)
}
const isDark = computed(() => theme.value === 'dark')
const dialog = useDialog()

const displayNickname = computed(() => authStore.user?.nickname || '用户')
const userInitial = computed(() => displayNickname.value.charAt(0).toUpperCase())
const showSettings = ref(false)

const userMenuOptions: DropdownOption[] = [
  { label: '设置', key: 'settings', icon: renderDropdownIcon(SettingsOutline) },
  { type: 'divider', key: 'd1' },
  { label: '退出登录', key: 'logout', icon: renderDropdownIcon(LogOutOutline) },
]

function handleUserMenu(key: string) {
  if (key === 'settings') {
    showSettings.value = true
    return
  }
  if (key === 'logout') {
    handleLogout()
  }
}

function handleLogout() {
  void (async () => {
    await authStore.logout()
    await router.replace('/login')
  })()
}

function handleNewChat() {
  chatStore.pendingWorkspace = null
  chatStore.newTaskMode = false
  void (async () => {
    try {
      await chatStore.create()
      if (route.name !== 'chat') router.push('/chat')
    } catch (e) {
      console.error('[MainLayout] 创建会话失败', e)
    }
  })()
}

async function handleNewTask() {
  chatStore.newTaskMode = true
  chatStore.currentId = null
  expanded.workspace = true
  expanded.chat = false
  expanded.platform = false
  // 自动选择第一个工作区；分支由 GitBranchSelector 继承最后会话分支
  if (workspaces.value.length === 0) await fetchWorkspaces()
  const first = workspaces.value[0]
  if (first) {
    chatStore.pendingWorkspace = { wsId: first.id, wsName: first.name }
    wsGroupOpen[first.id] = true
  } else {
    chatStore.pendingWorkspace = null
  }
  if (route.name !== 'chat') router.push('/chat')
}

function handleSwitchConversation(id: string) {
  // 切换到已有会话：无论任务还是对话，都退出「新任务选项目」流程。
  // 若只对非 task 清 pendingWorkspace，点完「新任务」再点已有任务对话会残留，
  // 导致该对话错误显示项目/分支选择器。
  chatStore.pendingWorkspace = null
  chatStore.newTaskMode = false
  void (async () => {
    if (getAttention(id)) {
      requestScrollToBottom(id)
      clearAttention(id)
    }
    await chatStore.switchTo(id)
    if (route.name !== 'chat') router.push('/chat')
  })()
}

/** 搜索弹窗选中：搜索结果可能不在本地 store，走 ensureConversation 兜底加载 */
const showSearch = ref(false)
function handleSearchSelect(id: string) {
  chatStore.pendingWorkspace = null
  chatStore.newTaskMode = false
  void (async () => {
    if (!chatStore.conversations.some(c => c.id === id)) {
      await chatStore.ensureConversation(id)
    } else {
      await chatStore.switchTo(id)
    }
    if (route.name !== 'chat') router.push('/chat')
  })()
}

function conversationMenuOptions(id: string): DropdownOption[] {
  return [
    { label: '重命名', key: `rename:${id}`, icon: renderDropdownIcon(CreateOutline) },
    { type: 'divider', key: `div:${id}` },
    {
      label: '删除',
      key: `delete:${id}`,
      props: { class: 'history-dropdown-delete' },
      icon: renderDropdownIcon(TrashOutline),
    },
  ]
}

function handleConversationMenu(key: string) {
  const sep = key.indexOf(':')
  if (sep < 0) return
  const action = key.slice(0, sep)
  const id = key.slice(sep + 1)
  if (action === 'rename') handleRenameConversation(id)
  else if (action === 'delete') handleDeleteConversation(id)
}

function handleRenameConversation(id: string) {
  const conv = chatStore.conversations.find(c => c.id === id)
  const inputValue = ref(conv?.title ?? '')
  dialog.create({
    class: 'sunshine-dialog',
    showIcon: false,
    title: '重命名对话',
    content: () => h(NInput, {
      value: inputValue.value,
      maxlength: 64,
      placeholder: '输入对话标题',
      autofocus: true,
      onUpdateValue: (v: string) => { inputValue.value = v },
    }),
    positiveText: '保存',
    negativeText: '取消',
    onPositiveClick: () => submitRename(id, inputValue.value.trim()),
  })
}

async function submitRename(id: string, title: string): Promise<boolean> {
  if (!title) return false
  try {
    await chatStore.rename(id, title)
    return true
  } catch (e) {
    console.error('[MainLayout] 重命名失败', e)
    return false
  }
}

function handleDeleteConversation(id: string) {
  const conv = chatStore.conversations.find(c => c.id === id)
  const title = conv?.title || '该对话'
  dialog.create({
    class: 'sunshine-dialog',
    showIcon: false,
    title: '永久删除对话',
    content: `确定删除「${title}」吗？\n此操作不可撤销，对话内容将永久删除且无法恢复。`,
    positiveText: '永久删除',
    negativeText: '取消',
    positiveButtonProps: { type: 'error', size: 'medium' },
    negativeButtonProps: { ghost: false, quaternary: true, size: 'medium' },
    onPositiveClick: () => {
      void chatStore.remove(id)
    },
  })
}

// ===== 工作区 =====
const workspaces = ref<WorkspaceVO[]>([])
const loadingWorkspaces = ref(false)
const showCreateWorkspace = ref(false)
const newWsName = ref('')
const newWsRepoUrl = ref('')
const creatingWs = ref(false)
/** 工作区分组折叠展开 */
const wsGroupOpen = reactive<Record<string, boolean>>({})

/** 任务侧栏状态图标 */
const { resolveIndicator } = useConversationSidebarIndicator()

/** 当前时间计数器（用于分组标签） */
const nowTick = ref(Date.now())
let nowTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => { nowTimer = setInterval(() => { nowTick.value = Date.now() }, 60_000) })
onUnmounted(() => { if (nowTimer) clearInterval(nowTimer) })

/** 按工作区聚合任务会话，并按天分组 */
const chatSidebarConversations = computed(() =>
  chatStore.conversations.filter(c => c.kind !== 'task')
)
const wsTaskGroups = computed(() => {
  const tasks = chatStore.conversations.filter(c => c.kind === 'task' && c.workspaceId)
  const byWs = new Map<string, { key: string; label: string; sortOrder: number; items: Conversation[] }[]>()
  for (const conv of tasks) {
    const wsId = conv.workspaceId!
    const bucket = conversationDayBucketKey(conv.updatedAt, nowTick.value)
    const label = conversationDayLabel(conv.updatedAt, nowTick.value)
    const sortOrder = bucket === 'older' ? 9999 : daysBeforeToday(conv.updatedAt, nowTick.value)
    const groups = byWs.get(wsId) ?? []
    let group = groups.find(g => g.key === bucket)
    if (!group) {
      group = { key: bucket, label, sortOrder, items: [] }
      groups.push(group)
    }
    group.items.push(conv)
    if (!byWs.has(wsId)) byWs.set(wsId, groups)
  }
  // sort groups within each workspace
  for (const groups of byWs.values()) {
    groups.sort((a, b) => a.sortOrder - b.sortOrder)
  }
  return byWs
})

/** 任务会话 hover 卡状态 */
const taskHoverConv = ref<Conversation | null>(null)
const taskHoverAnchor = ref<HTMLElement | null>(null)
const taskHoverCardRef = ref<InstanceType<typeof ConversationHoverCard> | null>(null)

function onTaskItemEnter(conv: Conversation, e: MouseEvent) {
  taskHoverConv.value = conv
  taskHoverAnchor.value = e.currentTarget as HTMLElement
  requestAnimationFrame(() => taskHoverCardRef.value?.show())
}
function onTaskItemLeave() {
  taskHoverCardRef.value?.hide()
  taskHoverConv.value = null
  taskHoverAnchor.value = null
}

/** 工作区名称 hover 卡（Teleport，对齐会话 hover 卡） */
const wsHover = ref<WorkspaceVO | null>(null)
const wsHoverAnchor = ref<HTMLElement | null>(null)
const wsHoverVisible = ref(false)
let wsHoverTimer: ReturnType<typeof setTimeout> | null = null

function onWsNameEnter(ws: WorkspaceVO, e: MouseEvent) {
  wsHover.value = ws
  wsHoverAnchor.value = e.currentTarget as HTMLElement
  if (wsHoverTimer) clearTimeout(wsHoverTimer)
  wsHoverTimer = setTimeout(() => {
    wsHoverVisible.value = true
  }, 350)
}
function onWsNameLeave() {
  if (wsHoverTimer) clearTimeout(wsHoverTimer)
  wsHoverTimer = null
  wsHoverVisible.value = false
  wsHover.value = null
  wsHoverAnchor.value = null
}

function taskItemTime(conv: Conversation) {
  return formatSidebarItemTime(conv.updatedAt, nowTick.value)
}

/** 反查工作区 id → 项目名（hover 卡显示用） */
function workspaceNameOf(wsId?: string | null): string {
  if (!wsId) return ''
  return workspaces.value.find(w => w.id === wsId)?.name ?? ''
}

/** 格式化工作区创建时间（简短显示） */
function formatWorkspaceTime(d: string) {
  if (!d) return '-'
  try { return formatConversationTime(new Date(d).getTime()) } catch { return d }
}

/** 某工作区下的任务会话数 */
function wsTaskCount(wsId: string): number {
  return chatStore.conversations.filter(c => c.kind === 'task' && c.workspaceId === wsId).length
}

function taskMenuOptions(id: string): DropdownOption[] {
  return [
    { label: '重命名', key: `rename:${id}`, icon: renderDropdownIcon(CreateOutline) },
    { type: 'divider', key: `div:${id}` },
    {
      label: '删除',
      key: `delete:${id}`,
      props: { class: 'history-dropdown-delete' },
      icon: renderDropdownIcon(TrashOutline),
    },
  ]
}

function toggleWsGroup(wsId: string) {
  wsGroupOpen[wsId] = !wsGroupOpen[wsId]
}

function wsMenuOptions(ws: WorkspaceVO): DropdownOption[] {
  return [
    {
      key: 'new-task',
      label: '新任务',
      icon: () => h(NIcon, { size: 14, component: AddOutline }),
    },
    {
      key: 'project-guide',
      label: '项目规范',
      icon: () => h(NIcon, { size: 14, component: DocumentTextOutline }),
    },
    {
      key: 'delete',
      label: '删除',
      icon: () => h(NIcon, { size: 14, component: TrashOutline, style: 'color: var(--sun-error, #e54d42)' }),
    },
  ]
}

function handleWsMenuSelect(key: string, ws: WorkspaceVO) {
  if (key === 'new-task') {
    handleWsNewTask(ws)
  } else if (key === 'project-guide') {
    openProjectGuide(ws)
  } else if (key === 'delete') {
    confirmDeleteWorkspace(ws)
  }
}

const showProjectGuide = ref(false)
const guideWorkspace = ref<WorkspaceVO | null>(null)
function openProjectGuide(ws: WorkspaceVO) {
  guideWorkspace.value = ws
  showProjectGuide.value = true
}

function handleWsNewTask(ws: WorkspaceVO) {
  chatStore.pendingWorkspace = { wsId: ws.id, wsName: ws.name }
  chatStore.newTaskMode = true
  chatStore.currentId = null
  expanded.workspace = true
  expanded.chat = false
  expanded.platform = false
  wsGroupOpen[ws.id] = true
  if (route.name !== 'chat') router.push('/chat')
}

const deletingWs = ref<string | null>(null)
async function handleDeleteWorkspace(ws: WorkspaceVO) {
  deletingWs.value = ws.id
  try {
    await destroyWorkspace(ws.id)
    message.success('工作区已删除')
    if (wsGroupOpen[ws.id]) delete wsGroupOpen[ws.id]
    await fetchWorkspaces()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '删除失败'))
  } finally { deletingWs.value = null }
}

function confirmDeleteWorkspace(ws: WorkspaceVO) {
  dialog.create({
    class: 'sunshine-dialog',
    showIcon: false,
    title: '永久删除工作区',
    content: `确定删除「${ws.name}」吗？\n此操作不可撤销，将清除 Docker 容器和所有关联会话。`,
    positiveText: '永久删除',
    negativeText: '取消',
    positiveButtonProps: { type: 'error', size: 'medium' },
    negativeButtonProps: { ghost: false, quaternary: true, size: 'medium' },
    onPositiveClick: () => void handleDeleteWorkspace(ws),
  })
}

async function fetchWorkspaces() {
  loadingWorkspaces.value = true
  try { workspaces.value = await listWorkspaces() }
  catch { /* silently fail */ }
  finally { loadingWorkspaces.value = false }
}

watch(() => expanded.workspace, (open) => {
  if (open && workspaces.value.length === 0 && !loadingWorkspaces.value) {
    fetchWorkspaces()
  }
})

/** 当前会话变化（含刷新后 ChatView 恢复会话）→ 侧栏展开对应分区与工作区分组：task → 任务、chat → 对话 */
watch(
  () => [chatStore.current?.kind, chatStore.current?.workspaceId] as const,
  ([kind, wsId]) => {
    if (kind === 'task') {
      expanded.workspace = true
      expanded.chat = false
      expanded.platform = false
      if (wsId) wsGroupOpen[wsId] = true
    } else if (kind === 'chat') {
      expanded.chat = true
      expanded.workspace = false
      expanded.platform = false
    }
  },
)

async function handleCreateWorkspace() {
  const name = newWsName.value.trim()
  const url = newWsRepoUrl.value.trim()
  if (!url) { message.warning('请输入仓库地址'); return }
  creatingWs.value = true
  try {
    // 只填 git 路径：clone 默认拉取远程主分支
    await createWorkspace({ name: name || undefined, repoUrl: url })
    message.success('工作区已创建')
    showCreateWorkspace.value = false
    newWsName.value = ''
    newWsRepoUrl.value = ''
    await fetchWorkspaces()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '创建失败'))
  } finally { creatingWs.value = false }
}

function handleWorkspaceClick(_ws: WorkspaceVO) {
  // handled by handleWsNewTask + ChatView branch selector
}

onMounted(() => {
  void chatStore.init()
})
</script>

<template>
  <NLayout has-sider class="app-shell">
    <NLayoutSider
      v-if="sidebarVisible"
      bordered
      :width="sidebarWidth"
      class="sidebar"
    >
      <!-- Brand -->
      <div class="brand">
        <BrandMark class="brand-mark" />
        <span class="brand-name">Sunshine<span class="brand-ai"> AI</span></span>
        <button
          type="button"
          class="brand-search-btn"
          title="搜索会话"
          aria-label="搜索会话"
          @click="showSearch = true"
        >
          <NIcon :size="16" :component="SearchOutline" />
        </button>
      </div>

      <!-- 快捷操作：新对话、新任务 -->
      <div class="quick-actions">
        <button type="button" class="action-btn" @click="handleNewChat">
          <NIcon :size="16" :component="ChatbubblesOutline" />
          <span>新对话</span>
        </button>
        <button type="button" class="action-btn" @click="handleNewTask">
          <NIcon :size="16" :component="CodeSlashOutline" />
          <span>新任务</span>
        </button>
      </div>

      <!-- 可折叠面板容器 -->
      <div class="sections-scroll">

        <!-- 平台 -->
        <section class="section">
          <button
            type="button"
            class="section-header"
            @click="toggleSection('platform')"
          >
            <span class="section-icon-wrap">
              <NIcon :size="16" :component="AppsOutline" class="section-icon" />
              <NIcon :size="14" :component="ChevronDownOutline" class="section-chevron" :class="{ rotated: expanded.platform }" />
            </span>
            <span class="section-label">平台</span>
          </button>
          <div v-show="expanded.platform" class="section-body">
            <NMenu
              :value="activeKey"
              :options="platformMenuOptions"
              @update:value="handleMenuClick"
              class="nav-menu nav-menu--platform"
            />
          </div>
        </section>

        <!-- 对话 -->
        <section class="section">
          <button
            type="button"
            class="section-header"
            @click="toggleSection('chat')"
          >
            <span class="section-icon-wrap">
              <NIcon :size="16" :component="ChatbubblesOutline" class="section-icon" />
              <NIcon :size="14" :component="ChevronDownOutline" class="section-chevron" :class="{ rotated: expanded.chat }" />
            </span>
            <span class="section-label">对话</span>
          </button>
          <div v-show="expanded.chat" class="section-body chat-body">
            <ConversationSidebarList
              :conversations="chatSidebarConversations"
              :menu-options="conversationMenuOptions"
              @switch="handleSwitchConversation"
              @menu="handleConversationMenu"
            />
          </div>
        </section>

        <!-- 任务（工作区） -->
        <section class="section">
          <div class="section-header-row">
            <button
              type="button"
              class="section-header section-header--grow"
              @click="toggleSection('workspace')"
            >
              <span class="section-icon-wrap">
                <NIcon :size="16" :component="CodeSlashOutline" class="section-icon" />
                <NIcon :size="14" :component="ChevronDownOutline" class="section-chevron" :class="{ rotated: expanded.workspace }" />
              </span>
              <span class="section-label">任务</span>
            </button>
            <button
              type="button"
              class="section-add-btn"
              title="添加工作区"
              @click.stop="showCreateWorkspace = true"
            >
              <NIcon :size="16" :component="AddOutline" />
            </button>
          </div>
          <div v-show="expanded.workspace" class="section-body ws-body">
            <div v-if="loadingWorkspaces" class="ws-loading">加载中...</div>
            <div v-else-if="workspaces.length === 0" class="ws-empty">
              <span class="ws-empty-text">暂无工作区</span>
              <NButton size="tiny" quaternary @click="showCreateWorkspace = true">创建</NButton>
            </div>
            <div v-else class="ws-list">
              <!-- 工作区分组，支持折叠展开 -->
              <div
                v-for="ws in workspaces"
                :key="ws.id"
                class="ws-group"
              >
                <div class="ws-group-header" @click="toggleWsGroup(ws.id)">
                  <span class="ws-group-icon-wrap">
                    <NIcon :size="14" :component="wsGroupOpen[ws.id] ? FolderOpenOutline : FolderOutline" class="ws-folder-icon" />
                    <NIcon :size="12" :component="ChevronDownOutline" class="ws-group-chevron" :class="{ rotated: wsGroupOpen[ws.id] }" />
                  </span>
                  <span
                    class="ws-group-name"
                    @mouseenter="onWsNameEnter(ws, $event)"
                    @mouseleave="onWsNameLeave"
                  >{{ ws.name }}</span>
                  <div class="ws-group-menu-wrap" @click.stop>
                    <NDropdown
                      trigger="click"
                      size="small"
                      placement="bottom-end"
                      :options="wsMenuOptions(ws)"
                      @select="handleWsMenuSelect($event, ws)"
                    >
                      <button
                        type="button"
                        class="ws-group-menu-btn"
                        title="更多"
                      >
                        <NIcon :size="14" :component="EllipsisHorizontal" />
                      </button>
                    </NDropdown>
                  </div>
                </div>
                <!-- 该工作区下的任务会话（按时段分组，样式对齐对话列表） -->
                <div v-if="wsGroupOpen[ws.id]" class="ws-group-body task-conv-list">
                  <div v-if="wsTaskGroups.has(ws.id)">
                    <div v-for="group in wsTaskGroups.get(ws.id)!" :key="group.key" class="task-conv-group">
                      <div class="task-conv-group-label">{{ group.label }}</div>
                      <div
                        v-for="conv in group.items"
                        :key="conv.id"
                        class="task-conv-item"
                        :class="{
                          active: route.name === 'chat' && conv.id === chatStore.currentId,
                        }"
                        @click="handleSwitchConversation(conv.id)"
                        @mouseenter="onTaskItemEnter(conv, $event)"
                        @mouseleave="onTaskItemLeave"
                      >
                        <ConversationStatusIcon
                          :state="resolveIndicator(conv.id, conv.messages)"
                          :active="route.name === 'chat' && conv.id === chatStore.currentId"
                          :title="resolveIndicator(conv.id, conv.messages) === 'streaming' ? '正在生成' : undefined"
                        />
                        <span class="task-conv-title">{{ conv.title }}</span>
                        <span class="task-conv-meta">
                          <span class="task-conv-time">{{ taskItemTime(conv) }}</span>
                        </span>
                        <NDropdown
                          trigger="click"
                          size="small"
                          placement="bottom-end"
                          :options="taskMenuOptions(conv.id)"
                          @select="handleConversationMenu"
                        >
                          <button
                            type="button"
                            class="task-conv-more"
                            title="更多"
                            aria-label="更多"
                            @click.stop
                          >
                            <EllipsisHorizontal width="16" height="16" />
                          </button>
                        </NDropdown>
                      </div>
                    </div>
                  </div>
                  <div v-else class="task-conv-empty">暂无任务</div>
                </div>
              </div>
            </div>
          </div>
        </section>

      </div>

      <!-- 用户区 -->
      <div class="sidebar-user">
        <div class="user-avatar" aria-hidden="true">{{ userInitial }}</div>
        <span class="user-nickname" :title="displayNickname">{{ displayNickname }}</span>
        <div class="sidebar-user-actions">
          <button class="theme-toggle" type="button" @click="toggleTheme" :title="isDark ? '切换浅色模式' : '切换深色模式'">
            <svg v-if="isDark" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <circle cx="12" cy="12" r="5" /><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
            </svg>
            <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
            </svg>
          </button>
          <NDropdown trigger="click" size="small" :options="userMenuOptions" @select="handleUserMenu">
            <button type="button" class="user-more-btn" title="更多" aria-label="更多">
              <EllipsisHorizontal width="18" height="18" />
            </button>
          </NDropdown>
        </div>
      </div>

      <!-- 侧栏宽度拖拽手柄 -->
      <div
        class="sidebar-resize-handle"
        role="separator"
        aria-orientation="vertical"
        aria-label="调整侧栏宽度"
        @pointerdown="onSidebarResizePointerDown"
      />
    </NLayoutSider>

    <ConversationHoverCard
      v-if="taskHoverConv"
      ref="taskHoverCardRef"
      :conversation="taskHoverConv"
      :anchor="taskHoverAnchor"
      :workspace-name="workspaceNameOf(taskHoverConv.workspaceId)"
    />
    <!-- 工作区 hover 详情卡（Teleport，对齐会话 hover 卡） -->
    <Teleport to="body">
      <div
        v-if="wsHoverVisible && wsHover && wsHoverAnchor"
        class="ws-hover-card"
        :style="{ top: wsHoverAnchor.getBoundingClientRect().top + 'px', left: (wsHoverAnchor.getBoundingClientRect().right + 8) + 'px' }"
        role="tooltip"
      >
        <div class="ws-hover-title">{{ wsHover.name }}</div>
        <div class="ws-hover-row">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="3" /><path d="M20 12a8 8 0 1 0-4 6.93" /><path d="M15 17l4 4-4 4" /></svg>
          <span class="ws-hover-row-text">{{ wsHover.repoUrl || '-' }}</span>
        </div>
        <div class="ws-hover-row">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10" /><polyline points="12 6 12 12 16 14" /></svg>
          <span class="ws-hover-row-text">创建于 {{ formatWorkspaceTime(wsHover.createdAt) }}</span>
        </div>
        <div class="ws-hover-row">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>
          <span class="ws-hover-row-text">{{ wsTaskCount(wsHover.id) }} 个任务会话</span>
        </div>
      </div>
    </Teleport>
    <UserSettingsModal v-model:show="showSettings" />

    <!-- 会话聚合搜索弹窗（对话 + 任务会话） -->
    <ConversationSearchModal
      v-model:show="showSearch"
      :workspace-name-of="workspaceNameOf"
      @select="handleSearchSelect"
    />

    <!-- 项目规范弹窗（类 CLAUDE.md，用户手动维护） -->
    <ProjectGuideModal v-model:show="showProjectGuide" :workspace="guideWorkspace" />

    <!-- 创建工作区弹窗 -->
    <NModal
      :show="showCreateWorkspace"
      preset="card"
      title="新建工作区"
      style="width:480px"
      @update:show="showCreateWorkspace = $event"
    >
      <div class="ws-create-form">
        <label class="ws-create-label">名称 <span class="ws-create-optional">(可选，留空自动从仓库地址提取)</span></label>
        <NInput v-model:value="newWsName" class="sun-field" placeholder="如 my-project" maxlength="128" :disabled="creatingWs" />
        <label class="ws-create-label">仓库地址</label>
        <NInput v-model:value="newWsRepoUrl" class="sun-field" placeholder="https://github.com/user/repo" maxlength="512" :disabled="creatingWs" />
        <p class="ws-create-hint">clone 默认拉取远程主分支，进入任务会话时可选择其他分支。</p>
      </div>
      <template #footer>
        <div class="ws-create-footer">
          <NButton quaternary :disabled="creatingWs" @click="showCreateWorkspace = false">取消</NButton>
          <NButton type="primary" :loading="creatingWs" @click="handleCreateWorkspace">创建</NButton>
        </div>
      </template>
    </NModal>

    <NLayoutContent class="content-area" :class="{ 'content-area--fill': contentFill }">
      <SidebarToggle v-if="!sidebarVisible && !hideSidebarFab" variant="fab" />
      <router-view />
    </NLayoutContent>
  </NLayout>
</template>

<style scoped>
.sidebar-user-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  margin-left: auto;
}

/* --- Layout --- */
.app-shell {
  height: 100vh;
  min-height: 100vh;
}

.app-shell :deep(.n-layout-scroll-container) {
  height: 100%;
}

.sidebar {
  position: relative;
  background: var(--sun-sidebar-bg) !important;
  border-right: 1px solid var(--sun-border) !important;
  display: flex;
  flex-direction: column;
  height: 100vh;
}

/* 拖拽中禁用 NLayoutSider 自带动画，实现即时跟随 */
:global(body.sun-sidebar-resizing) .sidebar,
:global(body.sun-sidebar-resizing) .sidebar :deep(.n-layout-sider-scroll-container),
:global(body.sun-sidebar-resizing) .sidebar :deep(.n-layout-toggle-bar) {
  transition: none !important;
}

.sidebar :deep(.n-layout-sider-border) {
  background-color: var(--sun-border) !important;
}

.sidebar :deep(.n-layout-sider-scroll-container) {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  height: 100%;
}

.sidebar-resize-handle {
  position: absolute;
  right: -3px;
  top: 0;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 10;
}

.sidebar-resize-handle::after {
  content: '';
  position: absolute;
  left: 2px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: transparent;
}

.sidebar-resize-handle:hover::after,
:global(body.sun-sidebar-resizing) .sidebar-resize-handle::after {
  background: var(--sun-border);
}

:global(body.sun-sidebar-resizing) {
  cursor: col-resize !important;
  user-select: none;
}

/* --- Brand --- */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px 10px;
  border-bottom: 1px solid var(--sun-border);
}

.brand-mark {
  flex-shrink: 0;
  display: block;
}

.brand-search-btn {
  width: 26px;
  height: 26px;
  margin-left: auto;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: background 0.15s, color 0.15s;
}

.brand-search-btn:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.brand-name {
  font-size: var(--sun-font-md);
  font-weight: 600;
  letter-spacing: -0.45px;
  color: var(--sun-text);
  line-height: 1;
  white-space: nowrap;
}

.brand-ai {
  font-weight: 400;
  color: var(--sun-text-muted);
}

/* --- Quick Actions --- */
.quick-actions {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 8px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 9px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-base);
  line-height: 1.3;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.action-btn:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

/* --- Sections --- */
.sections-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  padding: 4px 8px;
  gap: 1px;
}

.section {
  flex-shrink: 0;
}

.section-header-row {
  display: flex;
  align-items: center;
  border-radius: 6px;
  transition: background 0.15s;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 9px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: var(--sun-font-base);
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  letter-spacing: 0.02em;
  transition: color 0.15s, background 0.15s;
}

.section-header:hover {
  color: var(--sun-text-secondary);
  background: var(--sun-row-hover);
}

.section-header-row:hover {
  background: var(--sun-row-hover);
}

.section-header-row:hover .section-header {
  color: var(--sun-text-secondary);
}

.section-header-row:hover .section-add-btn {
  color: var(--sun-text);
}

.section-header-row .section-header:hover,
.section-header-row .section-add-btn:hover {
  background: transparent;
}

.section-header-row .section-header {
  padding: 9px 12px;
}

.section-header-row .section-add-btn {
  margin-right: 12px;
}

.section-header--grow {
  flex: 1;
}

.section-icon-wrap {
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.section-icon-wrap > * {
  grid-area: 1 / 1;
}

.section-chevron {
  opacity: 0;
  transition: opacity 0.15s, transform 0.18s ease;
}

.section-header:hover .section-chevron,
.section-header-row:hover .section-chevron {
  opacity: 0.6;
}

.section-chevron.rotated {
  transform: rotate(90deg);
}

.section-icon {
  opacity: 0.65;
  transition: opacity 0.15s;
}

.section-header:hover .section-icon,
.section-header-row:hover .section-icon {
  opacity: 0;
}

.section-label {
  flex: 1;
  text-align: left;
}

.section-add-btn {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-right: 8px;
  transition: color 0.15s, background 0.15s;
}

.section-add-btn:hover {
  color: var(--sun-text);
}

.section-body {
  overflow: hidden;
}

.section-body.chat-body {
  display: flex;
  flex-direction: column;
  padding: 0 0 4px;
}

/* --- Platform NMenu --- */
.nav-menu {
  padding: 0 8px;
}

.nav-menu :deep(.n-menu) {
  --n-color: transparent !important;
  --n-item-color-hover: var(--sun-row-hover) !important;
  --n-item-color-active: var(--sun-row-hover) !important;
  --n-item-color-active-hover: var(--sun-row-hover) !important;
}

.nav-menu :deep(.n-menu-item-content) {
  border-radius: var(--radius-sm);
  transition: background 0.15s, color 0.15s;
}

.nav-menu :deep(.n-menu-item-content--selected) {
  font-weight: 600;
}

.nav-menu :deep(.n-menu-item-content--selected .n-menu-item-content-header),
.nav-menu :deep(.n-menu-item-content--selected .n-menu-item-content__icon) {
  color: var(--sun-text) !important;
}

.nav-menu :deep(.n-menu-item-content-header) {
  font-size: var(--sun-font-base);
  color: var(--sun-text-secondary);
}

.nav-menu :deep(.n-menu-item-content__icon) {
  color: var(--sun-text-secondary);
}

.nav-menu :deep(.n-menu-item) {
  height: 40px;
}

/* 平台菜单：缩小图标与字体 */
.nav-menu--platform :deep(.n-menu-item-content-header) {
  font-size: var(--sun-font-sm, 13px);
}

.nav-menu--platform :deep(.n-menu-item-content__icon) {
  font-size: 16px !important;
  margin-right: 6px !important;
}

.nav-menu--platform :deep(.n-menu-item) {
  height: 38px;
}

/* --- Workspace Body --- */
.ws-body {
  padding: 0 0 4px;
}

.ws-loading {
  padding: 12px;
  color: var(--sun-text-muted);
  text-align: center;
  font-size: var(--sun-font-xs);
}

.ws-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 16px 8px 4px;
}

.ws-empty-text {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

.ws-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ws-group {
  margin: 1px 0;
}

.ws-group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.ws-group-header:hover {
  background: var(--sun-row-hover);
}

.ws-group-icon-wrap {
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.ws-group-icon-wrap > * {
  grid-area: 1 / 1;
}

.ws-folder-icon {
  opacity: 0.65;
  transition: opacity 0.15s;
}

.ws-group-header:hover .ws-folder-icon {
  opacity: 0;
}

.ws-group-chevron {
  opacity: 0;
  transition: opacity 0.15s, transform 0.18s ease;
}

.ws-group-header:hover .ws-group-chevron {
  opacity: 0.6;
}

.ws-group-chevron.rotated {
  transform: rotate(90deg);
}

.ws-group-name {
  flex: 1;
  text-align: left;
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-group-menu-wrap {
  flex-shrink: 0;
}

.ws-group-menu-btn {
  width: 22px;
  height: 22px;
  border-radius: 4px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.15s, color 0.15s;
}

.ws-group-header:hover .ws-group-menu-btn,
.ws-group-menu-btn:hover {
  opacity: 1;
}

.ws-group-menu-btn:hover {
  color: var(--sun-text);
}

/* --- 工作区 hover 详情卡（Teleport，对齐 conv-hover-card） --- */
.ws-hover-card {
  position: fixed;
  z-index: 1000;
  min-width: 220px;
  max-width: 340px;
  padding: 10px 12px;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-elevated);
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ws-hover-title {
  font-size: var(--sun-font-base);
  font-weight: 600;
  color: var(--sun-text);
  line-height: 1.4;
  word-break: break-word;
  white-space: normal;
}
.ws-hover-row {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1.3;
}
.ws-hover-row svg {
  flex-shrink: 0;
  opacity: 0.8;
}
.ws-hover-row-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* --- 任务会话列表（对齐对话侧栏样式） --- */
.task-conv-list {
  padding: 2px 0 6px;
}

.task-conv-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.task-conv-group-label {
  padding: 4px 8px 2px 24px;
  font-size: var(--sun-font-xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--sun-text-muted);
  user-select: none;
}

.task-conv-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px 6px 22px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}

.task-conv-item:hover { background: var(--sun-row-hover); }
.task-conv-item.active { background: var(--sun-row-hover); }
.task-conv-item.active .task-conv-title {
  color: var(--sun-text);
  font-weight: 500;
}

.task-conv-title {
  flex: 1;
  min-width: 0;
  font-size: var(--sun-font-sm);
  font-weight: 400;
  color: var(--sun-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: var(--sun-line);
}

.task-conv-meta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
}

.task-conv-time {
  line-height: 1;
}

.task-conv-more {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  opacity: 0;
  transition: background 0.15s, color 0.15s, opacity 0.15s;
}

.task-conv-item:hover .task-conv-more,
.task-conv-item.active .task-conv-more {
  opacity: 0.55;
}

.task-conv-more:hover {
  opacity: 1 !important;
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

.task-conv-empty {
  padding: 8px 24px;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
}

/* --- Create Workspace Modal --- */
.ws-create-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ws-create-label {
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
}

.ws-create-optional {
  font-weight: 400;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
}

.ws-create-hint {
  margin: -4px 0 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1.5;
}

.ws-create-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* --- User Area --- */
.sidebar-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px 8px 14px;
  flex-shrink: 0;
  border-top: 1px solid var(--sun-border);
}

.user-nickname {
  flex: 1;
  min-width: 0;
  font-size: var(--sun-font-sm);
  font-weight: 600;
  color: var(--sun-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-more-btn {
  width: 26px;
  height: 26px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.user-more-btn:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.user-avatar {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm);
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.theme-toggle {
  width: 26px; height: 26px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
  flex-shrink: 0;
}

.theme-toggle:hover {
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

/* --- Content --- */
.content-area {
  background: var(--sun-black);
  overflow: auto;
  height: 100%;
  min-height: 0;
}

.content-area--fill {
  overflow: hidden;
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.content-area--fill :deep(> *) {
  flex: 1;
  min-height: 0;
}
</style>

<!-- 拖拽侧栏时禁用 Naive UI NLayoutSider 自带 CSS transition -->
<style>
body.sun-sidebar-resizing .n-layout-sider,
body.sun-sidebar-resizing .n-layout-sider * {
  transition: none !important;
}
body.sun-sidebar-resizing .sidebar {
  transition: none !important;
}
</style>
