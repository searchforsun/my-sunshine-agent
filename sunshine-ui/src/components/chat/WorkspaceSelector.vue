<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NModal, NButton, useMessage } from 'naive-ui'
import type { WorkspaceVO } from '../../api/workspaces'
import { listWorkspaces } from '../../api/workspaces'

defineProps<{ modelValue?: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  selected: [workspaceId: string]
}>()

const loading = ref(false)
const workspaces = ref<WorkspaceVO[]>([])
const message = useMessage()

async function fetchWorkspaces() {
  loading.value = true
  try { workspaces.value = await listWorkspaces() }
  catch { /* silently fail */ }
  finally { loading.value = false }
}

onMounted(fetchWorkspaces)

function select(ws: WorkspaceVO) {
  emit('selected', ws.id)
  emit('update:modelValue', false)
}

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <NModal
    :show="modelValue"
    preset="card"
    title="选择工作区"
    style="width:480px"
    @update:show="emit('update:modelValue', $event as boolean)"
  >
    <div v-if="loading" class="ws-loading">加载中...</div>
    <div v-else-if="workspaces.length === 0" class="ws-empty">
      暂无工作区，请先到 <router-link to="/workspaces" @click="close">工作区管理</router-link> 创建。
    </div>
    <div v-else class="ws-list">
      <button
        v-for="ws in workspaces"
        :key="ws.id"
        type="button"
        class="ws-item"
        @click="select(ws)"
      >
        <div class="ws-item-name">{{ ws.name }}</div>
        <div class="ws-item-repo">{{ ws.repoUrl }}</div>
      </button>
    </div>
    <template #footer>
      <NButton quaternary @click="close">取消</NButton>
    </template>
  </NModal>
</template>

<style scoped>
.ws-loading { padding: 16px; color: var(--sun-text-muted); text-align: center; }
.ws-empty { padding: 16px; color: var(--sun-text-muted); font-size: var(--sun-font-xs, 12px); }
.ws-list { display: flex; flex-direction: column; gap: 4px; }
.ws-item {
  background: transparent; border: 1px solid var(--sun-border); border-radius: 6px;
  padding: 10px 12px; cursor: pointer; text-align: left; width: 100%;
}
.ws-item:hover { border-color: var(--sun-accent); }
.ws-item-name { font-size: var(--sun-font-base, 14px); font-weight: 500; }
.ws-item-repo { font-size: var(--sun-font-xs, 12px); color: var(--sun-text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>

