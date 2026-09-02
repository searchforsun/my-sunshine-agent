<script setup lang="ts">
import { ref, watch } from 'vue'
import { NModal, NForm, NFormItem, NInput, NButton, useMessage } from 'naive-ui'
import { useAuthStore } from '../stores/authStore'
import { useChatStore } from '../stores/chatStore'
import ExecutionModeSelector from './chat/ExecutionModeSelector.vue'
import SidebarSectionsLayoutSelector from './chat/SidebarSectionsLayoutSelector.vue'
import TimelineStyleSelector from './chat/TimelineStyleSelector.vue'
import WriteHitlModeSelector from './sandbox/WriteHitlModeSelector.vue'
import TenantSelector from './knowledge/TenantSelector.vue'
import KbSelector from './knowledge/KbSelector.vue'
import { useExecutionMode } from '../composables/useExecutionMode'
import { useTimelineStyle, type TimelineStyle } from '../composables/useTimelineStyle'
import { useKbPreference } from '../composables/useKbPreference'
import { useWriteHitlMode } from '../composables/useWriteHitlMode'
import { isWriteHitlMode, type WriteHitlMode } from '../api/writeHitlModes'
import {
  normalizeSidebarSectionsLayout,
  type SidebarSectionsLayout,
} from '../api/sidebarSectionsLayouts'
import { listKbs, type KnowledgeBase } from '../api/ragAdmin'
import { friendlyErrorMessage } from '../api/apiError'
import type { TenantId } from '../api/tenants'

type SettingsGroup = 'account' | 'chat' | 'rules' | 'git'

const GROUPS: Array<{ key: SettingsGroup; label: string }> = [
  { key: 'account', label: '账号' },
  { key: 'chat', label: '对话偏好' },
  { key: 'rules', label: '个人规则' },
  { key: 'git', label: 'Git' },
]

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ 'update:show': [value: boolean] }>()

const auth = useAuthStore()
const chatStore = useChatStore()
const message = useMessage()
const { globalDefault, setGlobalDefault } = useExecutionMode()
const { timelineStyle, setTimelineStyle } = useTimelineStyle()
const { setGlobalDefaultKb } = useKbPreference()
const { globalDefault: writeHitlGlobal, setGlobalDefault: setWriteHitlGlobal } = useWriteHitlMode(
  () => chatStore.currentId,
)
const activeGroup = ref<SettingsGroup>('account')
const nickname = ref('')
const defaultMode = ref(globalDefault.value)
const defaultWriteHitl = ref<WriteHitlMode>(writeHitlGlobal.value)
const sidebarLayout = ref<SidebarSectionsLayout>('vertical')
const timelineStyleLocal = ref<TimelineStyle>('minimal')
const tenantId = ref<TenantId>('default')
const defaultKbId = ref<string | null>(null)
const settingsKbs = ref<KnowledgeBase[]>([])
const loadingSettingsKbs = ref(false)
const personalRules = ref('')
const githubUrl = ref('')
const githubToken = ref('')
const gitlabUrl = ref('')
const gitlabToken = ref('')
/** 打开弹窗时的 PAT 快照：未改动则保存时传 null（不修改、不重验） */
const loadedGithubToken = ref('')
const loadedGitlabToken = ref('')
const saving = ref(false)

async function loadSettingsKbs(tid: TenantId, preferredKb: string | null) {
  loadingSettingsKbs.value = true
  try {
    const list = await listKbs(tid)
    settingsKbs.value = list
    if (preferredKb && list.some((kb) => kb.kbId === preferredKb)) {
      defaultKbId.value = preferredKb
      return
    }
    const fallback = list.find((kb) => kb.isDefault) ?? list[0]
    defaultKbId.value = fallback?.kbId ?? null
  } catch (e) {
    settingsKbs.value = []
    defaultKbId.value = preferredKb
    console.warn('[UserSettingsModal] 加载知识库列表失败', e)
  } finally {
    loadingSettingsKbs.value = false
  }
}

