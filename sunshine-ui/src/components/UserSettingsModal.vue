<script setup lang="ts">
import { ref, watch } from 'vue'
import { NModal, NForm, NFormItem, NInput, NButton, useMessage } from 'naive-ui'
import { useAuthStore } from '../stores/authStore'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ 'update:show': [value: boolean] }>()

const auth = useAuthStore()
const message = useMessage()
const nickname = ref('')
const saving = ref(false)

watch(
  () => props.show,
  (open) => {
    if (open) {
      nickname.value = auth.user?.nickname ?? ''
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
</style>
