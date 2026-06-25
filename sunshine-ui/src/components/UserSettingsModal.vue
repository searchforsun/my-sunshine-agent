<script setup lang="ts">
import { ref, watch } from 'vue'
import { NModal, NForm, NFormItem, NInput, NButton, useMessage } from 'naive-ui'
import { useAuthStore } from '../stores/authStore'
import ExecutionModeSelector from './chat/ExecutionModeSelector.vue'
import { useExecutionPreference } from '../composables/useExecutionPreference'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ 'update:show': [value: boolean] }>()

const auth = useAuthStore()
const message = useMessage()
const { globalDefault, setGlobalDefault } = useExecutionPreference()
const nickname = ref('')
const defaultMode = ref(globalDefault.value)
const saving = ref(false)

watch(
  () => props.show,
  (open) => {
    if (open) {
      nickname.value = auth.user?.nickname ?? ''
      defaultMode.value = globalDefault.value
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
    await auth.updateProfile(value)
    setGlobalDefault(defaultMode.value)
    message.success('资料已更新')
    close()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="账号设置"
    class="user-settings-modal"
    :style="{ width: 'min(420px, 92vw)' }"
    :mask-closable="!saving"
    @update:show="emit('update:show', $event)"
  >
    <NForm label-placement="top" :show-require-mark="false">
      <NFormItem label="用户名">
        <NInput :value="auth.user?.username" disabled />
      </NFormItem>
      <NFormItem label="昵称">
        <NInput
          v-model:value="nickname"
          placeholder="展示在侧栏的名称"
          maxlength="64"
          :disabled="saving"
          @keydown.enter="handleSave"
        />
      </NFormItem>
      <NFormItem label="默认执行模式">
        <div class="execution-mode-field">
          <ExecutionModeSelector
            variant="block"
            :model-value="defaultMode"
            :disabled="saving"
            @update:model-value="defaultMode = $event"
          />
          <p class="settings-hint">新建或无记忆会话时使用；已有会话恢复其最近一次选择。</p>
        </div>
      </NFormItem>
    </NForm>
    <template #footer>
      <div class="settings-footer">
        <NButton quaternary :disabled="saving" @click="close">取消</NButton>
        <NButton type="primary" :loading="saving" @click="handleSave">保存</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.settings-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.execution-mode-field {
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
