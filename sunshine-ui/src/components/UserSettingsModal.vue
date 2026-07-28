<script setup lang="ts">
import { ref, watch } from 'vue'
import { NModal, NForm, NFormItem, NInput, NButton, useMessage } from 'naive-ui'
import { useAuthStore } from '../stores/authStore'
import { useChatStore } from '../stores/chatStore'
import ExecutionModeSelector from './chat/ExecutionModeSelector.vue'
import WriteHitlModeSelector from './sandbox/WriteHitlModeSelector.vue'
import TenantSelector from './knowledge/TenantSelector.vue'
import { useExecutionPreference } from '../composables/useExecutionPreference'
import { useWriteHitlMode } from '../composables/useWriteHitlMode'
import { isWriteHitlMode, type WriteHitlMode } from '../api/writeHitlModes'
import { friendlyErrorMessage } from '../api/apiError'
import type { TenantId } from '../api/tenants'

type SettingsGroup = 'account' | 'chat' | 'rules'

const GROUPS: Array<{ key: SettingsGroup; label: string }> = [
  { key: 'account', label: '账号' },
  { key: 'chat', label: '对话偏好' },
  { key: 'rules', label: '个人规则' },
]

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ 'update:show': [value: boolean] }>()

const auth = useAuthStore()
const chatStore = useChatStore()
const message = useMessage()
const { globalDefault, setGlobalDefault } = useExecutionPreference()
const { globalDefault: writeHitlGlobal, setGlobalDefault: setWriteHitlGlobal } = useWriteHitlMode(
  () => chatStore.currentId,
)
const activeGroup = ref<SettingsGroup>('account')
const nickname = ref('')
const defaultMode = ref(globalDefault.value)
const defaultWriteHitl = ref<WriteHitlMode>(writeHitlGlobal.value)
const tenantId = ref<TenantId>('default')
const personalRules = ref('')
const saving = ref(false)

watch(
  () => props.show,
  (open) => {
    if (open) {
      activeGroup.value = 'account'
      nickname.value = auth.user?.nickname ?? ''
      defaultMode.value = globalDefault.value
      const fromAuth = auth.user?.defaultWriteHitlMode
      defaultWriteHitl.value = isWriteHitlMode(fromAuth) ? fromAuth : writeHitlGlobal.value
      tenantId.value = auth.user?.tenantId ?? 'default'
      personalRules.value = auth.user?.personalRules ?? ''
    }
  },
)

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
    await auth.updateProfile(value, tenantId.value, defaultWriteHitl.value, personalRules.value)
    setGlobalDefault(defaultMode.value)
    setWriteHitlGlobal(defaultWriteHitl.value)
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
            <div class="settings-field">
              <TenantSelector
                variant="block"
                :model-value="tenantId"
                :disabled="saving"
                @update:model-value="tenantId = $event"
              />
              <p class="settings-hint">保存后自动刷新登录凭证，无需重新登录。</p>
            </div>
          </NFormItem>
          <NFormItem label="默认执行模式">
            <div class="settings-field">
              <ExecutionModeSelector
                variant="block"
                :model-value="defaultMode"
                :disabled="saving"
                @update:model-value="defaultMode = $event"
              />
              <p class="settings-hint">已有会话保留其最近一次选择。</p>
            </div>
          </NFormItem>
          <NFormItem label="默认写操作确认">
            <div class="settings-field">
              <WriteHitlModeSelector
                variant="block"
                :model-value="defaultWriteHitl"
                :disabled="saving"
                @update:model-value="defaultWriteHitl = $event"
              />
              <p class="settings-hint">已有会话保留其最近一次选择；工作区临时覆盖不回写。</p>
            </div>
          </NFormItem>
        </NForm>
        <NForm v-show="activeGroup === 'rules'" label-placement="top" :show-require-mark="false">
          <NFormItem label="个人规则（soul）">
            <div class="settings-field">
              <NInput
                v-model:value="personalRules"
                class="sun-field"
                type="textarea"
                placeholder="例：回答默认使用简体中文……"
                maxlength="4000"
                show-count
                :autosize="{ minRows: 8, maxRows: 8 }"
                :disabled="saving"
              />
              <p class="settings-hint">注入你的所有对话系统提示；留空不注入，子 Agent 不继承。</p>
            </div>
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
  /* 锁定弹窗高度，切换分组不抖动；按最高面板（对话偏好 3 项 + 说明）对齐 */
  height: 460px;
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

.settings-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.settings-hint {
  margin: 0;
  font-size: var(--sun-font-xs, 11px);
  color: var(--sun-text-muted, #888);
  line-height: 1.5;
}
</style>
