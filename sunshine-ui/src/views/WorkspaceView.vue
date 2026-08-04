<script setup lang="ts">
import { ref, onMounted, h } from 'vue'
import { NDataTable, NButton, NModal, NForm, NFormItem, NInput, NSelect, useMessage } from 'naive-ui'
import type { WorkspaceVO, CreateWorkspaceRequest } from '../api/workspaces'
import { listWorkspaces, createWorkspace, destroyWorkspace } from '../api/workspaces'
import { friendlyErrorMessage } from '../api/apiError'

const message = useMessage()
const workspaces = ref<WorkspaceVO[]>([])
const loading = ref(false)
const showCreate = ref(false)
const creating = ref(false)
const newName = ref('')
const newRepoUrl = ref('')

/** 硬件档位预设（与 Nacos agent.sandbox.profiles.full.allowed-presets 对齐；后端校验 SSOT） */
const HARDWARE_PRESETS = [
  { label: '1C / 1G', value: '1-1024' },
  { label: '2C / 2G', value: '2-2048' },
  { label: '4C / 4G', value: '4-4096' },
] as const
const newHardware = ref<string>('2-2048')

function presetToSpec(value: string): { cpus: number; memoryMb: number } {
  const [cpu, mem] = value.split('-')
  return { cpus: Number(cpu), memoryMb: Number(mem) }
}

const columns = [
  { key: 'name', title: '名称', width: 200 },
  { key: 'repoUrl', title: '仓库', ellipsis: { tooltip: true } },
  { key: 'status', title: '状态', width: 80 },
  {
    key: 'actions', title: '操作', width: 80,
    render(row: WorkspaceVO) {
      return h(NButton, {
        size: 'tiny', quaternary: true, type: 'error',
        onClick: () => handleDestroy(row),
      }, { default: () => '删除' })
    },
  },
]

async function fetchWorkspaces() {
  loading.value = true
  try { workspaces.value = await listWorkspaces() }
  catch (e) { message.error(friendlyErrorMessage(e, '加载工作区失败')) }
  finally { loading.value = false }
}

async function handleCreate() {
  const name = newName.value.trim()
  const url = newRepoUrl.value.trim()
  if (!name) { message.warning('请输入名称'); return }
  if (!url) { message.warning('请输入仓库地址'); return }
  creating.value = true
  try {
    const spec = presetToSpec(newHardware.value)
    const req: CreateWorkspaceRequest = {
      name, repoUrl: url, memoryMb: spec.memoryMb, cpus: spec.cpus,
    }
    await createWorkspace(req)
    message.success('工作区已创建')
    showCreate.value = false
    await fetchWorkspaces()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '创建失败'))
  } finally { creating.value = false }
}

async function handleDestroy(ws: WorkspaceVO) {
  try {
    await destroyWorkspace(ws.id)
    message.success('工作区已归档')
    await fetchWorkspaces()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '归档失败'))
  }
}

onMounted(fetchWorkspaces)
</script>

<template>
  <div class="workspace-page">
    <div class="page-header">
      <h2>工作区</h2>
      <NButton type="primary" @click="showCreate = true">新建工作区</NButton>
    </div>
    <NDataTable
      :columns="columns"
      :data="workspaces"
      :loading="loading"
    />
    <NModal
      :show="showCreate"
      preset="card"
      title="新建工作区"
      style="width:560px"
      @update:show="showCreate = $event"
    >
      <NForm label-placement="top">
        <NFormItem label="名称">
          <NInput v-model:value="newName" class="sun-field" placeholder="my-project" maxlength="128" :disabled="creating" />
        </NFormItem>
        <NFormItem label="仓库地址">
          <NInput v-model:value="newRepoUrl" class="sun-field" placeholder="https://github.com/user/repo" maxlength="512" :disabled="creating" />
        </NFormItem>
        <NFormItem label="硬件档位">
          <NSelect v-model:value="newHardware" :options="[...HARDWARE_PRESETS]" :disabled="creating" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NButton quaternary :disabled="creating" @click="showCreate = false">取消</NButton>
        <NButton type="primary" :loading="creating" @click="handleCreate">创建</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.workspace-page { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; }
</style>