watch(
  () => props.show,
  async (open) => {
    if (open) {
      activeGroup.value = 'account'
      // 打开设置时拉最新资料，保证 PAT 明文回显
      if (auth.isLoggedIn) {
        await auth.fetchMe()
      }
      nickname.value = auth.user?.nickname ?? ''
      defaultMode.value = globalDefault.value
      const fromAuth = auth.user?.defaultWriteHitlMode
      defaultWriteHitl.value = isWriteHitlMode(fromAuth) ? fromAuth : writeHitlGlobal.value
      sidebarLayout.value = normalizeSidebarSectionsLayout(auth.user?.sidebarSectionsLayout)
      timelineStyleLocal.value = timelineStyle.value
      tenantId.value = auth.user?.tenantId ?? 'default'
      personalRules.value = auth.user?.personalRules ?? ''
      githubUrl.value = auth.user?.githubUrl ?? ''
      loadedGithubToken.value = auth.user?.githubToken ?? ''
      githubToken.value = loadedGithubToken.value
      gitlabUrl.value = auth.user?.gitlabUrl ?? ''
      loadedGitlabToken.value = auth.user?.gitlabToken ?? ''
      gitlabToken.value = loadedGitlabToken.value
      await loadSettingsKbs(tenantId.value, auth.user?.defaultKbId ?? null)
    }
  },
)

watch(tenantId, async (tid, prev) => {
  if (!props.show || tid === prev) return
  await loadSettingsKbs(tid, defaultKbId.value)
})

function close() {
  emit('update:show', false)
}

