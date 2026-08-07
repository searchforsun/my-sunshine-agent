<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { NIcon, NPopover } from 'naive-ui'
import { ChevronDownOutline, GitBranchOutline, CheckmarkOutline, AddOutline, ConstructOutline } from '@vicons/ionicons5'
import { listCheckouts, listBranches } from '../../api/workspaceGit'
import type { CheckoutInfo, GitBranchInfo } from '../../api/workspaceGit'

const props = defineProps<{
  workspaceId: string
  /** 当前选中的分支名（本地名，与 checkout 目录解耦） */
  modelValue: string
  /** 右侧工作区真实代码分支（会话未发送时为缺省 checkout 分支；与 modelValue 意图分支可不同） */
  activeBranch?: string
  /** 是否新建会话：可选择任意分支；已有会话亦可切换分支（发送时才真正切换 checkout） */
  createMode?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const checkouts = ref<CheckoutInfo[]>([])
const branches = ref<GitBranchInfo[]>([])
const loading = ref(false)
const showMenu = ref(false)
/** 顶部表单：筛选关键词 / 新建分支名 */
const filterText = ref('')

/** 实际选中分支名（分支与工作区目录一一对应；未选为空） */
const currentBranch = computed(() => props.modelValue)
/** 过滤后的分支列表 */
const filteredLocal = computed(() => {
  const kw = filterText.value.trim().toLowerCase()
  const list = branches.value.filter(b => b.type === 'local')
  return kw ? list.filter(b => b.name.toLowerCase().includes(kw)) : list
})
const filteredRemote = computed(() => {
  const kw = filterText.value.trim().toLowerCase()
  const list = branches.value.filter(b => b.type === 'remote' && b.name !== 'origin/HEAD')
  return kw ? list.filter(b => b.name.toLowerCase().includes(kw)) : list
})
/** 当前选中分支是否为「新建」（不在分支列表中，发送会话后才真正创建） */
const isNewBranch = computed(() => {
  const v = props.modelValue
  return !!v && !branches.value.some(b => b.name === v)
})

/** 远程分支 → 本地名（选择即绑定对应本地分支，与目录一一对应） */
function localize(branchName: string): string {
  return branchName.startsWith('origin/') ? branchName.slice('origin/'.length) : branchName
}

/** 输入名是否对应已存在分支 */
function branchExists(name: string): boolean {
  return branches.value.some(b => b.name === name || localize(b.name) === name)
}

async function fetchAll() {
  if (!props.workspaceId) return
  loading.value = true
  try {
    // 先 listCheckouts（内部会 ensureWorkspaceSession 触发 clone，同步阻塞到完成），
    // 再 listBranches 确保 host 上 .git 已存在，避免并发时 clone 未完成导致分支列表为空
    const cs = await listCheckouts(props.workspaceId)
    const bs = await listBranches(props.workspaceId)
    checkouts.value = cs
    branches.value = bs
  } catch { /* silently fail */ }
  finally { loading.value = false }
}

/** 点击分支：仅前端选中，不触发任何创建；发送会话时才懒创建对应目录 */
function selectBranch(branchName: string) {
  emit('update:modelValue', localize(branchName))
  showMenu.value = false
  filterText.value = ''
}

/** 顶部表单「+」：已有分支 → 选中；新分支名 → 暂存选中（发送会话时才真正创建） */
function handleCreateBranch() {
  const name = filterText.value.trim()
  if (!name) return
  selectBranch(name)
}

function onShowUpdate(next: boolean) {
  if (next) fetchAll()
  showMenu.value = next
  if (!next) filterText.value = ''
}

watch(() => props.workspaceId, () => {
  if (props.workspaceId) fetchAll()
}, { immediate: true })

// 会话切换（modelValue 变化）时重载，确保外部显示的实际分支名同步
watch(() => props.modelValue, () => {
  if (props.workspaceId) fetchAll()
})
</script>

<template>
  <div class="branch-dropdown-root">
    <NPopover
      :show="showMenu"
      trigger="click"
      content-class="branch-selector-popover"
      placement="top-start"
      :width="300"
      raw
      :show-arrow="false"
      @update:show="onShowUpdate"
    >
      <template #trigger>
        <button
          type="button"
          class="branch-trigger"
          :title="currentBranch || '选择分支'"
        >
          <span class="branch-leading">
            <NIcon class="branch-icon" :component="GitBranchOutline" :size="14" />
            <span class="branch-label">{{ currentBranch || '选择分支' }}</span>
          </span>
          <NIcon class="branch-chevron" :component="ChevronDownOutline" :size="12" />
        </button>
      </template>

      <div class="branch-menu" role="listbox" aria-label="分支">
        <!-- 顶部表单：筛选 + 新建分支名 -->
        <div class="branch-filter-row">
          <input
            v-model="filterText"
            class="branch-filter-input"
            placeholder="筛选 / 新建分支名"
            maxlength="128"
            spellcheck="false"
            @keydown.enter="handleCreateBranch"
          />
          <button
            type="button"
            class="branch-filter-add"
            :disabled="!filterText.trim()"
            title="添加分支"
            @click="handleCreateBranch"
          >
            <NIcon :size="16" :component="AddOutline" />
          </button>
        </div>

        <div v-if="loading" class="branch-menu-empty">加载中...</div>
        <template v-else>
          <div
            v-if="filterText.trim() && !branchExists(filterText.trim())"
            class="branch-menu-empty is-new-branch"
          >
            新建分支：{{ filterText.trim() }}
          </div>
          <div
            v-else-if="filteredLocal.length === 0 && filteredRemote.length === 0"
            class="branch-menu-empty"
          >暂无分支</div>

          <template v-if="filteredLocal.length > 0">
            <div class="branch-section-label">本地分支</div>
            <button
              v-for="b in filteredLocal"
              :key="'l-'+b.name"
              type="button"
              role="option"
              class="branch-menu-item"
              :class="{ 'is-selected': currentBranch === b.name }"
              :aria-selected="currentBranch === b.name"
              @click="selectBranch(b.name)"
            >
              <NIcon class="branch-menu-icon" :component="GitBranchOutline" :size="18" />
              <span class="branch-menu-text">
                <span class="branch-menu-title">{{ b.name }}</span>
              </span>
              <span class="branch-menu-check-slot">
                <template v-if="currentBranch === b.name">
                  <NIcon
                    class="branch-menu-check"
                    :component="CheckmarkOutline"
                    :size="16"
                  />
                </template>
                <template v-else-if="props.activeBranch && props.activeBranch === b.name">
                  <NIcon
                    class="branch-menu-ws"
                    :component="ConstructOutline"
                    :size="14"
                    title="右侧工作区对应此分支"
                  />
                </template>
              </span>
            </button>
          </template>

          <template v-if="filteredRemote.length > 0">
            <div class="branch-section-label">远程分支</div>
            <button
              v-for="b in filteredRemote"
              :key="'r-'+b.name"
              type="button"
              role="option"
              class="branch-menu-item"
              :class="{ 'is-selected': currentBranch === localize(b.name) }"
              :aria-selected="currentBranch === localize(b.name)"
              @click="selectBranch(b.name)"
            >
              <NIcon class="branch-menu-icon" :component="GitBranchOutline" :size="18" />
              <span class="branch-menu-text">
                <span class="branch-menu-title">{{ b.name }}</span>
              </span>
              <span class="branch-menu-check-slot">
                <template v-if="currentBranch === localize(b.name)">
                  <NIcon
                    class="branch-menu-check"
                    :component="CheckmarkOutline"
                    :size="16"
                  />
                </template>
                <template v-else-if="props.activeBranch && props.activeBranch === localize(b.name)">
                  <NIcon
                    class="branch-menu-ws"
                    :component="ConstructOutline"
                    :size="14"
                    title="右侧工作区对应此分支"
                  />
                </template>
              </span>
            </button>
          </template>

          <div v-if="isNewBranch" class="branch-menu-empty is-new-tag">
            将在发送后创建新分支「{{ currentBranch }}」及对应工作区
          </div>
        </template>
      </div>
    </NPopover>
  </div>
</template>

<style scoped>
.branch-dropdown-root {
  display: inline-flex;
  max-width: 100%;
}

.branch-trigger {
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
  cursor: pointer;
  flex-shrink: 0;
  max-width: 180px;
  transition: background 0.15s, color 0.15s;
}

.branch-trigger:hover {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
  color: var(--sun-text);
}

.branch-leading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.branch-icon,
.branch-chevron {
  flex-shrink: 0;
  color: currentColor;
}

.branch-label {
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.branch-chevron {
  opacity: 0.55;
}

.branch-menu {
  padding: 3px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, #fff);
  box-shadow: var(--shadow-elevated, 0 4px 12px rgba(0, 0, 0, 0.12));
  border: 1px solid var(--sun-border, #e8e8e8);
  overflow: hidden;
  max-height: 420px;
  overflow-y: auto;
}

.branch-filter-row {
  display: flex;
  gap: 4px;
  padding: 4px 4px 6px;
  border-bottom: 1px solid var(--sun-border);
  margin-bottom: 2px;
}

.branch-filter-input {
  flex: 1;
  min-width: 0;
  height: 30px;
  padding: 0 8px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--sun-text);
  font-size: var(--sun-font-sm, 12px);
  font-family: inherit;
  outline: none;
}

.branch-filter-input:focus {
  background: var(--sun-row-hover);
}

.branch-filter-input::placeholder {
  color: var(--sun-text-muted);
}

.branch-filter-add {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  flex-shrink: 0;
  transition: color 0.15s, background 0.15s;
}

.branch-filter-add:hover:not(:disabled) {
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

.branch-filter-add:disabled {
  opacity: 0.35;
  cursor: default;
}

.branch-menu-empty {
  padding: 10px 10px;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  text-align: center;
}

.branch-menu-empty.is-new-branch {
  color: var(--sun-accent);
  font-weight: 500;
}

.branch-menu-empty.is-new-tag {
  padding: 6px 10px;
  border-top: 1px solid var(--sun-border);
  font-size: 11px;
  text-align: left;
}

.branch-section-label {
  padding: 6px 10px 3px;
  font-size: 11px;
  font-weight: 600;
  color: var(--sun-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.branch-menu-item {
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

.branch-menu-item:hover {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
}

.branch-menu-icon {
  flex-shrink: 0;
  color: var(--sun-text-secondary, #666);
}

.branch-menu-item.is-selected .branch-menu-icon {
  color: var(--sun-text, #212121);
}

.branch-menu-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.branch-menu-title {
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  line-height: 1.35;
  color: var(--sun-text, #212121);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.branch-menu-sub {
  font-size: 11px;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.branch-menu-check-slot {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  min-width: 18px;
}

.branch-menu-check {
  color: var(--sun-text, #212121);
}

.branch-menu-ws {
  margin-left: 3px;
  color: var(--sun-text-muted);
}
</style>

<style>
.n-popover.n-popover--raw:has(.branch-menu),
.n-popover-shared:has(.branch-menu) {
  box-shadow: none !important;
  background: transparent !important;
  border-radius: 0 !important;
  padding: 0 !important;
}

.branch-selector-popover {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}

.branch-selector-popover .n-popover__content,
.branch-selector-popover .v-binder-follower-content,
.v-binder-follower-content:has(.branch-menu) {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}
</style>