async function handleSave() {
  const value = nickname.value.trim()
  if (!value) {
    message.warning('请输入昵称')
    return
  }
  saving.value = true
  try {
    // PAT：与打开时相同 → null（不修改）；清空 → ""；改写 → 新值
    const nextGithubToken = githubToken.value === loadedGithubToken.value ? null : githubToken.value
    const nextGitlabToken = gitlabToken.value === loadedGitlabToken.value ? null : gitlabToken.value
    const nextKbId = defaultKbId.value?.trim() || ''
    await auth.updateProfile(value, tenantId.value, defaultWriteHitl.value, personalRules.value,
      githubUrl.value || null, nextGithubToken,
      gitlabUrl.value || null, nextGitlabToken,
      sidebarLayout.value, nextKbId)
    setGlobalDefault(defaultMode.value)
    setTimelineStyle(timelineStyleLocal.value)
    setWriteHitlGlobal(defaultWriteHitl.value)
    setGlobalDefaultKb(nextKbId || null)
    const convId = chatStore.currentId
    if (convId) chatStore.updateKbIdLocal(convId, nextKbId || null)
    message.success('资料已更新')
    close()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '保存失败'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="设置"
    class="user-settings-modal"
    :style="{ width: 'min(680px, 94vw)' }"
    :mask-closable="!saving"
    @update:show="emit('update:show', $event)"
  >
    <div class="settings-body">
      <nav class="settings-nav">
        <button
          v-for="g in GROUPS"
          :key="g.key"
          type="button"
          class="settings-nav-item"
          :class="{ active: activeGroup === g.key }"
          :disabled="saving"
          @click="activeGroup = g.key"
        >
          {{ g.label }}
        </button>
      </nav>
      <div class="settings-panel">
        <NForm v-show="activeGroup === 'account'" label-placement="top" :show-require-mark="false">
          <NFormItem label="用户名">
            <NInput class="sun-field" :value="auth.user?.username" disabled />
          </NFormItem>
          <NFormItem label="昵称">
            <NInput
              v-model:value="nickname"
              class="sun-field"
              placeholder="展示在侧栏的名称"
              maxlength="64"
              :disabled="saving"
              @keydown.enter="handleSave"
            />
          </NFormItem>
        </NForm>
        <NForm v-show="activeGroup === 'chat'" label-placement="top" :show-require-mark="false">
          <NFormItem label="当前租户">
            <TenantSelector
              variant="block"
              :model-value="tenantId"
              :disabled="saving"
              @update:model-value="tenantId = $event"
            />
          </NFormItem>
          <NFormItem label="默认知识库">
            <KbSelector
              variant="block"
              :kbs="settingsKbs"
              :model-value="defaultKbId"
              :loading="saving || loadingSettingsKbs"
              :show-create="false"
              @update:model-value="defaultKbId = $event"
            />
          </NFormItem>
          <NFormItem label="默认执行模式">
            <ExecutionModeSelector
              variant="block"
              :model-value="defaultMode"
              :disabled="saving"
              @update:model-value="defaultMode = $event"
            />
          </NFormItem>
          <NFormItem label="默认写操作确认">
            <WriteHitlModeSelector
              variant="block"
              :model-value="defaultWriteHitl"
              :disabled="saving"
              @update:model-value="defaultWriteHitl = $event"
            />
          </NFormItem>
          <NFormItem label="侧栏分区排布">
            <SidebarSectionsLayoutSelector
              :model-value="sidebarLayout"
              :disabled="saving"
              @update:model-value="sidebarLayout = $event"
            />
          </NFormItem>
          <NFormItem label="时间线风格">
            <TimelineStyleSelector
              :model-value="timelineStyleLocal"
              :disabled="saving"
              @update:model-value="timelineStyleLocal = $event"
            />
          </NFormItem>
        </NForm>
        <NForm v-show="activeGroup === 'rules'" label-placement="top" :show-require-mark="false">
          <NFormItem label="个人规则（soul）">
            <NInput
              v-model:value="personalRules"
              class="sun-field"
              type="textarea"
              placeholder="例：回答默认使用简体中文……"
              maxlength="4000"
              show-count
              :autosize="{ minRows: 15, maxRows: 15 }"
              :disabled="saving"
            />
          </NFormItem>
        </NForm>
        <NForm v-show="activeGroup === 'git'" label-placement="top" :show-require-mark="false">
          <NFormItem label="GitHub 基础地址">
            <NInput
              v-model:value="githubUrl"
              class="sun-field"
              placeholder="如 https://github.com"
              maxlength="255"
              :disabled="saving"
            />
          </NFormItem>
          <NFormItem label="GitHub PAT">
            <NInput
              v-model:value="githubToken"
              class="sun-field"
              type="password"
              show-password-on="click"
              placeholder="未配置"
              maxlength="255"
              :disabled="saving"
            />
          </NFormItem>
          <NFormItem label="内网 GitLab 基础地址">
            <NInput
              v-model:value="gitlabUrl"
              class="sun-field"
              placeholder="如 https://gitlab.example.com"
              maxlength="255"
              :disabled="saving"
            />
          </NFormItem>
          <NFormItem label="内网 GitLab PAT">
            <NInput
              v-model:value="gitlabToken"
              class="sun-field"
              type="password"
              show-password-on="click"
              placeholder="未配置"
              maxlength="255"
              :disabled="saving"
            />
          </NFormItem>
        </NForm>
      </div>
    </div>
    <template #footer>
      <div class="settings-footer">
        <NButton quaternary :disabled="saving" @click="close">取消</NButton>
        <NButton type="primary" :loading="saving" @click="handleSave">保存</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.settings-body {
  display: flex;
  gap: 16px;
  /* 锁定弹窗高度，切换分组不抖动；按个人规则文本域高度对齐 */
  height: 520px;
}

.settings-nav {
  width: 160px;
  flex-shrink: 0;
  border-right: 1px solid var(--sun-border);
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-right: 12px;
}

.settings-nav-item {
  padding: 8px 10px;
  border: none;
  background: transparent;
  color: var(--sun-text);
  text-align: left;
  cursor: pointer;
  font-size: var(--sun-font-base, 14px);
  border-radius: 6px;
}

.settings-nav-item:hover:not(:disabled) {
  color: var(--sun-text-strong, var(--sun-text));
}

.settings-nav-item.active {
  font-weight: 600;
  background: transparent;
}

.settings-nav-item:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.settings-panel {
  flex: 1;
  min-width: 0;
}

.settings-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
